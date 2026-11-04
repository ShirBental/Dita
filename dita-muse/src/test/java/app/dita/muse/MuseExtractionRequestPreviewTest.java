package app.dita.muse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OFFLINE: builds the exact extraction request the live test will send (same settings factory, same code path,
 * synthetic key, fake transport) and writes it to target/muse-extraction-request-preview.json for review.
 */
class MuseExtractionRequestPreviewTest {
    @Test void approvedExperimentRequestIsExactlyAsSpecified() throws Exception {
        MuseClient.resetProcessCountForTests();
        MuseClientTest.Fake fake = new MuseClientTest.Fake(MuseClientTest.openAi("{\"items\":[],\"contradictions\":[]}"));
        Map<String, Object> rep = new LinkedHashMap<>();
        MuseClient client = new MuseClient(LiveSettings.extraction(k -> null), LiveSettings.guarded(fake, rep), k -> "synthetic-preview-key");
        new MuseKnowledgeExtractor(client).extract(Fixture.G, Fixture.messages());

        assertEquals("PASS: reasoning_effort=low, max_completion_tokens=4096, no response_format, documented URL/model", rep.get("request_check"));
        MuseClientTest.Sent s = fake.sent.get(0);
        JsonNode b = new ObjectMapper().readTree(s.body());
        assertEquals("low", b.path("reasoning_effort").asText());
        assertEquals(4096, b.path("max_completion_tokens").asInt());
        assertTrue(b.path("response_format").isMissingNode(), "PROMPT_ONLY for this run");
        assertTrue(b.path("max_tokens").isMissingNode());
        assertEquals(2, b.path("messages").size());
        JsonNode input = new ObjectMapper().readTree(b.path("messages").get(1).path("content").asText());
        assertEquals(10, input.size(), "unchanged 10-message synthetic fixture");
        assertEquals("m10", input.get(9).path("id").asText());
        assertTrue(b.path("messages").get(0).path("content").asText().contains("MUST conform to this JSON Schema"));
        assertFalse(s.body().contains("synthetic-preview-key"));

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("url", s.url().toString());
        preview.put("headers", Map.of("Authorization", "Bearer <redacted>", "Content-Type", s.headers().get("Content-Type")));
        preview.put("body", b);
        Files.writeString(Path.of("target", "muse-extraction-request-preview.json"),
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(preview));
    }
}
