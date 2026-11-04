package app.dita.core.projection;

import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.store.MessageStore;

import java.util.Optional;

/** Read-back of current truth for projections (events carry ids; state comes from Postgres). */
public interface ProjectionSource {
    Optional<Message> message(String groupId, String messageId);
    Optional<KnowledgeItem> knowledge(String groupId, String knowledgeId);
    Optional<MessageLink> link(String groupId, String linkKey);

    static ProjectionSource of(MessageStore store) {
        return new ProjectionSource() {
            public Optional<Message> message(String g, String id) { return store.find(new GroupScope(g), id); }
            public Optional<KnowledgeItem> knowledge(String g, String id) { return store.knowledgeById(new GroupScope(g), id); }
            public Optional<MessageLink> link(String g, String key) {
                return store.links(new GroupScope(g)).stream().filter(l -> l.key().equals(key)).findFirst();
            }
        };
    }
}
