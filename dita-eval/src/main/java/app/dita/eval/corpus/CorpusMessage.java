package app.dita.eval.corpus;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** One synthetic corpus line: {id, author, sent_at, reply_to, forwarded, edited, text, deleted_at?}. */
public record CorpusMessage(String id, String author, Instant sentAt, String replyTo, boolean forwarded,
                            boolean edited, String text, Instant deletedAt) {
    /** DEC-012 early window: deletions within 5 minutes of sending are Track E. */
    public static final Duration EARLY_WINDOW = Duration.ofMinutes(5);

    public CorpusMessage {
        Objects.requireNonNull(id); Objects.requireNonNull(author); Objects.requireNonNull(sentAt); Objects.requireNonNull(text);
        if (deletedAt != null && deletedAt.isBefore(sentAt)) throw new IllegalArgumentException("deleted before sent: " + id);
    }

    /** Sent at or before {@code asOf} and not deleted at {@code asOf} (a deletion at exactly asOf counts as deleted). */
    public boolean visibleAt(Instant asOf) {
        return !sentAt.isAfter(asOf) && (deletedAt == null || deletedAt.isAfter(asOf));
    }

    public boolean deletedBy(Instant asOf) { return deletedAt != null && !deletedAt.isAfter(asOf); }

    /** "E", "L" or null. */
    public String deletionTrack() {
        if (deletedAt == null) return null;
        return Duration.between(sentAt, deletedAt).compareTo(EARLY_WINDOW) <= 0 ? "E" : "L";
    }

    @Override public String toString() { return "CorpusMessage[id=" + id + ", author=" + author + ", text=<redacted>]"; }
}
