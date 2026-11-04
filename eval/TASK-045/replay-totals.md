# TASK-045 offline RECORDED replays: totals only (no model calls, in-memory, frozen scorer)

before = DEC-033 code; after = DEC-034 code (Related lines off); ablation = DEC-034 with Related lines on.

- after lab A: user-facing correct 5/15 · citation precision 0.41 (18/44) · all auto checks 7/15 · facts 6/11 · sources 8/15 · temporal 2/3 · qualifiers 6/7 · speaker attribution 3/5 · behaviour 13/15
- after lab B: user-facing correct 6/15 · citation precision 0.21 (21/100) · all auto checks 9/15 · facts 7/11 · sources 10/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 4/5 · behaviour 13/15
- after lab C: user-facing correct 9/15 · citation precision 0.52 (22/42) · all auto checks 9/15 · facts 8/11 · sources 9/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 5/5 · behaviour 13/15
- after holdout A: user-facing correct 4/15 · citation precision 0.35 (24/68) · all auto checks 5/15 · facts 7/12 · sources 8/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 3/6 · behaviour 14/15
- after holdout B: user-facing correct 4/15 · citation precision 0.31 (33/105) · all auto checks 7/15 · facts 8/12 · sources 12/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 5/6 · behaviour 14/15
- after holdout C: user-facing correct 4/15 · citation precision 0.60 (21/35) · all auto checks 6/15 · facts 9/12 · sources 8/15 · temporal 2/2 · qualifiers 2/6 · speaker attribution 6/6 · behaviour 15/15
- after holdout-v2 A: user-facing correct 3/15 · citation precision 0.40 (20/50) · all auto checks 6/15 · facts 7/12 · sources 7/15 · temporal 1/3 · qualifiers 2/3 · speaker attribution 3/6 · behaviour 13/15
- after holdout-v2 B: user-facing correct 4/15 · citation precision 0.30 (21/70) · all auto checks 7/15 · facts 7/12 · sources 8/15 · temporal 1/3 · qualifiers 3/3 · speaker attribution 3/6 · behaviour 13/15
- after holdout-v2 C: user-facing correct 6/15 · citation precision 0.50 (21/42) · all auto checks 7/15 · facts 6/12 · sources 9/15 · temporal 2/3 · qualifiers 2/3 · speaker attribution 4/6 · behaviour 15/15
- ablation lab A: user-facing correct 5/15 · citation precision 0.41 (18/44) · all auto checks 7/15 · facts 6/11 · sources 8/15 · temporal 2/3 · qualifiers 6/7 · speaker attribution 3/5 · behaviour 13/15
- ablation lab B: user-facing correct 6/15 · citation precision 0.21 (21/100) · all auto checks 9/15 · facts 7/11 · sources 10/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 4/5 · behaviour 13/15
- ablation lab C: user-facing correct 9/15 · citation precision 0.44 (23/52) · all auto checks 9/15 · facts 8/11 · sources 10/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 5/5 · behaviour 13/15
- ablation holdout A: user-facing correct 4/15 · citation precision 0.35 (24/68) · all auto checks 5/15 · facts 7/12 · sources 8/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 3/6 · behaviour 14/15
- ablation holdout B: user-facing correct 4/15 · citation precision 0.31 (33/105) · all auto checks 7/15 · facts 8/12 · sources 12/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 5/6 · behaviour 14/15
- ablation holdout C: user-facing correct 4/15 · citation precision 0.46 (28/61) · all auto checks 8/15 · facts 11/12 · sources 11/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 6/6 · behaviour 15/15
- ablation holdout-v2 A: user-facing correct 3/15 · citation precision 0.40 (20/50) · all auto checks 6/15 · facts 7/12 · sources 7/15 · temporal 1/3 · qualifiers 2/3 · speaker attribution 3/6 · behaviour 13/15
- ablation holdout-v2 B: user-facing correct 4/15 · citation precision 0.30 (21/70) · all auto checks 7/15 · facts 7/12 · sources 8/15 · temporal 1/3 · qualifiers 3/3 · speaker attribution 3/6 · behaviour 13/15
- ablation holdout-v2 C: user-facing correct 6/15 · citation precision 0.42 (23/55) · all auto checks 7/15 · facts 6/12 · sources 9/15 · temporal 2/3 · qualifiers 2/3 · speaker attribution 4/6 · behaviour 15/15
