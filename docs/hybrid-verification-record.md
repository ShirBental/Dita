# Historical hybrid verification evidence

Evidence reconciled on 2026-11-04; no tests or paid calls were triggered by this correction.

The user supplied terminal output from 2026-11-02T22:33:41Z, tree
`c529d4a2cd92e37ef9b23cb9c6f202894745fc4b1612db2e93b41cb8f61b0d97` (312 files), reporting all three
`scripts/e2e-hybrid-verify.sh` stages PASS, zero skipped, Muse RECORDED. The earlier zsh errors came from
pasting shell prompts/output as commands; they do not negate the later successful verification.

Local logs currently describe a later run starting 2026-11-03T13:36:09Z, tree
`5c0fdd92c92ee60d01c9dd6235398d849c761642510ee3c13174d625d73bdadd` (313 files).
These are distinct run records, not matching copies of one run.

| Stage | Local Maven evidence | Result |
|---|---|---|
| 1: demo stack, dense off | TelegramReplayPostgresAcceptanceTest | 1 run, 0 failures/errors/skips |
| 2: isolated dense stack, production QueryService | Task049EnglishIntegrationTest | 1 run, 0 failures/errors/skips |
| 3: isolated dense stack, dense-on replay | TelegramReplayPostgresAcceptanceTest | 1 run, 0 failures/errors/skips |

All three local Maven logs report BUILD SUCCESS; run.log reports PASS (3/3 stages executed, 0 skipped),
Muse RECORDED, not live. This supports historical end-to-end execution of the knowledge hybrid path.
It does not establish current-tree reproducibility, live Muse quality, broad retrieval accuracy or product
validation. The existing TASK-049 acceptance/rollout gaps remain open. TASK-054 semantic message retrieval
is a separate path. No source, schema, migration or runtime configuration was changed.

Local log location: `build/e2e-hybrid-verify/` (ignored by Git). Content fingerprints preserve which logs
were inspected, without publishing their complete contents:

| Local file | SHA-256 |
|---|---|
| `run.log` | `e07b9325a75f9d13d5884e3b9197bb7d63adc7139221d2105dd492ae67f8ae70` |
| `stage1-mvn.log` | `e4e710c63894ea13bd57ed973046aeb76a78d1d3414c5984457f955d8d4fc416` |
| `stage2-mvn.log` | `8fbdb9ba8315f60ab1a978d5da3bb4d6296f1a39b48e14e0c2a315a10d9a28ed` |
| `stage3-mvn.log` | `e981811e08fe181a6cbc63e59606b0a57e98d146907d01c7698ebe2bc82d5ab8` |

Current overview: [README](../README.md); capability register: [capability-status.md](capability-status.md).
