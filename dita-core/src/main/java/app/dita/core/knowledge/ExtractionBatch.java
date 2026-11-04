package app.dita.core.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A contextual batch of one group's messages for knowledge extraction (hybrid pipeline). Ingestion only enqueues; a
 * batch is sent to the proposer (Muse) ONLY after an explicit approval (NATASHA-EGRESS-001 E-1), asynchronously.
 * {@code id} is a hash of the group and the target message ids, so re-collecting the same messages is idempotent.
 */
public record ExtractionBatch(String id, String groupId, List<Target> targets, Status status, int attempts,
                              String approvalRef, String lastError, Instant createdAt, Instant updatedAt) {
    /** A message to extract from; {@code repliesToAnswerId} is set when it replies to an answer Dita published. */
    public record Target(String messageId, String repliesToAnswerId) {
        public Target { Objects.requireNonNull(messageId); }
    }

    public enum Status { COLLECTED, APPROVED, RUNNING, DONE, FAILED }

    public ExtractionBatch {
        Objects.requireNonNull(id); Objects.requireNonNull(groupId); Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        targets = List.copyOf(targets);
        if (targets.isEmpty()) throw new IllegalArgumentException("empty batch");
        if (status != Status.COLLECTED && approvalRef == null) throw new IllegalArgumentException("a batch leaves COLLECTED only with an approval");
    }

    public List<String> messageIds() { return targets.stream().map(Target::messageId).toList(); }

    public ExtractionBatch with(Status s, int attemptCount, String approval, String error, Instant at) {
        return new ExtractionBatch(id, groupId, targets, s, attemptCount, approval, error, createdAt, at);
    }
}
