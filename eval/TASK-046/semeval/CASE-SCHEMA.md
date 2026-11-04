# TASK-046 semantic evaluation: case schema and annotation guideline (v1, 2026-11-01)

Everything is synthetic English: invented people and events, no real data. Authors write cases; annotators label them without
seeing the author's labels.

## Files
- **Dev** (builders may read): `eval/TASK-046/semeval/dev/cases-NN.json`. Each file is a JSON array of cases.
- **Sealed** (builders must never open; created by Peter only after operator approves the allocation): `eval/TASK-046/semeval/sealed/`.
- **Labels:**
  - The author's intended labels sit inside each case under `authorGold`.
  - Annotators write separate files, `labels-<annotator>-NN.json`, mapping case id → `gold` (same structure as `authorGold`).

## Case
```json
{
  "id": "DEV-001",
  "template": "T-value-change-time",
  "class": "replaces | restatement | addition | conditional | scoped | different-property | different-subject | status-genuine",
  "mechanism": "value-change | option-decision | offer-confirmation | reversal | withdrawal | null",
  "lure": "negation | condition | question | trimmed-quote | reported-speech | decision-prefix-on-option | null",
  "names": {"u-ana": "Ana", "u-ben": "Ben"},
  "messages": [
    {"id": "m1", "author": "u-ana", "minute": 0, "text": "The picnic is on Friday.", "replyTo": null},
    {"id": "m2", "author": "u-ana", "minute": 5, "text": "Update: the picnic moved to Saturday.", "replyTo": null}
  ],
  "items": [
    {"mid": "m1", "status": "ATTRIBUTED_STATEMENT", "certainty": "ASSERTED", "subjectId": "picnic", "subject": "The picnic",
     "predicate": "states", "quote": "The picnic is on Friday.", "qualifiers": [], "attributedTo": null},
    {"mid": "m2", "status": "ATTRIBUTED_STATEMENT", "certainty": "ASSERTED", "subjectId": "picnic", "subject": "the picnic",
     "predicate": "states", "quote": "the picnic moved to Saturday.", "qualifiers": [], "attributedTo": null}
  ],
  "question": "When is the picnic?",
  "authorGold": {
    "pairs": [{"later": "m2", "earlier": "m1", "sameProperty": "yes", "replaces": "yes", "evidence": "moved to Saturday"}],
    "status": {"m1": "ATTRIBUTED_STATEMENT", "m2": "ATTRIBUTED_STATEMENT"},
    "current": ["m2"], "notCurrent": ["m1"],
    "answer": {"mustState": ["Saturday"], "mustNotStateAsCurrent": ["Friday"], "mustNotLabel": []}
  }
}
```

## Field rules
- **messages:** 2–4 per case. `minute` increases. `replyTo` is a message id in the same case, or null.
- **items:** what an extraction model would PROPOSE for these messages (the fixture for arms A1–A5).
  - `quote` is verbatim text from the item's message.
  - `subject` occurs verbatim in that message or in its reply parent.
  - `subjectId` is shared by claims about the same thing.
  - `status` may be stronger than the truth, deliberately, in a lure case: this is how a model over-labels.
  - `predicate`: states | recommends | advises_against | offers | commits | decides | asks.
  - `qualifiers`: `{"kind": "CONDITION|TIME|POPULATION|CONTEXT", "mid": ..., "quote": verbatim}`.
- **Candidate pairs:** every pair of items with the same speaker and the same subjectId, later in time. The target pair must be among them. Every candidate pair gets a label in `authorGold.pairs`.
- **question:** one natural question whose correct answer depends on the target pair.

## Annotation guideline: the labels
- **sameProperty** (yes / no / uncertain): do the two claims give a value for the same attribute of the same thing?
  - Same: the picnic's day, the dinner's time, who brings the chairs.
  - Different: the picnic's budget vs the picnic's start time; who brings chairs vs where the picnic is.
- **replaces** (yes / no / uncertain): after the later message, is the earlier claim no longer what the speaker holds as current?
  - **yes:** a corrected or changed value; an option settled by a decision; an offer confirmed or withdrawn; a reversed recommendation.
  - **no:**
    - a restatement of the same value;
    - an addition of a compatible extra value;
    - a conditional or contingency plan alongside the main plan;
    - a different scope (for kids vs for adults);
    - a different property;
    - a different subject that only shares an id.
  - **uncertain:** a careful reader could not tell from these messages alone.
- **evidence:** the shortest verbatim span of the LATER message that shows the replacement (or, for "no", what shows it is not one).
- **status** (per item, the true speech act): DECISION | CONFIRMED_COMMITMENT | OFFER | ATTRIBUTED_STATEMENT | REPORTED_CLAIM | QUESTION.
  - The status is based on the speaker's FULL message, not on the trimmed quote.
  - "Decision: Friday?" is a question.
  - "We haven't decided" is not a decision.
  - "If X, I'll bring chairs" is at most an offer.
  - "Dana said she'll bring chairs" is a reported claim.
- **current / notCurrent:** after the whole case, which items still state what the group should treat as current.
- **answer:** for the question:
  - `mustState`: values a correct answer contains;
  - `mustNotStateAsCurrent`: stale or unsupported values that must not be presented as current;
  - `mustNotLabel`: rendered act verbs that would be unsupported ("decided on", "committed to", "offered").

## Independence
- Annotators must not open `authorGold` or another annotator's file.
- Any disagreement goes to adjudication, and operator audits a stratified sample.
- Gold is hashed and frozen before any arm runs.
