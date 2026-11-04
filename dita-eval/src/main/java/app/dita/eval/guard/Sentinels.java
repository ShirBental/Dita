package app.dita.eval.guard;

import app.dita.core.text.Tokenizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * SHA-256 hashes of sealed strings, so the runner-side guard can detect them without holding the plaintext. Built on
 * the scorer side by {@code scorer.SentinelSealer}. Strings are normalised with {@link Tokenizer#rawWords} and matched
 * as word n-grams (n &lt;= {@link #MAX_N}).
 */
public record Sentinels(Set<String> canaryHashes, Set<String> answerHashes) {
    public static final int MAX_N = 6;
    public static final Sentinels NONE = new Sentinels(Set.of(), Set.of());

    public Sentinels { canaryHashes = Set.copyOf(canaryHashes); answerHashes = Set.copyOf(answerHashes); }

    public static String normalize(String s) { return String.join(" ", Tokenizer.rawWords(s)); }

    public static String hash(String s) { return sha256(normalize(s)); }

    /** Hashes of every word n-gram (1..MAX_N) of {@code text}, normalised the same way as {@link #hash}. */
    public static Set<String> ngramHashes(String text) {
        List<String> w = Tokenizer.rawWords(text);
        Set<String> out = new HashSet<>();
        for (int i = 0; i < w.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int n = 0; n < MAX_N && i + n < w.size(); n++) {
                if (n > 0) sb.append(' ');
                sb.append(w.get(i + n));
                out.add(sha256(sb.toString()));
            }
        }
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
