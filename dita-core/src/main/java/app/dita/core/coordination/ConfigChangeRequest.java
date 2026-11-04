package app.dita.core.coordination;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Group configuration change. Applies only with unanimous approval of the admins current at request time.
 * If the admin set changes before completion, the request becomes STALE and must be re-requested.
 */
public final class ConfigChangeRequest {
    public enum Status { PENDING, APPLIED, REJECTED, STALE }

    private final Map<String, Object> proposed;
    private final Set<String> requiredAdmins;
    private final Set<String> approved = new HashSet<>();
    private Status status = Status.PENDING;

    public ConfigChangeRequest(Map<String, Object> proposed, Set<String> currentAdmins) {
        if (currentAdmins.isEmpty()) throw new IllegalArgumentException("group has no admins");
        this.proposed = Map.copyOf(proposed);
        this.requiredAdmins = Set.copyOf(currentAdmins);
    }

    /** {@code adminsNow} comes from the platform at decision time, never from message content. */
    public Status decide(String adminId, boolean approve, Set<String> adminsNow) {
        if (status != Status.PENDING) return status;
        if (!adminsNow.equals(requiredAdmins)) return status = Status.STALE;
        if (!requiredAdmins.contains(adminId)) throw new IllegalStateException(adminId + " is not an admin");
        if (!approve) return status = Status.REJECTED;
        approved.add(adminId);
        if (approved.containsAll(requiredAdmins)) status = Status.APPLIED;
        return status;
    }

    public Status status() { return status; }
    public Map<String, Object> proposed() { return proposed; }
}
