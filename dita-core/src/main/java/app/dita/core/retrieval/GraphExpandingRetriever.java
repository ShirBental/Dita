package app.dita.core.retrieval;

import app.dita.core.model.GroupScope;
import app.dita.core.model.MessageLink;
import app.dita.core.store.MessageStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphRAG-style contextual expansion over the conversation projection (Postgres links now, Neo4j later).
 * Explicit edges propagate more weight than candidate edges; inferred context is flagged as such.
 */
public final class GraphExpandingRetriever implements Retriever {
    private final double EXPLICIT_WEIGHT, CANDIDATE_WEIGHT;
    private final Retriever seed;
    private final MessageStore store;
    private final int hops;

    public GraphExpandingRetriever(Retriever seed, MessageStore store, int hops) { this(seed, store, hops, 0.8, 0.5); }

    public GraphExpandingRetriever(Retriever seed, MessageStore store, int hops, double explicitWeight, double candidateWeight) {
        this.seed = seed; this.store = store; this.hops = hops;
        this.EXPLICIT_WEIGHT = explicitWeight; this.CANDIDATE_WEIGHT = candidateWeight;
    }

    @Override
    public List<Hit> retrieve(GroupScope scope, String query, int k) {
        Map<String, Hit> best = new HashMap<>();
        List<Hit> frontier = seed.retrieve(scope, query, k);
        frontier.forEach(h -> best.put(h.messageId(), h));
        List<MessageLink> links = store.links(scope).stream()
                .filter(l -> l.status() != MessageLink.Status.REJECTED).toList();
        for (int hop = 0; hop < hops; hop++) {
            List<Hit> next = new ArrayList<>();
            for (Hit h : frontier) {
                for (MessageLink l : links) {
                    String other = l.fromId().equals(h.messageId()) ? l.toId()
                            : l.toId().equals(h.messageId()) ? l.fromId() : null;
                    if (other == null || store.find(scope, other).map(m -> m.deleted()).orElse(true)) continue;
                    double w = l.isExplicit() ? EXPLICIT_WEIGHT : CANDIDATE_WEIGHT * l.score();
                    boolean inferred = h.inferredContext() || !l.isExplicit();
                    Hit cand = new Hit(other, h.score() * w, "graph:" + l.kind(), inferred);
                    Hit prev = best.get(other);
                    if (prev == null || prev.score() < cand.score()) {
                        best.put(other, prev != null && !prev.inferredContext() ? new Hit(other, cand.score(), prev.via(), false) : cand);
                        next.add(cand);
                    }
                }
            }
            frontier = next;
        }
        return best.values().stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).limit(k).toList();
    }
}
