package app.dita.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Provenance of an answer Dita published (Priority 6): which knowledge items and source messages it was built from.
 * Resolves a user's reply to that answer back to the items it may refine. No answer text is stored (it is derived).
 */
public record AnswerRecord(String groupId, String replyId, List<String> knowledgeIds, List<String> messageIds, Instant publishedAt) {
    public AnswerRecord {
        Objects.requireNonNull(groupId); Objects.requireNonNull(replyId); Objects.requireNonNull(publishedAt);
        knowledgeIds = List.copyOf(knowledgeIds);
        messageIds = List.copyOf(messageIds);
    }
}
