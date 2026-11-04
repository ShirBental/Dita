package app.dita.eval.arms;

import app.dita.core.IngestionPipeline;
import app.dita.core.config.DeletionSettings;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.RuleLinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.InMemoryMessageStore;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replays a corpus into Dita's in-memory store exactly as production would have seen it up to {@code as_of}: messages
 * are ingested (with rule link inference) in time order and deletions are applied through {@link DeletionService} at
 * their {@code deleted_at}, so links that depended on a deleted message are invalidated the production way. Nothing
 * after {@code as_of} is replayed (no future leakage into links). The decoy sibling goes into its own partition of
 * the same store; it is never queried.
 */
public final class StoreReplay {
    public static final String BACKEND = "in-memory";
    public static final int LINK_WINDOW = 50;

    public record Replayed(InMemoryMessageStore store, GroupScope scope, GroupScope decoyScope, Instant asOf, long buildNanos) {}

    private StoreReplay() {}

    public static Replayed replay(Corpus corpus, Corpus decoyOrNull, Instant asOf) {
        long t0 = System.nanoTime();
        InMemoryMessageStore store = new InMemoryMessageStore();
        RuleLinkInferencer inferencer = new RuleLinkInferencer();
        IngestionPipeline pipeline = new IngestionPipeline(store, inferencer);
        DeletionService deletions = new DeletionService(store, inferencer, DeletionSettings.DEFAULTS, EventPublisher.NONE, LINK_WINDOW);
        GroupScope scope = new GroupScope(corpus.id());
        apply(corpus, scope, asOf, pipeline, deletions);
        GroupScope decoyScope = null;
        if (decoyOrNull != null) {
            decoyScope = new GroupScope(decoyOrNull.id());
            apply(decoyOrNull, decoyScope, asOf, pipeline, deletions);
        }
        return new Replayed(store, scope, decoyScope, asOf, System.nanoTime() - t0);
    }

    private record Ev(Instant at, int order, CorpusMessage m, boolean delete) {}

    private static void apply(Corpus c, GroupScope scope, Instant asOf, IngestionPipeline pipeline, DeletionService deletions) {
        List<Ev> events = new ArrayList<>();
        for (CorpusMessage m : c.messages()) {
            if (m.sentAt().isAfter(asOf)) continue;
            events.add(new Ev(m.sentAt(), 0, m, false));
            if (m.deletedBy(asOf)) events.add(new Ev(m.deletedAt(), 1, m, true));
        }
        events.sort(Comparator.comparing(Ev::at).thenComparingInt(Ev::order).thenComparing(e -> e.m().id()));
        for (Ev e : events) {
            CorpusMessage m = e.m();
            if (e.delete()) {
                deletions.delete(scope, m.id(), m.deletedAt(), DeletionSource.PLATFORM_EVENT);
            } else {
                pipeline.ingest(scope, new Message(m.id(), scope.groupId(), m.author(), m.sentAt(), m.replyTo(), m.text(),
                        SourceKind.SYNTHETIC, false));
            }
        }
    }
}
