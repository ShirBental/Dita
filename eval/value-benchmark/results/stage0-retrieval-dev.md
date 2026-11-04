# TASK-034 Stage 0: retrieval-only results (dev)

**dev split, small regime, in-memory backend, retrieval-only, no model, n=35, synthetic.**

This measures whether gold evidence reaches the context. It does not measure answers, cost or product value. B-weak is a labelled weak control (BM25 + char-n-gram hashing). It is not the real arm B, which needs a multilingual embedder that does not exist yet. A-full is complete by construction in the small regime, and O-gold is a sanity row (recall = 1 by construction). Rows marked (diag) are not pre-registered arms. They cross the retriever with the +/-2 neighbour window, which DESIGN §1 gives only to B, so a reader can see how much of a recall difference comes from context volume rather than ranking.

Settings: T_ctx = 4000 chars of rendered context JSON, k = 8, candidate depth/retriever = 24, RRF k = 60.0, B neighbour window = +/-2, C neighbour window = 0, history access = FULL_GROUP_HISTORY.

| Arm | n | n with gold | Context recall | AllGold within T_ctx | Forbidden in ctx (items) | Superseded w/o successor | Injection msgs in ctx (items) | Visibility violations | Ctx msgs (mean) | Ctx chars mean / p95 | Retrieval p50 / p95 ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A-full | 35 | 27 | 1.000 | 1.000 (27/27) | 0 (0) | 0/5 | 11 (11) | 0 | 35.170 | 6613 / 7860 | 0.016 / 0.066 |
| A-budget | 35 | 27 | 0.556 | 0.482 (13/27) | 0 (0) | 0/5 | 8 (8) | 0 | 20.910 | 3904 / 3927 | 0.028 / 0.106 |
| B-weak control | 35 | 27 | 0.833 | 0.778 (21/27) | 0 (0) | 0/5 | 7 (7) | 0 | 19.970 | 3779 / 3989 | 0.336 / 0.808 |
| C-today | 35 | 27 | 0.664 | 0.556 (15/27) | 0 (0) | 0/5 | 3 (3) | 0 | 4.940 | 993 / 1642 | 0.283 / 0.531 |
| C-graph-off | 35 | 27 | 0.608 | 0.482 (13/27) | 0 (0) | 0/5 | 3 (3) | 0 | 3.800 | 781 / 1553 | 0.144 / 0.283 |
| B-weak no-window (diag) | 35 | 27 | 0.639 | 0.482 (13/27) | 0 (0) | 0/5 | 4 (4) | 0 | 7.400 | 1483 / 1741 | 0.320 / 0.545 |
| C-today +/-2 (diag) | 35 | 27 | 0.759 | 0.704 (19/27) | 0 (0) | 0/5 | 6 (6) | 0 | 14.430 | 2739 / 3956 | 0.302 / 0.665 |
| O-gold | 35 | 27 | 1.000 | 1.000 (27/27) | 0 (0) | 0/5 | 0 (0) | 0 | 3.910 | 771 / 1517 | n/a (scorer-side oracle) |

Index build (in-memory store replay to as_of: ingest + RuleLinkInferencer links + DeletionService deletions (corpus + decoy)): 5 (corpus, as_of) builds, 25 timed samples, p50 7.499 ms, p95 11.676 ms, max 11.703 ms. Timing: 1 warm-up + 5 timed runs per (question, arm); per (corpus, as_of) for index build; wall clock, single JVM, System.nanoTime.

## AllGold within T_ctx by scenario (descriptive only; cells are tiny)

| Scenario | n | A-full | A-budget | B-weak control | C-today | C-graph-off | B-weak no-window (diag) | C-today +/-2 (diag) | O-gold |
|---|---|---|---|---|---|---|---|---|---|
| ambiguous_pronoun | 3 | 1.000 | 1.000 | 0.667 | 0.667 | 0.000 | 0.000 | 0.667 | 1.000 |
| as_of_temporal | 1 | 1.000 | 0.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| conflicting | 3 | 1.000 | 0.000 | 0.667 | 0.333 | 0.333 | 0.333 | 0.667 | 1.000 |
| context_dependent_rec | 3 | 1.000 | 0.000 | 1.000 | 0.000 | 0.000 | 0.000 | 0.667 | 1.000 |
| correction | 4 | 1.000 | 0.750 | 1.000 | 0.750 | 0.750 | 0.750 | 0.750 | 1.000 |
| cross_language | 9 | 1.000 | 0.444 | 0.556 | 0.111 | 0.000 | 0.000 | 0.444 | 1.000 |
| deleted | 1 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| forwarded_non_independent | 1 | 1.000 | 0.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| injection | 9 | 1.000 | 0.556 | 0.667 | 0.667 | 0.667 | 0.667 | 0.667 | 1.000 |
| missing_reply_link | 5 | 1.000 | 0.800 | 0.600 | 0.200 | 0.000 | 0.000 | 0.800 | 1.000 |
| multi_hop_distant | 2 | 1.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 1.000 |
| offer_vs_confirmed | 2 | 1.000 | 1.000 | 0.500 | 1.000 | 1.000 | 1.000 | 0.500 | 1.000 |
| parallel_threads | 3 | 1.000 | 1.000 | 0.667 | 0.333 | 0.333 | 0.333 | 0.667 | 1.000 |
| plain_lookup | 3 | 1.000 | 0.333 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| resumed_conversation | 4 | 1.000 | 0.750 | 1.000 | 0.500 | 0.250 | 0.250 | 0.750 | 1.000 |
| split_reconnect | 2 | 1.000 | 1.000 | 1.000 | 1.000 | 0.500 | 0.500 | 1.000 | 1.000 |
| spread_recommendations | 2 | 1.000 | 0.000 | 1.000 | 0.000 | 0.000 | 0.000 | 0.500 | 1.000 |
| superseded | 3 | 1.000 | 0.667 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |

## Context recall by lexical-overlap bucket (question vs gold evidence tokens)

| Bucket | n | A-full | A-budget | B-weak control | C-today | C-graph-off | B-weak no-window (diag) | C-today +/-2 (diag) | O-gold |
|---|---|---|---|---|---|---|---|---|---|
| low | 10 | 1.000 | 0.650 | 0.750 | 0.267 | 0.117 | 0.200 | 0.550 | 1.000 |
| mid | 7 | 1.000 | 0.286 | 0.786 | 0.821 | 0.821 | 0.821 | 0.786 | 1.000 |
| high | 10 | 1.000 | 0.650 | 0.950 | 0.950 | 0.950 | 0.950 | 0.950 | 1.000 |

Source: `results/stage0-retrieval-dev.json` (per-item ids in `items`). Runner output generated 2026-10-29T16:22:16.902126Z, scored 2026-10-29T16:22:17.107394Z.
