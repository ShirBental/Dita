package app.dita.eval.capability;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.runner.RunnerFileAccess;
import app.dita.eval.scorer.ScorerFileAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the TASK-044 capability test end to end (runner, then scorer) and writes results/capability-dev*.
 * Opt-in (-Ddita.capability.run=true) because it writes result files. No model, no network, synthetic data only.
 */
class CapabilityRunTest {
    @Test void runAndScoreDev() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.capability.run"), "set -Ddita.capability.run=true to run the capability test");
        BenchPaths paths = BenchPaths.locate();
        RunnerFileAccess runner = new RunnerFileAccess(paths);
        var rows = DitaProductRunner.run(runner, "dev");
        assertEquals(35, rows.size());
        runner.writeResult("capability-dev-answers.jsonl", DitaProductRunner.toJsonl(rows, "dev"));
        ScorerFileAccess scorer = new ScorerFileAccess(paths);
        var res = CapabilityScorer.score(scorer, "dev", "capability-dev-answers.jsonl");
        scorer.writeResult("capability-dev.json", Json.pretty(res.json()));
        scorer.writeResult("capability-dev.md", res.markdown());
        assertTrue(res.json().get("gated").get("n").asInt() == 35);
        var base = DitaProductRunner.run(runner, "dev", DitaProductRunner.BM25_ONLY);
        runner.writeResult("capability-dev-bm25-answers.jsonl", DitaProductRunner.toJsonl(base, "dev", DitaProductRunner.BASELINE_ARM));
        var bres = CapabilityScorer.score(scorer, "dev", "capability-dev-bm25-answers.jsonl");
        scorer.writeResult("capability-dev-bm25.json", Json.pretty(bres.json()));
        scorer.writeResult("capability-dev-bm25.md", bres.markdown());
    }
}
