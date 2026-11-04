package app.dita.core;

import app.dita.core.answer.AnswerVerifier;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DeletionSettings.DitaReplies;
import app.dita.core.config.DeletionSettings.LateKnowledge;
import app.dita.core.config.DeletionSettings.RepeatedWording;
import app.dita.core.config.DispatchSettings;
import app.dita.core.coordination.EventPlan;
import app.dita.core.coordination.GroupLifecycle;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionPolicy;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.deletion.Requester;
import app.dita.core.events.EventDispatcher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeItem.Kind;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import app.dita.core.projection.InMemoryGraphProjection;
import app.dita.core.projection.InMemorySearchIndex;
import app.dita.core.projection.ProjectionListener;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DEC-012/DEC-013 deletion policy with selective dependency invalidation. All data synthetic. */
class DeletionPolicyTest {
    static final Instant T = Instant.parse("2026-09-01T08:00:00Z");
    final GroupScope g = new GroupScope("g-parents");
    InMemoryMessageStore store;
    InMemoryGraphProjection graph;
    InMemorySearchIndex index;
    EventDispatcher dispatcher;
    MutableClock clock;
    RuleLinkInferencer inferencer;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @BeforeEach void setUp() {
        store = new InMemoryMessageStore();
        graph = new InMemoryGraphProjection();
        index = new InMemorySearchIndex();
        clock = new MutableClock();
        dispatcher = new EventDispatcher(new DispatchSettings(100, 2, 3, Duration.ofSeconds(1), Duration.ofSeconds(4)), clock);
        ProjectionSource truth = ProjectionSource.of(store);
        dispatcher.register(ProjectionListener.consumer("neo4j", graph, truth));
        dispatcher.register(ProjectionListener.consumer("vespa", index, truth));
        inferencer = new RuleLinkInferencer();
        IngestionPipeline p = new IngestionPipeline(store, inferencer, 50, dispatcher);
        p.ingest(g, msg("a1", "dana", 0, null, "Does anyone know a good pediatrician near the park?"));
        p.ingest(g, msg("a4", "maya", 30, "a1", "We love Dr. Levi, very patient with our toddler"));
        p.ingest(g, msg("a2", "yossi", 60, "a1", "Dr. Levi on Herzl St. is excellent, we have been going there for years"));
        p.ingest(g, msg("a3", "ron", 180, null, "Dr. Levi is fine for babies but avoid him for teenagers"));
        
        p.ingest(g, msg("a5", "dana", 240, null, "thanks! is she also good with allergies?"));
        // Claim supported by two independent messages (a2, a4) plus two derived records from a2 alone.
        know(KnowledgeItem.of("k-levi", "g-parents", Kind.RECOMMENDATION, "Dr. Levi", "Members recommend Dr. Levi as a pediatrician", 1,
                MessageLink.Method.RULE, List.of("a2", "a4")));
        know(KnowledgeItem.of("k-herzl", "g-parents", Kind.CLAIM, "Dr. Levi", "Dr. Levi's clinic is on Herzl St.", 0, MessageLink.Method.RULE, List.of("a2")));
        know(new KnowledgeItem("k-dup", "g-parents", Kind.CLAIM, "Dr. Levi", "Dr. Levi is excellent", 1, MessageLink.Method.RULE,
                List.of(KnowledgeItem.Evidence.verified("a2"), KnowledgeItem.Evidence.verified("a2")), false));
        know(KnowledgeItem.of("k-quote", "g-parents", Kind.CLAIM, "Dr. Levi", "on Herzl St. is excellent, we have been going there", 1, MessageLink.Method.RULE, List.of("a2", "a4")));
        know(KnowledgeItem.of("k-question", "g-parents", Kind.QUESTION, "pediatrician", "Looking for a pediatrician near the park", 0, MessageLink.Method.RULE, List.of("a1")));
        // Provisional support for k-levi: a5 relates to a2 only through an inferred link.
        MessageLink a5a2 = store.links(g).stream().filter(l -> l.fromId().equals("a5") && l.toId().equals("a2")).findFirst()
                .orElseThrow(() -> new AssertionError("fixture precondition: a5 must have an inferred link to a2"));
        KnowledgeItem withProv = store.knowledgeById(g, "k-levi").orElseThrow().withProvisional("a5", a5a2.key());
        store.replaceKnowledge(g, withProv);
        dispatcher.publish(g.groupId(), new app.dita.core.events.Refs.KnowledgeRef(g.groupId(), "k-levi"));
        drain();
    }

    @AfterEach void tearDown() { dispatcher.close(); }

    Message msg(String id, String author, long seconds, String replyTo, String text) {
        return new Message(id, "g-parents", author, T.plusSeconds(seconds), replyTo, text, SourceKind.SYNTHETIC, false);
    }

    void know(KnowledgeItem k) { store.addKnowledge(g, k); dispatcher.publish(g.groupId(), new app.dita.core.events.Refs.KnowledgeRef(g.groupId(), k.id())); }
    void drain() { dispatcher.drain(); }
    KnowledgeItem k(String id) { return store.knowledgeById(g, id).orElse(null); }
    boolean linksTouch(String id) { return store.links(g).stream().anyMatch(l -> l.dependsOn(id)); }

    DeletionService service(DeletionSettings s) { return new DeletionService(store, inferencer, s, dispatcher, 50); }
    DeletionService defaults() { return service(DeletionSettings.DEFAULTS); }
    Instant early(String id) { return store.find(g, id).orElseThrow().sentAt().plusSeconds(299); }
    Instant late(String id) { return store.find(g, id).orElseThrow().sentAt().plus(Duration.ofHours(2)); }

    @Test void fiveMinuteBoundaryIsInclusiveAndConfigurable() {
        assertEquals(DeletionMode.EARLY_PURGE, DeletionPolicy.classify(T, T.plus(Duration.ofMinutes(5))));
        assertEquals(DeletionMode.LATE_TEXT_ONLY, DeletionPolicy.classify(T, T.plus(Duration.ofMinutes(5)).plusMillis(1)));
        assertEquals(DeletionMode.EARLY_PURGE, DeletionPolicy.classify(T, T.minusSeconds(1)), "skew clamped (N-14)");
        assertEquals(DeletionMode.EARLY_PURGE, DeletionPolicy.classify(T, T.plus(Duration.ofMinutes(9)), Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new DeletionSettings(Duration.ofMinutes(4), DeletionSettings.EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE,
                LateKnowledge.PRESERVE_WITH_DELETED_SOURCE_PROVENANCE, RepeatedWording.REDACT_WORDING, Duration.ofDays(21), false, DitaReplies.KEEP), "N-26: window cannot be shortened");
        assertThrows(IllegalArgumentException.class, () -> new DeletionSettings(Duration.ofMinutes(5), DeletionSettings.EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE,
                LateKnowledge.PRESERVE_WITH_DELETED_SOURCE_PROVENANCE, RepeatedWording.REDACT_WORDING, Duration.ofDays(22), false, DitaReplies.KEEP), "N-26: retention cannot be extended");
    }

    @Test void editsDoNotRestartTheClock() {
        store.edit(g, "a3", "Dr. Levi is fine for babies", T.plusSeconds(180 + 280));
        DeletionEvent e = defaults().delete(g, "a3", T.plusSeconds(180 + 301), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertEquals(DeletionMode.LATE_TEXT_ONLY, e.mode(), "measured from the original send time, not the edit");
    }

    @Test void earlyDeletionKeepsClaimWithOtherIndependentSupportAndRecalculates() {
        DeletionEvent e = defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertEquals(DeletionMode.EARLY_PURGE, e.mode());

        KnowledgeItem levi = k("k-levi");
        assertNotNull(levi, "a4 independently supports the claim");
        assertEquals(java.util.Set.of("a4"), levi.independentSupport());
        assertTrue(levi.evidence().stream().noneMatch(x -> x.messageId().equals("a2")));
        assertTrue(levi.evidence().stream().noneMatch(x -> x.basis() == KnowledgeItem.Basis.PROVISIONAL),
                "provisional evidence via the invalidated a5->a2 link is removed");

        assertNull(k("k-herzl"), "only source was a2");
        assertNull(k("k-dup"), "two derived records from one message are not independent support");
        assertNotNull(k("k-question"));
        assertTrue(e.removedKnowledgeIds().containsAll(List.of("k-herzl", "k-dup")));
        assertTrue(e.updatedKnowledgeIds().contains("k-levi"));
    }

    @Test void earlyDeletionRedactsSurvivingWordingThatRepeatsDeletedText() {
        defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT);
        KnowledgeItem q = k("k-quote");
        assertNotNull(q, "still supported by a4");
        assertTrue(q.statementRedacted());
        assertEquals(KnowledgeItem.REDACTED, q.statement());
    }

    @Test void earlyDeletionInvalidatesDependentLinksAndRecalculatesThem() {
        assertTrue(linksTouch("a2"));
        java.util.Set<String> before = store.links(g).stream().map(MessageLink::key).collect(java.util.stream.Collectors.toSet());
        DeletionEvent e = defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertFalse(linksTouch("a2"), "no link may rely on deleted evidence");
        assertTrue(e.removedLinkKeys().stream().anyMatch(x -> x.startsWith("a5->a2")));
        // a5 was re-inferred without a2; any new candidate it got is listed and still only CANDIDATE.
        store.links(g).stream().filter(l -> l.fromId().equals("a5"))
                .forEach(l -> { assertEquals(MessageLink.Status.CANDIDATE, l.status());
                                assertTrue(before.contains(l.key()) || e.addedLinkKeys().contains(l.key())); });
        assertTrue(store.links(g).stream().anyMatch(l -> l.fromId().equals("a5") && l.toId().equals("a4")),
                "a5 keeps its independent link to a4");
    }

    @Test void lateDeletionPreservesKnowledgeWithDeletedSourceProvenanceAndRedactsVerbatim() {
        DeletionEvent e = defaults().delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertEquals(DeletionMode.LATE_TEXT_ONLY, e.mode());
        assertNull(store.find(g, "a2").orElseThrow().text());
        assertTrue(store.history(g, "a2").stream().allMatch(ev -> ev.text() == null));
        assertTrue(k("k-herzl").deletedSourceIds().contains("a2"), "preserved with deleted-source provenance");
        assertEquals("Dr. Levi's clinic is on Herzl St.", k("k-herzl").statement());
        assertTrue(k("k-quote").statementRedacted(), "C-3: removed wording is not retained verbatim");
        assertTrue(linksTouch("a2"), "links preserved in late mode");
        assertTrue(e.preservedWithDeletedSourceIds().containsAll(List.of("k-levi", "k-herzl", "k-dup", "k-quote")));
    }

    @Test void lateDeletionCanBeConfiguredToRemoveContribution() {
        DeletionSettings s = new DeletionSettings(Duration.ofMinutes(5), DeletionSettings.EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE,
                LateKnowledge.REMOVE_CONTRIBUTION_AND_RECALCULATE, RepeatedWording.REMOVE_ITEM, Duration.ofDays(21), false, DitaReplies.KEEP);
        service(s).delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT);
        assertNull(k("k-herzl"));
        assertNull(k("k-quote"), "REMOVE_ITEM removes wording-repeating items");
        assertEquals(java.util.Set.of("a4"), k("k-levi").independentSupport());
    }

    @Test void deletionPropagatesToProjectionsAndSurvivesOutages() {
        assertTrue(index.messageDocs.containsKey("a2"));
        graph.failNext.set(2);                                   // Neo4j down for two attempts
        defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT);
        drain();
        assertFalse(index.messageDocs.containsKey("a2"), "Vespa is not blocked by the Neo4j outage");
        assertFalse(index.knowledgeDocs.containsKey("k-herzl"));
        assertTrue(index.knowledgeDocs.get("k-quote").redacted());
        assertNotNull(graph.nodes.get("a2").text(), "graph has not caught up yet");
        assertTrue(dispatcher.lag("neo4j") > 0);

        clock.now = clock.now.plusSeconds(10); drain();          // retry 1 fails
        clock.now = clock.now.plusSeconds(10); drain();          // retry 2 succeeds
        assertEquals(0, dispatcher.lag("neo4j"));
        assertNull(graph.nodes.get("a2").text());
        assertTrue(graph.nodes.get("a2").deleted());
        assertTrue(graph.edges.values().stream().noneMatch(x -> x.from().equals("a2") || x.to().equals("a2")));
        assertFalse(graph.knowledge.containsKey("k-dup"));
        assertTrue(graph.knowledge.get("k-quote").redacted());
        assertTrue(dispatcher.deadLetters().isEmpty());
    }

    @Test void persistentFailureOfNonDeletionEventIsDeadLetteredWithIdsOnly() {
        graph.failNext.set(100);
        dispatcher.publish(g.groupId(), new app.dita.core.events.Refs.KnowledgeRef(g.groupId(), "k-levi"));
        for (int i = 0; i < 6; i++) { clock.now = clock.now.plusSeconds(30); drain(); }
        assertEquals(1, dispatcher.deadLetters().size());
        assertInstanceOf(app.dita.core.events.Refs.KnowledgeRef.class, dispatcher.deadLetters().get(0).envelope().payload());
        graph.failNext.set(0);
        dispatcher.redrive("neo4j");
        assertTrue(dispatcher.deadLetters().isEmpty(), "redrive re-delivers after the cause is fixed");
    }

    @Test void replayAfterDeletionNeverRestoresText() {
        defaults().delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT);
        drain();
        dispatcher.publish(g.groupId(), new app.dita.core.events.Refs.MessageRef(g.groupId(), "a2"));   // stale/replayed ingest event
        drain();
        assertFalse(index.messageDocs.containsKey("a2"));
        assertNull(graph.nodes.get("a2").text());
        assertThrows(IllegalStateException.class, () -> store.edit(g, "a2", "restored?", late("a2")));
        assertTrue(new LexicalRetriever(store).retrieve(g, "Herzl excellent", 10).stream().noneMatch(h -> h.messageId().equals("a2")));
    }

    @Test void deletedMessageIsNeverCited() {
        defaults().delete(g, "a3", late("a3"), DeletionSource.PLATFORM_EVENT);
        Citation c = new Citation("a3", "ron", T, "avoid him", Citation.Evidence.EXPLICIT, -1, false);
        assertFalse(new AnswerVerifier(store).verify(g, new GroundedAnswer("q", "x", List.of(c), false, true)).found());
    }

    @Test void deletionIsIdempotent() {
        assertTrue(defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT).isPresent());
        assertTrue(defaults().delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT).isEmpty());
    }

    @Test void forgetIsAuthorOnlyByDefaultAndRefusesProxies() {
        DeletionService d = defaults();
        assertThrows(SecurityException.class, () -> d.forget(g, Requester.admin("g-parents", "dana", early("a2")), "a2", early("a2")),
                "admins cannot forget others' messages by default (OPEN-10)");
        assertThrows(SecurityException.class, () -> d.forget(g, new Requester("g-parents", "yossi", true, null), "a2", early("a2")),
                "anonymous admin / sender_chat cannot prove authorship (N-15)");
        assertEquals(DeletionSource.FORGET_COMMAND, d.forget(g, Requester.member("g-parents", "yossi"), "a2", early("a2")).orElseThrow().source());
    }

    @Test void adminForgetRequiresConfigAndVerifiedAdmin() {
        DeletionSettings s = new DeletionSettings(Duration.ofMinutes(5), DeletionSettings.EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE,
                LateKnowledge.PRESERVE_WITH_DELETED_SOURCE_PROVENANCE, RepeatedWording.REDACT_WORDING, Duration.ofDays(21), true, DitaReplies.KEEP);
        DeletionService d = service(s);
        assertThrows(SecurityException.class, () -> d.forget(g, Requester.member("g-parents", "dana"), "a2", late("a2")));
        assertThrows(SecurityException.class, () -> d.forget(g, Requester.admin("g-parents", "dana", early("a2")), "a2", late("a2")),
                "stale admin verification (> 10 min) is refused");
        assertThrows(app.dita.core.model.IsolationViolation.class, () -> d.forget(g, Requester.admin("g-other", "dana", late("a2")), "a2", late("a2")));
        assertTrue(d.forget(g, Requester.admin("g-parents", "dana", late("a2")), "a2", late("a2")).isPresent());
    }

    @Test void ditaRepliesAreKeptByDefaultAndCanBeReported() {
        store.recordDitaReply(g, "dita-r1", List.of("a2", "a4"));
        assertTrue(defaults().delete(g, "a4", late("a4"), DeletionSource.PLATFORM_EVENT).orElseThrow().affectedDitaReplyIds().isEmpty());
        DeletionSettings s = new DeletionSettings(Duration.ofMinutes(5), DeletionSettings.EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE,
                LateKnowledge.PRESERVE_WITH_DELETED_SOURCE_PROVENANCE, RepeatedWording.REDACT_WORDING, Duration.ofDays(21), false, DitaReplies.REPORT_AFFECTED);
        assertEquals(List.of("dita-r1"), service(s).delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT).orElseThrow().affectedDitaReplyIds());
    }

    @Test void cannotDeriveNewKnowledgeOrLinksFromDeletedMessage() {
        defaults().delete(g, "a3", late("a3"), DeletionSource.PLATFORM_EVENT);
        assertThrows(IllegalStateException.class, () -> store.addKnowledge(g,
                KnowledgeItem.of("k-new", "g-parents", Kind.CLAIM, null, "x", 0, MessageLink.Method.RULE, List.of("a3"))));
        assertThrows(IllegalStateException.class, () -> store.addLink(g, new MessageLink("g-parents", "a5", "a3",
                MessageLink.Kind.INFERRED_REFERENCE, MessageLink.Status.CANDIDATE, MessageLink.Method.RULE, 0.5, java.util.Map.of())));
    }

    @Test void groupRemovalRetentionRejoinAndNewWindow() {
        GroupLifecycle life = new GroupLifecycle(Duration.ofDays(21));
        life.botRemoved(T);
        assertEquals(T.plus(Duration.ofDays(21)), life.purgeAfter());
        assertTrue(life.botReadded(T.plus(Duration.ofDays(20))), "rejoin within window restores memory");
        life.botRemoved(T.plus(Duration.ofDays(30)));
        assertEquals(T.plus(Duration.ofDays(51)), life.purgeAfter(), "a later removal starts a new window");
        assertFalse(store.find(g, "a2").orElseThrow().deleted(), "removal alone deletes no message text");
    }

    @Test void coordinationItemsFollowThePolicy() {   // N-11
        EventPlan early = new EventPlan("g-parents", "party");
        early.addTask("t1", "Bring snacks", "a2");
        early.recordPreference("yossi", "prefers Saturday", "a2");
        early.onSourceMessageDeleted("a2", DeletionMode.EARLY_PURGE);
        assertEquals(EventPlan.REMOVED_TEXT, early.task("t1").orElseThrow().text());
        assertTrue(early.preferences().isEmpty());
        EventPlan late = new EventPlan("g-parents", "party");
        late.addTask("t1", "Bring snacks", "a2");
        late.onSourceMessageDeleted("a2", DeletionMode.LATE_TEXT_ONLY);
        assertTrue(late.task("t1").orElseThrow().sourceDeleted());
    }

    @Test void outboxAndDeadLettersHoldIdsOnlyAndArePruned() {   // N-18
        assertThrows(IllegalArgumentException.class, () -> dispatcher.publish(g.groupId(), store.find(g, "a1").orElseThrow()));
        assertEquals(0, dispatcher.retainedEnvelopes(), "delivered envelopes are pruned");
    }

    @Test void deletionEventsAreNeverDeadLetteredAndReconcileHeals() {   // N-20
        graph.failNext.set(1000);
        defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT);
        for (int i = 0; i < 8; i++) { clock.now = clock.now.plus(Duration.ofMinutes(2)); drain(); }
        assertTrue(dispatcher.blockedOnDeletion().contains("neo4j"), "operator alert instead of silently skipping");
        assertTrue(dispatcher.deadLetters().stream().noneMatch(d -> d.envelope().payload() instanceof DeletionEvent));
        graph.failNext.set(0);
        clock.now = clock.now.plus(Duration.ofMinutes(2)); drain();
        assertTrue(dispatcher.blockedOnDeletion().isEmpty());
        assertNull(graph.nodes.get("a2").text());
    }

    @Test void projectionsNeverIndexFromPayloadWhenTruthIsMissing() {   // N-19
        InMemorySearchIndex fresh = new InMemorySearchIndex();
        ProjectionSource empty = new ProjectionSource() {
            public java.util.Optional<Message> message(String gr, String id) { return java.util.Optional.empty(); }
            public java.util.Optional<KnowledgeItem> knowledge(String gr, String id) { return java.util.Optional.empty(); }
            public java.util.Optional<MessageLink> link(String gr, String key) { return java.util.Optional.empty(); }
        };
        fresh.onMessage("g-parents", "a2", empty);
        assertTrue(fresh.messageDocs.isEmpty());
        index.reconcile("g-parents", empty);
        assertTrue(index.messageDocs.isEmpty(), "reconcile removes documents truth no longer has");
    }

    @Test void failedDeletionRollsBackAndCanBeRetried() {   // N-21
        app.dita.core.link.LinkInferencer exploding = (s, m, w, k) -> { throw new IllegalStateException("boom"); };
        DeletionService broken = new DeletionService(store, exploding, DeletionSettings.DEFAULTS, dispatcher, 50);
        assertThrows(IllegalStateException.class, () -> broken.delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT));
        assertFalse(store.find(g, "a2").orElseThrow().deleted(), "no half-applied tombstone");
        assertNotNull(k("k-herzl"));
        assertTrue(defaults().delete(g, "a2", early("a2"), DeletionSource.PLATFORM_EVENT).isPresent(), "retry completes");
        assertNull(k("k-herzl"));
    }

    @Test void lateDeletedOnlySourcesAreMarked() {   // N-23
        defaults().delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT);
        assertTrue(k("k-herzl").onlyDeletedSourcesRemain());
        assertFalse(k("k-levi").onlyDeletedSourcesRemain(), "a4 is still live");
    }

    @Test void eventsAndToStringNeverCarryText() {
        DeletionEvent e = defaults().delete(g, "a2", late("a2"), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertFalse(e.toString().contains("excellent"));
        assertFalse(store.find(g, "a3").orElseThrow().toString().contains("teenagers"));
        assertFalse(k("k-herzl").toString().contains("Herzl St."));
    }
}
