package app.dita.core;

import app.dita.core.answer.AnswerVerifier;
import app.dita.core.answer.Citation;
import app.dita.core.answer.ExtractiveAnswerComposer;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.fixture.SyntheticConversation;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageEvent;
import app.dita.core.model.MessageLink;
import app.dita.core.model.MessageLink.Kind;
import app.dita.core.model.MessageLink.Status;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.GraphExpandingRetriever;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.HybridRetriever;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Slice 1: synthetic ingest → explicit + inferred links → hybrid/graph retrieval → verified grounded answer. */
class SliceOneTest {
    static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    final GroupScope parents = new GroupScope("g-parents");
    final GroupScope other = new GroupScope("g-other");
    InMemoryMessageStore store;
    IngestionPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryMessageStore();
        pipeline = new IngestionPipeline(store, new RuleLinkInferencer());
        load(parents, "/fixtures/parents-group.synthetic.txt");
        load(other, "/fixtures/other-group.synthetic.txt");
    }

    void load(GroupScope scope, String path) throws Exception {
        for (Message m : SyntheticConversation.load(scope, T0, getClass().getResourceAsStream(path))) {
            pipeline.ingest(scope, m);
        }
    }

    List<MessageLink> linksFrom(String id) {
        return store.links(parents).stream().filter(l -> l.fromId().equals(id)).toList();
    }

    Set<String> ids(List<Hit> hits) { return hits.stream().map(Hit::messageId).collect(Collectors.toSet()); }

    @Test void syntheticDataIsLabelled() {
        assertTrue(store.live(parents).stream().allMatch(m -> m.sourceKind() == SourceKind.SYNTHETIC));
    }

    @Test void explicitReplyIsFact() {
        List<MessageLink> l = linksFrom("m2");
        assertEquals(1, l.size());
        assertEquals(Kind.EXPLICIT_REPLY, l.get(0).kind());
        assertEquals(Status.FACT, l.get(0).status());
        assertEquals("m1", l.get(0).toId());
    }

    @Test void missingReplyLinkIsInferredAsCandidateNeverFact() {
        List<MessageLink> l = linksFrom("m5");
        assertFalse(l.isEmpty(), "m5 discusses Dr. Levi without a reply link");
        assertEquals("m2", l.get(0).toId());
        assertTrue(l.stream().allMatch(x -> x.status() == Status.CANDIDATE));
        assertEquals(1.0, l.get(0).evidence().get("entity"));
    }

    @Test void ambiguousPronounResolvedViaThreadParticipation() {
        List<MessageLink> l = linksFrom("m7");
        assertEquals("m2", l.get(0).toId(), "'she' follows the thread dana started, not the snacks thread");
        assertEquals(1.0, l.get(0).evidence().get("answered_author"));
        assertEquals(Status.CANDIDATE, l.get(0).status());
    }

    @Test void explicitCorrectionLinksToCorrectedCommitment() {
        List<MessageLink> l = linksFrom("m8");
        assertEquals("m6", l.get(0).toId());
        assertEquals(Kind.INFERRED_CORRECTION, l.get(0).kind());
    }

    @Test void resumedConversationAfterDaysIsLinkedAndAmbiguityIsSurfaced() {
        List<MessageLink> l = linksFrom("m9");
        assertFalse(l.isEmpty());
        assertTrue(l.stream().allMatch(x -> x.kind() == Kind.INFERRED_CONTINUATION));
        assertTrue(Set.of("m2", "m5").containsAll(l.stream().map(MessageLink::toId).toList()));
    }

    @Test void inferredLinkCannotBePromotedToFact() {
        assertThrows(IllegalArgumentException.class, () -> new MessageLink("g-parents", "m5", "m2",
                Kind.INFERRED_REFERENCE, Status.FACT, MessageLink.Method.RULE, 0.9, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new MessageLink("g-parents", "m5", "m2",
                Kind.INFERRED_REFERENCE, Status.CONFIRMED, MessageLink.Method.LLM, 0.9, Map.of()));
    }

    @Test void lexicalRetrievalAcrossFragmentedThreadStaysInGroup() {
        List<Hit> hits = new LexicalRetriever(store).retrieve(parents, "pediatrician Dr Levi", 10);
        assertTrue(ids(hits).containsAll(Set.of("m1", "m2", "m5", "m9")));
        assertFalse(ids(hits).contains("x1"));
    }

    @Test void hebrewQueryFindsHebrewMessage() {
        assertTrue(ids(new LexicalRetriever(store).retrieve(parents, "רופא לוי", 5)).contains("m3"));
    }

    @Test void graphExpansionRecoversContextAndFlagsInference() {
        Retriever graph = new GraphExpandingRetriever(new LexicalRetriever(store), store, 2);
        List<Hit> hits = graph.retrieve(parents, "allergies", 10);
        Hit m2 = hits.stream().filter(h -> h.messageId().equals("m2")).findFirst().orElseThrow();
        assertTrue(m2.inferredContext());
        assertTrue(ids(hits).contains("m1"), "2nd hop reaches the original question");
        assertTrue(ids(new LexicalRetriever(store).retrieve(parents, "allergies", 10)).equals(Set.of("m7")));
    }

    @Test void groundedAnswerSurfacesContradictionAndQualifier() {
        Retriever hybrid = new HybridRetriever(List.of(new LexicalRetriever(store),
                new GraphExpandingRetriever(new LexicalRetriever(store), store, 1)));
        AnswerVerifier verifier = new AnswerVerifier(store);
        GroundedAnswer a = verifier.verify(parents,
                new ExtractiveAnswerComposer(hybrid, store).answer(parents, "Is Dr. Levi recommended?", 6));
        assertTrue(a.found());
        assertTrue(a.conflicting(), a.text());
        Citation m5 = a.citations().stream().filter(c -> c.messageId().equals("m5")).findFirst().orElseThrow();
        assertTrue(m5.qualified());
        assertEquals(-1, m5.polarity());
        assertTrue(a.citations().stream().noneMatch(c -> c.messageId().equals("x1")));
        for (Citation c : a.citations()) {
            assertTrue(store.find(parents, c.messageId()).orElseThrow().text().contains(c.quote()));
        }
    }

    @Test void verifierDropsFabricatedAndCrossGroupCitations() {
        Citation fabricated = new Citation("m2", "yossi", T0, "Dr. Levi is terrible", Citation.Evidence.EXPLICIT, -1, false);
        Citation crossGroup = new Citation("x1", "eve", T0, "worst dentist", Citation.Evidence.EXPLICIT, -1, false);
        Citation real = new Citation("m6", "amir", T0, "I can bring snacks", Citation.Evidence.EXPLICIT, 0, false);
        AnswerVerifier v = new AnswerVerifier(store);
        GroundedAnswer only = v.verify(parents, new GroundedAnswer("q", "x", List.of(fabricated, crossGroup), false, true));
        assertFalse(only.found());
        assertEquals(GroundedAnswer.NOT_FOUND, only.text());
        GroundedAnswer mixed = v.verify(parents, new GroundedAnswer("q", "x", List.of(fabricated, real), true, true));
        assertEquals(List.of("m6"), mixed.citations().stream().map(Citation::messageId).toList());
    }

    @Test void storeRefusesCrossGroupWritesAndReads() {
        Message foreign = new Message("y1", "g-other", "eve", T0, null, "hi", SourceKind.SYNTHETIC, false);
        assertThrows(IsolationViolation.class, () -> store.append(parents, foreign));
        assertTrue(store.find(parents, "x1").isEmpty());
        Message replyAcross = new Message("m99", "g-parents", "dana", T0.plusSeconds(99999), "x1", "?", SourceKind.SYNTHETIC, false);
        assertThrows(IllegalStateException.class, () -> store.append(parents, replyAcross));
    }

    @Test void editsKeepOriginalAndDeletesLeaveMemoryAndAnswers() {
        store.edit(parents, "m6", "I can bring snacks and juice", T0.plusSeconds(3600));
        List<MessageEvent> h = store.history(parents, "m6");
        assertEquals("I can bring snacks", h.get(0).text());
        assertEquals(MessageEvent.Kind.EDITED, h.get(1).kind());

        new app.dita.core.deletion.DeletionService(store, new RuleLinkInferencer(), app.dita.core.config.DeletionSettings.DEFAULTS, app.dita.core.events.EventPublisher.NONE, 50).delete(parents, "m5", T0.plusSeconds(7200), app.dita.core.deletion.DeletionSource.PLATFORM_EVENT);
        assertFalse(ids(new LexicalRetriever(store).retrieve(parents, "teenagers", 10)).contains("m5"));
        Citation stale = new Citation("m5", "ron", T0, "avoid him", Citation.Evidence.EXPLICIT, -1, false);
        assertFalse(new AnswerVerifier(store).verify(parents, new GroundedAnswer("q", "x", List.of(stale), false, true)).found());
    }

    @Test void deletionPurgesHistoryAndCannotBeRevivedByEdit() {   // Natasha N-1
        store.edit(parents, "m6", "I can bring snacks and juice", T0.plusSeconds(3600));
        new app.dita.core.deletion.DeletionService(store, new RuleLinkInferencer(), app.dita.core.config.DeletionSettings.DEFAULTS, app.dita.core.events.EventPublisher.NONE, 50).delete(parents, "m6", T0.plusSeconds(7200), app.dita.core.deletion.DeletionSource.PLATFORM_EVENT);
        assertTrue(store.history(parents, "m6").stream().allMatch(e -> e.text() == null));
        assertThrows(IllegalStateException.class, () -> store.edit(parents, "m6", "back again", T0.plusSeconds(9000)));
        assertTrue(store.find(parents, "m6").orElseThrow().deleted());
    }

    @Test void rejectedAppendLeavesNoPartialState() {   // Natasha N-10
        Message bad = new Message("m98", "g-parents", "dana", T0.plusSeconds(99999), "x1", "?", SourceKind.SYNTHETIC, false);
        assertThrows(IllegalStateException.class, () -> store.append(parents, bad));
        assertTrue(store.find(parents, "m98").isEmpty());
    }

    @Test void redeliveryIsIdempotent() {   // Tony: Telegram re-poll / outbox retry
        Message m2 = store.find(parents, "m2").orElseThrow();
        int before = store.links(parents).size();
        assertTrue(pipeline.ingest(parents, m2).isEmpty());
        assertEquals(before, store.links(parents).size());
    }

    @Test void unknownTopicReturnsNotFoundInsteadOfInventing() {
        GroundedAnswer a = new ExtractiveAnswerComposer(new LexicalRetriever(store), store)
                .answer(parents, "swimming lessons schedule", 5);
        assertFalse(a.found());
    }
}
