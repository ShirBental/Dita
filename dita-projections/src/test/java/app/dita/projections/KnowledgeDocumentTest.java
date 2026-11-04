package app.dita.projections;

import app.dita.core.deletion.DeletionMode;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.MessageLink;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline: the knowledge template renders only live verbatim spans and Dita fields; graph params carry no text. */
class KnowledgeDocumentTest {
    final KnowledgeFixture f = new KnowledgeFixture("g-render").withKnowledge();

    Optional<KnowledgeDocument> render(KnowledgeItem k) { return KnowledgeDocument.render(k, f.truth()::message); }

    @Test void templateUsesSpeakerPredicateAndLiveSpansOnly() {
        KnowledgeDocument d = render(f.c04()).orElseThrow();
        assertEquals("ron advises against Dr. Levi — for teenagers — \"avoid him for teenagers\"", d.text());
        assertEquals(List.of("c04"), d.sourceIds());
        assertEquals("ATTRIBUTED_STATEMENT", d.status());
        assertEquals("ACTIVE", d.validity());
        assertEquals("ent-levi", d.subjectId());
        assertEquals("yossi recommends Dr. Levi — \"Dr. Levi on Herzl St. is excellent\"", render(f.c02()).orElseThrow().text());
        assertEquals("eve states Dr. Katz (relayed from my neighbour) — for cleaning — \"Dr. Katz is the best dentist\"",
                render(f.c17()).orElseThrow().text());
        for (KnowledgeItem k : List.of(f.c02(), f.c04(), f.c17())) {
            String t = render(k).orElseThrow().text();
            assertFalse(t.contains(KnowledgeFixture.GLOSS), "model wording indexed");
            assertFalse(t.contains(k.subject()), "KnowledgeItem.subject is not a template input");
        }
    }

    @Test void surfacesNotVerbatimInTheLiveMessageAreDropped() {
        KnowledgeItem k = f.c17();
        Assertion a = k.assertion();
        Assertion invented = new Assertion(a.status(), a.certainty(), a.speakerId(), "the mayor", a.subjectId(), "Dr. Kats (sic)",
                a.predicate(), a.qualifiers(), a.entities(), a.statedAt(), a.validity(), a.proposalVersion(), a.batchId());
        String t = render(k.withAssertion(invented)).orElseThrow().text();
        assertEquals("eve states (relayed) — for cleaning — \"Dr. Katz is the best dentist\"", t);
    }

    @Test void deletedRedactedLegacyAndOutOfBoundsAreNotIndexed() {
        // Late deletion of the only source: preserved as audit provenance (D-1 (a)) but never indexed.
        f.store.tombstone(f.g, "c04", KnowledgeFixture.T0.plusSeconds(3600), DeletionMode.LATE_TEXT_ONLY, DeletionSource.FORGET_COMMAND);
        f.store.replaceKnowledge(f.g, f.c04().withSourceDeleted("c04"));
        assertEquals(Optional.empty(), render(f.store.knowledgeById(f.g, "k-c04").orElseThrow()));
        // Even if the flag were missing, a deleted message yields no span.
        assertEquals(Optional.empty(), render(f.c04()));
        assertEquals(Optional.empty(), render(f.c02().redacted(true)));
        assertEquals(Optional.empty(), render(KnowledgeItem.of("k-legacy", f.g.groupId(), KnowledgeItem.Kind.CLAIM, null, "rule text", 0,
                MessageLink.Method.RULE, List.of("c02"))));
        KnowledgeItem oob = f.c02().withEvidence(List.of(KnowledgeItem.Evidence.span("c02", KnowledgeItem.Role.ASSERTS, 50, 500)));
        assertEquals(Optional.empty(), render(oob));
    }

    @Test void qualifiesAndCorrectionTargetRoles() {
        KnowledgeItem k = f.c02().withEvidence(List.of(
                KnowledgeFixture.quote("c02", KnowledgeFixture.C02, "Dr. Levi on Herzl St. is excellent", KnowledgeItem.Role.ASSERTS),
                KnowledgeFixture.quote("c04", KnowledgeFixture.C04, "fine for babies", KnowledgeItem.Role.QUALIFIES),
                KnowledgeFixture.quote("c17", KnowledgeFixture.C17, "best dentist", KnowledgeItem.Role.CORRECTION_TARGET)));
        String t = render(k).orElseThrow().text();
        assertTrue(t.contains("\"fine for babies\""));
        assertFalse(t.contains("best dentist"), "a correction target's words are not the speaker's statement");
        KnowledgeItem noLiveAssertsQuote = f.c02().withEvidence(List.of(KnowledgeItem.Evidence.span("c02", KnowledgeItem.Role.ASSERTS, 50, 500),
                KnowledgeFixture.quote("c04", KnowledgeFixture.C04, "fine for babies", KnowledgeItem.Role.QUALIFIES)));
        assertEquals(Optional.empty(), render(noLiveAssertsQuote), "a qualifying quote alone is not indexable");
    }

    @Test void graphParamsCarryStructureOnly() {
        for (KnowledgeItem k : List.of(f.c02(), f.c04(), f.c17())) {
            Map<String, Object> p = Neo4jProjection.knowledgeParams(f.g.groupId(), k);
            String all = p.toString();
            for (String forbidden : List.of(KnowledgeFixture.GLOSS, "Dr. ", "teenagers", "neighbour", "cleaning", "Herzl", k.subject())) {
                assertFalse(all.contains(forbidden), "graph params contain text: " + forbidden);
            }
            assertEquals(k.assertion().status().name(), p.get("status"));
            assertEquals(k.assertion().subjectId(), p.get("subjectId"));
        }
        assertEquals(null, Neo4jProjection.knowledgeParams(f.g.groupId(), KnowledgeItem.of("k-l", f.g.groupId(),
                KnowledgeItem.Kind.CLAIM, null, "s", 0, MessageLink.Method.RULE, List.of("c02"))).get("status"));
    }

    @Test void knowledgeQueriesAreGroupFilteredAndStrict() throws Exception {
        String yql = VespaProjection.knowledgeYql("g-render", true);
        assertTrue(yql.contains("from knowledge where group_id contains \"g-render\""));
        assertTrue(yql.contains("validity contains \"ACTIVE\""));
        assertFalse(VespaProjection.knowledgeYql("g-render", false).contains("validity contains"));
        assertThrows(IllegalArgumentException.class, () -> VespaProjection.knowledgeYql("g\" or true", true));

        GroupScope a = new GroupScope("g-render");
        JsonNode ok = Http.JSON.readTree("{\"root\":{\"children\":[{\"relevance\":1.5,\"fields\":{\"group_id\":\"g-render\",\"knowledge_id\":\"k-c04\",\"validity\":\"ACTIVE\"}}]}}");
        assertEquals(List.of(new VespaProjection.KnowledgeHit("k-c04", 1.5, "ACTIVE")), VespaProjection.parseKnowledgeHits(a, ok));
        JsonNode foreign = Http.JSON.readTree("{\"root\":{\"children\":[{\"relevance\":1,\"fields\":{\"group_id\":\"g-other\",\"knowledge_id\":\"k\"}}]}}");
        assertThrows(IsolationViolation.class, () -> VespaProjection.parseKnowledgeHits(a, foreign));
        JsonNode err = Http.JSON.readTree("{\"root\":{\"errors\":[{\"code\":4,\"summary\":\"bad\"}]}}");
        assertThrows(Http.ProjectionException.class, () -> VespaProjection.parseKnowledgeHits(a, err));
        Map<String, Object> fields = VespaProjection.knowledgeFields(render(f.c04()).orElseThrow());
        assertEquals("g-render", fields.get("group_id"));
        assertFalse(fields.toString().contains(KnowledgeFixture.GLOSS));
    }
}
