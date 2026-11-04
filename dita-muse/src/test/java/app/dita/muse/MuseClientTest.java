package app.dita.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline regression tests with SYNTHETIC HTTP responses through a fake transport. Synthetic key; no network. */
class MuseClientTest {
    static final String KEY = "synthetic-test-key-0123456789";
    static final MuseSettings S = new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), "muse-spark-1.3",
            "MODEL_API_KEY", Duration.ofSeconds(5), 512, 20_000, 3, MuseSettings.ResponseFormat.PROMPT_ONLY);
    static final MuseSettings SCHEMA = new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), "muse-spark-1.3",
            "MODEL_API_KEY", Duration.ofSeconds(5), 512, 20_000, 3, MuseSettings.ResponseFormat.JSON_SCHEMA);

    record Sent(URI url, Map<String, String> headers, String body) {}

    static final class Fake implements MuseTransport {
        final List<Sent> sent = new ArrayList<>();
        int status = 200; String body; Exception fail;
        Fake(String body) { this.body = body; }
        Fake(int status, String body) { this.status = status; this.body = body; }
        public Reply post(URI url, Map<String, String> h, String b, Duration t) throws Exception {
            sent.add(new Sent(url, h, b));
            if (fail != null) throw fail;
            return new Reply(status, body, Map.of("x-request-id", "req-1"));
        }
    }

    static String openAi(String content) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("model", "muse-spark-1.3",
                "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 123, "completion_tokens", 45)));
    }

    /** The exact structure observed live on 2026-10-28 (run 2), with synthetic ids. */
    static final String LIVE_TRUNCATED = "{\"id\":\"chatcmpl-synthetic\",\"choices\":[{\"finish_reason\":\"length\",\"index\":0,"
            + "\"message\":{\"content\":null,\"refusal\":null,\"role\":\"assistant\"},\"logprobs\":null}],\"created\":1761691723,"
            + "\"model\":\"muse-spark-1.3\",\"object\":\"chat.completion\",\"usage\":{\"completion_tokens\":64,\"prompt_tokens\":25,"
            + "\"total_tokens\":89,\"completion_tokens_details\":{\"reasoning_tokens\":64},\"prompt_tokens_details\":{\"cached_tokens\":0}}}";

    static final String LIVE_OK = "{\"id\":\"chatcmpl-synthetic\",\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
            + "\"message\":{\"content\":\"OK\",\"refusal\":null,\"role\":\"assistant\"},\"logprobs\":null}],\"created\":1,"
            + "\"model\":\"muse-spark-1.3\",\"object\":\"chat.completion\",\"usage\":{\"completion_tokens\":90,\"prompt_tokens\":25,"
            + "\"total_tokens\":115,\"completion_tokens_details\":{\"reasoning_tokens\":88},\"prompt_tokens_details\":{\"cached_tokens\":0}}}";

    @BeforeEach void resetCap() { MuseClient.resetProcessCountForTests(); }

    MuseClient client(Fake f) { return new MuseClient(S, f, k -> k.equals("MODEL_API_KEY") ? KEY : null); }

    // ---------- request ----------

    @Test void requestFollowsDocsAndUsesMaxCompletionTokens() throws Exception {
        Fake f = new Fake(LIVE_OK);
        client(f).complete("sys", "user", "x", MuseKnowledgeExtractor.schema());
        Sent s = f.sent.get(0);
        assertEquals(URI.create("https://api.meta.ai/v1/chat/completions"), s.url());
        assertEquals("Bearer " + KEY, s.headers().get("Authorization"));
        assertFalse(s.body().contains(KEY));
        var body = new ObjectMapper().readTree(s.body());
        assertEquals("muse-spark-1.3", body.path("model").asText());
        assertEquals(512, body.path("max_completion_tokens").asInt(), "documented token-limit parameter");
        assertTrue(body.path("max_tokens").isMissingNode(), "max_tokens is no longer sent");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertTrue(body.path("response_format").isMissingNode(), "PROMPT_ONLY sends no response_format");
        assertTrue(body.path("messages").get(0).path("content").asText().contains("MUST conform to this JSON Schema"));
    }

    @Test void reasoningEffortIsAlwaysSentExplicitly() throws Exception {   // regression: runs 1-4 omitted it
        Fake f = new Fake(LIVE_OK);
        client(f).complete("s", "u", "x", MuseKnowledgeExtractor.schema());
        client(f).ping();
        for (Sent s : f.sent) {
            var body = new ObjectMapper().readTree(s.body());
            assertEquals("low", body.path("reasoning_effort").asText(), "top-level reasoning_effort, default LOW");
            assertTrue(body.path("reasoning").isMissingNode(), "no undocumented nested reasoning object");
        }
        for (MuseSettings.ReasoningEffort e : MuseSettings.ReasoningEffort.values()) {
            MuseClient.resetProcessCountForTests();
            Fake g = new Fake(LIVE_OK);
            new MuseClient(new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), "muse-spark-1.3", "MODEL_API_KEY",
                    Duration.ofSeconds(5), 512, 20_000, 3, MuseSettings.ResponseFormat.PROMPT_ONLY, e), g, k -> KEY).ping();
            assertEquals(e.wire(), new ObjectMapper().readTree(g.sent.get(0).body()).path("reasoning_effort").asText());
        }
        assertEquals(java.util.List.of("minimal", "low", "medium", "high", "xhigh"),
                java.util.Arrays.stream(MuseSettings.ReasoningEffort.values()).map(MuseSettings.ReasoningEffort::wire).toList());
        assertThrows(IllegalArgumentException.class, () -> MuseSettings.ReasoningEffort.valueOf("NONE"), "\"none\" is unsupported by Muse Spark");
        assertThrows(NullPointerException.class, () -> new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), "muse-spark-1.3", "MODEL_API_KEY",
                Duration.ofSeconds(5), 512, 20_000, 3, MuseSettings.ResponseFormat.PROMPT_ONLY, null));
    }

    @Test void connectivityPingUses128CompletionTokens() throws Exception {
        Fake f = new Fake(LIVE_OK);
        client(f).ping();
        var body = new ObjectMapper().readTree(f.sent.get(0).body());
        assertEquals(128, body.path("max_completion_tokens").asInt());
        assertTrue(body.path("response_format").isMissingNode());
    }

    @Test void jsonSchemaModeSendsResponseFormat() throws Exception {
        Fake f = new Fake(LIVE_OK);
        new MuseClient(SCHEMA, f, k -> KEY).complete("s", "u", "x", MuseKnowledgeExtractor.schema());
        assertEquals("json_schema", new ObjectMapper().readTree(f.sent.get(0).body()).path("response_format").path("type").asText());
    }

    // ---------- success ----------

    @Test void successPreservesStatusFinishReasonUsageAndShape() {
        MuseClient.Result r = client(new Fake(200, LIVE_OK)).ping();
        assertEquals("OK", r.content());
        assertEquals(200, r.httpStatus());
        assertEquals("stop", r.finishReason());
        assertEquals(88, r.reasoningTokens());
        assertEquals(Map.of("completion_tokens", 90, "prompt_tokens", 25, "total_tokens", 115,
                "completion_tokens_details.reasoning_tokens", 88, "prompt_tokens_details.cached_tokens", 0), r.usage());
        assertTrue(r.shape().contains("message.content=STRING(len 2)"));
        assertFalse(r.shape().contains("OK\""), "shape never contains response text");
    }

    // ---------- 2xx but unusable ----------

    @Test void liveTruncationIsEmptyContentWithRealStatusNotZero() {   // regression: run 1/2 reported http_status 0
        MuseException e = assertThrows(MuseException.class, () -> client(new Fake(200, LIVE_TRUNCATED)).ping());
        assertEquals(MuseException.Kind.EMPTY_CONTENT, e.kind());
        assertEquals(200, e.status());
        assertEquals("length", e.details().finishReason());
        assertEquals(64, e.details().usage().get("completion_tokens_details.reasoning_tokens"));
        assertTrue(e.details().shape().contains("message.content=NULL"));
        assertTrue(e.getMessage().contains("completion budget exhausted"));
    }

    @Test void emptyContentWithOtherFinishReasonAndRefusalAreDescribedWithoutText() {
        String refusal = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\",\"refusal\":\"I won't do that, private details\"}}]}";
        MuseException e = assertThrows(MuseException.class, () -> client(new Fake(200, refusal)).ping());
        assertEquals(MuseException.Kind.EMPTY_CONTENT, e.kind());
        assertTrue(e.getMessage().contains("message.refusal=STRING(len 32)"), e.getMessage());
        assertFalse(e.getMessage().contains("private details"), "no response text in diagnostics");
    }

    @Test void malformedSuccessBodiesAreResponseFormatWithStatus() {
        for (String b : new String[]{"not json at all", "[1,2]", "{\"object\":\"chat.completion\"}", "{\"choices\":[]}"}) {
            MuseException e = assertThrows(MuseException.class, () -> client(new Fake(200, b)).ping(), b);
            assertEquals(MuseException.Kind.RESPONSE_FORMAT, e.kind(), b);
            assertEquals(200, e.status(), b);
            MuseClient.resetProcessCountForTests();
        }
    }

    @Test void undocumentedResponseShapesAreNotAccepted() {
        String llama = "{\"completion_message\":{\"content\":{\"type\":\"text\",\"text\":\"OK\"}}}";
        assertEquals(MuseException.Kind.RESPONSE_FORMAT, assertThrows(MuseException.class, () -> client(new Fake(200, llama)).ping()).kind());
    }

    // ---------- API errors (non-2xx): real status + parsed envelope, redacted ----------

    @Test void apiErrorEnvelopesArePreservedAndRedacted() {
        record Case(int status, MuseException.Kind kind) {}
        for (Case c : List.of(new Case(400, MuseException.Kind.CLIENT), new Case(401, MuseException.Kind.AUTH), new Case(403, MuseException.Kind.AUTH),
                new Case(404, MuseException.Kind.CLIENT), new Case(429, MuseException.Kind.RATE_LIMIT), new Case(500, MuseException.Kind.SERVER),
                new Case(503, MuseException.Kind.SERVER))) {
            MuseClient.resetProcessCountForTests();
            String env = "{\"error\":{\"message\":\"Invalid key " + KEY + " for Bearer " + KEY + "\",\"type\":\"invalid_request_error\","
                    + "\"code\":\"invalid_api_key\",\"param\":null}}";
            Fake f = new Fake(c.status(), env);
            MuseException e = assertThrows(MuseException.class, () -> client(f).ping());
            assertEquals(c.kind(), e.kind(), "status " + c.status());
            assertEquals(c.status(), e.status(), "real HTTP status preserved");
            assertEquals("invalid_request_error", e.details().apiErrorType());
            assertEquals("invalid_api_key", e.details().apiErrorCode());
            assertFalse(e.getMessage().contains(KEY), e.getMessage());
            assertTrue(e.getMessage().contains("<redacted>"));
            assertEquals(1, f.sent.size(), "never retried");
        }
    }

    @Test void apiErrorWithoutEnvelopeOrNonJsonIsDescribedByLengthOnly() {
        MuseException e = assertThrows(MuseException.class, () -> client(new Fake(502, "<html>upstream secret page</html>")).ping());
        assertEquals(MuseException.Kind.SERVER, e.kind());
        assertEquals(502, e.status());
        assertFalse(e.getMessage().contains("secret"));
        MuseClient.resetProcessCountForTests();
        MuseException n = assertThrows(MuseException.class, () -> client(new Fake(400, "{\"detail\":\"something\"}")).ping());
        assertTrue(n.getMessage().contains("without an error envelope"));
        assertFalse(n.getMessage().contains("something"));
    }

    @Test void numericErrorCodesAreKept() {
        MuseException e = assertThrows(MuseException.class, () -> client(new Fake(429, "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit\",\"code\":429}}")).ping());
        assertEquals("429", e.details().apiErrorCode());
    }

    // ---------- transport ----------

    @Test void transportFailuresHaveNoStatusAndNoRetry() {
        Fake f = new Fake(LIVE_OK);
        f.fail = new java.net.ConnectException("connection refused to https://api.meta.ai with Bearer " + KEY);
        MuseException e = assertThrows(MuseException.class, () -> client(f).ping());
        assertEquals(MuseException.Kind.TRANSPORT, e.kind());
        assertEquals(0, e.status());
        assertFalse(e.getMessage().contains(KEY));
        assertEquals(1, f.sent.size());
        MuseClient.resetProcessCountForTests();
        Fake t = new Fake(LIVE_OK);
        t.fail = new MuseException(MuseException.Kind.TIMEOUT, 0, "request timed out (not retried: may already be billed)");
        assertEquals(MuseException.Kind.TIMEOUT, assertThrows(MuseException.class, () -> client(t).ping()).kind());
        assertEquals(1, t.sent.size());
    }

    // ---------- local guards ----------

    @Test void missingKeyBudgetAndInputLimitsFailBeforeAnyRequest() {
        Fake f = new Fake(LIVE_OK);
        assertEquals(MuseException.Kind.CONFIG, assertThrows(MuseException.class, () -> new MuseClient(S, f, k -> null).ping()).kind());
        assertEquals(MuseException.Kind.BUDGET, assertThrows(MuseException.class,
                () -> client(f).complete("s", "u".repeat(30_000), "x", MuseKnowledgeExtractor.schema())).kind());
        assertTrue(f.sent.isEmpty());
    }

    @Test void callCapIsPerProcessAcrossInstances() {
        Fake f = new Fake(LIVE_OK);
        client(f).ping(); client(f).ping(); client(f).ping();
        assertEquals(MuseException.Kind.BUDGET, assertThrows(MuseException.class, () -> client(f).ping()).kind());
        assertEquals(3, f.sent.size());
    }

    @Test void keyIsNeverSentToAnyHostButTheDocumentedOne() {
        for (String u : new String[]{"http://api.meta.ai/v1/chat/completions", "https://u:p@api.meta.ai/v1/chat/completions",
                "https://api.meta.ai.evil.example/v1/chat/completions", "https://api.meta.ia/v1/chat/completions", "https://api.meta.ai:8443/v1/chat/completions"}) {
            assertThrows(IllegalArgumentException.class, () -> new MuseSettings(URI.create(u), "muse-spark-1.3", "MODEL_API_KEY",
                    Duration.ofSeconds(5), 512, 20_000, 3, MuseSettings.ResponseFormat.PROMPT_ONLY), u);
        }
    }

    @Test void redactionRemovesKeyAndBearerTokensAndTruncates() {
        String r = MuseClient.redact("x".repeat(400) + KEY + " Bearer abc.def-123", KEY);
        assertFalse(r.contains(KEY));
        assertTrue(r.length() <= 301);
        assertEquals("Bearer <redacted>", MuseClient.redact("Bearer abc.def-123", KEY));
    }
}
