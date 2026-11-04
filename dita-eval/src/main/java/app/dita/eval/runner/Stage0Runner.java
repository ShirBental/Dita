package app.dita.eval.runner;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.arms.ArmContextFactory;
import app.dita.eval.arms.ArmId;
import app.dita.eval.arms.ArmSettings;
import app.dita.eval.arms.StoreReplay;
import app.dita.eval.Stats;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stage 0 retrieval-only runner (no model, no network). Opens only prompts/ and corpora/ (via {@link RunnerFileAccess})
 * and writes the per-(question, arm) context ids plus timings to results/. The scorer, a separate entry point, reads
 * them together with the sealed gold.
 * <p>Timing protocol (BENCHMARK.md): 1 warm-up and then 5 timed runs, per (question, arm) for retrieval + context
 * building, and per (corpus, as_of) for the index build (store replay with link inference and deletions). The arm
 * order is shuffled per question with a fixed seed.
 */
public final class Stage0Runner {
    public static final int WARMUP = 1, RUNS = 5;
    public static final long SEED = 34L;

    private Stage0Runner() {}

    public record Output(Path contexts, Path meta, int questions) {}

    public static Output run(BenchPaths paths, String split, ArmSettings settings) {
        RunnerFileAccess files = new RunnerFileAccess(paths);
        ContextPlanner planner = new ContextPlanner(files, settings);
        List<Prompt> prompts = planner.prompts(split);

        Map<String, List<Double>> buildMs = new LinkedHashMap<>();
        for (Prompt p : prompts) {
            String key = ContextPlanner.replayKey(p);
            if (buildMs.containsKey(key)) continue;
            List<Double> samples = new ArrayList<>();
            StoreReplay.Replayed last = null;
            for (int i = 0; i < WARMUP + RUNS; i++) {
                last = planner.replayFresh(p);
                if (i >= WARMUP) samples.add(last.buildNanos() / 1e6);
            }
            planner.putReplay(p, last);
            buildMs.put(key, samples);
        }

        Map<ArmId, List<Double>> latency = new EnumMap<>(ArmId.class);
        StringBuilder jsonl = new StringBuilder();
        Random rnd = new Random(SEED);
        for (Prompt p : prompts) {
            List<ArmId> order = new ArrayList<>(Arrays.asList(ArmId.values()));
            Collections.shuffle(order, rnd);
            Map<ArmId, ArmContextFactory.ArmContext> results = new EnumMap<>(ArmId.class);
            for (ArmId arm : order) {
                ArmContextFactory.ArmContext ctx = null;
                for (int i = 0; i < WARMUP + RUNS; i++) {
                    long t0 = System.nanoTime();
                    ArmContextFactory.ArmContext c = planner.context(arm, p);
                    long dt = System.nanoTime() - t0;
                    if (i >= WARMUP) latency.computeIfAbsent(arm, a -> new ArrayList<>()).add(dt / 1e6);
                    if (ctx != null && !ctx.context().ids().equals(c.context().ids()))
                        throw new IllegalStateException("non-deterministic context for " + p.qid() + "/" + arm);
                    ctx = c;
                }
                results.put(arm, ctx);
            }
            for (ArmId arm : ArmId.values()) {
                ArmContextFactory.ArmContext c = results.get(arm);
                ObjectNode row = Json.object();
                row.put("qid", p.qid()).put("arm", arm.label).put("backend", StoreReplay.BACKEND)
                        .put("history_access", ArmSettings.HISTORY_ACCESS);
                row.set("context_ids", Json.strings(c.context().ids()));
                row.put("context_chars", c.context().chars());
                row.set("retrieved_ids", Json.strings(c.retrievedIds()));
                row.set("dropped_ids", Json.strings(c.context().droppedIds()));
                jsonl.append(Json.compact(row)).append('\n');
            }
        }

        ObjectNode meta = Json.object();
        meta.put("stage", "0");
        meta.put("split", split);
        meta.put("regime", "small");
        meta.put("synthetic", true);
        meta.put("retrieval_only", true);
        meta.put("model", "none (no model calls)");
        meta.put("backend", StoreReplay.BACKEND);
        meta.put("n_questions", prompts.size());
        meta.set("arm_settings", settings.toJson());
        ObjectNode arms = meta.putObject("arms");
        for (ArmId a : ArmId.values()) arms.put(a.label, a.description);
        meta.put("timing_protocol", WARMUP + " warm-up + " + RUNS + " timed runs per (question, arm); per (corpus, as_of) for index build; wall clock, single JVM, System.nanoTime");
        ObjectNode lat = meta.putObject("retrieval_latency_ms");
        latency.forEach((arm, xs) -> {
            ObjectNode o = lat.putObject(arm.label);
            o.put("n_samples", xs.size());
            o.put("p50", Stats.round(Stats.percentile(xs, 50), 3));
            o.put("p95", Stats.round(Stats.percentile(xs, 95), 3));
            o.put("mean", Stats.round(Stats.mean(xs), 3));
        });
        List<Double> allBuild = new ArrayList<>();
        buildMs.values().forEach(allBuild::addAll);
        ObjectNode ib = meta.putObject("index_build_ms");
        ib.put("what", "in-memory store replay to as_of: ingest + RuleLinkInferencer links + DeletionService deletions (corpus + decoy)");
        ib.put("n_builds", buildMs.size());
        ib.put("n_samples", allBuild.size());
        ib.put("p50", Stats.round(Stats.percentile(allBuild, 50), 3));
        ib.put("p95", Stats.round(Stats.percentile(allBuild, 95), 3));
        ib.put("mean", Stats.round(Stats.mean(allBuild), 3));
        ib.put("max", Stats.round(Stats.max(allBuild), 3));
        ObjectNode per = ib.putObject("per_corpus_as_of_p50");
        buildMs.forEach((k, xs) -> per.put(k, Stats.round(Stats.percentile(xs, 50), 3)));
        meta.put("java_version", System.getProperty("java.version"));
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        meta.put("generated_at", Instant.now().toString());

        Path ctx = files.writeResult("stage0-contexts-" + split + ".jsonl", jsonl.toString());
        Path m = files.writeResult("stage0-runner-meta-" + split + ".json", Json.pretty(meta) + "\n");
        return new Output(ctx, m, prompts.size());
    }

    public static void main(String[] args) {
        String split = args.length > 0 ? args[0] : "dev";
        Output o = run(BenchPaths.locate(), split, ArmSettings.STAGE0);
        System.out.println("Stage0Runner: " + o.questions() + " questions x " + ArmId.values().length + " arms -> " + o.contexts() + ", " + o.meta());
    }
}
