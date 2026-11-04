package app.dita.eval.answer;

/**
 * Placeholder for a future Muse answerer. There is deliberately no live path in Stage 0: every call throws. A live
 * implementation needs the N-44/N-45/N-46/N-50 gates closed and explicit user approval per run (DESIGN §10).
 */
public final class MuseAnswererStub implements ModelAnswerer {
    public static final String MESSAGE = "live calls require approval: the Muse answerer is not implemented in Stage 0 (offline only)";

    @Override public String name() { return "muse-stub"; }
    @Override public boolean isMock() { return false; }

    @Override public Reply answer(ModelRequest request) { throw new UnsupportedOperationException(MESSAGE); }
}
