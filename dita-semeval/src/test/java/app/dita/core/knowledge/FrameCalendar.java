package app.dita.core.knowledge;

import app.dita.core.model.Assertion;
import app.dita.core.model.KnowledgeItem;

import java.util.List;
import java.util.Set;

/** EXPERIMENT ONLY (arm A2): a verbatim copy of RelationRules' calendar helpers, so the frame baseline compiles without
 *  touching production code. */
final class FrameCalendar {
    private FrameCalendar() {}

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

    static java.util.Map<String, Set<String>> calendarValues(KnowledgeItem k, String text, java.util.function.Function<Assertion.Qualifier, String> qualifierText) {
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


    static boolean calendarWord(String w) {
        return WEEKDAYS.contains(w) || MONTHS.contains(w) || MONTH_ABBREVIATIONS.contains(w) || HE_MONTHS.contains(w) || HE_WEEKDAYS.contains(w)
                || w.equals("noon") || w.equals("midnight") || w.equals("tomorrow") || w.equals("tonight") || w.equals("today");
    }
}
