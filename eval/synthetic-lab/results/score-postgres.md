# Synthetic lab — Dita deterministic scoring (postgres)

Frozen rubric sha256 decbb0e0b131147659596d0100ab1a346316470c1e81cdf9695a3da18463ba2a. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | When and where is the party? | ANSWER | yes | yes | NO | - | - | NO | NO | 1023 1022 1016 1000 1025 1001 1034 1039 | 1000 | - |
| 2 | Why was the original date canceled? | ANSWER | NO | NO | - | - | - | NO | NO | 1007 1012 1025 1032 1001 1016 1030 1031 |  | - |
| 3 | Who is responsible for aprons? | ANSWER | yes | yes | - | - | yes | yes | NO | 1039 1014 1015 1028 1019 1022 1021 1024 |  | must not give November 7 as the current arrival/purchase date without the November 10 update |
| 4 | What is Ron responsible for? | ANSWER | NO | NO | NO | NO | NO | NO | NO | 1020 1031 1019 1022 1021 1017 1024 1025 | 1024 | must not say Ron brings a speaker |
| 5 | What is Maya bringing? | ANSWER | NO | NO | - | - | yes | NO | NO | 1023 1020 1039 1026 1024 1028 1025 1014 |  | - |
| 6 | Has the rain venue been booked? | ANSWER | yes | yes | - | yes | - | yes | yes | 1035 1033 1032 1007 1012 1025 1034 1001 |  | must not say the rain venue is booked or confirmed |
| 7 | Is the food finalized? | ANSWER | yes | yes | - | yes | - | yes | NO | 1038 1036 1025 1001 1034 1037 1039 1023 |  | must not say the food is finalized |
| 8 | Who is ordering the cake? | ANSWER | - | yes | - | yes | yes | yes | yes | 1039 1040 1036 1025 1001 1034 1032 1023 |  | - |
| 9 | Why was ColorHouse not chosen? | ANSWER | NO | NO | - | yes | - | NO | NO | 1005 1006 1010 1008 1034 1038 1037 1009 |  | must not present $448 as a verified current price |
| 10 | What should be decided by November 12? | ANSWER | yes | yes | - | - | - | yes | yes | 1035 1020 1018 1008 1014 1021 1017 1019 |  | - |
| 11 | Is the community center definitely available? | ANSWER | - | yes | - | yes | - | yes | yes | 1033 1034 1022 1037 1025 1001 1032 1039 |  | must not confirm availability |
| 12 | What activity does Noa prefer? | ANSWER | yes | yes | - | - | - | yes | yes | 1004 1011 1020 1007 1025 1003 1000 1006 |  | - |
| 13 | What is the difference between Ron's initial speaker proposal and final decision? | ANSWER | yes | yes | - | - | - | yes | yes | 1025 1023 1020 1022 1026 1024 1016 1001 |  | - |
| 14 | Who owns the cake and when is it due? | ANSWER | - | yes | - | yes | yes | yes | yes | 1039 1040 1032 1022 1016 1025 1001 1034 |  | must not invent a cake deadline |
| 15 | Which source messages support the final date? | ANSWER | - | NO | yes | - | - | NO | NO | 1007 1012 1025 1032 1001 1016 1030 1031 |  | - |

**Totals:** user-facing correct 7/15 · citation precision 0.17 (21/120) · all auto checks 9/15 · facts 7/11 · sources 10/15 · temporal 1/3 · qualifiers 6/7 · speaker attribution 4/5 · behaviour 15/15
