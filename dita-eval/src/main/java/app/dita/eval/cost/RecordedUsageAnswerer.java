package app.dita.eval.cost;

import app.dita.eval.Json;
import app.dita.eval.answer.ModelAnswerer;
import app.dita.eval.answer.ModelRequest;
import app.dita.eval.guard.BudgetGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replays recorded USAGE (numbers only) for a request, keyed by the SHA-256 of the exact request body. It returns no
 * model text: {@code content} is always empty, so its answers are never scorable and {@link #isMock()} is true. It
 * fails closed: a request whose body hash has no recording ({@link MissingRecordingException}) or whose recording
 * no longer matches the request (arm, model, effort, cap or byte length: {@link StaleRecordingException}) aborts.
 * Every replayed record keeps its own provenance; synthetic test records are SIMULATED and must never be presented as
 * measured. If a {@link CostLedger} is supplied, each replay is reserved at worst case and then charged its actual.
 */
public final class RecordedUsageAnswerer implements ModelAnswerer {
    public static final class MissingRecordingException extends IllegalStateException {
        MissingRecordingException(String m) { super(m); }
    }
    public static final class StaleRecordingException extends IllegalStateException {
        StaleRecordingException(String m) { super(m); }
    }

    private final Map<String, UsageRecord> byHash = new HashMap<>();
    private final CostLedger ledger;
    private UsageRecord last;

    public RecordedUsageAnswerer(List<UsageRecord> records, CostLedger ledger) {
        this.ledger = ledger;
        for (UsageRecord r : records) {
            if (r.callKind() != UsageRecord.CallKind.ANSWER) continue;
            if (r.requestSha256() == null) throw new IllegalArgumentException("answer recording without request_sha256 cannot be replayed");
            if (byHash.put(r.requestSha256(), r) != null) throw new IllegalArgumentException("duplicate request_sha256 in recordings");
        }
    }

    public static String bodySha256(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public String name() { return "recorded-usage-replay"; }

    /** True: replays usage only, never model text; its outputs are not results. */
    @Override public boolean isMock() { return true; }

    /** The usage record replayed by the most recent call (carries its provenance), or null. */
    public synchronized UsageRecord lastUsage() { return last; }

    @Override
    public synchronized Reply answer(ModelRequest request) {
        String h = bodySha256(request.body());
        UsageRecord r = byHash.get(h);
        if (r == null) throw new MissingRecordingException("no recorded usage for request hash (missing or stale recording; qid="
                + request.qid() + ", arm=" + request.arm() + ")");
        checkFresh(r, request);
        if (ledger != null) {
            String callId = ("replay-" + request.qid() + "-" + request.arm().label).replaceAll("[^A-Za-z0-9_.:-]", "_");
            ledger.reserve(callId, UsageRecord.CallKind.ANSWER, r.arm(), BudgetGuard.inputTokenUpperBound(request.body()),
                    request.maxCompletionTokens());
            ledger.recordActual(callId, r);
        }
        last = r;
        return new Reply("", r.finishReason(), Math.toIntExact(r.promptTokens()), Math.toIntExact(r.completionTokens()));
    }

    private static void checkFresh(UsageRecord r, ModelRequest req) {
        String where = " (qid=" + req.qid() + ", arm=" + req.arm() + ")";
        if (r.arm() != null && !r.arm().equals(req.arm().label)) throw new StaleRecordingException("recording arm differs" + where);
        if (r.maxCompletionTokens() != req.maxCompletionTokens()) throw new StaleRecordingException("recording max_completion_tokens differs" + where);
        JsonNode body = Json.parse(req.body());
        if (!Objects.equals(r.model(), Json.textOrNull(body, "model"))) throw new StaleRecordingException("recording model differs" + where);
        if (!Objects.equals(r.reasoningEffort(), Json.textOrNull(body, "reasoning_effort"))) throw new StaleRecordingException("recording reasoning_effort differs" + where);
        if (r.requestBytes() != null && r.requestBytes() != req.body().getBytes(StandardCharsets.UTF_8).length)
            throw new StaleRecordingException("recording request_bytes differs" + where);
    }
}
