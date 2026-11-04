package app.dita.core.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic EN/HE tokenizer. Hebrew: strips niqqud and also emits a variant with one common
 * prefix letter removed (ו ה ב ל מ ש כ) to favour recall. English: lowercases and strips plural "s".
 */
public final class Tokenizer {
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "for", "to", "of", "in", "on", "and", "or",
            "but", "with", "my", "he", "she", "him", "her", "it", "its", "we", "i", "you", "they", "that", "this",
            "have", "has", "do", "does", "any", "anyone", "also", "now", "so", "if", "at", "by", "from", "our",
            "us", "can", "who", "what", "about", "there", "near",
            "של", "את", "זה", "זאת", "על", "עם", "גם", "כן", "אני", "הוא", "היא", "יש", "מה", "מי", "אם", "כי",
            "או", "אבל", "רק", "לי", "לך", "שלי", "אנחנו", "הם", "הן");
    private static final java.util.regex.Pattern HYPHENATED = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])([a-z]{2,})-([a-z]{2,})(?![\\p{L}\\p{N}])");
    private static final String HEBREW_PREFIXES = "והבלמשכ";

    private Tokenizer() {}

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : rawWords(text)) {
            if (raw.length() < 2 || STOP.contains(raw)) continue;
            out.add(raw);
            if (isHebrew(raw) && raw.length() >= 4 && HEBREW_PREFIXES.indexOf(raw.charAt(0)) >= 0) {
                out.add(raw.substring(1));
            } else if (!isHebrew(raw) && raw.length() > 3 && raw.endsWith("s") && !raw.endsWith("ss")) {
                out.add(raw.substring(0, raw.length() - 1));
            }
        }
        return out;
    }

    public static Set<String> tokenSet(String text) { return new LinkedHashSet<>(tokens(text)); }

    /**
     * TASK-045: tokens plus a conservative English base form, so "booked"/"booking"/"book", "decided"/"decide",
     * "activities"/"activity" and "prefers"/"preferred"/"prefer" meet. Used by the KNOWLEDGE path only (message
     * retrieval and the benchmark baselines are unchanged). Hebrew tokens are left as {@link #tokens} makes them.
     */
    public static List<String> normalizedTokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : tokens(text)) { out.add(t); if (!isHebrew(t)) { String b = base(t); if (!b.equals(t)) out.add(b); } }
        if (text != null) {                                                     // B-R1: "kick-off" also meets "kickoff"
            var m = HYPHENATED.matcher(text.toLowerCase(Locale.ROOT));
            while (m.find()) { String j = m.group(1) + m.group(2); out.add(j); String b = base(j); if (!b.equals(j)) out.add(b); }
        }
        return out;
    }

    public static Set<String> normalizedSet(String text) { return new LinkedHashSet<>(normalizedTokens(text)); }

    public static String base(String w) {
        String s = w;
        if (s.length() > 4 && s.endsWith("ies")) s = s.substring(0, s.length() - 3) + "y";
        else if (s.length() > 4 && s.endsWith("ied")) s = s.substring(0, s.length() - 3) + "y";
        else if (s.length() > 5 && s.endsWith("ing")) s = undouble(s.substring(0, s.length() - 3));
        else if (s.length() > 4 && s.endsWith("ed")) s = undouble(s.substring(0, s.length() - 2));
        else if (s.length() > 4 && s.endsWith("es") && (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("ches") || s.endsWith("shes"))) s = s.substring(0, s.length() - 2);
        else if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.substring(0, s.length() - 1);
        if (s.length() > 4 && s.endsWith("e")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String undouble(String s) {
        int n = s.length();
        return n > 3 && s.charAt(n - 1) == s.charAt(n - 2) && "bdfglmnprt".indexOf(s.charAt(n - 1)) >= 0 ? s.substring(0, n - 1) : s;
    }

    /** Lowercased words including stopwords; quotes inside words (ד"ר, we've) are kept. */
    public static List<String> rawWords(String text) {
        if (text == null) return List.of();
        String norm = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\u0591-\\u05C7&&[^\\u05BE]]", "")
                .replace('״', '"').replace('׳', '\'').replace('’', '\'');
        List<String> out = new ArrayList<>();
        for (String w : norm.split("[^\\p{L}\\p{N}'\"]+")) {
            String t = w.replaceAll("^['\"]+|['\"]+$", "");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static boolean isHebrew(String s) {
        return s.codePoints().anyMatch(c -> c >= 0x05D0 && c <= 0x05EA);
    }

    /** Proper-noun-like tokens: capitalised Latin words not at text start, and Hebrew words after ד"ר. */
    public static Set<String> entities(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        String[] words = text.split("[^\\p{L}\\p{N}'\"״]+");
        boolean first = true;
        String prev = "";
        for (String w : words) {
            if (w.isEmpty()) continue;
            String clean = w.replaceAll("^['\"]+|['\"]+$", "");
            if (!first && !clean.isEmpty() && Character.isUpperCase(clean.charAt(0)) && clean.length() > 1
                    && !STOP.contains(clean.toLowerCase(Locale.ROOT))) {
                out.add(clean.toLowerCase(Locale.ROOT).replaceAll("'s$", ""));
            }
            String p = prev.replace('״', '"');
            if (p.equals("ד\"ר") || p.equals("דר")) out.add(clean);
            prev = clean;
            first = false;
        }
        out.remove("dr");
        return out;
    }
}
