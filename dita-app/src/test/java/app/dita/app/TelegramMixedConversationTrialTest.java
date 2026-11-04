package app.dita.app;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * TASK-054 ACCEPTANCE (offline): mixed synthetic group conversation → /ask → Dita selects the relevant messages with the
 * answer path's own retrieval → bounded extraction (Approve button) → knowledge → compact synthesis request (Approve
 * button) → validated reply with sources, in the trial chat. Real Spring context, real adapter, production extraction,
 * retrieval, answer composer, synthesis validator and approval gate, the Telegram button approver and the trial worker
 * thread. FAKES (no network): the Telegram gateway, and the Muse transport.
 * <ul>
 *   <li>Extraction replies are HAND-WRITTEN by this test from the request it receives (labelled "fake-extraction"). They
 *   prove wiring and validation only, never model quality.</li>
 *   <li>Synthesis replies are the SAVED live v2 responses with only the message ids rewritten. Reused for a changed
 *   input, they do not prove model quality either.</li>
 * </ul>
 * Conversation: operator's mixed synthetic messages (run-20261103T214421Z-06c0, distinct texts) plus one reply,
 * "He was patient with our baby.", that refers to the doctor only through its reply link.
 */
class TelegramMixedConversationTrialTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000054L, SENDER = 1001L;
    static final String G = "tgt" + Math.abs(CHAT);
    static final String FAKE_KEY = "synthetic-fake-model-key-not-real", FAKE_TOKEN = "123456789:AAfakeTokenForOfflineTestsOnly_0123456789";
    static final Path V2 = SynthesisTrialHarness.ROOT.resolve("build/controlled-synthesis-cedar-v2/postgres");
    static final Path FAILED_RUN = SynthesisTrialHarness.ROOT.resolve("build/telegram-synthesis-trial/run-20261103T214421Z-06c0");

    /** mid, text, reply-to mid (0 = none). */
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
            new M(76, "He was patient with our baby.", 66));
    static final Set<Long> DOCTOR = Set.of(52L, 59L, 61L, 63L, 66L, 76L);
    static final Set<Long> UNRELATED = Set.of(54L, 60L, 62L, 64L, 65L, 67L, 68L, 73L, 74L, 75L);

    /** Hand-written extraction table (fake-extraction): text → {subject, predicate, population quote or null}. */
    static final Map<String, String[]> FAKE_EXTRACTION = Map.of(
            "Dr. Cedar on Maple Street is excellent for babies.", new String[]{"Dr. Cedar", "recommends", "for babies"},
            "I recommend Dr. Cedar for infant checkups; he was patient with our baby.", new String[]{"Dr. Cedar", "recommends", "for infant checkups"},
            "Dr. Cedar is not recommended for teenagers.", new String[]{"Dr. Cedar", "advises_against", "for teenagers"},
            "Dr. Cedar explained our baby's checkup clearly and gave us time for questions.", new String[]{"Dr. Cedar", "states", null},
            "I would choose Dr. Cedar again for a newborn appointment.", new String[]{"Dr. Cedar", "recommends", "for a newborn appointment"},
            "He was patient with our baby.", new String[]{"Dr. Cedar", "states", null},
            "The neighborhood picnic is planned for Saturday at noon.", new String[]{"picnic", "states", null},
            "Correction: the picnic starts at one, not noon.", new String[]{"picnic", "states", null});

    final List<Sent> sent = new CopyOnWriteArrayList<>();
    final List<String> callbackAnswers = new CopyOnWriteArrayList<>();
    final List<String> museBodies = new CopyOnWriteArrayList<>();
    final Map<String, String> synthesisByQuestion = new HashMap<>();
    volatile String failNextExtractionWith;                    // a saved Muse body returned for the next extraction call
    Path evidence;
    ConfigurableApplicationContext ctx;
    long updateId = 5000, nextMid = 9000;

    record Sent(long id, String text, List<TelegramGateway.Button> buttons) {}

    @AfterEach void close() { if (ctx != null) ctx.close(); app.dita.muse.MuseCallCapTestAccess.reset(); }

    static String id(long mid) { return G + "m" + mid; }

    void boot() throws Exception {
        evidence = Files.createDirectories(Path.of("target")).resolve("telegram-mixed-test-" + UUID.randomUUID().toString().substring(0, 8));
        TelegramGateway gateway = new TelegramGateway() {
            long next = 7000;
            public List<JsonNode> getUpdates(long offset, int t, int limit) throws Exception { Thread.sleep(20); return List.of(); }
            public synchronized long sendMessage(long chatId, String text, Long replyTo, List<Button> buttons) {
                assertEquals(CHAT, chatId, "the trial only ever posts to the trial chat");
                long id = next++; sent.add(new Sent(id, text, List.copyOf(buttons))); return id;
            }
            public void answerCallbackQuery(String id, String text) { callbackAnswers.add(text); }
            public Set<Long> chatAdministrators(long chatId) { return Set.of(); }
        };
        MuseTransport transport = (url, headers, body, timeout) -> {
            assertEquals("Bearer " + FAKE_KEY, headers.get("Authorization"));
            museBodies.add(body);
            if (body.contains("You write the reply of Dita")) {
                for (var e : synthesisByQuestion.entrySet()) if (body.contains(e.getKey())) return new MuseTransport.Reply(200, e.getValue(), Map.of());
                return new MuseTransport.Reply(500, "{\"error\":{\"type\":\"server_error\",\"message\":\"synthetic\"}}", Map.of());
            }
            String fail = failNextExtractionWith;
            if (fail != null) { failNextExtractionWith = null; return new MuseTransport.Reply(200, fail, Map.of()); }
            return new MuseTransport.Reply(200, fakeExtraction(body), Map.of());
        };
        Map<String, String> env = Map.of("TELEGRAM_BOT_TOKEN", FAKE_TOKEN, "DITA_CALLBACK_SECRET", "x".repeat(40), "MODEL_API_KEY", FAKE_KEY);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dita.storage.backend", "IN_MEMORY");
        p.put("dita.telegram.enabled", "true"); p.put("dita.telegram.mode", "LIVE"); p.put("dita.telegram.bot-username", "ExampleDitaBot");
        p.put("dita.telegram.allowed-chat-ids", Long.toString(CHAT)); p.put("dita.telegram.allowed-author-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.enabled", "true"); p.put("dita.telegram.synthetic-trial.approver-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.evidence-dir", evidence.toString());
        p.put("dita.telegram.synthetic-trial.session-declaration", "CONFIRM_IN_TELEGRAM");
        p.put("dita.telegram.synthetic-trial.max-paid-calls", "20"); p.put("dita.telegram.synthetic-trial.approval-timeout", "20s");
        p.put("dita.muse.enabled", "true"); p.put("dita.muse.transport", "HTTP"); p.put("dita.muse.max-output-tokens", "4096");
        p.put("dita.muse.max-calls-per-process", "20"); p.put("dita.muse.timeout", "60s");
        p.put("dita.muse.response-format", "PROMPT_ONLY"); p.put("dita.muse.reasoning-effort", "LOW");
        p.put("dita.memory.answers.synthesis.enabled", "true"); p.put("dita.memory.answers.synthesis.allow-live", "true");
        p.put("dita.extraction.worker-enabled", "false"); p.put("dita.coordination.enabled", "false");
        ctx = new SpringApplicationBuilder(DitaApplication.class).initializers(c -> {
            c.getBeanFactory().registerSingleton("ditaEnv", (java.util.function.Function<String, String>) env::get);
            c.getBeanFactory().registerSingleton("telegramGateway", gateway);
            c.getBeanFactory().registerSingleton("museTransport", transport);
        }).run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    /** fake-extraction: hand-written items for the request's extractable messages found in {@link #FAKE_EXTRACTION}. */
    static String fakeExtraction(String body) throws Exception {
        String user = JSON.readTree(body).path("messages").get(1).path("content").asText();
        JsonNode msgs = JSON.readTree(user.substring(user.indexOf('['), user.lastIndexOf(']') + 1));
        ObjectNode out = JSON.createObjectNode().put("note", "fake-extraction: hand-written by TelegramMixedConversationTrialTest");
        ArrayNode items = out.putArray("items");
        for (JsonNode m : msgs) {
            if (!m.path("extractable").asBoolean()) continue;
            String[] f = FAKE_EXTRACTION.get(m.path("text").asText());
            if (f == null) continue;
            ObjectNode it = items.addObject().put("status", "ATTRIBUTED_STATEMENT").put("certainty", "ASSERTED")
                    .put("subject_id", f[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "_")).put("subject", f[0]).put("predicate", f[1]);
            it.putArray("evidence").addObject().put("message_id", m.path("id").asText()).put("quote", m.path("text").asText());
            ArrayNode q = it.putArray("qualifiers");
            if (f[2] != null) q.addObject().put("kind", "POPULATION").put("message_id", m.path("id").asText()).put("quote", f[2]);
            it.putArray("entities");
        }
        ObjectNode reply = JSON.createObjectNode().put("id", "fake-extraction");
        reply.putArray("choices").addObject().put("finish_reason", "stop").put("index", 0)
                .putObject("message").put("role", "assistant").put("content", JSON.writeValueAsString(out));
        reply.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 50);
        return JSON.writeValueAsString(reply);
    }

    /** Saved live v2 synthesis body with m1/m2 rewritten to the given trial ids (recorded data, changed input). */
    static String savedSynthesis(String q, long babies, long teens) throws Exception {
        return Files.readString(V2.resolve(q).resolve("synthesis-recording.json"))
                .replace("\\\"m1\\\"", "\\\"" + id(babies) + "\\\"").replace("\\\"m2\\\"", "\\\"" + id(teens) + "\\\"")
                .replace("[m1]", "[" + id(babies) + "]").replace("[m2]", "[" + id(teens) + "]");
    }

    TelegramAdapter adapter() { return ctx.getBean(TelegramAdapter.class); }
    long now() { return Instant.now().getEpochSecond() + 1; }

    void say(M m) {
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var msg = u.putObject("message").put("message_id", m.mid()).put("date", now()).put("text", m.text());
        msg.putObject("chat").put("id", CHAT).put("type", "supergroup");
        msg.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        if (m.replyTo() != 0) msg.putObject("reply_to_message").put("message_id", m.replyTo());
        adapter().handle(u);
    }

    JsonNode cmd(String text) {
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var msg = u.putObject("message").put("message_id", ++nextMid).put("date", now()).put("text", text);
        msg.putObject("chat").put("id", CHAT).put("type", "supergroup");
        msg.putObject("from").put("id", SENDER).put("is_bot", false).put("first_name", "Synthetic");
        adapter().handle(u);
        return u;
    }

    void press(long from, String cbData) {
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var cq = u.putObject("callback_query").put("id", "cq" + updateId).put("data", cbData);
        cq.putObject("from").put("id", from).put("is_bot", false);
        var msg = cq.putObject("message").put("message_id", 1).put("date", now());
        msg.putObject("chat").put("id", CHAT).put("type", "supergroup");
        adapter().handle(u);
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        for (int i = 0; i < 400; i++) { if (cond.getAsBoolean()) return; Thread.sleep(25); }
        fail("timed out waiting for " + what);
    }

    /** Waits for the next open approval screen (after {@code seen} messages) and returns it. */
    Sent nextApproval(int seen) throws InterruptedException {
        await(() -> sent.stream().skip(seen).anyMatch(s -> s.buttons().stream().anyMatch(b -> b.callbackData().startsWith("tq:a:"))), "an approval screen");
        return sent.stream().skip(seen).filter(s -> s.buttons().stream().anyMatch(b -> b.callbackData().startsWith("tq:a:"))).findFirst().orElseThrow();
    }

    static String data(Sent s, String label) { return s.buttons().stream().filter(b -> b.text().equals(label)).findFirst().orElseThrow().callbackData(); }

    /** Dita's answer: a labelled "[trial]" status, or a validated answer (sources, no footer, no buttons). */
    static boolean isReply(Sent s) { return s.buttons().isEmpty() && (s.text().contains("[trial] ") || s.text().contains("\nSources:")); }
    Sent nextReply(int seen) throws InterruptedException {
        await(() -> sent.stream().skip(seen).anyMatch(TelegramMixedConversationTrialTest::isReply), "Dita's reply");
        return sent.stream().skip(seen).filter(TelegramMixedConversationTrialTest::isReply).findFirst().orElseThrow();
    }

    Path runDir() throws Exception { try (Stream<Path> s = Files.list(evidence)) { return s.filter(Files::isDirectory).findFirst().orElseThrow(); } }

    /** The exact request of call {@code callId} (written before approval; nothing sent yet). */
    JsonNode request(String callId) throws Exception { return JSON.readTree(Files.readString(runDir().resolve(callId).resolve("request.json"))); }

    String latestCall(String kind) throws Exception {
        try (Stream<Path> s = Files.list(runDir())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("call-") && n.endsWith(kind)).sorted().reduce((a, b) -> b).orElseThrow();
        }
    }

    /** Extraction request → (targets, context) message ids. */
    static List<Set<String>> targetsAndContext(JsonNode req) throws Exception {
        String user = req.path("messages").get(1).path("content").asText();
        JsonNode msgs = JSON.readTree(user.substring(user.indexOf('['), user.lastIndexOf(']') + 1));
        Set<String> t = new LinkedHashSet<>(), c = new LinkedHashSet<>();
        for (JsonNode m : msgs) (m.path("extractable").asBoolean() ? t : c).add(m.path("id").asText());
        return List.of(t, c);
    }

    void setUpSession() throws Exception {
        boot();
        await(() -> sent.stream().anyMatch(s -> s.text().startsWith("Dita synthetic trial: session setup")), "the setup screen");
        Sent setup = sent.get(0);
        assertTrue(setup.text().contains(Long.toString(CHAT)) && setup.text().contains(Long.toString(SENDER)) && setup.text().contains("fictional"), setup.text());
        assertEquals(List.of("Confirm synthetic session", "Cancel"), setup.buttons().stream().map(TelegramGateway.Button::text).toList());
        say(new M(40, "A message before the session is confirmed.", 0));
        say(new M(41, "/syn A declared statement before the session is confirmed.", 0));   // M2: /syn obeys the gate too
        assertTrue(store().live(new GroupScope(G)).isEmpty(), "nothing is stored before confirmation");
        press(111L, data(setup, "Confirm synthetic session"));                          // not the authorized sender
        assertFalse(ctx.getBean(app.dita.app.trial.SyntheticTrialController.class).sessionConfirmed());
        press(SENDER, data(setup, "Confirm synthetic session"));
        assertTrue(ctx.getBean(app.dita.app.trial.SyntheticTrialController.class).sessionConfirmed());
        assertTrue(Files.isRegularFile(runDir().resolve("session.json")));
    }

    MessageStore store() { return ctx.getBean(MessageStore.class); }

    @Test void mixedConversationToRelevantCompactContextToValidatedAnswerWithButtonApprovals() throws Exception {
        assumeTrue(Files.isRegularFile(V2.resolve("q1/synthesis-recording.json")) && Files.isRegularFile(V2.resolve("q2/synthesis-recording.json")),
                "saved synthesis responses absent: NOT EXECUTED");
        setUpSession();
        int seen = sent.size();
        for (M m : MIXED) say(m);
        assertEquals(seen, sent.size(), "ingestion is silent");
        assertEquals(MIXED.size(), store().live(new GroupScope(G)).size());
        assertEquals(id(66), store().find(new GroupScope(G), id(76)).orElseThrow().replyToId(), "the reply keeps its link");
        assertTrue(museBodies.isEmpty());

        // ---- /ask (no /extract): extraction approvals, each for a bounded batch of RELEVANT messages only
        synthesisByQuestion.put("Is Dr. Cedar recommended for babies?", savedSynthesis("q1", 52, 61));
        synthesisByQuestion.put("Is Dr. Cedar recommended for teenagers?", savedSynthesis("q2", 52, 61));
        JsonNode ask1 = cmd("/ask Is Dr. Cedar recommended for babies?");
        Set<String> targets = new LinkedHashSet<>(), context = new LinkedHashSet<>();
        List<Integer> sizes = new ArrayList<>();
        while (true) {
            Sent appr = nextApproval(seen);
            seen = sent.indexOf(appr) + 1;
            if (appr.text().startsWith("Approval needed: answer")) {               // stage 2 reached
                JsonNode synth = request(latestCall("synthesis"));
                String user = synth.path("messages").get(1).path("content").asText();
                JsonNode u = JSON.readTree(user);
                assertEquals("Is Dr. Cedar recommended for babies?", u.path("question").asText());
                Set<String> evidenceIds = new LinkedHashSet<>();
                for (JsonNode e : u.path("evidence")) evidenceIds.add(e.path("id").asText());
                System.out.println("[TASK-054 mixed] synthesis request bytes=" + synth.toString().length() + " evidence=" + evidenceIds);
                for (long x : UNRELATED) assertFalse(user.contains(id(x)), "unrelated message in the synthesis request: " + x);
                assertTrue(evidenceIds.contains(id(52)), "the babies statement is evidence: " + evidenceIds);
                assertTrue(evidenceIds.stream().allMatch(i -> DOCTOR.stream().anyMatch(d -> id(d).equals(i))), "compact doctor evidence only: " + evidenceIds);
                press(SENDER, data(appr, "Approve"));
                break;
            }
            assertTrue(appr.text().startsWith("Approval needed: prepare knowledge (extraction)"), appr.text());
            assertFalse(appr.text().toLowerCase().contains("approve 0"), "no typed phrase");
            JsonNode req = request(latestCall("extraction"));
            var tc = targetsAndContext(req);
            sizes.add(req.toString().length());
            assertTrue(tc.get(0).size() <= 4, "bounded batch: " + tc.get(0));
            targets.addAll(tc.get(0)); context.addAll(tc.get(1));
            press(SENDER, data(appr, "Approve"));
        }
        System.out.println("[TASK-054 mixed] extraction targets=" + targets + " context=" + context + " request_bytes=" + sizes);
        for (long d : List.of(52L, 59L, 61L, 63L, 66L)) assertTrue(targets.contains(id(d)), "doctor message selected: " + d);
        assertTrue(targets.contains(id(76)) || context.contains(id(76)), "the reply reached through its link is included");
        if (targets.contains(id(76))) assertTrue(targets.contains(id(66)) || context.contains(id(66)), "its parent is there to interpret 'he'");
        for (long x : UNRELATED) assertFalse(targets.contains(id(x)) || context.contains(id(x)), "unrelated message sent for extraction: " + x);
        Sent r1 = nextReply(seen);
        System.out.println("[TASK-054 mixed] reply=" + r1.text().replace("\n", " | "));
        assertTrue(!r1.text().contains("[trial]"), r1.text());
        assertTrue(r1.text().contains("[" + id(52) + "]") && !r1.text().contains(id(61)), r1.text());
        // unrelated messages are still pending for other questions
        var pending = ctx.getBean(ExtractionService.class).pendingIds(new GroupScope(G));
        for (long x : UNRELATED) assertTrue(pending.contains(id(x)), "still pending: " + x);
        int extractionCalls = (int) museBodies.stream().filter(b -> !b.contains("You write the reply of Dita")).count();

        // ---- duplicate delivery of the same /ask: nothing new
        int before = sent.size();
        adapter().handle(ask1);
        Thread.sleep(200);
        assertEquals(before, sent.size());

        // ---- teenagers: knowledge is current → NO extraction, one synthesis approval
        seen = sent.size();
        cmd("/ask Is Dr. Cedar recommended for teenagers?");
        Sent appr = nextApproval(seen);
        assertTrue(appr.text().startsWith("Approval needed: answer"), "no re-extraction of prepared messages: " + appr.text());
        press(SENDER, data(appr, "Approve"));
        Sent r2 = nextReply(sent.indexOf(appr) + 1);
        assertTrue(!r2.text().contains("[trial]") && r2.text().contains("[" + id(61) + "]") && !r2.text().contains(id(52)), r2.text());
        assertEquals(extractionCalls, museBodies.stream().filter(b -> !b.contains("You write the reply of Dita")).count());

        // ---- a stale (already used) approval button does nothing
        press(SENDER, data(appr, "Approve"));
        assertTrue(callbackAnswers.get(callbackAnswers.size() - 1).contains("no longer open"), callbackAnswers.toString());

        // ---- different topic: only picnic messages are proposed; Cancel → nothing sent, honest stop, no synthesis prompt
        seen = sent.size();
        int bodies = museBodies.size();
        cmd("/ask When does the picnic start?");
        Sent pic = nextApproval(seen);
        assertTrue(pic.text().startsWith("Approval needed: prepare knowledge"), pic.text());
        var ptc = targetsAndContext(request(latestCall("extraction")));
        System.out.println("[TASK-054 mixed] picnic targets=" + ptc.get(0) + " context=" + ptc.get(1));
        assertTrue(ptc.get(0).contains(id(60)) && ptc.get(0).contains(id(67)), ptc.toString());
        for (long d : DOCTOR) assertFalse(ptc.get(0).contains(id(d)) || ptc.get(1).contains(id(d)), "doctor message in a picnic request: " + d);
        press(SENDER, data(pic, "Cancel"));
        Sent stop = nextReply(sent.indexOf(pic) + 1);
        assertTrue(stop.text().contains("[trial] No answer: EXTRACTION_FAILED.") && stop.text().contains("cancelled; nothing was sent"), stop.text());
        assertEquals(bodies, museBodies.size(), "a cancelled request is never sent");
        // ---- ask again: the cancelled picnic messages are prepared from the stored messages with a NEW approval
        seen = sent.size();
        cmd("/ask When does the picnic start?");
        Sent pic2 = nextApproval(seen);
        assertTrue(pic2.text().startsWith("Approval needed: prepare knowledge"), pic2.text());
        assertNotEquals(data(pic, "Approve"), data(pic2, "Approve"), "a new approval, never the old one");
        press(SENDER, data(pic2, "Approve"));
        Sent synthPic = nextApproval(sent.indexOf(pic2) + 1);
        press(SENDER, data(synthPic, "Cancel"));                                       // synthesis cancelled → labelled fallback
        Sent r3 = nextReply(sent.indexOf(synthPic) + 1);
        assertTrue(r3.text().contains("[trial] Not a Muse answer"), r3.text());
        for (long d : DOCTOR) assertFalse(r3.text().contains(id(d)), r3.text());

        // ---- edit of the babies statement: withdrawn, never re-extracted, never cited
        var edit = JSON.createObjectNode().put("update_id", ++updateId);
        var em = edit.putObject("edited_message").put("message_id", 52).put("date", now()).put("edit_date", now()).put("text", "Dr. Cedar is fine for everyone.");
        em.putObject("chat").put("id", CHAT).put("type", "supergroup");
        em.putObject("from").put("id", SENDER).put("is_bot", false);
        adapter().handle(edit);
        int ex = (int) museBodies.stream().filter(b -> !b.contains("You write the reply of Dita")).count();
        seen = sent.size();
        cmd("/ask Is Dr. Cedar recommended for babies?");
        Sent a4 = nextApproval(seen);
        assertTrue(a4.text().startsWith("Approval needed: answer"), "an edit never triggers re-extraction: " + a4.text());
        assertFalse(request(latestCall("synthesis")).toString().contains("fine for everyone"), "edited text never reaches Muse");
        press(SENDER, data(a4, "Cancel"));
        Sent r4 = nextReply(sent.indexOf(a4) + 1);
        assertFalse(r4.text().contains("[" + id(52) + "]"), r4.text());
        assertEquals(ex, museBodies.stream().filter(b -> !b.contains("You write the reply of Dita")).count());

        assertCleanEvidence();
    }

    @Test void recoveryFromTheSavedNineteenMessageFailurePreparesOnlyRelevantMessagesWithNewApprovals() throws Exception {
        Path req = FAILED_RUN.resolve("call-001-extraction/request.json"), resp = FAILED_RUN.resolve("call-001-extraction/response.json");
        assumeTrue(Files.isRegularFile(req) && Files.isRegularFile(resp) && Files.isRegularFile(V2.resolve("q1/synthesis-recording.json")),
                "saved failed run absent: NOT EXECUTED");
        byte[] savedBefore = digest(FAILED_RUN);
        // the saved conversation (19 messages, the exact texts and ids of the failed request)
        JsonNode saved = JSON.readTree(Files.readString(req));
        String savedUser = saved.path("messages").get(1).path("content").asText();
        JsonNode savedMsgs = JSON.readTree(savedUser.substring(savedUser.indexOf('['), savedUser.lastIndexOf(']') + 1));
        assertEquals(19, savedMsgs.size());
        assertEquals("length", JSON.readTree(Files.readString(resp)).path("choices").get(0).path("finish_reason").asText());
        setUpSession();
        for (JsonNode m : savedMsgs) say(new M(Long.parseLong(m.path("id").asText().substring(G.length() + 1)), m.path("text").asText(), 0));

        // reproduce the failure with the old all-pending preparation (/extract), answered by the SAVED response
        failNextExtractionWith = Files.readString(resp);
        int seen = sent.size();
        cmd("/extract");
        Sent old = nextApproval(seen);
        var oldTc = targetsAndContext(request(latestCall("extraction")));
        assertEquals(19, oldTc.get(0).size(), "the old behaviour: every pending message");
        press(SENDER, data(old, "Approve"));
        await(() -> sent.stream().anyMatch(s -> s.text().contains("FAILED")), "the failure reply");
        var failed = ctx.getBean(ExtractionQueue.class).batches(new GroupScope(G), ExtractionBatch.Status.FAILED);
        assertEquals(1, failed.size());
        String oldRef = failed.get(0).approvalRef();
        String failedBatch = failed.get(0).id();

        // /ask: only the doctor messages of the failed batch are prepared, as subsets, each with a NEW approval
        synthesisByQuestion.put("Is Dr. Cedar recommended for babies?", savedSynthesis("q1", 52, 61));
        seen = sent.size();
        cmd("/ask Is Dr. Cedar recommended for babies?");
        Set<String> targets = new LinkedHashSet<>(), context = new LinkedHashSet<>();
        while (true) {
            Sent appr = nextApproval(seen);
            seen = sent.indexOf(appr) + 1;
            if (appr.text().startsWith("Approval needed: answer")) { press(SENDER, data(appr, "Approve")); break; }
            var tc = targetsAndContext(request(latestCall("extraction")));
            assertTrue(tc.get(0).size() <= 4, tc.toString());
            targets.addAll(tc.get(0)); context.addAll(tc.get(1));
            press(SENDER, data(appr, "Approve"));
        }
        System.out.println("[TASK-054 recovery] targets=" + targets + " context=" + context);
        Set<String> doctorIds = new HashSet<>();
        for (JsonNode m : savedMsgs) if (m.path("text").asText().contains("Cedar")) doctorIds.add(m.path("id").asText());
        assertEquals(doctorIds, targets, "exactly the doctor messages of the failed batch");
        for (JsonNode m : savedMsgs) if (!doctorIds.contains(m.path("id").asText()))
            assertFalse(context.contains(m.path("id").asText()), "unrelated context: " + m.path("text").asText());
        Sent r = nextReply(seen);
        assertTrue(!r.text().contains("[trial]") && r.text().contains("[" + id(52) + "]"), r.text());
        var after = ctx.getBean(ExtractionQueue.class).batch(new GroupScope(G), failedBatch).orElseThrow();
        assertEquals(ExtractionBatch.Status.FAILED, after.status(), "its unrelated messages were never prepared");
        assertEquals(ExtractionService.PARTIALLY_PREPARED, after.lastError());
        assertNotEquals(oldRef, after.approvalRef(), "a new approval reference, never the old one");
        assertArrayEquals(savedBefore, digest(FAILED_RUN), "the saved failed request, response and attempt marker are unchanged");
        assertCleanEvidence();
    }

    static byte[] digest(Path dir) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        try (Stream<Path> s = Files.walk(dir)) { for (Path f : s.filter(Files::isRegularFile).sorted().toList()) md.update(Files.readAllBytes(f)); }
        return md.digest();
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
                JsonNode ap = JSON.readTree(Files.readString(d.resolve("approval.json")));
                boolean approved = ap.path("decision").asText().equals("APPROVED");
                assertEquals(approved, Files.exists(d.resolve("attempted")), "attempt marker only for approved calls: " + d);
                assertEquals("telegram-button", ap.path("approval_mode").asText(), d.toString());
                if (approved) assertEquals(SENDER, ap.path("pressed_by").asLong(), "who approved is recorded: " + d);
                assertFalse(ap.has("required_phrase"), "no typed phrase in the button flow");
            }
        }
    }
}
