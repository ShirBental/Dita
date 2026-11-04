#!/usr/bin/env bash
# Dita local infrastructure verification (steps 3–7). Touches ONLY the "dita" compose project defined in
# docker/compose.yml: no other containers, images, networks or volumes are modified.
# Synthetic data only. Writes a full log to build/infra-report/run.log and surefire reports next to it.
#
# Usage:  scripts/infra-verify.sh [--only postgres|neo4j|vespa|tests]
#         scripts/infra-verify.sh --reset-dita-volumes   # deletes ONLY the dita_* dev volumes (synthetic data), then exits
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
OUT=$ROOT/build/infra-report
mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1

ONLY="${2:-all}"; [[ "${1:-}" == "--only" ]] || ONLY=all
DC=(docker compose -p dita -f docker/compose.yml)
if [[ "${1:-}" == "--reset-dita-volumes" ]]; then
  read -r -p "Delete the dita project's containers and dita_* volumes (synthetic dev data only)? Type 'dita' to confirm: " c
  [[ "$c" == dita ]] || { echo "cancelled"; exit 1; }
  "${DC[@]}" down -v && echo "dita project containers and dita_* volumes removed"; exit $?
fi
PG_PORT=${DITA_DB_PORT:-55432}; NEO_HTTP=${DITA_NEO4J_HTTP_PORT:-57474}
VQ=${DITA_VESPA_QUERY_PORT:-58080}; VC=${DITA_VESPA_CONFIG_PORT:-59071}

port_free() {   # $1 port, $2 service: fail fast if something OTHER than our container holds the port
  local holder
  holder=$(lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1}' | sort -u | tr '\n' ' ')
  if [[ -n "$holder" ]] && ! "${DC[@]}" ps --status running -q "$2" | grep -q .; then
    echo "host port $1 is already used by: $holder. Not touching it. Set a different port (e.g. DITA_DB_PORT) and re-run."
    return 1
  fi
}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OWNER=${DITA_DB_OWNER:-dita}; DB=${DITA_DB_NAME:-dita}
APP_PW=${DITA_APP_PASSWORD:-dita-app-dev-not-secret}
NEO_PW=${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}
RESULTS=()
ok()   { RESULTS+=("PASS  $1"); echo "=== PASS: $1"; }
bad()  { RESULTS+=("FAIL  $1"); echo "=== FAIL: $1"; }
step() { echo; echo "################ $1 ($(date -u +%H:%M:%SZ))"; }

wait_healthy() {   # $1 service, $2 max seconds
  local id s t=0
  id=$("${DC[@]}" ps -q "$1")
  while (( t < $2 )); do
    s=$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)
    [[ "$s" == healthy ]] && { echo "$1 healthy after ${t}s"; return 0; }
    sleep 5; t=$((t+5))
  done
  echo "$1 not healthy after $2s (last: $s)"; "${DC[@]}" logs --tail 80 "$1"; return 1
}

psql_owner() { "${DC[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "$OWNER" -d "$DB" "$@"; }

step "environment"
uname -m; docker version --format 'engine {{.Server.Version}} {{.Server.Arch}}'; docker compose version
"${DC[@]}" config --quiet && ok "compose file valid" || bad "compose file valid"

if [[ $ONLY == all || $ONLY == postgres || $ONLY == tests ]]; then
  step "3. postgres up"
  if ! { port_free "$PG_PORT" postgres && "${DC[@]}" up -d postgres && wait_healthy postgres 120; }; then
    bad "postgres healthy"; echo "stopping: no further postgres steps without a healthy Dita container"; ONLY=skip-postgres
  else
    ok "postgres healthy"
  fi
fi
if [[ $ONLY == all || $ONLY == postgres || $ONLY == tests ]]; then
  docker image inspect "$("${DC[@]}" images -q postgres | head -1)" --format 'image arch: {{.Architecture}}' || true
  MAPPED=$("${DC[@]}" port postgres 5432)                           # the port that really leads to OUR container
  echo "dita postgres reachable at $MAPPED"

  step "4a. schema"
  if [[ "$(psql_owner -Atc "select to_regclass('public.message') is not null")" == t ]]; then
    echo "schema already present: not re-applied (use 'docker compose -p dita -f docker/compose.yml down -v' to reset DITA volumes only)"
  else
    psql_owner -1 -f /migrations/V1__core.sql && ok "V1__core.sql applied" || bad "V1__core.sql applied"
  fi
  psql_owner -c '\dt'
  ROLE=$(psql_owner -Atc "select 'super='||rolsuper||' bypassrls='||rolbypassrls from pg_roles where rolname='dita_app'")
  echo "dita_app: ${ROLE:-MISSING}"
  if [[ "$ROLE" == "super=false bypassrls=false" ]]; then ok "dita_app role is non-superuser, no RLS bypass"
  else bad "dita_app role (init script did not run on this volume: re-run with --reset-dita-volumes)"; fi
  psql_owner -Atc "select count(*)||' tables with RLS forced' from pg_class where relrowsecurity and relforcerowsecurity"

  step "4b. integration tests (all postgres-gated tests)"
  DITA_TEST_DB_URL="jdbc:postgresql://${MAPPED}/$DB" DITA_TEST_DB_USER=dita_app DITA_TEST_DB_PASSWORD="$APP_PW" \
    mvn -o -pl dita-core,dita-store-postgres test > "$OUT/mvn-postgres.log" 2>&1
  rc=$?
  grep -E 'Tests run:|FAIL|ERROR\]' "$OUT/mvn-postgres.log" | grep -v '^\[INFO\] *$' | head -60
  rm -rf "$OUT/surefire" && cp -R dita-store-postgres/target/surefire-reports "$OUT/surefire" 2>/dev/null || true
  (( rc == 0 )) && ok "postgres integration tests (see mvn-postgres.log)" || bad "postgres integration tests rc=$rc (see mvn-postgres.log)"

  step "7a. postgres persistence across restart"
  psql_owner -c "insert into chat_group (id, platform, platform_chat_id, chat_type, source_kind) values ('infra-persist-check','synthetic','infra-persist-check','synthetic','SYNTHETIC') on conflict do nothing"
  "${DC[@]}" restart postgres && wait_healthy postgres 120
  [[ "$(psql_owner -Atc "select count(*) from chat_group where id='infra-persist-check'")" == 1 ]] && ok "postgres persistence" || bad "postgres persistence"
fi

if [[ $ONLY == all || $ONLY == neo4j ]]; then
  step "6a. neo4j up"
  port_free "$NEO_HTTP" neo4j && "${DC[@]}" up -d neo4j && wait_healthy neo4j 240 && ok "neo4j healthy" || bad "neo4j healthy"
  cy() { "${DC[@]}" exec -T neo4j cypher-shell -u neo4j -p "$NEO_PW" --format plain "$1"; }
  step "7b. neo4j connectivity + persistence"
  cy "CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition" && ok "neo4j bolt query" || bad "neo4j bolt query"
  cy "MERGE (n:InfraCheck {id:'persist'}) RETURN n.id"
  "${DC[@]}" restart neo4j && wait_healthy neo4j 240
  [[ "$(cy "MATCH (n:InfraCheck {id:'persist'}) RETURN count(n) AS c" | tail -1)" == 1 ]] && ok "neo4j persistence" || bad "neo4j persistence"
  curl -fsS -o /dev/null -w 'neo4j http %{http_code}\n' "http://127.0.0.1:${NEO_HTTP}/" && ok "neo4j http from host" || bad "neo4j http from host"
fi

if [[ $ONLY == all || $ONLY == vespa ]]; then
  step "6b. vespa up"
  if ! { port_free "$VQ" vespa && port_free "$VC" vespa && "${DC[@]}" up -d vespa && wait_healthy vespa 300; }; then
    bad "vespa config server healthy"; echo "N-29: skipping all vespa writes (use scripts/vespa-verify.sh)"
  else
  ok "vespa config server healthy"
  CFG="http://127.0.0.1:${VC}"; Q="http://127.0.0.1:${VQ}"
  step "6c. deploy application package (vespa/app)"
  (cd vespa/app && rm -f "$OUT/app.zip" && zip -qr "$OUT/app.zip" .)
  curl -fsS -H 'Content-Type: application/zip' --data-binary @"$OUT/app.zip" "$CFG/application/v2/tenant/default/prepareandactivate" \
    && echo && ok "vespa app deployed" || bad "vespa app deployed"
  t=0; until curl -fsS -o /dev/null "$Q/ApplicationStatus" || (( t > 240 )); do sleep 5; t=$((t+5)); done
  curl -fsS -o /dev/null -w 'vespa query port %{http_code}\n' "$Q/ApplicationStatus" && ok "vespa container up" || bad "vespa container up"
  step "7c. vespa feed + query + persistence"
  DOC="$Q/document/v1/dita/message/docid/infra-persist-check"
  curl -fsS -X POST -H 'Content-Type: application/json' "$DOC" \
    -d '{"fields":{"group_id":"infra-check","message_id":"infra-persist-check","text":"synthetic persistence check"}}' && echo && ok "vespa feed" || bad "vespa feed"
  sleep 2
  curl -fsS "$Q/search/?yql=select%20*%20from%20message%20where%20group_id%20contains%20%22infra-check%22%20and%20text%20contains%20%22persistence%22&ranking=bm25" \
    | grep -q infra-persist-check && ok "vespa query (group-filtered bm25)" || bad "vespa query"
  "${DC[@]}" restart vespa && wait_healthy vespa 300
  t=0; until curl -fsS -o /dev/null "$DOC" || (( t > 240 )); do sleep 5; t=$((t+5)); done
  curl -fsS "$DOC" | grep -q 'synthetic persistence check' && ok "vespa persistence" || bad "vespa persistence"
  fi
fi

step "memory"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}' $("${DC[@]}" ps -q) 2>/dev/null || true

step "SUMMARY"
printf '%s\n' "${RESULTS[@]}"
echo "full log: $OUT/run.log"
