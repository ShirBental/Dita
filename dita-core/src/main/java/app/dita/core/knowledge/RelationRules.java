package app.dita.core.knowledge;

import app.dita.core.model.Assertion;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.KnowledgeRelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RFC-002 §3.5: Dita recomputes relations BY RULE from validated items; nothing here comes from the model. Implemented:
 * <ul>
 *   <li><b>SUPERSEDES</b> (later → earlier): same speaker, same subject, later in time, and either the same predicate
 *       (temporal change; for the generic "states" predicate only with explicit change wording, DEC-030) or a stronger status (OFFER → CONFIRMED_COMMITMENT; anything → DECISION). The earlier item
 *       becomes {@code SUPERSEDED} and is no longer shown as current.</li>
 *   <li><b>CONTRADICTS</b> (recorded once, later → earlier): same subject, opposite polarity, equal qualifier kinds,
 *       neither hedged, different speakers. Both stay ACTIVE and are shown side by side. Deviation from the RFC table
 *       wording ("same predicate"): polarity is derived from the predicate (recommends +1 / advises_against −1), so
 *       "same predicate, opposite polarity" can never hold. Factual contradictions between two "states" items have no
 *       rule-safe signal and are NOT detected.</li>
 * </ul>
 * Not implemented (no rule-safe signal yet): CORRECTS (needs CORRECTION items), SUPPORTS, QUALIFIES, ANSWERS.
 * QUESTION items never take part.
 */
public final class RelationRules {
    public record Outcome(List<KnowledgeRelation> relations, Set<String> superseded) {}

    private RelationRules() {}

    /** {@code existing}: the group's stored items; {@code added}: newly accepted items. Only pairs involving an added item. */
    /** Explicit change wording; a generic "states" item supersedes an earlier one only with such a signal (DEC-030). */
    static final List<String> CHANGE_MARKERS = List.of("correction", "update", "actually", "changed my mind", "no longer",
            "instead", "not anymore", "i got that wrong", "i was wrong", "scratch that", "change of plan", "moved to",
            "תיקון", "עדכון", "בעצם", "במקום", "טעיתי");
    /** TASK-045: a restatement is never a replacement. */
    static final List<String> RESTATEMENT_MARKERS = List.of("as i said", "as mentioned", "like i said", "reminder", "to repeat",
            "as i wrote", "just repeating", "כמו שאמרתי", "תזכורת");
    /** TASK-045: a hypothetical never replaces a fact. */
    static final List<String> HYPOTHETICAL_MARKERS = List.of("if ", "might", "could ", "maybe", "perhaps", "in case", "possibly",
            "אם ", "אולי");
    /** TASK-045: additive or continuity wording means the earlier claim still holds ("also", "still", "no update"). */
    static final List<String> CONTINUITY_MARKERS = List.of("also", "too", "as well", "in addition", "additionally", "still",
            "no update", "no change", "unchanged", "same as before", "remains", "עדיין", "בנוסף", "אין שינוי", "אין עדכון");
    /** TASK-045: withdrawal of an earlier offer/commitment by the same speaker. */
    static final List<String> RETRACTION_MARKERS = List.of("after all", "can't", "cannot", "can not", "won't be able", "not make it",
            "can no longer", "no longer", "not anymore", "cancel", "cancelled", "canceled", "pull out", "drop out", "backing out",
            "לא אוכל", "לא יכול", "לא יכולה", "מבטל", "מבטלת");
    /** N-95: idioms that contain retraction words but are not retractions. */
    static final List<String> NON_RETRACTION_IDIOMS = List.of("can't wait", "cannot wait", "can't believe", "can't stop",
            "can't recommend", "cannot recommend", "can't thank");
    private static final java.util.Map<String, java.util.regex.Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Outcome compute(String groupId, List<KnowledgeItem> existing, List<KnowledgeItem> added) {
        return compute(groupId, existing, added, k -> "");
    }

    /** {@code quoteOf}: the live text of the later item's FULL primary message (change / restatement / hypothetical wording). */
    public static Outcome compute(String groupId, List<KnowledgeItem> existing, List<KnowledgeItem> added,
                                  java.util.function.Function<KnowledgeItem, String> quoteOf) {
        return compute(groupId, existing, added, quoteOf, q -> q.kind().name());
    }

    /** {@code qualifierText}: the live text of a qualifier span; CONTRADICTS needs EQUAL qualifier texts (RFC-002 §3.5). */
    public static Outcome compute(String groupId, List<KnowledgeItem> existing, List<KnowledgeItem> added,
                                  java.util.function.Function<KnowledgeItem, String> quoteOf,
                                  java.util.function.Function<Assertion.Qualifier, String> qualifierText) {
        return compute(groupId, existing, added, quoteOf, qualifierText, quoteOf);
    }

    /**
     * {@code contextOf}: the later item's full message text PLUS its live reply parent (referent check only; change
     * wording is never read from the parent). DEC-034 rules, all on the later item's own full message:
     * <ul>
     *   <li>vetoes (never supersede): different speaker; hypothetical (hedged, CONDITION qualifier, hedge wording);
     *       restatement wording; additive/continuity wording ("also", "too", "still", "no update"); the later message
     *       does not mention the earlier item's subject (in itself or its reply parent);</li>
     *   <li>change: same predicate, or opposite polarity, with explicit change wording;</li>
     *   <li>retraction: an earlier OFFER/commitment withdrawn with retraction wording ("can't … after all");</li>
     *   <li>stronger status: OFFER → CONFIRMED_COMMITMENT; anything → DECISION.</li>
     * </ul>
     * Uncertain → no relation: both claims stay ACTIVE (a false supersession hides valid evidence).
     */
    public static Outcome compute(String groupId, List<KnowledgeItem> existing, List<KnowledgeItem> added,
                                  java.util.function.Function<KnowledgeItem, String> quoteOf,
                                  java.util.function.Function<Assertion.Qualifier, String> qualifierText,
                                  java.util.function.Function<KnowledgeItem, String> contextOf) {
        List<KnowledgeItem> all = new ArrayList<>(existing);
        Set<String> addedIds = new HashSet<>();
        for (KnowledgeItem k : added) { all.add(k); addedIds.add(k.id()); }
        all.removeIf(k -> k.assertion() == null || k.assertion().status() == Assertion.Status.QUESTION);
        all.sort(Comparator.comparing((KnowledgeItem k) -> k.assertion().statedAt()).thenComparing(KnowledgeItem::id));
        List<KnowledgeRelation> rel = new ArrayList<>();
        Set<String> superseded = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            KnowledgeItem later = all.get(i);
            for (int j = 0; j < i; j++) {
                KnowledgeItem earlier = all.get(j);
                if (!addedIds.contains(later.id()) && !addedIds.contains(earlier.id())) continue;
                Assertion l = later.assertion(), e = earlier.assertion();
                if (!l.subjectId().equals(e.subjectId()) || !l.statedAt().isAfter(e.statedAt())) continue;
                String evidence = later.asserting().isEmpty() ? null : later.asserting().get(0).messageId();
                if (l.speakerId().equals(e.speakerId())) {
                    // N-98: an item whose sources are all deleted (or redacted) never retires anything
                    if (later.onlyDeletedSourcesRemain() || later.statementRedacted()) continue;
                    if (supersedes(later, earlier, quoteOf.apply(later), contextOf.apply(later), qualifierText)) {
                        rel.add(new KnowledgeRelation(groupId, later.id(), earlier.id(), KnowledgeRelation.Kind.SUPERSEDES, evidence));
                        superseded.add(earlier.id());
                    }
                } else if (later.polarity() != 0 && earlier.polarity() == -later.polarity()
                        && qualifierSpans(l, qualifierText).equals(qualifierSpans(e, qualifierText))
                        && l.certainty() != Assertion.Certainty.HEDGED && e.certainty() != Assertion.Certainty.HEDGED) {
                    rel.add(new KnowledgeRelation(groupId, later.id(), earlier.id(), KnowledgeRelation.Kind.CONTRADICTS, evidence));
                }
            }
        }
        return new Outcome(rel, superseded);
    }

    /** Same speaker and subject already established; {@code text} = later full message, {@code context} = + reply parent. */
    static boolean supersedes(KnowledgeItem later, KnowledgeItem earlier, String text, String context,
                              java.util.function.Function<Assertion.Qualifier, String> qualifierText) {
        Assertion l = later.assertion(), e = earlier.assertion();
        // DEC-035 (Tony R6, N-96): vetoes are read from the CLAUSE that holds the later quote ("Also, change of plan: …"
        // and "Decision: Haifa, it's still the cheapest" are not blocked by a word in another clause)
        String clause = quotedClause(later, text);
        boolean hypothetical = l.certainty() == Assertion.Certainty.HEDGED
                || l.qualifiers().stream().anyMatch(q -> q.kind() == Assertion.Qualifier.Kind.CONDITION)
                || containsAny(clause, HYPOTHETICAL_MARKERS);
        if (hypothetical) return false;
        // Tony R3: different POPULATION / CONTEXT scope ("for beginners" vs "for experienced hikers") never replaces
        // (only when BOTH carry a scope qualifier: a qualifier on one side alone is part of the new value, e.g. a decided place)
        Set<String> ls = scopeQualifiers(l, qualifierText), es = scopeQualifiers(e, qualifierText);
        if (!ls.isEmpty() && !es.isEmpty() && !ls.equals(es)) return false;
        // TASK-046 (Codex P4): an earlier CONDITIONAL claim (a contingency) is complementary; only explicit change wording retires it
        boolean earlierConditional = e.status() != Assertion.Status.OFFER && e.status() != Assertion.Status.CONFIRMED_COMMITMENT
                && (e.qualifiers().stream().anyMatch(q -> q.kind() == Assertion.Qualifier.Kind.CONDITION)
                || containsAny(earlier.statementRedacted() ? "" : earlier.statement(), CONDITION_WORDS));
        // B46-5: …and the change must touch the CONDITION ("Update: the picnic is on Sunday" leaves "If it rains, …" alone)
        if (earlierConditional && !(hasChangeWording(clause) && touchesCondition(earlier, clause, qualifierText))) return false;
        // B46-2: a restatement never replaces anything, also on the strong path
        if (containsAny(clause, RESTATEMENT_MARKERS)) return false;
        // an explicit, validated DECISION / confirmation by the same speaker on the same subject id is a strong signal, but
        // TASK-046 (Codex P4): only for the SAME property, i.e. when the earlier item was itself an option on that subject
        // (an offer, a commitment, a recommendation, a tentative or proposed plan) or the later clause says it changes it.
        // Subject equality alone never retires a plain fact ("The hall has a kitchen" survives "Decision: we book the hall").
        // Codex review C1: change wording or an earlier offer NEVER waives the same-property evidence ("Update: Decision: the
        // picnic starts at noon" does not retire "The picnic budget is 50 dollars"; "Decision: the picnic is Friday" does not
        // retire "I can bring chairs to the picnic"). The calendar path carries its own property evidence (rest covered).
        boolean property = sameProperty(later, earlier, clause);
        if (stronger(l.status(), e.status()))
            return (property && (isOption(earlier) || hasChangeWording(clause))) || replacesCalendarValue(later, earlier, text, qualifierText);
        if (containsAny(clause, RESTATEMENT_MARKERS) || containsAny(clause, CONTINUITY_MARKERS)) return false;
        // weak signals (change / retraction wording) also need the later message to name the earlier referent
        // (B-S13: "Update: my car is in the shop" must not retire "The rental car is booked")
        if (!mentionsSubject(context, e.subject())) return false;
        boolean change = hasChangeWording(text)
                && (l.predicate().equals(e.predicate()) || (later.polarity() != 0 && earlier.polarity() == -later.polarity()));
        // N-95: a retraction is the speaker's OWN first-hand statement, with retraction wording in the quoted clause, and
        // the speaker's own message (not another member's reply parent) names the referent; "can't wait" is not one
        boolean retraction = (e.status() == Assertion.Status.OFFER || e.status() == Assertion.Status.CONFIRMED_COMMITMENT)
                && l.status() != Assertion.Status.REPORTED_CLAIM && l.certainty() != Assertion.Certainty.HEARSAY
                && containsAny(clause, RETRACTION_MARKERS) && !containsAny(clause, NON_RETRACTION_IDIOMS)
                && mentionsSubject(text, e.subject());
        // property evidence on the weak paths: a shared content word / value slot, OR the act itself is about the property:
        // a reversed stance on the same subject ("I recommend the ferry" → "Actually, avoid the ferry"), or the speaker's
        // own retraction of their offer/commitment (retraction wording + the referent named in their own message)
        boolean reversal = later.polarity() != 0 && earlier.polarity() == -later.polarity();
        return (change && (property || reversal || replacesCalendarValue(later, earlier, text, qualifierText))) || retraction;
    }

    /** The clause(s) of {@code text} containing the item's first evidence span (bounded by ; . ! ? or a line break). */
    static String quotedClause(KnowledgeItem k, String text) {
        if (text == null || text.isEmpty() || k.asserting().isEmpty()) return text == null ? "" : text;
        KnowledgeItem.Evidence ev = k.asserting().get(0);
        if (!ev.hasSpan() || ev.spanEnd() > text.length()) return text;
        int a = ev.spanStart(), b = ev.spanEnd();
        while (a > 0 && ";.!?\n".indexOf(text.charAt(a - 1)) < 0) a--;
        while (b < text.length() && ";.!?\n".indexOf(text.charAt(b)) < 0) b++;
        return text.substring(a, b);
    }

    private static Set<String> scopeQualifiers(Assertion a, java.util.function.Function<Assertion.Qualifier, String> text) {
        Set<String> s = new HashSet<>();
        a.qualifiers().stream().filter(q -> q.kind() == Assertion.Qualifier.Kind.POPULATION || q.kind() == Assertion.Qualifier.Kind.CONTEXT)
                .forEach(q -> s.add(Spans.norm(text.apply(q)).toLowerCase(java.util.Locale.ROOT)));
        return s;
    }

    /** Every content token of the earlier subject occurs in the later message or its reply parent (inflection-tolerant). */
    static boolean mentionsSubject(String context, String earlierSubject) {
        if (context == null || context.isBlank()) return true;                          // no text available: legacy behaviour
        Set<String> need = new HashSet<>(app.dita.core.text.Tokenizer.tokens(earlierSubject));
        need.removeIf(t -> t.length() < 2);
        if (need.isEmpty()) return true;
        Set<String> have = app.dita.core.text.Tokenizer.normalizedSet(context);
        for (String t : need) {
            if (have.contains(t) || have.contains(app.dita.core.text.Tokenizer.base(t))) continue;
            return false;
        }
        return true;
    }

    static boolean hasChangeWording(String text) { return containsAny(text, CHANGE_MARKERS); }

    /** Latin markers match on word boundaries ("finally" is not "final"); Hebrew markers as substrings (prefix letters). */
    static boolean containsAny(String text, List<String> markers) {
        if (text == null || text.isEmpty()) return false;
        String q = text.toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
        for (String m : markers) {
            String k = m.strip();
            if (k.isEmpty()) continue;
            if (app.dita.core.text.Tokenizer.isHebrew(k)) { if (hebrewMatch(q, k)) return true; continue; }
            if (PATTERNS.computeIfAbsent(k, x -> java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(x)
                    + "(?![\\p{L}\\p{N}])")).matcher(q).find()) return true;
        }
        return false;
    }

    /**
     * Tony R6: Hebrew markers match whole words, optionally after ONE prefix letter (ו ה ב ל מ ש כ), so "אם" is not
     * matched inside "תואם", and the question particle "האם" is never "if". Multi-word markers match word sequences.
     */
    static boolean hebrewMatch(String text, String marker) {
        List<String> words = app.dita.core.text.Tokenizer.rawWords(text), m = app.dita.core.text.Tokenizer.rawWords(marker);
        if (m.isEmpty()) return false;
        for (int i = 0; i + m.size() <= words.size(); i++) {
            String w = words.get(i);
            boolean first = w.equals(m.get(0)) || (w.length() == m.get(0).length() + 1 && "והבלמשכ".indexOf(w.charAt(0)) >= 0
                    && w.substring(1).equals(m.get(0)) && !w.equals("האם"));
            if (!first) continue;
            boolean rest = true;
            for (int j = 1; j < m.size() && rest; j++) rest = words.get(i + j).equals(m.get(j));
            if (rest) return true;
        }
        return false;
    }

    /**
     * TASK-046: FIRST-PERSON or imperative proposal forms that make a statement an OPTION for a later decision ("Let's do
     * the picnic on Friday"). B46-1: bare nouns/verbs ("option", "idea", "suggest", "planning", Hebrew present-tense
     * "מציע" = "offers") are NOT proposal markers: "The hall has a vegan option" is a fact.
     */
    static final List<String> PROPOSAL_MARKERS = List.of("let's", "let us", "we should", "we could", "how about", "what about",
            "i suggest", "i'd suggest", "i would suggest", "i propose", "my suggestion", "my proposal", "maybe we", "i think we should",
            "shall we", "why don't we", "why not", "בואו", "אני מציע", "אני מציעה", "ההצעה שלי", "נלך על", "אולי נ");
    /** TASK-046: condition wording inside an earlier claim (a contingency, not the main plan). */
    static final List<String> CONDITION_WORDS = List.of("if ", "in case", "unless", "otherwise", "אם ", "במקרה");

    /** The earlier item was an option for the subject (so a later decision/commitment can replace it). */
    static boolean isOption(KnowledgeItem earlier) {
        Assertion e = earlier.assertion();
        if (e.status() == Assertion.Status.OFFER || e.status() == Assertion.Status.CONFIRMED_COMMITMENT) return true;
        if (e.certainty() == Assertion.Certainty.HEDGED || earlier.polarity() > 0) return true;   // tentative, or recommended
        String quote = earlier.statementRedacted() ? "" : earlier.statement().strip();
        return containsAny(quote, PROPOSAL_MARKERS) || quote.endsWith("?");                    // B46-1: a question form only
    }

    /**
     * B46-2 / Codex C1: the later claim is about the same PROPERTY as the earlier one, on EVERY supersession path (offers
     * included: "I can bring the blankets" → "Confirmed: I'll bring the blankets" shares "bring"). The earlier claim must
     * share a content word, beyond the subject's own words, with the later clause ("Let's do the picnic on Friday at the lake" ↔ "Decision: the picnic is on Saturday
     * at the lake"; but "Let's bring snacks to the party" ≠ "Decision: the party is Friday").
     */
    static boolean sameProperty(KnowledgeItem later, KnowledgeItem earlier, String laterClause) {
        Set<String> subject = new HashSet<>(app.dita.core.text.Tokenizer.normalizedSet(earlier.assertion().subject()));
        subject.addAll(app.dita.core.text.Tokenizer.normalizedSet(later.assertion().subject()));
        Set<String> a = new HashSet<>(app.dita.core.text.Tokenizer.normalizedSet(earlier.statementRedacted() ? "" : earlier.statement()));
        a.removeAll(subject); a.removeAll(PROPERTY_NOISE);
        Set<String> b = app.dita.core.text.Tokenizer.normalizedSet(laterClause);
        a.retainAll(b);
        if (!a.isEmpty()) return true;
        // the same value SLOT: both put a value after the same place/time preposition ("go to Eilat" ↔ "the trip is to Haifa",
        // "at the park" ↔ "at the boathouse"). English only: Hebrew prepositions are prefix letters and too ambiguous (a
        // missed supersession is the safe direction).
        Set<String> fa = slotFrames(earlier.statementRedacted() ? "" : earlier.statement()), fb = slotFrames(laterClause);
        fa.retainAll(fb);
        return !fa.isEmpty();
    }

    static final Set<String> SLOT_PREPOSITIONS = Set.of("to", "at", "in", "on", "from", "near", "until", "before", "after");

    private static Set<String> slotFrames(String text) {
        Set<String> out = new HashSet<>();
        for (String w : app.dita.core.text.Tokenizer.rawWords(text == null ? "" : text.toLowerCase(java.util.Locale.ROOT)))
            if (SLOT_PREPOSITIONS.contains(w)) out.add(w);
        return out;
    }

    /** Proposal / decision scaffolding that never identifies a property. */
    static final Set<String> PROPERTY_NOISE = Set.of("let", "let's", "lets", "should", "could", "how", "suggest", "propose", "decision",
            "decided", "decide", "maybe", "think", "will", "go", "do", "we'll", "shall", "why", "not", "don't", "בואו", "החלטה", "החלטנו");

    /** B46-5: the later clause names a content word of the earlier claim's condition (qualifier span, or the "if …" clause). */
    static boolean touchesCondition(KnowledgeItem earlier, String laterClause, java.util.function.Function<Assertion.Qualifier, String> qualifierText) {
        StringBuilder cond = new StringBuilder();
        earlier.assertion().qualifiers().stream().filter(q -> q.kind() == Assertion.Qualifier.Kind.CONDITION).forEach(q -> cond.append(qualifierText.apply(q)).append(' '));
        String st = earlier.statementRedacted() ? "" : earlier.statement();
        var m = java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}])(if|unless|in case|אם|במקרה)\\s+([^,.;!?]*)").matcher(st);
        while (m.find()) cond.append(m.group(2)).append(' ');
        Set<String> c = new HashSet<>(app.dita.core.text.Tokenizer.normalizedSet(cond.toString()));
        c.removeAll(app.dita.core.text.Tokenizer.normalizedSet(earlier.assertion().subject()));
        if (c.isEmpty()) return false;
        Set<String> l = app.dita.core.text.Tokenizer.normalizedSet(laterClause);
        return c.stream().anyMatch(l::contains);
    }

    /**
     * TASK-046: evidence that the later item replaces the SAME temporal property of the earlier one: both state a calendar
     * value of the SAME TYPE (date, weekday, clock time, relative day, month) and the later values of that type do not
     * include the earlier ones ("…party for November 14" → "Decision: the party is Sunday, November 15"). Bare numbers and
     * ordinals ("parking for 20 cars", Hebrew "ראשון" = "first") are not calendar values (B46-3). English and Hebrew forms
     * (B46-4): "Nov 15", "15th", "14/11", "14 בנובמבר", "ביום שני", "בשבת". Known limit: two DIFFERENT temporal properties
     * of one subject with the same value type ("setup at 3pm" vs "party at 7pm") cannot be told apart lexically.
     */
    static boolean replacesCalendarValue(KnowledgeItem later, KnowledgeItem earlier, String laterText,
                                         java.util.function.Function<Assertion.Qualifier, String> qualifierText) {
        java.util.Map<String, Set<String>> e = calendarValues(earlier, earlier.statementRedacted() ? "" : earlier.statement(), qualifierText);
        java.util.Map<String, Set<String>> l = calendarValues(later, quotedClause(later, laterText), qualifierText);
        boolean differs = false;
        for (var t : e.entrySet()) {
            if (t.getKey().equals("month") && (e.containsKey("date") || l.containsKey("date"))) continue;   // month is part of a date
            Set<String> lv = l.get(t.getKey());
            if (lv != null && !lv.containsAll(t.getValue())) differs = true;
        }
        if (!differs) return false;
        // B46-3: what the earlier claim says BESIDES its subject, its date and planning verbs must be in the decision clause
        // ("The hall is closed on Monday" is about closing, not about when the party is)
        Set<String> rest = new HashSet<>(app.dita.core.text.Tokenizer.normalizedSet(earlier.statementRedacted() ? "" : earlier.statement()));
        rest.removeAll(app.dita.core.text.Tokenizer.normalizedSet(earlier.assertion().subject()));
        rest.removeAll(app.dita.core.text.Tokenizer.normalizedSet(later.assertion().subject()));
        rest.removeAll(Noise.FORMS);
        rest.removeIf(w -> w.chars().anyMatch(Character::isDigit)
                || WEEKDAYS.contains(w) || MONTHS.contains(w) || MONTH_ABBREVIATIONS.contains(w) || HE_MONTHS.contains(w) || HE_WEEKDAYS.contains(w) || w.equals("am") || w.equals("pm"));
        rest.removeAll(app.dita.core.text.Tokenizer.normalizedSet(quotedClause(later, laterText)));
        return rest.isEmpty();
    }

    /** PROPERTY_NOISE + PLANNING_WORDS in every normalised form the tokenizer produces. */
    private static final class Noise {
        static final Set<String> FORMS = new HashSet<>();
        static {
            for (String w : PROPERTY_NOISE) FORMS.addAll(app.dita.core.text.Tokenizer.normalizedSet(w));
            for (String w : PLANNING_WORDS) FORMS.addAll(app.dita.core.text.Tokenizer.normalizedSet(w));
            FORMS.addAll(PROPERTY_NOISE); FORMS.addAll(PLANNING_WORDS);
        }
    }

    /** Planning verbs that carry no property of their own ("We need to organize the party for …"). */
    static final Set<String> PLANNING_WORDS = Set.of("need", "needs", "organize", "organise", "plan", "planned", "hold", "have", "schedule",
            "scheduled", "set", "arrange", "arranged", "happen", "happening", "take", "place", "be", "will", "it", "יהיה", "תהיה", "צריך",
            "צריכים", "לארגן", "נקבע", "מתוכנן");

    static final List<String> MONTHS = List.of("january", "february", "march", "april", "may", "june", "july", "august", "september",
            "october", "november", "december");
    static final List<String> HE_MONTHS = List.of("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר",
            "אוקטובר", "נובמבר", "דצמבר");
    static final Set<String> MONTH_ABBREVIATIONS = Set.of("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun");
    static final Set<String> WEEKDAYS = Set.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");
    /** Hebrew weekday names; ראשון/שני are also ordinals, so a weekday needs "יום"/"ביום" before it (or ב+name for the rest). */
    static final List<String> HE_WEEKDAYS = List.of("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת");
    private static final String NB = "(?<![\\p{L}\\p{N}])", NA = "(?![\\p{L}\\p{N}])";
    private static final java.util.regex.Pattern CLOCK = java.util.regex.Pattern.compile(NB + "(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)" + NA
            + "|" + NB + "(\\d{1,2}):(\\d{2})" + NA);
    private static final java.util.regex.Pattern EN_DATE = java.util.regex.Pattern.compile(NB
            + "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?" + NA
            + "|" + NB + "(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*" + NA
            + "|" + NB + "(\\d{1,2})[/.](\\d{1,2})" + NA
            + "|" + NB + "the\\s+(\\d{1,2})(?:st|nd|rd|th)" + NA);
    private static final java.util.regex.Pattern HE_DATE = java.util.regex.Pattern.compile(NB + "(\\d{1,2})\\s*[בל]?-?(" + String.join("|", HE_MONTHS) + ")" + NA);

    private static java.util.Map<String, Set<String>> calendarValues(KnowledgeItem k, String text, java.util.function.Function<Assertion.Qualifier, String> qualifierText) {
        StringBuilder all = new StringBuilder(text == null ? "" : text);
        for (Assertion.Qualifier q : k.assertion().qualifiers()) if (q.kind() == Assertion.Qualifier.Kind.TIME) all.append(" \n ").append(qualifierText.apply(q));
        String t = all.toString().toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
        java.util.Map<String, Set<String>> out = new java.util.TreeMap<>();
        java.util.function.BiConsumer<String, String> put = (type, v) -> out.computeIfAbsent(type, x -> new java.util.TreeSet<>()).add(v);
        var c = CLOCK.matcher(t);
        while (c.find()) {
            if (c.group(1) != null) {
                int h = Integer.parseInt(c.group(1)) % 12 + (c.group(3).equals("pm") ? 12 : 0);
                put.accept("clock", h + ":" + (c.group(2) == null ? "00" : c.group(2)));
            } else put.accept("clock", Integer.parseInt(c.group(4)) + ":" + c.group(5));
        }
        var d = EN_DATE.matcher(t);
        while (d.find()) {
            if (d.group(1) != null) put.accept("date", month(d.group(1)) + " " + Integer.parseInt(d.group(2)));
            else if (d.group(3) != null) put.accept("date", month(d.group(4)) + " " + Integer.parseInt(d.group(3)));
            else if (d.group(5) != null) put.accept("date", "d" + Integer.parseInt(d.group(5)) + "/" + Integer.parseInt(d.group(6)));
            else put.accept("date", "day " + Integer.parseInt(d.group(7)));
        }
        var h = HE_DATE.matcher(t);
        while (h.find()) put.accept("date", MONTHS.get(HE_MONTHS.indexOf(h.group(2))) + " " + Integer.parseInt(h.group(1)));
        List<String> words = app.dita.core.text.Tokenizer.rawWords(t);
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (WEEKDAYS.contains(w)) put.accept("weekday", w);
            else if (w.equals("tomorrow") || w.equals("tonight") || w.equals("today")) put.accept("relday", w);
            else if (MONTHS.contains(w) && !w.equals("may")) put.accept("month", w);           // "may" is also a modal verb
            else if (app.dita.core.text.Tokenizer.isHebrew(w)) {
                boolean afterDay = i > 0 && (words.get(i - 1).equals("יום") || words.get(i - 1).equals("ביום") || words.get(i - 1).equals("ויום"));
                if (HE_WEEKDAYS.contains(w) && afterDay) put.accept("weekday", "he:" + w);
                else if (w.length() > 2 && "בו".indexOf(w.charAt(0)) >= 0 && HE_WEEKDAYS.contains(w.substring(1))
                        && !w.substring(1).equals("ראשון") && !w.substring(1).equals("שני")) put.accept("weekday", "he:" + w.substring(1));
                else if (w.equals("שבת")) put.accept("weekday", "he:שבת");
                else {
                    String bare = w.length() > 2 && "בלו".indexOf(w.charAt(0)) >= 0 ? w.substring(1) : w;
                    if (HE_MONTHS.contains(bare)) put.accept("month", MONTHS.get(HE_MONTHS.indexOf(bare)));
                }
            }
        }
        return out;
    }

    private static String month(String abbrev) {
        for (String m : MONTHS) if (m.startsWith(abbrev.substring(0, 3))) return m;
        return abbrev;
    }

    static boolean stronger(Assertion.Status later, Assertion.Status earlier) {
        if (later == Assertion.Status.DECISION) return earlier != Assertion.Status.DECISION;
        return later == Assertion.Status.CONFIRMED_COMMITMENT && earlier == Assertion.Status.OFFER;
    }

    /** Qualifier sets compared by normalised live TEXT (DEC-031: "for babies" ≠ "for teenagers" is not a contradiction). */
    private static Set<String> qualifierSpans(Assertion a, java.util.function.Function<Assertion.Qualifier, String> text) {
        Set<String> s = new HashSet<>();
        a.qualifiers().forEach(q -> s.add(Spans.norm(text.apply(q)).toLowerCase(java.util.Locale.ROOT)));
        return s;
    }
}
