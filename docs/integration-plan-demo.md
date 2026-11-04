> **Historical (TASK-055, 2026-11-04).** Kept as evidence; do not follow as current instructions. Current status: [capability-status.md](capability-status.md); index: [README.md](README.md).

# DEMO-INT: Telegram → persistent memory → Muse extraction → grounded answer

**Status:** PLAN (2026-10-29). Nothing implemented yet.

**Authority:**
- DEC-024 (PO): wire the real stack behind a profile; a synthetic demo group; Kafka not blocking; RFC-002 preserved; deterministic answers first.
- DEC-025: RFC-002 reconciliation.
- DEC-026: NATASHA-EGRESS-001 conditions.

**Baseline:** [audit 2026-10-29](audit/2026-10-29-muse-integration-audit.md).

**Ownership and locks:** [.avengers/ownership.md](../.avengers/ownership.md). Shuri is the single writer of `db/migration/`; Fury the single writer of `decisions.md`. DEMO-INT does not touch `dita-eval/`, `eval/value-benchmark/` or `prototypes/kafka-spike/` (TASK-034/035).

## Demo story (what must work on Nov 4)

1. Team members type synthetic messages in the allow-listed demo group "[SYNTHETIC DEMO] Dita parents". They are stored in Postgres and projected to Neo4j/Vespa.
2. `/ask` answers immediately from **messages** (deterministic, verbatim, already working).
3. The operator runs `scripts/muse-extract-batch.sh`. It shows the batch ids, model, token and cost estimate, then asks for `approve` (E-1). Muse proposes; Dita promotes per RFC-002; accepted items are stored with speaker plus verbatim spans.
4. `/ask who recommended Dr. Levi?` returns a templated answer, e.g. "Yossi recommended … "quote" [msg]; Ron warned — for teenagers — "quote" [msg]; conflicting/qualified flags", re-verified against Postgres.
5. `/forget` on a source message: the answer changes and the item disappears, or survives on independent support, across Postgres, Neo4j and Vespa.

## Work packages

| WP | Owner | Change (minimum; no redesign) | Depends on | Acceptance test (AT) |
|---|---|---|---|---|
| **WP1** wire the real stack | **Shuri** | `dita-app`: add the `dita-store-postgres` + `dita-projections` dependencies; profile `postgres` with beans for `PostgresMessageStore`, `PostgresEventPublisher` (IngestionPipeline + DeletionService), `PostgresOutboxRelay` + `Neo4jProjection` + `VespaProjection` consumers, and a relay worker; datasource/relay/Neo4j credentials from env; the in-memory default is unchanged | DEC-024.1 | **AT-1** |
| **WP2** relay ordering fix | Shuri | ADR-001 F1/N-44 option B (advisory lock in `publish`) | **ADR-001 Q2 approval** | **AT-2** |
| **WP3** V5 migration (RFC-002 minimum subset) | **Shuri** (single writer) | `knowledge_item`: `status`, `certainty`, `speaker_id`, `attributed_to`, `subject_id`, `predicate`, `proposal_version`; `knowledge_source`: `role`, `origin_key`, NOT NULL spans; `message`: origin fields; `knowledge_entity`; drop `superseded_by` (FK bug) + `confidence`; DB guard ≥ 1 DIRECT source. **Deferred:** `knowledge_relation` (relations are computed at query time for the demo) | DEC-022/025 | **AT-3** |
| **WP4** core model v2 + promotion | **Fury** | `KnowledgeItem` v2 fields; `Evidence` spans/role/originKey; promotion rules 1–7 (RFC-002 §3.4: speaker from metadata, stance/negation, subject surface, status guards, hearsay, qualifier spans); templated display; the gloss is never shown | WP3 contract | **AT-4** |
| **WP5** synthetic demo chat classification | **Fury** | `dita.telegram.synthetic-demo-chats` (chat-id, `allowed-author-ids`, title marker, approval-ref) with startup validation; SYNTHETIC only if E-2/E-3/E-5 hold; never upgraded on re-ingest (E-8); minimal `forward_origin`/`quote` parsing (D-2) | NATASHA-EGRESS-001 | **AT-5** |
| **WP6** approved batch extraction | **Fury** | `scripts/muse-extract-batch.sh` + `MuseBatchRunner`: a selected batch of one demo chat; mandatory member-count check (E-7); pseudonyms (E-6); persisted daily cap (E-8); typed approval with the batch ids, model and cost (E-1); id/count/cost-only logs (E-11); accepted items via `store.addKnowledge` in one transaction with outbox events | WP1, WP4, WP5 | **AT-6** |
| **WP7** knowledge-aware deterministic answer | **Fury** | `QueryService`: subject match → the group's stored items (from Postgres, no projection needed); templated answer = speaker + status + qualifiers + verbatim span + msg id; conflicting/qualified/superseded/source-deleted flags computed at query time; `AnswerVerifier` re-checks every quote; SOURCE_DELETED-only items excluded (DEC-025 D-1 interim) | WP4 | **AT-7** |
| **WP8** knowledge projections | Shuri | Neo4j `onKnowledge` test; Vespa knowledge docs keyed `(groupId, id)` with template text (K-7); `ProjectionSource.knowledge(groupId)` for reconcile | WP3, WP4 | **AT-8** |
| **WP9** scorer + recorded run | **Bruce** (spec), Shuri (code, inside TASK-034's `dita-eval` by agreement) | Score extraction against `parents-c` gold with the hard gates; first run uses a **recorded** response | WP4 | **AT-9** |
| **WP10** reviews and live runs | **Natasha**, user | Natasha implementation review of WP5/WP6 → unblocks live egress; one approved scored Muse run on parents-c (D-4); Telegram token + live demo-group run | WP1–WP9 | **AT-10, AT-11** |
| WP11 demo script and rehearsal | **Peter** | Demo A/B scripts against AT-7/AT-6 outputs; fallback recording | AT-7 | rehearsal ×2 |

## Acceptance tests

All synthetic. AT-1 to AT-9 involve no live calls.

| AT | Test | Pass condition |
|---|---|---|
| AT-1 | `PostgresProfileStartupTest` (gated on the 3 services) | The app boots with profile `postgres`; a synthetic `IngestionPipeline` message lands in PG + outbox; the relay delivers to Neo4j + Vespa; the existing EndToEndSliceTest 6/6 still passes |
| AT-2 | `RelayOrderingTest` (gated) | Two concurrent publishes committing out of order: both delivered; a deletion is never skipped |
| AT-3 | V5 applied on the real DB + extended store contract | All 24 existing contract tests + new knowledge-v2 tests pass; RLS 1/1; the direct-SQL attempt to leave an item without a DIRECT source is rejected |
| AT-4 | `PromotionRulesTest` (offline) | Per rule: accepted vs rejected examples from parents-c: speaker from metadata; "I would NOT say … excellent" rejected as +1; a "Levi's bakery" subject not merged; an offer is not a commitment; hearsay needs attributed_to in the span |
| AT-5 | `SyntheticDemoChatTest` (offline, synthetic updates) | SYNTHETIC only for an allow-listed chat + allowed author + marker + not a forward/quote/edit. Edit → PLATFORM permanently. Unknown author → PLATFORM. A migrated chat id → PLATFORM until re-approved |
| AT-6 | `MuseBatchRunnerTest` (offline, **recorded** response) | No call without typed approval; member-count failure blocks; pseudonyms only in the request; the daily cap persists across restart; accepted items persisted with spans + speaker; logs contain no text |
| AT-7 | `KnowledgeAnswerTest` (gated) | "who recommended Dr. Levi?" → each named speaker with a verbatim quote and msg id; the qualification is shown as "for teenagers", not as a contradiction; no model wording in the answer; no other group's items |
| AT-8 | `KnowledgeProjectionTest` (gated) | Items in Neo4j/Vespa match PG; reconcile repairs a dropped event; a rebuild from PG yields no deleted text |
| AT-9 | `ExtractionScoreTest` (offline, recorded) | The scorer computes all RFC-002 §6 metrics; hard gates are evaluated; the report has no free text |
| **AT-E2E** | `TelegramToKnowledgeAnswerTest` (gated; the single proof of integration) | Synthetic Telegram updates → adapter on the `postgres` profile → relay → approved batch with a recorded Muse response → persisted items → `/ask` cites them → `/forget` of one source → the item is removed, or survives on independent support, in PG/Neo4j/Vespa, and the answer changes accordingly |
| AT-10 | Live, approved: one scored Muse run on parents-c | Hard gates pass; cost and tokens reported |
| AT-11 | Live, approved: the Telegram demo group | Demo story steps 1–5 work end to end |

**End-to-end Muse integration may be claimed only when AT-E2E passes on the real stack.**

## Shortest path

`WP1 → (WP2 if Q2 approved) → WP3 → WP4 → WP5 → WP6 → WP7 → AT-E2E → Natasha implementation review → AT-10/AT-11`.

WP8 and WP9 run in parallel after WP4.

| Day | Outcome |
|---|---|
| Oct 29 | WP1 + AT-1; WP3 V5 drafted (Fury review); WP4 started |
| Oct 30 | WP3 applied + AT-3; WP4 + AT-4; WP5 + AT-5 |
| Oct 31 | WP6 + AT-6; WP7 + AT-7; **AT-E2E green on the real stack**; WP9 + AT-9 |
| Nov 1 | Natasha implementation review; WP8 + AT-8; AT-10 (approved); Telegram token → AT-11 |
| Nov 2 | Demo B pass (approvals already implemented); fixes |
| Nov 3 | Rehearsals ×2, fallback recording, freeze |
| Nov 4 | Demo |

## Decisions still needed (PO)

- **ADR-001 Q2:** approve relay fix option B (needed before the demo moment "delete → answer changes", to remove the skip risk).
- **RFC-002 D-1:** confirm the interim deletion default (DEC-025).
- **D-4:** live budget for AT-10 + AT-11 (suggested ≤ $0.30, plus demo-day batches at about $0.013 per 10 messages).
- **Telegram:** a bot token, a supergroup created with change_info restricted to admins and joining other groups disabled (E-4, E-9, E-10), and the list of team member ids for `allowed-author-ids` (E-3).
