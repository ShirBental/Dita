package app.dita.eval.context;

import app.dita.eval.Json;
import app.dita.eval.corpus.Corpus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** as_of and deletion filtering shared by every arm. Synthetic, fictional content. */
class ContextBuilderTest {
    static final String C = String.join("\n",
            line("c1", "ann", "2026-01-01T10:00:00Z", null, "the picnic is at Pinecone Meadow", null),
            line("c2", "bo", "2026-01-01T10:01:00Z", "c1", "great, bring lemonade", null),
            line("c3", "cy", "2026-01-01T10:02:00Z", null, "secret gate code 9999", "2026-01-01T10:04:00Z"),   // Track E
            line("c4", "ann", "2026-01-01T11:00:00Z", null, "moved to Willowbend Park", "2026-01-01T13:00:00Z"), // Track L
            line("c5", "bo", "2026-01-02T09:00:00Z", null, "see you all tomorrow", null),
            line("c6", "cy", "2026-01-03T09:00:00Z", null, "future message", null));

    static String line(String id, String a, String t, String reply, String text, String del) {
        return "{\"id\":\"" + id + "\",\"author\":\"" + a + "\",\"sent_at\":\"" + t + "\",\"reply_to\":" + (reply == null ? "null" : "\"" + reply + "\"")
                + ",\"forwarded\":false,\"edited\":false,\"text\":\"" + text + "\"" + (del == null ? "" : ",\"deleted_at\":\"" + del + "\"") + "}";
    }

    final Corpus corpus = Corpus.parse("tiny", C);

    List<String> ids(Instant asOf) { return ContextBuilder.full(corpus, asOf).ids(); }

    @Test void excludesFutureMessagesAndIncludesSentExactlyAtAsOf() {
        assertEquals(List.of("c1", "c2", "c5"), ids(Instant.parse("2026-01-02T09:00:00Z")), "c3/c4 already deleted, c6 in the future");
        assertEquals(List.of("c1", "c2", "c3"), ids(Instant.parse("2026-01-01T10:02:00Z")), "sent exactly at as_of is included");
        assertFalse(ids(Instant.parse("2026-01-02T08:59:59Z")).contains("c5"));
    }

    @Test void deletionIsAppliedAsOfTheQuestionTime() {
        assertTrue(ids(Instant.parse("2026-01-01T10:03:00Z")).contains("c3"), "not yet deleted at as_of");
        assertFalse(ids(Instant.parse("2026-01-01T10:04:00Z")).contains("c3"), "deleted exactly at as_of counts as deleted");
        assertTrue(ids(Instant.parse("2026-01-01T12:00:00Z")).contains("c4"));
        assertFalse(ids(Instant.parse("2026-01-02T12:00:00Z")).contains("c4"));
    }

    @Test void deletionTracks() {
        assertEquals("E", corpus.find("c3").orElseThrow().deletionTrack());
        assertEquals("L", corpus.find("c4").orElseThrow().deletionTrack());
        assertNull(corpus.find("c1").orElseThrow().deletionTrack());
    }

    @Test void renderedEntriesHaveTheSharedSchemaInOrder() {
        JsonNode a = Json.parse(ContextBuilder.full(corpus, Instant.parse("2026-01-01T10:01:00Z")).json());
        assertEquals(2, a.size());
        List<String> fields = new java.util.ArrayList<>();
        a.get(1).fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("type", "id", "author", "sent_at", "reply_to", "forwarded", "edited", "text"), fields);
        assertEquals("message", a.get(1).get("type").asText());
        assertEquals("c1", a.get(1).get("reply_to").asText());
        assertTrue(a.get(0).get("reply_to").isNull());
    }

    @Test void newestWithinBudgetIsContiguousChronologicalAndFits() {
        Instant asOf = Instant.parse("2026-01-02T12:00:00Z");
        int full = ContextBuilder.full(corpus, asOf).chars();
        int one = ContextBuilder.entryChars(corpus.find("c5").orElseThrow());
        ContextBuilder.Built b = ContextBuilder.newestWithin(corpus, asOf, one + 2);
        assertEquals(List.of("c5"), b.ids());
        assertTrue(b.chars() <= one + 2);
        ContextBuilder.Built two = ContextBuilder.newestWithin(corpus, asOf, full - 1);
        assertEquals(List.of("c2", "c5"), two.ids(), "c3 and c4 are deleted; newest visible kept, oldest dropped");
        assertEquals(ContextBuilder.full(corpus, asOf).ids(), ContextBuilder.newestWithin(corpus, asOf, full).ids());
    }

    @Test void packDropsNonVisibleIdsAndRespectsBudget() {
        Instant asOf = Instant.parse("2026-01-02T12:00:00Z");
        ContextBuilder.Built b = ContextBuilder.pack(corpus, asOf, List.of("c6", "c3", "c5", "nope"), 0, 10_000);
        assertEquals(List.of("c5"), b.ids());
        assertEquals(List.of("c6", "c3", "nope"), b.droppedIds());
        ContextBuilder.Built tight = ContextBuilder.pack(corpus, asOf, List.of("c5", "c1"), 0, 50);
        assertTrue(tight.chars() <= 50);
    }

    @Test void neighbourWindowExpandsOverVisibleMessagesOnly() {
        Instant asOf = Instant.parse("2026-01-02T12:00:00Z");   // visible: c1, c2, c5
        assertEquals(List.of("c1", "c2", "c5"), ContextBuilder.pack(corpus, asOf, List.of("c5"), 2, 10_000).ids());
        assertEquals(List.of("c5"), ContextBuilder.pack(corpus, asOf, List.of("c5"), 0, 10_000).ids());
    }

    @Test void ofIdsFiltersByVisibility() {
        ContextBuilder.Built b = ContextBuilder.ofIds(corpus, Instant.parse("2026-01-02T12:00:00Z"), Set.of("c1", "c3", "c6"));
        assertEquals(List.of("c1"), b.ids());
        assertEquals(List.of("c3", "c6"), b.droppedIds());
    }

    @Test void corpusParsingIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> Corpus.parse("bad", "{\"id\":\"x\",\"author\":\"a\",\"sent_at\":\"2026-01-01T00:00:00Z\",\"text\":\"t\",\"phone\":\"1\"}"));
        assertThrows(IllegalArgumentException.class, () -> Corpus.parse("bad", line("x", "a", "2026-01-01T00:00:00Z", "zzz", "t", null)));
        assertThrows(IllegalArgumentException.class, () -> Corpus.parse("Bad_ID", line("x", "a", "2026-01-01T00:00:00Z", null, "t", null)));
    }
}
