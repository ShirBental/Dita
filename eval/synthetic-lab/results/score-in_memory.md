# Synthetic Lab — Dita deterministic scoring (in_memory)

Frozen rubric sha256 decbb0e0b131147659596d0100ab1a346316470c1e81cdf9695a3da18463ba2a. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | When and where is the party? | ANSWER | yes | yes | NO | - | - | NO | NO | 1000 1023 1020 1028 1017 1029 1035 1018 | 1017 1000 | - |
| 2 | Why was the original date canceled? | UNKNOWN | NO | NO | - | - | - | NO | NO |  |  | - |
| 3 | Who is responsible for aprons? | ANSWER | yes | yes | - | - | yes | yes | yes | 1015 1028 1014 1023 1018 1027 1029 1032 |  | must not give November 7 as the current arrival/purchase date without the November 10 update |
| 4 | What is Ron responsible for? | ANSWER | NO | NO | yes | yes | yes | NO | NO | 1031 1030 1039 1029 1032 |  | must not say Ron brings a speaker |
| 5 | What is Maya bringing? | ANSWER | NO | NO | - | - | NO | NO | NO | 1039 1032 1031 1030 1028 1026 1033 |  | - |
| 6 | Has the rain venue been booked? | ANSWER | yes | yes | - | yes | - | yes | NO | 1035 1032 1033 1020 1028 1034 1026 1039 |  | must not say the rain venue is booked or confirmed |
| 7 | Is the food finalized? | ANSWER | yes | yes | - | yes | - | yes | NO | 1036 1038 1033 1040 1037 1034 1032 1029 |  | must not say the food is finalized |
| 8 | Who is ordering the cake? | ANSWER | - | yes | - | yes | yes | yes | yes | 1036 1039 1040 1038 1033 1032 1031 1030 |  | - |
| 9 | Why was ColorHouse not chosen? | ANSWER | NO | NO | - | yes | - | NO | NO | 1005 1034 1010 1037 1006 1008 1038 1009 |  | must not present $448 as a verified current price |
| 10 | What should be decided by November 12? | ANSWER | yes | yes | - | - | - | yes | yes | 1035 1008 1014 1020 1021 1015 1017 1018 |  | - |
| 11 | Is the community center definitely available? | ANSWER | - | yes | - | yes | - | yes | yes | 1034 1033 1022 1032 1036 1029 1037 1021 |  | must not confirm availability |
| 12 | What activity does Noa prefer? | ANSWER | yes | yes | - | - | - | yes | yes | 1011 1004 1007 1025 1003 1010 1006 1020 |  | - |
| 13 | What is the difference between Ron's initial speaker proposal and final decision? | ANSWER | yes | yes | - | - | - | yes | NO | 1023 1024 1025 1026 1020 1028 1022 1032 |  | - |
| 14 | Who owns the cake and when is it due? | ANSWER | - | yes | - | yes | yes | yes | yes | 1039 1040 1032 1031 1038 1030 1028 1026 |  | must not invent a cake deadline |
| 15 | Which source messages support the final date? | UNKNOWN | - | NO | yes | - | - | NO | NO |  |  | - |

**Totals:** user-facing correct 6/15 · citation precision 0.21 (21/100) · all auto checks 9/15 · facts 7/11 · sources 10/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 4/5 · behaviour 13/15
