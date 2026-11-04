package app.dita.app;

import app.dita.app.config.DitaSettings;
import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.answer.synthesis.GroundedSynthesis;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.knowledge.KnowledgeAnswerComposer;
import app.dita.core.knowledge.KnowledgeRetriever;
import app.dita.core.model.*;
import app.dita.core.store.MessageStore;
import app.dita.muse.MuseAnswerSynthesizer;
import app.dita.muse.MuseTransport;
import app.dita.store.postgres.PostgresOutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controlled synthesis trial (TASK-054): boots the PRODUCTION Spring context with synthesis enabled for this process only,
 * establishes knowledge through the production extraction path from a fixed Muse EXTRACTION reply, stores further
 * synthetic messages that are NOT extracted (so the trial can show what Dita did NOT send), then asks questions through
 * {@link QueryService#ask}. Synthesis requests go to a caller-supplied reply function; whatever Dita selected, composed,
 * validated and returned is recorded unchanged. Nothing here filters the evidence.
 * <p>Fixtures: {@link #cedar()} replays the SAVED live extraction response (build/controlled-muse-cedar, read-only; the
 * extraction request must match the saved one byte for byte). {@link #willow()} uses a HAND-AUTHORED extraction reply
 * (a test fixture, not Muse output) for a different fictional subject.
 */
final class SynthesisTrialHarness implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String URL = "https://api.meta.ai/v1/chat/completions";
    static final String MODEL = "muse-spark-1.3";
    static final int MAX_OUTPUT_TOKENS = 4096;
    static final String TIMEOUT = "60s";
    /** First words of every MuseAnswerSynthesizer prompt version: identifies a synthesis request (extraction never contains it). */
    static final String SYNTHESIS_MARKER = "You write the reply of Dita";
    static final Path ROOT = app.dita.eval.BenchPaths.locate().projectRoot();
    static final Path EXTRACTION_DIR = ROOT.resolve("build/controlled-muse-cedar");

    /** A stored message; {@code extract} = part of the single approved extraction batch. */
    record Msg(String id, String author, String text, boolean extract) {}

    /**
     * What a correct final answer looks like for one question (test expectation, not production logic).
     * {@code sources} empty = abstention expected. {@code forbidden}/{@code required}: lower-case substrings.
     */
    record Expect(Set<String> sources, List<String> forbidden, List<String> required) {
        static Expect abstain() { return new Expect(Set.of(), List.of(), List.of()); }
    }

    record Question(String text, Expect expect) {}

    /**
     * @param extractionRequest exact expected extraction request body, or null (hand-authored fixture: not compared)
     * @param extractionReply full HTTP body served for the one extraction request
     */
    record Fixture(String name, List<Msg> messages, String extractionRequest, String extractionReply, Map<String, Question> questions) {}

    static boolean extractionArtifactsPresent() {
        return Files.isRegularFile(EXTRACTION_DIR.resolve("request.json")) && Files.isRegularFile(EXTRACTION_DIR.resolve("recording.json"));
    }

    /** Cedar: m1/m2 by ONE author (synthetic1, extracted); m3/m4 by synthetic2 (stored and indexed, not extracted). */
    static Fixture cedar() throws Exception {
        Map<String, Question> q = new LinkedHashMap<>();
        q.put("q1", new Question("Is Dr. Cedar recommended for babies?", new Expect(Set.of("m1"), List.of("teen"), List.of())));
        q.put("q2", new Question("Is Dr. Cedar recommended for teenagers?", new Expect(Set.of("m2"), List.of("bab"), List.of())));
        q.put("q3", new Question("Is Dr. Cedar recommended?", new Expect(Set.of("m1", "m2"), List.of(), List.of("bab", "teen"))));
        q.put("q4", new Question("Is Dr. Cedar recommended for adults?", Expect.abstain()));   // unsupported population
        return new Fixture("cedar", List.of(
                new Msg("m1", "synthetic1", "Dr. Cedar on Maple Street is excellent for babies.", true),
                new Msg("m2", "synthetic1", "Dr. Cedar is not recommended for teenagers.", true),
                new Msg("m3", "synthetic2", "Dr. Birch's clinic is closed on Fridays.", false),
                new Msg("m4", "synthetic2", "Who is bringing snacks for Friday's trip?", false)),
                Files.readString(EXTRACTION_DIR.resolve("request.json")), Files.readString(EXTRACTION_DIR.resolve("recording.json")), q);
    }

    static String completion(String content) throws Exception {
        return JSON.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0)));
    }

    static Map<String, Object> item(String predicate, String id, String quote, String qualifier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ATTRIBUTED_STATEMENT"); m.put("certainty", "ASSERTED");
        m.put("subject_id", "coach_willow"); m.put("subject", "Coach Willow"); m.put("predicate", predicate);
        m.put("evidence", List.of(Map.of("message_id", id, "quote", quote)));
        m.put("qualifiers", List.of(Map.of("kind", "POPULATION", "message_id", id, "quote", qualifier)));
        m.put("entities", List.of());
        return m;
    }

    /**
     * Willow (different fictional subject, HAND-AUTHORED extraction reply = test fixture): w1/w2 by ONE author about two
     * populations; w3 by another author OPPOSING w1 about the SAME population (with {@code conflict}); w4 unrelated, not extracted.
     */
    static Fixture willow(boolean conflict) throws Exception {
        List<Msg> msgs = new ArrayList<>(List.of(
                new Msg("w1", "synthetic1", "Coach Willow is great for beginners.", true),
                new Msg("w2", "synthetic1", "Coach Willow is not suitable for competitive swimmers.", true)));
        List<Object> items = new ArrayList<>(List.of(
                item("recommends", "w1", "Coach Willow is great for beginners.", "for beginners"),
                item("advises_against", "w2", "Coach Willow is not suitable for competitive swimmers.", "for competitive swimmers")));
        if (conflict) {
            msgs.add(new Msg("w3", "synthetic3", "Coach Willow is not good for beginners.", true));
            items.add(item("advises_against", "w3", "Coach Willow is not good for beginners.", "for beginners"));
        }
        msgs.add(new Msg("w4", "synthetic2", "The pool car park closes at nine.", false));
        Map<String, Question> q = new LinkedHashMap<>();
        if (conflict) {
            q.put("c1", new Question("Is Coach Willow recommended for beginners?", new Expect(Set.of("w1", "w3"), List.of("competitive"), List.of())));
        } else {
            q.put("p1", new Question("Is Coach Willow recommended for beginners?", new Expect(Set.of("w1"), List.of("competitive"), List.of())));
            q.put("p2", new Question("Is Coach Willow recommended for competitive swimmers?", new Expect(Set.of("w2"), List.of("beginner"), List.of())));
            q.put("p3", new Question("Is Coach Willow recommended?", new Expect(Set.of("w1", "w2"), List.of(), List.of("beginner", "competitive"))));
            q.put("p4", new Question("Is Coach Willow recommended for toddlers?", Expect.abstain()));
        }
        String reply = completion(JSON.writeValueAsString(Map.of("items", items)));
        return new Fixture(conflict ? "willow-conflict" : "willow", List.copyOf(msgs), null, reply, q);
    }

    /** Serves one synthesis request; {@code qid} identifies the question being asked. */
    @FunctionalInterface interface SynthesisReply { MuseTransport.Reply reply(String qid, String body,
                                                                              java.net.URI url, Map<String, String> headers,
                                                                              java.time.Duration timeout) throws Exception; }

    final ConfigurableApplicationContext ctx;
    final GroupScope group;
    final String prefix, backend;
    final Fixture fixture;
    final List<GroundedSynthesis.Result> results;
    final List<String> synthesisRequests;
    private final String[] currentQuestion;

    private SynthesisTrialHarness(ConfigurableApplicationContext ctx, GroupScope group, String prefix, String backend, Fixture fixture,
                                  List<GroundedSynthesis.Result> results, List<String> synthesisRequests, String[] current) {
        this.ctx = ctx; this.group = group; this.prefix = prefix; this.backend = backend; this.fixture = fixture;
        this.results = results; this.synthesisRequests = synthesisRequests; this.currentQuestion = current;
    }

    /** Cedar fixture with the current prompt (V2). */
    static SynthesisTrialHarness start(String backend, int maxCalls, Function<String, String> env, SynthesisReply synthesis) throws Exception {
        return start(backend, maxCalls, env, MuseAnswerSynthesizer.Prompt.V2, cedar(), synthesis);
    }

    /**
     * @param backend IN_MEMORY or POSTGRES (PostgreSQL + Neo4j + Vespa, credentials from DITA_TEST_* variables)
     * @param maxCalls process-wide Muse call cap (the extraction reply counts as one call)
     * @param env environment seen by Dita (the API key is only ever present in an approved LIVE run)
     * @param prompt synthesis instruction version (production property dita.memory.answers.synthesis.prompt)
     */
    static SynthesisTrialHarness start(String backend, int maxCalls, Function<String, String> env, MuseAnswerSynthesizer.Prompt prompt,
                                       Fixture fx, SynthesisReply synthesis) throws Exception {
        assertTrue(Set.of("IN_MEMORY", "POSTGRES").contains(backend));
        String prefix = "synth-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> p = new HashMap<>(Map.of(
                "dita.telegram.enabled", "false", "dita.coordination.enabled", "false",
                "dita.muse.enabled", "true", "dita.muse.transport", "HTTP",
                "dita.muse.chat-completions-url", URL, "dita.muse.model", MODEL,
                "dita.muse.max-output-tokens", Integer.toString(MAX_OUTPUT_TOKENS),
                "dita.muse.max-calls-per-process", Integer.toString(maxCalls),
                "dita.muse.timeout", TIMEOUT, "dita.extraction.worker-enabled", "false"));
        p.put("dita.muse.response-format", "PROMPT_ONLY");
        p.put("dita.muse.reasoning-effort", "LOW");
        // Synthesis ON for THIS test process only. allow-live is required because the trial transport is not the
        // RecordedMuseTransport class; the transport below decides per request whether anything leaves the machine.
        p.put("dita.memory.answers.synthesis.enabled", "true");
        p.put("dita.memory.answers.synthesis.allow-live", "true");
        p.put("dita.memory.answers.synthesis.prompt", prompt.name());
        p.put("dita.storage.backend", backend);
        p.put("dita.storage.relay-consumer-prefix", prefix);
        if (backend.equals("POSTGRES")) {
            for (String v : KnowledgeSlicePostgresTest.REQUIRED) assertNotNull(System.getenv(v), v + " missing");
            p.putAll(Map.of("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL"),
                    "dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD",
                    "dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD",
                    "dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL"),
                    "dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD",
                    "dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")));
        }
        List<GroundedSynthesis.Result> results = Collections.synchronizedList(new ArrayList<>());
        List<String> synthesisRequests = Collections.synchronizedList(new ArrayList<>());
        String[] current = {null};
        int[] extractionCalls = {0};
        MuseTransport transport = (url, headers, body, timeout) -> {
            assertEquals(URL, url.toString(), "only the documented endpoint");
            if (body.contains(SYNTHESIS_MARKER)) {
                assertNotNull(current[0], "synthesis request outside a trial question");
                synthesisRequests.add(body);
                return synthesis.reply(current[0], body, url, headers, timeout);
            }
            assertEquals(0, extractionCalls[0]++, "exactly one extraction request (served from a fixed reply; no network)");
            if (fx.extractionRequest() != null)
                assertEquals(fx.extractionRequest(), body, "extraction request must equal the saved request byte for byte");
            return new MuseTransport.Reply(200, fx.extractionReply(), Map.of());
        };
        GroundedSynthesis.Observer observer = results::add;
        var ctx = new SpringApplicationBuilder(DitaApplication.class).initializers(c -> {
            c.getBeanFactory().registerSingleton("ditaEnv", env);
            c.getBeanFactory().registerSingleton("museTransport", transport);
            c.getBeanFactory().registerSingleton("synthesisTrialObserver", observer);
        }).run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        try {
            var g = new GroupScope(prefix);
            ctx.getBean(GroupRegistry.class).ensureRegistered(prefix, "synthetic", prefix, GroupDirectory.ChatType.SYNTHETIC, SourceKind.SYNTHETIC);
            var x = ctx.getBean(ExtractionService.class);
            var ingest = ctx.getBean(IngestionPipeline.class);
            Instant t0 = Instant.parse("2026-11-03T12:00:00Z");
            List<Msg> extracted = fx.messages().stream().filter(Msg::extract).toList();
            List<Msg> other = fx.messages().stream().filter(m -> !m.extract()).toList();
            for (int i = 0; i < extracted.size(); i++) {
                var mm = extracted.get(i);
                var m = new Message(mm.id(), prefix, mm.author(), t0.plusSeconds(i * 30L), null, mm.text(), SourceKind.SYNTHETIC, false);
                ingest.ingest(g, m);
                assertTrue(x.enqueue(g, m));
            }
            var batches = x.collect(g);
            assertEquals(1, batches.size());
            x.approve(g, batches.get(0).id(), "offline-fixed-extraction-reply");
            var rep = x.process(g, batches.get(0).id()).orElseThrow();
            assertEquals(ExtractionBatch.Status.DONE, rep.status(), "Extraction status: " + rep.error());
            assertEquals(1, extractionCalls[0]);
            assertEquals(extracted.size(), ctx.getBean(MessageStore.class).knowledge(g).size(), "every fixture statement became knowledge");
            for (int i = 0; i < other.size(); i++) {
                var d = other.get(i);
                ingest.ingest(g, new Message(d.id(), prefix, d.author(), t0.plusSeconds(600 + i * 30L), null, d.text(), SourceKind.SYNTHETIC, false));
            }
            if (backend.equals("POSTGRES")) SyntheticLabReplayTest.awaitDrained(ctx.getBean(PostgresOutboxRelay.class), prefix);
            return new SynthesisTrialHarness(ctx, g, prefix, backend, fx, results, synthesisRequests, current);
        } catch (Exception | Error e) {
            ctx.close();
            throw e;
        }
    }

    MessageStore store() { return ctx.getBean(MessageStore.class); }

    /** Everything one question produced, in pipeline order. */
    record QuestionRun(String qid, String question, KnowledgeAnswerComposer.SelectionTrace selection, GroundedAnswer baseline,
                       List<String> requests, List<GroundedSynthesis.Result> synthesis, GroundedAnswer answer) {
        /** Success = the validator accepted the draft AND that text is what Dita returned. */
        boolean synthesized() {
            return synthesis.size() == 1 && synthesis.get(0).outcome() == GroundedSynthesis.Outcome.SYNTHESIZED
                    && synthesis.get(0).answer().text().equals(answer.text());
        }
    }

    QuestionRun ask(String qid) {
        String q = fixture.questions().get(qid).text();
        int maxQuote = ctx.getBean(DitaSettings.class).raw().memory().answers().maxQuoteChars();
        // Diagnostic only: the same selection the production composer makes (same store, retriever and settings).
        var trace = new KnowledgeAnswerComposer(store(), maxQuote, id -> id, ctx.getBean(KnowledgeRetriever.class)).select(group, q).trace();
        var queries = ctx.getBean(QueryService.class);
        assertTrue(queries.synthesisEnabled(), "production QueryService must carry the synthesis stage");
        GroundedAnswer baseline = queries.withSynthesis(null).ask(group, "synthetic1", q);   // no Muse call
        int r0 = results.size(), s0 = synthesisRequests.size();
        currentQuestion[0] = qid;
        GroundedAnswer answer;
        try { answer = queries.ask(group, "synthetic1", q); }
        finally { currentQuestion[0] = null; }
        return new QuestionRun(qid, q, trace, baseline, List.copyOf(synthesisRequests.subList(s0, synthesisRequests.size())),
                List.copyOf(results.subList(r0, results.size())), answer);
    }

    // ---- evidence ----

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The user message Dita sent to Muse (question, draft, evidence, replaced, conflicting), parsed from the request. */
    static JsonNode userPayload(String body) throws Exception {
        JsonNode b = JSON.readTree(body);
        return JSON.readTree(b.path("messages").get(1).path("content").asText());
    }

    static String systemPrompt(String body) throws Exception {
        return JSON.readTree(body).path("messages").get(0).path("content").asText();
    }

    /** Which stored messages' text appears anywhere in the request bytes. */
    Map<String, Boolean> conversationInRequest(String body) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Msg m : fixture.messages()) out.put(m.id(), body.contains(m.text()) || body.contains(JSON.valueToTree(m.text()).toString()));
        return out;
    }

    static List<Map<String, Object>> citations(GroundedAnswer a) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Citation c : a.citations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message_id", c.messageId()); m.put("author", c.authorId()); m.put("quote", c.quote());
            m.put("polarity", c.polarity()); m.put("qualified", c.qualified());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> answer(GroundedAnswer a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("found", a.found()); m.put("text", a.text()); m.put("citations", citations(a));
        m.put("knowledge_ids", a.knowledgeIds()); m.put("conflicting", a.conflicting());
        return m;
    }

    static Set<String> sources(GroundedAnswer a) {
        Set<String> s = new TreeSet<>();
        a.citations().forEach(c -> s.add(c.messageId()));
        return s;
    }

    /** One author among the cited evidence → no wording may imply several members or a disagreement between them. */
    static final String MULTI_MEMBER = "(?s).*\\b(members'? (views|opinions)|members disagree|views differ|disagree|differ(s)? by|opinions differ)\\b.*";

    /**
     * Product criteria applied to the FINAL answer exactly as returned. A pass by a deterministic fallback is reported as
     * such by the caller and is never a synthesis success.
     */
    Map<String, Object> quality(String qid, GroundedAnswer a) {
        Expect e = fixture.questions().get(qid).expect();
        Set<String> src = sources(a);
        String t = a.text().toLowerCase(Locale.ROOT);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expected_sources", new TreeSet<>(e.sources()));
        if (e.sources().isEmpty()) {
            boolean abstained = !a.found() && a.citations().isEmpty();
            m.put("abstained", abstained);
            m.put("pass", abstained);
            return m;
        }
        boolean sourcesOk = a.found() && src.equals(e.sources());
        m.put("sources_match", sourcesOk);
        boolean forbiddenOk = e.forbidden().stream().noneMatch(t::contains);
        boolean requiredOk = e.required().stream().allMatch(t::contains);
        m.put("excludes_other_context", forbiddenOk);
        m.put("presents_required_contexts", requiredOk);
        long authors = a.citations().stream().map(Citation::authorId).distinct().count();
        boolean wordingOk = authors > 1 || !t.matches(MULTI_MEMBER);
        m.put("cited_authors", authors);
        m.put("no_multi_member_disagreement_wording_for_one_author", wordingOk);
        boolean conflictOk = authors > 1 || !a.conflicting();
        m.put("not_flagged_conflicting_for_one_author", conflictOk);
        m.put("pass", sourcesOk && forbiddenOk && requiredOk && wordingOk && conflictOk);
        return m;
    }

    @Override public void close() { ctx.close(); }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
