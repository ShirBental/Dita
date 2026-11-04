package app.dita.core;

import app.dita.core.answer.AnswerVerifier;
import app.dita.core.answer.Citation;
import app.dita.core.answer.ExtractiveAnswerComposer;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.knowledge.KnowledgeAnswerComposer;
import app.dita.core.knowledge.SelectionPolicy;
import app.dita.core.memory.HistoryAccessFilter;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.Retriever;
import app.dita.core.store.MessageStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers a member's question from the group's memory: retrieve → history-access filter (server-side) → extractive
 * grounded answer → verifier. Every step is scoped; question text is data, never instructions.
 */
public final class QueryService {
    private final MessageStore store;
    private final Retriever retriever;
    private final HistoryAccessFilter access;
    private final AnswerVerifier verifier;
    private final int k, maxQuote;
    private final java.util.function.Function<String, String> names;
    /** TASK-054 (DEC-050): need-coverage context selection for the messages-only path; null = top-k (previous behaviour). */
    private final app.dita.core.retrieval.ConversationContextSelector contextSelector;
    private final app.dita.core.knowledge.KnowledgeRetriever knowledge;
    /** TASK-045 (DEC-034): "Related message" lines are OFF by default (no measured benefit; they re-introduced stale and
     *  off-topic content). Opt in with {@link #withSupportingMessages(boolean)}. */
    private final boolean supportingMessages;
    /** TASK-053: optional Muse answer synthesis after the deterministic answer; null = deterministic baseline only (default). */
    private final app.dita.core.answer.synthesis.GroundedSynthesis synthesis;
    /** TASK-054 messages-only demo mode: skip the knowledge composer; answers are composed from raw messages only. */
    private final boolean messagesOnly;
    /** TASK-054: extra eligibility of retrieved messages (e.g. this trial session only); null = all. */
    private final java.util.function.Predicate<Message> messageFilter;

    public QueryService(MessageStore store, Retriever retriever, HistoryAccessFilter access, int k, int maxQuoteChars) {
        this(store, retriever, access, k, maxQuoteChars, id -> id);
    }

    /** {@code names}: author id → display name for the knowledge path (identity = no member directory; production default). */
    public QueryService(MessageStore store, Retriever retriever, HistoryAccessFilter access, int k, int maxQuoteChars,
                        java.util.function.Function<String, String> names) {
        this(store, retriever, access, k, maxQuoteChars, names, null);
    }

    /** {@code knowledge}: ranks current knowledge items (Vespa on POSTGRES); null = in-process BM25. */
    public QueryService(MessageStore store, Retriever retriever, HistoryAccessFilter access, int k, int maxQuoteChars,
                        java.util.function.Function<String, String> names, app.dita.core.knowledge.KnowledgeRetriever knowledge) {
        this(store, retriever, access, k, maxQuoteChars, names, knowledge, false, null, false, null);
    }

    private QueryService(MessageStore store, Retriever retriever, HistoryAccessFilter access, int k, int maxQuoteChars,
                         java.util.function.Function<String, String> names, app.dita.core.knowledge.KnowledgeRetriever knowledge,
                         boolean supportingMessages, app.dita.core.answer.synthesis.GroundedSynthesis synthesis,
                         boolean messagesOnly, java.util.function.Predicate<Message> messageFilter) {
        this(store, retriever, access, k, maxQuoteChars, names, knowledge, supportingMessages, synthesis, messagesOnly, messageFilter, null);
    }

    private QueryService(MessageStore store, Retriever retriever, HistoryAccessFilter access, int k, int maxQuoteChars,
                         java.util.function.Function<String, String> names, app.dita.core.knowledge.KnowledgeRetriever knowledge,
                         boolean supportingMessages, app.dita.core.answer.synthesis.GroundedSynthesis synthesis,
                         boolean messagesOnly, java.util.function.Predicate<Message> messageFilter,
                         app.dita.core.retrieval.ConversationContextSelector contextSelector) {
        this.contextSelector = contextSelector;
        this.supportingMessages = supportingMessages; this.synthesis = synthesis;
        this.messagesOnly = messagesOnly; this.messageFilter = messageFilter;
        this.store = store; this.retriever = retriever; this.access = access; this.k = k; this.maxQuote = maxQuoteChars;
        this.verifier = new AnswerVerifier(store); this.names = names; this.knowledge = knowledge;
    }

    /** A copy with "Related message" lines enabled or disabled (default disabled). */
    public QueryService withSupportingMessages(boolean enabled) {
        return new QueryService(store, retriever, access, k, maxQuote, names, knowledge, enabled, synthesis, messagesOnly, messageFilter, contextSelector);
    }

    /** TASK-053: a copy that runs {@code s} after the deterministic answer (null = baseline only). */
    public QueryService withSynthesis(app.dita.core.answer.synthesis.GroundedSynthesis s) {
        return new QueryService(store, retriever, access, k, maxQuote, names, knowledge, supportingMessages, s, messagesOnly, messageFilter, contextSelector);
    }

    /**
     * TASK-054 messages-only demo mode: a copy that never consults structured knowledge. Answers come from the message
     * retriever (same history filter, evidence view, verifier, synthesis and final gate). {@code eligible} further limits
     * which retrieved messages may be used (null = all).
     */
    public QueryService messagesOnly(java.util.function.Predicate<Message> eligible) {
        return new QueryService(store, retriever, access, k, maxQuote, names, knowledge, supportingMessages, synthesis, true, eligible, contextSelector);
    }

    /**
     * TASK-054 (DEC-050): a copy whose messages-only answers use {@code selector} (bounded pool → exchanges → need
     * coverage → closure → budget) instead of the top-k cut. The knowledge path is unaffected. null = top-k.
     */
    public QueryService withContextSelection(app.dita.core.retrieval.ConversationContextSelector selector) {
        return new QueryService(store, retriever, access, k, maxQuote, names, knowledge, supportingMessages, synthesis, messagesOnly, messageFilter, selector);
    }

    /** The configured retrieval depth of the answer path. */
    public int retrievalDepth() { return k; }

    /** The message hits the answer path would use (history filter, evidence view, eligibility), at most {@code kk}. */
    private List<app.dita.core.retrieval.Hit> answerHits(GroupScope s, String requesterId, String q, int kk, EvidenceView view) {
        if (messagesOnly && contextSelector != null) {
            // the SAME filters as below, applied to every pooled and every reconstructed message
            java.util.function.Function<List<app.dita.core.retrieval.Hit>, List<app.dita.core.retrieval.Hit>> eligible = hits ->
                    access.filter(s, requesterId, hits).stream()
                            .filter(h -> !view.excludedEntirely(h.messageId()))
                            .filter(h -> store.find(s, h.messageId()).filter(m -> !m.deleted())
                                    .filter(m -> messageFilter == null || messageFilter.test(m)).isPresent())
                            .toList();
            return contextSelector.select(s, q, retriever, store, eligible);
        }
        int depth = messageFilter == null ? kk : Math.min(50, kk * 3);           // over-fetch only when some hits may be dropped
        var hits = access.filter(s, requesterId, retriever.retrieve(s, q, depth)).stream()
                .filter(h -> !view.excludedEntirely(h.messageId()));
        if (messageFilter != null) hits = hits.filter(h -> store.find(s, h.messageId()).filter(m -> !m.deleted()).filter(messageFilter).isPresent());
        return hits.limit(kk).toList();
    }

    public boolean synthesisEnabled() { return synthesis != null; }

    public boolean supportingMessagesEnabled() { return supportingMessages; }

    /**
     * Stored knowledge first (RFC-002, deterministic templates + live verbatim quotes); falls back to message retrieval
     * when no knowledge matches. Both paths end in the same verifier, then in a final current-state gate (Codex TASK-047
     * F2): the answer is re-checked against truth as it is AFTER composition; if anything it relies on is no longer
     * current, it is composed once more from fresh state, and if that also fails, Dita says it found nothing.
     */
    public GroundedAnswer ask(GroupScope scope, String requesterId, String question) {
        for (int attempt = 0; attempt < 2; attempt++) {
            GroundedAnswer a = composeOnce(scope, requesterId, question);
            // The gate must observe ONE committed group state, not a series of READ COMMITTED snapshots.
            // atomically excludes same-group writers until validation finishes (PG advisory transaction lock;
            // in-memory monitor). A later commit belongs to the next answer, not to this validation point.
            if (!a.found() || store.atomically(scope, () -> currentAtReturn(scope, a))) return a;
        }
        return GroundedAnswer.notFound(question);
    }

    /**
     * TASK-054 question-time preparation: the messages the ANSWER path's own retrieval finds for this question (the
     * configured retriever: BM25 + graph expansion over stored message links, fused with RRF; Vespa + Neo4j on POSTGRES),
     * after the same history-access filter and evidence-view exclusions. No new ranking: this is what the message path
     * would see. {@link app.dita.core.retrieval.Hit#via()} and {@code inferredContext} say whether a hit came lexically
     * or through a link.
     */
    public List<app.dita.core.retrieval.Hit> relevantMessages(GroupScope scope, String requesterId, String question, int k) {
        return answerHits(scope, requesterId, question, k, evidenceView(scope));
    }

    private GroundedAnswer composeOnce(GroupScope scope, String requesterId, String question) {
        var selection = access.fullHistory() && !messagesOnly       // per-requester history limits: message path only (for now)
                ? new KnowledgeAnswerComposer(store, maxQuote, names, knowledge).select(scope, question) : null;
        var fromKnowledge = selection==null?java.util.Optional.<GroundedAnswer>empty():selection.answer();
        EvidenceView view = evidenceView(scope);
        Retriever filtered = (s, q, kk) -> answerHits(s, requesterId, q, kk, view);
        if (fromKnowledge.isPresent()) {
            GroundedAnswer v = verifier.verify(scope, supportingMessages
                    ? withSupportingMessages(scope, fromKnowledge.get(), filtered, question) : fromKnowledge.get());
            if (v.found() && selection.trace().responsive()) return synthesize(scope, v);
        }
        GroundedAnswer a = new ExtractiveAnswerComposer(filtered, store, maxQuote, view::evidenceTexts, names).answer(scope, question, k);
        GroundedAnswer verified=verifier.verify(scope,a);
        return SelectionPolicy.substantive(store,scope,verified)?synthesize(scope, verified):GroundedAnswer.notFound(question);
    }

    /**
     * TASK-053: synthesis runs only on a verified, found answer and only re-words its evidence. The result is verified
     * again here (and by the final current-state gate in {@link #ask}); if that fails, the deterministic answer stands.
     */
    private GroundedAnswer synthesize(GroupScope scope, GroundedAnswer verified) {
        if (synthesis == null || !verified.found()) return verified;
        GroundedAnswer s = synthesis.apply(scope, verified).answer();
        if (s == verified || !s.found()) return s;
        GroundedAnswer again = verifier.verify(scope, s);
        return again.found() && again.citations().size() == s.citations().size() ? again : verified;
    }

    /**
     * Codex TASK-047 F2: the final gate, on truth read AFTER composition (not the pre-retrieval snapshot). Every citation
     * must still verify (live, verbatim, same author) and be quotable under the CURRENT evidence view; every knowledge item
     * the answer used must still exist, be ACTIVE, unredacted and have a live source.
     */
    boolean currentAtReturn(GroupScope scope, GroundedAnswer a) {
        GroundedAnswer again = verifier.verify(scope, a);
        if (!again.found() || again.citations().size() != a.citations().size()) return false;
        EvidenceView view = evidenceView(scope);
        for (Citation c : a.citations()) {
            List<String> parts = view.restricted().get(c.messageId());
            if (parts != null && parts.stream().noneMatch(p -> p.contains(c.quote()))) return false;
        }
        var edited = a.knowledgeIds().isEmpty() ? java.util.Set.<String>of() : store.editedMessageIds(scope);
        for (String id : a.knowledgeIds()) {
            var k = store.knowledgeById(scope, id).orElse(null);
            if (k == null || k.statementRedacted() || k.onlyDeletedSourcesRemain()) return false;
            if (k.evidence().stream().anyMatch(e -> edited.contains(e.messageId()))) return false;
            if (k.assertion() != null && k.assertion().validity() != app.dita.core.model.Assertion.Validity.ACTIVE) return false;
        }
        return true;
    }

    /**
     * Which parts of a message the message path may quote. {@code restricted}: messages that assert a claim with no
     * current replacement (UNRESOLVED, or SUPERSEDED with no ACTIVE superseder), mapped to the validated ASSERTS spans of
     * their ACTIVE claims that do not overlap any such claim's span (empty = nothing quotable; a claim without a span in
     * the message makes it empty, fail-closed). Every other message is quotable whole. Structural only: no wording rule.
     */
    record EvidenceView(java.util.Map<String, List<String>> restricted) {
        boolean excludedEntirely(String messageId) {
            List<String> parts = restricted.get(messageId);
            return parts != null && parts.isEmpty();
        }
        List<String> evidenceTexts(app.dita.core.model.Message m) {
            List<String> parts = restricted.get(m.id());
            return parts != null ? parts : List.of(m.text());
        }
    }

    EvidenceView evidenceView(GroupScope scope) {
        java.util.List<app.dita.core.model.KnowledgeItem> items = store.knowledge(scope);
        java.util.Map<String, app.dita.core.model.KnowledgeItem> byId = new java.util.HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        java.util.List<app.dita.core.model.KnowledgeRelation> relations;
        try { relations = store.relations(scope); } catch (UnsupportedOperationException e) { relations = java.util.List.of(); }
        java.util.Set<String> hasActiveSuperseder = new java.util.HashSet<>();
        for (var r : relations) {
            if (r.kind() != app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES) continue;
            var from = byId.get(r.fromId());
            if (from != null && from.assertion() != null && from.assertion().validity() == app.dita.core.model.Assertion.Validity.ACTIVE)
                hasActiveSuperseder.add(r.toId());
        }
        // per message: spans of claims with no current replacement ("blocked") and of ACTIVE claims ("allowed")
        java.util.Map<String, List<int[]>> blocked = new java.util.HashMap<>(), allowed = new java.util.HashMap<>();
        java.util.Set<String> unlocalized = new java.util.HashSet<>();
        for (var item : items) {
            var a = item.assertion();
            boolean noCurrent = a != null && (a.validity() == app.dita.core.model.Assertion.Validity.UNRESOLVED
                    || (a.validity() == app.dita.core.model.Assertion.Validity.SUPERSEDED && !hasActiveSuperseder.contains(item.id())));
            boolean active = a == null || a.validity() == app.dita.core.model.Assertion.Validity.ACTIVE;
            boolean showable = !item.statementRedacted() && !item.onlyDeletedSourcesRemain();
            for (var e : item.asserting()) {
                if (noCurrent) {
                    if (e.hasSpan()) blocked.computeIfAbsent(e.messageId(), x -> new ArrayList<>()).add(new int[]{e.spanStart(), e.spanEnd()});
                    else unlocalized.add(e.messageId());
                } else if (active && showable && a != null && e.hasSpan() && !e.sourceDeleted()) {
                    allowed.computeIfAbsent(e.messageId(), x -> new ArrayList<>()).add(new int[]{e.spanStart(), e.spanEnd()});
                }
            }
        }
        java.util.Map<String, List<String>> restricted = new java.util.HashMap<>();
        java.util.Set<String> tainted = new java.util.HashSet<>(blocked.keySet()); tainted.addAll(unlocalized);
        for (String mid : tainted) {
            var m = store.find(scope, mid).orElse(null);
            List<String> parts = new ArrayList<>();
            if (m != null && !m.deleted() && m.text() != null && !unlocalized.contains(mid)) {
                for (int[] sp : allowed.getOrDefault(mid, List.of())) {
                    if (sp[0] < 0 || sp[1] > m.text().length() || sp[1] <= sp[0]) continue;
                    boolean overlaps = blocked.getOrDefault(mid, List.of()).stream().anyMatch(b -> sp[0] < b[1] && b[0] < sp[1]);
                    String part = m.text().substring(sp[0], sp[1]);
                    if (!overlaps && !parts.contains(part)) parts.add(part);
                }
            }
            restricted.put(mid, List.copyOf(parts));
        }
        return new EvidenceView(java.util.Map.copyOf(restricted));
    }

    /**
     * DEC-038 (N-110) + Codex TASK-047 F1: messages from which the message path may quote NOTHING (every part of them is a
     * claim with no current replacement, or such a claim cannot be localized). Mixed messages are not listed: only their
     * ACTIVE claims' validated spans may be quoted ({@link #evidenceView}).
     */
    public java.util.Set<String> messagesAssertingOnlyRetiredWithoutCurrent(GroupScope scope) {
        java.util.Set<String> out = new java.util.HashSet<>();
        evidenceView(scope).restricted().forEach((mid, parts) -> { if (parts.isEmpty()) out.add(mid); });
        return out;
    }

    static final int SUPPORTING_MESSAGES = 2;

    /**
     * DEC-033: message retrieval stays as supporting evidence. Up to {@value #SUPPORTING_MESSAGES} top-ranked live messages
     * that the knowledge answer does not already cite, that share a content word with the question and are not
     * instructions to Dita, are appended as "Related message" lines (verbatim, cited, verified like every citation).
     */
    GroundedAnswer withSupportingMessages(GroupScope scope, GroundedAnswer knowledgeAnswer, Retriever filtered, String question) {
        GroundedAnswer k = knowledgeAnswer;
        java.util.Set<String> q = app.dita.core.text.Tokenizer.tokenSet(question);
        java.util.Set<String> cited = new java.util.HashSet<>();
        k.citations().forEach(c -> cited.add(c.messageId()));
        // never re-introduce what reconciliation retired: messages that assert a SUPERSEDED/UNRESOLVED claim are not supporting evidence
        java.util.Set<String> retired = new java.util.HashSet<>();
        for (var item : store.knowledge(scope)) {
            if (item.assertion() != null && item.assertion().validity() != app.dita.core.model.Assertion.Validity.ACTIVE)   // SUPERSEDED or UNRESOLVED
                item.asserting().forEach(e -> retired.add(e.messageId()));
        }
        java.util.List<Citation> extra = new java.util.ArrayList<>();
        StringBuilder lines = new StringBuilder();
        for (var h : filtered.retrieve(scope, question, this.k)) {
            if (extra.size() >= SUPPORTING_MESSAGES) break;
            if (cited.contains(h.messageId()) || h.inferredContext() || retired.contains(h.messageId())) continue;
            var m = store.find(scope, h.messageId()).orElse(null);
            if (m == null || m.deleted() || m.text() == null || app.dita.core.answer.InjectionGuard.isInstructionToDita(m.text())) continue;
            if (app.dita.core.text.Tokenizer.tokenSet(m.text()).stream().noneMatch(q::contains)) continue;
            String quote = m.text().length() > maxQuote ? m.text().substring(0, maxQuote) : m.text();
            extra.add(new Citation(m.id(), m.authorId(), m.sentAt(), quote, Citation.Evidence.EXPLICIT,
                    app.dita.core.answer.Stance.polarity(m.text()), app.dita.core.answer.Stance.qualified(m.text())));
            lines.append("- Related message from ").append(names.apply(m.authorId())).append(": \"").append(quote).append("\" [").append(m.id()).append("]\n");
            cited.add(m.id());
        }
        if (extra.isEmpty()) return k;
        String d = app.dita.core.knowledge.KnowledgeAnswerComposer.DISCLAIMER;
        String text = k.text().endsWith(d) ? k.text().substring(0, k.text().length() - d.length()) + lines + d : k.text() + "\n" + lines;
        java.util.List<Citation> all = new java.util.ArrayList<>(k.citations());
        all.addAll(extra);
        return new GroundedAnswer(k.question(), text, all, k.conflicting(), k.found(), k.knowledgeIds());
    }

    /** Records which messages Dita's reply cited (C-11 reporting). */
    public void recordReply(GroupScope scope, String replyId, GroundedAnswer a) {
        if (a.found()) store.recordDitaReply(scope, replyId, a.citations().stream().map(Citation::messageId).toList());
    }
}
