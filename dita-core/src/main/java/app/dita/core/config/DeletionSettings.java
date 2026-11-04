package app.dita.core.config;

import java.time.Duration;

/**
 * Deletion behaviour within the product-owner policy (DEC-012/DEC-013). Not configurable (security invariants):
 * removal of source text and edit-history text in both modes, idempotency, no restoration through edits,
 * caches, indexes or projections, author-only /forget unless the admin option is enabled with verified admins.
 */
public record DeletionSettings(Duration earlyWindow, EarlyBehavior earlyBehavior, LateKnowledge lateKnowledge,
                               RepeatedWording repeatedWording, Duration groupRemovalRetention,
                               boolean adminsMayForgetOthers, DitaReplies ditaReplies) {
    /** The only implemented early behaviour (selective dependency invalidation). */
    public enum EarlyBehavior { REMOVE_CONTRIBUTION_AND_RECALCULATE }
    public enum LateKnowledge { PRESERVE_WITH_DELETED_SOURCE_PROVENANCE, REMOVE_CONTRIBUTION_AND_RECALCULATE }
    /** Derived wording that repeats deleted text is never kept verbatim; choose how to avoid it. */
    public enum RepeatedWording { REDACT_WORDING, REMOVE_ITEM }
    /** Dita never deletes its own published replies during ordinary deletion (C-11). REPORT lists affected replies. */
    public enum DitaReplies { KEEP, REPORT_AFFECTED }

    public static final Duration MIN_EARLY_WINDOW = Duration.ofMinutes(5);
    public static final Duration MAX_GROUP_RETENTION = Duration.ofDays(21);

    public static final DeletionSettings DEFAULTS = new DeletionSettings(Duration.ofMinutes(5),
            EarlyBehavior.REMOVE_CONTRIBUTION_AND_RECALCULATE, LateKnowledge.PRESERVE_WITH_DELETED_SOURCE_PROVENANCE,
            RepeatedWording.REDACT_WORDING, Duration.ofDays(21), false, DitaReplies.KEEP);

    public DeletionSettings {
        // N-26: the window may only be widened (more purging), never shortened below the product-owner policy.
        LinkSettings.require(earlyWindow != null && earlyWindow.compareTo(MIN_EARLY_WINDOW) >= 0 && earlyWindow.compareTo(Duration.ofHours(24)) <= 0,
                "earlyWindow must be in [5m, 24h]: shorter windows would weaken the early-deletion guarantee");
        LinkSettings.require(earlyBehavior != null && lateKnowledge != null && repeatedWording != null && ditaReplies != null,
                "deletion behaviours must be set");
        // N-26: retention after removal may be shortened, never extended beyond the agreed 21 days.
        LinkSettings.require(groupRemovalRetention != null && groupRemovalRetention.compareTo(Duration.ofDays(1)) >= 0
                && groupRemovalRetention.compareTo(MAX_GROUP_RETENTION) <= 0, "groupRemovalRetention must be in [1d, 21d]"); 
    }
}
