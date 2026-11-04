# Synthetic lab — Dita deterministic scoring (lab-C-in_memory-recorded)

Frozen rubric sha256 decbb0e0b131147659596d0100ab1a346316470c1e81cdf9695a3da18463ba2a. No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.

| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | When and where is the party? | ANSWER | yes | yes | yes | - | - | yes | yes | 1023 1018 1021 |  | - |
| 2 | Why was the original date canceled? | UNKNOWN | NO | NO | - | - | - | NO | NO |  |  | - |
| 3 | Who is responsible for aprons? | ANSWER | yes | yes | - | - | yes | yes | yes | 1015 1028 |  | must not give November 7 as the current arrival/purchase date without the November 10 update |
| 4 | What is Ron responsible for? | ANSWER | yes | NO | NO | yes | yes | NO | NO | 1016 1024 1030 1003 1007 1011 1021 1026 1035 | 1024 | must not say Ron brings a speaker |
| 5 | What is Maya bringing? | ANSWER | yes | yes | - | - | yes | yes | NO | 1001 1012 1005 1009 1018 1022 1027 1032 1036 |  | - |
| 6 | Has the rain venue been booked? | ANSWER | yes | yes | - | yes | - | yes | yes | 1033 1034 1035 |  | must not say the rain venue is booked or confirmed |
| 7 | Is the food finalized? | ANSWER | NO | NO | - | yes | - | NO | NO | 1036 1038 |  | must not say the food is finalized |
| 8 | Who is ordering the cake? | ANSWER | - | yes | - | yes | yes | yes | yes | 1040 |  | - |
| 9 | Why was ColorHouse not chosen? | ANSWER | NO | NO | - | yes | - | NO | NO | 1005 1006 1010 |  | must not present $448 as a verified current price |
| 10 | What should be decided by November 12? | ANSWER | NO | NO | - | - | - | NO | NO | 1023 1008 1009 1018 1021 |  | - |
| 11 | Is the community center definitely available? | ANSWER | - | yes | - | yes | - | yes | yes | 1033 1034 |  | must not confirm availability |
| 12 | What activity does Noa prefer? | ANSWER | yes | yes | - | - | - | yes | yes | 1003 1004 1011 |  | - |
| 13 | What is the difference between Ron's initial speaker proposal and final decision? | ANSWER | yes | yes | - | - | - | yes | NO | 1016 1024 1025 1026 |  | - |
| 14 | Who owns the cake and when is it due? | ANSWER | - | yes | - | yes | yes | yes | yes | 1040 |  | must not invent a cake deadline |
| 15 | Which source messages support the final date? | UNKNOWN | - | NO | yes | - | - | NO | NO |  |  | - |

**Totals:** user-facing correct 7/15 · citation precision 0.47 (22/47) · all auto checks 9/15 · facts 7/11 · sources 9/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 5/5 · behaviour 13/15
