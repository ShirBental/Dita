#!/usr/bin/env bash
# Dita Postgres outbox spike (prototype). NOT RUN by the agent: the sandbox cannot reach the Docker socket or port 55432.
# Run on the developer's Mac, with the dita postgres container already up and healthy:
#   docker compose -f docker/compose.yml up -d postgres      # if not running (the script never starts or stops it)
#   bash prototypes/kafka-spike/bench-outbox.sh
#
# Isolation: everything happens in a THROWAWAY database `dita_bench` created inside the dita postgres container and
# dropped at the end (DROP DATABASE dita_bench WITH (FORCE)). A separate database rather than a schema inside `dita`
# because (a) DROP DATABASE dita_bench cannot touch a single object of the `dita` database, (b) the fixture reuses the
# real table names (outbox, outbox_consumer) and must not be resolvable from the app's search_path, and (c) dita's
# default privileges would otherwise grant dita_app access to the bench tables. The script refuses to run if
# dita_bench already exists without its marker comment, and only drops a database that carries the marker.
# It uses `docker compose exec` on the dita project only; it never runs up/down/rm and never touches volumes, .env or data/.
#
# Parts:
#   1. insert throughput: one bulk transaction, then concurrent publishers via pgbench
#      (plain vs. option B: transaction-scoped advisory lock serialising outbox inserts)
#   2. drain throughput: id cursor (current relay) vs. (txid, id) cursor below the xmin horizon (option A)
#   3. live race: concurrent publishers + live drainer for both models, then an audit of never-delivered rows
#   4. deterministic two-session reproduction of the cursor skip (session A commits a LOWER id AFTER session B),
#      followed by the same scenario under option A and option B
# Logs: build/kafka-spike/outbox-<UTC timestamp>/
# Knobs: N (200000 bulk rows), CLIENTS (8), DURATION (15 s), WORK_MS (2: in-transaction work after publish), BATCH (100)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$ROOT/build/kafka-spike/outbox-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1

DC=(docker compose -f "$ROOT/docker/compose.yml")   # dita project: used ONLY for `exec postgres`
OWNER=${DITA_DB_OWNER:-dita}
BENCH_DB=dita_bench
MARKER='dita-kafka-spike throwaway benchmark database; safe to drop'
N=${N:-200000}
CLIENTS=${CLIENTS:-8}
DURATION=${DURATION:-15}
WORK_MS=${WORK_MS:-2}
BATCH=${BATCH:-100}
LOCK_KEY=4920556707358330712          # 0x444954414F424F58 = "DITAOBOX"; must match proposed-relay-fix.md option B

step() { printf '\n=== %s  [%s]\n' "$1" "$(date -u +%H:%M:%S)"; }
note() { printf '%s\n' "$*" | tee -a "$OUT/summary.txt"; }
pg_admin() { "${DC[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$OWNER" -d postgres "$@"; }
pg()       { "${DC[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$OWNER" -d "$BENCH_DB" "$@"; }
pgq()      { pg -Atq -c "$1"; }
pgbench()  { "${DC[@]}" exec -T postgres pgbench -n -U "$OWNER" -d "$BENCH_DB" "$@"; }
put_file() { "${DC[@]}" exec -T postgres sh -c "cat > /tmp/dita-kafka-spike-$1"; }

marker_of() { pg_admin -Atq -c "SELECT coalesce(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '$BENCH_DB'"; }
exists()    { [[ "$(pg_admin -Atq -c "SELECT count(*) FROM pg_database WHERE datname = '$BENCH_DB'")" == 1 ]]; }

cleanup() {
  rc=$?
  for j in $(jobs -p); do kill "$j" 2>/dev/null || true; done
  step "cleanup: drop $BENCH_DB (only if it carries the marker) and temp files in the container"
  if exists && [[ "$(marker_of)" == "$MARKER" ]]; then
    pg_admin -c "DROP DATABASE $BENCH_DB WITH (FORCE)" || true
  elif exists; then
    echo "NOT dropping $BENCH_DB: marker missing (not created by this script)"
  fi
  "${DC[@]}" exec -T postgres sh -c 'rm -f /tmp/dita-kafka-spike-*' || true
  echo "logs: $OUT  (exit $rc)"
}

# ---------------------------------------------------------------- 0. pre-flight + create the throwaway database
step "0. pre-flight"
"${DC[@]}" ps postgres
[[ "$(pg_admin -Atq -c 'SELECT 1')" == 1 ]] || { echo "dita postgres is not reachable via docker compose exec"; exit 2; }
pg_admin -Atq -c "SELECT 'server ' || version()"
if exists; then
  echo "refusing: database $BENCH_DB already exists (marker: '$(marker_of)'). Inspect it and drop it yourself if it is stale."
  exit 2
fi
trap cleanup EXIT
pg_admin -c "CREATE DATABASE $BENCH_DB"
pg_admin -c "COMMENT ON DATABASE $BENCH_DB IS '$MARKER'"
pg -q -f - < "$HERE/bench-outbox.sql"
note "fixture loaded into $BENCH_DB; settings: N=$N CLIENTS=$CLIENTS DURATION=${DURATION}s WORK_MS=$WORK_MS BATCH=$BATCH"
pgq "SELECT 'synchronous_commit=' || current_setting('synchronous_commit') || ' shared_buffers=' || current_setting('shared_buffers')" | tee -a "$OUT/summary.txt"

reset_tables() { pgq "TRUNCATE outbox, outbox_consumer, delivered RESTART IDENTITY"; }
register()     { pgq "INSERT INTO outbox_consumer (name) VALUES $(printf "('%s')," "$@" | sed 's/,$//')"; }

# ---------------------------------------------------------------- 1. insert throughput
step "1a. bulk insert: $N synthetic ids-only rows in ONE transaction"
reset_tables
pg -c '\timing on' -c "INSERT INTO outbox (group_id, topic, payload)
  SELECT 'g-synth-' || (i % 50), 'message.created',
         jsonb_build_object('groupId', 'g-synth-' || (i % 50), 'messageId', 'm-synth-' || i)
  FROM generate_series(1, $N) AS i" | tee "$OUT/bulk-insert.log"
note "bulk_insert: $(grep -E '^Time:' "$OUT/bulk-insert.log" | tail -n 1) for $N rows"

step "2. (drain throughput) bulk drain of $N rows: id cursor vs (txid, id) cursor, batch $BATCH, one transaction per batch"
register bulk-id bulk-xid
pg -c "CALL drain_all('bulk-id', 'id', $BATCH)" -c "CALL drain_all('bulk-xid', 'xid', $BATCH)" 2>&1 | tee "$OUT/bulk-drain.log"
grep -o 'DRAIN.*' "$OUT/bulk-drain.log" | sed 's/^/bulk_/' | tee -a "$OUT/summary.txt"

# pgbench transaction = what IngestionPipeline does at the end of ingest(): publish MessageRef + one LinkRef, a little
# more in-transaction work, commit. Option B adds the advisory lock before the first outbox insert.
gen_pgbench() {   # $1 = plain|lock
  echo '\set g random(1, 50)'
  echo '\set m random(1, 1000000000)'
  echo 'BEGIN;'
  [[ "$1" == lock ]] && echo "SELECT pg_advisory_xact_lock($LOCK_KEY);"
  echo "INSERT INTO outbox (group_id, topic, payload) VALUES ('g-synth-' || :g, 'message.created', jsonb_build_object('groupId', 'g-synth-' || :g, 'messageId', 'm-synth-' || :m));"
  echo "\\sleep $WORK_MS ms"
  echo "INSERT INTO outbox (group_id, topic, payload) VALUES ('g-synth-' || :g, 'link.added', jsonb_build_object('groupId', 'g-synth-' || :g, 'linkKey', 'l-synth-' || :m));"
  echo 'COMMIT;'
}
gen_pgbench plain | put_file plain.sql
gen_pgbench lock  | put_file lock.sql

for v in plain lock; do
  step "1b. concurrent publishers ($v): $CLIENTS clients x ${DURATION}s, 2 outbox rows per transaction"
  reset_tables
  pgbench -c "$CLIENTS" -j "$(( CLIENTS < 4 ? CLIENTS : 4 ))" -T "$DURATION" -r -f "/tmp/dita-kafka-spike-$v.sql" | tee "$OUT/pgbench-$v.log"
  note "publish_$v: $(grep -E '^tps' "$OUT/pgbench-$v.log" | head -n 1) | $(grep -E 'latency average' "$OUT/pgbench-$v.log") | rows=$(pgq 'SELECT count(*) FROM outbox')"
done

# ---------------------------------------------------------------- 3. live race
step "3. live race: $CLIENTS publishers (plain, no lock) + live drainer polling both cursor models for ${DURATION}s"
reset_tables
register live-id live-xid
pg -c "CALL drain_live($((DURATION + 2)), $BATCH)" > "$OUT/live-drainer.log" 2>&1 &
DRAINER=$!
sleep 1
pgbench -c "$CLIENTS" -j "$(( CLIENTS < 4 ? CLIENTS : 4 ))" -T "$DURATION" -f /tmp/dita-kafka-spike-plain.sql > "$OUT/pgbench-live.log"
wait "$DRAINER"
pg -c "CALL drain_all('live-id', 'id', $BATCH)" -c "CALL drain_all('live-xid', 'xid', $BATCH)" >> "$OUT/live-drainer.log" 2>&1
pg -c "SELECT * FROM delivery_audit ORDER BY consumer" | tee "$OUT/live-audit.txt"
note "live_race rows=$(pgq 'SELECT count(*) FROM outbox')"
pgq "SELECT 'live_race ' || consumer || ': never_delivered=' || never_delivered || ' duplicated=' || duplicated FROM delivery_audit ORDER BY consumer" \
  | tee -a "$OUT/summary.txt"
note "(live-id never_delivered > 0 means the current relay's cursor skipped committed rows; it is timing-dependent, so 0 here does not refute the bug - part 4 is the deterministic proof)"

# ---------------------------------------------------------------- 4. deterministic two-session reproduction
# Session A: BEGIN; INSERT (gets the LOWER id) ... stays open for 6 s ... COMMIT.
# Session B: starts 2 s later, INSERT (HIGHER id), autocommit.
# Relay pass at t=3 s sees only B's row and advances its cursor past A's id. A commits at t=6 s; its row is never read.
session_a() {   # $1 = extra SQL before the insert (lock or nothing)
  pg -Atq <<SQL
BEGIN;
$1
INSERT INTO outbox (group_id, topic, payload)
  VALUES ('g-synth-a', 'message.deleted', '{"groupId":"g-synth-a","messageId":"m-synth-a1","mode":"FORGET","source":"SYNTHETIC"}')
  RETURNING 'session A inserted id=' || id || ' txid=' || txid || ' (uncommitted until t=6s)';
SELECT pg_sleep(6);
COMMIT;
SQL
}
session_b() {
  pg -Atq <<SQL
BEGIN;
$1
INSERT INTO outbox (group_id, topic, payload)
  VALUES ('g-synth-b', 'message.created', '{"groupId":"g-synth-b","messageId":"m-synth-b1"}')
  RETURNING 'session B inserted id=' || id || ' txid=' || txid || ' at ' || clock_timestamp()::time;
COMMIT;
SELECT 'session B committed at ' || clock_timestamp()::time;
SQL
}
relay_pass() { pgq "SELECT 'relay pass $1: drain_id delivered ' || drain_id('repro-id') || ', drain_xid delivered ' || drain_xid('repro-xid')"; }
show() {
  pg -c "SELECT id, txid, topic, payload->>'messageId' AS message FROM outbox ORDER BY id"
  pg -c "SELECT name, last_id, last_txid FROM outbox_consumer ORDER BY name"
  pg -c "SELECT consumer, outbox_id FROM delivered ORDER BY consumer, delivered_at"
}

step "4a. reproduction: current relay (id cursor) vs option A (txid horizon), no lock"
reset_tables; register repro-id repro-xid
session_a "" & A=$!
sleep 2
session_b ""
sleep 1
relay_pass "t=3s (A still open)"
wait "$A"
relay_pass "t=6s+ (A committed)"
relay_pass "final"
show | tee "$OUT/repro-a.txt"
SKIPPED_ID=$(pgq "SELECT never_delivered FROM delivery_audit WHERE consumer = 'repro-id'")
SKIPPED_XID=$(pgq "SELECT never_delivered FROM delivery_audit WHERE consumer = 'repro-xid'")
if [[ "$SKIPPED_ID" -gt 0 ]]; then note "repro_current_relay=SKIP REPRODUCED: repro-id never received $SKIPPED_ID row(s), incl. the message.deleted from session A"
else note "repro_current_relay=skip NOT reproduced (unexpected; inspect $OUT/repro-a.txt)"; fi
if [[ "$SKIPPED_XID" == 0 ]]; then note "repro_option_A=PASS: repro-xid received both rows"
else note "repro_option_A=FAIL: repro-xid never received $SKIPPED_XID row(s)"; fi

step "4b. reproduction under option B (advisory lock in both sessions), id cursor"
reset_tables; register repro-id repro-xid
LOCK="SELECT pg_advisory_xact_lock($LOCK_KEY);"
session_a "$LOCK" & A=$!
sleep 2
T_B=$SECONDS
session_b "$LOCK" & B=$!
sleep 1
relay_pass "t=3s (A open, B blocked on the lock)"
wait "$A"; wait "$B"
note "option_B: session B waited about $(( SECONDS - T_B ))s for the outbox lock (serialisation cost of a 6s transaction)"
relay_pass "after both committed"
relay_pass "final"
show | tee "$OUT/repro-b.txt"
SKIPPED_B=$(pgq "SELECT never_delivered FROM delivery_audit WHERE consumer = 'repro-id'")
if [[ "$SKIPPED_B" == 0 ]]; then note "repro_option_B=PASS: id cursor received both rows (ids issued in commit order)"
else note "repro_option_B=FAIL: id cursor never received $SKIPPED_B row(s)"; fi

step "summary"
cat "$OUT/summary.txt"
