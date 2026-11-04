package app.dita.app;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.deletion.DeletionService;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.model.GroupRegistry;
import app.dita.core.model.GroupScope;
import app.dita.core.store.MessageStore;
import app.dita.eval.BenchPaths;
import app.dita.store.postgres.PostgresOutboxRelay;
import app.dita.telegram.CallbackSigner;
import app.dita.telegram.TelegramAdapter;
import app.dita.telegram.TelegramAdapterSettings;
import app.dita.telegram.TelegramGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-044 Priority 3: the user's Synthetic Lab conversation (eval/synthetic-lab/synthetic_updates.json, 41 Telegram
 * updates, four fictional authors) replayed through Dita's NORMAL ingestion path: TelegramAdapter.handle(update) →
 * allow-lists → IngestionPipeline → store (+ outbox → Neo4j/Vespa on POSTGRES). Message ids, authors, text and
 * timestamps are preserved; only chat.id is remapped to a fresh synthetic id per run so the replay can never touch the
 * real demo chat's memory. The run-start backlog cutoff (TASK-040) is a LIVE-polling safeguard and is off here, because
 * this is a replay of a historical conversation. Then the 15 acceptance questions are asked via /ask (the reply text
 * users see) and via QueryService (structured citations). Results only; scoring is done separately against the frozen
 * rubric. No model, no network. Opt-in: -Ddita.capability.run=true; POSTGRES with -Ddita.lab.backend=POSTGRES + DITA_TEST_*.
 */
class SyntheticLabReplayTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern QUESTION = Pattern.compile("^\\d+\\. \\*\\*(.+?)\\*\\*", Pattern.MULTILINE);

    static final class FakeGateway implements TelegramGateway {
        record Sent(long chatId, String text, Long replyTo) {}
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        long next = 990_000;
        @Override public List<JsonNode> getUpdates(long o, int t, int l) { return List.of(); }
        @Override public synchronized long sendMessage(long chatId, String text, Long replyTo, List<Button> b) { sent.add(new Sent(chatId, text, replyTo)); return ++next; }
        @Override public void answerCallbackQuery(String id, String text) { }
        @Override public Set<Long> chatAdministrators(long chatId) { return Set.of(); }
        String replyTo(long mid) { synchronized (sent) { return sent.stream().filter(s -> s.replyTo() != null && s.replyTo() == mid).map(Sent::text).findFirst().orElse(null); } }
    }

    @Test void replayAndAsk() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.capability.run"), "set -Ddita.capability.run=true");
        String backend = System.getProperty("dita.lab.backend", "IN_MEMORY");
        Map<String, String> props = new HashMap<>(Map.of("dita.storage.backend", backend, "dita.processing.dispatch.poll-interval", "50ms"));
        String prefix = "lab-" + UUID.randomUUID().toString().substring(0, 8);
        if (backend.equals("POSTGRES")) {
            for (String v : CapabilityPostgresRunTest.REQUIRED) assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: NOT EXECUTED");
            props.putAll(Map.of("dita.storage.relay-consumer-prefix", prefix,
                    "dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL"),
                    "dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD",
                    "dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD",
                    "dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL"),
                    "dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD",
                    "dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")));
        }
        Path root = BenchPaths.locate().projectRoot(), lab = root.resolve("eval/synthetic-lab");
        JsonNode updates = JSON.readTree(Files.readString(lab.resolve("synthetic_updates.json")));
        List<String> questions = new ArrayList<>();
        Matcher qm = QUESTION.matcher(Files.readString(lab.resolve("expected_questions.md")));
        while (qm.find()) questions.add(qm.group(1));
        assertEquals(15, questions.size(), "15 acceptance questions");

        long chat = -(1_000_000_000_000L + ThreadLocalRandom.current().nextLong(1, 999_999_999));   // fresh synthetic chat id
        Set<Long> authors = new LinkedHashSet<>();
        updates.forEach(u -> authors.add(u.path("message").path("from").path("id").asLong()));
        FakeGateway gw = new FakeGateway();
        List<String> out = new ArrayList<>();
        try (ConfigurableApplicationContext c = new SpringApplicationBuilder(DitaApplication.class)
                .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new))) {
            var settings = new TelegramAdapterSettings(Set.of(chat), "dita_synthetic_bot", false, 10, Set.of(),
                    Duration.ofDays(30), false, authors, null);                       // ignoreBefore = null: historical replay
            TelegramAdapter adapter = new TelegramAdapter(settings, c.getBean(MessageStore.class), c.getBean(IngestionPipeline.class),
                    c.getBean(DeletionService.class), c.getBean(QueryService.class), c.getBean(ClarificationGate.class), gw,
                    new CallbackSigner("synthetic-lab-callback-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
                    c.getBean(Clock.class), c.getBean(GroupRegistry.class));
            long lastDate = 0, lastId = 0, updateId = 0;
            for (JsonNode u : updates) {
                ObjectNode copy = u.deepCopy();
                ((ObjectNode) copy.path("message").path("chat")).put("id", chat).put("title", "Dita Synthetic Lab (replay)");
                adapter.handle(copy);
                lastDate = Math.max(lastDate, u.path("message").path("date").asLong());
                lastId = Math.max(lastId, u.path("message").path("message_id").asLong());
                updateId = Math.max(updateId, u.path("update_id").asLong());
            }
            GroupScope g = new GroupScope("tg" + Math.abs(chat));
            MessageStore store = c.getBean(MessageStore.class);
            for (JsonNode u : updates) {
                String id = "tg" + Math.abs(chat) + "m" + u.path("message").path("message_id").asLong();
                assertTrue(store.find(g, id).isPresent(), "ingested " + id);
            }
            if (backend.equals("POSTGRES")) awaitDrained(c.getBean(PostgresOutboxRelay.class), prefix);
            QueryService queries = c.getBean(QueryService.class);
            // Simpler-retrieval baseline on the SAME stored messages (in-memory only: it needs the store's BM25 index).
            QueryService baseline = backend.equals("IN_MEMORY") ? new QueryService(store,
                    app.dita.core.retrieval.Retrievers.build(store, app.dita.eval.capability.DitaProductRunner.BM25_ONLY),
                    new app.dita.core.memory.HistoryAccessFilter(app.dita.core.memory.HistoryAccess.FULL_GROUP_HISTORY, (sc, mb) -> java.util.Optional.empty(), store),
                    app.dita.eval.capability.DitaProductRunner.K, app.dita.eval.capability.DitaProductRunner.MAX_QUOTE) : null;
            List<String> base = new ArrayList<>();
            String asker = Long.toString(authors.iterator().next());
            for (int i = 0; i < questions.size(); i++) {
                String q = questions.get(i);
                long mid = lastId + 100 + i;
                ObjectNode ask = JSON.createObjectNode().put("update_id", ++updateId);
                ObjectNode m = ask.putObject("message").put("message_id", mid).put("date", lastDate + 60L * (i + 1)).put("text", "/ask " + q);
                m.putObject("from").put("id", Long.parseLong(asker)).put("is_bot", false).put("first_name", "Asker");
                m.putObject("chat").put("id", chat).put("type", "group").put("title", "Dita Synthetic Lab (replay)");
                adapter.handle(ask);
                String reply = gw.replyTo(mid);
                GroundedAnswer a = queries.ask(g, asker, q);                           // same call /ask makes, for structure
                ObjectNode o = JSON.createObjectNode();
                o.put("q", i + 1).put("question", q).put("backend", backend);
                o.put("behavior", !a.found() ? "UNKNOWN" : a.conflicting() ? "CONFLICT" : "ANSWER");
                o.put("answer_text", a.text());
                if (reply == null) o.putNull("telegram_reply"); else o.put("telegram_reply", reply);
                o.put("knowledge_items_used", a.knowledgeIds().size());
                ArrayNode cites = o.putArray("citations");
                for (Citation ci : a.citations()) {
                    String prefixId = "tg" + Math.abs(chat) + "m";
                    if (!ci.messageId().startsWith(prefixId)) fail("citation outside the replayed group: " + ci.messageId());
                    cites.addObject().put("message_id", Long.parseLong(ci.messageId().substring(prefixId.length())))
                            .put("author", ci.authorId()).put("evidence", ci.evidence().name()).put("polarity", ci.polarity());
                }
                out.add(o.toString());
                if (baseline != null) {
                    GroundedAnswer b = baseline.ask(g, asker, q);
                    ObjectNode bo = JSON.createObjectNode();
                    bo.put("q", i + 1).put("question", q).put("backend", "in_memory-bm25-only");
                    bo.put("behavior", !b.found() ? "UNKNOWN" : b.conflicting() ? "CONFLICT" : "ANSWER").put("answer_text", b.text());
                    bo.putNull("telegram_reply").put("knowledge_items_used", 0);
                    ArrayNode bc = bo.putArray("citations");
                    String pid = "tg" + Math.abs(chat) + "m";
                    for (Citation ci : b.citations()) bc.addObject().put("message_id", Long.parseLong(ci.messageId().substring(pid.length())))
                            .put("author", ci.authorId()).put("evidence", ci.evidence().name()).put("polarity", ci.polarity());
                    base.add(bo.toString());
                }
            }
            if (!base.isEmpty()) {
                Files.createDirectories(lab.resolve("results"));
                Files.writeString(lab.resolve("results/answers-in_memory_bm25.jsonl"), String.join("\n", base) + "\n");
            }
        }
        Path results = lab.resolve("results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("answers-" + backend.toLowerCase() + ".jsonl"), String.join("\n", out) + "\n");
    }

    static void awaitDrained(PostgresOutboxRelay relay, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (relay.lag(prefix + "-neo4j") == 0 && relay.lag(prefix + "-vespa") == 0
                    && !relay.health(prefix + "-neo4j").stalled() && !relay.health(prefix + "-vespa").stalled()) return;
            Thread.sleep(50);
        }
        fail("relay did not drain within 60s");
    }
}
