package app.dita.app;

import app.dita.core.text.Tokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-052 stage 3 regression (offline). The dense acceptance relies on m7 being a genuine low-lexical-overlap
 * paraphrase of m1. "No shared word" is false under Vespa's type=any (function words such as "is"/"for" match), so the
 * premise is pinned on CONTENT terms, with Dita's own tokenizer: m7 shares none with m1 or with m1's stored statement,
 * while the positive control m6 does share content terms. A fixture edit that breaks the premise fails here first.
 */
class ReplayFixtureLexicalControlTest {
    static final String M1_STATEMENT = "Dr. Levi on Herzl St. is excellent for babies";   // asserted by TelegramReplayAcceptance

    static Map<Integer, String> fixtureTexts() throws Exception {
        Map<Integer, String> out = new HashMap<>();
        ObjectMapper json = new ObjectMapper();
        try (var in = ReplayFixtureLexicalControlTest.class.getResourceAsStream("/replay/parents-levi.replay.jsonl");
             var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) continue;
                JsonNode n = json.readTree(line);
                if (n.has("message")) out.put(n.path("message").path("message_id").asInt(), n.path("message").path("text").asText());
            }
        }
        return out;
    }

    static Set<String> shared(String a, String b) {
        Set<String> s = new LinkedHashSet<>(Tokenizer.normalizedTokens(a));
        s.retainAll(new HashSet<>(Tokenizer.normalizedTokens(b)));
        return s;
    }

    @Test void m7SharesNoContentTermWithM1OrItsStatement() throws Exception {
        var t = fixtureTexts();
        String m7 = t.get(7).replaceFirst("^/ask\\s+", "");
        assertEquals("Which doctor is good for infants?", m7, "the acceptance test hard-codes this question");
        assertEquals(Set.of(), shared(m7, t.get(1)), "m7 vs m1 message text");
        assertEquals(Set.of(), shared(m7, M1_STATEMENT), "m7 vs m1's stored statement");
    }

    @Test void theFullQuestionDoesShareFunctionWordsWhichIsWhyTheOldControlWasInvalid() throws Exception {
        String m7 = fixtureTexts().get(7).toLowerCase();
        String m1 = M1_STATEMENT.toLowerCase();
        for (String w : new String[]{"is", "for"}) assertTrue(m7.matches(".*\\b" + w + "\\b.*") && m1.matches(".*\\b" + w + "\\b.*"), w);
    }

    @Test void positiveControlM6DoesShareContentTermsWithM1() throws Exception {
        var t = fixtureTexts();
        assertFalse(shared(t.get(6).replaceFirst("^/ask\\s+", ""), t.get(1)).isEmpty(), "m6 is the lexical control question");
    }
}
