package app.dita.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * RFC-002 / DEC-022: what a PARTICIPANT said, with evidence. Dita never stores world facts: every assertion is a speech
 * act attributed to {@code speakerId} (taken from message metadata, never from the model). A "verified" item means
 * "verified that this person said this", not "verified that it is true".
 *
 * @param status       semantic type of the speech act
 * @param certainty    how the speaker framed it (asserted, hedged, or relayed from someone else)
 * @param speakerId    author of the ASSERTS evidence message(s); set by Dita
 * @param attributedTo for REPORTED_CLAIM: the relayed source as it appears in the span ("my neighbour"), else null
 * @param subjectId    group-scoped entity id (model-proposed, surface-verified); links items about the same thing
 * @param subject      the subject's surface form, verbatim in the evidence message or its reply parent
 * @param predicate    closed vocabulary: recommends | advises_against | states | asks | offers | commits | decides | corrects | confirms
 * @param qualifiers   context/conditions/time, each a verbatim span in an evidence message (no copied text)
 * @param entities     other entities mentioned, each surface-verified
 * @param statedAt     when it was said (send time of the primary evidence message)
 * @param validity     ACTIVE; SUPERSEDED by a later statement of the same speaker (history is kept); or UNRESOLVED: it was
 *                     superseded, but every claim that superseded it was withdrawn (deleted, edited, unshowable). The
 *                     withdrawal is not evidence that the older claim is current again, so it is never shown as current
 *                     (DEC-038, N-110 policy)
 * @param proposalVersion model + prompt version that proposed it ("rule" for deterministic items)
 * @param batchId      extraction batch that produced it (idempotency / audit)
 */
public record Assertion(Status status, Certainty certainty, String speakerId, String attributedTo,
                        String subjectId, String subject, String predicate, List<Qualifier> qualifiers,
                        List<Entity> entities, Instant statedAt, Validity validity, String proposalVersion, String batchId) {
    public enum Status { ATTRIBUTED_STATEMENT, REPORTED_CLAIM, OFFER, CONFIRMED_COMMITMENT, DECISION, QUESTION, CORRECTION, CONFIRMATION }
    public enum Certainty { ASSERTED, HEDGED, HEARSAY }
    public enum Validity { ACTIVE, SUPERSEDED, UNRESOLVED }

    public record Qualifier(Kind kind, String messageId, int spanStart, int spanEnd) {
        public enum Kind { POPULATION, CONDITION, TIME, CONTEXT }
        public Qualifier {
            Objects.requireNonNull(kind); Objects.requireNonNull(messageId);
            if (spanStart < 0 || spanEnd <= spanStart) throw new IllegalArgumentException("qualifier needs a span");
        }
    }

    public record Entity(String id, String surface) {
        public Entity { Objects.requireNonNull(id); Objects.requireNonNull(surface); }
    }

    public static final List<String> PREDICATES = List.of("recommends", "advises_against", "states", "asks", "offers",
            "commits", "decides", "corrects", "confirms");

    public Assertion {
        Objects.requireNonNull(status); Objects.requireNonNull(certainty); Objects.requireNonNull(speakerId);
        Objects.requireNonNull(subjectId); Objects.requireNonNull(predicate); Objects.requireNonNull(statedAt);
        Objects.requireNonNull(validity);
        if (!PREDICATES.contains(predicate)) throw new IllegalArgumentException("predicate outside the closed vocabulary");
        if ((status == Status.REPORTED_CLAIM) != (certainty == Certainty.HEARSAY)) throw new IllegalArgumentException("reported claims are hearsay, and only they are");
        if (certainty == Certainty.HEARSAY && (attributedTo == null || attributedTo.isBlank())) throw new IllegalArgumentException("hearsay needs attributed_to");
        qualifiers = List.copyOf(qualifiers);
        entities = List.copyOf(entities);
    }

    public Assertion superseded() {
        return new Assertion(status, certainty, speakerId, attributedTo, subjectId, subject, predicate, qualifiers, entities,
                statedAt, Validity.SUPERSEDED, proposalVersion, batchId);
    }

    /**
     * DEC-038 (N-110): the only evidence of supersession was withdrawn. The item is NOT reactivated (a withdrawn
     * correction does not show the old value is true again); its provenance is kept, and it is not current.
     */
    public Assertion unresolved() {
        return new Assertion(status, certainty, speakerId, attributedTo, subjectId, subject, predicate, qualifiers, entities,
                statedAt, Validity.UNRESOLVED, proposalVersion, batchId);
    }

    /**
     * DEC-039 (Codex TASK-047 F3): back to ACTIVE ONLY when the relation(s) that retired it were REJECTED as invalid
     * inferences and it has no withdrawal history. Called only by {@link app.dita.core.knowledge.ValidityReconciler}.
     */
    public Assertion restoredAfterRejectedRelation() {
        if (validity != Validity.SUPERSEDED) throw new IllegalStateException("only a relation-retired (SUPERSEDED) item can be restored");
        return new Assertion(status, certainty, speakerId, attributedTo, subjectId, subject, predicate, qualifiers, entities,
                statedAt, Validity.ACTIVE, proposalVersion, batchId);
    }

    public Assertion withQualifiers(List<Qualifier> q) {
        return new Assertion(status, certainty, speakerId, attributedTo, subjectId, subject, predicate, q, entities,
                statedAt, validity, proposalVersion, batchId);
    }
}
