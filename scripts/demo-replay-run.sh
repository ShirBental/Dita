#!/usr/bin/env bash
# TASK-051 demo run: DitaApplication on the REAL stack (PostgreSQL + Neo4j + Vespa, compose project "dita") with a
# SYNTHETIC Telegram replay through the real poller and adapter, and RECORDED Muse replies.
#   scripts/demo-replay-run.sh --chats <negative ids,…> --authors <user ids,…> --approver <user id> [replay.jsonl] [recording.json]
# The allow-lists are explicit operator arguments (Natasha M-1), never derived from the replay file. The replay file must
# start with {"replay":"synthetic-fixture"} (the operator declares it synthetic; never a real chat export).
# Never contacts Telegram, never calls Muse (REPLAY requires transport RECORDED; the API key is not passed), never
# migrates. Inserts SYNTHETIC rows only. Refuses to start while another Dita process is running (Natasha M-3).
# Dita's outgoing replies (each /ask question, the exact answer text and its source citations) are written, as they are
# sent, to build/demo-replay-run/transcript.jsonl and transcript.txt, and printed to the terminal (so also run.log).
# Each run overwrites them. They are NOT redacted by /forget: acceptable for synthetic fixtures only (Natasha L-4).
set -uo pipefail
cd "$(dirname "$0")/.."
CHATS=""; AUTHORS=""; APPROVER=""
while [[ $# -gt 0 && "$1" == --* ]]; do
  case "$1" in
    --chats) CHATS=$2; shift 2 ;;
    --authors) AUTHORS=$2; shift 2 ;;
    --approver) APPROVER=$2; shift 2 ;;
    *) echo "unknown option $1"; exit 64 ;;
  esac
done
REPLAY=${1:-dita-app/src/test/resources/replay/parents-levi.replay.jsonl}
REC=${2:-dita-app/src/test/resources/replay/parents-levi.muse-recording.json}
[[ "$CHATS" =~ ^-[0-9]+(,-[0-9]+)*$ && "$AUTHORS" =~ ^[0-9]+(,[0-9]+)*$ && "$APPROVER" =~ ^[0-9]+$ ]] \
  || { echo "usage: $0 --chats -1001000000052 --authors 1001,1002,1003,1009 --approver 1009 [replay] [recording]"; exit 64; }
OUT=build/demo-replay-run; mkdir -p "$OUT"
JAVA_HOME=/opt/homebrew/opt/openjdk@21                     # fixed (Natasha L-3), not taken from the caller
for f in "$REPLAY" "$REC"; do [[ -r "$f" ]] || { echo "not readable: $f"; exit 2; }; done
[[ "$(head -1 "$REPLAY")" == '{"replay":"synthetic-fixture"}' ]] || { echo "replay file lacks the synthetic-fixture header; refusing"; exit 2; }
if pgrep -f 'app.dita.app.DitaApplication' >/dev/null; then echo "another Dita process is running; stop it first (M-3)"; exit 2; fi
scripts/knowledge-verify.sh --check > "$OUT/check.log" 2>&1 && grep -q 'V5: applied' "$OUT/check.log" \
  || { echo "real stack not ready (see $OUT/check.log); nothing started"; exit 2; }
mvn -o -q -pl dita-app -am compile dependency:build-classpath -Dmdep.includeScope=runtime \
    -Dmdep.outputFile="$PWD/$OUT/classpath.txt" > "$OUT/build.log" 2>&1 || { echo "build failed (see $OUT/build.log)"; exit 1; }
CP="$PWD/dita-app/target/classes:$(cat "$OUT/classpath.txt")"
TRANSCRIPT="$PWD/$OUT/transcript.jsonl"
echo "Replaying $(shasum -a 256 "$REPLAY" | cut -c1-16) (chats $CHATS, authors $AUTHORS, approver $APPROVER) on POSTGRES + Neo4j + Vespa; Muse RECORDED."
echo "Dita's replies: $TRANSCRIPT (readable: ${TRANSCRIPT%.jsonl}.txt). Stop with Ctrl-C after the /ask answer appears."
APP_PW=${DITA_APP_PASSWORD:-dita-app-dev-not-secret}; RELAY_PW=${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}
NEO_PW=${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}
PGURL="jdbc:postgresql://127.0.0.1:${DITA_DB_PORT:-55432}/${DITA_DB_NAME:-dita}"; NEOURL="http://127.0.0.1:${DITA_NEO4J_HTTP_PORT:-57474}"; VESPAURL="http://127.0.0.1:${DITA_VESPA_QUERY_PORT:-58080}"
(
  # Clean environment without putting passwords in any process's argv (Natasha L-2).
  for v in $(env | cut -d= -f1); do case "$v" in PATH|HOME|APP_PW|RELAY_PW|NEO_PW|JAVA_HOME|CP|REPLAY|REC|CHATS|AUTHORS|APPROVER|OUT|PGURL|NEOURL|VESPAURL|TRANSCRIPT) ;; *) unset "$v" 2>/dev/null ;; esac; done
  export DITA_APP_DB_PASSWORD="$APP_PW" DITA_RELAY_DB_PASSWORD="$RELAY_PW" DITA_NEO4J_PASSWORD="$NEO_PW"
  unset APP_PW RELAY_PW NEO_PW
  exec "$JAVA_HOME/bin/java" -cp "$CP" app.dita.app.DitaApplication \
    --dita.storage.backend=POSTGRES \
    --dita.storage.postgres.url="$PGURL" \
    --dita.storage.postgres.app-password-env-var=DITA_APP_DB_PASSWORD --dita.storage.postgres.relay-password-env-var=DITA_RELAY_DB_PASSWORD \
    --dita.storage.neo4j.url="$NEOURL" --dita.storage.neo4j.password-env-var=DITA_NEO4J_PASSWORD \
    --dita.storage.vespa.url="$VESPAURL" \
    --dita.telegram.enabled=true --dita.telegram.mode=REPLAY --dita.telegram.replay.file="$REPLAY" \
    --dita.telegram.replay.extraction-approver-ids="$APPROVER" \
    --dita.telegram.replay.transcript-file="$TRANSCRIPT" --dita.telegram.replay.transcript-echo=true \
    --dita.telegram.allowed-chat-ids="$CHATS" --dita.telegram.allowed-author-ids="$AUTHORS" --dita.telegram.poll-timeout=1s \
    --dita.muse.enabled=true --dita.muse.transport=RECORDED --dita.muse.recording-file="$REC" \
    --dita.coordination.enabled=false --spring.main.keep-alive=true
) 2>&1 | tee "$OUT/run.log"
