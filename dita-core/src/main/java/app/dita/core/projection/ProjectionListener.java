package app.dita.core.projection;

import app.dita.core.deletion.DeletionEvent;
import app.dita.core.events.EventDispatcher;
import app.dita.core.events.Refs;
import app.dita.core.model.MessageLink;

/**
 * Downstream projection contract (Neo4j graph, Vespa index). Rebuildable from Postgres, idempotent, group-scoped,
 * and must never restore deleted text: message payloads are re-checked against current truth before indexing.
 */
public interface ProjectionListener {
    /** Apply the CURRENT truth for this message; if truth has no live row, remove it (never index from a payload). */
    void onMessage(String groupId, String messageId, ProjectionSource truth);
    void onLink(String groupId, String linkKey, ProjectionSource truth);
    void onKnowledge(String groupId, String knowledgeId, ProjectionSource truth);
    void onMessageDeleted(DeletionEvent e, ProjectionSource truth);
    /** Reconcile sweep (N-20): remove anything the truth no longer has live, e.g. after a lost event. */
    void reconcile(String groupId, ProjectionSource truth);

    static String linkKey(MessageLink l) { return l.key(); }

    /** Adapts a listener to the dispatcher. */
    static EventDispatcher.Consumer consumer(String name, ProjectionListener p, ProjectionSource truth) {
        return new EventDispatcher.Consumer() {
            public String name() { return name; }
            public void accept(EventDispatcher.Envelope e) {
                switch (e.payload()) {
                    case Refs.MessageRef m -> p.onMessage(m.groupId(), m.messageId(), truth);
                    case Refs.LinkRef l -> p.onLink(l.groupId(), l.linkKey(), truth);
                    case Refs.KnowledgeRef k -> p.onKnowledge(k.groupId(), k.knowledgeId(), truth);
                    case DeletionEvent d -> p.onMessageDeleted(d, truth);
                    default -> { }   // unknown topics are ignored by projections
                }
            }
        };
    }
}
