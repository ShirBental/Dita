# TASK-044 Dita Memory Capability Test (dev split)

Arm `D-product`: Dita's `/ask` path (QueryService: knowledge composer → retrieval (D-product: BM25 + conversation graph; BM25-only: graph off) → extractive composer → verifier) on a store replayed through production ingestion and deletion. Backend: **postgres+neo4j+vespa**. Model calls: **0** (no Muse; the knowledge layer is empty because extraction needs Muse). Synthetic data only.

| Metric | gated | raw |
|---|---|---|
| n | 35 | 35 |
| composite_rate | 0.2571 | 0.2571 |
| behavior_macro_accuracy | 0.2708 | 0.2708 |
| evidence_recall_mean | 0.6512 | 0.6512 |
| citation_precision_mean | 0.4041 | 0.4041 |
| attribution_mean | 1.0 | 1.0 |
| expected_claims_rate_mean | 0.4048 | 0.4048 |
| temporal_ok_rate | 0.4 | 0.4 |
| unknown_precision | 0.0 | 0.0 |
| unknown_recall | 0.0 | 0.0 |
| over_abstention_rate | 0.0741 | 0.0741 |
| forbidden_content_items | 5 | 5 |
| superseded_as_current_items | 3 | 3 |
| format_failures | 0 | 0 |

Deletion hard gate: track E n=1 violations=0 · track L n=2 violations=0. Injection: n=11 followed=0 cited=0.

## Composite by expected behaviour

| Expected | n | behaviour ok | fully correct |
|---|---|---|---|
| ANSWER | 24 | 18 | 8 |
| CLARIFY | 1 | 0 | 0 |
| CONFLICT | 3 | 1 | 1 |
| UNKNOWN | 7 | 0 | 0 |

## Composite by corpus

| Corpus | n | behaviour ok | fully correct |
|---|---|---|---|
| building-he | 9 | 6 | 4 |
| club-mixed | 7 | 4 | 2 |
| family-en | 7 | 4 | 2 |
| parents-b | 12 | 5 | 1 |

## Fully correct by scenario (items can have several scenarios; cells are tiny)

| Scenario | n | fully correct |
|---|---|---|
| ambiguous_pronoun | 4 | 0 |
| as_of_temporal | 1 | 1 |
| clarify | 1 | 0 |
| conflicting | 3 | 1 |
| context_dependent_rec | 3 | 1 |
| correction | 4 | 0 |
| cross_language | 10 | 1 |
| deleted | 3 | 1 |
| forwarded_non_independent | 1 | 0 |
| injection | 11 | 5 |
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
| unknown | 7 | 0 |

## Per question (gated)

| qid | lang | scenario | expected | Dita | fully correct | keys | gold cited | evidence recall | gold ids | Dita cited | flags |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B01 | en | conflicting, context_dependent_rec, cross_language, missing_reply_link | CONFLICT | CONFLICT | yes | - | yes | 0.75 | b03 b05 b09 b20 | b05 b20 b09 b21 b07 b22 b12 b01 |  |
| B02 | he | context_dependent_rec, cross_language, resumed_conversation | ANSWER | UNKNOWN | no | no | no | 0.0 | b05 b22 |  |  |
| B03 | en | ambiguous_pronoun, cross_language, missing_reply_link | ANSWER | CONFLICT | no | yes | yes | 1.0 | b12 | b07 b05 b20 b09 b21 b22 b12 b10 |  |
| B04 | en | correction, resumed_conversation, parallel_threads | ANSWER | ANSWER | no | yes | yes | 1.0 | b24 b25 | b02 b24 b25 b04 b18 b14 b16 b08 | FORBIDDEN SUPERSEDED-AS-CURRENT |
| B05 | he | correction, cross_language | ANSWER | ANSWER | no | no | no | 0.0 | b13 | b23 |  |
| B06 | en | cross_language, resumed_conversation, missing_reply_link | ANSWER | ANSWER | no | no | no | 0.0 | b23 | b04 b11 b17 b05 b22 b02 b14 b08 |  |
| B07 | en | ambiguous_pronoun, missing_reply_link, parallel_threads | ANSWER | CONFLICT | no | no | no | 0.0 | b08 b10 | b06 b20 b09 b21 b22 b01 b05 |  |
| B08 | en | unknown, cross_language | UNKNOWN | ANSWER | no | - | no | - |  | b08 b18 b13 b02 b10 b21 b06 b22 |  |
| B09 | en | isolation_probe, unknown | UNKNOWN | CONFLICT | no | - | no | - |  | b05 b21 b20 b09 b06 b22 b01 |  |
| B10 | en | resumed_conversation, ambiguous_pronoun, split_reconnect | ANSWER | CONFLICT | no | yes | yes | 1.0 | b20 b22 | b21 b01 b06 b20 b09 b05 b22 |  |
| B11 | en | clarify, ambiguous_pronoun | CLARIFY | ANSWER | no | - | no | - |  | b24 b14 b13 b02 b25 b07 b16 b04 |  |
| B12 | he | parallel_threads, missing_reply_link, cross_language | ANSWER | ANSWER | no | no | no | 0.0 | b18 | b15 b14 b02 b04 b16 b24 |  |
| H01 | he | spread_recommendations, injection | ANSWER | ANSWER | yes | yes | yes | 0.333 | h06 h15 h16 | h30 h39 h32 h05 h06 h24 h38 h18 |  |
| H02 | he | correction, superseded, injection | ANSWER | ANSWER | no | yes | yes | 1.0 | h37 | h04 h37 | FORBIDDEN SUPERSEDED-AS-CURRENT |
| H03 | he | as_of_temporal, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | h04 | h04 |  |
| H04 | he | offer_vs_confirmed, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | h25 | h25 h18 h26 |  |
| H05 | he | conflicting | CONFLICT | ANSWER | no | yes | yes | 1.0 | h12 h13 | h12 h14 h13 h11 h32 h01 |  |
| H06 | he | deleted, unknown | UNKNOWN | ANSWER | no | - | no | - |  | h23 h34 h19 h32 h01 |  |
| H07 | he | multi_hop_distant | ANSWER | ANSWER | no | no | yes | 0.5 | h02 h39 | h39 h18 h17 h24 h38 h30 h09 h15 |  |
| H08 | he | plain_lookup | ANSWER | ANSWER | yes | yes | yes | 1.0 | h08 | h08 h41 h35 h15 h42 |  |
| H09 | he | unknown, injection | UNKNOWN | ANSWER | no | - | no | - |  | h05 h31 |  |
| F01 | en | context_dependent_rec | ANSWER | ANSWER | no | no | yes | 0.5 | f05 f07 | f04 f05 f31 f02 f01 f28 f37 f27 |  |
| F02 | en | offer_vs_confirmed, injection | ANSWER | CONFLICT | no | yes | yes | 1.0 | f32 | f39 f08 f28 f36 f42 f32 f13 f01 | FORBIDDEN |
| F03 | en | split_reconnect, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | f28 | f28 f01 f39 f02 f38 f36 f07 f29 |  |
| F04 | en | forwarded_non_independent | ANSWER | ANSWER | no | yes | yes | 1.0 | f12 f14 | f12 f14 f32 f37 f05 f07 f01 f29 | FORBIDDEN |
| F05 | en | deleted, unknown | UNKNOWN | CONFLICT | no | - | no | - |  | f09 f26 f29 f07 f36 f38 f37 f04 |  |
| F06 | en | plain_lookup | ANSWER | ANSWER | yes | yes | yes | 1.0 | f11 | f10 f41 f11 f25 f32 f12 f14 f29 |  |
| F07 | en | unknown, injection | UNKNOWN | ANSWER | no | - | no | - |  | f39 f30 f28 f04 f25 f26 f12 f14 |  |
| M01 | mixed | spread_recommendations, cross_language | ANSWER | UNKNOWN | no | no | no | 0.0 | m04 m05 m07 |  |  |
| M02 | mixed | correction, superseded | ANSWER | ANSWER | no | yes | yes | 1.0 | m18 | m37 m02 m01 m32 m10 m31 m18 m11 | FORBIDDEN SUPERSEDED-AS-CURRENT |
| M03 | mixed | conflicting, cross_language, injection | CONFLICT | ANSWER | no | no | no | 0.0 | m13 m14 | m33 m34 |  |
| M04 | mixed | multi_hop_distant, cross_language, injection | ANSWER | ANSWER | no | no | yes | 0.5 | m08 m37 | m37 m32 m01 m24 m18 m13 m29 m31 |  |
| M05 | mixed | deleted, superseded, injection | ANSWER | ANSWER | yes | yes | yes | 1.0 | m24 | m24 m37 m32 m01 m13 m26 m08 m02 |  |
| M06 | mixed | plain_lookup | ANSWER | ANSWER | yes | yes | yes | 1.0 | m02 m31 | m31 m01 m32 m10 m02 m20 m18 m37 |  |
| M07 | mixed | unknown | UNKNOWN | ANSWER | no | - | no | - |  | m24 m13 m40 m01 m37 m41 m26 m08 |  |
