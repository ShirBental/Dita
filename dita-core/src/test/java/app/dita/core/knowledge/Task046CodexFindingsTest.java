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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-046 round 3: the counterexamples from Codex's independent review of revision c2e74c7e (C1–C5), verbatim where
 * Codex gave them, each with a positive control. The adapter command-edit case (C4) is in dita-telegram TelegramAdapterTest.
 */
class Task046CodexFindingsTest {
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    final GroupScope g = new GroupScope("g-046c");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    final Map<String, String> names = Map.of("u-ana", "Ana");

    void msg(String id, int min, String reply, String text) {
        store.append(g, new Message(id, g.groupId(), "u-ana", T0.plusSeconds(60L * min), reply, text, SourceKind.SYNTHETIC, false));
    }
    void msg(String id, int min, String text) { msg(id, min, null, text); }

    static Proposal.Item it(String status, String subjectId, String subject, String predicate, String mid, String quote) {
        return new Proposal.Item(status, "ASSERTED", subjectId, subject, predicate, null, null, List.of(new Proposal.Quote(mid, quote)), List.of(), List.of());
    }

    ExtractionService.Report run(KnowledgeProposer p, String... ids) {
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), p, EventPublisher.NONE, now, 50, Duration.ofMinutes(30));
        for (String id : ids) x.enqueue(g, store.find(g, id).orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        return x.process(g, b.id()).orElseThrow();
    }

    void run(List<Proposal.Item> items, String... ids) { run((s, b, t, c) -> new Proposal(items, "t046c"), ids); }

    Optional<KnowledgeItem> item(String mid) {
        return store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals(mid)).findFirst();
    }
    Assertion.Validity validity(String mid) { return item(mid).orElseThrow().assertion().validity(); }
    Assertion.Status status(String mid) { return item(mid).orElseThrow().assertion().status(); }

    // ---- C1: every supersession path needs same-property evidence ----

    @Test void c1_anUpdateDecisionDoesNotRetireTheBudget() {
        msg("b1", 0, "The picnic budget is 50 dollars.");
        msg("b2", 5, "Update: Decision: the picnic starts at noon.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "picnic", "The picnic", "states", "b1", "The picnic budget is 50 dollars."),
                it("DECISION", "picnic", "the picnic", "decides", "b2", "Update: Decision: the picnic starts at noon.")), "b1", "b2");
        assertEquals(Assertion.Validity.ACTIVE, validity("b1"));
    }

    @Test void c1_aDateDecisionDoesNotRetireAChairOffer() {
        msg("o1", 0, "I can bring chairs to the picnic.");
        msg("o2", 5, "Decision: the picnic is Friday.");
        run(List.of(it("OFFER", "picnic", "the picnic", "offers", "o1", "I can bring chairs to the picnic."),
                it("DECISION", "picnic", "the picnic", "decides", "o2", "Decision: the picnic is Friday.")), "o1", "o2");
        assertEquals(Assertion.Validity.ACTIVE, validity("o1"));
    }

    @Test void c1_control_aChangedBudgetAndAConfirmedOfferStillReplace() {
        msg("p1", 0, "The picnic budget is 50 dollars.");
        msg("p2", 5, "Update: the picnic budget is 80 dollars now.");
        msg("p3", 6, "I can bring the chairs to the picnic.");
        msg("p4", 9, "Confirmed: I'll bring the chairs.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "budget", "The picnic budget", "states", "p1", "The picnic budget is 50 dollars."),
                it("ATTRIBUTED_STATEMENT", "budget", "the picnic budget", "states", "p2", "Update: the picnic budget is 80 dollars now."),
                it("OFFER", "chairs", "the chairs", "offers", "p3", "I can bring the chairs to the picnic."),
                it("CONFIRMED_COMMITMENT", "chairs", "the chairs", "commits", "p4", "Confirmed: I'll bring the chairs.")), "p1", "p2", "p3", "p4");
        assertEquals(Assertion.Validity.SUPERSEDED, validity("p1"));
        assertEquals(Assertion.Validity.SUPERSEDED, validity("p3"));
    }

    // ---- C2: disqualifiers are judged on the same clause as the affirmative markers ----

    @Test void c2_aTrimmedNegationIsNeverACommitment() {
        msg("n1", 0, "I will not bring the picnic cake.");
        run(List.of(it("CONFIRMED_COMMITMENT", "cake", "the picnic cake", "commits", "n1", "bring the picnic cake.")), "n1");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, status("n1"));
        var a = new KnowledgeAnswerComposer(store, 200, names::get).answer(g, "Who brings the picnic cake?");
        assertTrue(a.isEmpty() || !a.get().text().contains("committed to"), a.map(x -> x.text()).orElse(""));
    }

    @Test void c2_aTrimmedQuestionMarkIsNeverADecision() {
        msg("q1", 0, "Decision: the picnic is Friday?");
        run(List.of(it("DECISION", "picnic", "the picnic", "decides", "q1", "Decision: the picnic is Friday")), "q1");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, status("q1"));
    }

    @Test void c2_aTrimmedConditionIsNeverAnUnqualifiedCommitment() {
        msg("i1", 0, "If approved, I will bring the picnic cake.");
        run(List.of(it("CONFIRMED_COMMITMENT", "cake", "the picnic cake", "commits", "i1", "I will bring the picnic cake.")), "i1");
        assertTrue(status("i1") != Assertion.Status.CONFIRMED_COMMITMENT, "conditional: at most an offer, got " + status("i1"));
    }

    @Test void c2_aNegatedOfferIsNotAnOffer() {
        msg("x1", 0, "I can not bring the picnic cake.");
        run(List.of(it("OFFER", "cake", "the picnic cake", "offers", "x1", "I can not bring the picnic cake.")), "x1");
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, status("x1"));
    }

    @Test void c2_control_genuineActsKeepTheirLabels() {
        msg("g1", 0, "Decision: the picnic is on Friday.");
        msg("g2", 1, "Confirmed: I will bring the picnic cake.");
        msg("g3", 2, "I can bring the picnic blankets.");
        run(List.of(it("DECISION", "picnic", "the picnic", "decides", "g1", "Decision: the picnic is on Friday."),
                it("CONFIRMED_COMMITMENT", "cake", "the picnic cake", "commits", "g2", "Confirmed: I will bring the picnic cake."),
                it("OFFER", "blankets", "the picnic blankets", "offers", "g3", "I can bring the picnic blankets.")), "g1", "g2", "g3");
        assertEquals(Assertion.Status.DECISION, status("g1"));
        assertEquals(Assertion.Status.CONFIRMED_COMMITMENT, status("g2"));
        assertEquals(Assertion.Status.OFFER, status("g3"));
    }

    // ---- C3: reply parents are dependencies ----

    @Test void c3_aReplyParentEditedDuringTheProposerCallDropsTheReplyAndForgesNoRelation() {
        msg("r1", 0, "Let's hold the picnic on Friday.");
        msg("r2", 1, "The picnic is on Friday.");
        msg("r3", 2, "r2", "Decision: move it to Saturday.");
        List<Proposal.Item> items = List.of(it("ATTRIBUTED_STATEMENT", "picnic", "the picnic", "states", "r1", "Let's hold the picnic on Friday."),
                it("DECISION", "picnic", "The picnic", "decides", "r3", "Decision: move it to Saturday."));
        var rep = run((s, b, t, c) -> { store.edit(g, "r2", "The rehearsal is on Friday.", T0.plusSeconds(300)); return new Proposal(items, "t046c"); },
                "r1", "r3");
        assertEquals(ExtractionBatch.Status.DONE, rep.status());
        assertTrue(item("r3").isEmpty(), "validated against the parent's old revision: dropped");
        assertEquals(Assertion.Validity.ACTIVE, validity("r1"), "no false supersession of the picnic plan");
        assertTrue(store.relations(g).isEmpty());
    }

    @Test void c3_aLaterParentEditRemovesTheReplyWhoseSubjectCameFromIt() {
        msg("s1", 0, "Let's hold the picnic on Friday.");
        msg("s2", 1, "The picnic is on Friday.");
        msg("s3", 2, "s2", "Decision: move it to Saturday.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "picnic", "the picnic", "states", "s1", "Let's hold the picnic on Friday."),
                it("DECISION", "picnic", "The picnic", "decides", "s3", "Decision: move it to Saturday.")), "s1", "s3");
        assertEquals(Assertion.Validity.SUPERSEDED, validity("s1"), "fixture: the reply's decision retires the plan");
        new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, EventPublisher.NONE, 50)
                .edit(g, "s2", "The rehearsal is on Friday.", T0.plusSeconds(900));
        assertTrue(item("s3").isEmpty());
        // DEC-038 (N-110): the invalidated decision was the only supersession; the plan is NOT current again
        assertEquals(Assertion.Validity.UNRESOLVED, validity("s1"));
    }

    // ---- C5: a shared verb is not the requested object ----

    @Test void c5_aSharedVerbDoesNotAnswerAQuestionAboutAnotherObject() {
        msg("v1", 0, "I booked the picnic shelter.");
        run(List.of(it("CONFIRMED_COMMITMENT", "shelter", "the picnic shelter", "commits", "v1", "I booked the picnic shelter.")), "v1");
        var c = new KnowledgeAnswerComposer(store, 200, names::get);
        assertTrue(c.answer(g, "Has Ana booked the submarine?").isEmpty());
        assertTrue(c.answer(g, "Has Ana booked the picnic shelter?").isPresent(), "positive control");
        assertFalse(c.answer(g, "What did Ana say?").isEmpty(), "broad history still answers");
    }
}
