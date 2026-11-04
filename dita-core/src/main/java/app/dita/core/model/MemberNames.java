package app.dita.core.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author id → display name for Dita's own replies (in the group the message came from). Empty by default, so every
 * caller that never records a name sees the id exactly as before. Names are never sent to the model: the synthesis
 * pseudonymiser replaces display names and ids with P&lt;n&gt; in everything that leaves the machine.
 * Process memory only (bounded); an author not seen since start is shown by id.
 */
public final class MemberNames {   // deliberately NOT a Function bean (would clash with ditaEnv injection)
    private static final int MAX_ENTRIES = 1000, MAX_CHARS = 40;
    private final Map<String, String> names = new ConcurrentHashMap<>();

    /** Records the display name of {@code authorId}; unusable names (blank, too short, pseudonym-like) are ignored. */
    public void observe(String authorId, String raw) {
        if (authorId == null || raw == null) return;
        String n = clean(raw);
        if (n == null) return;
        if (names.size() >= MAX_ENTRIES && !names.containsKey(authorId)) return;
        names.put(authorId, n);
    }

    /** Control/format characters removed, whitespace collapsed, brackets dropped (no fake [citation] markers), capped. */
    static String clean(String raw) {
        String n = raw.replaceAll("[\\p{C}\\[\\]]", "").replaceAll("\\s+", " ").strip();
        if (n.length() > MAX_CHARS) n = n.substring(0, MAX_CHARS).strip();
        if (n.codePointCount(0, n.length()) < 2) return null;
        if (n.matches("(?i)P\\d+") || n.matches("\\d+")) return null;   // would collide with pseudonyms or look like an id
        return n;
    }

    /** The display name, or the id when none is known. */
    public String name(String authorId) {
        return authorId == null ? null : names.getOrDefault(authorId, authorId);
    }
}
