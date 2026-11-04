package app.dita.eval.capability;

import app.dita.eval.BenchPaths;
import app.dita.eval.Json;
import app.dita.eval.Stats;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.scorer.AnswerMetrics;
import app.dita.eval.scorer.AnswerMetrics.ItemScore;
import app.dita.eval.scorer.GoldLabel;
import app.dita.eval.scorer.ScorerFileAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * TASK-044 scorer side: scores the runner's answers against sealed gold with the benchmark's deterministic
 * {@link AnswerMetrics} (same rules as every arm), raw and gated. Writes capability-&lt;split&gt;.json/.md to results/.
 * Reports ids, labels and metrics only; it never prints corpus text or answer text.
 */
public final class CapabilityScorer {
    private CapabilityScorer() {}

    public record Result(ObjectNode json, String markdown) {}

    public static Result score(ScorerFileAccess files, String split, String answersFile) {
        Map<String, GoldLabel> gold = files.gold(split);
        Map<String, Corpus> corpora = new HashMap<>();
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (String line : files.result(answersFile).split("\n")) if (!line.isBlank()) { JsonNode r = Json.parse(line); rows.put(r.get("qid").asText(), r); }
        if (!rows.keySet().equals(gold.keySet())) throw new IllegalStateException("answers do not cover exactly the gold qids");

        List<ItemScore> raw = new ArrayList<>(), gated = new ArrayList<>();
        ArrayNode items = Json.array();
        for (GoldLabel g : gold.values()) {
            Corpus c = corpora.computeIfAbsent(g.corpusId(), files::corpus);
            JsonNode r = rows.get(g.qid());
            ItemScore s = AnswerMetrics.scoreItem(g, c, r.get("content").asText(), r.get("finish_reason").asText(), false);
            ItemScore sg = AnswerMetrics.scoreItem(g, c, r.get("content").asText(), r.get("finish_reason").asText(), true);
            raw.add(s); gated.add(sg);
            JsonNode out = Json.parse(r.get("content").asText());
            List<String> cited = new ArrayList<>();
            out.get("claims").forEach(cl -> cl.get("message_ids").forEach(id -> cited.add(id.asText())));
            ObjectNode it = items.addObject();
            it.put("qid", g.qid()).put("corpus", g.corpusId()).put("lang", g.lang());
            it.set("scenario", Json.strings(g.scenario()));
            it.put("expected", g.expectedBehavior()).put("predicted", sg.predicted());
            it.put("composite", sg.composite()).put("behavior_ok", sg.behaviorCorrect());
            if (sg.answerKeysPresent() == null) it.putNull("answer_keys"); else it.put("answer_keys", sg.answerKeysPresent());
            it.put("verified_gold_citation", sg.verifiedGoldCitation());
            if (sg.evidenceRecall() == null) it.putNull("evidence_recall"); else it.put("evidence_recall", Stats.round(sg.evidenceRecall(), 3));
            it.set("gold_ids", Json.strings(new ArrayList<>(g.goldIds())));
            it.set("cited_ids", Json.strings(cited));
            it.put("forbidden", sg.forbiddenString() || sg.forbiddenIdCited() || sg.mustNotViolations() > 0);
            it.put("superseded_as_current", sg.supersededAsCurrent());
            if (sg.attribution() == null) it.putNull("attribution"); else it.put("attribution", Stats.round(sg.attribution(), 3));
            if (sg.expectedClaimsRate() == null) it.putNull("expected_claims"); else it.put("expected_claims", Stats.round(sg.expectedClaimsRate(), 3));
            it.put("deletion_track", g.deletionTrack()).put("deletion_violation", sg.deletionViolation());
            it.put("injection_followed", sg.injectionFollowed()).put("injection_cited", sg.injectionCited());
            it.put("ask_ms", Stats.round(r.get("ask_ms").asDouble(), 3));
            it.put("knowledge_items_used", r.get("knowledge_items_used").asInt());
        }

        ObjectNode o = Json.object();
        String arm = rows.values().iterator().next().get("arm").asText();
        o.put("task", "TASK-044 Dita Memory Capability Test").put("split", split).put("arm", arm);
        o.put("backend", rows.values().iterator().next().get("backend").asText());
        o.put("model_calls", 0);
        o.set("raw", AnswerMetrics.aggregate(arm, false, raw, gold, false));
        o.set("gated", AnswerMetrics.aggregate(arm, true, gated, gold, false));
        o.set("by_scenario", byScenario(gated, gold));
        o.set("by_corpus", byKey(gated, gold, g -> g.corpusId()));
        o.set("by_expected_behavior", byKey(gated, gold, GoldLabel::expectedBehavior));
        o.set("items", items);
        return new Result(o, markdown(o));
    }

    private static ObjectNode byScenario(List<ItemScore> s, Map<String, GoldLabel> gold) {
        Map<String, int[]> m = new TreeMap<>();
        for (ItemScore x : s) for (String sc : gold.get(x.qid()).scenario()) { int[] c = m.computeIfAbsent(sc, k -> new int[2]); c[1]++; if (x.composite()) c[0]++; }
        ObjectNode o = Json.object();
        m.forEach((k, c) -> o.putObject(k).put("n", c[1]).put("composite", c[0]));
        return o;
    }

    private static ObjectNode byKey(List<ItemScore> s, Map<String, GoldLabel> gold, java.util.function.Function<GoldLabel, String> key) {
        Map<String, int[]> m = new TreeMap<>();
        for (ItemScore x : s) { int[] c = m.computeIfAbsent(key.apply(gold.get(x.qid())), k -> new int[3]); c[2]++; if (x.composite()) c[0]++; if (x.behaviorCorrect()) c[1]++; }
        ObjectNode o = Json.object();
        m.forEach((k, c) -> o.putObject(k).put("n", c[2]).put("composite", c[0]).put("behavior_ok", c[1]));
        return o;
    }

    static String markdown(ObjectNode o) {
        JsonNode g = o.get("gated"), r = o.get("raw");
        StringBuilder sb = new StringBuilder();
        sb.append("# TASK-044 Dita Memory Capability Test (").append(o.get("split").asText()).append(" split)\n\n");
        sb.append("Arm `").append(o.get("arm").asText()).append("`: Dita's `/ask` path (QueryService: knowledge composer → retrieval (D-product: BM25 + conversation graph; BM25-only: graph off) → extractive composer → verifier) on a store replayed through production ingestion and deletion. Backend: **")
          .append(o.get("backend").asText()).append("**. Model calls: **0** (no Muse; the knowledge layer is empty because extraction needs Muse). Synthetic data only.\n\n");
        sb.append("| Metric | gated | raw |\n|---|---|---|\n");
        for (String k : List.of("n", "composite_rate", "behavior_macro_accuracy", "evidence_recall_mean", "citation_precision_mean", "attribution_mean",
                "expected_claims_rate_mean", "temporal_ok_rate", "unknown_precision", "unknown_recall", "over_abstention_rate",
                "forbidden_content_items", "superseded_as_current_items", "format_failures")) {
            sb.append("| ").append(k).append(" | ").append(g.get(k).asText()).append(" | ").append(r.get(k).asText()).append(" |\n");
        }
        JsonNode d = g.get("deletion_hard_gate"), inj = g.get("injection");
        sb.append("\nDeletion hard gate: track E n=").append(d.get("track_E").get("n").asText()).append(" violations=").append(d.get("track_E").get("violations").asText())
          .append(" · track L n=").append(d.get("track_L").get("n").asText()).append(" violations=").append(d.get("track_L").get("violations").asText())
          .append(". Injection: n=").append(inj.get("n").asText()).append(" followed=").append(inj.get("followed").asText()).append(" cited=").append(inj.get("cited").asText()).append(".\n\n");
        sb.append("## Composite by expected behaviour\n\n| Expected | n | behaviour ok | fully correct |\n|---|---|---|---|\n");
        o.get("by_expected_behavior").fields().forEachRemaining(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().get("n").asText())
                .append(" | ").append(e.getValue().get("behavior_ok").asText()).append(" | ").append(e.getValue().get("composite").asText()).append(" |\n"));
        sb.append("\n## Composite by corpus\n\n| Corpus | n | behaviour ok | fully correct |\n|---|---|---|---|\n");
        o.get("by_corpus").fields().forEachRemaining(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().get("n").asText())
                .append(" | ").append(e.getValue().get("behavior_ok").asText()).append(" | ").append(e.getValue().get("composite").asText()).append(" |\n"));
        sb.append("\n## Fully correct by scenario (items can have several scenarios; cells are tiny)\n\n| Scenario | n | fully correct |\n|---|---|---|\n");
        o.get("by_scenario").fields().forEachRemaining(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().get("n").asText())
                .append(" | ").append(e.getValue().get("composite").asText()).append(" |\n"));
        sb.append("\n## Per question (gated)\n\n| qid | lang | scenario | expected | Dita | fully correct | keys | gold cited | evidence recall | gold ids | Dita cited | flags |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (JsonNode it : o.get("items")) {
            List<String> flags = new ArrayList<>();
            if (it.get("forbidden").asBoolean()) flags.add("FORBIDDEN");
            if (it.get("superseded_as_current").asBoolean()) flags.add("SUPERSEDED-AS-CURRENT");
            if (it.get("deletion_violation").asBoolean()) flags.add("DELETION-VIOLATION");
            if (it.get("injection_followed").asBoolean()) flags.add("INJECTION-FOLLOWED");
            if (it.get("injection_cited").asBoolean()) flags.add("injection-cited");
            sb.append("| ").append(it.get("qid").asText()).append(" | ").append(it.get("lang").asText()).append(" | ")
              .append(String.join(", ", Json.stringList(it.get("scenario")))).append(" | ").append(it.get("expected").asText()).append(" | ")
              .append(it.get("predicted").asText()).append(" | ").append(it.get("composite").asBoolean() ? "yes" : "no").append(" | ")
              .append(it.get("answer_keys").isNull() ? "-" : it.get("answer_keys").asBoolean() ? "yes" : "no").append(" | ")
              .append(it.get("verified_gold_citation").asBoolean() ? "yes" : "no").append(" | ")
              .append(it.get("evidence_recall").isNull() ? "-" : it.get("evidence_recall").asText()).append(" | ")
              .append(String.join(" ", Json.stringList(it.get("gold_ids")))).append(" | ").append(String.join(" ", Json.stringList(it.get("cited_ids")))).append(" | ")
              .append(String.join(" ", flags)).append(" |\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        String split = args.length > 0 ? args[0] : "dev";
        ScorerFileAccess files = new ScorerFileAccess(BenchPaths.locate());
        files.verifyManifestOrThrow();
        Result res = score(files, split, "capability-" + split + "-answers.jsonl");
        files.writeResult("capability-" + split + ".json", Json.pretty(res.json()));
        files.writeResult("capability-" + split + ".md", res.markdown());
        System.out.println("capability scorer: " + res.json().get("gated").get("composite_rate").asText() + " fully correct (gated) "
                + String.format(Locale.ROOT, "over %d items", res.json().get("items").size()));
    }
}
