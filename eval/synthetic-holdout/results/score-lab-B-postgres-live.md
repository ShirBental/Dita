# Synthetic holdout — Dita deterministic scoring (lab-B-postgres-live)

Frozen rubric sha256 691e796b203332247fdc0f460ff3798e6194c44570b5ab12599eb5e80e970884. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | When and where is the workday? | ANSWER | yes | NO | NO | - | - | NO | NO | 5004 5028 5016 5001 5018 5023 5033 5012 | 5004 5001 | must not give Saturday November 21 as the current date |
| 2 | Why was the workday date moved? | ANSWER | NO | yes | - | - | - | NO | NO | 5028 5001 5004 5024 5018 5006 5017 5023 |  | - |
| 3 | How many bags of mulch are needed? | ANSWER | yes | yes | NO | - | yes | NO | NO | 5008 5012 5013 5033 5034 5020 5006 5022 | 5008 | must not give 20 bags as the current quantity |
| 4 | Who is ordering the mulch, and when? | ANSWER | NO | yes | - | - | yes | NO | NO | 5033 5006 5034 5012 5008 5018 5023 5002 |  | - |
| 5 | What wood will be used for the bed frames? | ANSWER | yes | yes | - | yes | yes | yes | NO | 5005 5031 5009 5006 5014 5015 5022 5039 |  | must not state that a wood type has been finalized |
| 6 | Is the pickup truck available for hauling lumber? | ANSWER | yes | yes | - | - | yes | yes | yes | 5011 5035 5010 5037 5036 5031 5002 5015 |  | must not describe the pickup as still unconfirmed |
| 7 | Where will the new compost bins go? | ANSWER | yes | yes | - | yes | - | yes | NO | 5016 5019 5018 5017 5038 5031 5014 5006 |  | must not state either location as the decided location |
| 8 | Will the city green-waste pickup take the old bed boards on the Monday after? | ANSWER | - | yes | - | yes | - | yes | yes | 5031 5022 5006 5032 5009 5005 5035 5011 |  | must not confirm the Monday pickup |
| 9 | Do we need city permission to use the hydrant hose on a weekend? | ANSWER | - | yes | - | NO | - | NO | NO | 5022 5039 5031 5011 5012 5008 5038 5035 |  | must not assert that permission is or is not required |
| 10 | What did Hazel commit to, and by when? | ANSWER | yes | NO | - | - | yes | NO | NO | 5039 5016 5005 5023 5030 5038 5021 5024 |  | - |
| 11 | What is the lumber budget? | ANSWER | yes | yes | - | yes | - | yes | NO | 5037 5015 5010 5005 5036 5035 5009 5018 |  | must not claim a budget top-up was approved or give $240 as the budget |
| 12 | How many raised beds are being rebuilt? | ANSWER | yes | yes | - | - | - | yes | yes | 5005 5006 5031 5014 5009 5032 |  | - |
| 13 | What is Arjun responsible for? | ANSWER | NO | NO | - | - | NO | NO | NO | 5035 5005 5039 5002 5026 5018 5033 5014 |  | - |
| 14 | What time will the workday finish? | ANSWER | - | yes | - | NO | - | NO | NO | 5039 5004 5005 5028 5001 5031 5006 5018 |  | must not invent an end time |
| 15 | What was the original workday date, and what replaced it? | ANSWER | yes | yes | - | - | - | yes | NO | 5005 5001 5039 5004 5028 5023 5032 5006 |  | - |

**Totals:** user-facing correct 3/15 · citation precision 0.26 (31/118) · all auto checks 7/15 · facts 9/12 · sources 12/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 5/6 · behaviour 15/15
