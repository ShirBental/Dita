package app.dita.eval;

import app.dita.eval.answer.MockAnswerer;
import app.dita.eval.answer.ModelAnswerer;
import app.dita.eval.answer.ModelRequest;
import app.dita.eval.arms.ArmId;
import app.dita.eval.arms.ArmSettings;
import app.dita.eval.gate.Manifest;
import app.dita.eval.gate.SyntheticDataGate;
import app.dita.eval.guard.CanaryGuard;
import app.dita.eval.guard.LeakageException;
import app.dita.eval.guard.Sentinels;
import app.dita.eval.runner.AnswerRunner;
import app.dita.eval.runner.ContextPlanner;
import app.dita.eval.runner.RunnerFileAccess;
import app.dita.eval.runner.Stage0Runner;
import app.dita.eval.scorer.AnswerMetrics;
import app.dita.eval.scorer.GoldLabel;
import app.dita.eval.scorer.ScorerFileAccess;
import app.dita.eval.scorer.SentinelSealer;
import app.dita.eval.scorer.Stage0Scorer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline end-to-end on a temp copy of the benchmark: gate → retrieval runner → scorer, and gate → guarded mock answer
 * loop → answer scorer. Mock scores are plumbing checks only and are never written to the real results/.
 */
class EndToEndMockTest {
    @TempDir Path tmp;

    @Test void retrievalPipelineRunsOfflineAndScores() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        assertTrue(new SyntheticDataGate(p).run().passed());
        Stage0Runner.Output r = Stage0Runner.run(p, "dev", ArmSettings.STAGE0);
        assertEquals(35, r.questions());
        Stage0Scorer.Output s = Stage0Scorer.run(p, "dev");
        JsonNode out = Json.parse(Files.readString(s.json()));
        assertEquals("in-memory", out.get("backend").asText());
        assertTrue(out.get("retrieval_only").asBoolean());
        for (String arm : List.of("A-full", "A-budget", "B-weak control", "C-today", "C-graph-off", "O-gold")) {
            JsonNode a = out.get("arms").get(arm);
            assertNotNull(a, arm);
            assertEquals(0, a.get("visibility_violations").asInt(), arm);
            assertEquals(0, a.get("forbidden_in_context_count").asInt(), arm + ": decoy/deleted/future ids must never reach context");
        }
        assertEquals(1.0, out.get("arms").get("A-full").get("context_recall_mean").asDouble());
        assertEquals(1.0, out.get("arms").get("O-gold").get("context_recall_mean").asDouble());
        assertTrue(Files.readString(s.md()).contains("dev split, small regime, in-memory backend, retrieval-only, no model, n=35, synthetic"));
    }

    @Test void scorerRefusesWhenSealedGoldChangedAfterManifest() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        Stage0Runner.run(p, "dev", ArmSettings.STAGE0);
        Files.writeString(p.sealedGold().resolve("dev.jsonl"), Files.readString(p.sealedGold().resolve("dev.jsonl")) + "\n");
        assertThrows(IllegalStateException.class, () -> Stage0Scorer.run(p, "dev"));
    }

    @Test void guardedMockAnswerLoopAndAnswerScoring() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        assertTrue(new SyntheticDataGate(p).run().passed());
        ScorerFileAccess scorerFiles = new ScorerFileAccess(p);
        Map<String, GoldLabel> gold = scorerFiles.gold("dev");
        CanaryGuard guard = new CanaryGuard(SentinelSealer.seal(gold.values()));
        List<ArmId> arms = Arrays.asList(ArmId.values());
        List<AnswerRunner.Row> rows = new AnswerRunner(new ContextPlanner(new RunnerFileAccess(p), ArmSettings.STAGE0), guard, new MockAnswerer())
                .run("dev", arms);
        assertEquals(35 * arms.size(), rows.size());
        assertEquals(rows.size(), guard.requestsChecked(), "every request, the mock's included, passed through the guard");
        assertTrue(rows.stream().allMatch(AnswerRunner.Row::mock));

        for (ArmId arm : arms) {
            for (boolean gated : new boolean[]{false, true}) {
                List<AnswerMetrics.ItemScore> items = new ArrayList<>();
                for (AnswerRunner.Row row : rows) {
                    if (!row.arm().equals(arm.label)) continue;
                    GoldLabel g = gold.get(row.qid());
                    items.add(AnswerMetrics.scoreItem(g, scorerFiles.corpus(g.corpusId()), row.content(), row.finishReason(), gated));
                }
                assertEquals(35, items.size());
                assertTrue(items.stream().allMatch(AnswerMetrics.ItemScore::formatOk), "mock output must parse");
                ObjectNode agg = AnswerMetrics.aggregate(arm.label, gated, items, gold, true);
                assertTrue(agg.get("WARNING").asText().contains("NOT A RESULT"));
            }
        }
        assertFalse(Files.exists(BenchPaths.locate().results().resolve("mock-answers-dev.jsonl")));
    }

    @Test void plantedCanaryAbortsTheRunBeforeTheAnswererSeesIt() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        Map<String, GoldLabel> gold = new ScorerFileAccess(p).gold("dev");
        String canary = gold.values().iterator().next().canary();
        Path prompts = p.prompts().resolve("dev.jsonl");
        String text = Files.readString(prompts);
        Files.writeString(prompts, text.replaceFirst("\"question\": \"", "\"question\": \"" + canary + " "));
        Manifest.write(p);
        AtomicInteger calls = new AtomicInteger();
        ModelAnswerer counting = new ModelAnswerer() {
            final MockAnswerer m = new MockAnswerer();
            public String name() { return "counting-mock"; }
            public boolean isMock() { return true; }
            public Reply answer(ModelRequest r) { calls.incrementAndGet(); return m.answer(r); }
        };
        AnswerRunner runner = new AnswerRunner(new ContextPlanner(new RunnerFileAccess(p), ArmSettings.STAGE0),
                new CanaryGuard(SentinelSealer.seal(gold.values())), counting);
        LeakageException e = assertThrows(LeakageException.class, () -> runner.run("dev", List.of(ArmId.A_FULL)));
        assertFalse(e.getMessage().contains(canary));
        assertEquals(0, calls.get(), "the first prompt carried the canary, so no request reached the answerer");
    }

    @Test void plantedCanaryInCorpusTextAlsoTrips() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        Map<String, GoldLabel> gold = new ScorerFileAccess(p).gold("dev");
        Sentinels s = SentinelSealer.seal(gold.values());
        Path c = p.corpora().resolve("family-en.jsonl");
        Files.writeString(c, Files.readString(c).replace("Counting down to the 14th!", "Counting down " + gold.get("F01").canary()));
        AnswerRunner runner = new AnswerRunner(new ContextPlanner(new RunnerFileAccess(p), ArmSettings.STAGE0), new CanaryGuard(s), new MockAnswerer());
        assertThrows(LeakageException.class, () -> runner.run("dev", List.of(ArmId.A_FULL)));
    }
}
