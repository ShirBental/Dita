# A/B/C comparison (lab, in_memory-recorded)

- **A**: user-facing correct 5/15 · citation precision 0.41 (18/44) · all auto checks 7/15 · facts 6/11 · sources 8/15 · temporal 2/3 · qualifiers 6/7 · speaker attribution 3/5 · behaviour 13/15
- **B**: user-facing correct 6/15 · citation precision 0.21 (21/100) · all auto checks 9/15 · facts 7/11 · sources 10/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 4/5 · behaviour 13/15
- **C**: user-facing correct 7/15 · citation precision 0.47 (22/47) · all auto checks 9/15 · facts 7/11 · sources 9/15 · temporal 2/3 · qualifiers 7/7 · speaker attribution 5/5 · behaviour 13/15

| q | question | A user-facing | B user-facing | C user-facing | C knowledge items used | B→C | C latency µs |
|---|---|---|---|---|---|---|---|
| 1 | When and where is the party? | NO | NO | yes | 3 | improved | 1194 |
| 2 | Why was the original date canceled? | NO | NO | NO | 0 | same | 1365 |
| 3 | Who is responsible for aprons? | NO | yes | yes | 2 | same | 717 |
| 4 | What is Ron responsible for? | NO | NO | NO | 9 | same | 693 |
| 5 | What is Maya bringing? | NO | NO | NO | 9 | same | 714 |
| 6 | Has the rain venue been booked? | NO | NO | yes | 3 | improved | 677 |
| 7 | Is the food finalized? | NO | NO | NO | 2 | same | 756 |
| 8 | Who is ordering the cake? | yes | yes | yes | 1 | same | 667 |
| 9 | Why was ColorHouse not chosen? | NO | NO | NO | 3 | same | 668 |
| 10 | What should be decided by November 12? | yes | yes | NO | 5 | regressed | 813 |
| 11 | Is the community center definitely available? | yes | yes | yes | 2 | same | 1253 |
| 12 | What activity does Noa prefer? | yes | yes | yes | 3 | same | 588 |
| 13 | What is the difference between Ron's initial speaker proposal and final decision? | NO | NO | NO | 4 | same | 665 |
| 14 | Who owns the cake and when is it due? | yes | yes | yes | 1 | same | 659 |
| 15 | Which source messages support the final date? | NO | NO | NO | 0 | same | 1371 |

## User-facing answers, B vs C (verbatim)

### Q1. When and where is the party?

**B** (ANSWER; cites 1000 1023 1020 1028 1017 1029 1035 1018):
```
What the group said:
• 900001 (2026-10-30): "We need to organize Noa's 7th birthday party for November 14." [msg m1000]
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [related by inference] [msg m1020]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [related by inference] [msg m1028]
• 900001 (2026-10-30): "Let's plan for Saturday, November 14, from 2 PM to 4 PM." [related by inference] [msg m1017]
• 900001 (2026-10-30): "We need someone to manage RSVPs. I haven't assigned that." [related by inference] [msg m1029]
• 900004 (2026-10-30): "Reminder: we should decide on the rain plan by November 12." [related by inference] [msg m1035]
• 900003 (2026-10-30): "I won't be home on November 14. I forgot about my sister's wedding." [related by inference] [msg m1018]
```
**C** (ANSWER; cites 1023 1018 1021):
```
- Dana decided on the party (Sunday, November 15, 2 PM to 4 PM; at Maya's backyard): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [m1023]
- Maya said about November 14 (on November 14): "I won't be home on November 14. I forgot about my sister's wedding." [m1018]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
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

**B** (ANSWER; cites 1015 1028 1014 1023 1018 1027 1029 1032):
```
What the group said:
• 900002 (2026-10-30): "I can bring 15 aprons. I'll buy them on November 7." [msg m1015]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [msg m1028]
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [related by inference] [msg m1023]
• 900003 (2026-10-30): "I won't be home on November 14. I forgot about my sister's wedding." [related by inference] [msg m1018]
• 900003 (2026-10-30): "I said I'd get clay; correction: I can also bring disposable tablecloths." [related by inference] [msg m1027]
• 900001 (2026-10-30): "We need someone to manage RSVPs. I haven't assigned that." [related by inference] [msg m1029]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
```
**C** (ANSWER; cites 1015 1028):
```
- Yossi committed to 15 aprons (on November 7): "I can bring 15 aprons. I'll buy them on November 7." [m1015]
- Yossi said about the aprons (November 10): "Update: the aprons arrive November 10, so I can still bring them." [m1028]
(These are group members' statements, not verified facts.)
```

### Q4. What is Ron responsible for?

**B** (ANSWER; cites 1031 1030 1039 1029 1032):
```
What the group said:
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [msg m1031]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [related by inference] [msg m1030]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [related by inference] [msg m1039]
• 900001 (2026-10-30): "We need someone to manage RSVPs. I haven't assigned that." [related by inference] [msg m1029]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
```
**C** (ANSWER; cites 1016 1024 1030 1003 1007 1011 1021 1026 1035):
```
- Ron committed to drinks (for the kids): "I'll handle drinks: water and juice boxes for the kids." [m1016]
- Ron offered a portable speaker for background music: "I can bring a portable speaker for background music." [m1024]
- Ron offered RSVPs (if you send me the guest list): "I can handle RSVPs if you send me the guest list." [m1030]
- Ron said about Noa: "Noa doesn't like loud music." [m1003]
- Ron recommended the activity budget: "I suggest we keep the activity budget under $400." [m1007]
- Ron recommended a backyard workshop: "That's over our $400 activity budget. I prefer a backyard workshop." [m1011]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
- Ron said about the rain plan (by November 12): "Reminder: we should decide on the rain plan by November 12." [m1035]
(These are group members' statements, not verified facts.)
```

### Q5. What is Maya bringing?

**B** (ANSWER; cites 1039 1032 1031 1030 1028 1026 1033):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [related by inference] [msg m1031]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [related by inference] [msg m1030]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [related by inference] [msg m1028]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [related by inference] [msg m1026]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [related by inference] [msg m1033]
```
**C** (ANSWER; cites 1001 1012 1005 1009 1018 1022 1027 1032 1036):
```
- Maya offered my backyard (if the weather is good): "I can host in my backyard if the weather is good." [m1001]
- Maya offered a clay workshop in the backyard (in the backyard): "I can lead a clay workshop in the backyard." [m1012]
- Maya said about ColorHouse (for children): "There's also ColorHouse. They run painting workshops for children." [m1005]
- Maya said about 14 children total (14 children total): "two siblings will also attend, so we'd have 14 children total." [m1009]
- Maya said about November 14 (on November 14): "I won't be home on November 14. I forgot about my sister's wedding." [m1018]
- Maya said about my backyard (November 15): "November 15 works for me too, and my backyard is available." [m1022]
- Maya said about disposable tablecloths: "I said I'd get clay; correction: I can also bring disposable tablecloths." [m1027]
- Maya said about the backyard (if it rains): "Potential issue: if it rains, the backyard won't work." [m1032]
- Maya recommended plain cheese pizza and fruit (For food): "For food, I recommend ordering plain cheese pizza and fruit." [m1036]
(These are group members' statements, not verified facts.)
```

### Q6. Has the rain venue been booked?

**B** (ANSWER; cites 1035 1032 1033 1020 1028 1034 1026 1039):
```
What the group said:
• 900004 (2026-10-30): "Reminder: we should decide on the rain plan by November 12." [msg m1035]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [msg m1032]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [msg m1033]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [related by inference] [msg m1020]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [related by inference] [msg m1028]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [related by inference] [msg m1034]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [related by inference] [msg m1026]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [related by inference] [msg m1039]
```
**C** (ANSWER; cites 1033 1034 1035):
```
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
- Dana said about The community center: "The community center is only a suggestion, not a confirmed backup." [m1034]
- Ron said about the rain plan (by November 12): "Reminder: we should decide on the rain plan by November 12." [m1035]
(These are group members' statements, not verified facts.)
```

### Q7. Is the food finalized?

**B** (ANSWER; cites 1036 1038 1033 1040 1037 1034 1032 1029):
```
What the group said:
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [msg m1036]
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [msg m1038]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [related by inference] [msg m1033]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [related by inference] [msg m1040]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [related by inference] [msg m1037]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [related by inference] [msg m1034]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
• 900001 (2026-10-30): "We need someone to manage RSVPs. I haven't assigned that." [related by inference] [msg m1029]
```
**C** (ANSWER; cites 1036 1038):
```
- Maya recommended plain cheese pizza and fruit (For food): "For food, I recommend ordering plain cheese pizza and fruit." [m1036]
- Yossi said about food (until we confirm dietary restrictions): "Then let's not finalize food until we confirm dietary restrictions." [m1038]
(These are group members' statements, not verified facts.)
```

### Q8. Who is ordering the cake?

**B** (ANSWER; cites 1036 1039 1040 1038 1033 1032 1031 1030):
```
What the group said:
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [msg m1036]
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [msg m1040]
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [related by inference] [msg m1038]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [related by inference] [msg m1033]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [related by inference] [msg m1031]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [related by inference] [msg m1030]
```
**C** (ANSWER; cites 1040):
```
- Dana committed to the cake (only after we confirm dietary restrictions): "I will order the cake, but only after we confirm dietary restrictions." [m1040]
(These are group members' statements, not verified facts.)
```

### Q9. Why was ColorHouse not chosen?

**B** (ANSWER; cites 1005 1034 1010 1037 1006 1008 1038 1009):
```
What the group said:
• 900003 (2026-10-30): "There's also ColorHouse. They run painting workshops for children." [msg m1005]
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900002 (2026-10-30): "For 14 kids, ColorHouse could cost around $448, based on that quote." [msg m1010]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [msg m1037]
• 900002 (2026-10-30): "ColorHouse charges about $32 per child, according to their quote last month." [msg m1006]
• 900001 (2026-10-30): "Let's invite 12 children, not counting siblings." [msg m1008]
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [msg m1038]
• 900003 (2026-10-30): "Actually, two siblings will also attend, so we'd have 14 children total." [related by inference] [msg m1009]
```
**C** (ANSWER; cites 1005 1006 1010):
```
- Maya said about ColorHouse (for children): "There's also ColorHouse. They run painting workshops for children." [m1005]
- Yossi relayed (from their quote last month) about ColorHouse (per child): "ColorHouse charges about $32 per child, according to their quote last month." [m1006]
- Yossi said about ColorHouse (For 14 kids): "For 14 kids, ColorHouse could cost around $448, based on that quote." [m1010]
(These are group members' statements, not verified facts.)
```

### Q10. What should be decided by November 12?

**B** (ANSWER; cites 1035 1008 1014 1020 1021 1015 1022 1019):
```
What the group said:
• 900004 (2026-10-30): "Reminder: we should decide on the rain plan by November 12." [msg m1035]
• 900001 (2026-10-30): "Let's invite 12 children, not counting siblings." [msg m1008]
• 900003 (2026-10-30): "Yes. I can get the clay, but someone else should bring aprons." [qualified] [msg m1014]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [msg m1020]
• 900004 (2026-10-30): "November 15 works for me." [msg m1021]
• 900002 (2026-10-30): "I can bring 15 aprons. I'll buy them on November 7." [msg m1015]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900001 (2026-10-30): "Thanks for catching that. November 14 is no longer an option." [msg m1019]
```
**C** (ANSWER; cites 1023 1008 1009 1018 1021):
```
- Dana decided on the party (Sunday, November 15, 2 PM to 4 PM; at Maya's backyard): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [m1023]
- Dana said about 12 children (12 children; not counting siblings): "Let's invite 12 children, not counting siblings." [m1008]
- Maya said about 14 children total (14 children total): "two siblings will also attend, so we'd have 14 children total." [m1009]
- Maya said about November 14 (on November 14): "I won't be home on November 14. I forgot about my sister's wedding." [m1018]
- Ron said about November 15 (November 15): "November 15 works for me." [m1021]
(These are group members' statements, not verified facts.)
```

### Q11. Is the community center definitely available?

**B** (ANSWER; cites 1034 1033 1022 1032 1036 1029 1037 1021):
```
What the group said:
• 900001 (2026-10-30): "The community center is only a suggestion, not a confirmed backup." [qualified] [msg m1034]
• 900002 (2026-10-30): "I recommend the community center as a rain backup, but I haven't checked availability." [qualified] [msg m1033]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [msg m1022]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
• 900003 (2026-10-30): "For food, I recommend ordering plain cheese pizza and fruit." [related by inference] [msg m1036]
• 900001 (2026-10-30): "We need someone to manage RSVPs. I haven't assigned that." [related by inference] [msg m1029]
• 900001 (2026-10-30): "One child may have a dairy allergy; this is not confirmed yet." [related by inference] [msg m1037]
• 900004 (2026-10-30): "November 15 works for me." [related by inference] [msg m1021]
```
**C** (ANSWER; cites 1033 1034):
```
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
- Dana said about The community center: "The community center is only a suggestion, not a confirmed backup." [m1034]
(These are group members' statements, not verified facts.)
```

### Q12. What activity does Noa prefer?

**B** (ANSWER; cites 1011 1004 1007 1025 1003 1010 1006 1020):
```
What the group said:
• 900004 (2026-10-30): "That's over our $400 activity budget. I prefer a backyard workshop." [msg m1011]
• 900001 (2026-10-30): "Noa prefers crafts and science activities to loud games." [msg m1004]
• 900004 (2026-10-30): "I suggest we keep the activity budget under $400." [msg m1007]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900004 (2026-10-30): "I heard PlayLab gets noisy. Noa doesn't like loud music." [msg m1003]
• 900002 (2026-10-30): "For 14 kids, ColorHouse could cost around $448, based on that quote." [related by inference] [msg m1010]
• 900002 (2026-10-30): "ColorHouse charges about $32 per child, according to their quote last month." [related by inference] [msg m1006]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [related by inference] [msg m1020]
```
**C** (ANSWER; cites 1003 1004 1011):
```
- Ron said about Noa: "Noa doesn't like loud music." [m1003]
- Dana said about Noa: "Noa prefers crafts and science activities to loud games." [m1004]
- Ron recommended a backyard workshop: "That's over our $400 activity budget. I prefer a backyard workshop." [m1011]
(These are group members' statements, not verified facts.)
```

### Q13. What is the difference between Ron's initial speaker proposal and final decision?

**B** (ANSWER; cites 1023 1024 1025 1026 1020 1028 1022 1032):
```
What the group said:
• 900001 (2026-10-30): "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard." [msg m1023]
• 900004 (2026-10-30): "I can bring a portable speaker for background music." [msg m1024]
• 900001 (2026-10-30): "Please skip the speaker. Noa is sensitive to loud sound." [msg m1025]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [msg m1026]
• 900002 (2026-10-30): "What about Sunday, November 15, from 2 PM to 4 PM?" [related by inference] [msg m1020]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [related by inference] [msg m1028]
• 900003 (2026-10-30): "November 15 works for me too, and my backyard is available." [related by inference] [msg m1022]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
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

**B** (ANSWER; cites 1039 1040 1032 1031 1038 1030 1028 1026):
```
What the group said:
• 900004 (2026-10-30): "Who is bringing the cake? I don't think we assigned that." [msg m1039]
• 900001 (2026-10-30): "I will order the cake, but only after we confirm dietary restrictions." [qualified] [msg m1040]
• 900003 (2026-10-30): "Potential issue: if it rains, the backyard won't work." [related by inference] [msg m1032]
• 900001 (2026-10-30): "Ron, please handle RSVPs. I'll send you the list tonight." [related by inference] [msg m1031]
• 900002 (2026-10-30): "Then let's not finalize food until we confirm dietary restrictions." [related by inference] [msg m1038]
• 900004 (2026-10-30): "I can handle RSVPs if you send me the guest list." [related by inference] [msg m1030]
• 900002 (2026-10-30): "Update: the aprons arrive November 10, so I can still bring them." [related by inference] [msg m1028]
• 900004 (2026-10-30): "Understood. No speaker. I'll bring drinks only." [related by inference] [msg m1026]
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

