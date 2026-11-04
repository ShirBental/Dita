package app.dita.app.trial;

import app.dita.core.answer.synthesis.GroundedSynthesis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * TASK-054: the Telegram request a Muse call belongs to. Set only around one /extract batch or one /ask on the calling
 * thread. A Muse call with no context, of the wrong kind, or beyond the context's single-call allowance is refused by
 * {@link ApprovalGatedTransport} without asking anyone and without any network access.
 */
public final class TrialContext {
    public enum Kind { EXTRACTION, SYNTHESIS }

    private static final ThreadLocal<TrialContext> CURRENT = new ThreadLocal<>();

    public final String correlation;
    public final Kind kind;
    final List<String> callIds = new ArrayList<>();
    final List<GroundedSynthesis.Result> synthesis = new ArrayList<>();
    int callsAttempted;

    private TrialContext(String correlation, Kind kind) { this.correlation = correlation; this.kind = kind; }

    public static <T> T during(String correlation, Kind kind, Supplier<T> body) {
        if (CURRENT.get() != null) throw new IllegalStateException("nested trial context");
        TrialContext c = new TrialContext(correlation, kind);
        CURRENT.set(c);
        try { return body.get(); } finally { CURRENT.remove(); }
    }

    /** Runs {@code body} and also returns the context's evidence (call ids, synthesis results). */
    public static <T> Observed<T> observe(String correlation, Kind kind, Supplier<T> body) {
        TrialContext[] seen = new TrialContext[1];
        T value = during(correlation, kind, () -> { seen[0] = CURRENT.get(); return body.get(); });
        return new Observed<>(value, List.copyOf(seen[0].callIds), List.copyOf(seen[0].synthesis), seen[0].callsAttempted);
    }

    public record Observed<T>(T value, List<String> callIds, List<GroundedSynthesis.Result> synthesis, int callsAttempted) {}

    static TrialContext current() { return CURRENT.get(); }

    /** GroundedSynthesis observer: records results of synthesis inside a trial context (ignored elsewhere). */
    public static void record(GroundedSynthesis.Result r) {
        TrialContext c = CURRENT.get();
        if (c != null) c.synthesis.add(r);
    }
}
