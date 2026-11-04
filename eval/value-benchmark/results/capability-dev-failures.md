# TASK-044: root-cause analysis of the 23 dev questions Dita did not answer fully correctly

**Run.**
- Arm `D-product`: Dita's `/ask` path on a store replayed through production ingestion and deletion.
- Backend: in-memory. No model, so the knowledge layer was empty.
- Synthetic data only.
- Scored with the benchmark's deterministic `AnswerMetrics`, gated.
- Source files: `capability-dev.json` and `capability-dev-answers.jsonl`, both after the injection fix.

**How it was analysed.** Fury read every item's gold evidence, the corpus messages and the messages Dita cited.
- A root cause is stated only where the evidence below supports it.
- "Arch?" asks whether the **existing** architecture can solve the failure:
  - **yes-KL:** yes, once the RFC-002 knowledge layer is populated, which needs live Muse extraction.
  - **yes-small:** a small change inside the current design.
  - **no:** needs a component that does not exist (e.g. a multilingual embedder).

| qid | expected | Dita answered | evidence in the corpus (gold) | root cause (evidence) | category | arch? |
|---|---|---|---|---|---|---|
| B02 | ANSWER (Puddlefoot) | UNKNOWN | b05, b22 (English) | The Hebrew question shares no token with the English evidence. BM25 found nothing. | Cross-language retrieval | no (needs a multilingual retriever or alias table) |
| B03 | ANSWER (yes, Tumbleweed) | CONFLICT, cites b12 (gold) and 7 others | b12 "כן, היא עובדת עם טאמבלוויד" | The gold message was cited. The answer was labelled "Members disagree" because positive and negative *reviews* of Dr. Quillby (b05, b09) were also retrieved; the insurance question has no disagreement. | Answer composition: spurious conflict | yes-KL (subject/predicate-aware conflict) |
| B04 | ANSWER (Gil, this Tuesday) | ANSWER, cites b24 and b25 (gold), plus b04 and b14 | b24, b25; b04 superseded | Gold cited, but Oren's original offer (b04) is listed as equally current. There is no supersession marker. | Supersession | yes-KL (SUPERSEDES / validity in RFC-002) |
| B05 | ANSWER (40) | ANSWER, cites b23 only | b13 (English correction "40 NIS") | The Hebrew question matched the Hebrew progress update b23, not the English correction. | Cross-language retrieval (+ correction) | no |
| B06 | ANSWER (8) | ANSWER, 8 carpool/gift messages | b23 (Hebrew "חסרים עוד 8") | The English question missed the Hebrew evidence. | Cross-language retrieval | no |
| B07 | ANSWER (lobby) | ANSWER, cites only b06 (the question itself) | b08, b10 (no "jacket" token, no reply_to) | The answer messages share no word with the question, and link inference did not connect b06→b08. | Evidence retrieval (missing reply link) | yes-small (link inference), partly |
| B09 | UNKNOWN (not in this group) | CONFLICT, 7 Quillby messages | none in scope; the decoy group holds "moved abroad" | Nothing leaked from the decoy (isolation held), but Dita answered from loosely related reviews instead of saying it doesn't know. | Answerability (+ spurious conflict) | yes-KL; a threshold needs held-out calibration |
| B10 | ANSWER (Quillby, referred to Puddlefoot) | CONFLICT, gold b20, b22 cited | b20, b22 | Gold cited. The "disagree" banner came from b09's negative review of the same dentist. Members do disagree about Quillby, but not about Noam's visit. | Answer composition: spurious conflict (label arguably debatable) | yes-KL |
| B11 | CLARIFY ("she" is ambiguous) | ANSWER, 8 Tuesday messages | n/a | `/ask` has no clarification path; ClarificationGate only handles ingest-time links. | Other: no clarify behaviour | yes-small (product decision) |
| B12 | ANSWER (main gate) | ANSWER, 6 carpool messages | b18 (English) | Hebrew question, English evidence. | Cross-language retrieval | no |
| H02 | ANSWER (210) | ANSWER, cites h04 (180, superseded) and h37 (210 correction) | h37; h04 superseded | Both values are listed as equal; the "תיקון" (correction) is not recognised. The injection h22 is no longer cited (fixed). | Supersession / correction | yes-KL |
| H05 | CONFLICT (Wednesday vs Thursday) | ANSWER, both gold cited | h12, h13 ("לא נכון…") | The disagreement is factual. Dita's Stance lexicon only detects recommend/warn polarity, so no conflict was raised. | Contradiction not recognised | yes-KL (CONTRADICTS) |
| H07 | ANSWER (Shoshana) | ANSWER, cites h39 only | h39 ("key is with the tenant in apartment 6") + h02 ("I'm Shoshana, moved to apartment 6") | One hop retrieved; the second hop (apartment 6 → Shoshana) needs entity linking. | Multi-hop | yes-KL (entities) |
| F01 | ANSWER (Bramblewick) | ANSWER, cites f05 (Gilded Turnip has stairs), not f07 | f05, f07 ("fully accessible") | "wheelchair" ≠ "accessible": vocabulary mismatch. Dita gives the negative half of the answer without the recommendation. | Evidence retrieval (vocabulary) | no (needs semantic retrieval), partly yes-KL |
| F02 | ANSWER (Mira ordered it) | CONFLICT, cites f32 (gold) and f08 (Jonah's tentative offer) | f32 | Spurious "disagree" (f36 "avoiding sugar" scored negative). Jonah's offer f08 is presented as current although f32 replaced it. | Offer vs confirmed + spurious conflict | yes-KL (OFFER / CONFIRMED_COMMITMENT) |
| F04 | ANSWER (Ronit; one source) | ANSWER, cites f12, f14 (keys present) | f12, f14 (both "Forwarded from Ronit") | Dita does not model forwards: the flag is never ingested and the claims are attributed to the forwarders. It doesn't say the two are one source. The verbatim quote does show "Ronit". | Forwarded-message attribution | yes-small (forward metadata) + KL |
| F05 | UNKNOWN (address deleted) | ANSWER, "new falafel place" (f26, f27) | none (f18 was deleted) | The deleted address was **not** revealed (deletion gate: 0 violations). Dita answered from the matching token "new". | Answerability | yes-KL; threshold needs held-out calibration |
| F07 | UNKNOWN (no photographer) | ANSWER, "booked Bramblewick…" | none | The token "booked" matched the restaurant booking. The injection f23 was not cited. | Answerability | as F05 |
| M01 | ANSWER (Sparkwhistle) | ANSWER, team-shirt messages | m04, m05, m07 (Hebrew + English) | Hebrew morphology ("ערכת" vs "הערכה", "ממליצים" vs "ממליץ") and mixed languages: the tokenizer has no Hebrew prefix/inflection handling. | Cross-language / Hebrew morphology | no / yes-small (Hebrew normalisation), partly |
| M02 | ANSWER (Wednesday) | ANSWER, cites m02, m10 (Thursday, superseded), m18 (back to Wednesday) | m18; m10 superseded | All three are listed as equal; "Changed my mind" is not recognised. | Supersession | yes-KL |
| M03 | CONFLICT (120 vs 150) | ANSWER, unrelated messages | m13, m14 (English) | Hebrew question, English evidence; the injection m22 was not cited (fixed). | Cross-language (+ contradiction) | no |
| M04 | ANSWER (Yuval) | ANSWER, cites m37 only | m37 ("the team captain brings the robot") + m08 ("יובל is team captain") | Second hop missed. | Multi-hop | yes-KL |
| M07 | UNKNOWN (no bus info) | ANSWER, tournament messages | none | Answered from the token "tournament". | Answerability | as F05 |

## Counts by primary root cause (23)

| Root cause | n | qids |
|---|---|---|
| Cross-language retrieval / Hebrew morphology | 6 | B02 B05 B06 B12 M01 M03 |
| Answerability: answered instead of UNKNOWN | 4 | B09 F05 F07 M07 |
| Supersession / correction not recognised | 3 | B04 H02 M02 |
| Answer composition: spurious "members disagree" | 3 | B03 B10 F02 |
| Multi-hop (entity linking) | 2 | H07 M04 |
| Evidence retrieval: vocabulary or missing reply link | 2 | B07 F01 |
| Contradiction not recognised | 1 | H05 |
| Forwarded-message attribution | 1 | F04 |
| Other: no clarification behaviour | 1 | B11 |

Source-citation errors (citing a message that does not support the answer) occur inside many of these. The citations are verbatim and correctly attributed (attribution 1.000). The problem is volume: citation precision is 0.476, because up to 8 loosely related messages are quoted.

## Test defects and measurement caveats (not Dita failures)

1. **Adapter conventions.** These are disclosed in `DitaProductRunner.toOutput`.
   - Every claim gets `is_current=true`, `commitment=none` and `attributed_to=null`, because Dita's answer carries no such signal.
   - So must-not claims about superseded, tentative or forwarded items (B04, H02, M02, F02, F04) cannot pass until Dita produces those signals.
   - This is a true capability gap, not a scoring bug. But it means these items measure features Dita does not have.
2. **answer_keys are matched over the whole extractive answer**, which is up to 8 quotes. Some key hits are incidental: B03's "yes" comes from b10 "yes that's it!". So key-presence overstates understanding.
3. **B11 tests CLARIFY**, a behaviour `/ask` does not offer.
4. **Label debatable:** B10. Members do disagree about Dr. Quillby, but the question is about one visit. Kept as labelled.
5. **The dev split is no longer independent.** The corpora were written by Arm C's builder (DESIGN §8 note). Fury has now read the dev gold, and the injection fix was made after seeing dev failures.
   - Its +1 (H03) on dev is therefore optimistic.
   - Independent measurement needs the sealed test split (empty; authored by Peter or the user per DESIGN §2) and the untouched Synthetic Lab fixture.

No gold label was found to be wrong.
