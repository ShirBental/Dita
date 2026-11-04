package app.dita.eval.guard;

import app.dita.eval.answer.ModelRequest;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * N-50: inspects every outgoing model request body (the mock's included) before it is handed to an answerer and aborts
 * the run on any sign of sealed-label leakage:
 * <ol>
 *   <li>any canary-shaped token ({@code DITA-CANARY-...}) anywhere in the body;</li>
 *   <li>any gold-label field name (expected_behavior, answer_keys, ...) anywhere in the body;</li>
 *   <li>any hashed canary from the sealed gold, as a word n-gram of the body;</li>
 *   <li>any hashed gold answer string inside the arm-independent prompt text (system prompt + template scaffolding).
 *       Answers legitimately occur in the question and in the message context, so those parts are not checked
 *       against answer strings.</li>
 * </ol>
 * Exception messages name the check and qid, never the leaked text. Application control, not a security boundary.
 */
public final class CanaryGuard {
    public static final Pattern CANARY_SHAPE = Pattern.compile("DITA-CANARY-[0-9A-F]{16}", Pattern.CASE_INSENSITIVE);
    public static final List<String> LABEL_MARKERS = List.of("expected_behavior", "gold_evidence", "answer_keys",
            "forbidden_strings", "must_not_claims", "expected_claims", "acceptable_extra_ids", "superseded_ids",
            "forbidden_ids", "injection_ids", "deletion_track", "_canary", "gold_link_pairs");

    private final Sentinels sentinels;
    private int checked;

    public CanaryGuard(Sentinels sentinels) { this.sentinels = sentinels; }

    public int requestsChecked() { return checked; }

    public void check(ModelRequest req) {
        String body = req.body();
        if (CANARY_SHAPE.matcher(body).find()) throw trip("canary-shaped token in request body", req);
        for (String m : LABEL_MARKERS) if (body.contains(m)) throw trip("gold label field name in request body", req);
        if (!sentinels.canaryHashes().isEmpty()) {
            Set<String> grams = Sentinels.ngramHashes(body);
            for (String h : sentinels.canaryHashes()) if (grams.contains(h)) throw trip("sealed canary in request body", req);
        }
        if (!sentinels.answerHashes().isEmpty()) {
            Set<String> grams = Sentinels.ngramHashes(req.fixedText());
            for (String h : sentinels.answerHashes()) if (grams.contains(h)) throw trip("gold answer string in fixed prompt text", req);
        }
        checked++;
    }

    private static LeakageException trip(String what, ModelRequest req) {
        return new LeakageException("CanaryGuard aborted the run: " + what + " (qid=" + req.qid() + ", arm=" + req.arm() + ")");
    }
}
