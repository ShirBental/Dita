package app.dita.core.answer;

import java.time.Instant;

/** Points at one original message; {@code quote} must be a verbatim substring of its current text. */
public record Citation(String messageId, String authorId, Instant sentAt, String quote,
                       Evidence evidence, int polarity, boolean qualified) {
    /** EXPLICIT: matched the question directly. INFERRED: reached only via an inferred link. */
    public enum Evidence { EXPLICIT, INFERRED }
}
