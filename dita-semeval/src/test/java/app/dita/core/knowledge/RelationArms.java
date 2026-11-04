package app.dita.core.knowledge;

import app.dita.core.answer.InjectionGuard;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TASK-046 semantic evaluation (DEC-037), EXPERIMENT ONLY. Tony's design: arms PROPOSE relations for candidate pairs chosen
 * by Dita; a deterministic gate decides; ValidityReconciler remains the only validity writer. Nothing here is wired into
 * production.
 */
final class RelationArms {
    private RelationArms() {}

    enum Tri { YES, NO, UNCERTAIN }

    record Pair(KnowledgeItem later, KnowledgeItem earlier) {}

    record Proposal(Pair pair, Tri sameProperty, Tri replaces, List<String> evidenceQuotes, double confidence, String arm, long nanos) {}

    interface Arm {
        String id();
        Proposal propose(GroupScope g, MessageStore store, Pair p) throws Exception;
    }

    /** Candidate pairs, chosen by Dita (the production pre-filter): same speaker, same subject id, later in time, no QUESTION. */
    static List<Pair> candidates(List<KnowledgeItem> items) {
        List<KnowledgeItem> all = new ArrayList<>(items);
        all.removeIf(k -> k.assertion() == null || k.assertion().status() == Assertion.Status.QUESTION);
        all.sort(Comparator.comparing((KnowledgeItem k) -> k.assertion().statedAt()).thenComparing(KnowledgeItem::id));
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) for (int j = 0; j < i; j++) {
            Assertion l = all.get(i).assertion(), e = all.get(j).assertion();
            if (l.speakerId().equals(e.speakerId()) && l.subjectId().equals(e.subjectId()) && l.statedAt().isAfter(e.statedAt()))
                out.add(new Pair(all.get(i), all.get(j)));
        }
        return out;
    }

    /** The deterministic gate. Returns the rejection reason, or empty when a SUPERSEDES relation may be written. */
    static Optional<String> gate(GroupScope g, MessageStore store, Proposal p, double minConfidence) {
        if (p.replaces() != Tri.YES || p.sameProperty() != Tri.YES) return Optional.of("not YES/YES");
        Assertion l = p.pair().later().assertion(), e = p.pair().earlier().assertion();
        if (!l.speakerId().equals(e.speakerId()) || !l.subjectId().equals(e.subjectId()) || !l.statedAt().isAfter(e.statedAt()))
            return Optional.of("structure");
        if (!ValidityReconciler.canRetire(g, store, p.pair().later(), store.editedMessageIds(g))) return Optional.of("later not showable");
        if (l.certainty() == Assertion.Certainty.HEDGED || l.status() == Assertion.Status.REPORTED_CLAIM
                || l.qualifiers().stream().anyMatch(q -> q.kind() == Assertion.Qualifier.Kind.CONDITION) || p.pair().later().statementRedacted())
            return Optional.of("structural veto");
        if (p.evidenceQuotes().isEmpty()) return Optional.of("no evidence");
        Set<String> edited = store.editedMessageIds(g);
        for (String q : p.evidenceQuotes()) {
            boolean found = false;
            for (String mid : evidenceMessages(g, store, p.pair().later())) {
                Message m = store.find(g, mid).orElse(null);
                if (m == null || m.deleted() || m.text() == null || edited.contains(mid) || InjectionGuard.isInstructionToDita(m.text())) continue;
                if (Spans.find(m.text(), q) != null) { found = true; break; }
            }
            if (!found) return Optional.of("evidence not verbatim in the later message or its reply parent");
        }
        if (p.confidence() < minConfidence) return Optional.of("confidence");
        return Optional.empty();
    }

    private static List<String> evidenceMessages(GroupScope g, MessageStore store, KnowledgeItem later) {
        List<String> out = new ArrayList<>();
        for (KnowledgeItem.Evidence ev : later.asserting()) {
            out.add(ev.messageId());
            store.find(g, ev.messageId()).map(Message::replyToId).ifPresent(out::add);
        }
        return out;
    }

    /** Test-only baseline: every extracted assertion ACTIVE, exactly as validated, before any arm decides relations. */
    static void resetToValidatedBaseline(GroupScope g, MessageStore store) {
        for (KnowledgeItem k : store.knowledge(g)) {
            Assertion a = k.assertion();
            if (a == null || a.validity() == Assertion.Validity.ACTIVE) continue;
            if (a.validity() == Assertion.Validity.UNRESOLVED)
                throw new IllegalStateException("evaluation baseline carries withdrawal history: " + k.id());
            store.replaceKnowledge(g, k.withAssertion(new Assertion(a.status(), a.certainty(), a.speakerId(), a.attributedTo(),
                    a.subjectId(), a.subject(), a.predicate(), a.qualifiers(), a.entities(), a.statedAt(), Assertion.Validity.ACTIVE,
                    a.proposalVersion(), a.batchId())));
        }
    }

    /**
     * Replaces every SUPERSEDES relation by the gated proposals of {@code arm}, then lets the reconciler derive validity.
     * TASK-047 Codex F4 / T47-1: every arm starts from an UNBIASED baseline. The production rules ran during extraction and
     * retired items; those relations are removed AND their validity consequence is cleared in this throwaway store, so an
     * arm that rejects a supersession leaves both claims current. Only relation-retired (SUPERSEDED) items are reset; a
     * fixture never contains a withdrawal, and one with withdrawal history (UNRESOLVED) is refused rather than erased.
     * Production withdrawal behaviour is not changed for the evaluation.
     */
    static List<Proposal> apply(GroupScope g, MessageStore store, Arm arm, double minConfidence) throws Exception {
        store.removeRelations(g, store.relations(g).stream().filter(r -> r.kind() == KnowledgeRelation.Kind.SUPERSEDES)
                .map(KnowledgeRelation::key).toList());
        resetToValidatedBaseline(g, store);
        List<Proposal> ps = new ArrayList<>();
        for (Pair p : candidates(store.knowledge(g))) {
            Proposal pr = arm.propose(g, store, p);
            ps.add(pr);
            if (gate(g, store, pr, minConfidence).isEmpty()) {
                String ev = p.later().asserting().isEmpty() ? null : p.later().asserting().get(0).messageId();
                store.addRelation(g, new KnowledgeRelation(g.groupId(), p.later().id(), p.earlier().id(), KnowledgeRelation.Kind.SUPERSEDES, ev));
            }
        }
        ValidityReconciler.reconcile(store, g);
        return ps;
    }

    // ---------------- offline arms ----------------

    /** A1: the current rules (round-3 linguistic changes included; experimental baseline, DEC-037 amendment 4). */
    static final class Rules implements Arm {
        public String id() { return "A1-rules"; }
        public Proposal propose(GroupScope g, MessageStore store, Pair p) {
            long t = System.nanoTime();
            String text = fullText(g, store, p.later()), ctx = text + "\n" + parentText(g, store, p.later());
            boolean r = p.later().assertion().speakerId().equals(p.earlier().assertion().speakerId())
                    && RelationRules.supersedes(p.later(), p.earlier(), text, ctx, q -> qualifier(g, store, q));
            return new Proposal(p, r ? Tri.YES : Tri.UNCERTAIN, r ? Tri.YES : Tri.NO, List.of(p.later().statement()), 1.0, id(), System.nanoTime() - t);
        }
    }

    static String fullText(GroupScope g, MessageStore s, KnowledgeItem k) {
        return k.asserting().stream().map(e -> s.find(g, e.messageId()).filter(m -> !m.deleted()).map(Message::text).orElse(""))
                .findFirst().orElse("");
    }

    static String parentText(GroupScope g, MessageStore s, KnowledgeItem k) {
        return k.asserting().stream().map(e -> s.find(g, e.messageId()).map(Message::replyToId).flatMap(pid -> pid == null ? Optional.empty() : s.find(g, pid))
                .filter(m -> !m.deleted()).map(Message::text).orElse("")).findFirst().orElse("");
    }

    static String qualifier(GroupScope g, MessageStore s, Assertion.Qualifier q) {
        return s.find(g, q.messageId()).filter(m -> !m.deleted() && m.text() != null && q.spanEnd() <= m.text().length())
                .map(m -> m.text().substring(q.spanStart(), q.spanEnd())).orElse("");
    }

    /**
     * A4: local NLI (premise = earlier quote, hypothesis = later quote). Thresholds are chosen on DEV only and frozen before the
     * sealed run. contradiction ≥ τ_rep → replaces YES (and same property YES); entailment ≥ τ_same → same property YES,
     * replaces NO (restatement); neutral ≥ τ_diff → same property NO; anything else UNCERTAIN.
     */
    static final class Nli implements Arm {
        final NliModel model; final double tRep, tSame, tDiff;
        Nli(NliModel model, double tRep, double tSame, double tDiff) { this.model = model; this.tRep = tRep; this.tSame = tSame; this.tDiff = tDiff; }
        public String id() { return String.format("A4-nli(%.2f,%.2f,%.2f)", tRep, tSame, tDiff); }
        public Proposal propose(GroupScope g, MessageStore store, Pair p) throws Exception {
            long t = System.nanoTime();
            NliModel.Scores s = model.score(p.earlier().statement(), p.later().statement());
            Tri same, rep; double conf;
            if (s.contradiction() >= tRep) { same = Tri.YES; rep = Tri.YES; conf = s.contradiction(); }
            else if (s.entailment() >= tSame) { same = Tri.YES; rep = Tri.NO; conf = s.entailment(); }
            else if (s.neutral() >= tDiff) { same = Tri.NO; rep = Tri.NO; conf = s.neutral(); }
            else { same = Tri.UNCERTAIN; rep = Tri.UNCERTAIN; conf = Math.max(s.contradiction(), Math.max(s.entailment(), s.neutral())); }
            return new Proposal(p, same, rep, List.of(p.later().statement()), conf, id(), System.nanoTime() - t);
        }
    }
}
