package app.dita.core.retrieval.task054;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.MessageStore;
import app.dita.core.text.Tokenizer;

import java.util.*;

/**
 * Offline emulations of the two Vespa message channels, labelled with the production via strings so the production
 * fusion, composer and evidence code treat them exactly as on the real stack. EMULATIONS, not Vespa:
 *  - {@link #bm25}: Vespa rank profile bm25(text) with query type "any": every query word counts (Vespa removes no
 *    stopwords), standard BM25 (k1 1.2, b 0.75), English stemming approximated by Tokenizer.base.
 *  - {@link #dense}: exact nearest neighbour over the group with the frozen bge-small (LocalBgeSmall), cosine floor.
 */
final class EmulatedChannels {
    private EmulatedChannels() {}

    static List<String> vespaTerms(String text) {
        List<String> out = new ArrayList<>();
        for (String w : Tokenizer.rawWords(text)) out.add(Tokenizer.base(w.replace("'", "")));
        return out;
    }

    static Retriever bm25(MessageStore store) {
        return (scope, query, k) -> {
            List<Message> docs = store.live(scope);
            List<List<String>> toks = docs.stream().map(d -> vespaTerms(d.text())).toList();
            double avg = toks.stream().mapToInt(List::size).average().orElse(1);
            Map<String, Integer> df = new HashMap<>();
            toks.forEach(t -> new HashSet<>(t).forEach(w -> df.merge(w, 1, Integer::sum)));
            Set<String> q = new LinkedHashSet<>(vespaTerms(query));
            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Map<String, Integer> tf = new HashMap<>();
                toks.get(i).forEach(w -> tf.merge(w, 1, Integer::sum));
                double s = 0;
                for (String term : q) {
                    int f = tf.getOrDefault(term, 0);
                    if (f == 0) continue;
                    int n = df.get(term);
                    double idf = Math.log(1 + (docs.size() - n + 0.5) / (n + 0.5));
                    s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * toks.get(i).size() / avg));
                }
                if (s > 0) hits.add(new Hit(docs.get(i).id(), s, "vespa:bm25", false));
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            return hits.subList(0, Math.min(k, hits.size()));
        };
    }

    static Retriever dense(MessageStore store, LocalBgeSmall model, double minCosine) {
        return (scope, query, k) -> {
            try {
                float[] qv = model.query(query);
                List<Hit> hits = new ArrayList<>();
                for (Message m : store.live(scope)) {
                    double c = LocalBgeSmall.dot(qv, model.document(m.text()));
                    if (c >= minCosine) hits.add(new Hit(m.id(), c, "vespa:dense", false));
                }
                hits.sort(Comparator.comparingDouble(Hit::score).reversed());
                return hits.subList(0, Math.min(k, hits.size()));
            } catch (Exception e) { throw new IllegalStateException(e); }
        };
    }

    /** Diagnostics: the cosine of every message for a query (no floor). */
    static Map<String, Double> cosines(MessageStore store, LocalBgeSmall model, GroupScope scope, String query) throws Exception {
        Map<String, Double> out = new HashMap<>();
        float[] qv = model.query(query);
        for (Message m : store.live(scope)) out.put(m.id(), LocalBgeSmall.dot(qv, model.document(m.text())));
        return out;
    }
}
