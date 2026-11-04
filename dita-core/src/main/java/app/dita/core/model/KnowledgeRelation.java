package app.dita.core.model;

import java.util.Objects;

/**
 * RFC-002 §3.5: a relation between two knowledge items of ONE group. Kinds are recomputed by Dita's rules; a model label
 * is only a hint. {@code evidenceMessageId}: the message whose content established the relation (for CORRECTS/CONFIRMS
 * the user's reply), so deleting it removes the relation.
 */
public record KnowledgeRelation(String groupId, String fromId, String toId, Kind kind, String evidenceMessageId) {
    public enum Kind { SUPPORTS, QUALIFIES, CONTRADICTS, CORRECTS, SUPERSEDES, ANSWERS, CONFIRMS }

    public KnowledgeRelation {
        Objects.requireNonNull(groupId); Objects.requireNonNull(fromId); Objects.requireNonNull(toId); Objects.requireNonNull(kind);
        if (fromId.equals(toId)) throw new IllegalArgumentException("self relation");
    }

    public String key() { return fromId + "|" + kind + "|" + toId; }
    public boolean involves(String knowledgeId) { return fromId.equals(knowledgeId) || toId.equals(knowledgeId); }
}
