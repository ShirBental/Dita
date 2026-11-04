package app.dita.app.trial;

import app.dita.muse.MuseException;
import app.dita.muse.MuseTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TASK-054: every Muse call of the controlled Telegram trial passes here, BELOW the production MuseClient (so the request
 * bytes are exactly what would be sent). A call is sent only if
 * <ol>
 *   <li>it happens inside a {@link TrialContext} (one Telegram /extract batch or one /ask), of that context's kind,</li>
 *   <li>it is the context's first call (no retry, no second synthesis attempt),</li>
 *   <li>the run's approved-call budget is not exhausted, and</li>
 *   <li>the operator approves THIS request (its SHA-256 prefix) on the terminal.</li>
 * </ol>
 * Otherwise nothing leaves the machine and the caller gets a MuseException (extraction: batch FAILED; synthesis:
 * deterministic fallback). Evidence per call: request.json, pending.json, approval.json, attempted, http.json, response.json.
 * Headers (the Authorization credential) are never written.
 */
public final class ApprovalGatedTransport implements MuseTransport {
    static final ObjectMapper JSON = new ObjectMapper();
    /** First words of every MuseAnswerSynthesizer prompt version. */
    static final String SYNTHESIS_MARKER = "You write the reply of Dita";
    /** Standard-mode Muse Spark prices, user-supplied 2026-10-28 (historical, not verified current, not a cap). */
    public static final double IN_PER_MTOK = 1.25, OUT_PER_MTOK = 4.25;
    public static final String PRICING = "user-supplied 2026-10-28 standard-mode Muse Spark prices: $1.25/M input, $4.25/M output (reasoning billed as output); historical, not verified current, not a billing cap";

    public record CallSettings(String destination, String model, int maxCompletionTokens, String reasoningEffort, String timeout) {}

    private final MuseTransport inner;
    private final CallApprover approver;
    private final TrialEvidence evidence;
    private final CallSettings settings;
    private final int maxApproved;
    private final AtomicInteger approved = new AtomicInteger(), sent = new AtomicInteger();

    public ApprovalGatedTransport(MuseTransport inner, CallApprover approver, TrialEvidence evidence, CallSettings settings, int maxApproved) {
        this.inner = inner; this.approver = approver; this.evidence = evidence; this.settings = settings; this.maxApproved = maxApproved;
    }

    public int approvedCalls() { return approved.get(); }
    public int sentCalls() { return sent.get(); }

    @Override public Reply post(URI url, Map<String, String> headers, String body, Duration timeout) throws Exception {
        TrialContext ctx = TrialContext.current();
        TrialContext.Kind kind = body.contains(SYNTHESIS_MARKER) ? TrialContext.Kind.SYNTHESIS : TrialContext.Kind.EXTRACTION;
        if (ctx == null) throw refused("no Telegram trial request is in progress");
        if (ctx.kind != kind) throw refused("a " + kind + " call inside a " + ctx.kind + " request");
        if (ctx.callsAttempted++ > 0) throw refused("only one Muse call per Telegram request (no retry)");
        if (approved.get() >= maxApproved) throw refused("the run's approved-call budget (" + maxApproved + ") is used up");

        int n = evidence.nextSeq();
        String callId = String.format("call-%03d-%s", n, kind.name().toLowerCase());
        ctx.callIds.add(callId);
        Path dir = evidence.dir(callId);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        Path req = evidence.write(dir.resolve("request.json"), body);
        int estIn = (int) Math.ceil(bytes.length / 3.0);
        double maxCost = round(estIn * IN_PER_MTOK / 1e6 + settings.maxCompletionTokens() * OUT_PER_MTOK / 1e6);
        var pending = new CallApprover.PendingCall(callId, kind, ctx.correlation, evidence.display(req), sha, bytes.length,
                url.toString(), settings.model(), settings.maxCompletionTokens(), settings.reasoningEffort(), settings.timeout(),
                estIn, maxCost, PRICING, summary(kind, body));
        evidence.writeJson(dir.resolve("pending.json"), pending);

        CallApprover.Decision d;
        try { d = approver.decide(pending); } catch (RuntimeException e) { d = CallApprover.Decision.UNAVAILABLE; }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decision", d.name()); decision.put("at", Instant.now().toString()); decision.put("sha256", sha);
        Map<String, Object> details = approver.decisionDetails(callId);
        if (details.isEmpty()) decision.put("required_phrase", pending.approvalPhrase());
        decision.putAll(details);
        decision.put("correlation", ctx.correlation);
        evidence.writeJson(dir.resolve("approval.json"), decision);
        if (d != CallApprover.Decision.APPROVED) throw refused("operator decision " + d + "; nothing sent");
        if (approved.incrementAndGet() > maxApproved) throw refused("approved-call budget exceeded");

        evidence.write(dir.resolve("attempted"), Instant.now() + " sha256=" + sha + "\n");     // before any byte is sent
        long t0 = System.nanoTime();
        Reply r;
        try {
            sent.incrementAndGet();
            r = inner.post(url, headers, body, timeout);
        } catch (Exception e) {
            evidence.writeJson(dir.resolve("http.json"), Map.of("error", e.getClass().getSimpleName(), "latency_ms", (System.nanoTime() - t0) / 1_000_000,
                    "note", "not retried; a timed-out call may still be billed"));
            throw e;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("http_status", r.status()); http.put("latency_ms", ms); http.put("x_request_id", r.headers().getOrDefault("x-request-id", ""));
        http.putAll(usage(r.body()));
        evidence.writeJson(dir.resolve("http.json"), http);
        if (r.status() == 200) evidence.write(dir.resolve("response.json"), r.body());
        return r;
    }

    private static MuseException refused(String why) {
        return new MuseException(MuseException.Kind.CLIENT, 0, "synthetic trial: call not sent (" + why + ")");
    }

    static double round(double d) { return Math.round(d * 1e6) / 1e6; }

    /** Usage and cost from a 2xx body, when present. */
    static Map<String, Object> usage(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode u = JSON.readTree(body).path("usage");
            if (u.has("prompt_tokens") && u.has("completion_tokens")) {
                int pt = u.path("prompt_tokens").asInt(), ct = u.path("completion_tokens").asInt();
                out.put("prompt_tokens", pt); out.put("completion_tokens", ct);
                JsonNode rt = u.path("completion_tokens_details").path("reasoning_tokens");
                if (rt.isNumber()) out.put("reasoning_tokens", rt.asInt());
                out.put("cost_usd", round(pt * IN_PER_MTOK / 1e6 + ct * OUT_PER_MTOK / 1e6));
                out.put("pricing_basis", PRICING);
            } else out.put("cost_usd", "unknown: usage not reported");
        } catch (Exception e) { out.put("cost_usd", "unknown: body not JSON"); }
        return out;
    }

    /** Outbound data in readable form (synthetic content only). */
    static List<String> summary(TrialContext.Kind kind, String body) {
        List<String> lines = new ArrayList<>();
        try {
            String user = JSON.readTree(body).path("messages").get(1).path("content").asText();
            if (kind == TrialContext.Kind.SYNTHESIS) {
                JsonNode u = JSON.readTree(user);
                lines.add("question: \"" + u.path("question").asText() + "\"");
                for (JsonNode e : u.path("evidence"))
                    lines.add("evidence [" + e.path("id").asText() + "] " + e.path("speaker").asText() + " (" + e.path("stance").asText() + "): \"" + e.path("quote").asText() + "\"");
                lines.add("conflicting: " + u.path("conflicting").asBoolean() + "; replaced: " + u.path("replaced"));
                lines.add("draft: " + u.path("draft").asText().replace("\n", " / "));
            } else {
                int start = user.indexOf('['), end = user.lastIndexOf(']');
                JsonNode msgs = start >= 0 && end > start ? JSON.readTree(user.substring(start, end + 1)) : null;
                if (msgs != null && msgs.isArray()) {
                    for (JsonNode m : msgs) lines.add("message [" + m.path("id").asText() + "] " + m.path("author").asText()
                            + (m.path("extractable").asBoolean(true) ? "" : " (context only)") + ": \"" + m.path("text").asText() + "\"");
                } else lines.add("user content: " + user.length() + " chars (see the request file)");
            }
        } catch (Exception e) { lines.add("request not parseable for a summary; review the request file"); }
        lines.add("plus Dita's fixed system instructions (see the request file)");
        return lines;
    }
}
