package app.dita.core.knowledge;

import app.dita.core.events.EventPublisher;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Hybrid pipeline semantics (offline, synthetic): approval gate, eligibility, failure/retry, false-success guard. */
class ExtractionServiceTest {
    static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    final GroupScope g = new GroupScope("g-x");
    final Clock clock = Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC);
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryExtractionQueue queue = new InMemoryExtractionQueue();
    final AtomicInteger calls = new AtomicInteger();

    Message msg(String id, String author, int sec, String text, SourceKind kind) {
        Message m = new Message(id, g.groupId(), author, T0.plusSeconds(sec), null, text, kind, false);
        store.append(g, m);
        return m;
    }

    static Proposal.Item item(String predicate, String messageId, String quote, String subject) {
        return new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", "dr-levi", subject, predicate, null, null,
                List.of(new Proposal.Quote(messageId, quote)), List.of(), List.of());
    }

    KnowledgeProposer proposing(List<Proposal.Item> items) {
        return (s, b, t, c) -> { calls.incrementAndGet(); return new Proposal(items, "test-v1"); };
    }

    ExtractionService service(MessageStore s, KnowledgeProposer p) { return new ExtractionService(s, queue, p, EventPublisher.NONE, clock, 20); }

    @Test void nothingIsProposedWithoutApproval() {
        Message m = msg("m1", "dana", 0, "Dr. Levi on Herzl St. is excellent for babies", SourceKind.SYNTHETIC);
        var x = service(store, proposing(List.of(item("recommends", "m1", "Dr. Levi on Herzl St. is excellent", "Dr. Levi"))));
        x.enqueue(g, m);
        ExtractionBatch b = x.collect(g).get(0);
        assertTrue(x.process(g, b.id()).isEmpty(), "a COLLECTED batch is never processed");
        assertTrue(x.processApproved().isEmpty());
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> x.approve(g, b.id(), " "), "approval needs a reference");
        x.approve(g, b.id(), "op-1");
        assertEquals(ExtractionBatch.Status.DONE, x.process(g, b.id()).orElseThrow().status());
        assertEquals(1, calls.get());
        assertEquals(1, store.knowledge(g).size());
    }

    @Test void nonSyntheticMessagesAreNeverQueuedOrApproved() {
        Message platform = msg("p1", "dana", 0, "Dr. Levi on Herzl St. is excellent for babies", SourceKind.PLATFORM);
        var x = service(store, proposing(List.of()));
        assertFalse(x.enqueue(g, platform), "PLATFORM messages are not eligible (OPEN-6/C-1)");
        assertTrue(x.collect(g).isEmpty());
        queue.enqueue(g, new ExtractionBatch.Target("p1", null), T0);                   // even if queued by mistake…
        ExtractionBatch b = x.collect(g).get(0);
        assertThrows(IllegalStateException.class, () -> x.approve(g, b.id(), "op-1"), "…a batch with a PLATFORM message cannot be approved");
    }

    @Test void editedMessagesAreNeverSentAndCannotBeCited() {
        Message m = msg("m1", "dana", 0, "Dr. Levi on Herzl St. is excellent for babies", SourceKind.SYNTHETIC);
        store.edit(g, "m1", "Dr. Levi on Herzl St. is excellent for babies (edited)", T0.plusSeconds(30));
        List<List<Message>> sent = new ArrayList<>();
        var x = service(store, (s, b, t, c) -> { sent.add(t); return new Proposal(List.of(item("recommends", "m1", "Dr. Levi on Herzl St. is excellent", "Dr. Levi")), "v"); });
        x.enqueue(g, m);
        ExtractionBatch b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-1");
        var r = x.process(g, b.id()).orElseThrow();
        assertTrue(sent.isEmpty(), "an edited message is not sent (E-2)");
        assertEquals(0, r.accepted());
        assertTrue(store.knowledge(g).isEmpty());
    }

    @Test void aFailedBatchStoresNothingAndCanBeRetriedExplicitly() {
        Message m = msg("m1", "dana", 0, "Dr. Levi on Herzl St. is excellent for babies", SourceKind.SYNTHETIC);
        AtomicInteger n = new AtomicInteger();
        var x = service(store, (s, b, t, c) -> {
            if (n.incrementAndGet() == 1) throw new IllegalStateException("synthetic transport failure");
            return new Proposal(List.of(item("recommends", "m1", "Dr. Levi on Herzl St. is excellent", "Dr. Levi")), "v");
        });
        x.enqueue(g, m);
        ExtractionBatch b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-1");
        var failed = x.process(g, b.id()).orElseThrow();
        assertEquals(ExtractionBatch.Status.FAILED, failed.status());
        assertEquals("IllegalStateException", failed.error(), "errors are recorded by class only (no content)");
        assertTrue(store.knowledge(g).isEmpty());
        assertTrue(x.processApproved().isEmpty(), "a FAILED batch is not retried automatically");
        x.retry(g, b.id());
        assertEquals(ExtractionBatch.Status.DONE, x.process(g, b.id()).orElseThrow().status());
        assertEquals(1, store.knowledge(g).size());
        assertEquals(2, x.batch(g, b.id()).orElseThrow().attempts());
    }

    /**
     * Priority 1: a backend that accepts addKnowledge but silently drops RFC-002 fields (as a pre-V5 PostgreSQL
     * schema would) must make the batch FAIL and roll back. It can never report successful persistence.
     */
    @Test void aBackendThatDropsAssertionFieldsCannotReportSuccess() {
        Message m = msg("m1", "dana", 0, "Dr. Levi on Herzl St. is excellent for babies", SourceKind.SYNTHETIC);
        MessageStore lossy = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(), new Class<?>[]{MessageStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addKnowledge")) {
                        KnowledgeItem k = (KnowledgeItem) args[1];
                        args = new Object[]{args[0], new KnowledgeItem(k.id(), k.groupId(), k.kind(), k.subject(), k.statement(), k.polarity(),
                                k.method(), k.evidence().stream().map(e -> new KnowledgeItem.Evidence(e.messageId(), e.basis(), e.viaLinkKey(), e.sourceDeleted())).toList(),
                                k.statementRedacted())};                                    // what a pre-V5 schema can hold
                    }
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    try { return method.invoke(store, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        var x = service(lossy, proposing(List.of(item("recommends", "m1", "Dr. Levi on Herzl St. is excellent", "Dr. Levi"))));
        x.enqueue(g, m);
        ExtractionBatch b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-1");
        var r = x.process(g, b.id()).orElseThrow();
        assertEquals(ExtractionBatch.Status.FAILED, r.status(), "read-after-write detects the lost fields");
        assertTrue(store.knowledge(g).isEmpty(), "and the write was rolled back");
    }

    @Test void defaultStoreMethodsFailExplicitly() {
        MessageStore bare = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(), new Class<?>[]{MessageStore.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    throw new AssertionError("not used");
                });
        assertThrows(UnsupportedOperationException.class, () -> bare.relations(g));
        assertThrows(UnsupportedOperationException.class, () -> bare.answer(g, "r1"));
        assertThrows(UnsupportedOperationException.class, () -> bare.recordAnswer(g, null));
        assertThrows(UnsupportedOperationException.class, () -> bare.addRelation(g, null));
    }

    @Test void validatorRulesRejectUnsupportedProposals() {
        Message m1 = msg("m1", "dana", 0, "My neighbor says Dr. Levi on Herzl St. is excellent for babies", SourceKind.SYNTHETIC);
        Message m2 = msg("m2", "ron", 10, "Does anyone know if Dr. Levi sees teenagers?", SourceKind.SYNTHETIC);
        var v = new EvidenceValidator();
        var targets = new java.util.LinkedHashMap<String, Message>();
        targets.put("m1", m1); targets.put("m2", m2);
        Proposal p = new Proposal(List.of(
                new Proposal.Item("REPORTED_CLAIM", "HEARSAY", "dr-levi", "Dr. Levi", "recommends", null, "my neighbor",
                        List.of(new Proposal.Quote("m1", "Dr. Levi on Herzl St. is excellent for babies")), List.of(), List.of()),   // ok: hearsay in span
                new Proposal.Item("REPORTED_CLAIM", "HEARSAY", "dr-levi", "Dr. Levi", "recommends", null, "the ministry",
                        List.of(new Proposal.Quote("m1", "Dr. Levi on Herzl St. is excellent for babies")), List.of(), List.of()),   // source not in span
                new Proposal.Item("QUESTION", "ASSERTED", "dr-levi", "Dr. Levi", "asks", null, null,
                        List.of(new Proposal.Quote("m2", "Does anyone know if Dr. Levi sees teenagers?")), List.of(), List.of()),     // ok
                new Proposal.Item("QUESTION", "ASSERTED", "dr-levi", "Dr. Levi", "asks", null, null,
                        List.of(new Proposal.Quote("m1", "Dr. Levi on Herzl St. is excellent")), List.of(), List.of()),             // no '?'
                item("recommends", "m1", "excellent", "Dr. Levi"),                                                                   // too short
                item("recommends", "m1", "Dr. Levi on Herzl St. is excellent", "Dr. Cohen"),                                        // subject absent
                new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", "dr-levi", "Dr. Levi", "recommends", null, null,
                        List.of(new Proposal.Quote("m1", "Dr. Levi on Herzl St. is excellent")),
                        List.of(new Proposal.Qualifier("POPULATION", "m2", "teenagers")), List.of()),                              // qualifier outside evidence
                new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", "dr-levi", "Dr. Levi", "is_true", null, null,
                        List.of(new Proposal.Quote("m1", "Dr. Levi on Herzl St. is excellent")), List.of(), List.of()),            // no "fact" predicates
                new Proposal.Item("CORRECTION", "ASSERTED", "dr-levi", "Dr. Levi", "corrects", null, null,
                        List.of(new Proposal.Quote("m1", "Dr. Levi on Herzl St. is excellent")), List.of(), List.of())),           // refinement not enabled
                "v");
        var r = v.validate(g, "b1", p, targets, java.util.Map.of(), java.util.Set.of());
        assertEquals(2, r.accepted().size(), "accepted: the hearsay item and the question; rejected: " + r.rejections());
        var hearsay = r.accepted().get(0).assertion();
        assertEquals(app.dita.core.model.Assertion.Certainty.HEARSAY, hearsay.certainty());
        assertEquals("dana", hearsay.speakerId(), "the speaker is the author, not the relayed source");
        assertEquals("my neighbor", hearsay.attributedTo());
        assertEquals(List.of("hearsay source does not occur in the evidence", "a question needs a question mark in the quote",
                "quote is too short", "subject does not occur in the evidence", "qualifier outside the evidence",
                "predicate outside the closed vocabulary", "status CORRECTION is not enabled in this increment"),
                r.rejections().stream().map(EvidenceValidator.Rejection::reason).toList());
    }
}
