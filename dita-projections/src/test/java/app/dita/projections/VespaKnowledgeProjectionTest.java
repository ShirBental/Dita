package app.dita.projections;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.KnowledgeItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Knowledge documents on a REAL Vespa with the knowledge schema deployed (vespa/app). Truth = in-memory store with
 * extracted-style RFC-002 items. SKIPPED unless DITA_TEST_VESPA_URL is set; loopback Dita port only (N-29). Synthetic.
 */
class VespaKnowledgeProjectionTest {
    static final String VESPA = System.getenv("DITA_TEST_VESPA_URL");
    VespaProjection vespa;
    KnowledgeFixture a, b;

    @BeforeEach void setUp() {
        assumeTrue(VESPA != null && !VESPA.isBlank(), "DITA_TEST_VESPA_URL not set: Vespa knowledge projection test skipped");
        assertTrue(VESPA.matches("http://127\\.0\\.0\\.1:5\\d{4}/?"), "refusing non-Dita endpoint");
        vespa = new VespaProjection(VESPA);
        String run = UUID.randomUUID().toString().substring(0, 8);
        a = new KnowledgeFixture("kv-a-" + run).withKnowledge();
        b = new KnowledgeFixture("kv-b-" + run).withKnowledge();
        for (KnowledgeFixture f : List.of(a, b)) for (KnowledgeItem k : f.store.knowledge(f.g)) vespa.onKnowledge(f.g.groupId(), k.id(), f.truth());
    }

    Set<String> ids(KnowledgeFixture f, String q) {
        return vespa.retrieveKnowledge(f.g, q, 10, true).stream().map(VespaProjection.KnowledgeHit::knowledgeId).collect(Collectors.toSet());
    }

    @Test void indexesTemplateTextOnlyAndRetrievesWithinTheGroup() {
        JsonNode doc = vespa.knowledgeDocument(a.g.groupId(), "k-c04").orElseThrow();
        assertEquals("ron advises against Dr. Levi — for teenagers — \"avoid him for teenagers\"", doc.path("text").asText());
        assertFalse(doc.toString().contains(KnowledgeFixture.GLOSS), "model wording indexed");
        assertTrue(ids(a, "teenagers").contains("k-c04"));
        assertTrue(ids(a, "neighbour dentist").contains("k-c17"));
        assertFalse(ids(a, KnowledgeFixture.GLOSS).contains("k-c02"), "gloss is not searchable");
        // Same knowledge ids exist in B; hits are confined to the scope (parseKnowledgeHits throws otherwise).
        Set<String> levi = ids(a, "Levi");
        assertEquals(levi, ids(b, "Levi"));
        assertTrue(levi.containsAll(Set.of("k-c02", "k-c04")), "query 'Levi' in group A returned " + levi);
    }

    @Test void removalAndSourcePurgeDeleteDocuments() {
        a.store.removeKnowledge(a.g, "k-c02");
        vespa.onKnowledge(a.g.groupId(), "k-c02", a.truth());
        assertEquals(404, vespa.knowledgeStatus(a.g.groupId(), "k-c02"));
        assertEquals(200, vespa.knowledgeStatus(b.g.groupId(), "k-c02"), "B's document untouched");

        // Late deletion of c04: k-c04 is preserved in truth (audit only) but must leave the index. The event lists are
        // deliberately EMPTY: the visit-based sweep over source_ids must still find and remove the document.
        a.store.tombstone(a.g, "c04", KnowledgeFixture.T0.plusSeconds(3600), DeletionMode.LATE_TEXT_ONLY, DeletionSource.PLATFORM_EVENT);
        a.store.replaceKnowledge(a.g, a.c04().withSourceDeleted("c04"));
        vespa.onMessageDeleted(new DeletionEvent(a.g.groupId(), "c04", DeletionMode.LATE_TEXT_ONLY, DeletionSource.PLATFORM_EVENT,
                KnowledgeFixture.T0, KnowledgeFixture.T0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), a.truth());
        assertEquals(404, vespa.knowledgeStatus(a.g.groupId(), "k-c04"));
        assertFalse(ids(a, "teenagers").contains("k-c04"));
        assertTrue(ids(b, "teenagers").contains("k-c04"));

        // Reconcile re-adds an item the index never saw.
        a.store.addKnowledge(a.g, a.c02());
        vespa.reconcile(a.g.groupId(), a.truth());
        assertEquals(200, vespa.knowledgeStatus(a.g.groupId(), "k-c02"));
        assertEquals(404, vespa.knowledgeStatus(a.g.groupId(), "k-c04"));
    }
}
