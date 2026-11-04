package app.dita.core.knowledge;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.Assertion;
import app.dita.core.model.Assertion.Validity;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.projection.InMemorySearchIndex;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-038 (TASK-047, N-110 product-owner policy, docs/TASK-046-deleted-correction-policy.md): withdrawing a correction
 * never makes the claim it superseded current again. The older claim keeps its provenance and becomes UNRESOLVED.
 * Deterministic state tests only: no wording rule is added or exercised beyond the existing fixtures. Synthetic text.
 */
class DeletedCorrectionPolicyTest {
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    static final Duration EARLY = Duration.ofMinutes(3), LATE = Duration.ofHours(3);   // DEC-012: 5-minute window
    final GroupScope g = new GroupScope("g-047");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemorySearchIndex index = new InMemorySearchIndex();
    final ProjectionSource truth = ProjectionSource.of(store);
    final List<Object> events = new ArrayList<>();
    /** Applies every outbox event to an in-memory projection synchronously and records it. */
    final EventPublisher pub = (grp, p) -> {
        events.add(p);
        switch (p) {
            case Refs.MessageRef m -> index.onMessage(m.groupId(), m.messageId(), truth);
            case Refs.KnowledgeRef k -> index.onKnowledge(k.groupId(), k.knowledgeId(), truth);
            case DeletionEvent d -> index.onMessageDeleted(d, truth);
            default -> { }
        }
        return events.size();
    };
    final Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    final Map<String, String> names = Map.of("u-ana", "Ana", "u-ben", "Ben");

    void msg(String id, String author, int min, String text) {
        store.append(g, new Message(id, g.groupId(), author, T0.plusSeconds(60L * min), null, text, SourceKind.SYNTHETIC, false));
        index.onMessage(g.groupId(), id, truth);
    }

    static Proposal.Item it(String status, String subjectId, String subject, String predicate, String mid, String quote) {
        return new Proposal.Item(status, "ASSERTED", subjectId, subject, predicate, null, null, List.of(new Proposal.Quote(mid, quote)), List.of(), List.of());
    }

    static Proposal.Item stated(String subjectId, String subject, String mid, String quote) {
        return it("ATTRIBUTED_STATEMENT", subjectId, subject, "states", mid, quote);
    }

    void run(List<Proposal.Item> items, String... ids) {
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, c) -> new Proposal(items, "t047"), pub, now, 50, Duration.ofMinutes(30));
        for (String id : ids) x.enqueue(g, store.find(g, id).orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        assertEquals(app.dita.core.knowledge.ExtractionBatch.Status.DONE, x.process(g, b.id()).orElseThrow().status());
    }

    DeletionService deletions() { return new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, pub, 50); }

    DeletionEvent delete(String mid, Duration afterSend) {
        Instant sent = store.find(g, mid).orElseThrow().sentAt();
        return deletions().delete(g, mid, sent.plus(afterSend), DeletionSource.PLATFORM_EVENT).orElseThrow();
    }

    KnowledgeItem byMid(String mid) {
        return store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals(mid)).findFirst().orElse(null);
    }

    Validity validity(String mid) { return byMid(mid).assertion().validity(); }

    QueryService query() {
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
        return new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, names::get,
                new LexicalKnowledgeRetriever(store, names::get));
    }

    static List<String> cited(GroundedAnswer a) { return a.citations().stream().map(Citation::messageId).toList(); }

    /** DEC-039 invariant: a live SUPERSEDES relation ⇒ never ACTIVE; SUPERSEDED ⇒ a live relation (UNRESOLVED is sticky
     *  withdrawal history and may coexist with a later relation). */
    void assertInvariant() {
        var targets = store.relations(g).stream().filter(r -> r.kind() == KnowledgeRelation.Kind.SUPERSEDES).map(KnowledgeRelation::toId).toList();
        for (KnowledgeItem k : store.knowledge(g)) {
            if (targets.contains(k.id())) assertNotEquals(Validity.ACTIVE, k.assertion().validity(), k.id() + " retired but ACTIVE");
            if (k.assertion().validity() == Validity.SUPERSEDED) assertTrue(targets.contains(k.id()), k.id() + " SUPERSEDED without a relation");
        }
    }

    void dinner() {
        msg("d1", "u-ana", 0, "Dinner is at 7 PM at the community hall.");
        msg("d2", "u-ana", 2, "Sorry, I got that wrong earlier. Dinner is at 8 PM at the community hall.");
        run(List.of(stated("dinner", "Dinner", "d1", "Dinner is at 7 PM at the community hall."),
                stated("dinner", "Dinner", "d2", "Dinner is at 8 PM at the community hall.")), "d1", "d2");
        assertEquals(Validity.SUPERSEDED, validity("d1"), "fixture: the correction retires the earlier time");
    }

    /** No answer path (knowledge or message) presents the withdrawn correction or the unresolved claim as current. */
    void assertNoCurrentDinnerTime() {
        for (String q : List.of("What time is dinner?", "When is dinner at the community hall?")) {
            GroundedAnswer a = query().ask(g, "u-asker", q);
            assertFalse(a.text().contains("7 PM") || a.text().contains("8 PM"), q + " → " + a.text());
            assertFalse(cited(a).contains("d1") || cited(a).contains("d2"), q + " → " + cited(a));
        }
    }

    // ------------------------------------------------------------------ early / late deletion of the correction

    @Test void earlyDeletionOfTheCorrectionLeavesTheOlderClaimUnresolvedWithProvenance() {
        dinner();
        KnowledgeItem before = byMid("d1");
        events.clear();
        DeletionEvent e = delete("d2", EARLY);
        assertEquals(app.dita.core.deletion.DeletionMode.EARLY_PURGE, e.mode());
        assertTrue(byMid("d2") == null, "early purge removes the correction's knowledge");
        KnowledgeItem after = byMid("d1");
        assertEquals(Validity.UNRESOLVED, after.assertion().validity(), "not restored as current");
        assertEquals(before.evidence(), after.evidence(), "historical provenance of the older claim is kept");
        assertEquals(before.statement(), after.statement());
        assertFalse(after.sourceMessageIds().contains("d2") || after.deletedSourceIds().contains("d2")
                || after.assertion().qualifiers().stream().anyMatch(q -> q.messageId().equals("d2")), "nothing points at the deleted correction");
        assertTrue(store.relations(g).stream().noneMatch(r -> "d2".equals(r.evidenceMessageId())), "no relation keeps the deleted message");
        assertTrue(e.updatedKnowledgeIds().contains(after.id()), "the change is published to the projections");
        assertInvariant();
        assertNoCurrentDinnerTime();
    }

    @Test void lateDeletionOfTheCorrectionLeavesTheOlderClaimUnresolvedAndPreservesTheCorrectionAsHistory() {
        dinner();
        DeletionEvent e = delete("d2", LATE);
        assertEquals(app.dita.core.deletion.DeletionMode.LATE_TEXT_ONLY, e.mode());
        KnowledgeItem d2 = byMid("d2");
        assertTrue(d2 != null && d2.onlyDeletedSourcesRemain(), "late preserve keeps the correction as deleted-source history");
        assertEquals(Validity.UNRESOLVED, validity("d1"));
        assertTrue(e.updatedKnowledgeIds().contains(byMid("d1").id()));
        assertInvariant();
        assertNoCurrentDinnerTime();
    }

    @Test void editingTheCorrectionLeavesTheOlderClaimUnresolved() {
        dinner();
        var out = deletions().edit(g, "d2", "Dinner plans are still open, I will confirm tomorrow.", T0.plusSeconds(600));
        assertEquals(Validity.UNRESOLVED, validity("d1"));
        assertTrue(out.updatedKnowledgeIds().contains(byMid("d1").id()));
        assertInvariant();
    }

    @Test void deletingTheOlderClaimItselfStillWorksAndTheCorrectionStaysCurrent() {
        dinner();
        delete("d1", EARLY);
        assertTrue(byMid("d1") == null);
        assertEquals(Validity.ACTIVE, validity("d2"));
        assertInvariant();
        assertTrue(query().ask(g, "u-asker", "What time is dinner?").text().contains("8 PM"));
    }

    @Test void unrelatedActiveClaimsAreNeverMarkedUnresolved() {
        dinner();
        msg("x1", "u-ben", 5, "The bus leaves from the school gate at 8 AM.");
        run(List.of(stated("bus", "The bus", "x1", "The bus leaves from the school gate at 8 AM.")), "x1");
        delete("d2", EARLY);
        assertEquals(Validity.ACTIVE, validity("x1"), "only items that were retired can become unresolved");
    }

    // ------------------------------------------------------------------ multiple corrections (structural shapes)

    /** Three claims on distinct subjects (so no rule relates them); relations are then added explicitly. */
    List<String> threeClaims() {
        msg("a1", "u-ana", 0, "The rehearsal is in room one.");
        msg("a2", "u-ana", 1, "The concert is in the big hall.");
        msg("a3", "u-ana", 2, "The party is in the garden.");
        run(List.of(stated("rehearsal", "The rehearsal", "a1", "The rehearsal is in room one."),
                stated("concert", "The concert", "a2", "The concert is in the big hall."),
                stated("party", "The party", "a3", "The party is in the garden.")), "a1", "a2", "a3");
        assertTrue(store.relations(g).isEmpty(), "fixture: no rule relation between distinct subjects");
        return List.of(byMid("a1").id(), byMid("a2").id(), byMid("a3").id());
    }

    void supersedes(String from, String to, String evidenceMid) {
        store.addRelation(g, new KnowledgeRelation(g.groupId(), from, to, KnowledgeRelation.Kind.SUPERSEDES, evidenceMid));
        ValidityReconciler.reconcile(store, g);
    }

    // DEC-039: withdrawing ONE of two corrections is recorded at once (UNRESOLVED), so a later rejection of the other can
    // never reactivate it; it stays non-current throughout
    @Test void twoCorrectionsOfTheSameClaim_withdrawingEitherRecordsTheWithdrawal_neverCurrent() {
        List<String> ids = threeClaims();
        supersedes(ids.get(1), ids.get(0), "a2");
        supersedes(ids.get(2), ids.get(0), "a3");
        assertEquals(Validity.SUPERSEDED, validity("a1"));
        delete("a2", EARLY);
        assertEquals(Validity.UNRESOLVED, validity("a1"), "still retired (a3 remains) and the withdrawal is recorded");
        assertEquals(Validity.ACTIVE, validity("a3"));
        delete("a3", LATE);
        assertEquals(Validity.UNRESOLVED, validity("a1"), "no supersession evidence left → unresolved, not current");
        assertInvariant();
    }

    @Test void chainOfCorrections_deletingTheLastLeavesTheMiddleUnresolvedAndTheFirstRetired() {
        List<String> ids = threeClaims();
        supersedes(ids.get(1), ids.get(0), "a2");                        // a2 corrects a1
        supersedes(ids.get(2), ids.get(1), "a3");                        // a3 corrects a2
        delete("a3", EARLY);
        assertEquals(Validity.UNRESOLVED, validity("a2"), "the middle claim is not current again");
        assertEquals(Validity.SUPERSEDED, validity("a1"), "a2's message is live, so it still retires a1");
        assertInvariant();
        // message path: neither retired claim without a current replacement is offered as an answer
        Set<String> excluded = query().messagesAssertingOnlyRetiredWithoutCurrent(g);
        assertEquals(Set.of("a1", "a2"), excluded);
        delete("a2", LATE);
        assertEquals(Validity.UNRESOLVED, validity("a1"));
        assertInvariant();
    }

    @Test void liveChainOfCorrections_onlyTheCurrentClaimReachesTheMessagePath() {      // T47-7
        List<String> ids = threeClaims();
        supersedes(ids.get(1), ids.get(0), "a2");
        supersedes(ids.get(2), ids.get(1), "a3");
        assertEquals(Set.of("a1"), query().messagesAssertingOnlyRetiredWithoutCurrent(g),
                "a1's direct superseder is itself retired; a2 still has an ACTIVE superseder");
        supersedes(ids.get(1), ids.get(0), "a2");
        assertEquals(Validity.ACTIVE, validity("a3"));
    }

    @Test void chainOfCorrections_deletingTheMiddleLeavesTheFirstUnresolvedAndTheLastCurrent() {
        List<String> ids = threeClaims();
        supersedes(ids.get(1), ids.get(0), "a2");
        supersedes(ids.get(2), ids.get(1), "a3");
        delete("a2", EARLY);
        assertEquals(Validity.UNRESOLVED, validity("a1"));
        assertEquals(Validity.ACTIVE, validity("a3"));
        assertInvariant();
    }

    // DEC-039: UNRESOLVED is sticky withdrawal history: a new valid supersession keeps it retired (not SUPERSEDED)
    @Test void repeatedWithdrawalsNeverReactivate_andAnUnresolvedItemStaysUnresolvedWhenSupersededAgain() {
        List<String> ids = threeClaims();
        supersedes(ids.get(1), ids.get(0), "a2");
        delete("a2", EARLY);
        assertEquals(Validity.UNRESOLVED, validity("a1"));
        supersedes(ids.get(2), ids.get(0), "a3");                        // a new valid supersession
        assertEquals(Validity.UNRESOLVED, validity("a1"), "still not current; history kept");
        delete("a3", EARLY);
        assertEquals(Validity.UNRESOLVED, validity("a1"), "withdrawn again → unresolved again, never ACTIVE");
        assertTrue(ValidityReconciler.reconcile(store, g).isEmpty(), "reconciliation is idempotent");
    }

    @Test void theStateMachine() {
        for (Validity v : List.of(Validity.ACTIVE, Validity.SUPERSEDED)) {
            assertEquals(Validity.SUPERSEDED, ValidityReconciler.target(v, true, true));
            assertEquals(Validity.SUPERSEDED, ValidityReconciler.target(v, true, false));
        }
        assertEquals(Validity.UNRESOLVED, ValidityReconciler.target(Validity.UNRESOLVED, true, true), "sticky history");
        // DEC-039 reasons
        assertEquals(Validity.ACTIVE, ValidityReconciler.target(Validity.SUPERSEDED, false, true, false, true), "rejected only → restored");
        assertEquals(Validity.UNRESOLVED, ValidityReconciler.target(Validity.SUPERSEDED, false, true, true, true), "withdrawn wins");
        assertEquals(Validity.UNRESOLVED, ValidityReconciler.target(Validity.SUPERSEDED, true, true, true, false), "fan-in withdrawal recorded");
        assertEquals(Validity.SUPERSEDED, ValidityReconciler.target(Validity.SUPERSEDED, false, false, false, true), "V5: fail-closed");
        assertEquals(Validity.UNRESOLVED, ValidityReconciler.target(Validity.UNRESOLVED, false, true, false, true), "rejection never erases history");
        assertEquals(Validity.ACTIVE, ValidityReconciler.target(Validity.ACTIVE, false, true, true, false), "an ACTIVE item is never retired by a reason alone");
        assertEquals(Validity.ACTIVE, ValidityReconciler.target(Validity.ACTIVE, false, true));
        assertEquals(Validity.UNRESOLVED, ValidityReconciler.target(Validity.SUPERSEDED, false, true));
        assertEquals(Validity.UNRESOLVED, ValidityReconciler.target(Validity.UNRESOLVED, false, true));
        assertEquals(Validity.SUPERSEDED, ValidityReconciler.target(Validity.SUPERSEDED, false, false), "PG < V6: kept retired");
        for (Validity v : Validity.values()) if (v != Validity.ACTIVE)
            for (boolean storable : new boolean[]{true, false})
                assertNotEquals(Validity.ACTIVE, ValidityReconciler.target(v, false, storable), "a retired item is never reactivated");
    }

    // ------------------------------------------------------------------ subsequent explicit reconfirmation

    @Test void anExplicitReconfirmationAfterTheDeletionIsCurrentAndTheOldItemStaysUnresolved() {
        dinner();
        delete("d2", EARLY);
        msg("d4", "u-ana", 30, "Confirming again: dinner is at 7 PM at the community hall.");
        run(List.of(stated("dinner", "dinner", "d4", "dinner is at 7 PM at the community hall.")), "d4");
        assertEquals(Validity.ACTIVE, validity("d4"), "the reconfirmation is a new current claim");
        assertNotEquals(Validity.ACTIVE, validity("d1"), "the old item is not silently reactivated by it");
        GroundedAnswer a = query().ask(g, "u-asker", "What time is dinner?");
        assertTrue(a.text().contains("7 PM") && cited(a).contains("d4"), "answered from the reconfirmation: " + a.text());
        assertFalse(cited(a).contains("d2"), a.text());
        assertInvariant();
    }

    @Test void aNewValueAfterTheDeletionIsCurrent() {
        dinner();
        delete("d2", LATE);
        msg("d5", "u-ana", 200, "Update: dinner is at 9 PM at the community hall.");
        run(List.of(stated("dinner", "dinner", "d5", "dinner is at 9 PM at the community hall.")), "d5");
        assertEquals(Validity.ACTIVE, validity("d5"));
        assertNotEquals(Validity.ACTIVE, validity("d1"));
        GroundedAnswer a = query().ask(g, "u-asker", "What time is dinner?");
        assertTrue(a.text().contains("9 PM") && cited(a).contains("d5"), a.text());
        assertFalse(a.text().contains("7 PM") || a.text().contains("8 PM"), a.text());
        assertInvariant();
    }

    // ------------------------------------------------------------------ projections (in-memory index) and residue

    @Test void projectionsReceiveTheChangeAndHoldNoDeletedWording() {
        dinner();
        delete("d2", EARLY);
        String d1 = byMid("d1").id();
        assertTrue(events.contains(new Refs.KnowledgeRef(g.groupId(), d1)) || events.stream().anyMatch(ev ->
                ev instanceof DeletionEvent de && de.updatedKnowledgeIds().contains(d1)), "the validity change is published");
        assertFalse(index.messageDocs.containsKey("d2"));
        index.knowledgeDocs.values().forEach(doc -> assertFalse(
                String.valueOf(doc.statement()).contains("8 PM") || String.valueOf(doc.subject()).contains("8 PM"), doc.toString()));
        index.messageDocs.values().forEach(doc -> assertFalse(String.valueOf(doc.text()).contains("8 PM"), doc.toString()));
    }

    // ------------------------------------------------------------------ a store that cannot hold UNRESOLVED (PG < V6)

    @Test void aStoreWithoutUnresolvedSupportKeepsTheClaimRetiredAndTheMessagePathStillExcludesIt() {
        dinner();
        MessageStore v5 = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(), new Class<?>[]{MessageStore.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("supportsUnresolvedValidity")) return false;
                    if (m.getName().equals("replaceKnowledge") && ((KnowledgeItem) args[1]).assertion() != null
                            && ((KnowledgeItem) args[1]).assertion().validity() == Validity.UNRESOLVED)
                        throw new UnsupportedOperationException("V6 not applied");
                    try { return m.invoke(store, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var e = new DeletionService(v5, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, pub, 50)
                .delete(g, "d2", T0.plusSeconds(120).plus(EARLY), DeletionSource.PLATFORM_EVENT);
        assertTrue(e.isPresent(), "the deletion itself never fails on an un-migrated store");
        assertEquals(Validity.SUPERSEDED, validity("d1"), "kept retired: never current");
        assertTrue(ValidityReconciler.reconcile(v5, g).isEmpty(), "stable: no flapping between reconciliations");
        assertNoCurrentDinnerTime();
    }
}
