package app.dita.telegram;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.config.ClarificationSettings;
import app.dita.core.config.DeletionSettings;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.deletion.DeletionService;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-051 observability: Dita's outgoing /ask reply in a synthetic replay is written to the transcript file exactly as
 * the gateway sent it, with its source citations. Live Telegram (no ReplayOptions) never records answers.
 * Fictional ids and text; no network; the real QueryService (lexical, message-only answers).
 */
class ReplayTranscriptTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000053L, DANA = 1001, YOSSI = 1002, NOA = 1003;
    static final String M1 = "Dr. Levi on Herzl St. is excellent for babies, we've been going since our daughter was born";
    static final String M2 = "Who is bringing snacks for Friday's trip?";

    final InMemoryMessageStore store = new InMemoryMessageStore();

    static String line(long updateId, long mid, long from, int offset, String text) {
        return "{\"update_id\":" + updateId + ",\"message\":{\"message_id\":" + mid + ",\"date_offset_s\":" + offset
                + ",\"chat\":{\"id\":" + CHAT + ",\"type\":\"supergroup\",\"title\":\"Synthetic transcript test\"},\"from\":{\"id\":"
                + from + ",\"is_bot\":false,\"first_name\":\"Synthetic" + from + "\"},\"text\":" + JSON.valueToTree(text) + "}}";
    }

    ReplayTelegramGateway gateway() throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String l : List.of(line(1, 1, DANA, 0, M1), line(2, 2, NOA, 10, M2), line(3, 3, YOSSI, 20, "/ask Is Dr. Levi good for babies?"))) {
            lines.add(JSON.readTree(l));
        }
        return new ReplayTelegramGateway(lines, Clock.systemUTC(), () -> true, Duration.ofSeconds(1), "abc123-transcript");
    }

    TelegramAdapter adapter(TelegramGateway gw, boolean replay) {
        var inf = new RuleLinkInferencer();
        var settings = new TelegramAdapterSettings(Set.of(CHAT), "DitaDemoBot", false, 50, EnumSet.allOf(ApprovalAct.class),
                Duration.ofDays(21), false, Set.of(DANA, YOSSI, NOA), null);
        var query = new QueryService(store, new LexicalRetriever(store),
                new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store), 5, 200);
        TelegramAdapter.ReplayOptions options = !replay ? null : new TelegramAdapter.ReplayOptions(Set.of(DANA), "abc123-transcript",
                new TelegramAdapter.ExtractionHooks() {
                    public void ingested(GroupScope s, Message m) { }
                    public int approvePending(GroupScope s, String ref) { return 0; }
                });
        return new TelegramAdapter(settings, store, new IngestionPipeline(store, inf, 50, EventPublisher.NONE),
                new DeletionService(store, inf, DeletionSettings.DEFAULTS, EventPublisher.NONE, 50), query,
                new ClarificationGate(ClarificationSettings.DEFAULTS), gw,
                new CallbackSigner("synthetic-test-secret-0123456789abcdef".getBytes()), Clock.systemUTC(),
                app.dita.core.model.GroupRegistry.NONE, options);
    }

    /** Delivers every replay line through the gateway, as the poller does, and lets the adapter handle it. */
    static void drain(ReplayTelegramGateway gw, TelegramAdapter a) throws Exception {
        long offset = 0;
        while (!gw.finished()) {
            for (JsonNode u : gw.getUpdates(offset, 1, 100)) { a.handle(u); offset = u.path("update_id").asLong() + 1; }
        }
    }

    static List<JsonNode> records(Path f) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String l : Files.readAllLines(f)) out.add(JSON.readTree(l));
        return out;
    }

    @Test void askReplyAppearsInTheTranscriptExactlyAsSentWithItsCitations() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "replay-transcript-test"));
        Path file = dir.resolve("transcript.jsonl");
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        ReplayTelegramGateway gw = gateway();
        gw.writeTranscriptTo(file, new PrintStream(terminal, true, StandardCharsets.UTF_8));
        drain(gw, adapter(gw, true));
        gw.close();                                              // as at shutdown; the file must be complete afterwards

        ReplayTelegramGateway.Sent reply = gw.transcript().stream().filter(s -> Long.valueOf(3).equals(s.replyTo())).findFirst().orElseThrow();
        List<JsonNode> recs = records(file);
        assertEquals("run", recs.get(0).path("type").asText());
        assertEquals("synthetic-replay", recs.get(0).path("source").asText());

        JsonNode ask = recs.stream().filter(r -> r.path("type").asText().equals("ask")).findFirst().orElseThrow();
        assertEquals("SENT", ask.path("status").asText());
        assertEquals(reply.text(), ask.path("answer_text").asText(), "the transcript holds the gateway's exact text");
        assertEquals(reply.messageId(), ask.path("reply_message_id").asLong());
        assertEquals(3, ask.path("ask_message_id").asLong());
        assertEquals("/ask Is Dr. Levi good for babies?", ask.path("asked").path("text").asText());
        assertEquals(YOSSI, ask.path("asked").path("from_id").asLong());
        assertEquals("abc123-transcript", ask.path("run").asText());

        // citations: the original source message, quoted verbatim; the unrelated message is not cited
        JsonNode cites = ask.path("citations");
        assertTrue(cites.size() >= 1, "the answer cites its source: " + ask);
        String m1Id = "tgr" + Math.abs(CHAT) + "m1";
        assertTrue(cites.findValuesAsText("message_id").contains(m1Id), ask.toString());
        for (JsonNode c : cites) {
            String original = store.find(new GroupScope("tgr" + Math.abs(CHAT)), c.path("message_id").asText()).orElseThrow().text();
            assertTrue(original.contains(c.path("quote").asText()), "quote is verbatim from its original message");
        }
        assertFalse(cites.findValuesAsText("message_id").contains("tgr" + Math.abs(CHAT) + "m2"));

        // the "sent" record of the same reply carries the identical text and what it answered
        JsonNode sent = recs.stream().filter(r -> r.path("type").asText().equals("sent") && r.path("message_id").asLong() == reply.messageId())
                .findFirst().orElseThrow();
        assertEquals(reply.text(), sent.path("text").asText());
        assertEquals(3, sent.path("reply_to_message_id").asLong());

        // readable copy (file and terminal) contains the exact answer text verbatim, between markers
        String txt = Files.readString(ReplayTelegramGateway.readableTranscriptPath(file));
        String block = ">>> exact answer text >>>\n" + reply.text() + "\n<<< end answer <<<\n";
        assertTrue(txt.contains(block), txt);
        assertTrue(txt.contains(m1Id), txt);
        assertTrue(terminal.toString(StandardCharsets.UTF_8).contains(block));
    }

    /** Without ReplayOptions (live Telegram semantics) the adapter never records answers, even on a replay gateway. */
    @Test void liveAdapterNeverRecordsAnswers() throws Exception {
        Path file = Files.createDirectories(Path.of("target", "replay-transcript-test")).resolve("live.jsonl");
        ByteArrayOutputStream terminal = new ByteArrayOutputStream();
        ReplayTelegramGateway gw = gateway();
        gw.writeTranscriptTo(file, new PrintStream(terminal, true, StandardCharsets.UTF_8));
        drain(gw, adapter(gw, false));
        gw.close();
        assertTrue(records(file).stream().noneMatch(r -> r.path("type").asText().equals("ask")));
        assertFalse(terminal.toString(StandardCharsets.UTF_8).contains("exact answer text"));
    }

    /** Without a transcript file the gateway writes nothing (the in-memory transcript is unchanged). */
    @Test void noTranscriptFileByDefault() throws Exception {
        ReplayTelegramGateway gw = gateway();
        drain(gw, adapter(gw, true));
        gw.close();
        assertTrue(gw.transcript().stream().anyMatch(s -> Long.valueOf(3).equals(s.replyTo())));
    }

    /** Natasha L-1/L-2: the transcript never follows a symlink and is created owner-only. */
    @Test void transcriptRefusesSymlinksAndIsOwnerOnly() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "replay-transcript-test", "links"));
        Path victim = dir.resolve("victim.jsonl");
        Files.writeString(victim, "keep me");
        Path link = dir.resolve("link.jsonl");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, victim.toAbsolutePath());
        assertThrows(IllegalArgumentException.class, () -> gateway().writeTranscriptTo(link, null));
        assertEquals("keep me", Files.readString(victim), "a symlinked target is never truncated");

        Path ok = dir.resolve("owner-only.jsonl");
        Files.deleteIfExists(ok);
        Files.deleteIfExists(ReplayTelegramGateway.readableTranscriptPath(ok));
        ReplayTelegramGateway gw = gateway();
        gw.writeTranscriptTo(ok, null);
        gw.close();
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(ok)));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(ReplayTelegramGateway.readableTranscriptPath(ok))));
    }
}
