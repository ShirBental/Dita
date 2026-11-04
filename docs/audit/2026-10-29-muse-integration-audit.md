> **Historical snapshot (2026-11-04 cleanup).** Existing findings are preserved; current runtime guidance: [documentation index](../README.md).

# Muse end-to-end integration audit (2026-10-29)

**Method:** read the CURRENT code (grep of main sources, bean wiring, module dependencies); ran the offline suite today; read the real-stack logs.

**Constraints:** no live calls, no changes to code or schema. Coordinated with RFC-002 (proposed), ADR-001/DEC-020 (event transport), and TASK-034 (Shuri building `dita-eval`; untouched).

**Evidence base**
- **Offline today:** `mvn -o test` BUILD SUCCESS (core 53; store 50 with 25 DB-gated skipped; projections 5 + E2E class "0 run" offline; muse 26 with 2 live skipped; telegram 14; app 13).
- **Real stack** (`build/infra-report/e2e.log`, 2026-10-28 18:00): PostgresMessageStoreContractTest 24/24, PostgresRlsIsolationTest 1/1, **EndToEndSliceTest 6/6**, VespaResponseTest 5/5; V2 + V3 applied. No core, store, projection or telegram main code changed after that run; only `dita-app` Muse config changed.
- **Live Muse:** one successful extraction (run 5, `muse-extraction-report.json`) on in-memory fixture `Message` objects, not via ingestion.

## 1. Runtime flow: per-step status

Legend:
- **WIRED** = implemented and used by the running app.
- **NOT WIRED** = implemented, but not used by the running app.
- **IN-MEMORY** = the running app uses a mock or reference implementation.
- **MISSING** = not implemented.

| # | Step | Status | Code (current) | Exercised by |
|---|---|---|---|---|
| 1 | Telegram ingestion | **WIRED, but to in-memory backends**; never run against the live API | `TelegramAdapter.onMessage` → `IngestionPipeline.ingest` (TelegramAdapter.java:167); bean in `TelegramConfiguration` (only if `dita.telegram.enabled`) | `TelegramAdapterTest` (14, synthetic updates, `InMemoryMessageStore`, `EventPublisher.NONE`) |
| 2 | PostgreSQL persistence + outbox | **NOT WIRED** | `PostgresMessageStore`, `PostgresEventPublisher` (dita-store-postgres). The app has **no dependency** on dita-store-postgres (dita-app/pom.xml) and **no datasource config**. `DitaConfiguration.messageStore()` returns `new InMemoryMessageStore()` (line 52) | Contract 24 + RLS 1 + E2E 6 **on the real DB** |
| 3 | Event delivery + projection updates | **NOT WIRED** (app: IN-MEMORY) | Real: `PostgresOutboxRelay` → `Neo4jProjection` / `VespaProjection` (dita-projections; not an app dependency). App: in-memory `EventDispatcher` + `InMemoryGraphProjection` / `InMemorySearchIndex` (DitaConfiguration.java:56-59) | E2E 6 on the real stack; `DeletionPolicyTest` (in-memory). Open: ADR-001 F1/N-44 cursor race (inferred, not reproduced) |
| 4 | Muse extraction | **NOT WIRED** | `MuseKnowledgeExtractor.extract`. Bean only (MuseConfiguration.java:20, if `dita.muse.enabled`); **no caller anywhere** in main code. **Blocker:** `extract` refuses every non-SYNTHETIC message (MuseKnowledgeExtractor.java, OPEN-6 invariant), and Telegram ingests as `SourceKind.PLATFORM` (TelegramAdapter.java:166). So Telegram → Muse cannot work as designed | `MuseKnowledgeExtractorTest` 6, `MuseClientTest` 17, `MuseExtractionRequestPreviewTest` 1 (offline); 1 live run on fixture objects |
| 5 | Evidence validation | **NOT WIRED** (runs only inside 4); **partial** | `verifiedSources`: ids in input, verbatim normalised quote ≥ 12 chars. **Missing** (RFC-002 / Natasha): speaker from metadata (K-2), negation/stance check (K-3), subject check, status guards | Same as 4 |
| 6 | Persistence of accepted knowledge | **MISSING** (the store API exists) | No main-code caller of `addKnowledge` (only `DeletionService.replaceKnowledge`). `PostgresMessageStore.addKnowledge` + evidence rows exist. The current `KnowledgeItem` stores model wording (K-1) and no speaker | Store contract `knowledgeRoundTrips…` (real DB) |
| 7 | Knowledge indexing (Neo4j, Vespa) | Neo4j: **implemented, NOT WIRED, untested with knowledge**. Vespa: **MISSING** | `Neo4jProjection.onKnowledge` (DERIVED_FROM edges). `VespaProjection.onKnowledge` is a no-op | `EndToEndSliceTest` ingests no knowledge, so this is not exercised |
| 8 | Retrieval of stored knowledge | **MISSING** | `LexicalRetriever`, `GraphExpandingRetriever`, `HybridRetriever`, `VespaProjection.retrieve` and `QueryService.ask` return **message** hits only; nothing reads `knowledge(scope)` | — |
| 9 | Muse-generated answer with verified sources | **MISSING**. What exists and is wired is a *deterministic* grounded answer | `QueryService.ask` → `ExtractiveAnswerComposer` (verbatim quotes) → `AnswerVerifier`. Telegram `/ask` uses it (TelegramAdapter.java:227). No Muse answer composer exists | `SliceOneTest`, `ApplicationStartupTest`, `TelegramAdapterTest.askAnswers…`; E2E test 4 on the real stack (extractive, Vespa + Neo4j retrieval) |
| 10a | Deletion propagation | **WIRED (in-memory)**; implemented and **tested on the real stack** | `/forget` → `DeletionService.forget` (TelegramAdapter.java:217) → tombstone + selective invalidation + outbox `DeletionEvent` → projections | `DeletionPolicyTest` 24, `TelegramAdapterTest` forget cases; **E2E tests 5–6 on the real stack** (PG + Neo4j + Vespa, replay/reconcile) |
| 10b | Correction propagation (a correction targets an earlier statement) | **MISSING** | No relation model, no correction target (RFC-002 §3.5). Edits don't revalidate knowledge (K-4, `InMemoryMessageStore.edit`) | — |

## 2. Verified capabilities (genuinely exercised)

- **Real stack, synthetic, via test code** (not the app), all in `EndToEndSliceTest` 6/6:
  - ingest → Postgres (RLS, least-privilege roles) + outbox;
  - durable relay → Neo4j (structure only, no text) + Vespa;
  - group-scoped BM25 including Hebrew;
  - Neo4j graph expansion with inferred-link flags;
  - extractive answer verified against Postgres;
  - early deletion propagating to all three stores;
  - replay/reconcile never restoring text.
- **Muse API, live:** auth, endpoint, model, documented response shape, `max_completion_tokens`, `reasoning_effort=low`. One extraction with 7/7 items verbatim-validated. Cost and latency measured.
- **Telegram adapter logic (offline, synthetic):** scoping, allow-list, identity, `/forget`, `/ask`, signed version-bound approvals, idempotency, migration, removal and retention.
- **The running application** (`DitaApplication`): starts with the committed yml; in-memory ingest → extractive answer (`ApplicationStartupTest`).

**Not established by any test:** Telegram → Postgres, Telegram → Muse, Muse → persisted knowledge, knowledge retrieval, a Muse-written answer, the live Telegram API, or the app running on Postgres/Neo4j/Vespa.

## 3. Missing connections (the actual gap)

1. **The app runs on in-memory components.** Postgres, the relay and the Neo4j/Vespa projections are tested but not wired into `dita-app`. This is the same as ADR-001 Q3, which awaits a human decision.
2. **No extraction trigger.** Nothing calls Muse after ingestion.
3. **The Telegram/Muse data policy conflicts.** Telegram messages are PLATFORM; Muse accepts only SYNTHETIC (OPEN-6/C-1). A demo through Telegram needs a policy decision (§5, P0-1).
4. **No knowledge persistence path**, and the current item shape violates K-1/K-2 if persisted as-is (RFC-002).
5. **No knowledge retrieval; answers are message-only.** No Muse answer composer.
6. **Vespa knowledge indexing** is missing; Neo4j knowledge indexing is untested.
7. **Correction targets** and edit revalidation are missing.

## 4. Actual test coverage (by layer)

| Layer | Offline (run today) | Real stack (Oct 28 18:00) | Live API |
|---|---|---|---|
| Core memory, deletion, config | 53 + 13 pass | — | — |
| Postgres store, RLS | in-memory contract 22; PG 25 skipped offline | 24 + 1 pass | — |
| Relay → Neo4j/Vespa → retrieval → answer → deletion | E2E class "0 run" offline | **6 pass** | — |
| Telegram adapter | 14 pass (synthetic) | — | **never** |
| Muse client/extractor | 24 pass (fake transport) | — | 5 runs; 1 successful extraction |
| Telegram → Muse → knowledge → answer | **none** | **none** | **none** |

## 5. Minimum path to the demo: Telegram → Muse → persistent memory → grounded answer

No redesign, Kafka work or new datastore. Ordered by the blockers to the Telegram demo.

| P | Change (minimum) | Depends on | Size |
|---|---|---|---|
| **P0-1** | **Policy decision:** allow-listed demo chats are treated as **SYNTHETIC** (the team types synthetic content), so Muse may process them. Alternatively, the demo shows Telegram for ingest/`/ask`/`/forget` and Muse only on fixtures. Needs PO + Natasha: it relaxes how C-1/OPEN-6 is enforced, via a `dita.telegram.synthetic-demo-chat-ids` label | — | S |
| **P0-2** | **Wire the real path into `dita-app`** behind a `postgres` profile: add the dita-store-postgres + dita-projections dependencies; datasource from env; beans for `PostgresMessageStore`, `PostgresEventPublisher` (pipeline + deletion), `PostgresOutboxRelay` + Neo4j/Vespa consumers + a relay worker. The in-memory default stays for tests (ADR-001 Q3) | human OK on Q3 | M |
| **P0-3** | **Extraction trigger:** a batch worker that, after ingestion, takes a group's new SYNTHETIC messages (window = existing `linkWindowMessages`) and calls `MuseKnowledgeExtractor`. Rate- and budget-capped; never at question time | P0-1, P0-2 | S–M |
| **P0-4** | **Persist accepted items, minimum RFC-002 subset:** speaker from metadata, verbatim spans, templated text (K-1, K-2, K-3 negation check). Needs the V5 migration | RFC-002 approval (D-1..D-5) | M |
| **P0-5** | **Knowledge-aware answer:** `QueryService` reads the group's stored knowledge (small; straight from Postgres, no projection needed for the demo), plus a templated answer citing speaker + verbatim quote + message id, re-verified by `AnswerVerifier` | P0-4 | S–M |
| **P0-6** | **One gated integration test** `TelegramToKnowledgeAnswerTest`: synthetic Telegram updates → adapter on Postgres → relay → extraction with a **recorded** Muse response (run-5 shape, fake transport, no live call) → persisted knowledge → `/ask` cites it → `/forget` removes it | P0-2..5 | M |
| P0-7 | Telegram bot token + one live run in an allow-listed group | human | S |
| P1 | Muse-phrased answer (stretch: needs a faithfulness check beyond citations); Vespa knowledge docs; Neo4j knowledge test; correction targets; edit revalidation; F1/N-44 relay fix before any real data | — | — |

**The critical path:** P0-1 decision → P0-2 → (RFC-002 approval) P0-4 → P0-3 → P0-5 → P0-6 → P0-7.

Until P0-6 passes, **no claim of end-to-end Muse integration is supportable**.
