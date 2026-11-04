package app.dita.app.config;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.memory.MembershipPort;
import app.dita.core.model.GroupDirectory;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.MessageStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;

/**
 * Wires the platform-neutral core from validated settings. The storage backend (MessageStore, EventPublisher,
 * Retriever, GroupRegistry and the relay worker) comes from InMemoryRuntimeConfiguration (default) or
 * PostgresRuntimeConfiguration (dita.storage.backend=POSTGRES); exactly one is active.
 */
@Configuration
@EnableConfigurationProperties(DitaProperties.class)
public class DitaConfiguration {

    /** Environment lookup for credential env vars; overridable in tests. Never logs values. */
    @Bean @ConditionalOnMissingBean(name = "ditaEnv")
    public Function<String, String> ditaEnv() { return System::getenv; }

    @Bean
    public DitaSettings ditaSettings(DitaProperties props, Function<String, String> ditaEnv) {
        DitaSettings s = DitaSettings.from(props, ditaEnv);
        ConfigAudit.log(s);
        return s;
    }

    @Bean @ConditionalOnMissingBean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean public RuleLinkInferencer linkInferencer(DitaSettings s) { return new RuleLinkInferencer(s.linking()); }

    @Bean
    public IngestionPipeline ingestionPipeline(MessageStore store, RuleLinkInferencer inf, DitaSettings s, EventPublisher d) {
        return new IngestionPipeline(store, inf, s.linking().windowMessages(), d);
    }

    @Bean
    public DeletionService deletionService(MessageStore store, RuleLinkInferencer inf, DitaSettings s, EventPublisher d) {
        return new DeletionService(store, inf, s.deletion(), d, s.linking().windowMessages());
    }

    @Bean @ConditionalOnMissingBean
    public MembershipPort membershipPort() { return (scope, member) -> Optional.empty(); }

    /** Display names for Dita's replies; empty unless a component records names (today: the TASK-054 trial only). */
    @Bean public app.dita.core.model.MemberNames memberNames() { return new app.dita.core.model.MemberNames(); }

    @Bean
    public QueryService queryService(MessageStore store, DitaSettings s, MembershipPort membership, Retriever retriever,
                                     app.dita.core.model.MemberNames memberNames,
                                     app.dita.core.knowledge.KnowledgeRetriever knowledge,
                                     org.springframework.beans.factory.ObjectProvider<app.dita.core.answer.synthesis.GroundedSynthesis> synthesis) {
        var access = new HistoryAccessFilter(s.raw().memory().historyAccess(), membership, store);
        if (s.raw().memory().answers().synthesis().enabled() && synthesis.getIfAvailable() == null)
            throw new DitaConfigurationException("dita.memory.answers.synthesis.enabled=true requires dita.muse.enabled=true");
        return new QueryService(store, retriever, access,
                s.retrieval().defaultK(), s.raw().memory().answers().maxQuoteChars(), memberNames::name, knowledge)
                .withSynthesis(synthesis.getIfAvailable());   // TASK-053: null (default) = deterministic answers only
    }

    @Bean public ClarificationGate clarificationGate(DitaSettings s) { return new ClarificationGate(s.clarification()); }

    @Bean @ConditionalOnMissingBean
    public GroupDirectory groupDirectory() { return new GroupDirectory(g -> true); }

    @Bean public DeletionSettings deletionSettings(DitaSettings s) { return s.deletion(); }
}
