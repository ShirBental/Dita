package app.dita.core.knowledge;

import app.dita.core.events.EventPublisher;
import app.dita.core.events.Refs;
import app.dita.core.model.GroupScope;
import app.dita.core.model.KnowledgeItem;
import app.dita.core.model.Message;
import app.dita.core.model.MessageEvent;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hybrid extraction pipeline. Ingestion only {@link #enqueue}s (no model latency on the ingest path). Pending messages
 * are {@link #collect}ed into contextual batches; a batch is sent to the proposer ONLY after {@link #approve}
 * (NATASHA-EGRESS-001 E-1) and is {@link #process}ed asynchronously by a worker.
 * <ul>
 *   <li>Only SYNTHETIC, never-edited, live messages are sent (checked here and again by the Muse adapter).</li>
 *   <li>Every proposed item is validated ({@link EvidenceValidator}); rejected items never enter memory.</li>
 *   <li>Promotion is one store transaction with the outbox events. Item ids are deterministic, so re-processing the
 *       same batch never duplicates knowledge.</li>
 *   <li>Read-after-write: every promoted item is read back inside the transaction and must equal what was written.
 *       A backend that drops RFC-002 fields (e.g. no V5 schema) fails the batch instead of reporting success.</li>
 *   <li>A failed batch keeps its approval and can be {@link #retry}ed explicitly (each retry may be one more call).</li>
 * </ul>
 */
public final class ExtractionService {
    public static final int CONTEXT_MESSAGES = 10;

    public record Report(String batchId, ExtractionBatch.Status status, int accepted, int duplicates,
                         List<EvidenceValidator.Rejection> rejections, String error, int relations, int superseded) {
        public Report(String batchId, ExtractionBatch.Status status, int accepted, int duplicates,
                      List<EvidenceValidator.Rejection> rejections, String error) { this(batchId, status, accepted, duplicates, rejections, error, 0, 0); }
    }

    /** An approval is consent for one batch NOW, not standing consent: older approvals fail without any model call. */
    public static final java.time.Duration DEFAULT_APPROVAL_TTL = java.time.Duration.ofMinutes(30);

    private final MessageStore store;
    private final ExtractionQueue queue;
    private final KnowledgeProposer proposer;
    private final EventPublisher publisher;
    private final Clock clock;
    private final EvidenceValidator validator = new EvidenceValidator();
    private final int batchSize;
    private final java.time.Duration approvalTtl;

    public ExtractionService(MessageStore store, ExtractionQueue queue, KnowledgeProposer proposer, EventPublisher publisher,
                             Clock clock, int batchSize) {
        this(store, queue, proposer, publisher, clock, batchSize, DEFAULT_APPROVAL_TTL);
    }

    public ExtractionService(MessageStore store, ExtractionQueue queue, KnowledgeProposer proposer, EventPublisher publisher,
                             Clock clock, int batchSize, java.time.Duration approvalTtl) {
        this.store = store; this.queue = queue; this.proposer = proposer; this.publisher = publisher; this.clock = clock;
        this.batchSize = batchSize; this.approvalTtl = approvalTtl;
    }

    /** Called after ingestion. Only SYNTHETIC messages are eligible; anything else is ignored (never sent). */
    public boolean enqueue(GroupScope scope, Message m) {
        if (m.sourceKind() != SourceKind.SYNTHETIC || m.deleted() || m.text() == null) return false;
        queue.enqueue(scope, new ExtractionBatch.Target(m.id(), null), clock.instant());
        return true;
    }

    public List<ExtractionBatch> collect(GroupScope scope) { return queue.collect(scope, batchSize, clock.instant()); }

    /** TASK-054: batches of ONLY these pending messages (at most {@code maxTargets} per batch); the rest stay pending. */
    public List<ExtractionBatch> collect(GroupScope scope, java.util.Collection<String> only, int maxTargets) {
        return queue.collect(scope, only, Math.min(maxTargets, batchSize), clock.instant());
    }

    public List<String> pendingIds(GroupScope scope) { return queue.pendingIds(scope); }

    /** E-1: an explicit, recorded approval for exactly this batch. Refused if any target is not eligible. */
    public ExtractionBatch approve(GroupScope scope, String batchId, String approvalRef) {
        if (approvalRef == null || approvalRef.isBlank()) throw new IllegalArgumentException("approval reference required");
        ExtractionBatch b = queue.batch(scope, batchId).orElseThrow(() -> new IllegalArgumentException("unknown batch"));
        if (b.status() != ExtractionBatch.Status.COLLECTED) throw new IllegalStateException("batch is " + b.status());
        for (String id : b.messageIds()) {
            Message m = store.find(scope, id).orElseThrow(() -> new IllegalStateException("batch message missing"));
            if (m.sourceKind() != SourceKind.SYNTHETIC) throw new IllegalStateException("batch contains a non-synthetic message");
        }
        ExtractionBatch next = b.with(ExtractionBatch.Status.APPROVED, b.attempts(), approvalRef, null, clock.instant());
        if (!queue.transition(scope, ExtractionBatch.Status.COLLECTED, next)) throw new IllegalStateException("batch changed concurrently");
        return next;
    }

    /** Explicit retry of a FAILED batch under its original approval. An expired or cleaned-up approval is never revived (N-76). */
    public ExtractionBatch retry(GroupScope scope, String batchId) {
        ExtractionBatch b = queue.batch(scope, batchId).orElseThrow(() -> new IllegalArgumentException("unknown batch"));
        if ("ApprovalExpired".equals(b.lastError()) || "test-cleanup".equals(b.lastError()) || PARTIALLY_PREPARED.equals(b.lastError()))
            throw new IllegalStateException("approval expired: needs a new approval (approve after re-collecting), not a retry");
        ExtractionBatch next = b.with(ExtractionBatch.Status.APPROVED, b.attempts(), b.approvalRef(), b.lastError(), clock.instant());
        if (!queue.transition(scope, ExtractionBatch.Status.FAILED, next)) throw new IllegalStateException("only FAILED batches can be retried");
        return next;
    }

    /**
     * Operator re-extraction of a DONE batch (e.g. after a prompt change) under a NEW approval. Item ids are deterministic,
     * so knowledge that is already stored is counted as a duplicate and never stored twice.
     */
    public ExtractionBatch reprocess(GroupScope scope, String batchId, String approvalRef) {
        if (approvalRef == null || approvalRef.isBlank()) throw new IllegalArgumentException("approval reference required");
        ExtractionBatch b = queue.batch(scope, batchId).orElseThrow(() -> new IllegalArgumentException("unknown batch"));
        ExtractionBatch next = b.with(ExtractionBatch.Status.APPROVED, b.attempts(), approvalRef, null, clock.instant());
        if (!queue.transition(scope, ExtractionBatch.Status.DONE, next)) throw new IllegalStateException("only DONE batches can be re-extracted");
        return next;
    }

    /** Processes one APPROVED batch. Safe to call concurrently: the APPROVED→RUNNING transition is compare-and-set. */
    public Optional<Report> process(GroupScope scope, String batchId) { return process(scope, batchId, ContextMode.WINDOW); }

    /**
     * TASK-054: how the proposer's CONTEXT (non-extractable messages) is chosen. WINDOW (default, unchanged): the
     * {@value #CONTEXT_MESSAGES} synthetic messages before the first target plus reply parents. LINKED (question-time
     * preparation): only reply parents and messages joined to a target by a stored, non-rejected {@link
     * app.dita.core.model.MessageLink} (explicit replies, inferred references/corrections), so unrelated neighbours are
     * not sent. Targets are never chosen here.
     */
    public enum ContextMode { WINDOW, LINKED }

    public Optional<Report> process(GroupScope scope, String batchId, ContextMode mode) {
        ExtractionBatch b = queue.batch(scope, batchId).orElseThrow(() -> new IllegalArgumentException("unknown batch"));
        if (b.status() != ExtractionBatch.Status.APPROVED) return Optional.empty();
        if (b.updatedAt().plus(approvalTtl).isBefore(clock.instant())) {                    // stale approval: never sent
            ExtractionBatch expired = b.with(ExtractionBatch.Status.FAILED, b.attempts(), b.approvalRef(), "ApprovalExpired", clock.instant());
            if (!queue.transition(scope, ExtractionBatch.Status.APPROVED, expired)) return Optional.empty();
            return Optional.of(new Report(b.id(), ExtractionBatch.Status.FAILED, 0, 0, List.of(), "ApprovalExpired"));
        }
        ExtractionBatch running = b.with(ExtractionBatch.Status.RUNNING, b.attempts() + 1, b.approvalRef(), null, clock.instant());
        if (!queue.transition(scope, ExtractionBatch.Status.APPROVED, running)) return Optional.empty();
        return Optional.of(run(scope, b, running, b.messageIds(), mode, ExtractionBatch.Status.DONE, null));
    }

    /** lastError of a FAILED batch some of whose targets were later prepared by {@link #prepareFailedSubset}. */
    public static final String PARTIALLY_PREPARED = "PartiallyPrepared";

    /**
     * TASK-054 recovery: prepares ONLY {@code subset} of a FAILED batch's targets, under a NEW explicit approval
     * ({@code approvalRef}; the old approval is never revived, nothing is retried automatically). The batch stays FAILED
     * (lastError {@link #PARTIALLY_PREPARED}) unless every live target of it was in the subset, because its other targets
     * were never extracted. The schema keeps a message in at most one batch (V5), so this is the only way to prepare them.
     */
    public Report prepareFailedSubset(GroupScope scope, String batchId, java.util.Collection<String> subset, String approvalRef, ContextMode mode) {
        if (approvalRef == null || approvalRef.isBlank()) throw new IllegalArgumentException("approval reference required");
        ExtractionBatch b = queue.batch(scope, batchId).orElseThrow(() -> new IllegalArgumentException("unknown batch"));
        if (b.status() != ExtractionBatch.Status.FAILED) throw new IllegalStateException("batch is " + b.status());
        List<String> ids = b.messageIds().stream().filter(subset::contains).toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("subset is not part of the batch");
        for (String id : ids) {
            Message m = store.find(scope, id).orElseThrow(() -> new IllegalStateException("batch message missing"));
            if (m.sourceKind() != SourceKind.SYNTHETIC) throw new IllegalStateException("batch contains a non-synthetic message");
        }
        ExtractionBatch running = b.with(ExtractionBatch.Status.RUNNING, b.attempts() + 1, approvalRef, null, clock.instant());
        if (!queue.transition(scope, ExtractionBatch.Status.FAILED, running)) throw new IllegalStateException("batch changed concurrently");
        Set<String> everEdited = store.editedMessageIds(scope);
        boolean all = b.messageIds().stream().filter(id -> store.find(scope, id).filter(m -> !m.deleted()).isPresent() && !everEdited.contains(id))
                .allMatch(ids::contains);
        return run(scope, b, running, ids, mode, all ? ExtractionBatch.Status.DONE : ExtractionBatch.Status.FAILED, all ? null : PARTIALLY_PREPARED);
    }

    private Report run(GroupScope scope, ExtractionBatch b, ExtractionBatch running, List<String> targetIds, ContextMode mode,
                       ExtractionBatch.Status successStatus, String successNote) {
        try {
            Map<String, Message> targets = new LinkedHashMap<>(), parents = new LinkedHashMap<>();
            Set<String> edited = new LinkedHashSet<>();
            // TASK-046 (B46R3-12, E-2): ever-edited messages are never sent to the proposer, as targets, context or reply
            // parents, and never used to validate a subject
            Set<String> everEdited = store.editedMessageIds(scope);
            for (String id : targetIds) {
                Optional<Message> m = store.find(scope, id);
                if (m.isEmpty() || m.get().deleted() || m.get().sourceKind() != SourceKind.SYNTHETIC) continue;   // deleted since approval
                if (everEdited.contains(id)) { edited.add(id); continue; }
                targets.put(id, m.get());
                if (m.get().replyToId() != null) store.find(scope, m.get().replyToId())
                        .filter(p -> !p.deleted() && !everEdited.contains(p.id())).ifPresent(p -> parents.put(p.id(), p));
            }
            List<Message> context = new ArrayList<>();
            if (!targets.isEmpty() && mode == ContextMode.LINKED) {
                for (var l : store.links(scope)) {
                    if (l.status() == app.dita.core.model.MessageLink.Status.REJECTED) continue;
                    String other = targets.containsKey(l.fromId()) ? l.toId() : targets.containsKey(l.toId()) ? l.fromId() : null;
                    if (other == null || targets.containsKey(other) || everEdited.contains(other)) continue;
                    store.find(scope, other).filter(c -> !c.deleted() && c.sourceKind() == SourceKind.SYNTHETIC && !context.contains(c)).ifPresent(context::add);
                }
                parents.values().stream().filter(p -> p.sourceKind() == SourceKind.SYNTHETIC && !context.contains(p) && !targets.containsKey(p.id())).forEach(context::add);
            } else if (!targets.isEmpty()) {
                for (Message c : store.windowBefore(scope, targets.keySet().iterator().next(), CONTEXT_MESSAGES)) {
                    if (c.sourceKind() == SourceKind.SYNTHETIC && !targets.containsKey(c.id()) && !everEdited.contains(c.id())) context.add(c);
                }
                parents.values().stream().filter(p -> p.sourceKind() == SourceKind.SYNTHETIC && !context.contains(p) && !targets.containsKey(p.id())).forEach(context::add);
            }
            Proposal proposal = targets.isEmpty() ? new Proposal(List.of(), "none") : proposer.propose(scope, b.id(), List.copyOf(targets.values()), context);
            EvidenceValidator.Result r = validator.validate(scope, b.id(), proposal, targets, parents, edited);
            int[] dup = {0}, rel = {0}, sup = {0}, stale = {0};
            store.atomically(scope, () -> {
                List<KnowledgeItem> existing = store.knowledge(scope);
                List<KnowledgeItem> added = new ArrayList<>();
                // TASK-046 (T46-1/N-106): lock the evidence rows first, so an edit cannot commit between the check and the write
                // Codex review C3: reply parents used to validate subjects/referents are dependencies too
                List<String> lockIds = new ArrayList<>(r.accepted().stream().flatMap(k -> k.sourceMessageIds().stream()).distinct().toList());
                lockIds.addAll(parents.keySet());
                store.lockForRead(scope, lockIds);
                for (KnowledgeItem k : r.accepted()) {
                    if (store.knowledgeById(scope, k.id()).isPresent()) { dup[0]++; continue; }        // idempotent re-extraction
                    // TASK-046 (Codex P2): a message edited (or deleted) while the proposer ran was validated against its OLD
                    // revision; its items are dropped, never stored
                    if (!unchangedSince(scope, k, targets, parents)) { stale[0]++; continue; }
                    store.addKnowledge(scope, k);
                    KnowledgeItem back = store.knowledgeById(scope, k.id())
                            .orElseThrow(() -> new IllegalStateException("backend did not persist knowledge " + k.id()));
                    if (!back.equals(k)) throw new IllegalStateException("backend did not persist every RFC-002 field of " + k.id());
                    added.add(k);
                    publisher.publish(scope.groupId(), new Refs.KnowledgeRef(scope.groupId(), k.id()));
                }
                // RFC-002 §3.5: relations are recomputed by rule (RelationRules), never taken from the model.
                RelationRules.Outcome o = RelationRules.compute(scope.groupId(), existing, added, k -> primaryMessageText(scope, k),
                        q -> store.find(scope, q.messageId()).filter(m -> !m.deleted() && m.text() != null && q.spanEnd() <= m.text().length())
                                .map(m -> m.text().substring(q.spanStart(), q.spanEnd())).orElse(""),
                        k -> primaryContextText(scope, k));
                for (var relation : o.relations()) { store.addRelation(scope, relation); rel[0]++; }
                // TASK-046 (T46-2): validity has ONE writer, the reconciler (SUPERSEDED iff a SUPERSEDES relation from a
                // showable item points to it); read back so a backend that drops the change fails the batch
                for (String id : ValidityReconciler.reconcile(store, scope)) {
                    var back = store.knowledgeById(scope, id);
                    if (back.isPresent() && o.superseded().contains(id)
                            && back.get().assertion().validity() == app.dita.core.model.Assertion.Validity.SUPERSEDED) sup[0]++;
                    if (back.isPresent()) publisher.publish(scope.groupId(), new Refs.KnowledgeRef(scope.groupId(), id));
                }
                for (String id : o.superseded()) {
                    var back = store.knowledgeById(scope, id).orElseThrow();
                    boolean retired = store.relations(scope).stream().anyMatch(x -> x.kind() == app.dita.core.model.KnowledgeRelation.Kind.SUPERSEDES && x.toId().equals(id));
                    var v = back.assertion().validity();
                    boolean superseded = v == app.dita.core.model.Assertion.Validity.SUPERSEDED;
                    // DEC-038/039: a live relation ⇒ never ACTIVE (UNRESOLVED stays UNRESOLVED: withdrawal history is sticky);
                    // no relation ⇒ ACTIVE or UNRESOLVED, or SUPERSEDED only where the store cannot hold UNRESOLVED (PG < V6)
                    boolean consistent = retired ? v != app.dita.core.model.Assertion.Validity.ACTIVE
                            : (!superseded || !store.supportsUnresolvedValidity());
                    if (!consistent) throw new IllegalStateException("backend did not persist the validity of " + id);
                }
                return null;
            });
            queue.transition(scope, ExtractionBatch.Status.RUNNING, running.with(successStatus, running.attempts(), running.approvalRef(), successNote, clock.instant()));
            return new Report(b.id(), ExtractionBatch.Status.DONE, r.accepted().size() - dup[0] - stale[0], dup[0], r.rejections(), null, rel[0], sup[0]);
        } catch (RuntimeException e) {
            String err = e.getClass().getSimpleName();                                                  // never message content
            queue.transition(scope, ExtractionBatch.Status.RUNNING, running.with(ExtractionBatch.Status.FAILED, running.attempts(), running.approvalRef(), err, clock.instant()));
            return new Report(b.id(), ExtractionBatch.Status.FAILED, 0, 0, List.of(), err);
        }
    }

    /** Every evidence message is still live, never edited, and has exactly the text that was validated. */
    private boolean unchangedSince(GroupScope scope, KnowledgeItem k, Map<String, Message> validated, Map<String, Message> parents) {
        for (String mid : k.sourceMessageIds()) {
            Message then = validated.get(mid);
            if (!same(scope, mid, then)) return false;
            // Codex review C3: the reply parent the subject/referent was validated against must be the same revision too
            if (then.replyToId() != null && parents.containsKey(then.replyToId()) && !same(scope, then.replyToId(), parents.get(then.replyToId()))) return false;
        }
        return true;
    }

    private boolean same(GroupScope scope, String mid, Message then) {
        Optional<Message> now = store.find(scope, mid);
        if (then == null || now.isEmpty() || now.get().deleted() || !java.util.Objects.equals(now.get().text(), then.text())) return false;
        return store.history(scope, mid).stream().noneMatch(e -> e.kind() == MessageEvent.Kind.EDITED);
    }

    /** TASK-045: the FULL live text of the item's primary message (correction wording is often outside the quote). */
    private String primaryMessageText(GroupScope scope, KnowledgeItem k) {
        for (KnowledgeItem.Evidence e : k.asserting()) {
            var m = store.find(scope, e.messageId());
            if (m.isPresent() && !m.get().deleted() && m.get().text() != null) return m.get().text();
        }
        return "";
    }

    /** TASK-045: the primary message plus its live reply parent (subject-referent check only, never change wording). */
    private String primaryContextText(GroupScope scope, KnowledgeItem k) {
        for (KnowledgeItem.Evidence e : k.asserting()) {
            var m = store.find(scope, e.messageId());
            if (m.isEmpty() || m.get().deleted() || m.get().text() == null) continue;
            String parent = m.get().replyToId() == null ? "" : store.find(scope, m.get().replyToId())
                    .filter(p -> !p.deleted() && p.text() != null).map(app.dita.core.model.Message::text).orElse("");
            return m.get().text() + "\n" + parent;
        }
        return "";
    }

    /** Worker step: processes every APPROVED batch of every group once. Returns the reports. */
    /** No isolation filter: core tests only. Runtime workers must use {@link #processApproved(java.util.function.Predicate)} (Natasha N-3). */
    @Deprecated public List<Report> processApproved() { return processApproved(b -> true); }

    /** TASK-051 (Natasha M-3): processes only the APPROVED batches {@code eligible} accepts; the rest stay APPROVED, untouched. */
    public List<Report> processApproved(java.util.function.Predicate<ExtractionBatch> eligible) {
        List<Report> out = new ArrayList<>();
        for (String g : queue.groupsWithApproved()) {
            GroupScope scope = new GroupScope(g);
            for (ExtractionBatch b : queue.batches(scope, ExtractionBatch.Status.APPROVED)) {
                if (eligible.test(b)) process(scope, b.id()).ifPresent(out::add);
            }
        }
        return out;
    }

    public Optional<ExtractionBatch> batch(GroupScope scope, String id) { return queue.batch(scope, id); }
}
