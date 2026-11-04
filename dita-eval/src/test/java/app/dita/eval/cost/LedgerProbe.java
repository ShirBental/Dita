package app.dita.eval.cost;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Test helper run in a SEPARATE JVM by {@link CostLedgerTest}: opens an existing ledger file and reports what it
 * re-read, then tries to (a) reserve a call that no longer fits and (b) reopen with a raised ceiling. Prints numbers
 * only.
 */
public final class LedgerProbe {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        BigDecimal ceiling = new BigDecimal(args[1]);
        try (CostLedger l = CostLedger.openOrCreate(file, "probe-exp", ceiling, PriceTable.DEFAULT)) {
            System.out.println("committed=" + l.committedUsd().toPlainString());
            System.out.println("calls=" + l.callsReserved());
            System.out.println("halted=" + l.halted());
            try {
                l.reserve("probe-big", UsageRecord.CallKind.ANSWER, null, 0, 4096);
                System.out.println("big=accepted");
            } catch (app.dita.eval.guard.BudgetExceededException e) {   // refused by the budget, not by some other error
                System.out.println("big=refused");
            }
        }
        try (CostLedger l = CostLedger.openOrCreate(file, "probe-exp", ceiling.add(BigDecimal.ONE), PriceTable.DEFAULT)) {
            System.out.println("raise=accepted");
        } catch (IllegalStateException e) {
            System.out.println("raise=refused");
        }
    }

    private LedgerProbe() {}
}
