package app.dita.eval.cost;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One model call's usage, NUMBERS ONLY (N-55). Format {@value #SCHEMA}; see {@link UsageJsonl} for the strict loader.
 * Every string field is constrained to an enum or a short identifier pattern, so free text (evidence, quotes, model
 * statements, prompts) cannot be smuggled through it. {@code request_id} is never stored: only its SHA-256 (N-60).
 */
public record UsageRecord(CallKind callKind, String arm, String runLabel, String model, String reasoningEffort,
                          int maxCompletionTokens, long promptTokens, long completionTokens, Long reasoningTokens,
                          Long cachedTokens, Long latencyMs, String finishReason, Integer httpStatus, Long requestBytes,
                          String requestSha256, String requestIdSha256, Provenance provenance) {
    public static final String SCHEMA = "dita-eval/usage-v1";

    public enum CallKind { ANSWER, EXTRACTION, CONNECTIVITY }

    /** Arm labels a usage record may carry (Stage 0 runner arms plus the cost-model arms). */
    public static final Set<String> ARMS = Set.of("A-full", "A-budget", "A-max", "B-weak control", "B", "C-today",
            "C-graph-off", "C-rep", "C-full", "O-gold", "B-weak no-window (diag)", "C-today +/-2 (diag)");
    public static final Set<String> FINISH_REASONS = Set.of("stop", "length", "content_filter", "tool_calls");
    public static final Set<String> EFFORTS = Set.of("minimal", "low", "medium", "high");
    static final Pattern RUN_LABEL = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    static final Pattern MODEL = Pattern.compile("[a-z0-9][a-z0-9.-]{0,47}");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public UsageRecord {
        Objects.requireNonNull(callKind, "call_kind");
        Objects.requireNonNull(runLabel, "run_label");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(provenance, "provenance");
        if (arm != null && !ARMS.contains(arm)) throw new IllegalArgumentException("arm not allowlisted");
        if (!RUN_LABEL.matcher(runLabel).matches()) throw new IllegalArgumentException("run_label must match " + RUN_LABEL);
        if (!MODEL.matcher(model).matches()) throw new IllegalArgumentException("model must match " + MODEL);
        if (reasoningEffort != null && !EFFORTS.contains(reasoningEffort)) throw new IllegalArgumentException("reasoning_effort not allowlisted");
        if (finishReason != null && !FINISH_REASONS.contains(finishReason)) throw new IllegalArgumentException("finish_reason not allowlisted");
        if (requestSha256 != null && !SHA256.matcher(requestSha256).matches()) throw new IllegalArgumentException("request_sha256 must be 64 lowercase hex");
        if (requestIdSha256 != null && !SHA256.matcher(requestIdSha256).matches()) throw new IllegalArgumentException("request_id_sha256 must be 64 lowercase hex");
        if (maxCompletionTokens <= 0) throw new IllegalArgumentException("max_completion_tokens must be > 0");
        if (promptTokens < 0 || completionTokens < 0) throw new IllegalArgumentException("token counts must be >= 0");
        nonNeg(reasoningTokens, "reasoning_tokens"); nonNeg(cachedTokens, "cached_tokens"); nonNeg(latencyMs, "latency_ms");
        nonNeg(requestBytes, "request_bytes");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) throw new IllegalArgumentException("http_status out of range");
        if (reasoningTokens != null && reasoningTokens > completionTokens) throw new IllegalArgumentException("reasoning_tokens > completion_tokens");
        if (cachedTokens != null && cachedTokens > promptTokens) throw new IllegalArgumentException("cached_tokens > prompt_tokens");
        if (completionTokens > maxCompletionTokens) throw new IllegalArgumentException("completion_tokens > max_completion_tokens");
    }

    private static void nonNeg(Long v, String f) { if (v != null && v < 0) throw new IllegalArgumentException(f + " must be >= 0"); }

    /** Visible output tokens, or null if reasoning is unknown. */
    public Long visibleTokens() { return reasoningTokens == null ? null : completionTokens - reasoningTokens; }

    public boolean truncated() { return "length".equals(finishReason); }
}
