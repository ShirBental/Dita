#!/usr/bin/env bash
# First end-to-end slice on the running "dita" stack (postgres + neo4j + vespa with vespa/app deployed).
# Applies V2 (relay cursors) and V3 (least privilege, dita_relay role) if missing, as the schema owner inside the
# container, then runs ALL modules' tests with every gated integration test enabled. Restarts nothing.
# N-29: aborts unless every service maps to 127.0.0.1 on its Dita port. Log: build/infra-report/e2e.log
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/infra-report; mkdir -p "$OUT"
exec > >(tee "$OUT/e2e.log") 2>&1
DC=(docker compose -p dita -f docker/compose.yml)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OWNER=${DITA_DB_OWNER:-dita}; DB=${DITA_DB_NAME:-dita}
RELAY_PW=${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}
die() { echo "=== FAIL: $1"; echo "ABORTING (nothing was written)"; exit 1; }
psql_owner() { "${DC[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "$OWNER" -d "$DB" "$@"; }
mapped() {   # $1 service, $2 container port, $3 expected host port → prints 127.0.0.1:port or aborts
  local m; m=$("${DC[@]}" port "$1" "$2" 2>/dev/null)
  [[ "$m" == "127.0.0.1:$3" ]] || die "$1:$2 maps to '${m:-nothing}', expected 127.0.0.1:$3"
  echo "$m"
}

echo "## services"; "${DC[@]}" ps
PG=$(mapped postgres 5432 "${DITA_DB_PORT:-55432}") || exit 1
NEO=$(mapped neo4j 7474 "${DITA_NEO4J_HTTP_PORT:-57474}") || exit 1
VESPA=$(mapped vespa 8080 "${DITA_VESPA_QUERY_PORT:-58080}") || exit 1
curl -fsS -o /dev/null "http://$VESPA/ApplicationStatus" || die "vespa application not deployed (run scripts/vespa-verify.sh first)"
echo "postgres=$PG neo4j=$NEO vespa=$VESPA"

echo "## migrations"
if [[ "$(psql_owner -Atc "select to_regclass('public.outbox_consumer') is not null")" == t ]]; then echo "V2 already applied"
else psql_owner -1 -f /migrations/V2__outbox_relay.sql && echo "V2 applied" || die "V2"; fi
if [[ "$(psql_owner -Atc "select exists(select 1 from pg_policies where policyname='outbox_app_own_group')")" == t ]]; then echo "V3 already applied"
else psql_owner -1 -v relay_password="$RELAY_PW" -v dbname="$DB" -f /migrations/V3__outbox_least_privilege.sql && echo "V3 applied" || die "V3"; fi
psql_owner -Atc "select rolname||' super='||rolsuper||' bypassrls='||rolbypassrls from pg_roles where rolname in ('dita_app','dita_relay') order by 1"
psql_owner -Atc "select 'dita_app outbox privileges: '||string_agg(privilege_type, ',' order by privilege_type) from information_schema.role_table_grants where grantee='dita_app' and table_name='outbox'"

echo "## tests (all gated integration tests enabled)"
DITA_TEST_DB_URL="jdbc:postgresql://$PG/$DB" DITA_TEST_DB_USER=dita_app DITA_TEST_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" \
DITA_TEST_RELAY_PASSWORD="$RELAY_PW" \
DITA_TEST_NEO4J_URL="http://$NEO" DITA_TEST_NEO4J_PASSWORD="${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}" \
DITA_TEST_VESPA_URL="http://$VESPA" \
  mvn -o test > "$OUT/mvn-e2e.log" 2>&1
rc=$?
grep -E 'Tests run:|FAIL|ERROR\]|Reactor Summary|SUCCESS \[|FAILURE \[' "$OUT/mvn-e2e.log" | head -60
mkdir -p "$OUT/surefire-e2e" && cp -R dita-projections/target/surefire-reports/. "$OUT/surefire-e2e/" 2>/dev/null || true
# No false positives: the build must pass AND every gated suite must have actually executed with zero skips.
fails=0
req() {   # $1 test class, $2 expected count
  if grep -qE "Tests run: $2, Failures: 0, Errors: 0, Skipped: 0, .* in .*\.$1\$" "$OUT/mvn-e2e.log"; then echo "=== PASS: $1 ran $2/$2, 0 skipped"
  else echo "=== FAIL: $1 did not run $2 tests with 0 failures/skips"; grep -E "in .*\.$1\$" "$OUT/mvn-e2e.log"; fails=$((fails+1)); fi
}
req PostgresMessageStoreContractTest 24
req PostgresRlsIsolationTest 1
req EndToEndSliceTest 6
req VespaResponseTest 5
# Per-class skips, excluding the two approval-gated live Muse tests (scripts/muse-live.sh).
skipped=$(grep -E 'Skipped: [1-9][0-9]*, .* in app\.dita\.' "$OUT/mvn-e2e.log" | grep -vE 'Muse(Connectivity|Extraction)LiveTest' || true)
[[ -z "$skipped" ]] && echo "=== PASS: no skipped tests (MuseLiveTest excluded: approval-gated)" || { echo "=== FAIL: skipped tests present:"; echo "$skipped"; fails=$((fails+1)); }
(( rc == 0 && fails == 0 )) && echo "=== PASS: full build with all integration tests executed" || { echo "=== FAIL: rc=$rc, failed checks=$fails (see mvn-e2e.log)"; exit 1; }
