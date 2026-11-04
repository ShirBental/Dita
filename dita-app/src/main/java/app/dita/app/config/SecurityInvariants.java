package app.dita.app.config;

import java.util.List;

/**
 * Non-configurable security invariants. They are enforced in code (core and adapters), not read from configuration;
 * {@link DitaProperties} rejects unknown keys so none of them can be toggled. Documented in docs/configuration.md.
 */
public final class SecurityInvariants {
    private SecurityInvariants() {}

    public static final List<String> ALL = List.of(
            "GROUP_ISOLATION: every read/write is bound to one GroupScope; cross-group data never mixes (GroupScope, store, RLS)",
            "NO_PRIVATE_OR_FOREIGN_CONTEXTS: private chats and channels never get a scope (GroupDirectory)",
            "VERIFIED_IDENTITY: sender identity comes from authenticated platform data, never message text (Requester, adapter)",
            "SERVER_SIDE_AUTHORIZATION: /forget, approvals, admin checks are decided server-side (DeletionService, EventPlan)",
            "PROVENANCE_INTEGRITY: knowledge needs verified sources; inferred links are never promoted to facts (KnowledgeItem, MessageLink)",
            "NO_RESTORATION_OF_DELETED_TEXT: no edit, cache, index, projection or replay restores deleted text",
            "CONTENT_IS_DATA: user messages and retrieved documents cannot override system instructions or permissions",
            "EXPLICIT_APPROVAL_ONLY: preferences never count as approval; approvals bind to a plan revision",
            "NO_PURCHASES_OR_BOOKINGS: no capability exists",
            "LEGAL_AND_PLATFORM_REQUIREMENTS: configuration cannot override them; real-group onboarding stays disabled until reviewed (C-1)");
}
