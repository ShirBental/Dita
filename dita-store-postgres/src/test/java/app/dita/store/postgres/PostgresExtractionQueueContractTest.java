package app.dita.store.postgres;

import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Queue contract against a live Postgres migrated through V5. SKIPPED unless DITA_TEST_DB_URL is set, and SKIPPED
 * (with the reason) if V5__knowledge_v2.sql is not applied.
 */
class PostgresExtractionQueueContractTest extends ExtractionQueueContractTest {
    static DataSource ds;
    static JdbcTemplate jdbc;
    PostgresMessageStore store;

    @Override protected ExtractionQueue createQueue() {
        TestDb.assumeConfigured();
        if (ds == null) { ds = TestDb.dataSource(); jdbc = new JdbcTemplate(ds); }
        store = new PostgresMessageStore(ds);
        TestDb.assumeV5(store);
        return new PostgresExtractionQueue(ds, store);
    }

    @Override protected GroupScope newGroup(String label) { return TestDb.newGroup(jdbc, label); }

    @Override protected void seedMessages(GroupScope s, String... ids) {
        for (int i = 0; i < ids.length; i++) {
            store.append(s, new Message(ids[i], s.groupId(), "synthetic-author", T0.plusSeconds(60L * i), null,
                    "synthetic message " + i, SourceKind.SYNTHETIC, false));
        }
    }
}
