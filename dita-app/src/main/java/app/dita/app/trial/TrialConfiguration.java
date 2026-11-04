package app.dita.app.trial;

import app.dita.app.config.DitaProperties;
import app.dita.core.QueryService;
import app.dita.core.answer.synthesis.GroundedSynthesis;
import app.dita.core.knowledge.ExtractionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * TASK-054: beans of the controlled Telegram synthetic trial. Active ONLY with dita.telegram.synthetic-trial.enabled=true
 * (validated in DitaSettings: LIVE Telegram, one chat, Muse + synthesis on, extraction worker off, coordination off).
 */
@Configuration
@ConditionalOnProperty(prefix = "dita.telegram.synthetic-trial", name = "enabled", havingValue = "true")
public class TrialConfiguration {

    @Bean public TrialEvidence trialEvidence(DitaProperties p, java.time.Clock clock) {
        String tag = "run-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant())
                + "-" + Integer.toHexString(new java.security.SecureRandom().nextInt(0x10000) | 0x10000).substring(1);
        return new TrialEvidence(Path.of(p.telegram().syntheticTrial().evidenceDir()), tag);
    }

    /**
     * Approval of each call: Approve / Cancel buttons in the trial chat with the in-chat session declaration
     * (CONFIRM_IN_TELEGRAM), the terminal otherwise. Tests replace it with a scripted approver.
     */
    @Bean @ConditionalOnMissingBean public CallApprover callApprover(DitaProperties p) {
        var st = p.telegram().syntheticTrial();
        return SyntheticTrialController.sessionBounded(st.sessionDeclaration())
                ? new TelegramButtonApprover(st.approvalTimeout(), st.sessionBudgetUsd()) : new TtyCallApprover(st.approvalTimeout());
    }

    /** CONFIRM_IN_TELEGRAM: post the session setup screen once the application (and polling) is up. */
    @Bean public org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> trialSetupScreen(
            SyntheticTrialController controller) {
        return e -> controller.announce();
    }

    /** Records synthesis results of the trial request in progress (outcome and violations; evidence only). */
    @Bean public GroundedSynthesis.Observer trialSynthesisObserver() { return TrialContext::record; }

    /** Projection readiness: the replay barrier's check (relay lag 0 for neo4j + vespa on POSTGRES; dispatcher lag 0 IN_MEMORY). */
    @Bean @ConditionalOnMissingBean public TrialReadiness trialReadiness(DitaProperties p,
            org.springframework.beans.factory.ObjectProvider<app.dita.store.postgres.PostgresOutboxRelay> relays,
            org.springframework.beans.factory.ObjectProvider<app.dita.core.events.EventDispatcher> dispatchers) {
        var relay = relays.getIfAvailable();
        if (relay != null) return scope -> relay.lag(app.dita.app.config.PostgresRuntimeConfiguration.neo4jConsumer(p)) == 0
                && relay.lag(app.dita.app.config.PostgresRuntimeConfiguration.vespaConsumer(p)) == 0;
        var d = dispatchers.getIfAvailable();
        if (d == null) throw new app.dita.app.config.DitaConfigurationException("synthetic trial: no projection pipeline to check readiness");
        return scope -> d.maxLag() == 0;
    }

    @Bean public SyntheticTrialController syntheticTrialController(ExtractionService extraction, app.dita.core.knowledge.ExtractionQueue queue,
                                                                   app.dita.core.store.MessageStore store,
                                                                   QueryService queries, TrialEvidence evidence, DitaProperties p, TrialReadiness readiness,
                                                                   CallApprover approver, app.dita.core.model.MemberNames memberNames) {
        var st = p.telegram().syntheticTrial();
        st.memberNames().forEach((id, name) -> {
            if (p.telegram().allowedAuthorIds().stream().anyMatch(a -> Long.toString(a).equals(id))) memberNames.observe(id, name);
        });
        java.util.Map<String, Object> run = new java.util.LinkedHashMap<>();
        run.put("run_tag", evidence.runTag()); run.put("chat_ids", p.telegram().allowedChatIds());
        run.put("author_ids", p.telegram().allowedAuthorIds()); run.put("declared_session_author_ids", st.sessionAuthorIds()); run.put("approver_ids", st.approverIds());
        run.put("backend", p.storage().backend().name()); run.put("model", p.muse().model()); run.put("destination", p.muse().chatCompletionsUrl());
        run.put("max_output_tokens", p.muse().maxOutputTokens()); run.put("reasoning_effort", p.muse().reasoningEffort().name());
        run.put("enforce_language_rules", p.memory().answers().synthesis().enforceLanguageRules());
        run.put("show_sources", p.memory().answers().synthesis().showSources());
        run.put("synthesis_prompt", p.memory().answers().synthesis().prompt().version); run.put("max_paid_calls", st.maxPaidCalls());
        run.put("approval_timeout", st.approvalTimeout().toString());
        run.put("session_declaration", st.sessionDeclaration());
        run.put("readiness_timeout", st.readinessTimeout().toString());
        run.put("session_budget_usd", st.sessionBudgetUsd());
        run.put("max_targets_per_question", st.maxTargetsPerQuestion()); run.put("extraction_batch_targets", st.extractionBatchTargets());
        run.put("answer_mode", st.answerMode().name());
        java.time.Instant continueFrom = SyntheticTrialController.continuation(st.continueSessionFrom(), st.continueSessionUntil(), st.answerMode(), st.sessionDeclaration(), java.time.Instant.now(),
                evidence.root().getParent());
        java.util.Set<String> continueAuthors = new java.util.HashSet<>();
        st.sessionAuthorIds().forEach(a -> continueAuthors.add(Long.toString(a)));
        if (continueAuthors.isEmpty()) st.approverIds().forEach(a -> continueAuthors.add(Long.toString(a)));
        run.put("continue_session_from", continueFrom == null ? "" : continueFrom.toString());
        var continueUntil = st.continueSessionUntil().isBlank() ? null : java.time.Instant.parse(st.continueSessionUntil());
        run.put("continue_session_until", continueUntil == null ? "" : continueUntil.toString());
        run.put("semantic_message_retrieval", p.retrieval().dense().messages());
        run.put("semantic_message_min_cosine", p.retrieval().dense().messagesMinCosine());
        evidence.writeJson(evidence.root().resolve("run.json"), run);
        long chat = p.telegram().allowedChatIds().iterator().next();
        long sender = st.approverIds().iterator().next();
        return new SyntheticTrialController(extraction, queue, store, queries, evidence, p.memory().answers().synthesis().prompt().version,
                readiness, st.readinessTimeout(), new SyntheticTrialController.Limits(st.maxTargetsPerQuestion(), st.extractionBatchTargets()),
                st.sessionDeclaration(), approver instanceof TelegramButtonApprover b ? b : null,
                new SyntheticTrialController.SetupInfo("this group", chat, sender, p.muse().model(), st.maxPaidCalls(),
                        p.retrieval().dense().messages()), st.answerMode()).memberNames(memberNames).showSources(p.memory().answers().synthesis().showSources()).continueFrom(continueFrom, continueAuthors).continueUntil(continueUntil);
    }
}
