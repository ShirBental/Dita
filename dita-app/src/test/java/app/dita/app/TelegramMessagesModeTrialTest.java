package app.dita.app;

import app.dita.app.trial.SyntheticTrialController;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.knowledge.ExtractionService;
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
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-054 MESSAGES demo mode (offline acceptance): mixed synthetic conversation → /ask → the answer path's own message
 * retriever selects raw messages → ONE Muse synthesis call over exactly those messages → validator → reply citing the
 * original messages. No structured extraction at all. Real Spring context, adapter, retriever, composer, verifier,
 * synthesis, validator, approval gate and the $1 session-budget approver (no buttons, no typed phrases).
 * FAKES (no network): Telegram gateway; Muse transport. Muse replies are either the SAVED live v2 bodies with only the
 * ids rewritten (recorded data reused for a changed input) or HAND-WRITTEN (labelled "fake-synthesis"). They prove
 * wiring and validation, never model quality.
 */
class TelegramMessagesModeTrialTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000054L, SENDER = 1001L;
    static final String G = "tgt" + Math.abs(CHAT);
    static final String FAKE_KEY = "synthetic-fake-model-key-not-real", FAKE_TOKEN = "123456789:AAfakeTokenForOfflineTestsOnly_0123456789";
    static final Path V2 = SynthesisTrialHarness.ROOT.resolve("build/controlled-synthesis-cedar-v2/postgres");
    static final String MARK = "You write the reply of Dita";

    record M(long mid, String text, long replyTo) {}
    static final List<M> MIXED = List.of(
            new M(52, "Dr. Cedar on Maple Street is excellent for babies.", 0),
            new M(54, "Does anyone have a spare blue umbrella?", 0),
            new M(59, "I recommend Dr. Cedar for infant checkups; he was patient with our baby.", 0),
            new M(60, "The neighborhood picnic is planned for Saturday at noon.", 0),
            new M(61, "Dr. Cedar is not recommended for teenagers.", 0),
            new M(62, "I can bring paper cups to the picnic, but I cannot bring plates.", 0),
            new M(63, "Dr. Cedar explained our baby's checkup clearly and gave us time for questions.", 0),
            new M(64, "Has anyone seen a small green backpack near the playground?", 0),
            new M(65, "The library's storytelling session starts at four on Tuesday.", 0),
            new M(66, "I would choose Dr. Cedar again for a newborn appointment.", 0),
            new M(67, "Correction: the picnic starts at one, not noon.", 0),
            new M(68, "We have not confirmed whether anyone is bringing plates.", 0),
            new M(73, "I love blue baloons", 0),
            new M(74, "I want to go to the park", 0),
            new M(75, "Where are you sweety?", 0),
            new M(76, "He was patient with our baby.", 66),
            new M(80, "Coach Willow is great for teenagers.", 0),
            new M(81, "I would not recommend Coach Willow for teenagers.", 0));
    static final Set<Long> DOCTOR = Set.of(52L, 59L, 61L, 63L, 66L, 76L);
    static final Set<Long> UNRELATED = Set.of(54L, 60L, 62L, 64L, 65L, 67L, 68L, 73L, 74L, 75L);   // picnic/umbrella/balloon/park/...

    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> museBodies = new CopyOnWriteArrayList<>();
    final List<String> nonSynthesisBodies = new CopyOnWriteArrayList<>();
    final Map<String, String> synthesisByQuestion = new HashMap<>();
    Path evidence;
    ConfigurableApplicationContext ctx;
    long updateId = 5000, nextMid = 9000;

    @AfterEach void close() { if (ctx != null) ctx.close(); app.dita.muse.MuseCallCapTestAccess.reset(); }

    static String id(long mid) { return G + "m" + mid; }

    void boot() throws Exception { boot("CONFIRM_IN_TELEGRAM"); }

    void boot(String declaration) throws Exception {
        evidence = Files.createDirectories(Path.of("target")).resolve("telegram-messages-test-" + UUID.randomUUID().toString().substring(0, 8));
        TelegramGateway gateway = new TelegramGateway() {
            final List<Button> setup = new CopyOnWriteArrayList<>();
            long next = 7000;
            public List<JsonNode> getUpdates(long offset, int t, int limit) throws Exception { Thread.sleep(20); return List.of(); }
            public synchronized long sendMessage(long chatId, String text, Long replyTo, List<Button> buttons) {
                assertEquals(CHAT, chatId);
                for (Button b : buttons) assertFalse(b.callbackData().startsWith("tq:a:"), "no per-call approval buttons in budget mode");
                if (!buttons.isEmpty()) setupData = buttons.get(0).callbackData();
                sent.add(text); return next++;
            }
            public void answerCallbackQuery(String id, String text) { }
            public Set<Long> chatAdministrators(long chatId) { return Set.of(); }
        };
        MuseTransport transport = (url, headers, body, timeout) -> {
            museBodies.add(body);
            if (!body.contains(MARK)) { nonSynthesisBodies.add(body); return new MuseTransport.Reply(500, "{}", Map.of()); }
            for (var e : synthesisByQuestion.entrySet()) if (body.contains(e.getKey())) return new MuseTransport.Reply(200, e.getValue(), Map.of());
            return new MuseTransport.Reply(500, "{\"error\":{\"type\":\"server_error\",\"message\":\"synthetic\"}}", Map.of());
        };
        Map<String, String> env = Map.of("TELEGRAM_BOT_TOKEN", FAKE_TOKEN, "DITA_CALLBACK_SECRET", "x".repeat(40), "MODEL_API_KEY", FAKE_KEY);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dita.storage.backend", "IN_MEMORY");
        p.put("dita.telegram.enabled", "true"); p.put("dita.telegram.mode", "LIVE"); p.put("dita.telegram.bot-username", "ExampleDitaBot");
        p.put("dita.telegram.allowed-chat-ids", Long.toString(CHAT)); p.put("dita.telegram.allowed-author-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.enabled", "true"); p.put("dita.telegram.synthetic-trial.approver-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.evidence-dir", evidence.toString());
        p.put("dita.telegram.synthetic-trial.session-declaration", declaration);
        p.put("dita.telegram.synthetic-trial.session-budget-usd", "1");
        p.put("dita.telegram.synthetic-trial.answer-mode", "MESSAGES");
        p.put("dita.telegram.synthetic-trial.max-paid-calls", "8");
        p.put("dita.muse.enabled", "true"); p.put("dita.muse.transport", "HTTP"); p.put("dita.muse.max-output-tokens", "4096");
        p.put("dita.muse.chat-completions-url", "https://api.meta.ai/v1/chat/completions"); p.put("dita.muse.model", "muse-spark-1.3");
        p.put("dita.muse.max-calls-per-process", "6"); p.put("dita.muse.timeout", "60s");
        p.put("dita.muse.response-format", "PROMPT_ONLY"); p.put("dita.muse.reasoning-effort", "LOW");
        p.put("dita.memory.answers.synthesis.enabled", "true"); p.put("dita.memory.answers.synthesis.allow-live", "true");
        p.put("dita.memory.answers.synthesis.prompt", "V2");
        if (declaration.equals("PREAUTHORIZED_SESSION")) {
            p.put("dita.memory.answers.synthesis.enforce-language-rules", "false");
            p.put("dita.memory.answers.synthesis.show-sources", "false");
            p.put("dita.telegram.synthetic-trial.member-names." + SENDER, "Alice");
        }
        p.put("dita.extraction.worker-enabled", "false"); p.put("dita.coordination.enabled", "false");
        ctx = new SpringApplicationBuilder(DitaApplication.class).initializers(c -> {
            c.getBeanFactory().registerSingleton("ditaEnv", (java.util.function.Function<String, String>) env::get);
            c.getBeanFactory().registerSingleton("telegramGateway", gateway);
            c.getBeanFactory().registerSingleton("museTransport", transport);
        }).run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }
    volatile String setupData;

    TelegramAdapter adapter() { return ctx.getBean(TelegramAdapter.class); }
    MessageStore store() { return ctx.getBean(MessageStore.class); }
    long now() { return Instant.now().getEpochSecond() + 1; }

    void say(M m) {
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var msg = u.putObject("message").put("message_id", m.mid()).put("date", now()).put("text", m.text());
        msg.putObject("chat").put("id", CHAT).put("type", "supergroup");
        msg.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        if (m.replyTo() != 0) msg.putObject("reply_to_message").put("message_id", m.replyTo());
        adapter().handle(u);
    }

    /** Sends a command and waits for Dita's reply to it (the trial reply ends with a "[trial] " line). */
    String ask(String text) throws InterruptedException {
        int seen = sent.size();
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var msg = u.putObject("message").put("message_id", ++nextMid).put("date", now()).put("text", text);
        msg.putObject("chat").put("id", CHAT).put("type", "supergroup");
        msg.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        adapter().handle(u);
        await(() -> sent.size() > seen, "a reply to " + text);
        return sent.get(seen);
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        for (int i = 0; i < 400; i++) { if (cond.getAsBoolean()) return; Thread.sleep(25); }
        fail("timed out waiting for " + what);
    }

    Path runDir() throws Exception { try (Stream<Path> s = Files.list(evidence)) { return s.filter(Files::isDirectory).findFirst().orElseThrow(); } }

    JsonNode latestAsk() throws Exception {
        try (Stream<Path> s = Files.list(runDir())) {
            Path p = s.filter(f -> f.getFileName().toString().startsWith("ask-")).sorted().reduce((a, b) -> b).orElseThrow();
            return JSON.readTree(Files.readString(p));
        }
    }

    /** The synthesis user content (question + evidence) of the latest call: exactly what left the machine. */
    JsonNode latestSynthesisUser() throws Exception {
        try (Stream<Path> s = Files.list(runDir())) {
            Path d = s.filter(f -> f.getFileName().toString().startsWith("call-")).sorted().reduce((a, b) -> b).orElseThrow();
            assertTrue(d.getFileName().toString().endsWith("synthesis"), d.toString());
            JsonNode req = JSON.readTree(Files.readString(d.resolve("request.json")));
            return JSON.readTree(req.path("messages").get(1).path("content").asText());
        }
    }

    static Set<String> evidenceIds(JsonNode user) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode e : user.path("evidence")) out.add(e.path("id").asText());
        return out;
    }

    /** Saved live v2 body with m1/m2 rewritten to trial ids (recorded data, changed input). */
    static String saved(String q, long babies, long teens) throws Exception {
        return Files.readString(V2.resolve(q).resolve("synthesis-recording.json"))
                .replace("\\\"m1\\\"", "\\\"" + id(babies) + "\\\"").replace("\\\"m2\\\"", "\\\"" + id(teens) + "\\\"")
                .replace("[m1]", "[" + id(babies) + "]").replace("[m2]", "[" + id(teens) + "]");
    }

    /** fake-synthesis: a HAND-WRITTEN explicit abstention. */
    static String fakeAbstain() throws Exception {
        var reply = JSON.createObjectNode().put("id", "fake-synthesis");
        reply.putArray("choices").addObject().put("finish_reason", "stop").put("index", 0).putObject("message").put("role", "assistant")
                .put("content", "{\"abstain\": true, \"answer\": \"\", \"citations\": []}");
        reply.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20);
        return JSON.writeValueAsString(reply);
    }

    void confirmAndIngest() throws Exception {
        boot();
        await(() -> setupData != null, "the setup screen");
        assertTrue(sent.get(0).contains("Muse writes ONE answer from exactly those messages") && sent.get(0).contains("No structured extraction"), sent.get(0));
        assertTrue(sent.get(0).contains("preauthorized"), sent.get(0));
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var cq = u.putObject("callback_query").put("id", "cq1").put("data", setupData);
        cq.putObject("from").put("id", SENDER).put("is_bot", false);
        cq.putObject("message").put("message_id", 1).put("date", now()).putObject("chat").put("id", CHAT).put("type", "supergroup");
        adapter().handle(u);
        assertTrue(ctx.getBean(SyntheticTrialController.class).sessionConfirmed());
        assertEquals(SyntheticTrialController.AnswerMode.MESSAGES, ctx.getBean(SyntheticTrialController.class).mode());
        Thread.sleep(1100);                                         // messages dated strictly after the confirmation second
        int seen = sent.size();
        for (M m : MIXED) say(m);
        assertEquals(seen, sent.size(), "ingestion is silent");
        assertEquals(MIXED.size(), store().live(new GroupScope(G)).size());
        assertTrue(museBodies.isEmpty());
        assertTrue(ctx.getBean(ExtractionService.class).pendingIds(new GroupScope(G)).isEmpty(), "nothing queued for extraction");
    }

    void assertNoExtraction() {
        assertTrue(nonSynthesisBodies.isEmpty(), "no structured-extraction call ever reaches Muse");
        assertTrue(ctx.getBean(ExtractionService.class).pendingIds(new GroupScope(G)).isEmpty());
        for (var st : ExtractionBatch.Status.values())
            assertTrue(ctx.getBean(ExtractionQueue.class).batches(new GroupScope(G), st).isEmpty(), "no extraction batch: " + st);
        assertTrue(store().knowledge(new GroupScope(G)).isEmpty(), "no structured knowledge");
    }

    static String report(String label, JsonNode ask, JsonNode user) {
        return "[TASK-054 messages] " + label + " channels=" + ask.path("retrieval_channels") + " candidates="
                + ask.path("retrieval_candidates").findValuesAsText("id") + " vias=" + ask.path("retrieval_candidates").findValuesAsText("via")
                + " outbound_evidence=" + (user == null ? "[]" : evidenceIds(user)) + " outcome=" + ask.path("synthesis_outcomes")
                + " calls=" + ask.path("calls") + " final_sources=" + ask.path("final_sources");
    }

    /** fake-synthesis: a HAND-WRITTEN answer (labelled; proves wiring and validation only, never model quality). */
    static String fakeAnswer(String answer, long... cited) throws Exception {
        var content = JSON.createObjectNode().put("abstain", false).put("answer", answer);
        var c = content.putArray("citations"); for (long m : cited) c.add(id(m));
        var reply = JSON.createObjectNode().put("id", "fake-synthesis");
        reply.putArray("choices").addObject().put("finish_reason", "stop").put("index", 0).putObject("message").put("role", "assistant")
                .put("content", JSON.writeValueAsString(content));
        reply.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 40);
        return JSON.writeValueAsString(reply);
    }

    /** Ask, then the standard per-question checks: one call at most, no unrelated or other-subject leakage, no extraction. */
    record Turn(String reply, JsonNode ask, JsonNode user) {}
    Turn turn(String label, String question, Set<Long> forbidden) throws Exception {
        int before = museBodies.size();
        String r = ask("/ask " + question);
        JsonNode a = latestAsk();
        JsonNode u = a.path("calls").isEmpty() ? null : latestSynthesisUser();
        System.out.println(report(label, a, u));
        assertEquals("messages", a.path("mode").asText());
        assertEquals(0, a.path("extraction_calls").asInt());
        assertTrue(museBodies.size() - before <= 1, "at most ONE Muse call per question");
        assertEquals(a.path("calls").size(), museBodies.size() - before);
        if (u != null) {
            assertEquals(question, u.path("question").asText());
            for (long x : forbidden) assertFalse(u.toString().contains(id(x)), label + ": unrelated message sent to Muse: " + x);
            assertTrue(evidenceIds(u).size() <= 8, "compact context: at most 8 messages");
        }
        for (long x : forbidden) assertFalse(r.contains(id(x)), label + ": unrelated message cited: " + x);
        return new Turn(r, a, u);
    }
    /** A validated Muse answer: sources, no "[trial]" footer (operator 2026-11-04); the outcome is asserted from ask-NNN.json. */
    static boolean validated(String reply) { return reply.contains("Sources:") && !reply.contains("[trial]"); }

    @Test void mixedConversationRawMessagesToOneSynthesisCallToValidatedCitedReply() throws Exception {
        assumeTrue(Files.isRegularFile(V2.resolve("q1/synthesis-recording.json")) && Files.isRegularFile(V2.resolve("q2/synthesis-recording.json")),
                "saved synthesis responses absent: NOT EXECUTED");
        confirmAndIngest();
        // saved live v2 bodies (ids rewritten) for the two focused questions; the rest HAND-WRITTEN (fake-synthesis)
        synthesisByQuestion.put("Is Dr. Cedar recommended for babies?", saved("q1", 52, 61));
        synthesisByQuestion.put("Is Dr. Cedar recommended for teenagers?", saved("q2", 52, 61));
        synthesisByQuestion.put("Is Dr. Cedar recommended for adults?", fakeAbstain());
        synthesisByQuestion.put("What do members say about Dr. Cedar?", fakeAnswer(
                "P1 said Dr. Cedar on Maple Street is excellent for babies [" + id(52) + "]. P1 stated Dr. Cedar is not recommended for teenagers [" + id(61) + "].", 52, 61));
        synthesisByQuestion.put("Is Coach Willow recommended for teenagers?", fakeAnswer(
                "P1 said Coach Willow is great for teenagers [" + id(80) + "].", 80));
        synthesisByQuestion.put("Do members recommend Coach Willow for teenagers?", fakeAnswer(
                "P1 said Coach Willow is great for teenagers [" + id(80) + "]. P1 said they would not recommend Coach Willow for teenagers [" + id(81) + "].", 80, 81));
        synthesisByQuestion.put("When does the picnic start?", fakeAnswer("P1 said the picnic starts at one, not noon [" + id(67) + "].", 67));

        Set<Long> notDoctor = new HashSet<>(UNRELATED);

        // ---- babies (G2): focused answer may omit the teenagers-only statement; validated; one call; unrelated excluded
        Turn t1 = turn("babies", "Is Dr. Cedar recommended for babies?", notDoctor);
        System.out.println("[TASK-054 messages] babies outbound_user_content=" + t1.user());
        System.out.println("[TASK-054 messages] babies reply=" + t1.reply().replace("\n", " | "));
        assertEquals(1, t1.ask().path("calls").size(), "exactly one Muse call for this question");
        assertTrue(evidenceIds(t1.user()).contains(id(52)));
        for (JsonNode ch : t1.ask().path("retrieval_channels")) assertTrue(Set.of("keyword", "graph").contains(ch.asText()),
                "in-memory offline stack: keyword + graph only (semantic needs the real stack): " + ch.asText());
        assertTrue(t1.ask().path("retrieval_candidates").findValuesAsText("via").stream().anyMatch(v -> v.contains("graph:EXPLICIT_REPLY")),
                "the pronoun-only reply is reached through its Telegram Reply link");
        assertEquals("[\"SYNTHESIZED\"]", t1.ask().path("synthesis_outcomes").toString(), t1.ask().path("violations").toString());
        assertEquals("[\"" + id(52) + "\"]", t1.ask().path("final_sources").toString());
        assertFalse(t1.ask().path("final_answer").path("conflicting").asBoolean(), "babies vs teenagers is not a disagreement");
        assertTrue(validated(t1.reply()) && t1.reply().contains("[" + id(52) + "]"), t1.reply());
        assertFalse(t1.reply().contains("disagree"), t1.reply());
        assertTrue(t1.reply().startsWith("Synthetic said"), "speaker shown by Telegram display name: " + t1.reply());
        assertFalse(t1.reply().contains(Long.toString(SENDER)), "no numeric Telegram id in the reply: " + t1.reply());
        assertFalse(t1.user().toString().contains("Synthetic"), "the display name never goes to Muse (P1 only)");
        assertTrue(t1.ask().path("final_answer").path("knowledge_ids").isEmpty(), "raw messages, not structured knowledge");

        // ---- teenagers: symmetric
        Turn t2 = turn("teenagers", "Is Dr. Cedar recommended for teenagers?", notDoctor);
        assertEquals("[\"SYNTHESIZED\"]", t2.ask().path("synthesis_outcomes").toString(), t2.ask().path("violations").toString());
        assertEquals("[\"" + id(61) + "\"]", t2.ask().path("final_sources").toString());
        assertTrue(validated(t2.reply()), t2.reply());

        // ---- general question: both contexts presented, no invented disagreement (hand-written answer)
        Turn t3 = turn("general", "What do members say about Dr. Cedar?", notDoctor);
        assertEquals("[\"SYNTHESIZED\"]", t3.ask().path("synthesis_outcomes").toString(), t3.ask().path("violations").toString());
        assertTrue(t3.reply().contains("[" + id(52) + "]") && t3.reply().contains("[" + id(61) + "]"), t3.reply());
        assertFalse(t3.reply().toLowerCase().contains("disagree"), t3.reply());

        // ---- unsupported population: Muse abstains → honest no-answer (and R10 would reject a babies-only extrapolation)
        Turn t4 = turn("adults(unsupported)", "Is Dr. Cedar recommended for adults?", notDoctor);
        assertEquals("[\"ABSTAINED\"]", t4.ask().path("synthesis_outcomes").toString());
        assertTrue(t4.reply().contains("I can't answer that reliably") && t4.reply().contains("Muse abstained"), t4.reply());
        assertFalse(t4.reply().contains("Sources:"), t4.reply());

        // ---- nothing relevant: no Muse call at all
        int bodies = museBodies.size();
        Turn t8 = turn("wifi(nothing relevant)", "What is the wifi password?", Set.of());
        assertTrue(t8.ask().path("calls").isEmpty(), "a Muse call was made for an unrelated question; candidates=" + t8.ask().path("retrieval_candidates"));
        assertEquals(bodies, museBodies.size());
        assertTrue(t8.reply().contains("No relevant messages found; no Muse call was made."), t8.reply());

        // ---- /extract is off in this mode
        assertTrue(ask("/extract").contains("Structured extraction is off in this demo mode"));

        // ---- edit withdraws; the edited text never reaches Muse; the babies statement is no longer cited
        var edit = JSON.createObjectNode().put("update_id", ++updateId);
        var em = edit.putObject("edited_message").put("message_id", 52).put("date", now()).put("edit_date", now()).put("text", "Dr. Cedar is fine for everyone.");
        em.putObject("chat").put("id", CHAT).put("type", "supergroup");
        em.putObject("from").put("id", SENDER).put("is_bot", false);
        adapter().handle(edit);
        Turn t9 = turn("babies-after-edit", "Is Dr. Cedar recommended for babies?", notDoctor);
        assertFalse(museBodies.stream().anyMatch(b -> b.contains("fine for everyone")), "edited text never reaches Muse");
        assertFalse(t9.reply().contains("[" + id(52) + "]"), t9.reply());
        if (t9.user() != null) assertFalse(evidenceIds(t9.user()).contains(id(52)));

        // ---- /forget (deletion) of the teenagers statement: never sent or cited again
        var forget = JSON.createObjectNode().put("update_id", ++updateId);
        var fm = forget.putObject("message").put("message_id", ++nextMid).put("date", now()).put("text", "/forget");
        fm.putObject("chat").put("id", CHAT).put("type", "supergroup");
        fm.putObject("from").put("id", SENDER).put("is_bot", false);
        fm.putObject("reply_to_message").put("message_id", 61);
        adapter().handle(forget);
        int before = museBodies.size();
        Turn t10 = turn("teenagers-after-forget", "Is Dr. Cedar recommended for teenagers?", notDoctor);
        assertFalse(t10.reply().contains("[" + id(61) + "]"), t10.reply());
        for (int i = before; i < museBodies.size(); i++) assertFalse(museBodies.get(i).contains("Dr. Cedar is not recommended for teenagers"), "deleted text sent");

        assertNoExtraction();
        System.out.println("[TASK-054 messages] total Muse calls=" + museBodies.size() + " (all synthesis) extraction calls=0");
        assertCleanEvidence();
    }

    /**
     * Second run (a new process: the runner's per-process cap is 6 Muse calls, so one rehearsal run answers at most 6
     * questions). Genuine same-context conflict about a different fictional subject, a correction, and the
     * unauthorized / foreign-chat / duplicate handling. Muse replies are HAND-WRITTEN (fake-synthesis).
     */
    @Test void sameContextConflictCorrectionAndUnauthorizedOrDuplicateInput() throws Exception {
        confirmAndIngest();
        synthesisByQuestion.put("Is Dr. Cedar recommended for babies?", fakeAnswer(
                "P1 said Dr. Cedar on Maple Street is excellent for babies [" + id(52) + "].", 52));
        synthesisByQuestion.put("Is Coach Willow recommended for teenagers?", fakeAnswer(
                "P1 said Coach Willow is great for teenagers [" + id(80) + "].", 80));
        synthesisByQuestion.put("Do members recommend Coach Willow for teenagers?", fakeAnswer(
                "P1 said Coach Willow is great for teenagers [" + id(80) + "]. P1 said they would not recommend Coach Willow for teenagers [" + id(81) + "].", 80, 81));
        synthesisByQuestion.put("When does the picnic start?", fakeAnswer("P1 said the picnic starts at one, not noon [" + id(67) + "].", 67));
        Set<Long> notDoctor = new HashSet<>(UNRELATED);

        // ---- genuine same-context conflict (different fictional subject): one-sided answer rejected, deterministic
        //      fallback keeps both sides; two-sided answer validated
        // keyword overlap ("recommend", "teenagers") may also send Cedar statements; they are never cited for Willow
        Set<Long> notWillow = new HashSet<>(UNRELATED);
        Turn t5 = turn("willow one-sided", "Is Coach Willow recommended for teenagers?", notWillow);
        assertEquals("[\"FALLBACK_INVALID\"]", t5.ask().path("synthesis_outcomes").toString());
        assertTrue(t5.ask().path("violations").toString().contains("R5 conflicting evidence: the opposing side is missing"), t5.ask().path("violations").toString());
        assertTrue(t5.ask().path("final_answer").path("conflicting").asBoolean());
        assertTrue(t5.reply().contains("[" + id(80) + "]") && t5.reply().contains("[" + id(81) + "]"), "the fallback keeps both sides: " + t5.reply());
        assertTrue(t5.reply().contains("[trial] Not a Muse answer"), t5.reply());
        Turn t6 = turn("willow two-sided", "Do members recommend Coach Willow for teenagers?", notWillow);
        assertEquals("[\"SYNTHESIZED\"]", t6.ask().path("synthesis_outcomes").toString(), t6.ask().path("violations").toString());
        assertEquals("[\"" + id(80) + "\",\"" + id(81) + "\"]", t6.ask().path("final_sources").toString());
        // and the Willow conflict did not block the Cedar teenagers answer above (t2 SYNTHESIZED) although both mention teenagers

        // ---- correction: the picnic question gets picnic messages only
        Set<Long> notPicnic = new HashSet<>(DOCTOR); notPicnic.addAll(Set.of(54L, 64L, 73L, 74L, 75L, 80L, 81L));
        Turn t7 = turn("picnic correction", "When does the picnic start?", notPicnic);
        assertTrue(evidenceIds(t7.user()).contains(id(67)), evidenceIds(t7.user()).toString());
        assertEquals("[\"SYNTHESIZED\"]", t7.ask().path("synthesis_outcomes").toString(), t7.ask().path("violations").toString());

        // ---- unauthorized sender, another chat, and a duplicate delivery are not stored; a duplicate /ask makes no call
        int live = store().live(new GroupScope(G)).size();
        var other = JSON.createObjectNode().put("update_id", ++updateId);
        var om = other.putObject("message").put("message_id", 90).put("date", now()).put("text", "Dr. Cedar is wonderful for adults.");
        om.putObject("chat").put("id", CHAT).put("type", "supergroup");
        om.putObject("from").put("id", 1111L).put("is_bot", false).put("first_name", "Stranger");
        adapter().handle(other);
        var elsewhere = JSON.createObjectNode().put("update_id", ++updateId);
        var xm = elsewhere.putObject("message").put("message_id", 91).put("date", now()).put("text", "Dr. Cedar is wonderful for adults.");
        xm.putObject("chat").put("id", -100999L).put("type", "supergroup");
        xm.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        adapter().handle(elsewhere);
        say(MIXED.get(0));                                        // same Telegram message id again
        assertEquals(live, store().live(new GroupScope(G)).size(), "unauthorized, foreign-chat and duplicate messages are not stored");
        assertFalse(museBodies.stream().anyMatch(b -> b.contains("wonderful for adults")));
        var dup = JSON.createObjectNode().put("update_id", ++updateId);
        var dm = dup.putObject("message").put("message_id", ++nextMid).put("date", now()).put("text", "/ask Is Dr. Cedar recommended for babies?");
        dm.putObject("chat").put("id", CHAT).put("type", "supergroup");
        dm.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        int seen = sent.size(), calls = museBodies.size();
        adapter().handle(dup);
        await(() -> sent.size() > seen, "the reply to the first delivery");
        adapter().handle(dup.deepCopy());                         // Telegram redelivers the same update
        Thread.sleep(500);
        assertEquals(seen + 1, sent.size(), "a redelivered /ask is answered once");
        assertEquals(calls + 1, museBodies.size(), "a redelivered /ask makes no second call");

        assertNoExtraction();
        assertCleanEvidence();
    }

    /**
     * CAPABILITY PROBE (not a feature test): a pure paraphrase that shares no token with any doctor message. Raw messages
     * have NO embeddings in this tree (Vespa message schema: 8-D placeholder never written; in-memory: BM25 + graph), so a
     * meaning-only match must NOT be found. If this ever starts passing differently, semantic retrieval was added and this
     * probe must be revisited rather than read as keyword success.
     */
    @Test void paraphraseRetrievalIsNotAvailableForRawMessagesKeywordMatchingIsNotEmbeddings() throws Exception {
        confirmAndIngest();
        var q = ctx.getBean(app.dita.core.QueryService.class);
        String paraphrase = "Which pediatrician do local parents trust most?";
        Set<String> qTokens = app.dita.core.text.Tokenizer.normalizedSet(paraphrase);
        for (long d : DOCTOR) {
            String text = store().find(new GroupScope(G), id(d)).orElseThrow().text();
            Set<String> shared = new HashSet<>(app.dita.core.text.Tokenizer.normalizedSet(text)); shared.retainAll(qTokens);
            assertTrue(shared.isEmpty(), "the paraphrase must share no token with " + d + ": " + shared);
        }
        var hits = q.messagesOnly(null).relevantMessages(new GroupScope(G), Long.toString(SENDER), paraphrase, 8);
        System.out.println("[TASK-054 messages] paraphrase probe hits=" + hits);
        for (var h : hits) assertFalse(h.via().contains("dense") || h.via().contains("semantic") || h.via().contains("embed"), h.via());
        for (long d : DOCTOR) assertFalse(hits.stream().anyMatch(h -> h.messageId().equals(id(d))),
                "a doctor message was found by meaning alone; semantic retrieval exists after all: " + hits);
        // and the end-to-end consequence: no Muse call, an honest "nothing found"
        String r = ask("/ask " + paraphrase);
        System.out.println("[TASK-054 messages] paraphrase reply=" + r.replace("\n", " | "));
        assertTrue(museBodies.isEmpty(), "no Muse call when retrieval finds nothing");
        assertTrue(r.contains("No relevant messages found"), r);
        // the keyword version of the same intent IS found (lexical), which is what the demo relies on
        var kw = q.messagesOnly(null).relevantMessages(new GroupScope(G), Long.toString(SENDER), "Which doctor is good for babies?", 8);
        assertTrue(kw.stream().anyMatch(h -> h.messageId().equals(id(52)) && h.via().equals("lexical")), kw.toString());
        assertNoExtraction();
    }

    void assertCleanEvidence() throws Exception {
        try (Stream<Path> files = Files.walk(evidence)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String c = Files.readString(f);
                assertFalse(c.contains(FAKE_KEY) || c.contains(FAKE_TOKEN), "credential in " + f);
            }
        }
        try (Stream<Path> dirs = Files.list(runDir())) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                assertTrue(d.getFileName().toString().endsWith("synthesis"), "only synthesis calls: " + d);
                JsonNode ap = JSON.readTree(Files.readString(d.resolve("approval.json")));
                assertEquals("preauthorized-session-budget", ap.path("approval_mode").asText(), d.toString());
                assertEquals(ap.path("decision").asText().equals("APPROVED"), Files.exists(d.resolve("attempted")), d.toString());
            }
        }
    }

    @Test void preauthorizedSessionStartsSilentlyAndKeepsItsTimeBoundary() throws Exception {
        boot("PREAUTHORIZED_SESSION");
        var controller = ctx.getBean(SyntheticTrialController.class);
        assertTrue(controller.sessionConfirmed());
        assertTrue(sent.isEmpty(), "no setup or synthetic-data confirmation message");
        assertNull(setupData);
        Path session = Files.walk(evidence).filter(p -> p.getFileName().toString().equals("session.json")).findFirst().orElseThrow();
        JsonNode record = JSON.readTree(session.toFile());
        Instant start = Instant.parse(record.path("confirmed_at").asText());
        assertFalse(controller.acceptsPlainMessage(start.minusSeconds(2)), "backlog stays excluded");
        assertTrue(controller.acceptsPlainMessage(start.plusSeconds(1)));
        String original = Files.readString(session);
        controller.announce();
        assertEquals(original, Files.readString(session), "repeated announce must not move the session window");
        say(new M(950, "A fictional message for the offline trial.", 0));
        assertTrue(store().find(new GroupScope(G), id(950)).isPresent());
        assertTrue(sent.isEmpty(), "ordinary messages are stored silently");
        assertTrue(museBodies.isEmpty(), "startup and storage do not call Muse");
    }

    @Test void directMuseReplyHasNoLanguageGateOrVisibleSources() throws Exception {
        boot("PREAUTHORIZED_SESSION");
        assertEquals("Alice", ctx.getBean(app.dita.core.model.MemberNames.class).name(Long.toString(SENDER)));
        say(new M(951, "The picnic is Saturday at one.", 0));
        synthesisByQuestion.put("What is the picnic plan?", fakeAnswer("The gathering is set for Saturday at one [" + id(951) + "].", 951));
        ctx.getBean(app.dita.core.model.MemberNames.class).observe(Long.toString(SENDER), "Alice");
        String reply = ask("/ask What is the picnic plan?");
        assertTrue(reply.contains("The gathering is set for Saturday at one."), reply);
        assertFalse(reply.contains("Sources:"), reply);
        assertFalse(reply.contains(id(951)), reply);
        assertFalse(reply.contains("[trial]"), reply);
        assertEquals("[\"SYNTHESIZED\"]", latestAsk().path("synthesis_outcomes").toString());
        assertTrue(latestAsk().path("final_sources").toString().contains(id(951)), "internal provenance retained");
        assertEquals(1, museBodies.size());
    }
}
