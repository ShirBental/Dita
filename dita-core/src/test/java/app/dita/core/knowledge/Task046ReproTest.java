package app.dita.core.knowledge;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-046: independent regression tests for four externally reported defects (message edits, predicate/status
 * consistency, over-broad supersession, named-person relevance). Offline, synthetic fixtures written for this test only.
 * Each defect has repro tests (expected to fail until fixed) and preservation tests (must pass before and after).
 */
class Task046ReproTest {
    static final Instant T0 = Instant.parse("2026-12-05T09:00:00Z");
    final GroupScope g = new GroupScope("g-task046");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryExtractionQueue queue = new InMemoryExtractionQueue();
    final Function<String, String> names = Map.of("u-ana", "Ana", "u-ben", "Ben", "u-kim", "Kim")::get;
    final Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);

    void msg(String id, String author, int min, String text) {
        store.append(g, new Message(id, g.groupId(), author, T0.plusSeconds(60L * min), null, text, SourceKind.SYNTHETIC, false));
    }

    static Proposal.Item it(String status, String certainty, String subjectId, String subject, String predicate, String mid, String quote) {
        return new Proposal.Item(status, certainty, subjectId, subject, predicate, null, null, List.of(new Proposal.Quote(mid, quote)), List.of(), List.of());
    }

    static Proposal.Item withQualifier(Proposal.Item i, String kind, String mid, String quote) {
        return new Proposal.Item(i.status(), i.certainty(), i.subjectId(), i.subject(), i.predicate(), i.polarity(), i.attributedTo(),
                i.evidence(), List.of(new Proposal.Qualifier(kind, mid, quote)), i.entities());
    }

    ExtractionService.Report run(List<Proposal.Item> items, String... ids) {
        ExtractionService x = new ExtractionService(store, queue, (s, b, t, c) -> new Proposal(items, "test"),
                EventPublisher.NONE, now, 50, Duration.ofMinutes(30));
        for (String id : ids) x.enqueue(g, store.find(g, id).orElseThrow());
        ExtractionBatch b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        ExtractionService.Report r = x.process(g, b.id()).orElseThrow();
        assertEquals(ExtractionBatch.Status.DONE, r.status(), "setup: batch must process: " + r);
        return r;
    }

    KnowledgeAnswerComposer composer() { return new KnowledgeAnswerComposer(store, 200, names); }

    KnowledgeItem itemFrom(String mid) {
        return store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals(mid)).findFirst().orElseThrow();
    }

    static List<String> cited(GroundedAnswer a) { return a.citations().stream().map(Citation::messageId).toList(); }

    QueryService query() {
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
        return new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, names,
                new LexicalKnowledgeRetriever(store, names));
    }

    // ---------------------------------------------------------------- Defect 1: message edits

    @Test void d1_editedSourceMessageIsNotCitedAsKnowledgeWithTheOldSpan() {
        msg("m1", "u-ana", 0, "The bus leaves at 9 AM from the school.");
        msg("m2", "u-kim", 2, "The museum opens at 10 AM on Friday.");
        run(List.of(
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "bus", "The bus", "states", "m1", "The bus leaves at 9 AM from the school."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "museum", "The museum", "states", "m2", "The museum opens at 10 AM on Friday.")), "m1", "m2");
        assertEquals(2, store.knowledge(g).size(), "setup");
        store.edit(g, "m1", "The bus leaves from the gym, time to be announced later today.", T0.plusSeconds(600));
        Optional<GroundedAnswer> a = composer().answer(g, "When does the bus leave?");
        a.ifPresent(x -> {
            assertFalse(cited(x).contains("m1"), "knowledge from revision 1 of edited m1 is still cited: " + x.text());
            assertFalse(x.text().contains("9 AM"), "old-revision content presented as current: " + x.text());
        });
        GroundedAnswer museum = composer().answer(g, "When does the museum open?").orElseThrow();
        assertTrue(cited(museum).contains("m2"), museum.text());
    }

    @Test void d1_anEditedSupersedingMessageIsNotShownAsTheCurrentClaim() {
        msg("m1", "u-ana", 0, "Dinner is at 7 PM.");
        msg("m2", "u-ana", 5, "Sorry, I got that wrong. Dinner is at 8 PM.");
        var r = run(List.of(
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "dinner", "Dinner", "states", "m1", "Dinner is at 7 PM."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "dinner", "Dinner", "states", "m2", "Dinner is at 8 PM.")), "m1", "m2");
        assertEquals(1, r.superseded(), "setup: correction supersedes");
        store.edit(g, "m2", "Sorry, I got that wrong. Dinner is cancelled, there is no food tonight.", T0.plusSeconds(900));
        Optional<GroundedAnswer> a = composer().answer(g, "When is dinner?");
        a.ifPresent(x -> {
            assertFalse(cited(x).contains("m2"), "knowledge from the pre-edit revision of m2 is shown as current: " + x.text());
            assertFalse(x.text().contains("This replaces earlier messages"), "a supersession by an edited revision is presented: " + x.text());
        });
    }

    @Test void d1_preservation_editingOneMessageLeavesOtherMessagesKnowledgeAnswerable() {
        msg("m1", "u-ana", 0, "The bus leaves at 9 AM from the school.");
        msg("m2", "u-kim", 2, "The museum opens at 10 AM on Friday.");
        run(List.of(
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "bus", "The bus", "states", "m1", "The bus leaves at 9 AM from the school."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "museum", "The museum", "states", "m2", "The museum opens at 10 AM on Friday.")), "m1", "m2");
        store.edit(g, "m1", "The bus leaves from the gym, time to be announced later today.", T0.plusSeconds(600));
        GroundedAnswer museum = composer().answer(g, "When does the museum open?").orElseThrow();
        assertEquals("m2", cited(museum).get(0), museum.text());
        assertTrue(museum.text().contains("The museum opens at 10 AM on Friday."), museum.text());
    }

    // ---------------------------------------------------------------- Defect 2: predicate/status consistency

    @Test void d2_aSuggestionWithPredicateDecidesIsNotRenderedAsADecision() {
        msg("t1", "u-ana", 0, "I suggest we go with the blue tent.");
        var r = run(List.of(it("ATTRIBUTED_STATEMENT", "ASSERTED", "tent", "the blue tent", "decides", "t1", "I suggest we go with the blue tent.")), "t1");
        Optional<GroundedAnswer> a = composer().answer(g, "Which tent are we using?");
        a.ifPresent(x -> assertFalse(x.text().contains("decided on"), "proposal rendered as a decision: " + x.text()));
        assertTrue(r.accepted() == 1 || a.isEmpty(), "either accepted with a consistent predicate or rejected");
    }

    @Test void d2_anOfferWithPredicateCommitsIsNotRenderedAsACommitment() {
        msg("c1", "u-ben", 0, "I can bring chairs if you want.");
        run(List.of(it("OFFER", "ASSERTED", "chairs", "chairs", "commits", "c1", "I can bring chairs if you want.")), "c1");
        composer().answer(g, "Who brings chairs?")
                .ifPresent(x -> assertFalse(x.text().contains("committed to"), "offer rendered as a commitment: " + x.text()));
    }

    @Test void d2_aDecisionMarkerInAnotherClauseDoesNotMakeASuggestionADecision() {
        msg("d1", "u-ana", 0, "The final exam is on Monday. I suggest we meet at the library.");
        run(List.of(it("DECISION", "ASSERTED", "library", "the library", "decides", "d1", "I suggest we meet at the library.")), "d1");
        composer().answer(g, "Where do we meet at the library?")
                .ifPresent(x -> assertFalse(x.text().contains("decided on"), "'final' in another clause made a suggestion a decision: " + x.text()));
    }

    @Test void d2_aCommitmentMarkerInAnotherClauseDoesNotConfirmAHedgedOffer() {
        msg("k1", "u-kim", 0, "The invitations are done. I could pick up the cake.");
        run(List.of(it("CONFIRMED_COMMITMENT", "ASSERTED", "cake", "the cake", "commits", "k1", "I could pick up the cake.")), "k1");
        composer().answer(g, "Who will pick up the cake?")
                .ifPresent(x -> assertFalse(x.text().contains("committed to"), "'done' in another clause confirmed a hedged offer: " + x.text()));
    }

    @Test void d2_preservation_anExplicitDecisionStaysADecisionAndAConfirmationStaysACommitment() {
        msg("t1", "u-ana", 0, "Decision: we go with the blue tent.");
        msg("c1", "u-ben", 1, "Confirmed: I'll bring the chairs.");
        run(List.of(
                it("DECISION", "ASSERTED", "tent", "the blue tent", "decides", "t1", "Decision: we go with the blue tent."),
                it("CONFIRMED_COMMITMENT", "ASSERTED", "chairs", "the chairs", "commits", "c1", "Confirmed: I'll bring the chairs.")), "t1", "c1");
        String tent = composer().answer(g, "Which tent are we using?").orElseThrow().text();
        assertTrue(tent.lines().findFirst().orElseThrow().startsWith("- Ana decided on the blue tent"), tent);
        String chairs = composer().answer(g, "Who brings the chairs?").orElseThrow().text();
        assertTrue(chairs.lines().findFirst().orElseThrow().startsWith("- Ben committed to the chairs"), chairs);
    }

    // ---------------------------------------------------------------- Defect 3: supersession

    @Test void d3_aDecisionDoesNotRetireAComplementaryFactAboutTheSameSubject() {
        msg("h1", "u-ana", 0, "The hall has a small kitchen and parking for 20 cars.");
        msg("h2", "u-ana", 5, "Decision: we book the hall for the party.");
        run(List.of(
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "hall", "The hall", "states", "h1", "The hall has a small kitchen and parking for 20 cars."),
                it("DECISION", "ASSERTED", "hall", "the hall", "decides", "h2", "Decision: we book the hall for the party.")), "h1", "h2");
        assertEquals(Assertion.Status.DECISION, itemFrom("h2").assertion().status(), "setup");
        assertEquals(Assertion.Validity.ACTIVE, itemFrom("h1").assertion().validity(), "the kitchen fact was retired by an unrelated decision");
        GroundedAnswer a = composer().answer(g, "Does the hall have a kitchen?").orElseThrow();
        assertTrue(cited(a).contains("h1"), a.text());
    }

    @Test void d3_aDecisionDoesNotRetireAnEarlierConditionalStatement() {
        msg("p1", "u-ana", 0, "If it rains, the picnic moves to the gym.");
        msg("p2", "u-ana", 5, "Decision: the picnic is on Saturday at the lake.");
        run(List.of(
                withQualifier(it("ATTRIBUTED_STATEMENT", "ASSERTED", "picnic", "the picnic", "states", "p1", "If it rains, the picnic moves to the gym."),
                        "CONDITION", "p1", "If it rains"),
                it("DECISION", "ASSERTED", "picnic", "the picnic", "decides", "p2", "Decision: the picnic is on Saturday at the lake.")), "p1", "p2");
        assertEquals(1, itemFrom("p1").assertion().qualifiers().size(), "setup: CONDITION qualifier stored");
        assertEquals(Assertion.Validity.ACTIVE, itemFrom("p1").assertion().validity(), "the rain contingency was retired by the date decision");
    }

    @Test void d3_deletingTheSupersedingMessageLeavesValidityConsistentWithRelations() {
        msg("m1", "u-ana", 0, "Dinner is at 7 PM.");
        msg("m2", "u-ana", 5, "Sorry, I got that wrong. Dinner is at 8 PM.");
        var r = run(List.of(
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "dinner", "Dinner", "states", "m1", "Dinner is at 7 PM."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "dinner", "Dinner", "states", "m2", "Dinner is at 8 PM.")), "m1", "m2");
        assertEquals(1, r.superseded(), "setup");
        DeletionService del = new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, EventPublisher.NONE, 50);
        Instant at = store.find(g, "m2").orElseThrow().sentAt().plusSeconds(60);
        assertTrue(del.delete(g, "m2", at, DeletionSource.PLATFORM_EVENT).isPresent(), "setup: deletion ran");
        List<KnowledgeRelation> rel = store.relations(g);
        for (KnowledgeItem k : store.knowledge(g)) {
            if (k.assertion().validity() != Assertion.Validity.SUPERSEDED) continue;
            assertTrue(rel.stream().anyMatch(x -> x.kind() == KnowledgeRelation.Kind.SUPERSEDES && x.toId().equals(k.id())),
                    "item " + k.asserting().get(0).messageId() + " is SUPERSEDED but no SUPERSEDES relation points to it; relations=" + rel);
        }
    }

    @Test void d3_preservation_aChangedDecisionStillSupersedesAndARestatementDoesNot() {
        msg("p1", "u-ana", 0, "Let's do the picnic on Friday.");
        msg("p2", "u-ana", 5, "Decision: the picnic is on Saturday.");
        msg("b1", "u-ben", 6, "The bus leaves at 9 AM sharp.");
        msg("b2", "u-ben", 9, "As I said, the bus actually leaves at 9 AM sharp.");
        run(List.of(
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "picnic", "the picnic", "states", "p1", "Let's do the picnic on Friday."),
                it("DECISION", "ASSERTED", "picnic", "the picnic", "decides", "p2", "Decision: the picnic is on Saturday."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "bus", "The bus", "states", "b1", "The bus leaves at 9 AM sharp."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "bus", "the bus", "states", "b2", "As I said, the bus actually leaves at 9 AM sharp.")),
                "p1", "p2", "b1", "b2");
        assertEquals(Assertion.Validity.SUPERSEDED, itemFrom("p1").assertion().validity(), "Friday plan replaced by the Saturday decision");
        assertEquals(Assertion.Validity.ACTIVE, itemFrom("b1").assertion().validity(), "a restatement never replaces");
        assertEquals(Assertion.Validity.ACTIVE, itemFrom("b2").assertion().validity());
    }

    // ---------------------------------------------------------------- Defect 4: named-person relevance

    void anaFixture() {
        msg("a1", "u-ana", 0, "I'll bring the lemonade for the picnic.");
        msg("a2", "u-ana", 2, "My cat is called Pixel.");
        msg("b1", "u-ben", 3, "I'll bring the sandwiches for the picnic.");
        run(List.of(
                it("CONFIRMED_COMMITMENT", "ASSERTED", "lemonade", "the lemonade", "commits", "a1", "I'll bring the lemonade for the picnic."),
                it("ATTRIBUTED_STATEMENT", "ASSERTED", "cat", "My cat", "states", "a2", "My cat is called Pixel."),
                it("CONFIRMED_COMMITMENT", "ASSERTED", "sandwiches", "the sandwiches", "commits", "b1", "I'll bring the sandwiches for the picnic.")),
                "a1", "a2", "b1");
        assertEquals(3, store.knowledge(g).size(), "setup");
    }

    @Test void d4_aNamedMemberQuestionWithNoTopicalItemReturnsNothingFromKnowledge() {
        anaFixture();
        Optional<GroundedAnswer> a = composer().answer(g, "When does Ana's flight land?");
        assertTrue(a.isEmpty(), "Ana's unrelated items were returned for a flight question: " + a.map(GroundedAnswer::text).orElse(""));
    }

    @Test void d4_queryServiceDoesNotCiteTheMembersUnrelatedLines() {
        anaFixture();
        GroundedAnswer a = query().ask(g, "u-asker", "When does Ana's flight land?");
        assertFalse(cited(a).contains("a1") || cited(a).contains("a2"), "unrelated lines cited: " + a.text());
        assertFalse(a.text().contains("lemonade") || a.text().contains("Pixel"), a.text());
    }

    @Test void d4_preservation_broadHistoryAndTopicalQuestionsAboutAMemberStillAnswer() {
        anaFixture();
        GroundedAnswer broad = composer().answer(g, "What did Ana say?").orElseThrow();
        assertTrue(cited(broad).contains("a1") && cited(broad).contains("a2"), broad.text());
        assertFalse(cited(broad).contains("b1"), broad.text());
        GroundedAnswer topical = composer().answer(g, "What is Ana bringing to the picnic?").orElseThrow();
        assertEquals("a1", cited(topical).get(0), topical.text());
        assertTrue(topical.text().contains("lemonade"), topical.text());
    }
}
