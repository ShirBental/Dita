package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable extraction state (PostgreSQL V5 tables in dita-store-postgres; in-memory reference here). Scoped like
 * MessageStore: no method reads or writes another group.
 */
public interface ExtractionQueue {
    /** Marks a message as pending extraction. Idempotent per (group, message). */
    void enqueue(GroupScope scope, ExtractionBatch.Target target, Instant at);

    /**
     * Moves up to {@code maxSize} pending messages (oldest first) into new COLLECTED batches and returns them.
     * A message is in at most one batch.
     */
    List<ExtractionBatch> collect(GroupScope scope, int maxSize, Instant at);

    /**
     * TASK-054 (question-time preparation): like {@link #collect(GroupScope, int, Instant)}, but moves ONLY the pending
     * messages in {@code only} (oldest first); every other pending message stays pending for later questions.
     */
    List<ExtractionBatch> collect(GroupScope scope, java.util.Collection<String> only, int maxSize, Instant at);
    /** Ids of this group's pending (not yet batched) messages, oldest first. */
    List<String> pendingIds(GroupScope scope);
    Optional<ExtractionBatch> batch(GroupScope scope, String batchId);
    List<ExtractionBatch> batches(GroupScope scope, ExtractionBatch.Status status);

    /** Compare-and-set on status: returns false (and changes nothing) if the stored status is not {@code expected}. */
    boolean transition(GroupScope scope, ExtractionBatch.Status expected, ExtractionBatch next);

    /** Groups that currently have APPROVED batches (for the worker). */
    List<String> groupsWithApproved();
}
