package app.dita.app.config;

import app.dita.core.config.ClarificationSettings;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DispatchSettings;
import app.dita.core.config.LinkSettings;
import app.dita.core.config.RetrievalSettings;

import java.util.function.Function;

/**
 * Validated, immutable runtime settings derived once from {@link DitaProperties}. Performs cross-field and safety
 * validation that annotations cannot express; any failure aborts startup with a {@link DitaConfigurationException}.
 */
public record DitaSettings(DeletionSettings deletion, LinkSettings linking, ClarificationSettings clarification,
                           RetrievalSettings retrieval, DispatchSettings dispatch, DitaProperties raw,
                           TelegramCredentials telegram, app.dita.muse.MuseSettings muse) {

    /** Present only when Telegram is enabled. Never logged (toString redacts). */
    public record TelegramCredentials(String botToken, byte[] callbackSecret) {
        @Override public String toString() { return "TelegramCredentials[<redacted>]"; }
    }

    public static DitaSettings from(DitaProperties p, Function<String, String> env) {
        DeletionSettings deletion = core("dita.deletion", () -> new DeletionSettings(p.deletion().earlyWindow(),
                p.deletion().earlyBehavior(), p.deletion().lateDerivedKnowledge(), p.deletion().repeatedWording(),
                p.deletion().groupRemovalRetention(), p.deletion().adminsMayForgetOthers(), p.deletion().ditaReplies()));

        var mem = p.memory();
        var esc = p.processing().escalation();
        LinkSettings linking = core("dita.memory.linking / dita.processing.escalation", () -> new LinkSettings(
                mem.linking().candidateThreshold(), mem.linking().ambiguityMargin(), mem.context().linkWindowMessages(),
                mem.context().temporalHalfLife().toSeconds() / 60.0, esc.adjudicationLow(), esc.adjudicationHigh()));

        var c = mem.clarification();
        ClarificationSettings clarification = core("dita.memory.clarification", () ->
                new ClarificationSettings(c.enabled(), c.minCandidateScore(), c.maxScoreGap(), c.maxPerGroup(), c.rateWindow()));
        if (c.enabled() && c.minCandidateScore() < mem.linking().candidateThreshold()) {
            throw new DitaConfigurationException("dita.memory.clarification.min-candidate-score must be >= "
                    + "dita.memory.linking.candidate-threshold (no candidate below the threshold exists)");
        }

        var r = p.retrieval();
        if (r.dense().messages() && p.storage().backend() != DitaProperties.Backend.POSTGRES) {
            throw new DitaConfigurationException("dita.retrieval.dense.messages=true requires dita.storage.backend=POSTGRES "
                    + "(the bge-small message embeddings live in the Vespa package; there is no in-memory embedder)");
        }
        if (r.dense().enabled() && p.storage().backend() != DitaProperties.Backend.POSTGRES) {
            throw new DitaConfigurationException("dense retrieval requires the POSTGRES/Vespa runtime and a hash-verified hybrid package");
        }
        RetrievalSettings retrieval = core("dita.retrieval", () -> new RetrievalSettings(r.sparse().enabled(), r.sparse().bm25K1(),
                r.sparse().bm25B(), r.graph().enabled(), r.graph().depth(), r.graph().explicitEdgeWeight(),
                r.graph().candidateEdgeWeight(), r.fusion().rrfK(), r.fusion().candidateMultiplier(),
                r.fusion().sparseWeight(), r.fusion().graphWeight(), r.defaultK(), r.maxK()));

        app.dita.muse.MuseSettings muse = null;
        var mp = p.muse();
        if (mp.enabled()) {
            if (mp.chatCompletionsUrl().isBlank() || mp.model().isBlank()) {
                throw new DitaConfigurationException("dita.muse.enabled=true requires dita.muse.chat-completions-url and dita.muse.model "
                        + "(take both from the official Meta Model API documentation)");
            }
            // Static configuration first (https, model id, limits), then the environment.
            muse = core("dita.muse", () -> new app.dita.muse.MuseSettings(java.net.URI.create(mp.chatCompletionsUrl()), mp.model(),
                    mp.apiKeyEnvVar(), mp.timeout(), mp.maxOutputTokens(), mp.maxInputChars(), mp.maxCallsPerProcess(), mp.responseFormat(),
                    mp.reasoningEffort()));
            if (mp.transport() == DitaProperties.MuseTransportMode.RECORDED) {
                // TASK-051: recorded replies only; the API key is never read, so no live call is possible from this process.
                if (mp.recordingFile().isBlank() || !java.nio.file.Files.isReadable(java.nio.file.Path.of(mp.recordingFile()))) {
                    throw new DitaConfigurationException("dita.muse.recording-file must name a readable recording (dita.muse.transport=RECORDED)");
                }
            } else {
                String k = env.apply(mp.apiKeyEnvVar());
                if (k == null || k.isBlank()) {
                    throw new DitaConfigurationException("environment variable " + mp.apiKeyEnvVar() + " must hold the Meta Model API key (value not shown)");
                }
            }
        }
        for (var tier : new Object[][]{{"t1", p.processing().tiers().t1()}, {"t2", p.processing().tiers().t2()}}) {
            var t = (DitaProperties.Tier) tier[1];
            if (t.provider() == DitaProperties.Provider.META_MODEL_API && muse == null) {
                throw new DitaConfigurationException("dita.processing.tiers." + tier[0] + ".provider=META_MODEL_API requires dita.muse.enabled=true");
            }
            if (t.provider() == DitaProperties.Provider.META_MODEL_API && !t.model().isEmpty() && !t.model().equals(mp.model())) {
                throw new DitaConfigurationException("dita.processing.tiers." + tier[0] + ".model must equal dita.muse.model (Muse is Dita's single reasoning model)");
            }
            if (t.provider() == DitaProperties.Provider.NONE && !t.model().isEmpty()) {
                throw new DitaConfigurationException("dita.processing.tiers." + tier[0] + ".model must be empty when provider is NONE");
            }
        }

        var d = p.processing().dispatch();
        DispatchSettings dispatch = core("dita.processing.dispatch", () ->
                new DispatchSettings(d.batchSize(), d.workerThreads(), d.maxAttempts(), d.initialBackoff(), d.maxBackoff()));

        TelegramCredentials creds = null;
        var tg = p.telegram();
        validateSyntheticTrial(p);
        if (tg.enabled()) {
            if (tg.allowedChatIds().isEmpty()) {
                throw new DitaConfigurationException("dita.telegram.allowed-chat-ids must list the synthetic demo chats "
                        + "(real-group onboarding is disabled until erasure and platform requirements are reviewed, C-1/OPEN-6)");
            }
            if (tg.allowedChatIds().stream().anyMatch(id -> id >= 0)) {
                throw new DitaConfigurationException("dita.telegram.allowed-chat-ids must be group chat ids (negative); private chats are never served");
            }
            if (tg.allowedAuthorIds().isEmpty()) {
                throw new DitaConfigurationException("dita.telegram.allowed-author-ids must list the synthetic demo authors "
                        + "(Telegram user ids); without it no message would be accepted (TASK-040, E-3)");
            }
            if (tg.allowedAuthorIds().stream().anyMatch(id -> id <= 0)) {
                throw new DitaConfigurationException("dita.telegram.allowed-author-ids must be Telegram user ids (positive)");
            }
            if (tg.mode() == DitaProperties.TelegramMode.REPLAY) {
                // TASK-051: no Telegram credentials are read or needed; the replay never contacts Telegram.
                var rp = tg.replay();
                if (mp.enabled() && mp.transport() != DitaProperties.MuseTransportMode.RECORDED) {
                    // Natasha H-1: a replay /extract is never approval for a live call (that needs scripts/muse-live.sh)
                    throw new DitaConfigurationException("dita.telegram.mode=REPLAY requires dita.muse.transport=RECORDED (no live Muse calls from a replay)");
                }
                if (rp.file().isBlank() || !java.nio.file.Files.isReadable(java.nio.file.Path.of(rp.file()))) {
                    throw new DitaConfigurationException("dita.telegram.replay.file must name a readable synthetic replay file (REPLAY mode)");
                }
                if (rp.extractionApproverIds().isEmpty() || !tg.allowedAuthorIds().containsAll(rp.extractionApproverIds())) {
                    throw new DitaConfigurationException("dita.telegram.replay.extraction-approver-ids must be non-empty allow-listed authors (E-1)");
                }
                byte[] secret = new byte[32];
                new java.security.SecureRandom().nextBytes(secret);              // per-process; replay buttons only
                return new DitaSettings(deletion, linking, clarification, retrieval, dispatch, p,
                        new TelegramCredentials("", secret), muse);
            }
            if (!tg.replay().file().isBlank() || !tg.replay().extractionApproverIds().isEmpty()
                    || !tg.replay().transcriptFile().isBlank() || tg.replay().transcriptEcho()) {   // no reply logging on live Telegram
                throw new DitaConfigurationException("dita.telegram.replay.* is only valid with dita.telegram.mode=REPLAY; "
                        + "live Telegram messages are never offered to extraction (N-8)");
            }
            String token = env.apply(tg.tokenEnvVar());
            if (token == null || !token.matches("\\d{5,16}:[A-Za-z0-9_-]{30,64}")) {
                throw new DitaConfigurationException("environment variable " + tg.tokenEnvVar()
                        + " must hold a Telegram bot token (value not shown)");
            }
            String secret = env.apply(tg.callbackSecretEnvVar());
            if (secret == null || secret.length() < 32) {
                throw new DitaConfigurationException("environment variable " + tg.callbackSecretEnvVar()
                        + " must hold at least 32 characters for callback signing (value not shown)");
            }
            creds = new TelegramCredentials(token, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new DitaSettings(deletion, linking, clarification, retrieval, dispatch, p, creds, muse);
    }

    /**
     * TASK-054: the synthetic trial is valid only as an explicit, single-chat, operator-attended LIVE run. Each condition
     * closes a way a paid call could happen without its own approval, or a non-declared message could be treated as synthetic.
     */
    static void validateSyntheticTrial(DitaProperties p) {
        var st = p.telegram().syntheticTrial();
        if (!st.sessionDeclaration().isEmpty() && (!st.enabled() || !java.util.Set.of("ALL_NEW_MESSAGES_SYNTHETIC", "CONFIRM_IN_TELEGRAM", "PREAUTHORIZED_SESSION").contains(st.sessionDeclaration())))
            throw new DitaConfigurationException("Plain trial messages require enabled trial and session-declaration=ALL_NEW_MESSAGES_SYNTHETIC, CONFIRM_IN_TELEGRAM or PREAUTHORIZED_SESSION");
        if (!st.enabled()) return;
        String pre = "dita.telegram.synthetic-trial.enabled=true requires ";
        if (!p.telegram().enabled() || p.telegram().mode() != DitaProperties.TelegramMode.LIVE)
            throw new DitaConfigurationException(pre + "dita.telegram.enabled=true and mode=LIVE (REPLAY keeps its own rules)");
        if (p.telegram().allowedChatIds().size() != 1)
            throw new DitaConfigurationException(pre + "exactly one dita.telegram.allowed-chat-ids entry");
        if (st.approverIds().isEmpty() || !p.telegram().allowedAuthorIds().containsAll(st.approverIds()))
            throw new DitaConfigurationException(pre + "non-empty approver-ids that are all allow-listed authors");
        if (!st.sessionDeclaration().isEmpty() && (st.approverIds().size() != 1 || !p.telegram().allowedAuthorIds().equals(st.sessionAuthorIds().isEmpty() ? st.approverIds() : st.sessionAuthorIds())))
            throw new DitaConfigurationException(pre + "with a session declaration, exactly one allow-listed author who is also the approver "
                    + "(only the declaring operator's plain messages may be stored as synthetic)");               // Natasha L1
        if (!p.muse().enabled())
            throw new DitaConfigurationException(pre + "dita.muse.enabled=true");
        if (!p.memory().answers().synthesis().enabled())
            throw new DitaConfigurationException(pre + "dita.memory.answers.synthesis.enabled=true");
        if (p.extraction().workerEnabled())
            throw new DitaConfigurationException(pre + "dita.extraction.worker-enabled=false (only the approved batch is processed)");
        if (p.coordination().enabled())
            throw new DitaConfigurationException(pre + "dita.coordination.enabled=false");
        if (st.evidenceDir().isBlank())
            throw new DitaConfigurationException(pre + "dita.telegram.synthetic-trial.evidence-dir");
    }

    private static <T> T core(String prefix, java.util.function.Supplier<T> f) {
        try {
            return f.get();
        } catch (IllegalArgumentException e) {
            throw new DitaConfigurationException(prefix + ": " + e.getMessage(), e);
        }
    }
}
