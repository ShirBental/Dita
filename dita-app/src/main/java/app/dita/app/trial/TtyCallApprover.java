package app.dita.app.trial;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * TASK-054: operator approval on the controlling terminal (/dev/tty), so it works while stdout is piped to a log and is
 * never fed by Telegram or by the process's stdin. Request-bound: the operator must type {@code approve <call number>-<first 8 hex of
 * the request SHA-256>}. A wrong entry (e.g. plain "approve") is explained, and the operator may correct it for the SAME
 * request within the original timeout. "cancel", end of input, a missing terminal, the timeout or MAX_WRONG wrong entries
 * refuse. One call per decision.
 */
public final class TtyCallApprover implements CallApprover {
    private final Supplier<Terminal> terminal;
    private final Duration timeout;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "trial-approval"); t.setDaemon(true); return t; });

    public record Terminal(Reader in, Writer out) {}

    public TtyCallApprover(Duration timeout) { this(once(TtyCallApprover::devTty), timeout); }

    /** /dev/tty is opened once per run and kept (Natasha F6: no descriptor per decision). */
    static Supplier<Terminal> once(Supplier<Terminal> open) {
        return new Supplier<>() {
            private Terminal t; private boolean opened;
            @Override public synchronized Terminal get() { if (!opened) { t = open.get(); opened = true; } return t; }
        };
    }

    /**
     * Telegram-originated text is shown to the operator: C0/C1 controls, DEL, bidi controls and other format characters are
     * shown as \\uXXXX so terminal escapes cannot rewrite or hide what is being approved (Natasha F2).
     */
    static String safe(Object o) {
        String s = String.valueOf(o);
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean bad = cp < 0x20 || (cp >= 0x7F && cp <= 0x9F) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.SURROGATE;
            if (bad) {
                if (Character.isBmpCodePoint(cp)) b.append(String.format("\\u%04X", cp));
                else b.append(String.format("\\U%08X", cp));
            } else b.appendCodePoint(cp);
        });
        return b.toString();
    }

    /** One BufferedReader per terminal, so read-ahead bytes are never lost between decisions. */
    private Terminal lastTerminal; private BufferedReader lastReader;

    /** Test seam: any reader/writer pair. */
    public TtyCallApprover(Supplier<Terminal> terminal, Duration timeout) { this.terminal = terminal; this.timeout = timeout; }

    static Terminal devTty() {
        try {
            return new Terminal(new InputStreamReader(new java.io.FileInputStream("/dev/tty"), StandardCharsets.UTF_8),
                    new OutputStreamWriter(new java.io.FileOutputStream("/dev/tty"), StandardCharsets.UTF_8));
        } catch (IOException e) { return null; }
    }

    /** After a timeout a terminal read may still be pending and could consume the NEXT answer: refuse everything after. */
    private boolean disabled;

    /** Explicit refusal words (anything else that is not the exact phrase asks again, within the same timeout). */
    static final java.util.Set<String> CANCEL = java.util.Set.of("cancel", "refuse", "no", "n", "stop");
    /** Wrong entries tolerated for one request before it is refused. */
    static final int MAX_WRONG = 5;

    @Override public synchronized Decision decide(PendingCall c) {
        if (disabled) return Decision.UNAVAILABLE;
        Terminal t = terminal.get();
        if (t == null) return Decision.UNAVAILABLE;
        PrintWriter out = new PrintWriter(t.out(), true);
        String phrase = c.approvalPhrase();
        boolean extraction = c.kind() == TrialContext.Kind.EXTRACTION;
        out.println();
        out.println("==================================================================================");
        out.println(extraction
                ? " STAGE: EXTRACTION  (prepare structured knowledge from the trial messages below)"
                : " STAGE: ANSWER SYNTHESIS  (Muse drafts the reply; Dita validates it before sending)");
        out.println(" ONE live Muse call needs your approval (" + safe(c.callId()) + "). Nothing has been sent yet.");
        out.println("==================================================================================");
        out.println("  for         : " + safe(c.correlation()));
        out.println("  destination : " + safe(c.destination()) + "   model: " + safe(c.model()));
        out.println("  limits      : max_completion_tokens " + c.maxCompletionTokens() + " (includes reasoning), reasoning_effort "
                + safe(c.reasoningEffort()) + ", timeout " + safe(c.timeout()) + ", no retries");
        out.println("  request     : " + safe(c.requestFile()) + " (" + c.bytes() + " bytes, sha256 " + safe(c.sha256()) + ")");
        out.println("  cost        : at most about $" + c.maxCostUsd() + " (" + c.promptTokensUpperEstimate() + " prompt-token upper estimate + "
                + c.maxCompletionTokens() + " output worst case); " + safe(c.pricingBasis()));
        out.println("  outbound data:");
        for (String line : c.summary()) out.println("    " + safe(line));
        out.println();
        out.println("  To SEND this one call, type exactly:");
        out.println();
        out.println("        " + safe(phrase));
        out.println();
        out.println("  To refuse, type: cancel      (" + timeout.toSeconds() + " s to decide; nothing is sent unless you type the phrase)");
        if (t != lastTerminal) { lastTerminal = t; lastReader = new BufferedReader(t.in()); }
        BufferedReader in = lastReader;
        long deadline = System.nanoTime() + timeout.toNanos();
        int wrong = 0;
        while (true) {
            out.print("> "); out.flush();
            long left = deadline - System.nanoTime();
            String answer;
            Future<String> line = null;
            try {
                if (left <= 0) throw new TimeoutException();
                line = reader.submit(in::readLine);
                answer = line.get(left, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (line != null) line.cancel(true);
                disabled = true;
                out.println(); out.println("timed out: nothing sent. Approvals are now disabled for this run; restart the trial to continue.");
                return Decision.TIMEOUT;
            } catch (Exception e) {
                out.println("refused: nothing sent");
                return Decision.REFUSED;
            }
            if (answer == null) { out.println("refused (end of input): nothing sent"); return Decision.REFUSED; }
            String a = answer.strip();
            if (a.equals(phrase)) { out.println("approved: sending this one call (no retry)"); return Decision.APPROVED; }
            if (CANCEL.contains(a.toLowerCase(java.util.Locale.ROOT))) { out.println("cancelled: nothing sent"); return Decision.REFUSED; }
            if (++wrong >= MAX_WRONG) { out.println("refused after " + MAX_WRONG + " entries that were not the phrase: nothing sent"); return Decision.REFUSED; }
            out.println(a.equalsIgnoreCase("approve")
                    ? "  'approve' alone is not enough: this request needs its own phrase. Type exactly:  " + safe(phrase) + "   (or: cancel)"
                    : "  That is not the phrase for this request (nothing sent). Type exactly:  " + safe(phrase) + "   (or: cancel)");
        }
    }
}
