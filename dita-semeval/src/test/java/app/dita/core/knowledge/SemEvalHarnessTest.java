package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;
import app.dita.core.store.MessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evaluation harness itself is tested before any data is scored: the scorer must flag a deliberately broken arm on every
 * end-to-end metric and must not flag a correct one. Hand-written synthetic cases, not part of any dataset.
 */
class SemEvalHarnessTest {
    static final String REPLACES = """
        {"id":"H-1","class":"replaces","mechanism":"value-change","lure":null,"names":{"u-ana":"Ana"},
         "messages":[{"id":"m1","author":"u-ana","minute":0,"text":"The picnic is on Friday at the lake.","replyTo":null},
                     {"id":"m2","author":"u-ana","minute":5,"text":"Sorry, I got that wrong. The picnic is on Saturday at the lake.","replyTo":null}],
         "items":[{"mid":"m1","status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subjectId":"picnic","subject":"The picnic","predicate":"states","quote":"The picnic is on Friday at the lake.","qualifiers":[]},
                  {"mid":"m2","status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subjectId":"picnic","subject":"The picnic","predicate":"states","quote":"The picnic is on Saturday at the lake.","qualifiers":[]}],
         "question":"When is the picnic?",
         "authorGold":{"pairs":[{"later":"m2","earlier":"m1","sameProperty":"yes","replaces":"yes","evidence":"The picnic is on Saturday"}],
           "status":{"m1":"ATTRIBUTED_STATEMENT","m2":"ATTRIBUTED_STATEMENT"},"current":["m2"],"notCurrent":["m1"],
           "answer":{"mustState":["Saturday"],"mustNotStateAsCurrent":["Friday"],"mustNotLabel":[]}}}
        """;
    static final String DIFFERENT = """
        {"id":"H-2","class":"different-property","mechanism":null,"lure":"decision-prefix-on-option","names":{"u-ana":"Ana"},
         "messages":[{"id":"m1","author":"u-ana","minute":0,"text":"The picnic budget is 50 dollars.","replyTo":null},
                     {"id":"m2","author":"u-ana","minute":5,"text":"Decision: maybe we start the picnic at noon?","replyTo":null}],
         "items":[{"mid":"m1","status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subjectId":"picnic","subject":"The picnic","predicate":"states","quote":"The picnic budget is 50 dollars.","qualifiers":[]},
                  {"mid":"m2","status":"DECISION","certainty":"ASSERTED","subjectId":"picnic","subject":"the picnic","predicate":"decides","quote":"Decision: maybe we start the picnic at noon?","qualifiers":[]}],
         "question":"What is the picnic budget?",
         "authorGold":{"pairs":[{"later":"m2","earlier":"m1","sameProperty":"no","replaces":"no","evidence":"start the picnic at noon"}],
           "status":{"m1":"ATTRIBUTED_STATEMENT","m2":"QUESTION"},"current":["m1"],"notCurrent":[],
           "answer":{"mustState":["50 dollars"],"mustNotStateAsCurrent":[],"mustNotLabel":["decided on"]}}}
        """;

    /** Broken arm: always "same property, replaces", with a valid verbatim quote, so the gate admits it. */
    static final RelationArms.Arm ALWAYS = new RelationArms.Arm() {
        public String id() { return "broken-always"; }
        public RelationArms.Proposal propose(GroupScope g, MessageStore s, RelationArms.Pair p) {
            return new RelationArms.Proposal(p, RelationArms.Tri.YES, RelationArms.Tri.YES, List.of(p.later().statement()), 1, id(), 0);
        }
    };
    static final RelationArms.Arm NEVER = new RelationArms.Arm() {
        public String id() { return "broken-never"; }
        public RelationArms.Proposal propose(GroupScope g, MessageStore s, RelationArms.Pair p) {
            return new RelationArms.Proposal(p, RelationArms.Tri.NO, RelationArms.Tri.NO, List.of(), 1, id(), 0);
        }
    };

    static SemEval.Outcome run(String json, RelationArms.Arm arm) throws Exception {
        JsonNode c = SemEval.JSON.readTree(json);
        return SemEval.run(c, c.path("authorGold"), arm, SemEval.NO_STATUS_ARM, SemEval.fixtureItems(c), 0);
    }

    @Test void aCorrectReplacementIsDetectedAndAMissedOneIsScoredAsStaleAnswer() throws Exception {
        var ok = run(REPLACES, ALWAYS);
        assertTrue(ok.replacesTarget() && ok.e3Success() && !ok.e4Fail(), ok.notes().toString());
        var miss = run(REPLACES, NEVER);
        assertFalse(miss.e3Success(), "the replacement is missed");
        assertTrue(miss.notes().stream().anyMatch(n -> n.startsWith("E3 miss")), miss.notes().toString());
    }

    @Test void aFalseSupersessionIsAnE1FailureAndAnE4Omission() throws Exception {
        var bad = run(DIFFERENT, ALWAYS);
        assertTrue(bad.atRisk() && bad.e1Fail(), "the budget claim is invalidated: " + bad.notes());
        var good = run(DIFFERENT, NEVER);
        assertTrue(good.atRisk() && !good.e1Fail(), good.notes().toString());
    }

    @Test void theLureIsScoredOnTheValidatedStatusNotTheProposal() throws Exception {
        var o = run(DIFFERENT, NEVER);
        assertTrue(o.lure(), "a DECISION proposal whose gold is QUESTION is a lure");
        assertFalse(o.e2Fail(), "the validator downgrades it (question mark / hedge): " + o.notes());
        assertFalse(o.e4Fail(), "the answer does not say 'decided on': " + o.notes());
    }

    @Test void clopperPearsonAndWilsonMatchTheVerifiedValues() {
        assertTrue(SemEvalRunTest.cpUpper(6, 400) <= 0.03 && SemEvalRunTest.cpUpper(7, 400) > 0.03, "n=400: at most 6 errors pass a 3% bound");
        assertTrue(SemEvalRunTest.cpUpper(12, 400) <= 0.05 && SemEvalRunTest.cpUpper(13, 400) > 0.05, "n=400: at most 12 errors pass a 5% bound");
        assertTrue(SemEvalRunTest.wilsonLower(73, 100) >= 0.65 && SemEvalRunTest.wilsonLower(72, 100) < 0.65, "n=100: 73 successes for a 0.65 lower bound");
        assertEquals(1.0, SemEvalRunTest.cpUpper(5, 5));
    }
}
