package app.dita.eval.arms;

/**
 * Runner-side arms. O-gold is not here: it reads sealed gold, so it lives with the scorer (scorer.OracleContext).
 * C-full is not here because it is not built (DESIGN §0).
 */
public enum ArmId {
    A_FULL("A-full", "full visible transcript up to as_of"),
    A_BUDGET("A-budget", "newest visible messages within T_ctx"),
    B_WEAK("B-weak control", "BM25 (LexicalRetriever) + char-n-gram hashing cosine, RRF (HybridRetriever), +/-2 neighbours, T_ctx. NOT real B: no multilingual embedder exists"),
    C_TODAY("C-today", "Retrievers.build(RetrievalSettings.DEFAULTS): BM25 + graph expansion RRF, T_ctx"),
    C_MINUS_GRAPH("C-graph-off", "C-today with graphEnabled=false (= LexicalRetriever), T_ctx"),
    /** Diagnostics (not pre-registered): separate the retriever from B's +/-2 neighbour-window packing. */
    B_WEAK_NO_WINDOW("B-weak no-window (diag)", "DIAGNOSTIC: B-weak retriever without the neighbour window"),
    C_TODAY_WINDOW("C-today +/-2 (diag)", "DIAGNOSTIC: C-today retriever with B's +/-2 neighbour window");

    public boolean diagnostic() { return this == B_WEAK_NO_WINDOW || this == C_TODAY_WINDOW; }

    public final String label, description;

    ArmId(String label, String description) { this.label = label; this.description = description; }
}
