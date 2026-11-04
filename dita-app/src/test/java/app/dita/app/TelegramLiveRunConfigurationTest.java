package app.dita.app;

import app.dita.core.model.GroupScope;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import app.dita.muse.MuseClient;
import app.dita.muse.MuseKnowledgeExtractor;
import app.dita.telegram.TelegramAdapter;
import app.dita.telegram.TelegramGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-040: boots the real DitaApplication with EXACTLY the arguments of scripts/telegram-run.sh (keep in sync) and the
 * environment that script passes (token + callback secret only; synthetic values here). The Bot API is replaced by an
 * in-process fake: no network, no live polling, no Muse.
 */
class TelegramLiveRunConfigurationTest {
    static final long CHAT = -1001000000054L, AUTHOR = 1001L, STRANGER = 9168992916L;
    /** Same list as ARGS in scripts/telegram-run.sh. */
    static final String[] RUN_ARGS = {"--spring.main.keep-alive=true",
            "--dita.telegram.enabled=true", "--dita.telegram.bot-username=ExampleDitaBot",
            "--dita.telegram.allowed-chat-ids=" + CHAT, "--dita.telegram.allowed-author-ids=" + AUTHOR,
            "--dita.muse.enabled=false", "--dita.processing.tiers.t1.provider=NONE", "--dita.processing.tiers.t2.provider=NONE",
            "--dita.storage.backend=IN_MEMORY", "--dita.coordination.enabled=false"};
    static final Map<String, String> RUN_ENV = Map.of(
            "TELEGRAM_BOT_TOKEN", "123456789:SYNTHETICtokenSYNTHETICtokenSYNTHETIC",
            "DITA_CALLBACK_SECRET", "0123456789abcdef0123456789abcdef0123456789abcdef");   // MODEL_API_KEY deliberately absent
    static final ObjectMapper JSON = new ObjectMapper();

    static final class FakeGateway implements TelegramGateway {
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        public List<JsonNode> getUpdates(long o, int t, int l) throws InterruptedException { Thread.sleep(50); return List.of(); }
        public long sendMessage(long c, String text, Long r, List<Button> b) { sent.add(text); return 9000 + sent.size(); }
        public void answerCallbackQuery(String id, String text) { }
        public Set<Long> chatAdministrators(long c) { return Set.of(); }
    }
    static final FakeGateway GW = new FakeGateway();

    static class Fakes {
        @Bean @Primary TelegramGateway fakeTelegramGateway() { return GW; }
    }

    ConfigurableApplicationContext ctx;
    long updateId = 1;

    @BeforeEach void boot() { GW.sent.clear(); ctx = new SpringApplicationBuilder(DitaApplication.class, Fakes.class)
            .initializers(c -> c.getBeanFactory().registerSingleton("ditaEnv", (Function<String, String>) RUN_ENV::get))   // the script's env
            .run(RUN_ARGS); }
    @AfterEach void close() { if (ctx != null) ctx.close(); }

    void post(long from, long mid, long date, String text) {
        ObjectNode m = JSON.createObjectNode();
        m.put("message_id", mid).put("date", date).put("text", text);
        m.putObject("chat").put("id", CHAT).put("type", "group").put("title", "SyntheticDemoGroup");
        m.putObject("from").put("id", from).put("is_bot", false).put("first_name", "Synthetic");
        ObjectNode u = JSON.createObjectNode();
        u.put("update_id", updateId++);
        u.set("message", m);
        ctx.getBean(TelegramAdapter.class).handle(u);
    }

    @Test void runConfigurationIsTelegramOnInMemoryAndMuseOff() {
        assertNotNull(ctx.getBean(TelegramAdapter.class));
        assertInstanceOf(InMemoryMessageStore.class, ctx.getBean(MessageStore.class));
        assertTrue(ctx.getBeansOfType(MuseClient.class).isEmpty(), "no Muse client in the live run");
        assertTrue(ctx.getBeansOfType(MuseKnowledgeExtractor.class).isEmpty(), "no Muse extractor in the live run");
    }

    @Test void allowedAuthorIsAnsweredStrangersAndBacklogAreIgnored() {
        long now = Instant.now().getEpochSecond();
        post(AUTHOR, 1, now - 3600, "Synthetic backlog from before the run");               // pending before start
        post(STRANGER, 2, now + 1, "Synthetic stranger: the pediatrician is Dr. Levi");
        post(AUTHOR, 3, now + 2, "Synthetic: Dr. Levi on Herzl St. is excellent for babies");
        post(STRANGER, 4, now + 3, "/ask pediatrician");
        post(AUTHOR, 5, now + 4, "/ask Is Dr. Levi recommended?");
        MessageStore store = ctx.getBean(MessageStore.class);
        GroupScope g = new GroupScope("tg" + Math.abs(CHAT));
        assertEquals(1, store.live(g).size(), "only the allowed author's in-run message is memory");
        assertEquals(1, GW.sent.size(), "exactly one reply: the allowed author's /ask");
        assertTrue(GW.sent.get(0).contains("excellent"), "the answer quotes the allowed author's synthetic message");
        assertFalse(GW.sent.get(0).contains("stranger") || GW.sent.get(0).contains("backlog"));
        TelegramAdapter a = ctx.getBean(TelegramAdapter.class);
        assertEquals(1, a.staleUpdateCount());
        assertEquals(2, a.rejectedAuthorCount());
    }
}
