> **Historical (TASK-055, 2026-11-04).** Kept as evidence; do not follow as current instructions. Current status: [capability-status.md](capability-status.md); index: [README.md](README.md).

# Dita execution plan: 2026-10-27 → 2026-11-04

Owner: Nick Fury. Status is kept live in `.avengers/tasks.md` (recovery copy) and Notion.
**Reality check:** 8 calendar days remain. The plan is ordered so that each day ends with something that can be demonstrated, and every heavy technology has a gate with a pre-agreed fallback.

## 1. Environment findings (2026-10-27, verified in this session)

| Item | Finding | Impact |
|---|---|---|
| JDK | `openjdk@21` (21.0.12) installed via Homebrew, **not on PATH**. Use `JAVA_HOME=/opt/homebrew/opt/openjdk@21` | OK |
| Build | Maven 3.9.16; JUnit 5.12.2 and Spring Boot 3.5.x artifacts cached; Gradle absent | Maven multi-module (DEC-005) |
| Maven Central | Blocked by the sandbox proxy unless allowed per command | New dependencies need an allowed fetch |
| PostgreSQL | Client 16.15 and Homebrew `postgresql@16`/`@18` installed; **server not running**; this sandbox blocks local socket/TCP connections | Postgres integration tests are blocked in this session (BLK-1) |
| Containers | **No Docker, Podman or Colima** | Neo4j, Vespa and Redpanda cannot run (BLK-2) |
| Node | Absent | No JS tooling; not needed |
| Muse / Meta Model API | Endpoint, model IDs and auth **unverified**; no key; credits unconfirmed | Live calls blocked (DEC-002/003); deterministic tier is the default |
| Telegram | No bot token yet | BLK-4 |
| Reusable code | `connectivity/meta_model_client.py`: a Python, stdlib-only client with 49 mocked tests (never executed) | Reference for the Java `LlmPort` adapter; not on the critical path |

> Status note (2026-11-02, DEC-040): this plan is not a capability record. Current state: docs/capability-status.md.

## 2. Slices (each demonstrable on its own)

| # | Slice | Proves FR | Target | Status |
|---|---|---|---|---|
| S1 | Core memory in-process: synthetic ingest → explicit + inferred links → lexical/graph/hybrid retrieval → verified grounded answer; coordination state machines | 1, 2, 4, 5, 6, 7, 9 (logic) | Oct 27 | **Done: 21/21 tests pass** (`mvn -o test`) |
| S2 | Persistence: Postgres store + Flyway V1 + outbox worker + rebuild-from-truth | 1, 3, 7 | Oct 28–29 | Blocked by BLK-1 |
| S3 | Benchmark v0 (≈150 synthetic msgs, 3 groups, HE/EN, ≈60 labelled questions) + harness: Recall@K, precision, attribution, context reconstruction, latency | eval | Oct 28–30 | Next (Bruce designs, Shuri builds harness) |
| S4 | Telegram adapter (long polling) → same pipeline → `/ask` answers with citations | 10 | Oct 29–30 | Blocked by BLK-4 (token) |
| S5 | Graph projection in Neo4j + graph-expansion retriever on Neo4j | 3, 4 | Oct 30–31 | Gate G2 |
| S6 | Vespa hybrid (BM25 + multilingual embeddings) + Vector-RAG baseline | 4, eval | Oct 31–Nov 1 | **NOT BUILT; dense retrieval disabled (DEC-013). G3 never decided; no deferral decision. See docs/capability-status.md (DEC-040)** |
| S7 | Birthday coordination in Telegram: plan, tasks, inline-button approvals, open questions; admin config with unanimity | 8, 9 | Nov 1–2 | Logic done in S1 |
| S8 | Muse tier: grey-zone link adjudication + answer phrasing behind the verifier, budget-guarded | quality | Nov 1–2 | Gate G4 (human approval) |
| S9 | Full benchmark run: Vector vs Graph vs Hybrid; Natasha release review; demo rehearsal A and B | eval, demo | Nov 2–3 | — |
| — | Buffer, freeze, submission | — | Nov 4 | — |

## 3. Critical path

`BLK-1/BLK-2 runtime` → **S2 Postgres** → **S4 Telegram** → S7 coordination in chat → S9 rehearsal.
S3 benchmark runs in parallel and must exist before S5, S6 and S8 can be judged.
Neo4j, Vespa and Muse are *value adds on the same ports*: if their gates fail, the demo still runs on the Postgres/rule fallbacks.

## 4. Gates and open engineering decisions

| ID | Decision | Owner | Deadline | Default if not decided |
|---|---|---|---|---|
| G1 / BLK-1 | Run Postgres reachable from the dev session: start `brew services start postgresql@16` **and** allow local connections in the sandbox (security setting, so the user decides) | **Human** | Oct 28 | Keep building against the in-memory store; S2 slips |
| G2 / BLK-2 | Install a container runtime (OrbStack, Colima or Docker Desktop) for Neo4j and Vespa | **Human** | Oct 29 EOD | **Escalation:** without it, Neo4j and Vespa drop to their Postgres fallbacks for the demo. That changes the established stack in practice, so it needs your OK |
| G3 | Embedding source for the vector retriever (Muse embeddings if available, else a local multilingual model such as multilingual-e5 via Vespa's embedder) | Tony + Bruce | Oct 30 | Local multilingual model (no spend) |
| G4 | Muse access: verified docs URL, model IDs, key, credits; approval per live call (model, data, cost) | **Human** | Oct 31 | Rules-only plus extractive answers |
| BLK-4 | Telegram bot token (BotFather) stored in `.env` (unreadable by agents); test group with consenting participants | **Human** | Oct 29 | Synthetic replay demo only |
| OPEN-5 | Confirm "agreed message deletion behaviour" (RFC §7.6 proposal) | **Human / Peter** | Oct 30 | Proposal as written |
| OPEN-6 | Live-demo data: real Telegram messages are `PLATFORM`, stored locally only, and purged after the hackathon | Natasha | Oct 30 | Synthetic-only demo group |
| OPEN-7 | Hosting: local laptop demo versus one small VM (deploy is reserved) | **Human** | Nov 1 | Local laptop |
| OPEN-8 | LLM-phrased answers: sentence-level attribution check (the verifier currently checks citations, not prose) | Bruce + Tony | Nov 1 | Ship extractive answers only |

## 5. Next 48 hours (assigned)

1. Shuri: Spring Boot `dita-app` skeleton + `dita-store-postgres` (JDBC + Flyway) coded against V1, with integration tests that run once BLK-1 clears.
2. Bruce: benchmark spec and labelled question set (synthetic, HE/EN, all 9 phenomena incl. cross-group isolation). Shuri builds the harness over the `Retriever` port.
3. Shuri: `dita-telegram` long-poll adapter with a recorded-update replay test, so it can be built without a token.
4. Tony: review RFC-001 and the ports. Natasha: review the isolation, approval, retention and data-egress rules (release-blocking).
