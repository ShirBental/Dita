package app.dita.app.config;

import app.dita.core.config.RetrievalSettings;
import app.dita.core.events.EventPublisher;
import app.dita.core.model.GroupRegistry;
import app.dita.core.projection.ProjectionListener;
import app.dita.core.projection.ProjectionSource;
import app.dita.core.retrieval.HybridRetriever;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.MessageStore;
import app.dita.projections.Neo4jProjection;
import app.dita.projections.VespaProjection;
import app.dita.store.postgres.PostgresEventPublisher;
import app.dita.store.postgres.PostgresGroupRegistry;
import app.dita.store.postgres.PostgresMessageStore;
import app.dita.store.postgres.PostgresOutboxRelay;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * dita.storage.backend=POSTGRES (ADR-001 / DEC-020): PostgreSQL is the source of truth and the transactional outbox;
 * a background {@link RelayWorker} drives the durable {@link PostgresOutboxRelay} (separate dita_relay role, N-27)
 * into the real Neo4j and Vespa projections; retrieval runs on Vespa + Neo4j graph expansion.
 * The application never runs migrations. Credentials come from the environment variables NAMED in configuration and
 * are never logged.
 */
@Configuration
@ConditionalOnProperty(prefix = "dita.storage", name = "backend", havingValue = "POSTGRES")
public class PostgresRuntimeConfiguration {
    /** Durable relay consumer names (cursor rows in outbox_consumer): {@code <dita.storage.relay-consumer-prefix>-neo4j|-vespa}. */
    public static String neo4jConsumer(DitaProperties p) { return p.storage().relayConsumerPrefix() + "-neo4j"; }
    public static String vespaConsumer(DitaProperties p) { return p.storage().relayConsumerPrefix() + "-vespa"; }

    static String secret(Function<String, String> env, String name) {
        String v = env.apply(name);
        if (v == null || v.isBlank()) {
            throw new DitaConfigurationException("environment variable " + name + " must be set for dita.storage.backend=POSTGRES (value not shown)");
        }
        return v;
    }

    private static HikariDataSource pool(String name, String url, String user, String password, int max) {
        HikariConfig c = new HikariConfig();
        c.setPoolName(name);
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(max);
        c.setMinimumIdle(1);
        c.setAutoCommit(true);
        c.setInitializationFailTimeout(10_000);    // fail startup if the database is unreachable or the role is wrong
        return new HikariDataSource(c);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource ditaAppDataSource(DitaProperties p, Function<String, String> ditaEnv) {
        var pg = p.storage().postgres();
        return pool("dita-app", pg.url(), pg.appUser(), secret(ditaEnv, pg.appPasswordEnvVar()), pg.maxPoolSize());
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource ditaRelayDataSource(DitaProperties p, Function<String, String> ditaEnv) {
        var pg = p.storage().postgres();
        return pool("dita-relay", pg.url(), pg.relayUser(), secret(ditaEnv, pg.relayPasswordEnvVar()), 2);
    }

    @Bean public MessageStore messageStore(@Qualifier("ditaAppDataSource") HikariDataSource ds) {
        return new PostgresMessageStore(ds, new DataSourceTransactionManager(ds));
    }

    @Bean public EventPublisher eventPublisher(@Qualifier("ditaAppDataSource") HikariDataSource ds) { return new PostgresEventPublisher(ds); }

    @Bean public GroupRegistry groupRegistry(@Qualifier("ditaAppDataSource") HikariDataSource ds) { return new PostgresGroupRegistry(ds); }

    /** Durable extraction state (V5 tables); shares the app pool and the store's group-scoped transactions. */
    @Bean public app.dita.core.knowledge.ExtractionQueue extractionQueue(@Qualifier("ditaAppDataSource") HikariDataSource ds, MessageStore store) {
        return new app.dita.store.postgres.PostgresExtractionQueue(ds, (PostgresMessageStore) store);
    }

    @Bean public Neo4jProjection neo4jProjection(DitaProperties p, Function<String, String> ditaEnv) {
        var n = p.storage().neo4j();
        Neo4jProjection neo4j = new Neo4jProjection(n.url(), n.user(), secret(ditaEnv, n.passwordEnvVar()));
        neo4j.ensureSchema();                          // idempotent constraints; fails startup if Neo4j is unreachable
        return neo4j;
    }

    @Bean public VespaProjection vespaProjection(DitaProperties p) { return new VespaProjection(p.storage().vespa().url()); }

    /** DEC-031: knowledge questions rank current items with Vespa's knowledge index (BM25, active only, group-filtered). */
    @Bean public app.dita.core.knowledge.KnowledgeRetriever knowledgeRetriever(VespaProjection vespa, MessageStore store, DitaProperties p) {
        var fallback = new app.dita.core.knowledge.LexicalKnowledgeRetriever(store, id -> id);
        if (p.retrieval().dense().enabled()) {
            app.dita.core.knowledge.KnowledgeRetriever sparse = new app.dita.core.knowledge.KnowledgeRetriever() {
                public List<Hit> retrieve(app.dita.core.model.GroupScope scope, String q, int k) {
                    return vespa.retrieveKnowledge(scope,q,k,true).stream().map(h->new Hit(h.knowledgeId(),h.relevance())).toList();
                }
                public String name() { return "vespa-knowledge-bm25"; }
            };
            // Enabling dense must prove schema + query embedder availability, never silently boot as lexical.
            // TASK-052: explicit per-attempt timeout and bounded 504 retry for the cold first embed; still fail-closed.
            vespa.awaitDenseReadiness(new app.dita.core.model.GroupScope("task049-readiness"), p.retrieval().dense().minCosine(),
                    app.dita.projections.KnowledgeTruth.of(store), java.time.Duration.ofSeconds(5), 3);
            return new app.dita.projections.HybridKnowledgeRetriever(vespa, app.dita.projections.KnowledgeTruth.of(store),
                    sparse, fallback, p.retrieval().dense().minCosine());
        }
        // DEC-032: Vespa proposes candidates, Dita's tokenizer ranks them (see RerankingKnowledgeRetriever)
        return new app.dita.core.knowledge.RerankingKnowledgeRetriever(new app.dita.core.knowledge.KnowledgeRetriever() {
            @Override public List<Hit> retrieve(app.dita.core.model.GroupScope scope, String question, int k) {
                try {
                    return vespa.retrieveKnowledge(scope, question, k, true).stream().map(h -> new Hit(h.knowledgeId(), h.relevance())).toList();
                } catch (app.dita.core.model.IsolationViolation iso) {
                    throw iso;                                                   // never mask an isolation failure
                } catch (RuntimeException vespaDown) {
                    return fallback.retrieve(scope, question, k);                // N-88: availability fallback (same scope)
                }
            }
            @Override public String name() { return "vespa-knowledge-bm25"; }
        }, fallback);
    }

    @Bean public PostgresOutboxRelay outboxRelay(@Qualifier("ditaRelayDataSource") HikariDataSource relayDs, DitaSettings s, Clock clock,
                                                 MessageStore store, Neo4jProjection neo4j, VespaProjection vespa, DitaProperties p) {
        PostgresOutboxRelay relay = new PostgresOutboxRelay(relayDs, s.dispatch(), clock);
        // KnowledgeTruth (not plain ProjectionSource): lets the projections build RELATES edges (SUPERSEDES/CONTRADICTS)
        // and lets reconcile add knowledge a projection never saw. Plain ProjectionSource silently dropped both.
        ProjectionSource truth = app.dita.projections.KnowledgeTruth.of(store);
        // First start creates the cursor at the current end of the outbox; later starts resume from the durable cursor.
        // Projections of data written before the first start are rebuilt by reconcile, never from old outbox rows.
        relay.registerFromNow(ProjectionListener.consumer(neo4jConsumer(p), neo4j, truth));
        relay.registerFromNow(ProjectionListener.consumer(vespaConsumer(p), vespa, truth));
        return relay;
    }

    @Bean public RelayWorker relayWorker(PostgresOutboxRelay relay, DitaSettings s, DitaProperties p) {
        return new RelayWorker(relay, s.raw().processing().dispatch().pollInterval(), List.of(neo4jConsumer(p), vespaConsumer(p)));
    }

    /**
     * The application's retriever: Vespa + Neo4j expansion. @Primary because VespaProjection is itself a Retriever (it is
     * composed here), so without it Spring finds two Retriever beans and the POSTGRES runtime cannot start.
     */
    @Bean @org.springframework.context.annotation.Primary
    public Retriever retriever(VespaProjection vespa, Neo4jProjection neo4j, DitaSettings s, DitaProperties p) {
        RetrievalSettings r = s.retrieval();
        var d = p.retrieval().dense();
        if (d.messages()) {
            // TASK-054 G1: keyword (BM25) + semantic (bge-small, in Vespa) message channels, fused by RRF; graph expansion
            // starts from the fused seeds, so a reply to a semantically found message is reached too. Every fused hit
            // records ALL channels that found it. Fail-closed: semantic mode never boots as keyword-only.
            int attempt = vespa.awaitDenseMessageReadiness(new app.dita.core.model.GroupScope("task054-readiness"), d.messagesMinCosine(),
                    java.time.Duration.ofSeconds(5), 3);
            System.getLogger(PostgresRuntimeConfiguration.class.getName()).log(System.Logger.Level.INFO,
                    "semantic message retrieval ready (attempt " + attempt + "): channels vespa:bm25 + vespa:dense"
                            + (r.graphEnabled() && r.graphDepth() > 0 ? " + neo4j graph" : ""));
            Retriever dense = vespa.denseMessages(d.messagesMinCosine());
            // TASK-054 (DEC-050): the keyword channel searches the question's CONTENT words; Vespa's "any" BM25 removes no
            // stopwords, so "when/where/what happens/if/it" otherwise rank incidental messages first
            Retriever keyword = app.dita.core.retrieval.QuestionNeeds.contentWordsOnly(vespa);
            Retriever seeds = new HybridRetriever(List.of(keyword, dense), List.of(r.sparseWeight(), d.messagesWeight()), r.rrfK(),
                    r.candidateMultiplier(), true);
            if (!r.graphEnabled() || r.graphDepth() == 0) return seeds;
            return new HybridRetriever(List.of(keyword, dense, neo4j.expanding(seeds, r.graphDepth(), r.explicitEdgeWeight(), r.candidateEdgeWeight())),
                    List.of(r.sparseWeight(), d.messagesWeight(), r.graphWeight()), r.rrfK(), r.candidateMultiplier(), true);
        }
        if (!r.graphEnabled() || r.graphDepth() == 0) return vespa;
        return new HybridRetriever(List.of(vespa, neo4j.expanding(vespa, r.graphDepth(), r.explicitEdgeWeight(), r.candidateEdgeWeight())),
                List.of(r.sparseWeight(), r.graphWeight()), r.rrfK(), r.candidateMultiplier());
    }
}
