package app.dita.core.knowledge;

import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.model.Assertion;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;
import app.dita.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Question-directed answer from stored knowledge (RFC-002 §3.3, DEC-031). Deterministic and grounded:
 * <ol>
 *   <li><b>Retrieve</b> current (ACTIVE) items with a {@link KnowledgeRetriever} (Vespa's knowledge index on POSTGRES,
 *       in-process BM25 otherwise). Every hit is re-read from the store in the caller's scope (isolation).</li>
 *   <li><b>Relevance gate</b>: an item counts only if it shares a content word with the question beyond member names;
 *       a question that names a member selects that member's items (structured speakerId) or items that mention them.
 *       No relevant item → empty (the caller falls back to message evidence).</li>
 *   <li><b>Select</b>: retain relevant retrieved evidence before optional same-subject context (at most
 *       {@value #MAX_LINES} lines). Questions cannot displace facts, but remain eligible for outstanding-question
 *       requests. CONTRADICTS pulls in the opposing claim.</li>
 *   <li><b>Compose</b>: the first line answers directly and says what kind of speech act it is (decision, commitment,
 *       offer, suggestion, tentative, relayed); qualifiers stay attached; SUPERSEDES adds "replaces an earlier message"
 *       with that message's id; every line cites its source message.</li>
 * </ol>
 * Nothing the model wrote is shown: quotes and qualifiers are live verbatim spans, the subject is shown only where it
 * occurs verbatim in the live evidence (N-87), and the speaker comes from metadata. This is
 * selection + structured rendering, not natural-language synthesis (that would need a separately approved model call).
 */
public final class KnowledgeAnswerComposer {
    public static final String DISCLAIMER = "(These are group members' statements, not verified facts.)";
    static final int MAX_LINES = 4, RETRIEVE_K = 10;
    private final MessageStore store;
    private final int maxQuote;
    private final Function<String, String> names;
    private final KnowledgeRetriever retriever;

    public KnowledgeAnswerComposer(MessageStore store, int maxQuoteChars) { this(store, maxQuoteChars, id -> id); }

    public KnowledgeAnswerComposer(MessageStore store, int maxQuoteChars, Function<String, String> names) {
        this(store, maxQuoteChars, names, null);
    }

    /** {@code retriever} null = in-process BM25 ({@link LexicalKnowledgeRetriever}). */
    public KnowledgeAnswerComposer(MessageStore store, int maxQuoteChars, Function<String, String> names, KnowledgeRetriever retriever) {
        this.store = store; this.maxQuote = maxQuoteChars; this.names = names;
        this.retriever = retriever != null ? retriever : new LexicalKnowledgeRetriever(store, names);
    }

    record Line(KnowledgeItem item, Message source, String quote, List<String> qualifiers) {}

    /** IDs/reason codes only: safe to inspect without logging conversation text. */
    public record SelectionTrace(boolean outstandingRequest, List<String> candidateIds, List<String> selectedIds,
                                 Map<String,String> excluded, boolean responsive, String outcome) {}
    public record Selection(Optional<GroundedAnswer> answer, SelectionTrace trace) {}
    private static final class Audit {
        final boolean outstanding; final List<String> candidates=new ArrayList<>();
        final Map<String,String> excluded=new LinkedHashMap<>();
        Audit(boolean outstanding){this.outstanding=outstanding;}
    }
    public Selection select(GroupScope scope,String question) {
        Audit audit=new Audit(SelectionPolicy.outstandingRequest(question));
        Optional<GroundedAnswer> answer=compose(scope,question,audit);
        List<String> selected=answer.map(GroundedAnswer::knowledgeIds).orElse(List.of());
        for(String id:selected)audit.excluded.remove(id);
        for(String id:audit.candidates)if(!selected.contains(id))audit.excluded.putIfAbsent(id,"NO_TOPIC_OR_COVERAGE");
        boolean responsive=answer.filter(a->SelectionPolicy.substantive(store,scope,a)).isPresent();
        return new Selection(answer,new SelectionTrace(audit.outstanding,List.copyOf(audit.candidates),List.copyOf(selected),
                Map.copyOf(audit.excluded),responsive,answer.isEmpty()?"NO_ELIGIBLE_EVIDENCE":responsive?"EVIDENCE_SELECTED":"QUESTION_ONLY_FALLBACK_REQUIRED"));
    }

    public Optional<GroundedAnswer> answer(GroupScope scope, String question) { return select(scope,question).answer(); }

    private Optional<GroundedAnswer> compose(GroupScope scope,String question,Audit audit) {
        Set<String> q = Tokenizer.tokenSet(question);
        if (q.isEmpty()) return Optional.empty();
        Map<String, Line> current = new LinkedHashMap<>();
        Set<String> edited = store.editedMessageIds(scope);                 // TASK-046: one read per answer (T46-5/N-108)
        for (KnowledgeItem k : store.knowledge(scope)) {
            Assertion a = k.assertion();
            if (a == null || a.validity() != Assertion.Validity.ACTIVE || k.statementRedacted() || k.onlyDeletedSourcesRemain()) continue;
            if (derivedFromEditedMessage(k, edited)) continue;
            render(scope, k).ifPresent(l -> current.put(k.id(), l));
        }
        if (current.isEmpty()) return Optional.empty();

        // members named in the question (structured speaker, via the member directory)
        Set<String> named = new LinkedHashSet<>(), nameTokens = new LinkedHashSet<>();
        for (Line l : current.values()) {
            String id = l.item().assertion().speakerId(), display = names.apply(id);
            if (display == null || display.equals(id)) continue;
            Set<String> nt = Tokenizer.tokenSet(display);
            if (!nt.isEmpty() && (q.containsAll(nt) || Tokenizer.tokenSet(question.replace("'s", "")).containsAll(nt))) { named.add(id); nameTokens.addAll(nt); }
        }
        // TASK-045: inflection-tolerant content words ("booked" meets "booking", "kick-off" meets "kickoff")
        Set<String> content = new LinkedHashSet<>(Tokenizer.normalizedSet(question));
        content.removeAll(nameTokens);
        // TASK-046 (Codex P5): question-frame words ("when", "did", "say") are not a topic; possessive name forms neither
        content.removeIf(t -> isFrame(t) || nameTokens.stream().anyMatch(n -> t.startsWith(n + "'") || t.startsWith(n + "’")));

        // 1. retrieval ranking (scope re-checked: only ids that exist as current items of THIS group survive)
        List<Line> ranked = new ArrayList<>();
        Set<String> semantic = new LinkedHashSet<>();
        for (KnowledgeRetriever.Hit h : retriever.retrieve(scope, question, RETRIEVE_K)) {
            audit.candidates.add(h.knowledgeId());
            Line l = current.get(h.knowledgeId());
            if(l==null)audit.excluded.put(h.knowledgeId(),"NOT_CURRENT_OR_RENDERABLE");
            if (l != null && !ranked.contains(l)) ranked.add(l);
            if (l != null && h.semanticAdmission()) semantic.add(l.item().id());
        }
        // 2. relevance gate
        List<Line> relevant = new ArrayList<>();
        if (!named.isEmpty()) {
            // the member's OWN items first (structured speaker), then items that only mention them
            List<Line> own = current.values().stream().filter(l -> named.contains(l.item().assertion().speakerId())).toList();
            List<Line> about = new ArrayList<>(own);
            current.values().stream().filter(l -> !own.contains(l) && mentions(l, nameTokens)).forEach(about::add);
            List<Line> lexicalHits = about.stream().filter(l -> !content.isEmpty() && overlaps(l, content)).toList();
            // Codex review C5: a specific question needs its OBJECT, not just a shared verb ("Has Ana booked the submarine?" is
            // not answered by "I booked the picnic shelter"): the member's on-topic lines together must cover MORE than half
            // of the question's distinct content words (by base form)
            // (refined: a line whose SUBJECT or an entity is named by the question counts on its own — "Ron's speaker proposal"
            // names the subject of Ron's speaker offer — otherwise the lines must jointly cover the question)
            List<Line> bySubject = lexicalHits.stream().filter(l -> namesSubject(l, content)).toList();
            if (!bySubject.isEmpty()) lexicalHits = lexicalHits.stream().filter(l -> bySubject.contains(l) || coversQuestion(List.of(l), content)).toList();
            else if (!coversQuestion(lexicalHits, content)) lexicalHits = List.of();
            List<Line> lexicalTopical = lexicalHits;
            List<Line> topical = about.stream().filter(l -> lexicalTopical.contains(l) || semantic.contains(l.item().id()) || asksForAct(l, content)).toList();
            // TASK-046 (Codex P5): only a BROAD question ("What did Ana say?", no topic beyond the name) may list the member's
            // history; a specific question with no on-topic item of the member gets none of the member's unrelated claims
            List<Line> pool = content.isEmpty() ? about : topical;
            // retrieval order is meaningful only when the question has content beyond the name; the member's OWN items lead
            // (TASK-046: and only when an item matches it by WORDS; a speech-act match alone is ordered by status below)
            boolean lexical = !lexicalTopical.isEmpty();
            if (!topical.isEmpty() && lexical) ranked.stream().filter(pool::contains)
                    .sorted(Comparator.comparingInt((Line l) -> own.contains(l) ? 0 : 1)).forEach(relevant::add);
            pool.stream().filter(l -> !relevant.contains(l))
                    .sorted(Comparator.comparingInt((Line l) -> own.contains(l) ? 0 : 1)
                            .thenComparingInt(l -> rank(l.item().assertion().status())).thenComparing(l -> l.item().assertion().statedAt()))
                    .forEach(relevant::add);
            // TASK-045: other members' on-topic items may answer a question about a member ("Will Ana's bike get fixed?"
            // → Ben's offer); they come after the member's own and mentioning items
            // (Codex C5: each such line must itself cover the question, not just share a verb)
            if (!content.isEmpty()) ranked.stream().filter(l -> !relevant.contains(l) && overlaps(l, content) && (namesSubject(l, content) || coversQuestion(List.of(l), content)))
                    .forEach(relevant::add);
        } else {
            ranked.stream().filter(l -> semantic.contains(l.item().id()) || overlaps(l, content) || (audit.outstanding && l.item().assertion().status()==Assertion.Status.QUESTION)).forEach(relevant::add);
        }
        // A focused participant question names the requested object; a shared verb or venue is not its answer.
        List<Line> focusedQuestions=relevant.stream().filter(l->l.item().assertion().status()==Assertion.Status.QUESTION
                && focusedSubject(l,content)).toList();
        if(!audit.outstanding)for(Line asked:focusedQuestions)for(Line reply:repliesTo(scope,asked,current))
            if(!relevant.contains(reply) && (reply.item().assertion().subjectId().equals(asked.item().assertion().subjectId()) || overlaps(reply,content)))relevant.add(reply);
        // TASK-048: request type, rather than the top hit, decides whether questions can answer.
        if(audit.outstanding) {
            Set<String> requestedTopics=SelectionPolicy.outstandingTopics(question); requestedTopics.removeAll(nameTokens);
            relevant.removeIf(l->{
                boolean keep=l.item().assertion().status()==Assertion.Status.QUESTION
                        ? !questionAnswered(scope,l,current)
                        : SelectionPolicy.outstandingStatement(l.quote());
                keep=keep && (requestedTopics.isEmpty() || overlaps(l,requestedTopics));
                if(!keep)audit.excluded.put(l.item().id(),"NOT_OUTSTANDING_OR_ON_TOPIC");
                return !keep;
            });
            relevant.sort(Comparator.comparingInt(l->l.item().assertion().status()==Assertion.Status.QUESTION?0:1));
        } else {
            List<Line> availableFacts=relevant.stream().filter(l->l.item().assertion().status()!=Assertion.Status.QUESTION)
                    .filter(l->focusedQuestions.isEmpty() || focusedQuestions.stream().anyMatch(asked->onQuestionTopic(asked,l))).toList();
            List<Line> facts=availableFacts.stream().filter(l->semantic.contains(l.item().id()) || namesSubject(l,content) || qualifierMatches(l,content) || baseOverlap(l,content)>=2
                    || requestActMatch(l,content) || baseForms(content).size()<=1 || availableFacts.size()==1).toList();
            if(!facts.isEmpty()) {
                for(Line l:relevant)if(!facts.contains(l))audit.excluded.put(l.item().id(),l.item().assertion().status()==Assertion.Status.QUESTION?"QUESTION_NOT_AN_ANSWER":"WEAK_TOPIC_OVERLAP");
                relevant.clear(); relevant.addAll(facts);
            } else {
                // Retain a question only as explicitly unanswered evidence for compatibility; QueryService must try fallback.
                relevant.removeIf(l->l.item().assertion().status()!=Assertion.Status.QUESTION);
                relevant.removeIf(l->questionAnswered(scope,l,current));
            }
        }
        if(relevant.isEmpty())return Optional.empty();
        // TASK-045: a DECISION/commitment on the top hit's subject leads over a suggestion about the same subject
        String topSubject = relevant.get(0).item().assertion().subjectId();
        // Tony R4: only a DECISION / CONFIRMED_COMMITMENT leads, and only with no or the same scope qualifiers
        Line top = relevant.get(0);
        relevant.stream().filter(l -> l.item().assertion().subjectId().equals(topSubject) && rank(l.item().assertion().status()) <= 1
                        && (l.qualifiers().isEmpty() || Set.copyOf(l.qualifiers()).equals(Set.copyOf(top.qualifiers()))))
                .min(Comparator.comparingInt((Line l) -> rank(l.item().assertion().status())))
                .filter(l -> rank(l.item().assertion().status()) < rank(top.item().assertion().status()))
                .ifPresent(l -> { relevant.remove(l); relevant.add(0, l); });
        // TASK-045: the same speaker's later parallel restatement of a value (no change wording, so no SUPERSEDES) is the
        // newest version: it leads, and the older one is shown marked as earlier
        Line newest = latestVersion(relevant.get(0), current.values());
        if (newest != relevant.get(0)) { relevant.remove(newest); relevant.add(0, newest); }

        // 3. selection: direct answer + its current same-subject items + next relevant hits; contradictions pulled in
        Line primary = relevant.get(0);
        List<Line> chosen = new ArrayList<>(List.of(primary));
        // Diversity first: optional context cannot crowd out other retrieved subjects/facets.
        for(Line l:relevant)if(!chosen.contains(l))chosen.add(l);
        if(!audit.outstanding)current.values().stream().filter(l -> l != primary
                && l.item().assertion().subjectId().equals(primary.item().assertion().subjectId())
                && l.item().assertion().status()!=Assertion.Status.QUESTION && !chosen.contains(l))
                .sorted(Comparator.comparing((Line l)->l.item().assertion().statedAt(),Comparator.reverseOrder()))
                .limit(MAX_LINES-2).forEach(chosen::add);
        List<KnowledgeRelation> relations = relationsOf(scope);
        boolean contradiction = false;
        for (KnowledgeRelation r : relations) {
            if (r.kind() != KnowledgeRelation.Kind.CONTRADICTS) continue;
            for (Line l : List.copyOf(chosen.subList(0, Math.min(chosen.size(), MAX_LINES)))) {
                String other = r.fromId().equals(l.item().id()) ? r.toId() : r.toId().equals(l.item().id()) ? r.fromId() : null;
                if (other == null || current.get(other) == null) continue;
                contradiction = true;
                Line o = current.get(other);
                chosen.remove(o);
                chosen.add(Math.min(chosen.indexOf(l) + 1, chosen.size()), o);
            }
        }
        while (chosen.size() > MAX_LINES) audit.excluded.put(chosen.remove(chosen.size()-1).item().id(),"ANSWER_LINE_LIMIT");

        // 4. composition. Mixed stances: "views differ by context" when the qualifiers differ (RFC-002 QUALIFIES case),
        // otherwise (or with a CONTRADICTS relation) "conflicting reports"; such comparisons are listed chronologically.
        Set<Integer> polarities = new LinkedHashSet<>();
        chosen.forEach(l -> { if (l.item().polarity() != 0) polarities.add(l.item().polarity()); });
        boolean mixed = polarities.size() > 1;
        boolean differByContext = mixed && !contradiction && chosen.stream().filter(l -> l.item().polarity() != 0)
                .map(l -> Set.copyOf(l.qualifiers())).distinct().count() > 1;
        if (mixed && !differByContext) contradiction = true;
        if (mixed || contradiction) chosen.sort(Comparator.comparing((Line l) -> l.item().assertion().statedAt()).thenComparing(l -> l.item().id()));
        StringBuilder sb = new StringBuilder();
        if(audit.outstanding)sb.append("Recorded evidence about outstanding questions (not a complete list):\n");
        if (differByContext) sb.append("Members' views differ by context:\n");
        else if (contradiction) sb.append("Conflicting reports:\n");
        List<Citation> citations = new ArrayList<>();
        boolean onlyQuestions = chosen.stream().allMatch(l -> l.item().assertion().status() == Assertion.Status.QUESTION);
        for (int i = 0; i < chosen.size(); i++) {
            Line l = chosen.get(i);
            boolean earlier = chosen.stream().anyMatch(o -> o != l && isNewerValue(l,o));
            sb.append(describe(l, onlyQuestions && !audit.outstanding, earlier)).append('\n');
            citations.add(citation(l));
            if (i == 0) {
                // SUPERSEDES: name what the current answer replaced, by message id ONLY (N-85/N-86: never re-quote a
                // superseded item, which may be redacted, and never show text that is not a cited, verified quote).
                List<String> replaced = new ArrayList<>();
                for (KnowledgeRelation r : relations) {
                    if (r.kind() != KnowledgeRelation.Kind.SUPERSEDES || !r.fromId().equals(l.item().id())) continue;
                    store.knowledgeById(scope, r.toId()).filter(old -> !old.asserting().isEmpty())
                            .ifPresent(old -> replaced.add("[" + old.asserting().get(0).messageId() + "]"));
                }
                if (!replaced.isEmpty()) sb.append("- This replaces earlier messages that are no longer current: ").append(String.join(" ", replaced)).append('\n');
            }
        }
        sb.append(DISCLAIMER);
        return Optional.of(new GroundedAnswer(question, sb.toString(), citations, contradiction, true,
                chosen.stream().map(l -> l.item().id()).toList()));
    }

    private static boolean focusedSubject(Line asked,Set<String> content) {
        String surface=verbatim(asked.item().assertion().subject(),asked.source().text());
        if(surface==null)return false;
        Set<String> words=baseForms(Tokenizer.tokenSet(surface));
        return !words.isEmpty() && baseForms(content).containsAll(words);
    }
    private static boolean onQuestionTopic(Line asked,Line claim) {
        return claim.item().assertion().subjectId().equals(asked.item().assertion().subjectId())
                || asked.source().id().equals(claim.source().replyToId())
                || baseForms(lineTokens(claim)).containsAll(baseForms(Tokenizer.tokenSet(asked.item().assertion().subject())));
    }
    private boolean questionAnswered(GroupScope scope,Line asked,Map<String,Line> current) {
        return hasReply(scope,asked) || current.values().stream().anyMatch(l->l.item().assertion().status()!=Assertion.Status.QUESTION
                && !SelectionPolicy.outstandingStatement(l.quote()) && l.item().assertion().statedAt().isAfter(asked.item().assertion().statedAt())
                && (l.item().assertion().subjectId().equals(asked.item().assertion().subjectId()) || answersByContent(asked,l)));
    }
    private static boolean qualifierMatches(Line l,Set<String> content) {
        Set<String> want=baseForms(content);
        return l.qualifiers().stream().flatMap(q->baseForms(Tokenizer.normalizedSet(q)).stream()).anyMatch(want::contains);
    }
    private static long baseOverlap(Line l,Set<String> content) {
        Set<String> have=baseForms(lineTokens(l)), want=baseForms(content); want.retainAll(have); return want.size();
    }
    private static boolean requestActMatch(Line l,Set<String> content) {
        return asksForAct(l,content) || (l.item().assertion().status()==Assertion.Status.CONFIRMED_COMMITMENT
                && content.stream().anyMatch(t->t.equals("confirm") || t.equals("confirmed")));
    }

    /** One line: speaker, speech act (decided on / committed to / offered / recommended / …), subject, qualifiers, quote, id. */
    private String describe(Line l, boolean onlyQuestions, boolean earlier) {
        Assertion a = l.item().assertion();
        // N-87: the subject is shown only as it appears verbatim in the live evidence (never the model's wording)
        String subject = verbatim(a.subject(), l.source().text());
        if (subject != null && Tokenizer.tokenSet(subject).isEmpty()) subject = null;            // DEC-033: no pronoun subjects ("they")
        StringBuilder s = new StringBuilder("- ").append(names.apply(a.speakerId())).append(' ').append(verb(a));
        if (subject != null) s.append(' ').append(subject);
        if (!l.qualifiers().isEmpty()) s.append(" (").append(String.join("; ", l.qualifiers())).append(')');
        if (a.certainty() == Assertion.Certainty.HEDGED) s.append(" [tentative]");
        if (a.status() == Assertion.Status.OFFER) s.append(" [offer, not confirmed]");
        if (a.status() == Assertion.Status.QUESTION && onlyQuestions) s.append(" [asked; Dita found no reply to it in the group's messages]");
        if (earlier) s.append(" [earlier statement; a later one exists]");
        return s.append(": \"").append(l.quote()).append("\" [").append(l.source().id()).append(']').toString();
    }

    private Citation citation(Line l) {
        return new Citation(l.source().id(), l.source().authorId(), l.source().sentAt(), l.quote(), Citation.Evidence.EXPLICIT,
                l.item().polarity(), !l.qualifiers().isEmpty());
    }

    private List<KnowledgeRelation> relationsOf(GroupScope scope) {
        try { return store.relations(scope); } catch (UnsupportedOperationException e) { return List.of(); }
    }

    private boolean overlaps(Line l, Set<String> content) {
        if (content.isEmpty()) return false;
        return lineTokens(l).stream().anyMatch(content::contains);
    }

    private static Set<String> lineTokens(Line l) {
        Set<String> t = new LinkedHashSet<>(Tokenizer.normalizedSet(l.quote()));
        String subj = verbatim(l.item().assertion().subject(), l.source().text());
        if (subj != null) t.addAll(Tokenizer.normalizedSet(subj));
        l.qualifiers().forEach(x -> t.addAll(Tokenizer.normalizedSet(x)));
        return t;
    }

    /** A later claim answers a group question by content: >= 2 shared normalised content words, stated after it. */
    private static boolean answersByContent(Line asked, Line l) {
        if (!l.item().assertion().statedAt().isAfter(asked.item().assertion().statedAt())) return false;
        Set<String> have = lineTokens(l);
        // the question's own subject words must appear ("booked the train tickets" does not answer "booked the boat?")
        String subj = verbatim(asked.item().assertion().subject(), asked.source().text());
        if (subj != null && !Tokenizer.tokenSet(subj).isEmpty()
                && !Tokenizer.tokenSet(subj).stream().allMatch(t -> have.contains(t) || have.contains(Tokenizer.base(t)))) return false;
        // Tony R5: count DISTINCT words by base form ("booked" + "book" is one word, not two)
        Set<String> a = baseForms(Tokenizer.tokenSet(asked.quote())), b = baseForms(have);
        a.retainAll(b);
        return a.size() >= 2;
    }

    /** A live message by another member replies (reply structure) to the question's message. */
    private boolean hasReply(GroupScope scope, Line asked) {
        String qid = asked.source().id(), author = asked.source().authorId();
        return store.live(scope).stream().anyMatch(m -> qid.equals(m.replyToId()) && !m.deleted() && !author.equals(m.authorId()));
    }

    /** Current non-question claims asserted in live replies to the question's message. */
    private static List<Line> repliesTo(GroupScope scope, Line asked, Map<String, Line> current) {
        String qid = asked.source().id();
        return current.values().stream().filter(l -> l.item().assertion().status() != Assertion.Status.QUESTION
                && qid.equals(l.source().replyToId()) && !l.source().authorId().equals(asked.source().authorId())).toList();
    }

    /**
     * The newest same-speaker, same-subject, same-predicate, equal-qualifier, non-hedged claim that restates {@code l}
     * in parallel wording (>= 60% of its non-numeric words) with a different value and no continuity, restatement or
     * hypothetical wording; {@code l} itself if none. Answer-time only; nothing is invalidated.
     */
    static Line latestVersion(Line l, java.util.Collection<Line> candidates) {
        // Tony R2: compare each candidate with the CURRENT best (not the start line) and repeat until stable
        Line best = l;
        for (boolean moved = true; moved; ) {
            moved = false;
            for (Line o : candidates) {
                if (o != best && isNewerValue(best, o)) { best = o; moved = true; }
            }
        }
        return best;
    }

    /** {@code o} is a later same-speaker restatement of {@code l} that changes only a VALUE slot (number, time, day, month). */
    static boolean isNewerValue(Line l, Line o) {
        Assertion a = l.item().assertion(), b = o.item().assertion();
        if (a.status() == Assertion.Status.QUESTION || b.status() == Assertion.Status.QUESTION) return false;
        if (!b.speakerId().equals(a.speakerId()) || !b.subjectId().equals(a.subjectId()) || !b.predicate().equals(a.predicate())
                || !b.statedAt().isAfter(a.statedAt()) || b.certainty() == Assertion.Certainty.HEDGED
                || !Set.copyOf(o.qualifiers()).equals(Set.copyOf(l.qualifiers()))) return false;
        String clause = RelationRules.quotedClause(o.item(), o.source().text());
        if (RelationRules.containsAny(clause, RelationRules.CONTINUITY_MARKERS) || RelationRules.containsAny(clause, RelationRules.RESTATEMENT_MARKERS)
                || RelationRules.containsAny(clause, RelationRules.HYPOTHETICAL_MARKERS)) return false;
        Set<String> x = valueAware(l.quote()), y = valueAware(o.quote());
        Set<String> onlyX = new LinkedHashSet<>(x), onlyY = new LinkedHashSet<>(y);
        onlyX.removeAll(y); onlyY.removeAll(x);
        // the change must be a value on BOTH sides ("$20" → "$25"), with at most two other differing words ("now")
        if (onlyX.stream().noneMatch(KnowledgeAnswerComposer::isValue) || onlyY.stream().noneMatch(KnowledgeAnswerComposer::isValue)) return false;
        long other = onlyX.stream().filter(t -> !isValue(t)).count() + onlyY.stream().filter(t -> !isValue(t)).count();
        Set<String> shared = new LinkedHashSet<>(x); shared.retainAll(y);
        return other <= 2 && !shared.isEmpty();
    }

    static final Set<String> VALUE_WORDS = Set.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
            "am", "pm", "noon", "midnight", "ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת");

    static boolean isValue(String t) {
        return t.chars().anyMatch(Character::isDigit) || VALUE_WORDS.contains(t);
    }

    /** Base forms plus every digit-bearing word (the tokenizer drops 1-character words such as "5"). */
    static Set<String> valueAware(String quote) {
        Set<String> out = baseForms(Tokenizer.tokenSet(quote));
        for (String w : Tokenizer.rawWords(quote)) if (w.chars().anyMatch(Character::isDigit)) out.add(w);
        return out;
    }

    /** One canonical form per word (Tony R5). */
    static Set<String> baseForms(java.util.Collection<String> tokens) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tokens) out.add(Tokenizer.isHebrew(t) ? t : Tokenizer.base(t));
        return out;
    }

    /** {@code surface} as written in {@code text} (case-insensitive, original casing kept), else null. */
    static String verbatim(String surface, String text) {
        if (surface == null || surface.isBlank() || text == null) return null;
        String needle = surface.strip().toLowerCase(java.util.Locale.ROOT), hay = text.toLowerCase(java.util.Locale.ROOT);
        int i = hay.indexOf(needle);
        return i < 0 || hay.length() != text.length() ? null : text.substring(i, i + needle.length());
    }

    private static boolean mentions(Line l, Set<String> nameTokens) {
        Set<String> t = Tokenizer.tokenSet(l.quote().replace("'s", ""));
        return nameTokens.stream().anyMatch(t::contains);
    }


    Optional<Line> render(GroupScope scope, KnowledgeItem k) {
        for (KnowledgeItem.Evidence e : k.asserting()) {
            if (e.sourceDeleted() || !e.hasSpan()) continue;
            Optional<Message> m = store.find(scope, e.messageId());
            if (m.isEmpty() || m.get().deleted() || m.get().text() == null || e.spanEnd() > m.get().text().length()) continue;
            String quote = m.get().text().substring(e.spanStart(), e.spanEnd());
            if (quote.length() > maxQuote) quote = quote.substring(0, maxQuote);
            List<String> qualifiers = new ArrayList<>();
            for (Assertion.Qualifier q : k.assertion().qualifiers()) {
                store.find(scope, q.messageId()).filter(x -> !x.deleted() && x.text() != null && q.spanEnd() <= x.text().length())
                        .ifPresent(x -> qualifiers.add(x.text().substring(q.spanStart(), q.spanEnd())));
            }
            return Optional.of(new Line(k, m.get(), quote, qualifiers));
        }
        return Optional.empty();
    }

    /**
     * TASK-046 (Codex P2): knowledge is extracted only from never-edited messages, so an item whose evidence or qualifier
     * message has been edited since was derived from an OLDER revision: its spans no longer index what the speaker now
     * says. Never shown (DeletionService.edit removes such items; this guards every other write path).
     */
    static boolean derivedFromEditedMessage(KnowledgeItem k, Set<String> edited) {
        if (edited.isEmpty()) return false;
        if (k.sourceMessageIds().stream().anyMatch(edited::contains)) return true;
        return k.assertion() != null && k.assertion().qualifiers().stream().anyMatch(q -> edited.contains(q.messageId()));
    }

    /** The question names the line's verbatim subject or one of its entities (by base form), not just a word of its quote. */
    static boolean namesSubject(Line l, Set<String> content) {
        Set<String> want = baseForms(content);
        Set<String> subj = new LinkedHashSet<>();
        String s = verbatim(l.item().assertion().subject(), l.source().text());
        if (s != null) subj.addAll(baseForms(Tokenizer.normalizedSet(s)));
        l.item().assertion().entities().forEach(e -> subj.addAll(baseForms(Tokenizer.normalizedSet(e.surface()))));
        return subj.stream().anyMatch(want::contains);
    }

    /** Lines jointly cover more than half of the question's distinct content words (one word: that word). */
    static boolean coversQuestion(List<Line> lines, Set<String> content) {
        if (lines.isEmpty()) return false;
        Set<String> want = baseForms(content.stream().filter(t -> !t.endsWith("'")).toList());
        if (want.size() <= 1) return true;
        Set<String> have = new LinkedHashSet<>();
        for (Line l : lines) have.addAll(baseForms(lineTokens(l)));
        long covered = want.stream().filter(have::contains).count();
        return covered * 2 > want.size();
    }

    /** TASK-046: words that ask about a member's commitments or decisions (matched on the VALIDATED status, not wording). */
    static final Set<String> COMMITMENT_TOPIC = Set.of("responsible", "responsibility", "responsibilities", "task", "tasks", "job", "jobs",
            "duty", "duties", "assigned", "handling", "handle", "promised", "promise", "committed", "commitment", "commitments",
            "volunteer", "volunteered", "אחראי", "אחראית", "משימה", "משימות", "תפקיד");
    static final Set<String> DECISION_TOPIC = Set.of("decide", "decided", "decision", "decisions", "החליט", "החליטה", "החלטה");

    /**
     * The question asks for the member's commitments/decisions AS SUCH ("What is Sam responsible for?"). B46-8: only when
     * nothing else is asked; "Did Ana promise to pay the deposit?" must match "deposit" by words.
     */
    private static boolean asksForAct(Line l, Set<String> content) {
        Set<String> rest = new LinkedHashSet<>(content);
        rest.removeIf(KnowledgeAnswerComposer::isActWord);
        if (!rest.isEmpty()) return false;
        Assertion.Status s = l.item().assertion().status();
        boolean commitment = s == Assertion.Status.CONFIRMED_COMMITMENT || s == Assertion.Status.OFFER;
        return (commitment && content.stream().anyMatch(t -> inTopic(t, COMMITMENT_TOPIC)))
                || (s == Assertion.Status.DECISION && content.stream().anyMatch(t -> inTopic(t, DECISION_TOPIC)));
    }

    /** Matches by word or by the tokenizer's base form ("responsible" and its base both count). */
    private static boolean inTopic(String t, Set<String> topic) {
        return topic.contains(t) || topic.stream().anyMatch(w -> !Tokenizer.isHebrew(w) && Tokenizer.base(w).equals(t));
    }

    private static boolean isActWord(String t) { return inTopic(t, COMMITMENT_TOPIC) || inTopic(t, DECISION_TOPIC); }

    /** TASK-046: interrogative / reporting words that frame a question but carry no topic (after normalisation). */
    static final Set<String> QUESTION_FRAME = Set.of("when", "where", "how", "why", "which", "whose", "whom", "did", "doe", "done",
            "will", "would", "should", "could", "ever", "say", "said", "says", "saying", "tell", "told", "mention", "mentioned",
            "think", "thinks", "thought", "write", "wrote", "written", "post", "posted", "share", "shared", "talk", "talked",
            "anything", "something", "everything", "all", "recently", "lately", "last", "me", "please", "know", "knows",
            "new", "news", "what's", "whats", "latest", "update", "updates", "hear", "heard", "up", "been", "going", "else",
            "מתי", "איפה", "איך", "למה", "מה", "האם", "אמר", "אמרה", "אמרו", "כתב", "כתבה", "סיפר", "סיפרה", "סיפרו", "משהו",
            "לנו", "לי", "חדש", "עדכון", "שמענו");

    /** Every normalised form the tokenizer produces for a frame word ("what's" → "what'", "anything" → "anyth"). */
    private static final class FrameForms {
        static final Set<String> ALL = new java.util.HashSet<>();
        static { for (String w : QUESTION_FRAME) ALL.addAll(Tokenizer.normalizedSet(w)); }
    }

    /** B46-9: a frame word, also after one Hebrew prefix letter ("ומה", "ומשהו"). */
    static boolean isFrame(String t) {
        if (QUESTION_FRAME.contains(t) || FrameForms.ALL.contains(t)) return true;
        return Tokenizer.isHebrew(t) && t.length() >= 3 && "והבלמשכ".indexOf(t.charAt(0)) >= 0 && QUESTION_FRAME.contains(t.substring(1));
    }

    static int rank(Assertion.Status s) {
        return switch (s) {
            case DECISION -> 0;
            case CONFIRMED_COMMITMENT -> 1;
            case OFFER -> 2;
            case QUESTION -> 9;
            default -> 3;
        };
    }

    static String verb(Assertion a) {
        if (a.status() == Assertion.Status.REPORTED_CLAIM) return "relayed (from " + a.attributedTo() + ") about";
        // TASK-046 (Codex P3): the speech-act verb comes from the VALIDATED status, never from the predicate alone, so a
        // stored item with an inconsistent pair can never be rendered as a decision or a commitment
        switch (a.status()) {
            case DECISION: return "decided on";
            case CONFIRMED_COMMITMENT: return "committed to";
            case OFFER: return "offered";
            case QUESTION: return "asked about";
            default: break;
        }
        return switch (a.predicate()) {
            case "recommends" -> "recommended";
            case "advises_against" -> "advised against";
            default -> "said about";
        };
    }
}
