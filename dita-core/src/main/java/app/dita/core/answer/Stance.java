package app.dita.core.answer;

import java.util.List;
import java.util.Locale;

/** Deterministic recommendation stance cues (EN/HE). Muse may refine; rules remain the baseline. */
public final class Stance {
    private static final List<String> NEGATIVE = List.of("avoid", "don't go", "do not go", "not recommend",
            "wouldn't recommend", "can't recommend", "can’t recommend", "cannot recommend", "worst", "terrible", "rough with", "dismissive", "לא ממליץ", "לא ממליצה", "לא מומלץ", "גרוע", "להימנע", "תתרחקו");
    private static final List<String> POSITIVE = List.of("recommend", "excellent", "great", "best", "amazing", "wonderful",
            "ממליץ", "ממליצה", "מומלץ", "מעולה", "מצוין", "מדהים");
    private static final List<String> QUALIFIERS = List.of(" only ", " but ", " except", " unless", " though",
            " אבל ", " רק ", " חוץ מ", " בתנאי");

    private Stance() {}

    /** +1 recommends, -1 advises against, 0 neutral. Negative cues are checked first ("not recommend"). */
    /** N-97 / B-V1: "could not recommend X more (highly)" is emphatic praise; bounded, clause-final; treated as neutral. */
    static final java.util.regex.Pattern PRAISE_IDIOM = java.util.regex.Pattern.compile(
            "\\b(could|can)(\\s+not|n['’]t)\\s+(recommend|praise)\\b[^.;!?\\n]{0,40}?(?<!\\bany\\s)(?<!\\bno\\s)\\b(more|enough)\\b(\\s+(highly|strongly))?\\s*[.!]?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public static int polarity(String text) {
        if (text == null) return 0;
        if (PRAISE_IDIOM.matcher(text.strip()).find()) return 0;
        String t = text.toLowerCase(Locale.ROOT);
        if (NEGATIVE.stream().anyMatch(t::contains)) return -1;
        if (POSITIVE.stream().anyMatch(t::contains)) return 1;
        return 0;
    }

    /**
     * TASK-054 G2: end index (in {@code lowerClause}) of the earliest stance cue, or -1. Used to attach a "for …"
     * context only to what the stance is about ("excellent for babies"), not to any "for" in the sentence ("for years").
     */
    static int cueEnd(String lowerClause) {
        int bestStart = Integer.MAX_VALUE, end = -1;
        for (List<String> cues : List.of(NEGATIVE, POSITIVE))
            for (String c : cues) {
                int i = lowerClause.indexOf(c);
                if (i >= 0 && i < bestStart) { bestStart = i; end = i + c.length(); }
            }
        return end;
    }

    public static boolean qualified(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        return QUALIFIERS.stream().anyMatch(t::contains);
    }
}
