package app.dita.core.deletion;

import app.dita.core.config.DeletionSettings;
import app.dita.core.config.DeletionSettings.LateKnowledge;
import app.dita.core.config.DeletionSettings.RepeatedWording;
import app.dita.core.events.EventPublisher;
import app.dita.core.link.LinkInferencer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.store.MessageStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the deletion policy and selective dependency invalidation. All truth changes plus the outbox event happen
 * in one {@link MessageStore#atomically} unit; projections follow asynchronously from the event.
 *
 * <p>When contribution removal applies (always for EARLY_PURGE; for LATE only if configured):
 * <ol>
 *   <li>Inferred and explicit links whose evidence used the message are removed.</li>
 *   <li>Links of other live messages that relied on it are recalculated by re-running inference without it.</li>
 *   <li>Knowledge loses the message's evidence and any provisional evidence through removed links; the claim
 *       survives iff at least one independent VERIFIED source remains, else it is removed.</li>
 * </ol>
 * In LATE preserve mode the knowledge keeps the source as deleted-source provenance. In both modes, wording that
 * repeats any version of the deleted text is redacted or removed (never kept verbatim).
 */
public final class DeletionService {
    private final MessageStore store;
    private final LinkInferencer inferencer;
    private final DeletionSettings settings;
    private final EventPublisher publisher;
    private final int window;

    public DeletionService(MessageStore store, LinkInferencer inferencer, DeletionSettings settings,
                           EventPublisher publisher, int linkWindowMessages) {
        this.store = store; this.inferencer = inferencer; this.settings = settings;
        this.publisher = publisher; this.window = linkWindowMessages;
    }

    public DeletionSettings settings() { return settings; }

    public Optional<DeletionEvent> delete(GroupScope scope, String messageId, Instant signalledAt, DeletionSource source) {
        return store.atomically(scope, () -> {
            Message m = store.find(scope, messageId).orElseThrow(() -> new IllegalArgumentException("unknown message " + messageId));
            if (m.deleted()) return Optional.<DeletionEvent>empty();                          // idempotent
            Instant at = DeletionPolicy.clamp(m.sentAt(), signalledAt);
            DeletionMode mode = DeletionPolicy.classify(m.sentAt(), at, settings.earlyWindow());
            boolean removeContribution = mode == DeletionMode.EARLY_PURGE
                    || settings.lateKnowledge() == LateKnowledge.REMOVE_CONTRIBUTION_AND_RECALCULATE;

            List<String> versions = store.tombstone(scope, messageId, at, mode, source);

            Set<String> dependentLinks = store.links(scope).stream().filter(l -> l.dependsOn(messageId))
                    .map(MessageLink::key).collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> removedLinks = new ArrayList<>(), addedLinks = new ArrayList<>();
            if (removeContribution) {
                Set<String> affectedFrom = store.links(scope).stream()
                        .filter(l -> dependentLinks.contains(l.key()) && !l.fromId().equals(messageId))
                        .map(MessageLink::fromId).collect(Collectors.toCollection(LinkedHashSet::new));
                store.removeLinks(scope, dependentLinks);
                removedLinks.addAll(dependentLinks);
                for (String f : affectedFrom) {
                    Message fm = store.find(scope, f).orElse(null);
                    if (fm == null || fm.deleted()) continue;
                    Set<String> existing = store.links(scope).stream().map(MessageLink::key).collect(Collectors.toSet());
                    for (MessageLink nl : inferencer.infer(scope, fm, store.windowBefore(scope, f, window), store.links(scope))) {
                        if (nl.dependsOn(messageId) || existing.contains(nl.key())) continue;
                        store.addLink(scope, nl);
                        addedLinks.add(nl.key());
                    }
                }
            }

            List<String> removedK = new ArrayList<>(), updatedK = new ArrayList<>(), redactedK = new ArrayList<>(), preservedK = new ArrayList<>();
            List<app.dita.core.model.KnowledgeRelation> relationsBefore = relationsOrEmpty(scope);   // the cascade removes some
            for (KnowledgeItem k : store.knowledge(scope)) {
                // TASK-046 (N-104): a reply's item whose subject/entity came only from the deleted PARENT. Early purge removes
                // the parent's contribution (the item goes); late preserve redacts the subject if it repeats deleted wording.
                if (!k.dependsOn(messageId, dependentLinks) && subjectFromParent(scope, k, messageId)) {
                    if (removeContribution) { store.removeKnowledge(scope, k.id()); removedK.add(k.id()); }
                    else if (!k.statementRedacted() && versions.stream().anyMatch(v -> TextOverlap.repeatsPhrase(k.subject(), v))) {
                        store.replaceKnowledge(scope, k.redacted(true)); redactedK.add(k.id()); updatedK.add(k.id());
                    }
                    continue;
                }
                if (!k.dependsOn(messageId, dependentLinks)) continue;
                KnowledgeItem next;
                if (removeContribution) {
                    List<KnowledgeItem.Evidence> remaining = k.evidenceWithout(messageId, dependentLinks);
                    boolean assertionLost = k.assertion() != null                                // RFC-002: the speaker's own words must remain
                            && remaining.stream().noneMatch(e -> e.role() == KnowledgeItem.Role.ASSERTS && e.hasSpan());
                    if (assertionLost || remaining.stream().noneMatch(e -> e.basis() == KnowledgeItem.Basis.VERIFIED)) {
                        store.removeKnowledge(scope, k.id());                                  // no valid support left
                        removedK.add(k.id());
                        continue;
                    }
                    KnowledgeItem kept = k.withEvidence(remaining);
                    next = kept.assertion() == null ? kept                                   // qualifier spans into the purged message go too
                            : kept.withAssertion(kept.assertion().withQualifiers(kept.assertion().qualifiers().stream()
                                    .filter(q -> !q.messageId().equals(messageId)).toList()));
                } else {
                    next = k.withSourceDeleted(messageId);
                    preservedK.add(k.id());
                }
                boolean subjectRepeats = versions.stream().anyMatch(v -> TextOverlap.repeatsPhrase(next.subject(), v));
                if (!next.statementRedacted() && (subjectRepeats || versions.stream().anyMatch(v -> TextOverlap.repeats(next.statement(), v)))) {
                    if (settings.repeatedWording() == RepeatedWording.REMOVE_ITEM) {
                        store.removeKnowledge(scope, k.id());
                        removedK.add(k.id());
                        preservedK.remove(k.id());
                        continue;
                    }
                    store.replaceKnowledge(scope, next.redacted(subjectRepeats));
                    redactedK.add(k.id());
                } else {
                    store.replaceKnowledge(scope, next);
                }
                updatedK.add(k.id());
            }

            // TASK-046 (Codex P4) + DEC-038 (N-110): validity is re-derived from the remaining SUPERSEDES relations. A claim
            // retired only by the deleted correction loses its relation and becomes UNRESOLVED, never current again.
            for (String id : app.dita.core.knowledge.ValidityReconciler.reconcile(store, scope, withdrawnTargets(relationsBefore, removedK))) {
                if (!removedK.contains(id) && !updatedK.contains(id) && store.knowledgeById(scope, id).isPresent()) updatedK.add(id);
            }

            List<String> replies = settings.ditaReplies() == DeletionSettings.DitaReplies.REPORT_AFFECTED
                    ? store.ditaRepliesCiting(scope, messageId) : List.of();
            DeletionEvent e = new DeletionEvent(scope.groupId(), messageId, mode, source, m.sentAt(), at,
                    removedK, updatedK, redactedK, preservedK, removedLinks, addedLinks, replies);
            publisher.publish(scope.groupId(), e);                                            // same unit as the truth change
            return Optional.of(e);
        });
    }

    /** What a message edit invalidated (ids only). {@code affectedReplies}: Dita replies that cited it (REPORT_AFFECTED). */
    public record EditOutcome(String messageId, List<String> removedKnowledgeIds, List<String> updatedKnowledgeIds,
                              List<String> removedRelationKeys, List<String> affectedReplies) {}

    /**
     * TASK-046 (Codex P2): applies a message edit and invalidates everything derived from the OLD revision, in one atomic
     * unit with the outbox events. Knowledge is extracted only from never-edited messages (E-2), so after an edit:
     * <ol>
     *   <li>every knowledge item with evidence or a qualifier span in the edited message, or provisional evidence through a
     *       link that depends on it, is REMOVED (its spans index the old revision and its statement repeats old wording;
     *       it is never re-pointed at the new text);</li>
     *   <li>relations established by the edited message, and SUPERSEDES relations whose referent check read it as a reply
     *       parent, are removed (uncertain → no relation);</li>
     *   <li>validity is re-derived ({@link app.dita.core.knowledge.ValidityReconciler}): DEC-038, a claim retired only by
     *       removed knowledge or a dropped relation becomes UNRESOLVED, never current again (fail-closed; whether an
     *       uncertain reply-parent edit should instead restore it is an open product-owner question, T47-2).</li>
     * </ol>
     * The new revision is not re-extracted (edited messages are never sent to the model); the message path answers from
     * the live text. Deleted messages cannot be edited (store contract). Items whose SUBJECT or an entity was verified only in
     * the edited reply parent are removed too (Codex C3). Known limit: inferred links are not recomputed; on DELETION of a
     * reply parent such items are still kept (N-104). A backend without relations (no V5) still applies the edit (N-103).
     */
    public EditOutcome edit(GroupScope scope, String messageId, String newText, Instant at) {
        return store.atomically(scope, () -> {
            store.edit(scope, messageId, newText, at);
            Set<String> dependentLinks = store.links(scope).stream().filter(l -> l.dependsOn(messageId))
                    .map(MessageLink::key).collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> removedK = new ArrayList<>();
            List<app.dita.core.model.KnowledgeRelation> relationsBefore = relationsOrEmpty(scope);    // N-103: empty without V5
            for (KnowledgeItem k : store.knowledge(scope)) {
                boolean qualifier = k.assertion() != null && k.assertion().qualifiers().stream().anyMatch(q -> q.messageId().equals(messageId));
                if (k.dependsOn(messageId, dependentLinks) || qualifier || subjectFromParent(scope, k, messageId)) {
                    store.removeKnowledge(scope, k.id()); removedK.add(k.id());
                }
            }
            List<app.dita.core.model.KnowledgeRelation> relations;
            try { relations = store.relations(scope); } catch (UnsupportedOperationException e) { relations = List.of(); }   // N-103
            java.util.Map<String, KnowledgeItem> byId = new java.util.HashMap<>();
            store.knowledge(scope).forEach(k -> byId.put(k.id(), k));
            List<app.dita.core.model.KnowledgeRelation> dropped = new ArrayList<>();
            for (var r : relations) {
                boolean byEdited = messageId.equals(r.evidenceMessageId());
                KnowledgeItem from = byId.get(r.fromId());
                boolean parentRead = r.kind() == app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES && from != null
                        && from.asserting().stream().anyMatch(e -> store.find(scope, e.messageId())
                                .map(m -> messageId.equals(m.replyToId())).orElse(false));
                if (!(byEdited || parentRead)) continue;
                // Codex TASK-047 F5: keep a SUPERSEDES relation that still holds WITHOUT the edited parent (the existing rule,
                // re-run on the later item's own message only); drop only relations the parent contributed to
                if (r.kind() == app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES && from != null && byId.containsKey(r.toId())
                        && from.sourceMessageIds().stream().noneMatch(messageId::equals)
                        && holdsWithoutMessage(scope, from, byId.get(r.toId()), messageId, r.key())) continue;
                dropped.add(r);
            }
            if (!dropped.isEmpty()) store.removeRelations(scope, dropped.stream().map(app.dita.core.model.KnowledgeRelation::key).toList());
            Set<String> withdrawn = new java.util.HashSet<>(withdrawnTargets(relationsBefore, removedK));
            for (var r : dropped)                    // uncertain → no relation, and fail-closed: treated as a withdrawal (T47-2, PO question)
                if (r.kind() == app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES) withdrawn.add(r.toId());
            List<String> updatedK = new ArrayList<>();
            for (String id : app.dita.core.knowledge.ValidityReconciler.reconcile(store, scope, withdrawn))
                if (!removedK.contains(id) && byId.containsKey(id)) updatedK.add(id);
            for (var r : dropped)                                                            // both ends' edges are rebuilt
                for (String end : List.of(r.fromId(), r.toId())) if (byId.containsKey(end) && !updatedK.contains(end)) updatedK.add(end);
            List<String> replies = settings.ditaReplies() == DeletionSettings.DitaReplies.REPORT_AFFECTED
                    ? store.ditaRepliesCiting(scope, messageId) : List.of();
            publisher.publish(scope.groupId(), new app.dita.core.events.Refs.MessageRef(scope.groupId(), messageId));
            for (String id : removedK) publisher.publish(scope.groupId(), new app.dita.core.events.Refs.KnowledgeRef(scope.groupId(), id));
            for (String id : updatedK) publisher.publish(scope.groupId(), new app.dita.core.events.Refs.KnowledgeRef(scope.groupId(), id));
            return new EditOutcome(messageId, List.copyOf(removedK), List.copyOf(updatedK),
                    dropped.stream().map(app.dita.core.model.KnowledgeRelation::key).toList(), List.copyOf(replies));
        });
    }

    private List<app.dita.core.model.KnowledgeRelation> relationsOrEmpty(GroupScope scope) {
        try { return store.relations(scope); } catch (UnsupportedOperationException e) { return List.of(); }
    }

    /** DEC-039: targets of SUPERSEDES relations whose superseding knowledge this unit removed (cascade) = withdrawals. */
    private static Set<String> withdrawnTargets(List<app.dita.core.model.KnowledgeRelation> before, List<String> removedK) {
        Set<String> out = new java.util.HashSet<>();
        for (var r : before)
            if (r.kind() == app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES && removedK.contains(r.fromId())) out.add(r.toId());
        return out;
    }

    /**
     * Codex TASK-047 F5: does the existing {@link app.dita.core.knowledge.RelationRules} decision still hold when the
     * edited message is not read at all (the later item's own live message is its quote AND its context)? No new wording
     * rule: the same rule, with less context.
     */
    private boolean holdsWithoutMessage(GroupScope scope, KnowledgeItem later, KnowledgeItem earlier, String editedId, String key) {
        java.util.function.Function<KnowledgeItem, String> own = k -> k.asserting().stream()
                .map(e -> store.find(scope, e.messageId()).orElse(null))
                .filter(m -> m != null && !m.deleted() && m.text() != null && !m.id().equals(editedId))
                .map(Message::text).findFirst().orElse("");
        java.util.function.Function<app.dita.core.model.Assertion.Qualifier, String> qualifier = q -> q.messageId().equals(editedId) ? ""
                : store.find(scope, q.messageId()).filter(m -> !m.deleted() && m.text() != null && q.spanEnd() <= m.text().length())
                        .map(m -> m.text().substring(q.spanStart(), q.spanEnd())).orElse("");
        var outcome = app.dita.core.knowledge.RelationRules.compute(scope.groupId(), List.of(earlier), List.of(later), own, qualifier, own);
        return outcome.relations().stream().anyMatch(x -> x.key().equals(key));
    }

    /**
     * Codex review C3 / N-104 (edits): the item's asserting message replies to {@code parentId} and its subject (or an
     * entity surface) does not occur in the asserting messages themselves, so it was verified against the parent's OLD text.
     */
    private boolean subjectFromParent(GroupScope scope, KnowledgeItem k, String parentId) {
        if (k.assertion() == null) return false;
        List<Message> own = k.asserting().stream().map(e -> store.find(scope, e.messageId()).orElse(null))
                .filter(m -> m != null && !m.deleted() && m.text() != null).toList();
        if (own.stream().noneMatch(m -> parentId.equals(m.replyToId()))) return false;
        java.util.function.Predicate<String> inOwn = surface -> surface == null || own.stream().anyMatch(m ->
                m.text().toLowerCase(java.util.Locale.ROOT).contains(surface.strip().toLowerCase(java.util.Locale.ROOT)));
        return !inOwn.test(k.assertion().subject()) || k.assertion().entities().stream().anyMatch(en -> !inOwn.test(en.surface()));
    }

    /**
     * Fallback deletion signal ({@code /forget} as a reply). {@code commandAt} must be the command message's own
     * platform timestamp. Anonymous/proxy senders are refused. Only the author may forget a message, unless the
     * system administrator enabled {@code adminsMayForgetOthers} and the requester is a platform-verified admin.
     */
    public Optional<DeletionEvent> forget(GroupScope scope, Requester requester, String targetMessageId, Instant commandAt) {
        scope.requireSame(requester.groupId());                                              // N-25: identity is per group
        if (requester.anonymousOrProxy()) throw new SecurityException("anonymous or proxy senders cannot use /forget");
        Message m = store.find(scope, targetMessageId)
                .orElseThrow(() -> new IllegalArgumentException("message not in this group's memory"));
        boolean author = m.authorId().equals(requester.userId());
        boolean admin = settings.adminsMayForgetOthers() && requester.adminVerifiedAt(commandAt, Requester.ADMIN_FRESHNESS);
        if (!author && !admin) throw new SecurityException("only the author can ask Dita to forget this message");
        return delete(scope, targetMessageId, commandAt, DeletionSource.FORGET_COMMAND);
    }
}
