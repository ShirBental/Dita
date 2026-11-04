package app.dita.app;

import app.dita.app.config.KnowledgeConfiguration;
import app.dita.app.trial.CallApprover;
import app.dita.core.QueryService;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.model.GroupScope;
import app.dita.core.model.SourceKind;
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
 * TASK-054 OFFLINE test of the controlled Telegram synthetic trial through the PRODUCTION Spring context: real adapter,
 * real extraction, retrieval, v2 synthesis, validator and approval gate. FAKES: the Telegram gateway (no network), the
 * operator approver (scripted decisions) and the Muse transport. Muse replies are the SAVED live responses
 * (build/controlled-muse-cedar, build/controlled-synthesis-cedar-v2/postgres) with ONLY the message ids rewritten to the
 * Telegram trial ids; they are recorded data, not new Muse output, and this is not a live Telegram run.
 */
class TelegramSyntheticTrialTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000054L, SENDER = 1001L;
    static final String FAKE_KEY = "synthetic-fake-model-key-not-real", FAKE_TOKEN = "123456789:AAfakeTokenForOfflineTestsOnly_0123456789";
    static final Path V2 = SynthesisTrialHarness.ROOT.resolve("build/controlled-synthesis-cedar-v2/postgres");
    static final String G = "tgt" + Math.abs(CHAT);

    final List<String[]> sent = new CopyOnWriteArrayList<>();                 // {chatId, text}
    final List<CallApprover.PendingCall> asked = new CopyOnWriteArrayList<>();
    final Deque<CallApprover.Decision> decisions = new ArrayDeque<>();
    final List<String> museBodies = new CopyOnWriteArrayList<>();
    final Map<String, MuseTransport.Reply> synthesisReplies = new HashMap<>();
    String extractionReply;                                                   // null = the extraction call fails (HTTP 500)
    app.dita.app.trial.TrialReadiness readiness;                              // null = production readiness check
    Path evidence;
    ConfigurableApplicationContext ctx;
    long updateId = 1000;

    @AfterEach void close() { if (ctx != null) ctx.close(); app.dita.muse.MuseCallCapTestAccess.reset(); }

    static boolean savedResponsesPresent() {
        return SynthesisTrialHarness.extractionArtifactsPresent() && Files.isRegularFile(V2.resolve("q1/synthesis-recording.json"))
                && Files.isRegularFile(V2.resolve("q2/synthesis-recording.json"));
    }

    static String id(long mid) { return G + "m" + mid; }

    /** Saved live body with the fixture ids m1/m2 rewritten to Telegram trial ids (escaped inside the content string). */
    static String rewrite(String body, long mid1, long mid2) {
        return body.replace("\\\"m1\\\"", "\\\"" + id(mid1) + "\\\"").replace("\\\"m2\\\"", "\\\"" + id(mid2) + "\\\"")
                .replace("[m1]", "[" + id(mid1) + "]").replace("[m2]", "[" + id(mid2) + "]");
    }

    void boot(Map<String, String> extra) throws Exception {
        evidence = Files.createDirectories(Path.of("target")).resolve("telegram-trial-test-" + UUID.randomUUID().toString().substring(0, 8));
        TelegramGateway gateway = new TelegramGateway() {
            long next = 5000;
            public List<JsonNode> getUpdates(long offset, int t, int limit) throws Exception { Thread.sleep(20); return List.of(); }
            public long sendMessage(long chatId, String text, Long replyTo, List<Button> buttons) { sent.add(new String[]{Long.toString(chatId), text}); return next++; }
            public void answerCallbackQuery(String id, String text) { }
            public Set<Long> chatAdministrators(long chatId) { return Set.of(); }
        };
        MuseTransport transport = (url, headers, body, timeout) -> {
            assertEquals("Bearer " + FAKE_KEY, headers.get("Authorization"));
            museBodies.add(body);
            if (!body.contains("You write the reply of Dita")) return extractionReply == null
                    ? new MuseTransport.Reply(500, "{\"error\":{\"type\":\"server_error\",\"message\":\"synthetic\"}}", Map.of())
                    : new MuseTransport.Reply(200, extractionReply, Map.of());
            for (var e : synthesisReplies.entrySet()) if (body.contains(e.getKey())) return e.getValue();
            return new MuseTransport.Reply(500, "{\"error\":{\"type\":\"server_error\",\"message\":\"synthetic\"}}", Map.of());
        };
        CallApprover approver = c -> { asked.add(c); return decisions.isEmpty() ? CallApprover.Decision.REFUSED : decisions.removeFirst(); };
        Map<String, String> env = Map.of("TELEGRAM_BOT_TOKEN", FAKE_TOKEN, "DITA_CALLBACK_SECRET", "x".repeat(40), "MODEL_API_KEY", FAKE_KEY);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dita.storage.backend", "IN_MEMORY");
        p.put("dita.telegram.enabled", "true"); p.put("dita.telegram.mode", "LIVE"); p.put("dita.telegram.bot-username", "ExampleDitaBot");
        p.put("dita.telegram.allowed-chat-ids", Long.toString(CHAT)); p.put("dita.telegram.allowed-author-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.enabled", "true"); p.put("dita.telegram.synthetic-trial.approver-ids", Long.toString(SENDER));
        p.put("dita.telegram.synthetic-trial.evidence-dir", evidence.toString());
        p.put("dita.muse.enabled", "true"); p.put("dita.muse.transport", "HTTP"); p.put("dita.muse.max-output-tokens", "4096");
        p.put("dita.muse.max-calls-per-process", "20"); p.put("dita.muse.timeout", "60s");
        p.put("dita.muse.response-format", "PROMPT_ONLY"); p.put("dita.muse.reasoning-effort", "LOW");
        p.put("dita.memory.answers.synthesis.enabled", "true"); p.put("dita.memory.answers.synthesis.allow-live", "true");
        p.put("dita.extraction.worker-enabled", "false"); p.put("dita.coordination.enabled", "false");
        p.putAll(extra);
        ctx = new SpringApplicationBuilder(DitaApplication.class).initializers(c -> {
            c.getBeanFactory().registerSingleton("ditaEnv", (java.util.function.Function<String, String>) env::get);
            c.getBeanFactory().registerSingleton("telegramGateway", gateway);
            c.getBeanFactory().registerSingleton("museTransport", transport);
            c.getBeanFactory().registerSingleton("callApprover", approver);
            c.getBeanFactory().registerSingleton("trialWorker", (java.util.concurrent.Executor) Runnable::run);   // scripted: inline
            if (readiness != null) c.getBeanFactory().registerSingleton("trialReadiness", readiness);
        }).run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    TelegramAdapter adapter() { return ctx.getBean(TelegramAdapter.class); }
    MessageStore store() { return ctx.getBean(MessageStore.class); }

    JsonNode update(long chat, long from, long mid, String text, long date) {
        var u = JSON.createObjectNode().put("update_id", ++updateId);
        var m = u.putObject("message").put("message_id", mid).put("date", date).put("text", text);
        m.putObject("chat").put("id", chat).put("type", "supergroup");
        m.putObject("from").put("id", from).put("is_bot", false).put("first_name", "Synthetic");
        return u;
    }
    long now() { return Instant.now().getEpochSecond() + 1; }
    String lastReply() { return sent.get(sent.size() - 1)[1]; }

    List<JsonNode> evidenceFiles(String prefix) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(Files.list(evidence).findFirst().orElseThrow())) {
            for (Path f : s.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList()) out.add(JSON.readTree(Files.readString(f)));
        }
        return out;
    }

    @Test void questionToValidatedMuseReplyInTheGroupWithGatesAndFallbacks() throws Exception {
        assumeTrue(savedResponsesPresent(), "saved live responses absent: NOT EXECUTED");
        long m1 = 101, m2 = 102;
        extractionReply = rewrite(Files.readString(SynthesisTrialHarness.EXTRACTION_DIR.resolve("recording.json")), m1, m2);
        synthesisReplies.put("Is Dr. Cedar recommended for babies?", new MuseTransport.Reply(200, rewrite(Files.readString(V2.resolve("q1/synthesis-recording.json")), m1, m2), Map.of()));
        synthesisReplies.put("Is Dr. Cedar recommended for teenagers?", new MuseTransport.Reply(200, rewrite(Files.readString(V2.resolve("q2/synthesis-recording.json")), m1, m2), Map.of()));
        boot(Map.of());
        assertTrue(ctx.getBeansOfType(app.dita.app.config.ExtractionWorker.class).isEmpty(), "no background extraction worker");
        var a = adapter();

        // --- rejected before anything is stored or answered
        a.handle(update(CHAT, SENDER, 90, "/syn Dr. Cedar is wonderful.", now() - 3600));        // stale (before start)
        a.handle(update(-100999L, SENDER, 91, "/syn Dr. Cedar is wonderful.", now()));          // other group
        a.handle(update(CHAT, 111L, 92, "/syn Dr. Cedar is wonderful.", now()));                // other sender
        a.handle(update(CHAT, SENDER, 93, "a normal chat message that was not declared synthetic", now()));   // undeclared
        a.handle(update(CHAT, 111L, 94, "/ask Is Dr. Cedar recommended?", now()));               // other sender asks
        var fwd = update(CHAT, SENDER, 95, "/syn Dr. Cedar is wonderful.", now());              // forwarded third-party text
        ((com.fasterxml.jackson.databind.node.ObjectNode) fwd.get("message")).putObject("forward_origin").put("type", "user");
        a.handle(fwd);
        var viaBot = update(CHAT, SENDER, 96, "/syn Dr. Cedar is wonderful.", now());           // inline-bot result
        ((com.fasterxml.jackson.databind.node.ObjectNode) viaBot.get("message")).putObject("via_bot").put("id", 42).put("is_bot", true);
        a.handle(viaBot);
        assertEquals(1, a.staleUpdateCount());
        assertEquals(2, a.rejectedAuthorCount());
        assertEquals(3, a.ignoredUndeclaredCount());
        assertTrue(sent.isEmpty(), "no reply to rejected updates");
        assertTrue(store().live(new GroupScope(G)).isEmpty(), "nothing stored");

        // --- declared synthetic statements, same author
        a.handle(update(CHAT, SENDER, m1, "/syn Dr. Cedar on Maple Street is excellent for babies.", now()));
        a.handle(update(CHAT, SENDER, m2, "/syn Dr. Cedar is not recommended for teenagers.", now()));
        var stored = store().live(new GroupScope(G));
        assertEquals(2, stored.size());
        assertTrue(stored.stream().allMatch(m -> m.sourceKind() == SourceKind.SYNTHETIC && m.authorId().equals(Long.toString(SENDER))));
        assertTrue(lastReply().startsWith("Stored as a synthetic trial statement"));

        // --- /extract: one approved extraction call (exact request reviewed: both statements, nothing else)
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 150, "/extract", now()));
        assertEquals(1, asked.size());
        assertEquals(1, museBodies.size());
        var ex = asked.get(0);
        assertEquals(app.dita.app.trial.TrialContext.Kind.EXTRACTION, ex.kind());
        assertTrue(ex.correlation().startsWith("telegram chat " + CHAT + " message 150 /extract"), ex.correlation());
        assertTrue(ex.summary().stream().anyMatch(l -> l.contains("excellent for babies")) && ex.summary().stream().anyMatch(l -> l.contains("teenagers")));
        assertFalse(museBodies.get(0).contains("not declared synthetic"), "undeclared text never reaches Muse");
        assertFalse(museBodies.get(0).contains(Long.toString(SENDER)), "author ids are pseudonymised");
        assertEquals("api.meta.ai", java.net.URI.create(ex.destination()).getHost());
        assertEquals(4096, ex.maxCompletionTokens());
        assertTrue(ex.maxCostUsd() > 0 && ex.pricingBasis().contains("2026-10-28"));
        assertTrue(lastReply().contains("done, 2 statement(s)"), lastReply());
        assertEquals(2, store().knowledge(new GroupScope(G)).size());

        // --- /ask babies: approved → validated Muse answer citing m1 only, replied in the group
        decisions.add(CallApprover.Decision.APPROVED);
        var babies = update(CHAT, SENDER, 201, "/ask Is Dr. Cedar recommended for babies?", now());
        a.handle(babies);
        assertEquals(2, asked.size());
        assertEquals(app.dita.app.trial.TrialContext.Kind.SYNTHESIS, asked.get(1).kind());
        assertTrue(asked.get(1).summary().get(0).contains("Is Dr. Cedar recommended for babies?"));
        String r1 = lastReply();
        assertTrue(!r1.contains("[trial]"), r1);
        assertTrue(r1.contains("[" + id(m1) + "]") && !r1.contains(id(m2)) && !r1.toLowerCase().contains("teen"), r1);
        assertTrue(r1.contains("Sources:"), r1);
        var ask1 = evidenceFiles("ask-").get(0);
        assertTrue(ask1.path("synthesis_succeeded").asBoolean());
        assertFalse(ask1.path("deterministic_fallback").asBoolean());
        assertEquals("[\"" + id(m1) + "\"]", ask1.path("final_sources").toString());
        assertEquals(1, ask1.path("calls").size());
        assertEquals(200, ask1.path("http").get(0).path("http").path("http_status").asInt());
        assertTrue(ask1.path("http").get(0).path("http").has("cost_usd"));

        // --- the same update delivered twice: no second call, no second reply
        int replies = sent.size();
        a.handle(babies);
        assertEquals(replies, sent.size());
        assertEquals(2, asked.size());

        // --- /ask teenagers: the captured "stated" answer is now accepted (validator fix), citing m2 only
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 202, "/ask Is Dr. Cedar recommended for teenagers?", now()));
        String r2 = lastReply();
        assertTrue(!r2.contains("[trial]"), r2);
        assertTrue(r2.contains("[" + id(m2) + "]") && !r2.contains(id(m1)) && !r2.toLowerCase().contains("bab"), r2);

        // --- /ask general: operator REFUSES → nothing sent, deterministic reply clearly marked as not Muse
        int bodies = museBodies.size();
        decisions.add(CallApprover.Decision.REFUSED);
        a.handle(update(CHAT, SENDER, 203, "/ask Is Dr. Cedar recommended?", now()));
        assertEquals(bodies, museBodies.size(), "refused call not sent");
        assertTrue(lastReply().contains("[trial] Not a Muse answer"), lastReply());
        var ask3 = evidenceFiles("ask-").get(2);
        assertFalse(ask3.path("synthesis_succeeded").asBoolean());
        assertTrue(ask3.path("deterministic_fallback").asBoolean());
        assertEquals("FALLBACK_ERROR", ask3.path("synthesis_outcomes").get(0).asText());
        assertEquals("REFUSED", ask3.path("http").get(0).path("approval").asText());

        // --- /ask unsupported population: approved but the call FAILS (HTTP 500) → one attempt, no retry, fallback marked
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 204, "/ask Is Dr. Cedar recommended for adults?", now()));
        assertEquals(bodies + 1, museBodies.size(), "exactly one attempt");
        assertTrue(lastReply().contains("[trial] Not a Muse answer"), lastReply());
        var ask4 = evidenceFiles("ask-").get(3);
        assertEquals(500, ask4.path("http").get(0).path("http").path("http_status").asInt());
        assertEquals(1, ask4.path("calls_attempted").asInt());

        // --- a Muse call outside any Telegram request is refused without asking anyone and without network
        int before = asked.size(); bodies = museBodies.size();
        var direct = ctx.getBean(QueryService.class).ask(new GroupScope(G), Long.toString(SENDER), "Is Dr. Cedar recommended for babies?");
        assertTrue(direct.found());
        assertEquals(before, asked.size());
        assertEquals(bodies, museBodies.size());

        // --- /extract with nothing pending: no call, no approval prompt
        a.handle(update(CHAT, SENDER, 205, "/extract", now()));
        assertEquals("Nothing is waiting for extraction.", lastReply());
        assertEquals(before, asked.size());

        // --- /forget a statement (reply to its /syn message): deleted, and never cited again
        var forget = update(CHAT, SENDER, 206, "/forget", now());
        ((com.fasterxml.jackson.databind.node.ObjectNode) forget.path("message")).putObject("reply_to_message").put("message_id", m1);
        a.handle(forget);
        assertTrue(lastReply().startsWith("Done."), lastReply());
        decisions.add(CallApprover.Decision.REFUSED);
        a.handle(update(CHAT, SENDER, 207, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertFalse(lastReply().contains("[" + id(m1) + "]"), lastReply());

        // --- evidence never contains a credential; every call has request + approval records
        try (Stream<Path> files = Files.walk(evidence)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String c = Files.readString(f);
                assertFalse(c.contains(FAKE_KEY) || c.contains(FAKE_TOKEN), "credential in " + f);
            }
        }
        try (Stream<Path> dirs = Files.list(Files.list(evidence).findFirst().orElseThrow())) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                assertTrue(Files.isRegularFile(d.resolve("request.json")) && Files.isRegularFile(d.resolve("approval.json")), d.toString());
                boolean approved = JSON.readTree(Files.readString(d.resolve("approval.json"))).path("decision").asText().equals("APPROVED");
                assertEquals(approved, Files.exists(d.resolve("attempted")), "attempt marker only for approved calls: " + d);
            }
        }
        System.out.println("[TASK-054 telegram-trial offline] approvals=" + asked.size() + " muse_calls_sent=" + museBodies.size()
                + " replies=" + sent.size() + " evidence=" + evidence);
    }

    @Test void refusedExtractionIsNotRetriedOrReprocessedAndNoWorkerMayTakeTrialBatches() throws Exception {
        assumeTrue(savedResponsesPresent(), "saved live responses absent: NOT EXECUTED");
        extractionReply = rewrite(Files.readString(SynthesisTrialHarness.EXTRACTION_DIR.resolve("recording.json")), 101, 102);
        boot(Map.of());
        var a = adapter();
        a.handle(update(CHAT, SENDER, 101, "/syn Dr. Cedar on Maple Street is excellent for babies.", now()));
        a.handle(update(CHAT, 111L, 120, "/extract", now()));                        // not an approver (not even allow-listed)
        assertTrue(asked.isEmpty());
        decisions.add(CallApprover.Decision.TIMEOUT);
        a.handle(update(CHAT, SENDER, 121, "/extract", now()));
        assertEquals(1, asked.size());
        assertTrue(museBodies.isEmpty(), "nothing sent without approval");
        assertTrue(lastReply().contains("FAILED") && lastReply().contains("Not retried"), lastReply());
        a.handle(update(CHAT, SENDER, 122, "/extract", now()));
        assertEquals("Nothing is waiting for extraction.", lastReply(), "a failed batch is never reprocessed automatically");
        assertEquals(1, asked.size());
        var failed = ctx.getBean(ExtractionQueue.class).batches(new GroupScope(G), ExtractionBatch.Status.FAILED);
        assertEquals(1, failed.size());
        assertTrue(failed.get(0).approvalRef().startsWith(TelegramAdapter.TRIAL_APPROVAL_PREFIX));
        assertTrue(KnowledgeConfiguration.trialBatch(failed.get(0)));
        // no worker of ANY process configuration may take a trial batch (shared database)
        var props = ctx.getBean(app.dita.app.config.DitaProperties.class);
        assertFalse(KnowledgeConfiguration.eligibility(props, false).test(failed.get(0)));
        assertFalse(KnowledgeConfiguration.eligibility(props, true).test(failed.get(0)));
    }

    @Test void sessionDeclarationAcceptsPlainMessagesWithoutBroadeningAccessOrCallingMuse() throws Exception {
        boot(Map.of("dita.telegram.synthetic-trial.session-declaration", "ALL_NEW_MESSAGES_SYNTHETIC"));
        var a = adapter();
        a.handle(update(CHAT, SENDER, 201, "Dr. Cedar is excellent for babies.", now()));
        a.handle(update(CHAT, SENDER, 201, "Dr. Cedar is excellent for babies.", now()));
        assertTrue(sent.isEmpty(), "Plain trial statements are ingested silently, including duplicates");
        a.handle(update(CHAT, SENDER, 202, "Old fictional statement", now()-3600));
        a.handle(update(CHAT, 111L, 203, "Unauthorized fictional statement", now()));
        a.handle(update(-100999L, SENDER, 204, "Other group", now()));
        var fwd = update(CHAT, SENDER, 205, "Forwarded text", now());
        ((com.fasterxml.jackson.databind.node.ObjectNode) fwd.path("message")).put("forward_date", now());
        a.handle(fwd);
        a.handle(update(CHAT, SENDER, 206, "/unknown", now()));
        var messages = store().live(new GroupScope(G));
        assertEquals(1, messages.size());
        assertEquals("Dr. Cedar is excellent for babies.", messages.get(0).text());
        assertEquals(SourceKind.SYNTHETIC, messages.get(0).sourceKind());
        assertTrue(asked.isEmpty()); assertTrue(museBodies.isEmpty());
    }

    @Test void explicitlyDeclaredSecondSyntheticAccountIsIngestedSilently() throws Exception {
        boot(Map.of("dita.telegram.allowed-author-ids", SENDER+",1002",
            "dita.telegram.synthetic-trial.session-author-ids", SENDER+",1002",
            "dita.telegram.synthetic-trial.session-declaration", "ALL_NEW_MESSAGES_SYNTHETIC"));
        adapter().handle(update(CHAT, 1002L, 211, "The fictional picnic starts at noon.", now()));
        assertEquals(1, store().live(new GroupScope(G)).size());
        assertEquals("1002", store().live(new GroupScope(G)).get(0).authorId());
        assertTrue(sent.isEmpty()); assertTrue(museBodies.isEmpty());
    }

    @Test void invalidSessionDeclarationIsRefused() {
        assertThrows(Exception.class, () -> boot(Map.of("dita.telegram.synthetic-trial.session-declaration", "yes")));
    }

    @Test void onlyTheTrialApproverMayAskBecauseTheQuestionReachesMuse() throws Exception {
        boot(Map.of("dita.telegram.allowed-author-ids", SENDER + ",222"));
        var a = adapter();
        a.handle(update(CHAT, 222L, 130, "/ask Is Dr. Cedar recommended for babies?", now()));   // allow-listed, not an approver
        assertEquals("In this synthetic trial only a configured trial approver can ask.", lastReply());
        assertTrue(asked.isEmpty(), "no approval prompt");
        assertTrue(museBodies.isEmpty(), "nothing sent");
    }

    // ---------------- TASK-054 orchestration: plain synthetic messages → /ask (no /extract) ----------------

    static final String BABIES = "Dr. Cedar on Maple Street is excellent for babies.", TEENS = "Dr. Cedar is not recommended for teenagers.";
    static final Map<String, String> DECLARED = Map.of("dita.telegram.synthetic-trial.session-declaration", "ALL_NEW_MESSAGES_SYNTHETIC");

    void cedarReplies(long m1, long m2) throws Exception {
        extractionReply = rewrite(Files.readString(SynthesisTrialHarness.EXTRACTION_DIR.resolve("recording.json")), m1, m2);
        synthesisReplies.put("Is Dr. Cedar recommended for babies?", new MuseTransport.Reply(200, rewrite(Files.readString(V2.resolve("q1/synthesis-recording.json")), m1, m2), Map.of()));
        synthesisReplies.put("Is Dr. Cedar recommended for teenagers?", new MuseTransport.Reply(200, rewrite(Files.readString(V2.resolve("q2/synthesis-recording.json")), m1, m2), Map.of()));
    }

    long kinds(app.dita.app.trial.TrialContext.Kind k) { return asked.stream().filter(c -> c.kind() == k).count(); }

    @Test void plainMessagesThenAskPreparesKnowledgeThenAnswersWithSeparateApprovals() throws Exception {
        assumeTrue(savedResponsesPresent(), "saved live responses absent: NOT EXECUTED");
        long m1 = 101, m2 = 102;
        cedarReplies(m1, m2);
        boot(DECLARED);
        var a = adapter();
        a.handle(update(CHAT, SENDER, m1, BABIES, now()));
        a.handle(update(CHAT, SENDER, m2, TEENS, now()));
        assertTrue(sent.isEmpty(), "ingestion is silent");
        assertTrue(asked.isEmpty() && museBodies.isEmpty(), "ingestion never calls Muse");

        // /ask babies, NO /extract: stage 1 extraction approval, then a SEPARATE stage 2 synthesis approval
        decisions.add(CallApprover.Decision.APPROVED); decisions.add(CallApprover.Decision.APPROVED);
        var babies = update(CHAT, SENDER, 201, "/ask Is Dr. Cedar recommended for babies?", now());
        a.handle(babies);
        assertEquals(2, asked.size());
        assertEquals(app.dita.app.trial.TrialContext.Kind.EXTRACTION, asked.get(0).kind());
        assertTrue(asked.get(0).correlation().contains("STEP 1 of 2"), asked.get(0).correlation());
        assertTrue(asked.get(0).summary().stream().anyMatch(l -> l.contains("babies")) && asked.get(0).summary().stream().anyMatch(l -> l.contains("teenagers")));
        assertEquals(app.dita.app.trial.TrialContext.Kind.SYNTHESIS, asked.get(1).kind());
        assertTrue(asked.get(1).correlation().contains("STEP 2 of 2"), asked.get(1).correlation());
        assertNotEquals(asked.get(0).approvalPhrase(), asked.get(1).approvalPhrase(), "one approval per call");
        assertEquals(2, museBodies.size());
        assertEquals(2, store().knowledge(new GroupScope(G)).size(), "structured knowledge prepared by /ask");
        assertEquals(1, sent.size(), "one reply: the answer");
        String r1 = lastReply();
        assertTrue(!r1.contains("[trial]"), r1);                       // model output + validation passed
        assertTrue(r1.contains("[" + id(m1) + "]") && !r1.contains(id(m2)) && !r1.toLowerCase().contains("teen"), r1);   // quality: focused
        var ask1 = evidenceFiles("ask-").get(0);
        assertEquals(1, ask1.path("preparation").size(), "one bounded extraction step");
        assertTrue(ask1.path("projections_ready").asBoolean());
        assertTrue(ask1.path("synthesis_succeeded").asBoolean());
        assertFalse(ask1.path("final_answer").path("knowledge_ids").isEmpty(), "answer built from knowledge, not raw messages");
        assertEquals("[\"" + id(m1) + "\"]", ask1.path("final_sources").toString());

        // duplicate delivery of the same /ask: no new prompt, call or reply
        a.handle(babies);
        assertEquals(2, asked.size()); assertEquals(2, museBodies.size()); assertEquals(1, sent.size());

        // /ask teenagers: knowledge is current → NO extraction call, only the synthesis approval
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 202, "/ask Is Dr. Cedar recommended for teenagers?", now()));
        assertEquals(1, kinds(app.dita.app.trial.TrialContext.Kind.EXTRACTION), "unchanged completed messages are never re-extracted");
        assertEquals(3, museBodies.size());
        String r2 = lastReply();
        assertTrue(!r2.contains("[trial]"), r2);
        assertTrue(r2.contains("[" + id(m2) + "]") && !r2.contains(id(m1)) && !r2.toLowerCase().contains("bab"), r2);

        // edit of the babies statement withdraws it: no re-extraction, and it is never cited again
        var edit = (com.fasterxml.jackson.databind.node.ObjectNode) update(CHAT, SENDER, m1, "Dr. Cedar is excellent for everyone.", now());
        var em = (com.fasterxml.jackson.databind.node.ObjectNode) edit.remove("message");
        em.put("edit_date", now());
        edit.set("edited_message", em);
        a.handle(edit);
        decisions.add(CallApprover.Decision.REFUSED);
        a.handle(update(CHAT, SENDER, 203, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(1, kinds(app.dita.app.trial.TrialContext.Kind.EXTRACTION), "an edit never revives or re-extracts");
        assertFalse(lastReply().contains("[" + id(m1) + "]"), lastReply());
        assertFalse(lastReply().contains("everyone"), lastReply());
        assertFalse(museBodies.stream().anyMatch(b -> b.contains("everyone")), "edited text never reaches Muse");

        // deletion (/forget) of the teenagers statement: never cited again, no extraction
        var forget = update(CHAT, SENDER, 204, "/forget", now());
        ((com.fasterxml.jackson.databind.node.ObjectNode) forget.path("message")).putObject("reply_to_message").put("message_id", m2);
        a.handle(forget);
        decisions.add(CallApprover.Decision.REFUSED);
        a.handle(update(CHAT, SENDER, 205, "/ask Is Dr. Cedar recommended for teenagers?", now()));
        assertEquals(1, kinds(app.dita.app.trial.TrialContext.Kind.EXTRACTION));
        assertFalse(lastReply().contains("[" + id(m2) + "]"), lastReply());
        assertCleanEvidence();
    }

    @Test void refusedOrFailedExtractionStopsTheAskBeforeSynthesisAndALaterAskNeedsANewApproval() throws Exception {
        assumeTrue(savedResponsesPresent(), "saved live responses absent: NOT EXECUTED");
        cedarReplies(101, 102);
        boot(DECLARED);
        var a = adapter();
        a.handle(update(CHAT, SENDER, 101, BABIES, now()));
        // explicit cancellation of the extraction request: nothing sent, no synthesis prompt, honest status, no answer
        decisions.add(CallApprover.Decision.REFUSED);
        a.handle(update(CHAT, SENDER, 201, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(1, asked.size()); assertEquals(0, kinds(app.dita.app.trial.TrialContext.Kind.SYNTHESIS));
        assertTrue(museBodies.isEmpty());
        assertTrue(lastReply().contains("could not prepare the knowledge") && lastReply().contains("cancelled; nothing was sent"), lastReply());
        assertTrue(lastReply().contains("[trial] No answer: EXTRACTION_FAILED."), lastReply());
        assertFalse(lastReply().contains("Cedar on Maple"), "no message-only answer");
        assertTrue(lastReply().contains("Ask again"), lastReply());
        // a timed-out approval on the next ask: the stored message is re-offered under a NEW approval; nothing is sent
        decisions.add(CallApprover.Decision.TIMEOUT);
        a.handle(update(CHAT, SENDER, 202, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(2, asked.size()); assertTrue(museBodies.isEmpty());
        assertNotEquals(asked.get(0).approvalPhrase(), asked.get(1).approvalPhrase(), "never the old approval");
        assertTrue(lastReply().contains("approval timed out; nothing was sent"), lastReply());
    }

    @Test void approvedButFailedExtractionIsNotRetriedAndALaterAskPreparesTheStoredMessagesWithoutResending() throws Exception {
        assumeTrue(savedResponsesPresent(), "saved live responses absent: NOT EXECUTED");
        boot(DECLARED);
        var a = adapter();
        a.handle(update(CHAT, SENDER, 103, BABIES, now()));
        a.handle(update(CHAT, SENDER, 104, TEENS, now()));
        // approved but the extraction call FAILS (HTTP 500): one attempt, no retry, no synthesis
        extractionReply = null;
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 203, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(1, asked.size()); assertEquals(1, museBodies.size(), "exactly one attempt");
        assertEquals(0, kinds(app.dita.app.trial.TrialContext.Kind.SYNTHESIS));
        assertTrue(lastReply().contains("was sent but") && lastReply().contains("not retried"), lastReply());
        // recovery: a later /ask prepares the still-eligible stored messages of the FAILED batch with a NEW approval
        extractionReply = rewrite(Files.readString(SynthesisTrialHarness.EXTRACTION_DIR.resolve("recording.json")), 103, 104);
        synthesisReplies.put("Is Dr. Cedar recommended for babies?", new MuseTransport.Reply(200, rewrite(Files.readString(V2.resolve("q1/synthesis-recording.json")), 103, 104), Map.of()));
        decisions.add(CallApprover.Decision.APPROVED); decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 204, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(3, asked.size());
        assertNotEquals(asked.get(0).approvalPhrase(), asked.get(1).approvalPhrase(), "a new approval, never the failed one");
        assertTrue(!lastReply().contains("[trial]") && lastReply().contains("[" + id(103) + "]") && !lastReply().contains(id(104)), lastReply());
        assertCleanEvidence();
    }

    @Test void projectionsNotReadyStopsTheAskAndALaterAskNeedsNoNewExtraction() throws Exception {
        assumeTrue(savedResponsesPresent(), "saved live responses absent: NOT EXECUTED");
        cedarReplies(101, 102);
        var ready = new java.util.concurrent.atomic.AtomicBoolean(false);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        readiness = s -> checks.getAndIncrement() == 0 || ready.get();      // ready before planning, lagging after extraction
        var props = new HashMap<>(DECLARED);
        props.put("dita.telegram.synthetic-trial.readiness-timeout", "300ms");
        boot(props);
        var a = adapter();
        a.handle(update(CHAT, SENDER, 101, BABIES, now()));
        a.handle(update(CHAT, SENDER, 102, TEENS, now()));
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 201, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(1, asked.size()); assertEquals(1, museBodies.size());
        assertEquals(0, kinds(app.dita.app.trial.TrialContext.Kind.SYNTHESIS), "no synthesis before the projections are ready");
        assertTrue(lastReply().contains("[trial] No answer: PROJECTIONS_NOT_READY."), lastReply());
        ready.set(true);
        decisions.add(CallApprover.Decision.APPROVED);
        a.handle(update(CHAT, SENDER, 202, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertEquals(1, kinds(app.dita.app.trial.TrialContext.Kind.EXTRACTION), "prepared knowledge is reused");
        assertTrue(!lastReply().contains("[trial]") && lastReply().contains("[" + id(101) + "]"), lastReply());
    }

    @Test void declaredSessionStillExcludesInlineBotForwardedAndOtherSenders() throws Exception {
        boot(DECLARED);
        var a = adapter();
        var viaBot = update(CHAT, SENDER, 301, "Inline bot result", now());
        ((com.fasterxml.jackson.databind.node.ObjectNode) viaBot.get("message")).putObject("via_bot").put("id", 42).put("is_bot", true);
        a.handle(viaBot);
        var origin = update(CHAT, SENDER, 302, "Forwarded with origin", now());
        ((com.fasterxml.jackson.databind.node.ObjectNode) origin.get("message")).putObject("forward_origin").put("type", "user");
        a.handle(origin);
        assertTrue(store().live(new GroupScope(G)).isEmpty());
        a.handle(update(CHAT, 222L, 303, "Other sender's plain text", now()));
        a.handle(update(CHAT, 222L, 304, "/ask Is Dr. Cedar recommended for babies?", now()));
        assertTrue(store().live(new GroupScope(G)).isEmpty());
        assertTrue(asked.isEmpty() && museBodies.isEmpty() && sent.isEmpty());
    }

    void forget(TelegramAdapter a, long mid, long target) {
        var f = update(CHAT, SENDER, mid, "/forget", now());
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.path("message")).putObject("reply_to_message").put("message_id", target);
        a.handle(f);
        assertTrue(lastReply().startsWith("Done."), lastReply());
    }

    void assertCleanEvidence() throws Exception {
        try (Stream<Path> files = Files.walk(evidence)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String c = Files.readString(f);
                assertFalse(c.contains(FAKE_KEY) || c.contains(FAKE_TOKEN), "credential in " + f);
            }
        }
        try (Stream<Path> dirs = Files.list(Files.list(evidence).findFirst().orElseThrow())) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                boolean approved = JSON.readTree(Files.readString(d.resolve("approval.json"))).path("decision").asText().equals("APPROVED");
                assertEquals(approved, Files.exists(d.resolve("attempted")), "attempt marker only for approved calls: " + d);
            }
        }
    }

    @Test void trialConfigurationIsRefusedUnlessExplicitAndNarrow() throws Exception {
        record Case(String why, Map<String, String> extra) {}
        for (var c : List.of(
                new Case("worker", Map.of("dita.extraction.worker-enabled", "true")),
                new Case("approver", Map.of("dita.telegram.synthetic-trial.approver-ids", "111")),
                new Case("two chats", Map.of("dita.telegram.allowed-chat-ids", CHAT + ",-100777")),
                new Case("coordination", Map.of("dita.coordination.enabled", "true")),
                new Case("synthesis", Map.of("dita.memory.answers.synthesis.enabled", "false")),
                new Case("allow-live", Map.of("dita.memory.answers.synthesis.allow-live", "false")),
                new Case("evidence", Map.of("dita.telegram.synthetic-trial.evidence-dir", "")),
                new Case("replay", Map.of("dita.telegram.mode", "REPLAY")),
                new Case("declared, second author", Map.of("dita.telegram.synthetic-trial.session-declaration", "ALL_NEW_MESSAGES_SYNTHETIC",
                        "dita.telegram.allowed-author-ids", SENDER + ",222")))) {
            Exception e = assertThrows(Exception.class, () -> boot(c.extra()), c.why());
            assertNotNull(e);
            ctx = null;
        }
    }
}
