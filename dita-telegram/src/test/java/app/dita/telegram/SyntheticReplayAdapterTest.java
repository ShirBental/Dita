package app.dita.telegram;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.config.DeletionSettings;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.deletion.DeletionService;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.config.ClarificationSettings;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.store.InMemoryMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-051: synthetic replay mode of the real adapter. Fictional ids and text; no network. */
class SyntheticReplayAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CHAT = -1001000000051L, DANA = 1001, YOSSI = 1002, OPERATOR = 1009;
    static final long T0 = Instant.parse("2026-11-01T10:00:00Z").getEpochSecond();

    final InMemoryMessageStore store = new InMemoryMessageStore();
    final TelegramAdapterTest.FakeGateway gw = new TelegramAdapterTest.FakeGateway();
    final List<Message> offered = new ArrayList<>();
    final List<String> approvals = new ArrayList<>();
    long updateId = 1;

    TelegramAdapter adapter(boolean replay) {
        var inf = new RuleLinkInferencer();
        var settings = new TelegramAdapterSettings(Set.of(CHAT), "DitaDemoBot", false, 50, EnumSet.allOf(ApprovalAct.class),
                Duration.ofDays(21), false, Set.of(DANA, YOSSI, OPERATOR), null);
        var query = new QueryService(store, new LexicalRetriever(store),
                new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, m) -> Optional.empty(), store), 5, 200);
        TelegramAdapter.ReplayOptions options = !replay ? null : new TelegramAdapter.ReplayOptions(Set.of(OPERATOR), "abc123-run1", new TelegramAdapter.ExtractionHooks() {
            public void ingested(GroupScope s, Message m) { offered.add(m); }
            public int approvePending(GroupScope s, String ref) { approvals.add(s.groupId() + "|" + ref); return 1; }
        });
        return new TelegramAdapter(settings, store, new IngestionPipeline(store, inf, 50, EventPublisher.NONE),
                new DeletionService(store, inf, DeletionSettings.DEFAULTS, EventPublisher.NONE, 50), query,
                new ClarificationGate(ClarificationSettings.DEFAULTS), gw, new CallbackSigner("synthetic-test-secret-0123456789abcdef".getBytes()),
                Clock.fixed(Instant.ofEpochSecond(T0 + 3600), ZoneOffset.UTC), app.dita.core.model.GroupRegistry.NONE, options);
    }

    void post(TelegramAdapter a, long mid, long from, String text) throws Exception {
        a.handle(JSON.readTree("{\"update_id\":" + (updateId++) + ",\"message\":" + TelegramAdapterTest.msg(CHAT, "supergroup", mid, from, mid, text, null, null) + "}"));
    }

    @Test void replayStoresSyntheticMessagesInASeparateNamespaceAndOffersThemToExtraction() throws Exception {
        var a = adapter(true);
        post(a, 1, DANA, "Dr. Levi on Herzl St. is excellent for babies");
        GroupScope replayScope = new GroupScope("tgr" + Math.abs(CHAT));
        Message m = store.find(replayScope, "tgr" + Math.abs(CHAT) + "m1").orElseThrow();
        assertEquals(SourceKind.SYNTHETIC, m.sourceKind());
        assertTrue(store.find(new GroupScope("tg" + Math.abs(CHAT)), "tg" + Math.abs(CHAT) + "m1").isEmpty(), "never in the live namespace");
        assertEquals(List.of(m.id()), offered.stream().map(Message::id).toList());
    }

    @Test void extractApprovesOnlyForAConfiguredApproverAndRecordsWhoApproved() throws Exception {
        var a = adapter(true);
        post(a, 1, DANA, "Dr. Levi on Herzl St. is excellent for babies");
        post(a, 2, YOSSI, "/extract");
        assertTrue(approvals.isEmpty(), "an allow-listed author who is not an approver cannot approve");
        assertTrue(gw.last().text().contains("Only a configured extraction approver"));
        post(a, 3, OPERATOR, "/extract");
        assertEquals(List.of("tgr" + Math.abs(CHAT) + "|telegram-replay:abc123-run1:" + Math.abs(CHAT) + ":m3:by" + OPERATOR), approvals);
        assertTrue(store.find(new GroupScope("tgr" + Math.abs(CHAT)), "tgr" + Math.abs(CHAT) + "m3").isEmpty(), "commands are not memory");
    }

    @Test void liveModeNeverOffersMessagesAndIgnoresExtract() throws Exception {
        var a = adapter(false);
        post(a, 1, DANA, "Dr. Levi on Herzl St. is excellent for babies");
        post(a, 2, OPERATOR, "/extract");
        assertTrue(offered.isEmpty() && approvals.isEmpty());
        assertTrue(gw.sent.isEmpty(), "/extract does not exist on live Telegram");
        assertEquals(SourceKind.PLATFORM, store.find(new GroupScope("tg" + Math.abs(CHAT)), "tg" + Math.abs(CHAT) + "m1").orElseThrow().sourceKind());
    }

    @Test void replayGatewayRebasesDatesHoldsAtBarriersAndRedeliversUnacknowledgedUpdates() throws Exception {
        boolean[] idle = {false};
        List<JsonNode> lines = List.of(
                JSON.readTree("{\"update_id\":1,\"message\":{\"message_id\":1,\"date_offset_s\":5,\"chat\":{\"id\":" + CHAT + ",\"type\":\"supergroup\"},\"text\":\"a\"}}"),
                JSON.readTree("{\"replay\":\"await-idle\"}"),
                JSON.readTree("{\"update_id\":2,\"message\":{\"message_id\":2,\"date_offset_s\":9,\"chat\":{\"id\":" + CHAT + ",\"type\":\"supergroup\"},\"text\":\"b\"}}"));
        Clock clock = Clock.systemUTC();
        var g = new ReplayTelegramGateway(lines, clock, () -> idle[0], Duration.ofMillis(800));
        List<JsonNode> first = g.getUpdates(0, 1, 100);
        assertEquals(1, first.size());
        long date = first.get(0).path("message").path("date").asLong();
        assertTrue(date >= clock.instant().getEpochSecond() + 4, "rebased into the run, never before the adapter's start");
        assertFalse(first.get(0).path("message").has("date_offset_s"));
        assertEquals(1, g.getUpdates(0, 1, 100).size(), "not acknowledged → redelivered");
        long t = System.nanoTime();
        List<JsonNode> second = g.getUpdates(2, 1, 100);                  // acknowledges update 1; barrier not idle → times out
        assertTrue(System.nanoTime() - t >= Duration.ofMillis(700).toNanos(), "held at the barrier");
        assertEquals(2, second.get(0).path("update_id").asLong());
        assertTrue(g.finished());
        assertEquals(900_001, g.sendMessage(CHAT, "answer", 2L, List.of()));
        assertEquals("answer", g.transcript().get(0).text());
    }

    @Test void replayFilesMustDeclareThemselvesSyntheticAndErrorsNeverEchoContent() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Path bare = dir.resolve("replay-no-header.jsonl");
        java.nio.file.Files.writeString(bare, "{\"update_id\":1,\"message\":{\"text\":\"secret words\"}}\n");
        var e = assertThrows(IllegalArgumentException.class, () -> ReplayTelegramGateway.fromFile(bare, Clock.systemUTC(), () -> true, Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("synthetic-fixture"));
        java.nio.file.Path broken = dir.resolve("replay-broken.jsonl");
        java.nio.file.Files.writeString(broken, ReplayTelegramGateway.HEADER + "\n{\"text\":\"secret words\"\n");
        var e2 = assertThrows(IllegalArgumentException.class, () -> ReplayTelegramGateway.fromFile(broken, Clock.systemUTC(), () -> true, Duration.ofSeconds(1)));
        assertFalse(e2.getMessage().contains("secret") || e2.getCause() != null, "no content and no parser cause in the error (L-1)");
        java.nio.file.Path ok = dir.resolve("replay-ok.jsonl");
        java.nio.file.Files.writeString(ok, ReplayTelegramGateway.HEADER + "\n{\"replay\":\"await-idle\"}\n");
        assertTrue(ReplayTelegramGateway.fromFile(ok, Clock.systemUTC(), () -> true, Duration.ofSeconds(1)).approvalTag().matches("[0-9a-f]{16}-[0-9a-z]+"));
    }
}
