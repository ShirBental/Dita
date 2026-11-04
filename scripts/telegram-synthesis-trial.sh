#!/usr/bin/env bash
# TASK-054: CONTROLLED SYNTHETIC TRIAL in SyntheticDemoGroup through the PRODUCTION knowledge + Muse answer-synthesis path.
#   bash scripts/telegram-synthesis-trial.sh [postgres|inmemory] [semantic|keyword|knowledge]   (defaults: postgres, semantic)
# What runs (no typing in this terminal after launch; everything happens in SyntheticDemoGroup):
#   - LIVE polling of @ExampleDitaBot, chat -1001000000054 only, sender 1001 only; updates dated before start are ignored.
#   - The preauthorized session starts silently with no setup message or confirmation button.
#   - New ordinary messages from the authorized senders are stored silently as SYNTHETIC (trial namespace).
#     Other senders, earlier messages, forwards and bots are ignored.
#   - "/ask <question>": Dita selects the messages relevant to the question with the answer path's own retrieval,
#     prepares only those that have no knowledge yet (at most 4 per extraction call), then answers. Paid Muse calls are
#     preauthorized for this process (<= $1 in conservative reservations, <= 8 calls); there are no per-call approval
#     buttons. No retries; no background worker. Every request sent is recorded in the run's evidence.
#   - SYNTHESIS_PROMPT=V1|V2|V3 selects the answer prompt (default V3 = muse-answer-v3); recorded in run.json.
#   - Refused or failed synthesis: Dita replies with its deterministic answer, marked "[trial] Not a Muse answer".
# Credentials: TELEGRAM_BOT_TOKEN and MODEL_API_KEY are read from .env.local (one line each), passed only through the
# environment (never argv), never printed; logs and evidence are scanned for both after the run.
# Evidence (synthetic content only, not redacted by /forget): build/telegram-synthesis-trial/<run-tag>/
# Stop: Ctrl-C.
set -uo pipefail
PROMPT="${SYNTHESIS_PROMPT:-V3}"
[[ "$PROMPT" =~ ^V[123]$ ]] || { echo "SYNTHESIS_PROMPT must be V1, V2 or V3"; exit 2; }
cd "$(dirname "$0")/.."
BACKEND=${1:-postgres}
USAGE="usage: $0 [postgres|inmemory] [semantic|keyword|knowledge]"
case "$BACKEND" in postgres|inmemory) ;; *) echo "$USAGE"; exit 64 ;; esac
# semantic (default, the target demo): raw messages via local bge-small semantic search + BM25 + reply links, ONE
#   synthesis call, no extraction. Needs the TASK-054 Vespa package deployed (scripts/task054-semantic-deploy.sh);
#   otherwise Dita refuses to start (it never silently falls back to keyword-only).
# keyword: INTERIM - the same without semantic search. knowledge: the extraction-based mode (DEC-046/047).
MODE=${2:-semantic}
SEMANTIC=false
case "$MODE" in semantic) ANSWER_MODE=MESSAGES; SEMANTIC=true ;; keyword|messages) ANSWER_MODE=MESSAGES ;; knowledge) ANSWER_MODE=KNOWLEDGE ;; *) echo "$USAGE"; exit 64 ;; esac
[[ $SEMANTIC == true && $BACKEND != postgres ]] && { echo "semantic retrieval needs postgres (the embedder lives in Vespa)"; exit 64; }
source scripts/telegram-local-config.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21
OUT=build/telegram-synthesis-trial; mkdir -p "$OUT"
for t in mvn openssl python3; do command -v "$t" >/dev/null || { echo "required tool '$t' not found"; exit 1; }; done
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21' || { echo "JAVA_HOME=$JAVA_HOME is not Java 21"; exit 1; }

# Local configuration is validated by telegram-local-config.sh; defaults stay synthetic.


if [[ $BACKEND == postgres ]]; then
  bash scripts/knowledge-verify.sh --check > "$OUT/check.log" 2>&1 && grep -q 'V5: applied' "$OUT/check.log" \
    || { echo "real stack not ready (see $OUT/check.log); nothing started"; exit 2; }
  sessions=$(docker compose -p dita -f docker/compose.yml exec -T postgres psql -X -U dita -d dita -Atc "select count(*) from pg_stat_activity where usename in ('dita_app','dita_relay')")
  [[ "$sessions" == 0 ]] || { echo "active Dita database sessions; stop other runs first"; exit 2; }
fi

# One assignment line per key in .env.local; nothing else is read from that file.
read_key() {   # $1 = variable name; prints the cleaned value
  local n=$1 lines raw v
  lines=$(grep -cE "^[[:space:]]*(export[[:space:]]+)?$n=" .env.local 2>/dev/null || true)
  [[ "$lines" == 1 ]] || { echo "expected exactly one $n= line in .env.local (found ${lines:-0})" >&2; return 1; }
  raw=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$n=" .env.local | tr -d '\r'); v=${raw#*$n=}
  v="${v#"${v%%[![:space:]]*}"}"; v="${v%"${v##*[![:space:]]}"}"
  if [[ "$v" =~ ^\"(.*)\"$ || "$v" =~ ^\'(.*)\'$ ]]; then v=${BASH_REMATCH[1]}; fi
  [[ -n "$v" && ! "$v" =~ [[:space:]#\"\'] ]] || { echo "$n value in .env.local has spaces, quotes or a comment" >&2; return 1; }
  printf '%s' "$v"
}
[[ -f .env.local ]] || { echo "no .env.local"; exit 1; }
TOKEN=$(read_key TELEGRAM_BOT_TOKEN) || exit 1
[[ "$TOKEN" =~ ^[0-9]{5,16}:[A-Za-z0-9_-]{30,64}$ ]] || { unset TOKEN; echo "TELEGRAM_BOT_TOKEN is not a bot token shape (value not shown)"; exit 1; }
MKEY=$(read_key MODEL_API_KEY) || { unset TOKEN; exit 1; }
trap 'unset TOKEN MKEY SECRET' EXIT

echo "## build (offline; no credentials in the build environment)"
env -u TOKEN -u MKEY mvn -o -q -pl dita-app -am compile dependency:build-classpath -Dmdep.includeScope=runtime \
    -Dmdep.outputFile="$PWD/$OUT/classpath.txt" > "$OUT/build.log" 2>&1 || { echo "build failed (see $OUT/build.log)"; exit 1; }
CP="$PWD/dita-app/target/classes:$(cat "$OUT/classpath.txt")"

STORE=(--dita.storage.backend=IN_MEMORY)
if [[ $BACKEND == postgres ]]; then
  STORE=(--dita.storage.backend=POSTGRES --dita.storage.postgres.url=jdbc:postgresql://127.0.0.1:55432/dita
         --dita.storage.postgres.app-password-env-var=DITA_APP_DB_PASSWORD --dita.storage.postgres.relay-password-env-var=DITA_RELAY_DB_PASSWORD
         --dita.storage.neo4j.url=http://127.0.0.1:57474 --dita.storage.neo4j.password-env-var=DITA_NEO4J_PASSWORD
         --dita.storage.vespa.url=http://127.0.0.1:58080)
fi
MEMBER_ARGS=()
IFS=',' read -r -a SESSION_MEMBERS <<< "$AUTHORS"
for i in "${!SESSION_MEMBERS[@]}"; do
  name="P$((i+1))"; [[ $i == 0 ]] && name=Alice; [[ $i == 1 ]] && name=Bob
  MEMBER_ARGS+=("--dita.telegram.synthetic-trial.member-names.${SESSION_MEMBERS[$i]}=$name")
done
ARGS=(--spring.main.keep-alive=true
      --dita.telegram.enabled=true --dita.telegram.mode=LIVE --dita.telegram.bot-username=$BOT
      --dita.telegram.allowed-chat-ids=$CHAT --dita.telegram.allowed-author-ids=$AUTHORS
      --dita.telegram.synthetic-trial.enabled=true --dita.telegram.synthetic-trial.approver-ids=$AUTHOR --dita.telegram.synthetic-trial.session-author-ids=$AUTHORS
      "${MEMBER_ARGS[@]}"
      --dita.telegram.synthetic-trial.evidence-dir="$PWD/$OUT" --dita.telegram.synthetic-trial.max-paid-calls=8
      --dita.telegram.synthetic-trial.approval-timeout=10m --dita.telegram.synthetic-trial.session-budget-usd=1
      --dita.muse.enabled=true --dita.muse.transport=HTTP --dita.muse.chat-completions-url=https://api.meta.ai/v1/chat/completions
      --dita.muse.model=muse-spark-1.3 --dita.muse.max-output-tokens=4096 --dita.muse.timeout=60s --dita.muse.max-calls-per-process=6
      --dita.muse.response-format=PROMPT_ONLY --dita.muse.reasoning-effort=LOW
      --dita.memory.answers.synthesis.enforce-language-rules=false --dita.memory.answers.synthesis.show-sources=false
      --dita.memory.answers.synthesis.enabled=true --dita.memory.answers.synthesis.allow-live=true "--dita.memory.answers.synthesis.prompt=$PROMPT"
      --dita.extraction.worker-enabled=false --dita.coordination.enabled=false
      "${STORE[@]}")

cat <<EOF
## starting the controlled synthetic trial (nothing to type here; Ctrl-C stops it)
  - @$BOT, chat $CHAT (SyntheticDemoGroup) only, senders $AUTHORS; operator $AUTHOR; storage: $BACKEND
  - Session starts silently under the operator’s existing authorization; no setup message or confirmation button
  - Muse: muse-spark-1.3 at https://api.meta.ai/v1/chat/completions, prompt $PROMPT (muse-answer-v3 unless SYNTHESIS_PROMPT is set), max_completion_tokens 4096
    (incl. reasoning), reasoning low, timeout 60s, no retries, <= 8 paid calls, preauthorized up to \$1 in conservative reservations for this process; no per-call approvals
  - evidence: $OUT/<run-tag>/ ; log: $OUT/run.log
EOF
ARGS+=(--dita.telegram.synthetic-trial.session-declaration=PREAUTHORIZED_SESSION --dita.telegram.synthetic-trial.answer-mode=$ANSWER_MODE
       --dita.retrieval.dense.messages=$SEMANTIC)
# Optional third argument identifies a recorded session, avoiding an easily omitted environment prefix.
if [[ -n "${3:-}" ]]; then
  [[ "$3" =~ ^--continue-session=(run-[A-Za-z0-9-]+)$ ]] || { echo 'Invalid continuation argument'; exit 64; }
  selected=${BASH_REMATCH[1]}
  window=$(python3 -I - "$OUT" "$selected" <<'WINDOW'
from pathlib import Path
import json,sys
from datetime import datetime
root=Path(sys.argv[1]); target=root/sys.argv[2]/'session.json'
start=json.loads(target.read_text())['confirmed_at']
inst=lambda s: datetime.fromisoformat(s.replace('Z','+00:00'))
records=[json.loads(p.read_text())['confirmed_at'] for p in root.glob('run-*/session.json')]
later=sorted((x for x in records if inst(x)>inst(start)), key=inst)
print(start); print(later[0] if later else '')
WINDOW
  ) || exit 2
  CONTINUE_SESSION_FROM=$(printf '%s\n' "$window" | head -1)
  CONTINUE_SESSION_UNTIL=$(printf '%s\n' "$window" | sed -n '2p')
fi
# TASK-054 (DEC-050) explicit continuation of ONE earlier session: CONTINUE_SESSION_FROM=<its session.json confirmed_at>.
# Messages sent since that instant are used for answers; older history stays excluded. Nothing is deleted or reset.
if [ -n "${CONTINUE_SESSION_FROM:-}" ]; then
  case "$ANSWER_MODE" in MESSAGES) ;; *) echo "CONTINUE_SESSION_FROM needs semantic or keyword mode"; exit 64 ;; esac
  ARGS+=(--dita.telegram.synthetic-trial.continue-session-from="$CONTINUE_SESSION_FROM")
  if [[ -n "${CONTINUE_SESSION_UNTIL:-}" ]]; then
    ARGS+=(--dita.telegram.synthetic-trial.continue-session-until="$CONTINUE_SESSION_UNTIL")
  fi
  echo "  - continuing the session confirmed at $CONTINUE_SESSION_FROM (its messages are used; older ones are not)"
fi
case "$MODE" in
  semantic) echo "  - mode: semantic (bge-small in Vespa + BM25 + reply links; no extraction; one synthesis call per answered question)" ;;
  knowledge) echo "  - mode: knowledge (extraction, then synthesis)" ;;
  *) echo "  - mode: keyword (INTERIM: BM25 + reply links only, no raw-message embeddings; no extraction)" ;;
esac

SECRET=$(openssl rand -hex 32)
echo "## running (Ctrl-C to stop)"
# Ctrl-C must stop java but NOT this script, so the credential scan and summary below always run (Natasha F1).
# Secrets are exported inside a subshell and java is exec'd with a cleaned environment: they never appear in any argv
# (Natasha F7; `env -i NAME=value` would put them in env's argv until it execs).
trap ':' INT
(
  trap - INT
  export TELEGRAM_BOT_TOKEN="$TOKEN" DITA_CALLBACK_SECRET="$SECRET" MODEL_API_KEY="$MKEY"
  export DITA_APP_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" DITA_RELAY_DB_PASSWORD="${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}"
  export DITA_NEO4J_PASSWORD="${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}"
  keep=" PATH HOME JAVA_HOME LANG TELEGRAM_BOT_TOKEN DITA_CALLBACK_SECRET MODEL_API_KEY DITA_APP_DB_PASSWORD DITA_RELAY_DB_PASSWORD DITA_NEO4J_PASSWORD "
  for v in $(compgen -e); do [[ "$keep" == *" $v "* ]] || unset "$v" 2>/dev/null; done
  export PATH=/usr/bin:/bin LANG=en_US.UTF-8 HOME JAVA_HOME
  exec "$JAVA_HOME/bin/java" -cp "$CP" app.dita.app.DitaApplication "${ARGS[@]}"
) 2>&1 | tee -i "$OUT/run.log"
trap - INT

# After stop: no credential may appear in the log or the evidence.
TOKEN="$TOKEN" MKEY="$MKEY" python3 -I - "$OUT" <<'PY'
import os, sys
from pathlib import Path
keys = [os.environ['TOKEN'].encode(), os.environ['MKEY'].encode()]
bad = [p for p in Path(sys.argv[1]).rglob('*') if p.is_file() and any(k in p.read_bytes() for k in keys)]
print('STOP: credential found in ' + ', '.join(map(str, bad)) + '; do not share these files' if bad else 'OK: no credential in log or evidence')
PY
echo "## summary of this run's requests"
python3 -I - "$OUT" <<'PY'
import json, sys
from pathlib import Path
runs = sorted(p for p in Path(sys.argv[1]).glob('run-*') if p.is_dir())
if not runs: raise SystemExit('no run directory')
r = runs[-1]; print('run:', r)
for f in sorted(r.glob('extract-*.json')) + sorted(r.glob('ask-*.json')):
    e = json.loads(f.read_text())
    calls = [(h.get('approval'), (h.get('http') or {}).get('http_status'), (h.get('http') or {}).get('latency_ms'), (h.get('http') or {}).get('cost_usd')) for h in e.get('http', [])]
    if e['kind'] == 'extract': print(f.name, e['batch'], e['status'], 'accepted', e['accepted'], 'calls', calls)
    else:
        prep = [(x.get('batch'), x.get('status'), x.get('decision')) for x in e.get('preparation', [])]
        if e.get('stopped'): print(f.name, repr(e['question']), 'STOPPED', e['stopped'], 'preparation', prep)
        else: print(f.name, repr(e['question']), 'preparation', prep, 'projections_ready', e.get('projections_ready'), e.get('synthesis_outcomes'),
                    'synthesized', e.get('synthesis_succeeded'), 'fallback', e.get('deterministic_fallback'), 'sources', e.get('final_sources'), 'calls', calls)
PY
