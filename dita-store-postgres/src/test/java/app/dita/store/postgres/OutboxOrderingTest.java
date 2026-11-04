package app.dita.store.postgres;

import app.dita.core.IngestionPipeline;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DispatchSettings;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventDispatcher;
import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-001 F1 / N-44 on a REAL PostgreSQL: the relay cursor ({@code id > last_id}) must never skip an outbox row that
 * commits out of id order. Synthetic data only. Gated: SKIPPED unless DITA_TEST_DB_URL (dita_app) and
 * DITA_TEST_RELAY_PASSWORD (dita_relay) are set. The *DefectDemo tests additionally need -Ddita.demonstrateDefect=true:
 * they run the pre-fix publisher (LegacyUnlockedPublisher). On a database WITHOUT the V4 trigger they are EXPECTED TO
 * FAIL with "MISSING committed outbox ids" (the original defect; the two-transaction demos are deterministic, the stress
 * demo is probabilistic). WITH V4 installed they must PASS, because the database then orders even raw writers.
 * scripts/relay-verify.sh runs both phases. Run DB suites serially (never mvn -T) against one database.
 */
class OutboxOrderingTest {
    static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    static final DispatchSettings FAST = new DispatchSettings(100, 1, 5, Duration.ZERO, Duration.ZERO);

    static DataSource appDs, relayDs;
    static JdbcTemplate app, relayJdbc;
    PostgresMessageStore store;
    PostgresEventPublisher fixed;
    LegacyUnlockedPublisher legacy;

    @BeforeEach void setUp() {
        TestDb.assumeConfigured();
        String relayPw = System.getenv("DITA_TEST_RELAY_PASSWORD");
        assumeTrue(relayPw != null, "DITA_TEST_RELAY_PASSWORD not set: relay tests skipped");
        if (appDs == null) {
            appDs = TestDb.dataSource();
            DriverManagerDataSource r = new DriverManagerDataSource(TestDb.URL, "dita_relay", relayPw);
            r.setDriverClassName("org.postgresql.Driver");
            relayDs = r;
            app = new JdbcTemplate(appDs);
            relayJdbc = new JdbcTemplate(relayDs);
        }
        store = new PostgresMessageStore(appDs);
        fixed = new PostgresEventPublisher(appDs);
        legacy = new LegacyUnlockedPublisher(appDs);
    }

    // ------------------------------------------------------------------ helpers

    /** Records every envelope of the given groups, in delivery order. */
    static final class Recorder implements EventDispatcher.Consumer {
        final String name = "ordering-" + UUID.randomUUID();
        final Set<String> groups;
        final List<EventDispatcher.Envelope> got = Collections.synchronizedList(new ArrayList<>());
        Recorder(GroupScope... gs) { groups = new HashSet<>(); for (GroupScope g : gs) groups.add(g.groupId()); }
        public String name() { return name; }
        public void accept(EventDispatcher.Envelope e) { if (groups.contains(e.groupId())) got.add(e); }
        List<Long> ids() { synchronized (got) { return got.stream().map(EventDispatcher.Envelope::seq).toList(); } }
    }

    PostgresOutboxRelay relay(Clock clock, DispatchSettings s) { return new PostgresOutboxRelay(relayDs, s, clock); }

    /** A transaction that publishes, then stays open until released. */
    final class Held {
        final CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicLong id = new AtomicLong(-1);
        final CompletableFuture<Long> committed = new CompletableFuture<>();
        Held(GroupScope g, EventPublisher pub, Object payload) {
            Thread.ofPlatform().start(() -> {
                try {
                    committed.complete(store.atomically(g, () -> {
                        id.set(pub.publish(g.groupId(), payload));
                        inserted.countDown();
                        try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                        return id.get();
                    }));
                } catch (Throwable t) { committed.completeExceptionally(t); inserted.countDown(); }
            });
        }
        long awaitInserted() throws Exception { assertTrue(inserted.await(10, TimeUnit.SECONDS), "T1 never inserted"); return id.get(); }
        long commit() throws Exception { release.countDown(); return committed.get(10, TimeUnit.SECONDS); }
    }

    static DeletionEvent deletion(GroupScope g, String messageId) {
        return new DeletionEvent(g.groupId(), messageId, DeletionMode.EARLY_PURGE, DeletionSource.FORGET_COMMAND, T0, T0.plusSeconds(120),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static long waitingAdvisoryLocks() {
        // Only waiters on OUR key (0x44495441 / 0x00000001; bigint keys use objsubid 1), not unrelated advisory locks.
        return app.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted "
                + "AND classid = 1145656385 AND objid = 1 AND objsubid = 1", Long.class);
    }

    long publishCommitted(GroupScope g, Object payload) { return store.atomically(g, () -> fixed.publish(g.groupId(), payload)); }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(T0);
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    }

    // ------------------------------------------------------------------ 1-5: the two-transaction race

    /**
     * T1 publishes (lower id) and stays open; T2 publishes and tries to commit first; the relay drains in between;
     * then T1 commits and the relay drains again. Every committed row must be delivered.
     * Fixed publisher: T2 must BLOCK until T1's commit is visible (proved by T2 seeing T1's row right after its own
     * INSERT), so ids follow commit order and nothing can be skipped.
     */
    void twoTransactionRace(EventPublisher pub, Object payloadT1, Object payloadT2, GroupScope g) throws Exception {
        boolean isFixed = pub == fixed;
        Recorder rec = new Recorder(g);
        PostgresOutboxRelay relay = relay(Clock.systemUTC(), FAST);
        relay.registerFromNow(rec);

        Held t1 = new Held(g, pub, payloadT1);
        long id1 = t1.awaitInserted();
        CompletableFuture<long[]> t2 = CompletableFuture.supplyAsync(() -> store.atomically(g, () -> {
            long id = pub.publish(g.groupId(), payloadT2);
            long t1Visible = app.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Long.class, id1);
            return new long[]{id, t1Visible};
        }));
        boolean t2CommittedWhileT1Open;
        try { t2.get(1500, TimeUnit.MILLISECONDS); t2CommittedWhileT1Open = true; }
        catch (java.util.concurrent.TimeoutException e) { t2CommittedWhileT1Open = false; }

        relay.drainUntilIdle(10);                                           // the relay runs while T1 is still open
        List<Long> midway = rec.ids();
        if (isFixed) {
            assertFalse(t2CommittedWhileT1Open, "T2 must block on the outbox lock while T1 is open");
            assertTrue(waitingAdvisoryLocks() >= 1, "T2 is waiting on the advisory lock");
            assertEquals(List.of(), midway, "nothing of this group is visible yet");
        }

        long committed1 = t1.commit();
        long[] r2 = t2.get(10, TimeUnit.SECONDS);
        relay.drainUntilIdle(10);

        if (isFixed) {
            assertEquals(1L, r2[1], "T2 drew its id only after T1's commit was visible (commit ordering, not just id ordering)");
            assertTrue(r2[0] > committed1, "T2's id follows T1's");
            assertEquals(List.of(committed1, r2[0]), rec.ids(), "delivered once each, in commit order");
        }
        Set<Long> missing = new TreeSet<>(Set.of(committed1, r2[0]));
        rec.ids().forEach(missing::remove);
        assertTrue(missing.isEmpty(), "MISSING committed outbox ids " + missing + " (midway delivery " + midway
                + ", T2 committed while T1 open: " + t2CommittedWhileT1Open + ")");
    }

    @Test void fixedPublisherOrdersCommitsAndDeliversEverything() throws Exception {
        GroupScope g = TestDb.newGroup(app, "order");
        twoTransactionRace(fixed, new Refs.MessageRef(g.groupId(), "m1"), new Refs.MessageRef(g.groupId(), "m2"), g);
    }

    @Test void fixedPublisherNeverSkipsALateCommittingDeletion() throws Exception {
        GroupScope g = TestDb.newGroup(app, "order-del");
        twoTransactionRace(fixed, deletion(g, "m1"), new Refs.MessageRef(g.groupId(), "m2"), g);
    }

    @Test void legacyPublisherLosesOutOfOrderCommit_DefectDemo() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.demonstrateDefect"), "defect demo only with -Ddita.demonstrateDefect=true");
        GroupScope g = TestDb.newGroup(app, "legacy");
        twoTransactionRace(legacy, new Refs.MessageRef(g.groupId(), "m1"), new Refs.MessageRef(g.groupId(), "m2"), g);
    }

    @Test void legacyPublisherLosesLateDeletion_DefectDemo() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.demonstrateDefect"), "defect demo only with -Ddita.demonstrateDefect=true");
        GroupScope g = TestDb.newGroup(app, "legacy-del");
        twoTransactionRace(legacy, deletion(g, "m1"), new Refs.MessageRef(g.groupId(), "m2"), g);
    }

    // ------------------------------------------------------------------ 7: concurrency stress

    void stress(EventPublisher pub) throws Exception {
        List<GroupScope> gs = List.of(TestDb.newGroup(app, "s1"), TestDb.newGroup(app, "s2"), TestDb.newGroup(app, "s3"));
        Recorder rec = new Recorder(gs.toArray(GroupScope[]::new));
        PostgresOutboxRelay relay = relay(Clock.systemUTC(), FAST);
        relay.registerFromNow(rec);
        Set<Long> committed = ConcurrentHashMap.newKeySet(), rolledBack = ConcurrentHashMap.newKeySet();
        AtomicLong deletionsCommitted = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        List<Throwable> threadErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicLong deliveredWhileWriting = new AtomicLong();
        Thread drainer = Thread.ofPlatform().start(() -> {
            try { while (running.get()) deliveredWhileWriting.addAndGet(relay.drainOnce()); }
            catch (Throwable t) { threadErrors.add(t); }                   // N-66b: a dead drainer must fail the test
        });
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            int ww = w;
            writers.add(Thread.ofPlatform().start(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int t = 0; t < 30; t++) {
                    GroupScope g = gs.get(rnd.nextInt(gs.size()));
                    List<Long> mine = new ArrayList<>();
                    boolean rollback = rnd.nextInt(10) == 0;
                    try {
                        store.atomically(g, () -> {
                            int n = 1 + rnd.nextInt(3);
                            for (int i = 0; i < n; i++) {
                                Object p = rnd.nextInt(5) == 0 ? deletion(g, "d-" + ww + "-" + mine.size())
                                        : new Refs.MessageRef(g.groupId(), "m-" + ww + "-" + mine.size());
                                mine.add(pub.publish(g.groupId(), p));
                                if (p instanceof DeletionEvent) deletionsMarker.get().incrementAndGet();
                                sleep(rnd.nextInt(4));
                            }
                            sleep(rnd.nextInt(8));
                            if (rollback) throw new IllegalStateException("synthetic rollback");
                            return null;
                        });
                        committed.addAll(mine);
                        deletionsCommitted.addAndGet(deletionsMarker.get().getAndSet(0));
                    } catch (IllegalStateException e) {
                        if (!"synthetic rollback".equals(e.getMessage())) threadErrors.add(e);
                        rolledBack.addAll(mine);
                        deletionsMarker.get().set(0);
                    } catch (Throwable e) {
                        threadErrors.add(e);                                  // N-66b: never drop a writer silently
                        rolledBack.addAll(mine);
                        deletionsMarker.get().set(0);
                    }
                }
            }));
        }
        for (Thread w : writers) w.join();
        running.set(false);
        drainer.join();
        relay.drainUntilIdle(100);

        assertEquals(List.of(), threadErrors.stream().map(t -> t.getClass().getSimpleName() + ": " + t.getMessage()).toList(),
                "writer/drainer threads failed");
        assertTrue(committed.size() >= 150, "too few committed rows for a meaningful stress run: " + committed.size());
        assertTrue(deliveredWhileWriting.get() > 0, "the relay must deliver concurrently with the writers, not only at the end");
        Set<Long> delivered = new HashSet<>(rec.ids());
        Set<Long> missing = new TreeSet<>(committed); missing.removeAll(delivered);
        Set<Long> phantom = new TreeSet<>(delivered); phantom.retainAll(rolledBack);
        long deletionsDelivered;
        synchronized (rec.got) { deletionsDelivered = rec.got.stream().filter(e -> e.payload() instanceof DeletionEvent).count(); }
        assertTrue(missing.isEmpty(), "MISSING " + missing.size() + " of " + committed.size() + " committed ids, e.g. "
                + missing.stream().limit(10).toList());
        assertTrue(phantom.isEmpty(), "rolled-back rows were delivered: " + phantom);
        assertEquals(committed.size(), rec.ids().size(), "each committed row delivered exactly once in this run");
        assertEquals(deletionsCommitted.get(), deletionsDelivered, "every committed deletion delivered");
    }

    final ThreadLocal<AtomicLong> deletionsMarker = ThreadLocal.withInitial(AtomicLong::new);

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IllegalStateException(e); } }

    @Test void concurrentWritersAndRelayNeverLoseOrInventEvents() throws Exception { stress(fixed); }

    @Test void concurrentLegacyWritersLoseEvents_DefectDemo() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.demonstrateDefect"), "defect demo only with -Ddita.demonstrateDefect=true");
        stress(legacy);
    }

    // ------------------------------------------------------------------ 7: crash recovery

    /** A relay that dies mid-batch (Error, not Exception) rolls back its cursor; a restarted relay redelivers. */
    @Test void relayCrashMidBatchRedeliversFromLastCommittedCursor() {
        GroupScope g = TestDb.newGroup(app, "crash");
        String name = "ordering-crash-" + UUID.randomUUID();
        List<Long> firstLife = new ArrayList<>(), secondLife = new ArrayList<>();
        EventDispatcher.Consumer crashing = new EventDispatcher.Consumer() {
            public String name() { return name; }
            public void accept(EventDispatcher.Envelope e) {
                if (!e.groupId().equals(g.groupId())) return;
                if (firstLife.size() == 4) throw new Error("simulated process crash");
                firstLife.add(e.seq());
            }
        };
        PostgresOutboxRelay r1 = relay(Clock.systemUTC(), FAST);
        r1.registerFromNow(crashing);
        List<Long> published = new ArrayList<>();
        for (int i = 0; i < 10; i++) published.add(publishCommitted(g, new Refs.MessageRef(g.groupId(), "c" + i)));
        assertThrows(Error.class, r1::drainOnce, "the crash propagates out of the relay transaction");
        assertEquals(4, firstLife.size());

        PostgresOutboxRelay r2 = relay(Clock.systemUTC(), FAST);            // "restarted process", same consumer name
        r2.register(new EventDispatcher.Consumer() {
            public String name() { return name; }
            public void accept(EventDispatcher.Envelope e) { if (e.groupId().equals(g.groupId())) secondLife.add(e.seq()); }
        });
        r2.drainUntilIdle(10);
        assertEquals(published, secondLife, "cursor was not advanced by the crashed pass: everything is redelivered, in order");
        assertEquals(0, r2.lag(name));
    }

    // ------------------------------------------------------------------ 6/7: replay, idempotency, deletion never revived

    @Test void replayFromAnEarlierCursorIsIdempotentAndNeverRevivesDeletedText() {
        GroupScope g = TestDb.newGroup(app, "replay");
        Map<String, String> projection = new ConcurrentHashMap<>();
        List<String> m1States = Collections.synchronizedList(new ArrayList<>());
        String name = "ordering-replay-" + UUID.randomUUID();
        EventDispatcher.Consumer projector = new EventDispatcher.Consumer() {   // re-reads truth, like Neo4j/Vespa projections
            public String name() { return name; }
            public void accept(EventDispatcher.Envelope e) {
                if (!e.groupId().equals(g.groupId())) return;
                String id = switch (e.payload()) {
                    case Refs.MessageRef r -> r.messageId();
                    case DeletionEvent d -> d.messageId();
                    default -> null;
                };
                if (id == null) return;
                Message m = store.find(g, id).orElse(null);
                String state = (m == null || m.deleted() || m.text() == null) ? "deleted" : "live:" + m.text().hashCode();
                projection.put(id, state);
                if (id.equals("m1")) m1States.add(state);
            }
        };
        PostgresOutboxRelay relay = relay(Clock.systemUTC(), FAST);
        relay.registerFromNow(projector);
        long start = relayJdbc.queryForObject("SELECT last_id FROM outbox_consumer WHERE name = ?", Long.class, name);

        var ingest = new IngestionPipeline(store, new RuleLinkInferencer(), 50, fixed);
        ingest.ingest(g, new Message("m1", g.groupId(), "dana", T0, null, "synthetic: the pediatrician on Herzl St.", SourceKind.SYNTHETIC, false));
        ingest.ingest(g, new Message("m2", g.groupId(), "yossi", T0.plusSeconds(60), null, "synthetic: snacks for Friday", SourceKind.SYNTHETIC, false));
        new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, fixed, 50)
                .delete(g, "m1", T0.plusSeconds(120), DeletionSource.FORGET_COMMAND).orElseThrow();
        relay.drainUntilIdle(10);
        Map<String, String> first = new HashMap<>(projection);
        assertEquals("deleted", first.get("m1"));
        assertTrue(first.get("m2").startsWith("live:"));

        m1States.clear();
        relayJdbc.update("UPDATE outbox_consumer SET last_id = ? WHERE name = ?", start, name);   // operator replay
        relay.drainUntilIdle(10);
        assertEquals(first, projection, "replay is idempotent");
        assertFalse(m1States.isEmpty(), "m1 events were replayed");
        assertTrue(m1States.stream().allMatch("deleted"::equals), "replaying m1's creation never revives its text: " + m1States);
    }

    // ------------------------------------------------------------------ 6: a failing deletion is never skipped

    @Test void failingDeletionBlocksTheConsumerAndIsNeverDeadLettered() {
        GroupScope g = TestDb.newGroup(app, "block");
        MutableClock clock = new MutableClock();
        AtomicBoolean failing = new AtomicBoolean(true);
        List<Object> got = Collections.synchronizedList(new ArrayList<>());
        String name = "ordering-block-" + UUID.randomUUID();
        PostgresOutboxRelay relay = relay(clock, new DispatchSettings(100, 1, 2, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        relay.registerFromNow(new EventDispatcher.Consumer() {
            public String name() { return name; }
            public void accept(EventDispatcher.Envelope e) {
                if (!e.groupId().equals(g.groupId())) return;
                if (e.payload() instanceof DeletionEvent && failing.get()) throw new IllegalStateException("projection down");
                got.add(e.payload());
            }
        });
        publishCommitted(g, new Refs.MessageRef(g.groupId(), "m1"));
        long delId = publishCommitted(g, deletion(g, "m1"));
        publishCommitted(g, new Refs.MessageRef(g.groupId(), "m2"));

        for (int i = 0; i < 6; i++) { relay.drainOnce(); clock.advance(Duration.ofSeconds(5)); }
        assertTrue(relay.blockedOnDeletion(name), "consumer is blocked on the deletion (operator alert)");
        assertEquals(1, got.size(), "only m1 delivered; nothing after the deletion");
        assertEquals(0L, relayJdbc.queryForObject("SELECT count(*) FROM outbox_dead_letter WHERE consumer = ? AND outbox_id = ?",
                Long.class, name, delId), "a deletion is never dead-lettered");

        failing.set(false);                                                   // projection recovers
        clock.advance(Duration.ofMinutes(2));
        relay.drainUntilIdle(10);
        assertFalse(relay.blockedOnDeletion(name));
        assertEquals(3, got.size(), "deletion then m2 delivered after recovery");
        assertInstanceOf(DeletionEvent.class, got.get(1));
    }
}
