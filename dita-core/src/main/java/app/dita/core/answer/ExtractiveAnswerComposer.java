package app.dita.core.answer;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Hit;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.MessageStore;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier-0 answer: quotes original messages instead of paraphrasing, so it cannot invent decisions.
 * A Muse composer may phrase the answer later, but its output must pass the same {@link AnswerVerifier}.
 */
public final class ExtractiveAnswerComposer {
    private final int MAX_QUOTE;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private final Retriever retriever;
    private final MessageStore store;
    private final java.util.function.Function<Message, List<String>> evidenceTexts;
    private final java.util.function.Function<String, String> names;

    public ExtractiveAnswerComposer(Retriever retriever, MessageStore store) { this(retriever, store, 280); }

    /** {@code maxQuoteChars} keeps quotation minimal (C-11): Dita's own replies are not retracted later. */
    public ExtractiveAnswerComposer(Retriever retriever, MessageStore store, int maxQuoteChars) {
        this(retriever, store, maxQuoteChars, m -> List.of(m.text()));
    }

    /**
     * Codex TASK-047 F1: {@code evidenceTexts} gives, per message, the verbatim parts that may be used as evidence (the
     * whole text by default). Relevance is judged and quotes are cut on those parts only; an empty list skips the message.
     * Every part must be a substring of the live text (the verifier checks it).
     */
    public ExtractiveAnswerComposer(Retriever retriever, MessageStore store, int maxQuoteChars,
                                    java.util.function.Function<Message, List<String>> evidenceTexts) {
        this(retriever, store, maxQuoteChars, evidenceTexts, id -> id);
    }

    /** {@code names}: author id → display name in the rendered reply (identity by default). */
    public ExtractiveAnswerComposer(Retriever retriever, MessageStore store, int maxQuoteChars,
                                    java.util.function.Function<Message, List<String>> evidenceTexts,
                                    java.util.function.Function<String, String> names) {
        this.retriever = retriever; this.store = store; this.MAX_QUOTE = maxQuoteChars; this.evidenceTexts = evidenceTexts;
        this.names = names == null ? id -> id : names;
    }

    public GroundedAnswer answer(GroupScope scope, String question, int k) {
        List<Citation> cites = new ArrayList<>();
        // TASK-045 (DEC-035): a search hit is not evidence. A directly matched message counts only if it shares a content
        // word (stopwords removed, inflection-tolerant) with the question; backends differ (Vespa's userQuery() matches
        // "the"), so relevance is judged here, identically for every backend. Inferred context is kept only when at
        // least one direct match is relevant. No relevant match → "not found", never an answer built from noise.
        java.util.Set<String> q = app.dita.core.text.Tokenizer.normalizedSet(question);
        List<Hit> hits = retriever.retrieve(scope, question, k);
        boolean anyRelevant = false;
        java.util.Set<String> relevant = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (linked(h)) continue;
            Message m = store.find(scope, h.messageId()).orElse(null);
            if (m == null || m.deleted() || m.text() == null) continue;
            // TASK-054 G1: a semantic hit already passed the dense channel's cosine floor, so it is relevant without sharing a
            // word with the question (that is the point of semantic retrieval); lexical hits keep the word-overlap gate.
            if (semantic(h) || app.dita.core.text.Tokenizer.normalizedSet(String.join("\n", evidenceTexts.apply(m))).stream().anyMatch(q::contains)) { relevant.add(m.id()); anyRelevant = true; }
        }
        for (Hit h : hits) {
            Message m = store.find(scope, h.messageId()).orElse(null);
            if (m == null || m.deleted()) continue;
            if (linked(h) ? !anyRelevant : !relevant.contains(m.id())) continue;
            if (m.text() == null || InjectionGuard.isInstructionToDita(m.text())) continue;   // an instruction to Dita is never evidence
            for (String part : evidenceTexts.apply(m)) {
                if (part == null || part.isBlank() || !m.text().contains(part)) continue;
                String quote = part.length() > MAX_QUOTE ? part.substring(0, MAX_QUOTE) : part;
                cites.add(new Citation(m.id(), m.authorId(), m.sentAt(), quote,
                        h.inferredContext() ? Citation.Evidence.INFERRED : Citation.Evidence.EXPLICIT,
                        Stance.polarity(part), Stance.qualified(part)));
            }
        }
        return render(question, cites, names);
    }

    /** Reached through a link (reply/graph edge, explicit or inferred), not by matching the question: context that is
     *  kept only when a directly matched message is relevant (a reply "I'll do it" shares no word with the question). */
    static boolean linked(Hit h) {
        return h.inferredContext() || (h.via() != null && (h.via().startsWith("graph:") || h.via().startsWith("neo4j:") || h.via().startsWith("context:")));
    }

    /** Found by the semantic (dense) message channel, possibly among others. */
    static boolean semantic(Hit h) {
        return h.via() != null && app.dita.core.retrieval.HybridRetriever.channels(h.via()).contains("vespa:dense");
    }

    static GroundedAnswer render(String question, List<Citation> cites) { return render(question, cites, id -> id); }

    static GroundedAnswer render(String question, List<Citation> cites, java.util.function.Function<String, String> names) {
        if (cites.isEmpty()) return GroundedAnswer.notFound(question);
        // Disagreement is judged only among messages that matched the question directly. Context reached only through an
        // inferred link may be about something else, so it must not raise a "members disagree" warning on its own.
        boolean pos = cites.stream().anyMatch(c -> c.evidence() == Citation.Evidence.EXPLICIT && c.polarity() > 0);
        boolean neg = cites.stream().anyMatch(c -> c.evidence() == Citation.Evidence.EXPLICIT && c.polarity() < 0);
        // TASK-054 G2: opposing statements conflict only when they are not explicitly about different contexts
        // ("for babies" vs "for teenagers"); unknown context still counts as a possible conflict (ContextCues).
        boolean conflict = !conflictGroups(cites).isEmpty();
        StringBuilder sb = new StringBuilder();
        if (conflict) sb.append("Members disagree. There are both recommendations for it and warnings against it.\n");
        else if (pos && neg) sb.append("Members' views differ by context.\n");
        sb.append("What the group said:\n");
        for (Citation c : cites) {
            sb.append("• ").append(names.apply(c.authorId())).append(" (").append(DAY.format(c.sentAt())).append("): \"")
              .append(c.quote()).append('"');
            if (c.qualified()) sb.append(" [qualified]");
            if (c.evidence() == Citation.Evidence.INFERRED) sb.append(" [related by inference]");
            sb.append(" [msg ").append(c.messageId()).append("]\n");
        }
        return new GroundedAnswer(question, sb.toString().strip(), List.copyOf(cites), conflict, true);
    }

    /** Genuine conflict groups among directly matched (EXPLICIT) citations, by message id (G2; see {@link ContextCues}). */
    public static List<java.util.Set<String>> conflictGroups(List<Citation> cites) {
        java.util.Map<String, ContextCues.Stated> byId = new java.util.LinkedHashMap<>();
        for (Citation c : cites) {
            if (c.evidence() != Citation.Evidence.EXPLICIT || c.polarity() == 0) continue;
            byId.putIfAbsent(c.messageId(), ContextCues.Stated.of(c.messageId(), c.polarity(), c.quote()));
        }
        return ContextCues.conflictGroups(byId.values());
    }
}
