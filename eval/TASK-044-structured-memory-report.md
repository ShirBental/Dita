# TASK-044: Structured memory, failure analysis and repair (Nick Fury, 2026-10-31)

**Dataset:** the Synthetic Lab conversation. It is a *development* set: it was inspected, and fixes were made after seeing its results.
- 41 messages; fixture sha256 ce412888….
- 15 questions; frozen rubric sha256 decbb0e0….
- Scorer: `score.py`, pre-registered sha256 550ddd8e…. The "user-facing correct" criterion requires every rubric check to pass, the key facts to appear in the **first two lines** of the answer, and no superseded message to be cited.
- Peter's sealed holdout was **not** touched (seal verified unchanged).

## Verdict

**Structured memory produced a small, real, but not yet convincing improvement on this development set.**
- After the fixes, Dita with knowledge (C) gets **7/15 user-facing correct**, against Dita without knowledge (B) **6/15** and BM25 (A) **5/15**. Measured offline from the recorded Muse reply, identical inputs.
- C's citation precision is **0.47**, against B **0.21** and A **0.41**. This is the clearest measured benefit: knowledge answers quote far fewer irrelevant messages than Dita's graph retrieval.
- **Decision currency works:** for "When and where is the party?" only C leads with the final decision and hides the superseded November 14 plan.
- One question regressed against B (Q10), and the knowledge layer is still unable to synthesise multi-part answers.
- With n=15, a dev set and +2/−1 paired changes, this is **not evidence of product value**. The independent holdout is the deciding experiment.

## Measured runs

| Run | Stack | Extraction | A | B | C | C citation precision |
|---|---|---|---|---|---|---|
| Live, before fixes (user's Mac) | PostgreSQL + Neo4j + Vespa | live Muse, 1 call, $0.031719 | 5/15 | 7/15 | 6/15 | 0.32 |
| Recorded replay, before fixes | in-memory | recorded reply (no call) | 5/15 | 6/15 | 6/15 | 0.43 |
| Recorded replay, **after fixes** | in-memory | recorded reply (no call) | 5/15 | 6/15 | **7/15** | **0.47** |
| Recorded replay, after fixes | **real stack: NOT EXECUTED** | — | — | — | — | — |

Run on your Mac: `bash scripts/lab-recorded-verify.sh`. It makes no Muse call and has no cost.

**B differs between backends** (7/15 on the real stack vs 6/15 in memory) because B's message retrieval differs: Vespa + Neo4j vs in-memory BM25 + graph. C was 6/15 on both before the fixes.

**Live call:**
- 2,602 prompt tokens and 6,698 completion tokens (2,210 of them reasoning), finish=stop, 27.5 s for the whole test.
- The transmitted request was byte-identical to the reviewed preview, and the leak scan passed.

**Answer latency:**
- real stack: C about 30–170 ms per question (first questions include warm-up of the stack);
- in memory: about 0.6–1.4 ms.

## Per question (after fixes, in-memory recorded; A / B / C user-facing)

| Q | Question | A | B | C | B→C | Root cause if C fails (stage) |
|---|---|---|---|---|---|---|
| 1 | When and where is the party? | NO | NO | **yes** | improved | — The decision (m1023) leads, and the November 14 items are SUPERSEDED (relations m1023→m1017, m1000). |
| 2 | Why was the original date canceled? | NO | NO | NO | same | **Retrieval:** no word shared between the question and m1018 ("my sister's wedding"). The knowledge composer found no item. Lexical-matching limit. |
| 3 | Who is responsible for aprons? | NO | yes | yes | same | — |
| 4 | What is Ron responsible for? | NO | NO | NO | same | **Composer:** lists all 9 of Ron's items. **Extraction:** Ron's withdrawal "No speaker" (m1026) was filed under the subject `drinks`, so the rule cannot supersede the speaker offer (m1024). **Validation fix:** m1031 is now stored (as Dana's statement), but person selection uses the speaker only. |
| 5 | What is Maya bringing? | NO | NO | NO | same | **Extraction:** "I can also bring disposable tablecloths" was labelled `states`, not `offers`. **Composer:** it ranks offers first and cannot synthesise "clay + tablecloths". |
| 6 | Has the rain venue been booked? | NO | NO | **yes** | improved | — |
| 7 | Is the food finalized? | NO | NO | NO | same | **Composer:** single-subject selection. The dairy allergy (subject `dairy_allergy`, m1037) is not shown with `food`. |
| 8 | Who is ordering the cake? | yes | yes | yes | same | — |
| 9 | Why was ColorHouse not chosen? | NO | NO | NO | same | **Composer:** Ron's "$400 budget" (m1011) has the subject `backyard_workshop`, so it isn't selected with `colorhouse`. Reconciliation is now correct: m1006 "$32" is no longer wrongly superseded. |
| 10 | What should be decided by November 12? | yes | yes | NO | **regressed** | **Composer gating:** "12" and "November" matched the `guest_count` and party subjects. The knowledge answer then *suppressed* B's correct message answer. Vespa knowledge retrieval ranked m1035 first, but QueryService does not use it. |
| 11 | Is the community center definitely available? | yes | yes | yes | same | — |
| 12 | What activity does Noa prefer? | yes | yes | yes | same | — |
| 13 | Ron's speaker proposal vs final decision | NO | NO | NO | same | **Composer ordering:** the drinks commitment ranks above the speaker offer and withdrawal, so "No speaker" is not in the first two lines. (On the real stack, B passed this question.) |
| 14 | Who owns the cake and when is it due? | yes | yes | yes | same | — |
| 15 | Which source messages support the final date? | NO | NO | NO | same | **Retrieval:** a meta-question with no word overlap with any message. Not addressable by lexical matching. |

**Groups:**
- **Improved by knowledge:** Q1, Q6.
- **Degraded by knowledge:** Q10. Also Q13 relative to the real-stack B.
- **Unchanged despite relevant stored knowledge:** Q4, Q5, Q7, Q9. In each, the correct claims were in storage and the composer failed.
- **Incorrect knowledge extracted:** subject granularity (Q4: withdrawal under `drinks`; Q9: budget under `backyard_workshop`) and the mislabelled offer (Q5).
- **Correct knowledge ignored:** Q10 (m1035) and Q7 (m1037).

## Extracted knowledge quality (live reply, 42 proposals)

**Before the fixes:** 39 accepted, 3 rejected.
- m1003 hearsay with no named source: a correct rejection.
- m1019 and m1031 proposed as DECISION without decision wording: wrongly lost. Fixed.

**After the fixes:** 41 accepted, 1 rejected.

**Quality is high where it can be checked:**
- every speaker comes from message metadata;
- every quote is a verbatim span;
- qualifiers are real sub-spans ("if the weather is good", "only after we confirm dietary restrictions", "per child", "by November 12");
- the hedges are correct (m1033, m1037);
- the commitments are correct (aprons, drinks, cake);
- Yossi's $32 was correctly labelled hearsay ("according to their quote last month").

**Weaknesses (extraction):**
- subject ids are too fine-grained, so related claims fall under different subjects;
- one offer was labelled `states`;
- no relation hints are requested from the model. By design, relations are computed by rule.

Example claims, with provenance:
- `DECISION · Dana · decides · birthday_party · m1023` "Decision: the party is Sunday, November 15, 2 PM to 4 PM, at Maya's backyard.", qualifiers [Sunday, November 15, 2 PM to 4 PM; at Maya's backyard].
- `CONFIRMED_COMMITMENT · Dana · commits · cake · m1040` "I will order the cake, but only after we confirm dietary restrictions.", qualifier [only after we confirm dietary restrictions].
- `REPORTED_CLAIM/HEARSAY · Yossi · states · colorhouse_price · m1006`, attributed to "their quote last month".
- `ATTRIBUTED_STATEMENT/HEDGED · Yossi · recommends · community_center · m1033`, qualifier [as a rain backup].

## Do structured relationships influence answers? (verified)

- **SUPERSEDES: yes, through PostgreSQL validity.** For Q1, the superseded November 14 items are excluded because `knowledge_item.validity = SUPERSEDED`.
- **Before the fix, two wrong supersessions removed valid facts:**
  - m1010→m1006 lost "$32";
  - m1032→m1022 lost "my backyard is available".
- **Neo4j:** 39 Knowledge nodes, 39 DERIVED_FROM edges and 6 RELATES edges, with validity identical to PostgreSQL for all 39 items. But **QueryService never reads Neo4j relations**. Answering uses PostgreSQL validity only. The Neo4j graph is currently a projection, not an input to answers.
- **Vespa:** 39 knowledge documents, validity consistent with PostgreSQL. `retrieveKnowledge` returns at least one required gold item in its top 5 for 10 of the 14 questions that have required sources (Q11 has none) (e.g. Q10's m1035 at rank 1). But **QueryService does not use Vespa knowledge retrieval**: the composer matches subjects lexically itself.

## Fixes (each: defect → test → fix → rerun)

**DEC-029 (earlier this task):**
- RelationRules (SUPERSEDES / CONTRADICTS by rule);
- "decision:" as a decision marker;
- approval TTL;
- worker toggle;
- member directory and speaker selection;
- KnowledgeTruth projection wiring.

**DEC-030 (this round):**
1. **Reconciliation defect:** a generic `states` item superseded the same speaker's earlier statement without any change signal (m1010→m1006, m1032→m1022).
   - Fix: for the `states` predicate, supersession requires explicit change wording (correction / update / actually / no longer / instead / Hebrew).
   - Test: `aGenericLaterStatementDoesNotSupersedeAnEarlierOneWithoutChangeWording`.
2. **Validation defect:** DECISION / CONFIRMED_COMMITMENT items without the required wording were *discarded*, losing real speech acts (m1019, m1031).
   - Fix: downgrade to ATTRIBUTED_STATEMENT/`states`; the model's stronger label is never stored.
   - Test: `aDecisionWithoutDecisionWordingIsKeptAsAnAttributedStatementNotLost`.
3. **Composer defect:** answers were chronological and included the group's own questions, so the decision came 4th.
   - Fix: question items are dropped when statements exist; order is DECISION > CONFIRMED_COMMITMENT > OFFER > statements, chronological within a rank.
   - Test: `theAnswerLeadsWithTheCurrentDecisionAndDoesNotPresentQuestionsAsAnswers`.
   - Regression caught and fixed: newest-first ordering broke the TASK-043 acceptance slice; reverted to chronological within a rank.

**Full regression suite:** BUILD SUCCESS on all modules (core 79, store-postgres 88, projections 20, muse 26, telegram 30, eval 128, app 21).

**Deliberately not done:**
- no threshold or lexical tuning for Q2, Q7, Q10 or Q15;
- no question-type lexicon;
- no "use Vespa's top hit" rule. Tested against the data, it would break Q3, whose top Vespa hit is Ron's cake *question*.

## Remaining architectural limitations (evidence above)

1. **Knowledge retrieval is lexical subject matching inside the composer.** It misses paraphrases (Q2, Q15), picks wrong subjects (Q10) and ignores Dita's own Vespa knowledge index.
2. **Answers are lists of claims, not composed answers.** Multi-part questions (Q4, Q5, Q7, Q9, Q13) fail the user-facing test, even when every needed claim is in storage. Composing a real answer needs a constrained Muse composition step, which isn't built, or a much richer template layer.
3. **Relations depend on subject ids being consistent across items.** Muse's fine-grained subjects block supersession (the Q4 withdrawal) and grouping (Q7, Q9).
4. **When knowledge matches weakly, it can suppress better message evidence** (Q10).
5. **Neo4j relations are not used at answer time.**

## Next independent experiment

1. Run `bash scripts/lab-recorded-verify.sh` (real stack, recorded reply, no cost) to confirm the after-fix numbers on PostgreSQL + Neo4j + Vespa.
2. Then the **sealed holdout**, run once on the frozen code: `bash scripts/muse-live.sh holdout`.
   - One Muse call, request sha256 ea06b528…, ceiling ~$0.075.
   - Natasha APPROVE WITH CONDITIONS.
   - **Needs your separate explicit authorization.**
   - Builders see only the printed counts (N-83).

## Security status for this work

- **Live lab call:** done under Natasha's conditions. Request byte-identical; one call; key never exposed.
- **Open findings:** N-78 (enforced by launcher pre-checks), N-79 (injection-guard bypasses), N-82 (context-message rules), N-83 (holdout results outside `sealed/`; procedural).
- **Partly fixed:** N-76, N-80, N-81.
- **Real-chat egress stays BLOCKED (DEC-026).**

---

# Round 3 (DEC-031): completing the knowledge-to-answer pipeline (2026-10-31)

## What was wrong (traced before changing code)
- `KnowledgeAnswerComposer` did its own lexical *subject* gating and printed every matching claim chronologically.
- Vespa's knowledge index was never queried.
- Relations mattered only through the validity flag.

## Implemented (existing architecture, no new services)
1. **`KnowledgeRetriever` port** (dita-core).
   - POSTGRES: backed by Vespa's existing `retrieveKnowledge` (BM25, active only, group-filtered; foreign hits throw).
   - Offline / IN_MEMORY: `LexicalKnowledgeRetriever`, BM25 over live verbatim spans + subject + qualifiers + entities + display name.
   - No vector retrieval exists, and none is claimed.
2. **Question-directed composer**, deterministic and grounded:
   - retrieval ranking chooses the claim that answers;
   - relevance gate: a content word must be shared beyond member names, else the message path is used;
   - named members: their own claims first (structured speakerId), then claims that mention them;
   - current same-subject claims are attached to the answer, because they qualify it;
   - CONTRADICTS pulls in the opposing claim ("Conflicting reports");
   - mixed views with different qualifiers keep "Members' views differ by context";
   - SUPERSEDES adds "This replaces earlier messages that are no longer current: [ids]". It shows ids only, never text, per Natasha N-85/N-86;
   - group questions are never presented as answers ("[no answer was recorded]" when only a question matches);
   - at most 4 claim lines.
3. **RelationRules:** CONTRADICTS now compares qualifier **text**, not qualifier kind. Kind-only comparison would have marked "fine for babies" vs "avoid for teenagers" as a contradiction.

**Not implemented: natural-language synthesis.** The answers are selected, verbatim, speaker-attributed claim lines. A model-composed sentence would need a separately approved Muse call. The composer is the deterministic fallback such a step would need anyway.

## Results (development set; recorded Muse reply; in-memory; identical inputs)

| Version | A | B | C user-facing | C citation precision |
|---|---|---|---|---|
| DEC-030 | 5/15 | 6/15 | 7/15 | 0.47 |
| **DEC-031** | 5/15 | 6/15 | **9/15** | **0.55** |

- **C vs B:** C better on Q1, Q6 and Q13; B better on none.
- **C, DEC-030 → DEC-031:** +Q10, +Q13, no regressions.
- An intermediate DEC-031 draft regressed Q6, by dropping Dana's "only a suggestion, not a confirmed backup". It was fixed by the same-subject rule, with a test.

**Still failing:**
- Q2, Q15: no lexical overlap.
- Q4: the withdrawn speaker offer is still shown, because Muse filed the withdrawal under the subject "drinks".
- Q5: the tablecloths offer was extracted as a statement under "supplies".
- Q7: the dairy allergy has its own subject and no word overlap.
- Q9: Ron's $400 budget is under a different subject.

**Real stack: NOT EXECUTED for DEC-031** (this sandbox cannot reach it). The Vespa-backed retriever runs only there. Command: `bash scripts/lab-recorded-verify.sh`. Its report records `knowledge_retriever: vespa-knowledge-bm25`.

## Do relations change answers?
- **SUPERSEDES: yes, visibly.**
  - Q1 now reads "Dana decided on the party … [m1023]", followed by "This replaces earlier messages that are no longer current: [m1000] [m1017] [m1019]".
  - Before, superseded items were silently hidden, or listed as if current.
- **CONTRADICTS:** no such relation occurs in this fixture, so it is unmeasured here. It is covered by a unit test.
- **Neo4j:** not queried at answer time. The relations come from the PostgreSQL truth table (`knowledge_relation`), which holds the same 6 edges Neo4j projects.
  - Traversing Neo4j would return identical data, plus a network dependency. All relations here are one hop, so that adds nothing.
  - Multi-hop traversal would only matter for relation chains, and none occur in this data.

## Example composed answers (C, DEC-031)

**Q6 "Has the rain venue been booked?"** (user-facing correct; B and A were not):
```
- Yossi recommended the community center as a rain backup (as a rain backup) [tentative]: "I recommend the community center as a rain backup, but I haven't checked availability." [m1033]
- Dana said about The community center: "The community center is only a suggestion, not a confirmed backup." [m1034]
…
```

**Q13 "Ron's initial speaker proposal vs final decision"** (user-facing correct; B was not):
```
- Ron offered a portable speaker for background music [offer, not confirmed]: "I can bring a portable speaker for background music." [m1024]
- Dana advised against the speaker: "Please skip the speaker. Noa is sensitive to loud sound." [m1025]
- Ron said about drinks: "Understood. No speaker. I'll bring drinks only." [m1026]
```

## Tests
- **New in StructuredMemoryRulesTest (now 17):** the retrieval ranking picks the answer over number collisions; contradiction pull-in; questions-only shown as "[no answer was recorded]"; foreign or unknown retriever ids ignored; a named member's commitment leads; own claims above mentions; same-subject qualification.
- **Changed:** the supersession test now expects the explicit "no longer current" line (a spec change: superseded information must be recognised, not silently hidden).
- **Full regression suite: BUILD SUCCESS** (core 86, store-postgres 88, projections 20, muse 26, telegram 30, eval 128, app 21). The TASK-043 acceptance slice passes **unchanged**.

## Caveat
These rules were written after reading this dev set's failures. They're general rules with their own synthetic tests, but the +2 on dev is optimistic. **The sealed holdout is the independent measure.**

## Security review (Natasha, DEC-031): APPROVE WITH CONDITIONS, conditions met
- **Group isolation, deletion, injection and display names:** OK.
- **N-85/N-86:** the "replaces" line re-quoted superseded items, uncited. Fixed by showing message ids only.
- **N-87:** the model's subject wording was shown and indexed. Fixed: verbatim-only.
- **N-88:** a Vespa outage failed the question. Fixed: in-scope lexical fallback.
- **N-89:** a deletion check was missing. Fixed.
- **Remeasured after these fixes:** unchanged. C 9/15, precision 0.55; A 5/15, B 6/15.

## Real-stack check of DEC-031 (user's Mac, recorded reply, no call)

| | A | B | C | C precision |
|---|---|---|---|---|
| Real stack, DEC-031 (Vespa ranking) | 5/15 | 7/15 | **7/15** | 0.38 |
| In memory, DEC-031 (Dita ranking) | 5/15 | 6/15 | 9/15 | 0.55 |

**On the real stack, structured memory did not beat Dita without knowledge.**
- C won Q1 (current decision) and Q3; B won Q6 and Q12.
- Cause, from the actual answers: Vespa's no-stemming knowledge field (DEC-028) ranked the wrong claim first.
  - Q12: "I **prefer** a backyard workshop… **activity** budget" outranked "Noa **prefers** crafts and science **activities**".
  - Q6: the rain-plan reminder outranked the community-center claims.
- **DEC-032 fix:** Vespa proposes candidates and Dita's tokenizer ranks them, with a test. **Not yet measured on the real stack.**

## Real stack, DEC-032 (user's Mac, recorded reply, no model call): MEASURED

| | A (BM25) | B (Dita, no knowledge) | C (Dita + knowledge) |
|---|---|---|---|
| User-facing correct | 5/15 | 7/15 | **9/15** |
| Citation precision | 0.41 | 0.17 | **0.39** |

- **Retriever:** `vespa-knowledge-bm25+dita-rerank`. 13 of 15 C answers came from stored knowledge.
- **C passes:** Q1, Q3, Q6, Q8, Q10, Q11, Q12, Q13, Q14.
- **Paired with B:** C better on Q1 (current decision, superseded plans named) and Q3 (aprons); **B better on none**.
- **Answer latency, real stack:** C about 78–153 ms per question.
- **Storage:** PostgreSQL 41 items / 6 relations; Neo4j 41 nodes / 6 RELATES; Vespa 41 documents.

**Status:** structured memory now produces measurably better user-facing answers than both baselines on the real stack, for this **development** conversation (+2 vs B, no losses; less than half B's irrelevant citations). It is not yet independent evidence: the fixes were made after seeing these questions. **Next: the sealed holdout** (one Muse call, needs authorization).

# Independent validation: Peter's sealed holdout (2026-10-31)

**Run:**
- One live Muse call: 2,766 prompt / 7,252 completion tokens, **$0.034279**. Request byte-identical to the reviewed preview; leak scan PASS.
- Real stack. Frozen code (DEC-032).
- Holdout seal verified unchanged before the run. Builders saw only printed counts (N-83).

**Extraction and storage:**
- 26 items accepted, 0 rejected (from 41 messages; the lab conversation gave 41 items). 1 relation, 1 superseded.
- PostgreSQL 26 / 1, Neo4j 26 nodes / 1 RELATES edge, Vespa 26 documents.
- Retriever: `vespa-knowledge-bm25+dita-rerank`.

| | A (BM25) | B (Dita, no knowledge) | C (Dita + knowledge) |
|---|---|---|---|
| User-facing correct | 4/15 | 3/15 | **5/15** |
| Citation precision | 0.35 | 0.26 | **0.57** |
| Temporal (superseded not shown as current) | 0/2 | 0/2 | **2/2** |
| Speaker attribution | 3/6 | 5/6 | **6/6** |
| Qualifiers | 4/6 | 4/6 | 3/6 |
| Facts present | 7/12 | 9/12 | 9/12 |
| Sources | 8/15 | 12/15 | 8/15 |

**Paired:**
- **C vs B:** 2 improved, 13 same, **0 regressed**.
- **C vs A:** C better on 2, A better on 1, both correct on 3, both wrong on 9.

**Verdict on independent data:** structured memory gives a **consistent but small** gain.
- **Gains:** C handles superseded information correctly (2/2 vs 0/2), attributes every speaker correctly, and cites less than half as much noise as Dita without knowledge.
- **Absolute accuracy is low:** 5/15 (33%). C also cites fewer of the required sources than B (8/15 vs 12/15), and keeps fewer qualifiers.
- n=15 with +2/0 paired is **not statistically conclusive**.
- **Root causes of the holdout failures: unknown.** Analysing them requires reading the holdout, which would consume it as an independent set.

# Holdout v1 audit (Bruce, aggregates only) and DEC-033

**Audit of configuration C's 10 holdout failures**, by primary stage:

| Stage | Failures |
|---|---|
| Extraction | 3 |
| Composition | 4 |
| Rubric strictness (answer acceptable) | 3 |
| Validation | 0 |
| Reconciliation | 0 |
| Retrieval | 0 |

- **Evidence:** 5 missing from storage, 3 stored but dropped by the relevance gate, 2 present but placed after the first two lines.
- **Safety:** no invention, no superseded fact shown as current, no misattribution. Not tested by this fixture: deletion, edit, injection and multi-group.
- **Extraction gaps:**
  - earlier versions of replaced facts are never extracted, so SUPERSEDES cannot form;
  - first-clause-only quotes;
  - coverage: 16 of 41 messages yielded nothing;
  - deferral labelled as a decision;
  - pronoun or clause-sized subjects.
- **Composer:** the one-token relevance gate is both too loose and too strict; unanswered questions get padded with other topics.
- **Rubric (for v2 pre-registration):** "first two lines" is too strict for 3-part answers, and the rubric requires corroborating sources.

**DEC-033 (generic fixes, each with a synthetic test):**
- supporting message evidence;
- a deferral is never a decision;
- no pronoun subjects;
- unanswered questions say so.

Dev effect: C 9/15 unchanged; sources 9 → 10; precision 0.55 → 0.46.

**Not done** (would change the Muse request and needs a new review and an authorized call): extracting earlier versions, multi-span quotes, a coverage pass, noun-phrase subjects.

**Next independent measure:** holdout v2 (sealed by Peter; 40 messages, 15 questions; hashes recorded).
