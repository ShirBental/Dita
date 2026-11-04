package app.dita.app;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.model.*;
import app.dita.core.store.MessageStore;
import app.dita.muse.MuseTransport;
import app.dita.projections.VespaProjection;
import app.dita.projections.Neo4jProjection;
import app.dita.store.postgres.PostgresOutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Direct production pipeline trial. Telegram remains OFF; replay security rules are unchanged. */
class ControlledMuseTrialTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String DATASET = System.getProperty("dita.trial.dataset", "levi");
    static final Path OUT = app.dita.eval.BenchPaths.locate().projectRoot().resolve(
        DATASET.equals("cedar") ? "build/controlled-muse-cedar" : "build/controlled-muse-trial");
    static final String URL = "https://api.meta.ai/v1/chat/completions";
    static final String MODEL = "muse-spark-1.3";
    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test void productionPipeline() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.trial.run"));
        assertTrue(Set.of("levi", "cedar").contains(DATASET));
        boolean cedar = DATASET.equals("cedar");
        String mode = System.getProperty("dita.trial.mode", "PREVIEW");
        assertTrue(Set.of("PREVIEW", "RECORDED", "LIVE", "SELFTEST").contains(mode));
        boolean live = mode.equals("LIVE"), preview = mode.equals("PREVIEW"), selftest = mode.equals("SELFTEST");
        Files.createDirectories(OUT);
        Path request = OUT.resolve("request.json"), recording = OUT.resolve("recording.json");
        if (live) {
            assertEquals("yes", System.getenv("DITA_TRIAL_APPROVED"));
            assertFalse(Files.exists(OUT.resolve("attempted")), "Already attempted: use RECORDED; never retry automatically");
            assertEquals(System.getenv("DITA_TRIAL_REQUEST_SHA256"), sha(Files.readAllBytes(request)));
        }
        String prefix = "trial-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String,String> p = new HashMap<>(Map.of(
            "dita.telegram.enabled", "false", "dita.coordination.enabled", "false",
            "dita.muse.enabled", "true", "dita.muse.transport", "HTTP",
            "dita.muse.chat-completions-url", URL, "dita.muse.model", MODEL,
            "dita.muse.max-output-tokens", "4096", "dita.muse.max-calls-per-process", "1",
            "dita.muse.timeout", "60s", "dita.extraction.worker-enabled", "false"));
        p.put("dita.memory.answers.synthesis.enabled", "false");
        p.put("dita.muse.response-format", "PROMPT_ONLY");
        p.put("dita.muse.reasoning-effort", "LOW");
        p.put("dita.storage.backend", preview || selftest ? "IN_MEMORY" : "POSTGRES");
        p.put("dita.storage.relay-consumer-prefix", prefix);
        if (!preview && !selftest) {
            for (String v : KnowledgeSlicePostgresTest.REQUIRED) assertNotNull(System.getenv(v), v + " missing");
            p.putAll(Map.of("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL"),
                "dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD",
                "dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD",
                "dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL"),
                "dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD",
                "dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")));
        }
        List<String> requests = new ArrayList<>();
        MuseTransport transport = (url, headers, body, timeout) -> {
            assertEquals(URL, url.toString());
            assertTrue(requests.isEmpty(), "Exactly one transport invocation");
            requests.add(body);
            if (preview) {
                Files.writeString(request, body);
                return new MuseTransport.Reply(599, "{}", Map.of());
            }
            assertEquals(Files.readString(request), body, "Request must match preview byte for byte");
            if (selftest) {
                String fixture = new String(getClass().getResourceAsStream("/replay/parents-levi.muse-recording.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                fixture = fixture.replace("tgr1001000000052m1", "m1").replace("tgr1001000000052m2", "m2");
                String content = JSON.readTree(fixture).path("recordings").get(0).path("content").toString();
                if (cedar) content = """
                    {"items":[
                      {"status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subject_id":"dr-cedar","subject":"Dr. Cedar","predicate":"recommends","attributed_to":"","evidence":[{"message_id":"m1","quote":"Dr. Cedar on Maple Street is excellent for babies."}],"qualifiers":[{"kind":"POPULATION","message_id":"m1","quote":"for babies"}],"entities":[]},
                      {"status":"ATTRIBUTED_STATEMENT","certainty":"ASSERTED","subject_id":"dr-cedar","subject":"Dr. Cedar","predicate":"advises_against","attributed_to":"","evidence":[{"message_id":"m2","quote":"Dr. Cedar is not recommended for teenagers."}],"qualifiers":[{"kind":"POPULATION","message_id":"m2","quote":"for teenagers"}],"entities":[]}
                    ]}
                    """;
                String response = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content",content)))));
                return new MuseTransport.Reply(200, response, Map.of());
            }
            if (!live) return new MuseTransport.Reply(200, Files.readString(recording), Map.of());
            assertEquals(System.getenv("DITA_TRIAL_REQUEST_SHA256"), sha(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Files.writeString(OUT.resolve("attempted"), Instant.now().toString(), StandardOpenOption.CREATE_NEW);
            var reply = MuseTransport.http().post(url, headers, body, timeout);
            if (reply.status() == 200) Files.writeString(recording, reply.body(), StandardOpenOption.CREATE_NEW);
            return reply;
        };
        Function<String,String> env = n -> n.equals("MODEL_API_KEY") && !live ? "synthetic-no-live-key" : System.getenv(n);
        try (var c = new SpringApplicationBuilder(DitaApplication.class).initializers(ctx -> {
            ctx.getBeanFactory().registerSingleton("ditaEnv", env);
            ctx.getBeanFactory().registerSingleton("museTransport", transport);
        }).run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new))) {
            var g = new GroupScope(prefix);
            c.getBean(GroupRegistry.class).ensureRegistered(prefix, "synthetic", prefix, GroupDirectory.ChatType.SYNTHETIC, SourceKind.SYNTHETIC);
            String[] text = cedar ? new String[]{
                "Dr. Cedar on Maple Street is excellent for babies.",
                "Dr. Cedar is not recommended for teenagers."
            } : new String[]{"Dr. Levi on Herzl St. is excellent for babies",
                "Dr. Levi is fine for babies but avoid him for teenagers, he was dismissive with my son",
                "Who is bringing snacks for Friday's trip?"};
            var x = c.getBean(ExtractionService.class);
            for (int i=0; i<text.length; i++) {
                var m = new Message("m"+(i+1), prefix, cedar ? "synthetic1" : "synthetic"+(i+1), Instant.parse("2026-11-03T12:00:00Z").plusSeconds(i*30), null, text[i], SourceKind.SYNTHETIC, false);
                c.getBean(IngestionPipeline.class).ingest(g, m);
                assertTrue(x.enqueue(g, m));
            }
            var batches = x.collect(g); assertEquals(1, batches.size());
            var b = batches.get(0);
            x.approve(g, b.id(), live ? "operator-approved:"+System.getenv("DITA_TRIAL_REQUEST_SHA256") : "offline-"+mode);
            var rep = x.process(g, b.id()).orElseThrow();
            assertEquals(1, requests.size());
            if (preview) { assertEquals(ExtractionBatch.Status.FAILED, rep.status()); return; }
            assertEquals(ExtractionBatch.Status.DONE, rep.status(), "Extraction status: " + rep.error());
            var store = c.getBean(MessageStore.class);
            var knowledge = store.knowledge(g);
            assertFalse(knowledge.isEmpty(), "Live output must produce validated knowledge");
            if (!selftest) {
            SyntheticLabReplayTest.awaitDrained(c.getBean(PostgresOutboxRelay.class), prefix);
            var vespa = c.getBean(VespaProjection.class);
            for (var k : knowledge) assertTrue(vespa.knowledgeDocument(prefix, k.id()).isPresent(), "Vespa must contain every accepted item");
            var rows = c.getBean(Neo4jProjection.class).query("MATCH (k:Knowledge) WHERE k.groupId = $g RETURN count(k)", Map.of("g",prefix));
            assertEquals(knowledge.size(), rows.get(0).get(0).asInt());
            }
            if (cedar) {
                var queries = c.getBean(QueryService.class);
                List<Map<String,Object>> answers = new ArrayList<>();
                List<Boolean> checks = new ArrayList<>();
                String[] questions = {"Is Dr. Cedar recommended for babies?", "Is Dr. Cedar recommended for teenagers?", "Is Dr. Cedar recommended?"};
                for (int i=0; i<questions.length; i++) {
                    var answer = queries.ask(g, "synthetic1", questions[i]);
                    Set<String> sources = new HashSet<>(answer.citations().stream().map(z -> z.messageId()).toList());
                    Set<String> expected = i==0 ? Set.of("m1") : i==1 ? Set.of("m2") : Set.of("m1","m2");
                    boolean ok = answer.found() && !answer.knowledgeIds().isEmpty() && sources.equals(expected)
                        && !answer.conflicting() && !answer.text().contains("Members disagree");
                    checks.add(ok);
                    answers.add(Map.of("question",questions[i],"text",answer.text(),"sources",sources,
                        "knowledge_ids",answer.knowledgeIds(),"conflicting",answer.conflicting(),"quality_pass",ok));
                }
                List<Map<String,Object>> extracted = new ArrayList<>();
                for (var item : knowledge) extracted.add(Map.of("id",item.id(),"statement",item.statement(),
                    "predicate",item.assertion().predicate(),"assertion",item.assertion().toString()));
                boolean quality = checks.stream().allMatch(Boolean::booleanValue);
                Files.writeString(OUT.resolve("result-"+mode.toLowerCase()+".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "status",quality ? "PASS" : "QUALITY_FAIL", "mode",mode,"group",prefix,
                    "accepted",knowledge.size(),"extracted",extracted,"answers",answers,"transport_invocations",requests.size())));
                assertTrue(quality, "Cedar population-specific quality failed; saved answers and recording permit offline diagnosis, do not repeat live call");
                return;
            }
            var a = c.getBean(QueryService.class).ask(g, "synthetic1", "Is Dr. Levi recommended for babies?");
            assertTrue(a.found()); assertFalse(a.knowledgeIds().isEmpty(), "Answer must use extracted knowledge");
            assertFalse(a.citations().isEmpty());
            for (var cite : a.citations()) assertTrue(store.find(g, cite.messageId()).isPresent(), "Citation must belong to fresh group");
            Files.writeString(OUT.resolve("result-"+mode.toLowerCase()+".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "status", "PASS", "mode",mode,"group",prefix,"accepted",knowledge.size(),"answer",Map.of("text",a.text(),"knowledge_ids",a.knowledgeIds(),"source_ids",a.citations().stream().map(z -> z.messageId()).toList()),"transport_invocations",requests.size())));
        }
    }
}
