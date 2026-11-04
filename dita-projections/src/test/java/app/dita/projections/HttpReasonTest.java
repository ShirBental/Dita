package app.dita.projections;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Diagnostics keep the server's reason but never echo values that may contain message text. Synthetic bodies only. */
class HttpReasonTest {
    private static JsonNode json(String s) throws Exception { return Http.JSON.readTree(s); }

    @Test void keepsAVespaDocumentApiReason() throws Exception {
        String r = Http.reason(json("{\"pathId\":\"/document/v1/dita/knowledge/docid\",\"message\":\"Document type knowledge does not exist\"}"));
        assertEquals("Document type knowledge does not exist", r);
    }

    @Test void keepsShortQuotedIdentifiersButRedactsQuotedValues() throws Exception {
        String r = Http.reason(json("{\"message\":\"Field 'text' of 'knowledge' rejected value \\\"Dr. Levi is fine for babies but avoid him\\\"\"}"));
        assertTrue(r.contains("'text'") && r.contains("'knowledge'"), r);
        assertFalse(r.contains("Levi") || r.contains("babies"), r);
        assertTrue(r.contains("\"…\""), r);
    }

    @Test void removesControlCharactersAndIsBounded() throws Exception {
        String r = Http.reason(json("{\"message\":\"line1\\nline2\\u0007 " + "x".repeat(500) + "\"}"));
        assertFalse(r.contains("\n") || r.contains("\u0007"), r);
        assertEquals(Http.MAX_REASON + 1, r.length());
        assertTrue(r.endsWith("…"));
    }

    @Test void readsVespaQueryAndNeo4jErrorShapes() throws Exception {
        assertEquals("Could not resolve source", Http.reason(json("{\"root\":{\"errors\":[{\"code\":4,\"message\":\"Could not resolve source\"}]}}")));
        assertEquals("Neo.ClientError.Security.Unauthorized", Http.reason(json("{\"errors\":[{\"code\":\"Neo.ClientError.Security.Unauthorized\",\"message\":\"secret detail\"}]}")));
    }

    @Test void neverEchoesANonJsonBodyAndToleratesEmptyOrUnknownShapes() throws Exception {
        assertEquals("non-JSON response", Http.reason(Http.JSON.getNodeFactory().textNode("<html>Dr. Levi</html>")));
        assertEquals("", Http.reason(Http.JSON.nullNode()));
        assertEquals("", Http.reason(json("{\"documents\":[]}")));
        assertEquals("", Http.reason(null));
    }
}
