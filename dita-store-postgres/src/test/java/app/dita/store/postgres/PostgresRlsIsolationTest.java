package app.dita.store.postgres;

import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.model.AnswerRecord;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-level isolation, bypassing the adapter's own group_id filters: with app.group_id = A, raw SQL must not
 * read or write group B rows. Requires a NOSUPERUSER NOBYPASSRLS role; skipped unless DITA_TEST_DB_URL is set.
 */
class PostgresRlsIsolationTest {
    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    @BeforeEach void connect() {   // per-test assumption so the test is reported SKIPPED without a database
        TestDb.assumeConfigured();
        ds = TestDb.dataSource();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    <T> T asGroup(String group, Function<JdbcTemplate, T> work) {
        return tx.execute(s -> {
            if (group != null) jdbc.queryForObject("SELECT set_config('app.group_id', ?, true)", String.class, group);
            return work.apply(jdbc);
        });
    }

    @Test void rlsConfinesRawSqlToTheScopedGroup() {
        Boolean bypass = jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertFalse(bypass, "test role bypasses RLS; connect as dita_app (docs/local-infra.md)");

        GroupScope a = TestDb.newGroup(jdbc, "rls-a");
        GroupScope b = TestDb.newGroup(jdbc, "rls-b");
        new PostgresMessageStore(ds).append(b, new Message("mb", b.groupId(), "zed", Instant.parse("2026-10-01T09:00:00Z"),
                null, "group B synthetic", SourceKind.SYNTHETIC, false));

        // Read: no WHERE group_id filter at all, still nothing from B.
        assertEquals(0L, (long) asGroup(a.groupId(), j -> j.queryForObject("SELECT count(*) FROM message", Long.class)));
        assertEquals(0L, (long) asGroup(a.groupId(), j -> j.queryForObject("SELECT count(*) FROM message_event", Long.class)));
        assertEquals(1L, (long) asGroup(b.groupId(), j -> j.queryForObject("SELECT count(*) FROM message", Long.class)));
        // Unset app.group_id: nothing is visible.
        assertEquals(0L, (long) asGroup(null, j -> j.queryForObject("SELECT count(*) FROM message", Long.class)));

        // Update/delete from A's scope cannot touch B's row.
        assertEquals(0, (int) asGroup(a.groupId(), j -> j.update("UPDATE message SET current_text = 'x' WHERE id = 'mb'")));
        assertEquals(0, (int) asGroup(a.groupId(), j -> j.update("DELETE FROM message_event WHERE message_id = 'mb'")));

        // Insert of a B row from A's scope violates the WITH CHECK policy.
        assertThrows(DataAccessException.class, () -> asGroup(a.groupId(), j -> j.update(
                "INSERT INTO message (group_id, id, author_id, sent_at, source_kind, current_text) "
                        + "VALUES (?, 'forged', 'eve', now(), 'SYNTHETIC', 'x')", b.groupId())));
        // Cross-group reference from an A row to B's message is rejected by the composite FK / RLS.
        assertThrows(DataAccessException.class, () -> asGroup(a.groupId(), j -> j.update(
                "INSERT INTO message (group_id, id, author_id, sent_at, source_kind, current_text, reply_to_message_id) "
                        + "VALUES (?, 'ra', 'eve', now(), 'SYNTHETIC', 'x', 'mb')", a.groupId())));

        assertEquals("group B synthetic", asGroup(b.groupId(),
                j -> j.queryForObject("SELECT current_text FROM message WHERE id = 'mb'", String.class)));
    }

    static final List<String> V5_TABLES = List.of("knowledge_relation", "dita_answer", "dita_answer_knowledge",
            "dita_answer_message", "extraction_pending", "extraction_batch", "extraction_batch_target");

    /** V5: every new table is FORCE-RLS confined to app.group_id, and dita_app has only the privileges it uses. */
    @Test void v5TablesAreGroupConfinedAndLeastPrivilege() {
        PostgresMessageStore store = new PostgresMessageStore(ds);
        TestDb.assumeV5(store);
        GroupScope a = TestDb.newGroup(jdbc, "rls5-a");
        GroupScope b = TestDb.newGroup(jdbc, "rls5-b");
        Instant t0 = Instant.parse("2026-10-01T09:00:00Z");
        store.append(b, new Message("mb1", b.groupId(), "zed", t0, null, "group B synthetic one", SourceKind.SYNTHETIC, false));
        store.append(b, new Message("mb2", b.groupId(), "yael", t0.plusSeconds(60), null, "group B synthetic two", SourceKind.SYNTHETIC, false));
        store.addKnowledge(b, KnowledgeItem.of("kb1", b.groupId(), KnowledgeItem.Kind.CLAIM, null, "s1", 0, MessageLink.Method.RULE, List.of("mb1")));
        store.addKnowledge(b, KnowledgeItem.of("kb2", b.groupId(), KnowledgeItem.Kind.CLAIM, null, "s2", 0, MessageLink.Method.RULE, List.of("mb2")));
        store.addRelation(b, new KnowledgeRelation(b.groupId(), "kb2", "kb1", KnowledgeRelation.Kind.SUPPORTS, "mb2"));
        store.recordAnswer(b, new AnswerRecord(b.groupId(), "rb", List.of("kb1"), List.of("mb1"), t0.plusSeconds(120)));
        PostgresExtractionQueue q = new PostgresExtractionQueue(ds, store);
        q.enqueue(b, new ExtractionBatch.Target("mb1", "rb"), t0);
        q.collect(b, 10, t0);
        q.enqueue(b, new ExtractionBatch.Target("mb2", null), t0);          // stays pending

        for (String t : V5_TABLES) {
            assertEquals(0L, (long) asGroup(a.groupId(), j -> j.queryForObject("SELECT count(*) FROM " + t, Long.class)), t + " leaks to A");
            assertEquals(0L, (long) asGroup(null, j -> j.queryForObject("SELECT count(*) FROM " + t, Long.class)), t + " visible unscoped");
            assertTrue(asGroup(b.groupId(), j -> j.queryForObject("SELECT count(*) FROM " + t, Long.class)) > 0, t + " empty for B");
        }
        assertThrows(DataAccessException.class, () -> asGroup(a.groupId(), j -> j.update(
                "INSERT INTO knowledge_relation (group_id, from_id, to_id, kind) VALUES (?, 'kb1', 'kb2', 'SUPPORTS')", b.groupId())));
        assertEquals(0, (int) asGroup(a.groupId(), j -> j.update("DELETE FROM knowledge_relation")));
        assertEquals(0, (int) asGroup(a.groupId(), j -> j.update("UPDATE extraction_batch SET status = 'FAILED'")));

        for (String[] p : new String[][]{{"knowledge_relation", "UPDATE"}, {"dita_answer", "UPDATE"}, {"dita_answer", "DELETE"},
                {"extraction_pending", "UPDATE"}, {"extraction_batch", "DELETE"}, {"extraction_batch_target", "UPDATE"}}) {
            assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user, ?, ?)", Boolean.class, p[0], p[1]),
                    "dita_app must not have " + p[1] + " on " + p[0]);
        }
    }
}
