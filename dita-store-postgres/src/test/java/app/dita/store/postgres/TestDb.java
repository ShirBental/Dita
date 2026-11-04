package app.dita.store.postgres;

import app.dita.core.model.GroupScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gate for the live-database tests. Set DITA_TEST_DB_URL (+ DITA_TEST_DB_USER / DITA_TEST_DB_PASSWORD) to a database
 * migrated with db/migration/V1__core.sql, connecting as the non-owner dita_app role (see docs/local-infra.md).
 * Unset → the tests are reported as SKIPPED via JUnit assumptions, never failed.
 */
final class TestDb {
    static final String URL = System.getenv("DITA_TEST_DB_URL");

    private TestDb() {}

    static void assumeConfigured() {
        assumeTrue(URL != null && !URL.isBlank(), "DITA_TEST_DB_URL not set: Postgres tests skipped");
    }

    /** RFC-002 tests need db/migration/V5__knowledge_v2.sql; on an older schema they are SKIPPED with this reason. */
    static void assumeV5(PostgresMessageStore store) {
        assumeTrue(store.hasV5(), "V5__knowledge_v2.sql not applied: knowledge-v2 Postgres tests skipped");
    }

    static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(URL,
                System.getenv().getOrDefault("DITA_TEST_DB_USER", "dita_app"),
                System.getenv().getOrDefault("DITA_TEST_DB_PASSWORD", ""));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    /** chat_group has no RLS (GroupDirectory reads across groups), so the app role can register a synthetic group. */
    static GroupScope newGroup(JdbcTemplate jdbc, String label) {
        String id = "test-" + label + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO chat_group (id, platform, platform_chat_id, chat_type, source_kind) "
                + "VALUES (?, 'synthetic', ?, 'synthetic', 'SYNTHETIC')", id, id);
        return new GroupScope(id);
    }
}
