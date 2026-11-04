# TASK-046 semantic evaluation: statistical pre-registration

**Author:** Bruce Banner (2026-11-01), validated before any sealed data exists.
**Verification:** Nick Fury recomputed every critical count by exact binomial enumeration (plain Python; scipy is not installed). All values match except one: at n = 480 the maximum error count is **8** (power 0.945), not 7. That error was also in SEMANTIC-RELATION-EVAL-DESIGN.md. It does not affect the chosen n = 400.

**Approval status:** operator approved the evaluation on 2026-11-01. Three changes below still need her approval: the sealed size of 400 + 40, the E4 bound ≤ 5%, and the answer-gold κ ≥ 0.80. They are marked **[NEEDS APPROVAL]**.

## Arms
| Arm | What it is |
|---|---|
| A1 | Current rules, including round 3 (an experimental baseline, not a validated improvement) |
| A2 | Same-frame baseline |
| A3 | Separate Muse relation-classification call |
| A4 | Local NLI: cross-encoder/nli-deberta-v3-xsmall @ a1508764, sha256 7105da41… |
| A5 | A2 ∧ A3 |
| A6 | Muse structured semantic labelling during extraction (subject, property, value, stance, conditions, proposed relations) |

Every arm only proposes. The deterministic gate in `dita-semeval/.../RelationArms.gate` decides, and `ValidityReconciler` is the only validity writer.

## 1. Units of analysis
- **Primary unit:** a case.
  - A case is 2–4 messages, fixture items, one question and gold labels.
  - Each case has one target pair (its class), plus at least one at-risk claim: a gold-current claim on the earlier side of a pair from Dita's pre-filter.
  - The per-case outcome is a failure if any claim in the case fails. Cases are independently authored, so they are Bernoulli trials and exact CP/Wilson bounds apply. The per-case rate is at least the per-claim rate, so this is conservative.
  - Claim-level and pair-level rates are secondary.
- **Clustering:**
  - At most 3 cases per template id.
  - Cluster bootstrap over templates (B = 10,000, seed 46046) for E3 and E4.
  - For E1 and E2, events are listed per template and batch.

## 2. Primary metrics (end-to-end, after the gate)
- **E1, false invalidation.** Numerator: cases where any at-risk claim ends non-current (superseded, hidden or retired). Denominator: all valid cases. A gold-UNCERTAIN pair counts as gold-current.
- **E2, unsupported promotion.** Numerator: lure cases where the lure claim is stored or rendered as DECISION, CONFIRMED_COMMITMENT or OFFER, above its gold status. Denominator: lure cases.
- **E3, replacement detection.** Numerator: gold replaces-yes targets where the earlier claim is non-current and the later one is current. Denominator: gold replaces-yes targets, excluding gold-UNCERTAIN.
- **E4, incorrect answer.** Denominator: all valid questions; the answer is Dita's deterministic composition from the stored state. Numerator: answers that do any of the following:
  - (a) present a gold-non-current value as current, or rank it above the newer value without an "earlier / possibly updated" mark;
  - (b) present a gold-weaker act as a decision, commitment or offer;
  - (c) cite a non-current claim as current.
  - Omissions are reported separately as E4o.
- **A6 alignment.** A system claim matches a gold claim when it has the same message and speaker and a quote with token Jaccard ≥ 0.5 (aligner frozen on dev). At least 95% of at-risk claims must match, otherwise A6 is not evaluable on E1/E2 and fails. This stops an arm passing by extracting less.
- **Secondary:**
  - UNCERTAIN rate (≤ 20%);
  - spans: 100% verbatim, and ≥ 90% supportive on 100 sampled spans;
  - per-arm pair FSR, recall and κ;
  - latency p50/p95 per pair and per case;
  - actual cost per case;
  - determinism.

## 3. Confidence methods
- **E1, E2, E4:** one-sided 95% Clopper–Pearson upper bound, the 0.95 quantile of Beta(x+1, n−x).
- **E3:** one-sided Wilson lower bound with z = 1.645.
- **Arm comparisons:** exact two-sided McNemar on per-case outcomes.
- **Within an arm:** all primary endpoints must pass (an intersection–union test), so no correction is applied.
- **Holm family, on E3:** {A6 vs A3, A3 vs A2, A6 vs A2}. Other comparisons are descriptive.
- **Adopted arm:** it must also pass a Bonferroni E1 bound (α/5, at most 4 errors at n = 400). If it does not, adoption is provisional and shadow-only until a new sealed set confirms it.

## 4. Sample size (exact values verified by Fury)
**E1 / E2: CP upper bound ≤ 3%**

| Cases (n) | Max errors to pass | Power at a true 1% |
|---|---|---|
| 200 | 1 | 0.405 |
| 300 | 3 | 0.647 |
| 320 | 4 | 0.781 |
| 350 | 5 | 0.859 |
| 360 | 5 | 0.845 |
| **400** | **6** | **0.890** |
| 480 | 8 | 0.945 |

**E3: Wilson lower bound ≥ 0.65 and point ≥ 0.75**

| n | Successes needed | Power at a true 0.80 |
|---|---|---|
| 60 | 46 | 0.793 |
| 80 | 60 | 0.893 |
| **100** | **75** | **0.913** |

**E4: [NEEDS APPROVAL] CP upper bound ≤ 5%** instead of 3%.
- At n = 400 a 5% bound passes up to 12 errors: power 0.938 at a true 2%, 0.576 at 3%.
- A 3% bound passes only 6 errors, which is power 0.311 at 2%. E4 combines several harms, so 3% is underpowered.

**McNemar power:**
- 20 vs 5 discordant cases gives p = 0.0041, which is significant after Holm.
- 15 vs 5 gives p = 0.0414, which is not.
- So a gain of about 15 points is detectable, but 10 points is not: an arm with a +10 point estimate and no significant test is **inconclusive**.

**Subtype rule:** with n = 40 per subtype, 3 or more errors means a point FSR above 5%, and the subtype fails.

## 5. Allocation: [NEEDS APPROVAL] 400 sealed + 40 sealed reserve (15 of them replaces), replacing 640 pairs
| Class | Sealed | Dev |
|---|---|---|
| Replaces: value change / option→decision / offer→confirmation / reversal / withdrawal | 100 (5 × 20) | 30 |
| Restatement / addition / conditional / scoped | 4 × 40 | 4 × 10 |
| Different property, same subject | 60 | 15 |
| Different subject (id collision) | 40 | 15 |
| Status genuine | 40 | 20 |

- **Status-weaker lures are a cross-cut, not a class.**
  - Six phenomena: negation, condition, question, trimmed quote, reported speech, and a "Decision:" prefix on an option.
  - They appear in 360 sealed cases (6 × 60) and 100 dev cases.
  - A lure is never on the later side of a replaces-yes target.
- **Dev:** 120 cases plus at most 12 existing Codex/Bruce counterexamples, used only to select prompts and thresholds. Builders may read dev; they never read sealed.

## 6. Muse budget ($10 approved; estimates, not measurements)
**Per case:**
- **A6:** about 1,085 output and 400 input tokens. Expected $0.0051, ceiling $0.0077.
- **A3:** 1.8 pairs × (350 output + 150 input tokens). Expected $0.0030, ceiling $0.0049.

**Runs per arm** (66 calls at 10 cases per call):
- dev: 4 prompt versions × 40 cases = 160 case-runs;
- sealed: 400 case-runs;
- stability: 2 × 50 = 100 case-runs.
- Total 660 case-runs.

**Totals:**

| | Expected | Ceiling |
|---|---|---|
| A6 | $3.37 | $5.08 |
| A3 | $1.98 | $3.23 |
| A3 + A6 | $5.35 | $8.31 |

**Hard stop:**
- Sub-caps: A6 $5.50, A3 $3.50.
- Before every call: if the total so far plus that call's worst case would exceed **$9.50**, stop.
- If `usage` is null, the call is charged at its ceiling.
- If the first 4 dev batches cost more than the per-case ceiling: stop and re-approve.

## 7. Rules
- **Invalid cases** are decided before any arm runs, from text and gold only:
  - the pre-filter does not produce the target pair;
  - the question is unanswerable;
  - the schema or spans are invalid;
  - the case duplicates dev or exceeds the template cap;
  - operator's audit flags it.

  An invalid case is replaced from the reserve in id order. If the reserve runs out, n shrinks and the thresholds are recomputed. After arms run nothing is excluded: an error or invalid output counts as UNCERTAIN, meaning no relation.
- **Agreement (κ):** REPLACES ≥ 0.75, SAME_PROPERTY ≥ 0.70, status ≥ 0.75, and **[NEEDS APPROVAL]** answer gold ≥ 0.80. PABAK is also reported.
  - Below target: guidelines are revised from dev disagreements only, then fresh instances relabel.
  - At most 2 cycles; after that the endpoint is not evaluable.
- **operator's audit:** 40 sealed + 12 dev cases, stratified. More than 2 disagreements in 40 triggers full re-adjudication (this catches a 10% gold-error rate with probability 0.78). Gold is hashed and frozen before any arm runs.
- **Budget cap:** if the cap stops a run, results are partial with the actual n, never extrapolated. Only a fail that is already certain may be declared.
- **Adoption rules:**
  - An arm passes only if all of these pass: E1, E2 and E4 bounds; E3 (Wilson and point); the subtype rule; A6 alignment; spans; and determinism.
  - **Determinism:** local arms must be byte-identical across runs. Muse needs ≥ 97% agreement and no flip between yes and no.
  - **A3 or A6** also needs at least +10 points over A2 and a Holm-significant McNemar.
  - **A6 vs A3:** if both pass, choose A6 when its E3 is within 5 points of A3's and its cost is no higher.
  - **No arm passes:** no automatic supersession.
  - **Any adoption requires operator's approval.**
