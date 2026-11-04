package app.dita.store.postgres;

import app.dita.core.QueryService;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.knowledge.*;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.*;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.store.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Synthetic PostgreSQL reproductions of the two round-2 blockers. Upgrade tests additionally require
 * DITA_TASK047_ISOLATED=true and an owner URL, and create NEW schemas only; never migrate existing application tables. */
class PostgresTask047BlockersTest {
    static final Instant T = Instant.parse("2026-12-01T12:00:00Z");
    DataSource ds;
    JdbcTemplate jdbc, owner;
    PostgresMessageStore store;

    @BeforeEach void setUp() {
        TestDb.assumeConfigured();
        ds = TestDb.dataSource(); jdbc = new JdbcTemplate(ds); store = new PostgresMessageStore(ds);
        TestDb.assumeV5(store);
    }

    GroupScope group(String label) { return TestDb.newGroup(jdbc, label); }
    void message(GroupScope g, String id, int seconds, String text) {
        store.append(g, new Message(id, g.groupId(), "ana", T.plusSeconds(seconds), null, text, SourceKind.SYNTHETIC, false));
    }
    Proposal.Item item(String id, String status, String predicate, String quote) {
        return new Proposal.Item(status, "ASSERTED", "dinner", "dinner", predicate, null, null,
                List.of(new Proposal.Quote(id, quote)), List.of(), List.of());
    }
    void extract(GroupScope g, List<Proposal.Item> items, String... ids) {
        var x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, c) -> new Proposal(items, "codex-blockers"),
                new PostgresEventPublisher(ds), Clock.fixed(T.plusSeconds(5000), ZoneOffset.UTC), 50);
        for (String id : ids) x.enqueue(g, store.find(g, id).orElseThrow());
        var batch = x.collect(g).getFirst(); x.approve(g, batch.id(), "isolated-synthetic");
        assertEquals(ExtractionBatch.Status.DONE, x.process(g, batch.id()).orElseThrow().status());
    }
    DeletionService changes() {
        return new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, new PostgresEventPublisher(ds), 50);
    }
    KnowledgeItem old(GroupScope g) {
        return store.knowledge(g).stream().filter(k -> k.sourceMessageIds().contains("a")).findFirst().orElseThrow();
    }
    QueryService query(MessageStore s, boolean limited) {
        var access = new HistoryAccessFilter(limited ? HistoryAccess.SINCE_REQUESTER_JOINED : HistoryAccess.FULL_GROUP_HISTORY,
                (g, id) -> Optional.of(T.minusSeconds(1)), s);
        return new QueryService(s, new LexicalRetriever(s), access, 10, 300, id -> "Ana");
    }

    @Test void editBetweenFinalVerifierAndEvidenceViewIsSerializedOrRetried() throws Exception { gateRace("edit", false); }
    @Test void deletionBetweenFinalVerifierAndEvidenceViewIsSerializedOrRetried() throws Exception { gateRace("delete", false); }
    @Test void anotherGroupCanCommitWhileTheFinalGateIsPaused() throws Exception { gateRace("delete", true); }

    private void gateRace(String operation, boolean otherGroup) throws Exception {
        GroupScope g = group("final-gate");
        message(g, "a", 0, "Dinner is at 7 PM."); message(g, "b", 60, "Correction: dinner is at 8 PM.");
        extract(g, List.of(item("a", "ATTRIBUTED_STATEMENT", "states", "Dinner is at 7 PM."),
                item("b", "ATTRIBUTED_STATEMENT", "states", "dinner is at 8 PM.")), "a", "b");
        GroupScope writerGroup = otherGroup ? group("independent-writer") : g;
        if (otherGroup) message(writerGroup, "b", 0, "Unrelated synthetic message.");
        var read = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        var intercepted = new AtomicBoolean();
        MessageStore view = (MessageStore) Proxy.newProxyInstance(MessageStore.class.getClassLoader(), new Class<?>[]{MessageStore.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(store, args);
                        boolean gate = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f -> f.getMethodName().equals("currentAtReturn"));
                        boolean verifier = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f ->
                                f.getClassName().equals("app.dita.core.answer.AnswerVerifier") && f.getMethodName().equals("valid"));
                        if (method.getName().equals("find") && args[1].equals("b") && gate && verifier && intercepted.compareAndSet(false, true)) {
                            read.countDown();
                            if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("gate not released");
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var access = new HistoryAccessFilter(HistoryAccess.SINCE_REQUESTER_JOINED, (s, id) -> Optional.of(T.minusSeconds(1)), view);
        var q = new QueryService(view, (s, question, k) -> List.of(new Hit("b", 1, "direct", false)), access, 10, 300);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var answering = threads.submit(() -> q.ask(g, "viewer", "What time is dinner?"));
            assertTrue(read.await(8, TimeUnit.SECONDS));
            var writing = threads.submit(() -> {
                started.countDown();
                if (operation.equals("edit")) changes().edit(writerGroup, "b", "Dinner plans remain open.", T.plusSeconds(180));
                else changes().delete(writerGroup, "b", T.plusSeconds(180), DeletionSource.FORGET_COMMAND);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            boolean committed;
            try { writing.get(1, TimeUnit.SECONDS); committed = true; }
            catch (TimeoutException contention) { committed = false; }
            release.countDown();
            GroundedAnswer answer = answering.get(8, TimeUnit.SECONDS); writing.get(8, TimeUnit.SECONDS);
            if (otherGroup) {
                assertTrue(committed, "isolation must be group-scoped, not a global answer lock");
                assertTrue(answer.found() && answer.text().contains("8 PM"));
            } else if (committed) {
                assertFalse(answer.text().contains("8 PM"), "commit happened inside final gate, yet stale evidence escaped: " + answer.text());
            } else {
                assertTrue(answer.found() && answer.text().contains("8 PM"), "valid at the serialized gate point");
            }
            GroundedAnswer next = query(store, true).ask(g, "viewer", "What time is dinner?");
            if (!otherGroup) assertFalse(next.text().contains("7 PM") || next.text().contains("8 PM"), next.text());
            for (var citation : next.citations()) {
                Message source = store.find(g, citation.messageId()).orElseThrow();
                assertFalse(source.deleted()); assertTrue(source.text().contains(citation.quote()));
            }
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    /** All DDL below is confined to a newly generated schema in an explicitly opted-in throwaway database. */
    private void isolatedSchema(boolean v6) throws Exception {
        assumeTrue("true".equals(System.getenv("DITA_TASK047_ISOLATED")), "isolated migration tests not enabled");
        String url = System.getenv("DITA_TASK047_OWNER_URL");
        assumeTrue(url != null && !url.isBlank(), "isolated owner URL not provided");
        String ownerUser = System.getenv().getOrDefault("DITA_TASK047_OWNER_USER", "review_owner");
        String ownerPassword = System.getenv().getOrDefault("DITA_TASK047_OWNER_PASSWORD", "");
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        String schema = "task047_" + UUID.randomUUID().toString().replace("-", "");
        bootstrap.execute("CREATE SCHEMA " + schema);
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var ownerDs = new DriverManagerDataSource(schemaUrl, ownerUser, ownerPassword);
        owner = new JdbcTemplate(ownerDs);
        for (String file : List.of("V1__core.sql", "V2__outbox_relay.sql", "V4__outbox_commit_order.sql", "V5__knowledge_v2.sql"))
            owner.execute(Files.readString(Path.of("../db/migration", file)));
        owner.execute("GRANT USAGE ON SCHEMA " + schema + " TO dita_app");
        owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema + " TO dita_app");
        owner.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + schema + " TO dita_app");
        ds = new DriverManagerDataSource(schemaUrl, "dita_app", "");
        jdbc = new JdbcTemplate(ds); store = new PostgresMessageStore(ds);
        if (v6) upgrade();
    }
    private void upgrade() throws Exception {
        String sql = Files.readString(Path.of("../db/migration/V6__validity_unresolved.sql"));
        new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).execute(tx -> { owner.execute(sql); return null; });
        assertTrue(store.supportsUnresolvedValidity(), "the same store must observe the schema change");
    }
    private void addFalseRelation(GroupScope g) {
        String text = "Decision: dinner starts at noon.";
        message(g, "c", 180, text); extract(g, List.of(item("c", "DECISION", "decides", text)), "c");
        assertTrue(store.relations(g).stream().anyMatch(r -> r.toId().equals(old(g).id())), "fixture: unchanged slot-frame heuristic creates false edge");
    }
    private void rejectAndRemoveC(GroupScope g) {
        store.atomically(g, () -> ValidityReconciler.rejectRelations(store, g, store.relations(g).stream().map(KnowledgeRelation::key).toList()));
        changes().delete(g, "c", T.plusSeconds(240), DeletionSource.FORGET_COMMAND);
    }
    private void migratedWithdrawal(String mode, boolean fanIn) throws Exception {
        isolatedSchema(false);
        GroupScope g = group("upgrade-withdrawal");
        String a = "Let's put dinner signs at the entrance.", b = "Correction: put dinner signs at the exit.";
        message(g, "a", 0, a); message(g, "b", 60, b);
        extract(g, List.of(item("a", "ATTRIBUTED_STATEMENT", "states", a), item("b", "ATTRIBUTED_STATEMENT", "states", b)), "a", "b");
        message(g, "parking", 30, "Parking is free.");
        extract(g, List.of(new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", "parking", "Parking", "states", null, null,
                List.of(new Proposal.Quote("parking", "Parking is free.")), List.of(), List.of())), "parking");
        assertEquals(Assertion.Validity.SUPERSEDED, old(g).assertion().validity());
        if (fanIn) addFalseRelation(g);
        if (mode.equals("edit")) changes().edit(g, "b", "Dinner plans remain open.", T.plusSeconds(120));
        else changes().delete(g, "b", T.plusSeconds(mode.equals("late") ? 1200 : 120), DeletionSource.FORGET_COMMAND);
        assertEquals(Assertion.Validity.SUPERSEDED, old(g).assertion().validity(), "V5 withdrawal fallback");
        String retainedText = old(g).statement();
        long lastEvent = owner.queryForObject("SELECT COALESCE(max(id), 0) FROM outbox", Long.class);
        int retiredBeforeUpgrade = owner.queryForObject("SELECT count(*) FROM knowledge_item WHERE validity = 'SUPERSEDED'", Integer.class);
        upgrade();
        assertEquals(retainedText, old(g).statement(), "upgrade must preserve source wording/provenance");
        int migrationEvents = owner.queryForObject("SELECT count(*) FROM outbox WHERE id > ? AND topic = 'knowledge.changed'",
                Integer.class, lastEvent);
        int nonIdPayloads = owner.queryForObject("SELECT count(*) FROM outbox WHERE id > ? AND "
                + "(payload - 'groupId' - 'knowledgeId') <> '{}'::jsonb", Integer.class, lastEvent);
        if (!fanIn) addFalseRelation(g);
        rejectAndRemoveC(g);
        GroundedAnswer answer = query(store, false).ask(g, "viewer", "Where should we put dinner signs?");
        assertFalse(answer.text().contains("entrance") || answer.text().contains("exit"), "genuine V5 withdrawal revived after repair: " + answer.text());
        assertEquals(Assertion.Validity.UNRESOLVED, old(g).assertion().validity());
        assertEquals(retiredBeforeUpgrade, migrationEvents, "each converted validity must reach the projections");
        assertEquals(0, nonIdPayloads, "migration events contain ids only");
        var parking = store.knowledge(g).stream().filter(k -> k.sourceMessageIds().contains("parking")).findFirst().orElseThrow();
        assertEquals(Assertion.Validity.ACTIVE, parking.assertion().validity(), "active legacy knowledge must not be retired");
        assertTrue(query(store, true).ask(g, "viewer", "Is parking free?").text().contains("Parking is free"));
        message(g, "reconfirmed", 360, "Dinner signs are at the entrance.");
        extract(g, List.of(item("reconfirmed", "ATTRIBUTED_STATEMENT", "states", "Dinner signs are at the entrance.")), "reconfirmed");
        GroundedAnswer reconfirmed = query(store, false).ask(g, "viewer", "Where should we put dinner signs?");
        assertTrue(reconfirmed.text().contains("entrance"), "a NEW claim can reconfirm the old value");
        assertTrue(reconfirmed.citations().stream().anyMatch(c -> c.messageId().equals("reconfirmed")));
        assertTrue(reconfirmed.citations().stream().noneMatch(c -> c.messageId().equals("a")), "never reactivate the original claim");
    }
    @Test void v5EarlyWithdrawalSurvivesUpgradeAndFalseRelationRepair() throws Exception { migratedWithdrawal("early", false); }
    @Test void v5LateWithdrawalSurvivesUpgradeAndFalseRelationRepair() throws Exception { migratedWithdrawal("late", false); }
    @Test void v5EditedCorrectionSurvivesUpgradeAndFalseRelationRepair() throws Exception { migratedWithdrawal("edit", false); }
    @Test void v5FanInWithdrawalSurvivesUpgradeAndFalseRelationRepair() throws Exception { migratedWithdrawal("early", true); }

    @Test void newV6RelationOnlyRetirementStillRestoresIndependentKnowledge() throws Exception {
        for (boolean legacyActive : List.of(false, true)) {
            isolatedSchema(!legacyActive);
            GroupScope g = group("new-v6-repair");
            String a = "Let's put dinner signs at the entrance.";
            message(g, "a", 0, a); extract(g, List.of(item("a", "ATTRIBUTED_STATEMENT", "states", a)), "a");
            if (legacyActive) upgrade(); // ACTIVE legacy claims have no ambiguous retirement history
            addFalseRelation(g); rejectAndRemoveC(g);
            assertEquals(Assertion.Validity.ACTIVE, old(g).assertion().validity());
            assertTrue(query(store, false).ask(g, "viewer", "Where should we put dinner signs?").text().contains("entrance"));
        }
    }
}
