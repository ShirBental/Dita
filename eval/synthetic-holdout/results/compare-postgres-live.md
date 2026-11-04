# A/B/C comparison (holdout, postgres-live)

- **A**: user-facing correct 4/15 · citation precision 0.35 (24/68) · all auto checks 5/15 · facts 7/12 · sources 8/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 3/6 · behaviour 14/15
- **B**: user-facing correct 3/15 · citation precision 0.26 (31/118) · all auto checks 7/15 · facts 9/12 · sources 12/15 · temporal 0/2 · qualifiers 4/6 · speaker attribution 5/6 · behaviour 15/15
- **C**: user-facing correct 5/15 · citation precision 0.57 (21/37) · all auto checks 7/15 · facts 9/12 · sources 8/15 · temporal 2/2 · qualifiers 3/6 · speaker attribution 6/6 · behaviour 15/15

| q | question | A user-facing | B user-facing | C user-facing | C knowledge items used | B→C | C latency µs |
|---|---|---|---|---|---|---|---|
| 1 | When and where is the workday? | NO | NO | NO | 1 | same | 61076 |
| 2 | Why was the workday date moved? | NO | NO | NO | 1 | same | 61959 |
| 3 | How many bags of mulch are needed? | NO | NO | yes | 2 | improved | 57203 |
| 4 | Who is ordering the mulch, and when? | NO | NO | NO | 3 | same | 53796 |
| 5 | What wood will be used for the bed frames? | yes | NO | NO | 4 | same | 58269 |
| 6 | Is the pickup truck available for hauling lumber? | yes | yes | yes | 3 | same | 56790 |
| 7 | Where will the new compost bins go? | NO | NO | NO | 3 | same | 52685 |
| 8 | Will the city green-waste pickup take the old bed boards on the Monday after? | yes | yes | yes | 4 | same | 55493 |
| 9 | Do we need city permission to use the hydrant hose on a weekend? | NO | NO | NO | 2 | same | 52744 |
| 10 | What did Hazel commit to, and by when? | NO | NO | NO | 3 | same | 50668 |
| 11 | What is the lumber budget? | NO | NO | NO | 4 | same | 54634 |
| 12 | How many raised beds are being rebuilt? | yes | yes | yes | 2 | same | 51725 |
| 13 | What is Arjun responsible for? | NO | NO | yes | 3 | improved | 50218 |
| 14 | What time will the workday finish? | NO | NO | NO | 1 | same | 51199 |
| 15 | What was the original workday date, and what replaced it? | NO | NO | NO | 1 | same | 49557 |

## User-facing answers, B vs C (verbatim)

### Q1. When and where is the workday?

**B** (ANSWER; cites 5004 5028 5016 5001 5018 5023 5033 5012):
```
What the group said:
• 910001 (2026-11-01): "Decided then: the workday is Saturday November 21, 9 AM, at the garden." [msg m5004]
• 910001 (2026-11-04): "OK, changing the plan: the workday moves to Sunday November 22, still 9 AM at the garden. Saturday is off." [msg m5028]
• 910002 (2026-11-02): "Where should the new compost bins go? I think next to the gate by the water tap, so they're easy to wet." [msg m5016]
• 910001 (2026-11-01): "Hi all, let's set a date for the fall workday at the community garden. I'm thinking Saturday November 21." [msg m5001]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
• 910002 (2026-11-04): "Bad news: a family thing came up and I'm away Saturday the 21st after all. And I have the only key to the tool shed." [qualified] [msg m5023]
• 910003 (2026-11-04): "Who is ordering the mulch?" [msg m5033]
• 910002 (2026-11-02): "Correction on the mulch: I measured the paths again and we need 30 bags, not 20." [msg m5012]
```
**C** (ANSWER; cites 5028):
```
- Priya said about the workday (Sunday November 22, still 9 AM): "the workday moves to Sunday November 22, still 9 AM at the garden." [m5028]
(These are group members' statements, not verified facts.)
```

### Q2. Why was the workday date moved?

**B** (ANSWER; cites 5028 5001 5004 5024 5018 5006 5017 5023):
```
What the group said:
• 910001 (2026-11-04): "OK, changing the plan: the workday moves to Sunday November 22, still 9 AM at the garden. Saturday is off." [msg m5028]
• 910001 (2026-11-01): "Hi all, let's set a date for the fall workday at the community garden. I'm thinking Saturday November 21." [msg m5001]
• 910001 (2026-11-01): "Decided then: the workday is Saturday November 21, 9 AM, at the garden." [msg m5004]
• 910001 (2026-11-04): "Can we move it to Sunday November 22 instead?" [msg m5024]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910001 (2026-11-02): "I'd rather put them along the north fence, away from the paths. The gate area already gets crowded." [msg m5017]
• 910002 (2026-11-04): "Bad news: a family thing came up and I'm away Saturday the 21st after all. And I have the only key to the tool shed." [qualified] [msg m5023]
```
**C** (ANSWER; cites 5028):
```
- Priya said about the workday (Sunday November 22, still 9 AM): "the workday moves to Sunday November 22, still 9 AM at the garden." [m5028]
(These are group members' statements, not verified facts.)
```

### Q3. How many bags of mulch are needed?

**B** (ANSWER; cites 5008 5012 5013 5033 5034 5020 5006 5022):
```
What the group said:
• 910002 (2026-11-01): "For the paths we'll need about 20 bags of mulch." [msg m5008]
• 910002 (2026-11-02): "Correction on the mulch: I measured the paths again and we need 30 bags, not 20." [msg m5012]
• 910005 (2026-11-02): "Noted, 30 bags." [msg m5013]
• 910003 (2026-11-04): "Who is ordering the mulch?" [msg m5033]
• 910002 (2026-11-04): "I'll order the 30 bags this week." [msg m5034]
• 910004 (2026-11-02): "I'll bring 12 pairs of work gloves." [msg m5020]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910005 (2026-11-02): "Does anyone know if we need permission from the city to use the hydrant hose on a weekend?" [msg m5022]
```
**C** (ANSWER; cites 5012 5034):
```
- Tomas said about we need 30 bags: "we need 30 bags, not 20." [m5012]
- Tomas committed to the 30 bags (this week): "I'll order the 30 bags this week." [m5034]
(These are group members' statements, not verified facts.)
```

### Q4. Who is ordering the mulch, and when?

**B** (ANSWER; cites 5033 5006 5034 5012 5008 5018 5023 5002):
```
What the group said:
• 910003 (2026-11-04): "Who is ordering the mulch?" [msg m5033]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910002 (2026-11-04): "I'll order the 30 bags this week." [msg m5034]
• 910002 (2026-11-02): "Correction on the mulch: I measured the paths again and we need 30 bags, not 20." [msg m5012]
• 910002 (2026-11-01): "For the paths we'll need about 20 bags of mulch." [msg m5008]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
• 910002 (2026-11-04): "Bad news: a family thing came up and I'm away Saturday the 21st after all. And I have the only key to the tool shed." [qualified] [msg m5023]
• 910002 (2026-11-01): "Saturday the 21st works for me. Morning is best." [msg m5002]
```
**C** (ANSWER; cites 5033 5012 5034):
```
- Leni asked about the mulch: "Who is ordering the mulch?" [m5033]
- Tomas said about we need 30 bags: "we need 30 bags, not 20." [m5012]
- Tomas committed to the 30 bags (this week): "I'll order the 30 bags this week." [m5034]
(These are group members' statements, not verified facts.)
```

### Q5. What wood will be used for the bed frames?

**B** (ANSWER; cites 5005 5031 5009 5006 5014 5015 5022 5039):
```
What the group said:
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910003 (2026-11-01): "For the bed frames I'd recommend cedar boards, they last much longer. But only if the budget allows; otherwise untreated pine with a plant-safe sealant is fine." [qualified] [msg m5009]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910003 (2026-11-02): "Cedar for six beds would be roughly $240, going by prices I saw last spring, so with $180 pine is probably the realistic option." [msg m5014]
• 910001 (2026-11-02): "Let's not finalize the wood type yet. I'll ask the fund committee whether they can top up the lumber budget." [msg m5015]
• 910005 (2026-11-02): "Does anyone know if we need permission from the city to use the hydrant hose on a weekend?" [msg m5022]
• 910005 (2026-11-06): "What time do we expect to finish? A few people asked me." [msg m5039]
```
**C** (ANSWER; cites 5015 5009 5014 5006):
```
- Priya decided on the wood type: "Let's not finalize the wood type yet." [m5015]
- Leni recommended cedar boards (only if the budget allows): "I'd recommend cedar boards, they last much longer." [m5009]
- Leni said about Cedar for six beds (last spring) [tentative]: "Cedar for six beds would be roughly $240" [m5014]
- Priya said about the six rotten raised beds: "The big job is rebuilding the six rotten raised beds." [m5006]
(These are group members' statements, not verified facts.)
```

### Q6. Is the pickup truck available for hauling lumber?

**B** (ANSWER; cites 5011 5035 5010 5037 5036 5031 5002 5015):
```
What the group said:
• 910004 (2026-11-01): "I might be able to borrow my cousin's pickup to haul the lumber, not sure yet. He sometimes needs it on weekends." [msg m5011]
• 910005 (2026-11-06): "I ran into Arjun's cousin yesterday. He confirmed Arjun can have the pickup on Sunday morning for the lumber." [msg m5035]
• 910001 (2026-11-01): "The garden fund has $180 for lumber this season." [msg m5010]
• 910001 (2026-11-06): "The fund committee still hasn't replied about topping up the lumber budget." [msg m5037]
• 910004 (2026-11-06): "Great, then I'll pick up the lumber Sunday at 8 AM before we start." [msg m5036]
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910002 (2026-11-01): "Saturday the 21st works for me. Morning is best." [msg m5002]
• 910001 (2026-11-02): "Let's not finalize the wood type yet. I'll ask the fund committee whether they can top up the lumber budget." [msg m5015]
```
**C** (ANSWER; cites 5035 5036 5010):
```
- Hazel relayed (from Arjun's cousin) about the pickup (on Sunday morning): "He confirmed Arjun can have the pickup on Sunday morning for the lumber." [m5035]
- Arjun committed to the lumber (Sunday at 8 AM): "I'll pick up the lumber Sunday at 8 AM before we start." [m5036]
- Priya said about The garden fund (this season): "The garden fund has $180 for lumber this season." [m5010]
(These are group members' statements, not verified facts.)
```

### Q7. Where will the new compost bins go?

**B** (ANSWER; cites 5016 5019 5018 5017 5038 5031 5014 5006):
```
What the group said:
• 910002 (2026-11-02): "Where should the new compost bins go? I think next to the gate by the water tap, so they're easy to wet." [msg m5016]
• 910005 (2026-11-02): "Let's leave the compost question for another day." [msg m5019]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
• 910001 (2026-11-02): "I'd rather put them along the north fence, away from the paths. The gate area already gets crowded." [msg m5017]
• 910003 (2026-11-06): "If they don't reply by the weekend, I'd go with pine." [msg m5038]
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910003 (2026-11-02): "Cedar for six beds would be roughly $240, going by prices I saw last spring, so with $180 pine is probably the realistic option." [msg m5014]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
```
**C** (ANSWER; cites 5019 5016 5017):
```
- Hazel said about the compost question: "Let's leave the compost question for another day." [m5019]
- Tomas said about the new compost bins: "I think next to the gate by the water tap, so they're easy to wet." [m5016]
- Priya recommended them: "I'd rather put them along the north fence, away from the paths." [m5017]
(These are group members' statements, not verified facts.)
```

### Q8. Will the city green-waste pickup take the old bed boards on the Monday after?

**B** (ANSWER; cites 5031 5022 5006 5032 5009 5005 5035 5011):
```
What the group said:
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910005 (2026-11-02): "Does anyone know if we need permission from the city to use the hydrant hose on a weekend?" [msg m5022]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910001 (2026-11-04): "I think they collect on Mondays, but I haven't confirmed it. Don't count on it yet." [qualified] [msg m5032]
• 910003 (2026-11-01): "For the bed frames I'd recommend cedar boards, they last much longer. But only if the budget allows; otherwise untreated pine with a plant-safe sealant is fine." [qualified] [msg m5009]
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910005 (2026-11-06): "I ran into Arjun's cousin yesterday. He confirmed Arjun can have the pickup on Sunday morning for the lumber." [msg m5035]
• 910004 (2026-11-01): "I might be able to borrow my cousin's pickup to haul the lumber, not sure yet. He sometimes needs it on weekends." [msg m5011]
```
**C** (ANSWER; cites 5009 5015 5032 5035):
```
- Leni recommended cedar boards (only if the budget allows): "I'd recommend cedar boards, they last much longer." [m5009]
- Priya decided on the wood type: "Let's not finalize the wood type yet." [m5015]
- Priya said about they (on Mondays) [tentative]: "I think they collect on Mondays, but I haven't confirmed it." [m5032]
- Hazel relayed (from Arjun's cousin) about the pickup (on Sunday morning): "He confirmed Arjun can have the pickup on Sunday morning for the lumber." [m5035]
(These are group members' statements, not verified facts.)
```

### Q9. Do we need city permission to use the hydrant hose on a weekend?

**B** (ANSWER; cites 5022 5039 5031 5011 5012 5008 5038 5035):
```
What the group said:
• 910005 (2026-11-02): "Does anyone know if we need permission from the city to use the hydrant hose on a weekend?" [msg m5022]
• 910005 (2026-11-06): "What time do we expect to finish? A few people asked me." [msg m5039]
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910004 (2026-11-01): "I might be able to borrow my cousin's pickup to haul the lumber, not sure yet. He sometimes needs it on weekends." [msg m5011]
• 910002 (2026-11-02): "Correction on the mulch: I measured the paths again and we need 30 bags, not 20." [msg m5012]
• 910002 (2026-11-01): "For the paths we'll need about 20 bags of mulch." [msg m5008]
• 910003 (2026-11-06): "If they don't reply by the weekend, I'd go with pine." [msg m5038]
• 910005 (2026-11-06): "I ran into Arjun's cousin yesterday. He confirmed Arjun can have the pickup on Sunday morning for the lumber." [msg m5035]
```
**C** (ANSWER; cites 5012 5034):
```
- Tomas said about we need 30 bags: "we need 30 bags, not 20." [m5012]
- Tomas committed to the 30 bags (this week): "I'll order the 30 bags this week." [m5034]
(These are group members' statements, not verified facts.)
```

### Q10. What did Hazel commit to, and by when?

**B** (ANSWER; cites 5039 5016 5005 5023 5030 5038 5021 5024):
```
What the group said:
• 910005 (2026-11-06): "What time do we expect to finish? A few people asked me." [msg m5039]
• 910002 (2026-11-02): "Where should the new compost bins go? I think next to the gate by the water tap, so they're easy to wet." [msg m5016]
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910002 (2026-11-04): "Bad news: a family thing came up and I'm away Saturday the 21st after all. And I have the only key to the tool shed." [qualified] [msg m5023]
• 910005 (2026-11-04): "I'll send the volunteer sign-up sheet by November 16." [msg m5030]
• 910003 (2026-11-06): "If they don't reply by the weekend, I'd go with pine." [msg m5038]
• 910003 (2026-11-02): "I'll bring coffee and muffins for everyone." [msg m5021]
• 910001 (2026-11-04): "Can we move it to Sunday November 22 instead?" [msg m5024]
```
**C** (ANSWER; cites 5030 5019 5035):
```
- Hazel committed to the volunteer sign-up sheet (by November 16): "I'll send the volunteer sign-up sheet by November 16." [m5030]
- Hazel said about the compost question: "Let's leave the compost question for another day." [m5019]
- Hazel relayed (from Arjun's cousin) about the pickup (on Sunday morning): "He confirmed Arjun can have the pickup on Sunday morning for the lumber." [m5035]
(These are group members' statements, not verified facts.)
```

### Q11. What is the lumber budget?

**B** (ANSWER; cites 5037 5015 5010 5005 5036 5035 5009 5018):
```
What the group said:
• 910001 (2026-11-06): "The fund committee still hasn't replied about topping up the lumber budget." [msg m5037]
• 910001 (2026-11-02): "Let's not finalize the wood type yet. I'll ask the fund committee whether they can top up the lumber budget." [msg m5015]
• 910001 (2026-11-01): "The garden fund has $180 for lumber this season." [msg m5010]
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910004 (2026-11-06): "Great, then I'll pick up the lumber Sunday at 8 AM before we start." [msg m5036]
• 910005 (2026-11-06): "I ran into Arjun's cousin yesterday. He confirmed Arjun can have the pickup on Sunday morning for the lumber." [msg m5035]
• 910003 (2026-11-01): "For the bed frames I'd recommend cedar boards, they last much longer. But only if the budget allows; otherwise untreated pine with a plant-safe sealant is fine." [qualified] [msg m5009]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
```
**C** (ANSWER; cites 5036 5035 5009 5010):
```
- Arjun committed to the lumber (Sunday at 8 AM): "I'll pick up the lumber Sunday at 8 AM before we start." [m5036]
- This replaces earlier messages that are no longer current: [m5011]
- Hazel relayed (from Arjun's cousin) about the pickup (on Sunday morning): "He confirmed Arjun can have the pickup on Sunday morning for the lumber." [m5035]
- Leni recommended cedar boards (only if the budget allows): "I'd recommend cedar boards, they last much longer." [m5009]
- Priya said about The garden fund (this season): "The garden fund has $180 for lumber this season." [m5010]
(These are group members' statements, not verified facts.)
```

### Q12. How many raised beds are being rebuilt?

**B** (ANSWER; cites 5005 5006 5031 5014 5009 5032):
```
What the group said:
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910003 (2026-11-02): "Cedar for six beds would be roughly $240, going by prices I saw last spring, so with $180 pine is probably the realistic option." [msg m5014]
• 910003 (2026-11-01): "For the bed frames I'd recommend cedar boards, they last much longer. But only if the budget allows; otherwise untreated pine with a plant-safe sealant is fine." [qualified] [msg m5009]
• 910001 (2026-11-04): "I think they collect on Mondays, but I haven't confirmed it. Don't count on it yet." [qualified] [msg m5032]
```
**C** (ANSWER; cites 5006 5014):
```
- Priya said about the six rotten raised beds: "The big job is rebuilding the six rotten raised beds." [m5006]
- Leni said about Cedar for six beds (last spring) [tentative]: "Cedar for six beds would be roughly $240" [m5014]
(These are group members' statements, not verified facts.)
```

### Q13. What is Arjun responsible for?

**B** (ANSWER; cites 5035 5005 5039 5002 5026 5018 5033 5014):
```
What the group said:
• 910005 (2026-11-06): "I ran into Arjun's cousin yesterday. He confirmed Arjun can have the pickup on Sunday morning for the lumber." [msg m5035]
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910005 (2026-11-06): "What time do we expect to finish? A few people asked me." [msg m5039]
• 910002 (2026-11-01): "Saturday the 21st works for me. Morning is best." [msg m5002]
• 910004 (2026-11-04): "Sunday is fine." [msg m5026]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
• 910003 (2026-11-04): "Who is ordering the mulch?" [msg m5033]
• 910003 (2026-11-02): "Cedar for six beds would be roughly $240, going by prices I saw last spring, so with $180 pine is probably the realistic option." [msg m5014]
```
**C** (ANSWER; cites 5020 5036 5035):
```
- Arjun committed to 12 pairs of work gloves: "I'll bring 12 pairs of work gloves." [m5020]
- Arjun committed to the lumber (Sunday at 8 AM): "I'll pick up the lumber Sunday at 8 AM before we start." [m5036]
- Hazel relayed (from Arjun's cousin) about the pickup (on Sunday morning): "He confirmed Arjun can have the pickup on Sunday morning for the lumber." [m5035]
(These are group members' statements, not verified facts.)
```

### Q14. What time will the workday finish?

**B** (ANSWER; cites 5039 5004 5005 5028 5001 5031 5006 5018):
```
What the group said:
• 910005 (2026-11-06): "What time do we expect to finish? A few people asked me." [msg m5039]
• 910001 (2026-11-01): "Decided then: the workday is Saturday November 21, 9 AM, at the garden." [msg m5004]
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910001 (2026-11-04): "OK, changing the plan: the workday moves to Sunday November 22, still 9 AM at the garden. Saturday is off." [msg m5028]
• 910001 (2026-11-01): "Hi all, let's set a date for the fall workday at the community garden. I'm thinking Saturday November 21." [msg m5001]
• 910004 (2026-11-04): "Will the city green-waste pickup take our old bed boards on the Monday after?" [msg m5031]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
• 910002 (2026-11-02): "The north fence is too far from the tap. The gate is better." [msg m5018]
```
**C** (ANSWER; cites 5028):
```
- Priya said about the workday (Sunday November 22, still 9 AM): "the workday moves to Sunday November 22, still 9 AM at the garden." [m5028]
(These are group members' statements, not verified facts.)
```

### Q15. What was the original workday date, and what replaced it?

**B** (ANSWER; cites 5005 5001 5039 5004 5028 5023 5032 5006):
```
What the group said:
• 910004 (2026-11-01): "What's the main goal? Just weeding, or the raised beds too?" [msg m5005]
• 910001 (2026-11-01): "Hi all, let's set a date for the fall workday at the community garden. I'm thinking Saturday November 21." [msg m5001]
• 910005 (2026-11-06): "What time do we expect to finish? A few people asked me." [msg m5039]
• 910001 (2026-11-01): "Decided then: the workday is Saturday November 21, 9 AM, at the garden." [msg m5004]
• 910001 (2026-11-04): "OK, changing the plan: the workday moves to Sunday November 22, still 9 AM at the garden. Saturday is off." [msg m5028]
• 910002 (2026-11-04): "Bad news: a family thing came up and I'm away Saturday the 21st after all. And I have the only key to the tool shed." [qualified] [msg m5023]
• 910001 (2026-11-04): "I think they collect on Mondays, but I haven't confirmed it. Don't count on it yet." [qualified] [msg m5032]
• 910001 (2026-11-01): "The big job is rebuilding the six rotten raised beds. Plus mulching the paths." [msg m5006]
```
**C** (ANSWER; cites 5028):
```
- Priya said about the workday (Sunday November 22, still 9 AM): "the workday moves to Sunday November 22, still 9 AM at the garden." [m5028]
(These are group members' statements, not verified facts.)
```

