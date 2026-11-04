package app.dita.core.answer;

import app.dita.core.text.Tokenizer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TASK-054 G2: deterministic, local context cues for raw-message answers (English only).
 * <p>
 * A QUESTION's context is the content words of any "for …" phrase in it ({@link #of}). A STATEMENT's context
 * ({@link #stated}) is narrower: only a "for …" phrase that follows a stance cue inside the same clause ("excellent for
 * babies" → {babie}, "not recommended for teenagers" → {teenager}); "excellent, we've been going for years" has NO
 * context, because "for years" is not what the stance is about. No such phrase means the context is UNKNOWN (empty
 * set), never "general".
 * <p>
 * Two opposing statements (one positive, one negative) are a genuine conflict UNLESS (a) both have an explicit context
 * and those contexts share no content word, or (b) both explicitly name their subject ({@link Tokenizer#entities}) and
 * the names share nothing ("Coach Willow" vs "Dr. Cedar"). Names count only if they are not sentence-initial, are in the
 * clause of the stance cue, and are not calendar words ({@link #subjects}); a pronoun ("He was …", "Avoid him.") names
 * nothing, so it stays uncertain. Unknown context is therefore never proof that a conflict is absent.
 * <p>
 * Matching is lexical (base forms). Generic population words ("children", "kids", "everyone", "people", "family" …)
 * overlap EVERY context, so "avoid him for children" still conflicts with "excellent for babies". KNOWN LIMITATION:
 * other synonyms ("babies" vs "infants") are still different words. For R10 that is stricter (a possible fallback); for
 * R5 it is MORE LENIENT (a same-population conflict phrased with synonyms is not detected as one).
 */
public final class ContextCues {
    private ContextCues() {}

    /** "for" + up to four words, stopping at punctuation or a clause connector. */
    private static final Pattern FOR = Pattern.compile(
            "\\bfor\\s+((?:[\\p{L}\\p{N}'’-]+\\s*){1,4}?)(?=[.,;:!?()\\n]|\\s+(?:and|but|or|because|since|when|if|so|he|she|they|we|it)\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> NOT_CONTEXT = Set.of("a", "an", "the", "our", "my", "your", "their", "his", "her", "its", "this",
            "that", "these", "those", "any", "some", "all", "every", "me", "us", "you", "them", "him", "it", "again", "now", "sure");

    /** Content words (normalized base forms) of every "for …" phrase in {@code text}; empty = unknown context. */
    public static Set<String> of(String text) {
        if (text == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = FOR.matcher(text);
        while (m.find()) {
            for (String t : Tokenizer.normalizedTokens(m.group(1))) if (!NOT_CONTEXT.contains(t)) out.add(t);
        }
        return Set.copyOf(out);
    }

    /** Clause boundaries for {@link #stated}: punctuation and clause connectors. */
    private static final Pattern CLAUSE = Pattern.compile("[.,;:!?()\\n]|\\s(?:but|and|or|because|since|although|though|while|whereas)\\s",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern TITLE_DOT = Pattern.compile("\\b(Dr|Mr|Mrs|Ms|Mx|Prof|St|Jr|Sr|Mt)\\.", Pattern.CASE_INSENSITIVE);

    /** A statement's context: "for …" phrases after a stance cue in the same clause; empty = unknown. */
    public static Set<String> stated(String text) {
        if (text == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        String t = TITLE_DOT.matcher(text).replaceAll("$1");           // "Dr. Cedar" is not a clause boundary
        for (String clause : CLAUSE.split(t)) {
            int cue = Stance.cueEnd(clause.toLowerCase(java.util.Locale.ROOT));
            if (cue >= 0) out.addAll(of(clause.substring(cue)));
        }
        return Set.copyOf(out);
    }

    /** One stated opinion: id, polarity sign, its context words and the names it mentions. */
    public record Stated(String id, int polarity, Set<String> context, Set<String> subjects) {
        public Stated { context = Set.copyOf(context); subjects = Set.copyOf(subjects); }
        public static Stated of(String id, int polarity, String text) {
            return new Stated(id, Integer.signum(polarity), ContextCues.stated(text), ContextCues.subjects(text));
        }
    }

    /** Generic population words: they overlap every context (Natasha M3). Normalized like the contexts. */
    private static final Set<String> GENERIC = normalized("children child kids kid everyone everybody anyone anybody people person "
            + "family families all ages age patients patient parents parent folks someone everything general");
    private static final Set<String> CALENDAR = Set.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
            "today", "tomorrow", "yesterday");
    private static Set<String> normalized(String words) {
        Set<String> out = new HashSet<>(); for (String w : words.split(" ")) out.addAll(Tokenizer.normalizedTokens(w)); return Set.copyOf(out);
    }

    /** True when the two opposing statements are explicitly about different contexts (both known, no shared or generic word). */
    public static boolean distinctContexts(Set<String> a, Set<String> b) {
        return !a.isEmpty() && !b.isEmpty() && Collections.disjoint(a, b)
                && Collections.disjoint(a, GENERIC) && Collections.disjoint(b, GENERIC);
    }

    /** Names the stance is about: not sentence-initial, inside a clause with a stance cue, not calendar words (Natasha M2). */
    public static Set<String> subjects(String text) {
        if (text == null) return Set.of();
        String t = TITLE_DOT.matcher(text).replaceAll("$1");
        Set<String> out = new LinkedHashSet<>();
        for (String sentence : t.split("[.!?\\n]+")) {
            Set<String> names = Tokenizer.entities(sentence.strip());       // skips the sentence's first word
            for (String clause : CLAUSE.split(sentence)) {
                if (Stance.cueEnd(clause.toLowerCase(java.util.Locale.ROOT)) < 0) continue;
                for (String w : clause.split("[^\\p{L}\\p{N}'\"]+")) {
                    String n = w.toLowerCase(java.util.Locale.ROOT).replaceAll("'s$", "");
                    if (names.contains(n) && !CALENDAR.contains(n)) out.add(n);
                }
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Genuine conflicts: every (positive, negative) pair whose contexts are not explicitly distinct, grouped into
     * connected components. Each component lists its member ids; the validator requires both sides of each component.
     */
    public static List<Set<String>> conflictGroups(Collection<Stated> statements) {
        List<Stated> pos = statements.stream().filter(s -> s.polarity() > 0).toList();
        List<Stated> neg = statements.stream().filter(s -> s.polarity() < 0).toList();
        Map<String, String> parent = new HashMap<>();
        java.util.function.UnaryOperator<String> find = new java.util.function.UnaryOperator<>() {
            public String apply(String x) { String p = parent.getOrDefault(x, x); return p.equals(x) ? x : apply(p); }
        };
        Set<String> inConflict = new LinkedHashSet<>();
        for (Stated p : pos) for (Stated n : neg) {
            if (distinctContexts(p.context(), n.context())
                    || (!p.subjects().isEmpty() && !n.subjects().isEmpty() && Collections.disjoint(p.subjects(), n.subjects()))) continue;
            inConflict.add(p.id()); inConflict.add(n.id());
            String a = find.apply(p.id()), b = find.apply(n.id());
            if (!a.equals(b)) parent.put(a, b);
        }
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        for (String id : inConflict) groups.computeIfAbsent(find.apply(id), k -> new LinkedHashSet<>()).add(id);
        return groups.values().stream().map(Set::copyOf).toList();
    }
}
