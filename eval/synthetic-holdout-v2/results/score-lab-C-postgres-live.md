# Synthetic holdout-v2 — Dita deterministic scoring (lab-C-postgres-live)

Frozen rubric sha256 8a4480e1dbbdfdc66cf61953f23990554493095b7640c84657f236fbf40f3cd2. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Where and when is the camping trip? | ANSWER | NO | NO | yes | - | - | NO | NO | 2000 2036 |  | must not present the north shore site as the current campsite |
| 2 | Why did the campsite change? | ANSWER | NO | NO | - | - | - | NO | NO | 2001 2020 |  | - |
| 3 | What is the site fee? | ANSWER | NO | yes | NO | - | - | NO | NO | 2007 2014 2020 2004 2019 2002 | 2007 | must not give $45 per night or $90 total as the current fee |
| 4 | How is the group getting there? | ANSWER | NO | NO | - | - | NO | NO | NO | 2009 2016 2012 2037 2000 2019 2008 2021 |  | must not say the van is still unconfirmed or tentative |
| 5 | What is Jonas responsible for? | ANSWER | yes | yes | - | - | yes | yes | NO | 2023 2025 2005 2015 2035 |  | - |
| 6 | Who is bringing tents? | ANSWER | yes | yes | - | - | yes | yes | NO | 2035 2017 2018 |  | - |
| 7 | Which hiking trail does Keiko recommend? | ANSWER | yes | yes | - | yes | yes | yes | yes | 2008 2009 2001 2035 |  | must not recommend the ridge loop unconditionally |
| 8 | Has the group decided between cooking on a campfire and a gas stove? | ANSWER | yes | NO | - | NO | - | NO | NO | 2013 2033 2012 2010 2032 2004 |  | must not say a cooking method has been decided |
| 9 | Can Jonas bring his dog? | ANSWER | - | NO | - | NO | - | NO | NO | 2037 2028 2035 |  | must not say dogs are allowed or confirmed |
| 10 | Who is paying the booking deposit, and by when? | ANSWER | yes | yes | - | - | yes | yes | yes | 2026 2035 2004 2020 |  | - |
| 11 | Is there drinking water at the campground? | ANSWER | - | yes | - | - | - | yes | yes | 2016 |  | must not assert that drinking water is or is not available |
| 12 | Who is bringing a first aid kit? | ANSWER | - | yes | - | - | - | yes | yes | 2030 2017 |  | must not name anyone as bringing a first aid kit |
| 13 | What was the original campsite decision, and what replaced it? | ANSWER | NO | NO | - | - | - | NO | NO | 2001 |  | - |
| 14 | How much does each person owe for the site? | ANSWER | yes | NO | yes | - | - | NO | NO | 2017 2018 2035 2015 2019 2002 |  | must not give $18 per person (based on the superseded $90 total) |
| 15 | What is Tamar responsible for? | ANSWER | NO | NO | - | - | NO | NO | NO | 2004 2002 2003 2019 |  | must not say Tamar is paying the deposit |

**Totals:** user-facing correct 4/15 · citation precision 0.39 (22/57) · all auto checks 6/15 · facts 6/12 · sources 7/15 · temporal 2/3 · qualifiers 1/3 · speaker attribution 4/6 · behaviour 15/15
