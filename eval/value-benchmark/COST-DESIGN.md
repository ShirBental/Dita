# TASK-039: Cost efficiency and break-even evaluation (extends DESIGN.md; does not replace it)

Status: **DESIGN + OFFLINE TOOLING. No answer call has ever been made, so no per-question query cost is measured.**
Authors: Bruce Banner (model), Tony Stark (instrumentation), Natasha Romanoff (N-55..N-60). Integrated by Nick Fury, 2026-10-29.

Question: does Dita's structured memory lower the total cost of answering questions compared with B (Basic RAG) and A (Muse Alone), **at equal or better quality**?

## 0. Evidence inventory (provenance labels used everywhere)

| Label | Meaning | What exists today |
|---|---|---|
| **MEASURED** | executed live, usage read from the API response | Extraction run 5: 10 synthetic msgs, 924 prompt / 2846 completion (2218 reasoning, 628 visible) tokens, $0.013251, 9.88 s, finish=stop, 7 items. Run 4: 924 / 4096, finish=length, **$0.018563 paid with zero usable output**. Runs 2–3: connectivity only. |
| **MEASURED-OFFLINE** | executed locally, no model | Stage 0 context sizes in chars per arm, retrieval latency, index build time |
| **ESTIMATED** | derived from measured inputs under stated assumptions | chars→tokens conversion; answer output and reasoning tokens (no answer call exists) |
| **REPLAYED** | recorded model output injected by the harness | run-5 extraction items as C-rep notes (parents fixture only) |
| **SIMULATED** | invented or synthetic stand-ins | any knowledge notes for corpora other than the run-5 fixture |

Prices: $1.25/M input, $4.25/M output (reasoning included). These are user-supplied and not verified against Meta's pricing. Cached-input price: unknown.

## 1. Arms (same questions, model, effort, prompt, schema and scoring as DESIGN.md)

| Arm | Context | Status |
|---|---|---|
| A | history within a fixed budget (A-full small regime; A-budget / A-max large regime) | builder exists |
| B | retrieved original messages | **B-weak control only** (char-n-gram; there is no real embedder) |
| C-today | BM25 + graph over messages | exists |
| C-rep | knowledge notes (REPLAYED run-5 extraction) + cited source messages | eval-only. **Not product functionality**: Muse output is not persisted or retrieved in code |
| C-full | persisted structured knowledge retrieval | **not built**; no claims |

Notes cannot be cited (DESIGN §1), so C must still send the source messages. A note saves tokens only when it lets distractor messages be dropped.

## 2. Cost model (Bruce)

**Answer call cost:**
- c_ans = P_in·(S + q + ctx) + P_out·(V + R), where S is the fixed system prompt plus schema.
- A truncated call (finish=length) is paid in full, scored wrong and **not retried**.

**Extraction cost:**
- E0(N, b) = Σ over batches of [P_in·(H_x + Σt_m + overlap) + P_out·(V + R)].
- Truncation risk f inflates each batch by (f/(1−f))·worst-case.
- Run 5 gives only H_x + Σt_m = 924 at b = 10, so the fixed overhead cannot be separated from the per-message cost.

**Maintenance cost M:**
- **New messages:** ⌈Δn/b_inc⌉·c̄_b.
- **Corrections:** re-extract with the target in context. There is no correction-target field yet, so this earns no quality credit.
- **Edits:** re-extract the affected batch.
- **Early deletion (≤ 5 min, EARLY_PURGE):** derived items are removed. Rebuilding from surviving sources costs u_E·c̄_b (policy choice u_E ∈ {0,1}).
- **Late deletion (> 5 min):** items are flagged and excluded (D-1 interim). $0 API.

**Embeddings (B, C-full):** $0 API; CPU time reported separately.

**Infrastructure:** reported as service count, RAM, disk and CPU-hours. It is **never folded into dollars** unless the user supplies a $/month figure.

**Totals:**
- TotalCost_A(Q) = Σc_ans,A
- TotalCost_B(Q) = Σc_ans,B
- TotalCost_Ct(Q) = Σc_ans,Ct
- TotalCost_Cr(Q) = E0 + M + Σc_ans,Cr
- Amortised extraction per question = E0/Q.

**Uneven benefit across questions (no uniform saving):**
- E[c_ans,C] = s_r·c_replace + s_a·c_augment + s_f·c_fallback.
- The shares come from logged retrieval traces, not assumptions.
- Plain lookups are expected to fall back to C-today cost.
- A reduction in reasoning tokens from notes is **measured (ΔR), never assumed**.

**Cost per correct answer (CPCA):**
- CPCA_X = TotalCost_X / K_X.
- K_X counts answers that meet the DESIGN §3 "fully correct" composite and also have a verified gold citation, correct attribution and a passed deletion gate. A correct UNKNOWN counts.
- Unsupported, incorrect, misattributed or truncated answers count toward cost and **never** toward K.
- If K = 0, CPCA is shown as "∞ / no qualifying answers", never 0.

## 3. Break-even

- **Q\*(C vs B):** the smallest Q in {1, 5, 10, 25, 50, 100} where TotalCost_C(Q) ≤ TotalCost_B(Q) **and** quality_C ≥ quality_B − 0.05 (TOST).
- If no Q meets both conditions, the result is **"no quality-qualified break-even"**. A crossover on cost alone is reported as "cost-only".
- **Analytic form:** Q\* = (E0 + M) / (c̄_B − c̄_C). Only if c̄_B > c̄_C; otherwise none exists.
- **Steady state over time:** C stays ahead only if q·(c̄_B − c̄_C) > λ·c_x·(1 + ρw), where λ is messages/day and q is questions/day.
- Q\* is also reported against A.
- **Sensitivity** (estimated order of dominance):
  1. Answer reasoning tokens and the difference between arms
  2. Extraction reasoning (≈ 71% of run-5 cost)
  3. Truncation rate f
  4. Batch size and fixed overhead
  5. q/λ
  6. s_r
  7. chars per token (input only)
- **Preliminary framing without invented query costs:** the calculator reports the **required per-question saving** Δ_req(Q) = (E0 + M)/Q that C must achieve over B to break even. E0 comes from MEASURED run-5 usage, scaled with a 0.5×–2× scenario band (n=1, so this is not a CI). Δ_req is then set against the ESTIMATED input-only saving from Stage 0 context sizes, clearly labelled. Output-cost differences are unknown and reported as such.

## 4. Token estimation (ESTIMATED only)

- **Chars per token:** English 3.5–4.5, Hebrew 1.5–3.0, JSON envelope 3–4, mixed 2.0–4.5. Each item is estimated from its own Hebrew share.
- **Calibration:** run 5 gives one weak point, ≈ 4.4 request bytes/token (bytes, envelope included).
- **Answer output (assumption):**
  - Visible V ∈ [150, 700] tokens; reasoning R/V ∈ [1, 3.5], where 3.5 is run 5's ratio.
  - Truncation f ∈ [0, 0.2].
- Output likely dominates cost (≈ 91% of run-5 cost). Input-only comparisons therefore **overstate** differences between arms. Context-only and total input are reported separately.
- **MockAnswerer output is never used for cost.**

## 5. Single-datapoint limits

Run 5 is n=1: one corpus, one batch size, one effort level, one latency sample.
- There is no variance and no CI.
- Reasoning may grow faster than linearly with batch size.
- The item yield does not transfer to other corpora.
- c_x ≈ $0.0013/msg is carried as the scenario band $0.0006–0.0027.

## 6. Security and accounting gates (Natasha)

Open before any live run:
- **N-44 OPEN:** no benchmark mode in `muse-live.sh`.
- **N-45 PARTIAL:** BudgetGuard exists but is not wired, keeps its state only in memory, and has hard-coded prices.
- **N-46 PARTIAL:** the gate is not on the live path.
- **N-50 CLOSED offline:** extraction requests are not yet canary-checked.

New findings:
- **N-55 HIGH:** run reports contain evidence and model text. Recorded-usage files must be **numbers only** (allowlisted fields; a test fails on any other field). Never copy the raw report.
- **N-56 HIGH:** one experiment-wide, disk-saved, append-only budget ledger covering answers, extraction, warm-up and retries.
  - Each call is charged at its worst case (full max_completion_tokens) before sending.
  - The run stops on the first error. Nothing is re-sent.
- **N-57 MEDIUM:** every calculator input carries a provenance label. Worst-case figures are used for planning; actual figures are reported next to them, never in their place.
- **N-58 MEDIUM:** run the synthetic-data, canary/label and key scans over every usage, latency and break-even report.
- **N-59 MEDIUM:** extraction requests and replayed notes also go through CanaryGuard.
- **N-60 LOW:** drop or hash request_id in anything posted.

**Notion:** per-arm and per-stage aggregates, the break-even grid and charts labelled MEASURED / ESTIMATED / REPLAYED may be posted. Per-question records, text, request ids and corpora may not.

**Live-client trap (Tony):** `MuseClient.complete` builds its own body (`temperature:0`, its own token cap, the schema appended), so the body that is checked and hashed is not the body sent. A live answerer needs a small "send exact body" method in `dita-muse`. That is a **production change and needs approval**. It is not done.

## 7. Smallest controlled live experiment (proposed, NOT approved, NOT run): "Stage 1c"

- **Data:** one synthetic dev corpus of about 60 messages (hash-manifested) and 10 dev questions: 3 lookup, 2 correction/superseded, 2 cross-language, 1 offer/confirmed, 1 multi-hop, 1 UNKNOWN.
- **Answer calls:** 10 × {A-full, B-weak, C-today, C-rep} = 40.
- **Extraction calls:** 6 at b=10 + 3 at b=20 + 1–2 repeats = 9–11. The two batch sizes separate fixed from per-message cost; the repeats give variance.
- **Maintenance calls:** 1 incremental batch including a correction, and 1 re-extraction after an early deletion = 2.
- **Total:** about 53 calls, effort LOW, retries 0.
- **Cost:**
  - Worst case per answer ≈ $0.021 (3k input + 4096 output).
  - **Worst-case total ≈ $1.07; hard ceiling $1.25**, charged at worst case.
  - Expected roughly $0.3–0.6 (ESTIMATED).
- **What it measures:** usage per call, finish rates, latency, ΔR per question class, E0 at two batch sizes, M_new, M_del,E, and offline-scored correctness and attribution.
- **Prerequisites:**
  - Close N-44, N-45, N-46, N-50, N-55, N-56, N-58 and N-59.
  - Approve the live-client body change.
  - Natasha re-reviews.
  - The user types `approve` on a prompt that shows Natasha's 12-item checklist.

## 7b. Preliminary offline analysis (executed 2026-10-30)

Outputs: [results/cost-prelim.md](results/cost-prelim.md) and [charts/cost-vs-questions.svg](charts/cost-vs-questions.svg). Calculator: `dita-eval/…/cost/BreakEvenCalculator` (tested).

Inputs and their provenance:
- Run-5 extraction usage: MEASURED (n=1).
- Stage 0 context sizes: MEASURED-OFFLINE.
- Everything else: ESTIMATED.

Findings:
- **Input-only saving** of C-today vs B-weak ≈ $0.00104 per question.
- **Break-even on input alone:** Q ≈ 38 (25 messages), 76 (60 messages), 226 (151 messages), 3818 (3000 messages).
- **Unknown answer output:** the band ($0.0013–0.0134 per answer) is about 10× that saving, so the answer is decided by output and reasoning tokens. None have been measured.
- **Steady state:** fails in 8 of 9 (λ, q) cells.
- **Quality:** C-today recall is below B-weak, so there is no quality-qualified break-even.

## 8. Hypotheses (Bruce; NOT results)

- **H-$1:** in the small regime, C-rep has no quality-qualified break-even against B for Q ≤ 100.
- **H-$2:** answer cost is mostly output, so C is cheaper only if notes reduce reasoning tokens.
- **H-$3:** in chat groups λ ≫ q, so extracting every message fails the steady-state condition. Selective extraction would be required.
- **H-$4:** C-today is cheaper per question than B but recalls less (0.664 vs 0.833). A cheaper answer at lower quality is not a saving; CPCA decides.
- **H-$5:** against A-max in the large regime, B and C are both much cheaper per question.
- **H-$6:** any C advantage, if one exists, is in CPCA on corrections, offer/confirmed and cross-language questions.
