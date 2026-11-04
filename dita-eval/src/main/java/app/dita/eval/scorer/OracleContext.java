package app.dita.eval.scorer;

import app.dita.eval.context.ContextBuilder;
import app.dita.eval.corpus.Corpus;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * O-gold oracle (DESIGN §1): gold ∪ acceptable messages only, through the same visibility filter as every arm. It is
 * the ONLY component that reads sealed gold to build a context, and it lives on the scorer side, never in the runner.
 * Its retrieval recall is 1.0 by construction; it is a sanity row and the future reasoning-ceiling arm.
 */
public final class OracleContext {
    public static final String LABEL = "O-gold";

    private OracleContext() {}

    public static ContextBuilder.Built build(GoldLabel g, Corpus corpus) {
        Set<String> ids = new LinkedHashSet<>(g.goldIds());
        ids.addAll(g.acceptableExtraIds());
        return ContextBuilder.ofIds(corpus, g.asOf(), ids);
    }
}
