package app.dita.core.knowledge;

import java.util.List;

/**
 * Untrusted output of a {@link KnowledgeProposer} (Muse). Nothing here is stored as-is: {@link EvidenceValidator}
 * promotes an item only if every rule passes, computes spans itself, takes the speaker from message metadata and
 * stores verbatim quotes, never the model's wording.
 */
public record Proposal(List<Item> items, String proposalVersion) {
    public record Quote(String messageId, String quote) {}
    public record Qualifier(String kind, String messageId, String quote) {}
    public record Entity(String id, String surface) {}
    public record Item(String status, String certainty, String subjectId, String subject, String predicate, Integer polarity,
                       String attributedTo, List<Quote> evidence, List<Qualifier> qualifiers, List<Entity> entities) {
        public Item {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
            entities = entities == null ? List.of() : List.copyOf(entities);
        }
    }

    public Proposal { items = List.copyOf(items); }
}
