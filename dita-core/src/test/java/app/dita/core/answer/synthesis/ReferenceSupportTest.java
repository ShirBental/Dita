package app.dita.core.answer.synthesis;

import app.dita.core.answer.*;
import app.dita.core.model.*;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceSupportTest {
    private final GroupScope scope = new GroupScope("g");
    private final InMemoryMessageStore store = new InMemoryMessageStore();
    private final Instant at = Instant.parse("2026-11-04T13:20:00Z");
    private final List<AnswerSynthesizer.Evidence> ev = List.of(
            e("q", "P1", "I will. If rain is forecast, should we use the library room?"),
            e("a", "P2", "I asked yesterday. It is booked, so that is not an option."));
    static AnswerSynthesizer.Evidence e(String id, String who, String quote) {
        return new AnswerSynthesizer.Evidence(id, who, "2026-11-04", "MESSAGE", 0, false, quote);
    }
    void append(String id, String who, int second, String reply, String text) {
        store.append(scope, new Message(id, "g", who, at.plusSeconds(second), reply, text, SourceKind.SYNTHETIC, false));
    }
    void pair() {
        append("q", "alice", 0, null, ev.get(0).quote());
        append("a", "bob", 1, null, ev.get(1).quote());
    }
    AnswerSynthesizer.Request request(List<AnswerSynthesizer.Evidence> evidence, Map<String,String> refs) {
        return new AnswerSynthesizer.Request("What happens if it rains?", "", evidence, List.of(), false, null, Map.of(), refs);
    }
    AnswerSynthesizer.Draft answer(String text) { return new AnswerSynthesizer.Draft(false, text, List.of("a")); }
    static final String GOOD = "P2 said the library room \"is booked, so that is not an option\" [a].";

    @Test void resolvesOnlySubjectAndRetainsAntecedentAsSource() {
        pair();
        var refs = ReferenceSupport.selectedQuestions(store, scope, ev);
        assertEquals(Map.of("a", "q"), refs);
        var result = new SynthesisValidator().validate(request(ev, refs), answer(GOOD));
        assertTrue(result.ok(), result.violations().toString());
        assertEquals(Set.of("q"), result.contextualSourceIds());
        assertFalse(new SynthesisValidator().validate(request(ev, Map.of()), answer(GOOD)).ok());
        for (String bad : List.of(GOOD.replace("library room", "school room"), GOOD.replace("booked", "available"),
                GOOD.replace("P2", "P1"), GOOD.replace("not an option", "an option"),
                GOOD.replace("is booked", "is booked for Saturday"), GOOD.replace("is booked", "is booked because of rain")))
            assertFalse(new SynthesisValidator().validate(request(ev, refs), answer(bad)).ok(), bad);
    }

    @Test void sparseSelectionDoesNotInventAdjacencyOrOverrideReply() {
        append("q", "alice", 0, null, ev.get(0).quote());
        append("other", "alice", 1, null, "Is the cafe open?");
        append("a", "bob", 2, null, ev.get(1).quote());
        assertTrue(ReferenceSupport.selectedQuestions(store, scope, ev).isEmpty());
        var otherStore = new InMemoryMessageStore();
        otherStore.append(scope, new Message("other", "g", "alice", at.minusSeconds(1), null, "Another topic?", SourceKind.SYNTHETIC, false));
        otherStore.append(scope, new Message("q", "g", "alice", at, null, ev.get(0).quote(), SourceKind.SYNTHETIC, false));
        otherStore.append(scope, new Message("a", "g", "bob", at.plusSeconds(1), "other", ev.get(1).quote(), SourceKind.SYNTHETIC, false));
        assertTrue(ReferenceSupport.selectedQuestions(otherStore, scope, ev).isEmpty());
    }

    @Test void missingChangedAndAmbiguousAntecedentsDoNotLicenseResolution() {
        pair();
        assertTrue(ReferenceSupport.selectedQuestions(store, scope, List.of(ev.get(1))).isEmpty());
        var ambiguous = List.of(e("q", "P1", "Should we use the library room or cafe?"), ev.get(1));
        assertTrue(ReferenceSupport.selectedQuestions(store, scope, ambiguous).isEmpty());
        store.edit(scope, "q", "Should we use the school room?", at.plusSeconds(2));
        assertTrue(ReferenceSupport.selectedQuestions(store, scope, ev).isEmpty());
    }

    @Test void whereLabelDoesNotLicenseAnInventedPlaceOrSpeaker() {
        var place = List.of(e("p", "P1", "Noon at Meadow Park, near the west gate."));
        var req = new AnswerSynthesizer.Request("When and where is the picnic?", "", place, List.of(), false);
        String good = "P1 said the location is \"Meadow Park, near the west gate\" [p].";
        var validator = new SynthesisValidator();
        assertTrue(validator.validate(req, new AnswerSynthesizer.Draft(false, good, List.of("p"))).ok());
        for (String bad : List.of(good.replace("Meadow", "Cedar"), good.replace("west", "east"), good.replace("P1", "P2")))
            assertFalse(validator.validate(req, new AnswerSynthesizer.Draft(false, bad, List.of("p"))).ok(), bad);
        var notWhere = new AnswerSynthesizer.Request("When is the picnic?", "", place, List.of(), false);
        assertFalse(validator.validate(notWhere, new AnswerSynthesizer.Draft(false, good, List.of("p"))).ok());
    }

    @Test void productionPathAddsContextSourceWithoutChangingQuotes() {
        pair();
        var citations = List.of(new Citation("q", "alice", at, ev.get(0).quote(), Citation.Evidence.EXPLICIT, 0, false),
                new Citation("a", "bob", at.plusSeconds(1), ev.get(1).quote(), Citation.Evidence.INFERRED, 0, false));
        var synth = new AnswerSynthesizer() {
            public String version() { return "offline-test"; }
            public Draft synthesize(Request req) { assertEquals(Map.of("a", "q"), req.referenceQuestions()); return answer(GOOD); }
        };
        var result = new GroundedSynthesis(store, synth, x -> x).apply(scope,
                new GroundedAnswer("What happens if it rains?", "baseline", citations, false, true));
        assertEquals(GroundedSynthesis.Outcome.SYNTHESIZED, result.outcome(), result.violations().toString());
        assertEquals(citations, result.answer().citations());
    }

    @Test void genericObjectsResolveButAlternativeQuestionsAndStalePairsDoNot() {
        for (String object : List.of("clinic lobby", "meeting room", "school hall")) {
            String question = "Should we book the " + object + "?";
            var evidence = List.of(e("q", "P1", question), ev.get(1));
            var local = new InMemoryMessageStore();
            local.append(scope, new Message("q", "g", "alice", at, null, question, SourceKind.SYNTHETIC, false));
            local.append(scope, new Message("a", "g", "bob", at.plusSeconds(1), "q", ev.get(1).quote(), SourceKind.SYNTHETIC, false));
            var refs = ReferenceSupport.selectedQuestions(local, scope, evidence);
            assertEquals(Map.of("a", "q"), refs);
            var verdict = new SynthesisValidator().validate(request(evidence, refs), answer(GOOD.replace("library room", object)));
            assertTrue(verdict.ok(), verdict.violations().toString());
        }
        for (String question : List.of("Should we use the library room or cafe?", "Should we use the cafe or book the library room?",
                "Is it open? Should we use the library room?", "Should we use another room?")) {
            var local = new InMemoryMessageStore();
            local.append(scope, new Message("q", "g", "alice", at, null, question, SourceKind.SYNTHETIC, false));
            local.append(scope, new Message("a", "g", "bob", at.plusSeconds(1), null, ev.get(1).quote(), SourceKind.SYNTHETIC, false));
            assertTrue(ReferenceSupport.selectedQuestions(local, scope, List.of(e("q", "P1", question), ev.get(1))).isEmpty(), question);
        }
        append("q", "alice", 0, null, ev.get(0).quote());
        append("a", "bob", 301, null, ev.get(1).quote());
        assertTrue(ReferenceSupport.selectedQuestions(store, scope, ev).isEmpty());
    }
}
