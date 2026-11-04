package app.dita.core.config;

import java.time.Duration;

/**
 * Proactive clarification: when a new message's best link candidates are too close to call, Dita may ask which
 * thread it belongs to. Off by default. Rate-limited per group.
 */
public record ClarificationSettings(boolean enabled, double minCandidateScore, double maxScoreGap,
                                    int maxPerGroupPerWindow, Duration rateWindow) {
    public static final ClarificationSettings DEFAULTS = new ClarificationSettings(false, 0.35, 0.05, 2, Duration.ofHours(1));

    public ClarificationSettings {
        LinkSettings.require(minCandidateScore > 0 && minCandidateScore < 1, "minCandidateScore must be in (0,1)");
        LinkSettings.require(maxScoreGap >= 0 && maxScoreGap < 0.5, "maxScoreGap must be in [0,0.5)");
        LinkSettings.require(maxPerGroupPerWindow >= 0 && maxPerGroupPerWindow <= 60, "maxPerGroupPerWindow must be in [0,60]");
        LinkSettings.require(rateWindow != null && rateWindow.compareTo(Duration.ofMinutes(1)) >= 0, "rateWindow must be >= 1m");
    }
}
