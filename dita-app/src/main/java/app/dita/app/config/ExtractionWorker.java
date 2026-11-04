package app.dita.app.config;

import app.dita.core.knowledge.ExtractionService;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;

/**
 * Asynchronous extraction: processes APPROVED batches off the ingestion path. Logs batch ids and counts only, never
 * message text or model output.
 */
public final class ExtractionWorker implements SmartLifecycle {
    private static final System.Logger log = System.getLogger(ExtractionWorker.class.getName());
    static final long MAX_BACKOFF_MS = 30_000;
    private final ExtractionService service;
    private final long pollMs;
    private volatile Thread thread;
    private volatile boolean running;

    private final java.util.function.Predicate<app.dita.core.knowledge.ExtractionBatch> eligible;

    public ExtractionWorker(ExtractionService service, Duration poll) { this(service, poll, b -> true); }

    /** TASK-051 (Natasha M-3): {@code eligible} decides which APPROVED batches this process may send to its transport. */
    public ExtractionWorker(ExtractionService service, Duration poll, java.util.function.Predicate<app.dita.core.knowledge.ExtractionBatch> eligible) {
        this.service = service; this.pollMs = Math.max(20, poll.toMillis()); this.eligible = eligible;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        thread = Thread.ofPlatform().daemon().name("dita-extraction").start(() -> {
            String failing = null;                                   // class of the current failure, logged once
            long delay = pollMs;
            while (running) {
                try {
                    for (var r : service.processApproved(eligible)) {
                        log.log(System.Logger.Level.INFO, "extraction batch " + r.batchId() + " " + r.status() + " accepted=" + r.accepted()
                                + " duplicates=" + r.duplicates() + " rejected=" + r.rejections().size() + (r.error() == null ? "" : " error=" + r.error()));
                    }
                    if (failing != null) log.log(System.Logger.Level.INFO, "extraction passes recovered after " + failing);
                    failing = null; delay = pollMs;
                } catch (RuntimeException e) {
                    // Still an explicit failure (e.g. no V5 schema: UnsupportedOperationException), but logged once per
                    // change and retried with backoff instead of a warning every poll.
                    String cls = e.getClass().getSimpleName();
                    if (!cls.equals(failing)) log.log(System.Logger.Level.WARNING, "extraction pass failed: " + cls + " (retrying with backoff up to " + MAX_BACKOFF_MS / 1000 + "s; logged once)");
                    failing = cls; delay = Math.min(MAX_BACKOFF_MS, delay * 2);
                }
                try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
            }
        });
    }

    @Override public synchronized void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); try { thread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    @Override public boolean isRunning() { return running; }
}
