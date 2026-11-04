package app.dita.eval.cost;

import app.dita.eval.Json;
import app.dita.eval.answer.AnswerPrompt;
import app.dita.eval.cost.TokenEstimator.TokenRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ESTIMATED chars-to-token ranges (COST-DESIGN §4). Hebrew 1.5–3.0, other prose 3.5–4.5, JSON envelope 3–4 chars/token. */
class TokenEstimatorTest {
    static final double TOL = 1e-9;

    @Test void englishProse() {
        TokenRange t = TokenEstimator.prose("abcdefghi");          // 9 chars: 9/4.5 = 2, 9/3.5 = 2.5714
        assertEquals(2.0, t.lo(), TOL);
        assertEquals(9 / 3.5, t.hi(), TOL);
        assertEquals(Provenance.ESTIMATED, t.provenance());
    }

    @Test void hebrewProse() {
        TokenRange t = TokenEstimator.prose("שלוםשלום");            // 8 Hebrew chars: 8/3 = 2.6667, 8/1.5 = 5.3333
        assertEquals(8 / 3.0, t.lo(), TOL);
        assertEquals(8 / 1.5, t.hi(), TOL);
        assertEquals(8, TokenEstimator.hebrewChars("שלוםשלום"));
    }

    @Test void mixedItemUsesItsOwnHebrewShareAndLandsInTheMixedRange() {
        // "ab" + "של": lo = 2/3 + 2/4.5 = 1.1111, hi = 2/1.5 + 2/3.5 = 1.9048; chars/token 4/1.1111 = 3.6 .. 4/1.9048 = 2.1
        TokenRange t = TokenEstimator.prose("abשל");
        assertEquals(2 / 3.0 + 2 / 4.5, t.lo(), TOL);
        assertEquals(2 / 1.5 + 2 / 3.5, t.hi(), TOL);
        assertTrue(4 / t.lo() <= 4.5 && 4 / t.hi() >= 2.0);
    }

    @Test void contextSplitsMessageTextFromEnvelope() {
        String json = "[{\"text\":\"abcdefghi\"}]";                // 22 chars; text 9; envelope 13 -> 3.25 .. 4.3333
        assertEquals(22, json.length());
        TokenRange t = TokenEstimator.context(json, List.of("abcdefghi"));
        assertEquals(2.0 + 13 / 4.0, t.lo(), TOL);
        assertEquals(9 / 3.5 + 13 / 3.0, t.hi(), TOL);
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.context("[]", List.of("longer than the json")));
    }

    @Test void fixedPromptSIsMeasuredFromAnswerPrompt() {
        TokenEstimator.FixedPrompt s = TokenEstimator.fixedPrompt();
        assertEquals(AnswerPrompt.scaffold().length(), s.totalChars());
        assertEquals(Json.compact(AnswerPrompt.OUTPUT_SCHEMA).length(), s.schemaChars());
        assertEquals(s.totalChars(), s.proseChars() + s.schemaChars());
        assertEquals(s.proseChars() / 4.5 + s.schemaChars() / 4.0, s.tokens().lo(), TOL);
        assertEquals(s.proseChars() / 3.5 + s.schemaChars() / 3.0, s.tokens().hi(), TOL);
    }
}
