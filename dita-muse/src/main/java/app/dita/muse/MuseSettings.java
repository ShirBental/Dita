package app.dita.muse;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Muse (Meta Model API) settings. Values verified by the user against the official docs
 * (https://dev.meta.ai/docs/cookbook/quickstart-chat-completions, 2026-10-28): endpoint
 * https://api.meta.ai/v1/chat/completions, model muse-spark-1.3, Bearer auth, OpenAI-compatible chat completions,
 * response in choices[0].message.content. The key is read from an environment variable at call time and is only
 * ever sent to {@link #DOCUMENTED_HOST} (N-38).
 * Retries are 0 by design (DEC-003): a timed-out request may already be billed, so it is never retried.
 */
public record MuseSettings(URI chatCompletionsUrl, String model, String apiKeyEnvVar, Duration timeout,
                           int maxOutputTokens, int maxInputChars, int maxCallsPerProcess, ResponseFormat responseFormat,
                           ReasoningEffort reasoningEffort) {
    /** How structured output is requested. Which modes the API supports is UNVERIFIED until the docs are checked. */
    public enum ResponseFormat { JSON_SCHEMA, JSON_OBJECT, PROMPT_ONLY }

    /**
     * Top-level {@code reasoning_effort} (user-verified docs: dev.meta.ai/docs/reasoning, /docs/protocols/chat-completions).
     * The default is model-dependent, so Dita ALWAYS sends it explicitly. "none" is unsupported by Muse Spark and is
     * deliberately absent. Reasoning tokens count toward max_completion_tokens and are billed as output tokens.
     * Meta recommends minimal or low for simple tasks such as structured extraction.
     */
    public enum ReasoningEffort {
        MINIMAL, LOW, MEDIUM, HIGH, XHIGH;
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** Convenience constructor: reasoning effort LOW (Meta's recommendation for extraction). */
    public MuseSettings(URI chatCompletionsUrl, String model, String apiKeyEnvVar, Duration timeout, int maxOutputTokens,
                        int maxInputChars, int maxCallsPerProcess, ResponseFormat responseFormat) {
        this(chatCompletionsUrl, model, apiKeyEnvVar, timeout, maxOutputTokens, maxInputChars, maxCallsPerProcess, responseFormat, ReasoningEffort.LOW);
    }

    /** The only host the API key may be sent to (from the user-verified docs). */
    public static final String DOCUMENTED_HOST = "api.meta.ai";
    public static final String DOCUMENTED_URL = "https://api.meta.ai/v1/chat/completions";

    public MuseSettings {
        Objects.requireNonNull(chatCompletionsUrl, "chatCompletionsUrl");
        if (!"https".equals(chatCompletionsUrl.getScheme())) throw new IllegalArgumentException("Muse endpoint must be https");
        if (chatCompletionsUrl.getUserInfo() != null) throw new IllegalArgumentException("Muse endpoint must not embed credentials");
        if (!DOCUMENTED_HOST.equals(chatCompletionsUrl.getHost()) || chatCompletionsUrl.getPort() != -1)
            throw new IllegalArgumentException("Muse endpoint host must be " + DOCUMENTED_HOST + " (the key is never sent elsewhere)");
        if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}")) throw new IllegalArgumentException("invalid Muse model id");
        if (apiKeyEnvVar == null || !apiKeyEnvVar.matches("[A-Z][A-Z0-9_]{2,63}")) throw new IllegalArgumentException("invalid key env var name");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("timeout must be in (0, 2m]");
        if (maxOutputTokens < 16 || maxOutputTokens > 32768) throw new IllegalArgumentException("maxOutputTokens in [16, 32768]");   // TASK-044: one 41-message batch needs > 8192 (reasoning model); cost cap stays explicit per run
        if (maxInputChars < 100 || maxInputChars > 200_000) throw new IllegalArgumentException("maxInputChars in [100, 200000]");
        if (maxCallsPerProcess < 1 || maxCallsPerProcess > 1000) throw new IllegalArgumentException("maxCallsPerProcess in [1, 1000]");
        Objects.requireNonNull(responseFormat);
        Objects.requireNonNull(reasoningEffort, "reasoningEffort must be set explicitly");
    }
}
