package app.dita.app;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.GroupDirectory;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.model.GroupRegistry;
import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.capability.CapabilityScorer;
import app.dita.eval.capability.DitaProductRunner;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;
import app.dita.eval.runner.Prompt;
import app.dita.eval.runner.RunnerFileAccess;
import app.dita.eval.scorer.ScorerFileAccess;
import app.dita.store.postgres.PostgresOutboxRelay;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-044 Dita Memory Capability Test on the REAL stack: the running DitaApplication with
 * dita.storage.backend=POSTGRES (PostgreSQL truth, relay, Neo4j + Vespa retrieval). Each (corpus, as_of) is replayed
 * into its own fresh synthetic group (plus the isolation decoy in another group) through the application's ingestion
 * and deletion services; the relay drains to Neo4j/Vespa; then every dev question is asked through the application's
 * QueryService. Answers are scored with the same scorer as the in-memory run. No model, no Telegram, synthetic only.
 * Opt-in: -Ddita.capability.run=true plus the DITA_TEST_* services (scripts/capability-verify.sh sets them).
 */
class CapabilityPostgresRunTest {
    static final List<String> REQUIRED = List.of("DITA_TEST_DB_URL", "DITA_TEST_DB_PASSWORD", "DITA_TEST_RELAY_PASSWORD",
            "DITA_TEST_NEO4J_URL", "DITA_TEST_NEO4J_PASSWORD", "DITA_TEST_VESPA_URL");

    @Test void realStackCapabilityDev() throws Exception {
        assumeTrue(Boolean.getBoolean("dita.capability.run"), "set -Ddita.capability.run=true");
        for (String v : REQUIRED) assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: NOT EXECUTED");
        String run = UUID.randomUUID().toString().substring(0, 8), prefix = "cap-" + run;
        Map<String, String> props = Map.ofEntries(
                Map.entry("dita.storage.backend", "POSTGRES"),
                Map.entry("dita.storage.relay-consumer-prefix", prefix),
                Map.entry("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL")),
                Map.entry("dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD"),
                Map.entry("dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD"),
                Map.entry("dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL")),
                Map.entry("dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD"),
                Map.entry("dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL")),
                Map.entry("dita.processing.dispatch.poll-interval", "50ms"));
        BenchPaths paths = BenchPaths.locate();
        RunnerFileAccess files = new RunnerFileAccess(paths);
        List<String> lines = new ArrayList<>();
        try (ConfigurableApplicationContext c = new SpringApplicationBuilder(DitaApplication.class)
                .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new))) {
            IngestionPipeline ingest = c.getBean(IngestionPipeline.class);
            DeletionService deletions = c.getBean(DeletionService.class);
            QueryService queries = c.getBean(QueryService.class);
            GroupRegistry groups = c.getBean(GroupRegistry.class);
            PostgresOutboxRelay relay = c.getBean(PostgresOutboxRelay.class);
            Map<String, GroupScope> replayed = new HashMap<>();
            int n = 0;
            for (Prompt p : files.prompts("dev")) {
                String key = p.corpusId() + "|" + p.asOf();
                GroupScope scope = replayed.get(key);
                if (scope == null) {
                    scope = new GroupScope(prefix + "-" + (n++));
                    replay(files.corpus(p.corpusId()), scope, p.asOf(), groups, ingest, deletions);
                    GroupScope decoy = new GroupScope(scope.groupId() + "-decoy");
                    files.decoyFor(p.corpusId()).ifPresent(d -> replay(d, decoy, p.asOf(), groups, ingest, deletions));
                    awaitDrained(relay, prefix);
                    replayed.put(key, scope);
                }
                long t0 = System.nanoTime();
                GroundedAnswer a = queries.ask(scope, DitaProductRunner.REQUESTER, p.question());
                long dt = System.nanoTime() - t0;
                for (Citation ci : a.citations()) assertTrue(ci.messageId() != null);   // scope enforced by the store (RLS)
                ObjectNode o = Json.object();
                o.put("qid", p.qid()).put("arm", DitaProductRunner.ARM).put("split", "dev").put("backend", "postgres+neo4j+vespa");
                o.put("content", Json.compact(DitaProductRunner.toOutput(a))).put("finish_reason", "stop");
                o.put("knowledge_items_used", a.knowledgeIds().size()).put("ask_ms", dt / 1e6);
                lines.add(Json.compact(o));
            }
        }
        assertEquals(35, lines.size());
        files.writeResult("capability-dev-postgres-answers.jsonl", String.join("\n", lines) + "\n");
        ScorerFileAccess scorer = new ScorerFileAccess(paths);
        var res = CapabilityScorer.score(scorer, "dev", "capability-dev-postgres-answers.jsonl");
        scorer.writeResult("capability-dev-postgres.json", Json.pretty(res.json()));
        scorer.writeResult("capability-dev-postgres.md", res.markdown());
    }

    /** Same event order as StoreReplay: messages sent ≤ as_of in time order; deletions at deleted_at (DEC-012). */
    static void replay(Corpus corpus, GroupScope scope, Instant asOf, GroupRegistry groups, IngestionPipeline ingest, DeletionService deletions) {
        groups.ensureRegistered(scope.groupId(), "synthetic", scope.groupId(), GroupDirectory.ChatType.SYNTHETIC, SourceKind.SYNTHETIC);
        record Ev(Instant at, int order, CorpusMessage m, boolean delete) {}
        List<Ev> events = new ArrayList<>();
        for (CorpusMessage m : corpus.messages()) {
            if (m.sentAt().isAfter(asOf)) continue;
            events.add(new Ev(m.sentAt(), 0, m, false));
            if (m.deletedBy(asOf)) events.add(new Ev(m.deletedAt(), 1, m, true));
        }
        events.sort(Comparator.comparing(Ev::at).thenComparingInt(Ev::order).thenComparing(e -> e.m().id()));
        for (Ev e : events) {
            CorpusMessage m = e.m();
            if (e.delete()) deletions.delete(scope, m.id(), m.deletedAt(), DeletionSource.PLATFORM_EVENT);
            else ingest.ingest(scope, new Message(m.id(), scope.groupId(), m.author(), m.sentAt(), m.replyTo(), m.text(), SourceKind.SYNTHETIC, false));
        }
    }

    static void awaitDrained(PostgresOutboxRelay relay, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (relay.lag(prefix + "-neo4j") == 0 && relay.lag(prefix + "-vespa") == 0
                    && !relay.health(prefix + "-neo4j").stalled() && !relay.health(prefix + "-vespa").stalled()) return;
            Thread.sleep(50);
        }
        fail("relay did not drain to Neo4j/Vespa within 60s: " + relay.health(prefix + "-neo4j") + " " + relay.health(prefix + "-vespa"));
    }
}
