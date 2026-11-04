package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DEC-032: an index (Vespa) proposes candidates; Dita ranks them with its own tokenizer (Hebrew prefix stripping, English
 * plural stripping) over the live verbatim text ({@link LexicalKnowledgeRetriever}). Measured reason: Vespa's knowledge
 * field has stemming off (DEC-028), so "prefer" ≠ "prefers" and "activity" ≠ "activities"; its ranking chose the wrong
 * claim on the real stack (TASK-044 Q12, Q6) where Dita's tokenizer did not. Candidates = index hits ∪ Dita's own hits;
 * order = Dita's score, index-only candidates last (in index order).
 */
public final class RerankingKnowledgeRetriever implements KnowledgeRetriever {
    private final KnowledgeRetriever index;
    private final LexicalKnowledgeRetriever ranker;

    public RerankingKnowledgeRetriever(KnowledgeRetriever index, LexicalKnowledgeRetriever ranker) { this.index = index; this.ranker = ranker; }

    @Override public String name() { return index.name() + "+dita-rerank"; }

    @Override public List<Hit> retrieve(GroupScope scope, String question, int k) {
        Set<String> candidates = new LinkedHashSet<>();
        index.retrieve(scope, question, Math.max(k * 2, 20)).forEach(h -> candidates.add(h.knowledgeId()));
        Map<String, Double> score = new HashMap<>();
        for (Hit h : ranker.retrieve(scope, question, Math.max(k * 2, 20))) { score.put(h.knowledgeId(), h.score()); candidates.add(h.knowledgeId()); }
        List<String> indexOrder = new ArrayList<>(candidates);
        List<Hit> out = new ArrayList<>();
        for (String id : candidates) out.add(new Hit(id, score.getOrDefault(id, 0.0)));
        out.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(h -> indexOrder.indexOf(h.knowledgeId())));
        return out.subList(0, Math.min(k, out.size()));
    }
}
