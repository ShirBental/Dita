package app.dita.store.postgres;

import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.AnswerRecord;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageEvent;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * PostgreSQL source of truth (schema: db/migration/V1__core.sql + V5__knowledge_v2.sql; V6 optional, DEC-038). Semantics follow
 * {@link app.dita.core.store.InMemoryMessageStore}, which is authoritative.
 * <ul>
 *   <li>Every operation runs inside {@link #atomically}: one transaction that first runs
 *       {@code set_config('app.group_id', ?, true)} (= {@code SET LOCAL}) so RLS applies.</li>
 *   <li>Every statement also filters on {@code group_id} (defence in depth on top of RLS).</li>
 *   <li>Nested calls join the open transaction only for the same group; a different scope throws
 *       {@link IsolationViolation}.</li>
 *   <li>Ids are opaque text, stored as supplied by core; timestamps are timestamptz (microsecond precision).</li>
 *   <li>No message text is ever put in an exception message (N-17).</li>
 *   <li>RFC-002 (V5): assertions, evidence roles/spans, relations and answer provenance. On a database without V5,
 *       legacy knowledge (no assertion, ASSERTS-only evidence) keeps working; anything that needs V5 fails explicitly
 *       ({@link UnsupportedOperationException}), never silently.</li>
 * </ul>
 */
public final class PostgresMessageStore implements MessageStore {
    // Two-int advisory namespace is disjoint from the outbox's one-bigint ordering lock. Hash collisions can only
    // serialize unrelated groups, never mix their data. Every outer store unit takes this BEFORE row/outbox locks.
    private static final int GROUP_STATE_LOCK_NAMESPACE = 0x4449_5447; // DITG
    /** Transaction resource key: the group whose {@code app.group_id} is set on the current transaction. */
    static final Object GROUP_KEY = new Object() {
        @Override public String toString() { return "dita.app.group_id"; }
    };

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Double>> EVIDENCE_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresMessageStore(DataSource dataSource) {
        this(dataSource, new DataSourceTransactionManager(dataSource));
    }

    /** {@code transactionManager} must manage {@code dataSource} so JdbcTemplate joins its transactions. */
    public PostgresMessageStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The group bound to the current transaction by {@link #atomically}, or null. */
    static String boundGroup() {
        return (String) TransactionSynchronizationManager.getResource(GROUP_KEY);
    }

    // ------------------------------------------------------------------ transactions

    @Override public <T> T atomically(GroupScope scope, Supplier<T> work) {
        Objects.requireNonNull(scope, "scope");
        String bound = boundGroup();
        if (bound != null) {
            if (!bound.equals(scope.groupId())) {
                throw new IsolationViolation("group " + scope.groupId() + " is outside scope " + bound);
            }
            return work.get();   // join; failure propagates and the outer transaction rolls back
        }
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.group_id', ?, true)", String.class, scope.groupId());
            jdbc.execute("SET LOCAL lock_timeout = '10s'");
            jdbc.query("SELECT pg_advisory_xact_lock(?, hashtext(?))", rs -> null,
                    GROUP_STATE_LOCK_NAMESPACE, scope.groupId());
            TransactionSynchronizationManager.bindResource(GROUP_KEY, scope.groupId());
            try {
                return work.get();
            } finally {
                TransactionSynchronizationManager.unbindResource(GROUP_KEY);
            }
        });
    }

    private void run(GroupScope scope, Runnable work) {
        atomically(scope, () -> { work.run(); return null; });
    }

    // ------------------------------------------------------------------ messages

    private static final String MESSAGE_COLUMNS =
            "id, group_id, author_id, sent_at, reply_to_message_id, current_text, source_kind::text AS source_kind, deleted_at";

    private static final RowMapper<Message> MESSAGE = (rs, n) -> new Message(
            rs.getString("id"), rs.getString("group_id"), rs.getString("author_id"), instant(rs, "sent_at"),
            rs.getString("reply_to_message_id"), rs.getString("current_text"),
            SourceKind.valueOf(rs.getString("source_kind")), rs.getObject("deleted_at") != null);

    @Override public void append(GroupScope scope, Message m) {
        scope.requireSame(m.groupId());
        run(scope, () -> {
            Optional<Message> existing = findIn(scope, m.id());
            if (existing.isPresent()) {
                requireSameDelivery(existing.get(), m);
                return;
            }
            if (m.replyToId() != null && findIn(scope, m.replyToId()).isEmpty()) {
                throw new IllegalStateException("reply target not in this group: " + m.replyToId());
            }
            int inserted = jdbc.update("""
                    INSERT INTO message (group_id, id, author_id, sent_at, reply_to_message_id, source_kind, current_text)
                    VALUES (?, ?, ?, ?, ?, ?::source_kind, ?)
                    ON CONFLICT (group_id, id) DO NOTHING""",
                    scope.groupId(), m.id(), m.authorId(), ts(m.sentAt()), m.replyToId(), m.sourceKind().name(), m.text());
            if (inserted == 0) {   // concurrent redelivery won the race
                requireSameDelivery(findIn(scope, m.id()).orElseThrow(), m);
                return;
            }
            insertEvent(scope, m.id(), MessageEvent.Kind.CREATED, m.text(), m.sentAt());
        });
    }

    private static void requireSameDelivery(Message existing, Message m) {
        if (existing.authorId().equals(m.authorId()) && existing.sentAt().equals(m.sentAt())) return;
        throw new IllegalStateException("conflicting duplicate message " + m.id());
    }

    private void insertEvent(GroupScope scope, String messageId, MessageEvent.Kind kind, String text, Instant at) {
        jdbc.update("INSERT INTO message_event (group_id, message_id, kind, text, occurred_at) VALUES (?, ?, ?::msg_event, ?, ?)",
                scope.groupId(), messageId, kind.name(), text, ts(at));
    }

    /** Locks the row; returns whether it is deleted. NoSuchElementException if absent from this group. */
    private boolean lockDeleted(GroupScope scope, String id) {
        List<Boolean> r = jdbc.query("SELECT deleted_at IS NOT NULL FROM message WHERE group_id = ? AND id = ? FOR UPDATE",
                (rs, n) -> rs.getBoolean(1), scope.groupId(), id);
        if (r.isEmpty()) throw new NoSuchElementException(id);
        return r.get(0);
    }

    @Override public void edit(GroupScope scope, String id, String newText, Instant at) {
        run(scope, () -> {
            if (lockDeleted(scope, id)) throw new IllegalStateException("cannot edit deleted message " + id);
            Objects.requireNonNull(newText, "newText");
            jdbc.update("UPDATE message SET current_text = ?, edited_at = ? WHERE group_id = ? AND id = ? AND deleted_at IS NULL",
                    newText, ts(at), scope.groupId(), id);
            insertEvent(scope, id, MessageEvent.Kind.EDITED, newText, at);
        });
    }

    @Override public List<String> tombstone(GroupScope scope, String id, Instant at, DeletionMode mode, DeletionSource source) {
        Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(source, "source");
        return atomically(scope, () -> {
            if (lockDeleted(scope, id)) throw new IllegalStateException("already deleted " + id);
            List<String> versions = jdbc.queryForList(
                    "SELECT text FROM message_event WHERE group_id = ? AND message_id = ? AND text IS NOT NULL ORDER BY id",
                    String.class, scope.groupId(), id);
            jdbc.update("""
                    UPDATE message SET current_text = NULL, deleted_at = ?, deletion_mode = ?::deletion_mode,
                           deletion_source = ?::deletion_source, deletion_recorded_at = now()
                    WHERE group_id = ? AND id = ?""",
                    ts(at), mode.name(), source.name(), scope.groupId(), id);
            jdbc.update("UPDATE message_event SET text = NULL WHERE group_id = ? AND message_id = ? AND text IS NOT NULL",
                    scope.groupId(), id);
            insertEvent(scope, id, MessageEvent.Kind.DELETED, null, at);
            return List.copyOf(versions);
        });
    }

    private Optional<Message> findIn(GroupScope scope, String id) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE group_id = ? AND id = ?",
                MESSAGE, scope.groupId(), id).stream().findFirst();
    }

    private Message require(GroupScope scope, String id) {
        return findIn(scope, id).orElseThrow(() -> new NoSuchElementException(id));
    }

    @Override public Optional<Message> find(GroupScope scope, String id) {
        return atomically(scope, () -> findIn(scope, id));
    }

    @Override public List<Message> live(GroupScope scope) {
        return atomically(scope, () -> jdbc.query("SELECT " + MESSAGE_COLUMNS
                + " FROM message WHERE group_id = ? AND deleted_at IS NULL ORDER BY sent_at, id COLLATE \"C\"",
                MESSAGE, scope.groupId()));
    }

    @Override public List<Message> windowBefore(GroupScope scope, String id, int limit) {
        if (limit < 0) throw new IllegalArgumentException("negative limit");
        return atomically(scope, () -> {
            Message target = require(scope, id);
            List<Message> newestFirst = jdbc.query("SELECT " + MESSAGE_COLUMNS + """
                     FROM message WHERE group_id = ? AND deleted_at IS NULL AND sent_at < ?
                    ORDER BY sent_at DESC, id COLLATE "C" DESC LIMIT ?""",
                    MESSAGE, scope.groupId(), ts(target.sentAt()), limit);
            List<Message> out = new ArrayList<>(newestFirst);
            Collections.reverse(out);
            return List.copyOf(out);
        });
    }

    /** TASK-046: one query (edited_at is set by every edit). */
    @Override public java.util.Set<String> editedMessageIds(GroupScope scope) {
        return atomically(scope, () -> new java.util.HashSet<>(jdbc.queryForList(
                "SELECT id FROM message WHERE group_id = ? AND edited_at IS NOT NULL", String.class, scope.groupId())));
    }

    /** TASK-046: shared row locks held until the caller's transaction ends (an edit's FOR UPDATE waits for them). */
    @Override public void lockForRead(GroupScope scope, java.util.Collection<String> ids) {
        run(scope, () -> {
            for (String id : new java.util.TreeSet<>(ids))                      // fixed order: no lock-order deadlocks
                jdbc.query("SELECT 1 FROM message WHERE group_id = ? AND id = ? FOR SHARE", (rs, n) -> 1, scope.groupId(), id);
        });
    }

    @Override public List<MessageEvent> history(GroupScope scope, String id) {
        return atomically(scope, () -> jdbc.query(
                "SELECT group_id, message_id, kind::text, text, occurred_at FROM message_event WHERE group_id = ? AND message_id = ? ORDER BY id",
                (rs, n) -> new MessageEvent(rs.getString(1), rs.getString(2), MessageEvent.Kind.valueOf(rs.getString(3)),
                        rs.getString(4), instant(rs, "occurred_at")),
                scope.groupId(), id));
    }

    // ------------------------------------------------------------------ links

    @Override public void addLink(GroupScope scope, MessageLink link) {
        scope.requireSame(link.groupId());
        run(scope, () -> {
            for (String dep : link.evidenceMessageIds()) {
                if (require(scope, dep).deleted()) throw new IllegalStateException("link evidence uses deleted message " + dep);
            }
            List<Long> ids = jdbc.queryForList("""
                    INSERT INTO message_link (group_id, link_key, from_msg, to_msg, kind, status, method, score, evidence)
                    VALUES (?, ?, ?, ?, ?::link_kind, ?::link_status, ?::link_method, ?, ?::jsonb)
                    ON CONFLICT (group_id, link_key) DO NOTHING RETURNING id""", Long.class,
                    scope.groupId(), link.key(), link.fromId(), link.toId(), link.kind().name(), link.status().name(),
                    link.method().name(), link.score(), json(link.evidence()));
            if (ids.isEmpty()) return;   // idempotent by key: first write wins (putIfAbsent)
            long linkId = ids.get(0);
            List<Object[]> rows = link.evidenceMessageIds().stream()
                    .map(mid -> new Object[]{scope.groupId(), linkId, mid}).toList();
            jdbc.batchUpdate("INSERT INTO message_link_evidence (group_id, link_id, message_id) VALUES (?, ?, ?)", rows);
        });
    }

    @Override public void removeLinks(GroupScope scope, Collection<String> keys) {
        if (keys.isEmpty()) return;
        run(scope, () -> jdbc.batchUpdate("DELETE FROM message_link WHERE group_id = ? AND link_key = ?",
                keys.stream().map(k -> new Object[]{scope.groupId(), k}).toList()));
    }

    @Override public List<MessageLink> links(GroupScope scope) {
        return atomically(scope, () -> jdbc.query("""
                SELECT l.group_id, l.from_msg, l.to_msg, l.kind::text, l.status::text, l.method::text, l.score, l.evidence::text,
                       ARRAY(SELECT e.message_id FROM message_link_evidence e
                             WHERE e.group_id = l.group_id AND e.link_id = l.id) AS deps
                FROM message_link l WHERE l.group_id = ? ORDER BY l.id""",
                (rs, n) -> new MessageLink(rs.getString(1), rs.getString(2), rs.getString(3),
                        MessageLink.Kind.valueOf(rs.getString(4)), MessageLink.Status.valueOf(rs.getString(5)),
                        MessageLink.Method.valueOf(rs.getString(6)), rs.getDouble(7), evidence(rs.getString(8)),
                        textArray(rs.getArray("deps"))),
                scope.groupId()));
    }

    private boolean linkExists(GroupScope scope, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM message_link WHERE group_id = ? AND link_key = ?)", Boolean.class, scope.groupId(), key));
    }

    // ------------------------------------------------------------------ knowledge

    /** Cached once observed: V5 is additive and never rolled back by the app. Re-checked while absent. */
    private volatile boolean v5Seen;

    /** True if db/migration/V5__knowledge_v2.sql has been applied (read-only catalog probe). */
    boolean hasV5() {
        if (v5Seen) return true;
        boolean present = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('knowledge_relation') IS NOT NULL", Boolean.class));
        if (present) v5Seen = true;
        return present;
    }

    /**
     * DEC-038: true if db/migration/V6__validity_unresolved.sql has been applied, i.e. knowledge_item.validity accepts
     * 'UNRESOLVED' (read-only catalog probe on the constraint V6 adds). NOT cached (N-120 / T47-4): if V6 is ever rolled
     * back, the next reconciliation falls back to SUPERSEDED instead of failing every deletion until a restart.
     */
    boolean hasV6() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = to_regclass('knowledge_item')"
                        + " AND conname = 'knowledge_item_validity_v6')", Boolean.class));
    }

    @Override public boolean supportsUnresolvedValidity() { return hasV6(); }

    private void requireV5(String what) {
        if (!hasV5()) throw new UnsupportedOperationException("PostgresMessageStore: " + what
                + " requires migration V5__knowledge_v2.sql, which is not applied");
    }

    /** Pre-V5 the schema can hold only legacy items: no assertion and ASSERTS-only evidence (spans exist since V1). Pre-V6: no UNRESOLVED. */
    private void requireStorable(KnowledgeItem k) {
        if (k.assertion() != null || k.evidence().stream().anyMatch(e -> e.role() != KnowledgeItem.Role.ASSERTS)) {
            requireV5("knowledge with an assertion or a non-ASSERTS evidence role");
        }
        // DEC-038: refused before any write, explicitly; never silently stored as another validity
        if (k.assertion() != null && k.assertion().validity() == Assertion.Validity.UNRESOLVED && !hasV6())
            throw new UnsupportedOperationException("PostgresMessageStore: validity UNRESOLVED requires migration V6__validity_unresolved.sql, which is not applied");
    }

    @Override public void addKnowledge(GroupScope scope, KnowledgeItem k) {
        scope.requireSame(k.groupId());
        run(scope, () -> {
            for (KnowledgeItem.Evidence e : k.evidence()) {
                if (require(scope, e.messageId()).deleted()) {
                    throw new IllegalStateException("cannot derive knowledge from deleted message " + e.messageId());
                }
                if (e.viaLinkKey() != null && !linkExists(scope, e.viaLinkKey())) {
                    throw new IllegalStateException("unknown link " + e.viaLinkKey());
                }
            }
            requireStorable(k);
            int inserted = jdbc.update("""
                    INSERT INTO knowledge_item (group_id, id, kind, subject, statement, statement_redacted, polarity, method)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::link_method)
                    ON CONFLICT (group_id, id) DO NOTHING""",
                    scope.groupId(), k.id(), k.kind().name(), k.subject(), k.statement(), k.statementRedacted(),
                    k.polarity(), k.method() == null ? null : k.method().name());
            if (inserted == 0) throw new IllegalStateException("duplicate knowledge " + k.id());
            writeAssertion(scope, k);
            insertSources(scope, k);
        });
    }

    @Override public void replaceKnowledge(GroupScope scope, KnowledgeItem k) {
        scope.requireSame(k.groupId());
        run(scope, () -> {
            requireStorable(k);
            int updated = jdbc.update("""
                    UPDATE knowledge_item SET kind = ?, subject = ?, statement = ?, statement_redacted = ?, polarity = ?,
                           method = ?::link_method, updated_at = now()
                    WHERE group_id = ? AND id = ?""",
                    k.kind().name(), k.subject(), k.statement(), k.statementRedacted(), k.polarity(),
                    k.method() == null ? null : k.method().name(), scope.groupId(), k.id());
            if (updated == 0) throw new NoSuchElementException(k.id());
            writeAssertion(scope, k);
            jdbc.update("DELETE FROM knowledge_source WHERE group_id = ? AND knowledge_id = ?", scope.groupId(), k.id());
            insertSources(scope, k);
        });
    }

    /** Writes (or clears) the V5 assertion columns. No-op before V5 (only legacy items reach here then). */
    private void writeAssertion(GroupScope scope, KnowledgeItem k) {
        if (!hasV5()) return;
        Assertion a = k.assertion();
        if (a == null) {
            jdbc.update("""
                    UPDATE knowledge_item SET assertion_status = NULL, certainty = NULL, speaker_id = NULL, attributed_to = NULL,
                           subject_id = NULL, subject_surface = NULL, predicate = NULL, qualifiers = NULL, entities = NULL,
                           stated_at = NULL, validity = NULL, proposal_version = NULL, batch_id = NULL
                    WHERE group_id = ? AND id = ?""", scope.groupId(), k.id());
            return;
        }
        jdbc.update("""
                UPDATE knowledge_item SET assertion_status = ?, certainty = ?, speaker_id = ?, attributed_to = ?,
                       subject_id = ?, subject_surface = ?, predicate = ?, qualifiers = ?::jsonb, entities = ?::jsonb,
                       stated_at = ?, validity = ?, proposal_version = ?, batch_id = ?
                WHERE group_id = ? AND id = ?""",
                a.status().name(), a.certainty().name(), a.speakerId(), a.attributedTo(), a.subjectId(), a.subject(),
                a.predicate(), qualifiersJson(a.qualifiers()), entitiesJson(a.entities()), ts(a.statedAt()),
                a.validity().name(), a.proposalVersion(), a.batchId(), scope.groupId(), k.id());
    }

    private void insertSources(GroupScope scope, KnowledgeItem k) {
        boolean v5 = hasV5();
        List<Object[]> rows = new ArrayList<>();
        int ordinal = 0;
        for (KnowledgeItem.Evidence e : k.evidence()) {
            Integer start = e.hasSpan() ? e.spanStart() : null, end = e.hasSpan() ? e.spanEnd() : null;
            rows.add(v5 ? new Object[]{scope.groupId(), k.id(), ordinal++, e.messageId(), e.basis().name(), e.viaLinkKey(),
                            e.sourceDeleted(), start, end, e.role().name()}
                        : new Object[]{scope.groupId(), k.id(), ordinal++, e.messageId(), e.basis().name(), e.viaLinkKey(),
                            e.sourceDeleted(), start, end});
        }
        jdbc.batchUpdate(v5 ? """
                INSERT INTO knowledge_source (group_id, knowledge_id, ordinal, message_id, basis, via_link_key, source_deleted,
                                              span_start, span_end, role)
                VALUES (?, ?, ?, ?, ?::evidence_basis, ?, ?, ?, ?, ?)"""
                : """
                INSERT INTO knowledge_source (group_id, knowledge_id, ordinal, message_id, basis, via_link_key, source_deleted,
                                              span_start, span_end)
                VALUES (?, ?, ?, ?, ?::evidence_basis, ?, ?, ?, ?)""", rows);
    }

    /** V5 FKs cascade to knowledge_relation (and answer provenance rows); knowledge_source cascades since V1. */
    @Override public void removeKnowledge(GroupScope scope, String id) {
        run(scope, () -> jdbc.update("DELETE FROM knowledge_item WHERE group_id = ? AND id = ?", scope.groupId(), id));
    }

    @Override public List<KnowledgeItem> knowledge(GroupScope scope) {
        return atomically(scope, () -> loadKnowledge(scope, null));
    }

    @Override public Optional<KnowledgeItem> knowledgeById(GroupScope scope, String id) {
        return atomically(scope, () -> loadKnowledge(scope, Objects.requireNonNull(id)).stream().findFirst());
    }

    private record Row(String id, String groupId, KnowledgeItem.Kind kind, String subject, String statement, int polarity,
                       MessageLink.Method method, boolean redacted, Assertion assertion) {}

    private static final String ITEM_COLUMNS = "id, group_id, kind, subject, statement, polarity, method::text, statement_redacted";
    private static final String ASSERTION_COLUMNS = ", assertion_status, certainty, speaker_id, attributed_to, subject_id,"
            + " subject_surface, predicate, qualifiers::text AS qualifiers, entities::text AS entities, stated_at, validity,"
            + " proposal_version, batch_id";

    private List<KnowledgeItem> loadKnowledge(GroupScope scope, String onlyId) {
        boolean v5 = hasV5();
        String filter = onlyId == null ? "" : " AND id = ?";
        Object[] args = onlyId == null ? new Object[]{scope.groupId()} : new Object[]{scope.groupId(), onlyId};
        List<Row> rows = jdbc.query("SELECT " + ITEM_COLUMNS + (v5 ? ASSERTION_COLUMNS : "")
                        + " FROM knowledge_item WHERE group_id = ?" + filter + " ORDER BY seq",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), KnowledgeItem.Kind.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getInt(6),
                        rs.getString(7) == null ? null : MessageLink.Method.valueOf(rs.getString(7)), rs.getBoolean(8),
                        v5 ? assertion(rs) : null),
                args);
        if (rows.isEmpty()) return List.of();
        Map<String, List<KnowledgeItem.Evidence>> ev = new LinkedHashMap<>();
        jdbc.query("SELECT knowledge_id, message_id, basis::text, via_link_key, source_deleted, span_start, span_end"
                        + (v5 ? ", role" : ", 'ASSERTS' AS role") + " FROM knowledge_source"
                        + " WHERE group_id = ?" + (onlyId == null ? "" : " AND knowledge_id = ?") + " ORDER BY knowledge_id, ordinal",
                rs -> {
                    Integer start = (Integer) rs.getObject(6), end = (Integer) rs.getObject(7);
                    ev.computeIfAbsent(rs.getString(1), x -> new ArrayList<>()).add(new KnowledgeItem.Evidence(
                            rs.getString(2), KnowledgeItem.Basis.valueOf(rs.getString(3)), rs.getString(4), rs.getBoolean(5),
                            KnowledgeItem.Role.valueOf(rs.getString(8)), start == null ? -1 : start, end == null ? -1 : end));
                },
                args);
        return rows.stream().map(r -> new KnowledgeItem(r.id(), r.groupId(), r.kind(), r.subject(), r.statement(), r.polarity(),
                r.method(), ev.getOrDefault(r.id(), List.of()), r.redacted(), r.assertion())).toList();
    }

    private static Assertion assertion(ResultSet rs) throws SQLException {
        String status = rs.getString("assertion_status");
        if (status == null) return null;                                   // legacy item
        return new Assertion(Assertion.Status.valueOf(status), Assertion.Certainty.valueOf(rs.getString("certainty")),
                rs.getString("speaker_id"), rs.getString("attributed_to"), rs.getString("subject_id"),
                rs.getString("subject_surface"), rs.getString("predicate"), qualifiers(rs.getString("qualifiers")),
                entities(rs.getString("entities")), instant(rs, "stated_at"),
                Assertion.Validity.valueOf(rs.getString("validity")), rs.getString("proposal_version"), rs.getString("batch_id"));
    }

    // ------------------------------------------------------------------ relations (RFC-002 §3.5, V5)

    @Override public void addRelation(GroupScope scope, KnowledgeRelation r) {
        scope.requireSame(r.groupId());
        run(scope, () -> {
            requireV5("relations");
            Long ends = jdbc.queryForObject("SELECT count(*) FROM knowledge_item WHERE group_id = ? AND id IN (?, ?)",
                    Long.class, scope.groupId(), r.fromId(), r.toId());
            if (ends == null || ends != 2) throw new IllegalStateException("relation ends must exist in this group");
            if (r.evidenceMessageId() != null) require(scope, r.evidenceMessageId());
            jdbc.update("""
                    INSERT INTO knowledge_relation (group_id, from_id, to_id, kind, evidence_message_id) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (group_id, from_id, kind, to_id) DO NOTHING""",       // idempotent by key: first write wins
                    scope.groupId(), r.fromId(), r.toId(), r.kind().name(), r.evidenceMessageId());
        });
    }

    @Override public void removeRelations(GroupScope scope, Collection<String> keys) {
        run(scope, () -> {
            requireV5("relations");
            if (keys.isEmpty()) return;
            // KnowledgeRelation.key() = from|KIND|to, compared as a whole so ids containing '|' cannot be mis-split.
            jdbc.batchUpdate("DELETE FROM knowledge_relation WHERE group_id = ? AND from_id || '|' || kind || '|' || to_id = ?",
                    keys.stream().map(k -> new Object[]{scope.groupId(), k}).toList());
        });
    }

    @Override public List<KnowledgeRelation> relations(GroupScope scope) {
        return atomically(scope, () -> {
            requireV5("relations");
            return jdbc.query("SELECT group_id, from_id, to_id, kind, evidence_message_id FROM knowledge_relation"
                            + " WHERE group_id = ? ORDER BY id",
                    (rs, n) -> new KnowledgeRelation(rs.getString(1), rs.getString(2), rs.getString(3),
                            KnowledgeRelation.Kind.valueOf(rs.getString(4)), rs.getString(5)),
                    scope.groupId());
        });
    }

    // ------------------------------------------------------------------ answer provenance (V5)

    @Override public void recordAnswer(GroupScope scope, AnswerRecord a) {
        scope.requireSame(a.groupId());
        run(scope, () -> {
            requireV5("answer provenance");
            for (String k : a.knowledgeIds()) {
                if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM knowledge_item WHERE group_id = ? AND id = ?)",
                        Boolean.class, scope.groupId(), k))) throw new IllegalStateException("unknown knowledge " + k);
            }
            for (String mid : a.messageIds()) require(scope, mid);
            int inserted = jdbc.update("INSERT INTO dita_answer (group_id, reply_id, published_at) VALUES (?, ?, ?)"
                    + " ON CONFLICT (group_id, reply_id) DO NOTHING", scope.groupId(), a.replyId(), ts(a.publishedAt()));
            if (inserted == 0) return;                                          // idempotent: first record wins
            List<Object[]> ks = new ArrayList<>(), ms = new ArrayList<>();
            for (int i = 0; i < a.knowledgeIds().size(); i++) ks.add(new Object[]{scope.groupId(), a.replyId(), i, a.knowledgeIds().get(i)});
            for (int i = 0; i < a.messageIds().size(); i++) ms.add(new Object[]{scope.groupId(), a.replyId(), i, a.messageIds().get(i)});
            jdbc.batchUpdate("INSERT INTO dita_answer_knowledge (group_id, reply_id, ordinal, knowledge_id) VALUES (?, ?, ?, ?)", ks);
            jdbc.batchUpdate("INSERT INTO dita_answer_message (group_id, reply_id, ordinal, message_id) VALUES (?, ?, ?, ?)", ms);
        });
    }

    @Override public Optional<AnswerRecord> answer(GroupScope scope, String replyId) {
        Objects.requireNonNull(replyId, "replyId");
        return atomically(scope, () -> {
            requireV5("answer provenance");
            List<Instant> published = jdbc.query("SELECT published_at FROM dita_answer WHERE group_id = ? AND reply_id = ?",
                    (rs, n) -> instant(rs, "published_at"), scope.groupId(), replyId);
            if (published.isEmpty()) return Optional.empty();
            List<String> ks = jdbc.queryForList("SELECT knowledge_id FROM dita_answer_knowledge WHERE group_id = ? AND reply_id = ?"
                    + " ORDER BY ordinal", String.class, scope.groupId(), replyId);
            List<String> ms = jdbc.queryForList("SELECT message_id FROM dita_answer_message WHERE group_id = ? AND reply_id = ?"
                    + " ORDER BY ordinal", String.class, scope.groupId(), replyId);
            return Optional.of(new AnswerRecord(scope.groupId(), replyId, ks, ms, published.get(0)));
        });
    }

    // ------------------------------------------------------------------ Dita replies

    @Override public void recordDitaReply(GroupScope scope, String replyId, Collection<String> cited) {
        Objects.requireNonNull(replyId, "replyId");
        run(scope, () -> {
            for (String c : cited) require(scope, c);
            Set<String> citedSet = Set.copyOf(cited);
            jdbc.update("INSERT INTO dita_reply (group_id, id) VALUES (?, ?) ON CONFLICT (group_id, id) DO NOTHING",
                    scope.groupId(), replyId);
            jdbc.update("DELETE FROM dita_reply_citation WHERE group_id = ? AND reply_id = ?", scope.groupId(), replyId);
            jdbc.batchUpdate("INSERT INTO dita_reply_citation (group_id, reply_id, message_id) VALUES (?, ?, ?)",
                    citedSet.stream().map(c -> new Object[]{scope.groupId(), replyId, c}).toList());
        });
    }

    @Override public List<String> ditaRepliesCiting(GroupScope scope, String messageId) {
        return atomically(scope, () -> jdbc.queryForList(
                "SELECT reply_id FROM dita_reply_citation WHERE group_id = ? AND message_id = ? ORDER BY reply_id COLLATE \"C\"",
                String.class, scope.groupId(), messageId));
    }

    // ------------------------------------------------------------------ mapping helpers

    static OffsetDateTime ts(Instant i) { return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC); }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static String json(Map<String, Double> m) {
        try { return JSON.writeValueAsString(m); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("unserializable link evidence", e); }
    }

    private static Map<String, Double> evidence(String s) {
        try { return JSON.readValue(s, EVIDENCE_TYPE); }
        catch (JsonProcessingException e) { throw new IllegalStateException("corrupt link evidence", e); }
    }

    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {};

    static String qualifiersJson(List<Assertion.Qualifier> qs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Assertion.Qualifier q : qs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", q.kind().name()); m.put("messageId", q.messageId()); m.put("spanStart", q.spanStart()); m.put("spanEnd", q.spanEnd());
            out.add(m);
        }
        return writeJson(out);
    }

    static String entitiesJson(List<Assertion.Entity> es) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Assertion.Entity e : es) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id()); m.put("surface", e.surface());
            out.add(m);
        }
        return writeJson(out);
    }

    static List<Assertion.Qualifier> qualifiers(String s) {
        if (s == null) return List.of();
        return readJsonList(s).stream().map(m -> new Assertion.Qualifier(Assertion.Qualifier.Kind.valueOf((String) m.get("kind")),
                (String) m.get("messageId"), ((Number) m.get("spanStart")).intValue(), ((Number) m.get("spanEnd")).intValue())).toList();
    }

    static List<Assertion.Entity> entities(String s) {
        if (s == null) return List.of();
        return readJsonList(s).stream().map(m -> new Assertion.Entity((String) m.get("id"), (String) m.get("surface"))).toList();
    }

    private static String writeJson(Object o) {
        try { return JSON.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("unserializable assertion field", e); }
    }

    private static List<Map<String, Object>> readJsonList(String s) {
        try { return JSON.readValue(s, JSON_LIST); }
        catch (JsonProcessingException e) { throw new IllegalStateException("corrupt assertion json", e); }
    }

    private static Set<String> textArray(Array a) throws SQLException {
        if (a == null) return Set.of();
        try { return new LinkedHashSet<>(Arrays.asList((String[]) a.getArray())); }
        finally { a.free(); }
    }
}
