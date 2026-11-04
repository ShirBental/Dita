# Synthetic holdout — Dita deterministic scoring (lab-C-postgres-live)

Frozen rubric sha256 691e796b203332247fdc0f460ff3798e6194c44570b5ab12599eb5e80e970884. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | When and where is the workday? | ANSWER | yes | NO | yes | - | - | NO | NO | 5028 |  | must not give Saturday November 21 as the current date |
| 2 | Why was the workday date moved? | ANSWER | NO | NO | - | - | - | NO | NO | 5028 |  | - |
| 3 | How many bags of mulch are needed? | ANSWER | yes | yes | yes | - | yes | yes | yes | 5012 5034 |  | must not give 20 bags as the current quantity |
| 4 | Who is ordering the mulch, and when? | ANSWER | yes | yes | - | - | yes | yes | NO | 5033 5012 5034 |  | - |
| 5 | What wood will be used for the bed frames? | ANSWER | NO | yes | - | yes | yes | NO | NO | 5015 5009 5014 5006 |  | must not state that a wood type has been finalized |
| 6 | Is the pickup truck available for hauling lumber? | ANSWER | yes | yes | - | - | yes | yes | yes | 5035 5036 5010 |  | must not describe the pickup as still unconfirmed |
| 7 | Where will the new compost bins go? | ANSWER | yes | yes | - | yes | - | yes | NO | 5019 5016 5017 |  | must not state either location as the decided location |
| 8 | Will the city green-waste pickup take the old bed boards on the Monday after? | ANSWER | - | yes | - | yes | - | yes | yes | 5009 5015 5032 5035 |  | must not confirm the Monday pickup |
| 9 | Do we need city permission to use the hydrant hose on a weekend? | ANSWER | - | NO | - | NO | - | NO | NO | 5012 5034 |  | must not assert that permission is or is not required |
| 10 | What did Hazel commit to, and by when? | ANSWER | yes | NO | - | - | yes | NO | NO | 5030 5019 5035 |  | - |
| 11 | What is the lumber budget? | ANSWER | yes | NO | - | NO | - | NO | NO | 5036 5035 5009 5010 |  | must not claim a budget top-up was approved or give $240 as the budget |
| 12 | How many raised beds are being rebuilt? | ANSWER | yes | yes | - | - | - | yes | yes | 5006 5014 |  | - |
| 13 | What is Arjun responsible for? | ANSWER | yes | yes | - | - | yes | yes | yes | 5020 5036 5035 |  | - |
| 14 | What time will the workday finish? | ANSWER | - | NO | - | NO | - | NO | NO | 5028 |  | must not invent an end time |
| 15 | What was the original workday date, and what replaced it? | ANSWER | NO | NO | - | - | - | NO | NO | 5028 |  | - |

**Totals:** user-facing correct 5/15 · citation precision 0.57 (21/37) · all auto checks 7/15 · facts 9/12 · sources 8/15 · temporal 2/2 · qualifiers 3/6 · speaker attribution 6/6 · behaviour 15/15
