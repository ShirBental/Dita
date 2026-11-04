package app.dita.app.config;

import app.dita.core.events.EventDispatcher;
import org.springframework.context.SmartLifecycle;

/** Runs the in-memory outbox dispatcher on a fixed interval (IN_MEMORY backend; see InMemoryRuntimeConfiguration). */
public class DispatchWorker implements SmartLifecycle {
    private final EventDispatcher dispatcher;
    private final long intervalMs;
    private volatile Thread thread;

    public DispatchWorker(EventDispatcher dispatcher, DitaSettings s) {
        this.dispatcher = dispatcher;
        this.intervalMs = s.raw().processing().dispatch().pollInterval().toMillis();
    }

    @Override public void start() {
        thread = Thread.ofVirtual().name("dita-dispatch").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { dispatcher.drain(); Thread.sleep(intervalMs); }
                catch (InterruptedException e) { return; }
                catch (RuntimeException e) { /* consumer failures are tracked by the dispatcher */ }
            }
        });
    }

    @Override public void stop() { if (thread != null) thread.interrupt(); }
    @Override public boolean isRunning() { return thread != null && thread.isAlive(); }
}
