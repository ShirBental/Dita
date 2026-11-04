package app.dita.app.trial;

import app.dita.core.QueryService;
import app.dita.core.answer.Citation;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.answer.synthesis.GroundedSynthesis;
import app.dita.core.knowledge.ExtractionBatch;
import app.dita.core.knowledge.ExtractionQueue;
import app.dita.core.knowledge.ExtractionService;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.store.MessageStore;
import app.dita.telegram.TelegramAdapter;
import app.dita.telegram.TelegramGateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TASK-054: the controlled Telegram synthetic trial on the PRODUCTION services. It adds no retrieval, ranking, answer or
 * validation logic of its own.
 * <ul>
 *   <li>/ask: question-time preparation selects the messages the ANSWER path's own retrieval finds for the question
 *   ({@link QueryService#relevantMessages}: BM25 + graph expansion over stored links, RRF; Vespa + Neo4j on POSTGRES),
 *   prepares only those that have no knowledge yet (bounded batches, linked context only, one approval per call), then
 *   answers through {@link QueryService#ask} (its synthesis call has its own approval).</li>
 *   <li>/extract (legacy): every pending message of the group, as before.</li>
 * </ul>
 * Each Muse call inside is gated by {@link ApprovalGatedTransport}. Logs ids, outcomes and counts only (never text).
 */
public final class SyntheticTrialController implements TelegramAdapter.TrialHooks {
    private static final System.Logger log = System.getLogger(SyntheticTrialController.class.getName());
    /** Session declaration values (dita.telegram.synthetic-trial.session-declaration). */
    public static final String DECLARED_AT_START = "ALL_NEW_MESSAGES_SYNTHETIC", CONFIRM_IN_TELEGRAM = "CONFIRM_IN_TELEGRAM", PREAUTHORIZED_SESSION = "PREAUTHORIZED_SESSION";

    private final ExtractionService extraction;
    private final ExtractionQueue queue;
    private final MessageStore store;
    private final QueryService queries;
    private final TrialEvidence evidence;
    private final String promptVersion;
    private final TrialReadiness readiness;
    private final Duration readinessTimeout;
    private final Limits limits;
    private final String declaration;
    private final TelegramButtonApprover buttons;          // null = terminal approvals
    private final SetupInfo setup;
    /** TASK-054: KNOWLEDGE (extraction then synthesis, DEC-046/047) or MESSAGES (raw messages → one synthesis call). */
    private final AnswerMode mode;
    /** MESSAGES mode: the answer path restricted to raw messages of this trial session (never structured knowledge). */
    private final QueryService messageQueries;

    /** Demo answer modes (dita.telegram.synthetic-trial.answer-mode). */
    public enum AnswerMode { KNOWLEDGE, MESSAGES }
    /** Messages prepared in THIS run whose extraction produced no knowledge: never sent again this run. */
    private final Set<String> preparedThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile TelegramAdapter.TrialChat chat;
    private volatile Instant confirmedAt;                   // CONFIRM_IN_TELEGRAM: when the operator confirmed the session
    private volatile String setupNonce;

    /** Bounds of question-time preparation. {@code perQuestion}: retrieval depth; {@code perBatch}: targets per extraction call. */
    public record Limits(int perQuestion, int perBatch) {
        public Limits { if (perQuestion < 1 || perBatch < 1) throw new IllegalArgumentException("limits"); }
    }

    /** What the setup screen states (configured, validated values). */
    public record SetupInfo(String groupLabel, long chatId, long senderId, String model, int maxPaidCalls, boolean semanticMessages) {
        public SetupInfo(String groupLabel, long chatId, long senderId, String model, int maxPaidCalls) {
            this(groupLabel, chatId, senderId, model, maxPaidCalls, false);
        }
    }

    public SyntheticTrialController(ExtractionService extraction, ExtractionQueue queue, MessageStore store, QueryService queries,
                                    TrialEvidence evidence, String promptVersion, TrialReadiness readiness, Duration readinessTimeout,
                                    Limits limits, String declaration, TelegramButtonApprover buttons, SetupInfo setup) {
        this(extraction, queue, store, queries, evidence, promptVersion, readiness, readinessTimeout, limits, declaration, buttons, setup,
                AnswerMode.KNOWLEDGE);
    }

    public SyntheticTrialController(ExtractionService extraction, ExtractionQueue queue, MessageStore store, QueryService queries,
                                    TrialEvidence evidence, String promptVersion, TrialReadiness readiness, Duration readinessTimeout,
                                    Limits limits, String declaration, TelegramButtonApprover buttons, SetupInfo setup, AnswerMode mode) {
        this.mode = mode == null ? AnswerMode.KNOWLEDGE : mode;
        // TASK-054 (DEC-050): MESSAGES mode selects compact, need-covering context (exchange reconstruction, corrections)
        this.messageQueries = queries.messagesOnly(this::inSession).withContextSelection(new app.dita.core.retrieval.ConversationContextSelector());
        this.extraction = extraction; this.queue = queue; this.store = store; this.queries = queries; this.evidence = evidence;
        this.promptVersion = promptVersion; this.readiness = readiness; this.readinessTimeout = readinessTimeout;
        this.limits = limits; this.declaration = declaration == null ? "" : declaration; this.buttons = buttons; this.setup = setup;
    }

    // ---------------- session setup (one screen, one button) ----------------

    public static boolean sessionBounded(String declaration) {
        return CONFIRM_IN_TELEGRAM.equals(declaration) || PREAUTHORIZED_SESSION.equals(declaration);
    }

    @Override public void attach(TelegramAdapter.TrialChat c) { this.chat = c; if (buttons != null) buttons.attach(c); }

    /** M3 (Natasha): disclose stored synthetic messages from EARLIER trial sessions of this group (they stay usable). */
    private String carriedOver() {
        var all = store.live(new GroupScope(TelegramAdapter.TRIAL_PREFIX + Math.abs(setup.chatId())));
        long n = all.size();
        if (n == 0) return "";
        Instant from = continueFrom;
        if (mode == AnswerMode.MESSAGES && from != null) {
            var authors = continueAuthors;
            long kept = all.stream().filter(m -> m.sentAt() != null && m.sentAt().getEpochSecond() >= from.getEpochSecond()
                    && authors.contains(m.authorId())).count();
            return "Continuing the session confirmed at " + from + ": its " + kept + " stored synthetic message(s) are used for answers; "
                    + (n - kept) + " older message(s) are kept but NOT used.\n";
        }
        return mode == AnswerMode.MESSAGES
                ? "Earlier sessions: " + n + " stored synthetic message(s) from EARLIER trial sessions are kept but NOT used for answers in this session.\n"
                : "Carried over: " + n + " synthetic message(s) stored by EARLIER trial sessions in this group. Relevant ones may "
                        + "be prepared and cited too.\n";
    }

    /** Posts the setup screen in the trial chat (CONFIRM_IN_TELEGRAM). Until it is confirmed, plain messages are ignored. */
    public void announce() {
        if (PREAUTHORIZED_SESSION.equals(declaration)) {
            if (confirmedAt == null) {
                confirmedAt = Instant.now();
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("kind", "session"); ev.put("declaration", declaration);
                ev.put("authorization", "operator-preauthorized-runner"); ev.put("confirmed_at", confirmedAt.toString());
                evidence.writeJson(evidence.root().resolve("session.json"), ev);
            }
            return;
        }
        if (!CONFIRM_IN_TELEGRAM.equals(declaration) || chat == null) return;
        setupNonce = TelegramButtonApprover.nonce();
        chat.send("Dita synthetic trial: session setup\n"
                + "Group: " + setup.groupLabel() + " (" + setup.chatId() + ")\n"
                + "Authorized sender: " + setup.senderId() + " (the only person whose messages are stored, who may ask and approve)\n"
                + "Declaration: every NEW message the authorized sender writes here during this session is fictional trial data. "
                + "Earlier messages, other people, forwards and bots are ignored.\n"
                + carriedOver()
                + (mode == AnswerMode.MESSAGES
                        ? "Answers: Dita finds the relevant stored messages, Muse writes ONE answer from exactly those messages, and Dita "
                                + "checks it and cites the original messages. No structured extraction.\n"
                                + (setup.semanticMessages()
                                        ? "Retrieval: local semantic search (bge-small, in Vespa) + keywords + reply links.\n"
                                        : "INTERIM retrieval: keywords (BM25) + reply links only. Raw-message semantic embeddings are NOT active.\n")
                        : "Answers: Dita prepares structured knowledge from relevant messages (extraction), then Muse writes the answer.\n")
                + "Dita stores the messages silently. Questions start with /ask. Paid Muse calls (" + setup.model()
                + ", at most " + setup.maxPaidCalls() + " this session): " + buttons.approvalDescription(),
                List.of(new TelegramGateway.Button("Confirm synthetic session", TelegramAdapter.TRIAL_BUTTON_PREFIX + "s:" + setupNonce + ":y"),
                        new TelegramGateway.Button("Cancel", TelegramAdapter.TRIAL_BUTTON_PREFIX + "s:" + setupNonce + ":n")));
    }

    @Override public boolean acceptsPlainMessage(Instant at) {
        if (DECLARED_AT_START.equals(declaration)) return true;
        Instant c = confirmedAt;
        return sessionBounded(declaration) && c != null && at.getEpochSecond() >= c.getEpochSecond();
    }

    @Override public String button(long fromId, String data) {
        String body = data.substring(TelegramAdapter.TRIAL_BUTTON_PREFIX.length());
        if (body.startsWith("a:")) return buttons == null ? "This button is not valid." : buttons.press(body.substring(2), fromId);
        if (body.startsWith("s:")) {
            String[] p = body.substring(2).split(":");
            String nonce = setupNonce;
            if (p.length != 2 || nonce == null || !nonce.equals(p[0]) || confirmedAt != null) return "This setup button is no longer valid.";
            setupNonce = null;
            if (!"y".equals(p[1])) { if (chat != null) chat.send("Session not started. Nothing is stored. Restart the runner to try again.", List.of()); return "Cancelled."; }
            confirmedAt = Instant.now();
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("kind", "session"); ev.put("declaration", declaration); ev.put("confirmed_by", fromId); ev.put("confirmed_at", confirmedAt.toString());
            evidence.writeJson(evidence.root().resolve("session.json"), ev);
            if (chat != null) chat.send("Synthetic session confirmed. Send your messages, then ask with /ask.", List.of());
            return "Session confirmed.";
        }
        return "This button is not valid.";
    }

    public boolean sessionConfirmed() { return confirmedAt != null; }

    // ---------------- extraction ----------------

    private boolean showSources = true;
    public SyntheticTrialController showSources(boolean value) { this.showSources = value; return this; }
    private volatile app.dita.core.model.MemberNames memberNames;
    /** Where observed display names go (the same map the answer path renders with). */
    public SyntheticTrialController memberNames(app.dita.core.model.MemberNames names) { this.memberNames = names; return this; }
    @Override public void observedName(String authorId, String displayName) {
        var n = memberNames; if (n != null) n.observe(authorId, displayName);
    }

    @Override public void ingested(GroupScope scope, Message m) {
        if (mode == AnswerMode.MESSAGES) return;                                    // stored and indexed only; never queued for extraction
        extraction.enqueue(scope, m);
    }

    public AnswerMode mode() { return mode; }

    /**
     * MESSAGES mode: a message belongs to this session if it was sent at or after confirmation (or always, when declared
     * at start). With an explicit continuation ({@link #continueFrom}), the window starts at the earlier session's
     * confirmation instead; the new session must still be confirmed before anything is answered.
     */
    private boolean inSession(Message m) {
        if (!sessionBounded(declaration)) return true;
        Instant c = confirmedAt;
        if (c == null || m.sentAt() == null) return false;
        if (m.sentAt().getEpochSecond() >= c.getEpochSecond()) return true;
        Instant from = continueFrom;
        // carried over from the continued session: only its window, and only this run's declared session authors (Natasha M1)
        return inContinuation(m, from, continueUntil, continueAuthors);
    }

    static boolean inContinuation(Message m, Instant from, Instant until, Set<String> authors) {
        return from != null && m.sentAt() != null && m.sentAt().getEpochSecond() >= from.getEpochSecond()
                && (until == null || m.sentAt().getEpochSecond() < until.getEpochSecond()) && authors.contains(m.authorId());
    }

    private volatile Instant continueFrom;
    private volatile Instant continueUntil;
    public SyntheticTrialController continueUntil(Instant until) { this.continueUntil = until; return this; }
    private volatile Set<String> continueAuthors = Set.of();
    /**
     * TASK-054 (DEC-050) explicit continuation of ONE earlier session (validated by {@link #continuation}); null = none.
     * {@code authors}: the only author ids whose carried-over messages are used (this run's declared session authors).
     */
    public SyntheticTrialController continueFrom(Instant from, Set<String> authors) {
        this.continueFrom = from; this.continueAuthors = authors == null ? Set.of() : Set.copyOf(authors); return this;
    }

    /**
     * Parses and validates {@code dita.telegram.synthetic-trial.continue-session-from}: empty → null; otherwise an
     * ISO-8601 instant in the past, allowed only in MESSAGES mode with CONFIRM_IN_TELEGRAM (the only mode with a session
     * window). Anything else fails startup rather than silently widening or ignoring the window.
     */
    public static Instant continuation(String raw, AnswerMode mode, String declaration, Instant now, java.nio.file.Path runsDir) {
        return continuation(raw, "", mode, declaration, now, runsDir);
    }

    public static Instant continuation(String raw, String untilRaw, AnswerMode mode, String declaration, Instant now, java.nio.file.Path runsDir) {
        if (raw == null || raw.isBlank()) {
            if (untilRaw != null && !untilRaw.isBlank()) throw new IllegalArgumentException("continuation end requires a start");
            return null;
        }
        if (mode != AnswerMode.MESSAGES || !sessionBounded(declaration))
            throw new IllegalArgumentException("continue-session-from requires answer-mode MESSAGES and session-declaration " + CONFIRM_IN_TELEGRAM);
        Instant from;
        try { from = Instant.parse(raw.strip()); }
        catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("continue-session-from is not an ISO-8601 instant: " + raw); }
        if (!from.isBefore(now)) throw new IllegalArgumentException("continue-session-from must be in the past: " + raw);
        // it must be the confirmed_at of a RECORDED session, and no later session may exist (else the window spans several)
        List<Instant> recorded = recordedSessions(runsDir);
        if (!recorded.contains(from))
            throw new IllegalArgumentException("continue-session-from is not the confirmed_at of a recorded session under " + runsDir + ": " + raw);
        Instant next = recorded.stream().filter(r -> r.isAfter(from)).min(Instant::compareTo).orElse(null);
        if (untilRaw != null && !untilRaw.isBlank()) {
            Instant until = Instant.parse(untilRaw);
            if (next == null || !next.equals(until) || !until.isBefore(now))
                throw new IllegalArgumentException("continuation end must exactly match the next recorded session");
        } else if (next != null) {
            throw new IllegalArgumentException("a later session (" + next + ") exists; an exact exclusive end is required");
        }
        return from;
    }

    /** confirmed_at of every session.json in the run directories under {@code runsDir} (the evidence parent). */
    static List<Instant> recordedSessions(java.nio.file.Path runsDir) {
        List<Instant> out = new ArrayList<>();
        if (runsDir == null || !java.nio.file.Files.isDirectory(runsDir)) return out;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var dirs = java.nio.file.Files.list(runsDir)) {
            for (var d : dirs.toList()) {
                var f = d.resolve("session.json");
                if (!java.nio.file.Files.isRegularFile(f)) continue;
                try { out.add(Instant.parse(json.readTree(f.toFile()).path("confirmed_at").asText())); }
                catch (Exception e) { throw new IllegalArgumentException("unreadable session record " + f + ": " + e.getMessage()); }
            }
        } catch (java.io.IOException e) { throw new IllegalArgumentException("cannot list " + runsDir + ": " + e.getMessage()); }
        return out;
    }

    /** Result of ONE extraction call: the production report plus what happened to the call. */
    record Prepared(String batchId, List<String> targets, ExtractionService.Report report, List<String> callIds, int callsAttempted, String decision) {
        boolean done() { return report.status() == ExtractionBatch.Status.DONE; }
        String why() {
            if (decision == null && callsAttempted == 0) return "no Muse call was made (" + report.error() + ")";
            return switch (decision == null ? "" : decision) {
                case "REFUSED" -> "the extraction request was cancelled; nothing was sent";
                case "TIMEOUT" -> "the extraction approval timed out; nothing was sent";
                case "UNAVAILABLE" -> "no approval was possible; nothing was sent";
                case "APPROVED" -> "the extraction call was sent but produced no usable result (" + report.error() + "); not retried";
                default -> "the extraction call was not sent (" + report.error() + ")";
            };
        }
    }

    private Prepared observeExtraction(String batchId, List<String> targets, String approvalRef, String correlation, String requestCorrelation,
                                       String kind, java.util.function.Supplier<ExtractionService.Report> body) {
        var obs = TrialContext.observe(correlation, TrialContext.Kind.EXTRACTION, body);
        var r = obs.value();
        var http = http(obs.callIds());
        String decision = http.isEmpty() ? null : (String) ((Map<?, ?>) http.get(0)).get("approval");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("kind", "extract"); ev.put("mode", kind); ev.put("correlation", requestCorrelation); ev.put("approval_ref", approvalRef);
        ev.put("batch", batchId); ev.put("messages", targets); ev.put("status", r.status().name());
        ev.put("accepted", r.accepted()); ev.put("duplicates", r.duplicates()); ev.put("rejections", r.rejections().size());
        ev.put("error", r.error()); ev.put("calls", obs.callIds()); ev.put("calls_attempted", obs.callsAttempted());
        ev.put("http", http);
        String name = String.format("extract-%03d.json", evidence.nextSeq());
        evidence.writeJson(evidence.root().resolve(name), ev);
        log.log(System.Logger.Level.INFO, "trial " + name + ": " + kind + " batch " + batchId + " targets=" + targets.size() + " " + r.status()
                + " accepted=" + r.accepted() + " calls=" + obs.callIds());
        return new Prepared(batchId, targets, r, obs.callIds(), obs.callsAttempted(), decision);
    }

    /** /extract (legacy, terminal flow): approves and processes every COLLECTED batch of this group. */
    @Override public String extract(GroupScope scope, String approvalRef, String correlation) {
        if (mode == AnswerMode.MESSAGES) return "Structured extraction is off in this demo mode: answers come from the stored messages. Nothing was sent.";
        extraction.collect(scope);
        List<ExtractionBatch> batches = queue.batches(scope, ExtractionBatch.Status.COLLECTED).stream()
                .sorted(java.util.Comparator.comparing(ExtractionBatch::createdAt).thenComparing(ExtractionBatch::id)).toList();
        if (batches.isEmpty()) return "Nothing is waiting for extraction.";
        List<String> lines = new ArrayList<>();
        for (ExtractionBatch b : batches) {
            extraction.approve(scope, b.id(), approvalRef);
            Prepared pr = observeExtraction(b.id(), b.messageIds(), approvalRef, correlation + " batch " + b.id(), correlation, "all-pending",
                    () -> extraction.process(scope, b.id()).orElseThrow());
            lines.add(pr.done() ? "Extraction " + b.id() + ": done, " + pr.report().accepted() + " statement(s) stored as knowledge."
                    : "Extraction " + b.id() + ": FAILED (" + pr.why() + "). Not retried.");
            if (!pr.done()) break;
        }
        return String.join("\n", lines);
    }

    // ---------------- /ask ----------------

    /** One planned extraction call: new batch of pending messages, or a subset of an earlier FAILED batch. */
    record Step(String kind, String failedBatchId, List<String> targets) {}

    /**
     * Question-time preparation plan. Candidates are the answer path's own retrieval hits (bounded by {@code perQuestion}).
     * A candidate needs preparation only if it is a live, never-edited synthetic message with no knowledge yet that was
     * not already prepared: pending ones form new batches (only them; the rest stay pending), ones stuck in a FAILED
     * batch are prepared as a subset of that batch under a NEW approval. Each step has at most {@code perBatch} targets.
     */
    Map<String, Object> plan(GroupScope scope, String requesterId, String question, List<Step> steps) {
        var hits = queries.relevantMessages(scope, requesterId, question, limits.perQuestion());
        Set<String> edited = store.editedMessageIds(scope);
        Set<String> withKnowledge = new java.util.HashSet<>();
        for (var k : store.knowledge(scope)) for (var e : k.evidence()) withKnowledge.add(e.messageId());
        Set<String> done = new java.util.HashSet<>(), stuck = new java.util.HashSet<>();
        Map<String, String> failedBatchOf = new LinkedHashMap<>();
        for (var b : queue.batches(scope, ExtractionBatch.Status.DONE)) done.addAll(b.messageIds());
        for (var st : List.of(ExtractionBatch.Status.APPROVED, ExtractionBatch.Status.RUNNING, ExtractionBatch.Status.COLLECTED))
            for (var b : queue.batches(scope, st)) stuck.addAll(b.messageIds());
        for (var b : queue.batches(scope, ExtractionBatch.Status.FAILED)) for (String id : b.messageIds()) failedBatchOf.put(id, b.id());
        Set<String> pending = new LinkedHashSet<>(queue.pendingIds(scope));

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<String> newTargets = new ArrayList<>();
        Map<String, List<String>> recover = new LinkedHashMap<>();
        for (var h : hits) {
            String id = h.messageId();
            String state;
            boolean live = store.find(scope, id).filter(m -> !m.deleted() && m.text() != null && !m.text().isBlank()
                    && m.sourceKind() == app.dita.core.model.SourceKind.SYNTHETIC).isPresent();
            if (!live || edited.contains(id)) state = "not-eligible";
            else if (withKnowledge.contains(id) || done.contains(id) || preparedThisRun.contains(id)) state = "prepared";
            else if (pending.contains(id)) { state = "pending"; newTargets.add(id); }
            else if (failedBatchOf.containsKey(id)) { state = "failed-earlier"; recover.computeIfAbsent(failedBatchOf.get(id), x -> new ArrayList<>()).add(id); }
            else if (stuck.contains(id)) state = "in-another-batch";
            else state = "not-queued";
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id); c.put("via", h.via()); c.put("inferred_context", h.inferredContext()); c.put("score", h.score()); c.put("state", state);
            candidates.add(c);
        }
        for (int i = 0; i < newTargets.size(); i += limits.perBatch())
            steps.add(new Step("new-batch", null, newTargets.subList(i, Math.min(newTargets.size(), i + limits.perBatch()))));
        for (var e : recover.entrySet())
            for (int i = 0; i < e.getValue().size(); i += limits.perBatch())
                steps.add(new Step("failed-batch-subset", e.getKey(), e.getValue().subList(i, Math.min(e.getValue().size(), i + limits.perBatch()))));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("retrieval_k", limits.perQuestion()); p.put("per_batch", limits.perBatch());
        p.put("candidates", candidates);
        p.put("steps", steps.stream().map(s -> Map.of("kind", s.kind(), "targets", s.targets(), "failed_batch", s.failedBatchId() == null ? "" : s.failedBatchId())).toList());
        p.put("left_pending", pending.stream().filter(id -> !newTargets.contains(id)).toList());
        return p;
    }

    /**
     * /ask: stage 0 projections ready → plan (above) → stage 1 one approved extraction call per step (the first that does
     * not complete stops the ask) → projections ready → stage 2 the production answer path (its synthesis call has its own
     * approval). A stopped ask composes NO answer. It never rethrows.
     */
    @Override public GroundedAnswer ask(GroupScope scope, String requesterId, String question, String correlation, String approvalRef) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("kind", "ask"); ev.put("correlation", correlation); ev.put("question", question); ev.put("prompt", promptVersion);
        try {
            if (!awaitReady(scope)) return notReady(ev, question);                     // the message index must see new messages
            if (mode == AnswerMode.MESSAGES) return answerFromMessages(ev, scope, requesterId, question, correlation);
            List<Step> steps = new ArrayList<>();
            ev.put("preparation_plan", plan(scope, requesterId, question, steps));
            List<Map<String, Object>> prepLog = new ArrayList<>();
            ev.put("preparation", prepLog);
            int stored = 0, i = 0;
            for (Step st : steps) {
                i++;
                String stage = correlation + " | STEP 1 of 2: prepare knowledge (extraction " + i + " of " + steps.size() + ", "
                        + st.targets().size() + " message(s) relevant to the question)";
                Prepared pr;
                if (st.failedBatchId() == null) {
                    List<ExtractionBatch> made = extraction.collect(scope, st.targets(), limits.perBatch());
                    if (made.size() != 1) throw new IllegalStateException("expected one batch, got " + made.size());
                    ExtractionBatch b = made.get(0);
                    extraction.approve(scope, b.id(), approvalRef);
                    pr = observeExtraction(b.id(), b.messageIds(), approvalRef, stage, correlation, st.kind(),
                            () -> extraction.process(scope, b.id(), ExtractionService.ContextMode.LINKED).orElseThrow());
                } else {
                    pr = observeExtraction(st.failedBatchId(), st.targets(), approvalRef, stage, correlation, st.kind(),
                            () -> extraction.prepareFailedSubset(scope, st.failedBatchId(), st.targets(), approvalRef, ExtractionService.ContextMode.LINKED));
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("kind", st.kind()); e.put("batch", pr.batchId()); e.put("targets", pr.targets());
                e.put("status", pr.report().status().name()); e.put("accepted", pr.report().accepted());
                e.put("calls", pr.callIds()); e.put("decision", pr.decision());
                prepLog.add(e);
                if (!pr.done()) {
                    String more = steps.size() > i ? " " + (steps.size() - i) + " further preparation step(s) were not started." : "";
                    return stopped(ev, question, "EXTRACTION_FAILED", "I could not prepare the knowledge for this question: " + pr.why() + ". "
                            + "I did not answer from raw messages, and nothing was sent for an answer." + more
                            + " Ask again to prepare these messages with a new approval.");
                }
                preparedThisRun.addAll(pr.targets());
                stored += pr.report().accepted();
            }
            ev.put("knowledge_stored", stored);
            long t0 = System.nanoTime();
            boolean ready = awaitReady(scope);
            ev.put("projections_ready", ready); ev.put("readiness_wait_ms", (System.nanoTime() - t0) / 1_000_000);
            if (!ready) return notReady(ev, question);
            // ---- stage 2: production answer path; its synthesis call has its own approval
            return answer(ev, queries, scope, requesterId, question, correlation + " | STEP 2 of 2: answer synthesis");
        } catch (RuntimeException e) {                                                 // never rethrown: no redelivery re-run
            ev.put("error", e.getClass().getSimpleName());
            return stopped(ev, question, "ERROR", "Something went wrong while preparing this answer (" + e.getClass().getSimpleName()
                    + "). Nothing further was sent and nothing is retried automatically.");
        }
    }

    /**
     * MESSAGES mode: no extraction, no queue, no knowledge. The answer path's own message retriever (with history filter,
     * evidence view and this-session eligibility) selects raw messages; the extractive composer + verifier build the
     * baseline; GroundedSynthesis makes ONE Muse call over exactly those cited messages; the validator, verifier and final
     * gate decide. Records the retrieval candidates and the channels that produced them.
     */
    private GroundedAnswer answerFromMessages(Map<String, Object> ev, GroupScope scope, String requesterId, String question, String correlation) {
        ev.put("mode", "messages");
        var hits = messageQueries.relevantMessages(scope, requesterId, question, messageQueries.retrievalDepth());
        List<Map<String, Object>> cand = new ArrayList<>();
        for (var h : hits) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", h.messageId()); c.put("via", h.via()); c.put("inferred_context", h.inferredContext()); c.put("score", h.score());
            cand.add(c);
        }
        ev.put("retrieval_k", messageQueries.retrievalDepth());
        ev.put("continue_session_from", continueFrom == null ? "" : continueFrom.toString());
        ev.put("continue_session_until", continueUntil == null ? "" : continueUntil.toString());
        var cs = app.dita.core.retrieval.ConversationContextSelector.Settings.DEFAULTS;
        ev.put("context_selection", Map.of("selector", "ConversationContextSelector/DEC-050", "needs",
                app.dita.core.retrieval.QuestionNeeds.of(question).needs().stream().map(n -> n.type().name()).toList(),
                "pool_per_query", cs.poolPerQuery(), "budget", cs.budget(), "max_span", cs.maxSpan()));
        ev.put("retrieval_candidates", cand);
        ev.put("retrieval_channels", hits.stream().flatMap(h -> app.dita.core.retrieval.HybridRetriever.channels(h.via()).stream())
                .map(SyntheticTrialController::channel).distinct().sorted().toList());
        Map<String, String> viaById = new LinkedHashMap<>();
        hits.forEach(h -> viaById.put(h.messageId(), h.via()));
        ev.put("_via_by_id", viaById);
        ev.put("extraction_calls", 0);
        return answer(ev, messageQueries, scope, requesterId, question, correlation + " | answer synthesis (one Muse call)");
    }

    /** "lexical" | "vespa:bm25" → keyword, "vespa:dense" → semantic, "graph:*" / "neo4j:*" → graph, "context:*" → context (inferred). */
    static String channel(String via) {
        if (via == null) return "unknown";
        if (via.equals("vespa:dense")) return "semantic";
        if (via.startsWith("graph:") || via.startsWith("neo4j:")) return "graph";
        if (via.startsWith("context:")) return "context";
        if (via.equals("lexical") || via.equals("vespa:bm25")) return "keyword";
        return via;
    }

    /** The production answer path under ONE synthesis call context (at most one Muse call, no retry); records the outcome. */
    private GroundedAnswer answer(Map<String, Object> ev, QueryService q, GroupScope scope, String requesterId, String question, String stage) {
        var obs = TrialContext.observe(stage, TrialContext.Kind.SYNTHESIS, () -> q.ask(scope, requesterId, question));
        GroundedAnswer a = obs.value();
        GroundedSynthesis.Result last = obs.synthesis().isEmpty() ? null : obs.synthesis().get(obs.synthesis().size() - 1);
        boolean synthesized = obs.synthesis().size() == 1 && last.outcome() == GroundedSynthesis.Outcome.SYNTHESIZED
                && last.answer().text().equals(a.text());
        String outcome = last == null ? "NOT_INVOKED" : last.outcome().name();
        boolean fallback = !synthesized && a.found();
        ev.put("synthesis_outcomes", obs.synthesis().stream().map(x -> x.outcome().name()).toList());
        ev.put("violations", last == null ? List.of() : last.violations());
        ev.put("synthesis_succeeded", synthesized); ev.put("deterministic_fallback", fallback);
        ev.put("calls", obs.callIds()); ev.put("calls_attempted", obs.callsAttempted()); ev.put("http", http(obs.callIds()));
        Map<String, Object> fin = new LinkedHashMap<>();
        fin.put("found", a.found()); fin.put("text", a.text()); fin.put("conflicting", a.conflicting()); fin.put("knowledge_ids", a.knowledgeIds());
        List<Map<String, Object>> cites = new ArrayList<>();
        for (Citation c : a.citations()) cites.add(Map.of("message_id", c.messageId(), "author", c.authorId(), "quote", c.quote()));
        fin.put("citations", cites);
        ev.put("final_answer", fin);
        ev.put("final_sources", a.citations().stream().map(Citation::messageId).sorted().toList());
        @SuppressWarnings("unchecked") Map<String, String> viaById = (Map<String, String>) ev.remove("_via_by_id");
        if (viaById != null) {
            // which channels actually contributed the CITED evidence (not just candidates)
            ev.put("evidence_channels", a.citations().stream().map(c -> viaById.getOrDefault(c.messageId(), "unknown"))
                    .flatMap(v -> app.dita.core.retrieval.HybridRetriever.channels(v).stream()).map(SyntheticTrialController::channel)
                    .distinct().sorted().toList());
            Map<String, String> perSource = new LinkedHashMap<>();
            a.citations().forEach(c -> perSource.put(c.messageId(), viaById.getOrDefault(c.messageId(), "unknown")));
            ev.put("source_channels", perSource);
        }
        String name = write(ev);
        log.log(System.Logger.Level.INFO, "trial " + name + ": outcome=" + outcome + " synthesized=" + synthesized + " fallback=" + fallback
                + " sources=" + ev.get("final_sources") + " calls=" + obs.callIds());
        return marked(a, synthesized, outcome, showSources);
    }

    private GroundedAnswer notReady(Map<String, Object> ev, String question) {
        return stopped(ev, question, "PROJECTIONS_NOT_READY", "The search index has not caught up yet (waited " + readinessTimeout.toSeconds()
                + " s). I did not answer and nothing was sent for an answer. Please /ask again in a moment.");
    }

    private boolean awaitReady(GroupScope scope) {
        long deadline = System.nanoTime() + readinessTimeout.toNanos();
        while (true) {
            try { if (readiness.ready(scope)) return true; } catch (RuntimeException e) { /* not ready */ }
            if (System.nanoTime() >= deadline) return false;
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
    }

    private GroundedAnswer stopped(Map<String, Object> ev, String question, String status, String text) {
        ev.put("stopped", status); ev.put("synthesis_succeeded", false); ev.put("deterministic_fallback", false);
        ev.put("final_answer", null); ev.put("final_sources", List.of());
        String name = write(ev);
        log.log(System.Logger.Level.INFO, "trial " + name + ": stopped " + status);
        return new GroundedAnswer(question, text + "\n[trial] No answer: " + status + ".", List.of(), false, false, List.of());
    }

    private String write(Map<String, Object> ev) {
        String name = String.format("ask-%03d.json", evidence.nextSeq());
        evidence.writeJson(evidence.root().resolve(name), ev);
        return name;
    }

    /** Trial-only footer: says plainly whether Muse wrote the answer, and lists the cited sources with their quotes. */
    static GroundedAnswer marked(GroundedAnswer a, boolean synthesized, String outcome) {
        return marked(a, synthesized, outcome, true);
    }
    static GroundedAnswer marked(GroundedAnswer a, boolean synthesized, String outcome, boolean showSources) {
        if (synthesized && !showSources) {
            String text = a.text().replaceAll("\\[[A-Za-z0-9_.:-]{1,80}\\]", "").replaceAll(" +([.,;:])", "$1")
                    .replaceAll(" {2,}", " ").strip();
            return new GroundedAnswer(a.question(), text, a.citations(), a.conflicting(), a.found(), a.knowledgeIds());
        }
        StringBuilder sb = new StringBuilder(a.text());
        if (a.found() && !a.citations().isEmpty()) {
            sb.append("\nSources:");
            for (Citation c : a.citations()) sb.append("\n[").append(c.messageId()).append("] \"").append(c.quote()).append("\"");
        }
        // A validated Muse answer carries no footer (operator, 2026-11-04); the outcome stays in ask-NNN.json. Every other
        // outcome keeps its plain label, so a non-Muse reply is never presented as a Muse answer.
        if (synthesized) return new GroundedAnswer(a.question(), sb.toString(), a.citations(), a.conflicting(), a.found(), a.knowledgeIds());
        sb.append("\n[trial] ").append("ABSTAINED".equals(outcome) ? "Muse abstained: the cited messages do not answer this question, so Dita gives no answer."
                : "NOT_INVOKED".equals(outcome) && !a.found() ? "No relevant messages found; no Muse call was made."
                : "Not a Muse answer: Dita's deterministic reply (synthesis outcome " + outcome + ").");
        return new GroundedAnswer(a.question(), sb.toString(), a.citations(), a.conflicting(), a.found(), a.knowledgeIds());
    }

    /** http.json of each call (status, latency, usage, cost), when present. */
    private List<Object> http(List<String> callIds) {
        List<Object> out = new ArrayList<>();
        for (String id : callIds) {
            Path f = evidence.root().resolve(id).resolve("http.json");
            Path a = evidence.root().resolve(id).resolve("approval.json");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("call", id);
            try {
                if (Files.isRegularFile(a)) m.put("approval", TrialEvidence.JSON.readTree(Files.readString(a)).path("decision").asText());
                if (Files.isRegularFile(f)) m.put("http", TrialEvidence.JSON.readTree(Files.readString(f)));
            } catch (Exception e) { m.put("error", "unreadable"); }
            out.add(m);
        }
        return out;
    }
}
