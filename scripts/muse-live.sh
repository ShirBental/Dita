#!/usr/bin/env bash
# Explicitly approved live Meta Muse calls (DEC-003). Two SEPARATE modes:
#   scripts/muse-live.sh connectivity   one tiny request ("Reply with the single word: OK", 8 tokens), no group data
#   scripts/muse-live.sh extraction     one extraction-quality evaluation on 10 SYNTHETIC messages
#   scripts/muse-live.sh lab            TASK-044: ONE extraction call on the 41-message Synthetic Lab fixture, then the
#                                       A/B/C experiment on the REAL stack (PostgreSQL + Neo4j + Vespa); the reply is
#                                       recorded so every later rerun is offline (RECORDED mode, no cost)
# Endpoint and model are fixed to the user-verified docs: https://api.meta.ai/v1/chat/completions, muse-spark-1.3
# (override the model only with DITA_MUSE_MODEL). Only MODEL_API_KEY is read from .env.local; it is never printed.
set -uo pipefail
if [[ "${1:-}" == trial ]]; then
  shift
  exec bash "$(dirname "$0")/controlled-muse-trial.sh" "$@"
fi
if [[ "${1:-}" == synthesis ]]; then        # TASK-054: production answer-synthesis trial (one approved question per call)
  shift
  exec bash "$(dirname "$0")/controlled-synthesis-trial.sh" "$@"
fi
cd "$(dirname "$0")/.."
MODE=${1:-}
case "$MODE" in
  connectivity) TEST=MuseConnectivityLiveTest; FLAG=DITA_MUSE_CONNECTIVITY_APPROVED; REPORT=dita-muse/target/muse-connectivity-report.json
    TOK=${DITA_MUSE_CONNECTIVITY_TOKENS:-128}
    [[ "$TOK" =~ ^[0-9]+$ ]] && (( TOK >= 16 && TOK <= 4096 )) || { echo "DITA_MUSE_CONNECTIVITY_TOKENS must be 16..4096"; exit 1; }
    DATA="a fixed prompt 'Reply with the single word: OK' (no group messages)"
    CAP="max_completion_tokens=$TOK (reasoning model: reasoning counts against this; 64 and 128 were fully consumed), 30 s timeout" ;;
  extraction) TEST=MuseExtractionLiveTest; FLAG=DITA_MUSE_EXTRACTION_APPROVED; REPORT=dita-muse/target/muse-extraction-report.json
    DATA="10 SYNTHETIC messages (fictional parents group, ~1,000 chars, incl. one prompt-injection attempt) + system prompt + JSON schema (~3,000 chars)"
    CAP="max_completion_tokens=4096 (reasoning model: reasoning counts against this), 60 s timeout" ;;
  lab|holdout|holdout-v2) TEST=SyntheticLabKnowledgeTest; FLAG=DITA_MUSE_LAB_APPROVED
    if [[ $MODE == lab ]]; then RES=eval/synthetic-lab/results; FIX=eval/synthetic-lab/synthetic_updates.json
    elif [[ $MODE == holdout ]]; then RES=eval/synthetic-holdout/results; FIX=eval/synthetic-holdout/sealed/conversation.json
    else RES=eval/synthetic-holdout-v2/results; FIX=eval/synthetic-holdout-v2/sealed/conversation.json; fi
    REPORT=$RES/knowledge-postgres-live.json
    TOK=${DITA_MUSE_LAB_TOKENS:-16000}
    [[ "$TOK" =~ ^[0-9]+$ ]] && (( TOK >= 1024 && TOK <= 32768 )) || { echo "DITA_MUSE_LAB_TOKENS must be 1024..32768"; exit 1; }
    [[ -f "$RES/muse-request-preview.json" ]] || { echo "no reviewed request preview in $RES; run the PREVIEW first. Nothing sent."; exit 1; }
    DATA="the SYNTHETIC $MODE fixture ($FIX, sha256 $(shasum -a 256 "$FIX" | cut -c1-16)…), authors pseudonymised; exact request = $RES/muse-request-preview.json (sha256 $(shasum -a 256 "$RES/muse-request-preview.json" | cut -c1-16)…, $(wc -c < "$RES/muse-request-preview.json" | tr -d ' ') bytes). No chat id, no user ids, no names except those inside the fictional message text"
    CAP="max_completion_tokens=$TOK (reasoning included), 120 s timeout, exactly ONE call (enforced in the test and by max-calls-per-process=1)" ;;
  *) echo "usage: $0 connectivity|extraction|lab|holdout|holdout-v2"; exit 64 ;;
esac
URL=https://api.meta.ai/v1/chat/completions                    # N-38: the only destination the key is sent to
MODEL=${DITA_MUSE_MODEL:-muse-spark-1.3}
FORMAT=${DITA_MUSE_RESPONSE_FORMAT:-PROMPT_ONLY}
EFFORT=${DITA_MUSE_REASONING_EFFORT:-LOW}
[[ "$EFFORT" =~ ^(MINIMAL|LOW|MEDIUM|HIGH|XHIGH)$ ]] || { echo "DITA_MUSE_REASONING_EFFORT must be MINIMAL|LOW|MEDIUM|HIGH|XHIGH (none is unsupported)"; exit 1; }
# Standard-mode Muse Spark pricing, user-supplied 2026-10-28: $1.25/M input, $4.25/M output (reasoning billed as output).
export DITA_MUSE_PRICE_INPUT_PER_MTOK=${DITA_MUSE_PRICE_INPUT_PER_MTOK:-1.25}
export DITA_MUSE_PRICE_OUTPUT_PER_MTOK=${DITA_MUSE_PRICE_OUTPUT_PER_MTOK:-4.25}
PRICE_NOTE="prices not supplied: the report gives token counts only"
if [[ -n "${DITA_MUSE_PRICE_INPUT_PER_MTOK:-}" && -n "${DITA_MUSE_PRICE_OUTPUT_PER_MTOK:-}" ]]; then
  MAXCOST=$(python3 -I -c 'import sys; i,o,t=map(float,sys.argv[1:]); print(round((4000*i+t*o)/1e6, 6))' \
            "$DITA_MUSE_PRICE_INPUT_PER_MTOK" "$DITA_MUSE_PRICE_OUTPUT_PER_MTOK" "${TOK:-4096}") || { echo "prices must be numbers"; exit 1; }
  PRICE_NOTE="at most about \$$MAXCOST (\$$DITA_MUSE_PRICE_INPUT_PER_MTOK/M in, \$$DITA_MUSE_PRICE_OUTPUT_PER_MTOK/M out incl. reasoning; 4,000 prompt tokens assumed)"
fi
[[ "$FORMAT" =~ ^(PROMPT_ONLY|JSON_OBJECT|JSON_SCHEMA)$ ]] || { echo "DITA_MUSE_RESPONSE_FORMAT must be PROMPT_ONLY|JSON_OBJECT|JSON_SCHEMA"; exit 1; }

# N-34: strict key parsing. One assignment line; strip 'export', CR and surrounding spaces; one pair of quotes;
# refuse inline comments or inner whitespace rather than guess.
LINES=$(grep -cE '^[[:space:]]*(export[[:space:]]+)?MODEL_API_KEY=' .env.local 2>/dev/null || true)
[[ "$LINES" == 1 ]] || { echo "expected exactly one MODEL_API_KEY= line in .env.local (found ${LINES:-0})"; exit 1; }
RAW=$(grep -E '^[[:space:]]*(export[[:space:]]+)?MODEL_API_KEY=' .env.local | tr -d '\r')
VAL=${RAW#*MODEL_API_KEY=}
VAL="${VAL#"${VAL%%[![:space:]]*}"}"; VAL="${VAL%"${VAL##*[![:space:]]}"}"
if [[ "$VAL" =~ ^\"(.*)\"$ || "$VAL" =~ ^\'(.*)\'$ ]]; then VAL=${BASH_REMATCH[1]}; fi
[[ -n "$VAL" && ! "$VAL" =~ [[:space:]#\"\'] ]] || { echo "MODEL_API_KEY value in .env.local has spaces, quotes or a comment; fix the line"; exit 1; }
export MODEL_API_KEY="$VAL"; unset RAW VAL

cat <<INFO
About to make ONE live Muse call ($MODE):
  destination : $URL
  model       : $MODEL
  data sent   : $DATA
  format      : $FORMAT (PROMPT_ONLY = schema in the prompt, no response_format parameter; connectivity ignores it)
  reasoning   : reasoning_effort=$(echo "$EFFORT" | tr A-Z a-z) (sent explicitly; reasoning tokens count toward the cap and bill as output)
  limits      : $CAP; no retries; no redirects (token limit sent as max_completion_tokens, per the docs)
  key         : MODEL_API_KEY from .env.local (${#MODEL_API_KEY} characters; never printed or logged)
  cost        : billable ≤ prompt tokens + the output cap (reasoning included); $PRICE_NOTE
INFO
read -r -p "Type 'approve' to send this one request: " ok
[[ "$ok" == approve ]] || { unset MODEL_API_KEY; echo "cancelled; nothing sent"; exit 1; }

# N-35: no stale artefacts can be mistaken for this run's results.
OUT=build/muse-report; mkdir -p "$OUT"
rm -f "$REPORT" "$OUT/$MODE.log"; rm -rf dita-muse/target/surefire-reports
if [[ $MODE == lab || $MODE == holdout || $MODE == holdout-v2 ]]; then
  DCMD=(docker compose -p dita -f docker/compose.yml)
  # Natasha N-78 and condition 3, enforced: no other Dita process, no Dita DB sessions, no Muse/extraction overrides.
  if pgrep -fl 'app\.dita\.app\.DitaApplication|dita-app[^ ]*\.jar' >/dev/null 2>&1; then unset MODEL_API_KEY; echo "another Dita application process is running (N-78); stop it first. Nothing sent."; exit 1; fi
  OVR=$(env | cut -d= -f1 | grep -E '^DITA_(MUSE|EXTRACTION)_' | grep -vE '^DITA_MUSE_(PRICE_INPUT_PER_MTOK|PRICE_OUTPUT_PER_MTOK|MODEL|RESPONSE_FORMAT|REASONING_EFFORT|LAB_TOKENS)$' || true)
  [[ -z "$OVR" ]] || { unset MODEL_API_KEY; echo "unexpected overrides in the environment: $OVR. Nothing sent."; exit 1; }
  for v in SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS MAVEN_OPTS JDK_JAVA_OPTIONS; do
    [[ -z "${!v:-}" ]] || { unset MODEL_API_KEY; echo "$v is set; unset it first (Natasha C2). Nothing sent."; exit 1; }
  done
  [[ "${DITA_MUSE_LAB_TOKENS:-16000}" == 16000 && "$EFFORT" == LOW && "$MODEL" == muse-spark-1.3 && "$FORMAT" == PROMPT_ONLY ]] || { unset MODEL_API_KEY; echo "non-default tokens/effort/model would not match the reviewed request. Nothing sent."; exit 1; }
  for svc in postgres neo4j vespa; do
    cid=$("${DCMD[@]}" ps -q "$svc" 2>/dev/null); [[ -n "$cid" ]] || { unset MODEL_API_KEY; echo "service $svc of project dita not running; nothing sent"; exit 1; }
    [[ "$(docker inspect -f '{{ if .State.Health }}{{ .State.Health.Status }}{{ else }}none{{ end }}' "$cid")" == healthy ]] || { unset MODEL_API_KEY; echo "$svc not healthy; nothing sent"; exit 1; }
  done
  [[ ! -e "$RES/muse-recording.json" ]] || { unset MODEL_API_KEY; echo "a paid recording already exists; rerun offline with RECORDED mode (N-81). Nothing sent."; exit 1; }
  SESS=$("${DCMD[@]}" exec -T postgres psql -X -U "${DITA_DB_OWNER:-dita}" -d "${DITA_DB_NAME:-dita}" -Atc "select count(*) from pg_stat_activity where usename in ('dita_app','dita_relay')" 2>/dev/null)
  [[ "$SESS" == 0 ]] || { unset MODEL_API_KEY; echo "PostgreSQL has ${SESS:-?} open dita_app/dita_relay sessions (another Dita process? N-78). Nothing sent."; exit 1; }
  rm -f "$REPORT"; rm -rf dita-app/target/surefire-reports
  env DITA_MUSE_LAB_APPROVED="user-approved-$(date -u +%Y%m%dT%H%M%SZ)" \
      DITA_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${DITA_DB_PORT:-55432}/${DITA_DB_NAME:-dita}" DITA_TEST_DB_USER=dita_app \
      DITA_TEST_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" DITA_TEST_RELAY_PASSWORD="${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}" \
      DITA_TEST_NEO4J_URL="http://127.0.0.1:${DITA_NEO4J_HTTP_PORT:-57474}" DITA_TEST_NEO4J_PASSWORD="${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}" \
      DITA_TEST_VESPA_URL="http://127.0.0.1:${DITA_VESPA_QUERY_PORT:-58080}" JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21} \
    mvn -o -pl dita-app -am test -Dtest="$TEST" -Dsurefire.failIfNoSpecifiedTests=false -Ddita.capability.run=true \
        -Ddita.lab.muse=LIVE -Ddita.lab.backend=POSTGRES -Ddita.lab.dataset="$MODE" -Ddita.lab.max-output-tokens="$TOK" > "$OUT/$MODE.log" 2>&1
  rc=$?
  LEAK=0
  for f in "$OUT/$MODE.log" "$RES"/* */target/surefire-reports/*; do
    [[ -f "$f" ]] && grep -qF -f <(printf '%s\n' "$MODEL_API_KEY") "$f" && { LEAK=1; echo "KEY FOUND IN $f: deleting"; rm -f "$f"; }
  done
  unset MODEL_API_KEY
  (( LEAK == 0 )) || { echo "=== FAIL: key leaked into output (files deleted)"; exit 2; }
  echo "=== PASS: no key in log, results or surefire output"
  grep -E "Tests run:.*$TEST|BUILD" "$OUT/$MODE.log" | head -3
  [[ -f "$REPORT" ]] && python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); print({k: r.get(k) for k in ("status","error","accepted","relations","superseded","model_calls","cost_usd_at_user_prices")}, "rejections:", len(r["rejections"]))' "$REPORT"
  [[ -f "$RES/muse-request-live.json" ]] && { cmp -s "$RES/muse-request-live.json" "$RES/muse-request-preview.json" && echo "=== PASS: transmitted request is byte-identical to the reviewed preview" || echo "=== FAIL: transmitted request differs from the preview"; }
  if (( rc == 0 )) && grep -qE "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .*$TEST" "$OUT/$MODE.log"; then
    for c in A B C; do python3 -I eval/synthetic-lab/score.py "lab-$c-postgres-live" "$MODE" > "$RES/score-lab-$c-postgres-live.md" && tail -1 "$RES/score-lab-$c-postgres-live.md"; done
    python3 -I eval/synthetic-lab/compare.py postgres-live "$MODE" > "$RES/compare-postgres-live.md" && echo "comparison: $RES/compare-postgres-live.md"
    echo "=== PASS: live extraction recorded ($RES/muse-recording.json) and A/B/C answered on the real stack"; exit 0
  fi
  echo "=== FAIL: $TEST did not complete (rc=$rc); see $OUT/$MODE.log and $REPORT"; exit 1
fi
 env "$FLAG=yes" DITA_MUSE_PRICE_INPUT_PER_MTOK="$DITA_MUSE_PRICE_INPUT_PER_MTOK" DITA_MUSE_PRICE_OUTPUT_PER_MTOK="$DITA_MUSE_PRICE_OUTPUT_PER_MTOK" DITA_MUSE_MODEL="$MODEL" DITA_MUSE_CONNECTIVITY_TOKENS="${TOK:-128}" DITA_MUSE_RESPONSE_FORMAT="$FORMAT" DITA_MUSE_REASONING_EFFORT="$EFFORT" JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21} \
  mvn -o -pl dita-core,dita-muse test -Dtest="$TEST" -Dsurefire.failIfNoSpecifiedTests=false > "$OUT/$MODE.log" 2>&1
rc=$?

# N-34: scan EVERY artefact for the cleaned key BEFORE unsetting it.
LEAK=0
for f in "$OUT/$MODE.log" "$REPORT" dita-muse/target/surefire-reports/*; do
  [[ -f "$f" ]] && grep -qF -f <(printf '%s\n' "$MODEL_API_KEY") "$f" && { LEAK=1; echo "KEY FOUND IN $f: deleting"; rm -f "$f"; }
done
unset MODEL_API_KEY
(( LEAK == 0 )) || { echo "=== FAIL: key leaked into output (files deleted)"; exit 2; }
echo "=== PASS: no key in log, report or surefire output"

grep -E "Tests run:.*$TEST|BUILD" "$OUT/$MODE.log" | head -3
[[ -f "$REPORT" ]] && cat "$REPORT" || echo "(no report written)"
if grep -qE "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, .* in app\.dita\.muse\.$TEST" "$OUT/$MODE.log" \
   && [[ -f "$REPORT" ]] && grep -q '"status" : "OK"' "$REPORT" && (( rc == 0 )); then
  echo "=== PASS: $TEST executed (1 run, 0 failures, 0 skipped) with status OK"
else
  echo "=== FAIL: $TEST did not execute cleanly (rc=$rc); see $OUT/$MODE.log and the report above"; exit 1
fi
