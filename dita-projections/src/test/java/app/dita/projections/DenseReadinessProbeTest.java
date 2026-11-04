package app.dita.projections;

import app.dita.core.model.GroupScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-052 stage 2 regression: the dense readiness probe run when the production knowledgeRetriever bean is built.
 * Scripted Vespa responses (no sockets). Contract: an explicit per-attempt timeout is sent; only HTTP 504 is retried, a
 * bounded number of times; anything else, or 504 on every attempt, fails closed; ordinary dense queries send no
 * timeout (unchanged behaviour). Synthetic bodies only.
 */
class DenseReadinessProbeTest {
    private static final GroupScope SCOPE = new GroupScope("task049-readiness");
    private static final VespaProjection V = new VespaProjection("http://127.0.0.1:1");   // never contacted here
    private final List<Map<String, Object>> sent = new ArrayList<>();

    private static Http.Response resp(int status, String json) {
        try { return new Http.Response(status, Http.JSON.readTree(json)); } catch (Exception e) { throw new AssertionError(e); }
    }
    static final String EMPTY = "{\"root\":{\"fields\":{\"totalCount\":0}}}";
    static final String CHAIN_TIMEOUT = "{\"root\":{\"errors\":[{\"code\":12,\"summary\":\"Timed out\",\"message\":\"Error in execution of chain 'dita': Chain timed out.\"}]}}";

    private Function<Map<String, Object>, Http.Response> script(Http.Response... rs) {
        Deque<Http.Response> q = new ArrayDeque<>(List.of(rs));
        return b -> { sent.add(b); Http.Response r = q.poll(); return r == null ? resp(500, "{}") : r; };
    }

    @Test void coldFirstEmbedTimesOutOnceThenTheProbeSucceedsWithAnExplicitTimeout() {
        int attempt = V.awaitDenseReadiness(script(resp(504, CHAIN_TIMEOUT), resp(200, EMPTY)), SCOPE, 0.6, null, Duration.ofSeconds(5), 3);
        assertEquals(2, attempt);
        assertEquals(2, sent.size());
        for (var b : sent) {
            assertEquals("5000ms", b.get("timeout"));
            assertEquals("dense", b.get("ranking"));
            assertTrue(String.valueOf(b.get("input.query(q)")).startsWith("embed(dita-english"), "the probe exercises the query embedder");
        }
    }

    @Test void a504OnEveryAttemptFailsClosedAfterTheBound() {
        var f = script(resp(504, CHAIN_TIMEOUT), resp(504, CHAIN_TIMEOUT), resp(504, CHAIN_TIMEOUT), resp(200, EMPTY));
        var e = assertThrows(Http.ProjectionException.class, () -> V.awaitDenseReadiness(f, SCOPE, 0.6, null, Duration.ofSeconds(5), 3));
        assertTrue(e.getMessage().contains("HTTP 504") && e.getMessage().contains("attempt 3/3"), e.getMessage());
        assertEquals(3, sent.size(), "never more than the configured attempts");
    }

    @Test void aNon504FailureIsNotRetried() {
        var f = script(resp(400, "{\"root\":{\"errors\":[{\"code\":4,\"message\":\"Unknown rank profile dense\"}]}}"), resp(200, EMPTY));
        var e = assertThrows(Http.ProjectionException.class, () -> V.awaitDenseReadiness(f, SCOPE, 0.6, null, Duration.ofSeconds(5), 3));
        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertEquals(1, sent.size(), "a missing rank profile or embedder is not a cold start: no retry");
    }

    @Test void aVespaErrorInsideA200StillFailsClosed() {
        assertThrows(Http.ProjectionException.class,
                () -> V.awaitDenseReadiness(script(resp(200, CHAIN_TIMEOUT)), SCOPE, 0.6, null, Duration.ofSeconds(5), 3));
        assertEquals(1, sent.size());
    }

    @Test void ordinaryDenseQueriesKeepVespasDefaultTimeout() {
        Map<String, Object> b = V.denseBody(new GroupScope("g1"), "which doctor", 5, 0.6, null);
        assertFalse(b.containsKey("timeout"), b.toString());
        assertEquals("dense", b.get("ranking"));
    }

    @Test void boundsAreEnforced() {
        var f = script(resp(200, EMPTY));
        assertThrows(IllegalArgumentException.class, () -> V.awaitDenseReadiness(f, SCOPE, 0.6, null, Duration.ofSeconds(5), 0));
        assertThrows(IllegalArgumentException.class, () -> V.awaitDenseReadiness(f, SCOPE, 0.6, null, Duration.ofSeconds(5), 6));
        assertThrows(IllegalArgumentException.class, () -> V.awaitDenseReadiness(f, SCOPE, 0.6, null, Duration.ofSeconds(60), 3));
        assertThrows(IllegalArgumentException.class, () -> V.awaitDenseReadiness(f, SCOPE, 0.6, null, Duration.ZERO, 3));
        assertTrue(sent.isEmpty(), "invalid bounds send nothing");
    }
}
