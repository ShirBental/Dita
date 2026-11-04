package app.dita.app;

import app.dita.core.answer.synthesis.GroundedSynthesis.Outcome;
import app.dita.muse.MuseTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 offline verification of the PRODUCTION synthesis path (IN_MEMORY, no network, no API key). Knowledge comes
 * from the saved live extraction response; synthesis replies are HAND-AUTHORED here (not Muse output) to exercise
 * acceptance, validator rejection, transport failure and abstention. Asserts the contract of the request (the question
 * plus exactly the evidence Dita's answer selected; no unrelated stored message) and of each fallback. It does NOT
 * assert which evidence Dita selects for a population-specific question: that is measured, not fixed, here.
 */
class SynthesisTrialOfflineTest {
    /** These offline tests make many fixture-served calls; give later tests in this JVM their full per-process budget back. */
    @org.junit.jupiter.api.AfterEach void restoreCallBudget() { app.dita.muse.MuseCallCapTestAccess.reset(); }

    static String completion(String content) throws Exception {
        return SynthesisTrialHarness.JSON.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0)));
    }

    static String draft(boolean abstain, String answer, String... ids) throws Exception {
        return SynthesisTrialHarness.JSON.writeValueAsString(Map.of("abstain", abstain, "answer", answer, "citations", List.of(ids)));
    }

    @Test void productionSynthesisPathValidatesAndFallsBackOffline() throws Exception {
        assumeTrue(SynthesisTrialHarness.extractionArtifactsPresent(), "saved Cedar extraction artifacts absent: NOT EXECUTED");
        Deque<MuseTransport.Reply> script = new ArrayDeque<>(List.of(
                // 1 q1: a grounded, attributed draft citing only m1 → accepted
                new MuseTransport.Reply(200, completion(draft(false, "P1 said Dr. Cedar on Maple Street is excellent for babies [m1].", "m1")), Map.of()),
                // 2 q1: unsupported content → validator rejects → deterministic baseline
                new MuseTransport.Reply(200, completion(draft(false, "P1 said Dr. Cedar is the best pediatrician in town [m1].", "m1")), Map.of()),
                // 3 q2: HTTP 500 → error fallback
                new MuseTransport.Reply(500, "{\"error\":{\"type\":\"server_error\",\"message\":\"synthetic\"}}", Map.of()),
                // 4 q2: explicit abstention → honoured (not found)
                new MuseTransport.Reply(200, completion(draft(true, "")), Map.of()),
                // 5 q3: both contexts, one speaker, no disagreement wording → accepted
                new MuseTransport.Reply(200, completion(draft(false,
                        "P1 said Dr. Cedar on Maple Street is excellent for babies [m1]. P1 also said Dr. Cedar is not recommended for teenagers [m2].",
                        "m1", "m2")), Map.of())));
        try (var h = SynthesisTrialHarness.start("IN_MEMORY", 1000, n -> n.equals("MODEL_API_KEY") ? "synthetic-no-live-key" : null,
                (qid, body, url, headers, timeout) -> script.removeFirst())) {
            var runs = List.of(h.ask("q1"), h.ask("q1"), h.ask("q2"), h.ask("q2"), h.ask("q3"));
            assertTrue(script.isEmpty());
            List<Outcome> expected = List.of(Outcome.SYNTHESIZED, Outcome.FALLBACK_INVALID, Outcome.FALLBACK_ERROR, Outcome.ABSTAINED, Outcome.SYNTHESIZED);
            for (int i = 0; i < runs.size(); i++) {
                var r = runs.get(i);
                assertEquals(1, r.requests().size(), "one synthesis request per question");
                assertEquals(1, r.synthesis().size());
                assertEquals(expected.get(i), r.synthesis().get(0).outcome(), "run " + (i + 1) + " violations " + r.synthesis().get(0).violations());
                // Request contract: the question and exactly the baseline's evidence; nothing else from the conversation
                String body = r.requests().get(0);
                JsonNode u = SynthesisTrialHarness.userPayload(body);
                assertEquals(r.question(), u.path("question").asText());
                Set<String> sent = new TreeSet<>();
                u.path("evidence").forEach(e -> sent.add(e.path("id").asText()));
                assertEquals(SynthesisTrialHarness.sources(r.baseline()), sent, "context = the evidence Dita's answer selected");
                var inRequest = h.conversationInRequest(body);
                assertFalse(inRequest.get("m3") || inRequest.get("m4"), "unrelated stored messages must not be sent: " + inRequest);
                assertFalse(body.contains("synthetic1") || body.contains("synthetic2"), "author ids are pseudonymised");
                assertFalse(body.contains(h.prefix), "no group id in the request");
                assertFalse(body.contains("synthetic-no-live-key"), "no credential in the body");
            }
            // Accepted drafts are what Dita returns, with the baseline's own verified citations
            assertTrue(runs.get(0).synthesized());
            assertEquals(Set.of("m1"), SynthesisTrialHarness.sources(runs.get(0).answer()));
            assertTrue(runs.get(0).answer().text().startsWith("synthetic1 said Dr. Cedar"), runs.get(0).answer().text());
            assertTrue(runs.get(4).synthesized());
            assertEquals(Set.of("m1", "m2"), SynthesisTrialHarness.sources(runs.get(4).answer()));
            // Fallbacks return the deterministic answer unchanged and are never a synthesis success
            for (int i : new int[]{1, 2}) {
                assertFalse(runs.get(i).synthesized());
                assertEquals(runs.get(i).baseline().text(), runs.get(i).answer().text());
                assertEquals(SynthesisTrialHarness.sources(runs.get(i).baseline()), SynthesisTrialHarness.sources(runs.get(i).answer()));
            }
            assertTrue(runs.get(1).synthesis().get(0).violations().stream().anyMatch(v -> v.startsWith("R8")));
            assertFalse(runs.get(3).answer().found(), "abstention is not an answer");
            assertTrue(runs.get(3).answer().citations().isEmpty());
            // Citations of every returned answer belong to this group and are live messages
            for (var r : runs) for (var c : r.answer().citations()) assertTrue(h.store().find(h.group, c.messageId()).isPresent());
            // Evidence print for the report (synthetic data only)
            for (var r : runs) System.out.println("[TASK-054 offline] " + r.qid() + " outcome=" + r.synthesis().get(0).outcome()
                    + " baseline_sources=" + SynthesisTrialHarness.sources(r.baseline()) + " final_sources=" + SynthesisTrialHarness.sources(r.answer())
                    + " selection=" + r.selection().selectedIds() + " candidates=" + r.selection().candidateIds());
        }
    }
}
