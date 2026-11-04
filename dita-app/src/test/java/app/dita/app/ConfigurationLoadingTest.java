package app.dita.app;

import app.dita.app.config.ConfigAudit;
import app.dita.app.config.DitaConfiguration;
import app.dita.app.config.DitaSettings;
import app.dita.core.config.ClarificationSettings;
import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DispatchSettings;
import app.dita.core.config.LinkSettings;
import app.dita.core.config.RetrievalSettings;
import app.dita.core.deletion.DeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationLoadingTest {
    static final String FAKE_TOKEN = "123456789:" + "A".repeat(35);          // synthetic, not a real token
    static final String FAKE_SECRET = "s".repeat(40);

    final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(DitaConfiguration.class, app.dita.app.config.InMemoryRuntimeConfiguration.class, app.dita.app.config.TelegramConfiguration.class, app.dita.app.config.MuseConfiguration.class);
    final ApplicationContextRunner withYaml = bare.withInitializer(new ConfigDataApplicationContextInitializer());

    static Function<String, String> env(Map<String, String> m) { return m::get; }

    @Test void applicationYmlLoadsAndMatchesProductOwnerDefaults() {
        withYaml.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DitaSettings s = ctx.getBean(DitaSettings.class);
            assertThat(s.deletion()).isEqualTo(DeletionSettings.DEFAULTS);
            assertThat(s.linking()).isEqualTo(LinkSettings.DEFAULTS);
            assertThat(s.retrieval()).isEqualTo(RetrievalSettings.DEFAULTS);
            assertThat(s.clarification()).isEqualTo(ClarificationSettings.DEFAULTS);
            assertThat(s.dispatch()).isEqualTo(DispatchSettings.DEFAULTS);
            assertThat(s.raw().memory().answers().maxQuoteChars()).isEqualTo(200);
            assertThat(s.telegram()).isNull();
            assertThat(ConfigAudit.nonDefaults(s)).isEmpty();
            assertThat(ctx).hasSingleBean(DeletionService.class);
            assertThat(ctx).doesNotHaveBean(app.dita.telegram.TelegramAdapter.class);
        });
    }

    @Test void bindingDefaultsWithoutYamlAreTheSame() {
        bare.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DitaSettings s = ctx.getBean(DitaSettings.class);
            assertThat(s.deletion()).isEqualTo(DeletionSettings.DEFAULTS);
            assertThat(s.retrieval()).isEqualTo(RetrievalSettings.DEFAULTS);
            assertThat(s.linking()).isEqualTo(LinkSettings.DEFAULTS);
        });
    }

    @Test void validOverridesBindAndAreAudited() {
        bare.withPropertyValues("dita.deletion.early-window=10m", "dita.deletion.admins-may-forget-others=true",
                        "dita.deletion.dita-replies=REPORT_AFFECTED", "dita.memory.history-access=SINCE_REQUESTER_JOINED",
                        "dita.retrieval.graph.depth=1", "dita.memory.clarification.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    DitaSettings s = ctx.getBean(DitaSettings.class);
                    assertThat(s.deletion().earlyWindow()).hasMinutes(10);
                    assertThat(s.retrieval().graphDepth()).isEqualTo(1);
                    assertThat(s.clarification().enabled()).isTrue();
                    assertThat(ConfigAudit.nonDefaults(s)).contains("dita.deletion.admins-may-forget-others=true",
                            "dita.deletion.early-window=PT10M", "dita.deletion.dita-replies=REPORT_AFFECTED");
                });
    }

    void failsWith(String expectedFragment, String... props) {
        bare.withPropertyValues(props).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(fullMessage(ctx.getStartupFailure())).contains(expectedFragment);
        });
    }

    static String fullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) sb.append(x.getMessage()).append(" | ");
        return sb.toString();
    }

    @Test void earlyWindowCannotBeShortenedOrExceed24h() {
        failsWith("earlyWindow must be in [5m, 24h]", "dita.deletion.early-window=4m");
        failsWith("earlyWindow must be in [5m, 24h]", "dita.deletion.early-window=25h");
    }

    @Test void groupRetentionCannotBeExtended() {
        failsWith("groupRemovalRetention must be in [1d, 21d]", "dita.deletion.group-removal-retention=22d");
        failsWith("groupRemovalRetention must be in [1d, 21d]", "dita.deletion.group-removal-retention=0d");
    }

    @Test void unimplementedFeaturesFailStartup() {
        failsWith("dense retrieval requires the POSTGRES/Vespa runtime", "dita.retrieval.dense.enabled=true");
        // TASK-054 G1: semantic raw-message retrieval cannot be "enabled" where there is no embedder (never a silent keyword-only mode)
        failsWith("dita.retrieval.dense.messages=true requires dita.storage.backend=POSTGRES", "dita.retrieval.dense.messages=true");
        failsWith("requires dita.muse.enabled=true", "dita.processing.tiers.t1.provider=META_MODEL_API");
        failsWith("model must be empty when provider is NONE", "dita.processing.tiers.t2.model=x");
    }

    @Test void outOfRangeAndInconsistentValuesFail() {
        failsWith("candidateThreshold", "dita.memory.linking.candidate-threshold=1.5");
        failsWith("adjudication band", "dita.processing.escalation.adjudication-low=0.6", "dita.processing.escalation.adjudication-high=0.4");
        failsWith("candidateEdgeWeight", "dita.retrieval.graph.candidate-edge-weight=0.9", "dita.retrieval.graph.explicit-edge-weight=0.8");
        failsWith("defaultK", "dita.retrieval.default-k=60", "dita.retrieval.max-k=50");
        failsWith("maxQuoteChars", "dita.memory.answers.max-quote-chars=10");
        failsWith("allowedActs", "dita.coordination.approval.allowed-acts=");
        failsWith("min-candidate-score", "dita.memory.clarification.enabled=true", "dita.memory.clarification.min-candidate-score=0.2");
        failsWith("graph expansion needs a seed", "dita.retrieval.sparse.enabled=false");
    }

    @Test void securityInvariantsCannotBeConfigured() {
        failsWith("dita.security", "dita.security.group-isolation=false");
        failsWith("dita.deletion.restore-deleted-text", "dita.deletion.restore-deleted-text=true");
        failsWith("dita.memory.cross-group-search", "dita.memory.cross-group-search=true");
    }

    @Test void telegramRequiresAllowListAndCredentialsFromEnvironment() {
        failsWith("allowed-chat-ids must list the synthetic demo chats", "dita.telegram.enabled=true");
        failsWith("private chats are never served", "dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=42");
        failsWith("allowed-author-ids must list the synthetic demo authors", "dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001");
        failsWith("allowed-author-ids must be Telegram user ids", "dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001",
                "dita.telegram.allowed-author-ids=-5");
        failsWith("must hold a Telegram bot token", "dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001", "dita.telegram.allowed-author-ids=1001");

        bare.withBean("ditaEnv", Function.class, () -> env(Map.of("TELEGRAM_BOT_TOKEN", FAKE_TOKEN, "DITA_CALLBACK_SECRET", "short")))
                .withPropertyValues("dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001", "dita.telegram.allowed-author-ids=1001")
                .run(ctx -> assertThat(fullMessage(ctx.getStartupFailure())).contains("at least 32 characters"));

        bare.withBean("ditaEnv", Function.class, () -> env(Map.of("TELEGRAM_BOT_TOKEN", FAKE_TOKEN, "DITA_CALLBACK_SECRET", FAKE_SECRET)))
                .withPropertyValues("dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001,-1002", "dita.telegram.allowed-author-ids=1001,1002")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    DitaSettings s = ctx.getBean(DitaSettings.class);
                    assertThat(s.telegram().botToken()).isEqualTo(FAKE_TOKEN);
                    assertThat(s.telegram().toString()).doesNotContain(FAKE_TOKEN);
                    assertThat(s.toString()).doesNotContain(FAKE_TOKEN);
                    assertThat(ctx).hasSingleBean(app.dita.telegram.TelegramAdapter.class);
                });
    }

    @Test void startupFailureMessagesNeverEchoSecrets() {
        bare.withBean("ditaEnv", Function.class, () -> env(Map.of("TELEGRAM_BOT_TOKEN", "not-a-token-but-secret-xyz", "DITA_CALLBACK_SECRET", FAKE_SECRET)))
                .withPropertyValues("dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001", "dita.telegram.allowed-author-ids=1001")
                .run(ctx -> assertThat(fullMessage(ctx.getStartupFailure())).doesNotContain("not-a-token-but-secret-xyz"));
    }

    @Test void museRequiresDocumentedEndpointModelAndKeyFromEnvironment() {
        failsWith("requires dita.muse.chat-completions-url and dita.muse.model", "dita.muse.enabled=true", "dita.muse.model=");
        failsWith("must hold the Meta Model API key", "dita.muse.enabled=true");
        failsWith("Muse endpoint must be https", "dita.muse.enabled=true", "dita.muse.chat-completions-url=http://api.meta.ai/v1/chat/completions");
        failsWith("reasoning-effort", "dita.muse.enabled=true", "dita.muse.reasoning-effort=NONE");   // "none" unsupported by Muse Spark
        failsWith("host must be api.meta.ai", "dita.muse.enabled=true", "dita.muse.chat-completions-url=https://api.meta.ai.evil.example/v1/chat/completions");
        bare.withBean("ditaEnv", Function.class, () -> env(Map.of("MODEL_API_KEY", "synthetic-key-value-123")))
                .withPropertyValues("dita.muse.enabled=true", "dita.processing.tiers.t1.provider=META_MODEL_API")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(app.dita.muse.MuseKnowledgeExtractor.class);
                    assertThat(ctx.getBean(DitaSettings.class).toString()).doesNotContain("synthetic-key-value-123");
                    assertThat(ctx.getBean(DitaSettings.class).muse().reasoningEffort()).isEqualTo(app.dita.muse.MuseSettings.ReasoningEffort.LOW);
                });
        bare.withBean("ditaEnv", Function.class, () -> env(Map.of("MODEL_API_KEY", "synthetic-key-value-123")))
                .withPropertyValues("dita.muse.enabled=true", "dita.processing.tiers.t2.provider=META_MODEL_API", "dita.processing.tiers.t2.model=other")
                .run(ctx -> assertThat(fullMessage(ctx.getStartupFailure())).contains("single reasoning model"));
    }

    @Test void committedYamlContainsNoCredentials() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).doesNotContainPattern("\\d{5,16}:[A-Za-z0-9_-]{30,}");
        assertThat(yml).doesNotContainIgnoringCase("password:").doesNotContainIgnoringCase("secret:");
    }

    /** TASK-040: the committed configuration names the demo chat and author, and keeps polling and Muse OFF. */
    @Test void committedYamlConfiguresTheDemoAllowListsWithPollingAndMuseOff() {
        withYaml.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var p = ctx.getBean(app.dita.app.config.DitaProperties.class);
            assertThat(p.telegram().enabled()).as("live polling not approved").isFalse();
            assertThat(p.muse().enabled()).as("Muse stays disabled").isFalse();
            assertThat(p.telegram().allowedChatIds()).containsExactly(-1001000000054L);
            assertThat(p.telegram().allowedAuthorIds()).containsExactly(1001L);
            assertThat(p.telegram().botUsername()).isEqualTo("ExampleDitaBot");
            assertThat(ctx).doesNotHaveBean(app.dita.telegram.TelegramAdapter.class);
        });
    }
}
