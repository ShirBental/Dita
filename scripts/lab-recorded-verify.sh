#!/usr/bin/env bash
# TASK-044: re-run the A/B/C experiment on the REAL stack (PostgreSQL + Neo4j + Vespa) from the SAVED Muse reply
# (eval/synthetic-lab/results/muse-recording.json). No Muse call, no key, no cost. Synthetic data only, fresh group.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/lab-recorded; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
RES=eval/synthetic-lab/results
stop() { echo "=== STOP: $1"; exit 2; }
[[ -f $RES/muse-recording.json ]] || stop "no saved Muse reply"
DC=(docker compose -p dita -f docker/compose.yml)
for svc in postgres neo4j vespa; do
  cid=$("${DC[@]}" ps -q "$svc" 2>/dev/null); [[ -n "$cid" ]] || stop "service $svc of project dita not running"
  [[ "$(docker inspect -f '{{ if .State.Health }}{{ .State.Health.Status }}{{ else }}none{{ end }}' "$cid")" == healthy ]] || stop "$svc not healthy"
done
pgrep -fl 'app\.dita\.app\.DitaApplication|dita-app[^ ]*\.jar' >/dev/null 2>&1 && stop "another Dita application process is running"
OVR=$(env | cut -d= -f1 | grep -E '^DITA_(MUSE|EXTRACTION|STORAGE|PROCESSING)_|^SPRING_APPLICATION_JSON$|^JAVA_TOOL_OPTIONS$' || true)
[[ -z "$OVR" ]] || stop "unexpected overrides: $OVR"
unset MODEL_API_KEY
env DITA_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${DITA_DB_PORT:-55432}/${DITA_DB_NAME:-dita}" DITA_TEST_DB_USER=dita_app \
    DITA_TEST_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" DITA_TEST_RELAY_PASSWORD="${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}" \
    DITA_TEST_NEO4J_URL="http://127.0.0.1:${DITA_NEO4J_HTTP_PORT:-57474}" DITA_TEST_NEO4J_PASSWORD="${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}" \
    DITA_TEST_VESPA_URL="http://127.0.0.1:${DITA_VESPA_QUERY_PORT:-58080}" JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21} \
  mvn -o -pl dita-app -am test -Dtest=SyntheticLabKnowledgeTest -Dsurefire.failIfNoSpecifiedTests=false -Ddita.capability.run=true \
      -Ddita.lab.muse=RECORDED -Ddita.lab.backend=POSTGRES > "$OUT/mvn.log" 2>&1
rc=$?
grep -E 'Tests run:.*SyntheticLabKnowledgeTest|BUILD' "$OUT/mvn.log" | head -3
grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .*SyntheticLabKnowledgeTest' "$OUT/mvn.log" && (( rc == 0 )) || { echo "=== FAIL (see $OUT/mvn.log)"; exit 1; }
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); p=r.get("projection_check",{}); print({k: r.get(k) for k in ("status","accepted","relations","superseded")}, {k: p.get(k) for k in ("postgres_items","postgres_relations","neo4j_knowledge_nodes","neo4j_relates_edges","vespa_knowledge_docs")})' $RES/knowledge-postgres-recorded.json
for c in A B C; do python3 -I eval/synthetic-lab/score.py "lab-$c-postgres-recorded" > "$RES/score-lab-$c-postgres-recorded.md"; echo "$c: $(tail -1 "$RES/score-lab-$c-postgres-recorded.md")"; done
python3 -I eval/synthetic-lab/compare.py postgres-recorded > "$RES/compare-postgres-recorded.md" && echo "comparison: $RES/compare-postgres-recorded.md"
echo "=== RESULT: PASS (real stack, recorded Muse reply, no model call)"
