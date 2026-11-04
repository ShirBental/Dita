package app.dita.store.postgres;

import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.AnswerRecord;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageEvent;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessageStore contract, derived from InMemoryMessageStore (authoritative reference semantics). Runs against every
 * implementation. Synthetic data only. Timestamps are whole seconds (Postgres timestamptz keeps microseconds).
 * Deletion times are consistent with the DEC-012 window because the schema enforces it.
 */
abstract class MessageStoreContractTest {
    static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    protected MessageStore store;
    protected GroupScope g;

    protected abstract MessageStore createStore();
    /** A fresh group that exists in the store (Postgres: a chat_group row). Unique per call. */
    protected abstract GroupScope newGroup(String label);
    /** RFC-002 cases need the V5 schema; the Postgres variant skips them (assumption) on a database without V5. */
    protected void assumeKnowledgeV2() { }
    /** DEC-038: Assertion.Validity.UNRESOLVED needs V6 on Postgres; the Postgres variant skips (assumption) without it. */
    protected void assumeUnresolvedValidity() { }

    @BeforeEach void setUp() {
        store = createStore();
        g = newGroup("parents");
    }

    static Message msg(GroupScope s, String id, String author, int minute, String replyTo, String text) {
        return new Message(id, s.groupId(), author, T0.plus(Duration.ofMinutes(minute)), replyTo, text, SourceKind.SYNTHETIC, false);
    }

    static Instant at(int minute) { return T0.plus(Duration.ofMinutes(minute)); }

    static MessageLink inferred(GroupScope s, String from, String to, double score, Set<String> deps) {
        return new MessageLink(s.groupId(), from, to, MessageLink.Kind.INFERRED_REFERENCE, MessageLink.Status.CANDIDATE,
                MessageLink.Method.RULE, score, Map.of("lexical", 0.5, "answered_author", 0.25), deps);
    }

    void seed() {
        store.append(g, msg(g, "m1", "alice", 0, null, "Which pediatrician do people use?"));
        store.append(g, msg(g, "m2", "bob", 2, "m1", "Dr. Synthetic Example is great."));
        store.append(g, msg(g, "m3", "carol", 4, null, "Agree, very patient."));
    }

    // ------------------------------------------------------------------ append / find / history

    @Test void appendThenFindLiveAndHistory() {
        seed();
        assertEquals(Optional.of(msg(g, "m2", "bob", 2, "m1", "Dr. Synthetic Example is great.")), store.find(g, "m2"));
        assertEquals(List.of("m1", "m2", "m3"), store.live(g).stream().map(Message::id).toList());
        assertEquals(List.of(new MessageEvent(g.groupId(), "m1", MessageEvent.Kind.CREATED, "Which pediatrician do people use?", at(0))),
                store.history(g, "m1"));
        assertEquals(Optional.empty(), store.find(g, "nope"));
    }

    @Test void appendIsIdempotentForRedelivery() {
        seed();
        store.append(g, msg(g, "m1", "alice", 0, null, "Which pediatrician do people use?"));
        store.append(g, msg(g, "m1", "alice", 0, null, "different text, same delivery identity"));
        assertEquals(1, store.history(g, "m1").size());
        assertEquals("Which pediatrician do people use?", store.find(g, "m1").orElseThrow().text());
    }

    @Test void conflictingDuplicateIsRefused() {
        seed();
        assertThrows(IllegalStateException.class, () -> store.append(g, msg(g, "m1", "mallory", 0, null, "x")));
        assertThrows(IllegalStateException.class, () -> store.append(g, msg(g, "m1", "alice", 1, null, "x")));
        assertEquals("alice", store.find(g, "m1").orElseThrow().authorId());
    }

    @Test void replyTargetMustExistInGroup() {
        assertThrows(IllegalStateException.class, () -> store.append(g, msg(g, "r1", "bob", 1, "ghost", "reply")));
        assertEquals(Optional.empty(), store.find(g, "r1"));
    }

    @Test void appendOutsideScopeIsAnIsolationViolation() {
        GroupScope other = newGroup("other");
        assertThrows(IsolationViolation.class, () -> store.append(g, msg(other, "x1", "eve", 0, null, "hi")));
        assertTrue(store.live(other).isEmpty());
        assertTrue(store.live(g).isEmpty());
    }

    @Test void liveIsChronologicalThenById() {
        store.append(g, msg(g, "b", "u", 5, null, "t"));
        store.append(g, msg(g, "a", "u", 5, null, "t"));
        store.append(g, msg(g, "c", "u", 1, null, "t"));
        assertEquals(List.of("c", "a", "b"), store.live(g).stream().map(Message::id).toList());
    }

    @Test void windowBeforeReturnsLiveEarlierMessagesOldestFirst() {
        seed();
        store.append(g, msg(g, "m4", "dan", 30, null, "later"));
        store.tombstone(g, "m2", at(20), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);
        assertEquals(List.of("m1", "m3"), store.windowBefore(g, "m4", 5).stream().map(Message::id).toList());
        assertEquals(List.of("m3"), store.windowBefore(g, "m4", 1).stream().map(Message::id).toList());
        assertEquals(List.of(), store.windowBefore(g, "m1", 5));
        assertThrows(NoSuchElementException.class, () -> store.windowBefore(g, "ghost", 5));
    }

    // ------------------------------------------------------------------ edit / tombstone

    @Test void editKeepsHistory() {
        seed();
        store.edit(g, "m2", "Dr. Synthetic Example is great (edited).", at(3));
        assertEquals("Dr. Synthetic Example is great (edited).", store.find(g, "m2").orElseThrow().text());
        assertEquals(List.of(MessageEvent.Kind.CREATED, MessageEvent.Kind.EDITED),
                store.history(g, "m2").stream().map(MessageEvent::kind).toList());
        assertThrows(NoSuchElementException.class, () -> store.edit(g, "ghost", "x", at(3)));
    }

    @Test void tombstoneNullsTextEverywhereAndReturnsAllVersions() {
        seed();
        store.edit(g, "m2", "v2", at(3));
        List<String> versions = store.tombstone(g, "m2", at(30), DeletionMode.LATE_TEXT_ONLY, DeletionSource.PLATFORM_EVENT);
        assertEquals(List.of("Dr. Synthetic Example is great.", "v2"), versions);
        Message m = store.find(g, "m2").orElseThrow();
        assertTrue(m.deleted());
        assertNull(m.text());
        List<MessageEvent> h = store.history(g, "m2");
        assertEquals(List.of(MessageEvent.Kind.CREATED, MessageEvent.Kind.EDITED, MessageEvent.Kind.DELETED),
                h.stream().map(MessageEvent::kind).toList());
        assertTrue(h.stream().allMatch(e -> e.text() == null));
        assertEquals(at(30), h.get(2).occurredAt());
        assertFalse(store.live(g).stream().anyMatch(x -> x.id().equals("m2")));
    }

    @Test void earlyPurgeTombstone() {
        seed();
        assertEquals(List.of("Agree, very patient."),
                store.tombstone(g, "m3", at(5), DeletionMode.EARLY_PURGE, DeletionSource.FORGET_COMMAND));
        assertTrue(store.find(g, "m3").orElseThrow().deleted());
    }

    @Test void editAfterDeleteAndDoubleDeleteAreRefused() {
        seed();
        store.tombstone(g, "m1", at(10), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);
        assertThrows(IllegalStateException.class, () -> store.edit(g, "m1", "resurrect", at(11)));
        assertThrows(IllegalStateException.class,
                () -> store.tombstone(g, "m1", at(12), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND));
        assertNull(store.find(g, "m1").orElseThrow().text());
        assertThrows(NoSuchElementException.class,
                () -> store.tombstone(g, "ghost", at(12), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND));
    }

    @Test void redeliveryAfterDeleteDoesNotRestoreText() {
        seed();
        store.tombstone(g, "m1", at(10), DeletionMode.LATE_TEXT_ONLY, DeletionSource.PLATFORM_EVENT);
        store.append(g, msg(g, "m1", "alice", 0, null, "Which pediatrician do people use?"));
        assertNull(store.find(g, "m1").orElseThrow().text());
        assertTrue(store.history(g, "m1").stream().allMatch(e -> e.text() == null));
    }

    // ------------------------------------------------------------------ links

    @Test void addLinkIsIdempotentByKeyFirstWriteWins() {
        seed();
        MessageLink first = inferred(g, "m3", "m2", 0.6, Set.of("m1"));
        store.addLink(g, first);
        store.addLink(g, inferred(g, "m3", "m2", 0.9, Set.of()));
        assertEquals(List.of(first), store.links(g));
        assertEquals(Set.of("m1", "m2", "m3"), store.links(g).get(0).evidenceMessageIds());
    }

    @Test void explicitReplyLinkRoundTrips() {
        seed();
        MessageLink reply = new MessageLink(g.groupId(), "m2", "m1", MessageLink.Kind.EXPLICIT_REPLY,
                MessageLink.Status.FACT, MessageLink.Method.PLATFORM, 1.0, Map.of());
        store.addLink(g, reply);
        store.addLink(g, inferred(g, "m3", "m1", 0.4, Set.of()));
        assertEquals(List.of(reply.key(), "m3->m1:INFERRED_REFERENCE"), store.links(g).stream().map(MessageLink::key).toList());
        assertEquals(reply, store.links(g).get(0));
    }

    @Test void linkEvidenceMustBeLiveAndInGroup() {
        seed();
        store.tombstone(g, "m1", at(10), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);
        assertThrows(IllegalStateException.class, () -> store.addLink(g, inferred(g, "m3", "m2", 0.5, Set.of("m1"))));
        assertThrows(NoSuchElementException.class, () -> store.addLink(g, inferred(g, "m3", "ghost", 0.5, Set.of())));
        assertTrue(store.links(g).isEmpty());
    }

    @Test void removeLinksByKey() {
        seed();
        store.addLink(g, inferred(g, "m3", "m2", 0.5, Set.of()));
        store.addLink(g, inferred(g, "m3", "m1", 0.5, Set.of()));
        store.removeLinks(g, List.of("m3->m2:INFERRED_REFERENCE", "unknown-key"));
        store.removeLinks(g, List.of());
        assertEquals(List.of("m3->m1:INFERRED_REFERENCE"), store.links(g).stream().map(MessageLink::key).toList());
    }

    // ------------------------------------------------------------------ knowledge

    KnowledgeItem rec(String id, List<String> sources) {
        return KnowledgeItem.of(id, g.groupId(), KnowledgeItem.Kind.RECOMMENDATION, "pediatrician",
                "Dr. Synthetic Example is recommended", 1, MessageLink.Method.RULE, sources);
    }

    @Test void knowledgeRoundTripsWithDuplicateAndProvisionalEvidence() {
        seed();
        MessageLink l = inferred(g, "m3", "m2", 0.5, Set.of());
        store.addLink(g, l);
        KnowledgeItem k = rec("k1", List.of("m2", "m2")).withProvisional("m3", l.key());
        store.addKnowledge(g, k);
        store.addKnowledge(g, rec("k0", List.of("m1")));
        assertEquals(List.of(k, rec("k0", List.of("m1"))), store.knowledge(g));
        assertEquals(Optional.of(k), store.knowledgeById(g, "k1"));
        assertEquals(Optional.empty(), store.knowledgeById(g, "nope"));
        assertEquals(Set.of("m2"), store.knowledge(g).get(0).independentSupport());
    }

    @Test void addKnowledgeValidates() {
        seed();
        store.tombstone(g, "m1", at(10), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);
        assertThrows(IllegalStateException.class, () -> store.addKnowledge(g, rec("k1", List.of("m1"))));
        assertThrows(NoSuchElementException.class, () -> store.addKnowledge(g, rec("k1", List.of("ghost"))));
        assertThrows(IllegalStateException.class,
                () -> store.addKnowledge(g, rec("k1", List.of("m2")).withProvisional("m3", "m3->m2:INFERRED_REFERENCE")));
        store.addKnowledge(g, rec("k1", List.of("m2")));
        assertThrows(IllegalStateException.class, () -> store.addKnowledge(g, rec("k1", List.of("m3"))));
        assertEquals(List.of(rec("k1", List.of("m2"))), store.knowledge(g));
        GroupScope other = newGroup("other");
        assertThrows(IsolationViolation.class, () -> store.addKnowledge(g, KnowledgeItem.of("k9", other.groupId(),
                KnowledgeItem.Kind.CLAIM, null, "s", 0, MessageLink.Method.RULE, List.of("m2"))));
    }

    @Test void replaceAndRemoveKnowledge() {
        seed();
        store.addKnowledge(g, rec("k1", List.of("m2", "m3")));
        store.addKnowledge(g, rec("k2", List.of("m3")));
        store.tombstone(g, "m2", at(30), DeletionMode.LATE_TEXT_ONLY, DeletionSource.PLATFORM_EVENT);
        KnowledgeItem updated = rec("k1", List.of("m2", "m3")).withSourceDeleted("m2").redacted(true);
        store.replaceKnowledge(g, updated);
        assertEquals(List.of(updated, rec("k2", List.of("m3"))), store.knowledge(g));   // position kept
        assertTrue(store.knowledge(g).get(0).statementRedacted());
        assertNull(store.knowledge(g).get(0).subject());
        assertEquals(Set.of("m2"), store.knowledge(g).get(0).deletedSourceIds());
        assertThrows(NoSuchElementException.class, () -> store.replaceKnowledge(g, rec("ghost", List.of("m3"))));
        store.removeKnowledge(g, "k1");
        store.removeKnowledge(g, "never-existed");
        assertEquals(List.of("k2"), store.knowledge(g).stream().map(KnowledgeItem::id).toList());
    }

    // ------------------------------------------------------------------ atomicity (N-21)

    @Test void atomicallyRollsBackOnFailure() {
        seed();
        store.addKnowledge(g, rec("k1", List.of("m2")));
        assertThrows(IllegalStateException.class, () -> store.atomically(g, () -> {
            store.append(g, msg(g, "m9", "alice", 9, null, "will be rolled back"));
            store.tombstone(g, "m2", at(30), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);
            store.removeKnowledge(g, "k1");
            throw new IllegalStateException("boom");
        }));
        assertEquals(Optional.empty(), store.find(g, "m9"));
        assertEquals("Dr. Synthetic Example is great.", store.find(g, "m2").orElseThrow().text());
        assertEquals(1, store.history(g, "m2").size());
        assertEquals(List.of("k1"), store.knowledge(g).stream().map(KnowledgeItem::id).toList());
        assertEquals("ok", store.atomically(g, () -> { store.edit(g, "m2", "v2", at(3)); return "ok"; }));
        assertEquals("v2", store.find(g, "m2").orElseThrow().text());
    }

    // ------------------------------------------------------------------ Dita replies

    @Test void ditaRepliesAreRecordedSortedAndOverwritten() {
        seed();
        store.recordDitaReply(g, "r2", List.of("m1", "m2"));
        store.recordDitaReply(g, "r1", List.of("m2"));
        assertEquals(List.of("r1", "r2"), store.ditaRepliesCiting(g, "m2"));
        assertEquals(List.of("r2"), store.ditaRepliesCiting(g, "m1"));
        store.recordDitaReply(g, "r2", List.of("m3"));
        assertEquals(List.of(), store.ditaRepliesCiting(g, "m1"));
        assertEquals(List.of("r2"), store.ditaRepliesCiting(g, "m3"));
        assertThrows(NoSuchElementException.class, () -> store.recordDitaReply(g, "r3", List.of("ghost")));
        assertEquals(List.of(), store.ditaRepliesCiting(g, "ghost"));
    }

    // ------------------------------------------------------------------ isolation (store level)

    @Test void groupsNeverSeeEachOther() {
        GroupScope b = newGroup("other");
        seed();
        store.append(b, msg(b, "m1", "zed", 0, null, "Group B's own m1"));
        store.append(b, msg(b, "only-b", "zed", 1, null, "secret-ish synthetic text"));
        store.addLink(b, inferred(b, "only-b", "m1", 0.5, Set.of()));
        store.addKnowledge(b, KnowledgeItem.of("kb", b.groupId(), KnowledgeItem.Kind.CLAIM, null, "b claim", 0,
                MessageLink.Method.RULE, List.of("only-b")));
        store.recordDitaReply(b, "rb", List.of("m1"));

        assertEquals("alice", store.find(g, "m1").orElseThrow().authorId());
        assertEquals("zed", store.find(b, "m1").orElseThrow().authorId());
        assertEquals(Optional.empty(), store.find(g, "only-b"));
        assertEquals(List.of("m1", "m2", "m3"), store.live(g).stream().map(Message::id).toList());
        assertEquals(1, store.history(g, "m1").size());
        assertEquals(List.of(), store.history(g, "only-b"));
        assertTrue(store.links(g).isEmpty());
        assertTrue(store.knowledge(g).isEmpty());
        assertEquals(Optional.empty(), store.knowledgeById(g, "kb"));
        assertEquals(List.of(), store.ditaRepliesCiting(g, "m1"));

        // Writes in A's scope cannot reach B's rows.
        assertThrows(NoSuchElementException.class, () -> store.edit(g, "only-b", "x", at(5)));
        assertThrows(NoSuchElementException.class,
                () -> store.tombstone(g, "only-b", at(30), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND));
        assertThrows(NoSuchElementException.class, () -> store.addKnowledge(g, rec("ka", List.of("only-b"))));
        assertThrows(NoSuchElementException.class, () -> store.recordDitaReply(g, "ra", List.of("only-b")));
        assertThrows(NoSuchElementException.class, () -> store.replaceKnowledge(g, rec("kb", List.of("m1"))));
        store.removeKnowledge(g, "kb");
        store.removeLinks(g, List.of("only-b->m1:INFERRED_REFERENCE"));
        store.tombstone(g, "m1", at(30), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);

        assertEquals("Group B's own m1", store.find(b, "m1").orElseThrow().text());
        assertEquals(1, store.links(b).size());
        assertEquals(List.of("kb"), store.knowledge(b).stream().map(KnowledgeItem::id).toList());
        assertEquals(List.of("rb"), store.ditaRepliesCiting(b, "m1"));
    }

    // ------------------------------------------------------------------ knowledge v2 (RFC-002 / V5)

    /** Extracted-style item: assertion with an ASSERTS span, a QUALIFIES span and a qualifier span (synthetic). */
    KnowledgeItem extracted(GroupScope s, String id, String speaker, String msgId, int start, int end) {
        Assertion a = new Assertion(Assertion.Status.ATTRIBUTED_STATEMENT, Assertion.Certainty.ASSERTED, speaker, null,
                "ent-dr-example", "Dr. Synthetic Example", "recommends",
                List.of(new Assertion.Qualifier(Assertion.Qualifier.Kind.CONTEXT, "m3", 0, 5)),
                List.of(new Assertion.Entity("ent-dr-example", "Dr. Synthetic Example")),
                at(2), Assertion.Validity.ACTIVE, "muse-spark-1.3/extract-v2", "xb-synthetic-0001");
        return new KnowledgeItem(id, s.groupId(), KnowledgeItem.Kind.RECOMMENDATION, "pediatrician",
                "bob recommends Dr. Synthetic Example", 1, MessageLink.Method.LLM,
                List.of(KnowledgeItem.Evidence.span(msgId, KnowledgeItem.Role.ASSERTS, start, end),
                        KnowledgeItem.Evidence.span("m3", KnowledgeItem.Role.QUALIFIES, 0, 5),
                        KnowledgeItem.Evidence.verified("m1")),
                false, a);
    }

    KnowledgeItem hearsay(GroupScope s, String id) {
        Assertion a = new Assertion(Assertion.Status.REPORTED_CLAIM, Assertion.Certainty.HEARSAY, "carol", "my neighbour",
                "ent-dr-example", null, "states", List.of(), List.of(), at(4), Assertion.Validity.ACTIVE, "rule", null);
        return new KnowledgeItem(id, s.groupId(), KnowledgeItem.Kind.CLAIM, null, "carol relays a claim", 0,
                MessageLink.Method.RULE, List.of(KnowledgeItem.Evidence.span("m3", KnowledgeItem.Role.ASSERTS, 0, 20)), false, a);
    }

    KnowledgeRelation relation(GroupScope s, String from, String to, KnowledgeRelation.Kind kind, String evidence) {
        return new KnowledgeRelation(s.groupId(), from, to, kind, evidence);
    }

    @Test void assertionAndSpansRoundTrip() {
        assumeKnowledgeV2();
        seed();
        KnowledgeItem k = extracted(g, "k1", "bob", "m2", 0, 30);
        store.addKnowledge(g, k);
        store.addKnowledge(g, hearsay(g, "k2"));
        store.addKnowledge(g, rec("k3", List.of("m1")));                       // legacy item next to v2 items
        assertEquals(List.of(k, hearsay(g, "k2"), rec("k3", List.of("m1"))), store.knowledge(g));
        KnowledgeItem back = store.knowledgeById(g, "k1").orElseThrow();
        assertEquals(k.assertion(), back.assertion());
        assertEquals(List.of(KnowledgeItem.Role.ASSERTS, KnowledgeItem.Role.QUALIFIES, KnowledgeItem.Role.ASSERTS),
                back.evidence().stream().map(KnowledgeItem.Evidence::role).toList());
        assertEquals(List.of(0, 0, -1), back.evidence().stream().map(KnowledgeItem.Evidence::spanStart).toList());
        assertEquals(List.of(30, 5, -1), back.evidence().stream().map(KnowledgeItem.Evidence::spanEnd).toList());
        assertNull(store.knowledgeById(g, "k3").orElseThrow().assertion());

        // Deletion processing: superseded + late-deleted source + redaction keeps the assertion; back to legacy clears it.
        store.tombstone(g, "m2", at(30), DeletionMode.LATE_TEXT_ONLY, DeletionSource.PLATFORM_EVENT);
        KnowledgeItem updated = k.withAssertion(k.assertion().superseded().withQualifiers(List.of()))
                .withSourceDeleted("m2").redacted(true);
        store.replaceKnowledge(g, updated);
        assertEquals(updated, store.knowledgeById(g, "k1").orElseThrow());
        assertEquals(Assertion.Validity.SUPERSEDED, store.knowledgeById(g, "k1").orElseThrow().assertion().validity());
        KnowledgeItem legacy = rec("k3", List.of("m1", "m3"));
        store.replaceKnowledge(g, legacy);
        assertEquals(legacy, store.knowledgeById(g, "k3").orElseThrow());
        assertEquals(List.of("k1", "k2", "k3"), store.knowledge(g).stream().map(KnowledgeItem::id).toList());
    }

    /** DEC-038 (N-110): UNRESOLVED round-trips and can be superseded again; the store reports it can hold it. */
    @Test void unresolvedValidityRoundTrips() {
        assumeKnowledgeV2();
        assumeUnresolvedValidity();
        seed();
        KnowledgeItem k = extracted(g, "k1", "bob", "m2", 0, 30);
        store.addKnowledge(g, k);
        assertTrue(store.supportsUnresolvedValidity());
        KnowledgeItem unresolved = k.withAssertion(k.assertion().unresolved());
        store.replaceKnowledge(g, unresolved);
        assertEquals(unresolved, store.knowledgeById(g, "k1").orElseThrow());
        assertEquals(Assertion.Validity.UNRESOLVED, store.knowledge(g).get(0).assertion().validity());
        store.replaceKnowledge(g, k.withAssertion(k.assertion().superseded()));
        assertEquals(Assertion.Validity.SUPERSEDED, store.knowledgeById(g, "k1").orElseThrow().assertion().validity());
    }

    @Test void relationsAreIdempotentByKeyAndValidated() {
        assumeKnowledgeV2();
        seed();
        store.addKnowledge(g, extracted(g, "k1", "bob", "m2", 0, 30));
        store.addKnowledge(g, hearsay(g, "k2"));
        KnowledgeRelation first = relation(g, "k2", "k1", KnowledgeRelation.Kind.QUALIFIES, "m3");
        store.addRelation(g, first);
        store.addRelation(g, relation(g, "k2", "k1", KnowledgeRelation.Kind.QUALIFIES, null));   // same key: first wins
        KnowledgeRelation other = relation(g, "k1", "k2", KnowledgeRelation.Kind.SUPPORTS, null);
        store.addRelation(g, other);
        assertEquals(List.of(first, other), store.relations(g));
        assertThrows(IllegalStateException.class, () -> store.addRelation(g, relation(g, "k1", "ghost", KnowledgeRelation.Kind.SUPPORTS, null)));
        assertThrows(NoSuchElementException.class, () -> store.addRelation(g, relation(g, "k1", "k2", KnowledgeRelation.Kind.CORRECTS, "ghost")));
        GroupScope b = newGroup("other");
        assertThrows(IsolationViolation.class, () -> store.addRelation(g, relation(b, "k1", "k2", KnowledgeRelation.Kind.CONFIRMS, null)));
        store.removeRelations(g, List.of(first.key(), "unknown|SUPPORTS|key"));
        store.removeRelations(g, List.of());
        assertEquals(List.of(other), store.relations(g));
    }

    @Test void removeKnowledgeCascadesRelations() {
        assumeKnowledgeV2();
        seed();
        store.addKnowledge(g, extracted(g, "k1", "bob", "m2", 0, 30));
        store.addKnowledge(g, hearsay(g, "k2"));
        store.addKnowledge(g, rec("k3", List.of("m1")));
        store.addRelation(g, relation(g, "k1", "k2", KnowledgeRelation.Kind.SUPPORTS, null));
        store.addRelation(g, relation(g, "k3", "k1", KnowledgeRelation.Kind.ANSWERS, "m2"));
        store.addRelation(g, relation(g, "k2", "k3", KnowledgeRelation.Kind.CONTRADICTS, null));
        store.removeKnowledge(g, "k1");
        assertEquals(List.of("k2|CONTRADICTS|k3"), store.relations(g).stream().map(KnowledgeRelation::key).toList());
        store.removeKnowledge(g, "k3");
        assertTrue(store.relations(g).isEmpty());
        assertEquals(List.of("k2"), store.knowledge(g).stream().map(KnowledgeItem::id).toList());
    }

    @Test void atomicallyRollsBackKnowledgeV2() {
        assumeKnowledgeV2();
        seed();
        store.addKnowledge(g, extracted(g, "k1", "bob", "m2", 0, 30));
        store.addKnowledge(g, hearsay(g, "k2"));
        KnowledgeRelation r = relation(g, "k2", "k1", KnowledgeRelation.Kind.QUALIFIES, "m3");
        store.addRelation(g, r);
        assertThrows(IllegalStateException.class, () -> store.atomically(g, () -> {
            store.addKnowledge(g, rec("k3", List.of("m1")));
            store.addRelation(g, relation(g, "k3", "k1", KnowledgeRelation.Kind.SUPPORTS, null));
            store.recordAnswer(g, new AnswerRecord(g.groupId(), "a1", List.of("k1"), List.of("m2"), at(10)));
            store.removeKnowledge(g, "k2");                                    // cascades r inside the transaction
            store.replaceKnowledge(g, extracted(g, "k1", "bob", "m2", 0, 30).withAssertion(null));
            throw new IllegalStateException("boom");
        }));
        assertEquals(List.of("k1", "k2"), store.knowledge(g).stream().map(KnowledgeItem::id).toList());
        assertEquals(extracted(g, "k1", "bob", "m2", 0, 30), store.knowledgeById(g, "k1").orElseThrow());
        assertEquals(List.of(r), store.relations(g));
        assertEquals(Optional.empty(), store.answer(g, "a1"));
    }

    @Test void answerProvenanceRoundTripsAndIsIdempotent() {
        assumeKnowledgeV2();
        seed();
        store.addKnowledge(g, extracted(g, "k1", "bob", "m2", 0, 30));
        store.addKnowledge(g, hearsay(g, "k2"));
        AnswerRecord a = new AnswerRecord(g.groupId(), "dita-reply-1", List.of("k2", "k1"), List.of("m3", "m2", "m1"), at(15));
        store.recordAnswer(g, a);
        store.recordAnswer(g, new AnswerRecord(g.groupId(), "dita-reply-1", List.of("k1"), List.of(), at(16)));   // first wins
        assertEquals(Optional.of(a), store.answer(g, "dita-reply-1"));
        AnswerRecord empty = new AnswerRecord(g.groupId(), "dita-reply-2", List.of(), List.of(), at(17));
        store.recordAnswer(g, empty);
        assertEquals(Optional.of(empty), store.answer(g, "dita-reply-2"));
        assertEquals(Optional.empty(), store.answer(g, "never-published"));
        assertThrows(IllegalStateException.class,
                () -> store.recordAnswer(g, new AnswerRecord(g.groupId(), "r3", List.of("ghost"), List.of(), at(18))));
        assertThrows(NoSuchElementException.class,
                () -> store.recordAnswer(g, new AnswerRecord(g.groupId(), "r3", List.of("k1"), List.of("ghost"), at(18))));
        assertEquals(Optional.empty(), store.answer(g, "r3"));
        GroupScope b = newGroup("other");
        assertThrows(IsolationViolation.class,
                () -> store.recordAnswer(g, new AnswerRecord(b.groupId(), "r4", List.of(), List.of(), at(18))));
    }

    @Test void knowledgeV2GroupsNeverSeeEachOther() {
        assumeKnowledgeV2();
        GroupScope b = newGroup("other");
        seed();
        store.append(b, msg(b, "m1", "zed", 0, null, "Group B question about Dr. Synthetic Example?"));
        store.append(b, msg(b, "m2", "yael", 2, "m1", "Dr. Synthetic Example is great, says group B."));
        store.append(b, msg(b, "m3", "zed", 4, null, "Group B qualifier text."));
        store.addKnowledge(b, extracted(b, "kb1", "yael", "m2", 0, 30));
        store.addKnowledge(b, hearsay(b, "kb2"));
        store.addRelation(b, relation(b, "kb2", "kb1", KnowledgeRelation.Kind.QUALIFIES, "m3"));
        store.recordAnswer(b, new AnswerRecord(b.groupId(), "rb", List.of("kb1"), List.of("m2"), at(9)));

        assertTrue(store.knowledge(g).isEmpty());
        assertTrue(store.relations(g).isEmpty());
        assertEquals(Optional.empty(), store.answer(g, "rb"));
        // A's scope cannot reference, record against or remove B's rows.
        store.addKnowledge(g, extracted(g, "k1", "bob", "m2", 0, 30));
        assertThrows(IllegalStateException.class, () -> store.addRelation(g, relation(g, "k1", "kb1", KnowledgeRelation.Kind.SUPPORTS, null)));
        assertThrows(IllegalStateException.class,
                () -> store.recordAnswer(g, new AnswerRecord(g.groupId(), "ra", List.of("kb1"), List.of(), at(9))));
        store.removeRelations(g, List.of("kb2|QUALIFIES|kb1"));
        store.removeKnowledge(g, "kb1");

        assertEquals(List.of("kb1", "kb2"), store.knowledge(b).stream().map(KnowledgeItem::id).toList());
        assertEquals(extracted(b, "kb1", "yael", "m2", 0, 30), store.knowledgeById(b, "kb1").orElseThrow());
        assertEquals(List.of("kb2|QUALIFIES|kb1"), store.relations(b).stream().map(KnowledgeRelation::key).toList());
        assertEquals(List.of("kb1"), store.answer(b, "rb").orElseThrow().knowledgeIds());
    }
}
