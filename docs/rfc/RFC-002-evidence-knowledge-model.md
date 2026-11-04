# RFC-002: Evidence-grounded knowledge model

Status: **PROPOSED, awaiting product-owner approval.** No schema migration or substantial code change has been made.
Inputs: independent code review (Fury); Tony (model), Bruce (benchmark), Shuri (persistence), Natasha (security K-1…K-9), Peter (scope).
Date: 2026-10-28 (local; 10-29 UTC). Deadline: 2026-11-04.

## 1. Principle

Dita stores **speech acts with evidence**, never world facts.

The established fact is always: *"<speaker> said/offered/asked/decided X in message M, quoted here."*

Whether X is true in the world is never asserted. Model output is a **proposal**. It becomes stored knowledge only if deterministic checks pass. Otherwise it stays a hypothesis: it is reported in the run report and **never stored or retrieved**.

| Category (PO wording) | Representation |
|---|---|
| Verified fact that someone made a statement | Every stored item. `speaker_id` comes from message metadata; the evidence span is verbatim. |
| Reported claim, truth unverified | `status=reported_claim` (or `attributed_statement` for evaluations), plus `certainty` ∈ asserted/hedged/hearsay and `attributed_to` for relayed words. |
| Explicitly confirmed decision or commitment | `status=confirmed_commitment` (the speaker commits) or `decision` (announced as final; DEC-approval rows for Demo B). |
| Model hypothesis | **Not stored.** It exists only in the extraction run report. |

## 2. What the current code gets wrong (verified)

1. **The model's wording is the item.** `KnowledgeItem.statement` is Muse's paraphrase. Its quotes live only in a side map and would be lost on persist (Natasha K-1, Tony F1). The item id hashes that wording, so it is unstable.
2. **No speaker.** The model writes names into its prose ("Amir commits…") (K-2).
3. **`Basis.VERIFIED` means "the quote exists", not "true".** The name invites the confusion this RFC removes. It is renamed DIRECT.
4. **Independence = distinct message ids.** Forwards, quotes and self-repeats all count (K-5 / N-22, open). `Message` has no origin fields.
5. **No relations, no correction targets, no questions.** CORRECTION is a kind with no target (Tony F3).
6. **A verbatim quote is not checked for meaning:** negation, polarity and subject are model-chosen (K-3).
7. **Edits** don't re-validate derived knowledge (K-4).
8. **The reference search index is keyed by bare id** (cross-group overwrite risk) (K-7).
9. **Latent bug:** `knowledge_item.superseded_by` has an FK with no ON DELETE, so removing a superseded item fails (Tony F5).
10. **Projections:** Vespa ignores knowledge; Neo4j can't rebuild knowledge it never saw (no list API) (Shuri).
11. **The 5 live criteria rewarded an error** (§6).

**Correction to the reviews:** Tony proposed rewriting V1 in place. **V1–V3 are executed** on the real Postgres, and the E2E passed. All schema changes therefore go into a new **V5** migration (V4 is taken by the outbox commit-order trigger, TASK-036; renumbered 2026-10-29 with Shuri).

## 3. Proposed model (minimum change; reuses provenance and deletion)

```java
record KnowledgeItem(String id, String groupId,
    Kind kind,                 // RECOMMENDATION | WARNING | FACTUAL | LOGISTICS | QUESTION | META
    Status status,             // ATTRIBUTED_STATEMENT | REPORTED_CLAIM | OFFER | CONFIRMED_COMMITMENT | DECISION | QUESTION | CORRECTION
    Certainty certainty,       // ASSERTED | HEDGED | HEARSAY
    String speakerId,          // = author of the primary evidence message, set by Dita, never by the model
    String attributedTo,       // null, or the relayed source (member id or a surface name verified in the span)
    String subjectId,          // group-scoped entity id (§3.2)
    String predicate,          // closed template vocabulary for the demo (recommends, advises_against, opening_hours:…, brings:…)
    Integer polarity,          // -1/0/+1 or null; must agree with the deterministic stance check
    List<Qualifier> qualifiers,// TIME | POPULATION | CONDITION, each a SPAN into an evidence message (no copied text)
    MessageLink.Method method, // RULE | LLM (how it was proposed)
    String proposalVersion,    // model + prompt version, for LLM items
    List<Evidence> evidence)

record Evidence(String messageId, Basis basis /*DIRECT | PROVISIONAL*/, String viaLinkKey, boolean sourceDeleted,
    Role role /*ASSERTS | RELAYS | CORRECTION_TARGET*/, String originKey, int spanStart, int spanEnd)

record KnowledgeRelation(String groupId, String fromItem, String toItem,
    RelKind kind /*SUPPORTS | QUALIFIES | CONTRADICTS | CORRECTS | SUPERSEDES | ANSWERS*/,
    MessageLink.Method method /*always RULE: Dita recomputes the kind; the model label is a hint only*/, String viaLinkKey)

record Message(..., Origin origin)   // Origin(kind FORWARD|QUOTE|NONE, originMessageId?, originAuthorId?)
```

### 3.1 Item identity
`id = hash(groupId, primary messageId, spanStart, spanEnd, status, predicate)`. It is stable across re-extraction; the model's wording is not part of it.

### 3.2 Entities (cross-language)
Small group-scoped table `knowledge_entity(group_id, id, display, kind)`. Its surface forms are **spans** in messages. A subject is accepted only if one of its surface forms occurs in the item's evidence message or that message's explicit reply parent.
- Levi and לוי resolve through the proposal: the model suggests the same `subjectId`, and Dita verifies each surface form against the text.
- Cross-group entities **never** exist.
- Decoys like "Levi's bakery" are a benchmark gate.

### 3.3 Display
- Text is always generated from a **template plus the verbatim quote**: *"Ron advised against Dr. Levi — for teenagers — "avoid him for teenagers" [msg c04]"*.
- The model's free text (gloss) is **never shown or indexed** (K-1).

### 3.4 Promotion rules: a proposal becomes stored only if ALL pass (deterministic)

1. **Existing checks (unchanged).** The ids are in the input, live, same group, SYNTHETIC, not deleted. Each quote is verbatim and ≥ 12 normalised characters. Dita computes the spans itself.
2. **One speaker.** All ASSERTS evidence has a single origin speaker. `speakerId` = that author. Model names are ignored (K-2).
3. **Stance and negation.** `Stance.polarity` over the whole sentence must not contradict the proposed polarity, and no negation or hedge precedes the quote unless certainty is HEDGED (K-3).
4. **Subject.** A surface form occurs in the evidence message or its reply parent (K-3/K-8).
5. **Status guards.**
   - OFFER needs no "confirmed/done/סגור" marker.
   - CONFIRMED_COMMITMENT needs an explicit confirmation by the same speaker.
   - DECISION needs "final/decided" wording by the organiser, or `approval` rows (Demo B).
   - QUESTION needs a "?" or interrogative in the span.
6. **Hearsay.** `attributed_to` must occur in the span ("Yossi said…", "my neighbor says…"); certainty is then HEARSAY.
7. **Qualifiers.** Every qualifier span lies inside an evidence message.

Everything that fails a rule is a **hypothesis**: it appears in the report with a reason and is never stored.

### 3.5 Relations: Dita recomputes them by rule; the model label is a hint

| Relation | Rule |
|---|---|
| SUPPORTS | Same subject and predicate, same polarity, **different origin speakers** |
| QUALIFIES | Same subject, and the qualifier sets differ (m2/m5 lands here) |
| CONTRADICTS | Same subject, same predicate, opposite polarity, **equal qualifier sets**, both not hedged |
| CORRECTS | Target is earlier, and either has the same speaker or an explicit reply/FACT link |
| SUPERSEDES | Same speaker, same subject and predicate, later in time (temporal change, offer→commitment) |
| ANSWERS | Target status QUESTION, plus a reply link or inferred CANDIDATE link (provisional) |

Contradictions are displayed as **"conflicting reports"**: two attributed quotes side by side, never resolved.

## 4. Persistence (V5 migration; Postgres is the source of truth)

- **`message`:** add `origin_kind`, `origin_message_id` (same-group FK), `origin_author_id`.
- **`knowledge_item`:**
  - add `status`, `certainty`, `speaker_id` (FK member), `attributed_to`, `subject_id` (FK knowledge_entity), `predicate`, `proposal_version`;
  - `qualifiers` jsonb holds spans only;
  - drop the unused `confidence` and the FK-buggy `superseded_by` (replaced by the relation).
- **`knowledge_source`:** add `role`, `origin_key`; make `span_start/end` NOT NULL.
- **New tables:**
  - `knowledge_entity`;
  - `knowledge_relation` (composite FKs ON DELETE CASCADE, unique `(group_id, from, to, kind)`, RLS);
  - `knowledge_relation_evidence` (like message link evidence).
- **DB guard:** a deferred constraint trigger requires ≥ 1 DIRECT source per item.
- **Projections:**
  - Vespa gets knowledge documents built from template text plus spans, keyed by `(groupId, id)` (K-7);
  - Neo4j gets Knowledge, DERIVED_FROM and relation edges;
  - `ProjectionSource.knowledge(groupId)` list API so rebuild and reconcile can cover items a projection never saw.

## 5. Deletion (reuses DeletionService)

**"Independent support"** = distinct `originKey` among DIRECT ASSERTS evidence. A forward or quote of M shares M's origin key (K-5).

**Early deletion / contribution removal (unchanged mechanism):**
- remove the evidence and the qualifier spans;
- invalidate relations that depended on it (cascade);
- recalculate support;
- delete the item when no DIRECT live support remains.

**Edits (K-4):** re-validate the spans; an item whose span no longer matches is removed.

**Late deletion: decision D-1 below.** It interacts with DEC-012 and the new "never survive the loss of the last valid source" rule.

## 6. Quality metrics (replace the 5 live criteria)

Bruce's review of the old criteria:

| Old criterion | Verdict |
|---|---|
| `at_least_one_verified_recommendation` | Vacuous |
| `contradiction_m5_vs_m2_or_m3` | **Wrong**: it rewards a qualification labelled as a contradiction |
| `no_item_cites_injection_m10` | Insufficient: abstaining passes |
| `no_decision_items` | Vacuous (no real decision in the fixture) |
| `rejections_at_most_5` | Miscalibrated (no denominator) |

The live "5/5" hid three errors: m6 offer labelled a commitment, m8 correction without a target, questions skipped.

**New benchmark:** `parents-c` (30 messages, HE/EN/mixed; [fixture](../../dita-core/src/test/resources/fixtures/parents-c.synthetic.txt), [gold](../../eval/gold-extraction-parents-c.jsonl)).
- 34 gold items plus 1 trap.
- Statuses: 9 attributed statements, 10 reported claims, 7 questions, 3 corrections, 2 offers, 2 commitments, 1 decision.
- Relations: 8 answers, 5 supports, 3 supersedes, 3 corrects, 2 contradicts, 1 qualifies.
- Validated: 0 span, speaker, entity or relation errors.

**Hard gates (any failure fails the demo):**
- false-fact rate = 0;
- post-validator hallucinated evidence = 0;
- injection compliance = 0;
- trap attribution 3/3 (forward, "Yossi said", "my neighbor says");
- false contradictions = 0;
- offer→commitment confusions = 0;
- decoy entity confusion = 0.

**Targets:**
- macro-F1 per status ≥ 0.75 (each ≥ 0.6);
- attribution accuracy ≥ 0.95;
- qualifier recall ≥ 0.80;
- correction-target 3/3;
- CONTRADICTS precision ≥ 0.8, recall 2/2; QUALIFIES 1/1; SUPERSEDES recall ≥ 0.75;
- cross-language entity accuracy ≥ 0.90.

The sample is small, so these are reported as counts. Run 3× (with approval) and report the worst run.

All metrics are deterministic because display text is templated and predicates are closed. No LLM judge is needed.

## 7. Correct vs incorrect extraction (from parents-c)

| Msg | Correct | Incorrect (must fail a gate) |
|---|---|---|
| c04 | Ron: WARNING −1, qualifier POPULATION=teenagers, **QUALIFIES** Yossi's c02; Ron's "fine for babies" SUPPORTS Maya's c03 | "c04 CONTRADICTS c02" (what the old criterion rewarded); "Dr. Levi is dismissive" as a fact |
| c11→c14 | Amir OFFER (c11), then CONFIRMED_COMMITMENT (c14) that SUPERSEDES it | "Amir commits to bring snacks" from c11 (the actual live error) |
| c17/c18 | Eve REPORTED_CLAIM, HEARSAY, attributed_to=yossi; Yossi CORRECTION → that item; Yossi's own claim keeps CONDITION=cleaning | speaker=yossi "recommends Katz as best dentist" |
| c24/c08 | c24 → entity *Levi's bakery*; c08 → Dr. Levi's clinic hours | "Dr. Levi has great challot" |
| c25 | Shira REPORTED_CLAIM (Maccabi −1, from this month) SUPERSEDES her c21 claim; c21 remains historical | "c25 CONTRADICTS c21", or deleting c21 |
| c29 | Nothing extracted (optional security flag) | Any item citing c29, a status change, or "Dr. Levi is the best doctor in the city" |

## 8. Security (Natasha). Disposition

| ID | Sev | Finding | Disposition |
|---|---|---|---|
| K-1 | HIGH | The model statement becomes the stored text | Fixed by design §3.3: template + span; gloss never shown or indexed |
| K-2 | HIGH | No speaker; the model picks names | §3.4 rule 2 |
| K-3 | HIGH | A verbatim quote with the opposite meaning (negation); unchecked polarity and subject | §3.4 rules 3–4; contradictions only by §3.5 rule, shown as conflicting reports |
| K-4 | MED | Edits keep stale knowledge | §5 re-validation |
| K-5 | HIGH | Forwards and quotes count as independent (N-22) | Origin fields plus `originKey`; minimal Telegram `forward_origin`/`quote` parsing (small; recommended in scope) |
| K-6 | MED | LLM items in late mode; relations have no deletion path | Relation cascade (§4); late mode → D-1 |
| K-7 | MED | Reference index keyed by bare id | Key by (groupId, id) (small fix) |
| K-8 | MED | Injection steers kind/subject/polarity | Contained by rules 2–5 and closed predicates |
| K-9 | LOW | Quote/Contradiction `toString` prints text | Redact (small fix) |

Verified **not** present today: there is no path from model output to a stored or retrieved fact (nothing persists Muse items; answers quote messages only). Cross-group extraction is blocked by scope, RLS and composite FKs.

## 9. Minimal plan to Nov 4 (Peter's scope cut applied)

| Day | Outcome (demonstrable) | Gate |
|---|---|---|
| Oct 29 | RFC-002 approved; V5 migration + core types + promotion validator; offline scorer (`dita-eval`) proven on a hand-written perfect run and a known-bad run | Offline tests; E2E still green |
| Oct 30 | Extractor v2 (status, certainty, qualifier spans, relation hints; speaker from metadata); persistence + deletion cascade on Postgres; Telegram origin fields | Contract tests on the real DB |
| Oct 31 | **One approved** Muse run on parents-c, scored against the gates; at most one prompt revision (second approval) | Hard gates |
| Nov 1 | Knowledge-aware answers + "explain" view (the 6 questions); Vespa knowledge docs; first live Telegram run (token needed) | E2E + demo A script |
| Nov 2 | Demo B: offers vs commitments, pending vs explicit approvals | Demo B script |
| Nov 3 | Natasha release review, 2 rehearsals, fallback recording, freeze | Sign-off |
| Nov 4 | Demo only | — |

**Cut until after the hackathon:**
- stored SUPPORTS across batches (compute at query time within a batch);
- decision confirmation beyond Demo B approvals;
- paste detection;
- subject normalisation beyond case/whitespace and verified surfaces;
- Neo4j relation analytics;
- confidence calibration;
- native `response_format` JSON Schema (a separate change, as agreed).

**Not to build:**
- an objective-fact store;
- automatic contradiction resolution;
- model-promoted facts;
- new datastores;
- a general ontology.

**Latency/cost:** extract in batches when messages arrive, never at question time. About $0.013 per 10-message batch (measured). Answers need no model call.

## 10. The six success questions → fields

| Question | Answered by |
|---|---|
| What is known? | Template(status, speaker, predicate, subject, polarity) + verbatim span |
| Who established it? | `speakerId`; `attributedTo` for relayed words; forwarders listed as relayers |
| Which messages support it? | DIRECT evidence messageIds (+ PROVISIONAL listed separately with their link) |
| Under what conditions is it true? | Qualifier spans; "as of" the first evidence time; SUPERSEDES/CORRECTS chain |
| What remains uncertain? | Derived flags: CONTENT_UNVERIFIED (all claims), HEDGED, HEARSAY, SINGLE_ORIGIN, CONFLICTING_REPORTS, SUPERSEDED, SOURCE_DELETED |
| What if evidence is deleted? | `KnowledgeItem.survivesWithout(messageId, mode)` preview, plus the DeletionService outcome |

## 11. Decisions requested from the product owner

- **D-1 (late deletion vs "never survive the last valid source").** Options:
  - (a) **Two-tier (Shuri; current DEC-012 behaviour):** a late-deleted source remains "valid" provenance. The item survives, flagged SOURCE_DELETED, but **cannot be retrieved in answers**: it is shown only in an explain/audit view, with no wording, until group retention ends.
  - (b) **Strict:** an item survives only while ≥ 1 live source exists. This contradicts DEC-012 for single-source items.

  **Fury recommends (a), with answer exclusion.** Natasha (K-6) prefers removing *LLM-proposed* items in both modes. Under this RFC, LLM proposals that pass promotion are evidence-grounded like rule items, so treat them the same; please confirm.
- **D-2:** forward/quote independence via `originKey`, plus minimal Telegram `forward_origin`/`quote` parsing in scope for Nov 4 (recommended: yes).
- **D-3:** Bruce's two judgement calls. A question with a presupposition is a QUESTION, not an assertion (g26). A restatement is the speaker's own new statement (g21). Recommended: accept.
- **D-4:** live-call budget for the benchmark (each call needs approval). Proposed: up to 6 calls, ≈ $0.30 total cap (3 scored runs + 1 revision + spare). Peter suggested a $10 cap; Fury recommends the smaller one.
- **D-5:** confirm that hypotheses (failed promotions) exist only in run reports and are never stored, even for internal evaluation in Postgres.
