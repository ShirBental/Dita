package app.dita.core.answer.synthesis;

import app.dita.core.answer.synthesis.AnswerSynthesizer.Draft;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Evidence;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Request;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-054 DEC-051 regression: R4 attribution carried within one sentence, R8 topic references / reporting verbs / word
 * forms, R11 dropped qualifiers. The evidence is the synthetic picnic conversation as sent in the live rehearsal
 * run-20261104T155040Z-0bb1 (ids shortened); the accepted answer is Muse's saved response from that run.
 */
class SynthesisValidatorContinuationTest {
    final SynthesisValidator v = new SynthesisValidator();

    static Evidence e(String id, String speaker, String quote) { return new Evidence(id, speaker, "2026-11-04", "MESSAGE", 0, false, quote); }
    static final List<Evidence> PICNIC = List.of(
            e("m323", "P1", "Speaking of waiting around, are we still doing the park picnic this Saturday?"),
            e("m325", "P1", "I originally said Sunday, then moved it to Saturday because the library event is Sunday."),
            e("m327", "P1", "Noon at Meadow Park, near the west gate."),
            e("m328", "P2", "Noon is tricky for us. Could we make it one instead?"),
            e("m329", "P1", "One works better for us too. Let us make it Saturday at one, not noon."),
            e("m337", "P1", "I will. If rain is forecast, should we use the library room?"),
            e("m338", "P2", "I asked yesterday. It is booked, so that is not an option."),
            e("m339", "P1", "Then rain means postponing, unless we find somewhere else."),
            e("m342", "P2", "Thanks. Saturday at one, west gate, weather permitting. That is what I have now."));
    static final String Q = "When and where is the picnic, and what happens if it rains?";
    static Request req(String question) { return new Request(question, "draft", PICNIC, List.of(), false); }

    /** Muse's saved live answer (run-20261104T155040Z-0bb1 call-001), ids shortened. */
    static final String LIVE = "P1 said the picnic was moved to Saturday because the library event is Sunday [m325]. P1 said to make it "
            + "Saturday at one, not noon [m329], at Meadow Park near the west gate [m327], which P2 noted as Saturday at one, west gate, "
            + "weather permitting [m342]. P1 asked if the library room should be used if rain is forecast [m337], and said rain means "
            + "postponing unless somewhere else is found [m339].";

    static Draft d(String answer, String... ids) { return new Draft(false, answer, List.of(ids)); }
    void ok(Request r, Draft x) { var y = v.validate(r, x); assertTrue(y.ok(), y.violations().toString()); }
    List<String> bad(Request r, Draft x) { var y = v.validate(r, x); assertFalse(y.ok(), "expected rejection: " + x.answer()); return y.violations(); }
    static void has(List<String> vs, String rule) { assertTrue(vs.stream().anyMatch(s -> s.startsWith(rule)), rule + " expected in " + vs); }

    @Test void savedLiveAnswerIsAccepted() {
        ok(req(Q), d(LIVE, "m325", "m329", "m327", "m342", "m337", "m339"));
    }

    // ---- R4: carried attribution
    @Test void carryRequiresTheSameSpeakerInTheCitedEvidence() {
        // m338 is P2's: continuing P1's sentence onto it is a speaker change
        var x = bad(req(Q), d("P1 asked if the library room should be used if rain is forecast [m337], and said it is booked [m338].", "m337", "m338"));
        has(x, "R4");
        assertTrue(x.toString().contains("is by P2"), x.toString());
    }

    @Test void carryStopsAtASentenceBoundary() {
        has(bad(req(Q), d("P1 said to make it Saturday at one, not noon [m329]. At Meadow Park near the west gate [m327].", "m329", "m327")), "R4");
    }

    @Test void noCarryFromAClauseThatNamedNobody() {
        has(bad(req(Q), d("At Meadow Park near the west gate [m327], and Saturday at one, not noon [m329].", "m327", "m329")), "R4");
    }

    @Test void explicitAttributionStillHasToMatchTheEvidence() {
        has(bad(req(Q), d("P2 said to make it Saturday at one, not noon [m329].", "m329")), "R4");
        has(bad(req(Q), d("P1 said to make it Saturday at one, not noon [m329], and P2 said at Meadow Park [m327].", "m329", "m327")), "R4");
    }

    @Test void carryAfterAMixedSpeakerClauseIsNotAllowed() {
        has(bad(req(Q), d("P1 and P2 said Saturday at one [m329 m342], at Meadow Park near the west gate [m327].", "m329", "m342", "m327")), "R4");
    }

    // ---- R8: topic references, reporting verbs, word forms
    @Test void aFalsePremiseInTheQuestionDoesNotAuthorizeTheTerm() {
        var r = req("When and where is the barbecue, and what happens if it rains?");
        has(bad(r, d("P1 said to make the barbecue Saturday at one, not noon [m329].", "m329")), "R8");
    }

    @Test void questionTermsOnlyIdentifyTheTopicNeverAClaim() {
        // "picnic" outside a topic reference, and other question words, stay unsupported
        has(bad(req(Q), d("P1 said it is a picnic at one, not noon [m329].", "m329")), "R8");
        has(bad(req("Is the picnic cancelled if it rains?"), d("P1 said the picnic is cancelled [m339].", "m339")), "R8");
    }

    @Test void inventedFactsStayUnsupported() {
        var x = bad(req(Q), d("P1 said the picnic was moved to Saturday because of a football match [m325].", "m325"));
        has(x, "R8");
        has(bad(req(Q), d("P1 said the library room was booked [m337].", "m337")), "R8");          // booked is m338's, not m337's
        has(bad(req(Q), d("P1 said to make it Saturday at two, not noon [m329].", "m329")), "R8");
    }

    @Test void reportingVerbOnlyInAttributionPosition() {
        ok(req(Q), d("P2 noted Saturday at one, west gate, weather permitting [m342].", "m342"));
        has(bad(req(Q), d("P2 said the plan was noted, Saturday at one, west gate, weather permitting [m342].", "m342")), "R8");
    }

    @Test void wordFormsAreConservative() {
        ok(req(Q), d("P1 said rain means postponing unless somewhere else is found [m339].", "m339"));
        // "postponed" (an inflected form of nothing quoted: the quote says "postponing") is accepted as the same word...
        ok(req(Q), d("P1 said rain means the picnic is postponed unless somewhere else is found [m339].", "m339"));
        // ...but a different word is not a form: "cancelled" for "postponing"
        has(bad(req(Q), d("P1 said rain means the picnic is cancelled unless somewhere else is found [m339].", "m339")), "R8");
        assertFalse(SynthesisValidator.formOf("not", Set.of("noted")));
        assertFalse(SynthesisValidator.formOf("noted", Set.of("not")));
        assertFalse(SynthesisValidator.formOf("is", Set.of("i")));
    }

    // ---- unchanged guards
    @Test void polarityChangeIsRejected() {
        has(bad(req(Q), d("P1 said rain does not mean postponing unless somewhere else is found [m339].", "m339")), "R9");
    }

    @Test void invalidCitationsAreRejected() {
        has(bad(req(Q), d("P1 said to make it Saturday at one, not noon [m999].", "m999")), "R2");
        has(bad(req(Q), d("P1 said to make it Saturday at one, not noon [m329].", "m329", "m327")), "R2");
    }

    // ---- R11: dropped qualifiers
    @Test void droppedQualifiersAreRejected() {
        var x = bad(req(Q), d("P2 noted Saturday at one, west gate [m342].", "m342"));
        has(x, "R11");
        has(bad(req(Q), d("P1 said rain means postponing [m339].", "m339")), "R11");
        ok(req(Q), d("P1 said rain means postponing, unless somewhere else is found [m339].", "m339"));
    }

    // ---- Natasha DEC-051 review: bypasses that must stay closed
    static Request one(String question, Evidence... ev) { return new Request(question, "draft", List.of(ev), List.of(), false); }

    @Test void blockerCurlyApostropheNegationIsRejected() {
        var r = one("Can P1 come?", e("m1", "P1", "I can come on Saturday."));
        has(bad(r, d("P1 said they can\u2019t come on Saturday [m1].", "m1")), "R");
        has(bad(r, d("P1 can\u2019t come on Saturday [m1].", "m1")), "R");
        has(bad(r, d("P1 can't come on Saturday [m1].", "m1")), "R");
        var w = one("Will P1 win?", e("m1", "P1", "We won the match on Saturday."));
        has(bad(w, d("P1 said we won\u2019t win the match on Saturday [m1].", "m1")), "R");
    }

    @Test void highCarryCannotHideWhoSpoke() {
        var r = one("Is rain a problem?", e("m1", "P1", "Rain is fine for us."), e("m2", "P1", "Rain is risky for the plates."));
        var x = bad(r, d("P1 said rain is fine for us [m1], while other members said rain is risky for the plates [m2].", "m1", "m2"));
        has(x, "R4");
        has(bad(r, d("P1 said rain is fine for us [m1], and they said rain is risky for the plates [m2].", "m1", "m2")), "R4");
        ok(r, d("P1 said rain is fine for us [m1], and said rain is risky for the plates [m2].", "m1", "m2"));
    }

    @Test void highDroppedNegationIsRejected() {
        var r = one("Did P1 find a venue?", e("m1", "P1", "I can't find a venue for Friday."));
        has(bad(r, d("P1 found a venue for Friday [m1].", "m1")), "R12");
        has(bad(r, d("P1 said a venue for Friday was found [m1].", "m1")), "R12");
        ok(r, d("P1 said they can't find a venue for Friday [m1].", "m1"));
    }

    @Test void mediumLooseFormsAreClosed() {
        assertFalse(SynthesisValidator.formOf("feed", Set.of("fee")));
        assertFalse(SynthesisValidator.formOf("seed", Set.of("see")));
        assertFalse(SynthesisValidator.formOf("shed", Set.of("she")));
        assertFalse(SynthesisValidator.formOf("left", Set.of("leave")));
        assertTrue(SynthesisValidator.formOf("used", Set.of("use")));
        assertTrue(SynthesisValidator.formOf("moved", Set.of("move")));
    }

    @Test void mediumQualifierMustKeepItsClass() {
        // a hedge does not stand in for a dropped condition, nor the reverse
        has(bad(req(Q), d("P2 noted Saturday at one, west gate, maybe [m342].", "m342")), "R11");
        var h = one("Will P1 come?", e("m1", "P1", "I might come on Saturday."));
        has(bad(h, d("P1 said they come on Saturday if invited [m1].", "m1")), "R11");
    }

    // ---- Natasha DEC-051 re-review
    @Test void otherApostrophesAndSpacedContractionsAreNegations() {
        var r = one("Can P1 come?", e("m1", "P1", "I can come on Saturday."));
        for (String ap : List.of("`", "\u00B4", "\u2032", "\u02BC", "\uFF07", " "))
            assertFalse(v.validate(r, d("P1 can" + ap + "t come on Saturday [m1].", "m1")).ok(), "apostrophe variant U+" + Integer.toHexString(ap.charAt(0)));
    }

    @Test void moreWaysToHideTheSpeakerAreRejected() {
        var r = one("Is rain a problem?", e("m1", "P1", "Rain is fine for us."), e("m2", "P1", "Rain is risky for the plates."));
        for (String w : List.of("both said", "who said", "we said", "my friend said", "the chat said"))
            has(bad(r, d("P1 said rain is fine for us [m1], and " + w + " rain is risky for the plates [m2].", "m1", "m2")), "R4");
    }

    @Test void notConfirmedDoesNotKeepADroppedNegation() {
        var r = one("Did P1 find a venue?", e("m1", "P1", "I can't find a venue for Friday."));
        has(bad(r, d("P1 found a venue for Friday, not confirmed [m1].", "m1")), "R12");
    }

    @Test void offerWordingKeepsAHedgeOnlyForOffers() {
        var r = one("Will P1 come?", e("m1", "P1", "I might come on Saturday."));
        has(bad(r, d("P1 offered to come on Saturday [m1].", "m1")), "R11");
        ok(r, d("P1 said they might come on Saturday [m1].", "m1"));
        ok(r, d("P1 could come on Saturday [m1].", "m1"));
    }

    @Test void contractionsWithoutApostropheAreNegations() {
        var r = one("Did P1 find a venue?", e("m1", "P1", "I cant find a venue for Friday."));
        has(bad(r, d("P1 found a venue for Friday [m1].", "m1")), "R12");
        var c = one("Can P1 come?", e("m1", "P1", "I can come on Saturday."));
        assertFalse(v.validate(c, d("P1 cant come on Saturday [m1].", "m1")).ok());
    }
}
