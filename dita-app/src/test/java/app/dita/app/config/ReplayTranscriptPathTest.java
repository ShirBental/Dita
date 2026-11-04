package app.dita.app.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Natasha L-1: a replay transcript path (or its .txt copy) may never be the replay or recording fixture. */
class ReplayTranscriptPathTest {
    @Test void transcriptMayNotOverwriteAFixture() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "replay-transcript-path"));
        Path replay = dir.resolve("r.jsonl"), rec = dir.resolve("rec.txt");
        Files.writeString(replay, "x"); Files.writeString(rec, "y");
        assertThrows(DitaConfigurationException.class, () -> TelegramConfiguration.refuseOverlap(replay, replay.toString(), rec.toString()));
        assertThrows(DitaConfigurationException.class, () -> TelegramConfiguration.refuseOverlap(dir.resolve("rec.jsonl"), replay.toString(), rec.toString()),
                "the readable .txt sibling would truncate the recording");
        assertThrows(DitaConfigurationException.class, () -> TelegramConfiguration.refuseOverlap(dir.resolve("./sub/../r.jsonl"), replay.toString(), ""));
        assertDoesNotThrow(() -> TelegramConfiguration.refuseOverlap(dir.resolve("transcript.jsonl"), replay.toString(), rec.toString()));
    }
}
