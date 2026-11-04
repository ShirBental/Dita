# TASK-044 FINAL REPORT: Does structured memory make Dita better? (Nick Fury, 2026-10-31)

All results are **MEASURED** on the user's Mac against the real stack (PostgreSQL + relay + Neo4j + Vespa), unless marked otherwise.
- The 15 questions per dataset are scored with a frozen rubric, using the pre-registered *user-facing* criterion: all rubric checks pass, the facts appear in the first two answer lines, and no superseded message is cited.
- **Arms:** A = BM25 only · B = Dita without structured knowledge · C = Dita with Muse-extracted knowledge. Same messages, questions and scorer for all three.
- Synthetic data only.

## 1. Results across three datasets

| Dataset | Independent? | Code | A | B | C | C vs B paired (better / worse) | C precision (A / B) |
|---|---|---|---|---|---|---|---|
| Synthetic Lab (dev) | **No**: fixes made after seeing it | DEC-033 | 5/15 | 7/15 | **9/15** | 2 / 0 | 0.35 (0.41 / 0.17) |
| Holdout v1 (Peter, sealed) | Yes (DEC-032 code) | DEC-032 | 4/15 | 3/15 | **5/15** | 2 / 0 | 0.57 (0.35 / 0.26) |
| Holdout v2 (Peter, sealed) | **Yes**: frozen code, sealed before the run | DEC-033 | 3/15 | 4/15 | **4/15** | **0 / 0** | 0.39 (0.40 / 0.17) |

**Pooled independent holdouts (v1 + v2, n=30):**
- **C 9/30, B 7/30, A 7/30.**
- C vs B paired: **+2 / −0**. C vs A: +4 / −2.

**Dimension detail on the holdouts:**

| Dimension | v1 A | v1 B | v1 C | v2 A | v2 B | v2 C |
|---|---|---|---|---|---|---|
| Temporal / supersession | 0/2 | 0/2 | **2/2** | 1/3 | 1/3 | **2/3** |
| Speaker attribution | 3/6 | 5/6 | **6/6** | 3/6 | **4/6** | **4/6** |
| Required sources | 8/15 | **12/15** | 8/15 | **7/15** | 6/15 | **7/15** |
| Qualifiers | **4/6** | **4/6** | 3/6 | **2/3** | **2/3** | 1/3 |
| Facts present | 7/12 | **9/12** | **9/12** | **7/12** | **7/12** | 6/12 |

- **Invented content:** 0 on every dataset; every line is a verbatim, attributed quote.
- **Misattribution:** 0 on both holdouts.
- **Superseded-as-current (Bruce's audits, judged):**
  - v1: 0, but only because the earlier versions were never extracted.
  - **v2: 4 knowledge lines and 3 "Related message" lines showed stale content as current**, across 3 answers. The scorer's temporal check caught 1.
- **v2 relations:** 0 of 3 expected SUPERSEDES formed, and **1 of 1 created relation was false**. That false relation hid a valid message.
- **v2 labels:** 3 false "[no answer was recorded]" labels.
- Deletion, injection and multi-group cases were **not tested** by the holdouts (Bruce, v1 audit).

## 2. Verdict

1. **Does structured memory outperform the baselines on v2?**
   - **No.** C ties B (4/15, identical pass/fail on all 15 questions) and beats BM25 by one (paired +2/−1).
   - C's v2 advantages are citation precision against B (0.39 vs 0.17) and conciseness.
   - Its temporal score (2/3 vs 1/3) hides stale lines the scorer did not catch (Bruce).
2. **Is the improvement consistent across independent datasets?**
   - **Partly.** Citation precision against B improves on every dataset.
   - Supersession works on the dev conversation, but **is not reliable on new conversations** (v2: 0/3 formed, 1 false).
   - Answer accuracy improves on v1 (+2), and on dev (+2, not independent), but **not on v2 (0)**.
   - Pooled over 30 independent questions, C vs B is +2/−0. That's a small, positive, statistically inconclusive signal.
3. **Genuinely demonstrated capabilities:**
   - extracting attributed, verbatim, qualifier-carrying claims from a conversation;
   - storing and projecting them consistently across PostgreSQL, Neo4j and Vespa;
   - on the dev conversation, **recognising superseded decisions and plans** (verified repeatedly with the recorded extraction);
   - correct speaker attribution;
   - far less irrelevant quoting than Dita's message-graph answers;
   - no invented content;
   - group-scoped retrieval.
4. **Unreliable capabilities:**
   - answering the actual question: **overall accuracy is 27–33% on independent data**;
   - **supersession and contradiction on new conversations**: wrong or missing relations, and stale content shown;
   - **retrieval**: word-form and paraphrase mismatch; the named-member gate excludes other members' answers. These cause 6 of 11 v2 failures;
   - "[no answer was recorded]" labelling (3 false on v2);
   - "Related message" lines (no measured benefit; they add stale and off-topic lines);
   - multi-part answers;
   - paraphrased questions (keyword retrieval only);
   - qualifier preservation;
   - extraction coverage (earlier versions of replaced facts are not extracted; first-clause quotes);
   - UNKNOWN / unanswered questions;
   - person-centred questions without a names policy.
5. **Can Dita support a controlled hackathon demo?** **DEMO LIMITED.**
   - **Defensible only as a scripted scenario on the verified Synthetic Lab conversation with its recorded extraction:** "the group changed its plan; ask Dita what was decided", showing the current decision, its source and the superseded plans by message id, plus a commitment-with-qualifier question.
     - These answers are deterministic for that recorded extraction and passed on the real stack repeatedly.
   - **Not demonstrable:** live extraction of a new conversation followed by questions (v2 showed stale content and false relations).
   - **Open-ended Q&A is not reliable enough to demo.**
6. **Before use with real groups (beyond the demo):**
   - **Consent and egress:** real-chat egress stays **BLOCKED** (DEC-026). Telegram messages are PLATFORM-classified, and Muse extraction of real chats needs the E-1…E-11 controls reviewed on real traffic.
   - **Display names:** a Natasha-approved names policy. Today the default is ids only.
   - **Open security findings:** N-79 (injection-guard bypasses), N-82 (context-message rules), N-90 (stale "Related message"), N-92 (quote delimiting/spoofing), N-76/N-80/N-81 (partly fixed).
   - **Evidence:** deletion, edit, injection and multi-group behaviour measured on held-out data (not yet covered).
   - **Extraction quality:** coverage, earlier versions, multi-span quotes, noun-phrase subjects.

## 3. Cost and latency (MEASURED)

| Live Muse call | Prompt / completion tokens | Cost at the user's prices |
|---|---|---|
| Lab extraction | 2,602 / 6,698 | $0.031719 |
| Holdout v1 | 2,766 / 7,252 | $0.034279 |
| Holdout v2 | 2,710 / 9,898 | $0.045454 |
| **Total** | 3 calls | **$0.111452** |

- Every request was byte-identical to Natasha's reviewed preview, and every leak scan passed.
- Every later run reused the saved replies: no further cost.
- **Answer latency (real stack, C):** v2 median 113 ms, max 161 ms per question; dev 78–153 ms. Extraction is about 25–35 s per 40-message batch, including stack setup.

## 4. Security status

- **Natasha reviews:**
  - lab call, holdout v1 call, holdout v2 call: all APPROVE (with conditions, all enforced in the launcher and test);
  - DEC-031 and DEC-033 trust-boundary reviews: APPROVE WITH CONDITIONS, conditions met.
- **Findings N-75…N-94 registered** (next free N-95).
  - Fixed: N-75, N-77, N-84, N-85, N-86, N-87, N-88, N-89, N-91, N-93.
  - Partly fixed: N-76, N-80, N-81.
  - Open: N-78 (enforced by pre-checks), N-79, N-82, N-83/N-94 (procedural sealing), N-90, N-92.
- **Not a security boundary:** no OS isolation; holdout sealing is procedural. **Real-chat egress remains blocked.**

## 5. What was built in TASK-044 (summary)

- **Structured-memory reconciliation:**
  - SUPERSEDES/CONTRADICTS computed by rule (DEC-029/030/031);
  - the validator downgrades unsupported decisions and commitments instead of discarding them, and a deferral is never a decision.
- **Knowledge retrieval:**
  - Vespa knowledge candidates + Dita re-ranking (DEC-032);
  - KnowledgeTruth projection wiring.
- **Question-directed composer:**
  - the direct answer first; current same-subject claims attached; superseded plans named by id;
  - contradiction pull-in; unanswered-question signal; supporting message evidence (DEC-033).
- **Safety and evaluation infrastructure:**
  - approval TTL;
  - the injection guard;
  - a byte-pinned, single-call, leak-scanned live launcher;
  - sealed holdouts with aggregate-only audits;
  - pre-registered scoring.

## 6. Remaining blockers and the smallest next milestone

**The blocker to a stronger claim:** answer accuracy on independent data is ~30%, and C's advantage over B is not consistent (v2: tie).

**The two independent audits disagree on the dominant cause:**
- **v1:** extraction 3, composition 4, rubric 3.
- **v2:** retrieval 6, composition 3, reconciliation 1, extraction 1; 10 of 11 failures had the evidence stored but unused.

**Smallest next product milestone (no rewrite, no new infrastructure): "trustworthy current state"**, fixing what made v2 *wrong* rather than merely incomplete:
1. **Reconciliation:**
   - check change wording on the full message, not only the quote;
   - a restatement is never SUPERSEDES;
   - a hearsay confirmation outranks an offer;
   - group a question and its answers under one subject.
2. **Retrieval:** English inflection normalisation in Dita's tokenizer, and a named-member gate that also admits other members' on-topic items.
3. **Labels:** "[no answer was recorded]" only when the reply structure shows no answer.
4. **"Related message" lines:** off by default (no measured benefit).

Measure **once** on a fresh sealed holdout v3 with Bruce's rubric recommendations pre-registered. v1 and v2 have now been examined in aggregate and are no longer blind.

**TASK-044 is closed** after this report. The demo should use the scripted supersession scenario only.
