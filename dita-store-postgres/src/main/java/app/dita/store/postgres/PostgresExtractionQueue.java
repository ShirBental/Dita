package app.dita.store.postgres;

import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.knowledge.InMemoryExtractionQueue;
import app.dita.core.model.GroupScope;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable {@link ExtractionQueue} on the V5 tables (extraction_pending, extraction_batch, extraction_batch_target).
 * Semantics follow {@link InMemoryExtractionQueue} (authoritative): idempotent enqueue, oldest-first chunked collect with
 * deterministic batch ids ({@link InMemoryExtractionQueue#batchId}), a message in at most one batch (also enforced by
 * UNIQUE (group_id, message_id) on extraction_batch_target), compare-and-set transitions.
 * <p>Every scoped operation runs in a {@link PostgresMessageStore#atomically} transaction (SET LOCAL app.group_id, RLS)
 * and also filters on group_id. Ids only: no message text is read or written here. Requires migration V5.
 */
public final class PostgresExtractionQueue implements ExtractionQueue {
    private final JdbcTemplate jdbc;
    private final PostgresMessageStore tx;

    /** {@code store} must use the same DataSource, so this queue joins its group-bound transactions. */
    public PostgresExtractionQueue(DataSource dataSource, PostgresMessageStore store) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = Objects.requireNonNull(store, "store");
    }

    public PostgresExtractionQueue(DataSource dataSource) { this(dataSource, new PostgresMessageStore(dataSource)); }

    private <T> T scoped(GroupScope scope, java.util.function.Supplier<T> work) {
        return tx.atomically(scope, () -> {
            if (!tx.hasV5()) throw new UnsupportedOperationException("PostgresExtractionQueue requires migration V5__knowledge_v2.sql, which is not applied");
            return work.get();
        });
    }

    @Override public void enqueue(GroupScope scope, ExtractionBatch.Target t, Instant at) {
        Objects.requireNonNull(t, "target"); Objects.requireNonNull(at, "at");
        scoped(scope, () -> {
            // Already batched → no-op (in-memory: `batched` check); already pending → first enqueue wins (putIfAbsent).
            jdbc.update("""
                    INSERT INTO extraction_pending (group_id, message_id, replies_to_answer_id, enqueued_at)
                    SELECT ?::text, ?::text, ?::text, ?::timestamptz
                    WHERE NOT EXISTS (SELECT 1 FROM extraction_batch_target WHERE group_id = ? AND message_id = ?)
                    ON CONFLICT (group_id, message_id) DO NOTHING""",
                    scope.groupId(), t.messageId(), t.repliesToAnswerId(), PostgresMessageStore.ts(at), scope.groupId(), t.messageId());
            return null;
        });
    }

    @Override public List<ExtractionBatch> collect(GroupScope scope, int maxSize, Instant at) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        Objects.requireNonNull(at, "at");
        return scoped(scope, () -> {
            List<ExtractionBatch> out = new ArrayList<>();
            while (true) {
                // DELETE ... RETURNING is the claim: a concurrent collector in the same group blocks on the row locks and
                // then skips rows already taken, so each pending message lands in exactly one batch. No FOR UPDATE needed
                // (dita_app has no UPDATE on extraction_pending).
                List<Object[]> claimed = new ArrayList<>(jdbc.query("""
                        DELETE FROM extraction_pending WHERE group_id = ? AND seq IN (
                            SELECT seq FROM extraction_pending WHERE group_id = ? ORDER BY seq LIMIT ?)
                        RETURNING seq, message_id, replies_to_answer_id""",
                        (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)},
                        scope.groupId(), scope.groupId(), maxSize));
                if (claimed.isEmpty()) break;
                claimed.sort((a, b) -> Long.compare((Long) a[0], (Long) b[0]));
                List<ExtractionBatch.Target> chunk = new ArrayList<>();
                for (Object[] c : claimed) {
                    String mid = (String) c[1];
                    boolean batched = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM extraction_batch_target WHERE group_id = ? AND message_id = ?)",
                            Boolean.class, scope.groupId(), mid));
                    if (!batched) chunk.add(new ExtractionBatch.Target(mid, (String) c[2]));   // enqueue/collect race
                }
                if (chunk.isEmpty()) continue;
                ExtractionBatch b = new ExtractionBatch(InMemoryExtractionQueue.batchId(scope.groupId(), chunk), scope.groupId(),
                        chunk, ExtractionBatch.Status.COLLECTED, 0, null, null, at, at);
                int inserted = jdbc.update("""
                        INSERT INTO extraction_batch (group_id, id, status, attempts, approval_ref, last_error, created_at, updated_at)
                        VALUES (?, ?, ?, 0, NULL, NULL, ?, ?) ON CONFLICT (group_id, id) DO NOTHING""",
                        scope.groupId(), b.id(), b.status().name(), PostgresMessageStore.ts(at), PostgresMessageStore.ts(at));
                if (inserted == 1) {
                    List<Object[]> rows = new ArrayList<>();
                    for (int i = 0; i < chunk.size(); i++) {
                        rows.add(new Object[]{scope.groupId(), b.id(), i, chunk.get(i).messageId(), chunk.get(i).repliesToAnswerId()});
                    }
                    jdbc.batchUpdate("""
                            INSERT INTO extraction_batch_target (group_id, batch_id, ordinal, message_id, replies_to_answer_id)
                            VALUES (?, ?, ?, ?, ?)""", rows);
                }
                out.add(b);
            }
            return List.copyOf(out);
        });
    }

    /** TASK-054: claims ONLY the listed pending messages (same DELETE ... RETURNING claim as collect); others stay pending. */
    @Override public List<ExtractionBatch> collect(GroupScope scope, java.util.Collection<String> only, int maxSize, Instant at) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        Objects.requireNonNull(at, "at");
        List<String> ids = List.copyOf(new java.util.LinkedHashSet<>(only));
        if (ids.isEmpty()) return List.of();
        return scoped(scope, () -> {
            String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            List<Object> args = new ArrayList<>(List.of(scope.groupId()));
            args.addAll(ids);
            List<Object[]> claimed = new ArrayList<>(jdbc.query(
                    "DELETE FROM extraction_pending WHERE group_id = ? AND message_id IN (" + in + ") RETURNING seq, message_id, replies_to_answer_id",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, args.toArray()));
            claimed.sort((a, b) -> Long.compare((Long) a[0], (Long) b[0]));
            List<ExtractionBatch> out = new ArrayList<>();
            List<ExtractionBatch.Target> chunk = new ArrayList<>();
            for (Object[] c : claimed) {
                String mid = (String) c[1];
                boolean batched = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM extraction_batch_target WHERE group_id = ? AND message_id = ?)",
                        Boolean.class, scope.groupId(), mid));
                if (batched) continue;                                                   // enqueue/collect race
                chunk.add(new ExtractionBatch.Target(mid, (String) c[2]));
                if (chunk.size() == maxSize) { out.add(insertBatch(scope, chunk, at)); chunk = new ArrayList<>(); }
            }
            if (!chunk.isEmpty()) out.add(insertBatch(scope, chunk, at));
            return List.copyOf(out);
        });
    }

    private ExtractionBatch insertBatch(GroupScope scope, List<ExtractionBatch.Target> chunk, Instant at) {
        ExtractionBatch b = new ExtractionBatch(InMemoryExtractionQueue.batchId(scope.groupId(), chunk), scope.groupId(),
                List.copyOf(chunk), ExtractionBatch.Status.COLLECTED, 0, null, null, at, at);
        int inserted = jdbc.update("""
                INSERT INTO extraction_batch (group_id, id, status, attempts, approval_ref, last_error, created_at, updated_at)
                VALUES (?, ?, ?, 0, NULL, NULL, ?, ?) ON CONFLICT (group_id, id) DO NOTHING""",
                scope.groupId(), b.id(), b.status().name(), PostgresMessageStore.ts(at), PostgresMessageStore.ts(at));
        if (inserted == 1) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < chunk.size(); i++)
                rows.add(new Object[]{scope.groupId(), b.id(), i, chunk.get(i).messageId(), chunk.get(i).repliesToAnswerId()});
            jdbc.batchUpdate("""
                    INSERT INTO extraction_batch_target (group_id, batch_id, ordinal, message_id, replies_to_answer_id)
                    VALUES (?, ?, ?, ?, ?)""", rows);
        }
        return b;
    }

    @Override public List<String> pendingIds(GroupScope scope) {
        return scoped(scope, () -> jdbc.queryForList(
                "SELECT message_id FROM extraction_pending WHERE group_id = ? ORDER BY seq", String.class, scope.groupId()));
    }

    @Override public Optional<ExtractionBatch> batch(GroupScope scope, String batchId) {
        Objects.requireNonNull(batchId, "batchId");
        return scoped(scope, () -> load(scope, "id", batchId).stream().findFirst());
    }

    @Override public List<ExtractionBatch> batches(GroupScope scope, ExtractionBatch.Status status) {
        Objects.requireNonNull(status, "status");
        return scoped(scope, () -> load(scope, "status", status.name()));
    }

    @Override public boolean transition(GroupScope scope, ExtractionBatch.Status expected, ExtractionBatch next) {
        Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(next, "next");
        scope.requireSame(next.groupId());
        // Compare-and-set in one statement: the row lock serialises concurrent transitions; the loser sees the new status.
        // Targets and createdAt are immutable (as in core, where `next` is derived via ExtractionBatch.with).
        return scoped(scope, () -> jdbc.update("""
                UPDATE extraction_batch SET status = ?, attempts = ?, approval_ref = ?, last_error = ?, updated_at = ?
                WHERE group_id = ? AND id = ? AND status = ?""",
                next.status().name(), next.attempts(), next.approvalRef(), next.lastError(), PostgresMessageStore.ts(next.updatedAt()),
                scope.groupId(), next.id(), expected.name()) == 1);
    }

    /**
     * Cross-group by nature (the worker's poll). RLS hides every batch unless app.group_id is set, so this walks the
     * group directory (chat_group has no RLS) and checks each group inside its own SET LOCAL scope: no RLS bypass,
     * no SECURITY DEFINER function. Returns group ids only, sorted. Cost: one indexed query per registered group.
     */
    @Override public List<String> groupsWithApproved() {
        List<String> groups = jdbc.queryForList("SELECT id FROM chat_group ORDER BY id COLLATE \"C\"", String.class);
        List<String> out = new ArrayList<>();
        for (String g : groups) {
            boolean any = scoped(new GroupScope(g), () -> Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM extraction_batch WHERE group_id = ? AND status = 'APPROVED')", Boolean.class, g)));
            if (any) out.add(g);
        }
        return List.copyOf(out);
    }

    private record Head(String id, ExtractionBatch.Status status, int attempts, String approval, String error, Instant created, Instant updated) {}

    /** {@code column} is a constant from this class ("id" or "status"), never caller input. */
    private List<ExtractionBatch> load(GroupScope scope, String column, String arg) {
        if (!column.equals("id") && !column.equals("status")) throw new IllegalArgumentException(column);
        List<Head> heads = jdbc.query("SELECT id, status, attempts, approval_ref, last_error, created_at, updated_at"
                        + " FROM extraction_batch WHERE group_id = ? AND " + column + " = ? ORDER BY seq",
                (rs, n) -> new Head(rs.getString(1), ExtractionBatch.Status.valueOf(rs.getString(2)), rs.getInt(3),
                        rs.getString(4), rs.getString(5), instant(rs, 6), instant(rs, 7)),
                scope.groupId(), arg);
        if (heads.isEmpty()) return List.of();
        Map<String, List<ExtractionBatch.Target>> targets = new LinkedHashMap<>();
        jdbc.query("SELECT t.batch_id, t.message_id, t.replies_to_answer_id FROM extraction_batch_target t"
                        + " JOIN extraction_batch b ON b.group_id = t.group_id AND b.id = t.batch_id"
                        + " WHERE t.group_id = ? AND b." + column + " = ?"
                        + " ORDER BY t.batch_id, t.ordinal",
                rs -> { targets.computeIfAbsent(rs.getString(1), x -> new ArrayList<>())
                        .add(new ExtractionBatch.Target(rs.getString(2), rs.getString(3))); },
                scope.groupId(), arg);
        return heads.stream().map(h -> new ExtractionBatch(h.id(), scope.groupId(), targets.getOrDefault(h.id(), List.of()),
                h.status(), h.attempts(), h.approval(), h.error(), h.created(), h.updated())).toList();
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
