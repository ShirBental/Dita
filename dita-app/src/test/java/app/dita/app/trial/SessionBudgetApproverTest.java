package app.dita.app.trial;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class SessionBudgetApproverTest {
    static CallApprover.PendingCall call(String id, String url, double cost) {
        return new CallApprover.PendingCall(id, TrialContext.Kind.EXTRACTION, "trial", "request.json", "ab".repeat(32),
            1000, url, "muse-spark-1.3", 4096, "low", "PT1M", 500, cost, "historical", List.of());
    }
    static final String URL = "https://api.meta.ai/v1/chat/completions";
    @Test void budgetReservesWithoutButtonsAndStopsBeforeExceedingIt() {
        var a = new TelegramButtonApprover(Duration.ofSeconds(1), 1);
        assertEquals(CallApprover.Decision.APPROVED, a.decide(call("a", URL, .6)));
        assertEquals("preauthorized-session-budget", a.decisionDetails("a").get("approval_mode"));
        assertEquals(CallApprover.Decision.REFUSED, a.decide(call("a", URL, .1)), "no duplicate authorization");
        assertEquals(CallApprover.Decision.REFUSED, a.decide(call("b", URL, .5)));
        assertEquals(CallApprover.Decision.APPROVED, a.decide(call("c", URL, .4)));
        assertEquals(CallApprover.Decision.REFUSED, a.decide(call("d", URL, .01)));
    }
    @Test void invalidBudgetsDestinationsAndCostsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TelegramButtonApprover(Duration.ofSeconds(1), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TelegramButtonApprover(Duration.ofSeconds(1), 2));
        var a = new TelegramButtonApprover(Duration.ofSeconds(1), 1);
        assertEquals(CallApprover.Decision.REFUSED, a.decide(call("a", "https://example.com", .01)));
        assertEquals(CallApprover.Decision.REFUSED, a.decide(call("b", URL, Double.NaN)));
    }
}
