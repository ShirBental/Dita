package app.dita.core.retrieval;

import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.retrieval.QuestionNeeds.Need;
import app.dita.core.retrieval.QuestionNeeds.Type;
import app.dita.core.store.MessageStore;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * TASK-054 (DEC-050): selects COMPACT, SUFFICIENT raw-message context for one synthesis call. Local and deterministic;
 * it reuses the production retriever (BM25 + semantic + graph fusion) and adds no model call.
 * <ol>
 *   <li><b>Pool</b> (bounded): the production retriever is queried once for the question and once per information need
 *       ({@link QuestionNeeds}), {@code poolPerQuery} hits each, all through the caller's eligibility filter (history
 *       access, evidence view, session, deletions).</li>
 *   <li><b>Exchanges</b>: chat messages are often elliptical ("Noon at Meadow Park, near the west gate." answers "What
 *       time on Saturday?" and never says "picnic"), so every directly relevant hit (anchor) is expanded to its exchange:
 *       the neighbouring messages in conversation order, up to a discourse topic-shift marker ("Speaking of…", "Before
 *       I forget…") or {@code maxSpan} messages either way. Measured on the natural conversation, embedding similarity
 *       did NOT separate exchanges (an unrelated café exchange scored like the picnic one), so this boundary is
 *       structural. These neighbours are only CANDIDATES.</li>
 *   <li><b>Need coverage</b>: per need, candidates are scored by expected answer type (a time for WHEN, a place for
 *       WHERE, a shared condition word for CONDITION), shared need/topic words, retrieval rank, being the next turn
 *       after a relevant question by another member (adjacency pair), the strength of the exchange (how many anchors
 *       it holds) and lateness within it (later statements in an exchange are the current plan). The best
 *       {@code perNeed} qualifying messages per need are kept.</li>
 *   <li><b>Closure</b>: a kept question brings its answer (the next turn by another member); a kept time/place statement
 *       brings a nearby later correction of the same kind ("…, not noon", "instead"), so a superseded proposal never
 *       travels without its correction; an exchange used only through elliptical messages brings its topic opener.</li>
 *   <li><b>Budget</b>: at most {@code budget} messages, returned in conversation order.</li>
 * </ol>
 * Provenance: a hit found by a retrieval channel keeps its channel via. A message added by reconstruction gets
 * {@code via = "context:<relation>"} and {@code inferredContext = true}: it is a heuristic relationship (adjacency,
 * correction, exchange), never presented as an explicit reply. Ambiguity is not resolved by guessing: a relationship
 * that the rules cannot establish simply does not add a message.
 */
public final class ConversationContextSelector {
    public record Settings(int poolPerQuery, int budget, int perNeed, int maxSpan, int maxAnchors, int maxGroupMessages) {
        public static final Settings DEFAULTS = new Settings(24, 12, 3, 20, 8, 5000);
        public Settings {
            if (poolPerQuery < 1 || poolPerQuery > 50 || budget < 1 || budget > 20 || perNeed < 1 || perNeed > 6
                    || maxSpan < 0 || maxSpan > 30 || maxAnchors < 1 || maxAnchors > 20 || maxGroupMessages < 1)
                throw new IllegalArgumentException("invalid context selection bounds");
        }
    }

    /** Discourse markers that open a new exchange (English). */
    static final Pattern SHIFT = Pattern.compile("^\\W*(speaking of|on the subject of|changing (the )?(topic|subject)|change of (topic|subject)"
            + "|unrelated|by the way|btw|before i forget|on another note|different (topic|question)|another question"
            + "|one more [a-z]+ question|random question|anyway)\\b|\\breminds me\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern CORRECTION = Pattern.compile("\\b(not|instead|rather than|change[sd]?|moved?|actually|correction|no longer|now)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A pick for a need must score at least this fraction of the need's best pick. */
    static final double RELATIVE_FLOOR = 0.6;

    /** Diagnostics only (-Ddita.ctx.debug=true): prints ids, needs and scores (never message text) to stderr; off by default. */
    static final boolean DEBUG = Boolean.getBoolean("dita.ctx.debug");
    private final Settings settings;
    public ConversationContextSelector(Settings settings) { this.settings = settings; }
    public ConversationContextSelector() { this(Settings.DEFAULTS); }
    public Settings settings() { return settings; }

    private record Seed(Hit hit, int bestRank, Map<Integer, Integer> rankByQuery) {}
    private record Pick(int pos, double score, String relation) {}

    /**
     * @param eligible the caller's filter (history access, evidence view, session, deletions); applied to EVERY message
     *                 before it can be selected, including reconstructed context
     */
    public List<Hit> select(GroupScope scope, String question, Retriever base, MessageStore store, Function<List<Hit>, List<Hit>> eligible) {
        var analysis = QuestionNeeds.of(question);
        List<Need> needs = analysis.needs();
        Set<String> topic = analysis.topic();
        Set<String> questionWords = QuestionNeeds.content(question);
        // the named subject ("Dr. Cedar"): reconstructed context for a general need must be about it, not merely share a
        // generic word ("recommend") with the question
        Set<String> subject = app.dita.core.text.Tokenizer.entities(question);

        // 1. bounded pool from the production retriever: query 0 = whole question, then one per need
        List<String> queries = new ArrayList<>(); queries.add(question);
        for (Need n : needs) if (!queries.contains(n.query())) queries.add(n.query());
        Map<String, Seed> seeds = new LinkedHashMap<>();
        for (int qi = 0; qi < queries.size(); qi++) {
            List<Hit> hits = eligible.apply(base.retrieve(scope, queries.get(qi), settings.poolPerQuery()));
            for (int r = 0; r < hits.size(); r++) {
                Hit h = hits.get(r);
                final int qIdx = qi, rank = r;
                seeds.compute(h.messageId(), (id, prev) -> {
                    Map<Integer, Integer> ranks = prev == null ? new HashMap<>() : new HashMap<>(prev.rankByQuery());
                    ranks.merge(qIdx, rank, Math::min);
                    Hit keep = prev == null || (prev.hit().inferredContext() && !h.inferredContext()) ? h : prev.hit();
                    return new Seed(keep, Math.min(rank, prev == null ? Integer.MAX_VALUE : prev.bestRank()), ranks);
                });
            }
        }
        if (seeds.isEmpty()) return List.of();

        // conversation order (live messages only); very large groups fall back to the ranked pool
        List<Message> live = new ArrayList<>(store.live(scope));
        if (live.size() > settings.maxGroupMessages()) return fallback(seeds);
        live.sort(Comparator.comparing(Message::sentAt).thenComparing(m -> m.id(), ConversationContextSelector::naturalCompare));
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < live.size(); i++) pos.put(live.get(i).id(), i);
        Map<Integer, Boolean> eligibleCache = new HashMap<>();
        Function<Integer, Boolean> ok = p -> eligibleCache.computeIfAbsent(p, x -> {
            Message m = live.get(x);
            return !m.deleted() && !eligible.apply(List.of(new Hit(m.id(), 0, "context:check", true))).isEmpty();
        });

        // 2. anchors: directly relevant pool hits (a semantic hit, or one sharing a question word), best ranked first
        List<Seed> anchors = seeds.values().stream()
                .filter(s -> pos.containsKey(s.hit().messageId()) && direct(s.hit(), live.get(pos.get(s.hit().messageId())), questionWords))
                .sorted(Comparator.comparingInt(Seed::bestRank).thenComparing(s -> -s.rankByQuery().size()))
                .limit(settings.maxAnchors()).toList();
        if (anchors.isEmpty()) return fallback(seeds);
        double[] strength = new double[live.size()];
        int[] segStart = new int[live.size()], segEnd = new int[live.size()];
        double[] segWeight = new double[live.size()];
        Arrays.fill(segStart, -1);
        for (Seed a : anchors) {
            int p = pos.get(a.hit().messageId());
            int s = p; while (s > 0 && p - s < settings.maxSpan() && !shift(live.get(s))) s--;
            int e = p; while (e + 1 < live.size() && e + 1 - p <= settings.maxSpan() && !shift(live.get(e + 1))) e++;
            double w = 1.0 / (1 + a.bestRank());
            for (int i = s; i <= e; i++) {
                strength[i] += w;
                if (w > segWeight[i]) { segWeight[i] = w; segStart[i] = s; segEnd[i] = e; }   // the strongest anchor's exchange
            }
        }
        double maxStrength = Arrays.stream(strength).max().orElse(1);
        // an exchange is ABOUT the question when its opener names the topic or is itself a semantic hit: then its elliptical
        // statements ("Noon at Meadow Park…") answer this question, not another plan that merely mentions the topic
        boolean[] about = new boolean[live.size()];
        for (int i = 0; i < live.size(); i++) {
            if (segStart[i] < 0) continue;
            Message opener = live.get(segStart[i]);
            Seed os = seeds.get(opener.id());
            about[i] = !Collections.disjoint(QuestionNeeds.content(opener.text()), topic)
                    || (os != null && !os.hit().inferredContext() && HybridRetriever.channels(os.hit().via()).contains("vespa:dense"));
        }
        if (DEBUG) {
            System.err.println("[ctx] needs=" + needs + " topic=" + topic);
            for (Seed a : anchors) { int p = pos.get(a.hit().messageId()); System.err.println("[ctx] anchor " + a.hit().messageId() + " rank=" + a.bestRank() + " via=" + a.hit().via() + " seg=" + segStart[p] + ".." + segEnd[p]); }
        }

        // candidates: seeds in conversation order + every member of an anchor's exchange (eligible only)
        Set<Integer> cands = new TreeSet<>();
        for (Seed s : seeds.values()) { Integer p = pos.get(s.hit().messageId()); if (p != null) cands.add(p); }
        for (int i = 0; i < live.size(); i++) if (strength[i] > 0) cands.add(i);
        cands.removeIf(p -> !ok.apply(p));

        // 3. need coverage
        Map<Integer, Pick> chosen = new LinkedHashMap<>();
        int quota = Math.max(settings.perNeed(), settings.budget() / needs.size());       // the budget is shared by the needs
        for (int ni = 0; ni < needs.size(); ni++) {
            Need n = needs.get(ni);
            int qIdx = queries.indexOf(n.query());
            List<Pick> scored = new ArrayList<>();
            for (int p : cands) {
                Message m = live.get(p);
                Seed seed = seeds.get(m.id());
                boolean typed = n.type() == Type.WHEN || n.type() == Type.WHERE;
                // a time/place need is answered by a statement; a question that mentions a time asks, it does not tell
                boolean type = QuestionNeeds.answers(n, m.text()) && !(typed && m.text().strip().endsWith("?"));
                Set<String> words = QuestionNeeds.content(m.text());
                boolean lex = !Collections.disjoint(words, n.terms()) || !Collections.disjoint(words, topic);
                double rankScore = 0;
                if (seed != null) {
                    Integer r = seed.rankByQuery().get(qIdx), r0 = seed.rankByQuery().get(0);
                    int best = Math.min(r == null ? Integer.MAX_VALUE : r, r0 == null ? Integer.MAX_VALUE : r0);
                    if (best != Integer.MAX_VALUE) rankScore = 1.0 / (1 + best / 4.0);
                }
                boolean qa = answersRelevantQuestion(live, p, n, topic, seeds, qIdx, about[p]);
                double seg = strength[p] / maxStrength;
                double late = segEnd[p] > segStart[p] && segStart[p] >= 0 ? (double) (p - segStart[p]) / (segEnd[p] - segStart[p]) : 0;
                boolean directSeed = seed != null && direct(seed.hit(), m, questionWords);
                boolean offSubject = seed == null && !typed && !subject.isEmpty() && !qa
                        && Collections.disjoint(subject, app.dita.core.text.Tokenizer.entities(m.text()))
                        && Collections.disjoint(subject, words);
                if (offSubject) continue;
                // a time/place need is answered only by a time/place statement (or the answer turn of a relevant question)
                boolean qualifies = typed ? (type && (lex || qa || seg >= 0.5 || seed != null)) || qa
                        : (type && (lex || qa || seg >= 0.5 || seed != null)) || qa || (directSeed && (lex || n.type() == Type.GENERAL));
                if (!qualifies) continue;
                // a time/place statement corrected later in its exchange is superseded for this need (it may still serve another)
                boolean superseded = type && (n.type() == Type.WHEN || n.type() == Type.WHERE) && supersededLater(live, p, segEnd[p], n.type());
                int alsoAnswers = 0;
                for (Need o : needs) if (o != n && o.type() != Type.GENERAL && QuestionNeeds.answers(o, m.text())) alsoAnswers++;
                double score = 1.2 * (type ? 1 : 0) + 0.8 * (lex ? 1 : 0) + 1.0 * rankScore + 0.8 * (qa ? 1 : 0) + (type ? 1.2 : 0.6) * seg + 0.5 * late
                        + 0.4 * alsoAnswers + 1.0 * (about[p] ? 1 : 0) - (superseded ? 1.0 : 0);
                scored.add(new Pick(p, score, qa ? "context:answer" : "context:thread"));
                if (DEBUG) System.err.printf("[ctx]   %s %s score=%.2f about=%s type=%s lex=%s rank=%.2f qa=%s seg=%.2f late=%.2f sup=%s%n", n.type(), m.id(), score, about[p], type, lex, rankScore, qa, seg, late, superseded);
            }
            scored.sort(Comparator.comparingDouble(Pick::score).reversed().thenComparing(Pick::pos, Comparator.reverseOrder()));
            double floor = scored.isEmpty() ? 0 : RELATIVE_FLOOR * scored.get(0).score();   // no quota filling with weak matches
            scored.removeIf(k -> k.score() < floor);
            for (Pick k : scored.subList(0, Math.min(quota, scored.size()))) chosen.merge(k.pos(), k, (a, b) -> a.score() >= b.score() ? a : b);
        }
        if (chosen.isEmpty()) return fallback(seeds);

        // 4. closure: answers to kept questions, corrections of kept time/place statements, exchange openers
        Map<Integer, Pick> closure = new LinkedHashMap<>();
        for (Pick k : List.copyOf(chosen.values())) {
            Message m = live.get(k.pos());
            if (m.text().strip().endsWith("?")) {
                int a = answerTurn(live, k.pos());
                // the answer ranks just above its question: the budget never keeps a question without its answer
                // (observed live: "should we use the library room?" kept, "It is booked" cut)
                if (a >= 0 && ok.apply(a)) closure.merge(a, new Pick(a, k.score() + 0.001, "context:answer"), (x, y) -> x.score() >= y.score() ? x : y);
            }
            // a correction ranks just above what it corrects, so the budget never keeps a proposal without its correction
            int c = correctionOf(live, k.pos());
            if (c >= 0 && ok.apply(c)) closure.merge(c, new Pick(c, k.score() + 0.001, "context:correction"), (x, y) -> x.score() >= y.score() ? x : y);
        }
        closure.forEach((at, k) -> chosen.merge(at, k, (x, y) -> x.score() >= y.score() ? x : y));   // never lowers a pick
        // a single-message hole between two kept messages of the same exchange is filled when it is a dialogue TURN (its
        // author differs from both neighbours): "I cannot speak for teenagers." / [other member's turn] / "That is useful
        // to know." One member's back-to-back unrelated statements are never filled in (no blind adjacency).
        List<Integer> keptPos = new ArrayList<>(new TreeSet<>(chosen.keySet()));
        for (int i = 0; i + 1 < keptPos.size(); i++) {
            int a = keptPos.get(i), b = keptPos.get(i + 1);
            if (b - a != 2 || segStart[a] < 0 || segStart[a] != segStart[b] || shift(live.get(a + 1)) || !ok.apply(a + 1)) continue;
            String mid = live.get(a + 1).authorId();
            if (mid.equals(live.get(a).authorId()) || mid.equals(live.get(b).authorId())) continue;
            double s = Math.min(chosen.get(a).score(), chosen.get(b).score()) - 0.02;
            chosen.putIfAbsent(a + 1, new Pick(a + 1, s, "context:thread"));
        }
        Set<Integer> openers = new LinkedHashSet<>();
        for (int p : chosen.keySet()) {
            if (!Collections.disjoint(QuestionNeeds.content(live.get(p).text()), topic) || topic.isEmpty() || segStart[p] < 0) continue;
            for (int s = p; s >= segStart[p]; s--) {
                if (!Collections.disjoint(QuestionNeeds.content(live.get(s).text()), topic) && ok.apply(s)) { openers.add(s); break; }
            }
        }
        for (int o : openers) chosen.putIfAbsent(o, new Pick(o, 0.5, "context:topic"));

        // 5. budget, then conversation order
        List<Pick> all = new ArrayList<>(chosen.values());
        all.sort(Comparator.comparingDouble(Pick::score).reversed().thenComparing(Pick::pos, Comparator.reverseOrder()));
        List<Pick> kept = new ArrayList<>(all.subList(0, Math.min(settings.budget(), all.size())));
        kept.sort(Comparator.comparingInt(Pick::pos));
        List<Hit> out = new ArrayList<>();
        for (Pick k : kept) {
            Message m = live.get(k.pos());
            Seed s = seeds.get(m.id());
            out.add(s != null && !s.hit().inferredContext() ? s.hit() : new Hit(m.id(), k.score(), k.relation(), true));
        }
        return List.copyOf(out);
    }

    private List<Hit> fallback(Map<String, Seed> seeds) {
        return seeds.values().stream().sorted(Comparator.comparingInt(Seed::bestRank)).limit(settings.budget()).map(Seed::hit).toList();
    }

    /** A hit is directly relevant: found by the semantic channel, or sharing a content word with the question. */
    static boolean direct(Hit h, Message m, Set<String> questionWords) {
        if (h.inferredContext() || h.via() == null) return false;
        if (HybridRetriever.channels(h.via()).contains("vespa:dense")) return true;
        if (HybridRetriever.channels(h.via()).stream().allMatch(HybridRetriever::linkChannel)) return false;
        return !Collections.disjoint(QuestionNeeds.content(m.text()), questionWords);
    }

    /**
     * Whether a later message in the same exchange corrects the time/place at {@code p}: it carries a correction cue and
     * a NEW value of the same kind ("Saturday at one, not noon" corrects "Noon at …"; restating "west gate" does not).
     */
    static boolean supersededLater(List<Message> live, int p, int segEnd, Type type) {
        Set<String> before = QuestionNeeds.values(type, live.get(p).text());
        for (int c = p + 1; c < live.size() && c <= Math.max(segEnd, p); c++) {
            String t = live.get(c).text();
            if (!correctionStatement(t) || !sameKind(type, t)) continue;
            Set<String> after = QuestionNeeds.values(type, t);
            if (!after.isEmpty() && !before.containsAll(after)) return true;
        }
        return false;
    }

    private static final Pattern CONDITIONAL = Pattern.compile("\\b(if|unless|in case)\\b", Pattern.CASE_INSENSITIVE);

    /** A statement with a correction cue; a question or a conditional ("…the hall instead?", "if it rains…") is not one. */
    static boolean correctionStatement(String t) {
        return t != null && CORRECTION.matcher(t).find() && !t.strip().endsWith("?") && !CONDITIONAL.matcher(t).find();
    }

    /** The message itself states a time (WHEN) or a place (WHERE); a capitalised day name is not a new place. */
    static boolean sameKind(Type type, String t) {
        return type == Type.WHEN ? QuestionNeeds.temporal(t) : QuestionNeeds.place(t);
    }

    /** The first later message (≤4, same exchange) that corrects a time/place value of {@code p}, or -1. */
    static int correctionOf(List<Message> live, int p) {
        String text = live.get(p).text();
        for (Type type : List.of(Type.WHEN, Type.WHERE)) {
            Set<String> before = QuestionNeeds.values(type, text);
            if (before.isEmpty()) continue;
            for (int c = p + 1; c < live.size() && c <= p + 4 && !shift(live.get(c)); c++) {
                String ct = live.get(c).text();
                if (!correctionStatement(ct) || !sameKind(type, ct)) continue;
                Set<String> after = QuestionNeeds.values(type, ct);
                if (!after.isEmpty() && !before.containsAll(after)) return c;
            }
        }
        return -1;
    }

    static boolean shift(Message m) { return m.text() != null && SHIFT.matcher(m.text().strip()).find(); }

    /** The next turn by ANOTHER member within two messages after {@code p} (the usual answer slot), or -1. */
    static int answerTurn(List<Message> live, int p) {
        String author = live.get(p).authorId();
        for (int i = p + 1; i < live.size() && i <= p + 2; i++) {
            if (shift(live.get(i))) return -1;
            if (!live.get(i).authorId().equals(author)) return i;
        }
        return -1;
    }

    /** Whether the message at {@code p} is the answer turn of a preceding question relevant to {@code n}. */
    private static boolean answersRelevantQuestion(List<Message> live, int p, Need n, Set<String> topic, Map<String, Seed> seeds, int qIdx, boolean about) {
        for (int q = p - 1; q >= 0 && q >= p - 2; q--) {
            Message qm = live.get(q);
            if (!qm.text().strip().endsWith("?") || qm.authorId().equals(live.get(p).authorId())) continue;
            if (answerTurn(live, q) != p) continue;
            Set<String> words = QuestionNeeds.content(qm.text());
            Seed s = seeds.get(qm.id());
            // a place/time question counts only inside an exchange about this topic (observed live: "Is that in the town
            // hall?" in the lost-property exchange answered a picnic WHERE need)
            boolean relevant = (QuestionNeeds.answers(n, qm.text()) && (about || !Collections.disjoint(words, topic)))
                    || !Collections.disjoint(words, n.terms())
                    || (s != null && s.rankByQuery().containsKey(qIdx) && !Collections.disjoint(words, topic));
            if (relevant) return true;
        }
        return false;
    }

    /** "…m9" before "…m10": ids compared with embedded numbers as numbers. */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int c = Long.compare(Long.parseLong(a.substring(si, Math.min(i, si + 18))), Long.parseLong(b.substring(sj, Math.min(j, sj + 18))));
                if (c != 0) return c;
            } else { if (ca != cb) return Character.compare(ca, cb); i++; j++; }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
