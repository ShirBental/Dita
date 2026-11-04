package app.dita.core.answer.synthesis;

import app.dita.core.answer.synthesis.AnswerSynthesizer.Draft;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Evidence;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Request;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-054 DEC-052, HAND-WRITTEN FIXTURES (not model output): restatements are not confirmations (R6 unchanged), genuine
 * decisions may be called decided/confirmed, and "later" needs send times that establish the order. Varied fictional
 * subjects (book club, carpool, dentist, bake sale) so nothing depends on the picnic conversation.
 */
class SynthesisValidatorOrderAndRestatementTest {
    final SynthesisValidator v = new SynthesisValidator();
    static final Instant T = Instant.parse("2027-03-01T18:00:00Z");

    static Evidence e(String id, String sp, String kind, String quote) { return new Evidence(id, sp, "2027-03-01", kind, 0, false, quote); }
    static Request req(String q, List<Evidence> ev, Map<String, Instant> at) { return new Request(q, "draft", ev, List.of(), false, null, at); }
    static Map<String, Instant> at(Object... idAndMinutes) {
        Map<String, Instant> m = new HashMap<>();
        for (int i = 0; i < idAndMinutes.length; i += 2) m.put((String) idAndMinutes[i], T.plusSeconds(60L * (Integer) idAndMinutes[i + 1]));
        return m;
    }
    static Draft d(String a, String... ids) { return new Draft(false, a, List.of(ids)); }
    void ok(Request r, Draft x) { var y = v.validate(r, x); assertTrue(y.ok(), y.violations().toString()); }
    List<String> bad(Request r, Draft x) { var y = v.validate(r, x); assertFalse(y.ok(), "expected rejection: " + x.answer()); return y.violations(); }
    static void has(List<String> vs, String prefix) { assertTrue(vs.stream().anyMatch(s -> s.startsWith(prefix)), prefix + " expected in " + vs); }

    // ---- book club: proposal, correction, neutral restatement
    static final List<Evidence> CLUB = List.of(
            e("b1", "P1", "MESSAGE", "Book club is Thursday at seven in the back room."),
            e("b2", "P2", "MESSAGE", "Seven is late for me. Could we do six?"),
            e("b3", "P1", "MESSAGE", "Six works. Thursday at six, not seven."),
            e("b4", "P2", "MESSAGE", "Thanks. Thursday at six, back room. That is what I wrote down."));
    static final String CQ = "When is the book club?";

    @Test void neutralRestatementIsNotAConfirmation() {
        var r = req(CQ, CLUB, at("b1", 0, "b2", 1, "b3", 2, "b4", 3));
        ok(r, d("P1 said Thursday at six, not seven [b3]. P2 noted Thursday at six, back room [b4].", "b3", "b4"));
        for (String verb : List.of("confirmed", "decided", "agreed", "committed to", "promised"))
            has(bad(r, d("P1 said Thursday at six, not seven [b3]. P2 " + verb + " Thursday at six, back room [b4].", "b3", "b4")), "R");
        has(bad(r, d("P1 said Thursday at six, not seven [b3]. P2 confirmed Thursday at six, back room [b4].", "b3", "b4")), "R6");
    }

    @Test void laterIsAllowedWhenTimestampsEstablishTheOrder() {
        var r = req(CQ, CLUB, at("b1", 0, "b2", 1, "b3", 2, "b4", 3));
        ok(r, d("P1 said book club is Thursday at seven in the back room [b1], and P1 later said Thursday at six, not seven [b3].", "b1", "b3"));
    }

    @Test void laterIsRejectedForReversedChronology() {
        var r = req(CQ, CLUB, at("b1", 5, "b2", 1, "b3", 2, "b4", 3));            // b1 sent AFTER b3
        has(bad(r, d("P1 said book club is Thursday at seven in the back room [b1], and P1 later said Thursday at six, not seven [b3].", "b1", "b3")), "R8 \"later\"");
        var cited = req(CQ, CLUB, at("b1", 0, "b2", 1, "b3", 2, "b4", 3));         // the answer itself reverses the order
        has(bad(cited, d("P1 said Thursday at six, not seven [b3], and P1 later said book club is Thursday at seven in the back room [b1].", "b3", "b1")), "R8 \"later\"");
    }

    @Test void laterIsRejectedWhenOrderIsUncertain() {
        has(bad(req(CQ, CLUB, Map.of()), d("P1 said book club is Thursday at seven in the back room [b1], and P1 later said Thursday at six, not seven [b3].", "b1", "b3")), "R8 \"later\"");
        has(bad(req(CQ, CLUB, at("b1", 2, "b3", 2)), d("P1 said book club is Thursday at seven in the back room [b1], and P1 later said Thursday at six, not seven [b3].", "b1", "b3")), "R8 \"later\"");
        has(bad(req(CQ, CLUB, at("b3", 2)), d("P1 said book club is Thursday at seven in the back room [b1], and P1 later said Thursday at six, not seven [b3].", "b1", "b3")), "R8 \"later\"");
        has(bad(req(CQ, CLUB, at("b1", 0, "b3", 2)), d("P1 later said Thursday at six, not seven [b3].", "b3")), "R8 \"later\"");   // nothing before it
    }

    // ---- carpool: a genuine decision may be called decided/confirmed
    @Test void genuineDecisionsMayBeCalledDecidedOrConfirmed() {
        var r = req("Who drives on Monday?", List.of(
                e("c1", "P3", "DECISION", "Final: I drive on Monday and P4 drives on Wednesday."),
                e("c2", "P4", "CONFIRMED_COMMITMENT", "Confirmed, I will drive on Wednesday.")), Map.of());
        ok(r, d("P3 decided that P3 drives on Monday [c1].", "c1"));
        ok(r, d("P4 confirmed P4 will drive on Wednesday [c2].", "c2"));
    }

    @Test void theQuoteItselfSayingConfirmedIsEnough() {
        var r = req("Is the dentist appointment set?", List.of(
                e("d1", "P2", "MESSAGE", "The clinic confirmed Tuesday at nine.")), Map.of());
        ok(r, d("P2 said the clinic confirmed Tuesday at nine [d1].", "d1"));
        has(bad(req("Is the dentist appointment set?", List.of(e("d2", "P2", "MESSAGE", "I think it is Tuesday at nine.")), Map.of()),
                d("P2 confirmed Tuesday at nine [d2].", "d2")), "R");
    }

    // ---- bake sale: "as" and other connecting words are not whitelisted
    @Test void connectingWordsAreNotWhitelisted() {
        var r = req("What is the bake sale price?", List.of(e("s1", "P1", "MESSAGE", "Cookies are two each.")), Map.of());
        has(bad(r, d("P1 described cookies as two each [s1].", "s1")), "R8");
        has(bad(r, d("P1 said cookies are two each, as agreed [s1].", "s1")), "R");
        ok(r, d("P1 said cookies are two each [s1].", "s1"));
    }

    // ---- the original live "confirmed" answer pattern stays rejected even with send times
    @Test void liveConfirmedPatternStaysRejectedWithTimestamps() {
        var ev = List.of(e("m329", "P1", "MESSAGE", "One works better for us too. Let us make it Saturday at one, not noon."),
                e("m342", "P2", "MESSAGE", "Thanks. Saturday at one, west gate, weather permitting. That is what I have now."));
        var r = req("When is the picnic?", ev, at("m329", 0, "m342", 13));
        var x = bad(r, d("P1 said to make it Saturday at one, not noon [m329], which P2 confirmed as Saturday at one, west gate, weather permitting [m342].", "m329", "m342"));
        has(x, "R6");
        ok(r, d("P1 said to make it Saturday at one, not noon [m329], which P2 noted as Saturday at one, west gate, weather permitting [m342].", "m329", "m342"));
    }

    // ---- Natasha DEC-052 review: every order word is gated, and "later" is checked against everything cited before
    @Test void otherOrderWordsAreGated() {
        var r = req(CQ, CLUB, at("b1", 0, "b2", 1, "b3", 2, "b4", 3));
        ok(r, d("P1 said book club is Thursday at seven in the back room [b1], then P1 said Thursday at six, not seven [b3].", "b1", "b3"));
        has(bad(r, d("P1 said Thursday at six, not seven [b3], then P1 said book club is Thursday at seven in the back room [b1].", "b3", "b1")), "R8 \"then\"");
        has(bad(req(CQ, CLUB, Map.of()), d("P1 said book club is Thursday at seven in the back room [b1], then P1 said Thursday at six, not seven [b3].", "b1", "b3")), "R8 \"then\"");
        has(bad(r, d("P1 first said book club is Thursday at seven in the back room [b1].", "b1")), "R8 \"first\"");
        has(bad(r, d("P1 previously said book club is Thursday at seven in the back room [b1].", "b1")), "R8 \"previously\"");
        has(bad(r, d("P1 said Thursday at six, not seven [b3]; the earlier plan was Thursday at seven in the back room [b1].", "b3", "b1")), "R8 \"earlier\"");
    }

    @Test void thenInsideTheQuoteIsContent() {
        var r = req("What if the kiln is broken?", List.of(e("k1", "P1", "MESSAGE", "Then a broken kiln means we only shape the pieces.")), Map.of());
        ok(r, d("P1 said then a broken kiln means we only shape the pieces [k1].", "k1"));
    }

    @Test void laterMustFollowEverythingCitedBefore() {
        var r = req(CQ, CLUB, at("b1", 5, "b2", 1, "b3", 2, "b4", 6));    // b1 is newest of the first three
        has(bad(r, d("P1 said book club is Thursday at seven in the back room [b1]. P2 asked could we do six [b2], and P1 later said Thursday at six, not seven [b3].", "b1", "b2", "b3")), "R8 \"later\"");
        ok(r, d("P1 said Thursday at six, not seven [b3], and P2 later said Thursday at six, back room [b4].", "b3", "b4"));
    }

    @Test void thenInAnotherCitedQuoteDoesNotAuthorizeTheOrder() {
        var ev = new ArrayList<>(CLUB); ev.add(e("k2", "P2", "MESSAGE", "See you then, Thursday at six."));
        var r = req(CQ, ev, at("b1", 5, "b2", 1, "b3", 2, "b4", 3, "k2", 4));    // b3 and k2 both before b1
        has(bad(r, d("P1 said book club is Thursday at seven in the back room [b1], then P1 said Thursday at six, not seven [b3][k2].", "b1", "b3", "k2")), "R8 \"then\"");
    }
}
