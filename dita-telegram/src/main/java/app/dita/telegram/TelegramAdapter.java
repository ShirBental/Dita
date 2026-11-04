package app.dita.telegram;

import app.dita.core.IngestionPipeline;
import app.dita.core.QueryService;
import app.dita.core.answer.GroundedAnswer;
import app.dita.core.coordination.ApprovalAct;
import app.dita.core.coordination.EventPlan;
import app.dita.core.coordination.GroupLifecycle;
import app.dita.core.deletion.DeletionService;
import app.dita.core.deletion.Requester;
import app.dita.core.memory.ClarificationGate;
import app.dita.core.model.GroupDirectory;
import app.dita.core.model.GroupRegistry;
import app.dita.core.model.GroupDirectory.ChatType;
import app.dita.core.model.GroupScope;
import app.dita.core.model.Message;
import app.dita.core.model.MessageLink;
import app.dita.core.model.SourceKind;
import app.dita.core.store.MessageStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps Telegram updates onto the platform-neutral core. Security properties (tested in TelegramAdapterTest):
 * <ul>
 *   <li>Scope comes only from chat.id via {@link GroupDirectory}; private chats/channels and non-allow-listed chats
 *       are ignored (synthetic demo groups only, OPEN-6/C-1).</li>
 *   <li>Identity comes only from {@code from.id}; anonymous admins / {@code sender_chat} posts are proxies (N-15).</li>
 *   <li>Commands are never ingested as memory and are ignored in edited messages; bot messages are not ingested.</li>
 *   <li>/forget verifies the reply target exists in THIS group's memory and uses the command's own date (N-14).</li>
 *   <li>Approval buttons are HMAC-signed and bound to chat, plan and revision; identity is callback_query.from.id.</li>
 *   <li>Update ids are processed at most once; message ingestion is idempotent in the store as well.</li>
 * </ul>
 * Telegram is not assumed to report message deletions (unverified); /forget is the deletion signal.
 * <p>TASK-051 synthetic replay ({@link ReplayOptions}, only with {@link ReplayTelegramGateway}): messages are SYNTHETIC
 * in a separate namespace (platform "telegram-replay", ids "tgr…"), so a replayed chat can never merge with a real chat's
 * memory. After ingestion each message is offered to extraction ({@link ExtractionHooks#ingested}); {@code /extract}
 * from a configured extraction approver collects and approves the pending batches (E-1: explicit, recorded approval).
 * Without ReplayOptions (live Telegram) messages are PLATFORM, nothing is offered to extraction and /extract is ignored.
 */
public final class TelegramAdapter {
    private static final System.Logger log = System.getLogger(TelegramAdapter.class.getName());
    static final String PLATFORM = "telegram";
    static final String REPLAY_PLATFORM = "telegram-replay";
    /** TASK-054 controlled synthetic trial on live Telegram: own platform, id prefix and approval prefix (never merges). */
    public static final String TRIAL_PLATFORM = "telegram-trial", TRIAL_PREFIX = "tgt", TRIAL_APPROVAL_PREFIX = "telegram-trial:";
    public static final String TRIAL_COMMAND = "/syn";
    /** Callback data prefix of trial buttons (setup confirmation, per-call approval). */
    public static final String TRIAL_BUTTON_PREFIX = "tq:";

    /** Posting to the trial chat (exactly one chat is allow-listed in a trial). Returns the Telegram message id, or -1. */
    @FunctionalInterface public interface TrialChat { long send(String text, List<TelegramGateway.Button> buttons); }

    /** TASK-051: runtime extraction hooks (synthetic replay only). */
    public interface ExtractionHooks {
        /** Offers an ingested message to extraction (the service accepts SYNTHETIC messages only). */
        void ingested(GroupScope scope, Message m);
        /** Collects pending messages into batches and approves every collected batch under {@code approvalRef}; returns how many. */
        int approvePending(GroupScope scope, String approvalRef);
    }

    /** TASK-051: present only for a synthetic replay run. {@code extractionApproverIds}: from.id values allowed to /extract. */
    /** {@code approvalTag}: identifies the replay file and run in every approval reference (Natasha M-1). */
    public record ReplayOptions(Set<Long> extractionApproverIds, String approvalTag, ExtractionHooks hooks) {
        public ReplayOptions {
            extractionApproverIds = Set.copyOf(extractionApproverIds);
            java.util.Objects.requireNonNull(hooks);
            if (approvalTag == null || !approvalTag.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("approval tag");
        }
    }

    /**
     * TASK-054: hooks of the controlled synthetic trial (live Telegram). The adapter never decides what is sent to Muse:
     * {@link #extract} and {@link #ask} run the production extraction and answer paths behind a per-call operator approval.
     */
    public interface TrialHooks {
        /** Offers a DECLARED synthetic trial statement to extraction. */
        void ingested(GroupScope scope, Message m);
        /** Approves and processes this group's pending batches, each behind its own call approval; returns the reply text. */
        String extract(GroupScope scope, String approvalRef, String correlation);
        /**
         * Prepares this group's pending statements (each extraction call behind its own approval, recorded under
         * {@code approvalRef}), then answers through the production QueryService (synthesis call behind its own approval).
         */
        GroundedAnswer ask(GroupScope scope, String requesterId, String question, String correlation, String approvalRef);
        /** Session declaration: may a plain (non-command) message dated {@code at} be stored as synthetic now? */
        default boolean acceptsPlainMessage(Instant at) { return true; }
        /** A trial button press ({@code data} starts with {@link #TRIAL_BUTTON_PREFIX}); returns the callback answer text. */
        default String button(long fromId, String data) { return "This button is not valid."; }
        /** The adapter hands the hooks a way to post to the single trial chat (setup screen, approval requests). */
        default void attach(TrialChat chat) { }
        /** The Telegram display name (first + last name) of a stored trial message's author, for Dita's replies only. */
        default void observedName(String authorId, String displayName) { }
    }

    /**
     * TASK-054: present only for the controlled synthetic trial on LIVE Telegram. Only {@code /syn <statement>} from an
     * allow-listed sender is stored, as SYNTHETIC, in the "telegram-trial" namespace: the sender's explicit per-message
     * declaration, not the chat's allow-list, is what makes a message synthetic. Every other message is ignored (no
     * backlog, no ordinary chat content). {@code approverIds}: who may send /extract.
     */
    public record SyntheticTrialOptions(Set<Long> approverIds, String runTag, TrialHooks hooks, boolean sessionDeclared,
                                        java.util.concurrent.Executor worker) {
        public SyntheticTrialOptions(Set<Long> approverIds, String runTag, TrialHooks hooks) {
            this(approverIds, runTag, hooks, false, Runnable::run);
        }
        public SyntheticTrialOptions(Set<Long> approverIds, String runTag, TrialHooks hooks, boolean sessionDeclared) {
            this(approverIds, runTag, hooks, sessionDeclared, Runnable::run);
        }
        public SyntheticTrialOptions {
            approverIds = Set.copyOf(approverIds);
            java.util.Objects.requireNonNull(hooks); java.util.Objects.requireNonNull(worker);
            if (approverIds.isEmpty()) throw new IllegalArgumentException("trial approvers");
            if (runTag == null || !runTag.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("run tag");
        }
    }

    private final SyntheticTrialOptions trial;          // null = not a synthetic trial
    private final AtomicLong ignoredUndeclared = new AtomicLong();
    private final ReplayOptions replay;                 // null = live Telegram
    private final String platform, prefix;
    private final SourceKind ingestKind;

    private final TelegramAdapterSettings settings;
    private final GroupDirectory directory;
    private final MessageStore store;
    private final IngestionPipeline ingestion;
    private final DeletionService deletions;
    private final QueryService queries;
    private final ClarificationGate clarifications;
    private final TelegramGateway gateway;
    private final CallbackSigner signer;
    private final Clock clock;

    private final AtomicLong rejectedAuthors = new AtomicLong(), staleUpdates = new AtomicLong();
    private final Set<Long> seenUpdates = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > 10_000; }
    }));
    private final Map<String, GroupLifecycle> lifecycles = new ConcurrentHashMap<>();
    private final Set<Long> registered = ConcurrentHashMap.newKeySet();
    private final GroupRegistry groups;
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private int planSeq = 0;

    static final class Plan {
        final String id; final String groupId; final EventPlan plan; final Set<String> participants = new LinkedHashSet<>();
        final List<String> taskIds = new ArrayList<>();
        Plan(String id, String groupId, EventPlan plan) { this.id = id; this.groupId = groupId; this.plan = plan; }
    }

    public TelegramAdapter(TelegramAdapterSettings settings, MessageStore store, IngestionPipeline ingestion,
                           DeletionService deletions, QueryService queries, ClarificationGate clarifications,
                           TelegramGateway gateway, CallbackSigner signer, Clock clock) {
        this(settings, store, ingestion, deletions, queries, clarifications, gateway, signer, clock, GroupRegistry.NONE);
    }

    /** {@code groups} persists each newly accepted group chat (Postgres: chat_group row) before its first message. */
    public TelegramAdapter(TelegramAdapterSettings settings, MessageStore store, IngestionPipeline ingestion,
                           DeletionService deletions, QueryService queries, ClarificationGate clarifications,
                           TelegramGateway gateway, CallbackSigner signer, Clock clock, GroupRegistry groups) {
        this(settings, store, ingestion, deletions, queries, clarifications, gateway, signer, clock, groups, null);
    }

    /** TASK-051: {@code replay} non-null ONLY for a synthetic replay run (see class comment). */
    public TelegramAdapter(TelegramAdapterSettings settings, MessageStore store, IngestionPipeline ingestion,
                           DeletionService deletions, QueryService queries, ClarificationGate clarifications,
                           TelegramGateway gateway, CallbackSigner signer, Clock clock, GroupRegistry groups, ReplayOptions replay) {
        this(settings, store, ingestion, deletions, queries, clarifications, gateway, signer, clock, groups, replay, null);
    }

    /** TASK-054: {@code trial} non-null ONLY for the controlled synthetic trial on live Telegram; never with {@code replay}. */
    public TelegramAdapter(TelegramAdapterSettings settings, MessageStore store, IngestionPipeline ingestion,
                           DeletionService deletions, QueryService queries, ClarificationGate clarifications,
                           TelegramGateway gateway, CallbackSigner signer, Clock clock, GroupRegistry groups, ReplayOptions replay,
                           SyntheticTrialOptions trial) {
        if (replay != null && trial != null) throw new IllegalArgumentException("a synthetic trial is never a replay");
        this.replay = replay;
        this.trial = trial;
        this.platform = trial != null ? TRIAL_PLATFORM : replay == null ? PLATFORM : REPLAY_PLATFORM;
        this.prefix = trial != null ? TRIAL_PREFIX : replay == null ? "tg" : "tgr";
        this.ingestKind = replay == null && trial == null ? SourceKind.PLATFORM : SourceKind.SYNTHETIC;
        this.groups = java.util.Objects.requireNonNull(groups);
        this.settings = settings; this.store = store; this.ingestion = ingestion; this.deletions = deletions;
        this.queries = queries; this.clarifications = clarifications; this.gateway = gateway; this.signer = signer;
        this.clock = clock;
        this.directory = new GroupDirectory(g -> lifecycle(g).status() == GroupLifecycle.Status.ACTIVE);
        if (trial != null) {
            if (settings.allowedChatIds().size() != 1) throw new IllegalArgumentException("a synthetic trial needs exactly one chat");
            long chat = settings.allowedChatIds().iterator().next();
            trial.hooks().attach((text, buttons) -> send(chat, text, null, buttons));
        }
    }

    /** TASK-054: one trial /ask at a time runs on the trial worker, so button presses keep arriving meanwhile. */
    private final java.util.concurrent.atomic.AtomicBoolean trialBusy = new java.util.concurrent.atomic.AtomicBoolean();

    private GroupLifecycle lifecycle(String groupId) {
        return lifecycles.computeIfAbsent(groupId, g -> new GroupLifecycle(settings.groupRemovalRetention()));
    }

    public GroupDirectory directory() { return directory; }
    public Optional<EventPlan> plan(String planId) { return Optional.ofNullable(plans.get(planId)).map(p -> p.plan); }

    /**
     * Processes one update. Failures are logged without message content. A TRANSIENT storage failure throws
     * {@link RetryableUpdateException} so the poller does not acknowledge the update (Shuri B1); any other failure is
     * logged and the update is skipped (a poison update must not wedge the bot).
     */
    public void handle(JsonNode update) {
        long updateId = update.path("update_id").asLong(-1);
        if (updateId < 0 || !seenUpdates.add(updateId)) return;                          // idempotent
        if (settings.ignoreBefore() != null && updateDate(update) < settings.ignoreBefore().getEpochSecond()) {
            staleUpdates.incrementAndGet();                                              // acknowledged by the poller, never processed
            log.log(System.Logger.Level.INFO, "ignored an update dated before this run started");
            return;
        }
        try {
            if (update.has("message")) onMessage(update.get("message"), false);
            else if (update.has("edited_message")) onMessage(update.get("edited_message"), true);
            else if (update.has("callback_query")) onCallback(update.get("callback_query"));
            else if (update.has("my_chat_member")) onMembership(update.get("my_chat_member"));
        } catch (RuntimeException e) {
            if (RetryableUpdateException.isTransient(e)) {                              // Shuri B1: never lose it
                seenUpdates.remove(updateId);                                            // allow the redelivery through
                log.log(System.Logger.Level.WARNING, "update " + updateId + " failed transiently (" + e.getClass().getSimpleName()
                        + "); it will be retried, not acknowledged");
                throw new RetryableUpdateException(updateId, e);
            }
            log.log(System.Logger.Level.WARNING, "update " + updateId + " failed: " + e.getClass().getSimpleName());
        }
    }

    // ---------- scope ----------

    static ChatType chatType(JsonNode chat) {
        return switch (chat.path("type").asText("")) {
            case "group" -> ChatType.GROUP;
            case "supergroup" -> ChatType.SUPERGROUP;
            case "channel" -> ChatType.CHANNEL;
            default -> ChatType.PRIVATE;                                                 // unknown types fail closed
        };
    }

    private Optional<GroupScope> scope(JsonNode chat) {
        long chatId = chat.path("id").asLong();
        ChatType type = chatType(chat);
        if (type == ChatType.PRIVATE || type == ChatType.CHANNEL) return Optional.empty();
        if (!settings.allowedChatIds().contains(chatId)) return Optional.empty();       // synthetic demo groups only
        Optional<GroupScope> s = directory.resolve(platform, Long.toString(chatId), type);
        if (s.isPresent()) return s;
        if (!registered.add(chatId)) return Optional.empty();                           // registered but inactive
        String groupId = prefix + Math.abs(chatId);
        try {
            groups.ensureRegistered(groupId, platform, Long.toString(chatId), type, ingestKind);   // before the first message (FK)
        } catch (RuntimeException e) {
            registered.remove(chatId);                                                   // retry on the next update
            throw e;
        }
        directory.register(platform, Long.toString(chatId), type, groupId);
        return directory.resolve(platform, Long.toString(chatId), type);
    }

    /**
     * TASK-040 / E-3: accept only a human sender whose platform id (from.id) is allow-listed. Anonymous admins and
     * channels (sender_chat), bots (including Dita itself) and senders without an id are rejected. Fail-closed.
     */
    boolean authorAllowed(JsonNode msgOrCallback) {
        if (msgOrCallback.has("sender_chat")) return false;
        JsonNode from = msgOrCallback.path("from");
        if (!from.path("id").canConvertToLong() || from.path("is_bot").asBoolean(true)) return false;
        return settings.allowedAuthorIds().contains(from.path("id").asLong());
    }

    /**
     * TASK-046 (B46R3-13): an author who is no longer allow-listed edits a message Dita remembers. Their NEW text is never
     * stored (TASK-040), but the OLD revision is withdrawn: the stored text becomes empty and everything derived from it is
     * invalidated. Read-only group lookup (never registers a group); only the message's own author can withdraw it.
     */
    private void withdrawEdited(JsonNode chat, JsonNode msg) {
        long chatId = chat.path("id").asLong();
        ChatType type = chatType(chat);
        if (type == ChatType.PRIVATE || type == ChatType.CHANNEL || msg.has("sender_chat")) return;
        JsonNode from = msg.path("from");
        if (!from.path("id").canConvertToLong()) return;
        String sender = Long.toString(from.path("id").asLong());
        // read-only: the directory, else the same deterministic id scope() assigns (the directory is empty after a restart,
        // which an allow-list change requires); an unknown group or message is a no-op
        GroupScope resolved = directory.resolve(platform, Long.toString(chatId), type).orElse(new GroupScope(prefix + Math.abs(chatId)));
        Optional.of(resolved).ifPresent(scope -> {
            String id = id(chatId, msg.path("message_id").asLong());
            Instant at = Instant.ofEpochSecond(msg.path("edit_date").asLong(msg.path("date").asLong()));
            store.find(scope, id).filter(m -> !m.deleted() && sender.equals(m.authorId()))
                    .ifPresent(m -> deletions.edit(scope, id, "", at));
        });
    }

    /** Counts and logs a rejected update. Never logs ids, names or text. */
    private void rejected(String kind) {
        rejectedAuthors.incrementAndGet();
        log.log(System.Logger.Level.INFO, "ignored a " + kind + " from a sender that is not allow-listed");
    }

    /** Platform date (epoch seconds) of an update; 0 when absent (treated as stale when a cutoff is set). */
    static long updateDate(JsonNode u) {
        if (u.has("message")) return u.get("message").path("date").asLong(0);
        if (u.has("edited_message")) return u.get("edited_message").path("date").asLong(0);   // original send date
        if (u.has("callback_query")) return u.get("callback_query").path("message").path("date").asLong(0);
        if (u.has("my_chat_member")) return u.get("my_chat_member").path("date").asLong(0);
        return 0;
    }

    /** Number of updates ignored because they predate this run (backlog or redelivery after restart). */
    public long staleUpdateCount() { return staleUpdates.get(); }

    /** TASK-054: messages ignored in a synthetic trial because they were not declared with /syn (never stored). */
    public long ignoredUndeclaredCount() { return ignoredUndeclared.get(); }

    /** Number of updates ignored because the sender is not allow-listed (operator metric; ids are never recorded). */
    public long rejectedAuthorCount() { return rejectedAuthors.get(); }

    static String messageId(long chatId, long messageId) { return "tg" + Math.abs(chatId) + "m" + messageId; }
    /** This adapter's namespaced message id ("tg…" live, "tgr…" replay). */
    String id(long chatId, long messageId) { return prefix + Math.abs(chatId) + "m" + messageId; }

    // ---------- messages ----------

    private void onMessage(JsonNode msg, boolean edited) throws RuntimeException {
        JsonNode chat = msg.path("chat");
        long chatId = chat.path("id").asLong();
        if (msg.has("migrate_to_chat_id") && settings.allowedChatIds().contains(chatId)) {
            scope(chat);                                                                // ensure the old id is registered
            directory.migrate(platform, Long.toString(chatId), msg.get("migrate_to_chat_id").asText());
            registered.add(msg.get("migrate_to_chat_id").asLong());
            return;
        }
        if (!settings.allowedChatIds().contains(chatId)) return;                       // checked before anything else
        if (!authorAllowed(msg)) {                                                        // TASK-040: before any write or reply
            if (edited) withdrawEdited(chat, msg);                                       // B46R3-13: but an edit still withdraws
            rejected("message");
            return;
        }
        Optional<GroupScope> maybe = scope(chat);
        if (maybe.isEmpty()) return;
        GroupScope scope = maybe.get();
        JsonNode from = msg.path("from");
        String text = msg.path("text").asText(null);
        String id = id(chatId, msg.path("message_id").asLong());
        Instant date = Instant.ofEpochSecond(msg.path("date").asLong());

        // TASK-046 (Codex C4 / N-107): an edit of a remembered message is processed BEFORE command/media classification, so the
        // replaced wording and everything derived from it are invalidated even when the new content is a command or media.
        // Commands and media are not memory: the stored text becomes empty. An edited command is never executed (N-15).
        if (edited) {
            // TASK-054: an edit of a trial statement withdraws it (an edit is not a new /syn declaration)
            String replacement = trial != null || text == null || text.startsWith("/") ? "" : text;
            store.find(scope, id).filter(m -> !m.deleted())
                    .ifPresent(m -> deletions.edit(scope, id, replacement, Instant.ofEpochSecond(msg.path("edit_date").asLong(date.getEpochSecond()))));
            return;
        }
        if (text == null) return;                                                      // media etc. not handled yet
        if (trial != null && forwardedOrViaBot(msg)) {                                 // TASK-054 (Natasha F3): a forwarded
            ignoredUndeclared.incrementAndGet();                                       // "/syn" is someone else's words
            log.log(System.Logger.Level.INFO, "synthetic trial: ignored a forwarded or inline-bot message");
            return;
        }
        if (text.startsWith("/")) {
            onCommand(scope, chatId, msg, text, date);
            return;                                                                    // commands are not memory
        }
        if (trial != null) {
            if (trial.sessionDeclared() && trial.hooks().acceptsPlainMessage(date)) {
                trialStatement(scope, chatId, msg, text, date, msg.path("message_id").asLong());
                return;
            }
            ignoredUndeclared.incrementAndGet();
            log.log(System.Logger.Level.INFO, "synthetic trial: ignored a message not declared with " + TRIAL_COMMAND);
            return;
        }
        String author = msg.has("sender_chat") ? "chat" + msg.path("sender_chat").path("id").asLong() : Long.toString(from.path("id").asLong());
        String replyTo = null;
        if (msg.has("reply_to_message")) {
            String candidate = id(chatId, msg.path("reply_to_message").path("message_id").asLong());
            if (store.find(scope, candidate).isPresent()) replyTo = candidate;          // pre-join targets stay unlinked
        }
        Message m = new Message(id, scope.groupId(), author, date, replyTo, text, ingestKind, false);
        List<MessageLink> links = ingestion.ingest(scope, m);
        if (replay != null) replay.hooks().ingested(scope, m);                          // TASK-051: runtime extraction trigger
        clarifications.consider(scope.groupId(), id, links, clock.instant()).ifPresent(c ->
                send(chatId, "I'm not sure which earlier discussion this continues. If it matters, please reply to the message you meant.",
                        msg.path("message_id").asLong(), List.of()));
    }

    private Requester requester(GroupScope scope, long chatId, JsonNode msg) {
        JsonNode from = msg.path("from");
        boolean proxy = msg.has("sender_chat") || from.path("is_bot").asBoolean(false);
        String user = Long.toString(from.path("id").asLong());
        Instant adminAt = null;
        if (!proxy && settings.adminsMayForgetOthers()) {
            try {
                if (gateway.chatAdministrators(chatId).contains(from.path("id").asLong())) adminAt = clock.instant();
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "admin lookup failed: " + e.getClass().getSimpleName());
            }
        }
        return new Requester(scope.groupId(), user, proxy, adminAt);
    }

    private void onCommand(GroupScope scope, long chatId, JsonNode msg, String text, Instant date) {
        String[] parts = text.strip().split("\\s+", 2);
        String cmd = parts[0];
        int at = cmd.indexOf('@');
        if (at >= 0) {
            if (!cmd.substring(at + 1).equalsIgnoreCase(settings.botUsername())) return; // addressed to another bot
            cmd = cmd.substring(0, at);
        }
        String arg = parts.length > 1 ? parts[1].strip() : "";
        long mid = msg.path("message_id").asLong();
        switch (cmd) {
            case "/forget" -> forget(scope, chatId, msg, date, mid);
            case "/ask" -> ask(scope, chatId, msg, arg, mid);
            case "/extract" -> {                                                         // live Telegram: ignored
                if (replay != null) extract(scope, chatId, msg, mid);
                else if (trial != null) trialExtract(scope, chatId, msg, mid);
            }
            case TRIAL_COMMAND -> { if (trial != null) trialStatement(scope, chatId, msg, arg, date, mid); }
            case "/plan", "/task", "/claim", "/join", "/approval" -> {
                if (!settings.coordinationEnabled()) { send(chatId, "Coordination is turned off for this Dita instance.", mid, List.of()); return; }
                coordinate(scope, chatId, msg, cmd, arg, mid);
            }
            default -> { }
        }
    }

    private void forget(GroupScope scope, long chatId, JsonNode msg, Instant date, long mid) {
        if (!msg.has("reply_to_message")) {
            send(chatId, "To make me forget a message, reply to it with /forget (before deleting it in Telegram).", mid, List.of());
            return;
        }
        String target = id(chatId, msg.path("reply_to_message").path("message_id").asLong());
        if (store.find(scope, target).isEmpty()) { send(chatId, "I don't have that message in this group's memory.", mid, List.of()); return; }
        try {
            boolean done = deletions.forget(scope, requester(scope, chatId, msg), target, date).isPresent();
            send(chatId, done ? "Done. I removed that message from this group's memory." : "That message was already removed.", mid, List.of());
        } catch (SecurityException e) {
            send(chatId, "Only the person who wrote that message can ask me to forget it.", mid, List.of());
        }
    }

    /** TASK-051 / E-1: explicit approval by a configured approver; the reference records chat, command and approver. */
    private void extract(GroupScope scope, long chatId, JsonNode msg, long mid) {
        JsonNode from = msg.path("from");
        if (msg.has("sender_chat") || from.path("is_bot").asBoolean(true) || !replay.extractionApproverIds().contains(from.path("id").asLong())) {
            send(chatId, "Only a configured extraction approver can approve extraction.", mid, List.of());
            return;
        }
        // A scripted approval in an operator-launched synthetic replay (Natasha Q2): file hash + run, chat, command, approver.
        String ref = REPLAY_PLATFORM + ":" + replay.approvalTag() + ":" + Math.abs(chatId) + ":m" + mid + ":by" + from.path("id").asLong();
        int n = replay.hooks().approvePending(scope, ref);
        send(chatId, n == 0 ? "Nothing is waiting for extraction." : "Approved " + n + " extraction batch" + (n == 1 ? "" : "es") + ".", mid, List.of());
    }

    /** TASK-054: a declared synthetic statement ("/syn <text>"): stored SYNTHETIC in the trial namespace, offered to extraction. */
    private void trialStatement(GroupScope scope, long chatId, JsonNode msg, String statement, Instant date, long mid) {
        if (statement.isBlank() || statement.startsWith("/")) { send(chatId, "Usage: " + TRIAL_COMMAND + " <synthetic statement>", mid, List.of()); return; }
        if (trial.sessionDeclared() && !trial.hooks().acceptsPlainMessage(date)) return;  // same session gate as plain messages
        String id = id(chatId, mid);
        if (store.find(scope, id).isPresent()) return;                                 // idempotent
        // a reply keeps its link when the parent is a stored trial message (same rule as ordinary ingestion: pre-join or
        // undeclared parents stay unlinked), so graph expansion and extraction context can use it
        String replyTo = null;
        if (msg.has("reply_to_message")) {
            String candidate = id(chatId, msg.path("reply_to_message").path("message_id").asLong());
            if (store.find(scope, candidate).filter(p -> !p.deleted()).isPresent()) replyTo = candidate;
        }
        Message m = new Message(id, scope.groupId(), Long.toString(msg.path("from").path("id").asLong()), date, replyTo, statement,
                SourceKind.SYNTHETIC, false);
        ingestion.ingest(scope, m);
        JsonNode from = msg.path("from");
        trial.hooks().observedName(m.authorId(), (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).strip());
        trial.hooks().ingested(scope, m);
        if (!trial.sessionDeclared()) send(chatId, "Stored as a synthetic trial statement (" + id + "). Nothing is sent to Muse until /extract and the operator's approval of that call.", mid, List.of());
    }

    /** TASK-054: /extract by a trial approver. Each resulting Muse call still needs its own terminal approval. */
    private void trialExtract(GroupScope scope, long chatId, JsonNode msg, long mid) {
        JsonNode from = msg.path("from");
        if (msg.has("sender_chat") || from.path("is_bot").asBoolean(true) || !trial.approverIds().contains(from.path("id").asLong())) {
            send(chatId, "Only a configured trial approver can request extraction.", mid, List.of());
            return;
        }
        String ref = TRIAL_APPROVAL_PREFIX + trial.runTag() + ":" + Math.abs(chatId) + ":m" + mid + ":by" + from.path("id").asLong();
        // like /ask: off the poll thread, so an approval button press can be received while the call waits for it
        if (!trialBusy.compareAndSet(false, true)) {
            send(chatId, "I'm still working on the previous request. Please try again when it is answered.", mid, List.of());
            return;
        }
        try {
            trial.worker().execute(() -> {
                try { send(chatId, trial.hooks().extract(scope, ref, correlation(chatId, mid, "/extract")), mid, List.of()); }
                finally { trialBusy.set(false); }
            });
        } catch (RuntimeException rejected) { trialBusy.set(false); throw rejected; }
    }

    /** Forwarded, automatically forwarded (linked channel) or sent via an inline bot: not the sender's own declaration. */
    static boolean forwardedOrViaBot(JsonNode msg) {
        return msg.has("forward_origin") || msg.has("forward_from") || msg.has("forward_from_chat") || msg.has("forward_sender_name")
                || msg.has("forward_date") || msg.path("is_automatic_forward").asBoolean(false) || msg.has("via_bot");
    }

    static String correlation(long chatId, long mid, String command) { return "telegram chat " + chatId + " message " + mid + " " + command; }

    private void ask(GroupScope scope, long chatId, JsonNode msg, String question, long mid) {
        if (question.isBlank()) { send(chatId, "Usage: /ask <question>", mid, List.of()); return; }
        if (trial != null && (msg.has("sender_chat") || msg.path("from").path("is_bot").asBoolean(true)
                || !trial.approverIds().contains(msg.path("from").path("id").asLong()))) {   // Natasha F4: the question goes to Muse
            send(chatId, "In this synthetic trial only a configured trial approver can ask.", mid, List.of());
            return;
        }
        String requester = Long.toString(msg.path("from").path("id").asLong());
        if (trial != null) {
            if (!trialBusy.compareAndSet(false, true)) {
                send(chatId, "I'm still working on the previous question. Please ask again when it is answered.", mid, List.of());
                return;
            }
            String ref = TRIAL_APPROVAL_PREFIX + trial.runTag() + ":" + Math.abs(chatId) + ":m" + mid + ":by" + requester;
            try {
                trial.worker().execute(() -> {
                    try {
                        GroundedAnswer a = trial.hooks().ask(scope, requester, question, correlation(chatId, mid, "/ask"), ref);
                        long replyId = send(chatId, a.text(), mid, List.of());
                        if (replyId > 0) queries.recordReply(scope, prefix + "reply" + Math.abs(chatId) + "m" + replyId, a);
                    } catch (RuntimeException e) {
                        log.log(System.Logger.Level.WARNING, "trial /ask failed: " + e.getClass().getSimpleName());
                    } finally { trialBusy.set(false); }
                });
            } catch (RuntimeException rejected) { trialBusy.set(false); throw rejected; }
            return;
        }
        GroundedAnswer a = queries.ask(scope, requester, question);
        long replyId = send(chatId, a.text(), mid, List.of());
        if (replyId > 0) queries.recordReply(scope, prefix + "reply" + Math.abs(chatId) + "m" + replyId, a);
        // TASK-051 observability: synthetic replay only (never live Telegram); records, never changes, the answer
        if (replay != null && gateway instanceof ReplayTelegramGateway rg) rg.answered(chatId, mid, replyId, a);
    }

    // ---------- coordination ----------

    private Optional<Plan> currentPlan(GroupScope scope) {
        return plans.values().stream().filter(p -> p.groupId.equals(scope.groupId())
                && p.plan.status() != EventPlan.Status.CANCELLED).reduce((a, b) -> b);
    }

    private synchronized void coordinate(GroupScope scope, long chatId, JsonNode msg, String cmd, String arg, long mid) {
        String member = Long.toString(msg.path("from").path("id").asLong());
        if (msg.has("sender_chat")) { send(chatId, "Anonymous senders can't take part in plans.", mid, List.of()); return; }
        switch (cmd) {
            case "/plan" -> {
                if (arg.isBlank()) { send(chatId, "Usage: /plan <title>", mid, List.of()); return; }
                String id = "p" + (++planSeq);
                Plan p = new Plan(id, scope.groupId(), new EventPlan(scope.groupId(), arg, settings.maxTasksPerPlan(), settings.allowedActs()));
                p.participants.add(member);
                plans.put(id, p);
                send(chatId, "Plan " + id + " created. Add tasks with /task, take one with /claim <number>, join with /join, then /approval.", mid, List.of());
            }
            case "/task" -> currentPlan(scope).ifPresentOrElse(p -> {
                if (arg.isBlank()) { send(chatId, "Usage: /task <description>", mid, List.of()); return; }
                String tid = "t" + (p.taskIds.size() + 1);
                p.plan.addTask(tid, arg, null);
                p.taskIds.add(tid);
                send(chatId, "Task " + p.taskIds.size() + " added to " + p.id + ". Approvals (if any) were reset.", mid, List.of());
            }, () -> send(chatId, "No open plan. Start one with /plan <title>.", mid, List.of()));
            case "/claim" -> currentPlan(scope).ifPresent(p -> {
                int n;
                try { n = Integer.parseInt(arg); } catch (NumberFormatException e) { send(chatId, "Usage: /claim <task number>", mid, List.of()); return; }
                if (n < 1 || n > p.taskIds.size()) { send(chatId, "No such task.", mid, List.of()); return; }
                String tid = p.taskIds.get(n - 1);
                p.plan.proposeAssignee(tid, member);
                p.plan.acceptTask(tid, member, ApprovalAct.COMMAND);                   // explicit act by that member
                p.participants.add(member);
                send(chatId, "Task " + n + " is now taken (explicitly accepted by its assignee).", mid, List.of());
            });
            case "/join" -> currentPlan(scope).ifPresent(p -> {
                p.participants.add(member);
                send(chatId, "You joined " + p.id + ". You'll be asked to approve it.", mid, List.of());
            });
            case "/approval" -> currentPlan(scope).ifPresent(p -> {
                if (!settings.allowedActs().contains(ApprovalAct.BUTTON)) { send(chatId, "Button approvals are disabled.", mid, List.of()); return; }
                p.plan.requestApproval(p.participants);
                int rev = p.plan.revision();
                send(chatId, "Approval requested for " + p.id + " (revision " + rev + ") from " + p.participants.size()
                        + " participant(s). Dita never books or buys anything; this only records your decision.", mid,
                        List.of(new TelegramGateway.Button("Approve", signer.sign(chatId, p.id, rev, true)),
                                new TelegramGateway.Button("Reject", signer.sign(chatId, p.id, rev, false))));
            });
            default -> { }
        }
    }

    private void onCallback(JsonNode cq) {
        String cqId = cq.path("id").asText();
        JsonNode chat = cq.path("message").path("chat");
        long chatId = chat.path("id").asLong();
        if (!settings.allowedChatIds().contains(chatId)) { answer(cqId, "This button is not valid."); return; }
        if (!authorAllowed(cq)) { rejected("button press"); answer(cqId, "This button is not valid."); return; }
        String raw = cq.path("data").asText("");
        if (trial != null && raw.startsWith(TRIAL_BUTTON_PREFIX)) {                   // TASK-054: setup / per-call approval
            long from = cq.path("from").path("id").asLong();
            answer(cqId, trial.approverIds().contains(from) ? trial.hooks().button(from, raw) : "Only the trial operator can use this button.");
            return;
        }
        Optional<GroupScope> scope = scope(chat);
        Optional<CallbackSigner.Approval> data = signer.verify(chatId, cq.path("data").asText(null));
        Plan p = data.map(d -> plans.get(d.planId())).orElse(null);
        if (scope.isEmpty() || data.isEmpty() || p == null || !p.groupId.equals(scope.get().groupId())) {
            answer(cqId, "This button is not valid.");
            return;
        }
        String member = Long.toString(cq.path("from").path("id").asLong());            // identity: from.id only
        try {
            p.plan.decide(member, data.get().revision(), data.get().approve() ? EventPlan.Decision.APPROVE : EventPlan.Decision.REJECT,
                    ApprovalAct.BUTTON, clock.instant());
            answer(cqId, "Recorded. Plan status: " + p.plan.status());
        } catch (IllegalStateException e) {
            answer(cqId, e.getMessage().startsWith("stale") ? "The plan changed since this button was sent." : "You are not an approver of this plan.");
        }
    }

    private void onMembership(JsonNode upd) {
        JsonNode chat = upd.path("chat");
        long chatId = chat.path("id").asLong();
        if (!settings.allowedChatIds().contains(chatId)) return;
        String groupId = prefix + Math.abs(chatId);
        String status = upd.path("new_chat_member").path("status").asText("");
        Instant at = Instant.ofEpochSecond(upd.path("date").asLong());
        switch (status) {
            case "left", "kicked" -> lifecycle(groupId).botRemoved(at);
            case "member", "administrator" -> lifecycle(groupId).botReadded(at);
            default -> { }
        }
    }

    // ---------- outbound ----------

    private long send(long chatId, String text, Long replyTo, List<TelegramGateway.Button> buttons) {
        try { return gateway.sendMessage(chatId, text, replyTo, buttons); }
        catch (Exception e) { log.log(System.Logger.Level.WARNING, "send failed: " + e.getClass().getSimpleName()); return -1; }
    }

    private void answer(String id, String text) {
        try { gateway.answerCallbackQuery(id, text); }
        catch (Exception e) { log.log(System.Logger.Level.WARNING, "answerCallbackQuery failed: " + e.getClass().getSimpleName()); }
    }

    public GroupLifecycle.Status groupStatus(long chatId) { return lifecycle(prefix + Math.abs(chatId)).status(); }

}
