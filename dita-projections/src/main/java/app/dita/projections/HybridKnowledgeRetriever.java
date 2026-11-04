package app.dita.projections;

import app.dita.core.knowledge.KnowledgeRetriever;
import app.dita.core.model.GroupScope;
import app.dita.core.model.IsolationViolation;
import app.dita.core.projection.ProjectionSource;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Independent sparse/dense branches, bounded weighted RRF, deduplication and content-bound semantic admission.
 * No exception text, query or conversation content is logged. Isolation errors never trigger a fallback. */
public final class HybridKnowledgeRetriever implements KnowledgeRetriever {
    private static final System.Logger LOG = System.getLogger(HybridKnowledgeRetriever.class.getName());
    private final VespaProjection vespa;
    private final ProjectionSource truth;
    private final KnowledgeRetriever sparse, fallback;
    private final double minCosine;
    private final LongAdder denseFailures = new LongAdder(), sparseFailures = new LongAdder(), calls = new LongAdder();
    public HybridKnowledgeRetriever(VespaProjection vespa, ProjectionSource truth, KnowledgeRetriever sparse,
                                    KnowledgeRetriever fallback, double minCosine) {
        if (!Double.isFinite(minCosine) || minCosine < 0 || minCosine > 1) throw new IllegalArgumentException("minCosine in [0,1]");
        this.vespa=vespa; this.truth=truth; this.sparse=sparse; this.fallback=fallback; this.minCosine=minCosine;
    }
    public record Metrics(long calls, long sparseFailures, long denseFailures) {}
    public Metrics metrics() { return new Metrics(calls.sum(), sparseFailures.sum(), denseFailures.sum()); }
    @Override public String name() { return "vespa-bm25+dense-rrf"; }
    @Override public List<Hit> retrieve(GroupScope scope, String question, int k) {
        if (k < 1 || k > 200) throw new IllegalArgumentException("k in [1,200]");
        calls.increment();
        int candidates = Math.min(400, Math.max(20, k * 3));
        List<Hit> lexical, dense;
        try { lexical=sparse.retrieve(scope,question,candidates); }
        catch (IsolationViolation e) { throw e; }
        catch (RuntimeException e) {
            sparseFailures.increment(); LOG.log(System.Logger.Level.WARNING,"retrieval_fallback branch=sparse reason=unavailable");
            lexical=fallback.retrieve(scope,question,candidates);
        }
        try { dense=vespa.retrieveDenseKnowledge(scope,question,candidates,minCosine,truth); }
        catch (IsolationViolation e) { throw e; }
        catch (RuntimeException e) {
            denseFailures.increment(); LOG.log(System.Logger.Level.WARNING,"retrieval_fallback branch=dense reason=unavailable");
            dense=List.of();
        }
        return fuse(lexical,dense,k);
    }
    static List<Hit> fuse(List<Hit> lexical,List<Hit> dense,int k) {
        Map<String,Double> scores=new HashMap<>(); Set<String> semantic=new HashSet<>();
        // A duplicate in one branch must not multiply its contribution.
        for (int branch=0;branch<2;branch++) {
            Set<String> seen=new HashSet<>(); int rank=0;
            for (Hit h:branch==0?lexical:dense) {
                if (!seen.add(h.knowledgeId())) continue;
                scores.merge(h.knowledgeId(),(branch==0?1.0:0.5)/(60.0 + ++rank),Double::sum);
                if (branch==1 && h.semanticAdmission()) semantic.add(h.knowledgeId());
            }
        }
        return scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).limit(k)
                .map(e->new Hit(e.getKey(),e.getValue(),semantic.contains(e.getKey()))).toList();
    }
}
