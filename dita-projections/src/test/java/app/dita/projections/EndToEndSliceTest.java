package app.dita.projections;

import app.dita.core.IngestionPipeline;
import app.dita.core.answer.AnswerVerifier;
import app.dita.core.answer.Citation;
import app.dita.core.answer.ExtractiveAnswerComposer;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DispatchSettings;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.projection.ProjectionListener;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.HybridRetriever;
import app.dita.core.retrieval.Retriever;
import app.dita.store.postgres.PostgresEventPublisher;
import app.dita.store.postgres.PostgresMessageStore;
import app.dita.store.postgres.PostgresOutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * First end-to-end Dita slice on the REAL stack: synthetic ingest → PostgreSQL (truth + outbox, RLS) → durable relay →
 * Neo4j graph + Vespa index → Vespa BM25 + Neo4j graph expansion → extractive answer verified against Postgres →
 * early deletion propagating to both projections. Synthetic data only. Gated: SKIPPED unless all three services are set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndSliceTest {
    static final String DB = System.getenv("DITA_TEST_DB_URL"), NEO = System.getenv("DITA_TEST_NEO4J_URL"), VESPA = System.getenv("DITA_TEST_VESPA_URL");
    static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    PostgresMessageStore store;
    PostgresEventPublisher publisher;
    PostgresOutboxRelay relay;
    Neo4jProjection neo4j;
    VespaProjection vespa;
    GroupScope a, b;
    String run;

    @BeforeAll void setUp() {
        assumeTrue(DB != null && NEO != null && VESPA != null, "DITA_TEST_DB_URL / DITA_TEST_NEO4J_URL / DITA_TEST_VESPA_URL not all set: E2E skipped");
        // N-29: only ever write to loopback services on the Dita host ports, never to a default-port local service.
        for (String u : List.of(DB, NEO, VESPA)) {
            assertTrue(u.matches("(jdbc:postgresql|http)://127\\.0\\.0\\.1:5\\d{4}(/.*)?"), "refusing non-Dita endpoint " + u.replaceAll("//.*@", "//"));
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(DB, System.getenv().getOrDefault("DITA_TEST_DB_USER", "dita_app"),
                System.getenv().getOrDefault("DITA_TEST_DB_PASSWORD", ""));
        ds.setDriverClassName("org.postgresql.Driver");
        store = new PostgresMessageStore(ds);
        publisher = new PostgresEventPublisher(ds);
        DriverManagerDataSource relayDs = new DriverManagerDataSource(DB, "dita_relay", System.getenv().getOrDefault("DITA_TEST_RELAY_PASSWORD", ""));
        relayDs.setDriverClassName("org.postgresql.Driver");
        relay = new PostgresOutboxRelay(relayDs, DispatchSettings.DEFAULTS, Clock.systemUTC());           // N-27: separate role
        neo4j = new Neo4jProjection(NEO, "neo4j", System.getenv().getOrDefault("DITA_TEST_NEO4J_PASSWORD", "dita-dev-not-secret"));
        vespa = new VespaProjection(VESPA);
        neo4j.ensureSchema();
        run = UUID.randomUUID().toString().substring(0, 8);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        a = group(jdbc, "e2e-a-" + run);
        b = group(jdbc, "e2e-b-" + run);
        ProjectionSource truth = ProjectionSource.of(store);
        relay.registerFromNow(ProjectionListener.consumer("e2e-neo4j-" + run, neo4j, truth));
        relay.registerFromNow(ProjectionListener.consumer("e2e-vespa-" + run, vespa, truth));
    }

    static GroupScope group(JdbcTemplate jdbc, String id) {
        jdbc.update("INSERT INTO chat_group (id, platform, platform_chat_id, chat_type, source_kind) VALUES (?, 'synthetic', ?, 'synthetic', 'SYNTHETIC')", id, id);
        return new GroupScope(id);
    }

    Message msg(GroupScope g, String id, String author, long minutes, String replyTo, String text) {
        return new Message(id, g.groupId(), author, T0.plusSeconds(minutes * 60), replyTo, text, SourceKind.SYNTHETIC, false);
    }

    Retriever hybrid() { return new HybridRetriever(List.of(vespa, neo4j.expanding(vespa, 2, 0.8, 0.5)), List.of(1.0, 1.0), 60, 3); }

    GroundedAnswer ask(GroupScope g, String q) {
        return new AnswerVerifier(store).verify(g, new ExtractiveAnswerComposer(hybrid(), store, 200).answer(g, q, 6));
    }

    static Set<String> ids(List<Hit> hits) { return hits.stream().map(Hit::messageId).collect(Collectors.toSet()); }

    @Test @Order(1) void ingestRelayAndProject() {
        var p = new IngestionPipeline(store, new RuleLinkInferencer(), 50, publisher);
        p.ingest(a, msg(a, "m1", "dana", 0, null, "Does anyone know a good pediatrician near the park?"));
        p.ingest(a, msg(a, "m2", "yossi", 3, "m1", "Dr. Levi on Herzl St. is excellent, we've been going for years"));
        p.ingest(a, msg(a, "m3", "maya", 5, null, "ממליצה על ד\"ר לוי, סבלנית מאוד עם תינוקות"));
        p.ingest(a, msg(a, "m4", "noa", 7, null, "Hey, who is bringing the snacks for Friday's trip?"));
        p.ingest(a, msg(a, "m5", "ron", 9, null, "Dr. Levi is fine for babies but avoid him for teenagers, he was dismissive with my son"));
        p.ingest(a, msg(a, "m6", "amir", 10, "m4", "I can bring snacks"));
        p.ingest(a, msg(a, "m7", "dana", 12, null, "thanks! is she also good with allergies?"));
        p.ingest(b, msg(b, "x1", "eve", 1, null, "Dr. Levi is the worst pediatrician in town, avoid Herzl St."));
        int delivered = relay.drainUntilIdle(20);
        assertTrue(delivered >= 8, "relay delivered " + delivered);
        assertConsumersHealthy("after ingest");

        List<List<JsonNode>> nodes = neo4j.query("MATCH (m:Message {groupId:$g}) RETURN count(m)", Map.of("g", a.groupId()));
        assertEquals(7, nodes.get(0).get(0).asInt(), "graph has group A's 7 messages");
        assertEquals(1, neo4j.query("MATCH (:Message {uid:$f})-[r:LINK {kind:'EXPLICIT_REPLY'}]->(:Message {uid:$t}) RETURN count(r)",
                Map.of("f", a.groupId() + "|m2", "t", a.groupId() + "|m1")).get(0).get(0).asInt());
        assertEquals(0, neo4j.query("MATCH (m:Message {groupId:$g}) WHERE m.text IS NOT NULL RETURN count(m)", Map.of("g", a.groupId())).get(0).get(0).asInt(),
                "Neo4j never stores message text");
        assertEquals(200, vespa.status(a.groupId(), "m2"));
    }

    @Test @Order(2) void vespaRetrievalIsGroupScoped() {
        Set<String> hits = ids(vespa.retrieve(a, "pediatrician Levi Herzl", 10));
        assertTrue(hits.containsAll(Set.of("m1", "m2", "m5")), "hits " + hits);
        assertFalse(hits.contains("x1"));
        assertEquals(Set.of("x1"), ids(vespa.retrieve(b, "pediatrician Levi Herzl", 10)));
        assertTrue(ids(vespa.retrieve(a, "לוי", 10)).contains("m3"), "Hebrew lexical match");
    }

    @Test @Order(3) void neo4jExpansionRecoversContextAndFlagsInference() {
        List<Hit> hits = neo4j.expanding(vespa, 2, 0.8, 0.5).retrieve(a, "allergies", 10);
        Hit m2 = hits.stream().filter(h -> h.messageId().equals("m2")).findFirst().orElseThrow(() -> new AssertionError("hits " + hits));
        assertTrue(m2.inferredContext(), "m7→m2 is an inferred link");
        assertTrue(ids(hits).contains("m1"), "second hop reaches the original question");
        assertFalse(ids(hits).contains("x1"));
    }

    @Test @Order(4) void groundedAnswerOnRealStack() {
        GroundedAnswer ans = ask(a, "Is Dr. Levi recommended?");
        assertTrue(ans.found());
        assertTrue(ans.conflicting(), ans.text());
        for (Citation c : ans.citations()) {
            assertTrue(store.find(a, c.messageId()).orElseThrow().text().contains(c.quote()));
            assertNotEquals("x1", c.messageId());
        }
    }

    @Test @Order(5) void earlyDeletionPropagatesToPostgresNeo4jAndVespa() {
        var deletions = new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, publisher, 50);
        var e = deletions.delete(a, "m5", T0.plusSeconds(9 * 60 + 120), DeletionSource.FORGET_COMMAND).orElseThrow();
        assertEquals(DeletionMode.EARLY_PURGE, e.mode());
        relay.drainUntilIdle(20);
        assertConsumersHealthy("after the early deletion of m5");

        assertNull(store.find(a, "m5").orElseThrow().text());
        assertTrue(store.history(a, "m5").stream().allMatch(h -> h.text() == null));
        assertEquals(404, vespa.status(a.groupId(), "m5"));
        assertFalse(ids(vespa.retrieve(a, "teenagers dismissive", 10)).contains("m5"));
        var row = neo4j.query("MATCH (m:Message {uid:$u}) RETURN m.deleted, m.deletionMode, m.authorId", Map.of("u", a.groupId() + "|m5")).get(0);
        assertTrue(row.get(0).asBoolean());
        assertEquals("EARLY_PURGE", row.get(1).asText());
        assertTrue(row.get(2).isNull(), "tombstone keeps no author (N-31)");
        assertEquals(0, neo4j.query("MATCH (:Message {uid:$u})-[r:LINK]-() RETURN count(r)", Map.of("u", a.groupId() + "|m5")).get(0).get(0).asInt(),
                "early deletion removes every link that used m5");
        GroundedAnswer after = ask(a, "Is Dr. Levi recommended?");
        assertTrue(after.citations().stream().noneMatch(c -> c.messageId().equals("m5")));
        assertFalse(after.conflicting(), "the only warning was deleted");
    }

    @Test @Order(6) void replayAndReconcileNeverRestoreDeletedText() {
        ProjectionSource truth = ProjectionSource.of(store);
        vespa.onMessage(a.groupId(), "m5", truth);                // replay of the original ingest event
        neo4j.onMessage(a.groupId(), "m5", truth);
        assertEquals(404, vespa.status(a.groupId(), "m5"));
        assertTrue(neo4j.query("MATCH (m:Message {uid:$u}) RETURN m.deleted", Map.of("u", a.groupId() + "|m5")).get(0).get(0).asBoolean());
        vespa.reconcile(a.groupId(), truth);
        neo4j.reconcile(a.groupId(), truth);
        assertEquals(404, vespa.status(a.groupId(), "m5"));
        assertFalse(relay.blockedOnDeletion("e2e-vespa-" + run));
        assertFalse(relay.blockedOnDeletion("e2e-neo4j-" + run));
        assertConsumersHealthy("after replay and reconcile");
    }

    /**
     * A deletion is never skipped, so a projection that throws on it is RETRIED, not blocked at first: the purged
     * message document can already be gone while the consumer is stuck. Checking only the projected state (or only
     * blocked_on_deletion, set after maxAttempts) misses that. Every consumer must be fully drained and error-free.
     */
    private void assertConsumersHealthy(String when) {
        for (String c : List.of("e2e-neo4j-" + run, "e2e-vespa-" + run)) {
            PostgresOutboxRelay.ConsumerHealth h = relay.health(c);
            assertFalse(h.stalled(), c + " is stalled " + when + ": " + h);
        }
    }
}
