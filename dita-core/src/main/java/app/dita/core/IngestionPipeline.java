package app.dita.core;

import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.link.LinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.store.MessageStore;

import java.util.List;

/**
 * Persist the original, derive links, publish to projections, all in one atomic unit (truth first). Projections
 * consume asynchronously from the outbox. Redelivery of an already stored message is a no-op.
 */
public final class IngestionPipeline {
    private final MessageStore store;
    private final LinkInferencer inferencer;
    private final int windowMessages;
    private final EventPublisher publisher;

    public IngestionPipeline(MessageStore store, LinkInferencer inferencer) { this(store, inferencer, 50, EventPublisher.NONE); }

    public IngestionPipeline(MessageStore store, LinkInferencer inferencer, int windowMessages, EventPublisher publisher) {
        this.store = store; this.inferencer = inferencer; this.windowMessages = windowMessages; this.publisher = publisher;
    }

    public List<MessageLink> ingest(GroupScope scope, Message m) {
        return store.atomically(scope, () -> {
            if (store.find(scope, m.id()).isPresent()) { store.append(scope, m); return List.<MessageLink>of(); }
            store.append(scope, m);
            List<Message> window = store.windowBefore(scope, m.id(), windowMessages);
            List<MessageLink> links = inferencer.infer(scope, m, window, store.links(scope));
            links.forEach(l -> store.addLink(scope, l));
            publisher.publish(scope.groupId(), new Refs.MessageRef(scope.groupId(), m.id()));
            links.forEach(l -> publisher.publish(scope.groupId(), new Refs.LinkRef(scope.groupId(), l.key())));
            return links;
        });
    }
}
