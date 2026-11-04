package app.dita.core.knowledge;

import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;
import app.dita.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * In-process BM25 over each ACTIVE item's text: speaker display name, subject, qualifier spans, entity surfaces and the
 * verbatim quote cut from the LIVE source message (the same ingredients as the Vespa knowledge document, plus the
 * display name). Items whose quote can no longer be rendered (deleted source) are not indexed.
 */
public final class LexicalKnowledgeRetriever implements KnowledgeRetriever {
    private static final double K1 = 1.2, B = 0.75;
    private final MessageStore store;
    private final Function<String, String> names;

    public LexicalKnowledgeRetriever(MessageStore store, Function<String, String> names) { this.store = store; this.names = names; }

    @Override public String name() { return "lexical-bm25"; }

    /** The indexed text of an item (empty if it is not current or cannot be rendered from a live source). */
    static Optional<String> text(MessageStore store, GroupScope scope, KnowledgeItem k, Function<String, String> names) {
        Assertion a = k.assertion();
        if (a == null || a.validity() != Assertion.Validity.ACTIVE || k.statementRedacted() || k.onlyDeletedSourcesRemain()) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        boolean quote = false;
        for (KnowledgeItem.Evidence e : k.asserting()) {
            if (e.sourceDeleted() || !e.hasSpan()) continue;
            Optional<Message> m = store.find(scope, e.messageId());
            if (m.isEmpty() || m.get().deleted() || m.get().text() == null || e.spanEnd() > m.get().text().length()) continue;
            sb.append(m.get().text(), e.spanStart(), e.spanEnd()).append(' ');
            quote = true;
        }
        if (!quote) return Optional.empty();
        sb.append(names.apply(a.speakerId())).append(' ');
        String live = sb.toString();
        String subj = KnowledgeAnswerComposer.verbatim(a.subject(), live);                         // N-87: verbatim only
        if (subj != null) sb.append(subj).append(' ');
        for (Assertion.Qualifier q : a.qualifiers()) {
            store.find(scope, q.messageId()).filter(x -> !x.deleted() && x.text() != null && q.spanEnd() <= x.text().length())
                    .ifPresent(x -> sb.append(x.text(), q.spanStart(), q.spanEnd()).append(' '));
        }
        return Optional.of(sb.toString());
    }

    @Override public List<Hit> retrieve(GroupScope scope, String question, int k) {
        List<String> q = new ArrayList<>(Tokenizer.normalizedSet(question));   // TASK-045: inflection-tolerant
        if (q.isEmpty()) return List.of();
        Map<String, List<String>> docs = new HashMap<>();
        for (KnowledgeItem it : store.knowledge(scope)) text(store, scope, it, names).ifPresent(t -> docs.put(it.id(), Tokenizer.normalizedTokens(t)));
        if (docs.isEmpty()) return List.of();
        double avg = docs.values().stream().mapToInt(List::size).average().orElse(1);
        Map<String, Integer> df = new HashMap<>();
        for (List<String> d : docs.values()) for (String t : new java.util.HashSet<>(d)) df.merge(t, 1, Integer::sum);
        List<Hit> hits = new ArrayList<>();
        for (var e : docs.entrySet()) {
            double s = 0;
            for (String t : q) {
                int tf = (int) e.getValue().stream().filter(t::equals).count();
                if (tf == 0) continue;
                double idf = Math.log(1 + (docs.size() - df.get(t) + 0.5) / (df.get(t) + 0.5));
                s += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * e.getValue().size() / avg));
            }
            if (s > 0) hits.add(new Hit(e.getKey(), s));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::knowledgeId));
        return hits.subList(0, Math.min(k, hits.size()));
    }
}
