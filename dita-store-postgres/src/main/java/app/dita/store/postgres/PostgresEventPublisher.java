package app.dita.store.postgres;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.model.IsolationViolation;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transactional outbox writer (DEC-004). Must be called inside {@link PostgresMessageStore#atomically} for the
 * same group, using the same DataSource, so the outbox row commits or rolls back with the truth change.
 * Payloads are reduced to ids before serialization: message text and knowledge statements never reach the outbox
 * (Tony/N-1, N-17). Unknown payload types are refused rather than serialized blindly.
 */
public final class PostgresEventPublisher implements EventPublisher {
    public static final String MESSAGE_CREATED = "message.created";
    public static final String LINK_ADDED = "link.added";
    public static final String KNOWLEDGE_CHANGED = "knowledge.changed";
    public static final String MESSAGE_DELETED = "message.deleted";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public PostgresEventPublisher(DataSource dataSource) { this.jdbc = new JdbcTemplate(dataSource); }

    /** Topic plus an ids-only payload. */
    record Outgoing(String topic, Map<String, Object> payload) {}

    @Override public long publish(String groupId, Object payload) {
        Objects.requireNonNull(groupId, "groupId"); Objects.requireNonNull(payload, "payload");
        String bound = PostgresMessageStore.boundGroup();
        if (bound == null) throw new IllegalStateException("publish must run inside MessageStore.atomically (transactional outbox)");
        if (!bound.equals(groupId)) throw new IsolationViolation("group " + groupId + " is outside scope " + bound);
        Outgoing out = toOutgoing(payload);
        Object payloadGroup = out.payload().get("groupId");
        if (!groupId.equals(payloadGroup)) throw new IsolationViolation("payload group " + payloadGroup + " is outside scope " + groupId);
        String json;
        try { json = JSON.writeValueAsString(out.payload()); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("unserializable outbox payload " + out.topic(), e); }
        // F1/N-44 (ADR-001): serialize outbox writers until COMMIT so outbox ids are allocated in commit-visibility order.
        // The relay reads `id > last_id`; without this, a transaction holding a lower id could commit after a higher id
        // was delivered, and its event (possibly message.deleted) would be skipped forever.
        // pg_advisory_xact_lock is held to transaction end (commit or rollback), and is taken BEFORE the identity value
        // is drawn, so the next writer cannot draw an id until this transaction's commit is visible. Re-entrant within
        // a transaction. Deadlock-free for current callers: publish() is the last step of every unit of work
        // (IngestionPipeline, DeletionService), so a lock holder never waits for a row lock afterwards.
        // Cost: outbox-writing transactions are serialized from their first publish() to commit (chat-scale: fine).
        // N-63: bounded wait. A stuck holder makes this writer fail and roll back (truth + outbox together), never hang.
        jdbc.query("SELECT set_config('lock_timeout', ?, true)", rs -> null, LOCK_TIMEOUT);
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, OUTBOX_ORDER_LOCK);
        Long id = jdbc.queryForObject("INSERT INTO outbox (group_id, topic, payload) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, groupId, out.topic(), json);
        return Objects.requireNonNull(id);
    }

    /** Advisory lock key that orders outbox writers ("DITA" + 0x0001). Every outbox INSERT must go through publish(). */
    public static final long OUTBOX_ORDER_LOCK = 0x4449_5441_0000_0001L;
    /** Upper bound on waiting for the outbox lock (transaction-local; applies to later lock waits in the same tx). */
    static final String LOCK_TIMEOUT = "10s";

    /** Ids-only projection of every payload type core publishes. Package-private for tests. */
    static Outgoing toOutgoing(Object payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        // Ids-only refs are what core publishes since N-18; the full-object branches below remain for compatibility.
        if (payload instanceof Refs.MessageRef r) {
            p.put("groupId", r.groupId()); p.put("messageId", r.messageId());
            return new Outgoing(MESSAGE_CREATED, p);
        }
        if (payload instanceof Refs.LinkRef r) {
            p.put("groupId", r.groupId()); p.put("linkKey", r.linkKey());
            return new Outgoing(LINK_ADDED, p);
        }
        if (payload instanceof Refs.KnowledgeRef r) {
            p.put("groupId", r.groupId()); p.put("knowledgeId", r.knowledgeId());
            return new Outgoing(KNOWLEDGE_CHANGED, p);
        }
        if (payload instanceof Message m) {
            p.put("groupId", m.groupId()); p.put("messageId", m.id());
            return new Outgoing(MESSAGE_CREATED, p);
        }
        if (payload instanceof MessageLink l) {
            p.put("groupId", l.groupId()); p.put("linkKey", l.key()); p.put("fromId", l.fromId()); p.put("toId", l.toId());
            return new Outgoing(LINK_ADDED, p);
        }
        if (payload instanceof KnowledgeItem k) {
            p.put("groupId", k.groupId()); p.put("knowledgeId", k.id());
            return new Outgoing(KNOWLEDGE_CHANGED, p);
        }
        if (payload instanceof DeletionEvent e) {
            p.put("groupId", e.groupId()); p.put("messageId", e.messageId());
            p.put("mode", e.mode() == null ? null : e.mode().name());
            p.put("source", e.source() == null ? null : e.source().name());
            p.put("createdAt", iso(e.createdAt())); p.put("deletedAt", iso(e.deletedAt()));
            p.put("removedKnowledgeIds", e.removedKnowledgeIds());
            p.put("updatedKnowledgeIds", e.updatedKnowledgeIds());
            p.put("redactedKnowledgeIds", e.redactedKnowledgeIds());
            p.put("preservedWithDeletedSourceIds", e.preservedWithDeletedSourceIds());
            p.put("removedLinkKeys", e.removedLinkKeys());
            p.put("addedLinkKeys", e.addedLinkKeys());
            p.put("affectedDitaReplyIds", e.affectedDitaReplyIds());
            return new Outgoing(MESSAGE_DELETED, p);
        }
        throw new IllegalArgumentException("unsupported outbox payload type " + payload.getClass().getName());
    }

    private static String iso(Instant i) { return i == null ? null : i.toString(); }
}
