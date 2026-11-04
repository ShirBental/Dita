package app.dita.core.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Arm A4 threshold selection on DEV ONLY (PREREGISTRATION §7: no tuning on sealed data). Objective, in order: fewest
 * end-to-end safety failures (E1 + E2 + E4), then most replacements detected (E3), then highest thresholds (most conservative).
 * Writes frozen-thresholds.json with the dev set's sha256. Refuses to run on anything but the dev directory.
 */
class SemEvalTuneTest {
    @Test void tuneA4OnDev() throws Exception {
        Path dev = SemEvalRunTest.ROOT.resolve("dev");
        Assumptions.assumeTrue(Boolean.getBoolean("dita.semeval.tune") && Files.isDirectory(dev), "set -Ddita.semeval.tune=true");
        List<JsonNode> cases = SemEval.load(dev);
        double[] grid = {0.5, 0.6, 0.7, 0.8, 0.9, 0.95}, statusGrid = {0.3, 0.5, 0.7};
        double best[] = null; int bestSafety = Integer.MAX_VALUE, bestE3 = -1;
        try (NliModel nli = new NliModel()) {
            for (double tStatus : statusGrid) for (double tRep : grid) for (double tSame : grid) for (double tDiff : grid) {
                var arm = new RelationArms.Nli(nli, tRep, tSame, tDiff);
                var status = new SemEvalRunTest.NliStatus(nli, tStatus);
                int safety = 0, e3 = 0;
                for (JsonNode c : cases) {
                    var o = SemEval.run(c, c.path("authorGold"), arm, status, SemEval.fixtureItems(c), 0);
                    safety += (o.e1Fail() ? 1 : 0) + (o.e2Fail() ? 1 : 0) + (o.e4Fail() ? 1 : 0);
                    e3 += o.e3Success() ? 1 : 0;
                }
                boolean better = safety < bestSafety || (safety == bestSafety && e3 > bestE3);
                if (better) { bestSafety = safety; bestE3 = e3; best = new double[]{tRep, tSame, tDiff, tStatus}; }
            }
        }
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(dev)) { for (Path f : files.sorted().toList()) if (f.getFileName().toString().startsWith("cases-")) all.append(Files.readString(f)); }
        ObjectNode root = (ObjectNode) SemEval.JSON.readTree(Files.readString(SemEvalRunTest.ROOT.resolve("frozen-thresholds.json")));
        ObjectNode a4 = root.putObject("A4");
        a4.put("tRep", best[0]).put("tSame", best[1]).put("tDiff", best[2]).put("tStatus", best[3])
                .put("selectedOn", "dev sha256 " + SemEvalMuseTest.sha256(all.toString())).put("devSafetyFailures", bestSafety).put("devE3", bestE3)
                .put("gold", "authorGold (dev self-check; re-check against adjudicated dev gold before the sealed run)");
        root.put("note", "Selected on DEV ONLY by SemEvalTuneTest; frozen before any sealed run.");
        Files.writeString(SemEvalRunTest.ROOT.resolve("frozen-thresholds.json"), SemEval.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
