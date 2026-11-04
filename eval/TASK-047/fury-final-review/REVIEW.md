# TASK-047 — Independent final review of the Codex F2/F3 freeze (Nick Fury, 2026-11-02)

Verdict: **APPROVED WITH CONDITIONS**. No new HIGH findings. The semantic evaluation may resume. The conditions
apply only to a future V6 application on a real database (that already needs separate human approval).

## 1. Frozen revision: VERIFIED
- `ARTIFACTS.sha256`: all 13 artifacts OK. Patch sha256 `b121c99a…646c1f` matches.
- `scripts/task046-freeze.sh` on the live tree gives `TREE 771f14662fabf2cdbaee00ef1e0cc4c08a9903d6faf1386bc2ee55057756ff86 (291 files)`.
  It is byte-identical to `manifest.sha256` (`cmp`).
- `changed-files.sha256`: all 7 OK.
- Baseline vs new manifest diff: exactly 4 modified sources, 2 new tests, nothing else.
- Reverse check (copy only): `patch -R -p1` restores all 4 modified sources to their baseline hashes and removes the 2 new tests.

## 2. Independent findings
| ID | Sev | Finding |
|---|---|---|
| R47-1 | MEDIUM (V6 apply-time only) | `V6__validity_unresolved.sql` inserts into `outbox` without `pg_advisory_xact_lock(4920556706028584961)` (= `PostgresEventPublisher.OUTBOX_ORDER_LOCK`). It is the only outbox INSERT outside `publish()`, which states "Every outbox INSERT must go through publish()". `IngestionPipeline.ingest` publishes `message.created` without touching `knowledge_item`, so it is not blocked by the migration's ACCESS EXCLUSIVE lock. If it draws a higher outbox id and commits first, the relay advances `last_id` past the migration's ids. The migration's `knowledge.changed` events would then be skipped forever: the same hazard V4 fixed (`LegacyUnlockedPublisher`). Truth stays correct and both states are non-current, so no stale answer is expected. The projection's validity can drift. Fix: take that lock immediately before the INSERT, or apply V6 only in a writer-free maintenance window. NOT reproduced on PostgreSQL (sandbox). The finding is from code reading. |
| R47-2 | LOW | Every PG store call, reads included, takes the exclusive group advisory lock. Same-group reads serialize, and a long writer can make `ask` throw after `lock_timeout` 10 s. The exception means no answer is given (fail-closed), not a graceful "not found". Throughput only; not measured. |
| R47-3 | LOW / product | The conservative V5→V6 conversion makes every legacy SUPERSEDED row UNRESOLVED. Legacy false relations (e.g. the picnic/entrance case) can then never be repaired by rejection and need a new claim. This is deliberate and documented; it needs product acceptance (Peter), not a code change. |
| R47-4 | INFO | The in-memory store's monitor covers the whole store, so different groups serialize in memory. My test confirmed there is no data mixing or wrong answer. PG uses per-group keys. |
| R47-5 | INFO | Codex's `arm-regression.txt` reports 3 F4 tests. `Task047ArmBaselineTest` has 4, and all 4 pass here. It is only a count discrepancy in the evidence. |

## 3. F2 / F3 results
F2: final answer consistency
- Same-group isolation: VERIFIED in-memory. With the gate paused between the verifier and the evidence view, an edit, a deletion or a new correction from another thread is strictly blocked (400 ms), the answer is valid at its serialization point, and the next answer reflects the change. PG: VERIFIED by code reading only. The lock is taken in the outer unit before any read, and nested units join.
- Edits/deletions during composition: VERIFIED. When the cited correction is withdrawn before the gate, the retry never revives 7 PM. This holds for full and limited history, within 2 attempts or fewer.
- Citation validity at return: VERIFIED in-memory. The gate re-verifies each citation and checks the current evidence view and ACTIVE status.
- Bounded retries: VERIFIED. Codex's tests and mine both show at most 2 compositions, then fail-closed.
- Independence across groups: PG NOT TESTED (sandbox). In-memory it is serialized but correct (R47-4).
- Lock ordering/deadlocks: VERIFIED by code reading.
  - The group lock is always taken first in an outer unit, and cross-group nesting raises `IsolationViolation`.
  - The global outbox lock comes after it.
  - The relay uses a separate DataSource and takes no group lock.
  - The model proposer runs outside any unit.
  - No cycle was found. Not exercised on PG.

F3: withdrawal provenance
- State machine: VERIFIED exhaustively over all 48 inputs.
  - UNRESOLVED is sticky.
  - A live relation never yields ACTIVE.
  - Non-ACTIVE→ACTIVE happens only on a pure rejection on V6.
  - Withdrawn+SUPERSEDED on V6→UNRESOLVED.
- Chained corrections (7→8→9 PM): VERIFIED.
  - Withdrawing the latest revives neither older claim.
  - Withdrawing the middle and then rejecting every remaining edge keeps the target UNRESOLVED.
- Fan-in, in both orders (reject the false edge then withdraw the genuine one; Codex covers the reverse): VERIFIED.
- Edit-withdrawal, then reconfirmation with identical wording in a new message: VERIFIED.
  - The new claim is answerable and cites only the new message; the old claim stays UNRESOLVED.
  - A later false relation on the reconfirmation is repairable.
- Legacy history preservation, migration atomicity, projection events and rollback: NOT TESTED by me (PostgreSQL unavailable).
  - Codex's evidence (`postgres-V5/V6.txt`, `adversarial-atomicity.txt`) and the SQL read correctly.
  - Exception: R47-1.

## 4. Regressions (F1/F4/F5): VERIFIED
- Their sources are unchanged: the manifest diff shows only the 7 files, and the QueryService change is confined to `ask()`.
- In my scratch copy, these all pass:
  - `Task047CodexReviewTest`: 14/14 (F1, F2, F3, F5).
  - `Task047ArmBaselineTest` (F4, `-P semeval`, non-scored): 4/4.
  - `FinalAnswerIsolationTest`: 5/5.

## 5. Test runs (scratch copy of the frozen tree; production tree untouched)
- Passed:
  - dita-core: 232 tests = 222 + my 10, 0 failures. 1 skip: `StressConversationSuiteTest`, because its data was deliberately not copied.
  - dita-projections: 23, 4 skips.
  - dita-muse: 26, 2 skips.
  - dita-telegram: 33.
  - dita-store-postgres: 104 run, 67 skipped. All PG-gated, so those are NOT TESTED.
- dita-eval: errors only where tests need `sealed/`, which I deliberately did not copy. NOT TESTED by me.
- dita-app: not reached. NOT TESTED by me.
- Skips are not counted as passes.

## 6. Remaining unverified risks
- PostgreSQL execution of everything, because this sandbox denies `shmget` and localhost TCP. That covers:
  - the advisory lock under real contention, and cross-group independence;
  - the 10 s timeout behaviour;
  - the V6 conversion, outbox and rollback;
  - R47-1.
- Load and lock-duration behaviour; Vespa/Neo4j round-trips; direct-SQL writers that bypass the protocol (documented).

## 7. Conditions
1. Before any real V6 application (already gated on human approval): fix R47-1 (one line), or apply in a writer-free window. Then re-run the PG suites on a disposable instance.
2. Peter accepts R47-3 (legacy false relations need reconfirmation).
3. R47-2 is tracked as a performance note only.

## Handoff: resuming the semantic evaluation
- Production tree frozen at `771f1466…` (291 files). Re-run `scripts/task046-freeze.sh` and compare before any scored run.
- The semantic evaluation runs on the in-memory store. R47-1 and R47-2 do not affect it.
- Re-baseline the scores affected by T47-1 (RelationArms), per the round-2 record, and record a new evaluation freeze before scoring.
- No V6 application, live Muse call or sealed holdout access is implied by this approval; each still needs its own approval.
- Evidence: `FuryIndependent047Test.java.txt` (sha256 6bc951b3…) and `offline-run-summary.txt` (sha256 753000d1…).
