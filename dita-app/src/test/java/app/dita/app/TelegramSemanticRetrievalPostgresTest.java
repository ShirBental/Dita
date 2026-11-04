package app.dita.app;

import app.dita.app.trial.SyntheticTrialController;
import app.dita.core.QueryService;
import app.dita.core.model.GroupScope;
import app.dita.core.store.MessageStore;
import app.dita.muse.MuseTransport;
import app.dita.telegram.TelegramAdapter;
import app.dita.telegram.TelegramGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 G1 REAL-STACK acceptance (GATED, never counted when skipped): PostgreSQL + Neo4j + Vespa running the
 * TASK-054 semantic package (message.sd text_embedding, frozen bge-small "dita-english" embedder), with
 * dita.retrieval.dense.messages=true. Proves what the offline tests cannot: a paraphrase that shares NO word with the
 * doctor messages is retrieved through the actual semantic channel ("vespa:dense"), then answered by ONE synthesis call.
 * <p>
 * Fakes: Telegram gateway, Muse transport (HAND-WRITTEN reply; no live Muse call). A fresh random fictional chat id per
 * run so the persistent test database never mixes runs. Gate: the KnowledgeSlicePostgresTest variables plus
 * DITA_TEST_SEMANTIC_MESSAGES=1 (set only after the semantic package is deployed to the TEST Vespa).
 */
class TelegramSemanticRetrievalPostgresTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long SENDER = 1001L;
    static final String FAKE_KEY = "synthetic-fake-model-key-not-real", FAKE_TOKEN = "123456789:AAfakeTokenForOfflineTestsOnly_0123456789";
    final long chat = -1_000_000_000_000L - new Random().nextInt(1_000_000_000);
    final String g = "tgt" + Math.abs(chat);
    final List<String> sent = new CopyOnWriteArrayList<>(), museBodies = new CopyOnWriteArrayList<>();
    final Map<String, String> replies = new HashMap<>();
    ConfigurableApplicationContext ctx;
    volatile String setupData;
    long updateId = 1, nextMid = 9000;
    Path evidence;

    @AfterEach void close() { if (ctx != null) ctx.close(); app.dita.muse.MuseCallCapTestAccess.reset(); }
    String id(long mid) { return g + "m" + mid; }

    @Test void aParaphraseIsRetrievedSemanticallyFromRawMessagesAndAnsweredWithOneSynthesisCall() throws Exception {
        for (String v : KnowledgeSlicePostgresTest.REQUIRED) assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: real-stack semantic test NOT EXECUTED");
        assumeTrue("1".equals(System.getenv("DITA_TEST_SEMANTIC_MESSAGES")), "semantic package not declared deployed on the test Vespa: NOT EXECUTED");
        boot();
        confirm();
        for (var m : TelegramMessagesModeTrialTest.MIXED) say(m.mid(), m.text(), m.replyTo());
        String paraphrase = "Which pediatrician do local parents trust most?";
        var q = ctx.getBean(QueryService.class);
        // 1. the actual semantic channel finds doctor messages for a no-overlap paraphrase (poll: the projection is async)
        List<app.dita.core.retrieval.Hit> hits = List.of();
        for (int i = 0; i < 120 && hits.stream().noneMatch(h -> h.via().contains("vespa:dense")); i++) {
            Thread.sleep(250); hits = q.messagesOnly(null).relevantMessages(new GroupScope(g), Long.toString(SENDER), paraphrase, 8);
        }
        System.out.println("[TASK-054 semantic] paraphrase hits=" + hits);
        Set<String> doctor = new HashSet<>(); for (long d : TelegramMessagesModeTrialTest.DOCTOR) doctor.add(id(d));
        assertTrue(hits.stream().anyMatch(h -> doctor.contains(h.messageId()) && h.via().contains("vespa:dense")), "semantic channel: " + hits);
        for (long u : TelegramMessagesModeTrialTest.UNRELATED) assertFalse(hits.stream().anyMatch(h -> h.messageId().equals(id(u)) && h.via().equals("vespa:dense")),
                "an unrelated message passed the cosine floor (calibration): " + u + " " + hits);
        // 2. isolation: another group sees nothing of this group's messages
        assertTrue(q.messagesOnly(null).relevantMessages(new GroupScope(g + "x"), Long.toString(SENDER), paraphrase, 8).isEmpty());
        // 3. end to end: one synthesis call, semantic channel recorded, validated cited answer (HAND-WRITTEN Muse reply)
        replies.put(paraphrase, TelegramMessagesModeTrialTestAccess.fakeAnswer(
                "P1 recommended Dr. Cedar for infant checkups; he was patient with our baby [" + id(59) + "].", id(59)));
        String r = ask("/ask " + paraphrase);
        JsonNode a = latestAsk();
        System.out.println("[TASK-054 semantic] ask=" + a.path("retrieval_channels") + " outcome=" + a.path("synthesis_outcomes") + " reply=" + r.replace("\n", " | "));
        assertEquals(1, museBodies.size());
        boolean semanticChannel = false; for (JsonNode c : a.path("retrieval_channels")) semanticChannel |= c.asText().equals("semantic");
        assertTrue(semanticChannel, a.path("retrieval_channels").toString());
        assertEquals("[\"SYNTHESIZED\"]", a.path("synthesis_outcomes").toString(), a.path("violations").toString());
        assertTrue(!r.contains("[trial]"), r);
        // 4. edit and deletion remove the message from the semantic index as well
        var edit = JSON.createObjectNode().put("update_id", ++updateId);
        var em = edit.putObject("edited_message").put("message_id", 52).put("date", now()).put("edit_date", now()).put("text", "Dr. Cedar is fine for everyone.");
        em.putObject("chat").put("id", chat).put("type", "supergroup"); em.putObject("from").put("id", SENDER).put("is_bot", false);
        ctx.getBean(TelegramAdapter.class).handle(edit);
        List<app.dita.core.retrieval.Hit> after = hits;
        for (int i = 0; i < 120 && after.stream().anyMatch(h -> h.messageId().equals(id(52))); i++) {
            Thread.sleep(250); after = q.messagesOnly(null).relevantMessages(new GroupScope(g), Long.toString(SENDER), paraphrase, 8);
        }
        assertFalse(after.stream().anyMatch(h -> h.messageId().equals(id(52))), "edited message still retrieved: " + after);
        assertTrue(museBodies.stream().noneMatch(b -> b.contains(FAKE_KEY)));
    }

    // ------------------------------------------------------------------ harness (same shape as the offline test)
    void boot() throws Exception {
        evidence = Files.createDirectories(Path.of("target")).resolve("telegram-semantic-test-" + UUID.randomUUID().toString().substring(0, 8));
        TelegramGateway gateway = new TelegramGateway() {
            long next = 7000;
            public List<JsonNode> getUpdates(long offset, int t, int limit) throws Exception { Thread.sleep(20); return List.of(); }
            public synchronized long sendMessage(long chatId, String text, Long replyTo, List<Button> buttons) {
                assertEquals(chat, chatId);
                if (!buttons.isEmpty()) setupData = buttons.get(0).callbackData();
                sent.add(text); return next++;
            }
            public void answerCallbackQuery(String id, String text) { }
            public Set<Long> chatAdministrators(long chatId) { return Set.of(); }
        };
        MuseTransport transport = (url, headers, body, timeout) -> {
            museBodies.add(body);
            for (var e : replies.entrySet()) if (body.contains(e.getKey())) return new MuseTransport.Reply(200, e.getValue(), Map.of());
            return new MuseTransport.Reply(500, "{}", Map.of());
        };
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TELEGRAM_BOT_TOKEN", FAKE_TOKEN); env.put("DITA_CALLBACK_SECRET", "x".repeat(40)); env.put("MODEL_API_KEY", FAKE_KEY);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dita.storage.backend", "POSTGRES");
        p.put("dita.storage.relay-consumer-prefix", "sem-" + UUID.randomUUID().toString().substring(0, 8));
        p.put("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL"));
        p.put("dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD");
        p.put("dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD");
        p.put("dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL"));
        p.put("dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD");
        p.put("dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL"));
        p.put("dita.retrieval.dense.messages", "true");
        p.put("dita.telegram.enabled", "true"); p.put("dita.telegram.mode", "LIVE"); p.put("dita.telegram.bot-username", "ExampleDitaBot");
        p.put("dita.telegram.allowed-chat-ids", Long.toString(chat)); p.put("dita.telegram.allowed-author-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.enabled", "true"); p.put("dita.telegram.synthetic-trial.approver-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.evidence-dir", evidence.toString());
        p.put("dita.telegram.synthetic-trial.session-declaration", "CONFIRM_IN_TELEGRAM");
        p.put("dita.telegram.synthetic-trial.session-budget-usd", "1");
        p.put("dita.telegram.synthetic-trial.answer-mode", "MESSAGES");
        p.put("dita.muse.enabled", "true"); p.put("dita.muse.transport", "HTTP"); p.put("dita.muse.max-output-tokens", "4096");
        p.put("dita.muse.chat-completions-url", "https://api.meta.ai/v1/chat/completions"); p.put("dita.muse.model", "muse-spark-1.3");
        p.put("dita.muse.max-calls-per-process", "6"); p.put("dita.muse.timeout", "60s");
        p.put("dita.muse.response-format", "PROMPT_ONLY"); p.put("dita.muse.reasoning-effort", "LOW");
        p.put("dita.memory.answers.synthesis.enabled", "true"); p.put("dita.memory.answers.synthesis.allow-live", "true");
        p.put("dita.memory.answers.synthesis.prompt", "V2");
        p.put("dita.extraction.worker-enabled", "false"); p.put("dita.coordination.enabled", "false");
        ctx = new SpringApplicationBuilder(DitaApplication.class).initializers(c -> {
            c.getBeanFactory().registerSingleton("ditaEnv", (java.util.function.Function<String, String>) env::get);
            c.getBeanFactory().registerSingleton("telegramGateway", gateway);
            c.getBeanFactory().registerSingleton("museTransport", transport);
        }).run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }
    long now() { return Instant.now().getEpochSecond() + 1; }
    void confirm() throws Exception {
        for (int i = 0; i < 400 && setupData == null; i++) Thread.sleep(25);
        assertNotNull(setupData);
        assertTrue(sent.get(0).contains("local semantic search"), sent.get(0));
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var cq = u.putObject("callback_query").put("id", "cq1").put("data", setupData);
        cq.putObject("from").put("id", SENDER).put("is_bot", false);
        cq.putObject("message").put("message_id", 1).put("date", now()).putObject("chat").put("id", chat).put("type", "supergroup");
        ctx.getBean(TelegramAdapter.class).handle(u);
        assertTrue(ctx.getBean(SyntheticTrialController.class).sessionConfirmed());
        Thread.sleep(1100);
    }
    void say(long mid, String text, long replyTo) {
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var msg = u.putObject("message").put("message_id", mid).put("date", now()).put("text", text);
        msg.putObject("chat").put("id", chat).put("type", "supergroup");
        msg.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        if (replyTo != 0) msg.putObject("reply_to_message").put("message_id", replyTo);
        ctx.getBean(TelegramAdapter.class).handle(u);
    }
    String ask(String text) throws Exception {
        int seen = sent.size();
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var msg = u.putObject("message").put("message_id", ++nextMid).put("date", now()).put("text", text);
        msg.putObject("chat").put("id", chat).put("type", "supergroup");
        msg.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        ctx.getBean(TelegramAdapter.class).handle(u);
        for (int i = 0; i < 2400 && sent.size() <= seen; i++) Thread.sleep(25);
        return sent.get(seen);
    }
    JsonNode latestAsk() throws Exception {
        Path run; try (Stream<Path> s = Files.list(evidence)) { run = s.filter(Files::isDirectory).findFirst().orElseThrow(); }
        try (Stream<Path> s = Files.list(run)) {
            return JSON.readTree(Files.readString(s.filter(f -> f.getFileName().toString().startsWith("ask-")).sorted().reduce((a, b) -> b).orElseThrow()));
        }
    }
}

/** Tiny bridge so the gated test reuses the offline test's hand-written reply builder with arbitrary ids. */
final class TelegramMessagesModeTrialTestAccess {
    static String fakeAnswer(String answer, String citedId) throws Exception {
        var content = TelegramMessagesModeTrialTest.JSON.createObjectNode().put("abstain", false).put("answer", answer);
        content.putArray("citations").add(citedId);
        var reply = TelegramMessagesModeTrialTest.JSON.createObjectNode().put("id", "fake-synthesis");
        reply.putArray("choices").addObject().put("finish_reason", "stop").put("index", 0).putObject("message").put("role", "assistant")
                .put("content", TelegramMessagesModeTrialTest.JSON.writeValueAsString(content));
        reply.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 40);
        return TelegramMessagesModeTrialTest.JSON.writeValueAsString(reply);
    }
}
