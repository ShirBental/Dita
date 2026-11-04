#!/usr/bin/env bash
# Invoked through scripts/muse-live.sh trial preview|live|recorded|selftest.
# Direct production pipeline, fixed synthetic fixture, Telegram OFF. Never retries a live attempt.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE=${1:-preview}
case "$MODE" in preview|live|recorded|selftest) ;; *) echo 'usage: bash scripts/muse-live.sh trial preview|live|recorded|selftest'; exit 64;; esac
DATASET=${2:-levi}
case "$DATASET" in levi) OUT=build/controlled-muse-trial; DATA_NOTE='3 fixed fictional messages about Dr. Levi and trip snacks';;
  cedar) OUT=build/controlled-muse-cedar; DATA_NOTE='2 fixed fictional Dr. Cedar messages (babies vs teenagers), SAME synthetic author';;
  *) echo 'Unknown dataset'; exit 64;; esac

mkdir -p "$OUT"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
# Clear all externally supplied JVM/Spring/Dita switches; leave PATH/HOME for existing Maven workflow.
for v in $(env | cut -d= -f1); do
  case "$v" in DITA_*|SPRING_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|MAVEN_OPTS|MODEL_API_KEY|TELEGRAM_BOT_TOKEN) unset "$v";; esac
done
if [[ $MODE == live || $MODE == recorded ]]; then
  prc=0
  pgrep -f 'app.dita.app.DitaApplication' > "$OUT/processes.log" 2>&1 || prc=$?
  [[ $prc == 1 ]] || { echo 'STOP: another Dita process exists or process inspection failed'; exit 2; }
fi
if [[ $MODE == live || $MODE == recorded ]]; then
  bash scripts/knowledge-verify.sh --check > "$OUT/check.log" 2>&1 || { echo "STOP: see $OUT/check.log"; exit 2; }
  grep -q 'V5: applied' "$OUT/check.log" || { echo 'STOP: V5 missing'; exit 2; }
  sessions=$(docker compose -p dita -f docker/compose.yml exec -T postgres psql -X -U dita -d dita -Atc "select count(*) from pg_stat_activity where usename in ('dita_app','dita_relay')")
  [[ "$sessions" == 0 ]] || { echo 'STOP: active Dita database sessions; stop other runs first'; exit 2; }
  export DITA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/dita DITA_TEST_DB_USER=dita_app
  export DITA_TEST_DB_PASSWORD=dita-app-dev-not-secret DITA_TEST_RELAY_PASSWORD=dita-relay-dev-not-secret
  export DITA_TEST_NEO4J_URL=http://127.0.0.1:57474 DITA_TEST_NEO4J_PASSWORD=dita-dev-not-secret
  export DITA_TEST_VESPA_URL=http://127.0.0.1:58080
fi
if [[ $MODE == preview ]]; then
  [[ ! -e "$OUT/attempted" ]] || { echo 'STOP: paid attempt exists; use recorded'; exit 2; }
fi
if [[ $MODE == live ]]; then
  [[ -f "$OUT/request.json" && ! -e "$OUT/attempted" && ! -e "$OUT/recording.json" ]] || { echo 'STOP: preview required; prior paid attempts must not be repeated'; exit 2; }
  export DITA_TRIAL_REQUEST_SHA256=$(shasum -a 256 "$OUT/request.json" | cut -d' ' -f1)
  cat <<INFO
ONE live call: https://api.meta.ai/v1/chat/completions
Model: muse-spark-1.3; reasoning LOW; output cap 4096 (includes reasoning), timeout 60s.
Data: $DATA_NOTE, pseudonymised authors, schema and system prompt.
Exact request: $OUT/request.json
SHA256: $DITA_TRIAL_REQUEST_SHA256
No Telegram. Synthetic writes on local Dita PostgreSQL/Neo4j/Vespa. No retries or redirects.
Cost estimate at historical user-supplied prices: about \$0.022408 assuming 4,000 input tokens.
This is an estimate, not a billing cap or a verified current price.
INFO
  read -r -p "Type 'approve' for this one call: " answer
  [[ $answer == approve ]] || { echo 'Cancelled; nothing sent'; exit 2; }
  # Parse only MODEL_API_KEY. Never source .env.local or print the credential.
  count=$(grep -cE '^[[:space:]]*(export[[:space:]]+)?MODEL_API_KEY=' .env.local || true)
  [[ "$count" == 1 ]] || { echo 'STOP: expected one MODEL_API_KEY assignment'; exit 2; }
  raw=$(grep -E '^[[:space:]]*(export[[:space:]]+)?MODEL_API_KEY=' .env.local | tr -d '\r')
  MODEL_API_KEY=${raw#*MODEL_API_KEY=}
  unset raw
  MODEL_API_KEY="${MODEL_API_KEY#"${MODEL_API_KEY%%[![:space:]]*}"}"
  MODEL_API_KEY="${MODEL_API_KEY%"${MODEL_API_KEY##*[![:space:]]}"}"
  if [[ "$MODEL_API_KEY" =~ ^\"(.*)\"$ || "$MODEL_API_KEY" =~ ^\'(.*)\'$ ]]; then MODEL_API_KEY=${BASH_REMATCH[1]}; fi
  [[ -n "$MODEL_API_KEY" && ! "$MODEL_API_KEY" =~ [[:space:]#\"\'] ]] || { unset MODEL_API_KEY; echo 'STOP: invalid key assignment'; exit 2; }
  export MODEL_API_KEY DITA_TRIAL_APPROVED=yes
fi
if [[ $MODE == recorded ]]; then
  [[ -f "$OUT/recording.json" ]] || { echo 'STOP: no saved successful live response'; exit 2; }
fi
rc=0
mvn -o -B -pl dita-app -am test -Dtest=ControlledMuseTrialTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddita.trial.run=true -Ddita.trial.dataset="$DATASET" -Ddita.trial.mode="$(echo "$MODE" | tr a-z A-Z)" > "$OUT/$MODE.log" 2>&1 || rc=$?
# Withhold output if the key appears in any generated report/log. Do not print matching lines.
if [[ $MODE == live ]]; then
  python3 -I - <<'PY'
import os
from pathlib import Path
key=os.environ['MODEL_API_KEY'].encode()
files=list(Path('build/controlled-muse-trial').glob('*'))+list(Path('build/controlled-muse-cedar').glob('*'))
files+=list(Path('.').glob('*/target/surefire-reports/*'))
leaks=[p for p in files if p.is_file() and key in p.read_bytes()]
if leaks: raise SystemExit('STOP: credential detected in output; do not share logs')
PY
  unset MODEL_API_KEY
fi
[[ ! -f "$OUT/result-$MODE.json" ]] || cat "$OUT/result-$MODE.json"
grep -E 'Tests run:.*ControlledMuseTrialTest|BUILD'  "$OUT/$MODE.log" || true
(( rc == 0 )) && grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .*ControlledMuseTrialTest' "$OUT/$MODE.log" || { echo "FAIL: inspect $OUT/$MODE.log locally"; exit 1; }
if [[ $MODE == preview ]]; then
  echo "PREVIEW PASS: no live call, no database connection. Review $OUT/request.json."
fi
