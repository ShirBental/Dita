# ADR-001: Event transport. PostgreSQL outbox + direct relay (A) vs PostgreSQL outbox + Kafka KRaft (B)

- **Status:** OPEN. Recommendation: keep A and defer B (DEC-020, pending human acknowledgement). Nothing has been merged or replaced.
- **Date:** 2026-10-28 (overnight review).
- **Deciders:** Fury (integration, routine delegation). Reviewers: Tony (architecture), Shuri (engineering), Natasha (security/privacy, release-gating), Bruce (evaluation), Peter (product/delivery, advisory).
- **Related:** DEC-004, DEC-012, RFC-001 §4/§11, N-20, N-27, N-28, and the new findings N-44 to N-51 (§6).

## 1. Context

- The user asked: was Kafka skipped before the outbox relay and async pipeline were built?
- **The record says it was considered.** DEC-004 and RFC-001 §4 put the Kafka-compatible bus behind an `EventBus` port and chose the outbox plus in-process workers. The stated reason was that no container runtime existed and only 8 days remained. Tony's 2026-10-27 refinement added that Kafka could only ever sit *behind* an outbox relay.
- **It was never measured.** Docker now exists: Engine 29.9.2, arm64, 7.75 GiB VM. So the review is legitimate. What changed is whether Kafka is *possible*, not yet whether it is *worth it*.
- **Current state (verified by reading the code):**
  - `PostgresOutboxRelay` gives each consumer its own cursor, with backoff, dead letters and `blocked_on_deletion`. The outbox carries ids only and has least-privilege roles (V2/V3).
  - The stack passed its E2E run on real Postgres, Neo4j and Vespa on 2026-10-28 at 22:00Z (EndToEndSliceTest 6/6; `build/infra-report/e2e.log`).
  - **However, the running `dita-app` still uses the in-memory store and `EventDispatcher`** (`DitaConfiguration.java:51-60`). `dita-app/pom.xml` does not depend on `dita-store-postgres` or `dita-projections`. The Postgres relay runs only inside `EndToEndSliceTest`.

## 2. Options

| Dimension | A: PG outbox + direct relay (existing) | B: PG outbox + relay to Kafka (KRaft, 1 broker) + Kafka consumers |
|---|---|---|
| Ordering | One global id order per consumer | Ordered within a partition (key = group_id). No order across topics |
| Replay | Outbox never pruned, so you can reset `last_id`. Rebuild-from-truth is the real path | Bounded by retention. Rebuild-from-truth is still required |
| Backpressure | Pull-based, per-consumer batches and backoff | Pull-based, consumer lag |
| Scaling | One active relay per consumer (SKIP LOCKED). Enough for chat scale | Partitions allow parallel consumers. Not needed at chat scale |
| Independent consumers | Yes: one `outbox_consumer` row each | Yes: consumer groups |
| Failure recovery | DLQ table; deletions block and alert, never skipped; idempotent projections that re-read truth | Retry and DLQ topics must be **built**. Defaults (auto-commit, Spring's skip-after-retries, `auto.offset.reset=latest`) **silently skip** deletions (N-48). A blocked deletion stalls the whole partition |
| Transactional guarantee | Truth + outbox row in one PG transaction | Same. The outbox stays, and Kafka adds a second hop with its own delivery semantics |
| Group isolation | RLS `WITH CHECK` on outbox + relay GroupMismatch check | Lost unless SASL/ACLs are added. PLAINTEXT allows any local process to read or forge any group's events (N-47) |
| Deletion/privacy | Metadata only in PG, under PG deletion control | Second store of ids, link keys and deletion modes in broker segments and DLT headers, outside PG deletion control (N-49) |
| Memory (7.75 GiB VM) | 0 added. Limits sum ≈ 6.3 GiB; measured ≈ 3.6 GiB | +0.5–1 GiB RSS (ESTIMATE). Limits sum ≈ 7.05 GiB, so < 0.75 GiB uncommitted |
| Moving parts | 0 added | +1 container, 1 volume, ≥2 topics (+DLT), 2 consumer groups, producer/consumer config. Relay still required. ESTIMATE +300–600 LoC/config |
| Fixes the defects in §3? | No | **No.** A skipped outbox row is never produced to Kafka |
| Deadline impact (Nov 4) | None | 3–5 days (Peter, low confidence). Displaces the Telegram live run, wiring Muse into the product, and rehearsal |

## 3. Findings that matter more than the transport choice

| ID | Finding | Applies to | Severity |
|---|---|---|---|
| F1 / N-44 | **Cursor skips out-of-order commits.**<br>`WHERE id > last_id` (`PostgresOutboxRelay.java:89-90`) on an IDENTITY id (`V1__core.sql:310`) assigned at INSERT. Sequence:<br>1. T1 holds id 10 uncommitted.<br>2. T2 commits id 11.<br>3. The relay delivers 11 and sets `last_id=11`.<br>4. T1 commits, and 10 is never delivered.<br>If 10 is `message.deleted`, the text stays in Vespa/Neo4j. Scheduled reconcile is not wired (no main-code caller). `registerFromNow` has the same race. Confirmed independently by Tony, Natasha, Bruce and Shuri by reading the code; **not yet reproduced**. Reproduction script: `prototypes/kafka-spike/` | A **and** B | HIGH for the synthetic demo; **BLOCKER for real data** |
| F1c | Same class of bug in the in-memory `EventDispatcher`: `seq.incrementAndGet()` and `outbox.add` are separate steps (`EventDispatcher.java:66-67`). A concurrent drain can pass a seq before its envelope is appended | A (the app's actual path today) | HIGH |
| F2 | The running app has no durable outbox. A crash loses pending events, and the publish is not transactional with truth | A as deployed | HIGH (demo durability) |
| F3 | `accept()` (HTTP, 30 s timeout) runs inside the transaction holding the cursor lock (`PostgresOutboxRelay.java:76-102`). This means long transactions, a pinned connection, held-back vacuum, and whole-batch redelivery if the commit fails. Safe today only because projections are idempotent | A | MEDIUM |
| F4 | V1 `processed_at`/`attempts`/`outbox_pending` index and the "claim" comment describe a model V2/V3 never use (`dita_relay` has SELECT only) | A | LOW (docs/dead schema) |
| N-45 | Projections apply `message.deleted` without checking truth. A forged deletion inserted by `dita_app` for its own group irreversibly tombstones the projection | A; worse under B | MEDIUM |

## 4. Decision (proposed)

**Keep A. Do not adopt Kafka before the Nov 4 hackathon. Fix F1, F1c and F2 first.** This must happen whatever is decided about Kafka later.

Justification:
1. **Kafka fixes none of the defects found** (F1–F4, N-45). The relay into Kafka copies the same cursor.
2. **It adds no capability the demo needs.**
   - Payloads are ids-only and projections re-read truth. Replay and rebuild already come from Postgres.
   - A's per-consumer cursors already give independent consumers.
   - Chat-scale load (tens to hundreds of events) is nowhere near A's limits.
3. **It adds privacy and security regressions** (N-47 to N-50), the opposite of Dita's strongest demo claim, verifiable deletion. Every one would need mitigating: SASL/ACLs, manual offset commits, block-on-deletion, short retention, no compaction, and DLT header stripping.
4. **Memory:** it pushes the limit sum to ≈ 7.05 / 7.75 GiB on one laptop that also runs Vespa (4 GiB).
5. **Delivery:** it costs 3–5 of the 7 remaining days and displaces judge-visible work.
6. **No lock-in.** DEC-004's `EventBus` port remains the extension point, so this is reversible.

## 5. When to revisit (triggers for B)

Adopt Kafka only when at least one of these holds **and** Bruce's decision rule (§7) passes:
- Consumers are deployed as separate services or owned by other teams, outside the DB trust boundary.
- There is more than one app instance, and relay lag exceeds an agreed SLO even after per-group parallel relays.
- A real need appears for event-stream retention or stream processing that truth tables cannot serve.
- Sustained throughput above 1k events/s that A demonstrably cannot handle.
- There is more than one host and an operator able to run a broker (SASL/ACL, monitoring).

## 6. Security and privacy register (Natasha, renumbered N-44 to N-51 to avoid colliding with the Muse review's N-34 to N-43)

| ID | Was | Finding | A/B | Severity |
|---|---|---|---|---|
| N-44 | (N-34) | Cursor skips out-of-order commits, including deletions (= F1). Mitigations:<br>(a) advisory lock or xmin-horizon read<br>(b) scheduled reconcile<br>(c) deletion sweep on `message.deleted_at > checkpoint`<br>(d) concurrency test | both | HIGH demo / BLOCKER real data |
| N-45 | (N-35) | Deletion events applied without a truth check | both (HIGH under B) | MEDIUM |
| N-46 | (N-36) | Replay and out-of-order delivery are safe today: ids-only, re-read truth, no un-delete. Add an ids-only contract test against "fat events" | both | LOW |
| N-47 | (N-37) | PLAINTEXT broker bypasses the RLS and GroupMismatch isolation. Needs loopback-only, SASL/SCRAM, per-principal ACLs | B | HIGH |
| N-48 | (N-38) | Kafka/Spring default offset handling skips deletions. Needs manual commit, block-on-deletion, `auto.offset.reset=none`, rebuild from PG only | B | HIGH |
| N-49 | (N-39) | Broker segments and DLT hold metadata outside PG deletion control (7-day default retention; DLT copies exception text). For A: the outbox is never pruned, and `outbox_dead_letter` FK blocks pruning | B (LOW part: A) | MEDIUM |
| N-50 | (N-40) | Compaction and tombstone semantics would quietly make Kafka a source of truth. Do not compact | B | MEDIUM |
| N-51 | (N-41) | Memory pressure → OOM → projections down → deletions blocked. The broker image needs digest pinning | B | LOW |

**Release gating (Natasha):** a synthetic-only demo is **not blocked** by these findings, provided Muse gating holds as recorded. Any real-data release is **blocked by N-44** until mitigation (a) plus a reconcile or deletion sweep are in place. Adopting B is blocked by N-47 and N-48, together with N-44 and N-45. The earlier N-1 to N-4 statuses are unchanged by this review.

## 7. Evaluation plan (Bruce). Status: PLAN, no measurements taken

- **Setup.** B = same outbox → relay → topic `dita.outbox`, 1 partition, key = group_id, `acks=all`, idempotent producer; offsets committed after the projection write. Five runs per tier; report the median plus min/max. Synthetic ids-only envelopes of about 200 B from `eval/` fixtures.
- **Metrics:**
  - M1 E2E latency p50/p95/p99, commit → visible in both projections
  - M2 throughput
  - M3 deletion propagation
  - M4 recovery time to lag 0
  - M5 rebuild time (N = 500 / 10k / 100k)
  - M6 RSS per container, idle and peak, plus headroom against 7.75 GiB and the `mem_limit` sum
  - M7 cold start
  - M8 added LoC and moving parts
  - M9 failure modes needing an operator
- **Tiers:** demo (500 events, 3 groups, 5% deletions, 20 ev/s), stress (100k, closed loop), burst (5k in 1 s after 60 s idle).
- **Failure injections (pass criteria):**
  - F1 Vespa down 60 s: Neo4j stays current, zero lost or duplicate docs, recovery < 30 s
  - F2 Neo4j down: the mirror of F1
  - F3 relay `kill -9` mid-batch: nothing skipped, redelivery allowed
  - F4 broker `kill -9` (B): no acked event lost
  - F5 Postgres restart: no gaps
  - F6 poison message: non-deletion is dead-lettered; deletion **blocks**
  - F7 out-of-order commit: event k is eventually visible. **Expected to FAIL today on A and B**
  - F8 replay after delete: text never reappears
  - F9 duplicate delivery: projection hash unchanged
  - After every run, the final projection hash must equal a rebuild from truth.
- **A priori ESTIMATES (not results):**
  - Broker RSS 350–500 MiB idle, 600–800 MiB peak with `-Xmx512m`
  - B adds 5–30 ms to p50
  - Both are projection-bound in throughput
  - B adds 10–25 s to cold start and 2–3 operator-action failure modes
- **Decision rule:** adopt B for the hackathon only if **all** of these hold:
  - zero correctness regressions in F1–F9
  - peak headroom ≥ 1.0 GiB **and** `mem_limit` sum ≤ 7.0 GiB
  - demo p95 ≤ 1 s and p99 ≤ 2 s
  - cold start ≤ 120 s
  - M9(B) − M9(A) ≤ 1
  - a demo-visible need A cannot meet (≥ 2× M2 or M5, or a consumer A cannot serve)

  Otherwise keep A.

## 8. Engineering detail (Shuri)

Shuri's review is complete. Her full write-up with diffs is in `prototypes/kafka-spike/proposed-relay-fix.md`. Nothing was run, because the sandbox has no Docker or localhost access and a scratch `initdb` failed on shmget.

### Transactions and delivery
- **Same-transaction write: yes.** `PostgresEventPublisher.publish` refuses to run outside `atomically` (`PostgresEventPublisher.java:43-45`) and joins the store's transaction on the same DataSource (`PostgresMessageStore.java:95-103`). Callers publish inside the unit of work (`IngestionPipeline.java:30-37`, `DeletionService.java:119`).
  - Weak point: the guard checks a thread-bound group marker, not that both use the same DataSource. Outbox RLS (V3) would probably reject a mis-wired insert.
- **F1 confirmed by reading:**
  - Nothing signals the loss: no dead letter, no block, and `lag()` can show 0.
  - `created_at` is the transaction *start* time, so a settle delay based on it is not a fix.
- **Delivery:** at-least-once per consumer.
  - A crash redelivers up to `batchSize` (100).
  - Backoff is exponential from 1 s to 60 s, with no jitter.
  - One undecodable deletion blocks that projection forever, by design (N-20).
  - The Postgres dead-letter table has **no redrive**: `redriven_at` is never used.
- **Idempotency: adequate.** Neo4j MERGEs from truth and never un-deletes. Vespa PUTs or DELETEs and accepts 404. Both tolerate reordering.

### Fix options for F1 (not applied)

| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| **A: recommended by Shuri** | V4 adds `txid xid8 DEFAULT pg_current_xact_id()`. The cursor becomes `(txid, id)`, and the relay reads only `txid < pg_snapshot_xmin(...)`, taken before it locks | No writer serialization. Also fixes `registerFromNow` and `lag`. Column-level INSERT grant stops `dita_app` forging `txid` | A migration. Latency (never loss) while any long transaction is open |
| **B: smallest diff** | `pg_advisory_xact_lock(const)` in `publish()` | Two lines, no migration | Serializes all outbox writers until commit. Every future writer must take the lock |
| C: delivered-row table | Per-consumer dedupe | — | **Unsafe alone**: a gap can't be told apart from in-flight work |

Fury's assessment: **B is the right hackathon fix** at chat-scale write rates, and **A is the right long-term fix**. The decision is held for human approval (Q2).

### F1c disagreement, resolved by Fury
- Shuri said the in-memory `EventDispatcher` is not affected. Tony said it is.
- Fury read `EventDispatcher.java:64-68` and 90-94. `seq.incrementAndGet()` and `outbox.add(...)` are separate, unsynchronized steps.
  1. Publisher P1 takes seq k.
  2. P2 takes k+1 and appends.
  3. A drain delivers k+1 and moves the cursor.
  4. P1 appends k, which then stays below the cursor (and is pruned).
- **Ruling: the race exists when publishes run concurrently** (Tony is right). Its severity depends on whether ingestion and `/forget` publish from more than one thread. Not verified.
- Fix: make publish atomic under the outbox monitor.

### Kafka mapping (only if adopted)
- **Topic:** one `dita.outbox.v1`, key = group_id. The event type goes in a header. Payloads are ids-only. `cleanup.policy=delete`.
- **Producer:** idempotent, `acks=all`, in-flight ≤ 5. The cursor advances only after the ack. **The publisher still needs fix A or B, or CDC (Debezium).**
- **Consumers:**
  - one consumer group per projection; auto-commit off; offsets committed after `accept()` returns
  - retry by pause plus seek on the partition; non-deletions go to a DLT after max attempts
  - a deletion is never sent to the DLT or committed past
  - an out-of-range offset triggers `reconcile()`, never a jump to latest
- **Reusable as is:** `EventDispatcher.Consumer`, `Envelope`, `ProjectionListener.consumer`, `decode`/`groupOf`, `mustDeliver`, `DispatchSettings` backoff.
- **Not reusable:** cursor tables, `lag()`, prune, redrive, the thread pool.

### Runnable artifacts (NOT RUN; `prototypes/kafka-spike/`, outside the Maven reactor)
- **`bench-outbox.sh` + `bench-outbox.sql`:**
  - runs in a throwaway database `dita_bench`, marker-guarded and dropped at the end; it never connects to `dita`
  - measures drain throughput for both cursor models and the cost of option B under concurrent writers
  - includes a **deterministic two-session reproduction of F1** against the current relay, option A and option B
- **`compose.kafka.yml` + `bench-kafka.sh`:**
  - separate compose project `dita-kafka-spike` with one KRaft broker (`apache/kafka:4.1.0`, tag **unverified**, pin the digest on first pull), `mem_limit 1g`, heap ≤ 512m, on `127.0.0.1:59092`
  - measures startup, idle and load memory, throughput and E2E latency, and runs a broker kill/restart loss check
  - teardown is `down -v` scoped to its own project and volume only
- `README.md`: the exact commands. Stop Neo4j and Vespa first: the spike needs about 1.5 GiB on top of the dita stack's 6.3 GiB of limits.
- `bash -n` passes for both scripts (checked by Shuri and Fury). The compose YAML parses (Shuri).

## 9. Migration plan (only if a §5 trigger fires; NOT approved, NOT started)

1. **Prerequisites:** F1/N-44 fixed and tested, F2 (Postgres path wired into the app), N-45 truth-checked deletions.
2. Add `kafka-producer` as **one more consumer** of the existing `PostgresOutboxRelay`. Its `accept` calls `send(...).get()`:
   - topic `dita.outbox`, key = group_id, value = the ids-only JSON, header `outbox_id`
   - `enable.idempotence=true`, `acks=all`, `max.in.flight ≤ 5`
   - the cursor advances only after the broker acks
3. Broker:
   - KRaft single node, digest-pinned
   - bound to 127.0.0.1; controller and JMX not published
   - SASL/SCRAM with a write-only relay principal and read-only per-consumer principals, plus ACLs
   - `cleanup.policy=delete`, retention in hours, no compaction
   - its volume is covered by `--reset` and the 21-day purge (C-9 storage list)
4. Consumers:
   - manual offset commit after the projection applies; `auto.offset.reset=none`
   - dedupe on `outbox_id`; re-read truth and re-check group
   - `message.deleted` → pause the partition and alert, never skip
   - non-deletion failures → DLT with exception headers stripped
5. Run A and B side by side on the same outbox, using separate consumer names (`neo4j` vs `neo4j-kafka`). Compare projection hashes through Bruce's F1–F9.
6. Cut over behind an `EventBus` config flag. Keep A as the fallback for one release. Rebuild always comes from Postgres, never from Kafka.
7. Natasha re-review before cut-over (N-47 to N-50 closed).

## 10. Risks and unresolved questions

- **R1:** F1/N-44 is inferred from PG semantics, not reproduced. The reproduction is written for the user to run (§8).
- **R2:** All Kafka/Spring default behaviours cited (auto-commit, DefaultErrorHandler, retention 7 d) come from reviewer memory and have not been doc-verified in this sandbox (no web access).
- **R3:** Every memory and latency figure for B is an ESTIMATE. Docker is not reachable from the sandbox, so nothing was measured.
- **R4:** The apache/kafka image tag is unverified and must be digest-pinned on first pull (N-32 practice).
- **Q1 (human):** acknowledge DEC-020 "keep A, defer Kafka", or ask for the benchmark first.
- **Q2 (human):** approve the F1 fix to the existing relay (it changes working infrastructure, so it is held per your rule). Options are in §8.
- **Q3 (PO/Peter):** is wiring the Postgres path into `dita-app` (F2) in scope before Nov 4? Recommended, because without it the demo app is not durable.
