# Proposed fix: outbox cursor can skip committed rows (PROPOSAL, NOT APPLIED)

Status: proposal only. Nothing under `dita-store-postgres/`, `db/migration/` or `dita-core/` was modified.
The diffs below are illustrative: not compiled, not run. Reproduction: `bench-outbox.sh` part 4 (NOT RUN).

## 1. The bug

`PostgresOutboxRelay.drainLocked` reads `SELECT ... FROM outbox WHERE id > ? ORDER BY id LIMIT ?`
(PostgresOutboxRelay.java:89-90). After each row it sets `last_id = id` (:103, also :99 and :113).
`outbox.id` is `bigint GENERATED ALWAYS AS IDENTITY` (V1__core.sql:310). The identity value is taken from the sequence
when the INSERT runs, not when the transaction commits. The relay runs at READ COMMITTED (the default; neither
`TransactionTemplate` sets an isolation level, PostgresOutboxRelay.java:40-41), so it cannot see rows that are not
yet committed.

Interleaving:

| t | Tx A (e.g. DeletionService) | Tx B (e.g. IngestionPipeline) | relay |
|---|---|---|---|
| 1 | INSERT outbox, gets id=41 (`message.deleted`) | | |
| 2 | ...still working... | INSERT outbox, gets id=42; COMMIT | |
| 3 | | | reads `id > 40`, sees only 42, delivers it, `last_id = 42`, COMMIT |
| 4 | COMMIT (row 41 now visible) | | |
| 5 | | | reads `id > 42`. **Row 41 is never delivered.** |

This is CONFIRMED by reading the code. It has not been reproduced on a live database yet.

- `publish` runs in the middle of the caller's transaction, and more work follows it before commit:
  IngestionPipeline.java:36 publishes the MessageRef, then :37 publishes the LinkRefs; DeletionService.java:119
  publishes last, but the commit still follows. Any transaction that runs long (lock wait, GC pause, slow inference
  inside `atomically`) widens the window.
- The registration and monitoring paths have the same race. `registerFromNow` seeds the cursor with `max(id)`
  (PostgresOutboxRelay.java:52), which skips every row that is in flight at that moment. `lag()` is computed as
  `max(id) - last_id` (:127), so it can report 0 while a row is lost.
- Impact: at-least-once becomes "at most once for rows that commit out of order". If the skipped row is a
  `message.deleted`, the N-20 promise ("a deletion is NEVER skipped", PostgresOutboxRelay.java:24-25,
  EventDispatcher.java:32-35) breaks without any signal. Vespa keeps the deleted message's text indexed
  (VespaProjection.java:56-59 is never called), and Neo4j keeps the node live. `reconcile()` would repair this, but
  nothing in production calls it (only EndToEndSliceTest.java:184-185). The dead-letter, blocked-on-deletion and
  lag signals cannot see a skipped row.
- The in-memory `EventDispatcher` does not have this bug. There `seq.incrementAndGet()` and `outbox.add` run at
  publish time (EventDispatcher.java:66-67), with no transaction visibility involved.

## 2. Fix options

### Option A (recommended): order by the writer's transaction id, and read only below the xmin horizon

- Add `txid xid8 DEFAULT pg_current_xact_id()` to `outbox` (PG 13+; the containers run 16.4). The cursor becomes the
  pair `(last_txid, last_id)`.
- On each pass, before the relay locks anything, read
  `horizon = pg_snapshot_xmin(pg_current_snapshot())`, the oldest transaction still in flight.
- Deliver only rows with `txid < horizon`, ordered by `(txid, id)`.

Why it is gap-free:
- Every transaction with xid < horizon has already finished, so all of its rows are visible now.
- No transaction can ever again write a row with txid < horizon.
- So once the cursor passes `(x, id)` with `x < horizon`, nothing can appear behind it.

Why the `id` cursor plus an xmin filter is not enough on its own: id order is not xid order. Suppose Tx R took
xid 50 early, then nextval 6, and committed. Tx S has xid 100, took nextval 5, and is still open. Then
`horizon = 100` and R passes the filter, so the cursor jumps to 6 and S is skipped anyway. The cursor itself must be
ordered by txid.

- Pros:
  - Writers are not serialized at all.
  - The relay change is about 15 lines.
  - Also fixes `registerFromNow` and `lag`.
  - The same `(txid, id)` cursor carries over to a Kafka publisher.
- Cons:
  - Needs a schema migration (V4).
  - Delivery order becomes xid order rather than id order. Neither is commit order. Every consumer re-reads truth
    (ProjectionListener + Neo4jProjection/VespaProjection), so a created/deleted pair delivered in swapped order
    still converges: `onMessage` re-reads truth, sees `deleted`, and deletes or marks deleted.
  - Latency: any long-running transaction anywhere in the cluster (the xid horizon is cluster-wide) holds the
    horizon back. Rows are delayed, never lost. This includes a relay instance holding its cursor lock (which
    assigns an xid) during slow HTTP calls. Monitor `now() - min(created_at)` of rows above the cursor.
  - `pg_current_snapshot()` must run on the primary.
  - The horizon is read in its own statement before the `FOR UPDATE`, so the relay's own xid can never hold the
    horizon back. (Uncertain whether the backend's own xid counts toward its snapshot xmin; reading the horizon
    first avoids the question.)
- Hardening: `dita_app` could forge a low `txid` by supplying the column explicitly. V4 therefore replaces
  `GRANT INSERT ON outbox` (V3:16) with a column-level grant. `id` is already `GENERATED ALWAYS`.

### Option B (smallest diff): serialize outbox writers with a transaction-scoped advisory lock

`PostgresEventPublisher.publish` takes `pg_advisory_xact_lock(K)` before its INSERT. The lock is held until commit
or rollback, so the next writer can only take nextval after the previous outbox writer has finished. Ids are then
issued in commit order, and the existing `id > last_id` cursor becomes correct unchanged. Repeated `publish` calls in
one transaction re-acquire the lock; this is re-entrant and stacks.

- Pros:
  - Two lines, no migration, no relay change.
  - Easy to reason about.
- Cons:
  - Globally serializes every writing transaction (all groups) from its first publish until commit. Throughput is
    capped at about 1 / (remaining in-transaction work + commit fsync).
  - Measure with `bench-outbox.sh` 1b ("plain" vs "lock").
  - A slow transaction stalls all ingestion and deletions.
  - Deadlock risk if a future caller takes row locks after publishing while another writer holds those rows and
    waits for K. Postgres detects the cycle and aborts one side, but that is a new failure mode. Today's callers
    publish only at the end (IngestionPipeline.java:36-37, DeletionService.java:119).
  - Must be enforced for every writer. A manual `INSERT INTO outbox` without the lock silently reopens the bug.
    A trigger could take the lock, but that is still per transaction.

### Option C: per-consumer delivered-row table (gap tracking)

Keep a low watermark plus `outbox_delivery(consumer, outbox_id)` rows for everything delivered above it. Read
`id > low AND NOT EXISTS delivered`, and advance `low` across contiguous ids.

- Pros: no writer-side change.
- Cons:
  - Identity gaps are normal (rollbacks, and up to 32 ids lost after a crash because of sequence WAL pre-logging).
    The relay can never prove that a gap is a rollback rather than in-flight work without the xid information from
    option A, or a timeout heuristic. "Assume a gap older than T is dead" is exactly the kind of silent loss N-20
    forbids.
  - One extra row per delivery per consumer, plus pruning.
- Not recommended unless combined with A.

### Rejected

- A "settle delay" (`created_at < now() - 5s`): `created_at DEFAULT now()` is the transaction START time, so a long
  transaction's row looks old the moment it commits. The delay does not help.
- `SERIALIZABLE` for the relay: it does not make uncommitted rows visible.
- `processed_at` claiming (V1__core.sql:320-321 comment): this is a work-queue model with a single consumer.
  `dita_relay` has no UPDATE on outbox (V3:17), and it does not give per-consumer cursors.
- Logical decoding / CDC (Debezium outbox router): commit-ordered by construction and the canonical Kafka path. It
  needs `wal_level=logical`, a replication slot (disk can fill if the slot stalls), and an extra service. It is the
  right long-term answer if Kafka is adopted (section 5), but it is not minimal.

## 3. Diff for option A (illustrative, not compiled)

`db/migration/V4__outbox_commit_horizon.sql` (new):

```sql
-- V4: gap-free outbox cursor. Rows are ordered by the top-level xid of the writing transaction and read only below
-- the xmin horizon (oldest in-flight xid), so a row that commits late can never fall behind a consumer's cursor.
-- Takes ACCESS EXCLUSIVE on outbox and rewrites it (volatile default): run in a quiet window.
ALTER TABLE outbox ADD COLUMN txid xid8 NOT NULL DEFAULT pg_current_xact_id();   -- existing rows: this migration's xid
CREATE INDEX outbox_txid_id ON outbox (txid, id);
ALTER TABLE outbox_consumer ADD COLUMN last_txid xid8 NOT NULL DEFAULT '0';
-- Existing rows all carry this migration's xid, so (migration xid, last_id) keeps every consumer's position exactly.
UPDATE outbox_consumer SET last_txid = pg_current_xact_id();
-- dita_app may not choose txid (or id): column-level INSERT only.
REVOKE INSERT ON outbox FROM dita_app;
GRANT INSERT (group_id, topic, payload) ON outbox TO dita_app;
-- One-off audit for rows already skipped before V4 (run per consumer; ids only):
--   SELECT o.id, o.topic FROM outbox o, outbox_consumer c
--   WHERE c.name = :'consumer' AND o.id <= c.last_id
--     AND NOT EXISTS (SELECT 1 FROM outbox_dead_letter d WHERE d.consumer = c.name AND d.outbox_id = o.id)
--   -- cannot distinguish "delivered" from "skipped" (deliveries are not logged): run reconcile() for every group instead.
```

`PostgresOutboxRelay.java`:

```diff
     public void register(EventDispatcher.Consumer c) {
-        jdbc.update("INSERT INTO outbox_consumer (name, last_id) VALUES (?, 0) ON CONFLICT (name) DO NOTHING", c.name());
+        jdbc.update("INSERT INTO outbox_consumer (name, last_txid, last_id) VALUES (?, '0', 0) ON CONFLICT (name) DO NOTHING", c.name());
         consumers.add(c);
     }

-    /** Registers a consumer that only sees events published after now (tests and fresh projections). */
+    /** Registers a consumer that only sees events from transactions still in flight or not yet started. */
     public void registerFromNow(EventDispatcher.Consumer c) {
-        jdbc.update("INSERT INTO outbox_consumer (name, last_id) VALUES (?, (SELECT coalesce(max(id), 0) FROM outbox)) "
-                + "ON CONFLICT (name) DO NOTHING", c.name());
+        jdbc.update("INSERT INTO outbox_consumer (name, last_txid, last_id) "
+                + "VALUES (?, pg_snapshot_xmin(pg_current_snapshot()), 0) ON CONFLICT (name) DO NOTHING", c.name());
         consumers.add(c);
     }
@@
     private int drainLocked(EventDispatcher.Consumer c) {
+        // Horizon FIRST, before FOR UPDATE gives this transaction an xid: every xid below it has finished, so every
+        // outbox row with txid < horizon is visible now and no new one can ever appear behind the cursor.
+        String horizon = jdbc.queryForObject("SELECT pg_snapshot_xmin(pg_current_snapshot())::text", String.class);
         List<Map<String, Object>> locked = jdbc.queryForList(
-                "SELECT last_id, attempts, retry_at FROM outbox_consumer WHERE name = ? FOR UPDATE SKIP LOCKED", c.name());
+                "SELECT last_txid::text AS last_txid, last_id, attempts, retry_at FROM outbox_consumer WHERE name = ? "
+                + "FOR UPDATE SKIP LOCKED", c.name());
         if (locked.isEmpty()) return 0;
         Map<String, Object> st = locked.get(0);
         ...
+        String lastTx = (String) st.get("last_txid");
         long last = ((Number) st.get("last_id")).longValue();
         int attempts = ((Number) st.get("attempts")).intValue();
         List<Map<String, Object>> rows = jdbc.queryForList(
-                "SELECT id, group_id, topic, payload::text AS payload FROM outbox WHERE id > ? ORDER BY id LIMIT ?", last, settings.batchSize());
+                "SELECT id, txid::text AS txid, group_id, topic, payload::text AS payload FROM outbox "
+                + "WHERE (txid, id) > (?::xid8, ?) AND txid < ?::xid8 ORDER BY txid, id LIMIT ?",
+                lastTx, last, horizon, settings.batchSize());
         int delivered = 0;
         for (Map<String, Object> r : rows) {
             long id = ((Number) r.get("id")).longValue();
+            String txid = (String) r.get("txid");
             ...
-                    jdbc.update("UPDATE outbox_consumer SET last_id = ?, attempts = 0, retry_at = NULL, updated_at = now() WHERE name = ?", id, c.name());
+                    jdbc.update("UPDATE outbox_consumer SET last_txid = ?::xid8, last_id = ?, attempts = 0, retry_at = NULL, "
+                            + "updated_at = now() WHERE name = ?", txid, id, c.name());
             (same change at the two other cursor advances: forged row :99, delivered :103, dead-lettered :113)
@@
     public long lag(String consumer) {
-        Long v = jdbc.queryForObject("SELECT coalesce((SELECT max(id) FROM outbox), 0) - last_id FROM outbox_consumer WHERE name = ?", Long.class, consumer);
+        Long v = jdbc.queryForObject("SELECT count(*) FROM outbox o JOIN outbox_consumer c ON c.name = ? "
+                + "WHERE (o.txid, o.id) > (c.last_txid, c.last_id)", Long.class, consumer);
         return v == null ? 0 : v;
     }
```

No change to `PostgresEventPublisher`; the column default fills `txid`. `Envelope.seq` stays the outbox `id`
(still unique). Consumers only need it for logging and deduplication, not for ordering.

## 4. Diff for option B (illustrative, not compiled)

```diff
 public final class PostgresEventPublisher implements EventPublisher {
+    /** "DITAOBOX". Serialises outbox writers until commit so identity ids are issued in commit order (cursor safety). */
+    static final long OUTBOX_WRITE_LOCK = 0x444954414F424F58L;   // 4920556707358330712
@@
         String json; ...
+        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", OUTBOX_WRITE_LOCK);   // held to commit/rollback; re-entrant
         Long id = jdbc.queryForObject("INSERT INTO outbox (group_id, topic, payload) VALUES (?, ?, ?::jsonb) RETURNING id",
```

`registerFromNow` and `lag` become correct as a side effect, because no lower id can commit after a higher one.

## 5. Tests to add with either fix (not written into the reactor)

- `PostgresOutboxRelayOrderingTest`, two JDBC connections:
  - conn1 `BEGIN; INSERT` (id n), left open;
  - conn2 `INSERT; COMMIT` (id n+1);
  - `relay.drainOnce()` delivers n+1 (option B: conn2 blocks, so drive conn2 on a thread) or nothing (option A);
  - conn1 `COMMIT`;
  - `relay.drainUntilIdle(5)`;
  - assert that the consumer saw both ids exactly once, and the `message.deleted` one in particular.
- Fuzz variant: 8 writer threads with random in-transaction sleeps and a concurrent drainer. Assert that the set of
  delivered ids equals the set of committed ids (`bench-outbox.sh` part 3 is the SQL version).
- `registerFromNow` with an open transaction holding an outbox row: the row must be delivered.

## 6. Kafka mapping (for the spike)

- **Topic:** one topic, `dita.outbox.v1`, for all four event types. The event type goes in a header (`dita-type`)
  and stays in the payload. Separate topics per type would lose the per-group cross-type ordering that
  link-after-message and deletion-after-create rely on.
- **Retention:** delete-based, not compacted. Compaction by group key would drop events.
- **Partitions:** start at 6-12. More can be added later, but that remaps keys, so only add them while drained.
- **Key and payload:** key = `group_id`, so all events of a group land in one partition, in order. The value is
  the same ids-only JSON as today: no text, ever (N-1/N-17/N-18).
  - Because the log holds ids only, a deletion never requires purging Kafka segments. Keep that property.
  - Headers: `outbox-id`, `outbox-txid`, `schema-version`.
- **Producer (relay to Kafka):** one publisher cursor (for example `kafka-publisher`) replaces the per-projection
  cursors. The `FOR UPDATE SKIP LOCKED` single-instance lock stays.
  - Settings: `enable.idempotence=true`, `acks=all`, `max.in.flight.requests.per.connection<=5` (ordering
    preserved across retries), `retries=MAX`, bounded `delivery.timeout.ms`.
  - Advance the cursor only after the send future is acked (or after `flush()` for a batch).
  - Idempotence deduplicates only within one producer session. A crash between ack and cursor commit republishes
    the batch, so the guarantee into Kafka is at-least-once.
  - A transactional producer (`transactional.id`) does not help here, because the cursor lives in Postgres and
    the two cannot commit atomically.
  - **The section 1 bug applies to the publisher exactly as it does to the current relay.** Kafka does not fix it;
    option A or B is still required. CDC is the alternative.
- **Consumers:** one consumer group per projection (`dita-neo4j`, `dita-vespa`); committed offsets replace
  `outbox_consumer` rows.
  - `enable.auto.commit=false`. Call `commitSync` for a record's offset only after `accept()` returns, which means
    after the projection is applied.
  - Use `isolation.level=read_committed` if a transactional producer is used.
  - `auto.offset.reset=earliest` for new groups.
  - Treat `OffsetOutOfRange` as "run `reconcile()` for every group", never "jump to latest".
- **Consumer idempotency:** already in place. Neo4j uses MERGE/SET from re-read truth and never un-deletes
  (Neo4jProjection.java:85-95). Vespa PUTs current truth or DELETEs and accepts 200/404 (VespaProjection.java:42-59).
  No dedupe table is needed; `outbox-id` in the header lets you log duplicates.
- **Retry:** retry in place, per partition. On failure, `pause()` the partition, `seek()` back to the failed offset
  and resume after `DispatchSettings.backoff(n)`.
  - This keeps the current "stop this consumer until retry is due" semantics (PostgresOutboxRelay.java:117-120),
    but blocks only one partition rather than the whole consumer.
  - Keep `max.poll.interval.ms` above the longest backoff, or keep polling while paused.
  - Do not use non-blocking retry topics for deletions: they reorder by design.
- **DLQ:** after `maxAttempts`, a non-deletion event goes to `dita.outbox.v1.dlq.<consumer>`. The DLQ record holds
  ids only, plus headers for the error class, original partition and offset. Then commit past it, mirroring
  `outbox_dead_letter`. Redrive means re-producing from the DLQ. The `redriven_at` column (V2:17) has no Postgres
  code yet either.
- **N-20 ("never skip a deletion") in Kafka terms:**
  1. A `message.deleted` record is never DLQ'd and its offset is never committed until it is applied. The partition
     stays paused or retrying, with `blocked_on_deletion` exported as a metric and an alert.
  2. The topic's `retention.ms` must exceed the longest tolerated block or outage, or the deletion ages out of the
     log unconsumed. Alert on the consumer-lag age well before retention.
  3. Production settings: `unclean.leader.election.enable=false`, RF=3, `min.insync.replicas=2`, so that an acked
     deletion survives one broker loss. The single-broker spike cannot show this.
  4. Offsets out of range trigger reconcile from truth.
  5. The producer side needs the section 1 fix (or CDC), or the deletion never reaches Kafka at all.
- **Reusable from `EventDispatcher`:**
  - The `Consumer` interface: `name()` maps to `group.id`, and `accept(Envelope)` becomes the per-record handler.
  - `Envelope(seq = outbox id, groupId = record key, payload)`.
  - `ProjectionListener.consumer(...)`.
  - `PostgresOutboxRelay.decode`/`groupOf`. The forged-group check moves to the consumer side: key == payload
    groupId.
  - The `mustDeliver` rule and `DispatchSettings` backoff/maxAttempts.
- **Not reusable:** the cursor tables, `lag()` (use consumer-group lag), the in-memory `prune`/`redrive`, and the
  thread pool (Kafka partitions give parallelism).
