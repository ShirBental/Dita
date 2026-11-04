package app.dita.store.postgres;

import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-001 item 4: safety of the outbox ordering lock on a REAL PostgreSQL (synthetic data only).
 * Release on rollback, contention without deadlock or timeout, bounded wait (N-63) with atomic rollback,
 * deadlock detection if a future caller ever writes after publish(), DB-level enforcement for raw INSERTs (V4, N-61)
 * and the CACHE 1 precondition (N-62). Gated like the other Postgres tests (DITA_TEST_DB_URL).
 */
class AdvisoryLockSafetyTest {
    static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    static DataSource ds;
    static JdbcTemplate jdbc;
    PostgresMessageStore store;
    PostgresEventPublisher pub;

    @BeforeEach void setUp() {
        TestDb.assumeConfigured();
        if (ds == null) { ds = TestDb.dataSource(); jdbc = new JdbcTemplate(ds); }
        store = new PostgresMessageStore(ds);
        pub = new PostgresEventPublisher(ds);
    }

    /** Holds an open transaction after {@code work} until released; then commits or rolls back. */
    final class Open {
        final CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Open(GroupScope g, Runnable work, boolean rollback) {
            Thread.ofPlatform().start(() -> {
                try {
                    store.atomically(g, () -> {
                        work.run();
                        ready.countDown();
                        try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                        if (rollback) throw new IllegalStateException("synthetic rollback");
                        return null;
                    });
                    done.complete(null);
                } catch (Throwable t) { ready.countDown(); done.completeExceptionally(t); }
            });
        }
        void awaitReady() throws Exception { assertTrue(ready.await(10, TimeUnit.SECONDS)); }
    }

    static Message msg(GroupScope g, String id) {
        return new Message(id, g.groupId(), "synthetic-author", T0, null, "synthetic text " + id, SourceKind.SYNTHETIC, false);
    }

    static boolean finishesWithin(CompletableFuture<?> f, long ms) {
        try { f.get(ms, TimeUnit.MILLISECONDS); return true; } catch (Exception e) { return f.isDone(); }
    }

    long outboxRows(GroupScope g) {
        return store.atomically(g, () -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE group_id = ?", Long.class, g.groupId()));
    }

    @Test void lockIsReleasedOnRollback() throws Exception {
        GroupScope g = TestDb.newGroup(jdbc, "lock-rb");
        Open t1 = new Open(g, () -> pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "a")), true);
        t1.awaitReady();
        CompletableFuture<Long> t2 = CompletableFuture.supplyAsync(() -> store.atomically(g, () -> pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "b"))));
        assertFalse(finishesWithin(t2, 1000), "T2 waits while T1 holds the lock");
        t1.release.countDown();
        assertThrows(Exception.class, () -> t1.done.get(10, TimeUnit.SECONDS));
        assertTrue(t2.get(5, TimeUnit.SECONDS) > 0, "T2 proceeds as soon as T1 rolls back");
        assertEquals(1, outboxRows(g), "only T2's row exists");
    }

    @Test void contentionManyWritersSerializeWithoutDeadlockOrTimeout() throws Exception {
        GroupScope g = TestDb.newGroup(jdbc, "lock-load");
        int threads = 16, txs = 10;
        List<Throwable> errors = new ArrayList<>();
        AtomicLong commits = new AtomicLong();
        List<Thread> ts = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            int tt = t;
            ts.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < txs; i++) {
                    int ii = i;
                    try {
                        store.atomically(g, () -> {
                            store.append(g, msg(g, "w" + tt + "-" + ii));
                            pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "w" + tt + "-" + ii));
                            pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "w" + tt + "-" + ii + "-x"));   // re-entrant
                            try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                            return null;
                        });
                        commits.incrementAndGet();
                    } catch (Throwable e) { synchronized (errors) { errors.add(e); } }
                }
            }));
        }
        for (Thread t : ts) t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[lock-contention] " + threads + "x" + txs + " transactions committed in " + ms + " ms");
        assertEquals(List.of(), errors.stream().map(e -> e.getClass().getSimpleName() + ": " + e.getMessage()).toList());
        assertEquals((long) threads * txs, commits.get());
        assertEquals(2L * threads * txs, outboxRows(g));
    }

    /** N-63: a stuck lock holder makes the next writer fail after lock_timeout and roll back truth + outbox together. */
    @Test void lockTimeoutRollsBackTruthAndOutboxTogether() throws Exception {
        GroupScope g = TestDb.newGroup(jdbc, "lock-timeout");
        Open holder = new Open(g, () -> pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "holder")), false);
        holder.awaitReady();
        long t0 = System.nanoTime();
        Exception e = assertThrows(Exception.class, () -> store.atomically(g, () -> {
            store.append(g, msg(g, "victim"));
            return pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "victim"));
        }));
        long waited = (System.nanoTime() - t0) / 1_000_000;
        holder.release.countDown();
        holder.done.get(10, TimeUnit.SECONDS);
        assertEquals("55P03", sqlState(e), "lock_not_available");
        assertTrue(waited >= 9_000 && waited < 30_000, "bounded wait, was " + waited + " ms");
        assertTrue(store.find(g, "victim").isEmpty(), "the truth change rolled back with the outbox row");
        assertEquals(1, outboxRows(g), "only the holder's row");
    }

    /**
     * Not reachable with today's callers (publish() is the last step of every unit), but proves the failure mode if a
     * future caller writes after publish(): PostgreSQL detects the lock/row cycle and aborts ONE transaction atomically;
     * nothing hangs and nothing is half-written.
     */
    @Test void contrivedDeadlockIsDetectedAndRolledBackAtomically() throws Exception {
        GroupScope g = TestDb.newGroup(jdbc, "lock-dl");
        CountDownLatch t1HasRow = new CountDownLatch(1), t1Go = new CountDownLatch(1), t2HasLock = new CountDownLatch(1);
        CompletableFuture<Void> t1 = CompletableFuture.runAsync(() -> store.atomically(g, () -> {
            store.append(g, msg(g, "x"));                                             // T1 holds the uncommitted row "x"
            t1HasRow.countDown();
            await(t1Go);
            pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "t1"));        // ...then needs the outbox lock
            return null;
        }));
        assertTrue(t1HasRow.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> store.atomically(g, () -> {
            pub.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "t2"));        // T2 holds the outbox lock
            t2HasLock.countDown();
            store.append(g, msg(g, "x"));                                             // ...then waits for T1's row "x"
            return null;
        }));
        assertTrue(t2HasLock.await(10, TimeUnit.SECONDS));
        Thread.sleep(300);                                                            // T2 is now blocked on "x"
        long t0 = System.nanoTime();
        t1Go.countDown();                                                             // cycle: T1 -> lock(T2), T2 -> row(T1)
        Throwable f1 = outcome(t1), f2 = outcome(t2);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue((f1 == null) != (f2 == null), "exactly one transaction is aborted (t1=" + f1 + ", t2=" + f2 + ")");
        assertEquals("40P01", sqlState(f1 != null ? f1 : f2), "deadlock_detected, not a timeout or hang");
        assertTrue(ms < 9_000, "resolved by the deadlock detector before lock_timeout, took " + ms + " ms");
        assertTrue(store.find(g, "x").isPresent(), "the survivor committed its row");
        assertEquals(1, outboxRows(g), "the aborted transaction left no outbox row (atomic rollback)");
    }

    static void await(CountDownLatch l) { try { assertTrue(l.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); } }

    static Throwable outcome(CompletableFuture<?> f) {
        try { f.get(20, TimeUnit.SECONDS); return null; }
        catch (java.util.concurrent.ExecutionException e) { return e.getCause(); }
        catch (Exception e) { throw new AssertionError("transaction did not finish: " + e); }
    }

    /** N-61 / V4: even a raw INSERT that bypasses PostgresEventPublisher is serialized by the database trigger. */
    @Test void rawInsertIsOrderedByTheDatabaseTrigger() throws Exception {
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname = 'outbox_commit_order' AND NOT tgisinternal", Long.class),
                "V4 trigger outbox_commit_order must be installed (scripts/relay-verify.sh applies it)");
        GroupScope g = TestDb.newGroup(jdbc, "lock-raw");
        EventPublisher raw = new LegacyUnlockedPublisher(ds);
        AtomicLong id1 = new AtomicLong();
        Open t1 = new Open(g, () -> id1.set(raw.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "r1"))), false);
        t1.awaitReady();
        CompletableFuture<long[]> t2 = CompletableFuture.supplyAsync(() -> store.atomically(g, () -> {
            long id = raw.publish(g.groupId(), new Refs.MessageRef(g.groupId(), "r2"));
            return new long[]{id, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Long.class, id1.get())};
        }));
        assertFalse(finishesWithin(t2, 1500), "a raw INSERT blocks on the trigger's lock while T1 is open");
        t1.release.countDown();
        t1.done.get(10, TimeUnit.SECONDS);
        long[] r2 = t2.get(10, TimeUnit.SECONDS);
        assertEquals(1L, r2[1], "T2 drew its id only after T1's commit was visible");
        assertTrue(r2[0] > id1.get());
    }

    /** N-62: ordering by lock requires that sessions do not pre-allocate blocks of ids. */
    @Test void outboxSequenceDoesNotCacheIds() {
        Long cache = jdbc.queryForObject("SELECT cache_size FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'outbox_id_seq'", Long.class);
        assertEquals(1L, cache, "outbox_id_seq must keep CACHE 1");
    }

    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SQLException s) return s.getSQLState();
        return null;
    }
}
