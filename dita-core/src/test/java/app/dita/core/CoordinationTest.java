package app.dita.core;

import app.dita.core.coordination.ApprovalAct;
import app.dita.core.coordination.ConfigChangeRequest;
import app.dita.core.coordination.EventPlan;
import app.dita.core.coordination.EventPlan.Decision;
import app.dita.core.coordination.GroupLifecycle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoordinationTest {
    static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

    @Test void preferencesNeverAuthorizeAndApprovalNeedsEveryExplicitAct() {
        EventPlan p = new EventPlan("g-parents", "Noa's 7th birthday");
        p.addTask("cake", "Bring the cake", "m10");
        p.proposeAssignee("cake", "amir");
        p.recordPreference("dana", "I'd love the park on Saturday", "m11");
        p.requestApproval(Set.of("dana", "yossi"));
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, p.status());
        assertEquals(Set.of("dana", "yossi"), p.pendingApprovers(), "a stated preference is not an approval");

        p.decide("dana", p.revision(), Decision.APPROVE, ApprovalAct.BUTTON, T);
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, p.status());
        p.decide("yossi", p.revision(), Decision.APPROVE, ApprovalAct.COMMAND, T);
        assertEquals(EventPlan.Status.APPROVED, p.status());
        assertThrows(IllegalStateException.class, () -> p.decide("stranger", p.revision(), Decision.APPROVE, ApprovalAct.BUTTON, T));
    }

    @Test void changingThePlanInvalidatesApprovals() {
        EventPlan p = new EventPlan("g", "party");
        p.addTask("cake", "Bring the cake", null);
        p.requestApproval(Set.of("dana"));
        p.decide("dana", p.revision(), Decision.APPROVE, ApprovalAct.BUTTON, T);
        assertEquals(EventPlan.Status.APPROVED, p.status());
        p.addTask("balloons", "Balloons", null);
        assertEquals(EventPlan.Status.DRAFT, p.status());
    }

    @Test void staleButtonCannotApproveARevisedPlan() {   // Natasha N-3
        EventPlan p = new EventPlan("g", "party");
        p.addTask("cake", "Bring the cake", null);
        p.requestApproval(Set.of("dana"));
        int shown = p.revision();
        p.addTask("venue", "Book nothing; just agree on the park", null);   // plan changes after buttons were sent
        p.requestApproval(Set.of("dana"));
        assertThrows(IllegalStateException.class, () -> p.decide("dana", shown, Decision.APPROVE, ApprovalAct.BUTTON, T));
        assertEquals(EventPlan.Status.AWAITING_APPROVAL, p.status());
    }

    @Test void cancelledPlanIsFrozen() {
        EventPlan p = new EventPlan("g", "party");
        p.addTask("cake", "Bring the cake", null);
        p.cancel();
        assertThrows(IllegalStateException.class, () -> p.proposeAssignee("cake", "amir"));
        assertThrows(IllegalStateException.class, () -> p.requestApproval(Set.of("dana")));
        assertThrows(IllegalStateException.class, () -> p.addOpenQuestion("when?"));
    }

    @Test void taskAcceptanceOnlyByProposedAssignee() {
        EventPlan p = new EventPlan("g", "party");
        p.addTask("cake", "Bring the cake", null);
        p.proposeAssignee("cake", "amir");
        assertThrows(IllegalStateException.class, () -> p.acceptTask("cake", "dana", ApprovalAct.BUTTON));
        assertEquals(1, p.unassignedOrUnaccepted().size());
        p.acceptTask("cake", "amir", ApprovalAct.BUTTON);
        assertTrue(p.unassignedOrUnaccepted().isEmpty());
    }

    @Test void rejectionRequestsChanges() {
        EventPlan p = new EventPlan("g", "party");
        p.requestApproval(Set.of("a", "b"));
        p.decide("a", p.revision(), Decision.REJECT, ApprovalAct.BUTTON, T);
        assertEquals(EventPlan.Status.CHANGES_REQUESTED, p.status());
    }

    @Test void configNeedsUnanimousCurrentAdmins() {
        Set<String> admins = Set.of("a1", "a2");
        ConfigChangeRequest r = new ConfigChangeRequest(Map.of("clarifying_questions", true), admins);
        assertEquals(ConfigChangeRequest.Status.PENDING, r.decide("a1", true, admins));
        assertThrows(IllegalStateException.class, () -> r.decide("member", true, admins));
        assertEquals(ConfigChangeRequest.Status.APPLIED, r.decide("a2", true, admins));

        ConfigChangeRequest veto = new ConfigChangeRequest(Map.of("x", 1), admins);
        assertEquals(ConfigChangeRequest.Status.REJECTED, veto.decide("a2", false, admins));

        ConfigChangeRequest stale = new ConfigChangeRequest(Map.of("x", 1), admins);
        stale.decide("a1", true, admins);
        assertEquals(ConfigChangeRequest.Status.STALE, stale.decide("a2", true, Set.of("a1", "a2", "a3")));
    }

    @Test void removalStartsRecoverable21DayRetention() {
        GroupLifecycle g = new GroupLifecycle();
        g.botRemoved(T);
        assertEquals(T.plus(Duration.ofDays(21)), g.purgeAfter());
        assertFalse(g.purgeDue(T.plus(Duration.ofDays(20))));
        assertTrue(g.botReadded(T.plus(Duration.ofDays(20))));
        assertEquals(GroupLifecycle.Status.ACTIVE, g.status());

        GroupLifecycle late = new GroupLifecycle();
        late.botRemoved(T);
        assertTrue(late.purgeDue(T.plus(Duration.ofDays(21))));
        assertFalse(late.botReadded(T.plus(Duration.ofDays(22))));
        late.markPurged(T.plus(Duration.ofDays(22)));
        assertEquals(GroupLifecycle.Status.PURGED, late.status());
    }
}
