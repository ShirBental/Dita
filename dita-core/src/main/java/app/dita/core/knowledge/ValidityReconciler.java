package app.dita.core.knowledge;

import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.store.MessageStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TASK-046 (Codex P4) + DEC-038 + DEC-039: validity is DERIVED from relations plus the item's own withdrawal history.
 * <ul>
 *   <li>a SUPERSEDES relation exists only while its superseding item can still be shown (it exists, has a live asserting
 *       source, is not redacted and was not derived from a since-edited message); otherwise it is removed as a
 *       WITHDRAWAL (N-98: such an item never retires anything);</li>
 *   <li>ACTIVE ⇒ no live SUPERSEDES relation targets it; a live relation ⇒ never ACTIVE;</li>
 *   <li>DEC-038 (N-110): a WITHDRAWN correction (its knowledge deleted early or late, its message edited, redacted, or a
 *       relation dropped as uncertain) never makes the older claim current again: the older claim becomes UNRESOLVED;</li>
 *   <li>DEC-039 (Codex TASK-047 F3 / T47-3): UNRESOLVED is the persistent withdrawal history and is STICKY: a later live
 *       relation does not turn it SUPERSEDED, and nothing turns it ACTIVE (only a new claim is current). Therefore, on a store
 *       that can hold UNRESOLVED, SUPERSEDED means "retired only by relations, never by a withdrawal", and a relation
 *       REJECTED as an invalid inference ({@link #rejectRelations}) can restore it to ACTIVE exactly, without weakening
 *       withdrawal protection. Relations removed with no stated reason ({@link #reconcile}) are treated as withdrawals
 *       (fail-closed).</li>
 * </ul>
 * A store that cannot persist UNRESOLVED (PostgreSQL without V6) keeps such items SUPERSEDED: never current; a rejection
 * cannot tell them apart from relation-only retirement there, so it stays SUPERSEDED (fail-closed) until V6.
 * No historical rows are recomputed: items reactivated before DEC-038 stay ACTIVE (known limitation).
 * The ONLY writer of validity (T46-2). Must run inside the caller's {@link MessageStore#atomically} unit. Returns the ids
 * whose stored state changed, so the caller publishes them to the projections.
 */
public final class ValidityReconciler {
    private ValidityReconciler() {}

    /** Re-derives validity; relations that disappeared without a stated reason count as withdrawals (fail-closed). */
    public static Set<String> reconcile(MessageStore store, GroupScope scope) {
        return derive(store, scope, Set.of(), Set.of());
    }

    /**
     * Deletion/edit path: {@code withdrawnTargets} are the targets of SUPERSEDES relations whose superseding knowledge
     * the caller removed or invalidated in this unit (the cascade removes those relations before reconciliation, so the
     * caller names them). Each becomes UNRESOLVED even if another relation still retires it (fan-in), so the withdrawal is
     * never forgotten.
     */
    public static Set<String> reconcile(MessageStore store, GroupScope scope, Set<String> withdrawnTargets) {
        return derive(store, scope, withdrawnTargets, Set.of());
    }

    /**
     * DEC-039 reason-aware repair (Codex F3 / T47-3, Tony R8): removes the given SUPERSEDES relations as REJECTED
     * inferences (the relation should never have existed; no correction was withdrawn). A target retired only by rejected
     * relations returns to ACTIVE; one with withdrawal history (UNRESOLVED) or another live relation stays retired.
     */
    public static Set<String> rejectRelations(MessageStore store, GroupScope scope, java.util.Collection<String> relationKeys) {
        Set<String> keys = new HashSet<>(relationKeys);
        Set<String> rejectedTargets = new HashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        List<String> remove = new ArrayList<>();
        for (KnowledgeRelation r : store.relations(scope)) {
            if (r.kind() != KnowledgeRelation.Kind.SUPERSEDES || !keys.contains(r.key())) continue;
            rejectedTargets.add(r.toId()); remove.add(r.key());
            touched.add(r.fromId()); touched.add(r.toId());
        }
        if (!remove.isEmpty()) store.removeRelations(scope, remove);
        touched.addAll(derive(store, scope, Set.of(), rejectedTargets));
        return touched;                                                       // both ends' graph edges changed
    }

    private static Set<String> derive(MessageStore store, GroupScope scope, Set<String> withdrawnTargets, Set<String> rejectedTargets) {
        Map<String, KnowledgeItem> byId = new HashMap<>();
        for (KnowledgeItem k : store.knowledge(scope)) byId.put(k.id(), k);
        List<KnowledgeRelation> relations;
        try { relations = store.relations(scope); } catch (UnsupportedOperationException e) { return Set.of(); }
        Set<String> edited = store.editedMessageIds(scope);
        Set<String> changed = new LinkedHashSet<>();
        List<String> dead = new ArrayList<>();
        Set<String> retired = new HashSet<>();
        Set<String> withdrawn = new HashSet<>(withdrawnTargets);
        for (KnowledgeRelation r : relations) {
            if (r.kind() != KnowledgeRelation.Kind.SUPERSEDES) continue;
            KnowledgeItem from = byId.get(r.fromId());
            if (from == null || !byId.containsKey(r.toId()) || !canRetire(scope, store, from, edited)) {
                dead.add(r.key());
                withdrawn.add(r.toId());                                       // the superseder can no longer be shown
                changed.add(r.fromId()); changed.add(r.toId());
            } else {
                retired.add(r.toId());
            }
        }
        if (!dead.isEmpty()) store.removeRelations(scope, dead);
        boolean unresolvedStorable = store.supportsUnresolvedValidity();
        for (KnowledgeItem k : byId.values()) {
            Assertion a = k.assertion();
            if (a == null) continue;
            Assertion.Validity target = target(a.validity(), retired.contains(k.id()), unresolvedStorable,
                    withdrawn.contains(k.id()), rejectedTargets.contains(k.id()));
            if (target == a.validity()) continue;
            store.replaceKnowledge(scope, k.withAssertion(switch (target) {
                case SUPERSEDED -> a.superseded();
                case UNRESOLVED -> a.unresolved();
                case ACTIVE -> a.restoredAfterRejectedRelation();
            }));
            changed.add(k.id());
        }
        return changed;
    }

    /** DEC-038 view (no stated reason): kept for the state-machine tests. */
    static Assertion.Validity target(Assertion.Validity current, boolean hasLiveSupersession, boolean unresolvedStorable) {
        return target(current, hasLiveSupersession, unresolvedStorable, false, false);
    }

    /**
     * The validity state machine (DEC-038 + DEC-039). ACTIVE is returned for a non-ACTIVE item ONLY when every relation
     * that retired it was rejected (never withdrawn) and the store can record withdrawal history. Package-visible for tests.
     */
    static Assertion.Validity target(Assertion.Validity current, boolean hasLiveSupersession, boolean unresolvedStorable,
                                     boolean withdrawn, boolean rejected) {
        if (current == Assertion.Validity.UNRESOLVED) return Assertion.Validity.UNRESOLVED;                 // sticky history
        if (withdrawn && current != Assertion.Validity.ACTIVE && unresolvedStorable) return Assertion.Validity.UNRESOLVED;
        if (hasLiveSupersession) return Assertion.Validity.SUPERSEDED;
        if (current == Assertion.Validity.ACTIVE) return Assertion.Validity.ACTIVE;
        if (rejected && !withdrawn && unresolvedStorable) return Assertion.Validity.ACTIVE;                // only relation-retired
        return unresolvedStorable ? Assertion.Validity.UNRESOLVED : Assertion.Validity.SUPERSEDED;
    }

    /** Showable: not redacted, a live asserting source, and NOT derived from a message edited since (B46-11 / N-105). */
    static boolean canRetire(GroupScope scope, MessageStore store, KnowledgeItem k, Set<String> edited) {
        if (k.statementRedacted() || k.onlyDeletedSourcesRemain()) return false;
        if (k.sourceMessageIds().stream().anyMatch(edited::contains)) return false;
        if (k.assertion() != null && k.assertion().qualifiers().stream().anyMatch(q -> edited.contains(q.messageId()))) return false;
        return k.asserting().stream().anyMatch(e -> !e.sourceDeleted()
                && store.find(scope, e.messageId()).filter(m -> !m.deleted() && m.text() != null).isPresent());
    }
}
