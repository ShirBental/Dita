package app.dita.store.postgres;

import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.knowledge.InMemoryExtractionQueue;
import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ExtractionQueue contract, derived from InMemoryExtractionQueue (authoritative). Runs for every implementation.
 * Ids only, synthetic. The Postgres queue references real message rows (composite FK), so each test first seeds the
 * messages it enqueues through {@link #seedMessages}; the in-memory reference does not need them.
 */
abstract class ExtractionQueueContractTest {
    static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    protected ExtractionQueue queue;
    protected GroupScope g;
    private final List<GroupScope> created = new ArrayList<>();

    protected abstract ExtractionQueue createQueue();
    protected abstract GroupScope newGroup(String label);
    protected abstract void seedMessages(GroupScope scope, String... messageIds);

    @BeforeEach void setUp() {
        queue = createQueue();
        g = group("xq");
        seedMessages(g, "m1", "m2", "m3", "m4", "m5", "m6");
    }

    GroupScope group(String label) { GroupScope s = newGroup(label); created.add(s); return s; }

    /**
     * An APPROVED batch is standing consent to send its messages to the model (E-1). On a shared database a test
     * that leaves one behind makes the next application run process it (observed 2026-10-30: the PostgreSQL
     * knowledge slice's worker picked up a batch approved here). Tests must end every approval in a terminal state;
     * this guard finalizes any leak (RUNNING → FAILED "test-cleanup") and then fails the leaking test.
     */
    @AfterEach void noApprovalIsLeftBehind() {
        List<String> leaked = new ArrayList<>();
        for (GroupScope s : created) {
            for (ExtractionBatch b : queue.batches(s, ExtractionBatch.Status.APPROVED)) {
                leaked.add(b.id());
                ExtractionBatch running = b.with(ExtractionBatch.Status.RUNNING, b.attempts(), b.approvalRef(), null, b.updatedAt());
                queue.transition(s, ExtractionBatch.Status.APPROVED, running);
                queue.transition(s, ExtractionBatch.Status.RUNNING,
                        running.with(ExtractionBatch.Status.FAILED, b.attempts(), b.approvalRef(), "test-cleanup", b.updatedAt()));
            }
        }
        if (!leaked.isEmpty()) fail("test left APPROVED batches behind (finalized as FAILED): " + leaked);
    }

    /** Ends an approval the test created, as a worker would after a failure. */
    void finalizeApproved(GroupScope s, ExtractionBatch approved) {
        ExtractionBatch running = approved.with(ExtractionBatch.Status.RUNNING, approved.attempts() + 1, approved.approvalRef(), null, approved.updatedAt());
        assertTrue(queue.transition(s, ExtractionBatch.Status.APPROVED, running));
        assertTrue(queue.transition(s, ExtractionBatch.Status.RUNNING,
                running.with(ExtractionBatch.Status.FAILED, running.attempts(), running.approvalRef(), "test-done", approved.updatedAt())));
    }

    static Instant at(int minute) { return T0.plus(Duration.ofMinutes(minute)); }
    static ExtractionBatch.Target t(String id) { return new ExtractionBatch.Target(id, null); }

    void enqueueAll(GroupScope s, String... ids) {
        for (int i = 0; i < ids.length; i++) queue.enqueue(s, t(ids[i]), at(i));
    }

    @Test void enqueueIsIdempotentFirstWins() {
        queue.enqueue(g, new ExtractionBatch.Target("m1", "dita-answer-7"), at(0));
        queue.enqueue(g, t("m1"), at(1));
        queue.enqueue(g, new ExtractionBatch.Target("m1", "dita-answer-8"), at(2));
        List<ExtractionBatch> out = queue.collect(g, 10, at(3));
        assertEquals(1, out.size());
        assertEquals(List.of(new ExtractionBatch.Target("m1", "dita-answer-7")), out.get(0).targets());
    }

    @Test void collectChunksOldestFirstWithDeterministicIds() {
        enqueueAll(g, "m1", "m2", "m3", "m4", "m5");
        List<ExtractionBatch> out = queue.collect(g, 2, at(10));
        assertEquals(List.of(List.of("m1", "m2"), List.of("m3", "m4"), List.of("m5")),
                out.stream().map(ExtractionBatch::messageIds).toList());
        for (ExtractionBatch b : out) {
            assertEquals(InMemoryExtractionQueue.batchId(g.groupId(), b.targets()), b.id());
            assertEquals(ExtractionBatch.Status.COLLECTED, b.status());
            assertEquals(0, b.attempts());
            assertEquals(at(10), b.createdAt());
            assertEquals(at(10), b.updatedAt());
            assertEquals(g.groupId(), b.groupId());
            assertEquals(Optional.of(b), queue.batch(g, b.id()));
        }
        assertEquals(out, queue.batches(g, ExtractionBatch.Status.COLLECTED));
        assertEquals(List.of(), queue.collect(g, 2, at(11)));
        assertEquals(Optional.empty(), queue.batch(g, "xb-unknown"));
        queue.enqueue(g, t("m6"), at(12));
        assertThrows(IllegalArgumentException.class, () -> queue.collect(g, 0, at(12)));
    }

    @Test void aMessageIsInAtMostOneBatch() {
        enqueueAll(g, "m1", "m2");
        List<ExtractionBatch> first = queue.collect(g, 10, at(5));
        assertEquals(List.of("m1", "m2"), first.get(0).messageIds());
        queue.enqueue(g, t("m1"), at(6));                                   // already batched: ignored
        queue.enqueue(g, t("m6"), at(7));
        List<ExtractionBatch> second = queue.collect(g, 10, at(8));
        assertEquals(List.of(List.of("m6")), second.stream().map(ExtractionBatch::messageIds).toList());
        assertEquals(2, queue.batches(g, ExtractionBatch.Status.COLLECTED).size());
    }

    @Test void transitionIsCompareAndSet() {
        enqueueAll(g, "m1", "m2", "m3");
        ExtractionBatch b = queue.collect(g, 10, at(5)).get(0);
        ExtractionBatch approved = b.with(ExtractionBatch.Status.APPROVED, 0, "approval-synthetic-1", null, at(6));
        assertTrue(queue.transition(g, ExtractionBatch.Status.COLLECTED, approved));
        assertFalse(queue.transition(g, ExtractionBatch.Status.COLLECTED,
                b.with(ExtractionBatch.Status.APPROVED, 0, "approval-synthetic-2", null, at(7))), "stale expected status");
        assertEquals(Optional.of(approved), queue.batch(g, b.id()));
        assertTrue(queue.groupsWithApproved().contains(g.groupId()));

        ExtractionBatch running = approved.with(ExtractionBatch.Status.RUNNING, 1, approved.approvalRef(), null, at(8));
        assertTrue(queue.transition(g, ExtractionBatch.Status.APPROVED, running));
        assertFalse(queue.groupsWithApproved().contains(g.groupId()));
        ExtractionBatch failed = running.with(ExtractionBatch.Status.FAILED, 1, running.approvalRef(), "SocketTimeoutException", at(9));
        assertTrue(queue.transition(g, ExtractionBatch.Status.RUNNING, failed));
        assertEquals(Optional.of(failed), queue.batch(g, b.id()));
        assertEquals(List.of(failed), queue.batches(g, ExtractionBatch.Status.FAILED));
        assertEquals(List.of(), queue.batches(g, ExtractionBatch.Status.RUNNING));

        ExtractionBatch ghost = new ExtractionBatch("xb-ghost", g.groupId(), List.of(t("m1")), ExtractionBatch.Status.APPROVED,
                0, "approval-synthetic-3", null, at(1), at(1));
        assertFalse(queue.transition(g, ExtractionBatch.Status.COLLECTED, ghost));
    }

    @Test void groupsAreIsolated() {
        GroupScope b = group("xq-other");
        seedMessages(b, "m1", "m2");
        enqueueAll(g, "m1", "m2");
        enqueueAll(b, "m1");                                                // same message id, other group: independent
        ExtractionBatch ga = queue.collect(g, 10, at(5)).get(0);
        assertEquals(List.of(), queue.batches(b, ExtractionBatch.Status.COLLECTED));
        assertEquals(Optional.empty(), queue.batch(b, ga.id()));
        List<ExtractionBatch> gb = queue.collect(b, 10, at(6));
        assertEquals(List.of(List.of("m1")), gb.stream().map(ExtractionBatch::messageIds).toList());
        assertFalse(gb.get(0).id().equals(ga.id()), "batch ids include the group");

        ExtractionBatch approvedA = ga.with(ExtractionBatch.Status.APPROVED, 0, "approval-synthetic-a", null, at(7));
        assertThrows(IsolationViolation.class, () -> queue.transition(b, ExtractionBatch.Status.COLLECTED, approvedA));
        ExtractionBatch forged = new ExtractionBatch(ga.id(), b.groupId(), ga.targets(), ExtractionBatch.Status.APPROVED, 0,
                "approval-forged", null, at(7), at(7));
        assertFalse(queue.transition(b, ExtractionBatch.Status.COLLECTED, forged), "B's scope cannot reach A's batch");
        assertEquals(ExtractionBatch.Status.COLLECTED, queue.batch(g, ga.id()).orElseThrow().status());

        assertTrue(queue.transition(g, ExtractionBatch.Status.COLLECTED, approvedA));
        assertTrue(queue.groupsWithApproved().contains(g.groupId()));
        assertFalse(queue.groupsWithApproved().contains(b.groupId()));
        finalizeApproved(g, approvedA);                                     // never leave standing consent behind
        assertFalse(queue.groupsWithApproved().contains(g.groupId()));
    }
}
