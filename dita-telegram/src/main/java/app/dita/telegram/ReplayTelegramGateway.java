package app.dita.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * TASK-051: a {@link TelegramGateway} that replays a SYNTHETIC conversation through the real {@link TelegramPoller} and
 * {@link TelegramAdapter}, and never contacts Telegram. Outgoing messages are recorded in a {@link #transcript()}.
 * <p>Replay file: JSON Lines. Each line is either a Bot API update object, or a barrier {@code {"replay":"await-idle"}}.
 * Update dates are rebased: {@code message.date_offset_s} (seconds) becomes {@code date} = replay start + offset. A
 * barrier holds back the following updates until {@code idle} reports that the pipeline has caught up (extraction and
 * projection), like a person who waits before asking. It never changes what Dita does with an update.
 * <p>Transcript file (optional, replay only): every outgoing message, and for each /ask the question, Dita's exact reply
 * text and its source citations, as JSON Lines plus a readable copy ({@link ReplayTranscript}).
 */
public final class ReplayTelegramGateway implements TelegramGateway, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger log = System.getLogger(ReplayTelegramGateway.class.getName());

    public record Sent(long chatId, long messageId, String text, Long replyTo) {}

    private final List<JsonNode> lines;
    private final Clock clock;
    private final BooleanSupplier idle;
    private final Duration barrierTimeout;
    private final List<Sent> transcript = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong nextSentId = new AtomicLong(900_000);
    private int cursor = 0;
    private final List<JsonNode> inflight = new ArrayList<>();   // delivered, not yet acknowledged (offset not past them)
    private Long base;                    // replay start (epoch seconds), fixed at the first poll
    private volatile boolean finished;
    /** Delivered synthetic messages, "chat:message_id" to the update's message, so a reply can show what it answers. */
    private final java.util.Map<String, JsonNode> delivered = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile ReplayTranscript transcriptFile;   // null = in-memory transcript only

    /** Unchecked lines and an "unpinned" tag: package tests only. Production uses {@link #fromFile} (Natasha N-4). */
    ReplayTelegramGateway(List<JsonNode> lines, Clock clock, BooleanSupplier idle, Duration barrierTimeout) {
        this(lines, clock, idle, barrierTimeout, "unpinned");
    }

    ReplayTelegramGateway(List<JsonNode> lines, Clock clock, BooleanSupplier idle, Duration barrierTimeout, String approvalTag) {
        this.lines = List.copyOf(lines); this.clock = clock; this.idle = idle; this.barrierTimeout = barrierTimeout;
        this.approvalTag = approvalTag;
    }

    /** Header every replay file must start with (Natasha M-1): the operator declares the content synthetic. */
    public static final String HEADER = "{\"replay\":\"synthetic-fixture\"}";

    private final String approvalTag;   // fixed at construction (Natasha re-review item 3)

    /** Short file hash + run id, recorded in every /extract approval reference. */
    public String approvalTag() { return approvalTag; }

    public static ReplayTelegramGateway fromFile(Path file, Clock clock, BooleanSupplier idle, Duration barrierTimeout) {
        byte[] bytes;
        try { bytes = Files.readAllBytes(file); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("unreadable Telegram replay file (path not shown)"); }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<String> all = text.lines().toList();
        // the literal first line, exactly as scripts/demo-replay-run.sh checks it (item 4); the header is the operator's
        // declaration that the content is synthetic, not proof of it
        if (!(text.equals(HEADER) || text.startsWith(HEADER + "\n"))) {   // a CRLF header is refused, as by `head -1`
            throw new IllegalArgumentException("replay file must start with " + HEADER + " (synthetic fixtures only)");
        }
        List<JsonNode> out = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            if (all.get(i).isBlank()) continue;
            try { out.add(JSON.readTree(all.get(i))); }
            catch (java.io.IOException e) { throw new IllegalArgumentException("replay file line " + (i + 1) + " is not JSON"); }   // no content echoed (L-1)
        }
        String sha;
        try {
            sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return new ReplayTelegramGateway(out, clock, idle, barrierTimeout, sha + "-" + Long.toString(clock.millis(), 36));
    }

    /**
     * Also writes the transcript to {@code jsonlFile} (truncated first) and its readable copy beside it; {@code echo},
     * when not null, also gets the readable copy (the terminal). Call before the first poll.
     */
    public static ReplayTelegramGateway fromFile(Path file, Clock clock, BooleanSupplier idle, Duration barrierTimeout,
                                                 Path jsonlFile, java.io.PrintStream echo) {
        ReplayTelegramGateway g = fromFile(file, clock, idle, barrierTimeout);
        if (jsonlFile != null) g.writeTranscriptTo(jsonlFile, echo);
        return g;
    }

    /** Package tests and {@link #fromFile}: starts the transcript file and its run header. */
    synchronized void writeTranscriptTo(Path jsonlFile, java.io.PrintStream echo) {
        if (transcriptFile != null) throw new IllegalStateException("transcript file already set");
        transcriptFile = new ReplayTranscript(jsonlFile, echo);
        ObjectNode r = JSON.createObjectNode().put("type", "run").put("run", approvalTag).put("source", "synthetic-replay")
                .put("started_at", clock.instant().toString());
        transcriptFile.write(r, "==== Dita synthetic replay " + approvalTag + " (started " + clock.instant() + ") ====\n"
                + "Synthetic fixture only. Answers below are Dita's exact outgoing text.\n\n");
    }

    /** Readable copy of a transcript file ({@code transcript.jsonl} gives {@code transcript.txt}). */
    public static Path readableTranscriptPath(Path jsonlFile) { return ReplayTranscript.readableSibling(jsonlFile); }

    /** True once every line was delivered. */
    public boolean finished() { return finished; }
    public List<Sent> transcript() { return List.copyOf(transcript); }

    @Override public synchronized List<JsonNode> getUpdates(long offset, int timeoutSeconds, int limit) throws Exception {
        // Rebase one second after the first poll: the adapter ignores updates dated before its own start (TASK-040).
        if (base == null) base = clock.instant().getEpochSecond() + 1;
        while (clock.instant().getEpochSecond() < base) Thread.sleep(50);
        inflight.removeIf(u -> u.path("update_id").asLong() < offset);
        if (!inflight.isEmpty()) return List.copyOf(inflight);          // redelivery after a transient failure (Shuri B1)
        List<JsonNode> out = new ArrayList<>();
        while (cursor < lines.size() && out.size() < limit) {
            JsonNode line = lines.get(cursor);
            if (line.has("replay")) {
                if (!out.isEmpty()) break;                                  // deliver what precedes the barrier first
                if (!"await-idle".equals(line.path("replay").asText())) throw new IllegalStateException("unknown replay directive");
                awaitIdle();
                cursor++;
                continue;
            }
            ObjectNode u = line.deepCopy();
            if (u.path("update_id").asLong(-1) >= offset) { out.add(rebase(u)); remember(u); }
            cursor++;
        }
        inflight.addAll(out);
        if (cursor >= lines.size()) finished = true;
        if (out.isEmpty()) Thread.sleep(Math.min(1000, timeoutSeconds * 1000L));   // long-poll idle
        return out;
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + barrierTimeout.toNanos();
        Thread.sleep(200);                                                  // let the preceding updates' async work start
        int stable = 0;
        while (System.nanoTime() < deadline) {
            if (idle.getAsBoolean()) { if (++stable >= 3) return; } else stable = 0;
            Thread.sleep(100);
        }
        log.log(System.Logger.Level.WARNING, "replay barrier timed out; continuing (the run's assertions decide the outcome)");
    }

    private JsonNode rebase(ObjectNode u) {
        for (String k : List.of("message", "edited_message")) {
            if (u.has(k) && u.get(k).isObject()) {
                ObjectNode m = (ObjectNode) u.get(k);
                long off = m.path("date_offset_s").asLong(0);
                m.remove("date_offset_s");
                m.put("date", base + off);
                if (m.has("edit_offset_s")) { m.put("edit_date", base + m.path("edit_offset_s").asLong()); m.remove("edit_offset_s"); }
            }
        }
        return u;
    }

    private void remember(JsonNode u) {
        for (String k : List.of("message", "edited_message")) {
            JsonNode m = u.path(k);
            if (m.isObject()) delivered.put(m.path("chat").path("id").asLong() + ":" + m.path("message_id").asLong(), m);
        }
    }

    @Override public long sendMessage(long chatId, String text, Long replyToMessageId, List<Button> buttons) {
        long id = nextSentId.incrementAndGet();
        transcript.add(new Sent(chatId, id, text, replyToMessageId));
        ReplayTranscript out = transcriptFile;
        if (out != null) {
            ObjectNode r = JSON.createObjectNode().put("type", "sent").put("run", approvalTag).put("status", "SENT")
                    .put("chat_id", chatId).put("message_id", id);
            if (replyToMessageId == null) r.putNull("reply_to_message_id"); else r.put("reply_to_message_id", replyToMessageId);
            JsonNode in = replyToMessageId == null ? null : delivered.get(chatId + ":" + replyToMessageId);
            if (in != null) r.set("in_reply_to", incoming(in));
            r.put("text", text);
            if (buttons != null && !buttons.isEmpty()) r.put("buttons", buttons.size());
            out.write(r, null);
        }
        return id;
    }

    /**
     * TASK-051 observability, called by the adapter after it sent an /ask reply in a replay. Records the question, Dita's
     * exact outgoing text (taken from what this gateway sent, never re-composed) and the answer's source citations.
     * {@code replyId} is what {@link #sendMessage} returned, or a value &lt;= 0 if sending failed.
     */
    public void answered(long chatId, long askMessageId, long replyId, app.dita.core.answer.GroundedAnswer a) {
        ReplayTranscript out = transcriptFile;
        if (out == null) return;
        Sent sent = replyId <= 0 ? null : transcript().stream().filter(s -> s.messageId() == replyId && s.chatId() == chatId)
                .findFirst().orElse(null);
        String status = sent == null ? "SEND_FAILED" : "SENT";
        JsonNode in = delivered.get(chatId + ":" + askMessageId);
        ObjectNode r = JSON.createObjectNode().put("type", "ask").put("run", approvalTag).put("status", status)
                .put("chat_id", chatId).put("ask_message_id", askMessageId);
        if (sent == null) r.putNull("reply_message_id"); else r.put("reply_message_id", sent.messageId());
        if (in != null) r.set("asked", incoming(in));
        r.put("question", a.question());
        if (sent == null) r.putNull("answer_text"); else r.put("answer_text", sent.text());
        r.put("found", a.found()).put("conflicting", a.conflicting());
        var cites = r.putArray("citations");
        for (var c : a.citations()) {
            ObjectNode o = cites.addObject().put("message_id", c.messageId()).put("author_id", c.authorId());
            if (c.sentAt() == null) o.putNull("sent_at"); else o.put("sent_at", c.sentAt().toString());
            o.put("quote", c.quote()).put("evidence", c.evidence() == null ? null : c.evidence().name())
                    .put("polarity", c.polarity()).put("qualified", c.qualified());
        }
        var kids = r.putArray("knowledge_ids");
        a.knowledgeIds().forEach(kids::add);
        out.write(r, readable(r));
    }

    private static ObjectNode incoming(JsonNode m) {
        return JSON.createObjectNode().put("message_id", m.path("message_id").asLong())
                .put("from_id", m.path("from").path("id").asLong()).put("text", m.path("text").asText(""));
    }

    private static String readable(ObjectNode r) {
        StringBuilder b = new StringBuilder("---- /ask (chat ").append(r.path("chat_id").asLong()).append(") ----\n");
        JsonNode asked = r.path("asked");
        b.append("Question  [message ").append(r.path("ask_message_id").asLong());
        if (asked.isObject()) b.append(", from ").append(asked.path("from_id").asLong());
        b.append("]: ").append(asked.isObject() ? asked.path("text").asText() : r.path("question").asText()).append('\n');
        b.append("Reply     [").append(r.path("status").asText());
        if (!r.path("reply_message_id").isNull()) b.append(", message ").append(r.path("reply_message_id").asLong());
        b.append(", in reply to ").append(r.path("ask_message_id").asLong())
                .append(", found=").append(r.path("found").asBoolean()).append(", conflicting=").append(r.path("conflicting").asBoolean())
                .append("]\n");
        b.append(">>> exact answer text >>>\n").append(r.path("answer_text").isNull() ? "(not sent)" : r.path("answer_text").asText())
                .append("\n<<< end answer <<<\n");
        JsonNode cites = r.path("citations");
        b.append("Sources   (").append(cites.size()).append(")\n");
        int i = 0;
        for (JsonNode c : cites) {
            b.append("  [").append(++i).append("] ").append(c.path("message_id").asText()).append(" by ").append(c.path("author_id").asText())
                    .append(" at ").append(c.path("sent_at").asText()).append(" (").append(c.path("evidence").asText())
                    .append(", polarity ").append(c.path("polarity").asInt()).append(")\n      \"").append(c.path("quote").asText()).append("\"\n");
        }
        return b.append('\n').toString();
    }

    /** Flushes and closes the transcript file (Spring calls it at shutdown; every record is already on disk). */
    @Override public void close() {
        ReplayTranscript out = transcriptFile;
        if (out != null) out.close();
    }

    @Override public void answerCallbackQuery(String callbackQueryId, String text) { }

    @Override public Set<Long> chatAdministrators(long chatId) { return Set.of(); }
}
