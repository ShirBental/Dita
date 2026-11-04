package app.dita.eval.scorer;

import app.dita.eval.guard.Sentinels;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Scorer-side: turns sealed gold into {@link Sentinels} (hashes only) for the runner's CanaryGuard, so the runner never
 * holds gold plaintext. Answer strings shorter than 2 normalised characters are skipped (they would match ordinary
 * prompt text); canaries are always included.
 */
public final class SentinelSealer {
    private SentinelSealer() {}

    public static Sentinels seal(Collection<GoldLabel> gold) {
        Set<String> canaries = new HashSet<>(), answers = new HashSet<>();
        for (GoldLabel g : gold) {
            canaries.add(Sentinels.hash(g.canary()));
            g.answerKeys().forEach(set -> set.forEach(s -> add(answers, s)));
            g.forbiddenStrings().forEach(s -> add(answers, s));
        }
        return new Sentinels(canaries, answers);
    }

    private static void add(Set<String> out, String s) {
        if (Sentinels.normalize(s).length() >= 2) out.add(Sentinels.hash(s));
    }
}
