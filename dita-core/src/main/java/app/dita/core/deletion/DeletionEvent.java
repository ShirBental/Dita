package app.dita.core.deletion;

import java.time.Instant;
import java.util.List;

/**
 * Outbox topic {@code message.deleted}. Ids only, never text. Consumers re-read current state of the listed
 * knowledge/links from the source of truth (versioned, idempotent), so a late or replayed event cannot restore text.
 */
public record DeletionEvent(String groupId, String messageId, DeletionMode mode, DeletionSource source,
                            Instant createdAt, Instant deletedAt,
                            List<String> removedKnowledgeIds, List<String> updatedKnowledgeIds,
                            List<String> redactedKnowledgeIds, List<String> preservedWithDeletedSourceIds,
                            List<String> removedLinkKeys, List<String> addedLinkKeys,
                            List<String> affectedDitaReplyIds) {
    public DeletionEvent {
        removedKnowledgeIds = List.copyOf(removedKnowledgeIds);
        updatedKnowledgeIds = List.copyOf(updatedKnowledgeIds);
        redactedKnowledgeIds = List.copyOf(redactedKnowledgeIds);
        preservedWithDeletedSourceIds = List.copyOf(preservedWithDeletedSourceIds);
        removedLinkKeys = List.copyOf(removedLinkKeys);
        addedLinkKeys = List.copyOf(addedLinkKeys);
        affectedDitaReplyIds = List.copyOf(affectedDitaReplyIds);
    }
}
