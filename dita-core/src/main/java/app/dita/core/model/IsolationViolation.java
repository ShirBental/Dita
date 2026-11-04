package app.dita.core.model;

/** Thrown whenever data from one group is about to cross into another group's scope. */
public final class IsolationViolation extends RuntimeException {
    public IsolationViolation(String message) { super(message); }
}
