package app.dita.core.deletion;

import java.time.Duration;
import java.time.Instant;

/**
 * Product-owner policy (DEC-012/013). Deleted within the early window (inclusive) of the ORIGINAL send time:
 * EARLY_PURGE. Later: LATE_TEXT_ONLY. Edits never restart the clock (createdAt is the original send time).
 * The deletion instant is the platform timestamp of the deletion signal (platform event, or the /forget
 * message's own date), never the bot's processing time. Skew before creation is clamped to creation.
 */
public final class DeletionPolicy {
    public static final Duration DEFAULT_EARLY_WINDOW = Duration.ofMinutes(5);

    private DeletionPolicy() {}

    public static DeletionMode classify(Instant createdAt, Instant deletedAt) {
        return classify(createdAt, deletedAt, DEFAULT_EARLY_WINDOW);
    }

    public static DeletionMode classify(Instant createdAt, Instant deletedAt, Duration earlyWindow) {
        Instant effective = clamp(createdAt, deletedAt);
        return Duration.between(createdAt, effective).compareTo(earlyWindow) <= 0
                ? DeletionMode.EARLY_PURGE : DeletionMode.LATE_TEXT_ONLY;
    }

    public static Instant clamp(Instant createdAt, Instant deletedAt) {
        return deletedAt.isBefore(createdAt) ? createdAt : deletedAt;
    }
}
