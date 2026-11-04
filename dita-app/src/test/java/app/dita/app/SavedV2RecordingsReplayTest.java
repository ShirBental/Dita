package app.dita.app;

import app.dita.core.answer.synthesis.GroundedSynthesis.Outcome;
import app.dita.muse.MuseAnswerSynthesizer.Prompt;
import app.dita.muse.MuseTransport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054: replays the SAVED live v2 Muse responses (build/controlled-synthesis-cedar-v2/postgres/qN, read-only)
 * through the production answer path on IN_MEMORY. The request Dita builds must equal the saved PostgreSQL request byte
 * for byte, so the replay checks the same input Muse actually received. Not a new live call; the PostgreSQL stack itself
 * is not exercised here.
 */
class SavedV2RecordingsReplayTest {
    static final Path DIR = SynthesisTrialHarness.ROOT.resolve("build/controlled-synthesis-cedar-v2/postgres");

    @org.junit.jupiter.api.AfterEach void restoreCallBudget() { app.dita.muse.MuseCallCapTestAccess.reset(); }

    @Test void savedLiveResponsesReplayThroughTheProductionPath() throws Exception {
        assumeTrue(SynthesisTrialHarness.extractionArtifactsPresent(), "saved extraction artifacts absent: NOT EXECUTED");
        List<String> qids = new ArrayList<>();
        for (String q : List.of("q1", "q2", "q3", "q4"))
            if (Files.isRegularFile(DIR.resolve(q).resolve("synthesis-recording.json"))) qids.add(q);
        assumeTrue(!qids.isEmpty(), "no saved v2 responses: NOT EXECUTED");
        byte[] before = digest(qids);
        try (var h = SynthesisTrialHarness.start("IN_MEMORY", 1000, n -> n.equals("MODEL_API_KEY") ? "synthetic-no-live-key" : null,
                Prompt.V2, SynthesisTrialHarness.cedar(), (qid, body, url, headers, timeout) -> {
                    assertEquals(Files.readString(DIR.resolve(qid).resolve("synthesis-request.json")), body,
                            qid + ": request must equal the saved request Muse received");
                    return new MuseTransport.Reply(200, Files.readString(DIR.resolve(qid).resolve("synthesis-recording.json")), Map.of());
                })) {
            for (String q : qids) {
                var r = h.ask(q);
                var quality = h.quality(q, r.answer());
                System.out.println("[TASK-054 replay-saved-v2] " + q + " outcome=" + r.synthesis().get(0).outcome() + " synthesized=" + r.synthesized()
                        + " final_sources=" + SynthesisTrialHarness.sources(r.answer()) + " quality_pass=" + quality.get("pass")
                        + " violations=" + r.synthesis().get(0).violations());
                assertEquals(Outcome.SYNTHESIZED, r.synthesis().get(0).outcome(), q + " " + r.synthesis().get(0).violations());
                assertTrue(r.synthesized());
                assertEquals(Boolean.TRUE, quality.get("pass"), q + " " + quality);
                for (var c : r.answer().citations()) assertTrue(h.store().find(h.group, c.messageId()).isPresent());
            }
        }
        assertArrayEquals(before, digest(qids), "saved live artifacts must be unchanged");
    }

    static byte[] digest(List<String> qids) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        for (String q : qids) for (String f : List.of("synthesis-request.json", "synthesis-recording.json", "attempted", "result-live.json")) {
            Path p = DIR.resolve(q).resolve(f);
            if (Files.exists(p)) md.update(Files.readAllBytes(p));
        }
        return md.digest();
    }
}
