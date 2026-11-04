# TASK-044: Dita Memory Capability Evaluation (Nick Fury, 2026-10-30)

**Question:** does Dita genuinely understand and maintain useful group memory better than a simpler retrieval system?

**Answer from today's measurements: not yet.**
- Today Dita does no better than a plain BM25 keyword search at answering. Both use the same answer composer and verifier.
- Its graph adds recall by quoting more loosely related messages. It does not add understanding.
- The parts meant to provide understanding (the RFC-002 knowledge layer: speech acts, supersession, contradictions, commitments, entities) are built and verified for persistence. But **they were empty in every run**, because extraction needs live Muse, which has not been approved.
- So this evaluates **Dita without its knowledge layer**.

Everything below is labelled **MEASURED** (actually run), **NOT EXECUTED**, or **ESTIMATE/HYPOTHESIS**.

## Setup (MEASURED)

| | Benchmark dev split | Synthetic Lab fixture (separate experiment) |
|---|---|---|
| Data | 4 synthetic corpora: 151 messages (25 + 3×42; the 42-message corpora have one deleted message each), plus isolation decoys; 35 questions with sealed gold | `~/Dev/dita_synthetic_test`, copied unchanged (sha256 recorded): 41 Telegram updates, 4 fictional authors, 15 acceptance questions |
| Ingestion | Production `IngestionPipeline` + `DeletionService` replayed to each question's `as_of` (`StoreReplay`) | Production `TelegramAdapter.handle()` (allow-lists → IngestionPipeline). Ids, authors, text and timestamps preserved. chat.id remapped to a fresh synthetic id; run-start cutoff off for this historical replay |
| Question path | `QueryService.ask`, the same call `/ask` makes | `/ask` updates through the adapter (Telegram reply captured) plus `QueryService.ask` for structured citations |
| Scoring | Benchmark `AnswerMetrics` (deterministic, gated) | `eval/synthetic-lab/score.py` against `rubric.json`, **frozen before any run** (sha256 decbb0e0…) |
| Arms | **D-product** (BM25 + conversation graph) vs **BM25-only** (graph off), same composer | same two arms |
| Backend | in-memory | in-memory |
| Model calls | 0 | 0 |

## 1. Full-answer accuracy and partial correctness (MEASURED, in-memory)

| Metric | Dev: D-product | Dev: BM25-only | Lab: D-product | Lab: BM25-only |
|---|---|---|---|---|
| Fully correct | **12/35 (34.3%)** | **12/35 (34.3%)** | 9/15 all auto checks | 7/15 |
| Behaviour correct (macro over 4 classes) | 0.399 | 0.399 | 13/15 | 13/15 |
| Evidence recall (gold units cited) | 0.664 | 0.608 | sources 10/15 | 8/15 |
| Key facts present in the answer text | — | — | 7/11 | 6/11 |
| Messages quoted per answer (mean) | 4.86 | 3.71 | 6.7 (56% via graph inference) | 2.9 |

**Paired comparison:**
- **Dev:** no question is fully correct for one arm and not the other.
- **Lab:** Dita's +2 (Q6, Q7) comes from extra context reached by graph inference. That's more quotes, not better answers.
- **Lab caveat:** its "auto checks" match facts anywhere in an extractive list of up to 8 quotes, so they **overstate** answer quality. For example, Q1 lists the superseded "Saturday, November 14" plan next to the final decision.

## 2. Evidence recall and source attribution (MEASURED)

- **Attribution: 1.000 on dev.** Every quote is verbatim from the cited message and credited to its real author.
- **Citation precision: 0.476 on dev.** Roughly half of the quoted messages are not evidence for the answer.
- **Display gap (Lab):** Telegram replies show authors as numeric ids (`900001`), not names ("Dana"). The adapter stores only `from.id`. So:
  - "What is Maya bringing?" cannot be answered: Maya's name never appears in her own messages;
  - answers are hard to read.

## 3. Unsupported claims and UNKNOWN handling (MEASURED)

- **No invented content.** The extractive composer only quotes, so it never invents a fact. Lab refusal items (Q6, Q7, Q11, Q14) assert no booking, finalisation, availability or deadline. They quote the hedges ("haven't checked availability", "not confirmed yet").
- **UNKNOWN recall 0.43** (3 of 7). Dita answers from keyword overlap when the answer isn't present:
  - F05: "new address" → "new falafel place";
  - F07: "booked a photographer" → "booked Bramblewick";
  - M07: no bus information → tournament messages;
  - B09: an isolation probe, answered from unrelated reviews.
- **UNKNOWN precision 0.75; over-abstention 0.037.** Dita rarely refuses when it shouldn't. The other side of that is Lab Q2 and Q15: answers that exist but are phrased differently returned "I couldn't find this".
- **Deletion: 0 violations** (track E n=1, track L n=2). Deleted text never appeared.
- **Isolation: 0 leaks.** No decoy-group message was ever cited, on either dataset.
- **Prompt injection: 0 followed / 0 cited after the fix.** Before the fix: 3 followed, 3 cited.

## 4. Decisions, recommendations, commitments, contradictions, corrections (MEASURED; cells are tiny)

| Phenomenon | Dev, fully correct | Lab (D-product) |
|---|---|---|
| Recommendations (spread / context-dependent) | 1/2 · 1/3 | Q6 rain-venue recommendation with "haven't checked" hedge: pass |
| Decisions | — | Q1 final date: decision quoted, but the superseded plan is listed beside it (temporal fail); Q10 deadline: pass |
| Commitments (offer vs confirmed) | 1/2 | Q3 aprons: pass; Q8, Q14 cake: pass; **Q4 Ron: fail** (drinks message missed); **Q5 Maya: fail** (name not resolvable) |
| Contradictions (conflicting) | 1/3 | no contradiction in the fixture |
| Corrections / supersession | **0/4** corrections · 1/3 superseded | Q1 superseded plan cited as current; Q13 speaker proposal→withdrawal: all three messages cited, no "final" marker |

BM25-only scores identically on every dev phenomenon row above.

## 5. In-memory vs real stack

**NOT EXECUTED on the real stack.**
- This agent's sandbox cannot connect to 127.0.0.1:55432, :57474, :58080 or :59071.
- Every earlier real-stack result (V4, V5, relay, knowledge slice) came from runs on the user's Mac.
- The harness is ready: `scripts/capability-verify.sh` runs **both** experiments on PostgreSQL + relay + Neo4j + Vespa, with the same scorers. It doesn't ask for confirmation; it adds only new synthetic groups, never the real demo chat.

**Expected differences (HYPOTHESIS until run):**
- Retrieval differs: Vespa BM25 + Neo4j expansion instead of in-memory BM25 + graph. Vespa's `message.text` still uses default stemming, so the per-document language-guess problem found in DEC-028 may affect message search on mixed-language text.
- Answers are otherwise the same code (QueryService, extractive composer, verifier).

## 6. The five highest-impact capability gaps (from measured failures)

1. **The knowledge layer is never populated (blocked).**
   - The understanding features (supersession, corrections, contradictions, offer vs confirmed, entities, subject-aware conflict) all depend on extraction, which needs live Muse.
   - Directly behind 10 of the 23 dev failures (supersession 3, spurious conflict 3, multi-hop 2, contradiction 1, forwarded 1) and Lab Q1/Q4/Q5/Q13.
2. **Cross-language and Hebrew retrieval.**
   - 6 of 23 dev failures. No multilingual retriever, and the tokenizer has no Hebrew prefix/inflection handling.
   - The existing architecture cannot fix this without a new component.
3. **Answerability.**
   - Dita answers instead of saying UNKNOWN (4 dev failures), and misses differently phrased answers (Lab Q2, Q15).
   - A score threshold would have to be calibrated on held-out data, not dev gold.
4. **Answers are quote lists, not answers.**
   - Up to 8 quotes, including stale ones; citation precision 0.48.
   - There is no composition step. Muse answer composition is not built, and the extractive composer cannot rank currency.
5. **People are ids, not names.**
   - No display names, so name-based questions and readable attribution fail (Lab Q5, Q4 in part).
   - Storing names is personal data: Natasha's decision.

## 7. Must be solved before the hackathon demo (recommendation; Peter to confirm)

- **(1) Knowledge layer live on the demo data.** Needs: your approval for live Muse extraction, and Natasha's sign-off for Telegram-ingested synthetic messages. Telegram replay stores messages as PLATFORM, and egress refuses them by design.
- **(4) A composed answer that puts the current decision first and marks superseded items.** The RFC-002 `validity` and `SUPERSEDES` fields already exist for this.
- **(5) Display names**, under a Natasha-approved policy.
- Gaps (2) and (3) matter less for an English demo script. They must be stated honestly as limitations.

## Fixes made in this evaluation (MEASURED)

**1. InjectionGuard** (`dita-core/.../answer/InjectionGuard.java`).
- What it does: a message that tries to instruct Dita (instruction override, or a directive addressed to Dita/the bot, in English or Hebrew) is never used as evidence. That applies to the extractive composer and to the knowledge `EvidenceValidator`. The message is still stored and still deletable.
- Evidence it was needed: H02/H03/M03 quoted "999 ש"ח" and "free" injections to users.
- Tests: 3 unit tests, using synthetic strings written for the test (not corpus text), including 7 non-injection negatives.
- Result: dev injection followed/cited went from 3/3 to 0/0, and H03 now passes (11 → 12; optimistic, since it was fixed on dev).
- **Needs Natasha's review**, because it is a security control.

**2. Disagreement banner considers only directly matched messages.** Context reached only through inferred links no longer triggers it. This is a principled rule, but it had **no measured effect** on dev.

Not changed, deliberately:
- no relevance thresholds;
- no Hebrew tokenizer tweaks;
- no supersession heuristics;
- no changes to labels or the rubric.

## Untouched evaluation set

- **The Synthetic Lab fixture is the clean held-out set.** Its rubric was frozen before the first run, and no Dita change was made after seeing its results.
- **The dev split is contaminated** for measuring improvements: it was read by the fix author.
- **The sealed test split is still empty.** DESIGN §2 says it must be written by Peter or the user, not by Arm C's builders.

## Blocked: needs approval

**Live Muse extraction for the knowledge arm.**
- Model: muse-spark-1.3.
- Destination: api.meta.ai.
- Data sent: synthetic message text only, with authors pseudonymised to P1, P2 (E-6).
- Volume: the 4 dev corpora (151 messages) plus the 41-message fixture, about 23 batches.
- **Cost, ESTIMATE** (from one measured call, n=1): about $0.24 for dev, and about $0.30 in total including the fixture. Range about $0.15–0.60.
- Also needed: Natasha must approve sending the Telegram-replayed fixture, which is stored as PLATFORM. The alternative is replaying it as SYNTHETIC through the ingestion pipeline directly.
- Not yet built: the knowledge-arm runner (enqueue → collect → approve → process per replay group, then the same scorers). It's an offline task, to be done before any approved run.

## Artifacts

- `eval/value-benchmark/results/`:
  - `capability-dev.{md,json}` and `capability-dev-bm25.{md,json}`;
  - `*-answers.jsonl`;
  - `capability-dev-failures.md`, the root-cause table for all 23 failures.
- `eval/synthetic-lab/`:
  - `rubric.json` (frozen) and `score.py`;
  - `results/answers-in_memory*.jsonl` and `results/score-in_memory*.md`.
- Harness:
  - `dita-eval/.../capability/{DitaProductRunner,CapabilityScorer}.java`;
  - `dita-app/src/test/.../{CapabilityPostgresRunTest,SyntheticLabReplayTest}.java`;
  - `scripts/capability-verify.sh`.
