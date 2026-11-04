package app.dita.app;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupDirectory;
import app.dita.core.model.GroupRegistry;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Retriever;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.MessageStore;
import app.dita.eval.BenchPaths;
import app.dita.eval.capability.DitaProductRunner;
import app.dita.muse.MuseTransport;
import app.dita.projections.Neo4jProjection;
import app.dita.projections.VespaProjection;
import app.dita.store.postgres.PostgresOutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-044 Priority Zero: controlled experiment on the Synthetic Lab conversation.
 * <ol>
 *   <li>The 41 fixture messages are ingested as SYNTHETIC (file-based synthetic fixture, explicitly labelled; never
 *       platform traffic) into a fresh group, with ids "m&lt;message_id&gt;", authors = from.id, times = date.</li>
 *   <li>Configurations A (BM25 only) and B (Dita without knowledge) answer the 15 questions BEFORE extraction.</li>
 *   <li>One explicitly approved extraction batch (all 41 messages) goes through the production ExtractionService →
 *       MuseKnowledgeProposer → EvidenceValidator → RelationRules → store (+ outbox → Neo4j/Vespa on POSTGRES).</li>
 *   <li>Configuration C (Dita with knowledge) answers the same questions; everything is written to results/.</li>
 * </ol>
 * Modes (-Ddita.lab.muse=…): PREVIEW (no network: captures the exact request body, makes no call), RECORDED (replays
 * results/muse-recording.json, no network), LIVE (one real call; only via scripts/muse-live.sh lab, which needs the
 * user's typed approval and DITA_MUSE_LAB_APPROVED). The background extraction worker is OFF: only this run's batch
 * is processed. Display names come from the fixture's synthetic first_name values (experiment-only member directory).
 */
class SyntheticLabKnowledgeTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern QUESTION = Pattern.compile("^\\d+\\. \\*\\*(.+?)\\*\\*", Pattern.MULTILINE);
    static final String FAKE_KEY = "synthetic-preview-key-not-a-real-muse-key";
    static final String REVIEWED_FIXTURE_SHA256 = "ce4128884ae963d6837bbffba01e1b1a0227ce120d804ebd3decf146651c27f2";
    /** Peter's sealed holdout conversation.json (hash recorded in tasks.md when sealed). */
    static final String HOLDOUT_FIXTURE_SHA256 = "127d6d4757e8a3bc33ac61da7f6e733a666b4bd018635bec44e383eeb96f68ae";
    static final String HOLDOUT_V2_FIXTURE_SHA256 = "93cb6fe513c6770855e0456718bf4bdef2bb6aa5d7047a8c1c1c9625a6f816bb";
    /** N-84: the request bodies Natasha reviewed (byte-exact previews), pinned so code + preview cannot change together. */
    static final String REVIEWED_LAB_REQUEST_SHA256 = "74ebeefadd3e7400eed86bfb68548a7a1662ed2cf0607d7d7e6c587488aa1f4f";
    static final String REVIEWED_HOLDOUT_REQUEST_SHA256 = "ea06b52808520a5b5dc00b35829d4d6e9b86bdd94f59c9aaa542afde34f3b218";
    /** Set only after Natasha reviews the v2 preview; until then LIVE v2 refuses (no reviewed request). */
    static final String REVIEWED_HOLDOUT_V2_REQUEST_SHA256 = "41fd094574e75b7f4cec7573af8639bef2e4fab37547a5e42ce038c7ec95e8c9";   // Natasha APPROVE 2026-10-31

    @Test void controlledExperiment() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.capability.run"), "set -Ddita.capability.run=true");
        String mode = System.getProperty("dita.lab.muse", "PREVIEW"), backend = System.getProperty("dita.lab.backend", "IN_MEMORY");
        String dataset = System.getProperty("dita.lab.dataset", "lab");
        assumeTrue(List.of("PREVIEW", "RECORDED", "LIVE").contains(mode), "bad dita.lab.muse");
        assumeTrue(List.of("lab", "holdout", "holdout-v2").contains(dataset), "bad dita.lab.dataset");
        Path root = BenchPaths.locate().projectRoot();
        // lab: the Synthetic Lab fixture. holdout: Peter's SEALED holdout; the builder never reads it. Questions come from
        // the rubric's "question" fields only (answers are never read here); results go OUTSIDE sealed/.
        Path lab = root.resolve(dataset.equals("lab") ? "eval/synthetic-lab" : dataset.equals("holdout") ? "eval/synthetic-holdout" : "eval/synthetic-holdout-v2");
        Path fixture = dataset.equals("lab") ? lab.resolve("synthetic_updates.json") : lab.resolve("sealed/conversation.json");
        String reviewedSha = dataset.equals("lab") ? REVIEWED_FIXTURE_SHA256 : dataset.equals("holdout") ? HOLDOUT_FIXTURE_SHA256 : HOLDOUT_V2_FIXTURE_SHA256;
        Path results = lab.resolve("results");
        Files.createDirectories(results);
        String approval = System.getenv("DITA_MUSE_LAB_APPROVED");
        if (mode.equals("LIVE")) {
            assumeTrue(approval != null && approval.startsWith("user-approved-"), "LIVE needs DITA_MUSE_LAB_APPROVED (set only by scripts/muse-live.sh lab)");
            assumeTrue(System.getenv("MODEL_API_KEY") != null, "MODEL_API_KEY not exported");
        }
        Path recording = results.resolve("muse-recording.json"), preview = results.resolve("muse-request-preview.json");
        byte[][] reviewedBytes = {null};
        if (mode.equals("LIVE")) {
            // N-75: the approval covers exactly the reviewed fixture and exactly the reviewed request.
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(fixture)));
            assertEquals(reviewedSha, sha, "fixture differs from the reviewed one: nothing sent");
            assertTrue(Files.isRegularFile(preview), "no reviewed request preview: nothing sent");
            reviewedBytes[0] = Files.readAllBytes(preview);                                   // N-93: hash and compare the SAME bytes
            String previewSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reviewedBytes[0]));
            assertEquals(dataset.equals("lab") ? REVIEWED_LAB_REQUEST_SHA256 : dataset.equals("holdout") ? REVIEWED_HOLDOUT_REQUEST_SHA256 : REVIEWED_HOLDOUT_V2_REQUEST_SHA256, previewSha,
                    "request preview is not the reviewed one (N-84): nothing sent");
            // N-81: a paid recording is never deleted or paid for again; reruns use RECORDED.
            assertTrue(!Files.exists(recording), "a live recording already exists: use -Ddita.lab.muse=RECORDED (nothing sent)");
        }
        if (mode.equals("RECORDED")) assumeTrue(Files.isRegularFile(recording), "no recorded live response yet: NOT EXECUTED");

        JsonNode updates = JSON.readTree(Files.readString(fixture));
        List<String> questions = new ArrayList<>();
        if (dataset.equals("lab")) {
            Matcher qm = QUESTION.matcher(Files.readString(lab.resolve("expected_questions.md")));
            while (qm.find()) questions.add(qm.group(1));
            assertEquals(15, questions.size());
        } else {
            for (JsonNode q : JSON.readTree(Files.readString(lab.resolve("sealed/rubric.json"))).path("questions")) questions.add(q.path("question").asText());
            assertTrue(questions.size() >= 12 && questions.size() <= 15, "holdout question count");
        }
        Map<String, String> names = new LinkedHashMap<>();
        updates.forEach(u -> names.put(Long.toString(u.path("message").path("from").path("id").asLong()), u.path("message").path("from").path("first_name").asText()));
        Function<String, String> display = id -> names.getOrDefault(id, id);

        // Transport: PREVIEW captures and refuses; RECORDED replays; LIVE calls api.meta.ai and records the reply.
        List<String> requests = new ArrayList<>();
        MuseTransport transport = switch (mode) {
            case "PREVIEW" -> (url, h, body, t) -> { requests.add(body); return new MuseTransport.Reply(599, "{\"error\":\"preview: no call made\"}", Map.of()); };
            case "RECORDED" -> (url, h, body, t) -> { requests.add(body); return new MuseTransport.Reply(200, Files.readString(recording), Map.of()); };
            default -> {
                MuseTransport real = MuseTransport.http();
                yield (url, h, body, t) -> {
                    if (!requests.isEmpty()) throw new IllegalStateException("experiment budget: exactly one live call");
                    if (reviewedBytes[0] == null || !body.equals(new String(reviewedBytes[0], java.nio.charset.StandardCharsets.UTF_8)))
                        throw new IllegalStateException("request differs from the reviewed preview: not sent");
                    requests.add(body);
                    MuseTransport.Reply r = real.post(url, h, body, t);
                    if (r.status() == 200) Files.writeString(recording, r.body());
                    return r;
                };
            }
        };
        Function<String, String> env = n -> n.equals("MODEL_API_KEY") ? (mode.equals("LIVE") ? System.getenv(n) : FAKE_KEY) : System.getenv(n);

        String run = UUID.randomUUID().toString().substring(0, 8), prefix = "lab-" + run;
        Map<String, String> props = new HashMap<>(Map.of(
                "dita.storage.backend", backend, "dita.muse.enabled", "true",
                "dita.muse.max-output-tokens", System.getProperty("dita.lab.max-output-tokens", "16000"),
                "dita.muse.max-input-chars", "40000", "dita.muse.max-calls-per-process", "1", "dita.muse.timeout", "120s",
                "dita.extraction.batch-size", "50", "dita.extraction.worker-enabled", "false",
                "dita.processing.dispatch.poll-interval", "50ms"));
        if (backend.equals("POSTGRES")) {
            for (String v : CapabilityPostgresRunTest.REQUIRED) assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: NOT EXECUTED");
            props.putAll(Map.of("dita.storage.relay-consumer-prefix", prefix,
                    "dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL"),
                    "dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD",
                    "dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD",
                    "dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL"),
                    "dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD",
                    "dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")));
        }
        String tag = backend.toLowerCase() + "-" + mode.toLowerCase();
        try (ConfigurableApplicationContext c = new SpringApplicationBuilder(DitaApplication.class)
                .initializers(ctx -> { ctx.getBeanFactory().registerSingleton("ditaEnv", env); ctx.getBeanFactory().registerSingleton("museTransport", transport); })
                .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new))) {
            MessageStore store = c.getBean(MessageStore.class);
            GroupScope g = new GroupScope(prefix);
            c.getBean(GroupRegistry.class).ensureRegistered(g.groupId(), "synthetic", g.groupId(), GroupDirectory.ChatType.SYNTHETIC, SourceKind.SYNTHETIC);
            IngestionPipeline ingest = c.getBean(IngestionPipeline.class);
            for (JsonNode u : updates) {
                JsonNode m = u.path("message");
                String replyTo = m.has("reply_to_message") ? "m" + m.path("reply_to_message").path("message_id").asLong() : null;
                ingest.ingest(g, new Message("m" + m.path("message_id").asLong(), g.groupId(), Long.toString(m.path("from").path("id").asLong()),
                        Instant.ofEpochSecond(m.path("date").asLong()), replyTo, m.path("text").asText(), SourceKind.SYNTHETIC, false));
            }
            if (backend.equals("POSTGRES")) SyntheticLabReplayTest.awaitDrained(c.getBean(PostgresOutboxRelay.class), prefix);

            var access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY, (s, mb) -> Optional.empty(), store);
            Retriever product = c.getBean(Retriever.class);
            QueryService a = new QueryService(store, Retrievers.build(store, DitaProductRunner.BM25_ONLY), access, DitaProductRunner.K, DitaProductRunner.MAX_QUOTE, display);
            // C's knowledge ranking: the app's KnowledgeRetriever on POSTGRES (Vespa knowledge index); in-memory BM25 offline
            app.dita.core.knowledge.KnowledgeRetriever kr = backend.equals("POSTGRES")
                    ? new app.dita.core.knowledge.RerankingKnowledgeRetriever(vespaIndex(c), new app.dita.core.knowledge.LexicalKnowledgeRetriever(store, display))
                    : new app.dita.core.knowledge.LexicalKnowledgeRetriever(store, display);
            QueryService bc = new QueryService(store, product, access, DitaProductRunner.K, DitaProductRunner.MAX_QUOTE, display, kr)
                    .withSupportingMessages(Boolean.getBoolean("dita.lab.related"));   // TASK-045: off by default (ablation switch)
            String asker = names.keySet().iterator().next();
            List<String> outA = ask(a, g, questions, "A-bm25", tag, asker), outB = ask(bc, g, questions, "B-dita-no-knowledge", tag, asker);

            ExtractionService x = c.getBean(ExtractionService.class);
            for (JsonNode u : updates) assertTrue(x.enqueue(g, store.find(g, "m" + u.path("message").path("message_id").asLong()).orElseThrow()));
            List<ExtractionBatch> batches = x.collect(g);
            assertEquals(1, batches.size(), "all 41 messages in ONE batch (consistent subject ids across the conversation)");
            ExtractionBatch b = batches.get(0);
            x.approve(g, b.id(), mode.equals("LIVE") ? approval : "offline-" + mode.toLowerCase());
            ExtractionService.Report rep = x.process(g, b.id()).orElseThrow();
            if (!requests.isEmpty()) {
                String body = requests.get(0);
                if (!mode.equals("PREVIEW") || !Files.exists(preview))                    // the reviewed preview is never overwritten
                    Files.writeString(results.resolve("muse-request-" + mode.toLowerCase() + ".json"), body);   // synthetic content only
            }
            ObjectNode report = JSON.createObjectNode();
            report.put("mode", mode).put("backend", backend).put("group", g.groupId()).put("batch", b.id())
                  .put("status", rep.status().name()).put("error", rep.error()).put("accepted", rep.accepted())
                  .put("duplicates", rep.duplicates()).put("relations", rep.relations()).put("superseded", rep.superseded())
                  .put("model_calls", requests.size()).put("request_chars", requests.isEmpty() ? 0 : requests.get(0).length())
                  .put("dataset", dataset).put("knowledge_retriever", kr.name());
            if (!mode.equals("PREVIEW") && Files.isRegularFile(recording)) {
                JsonNode usage = JSON.readTree(Files.readString(recording)).path("usage");
                report.set("usage", usage);
                double in = usage.path("prompt_tokens").asDouble(), outT = usage.path("completion_tokens").asDouble();
                report.put("cost_usd_at_user_prices", Math.round((in * 1.25 + outT * 4.25) / 1e6 * 1e6) / 1e6);   // $1.25/M in, $4.25/M out (user-supplied)
            }
            ArrayNode rej = report.putArray("rejections");
            rep.rejections().forEach(r -> rej.addObject().put("item", r.item()).put("reason", r.reason()));
            ArrayNode items = report.putArray("items");
            for (KnowledgeItem k : store.knowledge(g)) items.add(describe(store, g, k, display));
            ArrayNode rels = report.putArray("relation_list");
            for (KnowledgeRelation r : store.relations(g)) rels.addObject().put("from", r.fromId()).put("kind", r.kind().name()).put("to", r.toId());
            if (backend.equals("POSTGRES") && rep.status() == ExtractionBatch.Status.DONE) {
                SyntheticLabReplayTest.awaitDrained(c.getBean(PostgresOutboxRelay.class), prefix);
                ObjectNode pc = inspectProjections(c, g, store);
                ArrayNode vr = pc.putArray("vespa_knowledge_retrieval");
                VespaProjection vespa = c.getBean(VespaProjection.class);
                for (int i = 0; i < questions.size(); i++) {
                    ArrayNode hits = vr.addObject().put("q", i + 1).putArray("active_hits");
                    vespa.retrieveKnowledge(g, questions.get(i), 5, true).forEach(h -> hits.add(h.knowledgeId()));
                }
                report.set("projection_check", pc);
            }
            Files.writeString(results.resolve("knowledge-" + tag + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            if (mode.equals("PREVIEW")) {
                assertEquals(ExtractionBatch.Status.FAILED, rep.status(), "preview never succeeds (no call is made)");
                return;
            }
            assertEquals(ExtractionBatch.Status.DONE, rep.status(), "extraction failed: " + rep.error());
            List<String> outC = ask(bc, g, questions, "C-dita-knowledge", tag, asker);
            write(results, "answers-lab-A-" + tag + ".jsonl", outA);
            write(results, "answers-lab-B-" + tag + ".jsonl", outB);
            write(results, "answers-lab-C-" + tag + ".jsonl", outC);
        }
    }

    /** Vespa's knowledge index as candidate source (same call the app's bean makes). */
    static app.dita.core.knowledge.KnowledgeRetriever vespaIndex(ConfigurableApplicationContext c) {
        VespaProjection vespa = c.getBean(VespaProjection.class);
        return new app.dita.core.knowledge.KnowledgeRetriever() {
            public List<Hit> retrieve(GroupScope s, String q, int k) {
                return vespa.retrieveKnowledge(s, q, k, true).stream().map(h -> new Hit(h.knowledgeId(), h.relevance())).toList();
            }
            public String name() { return "vespa-knowledge-bm25"; }
        };
    }

    static List<String> ask(QueryService q, GroupScope g, List<String> questions, String config, String tag, String asker) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            q.ask(g, asker, questions.get(i));                                               // warm-up (latency is measured on the 2nd call)
            long t0 = System.nanoTime();
            GroundedAnswer a = q.ask(g, asker, questions.get(i));
            long askMicros = (System.nanoTime() - t0) / 1000;
            ObjectNode o = JSON.createObjectNode();
            o.put("q", i + 1).put("question", questions.get(i)).put("config", config).put("backend", tag);
            o.put("behavior", !a.found() ? "UNKNOWN" : a.conflicting() ? "CONFLICT" : "ANSWER").put("answer_text", a.text());
            o.put("knowledge_items_used", a.knowledgeIds().size()).put("ask_micros", askMicros);
            ArrayNode cites = o.putArray("citations");
            for (Citation c : a.citations()) {
                if (!c.messageId().matches("m\\d+")) fail("citation outside the replayed group: " + c.messageId());
                cites.addObject().put("message_id", Long.parseLong(c.messageId().substring(1))).put("author", c.authorId())
                        .put("evidence", c.evidence().name()).put("polarity", c.polarity());
            }
            out.add(o.toString());
        }
        return out;
    }

    static ObjectNode describe(MessageStore store, GroupScope g, KnowledgeItem k, Function<String, String> display) {
        ObjectNode o = JSON.createObjectNode();
        var a = k.assertion();
        o.put("id", k.id()).put("status", a.status().name()).put("certainty", a.certainty().name())
         .put("speaker", display.apply(a.speakerId())).put("speaker_id", a.speakerId()).put("subject_id", a.subjectId())
         .put("subject", a.subject()).put("predicate", a.predicate()).put("validity", a.validity().name())
         .put("stated_at", DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(a.statedAt()));
        if (a.attributedTo() != null) o.put("attributed_to", a.attributedTo());
        ArrayNode ev = o.putArray("evidence");
        for (KnowledgeItem.Evidence e : k.evidence()) {
            String quote = store.find(g, e.messageId()).filter(m -> e.hasSpan() && m.text() != null && e.spanEnd() <= m.text().length())
                    .map(m -> m.text().substring(e.spanStart(), e.spanEnd())).orElse(null);
            ev.addObject().put("message", e.messageId()).put("role", e.role().name()).put("quote", quote);
        }
        ArrayNode qs = o.putArray("qualifiers");
        a.qualifiers().forEach(q -> qs.addObject().put("kind", q.kind().name()).put("message", q.messageId())
                .put("text", store.find(g, q.messageId()).map(m -> m.text().substring(q.spanStart(), q.spanEnd())).orElse(null)));
        ArrayNode es = o.putArray("entities");
        a.entities().forEach(e -> es.addObject().put("id", e.id()).put("surface", e.surface()));
        return o;
    }

    /** Phase 3: what actually reached Neo4j and Vespa for each stored item (ids/fields only). */
    static ObjectNode inspectProjections(ConfigurableApplicationContext c, GroupScope g, MessageStore store) {
        ObjectNode o = JSON.createObjectNode();
        Neo4jProjection neo = c.getBean(Neo4jProjection.class);
        VespaProjection vespa = c.getBean(VespaProjection.class);
        List<KnowledgeItem> ks = store.knowledge(g);
        o.put("postgres_items", ks.size()).put("postgres_relations", store.relations(g).size());
        o.put("neo4j_knowledge_nodes", neo.query("MATCH (k:Knowledge {groupId:$g}) RETURN count(k)", Map.of("g", g.groupId())).get(0).get(0).asInt());
        o.put("neo4j_relates_edges", neo.query("MATCH (:Knowledge {groupId:$g})-[r:RELATES]->(:Knowledge) RETURN count(r)", Map.of("g", g.groupId())).get(0).get(0).asInt());
        o.put("neo4j_derived_from_edges", neo.query("MATCH (:Knowledge {groupId:$g})-[r:DERIVED_FROM]->() RETURN count(r)", Map.of("g", g.groupId())).get(0).get(0).asInt());
        ArrayNode per = o.putArray("items");
        int vespaDocs = 0;
        for (KnowledgeItem k : ks) {
            Optional<JsonNode> doc = vespa.knowledgeDocument(g.groupId(), k.id());
            if (doc.isPresent()) vespaDocs++;
            var row = neo.query("MATCH (k:Knowledge {uid:$u}) RETURN k.validity, k.speakerId, k.predicate, k.subjectId",
                    Map.of("u", g.groupId() + "|" + k.id()));
            ObjectNode it = per.addObject().put("id", k.id()).put("pg_validity", k.assertion().validity().name())
                    .put("vespa_present", doc.isPresent());
            if (doc.isPresent()) it.put("vespa_validity", doc.get().path("validity").asText(null)).put("vespa_text", doc.get().path("text").asText(null));
            if (!row.isEmpty()) it.put("neo4j_validity", row.get(0).get(0).asText(null)).put("neo4j_speaker", row.get(0).get(1).asText(null))
                    .put("neo4j_predicate", row.get(0).get(2).asText(null)).put("neo4j_subject", row.get(0).get(3).asText(null));
        }
        o.put("vespa_knowledge_docs", vespaDocs);
        return o;
    }

    static void write(Path dir, String name, List<String> lines) throws Exception { Files.writeString(dir.resolve(name), String.join("\n", lines) + "\n"); }
}
