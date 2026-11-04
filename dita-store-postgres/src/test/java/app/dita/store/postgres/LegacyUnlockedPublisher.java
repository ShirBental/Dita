package app.dita.store.postgres;

import app.dita.core.events.EventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * TEST-ONLY copy of PostgresEventPublisher.publish() as it was BEFORE the ADR-001 F1 fix: the same checks and the same
 * INSERT, but no pg_advisory_xact_lock. Exists solely so OutboxOrderingTest can demonstrate the out-of-order-commit
 * defect against the unchanged relay (run with -Ddita.demonstrateDefect=true). Never used by production code.
 */
final class LegacyUnlockedPublisher implements EventPublisher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    LegacyUnlockedPublisher(DataSource ds) { this.jdbc = new JdbcTemplate(ds); }

    @Override public long publish(String groupId, Object payload) {
        String bound = PostgresMessageStore.boundGroup();
        if (bound == null || !bound.equals(groupId)) throw new IllegalStateException("publish must run inside atomically for " + groupId);
        PostgresEventPublisher.Outgoing out = PostgresEventPublisher.toOutgoing(payload);
        try {
            Long id = jdbc.queryForObject("INSERT INTO outbox (group_id, topic, payload) VALUES (?, ?, ?::jsonb) RETURNING id",
                    Long.class, groupId, out.topic(), JSON.writeValueAsString(out.payload()));
            return Objects.requireNonNull(id);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
}
