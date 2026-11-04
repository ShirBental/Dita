-- V4 (ADR-001 F1/N-44, Natasha N-52): enforce outbox commit ordering IN THE DATABASE for every writer, not only for
-- PostgresEventPublisher. ADDITIVE ONLY: creates one function and one trigger; drops and rewrites nothing.
--
-- A statement-level BEFORE INSERT trigger fires before the INSERT evaluates its VALUES (and therefore before the
-- identity default draws outbox.id), and pg_advisory_xact_lock is held until the transaction ends. So no writer can
-- draw an outbox id until every earlier writer's transaction has committed (and become visible) or rolled back:
-- ids are allocated in commit order and the relay cursor (id > last_id) cannot skip a row.
-- Verified by AdvisoryLockSafetyTest.rawInsertIsOrderedByTheDatabaseTrigger (scripts/relay-verify.sh).
-- The key must equal PostgresEventPublisher.OUTBOX_ORDER_LOCK (0x4449544100000001). The Java lock is kept as well
-- (re-entrant; harmless) so ordering does not depend on this migration alone.
-- Run as the schema owner: psql -v ON_ERROR_STOP=1 -1 -f V4__outbox_commit_order.sql
CREATE OR REPLACE FUNCTION outbox_commit_order_lock() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(4920556706028584961);
  RETURN NULL;
END $$;

CREATE OR REPLACE TRIGGER outbox_commit_order
  BEFORE INSERT ON outbox
  FOR EACH STATEMENT EXECUTE FUNCTION outbox_commit_order_lock();
