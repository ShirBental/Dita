package app.dita.core.knowledge;

/**
 * Locates a model-supplied quote in the original message text and returns Dita's own character span. Matching
 * tolerates only whitespace runs and typographic/Hebrew quote marks; anything else must be verbatim.
 */
final class Spans {
    private Spans() {}

    static char canon(char c) {
        return switch (c) {
            case '“', '”', '״' -> '"';
            case '‘', '’', '׳' -> '\'';
            default -> c;
        };
    }

    /** Canonical form used for comparison (whitespace collapsed, quote marks unified, case kept). */
    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        boolean ws = false;
        for (char c : (s == null ? "" : s).strip().toCharArray()) {
            if (Character.isWhitespace(c)) { if (!ws) b.append(' '); ws = true; }
            else { b.append(canon(c)); ws = false; }
        }
        return b.toString();
    }

    /** {start, end} into {@code text}, or null if the quote does not occur (after canonicalisation). */
    static int[] find(String text, String quote) {
        if (text == null || quote == null) return null;
        String q = norm(quote);
        if (q.isEmpty()) return null;
        int exact = text.indexOf(quote.strip());
        if (exact >= 0) return new int[]{exact, exact + quote.strip().length()};
        StringBuilder n = new StringBuilder();
        int[] map = new int[text.length() + 1];
        boolean ws = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { if (ws) continue; map[n.length()] = i; n.append(' '); ws = true; }
            else { map[n.length()] = i; n.append(canon(c)); ws = false; }
        }
        int at = n.indexOf(q);
        if (at < 0) return null;
        int start = map[at], endIdx = at + q.length() - 1;
        return new int[]{start, map[endIdx] + 1};
    }

    static boolean containsIgnoreCase(String text, String part) {
        if (text == null || part == null || part.isBlank()) return false;
        return norm(text).toLowerCase(java.util.Locale.ROOT).contains(norm(part).toLowerCase(java.util.Locale.ROOT));
    }
}
