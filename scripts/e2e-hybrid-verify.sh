#!/usr/bin/env bash
# TASK-052 real-stack verification of the integrated tree (TASK-048 + TASK-049 + TASK-051). Three stages; PASS only if
# all three EXECUTED with 0 failures and 0 skipped. A skip is never a pass.
#   1. TASK-051 regression on the demo stack (compose "dita": 55432 / 57474 / 58080), dense OFF:
#      scripts/e2e-replay-verify.sh, unchanged.
#   2. Codex's frozen English A/B/C (Task049EnglishIntegrationTest), dense ON, on Codex's ISOLATED dense stack
#      (PostgreSQL 127.0.0.1:55532/dita049, Vespa 127.0.0.1:58180 with the hybrid bge-small package already deployed).
#   3. TASK-051 replay acceptance on the same isolated dense stack with dense ON: the synthetic Telegram replay must
#      answer the lexically poor m7 question from m1 through the production HybridKnowledgeRetriever.
# Safety: no migrations, no schema, package or role changes, no Vespa deploys. SYNTHETIC rows only, in fresh random
# groups. No Telegram, no Muse (recorded replies only; MODEL_API_KEY is unset). Credentials come from the caller's
# environment and are never printed.
# Stage 2/3 need the isolated stack's disposable dev credentials:
#   DITA049_APP_PASSWORD (dita_app on :55532/dita049), DITA049_RELAY_PASSWORD (dita_relay on the same database), and
#   DITA_NEO4J_PASSWORD (the demo-stack Neo4j on :57474 that Codex's isolated run shares).
# Credential sources, first match wins, values never printed or logged (only the source is reported):
#   1. the caller's environment;
#   2. an untracked KEY=VALUE file, default .env.task049 in the project root (override: DITA049_CREDENTIALS_FILE).
#      It must be a regular file (not a symlink) owned by you with mode 600. It is PARSED, never sourced; only the
#      three keys above are accepted. .gitignore already excludes .env.*;
#   3. DITA_NEO4J_PASSWORD only: the demo stack's documented dev default, exactly as stage 1 already uses.
# Before Maven runs, a read-only login check (SELECT 1 / Neo4j RETURN 1) stops the run if a credential is rejected.
# Optional: DITA049_DB_URL, DITA049_VESPA_URL, DITA049_NEO4J_URL, DITA049_MIN_COSINE (default 0.60, Codex's frozen v2 gate).
# Exit: 0 PASS · 1 FAIL / NOT EXECUTED · 2 STOP (prerequisite missing).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/e2e-hybrid-verify; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
unset MODEL_API_KEY TELEGRAM_BOT_TOKEN
date -u +"started %Y-%m-%dT%H:%M:%SZ"
echo "tree: $(scripts/task046-freeze.sh | tail -1)"
fail=0

echo "## 1. TASK-051 replay acceptance on the demo stack (dense off)"
if scripts/e2e-replay-verify.sh > "$OUT/stage1.log" 2>&1; then echo "=== stage 1 PASS"; else echo "=== stage 1 FAIL/NOT EXECUTED (see $OUT/stage1.log)"; fail=1; fi
cp build/e2e-replay-verify/mvn.log "$OUT/stage1-mvn.log" 2>/dev/null

echo "## credentials for stages 2 and 3 (values are never printed)"
CRED_KEYS="DITA049_APP_PASSWORD DITA049_RELAY_PASSWORD DITA_NEO4J_PASSWORD"
CRED_FILE=${DITA049_CREDENTIALS_FILE:-.env.task049}
for v in $CRED_KEYS; do if [[ -n "${!v:-}" ]]; then printf -v "SRC_$v" %s environment; else printf -v "SRC_$v" %s ""; fi; done
if [[ -e "$CRED_FILE" || -L "$CRED_FILE" ]]; then
  [[ -L "$CRED_FILE" ]] && { echo "=== STOP: $CRED_FILE is a symlink; refusing it"; exit 2; }
  [[ -f "$CRED_FILE" && -O "$CRED_FILE" ]] || { echo "=== STOP: $CRED_FILE must be a regular file owned by you"; exit 2; }
  mode=$(stat -f '%Lp' "$CRED_FILE" 2>/dev/null || stat -c '%a' "$CRED_FILE")
  [[ "$mode" == 600 || "$mode" == 400 ]] || { echo "=== STOP: $CRED_FILE has mode $mode; run: chmod 600 $CRED_FILE"; exit 2; }
  n=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    n=$((n+1)); line=${line%$'\r'}
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    line=${line#export }
    key=${line%%=*}; val=${line#*=}
    [[ "$line" == *=* && " $CRED_KEYS " == *" $key "* ]] || { echo "=== STOP: $CRED_FILE line $n is not KEY=VALUE for an accepted key (content not shown)"; exit 2; }
    [[ "$val" =~ ^\"(.*)\"$ || "$val" =~ ^\'(.*)\'$ ]] && val=${BASH_REMATCH[1]}
    [[ "$val" =~ ^[[:space:]] || "$val" =~ [[:space:]]$ ]] && echo "  WARNING: $CRED_FILE line $n ($key) has leading/trailing whitespace; it is kept as part of the password"
    sv="SRC_$key"
    if [[ -z "${!sv}" && -n "$val" ]]; then printf -v "$key" '%s' "$val"; export "$key"; printf -v "$sv" 'file %s' "$CRED_FILE"; fi
  done < "$CRED_FILE"
  unset line key val sv
fi
if [[ -z "${DITA_NEO4J_PASSWORD:-}" ]]; then
  export DITA_NEO4J_PASSWORD=dita-dev-not-secret; SRC_DITA_NEO4J_PASSWORD="demo-stack dev default (same as stage 1)"
fi
missing=0
for v in $CRED_KEYS; do
  if [[ -n "${!v:-}" ]]; then sv="SRC_$v"; echo "  $v: set (source: ${!sv})"; else echo "  $v: MISSING"; missing=1; fi
done
if (( missing )); then
  echo "=== STOP: stages 2 and 3 NOT EXECUTED. Put the missing values in $CRED_FILE (mode 600) or export them;"
  echo "    see docs/task052-hybrid-credentials.md. Nothing was reset or changed."; exit 2
fi
DB=${DITA049_DB_URL:-jdbc:postgresql://127.0.0.1:55532/dita049}
VESPA=${DITA049_VESPA_URL:-http://127.0.0.1:58180}
NEO=${DITA049_NEO4J_URL:-http://127.0.0.1:57474}
COS=${DITA049_MIN_COSINE:-0.60}

echo "## read-only login check (no writes)"
hp=${DB#jdbc:postgresql://}; hp=${hp%%/*}; PGDB=${DB##*/}; PGDB=${PGDB%%\?*}; PGH=${hp%%:*}; PGP=${hp##*:}
login_ok=1
for pair in "dita_app:DITA049_APP_PASSWORD" "dita_relay:DITA049_RELAY_PASSWORD"; do
  role=${pair%%:*}; var=${pair#*:}
  if command -v psql >/dev/null; then
    if PGPASSWORD="${!var}" PGCONNECT_TIMEOUT=5 psql -X -w -h "$PGH" -p "$PGP" -U "$role" -d "$PGDB" -Atc 'select 1' >/dev/null 2>"$OUT/login-$role.err"; then
      echo "  $role@$PGH:$PGP/$PGDB: login OK"
    else
      echo "  $role@$PGH:$PGP/$PGDB: login FAILED ($(grep -oE 'password authentication failed|Connection refused|does not exist|timeout' "$OUT/login-$role.err" | head -1))"; login_ok=0
    fi
  else echo "  psql not found: PostgreSQL login check skipped (Maven will still fail fast on a bad password)"; fi
done
code=$(printf 'user = "neo4j:%s"\n' "$DITA_NEO4J_PASSWORD" | curl -sS -m 5 -K - -o /dev/null -w '%{http_code}' \
       -H 'Content-Type: application/json' -d '{"statements":[{"statement":"RETURN 1"}]}' "$NEO/db/neo4j/tx/commit" 2>/dev/null)
if [[ "$code" == 200 ]]; then echo "  neo4j@$NEO: login OK"; else echo "  neo4j@$NEO: login FAILED (HTTP ${code:-none})"; login_ok=0; fi
if (( ! login_ok )); then echo "=== STOP: a credential was rejected or a service is unreachable; stages 2 and 3 NOT EXECUTED. Nothing was changed."; exit 2; fi

run_one() {   # $1 test class, $2 log, rest: extra mvn args. Returns 0 only for 1 executed, 0 skipped.
  local t=$1 log=$2; shift 2
  mvn -o -B -pl dita-app -am test -Dtest="$t" -Dsurefire.failIfNoSpecifiedTests=false "$@" > "$log" 2>&1
  local rc=$?
  grep -E "Tests run:.* in app\.dita\.app\.$t|BUILD" "$log"
  (( rc == 0 )) && grep -qE "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .* in app\.dita\.app\.$t" "$log"
}

echo "## 2. Codex frozen English A/B/C, dense on, production QueryService wiring (isolated dense stack)"
if ( export DITA_APP_PASSWORD="$DITA049_APP_PASSWORD" DITA_RELAY_PASSWORD="$DITA049_RELAY_PASSWORD" DITA_NEO4J_PASSWORD
     run_one Task049EnglishIntegrationTest "$OUT/stage2-mvn.log" -Ddita.task049.run=true \
       -Ddita.task049.freeze=freeze-manifest-v2.json -Ddita.task049.artifacts="$PWD/eval/TASK-049" \
       -Ddita.task049.output="$PWD/$OUT/task049-output" ); then echo "=== stage 2 PASS (metrics: $OUT/task049-output)"
else echo "=== stage 2 FAIL/NOT EXECUTED (see $OUT/stage2-mvn.log)"; fail=1; fi

echo "## 3. TASK-051 replay acceptance with dense on (isolated dense stack)"
if ( export DITA_TEST_DB_URL="$DB" DITA_TEST_DB_USER=dita_app DITA_TEST_DB_PASSWORD="$DITA049_APP_PASSWORD" \
            DITA_TEST_RELAY_PASSWORD="$DITA049_RELAY_PASSWORD" DITA_TEST_NEO4J_URL="$NEO" \
            DITA_TEST_NEO4J_PASSWORD="$DITA_NEO4J_PASSWORD" DITA_TEST_VESPA_URL="$VESPA" DITA_TEST_DENSE_MIN_COSINE="$COS"
     run_one TelegramReplayPostgresAcceptanceTest "$OUT/stage3-mvn.log" ); then echo "=== stage 3 PASS"
else echo "=== stage 3 FAIL/NOT EXECUTED (see $OUT/stage3-mvn.log)"; fail=1; fi

if (( fail == 0 )); then echo "=== RESULT: PASS (3/3 stages executed, 0 skipped). Muse RECORDED, not live."; exit 0; fi
echo "=== RESULT: FAIL or NOT EXECUTED — see $OUT/"; exit 1
