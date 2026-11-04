package app.dita.core.answer.synthesis;

import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.knowledge.KnowledgeAnswerComposer;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TASK-053: optional synthesis stage after Dita's deterministic, verified answer (the baseline). It never retrieves,
 * authorizes or widens evidence: the generator sees only the citations of the baseline answer (already scoped, filtered
 * by history access, current-state checked and verified), with speakers pseudonymised. Fail-closed throughout: an
 * ineligible input, a generator error or any {@link SynthesisValidator} violation returns the baseline unchanged.
 * <ul>
 *   <li>Egress guard (OPEN-6/C-1, as for extraction): every cited message must exist in this scope and be SYNTHETIC,
 *       otherwise nothing is sent.</li>
 *   <li>An explicit abstention is honoured (conservative): Dita says it cannot answer reliably.</li>
 *   <li>The returned answer cites only baseline citations (same verbatim Citation objects), keeps the baseline's
 *       knowledge ids and conflict flag, and still goes through QueryService's verifier and final current-state gate.</li>
 * </ul>
 */
public final class GroundedSynthesis {
    public static final String ABSTAIN_TEXT = "I can't answer that reliably from the group's messages.";
    static final Pattern ALIAS = Pattern.compile("\\bP(\\d{1,3})\\b");
    public static final int MAX_QUESTION_CHARS = 500;

    public enum Outcome { SYNTHESIZED, ABSTAINED, FALLBACK_INVALID, FALLBACK_ERROR, FALLBACK_NOT_ELIGIBLE, SKIPPED_NOT_FOUND }

    public record Result(GroundedAnswer answer, Outcome outcome, List<String> violations, String synthesizerVersion) {}

    /**
     * Receives every synthesis result (outcome, violations, answer). Used by controlled trials to record the ACTUAL
     * validation outcome; absent in production. Violation text may echo draft wording: do not log it in production.
     */
    public interface Observer extends Consumer<Result> {}

    private final boolean enforceLanguageRules;
    private final MessageStore store;
    private final AnswerSynthesizer synthesizer;
    private final Function<String, String> names;
    private final SynthesisValidator validator = new SynthesisValidator();
    private final Consumer<Result> observer;
    private final Map<Outcome, AtomicLong> counts = new LinkedHashMap<>();

    public GroundedSynthesis(MessageStore store, AnswerSynthesizer synthesizer, Function<String, String> names) {
        this(store, synthesizer, names, r -> {});
    }

    /** {@code observer}: receives every result (tests, metrics; it receives violation text, which may echo draft wording — do not log it in production). Must not throw. */
    public GroundedSynthesis(MessageStore store, AnswerSynthesizer synthesizer, Function<String, String> names, Consumer<Result> observer) {
        this(store, synthesizer, names, observer, true);
    }

    public GroundedSynthesis(MessageStore store, AnswerSynthesizer synthesizer, Function<String, String> names,
                             Consumer<Result> observer, boolean enforceLanguageRules) {
        this.enforceLanguageRules = enforceLanguageRules;
        this.store = store; this.synthesizer = synthesizer; this.names = names; this.observer = observer;
        for (Outcome o : Outcome.values()) counts.put(o, new AtomicLong());
    }

    public Map<Outcome, Long> metrics() {
        Map<Outcome, Long> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public Result apply(GroupScope scope, GroundedAnswer baseline) {
        Result r = applyInner(scope, baseline);
        counts.get(r.outcome()).incrementAndGet();
        observer.accept(r);
        return r;
    }

    private Result applyInner(GroupScope scope, GroundedAnswer baseline) {
        String version = synthesizer.version();
        if (!baseline.found() || baseline.citations().isEmpty()) return new Result(baseline, Outcome.SKIPPED_NOT_FOUND, List.of(), version);

        // Egress guard + speaker pseudonyms (per request, in citation order)
        Map<String, String> alias = new LinkedHashMap<>();
        for (Citation c : baseline.citations()) {
            Message m = store.find(scope, c.messageId()).orElse(null);
            if (m == null || m.deleted() || m.sourceKind() != SourceKind.SYNTHETIC || !scope.groupId().equals(m.groupId()))
                return new Result(baseline, Outcome.FALLBACK_NOT_ELIGIBLE, List.of("evidence not eligible for synthesis (non-synthetic, deleted or out of scope)"), version);
            alias.computeIfAbsent(c.authorId(), a -> "P" + (alias.size() + 1));
        }
        // F8: the question is egress too; bound it. F7: text that already looks like a pseudonym would be ambiguous.
        String question = baseline.question() == null ? "" : baseline.question();
        if (question.length() > MAX_QUESTION_CHARS)
            return new Result(baseline, Outcome.FALLBACK_NOT_ELIGIBLE, List.of("question longer than " + MAX_QUESTION_CHARS + " chars"), version);
        List<String> aliasLike = new ArrayList<>();
        aliasLike.add(question);
        baseline.citations().forEach(c -> { aliasLike.add(c.quote()); aliasLike.add(c.authorId()); aliasLike.add(names.apply(c.authorId())); });
        if (aliasLike.stream().anyMatch(t -> t != null && ALIAS.matcher(t).find()))
            return new Result(baseline, Outcome.FALLBACK_NOT_ELIGIBLE, List.of("input contains pseudonym-like text (P<n>)"), version);

        Map<String, String> kindByMessage = kinds(scope, baseline);
        List<AnswerSynthesizer.Evidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Citation c : baseline.citations()) {
            if (!seen.add(c.messageId())) continue;
            evidence.add(new AnswerSynthesizer.Evidence(c.messageId(), alias.get(c.authorId()),
                    c.sentAt() == null ? "" : c.sentAt().toString().substring(0, 10), kindByMessage.getOrDefault(c.messageId(), "MESSAGE"),
                    c.polarity(), c.qualified(), pseudonymise(c.quote(), alias)));   // F7: names inside quotes too
        }
        // F6: replaced ids come from structured SUPERSEDES relations of the knowledge the baseline used, never from text
        List<String> replaced = replacedIds(scope, baseline, seen);

        String draft = pseudonymise(stripDisclaimer(baseline.text()), alias);
        // G2: raw-message answers (no knowledge used) get the local context check; the knowledge path is unchanged.
        AnswerSynthesizer.ContextCheck context = null;
        if (baseline.knowledgeIds().isEmpty()) {
            Map<String, Set<String>> ctx = new LinkedHashMap<>();
            for (Citation c : baseline.citations()) ctx.merge(c.messageId(), app.dita.core.answer.ContextCues.stated(c.quote()),
                    (a, b) -> { Set<String> u = new LinkedHashSet<>(a); u.addAll(b); return u; });
            context = new AnswerSynthesizer.ContextCheck(app.dita.core.answer.ContextCues.of(question), ctx,
                    app.dita.core.answer.ExtractiveAnswerComposer.conflictGroups(baseline.citations()));
        }
        Map<String, java.time.Instant> sentAt = new LinkedHashMap<>();          // DEC-052: local only, for "later"
        for (Citation c : baseline.citations()) if (c.sentAt() != null) sentAt.putIfAbsent(c.messageId(), c.sentAt());
        var request = new AnswerSynthesizer.Request(question, draft, evidence, replaced, baseline.conflicting(), context, sentAt,
                baseline.knowledgeIds().isEmpty() ? ReferenceSupport.selectedQuestions(store, scope, evidence) : Map.of());

        AnswerSynthesizer.Draft out;
        try { out = synthesizer.synthesize(request); }
        catch (RuntimeException e) {
            return new Result(baseline, Outcome.FALLBACK_ERROR, List.of("synthesizer failed: " + e.getClass().getSimpleName()), version);
        }
        if (out == null) return new Result(baseline, Outcome.FALLBACK_ERROR, List.of("synthesizer returned nothing"), version);
        if (out.abstain()) {
            return new Result(new GroundedAnswer(baseline.question(), ABSTAIN_TEXT, List.of(), false, false), Outcome.ABSTAINED, List.of(), version);
        }
        out = CitationMarkers.canonicalize(request, out);
        var verdict = enforceLanguageRules ? validator.validate(request, out) : validator.validateReferences(request, out);
        if (!verdict.ok()) return new Result(baseline, Outcome.FALLBACK_INVALID, verdict.violations(), version);

        // Map back: pseudonyms → display names; citations = the baseline's own verified Citation objects actually used
        Map<String, String> reverse = new HashMap<>();
        alias.forEach((author, p) -> reverse.put(p, names.apply(author)));
        String text = ALIAS.matcher(out.answer().strip()).replaceAll(r -> Matcher.quoteReplacement(reverse.getOrDefault(r.group(), r.group())));
        Set<String> used = new LinkedHashSet<>(out.citedIds());
        used.addAll(verdict.contextualSourceIds()); // resolved subjects retain the question as provenance
        List<Citation> citations = baseline.citations().stream().filter(c -> used.contains(c.messageId())).toList();
        String finalText = text + "\n" + KnowledgeAnswerComposer.DISCLAIMER;
        return new Result(new GroundedAnswer(baseline.question(), finalText, citations, baseline.conflicting(), true, baseline.knowledgeIds()),
                Outcome.SYNTHESIZED, List.of(), version);
    }

    /** message id → status of the knowledge claim it asserts (only knowledge the baseline answer used). */
    private Map<String, String> kinds(GroupScope scope, GroundedAnswer baseline) {
        Map<String, String> out = new HashMap<>();
        for (String id : baseline.knowledgeIds()) {
            KnowledgeItem k = store.knowledgeById(scope, id).orElse(null);
            if (k == null || k.assertion() == null) continue;
            String status = k.assertion().status().name();
            k.asserting().forEach(e -> out.merge(e.messageId(), status, (a, b) -> a.equals(b) ? a : "MIXED"));
        }
        return out;
    }

    static String stripDisclaimer(String text) {
        String d = KnowledgeAnswerComposer.DISCLAIMER;
        String t = text == null ? "" : text.strip();
        return t.endsWith(d) ? t.substring(0, t.length() - d.length()).strip() : t;
    }

    /** F6: messages asserting knowledge that the baseline's knowledge SUPERSEDES (this group only, not already cited). */
    private List<String> replacedIds(GroupScope scope, GroundedAnswer baseline, Set<String> cited) {
        List<app.dita.core.model.KnowledgeRelation> relations;
        try { relations = store.relations(scope); } catch (UnsupportedOperationException e) { return List.of(); }
        Set<String> used = new LinkedHashSet<>(baseline.knowledgeIds());
        List<String> out = new ArrayList<>();
        for (var r : relations) {
            if (r.kind() != app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES || !used.contains(r.fromId())) continue;
            store.knowledgeById(scope, r.toId()).ifPresent(old -> old.asserting().forEach(e -> {
                String id = e.messageId();
                if (!cited.contains(id) && !out.contains(id) && store.find(scope, id).isPresent()) out.add(id);
            }));
        }
        return out;
    }

    /** F7: replaces every author's display name (and raw id) with its pseudonym in ONE pass (longest alternative first),
     *  so a replacement can never be re-matched by a later key. */
    private String pseudonymise(String text, Map<String, String> alias) {
        Map<String, String> keys = new HashMap<>();
        alias.forEach((author, p) -> {
            String n = names.apply(author);
            if (n != null && !n.isBlank()) keys.putIfAbsent(n, p);
            if (author != null && !author.isBlank()) keys.putIfAbsent(author, p);
        });
        if (keys.isEmpty()) return text;
        List<String> ordered = new ArrayList<>(keys.keySet());
        ordered.sort((a, b) -> b.length() - a.length());
        StringBuilder alt = new StringBuilder();
        for (String k : ordered) { if (alt.length() > 0) alt.append('|'); alt.append(Pattern.quote(k)); }
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alt + ")(?![\\p{L}\\p{N}])");
        return p.matcher(text).replaceAll(r -> Matcher.quoteReplacement(keys.get(r.group())));
    }
}
