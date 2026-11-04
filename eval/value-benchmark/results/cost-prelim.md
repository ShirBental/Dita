# TASK-039: preliminary cost analysis (offline, ESTIMATED – no answer call measured)

**Status: ESTIMATED. No answer call has ever been made, so no per-question answer cost, answer quality or CPCA is measured. Extraction figures come from one live call (run 5, n=1). Nothing below says which arm costs less in practice; it reports what the numbers show under the stated assumptions.**

Prices: $1.25/M input, $4.25/M output incl. reasoning (user-supplied, unverified). Cached-input price: unknown (cached tokens charged at the input price). Infrastructure: unpriced (no $/month supplied), never folded into dollars. Maintenance M = 0 here (static corpus).

Provenance labels: MEASURED (live API usage), MEASURED_OFFLINE (local, no model), ESTIMATED (derived under stated assumptions). Nothing here is REPLAYED or SIMULATED.

## 1. Measured extraction (run 5, n=1) and truncation waste (run 4)

| Figure | Value | Provenance |
|---|---|---|
| Run 5 prompt / completion tokens | 924 / 2846 (reasoning 2218, visible 628) | MEASURED |
| Run 5 cost (10 synthetic msgs, b=10, effort low) | $0.0132505 (ledger $0.013251) | MEASURED |
| Run 5 latency | 9884 ms | MEASURED |
| Run 5 reasoning share / output share of cost | 71.1% / 91.3% | MEASURED |
| c_x, extraction cost per message | $0.00133 (n=1, no variance) | MEASURED |
| c_x scenario band (0.5x to 2x, not a CI) | $0.000663 to $0.00265 | ESTIMATED |
| Run 4 cost, finish=length, zero usable output | $0.018563 | MEASURED |
| Truncation inflation per batch at f = 0.05 / 0.1 / 0.2 (f/(1-f) x run-4 worst case) | $0.000977 / $0.00206 / $0.00464 | ESTIMATED |
| Ledgered live usage, runs 2-5 (4 records; connectivity / extraction) | $0.000879 / $0.0318 (total $0.0327) | MEASURED |
| Unledgered connectivity call (cap 1024, 25 / 101 tokens), not in the total above | $0.000461 | MEASURED |

Recorded live usage covers runs 2 to 5, matching the tasks.md cost ledger (usage/recorded-usage.jsonl). Run 1 has no recorded usage and is omitted. One further real connectivity call appears in the dita-muse connectivity report but not in the tasks.md ledger; it is kept in usage/unledgered-usage.jsonl and reported on its own row.

## 2. Extraction E0 (ESTIMATED, scaled from run 5)

Batch model, b = 10: each batch is charged 92.4 input tokens per message and 2846 output tokens per batch (run 5 cannot separate fixed overhead from per-message input, so H_x = 0 and a partial batch pays full output). Each group is batched separately. The band is 0.5x to 2x of the central value. The f = 0.2 column adds (f/(1-f)) x the worst-case batch (4096 output tokens).

| Corpus | Messages | Batches | E0 central | E0 band | E0 at f = 0.2 | Provenance |
|---|---|---|---|---|---|---|
| parents-b | 25 | 3 | $0.0392 | $0.0196 to $0.0783 | $0.0530 | ESTIMATED |
| building-he | 42 | 5 | $0.0653 | $0.0327 to $0.1307 | $0.0883 | ESTIMATED |
| club-mixed | 42 | 5 | $0.0653 | $0.0327 to $0.1307 | $0.0883 | ESTIMATED |
| family-en | 42 | 5 | $0.0653 | $0.0327 to $0.1307 | $0.0883 | ESTIMATED |
| dev corpora total (4 groups) | 151 | 18 | $0.2352 | $0.1176 to $0.4703 | $0.3179 | ESTIMATED |
| scenario 60 msgs (Stage 1c) | 60 | 6 | $0.0795 | $0.0398 to $0.1590 | $0.1073 | ESTIMATED |
| scenario 100 msgs | 100 | 10 | $0.1325 | $0.0663 to $0.2650 | $0.1789 | ESTIMATED |
| scenario 3000 msgs (large regime) | 3000 | 300 | $3.9751 | $1.9876 to $7.9503 | $5.3674 | ESTIMATED |

Decoy groups are never queried and are not counted.

## 3. Required per-question saving, Delta_req(Q) = (E0 + M) / Q (ESTIMATED, M = 0)

| Corpus | Q=1 | Q=5 | Q=10 | Q=25 | Q=50 | Q=100 |
|---|---|---|---|---|---|---|
| parents-b | $0.0392 ($0.0196 to $0.0783) | $0.00783 ($0.00392 to $0.0157) | $0.00392 ($0.00196 to $0.00783) | $0.00157 ($0.000783 to $0.00313) | $0.000783 ($0.000392 to $0.00157) | $0.000392 ($0.000196 to $0.000783) |
| building-he | $0.0653 ($0.0327 to $0.1307) | $0.0131 ($0.00653 to $0.0261) | $0.00653 ($0.00327 to $0.0131) | $0.00261 ($0.00131 to $0.00523) | $0.00131 ($0.000653 to $0.00261) | $0.000653 ($0.000327 to $0.00131) |
| club-mixed | $0.0653 ($0.0327 to $0.1307) | $0.0131 ($0.00653 to $0.0261) | $0.00653 ($0.00327 to $0.0131) | $0.00261 ($0.00131 to $0.00523) | $0.00131 ($0.000653 to $0.00261) | $0.000653 ($0.000327 to $0.00131) |
| family-en | $0.0653 ($0.0327 to $0.1307) | $0.0131 ($0.00653 to $0.0261) | $0.00653 ($0.00327 to $0.0131) | $0.00261 ($0.00131 to $0.00523) | $0.00131 ($0.000653 to $0.00261) | $0.000653 ($0.000327 to $0.00131) |
| dev corpora total (4 groups) | $0.2352 ($0.1176 to $0.4703) | $0.0470 ($0.0235 to $0.0941) | $0.0235 ($0.0118 to $0.0470) | $0.00941 ($0.00470 to $0.0188) | $0.00470 ($0.00235 to $0.00941) | $0.00235 ($0.00118 to $0.00470) |
| scenario 60 msgs (Stage 1c) | $0.0795 ($0.0398 to $0.1590) | $0.0159 ($0.00795 to $0.0318) | $0.00795 ($0.00398 to $0.0159) | $0.00318 ($0.00159 to $0.00636) | $0.00159 ($0.000795 to $0.00318) | $0.000795 ($0.000398 to $0.00159) |
| scenario 100 msgs | $0.1325 ($0.0663 to $0.2650) | $0.0265 ($0.0133 to $0.0530) | $0.0133 ($0.00663 to $0.0265) | $0.00530 ($0.00265 to $0.0106) | $0.00265 ($0.00133 to $0.00530) | $0.00133 ($0.000663 to $0.00265) |
| scenario 3000 msgs (large regime) | $3.9751 ($1.9876 to $7.9503) | $0.7950 ($0.3975 to $1.5901) | $0.3975 ($0.1988 to $0.7950) | $0.1590 ($0.0795 to $0.3180) | $0.0795 ($0.0398 to $0.1590) | $0.0398 ($0.0199 to $0.0795) |

## 4. Estimated input-only cost per question (ESTIMATED from MEASURED_OFFLINE context sizes)

S (fixed system prompt + schema + empty user template) = 2278 chars (1470 prose + 808 schema JSON), MEASURED_OFFLINE from AnswerPrompt; 529 to 689 tokens, ESTIMATED. Chars per token: Hebrew 1.5 to 3.0, other prose 3.5 to 4.5, JSON envelope 3 to 4, applied to each item by its own Hebrew share. Question and as_of are included. Server chat-template tokens are unknown and not counted. All 140 (question, arm) contexts were rebuilt from the corpora and matched the Stage 0 char counts exactly.

| Arm | Context chars mean | Hebrew share of message text | Context tokens | Input tokens (S + q + context) | Input $ per question | Provenance |
|---|---|---|---|---|---|---|
| A-full | 6613 | 26.1% | 1653 to 2288 | 2196 to 2998 | $0.00275 to $0.00375 | chars MEASURED_OFFLINE; tokens and $ ESTIMATED |
| A-budget | 3904 | 22.8% | 972 to 1334 | 1514 to 2044 | $0.00189 to $0.00256 | chars MEASURED_OFFLINE; tokens and $ ESTIMATED |
| B-weak control | 3779 | 25.9% | 944 to 1305 | 1487 to 2015 | $0.00186 to $0.00252 | chars MEASURED_OFFLINE; tokens and $ ESTIMATED |
| C-today | 993 | 20.5% | 246 to 337 | 788 to 1047 | $0.000986 to $0.00131 | chars MEASURED_OFFLINE; tokens and $ ESTIMATED |

**Answer output cost is UNKNOWN and NOT a measurement.** Assumption band: V in [150, 700] visible tokens, R/V in [1, 3.5], so 300 to 3150 output tokens = $0.00128 to $0.0134 per answer, for every arm. The width of this band ($0.0121) is larger than every input difference in the table above, so input-only comparisons cannot settle the sign of a total-cost difference.

## 5. Comparison table (A / B / C)

| Arm | Context chars mean | Input $ per question | Output $ per question | Extraction E0 (dev corpora) | Context recall | CPCA | Provenance |
|---|---|---|---|---|---|---|---|
| A-full | 6613 | $0.00275 to $0.00375 | $0.00128 to $0.0134 (UNKNOWN) | none | 1.000 | not computable | chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band |
| A-budget | 3904 | $0.00189 to $0.00256 | $0.00128 to $0.0134 (UNKNOWN) | none | 0.556 | not computable | chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band |
| B-weak control | 3779 | $0.00186 to $0.00252 | $0.00128 to $0.0134 (UNKNOWN) | none | 0.833 | not computable | chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band (B-weak is a weak control, not real B) |
| C-today | 993 | $0.000986 to $0.00131 | $0.00128 to $0.0134 (UNKNOWN) | none | 0.664 | not computable | chars and recall MEASURED_OFFLINE; $ ESTIMATED; output = assumption band |
| C-rep (proxy, not built) | as C-today + notes (notes not counted) | $0.000986 to $0.00131 + note tokens | $0.00128 to $0.0134 (UNKNOWN) | $0.2352 ($0.1176 to $0.4703) | not measured | not computable | all ESTIMATED; no notes are replayed in this analysis |

## 6. Delta_req vs estimated input saving (ESTIMATED; input only; C-rep context assumed equal to C-today's)

Estimated input saving per question: B-weak minus C-today = $0.000873 to $0.00121; A-full minus C-today = $0.00176 to $0.00244. C-today itself needs no extraction, so for C-today this saving has no E0 to recover. The rows below ask how many questions a C-rep whose context were as small as C-today's would need before its input saving over B-weak repaid E0.

| Corpus | E0 central | Q where Delta_req = saving (band) | Q at central values | First grid Q with Delta_req(central) <= saving(mid) | Provenance |
|---|---|---|---|---|---|
| parents-b | $0.0392 | 16.2 to 89.8 | 37.6 | Q=50 | ESTIMATED |
| building-he | $0.0653 | 27.0 to 150 | 62.7 | Q=100 | ESTIMATED |
| club-mixed | $0.0653 | 27.0 to 150 | 62.7 | Q=100 | ESTIMATED |
| family-en | $0.0653 | 27.0 to 150 | 62.7 | Q=100 | ESTIMATED |
| dev corpora total (4 groups) | $0.2352 | 97.2 to 539 | 226 | none for Q <= 100 | ESTIMATED |
| scenario 60 msgs (Stage 1c) | $0.0795 | 32.9 to 182 | 76.4 | Q=100 | ESTIMATED |
| scenario 100 msgs | $0.1325 | 54.8 to 304 | 127 | none for Q <= 100 | ESTIMATED |
| scenario 3000 msgs (large regime) | $3.9751 | 1643 to 9111 | 3818 | none for Q <= 100 | ESTIMATED |

What the numbers show: Delta_req(Q) falls as 1/Q while the estimated input saving is a constant per question, so on input alone a crossover exists for every corpus size, at a Q that grows linearly with corpus size. This ignores (a) note tokens that C-rep would add, (b) the output band, which is wider than the saving, and (c) quality: C-today's recall is lower than B-weak's (section 8).

## 7. Calculator demonstration (60-message scenario, ESTIMATED)

C-rep proxy (C-today input) vs B-weak control and A-full; output tokens set equal for all arms at the band midpoint (1725), so output cancels in Q*; quality unknown; M = 0; infrastructure unpriced.

| Comparison | Analytic Q* | Grid Q* | Verdict | Provenance |
|---|---|---|---|---|
| C-rep (proxy) vs B-weak control | 76.4 | 100 | no quality-qualified break-even; cost-only crossover at Q=100 (quality unknown) | ESTIMATED |
| C-rep (proxy) vs A-full | 37.9 | 50 | no quality-qualified break-even; cost-only crossover at Q=50 (quality unknown) | ESTIMATED |

Steady state vs B-weak, q x (c_B - c_C) > lambda x c_x x (1 + rho_w), rho_w = 0.1, c_x central:

| lambda (msgs/day) | q (questions/day) | q x saving per day | lambda x c_x x (1 + rho_w) | C keeps lead | Provenance |
|---|---|---|---|---|---|
| 50 | 1 | $0.00104 | $0.0729 | no | ESTIMATED |
| 50 | 10 | $0.0104 | $0.0729 | no | ESTIMATED |
| 50 | 100 | $0.1041 | $0.0729 | yes | ESTIMATED |
| 300 | 1 | $0.00104 | $0.4373 | no | ESTIMATED |
| 300 | 10 | $0.0104 | $0.4373 | no | ESTIMATED |
| 300 | 100 | $0.1041 | $0.4373 | no | ESTIMATED |
| 2000 | 1 | $0.00104 | $2.9151 | no | ESTIMATED |
| 2000 | 10 | $0.0104 | $2.9151 | no | ESTIMATED |
| 2000 | 100 | $0.1041 | $2.9151 | no | ESTIMATED |

One-at-a-time sensitivity of analytic Q* (C-rep proxy vs B-weak); base Q* = 76.4:

| Parameter | Low setting | Q* low | High setting | Q* high | Total C - Total B at Q=25, low / high | Steady-state margin per day (lambda=300, q=10), low / high |
|---|---|---|---|---|---|---|
| answer output tokens (all arms) | x0.5 | 76.4 | x2 | 76.4 | $0.0535 / $0.0535 | -$0.4269 / -$0.4269 |
| answer output tokens (candidate only; arm difference) | x0.8 | 31.7 | x1.2 | none | $0.0168 / $0.0901 | -$0.4122 / -$0.4415 |
| extraction output tokens (reasoning) | x0.5 | 41.5 | x2 | 146 | $0.0172 / $0.1260 | -$0.2273 / -$0.8260 |
| extraction truncation rate f | f=0 | 76.4 | f=0.2 | 103 | $0.0535 / $0.0813 | -$0.4269 / -$0.5800 |
| extraction batch size b | b/2 | 146 | 2b | 41.5 | $0.1260 / $0.0172 | -$0.8260 / -$0.2273 |
| extraction fixed overhead H_x | H_x=0 | 76.4 | H_x+500 tokens | 80.0 | $0.0535 / $0.0572 | -$0.4269 / -$0.4475 |
| q/lambda | x0.5 | 76.4 | x2 | 76.4 | $0.0535 / $0.0535 | -$0.4321 / -$0.4164 |
| chars per token (input only) | x1.25 (fewer tokens) | 93.8 | x0.8 (more tokens) | 62.4 | $0.0573 / $0.0487 | -$0.4213 / -$0.4338 |

With output equal across arms, output-token rows leave Q* unchanged. That is an artefact of the assumption, not a finding: COST-DESIGN ranks answer reasoning tokens and their difference between arms as the dominant unknown. A 20% change in the candidate's output tokens alone (row 2) moves the result more than any input-side parameter.

## 8. Quality guardrail (MEASURED_OFFLINE, retrieval only)

| Arm | Context recall | AllGold within T_ctx | Provenance |
|---|---|---|---|
| A-full | 1.000 | 1.000 | MEASURED_OFFLINE |
| A-budget | 0.556 | 0.482 | MEASURED_OFFLINE |
| B-weak control | 0.833 | 0.778 | MEASURED_OFFLINE |
| C-today | 0.664 | 0.556 | MEASURED_OFFLINE |
| C-graph-off | 0.608 | 0.482 | MEASURED_OFFLINE |
| B-weak no-window (diag) | 0.639 | 0.482 | MEASURED_OFFLINE |
| C-today +/-2 (diag) | 0.759 | 0.704 | MEASURED_OFFLINE |

This is whether gold evidence reaches the context, not whether an answer is correct (dev n=35, synthetic, written by C's builder; B-weak is not real B). **No CPCA can be computed until answers are produced and scored**: K is unknown for every arm. A lower input cost at lower recall is not a saving; CPCA decides.

## 9. Measured vs estimated breakdown

| Quantity | Status | Source |
|---|---|---|
| Extraction usage and cost (one call, b=10) | MEASURED (n=1) | usage/recorded-usage.jsonl run 5 |
| Truncated extraction cost (one call) | MEASURED (n=1) | usage/recorded-usage.jsonl run 4 |
| c_x per message | MEASURED (n=1); band ESTIMATED | run 5 / 10 messages |
| E0 for other corpus sizes, batch effects, fixed overhead | ESTIMATED | linear scaling of run 5 |
| Live spend, runs 2-5 (ledger) and one unledgered connectivity call | MEASURED | usage/recorded-usage.jsonl, usage/unledgered-usage.jsonl |
| Context chars per arm, retrieval recall | MEASURED_OFFLINE | results/stage0-retrieval-dev.json |
| S (system prompt + schema) chars | MEASURED_OFFLINE | AnswerPrompt |
| Input tokens per question | ESTIMATED (range) | chars per token assumptions |
| Answer output and reasoning tokens | UNKNOWN (assumption band) | no answer call exists |
| Answer cost per question, total cost per arm | NOT MEASURED | needs a live answer run |
| Answer quality, K, CPCA | NOT MEASURED | needs scored answers |
| Maintenance M, infrastructure $ | NOT MODELLED here (0 / unpriced) | calculator supports both |

## 10. Limits and fairness notes

- One extraction call (n=1): no variance, one batch size, one effort level; reasoning may grow faster than linearly with batch size.
- The same output band is applied to every arm. If notes change reasoning tokens (Delta_R), that difference, not input size, decides cost; it is unmeasured.
- The C-rep proxy uses C-today's context and does not count note tokens, which favours C-rep.
- B-weak is a char-n-gram weak control, not the real arm B; the dev corpora were written by C's builder.
- Input-only figures overstate differences between arms because output is about 91% of the measured extraction cost.
- Chart: charts/cost-vs-questions.svg (title and legend say ESTIMATED – no answer call measured).

Source: results/cost-prelim.json. Generated offline by app.dita.eval.cost.PreliminaryCostAnalysis.
