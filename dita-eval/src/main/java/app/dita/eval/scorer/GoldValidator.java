package app.dita.eval.scorer;

import app.dita.eval.Json;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;
import app.dita.eval.guard.CanaryGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Structural check against schema/label.schema.json (hand-written: no JSON-Schema library is available offline) plus
 * cross-file consistency: evidence ids exist and are visible at as_of, deletion tracks match the corpus, prompts match
 * gold exactly, canaries are unique and well-formed.
 */
public final class GoldValidator {
    static final Set<String> TOP = Set.of("qid", "split", "regime", "corpus_id", "decoy_corpus_id", "template_id", "overlap_bucket",
            "as_of", "lang", "question", "scenario", "expected_behavior", "answer_keys", "forbidden_strings", "gold_evidence",
            "acceptable_extra_ids", "superseded_ids", "forbidden_ids", "injection_ids", "expected_claims", "must_not_claims",
            "deletion_track", "gold_link_pairs", "source", "_canary");
    static final Set<String> REQUIRED = Set.of("qid", "split", "regime", "corpus_id", "as_of", "lang", "question", "scenario",
            "expected_behavior", "answer_keys", "forbidden_strings", "gold_evidence", "acceptable_extra_ids", "superseded_ids",
            "forbidden_ids", "expected_claims", "must_not_claims", "deletion_track", "_canary");
    static final Set<String> SCENARIOS = Set.of("plain_lookup", "spread_recommendations", "context_dependent_rec", "correction",
            "offer_vs_confirmed", "split_reconnect", "forwarded_non_independent", "deleted", "superseded", "conflicting",
            "multi_hop_distant", "unknown", "clarify", "injection", "isolation_probe", "as_of_temporal", "cross_language",
            "missing_reply_link", "ambiguous_pronoun", "parallel_threads", "resumed_conversation");
    static final Set<String> CLAIM_FIELDS = Set.of("message_ids", "speaker", "attributed_to", "commitment", "is_current", "stance", "qualifier_required");

    private GoldValidator() {}

    public static List<String> validate(String split, Map<String, GoldLabel> gold, List<String> promptLines,
                                        Function<String, Optional<Corpus>> corpora) {
        List<String> err = new ArrayList<>();
        Set<String> canaries = new HashSet<>();
        for (GoldLabel g : gold.values()) {
            String q = g.qid();
            JsonNode n = g.raw();
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) { String f = it.next(); if (!TOP.contains(f)) err.add(q + ": unknown field " + f); }
            for (String f : REQUIRED) if (!n.has(f)) err.add(q + ": missing " + f);
            if (!split.equals(g.split())) err.add(q + ": split must be " + split);
            if (!Set.of("small", "large").contains(g.regime())) err.add(q + ": bad regime");
            if (!Set.of("he", "en", "mixed").contains(g.lang())) err.add(q + ": bad lang");
            if (!Set.of("ANSWER", "UNKNOWN", "CONFLICT", "CLARIFY").contains(g.expectedBehavior())) err.add(q + ": bad expected_behavior");
            if (g.scenario().isEmpty() || !SCENARIOS.containsAll(g.scenario())) err.add(q + ": bad scenario");
            if (g.deletionTrack() != null && !Set.of("E", "L").contains(g.deletionTrack())) err.add(q + ": bad deletion_track");
            if (!g.canary().matches(CanaryGuard.CANARY_SHAPE.pattern()) || !canaries.add(g.canary())) err.add(q + ": canary missing, malformed or duplicate");
            for (GoldLabel.ClaimPattern cp : concat(g.expectedClaims(), g.mustNotClaims())) {
                for (Iterator<String> it = cp.raw().fieldNames(); it.hasNext(); ) { String f = it.next(); if (!CLAIM_FIELDS.contains(f)) err.add(q + ": bad claim pattern field " + f); }
                if (cp.messageIds().isEmpty()) err.add(q + ": claim pattern needs message_ids");
            }
            boolean answerable = Set.of("ANSWER", "CONFLICT").contains(g.expectedBehavior());
            if (answerable != g.hasGoldEvidence()) err.add(q + ": ANSWER/CONFLICT need gold evidence; UNKNOWN/CLARIFY must have none");
            if (g.scenario().contains("deleted") != (g.deletionTrack() != null)) err.add(q + ": scenario 'deleted' iff deletion_track set");

            Optional<Corpus> c = corpora.apply(g.corpusId());
            if (c.isEmpty()) { err.add(q + ": unknown corpus " + g.corpusId()); continue; }
            Optional<Corpus> decoy = g.decoyCorpusId() == null ? Optional.empty() : corpora.apply(g.decoyCorpusId());
            if (g.decoyCorpusId() != null && decoy.isEmpty()) err.add(q + ": unknown decoy corpus");
            Instant asOf = g.asOf();
            for (String id : g.goldIds()) requireVisible(err, q, "gold", c.get(), id, asOf);
            for (String id : g.acceptableExtraIds()) requireVisible(err, q, "acceptable", c.get(), id, asOf);
            for (String id : g.injectionIds()) {
                requireVisible(err, q, "injection", c.get(), id, asOf);
                if (!g.forbiddenIds().contains(id)) err.add(q + ": injection id " + id + " must also be forbidden (never cited)");
            }
            for (String id : g.supersededIds()) if (c.get().find(id).isEmpty()) err.add(q + ": superseded id " + id + " not in corpus");
            for (String id : g.forbiddenIds()) {
                boolean inCorpus = c.get().find(id).isPresent();
                boolean inDecoy = decoy.flatMap(d -> d.find(id)).isPresent();
                if (!inCorpus && !inDecoy) err.add(q + ": forbidden id " + id + " not in corpus or decoy");
                if (g.goldIds().contains(id) || g.acceptableExtraIds().contains(id)) err.add(q + ": id " + id + " is both forbidden and gold/acceptable");
            }
            if (g.deletionTrack() != null) {
                boolean match = g.forbiddenIds().stream().map(id -> c.get().find(id)).flatMap(Optional::stream)
                        .anyMatch(m -> m.deletedBy(asOf) && g.deletionTrack().equals(m.deletionTrack()));
                if (!match) err.add(q + ": deletion_track " + g.deletionTrack() + " but no forbidden message deleted by as_of on that track");
            }
        }
        Set<String> promptQids = new HashSet<>();
        for (String line : promptLines) {
            if (line.isBlank()) continue;
            JsonNode p = Json.parse(line);
            String qid = p.path("qid").asText();
            promptQids.add(qid);
            GoldLabel g = gold.get(qid);
            if (g == null) { err.add("prompt " + qid + " has no gold label"); continue; }
            if (!g.corpusId().equals(p.path("corpus_id").asText()) || !g.asOf().equals(Instant.parse(p.path("as_of").asText()))
                    || !g.question().equals(p.path("question").asText())) err.add(qid + ": prompt row differs from gold");
        }
        for (String qid : gold.keySet()) if (!promptQids.contains(qid)) err.add(qid + ": gold label has no prompt");
        return err;
    }

    private static void requireVisible(List<String> err, String q, String what, Corpus c, String id, Instant asOf) {
        Optional<CorpusMessage> m = c.find(id);
        if (m.isEmpty()) err.add(q + ": " + what + " id " + id + " not in corpus");
        else if (!m.get().visibleAt(asOf)) err.add(q + ": " + what + " id " + id + " not visible at as_of");
    }

    private static <T> List<T> concat(List<T> a, List<T> b) { List<T> o = new ArrayList<>(a); o.addAll(b); return o; }
}
