# TASK-045 FINAL REPORT: Trustworthy Current State (Nick Fury, 2026-10-31)

**Status: PARTIAL. Reliability criteria NOT verified on independent cases. Not ready for holdout-v3.**

Scope of the evidence:
- All results are offline replays of recorded Muse replies, in memory, unless marked **REAL STACK**. No live model calls; $0 spent.
- Holdout v1/v2 were used for totals only and no rule was fitted to them; they are no longer blind.
- Holdout v3 is sealed and was not opened or run.

## 1. Root causes and fixes (DEC-034, DEC-035)

| Defect class (TASK-044 / new) | Root cause | Fix |
|---|---|---|
| Correction wording outside the quote ignored | RelationRules read only the quote | Reads the full live message (vetoes read from the quoted clause) |
| Restatement / hedge / additive text retired valid claims | Same subject + same predicate ⇒ SUPERSEDES | Restatement, hypothetical and continuity vetoes. Weak paths need change or retraction wording plus a referent check. Different POPULATION/CONTEXT scope (both sides) never supersedes |
| Marker substrings ("finally"→final, "overbooked"→booked) | Substring matching | Word-boundary matching. Hebrew whole words with one prefix letter ("האם" ≠ "if") |
| Deferral in another clause blocked decisions; hedged/negated decisions/commitments accepted | Whole-message checks; no negation | Clause-scoped deferral; hedged DECISION and negated commitment downgraded |
| Word-form misses (booked/booking, kick-off/kickoff) | No inflection normalisation | Conservative base forms + hyphen joins (knowledge path) |
| Named-member gate excluded other members' answers | Gate admitted only own/mentions | Others' on-topic items admitted after them |
| Stale value shown as current with no marker | No relation without change wording | Answer-time value-slot check: newest leads, older marked "[earlier statement; a later one exists]" |
| False "[no answer was recorded]" | Only a same-subject claim counted as an answer | Reply structure + on-topic later claims count. New label "[asked; Dita found no reply to it in the group's messages]" |
| "Related message" lines re-introduced stale or off-topic content | Always on | **OFF by default** (production bean asserted) |
| **New, REAL STACK: unsupported fallback answers (lab Q2/Q15)** | Vespa `userQuery()` matched only "the". The extractive fallback treated every hit as evidence | A direct hit counts as evidence only if it shares a content word with the question. Reply/graph-linked hits are kept only with a relevant anchor. Backend-independent |

**Q2/Q15 trace (dev lab):**
- **Knowledge path:** found nothing. Evidence exists (m1018; m1023 + sources), but it is paraphrased: *evidence exists, not retrieved*.
- **In memory:** the lexical fallback found nothing → UNKNOWN.
- **Real stack:** Vespa returned 8 messages that shared only "the" with the question. The fallback rendered them as an answer: *retrieved evidence does not answer the question*.
- The behaviour check scored that as correct; it was an unsupported answer.

**Regressions I introduced and then fixed (dev lab only):**
- the referent veto blocked the explicit DECISION path;
- the scope veto fired when only one side had a scope qualifier;
- the fallback gate dropped valid answers reached through reply edges (seen as B-arm totals changing; diagnosed with synthetic tests).

## 2. Measurements

| | Before (DEC-033) | Frozen (DEC-035) |
|---|---|---|
| Trust suite, 53 dev cases (visible while coding) | 17/46 pass, FALSE sup 11, VALID hidden 12, STALE 11 | 51/53 pass, 0 / 0 / 0, false no-answer 0; only the 2 paraphrase cases (expected to fail) fail; deterministic across batch splits |
| **Bruce B2, 20 UNSEEN cases, run once on frozen code** | (Bruce predicted 3/20 on DEC-034) | **5/20 pass; supersessions 4/9; FALSE 4; VALID hidden 4; STALE/off-topic 8; false no-answer 1** |
| Full regression | — | 418 tests, 0 failures (64 skips need the live stack or a live call) |
| Lab C (dev) user-facing | 9/15, precision 0.46 | 9/15, precision 0.52 |
| Holdout v1 C | 6/15 (0.46) | 5/15 (0.60); qualifiers 6→2 |
| Holdout v2 C | 4/15 (0.45) | 5/15 (0.50) |
| B arms (no knowledge) | lab 6, v1 4, v2 4 | identical totals |

**Lab on the real stack:**

| Code | A | B | C | C precision | Stores |
|---|---|---|---|---|---|
| DEC-034 | 5/15 | 7/15 | 9/15 | 0.38 | PG 41 items / 3 relations / 3 superseded = Neo4j 41/3 = Vespa 41 |

- The difference from in memory is only Q2/Q15 (the unsupported fallback above).
- **REAL STACK, FROZEN DEC-035 build (run by the user):** PASS; stores agree (41/3/3).
  - A: 5/15.
  - B: 6/15, precision 0.37 (was 7/15 at 0.17; 51 vs 120 citations).
  - **C: 9/15, precision 0.52, behaviour 13/15, now identical to in memory.**
  - Q2/Q15 return "not found" instead of 8 unsupported citations.
  - **Real cost:** B lost lab Q6. The relevance gate dropped a valid paraphrase-only answer (m1034, "only a suggestion, not a confirmed backup") that previously appeared by accident among 5 stopword hits. A lexical gate cannot tell these apart; no change after the freeze.
  - B Q3 fails on Vespa's ordering (the fact sits on line 3), not on the gate.

**Holdout audit (Bruce, aggregate only):**
- Pooled v1+v2 C: 10/30 → 10/30 on DEC-034.
- 2 of the v1 losses are a scorer artifact: the new unanswered label misses the rubric's phrasings.
- 2 more are from Related being off: the qualifier/fact wording existed only in those lines.
- After the change, v2 still has 3 stale-as-current answers that the scorer does not catch (before: 4).

## 3. Reviews

- **Bruce (independent):** the fixes are *partly real and narrow*.
  - The marker lists match his first case set almost word for word.
  - B2 confirms poor generalisation, with HIGH-severity defects:
    - unlisted change and retraction wording ("got pushed to", "is off for me");
    - change and retraction words used in another sense ("moved to the loft", "cancelled my gym class");
    - the strong DECISION path has no subject guard (colliding subject ids);
    - the reply parent can satisfy the referent check;
    - Hebrew prefixed subject forms;
    - stale cross-speaker decisions lead;
    - parallel values are over-marked as earlier;
    - wh-word subjects defeat the answered check;
    - multi-part questions collapse.
  - Not ready for v3 until these are fixed or explicitly accepted, and the v3 scoring is pre-registered: temporal scoring that accepts marked superseded citations, plus a manual meaning review and manual stale/label audit counts.
- **Natasha:** **APPROVE WITH CONDITIONS** for local synthetic use and one future v3 run; **BLOCK** for any real-group use.
  - New findings N-95…N-102. After DEC-035: N-96, N-97, N-102 fixed; N-95, N-98, N-101 partly fixed; N-99, N-100 open. N-90 mitigated.
  - Group isolation PASS, no new egress, no ReDoS.
  - Conditions for v3: its own egress pre-flight with your explicit approval; the frozen rules; strict suite recorded; Related off with the ablation reported separately; sealed handling.
- **Tony:**
  - Rules are pure and order-independent within a pass.
  - **No relation-recompute path.** Supersession is one-way (not reversed on deletion), and reply-parent timing can matter.
  - He recommended R1–R7; R1–R6 were applied before the freeze. R7 (freeze manifest) is done: `eval/TASK-045/FREEZE-MANIFEST.sha256`. R8 (recompute, un-supersede) is deferred.
  - The 6/15 ↔ 4/15 swings are within noise.

## 4. Success criteria

1. **Partly met.**
   - Dev regression: false-supersession classes are covered with 0 false.
   - Unseen B2: 4 false supersessions hide valid claims.
2. **Partly met.**
   - Related off; stale marking.
   - B2: 8 stale/off-topic citations.
3. **Partly met.**
   - Word forms: met on dev.
   - B2-G1 collision ("evening"/"even") admits a distractor.
   - Paraphrase is not handled (expected; lexical only).
4. **Partly met.**
   - Dev: 0 false no-answer.
   - B2-N1: 1 false label.
5. **Met.** Related off by default, asserted on the production bean.
6. **Met.** 418/418.
7. **Met (lab):** the frozen build is verified on PostgreSQL/Neo4j/Vespa. The holdouts were replayed in memory only.
8. **Met.** Bruce reviewed.
9. **Met with conditions.** Natasha signed off for synthetic use only.

Passing these would not mean Dita is reliable, and they did not all pass.

## 5. Remaining limitations

- Rule-based reconciliation relies on English/Hebrew marker lists, and B2 shows it does not generalise to new phrasing.
- Model-assigned subject ids are trusted for pairing.
- No paraphrase retrieval.
- No relation recompute and no un-supersede on deletion (N-98).
- Edited text steers relations (N-99).
- The unanswered label skips the message path (N-100).
- Quote spoofing (N-92).
- Injection-guard bypasses (N-79).
- Accuracy on independent data is about 30%.
- **Found by B2, not fixed (post-freeze):** `quotedClause` extends past a quote that ends in a full stop into the next sentence. That is why "Text me if you get lost" vetoed B2-S2.
- Real-chat egress remains blocked (DEC-026).

## 6. Holdout-v3 readiness and next milestone

**Not ready.** Running v3 now would measure rules already shown to fail on new wording.

**Recommended next milestone: "Reconciliation that generalises."** Make supersession less dependent on surface markers, without a redesign:
- fix the `quotedClause` boundary bug;
- a subject/predicate guard on the strong path;
- referent checks only on the speaker's own message;
- Hebrew subject matching with prefix stripping;
- recency-aware lead selection across speakers;
- value-slot marking that respects differing scope words ("weekdays"/"weekends");
- the wh-word and multi-part fixes in the unanswered logic;
- relation recompute + un-supersede on deletion (N-98).

**Process:**
- B2 is now **development data**.
- Bruce writes a **B3** unseen set after the next freeze.
- Pre-register the v3 scoring changes above **before** anyone sees v3.
- Then one v3 run, which needs your explicit approval for its single Muse call.

## 7. The one command for your Mac (frozen build, real stack, recorded reply, no cost)

```bash
cd ~/Dev/Dita && bash scripts/lab-recorded-verify.sh
```

Expected: the stores agree as before. Q2/Q15 should now be "not found" instead of 8 unrelated citations: precision up, behaviour possibly 13/15. Real-stack verification of the frozen build is not claimed until you run it.

**Files changed in TASK-045:**
- **Main code:**
  - dita-core `knowledge/{RelationRules,EvidenceValidator,KnowledgeAnswerComposer,ExtractionService,LexicalKnowledgeRetriever}.java`;
  - `text/Tokenizer.java`, `answer/{Stance,ExtractiveAnswerComposer}.java`, `QueryService.java`;
  - `dita-core/pom.xml` (test-scope jackson).
- **Tests:**
  - `TrustworthyStateSuiteTest` (new), `FallbackRelevanceTest` (new);
  - `StructuredMemoryRulesTest`, `ApplicationStartupTest`, `SyntheticLabKnowledgeTest` (ablation switch).
- **Fixtures:** `src/test/resources/trust/{fury-cases,fury-cases-2,bruce-cases}.json`, `trust-unseen/bruce-b2-cases.json`.
- **Records:** `eval/TASK-045/*` (trust reports, replay totals, freeze manifest, B2 report); DEC-034/035; the registry; tasks.md.
