package app.dita.core.answer.synthesis;

import java.util.*;
import java.util.regex.*;

/** Repairs an unambiguous Telegram marker abbreviation only when citedIds already supplies its exact evidence id. */
final class CitationMarkers {
    private CitationMarkers() {}
    private static final Pattern FULL = Pattern.compile("^tgt[0-9]+(m[0-9]+)$");
    private static final Pattern MARKER = Pattern.compile("\\[(m[0-9]+)\\]");
    static AnswerSynthesizer.Draft canonicalize(AnswerSynthesizer.Request req, AnswerSynthesizer.Draft draft) {
        Map<String, List<String>> aliases = new HashMap<>();
        Set<String> supplied = new HashSet<>();
        for (var e : req.evidence()) {
            supplied.add(e.id());
            Matcher m = FULL.matcher(e.id());
            if (m.matches()) aliases.computeIfAbsent(m.group(1), x -> new ArrayList<>()).add(e.id());
        }
        Matcher m = MARKER.matcher(draft.answer());
        StringBuffer answer = new StringBuffer();
        while (m.find()) {
            var ids = aliases.getOrDefault(m.group(1), List.of());
            String replacement = m.group();
            if (!supplied.contains(m.group(1)) && ids.size() == 1 && draft.citedIds().contains(ids.get(0)))
                replacement = "[" + ids.get(0) + "]";
            m.appendReplacement(answer, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(answer);
        return new AnswerSynthesizer.Draft(draft.abstain(), answer.toString(), draft.citedIds());
    }
}
