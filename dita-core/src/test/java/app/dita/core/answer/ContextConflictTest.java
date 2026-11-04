package app.dita.core.answer;

import app.dita.core.answer.synthesis.AnswerSynthesizer.ContextCheck;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Draft;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Evidence;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Request;
import app.dita.core.answer.synthesis.SynthesisValidator;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.HybridRetriever;
import app.dita.core.retrieval.Retriever;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-054 G2: context-sensitive conflicts for raw-message answers, without weakening genuine conflict checks.
 * Synthetic texts; deterministic; no model. Several subjects so nothing depends on one fixture.
 */
class ContextConflictTest {
    static Citation cite(String id, String text) {
        return new Citation(id, "u1", Instant.parse("2026-11-03T10:00:00Z"), text, Citation.Evidence.EXPLICIT, Stance.polarity(text), Stance.qualified(text));
    }
    static Evidence ev(String id, String text) { return new Evidence(id, "P1", "2026-11-03", "MESSAGE", Stance.polarity(text), false, text); }

    /** The request GroundedSynthesis builds for a raw-message answer (same helpers). */
    static Request request(String question, Map<String, String> texts) {
        List<Citation> cites = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        Map<String, Set<String>> ctx = new LinkedHashMap<>();
        texts.forEach((id, t) -> { cites.add(cite(id, t)); evidence.add(ev(id, t)); ctx.put(id, ContextCues.stated(t)); });
        var groups = ExtractiveAnswerComposer.conflictGroups(cites);
        return new Request(question, "draft", evidence, List.of(), !groups.isEmpty(), new ContextCheck(ContextCues.of(question), ctx, groups));
    }
    static Draft d(String answer, String... ids) { return new Draft(false, answer, List.of(ids)); }
    final SynthesisValidator v = new SynthesisValidator();
    List<String> violations(Request r, Draft d) { return v.validate(r, d).violations(); }

    static final String BABIES = "Dr. Cedar on Maple Street is excellent for babies.";
    static final String TEENS = "Dr. Cedar is not recommended for teenagers.";

    @Test void contextCuesComeFromForPhrasesAndAreEmptyWhenUnknown() {
        assertTrue(ContextCues.of(BABIES).contains("babies") || ContextCues.of(BABIES).contains("babie"), ContextCues.of(BABIES).toString());
        assertFalse(Collections.disjoint(ContextCues.of(TEENS), ContextCues.of("Is Dr. Cedar recommended for teenagers?")));
        assertTrue(ContextCues.stated("I recommend Dr. Cedar for infant checkups; he was patient with our baby.").contains("infant"));
        assertTrue(ContextCues.stated(BABIES).contains("babies") || ContextCues.stated(BABIES).contains("babie"));
        // a "for" that is not what the stance is about gives NO context (regression: SliceOneTest "for years")
        assertTrue(ContextCues.stated("Dr. Levi on Herzl St. is excellent, we've been going for years").isEmpty());
        assertTrue(ContextCues.stated("Dr. Cedar explained the checkup and gave us time for questions.").isEmpty(), "no stance cue");
        assertEquals(ContextCues.of("teenagers"), Set.of(), "sanity: no 'for'");
        assertFalse(ContextCues.stated("Dr. Levi is fine for babies but avoid him for teenagers").contains("babie"), "only the cue's clause");
        assertTrue(ContextCues.of("Dr. Cedar is terrible.").isEmpty(), "no 'for' phrase: unknown, never 'general'");
        assertTrue(ContextCues.of("What do members say about Dr. Cedar?").isEmpty());
    }

    @Test void anIncidentalForPhraseDoesNotHideAGenuineConflict() {
        var g = ExtractiveAnswerComposer.conflictGroups(List.of(cite("m2", "Dr. Levi on Herzl St. is excellent, we've been going for years"),
                cite("m5", "Dr. Levi is fine for babies but avoid him for teenagers, he was dismissive with my son")));
        assertEquals(List.of(Set.of("m2", "m5")), g);
    }

    @Test void capitalisedNonNamesAndGenericPopulationsDoNotHideAConflict() {
        // Natasha M2: "Terrible"/"Monday" are not subjects; the pronoun side stays uncertain → conflict kept
        assertEquals(1, ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", "We recommend Dr. Cedar."), cite("b", "Avoid him. Terrible on Monday."))).size());
        assertEquals(Set.of(), ContextCues.subjects("Avoid him. Terrible on Monday."));
        assertEquals(Set.of("cedar"), ContextCues.subjects("Dr. Cedar is not recommended for teenagers."));
        // Natasha M3: a generic population overlaps every context → conflict kept
        for (String generic : List.of("children", "kids", "everyone", "our family"))
            assertEquals(1, ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", BABIES), cite("b", "Avoid Dr. Cedar for " + generic + "."))).size(), generic);
        // R10: a generic question context is compatible with a specific statement (attributed, not extrapolated)
        var texts = new LinkedHashMap<String, String>(); texts.put("m1", BABIES);
        assertTrue(violations(request("Is Dr. Cedar good for kids?", texts), d("P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1"))
                .stream().noneMatch(x -> x.startsWith("R10")));
    }

    @Test void differentPopulationsAreNotAConflictButSameOrUnknownContextIs() {
        assertTrue(ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", BABIES), cite("b", TEENS))).isEmpty());
        // same context, opposite stance → genuine conflict
        var same = ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", "Coach Willow is great for teenagers."),
                cite("b", "I would not recommend Coach Willow for teenagers.")));
        assertEquals(List.of(Set.of("a", "b")), same);
        // unknown context on one side → uncertain → still a conflict
        assertEquals(1, ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", BABIES), cite("b", "Avoid Dr. Cedar."))).size());
        // different explicitly named subjects → not a conflict; a pronoun names nothing → still a conflict
        assertTrue(ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", "Coach Willow is great for teenagers."), cite("b", TEENS))).isEmpty());
        assertEquals(1, ExtractiveAnswerComposer.conflictGroups(List.of(cite("a", "Coach Willow is great for teenagers."),
                cite("b", "He is terrible for teenagers."))).size());
        // the composer's draft and flag follow the same rule
        var a = ExtractiveAnswerComposer.render("Is Dr. Cedar recommended for babies?", List.of(cite("a", BABIES), cite("b", TEENS)));
        assertFalse(a.conflicting()); assertTrue(a.text().startsWith("Members' views differ by context."), a.text());
    }

    @Test void focusedAnswersMayOmitTheOtherPopulation() {
        var texts = new LinkedHashMap<String, String>(); texts.put("m1", BABIES); texts.put("m2", TEENS);
        assertEquals(List.of(), violations(request("Is Dr. Cedar recommended for babies?", texts),
                d("P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1")));
        assertEquals(List.of(), violations(request("Is Dr. Cedar recommended for teenagers?", texts),
                d("P1 stated Dr. Cedar is not recommended for teenagers [m2].", "m2")));
        // general question: both contexts, no invented disagreement
        assertEquals(List.of(), violations(request("What do members say about Dr. Cedar?", texts),
                d("P1 said Dr. Cedar on Maple Street is excellent for babies [m1]. P1 stated Dr. Cedar is not recommended for teenagers [m2].", "m1", "m2")));
    }

    @Test void aGenuineSameContextConflictCannotBeReducedToOneSideEvenWithAnUnrelatedOpposite() {
        var texts = new LinkedHashMap<String, String>();
        texts.put("w1", "Coach Willow is great for teenagers.");
        texts.put("w2", "I would not recommend Coach Willow for teenagers.");
        texts.put("c1", BABIES);
        Request r = request("Is Coach Willow recommended for teenagers?", texts);
        assertTrue(r.conflicting());
        assertTrue(violations(r, d("P1 said Coach Willow is great for teenagers [w1].", "w1")).stream().anyMatch(x -> x.startsWith("R5")));
        // a positive statement about ANOTHER subject/context cannot stand in for the missing side (the old rule allowed it)
        var mixed = violations(r, d("P1 said Dr. Cedar on Maple Street is excellent for babies [c1]. P1 said I would not recommend Coach Willow for teenagers [w2].", "c1", "w2"));
        assertTrue(mixed.stream().anyMatch(x -> x.startsWith("R5 conflicting evidence: the supporting side is missing")), mixed.toString());
        // both sides → accepted
        assertEquals(List.of(), violations(r, d("P1 said Coach Willow is great for teenagers [w1]. P1 said I would not recommend Coach Willow for teenagers [w2].", "w1", "w2")));
    }

    @Test void aConflictAboutAnotherNamedSubjectDoesNotBlockAnAnswerButStillAppliesWheneverItMayBeInPlay() {
        var texts = new LinkedHashMap<String, String>();
        texts.put("c2", TEENS);
        texts.put("w1", "Coach Willow is great for teenagers.");
        texts.put("w2", "I would not recommend Coach Willow for teenagers.");
        String cedarOnly = "P1 stated Dr. Cedar is not recommended for teenagers [c2].";
        assertEquals(List.of(), violations(request("Is Dr. Cedar recommended for teenagers?", texts), d(cedarOnly, "c2")));
        // a question naming nobody: the Willow conflict may be what is asked about → both sides required
        assertTrue(violations(request("Who is recommended for teenagers?", texts), d(cedarOnly, "c2")).stream().anyMatch(x -> x.startsWith("R5")));
        // asked about Willow and answered one-sidedly → rejected
        assertTrue(violations(request("Is Coach Willow good for teenagers?", texts), d("P1 said Coach Willow is great for teenagers [w1].", "w1"))
                .stream().anyMatch(x -> x.startsWith("R5")));
        // a pronoun-only conflict (no name) is uncertain → in play for any question
        var pron = new LinkedHashMap<String, String>();
        pron.put("c2", TEENS); pron.put("p1", "He is great for teenagers."); pron.put("p2", "He is terrible for teenagers.");
        assertTrue(violations(request("Is Dr. Cedar recommended for teenagers?", pron), d(cedarOnly, "c2")).stream().anyMatch(x -> x.startsWith("R5")));
    }

    @Test void anUnsupportedPopulationIsNotAnsweredByExtrapolation() {
        var texts = new LinkedHashMap<String, String>(); texts.put("m1", BABIES); texts.put("m2", TEENS);
        var x = violations(request("Is Dr. Cedar recommended for adults?", texts),
                d("P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1"));
        assertTrue(x.stream().anyMatch(s -> s.startsWith("R10")), x.toString());
        // a statement with no stated context is not proof of another population: allowed (the clause checks still apply)
        texts.put("m3", "Dr. Cedar explained the checkup clearly.");
        assertTrue(violations(request("Is Dr. Cedar recommended for adults?", texts),
                d("P1 said Dr. Cedar explained the checkup clearly [m3].", "m3")).stream().noneMatch(s -> s.startsWith("R10")));
    }

    @Test void theKnowledgePathKeepsItsOriginalR5() {
        // no ContextCheck (knowledge answers): the original rule, both polarities required when the baseline conflicts
        Request legacy = new Request("Is Dr. Cedar recommended for babies?", "draft", List.of(ev("m1", BABIES), ev("m2", TEENS)), List.of(), true);
        assertTrue(violations(legacy, d("P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1")).stream()
                .anyMatch(s -> s.equals("R5 conflicting evidence: the opposing side is missing")));
    }

    @Test void citationSpeakerAndPolarityChecksAreUnchanged() {
        var texts = new LinkedHashMap<String, String>(); texts.put("m1", BABIES); texts.put("m2", TEENS);
        Request r = request("Is Dr. Cedar recommended for babies?", texts);
        assertFalse(v.validate(r, d("P1 said Dr. Cedar on Maple Street is excellent for babies [m9].", "m9")).ok(), "unknown citation");
        assertFalse(v.validate(r, d("P2 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1")).ok(), "invented speaker");
        assertFalse(v.validate(r, d("P1 said Dr. Cedar is not excellent for babies [m1].", "m1")).ok(), "negation unsupported");
        assertFalse(v.validate(r, d("P1 said Dr. Cedar is excellent for babies and adults [m1].", "m1")).ok(), "added content");
    }

    // ---------------- G1 pieces that are pure logic ----------------

    @Test void fusionRecordsEveryContributingChannelAndSemanticHitsAreRelevantWithoutSharedWords() {
        Retriever kw = (s, q, k) -> List.of(new Hit("a", 2, "vespa:bm25", false), new Hit("b", 1, "vespa:bm25", false));
        Retriever dense = (s, q, k) -> List.of(new Hit("b", 0.8, "vespa:dense", false), new Hit("c", 0.7, "vespa:dense", false));
        Retriever graph = (s, q, k) -> List.of(new Hit("d", 0.5, "graph:EXPLICIT_REPLY", false), new Hit("c", 0.4, "graph:EXPLICIT_REPLY", false));
        var fused = new HybridRetriever(List.of(kw, dense, graph), List.of(1.0, 0.5, 0.5), 60, 3, true).retrieve(new app.dita.core.model.GroupScope("g1"), "q", 10);
        Map<String, String> via = new HashMap<>(); fused.forEach(h -> via.put(h.messageId(), h.via()));
        assertEquals("vespa:bm25", via.get("a"));
        assertEquals("vespa:bm25+vespa:dense", via.get("b"));
        assertEquals("vespa:dense+graph:EXPLICIT_REPLY", via.get("c"), "direct channels first, link channels last");
        assertEquals("graph:EXPLICIT_REPLY", via.get("d"));
        assertTrue(ExtractiveAnswerComposer.semantic(new Hit("c", 1, via.get("c"), false)));
        assertFalse(ExtractiveAnswerComposer.linked(new Hit("c", 1, via.get("c"), false)), "found directly by a channel → not 'linked only'");
        assertTrue(ExtractiveAnswerComposer.linked(new Hit("d", 1, via.get("d"), false)));
        // default (recordChannels=false) behaviour is unchanged: first-seen via
        var old = new HybridRetriever(List.of(kw, dense), List.of(1.0, 0.5), 60, 3).retrieve(new app.dita.core.model.GroupScope("g1"), "q", 10);
        assertTrue(old.stream().noneMatch(h -> h.via().contains("+")));
    }

    @Test void theComposerKeepsASemanticHitThatSharesNoWordWithTheQuestion() {
        var store = new app.dita.core.store.InMemoryMessageStore();
        var g = new app.dita.core.model.GroupScope("g1");
        store.append(g, new app.dita.core.model.Message("g1m1", "g1", "u1", Instant.parse("2026-11-03T10:00:00Z"), null,
                BABIES, app.dita.core.model.SourceKind.SYNTHETIC, false));
        String paraphrase = "Which pediatrician do local parents trust most?";
        Retriever onlyDense = (s, q, k) -> List.of(new Hit("g1m1", 0.71, "vespa:dense", false));
        Retriever onlyKeyword = (s, q, k) -> List.of(new Hit("g1m1", 1.0, "vespa:bm25", false));
        assertTrue(new ExtractiveAnswerComposer(onlyDense, store, 300, m -> List.of(m.text())).answer(g, paraphrase, 8).found());
        assertFalse(new ExtractiveAnswerComposer(onlyKeyword, store, 300, m -> List.of(m.text())).answer(g, paraphrase, 8).found(),
                "a keyword hit without a shared word stays irrelevant (DEC-035 unchanged)");
    }
}
