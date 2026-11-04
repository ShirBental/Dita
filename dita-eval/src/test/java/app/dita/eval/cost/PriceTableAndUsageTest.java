package app.dita.eval.cost;

import app.dita.eval.BenchPaths;
import app.dita.eval.gate.Manifest;
import app.dita.eval.guard.BudgetExceededException;
import app.dita.eval.guard.BudgetGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PriceTable (incl. the BudgetGuard extension) and the numbers-only usage format (N-55, N-60). */
class PriceTableAndUsageTest {
    static BigDecimal usd(String s) { return new BigDecimal(s); }

    static final String GOOD = "{\"schema\":\"dita-eval/usage-v1\",\"call_kind\":\"ANSWER\",\"arm\":\"C-today\",\"run_label\":\"sim-1\","
            + "\"model\":\"muse-spark-1.3\",\"reasoning_effort\":\"low\",\"max_completion_tokens\":2048,\"prompt_tokens\":1000,"
            + "\"completion_tokens\":800,\"reasoning_tokens\":500,\"cached_tokens\":0,\"latency_ms\":1200,\"finish_reason\":\"stop\","
            + "\"http_status\":200,\"request_bytes\":4000,\"request_sha256\":null,\"request_id_sha256\":null,\"provenance\":\"SIMULATED\"}";

    // ------------------------------------------------------------ PriceTable

    @Test void defaultPricesAreUserSuppliedAndUnverified() {
        assertEquals(0, usd("1.25").compareTo(PriceTable.DEFAULT.inPerMillion()));
        assertEquals(0, usd("4.25").compareTo(PriceTable.DEFAULT.outPerMillion()));
        assertNull(PriceTable.DEFAULT.cachedInPerMillion());
        assertFalse(PriceTable.DEFAULT.cachedPriceKnown());
        assertEquals("user-supplied, unverified", PriceTable.DEFAULT.source());
        // Same worst case as the Stage 0 guard: 1000*1.25e-6 + 2048*4.25e-6 = 0.009954
        assertEquals(0, BudgetGuard.worstCaseUsd(1000, 2048).compareTo(PriceTable.DEFAULT.worstCaseUsd(1000, 2048)));
    }

    @Test void unknownCachedPriceChargesCachedTokensAtFullInputPrice() {
        // 1000 prompt (400 cached) + 100 completion. Unknown cached price: 1000*1.25e-6 + 100*4.25e-6 = 0.00125 + 0.000425
        assertEquals(0, usd("0.001675").compareTo(PriceTable.DEFAULT.actualUsd(1000, 400L, 100)));
        // Known cached price $0.25/M: 600*1.25e-6 + 400*0.25e-6 + 100*4.25e-6 = 0.00075 + 0.0001 + 0.000425 = 0.001275
        PriceTable withCache = new PriceTable(usd("1.25"), usd("4.25"), usd("0.25"), "test");
        assertEquals(0, usd("0.001275").compareTo(withCache.actualUsd(1000, 400L, 100)));
        assertThrows(IllegalArgumentException.class, () -> PriceTable.DEFAULT.actualUsd(10, 11L, 0));
        assertThrows(IllegalArgumentException.class, () -> new PriceTable(usd("1"), usd("1"), null, " "));
    }

    @Test void budgetGuardCanChargeAtAPriceTable() {
        PriceTable p = new PriceTable(usd("2"), usd("10"), null, "test");
        BudgetGuard g = new BudgetGuard(usd("0.05"), p);
        // 1000*2e-6 + 2048*10e-6 = 0.002 + 0.02048 = 0.02248
        assertEquals(0, usd("0.02248").compareTo(g.reserve(1000, 2048).worstCaseUsd()));
        g.reserve(1000, 2048);                                     // 0.04496
        assertThrows(BudgetExceededException.class, () -> g.reserve(1000, 2048));
        assertSame(p, g.prices());
        // The one-arg constructor keeps the Stage 0 behaviour (static default prices)
        BudgetGuard d = new BudgetGuard(usd("1"));
        assertNull(d.prices());
        assertEquals(0, usd("0.009954").compareTo(d.reserve(1000, 2048).worstCaseUsd()));
    }

    // ------------------------------------------------------------ usage format

    @Test void recordedUsageFileLoadsUnderTheAllowlistAndMatchesItsManifest() throws Exception {
        Path dir = BenchPaths.locate().bench().resolve("usage");
        List<UsageRecord> us = UsageJsonl.load(dir.resolve("recorded-usage.jsonl"));
        // Exactly the measured runs 2-5 of the tasks.md cost ledger, in order
        assertEquals(List.of("muse-live-run-2", "muse-live-run-3", "muse-live-run-4", "muse-live-run-5"), us.stream().map(UsageRecord::runLabel).toList());
        assertTrue(us.stream().allMatch(u -> u.provenance() == Provenance.MEASURED));
        assertTrue(us.stream().allMatch(u -> u.requestSha256() == null));   // no request bodies were recorded
        UsageRecord r2 = us.get(0), r3 = us.get(1);
        assertEquals(List.of(25L, 64L, 64), List.of(r2.promptTokens(), r2.completionTokens(), r2.maxCompletionTokens()));
        assertEquals(List.of(25L, 128L, 128), List.of(r3.promptTokens(), r3.completionTokens(), r3.maxCompletionTokens()));
        // ledger: run 2 25*1.25e-6 + 64*4.25e-6 = 0.00003125 + 0.000272 = 0.00030325 ($0.000303);
        //         run 3 0.00003125 + 128*4.25e-6 = 0.00003125 + 0.000544 = 0.00057525 ($0.000575)
        assertEquals(0, usd("0.00030325").compareTo(PriceTable.DEFAULT.actualUsd(r2)));
        assertEquals(0, usd("0.00057525").compareTo(PriceTable.DEFAULT.actualUsd(r3)));
        assertTrue(us.stream().allMatch(u -> u.arm() == null));
        assertTrue(us.stream().noneMatch(u -> u.runLabel().equals("muse-live-run-1")));   // run 1: no usage recorded
        UsageRecord r5 = us.stream().filter(u -> u.runLabel().equals("muse-live-run-5")).findFirst().orElseThrow();
        // 924*1.25e-6 + 2846*4.25e-6 = 0.001155 + 0.0120955 = 0.0132505 (ledger: $0.013251)
        assertEquals(0, usd("0.0132505").compareTo(PriceTable.DEFAULT.actualUsd(r5)));
        assertEquals(628L, r5.visibleTokens());
        assertEquals(List.of(924L, 2846L, 2218L, 9884L), List.of(r5.promptTokens(), r5.completionTokens(), r5.reasoningTokens(), r5.latencyMs()));
        assertEquals("stop", r5.finishReason());
        UsageRecord r4 = us.stream().filter(u -> u.runLabel().equals("muse-live-run-4")).findFirst().orElseThrow();
        assertTrue(r4.truncated());
        assertEquals(List.of(924L, 4096L), List.of(r4.promptTokens(), r4.completionTokens()));
        // 924*1.25e-6 + 4096*4.25e-6 = 0.001155 + 0.017408 = 0.018563
        assertEquals(0, usd("0.018563").compareTo(PriceTable.DEFAULT.actualUsd(r4)));
        Map<String, String> m = Manifest.parse(java.nio.file.Files.readString(dir.resolve("MANIFEST.sha256")), new java.util.ArrayList<>());
        assertEquals(Manifest.sha256(dir.resolve("recorded-usage.jsonl")), m.get("recorded-usage.jsonl"));
        // Request ids are present only as SHA-256 (N-60)
        assertTrue(us.stream().allMatch(u -> u.requestIdSha256() == null || u.requestIdSha256().matches("[0-9a-f]{64}")));
        // Sum = 0.00030325 + 0.00057525 + 0.018563 + 0.0132505 = 0.032692 (tasks.md: runs 2-4 ~ $0.0195, plus run 5 $0.013251)
        assertEquals(0, usd("0.032692").compareTo(us.stream().map(PriceTable.DEFAULT::actualUsd).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test void unledgeredCallIsKeptSeparatelyNumbersOnlyAndManifested() throws Exception {
        Path dir = BenchPaths.locate().bench().resolve("usage");
        List<UsageRecord> un = UsageJsonl.load(dir.resolve("unledgered-usage.jsonl"));
        assertEquals(1, un.size());
        UsageRecord c = un.get(0);
        assertEquals(UsageRecord.CallKind.CONNECTIVITY, c.callKind());
        assertEquals(Provenance.MEASURED, c.provenance());
        // 25*1.25e-6 + 101*4.25e-6 = 0.00003125 + 0.00042925 = 0.0004605
        assertEquals(0, usd("0.0004605").compareTo(PriceTable.DEFAULT.actualUsd(c)));
        Map<String, String> m = Manifest.parse(java.nio.file.Files.readString(dir.resolve("MANIFEST.sha256")), new java.util.ArrayList<>());
        assertEquals(Manifest.sha256(dir.resolve("unledgered-usage.jsonl")), m.get("unledgered-usage.jsonl"));
        assertEquals(2, m.size());
    }

    @Test void loaderRejectsEvidenceAndAnyOtherNonAllowlistedField() {
        assertEquals(1, UsageJsonl.parse(GOOD).size());
        String withEvidence = GOOD.replace("}", ",\"verbatim_evidence\":[{\"messageId\":\"m1\",\"quote\":\"synthetic\"}]}");
        UsageJsonl.UsageFormatException e = assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(withEvidence));
        assertTrue(e.getMessage().contains("verbatim_evidence"));
        assertFalse(e.getMessage().contains("synthetic"));                      // never echoes values
        for (String extra : List.of("content", "request_id", "model_statement_unverified", "accepted_items", "explanation"))
            assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("}", ",\"" + extra + "\":null}")), extra);
    }

    @Test void loaderRejectsTextSmuggledThroughAllowlistedFields() {
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"sim-1\"", "\"a free text sentence\"")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"stop\"", "\"the model said hi\"")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"C-today\"", "\"free text arm\"")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"request_sha256\":null", "\"request_sha256\":\"not-a-hash\"")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"prompt_tokens\":1000", "\"prompt_tokens\":\"1000\"")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"prompt_tokens\":1000", "\"prompt_tokens\":1000.5")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"latency_ms\":1200,", "")));   // missing
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("usage-v1", "usage-v2")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"SIMULATED\"", "\"GUESSED\"")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse(GOOD.replace("\"completion_tokens\":800", "\"completion_tokens\":4096")));
        assertThrows(UsageJsonl.UsageFormatException.class, () -> UsageJsonl.parse("[1,2]"));
    }

    @Test void renderParseRoundTripIsStable() {
        List<UsageRecord> a = UsageJsonl.parse(GOOD);
        String rendered = UsageJsonl.render(a);
        assertEquals(GOOD + "\n", rendered);
        assertEquals(a, UsageJsonl.parse(rendered));
    }
}
