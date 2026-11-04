package app.dita.app.config;

import app.dita.core.knowledge.KnowledgeProposer;
import app.dita.muse.MuseClient;
import app.dita.muse.MuseKnowledgeExtractor;
import app.dita.muse.MuseKnowledgeProposer;
import app.dita.muse.MuseTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * Active only with dita.muse.enabled=true. Muse output is validated data; nothing is stored or acted on automatically:
 * extraction runs only for explicitly approved batches (ExtractionService, E-1). Tests replace the transport with a
 * recorded one (no network).
 */
@Configuration
@ConditionalOnProperty(prefix = "dita.muse", name = "enabled", havingValue = "true")
public class MuseConfiguration {
    /** TASK-051: RECORDED serves replies from dita.muse.recording-file and never opens a connection. */
    @Bean @ConditionalOnMissingBean public MuseTransport museTransport(DitaProperties p) {
        return p.muse().transport() == DitaProperties.MuseTransportMode.RECORDED
                ? app.dita.muse.RecordedMuseTransport.fromFile(java.nio.file.Path.of(p.muse().recordingFile()))
                : MuseTransport.http();
    }

    @Bean public MuseClient museClient(DitaSettings s, Function<String, String> ditaEnv, MuseTransport transport, DitaProperties p,
                                       org.springframework.beans.factory.ObjectProvider<app.dita.app.trial.TrialEvidence> trialEvidence,
                                       org.springframework.beans.factory.ObjectProvider<app.dita.app.trial.CallApprover> trialApprover) {
        if (p.muse().transport() == DitaProperties.MuseTransportMode.RECORDED && !(transport instanceof app.dita.muse.RecordedMuseTransport)) {
            throw new IllegalStateException("dita.muse.transport=RECORDED but the MuseTransport in use is not the recorded transport (Natasha N-1)");
        }
        // RECORDED: the real key is never read in this process; the client gets a non-secret placeholder.
        Function<String, String> env = p.muse().transport() == DitaProperties.MuseTransportMode.RECORDED
                ? n -> n.equals(p.muse().apiKeyEnvVar()) ? "recorded-transport-no-api-key" : ditaEnv.apply(n)
                : ditaEnv;
        // TASK-054: in the controlled Telegram synthetic trial EVERY call goes through the per-call approval gate (below the
        // client, so the gated bytes are exactly the bytes sent). Fail closed if the trial beans are missing.
        MuseTransport effective = transport;
        if (p.telegram().syntheticTrial().enabled()) {
            var ev = trialEvidence.getIfAvailable();
            var ap = trialApprover.getIfAvailable();
            if (ev == null || ap == null) throw new DitaConfigurationException("synthetic trial enabled but its approval gate is not configured");
            effective = new app.dita.app.trial.ApprovalGatedTransport(transport, ap, ev, new app.dita.app.trial.ApprovalGatedTransport.CallSettings(
                    p.muse().chatCompletionsUrl(), p.muse().model(), p.muse().maxOutputTokens(), p.muse().reasoningEffort().wire(),
                    p.muse().timeout().toString()), p.telegram().syntheticTrial().maxPaidCalls());
        }
        return new MuseClient(s.muse(), effective, env);
    }

    @Bean public MuseKnowledgeExtractor museKnowledgeExtractor(MuseClient client) { return new MuseKnowledgeExtractor(client); }

    @Bean public KnowledgeProposer knowledgeProposer(MuseClient client) { return new MuseKnowledgeProposer(client); }

    /** TASK-053: answer synthesis (off by default). Live (HTTP) synthesis needs dita.memory.answers.synthesis.allow-live. */
    @Bean @ConditionalOnProperty(prefix = "dita.memory.answers.synthesis", name = "enabled", havingValue = "true")
    public app.dita.core.answer.synthesis.GroundedSynthesis groundedSynthesis(app.dita.core.store.MessageStore store, MuseClient client,
            app.dita.core.model.MemberNames memberNames,
                                                                              MuseTransport transport, DitaProperties p,
                                                                              org.springframework.beans.factory.ObjectProvider<app.dita.core.answer.synthesis.GroundedSynthesis.Observer> observer) {
        var syn = p.memory().answers().synthesis();
        boolean recorded = transport instanceof app.dita.muse.RecordedMuseTransport;
        if (!recorded && !syn.allowLive()) {
            throw new DitaConfigurationException("dita.memory.answers.synthesis.enabled with a live Muse transport requires "
                    + "dita.memory.answers.synthesis.allow-live=true (every /ask becomes a live Muse call)");
        }
        // Optional observer (controlled trials only): records the actual outcome and violations; no bean = no-op.
        var obs = observer.getIfAvailable();
        return new app.dita.core.answer.synthesis.GroundedSynthesis(store, new app.dita.muse.MuseAnswerSynthesizer(client, syn.prompt()), memberNames::name,
                obs == null ? r -> {} : obs, syn.enforceLanguageRules());
    }
}
