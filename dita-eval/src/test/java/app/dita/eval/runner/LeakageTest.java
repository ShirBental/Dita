package app.dita.eval.runner;

import app.dita.eval.BenchPaths;
import app.dita.eval.TestBench;
import app.dita.eval.answer.AnswerPrompt;
import app.dita.eval.answer.ModelRequest;
import app.dita.eval.arms.ArmId;
import app.dita.eval.guard.CanaryGuard;
import app.dita.eval.guard.LeakageException;
import app.dita.eval.guard.Sentinels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** N-50: the runner opens only prompts/ and corpora/; CanaryGuard trips on leaked labels. */
class LeakageTest {
    @TempDir Path tmp;

    @Test void runnerReadsPromptsAndCorporaOnly() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        RunnerFileAccess f = new RunnerFileAccess(p);
        assertFalse(f.prompts("dev").isEmpty());
        assertFalse(f.corpus("parents-b").messages().isEmpty());
        for (Path refused : List.of(p.sealedGold().resolve("dev.jsonl"), p.manifest(), p.sealed().resolve("test/README.md"),
                p.prompts().resolve("../sealed/gold/dev.jsonl"), p.dataDir().resolve("x.jsonl"), p.results().resolve("x.json"))) {
            assertThrows(LeakageException.class, () -> f.readText(refused), refused::toString);
        }
    }

    @Test void runnerRefusesSymlinkFromCorporaIntoSealed() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        Files.createSymbolicLink(p.corpora().resolve("sneaky.jsonl"), p.sealedGold().resolve("dev.jsonl"));
        RunnerFileAccess f = new RunnerFileAccess(p);
        assertThrows(LeakageException.class, () -> f.corpus("sneaky"));
    }

    @Test void runnerWritesOnlyToResults() throws Exception {
        BenchPaths p = TestBench.copyReal(tmp);
        RunnerFileAccess f = new RunnerFileAccess(p);
        assertThrows(IllegalArgumentException.class, () -> f.writeResult("../sealed/gold/dev.jsonl", "x"));
        assertTrue(f.writeResult("ok.json", "{}").startsWith(p.results()));
    }

    @Test void promptsWithLabelFieldsAreRejected() {
        String leaky = "{\"qid\":\"Q\",\"corpus_id\":\"c\",\"as_of\":\"2026-01-01T00:00:00Z\",\"question\":\"q\",\"answer_keys\":[[\"x\"]]}";
        assertThrows(IllegalArgumentException.class, () -> Prompt.parseAll(leaky));
    }

    /** Structural check: runner-side packages never reference sealed paths or scorer classes. */
    @Test void runnerSideSourcesNeverTouchSealedOrScorer() throws Exception {
        Path src = BenchPaths.locate().projectRoot().resolve("dita-eval/src/main/java/app/dita/eval");
        for (String pkg : List.of("runner", "arms", "context", "answer", "guard", "corpus")) {
            try (Stream<Path> s = Files.walk(src.resolve(pkg))) {
                for (Path f : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String code = Files.readString(f);
                    assertFalse(code.contains("sealedGold()") || code.contains("sealed()") || code.contains("import app.dita.eval.scorer"),
                            "runner-side file references sealed/ or scorer: " + f.getFileName());
                }
            }
        }
    }

    static ModelRequest request(String question, String context) {
        return AnswerPrompt.render(new Prompt("Q1", "c", Instant.parse("2026-01-01T00:00:00Z"), question), ArmId.C_TODAY, context);
    }

    @Test void guardPassesCleanRequest() {
        CanaryGuard g = new CanaryGuard(Sentinels.NONE);
        g.check(request("Where is the meeting?", "[]"));
        assertEquals(1, g.requestsChecked());
    }

    @Test void guardTripsOnCanaryShapedTokenAnywhere() {
        CanaryGuard g = new CanaryGuard(Sentinels.NONE);
        LeakageException e = assertThrows(LeakageException.class, () -> g.check(request("Where? DITA-CANARY-00112233445566AA", "[]")));
        assertFalse(e.getMessage().contains("00112233445566AA"), "message must not echo the leaked token");
        assertThrows(LeakageException.class, () -> g.check(request("Where?", "[{\"text\":\"dita-canary-00112233445566aa\"}]")));
    }

    @Test void guardTripsOnGoldLabelFieldNames() {
        CanaryGuard g = new CanaryGuard(Sentinels.NONE);
        assertThrows(LeakageException.class, () -> g.check(request("Where?", "[{\"expected_behavior\":\"ANSWER\"}]")));
    }

    @Test void guardTripsOnHashedSealedCanary() {
        CanaryGuard g = new CanaryGuard(new Sentinels(Set.of(Sentinels.hash("zebra quartz 77")), Set.of()));
        assertThrows(LeakageException.class, () -> g.check(request("Where?", "[{\"text\":\"note: Zebra-Quartz 77\"}]")));
    }

    @Test void answerStringsInQuestionOrContextAreNotLeakage() {
        CanaryGuard g = new CanaryGuard(new Sentinels(Set.of(), Set.of(Sentinels.hash("Gizmo Dome"))));
        g.check(request("Is it at the Gizmo Dome?", "[{\"text\":\"the Gizmo Dome\"}]"));
        assertEquals(1, g.requestsChecked());
    }
}
