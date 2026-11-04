package app.dita.eval.scorer;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.arms.ArmId;
import app.dita.eval.context.ContextBuilder;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.gate.Manifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 0 scorer: a separate entry point that runs AFTER the runner, on its saved outputs. It re-runs the gate (manifest
 * hashes + synthetic checks), validates gold against prompts and corpora, adds the scorer-side O-gold oracle row and
 * writes {@code results/stage0-retrieval-<split>.json} and {@code .md}. Retrieval-only: no model output is scored.
 */
public final class Stage0Scorer {
    private Stage0Scorer() {}

    public record Output(Path json, Path md) {}

    public static Output run(BenchPaths paths, String split) throws IOException {
        ScorerFileAccess files = new ScorerFileAccess(paths);
        files.verifyManifestOrThrow();
        Map<String, GoldLabel> gold = files.gold(split);
        Map<String, Optional<Corpus>> cache = new HashMap<>();
        java.util.function.Function<String, Optional<Corpus>> corpora = id -> cache.computeIfAbsent(id, k -> {
            try { return Optional.of(files.corpus(k)); } catch (RuntimeException e) { return Optional.empty(); }
        });
        List<String> errors = GoldValidator.validate(split, gold, files.promptsRaw(split), corpora);
        if (!errors.isEmpty()) throw new IllegalStateException("gold validation failed: " + errors);

        Map<String, List<RetrievalMetrics.ContextRecord>> byArm = new LinkedHashMap<>();
        for (ArmId a : ArmId.values()) byArm.put(a.label, new ArrayList<>());
        for (String line : files.result("stage0-contexts-" + split + ".jsonl").split("\n")) {
            if (line.isBlank()) continue;
            JsonNode n = Json.parse(line);
            if (!"in-memory".equals(n.path("backend").asText())) throw new IllegalStateException("unexpected backend in runner output");
            byArm.computeIfAbsent(n.path("arm").asText(), k -> new ArrayList<>()).add(new RetrievalMetrics.ContextRecord(
                    n.path("qid").asText(), n.path("arm").asText(), n.path("backend").asText(), Json.stringList(n.get("context_ids")),
                    n.path("context_chars").asInt(), Json.stringList(n.get("retrieved_ids")), Json.stringList(n.get("dropped_ids"))));
        }
        for (Map.Entry<String, List<RetrievalMetrics.ContextRecord>> e : byArm.entrySet()) {
            if (e.getValue().size() != gold.size()) throw new IllegalStateException("arm " + e.getKey() + " has " + e.getValue().size() + " rows, expected " + gold.size());
        }
        List<RetrievalMetrics.ContextRecord> oracle = new ArrayList<>();
        for (GoldLabel g : gold.values()) {
            ContextBuilder.Built b = OracleContext.build(g, corpora.apply(g.corpusId()).orElseThrow());
            oracle.add(new RetrievalMetrics.ContextRecord(g.qid(), OracleContext.LABEL, "in-memory", b.ids(), b.chars(), List.of(), b.droppedIds()));
        }
        byArm.put(OracleContext.LABEL, oracle);

        JsonNode meta = Json.parse(files.result("stage0-runner-meta-" + split + ".json"));
        ObjectNode out = Json.object();
        out.put("title", "TASK-034 Stage 0: retrieval-only scoring");
        out.put("split", split);
        out.put("regime", "small");
        out.put("backend", "in-memory");
        out.put("retrieval_only", true);
        out.put("model", "none");
        out.put("synthetic", true);
        out.put("n_questions", gold.size());
        out.put("not_a_value_result", "Retrieval coverage on a small hand-authored dev set; says nothing about answer quality, cost or Dita's value. B-weak is a weak control, not real B.");
        out.set("arm_settings", meta.get("arm_settings"));
        out.set("arms_description", meta.get("arms"));
        out.set("retrieval_latency_ms", meta.get("retrieval_latency_ms"));
        out.set("index_build_ms", meta.get("index_build_ms"));
        out.put("timing_protocol", meta.path("timing_protocol").asText());
        out.put("runner_generated_at", meta.path("generated_at").asText());
        out.put("scored_at", Instant.now().toString());
        ObjectNode sealedHashes = out.putObject("sealed_and_input_sha256_at_scoring");
        Manifest.compute(paths).forEach(sealedHashes::put);
        ObjectNode arms = out.putObject("arms");
        for (Map.Entry<String, List<RetrievalMetrics.ContextRecord>> e : byArm.entrySet()) {
            arms.set(e.getKey(), RetrievalMetrics.score(e.getKey(), e.getValue(), gold, corpora));
        }
        Path json = files.writeResult("stage0-retrieval-" + split + ".json", Json.pretty(out) + "\n");
        Path md = files.writeResult("stage0-retrieval-" + split + ".md", markdown(out));
        return new Output(json, md);
    }

    static String markdown(ObjectNode r) {
        StringBuilder sb = new StringBuilder();
        JsonNode s = r.get("arm_settings");
        sb.append("# TASK-034 Stage 0: retrieval-only results (").append(r.get("split").asText()).append(")\n\n");
        sb.append("**").append(r.get("split").asText()).append(" split, small regime, in-memory backend, retrieval-only, no model, n=")
                .append(r.get("n_questions").asInt()).append(", synthetic.**\n\n");
        sb.append("This measures whether gold evidence reaches the context. It does not measure answers, cost or product value. ")
                .append("B-weak is a labelled weak control (BM25 + char-n-gram hashing). It is not the real arm B, which needs a multilingual embedder that does not exist yet. ")
                .append("A-full is complete by construction in the small regime, and O-gold is a sanity row (recall = 1 by construction). ")
                .append("Rows marked (diag) are not pre-registered arms. They cross the retriever with the +/-2 neighbour window, which DESIGN §1 gives only to B, ")
                .append("so a reader can see how much of a recall difference comes from context volume rather than ranking.\n\n");
        sb.append("Settings: T_ctx = ").append(s.path("t_ctx_chars").asInt()).append(" chars of rendered context JSON, k = ").append(s.path("k").asInt())
                .append(", candidate depth/retriever = ").append(s.path("candidate_depth_per_retriever").asInt())
                .append(", RRF k = ").append(s.path("rrf_k").asDouble()).append(", B neighbour window = +/-").append(s.path("b_neighbour_window").asInt())
                .append(", C neighbour window = 0, history access = ").append(s.path("history_access").asText()).append(".\n\n");
        sb.append("| Arm | n | n with gold | Context recall | AllGold within T_ctx | Forbidden in ctx (items) | Superseded w/o successor | Injection msgs in ctx (items) | Visibility violations | Ctx msgs (mean) | Ctx chars mean / p95 | Retrieval p50 / p95 ms |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        JsonNode lat = r.get("retrieval_latency_ms");
        r.get("arms").fields().forEachRemaining(e -> {
            JsonNode a = e.getValue();
            JsonNode l = lat.get(e.getKey());
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(a.get("n_questions").asInt())
                    .append(" | ").append(a.get("n_with_gold_evidence").asInt())
                    .append(" | ").append(fmt(a.get("context_recall_mean").asDouble()))
                    .append(" | ").append(fmt(a.get("all_gold_within_budget_rate").asDouble())).append(" (").append(a.get("all_gold_within_budget_count").asInt()).append("/").append(a.get("n_with_gold_evidence").asInt()).append(")")
                    .append(" | ").append(a.get("forbidden_in_context_count").asInt()).append(" (").append(a.get("forbidden_in_context_items").asInt()).append(")")
                    .append(" | ").append(a.get("superseded_without_successor_items").asInt()).append("/").append(a.get("n_with_superseded").asInt())
                    .append(" | ").append(a.get("injection_in_context_count").asInt()).append(" (").append(a.get("injection_in_context_items").asInt()).append(")")
                    .append(" | ").append(a.get("visibility_violations").asInt())
                    .append(" | ").append(fmt(a.get("context_messages_mean").asDouble()))
                    .append(" | ").append(Math.round(a.get("context_chars_mean").asDouble())).append(" / ").append(Math.round(a.get("context_chars_p95").asDouble()))
                    .append(" | ").append(l == null ? "n/a (scorer-side oracle)" : fmt3(l.get("p50").asDouble()) + " / " + fmt3(l.get("p95").asDouble()))
                    .append(" |\n");
        });
        JsonNode ib = r.get("index_build_ms");
        sb.append("\nIndex build (").append(ib.path("what").asText()).append("): ").append(ib.path("n_builds").asInt())
                .append(" (corpus, as_of) builds, ").append(ib.path("n_samples").asInt()).append(" timed samples, p50 ")
                .append(fmt3(ib.path("p50").asDouble())).append(" ms, p95 ").append(fmt3(ib.path("p95").asDouble())).append(" ms, max ")
                .append(fmt3(ib.path("max").asDouble())).append(" ms. Timing: ").append(r.path("timing_protocol").asText()).append(".\n\n");

        sb.append("## AllGold within T_ctx by scenario (descriptive only; cells are tiny)\n\n");
        List<String> armNames = new ArrayList<>();
        r.get("arms").fieldNames().forEachRemaining(armNames::add);
        java.util.TreeSet<String> scen = new java.util.TreeSet<>();
        r.get("arms").forEach(a -> a.get("by_scenario").fieldNames().forEachRemaining(scen::add));
        sb.append("| Scenario | n |");
        armNames.forEach(a -> sb.append(' ').append(a).append(" |"));
        sb.append("\n|---|---|");
        armNames.forEach(a -> sb.append("---|"));
        sb.append('\n');
        for (String sc : scen) {
            JsonNode first = r.get("arms").get(armNames.get(0)).get("by_scenario").get(sc);
            sb.append("| ").append(sc).append(" | ").append(first == null ? 0 : first.get("n").asInt()).append(" |");
            for (String a : armNames) {
                JsonNode c = r.get("arms").get(a).get("by_scenario").get(sc);
                sb.append(' ').append(c == null ? "-" : fmt(c.get("all_gold_rate").asDouble())).append(" |");
            }
            sb.append('\n');
        }
        sb.append("\n## Context recall by lexical-overlap bucket (question vs gold evidence tokens)\n\n| Bucket | n |");
        armNames.forEach(a -> sb.append(' ').append(a).append(" |"));
        sb.append("\n|---|---|");
        armNames.forEach(a -> sb.append("---|"));
        sb.append('\n');
        for (String b : List.of("low", "mid", "high")) {
            JsonNode first = r.get("arms").get(armNames.get(0)).get("by_overlap_bucket").get(b);
            if (first == null) continue;
            sb.append("| ").append(b).append(" | ").append(first.get("n").asInt()).append(" |");
            for (String a : armNames) sb.append(' ').append(fmt(r.get("arms").get(a).get("by_overlap_bucket").get(b).get("recall").asDouble())).append(" |");
            sb.append('\n');
        }
        sb.append("\nSource: `results/stage0-retrieval-").append(r.get("split").asText()).append(".json` (per-item ids in `items`). Runner output generated ")
                .append(r.path("runner_generated_at").asText()).append(", scored ").append(r.path("scored_at").asText()).append(".\n");
        return sb.toString();
    }

    private static String fmt(double d) { return Double.isNaN(d) ? "n/a" : String.format(Locale.ROOT, "%.3f", d); }
    private static String fmt3(double d) { return Double.isNaN(d) ? "n/a" : String.format(Locale.ROOT, "%.3f", d); }

    public static void main(String[] args) throws IOException {
        String split = args.length > 0 ? args[0] : "dev";
        Output o = run(BenchPaths.locate(), split);
        System.out.println("Stage0Scorer: wrote " + o.json() + " and " + o.md());
    }
}
