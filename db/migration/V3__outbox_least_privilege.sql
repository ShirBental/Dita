-- V3 (Natasha N-27): least privilege for the outbox and relay tables. Run as the schema owner with
--   psql -v relay_password=... -v dbname=dita -f V3__outbox_least_privilege.sql
-- dita_app: may INSERT/SELECT outbox rows ONLY for the group bound to its transaction (app.group_id); no UPDATE/DELETE;
--           no access to relay cursors or dead letters.
-- dita_relay: separate role for the relay worker; reads every outbox row (ids only), owns cursor/dead-letter state.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dita_relay') THEN
    CREATE ROLE dita_relay LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
END $$;
ALTER ROLE dita_relay PASSWORD :'relay_password';
GRANT CONNECT ON DATABASE :"dbname" TO dita_relay;
GRANT USAGE ON SCHEMA public TO dita_relay;

REVOKE ALL ON outbox, outbox_consumer, outbox_dead_letter FROM dita_app;
GRANT SELECT, INSERT ON outbox TO dita_app;
GRANT SELECT ON outbox TO dita_relay;
GRANT SELECT, INSERT, UPDATE ON outbox_consumer TO dita_relay;
GRANT SELECT, INSERT ON outbox_dead_letter TO dita_relay;

ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_app_own_group ON outbox TO dita_app
  USING (group_id = current_setting('app.group_id', true))
  WITH CHECK (group_id = current_setting('app.group_id', true));
CREATE POLICY outbox_relay_read_all ON outbox FOR SELECT TO dita_relay USING (true);
