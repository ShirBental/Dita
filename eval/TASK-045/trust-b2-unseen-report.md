# TASK-045 trustworthy-current-state suite

cases 20 · pass 5 · expected supersessions formed 4/9 · FALSE supersessions 4 · VALID hidden 4 · STALE shown 8 · evidence recall 15/20 · citation precision 21/29 · FALSE no-answer 1

| case | class | pass | reasons |
|---|---|---|---|
| B2-S1 | supersession:unlisted-change-verb | NO | missing SUPERSEDES c01b→c01a; STALE/OFF cited c01a; text has 'Saturday' |
| B2-S2 | supersession:correction-with-unrelated-conditional-clause | NO | missing SUPERSEDES c02b→c02a; STALE/OFF cited c02a; text has 'room 10' |
| B2-S3 | supersession:referent-only-in-reply-parent-negative | NO | FALSE SUPERSEDES c03c→c03a; VALID HIDDEN c03a; evidence not cited c03a |
| B2-S4 | supersession:decision-strong-path-on-colliding-subject-id-negative | NO | FALSE SUPERSEDES c04b→c04a; VALID HIDDEN c04a; text missing 'committed to' |
| B2-S5 | supersession:retraction-word-about-something-else-negative | NO | FALSE SUPERSEDES c05b→c05a; VALID HIDDEN c05a; evidence not cited c05a |
| B2-S6 | supersession:retraction-without-listed-marker | NO | missing SUPERSEDES c06b→c06a; STALE/OFF cited c06a |
| B2-S7 | supersession:change-marker-narrative-sense-negative | NO | FALSE SUPERSEDES c07b→c07a; VALID HIDDEN c07a; evidence not cited c07a |
| B2-S8 | supersession:listed-change-marker-new-pattern-control | yes |  |
| B2-S9 | supersession:listed-retraction-new-pattern-control | yes |  |
| B2-H1 | supersession:hebrew-correction-prefixed-subject-form | NO | missing SUPERSEDES c10b→c10a; STALE/OFF cited c10a; text has 'שישי' |
| B2-H2 | decision:hebrew-decision-with-conditional-substring-false-positive | yes |  |
| B2-H3 | supersession:hebrew-listed-retraction-control | yes |  |
| B2-D1 | decision:decision-wording-outside-marker-list (expected to fail) | NO | missing SUPERSEDES c13b→c13a; STALE/OFF cited c13a; text missing 'decided on'; lead lacks 'Olive Tree' |
| B2-X1 | stale:cross-speaker-change-vs-decision-first | NO | STALE shown unmarked c14a; lead lacks 'south' |
| B2-T1 | stale-marking:parallel-values-for-different-days-negative | NO | text has 'earlier statement'; lead lacks 'weekdays' |
| B2-M1 | no-answer:multi-part-question-collapses-to-unanswered-part | NO | evidence not cited c16a |
| B2-N1 | no-answer:answered-without-reply-link-wh-word-subject | NO | evidence not cited c17b; text missing 'Crumbs'; FALSE no-answer label |
| B2-N2 | no-answer:reply-to-off-topic-question-leads | NO | STALE/OFF cited c18b; lead lacks 'green' |
| B2-G1 | retrieval:inflection-base-collision-admits-distractor-negative | NO | STALE/OFF cited c19b |
| B2-V1 | validation:praise-idiom-regex-overreach-negative | yes |  |
