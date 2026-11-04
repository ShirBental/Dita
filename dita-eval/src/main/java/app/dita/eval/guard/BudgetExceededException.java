package app.dita.eval.guard;

/** A planned call was refused because its worst-case cost would exceed the approved ceiling (N-45). */
public final class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) { super(message); }
}
