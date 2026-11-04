# A/B/C comparison (lab, postgres-live)

- **A**: user-facing correct 5/15 · citation precision 0.41 (18/44) · all auto checks 7/15 · facts 6/11 · sources 8/15 · temporal 2/3 · qualifiers 6/7 · speaker attribution 3/5 · behaviour 13/15
- **B**: user-facing correct 7/15 · citation precision 0.17 (21/120) · all auto checks 9/15 · facts 7/11 · sources 10/15 · temporal 1/3 · qualifiers 6/7 · speaker attribution 4/5 · behaviour 15/15
- **C**: user-facing correct 6/15 · citation precision 0.32 (21/65) · all auto checks 9/15 · facts 7/11 · sources 9/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 5/5 · behaviour 15/15

| q | question | A user-facing | B user-facing | C user-facing | C knowledge items used | B→C | C latency µs |
|---|---|---|---|---|---|---|---|
| 1 | When and where is the party? | NO | NO | NO | 4 | same | 170435 |
| 2 | Why was the original date canceled? | NO | NO | NO | 0 | same | 112275 |
| 3 | Who is responsible for aprons? | NO | NO | yes | 2 | improved | 77103 |
| 4 | What is Ron responsible for? | NO | NO | NO | 9 | same | 52617 |
| 5 | What is Maya bringing? | NO | NO | NO | 8 | same | 40929 |
| 6 | Has the rain venue been booked? | NO | yes | yes | 3 | same | 33363 |
| 7 | Is the food finalized? | NO | NO | NO | 2 | same | 39999 |
| 8 | Who is ordering the cake? | yes | yes | yes | 2 | same | 32552 |
| 9 | Why was ColorHouse not chosen? | NO | NO | NO | 2 | same | 34243 |
| 10 | What should be decided by November 12? | yes | yes | NO | 6 | regressed | 40526 |
| 11 | Is the community center definitely available? | yes | yes | yes | 2 | same | 31747 |
| 12 | What activity does Noa prefer? | yes | yes | yes | 3 | same | 32041 |
| 13 | What is the difference between Ron's initial speaker proposal and final decision? | NO | yes | NO | 4 | regressed | 33230 |
| 14 | Who owns the cake and when is it due? | yes | yes | yes | 2 | same | 31794 |
| 15 | Which source messages support the final date? | NO | NO | NO | 0 | same | 46693 |

## User-facing answers, B vs C (verbatim)

### Q1. When and where is the party?

**B** (ANSWER; cites 1023 1022 1016 1000 1025 1001 1034 1039):
```
What the group said:
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900001 (2026-10-30): "We need to organize Noa's 7th birthday party for November 14." [msg m1000]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
```
**C** (ANSWER; cites 1018 1020 1021 1023):
```
- Maya said about November 14 (on November 14): "I won't be home on November 14. I forgot about my sister's wedding." [m1018]
- Yossi asked about Sunday, November 15, from 2 PM to 4 PM (from 2 PM to 4 PM): "What about Sunday, November 15, from 2 PM to 4 PM?" [m1020]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
- Dana decided on the party (Sunday, November 15, 2 PM to 4 PM; at Maya's backyard): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [m1023]
(These are group members' statements, not verified facts.)
```

### Q2. Why was the original date canceled?

**B** (ANSWER; cites 1007 1012 1025 1032 1001 1016 1030 1031):
```
What the group said:
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900003 (2026-10-30): "I can lead a clay workshop in the backyard." [msg m1012]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [msg m1030]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
```
**C** (ANSWER; cites 1007 1012 1025 1032 1001 1016 1030 1031):
```
What the group said:
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900003 (2026-10-30): "I can lead a clay workshop in the backyard." [msg m1012]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [msg m1030]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
```

### Q3. Who is responsible for aprons?

**B** (ANSWER; cites 1039 1019 1022 1014 1015 1028 1021 1024):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "Thanks for catching that. November 14 is no longer an option." [msg m1019]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
• 900002 (2026-10-30): "I can bring 15 aprons. I'll buy them on November 7." [msg m1015]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [msg m1028]
• 900004 (2026-10-30): "November 15 works for me." [msg m1021]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
```
**C** (ANSWER; cites 1015 1028):
```
- Yossi committed to 15 aprons (on November 7): "I can bring 15 aprons. I'll buy them on November 7." [m1015]
- Yossi said about the aprons (November 10): "Update: the aprons arrive November 10, so I can still bring them." [m1028]
(These are group members' statements, not verified facts.)
```

### Q4. What is Ron responsible for?

**B** (ANSWER; cites 1020 1031 1019 1022 1021 1025 1024 1037):
```
What the group said:
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [msg m1020]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
• 900001 (2026-10-30): "Thanks for catching that. November 14 is no longer an option." [msg m1019]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900004 (2026-10-30): "November 15 works for me." [msg m1021]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [msg m1037]
```
**C** (ANSWER; cites 1003 1007 1011 1016 1021 1024 1026 1030 1035):
```
- Ron said about Noa: "Noa doesn't like loud music." [m1003]
- Ron recommended the activity budget: "I suggest we keep the activity budget under $400." [m1007]
- Ron recommended a backyard workshop: "That's over our $400 activity budget. I prefer a backyard workshop." [m1011]
- Ron committed to drinks (for the kids): "I'll handle drinks: water and juice boxes for the kids." [m1016]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
- Ron offered a portable speaker for background music: "I can bring a portable speaker for background music." [m1024]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
- Ron offered RSVPs (if you send me the guest list): "I can handle RSVPs if you send me the guest list." [m1030]
- Ron said about the rain plan (by November 12): "Reminder: we should decide on the rain plan by November 12." [m1035]
(These are group members' statements, not verified facts.)
```

### Q5. What is Maya bringing?

**B** (ANSWER; cites 1023 1020 1039 1026 1024 1014 1028 1015):
```
What the group said:
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [msg m1020]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [msg m1026]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [msg m1028]
• 900002 (2026-10-30): "I can bring 15 aprons. I'll buy them on November 7." [msg m1015]
```
**C** (ANSWER; cites 1001 1005 1009 1012 1018 1027 1032 1036):
```
- Maya offered my backyard (if the weather is good): "I can host in my backyard if the weather is good." [m1001]
- Maya said about ColorHouse (for children): "There's also ColorHouse. They run painting workshops for children." [m1005]
- Maya said about 14 children total (14 children total): "two siblings will also attend, so we'd have 14 children total." [m1009]
- Maya offered a clay workshop in the backyard (in the backyard): "I can lead a clay workshop in the backyard." [m1012]
- Maya said about November 14 (on November 14): "I won't be home on November 14. I forgot about my sister's wedding." [m1018]
- Maya said about disposable tablecloths: "I said I'd get clay; correction: I can also bring disposable tablecloths." [m1027]
- Maya said about the backyard (if it rains): "Potential issue: if it rains, the backyard won't work." [m1032]
- Maya recommended plain cheese pizza and fruit (For food): "For food, I recommend ordering plain cheese pizza and fruit." [m1036]
(These are group members' statements, not verified facts.)
```

### Q6. Has the rain venue been booked?

**B** (ANSWER; cites 1035 1033 1032 1007 1012 1025 1034 1001):
```
What the group said:
• 900004 (2026-10-30): "Reminder: we should decide on the rain plan by November 12." [msg m1035]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [msg m1033]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900003 (2026-10-30): "I can lead a clay workshop in the backyard." [msg m1012]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
```
**C** (ANSWER; cites 1033 1034 1035):
```
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
- Dana said about The community center: "The community center is only a suggestion, not a confirmed backup." [m1034]
- Ron said about the rain plan (by November 12): "Reminder: we should decide on the rain plan by November 12." [m1035]
(These are group members' statements, not verified facts.)
```

### Q7. Is the food finalized?

**B** (ANSWER; cites 1038 1036 1025 1001 1034 1037 1039 1023):
```
What the group said:
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [msg m1038]
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [msg m1036]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [msg m1037]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
```
**C** (ANSWER; cites 1036 1038):
```
- Maya recommended plain cheese pizza and fruit (For food): "For food, I recommend ordering plain cheese pizza and fruit." [m1036]
- Yossi said about food (until we confirm dietary restrictions): "Then let's not finalize food until we confirm dietary restrictions." [m1038]
(These are group members' statements, not verified facts.)
```

### Q8. Who is ordering the cake?

**B** (ANSWER; cites 1039 1040 1036 1025 1001 1034 1032 1023):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [msg m1040]
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [msg m1036]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
```
**C** (ANSWER; cites 1039 1040):
```
- Ron asked about the cake: "Who is bringing the cake? I don't think we assigned that." [m1039]
- Dana committed to the cake (only after we confirm dietary restrictions): "I will order the cake, but only after we confirm dietary restrictions." [m1040]
(These are group members' statements, not verified facts.)
```

### Q9. Why was ColorHouse not chosen?

**B** (ANSWER; cites 1005 1006 1010 1008 1034 1038 1037 1009):
```
What the group said:
• 900003 (2026-10-30): "There's also ColorHouse. They run painting workshops for children." [msg m1005]
• 900002 (2026-10-30): "ColorHouse charges about $32 per child, according to their quote last month." [msg m1006]
• 900002 (2026-10-30): "For 14 kids, ColorHouse could cost around $448, based on that quote." [msg m1010]
• 900001 (2026-10-30): "Let's invite 12 children, not counting siblings." [msg m1008]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [msg m1038]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [msg m1037]
• 900003 (2026-10-30): "Actually, two siblings will also attend, so we'd have 14 children total." [related by inference] [msg m1009]
```
**C** (ANSWER; cites 1005 1010):
```
- Maya said about ColorHouse (for children): "There's also ColorHouse. They run painting workshops for children." [m1005]
- Yossi said about ColorHouse (For 14 kids): "For 14 kids, ColorHouse could cost around $448, based on that quote." [m1010]
(These are group members' statements, not verified facts.)
```

### Q10. What should be decided by November 12?

**B** (ANSWER; cites 1035 1020 1018 1008 1014 1021 1019 1017):
```
What the group said:
• 900004 (2026-10-30): "Reminder: we should decide on the rain plan by November 12." [msg m1035]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [msg m1020]
• 900003 (2026-10-30): "I won't be home on November 14. I forgot about my sister's wedding." [msg m1018]
• 900001 (2026-10-30): "Let's invite 12 children, not counting siblings." [msg m1008]
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
• 900004 (2026-10-30): "November 15 works for me." [msg m1021]
• 900001 (2026-10-30): "Thanks for catching that. November 14 is no longer an option." [msg m1019]
• 900001 (2026-10-30): "Let's plan for Saturday, November 14, from 2 PM to 4 PM." [msg m1017]
```
**C** (ANSWER; cites 1008 1009 1018 1020 1021 1023):
```
- Dana said about 12 children (12 children; not counting siblings): "Let's invite 12 children, not counting siblings." [m1008]
- Maya said about 14 children total (14 children total): "two siblings will also attend, so we'd have 14 children total." [m1009]
- Maya said about November 14 (on November 14): "I won't be home on November 14. I forgot about my sister's wedding." [m1018]
- Yossi asked about Sunday, November 15, from 2 PM to 4 PM (from 2 PM to 4 PM): "What about Sunday, November 15, from 2 PM to 4 PM?" [m1020]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
- Dana decided on the party (Sunday, November 15, 2 PM to 4 PM; at Maya's backyard): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [m1023]
(These are group members' statements, not verified facts.)
```

### Q11. Is the community center definitely available?

**B** (ANSWER; cites 1033 1034 1022 1025 1037 1001 1039 1032):
```
What the group said:
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [msg m1033]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [msg m1037]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
```
**C** (ANSWER; cites 1033 1034):
```
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
- Dana said about The community center: "The community center is only a suggestion, not a confirmed backup." [m1034]
(These are group members' statements, not verified facts.)
```

### Q12. What activity does Noa prefer?

**B** (ANSWER; cites 1004 1011 1020 1007 1025 1003 1000 1006):
```
What the group said:
• 900001 (2026-10-30): "Noa prefers crafts and science activities to loud games." [msg m1004]
• 900004 (2026-10-30): "That's over our $400 activity budget. I prefer a backyard workshop." [msg m1011]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [msg m1020]
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900004 (2026-10-30): "I heard PlayLab gets noisy. Noa doesn't like loud music." [msg m1003]
• 900001 (2026-10-30): "We need to organize Noa's 7th birthday party for November 14." [msg m1000]
• 900002 (2026-10-30): "ColorHouse charges about $32 per child, according to their quote last month." [related by inference] [msg m1006]
```
**C** (ANSWER; cites 1003 1004 1011):
```
- Ron said about Noa: "Noa doesn't like loud music." [m1003]
- Dana said about Noa: "Noa prefers crafts and science activities to loud games." [m1004]
- Ron recommended a backyard workshop: "That's over our $400 activity budget. I prefer a backyard workshop." [m1011]
(These are group members' statements, not verified facts.)
```

### Q13. What is the difference between Ron's initial speaker proposal and final decision?

**B** (ANSWER; cites 1025 1023 1022 1020 1016 1026 1024 1001):
```
What the group said:
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [msg m1020]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [msg m1026]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
```
**C** (ANSWER; cites 1016 1024 1025 1026):
```
- Ron committed to drinks (for the kids): "I'll handle drinks: water and juice boxes for the kids." [m1016]
- Ron offered a portable speaker for background music: "I can bring a portable speaker for background music." [m1024]
- Dana advised against the speaker: "Please skip the speaker. Noa is sensitive to loud sound." [m1025]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
(These are group members' statements, not verified facts.)
```

### Q14. Who owns the cake and when is it due?

**B** (ANSWER; cites 1039 1032 1022 1040 1016 1025 1001 1034):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [msg m1040]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
```
**C** (ANSWER; cites 1039 1040):
```
- Ron asked about the cake: "Who is bringing the cake? I don't think we assigned that." [m1039]
- Dana committed to the cake (only after we confirm dietary restrictions): "I will order the cake, but only after we confirm dietary restrictions." [m1040]
(These are group members' statements, not verified facts.)
```

### Q15. Which source messages support the final date?

**B** (ANSWER; cites 1007 1012 1025 1032 1001 1016 1030 1031):
```
What the group said:
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900003 (2026-10-30): "I can lead a clay workshop in the backyard." [msg m1012]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [msg m1030]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
```
**C** (ANSWER; cites 1007 1012 1025 1032 1001 1016 1030 1031):
```
What the group said:
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900003 (2026-10-30): "I can lead a clay workshop in the backyard." [msg m1012]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900003 (2026-10-30): "I can host in my backyard if the weather is good." [msg m1001]
• 900004 (2026-10-30): "I'll handle drinks: water and juice boxes for the kids." [msg m1016]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [msg m1030]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
```

