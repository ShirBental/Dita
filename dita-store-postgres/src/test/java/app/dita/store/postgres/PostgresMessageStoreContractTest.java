package app.dita.store.postgres;

import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.model.SourceKind;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract run against a live, migrated Postgres. Skipped unless DITA_TEST_DB_URL is set. */
class PostgresMessageStoreContractTest extends MessageStoreContractTest {
    static DataSource ds;
    static JdbcTemplate jdbc;

    /** Called from the base @BeforeEach: the assumption marks each test SKIPPED (not failed) without a database. */
    @Override protected MessageStore createStore() {
        TestDb.assumeConfigured();
        if (ds == null) { ds = TestDb.dataSource(); jdbc = new JdbcTemplate(ds); }
        return new PostgresMessageStore(ds);
    }
    @Override protected GroupScope newGroup(String label) { return TestDb.newGroup(jdbc, label); }
    @Override protected void assumeKnowledgeV2() { TestDb.assumeV5((PostgresMessageStore) store); }
    @Override protected void assumeUnresolvedValidity() {
        org.junit.jupiter.api.Assumptions.assumeTrue(((PostgresMessageStore) store).hasV6(),
                "V6__validity_unresolved.sql not applied: UNRESOLVED round-trip skipped");
    }

    /**
     * N-123: on V5 without V6, deleting the only correction of a claim COMMITS (the deletion never fails) and leaves the
     * older claim SUPERSEDED without a relation (never current). Then the same on V6: UNRESOLVED.
     */
    @Test void deletingTheOnlyCorrectionCommitsOnEitherSchema() {
        TestDb.assumeV5((PostgresMessageStore) store);
        seed();
        app.dita.core.model.KnowledgeItem older = extracted(g, "k1", "bob", "m2", 0, 30);
        app.dita.core.model.KnowledgeItem correction = extracted(g, "k2", "carol", "m3", 0, 5);
        store.addKnowledge(g, older);
        store.addKnowledge(g, correction);
        store.addRelation(g, relation(g, "k2", "k1", app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES, "m3"));
        app.dita.core.knowledge.ValidityReconciler.reconcile(store, g);
        assertEquals(app.dita.core.model.Assertion.Validity.SUPERSEDED, store.knowledgeById(g, "k1").orElseThrow().assertion().validity());
        var e = new app.dita.core.deletion.DeletionService(store, new app.dita.core.link.RuleLinkInferencer(),
                app.dita.core.config.DeletionSettings.DEFAULTS, app.dita.core.events.EventPublisher.NONE, 50)
                .delete(g, "m3", at(4).plusSeconds(60), app.dita.core.deletion.DeletionSource.PLATFORM_EVENT);
        assertTrue(e.isPresent(), "the deletion committed");
        assertTrue(store.find(g, "m3").orElseThrow().deleted());
        assertEquals(((PostgresMessageStore) store).hasV6() ? app.dita.core.model.Assertion.Validity.UNRESOLVED
                        : app.dita.core.model.Assertion.Validity.SUPERSEDED,
                store.knowledgeById(g, "k1").orElseThrow().assertion().validity(), "never ACTIVE again");
    }

    /** DEC-038: on V5 without V6, UNRESOLVED is refused explicitly (never stored as something else) and reported unsupported. */
    @Test void unresolvedIsRefusedExplicitlyWithoutV6() {
        TestDb.assumeV5((PostgresMessageStore) store);
        org.junit.jupiter.api.Assumptions.assumeFalse(((PostgresMessageStore) store).hasV6(), "V6 applied: nothing to check");
        assertTrue(!store.supportsUnresolvedValidity());
        seed();
        app.dita.core.model.KnowledgeItem k = extracted(g, "k1", "bob", "m2", 0, 30);
        store.addKnowledge(g, k);
        assertThrows(UnsupportedOperationException.class, () -> store.replaceKnowledge(g, k.withAssertion(k.assertion().unresolved())));
        assertEquals(app.dita.core.model.Assertion.Validity.ACTIVE, store.knowledgeById(g, "k1").orElseThrow().assertion().validity());
    }

    // Postgres-only: nested scopes are bound to the transaction's app.group_id.

    @Test void nestedScopeForAnotherGroupIsRefused() {
        GroupScope b = newGroup("other");
        assertThrows(IsolationViolation.class, () -> store.atomically(g, () -> {
            store.append(b, msg(b, "x", "eve", 0, null, "cross-group write"));
            return null;
        }));
        assertTrue(store.live(b).isEmpty());
    }

    @Test void publisherWritesIdsOnlyOutboxRowInTheSameTransaction() {
        PostgresEventPublisher pub = new PostgresEventPublisher(ds);
        assertThrows(IllegalStateException.class, () -> pub.publish(g.groupId(), msg(g, "m1", "a", 0, null, "t")));
        Message m = new Message("m1", g.groupId(), "alice", T0, null, "synthetic text must not reach outbox", SourceKind.SYNTHETIC, false);
        long id = store.atomically(g, () -> { store.append(g, m); return pub.publish(g.groupId(), m); });
        // V3/N-27: dita_app may read outbox rows only of the group bound to its transaction.
        Map<String, Object> row = store.atomically(g, () -> jdbc.queryForMap("SELECT group_id, topic, payload::text AS payload FROM outbox WHERE id = ?", id));
        assertEquals(g.groupId(), row.get("group_id"));
        assertEquals(PostgresEventPublisher.MESSAGE_CREATED, row.get("topic"));
        assertTrue(!((String) row.get("payload")).contains("synthetic text"));

        long before = store.atomically(g, () -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE group_id = ?", Long.class, g.groupId()));
        assertThrows(IllegalStateException.class, () -> store.atomically(g, () -> {
            pub.publish(g.groupId(), m);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(before, store.atomically(g, () -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE group_id = ?", Long.class, g.groupId())));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class), "outside a group transaction dita_app sees no outbox rows");
        GroupScope b = newGroup("other");
        assertThrows(IsolationViolation.class, () -> store.atomically(g, () -> pub.publish(b.groupId(), m)));
        assertEquals(List.of(), store.atomically(b, () -> jdbc.queryForList("SELECT id FROM outbox WHERE group_id = ?", Long.class, b.groupId())));
    }
}
