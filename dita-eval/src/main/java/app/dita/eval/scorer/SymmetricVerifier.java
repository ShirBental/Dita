package app.dita.eval.scorer;

import app.dita.eval.answer.OutputParser.Claim;
import app.dita.eval.answer.OutputParser.Output;
import app.dita.eval.corpus.Corpus;
import app.dita.eval.corpus.CorpusMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The portable verifier applied identically to EVERY arm at scoring time (DESIGN §1, Tony risk 1). It mirrors
 * {@code app.dita.core.answer.AnswerVerifier} (the message exists in the question's group, is not deleted, contains a
 * verbatim quote, and the speaker is its author) and adds the as_of check (sent at or before as_of, not deleted at
 * as_of). It checks provenance, not truth.
 */
public final class SymmetricVerifier {
    private SymmetricVerifier() {}

    /** A cited message id is verified iff it passes every provenance check for this claim. */
    public static boolean citationVerified(Claim c, String messageId, Corpus corpus, Instant asOf) {
        Optional<CorpusMessage> m = corpus.find(messageId);
        if (m.isEmpty() || !m.get().visibleAt(asOf)) return false;
        if (c.speaker() == null || !c.speaker().strip().toLowerCase(Locale.ROOT).equals(m.get().author().toLowerCase(Locale.ROOT))) return false;
        return c.quotes().stream().anyMatch(q -> q != null && !q.isBlank() && m.get().text().contains(q));
    }

    public static List<String> verifiedIds(Claim c, Corpus corpus, Instant asOf) {
        return c.messageIds().stream().filter(id -> citationVerified(c, id, corpus, asOf)).toList();
    }

    /**
     * "Gated" row: the verifier used as a filter. Unverified citations are removed and claims left with none are dropped.
     * If an ANSWER/CONFLICT keeps no claim it becomes UNKNOWN, like AnswerVerifier's notFound. The answer prose is kept
     * when at least one claim survives (prose cannot be re-rendered deterministically; this is a known limitation).
     */
    public static Output gate(Output o, Corpus corpus, Instant asOf) {
        List<Claim> kept = new ArrayList<>();
        for (Claim c : o.claims()) {
            List<String> ids = verifiedIds(c, corpus, asOf);
            if (!ids.isEmpty()) kept.add(new Claim(c.speaker(), c.attributedTo(), ids, c.quotes(), c.stance(), c.qualifier(), c.isCurrent(), c.commitment()));
        }
        boolean answering = o.behavior().equals("ANSWER") || o.behavior().equals("CONFLICT");
        if (answering && kept.isEmpty()) return new Output("UNKNOWN", "", java.util.Map.of(), List.of(), null);
        return new Output(o.behavior(), o.answer(), o.slots(), kept, o.clarifyQuestion());
    }
}
