package app.dita.app;

import app.dita.app.SynthesisTrialHarness.Fixture;
import app.dita.core.answer.synthesis.GroundedSynthesis.Outcome;
import app.dita.muse.MuseAnswerSynthesizer.Prompt;
import app.dita.muse.MuseTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 offline checks for the v2 synthesis instructions on the PRODUCTION path (IN_MEMORY, no network, no key).
 * <p><b>Every model reply below is a HAND-WRITTEN TEST FIXTURE, not Muse output.</b> These tests show that Dita's wiring
 * and validator ACCEPT a correct focused answer, an abstention and a two-sided conflict answer, and REJECT a one-sided
 * answer under conflict. They say nothing about whether Muse follows the v2 instructions; only an approved live trial can.
 * Production retrieval/selection is unchanged and is NOT asserted to be focused: the baseline is recorded as it is.
 */
class SynthesisPromptV2OfflineTest {
    /** These offline tests make many fixture-served calls; give later tests in this JVM their full per-process budget back. */
    @org.junit.jupiter.api.AfterEach void restoreCallBudget() { app.dita.muse.MuseCallCapTestAccess.reset(); }

    static final Map<String, String> ENV = Map.of("MODEL_API_KEY", "synthetic-no-live-key");

    static MuseTransport.Reply reply(boolean abstain, String answer, String... ids) throws Exception {
        String d = SynthesisTrialHarness.JSON.writeValueAsString(Map.of("abstain", abstain, "answer", answer, "citations", List.of(ids)));
        return new MuseTransport.Reply(200, SynthesisTrialHarness.completion(d), Map.of());
    }

    /** Runs each question once with its fixture reply; returns the runs in order. */
    static List<SynthesisTrialHarness.QuestionRun> run(Fixture fx, Prompt prompt, Map<String, MuseTransport.Reply> replies, List<String> order) throws Exception {
        Deque<String> expected = new ArrayDeque<>(order);
        try (var h = SynthesisTrialHarness.start("IN_MEMORY", 1000, ENV::get, prompt, fx, (qid, body, url, headers, timeout) -> {
            assertEquals(expected.peekFirst(), qid);
            return Objects.requireNonNull(replies.get(qid), "no fixture reply for " + qid);
        })) {
            List<SynthesisTrialHarness.QuestionRun> runs = new ArrayList<>();
            for (String q : order) {
                var r = h.ask(q);
                expected.removeFirst();
                runs.add(r);
                assertEquals(1, r.requests().size(), q + ": one synthesis request");
                String body = r.requests().get(0);
                assertEquals(prompt.system, SynthesisTrialHarness.systemPrompt(body).split("\nThe JSON object MUST conform")[0], "instructions of the configured version");
                JsonNode u = SynthesisTrialHarness.userPayload(body);
                assertEquals(r.question(), u.path("question").asText());
                Set<String> sent = new TreeSet<>();
                u.path("evidence").forEach(e -> sent.add(e.path("id").asText()));
                assertEquals(SynthesisTrialHarness.sources(r.baseline()), sent, "context = exactly the evidence Dita selected");
                h.conversationInRequest(body).forEach((id, in) -> {
                    boolean extracted = fx.messages().stream().anyMatch(m -> m.id().equals(id) && m.extract());
                    if (!extracted) assertFalse(in, "unextracted message " + id + " must not be sent");
                });
                for (var c : r.answer().citations()) assertTrue(h.store().find(h.group, c.messageId()).isPresent(), "citation belongs to this group");
                System.out.println("[TASK-054 v2 offline] " + fx.name() + "/" + q + " outcome=" + r.synthesis().get(0).outcome()
                        + " baseline=" + SynthesisTrialHarness.sources(r.baseline()) + " conflicting=" + r.baseline().conflicting()
                        + " final=" + SynthesisTrialHarness.sources(r.answer()) + " quality=" + h.quality(q, r.answer()).get("pass")
                        + " violations=" + r.synthesis().get(0).violations());
            }
            // quality is computed inside the context (needs the fixture); attach via a side list
            QUALITY.clear();
            for (var r : runs) QUALITY.put(r.qid(), h.quality(r.qid(), r.answer()));
            return runs;
        }
    }
    static final Map<String, Map<String, Object>> QUALITY = new LinkedHashMap<>();

    static void assertFocusedSynthesis(SynthesisTrialHarness.QuestionRun r) {
        assertEquals(Outcome.SYNTHESIZED, r.synthesis().get(0).outcome(), r.qid() + " " + r.synthesis().get(0).violations());
        assertTrue(r.synthesized());
        assertEquals(Boolean.TRUE, QUALITY.get(r.qid()).get("pass"), r.qid() + " " + QUALITY.get(r.qid()));
    }

    static void assertAbstained(SynthesisTrialHarness.QuestionRun r) {
        assertEquals(Outcome.ABSTAINED, r.synthesis().get(0).outcome());
        assertFalse(r.answer().found());
        assertTrue(r.answer().citations().isEmpty(), "no evidence about another population is substituted");
        assertEquals(Boolean.TRUE, QUALITY.get(r.qid()).get("pass"));
    }

    @Test void cedarFocusedGeneralAndUnsupportedPopulation() throws Exception {
        assumeTrue(SynthesisTrialHarness.extractionArtifactsPresent(), "saved Cedar extraction artifacts absent: NOT EXECUTED");
        Map<String, MuseTransport.Reply> replies = Map.of(   // HAND-WRITTEN FIXTURES, not Muse output
                "q1", reply(false, "P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1"),
                "q2", reply(false, "P1 said Dr. Cedar is not recommended for teenagers [m2].", "m2"),
                "q3", reply(false, "For babies, P1 said Dr. Cedar on Maple Street is excellent [m1]. For teenagers, P1 said Dr. Cedar is not recommended [m2].", "m1", "m2"),
                "q4", reply(true, ""));
        var runs = run(SynthesisTrialHarness.cedar(), Prompt.V2, replies, List.of("q1", "q2", "q3", "q4"));
        assertFocusedSynthesis(runs.get(0));
        assertEquals(Set.of("m1"), SynthesisTrialHarness.sources(runs.get(0).answer()));
        assertFocusedSynthesis(runs.get(1));
        assertEquals(Set.of("m2"), SynthesisTrialHarness.sources(runs.get(1).answer()));
        assertFocusedSynthesis(runs.get(2));
        assertFalse(runs.get(2).answer().conflicting());
        assertAbstained(runs.get(3));
        // Same evidence as v1: the request data did not change, only the instructions.
        for (var r : runs) assertEquals(SynthesisTrialHarness.sources(r.baseline()), Set.of("m1", "m2"),
                "production selection unchanged (both statements selected); recorded, not fixed here");
    }

    @Test void v2RequestDataIsByteIdenticalToTheSavedV1RequestExceptInstructions() throws Exception {
        assumeTrue(SynthesisTrialHarness.extractionArtifactsPresent(), "saved Cedar extraction artifacts absent: NOT EXECUTED");
        Path v1 = SynthesisTrialHarness.ROOT.resolve("build/controlled-synthesis-cedar/inmemory/q1/synthesis-request.json");
        assumeTrue(Files.isRegularFile(v1), "saved v1 in-memory q1 request absent: NOT EXECUTED");
        String saved = Files.readString(v1);
        List<String> bodies = new ArrayList<>();
        for (Prompt p : Prompt.values()) {
            try (var h = SynthesisTrialHarness.start("IN_MEMORY", 1000, ENV::get, p, SynthesisTrialHarness.cedar(),
                    (qid, body, url, headers, timeout) -> { bodies.add(body); return new MuseTransport.Reply(599, "{}", Map.of()); })) {
                assertEquals(Outcome.FALLBACK_ERROR, h.ask("q1").synthesis().get(0).outcome());
            }
        }
        assertEquals(saved, bodies.get(0), "V1 still reproduces the saved (paid-call) v1 request byte for byte");
        JsonNode a = SynthesisTrialHarness.JSON.readTree(bodies.get(0)), b = SynthesisTrialHarness.JSON.readTree(bodies.get(1));
        assertEquals(a.path("messages").get(1), b.path("messages").get(1), "same question, evidence, draft, replaced, conflicting");
        assertNotEquals(a.path("messages").get(0), b.path("messages").get(0), "instructions differ");
        for (String k : List.of("model", "temperature", "max_completion_tokens", "reasoning_effort")) assertEquals(a.get(k), b.get(k), k);
        assertEquals(a.size(), b.size());
    }

    @Test void differentSubjectFocusedGeneralAndUnsupportedPopulation() throws Exception {
        Map<String, MuseTransport.Reply> replies = Map.of(   // HAND-WRITTEN FIXTURES, not Muse output
                "p1", reply(false, "P1 said Coach Willow is great for beginners [w1].", "w1"),
                "p2", reply(false, "P1 said Coach Willow is not suitable for competitive swimmers [w2].", "w2"),
                "p3", reply(false, "P1 said Coach Willow is great for beginners [w1]. P1 also said Coach Willow is not suitable for competitive swimmers [w2].", "w1", "w2"),
                "p4", reply(true, ""));
        var runs = run(SynthesisTrialHarness.willow(false), Prompt.V2, replies, List.of("p1", "p2", "p3", "p4"));
        assertFocusedSynthesis(runs.get(0));
        assertFocusedSynthesis(runs.get(1));
        assertFocusedSynthesis(runs.get(2));
        assertAbstained(runs.get(3));
    }

    /**
     * Genuine opposition about the SAME population by two authors: the existing conflict policy (validator R5) must keep
     * both sides. A one-sided focused answer is rejected (deterministic baseline returned); a two-sided one is accepted.
     */
    @Test void sameContextOppositionKeepsBothSides() throws Exception {
        Fixture fx = SynthesisTrialHarness.willow(true);
        // Discover Dita's own conflict flag first (no Muse involved); the policy below only applies if Dita flags it.
        var oneSided = run(fx, Prompt.V2, Map.of("c1", reply(false, "P1 said Coach Willow is great for beginners [w1].", "w1")), List.of("c1")).get(0);
        assumeTrue(oneSided.baseline().conflicting(), "Dita did not flag w1/w3 as conflicting: conflict-policy case NOT EXERCISED (reported)");
        assertTrue(SynthesisTrialHarness.sources(oneSided.baseline()).containsAll(Set.of("w1", "w3")));
        assertEquals(Outcome.FALLBACK_INVALID, oneSided.synthesis().get(0).outcome());
        assertTrue(oneSided.synthesis().get(0).violations().stream().anyMatch(v -> v.startsWith("R5")), oneSided.synthesis().get(0).violations().toString());
        assertEquals(oneSided.baseline().text(), oneSided.answer().text(), "baseline (both sides) returned");

        var twoSided = run(fx, Prompt.V2, Map.of("c1", reply(false,
                "P1 said Coach Willow is great for beginners [w1]. P2 said Coach Willow is not good for beginners [w3].", "w1", "w3")), List.of("c1")).get(0);
        assertEquals(Outcome.SYNTHESIZED, twoSided.synthesis().get(0).outcome(), twoSided.synthesis().get(0).violations().toString());
        assertTrue(twoSided.answer().conflicting());
        assertEquals(Set.of("w1", "w3"), SynthesisTrialHarness.sources(twoSided.answer()));
    }

    /**
     * KNOWN LIMIT (Natasha TASK-053 F5, unchanged): the validator does not check that a cited statement's qualifier is
     * kept or that it matches the requested population. Both drafts below are accepted. The v2 instructions ask Muse to
     * avoid this; nothing in Dita enforces it. Characterised here so a future validator change is visible.
     */
    @Test void knownLimitWrongContextOrDroppedQualifierIsNotDetected() throws Exception {
        Map<String, MuseTransport.Reply> replies = Map.of(   // HAND-WRITTEN FIXTURES, deliberately wrong
                "p2", reply(false, "P1 said Coach Willow is great [w1].", "w1"),          // wrong population, qualifier dropped
                "p4", reply(false, "P1 said Coach Willow is great for beginners [w1].", "w1")); // extrapolation instead of abstaining
        var runs = run(SynthesisTrialHarness.willow(false), Prompt.V2, replies, List.of("p2", "p4"));
        assertEquals(Outcome.SYNTHESIZED, runs.get(0).synthesis().get(0).outcome());
        assertEquals(Boolean.FALSE, QUALITY.get("p2").get("pass"), "the quality check (not the validator) catches it");
        assertEquals(Outcome.SYNTHESIZED, runs.get(1).synthesis().get(0).outcome());
        assertEquals(Boolean.FALSE, QUALITY.get("p4").get("pass"));
    }
}
