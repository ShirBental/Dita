package app.dita.core.memory;

import app.dita.core.config.ClarificationSettings;
import app.dita.core.model.MessageLink;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proactive clarification: if a new message has two inferred candidates whose scores are within maxScoreGap and
 * both reach minCandidateScore, Dita may ask which thread was meant. Rate-limited per group. The question text
 * names message ids only; the adapter renders it without quoting message content.
 */
public final class ClarificationGate {
    public record Clarification(String groupId, String messageId, List<String> candidateMessageIds) {}

    private final ClarificationSettings settings;
    private final Map<String, Deque<Instant>> asked = new HashMap<>();

    public ClarificationGate(ClarificationSettings settings) { this.settings = settings; }

    public synchronized Optional<Clarification> consider(String groupId, String messageId, List<MessageLink> newLinks, Instant now) {
        if (!settings.enabled()) return Optional.empty();
        List<MessageLink> inferred = newLinks.stream().filter(l -> !l.isExplicit())
                .sorted((a, b) -> Double.compare(b.score(), a.score())).toList();
        if (inferred.size() < 2) return Optional.empty();
        MessageLink a = inferred.get(0), b = inferred.get(1);
        if (b.score() < settings.minCandidateScore() || a.score() - b.score() > settings.maxScoreGap()) return Optional.empty();
        Deque<Instant> q = asked.computeIfAbsent(groupId, g -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(settings.rateWindow()))) q.pollFirst();
        if (q.size() >= settings.maxPerGroupPerWindow()) return Optional.empty();
        q.addLast(now);
        return Optional.of(new Clarification(groupId, messageId, List.of(a.toId(), b.toId())));
    }
}
