package app.dita.projections;

import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.store.MessageStore;

import java.util.List;
import java.util.Optional;

/**
 * Knowledge read-back for projections (RFC-002 §4: "ProjectionSource.knowledge(groupId) list API").
 * Defined here because {@link ProjectionSource} is a dita-core contract (not owned by this module). Projections use it
 * when the supplied truth implements it, and fall back to {@link ProjectionSource} otherwise (no relation edges, and
 * reconcile cannot add items the projection never saw). Proposed to Fury: move these two methods into ProjectionSource.
 */
public interface KnowledgeTruth extends ProjectionSource {
    /** Ids of every knowledge item of the group, in store order. */
    List<String> knowledgeIds(String groupId);
    /** Every relation of the group. */
    List<KnowledgeRelation> relations(String groupId);

    static KnowledgeTruth of(MessageStore store) {
        ProjectionSource base = ProjectionSource.of(store);
        return new KnowledgeTruth() {
            public Optional<Message> message(String g, String id) { return base.message(g, id); }
            public Optional<KnowledgeItem> knowledge(String g, String id) {
                var edited = store.editedMessageIds(new GroupScope(g));
                return base.knowledge(g, id).filter(k -> k.evidence().stream().noneMatch(e -> edited.contains(e.messageId())));
            }
            public Optional<MessageLink> link(String g, String key) { return base.link(g, key); }
            public List<String> knowledgeIds(String g) { return store.knowledge(new GroupScope(g)).stream().map(KnowledgeItem::id).toList(); }
            public List<KnowledgeRelation> relations(String g) { return store.relations(new GroupScope(g)); }
        };
    }
}
