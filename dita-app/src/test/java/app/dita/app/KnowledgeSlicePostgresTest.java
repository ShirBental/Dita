package app.dita.app;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The slice on REAL PostgreSQL + Neo4j + Vespa (dita.storage.backend=POSTGRES), including an application restart.
 * Gated: SKIPPED unless the Dita test services are configured (scripts/knowledge-verify.sh sets them). Requires V5.
 */
class KnowledgeSlicePostgresTest extends KnowledgeSliceAcceptance {
    static final List<String> REQUIRED = List.of("DITA_TEST_DB_URL", "DITA_TEST_DB_PASSWORD", "DITA_TEST_RELAY_PASSWORD",
            "DITA_TEST_NEO4J_URL", "DITA_TEST_NEO4J_PASSWORD", "DITA_TEST_VESPA_URL");

    @Override Map<String, Object> backendProperties() {
        return Map.ofEntries(
                Map.entry("dita.storage.backend", "POSTGRES"),
                Map.entry("dita.storage.relay-consumer-prefix", "ks-" + UUID.randomUUID().toString().substring(0, 8)),
                Map.entry("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL")),
                Map.entry("dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD"),
                Map.entry("dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD"),
                Map.entry("dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL")),
                Map.entry("dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD"),
                Map.entry("dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")));
    }
    @Override boolean durable() { return true; }

    @Test void syntheticMessagesBecomeDurableEvidenceGroundedKnowledge() throws Exception {
        for (String v : REQUIRED) assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: PostgreSQL slice NOT EXECUTED");
        assumeTrue(v5Applied(), "V5__knowledge_v2.sql not applied: PostgreSQL slice NOT EXECUTED");
        runSlice();
    }

    /** Read-only catalog probe (same marker as TestDb.assumeV5 and the verification scripts). */
    static boolean v5Applied() throws Exception {
        try (Connection c = DriverManager.getConnection(System.getenv("DITA_TEST_DB_URL"),
                System.getenv().getOrDefault("DITA_TEST_DB_USER", "dita_app"), System.getenv("DITA_TEST_DB_PASSWORD"));
             ResultSet rs = c.createStatement().executeQuery("SELECT to_regclass('public.knowledge_relation') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
