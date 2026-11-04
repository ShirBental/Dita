package app.dita.app.trial;

import app.dita.muse.MuseException;
import app.dita.muse.MuseTransport;
import org.junit.jupiter.api.Test;

import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-054: the per-call gate and the terminal approver fail closed. No network: the inner transport is a fake. */
class ApprovalGateTest {
    static final URI URL = URI.create("https://api.meta.ai/v1/chat/completions");
    static final String SYNTH = "{\"messages\":[{\"role\":\"system\",\"content\":\"You write the reply of Dita, ...\"},{\"role\":\"user\",\"content\":\"{\\\"question\\\":\\\"q?\\\",\\\"evidence\\\":[]}\"}]}";
    static final String EXTRACT = "{\"messages\":[{\"role\":\"system\",\"content\":\"extract\"},{\"role\":\"user\",\"content\":\"[]\"}]}";

    final List<String> sent = new ArrayList<>();
    final MuseTransport inner = (u, h, b, t) -> { sent.add(b); return new MuseTransport.Reply(200, "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}", Map.of()); };

    ApprovalGatedTransport gate(CallApprover a, int max) throws Exception {
        Path dir = Files.createTempDirectory(Files.createDirectories(Path.of("target")), "gate");
        return new ApprovalGatedTransport(inner, a, new TrialEvidence(dir, "run-test"),
                new ApprovalGatedTransport.CallSettings(URL.toString(), "muse-spark-1.3", 4096, "low", "PT60S"), max);
    }

    @Test void noContextWrongKindSecondCallAndBudgetAreRefusedWithoutAsking() throws Exception {
        List<CallApprover.PendingCall> asked = new ArrayList<>();
        var g = gate(c -> { asked.add(c); return CallApprover.Decision.APPROVED; }, 1);
        assertThrows(MuseException.class, () -> g.post(URL, Map.of(), SYNTH, Duration.ofSeconds(1)));                 // no context
        TrialContext.during("t1", TrialContext.Kind.EXTRACTION, () -> {
            assertThrows(MuseException.class, () -> g.post(URL, Map.of(), SYNTH, Duration.ofSeconds(1)));             // wrong kind
            return null;
        });
        assertTrue(asked.isEmpty() && sent.isEmpty());
        TrialContext.during("t2", TrialContext.Kind.SYNTHESIS, () -> {
            assertDoesNotThrow(() -> g.post(URL, Map.of("Authorization", "Bearer secret"), SYNTH, Duration.ofSeconds(1)));
            assertThrows(MuseException.class, () -> g.post(URL, Map.of(), SYNTH, Duration.ofSeconds(1)));             // second call
            return null;
        });
        assertEquals(1, asked.size()); assertEquals(1, sent.size());
        TrialContext.during("t3", TrialContext.Kind.SYNTHESIS, () -> {
            assertThrows(MuseException.class, () -> g.post(URL, Map.of(), SYNTH, Duration.ofSeconds(1)));             // budget used up
            return null;
        });
        assertEquals(1, asked.size()); assertEquals(1, sent.size());
    }

    @Test void refusalSendsNothingAndApproverExceptionsRefuse() throws Exception {
        var g = gate(c -> CallApprover.Decision.REFUSED, 5);
        TrialContext.during("t", TrialContext.Kind.EXTRACTION, () -> { assertThrows(MuseException.class, () -> g.post(URL, Map.of(), EXTRACT, Duration.ofSeconds(1))); return null; });
        var g2 = gate(c -> { throw new IllegalStateException("boom"); }, 5);
        TrialContext.during("t", TrialContext.Kind.EXTRACTION, () -> { assertThrows(MuseException.class, () -> g2.post(URL, Map.of(), EXTRACT, Duration.ofSeconds(1))); return null; });
        assertTrue(sent.isEmpty());
    }

    static CallApprover.PendingCall call() {
        return new CallApprover.PendingCall("call-001", TrialContext.Kind.SYNTHESIS, "telegram chat -1 message 2 /ask", "req.json",
                "abcdef0123456789".repeat(4), 100, URL.toString(), "muse-spark-1.3", 4096, "low", "PT60S", 34, 0.02, "basis", List.of("question: \"q?\""));
    }

    @Test void terminalApprovalIsRequestBound() {
        StringWriter out = new StringWriter();
        assertEquals(CallApprover.Decision.APPROVED, new TtyCallApprover(() -> new TtyCallApprover.Terminal(new StringReader("approve 001-abcdef01\n"), out), Duration.ofSeconds(2)).decide(call()));
        assertTrue(out.toString().contains("approve 001-abcdef01") && out.toString().contains("telegram chat -1 message 2 /ask"));
        for (String typed : List.of("approve\n", "yes\n", "approve 12345678\n", "approve abcdef01\n", "approve 002-abcdef01\n", "approve 001-abcdef01 please\n", ""))
            assertEquals(CallApprover.Decision.REFUSED, new TtyCallApprover(() -> new TtyCallApprover.Terminal(new StringReader(typed), new StringWriter()), Duration.ofSeconds(2)).decide(call()), typed);
        assertEquals(CallApprover.Decision.UNAVAILABLE, new TtyCallApprover(() -> null, Duration.ofSeconds(2)).decide(call()));
    }

    @Test void identicalRequestsGetDistinctPhrasesAndTelegramTextCannotDriveTheTerminal() {
        var c1 = call();
        var c2 = new CallApprover.PendingCall("call-002-synthesis", c1.kind(), c1.correlation(), c1.requestFile(), c1.sha256(), c1.bytes(),
                c1.destination(), c1.model(), c1.maxCompletionTokens(), c1.reasoningEffort(), c1.timeout(), c1.promptTokensUpperEstimate(),
                c1.maxCostUsd(), c1.pricingBasis(), List.of("question: \"q\u001b[2K\u001b]0;x\u0007\u202Eevil\u2066\r\nhidden\u009b\""));
        assertNotEquals(c1.approvalPhrase(), c2.approvalPhrase());
        assertEquals("approve 002-abcdef01", c2.approvalPhrase());
        StringWriter out = new StringWriter();
        new TtyCallApprover(() -> new TtyCallApprover.Terminal(new StringReader("no\n"), out), Duration.ofSeconds(2)).decide(c2);
        String shown = out.toString();
        for (char bad : new char[]{0x1b, 0x07, 0x202E, 0x2066, '\r', 0x9b}) assertEquals(-1, shown.indexOf(bad), "raw U+" + Integer.toHexString(bad));
        assertTrue(shown.contains("\\u001B[2K") && shown.contains("\\u202Eevil") && shown.contains("\\u000D\\u000Ahidden"), shown);
        assertEquals("Dr. Cedar \u05D3\u05E8 ok", TtyCallApprover.safe("Dr. Cedar \u05D3\u05E8 ok"), "ordinary (incl. Hebrew) text unchanged");
    }

    @Test void productionTerminalIsOpenedOnce() {
        int[] opens = {0};
        var s = TtyCallApprover.once(() -> { opens[0]++; return null; });
        s.get(); s.get(); s.get();
        assertEquals(1, opens[0]);
    }

    /** operator's observed failure: plain "approve" for a request that needs "approve 001-<sha8>". Now explained and correctable. */
    @Test void plainApproveIsExplainedAndTheSameRequestCanBeCorrectedWithinTheTimeout() throws Exception {
        PipedWriter w = new PipedWriter();
        PipedReader r = new PipedReader(w);
        StringWriter out = new StringWriter();
        var tty = new TtyCallApprover(() -> new TtyCallApprover.Terminal(r, out), Duration.ofSeconds(10));
        var g = gate(tty, 5);
        Thread operator = Thread.ofPlatform().start(() -> {
            try {
                String phrase = null;
                for (int i = 0; i < 200 && phrase == null; i++) {                  // read the phrase off the terminal, as operator would
                    var m = java.util.regex.Pattern.compile("\\n\\s+(approve \\d{3}-[0-9a-f]{8})\\n").matcher(out.toString());
                    if (m.find()) phrase = m.group(1); else Thread.sleep(20);
                }
                w.write("approve\n"); w.flush();                                    // the observed mistake
                Thread.sleep(100);
                w.write("approve 999-00000000\n"); w.flush();                       // another wrong phrase
                Thread.sleep(100);
                w.write(phrase + "\n"); w.flush();                                  // the correct phrase, same request
                Thread.sleep(300);                                                  // keep the pipe's write end alive
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        TrialContext.during("t", TrialContext.Kind.EXTRACTION, () -> { assertDoesNotThrow(() -> g.post(URL, Map.of(), EXTRACT, Duration.ofSeconds(1))); return null; });
        operator.join();
        String shown = out.toString();
        assertTrue(shown.contains("STAGE: EXTRACTION"), shown);
        assertTrue(shown.contains("'approve' alone is not enough"), shown);
        assertTrue(shown.contains("That is not the phrase for this request"), shown);
        assertTrue(shown.contains("approved: sending this one call"), shown);
        assertEquals(1, sent.size(), "one request, sent once");
        assertEquals(1, g.approvedCalls());
    }

    @Test void plainApproveNeverSendsAndCancelOrEndOfInputRefuses() {
        for (String typed : List.of("approve\n", "approve\napprove\napprove\napprove\napprove\napprove 001-abcdef01\n", "approve\ncancel\n", "CANCEL\n", "no\n"))
            assertEquals(CallApprover.Decision.REFUSED, new TtyCallApprover(() -> new TtyCallApprover.Terminal(new StringReader(typed), new StringWriter()),
                    Duration.ofSeconds(2)).decide(call()), typed.replace("\n", "|"));
        StringWriter out = new StringWriter();
        assertEquals(CallApprover.Decision.APPROVED, new TtyCallApprover(() -> new TtyCallApprover.Terminal(new StringReader("approve\n approve 001-abcdef01 \n"), out),
                Duration.ofSeconds(2)).decide(call()));
        assertTrue(out.toString().contains("STAGE: ANSWER SYNTHESIS") && out.toString().contains("\n        approve 001-abcdef01\n"), out.toString());
    }

    /** Natasha: a phrase left buffered from an earlier prompt can never approve a later request (call number differs). */
    @Test void aStaleBufferedPhraseFromAnEarlierCallNeverApprovesTheNextOne() {
        var c1 = call();
        var c2 = new CallApprover.PendingCall("call-002-synthesis", c1.kind(), c1.correlation(), c1.requestFile(), c1.sha256(), c1.bytes(),
                c1.destination(), c1.model(), c1.maxCompletionTokens(), c1.reasoningEffort(), c1.timeout(), c1.promptTokensUpperEstimate(),
                c1.maxCostUsd(), c1.pricingBasis(), c1.summary());
        var approver = new TtyCallApprover(() -> new TtyCallApprover.Terminal(new StringReader("approve 001-abcdef01\napprove 001-abcdef01\n"), new StringWriter()),
                Duration.ofSeconds(2));
        assertEquals(CallApprover.Decision.APPROVED, approver.decide(c1));
        assertEquals(CallApprover.Decision.REFUSED, approver.decide(c2), "stale line for call 001 then end of input: refused");
    }

    @Test void aWrongEntryDoesNotExtendTheTimeout() throws Exception {
        PipedWriter w = new PipedWriter();
        PipedReader r = new PipedReader(w);
        var approver = new TtyCallApprover(() -> new TtyCallApprover.Terminal(r, new StringWriter()), Duration.ofMillis(400));
        Thread.ofPlatform().daemon().start(() -> { try { Thread.sleep(100); w.write("approve\n"); w.flush(); Thread.sleep(3000); } catch (Exception e) { } });   // writer stays alive (PipedReader)
        long t0 = System.nanoTime();
        assertEquals(CallApprover.Decision.TIMEOUT, approver.decide(call()));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
        assertEquals(CallApprover.Decision.UNAVAILABLE, approver.decide(call()), "disabled after a timeout");
    }

    @Test void timeoutRefusesAndDisablesFurtherApprovals() throws Exception {
        PipedWriter w = new PipedWriter();
        PipedReader r = new PipedReader(w);
        var approver = new TtyCallApprover(() -> new TtyCallApprover.Terminal(r, new StringWriter()), Duration.ofMillis(200));
        assertEquals(CallApprover.Decision.TIMEOUT, approver.decide(call()));
        w.write("approve 001-abcdef01\n"); w.flush();                       // a late answer must not approve the next call
        assertEquals(CallApprover.Decision.UNAVAILABLE, approver.decide(call()));
    }
}
