package app.dita.core.knowledge;

import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.store.MessageStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Arm A2 (EXPERIMENT ONLY): Fury's same-frame rule, frozen as proposed on 2026-10-31, plus the explicit stance-reversal path
 * Bruce required (design case C8). Same property iff the frames are equal (the later frame is read up to a justification).
 * Replacement:
 * <ul>
 *   <li>offer → confirmed commitment with the same frame;</li>
 *   <li>a decision with the same frame that shares a value type (or has a non-empty frame);</li>
 *   <li>decision → decision, or explicit change wording, with the same frame and a changed value of the same type;</li>
 *   <li>a reversed stance (recommends ↔ advises against) whose earlier frame is contained in the later one;</li>
 *   <li>the speaker withdrawing their own offer or commitment (retraction wording naming the subject).</li>
 * </ul>
 * The production vetoes are kept: hypothetical later claim, restatement wording, and continuity wording on the weak paths.
 * Different frames → same property NO. Equal frames without replacement evidence → same property YES, replaces NO.
 */
final class FrameArm implements RelationArms.Arm {
    public String id() { return "A2-frame"; }

    public RelationArms.Proposal propose(GroupScope g, MessageStore store, RelationArms.Pair p) {
        long t = System.nanoTime();
        var later = p.later(); var earlier = p.earlier();
        Assertion l = later.assertion(), e = earlier.assertion();
        String text = RelationArms.fullText(g, store, later);
        String clause = RelationRules.quotedClause(later, text);
        java.util.function.Function<Assertion.Qualifier, String> qt = q -> RelationArms.qualifier(g, store, q);
        Set<String> subject = PropertyFrame.subjectTokens(earlier, later);
        PropertyFrame fe = PropertyFrame.ofEarlier(earlier, subject, qt), fl = PropertyFrame.ofLater(later, clause, subject, qt);
        RelationArms.Tri same = fe.sameFrameAs(fl) && (fe.informative() || fl.informative()) ? RelationArms.Tri.YES : RelationArms.Tri.NO;
        boolean hypothetical = l.certainty() == Assertion.Certainty.HEDGED
                || l.qualifiers().stream().anyMatch(q -> q.kind() == Assertion.Qualifier.Kind.CONDITION)
                || RelationRules.containsAny(clause, RelationRules.HYPOTHETICAL_MARKERS);
        boolean restatement = RelationRules.containsAny(clause, RelationRules.RESTATEMENT_MARKERS);
        boolean continuity = RelationRules.containsAny(clause, RelationRules.CONTINUITY_MARKERS);
        boolean change = RelationRules.hasChangeWording(clause);
        boolean replaces = false;
        if (!hypothetical && !restatement && l.speakerId().equals(e.speakerId())) {
            boolean equal = same == RelationArms.Tri.YES;
            if (l.status() == Assertion.Status.CONFIRMED_COMMITMENT && e.status() == Assertion.Status.OFFER) replaces = equal;
            else if (l.status() == Assertion.Status.DECISION && e.status() != Assertion.Status.DECISION)
                replaces = equal && (fl.sharesValueType(fe) || !fe.rest.isEmpty());
            else if (equal && fl.replacesValueOf(fe) && (change || (l.status() == Assertion.Status.DECISION && e.status() == Assertion.Status.DECISION)) && !continuity)
                replaces = true;
            boolean reversal = later.polarity() != 0 && earlier.polarity() == -later.polarity();
            if (!replaces && reversal && !continuity && fl.rest.containsAll(fe.rest)) { replaces = true; same = RelationArms.Tri.YES; }
            boolean retraction = (e.status() == Assertion.Status.OFFER || e.status() == Assertion.Status.CONFIRMED_COMMITMENT)
                    && RelationRules.containsAny(clause, RelationRules.RETRACTION_MARKERS)
                    && !RelationRules.containsAny(clause, RelationRules.NON_RETRACTION_IDIOMS)
                    && RelationRules.mentionsSubject(text, e.subject());
            if (!replaces && retraction && !continuity) { replaces = true; same = RelationArms.Tri.YES; }
        }
        return new RelationArms.Proposal(p, same, replaces ? RelationArms.Tri.YES : RelationArms.Tri.NO,
                List.of(later.statement()), 1.0, id(), System.nanoTime() - t);
    }

    static Set<String> union(Set<String> a, Set<String> b) { Set<String> s = new HashSet<>(a); s.addAll(b); return s; }
}
