package app.dita.core.coordination;

import java.time.Duration;
import java.time.Instant;

/** Removing Dita starts a recoverable 21-day retention period; afterwards the group's memory is purged. */
public final class GroupLifecycle {
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(21);
    public enum Status { ACTIVE, REMOVED_RETENTION, PURGED }

    private final Duration retention;
    private Status status = Status.ACTIVE;

    public GroupLifecycle() { this(DEFAULT_RETENTION); }
    public GroupLifecycle(Duration retention) { this.retention = retention; }
    private Instant purgeAfter;

    public void botRemoved(Instant at) {
        if (status != Status.ACTIVE) return;
        status = Status.REMOVED_RETENTION;
        purgeAfter = at.plus(retention);   // each removal starts a new window
    }

    /** Re-adding within the window restores memory; afterwards the old memory is gone. */
    public boolean botReadded(Instant at) {
        if (status == Status.REMOVED_RETENTION && at.isBefore(purgeAfter)) {
            status = Status.ACTIVE;
            purgeAfter = null;
            return true;
        }
        return status == Status.ACTIVE;
    }

    public boolean purgeDue(Instant now) {
        return status == Status.REMOVED_RETENTION && !now.isBefore(purgeAfter);
    }

    public void markPurged(Instant now) {
        if (!purgeDue(now)) throw new IllegalStateException("purge not due");
        status = Status.PURGED;
    }

    public Status status() { return status; }
    public Instant purgeAfter() { return purgeAfter; }
}
