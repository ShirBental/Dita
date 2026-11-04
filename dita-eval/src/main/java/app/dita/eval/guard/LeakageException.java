package app.dita.eval.guard;

/** A leakage control tripped (N-50). Messages name the check, never the leaked content. Aborts the run. */
public final class LeakageException extends RuntimeException {
    public LeakageException(String message) { super(message); }
}
