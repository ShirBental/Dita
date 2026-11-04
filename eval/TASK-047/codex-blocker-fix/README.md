# TASK-047 remaining blockers — Codex implementation freeze

Status: implemented and verified on synthetic in-memory and isolated PostgreSQL fixtures; ready for independent review.
No deployment or migration of an existing application database occurred. No semantic heuristics, semantic datasets,
sealed holdouts, live Muse calls or credentials were accessed/changed. The disposable PostgreSQL instance was stopped.

## Revision and patch

Baseline: `d18f47e0bb9574f8182f5a5a40affba67fd30625e85db6351de9a1b945398671` (289 source files).
New tree: `771f14662fabf2cdbaee00ef1e0cc4c08a9903d6faf1386bc2ee55057756ff86` (291 source files).
Patch SHA-256: `b121c99a27bb285cf4e07da0ebc5951cf05bc7c63847759436c3f37194646c1f`.

`changes.patch` applies with `patch -p1` to a pristine baseline and reproduces every new source-manifest entry and all
changed-file hashes. `manifest.sha256` follows the prior source-tree scope (module src/, poms, db/, vespa/).
`changed-files.sha256` additionally covers the changed policy document. Evidence/artifact hashes are in `ARTIFACTS.sha256`.

Exactly seven code/test/policy files changed:

- dita-core/src/main/java/app/dita/core/QueryService.java
- dita-core/src/main/java/app/dita/core/store/MessageStore.java
- dita-store-postgres/src/main/java/app/dita/store/postgres/PostgresMessageStore.java
- db/migration/V6__validity_unresolved.sql
- dita-core/src/test/java/app/dita/core/knowledge/FinalAnswerIsolationTest.java (new)
- dita-store-postgres/src/test/java/app/dita/store/postgres/PostgresTask047BlockersTest.java (new)
- docs/TASK-046-deleted-correction-policy.md

## Corrections

F2: final validation runs in one isolated group unit. PostgreSQL store units acquire the same group advisory transaction
lock before reading/mutating truth; in-memory units use their existing monitor. A writer cannot commit between the final
verifier and evidence-view reads. Already committed pre-gate changes still trigger the existing one-retry/fail-closed
behavior. Locks are group-scoped and bounded by a 10-second acquisition timeout. No retriever or external API runs while
the final gate holds the lock. The serialization point is final validation, not subsequent network delivery.

F3: V5 SUPERSEDED encoded two indistinguishable origins. The still-unapplied V6 migration now conservatively converts
existing SUPERSEDED rows to UNRESOLVED, including fan-in retirements, before V6 repair is enabled. ACTIVE/NULL rows,
source wording and evidence are unchanged. It emits ids-only knowledge.changed events in the same transaction.
Fresh V6 relation-only retirement remains repairable; legacy ACTIVE claims remain repairable; new reconfirmations remain
answerable. Ambiguous old retirements deliberately require a new claim rather than invented provenance.

## Evidence

- Fail-before on verified frozen sources plus final regression tests: core 5 tests, 2 expected failures; PostgreSQL
  8 tests, 6 expected failures, zero skips. Actual stale answers and revival of the withdrawn entrance claim appear in
  `fail-before.txt`. Controls pass before and after.
- Full default offline regression (`mvn -o -B -fae clean test`): 557 tests, zero failures/errors, 78 environment-gated
  skips. Skips are not passes. Per-module counts are in `offline-summary.json`; complete output is `full-offline.txt`.
- Isolated PostgreSQL V5 and V6: each run has all 12 TASK-047 tests passing, zero skips. Each also runs 33 storage contract
  tests with zero failures and one schema-inapplicable skip (V6-only round-trip on V5; V5-only rejection on V6).
  See `postgres-V5.txt` and `postgres-V6.txt`. The initial fixture lacked V3 outbox RLS; this was corrected only in the
  disposable database before these final contract runs.
- F4 non-scored lifecycle controls: 3 passed, zero skips (`arm-regression.txt`). No scored semantic evaluation ran.
- Fresh adversarial migration probe: deliberate projection-event failure rolls back the constraint, validity conversion
  and outbox together. Removing the synthetic failure and retrying succeeds (`adversarial-atomicity.txt`).
- Additional controls cover unrelated-group concurrency, bounded retries, early/late/edit/fan-in withdrawal, active legacy
  knowledge, fresh V6 repair, reconfirmation with a new source, unchanged wording, and ids-only projection updates.
- F1/F4/F5 source implementations and semantic heuristics/datasets are unchanged; their regression controls pass.

## Reproduce

From the project root:

```sh
bash scripts/task046-freeze.sh /tmp/task047-current-manifest.txt
cmp eval/TASK-047/codex-blocker-fix/manifest.sha256 /tmp/task047-current-manifest.txt
shasum -a 256 -c eval/TASK-047/codex-blocker-fix/changed-files.sha256
mvn -o -B -fae clean test
```

For PostgreSQL use ONLY a new disposable local PostgreSQL cluster with synthetic data, non-bypass `dita_app`, and an
owner capable of creating new schemas. Apply V1-V5 (including V3 outbox RLS), then repeat on V6. Set DITA_TEST_DB_URL,
DITA_TEST_DB_USER and DITA_TEST_DB_PASSWORD for that disposable fixture. The upgrade cases additionally require
DITA_TASK047_ISOLATED=true, DITA_TASK047_OWNER_URL and DITA_TASK047_OWNER_USER (optional owner password). The new-schema
fixtures expect local trust authentication for the synthetic dita_app role; never point them at a real database.

```sh
mvn -o -B -pl dita-store-postgres -am test \
  -Dtest=PostgresTask047Test,PostgresTask047BlockersTest,PostgresMessageStoreContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## Remaining limits and approvals

- No existing database was changed. V6 now includes a conservative data conversion and needs explicit approval before
  any real application. Use ONE transaction and an owner with BYPASSRLS/superuser; the migration refuses a role that
  could silently miss rows under FORCE RLS. Review lock duration/maintenance needs before non-test use.
- If the original V6 was already applied elsewhere, do not rerun this amended migration: prepare an approved forward
  upgrade establishing the same conservative provenance boundary first. This session only used newly created test data.
- Group serialization covers application truth operations through MessageStore. Direct SQL writers must honor that
  protocol or operate in a maintenance window. Hash collisions can serialize unrelated groups but cannot mix their data.
- Consistency is at the final validation transaction's serialization point. A later edit/withdrawal belongs to subsequent
  answers; downstream message delivery is not an atomic part of that database transaction.
- Sustained load, migration under production load, and actual Vespa/Neo4j engine round-trips were not verified. Their
  existing projection mirrors pass, and migration-event publication is checked against PostgreSQL.
- Known lexical supersession limitations and historical-answer wording remain outside this patch.
