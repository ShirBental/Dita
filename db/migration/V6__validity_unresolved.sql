-- V6 (DEC-038 / N-110 policy, TASK-047, Nick Fury): knowledge_item.validity may be 'UNRESOLVED'.
--
-- Product-owner policy (2026-11-01, docs/TASK-046-deleted-correction-policy.md): withdrawing a correction must not
-- make the claim it superseded current again. ValidityReconciler marks such a claim UNRESOLVED (history kept, not
-- current). V5 allows only ACTIVE/SUPERSEDED, so this replaces that one CHECK constraint.
--
-- UPGRADE PROVENANCE (TASK-047 round-2 F3): V5 used SUPERSEDED for BOTH relation-only retirement and a withdrawn
-- correction. That distinction cannot be reconstructed. Conservatively convert existing SUPERSEDED rows to UNRESOLVED
-- before V6 relation repair is enabled, including fan-in rows that still have live relations. Otherwise a new false
-- relation followed by rejection could revive a genuinely withdrawn V5 claim. Fresh V6 relation-only retirements remain
-- SUPERSEDED and repairable. ACTIVE and NULL rows, wording and evidence are unchanged. Old ambiguous retirements require
-- a new reconfirmation; this migration never invents provenance or reactivates historical knowledge.
-- Emits ids-only knowledge.changed events so existing projections follow the converted truth transactionally.
-- Until V6 is applied the application keeps such claims SUPERSEDED instead (still never current; see
-- PostgresMessageStore.hasV6 and ValidityReconciler.target), so V5 databases stay correct and deletions never fail.
--
-- REQUIRES USER APPROVAL BEFORE APPLYING anywhere but a throwaway test database. Not applied by any script.
-- V5 is never edited. Apply as the schema owner WITH BYPASSRLS (or superuser), in ONE transaction, after V1-V5.
-- FORCE RLS must not silently hide rows from the provenance conversion. This script never changes role permissions.
--   docker compose -p dita -f docker/compose.yml exec -T postgres \
--     psql -X -v ON_ERROR_STOP=1 -U "${DITA_DB_OWNER:-dita}" -d "${DITA_DB_NAME:-dita}" -1 -f /migrations/V6__validity_unresolved.sql
-- Applied check (read-only):
--   SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'knowledge_item'::regclass AND conname = 'knowledge_item_validity_v6');
-- Rollback (only while no row holds 'UNRESOLVED'; converting such rows to SUPERSEDED is a data change and needs
-- approval). The app re-probes per call, so no restart is needed afterwards (N-120 / T47-4):
--   ALTER TABLE knowledge_item DROP CONSTRAINT knowledge_item_validity_v6,
--     ADD CONSTRAINT knowledge_item_validity_check CHECK (validity IN ('ACTIVE','SUPERSEDED'));
-- Locks: lock_timeout bounds acquisition, not migration duration. The atomic transaction holds ACCESS EXCLUSIVE through
-- validation, conversion and outbox publication. Review duration/maintenance needs before any non-test application.

SET LOCAL lock_timeout = '5s';
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'V6 provenance preservation requires a schema owner with BYPASSRLS or superuser';
  END IF;
END $$;
ALTER TABLE knowledge_item
  DROP CONSTRAINT knowledge_item_validity_check,                     -- V5's inline column CHECK (default name)
  ADD CONSTRAINT knowledge_item_validity_v6 CHECK (validity IN ('ACTIVE','SUPERSEDED','UNRESOLVED')) NOT VALID;
ALTER TABLE knowledge_item VALIDATE CONSTRAINT knowledge_item_validity_v6;

WITH preserved AS (
  UPDATE knowledge_item SET validity = 'UNRESOLVED', updated_at = now()
  WHERE validity = 'SUPERSEDED'
  RETURNING group_id, id
)
INSERT INTO outbox (group_id, topic, payload)
SELECT group_id, 'knowledge.changed', jsonb_build_object('groupId', group_id, 'knowledgeId', id)
FROM preserved ORDER BY group_id, id;
