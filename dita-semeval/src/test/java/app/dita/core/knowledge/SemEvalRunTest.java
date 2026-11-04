package app.dita.core.knowledge;

import app.dita.core.model.Assertion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TASK-046 evaluation driver (EXPERIMENT ONLY; -P semeval). Properties:
 * <ul>
 *   <li>{@code dita.semeval.set} = dev (default) | sealed. SEALED prints and writes AGGREGATES ONLY (no case text, no ids).</li>
 *   <li>{@code dita.semeval.gold} = author (dev self-check only) | adjudicated (gold-adjudicated.json next to the cases).</li>
 *   <li>{@code dita.semeval.arms} = comma list of A1,A2,A4,A3,A5,A6 (A3/A5/A6 need recordings; otherwise NOT RUN).</li>
 * </ul>
 * Outputs to target/semeval-&lt;set&gt;/: results.jsonl (dev only), summary.md, summary.json.
 */
class SemEvalRunTest {
    static final Path ROOT = Path.of("../eval/TASK-046/semeval");

    @Test void run() throws Exception {
        String set = System.getProperty("dita.semeval.set", "dev");
        Path dir = ROOT.resolve(set);
        Assumptions.assumeTrue(Files.isDirectory(dir), "no case set " + dir);
        boolean sealed = set.equals("sealed");
        String goldMode = System.getProperty("dita.semeval.gold", "author");
        if (sealed && !goldMode.equals("adjudicated")) throw new IllegalStateException("the sealed run requires adjudicated gold");
        List<JsonNode> cases = SemEval.load(dir);
        Map<String, JsonNode> gold = new LinkedHashMap<>();
        if (goldMode.equals("adjudicated")) {
            JsonNode adj = SemEval.JSON.readTree(Files.readString(dir.resolve("gold-adjudicated.json")));
            adj.properties().forEach(e -> gold.put(e.getKey(), e.getValue()));
        } else for (JsonNode c : cases) gold.put(c.path("id").asText(), c.path("authorGold"));

        JsonNode thresholds = SemEval.JSON.readTree(Files.readString(ROOT.resolve("frozen-thresholds.json")));
        List<String> arms = List.of(System.getProperty("dita.semeval.arms", "A1,A2,A4").split(","));
        Path out = Path.of("target/semeval-" + set);
        Files.createDirectories(out);
        StringBuilder jsonl = new StringBuilder();
        ObjectNode summary = SemEval.JSON.createObjectNode();
        StringBuilder md = new StringBuilder("# TASK-046 semantic evaluation: " + set + " (gold: " + goldMode + ")\n\n"
                + "| arm | n | E1 false invalidation | E2 unsupported promotion | E3 replacement detection | E4 incorrect answer | E4o omissions | UNCERTAIN pairs | relation latency p50/p95 per pair | deterministic |\n|---|---|---|---|---|---|---|---|---|---|\n");

        try (NliModel nli = arms.contains("A4") ? new NliModel() : null) {
            for (String armId : arms) {
                Optional<Runner> runner = runner(armId.strip(), nli, thresholds, set);
                if (runner.isEmpty()) { md.append("| ").append(armId).append(" | NOT RUN (no recording / not available) |||||||||\n"); continue; }
                List<SemEval.Outcome> first = runAll(cases, gold, runner.get());
                List<SemEval.Outcome> second = armId.startsWith("A3") || armId.startsWith("A5") || armId.startsWith("A6") ? first : runAll(cases, gold, runner.get());
                boolean deterministic = signature(first).equals(signature(second));
                if (!sealed) for (SemEval.Outcome o : first) jsonl.append(o.json()).append('\n');
                Stats st = Stats.of(first);
                summary.set(armId, st.json(deterministic));
                md.append(st.row(armId, deterministic));
            }
        }
        if (!sealed) Files.writeString(out.resolve("results.jsonl"), jsonl.toString());
        Files.writeString(out.resolve("summary.md"), md.toString());
        Files.writeString(out.resolve("summary.json"), SemEval.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    record Runner(RelationArms.Arm arm, SemEval.StatusArm status, java.util.function.Function<JsonNode, List<Proposal.Item>> items, double minConfidence) {}

    static Optional<Runner> runner(String id, NliModel nli, JsonNode th, String set) throws Exception {
        switch (id) {
            case "A1": return Optional.of(new Runner(new RelationArms.Rules(), SemEval.NO_STATUS_ARM, SemEval::fixtureItems, 0));
            case "A2": return Optional.of(new Runner(new FrameArm(), SemEval.NO_STATUS_ARM, SemEval::fixtureItems, 0));
            case "A4": {
                JsonNode t = th.path("A4");
                var arm = new RelationArms.Nli(nli, t.path("tRep").asDouble(), t.path("tSame").asDouble(), t.path("tDiff").asDouble());
                return Optional.of(new Runner(arm, new NliStatus(nli, t.path("tStatus").asDouble()), SemEval::fixtureItems, 0));
            }
            default: {
                Optional<MuseRecordings> rec = MuseRecordings.load(ROOT.resolve("recordings").resolve(set), id);
                if (rec.isEmpty()) return Optional.empty();
                if (id.equals("A5")) {
                    Optional<MuseRecordings> a3 = MuseRecordings.load(ROOT.resolve("recordings").resolve(set), "A3");
                    if (a3.isEmpty()) return Optional.empty();
                    return Optional.of(new Runner(new Conjunction(new FrameArm(), a3.get().arm("A3")), SemEval.NO_STATUS_ARM, SemEval::fixtureItems, 0));
                }
                if (id.equals("A6")) return Optional.of(new Runner(rec.get().arm("A6"), SemEval.NO_STATUS_ARM, rec.get()::items, 0));
                return Optional.of(new Runner(rec.get().arm(id), SemEval.NO_STATUS_ARM, SemEval::fixtureItems, 0));
            }
        }
    }

    static List<SemEval.Outcome> runAll(List<JsonNode> cases, Map<String, JsonNode> gold, Runner r) throws Exception {
        List<SemEval.Outcome> out = new ArrayList<>();
        for (JsonNode c : cases) {
            JsonNode gd = gold.get(c.path("id").asText());
            if (gd == null || gd.isMissingNode() || gd.path("invalid").asBoolean(false)) continue;       // invalid cases are excluded before any arm runs
            List<Proposal.Item> items = r.items().apply(c);
            out.add(SemEval.run(c, gd, r.arm(), r.status(), items, r.minConfidence()));
        }
        return out;
    }

    static String signature(List<SemEval.Outcome> os) {
        StringBuilder sb = new StringBuilder();
        for (SemEval.Outcome o : os) sb.append(o.caseId()).append(o.e1Fail()).append(o.e2Fail()).append(o.e3Success()).append(o.e4Fail()).append(o.notes()).append('\n');
        return sb.toString();
    }

    /** A5: replaces only when A2 AND A3 both say YES/YES; any disagreement UNCERTAIN (pre-registered, no tuning). */
    record Conjunction(RelationArms.Arm a, RelationArms.Arm b) implements RelationArms.Arm {
        public String id() { return "A5-frame∧muse"; }
        public RelationArms.Proposal propose(app.dita.core.model.GroupScope g, app.dita.core.store.MessageStore s, RelationArms.Pair p) throws Exception {
            var x = a.propose(g, s, p); var y = b.propose(g, s, p);
            boolean both = x.replaces() == RelationArms.Tri.YES && y.replaces() == RelationArms.Tri.YES
                    && x.sameProperty() == RelationArms.Tri.YES && y.sameProperty() == RelationArms.Tri.YES;
            boolean agreeNo = x.replaces() == RelationArms.Tri.NO && y.replaces() == RelationArms.Tri.NO;
            RelationArms.Tri r = both ? RelationArms.Tri.YES : agreeNo ? RelationArms.Tri.NO : RelationArms.Tri.UNCERTAIN;
            return new RelationArms.Proposal(p, both ? RelationArms.Tri.YES : RelationArms.Tri.UNCERTAIN, r, y.evidenceQuotes(), Math.min(x.confidence(), y.confidence()), id(), x.nanos() + y.nanos());
        }
    }

    /** A4 status: downgrade-only zero-shot check on the item's FULL message (frozen threshold). */
    record NliStatus(NliModel nli, double tStatus) implements SemEval.StatusArm {
        static final Map<Assertion.Status, String> HYPOTHESIS = Map.of(
                Assertion.Status.DECISION, "A final decision has been made.",
                Assertion.Status.CONFIRMED_COMMITMENT, "The speaker definitely promises to do it.",
                Assertion.Status.OFFER, "The speaker offers to do it.");
        public Optional<Assertion.Status> downgrade(app.dita.core.model.GroupScope g, app.dita.core.store.InMemoryMessageStore s,
                                                    app.dita.core.model.KnowledgeItem k) throws Exception {
            String h = HYPOTHESIS.get(k.assertion().status());
            if (h == null) return Optional.empty();
            String text = RelationArms.fullText(g, s, k);
            if (nli.score(text, h).entailment() >= tStatus) return Optional.empty();
            return Optional.of(k.assertion().status() == Assertion.Status.CONFIRMED_COMMITMENT
                    && nli.score(text, HYPOTHESIS.get(Assertion.Status.OFFER)).entailment() >= tStatus ? Assertion.Status.OFFER : Assertion.Status.ATTRIBUTED_STATEMENT);
        }
    }

    /** Pre-registered statistics (PREREGISTRATION.md §3). */
    record Stats(int n, int e1, int lureN, int e2, int repN, int e3, int e4, int e4o, int pairs, int unc, List<Long> relNs) {
        static Stats of(List<SemEval.Outcome> os) {
            List<Long> rel = new ArrayList<>();
            int pairs = 0, unc = 0;
            for (SemEval.Outcome o : os) { pairs += o.pairs(); unc += o.uncertain(); if (o.pairs() > 0) rel.add(o.relationNanos() / o.pairs()); }
            Collections.sort(rel);
            return new Stats(os.size(), (int) os.stream().filter(SemEval.Outcome::e1Fail).count(),
                    (int) os.stream().filter(SemEval.Outcome::lure).count(), (int) os.stream().filter(o -> o.lure() && o.e2Fail()).count(),
                    (int) os.stream().filter(SemEval.Outcome::replacesTarget).count(), (int) os.stream().filter(SemEval.Outcome::e3Success).count(),
                    (int) os.stream().filter(SemEval.Outcome::e4Fail).count(), (int) os.stream().filter(SemEval.Outcome::e4Omission).count(), pairs, unc, rel);
        }
        String row(String arm, boolean det) {
            return String.format("| %s | %d | %d/%d (UB %.3f) | %d/%d (UB %.3f) | %d/%d (%.3f; WilsonLB %.3f) | %d/%d (UB %.3f) | %d | %d/%d | %s | %s |%n",
                    arm, n, e1, n, cpUpper(e1, n), e2, lureN, cpUpper(e2, lureN), e3, repN, repN == 0 ? 0 : (double) e3 / repN, wilsonLower(e3, repN),
                    e4, n, cpUpper(e4, n), e4o, unc, pairs, latency(), det ? "yes" : "NO");
        }
        ObjectNode json(boolean det) {
            ObjectNode o = SemEval.JSON.createObjectNode();
            o.put("n", n).put("e1", e1).put("e1UB", cpUpper(e1, n)).put("lureN", lureN).put("e2", e2).put("e2UB", cpUpper(e2, lureN))
                    .put("repN", repN).put("e3", e3).put("e3WilsonLB", wilsonLower(e3, repN)).put("e4", e4).put("e4UB", cpUpper(e4, n))
                    .put("e4o", e4o).put("pairs", pairs).put("uncertainPairs", unc).put("relationLatency", latency()).put("deterministic", det);
            return o;
        }
        String latency() {
            if (relNs.isEmpty()) return "-";
            return String.format("%.3f / %.3f ms", relNs.get(relNs.size() / 2) / 1e6, relNs.get(Math.min(relNs.size() - 1, (int) Math.ceil(relNs.size() * 0.95) - 1)) / 1e6);
        }
    }

    /** One-sided 95% Clopper–Pearson upper bound (Beta(x+1, n−x) 0.95 quantile) via the binomial identity. */
    static double cpUpper(int x, int n) {
        if (n == 0) return Double.NaN;
        if (x >= n) return 1.0;
        double lo = 0, hi = 1;
        for (int i = 0; i < 100; i++) { double mid = (lo + hi) / 2; if (binomCdf(x, n, mid) > 0.05) lo = mid; else hi = mid; }
        return hi;
    }

    static double binomCdf(int x, int n, double p) {
        double sum = 0;
        for (int k = 0; k <= x; k++) sum += Math.exp(logChoose(n, k) + k * Math.log(p) + (n - k) * Math.log1p(-p));
        return sum;
    }

    static double logChoose(int n, int k) { double s = 0; for (int i = 1; i <= k; i++) s += Math.log(n - k + i) - Math.log(i); return s; }

    /** One-sided Wilson lower bound, z = 1.645. */
    static double wilsonLower(int s, int n) {
        if (n == 0) return Double.NaN;
        double z = 1.645, p = (double) s / n, d = 1 + z * z / n, c = p + z * z / (2 * n), r = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n));
        return (c - r) / d;
    }
}
