package app.dita.app.config;

import app.dita.core.events.EventPublisher;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.knowledge.KnowledgeProposer;
import app.dita.core.store.MessageStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Hybrid extraction pipeline wiring. The queue comes from the storage backend (in-memory, or PostgreSQL when
 * dita.storage.backend=POSTGRES). Without Muse the proposer is {@link KnowledgeProposer#NONE}: approved batches fail
 * explicitly instead of silently producing nothing. The worker only processes batches that were APPROVED.
 */
@Configuration
public class KnowledgeConfiguration {
    public static final int BATCH_SIZE = 20;

    @Bean public ExtractionService extractionService(MessageStore store, ExtractionQueue queue, ObjectProvider<KnowledgeProposer> proposer,
                                                     EventPublisher publisher, Clock clock, DitaProperties p) {
        return new ExtractionService(store, queue, proposer.getIfAvailable(() -> KnowledgeProposer.NONE), publisher, clock,
                p.extraction().batchSize(), p.extraction().approvalTtl());
    }

    /** Off (dita.extraction.worker-enabled=false) for controlled runs that process only their own batch explicitly. */
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "dita.extraction", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
    public ExtractionWorker extractionWorker(ExtractionService service, DitaSettings s, ObjectProvider<app.dita.muse.MuseTransport> transport) {
        // "live" is decided by the transport bean actually in use, not only by the property (Natasha N-1)
        boolean recorded = transport.getIfAvailable() instanceof app.dita.muse.RecordedMuseTransport;
        return new ExtractionWorker(service, s.raw().processing().dispatch().pollInterval(), eligibility(s.raw(), recorded));
    }

    /**
     * TASK-051 (Natasha M-3), on a shared database: a REPLAY process touches only its own replay groups (tgr…). A process
     * whose Muse transport is HTTP never sends a batch approved by a replay /extract: that approval was never consent to a
     * live call.
     */
    public static java.util.function.Predicate<app.dita.core.knowledge.ExtractionBatch> eligibility(DitaProperties p) {
        return eligibility(p, p.muse().transport() == DitaProperties.MuseTransportMode.RECORDED);
    }

    /**
     * {@code recordedTransport}: the MuseTransport bean in use is the recorded one. Any other transport counts as live.
     * A REPLAY process handles only the replay groups of its own allowed chats, and only batches approved by a replay
     * /extract (Natasha N-2).
     */
    /** TASK-054: a batch of the controlled Telegram synthetic trial (approval reference or group namespace). */
    public static boolean trialBatch(app.dita.core.knowledge.ExtractionBatch b) {
        return (b.approvalRef() != null && b.approvalRef().startsWith(app.dita.telegram.TelegramAdapter.TRIAL_APPROVAL_PREFIX))
                || b.groupId().startsWith(app.dita.telegram.TelegramAdapter.TRIAL_PREFIX);
    }

    public static java.util.function.Predicate<app.dita.core.knowledge.ExtractionBatch> eligibility(DitaProperties p, boolean recordedTransport) {
        boolean replay = p.telegram().enabled() && p.telegram().mode() == DitaProperties.TelegramMode.REPLAY;
        boolean live = p.muse().enabled() && (p.muse().transport() == DitaProperties.MuseTransportMode.HTTP || !recordedTransport);
        java.util.Set<String> ownReplayGroups = new java.util.HashSet<>();
        for (long chat : p.telegram().allowedChatIds()) ownReplayGroups.add("tgr" + Math.abs(chat));
        return b -> {
            // TASK-054: synthetic-trial batches are processed ONLY synchronously by the trial run that approved them, behind
            // its per-call terminal approval. No worker (in this or any other process on a shared database) may take them.
            if (trialBatch(b)) return false;
            boolean replayApproval = b.approvalRef() != null && b.approvalRef().startsWith("telegram-replay:");
            if (live && (replayApproval || b.groupId().startsWith("tgr"))) return false;   // replay groups never go live
            return !replay || (replayApproval && ownReplayGroups.contains(b.groupId()));
        };
    }
}
