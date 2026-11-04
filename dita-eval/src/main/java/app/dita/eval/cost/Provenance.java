package app.dita.eval.cost;

/**
 * N-57: where a number came from (COST-DESIGN §0). Every figure the cost calculator emits carries one of these.
 * Worst-case planning figures and actual figures are reported side by side, never one in place of the other.
 */
public enum Provenance {
    /** Executed live; usage read from the API response. */
    MEASURED,
    /** Executed locally without a model (context sizes, retrieval latency, prompt size). */
    MEASURED_OFFLINE,
    /** Derived from measured inputs under stated assumptions (chars to tokens, scaled extraction, assumption bands). */
    ESTIMATED,
    /** Recorded model output injected by the harness. */
    REPLAYED,
    /** Invented or synthetic stand-ins (test records, notes for corpora other than the run-5 fixture). */
    SIMULATED;

    /** The weakest of two labels: a value derived from inputs of mixed provenance is only as strong as its weakest input. */
    public static Provenance weakest(Provenance a, Provenance b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public String label() { return name(); }
}
