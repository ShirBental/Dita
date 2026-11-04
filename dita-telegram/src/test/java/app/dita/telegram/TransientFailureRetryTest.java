package app.dita.telegram;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.config.ClarificationSettings;
import app.dita.core.config.DeletionSettings;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionService;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shuri B1: a transient storage failure (here a synthetic SQLSTATE 55P03 lock timeout, as the outbox lock_timeout
 * raises) must leave the Telegram update UNACKNOWLEDGED and fully rolled back, and the redelivered update must then
 * be processed exactly once. Covers a normal message and a /forget deletion. Synthetic data only; no network.
 */
class TransientFailureRetryTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000777L, AUTHOR = 4242;
    static final long T0 = Instant.parse("2026-11-01T10:00:00Z").getEpochSecond();

    /** Fails the next {@code failures} publishes of the given payload type with a transient SQLException. */
    static final class FlakyPublisher implements EventPublisher {
        final Class<?> type; final AtomicInteger failures; final List<Object> published = new ArrayList<>();
        FlakyPublisher(Class<?> type, int failures) { this.type = type; this.failures = new AtomicInteger(failures); }
        public long publish(String groupId, Object payload) {
            if (type.isInstance(payload) && failures.getAndDecrement() > 0) {
                throw new IllegalStateException("storage failure", new SQLException("synthetic lock timeout", "55P03"));
            }
            published.add(payload);
            return published.size();
        }
    }

    /** Replays every update at or after the requested offset, like the Bot API does for unacknowledged updates. */
    static final class ReplayingGateway implements TelegramGateway {
        final List<JsonNode> all = new ArrayList<>();
        final List<Long> requestedOffsets = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        public List<JsonNode> getUpdates(long offset, int t, int l) {
            requestedOffsets.add(offset);
            return all.stream().filter(u -> u.path("update_id").asLong() >= offset).toList();
        }
        public long sendMessage(long c, String text, Long r, List<Button> b) { sent.add(text); return 9000 + sent.size(); }
        public void answerCallbackQuery(String id, String text) { }
        public Set<Long> chatAdministrators(long c) { return Set.of(); }
    }

    InMemoryMessageStore store = new InMemoryMessageStore();
    ReplayingGateway gw = new ReplayingGateway();
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(T0 + 60), ZoneOffset.UTC);

    TelegramAdapter adapter(EventPublisher pub) {
        var inf = new RuleLinkInferencer();
        var settings = new TelegramAdapterSettings(Set.of(CHAT), "DitaDemoBot", true, 50, EnumSet.allOf(ApprovalAct.class), Duration.ofDays(21), false, Set.of(AUTHOR), null);
        var query = new QueryService(store, new LexicalRetriever(store),
                new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store), 5, 200);
        return new TelegramAdapter(settings, store, new IngestionPipeline(store, inf, 50, pub),
                new DeletionService(store, inf, DeletionSettings.DEFAULTS, pub, 50), query,
                new ClarificationGate(ClarificationSettings.DEFAULTS), gw, new CallbackSigner("synthetic-retry-secret-0123456789abcd".getBytes()), clock);
    }

    static JsonNode message(long updateId, long messageId, long date, String text, Long replyTo) {
        ObjectNode m = JSON.createObjectNode();
        m.put("message_id", messageId).put("date", date).put("text", text);
        m.putObject("chat").put("id", CHAT).put("type", "supergroup");
        m.putObject("from").put("id", AUTHOR).put("is_bot", false);
        if (replyTo != null) m.putObject("reply_to_message").put("message_id", replyTo);
        ObjectNode u = JSON.createObjectNode();
        u.put("update_id", updateId);
        u.set("message", m);
        return u;
    }

    static final GroupScope G = new GroupScope("tg" + Math.abs(CHAT));
    static String mid(long m) { return "tg" + Math.abs(CHAT) + "m" + m; }

    @Test void transientIngestFailureIsNotAcknowledgedAndSucceedsOnRedelivery() throws Exception {
        FlakyPublisher pub = new FlakyPublisher(app.dita.core.events.Refs.MessageRef.class, 1);
        TelegramAdapter adapter = adapter(pub);
        TelegramPoller poller = new TelegramPoller(gw, adapter, 0, 100);
        gw.all.add(message(10, 1, T0, "Synthetic: the pediatrician on Herzl St. is excellent", null));
        gw.all.add(message(11, 2, T0 + 5, "Synthetic: snacks for Friday", null));

        assertThrows(RetryableUpdateException.class, poller::pollOnce, "the transient failure surfaces to the poller");
        assertEquals(0, poller.offset(), "offset not advanced past the failed update");
        assertTrue(store.find(G, mid(1)).isEmpty(), "the failed transaction rolled back completely");
        assertTrue(store.find(G, mid(2)).isEmpty(), "later updates in the batch wait, preserving order");

        assertEquals(2, poller.pollOnce(), "redelivery: both updates handled");
        assertTrue(store.find(G, mid(1)).isPresent());
        assertTrue(store.find(G, mid(2)).isPresent());
        assertEquals(12, poller.offset());
        assertEquals(2, pub.published.size(), "each message published exactly once");
        assertEquals(List.of(0L, 0L), gw.requestedOffsets);
    }

    @Test void transientFailureDuringForgetNeverLosesTheDeletion() throws Exception {
        FlakyPublisher pub = new FlakyPublisher(DeletionEvent.class, 2);
        TelegramAdapter adapter = adapter(pub);
        TelegramPoller poller = new TelegramPoller(gw, adapter, 0, 100);
        gw.all.add(message(20, 1, T0, "Synthetic: avoid him for teenagers", null));
        assertEquals(1, poller.pollOnce());
        gw.all.add(message(21, 2, T0 + 30, "/forget", 1L));

        assertThrows(RetryableUpdateException.class, poller::pollOnce);
        assertFalse(store.find(G, mid(1)).orElseThrow().deleted(), "the failed deletion rolled back");
        assertThrows(RetryableUpdateException.class, poller::pollOnce, "still failing: still not acknowledged");
        assertEquals(21, poller.offset(), "offset stays at the /forget update");

        assertEquals(1, poller.pollOnce(), "the projection/DB recovered: the /forget is applied");
        assertTrue(store.find(G, mid(1)).orElseThrow().deleted());
        assertNull(store.find(G, mid(1)).orElseThrow().text());
        assertEquals(1, pub.published.stream().filter(p -> p instanceof DeletionEvent).count(), "deletion published exactly once");
        assertTrue(gw.sent.contains("Done. I removed that message from this group's memory."));
    }

    @Test void nonTransientFailureIsStillSkippedSoAPoisonUpdateCannotWedgeTheBot() throws Exception {
        EventPublisher broken = (g, p) -> { throw new IllegalArgumentException("bug, not transient"); };
        TelegramPoller poller = new TelegramPoller(gw, adapter(broken), 0, 100);
        gw.all.add(message(30, 1, T0, "Synthetic message", null));
        assertEquals(1, poller.pollOnce());
        assertEquals(31, poller.offset(), "acknowledged and logged (class name only)");
    }

    @Test void transientClassification() {
        assertTrue(RetryableUpdateException.isTransient(new RuntimeException(new SQLException("x", "40P01"))));
        assertTrue(RetryableUpdateException.isTransient(new RuntimeException(new SQLException("x", "08006"))));
        assertTrue(RetryableUpdateException.isTransient(new RuntimeException(new SQLException("x", "55P03"))));
        assertFalse(RetryableUpdateException.isTransient(new RuntimeException(new SQLException("x", "23505"))));
        assertFalse(RetryableUpdateException.isTransient(new IllegalStateException("no cause")));
    }
}
