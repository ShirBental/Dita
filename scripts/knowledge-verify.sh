#!/usr/bin/env bash
# TASK-043: verify the evidence-grounded knowledge slice on the REAL Dita PostgreSQL (+ Neo4j + Vespa), incl. a restart.
#   scripts/knowledge-verify.sh           read-only checks → typed 'apply' → V5 (if missing) → gated knowledge tests
#   scripts/knowledge-verify.sh --check   read-only: identity and migration status only. Changes nothing.
# Prerequisite: scripts/relay-verify.sh has passed on this database (V2–V4 applied).
# Safety: only compose project "dita" (ps/port/exec/config, docker inspect); never up/down/restart/rm/pull, never
# volumes. Synthetic data only. Muse is exercised with a RECORDED response inside the tests (no network, no key).
# Database change: V5__knowledge_v2.sql only (additive; Shuri), and only after you type 'apply'.
# Exit: 0 PASS · 1 FAIL (tests) · 2 STOP, no DB change · 3 STOP after V5 was applied.
set -uo pipefail
cd "$(dirname "$0")/.."
MODE=full
case "${1:-}" in "") ;; --check) MODE=check ;; *) echo "usage: $0 [--check]"; exit 64 ;; esac
OUT=build/knowledge-verify; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
DC=(docker compose -p dita -f docker/compose.yml)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OWNER=${DITA_DB_OWNER:-dita}; DB=${DITA_DB_NAME:-dita}
APP_PW=${DITA_APP_PASSWORD:-dita-app-dev-not-secret}
RELAY_PW=${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}
NEO_PW=${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}
fails=0; CHANGED=()
pass() { echo "=== PASS: $1"; }
fail() { echo "=== FAIL: $1"; fails=$((fails+1)); }
stop() { echo "=== STOP: $1"; if (( ${#CHANGED[@]} )); then echo "Database changes made: ${CHANGED[*]}"; exit 3; else echo "No database changes were made."; exit 2; fi; }
psql_owner() { "${DC[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$OWNER" -d "$DB" "$@"; }
q() { psql_owner -Atc "$1" 2>>"$OUT/psql-errors.log"; }
date -u +"started %Y-%m-%dT%H:%M:%SZ (mode: $MODE)"

echo "## 1. Identity (Dita-owned services and database)"
for t in docker mvn shasum curl; do command -v "$t" >/dev/null || stop "required tool '$t' not found"; done
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21' || stop "JAVA_HOME=$JAVA_HOME is not Java 21"
for svc in postgres neo4j vespa; do
  cid=$("${DC[@]}" ps -q "$svc" 2>/dev/null); [[ -n "$cid" ]] || stop "service $svc of project 'dita' is not running"
  [[ "$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid")" == dita ]] || stop "$svc is not in project dita"
  [[ "$(docker inspect -f '{{ if .State.Health }}{{ .State.Health.Status }}{{ else }}none{{ end }}' "$cid")" == healthy ]] || stop "$svc is not healthy"
  [[ $svc == postgres ]] && PG_CID=$cid
done
mapped() { local m; m=$("${DC[@]}" port "$1" "$2" 2>/dev/null); [[ "$m" == "127.0.0.1:$3" ]] || { echo "=== STOP: $1:$2 maps to '${m:-nothing}'"; return 1; }; echo "$m"; }
PG=$(mapped postgres 5432 "${DITA_DB_PORT:-55432}") || stop "postgres port"
NEO=$(mapped neo4j 7474 "${DITA_NEO4J_HTTP_PORT:-57474}") || stop "neo4j port"
VESPA=$(mapped vespa 8080 "${DITA_VESPA_QUERY_PORT:-58080}") || stop "vespa port"
VOL=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$PG_CID")
[[ "$VOL" == dita_* ]] || stop "postgres data volume is '${VOL:-none}', expected dita_*"
IDENT=$(q "select current_database()||'|'||current_setting('server_version')||'|'||(select system_identifier from pg_control_system())")
IFS='|' read -r ID_DB ID_VER SYSID <<<"$IDENT"
[[ "$ID_DB" == "$DB" ]] || stop "cannot identify database '$DB'"
echo "    database=$ID_DB server=$ID_VER system_identifier=$SYSID volume=$VOL host=$PG neo4j=$NEO vespa=$VESPA"
pass "services and database belong to project dita"
kcode=$(curl -sS -o "$OUT/vespa-knowledge-probe.json" -w '%{http_code}' \
  "http://$VESPA/document/v1/dita/knowledge/docid?selection=knowledge.group_id%3D%3D%22__probe__%22&cluster=dita&wantedDocumentCount=1" 2>/dev/null)
[[ "$kcode" == 200 ]] || stop "Vespa has no 'knowledge' document type (HTTP ${kcode:-none}; see $OUT/vespa-knowledge-probe.json): deploy vespa/app first (needs approval)"
pass "Vespa knowledge document type deployed (read-only probe)"

echo "## 2. Migration status (read-only)"
for f in db/migration/V*.sql; do
  b=$(basename "$f")
  [[ "$(shasum -a 256 "$f" | cut -d' ' -f1)" == "$("${DC[@]}" exec -T postgres sha256sum "/migrations/$b" 2>/dev/null | cut -d' ' -f1)" ]] \
    || stop "/migrations/$b in the container differs from the repo file"
done
V2=$(q "select to_regclass('public.outbox_consumer') is not null")
V3=$(q "select exists(select 1 from pg_policies where policyname='outbox_app_own_group')")
V4=$(q "select exists(select 1 from pg_trigger where tgname='outbox_commit_order' and not tgisinternal)")
V5=$(q "select to_regclass('public.knowledge_relation') is not null")
[[ "$V2$V3$V4$V5" =~ ^[tf]{4}$ ]] || stop "could not read migration status"
for v in V2 V3 V4 V5; do echo "    $v: $([[ ${!v} == t ]] && echo applied || echo PENDING)"; done
[[ $V2$V3$V4 == ttt ]] || stop "V2–V4 must be applied first: run scripts/relay-verify.sh (and make it pass) before this script"
pass "migration status read (planned: $([[ $V5 == f ]] && echo 'apply V5' || echo 'none'))"
[[ $MODE == check ]] && { echo "=== CHECK ONLY: nothing was changed."; exit 0; }

echo "## 3. Offline compile gate (no database access)"
mvn -o -q -pl '!dita-eval' test-compile > "$OUT/mvn-compile.log" 2>&1 || stop "compilation failed (see $OUT/mvn-compile.log)"
pass "compiles"

echo "## 4. Confirmation"
[[ $V5 == f ]] && echo "This applies db/migration/V5__knowledge_v2.sql (additive: new columns, tables, RLS policies, grants) to '$DB' (system_identifier=$SYSID)."
echo "Tests insert SYNTHETIC rows (groups ks-*, messages, knowledge, batches, relay cursors). Nothing is deleted."
read -r -p "Type 'apply' to continue: " ans || ans=""
[[ "$ans" == apply ]] || stop "not confirmed"
if [[ $V5 == f ]]; then
  psql_owner -1 -f /migrations/V5__knowledge_v2.sql >/dev/null || stop "V5 failed (rolled back)"
  CHANGED+=("V5")
  [[ "$(q "select to_regclass('public.knowledge_relation') is not null")" == t ]] && pass "V5 applied" || fail "V5 not visible after apply"
fi

echo "## 5. Gated knowledge tests on the real services"
export DITA_TEST_DB_URL="jdbc:postgresql://$PG/$DB" DITA_TEST_DB_USER=dita_app DITA_TEST_DB_PASSWORD="$APP_PW" \
       DITA_TEST_RELAY_PASSWORD="$RELAY_PW" DITA_TEST_NEO4J_URL="http://$NEO" DITA_TEST_NEO4J_PASSWORD="$NEO_PW" DITA_TEST_VESPA_URL="http://$VESPA" \
       DITA_TEST_FAKE_TELEGRAM_TOKEN="123456789:$(printf 'A%.0s' {1..35})" \
       DITA_TEST_FAKE_CALLBACK_SECRET="$(printf 's%.0s' {1..40})"   # same synthetic values as relay-verify.sh (in-process fake Bot API)
unset MODEL_API_KEY
# The Vespa knowledge schema is deployed (checked in step 1), so the Neo4j/Vespa knowledge projection tests run too.
mvn -o -pl '!dita-eval' -fae test -Ddita.demonstrateDefect=true \
    > "$OUT/mvn-full.log" 2>&1   # V4 present: the 3 pre-fix demos must PASS
rc=$?
grep -E 'Tests run:.*in app\.dita|BUILD' "$OUT/mvn-full.log" | head -80
req() {   # $1 class: must have run >= 1 test with 0 failures, 0 errors, 0 skipped
  if grep -qE "Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0, .* in .*\.$1\$" "$OUT/mvn-full.log"; then pass "$1 executed, 0 skipped"
  else fail "$1 did not execute cleanly:"; grep -E "in .*\.$1\$" "$OUT/mvn-full.log" || echo "    (not run)"; fi
}
req KnowledgeSlicePostgresTest          # running app on POSTGRES: extraction → validation → persistence → answer → deletion → RESTART
req KnowledgeSliceInMemoryTest
req PostgresMessageStoreContractTest    # incl. knowledge v2 round trip, relations, answers (Shuri)
req PostgresExtractionQueueContractTest
req PostgresRlsIsolationTest
req ExtractionServiceTest
req PostgresRuntimeIntegrationTest      # booted app on POSTGRES with V5 present (ingest, /ask, /forget)
req VespaKnowledgeProjectionTest
req Neo4jKnowledgeProjectionTest
# A class whose @BeforeAll assumption aborts reports "Tests run: 0" (not "Skipped"), so check for that explicitly.
zero=$(grep -E 'Tests run: 0, .* in app\.dita\.' "$OUT/mvn-full.log" || true)
[[ -z "$zero" ]] && pass "no test class ran zero tests" || { fail "test classes ran zero tests (aborted by an assumption):"; echo "$zero"; }
skipped=$(grep -E 'Skipped: [1-9][0-9]*, .* in app\.dita\.' "$OUT/mvn-full.log" | grep -vE 'Muse(Connectivity|Extraction)LiveTest' || true)
[[ -z "$skipped" ]] && pass "no skipped tests (Muse live tests excluded)" || { fail "skipped tests present:"; echo "$skipped"; }
(( rc == 0 )) && pass "maven build" || fail "maven build rc=$rc (see $OUT/mvn-full.log)"
echo; echo "Database changes made by this run: ${CHANGED[*]:-none}"
if (( fails == 0 )); then echo "=== RESULT: PASS (all checks)"; exit 0; else echo "=== RESULT: FAIL ($fails failed checks) — see $OUT/run.log"; exit 1; fi
