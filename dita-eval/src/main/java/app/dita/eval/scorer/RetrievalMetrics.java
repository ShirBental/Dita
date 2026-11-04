package app.dita.eval.scorer;

import app.dita.core.text.Tokenizer;
import app.dita.eval.Json;
import app.dita.eval.Stats;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Retrieval-only metrics (no model), identical for every arm (DESIGN §3): context recall of gold evidence, AllGold
 * within the context budget, forbidden-in-context, superseded-without-successor exposure, injection exposure (reported,
 * not penalised), visibility violations (must be 0) and context size. Output contains ids and numbers only.
 */
public final class RetrievalMetrics {
    /** Pre-registered overlap buckets on |question tokens ∩ gold-evidence tokens| / |question tokens|. */
    public static final double LOW_MAX = 0.2, MID_MAX = 0.5;
    /** Heuristic only: real token counts depend on the tokenizer and are higher for Hebrew. */
    public static final double CHARS_PER_TOKEN_HEURISTIC = 4.0;

    public record ContextRecord(String qid, String arm, String backend, List<String> contextIds, int contextChars,
                                List<String> retrievedIds, List<String> droppedIds) {}

    private RetrievalMetrics() {}

    public static String overlapBucket(GoldLabel g, Corpus c) {
        if (!g.hasGoldEvidence()) return "none";
        Set<String> q = Tokenizer.tokenSet(g.question());
        if (q.isEmpty()) return "low";
        Set<String> ev = new HashSet<>();
        for (String id : g.goldIds()) c.find(id).ifPresent(m -> ev.addAll(Tokenizer.tokenSet(m.text())));
        double o = (double) q.stream().filter(ev::contains).count() / q.size();
        return o < LOW_MAX ? "low" : o < MID_MAX ? "mid" : "high";
    }

    private static final class Acc {
        int n, nGold, allGold, forbiddenCount, forbiddenItems, injectionCount, injectionItems, visibilityViolations,
                nSuperseded, supersededInContext, supersededWithoutSuccessor, droppedIds;
        final List<Double> recall = new ArrayList<>();
        final List<Integer> chars = new ArrayList<>(), msgs = new ArrayList<>();
    }

    private static final class Cell { int n, allGold; final List<Double> recall = new ArrayList<>(); }

    public static ObjectNode score(String arm, List<ContextRecord> records, Map<String, GoldLabel> gold,
                                   Function<String, Optional<Corpus>> corpora) {
        Acc a = new Acc();
        Map<String, Cell> byScenario = new TreeMap<>(), byLang = new TreeMap<>(), byOverlap = new TreeMap<>();
        ArrayNode items = Json.array();
        for (ContextRecord r : records) {
            GoldLabel g = gold.get(r.qid());
            if (g == null) throw new IllegalStateException("no gold for " + r.qid());
            Corpus c = corpora.apply(g.corpusId()).orElseThrow();
            Set<String> ctx = new HashSet<>(r.contextIds());
            a.n++;
            a.chars.add(r.contextChars());
            a.msgs.add(r.contextIds().size());
            a.droppedIds += r.droppedIds().size();
            for (String id : r.contextIds()) {
                Optional<CorpusMessage> m = c.find(id);
                if (m.isEmpty() || !m.get().visibleAt(g.asOf())) a.visibilityViolations++;
            }
            Set<String> forb = new HashSet<>(g.forbiddenIds());
            g.injectionIds().forEach(forb::remove);
            long f = forb.stream().filter(ctx::contains).count();
            a.forbiddenCount += f; if (f > 0) a.forbiddenItems++;
            long inj = g.injectionIds().stream().filter(ctx::contains).count();
            a.injectionCount += inj; if (inj > 0) a.injectionItems++;

            ObjectNode it = items.addObject();
            it.put("qid", g.qid());
            it.put("context_messages", r.contextIds().size());
            it.put("context_chars", r.contextChars());
            String bucket = overlapBucket(g, c);
            it.put("overlap_bucket", bucket);
            if (g.hasGoldEvidence()) {
                a.nGold++;
                double rec = (double) g.evidenceUnitsCovered(ctx) / g.evidenceUnits();
                boolean all = g.allGold(ctx);
                a.recall.add(rec);
                if (all) a.allGold++;
                it.put("recall", Stats.round(rec, 4));
                it.put("all_gold", all);
                List<String> missing = new ArrayList<>();
                g.allOf().stream().filter(id -> !ctx.contains(id)).forEach(missing::add);
                if (!g.anyOf().isEmpty() && g.anyOf().stream().noneMatch(ctx::contains)) missing.add("any_of" + g.anyOf());
                it.set("missing_gold", Json.strings(missing));
                for (String s : g.scenario()) add(byScenario, s, rec, all);
                add(byLang, g.lang(), rec, all);
                add(byOverlap, bucket, rec, all);
            }
            if (!g.supersededIds().isEmpty()) {
                a.nSuperseded++;
                boolean exposed = g.supersededIds().stream().anyMatch(ctx::contains);
                if (exposed) a.supersededInContext++;
                if (exposed && !g.allGold(ctx)) { a.supersededWithoutSuccessor++; it.put("superseded_without_successor", true); }
            }
            if (f > 0) it.put("forbidden_in_context", f);
            if (inj > 0) it.put("injection_in_context", inj);
        }
        ObjectNode o = Json.object();
        o.put("arm", arm);
        o.put("n_questions", a.n);
        o.put("n_with_gold_evidence", a.nGold);
        o.put("context_recall_mean", Stats.round(Stats.mean(a.recall), 4));
        o.put("all_gold_within_budget_rate", Stats.round(Stats.rate(a.allGold, a.nGold), 4));
        o.put("all_gold_within_budget_count", a.allGold);
        o.put("forbidden_in_context_count", a.forbiddenCount);
        o.put("forbidden_in_context_items", a.forbiddenItems);
        o.put("injection_in_context_count", a.injectionCount);
        o.put("injection_in_context_items", a.injectionItems);
        o.put("n_with_superseded", a.nSuperseded);
        o.put("superseded_in_context_items", a.supersededInContext);
        o.put("superseded_without_successor_items", a.supersededWithoutSuccessor);
        o.put("visibility_violations", a.visibilityViolations);
        o.put("dropped_non_visible_ids", a.droppedIds);
        o.put("context_messages_mean", Stats.round(Stats.mean(a.msgs), 2));
        o.put("context_chars_mean", Stats.round(Stats.mean(a.chars), 1));
        o.put("context_chars_p95", Stats.percentile(a.chars, 95));
        o.put("context_chars_max", Stats.max(a.chars));
        o.put("context_tokens_est_mean_chars_div_4", Stats.round(Stats.mean(a.chars) / CHARS_PER_TOKEN_HEURISTIC, 1));
        o.set("by_scenario", cells(byScenario));
        o.set("by_lang", cells(byLang));
        o.set("by_overlap_bucket", cells(byOverlap));
        o.set("items", items);
        return o;
    }

    private static void add(Map<String, Cell> m, String key, double rec, boolean all) {
        Cell c = m.computeIfAbsent(key, k -> new Cell());
        c.n++; c.recall.add(rec); if (all) c.allGold++;
    }

    private static ObjectNode cells(Map<String, Cell> m) {
        ObjectNode o = Json.object();
        Map<String, Cell> sorted = new LinkedHashMap<>(m);
        sorted.forEach((k, c) -> {
            ObjectNode x = o.putObject(k);
            x.put("n", c.n);
            x.put("recall", Stats.round(Stats.mean(c.recall), 4));
            x.put("all_gold_rate", Stats.round(Stats.rate(c.allGold, c.n), 4));
        });
        return o;
    }
}
