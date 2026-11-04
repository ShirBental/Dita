package app.dita.core.projection;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;

import java.util.HashMap;
import java.util.Map;

/**
 * Executable reference for the Vespa adapter. Documents are built only from current truth; a missing or deleted
 * truth row removes the document (N-19). Deletion removes the message document in both modes.
 */
public final class InMemorySearchIndex implements ProjectionListener {
    public record MessageDoc(String id, String groupId, String text) {}
    public record KnowledgeDoc(String id, String groupId, String subject, String statement, boolean redacted, boolean sourceDeleted) {}

    public final Map<String, MessageDoc> messageDocs = new HashMap<>();
    public final Map<String, KnowledgeDoc> knowledgeDocs = new HashMap<>();

    @Override public void onMessage(String groupId, String id, ProjectionSource truth) {
        MessageDoc prev = messageDocs.get(id);
        if (prev != null && !prev.groupId().equals(groupId)) return;
        Message current = truth.message(groupId, id).filter(m -> !m.deleted()).orElse(null);
        if (current == null) { messageDocs.remove(id); return; }
        messageDocs.put(id, new MessageDoc(id, groupId, current.text()));
    }

    @Override public void onLink(String groupId, String key, ProjectionSource truth) { }

    @Override public void onKnowledge(String groupId, String id, ProjectionSource truth) {
        KnowledgeItem k = truth.knowledge(groupId, id).orElse(null);
        if (k == null) { knowledgeDocs.remove(id); return; }
        knowledgeDocs.put(k.id(), new KnowledgeDoc(k.id(), k.groupId(), k.subject(), k.statement(), k.statementRedacted(), k.hasDeletedSource()));
    }

    @Override public void onMessageDeleted(DeletionEvent e, ProjectionSource truth) {
        MessageDoc d = messageDocs.get(e.messageId());
        if (d != null && !d.groupId().equals(e.groupId())) return;               // N-16
        messageDocs.remove(e.messageId());
        e.removedKnowledgeIds().forEach(id -> knowledgeDocs.computeIfPresent(id, (k, v) -> v.groupId().equals(e.groupId()) ? null : v));
        e.updatedKnowledgeIds().forEach(id -> onKnowledge(e.groupId(), id, truth));
    }

    @Override public void reconcile(String groupId, ProjectionSource truth) {
        messageDocs.entrySet().removeIf(en -> en.getValue().groupId().equals(groupId)
                && truth.message(groupId, en.getKey()).filter(m -> !m.deleted()).isEmpty());
        knowledgeDocs.keySet().stream().filter(id -> knowledgeDocs.get(id).groupId().equals(groupId)).toList()
                .forEach(id -> onKnowledge(groupId, id, truth));
    }
}
