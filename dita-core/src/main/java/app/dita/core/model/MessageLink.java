package app.dita.core.model;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Directed relation from a later message to an earlier one. Only platform replies (or humans) are facts;
 * anything inferred stays a CANDIDATE with its full evidence vector. {@code evidenceMessageIds} lists every
 * message whose content or metadata contributed to the evidence (endpoints plus, e.g., the message whose
 * authorship produced an answered_author signal) so deletion can invalidate exactly the dependent links.
 */
public record MessageLink(String groupId, String fromId, String toId, Kind kind, Status status,
                          Method method, double score, Map<String, Double> evidence, Set<String> evidenceMessageIds) {
    public enum Kind { EXPLICIT_REPLY, INFERRED_REFERENCE, INFERRED_CONTINUATION, INFERRED_CORRECTION }
    public enum Status { FACT, CANDIDATE, CONFIRMED, REJECTED }
    public enum Method { PLATFORM, RULE, LLM, HUMAN }

    public MessageLink {
        Objects.requireNonNull(kind); Objects.requireNonNull(status); Objects.requireNonNull(method);
        evidence = Map.copyOf(evidence);
        Set<String> deps = new LinkedHashSet<>();
        deps.add(fromId); deps.add(toId);
        if (evidenceMessageIds != null) deps.addAll(evidenceMessageIds);
        evidenceMessageIds = Set.copyOf(deps);
        // FACT only from the platform; CONFIRMED only by a human review (matches the DB CHECKs; Shuri #4).
        if (status == Status.FACT && method != Method.PLATFORM) {
            throw new IllegalArgumentException("inferred links cannot be promoted to facts automatically");
        }
        if (status == Status.CONFIRMED && method != Method.HUMAN) {
            throw new IllegalArgumentException("only a human review can confirm a link");
        }
        if ((kind == Kind.EXPLICIT_REPLY) != (method == Method.PLATFORM)) {
            throw new IllegalArgumentException("EXPLICIT_REPLY must come from the platform");
        }
    }

    public MessageLink(String groupId, String fromId, String toId, Kind kind, Status status, Method method,
                       double score, Map<String, Double> evidence) {
        this(groupId, fromId, toId, kind, status, method, score, evidence, Set.of());
    }

    public boolean isExplicit() { return kind == Kind.EXPLICIT_REPLY; }
    public boolean dependsOn(String messageId) { return evidenceMessageIds.contains(messageId); }
    public String key() { return fromId + "->" + toId + ":" + kind; }
}
