package app.dita.app.trial;

import app.dita.telegram.TelegramAdapter;
import app.dita.telegram.TelegramGateway;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TASK-054: per-call approval with Approve / Cancel buttons in the trial chat (no typed phrases). Each pending call gets
 * a fresh random 96-bit nonce that is bound INTERNALLY to that call (its call id and request SHA-256). Only the open
 * call's nonce is accepted, once. The adapter accepts the press only from the configured trial operator (callback
 * from.id, allow-listed chat, not a bot); anything else, a stale or reused button, or the timeout sends nothing.
 * The request summary shown is what leaves the machine; the exact bytes are in the evidence directory.
 */
public final class TelegramButtonApprover implements CallApprover {
    private static final SecureRandom RNG = new SecureRandom();
    private final Duration timeout;
    private volatile TelegramAdapter.TrialChat chat;
    private Open open;                                       // at most one pending call (guarded by this)
    private final java.util.Map<String, java.util.Map<String, Object>> details = new java.util.concurrent.ConcurrentHashMap<>();

    private record Open(String nonce, PendingCall call, CompletableFuture<Decision> decision) {}

    private final double sessionBudgetUsd;
    private double reservedUsd;
    private final java.util.Set<String> budgetCallIds = new java.util.HashSet<>();
    public TelegramButtonApprover(Duration timeout) { this(timeout, 0); }
    public TelegramButtonApprover(Duration timeout, double budget) {
        if (!Double.isFinite(budget) || budget < 0 || budget > 1) throw new IllegalArgumentException("Trial budget must be 0..1 USD");
        this.timeout = timeout; this.sessionBudgetUsd = budget;
    }
    public String approvalDescription() {
        return sessionBudgetUsd > 0
            ? "Muse calls are preauthorized for this process up to $" + sessionBudgetUsd
                + " in conservative reservations at the configured historical prices; no per-call buttons. Stop the runner to end authorization."
            : "Dita asks here for Approve or Cancel before each paid Muse call.";
    }
    private synchronized Decision reserve(PendingCall c) {
        if (!budgetCallIds.add(c.callId())) return Decision.REFUSED;
        if (!c.destination().equals("https://api.meta.ai/v1/chat/completions") || !c.model().equals("muse-spark-1.3")
            || c.bytes() < 0 || c.maxCompletionTokens() < 1 || c.maxCompletionTokens() > 4096
            || !Double.isFinite(c.maxCostUsd()) || c.maxCostUsd() < 0) return Decision.REFUSED;
        // Reserve worst-case output and a conservative input allowance. Never refund failed/timed-out calls.
        double cost = Math.max(c.maxCostUsd(), ((c.bytes() + 4096.0) * ApprovalGatedTransport.IN_PER_MTOK
            + c.maxCompletionTokens() * ApprovalGatedTransport.OUT_PER_MTOK) / 1_000_000);
        boolean allowed = reservedUsd + cost <= sessionBudgetUsd;
        if (allowed) reservedUsd += cost;
        details.put(c.callId(), new java.util.LinkedHashMap<>(java.util.Map.of(
            "approval_mode", "preauthorized-session-budget", "budget_usd", sessionBudgetUsd,
            "reservation_usd", cost, "total_reserved_usd", reservedUsd,
            "note", "Historical prices; reservation is not a provider billing limit; failures are not refunded")));
        return allowed ? Decision.APPROVED : Decision.REFUSED;
    }

    void attach(TelegramAdapter.TrialChat c) { this.chat = c; }

    static String nonce() { byte[] b = new byte[12]; RNG.nextBytes(b); return HexFormat.of().formatHex(b); }

    @Override public Decision decide(PendingCall c) {
        if (sessionBudgetUsd > 0) return reserve(c);
        TelegramAdapter.TrialChat ch = chat;
        if (ch == null) return Decision.UNAVAILABLE;
        Open o;
        synchronized (this) {
            if (open != null) return Decision.REFUSED;                          // one approval at a time, never queued
            o = new Open(nonce(), c, new CompletableFuture<>());
            open = o;
        }
        try {
            String screen = text(c, timeout);
            if (screen == null) return Decision.UNAVAILABLE;                      // the full screen must fit, footer included
            long posted = ch.send(screen, List.of(
                    new TelegramGateway.Button("Approve", TelegramAdapter.TRIAL_BUTTON_PREFIX + "a:" + o.nonce() + ":y"),
                    new TelegramGateway.Button("Cancel", TelegramAdapter.TRIAL_BUTTON_PREFIX + "a:" + o.nonce() + ":n")));
            if (posted < 0) return Decision.UNAVAILABLE;                          // the request could not be shown
            details.put(c.callId(), new java.util.LinkedHashMap<>(java.util.Map.of("approval_mode", "telegram-button", "approval_message_id", posted)));
            Decision d;
            try {
                d = o.decision().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                synchronized (this) { o.decision().complete(Decision.TIMEOUT); }    // L1: a press racing the timeout loses cleanly
                d = o.decision().join();
            } catch (Exception e) {
                d = Decision.REFUSED;
            }
            if (d == Decision.TIMEOUT) ch.send("Approval for " + c.callId() + " timed out. Nothing was sent.", List.of());
            return d;
        } finally {
            synchronized (this) { if (open == o) open = null; }
        }
    }

    @Override public java.util.Map<String, Object> decisionDetails(String callId) {
        var d = details.remove(callId);
        return d == null ? java.util.Map.of() : d;
    }

    /** A button press ("<nonce>:y|n", after the "tq:a:" prefix) by Telegram user {@code fromId}. Returns the callback answer. */
    String press(String data, long fromId) {
        String[] p = data.split(":");
        synchronized (this) {
            if (p.length != 2 || open == null || !open.nonce().equals(p[0]) || open.decision().isDone())
                return "This approval is no longer open. Nothing was sent.";
            boolean yes = "y".equals(p[1]);
            var d = details.get(open.call().callId());
            if (d != null) { d.put("pressed_by", fromId); d.put("button", yes ? "Approve" : "Cancel"); }
            open.decision().complete(yes ? Decision.APPROVED : Decision.REFUSED);
            return yes ? "Approved: sending this one request." : "Cancelled: nothing was sent.";
        }
    }

    /** Escapes one piece of untrusted text for the screen: control/format characters escaped, newlines shown as ⏎. */
    static String piece(String t) { return TtyCallApprover.safe(t.replace("\r\n", "\n").replace("\n", " \u23CE ")); }

    /** The approval screen: step, what is sent, to whom, limits and cost (no request hash to type). Null if it cannot fit. */
    static String text(PendingCall c, Duration timeout) {
        boolean extraction = c.kind() == TrialContext.Kind.EXTRACTION;
        StringBuilder sb = new StringBuilder();
        sb.append(extraction ? "Approval needed: prepare knowledge (extraction)\n" : "Approval needed: answer (Muse drafts, Dita validates)\n");
        String corr = c.correlation();
        int bar = corr.indexOf(" | ");
        sb.append("Step: ").append(piece(bar >= 0 ? corr.substring(bar + 3) : corr)).append('\n');
        sb.append("Sends to Muse (").append(piece(c.model())).append(" at ").append(piece(java.net.URI.create(c.destination()).getHost())).append("):\n");
        int budget = 2600;                                     // counted AFTER escaping; the footer below is always added
        for (String line : c.summary()) {
            if (line.startsWith("plus Dita's fixed")) continue;
            String l = "• " + piece(line) + "\n";
            if (sb.length() + l.length() > budget) { sb.append("• … (more in the request file)\n"); break; }
            sb.append(l);
        }
        StringBuilder f = new StringBuilder();
        f.append("Limits: up to ").append(c.maxCompletionTokens()).append(" output tokens incl. reasoning, reasoning ")
                .append(piece(c.reasoningEffort())).append(", timeout ").append(piece(c.timeout())).append(", no retry.\n");
        f.append("Cost: at most about $").append(c.maxCostUsd()).append(" (").append(c.bytes()).append(" bytes; prices $")
                .append(ApprovalGatedTransport.IN_PER_MTOK).append("/M input, $").append(ApprovalGatedTransport.OUT_PER_MTOK)
                .append("/M output, user-supplied 2026-10-28, not a cap).\n");
        f.append("Exact request: ").append(piece(c.requestFile())).append('\n');
        f.append("Nothing is sent unless you press Approve (").append(timeout.toMinutes()).append(" min).");
        String s = sb.append(f).toString();
        return s.length() > 4000 ? null : s;
    }
}
