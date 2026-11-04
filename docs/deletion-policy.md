# Message deletion and retention policy (DEC-012)

Status: **Product-owner decision (OPEN-5 closed, 2026-10-28).** Implemented in `dita-core` and the V1 schema. Conflicts are flagged in §5. None has been silently resolved.

## 1. Rules

| # | Trigger | Message text | Edit history text | Derived knowledge | Derived links |
|---|---|---|---|---|---|
| 1 | Deleted **≤ 5 min** after original creation (`EARLY_PURGE`) | removed | removed | **removed** if it depends on the message, including multi-source items | removed |
| 2 | Deleted **> 5 min** after creation (`LATE_TEXT_ONLY`) | removed | removed | **preserved**, marked `has_deleted_source`, with `source_deleted_at` set per source | preserved; the endpoint becomes a text-less tombstone |
| 3 | Dita removed from the group | — | — | — | Separate 21-day recoverable retention for the whole group (`GroupLifecycle`) |

Interpretation choices (routine, Fury; reversible):
- **Boundary:** "within five minutes" is inclusive. Exactly 5:00 counts as EARLY.
- **Clock:** "creation" is the platform's original send time (`sent_at`); edits do not reset it. The deletion instant is when Dita learns of the deletion (platform event time or `/forget` command time). It is never back-dated.
- **Multi-source knowledge** in EARLY mode is removed entirely, not partially re-derived. This is the conservative reading of "dependent on it".
- **Retrieval:** a deleted message is never retrieved or cited in either mode, and no new knowledge may be derived from it.
- **Re-deleting** is a no-op.

## 2. Persistence contract

- `MessageStore.delete(scope, id, at, mode, source)` applies the classified deletion **atomically**. In Postgres this is one transaction:
  - message
  - `message_event` text nulling
  - `message_link` delete (EARLY)
  - `knowledge_item` delete (EARLY) or flag (LATE)
  - `knowledge_source.source_deleted_at`
  - the outbox row
- `DeletionService` owns the policy (`DeletionPolicy.classify`). Callers never choose the mode.
- Schema (`db/migration/V1__core.sql`):
  - columns `message.deletion_mode` and `deletion_source`, `knowledge_item.has_deleted_source` and `contains_source_text`, `knowledge_source.source_deleted_at`
  - a CHECK makes an EARLY_PURGE impossible more than 5 minutes after `sent_at`
- **Event:** outbox topic `message.deleted` carries `DeletionEvent`, which is **ids only, never text**.

## 3. Downstream projections

Executable references are `InMemoryGraphProjection` and `InMemorySearchIndex`. The real adapters must pass the same tests.

**Neo4j:**
- Both modes: `REMOVE m.text`, then `SET m.deleted=true, m.deletionMode=…`. The node stays as a tombstone so other members' reply structure survives.
- EARLY: delete the listed `LINK` edges and `DETACH DELETE` the listed `Knowledge` nodes.
- LATE: keep them and `SET k.sourceDeleted=true`.

**Vespa:**
- Both modes: delete the message document (text, tokens and embedding).
- EARLY: delete the listed knowledge documents.
- LATE: partial update with `source_deleted=true`, plus `needs_review=true` for items flagged under C-3.

Projections are idempotent and filter by `groupId`. A full rebuild from Postgres reproduces the same state, because the truth tables already reflect the deletion.

## 4. Telegram capabilities and fallback

**Verification status: NOT VERIFIED.** This session has no web tool, and the proxy denies `core.telegram.org` and `telegram.org`. My unverified recollection, which the code does *not* rely on:
- The Bot API `Update` object has no deletion event for ordinary group or supergroup messages.
- `deleted_business_messages` exists only for Telegram Business connections, which doesn't cover groups.

To verify, check the `Update` object at https://core.telegram.org/bots/api#update and the Bot Platform Developer Terms at https://telegram.org/tos/bot-developers. Either allow those domains for this session or paste or save the pages into the project.

The design therefore treats platform deletion events as **unavailable unless verified** and provides a fallback:

**`/forget`, sent as a reply to the message to be forgotten** (Telegram adapter, TASK-022):
- Only the message's author may use it (`requester == author`). Whether admins may also forget others' messages is OPEN-10.
- Timing is taken from when the command arrives. So `/forget` within 5 minutes of sending gives EARLY_PURGE; otherwise LATE_TEXT_ONLY.
- Dita confirms with a short reply that does not quote the forgotten text. The `/forget` command message itself is never ingested as memory.
- **Usage guidance for members:** send `/forget` *before* deleting the message in Telegram, because a deleted message can't be replied to.
- If Telegram verifiably delivers deletions on some surface, that adapter emits `DeletionSource.PLATFORM_EVENT` into the same `DeletionService`.

## 5. Flagged conflicts and risks (not legal advice; need product owner and/or counsel)

| ID | Conflict | Current behaviour | Decision needed |
|---|---|---|---|
| C-1 Legal | Right-to-erasure regimes may require erasing derived personal data on a data-subject request, which rule 2 preserves. Examples: GDPR Art. 17 if EU residents take part; Israel's Privacy Protection Law and its 2025 amendments (from memory, unverified). Preserved knowledge is still attributed to the author (e.g. "advises against Dr. Levi"). | Rule 2 implemented as written. There is **no** erasure override. | Do we need an explicit "erase everything about my messages" request path that overrides rule 2? Owner: PO + counsel. |
| C-2 Platform | Telegram's Bot Platform Developer Terms may impose data-handling or deletion obligations. Not verified (blocked). | None assumed. | Verify the terms (see §4). |
| C-3 Consistency | Preserved knowledge whose statement repeats the deleted text keeps that text in substance. | Preserved per rule 2, flagged `contains_source_text` / `needs_review`, and listed in the event. | Keep, redact the statement, or remove? Owner: PO. |
| C-4 Platform mechanics | If Telegram sends no group deletions, a message deleted in the Telegram client stays in Dita's memory unless `/forget` is used, and it can't be `/forget`-ed after deletion. Rule 1 is then effectively reachable only through `/forget` within 5 minutes. | `/forget` fallback; members are told "forget before deleting". | Accept this UX for the demo, or require admin-level removal tooling? Owner: PO. |
| C-5 Privacy mode | A memory bot must read all group messages, which means privacy mode **off** (or admin rights). This conflicts with Natasha N-8's "privacy mode on" mitigation for real data. | Synthetic-only demo default. | Natasha + PO, for any real-data demo (OPEN-6). |
| C-6 Model egress | Text sent to Muse before deletion can't be recalled from the provider. Provider retention terms are unverified. | Muse disabled by default; extractive answers. | Verify Meta Model API data retention before any live call on PLATFORM text (G4). |
| C-7 UX | Preserved knowledge can't be cited verbatim once its source is deleted. Answers will need a "source message deleted" citation without a quote. | Knowledge is not yet used in answers. | Approve that wording when knowledge retrieval lands. |
| C-8 UX/Policy | While Dita is removed (the 21-day window), members can't use `/forget`, so their deletions in that window are invisible to Dita. | Not handled. | Purge-on-request via an admin channel, or accept? Owner: PO. |
| C-9 Storage | Deleted text survives in Postgres dead tuples, WAL and backups until vacuum and backup expiry. | Not handled (demo has no backups). | Real-data demo: VACUUM after a purge and set a backup retention period. Owner: Natasha + DevOps. |
| C-10 Legal | Third-party and health data (e.g. children's doctors, a child's condition), plus the preserved author attribution in rule-2 knowledge. | Synthetic-only demo. | Counsel before any real-data use. |
| C-11 Consistency | Dita's own earlier replies in the chat may quote text that is later deleted. Dita can't unsend them unless it deletes its own messages. | Not handled. | Should Dita delete or edit its own replies that quote a deleted message? Owner: PO. |

**Natasha's gating assessment (TASK-024):**
- None of C-1 to C-11 blocks a **synthetic-only** demo, provided Muse stays off and Telegram uses only synthetic test groups.
- A **real-data** demo is blocked by C-1, C-2, C-5, C-6, C-9 and C-10. C-4 and C-8 are UX decisions, and C-3 needs a PO decision.

## 6. Review status (Natasha N-11 to N-17, 2026-10-28)
- Fixed in core and tested:
  - N-11 coordination items follow the policy
  - N-12 overlap is checked against all edit versions; the detector is documented as a heuristic
  - N-14 clock skew is clamped
  - N-16 projections check group scope and are replay-safe
  - N-17 `toString` redacts text
- Fixed in DDL, unexecuted: N-13 (an exact mode/window CHECK, `deleted_at >= sent_at`, `deletion_recorded_at`, and a trigger that forbids event text after deletion).
- Open, owned by the Telegram adapter (TASK-022):
  - N-14 timing: use the `/forget` message's own `date`
  - N-15 identity = (from.id, sender_chat.id): refuse anonymous-admin and sender_chat senders, ignore `/forget` in edited messages, never ingest or log the `reply_to_message` text

