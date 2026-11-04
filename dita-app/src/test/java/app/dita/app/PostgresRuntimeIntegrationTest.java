package app.dita.app;

import app.dita.app.config.RelayWorker;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;
import app.dita.projections.Neo4jProjection;
import app.dita.projections.VespaProjection;
import app.dita.store.postgres.PostgresOutboxRelay;
import app.dita.telegram.TelegramGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RUNTIME integration test (ADR-001 / TASK-036): boots the real DitaApplication with dita.storage.backend=POSTGRES
 * against the running Dita PostgreSQL, Neo4j and Vespa, and drives it ONLY through synthetic Telegram updates served
 * by a fake gateway. The app's own Telegram poller thread ingests, the app's background RelayWorker propagates via the
 * durable outbox, /ask is answered from Vespa + Neo4j retrieval, and /forget deletes through to both projections.
 * The test never calls drain(): it only observes. No network beyond loopback; no Telegram API; Muse stays disabled.
 *
 * Gated: SKIPPED unless DITA_TEST_DB_URL, DITA_TEST_DB_PASSWORD, DITA_TEST_RELAY_PASSWORD, DITA_TEST_NEO4J_URL,
 * DITA_TEST_NEO4J_PASSWORD, DITA_TEST_VESPA_URL, DITA_TEST_FAKE_TELEGRAM_TOKEN and DITA_TEST_FAKE_CALLBACK_SECRET are set
 * (scripts/relay-verify.sh sets them; the Telegram values are synthetic placeholders, never a real token).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresRuntimeIntegrationTest {
    static final List<String> REQUIRED = List.of("DITA_TEST_DB_URL", "DITA_TEST_DB_PASSWORD", "DITA_TEST_RELAY_PASSWORD",
            "DITA_TEST_NEO4J_URL", "DITA_TEST_NEO4J_PASSWORD", "DITA_TEST_VESPA_URL", "DITA_TEST_FAKE_TELEGRAM_TOKEN",
            "DITA_TEST_FAKE_CALLBACK_SECRET");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration WAIT = Duration.ofSeconds(30);

    /** In-process stand-in for the Bot API: serves queued synthetic updates, records replies. */
    static final class FakeGateway implements TelegramGateway {
        record Sent(long chatId, String text, Long replyTo) {}
        final ConcurrentLinkedQueue<JsonNode> pending = new ConcurrentLinkedQueue<>();
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong nextId = new AtomicLong(900_000);

        @Override public List<JsonNode> getUpdates(long offset, int timeoutSeconds, int limit) throws Exception {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode u; out.size() < limit && (u = pending.poll()) != null; ) out.add(u);
            if (out.isEmpty()) Thread.sleep(50);
            return out;
        }
        @Override public long sendMessage(long chatId, String text, Long replyTo, List<Button> buttons) {
            sent.add(new Sent(chatId, text, replyTo));
            return nextId.incrementAndGet();
        }
        @Override public void answerCallbackQuery(String id, String text) { }
        @Override public Set<Long> chatAdministrators(long chatId) { return Set.of(); }

        Optional<Sent> replyTo(long messageId) {
            synchronized (sent) { return sent.stream().filter(s -> s.replyTo() != null && s.replyTo() == messageId).findFirst(); }
        }
    }

    static final FakeGateway GATEWAY = new FakeGateway();

    /** Passed only to this test's SpringApplicationBuilder (no @Configuration, so component scanning never picks it up). */
    static class FakeTelegram {
        @Bean @Primary TelegramGateway fakeTelegramGateway() { return GATEWAY; }
    }

    ConfigurableApplicationContext ctx;
    MessageStore store;
    VespaProjection vespa;
    Neo4jProjection neo4j;
    PostgresOutboxRelay relay;
    RelayWorker worker;
    String prefix;
    long chat, otherChat;
    GroupScope g;
    long updateId = ThreadLocalRandom.current().nextLong(1_000_000, 2_000_000);
    /**
     * Telegram dates (epoch seconds) of the synthetic messages are taken AFTER the application started: Dita ignores
     * every update dated before its start (TASK-040 backlog cutoff), exactly as for a live chat. at(x) keeps the
     * original relative order (x = seconds before the last message) within a few seconds of the start.
     */
    long started;
    long at(int secondsBeforeLast) { return started + (120 - secondsBeforeLast) / 10; }

    @BeforeAll void boot() {
        for (String v : REQUIRED) assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: runtime test skipped");
        prefix = "rt-" + UUID.randomUUID().toString().substring(0, 8);
        chat = -(1_000_000_000_000L + ThreadLocalRandom.current().nextLong(1, 999_999_999));   // synthetic supergroup id
        otherChat = chat - 1;                                                                   // NOT allow-listed
        g = new GroupScope("tg" + Math.abs(chat));
        // Command-line arguments: builder.properties() are DEFAULT properties and application.yml would override them
        // (dita.storage.backend=IN_MEMORY), so this test would silently not run on PostgreSQL.
        Map<String, String> props = Map.ofEntries(
                        Map.entry("dita.storage.backend", "POSTGRES"),
                        Map.entry("dita.storage.relay-consumer-prefix", prefix),
                        Map.entry("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL")),
                        Map.entry("dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD"),
                        Map.entry("dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD"),
                        Map.entry("dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL")),
                        Map.entry("dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD"),
                        Map.entry("dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")),
                        Map.entry("dita.processing.dispatch.poll-interval", "100ms"),
                        Map.entry("dita.telegram.enabled", "true"),
                        Map.entry("dita.telegram.bot-username", "dita_synthetic_bot"),
                        Map.entry("dita.telegram.poll-timeout", "1s"),
                        Map.entry("dita.telegram.allowed-chat-ids", Long.toString(chat)),
                        Map.entry("dita.telegram.allowed-author-ids", "501,502,503,504"),     // TASK-040 author allow-list (505 is in a non-allowed chat)
                        Map.entry("dita.telegram.token-env-var", "DITA_TEST_FAKE_TELEGRAM_TOKEN"),
                        Map.entry("dita.telegram.callback-secret-env-var", "DITA_TEST_FAKE_CALLBACK_SECRET"));
        ctx = new SpringApplicationBuilder(DitaApplication.class, FakeTelegram.class)
                .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        store = ctx.getBean(MessageStore.class);
        vespa = ctx.getBean(VespaProjection.class);
        neo4j = ctx.getBean(Neo4jProjection.class);
        relay = ctx.getBean(PostgresOutboxRelay.class);
        worker = ctx.getBean(RelayWorker.class);
        started = Instant.now().getEpochSecond() + 1;                   // strictly after the adapter's ignoreBefore second
    }

    @AfterAll void shutdown() { if (ctx != null) ctx.close(); }

    // ------------------------------------------------------------------ synthetic updates

    long send(long chatId, long messageId, long fromUser, long date, String text, Long replyTo) {
        ObjectNode m = JSON.createObjectNode();
        m.put("message_id", messageId);
        m.put("date", date);
        m.putObject("chat").put("id", chatId).put("type", "supergroup");
        m.putObject("from").put("id", fromUser).put("is_bot", false).put("first_name", "Synthetic");
        m.put("text", text);
        if (replyTo != null) m.putObject("reply_to_message").put("message_id", replyTo);
        ObjectNode u = JSON.createObjectNode();
        u.put("update_id", ++updateId);
        u.set("message", m);
        GATEWAY.pending.add(u);
        return messageId;
    }

    String mid(long messageId) { return "tg" + Math.abs(chat) + "m" + messageId; }

    static void await(String what, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try { if (cond.getAsBoolean()) return; } catch (RuntimeException ignored) { /* service still catching up */ }
            Thread.sleep(100);
        }
        fail("timed out after " + WAIT.toSeconds() + "s waiting for: " + what);
    }

    int neo4jCount(String cypher, Map<String, Object> params) { return neo4j.query(cypher, params).get(0).get(0).asInt(); }

    // ------------------------------------------------------------------ the runtime slice

    @Test @Order(1) void bootsWithPostgresBackendAndBackgroundRelay() {
        assertTrue(worker.isRunning(), "RelayWorker thread is running");
        assertEquals("PostgresMessageStore", store.getClass().getSimpleName(), "the running app uses PostgreSQL, not the in-memory store");
        assertFalse(ctx.containsBean("eventDispatcher"), "the in-memory dispatcher is not wired in POSTGRES mode");
    }

    @Test @Order(2) void syntheticTelegramMessagesAreIngestedAndProjectedByTheBackgroundRelay() throws Exception {
        send(chat, 101, 501, at(120), "Does anyone know a good pediatrician near the park?", null);
        send(chat, 102, 502, at(110), "Dr. Levi on Herzl St. is excellent, we've been going for years", 101L);
        send(chat, 103, 503, at(100), "Dr. Levi is fine for babies but avoid him for teenagers, he was dismissive with my son", null);
        send(chat, 104, 504, at(90), "Who is bringing snacks for Friday's trip?", null);
        send(otherChat, 105, 505, at(80), "Synthetic message in a chat that is NOT allow-listed", null);

        await("4 messages in PostgreSQL", () -> List.of(101, 102, 103, 104).stream().allMatch(i -> store.find(g, mid(i)).isPresent()));
        assertEquals(mid(101), store.find(g, mid(102)).orElseThrow().replyToId(), "explicit reply preserved");
        await("4 documents in Vespa", () -> List.of(101, 102, 103, 104).stream().allMatch(i -> vespa.status(g.groupId(), mid(i)) == 200));
        await("4 message nodes in Neo4j", () -> neo4jCount("MATCH (m:Message {groupId:$g}) RETURN count(m)", Map.of("g", g.groupId())) == 4);
        assertEquals(0, neo4jCount("MATCH (m:Message {groupId:$g}) WHERE m.text IS NOT NULL RETURN count(m)", Map.of("g", g.groupId())),
                "Neo4j never stores message text");
        assertTrue(store.find(new GroupScope("tg" + Math.abs(otherChat)), mid(105)).isEmpty(), "non-allow-listed chat was ignored");
        assertTrue(worker.stats().delivered() >= 4, "delivered by the background worker: " + worker.stats());
    }

    @Test @Order(3) void askIsAnsweredFromVespaAndNeo4jWithVerifiedCitations() throws Exception {
        long ask = send(chat, 110, 501, at(60), "/ask Is Dr. Levi recommended?", null);
        await("a reply to /ask", () -> GATEWAY.replyTo(ask).isPresent());
        String answer = GATEWAY.replyTo(ask).orElseThrow().text();
        assertNotEquals(app.dita.core.answer.GroundedAnswer.NOT_FOUND, answer, "the question is answerable from memory");
        assertTrue(answer.contains("excellent") || answer.contains("teenagers"), "answer quotes the group's messages: " + answer.length() + " chars");
        assertTrue(store.find(g, mid(110)).isEmpty(), "commands are not stored as memory");
    }

    @Test @Order(4) void forgetDeletesThroughPostgresNeo4jAndVespa() throws Exception {
        long forget = send(chat, 120, 503, at(30), "/forget", 103L);     // author 503 forgets their own message m103
        await("a reply to /forget", () -> GATEWAY.replyTo(forget).isPresent());
        assertEquals("Done. I removed that message from this group's memory.", GATEWAY.replyTo(forget).orElseThrow().text());

        Message m = store.find(g, mid(103)).orElseThrow();
        assertTrue(m.deleted());
        assertNull(m.text(), "text purged in PostgreSQL");
        assertTrue(store.history(g, mid(103)).stream().allMatch(h -> h.text() == null), "history text purged");
        await("Vespa document removed", () -> vespa.status(g.groupId(), mid(103)) == 404);
        await("Neo4j node tombstoned", () -> neo4j.query("MATCH (m:Message {uid:$u}) RETURN m.deleted", Map.of("u", g.groupId() + "|" + mid(103)))
                .get(0).get(0).asBoolean());
        DitaPropertiesView p = new DitaPropertiesView(prefix);
        // The Vespa document disappears at the START of the consumer's deletion handler; its cursor only advances when
        // the handler and the relay transaction finish. Wait for both consumers to finish, then require them clean.
        await("Vespa consumer caught up", () -> relay.lag(p.vespa()) == 0);
        await("Neo4j consumer caught up", () -> relay.lag(p.neo4j()) == 0);
        for (String c : List.of(p.neo4j(), p.vespa())) {
            PostgresOutboxRelay.ConsumerHealth h = relay.health(c);
            assertFalse(h.stalled(), "consumer " + c + " after the deletion: " + h);   // no retry, no pending deletion, not blocked
        }
    }

    @Test @Order(5) void forgottenTextNeverComesBackInAnswers() throws Exception {
        long ask = send(chat, 130, 501, at(10), "/ask teenagers dismissive", null);
        await("a reply to the second /ask", () -> GATEWAY.replyTo(ask).isPresent());
        String answer = GATEWAY.replyTo(ask).orElseThrow().text();
        assertFalse(answer.contains("dismissive") || answer.contains("teenagers"), "deleted text is never quoted again");
        assertEquals(0, worker.stats().failures(), "no relay pass failed during the run: " + worker.stats());
    }

    /** Consumer names derived exactly as PostgresRuntimeConfiguration does. */
    record DitaPropertiesView(String prefix) {
        String neo4j() { return prefix + "-neo4j"; }
        String vespa() { return prefix + "-vespa"; }
    }
}
