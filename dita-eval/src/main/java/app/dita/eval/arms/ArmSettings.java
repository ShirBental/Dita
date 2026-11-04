package app.dita.eval.arms;

import app.dita.core.config.RetrievalSettings;
import app.dita.eval.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pre-registered Stage 0 parameters. B-weak and C share k, the per-retriever candidate depth (k x candidateMultiplier),
 * the RRF constant and the BM25 parameters. Only B gets the +/-2 neighbour window, per DESIGN §1.
 */
public record ArmSettings(int tCtxChars, int k, int neighbourWindowB, RetrievalSettings cToday, RetrievalSettings cMinusGraph) {
    public static final String HISTORY_ACCESS = "FULL_GROUP_HISTORY";

    public static final ArmSettings STAGE0 = new ArmSettings(4000, RetrievalSettings.DEFAULTS.defaultK(), 2,
            RetrievalSettings.DEFAULTS, withoutGraph(RetrievalSettings.DEFAULTS));

    public ArmSettings {
        if (tCtxChars < 200) throw new IllegalArgumentException("T_ctx too small");
        if (k < 1 || k > cToday.maxK()) throw new IllegalArgumentException("k out of range");
        if (cMinusGraph.graphEnabled()) throw new IllegalArgumentException("C-graph-off must disable the graph");
    }

    public static RetrievalSettings withoutGraph(RetrievalSettings s) {
        return new RetrievalSettings(s.sparseEnabled(), s.bm25K1(), s.bm25B(), false, s.graphDepth(), s.explicitEdgeWeight(),
                s.candidateEdgeWeight(), s.rrfK(), s.candidateMultiplier(), s.sparseWeight(), s.graphWeight(), s.defaultK(), s.maxK());
    }

    public int candidateDepth() { return k * cToday.candidateMultiplier(); }

    public ObjectNode toJson() {
        ObjectNode o = Json.object();
        o.put("t_ctx_chars", tCtxChars);
        o.put("k", k);
        o.put("candidate_depth_per_retriever", candidateDepth());
        o.put("rrf_k", cToday.rrfK());
        o.put("bm25_k1", cToday.bm25K1());
        o.put("bm25_b", cToday.bm25B());
        o.put("b_neighbour_window", neighbourWindowB);
        o.put("c_neighbour_window", 0);
        o.put("c_today_graph_depth", cToday.graphDepth());
        o.put("history_access", HISTORY_ACCESS);
        o.put("backend", StoreReplay.BACKEND);
        o.put("b_semantic_half", CharNgramRetriever.LABEL);
        return o;
    }
}
