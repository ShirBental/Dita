package app.dita.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** TASK-051: fail-closed startup rules for synthetic replay and recorded Muse replies. */
class ReplayConfigurationTest {
    final ConfigurationLoadingTest base = new ConfigurationLoadingTest();
    static final String[] TELEGRAM = {"dita.telegram.enabled=true", "dita.telegram.allowed-chat-ids=-1001000000052",
            "dita.telegram.allowed-author-ids=1001,1009"};

    String[] with(String[] a, String... b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test void replayNeedsAReadableFileAndAllowListedApprovers() throws Exception {
        base.failsWith("dita.telegram.replay.file", with(TELEGRAM, "dita.telegram.mode=REPLAY", "dita.telegram.replay.extraction-approver-ids=1009"));
        Path f = Files.createDirectories(Path.of("target")).resolve("replay-config-test.jsonl");
        Files.writeString(f, "{\"replay\":\"await-idle\"}\n");
        base.failsWith("extraction-approver-ids", with(TELEGRAM, "dita.telegram.mode=REPLAY", "dita.telegram.replay.file=" + f));
        base.failsWith("extraction-approver-ids", with(TELEGRAM, "dita.telegram.mode=REPLAY", "dita.telegram.replay.file=" + f,
                "dita.telegram.replay.extraction-approver-ids=4242"));
    }

    /** Natasha H-1: a replay can never reach live Muse, even with a key in the environment. */
    @Test void replayRefusesTheHttpMuseTransport() throws Exception {
        Path f = Files.createDirectories(Path.of("target")).resolve("replay-config-h1.jsonl");
        Files.writeString(f, app.dita.telegram.ReplayTelegramGateway.HEADER + "\n");
        base.bare.withBean("ditaEnv", java.util.function.Function.class, () -> (java.util.function.Function<String, String>) n -> n.equals("MODEL_API_KEY") ? "synthetic-not-a-key" : null)
                .withPropertyValues(with(TELEGRAM, "dita.telegram.mode=REPLAY", "dita.telegram.replay.file=" + f,
                        "dita.telegram.replay.extraction-approver-ids=1009", "dita.muse.enabled=true"))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ConfigurationLoadingTest.fullMessage(ctx.getStartupFailure())).contains("requires dita.muse.transport=RECORDED");
                });
    }

    /** Natasha M-3: an HTTP-transport worker never sends a replay-approved batch; a replay worker only touches tgr… groups. */
    @Test void workerEligibilitySeparatesReplayApprovalsFromLiveCalls() throws Exception {
        var t = java.time.Instant.parse("2026-11-02T12:00:00Z");
        var replayBatch = new app.dita.core.knowledge.ExtractionBatch("b1", "tgr1001000000052", java.util.List.of(new app.dita.core.knowledge.ExtractionBatch.Target("m1", null)),
                app.dita.core.knowledge.ExtractionBatch.Status.APPROVED, 0, "telegram-replay:abc-1:1001:m5:by1009", null, t, t);
        var otherBatch = new app.dita.core.knowledge.ExtractionBatch("b2", "lab-1", java.util.List.of(new app.dita.core.knowledge.ExtractionBatch.Target("m1", null)),
                app.dita.core.knowledge.ExtractionBatch.Status.APPROVED, 0, "operator-approval-7", null, t, t);
        base.bare.withBean("ditaEnv", java.util.function.Function.class, () -> (java.util.function.Function<String, String>) n -> n.equals("MODEL_API_KEY") ? "synthetic-not-a-key" : null)
                .withPropertyValues("dita.muse.enabled=true").run(ctx -> {
                    var p = ctx.getBean(app.dita.app.config.DitaProperties.class);
                    var live = app.dita.app.config.KnowledgeConfiguration.eligibility(p);
                    assertThat(live.test(replayBatch)).as("HTTP worker refuses a replay approval").isFalse();
                    assertThat(live.test(otherBatch)).isTrue();
                    var tgrOtherApproval = new app.dita.core.knowledge.ExtractionBatch("b3", "tgr1001", replayBatch.targets(),
                            app.dita.core.knowledge.ExtractionBatch.Status.APPROVED, 0, "operator-approval-8", null, t, t);
                    assertThat(live.test(tgrOtherApproval)).as("a replay group never goes live, whatever approved it").isFalse();
                });
        Path f = Files.createDirectories(Path.of("target")).resolve("replay-config-m3.jsonl");
        Path rec = Files.createDirectories(Path.of("target")).resolve("recording-config-m3.json");
        try { Files.writeString(f, app.dita.telegram.ReplayTelegramGateway.HEADER + "\n");
              Files.writeString(rec, "{\"recordings\":[{\"requires\":[],\"content\":{\"items\":[]}}]}"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        base.bare.withUserConfiguration(app.dita.app.config.KnowledgeConfiguration.class)
                .withPropertyValues(with(TELEGRAM, "dita.telegram.mode=REPLAY", "dita.telegram.replay.file=" + f,
                        "dita.telegram.replay.extraction-approver-ids=1009", "dita.muse.enabled=true", "dita.muse.transport=RECORDED",
                        "dita.muse.recording-file=" + rec))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var replay = app.dita.app.config.KnowledgeConfiguration.eligibility(ctx.getBean(app.dita.app.config.DitaProperties.class));
                    assertThat(replay.test(replayBatch)).as("a replay process handles its own replay groups").isTrue();
                    assertThat(replay.test(otherBatch)).as("a replay process never touches other groups' batches (M-3)").isFalse();
                    var foreignReplayGroup = new app.dita.core.knowledge.ExtractionBatch("b4", "tgr1009", replayBatch.targets(),
                            app.dita.core.knowledge.ExtractionBatch.Status.APPROVED, 0, replayBatch.approvalRef(), null, t, t);
                    assertThat(replay.test(foreignReplayGroup)).as("another run's replay group is not ours (N-2)").isFalse();
                    var ownGroupOtherApproval = new app.dita.core.knowledge.ExtractionBatch("b5", "tgr1001000000052", replayBatch.targets(),
                            app.dita.core.knowledge.ExtractionBatch.Status.APPROVED, 0, "operator-approval-9", null, t, t);
                    assertThat(replay.test(ownGroupOtherApproval)).as("only a replay /extract approval runs in replay (N-2)").isFalse();
                    assertThat(app.dita.app.config.KnowledgeConfiguration.eligibility(ctx.getBean(app.dita.app.config.DitaProperties.class), false)
                            .test(replayBatch)).as("a non-recorded transport bean counts as live (N-1)").isFalse();
                });
    }

    /** Natasha M-2: REPLAY never labels updates from any gateway other than the replay gateway. */
    @Test void replayRefusesANonReplayGateway() throws Exception {
        Path f = Files.createDirectories(Path.of("target")).resolve("replay-config-m2.jsonl");
        Files.writeString(f, app.dita.telegram.ReplayTelegramGateway.HEADER + "\n");
        base.bare.withBean("telegramGateway", app.dita.telegram.TelegramGateway.class, () -> new app.dita.telegram.TelegramGateway() {
                    public java.util.List<com.fasterxml.jackson.databind.JsonNode> getUpdates(long o, int t, int l) { return java.util.List.of(); }
                    public long sendMessage(long c, String x, Long r, java.util.List<Button> b) { return 1; }
                    public void answerCallbackQuery(String id, String x) { }
                    public java.util.Set<Long> chatAdministrators(long c) { return java.util.Set.of(); }
                })
                .withUserConfiguration(app.dita.app.config.KnowledgeConfiguration.class)
                .withPropertyValues(with(TELEGRAM, "dita.telegram.mode=REPLAY", "dita.telegram.replay.file=" + f,
                        "dita.telegram.replay.extraction-approver-ids=1009"))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ConfigurationLoadingTest.fullMessage(ctx.getStartupFailure())).contains("requires the replay gateway");
                });
    }

    /** Natasha N-1: RECORDED never runs with any other transport bean. */
    @Test void recordedModeRefusesAnyOtherTransportBean() throws Exception {
        Path rec = Files.createDirectories(Path.of("target")).resolve("recording-config-n1.json");
        Files.writeString(rec, "{\"recordings\":[{\"requires\":[],\"content\":{\"items\":[]}}]}");
        base.bare.withBean("museTransport", app.dita.muse.MuseTransport.class,
                        () -> (app.dita.muse.MuseTransport) (u, h, b, t) -> new app.dita.muse.MuseTransport.Reply(599, "{}", java.util.Map.of()))
                .withPropertyValues("dita.muse.enabled=true", "dita.muse.transport=RECORDED", "dita.muse.recording-file=" + rec)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ConfigurationLoadingTest.fullMessage(ctx.getStartupFailure())).contains("not the recorded transport");
                });
    }

    @Test void replaySettingsAreRefusedOnLiveTelegram() {
        base.failsWith("only valid with dita.telegram.mode=REPLAY", with(TELEGRAM, "dita.telegram.replay.extraction-approver-ids=1009"));
    }

    /** TASK-051 observability: reply transcripts and terminal echo exist for synthetic replay only, never live Telegram. */
    @Test void replyTranscriptIsRefusedOnLiveTelegram() {
        base.failsWith("only valid with dita.telegram.mode=REPLAY", with(TELEGRAM, "dita.telegram.replay.transcript-file=target/live-transcript.jsonl"));
        base.failsWith("only valid with dita.telegram.mode=REPLAY", with(TELEGRAM, "dita.telegram.replay.transcript-echo=true"));
        assertThat(Path.of("target/live-transcript.jsonl")).doesNotExist();
    }

    @Test void recordedMuseNeedsARecordingAndNeverTheKey() throws Exception {
        base.failsWith("dita.muse.recording-file", "dita.muse.enabled=true", "dita.muse.transport=RECORDED");
        Path rec = Files.createDirectories(Path.of("target")).resolve("recording-config-test.json");
        Files.writeString(rec, "{\"recordings\":[{\"requires\":[],\"content\":{\"items\":[]}}]}");
        // no MODEL_API_KEY in the environment, yet startup succeeds: the key is never read in RECORDED mode
        base.bare.withPropertyValues("dita.muse.enabled=true", "dita.muse.transport=RECORDED", "dita.muse.recording-file=" + rec)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(app.dita.muse.MuseTransport.class)).isInstanceOf(app.dita.muse.RecordedMuseTransport.class);
                });
    }
}
