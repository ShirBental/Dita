package app.dita.core.knowledge;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-046 regression tests (Fury), complementing Shuri's independent {@link Task046ReproTest}: the production edit path
 * ({@link DeletionService#edit}), an edit racing an extraction, late (preserve-mode) deletion of a superseding claim, the
 * status/predicate table, the calendar-value supersession rule, and that a rejected named-member answer really falls
 * back (to the message path) instead of just disappearing. Synthetic text only.
 */
class Task046RegressionTest {
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    final GroupScope g = new GroupScope("g-046");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final List<Object> events = new ArrayList<>();
    final EventPublisher pub = (grp, p) -> { events.add(p); return events.size(); };
    final Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    final Map<String, String> names = Map.of("u-ana", "Ana", "u-ben", "Ben");

    void msg(String id, String author, int min, String text) { msg(id, author, min, null, text); }
    void msg(String id, String author, int min, String reply, String text) {
        store.append(g, new Message(id, g.groupId(), author, T0.plusSeconds(60L * min), reply, text, SourceKind.SYNTHETIC, false));
    }

    static Proposal.Item it(String status, String subjectId, String subject, String predicate, String mid, String quote) {
        return new Proposal.Item(status, "ASSERTED", subjectId, subject, predicate, null, null, List.of(new Proposal.Quote(mid, quote)), List.of(), List.of());
    }

    ExtractionService.Report run(List<Proposal.Item> items, String... ids) {
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, c) -> new Proposal(items, "t046"), pub, now, 50, Duration.ofMinutes(30));
        for (String id : ids) x.enqueue(g, store.find(g, id).orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        return x.process(g, b.id()).orElseThrow();
    }

    DeletionService deletions(DeletionSettings s) { return new DeletionService(store, new RuleLinkInferencer(), s, pub, 50); }

    KnowledgeItem byMid(String mid) {
        return store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals(mid)).findFirst().orElse(null);
    }

    void dinner() {
        msg("d1", "u-ana", 0, "Dinner is at 7 PM at the community hall.");
        msg("d2", "u-ana", 2, "Sorry, I got that wrong earlier. Dinner is at 8 PM at the community hall.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "d1", "Dinner is at 7 PM at the community hall."),
                it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "d2", "Dinner is at 8 PM at the community hall.")), "d1", "d2");
        assertEquals(Assertion.Validity.SUPERSEDED, byMid("d1").assertion().validity());
    }

    /** Validity must follow the remaining SUPERSEDES relations exactly. */
    void assertValidityConsistent() {
        var targets = store.relations(g).stream().filter(r -> r.kind() == KnowledgeRelation.Kind.SUPERSEDES).map(KnowledgeRelation::toId).toList();
        for (KnowledgeItem k : store.knowledge(g))
            assertEquals(targets.contains(k.id()), k.assertion().validity() == Assertion.Validity.SUPERSEDED, k.id() + " validity vs relations");
    }

    // ---- P2: message edits ----

    @Test void editingAKnowledgeSourceRemovesItsKnowledgeAndPublishesTheChange() {
        dinner();
        String oldId = byMid("d2").id();
        events.clear();
        var out = deletions(DeletionSettings.DEFAULTS).edit(g, "d2", "Dinner plans are still open, I will confirm tomorrow.", T0.plusSeconds(600));
        assertEquals(List.of(oldId), out.removedKnowledgeIds());
        assertTrue(byMid("d2") == null, "knowledge from the old revision is gone");
        assertEquals(Assertion.Validity.UNRESOLVED, byMid("d1").assertion().validity(), "nothing retires d1 any more, but it is not current again (DEC-038)");
        assertValidityConsistent();
        assertTrue(events.contains(new Refs.KnowledgeRef(g.groupId(), oldId)), "projections are told to drop the item");
        assertTrue(events.contains(new Refs.KnowledgeRef(g.groupId(), byMid("d1").id())), "and to refresh the now-unresolved one");
        assertTrue(events.contains(new Refs.MessageRef(g.groupId(), "d2")), "and to re-index the edited message");
        assertEquals("Dinner plans are still open, I will confirm tomorrow.", store.find(g, "d2").orElseThrow().text());
    }

    @Test void editingASupersededSourceRemovesItButKeepsTheCurrentClaim() {
        dinner();
        deletions(DeletionSettings.DEFAULTS).edit(g, "d1", "Dinner is at the hall, time to be announced.", T0.plusSeconds(600));
        assertTrue(byMid("d1") == null);
        assertEquals(Assertion.Validity.ACTIVE, byMid("d2").assertion().validity());
        assertTrue(store.relations(g).isEmpty(), "the relation to the removed item cascades");
        var a = new KnowledgeAnswerComposer(store, 200, names::get).answer(g, "What time is dinner?").orElseThrow();
        assertTrue(a.text().contains("8 PM") && !a.text().contains("[d1]"), a.text());
    }

    @Test void editingAReplyParentRemovesItemsWhoseSubjectCameFromIt() {
        msg("p1", "u-ben", 0, "I can bring the folding chairs for the picnic.");
        msg("q1", "u-ana", 1, "Ben, are the folding chairs still coming?");
        msg("p2", "u-ben", 3, "q1", "Confirmed: I'll bring them.");
        run(List.of(it("OFFER", "chairs", "the folding chairs", "offers", "p1", "I can bring the folding chairs for the picnic."),
                it("CONFIRMED_COMMITMENT", "chairs", "the folding chairs", "commits", "p2", "Confirmed: I'll bring them.")), "p1", "q1", "p2");
        assertEquals(1, store.relations(g).size(), "fixture: the commitment supersedes the offer");
        String p2 = byMid("p2").id();
        var out = deletions(DeletionSettings.DEFAULTS).edit(g, "q1", "Ben, what about the snacks?", T0.plusSeconds(600));
        // Codex C3: "them" got its subject only from the edited parent, so the reply's item is removed (relation cascades)
        assertEquals(List.of(p2), out.removedKnowledgeIds());
        assertTrue(store.relations(g).isEmpty());
        assertEquals(Assertion.Validity.UNRESOLVED, byMid("p1").assertion().validity(), "uncertain → no relation; the offer is not current again (DEC-038)");
        assertValidityConsistent();
    }

    @Test void anEditThatLandsWhileTheProposerRunsIsNeverStored() {
        msg("r1", "u-ana", 0, "The bus leaves from the school gate at 8 AM.");
        var item = it("ATTRIBUTED_STATEMENT", "bus", "The bus", "states", "r1", "The bus leaves from the school gate at 8 AM.");
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, c) -> {
            store.edit(g, "r1", "The bus leaves from the library at 9 AM.", T0.plusSeconds(30));      // edit during the model call
            return new Proposal(List.of(item), "t046");
        }, pub, now, 50, Duration.ofMinutes(30));
        x.enqueue(g, store.find(g, "r1").orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        var rep = x.process(g, b.id()).orElseThrow();
        assertEquals(0, rep.accepted());
        assertTrue(store.knowledge(g).isEmpty(), "validated against the old revision: dropped");
    }

    @Test void aDirectStoreEditCanNeverSurfaceOldRevisionKnowledge() {
        dinner();
        store.edit(g, "d2", "Dinner is cancelled tonight, sorry everyone, see you next week.", T0.plusSeconds(600));
        var a = new KnowledgeAnswerComposer(store, 200, names::get).answer(g, "What time is dinner?");
        assertTrue(a.isEmpty() || a.get().citations().stream().noneMatch(c -> c.messageId().equals("d2")), a.map(x -> x.text()).orElse(""));
    }

    // ---- P4: deletion keeps validity consistent ----

    // DEC-038 (N-110) inverted this TASK-046 test: the earlier claim is NOT reactivated, it becomes UNRESOLVED
    @Test void earlyDeletionOfTheSupersedingClaimLeavesTheEarlierOneUnresolved() {
        dinner();
        var e = deletions(DeletionSettings.DEFAULTS).delete(g, "d2", T0.plusSeconds(150), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertEquals(Assertion.Validity.UNRESOLVED, byMid("d1").assertion().validity());
        assertTrue(e.updatedKnowledgeIds().contains(byMid("d1").id()), "projections refresh the now-unresolved item");
        assertValidityConsistent();
    }

    @Test void lateDeletionInPreserveModeNeverLeavesAnInvisibleSupersession() {
        dinner();
        // preserve mode: d2's item keeps deleted-source provenance but can no longer be shown, so it must not retire d1
        deletions(DeletionSettings.DEFAULTS).delete(g, "d2", T0.plusSeconds(3 * 3600), DeletionSource.PLATFORM_EVENT).orElseThrow();
        KnowledgeItem d2 = store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals("d2")).findFirst().orElseThrow();
        assertTrue(d2.onlyDeletedSourcesRemain());
        assertEquals(Assertion.Validity.UNRESOLVED, byMid("d1").assertion().validity(), "DEC-038: not reactivated");
        assertValidityConsistent();
        var a = new KnowledgeAnswerComposer(store, 200, names::get).answer(g, "What time is dinner?");
        assertTrue(a.isEmpty() || !(a.get().text().contains("8 PM") || a.get().text().contains("7 PM")),
                "neither the deleted correction nor the unresolved claim is answered as current: " + a.map(x -> x.text()).orElse(""));
    }

    // ---- P3: status / predicate table ----

    @Test void statusAndPredicateAlwaysAgreeAfterValidation() {
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, EvidenceValidator.consistentStatus(Assertion.Status.DECISION, "recommends"));
        assertEquals(Assertion.Status.OFFER, EvidenceValidator.consistentStatus(Assertion.Status.CONFIRMED_COMMITMENT, "offers"));
        assertEquals(Assertion.Status.OFFER, EvidenceValidator.consistentStatus(Assertion.Status.OFFER, "commits"));
        assertEquals("offers", EvidenceValidator.consistentPredicate(Assertion.Status.OFFER, "commits"));
        assertEquals("states", EvidenceValidator.consistentPredicate(Assertion.Status.ATTRIBUTED_STATEMENT, "decides"));
        assertEquals("recommends", EvidenceValidator.consistentPredicate(Assertion.Status.ATTRIBUTED_STATEMENT, "recommends"));
        assertEquals("asks", EvidenceValidator.consistentPredicate(Assertion.Status.QUESTION, "states"));
    }

    @Test void aDecisionLabelledRecommendsIsStoredAsAConsistentRecommendation() {
        msg("v1", "u-ana", 0, "Decision: I recommend the blue tent, it is the best one.");
        run(List.of(it("DECISION", "tent", "the blue tent", "recommends", "v1", "Decision: I recommend the blue tent, it is the best one.")), "v1");
        Assertion a = byMid("v1").assertion();
        assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, a.status());
        assertEquals("recommends", a.predicate());
        assertEquals(1, byMid("v1").polarity());
    }

    @Test void aHedgedCommitmentIsStoredAsAnOffer() {
        msg("w1", "u-ben", 0, "I'll maybe bring the speaker, done with the playlist.");
        run(List.of(it("CONFIRMED_COMMITMENT", "speaker", "the speaker", "commits", "w1", "I'll maybe bring the speaker, done with the playlist.")), "w1");
        assertEquals(Assertion.Status.OFFER, byMid("w1").assertion().status());
        assertEquals("offers", byMid("w1").assertion().predicate());
    }

    // ---- P4: calendar-value rule ----

    @Test void aDecisionReplacesTheSameSpeakersEarlierDateButNotAPlainFact() {
        msg("c1", "u-ana", 0, "We need to organize the school picnic for November 14.");
        msg("c2", "u-ana", 1, "The school picnic area has a playground for 30 kids.");
        msg("c3", "u-ana", 5, "Decision: the school picnic is on Sunday, November 15.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "picnic", "the school picnic", "states", "c1", "We need to organize the school picnic for November 14."),
                it("ATTRIBUTED_STATEMENT", "picnic", "The school picnic", "states", "c2", "The school picnic area has a playground for 30 kids."),
                it("DECISION", "picnic", "the school picnic", "decides", "c3", "Decision: the school picnic is on Sunday, November 15.")), "c1", "c2", "c3");
        assertEquals(Assertion.Validity.SUPERSEDED, byMid("c1").assertion().validity(), "same temporal property replaced");
        assertEquals(Assertion.Validity.ACTIVE, byMid("c2").assertion().validity(), "complementary fact preserved");
    }

    @Test void aDecisionRepeatingTheSameDateDoesNotRetireTheQualifiedPlan() {
        msg("e1", "u-ana", 0, "The trip for the older kids is on Sunday.");
        msg("e2", "u-ana", 5, "Decision: we rent a minibus for the trip on Sunday.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "trip", "The trip", "states", "e1", "The trip for the older kids is on Sunday."),
                it("DECISION", "trip", "the trip", "decides", "e2", "Decision: we rent a minibus for the trip on Sunday.")), "e1", "e2");
        assertEquals(Assertion.Validity.ACTIVE, byMid("e1").assertion().validity(), "same value: no evidence of replacement");
    }

    // ---- P5: named member, fallback really answers ----

    @Test void anUnrelatedNamedMemberClaimDoesNotBlockTheMessagePathAnswer() {
        msg("n1", "u-ana", 0, "I'll bring the lemonade for the picnic.");
        msg("n2", "u-ben", 1, "Ana's flight lands at 6 PM on Friday, I'll pick her up.");
        run(List.of(it("CONFIRMED_COMMITMENT", "lemonade", "the lemonade", "commits", "n1", "I'll bring the lemonade for the picnic.")), "n1", "n2");
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
        QueryService q = new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, names::get,
                new LexicalKnowledgeRetriever(store, names::get));
        var a = q.ask(g, "u-asker", "When does Ana's flight land?");
        List<String> cited = a.citations().stream().map(Citation::messageId).toList();
        assertTrue(cited.contains("n2") && !cited.contains("n1"), "message path answers; the unrelated claim is not used: " + a.text());
    }

    @Test void aSpecificQuestionWithNoEvidencePrefersUnknown() {
        msg("u1", "u-ana", 0, "I'll bring the lemonade for the picnic.");
        msg("u2", "u-ana", 1, "My cat is called Pixel and she sleeps all day.");
        run(List.of(it("CONFIRMED_COMMITMENT", "lemonade", "the lemonade", "commits", "u1", "I'll bring the lemonade for the picnic."),
                it("ATTRIBUTED_STATEMENT", "cat", "My cat", "states", "u2", "My cat is called Pixel and she sleeps all day.")), "u1", "u2");
        var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
        QueryService q = new QueryService(store, Retrievers.build(store, RetrievalSettings.DEFAULTS), access, 8, 200, names::get,
                new LexicalKnowledgeRetriever(store, names::get));
        var a = q.ask(g, "u-asker", "When does Ana's flight land?");
        assertTrue(a.citations().isEmpty(), "no supported answer → no citations (UNKNOWN): " + a.text());
        assertTrue(new KnowledgeAnswerComposer(store, 200, names::get).answer(g, "What did Ana say?").isPresent(), "broad history still answers");
        assertTrue(new KnowledgeAnswerComposer(store, 200, names::get).answer(g, "What is Ana responsible for?").orElseThrow().text().contains("lemonade"));
    }
}
