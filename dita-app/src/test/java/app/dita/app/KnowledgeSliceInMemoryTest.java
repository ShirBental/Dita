package app.dita.app;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** The slice on the IN_MEMORY backend (offline). Not proof of PostgreSQL persistence; no restart step. */
class KnowledgeSliceInMemoryTest extends KnowledgeSliceAcceptance {
    @Override Map<String, Object> backendProperties() { return Map.of("dita.storage.backend", "IN_MEMORY"); }
    @Override boolean durable() { return false; }

    @Test void syntheticMessagesBecomeEvidenceGroundedKnowledgeAndAnswers() throws Exception { runSlice(); }
}
