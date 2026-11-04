package app.dita.projections;

import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-054 G1: request shape, parsing and startup readiness of semantic RAW-MESSAGE retrieval. Scripted Vespa
 * responses only (no sockets, no model): this proves wiring and fail-closed behaviour, NOT embedding quality.
 */
class DenseMessageRetrievalTest {
    static final GroupScope G = new GroupScope("tgt1001000000054");
    static final VespaProjection V = new VespaProjection("http://127.0.0.1:1");    // never contacted
    final List<Map<String, Object>> sent = new ArrayList<>();
    static Http.Response resp(int status, String json) {
        try { return new Http.Response(status, Http.JSON.readTree(json)); } catch (Exception e) { throw new AssertionError(e); }
    }
    Function<Map<String, Object>, Http.Response> script(Http.Response... rs) {
        Deque<Http.Response> q = new ArrayDeque<>(List.of(rs));
        return b -> { sent.add(b); Http.Response r = q.poll(); return r == null ? resp(500, "{}") : r; };
    }
    static String hits(String... idGroupScore) {
        StringBuilder s = new StringBuilder("{\"root\":{\"fields\":{\"totalCount\":" + idGroupScore.length / 3 + "},\"children\":[");
        for (int i = 0; i < idGroupScore.length; i += 3) {
            if (i > 0) s.append(',');
            s.append("{\"relevance\":").append(idGroupScore[i + 2]).append(",\"fields\":{\"message_id\":\"").append(idGroupScore[i])
                    .append("\",\"group_id\":\"").append(idGroupScore[i + 1]).append("\"}}");
        }
        return s.append("]}}").toString();
    }

    @Test void theQueryIsGroupFilteredExactNearestNeighbourOverTheNewFieldWithTheFrozenEmbedder() {
        var b = VespaProjection.denseMessageBody(G, "Which pediatrician do local parents trust most?", 24, 0.6, null);
        String yql = (String) b.get("yql");
        assertTrue(yql.startsWith("select message_id, group_id from message where group_id contains \"tgt1001000000054\" and "), yql);
        assertTrue(yql.contains("{targetHits:24,approximate:false}nearestNeighbor(text_embedding,q)"), yql);
        assertEquals("dense", b.get("ranking"));
        assertEquals(24, b.get("hits"));
        assertEquals("embed(dita-english, @semanticQuery)", b.get("input.query(q)"));
        assertEquals("Which pediatrician do local parents trust most?", b.get("semanticQuery"), "the question is a parameter, never spliced into YQL");
        assertFalse(b.containsKey("timeout"));
    }

    @Test void boundsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.denseMessageBody(G, "q", 0, 0.6, null));
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.denseMessageBody(G, "q", 201, 0.6, null));
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.denseMessageBody(G, " ", 5, 0.6, null));
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.denseMessageBody(G, "x".repeat(501), 5, 0.6, null));
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.denseMessageBody(G, "q", 5, 1.5, null));
        assertThrows(RuntimeException.class, () -> VespaProjection.denseMessageBody(new GroupScope("bad\" or true"), "q", 5, 0.6, null));
    }

    @Test void parsingAppliesTheCosineFloorTagsTheSemanticChannelAndFailsClosed() throws Exception {
        var out = VespaProjection.parseDenseMessages(G, Http.JSON.readTree(hits("m1", G.groupId(), "0.82", "m2", G.groupId(), "0.41")), 0.6);
        assertEquals(1, out.size());
        assertEquals("m1", out.get(0).messageId());
        assertEquals(VespaProjection.VIA_DENSE, out.get(0).via());
        assertThrows(IsolationViolation.class, () -> VespaProjection.parseDenseMessages(G,
                Http.JSON.readTree(hits("x1", "another-group", "0.9")), 0.6), "a hit from another group is a hard failure, not filtered");
        assertThrows(Http.ProjectionException.class, () -> VespaProjection.parseDenseMessages(G,
                Http.JSON.readTree("{\"root\":{\"errors\":[{\"code\":4,\"summary\":\"Unknown field text_embedding\"}]}}"), 0.6),
                "a missing field is an error, never an empty result");
    }

    @Test void readinessRetriesOnlyABoundedColdStartAndOtherwiseFailsClosed() {
        String empty = "{\"root\":{\"fields\":{\"totalCount\":0}}}";
        assertEquals(2, V.awaitDenseMessageReadiness(script(resp(504, "{\"root\":{}}"), resp(200, empty)), new GroupScope("task054-readiness"), 0.6, Duration.ofSeconds(5), 3));
        assertEquals("5000ms", sent.get(0).get("timeout"));
        assertTrue(((String) sent.get(0).get("yql")).contains("nearestNeighbor(text_embedding,q)"));
        sent.clear();
        var e = assertThrows(Http.ProjectionException.class, () -> V.awaitDenseMessageReadiness(
                script(resp(400, "{\"root\":{\"errors\":[{\"code\":4,\"message\":\"Unknown rank profile dense\"}]}}"), resp(200, empty)),
                new GroupScope("task054-readiness"), 0.6, Duration.ofSeconds(5), 3));
        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertEquals(1, sent.size(), "the base package (no field/profile) fails at startup: semantic mode never boots as keyword-only");
        sent.clear();
        assertThrows(Http.ProjectionException.class, () -> V.awaitDenseMessageReadiness(
                script(resp(200, "{\"root\":{\"errors\":[{\"code\":4,\"summary\":\"field\"}]}}")), new GroupScope("task054-readiness"), 0.6, Duration.ofSeconds(5), 3),
                "an HTTP 200 carrying errors is also a failure");
    }
}
