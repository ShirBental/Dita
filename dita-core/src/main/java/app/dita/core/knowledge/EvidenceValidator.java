package app.dita.core.knowledge;

import app.dita.core.answer.Stance;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RFC-002 §3.4 promotion rules (deterministic). A proposed item becomes a {@link KnowledgeItem} only if ALL pass;
 * otherwise it is a rejection with a reason and is never stored or indexed.
 * <ol>
 *   <li>Evidence cites only this batch's target messages: live, same group, SYNTHETIC, never edited.</li>
 *   <li>Each quote is verbatim (whitespace/quote-mark tolerant) and >= 12 characters; Dita computes the span.</li>
 *   <li>One speaker: all asserting messages share one author, who becomes {@code speakerId} (the model's names are ignored).</li>
 *   <li>Stance: a quote's deterministic polarity must not contradict the predicate.</li>
 *   <li>Subject: its surface form occurs in an asserting message or that message's reply parent.</li>
 *   <li>Status guards (question mark, explicit decision/commitment wording); refinement statuses are not enabled yet.</li>
 *   <li>Hearsay: {@code attributedTo} occurs in the asserting message; certainty is then HEARSAY.</li>
 *   <li>Qualifiers: each is a verbatim span inside an asserting message.</li>
 * </ol>
 * The stored statement is the verbatim quote; Dita never stores the model's wording (K-1). Items are speech acts by
 * participants, never verified world facts.
 */
public final class EvidenceValidator {
    public static final int MIN_QUOTE_CHARS = 12;
    /** B46-6: "final" counts only in decision forms ("Final thought, let's…" / "המבחן הסופי" are not decisions). */
    static final List<String> DECISION_MARKERS = List.of("decision:", "decided", "final decision", "final:", "it's final", "it is final",
            "is final", "finalized", "finalised", "we will go with", "we're going with", "החלטה:", "החלטנו", "החלטה סופית", "זה סופי", "סופי:");
    /** B46-7: an OFFER needs offer wording (a model-labelled fact is not an offer). */
    static final List<String> OFFER_MARKERS = List.of("i can", "i could", "i'll", "i will", "i'd", "happy to", "glad to", "let me",
            "want me to", "shall i", "should i", "i'm able", "i am able", "i'm available", "available to", "i offer", "i'm happy",
            "אני יכול", "אני יכולה", "יכול להביא", "יכולה להביא", "אשמח", "אוכל", "אביא", "אני מציע", "אני מציעה");
    static final List<String> DEFERRAL_MARKERS = List.of("not decided", "haven't decided", "have not decided", "undecided",
            "still deciding", "no decision yet", "not yet decided", "decide later", "לא החלטנו", "עוד לא החלטנו", "טרם הוחלט");
    static final List<String> COMMITMENT_MARKERS = List.of("confirmed", "done", "booked", "i will", "i'll", "סגור", "אישרתי", "אני אביא");
    /** TASK-045: a negated commitment is not a commitment ("I'll not make it", "I won't bring it"). */
    static final List<String> NEGATED_COMMITMENT = List.of("i'll not", "i will not", "won't", "will not", "can't", "cannot", "can not",
            "couldn't", "could not", "wouldn't", "never", "not going to", "don't think i can", "no longer",
            "not make it", "not able", "unable", "לא אוכל", "לא אביא", "לא אגיע");
    /** TASK-045: hedged/relayed wording means a "decision" is not a decision ("might be", "maybe", "if"). */
    static final List<String> HYPOTHETICAL_DECISION = List.of("might", "maybe", "perhaps", "possibly", "if", "could be",
            "not sure", "probably", "try", "trying", "hope", "hopefully", "plan to", "planning to", "אולי", "אם", "כנראה", "אנסה", "מקווה");
    static final Set<Assertion.Status> ENABLED = Set.of(Assertion.Status.ATTRIBUTED_STATEMENT, Assertion.Status.REPORTED_CLAIM,
            Assertion.Status.OFFER, Assertion.Status.CONFIRMED_COMMITMENT, Assertion.Status.DECISION, Assertion.Status.QUESTION);

    public record Rejection(int item, String reason) {}
    public record Result(List<KnowledgeItem> accepted, List<Rejection> rejections) {}

    /**
     * @param targets  the batch's messages that may be cited as evidence (already loaded, same group)
     * @param parents  reply parents of the targets (subject check only; never evidence)
     * @param edited   ids of messages that were ever edited (E-2: never extracted)
     */
    public Result validate(GroupScope scope, String batchId, Proposal p, Map<String, Message> targets,
                           Map<String, Message> parents, Set<String> edited) {
        List<KnowledgeItem> ok = new ArrayList<>();
        List<Rejection> rej = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int n = 0;
        for (Proposal.Item it : p.items()) {
            n++;
            try {
                KnowledgeItem k = promote(scope, batchId, p.proposalVersion(), it, targets, parents, edited);
                if (seen.add(k.id())) ok.add(k); else rej.add(new Rejection(n, "duplicate of another proposed item"));
            } catch (Reject r) {
                rej.add(new Rejection(n, r.getMessage()));
            }
        }
        return new Result(List.copyOf(ok), List.copyOf(rej));
    }

    private static final class Reject extends RuntimeException {
        Reject(String reason) { super(reason, null, false, false); }
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String v, String what) {
        try { return Enum.valueOf(type, v == null ? "" : v.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new Reject("unknown " + what); }
    }

    KnowledgeItem promote(GroupScope scope, String batchId, String version, Proposal.Item it, Map<String, Message> targets,
                          Map<String, Message> parents, Set<String> edited) {
        Assertion.Status status = parse(Assertion.Status.class, it.status(), "status");
        if (!ENABLED.contains(status)) throw new Reject("status " + status + " is not enabled in this increment");
        String predicate = it.predicate() == null ? "" : it.predicate().strip().toLowerCase(Locale.ROOT);
        if (!Assertion.PREDICATES.contains(predicate)) throw new Reject("predicate outside the closed vocabulary");
        int polarity = switch (predicate) { case "recommends" -> 1; case "advises_against" -> -1; default -> 0; };
        if (it.polarity() != null && it.polarity() != polarity) throw new Reject("polarity disagrees with the predicate");
        if (it.evidence().isEmpty()) throw new Reject("no evidence quote");

        // rules 1-3: evidence messages, verbatim quotes, one speaker
        List<KnowledgeItem.Evidence> ev = new ArrayList<>();
        String speaker = null;
        Message primary = null;
        for (Proposal.Quote q : it.evidence()) {
            Message m = targets.get(q.messageId());
            if (m == null) throw new Reject("cites a message outside this batch");
            if (!m.groupId().equals(scope.groupId())) throw new Reject("cites another group");
            if (m.deleted() || m.text() == null) throw new Reject("cites a deleted message");
            if (m.sourceKind() != app.dita.core.model.SourceKind.SYNTHETIC) throw new Reject("cites a non-synthetic message");
            if (edited.contains(m.id())) throw new Reject("cites an edited message");
            if (app.dita.core.answer.InjectionGuard.isInstructionToDita(m.text())) throw new Reject("cites an instruction to Dita (prompt injection)");
            if (Spans.norm(q.quote()).length() < MIN_QUOTE_CHARS) throw new Reject("quote is too short");
            int[] span = Spans.find(m.text(), q.quote());
            if (span == null) throw new Reject("quote is not verbatim");
            if (speaker == null) { speaker = m.authorId(); primary = m; }
            else if (!speaker.equals(m.authorId())) throw new Reject("evidence from more than one speaker");
            String quoted = m.text().substring(span[0], span[1]);
            int stance = Stance.polarity(quoted);                                // N-97: praise idiom → neutral (in Stance)
            if (stance != 0 && polarity != 0 && stance != polarity) throw new Reject("quote stance contradicts the predicate");
            ev.add(KnowledgeItem.Evidence.span(m.id(), KnowledgeItem.Role.ASSERTS, span[0], span[1]));
        }
        List<Message> asserting = ev.stream().map(e -> targets.get(e.messageId())).distinct().toList();

        // rule 4: subject surface in an asserting message or its reply parent
        String subject = it.subject() == null ? "" : it.subject().strip();
        String subjectId = it.subjectId() == null ? "" : it.subjectId().strip().toLowerCase(Locale.ROOT);
        if (subject.isEmpty() || !subjectId.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{0,63}")) throw new Reject("missing subject or subject id");
        if (!occursNear(subject, asserting, parents)) throw new Reject("subject does not occur in the evidence");

        // rule 5: status guards
        String primaryQuote = primary.text().substring(ev.get(0).spanStart(), ev.get(0).spanEnd());
        if (status == Assertion.Status.QUESTION && !primaryQuote.contains("?")) throw new Reject("a question needs a question mark in the quote");
        // TASK-044 (DEC-030): a DECISION/CONFIRMED_COMMITMENT without the required wording is DOWNGRADED to what is verified
        // (the speaker said this, verbatim), not discarded: rejecting it lost real speech acts (e.g. "November 14 is no
        // longer an option"). The model's stronger label is never stored.
        String finalPredicate = predicate;
        // DEC-033 / TASK-045: deferral is judged on the CLAUSES that contain the quoted spans ("Decision: X; the menu we
        // decide later" is a decision about X); markers match on word boundaries ("finally" ≠ "final", "overbooked" ≠ "booked").
        String clauses = quotedClauses(ev, targets).toLowerCase(Locale.ROOT);
        String quotes = quotedText(ev, targets);
        boolean deferred = RelationRules.containsAny(clauses, DEFERRAL_MARKERS);
        // Codex review C2: affirmative AND disqualifying signals are judged on the SAME enclosing clause (with its terminal
        // punctuation), so trimming the quote can never drop a negation, a condition or a question mark:
        // "I will not bring the cake" quoted as "bring the cake", "If approved, I will bring…", "Decision: picnic Friday?"
        boolean hedged = RelationRules.containsAny(clauses, HYPOTHETICAL_DECISION)
                || Assertion.Certainty.HEDGED.name().equalsIgnoreCase(it.certainty() == null ? "" : it.certainty().strip());
        boolean negatedCommitment = RelationRules.containsAny(clauses, NEGATED_COMMITMENT);
        // TASK-046 (Codex P3): the decision/commitment wording must be in the clause(s) that hold the quote, not anywhere in
        // the message ("The final exam is on Monday. I suggest we meet at the library." is not a decision to meet there)
        // B46-6: a proposal or a question in the decision's own clause is not a decision ("Decision: picnic on Friday?")
        boolean proposal = RelationRules.containsAny(clauses, RelationRules.PROPOSAL_MARKERS) || clauses.contains("?");
        if (status == Assertion.Status.DECISION && (deferred || hedged || proposal || !RelationRules.containsAny(clauses, DECISION_MARKERS))) {
            status = Assertion.Status.ATTRIBUTED_STATEMENT;
        } else if (status == Assertion.Status.OFFER && (negatedCommitment || !RelationRules.containsAny(clauses, OFFER_MARKERS))) {
            status = Assertion.Status.ATTRIBUTED_STATEMENT;                              // B46-7; C2: "I can not bring…" offers nothing
        } else if (status == Assertion.Status.CONFIRMED_COMMITMENT) {
            if (negatedCommitment || !RelationRules.containsAny(clauses, COMMITMENT_MARKERS)) status = Assertion.Status.ATTRIBUTED_STATEMENT;
            else if (hedged) status = Assertion.Status.OFFER;                     // "I could pick up the cake" offers, it does not commit
        }
        // TASK-046 (Codex P3): status and predicate must agree; an inconsistent pair keeps only what the evidence supports
        // (never a stronger speech act than the status guards verified)
        Assertion.Status consistent = consistentStatus(status, predicate);
        status = consistent;
        finalPredicate = consistentPredicate(status, predicate);
        polarity = switch (finalPredicate) { case "recommends" -> 1; case "advises_against" -> -1; default -> 0; };

        // rule 6: certainty / hearsay
        Assertion.Certainty certainty = it.certainty() == null ? Assertion.Certainty.ASSERTED : parse(Assertion.Certainty.class, it.certainty(), "certainty");
        String attributedTo = it.attributedTo() == null || it.attributedTo().isBlank() ? null : it.attributedTo().strip();
        if (status == Assertion.Status.REPORTED_CLAIM || certainty == Assertion.Certainty.HEARSAY) {
            String source = attributedTo;
            if (source == null || asserting.stream().noneMatch(m -> Spans.containsIgnoreCase(m.text(), source))) {
                throw new Reject("hearsay source does not occur in the evidence");
            }
            status = Assertion.Status.REPORTED_CLAIM;
            certainty = Assertion.Certainty.HEARSAY;
        } else {
            attributedTo = null;
        }

        // rule 7: qualifiers are verbatim spans inside asserting messages
        List<Assertion.Qualifier> qualifiers = new ArrayList<>();
        for (Proposal.Qualifier q : it.qualifiers()) {
            Assertion.Qualifier.Kind kind = parse(Assertion.Qualifier.Kind.class, q.kind(), "qualifier kind");
            Message m = targets.get(q.messageId());
            if (m == null || asserting.stream().noneMatch(a -> a.id().equals(q.messageId()))) throw new Reject("qualifier outside the evidence");
            int[] span = Spans.find(m.text(), q.quote());
            if (span == null || Spans.norm(q.quote()).length() < 3) throw new Reject("qualifier is not verbatim");
            qualifiers.add(new Assertion.Qualifier(kind, m.id(), span[0], span[1]));
        }

        // entities: kept only if surface-verified (dropped otherwise; never invented)
        List<Assertion.Entity> entities = new ArrayList<>();
        for (Proposal.Entity e : it.entities()) {
            if (e.id() == null || e.surface() == null) continue;
            if (occursNear(e.surface(), asserting, parents)) entities.add(new Assertion.Entity(e.id().strip().toLowerCase(Locale.ROOT), e.surface().strip()));
        }

        Assertion a = new Assertion(status, certainty, speaker, attributedTo, subjectId, subject, finalPredicate, qualifiers,
                entities, primary.sentAt(), Assertion.Validity.ACTIVE, version, batchId);
        KnowledgeItem.Evidence first = ev.get(0);
        String id = "k-" + digest(scope.groupId(), first.messageId(), Integer.toString(first.spanStart()), Integer.toString(first.spanEnd()),
                status.name(), finalPredicate, subjectId);
        return new KnowledgeItem(id, scope.groupId(), legacyKind(status, finalPredicate), subject, primaryQuote, polarity,
                MessageLink.Method.LLM, ev, false, a);
    }

    /**
     * TASK-046: the strongest status the (status, predicate) pair supports. A speech-act status needs its own predicate
     * (DECISION↔decides, CONFIRMED_COMMITMENT↔commits, OFFER↔offers); a mismatch falls back to a weaker verified act:
     * a "commitment" labelled "offers" is an OFFER, an OFFER labelled "commits" stays an OFFER, anything else is a statement.
     */
    static Assertion.Status consistentStatus(Assertion.Status status, String predicate) {
        return switch (status) {
            case DECISION -> predicate.equals("decides") ? status : Assertion.Status.ATTRIBUTED_STATEMENT;
            case CONFIRMED_COMMITMENT -> predicate.equals("commits") ? status
                    : predicate.equals("offers") ? Assertion.Status.OFFER : Assertion.Status.ATTRIBUTED_STATEMENT;
            case OFFER -> predicate.equals("offers") || predicate.equals("commits") ? status : Assertion.Status.ATTRIBUTED_STATEMENT;
            default -> status;
        };
    }

    /** TASK-046: the predicate that matches the (already consistent) status; statements never carry a speech-act predicate. */
    static String consistentPredicate(Assertion.Status status, String predicate) {
        return switch (status) {
            case DECISION -> "decides";
            case CONFIRMED_COMMITMENT -> "commits";
            case OFFER -> "offers";
            case QUESTION -> "asks";
            default -> predicate.equals("recommends") || predicate.equals("advises_against") ? predicate : "states";
        };
    }

    /** The verbatim quoted spans, joined. */
    private static String quotedText(List<KnowledgeItem.Evidence> ev, Map<String, Message> targets) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeItem.Evidence e : ev) sb.append(targets.get(e.messageId()).text(), e.spanStart(), e.spanEnd()).append(" \n ");
        return sb.toString();
    }

    /** Each quoted span widened to its enclosing clause (bounded by ; . ! ? or a line break). */
    private static String quotedClauses(List<KnowledgeItem.Evidence> ev, Map<String, Message> targets) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeItem.Evidence e : ev) {
            String t = targets.get(e.messageId()).text();
            int a = e.spanStart(), b = e.spanEnd();
            while (a > 0 && ";.!?\n".indexOf(t.charAt(a - 1)) < 0) a--;
            while (b < t.length() && ";.!?\n".indexOf(t.charAt(b)) < 0) b++;
            if (b < t.length() && "?!".indexOf(t.charAt(b)) >= 0) b++;                // keep the clause's own ? / !
            sb.append(t, a, b).append(" \n ");
        }
        return sb.toString();
    }

    private static boolean occursNear(String surface, List<Message> asserting, Map<String, Message> parents) {
        for (Message m : asserting) {
            if (Spans.containsIgnoreCase(m.text(), surface)) return true;
            Message parent = m.replyToId() == null ? null : parents.get(m.replyToId());
            if (parent != null && !parent.deleted() && Spans.containsIgnoreCase(parent.text(), surface)) return true;
        }
        return false;
    }

    static KnowledgeItem.Kind legacyKind(Assertion.Status s, String predicate) {
        return switch (s) {
            case QUESTION -> KnowledgeItem.Kind.QUESTION;
            case DECISION -> KnowledgeItem.Kind.DECISION;
            case OFFER, CONFIRMED_COMMITMENT -> KnowledgeItem.Kind.COMMITMENT;
            case CORRECTION -> KnowledgeItem.Kind.CORRECTION;
            default -> predicate.equals("recommends") || predicate.equals("advises_against") ? KnowledgeItem.Kind.RECOMMENDATION : KnowledgeItem.Kind.CLAIM;
        };
    }

    static String digest(String... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String p : parts) { d.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8)); d.update((byte) 0); }
            return HexFormat.of().formatHex(d.digest()).substring(0, 20);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
