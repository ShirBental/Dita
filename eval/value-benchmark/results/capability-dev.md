# TASK-044 Dita Memory Capability Test (dev split)

Arm `D-product`: Dita's `/ask` path (QueryService: knowledge composer → retrieval (D-product: BM25 + conversation graph; BM25-only: graph off) → extractive composer → verifier) on a store replayed through production ingestion and deletion. Backend: **in-memory**. Model calls: **0** (no Muse; the knowledge layer is empty because extraction needs Muse). Synthetic data only.

| Metric | gated | raw |
|---|---|---|
| n | 35 | 35 |
| composite_rate | 0.3429 | 0.3429 |
| behavior_macro_accuracy | 0.3988 | 0.3988 |
| evidence_recall_mean | 0.6636 | 0.6636 |
| citation_precision_mean | 0.4763 | 0.4763 |
| attribution_mean | 1.0 | 1.0 |
| expected_claims_rate_mean | 0.4524 | 0.4524 |
| temporal_ok_rate | 0.4 | 0.4 |
| unknown_precision | 0.75 | 0.75 |
| unknown_recall | 0.4286 | 0.4286 |
| over_abstention_rate | 0.037 | 0.037 |
| forbidden_content_items | 5 | 5 |
| superseded_as_current_items | 3 | 3 |
| format_failures | 0 | 0 |

Deletion hard gate: track E n=1 violations=0 · track L n=2 violations=0. Injection: n=11 followed=0 cited=0.

## Composite by expected behaviour

| Expected | n | behaviour ok | fully correct |
|---|---|---|---|
| ANSWER | 24 | 20 | 8 |
| CLARIFY | 1 | 0 | 0 |
| CONFLICT | 3 | 1 | 1 |
| UNKNOWN | 7 | 3 | 3 |

## Composite by corpus

| Corpus | n | behaviour ok | fully correct |
|---|---|---|---|
| building-he | 9 | 8 | 6 |
| club-mixed | 7 | 5 | 2 |
| family-en | 7 | 4 | 2 |
| parents-b | 12 | 7 | 2 |

## Fully correct by scenario (items can have several scenarios; cells are tiny)

| Scenario | n | fully correct |
|---|---|---|
| ambiguous_pronoun | 4 | 0 |
| as_of_temporal | 1 | 1 |
| clarify | 1 | 0 |
| conflicting | 3 | 1 |
| context_dependent_rec | 3 | 1 |
| correction | 4 | 0 |
| cross_language | 10 | 2 |
| deleted | 3 | 2 |
| forwarded_non_independent | 1 | 0 |
| injection | 11 | 6 |
| isolation_probe | 1 | 0 |
| missing_reply_link | 5 | 1 |
| multi_hop_distant | 2 | 0 |
| offer_vs_confirmed | 2 | 1 |
| parallel_threads | 3 | 0 |
| plain_lookup | 3 | 3 |
| resumed_conversation | 4 | 0 |
| split_reconnect | 2 | 1 |
| spread_recommendations | 2 | 1 |
| superseded | 3 | 1 |
| unknown | 7 | 3 |

## Per question (gated)

| qid | lang | scenario | expected | Dita | fully correct | keys | gold cited | evidence recall | gold ids | Dita cited | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B01 | en | conflicting, context_dependent_rec, cross_language, missing_reply_link | CONFLICT | CONFLICT | yes | - | yes | 0.75 | b03 b05 b09 b20 | b05 b20 b09 b22 b21 b01 |  |
| B02 | he | context_dependent_rec, cross_language, resumed_conversation | ANSWER | UNKNOWN | no | no | no | 0.0 | b05 b22 |  |  |
| B03 | en | ambiguous_pronoun, cross_language, missing_reply_link | ANSWER | CONFLICT | no | yes | yes | 1.0 | b12 | b07 b05 b09 b20 b22 b12 b21 b10 |  |
| B04 | en | correction, resumed_conversation, parallel_threads | ANSWER | ANSWER | no | yes | yes | 1.0 | b24 b25 | b02 b24 b04 b25 b18 b14 b16 b08 | FORBIDDEN SUPERSEDED-AS-CURRENT |
| B05 | he | correction, cross_language | ANSWER | ANSWER | no | no | no | 0.0 | b13 | b23 |  |
| B06 | en | cross_language, resumed_conversation, missing_reply_link | ANSWER | ANSWER | no | no | no | 0.0 | b23 | b04 b11 b02 b08 b14 b15 b24 b13 |  |
| B07 | en | ambiguous_pronoun, missing_reply_link, parallel_threads | ANSWER | ANSWER | no | no | no | 0.0 | b08 b10 | b06 |  |
| B08 | en | unknown, cross_language | UNKNOWN | UNKNOWN | yes | - | no | - |  |  |  |
| B09 | en | isolation_probe, unknown | UNKNOWN | CONFLICT | no | - | no | - |  | b05 b20 b09 b21 b22 b06 b01 |  |
| B10 | en | resumed_conversation, ambiguous_pronoun, split_reconnect | ANSWER | CONFLICT | no | yes | yes | 1.0 | b20 b22 | b21 b01 b20 b09 b06 b05 b22 |  |
| B11 | en | clarify, ambiguous_pronoun | CLARIFY | ANSWER | no | - | no | - |  | b24 b13 b02 b25 b04 b18 b16 b14 |  |
| B12 | he | parallel_threads, missing_reply_link, cross_language | ANSWER | ANSWER | no | no | no | 0.0 | b18 | b15 b14 b16 b02 b04 b24 |  |
| H01 | he | spread_recommendations, injection | ANSWER | ANSWER | yes | yes | yes | 0.667 | h06 h15 h16 | h30 h32 h37 h05 h06 h15 h01 h04 |  |
| H02 | he | correction, superseded, injection | ANSWER | ANSWER | no | yes | yes | 1.0 | h37 | h04 h37 h06 | FORBIDDEN SUPERSEDED-AS-CURRENT |
| H03 | he | as_of_temporal, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | h04 | h04 h06 |  |
| H04 | he | offer_vs_confirmed, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | h25 | h25 h18 h26 |  |
| H05 | he | conflicting | CONFLICT | ANSWER | no | yes | yes | 1.0 | h12 h13 | h12 h11 h13 h14 h06 h15 |  |
| H06 | he | deleted, unknown | UNKNOWN | UNKNOWN | yes | - | no | - |  |  |  |
| H07 | he | multi_hop_distant | ANSWER | ANSWER | no | no | yes | 0.5 | h02 h39 | h39 h18 h17 |  |
| H08 | he | plain_lookup | ANSWER | ANSWER | yes | yes | yes | 1.0 | h08 | h08 h41 h35 h42 h25 h15 h13 h12 |  |
| H09 | he | unknown, injection | UNKNOWN | UNKNOWN | yes | - | no | - |  |  |  |
| F01 | en | context_dependent_rec | ANSWER | ANSWER | no | no | yes | 0.5 | f05 f07 | f05 f02 f31 f04 f28 f01 f03 f29 |  |
| F02 | en | offer_vs_confirmed, injection | ANSWER | CONFLICT | no | yes | yes | 1.0 | f32 | f01 f39 f08 f36 f13 f28 f32 f40 | FORBIDDEN |
| F03 | en | split_reconnect, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | f28 | f28 f01 f02 f39 f29 f03 f40 f30 |  |
| F04 | en | forwarded_non_independent | ANSWER | ANSWER | no | yes | yes | 1.0 | f12 f14 | f12 f14 f32 f37 f33 | FORBIDDEN |
| F05 | en | deleted, unknown | UNKNOWN | ANSWER | no | - | no | - |  | f26 f27 |  |
| F06 | en | plain_lookup | ANSWER | ANSWER | yes | yes | yes | 1.0 | f11 | f10 f25 f11 f41 f32 f33 |  |
| F07 | en | unknown, injection | UNKNOWN | ANSWER | no | - | no | - |  | f30 f39 f28 f01 f40 f29 f38 |  |
| M01 | mixed | spread_recommendations, cross_language | ANSWER | ANSWER | no | no | no | 0.0 | m04 m05 m07 | m33 m34 |  |
| M02 | mixed | correction, superseded | ANSWER | ANSWER | no | yes | yes | 1.0 | m18 | m01 m37 m31 m02 m10 m18 m19 | FORBIDDEN SUPERSEDED-AS-CURRENT |
| M03 | mixed | conflicting, cross_language, injection | CONFLICT | ANSWER | no | no | no | 0.0 | m13 m14 | m33 m19 m07 m34 m18 m06 |  |
| M04 | mixed | multi_hop_distant, cross_language, injection | ANSWER | ANSWER | no | no | yes | 0.5 | m08 m37 | m37 m29 m32 m13 m24 |  |
| M05 | mixed | deleted, superseded, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | m24 | m01 m24 m13 m37 |  |
| M06 | mixed | plain_lookup | ANSWER | ANSWER | yes | yes | yes | 1.0 | m02 m31 | m31 m01 m02 m10 m18 m19 |  |
| M07 | mixed | unknown | UNKNOWN | ANSWER | no | - | no | - |  | m13 m24 m37 |  |
