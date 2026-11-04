package app.dita.app;

import app.dita.core.model.KnowledgeItem;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

/**
 * TASK-051 WIRING CHECK on the in-memory backend (runs by default, no services). It is NOT the acceptance test: there is no
 * persistence and no Vespa/Neo4j indexing here (the user's demo backend is PostgreSQL + Neo4j + Vespa only). See
 * {@link TelegramReplayPostgresAcceptanceTest}.
 */
class TelegramReplayInMemoryWiringTest extends TelegramReplayAcceptance {
    @Override Map<String, Object> backendProperties() { return Map.of("dita.storage.backend", "IN_MEMORY"); }
    @Override void assertIndexed(ConfigurableApplicationContext c, List<KnowledgeItem> knowledge) { /* no indexing on IN_MEMORY */ }

    @Test void syntheticReplayRunsTheWholeRuntimePathInMemory() throws Exception { runAcceptance(); }
}
