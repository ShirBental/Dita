package app.dita.projections;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.KnowledgeItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Knowledge in the graph on a REAL Neo4j (truth = in-memory store with extracted-style RFC-002 items).
 * SKIPPED unless DITA_TEST_NEO4J_URL is set; refuses anything but a loopback Dita port (N-29). Synthetic data only.
 */
class Neo4jKnowledgeProjectionTest {
    static final String NEO = System.getenv("DITA_TEST_NEO4J_URL");
    Neo4jProjection neo4j;
    KnowledgeFixture a, b;

    @BeforeEach void setUp() {
        assumeTrue(NEO != null && !NEO.isBlank(), "DITA_TEST_NEO4J_URL not set: Neo4j knowledge projection test skipped");
        assertTrue(NEO.matches("http://127\\.0\\.0\\.1:5\\d{4}/?"), "refusing non-Dita endpoint");
        neo4j = new Neo4jProjection(NEO, "neo4j", System.getenv().getOrDefault("DITA_TEST_NEO4J_PASSWORD", "dita-dev-not-secret"));
        neo4j.ensureSchema();
        String run = UUID.randomUUID().toString().substring(0, 8);
        a = new KnowledgeFixture("kp-a-" + run).withKnowledge();
        b = new KnowledgeFixture("kp-b-" + run).withKnowledge();
    }

    int count(String cypher, String g) { return neo4j.query(cypher, Map.of("g", g)).get(0).get(0).asInt(); }

    void project(KnowledgeFixture f) {
        for (String m : List.of("c02", "c04", "c17")) neo4j.onMessage(f.g.groupId(), m, f.truth());
        for (KnowledgeItem k : f.store.knowledge(f.g)) neo4j.onKnowledge(f.g.groupId(), k.id(), f.truth());
    }

    @Test void knowledgeNodesDerivedFromAndRelationsWithoutText() {
        project(a);
        project(b);
        String g = a.g.groupId();
        assertEquals(3, count("MATCH (k:Knowledge {groupId:$g}) RETURN count(k)", g));
        assertEquals(0, count("MATCH (k:Knowledge {groupId:$g}) WHERE k.statement IS NOT NULL OR k.subject IS NOT NULL OR k.text IS NOT NULL "
                + "OR k.qualifiers IS NOT NULL OR k.attributedTo IS NOT NULL RETURN count(k)", g), "no wording in the graph");
        List<List<JsonNode>> props = neo4j.query("MATCH (k:Knowledge {groupId:$g, id:'k-c17'}) RETURN k.status, k.certainty, k.speakerId, k.subjectId", Map.of("g", g));
        assertEquals(List.of("REPORTED_CLAIM", "HEARSAY", "eve", "ent-katz"), props.get(0).stream().map(JsonNode::asText).toList());
        assertEquals(3, count("MATCH (k:Knowledge {groupId:$g})-[d:DERIVED_FROM {role:'ASSERTS'}]->(m:Message {groupId:$g}) RETURN count(d)", g));
        assertEquals(1, count("MATCH (:Knowledge {groupId:$g, id:'k-c04'})-[r:RELATES {kind:'QUALIFIES'}]->(:Knowledge {groupId:$g, id:'k-c02'}) RETURN count(r)", g));
        assertEquals(0, count("MATCH (k:Knowledge {groupId:$g})-[r]-(x) WHERE x.groupId <> $g RETURN count(r)", g), "no cross-group edges");
        assertEquals(3, count("MATCH (k:Knowledge {groupId:$g}) RETURN count(k)", b.g.groupId()));
    }

    @Test void removalDeletionAndReconcile() {
        project(a);
        String g = a.g.groupId();
        a.store.removeKnowledge(a.g, "k-c02");                                // cascades the relation in truth
        neo4j.onKnowledge(g, "k-c02", a.truth());
        assertEquals(0, count("MATCH (k:Knowledge {groupId:$g, id:'k-c02'}) RETURN count(k)", g));
        assertEquals(0, count("MATCH (:Knowledge {groupId:$g})-[r:RELATES]-() RETURN count(r)", g));

        // Early purge of c17 removes k-c17 in truth; the deletion event removes it from the graph.
        a.store.tombstone(a.g, "c17", KnowledgeFixture.T0.plusSeconds(17 * 60 + 30), DeletionMode.EARLY_PURGE, DeletionSource.FORGET_COMMAND);
        a.store.removeKnowledge(a.g, "k-c17");
        neo4j.onMessageDeleted(new DeletionEvent(g, "c17", DeletionMode.EARLY_PURGE, DeletionSource.FORGET_COMMAND,
                KnowledgeFixture.T0, KnowledgeFixture.T0, List.of("k-c17"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), a.truth());
        assertEquals(0, count("MATCH (k:Knowledge {groupId:$g, id:'k-c17'}) RETURN count(k)", g));

        // Reconcile adds an item the projection never saw (KnowledgeTruth list API) and keeps the survivor.
        a.store.addKnowledge(a.g, a.c02());
        neo4j.reconcile(g, a.truth());
        assertEquals(List.of("k-c02", "k-c04"), neo4j.query("MATCH (k:Knowledge {groupId:$g}) RETURN k.id ORDER BY k.id", Map.of("g", g))
                .stream().map(r -> r.get(0).asText()).toList());
    }
}
