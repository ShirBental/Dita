# TASK-045 trustworthy-current-state suite

cases 46 · pass 17 · expected supersessions formed 11/16 · FALSE supersessions 11 · VALID hidden 12 · STALE shown 11 · evidence recall 34/47 · citation precision 42/54 · FALSE no-answer 0

| case | class | pass | reasons |
|---|---|---|---|
| B-S1 | supersession:change-marker-additive-negative | NO | FALSE SUPERSEDES b1b→b1a; VALID HIDDEN b1a |
| B-S2 | supersession:negated-change-marker-negative | NO | FALSE SUPERSEDES b2b→b2a; VALID HIDDEN b2a; evidence not cited b2a |
| B-S3 | supersession:hedged-update-negative | NO | FALSE SUPERSEDES b3b→b3a; VALID HIDDEN b3a; evidence not cited b3a |
| B-S4 | supersession:marker-in-rejected-proposal-negative | NO | FALSE SUPERSEDES b4b→b4a; VALID HIDDEN b4a; evidence not cited b4a |
| B-S5 | supersession:unlisted-correction-phrase | NO | missing SUPERSEDES b5b→b5a; STALE/OFF cited b5a; text has 'Saturday' |
| B-S6 | supersession:commitment-retraction | NO | missing SUPERSEDES b6b→b6a; STALE/OFF cited b6a |
| B-S7 | supersession:additive-same-predicate-negative | NO | FALSE SUPERSEDES b7b→b7a; VALID HIDDEN b7a; evidence not cited b7a |
| B-S8 | supersession:different-qualifier-same-predicate-negative | NO | FALSE SUPERSEDES b8b→b8a; VALID HIDDEN b8a; evidence not cited b8a |
| B-S9 | supersession:same-speaker-polarity-reversal | NO | missing SUPERSEDES b9b→b9a; STALE/OFF cited b9a |
| B-S10 | supersession:three-step-chain | yes |  |
| B-S11 | supersession:revert-to-original-value | yes |  |
| B-S12 | supersession:reply-correction-subject-in-parent | yes |  |
| B-S13 | supersession:subject-id-collision-negative | NO | FALSE SUPERSEDES b13b→b13a; VALID HIDDEN b13a; evidence not cited b13a |
| B-S14 | supersession:hebrew-update-marker | yes |  |
| B-D1 | decision:marker-substring-finally-negative | NO | FALSE SUPERSEDES b15b→b15a; VALID HIDDEN b15a; evidence not cited b15a; text has 'decided on' |
| B-D2 | decision:deferral-in-other-clause | NO | missing SUPERSEDES b16b→b16a; STALE/OFF cited b16a; text missing 'decided on'; text has 'park' |
| B-D3 | decision:relayed-hedged-mislabel-negative | NO | FALSE SUPERSEDES b17b→b17a; VALID HIDDEN b17a; evidence not cited b17a; text has 'decided on' |
| B-D4 | decision:cross-speaker-decision-leads | NO | lead lacks 'forest' |
| B-C1 | commitment:negated-commitment-marker | NO | text has 'committed to' |
| B-C2 | commitment:marker-substring-overbooked-negative | NO | FALSE SUPERSEDES b20b→b20a; VALID HIDDEN b20a; text has 'committed to' |
| B-H1 | retrieval:named-member-own-vs-relayed | yes |  |
| B-X1 | contradiction:cross-speaker-control | yes |  |
| B-X2 | contradiction:differ-by-context-control | yes |  |
| B-N1 | no-answer:reply-answer-subject-only-in-parent | NO | STALE/OFF cited b24a |
| B-N2 | no-answer:negative-answer-morphology | yes |  |
| B-N3 | no-answer:unanswered-with-shared-word-distractor | NO | STALE/OFF cited b26b; text has 'janitor' |
| B-N4 | no-answer:answered-by-offer-only | NO | STALE/OFF cited b27a |
| B-R1 | retrieval:hyphenation | NO | evidence not cited b28a |
| B-R2 | retrieval:paraphrase (expected to fail) | NO | evidence not cited b29a |
| B-I1 | safety:instruction-to-dita-not-evidence | yes |  |
| B-T1 | stale:repeated-value-change-without-marker | NO | STALE shown unmarked b31a; STALE shown unmarked b31b; lead lacks '$15' |
| B-V1 | validation:stance-negation-idiom | NO | item b32a not stored |
| F-S1 | supersession:correction-outside-quote | NO | missing SUPERSEDES a2→a1; STALE/OFF cited a1; text has '7 PM' |
| F-S2 | supersession:restatement-negative | NO | FALSE SUPERSEDES b2→b1; VALID HIDDEN b1 |
| F-S3 | supersession:complementary-negative | yes |  |
| F-S4 | supersession:hypothetical-negative | yes |  |
| F-S5 | supersession:offer-confirmed-positive | yes |  |
| F-S6 | supersession:other-speaker-negative | yes |  |
| F-R1 | retrieval:morphology | NO | evidence not cited g1 |
| F-R2 | retrieval:morphology | NO | evidence not cited h1 |
| F-R3 | retrieval:paraphrase (expected to fail: lexical only) | NO | evidence not cited i1 |
| F-R4 | retrieval:named-member-answered-by-other | yes |  |
| F-N1 | no-answer:answered-under-other-subject | yes |  |
| F-N2 | no-answer:genuinely-unanswered | yes |  |
| F-T1 | stale:same-speaker-new-value-without-marker | NO | STALE shown unmarked m1; lead lacks '$25' |
| F-T2 | stale:supporting-message-reintroduces-old-plan | yes |  |
