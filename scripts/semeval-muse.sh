#!/usr/bin/env bash
# TASK-046 semantic evaluation: live Muse arms A3 (relation classification) / A6 (structured semantic extraction).
# operator approved up to $10 of Muse charges for this experiment (2026-11-01). The test enforces the cost ledger before EVERY call
# (eval/TASK-046/semeval/ledger.json): A6 <= $5.50, A3 <= $3.50, total stop at $9.50; null usage is charged at its worst case.
#   scripts/semeval-muse.sh <A3|A6> <dev|sealed> <reviewed-preview-sha256> [limit]
# The reviewed hash comes from eval/TASK-046/semeval/REVIEWED-PREVIEWS.md (Natasha N-113): the run refuses unless the freshly
# built preview equals EXACTLY what was reviewed.
# 1. builds the PREVIEW offline (no network) and shows its sha256 and worst-case cost; 2. asks you to type 'approve';
# 3. sends ONLY requests byte-identical to that preview to https://api.meta.ai/v1/chat/completions (muse-spark-1.3);
# 4. scans every artefact for the key. Synthetic case text only; authors pseudonymised. Re-running resumes, never repeats.
set -uo pipefail
cd "$(dirname "$0")/.."
ARM=${1:-}; SET=${2:-}; EXPECTED=${3:-}; LIMIT=${4:-}
[[ "$ARM" =~ ^(A3|A6)$ && "$SET" =~ ^(dev|sealed)$ && "$EXPECTED" =~ ^[0-9a-f]{64}$ ]] || { echo "usage: $0 <A3|A6> <dev|sealed> <reviewed-preview-sha256> [limit]"; exit 64; }
[[ -z "$LIMIT" || "$LIMIT" =~ ^[0-9]+$ ]] || { echo "limit must be a number"; exit 64; }
for v in SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS MAVEN_OPTS JDK_JAVA_OPTIONS; do
  [[ -z "${!v:-}" ]] || { echo "$v is set; unset it first. Nothing sent."; exit 1; }
done
OVR=$(env | cut -d= -f1 | grep -E '^DITA_(MUSE|SEMEVAL)_' || true)
[[ -z "$OVR" ]] || { echo "unexpected overrides in the environment: $OVR. Nothing sent."; exit 1; }
REC=eval/TASK-046/semeval/recordings/$SET
LIM=(); [[ -n "$LIMIT" ]] && LIM=(-Ddita.semeval.limit="$LIMIT")
JH=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OUT=build/semeval; mkdir -p "$OUT"

echo "Building the request preview offline (no network, its own JVM: N-111)…"
JAVA_HOME=$JH mvn -o -q -P semeval -pl dita-semeval -am test -Dtest=SemEvalMuseTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddita.semeval.muse=PREVIEW -Ddita.semeval.arm="$ARM" -Ddita.semeval.set="$SET" "${LIM[@]}" > "$OUT/$ARM-$SET-preview.log" 2>&1 \
  || { echo "preview failed; see $OUT/$ARM-$SET-preview.log. Nothing sent."; exit 1; }
P=$REC/$ARM-preview.json
[[ -f "$P" ]] || { echo "no preview written; nothing sent"; exit 1; }
SHA=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["requestsSha256"])' "$P")
[[ "$SHA" == "$EXPECTED" ]] || { echo "the preview built now ($SHA) is not the reviewed one ($EXPECTED); nothing sent (N-113)"; exit 1; }
SUMMARY=$(python3 -I -c 'import json,sys; p=json.load(open(sys.argv[1])); print(f"{p[\"cases\"]} cases in {p[\"batches\"]} calls, {p[\"totalRequestBytes\"]} request bytes, max_completion_tokens {p[\"maxCompletionTokens\"]}, worst case ${p[\"worstCaseUsdTotal\"]} (${p[\"worstCaseUsdPerCall\"]} per call)")' "$P")
SPENT=$(python3 -I -c 'import json,sys,os; f=sys.argv[1]; print(json.load(open(f))["totalUsd"] if os.path.exists(f) else 0)' eval/TASK-046/semeval/ledger.json)

# N-34: strict key parsing (same as muse-live.sh)
LINES=$(grep -cE '^[[:space:]]*(export[[:space:]]+)?MODEL_API_KEY=' .env.local 2>/dev/null || true)
[[ "$LINES" == 1 ]] || { echo "expected exactly one MODEL_API_KEY= line in .env.local (found ${LINES:-0})"; exit 1; }
RAW=$(grep -E '^[[:space:]]*(export[[:space:]]+)?MODEL_API_KEY=' .env.local | tr -d '\r')
VAL=${RAW#*MODEL_API_KEY=}
VAL="${VAL#"${VAL%%[![:space:]]*}"}"; VAL="${VAL%"${VAL##*[![:space:]]}"}"
if [[ "$VAL" =~ ^\"(.*)\"$ || "$VAL" =~ ^\'(.*)\'$ ]]; then VAL=${BASH_REMATCH[1]}; fi
[[ -n "$VAL" && ! "$VAL" =~ [[:space:]#\"\'] ]] || { echo "MODEL_API_KEY value in .env.local has spaces, quotes or a comment; fix the line"; exit 1; }
export MODEL_API_KEY="$VAL"; unset RAW VAL

cat <<INFO
About to make LIVE Muse calls for TASK-046 arm $ARM on the $SET set:
  destination : https://api.meta.ai/v1/chat/completions (muse-spark-1.3, reasoning_effort=low, temperature 0, schema in prompt)
  data sent   : SYNTHETIC evaluation cases only (eval/TASK-046/semeval/$SET), authors pseudonymised (P1, P2…), no ids or names
                beyond the fictional message text; exact requests = preview sha256 ${SHA:0:16}…
  calls       : $SUMMARY; no retries; resumes without repeating recorded batches
  budget      : spent so far \$$SPENT of the approved \$10; the test stops before any call whose worst case would pass
                \$9.50 total or the arm cap (A6 \$5.50, A3 \$3.50)
  key         : MODEL_API_KEY from .env.local (${#MODEL_API_KEY} characters; never printed or logged)
INFO
read -r -p "Type 'approve' to send these requests: " ok
[[ "$ok" == approve ]] || { unset MODEL_API_KEY; echo "cancelled; nothing sent"; exit 1; }

rm -rf dita-semeval/target/surefire-reports
env DITA_SEMEVAL_APPROVED="approved-$SHA" JAVA_HOME=$JH \
  mvn -o -q -P semeval -pl dita-semeval -am test -Dtest=SemEvalMuseTest -Dsurefire.failIfNoSpecifiedTests=false \
      -Ddita.semeval.muse=LIVE -Ddita.semeval.arm="$ARM" -Ddita.semeval.set="$SET" "${LIM[@]}" > "$OUT/$ARM-$SET-live.log" 2>&1
rc=$?
LEAK=0
for f in "$OUT"/* "$REC"/* eval/TASK-046/semeval/ledger.json dita-semeval/target/surefire-reports/*; do
  [[ -f "$f" ]] && grep -qF -f <(printf '%s\n' "$MODEL_API_KEY") "$f" && { LEAK=1; echo "KEY FOUND IN $f: deleting"; rm -f "$f"; }
done
unset MODEL_API_KEY
(( LEAK == 0 )) || { echo "=== FAIL: key leaked into output (files deleted)"; exit 2; }
echo "=== PASS: no key in logs, recordings, ledger or surefire output"
grep -h "BUDGET STOP" "$OUT/$ARM-$SET-live.log" dita-semeval/target/surefire-reports/*-output.txt 2>/dev/null | head -2
python3 -I -c 'import json,sys; l=json.load(open(sys.argv[1])); print("ledger:", l)' eval/TASK-046/semeval/ledger.json 2>/dev/null
[[ -f "$REC/$ARM-responses.jsonl" ]] && echo "recorded batches: $(grep -c . "$REC/$ARM-responses.jsonl") (errors: $(grep -c '"error"' "$REC/$ARM-responses.jsonl"))"
(( rc == 0 )) && echo "=== DONE ($ARM $SET). Send the log path to Fury: $OUT/$ARM-$SET-live.log" || { echo "=== FAIL (rc=$rc); see $OUT/$ARM-$SET-live.log"; exit 1; }
