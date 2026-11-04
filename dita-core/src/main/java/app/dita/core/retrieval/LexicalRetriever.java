package app.dita.core.retrieval;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;
import app.dita.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** BM25 over a single group's live messages. Reference baseline; Vespa replaces it at scale. */
public final class LexicalRetriever implements Retriever {
    private final double K1, B;
    private final MessageStore store;

    public LexicalRetriever(MessageStore store) { this(store, 1.2, 0.75); }
    public LexicalRetriever(MessageStore store, double k1, double b) { this.store = store; this.K1 = k1; this.B = b; }

    @Override
    public List<Hit> retrieve(GroupScope scope, String query, int k) {
        List<Message> docs = store.live(scope);
        if (docs.isEmpty()) return List.of();
        List<List<String>> toks = docs.stream().map(d -> Tokenizer.tokens(d.text())).toList();
        double avg = toks.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Integer> df = new HashMap<>();
        toks.forEach(t -> Set.copyOf(t).forEach(w -> df.merge(w, 1, Integer::sum)));
        Set<String> q = Tokenizer.tokenSet(query);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Integer> tf = new HashMap<>();
            toks.get(i).forEach(w -> tf.merge(w, 1, Integer::sum));
            double s = 0;
            for (String term : q) {
                int f = tf.getOrDefault(term, 0);
                if (f == 0) continue;
                double idf = Math.log(1 + (docs.size() - df.get(term) + 0.5) / (df.get(term) + 0.5));
                s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * toks.get(i).size() / avg));
            }
            if (s > 0) hits.add(new Hit(docs.get(i).id(), s, "lexical", false));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }
}
