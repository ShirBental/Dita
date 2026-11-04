package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;

import java.util.List;

/**
 * Proposes knowledge for an APPROVED batch (implemented by dita-muse). {@code targets} may be extracted from;
 * {@code context} is shown for understanding only (earlier messages and reply parents) and can never be cited as
 * asserting evidence. Implementations must only ever receive SYNTHETIC messages (checked by the caller and by Muse).
 */
public interface KnowledgeProposer {
    Proposal propose(GroupScope scope, String batchId, List<Message> targets, List<Message> context);

    /** No extraction backend configured: every call fails explicitly. */
    KnowledgeProposer NONE = (s, b, t, c) -> { throw new IllegalStateException("no knowledge proposer is configured (Muse disabled)"); };
}
