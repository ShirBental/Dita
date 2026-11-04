package app.dita.core.config;

/**
 * Conversation-link inference. Scores are in [0,1]. Candidates scoring in [adjudicationLow, adjudicationHigh]
 * are marked {@code needs_adjudication} for a higher processing tier; nothing is ever promoted to FACT.
 */
public record LinkSettings(double candidateThreshold, double ambiguityMargin, int windowMessages,
                           double temporalHalfLifeMinutes, double adjudicationLow, double adjudicationHigh) {
    public static final LinkSettings DEFAULTS = new LinkSettings(0.35, 0.05, 50, 30.0, 0.30, 0.55);

    public LinkSettings {
        require(candidateThreshold > 0 && candidateThreshold < 1, "candidateThreshold must be in (0,1)");
        require(ambiguityMargin >= 0 && ambiguityMargin < 0.5, "ambiguityMargin must be in [0,0.5)");
        require(windowMessages >= 1 && windowMessages <= 1000, "windowMessages must be in [1,1000]");
        require(temporalHalfLifeMinutes > 0, "temporalHalfLifeMinutes must be > 0");
        require(adjudicationLow >= 0 && adjudicationLow <= adjudicationHigh && adjudicationHigh <= 1,
                "adjudication band must satisfy 0 <= low <= high <= 1");
    }

    static void require(boolean ok, String msg) { if (!ok) throw new IllegalArgumentException(msg); }
}
