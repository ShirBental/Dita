package app.dita.core.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieval unit derived from messages, with per-source provenance.
 * <p>Support semantics (selective dependency invalidation):
 * <ul>
 *   <li>Independent support = distinct source <em>messages</em> with VERIFIED basis. Several evidence records
 *       derived from the same message count once.</li>
 *   <li>PROVISIONAL evidence (reached only through an inferred CANDIDATE link) is kept separately, never counts
 *       as support, and can never keep a claim alive on its own.</li>
 *   <li>A source deleted late (policy rule 2) stays as {@code sourceDeleted} provenance and still counts, because
 *       the policy preserves previously derived knowledge.</li>
 * </ul>
 */
public record KnowledgeItem(String id, String groupId, Kind kind, String subject, String statement, int polarity,
                            MessageLink.Method method, List<Evidence> evidence, boolean statementRedacted,
                            Assertion assertion) {
    public enum Kind { RECOMMENDATION, CLAIM, DECISION, COMMITMENT, QUESTION, CORRECTION }
    public enum Basis { VERIFIED, PROVISIONAL }

    /** RFC-002 evidence role: the speaker's own words, a qualification of them, or the target of a correction. */
    public enum Role { ASSERTS, QUALIFIES, CORRECTION_TARGET }

    /**
     * One evidence record. {@code viaLinkKey} is set for PROVISIONAL evidence and names the inferred link.
     * {@code spanStart/spanEnd} (RFC-002): the exact supporting quote as a character span of the message text, computed
     * by Dita (never copied from the model); -1/-1 for legacy rule-derived items. Quotes are rendered from the LIVE
     * message, so a deleted source can never be quoted again.
     */
    public record Evidence(String messageId, Basis basis, String viaLinkKey, boolean sourceDeleted, Role role, int spanStart, int spanEnd) {
        public Evidence {
            Objects.requireNonNull(messageId); Objects.requireNonNull(basis); Objects.requireNonNull(role);
            if (basis == Basis.PROVISIONAL && viaLinkKey == null) throw new IllegalArgumentException("provisional evidence needs its link");
            if (basis == Basis.VERIFIED && viaLinkKey != null) throw new IllegalArgumentException("verified evidence is direct");
            if ((spanStart < 0) != (spanEnd < 0) || (spanStart >= 0 && spanEnd <= spanStart)) throw new IllegalArgumentException("invalid span");
        }
        public Evidence(String messageId, Basis basis, String viaLinkKey, boolean sourceDeleted) {
            this(messageId, basis, viaLinkKey, sourceDeleted, Role.ASSERTS, -1, -1);
        }
        public static Evidence verified(String messageId) { return new Evidence(messageId, Basis.VERIFIED, null, false); }
        public static Evidence provisional(String messageId, String linkKey) { return new Evidence(messageId, Basis.PROVISIONAL, linkKey, false); }
        public static Evidence span(String messageId, Role role, int start, int end) { return new Evidence(messageId, Basis.VERIFIED, null, false, role, start, end); }
        public boolean hasSpan() { return spanStart >= 0; }
        Evidence markDeleted() { return new Evidence(messageId, basis, viaLinkKey, true, role, spanStart, spanEnd); }
    }

    public static final String REDACTED = "[wording withheld: it repeated a deleted message]";

    public KnowledgeItem {
        Objects.requireNonNull(id); Objects.requireNonNull(groupId); Objects.requireNonNull(kind);
        Objects.requireNonNull(statement);
        Objects.requireNonNull(method, "method");
        evidence = List.copyOf(evidence);
        if (evidence.stream().noneMatch(e -> e.basis() == Basis.VERIFIED)) {
            throw new IllegalArgumentException("knowledge needs at least one verified source (provenance)");
        }
        if (assertion != null) {
            if (method != MessageLink.Method.LLM && method != MessageLink.Method.RULE) throw new IllegalArgumentException("assertions are proposed by RULE or LLM");
            if (evidence.stream().noneMatch(e -> e.role() == Role.ASSERTS && e.hasSpan())) {
                throw new IllegalArgumentException("an assertion needs >= 1 ASSERTS evidence span (RFC-002)");
            }
        }
    }

    /** Legacy (pre-RFC-002) items: no assertion metadata. */
    public KnowledgeItem(String id, String groupId, Kind kind, String subject, String statement, int polarity,
                         MessageLink.Method method, List<Evidence> evidence, boolean statementRedacted) {
        this(id, groupId, kind, subject, statement, polarity, method, evidence, statementRedacted, null);
    }

    public KnowledgeItem withAssertion(Assertion a) {
        return new KnowledgeItem(id, groupId, kind, subject, statement, polarity, method, evidence, statementRedacted, a);
    }

    /** Evidence records that assert the statement (the speaker's own words), live or deleted. */
    public List<Evidence> asserting() { return evidence.stream().filter(e -> e.role() == Role.ASSERTS).toList(); }

    public static KnowledgeItem of(String id, String groupId, Kind kind, String subject, String statement, int polarity,
                                   MessageLink.Method method, List<String> verifiedSources) {
        return new KnowledgeItem(id, groupId, kind, subject, statement, polarity, method,
                verifiedSources.stream().map(Evidence::verified).toList(), false);
    }

    public KnowledgeItem withProvisional(String messageId, String linkKey) {
        List<Evidence> e = new ArrayList<>(evidence);
        e.add(Evidence.provisional(messageId, linkKey));
        return new KnowledgeItem(id, groupId, kind, subject, statement, polarity, method, e, statementRedacted, assertion);
    }

    /** True if any evidence record uses the message directly, or provisionally through a link that depends on it. */
    public boolean dependsOn(String messageId, Set<String> linkKeysDependingOnMessage) {
        return evidence.stream().anyMatch(e -> e.messageId().equals(messageId)
                || (e.viaLinkKey() != null && linkKeysDependingOnMessage.contains(e.viaLinkKey())));
    }

    /** Distinct messages with VERIFIED basis (deleted-late sources included; see class doc). */
    public Set<String> independentSupport() {
        return evidence.stream().filter(e -> e.basis() == Basis.VERIFIED).map(Evidence::messageId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Removes every contribution of {@code messageId} and every provisional record via an invalidated link. */
    public List<Evidence> evidenceWithout(String messageId, Set<String> invalidatedLinkKeys) {
        return evidence.stream().filter(e -> !e.messageId().equals(messageId)
                && (e.viaLinkKey() == null || !invalidatedLinkKeys.contains(e.viaLinkKey()))).toList();
    }

    public KnowledgeItem withEvidence(List<Evidence> newEvidence) {
        return new KnowledgeItem(id, groupId, kind, subject, statement, polarity, method, newEvidence, statementRedacted, assertion);
    }

    public KnowledgeItem withSourceDeleted(String messageId) {
        return withEvidence(evidence.stream().map(e -> e.messageId().equals(messageId) ? e.markDeleted() : e).toList());
    }

    /** Replaces the wording; the subject is withheld too when it repeats deleted phrasing (N-24). */
    public KnowledgeItem redacted(boolean redactSubject) {
        return new KnowledgeItem(id, groupId, kind, redactSubject ? null : subject, REDACTED, polarity, method, evidence, true, assertion);
    }

    public List<String> sourceMessageIds() { return evidence.stream().map(Evidence::messageId).distinct().toList(); }
    public Set<String> deletedSourceIds() {
        return evidence.stream().filter(Evidence::sourceDeleted).map(Evidence::messageId).collect(Collectors.toSet());
    }
    public boolean hasDeletedSource() { return evidence.stream().anyMatch(Evidence::sourceDeleted); }

    /** N-23: preserved under the late-deletion rule, but no live verified source is left. */
    public boolean onlyDeletedSourcesRemain() {
        return evidence.stream().filter(e -> e.basis() == Basis.VERIFIED).allMatch(Evidence::sourceDeleted);
    }

    @Override public String toString() {   // N-17: statements may repeat message text
        return "KnowledgeItem[id=" + id + ", groupId=" + groupId + ", kind=" + kind
                + (assertion == null ? "" : ", status=" + assertion.status() + ", validity=" + assertion.validity()) + ", sources=" + sourceMessageIds()
                + ", deletedSources=" + deletedSourceIds() + ", redacted=" + statementRedacted + ", statement=<redacted>]";
    }
}
