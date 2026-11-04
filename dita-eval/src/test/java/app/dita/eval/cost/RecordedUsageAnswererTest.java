package app.dita.eval.cost;

import app.dita.eval.answer.AnswerPrompt;
import app.dita.eval.answer.ModelAnswerer;
import app.dita.eval.answer.ModelRequest;
import app.dita.eval.arms.ArmId;
import app.dita.eval.runner.Prompt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays SIMULATED (synthetic) usage records keyed by request-body SHA-256. These records are invented test inputs
 * and are never presented as measurements.
 */
class RecordedUsageAnswererTest {
    static final Prompt P1 = new Prompt("SIM01", "synthetic-group", Instant.parse("2026-09-01T00:00:00Z"), "Who brings the snacks?");
    static final Prompt P2 = new Prompt("SIM02", "synthetic-group", Instant.parse("2026-09-01T00:00:00Z"), "When is the trip?");

    static ModelRequest req(Prompt p, ArmId arm) { return AnswerPrompt.render(p, arm, "[]"); }

    static UsageRecord simulated(ModelRequest r, String arm, int cap, String effort, Long bytes) {
        return new UsageRecord(UsageRecord.CallKind.ANSWER, arm, "simulated-fixture", AnswerPrompt.MODEL, effort, cap, 1200, 900, 600L, 0L,
                1000L, "stop", 200, bytes, RecordedUsageAnswerer.bodySha256(r.body()), null, Provenance.SIMULATED);
    }

    @Test void replaysUsageByBodyHashWithoutAnyModelText() {
        ModelRequest r = req(P1, ArmId.C_TODAY);
        long bytes = r.body().getBytes(StandardCharsets.UTF_8).length;
        RecordedUsageAnswerer a = new RecordedUsageAnswerer(List.of(simulated(r, "C-today", AnswerPrompt.MAX_COMPLETION_TOKENS, "low", bytes)), null);
        ModelAnswerer.Reply reply = a.answer(r);
        assertEquals("", reply.content());
        assertEquals("stop", reply.finishReason());
        assertEquals(1200, reply.promptTokens());
        assertEquals(900, reply.completionTokens());
        assertTrue(a.isMock());
        assertEquals(Provenance.SIMULATED, a.lastUsage().provenance());   // never MEASURED
    }

    @Test void failsClosedOnMissingHash() {
        ModelRequest r1 = req(P1, ArmId.C_TODAY), r2 = req(P2, ArmId.C_TODAY);
        RecordedUsageAnswerer a = new RecordedUsageAnswerer(List.of(simulated(r1, "C-today", AnswerPrompt.MAX_COMPLETION_TOKENS, "low", null)), null);
        assertThrows(RecordedUsageAnswerer.MissingRecordingException.class, () -> a.answer(r2));
        // Any change to the body (e.g. a different arm's context) changes the hash: also missing
        ModelRequest changed = AnswerPrompt.render(P1, ArmId.C_TODAY, "[{\"type\":\"message\"}]");
        assertThrows(RecordedUsageAnswerer.MissingRecordingException.class, () -> a.answer(changed));
    }

    @Test void failsClosedOnStaleRecording() {
        ModelRequest r = req(P1, ArmId.C_TODAY);
        assertThrows(RecordedUsageAnswerer.StaleRecordingException.class, () ->
                new RecordedUsageAnswerer(List.of(simulated(r, "B-weak control", AnswerPrompt.MAX_COMPLETION_TOKENS, "low", null)), null).answer(r));
        assertThrows(RecordedUsageAnswerer.StaleRecordingException.class, () ->
                new RecordedUsageAnswerer(List.of(simulated(r, "C-today", 4096, "low", null)), null).answer(r));
        assertThrows(RecordedUsageAnswerer.StaleRecordingException.class, () ->
                new RecordedUsageAnswerer(List.of(simulated(r, "C-today", AnswerPrompt.MAX_COMPLETION_TOKENS, "medium", null)), null).answer(r));
        assertThrows(RecordedUsageAnswerer.StaleRecordingException.class, () ->
                new RecordedUsageAnswerer(List.of(simulated(r, "C-today", AnswerPrompt.MAX_COMPLETION_TOKENS, "low", 7L)), null).answer(r));
    }

    @Test void rejectsUnreplayableOrDuplicateRecordings() {
        ModelRequest r = req(P1, ArmId.C_TODAY);
        UsageRecord noHash = new UsageRecord(UsageRecord.CallKind.ANSWER, "C-today", "sim", AnswerPrompt.MODEL, "low", 2048, 1, 1,
                null, null, null, "stop", 200, null, null, null, Provenance.SIMULATED);
        assertThrows(IllegalArgumentException.class, () -> new RecordedUsageAnswerer(List.of(noHash), null));
        UsageRecord s = simulated(r, "C-today", 2048, "low", null);
        assertThrows(IllegalArgumentException.class, () -> new RecordedUsageAnswerer(List.of(s, s), null));
    }

    @Test void chargesTheLedgerUnderTheRecordsOwnProvenanceAndNeverResends() {
        ModelRequest r = req(P1, ArmId.C_TODAY);
        CostLedger ledger = CostLedger.inMemory("replay-test", BigDecimal.ONE, PriceTable.DEFAULT);
        RecordedUsageAnswerer a = new RecordedUsageAnswerer(List.of(simulated(r, "C-today", AnswerPrompt.MAX_COMPLETION_TOKENS, "low", null)), ledger);
        a.answer(r);
        // 1200*1.25e-6 + 900*4.25e-6 = 0.0015 + 0.003825 = 0.005325, filed under SIMULATED only
        assertEquals(0, new BigDecimal("0.005325").compareTo(ledger.actualTotal(Provenance.SIMULATED)));
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.actualTotal(Provenance.MEASURED)));
        assertEquals(1, ledger.callsReserved());
        assertTrue(ledger.reconcile().withinReservations());
        assertThrows(IllegalStateException.class, () -> a.answer(r));   // the same call id cannot be reserved twice
    }
}
