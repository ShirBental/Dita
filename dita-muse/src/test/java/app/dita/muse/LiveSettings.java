package app.dita.muse;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * The ONE place the live extraction experiment's settings are defined, shared by the offline request preview and the
 * live test so they cannot drift. Experiment (user-approved design, 2026-10-28): reasoning_effort=low, PROMPT_ONLY
 * (isolates the effect of reasoning effort), max_completion_tokens=4096, unchanged 10-message fixture and schema.
 */
final class LiveSettings {
    static final int EXTRACTION_MAX_COMPLETION_TOKENS = 4096;
    static final MuseSettings.ReasoningEffort EXTRACTION_EFFORT = MuseSettings.ReasoningEffort.LOW;
    static final MuseSettings.ResponseFormat EXTRACTION_FORMAT = MuseSettings.ResponseFormat.PROMPT_ONLY;

    static MuseSettings extraction(Function<String, String> env) {
        return new MuseSettings(URI.create(MuseSettings.DOCUMENTED_URL), env.apply("DITA_MUSE_MODEL") == null ? "muse-spark-1.3" : env.apply("DITA_MUSE_MODEL"),
                "MODEL_API_KEY", Duration.ofSeconds(60), EXTRACTION_MAX_COMPLETION_TOKENS, 20_000, 1, EXTRACTION_FORMAT, EXTRACTION_EFFORT);
    }

    /** Pre-send guard: refuses to transmit unless the body is exactly the approved experiment. */
    static MuseTransport guarded(MuseTransport delegate, Map<String, Object> report) {
        return (url, headers, body, timeout) -> {
            var b = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            boolean ok = "low".equals(b.path("reasoning_effort").asText(null))
                    && b.path("max_completion_tokens").asInt(-1) == EXTRACTION_MAX_COMPLETION_TOKENS
                    && b.path("response_format").isMissingNode()
                    && b.path("max_tokens").isMissingNode()
                    && "muse-spark-1.3".equals(b.path("model").asText(null))
                    && MuseSettings.DOCUMENTED_URL.equals(url.toString());
            report.put("request_check", ok ? "PASS: reasoning_effort=low, max_completion_tokens=4096, no response_format, documented URL/model"
                    : "FAIL: request differs from the approved experiment; NOT SENT");
            report.put("request_bytes", body.length());
            if (!ok) throw new MuseException(MuseException.Kind.CONFIG, 0, "request differs from the approved experiment; not sent");
            return delegate.post(url, headers, body, timeout);
        };
    }

    private LiveSettings() {}
}
