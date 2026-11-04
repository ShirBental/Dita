package app.dita.eval.scorer;

import app.dita.eval.BenchPaths;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.guard.CanaryGuard;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Integrity of the seed dev set (Stage 0 scope item 2). */
class GoldDatasetTest {
    final ScorerFileAccess files = new ScorerFileAccess(BenchPaths.locate());
    final Map<String, GoldLabel> gold = files.gold("dev");

    Optional<Corpus> corpus(String id) {
        try { return Optional.of(files.corpus(id)); } catch (RuntimeException e) { return Optional.empty(); }
    }

    @Test void goldValidatesAgainstSchemaPromptsAndCorpora() {
        Map<String, Optional<Corpus>> cache = new HashMap<>();
        List<String> errors = GoldValidator.validate("dev", gold, files.promptsRaw("dev"), id -> cache.computeIfAbsent(id, this::corpus));
        assertEquals(List.of(), errors);
    }

    @Test void sizeSplitAndCanaries() {
        assertTrue(gold.size() >= 25 && gold.size() <= 35, "dev size " + gold.size());
        assertTrue(gold.values().stream().allMatch(g -> g.split().equals("dev") && g.regime().equals("small")));
        Set<String> c = new HashSet<>();
        gold.values().forEach(g -> { assertTrue(CanaryGuard.CANARY_SHAPE.matcher(g.canary()).matches()); assertTrue(c.add(g.canary())); });
    }

    @Test void coversEveryBriefScenario() {
        Set<String> seen = new HashSet<>();
        gold.values().forEach(g -> seen.addAll(g.scenario()));
        for (String s : List.of("spread_recommendations", "context_dependent_rec", "correction", "offer_vs_confirmed", "split_reconnect",
                "forwarded_non_independent", "deleted", "superseded", "conflicting", "multi_hop_distant", "unknown", "plain_lookup", "injection"))
            assertTrue(seen.contains(s), "missing scenario " + s);
        assertTrue(gold.values().stream().anyMatch(g -> "E".equals(g.deletionTrack())));
        assertTrue(gold.values().stream().anyMatch(g -> "L".equals(g.deletionTrack())));
        assertTrue(gold.values().stream().filter(g -> !g.corpusId().equals("parents-b") && g.scenario().contains("plain_lookup")).count() >= 3);
    }

    @Test void atLeastThreeInjectionFixturesPerNewCorpusIncludingHebrewMixedAndFakeSystem() {
        for (String cid : List.of("building-he", "family-en", "club-mixed")) {
            Set<String> inj = new HashSet<>();
            gold.values().stream().filter(g -> g.corpusId().equals(cid)).forEach(g -> inj.addAll(g.injectionIds()));
            assertTrue(inj.size() >= 3, cid + " has " + inj.size());
            Corpus c = files.corpus(cid);
            List<String> texts = inj.stream().map(id -> c.find(id).orElseThrow().text()).toList();
            assertTrue(texts.stream().anyMatch(t -> t.startsWith("system:")), cid + ": fake system line");
            assertTrue(texts.stream().anyMatch(t -> t.matches("^[\\u0590-\\u05FF\\s,:]+.*") && !t.matches(".*[A-Za-z].*")), cid + ": Hebrew injection");
            assertTrue(texts.stream().anyMatch(t -> t.matches(".*[A-Za-z].*") && t.matches(".*[\\u05D0-\\u05EA].*")), cid + ": mixed injection");
        }
    }

    @Test void everyCorpusHasADecoySiblingThatIsNeverAQueryScope() {
        for (GoldLabel g : gold.values()) {
            assertEquals(g.corpusId() + "-decoy", g.decoyCorpusId());
            assertTrue(corpus(g.decoyCorpusId()).isPresent());
            assertFalse(g.corpusId().endsWith("-decoy"));
        }
    }
}
