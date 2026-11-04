package app.dita.core.model;

import java.time.Instant;

/** Append-only history entry. Edits never overwrite earlier events. */
public record MessageEvent(String groupId, String messageId, Kind kind, String text, Instant occurredAt) {
    public enum Kind { CREATED, EDITED, DELETED }

    @Override public String toString() {   // N-17
        return "MessageEvent[" + groupId + "/" + messageId + " " + kind + " at " + occurredAt + ", text=<redacted>]";
    }
}
