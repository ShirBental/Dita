package app.dita.core.link;

import app.dita.core.config.LinkSettings;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.model.MessageLink.Kind;
import app.dita.core.model.MessageLink.Method;
import app.dita.core.model.MessageLink.Status;
import app.dita.core.text.Tokenizer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tier-0 deterministic link inference. Emits CANDIDATE links with a multidimensional evidence vector.
 * Never emits FACT for an inferred link. Grey-zone scores are the hand-off point for Muse adjudication.
 */
public final class RuleLinkInferencer implements LinkInferencer {
    private final LinkSettings settings;

    public RuleLinkInferencer() { this(LinkSettings.DEFAULTS); }
    public RuleLinkInferencer(LinkSettings settings) { this.settings = settings; }
    public LinkSettings settings() { return settings; }

    private static final List<String> ANAPHORA = List.of(" it ", " that ", " she ", " he ", " they ", " them ",
            " this one", " זה ", " זאת ", " היא ", " הוא ", " אותו ", " אותה ", " שם ");
    private static final List<String> CORRECTION = List.of("correction", "actually", "i meant", "sorry, i",
            "תיקון", "טעיתי", "בעצם", "התכוונתי");
    private static final List<String> RESUMPTION = List.of("back to", "going back", "re:", "regarding",
            "update on", "חוזרים ל", "לגבי", "בהמשך ל", "עדכון");

    @Override
    public List<MessageLink> infer(GroupScope scope, Message m, List<Message> window, List<MessageLink> known) {
        scope.requireSame(m.groupId());
        if (m.replyToId() != null) {
            return List.of(new MessageLink(m.groupId(), m.id(), m.replyToId(), Kind.EXPLICIT_REPLY, Status.FACT,
                    Method.PLATFORM, 1.0, Map.of("explicit_reply", 1.0)));
        }
        Map<String, Message> byId = new HashMap<>();
        window.forEach(w -> { scope.requireSame(w.groupId()); byId.put(w.id(), w); });

        String padded = " " + String.join(" ", Tokenizer.rawWords(m.text())) + " ";
        String lower = m.text().toLowerCase(Locale.ROOT);
        Set<String> toks = Tokenizer.tokenSet(m.text());
        Set<String> ents = Tokenizer.entities(m.text());
        boolean anaphoric = toks.size() <= 12 && ANAPHORA.stream().anyMatch(padded::contains);
        boolean correcting = CORRECTION.stream().anyMatch(lower::contains);
        boolean resuming = RESUMPTION.stream().anyMatch(lower::contains);

        List<MessageLink> scored = new ArrayList<>();
        for (Message c : window) {
            if (c.id().equals(m.id()) || c.deleted() || !c.sentAt().isBefore(m.sentAt())) continue;
            double minutes = Duration.between(c.sentAt(), m.sentAt()).toSeconds() / 60.0;
            double temporal = Math.pow(0.5, minutes / settings.temporalHalfLifeMinutes());
            Set<String> ct = Tokenizer.tokenSet(c.text());
            double lexical = overlap(toks, ct);
            double entity = Tokenizer.entities(c.text()).stream().anyMatch(ents::contains) ? 1.0 : 0.0;
            // answered_author depends on a third message (the one c answered); record it for invalidation.
            String answeredVia = c.authorId().equals(m.authorId()) ? null : known.stream()
                    .filter(l -> l.fromId().equals(c.id()) && byId.containsKey(l.toId())
                            && byId.get(l.toId()).authorId().equals(m.authorId()))
                    .map(MessageLink::toId).findFirst().orElse(null);
            double answeredAuthor = answeredVia != null ? 1.0 : 0.0;
            double qa = c.text().strip().endsWith("?") && !c.authorId().equals(m.authorId()) ? 1.0 : 0.0;
            double anaphora = anaphoric ? temporal : 0.0;
            double correction = correcting && c.authorId().equals(m.authorId()) && lexical > 0 ? 1.0 : 0.0;
            double resumption = resuming && (lexical > 0 || entity > 0) ? 1.0 : 0.0;

            double score = 0.20 * temporal + 0.25 * lexical + 0.20 * entity + 0.20 * answeredAuthor
                    + 0.10 * qa + 0.10 * anaphora + 0.30 * correction + 0.15 * resumption;
            score = Math.min(1.0, score);
            if (score < settings.candidateThreshold()) continue;

            Map<String, Double> ev = new TreeMap<>();
            ev.put("temporal", round(temporal)); ev.put("lexical", round(lexical)); ev.put("entity", entity);
            ev.put("answered_author", answeredAuthor); ev.put("qa_pattern", qa); ev.put("anaphora", round(anaphora));
            ev.put("correction", correction); ev.put("resumption", resumption);
            boolean adjudicate = score >= settings.adjudicationLow() && score <= settings.adjudicationHigh();
            ev.put("needs_adjudication", adjudicate ? 1.0 : 0.0);
            Kind kind = correction > 0 ? Kind.INFERRED_CORRECTION
                    : resumption > 0 ? Kind.INFERRED_CONTINUATION : Kind.INFERRED_REFERENCE;
            scored.add(new MessageLink(m.groupId(), m.id(), c.id(), kind, Status.CANDIDATE, Method.RULE,
                    round(score), ev, answeredVia == null ? Set.of() : Set.of(answeredVia)));
        }
        scored.sort(Comparator.comparingDouble(MessageLink::score).reversed()
                .thenComparing(l -> byId.get(l.toId()).sentAt(), Comparator.reverseOrder()));
        if (scored.isEmpty()) return List.of();
        List<MessageLink> out = new ArrayList<>();
        out.add(scored.get(0));
        // Keep a close runner-up rather than silently choosing: ambiguity is surfaced, not hidden.
        if (scored.size() > 1 && scored.get(0).score() - scored.get(1).score() <= settings.ambiguityMargin()) {
            out.add(scored.get(1));
        }
        return out;
    }

    /** Overlap coefficient |A∩B| / min(|A|,|B|). */
    static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        long inter = a.stream().filter(b::contains).count();
        return (double) inter / Math.min(a.size(), b.size());
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
