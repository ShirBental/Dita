package app.dita.projections;

import app.dita.core.model.Assertion;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The searchable text of a knowledge item (RFC-002 §3.3, Natasha K-1): a Dita TEMPLATE filled only with
 * <ul>
 *   <li>the speaker id (message metadata, set by Dita),</li>
 *   <li>a fixed phrase for the closed predicate vocabulary,</li>
 *   <li>verbatim spans cut from the LIVE evidence messages: the ASSERTS/QUALIFIES quotes and the qualifier spans,</li>
 *   <li>the subject and the hearsay source, but only if that surface occurs verbatim in a live evidence message
 *       (it is then copied from the message, not from the item).</li>
 * </ul>
 * Never used: {@link KnowledgeItem#statement()} (model or rule wording), {@link KnowledgeItem#subject()}, entity
 * surfaces, or any field the model wrote as free text. A deleted message contributes nothing, so a purged source can
 * never be re-indexed. Not indexed at all (empty result → the projection deletes the document): legacy items without an
 * assertion, redacted items, items whose verified sources are all deleted (RFC-002 D-1 (a): audit-only, never
 * retrieved), and items with no live ASSERTS quote left.
 * <p>Known limit (core K-4): a span is re-cut from the CURRENT text; if a message was edited after extraction the offsets
 * may no longer match the original quote. Re-validation on edit belongs to core.
 */
record KnowledgeDocument(String groupId, String knowledgeId, String text, String status, String predicate, String validity,
                         String subjectId, String speakerId, List<String> sourceIds) {

    static final Map<String, String> PREDICATE_PHRASE = Map.of(
            "recommends", "recommends", "advises_against", "advises against", "states", "states", "asks", "asks about",
            "offers", "offers", "commits", "commits to", "decides", "decided on", "corrects", "corrects", "confirms", "confirms");

    /** Renders from current truth; empty if the item must not be indexed. */
    static Optional<KnowledgeDocument> render(KnowledgeItem k, BiFunction<String, String, Optional<Message>> messages) {
        Assertion a = k.assertion();
        if (a == null || k.statementRedacted() || k.onlyDeletedSourcesRemain()) return Optional.empty();
        List<String> quotes = new ArrayList<>(), qualifierSpans = new ArrayList<>();
        List<String> liveAssertingTexts = new ArrayList<>();
        boolean assertsQuote = false;
        for (KnowledgeItem.Evidence e : k.evidence()) {
            if (e.sourceDeleted() || !e.hasSpan()) continue;
            Optional<String> span = cut(messages, k.groupId(), e.messageId(), e.spanStart(), e.spanEnd());
            if (span.isEmpty()) continue;
            if (e.role() == KnowledgeItem.Role.ASSERTS) {
                assertsQuote = true;
                messages.apply(k.groupId(), e.messageId()).filter(m -> !m.deleted()).ifPresent(m -> liveAssertingTexts.add(m.text()));
            }
            if (e.role() != KnowledgeItem.Role.CORRECTION_TARGET) quotes.add(span.get());
        }
        if (!assertsQuote) return Optional.empty();
        for (Assertion.Qualifier q : a.qualifiers()) {
            if (k.deletedSourceIds().contains(q.messageId())) continue;
            cut(messages, k.groupId(), q.messageId(), q.spanStart(), q.spanEnd()).ifPresent(qualifierSpans::add);
        }
        StringBuilder t = new StringBuilder(a.speakerId()).append(' ')
                .append(PREDICATE_PHRASE.getOrDefault(a.predicate(), "states"));
        verbatim(a.subject(), liveAssertingTexts).ifPresent(s -> t.append(' ').append(s));
        if (a.certainty() == Assertion.Certainty.HEARSAY) {
            t.append(" (relayed");
            verbatim(a.attributedTo(), liveAssertingTexts).ifPresent(s -> t.append(" from ").append(s));
            t.append(')');
        }
        if (!qualifierSpans.isEmpty()) t.append(" — ").append(String.join("; ", dedupe(qualifierSpans)));
        for (String q : dedupe(quotes)) t.append(" — \"").append(q).append('"');
        List<String> sources = k.sourceMessageIds();
        return Optional.of(new KnowledgeDocument(k.groupId(), k.id(), t.toString(), a.status().name(), a.predicate(),
                a.validity().name(), a.subjectId(), a.speakerId(), sources));
    }

    private static Optional<String> cut(BiFunction<String, String, Optional<Message>> messages, String g, String mid, int s, int e) {
        return messages.apply(g, mid).filter(m -> !m.deleted() && m.text() != null && e <= m.text().length() && s >= 0 && e > s)
                .map(m -> m.text().substring(s, e));
    }

    /** The surface as it appears in a live asserting message (case-insensitive match, message casing kept), else empty. */
    static Optional<String> verbatim(String surface, List<String> texts) {
        if (surface == null || surface.isBlank()) return Optional.empty();
        String needle = surface.strip().toLowerCase(java.util.Locale.ROOT);
        for (String text : texts) {
            int i = text.toLowerCase(java.util.Locale.ROOT).indexOf(needle);
            if (i >= 0 && text.toLowerCase(java.util.Locale.ROOT).length() == text.length()) return Optional.of(text.substring(i, i + needle.length()));
        }
        return Optional.empty();
    }

    private static List<String> dedupe(List<String> xs) { Set<String> s = new LinkedHashSet<>(xs); return List.copyOf(s); }
}
