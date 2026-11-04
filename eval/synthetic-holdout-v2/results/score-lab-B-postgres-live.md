# Synthetic holdout-v2 — Dita deterministic scoring (lab-B-postgres-live)

Frozen rubric sha256 8a4480e1dbbdfdc66cf61953f23990554493095b7640c84657f236fbf40f3cd2. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Where and when is the camping trip? | ANSWER | NO | NO | yes | - | - | NO | NO | 2000 2036 2009 2035 2012 2037 2019 2034 |  | must not present the north shore site as the current campsite |
| 2 | Why did the campsite change? | ANSWER | NO | NO | - | - | - | NO | NO | 2001 2020 2008 2027 2012 2022 2002 2009 |  | - |
| 3 | What is the site fee? | ANSWER | yes | NO | NO | - | - | NO | NO | 2007 2019 2034 2014 2002 2004 2026 2029 | 2007 | must not give $45 per night or $90 total as the current fee |
| 4 | How is the group getting there? | ANSWER | NO | NO | - | - | NO | NO | NO | 2009 2016 2012 2037 2000 2019 2008 2021 |  | must not say the van is still unconfirmed or tentative |
| 5 | What is Jonas responsible for? | ANSWER | yes | NO | - | - | yes | NO | NO | 2034 2035 2037 2007 2019 2003 2000 2025 |  | - |
| 6 | Who is bringing tents? | ANSWER | yes | yes | - | - | yes | yes | NO | 2017 2018 2034 2035 2028 2036 2032 2000 |  | - |
| 7 | Which hiking trail does Keiko recommend? | ANSWER | yes | yes | - | yes | yes | yes | yes | 2008 2001 2035 2016 2034 2030 2009 2037 |  | must not recommend the ridge loop unconditionally |
| 8 | Has the group decided between cooking on a campfire and a gas stove? | ANSWER | yes | yes | - | yes | - | yes | NO | 2011 2033 2010 2013 2012 2037 2032 2004 |  | must not say a cooking method has been decided |
| 9 | Can Jonas bring his dog? | ANSWER | - | NO | - | NO | - | NO | NO | 2028 2035 2037 2017 2018 2036 2030 2032 |  | must not say dogs are allowed or confirmed |
| 10 | Who is paying the booking deposit, and by when? | ANSWER | yes | yes | - | - | yes | yes | yes | 2026 2035 2034 2004 2012 2009 2020 2037 |  | - |
| 11 | Is there drinking water at the campground? | ANSWER | - | yes | - | - | - | yes | yes | 2016 2024 2004 2012 2037 2000 2023 2019 |  | must not assert that drinking water is or is not available |
| 12 | Who is bringing a first aid kit? | ANSWER | - | yes | - | - | - | yes | yes | 2030 2017 2034 2013 2018 2037 2021 2011 |  | must not name anyone as bringing a first aid kit |
| 13 | What was the original campsite decision, and what replaced it? | ANSWER | NO | NO | - | - | - | NO | NO | 2001 2034 2035 2009 2014 2008 2002 2031 |  | - |
| 14 | How much does each person owe for the site? | ANSWER | yes | NO | NO | - | - | NO | NO | 2019 2002 2018 2015 2026 2007 2016 2017 | 2007 | must not give $18 per person (based on the superseded $90 total) |
| 15 | What is Tamar responsible for? | ANSWER | NO | NO | - | - | NO | NO | NO | 2034 2037 2007 2019 2003 2000 2025 2013 |  | must not say Tamar is paying the deposit |

**Totals:** user-facing correct 4/15 · citation precision 0.17 (20/120) · all auto checks 6/15 · facts 7/12 · sources 6/15 · temporal 1/3 · qualifiers 2/3 · speaker attribution 4/6 · behaviour 15/15
