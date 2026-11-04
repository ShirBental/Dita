> **Historical (TASK-055, 2026-11-04).** Kept as evidence; do not follow as current instructions. Current status: [../docs/capability-status.md](../docs/capability-status.md); index: [../docs/README.md](../docs/README.md).

# Dita retrieval benchmark v0 (spec by Bruce Banner, written by Fury)

Status: **spec and the first data drop only. Nothing has been measured.** All data is synthetic.

## Data
- `parents-a` = `dita-core/.../fixtures/parents-group.synthetic.txt` (9 msgs; target ~40)
- `parents-b` = `fixtures/parents-b.synthetic.txt` (25 msgs; target ~50) + `eval/questions-parents-b.jsonl` (12 Qs) + `eval/gold-links-parents-b.json`
- `building-residents` (target ~40, Hebrew-heavy): TODO
- `isolation-decoy` = `fixtures/other-group.synthetic.txt` (x1, y1–y3; target ~20). Never a query scope; reuses entities with contradicting facts.
- Target: ~150 messages and ~60 questions.

Label fields:
- `qid`, `group`, `lang`, `phenomenon[]`, `question`
- `gold_message_ids` (must cite)
- `acceptable_extra_ids` (neutral)
- `expected_behavior` (ANSWER, NOT_FOUND, CONFLICT or CLARIFY)
- `gold_link_pairs` ([later, earlier])
- optional: `superseded_ids`, `answer_keys` (alternative token sets), `forbidden_ids`

## Metrics (deterministic unless noted)
- **Recall@K** (primary K = 3 and 5; 10 is secondary) and **AllGold@K**.
- **Precision:**
  - context precision@K = |top-K ∩ (gold ∪ acceptable)| / K
  - citation precision and recall, computed after `AnswerVerifier`
- **Faithfulness:** FaithProxy = 1 when all of these hold:
  - every citation verifies
  - behaviour matches
  - an answer_keys set is present
  - no superseded message is cited as current

  An LLM judge (claim entailment) runs on a stratified sample of about 20 items **only with prior approval**.
- **Attribution:**
  - each citation's author and sentAt match the store
  - its stance and qualifier match `Stance`
  - every author name in the answer owns a cited message
- **Context reconstruction:**
  - inferred-link P/R/F1 against the gold inferred edges (either alternative counts for ambiguous pairs)
  - per-question share of gold pairs with both ends in the top K
- **Isolation leakage:** count of out-of-scope hits plus citations. Must be 0.
- **Behaviour accuracy:** macro-averaged over the 4 classes.
- **Latency:** 1 warm-up run, then 5 timed runs. Report p50 and p95 per retriever, with model time reported separately.
- **Cost:** tokens × price. Lexical, graph and extractive retrieval cost $0.

## Arms
1. Lexical BM25
2. Vector: local multilingual embedder (G3); char-n-gram hashing as a weak control
3. Graph-expanded (seeded by lexical and by vector)
4. Hybrid RRF
5. Alias table (Levi/לוי, Katz/כץ, Michal/מיכל, Peretz/פרץ) as a **separate, labelled** arm

Every result records which backend actually ran (Postgres fallback vs Neo4j/Vespa).

## Hypotheses (unmeasured)
- **H1:** Vector beats lexical on cross_language only with a multilingual embedder or an alias table.
- **H2:** Graph expansion raises Recall@5 on missing-reply, pronoun and resumed questions, and lowers precision.
- **H3:** Hybrid Recall@5 ≥ best single arm − 0.02.
- **H4:** Isolation leakage is 0 for every arm.

## Acceptance

**Hard gates** (block the demo):
- isolation leakage = 0
- 100% of citations verify
- superseded-as-current = 0
- isolation probes are 100% NOT_FOUND

**Targets:**
- Hybrid Recall@5 ≥ 0.85 and Recall@10 ≥ 0.92
- AllGold@10 ≥ 0.75
- cross_language Recall@10 ≥ 0.80
- citation precision ≥ 0.80
- FaithProxy ≥ 0.85
- behaviour accuracy ≥ 0.85, with CONFLICT recall ≥ 0.80
- inferred-link F1 ≥ 0.70
- retrieve p95 ≤ 300 ms on 150 messages
- end-to-end p95 ≤ 3 s with the model
- cost ≤ $0.002 per question (estimated; live runs need approval)

**Comparative claims** require a ≥ 0.10 Recall@5 gain on the hard subset and non-overlapping 95% bootstrap CIs (10k resamples, fixed seed). With about 60 items, differences below about 0.10 are probably noise.

## Code gaps blocking scoring
1. `GroundedAnswer` needs a `CLARIFY` outcome.
2. The `Stance` lexicon misses "wonderful" and "rough".
3. No vector retriever exists yet.
4. The scorer and harness have not been built (`dita-eval`).
