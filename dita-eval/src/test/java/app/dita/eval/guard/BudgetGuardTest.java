package app.dita.eval.guard;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** N-45: worst-case charging at P_in=$1.25/M, P_out=$4.25/M. */
class BudgetGuardTest {
    static BigDecimal usd(String s) { return new BigDecimal(s); }

    @Test void worstCaseIsInputPlusFullMaxCompletion() {
        assertEquals(0, usd("5.50").compareTo(BudgetGuard.worstCaseUsd(1_000_000, 1_000_000)));
        // 1000 * 1.25e-6 + 2048 * 4.25e-6 = 0.00125 + 0.008704
        assertEquals(0, usd("0.009954").compareTo(BudgetGuard.worstCaseUsd(1000, 2048)));
    }

    @Test void refusesCallThatWouldExceedCeilingAndKeepsState() {
        BudgetGuard g = new BudgetGuard(usd("0.02"));
        g.reserve(1000, 2048);                       // 0.009954
        g.reserve(1000, 2048);                       // 0.019908
        BudgetExceededException e = assertThrows(BudgetExceededException.class, () -> g.reserve(1000, 2048));
        assertTrue(e.getMessage().contains("refused call 3"));
        assertEquals(2, g.callsReserved());
        assertEquals(0, usd("0.019908").compareTo(g.committedUsd()));
        g.reserve(0, 1);                             // 0.00000425 still fits
        assertEquals(3, g.callsReserved());
    }

    @Test void exactlyAtCeilingIsAllowed() {
        BudgetGuard g = new BudgetGuard(usd("5.50"));
        g.reserve(1_000_000, 1_000_000);
        assertEquals(0, BigDecimal.ZERO.compareTo(g.remainingUsd()));
        assertThrows(BudgetExceededException.class, () -> g.reserve(0, 1));
    }

    @Test void chargesFullMaxEvenIfTheCallWouldBeShort() {
        BudgetGuard g = new BudgetGuard(usd("1"));
        BudgetGuard.Reservation r = g.reserve(10, 4096);
        assertEquals(0, BudgetGuard.worstCaseUsd(10, 4096).compareTo(r.worstCaseUsd()));
    }

    @Test void unboundedOutputAndBadInputsAreRejected() {
        BudgetGuard g = new BudgetGuard(usd("1"));
        assertThrows(IllegalArgumentException.class, () -> g.reserve(10, 0));
        assertThrows(IllegalArgumentException.class, () -> g.reserve(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new BudgetGuard(usd("-0.01")));
        assertEquals(0, g.callsReserved());
    }

    @Test void inputUpperBoundCountsUtf8BytesSoHebrewIsNotUndercounted() {
        assertEquals(3, BudgetGuard.inputTokenUpperBound("abc"));
        assertEquals(8, BudgetGuard.inputTokenUpperBound("שלום"));   // 4 Hebrew letters x 2 UTF-8 bytes
    }

    @Test void zeroCeilingRefusesEverything() {
        assertThrows(BudgetExceededException.class, () -> new BudgetGuard(BigDecimal.ZERO).reserve(0, 1));
    }
}
