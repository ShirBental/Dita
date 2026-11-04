# Kafka spike (prototype, isolated)

Isolated spike directory. This project is not a git repository, so this directory serves as the "branch".

- Not part of the Maven reactor. `pom.xml` is unchanged and nothing here is compiled.
- Not part of `docker/compose.yml`.
- No existing file outside this directory was modified.
- Synthetic, ids-only data only.

| File | Purpose | Status |
|---|---|---|
| `proposed-relay-fix.md` | Confirms the outbox cursor-skip bug (file:line). Covers fix options A/B/C with diffs, tests to add, and the Kafka mapping. | Proposal. NOT applied. |
| `compose.kafka.yml` | Separate compose project `dita-kafka-spike`: one KRaft broker plus a `tools` container. Bound to 127.0.0.1:59092. | NOT RUN |
| `bench-kafka.sh` | Throughput, latency, idle/load memory, and a broker SIGKILL/restart with a lost/duplicate check. Tears down only its own project. | NOT RUN (`bash -n` OK) |
| `bench-outbox.sql` | Fixture: outbox tables, both cursor models as functions (`drain_id`, `drain_xid`), and the `delivery_audit` view. | NOT RUN |
| `bench-outbox.sh` | Postgres outbox benchmark plus the two-session reproduction of the cursor skip, run in the throwaway DB `dita_bench`. | NOT RUN (`bash -n` OK) |

## Why nothing here was run by the agent

- The agent sandbox has no Docker socket and no access to the localhost DB ports.
- It also blocks SysV shared memory. A scratch `initdb` with the local Homebrew PostgreSQL 16 failed with
  `shmget ... Operation not permitted`, so no local throwaway cluster was possible either.
- Maven is offline and `kafka-clients` is not in `~/.m2`, so no Java Kafka prototype was compiled.
- Only `bash -n` (syntax) was executed on both scripts, plus a YAML parse of `compose.kafka.yml`.

## Morning run (exact commands)

```sh
cd /path/to/Dita

# 0. Memory: the dita stack budgets ~6.3 GiB of a 7.75 GiB VM. Free room for the spike (~1.5 GiB) first.
#    `stop` keeps containers and volumes intact; nothing is deleted.
docker compose -f docker/compose.yml stop vespa neo4j
docker compose -f docker/compose.yml up -d postgres          # outbox bench needs it; the script never starts/stops it

# 1. Postgres outbox benchmark + cursor-skip reproduction (~2 min). Creates and drops DB dita_bench only.
bash prototypes/kafka-spike/bench-outbox.sh
#    knobs: N=200000 CLIENTS=8 DURATION=15 WORK_MS=2 BATCH=100

# 2. Kafka spike (~5 min plus image pull). Pin the image digest it prints into compose.kafka.yml afterwards.
docker pull apache/kafka:4.1.0                                  # TAG UNVERIFIED: if it does not exist, pick a 4.x tag
bash prototypes/kafka-spike/bench-kafka.sh
#    knobs: NUM_RECORDS=500000 RATE=5000 FAIL_RECORDS=30000 KILL_AFTER=8 DOWN_FOR=5 KEEP=1 (keep broker up)

# 3. Restore the dita stack
docker compose -f docker/compose.yml start neo4j vespa
```

Results go to `build/kafka-spike/outbox-<ts>/summary.txt` and `build/kafka-spike/kafka-<ts>/summary.txt`, with full
logs alongside.

## What to look for

- `repro_current_relay=SKIP REPRODUCED`: the current relay's `id > last_id` cursor lost session A's
  `message.deleted` row. `repro_option_A=PASS` and `repro_option_B=PASS` mean both fixes deliver it.
- `live_race live-id: never_delivered=N`: skips under real concurrency. These are timing-dependent, so 0 does not
  refute the bug. `live-xid` must always be 0.
- `publish_plain` vs `publish_lock` tps: the serialization cost of option B. `bulk_DRAIN ... rows_per_s` gives the
  drain throughput of each cursor model.
- `failover_verdict=PASS`: after a SIGKILL plus restart, with an idempotent `acks=all` producer, no acked record was
  lost and none was duplicated.
  - Single broker, process kill only. This says nothing about disk loss or multi-broker durability.
- `idle_broker_mem` / `load_peak_broker_mem`: compare against the 1g `mem_limit`.

## Safety properties of the scripts

- `bench-kafka.sh` always runs `docker compose -p dita-kafka-spike -f prototypes/kafka-spike/compose.kafka.yml`.
  - It refuses to run unless the file declares `name: dita-kafka-spike` and only the `data` volume
    (`dita-kafka-spike-data`).
  - Teardown is `down -v` of that project only. `KEEP=1` skips teardown.
  - Clients run in the `tools` container, so killing the broker does not kill the producer under test.
- `bench-outbox.sh` uses `docker compose -f docker/compose.yml exec postgres` only. It never runs up, down, rm or
  any volume command on the `dita` project.
  - It refuses to start if `dita_bench` already exists.
  - It marks the database it creates with a COMMENT, and drops only a database carrying that marker.
  - It never connects to the `dita` database. The fixture itself also refuses to load anywhere but `dita_bench`.
- No credentials are read or written. Compose's normal handling of `docker/.env` applies as for the existing
  scripts. The broker is PLAINTEXT with no auth and is bound to 127.0.0.1: dev only.

## Known uncertainties (unverified from the sandbox)

- The `apache/kafka:4.1.0` tag, and the image layout this assumes:
  - tools under `/opt/kafka/bin`;
  - `/var/lib/kafka/data` pre-created and owned by the image's user, so a fresh named volume is writable;
  - env-driven KRaft config.
- `org.apache.kafka.tools.EndToEndLatency` positional arguments. Newer releases may move to `--bootstrap-server`
  style flags; the script logs a failure and continues.
- Whether a backend's own xid lowers its snapshot xmin. Option A sidesteps this by reading the horizon before
  taking the cursor lock.
