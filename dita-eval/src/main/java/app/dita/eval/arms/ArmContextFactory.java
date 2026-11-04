package app.dita.eval.arms;

import app.dita.core.memory.HistoryAccess;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.HybridRetriever;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.core.retrieval.Retriever;
import app.dita.core.retrieval.Retrievers;
import app.dita.eval.context.ContextBuilder;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.runner.Prompt;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds the context block for one (arm, question). Every retrieval arm passes the FULL_GROUP_HISTORY filter. */
public final class ArmContextFactory {
    public static final String REQUESTER = "benchmark-requester";

    public record ArmContext(ArmId arm, List<String> retrievedIds, ContextBuilder.Built context) {}

    private final ArmSettings s;
    private final Map<StoreReplay.Replayed, Map<ArmId, Retriever>> retrievers = new IdentityHashMap<>();

    public ArmContextFactory(ArmSettings settings) { this.s = settings; }

    public ArmSettings settings() { return s; }

    public ArmContext build(ArmId arm, Corpus corpus, StoreReplay.Replayed replayed, Prompt p) {
        if (!replayed.scope().groupId().equals(corpus.id()) || !replayed.asOf().equals(p.asOf()))
            throw new IllegalArgumentException("store replay does not match the question's corpus/as_of");
        return switch (arm) {
            case A_FULL -> new ArmContext(arm, List.of(), ContextBuilder.full(corpus, p.asOf()));
            case A_BUDGET -> new ArmContext(arm, List.of(), ContextBuilder.newestWithin(corpus, p.asOf(), s.tCtxChars()));
            case B_WEAK, C_TODAY, C_MINUS_GRAPH, B_WEAK_NO_WINDOW, C_TODAY_WINDOW -> {
                Retriever r = retriever(baseRetrieverArm(arm), replayed);
                HistoryAccessFilter access = new HistoryAccessFilter(HistoryAccess.FULL_GROUP_HISTORY,
                        (scope, member) -> Optional.empty(), replayed.store());
                List<Hit> hits = access.filter(replayed.scope(), REQUESTER, r.retrieve(replayed.scope(), p.question(), s.k()));
                List<String> ids = hits.stream().map(Hit::messageId).toList();
                int window = arm == ArmId.B_WEAK || arm == ArmId.C_TODAY_WINDOW ? s.neighbourWindowB() : 0;
                yield new ArmContext(arm, ids, ContextBuilder.pack(corpus, p.asOf(), ids, window, s.tCtxChars()));
            }
        };
    }

    static ArmId baseRetrieverArm(ArmId arm) {
        return switch (arm) {
            case B_WEAK_NO_WINDOW -> ArmId.B_WEAK;
            case C_TODAY_WINDOW -> ArmId.C_TODAY;
            default -> arm;
        };
    }

    private Retriever retriever(ArmId arm, StoreReplay.Replayed rp) {
        return retrievers.computeIfAbsent(rp, x -> new java.util.EnumMap<>(ArmId.class)).computeIfAbsent(arm, a -> switch (a) {
            case B_WEAK -> new HybridRetriever(
                    List.of(new LexicalRetriever(rp.store(), s.cToday().bm25K1(), s.cToday().bm25B()), new CharNgramRetriever(rp.store())),
                    List.of(1.0, 1.0), s.cToday().rrfK(), s.cToday().candidateMultiplier());
            case C_TODAY -> Retrievers.build(rp.store(), s.cToday());
            case C_MINUS_GRAPH -> Retrievers.build(rp.store(), s.cMinusGraph());
            default -> throw new IllegalArgumentException("not a retrieval arm: " + a);
        });
    }
}
