package app.dita.core.knowledge;

import app.dita.core.events.EventPublisher;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-047 Codex F4 / T47-1: each semantic-evaluation arm starts from an unbiased baseline. Codex's EXACT reproduction
 * (/tmp/dita-task047-review/Evaluation047.java and Scoring047.java: same texts, ids, arm and scorer call), with controls.
 * Offline, synthetic, no model, no sealed data.
 */
class Task047ArmBaselineTest {
    static final GroupScope G = new GroupScope("independent047");
    static final Instant T = Instant.parse("2026-12-01T12:00:00Z");

    static Message m(String id, int sec, String text) {
        return new Message(id, G.groupId(), "ana", T.plusSeconds(sec), null, text, SourceKind.SYNTHETIC, false);
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

    /** Codex's arm that correctly rejects every supersession. */
    static final RelationArms.Arm REJECT = new RelationArms.Arm() {
        public String id() { return "independent-reject-all"; }
        public RelationArms.Proposal propose(GroupScope g, MessageStore s, RelationArms.Pair p) {
            return new RelationArms.Proposal(p, RelationArms.Tri.NO, RelationArms.Tri.NO, List.of(p.later().statement()), 1, id(), 0);
        }
    };

    static InMemoryMessageStore picnic() {
        var s = new InMemoryMessageStore();
        s.append(G, m("a", 0, "Let's put picnic signs at the entrance."));
        s.append(G, m("b", 60, "Decision: the picnic starts at noon."));
        var later = new Proposal.Item("DECISION", "ASSERTED", "picnic", "picnic", "decides", null, null,
                List.of(new Proposal.Quote("b", "Decision: the picnic starts at noon.")), List.of(), List.of());
        extract(s, List.of(p("a", "picnic", "picnic", "Let's put picnic signs at the entrance."), later), "a", "b");
        return s;
    }

    static Assertion.Validity v(MessageStore s, String mid) {
        return s.knowledge(G).stream().filter(k -> k.sourceMessageIds().contains(mid)).findFirst().orElseThrow().assertion().validity();
    }

    @Test void f4_aRejectingArmAfterAFalseProductionEdgeLeavesBothClaimsCurrent() throws Exception {
        var s = picnic();
        assertEquals(Assertion.Validity.SUPERSEDED, v(s, "a"), "fixture: Codex's false production edge");
        RelationArms.apply(G, s, REJECT, .8);
        assertEquals(Assertion.Validity.ACTIVE, v(s, "a"));                 // Codex actual: UNRESOLVED
        assertEquals(Assertion.Validity.ACTIVE, v(s, "b"));
        assertTrue(s.relations(G).isEmpty());
    }

    /** Positive control: the rules arm still retains a GENUINE replacement from the reset baseline. */
    @Test void f4_control_theRulesArmStillRetainsAGenuineReplacement() throws Exception {
        var s = new InMemoryMessageStore();
        s.append(G, m("a", 0, "Dinner is at 7 PM."));
        s.append(G, m("b", 60, "Correction: dinner is at 8 PM."));
        extract(s, List.of(p("a", "dinner", "Dinner", "Dinner is at 7 PM."), p("b", "dinner", "dinner", "dinner is at 8 PM.")), "a", "b");
        RelationArms.apply(G, s, new RelationArms.Rules(), .8);
        assertEquals(Assertion.Validity.SUPERSEDED, v(s, "a"));
        assertEquals(1, s.relations(G).size());
    }

    /** Negative control: a baseline with withdrawal history is refused, never silently erased. */
    @Test void f4_control_withdrawalHistoryIsRefusedNotErased() {
        var s = picnic();
        KnowledgeItem a = s.knowledge(G).stream().filter(k -> k.sourceMessageIds().contains("a")).findFirst().orElseThrow();
        s.removeRelations(G, s.relations(G).stream().map(KnowledgeRelation::key).toList());
        s.replaceKnowledge(G, a.withAssertion(a.assertion().unresolved()));
        assertThrows(IllegalStateException.class, () -> RelationArms.apply(G, s, REJECT, .8));
    }

    /** Codex's exact scorer reproduction: the rejecting arm must pass E1 and E4 on the complementary case. */
    @Test void f4_theActualScorerPassesTheRejectingArmOnTheComplementaryCase() throws Exception {
        JsonNode c = SemEval.JSON.readTree("""
                {"id":"independent047-complementary","messages":[{"id":"a","author":"ana","minute":0,"text":"Let's put picnic signs at the entrance."},{"id":"b","author":"ana","minute":1,"text":"Decision: the picnic starts at noon."}],"items":[{"mid":"a","status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subjectId":"picnic","subject":"picnic","predicate":"states","quote":"Let's put picnic signs at the entrance."},{"mid":"b","status":"DECISION","certainty":"ASSERTED","subjectId":"picnic","subject":"picnic","predicate":"decides","quote":"Decision: the picnic starts at noon."}],"question":"Where should we put picnic signs?"}
                """);
        JsonNode gold = SemEval.JSON.readTree("""
                {"current":["a","b"],"notCurrent":[],"status":{"a":"ATTRIBUTED_STATEMENT","b":"DECISION"},"pairs":[{"later":"b","earlier":"a","sameProperty":"no","replaces":"no"}],"answer":{"mustState":["entrance"],"mustNotStateAsCurrent":[],"mustNotLabel":[]}}
                """);
        JsonNode out = SemEval.run(c, gold, REJECT, SemEval.NO_STATUS_ARM, SemEval.fixtureItems(c), .8).json();
        assertFalse(out.path("e1Fail").asBoolean(), out.toString());       // Codex actual: e1Fail true ("E1 a retired")
        assertFalse(out.path("e4Omission").asBoolean(), out.toString());   // Codex actual: e4Omission true ("missing 'entrance'")
    }
}
