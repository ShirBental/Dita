package app.dita.telegram;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.config.ClarificationSettings;
import app.dita.core.config.DeletionSettings;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.coordination.EventPlan;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-040 / NATASHA-EGRESS-001 E-3: only the allow-listed author (from.id) is accepted in the allow-listed chat.
 * Uses the configured TASK-040 ids (chat -1001000000054, a basic group; author 1001). All message texts are
 * synthetic. Offline: in-memory store, fake gateway, no network, no Muse.
 */
class AuthorAllowListTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000054L, AUTHOR = 1001L;               // configured in application.yml (TASK-040)
    static final long STRANGER = 9168992916L, OTHER_CHAT = -5365313454L;       // synthetic, NOT allow-listed
    static final long T0 = Instant.parse("2026-11-01T10:00:00Z").getEpochSecond();
    static final String SECRET_TEXT = "SYNTHETIC-unauthorized-text-7f3a";      // must never reach memory, replies or logs

    static final class FakeGateway implements TelegramGateway {
        final List<String> sent = new ArrayList<>(), answers = new ArrayList<>();
        final List<List<Button>> buttons = new ArrayList<>();
        public List<JsonNode> getUpdates(long o, int t, int l) { return List.of(); }
        public long sendMessage(long c, String text, Long r, List<Button> b) { sent.add(text); buttons.add(b); return 9000 + sent.size(); }
        public void answerCallbackQuery(String id, String text) { answers.add(text); }
        public Set<Long> chatAdministrators(long c) { return Set.of(); }
    }

    /** Captures the adapter's log records (System.Logger → java.util.logging by default). */
    static final class Capture extends Handler {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        public void publish(LogRecord r) { lines.add(String.valueOf(r.getMessage())); }
        public void flush() { }
        public void close() { }
    }

    final Logger jul = Logger.getLogger(TelegramAdapter.class.getName());
    final Capture logs = new Capture();
    InMemoryMessageStore store;
    FakeGateway gw;
    TelegramAdapter adapter;
    long updateId = 1;

    TelegramAdapter build(Set<Long> authors) { return build(authors, null); }

    TelegramAdapter build(Set<Long> authors, Instant ignoreBefore) {
        var inf = new RuleLinkInferencer();
        var settings = new TelegramAdapterSettings(Set.of(CHAT), "ExampleDitaBot", true, 50, EnumSet.allOf(ApprovalAct.class),
                Duration.ofDays(21), false, authors, ignoreBefore);
        var query = new QueryService(store, new LexicalRetriever(store),
                new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store), 5, 200);
        return new TelegramAdapter(settings, store, new IngestionPipeline(store, inf, 50, EventPublisher.NONE),
                new DeletionService(store, inf, DeletionSettings.DEFAULTS, EventPublisher.NONE, 50), query,
                new ClarificationGate(ClarificationSettings.DEFAULTS), gw, new CallbackSigner("synthetic-allowlist-secret-0123456789ab".getBytes()),
                Clock.fixed(Instant.ofEpochSecond(T0 + 600), ZoneOffset.UTC));
    }

    @BeforeEach void setUp() {
        store = new InMemoryMessageStore(); gw = new FakeGateway(); adapter = build(Set.of(AUTHOR));
        jul.setLevel(Level.ALL); jul.addHandler(logs);
    }

    @AfterEach void tearDown() { jul.removeHandler(logs); }

    // ---------- synthetic updates ----------

    ObjectNode message(long chat, long mid, long from, long offset, String text) {
        ObjectNode m = JSON.createObjectNode();
        m.put("message_id", mid).put("date", T0 + offset).put("text", text);
        m.putObject("chat").put("id", chat).put("type", "group").put("title", "SyntheticDemoGroup (synthetic demo)");
        m.putObject("from").put("id", from).put("is_bot", false).put("first_name", "Synthetic");
        return m;
    }

    void handle(String kind, JsonNode body) {
        ObjectNode u = JSON.createObjectNode();
        u.put("update_id", updateId++);
        u.set(kind, body);
        adapter.handle(u);
    }

    void post(long chat, long mid, long from, long offset, String text) { handle("message", message(chat, mid, from, offset, text)); }

    GroupScope scope() { return new GroupScope("tg" + Math.abs(CHAT)); }
    String id(long mid) { return TelegramAdapter.messageId(CHAT, mid); }

    // ---------- tests ----------

    @Test void allowListedAuthorInTheConfiguredDemoChatIsIngested() {
        post(CHAT, 1, AUTHOR, 0, "Synthetic: who brings snacks on Friday?");
        assertEquals(1, store.live(scope()).size());
        assertEquals(Long.toString(AUTHOR), store.find(scope(), id(1)).orElseThrow().authorId());
        assertEquals(0, adapter.rejectedAuthorCount());
    }

    @Test void otherAuthorsAreIgnoredWithoutAnyWriteOrReply() {
        post(CHAT, 1, AUTHOR, 0, "Synthetic: the pediatrician on Herzl St. is great");
        post(CHAT, 2, STRANGER, 10, SECRET_TEXT);
        assertEquals(1, store.live(scope()).size(), "the stranger's message is not memory");
        assertTrue(store.find(scope(), id(2)).isEmpty());
        assertTrue(gw.sent.isEmpty(), "no reply is sent to a stranger");
        assertEquals(1, adapter.rejectedAuthorCount());
    }

    /** TASK-046 (B46R3-13): a removed author's edit withdraws the old revision; their new text is never stored. */
    @Test void aRemovedAuthorsEditWithdrawsTheOldRevisionWithoutStoringTheNewText() {
        post(CHAT, 1, AUTHOR, 0, "Synthetic: the picnic is at the lake on Friday");
        adapter = build(Set.of());                                                       // AUTHOR removed from the allow-list
        ObjectNode edit = message(CHAT, 1, AUTHOR, 0, SECRET_TEXT);
        edit.put("edit_date", T0 + 30);
        handle("edited_message", edit);
        assertEquals("", store.find(scope(), id(1)).orElseThrow().text(), "old revision withdrawn");
        assertTrue(gw.sent.isEmpty());
        assertEquals(1, adapter.rejectedAuthorCount());
    }

    @Test void aStrangerCannotWithdrawSomeoneElsesMessage() {
        post(CHAT, 1, AUTHOR, 0, "Synthetic: the picnic is at the lake on Friday");
        ObjectNode edit = message(CHAT, 1, STRANGER, 0, SECRET_TEXT);
        edit.put("edit_date", T0 + 30);
        handle("edited_message", edit);
        assertEquals("Synthetic: the picnic is at the lake on Friday", store.find(scope(), id(1)).orElseThrow().text());
    }

    @Test void otherAuthorsCannotUseCommands() {
        post(CHAT, 1, AUTHOR, 0, "Synthetic: the pediatrician on Herzl St. is great");
        ObjectNode forget = message(CHAT, 2, STRANGER, 10, "/forget");
        forget.putObject("reply_to_message").put("message_id", 1);
        handle("message", forget);
        post(CHAT, 3, STRANGER, 20, "/ask pediatrician");
        post(CHAT, 4, STRANGER, 30, "/plan Synthetic party");
        post(CHAT, 5, STRANGER, 40, "/ask@ExampleDitaBot pediatrician");
        assertFalse(store.find(scope(), id(1)).orElseThrow().deleted(), "a stranger cannot /forget");
        assertTrue(gw.sent.isEmpty(), "no /ask answer, no plan, no error reply to a stranger");
        assertTrue(adapter.plan("p1").isEmpty());
        assertEquals(4, adapter.rejectedAuthorCount());
    }

    @Test void otherAuthorsCannotEditAllowListedMessages() {
        post(CHAT, 1, AUTHOR, 0, "Synthetic: meeting at 5");
        ObjectNode edit = message(CHAT, 1, STRANGER, 0, SECRET_TEXT);
        edit.put("edit_date", T0 + 60);
        handle("edited_message", edit);
        assertEquals("Synthetic: meeting at 5", store.find(scope(), id(1)).orElseThrow().text());
    }

    @Test void botsAnonymousAdminsChannelsAndSendersWithoutIdAreRejected() {
        ObjectNode bot = message(CHAT, 1, AUTHOR, 0, SECRET_TEXT);
        ((ObjectNode) bot.get("from")).put("is_bot", true);                           // even with the allowed id
        handle("message", bot);
        ObjectNode anonymous = message(CHAT, 2, 1087968824L, 0, SECRET_TEXT);        // GroupAnonymousBot
        anonymous.putObject("sender_chat").put("id", CHAT).put("type", "group");
        handle("message", anonymous);
        ObjectNode asChannel = message(CHAT, 3, AUTHOR, 0, SECRET_TEXT);              // allowed person posting as a channel
        asChannel.putObject("sender_chat").put("id", -1009999L).put("type", "channel");
        handle("message", asChannel);
        ObjectNode noFrom = message(CHAT, 4, AUTHOR, 0, SECRET_TEXT);
        noFrom.remove("from");
        handle("message", noFrom);
        ObjectNode noBotFlag = message(CHAT, 5, AUTHOR, 0, SECRET_TEXT);
        ((ObjectNode) noBotFlag.get("from")).remove("is_bot");                       // fail closed on malformed senders
        handle("message", noBotFlag);
        assertTrue(store.live(scope()).isEmpty());
        assertTrue(gw.sent.isEmpty());
        assertEquals(5, adapter.rejectedAuthorCount());
    }

    @Test void allowListedAuthorInAnyOtherChatIsIgnored() {
        post(OTHER_CHAT, 1, AUTHOR, 0, SECRET_TEXT);
        assertTrue(store.live(new GroupScope("tg" + Math.abs(OTHER_CHAT))).isEmpty());
        assertTrue(gw.sent.isEmpty());
    }

    @Test void buttonPressesFromOtherAuthorsAreRejected() {
        post(CHAT, 1, AUTHOR, 0, "/plan Synthetic birthday");
        post(CHAT, 2, AUTHOR, 1, "/task Bring the cake");
        post(CHAT, 3, AUTHOR, 2, "/claim 1");
        post(CHAT, 4, AUTHOR, 3, "/approval");
        List<TelegramGateway.Button> b = gw.buttons.get(gw.buttons.size() - 1);
        assertFalse(b.isEmpty(), "approval buttons were offered");
        EventPlan.Status before = adapter.plan("p1").orElseThrow().status();
        ObjectNode cq = JSON.createObjectNode();
        cq.put("id", "cq1").put("data", b.get(0).callbackData());
        cq.putObject("from").put("id", STRANGER).put("is_bot", false).put("first_name", "Synthetic");
        ObjectNode m = cq.putObject("message");
        m.put("message_id", 5000).put("date", T0);
        m.putObject("chat").put("id", CHAT).put("type", "group");
        handle("callback_query", cq);
        assertEquals("This button is not valid.", gw.answers.get(gw.answers.size() - 1));
        assertEquals(before, adapter.plan("p1").orElseThrow().status(), "a stranger's press changes nothing");
        assertEquals(1, adapter.rejectedAuthorCount());
    }

    @Test void anEmptyAuthorAllowListAcceptsNobody() {
        adapter = build(Set.of());
        post(CHAT, 1, AUTHOR, 0, SECRET_TEXT);
        post(CHAT, 2, AUTHOR, 1, "/ask anything");
        assertTrue(store.live(scope()).isEmpty());
        assertTrue(gw.sent.isEmpty());
    }

    @Test void rejectionsNeverLogIdsOrMessageText() {
        post(CHAT, 1, STRANGER, 0, SECRET_TEXT);
        post(CHAT, 2, STRANGER, 1, "/ask " + SECRET_TEXT);
        assertFalse(logs.lines.isEmpty(), "rejections are logged");
        for (String line : logs.lines) {
            assertFalse(line.contains(SECRET_TEXT), "no message text in logs");
            assertFalse(line.contains(Long.toString(STRANGER)), "no author id in logs");
            assertFalse(line.contains(Long.toString(Math.abs(CHAT))), "no chat id in logs");
        }
    }

    // ---------- Natasha B1: nothing from before the run is processed (backlog, redelivery after restart) ----------

    @Test void updatesFromBeforeTheRunAreAcknowledgedButNeverProcessed() {
        adapter = build(Set.of(AUTHOR), Instant.ofEpochSecond(T0 + 100));          // the run "started" at T0+100
        post(CHAT, 1, AUTHOR, 0, SECRET_TEXT);                                        // pending backlog message
        post(CHAT, 2, AUTHOR, 50, "/ask anything");                                   // old command: must not be answered now
        ObjectNode oldEdit = message(CHAT, 1, AUTHOR, 0, SECRET_TEXT + " edited");
        oldEdit.put("edit_date", T0 + 200);                                          // edited during the run, sent before it
        handle("edited_message", oldEdit);
        ObjectNode noDate = message(CHAT, 3, AUTHOR, 0, SECRET_TEXT);
        noDate.remove("date");                                                        // fail closed on a missing date
        handle("message", noDate);
        assertTrue(store.live(scope()).isEmpty(), "nothing from before the start is memory");
        assertTrue(gw.sent.isEmpty(), "no reply to old commands");
        assertEquals(4, adapter.staleUpdateCount());
        for (String line : logs.lines) assertFalse(line.contains(SECRET_TEXT), "no text in logs");

        post(CHAT, 4, AUTHOR, 100, "Synthetic: typed exactly at the start");          // date == start is accepted
        post(CHAT, 5, AUTHOR, 160, "Synthetic: typed during the run");
        assertEquals(2, store.live(scope()).size());
    }

    @Test void aRestartNeverReprocessesTheLastBatch() {
        adapter = build(Set.of(AUTHOR), Instant.ofEpochSecond(T0 + 100));
        post(CHAT, 1, AUTHOR, 120, "Synthetic: during the first run");
        assertEquals(1, store.live(scope()).size());
        store = new InMemoryMessageStore();                                           // process restart: memory is empty
        adapter = build(Set.of(AUTHOR), Instant.ofEpochSecond(T0 + 300));             // new start time
        updateId = 1;                                                                 // Telegram redelivers the unacknowledged batch
        post(CHAT, 1, AUTHOR, 120, "Synthetic: during the first run");
        post(CHAT, 2, AUTHOR, 130, "/ask first run");
        assertTrue(store.live(scope()).isEmpty(), "redelivered updates are not ingested again");
        assertTrue(gw.sent.isEmpty(), "and not answered twice");
        assertEquals(2, adapter.staleUpdateCount());
    }

    @Test void buttonsSentDuringTheRunStillWork() {
        adapter = build(Set.of(AUTHOR), Instant.ofEpochSecond(T0));
        post(CHAT, 1, AUTHOR, 10, "/plan Synthetic birthday");
        assertTrue(adapter.plan("p1").isPresent());
        assertEquals(0, adapter.staleUpdateCount());
    }
}
