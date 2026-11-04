# Reviewed Muse request previews (Natasha Romanoff, 2026-11-01)

Approval: APPROVE WITH CONDITIONS, for live **dev** runs only. The **sealed** set is NOT approved.

N-118, N-119 and N-120 were fixed by Fury after the review:
- a pending marker is written before each call;
- a file lock is held for the whole live run;
- the guard checks body length.

After the fixes the request bodies and hashes are unchanged. Natasha has not yet re-reviewed these three fixes. A sealed run still requires her re-review.

| Arm | Set | Full preview sha256 (pass to the launcher) | Calls | Worst case |
|---|---|---|---|---|
| A3 relation classification | dev (120 cases, no limit) | `cfdcac9fbc4e649218a81c6de54af1d690bb8d552547f69be5cd65e545f80020` | 12 | $0.44 |
| A6 structured extraction | dev (120 cases, no limit) | `2704934d98fbddbc8ea0e89b89daa29798e481479072a04ba03a062c173bb289` | 12 | $0.85 |

## Conditions
1. Use only these hashes, and pass no limit argument.
2. Run A3 and A6 one after the other, never at the same time.
3. Never delete `eval/TASK-046/semeval/ledger.json`. If a run is interrupted, Fury reviews it before any rerun.
4. After the first batch, compare the reported usage with the worst-case estimate. The prices ($1.25/M in, $4.25/M out) are user-supplied and not verified.

## Commands (operator's Mac, repository root; each asks you to type `approve`)
```
scripts/semeval-muse.sh A3 dev cfdcac9fbc4e649218a81c6de54af1d690bb8d552547f69be5cd65e545f80020
scripts/semeval-muse.sh A6 dev 2704934d98fbddbc8ea0e89b89daa29798e481479072a04ba03a062c173bb289
```
