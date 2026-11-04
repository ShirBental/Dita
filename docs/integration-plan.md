> **Historical (TASK-055, 2026-11-04).** Kept as evidence; do not follow as current instructions. Current status: [capability-status.md](capability-status.md); index: [README.md](README.md).

# Integration plan (rev. 2026-10-28) → demo 2026-11-04

Primary risk: integration, not design. Each step names its gate, and none is called done without an executed test.

| # | Deliverable | Owner | Depends on | Target | Status / evidence |
|---|---|---|---|---|---|
| 1 | Core security corrections N-1…N-26 | Fury + Natasha | — | Oct 28 | **Core done**: 53 tests pass. Open: N-22 (forward/quote independence), N-25 (`delete()` caller restriction). |
| 2 | V1 schema executed on a real Postgres 16 | Human runs `docker compose`, Shuri runs tests | container runtime (BLK-2) or local Postgres (BLK-1) | **Oct 29** | Written, **never executed** |
| 3 | `PostgresMessageStore` contract + RLS isolation tests green against the DB | Shuri | 2 | Oct 29 | Written; 25 tests SKIPPED (no DB) |
| 4 | Telegram adapter live in an allow-listed synthetic group | Fury | BotFather token + callback secret in env (BLK-4) | Oct 30 | Adapter + 14 tests on synthetic updates. **Never run against the live API**. Bot API capabilities unverified. |
| 5 | End-to-end slice: Telegram → Postgres → outbox → projections → `/ask` with citations; `/forget` both modes | Fury + Shuri | 3, 4 | Oct 30–31 | In-process slice runs (ApplicationStartupTest) |
| 6 | Muse tier-1 adjudication + answer phrasing behind the verifier | Fury | G4 (docs, key, credits, per-call approval) | Nov 1 | Blocked. Config rejects `META_MODEL_API` until then. |
| 7 | Neo4j + Vespa adapters behind `ProjectionListener` (must pass the reference projection tests) | Shuri (Neo4j), Fury (Vespa) | 2 (compose) | Nov 1–2 | Reference projections only |
| 8 | Bruce's benchmark: lexical vs graph vs hybrid (+ vector when 7 lands) | Bruce + Shuri | 5 (7 for vector) | Nov 2 | Data v0 (25 msgs, 12 Qs); harness not built |
| 9 | Birthday coordination demo in Telegram (`/plan /task /claim /join /approval` + signed buttons) | Fury | 4 | Nov 2 | Implemented and tested on synthetic updates |
| 10 | Natasha release sign-off, then rehearse demos A and B, freeze | Natasha, Peter | all | Nov 3 | — |

**Fallback rule.** If step 2 is not green by **Oct 29 end of day**, the demo runs on the in-memory store, clearly labelled "prototype store". Neo4j and Vespa run as their reference projections. Both are already wired and tested. This is an escalation to the PO, not a silent change.
