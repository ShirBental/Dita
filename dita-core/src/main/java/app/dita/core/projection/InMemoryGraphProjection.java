package app.dita.core.projection;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.deletion.DeletionMode;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executable reference for the Neo4j adapter (the Cypher adapter must pass the same tests):
 * (:Message {id, groupId, text?, deleted, deletionMode}); [:LINK {key}]; (:Knowledge {id, statement, redacted,
 * sourceDeleted}). Every write reads current truth; a missing or deleted truth row removes text (N-19).
 */
public final class InMemoryGraphProjection implements ProjectionListener {
    public record Node(String id, String groupId, String text, boolean deleted, DeletionMode deletionMode) {}
    public record Edge(String key, String groupId, String from, String to) {}
    public record KnowledgeNode(String id, String groupId, String subject, String statement, boolean redacted, boolean sourceDeleted) {}

    public final Map<String, Node> nodes = new HashMap<>();
    public final Map<String, Edge> edges = new HashMap<>();
    public final Map<String, KnowledgeNode> knowledge = new HashMap<>();
    /** Test hook: fail the next N calls (simulates the database being down). */
    public final AtomicInteger failNext = new AtomicInteger();

    private void maybeFail() { if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("graph unavailable"); }

    @Override public void onMessage(String groupId, String id, ProjectionSource truth) {
        maybeFail();
        Message current = truth.message(groupId, id).orElse(null);
        Node prev = nodes.get(id);
        if (prev != null && !prev.groupId().equals(groupId)) return;
        if (current == null) { if (prev != null) nodes.put(id, new Node(id, groupId, null, true, prev.deletionMode())); return; }
        nodes.put(id, new Node(id, groupId, current.deleted() ? null : current.text(), current.deleted(),
                prev == null ? null : prev.deletionMode()));
    }

    @Override public void onLink(String groupId, String key, ProjectionSource truth) {
        maybeFail();
        MessageLink l = truth.link(groupId, key).orElse(null);
        if (l == null) { edges.remove(key); return; }
        edges.put(key, new Edge(key, groupId, l.fromId(), l.toId()));
    }

    @Override public void onKnowledge(String groupId, String id, ProjectionSource truth) {
        maybeFail();
        KnowledgeItem k = truth.knowledge(groupId, id).orElse(null);
        if (k == null) { knowledge.remove(id); return; }
        upsert(k);
    }

    private void upsert(KnowledgeItem k) {
        knowledge.put(k.id(), new KnowledgeNode(k.id(), k.groupId(), k.subject(), k.statement(), k.statementRedacted(), k.hasDeletedSource()));
    }

    @Override public void onMessageDeleted(DeletionEvent e, ProjectionSource truth) {
        maybeFail();
        Node n = nodes.get(e.messageId());
        if (n != null && !n.groupId().equals(e.groupId())) return;               // N-16: never act across groups
        nodes.put(e.messageId(), new Node(e.messageId(), e.groupId(), null, true, e.mode()));
        e.removedLinkKeys().forEach(key -> edges.computeIfPresent(key, (k, v) -> v.groupId().equals(e.groupId()) ? null : v));
        e.addedLinkKeys().forEach(key -> onLink(e.groupId(), key, truth));
        e.removedKnowledgeIds().forEach(id -> knowledge.computeIfPresent(id, (k, v) -> v.groupId().equals(e.groupId()) ? null : v));
        e.updatedKnowledgeIds().forEach(id -> onKnowledge(e.groupId(), id, truth));
    }

    @Override public void reconcile(String groupId, ProjectionSource truth) {
        nodes.replaceAll((id, n) -> !n.groupId().equals(groupId) || n.deleted() ? n
                : truth.message(groupId, id).filter(m -> !m.deleted()).isPresent() ? n : new Node(id, groupId, null, true, n.deletionMode()));
        edges.entrySet().removeIf(en -> en.getValue().groupId().equals(groupId) && truth.link(groupId, en.getKey()).isEmpty());
        knowledge.entrySet().removeIf(en -> en.getValue().groupId().equals(groupId) && truth.knowledge(groupId, en.getKey()).isEmpty());
        knowledge.replaceAll((id, k) -> !k.groupId().equals(groupId) ? k : truth.knowledge(groupId, id)
                .map(x -> new KnowledgeNode(x.id(), x.groupId(), x.subject(), x.statement(), x.statementRedacted(), x.hasDeletedSource())).orElse(k));
    }
}
