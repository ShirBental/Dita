# RFC-001 — Dita Prototype Architecture

Status: **Accepted for implementation (Fury, routine delegation)** · Review pending: Tony (architecture), Natasha (security/privacy, release-blocking)
Date: 2026-10-27 · Deadline: 2026-11-04 (Meta Global AI Developer Hackathon)
Scope: engineering prototype. Not production-scale.

## 1. Goals and non-goals

Goals: the ten functional requirements in the brief, demo scenarios A (parents' group memory) and B (birthday coordination), and a benchmark that compares Vector RAG, GraphRAG and hybrid retrieval.

Non-goals: production scale or high availability; WhatsApp (kept possible through the platform port only); purchases or bookings (never); conversation summaries unless a user asks for one.

## 2. Settled principles (not reopened)

- PostgreSQL is the source of truth. Every derived artefact carries provenance back to message IDs.
- Message = storage unit. Conversation = dynamic graph projection. Knowledge = retrieval unit.
- Explicit and inferred links are distinct types. Inferred links are **candidates** and are never promoted to facts automatically.
- Deterministic evidence comes first. An LLM (Muse) adjudicates only where rules are insufficient.
- Retrieval favours recall over speed and is hybrid and graph-aware. Answers are verified against the original messages.
- Strict per-group isolation. Content can never override permissions. Config changes need unanimous approval from the current admins. Removing Dita starts a recoverable 21-day retention period.

## 3. Component view (hexagonal)

```
 Platform adapters ──► Ingestion ──► PostgreSQL (truth) ──outbox──► Processing workers
 (Telegram, Synthetic)    │                                         │  link inference (rules → Muse)
                          │                                         │  knowledge extraction
                          ▼                                         ▼
                    Orchestration ◄── Retrieval ◄── Graph projection (Neo4j) + Index (Vespa)
                    (Q&A, coordination,  │ verifier
                     approvals, config)  ▼
                                    Grounded answer + citations ──► Platform adapter
```

| Module | Responsibility | Owns |
|---|---|---|
| `dita-core` | Domain model, ports, deterministic logic (linking, scoring, verification, coordination state machines). No framework. | Shuri, reviewed by Tony |
| `dita-store-postgres` | Messages, events, membership, permissions, links, knowledge, outbox. Flyway migrations. | Data workstream |
| `dita-graph-neo4j` | Conversation graph projection and graph expansion queries. Rebuildable from Postgres. | Graph workstream |
| `dita-retrieval-vespa` | Hybrid BM25 + vector + graph-feature ranking. Rebuildable from Postgres. | Retrieval workstream |
| `dita-muse` | LLM port implementation: link adjudication, extraction, answer phrasing. Budget-guarded. | Muse workstream |
| `dita-telegram` | Long-polling bot adapter mapped to the platform-neutral `PlatformEvent`. | Telegram workstream |
| `dita-app` | Spring Boot wiring, REST/admin API, workers. | Integration |
| `dita-eval` | Benchmark dataset, harness, metrics. | Bruce + Shuri |

## 4. Technology responsibilities, value and fallback

| Tech | Responsibility | Measurable value | Fallback (same port) |
|---|---|---|---|
| PostgreSQL 16 | Source of truth, outbox, audit | Zero lost or rewritten messages; rebuild-from-truth test | None (mandatory) |
| Neo4j | Conversation projection; k-hop expansion with weighted edges | Context-reconstruction score versus a no-graph baseline | `links` table plus recursive CTE in Postgres |
| Vespa | Hybrid lexical + vector + graph-feature ranking, Hebrew/English | Recall@K and nDCG versus Postgres FTS | Postgres FTS + `pg_trgm` (+ `pgvector` if available) |
| Meta Muse | Ambiguous link adjudication, extraction, answer phrasing | Faithfulness and attribution gains per $ versus rules-only | Deterministic rules plus extractive answers (always available) |
| Kafka-compatible bus | Async ingestion → processing fan-out | Replayability and decoupling | **Postgres transactional outbox + in-process workers (DEC-004, default for the demo)**; Redpanda behind the same `EventBus` port if a container runtime exists |
| Telegram Bot API | Live demo adapter (long polling, so no public webhook is needed) | Live end-to-end demo | Synthetic adapter and a CLI replay |
| Spring Boot 3.5 / Java 21 | Services | — | — |

## 5. Data model (summary; DDL in `db/migration/V1__core.sql`)

- `chat_group(id, platform, platform_chat_id, source_kind{SYNTHETIC|PLATFORM}, status{ACTIVE|REMOVED_RETENTION|PURGED}, removed_at, purge_after)`
- `member(group_id, member_id, display_name, role{MEMBER|ADMIN}, joined_at, left_at)`
- `message(id, group_id, platform_message_id, author_id, sent_at, reply_to_message_id, source_kind, current_text, lang, deleted_at)`. Message rows are immutable except for the current-version pointer and the deletion marker.
- `message_event(id, group_id, message_id, kind{CREATED|EDITED|DELETED}, payload, occurred_at)`. Append-only history. Deletion follows the agreed retention behaviour (see §7).
- `message_link(id, group_id, from_msg, to_msg, kind{EXPLICIT_REPLY|INFERRED_REFERENCE|INFERRED_CONTINUATION|INFERRED_CORRECTION}, status{FACT|CANDIDATE|CONFIRMED|REJECTED}, score, evidence jsonb, method{PLATFORM|RULE|LLM|HUMAN}, model_version)`. Only `PLATFORM` (explicit reply) or `HUMAN` may produce FACT or CONFIRMED.
- `knowledge_item(id, group_id, kind{RECOMMENDATION|CLAIM|DECISION|COMMITMENT|QUESTION|CORRECTION}, subject, statement, polarity, qualifiers, status{EXPLICIT|INFERRED|SUPERSEDED|RETRACTED}, confidence, method)` with `knowledge_source(knowledge_id, message_id, span_start, span_end)`
- Coordination: `plan`, `proposal`, `task(assignee, status)`, `approval(plan_id, member_id, decision, message_id)`. Approval needs an explicit act by that member, never an inferred preference.
- Config: `group_config`, `config_change_request`, `config_approval`. A change is applied only when every current admin has approved.
- `outbox(id, group_id, topic, payload, created_at, processed_at)`

The isolation rule: every table carries `group_id`; every port method takes a `GroupScope`; every retrieval result and citation is re-checked against the scope before it is returned (defence in depth). Postgres row-level security comes later (Natasha to rule).

## 6. Key contracts (ports in `dita-core`)

- `PlatformEvent` (inbound, platform-neutral): MessageCreated, MessageEdited, MessageDeleted, MemberChanged, BotRemoved, ButtonPressed.
- `MessageStore`: append(event), find(scope, ids), window(scope, before, limit), all(scope).
- `LinkInferencer`: `List<MessageLink> infer(scope, Message, List<Message> window)`. Deterministic `RuleLinkInferencer` emits an evidence vector `{temporal, lexical, addressing, qaPattern, anaphora}`.
- `Retriever`: `List<Hit> retrieve(scope, Query, k)`. Implementations: Lexical, Vector, Graph-expanded, Hybrid (reciprocal rank fusion).
- `AnswerComposer` → `GroundedAnswer{text, citations[{messageId, quote}], evidenceType{EXPLICIT|INFERRED}, unanswered}`.
- `AnswerVerifier`: every citation must exist in-scope, and its quote must be a verbatim substring of the original message's current text; otherwise it is dropped. An answer with no surviving citation becomes "I couldn't find this in the group's messages."
- `LlmPort`: `complete(purpose, prompt, budget)`. Disabled by default; a live call needs human approval (DEC-003).

## 7. Privacy and safety rules (Natasha to review)

1. One `GroupScope` per request, derived from the platform chat ID and never from message content.
2. Prompt-injection posture: message text is data; tool and permission decisions never read message text.
3. Preferences ≠ authorization. Only an explicit approval act (button press, or an explicit command by that member) counts.
4. No purchases or bookings. There is no tool for it, by design.
5. Bot removal sets `REMOVED_RETENTION` and `purge_after = removed_at + 21 days`. Re-adding within the window restores the group; after the window a purge job deletes the data (needs approval to run against real data).
6. Message deletion follows **DEC-012** (product-owner decision; full spec in `docs/deletion-policy.md`):
   - Deleted ≤ 5 min after creation: purge the text and all dependent knowledge and links.
   - Deleted later: purge the text, then preserve and flag the derived knowledge.
   - Telegram deletion events are not assumed; the `/forget` reply command is the fallback.
   - Conflicts C-1 to C-7 are flagged in §5 of that document.
7. Synthetic data is labelled `source_kind=SYNTHETIC` per group. Synthetic and platform messages never share a group.

## 8. Processing tiers

- T0: deterministic. Explicit replies, rule-based links, keyword or pattern extraction (EN/HE), lexical index. Always on, zero cost.
- T1: embeddings plus a cheap model for ambiguous candidates (score in the grey zone, 0.35–0.65).
- T2: Muse large model, only for answer phrasing and contested extractions, within a budget guard.

## 9. Deployment recommendation

Demo: a single host running `docker compose` (Postgres, Neo4j, Vespa, app). Telegram long polling needs no inbound port. If a hosted demo is needed, use one small cloud VM (deploy is a reserved action needing human approval). No managed Kafka.

## 10. Open engineering decisions

Listed in `docs/plan.md` §4 with owners and deadlines.

## 11. Revision 2 (2026-10-28): configuration, invalidation, propagation, Telegram

- **Modules:** `dita-core` (framework-free), `dita-store-postgres` (Shuri, TASK-016), `dita-telegram`, `dita-app` (Spring Boot 3.5.16).
- **Configuration:** a single system-administrator `application.yml` under `dita.*`, bound to typed `DitaProperties`. It is validated at startup, unknown keys are rejected, and changes need a restart. There is no policy engine, per-group config or config database. Security invariants are code, not config. See `docs/configuration.md`.
- **MessageStore** is a primitive port with `atomically(scope, work)`: all-or-nothing per group (Postgres transaction; the in-memory reference rolls back). The policy lives in services (`DeletionService`).
- **Knowledge provenance:** `Evidence{messageId, VERIFIED|PROVISIONAL, viaLinkKey, sourceDeleted}`. Support is the set of distinct VERIFIED source messages. Links record `evidenceMessageIds` so deletion invalidates exactly the dependent links and recalculates them.
- **Propagation:** no distributed transactions.
  - The truth change and the outbox row commit together.
  - The `EventDispatcher` relay is at-least-once, with per-consumer cursors, exponential backoff and dead letters.
  - Payloads are ids only (`Refs.*`, `DeletionEvent`).
  - Projections re-read truth, and a missing truth row means removal.
  - Deletions are never skipped: the consumer is blocked and an alert raised.
  - A `reconcile` sweep repairs drift.
  - The Neo4j/Vespa reference projections (`InMemoryGraphProjection`, `InMemorySearchIndex`) define the behaviour the real adapters must pass.
- **Telegram:** long polling, with scope only from chat.id through `GroupDirectory` plus an allow-list of synthetic demo chats.
  - Identity comes from `from.id`; anonymous and `sender_chat` senders count as proxies.
  - Commands are not memory and are ignored when edited.
  - `/forget` checks the reply target in this group and uses the command's own date.
  - Approval buttons are HMAC-signed and bound to chat, plan and revision.
  - Replies are sent without parse_mode, and the token is redacted from errors.
  - Platform deletion events are not assumed (unverified).
