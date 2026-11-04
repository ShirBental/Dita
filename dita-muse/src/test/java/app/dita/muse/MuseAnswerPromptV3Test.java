package app.dita.muse;

import app.dita.core.answer.synthesis.AnswerSynthesizer.Evidence;
import app.dita.core.answer.synthesis.AnswerSynthesizer.Request;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 DEC-052: PROMPT INSPECTION of muse-answer-v3. These tests check what the instructions SAY and what Dita SENDS;
 * they say nothing about how Muse follows them (only a live, approved call can show that).
 */
class MuseAnswerPromptV3Test {
    static String flat(String s) { return s.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    final String v2 = MuseAnswerSynthesizer.Prompt.V2.system, v3 = MuseAnswerSynthesizer.Prompt.V3.system;

    @Test void v3IsVersionedSeparatelyAndV2StaysTheDefault() {
        assertEquals("muse-answer-v3", MuseAnswerSynthesizer.Prompt.V3.version);
        assertEquals("muse-answer-v2", new MuseAnswerSynthesizer(null).version(), "the library default is unchanged");
        assertNotEquals(v2, v3);
    }

    @Test void v3KeepsEveryV2LineVerbatim() {
        for (String line : v2.split("\n")) assertTrue(v3.contains(line), "v2 line missing from v3: " + line);
        assertTrue(v3.startsWith("You write the reply of Dita"), "shared request marker");
    }

    @Test void v3AddsNeutralAttributionAndNoRestatementUpgrade() {
        String f = flat(v3);
        for (String must : List.of("\"said\", \"noted\", \"asked\" or \"wrote\"",
                "repeating, restating, summarizing or acknowledging a plan has only said or noted it",
                "never describe that as confirmed, decided, agreed, committed or promised",
                "only for evidence whose kind is decision or confirmed_commitment, or when the quote itself uses that word",
                "do not describe the order of messages", "evidence dates do not establish the order within a day",
                "do not add connecting words or labels of your own"))
            assertTrue(f.contains(must), "missing: " + must);
    }

    @Test void v3HasNoFixtureVocabulary() {
        String f = flat(v3);
        for (String w : List.of("picnic", "meadow", "west gate", "library", "rain", "saturday", "cedar", "willow", "baby", "teen"))
            assertFalse(f.contains(w), "fixture-specific word in the prompt: " + w);
    }

    @Test void userContentIsIdenticalAcrossVersionsAndNeverCarriesSendTimes() {
        var r = new Request("When is the book club?", "draft",
                List.of(new Evidence("m1", "P1", "2027-01-01", "MESSAGE", 0, false, "Thursday at seven in the back room.")),
                List.of(), false, null, Map.of("m1", Instant.parse("2027-01-01T10:00:00Z")));
        String content = MuseAnswerSynthesizer.input(r).toString();
        assertFalse(content.contains("10:00"), "send times are local to the validator, never sent: " + content);
        assertFalse(content.contains("sentAt"), content);
    }

    /** The recorded live v2 requests used exactly the v2 text: v2 was not altered by adding v3. */
    @Test void recordedLiveRequestsUsedTheUnchangedV2Text() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("build/telegram-synthesis-trial"))) root = root.getParent();
        assumeTrue(root != null, "saved trial evidence absent: NOT EXECUTED");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        int checked = 0;
        for (String run : List.of("run-20261104T155040Z-0bb1", "run-20261104T161752Z-c886")) {
            Path f = root.resolve("build/telegram-synthesis-trial").resolve(run).resolve("call-001-synthesis/request.json");
            if (!Files.isRegularFile(f)) continue;
            String sent = json.readTree(f.toFile()).path("messages").get(0).path("content").asText();
            // MuseClient appends the response schema to the system text (PROMPT_ONLY response format)
            assertTrue(sent.startsWith(v2) && sent.substring(v2.length()).startsWith("\nThe JSON object MUST conform to this JSON Schema:\n"), run);
            checked++;
        }
        assumeTrue(checked > 0, "saved requests absent: NOT EXECUTED");
    }

    /**
     * EXACT v3 PREVIEW (no call): the second live request (run-20261104T161752Z-c886) with only the instructions swapped
     * to v3. Same model, settings, schema suffix and user content (asserted byte-identical). Written separately to
     * build/task054-v3-preview/; the recording is read only and hash-checked. It is the request v3 WOULD send if the
     * live retrieval selects the same 12 messages again (not guaranteed).
     */
    @Test void exactV3PreviewOfTheStoredConversationRequest() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("build/telegram-synthesis-trial"))) root = root.getParent();
        assumeTrue(root != null, "saved trial evidence absent: NOT EXECUTED");
        Path src = root.resolve("build/telegram-synthesis-trial/run-20261104T161752Z-c886/call-001-synthesis/request.json");
        assumeTrue(Files.isRegularFile(src), "saved request absent: NOT EXECUTED");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        byte[] original = Files.readAllBytes(src);
        String before = sha(original);
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original);
        var sys = (com.fasterxml.jackson.databind.node.ObjectNode) body.path("messages").get(0);
        String sent = sys.path("content").asText();
        assertTrue(sent.startsWith(v2), "the recording was a v2 request");
        String suffix = sent.substring(v2.length());
        String userBefore = body.path("messages").get(1).path("content").asText();
        sys.put("content", v3 + suffix);
        assertEquals(userBefore, body.path("messages").get(1).path("content").asText(), "user content byte-identical");
        String preview = json.writeValueAsString(body);
        Path out = Files.createDirectories(root.resolve("build/task054-v3-preview"));
        Files.writeString(out.resolve("request.json"), preview);
        var meta = json.createObjectNode().put("kind", "PREVIEW (not sent)").put("prompt", "muse-answer-v3")
                .put("based_on", "run-20261104T161752Z-c886/call-001-synthesis/request.json").put("based_on_sha256", before)
                .put("request_sha256", sha(preview.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .put("request_bytes", preview.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .put("v2_request_bytes", original.length)
                .put("user_content_identical", true)
                .put("note", "Valid only if the live retrieval selects the same evidence. The request actually sent is recorded in the new run (call-NNN-synthesis/request.json); there is no per-call approval in this preauthorized trial.");
        Files.writeString(out.resolve("preview.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        Files.writeString(out.resolve("system-v3.txt"), v3);
        assertEquals(before, sha(Files.readAllBytes(src)), "recording unchanged");
        System.out.println("[TASK-054 v3 preview] " + meta);
    }

    static String sha(byte[] b) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }
}
