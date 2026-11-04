package app.dita.muse;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE API CONNECTIVITY only (no extraction, no group data): one request "Reply with the single word: OK",
 * max_completion_tokens=128 (run 2 with 64 was consumed entirely by reasoning: finish_reason=length, content=null), to the documented endpoint and model. Skipped unless DITA_MUSE_CONNECTIVITY_APPROVED=yes and
 * MODEL_API_KEY are set (scripts/muse-live.sh connectivity). Report: target/muse-connectivity-report.json.
 */
class MuseConnectivityLiveTest {
    @Test void documentedEndpointAnswers() throws Exception {
        assumeTrue(LiveReport.approved("DITA_MUSE_CONNECTIVITY_APPROVED"), "connectivity call not approved");
        MuseClient.resetProcessCountForTests();
        String model = System.getenv().getOrDefault("DITA_MUSE_MODEL", "muse-spark-1.3");
        // Budget chosen explicitly by the approver (shown in the prompt). Live runs: 64 → 64 reasoning; 128 → 125 reasoning.
        int budget = Integer.parseInt(System.getenv().getOrDefault("DITA_MUSE_CONNECTIVITY_TOKENS", "128"));
        assertTrue(budget >= 16 && budget <= 4096, "DITA_MUSE_CONNECTIVITY_TOKENS in [16, 4096]");
        MuseSettings.ReasoningEffort effort = LiveReport.effort();
        MuseSettings s = new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), model, "MODEL_API_KEY", Duration.ofSeconds(30),
                budget, 1_000, 1, MuseSettings.ResponseFormat.PROMPT_ONLY, effort);
        MuseClient client = new MuseClient(s, MuseTransport.http(), System::getenv);
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("test", "connectivity");
        rep.put("when", Instant.now().toString());
        rep.put("endpoint", MuseSettings.DOCUMENTED_URL);
        rep.put("model_requested", model);
        rep.put("data_sent", "fixed connectivity prompt only (no group messages)");
        rep.put("max_completion_tokens", budget);
        rep.put("reasoning_effort", effort.wire());
        MuseClient.Result r = null;
        try {
            r = client.ping(budget);
            rep.put("status", "OK");
            rep.put("http_status", r.httpStatus());
            rep.put("usage", r.usage());
            rep.put("response_shape", r.shape());
            rep.put("model_reported", r.model());
            rep.put("content_length", r.content().length());
            rep.put("content_is_OK", r.content().strip().replaceAll("[.!]$", "").equalsIgnoreCase("OK"));
            rep.put("latency_ms", r.latencyMs());
            LiveReport.tokens(rep, r.promptTokens(), r.completionTokens(), r.reasoningTokens());
            rep.put("finish_reason", r.finishReason());
            rep.put("request_id", r.requestId());
            rep.put("max_tokens_respected", r.completionTokens() == null ? null : r.completionTokens() <= budget);   // N-41
        } catch (MuseException e) {
            rep.put("status", "ERROR");
            // Two separate verdicts: the API answered correctly at protocol level vs a usable answer was produced.
            rep.put("protocol_verified", e.kind() == MuseException.Kind.EMPTY_CONTENT && e.status() == 200);
            rep.put("error_kind", e.kind().name());
            rep.put("http_status", e.status());
            rep.put("error", e.getMessage());
            rep.put("api_error_type", e.details().apiErrorType());
            rep.put("api_error_code", e.details().apiErrorCode());
            rep.put("finish_reason", e.details().finishReason());
            rep.put("usage", e.details().usage());
            rep.put("response_shape", e.details().shape());
            var u = e.details().usage();
            LiveReport.tokens(rep, u.get("prompt_tokens"), u.get("completion_tokens"), u.get("completion_tokens_details.reasoning_tokens"));
        } finally {
            rep.put("calls_made", client.callsMade());
            LiveReport.write("muse-connectivity-report.json", rep);
        }
        assertNotNull(r, "connectivity failed: see target/muse-connectivity-report.json");
        assertEquals(1, client.callsMade());
        assertFalse(r.content().isBlank());
    }
}
