package app.dita.store.postgres;

import app.dita.core.config.DispatchSettings;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventDispatcher;
import app.dita.core.events.Refs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Durable outbox relay (V2). Each consumer (e.g. neo4j, vespa) has its own cursor in outbox_consumer, so one failing
 * projection never blocks another. At-least-once; consumers must be idempotent and re-read truth. Failures back off;
 * after maxAttempts a non-deletion event is dead-lettered (ids only); a deletion event is NEVER skipped: the consumer
 * is marked blocked_on_deletion and keeps retrying (operator alert, N-20).
 * Connect as the dedicated dita_relay role (V3, N-27). Each consumer's pass runs in one transaction holding a row lock
 * on its cursor (FOR UPDATE SKIP LOCKED), so two relay instances can never interleave deliveries (N-28).
 * Rows whose payload group differs from the row's group are refused and dead-lettered as forged (N-27).
 */
public final class PostgresOutboxRelay {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final DispatchSettings settings;
    private final Clock clock;
    private final List<EventDispatcher.Consumer> consumers = new ArrayList<>();

    public PostgresOutboxRelay(DataSource ds, DispatchSettings settings, Clock clock) {
        this.jdbc = new JdbcTemplate(ds); this.settings = settings; this.clock = clock;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
    }

    /** Registers a consumer; a new consumer starts at the beginning of the outbox. */
    public void register(EventDispatcher.Consumer c) {
        jdbc.update("INSERT INTO outbox_consumer (name, last_id) VALUES (?, 0) ON CONFLICT (name) DO NOTHING", c.name());
        consumers.add(c);
    }

    /** Registers a consumer that only sees events published after now (tests and fresh projections). */
    public void registerFromNow(EventDispatcher.Consumer c) {
        jdbc.update("INSERT INTO outbox_consumer (name, last_id) VALUES (?, (SELECT coalesce(max(id), 0) FROM outbox)) "
                + "ON CONFLICT (name) DO NOTHING", c.name());
        consumers.add(c);
    }

    /** One pass over all consumers. Returns the number of envelopes delivered. */
    public int drainOnce() {
        int delivered = 0;
        for (EventDispatcher.Consumer c : consumers) delivered += drain(c);
        return delivered;
    }

    /** Drains until nothing is delivered or maxRounds is reached. */
    public int drainUntilIdle(int maxRounds) {
        int total = 0;
        for (int i = 0; i < maxRounds; i++) {
            int n = drainOnce();
            total += n;
            if (n == 0) break;
        }
        return total;
    }

    private int drain(EventDispatcher.Consumer c) {
        Integer n = tx.execute(status -> drainLocked(c));
        return n == null ? 0 : n;
    }

    private int drainLocked(EventDispatcher.Consumer c) {
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT last_id, attempts, retry_at FROM outbox_consumer WHERE name = ? FOR UPDATE SKIP LOCKED", c.name());
        if (locked.isEmpty()) return 0;                                       // another relay instance holds this consumer
        Map<String, Object> st = locked.get(0);
        Timestamp retryAt = (Timestamp) st.get("retry_at");
        if (retryAt != null && clock.instant().isBefore(retryAt.toInstant())) return 0;
        long last = ((Number) st.get("last_id")).longValue();
        int attempts = ((Number) st.get("attempts")).intValue();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, group_id, topic, payload::text AS payload FROM outbox WHERE id > ? ORDER BY id LIMIT ?", last, settings.batchSize());
        int delivered = 0;
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String topic = (String) r.get("topic");
            try {
                Object payload = decode(topic, (String) r.get("payload"));
                if (!r.get("group_id").equals(groupOf(payload))) {
                    jdbc.update("INSERT INTO outbox_dead_letter (consumer, outbox_id, error_class) VALUES (?, ?, 'GroupMismatch')", c.name(), id);
                    jdbc.update("UPDATE outbox_consumer SET last_id = ?, attempts = 0, retry_at = NULL, updated_at = now() WHERE name = ?", id, c.name());
                    continue;                                                  // forged row: never delivered
                }
                c.accept(new EventDispatcher.Envelope(id, (String) r.get("group_id"), payload));
                jdbc.update("UPDATE outbox_consumer SET last_id = ?, attempts = 0, retry_at = NULL, blocked_on_deletion = false, "
                        + "updated_at = now() WHERE name = ?", id, c.name());
                attempts = 0;
                delivered++;
            } catch (Exception ex) {
                attempts++;
                boolean deletion = PostgresEventPublisher.MESSAGE_DELETED.equals(topic);
                if (attempts >= settings.maxAttempts() && !deletion) {
                    jdbc.update("INSERT INTO outbox_dead_letter (consumer, outbox_id, error_class) VALUES (?, ?, ?)",
                            c.name(), id, ex.getClass().getSimpleName());
                    jdbc.update("UPDATE outbox_consumer SET last_id = ?, attempts = 0, retry_at = NULL, updated_at = now() WHERE name = ?", id, c.name());
                    attempts = 0;
                    continue;
                }
                Instant next = clock.instant().plus(attempts >= settings.maxAttempts() ? settings.maxBackoff() : settings.backoff(attempts));
                jdbc.update("UPDATE outbox_consumer SET attempts = ?, retry_at = ?, blocked_on_deletion = ?, updated_at = now() WHERE name = ?",
                        attempts, Timestamp.from(next), deletion && attempts >= settings.maxAttempts(), c.name());
                return delivered;                                                  // preserve order for this consumer
            }
        }
        return delivered;
    }

    public long lag(String consumer) {
        Long v = jdbc.queryForObject("SELECT coalesce((SELECT max(id) FROM outbox), 0) - last_id FROM outbox_consumer WHERE name = ?", Long.class, consumer);
        return v == null ? 0 : v;
    }

    /**
     * Delivery health of one consumer. {@code attempts > 0} or {@code retryPending} means the event at
     * {@code lastId + 1} failed and is being retried; {@code pendingDeletions} counts message.deleted events not yet
     * delivered to this consumer. A healthy, drained consumer has attempts 0, no retry pending and no pending deletion.
     */
    public record ConsumerHealth(String consumer, long lastId, int attempts, boolean retryPending, boolean blockedOnDeletion,
                                 long pendingDeletions) {
        public boolean stalled() { return attempts > 0 || retryPending || blockedOnDeletion || pendingDeletions > 0; }
    }

    public ConsumerHealth health(String consumer) {
        return jdbc.queryForObject("""
                SELECT c.last_id, c.attempts, c.retry_at IS NOT NULL AS retry_pending, c.blocked_on_deletion,
                       (SELECT count(*) FROM outbox o WHERE o.id > c.last_id AND o.topic = ?) AS pending_deletions
                  FROM outbox_consumer c WHERE c.name = ?""",
                (rs, i) -> new ConsumerHealth(consumer, rs.getLong(1), rs.getInt(2), rs.getBoolean(3), rs.getBoolean(4), rs.getLong(5)),
                PostgresEventPublisher.MESSAGE_DELETED, consumer);
    }

    public boolean blockedOnDeletion(String consumer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT blocked_on_deletion FROM outbox_consumer WHERE name = ?", Boolean.class, consumer));
    }

    static String groupOf(Object payload) {
        return switch (payload) {
            case Refs.MessageRef r -> r.groupId();
            case Refs.LinkRef r -> r.groupId();
            case Refs.KnowledgeRef r -> r.groupId();
            case DeletionEvent e -> e.groupId();
            default -> null;
        };
    }

    static Object decode(String topic, String json) throws Exception {
        JsonNode p = JSON.readTree(json);
        String g = p.path("groupId").asText();
        return switch (topic) {
            case PostgresEventPublisher.MESSAGE_CREATED -> new Refs.MessageRef(g, p.path("messageId").asText());
            case PostgresEventPublisher.LINK_ADDED -> new Refs.LinkRef(g, p.path("linkKey").asText());
            case PostgresEventPublisher.KNOWLEDGE_CHANGED -> new Refs.KnowledgeRef(g, p.path("knowledgeId").asText());
            case PostgresEventPublisher.MESSAGE_DELETED -> new DeletionEvent(g, p.path("messageId").asText(),
                    DeletionMode.valueOf(p.path("mode").asText()), DeletionSource.valueOf(p.path("source").asText()),
                    Instant.parse(p.path("createdAt").asText()), Instant.parse(p.path("deletedAt").asText()),
                    list(p, "removedKnowledgeIds"), list(p, "updatedKnowledgeIds"), list(p, "redactedKnowledgeIds"),
                    list(p, "preservedWithDeletedSourceIds"), list(p, "removedLinkKeys"), list(p, "addedLinkKeys"),
                    list(p, "affectedDitaReplyIds"));
            default -> throw new IllegalArgumentException("unknown outbox topic " + topic);
        };
    }

    private static List<String> list(JsonNode p, String field) {
        List<String> out = new ArrayList<>();
        p.path(field).forEach(n -> out.add(n.asText()));
        return out;
    }
}
