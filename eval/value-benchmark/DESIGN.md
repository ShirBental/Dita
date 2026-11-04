# TASK-034: Dita Product Value Benchmark. Design and test plan (v0.1)

Status: **DESIGN ONLY. Nothing has been measured. No live Muse calls have been made for this benchmark.**
Authors: Bruce Banner (design), Tony Stark (feasibility), Natasha Romanoff (security, N-44..N-54), Peter Parker (decision rule). Integrated by Nick Fury, 2026-10-29.
All data is synthetic.

The goal is not to show that Dita wins. It is to find out whether, where and why Dita adds value over Muse Alone or Muse + basic RAG, once its full operating cost is counted.

---

## 0. What the code can actually do today (verified by Tony and Bruce against the source)

| Arm-C capability in the brief | In code today? |
|---|---|
| Hybrid retrieval | **Partly.** `Retrievers.build` = BM25 + graph expansion fused by RRF. There is **no dense/vector retriever** (`RetrievalSettings`: "Dense retrieval is not implemented"). |
| Dynamic conversation graph | Yes: `RuleLinkInferencer` + `GraphExpandingRetriever` (in-memory/Postgres links); `Neo4jProjection.expanding` exists but is not used by `Retrievers.build`. |
| Provenance verification | Partly: `AnswerVerifier` checks the id exists, is in scope, is not deleted, has a verbatim quote and the right author. It checks that a quote is present, **not that a claim is true**. |
| Temporal corrections | **No.** There is no supersedes relation, and corrections have no target (TASK-033 note 5). |
| Evidence-grounded pre-extracted knowledge | **No path to answers.** `MuseKnowledgeExtractor` output is not persisted, and no `Retriever` reads `store.knowledge()`. |
| Muse answer composition | **No.** Only `ExtractiveAnswerComposer` (no LLM) exists. |

So arm C is reported in two forms:
- **C-today:** BM25 + graph, no knowledge layer.
- **C-full:** the brief's arm. It needs knowledge retrieval, supersession and the shared embedder. Its rows stay empty until C-full exists, and **no claim is made for it before then**. Building C-full means changing the architecture, which the user has put on hold for now.

## 1. Arms (held constant: same corpus, questions, prompt, output schema, model and effort)

**Fixed for every arm:**
- Model `muse-spark-1.3`, `reasoning_effort=low` (pre-registered; MEDIUM is a sensitivity check on dev only), one `max_completion_tokens` cap, PROMPT_ONLY schema.
- `temperature` is sent only if it is documented (unverified today, see context.md).
- One system prompt. Only the context block differs between arms.

**Context format (every arm):** a chronological JSON array of
`{type:"message", id, author, sent_at, reply_to, forwarded, edited, text}`.
- It holds only messages with `sent_at ≤ as_of` that were not deleted at `as_of`.
- C may add `{type:"note", source_ids[], text}` entries. Notes cannot be cited; citations must be message ids.

**Output schema (every arm):**
`{behavior: ANSWER|UNKNOWN|CONFLICT|CLARIFY, answer, slots, claims:[{speaker, attributed_to, message_ids[], quotes[], stance, qualifier, is_current, commitment: offer|confirmed|none}], clarify_question}`.

**Verification symmetry (Tony risk 1):**
- The verifier logic (plus an `as_of` check) is applied when scoring **every** arm.
- Each arm gets a raw row and a "gated" row (verifier used as a filter), A included.
- The verifier is portable code, so on its own it does not count as Dita value.

| Arm | Small-context regime | Large-history regime |
|---|---|---|
| A, Muse Alone | full transcript up to `as_of` (fits by construction) | **A-budget:** newest messages within T_ctx (the same budget B and C get). **A-max:** newest messages up to the largest allowed input (min of 200k chars and the verified window). |
| B, Basic RAG | BM25 (same `Tokenizer`) + dense retrieval, fused by RRF; top-K messages with ±2 neighbours, packed to T_ctx, chronological | same |
| C-today | `Retrievers.build` (BM25 + graph RRF), T_ctx | same |
| C-full | C-today + dense retrieval (the same embedder as B) + knowledge notes + supersession | same (blocked) |

**Embedder:**
- None exists today. The minimum honest choice for B is a frozen, local, multilingual model with real Hebrew support (multilingual-E5 / BGE-M3 class), chosen on dev only, with its checksum recorded.
- Char-n-gram hashing is a labelled **weak control** and must not stand in for B.
- C-full must use the same embedder as B.

**Ablations** (all built by composing existing `Retriever`s and `RetrievalSettings`):
- C − graph (`graphEnabled=false`)
- C − knowledge (knowledge retriever left out of the fusion)
- C keyword-only (only possible once knowledge exists; today it equals `LexicalRetriever`, i.e. B without its semantic half)

**Oracles** (to separate retrieval errors from reasoning errors):
- **O-gold:** gold ∪ acceptable messages only.
- **O-gold+distractors:** the same, padded to T_ctx with random in-corpus messages.

**Backend:** the quality comparison uses the in-memory store; one parity run uses Vespa and Neo4j. The backends are **not** equivalent (different scoring and Hebrew tokenization), so every result records which backend actually ran.

## 2. Dataset

- **Corpora:**
  - Synthetic groups (parents, building committee, extended family) in Hebrew-heavy, English-heavy and mixed variants.
  - Each has an isolation-decoy sibling group with contradicting facts; it is never a query scope.
  - Filler is realistic chatter that reuses entities, not random noise.
  - All people, clinics and streets are **clearly fictional** (N-47).
- **Regimes:**
  - **Small:** 40–150 messages per corpus, so everything fits.
  - **Large:** 2,500–4,000 messages over 18–36 months, so the history exceeds A-max.
  - Large corpora are built by a **seeded, deterministic template generator**, not by Muse. Muse-written text would bias the result toward the model that wrote it.
- **Question mix** (test, per regime):
  - 25% plain lookups (memory should not help)
  - about 7.5% for each of the ten brief scenarios (spread recommendations; context-dependent; corrections; offer vs confirmed; split/reconnect; forwarded/non-independent; deleted/superseded; conflicting; multi-hop distant; UNKNOWN)
  - about 5% CLARIFY
  - all stratified by lexical overlap with gold
- **Labels:** the schema in `schema/label.schema.json` (to be written by Shuri), which extends `eval/BENCHMARK.md` with `split, regime, corpus_id, template_id, as_of, scenario[], overlap_bucket, answer_keys, forbidden_strings, gold_evidence{all_of, any_of}, superseded_ids, forbidden_ids, expected_claims[], must_not_claims[], deletion_track`.
- **Sizes:**
  - Target: dev 40 + test 200 per regime.
  - Hackathon minimum (Peter): the P0 scenarios × 8–10 items. That result is **directional**, not statistically conclusive.
- **Leakage controls** (N-50):
  - `prompts/` holds `{qid, corpus_id, as_of, question}` only.
  - `sealed/gold/` and `sealed/test/` are never on the runner's path.
  - The scorer runs afterwards on saved outputs.
  - SHA-256 hashes of the sealed files are recorded in `.avengers/tasks.md` before any run and rechecked at scoring time.
  - A canary token is planted in the gold files; any request body containing it fails the run.
  - These are procedural and application controls, **not a security boundary**.
- **Unseen test split:**
  - Authored from a template spec by someone who does not build or tune C (Peter or the user), with held-out templates and fresh names and vocabulary.
  - Hash-frozen, then run once per arm.
  - Any change after a run creates test-v2.

Worked dev examples (one Hebrew correction+conflict, one mixed offer→confirmation with a forward, one UNKNOWN with deletion+isolation) are in Bruce's design and go into `dev/` as seed items. Publishing an item burns it as test material.

## 3. Scoring

Deterministic metrics come first. They are identical for every arm.

- **Primary composite, "fully correct":** behaviour correct ∧ answer keys present ∧ no forbidden or superseded-as-current content ∧ ≥ 1 verified gold citation (or a correct UNKNOWN).
- **Also reported:**
  - behaviour macro-accuracy, evidence recall, citation precision
  - attribution: claim speaker = cited author; hearsay goes in `attributed_to`
  - temporal correctness, qualifier/conflict handling
  - UNKNOWN precision/recall **and** over-abstention (N-51: refusing must not score well)
  - format failures, including `finish_reason=length`, which count as wrong, never as dropped
- **Retrieval only, no model:** context recall, AllGold within T_ctx, stale-without-successor exposure, forbidden-in-context. For A, the gold coverage of its window.
- **Error attribution per failed item:**
  - gold not in context → retrieval error
  - gold in context but answer wrong → reasoning error
  - O-gold also wrong → reasoner limit or label error
  - gold in messages but note missing or wrong → extraction error (C-full)
- **Deletion and privacy** (N-51, following DEC-012). A, B and C get the **same pre-filtered input**, so having a store earns no credit:
  - **Track E** (deleted ≤ 5 min): no citation, quote or paraphrase of content found only in that message. If that was the only source, the answer must be UNKNOWN.
  - **Track L** (> 5 min): no citation or quote. Paraphrase is reported but not scored until the PO rules on C-3.
  - Cases (a)–(e): sole source; surviving duplicate; superseded; cross-group decoy; deleted injection.
  - Misses are reported next to violations.
  - Deletion is a **hard gate reported separately**, not part of the headline weight. A passes by construction, so the risk that memory adds shows up as a C liability, never a C win.
- **Injection** (N-48): ≥ 3 injection fixtures per corpus (Hebrew, mixed, fake system line). An arm must neither follow nor cite them.
- **LLM judge:** only for prose completeness on summary items, with prior approval.
  - Blinded and length-normalised.
  - κ ≥ 0.7 against 30 human labels.
  - Not Muse if another model is approved; if Muse is the judge, the report says so and labels it the weakest evidence.
- **Statistics:**
  - Paired by question: exact McNemar on the composite; cluster bootstrap by corpus (10k resamples, fixed seed).
  - Holm correction over C vs A and C vs B per regime.
  - **Meaningful** = ≥ 0.10 absolute, Holm p < 0.05, CI excluding 0.
  - **Tie** only via TOST at ±0.05. Anything else is **inconclusive**.
  - Per-scenario cells are descriptive only.

## 4. Cost, latency and storage (complete operating cost)

Prices (user-supplied, standard mode): P_in $1.25/M, P_out $4.25/M; output includes reasoning tokens.

- **Answer call:** `c_ans = P_in·I + P_out·O`.
- **A:** no preprocessing.
- **B:** answer cost + local embedding (CPU-s, $0 API) + index bytes.
- **C:** `c_C = c_ans,C + (E0 + M(t)) / Q(t)`
  - `E0 = N·c_x`. Ledger run 5 (n=1, about 10 messages): $0.0133 per call → c_x ≈ $0.0006–0.002 per message, **unmeasured at batch scale**.
  - `M(t) = t·λ·c_x·(1+ρ·w)`, where ρ·w is the re-extraction triggered by edits, corrections and deletions.
- **Grid:** λ ∈ {50, 300, 2000} msgs/day, q ∈ {1, 10, 100} questions/day, horizon 12 months. Report the break-even against A-max.
- **Infrastructure** (Postgres, Neo4j, Vespa): RAM, disk and CPU-hours. Dollar figures only if the user supplies prices. Report the service count as the complexity tax.
- **Latency:** one warm-up call, then p50/p95 over the test questions. Arm order randomised per question. Retrieval, model and verification times reported separately. Report freshness lag (message arrival → queryable).

## 5. Pre-registered decision rule (Peter; **PROPOSED, needs PO confirmation before any scoring**)

**Primary user-visible metric:** the weighted critical-error rate. A critical error is any of:
- a stale or superseded commitment reported as current
- an invented decision, or an inferred one presented as stated
- a preference treated as an approval
- a deleted or non-existent message cited
- a buried answer that is present in the history but missed

**Scenario weights (P0/P1), PROPOSED:**
- superseded/reversed 25%
- stated vs inferred 20%
- buried info, HE/EN 20%
- approval vs preference 15%
- split/merged threads 10%
- open questions/roll-up 10%

The unweighted and plain-lookup results are always reported next to the weighted ones.

**Outcomes:**
- **(a) Proven value:** on P0, C has
  - ≥ 50% fewer critical errors than the best of A and B, **and** ≥ 10 points fewer absolute (meaningful per §3)
  - paired wins:losses ≥ 2:1
  - no P0 scenario worse by more than 1 item
  - cost per question ≤ 2× B
  - p95 latency ≤ 1.5× B
- **(b) Scenario-specific value:** the bar is met only in some P0 scenarios. Keep only those capabilities, and first test whether a thin layer over B matches them.
- **(c) No proven value:** use the simplest arm within 5 points of the best. **Ties go to the simpler arm** (A < B < C).

Changing the product to (b) or (c) is a material change of vision. It needs **human approval** via a CR.

## 6. Bias audit (summary; Bruce's design has the full 17-item table)

1. Scenarios mirror Dita's features. Counter: 25% plain lookups; weights frozen before results.
2. Strawman A. Counter: A-full (small regime) and A-max (large regime).
3. Strawman B. Counter: a competent multilingual embedder shared with C, ±2 window, the same k and candidate depth.
4. Verifier or prompt applied only to C. Counter: symmetric verifier and one prompt.
5. Lexicons and link rules were fitted to the parents fixtures. Counter: held-out templates, fresh vocabulary.
6. C-only tuning. Counter: equal logged dev-trial budget per arm.
7. Extraction or infrastructure left out of cost. Counter: §4.
8. Deletion and isolation, where A passes trivially. Counter: they are framed as C risks.
9. Over-abstention. Counter: precision and over-abstention are reported.
10. Future leakage into extraction. Counter: replay to `as_of`.
11. Judge bias. Counter: blinding and the κ gate.
12. Backend confusion. Counter: the backend is recorded per result.

## 7. Results table (template, NOT RUN)

One table per regime; cells are mean [95% CI].

| Arm | Composite | Crit. err (wtd) | Behaviour | Evidence recall | Citation prec. | Attribution | Temporal | Qual./conflict | Deletion E/L | UNKNOWN P/R | Over-abstain | Context recall | p50/p95 ms | $/q answer | $/q amortised | Storage | Services | Backend |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A (A-budget / A-max) | NOT RUN | | | | | | | | | | | | | | | | 0 | n/a |
| B basic RAG | NOT RUN | | | | | | | | | | | | | | | | | |
| C-today | NOT RUN | | | | | | | | | | | | | | | | | |
| C − graph | NOT RUN | | | | | | | | | | | | | | | | | |
| C − knowledge | = C-today (no knowledge layer exists) | | | | | | | | | | | | | | | | | |
| C keyword-only | ≈ LexicalRetriever (needs knowledge to differ) | | | | | | | | | | | | | | | | | |
| C-full | BLOCKED (not built) | | | | | | | | | | | | | | | | | |
| O-gold / O-gold+distr. | NOT RUN | | | | | | | | | | | | | | | | | |

The report also includes a scenario × arm grid of composite scores and C−A / C−B deltas with Holm-adjusted p.

**Stage 0 (executed 2026-10-29, offline, retrieval-only, dev n=35, in-memory):** see [results/stage0-retrieval-dev.md](results/stage0-retrieval-dev.md).
- Context recall: A-budget 0.556, B-weak control 0.833, C-today 0.664, C − graph 0.608.
- With equal ±2 windows: B-weak 0.833 vs C-today 0.759. With no windows: 0.639 vs 0.664.
- This is **not** a value result:
  - The dev corpora were written by C's builder and use `RuleLinkInferencer` cue words.
  - B-weak is not a real B.
  - Each scenario has only 1–9 items.

## 8. Error taxonomy

- **R, retrieval:**
  - R1 gold missed (lexical, cross-language, out of budget, graph)
  - R2 stale evidence without its successor
  - R3 forbidden or deleted message in context
- **K, knowledge:**
  - K1 missed
  - K2 offer shown as confirmed
  - K3 hearsay shown as the speaker's view
  - K4 false contradiction or lost qualifier
  - K5 stale after correction or deletion
  - K6 forward counted as independent
- **M, model:**
  - M1 wrong synthesis with gold present
  - M2 temporal
  - M3 attribution
  - M4 over-abstained
  - M5 hallucinated an answer to an UNKNOWN question
  - M6 invalid citation
  - M7 format failure or truncation
- **Other:**
  - L label error
  - I infra or backend fallback
  - P followed an injected instruction

## 9. Hypotheses (Bruce's priors, NOT results)

- **H-a, small regime:** A ≈ C (tie or A slightly ahead). C costs more per question when few questions are asked.
- **H-b, large regime:** B and C beat A-budget by a wide margin. A-max stays competitive until the history exceeds its window, and it has the highest cost per question.
- **H-c, C-today vs B:** C-today ties or loses on cross-language and paraphrase questions (it has no vector retriever) and wins only on split/reconnect and missing-reply questions. The difference is probably inconclusive (< 0.10).
- **H-d, C-full vs B:** any gain is concentrated in corrections, superseded information, offer vs confirmed, and qualified recommendations (+0.05..0.15), with ties on plain lookups. It appears only if supersession and notes are wired in.
- **H-e, forwards and deletion:** C does no better than B on forwards (N-22). C is at risk on deletion under DEC-012's late-deletion rule.
- **H-f, abstention:** the gated arms gain on UNKNOWN and lose on over-abstention.
- **H-g, ablations:** removing the graph hurts only thread-structure items. Removing knowledge costs C-full most of its edge.

**Implication to test, not assume:** if H-c holds, Dita's value today rests on capabilities it does not yet have.

## 10. Run plan (each live stage needs separate, explicit user approval)

| Stage | Content | Muse calls | Estimated cost |
|---|---|---|---|
| 0 offline | Harness, file split, PII/synthetic gate, scorer, mock Muse, retrieval-only scoring of lexical, C-today and C − graph on dev | 0 | $0 |
| 1 pilot | 1 small corpus, 10 dev Qs × {A, B, C-today} + about 6 extraction calls | about 36 | $0.25–0.65 (cap $1) |
| 2 dev | 80 Qs × 7 arms + A-max on 40 large-regime Qs, plus dev extraction | about 600 | $8–20 |
| 3 test | 400 Qs, one shot, + 10% repeats | about 3,000 | $45–90 |

Estimates come from one ledger run (n=1) and are **not measurements**. The $150 credits are **not confirmed** for this account.

**Gates before stage 1** (Natasha). All are open today and **block any live run**:
- **N-44** (HIGH): a `benchmark` mode in `scripts/muse-live.sh`. The approval prompt must show call count, per-call input cap, output cap, total-cost ceiling, corpus SHA-256 manifest and model.
- **N-45** (HIGH): a dollar budget guard that charges each call at its worst case. The harness must sit outside the `app.dita.muse` package so it cannot call `resetProcessCountForTests`.
- **N-46** (HIGH): a gate that proves the data is synthetic. It scans for phone, e-mail, ID and URL patterns, enforces an allowlisted corpus directory and a hash manifest, and never reads `data/`.
- **N-50** (HIGH): question/gold file separation and the canary check.
- **N-47 / N-48 / N-49** (MEDIUM): fictional names, injection fixtures, report scans.
- **N-52 / N-53** (Notion): post only aggregates, hashes and paths, never corpora, gold labels or per-question answers. Nothing is posted as a result unless it was executed and verified.

## 11. Value proposition template (to be filled only from measured results)

> On [N] frozen synthetic group-chat scenarios ([HE/EN/mixed split]) weighted to target-user priorities, Dita reduced user-visible critical errors from [B%] (basic RAG) and [A%] (Muse alone) to [C%] ([Δ] points; paired wins/losses [W/L]), with the largest gain in [scenario] and no meaningful gain in [scenario]. This cost [$/question C vs B, amortised at λ, q] and [p95 C vs B], and adds [K] services. Limits: synthetic data, [n] items per scenario, [deterministic/judge share] grading, backend [x].

## 12. Open items and owners

- **PO:** confirm the §5 decision rule and weights (Peter's proposal); C-3 late-deletion paraphrase; whether C-full may be built (architecture change, on hold per the user).
- **Peter/user:** author the sealed test split (independent of C's builders).
- **Shuri:** Stage 0 harness (`dita-eval`, offline only).
- **Bruce:** embedder shortlist for B (G3; local, no paid service).
- **Natasha:** re-review the N-44..N-50 fixes before any approval request.
