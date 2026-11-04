package app.dita.core;

import app.dita.core.config.DispatchSettings;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventDispatcher;
import app.dita.core.events.Refs;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1c (ADR-001): concurrent publish() and drain() must never lose an envelope. Before the fix, publish() took a
 * sequence number and appended in two unsynchronised steps, so a drain could advance the cursor past a sequence
 * whose envelope had not been appended yet; that envelope was then never delivered (and later pruned).
 * Synthetic ids only.
 */
class EventDispatcherConcurrencyTest {
    static final int PUBLISHERS = 8, PER_PUBLISHER = 5_000;

    @RepeatedTest(5)
    void concurrentPublishAndDrainDeliverEveryEnvelopeExactlyOnce() throws Exception {
        try (EventDispatcher d = new EventDispatcher(new DispatchSettings(100, 2, 5, Duration.ZERO, Duration.ZERO), Clock.systemUTC())) {
            Map<Long, AtomicInteger> seen = new ConcurrentHashMap<>();
            d.register(new EventDispatcher.Consumer() {
                public String name() { return "probe"; }
                public void accept(EventDispatcher.Envelope e) { seen.computeIfAbsent(e.seq(), k -> new AtomicInteger()).incrementAndGet(); }
            });
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean publishing = new AtomicBoolean(true);
            List<Thread> pubs = new ArrayList<>();
            for (int t = 0; t < PUBLISHERS; t++) {
                int tt = t;
                pubs.add(Thread.ofPlatform().start(() -> {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    for (int i = 0; i < PER_PUBLISHER; i++) d.publish("g", new Refs.MessageRef("g", "m-" + tt + "-" + i));
                }));
            }
            Thread drainer = Thread.ofPlatform().start(() -> { while (publishing.get()) d.drain(); });
            start.countDown();
            for (Thread p : pubs) p.join();
            publishing.set(false);
            drainer.join();
            for (int i = 0; i < 1_000 && d.lag("probe") > 0; i++) d.drain();
            for (int i = 0; i < 5; i++) d.drain();     // a lost envelope has lag 0, so drain a few more times anyway

            long total = (long) PUBLISHERS * PER_PUBLISHER;
            List<Long> missing = new ArrayList<>();
            for (long s = 1; s <= total; s++) if (!seen.containsKey(s)) missing.add(s);
            assertTrue(missing.isEmpty(), "lost " + missing.size() + " of " + total + " envelopes, e.g. " + missing.stream().limit(5).toList());
            assertTrue(seen.values().stream().allMatch(c -> c.get() == 1), "an envelope was delivered more than once");
            assertEquals(0, d.retainedEnvelopes(), "everything delivered is pruned");
        }
    }

    /** A deletion published concurrently with other events is delivered, never lost to the race (N-20). */
    @Test
    void deletionPublishedUnderContentionIsDelivered() throws Exception {
        for (int round = 0; round < 50; round++) {
            try (EventDispatcher d = new EventDispatcher(new DispatchSettings(100, 1, 5, Duration.ZERO, Duration.ZERO), Clock.systemUTC())) {
                AtomicInteger deletions = new AtomicInteger();
                d.register(new EventDispatcher.Consumer() {
                    public String name() { return "probe"; }
                    public void accept(EventDispatcher.Envelope e) { if (e.payload() instanceof DeletionEvent) deletions.incrementAndGet(); }
                });
                AtomicBoolean go = new AtomicBoolean(true);
                Thread noise = Thread.ofPlatform().start(() -> { int i = 0; while (go.get()) d.publish("g", new Refs.MessageRef("g", "n" + i++)); });
                Thread drainer = Thread.ofPlatform().start(() -> { while (go.get()) d.drain(); });
                Instant t = Instant.parse("2026-09-01T08:00:00Z");
                d.publish("g", new DeletionEvent("g", "m-del", DeletionMode.EARLY_PURGE, DeletionSource.FORGET_COMMAND, t, t.plusSeconds(60),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
                Thread.sleep(2);
                go.set(false);
                noise.join(); drainer.join();
                for (int i = 0; i < 1_000 && d.lag("probe") > 0; i++) d.drain();
                d.drain();
                assertEquals(1, deletions.get(), "round " + round + ": the deletion was not delivered exactly once");
            }
        }
    }
}
