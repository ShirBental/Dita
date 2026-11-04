package app.dita.eval.cost;

import app.dita.eval.Json;
import app.dita.eval.answer.AnswerPrompt;

import java.util.List;

/**
 * ESTIMATED chars-to-tokens RANGES (COST-DESIGN §4). No tokenizer for muse-spark-1.3 is available offline, so every
 * figure here is a range, never a point measurement:
 * <ul>
 *   <li>Hebrew-script characters: 1.5–3.0 chars/token;</li>
 *   <li>other prose characters (English, digits, punctuation): 3.5–4.5 chars/token;</li>
 *   <li>JSON envelope (keys, quotes, ids, timestamps, escapes): 3–4 chars/token.</li>
 * </ul>
 * Each text is split by its own Hebrew share, so a mixed item lands inside the design's mixed range (2.0–4.5). The
 * only calibration point is run 5 (about 4.4 request bytes per prompt token, envelope included; n=1). Chat-template
 * tokens added by the server per message are unknown and not counted.
 */
public final class TokenEstimator {
    public static final double HE_CPT_LO = 1.5, HE_CPT_HI = 3.0;
    public static final double EN_CPT_LO = 3.5, EN_CPT_HI = 4.5;
    public static final double JSON_CPT_LO = 3.0, JSON_CPT_HI = 4.0;

    /** Token range: {@code lo} assumes the most chars per token, {@code hi} the fewest. Always ESTIMATED. */
    public record TokenRange(double lo, double hi) {
        public TokenRange { if (lo < 0 || hi < lo) throw new IllegalArgumentException("bad token range"); }
        public static final TokenRange ZERO = new TokenRange(0, 0);
        public TokenRange plus(TokenRange o) { return new TokenRange(lo + o.lo, hi + o.hi); }
        public TokenRange times(double k) { return new TokenRange(lo * k, hi * k); }
        public double mid() { return (lo + hi) / 2; }
        public Provenance provenance() { return Provenance.ESTIMATED; }
    }

    /** The fixed system prompt plus schema (S), split into prose and schema JSON; sizes MEASURED_OFFLINE from AnswerPrompt. */
    public record FixedPrompt(int totalChars, int proseChars, int schemaChars, TokenRange tokens) {}

    private TokenEstimator() {}

    public static boolean isHebrew(char c) { return (c >= '֐' && c <= '׿') || (c >= 'יִ' && c <= 'ﭏ'); }

    public static int hebrewChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (isHebrew(s.charAt(i))) n++;
        return n;
    }

    /** Prose: Hebrew share at 1.5–3.0, the rest at 3.5–4.5. */
    public static TokenRange prose(String s) {
        int he = hebrewChars(s), other = s.length() - he;
        return new TokenRange(he / HE_CPT_HI + other / EN_CPT_HI, he / HE_CPT_LO + other / EN_CPT_LO);
    }

    public static TokenRange envelope(int chars) {
        if (chars < 0) throw new IllegalArgumentException("envelope chars must be >= 0");
        return new TokenRange(chars / JSON_CPT_HI, chars / JSON_CPT_LO);
    }

    /**
     * A rendered context JSON array: the message texts are prose (each by its own Hebrew share); everything else in
     * {@code contextJson} (keys, ids, timestamps, quotes and escapes) is envelope.
     */
    public static TokenRange context(String contextJson, List<String> messageTexts) {
        TokenRange t = TokenRange.ZERO;
        int textChars = 0;
        for (String m : messageTexts) { t = t.plus(prose(m)); textChars += m.length(); }
        int env = contextJson.length() - textChars;
        if (env < 0) throw new IllegalArgumentException("message texts longer than the context JSON");
        return t.plus(envelope(env));
    }

    /** S, measured offline from {@link AnswerPrompt}: system prompt + empty user template; the schema JSON counts as envelope. */
    public static FixedPrompt fixedPrompt() {
        String scaffold = AnswerPrompt.scaffold();
        int schema = Json.compact(AnswerPrompt.OUTPUT_SCHEMA).length();
        if (!AnswerPrompt.SYSTEM.endsWith(Json.compact(AnswerPrompt.OUTPUT_SCHEMA))) throw new IllegalStateException("schema is no longer the system prompt suffix");
        String prose = scaffold.replace(Json.compact(AnswerPrompt.OUTPUT_SCHEMA), "");
        return new FixedPrompt(scaffold.length(), prose.length(), schema, prose(prose).plus(envelope(schema)));
    }
}
