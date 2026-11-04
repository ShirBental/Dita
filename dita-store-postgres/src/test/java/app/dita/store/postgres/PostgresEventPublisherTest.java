package app.dita.store.postgres;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Offline: payload reduction to ids (no database). */
class PostgresEventPublisherTest {
    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");

    @Test void payloadsCarryIdsOnly() {
        Message m = new Message("m1", "g1", "alice", T, null, "SYNTHETIC-TEXT", SourceKind.SYNTHETIC, false);
        var out = PostgresEventPublisher.toOutgoing(m);
        assertEquals("message.created", out.topic());
        assertEquals(Map.of("groupId", "g1", "messageId", "m1"), out.payload());

        KnowledgeItem k = KnowledgeItem.of("k1", "g1", KnowledgeItem.Kind.CLAIM, "s", "SYNTHETIC-TEXT", 0,
                MessageLink.Method.RULE, List.of("m1"));
        assertEquals(Map.of("groupId", "g1", "knowledgeId", "k1"), PostgresEventPublisher.toOutgoing(k).payload());

        MessageLink l = new MessageLink("g1", "m2", "m1", MessageLink.Kind.INFERRED_REFERENCE, MessageLink.Status.CANDIDATE,
                MessageLink.Method.RULE, 0.5, Map.of());
        assertEquals("m2->m1:INFERRED_REFERENCE", PostgresEventPublisher.toOutgoing(l).payload().get("linkKey"));

        DeletionEvent e = new DeletionEvent("g1", "m1", DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND, T, T.plusSeconds(600),
                List.of("k1"), List.of(), List.of(), List.of(), List.of("m2->m1:INFERRED_REFERENCE"), List.of(), List.of("r1"));
        var d = PostgresEventPublisher.toOutgoing(e);
        assertEquals("message.deleted", d.topic());
        assertEquals("LATE_TEXT_ONLY", d.payload().get("mode"));
        assertEquals("2026-10-01T09:10:00Z", d.payload().get("deletedAt"));
        assertFalse(String.valueOf(out.payload()).contains("SYNTHETIC-TEXT"));
    }

    @Test void unknownPayloadTypesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PostgresEventPublisher.toOutgoing("free text"));
    }

    @Test void publishOutsideTransactionIsRefused() {
        var pub = new PostgresEventPublisher(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://invalid/none"));
        Message m = new Message("m1", "g1", "alice", T, null, "t", SourceKind.SYNTHETIC, false);
        assertThrows(IllegalStateException.class, () -> pub.publish("g1", m));
    }
}
