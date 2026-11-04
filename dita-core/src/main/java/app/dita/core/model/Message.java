package app.dita.core.model;

import java.time.Instant;
import java.util.Objects;

/** Storage unit. Original text is preserved in the store's event history; this is the current view. */
public record Message(String id, String groupId, String authorId, Instant sentAt, String replyToId,
                      String text, SourceKind sourceKind, boolean deleted) {
    public Message {
        Objects.requireNonNull(id); Objects.requireNonNull(groupId); Objects.requireNonNull(authorId);
        Objects.requireNonNull(sentAt); Objects.requireNonNull(sourceKind);
        sentAt = sentAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);   // Postgres timestamptz precision (Shuri #6)
        if (!deleted) Objects.requireNonNull(text);
    }

    /** N-17: never print message text (logs, exceptions, outbox last_error). */
    @Override public String toString() {
        return "Message[id=" + id + ", groupId=" + groupId + ", author=" + authorId + ", sentAt=" + sentAt
                + ", replyTo=" + replyToId + ", deleted=" + deleted + ", text=<redacted>]";
    }

    public Message withText(String newText) {
        if (deleted) throw new IllegalStateException("deleted message cannot be edited");
        return new Message(id, groupId, authorId, sentAt, replyToId, newText, sourceKind, false);
    }

    public Message asDeleted() {
        return new Message(id, groupId, authorId, sentAt, replyToId, null, sourceKind, true);
    }
}
