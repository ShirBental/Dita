package app.dita.eval.cost;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.guard.Sentinels;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** N-58 scans and reproducibility of the committed preliminary cost outputs. */
class CostOutputsTest {

    @Test void scannerCatchesKeysCanariesUuidsTextFieldsAndClaims() {
        assertRule("KEY_SK", CostOutputScan.scanText("r.md", "key sk-abcDEF123456", null, false, true));
        assertRule("KEY_BEARER", CostOutputScan.scanText("r.md", "Authorization: Bearer abcdefgh12345", null, false, true));
        assertRule("KEY_ENV_ASSIGN", CostOutputScan.scanText("r.md", "MODEL_API_KEY=abcdef123", null, false, true));
        assertRule("RAW_REQUEST_ID_UUID", CostOutputScan.scanText("r.md", "id 12345678-1234-1234-1234-123456789abc", null, false, true));
        assertRule("CANARY_SHAPE", CostOutputScan.scanText("r.md", "DITA-CANARY-0123456789ABCDEF", null, false, true));
        assertRule("GOLD_LABEL_FIELD", CostOutputScan.scanText("r.json", "{\"answer_keys\":[]}", null, false, true));
        assertRule("KEY_OPAQUE_TOKEN", CostOutputScan.scanText("r.md", "aB3" + "xY9".repeat(12), null, false, true));
        assertRule("CLAIM_WORDING", CostOutputScan.scanText("r.md", "Dita is cheaper than B", null, false, true));
        assertRule("PII_EMAIL", CostOutputScan.scanText("r.md", "mail someone@example.com", null, false, true));
        assertRule("USAGE_NOT_NUMBERS_ONLY", CostOutputScan.scanText("u.jsonl",
                PriceTableAndUsageTest.GOOD.replace("}", ",\"verbatim_evidence\":[]}"), null, true, false));
        Sentinels s = new Sentinels(Set.of(Sentinels.hash("synthetic canary phrase")), Set.of());
        assertRule("SEALED_CANARY", CostOutputScan.scanText("r.md", "a synthetic canary phrase here", s, false, true));
        // Not flagged: 64-hex hashes, snake_case keys, "per token: ..." prose
        assertEquals(List.of(), CostOutputScan.scanText("r.json", "\"request_id_sha256\":\"" + "ab".repeat(32) + "\" first_grid_q_central_delta_req_below_saving per token: Hebrew", null, false, true));
    }

    @Test void committedCostOutputsPassTheScan() throws Exception {
        // The sealed-canary rule is only meaningful if there are sealed canaries to look for
        assertFalse(CostOutputScan.sealedSentinels(BenchPaths.locate()).canaryHashes().isEmpty());
        assertTrue(CostOutputScan.OUTPUTS.contains("usage/unledgered-usage.jsonl"));
        List<CostOutputScan.Finding> f = CostOutputScan.run(BenchPaths.locate());
        assertEquals(List.of(), f);
    }

    @Test void analysisIsDeterministicAndMatchesTheCommittedOutputs() throws Exception {
        BenchPaths p = BenchPaths.locate();
        PreliminaryCostAnalysis.Outputs a = PreliminaryCostAnalysis.analyse(p), b = PreliminaryCostAnalysis.analyse(p);
        assertEquals(a, b);
        assertEquals(Files.readString(p.results().resolve("cost-prelim.json"), StandardCharsets.UTF_8), a.json(), "regenerate: committed cost-prelim.json is stale");
        assertEquals(Files.readString(p.results().resolve("cost-prelim.md"), StandardCharsets.UTF_8), a.markdown(), "regenerate: committed cost-prelim.md is stale");
        assertEquals(Files.readString(p.bench().resolve("charts/cost-vs-questions.svg"), StandardCharsets.UTF_8), a.svg(), "regenerate: committed SVG is stale");
    }

    @Test void reportIsLabelledAndEveryNumberCarriesProvenance() {
        BenchPaths p = BenchPaths.locate();
        PreliminaryCostAnalysis.Outputs o;
        try { o = PreliminaryCostAnalysis.analyse(p); } catch (Exception e) { throw new AssertionError(e); }
        assertTrue(o.svg().contains("ESTIMATED – no answer call measured"));
        assertTrue(o.markdown().contains("ESTIMATED – no answer call measured"));
        assertTrue(o.markdown().contains("No CPCA can be computed until answers are produced and scored"));
        assertTrue(o.markdown().contains("NOT a measurement"));
        JsonNode root = Json.parse(o.json());
        List<String> unlabelled = new ArrayList<>();
        walk(root, "$", unlabelled);
        assertEquals(List.of(), unlabelled, "numeric values without a sibling provenance label");
    }

    /** Every object holding "value" or "lo"/"hi" must hold "provenance" too. */
    private static void walk(JsonNode n, String path, List<String> bad) {
        if (n.isObject()) {
            if ((n.has("value") || n.has("lo")) && !n.has("provenance")) bad.add(path);
            n.fields().forEachRemaining(e -> walk(e.getValue(), path + "." + e.getKey(), bad));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) walk(n.get(i), path + "[" + i + "]", bad);
        }
    }

    private static void assertRule(String rule, List<CostOutputScan.Finding> f) {
        assertTrue(f.stream().anyMatch(x -> x.rule().equals(rule)), () -> rule + " not found in " + f);
    }
}
