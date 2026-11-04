package app.dita.telegram;

import app.dita.core.coordination.ApprovalAct;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Adapter settings derived from validated dita.* configuration.
 * {@code allowedChatIds}: the only chats served (OPEN-6, C-1). {@code allowedAuthorIds}: the only Telegram user ids
 * (from.id) whose messages, commands and button presses are accepted in those chats (TASK-040, NATASHA-EGRESS-001 E-3).
 * Both are fail-closed: an empty set accepts nothing.
 * {@code ignoreBefore} (nullable = off): updates dated before this instant (the run's start) are acknowledged but
 * never processed, so a pending backlog or a post-restart redelivery is never ingested or answered (TASK-040, Natasha B1).
 */
public record TelegramAdapterSettings(Set<Long> allowedChatIds, String botUsername, boolean coordinationEnabled,
                                      int maxTasksPerPlan, Set<ApprovalAct> allowedActs, Duration groupRemovalRetention,
                                      boolean adminsMayForgetOthers, Set<Long> allowedAuthorIds, Instant ignoreBefore) {
    public TelegramAdapterSettings {
        allowedChatIds = Set.copyOf(allowedChatIds);
        allowedActs = Set.copyOf(allowedActs);
        allowedAuthorIds = Set.copyOf(allowedAuthorIds);
    }
}
