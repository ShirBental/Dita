package app.dita.core.knowledge;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.Assertion.Validity;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-047 Codex independent review (BLOCKED, 2026-11-01): Codex's EXACT reproductions, transcribed from its harness
 * (/tmp/dita-task047-review/Independent047.java, Evaluation047.java; same texts, ids, times and call sequences), each with
 * negative and positive controls, asserting the USER-FACING answer, not only internal state. Synthetic text only.
 * Finding 4 (RelationArms) lives in dita-semeval (Task047ArmBaselineTest).
 */
class Task047CodexReviewTest {
    static final GroupScope G = new GroupScope("independent047");
    static final Instant T = Instant.parse("2026-12-01T12:00:00Z");

    static Message m(String id, int sec, String reply, String text) {
        return new Message(id, G.groupId(), "ana", T.plusSeconds(sec), reply, text, SourceKind.SYNTHETIC, false);
    }

    static Proposal.Item p(String mid, String sid, String subject, String text) {
        return new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", sid, subject, "states", null, null,
                List.of(new Proposal.Quote(mid, text)), List.of(), List.of());
    }

    static void extract(MessageStore s, List<Proposal.Item> ps, String... ids) {
        var x = new ExtractionService(s, new InMemoryExtractionQueue(), (a, b, c, d) -> new Proposal(ps, "independent047"),
                EventPublisher.NONE, Clock.fixed(T.plusSeconds(5000), ZoneOffset.UTC), 50);
        for (String id : ids) x.enqueue(G, s.find(G, id).orElseThrow());
        var b = x.collect(G).getFirst();
        x.approve(G, b.id(), "local-synthetic");
        assertEquals(ExtractionBatch.Status.DONE, x.process(G, b.id()).orElseThrow().status());
    }

    /** Codex fixture: A "Dinner is at 7 PM." (+ " Parking is free." when mixed), B "Correction: dinner is at 8 PM.". */
    static InMemoryMessageStore dinner(boolean mixed) {
        var s = new InMemoryMessageStore();
        String a = "Dinner is at 7 PM.";
        if (mixed) a += " Parking is free.";
        s.append(G, m("a", 0, null, a));
        s.append(G, m("b", 60, null, "Correction: dinner is at 8 PM."));
        var ps = new ArrayList<Proposal.Item>();
        ps.add(p("a", "dinner", "Dinner", "Dinner is at 7 PM."));
        if (mixed) ps.add(p("a", "parking", "Parking", "Parking is free."));
        ps.add(p("b", "dinner", "dinner", "dinner is at 8 PM."));
        extract(s, ps, "a", "b");
        return s;
    }

    static DeletionService changes(MessageStore s) {
        return new DeletionService(s, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, EventPublisher.NONE, 50);
    }

    static KnowledgeItem by(MessageStore s, String mid, String sid) {
        return s.knowledge(G).stream().filter(k -> k.sourceMessageIds().contains(mid) && k.assertion().subjectId().equals(sid))
                .findFirst().orElseThrow();
    }

    static QueryService query(MessageStore s, Retriever retriever, boolean limited) {
        var access = new HistoryAccessFilter(limited ? HistoryAccess.SINCE_REQUESTER_JOINED : HistoryAccess.FULL_GROUP_HISTORY,
                (g, id) -> Optional.of(T.minusSeconds(1)), s);
        return new QueryService(s, retriever, access, 10, 300, id -> "Ana");
    }

    static GroundedAnswer ask(MessageStore s, boolean limited, String q) {
        return query(s, new LexicalRetriever(s), limited).ask(G, "viewer", q);
    }

    static List<String> cited(GroundedAnswer a) { return a.citations().stream().map(Citation::messageId).toList(); }

    static void assertNoCurrentDinnerTime(GroundedAnswer a, String label) {
        assertFalse(a.text().contains("7 PM") || a.text().contains("8 PM"), label + " → " + a.text());
    }

    // ================================================================== Finding 1 (High): mixed messages leak via fallback

    @Test void f1_mixedMessage_earlyDeletion_fallbackNeverQuotesTheUnresolvedClaim_fullAndLimitedHistory() {
        var s = dinner(true);
        changes(s).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity());
        assertEquals(Validity.ACTIVE, by(s, "a", "parking").assertion().validity());
        for (boolean limited : List.of(false, true)) {
            GroundedAnswer a = ask(s, limited, "What time is dinner?");
            assertNoCurrentDinnerTime(a, "limited=" + limited);              // Codex actual: "Dinner is at 7 PM. Parking is free."
        }
    }

    @Test void f1_mixedMessage_lateDeletion_fallbackNeverQuotesTheUnresolvedClaim() {
        var s = dinner(true);
        changes(s).delete(G, "b", T.plusSeconds(1200), DeletionSource.FORGET_COMMAND);
        for (boolean limited : List.of(false, true)) assertNoCurrentDinnerTime(ask(s, limited, "What time is dinner?"), "late limited=" + limited);
    }

    /** Positive control: the unrelated ACTIVE claim in the same message is still answered, from its validated span only. */
    @Test void f1_control_theActiveClaimInTheMixedMessageIsStillAnsweredWithoutTheUnresolvedWords() {
        var s = dinner(true);
        changes(s).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
        for (boolean limited : List.of(false, true)) {
            GroundedAnswer a = ask(s, limited, "Is parking free?");
            assertTrue(a.found() && a.text().contains("Parking is free"), "limited=" + limited + " → " + a.text());
            assertFalse(a.text().contains("7 PM"), "the unresolved words are never quoted alongside: " + a.text());
        }
    }

    /** Negative controls: no deletion → the mixed message is quoted as before; single-claim message still excluded. */
    @Test void f1_controls_unaffectedMessagesAndTheSingleClaimCase() {
        var live = dinner(true);
        GroundedAnswer a = ask(live, true, "Is parking free?");
        assertTrue(a.text().contains("Parking is free") && cited(a).contains("a"), a.text());
        var single = dinner(false);
        changes(single).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
        for (boolean limited : List.of(false, true)) assertNoCurrentDinnerTime(ask(single, limited, "What time is dinner?"), "single");
    }

    // ================================================================== Finding 2 (High): stale exclusion snapshot

    /** Codex: limited-history message path; B deleted DURING retrieval, after ask() computed its exclusion set. */
    @Test void f2_withdrawalDuringRetrieval_finalGateRejectsTheNowUnresolvedEvidence() {
        var race = dinner(false);
        Retriever withdrawing = (g, q, k) -> {
            changes(race).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
            return List.of(new Hit("a", 1, "lexical", false));
        };
        GroundedAnswer a = query(race, withdrawing, true).ask(G, "viewer", "What time is dinner?");
        assertEquals(Validity.UNRESOLVED, by(race, "a", "dinner").assertion().validity());
        assertNoCurrentDinnerTime(a, "race");                                // Codex actual: "Dinner is at 7 PM." [msg a]
        assertFalse(cited(a).contains("a"));
    }

    @Test void f2_withdrawalDuringRetrieval_fullHistory() {
        var race = dinner(false);
        Retriever withdrawing = (g, q, k) -> {
            changes(race).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
            return List.of(new Hit("a", 1, "lexical", false));
        };
        // full history: the knowledge path answers first (B still live → "8 PM" is correct); the old value never appears
        GroundedAnswer a = query(race, withdrawing, false).ask(G, "viewer", "What time is dinner?");
        assertFalse(a.text().contains("7 PM"), a.text());
    }

    /** Positive control: a concurrent change to an UNRELATED message does not make the gate reject valid evidence. */
    @Test void f2_control_anUnrelatedConcurrentDeletionDoesNotRejectValidEvidence() {
        var s = new InMemoryMessageStore();
        s.append(G, m("p", 0, null, "Parking is free on Sundays."));
        s.append(G, m("x", 30, null, "See you all there."));
        Retriever unrelated = (g, q, k) -> {
            changes(s).delete(G, "x", T.plusSeconds(60), DeletionSource.FORGET_COMMAND);
            return List.of(new Hit("p", 1, "lexical", false));
        };
        GroundedAnswer a = query(s, unrelated, true).ask(G, "viewer", "Is parking free?");
        assertTrue(a.found() && cited(a).contains("p"), a.text());
    }

    // ================================================================== Finding 3 (High): rejected relation vs withdrawal

    /** Codex picnic fixture: the current rules falsely supersede A ("at" slot). Both sources stay live. */
    static InMemoryMessageStore picnic() {
        var s = new InMemoryMessageStore();
        s.append(G, m("a", 0, null, "Let's put picnic signs at the entrance."));
        s.append(G, m("b", 60, null, "Decision: the picnic starts at noon."));
        var later = new Proposal.Item("DECISION", "ASSERTED", "picnic", "picnic", "decides", null, null,
                List.of(new Proposal.Quote("b", "Decision: the picnic starts at noon.")), List.of(), List.of());
        extract(s, List.of(p("a", "picnic", "picnic", "Let's put picnic signs at the entrance."), later), "a", "b");
        return s;
    }

    static List<String> supersedesKeys(MessageStore s) {
        return s.relations(G).stream().filter(r -> r.kind() == KnowledgeRelation.Kind.SUPERSEDES).map(KnowledgeRelation::key).toList();
    }

    /** The reason-aware repair path: the relation is REJECTED as an invalid inference (not a withdrawn correction). */
    static void reject(MessageStore s, List<String> keys) { ValidityReconciler.rejectRelations(s, G, keys); }

    @Test void f3_rejectingAFalseRelationRestoresTheEarlierClaimAndItsAnswer() {
        var s = picnic();
        assertEquals(Validity.SUPERSEDED, by(s, "a", "picnic").assertion().validity(), "fixture: Codex's false production edge");
        reject(s, supersedesKeys(s));
        assertEquals(Validity.ACTIVE, by(s, "a", "picnic").assertion().validity(), "no replacement occurred");
        assertEquals(Validity.ACTIVE, by(s, "b", "picnic").assertion().validity());
        GroundedAnswer a = ask(s, false, "Where should we put picnic signs?");
        assertTrue(a.text().contains("entrance"), a.text());
    }

    /** Negative control: a GENUINE withdrawal is still protected; rejecting nothing never reactivates it. */
    @Test void f3_control_genuineWithdrawalStaysUnresolvedThroughTheRepairPath() {
        var s = dinner(false);
        changes(s).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
        reject(s, supersedesKeys(s));                                         // nothing left to reject
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity());
        assertNoCurrentDinnerTime(ask(s, false, "What time is dinner?"), "withdrawn");
    }

    /** Negative control: an unlabelled relation removal (no reason given) stays fail-closed, as in Codex's literal sequence. */
    @Test void f3_control_unlabelledRemovalStaysFailClosed() {
        var s = dinner(false);
        s.removeRelations(G, supersedesKeys(s));
        ValidityReconciler.reconcile(s, G);
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity());
    }

    /** Negative control: withdrawal history survives a later false relation that is then rejected. */
    @Test void f3_control_withdrawalHistorySurvivesAFalseRelationThatIsLaterRejected() {
        var s = dinner(false);
        changes(s).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
        String a = by(s, "a", "dinner").id();
        s.append(G, m("c", 300, null, "The music starts at nine."));
        extract(s, List.of(p("c", "music", "The music", "The music starts at nine.")), "c");
        // structural: a false relation (as a mistaken rule or arm would add) retires a again
        s.addRelation(G, new KnowledgeRelation(G.groupId(), by(s, "c", "music").id(), a, KnowledgeRelation.Kind.SUPERSEDES, "c"));
        ValidityReconciler.reconcile(s, G);
        List<String> keys = supersedesKeys(s);
        assertEquals(1, keys.size());
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity(), "still retired, history kept");
        reject(s, keys);
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity(), "rejection never erases a withdrawal");
    }

    /** Negative control (fan-in): one genuine correction withdrawn while a false one still points at A; rejecting the
     *  false one must leave A unresolved, because a genuine correction of A was withdrawn. */
    @Test void f3_control_fanInWithdrawalThenRejectionStaysUnresolved() {
        var s = new InMemoryMessageStore();
        s.append(G, m("a", 0, null, "Dinner is at 7 PM."));
        s.append(G, m("b", 60, null, "Correction: dinner is at 8 PM."));
        s.append(G, m("c", 90, null, "The music starts at nine."));
        extract(s, List.of(p("a", "dinner", "Dinner", "Dinner is at 7 PM."), p("b", "dinner", "dinner", "dinner is at 8 PM."),
                p("c", "music", "The music", "The music starts at nine.")), "a", "b", "c");
        String a = by(s, "a", "dinner").id(), c = by(s, "c", "music").id();
        s.addRelation(G, new KnowledgeRelation(G.groupId(), c, a, KnowledgeRelation.Kind.SUPERSEDES, "c"));   // the false edge
        ValidityReconciler.reconcile(s, G);
        List<String> falseEdge = s.relations(G).stream().filter(r -> r.kind() == KnowledgeRelation.Kind.SUPERSEDES
                && r.fromId().equals(c) && r.toId().equals(a)).map(KnowledgeRelation::key).toList();
        assertEquals(1, falseEdge.size());
        assertEquals(2, supersedesKeys(s).size(), "fixture: b (genuine) and c (false) both retire a");
        changes(s).delete(G, "b", T.plusSeconds(120), DeletionSource.FORGET_COMMAND);
        reject(s, falseEdge);
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity());
    }

    // ================================================================== Finding 5 (Medium): unrelated parent edits

    @Test void f5_editingAnUnrelatedParentKeepsASelfContainedCorrection() {
        var s = new InMemoryMessageStore();
        s.append(G, m("a", 0, null, "Dinner is at 7 PM."));
        s.append(G, m("parent", 30, null, "Please remind us about the plans."));
        s.append(G, m("b", 60, "parent", "Correction: dinner is at 8 PM."));
        extract(s, List.of(p("a", "dinner", "Dinner", "Dinner is at 7 PM."), p("b", "dinner", "dinner", "dinner is at 8 PM.")), "a", "b");
        assertEquals(Validity.SUPERSEDED, by(s, "a", "dinner").assertion().validity());
        changes(s).edit(G, "parent", "Please remind us about parking.", T.plusSeconds(120));
        assertEquals(Validity.SUPERSEDED, by(s, "a", "dinner").assertion().validity(), "the valid supersession is kept");
        assertEquals(Validity.ACTIVE, by(s, "b", "dinner").assertion().validity());
        assertEquals(1, supersedesKeys(s).size());
        GroundedAnswer a = ask(s, false, "What time is dinner?");
        assertTrue(a.text().contains("8 PM") && !a.text().contains("7 PM\""), a.text());
    }

    /** Control: a truly parent-dependent reply ("it is at 8 PM") is still invalidated by the parent edit (fail-closed). */
    @Test void f5_control_aParentDependentCorrectionIsStillInvalidated() {
        var s = new InMemoryMessageStore();
        s.append(G, m("a", 0, null, "Dinner is at 7 PM."));
        s.append(G, m("parent", 30, null, "Dinner is at 7 PM."));
        s.append(G, m("b", 60, "parent", "Correction: it is at 8 PM."));
        extract(s, List.of(p("a", "dinner", "Dinner", "Dinner is at 7 PM."), p("b", "dinner", "Dinner", "it is at 8 PM.")), "a", "b");
        changes(s).edit(G, "parent", "Rehearsal is at 7 PM.", T.plusSeconds(120));
        assertTrue(s.knowledge(G).stream().noneMatch(k -> k.sourceMessageIds().contains("b")), "the dependent reply's item goes");
        assertEquals(Validity.UNRESOLVED, by(s, "a", "dinner").assertion().validity());
        assertNoCurrentDinnerTime(ask(s, false, "What time is dinner?"), "dependent");
    }
}
