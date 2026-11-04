# Policy: deleting a correction never restores the older claim (operator, 2026-11-01)

**Status:**
- **Adopted policy.** operator decided it on 2026-11-01. It replaces the TASK-046 interim behaviour.
- **Implementation:** DONE offline in TASK-047 (DEC-038, 2026-11-01), with one deliberate change from §1 below: the state is a third validity value, `UNRESOLVED`, instead of a `retired_by_withdrawn` column (see DEC-038 for why). Offline tests pass; PostgreSQL/Neo4j/Vespa live runs not executed (sandbox). V6 (`db/migration/V6__validity_unresolved.sql`) is drafted and has not been applied to an existing database; it still needs operator's approval. The Codex TASK-047 blocker fix conservatively converts pre-V6 SUPERSEDED rows to UNRESOLVED because V5 cannot distinguish relation-only retirement from withdrawal history; it publishes ids-only projection updates in the same migration transaction. No answer wording was added (§3 notice deferred to Natasha's wording review).
- **Production data:** must not be altered. No migration has run, and none may run without approval.

## The policy
When a claim that superseded an earlier claim is withdrawn, the earlier claim does **not** automatically become current again. This applies when the superseding claim is:
- deleted, in either the EARLY purge or the LATE preserve mode;
- edited, so its knowledge is invalidated;
- made unshowable for any other reason (redacted, or only deleted sources remain).

**Rationale:** the speaker had already replaced the earlier value. Their deleting the replacement does not show that the old value is true again. Reviving it would present possibly stale information as current, with no evidence. This was raised as N-110, and Codex called it "questionable".

## What the code does today, and why it conflicts
`ValidityReconciler` (TASK-046) applies the rule "SUPERSEDED if and only if a SUPERSEDES relation from a showable item exists". When the superseding item is removed or becomes unshowable, its relation goes, and the earlier claim becomes ACTIVE again (`Task046RegressionTest.earlyDeletionOfTheSupersedingClaimReactivatesTheEarlierOne`, `lateDeletionInPreserveModeNeverLeavesAnInvisibleSupersession`). Under the new policy that is the wrong outcome.

## The required implementation change
1. **State.**
   - The fact "this claim was retired" must outlive the relation that retired it.
   - Proposed: an additive column, `knowledge.retired_by_withdrawn boolean NOT NULL DEFAULT false`, as part of an additive **V6**.
   - It is set when a superseding item is withdrawn while its target was SUPERSEDED.
   - It carries no reference to the withdrawn item and no wording, so nothing about the deleted message remains.
   - The in-memory store mirrors this as a field on `KnowledgeItem` (or `Assertion`), with read-after-write checks as for every RFC-002 field.
2. **The validity invariant becomes:** SUPERSEDED ⇔ (a SUPERSEDES relation from a showable item exists) **OR** `retired_by_withdrawn`. `ValidityReconciler` stays the only writer of validity:
   - In `DeletionService.delete` and `edit`: before relations are removed, the reconciler records which targets were SUPERSEDED solely through the withdrawn item, and sets the flag on them.
   - Otherwise the reconciler still never reactivates a flagged item.
   - A later valid claim by the same speaker on the same property supersedes normally. The flag only keeps the item retired.
3. **Answers.**
   - A flagged item is not shown as current.
   - When it is the only claim on the subject, the composer may say "an earlier statement on this was later changed; Dita has no current statement". It must not quote the deleted correction, give its id, or reveal that a deletion happened if that would identify it. Natasha reviews that wording.
   - The message path must also stop citing the flagged claim's message as current (DEC-033 `retired` set).
4. **Projections.** Neo4j and Vespa already read validity from truth through `KnowledgeRef`. The flag is published like any other validity change. No projection schema change.
5. **Tests to change or add.**
   - The two reactivation tests above are inverted: the earlier claim stays SUPERSEDED.
   - New tests:
     - early and late deletion of a correction;
     - an edit of a correction;
     - a later new claim superseding normally;
     - no deleted wording or ids anywhere in the store, projections or answers;
     - the trust suite unchanged.
6. **Data.**
   - The original flag proposal is superseded by DEC-038/039. On the V6 upgrade, existing SUPERSEDED rows conservatively become UNRESOLVED; their withdrawal origin cannot be recovered from V5. ACTIVE rows, wording and evidence are unchanged. Fresh V6 relation-only retirements remain repairable. This is an approval-gated upgrade rule, not historical reactivation or semantic re-extraction.
   - Items already reactivated before V6 stay as they are, and this is reported as a known limitation.

## Approvals and owners
| Item | Owner | Approval |
|---|---|---|
| V6 migration | Shuri drafts, Fury reviews | operator must approve before it is applied anywhere but a throwaway test database (V5 is never edited) |
| Answer wording, deletion residue | Natasha | Her review is required |
| Implementation | Next correctness task, after the V6 approval | Kept separate from the semantic evaluation; it does not depend on which relation architecture wins |
