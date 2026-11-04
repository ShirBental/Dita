#!/usr/bin/env bash
# TASK-044: Dita Memory Capability Test on the REAL local stack (PostgreSQL + relay + Neo4j + Vespa).
# Replays the synthetic benchmark corpora (dev split, 35 questions) into fresh synthetic groups "cap-<run>-N" through the
# running application, asks every question through QueryService and scores the answers against the sealed dev gold.
# Writes eval/value-benchmark/results/capability-dev-postgres.{json,md}. No model call, no Telegram, no schema change.
# Safety: only compose project "dita" (ps/port/inspect, read-only psql); never up/down/restart/rm/volumes.
# Exit: 0 PASS (ran and scored) · 1 FAIL · 2 STOP (nothing written to the database).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/capability-verify; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
DC=(docker compose -p dita -f docker/compose.yml)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OWNER=${DITA_DB_OWNER:-dita}; DB=${DITA_DB_NAME:-dita}
stop() { echo "=== STOP: $1"; echo "No database changes were made."; exit 2; }
q() { "${DC[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$OWNER" -d "$DB" -Atc "$1" 2>>"$OUT/psql-errors.log"; }
date -u +"started %Y-%m-%dT%H:%M:%SZ"
for t in docker mvn curl python3; do command -v "$t" >/dev/null || stop "required tool '$t' not found"; done
for svc in postgres neo4j vespa; do
  cid=$("${DC[@]}" ps -q "$svc" 2>/dev/null); [[ -n "$cid" ]] || stop "service $svc of project 'dita' is not running"
  [[ "$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid")" == dita ]] || stop "$svc is not in project dita"
  [[ "$(docker inspect -f '{{ if .State.Health }}{{ .State.Health.Status }}{{ else }}none{{ end }}' "$cid")" == healthy ]] || stop "$svc is not healthy"
done
mapped() { local m; m=$("${DC[@]}" port "$1" "$2" 2>/dev/null); [[ "$m" == "127.0.0.1:$3" ]] || return 1; echo "$m"; }
PG=$(mapped postgres 5432 "${DITA_DB_PORT:-55432}") || stop "postgres port"
NEO=$(mapped neo4j 7474 "${DITA_NEO4J_HTTP_PORT:-57474}") || stop "neo4j port"
VESPA=$(mapped vespa 8080 "${DITA_VESPA_QUERY_PORT:-58080}") || stop "vespa port"
[[ "$(q "select current_database()")" == "$DB" ]] || stop "cannot identify database '$DB'"
[[ "$(q "select exists(select 1 from pg_trigger where tgname='outbox_commit_order' and not tgisinternal)")" == t ]] || stop "V4 not applied (run scripts/relay-verify.sh)"
[[ "$(q "select to_regclass('public.knowledge_relation') is not null")" == t ]] || stop "V5 not applied (run scripts/knowledge-verify.sh)"
echo "=== PASS: project dita services, database $DB, V4+V5 present; postgres=$PG neo4j=$NEO vespa=$VESPA"
# No interference (Natasha N-78) and no stray overrides.
pgrep -fl 'app\.dita\.app\.DitaApplication|dita-app[^ ]*\.jar' >/dev/null 2>&1 && stop "another Dita application process is running; stop it first"
SESS=$(q "select count(*) from pg_stat_activity where usename in ('dita_app','dita_relay')")
[[ "$SESS" == 0 ]] || stop "PostgreSQL has ${SESS:-?} open dita_app/dita_relay sessions (another Dita process?)"
OVR=$(env | cut -d= -f1 | grep -E '^DITA_(MUSE|EXTRACTION|STORAGE|PROCESSING)_' || true)
[[ -z "$OVR" ]] || stop "unexpected Dita overrides in the environment: $OVR"
echo "=== PASS: no other Dita process, no Dita DB sessions, no Muse/extraction/storage overrides"
echo "This run inserts SYNTHETIC benchmark messages into new groups cap-<run>-N (and -decoy), and replays the Synthetic Lab"
echo "fixture into a fresh synthetic chat id (never the real demo chat). Nothing is deleted except"
echo "the corpus' own scripted deletions inside those new groups (DEC-012 replay)."
export DITA_TEST_DB_URL="jdbc:postgresql://$PG/$DB" DITA_TEST_DB_USER=dita_app DITA_TEST_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" \
       DITA_TEST_RELAY_PASSWORD="${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}" DITA_TEST_NEO4J_URL="http://$NEO" \
       DITA_TEST_NEO4J_PASSWORD="${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}" DITA_TEST_VESPA_URL="http://$VESPA"
unset MODEL_API_KEY
mvn -o -pl dita-app -am test -Dtest='CapabilityPostgresRunTest,SyntheticLabReplayTest,SyntheticLabKnowledgeTest' -Dsurefire.failIfNoSpecifiedTests=false -Ddita.lab.muse=PREVIEW \
    -Ddita.capability.run=true -Ddita.lab.backend=POSTGRES \
    > "$OUT/mvn.log" 2>&1
rc=$?
grep -E 'Tests run:.*(CapabilityPostgresRunTest|SyntheticLabReplayTest)|<<< (FAIL|ERR)|BUILD' "$OUT/mvn.log" | head
ok=0
for t in CapabilityPostgresRunTest SyntheticLabReplayTest SyntheticLabKnowledgeTest; do
  grep -qE "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .*$t" "$OUT/mvn.log" && echo "=== PASS: $t ran" || { echo "=== FAIL: $t did not run cleanly"; ok=1; }
done
if (( rc == 0 && ok == 0 )); then
  sed -n '1,30p' eval/value-benchmark/results/capability-dev-postgres.md
  python3 -I eval/synthetic-lab/score.py postgres | tee eval/synthetic-lab/results/score-postgres.md | tail -1
  echo "=== RESULT: PASS (ran and scored). Reports: eval/value-benchmark/results/capability-dev-postgres.md, eval/synthetic-lab/results/score-postgres.md"; exit 0
fi
echo "=== RESULT: FAIL (see $OUT/mvn.log)"; exit 1
