package app.dita.eval.arms;

import app.dita.core.config.RetrievalSettings;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.LexicalRetriever;
import app.dita.eval.BenchPaths;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;
import app.dita.eval.runner.ContextPlanner;
import app.dita.eval.runner.Prompt;
import app.dita.eval.runner.RunnerFileAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArmsTest {
    final RunnerFileAccess files = new RunnerFileAccess(BenchPaths.locate());

    @Test void replayAppliesDeletionsAndKeepsDecoyInItsOwnPartition() {
        Corpus c = files.corpus("building-he");
        Corpus d = files.decoyFor("building-he").orElseThrow();
        Instant asOf = Instant.parse("2026-09-06T00:00:00Z");
        StoreReplay.Replayed r = StoreReplay.replay(c, d, asOf);
        List<String> live = r.store().live(r.scope()).stream().map(Message::id).toList();
        assertFalse(live.contains("h29"), "Track E deletion applied");
        assertTrue(r.store().find(r.scope(), "h29").orElseThrow().deleted());
        assertEquals(c.visible(asOf).stream().map(CorpusMessage::id).toList(), live);
        assertTrue(live.stream().noneMatch(id -> id.startsWith("hx")));
        assertEquals(d.messages().size(), r.store().live(r.decoyScope()).size());
        assertTrue(r.store().links(r.scope()).stream().noneMatch(l -> l.dependsOn("h29")), "links depending on a deleted message are invalidated");
    }

    @Test void replayStopsAtAsOf() {
        Corpus c = files.corpus("building-he");
        StoreReplay.Replayed r = StoreReplay.replay(c, null, Instant.parse("2026-08-20T00:00:00Z"));
        assertTrue(r.store().find(r.scope(), "h37").isEmpty(), "future correction never replayed");
        assertTrue(r.store().links(r.scope()).stream().noneMatch(l -> l.fromId().equals("h37") || l.toId().equals("h37")));
    }

    @Test void bAndCShareKAndCandidateDepth() {
        ArmSettings s = ArmSettings.STAGE0;
        assertEquals(RetrievalSettings.DEFAULTS.defaultK(), s.k());
        assertEquals(s.cToday().candidateMultiplier(), s.cMinusGraph().candidateMultiplier());
        assertFalse(s.cMinusGraph().graphEnabled());
        assertTrue(s.cToday().graphEnabled());
        assertEquals("FULL_GROUP_HISTORY", s.toJson().get("history_access").asText());
        assertEquals("in-memory", s.toJson().get("backend").asText());
    }

    @Test void cMinusGraphEqualsLexicalRetriever() {
        ContextPlanner planner = new ContextPlanner(files, ArmSettings.STAGE0);
        for (Prompt p : planner.prompts("dev")) {
            StoreReplay.Replayed r = planner.replay(p);
            List<String> lexical = new LexicalRetriever(r.store()).retrieve(r.scope(), p.question(), 8).stream().map(Hit::messageId).toList();
            assertEquals(lexical, planner.context(ArmId.C_MINUS_GRAPH, p).retrievedIds(), p.qid());
        }
    }

    @Test void diagnosticArmsReuseTheirBaseRetrieverAndOnlyChangeTheWindow() {
        ContextPlanner planner = new ContextPlanner(files, ArmSettings.STAGE0);
        for (Prompt p : planner.prompts("dev")) {
            assertEquals(planner.context(ArmId.B_WEAK, p).retrievedIds(), planner.context(ArmId.B_WEAK_NO_WINDOW, p).retrievedIds());
            assertEquals(planner.context(ArmId.C_TODAY, p).retrievedIds(), planner.context(ArmId.C_TODAY_WINDOW, p).retrievedIds());
            assertTrue(planner.context(ArmId.C_TODAY_WINDOW, p).context().ids().size() >= planner.context(ArmId.C_TODAY, p).context().ids().size());
        }
        assertTrue(ArmId.C_TODAY_WINDOW.diagnostic() && !ArmId.C_TODAY.diagnostic());
    }

    @Test void everyArmContextIsVisibleInScopeAndWithinBudget() {
        ContextPlanner planner = new ContextPlanner(files, ArmSettings.STAGE0);
        for (Prompt p : planner.prompts("dev")) {
            Corpus c = planner.corpus(p.corpusId());
            Set<String> visible = new HashSet<>(c.visible(p.asOf()).stream().map(CorpusMessage::id).toList());
            for (ArmId arm : ArmId.values()) {
                var ctx = planner.context(arm, p);
                assertTrue(visible.containsAll(ctx.context().ids()), p.qid() + "/" + arm);
                assertTrue(ctx.context().droppedIds().isEmpty(), "store replay returned a non-visible id: " + p.qid() + "/" + arm);
                if (arm != ArmId.A_FULL) assertTrue(ctx.context().chars() <= ArmSettings.STAGE0.tCtxChars(), p.qid() + "/" + arm);
                if (arm != ArmId.A_FULL && arm != ArmId.A_BUDGET) assertTrue(ctx.retrievedIds().size() <= ArmSettings.STAGE0.k());
            }
        }
    }

    @Test void charNgramRetrieverFindsSharedSubstringsOnly() {
        Corpus c = files.corpus("club-mixed");
        StoreReplay.Replayed r = StoreReplay.replay(c, null, Instant.parse("2026-11-03T00:00:00Z"));
        List<Hit> hits = new CharNgramRetriever(r.store()).retrieve(r.scope(), "Sparkwhistle", 3);
        assertFalse(hits.isEmpty());
        assertTrue(c.find(hits.get(0).messageId()).orElseThrow().text().contains("Sparkwhistle"));
        assertTrue(new CharNgramRetriever(r.store()).retrieve(r.scope(), "", 3).isEmpty());
    }
}
