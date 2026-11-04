#!/bin/sh
# Runs once, on first start of an empty data volume (postgres image entrypoint).
# Creates the application role used by PostgresMessageStore and the gated tests. It must be NOSUPERUSER and
# NOBYPASSRLS, otherwise row-level security is silently bypassed. Development credentials only.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_password="${DITA_APP_PASSWORD:?DITA_APP_PASSWORD must be set}" -v owner="$POSTGRES_USER" -v dbname="$POSTGRES_DB" <<'SQL'
CREATE ROLE dita_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
GRANT CONNECT ON DATABASE :"dbname" TO dita_app;
GRANT USAGE ON SCHEMA public TO dita_app;
-- Tables created later by the owner (Flyway/psql migration) become usable by dita_app; DDL stays with the owner.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dita_app;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO dita_app;
SQL
