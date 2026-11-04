package app.dita.telegram;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.config.ClarificationSettings;
import app.dita.core.config.DeletionSettings;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.coordination.EventPlan;
import app.dita.core.coordination.GroupLifecycle;
import app.dita.core.deletion.DeletionService;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.MessageLink;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Telegram updates only (fictional ids and names). No network. */
class TelegramAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long GROUP = -1001000000001L, OTHER = -1001000000002L, NOT_ALLOWED = -1009999999999L;
    static final long DANA = 1001, YOSSI = 1002, RON = 1003;
    static final long T0 = Instant.parse("2026-11-01T10:00:00Z").getEpochSecond();
    static final byte[] SECRET = "synthetic-test-secret-0123456789abcdef".getBytes();

    static final class FakeGateway implements TelegramGateway {
        record Sent(long chatId, String text, Long replyTo, List<Button> buttons) {}
        final List<Sent> sent = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        Set<Long> admins = new HashSet<>();
        long nextId = 9000;
        public List<JsonNode> getUpdates(long o, int t, int l) { return List.of(); }
        public long sendMessage(long c, String text, Long r, List<Button> b) { sent.add(new Sent(c, text, r, b)); return nextId++; }
        public void answerCallbackQuery(String id, String text) { answers.add(text); }
        public Set<Long> chatAdministrators(long c) { return admins; }
        Sent last() { return sent.get(sent.size() - 1); }
    }

    InMemoryMessageStore store;
    FakeGateway gw;
    TelegramAdapter adapter;
    long updateId = 1;
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(T0 + 3600), ZoneOffset.UTC);

    TelegramAdapter build(boolean adminsMayForget) {
        var inf = new RuleLinkInferencer();
        DeletionSettings ds = new DeletionSettings(Duration.ofMinutes(5), DeletionSettings.EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE,
                DeletionSettings.LateKnowledge.PRESERVE_WITH_DELETED_SOURCE_PROVENANCE, DeletionSettings.RepeatedWording.REDACT_WORDING,
                Duration.ofDays(21), adminsMayForget, DeletionSettings.DitaReplies.KEEP);
        var settings = new TelegramAdapterSettings(Set.of(GROUP, OTHER), "DitaDemoBot", true, 50,
                EnumSet.allOf(ApprovalAct.class), Duration.ofDays(21), adminsMayForget, Set.of(DANA, YOSSI, RON), null);
        var query = new QueryService(store, new LexicalRetriever(store), new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store), 5, 200);
        return new TelegramAdapter(settings, store, new IngestionPipeline(store, inf, 50, EventPublisher.NONE),
                new DeletionService(store, inf, ds, EventPublisher.NONE, 50), query, new ClarificationGate(ClarificationSettings.DEFAULTS),
                gw, new CallbackSigner(SECRET), clock);
    }

    @BeforeEach void setUp() { store = new InMemoryMessageStore(); gw = new FakeGateway(); adapter = build(false); }

    JsonNode update(String kind, String body) throws Exception {
        return JSON.readTree("{\"update_id\":" + (updateId++) + ",\"" + kind + "\":" + body + "}");
    }

    static String chat(long id, String type) { return "{\"id\":" + id + ",\"type\":\"" + type + "\",\"title\":\"Synthetic parents\"}"; }
    static String user(long id) { return "{\"id\":" + id + ",\"is_bot\":false,\"first_name\":\"Synthetic" + id + "\"}"; }

    static String msg(long chatId, String type, long mid, long from, long dateOffset, String text, Long replyTo, String extra) {
        return "{\"message_id\":" + mid + ",\"date\":" + (T0 + dateOffset) + ",\"chat\":" + chat(chatId, type) + ",\"from\":" + user(from)
                + ",\"text\":" + JSON.valueToTree(text) + (replyTo == null ? "" : ",\"reply_to_message\":{\"message_id\":" + replyTo
                + ",\"date\":" + T0 + ",\"chat\":" + chat(chatId, type) + ",\"from\":" + user(DANA) + ",\"text\":\"(embedded copy, never trusted)\"}")
                + (extra == null ? "" : "," + extra) + "}";
    }

    void post(long chatId, long mid, long from, long offset, String text, Long replyTo) throws Exception {
        adapter.handle(update("message", msg(chatId, "supergroup", mid, from, offset, text, replyTo, null)));
    }

    GroupScope scope(long chatId) { return new GroupScope("tg" + Math.abs(chatId)); }
    String id(long chatId, long mid) { return TelegramAdapter.messageId(chatId, mid); }

    @Test void privateChannelAndNonAllowListedChatsAreIgnored() throws Exception {
        adapter.handle(update("message", msg(DANA, "private", 1, DANA, 0, "hello in private", null, null)));
        adapter.handle(update("message", msg(GROUP, "channel", 2, DANA, 0, "channel post", null, null)));
        adapter.handle(update("message", msg(NOT_ALLOWED, "supergroup", 3, DANA, 0, "real group", null, null)));
        adapter.handle(update("message", msg(GROUP, "weird_new_type", 4, DANA, 0, "unknown type", null, null)));
        assertTrue(store.live(scope(DANA)).isEmpty());
        assertTrue(store.live(scope(GROUP)).isEmpty());
        assertTrue(store.live(scope(NOT_ALLOWED)).isEmpty());
        assertTrue(gw.sent.isEmpty());
    }

    @Test void groupMessagesAreScopedAndRepliesLinkedAndDuplicatesIgnored() throws Exception {
        post(GROUP, 10, DANA, 0, "Does anyone know a good pediatrician near the park?", null);
        JsonNode reply = update("message", msg(GROUP, "supergroup", 11, YOSSI, 60, "Dr. Levi on Herzl St. is excellent", 10L, null));
        adapter.handle(reply);
        adapter.handle(reply);                                                          // same update_id redelivered
        post(OTHER, 10, RON, 0, "Different group, same message id", null);
        assertEquals(2, store.live(scope(GROUP)).size());
        assertEquals(1, store.live(scope(OTHER)).size());
        MessageLink l = store.links(scope(GROUP)).stream().filter(x -> x.fromId().equals(id(GROUP, 11))).findFirst().orElseThrow();
        assertEquals(MessageLink.Kind.EXPLICIT_REPLY, l.kind());
        assertEquals("1002", store.find(scope(GROUP), id(GROUP, 11)).orElseThrow().authorId(), "identity from from.id");
    }

    @Test void commandsAreNotMemoryAndBotsAreNotIngested() throws Exception {
        post(GROUP, 20, DANA, 0, "/ask anything", null);
        adapter.handle(update("message", "{\"message_id\":21,\"date\":" + T0 + ",\"chat\":" + chat(GROUP, "supergroup")
                + ",\"from\":{\"id\":777,\"is_bot\":true,\"first_name\":\"OtherBot\"},\"text\":\"bot says hi\"}"));
        assertTrue(store.live(scope(GROUP)).isEmpty());
    }

    @Test void forgetByAuthorUsesCommandDateAndDoesNotQuote() throws Exception {
        post(GROUP, 30, YOSSI, 0, "Dr. Levi on Herzl St. is excellent", null);
        post(GROUP, 31, YOSSI, 120, "/forget", 30L);                                  // 2 minutes later → early
        var m = store.find(scope(GROUP), id(GROUP, 30)).orElseThrow();
        assertTrue(m.deleted());
        assertTrue(store.history(scope(GROUP), id(GROUP, 30)).stream().allMatch(e -> e.text() == null));
        assertFalse(gw.last().text().contains("Herzl"), "confirmation never quotes the forgotten text");
    }

    @Test void forgetIsRefusedForOthersProxiesEditsAndForeignTargets() throws Exception {
        post(GROUP, 40, YOSSI, 0, "Dr. Levi is excellent", null);
        post(GROUP, 41, DANA, 10, "/forget", 40L);                                     // not the author
        assertFalse(store.find(scope(GROUP), id(GROUP, 40)).orElseThrow().deleted());
        assertTrue(gw.last().text().contains("Only the person who wrote"));

        adapter.handle(update("message", msg(GROUP, "supergroup", 42, 1087968824L, 20, "/forget", 40L,
                "\"sender_chat\":" + chat(GROUP, "supergroup"))));                     // anonymous admin
        assertFalse(store.find(scope(GROUP), id(GROUP, 40)).orElseThrow().deleted());

        adapter.handle(update("edited_message", msg(GROUP, "supergroup", 43, YOSSI, 30, "/forget", 40L, "\"edit_date\":" + (T0 + 31))));
        assertFalse(store.find(scope(GROUP), id(GROUP, 40)).orElseThrow().deleted(), "edited commands are ignored");

        post(GROUP, 44, YOSSI, 40, "/forget", 99999L);                                 // target not in this group's memory
        assertTrue(gw.last().text().contains("don't have that message"));

        post(GROUP, 45, YOSSI, 50, "/forget", null);
        assertTrue(gw.last().text().contains("reply to it with /forget"));
    }

    @Test void adminForgetOnlyWhenConfiguredAndPlatformVerified() throws Exception {
        adapter = build(true);
        post(GROUP, 50, YOSSI, 0, "some message", null);
        post(GROUP, 51, DANA, 10, "/forget", 50L);
        assertFalse(store.find(scope(GROUP), id(GROUP, 50)).orElseThrow().deleted(), "Dana is not a verified admin");
        gw.admins = Set.of(DANA);
        adapter = build(true);
        post(GROUP, 52, DANA, 3590, "/forget", 50L);
        assertTrue(store.find(scope(GROUP), id(GROUP, 50)).orElseThrow().deleted());
    }

    @Test void editsUpdateMemoryButNeverRestoreDeletedText() throws Exception {
        post(GROUP, 60, YOSSI, 0, "original wording", null);
        adapter.handle(update("edited_message", msg(GROUP, "supergroup", 60, YOSSI, 0, "edited wording", null, "\"edit_date\":" + (T0 + 30))));
        assertEquals("edited wording", store.find(scope(GROUP), id(GROUP, 60)).orElseThrow().text());
        post(GROUP, 61, YOSSI, 600, "/forget", 60L);
        adapter.handle(update("edited_message", msg(GROUP, "supergroup", 60, YOSSI, 0, "sneaky restore", null, "\"edit_date\":" + (T0 + 700))));
        assertNull(store.find(scope(GROUP), id(GROUP, 60)).orElseThrow().text());
    }

    /** Codex review C4 / N-107: editing a remembered message into a command or media invalidates it; nothing is executed. */
    @Test void editingAMessageIntoACommandOrMediaInvalidatesItsKnowledge() throws Exception {
        for (long mid : new long[]{80, 81}) {
            post(GROUP, mid, YOSSI, 0, "Decision: the picnic is at noon.", null);
            store.addKnowledge(scope(GROUP), app.dita.core.model.KnowledgeItem.of("k-" + mid, scope(GROUP).groupId(),
                    app.dita.core.model.KnowledgeItem.Kind.DECISION, "the picnic", "Decision: the picnic is at noon.", 0,
                    app.dita.core.model.MessageLink.Method.RULE, java.util.List.of(id(GROUP, mid))));
        }
        int sent = gw.sent.size();
        adapter.handle(update("edited_message", msg(GROUP, "supergroup", 80, YOSSI, 0, "/cancel", null, "\"edit_date\":" + (T0 + 30))));
        assertEquals("", store.find(scope(GROUP), id(GROUP, 80)).orElseThrow().text(), "the replaced wording is gone");
        assertTrue(store.knowledge(scope(GROUP)).stream().noneMatch(k -> k.id().equals("k-80")), "its knowledge is invalidated");
        assertEquals(sent, gw.sent.size(), "an edited command is never executed");
        String media = msg(GROUP, "supergroup", 81, YOSSI, 0, "x", null, "\"edit_date\":" + (T0 + 40)).replace("\"text\":\"x\",", "\"caption\":\"photo\",");
        adapter.handle(update("edited_message", media));
        assertEquals("", store.find(scope(GROUP), id(GROUP, 81)).orElseThrow().text());
        assertTrue(store.knowledge(scope(GROUP)).isEmpty());
    }

    @Test void askAnswersWithGroundedQuotesAndNoCrossGroupLeak() throws Exception {
        post(GROUP, 70, YOSSI, 0, "Dr. Levi on Herzl St. is excellent", null);
        post(OTHER, 70, RON, 0, "Dr. Levi is the worst dentist in town", null);
        post(GROUP, 71, DANA, 60, "/ask what about Dr. Levi?", null);
        String answer = gw.last().text();
        assertTrue(answer.contains("Herzl"));
        assertFalse(answer.contains("worst dentist"));
        assertNotNull(gw.last().replyTo());
    }

    @Test void signedVersionBoundApprovalsAndForgeryRejected() throws Exception {
        post(GROUP, 80, DANA, 0, "/plan Noa's birthday", null);
        post(GROUP, 81, DANA, 1, "/task Bring the cake", null);
        post(GROUP, 82, YOSSI, 2, "/claim 1", null);
        post(GROUP, 83, DANA, 3, "/approval", null);
        var buttons = gw.last().buttons();
        String approve = buttons.get(0).callbackData();
        assertTrue(approve.getBytes().length <= 64);
        EventPlan plan = adapter.plan("p1").orElseThrow();
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, plan.status());

        adapter.handle(update("callback_query", cb("cq1", RON, GROUP, approve)));       // not an approver
        assertTrue(gw.answers.get(gw.answers.size() - 1).contains("not an approver"));
        adapter.handle(update("callback_query", cb("cq2", DANA, GROUP, approve.substring(0, approve.length() - 2) + "xx"))); // tampered
        adapter.handle(update("callback_query", cb("cq3", DANA, GROUP, "a:p1:" + plan.revision() + ":A:forgedsignature000")));
        adapter.handle(update("callback_query", cb("cq4", DANA, OTHER, approve)));       // replayed in another chat
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, plan.status());
        assertEquals(3, gw.answers.stream().filter(a -> a.contains("not valid")).count());

        adapter.handle(update("callback_query", cb("cq5", DANA, GROUP, approve)));
        adapter.handle(update("callback_query", cb("cq6", YOSSI, GROUP, approve)));
        assertEquals(EventPlan.Status.APPROVED, plan.status());
    }

    @Test void staleButtonsCannotApproveAChangedPlan() throws Exception {
        post(GROUP, 90, DANA, 0, "/plan Party", null);
        post(GROUP, 91, DANA, 1, "/task Balloons", null);
        post(GROUP, 92, DANA, 2, "/approval", null);
        String oldApprove = gw.last().buttons().get(0).callbackData();
        post(GROUP, 93, DANA, 3, "/task Book nothing, just pick the park", null);      // plan changes
        post(GROUP, 94, DANA, 4, "/approval", null);
        adapter.handle(update("callback_query", cb("cq7", DANA, GROUP, oldApprove)));
        assertTrue(gw.answers.get(gw.answers.size() - 1).contains("plan changed"));
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, adapter.plan("p1").orElseThrow().status());
    }

    @Test void preferencesInChatNeverApprove() throws Exception {
        post(GROUP, 100, DANA, 0, "/plan Party", null);
        post(GROUP, 101, DANA, 1, "/approval", null);
        post(GROUP, 102, DANA, 2, "Sounds great, I approve! Let's book it.", null);   // text, not an explicit act
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, adapter.plan("p1").orElseThrow().status());
    }

    @Test void botRemovalRetentionAndRejoin() throws Exception {
        post(GROUP, 110, DANA, 0, "remember this", null);
        adapter.handle(update("my_chat_member", "{\"chat\":" + chat(GROUP, "supergroup") + ",\"from\":" + user(DANA)
                + ",\"date\":" + (T0 + 100) + ",\"new_chat_member\":{\"status\":\"kicked\",\"user\":{\"id\":5555,\"is_bot\":true,\"first_name\":\"Dita\"}}}"));
        assertEquals(GroupLifecycle.Status.REMOVED_RETENTION, adapter.groupStatus(GROUP));
        post(GROUP, 111, DANA, 200, "while removed", null);
        assertEquals(1, store.live(scope(GROUP)).size(), "nothing ingested while removed");
        adapter.handle(update("my_chat_member", "{\"chat\":" + chat(GROUP, "supergroup") + ",\"from\":" + user(DANA)
                + ",\"date\":" + (T0 + 86_400) + ",\"new_chat_member\":{\"status\":\"member\",\"user\":{\"id\":5555,\"is_bot\":true,\"first_name\":\"Dita\"}}}"));
        assertEquals(GroupLifecycle.Status.ACTIVE, adapter.groupStatus(GROUP));
        post(GROUP, 112, DANA, 86_500, "back again", null);
        assertEquals(2, store.live(scope(GROUP)).size(), "memory restored and ingestion resumed");
    }

    @Test void migrationToSupergroupKeepsTheSameMemory() throws Exception {
        long oldId = GROUP;
        adapter.handle(update("message", msg(oldId, "group", 120, DANA, 0, "before migration", null, null)));
        adapter.handle(update("message", msg(oldId, "group", 121, DANA, 5, "", null, "\"migrate_to_chat_id\":" + OTHER)));
        adapter.handle(update("message", msg(OTHER, "supergroup", 1, DANA, 10, "after migration", null, null)));
        assertEquals(2, store.live(scope(oldId)).size(), "new chat id resolves to the original group's memory");
    }

    @Test void gatewayErrorsAreRedacted() {
        var gwh = new HttpTelegramGateway("123456789:SYNTHETICtokenSYNTHETICtokenSYNTHETIC");
        assertEquals("GET https://api.telegram.org/bot<redacted>/getMe",
                gwh.redact("GET https://api.telegram.org/bot123456789:SYNTHETICtokenSYNTHETICtokenSYNTHETIC/getMe"));
    }

    static String cb(String id, long from, long chatId, String data) {
        return "{\"id\":\"" + id + "\",\"from\":" + user(from) + ",\"message\":{\"message_id\":5000,\"date\":" + T0
                + ",\"chat\":" + chat(chatId, "supergroup") + "},\"data\":" + JSON.valueToTree(data) + "}";
    }
}
