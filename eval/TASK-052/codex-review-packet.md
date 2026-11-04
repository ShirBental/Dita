# TASK-052 Codex review packet (prepared by Nick Fury, 2026-11-03)

Status: **REVIEW NOT STARTED.** Nothing in this packet is a Codex approval.

## Exact revision under review
- Source tree: `TREE 5c0fdd92c92ee60d01c9dd6235398d849c761642510ee3c13174d625d73bdadd` (313 files).
  Manifest: `eval/TASK-052/verified-manifest-5c0fdd92.sha256`. Recompute with `scripts/task046-freeze.sh`; the TREE line must match.
- The tree hash does not cover `scripts/` or `docs/`. Their hashes are in `eval/TASK-052/verified-scripts-5c0fdd92.sha256`.
- There is no git in this project. Your earlier TASK-049 snapshot `b49457db…` is a different base. The TASK-049 patch was applied
  unchanged (`eval/TASK-052/task049-artifacts.sha256`).
- The brief also mentioned tree `c529d4a2…`. That hash appears nowhere in the project, and no run log exists for it. The PASS below is
  for `5c0fdd92` only.

## Real-stack evidence for this exact tree (run by operator, 2026-11-03T13:36:09Z, `scripts/e2e-hybrid-verify.sh`)
| Stage | Test | Result |
|---|---|---|
| 1 | TelegramReplayPostgresAcceptanceTest, demo stack, dense OFF | 1 run, 0 failures, 0 errors, 0 skipped |
| 2 | Task049EnglishIntegrationTest, isolated stack, dense ON, frozen v2 manifest | 1 run, 0 failures, 0 errors, 0 skipped |
| 3 | TelegramReplayPostgresAcceptanceTest, isolated stack, dense ON | 1 run, 0 failures, 0 errors, 0 skipped |

Muse was RECORDED; there were no live calls. Logs: `build/e2e-hybrid-verify/{run,stage1,stage1-mvn,stage2-mvn,stage3-mvn}.log`.
Metrics: `build/e2e-hybrid-verify/task049-output/`.
- A/B/C, 13 questions (10 answerable + 3 abstain), exact cited sources: A (BM25) 5/10, B (dense) 4/10, C (hybrid) 6/10. On paraphrases
  (8): A 3, B 3, C 4. Abstention: 3/3 in every arm. Hybrid metrics: 28 calls, 0 sparse failures, 0 dense failures. Deleted vector absent,
  edit excluded, cross-group query empty.
- Stage 3 evidence line (synthetic): content-terms-only BM25 `[which doctor good infants infant]` → []. Full-question BM25 (type=any) → m1 item
  rank 1 (1.96), m2 item (1.29). Dense → m1 item cosine 0.6956 ≥ 0.60, semanticAdmission=true. Stage 1 asserts that, with dense OFF, the
  production m7 reply does not cite m1. Stage 3 asserts that, with dense ON, it does.

## Changes since the provisional integration manifest (0ff89e17)
TASK-052 fixes (Fury):
- `dita-projections/.../VespaProjection.java`: `awaitDenseReadiness` gives the startup probe an explicit 5 s timeout per attempt
  and retries only HTTP 504, at most 3 times; anything else fails closed. `denseBody` was extracted as a seam. User dense queries
  are unchanged.
- `dita-app/.../config/PostgresRuntimeConfiguration.java`: the knowledgeRetriever bean uses the probe.
- `dita-app/src/test/.../TelegramReplayAcceptance.java`: the control group is registered before its append (FK), and the
  failure message lists batch diagnostics.
- `dita-app/src/test/.../TelegramReplayPostgresAcceptanceTest.java`: corrected lexical control and dense-off/on differential.
- New: `DenseReadinessProbeTest` (6), `ReplayFixtureLexicalControlTest` (3).

TASK-051 transcript follow-up (concurrent session, Natasha L-1..L-4): `TelegramConfiguration.java`, `ReplayTranscript.java`,
`ReplayConfigurationTest.java`, `ReplayTranscriptPathTest.java`, `ReplayTranscriptTest.java`.

## Please focus on
1. Hybrid retrieval correctness: `HybridKnowledgeRetriever`, `VespaProjection.retrieveDenseKnowledge/parseDenseKnowledge`, `KnowledgeTruth`.
2. Dense/BM25 fusion and ranking: `HybridKnowledgeRetriever.fuse` (RRF k=60, dense weight 0.5, duplicate handling, tie-break by id).
3. DEC-032 reranker: the dense-OFF path uses `RerankingKnowledgeRetriever`; the dense-ON path bypasses it. Is that asymmetry intended and safe?
4. Citation integrity: semantic admission bit + current-render SHA-256 match, `KnowledgeAnswerComposer`, `AnswerVerifier`, final atomic gate.
5. Group isolation and authorization: the YQL group filter is built by string concatenation (`requireGroup` guards the id format);
   hit scope checks; `HistoryAccessFilter` ordering relative to dense admission.
6. Stale vectors and embedding model migration: there is no runtime model-identity check or re-embed path (TASK-049 open item).
7. Failure handling: at runtime a dense failure falls back to sparse silently at answer level, visible only in metrics and a WARN log.
   Startup fails closed.
8. Do the tests demonstrate dense value? Note that full-question BM25 already ranks m1 first. Dense adds the admission signal,
   not recall, in this case. The A/B/C is development-calibrated (threshold 0.60), with 10 answerable questions.

## Known open items (not hidden)
- Stage 1 failed twice (2026-11-02 22:00/22:02Z, 0 DONE extraction batches), then passed. The root cause was never established;
  diagnostics were added, but the failure has not recurred since. The leading hypothesis is another process on the shared demo DB
  claiming the batch (a non-replay RECORDED process is eligible for every group).
- The runtime path has no model/revision identity check against the deployed Vespa package.
- The default Vespa query timeout still applies to user dense queries. A cold embed after a restart is covered only by the startup probe.

## Reproduce
```bash
scripts/task046-freeze.sh | tail -1
scripts/e2e-hybrid-verify.sh
```
Needs the isolated stack credentials in `.env.task049` (see `docs/task052-hybrid-credentials.md`).
