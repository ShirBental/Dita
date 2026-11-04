package app.dita.core.answer.synthesis;

import app.dita.core.answer.synthesis.AnswerSynthesizer.Draft;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Evidence;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-054 regression: neutral attribution ("P1 stated ...") is accepted ONLY in attribution position; every other R2-R9
 * check is unchanged. The first case is the exact Muse answer captured in the live v2 q2 trial
 * (build/controlled-synthesis-cedar-v2/postgres/q2/synthesis-recording.json), which v1 of this validator rejected with
 * "R8 unsupported terms: [stated, stat]".
 */
class SynthesisValidatorAttributionTest {
    final SynthesisValidator v = new SynthesisValidator();

    static final Request CEDAR = new Request("Is Dr. Cedar recommended for teenagers?", "draft", List.of(
            new Evidence("m1", "P1", "2026-11-03", "ATTRIBUTED_STATEMENT", 1, true, "Dr. Cedar on Maple Street is excellent for babies."),
            new Evidence("m2", "P1", "2026-11-03", "ATTRIBUTED_STATEMENT", -1, true, "Dr. Cedar is not recommended for teenagers.")),
            List.of(), false);
    static final Request WILLOW = new Request("Is Coach Willow good for beginners?", "draft", List.of(
            new Evidence("w1", "P2", "2026-11-03", "ATTRIBUTED_STATEMENT", 1, true, "Coach Willow is great for beginners."),
            new Evidence("w2", "P2", "2026-11-03", "ATTRIBUTED_STATEMENT", -1, true, "Coach Willow is not suitable for competitive swimmers.")),
            List.of(), false);
    static final Request OFFER = new Request("Who brings snacks?", "draft", List.of(
            new Evidence("o1", "P1", "2026-11-03", "OFFER", 0, false, "I can bring snacks if nobody else does.")), List.of(), false);

    static Draft d(String answer, String... ids) { return new Draft(false, answer, List.of(ids)); }

    void ok(Request r, Draft d) { var x = v.validate(r, d); assertTrue(x.ok(), x.violations().toString()); }
    List<String> bad(Request r, Draft d) { var x = v.validate(r, d); assertFalse(x.ok(), "expected rejection: " + d.answer()); return x.violations(); }
    static boolean has(List<String> violations, String rule) { return violations.stream().anyMatch(s -> s.startsWith(rule)); }

    @Test void capturedLiveV2Q2AnswerIsAccepted() {
        ok(CEDAR, d("P1 stated Dr. Cedar is not recommended for teenagers [m2].", "m2"));
    }

    @Test void liveV2Q1AnswerStillAccepted() {
        ok(new Request("Is Dr. Cedar recommended for babies?", "draft", CEDAR.evidence(), List.of(), false),
                d("P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1"));
    }

    @Test void otherSubjectAndPresentTenseAndAlso() {
        ok(WILLOW, d("P2 stated Coach Willow is great for beginners [w1].", "w1"));
        ok(WILLOW, d("P2 states Coach Willow is great for beginners [w1]. P2 also stated Coach Willow is not suitable for competitive swimmers [w2].", "w1", "w2"));
    }

    @Test void factualAdditionsAfterAttributionAreStillRejected() {
        var x = bad(CEDAR, d("P1 stated Dr. Cedar is not recommended for teenagers due to long waits [m2].", "m2"));
        assertTrue(has(x, "R8"), x.toString());
        assertTrue(x.toString().contains("wait"), x.toString());
        x = bad(WILLOW, d("P2 stated Coach Willow is great for beginners on Tuesdays [w1].", "w1"));
        assertTrue(has(x, "R8"), x.toString());
    }

    @Test void attributionVerbOutsideAttributionPositionIsStillUnsupported() {
        var x = bad(CEDAR, d("It was stated by P1 that Dr. Cedar is not recommended for teenagers [m2].", "m2"));
        assertTrue(has(x, "R8") && x.toString().contains("stat"), x.toString());
        x = bad(CEDAR, d("P1 said the state recommends against Dr. Cedar for teenagers [m2].", "m2"));
        assertTrue(has(x, "R8") && x.toString().contains("state"), x.toString());
    }

    @Test void inventedSpeakerIsRejected() {
        var x = bad(CEDAR, d("P3 stated Dr. Cedar is not recommended for teenagers [m2].", "m2"));
        assertTrue(has(x, "R4"), x.toString());
        x = bad(CEDAR, d("Dr. Smith stated Dr. Cedar is not recommended for teenagers [m2].", "m2"));
        assertTrue(has(x, "R4") || has(x, "R8"), x.toString());
    }

    @Test void invalidCitationsAreRejected() {
        assertTrue(has(bad(CEDAR, d("P1 stated Dr. Cedar is not recommended for teenagers [m9].", "m9")), "R2"));
        assertTrue(has(bad(CEDAR, d("P1 stated Dr. Cedar is not recommended for teenagers [m2].", "m1")), "R2"));
        assertTrue(has(bad(CEDAR, d("P1 stated Dr. Cedar is not recommended for teenagers.")), "R2"));
    }

    @Test void polarityChecksStillApply() {
        // negation borrowed from no cited evidence: m1 is "for", and its quote has no "not"
        assertTrue(has(bad(CEDAR, d("P1 stated Dr. Cedar is not excellent for babies [m1].", "m1")), "R9"));
    }

    @Test void statementKindChecksStillApply() {
        var x = bad(OFFER, d("P1 stated they will bring snacks [o1].", "o1"));
        assertTrue(has(x, "R6"), x.toString());
        ok(OFFER, d("P1 stated they can bring snacks if nobody else does [o1].", "o1"));
    }

    @Test void conflictPolicyUnchanged() {
        var conflict = new Request("Is Coach Willow good for beginners?", "draft", List.of(
                new Evidence("w1", "P1", "2026-11-03", "ATTRIBUTED_STATEMENT", 1, true, "Coach Willow is great for beginners."),
                new Evidence("w3", "P2", "2026-11-03", "ATTRIBUTED_STATEMENT", -1, true, "Coach Willow is not good for beginners.")),
                List.of(), true);
        assertTrue(has(bad(conflict, d("P1 stated Coach Willow is great for beginners [w1].", "w1")), "R5"));
        ok(conflict, d("P1 stated Coach Willow is great for beginners [w1]. P2 stated Coach Willow is not good for beginners [w3].", "w1", "w3"));
    }
}
