package app.dita.core.knowledge;

import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.projection.InMemorySearchIndex;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-046 non-linguistic correctness (provenance, edits, deletions, projections), written after the user paused
 * semantic-rule work. No rule about wording is tested here: only that derived data follows its sources.
 * <ul>
 *   <li>edited messages are never sent to the proposer, as targets, context or reply parents (E-2, B46R3-12);</li>
 *   <li>edit and deletion events reach the projection and leave no old wording in any projected document;</li>
 *   <li>deleting a reply PARENT removes (early purge) a reply item whose subject came only from it (N-104).</li>
 * </ul>
 */
class Task046ProvenanceTest {
    static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");
    final GroupScope g = new GroupScope("g-046p");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemorySearchIndex index = new InMemorySearchIndex();
    final ProjectionSource truth = ProjectionSource.of(store);
    /** Applies every outbox event to the projection synchronously (the dispatcher does this asynchronously). */
    final EventPublisher pub = (grp, p) -> {
        switch (p) {
            case Refs.MessageRef m -> index.onMessage(m.groupId(), m.messageId(), truth);
            case Refs.KnowledgeRef k -> index.onKnowledge(k.groupId(), k.knowledgeId(), truth);
            case DeletionEvent d -> index.onMessageDeleted(d, truth);
            default -> { }
        }
        return 1;
    };
    final Clock now = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    final List<List<Message>> sentContext = new ArrayList<>(), sentTargets = new ArrayList<>();

    void msg(String id, String author, int min, String reply, String text) {
        Message m = new Message(id, g.groupId(), author, T0.plusSeconds(60L * min), reply, text, SourceKind.SYNTHETIC, false);
        store.append(g, m);
        index.onMessage(g.groupId(), id, truth);
    }

    static Proposal.Item it(String status, String subjectId, String subject, String predicate, String mid, String quote) {
        return new Proposal.Item(status, "ASSERTED", subjectId, subject, predicate, null, null, List.of(new Proposal.Quote(mid, quote)), List.of(), List.of());
    }

    void run(List<Proposal.Item> items, String... ids) {
        ExtractionService x = new ExtractionService(store, new InMemoryExtractionQueue(), (s, b, t, c) -> {
            sentTargets.add(List.copyOf(t)); sentContext.add(List.copyOf(c));
            return new Proposal(items, "t046p");
        }, pub, now, 50, Duration.ofMinutes(30));
        for (String id : ids) x.enqueue(g, store.find(g, id).orElseThrow());
        var b = x.collect(g).get(0);
        x.approve(g, b.id(), "op-test");
        assertEquals(ExtractionBatch.Status.DONE, x.process(g, b.id()).orElseThrow().status());
    }

    DeletionService deletions() { return new DeletionService(store, new RuleLinkInferencer(), DeletionSettings.DEFAULTS, pub, 50); }

    Optional<KnowledgeItem> item(String mid) {
        return store.knowledge(g).stream().filter(k -> k.asserting().get(0).messageId().equals(mid)).findFirst();
    }

    boolean anyDocMentions(String wording) {
        return index.messageDocs.values().stream().anyMatch(d -> d.text() != null && d.text().contains(wording))
                || index.knowledgeDocs.values().stream().anyMatch(d -> (d.statement() != null && d.statement().contains(wording))
                        || (d.subject() != null && d.subject().contains(wording)));
    }

    @Test void editedMessagesAreNeverSentToTheProposer() {
        msg("c1", "u-ana", 0, null, "The bus leaves from the school gate at 8 AM.");
        msg("c2", "u-ben", 1, null, "Thanks, I will be there with the kids.");
        msg("c3", "u-ana", 2, "c1", "Snacks are on me for the bus ride.");
        store.edit(g, "c1", "The bus leaves from the library at 9 AM.", T0.plusSeconds(200));
        run(List.of(), "c2", "c3");
        assertTrue(sentContext.get(0).stream().noneMatch(m -> m.id().equals("c1")), "an edited message is not context");
        assertTrue(sentTargets.get(0).stream().noneMatch(m -> m.id().equals("c1")));
    }

    @Test void anEditLeavesNoOldWordingInAnyProjectedDocument() {
        msg("e1", "u-ana", 0, null, "Dinner is at 7 PM at the community hall.");
        run(List.of(it("ATTRIBUTED_STATEMENT", "dinner", "Dinner", "states", "e1", "Dinner is at 7 PM at the community hall.")), "e1");
        assertTrue(anyDocMentions("7 PM"), "fixture: projected before the edit");
        deletions().edit(g, "e1", "Dinner plans are still open.", T0.plusSeconds(600));
        assertFalse(anyDocMentions("7 PM"), "no message or knowledge document keeps the old revision");
        assertEquals("Dinner plans are still open.", index.messageDocs.get("e1").text());
        assertTrue(index.knowledgeDocs.isEmpty());
    }

    @Test void deletingAReplyParentRemovesTheReplyItemWhoseSubjectCameFromIt() {
        msg("p1", "u-ana", 0, null, "Who is bringing the folding chairs for Saturday?");
        msg("p2", "u-ben", 1, "p1", "I'll bring them, confirmed.");
        run(List.of(it("CONFIRMED_COMMITMENT", "chairs", "the folding chairs", "commits", "p2", "I'll bring them, confirmed.")), "p2");
        assertTrue(item("p2").isPresent() && anyDocMentions("folding chairs"), "fixture: subject verified via the parent");
        deletions().delete(g, "p1", T0.plusSeconds(60 + 120), DeletionSource.FORGET_COMMAND).orElseThrow();   // early purge
        assertTrue(item("p2").isEmpty(), "the parent's contribution (the subject) is purged with it");
        assertFalse(anyDocMentions("folding chairs"), "no projected document keeps the deleted parent's wording");
    }

    /** Late (preserve) policy, same as for direct dependents (DEC-012 / N-24): the item is kept; a subject is redacted only
     *  if it repeats a six-word run of the deleted text (short entity names are not wording). */
    @Test void lateDeletionOfAReplyParentFollowsThePreservePolicy() {
        msg("q1", "u-ana", 0, null, "Who is bringing the folding chairs for Saturday?");
        msg("q2", "u-ben", 1, "q1", "I'll bring them, confirmed.");
        run(List.of(it("CONFIRMED_COMMITMENT", "chairs", "the folding chairs", "commits", "q2", "I'll bring them, confirmed.")), "q2");
        deletions().delete(g, "q1", T0.plusSeconds(3 * 3600), DeletionSource.FORGET_COMMAND).orElseThrow();   // late, preserve mode
        KnowledgeItem k = item("q2").orElseThrow();
        assertFalse(k.statementRedacted(), "kept: a three-word subject is not deleted wording under N-24");
        assertTrue(index.messageDocs.get("q1") == null, "the deleted parent message itself is gone from the index");
    }

    @Test void deletingAnUnrelatedParentKeepsAReplyWhoseSubjectIsItsOwn() {
        msg("r1", "u-ana", 0, null, "Can someone help on Saturday?");
        msg("r2", "u-ben", 1, "r1", "I'll bring the folding chairs, confirmed.");
        run(List.of(it("CONFIRMED_COMMITMENT", "chairs", "the folding chairs", "commits", "r2", "I'll bring the folding chairs, confirmed.")), "r2");
        deletions().delete(g, "r1", T0.plusSeconds(120), DeletionSource.FORGET_COMMAND).orElseThrow();
        assertTrue(item("r2").isPresent(), "its subject is in its own words: unaffected");
    }
}
