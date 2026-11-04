package app.dita.core.coordination;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Coordination aggregate (e.g. a birthday party). Tracks proposals, tasks, open questions and explicit
 * approvals. Preferences are recorded as evidence and never count as authorization. Dita has no booking
 * or purchase capability; the plan only records decisions people make.
 */
public final class EventPlan {
    public enum Status { DRAFT, AWAITING_APPROVAL, APPROVED, CHANGES_REQUESTED, CANCELLED }
    public enum Decision { APPROVE, REJECT }
    public record Task(String id, String text, String assignee, boolean accepted, String sourceMessageId,
                       boolean sourceDeleted) {
        public Task(String id, String text, String assignee, boolean accepted, String sourceMessageId) {
            this(id, text, assignee, accepted, sourceMessageId, false);
        }
    }
    public static final String REMOVED_TEXT = "[removed: source message was deleted]";
    public record Approval(String memberId, Decision decision, ApprovalAct act, Instant at) {}
    public record Preference(String memberId, String text, String sourceMessageId) {}

    private final String groupId;
    private final String title;
    private Status status = Status.DRAFT;
    private final Set<String> approvers = new LinkedHashSet<>();
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<String> openQuestions = new ArrayList<>();
    private final List<Preference> preferences = new ArrayList<>();
    private int revision = 0;

    private final int maxTasks;
    private final Set<ApprovalAct> allowedActs;

    public EventPlan(String groupId, String title) { this(groupId, title, 50, java.util.EnumSet.allOf(ApprovalAct.class)); }

    /** {@code allowedActs}: which explicit acts count as approval (configurable); there is never an implicit act. */
    public EventPlan(String groupId, String title, int maxTasks, Set<ApprovalAct> allowedActs) {
        if (allowedActs.isEmpty()) throw new IllegalArgumentException("at least one explicit approval act required");
        this.groupId = groupId; this.title = title; this.maxTasks = maxTasks; this.allowedActs = Set.copyOf(allowedActs);
    }

    public void addTask(String id, String text, String sourceMessageId) {
        requireEditable();
        if (tasks.size() >= maxTasks && !tasks.containsKey(id)) throw new IllegalStateException("task limit reached: " + maxTasks);
        tasks.put(id, new Task(id, text, null, false, sourceMessageId));
        touched();
    }

    /** Proposing an assignee is not acceptance; the assignee must accept explicitly. */
    public void proposeAssignee(String taskId, String memberId) {
        requireEditable();
        Task t = tasks.get(taskId);
        tasks.put(taskId, new Task(t.id(), t.text(), memberId, false, t.sourceMessageId()));
        touched();
    }

    public void acceptTask(String taskId, String memberId, ApprovalAct act) {
        requireEditable();
        java.util.Objects.requireNonNull(act, "explicit act required");
        Task t = tasks.get(taskId);
        if (t.assignee() == null || !t.assignee().equals(memberId)) {
            throw new IllegalStateException("only the proposed assignee can accept " + taskId);
        }
        tasks.put(taskId, new Task(t.id(), t.text(), memberId, true, t.sourceMessageId()));
    }

    /**
     * N-11 / DEC-012 applied to coordination items derived from a message. EARLY_PURGE: the item's text is
     * replaced by a placeholder and its source reference dropped (the item's existence, assignee and approvals
     * are plan structure, not message content). LATE_TEXT_ONLY: the item is preserved and flagged. Preferences
     * sourced from the message are removed (EARLY) or kept (LATE) the same way.
     */
    public void onSourceMessageDeleted(String messageId, app.dita.core.deletion.DeletionMode mode) {
        boolean early = mode == app.dita.core.deletion.DeletionMode.EARLY_PURGE;
        tasks.replaceAll((id, t) -> !messageId.equals(t.sourceMessageId()) ? t
                : early ? new Task(t.id(), REMOVED_TEXT, t.assignee(), t.accepted(), null, true)
                        : new Task(t.id(), t.text(), t.assignee(), t.accepted(), t.sourceMessageId(), true));
        if (early) preferences.removeIf(p -> messageId.equals(p.sourceMessageId()));
    }

    public void addOpenQuestion(String q) { requireEditable(); openQuestions.add(q); }
    public void resolveQuestion(String q) { requireEditable(); openQuestions.remove(q); }

    public void cancel() { status = Status.CANCELLED; approvals.clear(); }

    public void recordPreference(String memberId, String text, String sourceMessageId) {
        preferences.add(new Preference(memberId, text, sourceMessageId));   // evidence only
    }

    public void requestApproval(Set<String> requiredMembers) {
        requireEditable();
        if (requiredMembers.isEmpty()) throw new IllegalArgumentException("approvers required");
        approvers.clear();
        approvers.addAll(requiredMembers);
        approvals.clear();
        status = Status.AWAITING_APPROVAL;
    }

    /**
     * N-3: the decision must name the plan revision the member saw (carried in Telegram callback_data as
     * planId:revision). A button on an outdated message cannot approve a changed plan.
     */
    public void decide(String memberId, int seenRevision, Decision d, ApprovalAct act, Instant at) {
        java.util.Objects.requireNonNull(act, "explicit act required");
        if (!allowedActs.contains(act)) throw new IllegalStateException("approval act not allowed: " + act);
        if (seenRevision != revision) {
            throw new IllegalStateException("stale approval: saw revision " + seenRevision + ", plan is at " + revision);
        }
        if (status != Status.AWAITING_APPROVAL && status != Status.CHANGES_REQUESTED) {
            throw new IllegalStateException("plan is not awaiting approval: " + status);
        }
        if (!approvers.contains(memberId)) throw new IllegalStateException(memberId + " is not an approver");
        approvals.put(memberId, new Approval(memberId, d, act, at));
        if (approvals.values().stream().anyMatch(a -> a.decision() == Decision.REJECT)) {
            status = Status.CHANGES_REQUESTED;
        } else if (approvals.keySet().containsAll(approvers)) {
            status = Status.APPROVED;
        } else {
            status = Status.AWAITING_APPROVAL;
        }
    }

    /** Any substantive change after approvals were requested invalidates them. */
    private void touched() {
        revision++;
        if (status == Status.AWAITING_APPROVAL || status == Status.APPROVED || status == Status.CHANGES_REQUESTED) {
            approvals.clear();
            status = Status.DRAFT;
        }
    }

    private void requireEditable() {
        if (status == Status.CANCELLED) throw new IllegalStateException("cancelled");
    }

    public Set<String> pendingApprovers() {
        Set<String> p = new LinkedHashSet<>(approvers);
        p.removeAll(approvals.keySet());
        return p;
    }

    public List<Task> unassignedOrUnaccepted() {
        return tasks.values().stream().filter(t -> !t.accepted()).toList();
    }

    public Optional<Task> task(String id) { return Optional.ofNullable(tasks.get(id)); }
    public Status status() { return status; }
    public String groupId() { return groupId; }
    public String title() { return title; }
    public int revision() { return revision; }
    public List<String> openQuestions() { return List.copyOf(openQuestions); }
    public List<Preference> preferences() { return List.copyOf(preferences); }
}
