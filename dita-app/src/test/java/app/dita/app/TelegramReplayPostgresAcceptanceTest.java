package app.dita.app;

import app.dita.core.model.KnowledgeItem;
import app.dita.core.store.MessageStore;
import app.dita.projections.Neo4jProjection;
import app.dita.projections.VespaProjection;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-051 ACCEPTANCE TEST on the demo backend: PostgreSQL (truth + outbox) + Neo4j + Vespa, driven only through a
 * synthetic Telegram replay. Gated: SKIPPED (= NOT EXECUTED, never a pass) unless the Dita test services are configured
 * (same variables as KnowledgeSlicePostgresTest; scripts/e2e-replay-verify.sh). Requires V5.
 */
class TelegramReplayPostgresAcceptanceTest extends TelegramReplayAcceptance {
    /** TASK-052: set DITA_TEST_DENSE_MIN_COSINE to run the same acceptance with dense retrieval on (hybrid Vespa package required). */
    static final String DENSE = System.getenv("DITA_TEST_DENSE_MIN_COSINE");
    static boolean dense() { return DENSE != null && !DENSE.isBlank(); }

    @Override Map<String, Object> backendProperties() {
        Map<String, Object> p = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("dita.storage.backend", "POSTGRES"),
                Map.entry("dita.storage.relay-consumer-prefix", "e2e-" + UUID.randomUUID().toString().substring(0, 8)),
                Map.entry("dita.storage.postgres.url", System.getenv("DITA_TEST_DB_URL")),
                Map.entry("dita.storage.postgres.app-password-env-var", "DITA_TEST_DB_PASSWORD"),
                Map.entry("dita.storage.postgres.relay-password-env-var", "DITA_TEST_RELAY_PASSWORD"),
                Map.entry("dita.storage.neo4j.url", System.getenv("DITA_TEST_NEO4J_URL")),
                Map.entry("dita.storage.neo4j.password-env-var", "DITA_TEST_NEO4J_PASSWORD"),
                Map.entry("dita.storage.vespa.url", System.getenv("DITA_TEST_VESPA_URL"))));
        if (dense()) { p.put("dita.retrieval.dense.enabled", "true"); p.put("dita.retrieval.dense.min-cosine", DENSE); }
        return p;
    }

    /**
     * Dense mode: the lexically poor m7 question is answered from Dana's m1 item through the production hybrid knowledge
     * retriever, and the plain BM25 branch alone does not retrieve that item.
     */
    @Override void assertSemantic(ConfigurableApplicationContext c, List<KnowledgeItem> knowledge, String replyId, String text) {
        String q = "Which doctor is good for infants?";
        if (!dense()) {
            // TASK-052 differential control on the same production QueryService wiring: without dense, m7 is not
            // answered from m1. Dense mode (below) must answer it from m1. This is the evidence that dense adds value.
            assertFalse(c.getBean(MessageStore.class).ditaRepliesCiting(scope, mid(1)).contains(replyId),
                    "dense off: the lexical production path does not answer m7 from m1: " + text);
            return;
        }
        var retriever = c.getBean(app.dita.core.knowledge.KnowledgeRetriever.class);
        assertTrue(retriever instanceof app.dita.projections.HybridKnowledgeRetriever, "production knowledge retriever is hybrid: " + retriever.getClass());
        var metrics = ((app.dita.projections.HybridKnowledgeRetriever) retriever).metrics();
        assertTrue(metrics.calls() >= 2, "hybrid retriever served the /ask questions: " + metrics);
        assertEquals(0, metrics.denseFailures(), "no dense failure (no silent fallback to sparse): " + metrics);
        String m1Item = knowledge.stream().filter(i -> i.asserting().get(0).messageId().equals(mid(1))).findFirst().orElseThrow().id();
        VespaProjection vespa = c.getBean(VespaProjection.class);
        // TASK-052 control, corrected. retrieveKnowledge sends type=any (OR over all query words), so the full question
        // matches m1's item through function words ("is", "for") alone; "BM25 returns nothing" was never a valid
        // premise. The genuine claim is: no CONTENT term is shared. Checked on the text Vespa actually indexed, with
        // Dita's own tokenizer, and confirmed by BM25 on the content terms only. BM25 itself is not changed.
        String indexed = vespa.knowledgeDocument(scope.groupId(), m1Item).orElseThrow().path("text").asText();
        Set<String> shared = new java.util.LinkedHashSet<>(app.dita.core.text.Tokenizer.normalizedTokens(q));
        shared.retainAll(new java.util.HashSet<>(app.dita.core.text.Tokenizer.normalizedTokens(indexed)));
        assertTrue(shared.isEmpty(), "low-lexical-overlap premise: m7 shares no content term with m1's indexed text: " + shared);
        String contentQuery = String.join(" ", new java.util.LinkedHashSet<>(app.dita.core.text.Tokenizer.tokens(q)));
        var bm25Content = vespa.retrieveKnowledge(scope, contentQuery, 10, true);
        var bm25Full = vespa.retrieveKnowledge(scope, q, 10, true);
        assertFalse(bm25Content.stream().anyMatch(h -> h.knowledgeId().equals(m1Item)),
                "BM25 on the content terms [" + contentQuery + "] does not find m1's item: " + bm25Content);
        var denseHits = vespa.retrieveDenseKnowledge(scope, q, 10, Double.parseDouble(DENSE), app.dita.projections.KnowledgeTruth.of(c.getBean(MessageStore.class)));
        // Evidence for the report (synthetic ids and scores only): which channel finds m1, and how strongly.
        System.out.println("TASK-052 EVIDENCE m1Item=" + m1Item + " contentQuery=[" + contentQuery + "] bm25Content=" + bm25Content
                + " bm25FullQuestion(type=any)=" + bm25Full + " dense(minCosine=" + DENSE + ")=" + denseHits);
        assertTrue(denseHits.stream().anyMatch(h -> h.knowledgeId().equals(m1Item) && h.semanticAdmission()), "dense admits m1's item: " + denseHits);
        assertTrue(c.getBean(MessageStore.class).ditaRepliesCiting(scope, mid(1)).contains(replyId), "the m7 reply cites m1: " + text);
        assertFalse(c.getBean(MessageStore.class).ditaRepliesCiting(scope, mid(3)).contains(replyId), "the m7 reply does not cite m3");
    }

    @Override void assertIndexed(ConfigurableApplicationContext c, List<KnowledgeItem> knowledge) {
        Set<String> ids = knowledge.stream().map(KnowledgeItem::id).collect(Collectors.toSet());
        VespaProjection vespa = c.getBean(VespaProjection.class);
        for (String id : ids) assertTrue(vespa.knowledgeDocument(scope.groupId(), id).isPresent(), "Vespa indexed knowledge " + id);
        Set<String> hits = vespa.retrieveKnowledge(scope, "Dr. Levi babies teenagers", 10, true).stream()
                .map(VespaProjection.KnowledgeHit::knowledgeId).collect(Collectors.toSet());
        assertEquals(ids, hits, "Vespa's knowledge index retrieves exactly this group's active items");
        Neo4jProjection neo4j = c.getBean(Neo4jProjection.class);
        var rows = neo4j.query("MATCH (k:Knowledge) WHERE k.groupId = $g RETURN count(k)", Map.of("g", scope.groupId()));
        assertEquals(ids.size(), rows.get(0).get(0).asInt(), "Neo4j holds this group's knowledge nodes");
    }

    @Test void syntheticTelegramReplayBecomesAGroundedAnswerOnTheRealStack() throws Exception {
        for (String v : KnowledgeSlicePostgresTest.REQUIRED)
            assumeTrue(System.getenv(v) != null && !System.getenv(v).isBlank(), v + " not set: real-stack acceptance NOT EXECUTED");
        assumeTrue(KnowledgeSlicePostgresTest.v5Applied(), "V5 not applied: real-stack acceptance NOT EXECUTED");
        runAcceptance();
    }
}
