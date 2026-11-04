package app.dita.core.deletion;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A platform-verified identity within ONE group, built by the adapter from authenticated platform data
 * (Telegram: from.id on the bot's authenticated getUpdates channel), never from message text (N-25).
 * {@code anonymousOrProxy}: anonymous admin or sender_chat; the human author is unknown.
 * {@code adminVerifiedAt}: when the platform member API last confirmed admin status for this group (null = not admin).
 * Trust boundary: adapters are application code in the same process; this is not an OS-level boundary.
 */
public record Requester(String groupId, String userId, boolean anonymousOrProxy, Instant adminVerifiedAt) {
    public static final Duration ADMIN_FRESHNESS = Duration.ofMinutes(10);

    public Requester {
        Objects.requireNonNull(groupId); Objects.requireNonNull(userId);
    }

    public static Requester member(String groupId, String userId) { return new Requester(groupId, userId, false, null); }
    public static Requester admin(String groupId, String userId, Instant verifiedAt) { return new Requester(groupId, userId, false, verifiedAt); }

    public boolean adminVerifiedAt(Instant at, Duration freshness) {
        return adminVerifiedAt != null && !adminVerifiedAt.isAfter(at.plusSeconds(60))
                && Duration.between(adminVerifiedAt, at).compareTo(freshness) <= 0;
    }
}
