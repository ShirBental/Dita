# TASK-047 round 2: ready for Codex's independent review

**Status:** TASK-047 is BLOCKED until Codex verifies it. Fury does not claim approval.

**Prepared by:** Nick Fury (Claude Code session), 2026-11-01.

## Revision

- **Base:** the tree Codex reviewed, `TREE 71423ebd2f606df63493f423a344f1a0174f4e66202e6644f02e8895a17dc9e6` (286 files).
  - Fury re-verified that all 286 files matched before making any edit.
  - The base was rebuilt as a pristine copy and checked against Codex's `end-manifest.txt`: 0 mismatches.
- **New tree:** `TREE d18f47e0bb9574f8182f5a5a40affba67fd30625e85db6351de9a1b945398671` (289 files). See `manifest.sha256`.
  - It covers the same scope as Codex's manifest: the root `pom.xml`, `db/migration/*`, `vespa/app/**`, and each module's `pom.xml` and `src/**`.
  - The tree hash is the sha256 of the path-sorted manifest lines, computed the same way as Codex's.
- **Patch:** `changes.patch` (sha256 `cefcb2d35dce548a8327c31c4b42fdbb4b190d99afd1a240d2d7f7c4805bcdff`).
  - It changes 11 files.
  - Applied with `patch -p1` to a pristine copy of 71423ebd, it reproduces all 289 manifest entries exactly.
- **Nothing outside the patch was touched:**
  - V6 (`db/migration/V6__validity_unresolved.sql`) is unchanged and was not applied to any database.
  - No sealed data and no evaluation data were changed.
  - No model call was made.

## Fixes, in the priority order the user set

### F1 (High): mixed messages leaked UNRESOLVED claims through the fallback

**Fixed in:** `QueryService.evidenceView`, plus a new `ExtractiveAnswerComposer` constructor.

**What the fallback may quote now:**
- If a message asserts a claim that has no current replacement (UNRESOLVED, or SUPERSEDED with no ACTIVE superseder), the fallback may quote only the validated ASSERTS spans of that message's ACTIVE claims.
- Spans that overlap a blocked claim's span are dropped.
- If a blocked claim has no span in the message, nothing from the message is quoted (fail-closed).

**Relevance and quoting:** both are judged on the allowed parts only. Every part must be a verbatim substring of the live text, and the verifier checks this.

**Codex's case:**
- "What time is dinner?" now returns not found, for both full and limited history.
- "Is parking free?" still answers, quoting only "Parking is free.".

**Scope:** the change is structural (span arithmetic) and adds no wording rule.

### F2 (High): stale exclusion snapshot

**Fixed in:** `QueryService.ask` and `currentAtReturn`.

**How the final gate works:**
1. After composing an answer, Dita re-reads the truth.
2. Every citation must still verify (live, verbatim, same author) and must be quotable under the current evidence view.
3. Every knowledge item the answer used must still exist, be ACTIVE and unredacted, and have a live source.
4. If any check fails, Dita composes the answer once more from fresh state.
5. If that also fails, Dita says it found nothing.

**What it guarantees:** state at gate time. On PostgreSQL the gate's reads are separate statements under READ COMMITTED, so they are not a single snapshot. A change that commits between them can make the gate reject an answer, but should not let a stale one pass; that direction is reasoned from the code, not proven by a test. Sustained concurrency was not tested.

### F3 (High): rejecting a false relation permanently hid valid knowledge (T47-3)

**Decision:** DEC-039.

**How the two reasons are now kept apart:**
- **UNRESOLVED is persistent withdrawal history and is sticky.** A later relation does not turn it SUPERSEDED, and only a new claim is current. As a result, on a store that can hold UNRESOLVED, SUPERSEDED means "retired only by relations, never by a withdrawal".
- **Rejection has a new explicit repair path,** `ValidityReconciler.rejectRelations(store, scope, keys)`. When every relation that retired an item is rejected, the item returns to ACTIVE.
- **Withdrawals are named by the deletion and edit paths.** `reconcile(store, scope, withdrawnTargets)` records them even when another relation still retires the item (fan-in), so a later rejection cannot erase them.
- **A relation removed with no stated reason** is still treated as a withdrawal (fail-closed). Codex's literal sequence (remove the relation, then reconcile) therefore still gives UNRESOLVED.
- **On V5,** a rejection cannot tell relation-only retirement from the V5 withdrawal fallback, so the item stays SUPERSEDED (fail-closed) until V6.
- **No production caller of `rejectRelations` exists yet.** It is the hook for Tony R8 (relation recompute).

**Not done: removing bare shared-preposition matching (`RelationRules.slotFrames`).**
- DEC-037 paused linguistic rule changes, and this is exactly what the semantic evaluation measures.
- Changing it would shift the A1 baseline the evaluation compares against.
- It is left for the evaluation, or for an explicit user decision.

### F4 (High): semantic arms inherited production retirement (T47-1)

**Fixed in:** `dita-semeval/.../RelationArms.apply`. Before each arm decides, `resetToValidatedBaseline` resets relation-retired items in the throwaway store to ACTIVE.

**Guard:** an item that carries withdrawal history (UNRESOLVED) is refused, not erased. No evaluation fixture contains a deletion or an edit.

**Production withdrawal behaviour:** not changed for the evaluation.

**Codex's scorer case:** the rejecting arm now passes E1 and E4.

**Next steps for the evaluation:** affected scores must be re-baselined and the tree re-frozen. Scored runs remain paused.

### F5 (Medium): unrelated parent edits discarded independently supported relations (T47-2)

**Fixed in:** `DeletionService.edit`.

**What the edit path does now:**
- Before dropping a SUPERSEDES relation whose later item replies to the edited message, it re-runs the existing `RelationRules` decision on the later item's own message only, without reading the parent.
- If the relation still holds, it is kept.
- If it doesn't, it is dropped and treated as a withdrawal (fail-closed).

**Scope:** this reuses the existing rule with less context and adds no wording rule.

**Codex's dependent case** ("it is at 8 PM" replying to "Dinner is at 7 PM") is still invalidated.

**Still open:** whether an uncertain, parent-dependent drop should restore the older claim. That is the product-owner question T47-2.

## Regression tests (Codex's exact reproductions)

- **`dita-core/.../Task047CodexReviewTest.java`** has 14 tests.
  - They cover F1, F2, F3 and F5, with negative and positive controls.
  - Fixture texts and call sequences come from Codex's `Independent047.java` and `Evaluation047.java`.
- **`dita-semeval/.../Task047ArmBaselineTest.java`** has 4 tests.
  - They cover F4 using Codex's `Evaluation047`/`Scoring047` cases and the actual scorer, plus controls.
- **`dita-store-postgres/.../PostgresTask047Test.java`** has 4 PostgreSQL-gated tests: F1, F2, F3 and F5 on V5 and on V6.
- **`DeletedCorrectionPolicyTest`:** 4 expectations changed under DEC-039.
  - UNRESOLVED stays UNRESOLVED when superseded again.
  - A fan-in withdrawal is recorded at once.
  - The state-machine cases were updated accordingly.
  - The invariant is now: a live relation means the item is never ACTIVE, and SUPERSEDED means a live relation exists.

### Fail before, pass after

**Core, on the pristine 71423ebd tree** (`fail-before-core.txt`): 7 of 14 fail, all for the defect reason.
- F1 early, F1 late, F1 active-span control, F2 limited-history race, F3 picnic, F3 withdrawal-history and F5 self-contained.
- The 7 controls pass on both trees.
- **One adaptation:** on the frozen tree, `reject()` uses the pre-fix path (remove the relation, then reconcile), because `rejectRelations` did not exist there.

**Semantic evaluation, on the pristine tree** (`fail-before-semeval.txt`): 3 of 4 fail.
- `a` came out UNRESOLVED.
- The scorer reported `e1Fail: true`, matching Codex.
- The withdrawal-history guard did not exist yet.
- The rules-arm control passes.

**After the fix:** all of them pass.

## Results

- **Full offline regression** (isolated copy, `mvn -o -fae clean test`): BUILD SUCCESS. 544 tests, 0 failures, 70 env-gated skips (`full-regression-summary.txt`).
  - Per module: core 217, store-pg 96 (59 skipped), projections 23 (4 skipped), muse 26 (2 skipped), telegram 33, eval 128 (1 skipped), app 21 (4 skipped).
- **dita-semeval:** `SemEvalHarnessTest` 4/4 and `Task047ArmBaselineTest` 4/4.
  - `SemEvalRunTest` (an ungated, scored dev run), `SemEvalMuseTest` and `SemEvalTuneTest` were NOT run.
- **Recorded lab replay** (lab dataset only, in memory, no call):
  - A and B: 15/15 identical.
  - C: 14/15 identical. On Q9 the same 4 lines and citations appear in a different order.
  - **This Q9 ordering is pre-existing.** The unmodified 71423ebd tree flips between the two orders across runs (`lab-q9-order-nondeterminism.txt`).

## Not verified

- **PostgreSQL:** `PostgresTask047Test` and the earlier PostgreSQL-gated tests were not executed.
  - This sandbox refuses System V shared memory, so `initdb` fails with `shmget: Operation not permitted`.
  - They must be run on a throwaway instance outside the sandbox, as Codex did.
- **Not tested:** real Vespa and Neo4j engine round-trips, sustained concurrency, and migration under load.
- **Open items:**
  - The T47-2 policy question.
  - The preposition slot-frame rule.
  - N-118, N-121 (now narrowed to span granularity) and N-122.
  - V6 approval.
