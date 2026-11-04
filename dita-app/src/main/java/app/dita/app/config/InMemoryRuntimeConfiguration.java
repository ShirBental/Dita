package app.dita.app.config;

import app.dita.core.events.EventDispatcher;
import app.dita.core.model.GroupRegistry;
import app.dita.core.projection.InMemoryGraphProjection;
import app.dita.core.projection.InMemorySearchIndex;
import app.dita.core.projection.ProjectionListener;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.Retriever;
import app.dita.core.retrieval.Retrievers;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.core.store.MessageStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * dita.storage.backend=IN_MEMORY (default): in-process store, in-memory outbox dispatcher (it is also the
 * EventPublisher) and the reference projections. Not durable: for offline development and unit-level startup tests.
 */
@Configuration
@ConditionalOnProperty(prefix = "dita.storage", name = "backend", havingValue = "IN_MEMORY", matchIfMissing = true)
public class InMemoryRuntimeConfiguration {

    @Bean public MessageStore messageStore() { return new InMemoryMessageStore(); }

    @Bean(destroyMethod = "close")
    public EventDispatcher eventDispatcher(DitaSettings s, Clock clock, MessageStore store) {
        EventDispatcher d = new EventDispatcher(s.dispatch(), clock);
        ProjectionSource truth = ProjectionSource.of(store);
        d.register(ProjectionListener.consumer("graph", new InMemoryGraphProjection(), truth));
        d.register(ProjectionListener.consumer("index", new InMemorySearchIndex(), truth));
        return d;
    }

    @Bean public DispatchWorker dispatchWorker(EventDispatcher d, DitaSettings s) { return new DispatchWorker(d, s); }

    @Bean public Retriever retriever(MessageStore store, DitaSettings s) { return Retrievers.build(store, s.retrieval()); }

    @Bean public app.dita.core.knowledge.KnowledgeRetriever knowledgeRetriever(MessageStore store) {
        return new app.dita.core.knowledge.LexicalKnowledgeRetriever(store, id -> id);
    }

    @Bean public GroupRegistry groupRegistry() { return GroupRegistry.NONE; }

    @Bean public app.dita.core.knowledge.ExtractionQueue extractionQueue() { return new app.dita.core.knowledge.InMemoryExtractionQueue(); }
}
