package app.dita.app;

import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;
import app.dita.muse.MuseTransport;
import app.dita.muse.RecordedMuseTransport;
import app.dita.telegram.ReplayTelegramGateway;
import app.dita.telegram.TelegramGateway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-051 runtime acceptance, driven ONLY through the running DitaApplication and a synthetic Telegram replay:
 * Telegram adapter (real poller + adapter, {@link ReplayTelegramGateway}) → ingestion → runtime extraction trigger →
 * /extract approval by a configured approver → Muse client with a RECORDED reply (no network) → evidence validation →
 * persisted knowledge → projection indexing → retrieval → grounded /ask reply. The test never ingests, enqueues,
 * approves or inserts anything itself; it only reads state afterwards.
 * A recorded Muse reply is NOT a live Muse integration. Each run uses a fresh chat id, so stale data cannot satisfy it.
 */
abstract class TelegramReplayAcceptance {
    static final String FIXTURE_CHAT = "1001000000052";
    static final long YOSSI = 1002, OPERATOR = 1009;

    final String chatDigits = "1001" + (100_000_000 + ThreadLocalRandom.current().nextInt(899_999_999));
    final long chat = -Long.parseLong(chatDigits);
    final GroupScope scope = new GroupScope("tgr" + chatDigits);
    String mid(int n) { return "tgr" + chatDigits + "m" + n; }

    abstract Map<String, Object> backendProperties();
    /** Backend-specific proof that the persisted knowledge was indexed (Vespa/Neo4j); in-memory has none. */
    abstract void assertIndexed(ConfigurableApplicationContext c, List<KnowledgeItem> knowledge) throws Exception;

    Path fixture(String resource, Path dir) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/replay/" + resource)) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(FIXTURE_CHAT, chatDigits);
            Path out = dir.resolve(resource);
            Files.writeString(out, s);
            return out;
        }
    }

    ConfigurableApplicationContext boot(Path dir) throws Exception {
        Map<String, Object> p = new HashMap<>(backendProperties());
        p.put("dita.telegram.enabled", "true");
        p.put("dita.telegram.mode", "REPLAY");
        p.put("dita.telegram.replay.file", fixture("parents-levi.replay.jsonl", dir).toString());
        p.put("dita.telegram.replay.extraction-approver-ids", Long.toString(OPERATOR));
        p.put("dita.telegram.replay.barrier-timeout", "45s");
        p.put("dita.telegram.allowed-chat-ids", Long.toString(chat));
        p.put("dita.telegram.allowed-author-ids", "1001,1002,1003," + OPERATOR);
        p.put("dita.telegram.poll-timeout", "1s");
        p.put("dita.coordination.enabled", "false");
        p.put("dita.muse.enabled", "true");
        p.put("dita.muse.transport", "RECORDED");
        p.put("dita.muse.recording-file", fixture("parents-levi.muse-recording.json", dir).toString());
        p.put("dita.processing.dispatch.poll-interval", "50ms");
        p.put("dita.telegram.replay.transcript-file", dir.resolve("transcript.jsonl").toString());   // TASK-051 observability
        String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        Function<String, String> env = n -> n.equals("MODEL_API_KEY") ? null : System.getenv(n);   // the real key is never visible
        return new SpringApplicationBuilder(DitaApplication.class)
                .initializers(c -> c.getBeanFactory().registerSingleton("ditaEnv", env)).run(args);
    }

    /** m7 "Which doctor is good for infants?" shares no content word with the stored items. Default: nothing asserted. */
    void assertSemantic(ConfigurableApplicationContext c, List<KnowledgeItem> knowledge, String replyId, String text) {}

    void runAcceptance() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "replay-run-" + chatDigits));   // not the JVM temp dir (sandboxed runs)
        ConfigurableApplicationContext c = boot(dir);
        try {
            var gw = (ReplayTelegramGateway) c.getBean(TelegramGateway.class);
            var muse = (RecordedMuseTransport) c.getBean(MuseTransport.class);
            // Natasha N-5 negative control on the same store: an APPROVED batch in a non-replay group must stay untouched
            // by the replay worker. It is a control, not part of the pipeline under test (asserted only as "not processed").
            var control = new GroupScope("ctl" + chatDigits);
            var extraction = c.getBean(app.dita.core.knowledge.ExtractionService.class);
            // Register the control group first, as TelegramAdapter does: PostgreSQL enforces message.group_id → chat_group.
            c.getBean(app.dita.core.model.GroupRegistry.class).ensureRegistered(control.groupId(), "synthetic", control.groupId(),
                    app.dita.core.model.GroupDirectory.ChatType.SYNTHETIC, SourceKind.SYNTHETIC);
            c.getBean(MessageStore.class).append(control, new Message("ctl" + chatDigits + "m1", control.groupId(), "1001",
                    java.time.Instant.now(), null, "Control message that the replay worker must never extract.", SourceKind.SYNTHETIC, false));
            Message cm = c.getBean(MessageStore.class).find(control, "ctl" + chatDigits + "m1").orElseThrow();
            assertTrue(extraction.enqueue(control, cm));
            String controlBatch = extraction.collect(control).get(0).id();
            extraction.approve(control, controlBatch, "control-not-replay-" + chatDigits);
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            ReplayTelegramGateway.Sent reply = null;
            while (System.nanoTime() < deadline && reply == null) {
                reply = gw.transcript().stream().filter(s -> Long.valueOf(6).equals(s.replyTo())).findFirst().orElse(null);
                if (reply == null) Thread.sleep(100);
            }
            assertNotNull(reply, "no /ask reply within 90 s; transcript=" + gw.transcript().size() + " messages");
            ReplayTelegramGateway.Sent semanticReply = null;   // m7: a lexically poor paraphrase (TASK-052)
            while (System.nanoTime() < deadline && semanticReply == null) {
                semanticReply = gw.transcript().stream().filter(s -> Long.valueOf(7).equals(s.replyTo())).findFirst().orElse(null);
                if (semanticReply == null) Thread.sleep(100);
            }
            assertNotNull(semanticReply, "no reply to the m7 /ask within 90 s");
            MessageStore store = c.getBean(MessageStore.class);
            ExtractionQueue queue = c.getBean(ExtractionQueue.class);

            // 1. Telegram adapter → ingestion: three SYNTHETIC messages in the replay namespace; commands are not memory
            for (int n = 1; n <= 3; n++) {
                Message m = store.find(scope, mid(n)).orElseThrow(() -> new AssertionError("message not ingested"));
                assertEquals(SourceKind.SYNTHETIC, m.sourceKind());
            }
            for (int n = 4; n <= 7; n++) assertTrue(store.find(scope, mid(n)).isEmpty(), "commands are not memory");

            // 2. extraction trigger + approval: one DONE batch, approved by the configured approver's /extract (m5), not m4
            assertTrue(gw.transcript().stream().anyMatch(s -> Long.valueOf(4).equals(s.replyTo())
                    && s.text().contains("Only a configured extraction approver")), "a non-approver cannot approve");
            List<ExtractionBatch> done = queue.batches(scope, ExtractionBatch.Status.DONE);
            assertEquals(1, done.size(), () -> "exactly one extraction batch ran; batches in this group: "   // TASK-052 diagnostics
                    + java.util.Arrays.stream(ExtractionBatch.Status.values()).flatMap(s -> queue.batches(scope, s).stream())
                        .map(x -> x.id() + "=" + x.status() + " attempts=" + x.attempts() + " approval=" + x.approvalRef()
                                + " error=" + x.lastError()).toList()
                    + "; muse requests=" + muse.requests().size());
            ExtractionBatch b = done.get(0);
            assertEquals(List.of(mid(1), mid(2), mid(3)), b.messageIds());
            assertTrue(b.approvalRef().matches("telegram-replay:[0-9a-f]{16}-[0-9a-z]+:" + chatDigits + ":m5:by" + OPERATOR), b.approvalRef());

            // 3. Muse client called once through the configured (recorded) transport; authors pseudonymised
            assertEquals(1, muse.requests().size());
            String req = muse.requests().get(0);
            assertTrue(req.contains("Dr. Levi on Herzl St."), "the synthetic batch was sent to the Muse client");
            assertFalse(req.contains("Synthetic1001"), "no display names in the request");

            // 4. persisted structured knowledge: the two supported items; the unsupported proposal was rejected
            List<KnowledgeItem> k = store.knowledge(scope);
            assertEquals(2, k.size(), "only evidence-supported items are stored: " + k);
            assertTrue(k.stream().anyMatch(i -> i.statement().equals("Dr. Levi on Herzl St. is excellent for babies")
                    && i.asserting().get(0).messageId().equals(mid(1))));
            assertTrue(k.stream().anyMatch(i -> i.assertion().predicate().equals("advises_against")
                    && i.asserting().get(0).messageId().equals(mid(2))));

            // 5. indexing (backend-specific)
            assertIndexed(c, k);

            // 6. grounded /ask answer from the knowledge path, cited to the original messages
            String text = reply.text();
            assertTrue(text.contains("recommended Dr. Levi") && text.contains("for babies"), text);
            assertTrue(text.contains("advised against Dr. Levi") && text.contains("for teenagers"), text);
            assertFalse(text.contains("best pediatrician"), "a rejected proposal is never answered");

            // 6b. observability: the transcript file holds this exact reply text and its citations (written as sent)
            Path tf = dir.resolve("transcript.jsonl");
            com.fasterxml.jackson.databind.JsonNode ask = null;
            for (long until = System.nanoTime() + Duration.ofSeconds(10).toNanos(); ask == null && System.nanoTime() < until; Thread.sleep(50)) {
                if (!Files.exists(tf)) continue;
                for (String l : Files.readAllLines(tf)) {
                    var r = new com.fasterxml.jackson.databind.ObjectMapper().readTree(l);
                    if (r.path("type").asText().equals("ask") && r.path("ask_message_id").asLong() == 6) ask = r;
                }
            }
            assertNotNull(ask, "the /ask reply is in the transcript file");
            assertEquals(text, ask.path("answer_text").asText(), "transcript text is the gateway reply, byte for byte");
            assertEquals("SENT", ask.path("status").asText());
            assertEquals(reply.messageId(), ask.path("reply_message_id").asLong());
            assertEquals(6, ask.path("ask_message_id").asLong());
            assertTrue(ask.path("citations").findValuesAsText("message_id").containsAll(List.of(mid(1), mid(2))), ask.toString());
            assertFalse(ask.path("citations").findValuesAsText("message_id").contains(mid(3)), ask.toString());
            String replyId = "tgrreply" + chatDigits + "m" + reply.messageId();
            assertTrue(store.ditaRepliesCiting(scope, mid(1)).contains(replyId), "the reply cites message 1");
            assertTrue(store.ditaRepliesCiting(scope, mid(2)).contains(replyId), "the reply cites message 2");
            assertFalse(store.ditaRepliesCiting(scope, mid(3)).contains(replyId), "the unrelated message is not cited");

            // 7a. dense/hybrid (only when the backend enables it): see assertSemantic
            assertSemantic(c, k, "tgrreply" + chatDigits + "m" + semanticReply.messageId(), semanticReply.text());

            // 7. isolation on the shared store: the control batch was never picked up by the replay worker
            assertEquals(ExtractionBatch.Status.APPROVED, queue.batch(control, controlBatch).orElseThrow().status(),
                    "the replay worker never processes a non-replay group's batch");
            assertEquals(1, muse.requests().size(), "no Muse request for the control batch");
        } finally {
            c.close();
        }
    }
}
