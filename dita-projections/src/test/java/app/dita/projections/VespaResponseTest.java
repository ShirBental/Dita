package app.dita.projections;

import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.retrieval.Hit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests built from REAL Vespa 8.778.45 responses captured by scripts/vespa-verify.sh (synthetic data). */
class VespaResponseTest {
    static JsonNode resource(String name) throws Exception {
        return Http.JSON.readTree(VespaResponseTest.class.getResourceAsStream("/vespa/" + name));
    }

    @Test void queryErrorIsAFailureNotAnEmptyResult() throws Exception {
        // The response that once produced a false-positive isolation PASS.
        JsonNode body = resource("malformed-nn-error.json");
        Http.ProjectionException e = assertThrows(Http.ProjectionException.class,
                () -> VespaProjection.parseHits(new GroupScope("vt-group-a"), body, "vespa:nn"));
        assertTrue(e.getMessage().contains("code 4"));
    }

    @Test void validHybridResponseParsesGroupHits() throws Exception {
        List<Hit> hits = VespaProjection.parseHits(new GroupScope("vt-group-a"), resource("hybrid-ok.json"), "vespa:hybrid");
        assertEquals(List.of("a1", "a2"), hits.stream().map(Hit::messageId).toList());
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test void hitFromAnotherGroupIsAnIsolationViolation() throws Exception {
        assertThrows(IsolationViolation.class,
                () -> VespaProjection.parseHits(new GroupScope("vt-group-b"), resource("hybrid-ok.json"), "vespa:hybrid"));
    }

    @Test void feedAcknowledgementMustNameTheExpectedDocument() throws Exception {
        JsonNode real = resource("feed-a1-ok.json");                       // {"pathId":...,"id":"id:dita:message::a1"}
        assertEquals("id:dita:message::a1", real.path("id").asText(), "captured shape");
        JsonNode ours = Http.JSON.readTree("{\"pathId\":\"/document/v1/dita/message/docid/g1%3A%3Am1\",\"id\":\"id:dita:message::g1::m1\"}");
        assertDoesNotThrow(() -> VespaProjection.requireFed(ours, "g1", "m1"));
        assertThrows(Http.ProjectionException.class, () -> VespaProjection.requireFed(ours, "g1", "m2"));
        assertThrows(Http.ProjectionException.class, () -> VespaProjection.requireFed(
                Http.JSON.readTree("{\"pathId\":\"x\",\"message\":\"Error\"}"), "g1", "m1"));
    }

    @Test void nearestNeighborYqlIsParenthesisedAndGroupFiltered() {
        String y = VespaProjection.nearestNeighborYql("vt-group-a", 10, false);
        assertTrue(y.contains("group_id contains \"vt-group-a\" and ({targetHits:10}nearestNeighbor(embedding,q))"), y);
        assertFalse(y.matches(".*and \\{targetHits.*"), "the malformed form must never be generated");
        assertTrue(VespaProjection.nearestNeighborYql("vt-group-a", 10, true).contains("(({targetHits:10}nearestNeighbor(embedding,q)) or userQuery())"));
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.nearestNeighborYql("a\" or true or \"", 10, false));
    }
}
