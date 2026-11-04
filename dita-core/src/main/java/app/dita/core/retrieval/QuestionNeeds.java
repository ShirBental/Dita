package app.dita.core.retrieval;

import app.dita.core.text.Tokenizer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TASK-054 (DEC-050): deterministic, local analysis of an English question for message retrieval. No model call.
 * <ul>
 *   <li>{@link #keywordQuery}: the content words only, for the keyword (BM25) channel. Vespa's "any" query removes no
 *       stopwords, so question-frame words ("when", "where", "what happens", "is", "it") otherwise dominate short chat
 *       messages (observed: "whether it happens only when braking" ranked #1 for a picnic question).</li>
 *   <li>{@link #of}: the question's information needs. "When and where is X, and what happens if it rains?" →
 *       WHEN "when is X", WHERE "where is X", CONDITION "what happens if it rains X". The topic words of the whole
 *       question are carried into every need ("X"), so an elliptical clause keeps its subject. At most 4 needs.</li>
 *   <li>{@link #answers}: whether a message carries the expected answer type of a need (a time expression for WHEN,
 *       a place expression for WHERE; for CONDITION and GENERAL a shared need word).</li>
 * </ul>
 * Limits: English patterns only; a question phrased without these cues is one GENERAL need (the previous behaviour).
 */
public final class QuestionNeeds {
    private QuestionNeeds() {}

    public enum Type { WHEN, WHERE, CONDITION, GENERAL }

    /** One information need: its type, its retrieval query, and its own content words (not the shared topic). */
    public record Need(Type type, String query, Set<String> terms) {}

    /** The analysed question: its needs and its topic words (content words outside the question frame). */
    public record Analysis(List<Need> needs, Set<String> topic) {}

    /** Words that frame a question rather than name what it is about. Never used as topic or keyword terms. */
    static final Set<String> FRAME = Set.of("when", "where", "what", "which", "who", "whom", "whose", "why", "how", "time", "day",
            "date", "place", "happen", "happens", "happened", "happening", "still", "now", "currently", "current", "should",
            "would", "could", "will", "shall", "may", "might", "must", "did", "does", "do", "doe", "going", "go", "get",
            "say", "said", "tell", "know", "anything", "something", "please", "there", "then", "else", "much", "many",
            "hour", "hours", "take", "taking", "held", "hold");

    /** FRAME plus the base forms the normalizer produces ("where" → "wher", "taking" → "tak"). */
    static final Set<String> FRAME_FORMS;
    static {
        Set<String> f = new HashSet<>(FRAME);
        for (String w : FRAME) { f.add(Tokenizer.base(w)); f.addAll(Tokenizer.normalizedTokens(w)); }
        FRAME_FORMS = Set.copyOf(f);
    }
    static boolean frame(String t) { return FRAME_FORMS.contains(t) || FRAME_FORMS.contains(Tokenizer.base(t)); }

    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
            "\\?|;|,?\\s+and\\s+(?=(?:(?:at|in|on|for|by)\\s+)?(?:what|when|where|who|why|how|which|is|are|does|do|did|can|could|should|will|would)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COORDINATED_WH = Pattern.compile("^\\s*(when|where)\\s+and\\s+(when|where)\\b(.*)$", Pattern.CASE_INSENSITIVE);

    /** Content words of {@code question} for keyword search (normalized base forms); empty when only frame words remain. */
    public static String keywordQuery(String question) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : Tokenizer.tokens(question)) if (!frame(t)) out.add(t);
        return String.join(" ", out);
    }

    /** The keyword channel with {@link #keywordQuery} applied; a question of frame words only finds nothing. */
    public static Retriever contentWordsOnly(Retriever keyword) {
        return (scope, query, k) -> {
            String kq = keywordQuery(query);
            return kq.isBlank() ? java.util.List.of() : keyword.retrieve(scope, kq, k);
        };
    }

    public static Analysis of(String question) {
        String q = question == null ? "" : question.strip();
        Set<String> topic = new LinkedHashSet<>();
        List<String> clauses = new ArrayList<>();
        for (String c : CLAUSE_SPLIT.split(q)) {
            if (c.isBlank()) continue;
            Matcher co = COORDINATED_WH.matcher(c.strip());
            if (co.matches()) { clauses.add(co.group(1) + co.group(3)); clauses.add(co.group(2) + co.group(3)); }
            else clauses.add(c.strip());
        }
        Map<String, Set<String>> termsByClause = new LinkedHashMap<>();
        for (String c : clauses) termsByClause.put(c, content(c));
        // topic = content words of the first clause that has any ("When and where is X" splits into "When", "where is X";
        // the subject is usually stated once, early)
        for (String c : clauses) if (!termsByClause.get(c).isEmpty()) { topic.addAll(termsByClause.get(c)); break; }
        List<Need> needs = new ArrayList<>();
        for (String c : clauses) {
            Type t = type(c);
            Set<String> own = new LinkedHashSet<>(termsByClause.get(c));
            Set<String> missing = new LinkedHashSet<>(topic); missing.removeAll(own);
            String query = missing.isEmpty() ? c : c + " " + String.join(" ", missing);
            Set<String> needTerms = new LinkedHashSet<>(own);
            if (t == Type.CONDITION) needTerms = conditionTerms(c);
            needs.add(new Need(t, query, Set.copyOf(needTerms)));
            if (needs.size() == 4) break;
        }
        if (needs.isEmpty()) needs.add(new Need(Type.GENERAL, q, Set.copyOf(content(q))));
        if (needs.size() == 1 && needs.get(0).type() == Type.GENERAL) needs = List.of(new Need(Type.GENERAL, q, Set.copyOf(content(q))));
        return new Analysis(List.copyOf(needs), Set.copyOf(topic));
    }

    static Set<String> content(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : Tokenizer.normalizedTokens(text)) if (!frame(t) && t.length() > 1) out.add(t);
        return out;
    }

    private static final Pattern WHEN_Q = Pattern.compile("^\\s*(when\\b|what\\s+(time|day|date|hour)\\b|at\\s+what\\s+(time|hour)\\b|how\\s+(late|early|long)\\b)|\\b(what|which)\\s+(time|day|date|hour)\\b|\\bopening\\s+hours\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE_Q = Pattern.compile("^\\s*where\\b|\\b(which|what)\\s+(place|venue|room|address|location)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IF_Q = Pattern.compile("\\b(if|in\\s+case|unless)\\b", Pattern.CASE_INSENSITIVE);

    static Type type(String clause) {
        if (WHEN_Q.matcher(clause).find()) return Type.WHEN;
        if (WHERE_Q.matcher(clause).find()) return Type.WHERE;
        if (IF_Q.matcher(clause).find()) return Type.CONDITION;
        return Type.GENERAL;
    }

    /** The words of the condition itself ("what happens if it rains" → {rain}). */
    static Set<String> conditionTerms(String clause) {
        Matcher m = IF_Q.matcher(clause);
        String tail = m.find() ? clause.substring(m.end()) : clause;
        Set<String> out = content(tail);
        return out.isEmpty() ? content(clause) : out;
    }

    // ---------------------------------------------------------------- expected answer types (English cues)

    private static final Pattern TEMPORAL = Pattern.compile(
            "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|weekend|weekday|today|tonight|tomorrow|yesterday"
                    + "|morning|afternoon|evening|noon|midday|midnight|o'clock|half\\s+past|quarter\\s+(past|to)"
                    + "|january|february|march|april|june|july|august|september|october|november|december)\\b"
                    + "|\\b\\d{1,2}(:\\d{2})\\b|\\b\\d{1,2}\\s?(am|pm)\\b"
                    + "|\\b(at|from|until|till|by|starts?\\s+at)\\s+(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d{1,2})\\b"
                    + "|\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)-(thirty|fifteen|forty-five)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACE_NOUN = Pattern.compile(
            "\\b(park|gate|street|st|avenue|road|lane|square|station|stop|hall|room|centre|center|studio|library|cafe|caf\u00e9"
                    + "|office|building|floor|entrance|exit|venue|court|courtyard|garden|beach|church|school|clinic|lobby|desk)\\b",
            Pattern.CASE_INSENSITIVE);

    /** The concrete values of a time or place cue in {@code text} (to tell a correction from a restatement). */
    public static Set<String> values(Type type, String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        if (type == Type.WHEN) { Matcher m = TEMPORAL.matcher(text); while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")); }
        if (type == Type.WHERE) {
            Matcher m = PLACE_NOUN.matcher(text); while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
            for (String e : Tokenizer.entities(text)) if (!temporal(e)) out.add(e);   // "Saturday" is not a place
        }
        return out;
    }

    public static boolean temporal(String text) { return text != null && TEMPORAL.matcher(text).find(); }
    public static boolean place(String text) { return text != null && PLACE_NOUN.matcher(text).find(); }

    /** Whether {@code text} carries the expected answer type of {@code need} (see class comment). */
    public static boolean answers(Need need, String text) {
        return switch (need.type()) {
            case WHEN -> temporal(text);
            case WHERE -> place(text);
            case CONDITION, GENERAL -> !Collections.disjoint(content(text), need.terms());
        };
    }
}
