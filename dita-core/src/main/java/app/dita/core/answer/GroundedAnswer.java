package app.dita.core.answer;

import java.util.List;

public record GroundedAnswer(String question, String text, List<Citation> citations,
                             boolean conflicting, boolean found, List<String> knowledgeIds) {
    public GroundedAnswer {
        citations = List.copyOf(citations);
        knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
    }

    /** A message-only answer (no knowledge items). */
    public GroundedAnswer(String question, String text, List<Citation> citations, boolean conflicting, boolean found) {
        this(question, text, citations, conflicting, found, List.of());
    }

    public static final String NOT_FOUND = "I couldn't find this in the group's messages.";

    public static GroundedAnswer notFound(String question) {
        return new GroundedAnswer(question, NOT_FOUND, List.of(), false, false);
    }
}
