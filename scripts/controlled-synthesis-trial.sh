#!/usr/bin/env bash
# TASK-054. Invoked through scripts/muse-live.sh synthesis ...
#   preview  [postgres|inmemory] [v2|v1]          capture each question's exact synthesis request; NOTHING is sent
#   live     qN [postgres|inmemory] [v2|v1]       ONE live synthesis call for ONE reviewed question, after typing 'approve'
#   recorded [qN] [postgres|inmemory] [v2|v1]     replay saved synthesis responses offline (no network); qN = one question
# Prompt v2 (default, TASK-054 revision) writes to build/controlled-synthesis-cedar-v2/; v1 keeps build/controlled-synthesis-cedar/.
# Production answer path, fixed synthetic Cedar fixture, Telegram OFF. Knowledge comes from the SAVED extraction
# response in build/controlled-muse-cedar (read-only). Never retries a live attempt.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE=${1:-preview}
case "$MODE" in preview) BACKEND=${2:-postgres}; PROMPT=${3:-v2}; QID="";;
  recorded) QID=""; if [[ "${2:-}" =~ ^q[1234]$ ]]; then QID=$2; shift; fi; BACKEND=${2:-postgres}; PROMPT=${3:-v2};;
  live) QID=${2:-}; BACKEND=${3:-postgres}; PROMPT=${4:-v2}
        [[ "$QID" =~ ^q[1234]$ ]] || { echo 'usage: bash scripts/muse-live.sh synthesis live q1|q2|q3|q4 [postgres|inmemory] [v2|v1]'; exit 64; };;
  *) echo 'usage: bash scripts/muse-live.sh synthesis preview [postgres|inmemory] [v2|v1] | recorded [qN] [postgres|inmemory] [v2|v1] | live qN [postgres|inmemory] [v2|v1]'; exit 64;; esac
case "$PROMPT" in v2) TRIAL=build/controlled-synthesis-cedar-v2;; v1) TRIAL=build/controlled-synthesis-cedar;
  [[ $QID != q4 ]] || { echo 'q4 exists only for v2'; exit 64; };; *) echo 'prompt must be v2 or v1'; exit 64;; esac
JP=$(echo "$PROMPT" | tr a-z A-Z)
case "$BACKEND" in postgres) JB=POSTGRES;; inmemory) JB=IN_MEMORY;; *) echo 'backend must be postgres or inmemory'; exit 64;; esac
OUT=$TRIAL/$BACKEND
EXT=build/controlled-muse-cedar
mkdir -p "$OUT"
[[ -f "$EXT/request.json" && -f "$EXT/recording.json" ]] || { echo "STOP: saved extraction artifacts missing in $EXT"; exit 2; }
ext_sums() { shasum -a 256 "$EXT/request.json" "$EXT/recording.json" "$EXT/attempted" "$EXT/result-live.json" 2>/dev/null; }
EXT_BEFORE=$(ext_sums)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
for v in $(env | cut -d= -f1); do
  case "$v" in DITA_*|SPRING_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|MAVEN_OPTS|MODEL_API_KEY|TELEGRAM_BOT_TOKEN) unset "$v";; esac
done
if [[ $JB == POSTGRES ]]; then
  prc=0
  pgrep -f 'app.dita.app.DitaApplication' > "$OUT/processes.log" 2>&1 || prc=$?
  [[ $prc == 1 ]] || { echo 'STOP: another Dita process exists or process inspection failed'; exit 2; }
  bash scripts/knowledge-verify.sh --check > "$OUT/check.log" 2>&1 || { echo "STOP: see $OUT/check.log"; exit 2; }
  grep -q 'V5: applied' "$OUT/check.log" || { echo 'STOP: V5 missing'; exit 2; }
  sessions=$(docker compose -p dita -f docker/compose.yml exec -T postgres psql -X -U dita -d dita -Atc "select count(*) from pg_stat_activity where usename in ('dita_app','dita_relay')")
  [[ "$sessions" == 0 ]] || { echo 'STOP: active Dita database sessions; stop other runs first'; exit 2; }
  export DITA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/dita DITA_TEST_DB_USER=dita_app
  export DITA_TEST_DB_PASSWORD=dita-app-dev-not-secret DITA_TEST_RELAY_PASSWORD=dita-relay-dev-not-secret
  export DITA_TEST_NEO4J_URL=http://127.0.0.1:57474 DITA_TEST_NEO4J_PASSWORD=dita-dev-not-secret
  export DITA_TEST_VESPA_URL=http://127.0.0.1:58080
fi
if [[ $MODE == live ]]; then
  Q=$OUT/$QID
  [[ -f "$Q/synthesis-request.json" && -f "$Q/result-preview.json" ]] || { echo "STOP: run the $BACKEND preview first and review $OUT/review.md"; exit 2; }
  [[ ! -e "$Q/attempted" && ! -e "$Q/synthesis-recording.json" ]] || { echo "STOP: $QID was already attempted; use recorded. Never retried."; exit 2; }
  export DITA_SYNTH_REQUEST_SHA256=$(shasum -a 256 "$Q/synthesis-request.json" | cut -d' ' -f1)
  python3 -I - "$Q/result-preview.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1])); q = r["request"]; e = q["estimate"]
print(f"ONE live Muse SYNTHESIS call, prompt {r.get('prompt', 'muse-answer-v1')}")
print(f"  question    : {q['question_sent']}  ({r['qid']})")
print(f"  destination : {q['destination']}")
print(f"  model       : {q['model']}; reasoning_effort {q['reasoning_effort']}; temperature {q['temperature']}; max_completion_tokens {q['max_completion_tokens']} (includes reasoning); timeout 60s; no retries, no redirects")
print("  evidence    : " + "; ".join(f"[{x['id']}] {x['speaker']} {x['stance']}: \"{x['quote']}\"" for x in q["evidence_sent"]))
print(f"  draft       : {q['draft_sent']!r}")
print(f"  in request  : {q['conversation_messages_in_request']} (m3/m4 are unrelated stored messages)")
print(f"  bytes       : {q['bytes']}  sha256 {q['sha256']}")
print(f"  cost        : at most about ${e['max_cost_usd']} ({e['prompt_tokens_upper_estimate']} prompt-token upper estimate + {e['max_output_tokens_billable']} output worst case); {e['pricing_basis']}")
PY
  echo "  writes      : synthetic rows on local Dita $BACKEND storage; response saved to $Q/synthesis-recording.json"
  read -r -p "Type 'approve' for this ONE call ($QID only): " answer
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
  export MODEL_API_KEY DITA_SYNTH_APPROVED=yes
fi
LOG=$OUT/$MODE${QID:+-$QID}.log
rc=0
MAVEN_OPTS="-Djava.io.tmpdir=${TMPDIR:-/tmp}" mvn -o -B -pl dita-app -am test -Dtest=ControlledSynthesisTrialTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddita.synth.run=true -Ddita.synth.mode="$(echo "$MODE" | tr a-z A-Z)" -Ddita.synth.backend="$JB" -Ddita.synth.question="$QID" -Ddita.synth.prompt="$JP" > "$LOG" 2>&1 || rc=$?
if [[ $MODE == live ]]; then
  python3 -I - <<'PY'
import os
from pathlib import Path
key = os.environ['MODEL_API_KEY'].encode()
files = list(Path('build/controlled-synthesis-cedar').rglob('*')) + list(Path('build/controlled-synthesis-cedar-v2').rglob('*')) + list(Path('.').glob('*/target/surefire-reports/*'))
if any(p.is_file() and key in p.read_bytes() for p in files): raise SystemExit('STOP: credential detected in output; do not share logs')
PY
  unset MODEL_API_KEY
fi
[[ "$EXT_BEFORE" == "$(ext_sums)" ]] && echo "OK: saved extraction artifacts unchanged" || { echo "FAIL: $EXT changed"; exit 1; }
grep -E '^\[TASK-054|Tests run:.*ControlledSynthesisTrialTest|BUILD' "$LOG" || true
(( rc == 0 )) && grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .*ControlledSynthesisTrialTest' "$LOG" || { echo "FAIL: inspect $LOG locally"; exit 1; }
if [[ $MODE == preview ]]; then echo "PREVIEW DONE: nothing sent. Review $OUT/review.md and $OUT/q*/synthesis-request.json"; fi
if [[ $MODE == live ]]; then echo "LIVE DONE ($QID): see $OUT/$QID/result-live.json"; fi
