package app.dita.core.answer.synthesis;

import app.dita.core.model.GroupScope;
import app.dita.core.store.MessageStore;
import java.util.*;
import java.util.regex.*;

/** Conservative, local reference resolution. Does not add evidence or alter stored/source quotes. */
final class ReferenceSupport {
    private ReferenceSupport() {}
    // One definite object, no alternatives, negation, numbers or subordinate clauses. Unsupported forms fail closed.
    private static final Pattern OBJECT = Pattern.compile("(?i)\\b(?:use|book|reserve|try) (the [a-z]+(?: [a-z]+){0,3})\\?$");
    private static final Set<String> AMBIGUOUS = Set.of("and", "or", "not", "no", "other", "another", "if", "unless", "that", "which", "with", "without");
    private static final Pattern PRONOUN = Pattern.compile("(?i)(?:^|[.!?]\\s+)it is\\b");

    static Map<String, String> selectedQuestions(MessageStore store, GroupScope scope, List<AnswerSynthesizer.Evidence> evidence) {
        Map<String, AnswerSynthesizer.Evidence> selected = new HashMap<>();
        evidence.forEach(e -> selected.put(e.id(), e));
        Map<String, String> result = new LinkedHashMap<>();
        for (var e : evidence) {
            if (!PRONOUN.matcher(e.quote()).find()) continue;
            var current = store.find(scope, e.id()).orElse(null);
            if (current == null || !e.quote().equals(current.text())) continue;
            // Actual store adjacency, not adjacency in a sparse retrieval result. A reply to somebody else is excluded.
            var before = store.windowBefore(scope, e.id(), 1);
            if (before.size() != 1) continue;
            var q = before.get(0);
            var qe = selected.get(q.id());
            if (qe == null || java.time.Duration.between(q.sentAt(), current.sentAt()).compareTo(java.time.Duration.ofMinutes(5)) > 0
                    || !q.sentAt().isBefore(current.sentAt()) || !qe.quote().equals(q.text())
                    || q.deleted() || q.sourceKind() != current.sourceKind()
                    || (current.replyToId() != null && !current.replyToId().equals(q.id())) || object(qe.quote()) == null) continue;
            result.put(e.id(), q.id());
        }
        return Map.copyOf(result);
    }

    private static String object(String question) {
        if (question.chars().filter(c -> c == '?').count() != 1
                || Pattern.compile("(?i)\\bor\\b").matcher(question).find()) return null;
        Matcher m = OBJECT.matcher(question.strip());
        if (!m.find()) return null;
        String subject = m.group(1);
        for (String word : subject.toLowerCase(Locale.ROOT).split(" ")) if (AMBIGUOUS.contains(word)) return null;
        return subject;
    }

    static String resolveSubject(AnswerSynthesizer.Request req, String clause, List<AnswerSynthesizer.Evidence> cited,
                                 Set<String> contextualSources) {
        // Single-speaker, single-source claim only. Borrow the antecedent's subject, never its predicates or modifiers.
        if (cited.size() != 1) return clause;
        var e = cited.get(0);
        String questionId = req.referenceQuestions().get(e.id());
        if (questionId == null || !PRONOUN.matcher(e.quote()).find()) return clause;
        var q = req.evidence().stream().filter(x -> x.id().equals(questionId)).findFirst().orElse(null);
        if (q == null) return clause;
        String subject = object(q.quote());
        if (subject == null) return clause;
        Pattern subjectPosition = Pattern.compile("(?i)^(\\s*[.!?]?\\s*" + Pattern.quote(e.speaker())
                + "\\s+(?:said|noted|stated|reported)\\s+)" + Pattern.quote(subject) + "(?=\\s+[\\\"“]?is\\b)");
        Matcher m = subjectPosition.matcher(clause);
        if (!m.find()) return clause;
        contextualSources.add(questionId);
        return m.replaceFirst("$1it");
    }

    static String locationLabel(String question, String clause, List<AnswerSynthesizer.Evidence> cited) {
        // A WHERE field label is presentation, not a new place. All place words still have to pass R8 on this citation.
        if (!Pattern.compile("(?i)\\bwhere\\b").matcher(question).find() || cited.size() != 1) return clause;
        var e = cited.get(0);
        if (!Pattern.compile("(?i)\\b(?:at|near)\\b").matcher(e.quote()).find()) return clause;
        return clause.replaceFirst("(?i)^(\\s*[.!?]?\\s*" + Pattern.quote(e.speaker())
                + "\\s+(?:said|noted|stated|reported)\\s+)the location is\\s+", "$1");
    }
}
