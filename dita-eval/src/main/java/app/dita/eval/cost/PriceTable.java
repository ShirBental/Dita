package app.dita.eval.cost;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Per-million-token prices. Output includes reasoning tokens. {@code cachedInPerMillion} is null when the cached-input
 * price is unknown; cached tokens are then charged at the full input price (an upper bound), never at zero.
 * {@code source} says where the prices came from; the default is user-supplied and unverified against Meta's pricing.
 */
public record PriceTable(BigDecimal inPerMillion, BigDecimal outPerMillion, BigDecimal cachedInPerMillion, String source) {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public static final PriceTable DEFAULT = new PriceTable(new BigDecimal("1.25"), new BigDecimal("4.25"), null,
            "user-supplied, unverified");

    public PriceTable {
        Objects.requireNonNull(inPerMillion, "inPerMillion");
        Objects.requireNonNull(outPerMillion, "outPerMillion");
        Objects.requireNonNull(source, "source");
        if (inPerMillion.signum() < 0 || outPerMillion.signum() < 0) throw new IllegalArgumentException("prices must be >= 0");
        if (cachedInPerMillion != null && cachedInPerMillion.signum() < 0) throw new IllegalArgumentException("cached price must be >= 0");
        if (source.isBlank()) throw new IllegalArgumentException("price source must be stated");
    }

    public boolean cachedPriceKnown() { return cachedInPerMillion != null; }

    /** Worst case before sending: every input token plus the full {@code max_completion_tokens} (N-45/N-56). */
    public BigDecimal worstCaseUsd(long inputTokens, int maxCompletionTokens) {
        if (inputTokens < 0) throw new IllegalArgumentException("input tokens must be >= 0");
        if (maxCompletionTokens <= 0) throw new IllegalArgumentException("max_completion_tokens must be set and > 0 (unbounded output cannot be budgeted)");
        return inPerMillion.multiply(BigDecimal.valueOf(inputTokens))
                .add(outPerMillion.multiply(BigDecimal.valueOf(maxCompletionTokens)))
                .divide(MILLION, MathContext.DECIMAL64);
    }

    /**
     * Actual cost from usage. {@code promptTokens} includes {@code cachedTokens} (OpenAI-compatible convention). With an
     * unknown cached price, cached tokens are charged at P_in.
     */
    public BigDecimal actualUsd(long promptTokens, Long cachedTokens, long completionTokens) {
        if (promptTokens < 0 || completionTokens < 0) throw new IllegalArgumentException("token counts must be >= 0");
        long cached = cachedTokens == null ? 0 : cachedTokens;
        if (cached < 0 || cached > promptTokens) throw new IllegalArgumentException("cached tokens must be within prompt tokens");
        BigDecimal cachedRate = cachedInPerMillion == null ? inPerMillion : cachedInPerMillion;
        return inPerMillion.multiply(BigDecimal.valueOf(promptTokens - cached))
                .add(cachedRate.multiply(BigDecimal.valueOf(cached)))
                .add(outPerMillion.multiply(BigDecimal.valueOf(completionTokens)))
                .divide(MILLION, MathContext.DECIMAL64);
    }

    public BigDecimal actualUsd(UsageRecord u) {
        return actualUsd(u.promptTokens(), u.cachedTokens(), u.completionTokens());
    }

    /** Price per single token as a double (for the pure double-valued calculator). */
    public double inPerToken() { return inPerMillion.doubleValue() / 1e6; }
    public double outPerToken() { return outPerMillion.doubleValue() / 1e6; }
}
