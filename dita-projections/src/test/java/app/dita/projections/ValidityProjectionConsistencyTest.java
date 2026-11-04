package app.dita.projections;

import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.knowledge.InMemoryExtractionQueue;
import app.dita.core.knowledge.Proposal;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC-038 (TASK-047): after a correction is withdrawn, the search (Vespa document) and graph (Neo4j params) projections
 * hold the same validity as truth, using only the events core publishes (no reconcile pass). Offline: the projections'
 * own renderers are applied to a mirror exactly as {@link VespaProjection#onKnowledge}/{@link VespaProjection#onMessageDeleted}
 * select affected ids; the live Vespa/Neo4j round-trips stay in the env-gated tests. Synthetic text only.
 */
class ValidityProjectionConsistencyTest {
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    final GroupScope g = new GroupScope("g-047p");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final ProjectionSource truth = ProjectionSource.of(store);
    /** Mirror of the Vespa knowledge index (id → rendered document) and of the Neo4j node params. */
    final Map<String, KnowledgeDocument> search = new HashMap<>();
    final Map<String, Map<String, Object>> graph = new HashMap<>();

    void project(String id) {
        Optional<KnowledgeItem> k = store.knowledgeById(g, id);
        Optional<KnowledgeDocument> doc = k.flatMap(x -> KnowledgeDocument.render(x, truth::message));
        if (doc.isPresent()) search.put(id, doc.get()); else search.remove(id);
        if (k.isPresent()) graph.put(id, Neo4jProjection.knowledgeParams(g.groupId(), k.get())); else graph.remove(id);
    }

    final EventPublisher pub = (grp, p) -> {
        switch (p) {
            case Refs.KnowledgeRef k -> project(k.knowledgeId());
            case DeletionEvent e -> {                                   // same affected-id selection as VespaProjection
                Set<String> affected = new LinkedHashSet<>();
                affected.addAll(e.removedKnowledgeIds()); affected.addAll(e.updatedKnowledgeIds());
                affected.addAll(e.redactedKnowledgeIds()); affected.addAll(e.preservedWithDeletedSourceIds());
                search.forEach((id, d) -> { if (d.sourceIds().contains(e.messageId())) affected.add(id); });
                affected.forEach(this::project);
            }
            default -> { }
        }
        return 1;
    };

    void msg(String id, int min, String text) {
        store.append(g, new Message(id, g.groupId(), "u-ana", T0.plusSeconds(60L * min), null, text, SourceKind.SYNTHETIC, false));
    }

    void dinner() {
        msg("d1", 0, "Dinner is at 7 PM at the community hall.");
        msg("d2", 2, "Sorry, I got that wrong earlier. Dinner is at 8 PM at the community hall.");
        var items = List.of(
                new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", "dinner", "Dinner", "states", null, null,
                        List.of(new Proposal.Quote("d1", "Dinner is at 7 PM at the community hall.")), List.of(), List.of()),
                new Proposal.Item("ATTRIBUTED_STATEMENT", "ASSERTED", "dinner", "Dinner", "states", null, null,
                        List.of(new Proposal.Quote("d2", "Dinner is at 8 PM at the community hall.")), List.of(), List.of()));
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, c) -> new Proposal(items, "t047p"),
                pub, Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC), 50, Duration.ofMinutes(30));
        x.enqueue(g, store.find(g, "d1").orElseThrow());
        x.enqueue(g, store.find(g, "d2").orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        x.process(g, b.id()).orElseThrow();
        assertEquals("SUPERSEDED", search.get(d1()).validity(), "fixture: projections saw the supersession");
    }

    String d1() { return store.knowledge(g).stream().filter(k -> k.sourceMessageIds().contains("d1")).findFirst().orElseThrow().id(); }

    /** Every item in truth has the same validity in the graph mirror and (when indexed) in the search mirror. */
    void assertConsistent() {
        for (KnowledgeItem k : store.knowledge(g)) {
            String v = k.assertion().validity().name();
            assertEquals(v, graph.get(k.id()).get("validity"), "graph validity of " + k.id());
            Optional<KnowledgeDocument> expected = KnowledgeDocument.render(k, truth::message);
            assertEquals(expected, Optional.ofNullable(search.get(k.id())), "search document of " + k.id());
        }
        for (String id : search.keySet()) assertTrue(store.knowledgeById(g, id).isPresent(), "stale search document " + id);
        for (String id : graph.keySet()) assertTrue(store.knowledgeById(g, id).isPresent(), "stale graph node " + id);
    }

    /** What the production Vespa query (activeOnly=true: validity contains "ACTIVE") would return. */
    List<String> activeSearchIds() {
        return search.values().stream().filter(d -> d.validity().equals("ACTIVE")).map(KnowledgeDocument::knowledgeId).toList();
    }

    DeletionService deletions() { return new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, pub, 50); }

    @Test void earlyDeletionOfTheCorrection() {
        dinner();
        deletions().delete(g, "d2", T0.plusSeconds(120 + 60), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertEquals("UNRESOLVED", search.get(d1()).validity());
        assertEquals("UNRESOLVED", graph.get(d1()).get("validity"));
        assertTrue(activeSearchIds().isEmpty(), "nothing is retrievable as current: " + activeSearchIds());
        assertConsistent();
        search.values().forEach(d -> assertFalse(d.text().contains("8 PM"), d.text()));
    }

    @Test void lateDeletionOfTheCorrection() {
        dinner();
        deletions().delete(g, "d2", T0.plusSeconds(120 + 3 * 3600), DeletionSource.PLATFORM_EVENT).orElseThrow();
        assertEquals("UNRESOLVED", search.get(d1()).validity());
        assertTrue(activeSearchIds().isEmpty(), activeSearchIds().toString());
        assertConsistent();
        search.values().forEach(d -> assertFalse(d.text().contains("8 PM"), d.text()));
    }

    @Test void editOfTheCorrection() {
        dinner();
        deletions().edit(g, "d2", "Dinner plans are still open, I will confirm tomorrow.", T0.plusSeconds(600));
        assertEquals(Assertion.Validity.UNRESOLVED.name(), search.get(d1()).validity());
        assertTrue(activeSearchIds().isEmpty());
        assertConsistent();
    }
}
