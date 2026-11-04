package app.dita.core.answer.synthesis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-053: one focused case per validator rule (synthetic evidence; no model). */
class SynthesisValidatorTest {
    final SynthesisValidator v = new SynthesisValidator();
    static final AnswerSynthesizer.Evidence FOR = new AnswerSynthesizer.Evidence("m1", "P1", "2027-06-01", "ATTRIBUTED_STATEMENT", 1, false,
            "I recommend Pine Hill park, it has shade");
    static final AnswerSynthesizer.Evidence AGAINST = new AnswerSynthesizer.Evidence("m2", "P2", "2027-06-01", "ATTRIBUTED_STATEMENT", -1, false,
            "Avoid Pine Hill park, the parking is impossible");
    static final AnswerSynthesizer.Evidence OFFER = new AnswerSynthesizer.Evidence("m5", "P3", "2027-06-01", "OFFER", 0, true,
            "I can probably bring the grill if nobody else does");
    static final AnswerSynthesizer.Evidence QUESTION = new AnswerSynthesizer.Evidence("m7", "P4", "2027-06-01", "QUESTION", 0, false,
            "Who is bringing the drinks?");

    static AnswerSynthesizer.Request req(boolean conflicting, List<String> replaced, AnswerSynthesizer.Evidence... ev) {
        return new AnswerSynthesizer.Request("Should we go to Pine Hill park?", "draft", List.of(ev), replaced, conflicting);
    }
    static AnswerSynthesizer.Draft draft(String text, String... ids) { return new AnswerSynthesizer.Draft(false, text, List.of(ids)); }
    List<String> violations(AnswerSynthesizer.Request r, AnswerSynthesizer.Draft d) { return v.validate(r, d).violations(); }
    static boolean has(List<String> vs, String rule) { return vs.stream().anyMatch(x -> x.startsWith(rule)); }

    @Test void aFaithfulConflictAnswerPasses() {
        var vs = violations(req(true, List.of(), FOR, AGAINST),
                draft("P1 recommends Pine Hill park, which has shade [m1], while P2 advises against it: the parking is impossible [m2].", "m1", "m2"));
        assertEquals(List.of(), vs);
        // causal links are claims too: "because" is allowed only when the evidence itself says so (R8 is strict on purpose)
        assertTrue(has(violations(req(true, List.of(), FOR, AGAINST),
                draft("P1 recommends Pine Hill park because it has shade [m1], while P2 advises against it [m2].", "m1", "m2")), "R8"));
    }

    @Test void r1EmptyOrUrl() {
        assertTrue(has(violations(req(false, List.of(), FOR), draft("  ")), "R1"));
        assertTrue(has(violations(req(false, List.of(), FOR), draft("P1 recommends it, see https://x.example [m1].", "m1")), "R1"));
    }

    @Test void r2UnknownMarkerOrCitedId() {
        assertTrue(has(violations(req(false, List.of(), FOR), draft("P1 recommends Pine Hill park [m1] [o1].", "m1")), "R2"));
        assertTrue(has(violations(req(false, List.of(), FOR), draft("P1 recommends Pine Hill park [m1].", "m1", "m9")), "R2"));
    }

    @Test void r3UncitedSentence() {
        assertTrue(has(violations(req(false, List.of(), FOR), draft("P1 recommends Pine Hill park [m1]. It has shade.", "m1")), "R3"));
    }

    @Test void r4AttributionMustMatchTheCitedSpeaker() {
        assertTrue(has(violations(req(false, List.of(), FOR, AGAINST), draft("P2 recommends Pine Hill park [m1].", "m1")), "R4"));
        assertTrue(has(violations(req(false, List.of(), FOR), draft("Someone recommends Pine Hill park [m1].", "m1")), "R4"));
    }

    @Test void r5ConflictMustKeepBothSidesAndNeverClaimConsensus() {
        assertTrue(has(violations(req(true, List.of(), FOR, AGAINST), draft("P1 recommends Pine Hill park [m1].", "m1")), "R5"));
        assertTrue(has(violations(req(true, List.of(), FOR, AGAINST),
                draft("P1 recommends Pine Hill park [m1] and P2 says avoid it [m2], so there is consensus.", "m1", "m2")), "R5"));
    }

    @Test void r6OfferStaysAnOfferAndAQuestionStaysOpen() {
        assertTrue(has(violations(req(false, List.of(), OFFER), draft("P3 will bring the grill [m5].", "m5")), "R6"));
        assertEquals(List.of(), violations(req(false, List.of(), OFFER), draft("P3 offered to bring the grill if nobody else does, not confirmed [m5].", "m5")));
        assertTrue(has(violations(req(false, List.of(), QUESTION), draft("P4 is bringing the drinks [m7].", "m7")), "R6"));
        assertEquals(List.of(), violations(req(false, List.of(), QUESTION), draft("P4 asked who is bringing the drinks; it is unanswered [m7].", "m7")));
    }

    @Test void r7ReplacedIdOnlyWithReplacementWording() {
        assertTrue(has(violations(req(false, List.of("m3"), FOR), draft("P1 recommends Pine Hill park [m1] [m3].", "m1")), "R7"));
        assertEquals(List.of(), violations(req(false, List.of("m3"), FOR), draft("P1 recommends Pine Hill park, which replaced an earlier message [m1] [m3].", "m1")));
    }

    @Test void r8NewFactsAndNumbersAreUnsupported() {
        var vs = violations(req(false, List.of(), FOR), draft("P1 recommends Pine Hill park for its 3 lakes [m1].", "m1"));
        assertTrue(has(vs, "R8"), vs.toString());
        assertTrue(v.validate(req(false, List.of(), FOR), draft("P1 recommends Pine Hill park for its 3 lakes [m1].", "m1")).unsupportedTerms().contains("3"));
    }

    // ---- Natasha TASK-053 F1-F4: per-clause grounding ----

    @Test void f1TheQuestionCannotLendItsPremise() {
        var r = new AnswerSynthesizer.Request("Is the park closed for the season?", "draft", List.of(FOR), List.of(), false);
        var vs = violations(r, draft("P1 said the park is closed for the season [m1].", "m1"));
        assertTrue(has(vs, "R8"), vs.toString());
    }

    @Test void f2NegationFlipIsRejected() {
        var vs = violations(req(false, List.of(), FOR), draft("P1 does not recommend Pine Hill park [m1].", "m1"));
        assertTrue(has(vs, "R9"), vs.toString());
        // negation that the evidence carries is fine
        assertEquals(List.of(), violations(req(false, List.of(), AGAINST), draft("P2 says avoid Pine Hill park, the parking is impossible [m2].", "m2")));
    }

    @Test void f3DecisionEscalationIsRejected() {
        var vs = violations(req(false, List.of(), FOR), draft("P1 decided on Pine Hill park [m1].", "m1"));
        assertTrue(has(vs, "R6"), vs.toString());
        var claim = new AnswerSynthesizer.Evidence("m4", "P1", "2027-06-01", "REPORTED_CLAIM", 0, false, "The park gates close at noon");
        assertTrue(has(violations(req(false, List.of(), claim), draft("P1 says the park gates close at noon [m4].", "m4")), "R6"));
        assertEquals(List.of(), violations(req(false, List.of(), claim), draft("P1 reported that the park gates close at noon [m4].", "m4")));
    }

    @Test void f4AClauseCannotBorrowAnotherClausesWordsOrSpeaker() {
        var vs = violations(req(true, List.of(), FOR, AGAINST),
                draft("P1 recommends Pine Hill park, the parking is impossible [m1], while P2 advises against it [m2].", "m1", "m2"));
        assertTrue(has(vs, "R8"), vs.toString());
        vs = violations(req(true, List.of(), FOR, AGAINST), draft("P2 recommends Pine Hill park [m1], while P1 advises against it [m2].", "m1", "m2"));
        assertTrue(has(vs, "R4"), vs.toString());
    }

    @Test void clausesSplitAtMarkerRuns() {
        var cs = SynthesisValidator.clauses("A b [m1] [x9], c d [m2]. tail", java.util.Set.of("m1", "m2"));
        assertEquals(3, cs.size());
        assertEquals(java.util.Set.of("m1"), cs.get(0).support());
        assertEquals(java.util.Set.of("m1", "x9"), cs.get(0).markers());
        assertEquals(java.util.Set.of("m2"), cs.get(1).support());
        assertEquals(". tail", cs.get(2).text());
    }

    @Test void citationsListMustMatchTheMarkers() {
        assertTrue(has(violations(req(false, List.of(), FOR, AGAINST), draft("P1 recommends Pine Hill park [m1].", "m1", "m2")), "R2"));
    }
}
