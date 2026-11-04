package app.dita.core.answer.synthesis;

import java.util.List;

/**
 * TASK-053: turns ALREADY-SELECTED, verified group evidence into a natural-language answer. Implementations (e.g. Muse)
 * are untrusted text generators: they never see the store, the requester, or any other group, and they cannot widen the
 * evidence. Retrieval, authorization, evidence selection, supersession and conflict detection happen before this call
 * (QueryService + KnowledgeAnswerComposer); {@link SynthesisValidator} checks everything the generator returns.
 */
public interface AnswerSynthesizer {

    /** Model/prompt version recorded with every synthesized answer (audit). */
    String version();

    Draft synthesize(Request request);

    /**
     * One piece of evidence the generator may use. {@code speaker} is a per-request pseudonym (P1, P2…), never a name or
     * platform id. {@code kind}: the knowledge status of the claim the message asserts (DECISION, CONFIRMED_COMMITMENT,
     * OFFER, QUESTION, ATTRIBUTED_STATEMENT, REPORTED_CLAIM) or MESSAGE when the answer came from message retrieval.
     * {@code quote} is the verbatim, verified quote Dita will show; it is untrusted group content.
     */
    record Evidence(String id, String speaker, String date, String kind, int polarity, boolean qualified, String quote) {}

    /**
     * {@code draft}: Dita's deterministic answer with speakers pseudonymised (the configurable baseline). {@code replaced}:
     * ids of earlier messages the current answer replaces; they may be named as replaced, never used as support and
     * never quoted (their text is not supplied). {@code conflicting}: members disagree; both sides must remain.
     */
    record Request(String question, String draft, List<Evidence> evidence, List<String> replaced, boolean conflicting,
                   ContextCheck context, java.util.Map<String, java.time.Instant> sentAt,
                   java.util.Map<String, String> referenceQuestions) {
        public Request {
            evidence = List.copyOf(evidence);
            replaced = List.copyOf(replaced);
            sentAt = sentAt == null ? java.util.Map.of() : java.util.Map.copyOf(sentAt);
            referenceQuestions = referenceQuestions == null ? java.util.Map.of() : java.util.Map.copyOf(referenceQuestions);
        }
        /** LOCAL answer id → immediately preceding selected question id; never serialized to Muse. */
        public Request(String question, String draft, List<Evidence> evidence, List<String> replaced, boolean conflicting,
                       ContextCheck context, java.util.Map<String, java.time.Instant> sentAt) {
            this(question, draft, evidence, replaced, conflicting, context, sentAt, java.util.Map.of());
        }
        /**
         * Without send times (DEC-052): {@code sentAt} is LOCAL (evidence id → when the message was sent), used only by the
         * validator to check ordering words; it is never sent to Muse. Empty = order unknown.
         */
        public Request(String question, String draft, List<Evidence> evidence, List<String> replaced, boolean conflicting,
                       ContextCheck context) {
            this(question, draft, evidence, replaced, conflicting, context, java.util.Map.of());
        }
        /** The knowledge path (and older callers): no raw-message context check; R5 keeps its original rule. */
        public Request(String question, String draft, List<Evidence> evidence, List<String> replaced, boolean conflicting) {
            this(question, draft, evidence, replaced, conflicting, null);
        }
    }

    /**
     * TASK-054 G2, raw-message answers only. LOCAL facts the validator enforces; never sent to Muse.
     * {@code questionContext}: context words of the question ("for babies"), empty = none named.
     * {@code evidenceContext}: evidence id → its context words (empty = unknown).
     * {@code conflictGroups}: genuine conflicts (opposing statements not explicitly about different contexts); an answer
     * must cite at least one positive and one negative statement of EVERY group (R5).
     */
    record ContextCheck(java.util.Set<String> questionContext, java.util.Map<String, java.util.Set<String>> evidenceContext,
                        List<java.util.Set<String>> conflictGroups) {
        public ContextCheck {
            questionContext = java.util.Set.copyOf(questionContext);
            evidenceContext = java.util.Map.copyOf(evidenceContext);
            conflictGroups = conflictGroups.stream().map(java.util.Set::copyOf).toList();
        }
    }

    /** The generator's untrusted output: an explicit abstention, or answer text with [id] markers plus the ids it used. */
    record Draft(boolean abstain, String answer, List<String> citedIds) {
        public Draft {
            answer = answer == null ? "" : answer;
            citedIds = citedIds == null ? List.of() : List.copyOf(citedIds);
        }
    }
}
