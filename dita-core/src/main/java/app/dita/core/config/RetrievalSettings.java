package app.dita.core.config;

/** Recall-oriented hybrid retrieval. Dense retrieval is not implemented yet (S6) and cannot be enabled. */
public record RetrievalSettings(boolean sparseEnabled, double bm25K1, double bm25B,
                                boolean graphEnabled, int graphDepth, double explicitEdgeWeight, double candidateEdgeWeight,
                                double rrfK, int candidateMultiplier, double sparseWeight, double graphWeight,
                                int defaultK, int maxK) {
    public static final RetrievalSettings DEFAULTS =
            new RetrievalSettings(true, 1.2, 0.75, true, 2, 0.8, 0.5, 60, 3, 1.0, 1.0, 8, 50);

    public RetrievalSettings {
        LinkSettings.require(sparseEnabled || graphEnabled, "at least one retrieval arm must be enabled");
        LinkSettings.require(!graphEnabled || sparseEnabled, "graph expansion needs a seed retriever (sparse)");
        LinkSettings.require(bm25K1 > 0 && bm25K1 <= 3, "bm25K1 must be in (0,3]");
        LinkSettings.require(bm25B >= 0 && bm25B <= 1, "bm25B must be in [0,1]");
        LinkSettings.require(graphDepth >= 0 && graphDepth <= 4, "graphDepth must be in [0,4]");
        LinkSettings.require(explicitEdgeWeight > 0 && explicitEdgeWeight <= 1, "explicitEdgeWeight must be in (0,1]");
        LinkSettings.require(candidateEdgeWeight >= 0 && candidateEdgeWeight <= explicitEdgeWeight,
                "candidateEdgeWeight must be in [0, explicitEdgeWeight]: inferred edges never outweigh explicit ones");
        LinkSettings.require(rrfK > 0, "rrfK must be > 0");
        LinkSettings.require(candidateMultiplier >= 1 && candidateMultiplier <= 20, "candidateMultiplier must be in [1,20]");
        LinkSettings.require(sparseWeight >= 0 && graphWeight >= 0 && sparseWeight + graphWeight > 0, "fusion weights must be >= 0, not both 0");
        LinkSettings.require(defaultK >= 1 && defaultK <= maxK && maxK <= 200, "require 1 <= defaultK <= maxK <= 200");
    }

    public int clampK(int k) { return Math.max(1, Math.min(k, maxK)); }
}
