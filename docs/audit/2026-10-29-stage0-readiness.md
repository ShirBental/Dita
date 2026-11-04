> **Historical snapshot (2026-11-04 cleanup).** Existing findings are preserved; current runtime guidance: [documentation index](../README.md).

# Stage 0 benchmark verification and readiness (2026-10-29)

**Method:** Fury inspected the files and ran the tests independently. The harness was built by Shuri (TASK-034). Fury edited nothing in `dita-eval/` or `eval/value-benchmark/`.

**Scope note:** this is **benchmark-infrastructure readiness**. It says **nothing about product value**. No model has been called; every answer in Stage 0 comes from a deterministic mock.

## 1. Test execution (offline, `mvn -o test`)

| Module | Run | Pass | Fail | Skipped | Note |
|---|---|---|---|---|---|
| dita-eval (Stage 0) | 79 | **79** | 0 | 0 | AnswerPromptGuard 5, ContextBuilder 9, Leakage 10, ScorerMetrics 14, GoldDataset 5, EndToEndMock 5, SyntheticDataGate 18, BudgetGuard 7, Arms 6 |
| dita-core | 59 | 59 | 0 | 0 | includes the new `EventDispatcherConcurrencyTest` (outbox fix, parallel work) |
| dita-store-postgres | 59 | 25 | 0 | **34** | DB-gated: contract 24, RLS 1, **new `OutboxOrderingTest` 9**. Need the real DB |
| dita-projections | 5 | 5 | 0 | 0 (+E2E class "0 run") | E2E needs the 3 services |
| dita-muse | 26 | 24 | 0 | 2 | the live tests are approval-gated |
| dita-telegram / dita-app | 14 / 13 | all | 0 | 0 | |

**Execution notes**
1. **First run:** 33 dita-eval errors with `Failed to create default temp directory`. This is a **sandbox artefact**: Java's macOS temp path is not writable here. Rerun with `-DargLine=-Djava.io.tmpdir=$TMPDIR/junit`: all pass. It will not occur on the developer machine.
2. **First run:** one real assertion failure in `ContextBuilderTest`. It was **edited by Shuri at 11:24:46, during the run**. Reviewed: the original expectation ignored messages already deleted at `as_of`. The fix is **correct, not a weakening**: deletion-as-of has its own boundary test. Three reruns pass.

## 2. Verification of the 10 requirements

| # | Requirement | Verdict | Evidence |
|---|---|---|---|
| 1 | Runs fully offline | **YES** | dita-eval depends only on dita-core + jackson (no dita-muse, no HTTP client); `MuseAnswererStub.answer` always throws; no network classes in main |
| 2 | Gold cannot leak into model inputs | **YES (by code + tests)** | `RunnerFileAccess` (corpora/prompts read, results write) vs `ScorerFileAccess` (sealed); `CanaryGuard` scans every outgoing request, the mock's included; `LeakageTest` 10/10 (symlink into sealed refused, label-field names rejected, hashed canary trips) |
| 3 | Same questions and corpus for every arm | **YES** | `ContextBuilder.visible(corpus, as_of)` is the single pre-filter for all arms; one prompt file per split |
| 4 | Shared settings, prompt, verification | **YES** | one `ArmSettings.STAGE0` (T_ctx = 4000 chars, same k); one `AnswerPrompt`; `SymmetricVerifier` applied to every arm (raw + gated rows) |
| 5 | Implemented vs proposed distinguished | **YES** | `ArmId`: C-full is **absent** ("not built"); B is labelled **"B-weak control … NOT real B: no multilingual embedder"**; diagnostic arms are flagged; mock aggregates carry "MOCK ANSWERER: plumbing check only. NOT A RESULT." |
| 6 | Deletion scored separately per DEC-012 | **YES** | `deletion_track` E/L validated in `GoldValidator`; deletion is a separate gate; every arm gets the same pre-deleted input (no arm earns credit) |
| 7 | Semantic distinctions | **YES at answer level** | Claim fields `stance`, `qualifier` (+ `qualifier_required`), `is_current` (supersession), `commitment` ∈ offer/confirmed/none; `expected_behavior` CONFLICT vs ANSWER. Extraction-level scoring on `parents-c` (RFC-002) is **not** in Stage 0 (DEMO-INT WP9) |
| 8 | Deterministic, reproducible | **YES for metrics** | `Random(SEED)`; `Instant.now`/`nanoTime` only in metadata and timing (latency is inherently variable and reported separately); `MANIFEST.sha256` freezes the inputs; the scorer refuses a changed sealed gold |
| 9 | No live-result claims | **YES** | `eval/value-benchmark/results/` is empty; only mock runs exist |
| 10 | No live calls, no external data | **YES** | `SyntheticDataGate` 18/18 (phone/e-mail/ID/URL scans, `data/` refused, manifest checks); 174 synthetic corpus messages incl. decoys |

## 3. What Stage 0 measures, and what it cannot

**Offline today:**
- retrieval quality per arm against gold evidence (recall, precision, deletion and isolation gates);
- context construction;
- leakage and synthetic gates;
- scorer correctness;
- latency of the retrieval and indexing path.

**Requires live Muse:** every answer-quality metric:
- correctness, faithfulness, abstention, stance, commitment and supersession handling;
- injection resistance by the model;
- answer cost and latency.

**Dataset:**
- dev: 35 questions with sealed gold.
- **held-out test split: NOT CREATED** (`sealed/test/` has only a README). Any comparison that informs a decision needs it.

## 4. Missing implementations per arm

| Arm | Status |
|---|---|
| A-full / A-budget (Muse alone) | Context ready; **live answerer missing** (stub) |
| B (basic RAG) | **Only a weak control.** There is no multilingual embedder, so B is BM25 + char-n-gram. A win over B-weak is **not** a win over real basic RAG |
| C-today (current Dita) | Retrieval ready (BM25 + graph); answerer missing. **Uses no knowledge or extraction**, because those are not implemented. So it tests retrieval value only, not RFC-002 knowledge value |
| C-full | Not built (knowledge retrieval, supersession, embedder) |

## 5. Natasha's remaining blockers for any live benchmark call

_IDs renumbered 2026-10-29 (Natasha): benchmark N-44..N-51 are now N-67..N-74, because N-44..N-51 belong to ADR-001. N-52/53 keep their benchmark meaning. Registry: docs/security/finding-id-registry.md._

- **N-67 OPEN:** `scripts/muse-live.sh` has no benchmark mode (0 matches): the call count, per-call caps, total ceiling, corpus SHA-256 and model are not shown at approval.
- **N-68 code present:** `BudgetGuard` charges the worst case per call before it is made; dita-eval has no dita-muse dependency, so it cannot reach `resetProcessCountForTests`. **Pending Natasha re-review.**
- **N-69, N-73 code present + tested** (SyntheticDataGate, Leakage, Canary). **Pending re-review.**
- **N-70, N-71, N-72:** pending re-review. **N-52/53:** apply when reporting.
- DESIGN §12: Natasha must re-review the N-67..N-73 fixes before any approval request.

## 6. Cost of a controlled live comparison (estimates, not measured)

**Basis:** the measured run used 924 prompt + 2,846 completion tokens at low effort, $0.013. Prices are $1.25/M in and $4.25/M out (reasoning included). T_ctx is 4,000 chars (about 1,500–2,500 prompt tokens with Hebrew).

| Run | Calls | Typical | Worst-case ceiling (max_completion 4096) |
|---|---|---|---|
| Pilot on dev (A-full, B-weak, C-today × 12 stratified questions) | 36 | ≈ $0.45 | **$0.75** |
| Decision run on the held-out test (3 arms × ~30 questions, 1 pass) | 90 | ≈ $1.15 | **$1.90** |
| Variance check (2 repeats of the decision run) | 180 | ≈ $2.30 | $3.80 |

**Ingestion/extraction cost:** $0 API for C-today (rule-based indexing; compute time is reported by the harness). Extraction-based C-full would add about $0.013 per 10 messages: about $0.25 for the 174 corpus messages. Not applicable until C-full exists.
