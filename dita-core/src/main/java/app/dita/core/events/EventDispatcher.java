package app.dita.core.events;

import app.dita.core.config.DispatchSettings;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process outbox relay (DEC-004). At-least-once, ordered per consumer, independent cursors so a failing
 * projection (e.g. Vespa down) never blocks another (e.g. Neo4j). Failures retry with exponential backoff;
 * after maxAttempts the envelope is dead-lettered and counted. No distributed transactions are assumed:
 * consumers must be idempotent and re-read state from the source of truth.
 */
public final class EventDispatcher implements EventPublisher, AutoCloseable {
    public record Envelope(long seq, String groupId, Object payload) {}
    public record DeadLetter(String consumer, Envelope envelope, String errorClass) {}

    public interface Consumer {
        String name();
        void accept(Envelope e) throws Exception;
    }

    /** Payload types that may never be skipped: a lost deletion would leave text in a projection (N-20). */
    private static boolean mustDeliver(Object payload) {
        return payload instanceof app.dita.core.deletion.DeletionEvent;
    }

    private static void requireIdsOnly(Object payload) {
        if (payload instanceof app.dita.core.model.Message || payload instanceof app.dita.core.model.KnowledgeItem
                || payload instanceof app.dita.core.model.MessageLink) {
            throw new IllegalArgumentException("outbox payloads carry ids only (N-18): publish a Refs.* instead");
        }
    }

    private final DispatchSettings settings;
    private final Clock clock;
    private final List<Envelope> outbox = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong seq = new AtomicLong();
    private final List<Consumer> consumers = new java.util.concurrent.CopyOnWriteArrayList<>();   // registered while drain() iterates
    private final Map<String, Long> cursor = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> retryAt = new ConcurrentHashMap<>();
    private final List<DeadLetter> deadLetters = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong delivered = new AtomicLong(), failures = new AtomicLong();
    private final java.util.Set<String> blockedOnDeletion = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool;

    public EventDispatcher(DispatchSettings settings, Clock clock) {
        this.settings = settings; this.clock = clock;
        this.pool = Executors.newFixedThreadPool(settings.workerThreads());
    }

    public synchronized void register(Consumer c) { consumers.add(c); cursor.putIfAbsent(c.name(), 0L); }

    @Override public long publish(String groupId, Object payload) {
        requireIdsOnly(payload);
        // F1c (ADR-001): sequence allocation and append must be one atomic step under the same monitor drain() reads
        // with. Otherwise a drain can advance the cursor past a sequence whose envelope is not yet appended, and that
        // envelope is never delivered (EventDispatcherConcurrencyTest reproduced 225-625 losses per 40k before this).
        synchronized (outbox) {
            long s = seq.incrementAndGet();
            outbox.add(new Envelope(s, groupId, payload));
            return s;
        }
    }

    /** One relay pass: each consumer (in parallel, up to workerThreads) processes up to batchSize envelopes. */
    public void drain() {
        List<Future<?>> fs = new ArrayList<>();
        for (Consumer c : List.copyOf(consumers)) fs.add(pool.submit(() -> drain(c)));
        for (Future<?> f : fs) {
            try { f.get(); } catch (Exception e) { throw new IllegalStateException("dispatcher worker failed", e); }
        }
        prune();
    }

    /** Drops envelopes every consumer has passed (N-18: the in-memory outbox does not grow or retain history). */
    private void prune() {
        long min = consumers.stream().mapToLong(c -> cursor.getOrDefault(c.name(), 0L)).min().orElse(seq.get());
        synchronized (outbox) { outbox.removeIf(e -> e.seq() <= min); }
    }

    private void drain(Consumer c) {
        Instant wait = retryAt.get(c.name());
        if (wait != null && clock.instant().isBefore(wait)) return;
        List<Envelope> pending;
        synchronized (outbox) {
            long from = cursor.get(c.name());
            pending = outbox.stream().filter(e -> e.seq() > from).limit(settings.batchSize()).toList();
        }
        for (Envelope e : pending) {
            String key = c.name() + "#" + e.seq();
            try {
                c.accept(e);
                blockedOnDeletion.remove(c.name());
                delivered.incrementAndGet();
                attempts.remove(key);
                retryAt.remove(c.name());
                cursor.put(c.name(), e.seq());
            } catch (Exception ex) {
                failures.incrementAndGet();
                int n = attempts.merge(key, 1, Integer::sum);
                if (n >= settings.maxAttempts() && mustDeliver(e.payload())) {
                    blockedOnDeletion.add(c.name());                       // never skip a deletion: block + alert (N-20)
                    retryAt.put(c.name(), clock.instant().plus(settings.maxBackoff()));
                    return;
                }
                if (n >= settings.maxAttempts()) {
                    deadLetters.add(new DeadLetter(c.name(), e, ex.getClass().getSimpleName()));   // ids only (N-18)
                    attempts.remove(key);
                    cursor.put(c.name(), e.seq());
                    continue;
                }
                retryAt.put(c.name(), clock.instant().plus(settings.backoff(n)));
                return;   // preserve order: stop this consumer until the retry is due
            }
        }
    }

    public long lag(String consumer) { return seq.get() - cursor.getOrDefault(consumer, 0L); }
    /** TASK-054: the largest lag over all registered consumers (0 = every projection has seen every published event). */
    public long maxLag() { long s = seq.get(); return cursor.values().stream().mapToLong(c -> s - c).max().orElse(0); }
    public List<DeadLetter> deadLetters() { return List.copyOf(deadLetters); }
    public long deliveredCount() { return delivered.get(); }
    /** Consumers stuck on a deletion they cannot apply: an operator alert condition (N-20). */
    public java.util.Set<String> blockedOnDeletion() { return java.util.Set.copyOf(blockedOnDeletion); }
    public int retainedEnvelopes() { return outbox.size(); }

    /** Re-delivers dead-lettered envelopes for a consumer after the cause is fixed. */
    public void redrive(String consumer) {
        List<DeadLetter> mine = deadLetters.stream().filter(d -> d.consumer().equals(consumer)).toList();
        deadLetters.removeAll(mine);
        for (DeadLetter d : mine) {
            try { consumers.stream().filter(c -> c.name().equals(consumer)).findFirst().orElseThrow().accept(d.envelope()); }
            catch (Exception ex) { deadLetters.add(new DeadLetter(consumer, d.envelope(), ex.getClass().getSimpleName())); }
        }
    }
    public long failureCount() { return failures.get(); }

    @Override public void close() { pool.shutdownNow(); }
}
