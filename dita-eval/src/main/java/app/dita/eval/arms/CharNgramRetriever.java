package app.dita.eval.arms;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.MessageStore;
import app.dita.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WEAK CONTROL ONLY. Character n-gram feature hashing with cosine similarity. It has no cross-language semantics, so
 * it must not stand in for B's multilingual embedder (DESIGN §1). Group-scoped through {@link MessageStore#live}.
 */
public final class CharNgramRetriever implements Retriever {
    public static final String LABEL = "char-ngram-hashing (weak control)";
    private final MessageStore store;
    private final int n, dims;

    public CharNgramRetriever(MessageStore store) { this(store, 3, 1 << 18); }

    public CharNgramRetriever(MessageStore store, int n, int dims) {
        if (n < 2 || dims < 1024) throw new IllegalArgumentException("n >= 2, dims >= 1024");
        this.store = store; this.n = n; this.dims = dims;
    }

    @Override
    public List<Hit> retrieve(GroupScope scope, String query, int k) {
        Map<Integer, Double> q = vector(query);
        if (q.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (Message m : store.live(scope)) {
            double s = cosine(q, vector(m.text()));
            if (s > 0) hits.add(new Hit(m.id(), s, "char-ngram", false));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::messageId));
        return hits.subList(0, Math.min(k, hits.size()));
    }

    Map<Integer, Double> vector(String text) {
        Map<Integer, Double> tf = new HashMap<>();
        for (String w : Tokenizer.rawWords(text)) {
            String padded = " " + w + " ";
            if (padded.length() < n) continue;
            for (int i = 0; i + n <= padded.length(); i++) {
                tf.merge(Math.floorMod(padded.substring(i, i + n).hashCode(), dims), 1.0, Double::sum);
            }
        }
        double norm = 0;
        for (Map.Entry<Integer, Double> e : tf.entrySet()) {
            double w = 1 + Math.log(e.getValue());
            e.setValue(w);
            norm += w * w;
        }
        double len = Math.sqrt(norm);
        if (len > 0) tf.replaceAll((k, v) -> v / len);
        return tf;
    }

    static double cosine(Map<Integer, Double> a, Map<Integer, Double> b) {
        Map<Integer, Double> small = a.size() <= b.size() ? a : b, large = small == a ? b : a;
        double s = 0;
        for (Map.Entry<Integer, Double> e : small.entrySet()) s += e.getValue() * large.getOrDefault(e.getKey(), 0.0);
        return s;
    }
}
