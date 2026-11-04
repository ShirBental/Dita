package app.dita.app.config;

import app.dita.store.postgres.PostgresOutboxRelay;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background driver of the durable outbox relay (dita.storage.backend=POSTGRES). Drains continuously while there is
 * work and sleeps one poll interval when idle. A failing pass is logged by exception class only (never content) and
 * retried; per-consumer backoff, dead letters and the never-skip-deletions rule live in PostgresOutboxRelay.
 * A consumer blocked on a deletion is reported as a WARNING on every check (operator alert, N-20).
 * Delivery is at-least-once: after a crash the next pass resumes from the last committed cursor.
 */
public final class RelayWorker implements SmartLifecycle {
    private static final System.Logger log = System.getLogger(RelayWorker.class.getName());
    private static final int BLOCK_CHECK_EVERY = 20;

    private final PostgresOutboxRelay relay;
    private final long pollMs;
    private final List<String> consumers;
    private final AtomicLong passes = new AtomicLong(), delivered = new AtomicLong(), failures = new AtomicLong();
    private volatile boolean running;
    private volatile Thread thread;

    public RelayWorker(PostgresOutboxRelay relay, Duration pollInterval, List<String> consumers) {
        this.relay = relay; this.pollMs = Math.max(10, pollInterval.toMillis()); this.consumers = List.copyOf(consumers);
    }

    public record Stats(long passes, long delivered, long failures) {}
    public Stats stats() { return new Stats(passes.get(), delivered.get(), failures.get()); }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        thread = Thread.ofPlatform().daemon().name("dita-outbox-relay").start(this::loop);
    }

    private void loop() {
        while (running) {
            int n = 0;
            try {
                n = relay.drainOnce();
                delivered.addAndGet(n);
                if (passes.incrementAndGet() % BLOCK_CHECK_EVERY == 0) reportBlocked();
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {                                    // keep relaying; the cursor was rolled back
                failures.incrementAndGet();
                log.log(System.Logger.Level.WARNING, "outbox relay pass failed: " + e.getClass().getSimpleName());
            }
            if (n == 0) {
                try { Thread.sleep(pollMs); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void reportBlocked() {
        for (String c : consumers) {
            if (relay.blockedOnDeletion(c)) {
                log.log(System.Logger.Level.WARNING, "outbox consumer " + c + " is BLOCKED on a deletion event; operator action required (N-20)");
            }
        }
    }

    @Override public synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public boolean isRunning() { return running && thread != null && thread.isAlive(); }

    /** Starts before and stops AFTER the Telegram poller (default phase), so ingestion stops first (Shuri L3). */
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
