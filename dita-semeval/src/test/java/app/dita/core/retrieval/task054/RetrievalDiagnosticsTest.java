package app.dita.core.retrieval.task054;

import app.dita.core.model.MessageLink;
import app.dita.core.retrieval.Hit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** TASK-054 DIAGNOSTICS ONLY (prints, never asserts): per-channel ranks, cosines and inferred links for Q1's evidence. */
class RetrievalDiagnosticsTest {
    @Test void q1() throws Exception {
        assumeTrue(LocalBgeSmall.available(), "model absent: NOT EXECUTED");
        try (var model = new LocalBgeSmall()) {
            var h = new ConversationHarness("tgt1001000000054", Path.of("src/test/resources/task054/alice-bob-natural-dialogue.txt"), 302, model);
            String q = "When and where is the picnic, and what happens if it rains?";
            int[] essential = {21, 23, 24, 25, 26, 27, 35, 36, 37, 38, 39, 40, 198, 200, 78, 79, 80, 120};
            List<Hit> bm = EmulatedChannels.bm25(h.store).retrieve(h.scope, q, 200);
            List<Hit> fused = h.retriever.retrieve(h.scope, q, 200);
            Map<String, Integer> bmRank = rank(bm), fuRank = rank(fused);
            Map<String, Double> cos = EmulatedChannels.cosines(h.store, model, h.scope, q);
            List<String> byCos = cos.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
            System.out.println("[diag] BM25 query terms (Vespa 'any', no stopwords): " + EmulatedChannels.vespaTerms(q));
            System.out.println("[diag] dense: messages >= 0.60 cosine: " + cos.values().stream().filter(c -> c >= 0.6).count() + " of " + cos.size());
            String[] parts = {"When is the picnic?", "Where is the picnic?", "What happens to the picnic if it rains?"};
            for (int l : essential) {
                String id = h.id(l);
                StringBuilder sb = new StringBuilder(String.format("[diag] m%d L%-3d bm25#%-4s dense#%-4s cos=%.3f fused#%-4s", l + 302, l,
                        String.valueOf(bmRank.getOrDefault(id, -1)), byCos.indexOf(id) + 1, cos.get(id), String.valueOf(fuRank.getOrDefault(id, -1))));
                for (String p : parts) sb.append(String.format(" cos[%s]=%.3f", p.substring(0, 5), LocalBgeSmall.dot(model.query(p), model.document(h.textById.get(id)))));
                sb.append(" | ").append(h.textById.get(id));
                System.out.println(sb);
            }
            System.out.println("[diag] top 12 by BM25: ");
            bm.stream().limit(12).forEach(x -> System.out.printf("   m%s %.2f %s%n", x.messageId().substring(h.group.length() + 1), x.score(), h.textById.get(x.messageId())));
            System.out.println("[diag] top 12 by cosine: ");
            byCos.stream().limit(12).forEach(x -> System.out.printf("   m%s %.3f %s%n", x.substring(h.group.length() + 1), cos.get(x), h.textById.get(x)));
            System.out.println("[diag] inferred links touching essential messages (from -> to, kind, score, evidence):");
            Set<String> ess = new HashSet<>(); for (int l : essential) ess.add(h.id(l));
            for (MessageLink link : h.store.links(h.scope))
                if (ess.contains(link.fromId()) || ess.contains(link.toId()))
                    System.out.println("   " + link.fromId().substring(h.group.length()) + " -> " + link.toId().substring(h.group.length()) + " " + link.kind() + " " + link.score() + " " + link.evidence());
            System.out.println("[diag] total inferred links: " + h.store.links(h.scope).size());
        }
    }
    @Test void threadCoherence() throws Exception {
        assumeTrue(LocalBgeSmall.available(), "model absent: NOT EXECUTED");
        try (var model = new LocalBgeSmall()) {
            var h = new ConversationHarness("tgt1001000000054", Path.of("src/test/resources/task054/alice-bob-natural-dialogue.txt"), 302, model);
            float[] topic = model.query("picnic");
            int[][] ranges = {{14, 48}, {176, 200}};
            for (int[] r : ranges) for (int l = r[0]; l <= r[1]; l++) {
                String t = h.textById.get(h.id(l)), prev = h.textById.get(h.id(l - 1));
                float[] d = model.document(t);
                System.out.printf("[coh] L%-3d topic=%.3f prevDoc=%.3f | %s%n", l, LocalBgeSmall.dot(topic, d),
                        LocalBgeSmall.dot(d, model.document(prev)), t);
            }
        }
    }

    static Map<String, Integer> rank(List<Hit> hits) { Map<String, Integer> m = new HashMap<>(); for (int i = 0; i < hits.size(); i++) m.putIfAbsent(hits.get(i).messageId(), i + 1); return m; }
}
