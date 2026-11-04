# TASK-046: READY FOR CODEX REVIEW

**Status: READY FOR CODEX REVIEW. TASK-046 is NOT complete.** No Codex review of this revision has happened yet. Claude/Avengers internal reviews are listed below; they do not replace the independent audit. Do not edit the repository while Codex reviews it (the implementation is frozen).

## Frozen revision
This repository has no git. The revision is identified by a content manifest:
- Tree hash: `c2e74c7ec86a127591ed20f9ad2fe5989004ce5bfc71eaa0cd416afa6237eb96` (267 files; every module `src/`, `db/`, `vespa/`, all `pom.xml`; excludes target/, secrets, data and sealed/holdout sets).
- Manifest: [freeze-manifest.txt](freeze-manifest.txt). Verify with `scripts/task046-freeze.sh` and compare the TREE line.
- Diff against the pre-TASK-046 tree: [changes.patch](changes.patch) (sha256 `e7b7f2af516ed9f10cf4ee5d01d0f8347b13426506e2b53e4445edb4ebec81f8`, 18 files; `a/` = before).

## Reproducible test command (offline; no network, no model calls)
```
cd /path/to/Dita && mvn -o -B test
```
- Targeted: `mvn -o -B -pl dita-core test -Dtest='TrustworthyStateSuiteTest,TrustSuiteGateTest,Task046ReproTest,Task046RegressionTest,Task046ReviewFindingsTest'`.
- Recorded lab dev replay (in memory, replays the recorded Muse reply, no call): `mvn -o -B -pl dita-app -am test -Dtest=SyntheticLabKnowledgeTest -Dsurefire.failIfNoSpecifiedTests=false -Ddita.capability.run=true -Ddita.lab.muse=RECORDED`, then `python3 -I eval/synthetic-lab/score.py lab-C-in_memory-recorded`.
- Real stack (PostgreSQL + Neo4j + Vespa): `scripts/lab-recorded-verify.sh` (recorded reply, no model call). Run by the user; the result is under Results. The Postgres contract tests run when the `CapabilityPostgresRunTest.REQUIRED` env vars are set.

## Results at this revision (Fury, 2026-10-31, offline)
- Full regression: 486 tests, 0 failures, 0 errors, 63 skipped. Per module (tests/skipped): core 173/0, store-postgres 88/52, projections 20/4, muse 26/2, telegram 30/0, eval 128/1, app 21/4. All skips are environment-gated (real Postgres/Neo4j/Vespa, live Muse, capability runs). Unexecuted tests are not counted as passed.
- Trust suite (acceptance gate): 53 cases, 53 accepted, 2 tolerated declared expected failures (B-R2, F-R3: lexical-only paraphrase retrieval, EVIDENCE_NOT_CITED only).
- Recorded lab dev replay, compared with the pre-change snapshot run the same way:
  - Scores identical before and after: A 5/15, B 6/15, C 9/15, C citation precision 0.52.
  - Text changes: Q1 now also shows "November 14 is no longer an option" [m1019] as current, where it was listed as replaced (it is consistent with the decision, not a competing value). Q9 is reordered. Q10 drops an off-topic "should" line.
- Real stack (user's Mac, 2026-10-31, this frozen tree, recorded reply, no model call): `scripts/lab-recorded-verify.sh` **PASS**.
  - PostgreSQL 41 items / 2 relations = Neo4j 41 nodes / 2 RELATES = Vespa 41 docs.
  - A 5/15, B 6/15, C 9/15 (precision 0.52). C answers on PostgreSQL equal the in-memory answers on 14/15 (Q9 differs, as it already did between backends in TASK-045).
  - The PG `editedMessageIds` and `lockForRead` paths ran. NOT exercised on PG: `DeletionService.edit`, deletion reconciliation, lock concurrency.
- Not run: holdout v1/v2/v3, stress sealed sets (untouched).

## Confirmed defects and root causes
| # | Defect (Codex) | Root cause | Reproduced by |
|---|---|---|---|
| P1 | Trust suite did not enforce declared expectations | Only reasons prefixed FALSE/VALID/STALE/NONDETERMINISTIC/"item" were asserted. mustCite, textMust(Not), leadContains failures were reported and ignored; unknown keys and typos were silently vacuous; "expected to fail" was a class-name substring that excused every failure; `-Ddita.trust.strict=false` turned the gate off | The pre-change suite on a broken case: `pass 0`, Maven exit 0 |
| P2 | Old-revision knowledge shown as current after an edit | Telegram edits called `store.edit` only. Knowledge spans kept indexing the NEW text, the statement kept the old wording, and supersessions made by the old revision stayed | Task046ReproTest d1_* (old code: renders "Dinner is cancelle" from a stale span) |
| P3 | Proposals as decisions, offers as commitments | Status and predicate were validated independently; markers were searched in the whole message; the composer verb came from the predicate | d2_* |
| P4 | Blanket supersession on subject equality; deletion inconsistency | The strong path (later DECISION/commitment) retired ANY earlier same-speaker item with the same subject id; deleting the superseding item cascaded the relation but left the target SUPERSEDED | d3_* |
| P5 | Unrelated named-person claims answer specific questions | `pool = topical.isEmpty() ? about : topical`; question-frame words ("when") counted as topic | d4_* |

## Implementation (file list)
- `knowledge/TrustworthyStateSuiteTest.java` (rebuilt), `TrustSuiteGateTest.java` (new meta-tests), `resources/trust/{fury,bruce}-cases.json` (explicit expectedFailure markers on F-R3 and B-R2 only; no other case changed).
- `deletion/DeletionService.java`: new `edit(...)`; the delete path calls `ValidityReconciler`.
- `knowledge/ValidityReconciler.java` (new): the single writer of validity. SUPERSEDED iff a SUPERSEDES relation from a showable item exists; showable = not redacted, a live source, not derived from an edited message.
- `knowledge/ExtractionService.java`: locks evidence rows, drops items whose evidence changed during the proposer call, and writes validity via the reconciler.
- `knowledge/EvidenceValidator.java`: clause-scoped markers; "final" only in decision forms; proposal or '?' vetoes a DECISION; hedge words make a commitment an OFFER; an OFFER needs offer wording; status↔predicate table.
- `knowledge/RelationRules.java`:
  - Strong path = (first-person/imperative option AND same property) OR change wording OR a replaced calendar value of the same type with the rest of the claim covered.
  - Restatement veto now also applies to the strong path.
  - A contingency is retired only by a change that touches its condition.
  - Calendar value types cover English and Hebrew forms.
- `knowledge/KnowledgeAnswerComposer.java`: verb from status; one edited-ids read per answer; named-member gate (frame words, broad vs specific, speech-act topic only when nothing else is asked).
- `store/MessageStore.java` (+ `editedMessageIds`, `lockForRead` defaults), `InMemoryMessageStore.java`, `PostgresMessageStore.java` (one-query `editedMessageIds`; `SELECT … FOR SHARE` lock), `model/Assertion.java` (`reactivated()`).
- `dita-telegram/TelegramAdapter.java`: edits go through `DeletionService.edit`.

## Regression tests added
- `TrustSuiteGateTest` (15): the gate rejects forbidden text, missing evidence, a simulated stale-citing composer, an empty composer, missing/forbidden/false supersessions, typos, wrong types, vacuous expectations, XPASS, excused safety kinds, duplicate items per message, an empty case directory, and measure mode on the acceptance directory (including a path alias).
- `Task046ReproTest` (15, written independently by Shuri before the fix): 11 defect tests failed on the old code; 4 preservation tests.
- `Task046RegressionTest` (14, Fury): edit service, in-flight edit, reply-parent edit, early/late deletion, status table, calendar rule, fallback really answering, UNKNOWN. 9 of the 10 that compile against the old code fail there.
- `Task046ReviewFindingsTest` (23): one test per confirmed internal-review counterexample (B46-1…9, N-103, N-105, T46-2), English and Hebrew.

## Internal reviews (Claude Avengers, read-only; NOT the Codex audit)
- Tony (architecture) T46-1…7.
  - Fixed: T46-1 (row lock + edited items can never retire), T46-2 (reconciler is the single validity writer), T46-3, T46-5, T46-7 (dependent links; no key-string parsing).
  - Open: T46-4 (one-time migration for existing stored truth: needs human approval, not run), T46-6 (a shared lexicon class; only partly addressed).
- Natasha (security/privacy): APPROVE WITH CONDITIONS on round 1.
  - Fixed: N-103 (+test), N-105 (+test), N-106 (`lockForRead` FOR SHARE in the extraction transaction; Postgres behaviour NOT executed here), N-108, N-109.
  - Recorded, not fixed (older than TASK-046): N-104 (subject/entity wording verified in an edited/deleted REPLY PARENT stays in stored items/projections; answers are protected by N-87), N-107 (edits to a "/" command or to media are ignored).
  - Open PO question: N-110 (late-preserve deletion of a correction reactivates the outdated claim).
  - Natasha has NOT re-reviewed round 2.
- Bruce (correctness rules) B46-1…11: B46-1…11 addressed with tests. Known limit kept: B46-5's revised contingency is not detected (safe direction). Bruce has NOT re-reviewed round 2.

## Outstanding limitations
- On Postgres, the edit path, deletion reconciliation and FOR SHARE lock concurrency have no dedicated test (the lab run only exercises extraction and answering). The Postgres-gated contract tests were not run in this session.
- Existing stored knowledge is not migrated (T46-4). Items derived from messages edited before this change are hidden and cannot retire anything, but stay in storage/projections until a reviewed migration runs.
- Lexical rules: two different temporal properties of one subject with the same value type ("setup at 3pm" vs "party at 7pm") cannot be told apart. Hebrew place-slot proposals ("בואו נלך לאילת") are not superseded by a decision without a shared content word (missed supersession, the safe direction). Paraphrase retrieval is still lexical-only.
- The edited message's new revision is not re-extracted (E-2 policy); its content is answered from the message path only.
- Dev-set evidence only: the lab replay is not a holdout; holdout v3 remains sealed and unrun.
