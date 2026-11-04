package app.dita.core.model;

import java.util.Objects;

/**
 * Capability token for a single group's memory. Derived from the platform chat id by the adapter,
 * never from message content. Every port method requires one.
 */
public record GroupScope(String groupId) {
    public GroupScope {
        Objects.requireNonNull(groupId, "groupId");
        if (groupId.isBlank()) throw new IllegalArgumentException("blank groupId");
    }

    public void requireSame(String otherGroupId) {
        if (!groupId.equals(otherGroupId)) {
            throw new IsolationViolation("group " + otherGroupId + " is outside scope " + groupId);
        }
    }
}
