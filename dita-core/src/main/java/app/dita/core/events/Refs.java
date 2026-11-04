package app.dita.core.events;

/**
 * Outbox payloads carry identifiers only (N-18). Consumers read current state from the source of truth, so
 * neither the outbox, retries nor dead letters ever hold message text or knowledge wording.
 */
public final class Refs {
    private Refs() {}
    public record MessageRef(String groupId, String messageId) {}
    public record LinkRef(String groupId, String linkKey) {}
    public record KnowledgeRef(String groupId, String knowledgeId) {}
}
