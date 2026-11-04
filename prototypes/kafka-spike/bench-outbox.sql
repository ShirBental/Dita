-- Dita outbox spike fixture (prototype). Loaded by bench-outbox.sh into the THROWAWAY database dita_bench only.
-- Never run this against the dita database: it creates its own tables named like the real ones.
-- Synthetic, ids-only rows. Mirrors V1 outbox + V2 outbox_consumer, plus the proposed V4 column `txid xid8`
-- (see proposed-relay-fix.md) so both cursor models can be compared on the same rows.
\set ON_ERROR_STOP 1
SELECT current_database() = 'dita_bench' AS in_bench_db \gset
\if :in_bench_db
\else
  \echo 'refusing: bench-outbox.sql must run in database dita_bench'
  \quit
\endif

CREATE TABLE outbox (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_id   text NOT NULL,
  topic      text NOT NULL,
  payload    jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  txid       xid8 NOT NULL DEFAULT pg_current_xact_id()      -- proposed V4: top-level xid of the writing transaction
);
CREATE INDEX outbox_txid_id ON outbox (txid, id);

CREATE TABLE outbox_consumer (
  name       text PRIMARY KEY,
  last_id    bigint NOT NULL DEFAULT 0,
  last_txid  xid8   NOT NULL DEFAULT '0',
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Delivery log standing in for the projection side effect. No PK on purpose: duplicates must stay visible.
CREATE TABLE delivered (
  consumer     text NOT NULL,
  outbox_id    bigint NOT NULL,
  delivered_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX delivered_consumer ON delivered (consumer, outbox_id);

-- Current relay model (PostgresOutboxRelay.java:81-104): lock the cursor row, read WHERE id > last_id ORDER BY id,
-- "deliver", advance last_id per row. One call = one relay batch.
CREATE FUNCTION drain_id(p_consumer text, p_batch int DEFAULT 100) RETURNS int LANGUAGE plpgsql AS $$
DECLARE v_last bigint; r record; n int := 0;
BEGIN
  SELECT last_id INTO v_last FROM outbox_consumer WHERE name = p_consumer FOR UPDATE SKIP LOCKED;
  IF NOT FOUND THEN RETURN 0; END IF;
  FOR r IN SELECT id FROM outbox WHERE id > v_last ORDER BY id LIMIT p_batch LOOP
    INSERT INTO delivered (consumer, outbox_id) VALUES (p_consumer, r.id);
    UPDATE outbox_consumer SET last_id = r.id, updated_at = now() WHERE name = p_consumer;
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

-- Proposed model (proposed-relay-fix.md, option A): horizon = oldest transaction still in flight, taken BEFORE this
-- transaction locks anything (so before it has an xid of its own). Every row with txid < horizon is committed and
-- visible, and no new row with txid < horizon can ever appear, so a (txid, id) cursor below the horizon is gap-free.
CREATE FUNCTION drain_xid(p_consumer text, p_batch int DEFAULT 100) RETURNS int LANGUAGE plpgsql AS $$
DECLARE v_h xid8 := pg_snapshot_xmin(pg_current_snapshot()); v_tx xid8; v_last bigint; r record; n int := 0;
BEGIN
  SELECT last_txid, last_id INTO v_tx, v_last FROM outbox_consumer WHERE name = p_consumer FOR UPDATE SKIP LOCKED;
  IF NOT FOUND THEN RETURN 0; END IF;
  FOR r IN SELECT txid, id FROM outbox
           WHERE (txid, id) > (v_tx, v_last) AND txid < v_h
           ORDER BY txid, id LIMIT p_batch LOOP
    INSERT INTO delivered (consumer, outbox_id) VALUES (p_consumer, r.id);
    UPDATE outbox_consumer SET last_txid = r.txid, last_id = r.id, updated_at = now() WHERE name = p_consumer;
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

-- Drains a consumer to empty, committing after every batch like the relay does (one transaction per pass).
CREATE PROCEDURE drain_all(p_consumer text, p_mode text, p_batch int DEFAULT 100) LANGUAGE plpgsql AS $$
DECLARE n int; total bigint := 0; t0 timestamptz := clock_timestamp(); secs numeric;
BEGIN
  LOOP
    n := CASE p_mode WHEN 'id' THEN drain_id(p_consumer, p_batch) ELSE drain_xid(p_consumer, p_batch) END;
    total := total + n;
    COMMIT;
    EXIT WHEN n = 0;
  END LOOP;
  secs := greatest(extract(epoch FROM clock_timestamp() - t0), 0.000001);
  RAISE NOTICE 'DRAIN mode=% consumer=% batch=% rows=% seconds=% rows_per_s=%',
    p_mode, p_consumer, p_batch, total, round(secs, 3), round(total / secs);
END $$;

-- Live drainer: polls both cursor models side by side for p_seconds while publishers are inserting.
CREATE PROCEDURE drain_live(p_seconds int, p_batch int DEFAULT 100) LANGUAGE plpgsql AS $$
DECLARE t_end timestamptz := clock_timestamp() + make_interval(secs => p_seconds); n1 int; n2 int;
BEGIN
  WHILE clock_timestamp() < t_end LOOP
    n1 := drain_id('live-id', p_batch);   COMMIT;
    n2 := drain_xid('live-xid', p_batch); COMMIT;
    IF n1 = 0 AND n2 = 0 THEN PERFORM pg_sleep(0.005); END IF;
  END LOOP;
END $$;

-- Rows a consumer never received, and rows it received more than once.
CREATE VIEW delivery_audit AS
SELECT c.name AS consumer,
       (SELECT count(*) FROM outbox o
         WHERE NOT EXISTS (SELECT 1 FROM delivered d WHERE d.consumer = c.name AND d.outbox_id = o.id)) AS never_delivered,
       (SELECT count(*) FROM (SELECT outbox_id FROM delivered d WHERE d.consumer = c.name
                               GROUP BY outbox_id HAVING count(*) > 1) x)                         AS duplicated,
       (SELECT count(*) FROM delivered d WHERE d.consumer = c.name)                                AS deliveries
FROM outbox_consumer c;
