package app.dita.core.retrieval;

import app.dita.core.model.GroupScope;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal rank fusion of several retrievers; recall-first (each contributes depth 3k). */
public final class HybridRetriever implements Retriever {
    private final double RRF_K;
    private final int multiplier;
    private final List<Retriever> parts;
    private final List<Double> weights;

    public HybridRetriever(List<Retriever> parts) { this(parts, parts.stream().map(p -> 1.0).toList(), 60.0, 3); }

    /** TASK-054 G1: when true, a fused hit's {@code via} lists EVERY channel that returned it ("+"-joined; link channels last). */
    private final boolean recordChannels;

    public HybridRetriever(List<Retriever> parts, List<Double> weights, double rrfK, int candidateMultiplier) {
        this(parts, weights, rrfK, candidateMultiplier, false);
    }

    public HybridRetriever(List<Retriever> parts, List<Double> weights, double rrfK, int candidateMultiplier, boolean recordChannels) {
        if (parts.size() != weights.size()) throw new IllegalArgumentException("one weight per retriever");
        this.parts = List.copyOf(parts); this.weights = List.copyOf(weights); this.RRF_K = rrfK; this.multiplier = candidateMultiplier;
        this.recordChannels = recordChannels;
    }

    /** The individual channels of a (possibly joined) {@code via}. */
    public static List<String> channels(String via) {
        return via == null || via.isEmpty() ? List.of() : List.of(via.split("\\+"));
    }

    static boolean linkChannel(String via) { return via.startsWith("graph:") || via.startsWith("neo4j:") || via.startsWith("context:"); }

    @Override
    public List<Hit> retrieve(GroupScope scope, String query, int k) {
        Map<String, Double> fused = new HashMap<>();
        Map<String, Hit> firstSeen = new HashMap<>();
        Map<String, java.util.Set<String>> vias = new HashMap<>();
        for (int p = 0; p < parts.size(); p++) {
            Retriever r = parts.get(p);
            double w = weights.get(p);
            if (w == 0) continue;
            List<Hit> hits = r.retrieve(scope, query, k * multiplier);
            for (int rank = 0; rank < hits.size(); rank++) {
                Hit h = hits.get(rank);
                fused.merge(h.messageId(), w / (RRF_K + rank + 1), Double::sum);
                firstSeen.merge(h.messageId(), h, (a, b) -> a.inferredContext() && !b.inferredContext() ? b : a);
                if (h.via() != null) vias.computeIfAbsent(h.messageId(), x -> new java.util.TreeSet<>()).addAll(channels(h.via()));
            }
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(k)
                .map(e -> {
                    Hit h = firstSeen.get(e.getKey());
                    if (!recordChannels) return new Hit(h.messageId(), e.getValue(), h.via(), h.inferredContext());
                    var vs = vias.getOrDefault(e.getKey(), java.util.Set.of());
                    // direct channels first, link channels last; a hit is "linked" only if NO direct channel found it
                    String joined = java.util.stream.Stream.concat(vs.stream().filter(v -> !linkChannel(v)), vs.stream().filter(HybridRetriever::linkChannel))
                            .collect(java.util.stream.Collectors.joining("+"));
                    boolean direct = vs.stream().anyMatch(v -> !linkChannel(v));
                    return new Hit(h.messageId(), e.getValue(), joined.isEmpty() ? h.via() : joined, h.inferredContext() && !direct);
                })
                .toList();
    }
}
