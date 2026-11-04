package app.dita.core.store;

import app.dita.core.deletion.DeletionMode;
import app.dita.core.model.AnswerRecord;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.deletion.DeletionSource;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageEvent;
import app.dita.core.model.MessageLink;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Source-of-truth port (PostgreSQL in dita-store-postgres; in-memory reference here). Primitive operations only;
 * policy lives in services. Every method is scoped; implementations must never read or write another group.
 */
public interface MessageStore {
    /** Runs {@code work} atomically and in isolation from other units on this group's truth. Nested operations join
     * the unit. PostgreSQL binds app.group_id and holds a group advisory transaction lock; in-memory holds its monitor.
     * This also permits a final answer gate to validate messages, assertions and relations at one serialization point. */
    <T> T atomically(GroupScope scope, Supplier<T> work);

    /** Idempotent for redelivery of the same message; validates before mutating. */
    void append(GroupScope scope, Message message);
    /** Fails for deleted messages: an edit can never restore deleted text. */
    void edit(GroupScope scope, String messageId, String newText, Instant at);
    Optional<Message> find(GroupScope scope, String messageId);
    /** Live (non-deleted) messages in chronological order. */
    List<Message> live(GroupScope scope);
    /** Up to {@code limit} live messages sent before the given message, oldest first. */
    List<Message> windowBefore(GroupScope scope, String messageId, int limit);
    List<MessageEvent> history(GroupScope scope, String messageId);

    /** TASK-046 (T46-5/N-108): ids of this group's messages that were ever edited, in ONE read (backends should override). */
    default java.util.Set<String> editedMessageIds(GroupScope scope) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Message m : live(scope))
            if (history(scope, m.id()).stream().anyMatch(e -> e.kind() == MessageEvent.Kind.EDITED)) out.add(m.id());
        return out;
    }

    /**
     * TASK-046 (T46-1/N-106): holds a shared lock on these messages until the caller's {@link #atomically} unit ends, so a
     * concurrent edit or deletion cannot commit between a check and the dependent write. In-memory: a no-op (every store
     * operation is serialised). Postgres: SELECT … FOR SHARE.
     */
    default void lockForRead(GroupScope scope, Collection<String> messageIds) { }

    /**
     * Marks the message deleted and nulls its text in the message and in every history event.
     * Returns every text version that existed (for residue checks), which the caller must not persist.
     */
    List<String> tombstone(GroupScope scope, String messageId, Instant at, DeletionMode mode, DeletionSource source);

    /** Idempotent by {@link MessageLink#key()}. */
    void addLink(GroupScope scope, MessageLink link);
    void removeLinks(GroupScope scope, Collection<String> linkKeys);
    List<MessageLink> links(GroupScope scope);

    /** New knowledge: every source must be live in this group; provisional links must exist. */
    void addKnowledge(GroupScope scope, KnowledgeItem item);
    /** Update during deletion processing (sources may now be deleted). */
    void replaceKnowledge(GroupScope scope, KnowledgeItem item);
    void removeKnowledge(GroupScope scope, String knowledgeId);
    List<KnowledgeItem> knowledge(GroupScope scope);
    default Optional<KnowledgeItem> knowledgeById(GroupScope scope, String id) {
        return knowledge(scope).stream().filter(k -> k.id().equals(id)).findFirst();
    }

    /**
     * RFC-002 relations. Idempotent by {@link KnowledgeRelation#key()}; both ends must exist in this group.
     * {@link #removeKnowledge} must also remove every relation that involves the removed item.
     * Defaults FAIL explicitly: a backend that has not implemented them can never report success (no silent no-op).
     */
    default void addRelation(GroupScope scope, KnowledgeRelation r) { throw unsupported("relations"); }
    default void removeRelations(GroupScope scope, Collection<String> keys) { throw unsupported("relations"); }
    default List<KnowledgeRelation> relations(GroupScope scope) { throw unsupported("relations"); }

    /**
     * DEC-038: whether {@link app.dita.core.model.Assertion.Validity#UNRESOLVED} can be persisted. PostgreSQL needs V6
     * (an approval-gated constraint change); without it the reconciler keeps such items SUPERSEDED (still not current).
     */
    default boolean supportsUnresolvedValidity() { return true; }

    /** Provenance of a published answer (knowledge items + source messages), keyed by Dita's reply id. Idempotent. */
    default void recordAnswer(GroupScope scope, AnswerRecord a) { throw unsupported("answer provenance"); }
    default Optional<AnswerRecord> answer(GroupScope scope, String replyId) { throw unsupported("answer provenance"); }

    private UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(getClass().getSimpleName() + " does not implement " + what + " (RFC-002 / V5)");
    }

    /** Dita-authored replies and the messages they cited (C-11 reporting). */
    void recordDitaReply(GroupScope scope, String replyId, Collection<String> citedMessageIds);
    List<String> ditaRepliesCiting(GroupScope scope, String messageId);
}
