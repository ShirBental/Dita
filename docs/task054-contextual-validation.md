# Contextual validation repair — 2026-11-04

The saved v3 picnic answer was rejected for `location`, `library`, and `room`, despite the selected evidence containing the place and the immediately preceding question identifying the library room.

## Implemented behavior

`GroundedSynthesis` now supplies local answer-to-question reference metadata to the validator. A reference is eligible only when both full, unchanged quotes are already selected, from the same scoped store and source kind, the question immediately precedes the answer in the live store, timestamps are strictly ordered within five minutes, and an explicit reply does not point elsewhere. Sparse retrieval adjacency is insufficient. No extra messages are sent to Muse.

The initial resolver supports one definite object of `use`, `book`, `reserve`, or `try` and a response containing sentence-initial `It is`. Alternative and multiple questions fail closed. It substitutes only the exact subject in an attributed answer clause; it never lends the question’s predicates, times, numbers, or other wording to the answer. Unsupported reference forms still fall back. This is bounded reference resolution, not a general natural-language entailment model.

When a subject is resolved, the antecedent question is retained in the final Sources/provenance along with the directly cited answer. Stored quotes are unchanged. A WHERE answer can use the neutral field label `the location is` when the single cited source contains an `at`/`near` expression; every place word remains subject to the original citation-local word check. Other validator rules remain active.

## Offline evidence

`ReferenceSupportTest` exercises the production path, provenance, generalization to other objects, wrong speakers/places/predicates, dropped negation, invented causes/times, missing or edited antecedents, intervening messages, incompatible replies, ambiguous questions and stale pairs.

`SavedLiveResponseReplayTest.savedV3AnswerPassesThroughProductionContextResolutionWithoutChangingArtifacts` replays the saved `run-20261104T164835Z-8567` response through `GroundedSynthesis`. The chronology fixture preserves all numerical gaps rather than treating sparse retrieved messages as adjacent. The saved request, response and ask artifacts are hash-checked unchanged. Eight modified answers must be rejected. The earlier invalid commitment and incomplete-answer replays remain rejected. No new model request is made.

Activation requires a runner restart; this change does not restart or send a Telegram message. A new live answer remains unverified until a separately authorized rehearsal.
