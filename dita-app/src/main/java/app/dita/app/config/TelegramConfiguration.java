package app.dita.app.config;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.deletion.DeletionService;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.model.GroupRegistry;
import app.dita.core.store.MessageStore;
import app.dita.telegram.CallbackSigner;
import app.dita.telegram.HttpTelegramGateway;
import app.dita.telegram.TelegramAdapter;
import app.dita.telegram.TelegramAdapterSettings;
import app.dita.telegram.TelegramGateway;
import app.dita.telegram.TelegramPoller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Active only with dita.telegram.enabled=true (credentials validated by DitaSettings at startup). */
@Configuration
@ConditionalOnProperty(prefix = "dita.telegram", name = "enabled", havingValue = "true")
public class TelegramConfiguration {

    /** LIVE: the Bot API. REPLAY (TASK-051): the synthetic replay file; Telegram is never contacted. */
    @Bean @ConditionalOnMissingBean
    public TelegramGateway telegramGateway(DitaSettings s, Clock clock, org.springframework.beans.factory.ObjectProvider<app.dita.core.knowledge.ExtractionQueue> queues,
                                           org.springframework.beans.factory.ObjectProvider<app.dita.store.postgres.PostgresOutboxRelay> relay,
                                           DitaProperties p) {
        var tg = s.raw().telegram();
        if (tg.mode() != DitaProperties.TelegramMode.REPLAY) {
            if (!tg.replay().transcriptFile().isBlank() || tg.replay().transcriptEcho()) {   // also refused by DitaSettings
                throw new DitaConfigurationException("dita.telegram.replay.transcript-* is only valid with dita.telegram.mode=REPLAY");
            }
            return new HttpTelegramGateway(s.telegram().botToken());
        }
        var queue = queues.getIfAvailable();
        if (queue == null) throw new DitaConfigurationException("dita.telegram.mode=REPLAY requires the extraction pipeline (no ExtractionQueue)");
        java.util.function.BooleanSupplier idle = () -> pipelineIdle(tg.allowedChatIds(), queue, relay.getIfAvailable(), p);
        var rp = tg.replay();
        if (!rp.transcriptFile().isBlank()) refuseOverlap(java.nio.file.Path.of(rp.transcriptFile()), rp.file(), s.raw().muse().recordingFile());
        return app.dita.telegram.ReplayTelegramGateway.fromFile(java.nio.file.Path.of(rp.file()), clock, idle, rp.barrierTimeout(),
                rp.transcriptFile().isBlank() ? null : java.nio.file.Path.of(rp.transcriptFile()), rp.transcriptEcho() ? System.out : null);
    }

    /** Natasha L-1: the transcript (or its .txt copy) must never be the replay or recording fixture, which it would truncate. */
    static void refuseOverlap(java.nio.file.Path transcript, String... fixtures) {
        var outs = java.util.List.of(transcript, app.dita.telegram.ReplayTelegramGateway.readableTranscriptPath(transcript));
        for (String f : fixtures) {
            if (f == null || f.isBlank()) continue;
            for (var o : outs) {
                if (canonical(o).equals(canonical(java.nio.file.Path.of(f)))) {
                    throw new DitaConfigurationException("dita.telegram.replay.transcript-file must not overwrite a replay or recording fixture");
                }
            }
        }
    }

    private static java.nio.file.Path canonical(java.nio.file.Path p) {
        try { return p.toRealPath(); } catch (java.io.IOException e) { return p.toAbsolutePath().normalize(); }
    }

    /** Replay barrier: no approved or running extraction for the replayed chats, and (POSTGRES) every projection caught up. */
    static boolean pipelineIdle(java.util.Set<Long> chats, app.dita.core.knowledge.ExtractionQueue queue,
                                app.dita.store.postgres.PostgresOutboxRelay relay, DitaProperties p) {
        for (long chat : chats) {                         // only this run's replay groups (Natasha re-review item 1)
            var scope = new app.dita.core.model.GroupScope("tgr" + Math.abs(chat));
            if (!queue.batches(scope, app.dita.core.knowledge.ExtractionBatch.Status.APPROVED).isEmpty()) return false;
            if (!queue.batches(scope, app.dita.core.knowledge.ExtractionBatch.Status.RUNNING).isEmpty()) return false;
        }
        if (relay == null) return true;
        return relay.lag(PostgresRuntimeConfiguration.neo4jConsumer(p)) == 0 && relay.lag(PostgresRuntimeConfiguration.vespaConsumer(p)) == 0;
    }

    @Bean
    public TelegramAdapter telegramAdapter(DitaSettings s, MessageStore store, IngestionPipeline ingestion, DeletionService deletions,
                                           QueryService queries, ClarificationGate gate, TelegramGateway gw, Clock clock,
                                           GroupRegistry groups,
                                           org.springframework.beans.factory.ObjectProvider<app.dita.core.knowledge.ExtractionService> extractions,
                                           org.springframework.beans.factory.ObjectProvider<app.dita.app.trial.SyntheticTrialController> trialController,
                                           org.springframework.beans.factory.ObjectProvider<app.dita.app.trial.TrialEvidence> trialEvidence,
                                           @org.springframework.beans.factory.annotation.Qualifier("trialWorker")
                                           org.springframework.beans.factory.ObjectProvider<java.util.concurrent.Executor> trialWorker) {
        var p = s.raw();
        var settings = new TelegramAdapterSettings(p.telegram().allowedChatIds(), p.telegram().botUsername(),
                p.coordination().enabled(), p.coordination().tasks().maxPerPlan(), p.coordination().approval().allowedActs(),
                s.deletion().groupRemovalRetention(), s.deletion().adminsMayForgetOthers(),
                p.telegram().allowedAuthorIds(),
                clock.instant());                                    // TASK-040: never process updates from before this start
        TelegramAdapter.ReplayOptions replay = null;
        if (p.telegram().mode() == DitaProperties.TelegramMode.REPLAY) {
            if (!(gw instanceof app.dita.telegram.ReplayTelegramGateway rg)) {   // Natasha M-2: label follows the gateway
                throw new DitaConfigurationException("dita.telegram.mode=REPLAY requires the replay gateway; refusing to label other updates SYNTHETIC");
            }
            var extraction = extractions.getIfAvailable();
            if (extraction == null) throw new DitaConfigurationException("dita.telegram.mode=REPLAY requires the extraction pipeline (no ExtractionService)");
            // TASK-051: the runtime extraction trigger. enqueue() itself refuses anything but SYNTHETIC messages (N-8).
            replay = new TelegramAdapter.ReplayOptions(p.telegram().replay().extractionApproverIds(), rg.approvalTag(), new TelegramAdapter.ExtractionHooks() {
                @Override public void ingested(app.dita.core.model.GroupScope scope, app.dita.core.model.Message m) { extraction.enqueue(scope, m); }
                @Override public int approvePending(app.dita.core.model.GroupScope scope, String ref) {
                    int n = 0;
                    for (var b : extraction.collect(scope)) { extraction.approve(scope, b.id(), ref); n++; }
                    return n;
                }
            });
        }
        // TASK-054: controlled synthetic trial on LIVE Telegram (validated in DitaSettings; never together with REPLAY).
        TelegramAdapter.SyntheticTrialOptions trial = null;
        if (p.telegram().syntheticTrial().enabled()) {
            if (replay != null || gw instanceof app.dita.telegram.ReplayTelegramGateway)
                throw new DitaConfigurationException("the synthetic trial runs only on live Telegram, never through the replay gateway");
            var controller = trialController.getIfAvailable();
            var ev = trialEvidence.getIfAvailable();
            if (controller == null || ev == null) throw new DitaConfigurationException("synthetic trial enabled but its controller is not configured");
            var st = p.telegram().syntheticTrial();
            // /ask runs on one trial worker thread so approval button presses keep arriving while it waits (tests may
            // supply an inline executor bean "trialWorker")
            java.util.concurrent.Executor worker = trialWorker.getIfAvailable(() -> java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> { Thread t = new Thread(r, "dita-trial-ask"); t.setDaemon(true); return t; }));
            trial = new TelegramAdapter.SyntheticTrialOptions(st.approverIds(), ev.runTag(), controller, !st.sessionDeclaration().isEmpty(), worker);
        }
        return new TelegramAdapter(settings, store, ingestion, deletions, queries, gate, gw,
                new CallbackSigner(s.telegram().callbackSecret()), clock, groups, replay, trial);
    }

    @Bean
    public SmartLifecycle telegramPolling(DitaSettings s, TelegramGateway gw, TelegramAdapter adapter) {
        TelegramPoller poller = new TelegramPoller(gw, adapter, (int) s.raw().telegram().pollTimeout().toSeconds(), s.raw().telegram().pollLimit());
        return new SmartLifecycle() {
            private Thread t;
            public void start() { t = Thread.ofVirtual().name("telegram-poller").start(poller); }
            public void stop() { poller.stop(); if (t != null) t.interrupt(); }
            public boolean isRunning() { return t != null && t.isAlive(); }
        };
    }
}
