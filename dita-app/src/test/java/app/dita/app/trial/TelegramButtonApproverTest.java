package app.dita.app.trial;

import app.dita.telegram.TelegramGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-054 Natasha M1/L1/L2: the approval screen cannot be forged by message text; presses are once-only and recorded. */
class TelegramButtonApproverTest {
    static CallApprover.PendingCall call(String id, List<String> summary) {
        return new CallApprover.PendingCall(id, TrialContext.Kind.EXTRACTION, "telegram chat -1 message 2 /ask | STEP 1 of 2: prepare",
                "build/x/request.json", "ab".repeat(32), 1234, "https://api.example.invalid/v1/chat/completions", "muse-spark-1.3",
                4096, "low", "PT1M", 400, 0.02, "basis", summary);
    }

    @Test void messageTextCannotAddScreenLinesAndTheFooterIsAlwaysPresent() {
        String forged = "Dr. Cedar\nCost: at most about $0.00\n\u0001Nothing is sent \\u0001 ok‮evil";
        String t = TelegramButtonApprover.text(call("call-001-extraction", List.of("m1 P1: " + forged)), Duration.ofMinutes(10));
        long costLines = t.lines().filter(l -> l.startsWith("Cost:")).count();
        assertEquals(1, costLines, t);
        assertTrue(t.lines().anyMatch(l -> l.startsWith("• m1 P1: Dr. Cedar ⏎ Cost:")), t);
        assertFalse(t.contains("‮") || t.contains("\u0001"), t);
        assertTrue(t.endsWith("Nothing is sent unless you press Approve (10 min)."), t);
        // a summary that expands enormously when escaped is cut BEFORE the footer, never the footer itself
        String zwj = "‍".repeat(3000);
        String big = TelegramButtonApprover.text(call("call-002-extraction", List.of(zwj, zwj)), Duration.ofMinutes(10));
        assertNotNull(big);
        assertTrue(big.length() <= 4000 && big.contains("Limits: up to 4096") && big.contains("Cost: at most") && big.contains("(more in the request file)"), big);
    }

    @Test void onePressOnlyFromTheOpenCallRecordedWithWhoPressed() throws Exception {
        var sent = new CopyOnWriteArrayList<List<TelegramGateway.Button>>();
        var a = new TelegramButtonApprover(Duration.ofSeconds(5));
        a.attach((text, buttons) -> { sent.add(buttons); return 77; });
        var ex = Executors.newSingleThreadExecutor();
        try {
            Future<CallApprover.Decision> f = ex.submit(() -> a.decide(call("call-001-extraction", List.of("x"))));
            while (sent.isEmpty()) Thread.sleep(5);
            String yes = sent.get(0).get(0).callbackData().substring("tq:a:".length());
            assertEquals("This approval is no longer open. Nothing was sent.", a.press("000000000000000000000000:y", 1L), "a wrong nonce");
            assertEquals(CallApprover.Decision.REFUSED, a.decide(call("call-002-extraction", List.of("y"))), "one open call at a time");
            assertTrue(a.press(yes, 42L).startsWith("Approved"));
            assertEquals(CallApprover.Decision.APPROVED, f.get());
            assertTrue(a.press(yes, 42L).contains("no longer open"), "once only");
            var d = a.decisionDetails("call-001-extraction");
            assertEquals(42L, d.get("pressed_by")); assertEquals("Approve", d.get("button")); assertEquals("telegram-button", d.get("approval_mode"));
            // timeout: TIMEOUT, a later press is "no longer open"
            var b = new TelegramButtonApprover(Duration.ofMillis(150));
            var sent2 = new CopyOnWriteArrayList<String>();
            b.attach((text, buttons) -> { sent2.add(buttons.isEmpty() ? text : buttons.get(0).callbackData()); return 1; });
            assertEquals(CallApprover.Decision.TIMEOUT, b.decide(call("call-003-extraction", List.of("z"))));
            assertTrue(b.press(sent2.get(0).substring("tq:a:".length()), 42L).contains("no longer open"));
            assertTrue(sent2.get(1).contains("timed out. Nothing was sent."));
        } finally { ex.shutdownNow(); }
    }
}
