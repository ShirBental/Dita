package app.dita.eval.cost;

import app.dita.eval.cost.UsageRecord.CallKind;
import app.dita.eval.guard.BudgetExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N-56 ledger. Default prices ($1.25/M in, $4.25/M out). Worst case of (1000 input, cap 2048) =
 * 1000*1.25e-6 + 2048*4.25e-6 = 0.00125 + 0.008704 = 0.009954. All usage records here are synthetic.
 */
class CostLedgerTest {
    static BigDecimal usd(String s) { return new BigDecimal(s); }

    static UsageRecord use(CallKind k, String arm, long in, long out, int cap, Provenance p) {
        return new UsageRecord(k, arm, "test", "muse-spark-1.3", "low", cap, in, out, null, null, null, "stop", 200, null, null, null, p);
    }

    @Test void sumsArePerArmAndPerCallKindAndSeparatedByProvenance() {
        CostLedger l = CostLedger.inMemory("exp-1", usd("1"), PriceTable.DEFAULT);
        l.reserve("a1", CallKind.ANSWER, "B-weak control", 1000, 2048);
        l.reserve("a2", CallKind.ANSWER, "C-today", 1000, 2048);
        l.reserve("x1", CallKind.EXTRACTION, null, 1000, 2048);
        // a1 SIMULATED: 1000*1.25e-6 + 500*4.25e-6 = 0.00125 + 0.002125 = 0.003375
        assertEquals(0, usd("0.003375").compareTo(l.recordActual("a1", use(CallKind.ANSWER, "B-weak control", 1000, 500, 2048, Provenance.SIMULATED))));
        // a2 REPLAYED: 800*1.25e-6 + 400*4.25e-6 = 0.001 + 0.0017 = 0.0027
        l.recordActual("a2", use(CallKind.ANSWER, "C-today", 800, 400, 2048, Provenance.REPLAYED));
        // x1 MEASURED: 924*1.25e-6 + 2000*4.25e-6 = 0.001155 + 0.0085 = 0.009655
        l.recordActual("x1", use(CallKind.EXTRACTION, null, 924, 2000, 2048, Provenance.MEASURED));

        Map<Provenance, Map<String, BigDecimal>> byArm = l.actualByArm();
        assertEquals(0, usd("0.003375").compareTo(byArm.get(Provenance.SIMULATED).get("B-weak control")));
        assertEquals(0, usd("0.0027").compareTo(byArm.get(Provenance.REPLAYED).get("C-today")));
        assertEquals(0, usd("0.009655").compareTo(byArm.get(Provenance.MEASURED).get("(none)")));
        assertNull(byArm.get(Provenance.MEASURED).get("B-weak control"));            // never mixed across provenance
        assertEquals(0, usd("0.009655").compareTo(l.actualByCallKind().get(Provenance.MEASURED).get(CallKind.EXTRACTION)));
        assertNull(l.actualByCallKind().get(Provenance.MEASURED).get(CallKind.ANSWER));
        assertEquals(0, usd("0.009655").compareTo(l.actualTotal(Provenance.MEASURED)));

        // reservations: 3 x 0.009954 = 0.029862 committed, independent of actuals
        CostLedger.Reconciliation r = l.reconcile();
        assertEquals(0, usd("0.029862").compareTo(r.reservedUsd()));
        assertEquals(3, r.callsReserved());
        assertEquals(3, r.callsWithActual());
        assertEquals(0, r.reservedWithoutActual());
        assertTrue(r.withinReservations());
        assertEquals(0, usd("0.009655").compareTo(r.actualUsdByProvenance().get(Provenance.MEASURED)));
    }

    @Test void reconciliationFlagsOverrunsAndReservedWithoutActual() {
        CostLedger l = CostLedger.inMemory("exp-2", usd("1"), PriceTable.DEFAULT);
        l.reserve("a1", CallKind.ANSWER, null, 10, 2048);     // reserved with an undercounted input bound
        l.reserve("a2", CallKind.ANSWER, null, 1000, 2048);   // never completes
        // a1 actual 100000 prompt tokens -> 0.125 + 0.0085 > worst case 0.0000125 + 0.008704
        l.recordActual("a1", use(CallKind.ANSWER, null, 100_000, 2000, 2048, Provenance.SIMULATED));
        l.importHistorical("h1", use(CallKind.CONNECTIVITY, null, 25, 64, 64, Provenance.MEASURED));
        CostLedger.Reconciliation r = l.reconcile();
        assertEquals(List.of("a1"), r.overrunCallIds());
        assertFalse(r.withinReservations());
        assertEquals(1, r.reservedWithoutActual());
        assertEquals(0, usd("0.009954").compareTo(r.reservedWithoutActualUsd()));   // presumed charged at worst case
        assertEquals(1, r.unreservedActuals());
        assertEquals(2, r.callsReserved());                                         // the import consumed no budget
    }

    @Test void noResendNoUnreservedActualsAndMismatchesRefused() {
        CostLedger l = CostLedger.inMemory("exp-3", usd("1"), PriceTable.DEFAULT);
        l.reserve("a1", CallKind.ANSWER, "C-today", 1000, 2048);
        assertThrows(IllegalStateException.class, () -> l.reserve("a1", CallKind.ANSWER, "C-today", 1000, 2048));
        assertThrows(IllegalStateException.class, () -> l.recordActual("zz", use(CallKind.ANSWER, null, 1, 1, 2048, Provenance.SIMULATED)));
        assertThrows(IllegalArgumentException.class, () -> l.recordActual("a1", use(CallKind.ANSWER, "B-weak control", 1, 1, 2048, Provenance.SIMULATED)));
        assertThrows(IllegalArgumentException.class, () -> l.recordActual("a1", use(CallKind.ANSWER, "C-today", 1, 1, 1024, Provenance.SIMULATED)));
        assertThrows(IllegalArgumentException.class, () -> l.reserve("bad id with spaces", CallKind.ANSWER, null, 1, 1));
        l.recordActual("a1", use(CallKind.ANSWER, "C-today", 1, 1, 2048, Provenance.SIMULATED));
        assertThrows(IllegalStateException.class, () -> l.recordActual("a1", use(CallKind.ANSWER, "C-today", 1, 1, 2048, Provenance.SIMULATED)));
        assertEquals(1, l.callsReserved());
    }

    @Test void failureHaltsAndBudgetIsEnforced() {
        CostLedger l = CostLedger.inMemory("exp-4", usd("0.02"), PriceTable.DEFAULT);
        l.reserve("a1", CallKind.ANSWER, null, 1000, 2048);
        l.reserve("a2", CallKind.ANSWER, null, 1000, 2048);   // 0.019908
        assertThrows(BudgetExceededException.class, () -> l.reserve("a3", CallKind.ANSWER, null, 1000, 2048));
        l.recordFailure("a2", CostLedger.FailureCode.HTTP_ERROR);
        assertTrue(l.halted());
        assertThrows(IllegalStateException.class, () -> l.reserve("a4", CallKind.ANSWER, null, 0, 1));
        assertTrue(l.reconcile().halted());
    }

    @Test void diskLedgerSurvivesANewInstanceAndCannotBeReset(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ledger.jsonl");
        String head;
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-disk", usd("0.03"), PriceTable.DEFAULT)) {
            l.reserve("a1", CallKind.ANSWER, "C-today", 1000, 2048);
            l.reserve("a2", CallKind.EXTRACTION, null, 1000, 2048);
            l.recordActual("a1", use(CallKind.ANSWER, "C-today", 900, 300, 2048, Provenance.SIMULATED));
            head = l.headHash();
        }
        // A new instance re-reads the running total (0.019908) instead of starting at zero
        try (CostLedger l2 = CostLedger.openOrCreate(f, "exp-disk", usd("0.03"), PriceTable.DEFAULT, head)) {
            assertEquals(0, usd("0.019908").compareTo(l2.committedUsd()));
            assertEquals(2, l2.callsReserved());
            assertEquals(0, usd("0.010092").compareTo(l2.remainingUsd()));
            // 900*1.25e-6 + 300*4.25e-6 = 0.001125 + 0.001275 = 0.0024
            assertEquals(0, usd("0.0024").compareTo(l2.actualTotal(Provenance.SIMULATED)));
            assertThrows(IllegalStateException.class, () -> l2.reserve("a1", CallKind.ANSWER, "C-today", 1, 1));   // still no re-send
            // 1000*1.25e-6 + 4096*4.25e-6 = 0.00125 + 0.017408 = 0.018658 > remaining 0.010092
            assertThrows(BudgetExceededException.class, () -> l2.reserve("a3", CallKind.ANSWER, null, 1000, 4096));
            assertEquals(0, usd("0.019908").compareTo(l2.committedUsd()));   // a refused call commits nothing
            assertEquals(2, l2.callsReserved());
            // Boundary: cap 2048 -> 0.009954 <= remaining 0.010092, so it fits: committed 0.029862 <= ceiling 0.03
            l2.reserve("a4", CallKind.ANSWER, null, 1000, 2048);
            assertEquals(0, usd("0.029862").compareTo(l2.committedUsd()));
        }
        // Raising the ceiling, switching experiment or prices on reopen is refused
        assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-disk", usd("100"), PriceTable.DEFAULT).close());
        assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-other", usd("0.03"), PriceTable.DEFAULT).close());
        assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-disk", usd("0.03"),
                new PriceTable(usd("0.01"), usd("0.01"), null, "cheap test")).close());
    }

    @Test void separateProcessReReadsTheTotalAndCannotResetOrRaiseIt(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ledger.jsonl");
        try (CostLedger l = CostLedger.openOrCreate(f, "probe-exp", usd("0.03"), PriceTable.DEFAULT)) {
            l.reserve("a1", CallKind.ANSWER, null, 1000, 2048);
            l.reserve("a2", CallKind.ANSWER, null, 1000, 2048);   // committed 0.019908
        }
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LedgerProbe.class.getName(), f.toString(), "0.03")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        List<String> lines = out.lines().toList();
        String committed = lines.stream().filter(x -> x.startsWith("committed=")).findFirst().orElseThrow().substring("committed=".length());
        assertEquals(0, usd("0.019908").compareTo(usd(committed)), out);
        assertTrue(lines.contains("calls=2"), out);
        // probe-big: 0 + 4096*4.25e-6 = 0.017408 -> 0.037316 > 0.03: refused
        assertTrue(lines.contains("big=refused"), out);
        assertTrue(lines.contains("raise=refused"), out);
        try (CostLedger l = CostLedger.openOrCreate(f, "probe-exp", usd("0.03"), PriceTable.DEFAULT)) {
            assertEquals(0, usd("0.019908").compareTo(l.committedUsd()));
        }
    }

    @Test void haltSurvivesReopen(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ledger.jsonl");
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-h", usd("1"), PriceTable.DEFAULT)) {
            l.reserve("a1", CallKind.ANSWER, null, 10, 10);
            l.recordFailure("a1", CostLedger.FailureCode.PARSE);
        }
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-h", usd("1"), PriceTable.DEFAULT)) {
            assertTrue(l.halted());
            assertThrows(IllegalStateException.class, () -> l.reserve("a2", CallKind.ANSWER, null, 10, 10));
        }
    }

    @Test void tamperingIsDetected(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ledger.jsonl");
        String head;
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-t", usd("1"), PriceTable.DEFAULT)) {
            l.reserve("a1", CallKind.ANSWER, null, 1000, 2048);
            l.reserve("a2", CallKind.ANSWER, null, 1000, 2048);
            head = l.headHash();
        }
        List<String> lines = Files.readAllLines(f);
        assertEquals(3, lines.size());   // header + 2 reservations

        // (1) editing an amount breaks the chain
        rewrite(f, lines, 1, lines.get(1).replaceFirst("\"usd\":\"[0-9.]+\"", "\"usd\":\"0.000001\""));
        assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-t", usd("1"), PriceTable.DEFAULT).close());

        // (2) deleting the last reservation (tail truncation) passes the chain but fails against the recorded head
        Files.writeString(f, lines.get(0) + "\n" + lines.get(1) + "\n");
        assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-t", usd("1"), PriceTable.DEFAULT, head).close());

        // (3) a partial last line is refused
        Files.writeString(f, String.join("\n", lines) + "\n{\"seq\":3");
        assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-t", usd("1"), PriceTable.DEFAULT).close());

        // (4) the intact file reopens with the same head
        Files.writeString(f, String.join("\n", lines) + "\n");
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-t", usd("1"), PriceTable.DEFAULT, head)) {
            assertEquals(head, l.headHash());
        }
    }

    @Test void secondOpenInTheSameProcessIsRefusedWhileLocked(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("ledger.jsonl");
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-l", usd("1"), PriceTable.DEFAULT)) {
            assertThrows(IllegalStateException.class, () -> CostLedger.openOrCreate(f, "exp-l", usd("1"), PriceTable.DEFAULT));
            l.reserve("a1", CallKind.ANSWER, null, 1, 1);
        }
        try (CostLedger l = CostLedger.openOrCreate(f, "exp-l", usd("1"), PriceTable.DEFAULT)) {
            assertEquals(1, l.callsReserved());
        }
    }

    private static void rewrite(Path f, List<String> lines, int idx, String replacement) throws IOException {
        List<String> copy = new ArrayList<>(lines);
        copy.set(idx, replacement);
        Files.writeString(f, String.join("\n", copy) + "\n");
    }
}
