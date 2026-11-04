package app.dita.core.knowledge;

import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-046 round 2: one regression test per confirmed internal-review counterexample (Bruce B46-*, Natasha N-*, Tony T46-*).
 * Synthetic text only. Each test names its finding.
 */
class Task046ReviewFindingsTest {
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    final GroupScope g = new GroupScope("g-046b");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    final Map<String, String> names = Map.of("u-ana", "Ana", "u-dana", "דנה");

    void msg(String id, String author, int min, String text) {
        store.append(g, new Message(id, g.groupId(), author, T0.plusSeconds(60L * min), null, text, SourceKind.SYNTHETIC, false));
    }

    static Proposal.Item it(String status, String subjectId, String subject, String predicate, String mid, String quote) {
        return new Proposal.Item(status, "ASSERTED", subjectId, subject, predicate, null, null, List.of(new Proposal.Quote(mid, quote)), List.of(), List.of());
    }

    void run(MessageStore s, List<Proposal.Item> items, String... ids) {
        ExtractionService x = new ExtractionService(s, new InMemoryExtractionQueue(), (sc, b, t, c) -> new Proposal(items, "t046b"),
                EventPublisher.NONE, now, 50, Duration.ofMinutes(30));
        for (String id : ids) x.enqueue(g, s.find(g, id).orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        var rep = x.process(g, b.id()).orElseThrow();
        assertEquals(app.dita.core.knowledge.ExtractionBatch.Status.DONE, rep.status(), String.valueOf(rep.error()));
    }

    void run(List<Proposal.Item> items, String... ids) { run(store, items, ids); }

    KnowledgeItem byMid(String mid) {
        return store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals(mid)).findFirst().orElseThrow();
    }

    Assertion.Validity validity(String mid) { return byMid(mid).assertion().validity(); }

    /** Earlier fact by u-ana, later decision by u-ana on the same subject: the fact must stay ACTIVE. */
    void factSurvives(String fact, String decision, String subject) {
        msg("f1", "u-ana", 0, fact);
        msg("f2", "u-ana", 5, decision);
        run(List.of(it("ATTRIBUTED_STATEMENT", "s", subject, "states", "f1", fact), it("DECISION", "s", subject, "decides", "f2", decision)), "f1", "f2");
        assertEquals(Assertion.Validity.ACTIVE, validity("f1"), fact + " / " + decision);
    }

    // ---- B46-1: bare nouns/verbs are not proposal markers ----
    @Test void b46_1_aVeganOptionIsAFactNotAnOption() { factSurvives("The hall has a vegan option and parking.", "Decision: we book the hall.", "The hall"); }
    @Test void b46_1_reviewsSuggestIsNotAProposal() { factSurvives("Reviews suggest the hall is noisy at night.", "Decision: we book the hall.", "the hall"); }
    @Test void b46_1_hebrewPresentTenseOffersIsNotAProposal() { factSurvives("האולם מציע חניה חינם לאורחים.", "החלטה: סוגרים את האולם לאירוע.", "האולם"); }
    @Test void b46_1_noIdeaIsNotAProposal() { factSurvives("No idea why, but the hall has no wifi at all.", "Decision: we book the hall.", "the hall"); }

    // ---- B46-2: the decision must be about the same property ----
    @Test void b46_2_aSnacksProposalSurvivesADateDecision() {
        msg("p1", "u-ana", 0, "Let's bring snacks to the party.");
        msg("p2", "u-ana", 5, "Decision: the party is Friday; we'll still bring snacks as I said.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "party", "the party", "states", "p1", "Let's bring snacks to the party."),
                it("DECISION", "party", "the party", "decides", "p2", "Decision: the party is Friday")), "p1", "p2");
        assertEquals(Assertion.Validity.ACTIVE, validity("p1"));
    }

    @Test void b46_2_hebrewBalloonsSurviveADayDecision() { factSurvives("בואו נביא בלונים למסיבה.", "החלטה: המסיבה ביום שישי.", "מסיבה"); }

    @Test void b46_2_aCompetingPlaceProposalIsStillReplaced() {
        msg("q1", "u-ana", 0, "Let's go to Eilat for the trip.");
        msg("q2", "u-ana", 5, "Decision: the trip is to Haifa.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "trip", "the trip", "states", "q1", "Let's go to Eilat for the trip."),
                it("DECISION", "trip", "the trip", "decides", "q2", "Decision: the trip is to Haifa.")), "q1", "q2");
        assertEquals(Assertion.Validity.SUPERSEDED, validity("q1"));
    }

    // ---- B46-3 / B46-4: calendar rule ----
    @Test void b46_3_aClosingDayIsNotTheEventDay() { factSurvives("The hall is closed on Monday.", "Decision: the party at the hall is on Saturday.", "the hall"); }
    @Test void b46_3_hebrewOrdinalFirstIsNotSunday() { factSurvives("במסיבה האוכל מוגש ראשון והעוגה אחריו.", "החלטה: המסיבה ביום שישי.", "מסיבה"); }

    @Test void b46_4_hebrewDatesAreCalendarValues() {
        msg("h1", "u-dana", 0, "המסיבה ב-14 בנובמבר.");
        msg("h2", "u-dana", 5, "החלטה: המסיבה ב-15 בנובמבר.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "party", "המסיבה", "states", "h1", "המסיבה ב-14 בנובמבר."),
                it("DECISION", "party", "המסיבה", "decides", "h2", "החלטה: המסיבה ב-15 בנובמבר.")), "h1", "h2");
        assertEquals(Assertion.Validity.SUPERSEDED, validity("h1"));
    }

    @Test void b46_4_abbreviatedAndNumericDatesAreCalendarValues() {
        msg("n1", "u-ana", 0, "The picnic is on Nov 14.");
        msg("n2", "u-ana", 5, "Decision: the picnic is on 15/11.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "picnic", "The picnic", "states", "n1", "The picnic is on Nov 14."),
                it("DECISION", "picnic", "the picnic", "decides", "n2", "Decision: the picnic is on 15/11.")), "n1", "n2");
        // different encodings of a date are both detected as dates and differ, so the earlier one is replaced
        assertEquals(Assertion.Validity.SUPERSEDED, validity("n1"));
    }

    // ---- B46-5: a contingency survives an update that does not touch its condition ----
    @Test void b46_5_aRainPlanSurvivesAnUnrelatedUpdate() {
        msg("r1", "u-ana", 0, "If it rains, the picnic is at the gym.");
        msg("r2", "u-ana", 5, "Update: the picnic is on Sunday.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "picnic", "the picnic", "states", "r1", "If it rains, the picnic is at the gym."),
                it("ATTRIBUTED_STATEMENT", "picnic", "the picnic", "states", "r2", "Update: the picnic is on Sunday.")), "r1", "r2");
        assertEquals(Assertion.Validity.ACTIVE, validity("r1"));
    }

    /** Known limit (recorded): a REVISED contingency ("Update: if it rains, … instead") is not detected, because a conditional
     *  later claim never replaces anything (pre-existing hypothetical veto). Missed supersession = the safe direction. */
    @Test void b46_5_knownLimit_aRevisedRainPlanIsNotDetected() {
        msg("s1", "u-ana", 0, "If it rains, the picnic is at the gym.");
        msg("s2", "u-ana", 5, "Update: if it rains, the picnic is at the library instead.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "picnic", "the picnic", "states", "s1", "If it rains, the picnic is at the gym."),
                it("ATTRIBUTED_STATEMENT", "picnic", "the picnic", "states", "s2", "Update: if it rains, the picnic is at the library instead.")), "s1", "s2");
        assertEquals(Assertion.Validity.ACTIVE, validity("s1"));
        assertEquals(Assertion.Validity.ACTIVE, validity("s2"), "both stay visible");
    }

    // ---- B46-6 / B46-7: validator ----
    @Test void b46_6_finalThoughtIsNotADecision() {
        msg("v1", "u-ana", 0, "Final thought, let's meet at the library.");
        run(List.of(it("DECISION", "meet", "the library", "decides", "v1", "Final thought, let's meet at the library.")), "v1");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, byMid("v1").assertion().status());
    }

    @Test void b46_6_aQuestionIsNotADecision() {
        msg("v2", "u-ana", 0, "Decision: picnic on Friday?");
        run(List.of(it("DECISION", "picnic", "picnic", "decides", "v2", "Decision: picnic on Friday?")), "v2");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, byMid("v2").assertion().status());
    }

    @Test void b46_6_hebrewFinalExamIsNotADecision() {
        msg("v3", "u-dana", 0, "המבחן הסופי ביום שני, בואו ניפגש בספרייה.");
        run(List.of(it("DECISION", "meet", "בספרייה", "decides", "v3", "המבחן הסופי ביום שני, בואו ניפגש בספרייה.")), "v3");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, byMid("v3").assertion().status());
    }

    @Test void b46_7_tryingIsAnOfferNotACommitment() {
        msg("c1", "u-ana", 0, "I'll try to bring the cake.");
        run(List.of(it("CONFIRMED_COMMITMENT", "cake", "the cake", "commits", "c1", "I'll try to bring the cake.")), "c1");
        assertEquals(Assertion.Status.OFFER, byMid("c1").assertion().status());
    }

    @Test void b46_7_aFactLabelledOfferIsAStatement() {
        msg("c2", "u-ana", 0, "The hall has forty folding chairs.");
        run(List.of(it("OFFER", "chairs", "folding chairs", "offers", "c2", "The hall has forty folding chairs.")), "c2");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, byMid("c2").assertion().status());
    }

    // ---- B46-8 / B46-9: named member ----
    @Test void b46_8_aSpecificCommitmentQuestionNeedsTheTopic() {
        msg("a1", "u-ana", 0, "I'll bring the cake to the party.");
        run(List.of(it("CONFIRMED_COMMITMENT", "cake", "the cake", "commits", "a1", "I'll bring the cake to the party.")), "a1");
        var c = new KnowledgeAnswerComposer(store, 200, names::get);
        assertTrue(c.answer(g, "Did Ana promise to pay the deposit?").isEmpty());
        assertTrue(c.answer(g, "What is Ana responsible for?").isPresent());
    }

    @Test void b46_9_broadHistoryQuestionsStillListTheMembersClaims() {
        msg("b1", "u-ana", 0, "I'll bring the cake to the party.");
        msg("b2", "u-dana", 1, "אני אביא את הבלונים למסיבה.");
        run(List.of(it("CONFIRMED_COMMITMENT", "cake", "the cake", "commits", "b1", "I'll bring the cake to the party."),
                it("CONFIRMED_COMMITMENT", "balloons", "הבלונים", "commits", "b2", "אני אביא את הבלונים למסיבה.")), "b1", "b2");
        var c = new KnowledgeAnswerComposer(store, 200, names::get);
        for (String q : List.of("What's new with Ana?", "Did Ana say anything new?"))
            assertTrue(c.answer(g, q).map(a -> a.text().contains("[b1]")).orElse(false), q);
        for (String q : List.of("מה דנה סיפרה?", "האם דנה אמרה משהו?", "מה דנה אמרה לנו?", "ומה דנה אמרה?"))
            assertTrue(c.answer(g, q).map(a -> a.text().contains("[b2]")).orElse(false), q);
    }

    // ---- N-105 / B46-11: an item from a since-edited message never retires anything ----
    // DEC-038 (N-110): the edited superseder no longer retires d1 (no relation), but d1 is UNRESOLVED, not current again
    @Test void n105_anEditedSupersederNoLongerRetiresTheEarlierClaimWhichStaysUnresolved() {
        msg("d1", "u-ana", 0, "Dinner is at 7 PM at the community hall.");
        msg("d2", "u-ana", 2, "Sorry, I got that wrong earlier. Dinner is at 8 PM at the community hall.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "d1", "Dinner is at 7 PM at the community hall."),
                it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "d2", "Dinner is at 8 PM at the community hall.")), "d1", "d2");
        store.edit(g, "d2", "Dinner is cancelled tonight, sorry everyone, see you next week.", T0.plusSeconds(600));   // a non-service edit
        ValidityReconciler.reconcile(store, g);
        assertEquals(Assertion.Validity.UNRESOLVED, validity("d1"));
        assertTrue(store.relations(g).isEmpty());
    }

    // ---- N-103 / T46-3: a backend without relations (no V5) still applies edits ----
    @Test void n103_anEditOnABackendWithoutRelationsIsApplied() {
        MessageStore noRelations = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(), new Class<?>[]{MessageStore.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("relations") || m.getName().equals("addRelation") || m.getName().equals("removeRelations"))
                        throw new UnsupportedOperationException("no V5");
                    try { return m.invoke(store, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        msg("e1", "u-ana", 0, "The bus leaves from the school gate at 8 AM.");
        var out = new DeletionService(noRelations, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, EventPublisher.NONE, 50)
                .edit(g, "e1", "The bus leaves from the library at 9 AM.", T0.plusSeconds(600));
        assertEquals("The bus leaves from the library at 9 AM.", store.find(g, "e1").orElseThrow().text());
        assertTrue(out.removedRelationKeys().isEmpty());
    }

    // ---- T46-2: extraction no longer writes validity on its own ----
    @Test void t46_2_extractionAndReconcilerAgree() {
        msg("x1", "u-ana", 0, "Dinner is at 7 PM at the community hall.");
        msg("x2", "u-ana", 2, "Sorry, I got that wrong earlier. Dinner is at 8 PM at the community hall.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "x1", "Dinner is at 7 PM at the community hall."),
                it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "x2", "Dinner is at 8 PM at the community hall.")), "x1", "x2");
        assertEquals(Assertion.Validity.SUPERSEDED, validity("x1"));
        assertTrue(ValidityReconciler.reconcile(store, g).isEmpty(), "nothing left to reconcile after extraction");
        assertFalse(store.relations(g).isEmpty());
    }
}
