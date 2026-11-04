package app.dita.core.retrieval;

import app.dita.core.config.RetrievalSettings;
import app.dita.core.store.MessageStore;

import java.util.ArrayList;
import java.util.List;

/** Builds the configured retrieval stack. Every arm is group-scoped by its {@code GroupScope} argument. */
public final class Retrievers {
    private Retrievers() {}

    public static Retriever build(MessageStore store, RetrievalSettings s) {
        Retriever sparse = new LexicalRetriever(store, s.bm25K1(), s.bm25B());
        if (!s.graphEnabled() || s.graphDepth() == 0) return sparse;
        Retriever graph = new GraphExpandingRetriever(sparse, store, s.graphDepth(), s.explicitEdgeWeight(), s.candidateEdgeWeight());
        List<Retriever> parts = new ArrayList<>(List.of(sparse, graph));
        return new HybridRetriever(parts, List.of(s.sparseWeight(), s.graphWeight()), s.rrfK(), s.candidateMultiplier());
    }
}
