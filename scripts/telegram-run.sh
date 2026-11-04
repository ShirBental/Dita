#!/usr/bin/env bash
# TASK-040: FIRST CONTROLLED LIVE TELEGRAM RUN of Dita (@ExampleDitaBot in SyntheticDemoGroup). Run LOCALLY; stop with Ctrl-C.
#   - Polls ONLY chat -1001000000054 and accepts ONLY author 1001; everyone else is ignored before any write/reply.
#   - Updates dated before this start are acknowledged and never processed (no backlog, no re-answers after restart).
#   - Muse is OFF (forced on the command line); MODEL_API_KEY and every DITA_* override are removed (env -i).
#   - Memory is IN_MEMORY: it lives only in this process and is gone when it stops. No database, no Docker.
#   - Coordination (/plan, buttons) is OFF for this run; /ask and /forget answer deterministically (no model).
#   - The token is read only from .env.local (TELEGRAM_BOT_TOKEN), is never in argv or logs; the callback secret is
#     random per run and kept in memory only.
#   - Telegram side effects: replies posted in SyntheticDemoGroup; getUpdates stores the bot's allowed_updates setting
#     (message, edited_message, callback_query, my_chat_member).
# Log (no message text, no token): build/telegram-run/run.log
set -uo pipefail
cd "$(dirname "$0")/.."
unset MODEL_API_KEY
source scripts/telegram-local-config.sh
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OUT=build/telegram-run; mkdir -p "$OUT"
for t in mvn openssl; do command -v "$t" >/dev/null || { echo "required tool '$t' not found"; exit 1; }; done
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21' || { echo "JAVA_HOME=$JAVA_HOME is not Java 21"; exit 1; }

# Local configuration is validated by telegram-local-config.sh; defaults stay synthetic.


# Token: exactly one TELEGRAM_BOT_TOKEN= line in .env.local; nothing else is read from that file.
[[ -f .env.local ]] || { echo "no .env.local in $(pwd)"; exit 1; }
LINES=$(grep -cE '^[[:space:]]*(export[[:space:]]+)?TELEGRAM_BOT_TOKEN=' .env.local 2>/dev/null || true)
[[ "$LINES" == 1 ]] || { echo "expected exactly one TELEGRAM_BOT_TOKEN= line in .env.local (found ${LINES:-0})"; exit 1; }
RAW=$(grep -E '^[[:space:]]*(export[[:space:]]+)?TELEGRAM_BOT_TOKEN=' .env.local | tr -d '\r')
TOKEN=${RAW#*TELEGRAM_BOT_TOKEN=}; unset RAW
TOKEN="${TOKEN#"${TOKEN%%[![:space:]]*}"}"; TOKEN="${TOKEN%"${TOKEN##*[![:space:]]}"}"
if [[ "$TOKEN" =~ ^\"(.*)\"$ || "$TOKEN" =~ ^\'(.*)\'$ ]]; then TOKEN=${BASH_REMATCH[1]}; fi
[[ "$TOKEN" =~ ^[0-9]{5,16}:[A-Za-z0-9_-]{30,64}$ ]] || { unset TOKEN; echo "TELEGRAM_BOT_TOKEN is not a bot token shape (value not shown)"; exit 1; }
trap 'unset TOKEN SECRET' EXIT

echo "## build (offline; no token in the build environment)"
env -u TOKEN mvn -o -q -pl dita-app -am compile dependency:build-classpath -Dmdep.includeScope=runtime \
    -Dmdep.outputFile="$PWD/$OUT/classpath.txt" > "$OUT/build.log" 2>&1 || { echo "build failed (see $OUT/build.log)"; exit 1; }
CP="$PWD/dita-app/target/classes:$(cat "$OUT/classpath.txt")"

ARGS=(--spring.main.keep-alive=true
      --dita.telegram.enabled=true --dita.telegram.bot-username=$BOT
      --dita.telegram.allowed-chat-ids=$CHAT --dita.telegram.allowed-author-ids=$AUTHOR
      --dita.muse.enabled=false --dita.processing.tiers.t1.provider=NONE --dita.processing.tiers.t2.provider=NONE
      --dita.storage.backend=IN_MEMORY --dita.coordination.enabled=false)

cat <<EOF
## approval
This starts LIVE polling of @$BOT:
  - chat $CHAT (SyntheticDemoGroup) only; author $AUTHOR only; messages from before now are ignored
  - Dita stores the accepted messages IN MEMORY (lost on stop) and answers /ask and /forget in SyntheticDemoGroup
  - Muse OFF; MODEL_API_KEY not passed; no database; coordination off
  - Telegram stores the bot's allowed_updates setting (message, edited_message, callback_query, my_chat_member)
  - type SYNTHETIC text only: no forwards, no inline bots, no real names or details
Stop at any time with Ctrl-C.
EOF
read -r -p "Type 'approve' to start polling: " ans || ans=""
[[ "$ans" == approve ]] || { echo "not approved; nothing started"; exit 2; }

SECRET=$(openssl rand -hex 32)
echo "## running (Ctrl-C to stop); log: $OUT/run.log"
env -i PATH=/usr/bin:/bin HOME="$HOME" JAVA_HOME="$JAVA_HOME" LANG=en_US.UTF-8 \
    TELEGRAM_BOT_TOKEN="$TOKEN" DITA_CALLBACK_SECRET="$SECRET" \
    "$JAVA_HOME/bin/java" -cp "$CP" app.dita.app.DitaApplication "${ARGS[@]}" 2>&1 | tee "$OUT/run.log"
