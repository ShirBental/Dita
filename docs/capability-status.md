# Dita capability register (DEC-040)

This is the single source for what Dita can actually do. Fury keeps it current. Any report on quality or readiness cites it.

Maturity levels:
- **DESIGNED**: in an RFC, plan or diagram only.
- **IMPLEMENTED**: code plus unit tests, but not reachable from the runtime entry point.
- **INTEGRATED**: wired into the default runtime path, with file:line evidence.
- **VERIFIED E2E**: a reproducible acceptance test passes through the runtime entry point on the declared stack. Skips do not count.
- **PRODUCT VALIDATED**: meets the PO's criterion on unseen cases.

Not evidence of "operational": diagrams, config names, unit tests, in-memory reference projections, or existing infrastructure.

> **2026-11-04 note (Fury, TASK-055).** Rows below predate the TASK-054 demo where they say otherwise. Current demo (`scripts/telegram-synthesis-trial.sh postgres semantic`): Telegram ingest → PG + outbox → Vespa/Neo4j; messages retrieved by BM25 (content words) + local bge-small dense message search + reply links; ConversationContextSelector; one Muse synthesis call (muse-answer-v3); reference-integrity checks only (language rules off, sources hidden, operator's choice). Evidence: two live SYNTHESIZED runs on one synthetic question (57c9, 0cc5; Codex's record), i.e. INTEGRATED and demonstrated, not PRODUCT VALIDATED. Stale statements below: `telegram-run.sh` is not the only launcher; messages are no longer placeholder-only (dense message embeddings exist when the TASK-054 package is deployed); "citations/answer verification" does not apply to the demo path.

Runtime facts:
- Default `dita.storage.backend=IN_MEMORY` (`dita-app/src/main/resources/application.yml:13`).
- The only launch script, `scripts/telegram-run.sh:46-47`, forces IN_MEMORY, Muse off and coordination off.
- POSTGRES / Vespa / Neo4j wiring exists, but only environment-gated tests select it.

Baseline from Tony Stark's read-only audit, 2026-11-02. Nick Fury spot-checked the starred rows (*) against the code.

| Capability | Level | Evidence | E2E acceptance test |
|---|---|---|---|
| Ingestion (Telegram → store) | VERIFIED E2E (in-memory) | `TelegramAdapter.java:264` → `IngestionPipeline.java:29` | `TelegramLiveRunConfigurationTest` (calls `adapter.handle`, not the poller) |
| Message store, in-memory | VERIFIED E2E | `InMemoryRuntimeConfiguration.java:24` | `ApplicationStartupTest` |
| Message store, PostgreSQL | VERIFIED E2E (synthetic replay only) | `PostgresRuntimeConfiguration.java:37`; selected by `scripts/demo-replay-run.sh` and the replay acceptance test | `PostgresRuntimeIntegrationTest`: gated, skipped by default |
| Extraction (proposer → validator → knowledge)* | VERIFIED E2E on POSTGRES + Neo4j + Vespa and in memory (synthetic replay, RECORDED Muse; live Muse not exercised) | TASK-051 runtime trigger: replay adapter → `ExtractionHooks.ingested` → `enqueue`; `/extract` by a configured approver → `collect`/`approve` (`TelegramConfiguration`); worker → Muse client. Live Telegram stays PLATFORM, with no trigger (N-8) | `TelegramReplayInMemoryWiringTest` PASS; `TelegramReplayPostgresAcceptanceTest` PASS on the real stack, 1 run / 0 skipped, run by the user via `scripts/e2e-replay-verify.sh` 2026-11-02T20:57Z, tree a038da39… (log: build/e2e-replay-verify/) |
| Knowledge relations (`RelationRules`) | IMPLEMENTED | runs only inside extraction | none |
| Validity (`ValidityReconciler`, V5/V6) | IMPLEMENTED | runs on `/forget`, but the runtime knowledge store is empty | none |
| Knowledge retrieval | VERIFIED E2E, in memory and on POSTGRES (Vespa knowledge BM25), via synthetic replay. BM25 only: no dense in this tree | `QueryService.java:82`; POSTGRES `PostgresRuntimeConfiguration` knowledge retriever (falls back to lexical if Vespa is down) | replay tests above; the PG run asserted the Vespa knowledge documents, retrieval returning the stored ids, the Neo4j Knowledge node count, and the cited answer |
| Lexical message retrieval (BM25 + graph RRF) | VERIFIED E2E (in-memory) | `Retrievers.java:13-19` | `ApplicationStartupTest` |
| Dense / vector retrieval* | VERIFIED E2E on the recorded 2026-11-03 tree (TASK-052; default off, POSTGRES only). Current tree not rerun; not PRODUCT VALIDATED | TASK-049 patch applied cleanly to main (27 files). `PostgresRuntimeConfiguration` knowledge-retriever bean → `HybridKnowledgeRetriever` (BM25 + dense, weighted RRF) when `dita.retrieval.dense.enabled=true`. The message `Retriever` stays BM25 + Neo4j. Offline: 477 run / 0 failures. Codex report: the semantic control answered with a correct citation, edited evidence excluded, a deleted item's Vespa document removed, no foreign-group dense hits; dev fixture hybrid 6/10 vs BM25 5/10, 3/3 negatives abstained, paraphrase 2/6 | `scripts/e2e-hybrid-verify.sh`: Codex's A/B/C test (`Task049EnglishIntegrationTest`) plus the replay acceptance with dense on (lexically poor m7 must be answered from m1 via the hybrid bean). PASS in prior real-stack run: 3/3 stages, 0 skipped, RECORDED Muse; tree `5c0fdd92…`, 2026-11-03. See [execution record](hybrid-verification-record.md). |
| Embeddings (knowledge) | INTEGRATED, POSTGRES only | Vespa built-in hugging-face embedder `dita-english` (bge-small-en-v1.5, 384-D, CLS, L2-normalised), from the generated package. Messages still use the 8-D placeholder | as above; vector values and norms never inspected directly (Codex's report) |
| Vespa BM25 (messages, knowledge) | IMPLEMENTED | POSTGRES only | gated, skipped |
| Neo4j graph | IMPLEMENTED | POSTGRES only; knowledge edges are written, never read at query time | gated, skipped |
| In-memory graph/search projections | DISABLED (written, never read) | `InMemoryRuntimeConfiguration.java:33-34` | none |
| History-access filter* | DISABLED | the membership port is a stub that always returns empty (`DitaConfiguration.java:60`); `SINCE_REQUESTER_JOINED` returns nothing for everyone | unit only |
| Row-level security | IMPLEMENTED | POSTGRES only | `PostgresRlsIsolationTest`: gated, skipped |
| Deletion (`/forget`, edits) | VERIFIED E2E (in-memory) | `TelegramAdapter.java:192,250,315` | `KnowledgeSliceInMemoryTest:191-197` (adapter path covered by component tests only) |
| Citations / answer verification | VERIFIED E2E (in-memory) | `QueryService.java:54,89,94` | `ApplicationStartupTest:36` |
| Outbox / projection relay | IMPLEMENTED | in memory: feeds unread projections; POSTGRES only otherwise; reconcile never called | gated |
| Coordination (`/plan`, approvals) | PARTIAL | in-memory map, not persisted; off in the live run | component tests |
| Admin config unanimity, Muse tiers T1/T2, 21-day retention purge | NOT INTEGRATED / NOT IMPLEMENTED | no application caller; tier settings have no effect | none |

No capability is PRODUCT VALIDATED.

TASK-051 (2026-11-02): the demo path is synthetic Telegram replay on POSTGRES + Neo4j + Vespa (user decision). Its acceptance test PASSED on the real stack (user-run, 2026-11-02T20:57Z, 0 skipped). The path: real adapter → PG ingest → extraction trigger → /extract approval → recorded Muse → validator → PG knowledge → outbox relay → Vespa + Neo4j → BM25 knowledge retrieval → grounded, cited /ask. Not covered: live Muse, live Telegram, dense retrieval, holdout quality. Recorded Muse replies are not live Muse.

TASK-052 (2026-11-02): Codex's TASK-049 patch is integrated in this tree and passes offline. Dense is not VERIFIED E2E here until `scripts/e2e-hybrid-verify.sh` passes. Codex's dev-fixture numbers: hybrid 6/10 vs BM25 5/10, paraphrase 2/6, 3/3 negatives abstained. Those numbers are calibrated on development data, not unseen data.

Open: whether the gated PostgreSQL / Vespa / Neo4j tests pass on the current tree (UNKNOWN; this sandbox cannot run them).
