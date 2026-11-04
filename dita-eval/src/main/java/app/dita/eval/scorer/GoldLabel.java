package app.dita.eval.scorer;

import app.dita.eval.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A sealed gold label (schema/label.schema.json). Scorer-side only. */
public record GoldLabel(String qid, String split, String regime, String corpusId, String decoyCorpusId, Instant asOf,
                        String lang, String question, List<String> scenario, String expectedBehavior,
                        List<List<String>> answerKeys, List<String> forbiddenStrings, List<String> allOf, List<String> anyOf,
                        List<String> acceptableExtraIds, List<String> supersededIds, List<String> forbiddenIds,
                        List<String> injectionIds, List<ClaimPattern> expectedClaims, List<ClaimPattern> mustNotClaims,
                        String deletionTrack, String canary, JsonNode raw) {

    /** Matches an output claim citing any of {@code messageIds} whose other given fields agree ({@code raw} keeps nulls). */
    public record ClaimPattern(Set<String> messageIds, JsonNode raw) {}

    public boolean hasGoldEvidence() { return !allOf.isEmpty() || !anyOf.isEmpty(); }

    /** all_of ∪ any_of. */
    public Set<String> goldIds() { Set<String> s = new LinkedHashSet<>(allOf); s.addAll(anyOf); return s; }

    /** Number of evidence units: each all_of id, plus one unit for the any_of group if present. */
    public int evidenceUnits() { return allOf.size() + (anyOf.isEmpty() ? 0 : 1); }

    public int evidenceUnitsCovered(Set<String> ids) {
        int c = (int) allOf.stream().filter(ids::contains).count();
        if (!anyOf.isEmpty() && anyOf.stream().anyMatch(ids::contains)) c++;
        return c;
    }

    public boolean allGold(Set<String> ids) { return hasGoldEvidence() && evidenceUnitsCovered(ids) == evidenceUnits(); }

    public static GoldLabel parse(JsonNode n) {
        List<List<String>> keys = new ArrayList<>();
        n.path("answer_keys").forEach(k -> keys.add(Json.stringList(k)));
        JsonNode ge = n.path("gold_evidence");
        return new GoldLabel(n.path("qid").asText(), n.path("split").asText(), n.path("regime").asText(),
                n.path("corpus_id").asText(), Json.textOrNull(n, "decoy_corpus_id"), Instant.parse(n.path("as_of").asText()),
                n.path("lang").asText(), n.path("question").asText(), Json.stringList(n.get("scenario")),
                n.path("expected_behavior").asText(), keys, Json.stringList(n.get("forbidden_strings")),
                Json.stringList(ge.get("all_of")), Json.stringList(ge.get("any_of")),
                Json.stringList(n.get("acceptable_extra_ids")), Json.stringList(n.get("superseded_ids")),
                Json.stringList(n.get("forbidden_ids")), Json.stringList(n.get("injection_ids")),
                patterns(n.get("expected_claims")), patterns(n.get("must_not_claims")),
                Json.textOrNull(n, "deletion_track"), n.path("_canary").asText(), n);
    }

    private static List<ClaimPattern> patterns(JsonNode a) {
        List<ClaimPattern> out = new ArrayList<>();
        if (a != null) a.forEach(p -> out.add(new ClaimPattern(new LinkedHashSet<>(Json.stringList(p.get("message_ids"))), p)));
        return out;
    }

    @Override public String toString() { return "GoldLabel[" + qid + ", sealed]"; }
}
