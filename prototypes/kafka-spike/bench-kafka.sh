#!/usr/bin/env bash
# Dita Kafka spike benchmark (prototype). NOT RUN by the agent: the sandbox cannot reach the Docker socket.
# Run on the developer's Mac:  bash prototypes/kafka-spike/bench-kafka.sh
#
# What it does, using ONLY the separate compose project `dita-kafka-spike` (prototypes/kafka-spike/compose.kafka.yml):
#   1. up + wait healthy (startup time)          5. consumer perf test
#   2. docker stats at idle                       6. end-to-end latency (EndToEndLatency tool)
#   3. topics: dita.outbox.v1, ...failover        7. failure injection: SIGKILL the broker mid-stream, restart,
#   4. producer perf (max + rate-limited),           verify acked records: none lost, none duplicated
#      ~200-byte ids-only synthetic JSON,          8. teardown of THIS project only (KEEP=1 keeps it running)
#      docker stats sampled under load
# It never touches the `dita` project (docker/compose.yml), its volumes, .env files or data/.
# Logs: build/kafka-spike/kafka-<UTC timestamp>/
#
# Knobs (environment): NUM_RECORDS (500000), RATE_RECORDS (100000), RATE (5000/s), FAIL_RECORDS (30000),
#   FAIL_RATE (1000/s), KILL_AFTER (8 s), DOWN_FOR (5 s), PARTITIONS (6), KEEP (0).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PROJECT=dita-kafka-spike
COMPOSE_FILE="$HERE/compose.kafka.yml"
KC=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE")
OUT="$ROOT/build/kafka-spike/kafka-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1

NUM_RECORDS=${NUM_RECORDS:-500000}
RATE_RECORDS=${RATE_RECORDS:-100000}
RATE=${RATE:-5000}
FAIL_RECORDS=${FAIL_RECORDS:-30000}
FAIL_RATE=${FAIL_RATE:-1000}
KILL_AFTER=${KILL_AFTER:-8}
DOWN_FOR=${DOWN_FOR:-5}
PARTITIONS=${PARTITIONS:-6}
KEEP=${KEEP:-0}
BOOT=kafka:19092                         # INTERNAL listener, reachable from the tools container
TOPIC=dita.outbox.v1
FAIL_TOPIC=dita.outbox.v1.failover
LAT_TOPIC=dita.outbox.v1.latency
BIN=/opt/kafka/bin

step() { printf '\n=== %s  [%s]\n' "$1" "$(date -u +%H:%M:%S)"; }
note() { printf '%s\n' "$*" | tee -a "$OUT/summary.txt"; }
tools() { "${KC[@]}" exec -T tools "$@"; }

# ---------------------------------------------------------------- safety guard: only ever operate on our own project
"${KC[@]}" config 2>/dev/null | grep -qx "name: $PROJECT" \
  || { echo "refusing: $COMPOSE_FILE does not declare 'name: $PROJECT'"; exit 2; }
"${KC[@]}" config --volumes 2>/dev/null | grep -qv '^data$' \
  && { echo "refusing: unexpected volumes in $COMPOSE_FILE"; exit 2; } || true

cleanup() {
  rc=$?
  [[ -n "${SAMPLER_PID:-}" ]] && kill "$SAMPLER_PID" 2>/dev/null || true
  if [[ "$KEEP" == 1 ]]; then
    echo "KEEP=1: leaving $PROJECT running. Remove later with: docker compose -p $PROJECT -f $COMPOSE_FILE down -v"
  else
    step "teardown ($PROJECT only; removes its own volume dita-kafka-spike-data)"
    "${KC[@]}" down -v --remove-orphans || true
  fi
  echo "logs: $OUT  (exit $rc)"
}
trap cleanup EXIT

stats_once() {   # name, mem usage, mem %, cpu %
  docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' "$@"
}
peak_mem() {     # peak "used" column of a stats file for the container whose name contains $2, in MiB
  awk -F'\t' -v n="$2" '$1 ~ n { split($2, a, " / "); v=a[1];
      if (v ~ /GiB$/) { sub(/GiB$/,"",v); v*=1024 } else if (v ~ /MiB$/) { sub(/MiB$/,"",v) }
      else if (v ~ /kB$/) { sub(/kB$/,"",v); v/=1024 } else if (v ~ /B$/) { sub(/B$/,"",v); v/=1048576 }
      if (v+0 > m) m = v+0 } END { printf "%.0f MiB\n", m }' "$1"
}
wait_healthy() {
  local id t0=$SECONDS
  id=$("${KC[@]}" ps -q kafka)
  until [[ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" == healthy ]]; do
    (( SECONDS - t0 > 180 )) && { echo "broker not healthy after 180s" >&2; "${KC[@]}" logs --tail 80 kafka >&2; return 1; }
    sleep 1
  done
  echo $(( SECONDS - t0 ))
}

# ---------------------------------------------------------------- 0. pre-flight
step "0. pre-flight"
docker version --format 'docker client {{.Client.Version}} / server {{.Server.Version}}'
docker info --format 'Docker VM memory: {{.MemTotal}} bytes, CPUs: {{.NCPU}}'
echo "Currently running containers (the spike needs ~1.5 GiB on top of these):"
stats_once $(docker ps -q) 2>/dev/null || true
echo "Image (tag unverified until first pull):"; grep -m1 'x-kafka-image' "$COMPOSE_FILE"

# ---------------------------------------------------------------- 1. up
step "1. up ($PROJECT)"
T0=$SECONDS
"${KC[@]}" up -d kafka
UP_S=$(wait_healthy)
"${KC[@]}" up -d tools
note "startup_to_healthy_s=$UP_S (includes image start; pull time excluded if already pulled: total $((SECONDS - T0))s)"
docker image inspect "$(grep -m1 'x-kafka-image' "$COMPOSE_FILE" | awk '{print $3}')" \
  --format 'image digest (pin this in compose.kafka.yml): {{index .RepoDigests 0}}' | tee -a "$OUT/summary.txt" || true
BROKER=$("${KC[@]}" ps -q kafka); TOOLS=$("${KC[@]}" ps -q tools)
tools sh -c "$BIN/kafka-topics.sh --version" | tee -a "$OUT/summary.txt"

# ---------------------------------------------------------------- 2. idle memory
step "2. idle memory (after 20s settle)"
sleep 20
stats_once "$BROKER" "$TOOLS" | tee "$OUT/stats-idle.tsv"
note "idle_broker_mem=$(peak_mem "$OUT/stats-idle.tsv" kafka-1)"

# ---------------------------------------------------------------- 3. topics
step "3. topics"
for t in "$TOPIC:$PARTITIONS" "$FAIL_TOPIC:3" "$LAT_TOPIC:1"; do
  tools sh -c "$BIN/kafka-topics.sh --bootstrap-server $BOOT --create --if-not-exists --topic ${t%%:*} \
    --partitions ${t##*:} --replication-factor 1 --config min.insync.replicas=1 --config retention.ms=86400000"
done
tools sh -c "$BIN/kafka-topics.sh --bootstrap-server $BOOT --describe --topic $TOPIC"

# ---------------------------------------------------------------- 4. producer perf
step "4. producer perf: synthetic ids-only JSON, ~200 bytes, acks=all, idempotent"
PAY="$OUT/payloads.synthetic.txt"
for i in $(seq 1 1000); do
  line=$(printf '{"v":1,"type":"message.created","groupId":"g-synth-%04d","messageId":"m-synth-%08d","outboxId":%d,"source":"SYNTHETIC","pad":"' \
    $((i % 50)) "$i" "$i")
  pad=$(( 200 - ${#line} - 2 )); (( pad < 0 )) && pad=0
  printf '%s%s"}\n' "$line" "$(printf '%*s' "$pad" '' | tr ' ' 'x')"
done > "$PAY"
note "payload_lines=$(wc -l < "$PAY") avg_bytes=$(awk '{s+=length($0)} END {printf "%.0f", s/NR}' "$PAY") (synthetic, ids only, no text)"
tools sh -c 'cat > /tmp/payloads.txt' < "$PAY"
PROPS="bootstrap.servers=$BOOT acks=all enable.idempotence=true max.in.flight.requests.per.connection=5 linger.ms=5 batch.size=65536 compression.type=lz4"

( while :; do stats_once "$BROKER" "$TOOLS"; sleep 1; done ) > "$OUT/stats-load.tsv" 2>/dev/null &
SAMPLER_PID=$!
echo "--- 4a. max throughput, $NUM_RECORDS records"
tools sh -c "$BIN/kafka-producer-perf-test.sh --topic $TOPIC --num-records $NUM_RECORDS --throughput -1 \
  --payload-file /tmp/payloads.txt --producer-props $PROPS" | tee "$OUT/producer-max.log"
echo "--- 4b. rate-limited latency, $RATE_RECORDS records at $RATE/s"
tools sh -c "$BIN/kafka-producer-perf-test.sh --topic $TOPIC --num-records $RATE_RECORDS --throughput $RATE \
  --payload-file /tmp/payloads.txt --producer-props $PROPS" | tee "$OUT/producer-rate.log"

# ---------------------------------------------------------------- 5. consumer perf
step "5. consumer perf"
tools sh -c "$BIN/kafka-consumer-perf-test.sh --bootstrap-server $BOOT --topic $TOPIC \
  --messages $((NUM_RECORDS + RATE_RECORDS)) --group dita-spike-perf --timeout 60000" | tee "$OUT/consumer.log"
kill "$SAMPLER_PID" 2>/dev/null || true; SAMPLER_PID=
note "load_peak_broker_mem=$(peak_mem "$OUT/stats-load.tsv" kafka-1) load_peak_tools_mem=$(peak_mem "$OUT/stats-load.tsv" tools-1)"
note "producer_max: $(tail -n 1 "$OUT/producer-max.log")"
note "producer_rate: $(tail -n 1 "$OUT/producer-rate.log")"
note "consumer: $(tail -n 1 "$OUT/consumer.log")"

# ---------------------------------------------------------------- 6. end-to-end latency
step "6. end-to-end latency (produce -> consume round trip, acks=all, 200 bytes, 10000 msgs)"
tools sh -c "$BIN/kafka-run-class.sh org.apache.kafka.tools.EndToEndLatency $BOOT $LAT_TOPIC 10000 all 200" \
  | tee "$OUT/e2e-latency.log" || echo "EndToEndLatency tool failed (class name/args differ in this Kafka version?)"
note "e2e_latency: $(grep -iE 'avg|percentile' "$OUT/e2e-latency.log" | tr '\n' ' ')"

# ---------------------------------------------------------------- 7. failure injection
step "7. failure injection: SIGKILL broker after ${KILL_AFTER}s of a ${FAIL_RECORDS}-record stream, down ${DOWN_FOR}s, restart"
tools sh -c 'cat > /tmp/fail-producer.properties' <<EOF
acks=all
enable.idempotence=true
max.in.flight.requests.per.connection=5
retries=2147483647
delivery.timeout.ms=180000
request.timeout.ms=10000
retry.backoff.ms=200
EOF
tools sh -c "$BIN/kafka-verifiable-producer.sh --bootstrap-server $BOOT --topic $FAIL_TOPIC \
  --max-messages $FAIL_RECORDS --throughput $FAIL_RATE --acks -1 --producer.config /tmp/fail-producer.properties" \
  > "$OUT/fail-producer.jsonl" 2> "$OUT/fail-producer.stderr" &
PROD_PID=$!
sleep "$KILL_AFTER"
echo "SIGKILL broker at $(date -u +%H:%M:%S)"
"${KC[@]}" kill -s SIGKILL kafka
sleep "$DOWN_FOR"
echo "restart broker at $(date -u +%H:%M:%S)"
"${KC[@]}" start kafka
REC_S=$(wait_healthy)
note "broker_restart_to_healthy_s=$REC_S"
T1=$SECONDS
while kill -0 "$PROD_PID" 2>/dev/null; do
  (( SECONDS - T1 > 240 )) && { echo "producer still running after 240s; stopping it"; kill "$PROD_PID"; break; }
  sleep 2
done
wait "$PROD_PID" 2>/dev/null || true
ACKED=$(grep -c '"name":"producer_send_success"' "$OUT/fail-producer.jsonl" || true)
ERRS=$(grep -c '"name":"producer_send_error"' "$OUT/fail-producer.jsonl" || true)
grep '"name":"producer_send_success"' "$OUT/fail-producer.jsonl" | sed -E 's/.*"value":"([^"]*)".*/\1/' | LC_ALL=C sort -u > "$OUT/fail-acked.txt"
tools sh -c "$BIN/kafka-console-consumer.sh --bootstrap-server $BOOT --topic $FAIL_TOPIC --from-beginning \
  --timeout-ms 20000 --group dita-spike-verify" > "$OUT/fail-consumed.txt" 2> "$OUT/fail-consumer.stderr" || true
CONSUMED=$(wc -l < "$OUT/fail-consumed.txt" | tr -d ' ')
UNIQUE=$(sort -u "$OUT/fail-consumed.txt" | wc -l | tr -d ' ')
DUPES=$(( CONSUMED - UNIQUE ))
LOST=$(LC_ALL=C sort -u "$OUT/fail-consumed.txt" | LC_ALL=C comm -23 "$OUT/fail-acked.txt" - | wc -l | tr -d ' ')
note "failover: sent=$FAIL_RECORDS acked=$ACKED send_errors=$ERRS consumed=$CONSUMED unique=$UNIQUE duplicates=$DUPES acked_but_missing=$LOST"
if (( LOST == 0 && DUPES == 0 )); then note "failover_verdict=PASS (no acked record lost, no duplicate)"
else note "failover_verdict=FAIL (see $OUT/fail-*.txt)"; fi

# ---------------------------------------------------------------- final
step "final memory"
stats_once "$BROKER" "$TOOLS" | tee "$OUT/stats-final.tsv"
step "summary"
cat "$OUT/summary.txt"
