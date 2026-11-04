package app.dita.core.deletion;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HEURISTIC detector for derived knowledge that repeats deleted source text (verbatim containment, or a shared
 * run of six consecutive words). It misses paraphrase, translation and very short sources (N-12): a false result
 * means "not detected", never "no residue". Matches are preserved per policy but flagged (deletion-policy.md C-3).
 */
public final class TextOverlap {
    private static final int SHINGLE = 6;

    private TextOverlap() {}

    /** Phrase-level only (six-word runs); used for short fields such as subjects, where entity names are not wording. */
    public static boolean repeatsPhrase(String field, String source) {
        if (field == null || source == null) return false;
        java.util.Set<String> sh = shingles(norm(source));
        return shingles(norm(field)).stream().anyMatch(sh::contains);
    }

    public static boolean repeats(String statement, String source) {
        if (statement == null || source == null) return false;
        String a = norm(statement), b = norm(source);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.contains(b) || b.contains(a)) return true;
        Set<String> sh = shingles(b);
        return shingles(a).stream().anyMatch(sh::contains);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Set<String> shingles(String s) {
        List<String> w = List.of(s.split(" "));
        Set<String> out = new HashSet<>();
        for (int i = 0; i + SHINGLE <= w.size(); i++) out.add(String.join(" ", w.subList(i, i + SHINGLE)));
        return out;
    }
}
