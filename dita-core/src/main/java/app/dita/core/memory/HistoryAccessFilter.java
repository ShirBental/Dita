package app.dita.core.memory;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Hit;
import app.dita.core.store.MessageStore;

import java.time.Instant;
import java.util.List;

/**
 * Applies the configured historical-memory access to retrieval results for a specific requester. Unknown join time
 * under SINCE_REQUESTER_JOINED yields nothing (fail closed). Always applied server-side.
 */
public final class HistoryAccessFilter {
    private final HistoryAccess mode;
    private final MembershipPort membership;
    private final MessageStore store;

    public HistoryAccessFilter(HistoryAccess mode, MembershipPort membership, MessageStore store) {
        this.mode = mode; this.membership = membership; this.store = store;
    }

    /** True when every member may see the whole group history (knowledge answers are only served then, for now). */
    public boolean fullHistory() { return mode == HistoryAccess.FULL_GROUP_HISTORY; }

    public List<Hit> filter(GroupScope scope, String requesterId, List<Hit> hits) {
        if (mode == HistoryAccess.FULL_GROUP_HISTORY) return hits;
        Instant joined = membership.joinedAt(scope, requesterId).orElse(null);
        if (joined == null) return List.of();
        return hits.stream().filter(h -> store.find(scope, h.messageId()).map(Message::sentAt)
                .map(t -> !t.isBefore(joined)).orElse(false)).toList();
    }
}
