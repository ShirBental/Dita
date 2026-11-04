# Natasha finding-ID registry

Created 2026-10-29. The mapping is Natasha's (read-only review). Fury applied it and checked it by grep.

**Why:** parallel reviews issued the same IDs twice. The earliest-issued meaning keeps its ID. Later meanings move to new IDs starting at **N-61**; the highest ID in use before this was N-60. No finding text, severity or status was changed.

**Next free ID: N-95.** Allocate by checking this file first. Never reuse an ID.

## Collisions resolved

| Old ID | Meaning that KEEPS the ID | Moved meaning | New ID |
|---|---|---|---|
| N-44 | ADR-001: cursor skips out-of-order commits (= F1). docs/adr/ADR-001-event-transport.md:75 | value benchmark: no `benchmark` mode in muse-live.sh (eval/value-benchmark/DESIGN.md:273) | **N-67** |
| N-45 | ADR-001: deletion applied without a truth check | benchmark: dollar budget guard | **N-68** |
| N-46 | ADR-001: replay/out-of-order safety contract test | benchmark: synthetic-data gate | **N-69** |
| N-47 | ADR-001 | benchmark: real-sounding names in fixtures | **N-70** |
| N-48 | ADR-001 | benchmark: injection coverage | **N-71** |
| N-49 | ADR-001 | benchmark: report scans | **N-72** |
| N-50 | ADR-001 | benchmark: gold answers on the same line as questions | **N-73** |
| N-51 | ADR-001 | benchmark: two-track deletion scoring | **N-74** |
| N-52 | benchmark: Notion gets aggregates, hashes and paths only (DESIGN.md:278) | relay: DB-level ordering for every writer (V4 trigger) | **N-61** |
| N-53 | benchmark (described jointly with N-52; **text missing**, Natasha to supply) | relay: `outbox_id_seq` CACHE 1 precondition | **N-62** |
| N-54 | benchmark LOW/INFO (**text missing**, Natasha to supply) | relay: bounded `lock_timeout` on the outbox lock | **N-63** |
| N-55 | cost design: usage files hold numbers only (COST-DESIGN.md:117) | relay: reconcile/backfill not wired | **N-64** |
| N-56 | cost design: experiment-wide budget ledger on disk | relay: relay Error handling per consumer | **N-65** |
| N-57 | cost design: provenance labels | relay: N-57a script checks the MISSING marker, N-57b test hardening, N-57c replay test uses a test projector | **N-66a/b/c** |

N-55..N-57: medium confidence on which was issued first. The relay set was moved as one block because N-52..N-54 had to move anyway.

## Relay findings (TASK-036): status as recorded

Severities were not recorded. The fixes are written but NOT RUN on PostgreSQL until `scripts/relay-verify.sh` passes.

| ID | Status |
|---|---|
| N-61 | fixed in code (V4 trigger). An older record also lists it as blocking real data; it is fixed by V4 once V4 is applied and verified |
| N-62 | fixed (verify-script check + test) |
| N-63 | fixed (10 s lock_timeout) |
| N-64 | **OPEN**: blocks real data |
| N-65 | **OPEN**: blocks real data |
| N-66a, N-66b | fixed |
| N-66c | **OPEN** (the real-projection replay is covered by EndToEndSliceTest #6) |

## Where references were updated

- **Updated:**
  - Relay IDs: docs/configuration.md, PostgresEventPublisher.java, AdvisoryLockSafetyTest.java, OutboxOrderingTest.java, scripts/relay-verify.sh.
  - Benchmark IDs: .avengers/decisions.md (DEC-023 lines) and docs/audit/2026-10-29-stage0-readiness.md §5.
- **Deliberately NOT edited:**
  - `db/migration/V4__outbox_commit_order.sql` line 1 still says "Natasha N-52". It is a migration that may already be applied, and is never rewritten; read it as **N-61**.
  - `.avengers/tasks.md` is append-only: older lines keep the old IDs. Read them through this table.
  - **Locked by TASK-039** (dita-eval/**, eval/value-benchmark/**): DESIGN.md, COST-DESIGN.md, corpora/README.md and the dita-eval sources/tests still use benchmark N-44..N-51. The lock owner applies N-67..N-74 there when the lock is released. The full line list is in tasks.md (2026-10-29 renumbering entry).
- **ADR-001 and the cost-design IDs (N-55..N-60)** keep their numbers: no change.

## TASK-044 pre-flight review: one live Muse lab call (Natasha Romanoff, 2026-10-30; verdict APPROVE WITH CONDITIONS)
| ID | Severity | Status | Summary |
|---|---|---|---|
| N-75 | MEDIUM | FIXED (Fury) | LIVE checks fixture sha256 ce412888… and refuses unless the request is byte-identical to the reviewed preview |
| N-76 | MEDIUM | PARTLY FIXED | retry() refuses ApprovalExpired/test-cleanup batches; TTL still measured from the last update (approval time not stored) |
| N-77 | LOW | FIXED | dita.extraction.approval-ttl bounded to [1m, 1h]; worker default still on (open) |
| N-78 | LOW | OPEN (manual condition) | Another Dita JVM on the same DB could claim the batch; user confirms no other Dita process before the run |
| N-79 | MEDIUM | OPEN | InjectionGuard bypasses (zero-width/homoglyphs, paraphrase, other languages, split messages); uncited injection still reaches Muse |
| N-80 | LOW | PARTLY FIXED | key no longer a grep argument; leak scan covers */target/surefire-reports; key still exported to child processes |
| N-81 | LOW | PARTLY FIXED | LIVE refuses when a paid recording exists; no dollar ledger yet (N-68) |
| N-82 | LOW | OPEN | edited/PLATFORM rule not applied to context messages; missing tests (target deleted after approval, Postgres path) |
| N-83 | MEDIUM (eval validity) / LOW (security) | OPEN (procedural) | Holdout content lands outside sealed/ (eval/synthetic-holdout/results/*). Builders treat that folder as sealed and use stdout counts only. |
| N-84 | LOW | FIXED (Fury) | Reviewed request sha256 pinned per dataset in SyntheticLabKnowledgeTest (lab 74ebeefa…, holdout ea06b528…) |

## TASK-044 DEC-031 answering-pipeline review (Natasha Romanoff, 2026-10-31; verdict APPROVE WITH CONDITIONS)
| ID | Severity | Status | Summary |
|---|---|---|---|
| N-85 | MEDIUM | FIXED (Fury) | "replaces" line could re-quote a redacted superseded item. It now shows message ids only, with no text (test updated) |
| N-86 | MEDIUM | FIXED (Fury) | "replaces" quote was uncited/unverified. It is no longer quoted, and only verified quotes are cited (test asserts citations == [current]) |
| N-87 | LOW | FIXED (Fury) | Model subject wording shown and indexed. Now the subject is shown/indexed only where verbatim in live evidence; entity surfaces removed from BM25 text |
| N-88 | LOW | FIXED (Fury) | Vespa failure fails the question. Now falls back to the in-process retriever in the same scope (IsolationViolation is never masked) |
| N-89 | LOW | FIXED (Fury) | Qualifier text for RelationRules read without a deletion check. Now adds !deleted() |

## TASK-044 DEC-033 + holdout v2 pre-flight (Natasha Romanoff, 2026-10-31; DEC-033 APPROVE WITH CONDITIONS, v2 egress APPROVE)
| ID | Severity | Status | Summary |
|---|---|---|---|
| N-90 | LOW | OPEN | Stale info can appear as a "Related message" if restated by another member or never extracted (integrity) |
| N-91 | LOW | FIXED (Fury) | Tests added: deleted not Related; C-11 records supporting ids; foreign/missing hits ignored; no Related under SINCE_REQUESTER_JOINED. Dead code removed |
| N-92 | LOW | OPEN | Quotes not delimited: a newline + fake "Related message" line inside a member's message can spoof attribution (all renderers) |
| N-93 | LOW | FIXED (Fury) | Send-time byte-equality now compares against the exact bytes that were hashed (no re-read) |
| N-94 | INFO | OPEN (procedural) | answers-lab-*.jsonl in holdout results contain question text outside sealed/; builders treat holdout results/ as sealed |


## TASK-045 DEC-034/035 trust-boundary review (Natasha Romanoff, 2026-10-31; local synthetic + one-shot v3: APPROVE WITH CONDITIONS; real-group use: BLOCK)
| ID | Severity | Status | Summary |
|---|---|---|---|
| N-95 | MEDIUM | PARTLY FIXED (DEC-035) | Retraction path too broad; another member's reply parent satisfied the referent check. Now: first-hand only, marker in the quoted clause, own message names the referent, "can't wait"-type idioms excluded. B2-S5 (unseen) shows "cancelled" about another thing still retires a commitment: OPEN for that class |
| N-96 | LOW | FIXED (DEC-035) | Continuity vetoes no longer block the DECISION path; vetoes read from the quoted clause |
| N-97 | LOW | FIXED (DEC-035) | Praise idiom bounded and clause-final, neutral stance, now in Stance; "can't/cannot recommend" negative. B2-V1 (unseen) passes |
| N-98 | MEDIUM | PARTLY FIXED (DEC-035) | Deleted/redacted-source items never supersede. Still OPEN: superseded items are not reactivated when the superseding message is deleted; no relation recompute |
| N-99 | LOW | OPEN | Post-extraction edits steer relations/ordering (full-message and reply-parent reads ignore EDITED history) |
| N-100 | LOW | OPEN | "[asked; Dita found no reply…]" ends the answer without trying the message path; label applied to unchecked secondary question lines |
| N-101 | LOW | PARTLY FIXED (DEC-035) | Reply lines now need topical overlap or the question's subject, in time order. B2-N2 (unseen) still shows an off-topic reply leading (shares the question's subject id) |
| N-102 | LOW | FIXED (DEC-034/035) | Trust suite strict by default with determinism check; production bean asserts Related lines off |
Status changes: N-90 OPEN → MITIGATED (Related lines off in production; reopens on opt-in). N-79 stays OPEN (integrity consequence via N-95). N-82 stays OPEN (narrowed). N-92 stays OPEN (scope widened: new bracket labels are spoof targets). N-83/N-94 unchanged.
