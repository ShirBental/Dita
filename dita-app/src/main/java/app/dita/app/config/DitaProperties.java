package app.dita.app.config;

import app.dita.core.config.DeletionSettings.DitaReplies;
import app.dita.core.config.DeletionSettings.EarlyBehavior;
import app.dita.core.config.DeletionSettings.LateKnowledge;
import app.dita.core.config.DeletionSettings.RepeatedWording;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.memory.HistoryAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/**
 * System-administrator configuration for Dita (namespace {@code dita.*}). Read once at startup; changes take effect
 * after restart. Unknown keys fail startup (so no key can "turn off" a security invariant). Credentials are never
 * properties: only the NAMES of environment variables holding them are configured.
 * See docs/configuration.md for semantics; {@link SecurityInvariants} lists what is deliberately not configurable.
 */
@Validated
@ConfigurationProperties(prefix = "dita", ignoreUnknownFields = false)
public record DitaProperties(@Valid @DefaultValue Deletion deletion,
                             @Valid @DefaultValue Memory memory,
                             @Valid @DefaultValue Processing processing,
                             @Valid @DefaultValue Retrieval retrieval,
                             @Valid @DefaultValue Coordination coordination,
                             @Valid @DefaultValue Telegram telegram,
                             @Valid @DefaultValue Muse muse,
                             @Valid @DefaultValue Storage storage,
                             @Valid @DefaultValue Extraction extraction) {

    public enum Backend { IN_MEMORY, POSTGRES }

    /**
     * Hybrid extraction (TASK-043/044). batch-size: messages per approved batch. approval-ttl: an approval older than
     * this is never sent (it fails as ApprovalExpired). worker-enabled=false: no background worker; a controlled run
     * processes only its own batch explicitly.
     */
    public record Extraction(@Min(1) @Max(100) @DefaultValue("20") int batchSize,
                             @NotNull @DefaultValue("30m") Duration approvalTtl,
                             @DefaultValue("true") boolean workerEnabled) {
        public Extraction {   // N-77: an approval is consent for now, not standing consent
            if (approvalTtl.compareTo(Duration.ofMinutes(1)) < 0 || approvalTtl.compareTo(Duration.ofHours(1)) > 0)
                throw new IllegalArgumentException("dita.extraction.approval-ttl must be in [1m, 1h]");
        }
    }

    /**
     * Runtime storage (ADR-001). IN_MEMORY (default): in-process store, dispatcher and reference projections.
     * POSTGRES: PostgreSQL truth + transactional outbox, durable relay worker, real Neo4j and Vespa projections and
     * Vespa/Neo4j retrieval. Every endpoint must be loopback on a Dita 5xxxx host port (local demo only, DEC-010/N-29).
     * Passwords are never properties: only the NAMES of the environment variables that hold them. The application
     * never runs migrations (they are applied by scripts/e2e-verify.sh as the schema owner).
     */
    public record Storage(@NotNull @DefaultValue("IN_MEMORY") Backend backend,
                          @Valid @DefaultValue Postgres postgres,
                          @Valid @DefaultValue Neo4j neo4j,
                          @Valid @DefaultValue Vespa vespa,
                          /** Prefix of the durable relay cursor names (<prefix>-neo4j, <prefix>-vespa). Keep it stable in
                           *  production; a new prefix starts new cursors at the current end of the outbox. */
                          @NotNull @DefaultValue("app") @Pattern(regexp = "[a-z][a-z0-9-]{0,40}") String relayConsumerPrefix) {}

    public record Postgres(@NotNull @DefaultValue("jdbc:postgresql://127.0.0.1:55432/dita")
                           @Pattern(regexp = "jdbc:postgresql://127\\.0\\.0\\.1:5\\d{4}/[A-Za-z0-9_]{1,63}") String url,
                           @NotNull @DefaultValue("dita_app") @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}") String appUser,
                           @NotNull @DefaultValue("DITA_APP_PASSWORD") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String appPasswordEnvVar,
                           @NotNull @DefaultValue("dita_relay") @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}") String relayUser,
                           @NotNull @DefaultValue("DITA_RELAY_PASSWORD") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String relayPasswordEnvVar,
                           @Min(1) @Max(20) @DefaultValue("5") int maxPoolSize) {}

    public record Neo4j(@NotNull @DefaultValue("http://127.0.0.1:57474")
                        @Pattern(regexp = "http://127\\.0\\.0\\.1:5\\d{4}") String url,
                        @NotNull @DefaultValue("neo4j") @Pattern(regexp = "[A-Za-z0-9_]{1,63}") String user,
                        @NotNull @DefaultValue("DITA_NEO4J_PASSWORD") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String passwordEnvVar) {}

    public record Vespa(@NotNull @DefaultValue("http://127.0.0.1:58080")
                        @Pattern(regexp = "http://127\\.0\\.0\\.1:5\\d{4}") String url) {}

    /**
     * Meta Muse (Meta Model API). Endpoint and model must come from the official documentation; nothing is hard-coded.
     * The key is read at call time from the environment variable named here (default MODEL_API_KEY, e.g. exported from
     * .env.local). Only SYNTHETIC messages are ever sent (OPEN-6/C-1, not configurable).
     */
    public record Muse(@DefaultValue("false") boolean enabled,
                       @NotNull @DefaultValue("https://api.meta.ai/v1/chat/completions") String chatCompletionsUrl,
                       @NotNull @DefaultValue("muse-spark-1.3") @Pattern(regexp = "|[A-Za-z0-9._:/-]{1,128}") String model,
                       @NotNull @DefaultValue("MODEL_API_KEY") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String apiKeyEnvVar,
                       @NotNull @DefaultValue("30s") Duration timeout,
                       @Min(16) @Max(32768) @DefaultValue("1024") int maxOutputTokens,
                       @Min(100) @Max(200000) @DefaultValue("20000") int maxInputChars,
                       @Min(1) @Max(1000) @DefaultValue("20") int maxCallsPerProcess,
                       @NotNull @DefaultValue("PROMPT_ONLY") app.dita.muse.MuseSettings.ResponseFormat responseFormat,
                       @NotNull @DefaultValue("LOW") app.dita.muse.MuseSettings.ReasoningEffort reasoningEffort,
                       /** TASK-051: HTTP = the real Meta Model API (live calls need explicit approval); RECORDED = replies from
                        *  {@code recordingFile}, never a network call (the API key is not read). */
                       @NotNull @DefaultValue("HTTP") MuseTransportMode transport,
                       @NotNull @DefaultValue("") String recordingFile) {}

    public enum MuseTransportMode { HTTP, RECORDED }

    public record Deletion(@NotNull @DefaultValue("5m") Duration earlyWindow,
                           @NotNull @DefaultValue("REMOVE_CONTRIBUTION_AND_RECALCULATE") EarlyBehavior earlyBehavior,
                           @NotNull @DefaultValue("PRESERVE_WITH_DELETED_SOURCE_PROVENANCE") LateKnowledge lateDerivedKnowledge,
                           @NotNull @DefaultValue("REDACT_WORDING") RepeatedWording repeatedWording,
                           @NotNull @DefaultValue("21d") Duration groupRemovalRetention,
                           @DefaultValue("false") boolean adminsMayForgetOthers,
                           @NotNull @DefaultValue("KEEP") DitaReplies ditaReplies) {}

    public record Memory(@NotNull @DefaultValue("FULL_GROUP_HISTORY") HistoryAccess historyAccess,
                         @Valid @DefaultValue Answers answers,
                         @Valid @DefaultValue Context context,
                         @Valid @DefaultValue Linking linking,
                         @Valid @DefaultValue Clarification clarification) {}

    public record Answers(@Min(40) @Max(1000) @DefaultValue("200") int maxQuoteChars,
                          @Valid @DefaultValue Synthesis synthesis) {}

    /**
     * TASK-053: Muse answer synthesis after the deterministic answer. OFF by default until validated. Requires
     * dita.muse.enabled. With the HTTP (live) transport it is refused unless {@code allowLive} is set explicitly, because
     * every /ask would become a live Muse call (each needs prior approval). {@code prompt}: instruction version (TASK-054;
     * V1 is kept only to reproduce earlier recorded trials).
     */
    public record Synthesis(@DefaultValue("false") boolean enabled, @DefaultValue("false") boolean allowLive,
                            @DefaultValue("V2") app.dita.muse.MuseAnswerSynthesizer.Prompt prompt,
                            @DefaultValue("true") boolean enforceLanguageRules, @DefaultValue("true") boolean showSources) {}

    public record Context(@Min(1) @Max(1000) @DefaultValue("50") int linkWindowMessages,
                          @NotNull @DefaultValue("30m") Duration temporalHalfLife) {}

    public record Linking(@DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
                          @DefaultValue("0.35") double candidateThreshold,
                          @DecimalMin("0") @DecimalMax(value = "0.5", inclusive = false) @DefaultValue("0.05") double ambiguityMargin) {}

    public record Clarification(@DefaultValue("false") boolean enabled,
                                @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
                                @DefaultValue("0.35") double minCandidateScore,
                                @DecimalMin("0") @DecimalMax(value = "0.5", inclusive = false) @DefaultValue("0.05") double maxScoreGap,
                                @Min(0) @Max(60) @DefaultValue("2") int maxPerGroup,
                                @NotNull @DefaultValue("1h") Duration rateWindow) {}

    public record Processing(@Valid @DefaultValue Escalation escalation,
                             @Valid @DefaultValue Tiers tiers,
                             @Valid @DefaultValue Dispatch dispatch) {}

    /** Inferred-link scores in [low, high] are marked needs_adjudication for tier 1. */
    public record Escalation(@DecimalMin("0") @DecimalMax("1") @DefaultValue("0.30") double adjudicationLow,
                             @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.55") double adjudicationHigh) {}

    /** Tier 0 is always deterministic rules and is not configurable. */
    public record Tiers(@Valid @DefaultValue Tier t1, @Valid @DefaultValue Tier t2) {}

    public enum Provider { NONE, META_MODEL_API }

    public record Tier(@NotNull @DefaultValue("NONE") Provider provider,
                       @NotNull @DefaultValue("") @Pattern(regexp = "[A-Za-z0-9._:/-]{0,128}") String model) {}

    public record Dispatch(@Min(1) @Max(10000) @DefaultValue("100") int batchSize,
                           @Min(1) @Max(32) @DefaultValue("2") int workerThreads,
                           @Min(1) @Max(100) @DefaultValue("5") int maxAttempts,
                           @NotNull @DefaultValue("1s") Duration initialBackoff,
                           @NotNull @DefaultValue("1m") Duration maxBackoff,
                           @NotNull @DefaultValue("500ms") Duration pollInterval) {}

    public record Retrieval(@Valid @DefaultValue Sparse sparse,
                            @Valid @DefaultValue Dense dense,
                            @Valid @DefaultValue Graph graph,
                            @Valid @DefaultValue Fusion fusion,
                            @Min(1) @Max(200) @DefaultValue("8") int defaultK,
                            @Min(1) @Max(200) @DefaultValue("50") int maxK) {}

    public record Sparse(@DefaultValue("true") boolean enabled,
                         @DecimalMin(value = "0", inclusive = false) @DecimalMax("3") @DefaultValue("1.2") double bm25K1,
                         @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.75") double bm25B) {}

    /** Dense retrieval requires the separately built hash-verified Vespa hybrid package. */
    public record Dense(@DefaultValue("false") boolean enabled,
                        @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.60") double minCosine,
                        /* TASK-054 G1: semantic retrieval over RAW MESSAGES (same frozen bge-small embedder; POSTGRES only) */
                        @DefaultValue("false") boolean messages,
                        @DecimalMin("0.5") @DecimalMax("1") @DefaultValue("0.60") double messagesMinCosine,
                        @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.5") double messagesWeight) {}

    public record Graph(@DefaultValue("true") boolean enabled,
                        @Min(0) @Max(4) @DefaultValue("2") int depth,
                        @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") @DefaultValue("0.8") double explicitEdgeWeight,
                        @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.5") double candidateEdgeWeight) {}

    public record Fusion(@DecimalMin(value = "0", inclusive = false) @DefaultValue("60") double rrfK,
                         @Min(1) @Max(20) @DefaultValue("3") int candidateMultiplier,
                         @DecimalMin("0") @DefaultValue("1.0") double sparseWeight,
                         @DecimalMin("0") @DefaultValue("1.0") double graphWeight) {}

    public record Coordination(@DefaultValue("true") boolean enabled,
                               @Valid @DefaultValue Approval approval,
                               @Valid @DefaultValue Tasks tasks) {}

    public record Approval(@NotEmpty @DefaultValue({"BUTTON", "COMMAND"}) Set<ApprovalAct> allowedActs) {}

    public record Tasks(@Min(1) @Max(500) @DefaultValue("50") int maxPerPlan) {}

    public record Telegram(@DefaultValue("false") boolean enabled,
                           @NotNull @DefaultValue("") @Pattern(regexp = "|[A-Za-z0-9_]{5,32}") String botUsername,
                           @NotNull @DefaultValue("30s") Duration pollTimeout,
                           @Min(1) @Max(100) @DefaultValue("100") int pollLimit,
                           @NotNull @DefaultValue("TELEGRAM_BOT_TOKEN") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String tokenEnvVar,
                           @NotNull @DefaultValue("DITA_CALLBACK_SECRET") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String callbackSecretEnvVar,
                           /** OPEN-6/C-1: only these synthetic demo chats are served; everything else is ignored. */
                           @NotNull @DefaultValue({}) Set<Long> allowedChatIds,
                           /** TASK-040 / E-3: only these Telegram user ids (from.id) are accepted in the allowed chats.
                            *  Everyone else (other people, bots, anonymous admins, channels) is ignored. Fail-closed. */
                           @NotNull @DefaultValue({}) Set<Long> allowedAuthorIds,
                           /** TASK-051: LIVE polls the Bot API; REPLAY feeds a synthetic replay file through the same poller and
                            *  adapter, never contacts Telegram, stores SYNTHETIC messages in a separate namespace. */
                           @NotNull @DefaultValue("LIVE") TelegramMode mode,
                           @Valid @DefaultValue Replay replay,
                           /** TASK-054: controlled synthetic trial on LIVE Telegram (off by default; see SyntheticTrial). */
                           @Valid @DefaultValue SyntheticTrial syntheticTrial) {}

    public enum TelegramMode { LIVE, REPLAY }

    /**
     * TASK-054 controlled synthetic trial on LIVE Telegram. OFF by default. When on, ONLY messages the allow-listed sender
     * explicitly declares with {@code /syn <statement>} are stored (SYNTHETIC, separate namespace "telegram-trial"/"tgt…");
     * every other message is ignored (or, with {@code sessionDeclaration}, new plain text from that sender is stored). /ask
     * first prepares pending statements (one extraction call per batch), then answers (one synthesis call); and every call needs the operator's terminal approval of its exact request (request-bound, SHA prefix typed).
     * {@code evidenceDir}: per-call request/approval/response evidence. {@code maxPaidCalls}: approved calls per run.
     */
    public record SyntheticTrial(@DefaultValue("false") boolean enabled,
                                 @NotNull @DefaultValue({}) Set<Long> approverIds,
                                 @NotNull @DefaultValue("") String evidenceDir,
                                 @NotNull @DefaultValue("10m") Duration approvalTimeout,
                                 @Min(1) @Max(20) @DefaultValue("6") int maxPaidCalls,
                                 @NotNull @DefaultValue("") String sessionDeclaration,
                                 /** How long an /ask waits for the projections after preparing knowledge before it stops. */
                                 @NotNull @DefaultValue("60s") Duration readinessTimeout,
                                 /** /ask preparation: retrieval depth (relevant messages considered per question). */
                                 @Min(1) @Max(20) @DefaultValue("8") int maxTargetsPerQuestion,
                                 /** /ask preparation: target messages per extraction call (one approval each). */
                                 @Min(1) @Max(10) @DefaultValue("4") int extractionBatchTargets,
                                 @DefaultValue("0") double sessionBudgetUsd,
                                 /* TASK-054: KNOWLEDGE (extraction then synthesis) or MESSAGES (raw messages, one synthesis call) */
                                 @NotNull @DefaultValue("KNOWLEDGE") app.dita.app.trial.SyntheticTrialController.AnswerMode answerMode,
                                 @NotNull @DefaultValue({}) Set<Long> sessionAuthorIds,
                                 /*
                                  * TASK-054 (DEC-050) explicit continuation, MESSAGES + CONFIRM_IN_TELEGRAM only: the confirmed_at of ONE
                                  * earlier session (its session.json, ISO-8601). Messages sent since that instant are used for answers;
                                  * older history stays excluded. Empty (default) = this session only. Never deletes or resets anything.
                                  */
                                 @NotNull @DefaultValue("") String continueSessionFrom,
                                 @NotNull @DefaultValue("") String continueSessionUntil,
                                 java.util.Map<String, String> memberNames) {
        public SyntheticTrial { memberNames = memberNames == null ? java.util.Map.of() : java.util.Map.copyOf(memberNames); }
    }

    /** TASK-051 synthetic replay. {@code extractionApproverIds}: from.id values whose /extract approves pending extraction. */
    /** {@code transcriptFile}: optional JSON Lines transcript of Dita's outgoing replies (plus a readable .txt copy). */
    public record Replay(@NotNull @DefaultValue("") String file,
                         @NotNull @DefaultValue({}) Set<Long> extractionApproverIds,
                         @NotNull @DefaultValue("60s") Duration barrierTimeout,
                         @NotNull @DefaultValue("") String transcriptFile,
                         @DefaultValue("false") boolean transcriptEcho) {}
}
