# Dita system configuration (`dita.*`)

The configuration is owned by Dita's **system administrator**, not by Telegram group admins.

- **Source:** `dita-app/src/main/resources/application.yml`, overridable by standard Spring sources (env vars such as `DITA_DELETION_EARLY_WINDOW`, or `--dita.…` arguments).
- **Binding:** `DitaProperties`, typed with `@ConfigurationProperties(prefix="dita", ignoreUnknownFields=false)` and `@Validated`.
- **Cross-field and safety validation:** `DitaSettings.from`.
- **Lifecycle:** read once at startup. Changes take effect after a restart. There is no runtime refresh, per-group configuration, voting, configuration database or UI.
- **Startup audit:** every security-relevant value that differs from the product-owner default is logged at WARN (`ConfigAudit`). No secrets are logged.
- **Credentials** are never properties. Only the **names** of the environment variables that hold them are configured: `dita.telegram.token-env-var` and `callback-secret-env-var`.

## Settings (all implemented, with defined semantics)

| Key | Default | Allowed | Semantics |
|---|---|---|---|
| `deletion.early-window` | `5m` | 5m–24h | A deletion signal ≤ window after the **original** send time is EARLY (inclusive; edits don't restart the clock). It can only be widened (N-26). |
| `deletion.early-behavior` | `REMOVE_CONTRIBUTION_AND_RECALCULATE` | that value only | Selective dependency invalidation (see below). |
| `deletion.late-derived-knowledge` | `PRESERVE_WITH_DELETED_SOURCE_PROVENANCE` | or `REMOVE_CONTRIBUTION_AND_RECALCULATE` | What happens to derived knowledge on late deletion. Source text is removed in both cases. |
| `deletion.repeated-wording` | `REDACT_WORDING` | or `REMOVE_ITEM` | Derived wording (and subjects) repeating any version of the deleted text is never kept verbatim. |
| `deletion.group-removal-retention` | `21d` | 1d–21d | Removing Dita starts a recoverable window; rejoining within it restores memory; each removal starts a new window. It can only be shortened (N-26). |
| `deletion.admins-may-forget-others` | `false` | bool | If true, a **platform-verified** admin (checked within 10 minutes via getChatAdministrators) may `/forget` others' messages. Anonymous admins are never accepted. |
| `deletion.dita-replies` | `KEEP` | or `REPORT_AFFECTED` | Dita never deletes its own published replies during ordinary deletion (C-11). REPORT lists affected reply ids in the deletion event. |
| `memory.history-access` | `FULL_GROUP_HISTORY` | or `SINCE_REQUESTER_JOINED` | Applied server-side to every answer. It fails closed when the join time is unknown. |
| `memory.answers.max-quote-chars` | `200` | 40–1000 | Keeps quotation minimal. |
| `memory.context.link-window-messages` | `50` | 1–1000 | Earlier messages considered for link inference and recalculation. |
| `memory.context.temporal-half-life` | `30m` | > 0 | Temporal decay of link evidence. |
| `memory.linking.candidate-threshold` | `0.35` | (0,1) | Minimum score for storing an inferred CANDIDATE link. These links are never promoted to FACT. |
| `memory.linking.ambiguity-margin` | `0.05` | [0,0.5) | A runner-up within the margin is kept, so ambiguity is surfaced rather than hidden. |
| `memory.clarification.*` | disabled; 0.35 / 0.05 / 2 per 1h | see yml | Asks "which discussion?" when the two best candidates are within the gap. Rate-limited per group. Never quotes. |
| `processing.escalation.adjudication-low/high` | `0.30` / `0.55` | 0 ≤ low ≤ high ≤ 1 | Candidate scores in the band are marked `needs_adjudication` for tier 1. |
| `processing.tiers.t1/t2.provider` | `NONE` | `NONE` only for now | `META_MODEL_API` **fails startup** until the adapter exists and G4 is approved. Tier 0 (rules) is always on. |
| `processing.dispatch.*` | 100 / 2 threads / 5 attempts / 1s → 1m / 500ms | see yml | Outbox relay: retries with exponential backoff, then dead-lettering (ids only). Deletion events are **never** dead-lettered: the consumer blocks and is reported as `blockedOnDeletion`. |
| `retrieval.sparse.*` | on, k1=1.2, b=0.75 | see yml | BM25. |
| `retrieval.dense.enabled` | `false` | `true` only on POSTGRES with the hash-verified hybrid Vespa package (bge-small-en-v1.5 @5c38ec7c, 384-D, built by `scripts/task049-package.py`) | TASK-049, integrated by TASK-052. IN_MEMORY + `true` fails startup. On POSTGRES, startup runs a real dense readiness query and fails if the schema or embedder is missing. Per request: dense failure falls back to sparse, sparse failure to scoped lexical; isolation errors propagate. |
| `retrieval.dense.min-cosine` | `0.60` | 0–1 | Admission gate for dense-only evidence. Calibrated on the TASK-049 development fixture (v1 at 0.70 failed); not validated on unseen data. |
| `retrieval.graph.*` | on, depth 2, 0.8 / 0.5 | depth 0–4; candidate ≤ explicit | Graph expansion. Inferred edges never outweigh explicit ones. |
| `retrieval.fusion.*` | rrf-k 60, ×3 candidates, weights 1/1 | see yml | Recall-first weighted RRF. |
| `retrieval.default-k` / `max-k` | 8 / 50 | 1 ≤ default ≤ max ≤ 200 | |
| `coordination.enabled` | `true` | bool | Turns the `/plan /task /claim /join /approval` commands on or off. |
| `coordination.approval.allowed-acts` | `[BUTTON, COMMAND]` | non-empty subset | Which **explicit** acts count. There is never an implicit or text-based approval. |
| `coordination.tasks.max-per-plan` | `50` | 1–500 | |
| `telegram.enabled` | `false` | bool | When true, a valid token env var, a ≥ 32-character callback secret env var and a non-empty negative `allowed-chat-ids` list and a non-empty positive `allowed-author-ids` list are all required. **Committed value: `false`.** Live polling needs the user's approval (TASK-040). |
| `telegram.allowed-chat-ids` | `[]` (committed: `[-1001000000054]`, the SyntheticDemoGroup demo group) | negative ids | Only these **synthetic demo groups** are served (OPEN-6, C-1). After a supergroup migration, allow-list the new id too. |
| `telegram.allowed-author-ids` | `[]` (committed: `[1001]`) | positive Telegram user ids | TASK-040 / E-3. In the allowed chats, only these senders (`from.id`) are accepted, for messages, edits, commands and button presses. Everyone else is ignored **before any write or reply**: other people, bots (even with an allowed id), anonymous admins and posts sent as a channel (`sender_chat`), and senders without an id. Rejections are counted (`TelegramAdapter.rejectedAuthorCount()`) and logged without ids, names or text. An empty list accepts nobody. |
| `telegram.mode` | `LIVE` | `LIVE` \| `REPLAY` | TASK-051. `REPLAY` feeds `telegram.replay.file` (synthetic JSON Lines) through the real poller and adapter and never contacts Telegram (no token is read). Replayed messages are `SYNTHETIC`, in their own namespace (`telegram-replay`, ids `tgr…`), and are offered to extraction. `LIVE` messages are `PLATFORM` and are never offered to extraction (N-8). |
| `telegram.replay.file` / `.extraction-approver-ids` / `.barrier-timeout` | `""` / `[]` / `60s` | REPLAY only (refused in LIVE) | Approver ids must be allow-listed authors. Their `/extract` collects and approves pending batches (E-1, approval ref `telegram-replay:<chat>:m<msg>:by<id>`). A `{"replay":"await-idle"}` line holds the replay until extraction and projections have caught up. |
| `muse.transport` / `muse.recording-file` | `HTTP` / `""` | `HTTP` \| `RECORDED` | TASK-051. `RECORDED` answers from a recording file and never opens a connection. The API key is not read, and a request with no matching recording fails the batch. A recorded reply is **not** a live Muse integration. `HTTP` live calls still need explicit approval. |

## Storage backend (`dita.storage.*`, ADR-001 / DEC-020)

| Key | Default | Allowed | Meaning |
|---|---|---|---|
| `storage.backend` | `IN_MEMORY` | `IN_MEMORY`, `POSTGRES` | `IN_MEMORY`: in-process store, dispatcher and reference projections. **Not durable**: use it for offline development and unit-level startup tests only. `POSTGRES`: PostgreSQL truth plus the transactional outbox, a background `RelayWorker` driving the durable relay into the real Neo4j and Vespa projections, and Vespa + Neo4j retrieval. |
| `storage.postgres.url` | `jdbc:postgresql://127.0.0.1:55432/dita` | `jdbc:postgresql://127.0.0.1:5xxxx/<db>` | Loopback on a Dita host port only. |
| `storage.postgres.app-user` / `app-password-env-var` | `dita_app` / `DITA_APP_PASSWORD` | role name / env-var **name** | The non-owner, RLS-bound application role. The password is read from the named variable and is never a property or logged. |
| `storage.postgres.relay-user` / `relay-password-env-var` | `dita_relay` / `DITA_RELAY_PASSWORD` | role name / env-var name | A separate relay role (N-27), with its own 2-connection pool. |
| `storage.postgres.max-pool-size` | `5` | 1–20 | The application pool. |
| `storage.neo4j.url` / `user` / `password-env-var` | `http://127.0.0.1:57474` / `neo4j` / `DITA_NEO4J_PASSWORD` | loopback 5xxxx | `ensureSchema()` runs at startup, so startup fails if Neo4j is unreachable. |
| `storage.vespa.url` | `http://127.0.0.1:58080` | loopback 5xxxx | The application package must already be deployed (`scripts/vespa-verify.sh`). |
| `storage.relay-consumer-prefix` | `app` | `[a-z][a-z0-9-]{0,40}` | Durable cursor names `<prefix>-neo4j` and `<prefix>-vespa`. **Keep this stable.** A new prefix starts new cursors at the current end of the outbox, so data written earlier reaches the projections only through a rebuild or reconcile (N-64, not wired yet). |

Rules for `POSTGRES`:
- **The application never runs migrations.** Spring's JDBC, Flyway and SQL-init auto-configuration are excluded. Migrations V2–V4 are applied by `scripts/e2e-verify.sh` / `scripts/relay-verify.sh` as the schema owner.
- **Outbox ordering (F1/N-44).** Every outbox writer takes the transaction-scoped advisory lock `0x4449544100000001` before drawing an id and holds it until commit or rollback:
  - in `PostgresEventPublisher`, with a 10 s `lock_timeout` (N-63);
  - in the V4 statement-level trigger, which covers any other writer (N-61).

  So ids are allocated in commit-visibility order, and the relay cursor `id > last_id` cannot skip a row. This depends on `outbox_id_seq` keeping `CACHE 1` (N-62, checked by the verify script).
- **Cost and rule.** Outbox-writing transactions are serialized from their first `publish()` until commit. `publish()` must remain the **last** step of a unit of work: a write after it can deadlock. PostgreSQL detects that and rolls back one transaction atomically (see `AdvisoryLockSafetyTest`), but it is still a bug.
- **Telegram chats.** An accepted (allow-listed) chat is registered in `chat_group` as `PLATFORM` before its first message.

## Selective dependency invalidation (early deletion, and late if configured)

1. Every link whose evidence used the message is removed (`MessageLink.evidenceMessageIds`), and the affected messages' links are **recalculated** without it.
2. Knowledge loses that message's evidence records and any **provisional** evidence through invalidated links.
3. The claim survives iff ≥ 1 **independent VERIFIED** source remains. Independent means a distinct source message: several derived records from one message count once. Provisional evidence never counts.
4. Surviving wording or subjects that repeat deleted text are redacted (or the item is removed).

Late deletion in the default mode keeps the knowledge, marks the source `sourceDeleted`, and flags `onlyDeletedSourcesRemain()` when no live source is left.

## Non-configurable security invariants (`SecurityInvariants`)

Unknown keys fail startup, so none of these can be toggled:
- group isolation
- no private or foreign contexts
- verified platform identity and authorship
- server-side authorization
- provenance integrity, and no automatic promotion of inferred links
- no restoration of deleted text through edits, caches, indexes, projections or replays
- content is data, never instructions
- explicit approvals only, bound to a plan revision
- no purchases or bookings
- legal and platform requirements (real-group onboarding stays disabled until reviewed)

## Tests

`ConfigurationLoadingTest` (11 tests) covers:
- yml defaults equal binding defaults equal the core defaults
- valid overrides, and that they are audited
- unsafe or unsupported values fail startup
- invariant keys are rejected
- Telegram credential and allow-list rules
- secrets never appear in messages or `toString`
- the committed yml holds no credentials

`ApplicationStartupTest` boots the real application with the committed yml.
