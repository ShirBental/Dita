package app.dita.core.answer;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-045 (DEC-035): a search hit is not evidence. The real-stack message index (Vespa userQuery(), no stopword
 * removal) returned eight messages that matched the question only on "the"; the fallback presented them as an answer.
 * Synthetic fixtures only; the retriever is a stand-in that returns whatever a backend might.
 */
class FallbackRelevanceTest {
    static final GroupScope G = new GroupScope("g-fallback");
    static final Instant T = Instant.parse("2026-12-01T09:00:00Z");

    InMemoryMessageStore store(String... idText) {
        InMemoryMessageStore s = new InMemoryMessageStore();
        for (int i = 0; i < idText.length; i += 2)
            s.append(G, new Message(idText[i], G.groupId(), "u-" + i, T.plusSeconds(60L * i), null, idText[i + 1], SourceKind.SYNTHETIC, false));
        return s;
    }

    static Retriever returning(String... ids) {
        return (scope, q, k) -> java.util.Arrays.stream(ids).map(id -> new Hit(id, 1.0, "test", false)).toList();
    }

    @Test void stopwordOnlyMatchesAreNotAnAnswer() {                               // false positive (the Q2/Q15 class)
        var s = store("m1", "I suggest we keep the budget under $400.", "m2", "I can host in the backyard.");
        GroundedAnswer a = new ExtractiveAnswerComposer(returning("m1", "m2"), s).answer(G, "Why was the original date canceled?", 8);
        assertFalse(a.found(), "hits sharing only stopwords are not evidence: " + a.text());
        assertTrue(a.citations().isEmpty());
    }

    @Test void onlyRelevantHitsAreCitedWhenSomeMatch() {                          // valid fallback answer, noise dropped
        var s = store("m1", "I suggest we keep the budget under $400.", "m2", "The date was canceled because of the storm.");
        GroundedAnswer a = new ExtractiveAnswerComposer(returning("m1", "m2"), s).answer(G, "Why was the original date canceled?", 8);
        assertTrue(a.found(), a.text());
        assertEquals(List.of("m2"), a.citations().stream().map(Citation::messageId).toList());
    }

    @Test void inflectedFormsStillCountAsRelevant() {                             // valid fallback across word forms
        var s = store("m1", "I booked the van for Saturday.");
        GroundedAnswer a = new ExtractiveAnswerComposer(returning("m1"), s).answer(G, "Who is booking the van?", 8);
        assertTrue(a.found() && a.citations().size() == 1, a.text());
    }

    @Test void hebrewContentWordsCount() {
        var s = store("m1", "הפגישה עברה ליום שלישי.");
        GroundedAnswer a = new ExtractiveAnswerComposer(returning("m1"), s).answer(G, "מתי הפגישה?", 8);
        assertTrue(a.found(), a.text());
    }

    @Test void inferredContextNeedsARelevantDirectMatch() {
        var s = store("m1", "The weather looks fine.", "m2", "The cake order is confirmed.");
        Retriever r = (scope, q, k) -> List.of(new Hit("m1", 1.0, "test", false), new Hit("m2", 0.5, "graph", true));
        assertFalse(new ExtractiveAnswerComposer(r, s).answer(G, "Who orders the balloons?", 8).found(),
                "inferred context alone, reached from an irrelevant hit, is not an answer");
        Retriever r2 = (scope, q, k) -> List.of(new Hit("m2", 1.0, "test", false), new Hit("m1", 0.5, "graph", true));
        GroundedAnswer ok = new ExtractiveAnswerComposer(r2, s).answer(G, "Who confirmed the cake?", 8);
        assertEquals(List.of("m2", "m1"), ok.citations().stream().map(Citation::messageId).toList(), "inferred context kept with a relevant anchor");
    }

    @Test void replyLinkedAnswerWithoutSharedWordsIsKept() {                      // valid fallback via an explicit reply edge
        var s = store("m1", "Can someone water the plants on Monday?", "m2", "I'll do it before work.");
        Retriever r = (scope, q, k) -> List.of(new Hit("m1", 1.0, "lexical", false), new Hit("m2", 0.6, "graph:EXPLICIT_REPLY", false));
        GroundedAnswer a = new ExtractiveAnswerComposer(r, s).answer(G, "Who waters the plants?", 8);
        assertEquals(List.of("m1", "m2"), a.citations().stream().map(Citation::messageId).toList(), a.text());
        Retriever noise = (scope, q, k) -> List.of(new Hit("m2", 1.0, "neo4j:explicit:1", false));
        assertFalse(new ExtractiveAnswerComposer(noise, s).answer(G, "Who waters the plants?", 8).found(), "a link alone is not evidence");
    }
}
