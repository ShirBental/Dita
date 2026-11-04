package app.dita.eval.context;

import app.dita.eval.Json;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared by every arm (DESIGN §1): a chronological JSON array of
 * {@code {type:"message", id, author, sent_at, reply_to, forwarded, edited, text}} holding only messages with
 * {@code sent_at <= as_of} that were not deleted at {@code as_of}. Every arm goes through {@link #visible}, so A, B and
 * C receive the same pre-filtered input and no arm earns credit for deletion handling. Budgets are in characters of
 * the rendered array.
 */
public final class ContextBuilder {
    private ContextBuilder() {}

    /** A built context. {@code droppedIds} are ranked ids refused because they were unknown or not visible at as_of. */
    public record Built(List<CorpusMessage> messages, String json, List<String> droppedIds) {
        public List<String> ids() { return messages.stream().map(CorpusMessage::id).toList(); }
        public int chars() { return json.length(); }
    }

    public static List<CorpusMessage> visible(Corpus corpus, Instant asOf) { return corpus.visible(asOf); }

    public static ObjectNode entry(CorpusMessage m) {
        ObjectNode o = Json.object();
        o.put("type", "message");
        o.put("id", m.id());
        o.put("author", m.author());
        o.put("sent_at", m.sentAt().toString());
        if (m.replyTo() == null) o.putNull("reply_to"); else o.put("reply_to", m.replyTo());
        o.put("forwarded", m.forwarded());
        o.put("edited", m.edited());
        o.put("text", m.text());
        return o;
    }

    public static int entryChars(CorpusMessage m) { return Json.compact(entry(m)).length(); }

    public static String render(List<CorpusMessage> chronological) {
        ArrayNode a = Json.array();
        chronological.forEach(m -> a.add(entry(m)));
        return Json.compact(a);
    }

    /** Arm A-full: the whole visible transcript. */
    public static Built full(Corpus corpus, Instant asOf) {
        List<CorpusMessage> v = visible(corpus, asOf);
        return new Built(v, render(v), List.of());
    }

    /** Arm A-budget: the newest contiguous visible messages whose rendered array fits {@code budgetChars}. */
    public static Built newestWithin(Corpus corpus, Instant asOf, int budgetChars) {
        List<CorpusMessage> v = visible(corpus, asOf);
        List<CorpusMessage> picked = new ArrayList<>();
        int used = 2;
        for (int i = v.size() - 1; i >= 0; i--) {
            int cost = entryChars(v.get(i)) + (picked.isEmpty() ? 0 : 1);
            if (used + cost > budgetChars) break;
            used += cost;
            picked.add(v.get(i));
        }
        picked.sort(Comparator.comparing(CorpusMessage::sentAt).thenComparing(CorpusMessage::id));
        return new Built(picked, render(picked), List.of());
    }

    /**
     * Retrieval arms: ranked ids, each expanded with up to {@code neighbourWindow} visible neighbours on each side
     * (nearest first), packed greedily in rank order into {@code budgetChars}, then emitted chronologically. Unknown or
     * not-visible ids are dropped and reported (defence in depth: the store replay should never return them).
     */
    public static Built pack(Corpus corpus, Instant asOf, List<String> rankedIds, int neighbourWindow, int budgetChars) {
        List<CorpusMessage> v = visible(corpus, asOf);
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < v.size(); i++) pos.put(v.get(i).id(), i);
        List<String> dropped = new ArrayList<>();
        Set<Integer> chosen = new LinkedHashSet<>();
        int used = 2;
        for (String id : rankedIds) {
            Integer p = pos.get(id);
            if (p == null) { dropped.add(id); continue; }
            List<Integer> block = new ArrayList<>(List.of(p));
            for (int d = 1; d <= neighbourWindow; d++) {
                if (p - d >= 0) block.add(p - d);
                if (p + d < v.size()) block.add(p + d);
            }
            for (int b : block) {
                if (chosen.contains(b)) continue;
                int cost = entryChars(v.get(b)) + (chosen.isEmpty() ? 0 : 1);
                if (used + cost > budgetChars) continue;
                used += cost;
                chosen.add(b);
            }
        }
        List<CorpusMessage> out = chosen.stream().sorted().map(v::get).toList();
        return new Built(out, render(out), dropped);
    }

    /** Builds a context from an explicit id set (used by the O-gold oracle). */
    public static Built ofIds(Corpus corpus, Instant asOf, Set<String> ids) {
        List<CorpusMessage> out = visible(corpus, asOf).stream().filter(m -> ids.contains(m.id())).toList();
        List<String> dropped = ids.stream().filter(id -> out.stream().noneMatch(m -> m.id().equals(id))).sorted().toList();
        return new Built(out, render(out), dropped);
    }
}
