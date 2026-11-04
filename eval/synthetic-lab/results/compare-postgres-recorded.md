# A/B/C comparison (lab, postgres-recorded)

- **A**: user-facing correct 5/15 · citation precision 0.41 (18/44) · all auto checks 7/15 · facts 6/11 · sources 8/15 · temporal 2/3 · qualifiers 6/7 · speaker attribution 3/5 · behaviour 13/15
- **B**: user-facing correct 6/15 · citation precision 0.38 (19/50) · all auto checks 7/15 · facts 6/11 · sources 8/15 · temporal 2/3 · qualifiers 6/7 · speaker attribution 4/5 · behaviour 13/15
- **C**: user-facing correct 9/15 · citation precision 0.54 (22/41) · all auto checks 9/15 · facts 8/11 · sources 9/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 5/5 · behaviour 13/15

| q | question | A user-facing | B user-facing | C user-facing | C knowledge items used | B→C | C latency µs |
|---|---|---|---|---|---|---|---|
| 1 | When and where is the party? | NO | NO | yes | 3 | improved | 151696 |
| 2 | Why was the original date canceled? | NO | NO | NO | 0 | same | 403440 |
| 3 | Who is responsible for aprons? | NO | NO | yes | 3 | improved | 141022 |
| 4 | What is Ron responsible for? | NO | NO | NO | 4 | same | 147468 |
| 5 | What is Maya bringing? | NO | NO | yes | 4 | improved | 133266 |
| 6 | Has the rain venue been booked? | NO | NO | NO | 4 | same | 130543 |
| 7 | Is the food finalized? | NO | NO | NO | 2 | same | 125303 |
| 8 | Who is ordering the cake? | yes | yes | yes | 2 | same | 131038 |
| 9 | Why was ColorHouse not chosen? | NO | NO | NO | 4 | same | 131948 |
| 10 | What should be decided by November 12? | yes | yes | yes | 4 | same | 126340 |
| 11 | Is the community center definitely available? | yes | yes | yes | 3 | same | 172127 |
| 12 | What activity does Noa prefer? | yes | yes | yes | 4 | same | 134579 |
| 13 | What is the difference between Ron's initial speaker proposal and final decision? | NO | yes | yes | 3 | same | 139862 |
| 14 | Who owns the cake and when is it due? | yes | yes | yes | 1 | same | 130842 |
| 15 | Which source messages support the final date? | NO | NO | NO | 0 | same | 153877 |

## User-facing answers, B vs C (verbatim)

### Q1. When and where is the party?

**B** (ANSWER; cites 1023 1000):
```
What the group said:
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900001 (2026-10-30): "We need to organize Noa's 7th birthday party for November 14." [msg m1000]
```
**C** (ANSWER; cites 1023 1021 1019):
```
- Dana decided on the party (Sunday, November 15, 2 PM to 4 PM; at Maya's backyard): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [m1023]
- This replaces earlier messages that are no longer current: [m1000] [m1017]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
- Dana said about November 14 (November 14): "November 14 is no longer an option." [m1019]
(These are group members' statements, not verified facts.)
```

### Q2. Why was the original date canceled?

**B** (UNKNOWN; cites -):
```
I couldn't find this in the group's messages.
```
**C** (UNKNOWN; cites -):
```
I couldn't find this in the group's messages.
```

### Q3. Who is responsible for aprons?

**B** (ANSWER; cites 1014 1015 1028):
```
What the group said:
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
• 900002 (2026-10-30): "I can bring 15 aprons. I'll buy them on November 7." [msg m1015]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [msg m1028]
```
**C** (ANSWER; cites 1015 1028 1014):
```
- Yossi committed to 15 aprons (on November 7): "I can bring 15 aprons. I'll buy them on November 7." [m1015]
- Yossi said about the aprons (November 10): "Update: the aprons arrive November 10, so I can still bring them." [m1028]
- Maya said about the clay: "I can get the clay, but someone else should bring aprons." [m1014]
(These are group members' statements, not verified facts.)
```

### Q4. What is Ron responsible for?

**B** (ANSWER; cites 1031):
```
What the group said:
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
```
**C** (ANSWER; cites 1016 1026 1024 1030):
```
- Ron committed to drinks (for the kids): "I'll handle drinks: water and juice boxes for the kids." [m1016]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
- Ron offered a portable speaker for background music [offer, not confirmed]: "I can bring a portable speaker for background music." [m1024]
- Ron offered RSVPs (if you send me the guest list) [offer, not confirmed]: "I can handle RSVPs if you send me the guest list." [m1030]
(These are group members' statements, not verified facts.)
```

### Q5. What is Maya bringing?

**B** (ANSWER; cites 1039 1026 1024 1028 1014):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [msg m1026]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [msg m1028]
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
```
**C** (ANSWER; cites 1014 1027 1024 1026):
```
- Maya said about the clay: "I can get the clay, but someone else should bring aprons." [m1014]
- Maya said about disposable tablecloths: "I said I'd get clay; correction: I can also bring disposable tablecloths." [m1027]
- Ron offered a portable speaker for background music [offer, not confirmed]: "I can bring a portable speaker for background music." [m1024]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
(These are group members' statements, not verified facts.)
```

### Q6. Has the rain venue been booked?

**B** (ANSWER; cites 1035 1033 1032):
```
What the group said:
• 900004 (2026-10-30): "Reminder: we should decide on the rain plan by November 12." [msg m1035]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [msg m1033]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
```
**C** (ANSWER; cites 1032 1022 1001 1033):
```
- Maya said about the backyard (if it rains): "Potential issue: if it rains, the backyard won't work." [m1032]
- Maya said about my backyard (November 15): "November 15 works for me too, and my backyard is available." [m1022]
- Maya offered my backyard (if the weather is good) [offer, not confirmed]: "I can host in my backyard if the weather is good." [m1001]
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
(These are group members' statements, not verified facts.)
```

### Q7. Is the food finalized?

**B** (ANSWER; cites 1038 1036):
```
What the group said:
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [msg m1038]
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [msg m1036]
```
**C** (ANSWER; cites 1038 1036):
```
- Yossi said about food (until we confirm dietary restrictions): "Then let's not finalize food until we confirm dietary restrictions." [m1038]
- Maya recommended plain cheese pizza and fruit (For food): "For food, I recommend ordering plain cheese pizza and fruit." [m1036]
(These are group members' statements, not verified facts.)
```

### Q8. Who is ordering the cake?

**B** (ANSWER; cites 1039 1040 1036):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [msg m1040]
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [msg m1036]
```
**C** (ANSWER; cites 1040 1036):
```
- Dana committed to the cake (only after we confirm dietary restrictions): "I will order the cake, but only after we confirm dietary restrictions." [m1040]
- Maya recommended plain cheese pizza and fruit (For food): "For food, I recommend ordering plain cheese pizza and fruit." [m1036]
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
**C** (ANSWER; cites 1005 1010 1006 1008):
```
- Maya said about ColorHouse (for children): "There's also ColorHouse. They run painting workshops for children." [m1005]
- Yossi said about ColorHouse (For 14 kids): "For 14 kids, ColorHouse could cost around $448, based on that quote." [m1010]
- Yossi relayed (from their quote last month) about ColorHouse (per child): "ColorHouse charges about $32 per child, according to their quote last month." [m1006]
- Dana said about 12 children (12 children; not counting siblings): "Let's invite 12 children, not counting siblings." [m1008]
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
**C** (ANSWER; cites 1035 1008 1019 1021):
```
- Ron said about the rain plan (by November 12): "Reminder: we should decide on the rain plan by November 12." [m1035]
- Dana said about 12 children (12 children; not counting siblings): "Let's invite 12 children, not counting siblings." [m1008]
- Dana said about November 14 (November 14): "November 14 is no longer an option." [m1019]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
(These are group members' statements, not verified facts.)
```

### Q11. Is the community center definitely available?

**B** (ANSWER; cites 1033 1034 1022):
```
What the group said:
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [msg m1033]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
```
**C** (ANSWER; cites 1034 1033 1022):
```
- Dana said about The community center: "The community center is only a suggestion, not a confirmed backup." [m1034]
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
- Maya said about my backyard (November 15): "November 15 works for me too, and my backyard is available." [m1022]
(These are group members' statements, not verified facts.)
```

### Q12. What activity does Noa prefer?

**B** (ANSWER; cites 1004 1011 1007 1025 1003 1006):
```
What the group said:
• 900001 (2026-10-30): "Noa prefers crafts and science activities to loud games." [msg m1004]
• 900004 (2026-10-30): "That's over our $400 activity budget. I prefer a backyard workshop." [msg m1011]
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900004 (2026-10-30): "I heard PlayLab gets noisy. Noa doesn't like loud music." [msg m1003]
• 900002 (2026-10-30): "ColorHouse charges about $32 per child, according to their quote last month." [related by inference] [msg m1006]
```
**C** (ANSWER; cites 1004 1003 1011 1007):
```
- Dana said about Noa: "Noa prefers crafts and science activities to loud games." [m1004]
- Ron said about Noa: "Noa doesn't like loud music." [m1003]
- Ron recommended a backyard workshop: "That's over our $400 activity budget. I prefer a backyard workshop." [m1011]
- Ron recommended the activity budget: "I suggest we keep the activity budget under $400." [m1007]
(These are group members' statements, not verified facts.)
```

### Q13. What is the difference between Ron's initial speaker proposal and final decision?

**B** (ANSWER; cites 1025 1023 1026 1024):
```
What the group said:
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [msg m1026]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
```
**C** (ANSWER; cites 1024 1025 1026):
```
- Ron offered a portable speaker for background music [offer, not confirmed]: "I can bring a portable speaker for background music." [m1024]
- Dana advised against the speaker: "Please skip the speaker. Noa is sensitive to loud sound." [m1025]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
(These are group members' statements, not verified facts.)
```

### Q14. Who owns the cake and when is it due?

**B** (ANSWER; cites 1039 1040):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [msg m1040]
```
**C** (ANSWER; cites 1040):
```
- Dana committed to the cake (only after we confirm dietary restrictions): "I will order the cake, but only after we confirm dietary restrictions." [m1040]
(These are group members' statements, not verified facts.)
```

### Q15. Which source messages support the final date?

**B** (UNKNOWN; cites -):
```
I couldn't find this in the group's messages.
```
**C** (UNKNOWN; cites -):
```
I couldn't find this in the group's messages.
```

