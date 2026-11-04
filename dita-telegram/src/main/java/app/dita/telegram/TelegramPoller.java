package app.dita.telegram;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Long-polling loop. Offset is acknowledged only after handling, so a crash re-delivers (the adapter is idempotent).
 * NOT RUN against the live API in this environment.
 */
public final class TelegramPoller implements Runnable {
    private static final System.Logger log = System.getLogger(TelegramPoller.class.getName());
    private final TelegramGateway gateway;
    private final TelegramAdapter adapter;
    private final int timeoutSeconds, limit;
    private volatile boolean running = true;
    private long offset = 0;

    public TelegramPoller(TelegramGateway gateway, TelegramAdapter adapter, int timeoutSeconds, int limit) {
        this.gateway = gateway; this.adapter = adapter; this.timeoutSeconds = timeoutSeconds; this.limit = limit;
    }

    public void stop() { running = false; }

    /**
     * Handles one batch in order. The offset (= acknowledgement to Telegram) advances only past updates that were
     * handled. On a {@link RetryableUpdateException} the batch stops at that update and the exception propagates, so
     * {@link #run()} backs off and the next getUpdates call returns the same update again (Shuri B1).
     */
    public int pollOnce() throws Exception {
        int n = 0;
        for (JsonNode u : gateway.getUpdates(offset, timeoutSeconds, limit)) {
            adapter.handle(u);                                                     // throws Retryable: offset NOT advanced
            offset = Math.max(offset, u.path("update_id").asLong() + 1);
            n++;
        }
        return n;
    }

    /** Next offset to request (exclusive acknowledgement point). Package-private for tests. */
    long offset() { return offset; }

    @Override public void run() {
        long backoff = 1000;
        while (running) {
            try { pollOnce(); backoff = 1000; }
            catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "poll failed: " + e.getMessage());   // gateway messages are redacted
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }
}
