#!/usr/bin/env bash
# TASK-051 runtime acceptance on the REAL demo stack (PostgreSQL + Neo4j + Vespa of compose project "dita"):
#   synthetic Telegram replay → real poller + adapter → ingestion → extraction trigger → /extract approval →
#   Muse client with a RECORDED reply (no network, no key) → validation → persisted knowledge → Vespa/Neo4j indexing →
#   retrieval → grounded /ask reply.  Test: TelegramReplayPostgresAcceptanceTest (dita-app).
# Safety: read-only identity + migration check via scripts/knowledge-verify.sh --check. NO migrations, NO schema or
# role changes. Inserts SYNTHETIC rows only (a fresh replay group tgr1001… per run). No Telegram, no Muse call.
# Exit: 0 PASS (test executed, 0 failures, 0 skipped) · 1 FAIL · 2 STOP (prerequisite missing; nothing written).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/e2e-replay-verify; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
unset MODEL_API_KEY TELEGRAM_BOT_TOKEN
date -u +"started %Y-%m-%dT%H:%M:%SZ"

echo "## 1. Read-only identity and migration check (V2–V5 must already be applied)"
scripts/knowledge-verify.sh --check > "$OUT/check.log" 2>&1 || { cat "$OUT/check.log"; echo "=== STOP: prerequisite check failed; nothing was written"; exit 2; }
grep -q 'V5: applied' "$OUT/check.log" || { echo "=== STOP: V5 is not applied (run scripts/knowledge-verify.sh, which asks before applying)"; exit 2; }
PG=127.0.0.1:${DITA_DB_PORT:-55432}; NEO=127.0.0.1:${DITA_NEO4J_HTTP_PORT:-57474}; VESPA=127.0.0.1:${DITA_VESPA_QUERY_PORT:-58080}
echo "=== PASS: services and V5 present (see $OUT/check.log)"

echo "## 2. Acceptance test on the real services"
export DITA_TEST_DB_URL="jdbc:postgresql://$PG/${DITA_DB_NAME:-dita}" DITA_TEST_DB_USER=dita_app \
       DITA_TEST_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" \
       DITA_TEST_RELAY_PASSWORD="${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}" \
       DITA_TEST_NEO4J_URL="http://$NEO" DITA_TEST_NEO4J_PASSWORD="${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}" \
       DITA_TEST_VESPA_URL="http://$VESPA"
mvn -o -B -pl dita-app -am test -Dtest=TelegramReplayPostgresAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false \
    > "$OUT/mvn.log" 2>&1
rc=$?
grep -E 'Tests run:.*TelegramReplayPostgresAcceptanceTest|BUILD' "$OUT/mvn.log"
if (( rc == 0 )) && grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .* in app\.dita\.app\.TelegramReplayPostgresAcceptanceTest' "$OUT/mvn.log"; then
  echo "=== RESULT: PASS (executed on the real stack, 0 skipped). Muse reply was RECORDED, not live."; exit 0
fi
echo "=== RESULT: FAIL or NOT EXECUTED (a skip is not a pass) — see $OUT/mvn.log"; exit 1
