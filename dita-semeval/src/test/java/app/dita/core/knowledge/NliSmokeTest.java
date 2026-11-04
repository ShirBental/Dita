package app.dita.core.knowledge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pinned NLI model loads offline, behaves sanely on textbook pairs, and its CPU latency is measured (not estimated). */
class NliSmokeTest {
    @Test void loadsOfflineScoresTextbookPairsAndMeasuresLatency() throws Exception {
        try (NliModel m = new NliModel()) {
            var c = m.score("The picnic is on Friday.", "The picnic is on Saturday.");
            var e = m.score("The picnic is on Friday at the lake.", "The picnic is on Friday.");
            assertTrue(c.contradiction() > c.entailment(), "contradiction expected: " + c);
            assertTrue(e.entailment() > e.contradiction(), "entailment expected: " + e);
            for (int i = 0; i < 5; i++) m.score("Warm up the session.", "Warm up.");
            List<Long> ns = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                long t = System.nanoTime();
                m.score("I can bring chairs to the picnic on Friday morning.", "The picnic budget is fifty dollars.");
                ns.add(System.nanoTime() - t);
            }
            Collections.sort(ns);
            String line = String.format("NLI latency per pair (1 thread, n=50): p50 %.1f ms, p95 %.1f ms; contradiction=%s entailment=%s%n",
                    ns.get(25) / 1e6, ns.get(47) / 1e6, c, e);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/nli-smoke.txt"), line);
        }
    }
}
