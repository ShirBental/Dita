# Semantic relation evaluation: design (TASK-046 follow-up)

**Status: DESIGN ONLY.**
- Nothing here has been built or run, and no model has been called or downloaded.
- The same-frame rule is parked as a baseline in [baselines/PropertyFrame.java.txt](baselines/PropertyFrame.java.txt). It is not compiled or wired in.
- Semantic-rule implementation is paused by the user's decision of 2026-10-31.
- Authors: Bruce Banner (evaluation design, failure cases), Tony Stark (architecture, cost). Combined by Nick Fury. Every arm prediction below is from code reading; none was measured.

## The question
Given two claims by group members, both labelled with evidence and allowing UNCERTAIN:
- **SAME_PROPERTY:** do they refer to the same property?
- **REPLACES:** does the later one actually replace the earlier one?

No arm may change truth state. Arms only *propose*; a deterministic Dita gate decides, and `ValidityReconciler` stays the only validity writer.

## Arms (English only)
1. **Current rules:** `RelationRules.supersedes()` in the current tree, frozen by sha256.
2. **Same-frame rule** (Fury's draft, the baseline). A claim minus its subject, typed values (calendar / money / quantity / place slot) and scaffolding words. Same property means equal frames. A replacement also needs a changed value of the same type, offer→confirmation, or option→decision. It needs an explicit stance-reversal path (case C8) and fixes so it compiles (Tony); this is eval-only code.
3. **Muse structured relations:** muse-spark-1.3 proposes `{same_property, replaces, evidence spans, confidence | UNCERTAIN}` per candidate pair. Recorded once (with approval), then replayed.
4. **Local classifier / NLI:** an off-the-shelf MNLI or cross-encoder run locally on CPU with pinned weights. Contradiction ≥ τ_hi → replaces, entailment/neutral ≥ τ_lo → no, anything else UNCERTAIN; thresholds set on dev only. Tony's caveat: "same property" is not an entailment task, so this arm will probably be weak on SAME_PROPERTY.
5. **Pre-registered conjunction:** arm 2 AND arm 3 must both say "replaces"; any disagreement is UNCERTAIN. Computed from recorded outputs, with no tuning.

## Dataset (Bruce)
- **Unit:** a pair of extracted-assertion fixtures. Each side has speaker, subject id and text, status, certainty, polarity, qualifier spans, quote, full message, reply parent and time. This tests the relation layer, not extraction.
- **Candidate pairs are chosen by Dita, never by an arm**, using the production pre-filter: same speaker, same subject id, later in time, no QUESTION items, no deleted or redacted sources. Peter writes different-subject pairs on purpose as subject-id collisions.
- **Authoring:** Peter writes about 40 short English conversations and seals the held-out split with a sha256 manifest. Builders (Fury, Tony, Shuri, Bruce, and whoever writes the Muse prompt or the NLI thresholds) never see it.

| Class | Sealed | Dev |
|---|---|---|
| Same property, replaces | 160 | 80 |
| Same property, not replaces: restatement / addition / conditional / scoped | 4 × 60 = 240 | 4 × 30 = 120 |
| Different property, same subject | 160 | 80 |
| Different subject (id collision) | 80 | 40 |

- **Existing Codex and Bruce counterexamples:**
  - They go into dev only, as a tagged slice capped at 10% of dev.
  - Sealed gets fresh variants of each *mechanism*: at most 3 per mechanism, at most 10% of sealed.
  - Every metric is reported with and without that slice.

## Gold labels
- **Per pair:** SAME_PROPERTY yes/no/uncertain; REPLACES yes/no/uncertain; a required evidence span in the later message (the change cue or new value); for SAME_PROPERTY=yes, also a span in the earlier message; a one-line rationale.
- **Annotation:** two independent non-builder annotators per pair, with a third adjudicating. operator audits 10%; that audit is the check against agent annotators making errors that correlate with the LLM arm.
- **Agreement target:** Cohen's κ ≥ 0.75 on REPLACES and ≥ 0.70 on SAME_PROPERTY. Below target, the guidelines are revised and relabelled before any arm runs.

## Metrics and pre-registered thresholds
- **Primary: false supersession rate (FSR),** meaning REPLACES=yes where gold is no, over the 480 gold-no pairs. A "yes" on a gold-uncertain pair is reported separately as unsafe supersession.
  - Pass requires a one-sided 95% Clopper–Pearson upper bound ≤ 3%, which allows at most 7 errors in 480. If the true rate is 1%, the chance of passing is about 0.89.
  - No subtype may have a point FSR above 5%.
- **Missed supersession** (stale data shown as current): recall on replaces-yes ≥ 0.75, with a Wilson lower bound ≥ 0.65 (n = 160).
- **UNCERTAIN rate** on decidable pairs ≤ 20%.
- **Evidence spans:** 100% must be exact substrings of the stated message (automated). At least 90% must support the label, judged on an annotated sample of 100.
- **Determinism:**
  - Arms 1, 2 and 4 must be byte-identical across 3 runs.
  - Muse live, 3 repeats on 100 pairs: ≥ 97% REPLACES agreement, and no pair may flip between no and yes.
- **Arm comparison:** paired McNemar tests on the same pairs.
- Each arm is reported both raw and after the gate.

## Protocol
- **Freeze:**
  - Arm 1: sha256 of `RelationRules.java`.
  - Arm 2: an eval-only harness class.
  - Arm 3: model id, prompt, temperature and schema, with at most 3 prompt revisions, on dev only.
  - Arm 4: weight hash and thresholds, set on dev only.
- **One sealed run** by Peter with every arm frozen. Aggregates are published first; the details are then released and the set becomes dev. Any later round needs a new sealed set.
- **UNCERTAIN, or SAME_PROPERTY not yes, means no relation.** Both claims stay ACTIVE.
- **End-to-end check for any winning arm:**
  - Trust suite: 53/53 accepted.
  - Recorded lab replay must not be worse than the current A 5 / B 6 / C 9 of 15 (C precision 0.54).
  - `scripts/lab-recorded-verify.sh` on operator's Mac.

## Representative failure cases (gold labels; predicted failing arms, from code reading)
| # | Earlier → later (same speaker, same subject id) | Gold SP / R | Predicted to fail |
|---|---|---|---|
| C1 | "The picnic budget is $50" → "Update: Decision: the picnic starts at noon" | no / no | none expected (control for the Codex C1 fix); small A3 risk |
| C2 | "I can bring chairs to the picnic" (offer) → "Decision: we're driving to the picnic on Friday" | no / no | A1 (shared "to" slot + offer as option) |
| C3 | "Let's have the picnic at the park" → "Decision: the picnic starts at noon" | no / no | A1 (both have "at") |
| C4 | same → "Decision: the picnic is at noon" | no / no | A1; A2 ("noon" fills the place slot, so frames are equal); A4 risk |
| C5 | same → "Decision: the picnic is at the botanical garden" | yes / yes | A4 risk (proposal vs decision read as neutral) |
| C6 | "I'll bring charcoal to the barbecue" → "Update: the barbecue moved to Sunday" | no / no | A1 |
| C7 | "Decision: the picnic is on Saturday" → "Decision: the picnic is on Sunday" | yes / yes | A1 misses it (DECISION→DECISION excluded) |
| C8 | "I recommend the ferry, it's quickest" → "Actually, avoid the ferry" | yes / yes | A2 misses it (no reversal path). Variant "→ avoid the ferry café" (no/no): A1 and A4 fail |
| C9 | "I can bring chairs" → "Never mind the chairs, Dana has plenty" | yes / yes | A1 and A2 miss it; A4 uncertain |
| C10 | "The picnic carpool leaves at 1pm" → "Update: the picnic is at 3pm" | no / no | A1; A4 risk |
| C11 | "I'll bring burgers to the picnic" → "Update: I'll bring buns for the burgers" | yes / no (addition) | A1; A3/A4 risk |
| C12 | "In the event of rain, the picnic is at the pavilion" → "Update: the picnic is at the gazebo" | no / no (contingency) | A1, A2, A4 |
| C13 | "The kids' games start at 11am" (subject picnic) → "Update: the picnic starts at noon" | no / no | A1 |
| C14 | "The school picnic is on Friday" → "Update: the office picnic is on Saturday" (one id) | no / no (different subject) | A1; A4 likely |
| C15 | "The picnic is at the park" → "Update for latecomers: the picnic is at the park" | yes / no (restatement) | A1 |

## Status labels (a smaller, separate evaluation)
- **Items:** single claims. Sealed 240 (60 decision, 60 commitment, 40 offer, 80 weaker); dev 120. The weaker class should be raised to at least 100 if the bound must be ≤ 3%.
- **Phenomena:**
  - Negation: "We haven't decided on Friday".
  - Conditions: "If Dana comes, I'll bring chairs".
  - Questions: "Decision: Friday?" and "Are we set on Friday?".
  - Trimmed quotes: "I'll bring the chairs" taken from "Not sure I'll bring the chairs".
  - Reported speech: "Dana said she'll bring chairs".
- **Arms:** the current `EvidenceValidator`, Muse, and zero-shot NLI.
- **Primary metric:** false strong-status rate (a weaker claim labelled decision or commitment).
- **Gate (Tony):** the validator stays the only status gate. An arm may confirm or downgrade, never upgrade; UNCERTAIN becomes ATTRIBUTED_STATEMENT.

## Minimum architectural change (Tony)
1. **A `RelationProposer` port in dita-core:**
   - `RelationProposal{fromId, toId, sameProperty: YES|NO|UNCERTAIN, replaces: YES|NO|UNCERTAIN, evidence (message id, quote), proposerId, version, confidence}`.
   - Arms: `RulesRelationProposer` (wraps today's `supersedes()` and stays the default, so the app wiring is unchanged), `FrameRelationProposer`, `MuseRelationProposer` (dita-muse), and `NliRelationProposer` (a new dita-nli module, so dita-core gains no dependencies).
2. **A `RelationGate`, deterministic.** It returns today's `RelationRules.Outcome`, so the extraction read-back checks stay as they are. A SUPERSEDES relation is written only if:
   - both labels are YES;
   - same group, same speaker, same subject id, and the later item is newer;
   - the later item can be shown (`ValidityReconciler.canRetire`);
   - structural vetoes pass: the later item is not HEDGED, CONDITION, REPORTED_CLAIM or redacted;
   - each evidence quote is found by Dita (`Spans.find`) in the later item's own live, never-edited, synthetic message or its live reply parent, and passes `InjectionGuard`;
   - confidence meets a per-proposer threshold. Confidence alone never admits a relation.

   CONTRADICTS stays with the rules.
3. **Model calls run before the store transaction.** Today relations are computed inside `store.atomically` while row locks are held. Proposals are gathered first, and their spans are re-verified inside the transaction against the locked text.
4. **Shadow mode in dita-eval:** replay recorded extractions into a throwaway in-memory store, run every arm on the same pairs through the same gate, and write JSONL per pair. Nothing is written to Postgres or the projections.
5. **Schema:**
   - The evaluation needs no migration.
   - Only to *adopt* a winner: an additive V6 adding `knowledge_relation.proposer` and `proposer_version` (NULL meaning the pre-V6 rules), so a bad proposer's relations can be removed and the reconciler re-run.
   - V6 is owned by Shuri, reviewed by Fury and applied only with operator's approval. V5 is never edited.
6. **Unchanged:** `ValidityReconciler`, `DeletionService`, `EvidenceValidator`, the extraction prompt, the ingest path, the outbox, V1–V5, and the projections.

## Cost (estimates, not measured)
Muse prices: $1.25 per million input tokens and $4.25 per million output tokens, with reasoning billed as output (`scripts/muse-live.sh`). These are consistent with the recorded $0.031719 lab call.

| Arm | Evaluation | Production per group-day |
|---|---|---|
| 1 Rules, 2 Frame | $0, under 1 ms per pair | $0 |
| 3 Muse | About $0.0016 per pair at 400 in / 250 out, up to about $0.005 with heavy reasoning. Dev + sealed + stability + status is about 1,300 pair evaluations: **about $2–$7, ceiling $10**. Batching pairs per extraction batch is cheaper (Tony: $0.15–$0.60 for 200–400 pairs) | About $0.01–$0.12 for 20–60 pairs, on top of extraction (about $0.03–$0.05 per 41-message batch) |
| 4 Local NLI | $0 | $0 |

Local NLI, CPU, unmeasured and uncertain by about 2× either way:

| Model class | Latency per pair | Memory |
|---|---|---|
| MiniLM-class cross-encoder | 3–10 ms | 150–300 MB |
| DeBERTa-v3-base | 20–60 ms | 300–800 MB |
| DeBERTa-v3-large | 80–250 ms | 1–2.5 GB |

- **Integration:** ONNX Runtime Java (MIT) plus DJL tokenizers (Apache-2.0).
- **Download:** weights are fetched once with approval and pinned by sha256; the build runs Maven offline. The download reveals only the model id and IP. No message text leaves the machine.
- **Licence:** check every model card, since some NLI training data (ANLI) is CC-BY-NC.

## Approvals needed before any of this runs
- Peter authoring and sealing the sets, and non-builder annotation (internal; no approval needed).
- **operator:**
  - each live Muse relation run, through a new reviewed `relations` mode in `scripts/muse-live.sh`;
  - the NLI weight and dependency download;
  - V6, only if an arm is adopted;
  - any production model.

## What result would justify which architecture
- **Arm 1:** only if it passes every threshold. Predicted not to.
- **Arm 2:** if it passes FSR, its recall is at least Arm 1's and within 10 points of the best semantic arm, and its failures fit the frame definition rather than needing new exceptions. It is deterministic, offline and free.
- **Arm 3:** only if it passes FSR and beats the best deterministic arm's recall by at least 10 points (McNemar p < 0.05), with valid spans, a stable live run and an acceptable cost. It would ship as a recorded proposer behind the gate.
- **Arm 4:** if it passes FSR offline with recall at least Arm 2's. The dependency needs approval.
- **Arm 5:** if Arm 2 has low FSR and Arm 3 has high recall but neither passes alone.
- **No arm passes:** no automatic supersession. Both claims are shown, newest first, possibly marked "possibly updated". That is a product decision for operator.
