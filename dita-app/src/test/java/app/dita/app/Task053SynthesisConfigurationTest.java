package app.dita.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** TASK-053: answer synthesis is OFF by default, needs Muse, and never silently goes live. No network in any case. */
class Task053SynthesisConfigurationTest {
    final ConfigurationLoadingTest base = new ConfigurationLoadingTest();
    static final Function<String, String> FAKE_KEY = n -> n.equals("MODEL_API_KEY") ? "synthetic-not-a-key" : null;

    @Test void offByDefaultTheProductionQueryServiceIsDeterministic() {
        base.withYaml.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(app.dita.core.QueryService.class).synthesisEnabled()).isFalse();
            assertThat(ctx).doesNotHaveBean(app.dita.core.answer.synthesis.GroundedSynthesis.class);
            assertThat(ctx.getBean(app.dita.app.config.DitaSettings.class).raw().memory().answers().synthesis().enabled()).isFalse();
        });
    }

    @Test void enablingSynthesisWithoutMuseIsRefused() {
        base.failsWith("requires dita.muse.enabled=true", "dita.memory.answers.synthesis.enabled=true");
    }

    @Test void liveTransportNeedsAnExplicitAllowLive() {
        base.bare.withBean("ditaEnv", Function.class, () -> FAKE_KEY)
                .withPropertyValues("dita.muse.enabled=true", "dita.memory.answers.synthesis.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ConfigurationLoadingTest.fullMessage(ctx.getStartupFailure())).contains("allow-live");
                });
    }

    @Test void recordedTransportWiresSynthesisIntoTheProductionQueryService() throws Exception {
        Path rec = Files.createDirectories(Path.of("target")).resolve("task053-config-recording.json");
        Files.writeString(rec, "{\"recordings\":[{\"requires\":[\"never-matches-anything\"],\"content\":\"{}\"}]}");
        base.bare.withBean("ditaEnv", Function.class, () -> FAKE_KEY)
                .withPropertyValues("dita.muse.enabled=true", "dita.muse.transport=RECORDED", "dita.muse.recording-file=" + rec,
                        "dita.memory.answers.synthesis.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(app.dita.core.answer.synthesis.GroundedSynthesis.class);
                    assertThat(ctx.getBean(app.dita.core.QueryService.class).synthesisEnabled()).isTrue();
                });
    }
}
