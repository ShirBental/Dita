package app.dita.eval.guard;

import app.dita.eval.cost.PriceTable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;

/**
 * N-45 dollar budget guard. Every planned call is charged at its worst case BEFORE it is made:
 * {@code P_in * inputTokensUpperBound + P_out * max_completion_tokens} (output includes reasoning tokens). A call that
 * would push the committed total above the approved ceiling is refused. Reservations are never refunded, so the
 * committed total is an upper bound on spend. Not wired to any live client in Stage 0.
 * <p>The static helpers use the default user-supplied prices. An instance built with
 * {@link #BudgetGuard(BigDecimal, PriceTable)} charges reservations at that table's prices instead (TASK-039).
 */
public final class BudgetGuard {
    public static final BigDecimal P_IN_PER_MILLION = new BigDecimal("1.25");
    public static final BigDecimal P_OUT_PER_MILLION = new BigDecimal("4.25");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public record Reservation(int callNumber, long inputTokens, int maxCompletionTokens, BigDecimal worstCaseUsd, BigDecimal committedUsd) {}

    private final BigDecimal ceilingUsd;
    private final PriceTable prices;
    private BigDecimal committed = BigDecimal.ZERO;
    private int calls;

    public BudgetGuard(BigDecimal ceilingUsd) {
        this(ceilingUsd, null);
    }

    /** {@code prices == null} means the default static prices ({@link #P_IN_PER_MILLION}, {@link #P_OUT_PER_MILLION}). */
    public BudgetGuard(BigDecimal ceilingUsd, PriceTable prices) {
        if (ceilingUsd == null || ceilingUsd.signum() < 0) throw new IllegalArgumentException("ceiling must be >= 0");
        this.ceilingUsd = ceilingUsd;
        this.prices = prices;
    }

    /** Worst case at this guard's prices. */
    public BigDecimal worstCaseAtGuardPrices(long inputTokens, int maxCompletionTokens) {
        return prices == null ? worstCaseUsd(inputTokens, maxCompletionTokens) : prices.worstCaseUsd(inputTokens, maxCompletionTokens);
    }

    /** The price table this guard charges at, or null for the static defaults. */
    public PriceTable prices() { return prices; }

    /**
     * Upper bound on input tokens: one token per UTF-8 byte of the request body. Byte-level BPE never produces more
     * tokens than bytes, and the full body overcounts JSON framing, so this cannot undercount.
     */
    public static long inputTokenUpperBound(String requestBody) { return requestBody.getBytes(StandardCharsets.UTF_8).length; }

    public static BigDecimal worstCaseUsd(long inputTokens, int maxCompletionTokens) {
        if (inputTokens < 0) throw new IllegalArgumentException("input tokens must be >= 0");
        if (maxCompletionTokens <= 0) throw new IllegalArgumentException("max_completion_tokens must be set and > 0 (unbounded output cannot be budgeted)");
        return P_IN_PER_MILLION.multiply(BigDecimal.valueOf(inputTokens))
                .add(P_OUT_PER_MILLION.multiply(BigDecimal.valueOf(maxCompletionTokens)))
                .divide(MILLION, MathContext.DECIMAL64);
    }

    public synchronized Reservation reserve(long inputTokens, int maxCompletionTokens) {
        BigDecimal cost = worstCaseAtGuardPrices(inputTokens, maxCompletionTokens);
        BigDecimal next = committed.add(cost);
        if (next.compareTo(ceilingUsd) > 0) {
            throw new BudgetExceededException("refused call " + (calls + 1) + ": worst case $" + cost.toPlainString()
                    + " would raise committed $" + committed.toPlainString() + " above ceiling $" + ceilingUsd.toPlainString());
        }
        committed = next;
        calls++;
        return new Reservation(calls, inputTokens, maxCompletionTokens, cost, committed);
    }

    public synchronized Reservation reserveForBody(String requestBody, int maxCompletionTokens) {
        return reserve(inputTokenUpperBound(requestBody), maxCompletionTokens);
    }

    public synchronized BigDecimal committedUsd() { return committed; }
    public synchronized BigDecimal remainingUsd() { return ceilingUsd.subtract(committed); }
    public synchronized int callsReserved() { return calls; }
    public BigDecimal ceilingUsd() { return ceilingUsd; }
}
