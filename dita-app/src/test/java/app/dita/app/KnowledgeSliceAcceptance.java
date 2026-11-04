package app.dita.app;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupDirectory;
import app.dita.core.model.GroupRegistry;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;
import app.dita.muse.MuseTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-043 vertical slice, driven through the RUNNING application (Spring context of DitaApplication):
 * synthetic messages → approved batch → RECORDED Muse response (no network) → evidence validation → knowledge
 * persistence → knowledge retrieval → source-grounded answer; plus rejection, duplicates, isolation, DEC-012 deletion
 * and (persistent backends) a restart. Subclasses choose the backend. A recorded response is NOT a live Muse
 * integration, and the in-memory run is NOT proof of PostgreSQL persistence.
 */
abstract class KnowledgeSliceAcceptance {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String FAKE_KEY = "synthetic-test-key-not-a-real-muse-key";

    /** Backend-specific properties (and whether knowledge can survive a restart). */
    abstract Map<String, Object> backendProperties();
    abstract boolean durable();

    /** Serves the recorded Muse reply; records every request body; never touches the network. */
    static final class RecordedMuse implements MuseTransport {
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> ids = new HashMap<>();
        @Override public Reply post(java.net.URI url, Map<String, String> headers, String body, Duration timeout) throws Exception {
            assertEquals("api.meta.ai", url.getHost(), "the client still targets the documented host (no call is made)");
            requests.add(body);
            String content;
            try (InputStream in = getClass().getResourceAsStream("/recorded/muse-knowledge-levi.content.json")) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (var e : ids.entrySet()) content = content.replace("${" + e.getKey() + "}", e.getValue());
            var env = JSON.createObjectNode().put("id", "recorded-1").put("model", "muse-spark-1.3");
            var choice = env.putArray("choices").addObject();
            choice.putObject("message").put("role", "assistant").put("content", content);
            choice.put("finish_reason", "stop");
            env.putObject("usage").put("prompt_tokens", 900).put("completion_tokens", 600).put("total_tokens", 1500);
            return new Reply(200, env.toString(), Map.of("x-request-id", "recorded"));
        }
    }

    final RecordedMuse muse = new RecordedMuse();
    final String run = UUID.randomUUID().toString().substring(0, 8);
    final GroupScope g = new GroupScope("ks-" + run), other = new GroupScope("ks-" + run + "-other");

    ConfigurableApplicationContext boot() {
        Map<String, Object> p = new HashMap<>(backendProperties());
        p.put("dita.muse.enabled", "true");
        p.put("dita.processing.dispatch.poll-interval", "50ms");
        Function<String, String> env = n -> n.equals("MODEL_API_KEY") ? FAKE_KEY : System.getenv(n);
        // Command-line arguments (highest precedence): builder.properties() would be DEFAULT properties, which the
        // committed application.yml overrides (e.g. dita.muse.enabled=false, dita.storage.backend=IN_MEMORY).
        String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(DitaApplication.class)
                .initializers(c -> {
                    c.getBeanFactory().registerSingleton("ditaEnv", env);
                    c.getBeanFactory().registerSingleton("museTransport", muse);
                }).run(args);
    }

    static <T> T bean(ConfigurableApplicationContext c, Class<T> t) { return c.getBean(t); }

    Message msg(GroupScope s, String id, String author, Instant at, String text) {
        return new Message(id, s.groupId(), author, at, null, text, SourceKind.SYNTHETIC, false);
    }

    void register(ConfigurableApplicationContext c, GroupScope s) {
        bean(c, GroupRegistry.class).ensureRegistered(s.groupId(), "synthetic", s.groupId(), GroupDirectory.ChatType.SYNTHETIC, SourceKind.SYNTHETIC);
    }

    ExtractionBatch awaitStatus(ExtractionService x, GroupScope s, String id, ExtractionBatch.Status want) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            ExtractionBatch b = x.batch(s, id).orElseThrow();
            if (b.status() == want) return b;
            if (b.status() == ExtractionBatch.Status.FAILED && want != ExtractionBatch.Status.FAILED) fail("batch FAILED: " + b.lastError());
            Thread.sleep(25);
        }
        fail("batch never reached " + want);
        return null;
    }

    static List<String> cited(GroundedAnswer a) { return a.citations().stream().map(Citation::messageId).toList(); }

    /** The whole slice. */
    void runSlice() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofMinutes(2));
        String m1 = g.groupId() + "-m1", m2 = g.groupId() + "-m2", m3 = g.groupId() + "-m3", o1 = other.groupId() + "-m1";
        muse.ids.putAll(Map.of("m1", m1, "m2", m2, "otherGroupMessage", o1));
        ConfigurableApplicationContext c = boot();
        try {
            register(c, g); register(c, other);
            IngestionPipeline ingest = bean(c, IngestionPipeline.class);
            ExtractionService x = bean(c, ExtractionService.class);
            MessageStore store = bean(c, MessageStore.class);
            QueryService queries = bean(c, QueryService.class);

            ingest.ingest(g, msg(g, m1, "dana", t0, "Dr. Levi on Herzl St. is excellent for babies, we've been going since our daughter was born"));
            ingest.ingest(g, msg(g, m2, "ron", t0.plusSeconds(30), "Dr. Levi is fine for babies but avoid him for teenagers, he was dismissive with my son"));
            ingest.ingest(g, msg(g, m3, "yossi", t0.plusSeconds(60), "Who is bringing snacks for Friday's trip?"));
            ingest.ingest(other, msg(other, o1, "noa", t0, "Dr. Levi on Herzl St. is excellent, but this is a different group"));
            for (String id : List.of(m1, m2, m3)) assertTrue(x.enqueue(g, store.find(g, id).orElseThrow()));

            List<ExtractionBatch> batches = x.collect(g);
            assertEquals(1, batches.size());
            ExtractionBatch b = batches.get(0);
            assertEquals(List.of(m1, m2, m3), b.messageIds());
            Thread.sleep(300);
            assertEquals(ExtractionBatch.Status.COLLECTED, x.batch(g, b.id()).orElseThrow().status(), "nothing is sent without approval (E-1)");
            assertTrue(muse.requests.isEmpty(), "no Muse request before approval");

            x.approve(g, b.id(), "test-approval-" + run);
            awaitStatus(x, g, b.id(), ExtractionBatch.Status.DONE);                 // processed asynchronously by the worker
            assertEquals(1, muse.requests.size());
            String req = muse.requests.get(0);
            assertFalse(req.contains("\"dana\"") || req.contains("\"ron\""), "authors are pseudonymised in the request (E-6)");
            assertFalse(req.contains(o1), "no other group's message is sent");

            // 1 + 2: two valid items persisted, four unsupported proposals rejected
            List<KnowledgeItem> k = store.knowledge(g);
            assertEquals(2, k.size(), "only the two supported items are stored: " + k);
            KnowledgeItem dana = k.stream().filter(i -> i.assertion().speakerId().equals("dana")).findFirst().orElseThrow();
            KnowledgeItem ron = k.stream().filter(i -> i.assertion().speakerId().equals("ron")).findFirst().orElseThrow();
            assertEquals("recommends", dana.assertion().predicate());
            assertEquals("advises_against", ron.assertion().predicate());
            assertEquals(Assertion.Status.ATTRIBUTED_STATEMENT, dana.assertion().status());
            assertEquals("Dr. Levi on Herzl St. is excellent for babies", dana.statement(), "statement is the verbatim quote, not model wording");
            assertEquals(m1, dana.asserting().get(0).messageId());
            assertEquals(0, store.find(g, m1).orElseThrow().text().indexOf(dana.statement()));
            assertEquals(List.of("herzl-st"), dana.assertion().entities().stream().map(Assertion.Entity::id).toList());
            assertEquals(b.id(), dana.assertion().batchId());
            assertTrue(store.knowledge(other).isEmpty(), "nothing stored for the other group");

            // 3: duplicate extraction (operator re-extraction of the same batch) stores nothing new
            x.reprocess(g, b.id(), "test-reapproval-" + run);
            awaitStatus(x, g, b.id(), ExtractionBatch.Status.DONE);
            assertEquals(2, muse.requests.size());
            assertEquals(2, store.knowledge(g).size(), "no duplicate knowledge");

            // 4 + 5: retrieval and a source-grounded answer with speakers and qualifications
            GroundedAnswer a = queries.ask(g, "yossi", "Is Dr. Levi recommended?");
            assertTrue(a.found());
            assertEquals(List.of(dana.id(), ron.id()), a.knowledgeIds(), "answered from stored knowledge");
            assertEquals(List.of(m1, m2), cited(a), "cites the original messages");
            assertTrue(a.text().contains("dana recommended Dr. Levi (for babies)"), a.text());
            assertTrue(a.text().contains("ron advised against Dr. Levi (for teenagers)"), a.text());
            assertTrue(a.text().contains("differ by context"), "different populations are not reported as a conflict");
            assertFalse(a.conflicting());
            assertTrue(a.text().contains("not verified facts"));

            // 6: isolation
            GroundedAnswer o = queries.ask(other, "noa", "Is Dr. Levi recommended?");
            assertTrue(o.knowledgeIds().isEmpty(), "the other group has no knowledge");
            assertTrue(cited(o).stream().allMatch(id -> id.startsWith(other.groupId())), "never cites another group");

            // 7a: DEC-012 early deletion (within 5 min of sending): Ron's contribution and item are removed
            DeletionService deletions = bean(c, DeletionService.class);
            deletions.delete(g, m2, t0.plusSeconds(30 + 120), DeletionSource.FORGET_COMMAND).orElseThrow();
            assertTrue(store.knowledgeById(g, ron.id()).isEmpty(), "early deletion removes the dependent item");
            GroundedAnswer afterDelete = queries.ask(g, "yossi", "Is Dr. Levi recommended?");
            assertEquals(List.of(dana.id()), afterDelete.knowledgeIds());
            assertFalse(afterDelete.text().contains("teenagers"), "deleted text is never quoted");
            assertFalse(cited(afterDelete).contains(m2));
        } finally {
            c.close();
        }

        if (!durable()) return;

        // 8: restart — a NEW application context on the same database
        ConfigurableApplicationContext c2 = boot();
        try {
            MessageStore store = bean(c2, MessageStore.class);
            QueryService queries = bean(c2, QueryService.class);
            assertEquals(1, store.knowledge(g).size(), "valid knowledge survived the restart; the deleted contribution did not come back");
            GroundedAnswer a = queries.ask(g, "yossi", "Is Dr. Levi recommended?");
            assertEquals(1, a.knowledgeIds().size());
            assertEquals(List.of(m1), cited(a));
            assertTrue(a.text().contains("dana recommended Dr. Levi (for babies)"), a.text());

            // 7b: DEC-012 late deletion (after 5 min): the item is preserved with deleted-source provenance but is no longer
            // used in answers (DEC-025 D-1 interim), and its text is never quoted
            bean(c2, DeletionService.class).delete(g, m1, t0.plus(Duration.ofHours(1)), DeletionSource.FORGET_COMMAND).orElseThrow();
            KnowledgeItem kept = store.knowledgeById(g, a.knowledgeIds().get(0)).orElseThrow();
            assertTrue(kept.onlyDeletedSourcesRemain());
            GroundedAnswer late = queries.ask(g, "yossi", "Is Dr. Levi recommended?");
            assertTrue(late.knowledgeIds().isEmpty());
            assertFalse(late.text().contains("babies"));
        } finally {
            c2.close();
        }
    }
}
