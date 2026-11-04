package app.dita.core.knowledge;

import app.dita.core.model.GroupScope;
import app.dita.core.store.MessageStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replays recorded Muse outputs (recordings/&lt;set&gt;/&lt;arm&gt;-responses.jsonl: one line per batch {"batch", "content", "usage", ...}).
 * No network. Anything missing or malformed is UNCERTAIN (PREREGISTRATION §7: errors and bad JSON mean no relation).
 */
final class MuseRecordings {
    private final Map<String, JsonNode> pairs = new HashMap<>();          // "case#later>earlier" → A3 pair output
    private final Map<String, JsonNode> a6Cases = new HashMap<>();        // case id → A6 case output

    static Optional<MuseRecordings> load(Path dir, String arm) throws Exception {
        String base = arm.equals("A5") ? "A3" : arm;
        Path f = dir.resolve(base + "-responses.jsonl");
        if (!Files.isRegularFile(f)) return Optional.empty();
        MuseRecordings r = new MuseRecordings();
        for (String line : Files.readAllLines(f)) {
            if (line.isBlank()) continue;
            JsonNode rec = SemEval.JSON.readTree(line);
            if (rec.path("pending").asBoolean(false)) continue;                       // N-118 marker, not a reply
            JsonNode out;
            try { out = SemEval.JSON.readTree(stripFences(rec.path("content").asText(""))); }
            catch (Exception e) { continue; }                                          // malformed batch: every pair UNCERTAIN
            if (base.equals("A3")) out.path("pairs").forEach(p -> r.pairs.put(p.path("pair_id").asText(), p));
            else out.path("cases").forEach(c -> r.a6Cases.put(c.path("case").asText(), c));
        }
        return Optional.of(r);
    }

    /** Same as MuseKnowledgeExtractor.stripFences (copied: that method is package-private in production). */
    static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) { t = t.replaceFirst("^```(?:json)?\\s*", ""); t = t.replaceFirst("\\s*```$", ""); }
        return t;
    }

    static RelationArms.Tri tri(JsonNode v) {
        try { return RelationArms.Tri.valueOf(v.asText("UNCERTAIN").strip().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return RelationArms.Tri.UNCERTAIN; }
    }

    RelationArms.Arm arm(String id) {
        return new RelationArms.Arm() {
            public String id() { return id + "-muse(recorded)"; }
            public RelationArms.Proposal propose(GroupScope g, MessageStore s, RelationArms.Pair p) {
                String c = SemEval.CURRENT_CASE.get();
                String later = SemEval.mid(p.later()), earlier = SemEval.mid(p.earlier());
                JsonNode o = id.startsWith("A6") ? a6Relation(c, later, earlier) : pairs.get(c + "#" + later + ">" + earlier);
                if (o == null) return new RelationArms.Proposal(p, RelationArms.Tri.UNCERTAIN, RelationArms.Tri.UNCERTAIN, List.of(), 0, id(), 0);
                List<String> ev = o.path("evidence").isTextual() && !o.path("evidence").asText().isBlank() ? List.of(o.path("evidence").asText()) : List.of();
                return new RelationArms.Proposal(p, tri(o.path("same_property")), tri(o.path("replaces")), ev, o.path("confidence").asDouble(0), id(), 0);
            }
        };
    }

    /** A6 relation between two extracted items, located through their evidence message ids. */
    private JsonNode a6Relation(String caseId, String laterMid, String earlierMid) {
        JsonNode c = a6Cases.get(caseId);
        if (c == null) return null;
        Map<String, String> keyToMid = new HashMap<>();
        c.path("items").forEach(it -> keyToMid.put(it.path("item_key").asText(), localMid(it.path("evidence").path(0).path("message_id").asText())));
        for (JsonNode r : c.path("relations"))
            if (laterMid.equals(keyToMid.get(r.path("later_key").asText())) && earlierMid.equals(keyToMid.get(r.path("earlier_key").asText()))) return r;
        return null;
    }

    static String localMid(String namespaced) { int i = namespaced.indexOf('#'); return i < 0 ? namespaced : namespaced.substring(i + 1); }

    /** A6 items for a case, as extraction proposals (namespaced ids mapped back). Missing case → no items. */
    List<Proposal.Item> items(JsonNode caseNode) {
        JsonNode c = a6Cases.get(caseNode.path("id").asText());
        List<Proposal.Item> out = new ArrayList<>();
        if (c == null) return out;
        for (JsonNode it : c.path("items")) {
            List<Proposal.Quote> ev = new ArrayList<>();
            it.path("evidence").forEach(q -> ev.add(new Proposal.Quote(localMid(q.path("message_id").asText()), q.path("quote").asText(null))));
            List<Proposal.Qualifier> qs = new ArrayList<>();
            String firstMid = ev.isEmpty() ? null : ev.get(0).messageId();
            it.path("conditions").forEach(cond -> { if (firstMid != null && cond.isTextual()) qs.add(new Proposal.Qualifier("CONDITION", firstMid, cond.asText())); });
            out.add(new Proposal.Item(it.path("status").asText(null), it.path("certainty").asText(null), it.path("subject_id").asText(null),
                    it.path("subject").asText(null), it.path("predicate").asText(null), null,
                    it.hasNonNull("attributed_to") ? it.get("attributed_to").asText() : null, ev, qs, List.of()));
        }
        return out;
    }
}
