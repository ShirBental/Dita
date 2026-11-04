#!/usr/bin/env bash
# TASK-036 / ADR-001: verify the outbox ordering fix (F1/N-44) and the real POSTGRES runtime on the running Dita stack.
#
#   scripts/relay-verify.sh            full run: read-only checks, typed confirmation, pre-fix gate, V4, all tests
#   scripts/relay-verify.sh --check    read-only: identity, migration status and plan only. Changes nothing.
#
# Order of operations (every STOP before step 6 leaves the database unchanged, except V2/V3 if you confirmed them):
#   0 preflight (tools, Java 21, compose file)             read-only
#   1 identity: containers belong to project "dita", healthy, on 127.0.0.1 Dita ports, data volume dita_*,
#     database identity (name, owner, server version, system identifier)                                   read-only
#   2 migration status V1..V4, repo files == files mounted in the container, outbox_id_seq CACHE 1 (N-62),
#     V4-already-applied check and the recorded pre-fix evidence                                           read-only
#   3 offline compile + test discovery gate (the 3 *_DefectDemo tests, the runtime test)                   no DB access
#   4 reachability of PostgreSQL / Neo4j / Vespa from the host                                             read-only
#   5 plan + typed confirmation ('apply'), then V2/V3 only if missing
#   6 HARD GATE: pre-fix defect reproduction (V4 absent). Exact failure signature required; anything else STOPs.
#   7 V4 (only after a conclusive step 6)
#   8 full build with every gated suite, 9 database evidence (deletions never skipped), 10 summary
#
# Safety: talks ONLY to compose project "dita" (docker/compose.yml): `ps`, `port`, `exec`, `config` and
# `docker inspect`. It never runs up/down/stop/restart/rm/pull, never touches volumes, other projects or a non-Dita
# Postgres. Synthetic data only. Muse stays disabled (no live calls). No Telegram API (fake in-process gateway; the
# token/secret below are synthetic placeholders). dita-eval is excluded (another session owns it; it has no DB tests).
# Exit codes: 0 PASS · 1 FAIL (checks after V4) · 2 STOP, no DB change · 3 STOP after V2/V3 only, V4 NOT applied.
set -uo pipefail
cd "$(dirname "$0")/.."
MODE=full
case "${1:-}" in "") ;; --check) MODE=check ;; *) echo "usage: $0 [--check]"; exit 64 ;; esac
OUT=build/relay-verify; EVID="$OUT/defect-demo"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
DC=(docker compose -p dita -f docker/compose.yml)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OWNER=${DITA_DB_OWNER:-dita}; DB=${DITA_DB_NAME:-dita}
APP_PW=${DITA_APP_PASSWORD:-dita-app-dev-not-secret}
RELAY_PW=${DITA_RELAY_PASSWORD:-dita-relay-dev-not-secret}
NEO_PW=${DITA_NEO4J_PASSWORD:-dita-dev-not-secret}
LOCK_KEY=4920556706028584961                       # 0x4449544100000001 = PostgresEventPublisher.OUTBOX_ORDER_LOCK
MVN=(mvn -o -pl '!dita-eval')
DEMOS=(legacyPublisherLosesOutOfOrderCommit_DefectDemo legacyPublisherLosesLateDeletion_DefectDemo concurrentLegacyWritersLoseEvents_DefectDemo)
SUITE=dita-store-postgres/target/surefire-reports/TEST-app.dita.store.postgres.OutboxOrderingTest.xml
fails=0; CHANGED=(); V4_APPLIED_NOW=no
pass() { echo "=== PASS: $1"; }
fail() { echo "=== FAIL: $1"; fails=$((fails+1)); }
info() { echo "    $1"; }
stop() {   # $1 reason
  echo "=== STOP: $1"
  if (( ${#CHANGED[@]} )); then echo "Database changes made by this run: ${CHANGED[*]}. V4 was NOT applied."; exit 3
  else echo "No database changes were made by this run."; exit 2; fi
}
psql_owner() { "${DC[@]}" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U "$OWNER" -d "$DB" "$@"; }
q() { psql_owner -Atc "$1" 2>>"$OUT/psql-errors.log"; }   # errors kept in a log (never shown values)
date -u +"started %Y-%m-%dT%H:%M:%SZ (mode: $MODE)"

echo "## 0. Preflight"
for t in docker mvn curl xmllint shasum; do command -v "$t" >/dev/null || stop "required tool '$t' not found"; done
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21' || stop "JAVA_HOME=$JAVA_HOME is not Java 21"
"${DC[@]}" config --quiet 2>/dev/null || stop "docker/compose.yml is not valid"
pass "tools, Java 21, compose file"

echo "## 1. Identity (Dita-owned services and database)"
for svc in postgres neo4j vespa; do
  cid=$("${DC[@]}" ps -q "$svc" 2>/dev/null)
  [[ -n "$cid" ]] || stop "service $svc of compose project 'dita' is not running (start the Dita stack first)"
  proj=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid")
  [[ "$proj" == dita ]] || stop "$svc container belongs to project '$proj', not 'dita'"
  health=$(docker inspect -f '{{ if .State.Health }}{{ .State.Health.Status }}{{ else }}none{{ end }}' "$cid")
  [[ "$health" == healthy ]] || stop "$svc is '$health', expected healthy"
  info "$svc: container=$(docker inspect -f '{{.Name}}' "$cid") project=$proj health=$health image=$(docker inspect -f '{{.Config.Image}}' "$cid")"
  [[ $svc == postgres ]] && PG_CID=$cid
done
mapped() {   # $1 service, $2 container port, $3 expected host port
  local m; m=$("${DC[@]}" port "$1" "$2" 2>/dev/null)
  [[ "$m" == "127.0.0.1:$3" ]] || { echo "=== STOP: $1:$2 maps to '${m:-nothing}', expected 127.0.0.1:$3"; return 1; }
  echo "$m"
}
PG=$(mapped postgres 5432 "${DITA_DB_PORT:-55432}") || stop "postgres port mapping"
NEO=$(mapped neo4j 7474 "${DITA_NEO4J_HTTP_PORT:-57474}") || stop "neo4j port mapping"
VESPA=$(mapped vespa 8080 "${DITA_VESPA_QUERY_PORT:-58080}") || stop "vespa port mapping"
VOL=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$PG_CID")
[[ "$VOL" == dita_* ]] || stop "postgres data volume is '${VOL:-none}', expected a dita_* volume"
IDENT=$(q "select current_database()||'|'||current_user||'|'||current_setting('server_version')||'|'||(select system_identifier from pg_control_system())")
[[ -n "$IDENT" ]] || stop "cannot query database '$DB' as '$OWNER' inside the dita postgres container"
IFS='|' read -r ID_DB ID_USER ID_VER SYSID <<<"$IDENT"
[[ "$ID_DB" == "$DB" ]] || stop "connected to database '$ID_DB', expected '$DB'"
info "database: name=$ID_DB owner=$ID_USER server=$ID_VER system_identifier=$SYSID volume=$VOL host=$PG"
info "neo4j=$NEO vespa=$VESPA"
pass "services, ports, volume and database belong to project dita"

echo "## 2. Migration status (read-only)"
[[ "$(q "select to_regclass('public.message') is not null and to_regclass('public.outbox') is not null")" == t ]] || stop "V1 schema missing in '$DB' (not applied by this script; see docs/local-infra.md)"
info "V1 core schema ............ applied"
V2=$(q "select to_regclass('public.outbox_consumer') is not null")
V3=$(q "select exists(select 1 from pg_policies where policyname='outbox_app_own_group') and exists(select 1 from pg_roles where rolname='dita_relay')")
V4=$(q "select exists(select 1 from pg_trigger where tgname='outbox_commit_order' and not tgisinternal)")
[[ "$V2$V3$V4" =~ ^[tf][tf][tf]$ ]] || stop "could not read migration status (V2='$V2' V3='$V3' V4='$V4')"
st() { [[ $1 == t ]] && echo "applied" || echo "PENDING"; }
info "V2 outbox relay cursors ... $(st "$V2")"
info "V3 least privilege ........ $(st "$V3")"
info "V4 outbox commit order .... $(st "$V4")"
V5=$(q "select to_regclass('public.knowledge_relation') is not null")
[[ "$V5" =~ ^[tf]$ ]] || stop "could not read V5 status"
info "V5 knowledge v2 ........... $(st "$V5") (never applied by this script; scripts/knowledge-verify.sh does)"
[[ $V2 == f && $V3 == t ]] && stop "inconsistent: V3 present without V2"
for f in db/migration/V*.sql; do
  b=$(basename "$f")
  local_sum=$(shasum -a 256 "$f" | cut -d' ' -f1)
  mounted=$("${DC[@]}" exec -T postgres sha256sum "/migrations/$b" 2>/dev/null | cut -d' ' -f1)
  [[ "$local_sum" == "$mounted" ]] || stop "/migrations/$b in the container differs from the repo file (or is missing)"
done
info "repo migration files == files mounted at /migrations ($(ls db/migration/V*.sql | wc -l | tr -d ' ') files)"
for f in db/migration/V*.sql; do [[ "$(basename "$f")" =~ ^V[1-4]__ ]] || info "NOTE: $(basename "$f") exists and is NOT applied by this script"; done
[[ "$(q "select cache_size from pg_sequences where schemaname='public' and sequencename='outbox_id_seq'")" == 1 ]] \
  || stop "outbox_id_seq cache_size is not 1: lock ordering would not hold (N-62)"
info "outbox_id_seq CACHE 1 (N-62)"
if [[ $V4 == t ]]; then
  [[ "$(q "select prosrc like '%$LOCK_KEY%' from pg_proc where proname='outbox_commit_order_lock'")" == t ]] || stop "V4 trigger present but its function does not use lock key $LOCK_KEY"
  if [[ -f "$EVID/EVIDENCE.txt" ]] && grep -qx "system_identifier=$SYSID" "$EVID/EVIDENCE.txt" && grep -qx "verdict=REPRODUCED" "$EVID/EVIDENCE.txt"; then
    info "V4 already applied; the pre-fix reproduction recorded for THIS database is in $EVID/EVIDENCE.txt"
  else
    stop "V4 is already applied and there is no recorded pre-fix reproduction for database system_identifier=$SYSID. The original defect cannot be demonstrated on this database any more. Nothing was changed; ask the engineering lead how to proceed (do NOT drop the trigger or volumes)"
  fi
fi
PLAN=(); [[ $V2 == f ]] && PLAN+=("V2") ; [[ $V3 == f ]] && PLAN+=("V3"); [[ $V4 == f ]] && PLAN+=("V4(after the pre-fix gate)")
pass "migration status read; planned schema changes: ${PLAN[*]:-none}"
if [[ $MODE == check ]]; then echo "=== CHECK ONLY: nothing was changed."; exit 0; fi

echo "## 3. Offline compile and test-discovery gate (no database access)"
"${MVN[@]}" -q test-compile > "$OUT/mvn-compile.log" 2>&1 || stop "compilation failed (see $OUT/mvn-compile.log)"
CLS=dita-store-postgres/target/test-classes/app/dita/store/postgres/OutboxOrderingTest.class
for m in "${DEMOS[@]}"; do grep -q "$m" "$CLS" 2>/dev/null || stop "test discovery: $m not found in $CLS"; done
[[ -f dita-app/target/test-classes/app/dita/app/PostgresRuntimeIntegrationTest.class ]] || stop "test discovery: PostgresRuntimeIntegrationTest not compiled"
pass "all modules except dita-eval compile; the 3 pre-fix demos and the runtime test are present"

echo "## 4. Reachability from the host (read-only)"
reach() { (exec 3<>"/dev/tcp/${1%:*}/${1#*:}") 2>/dev/null; }
reach "$PG" || stop "cannot open a TCP connection to PostgreSQL at $PG from the host"
curl -fsS -o /dev/null "http://$NEO/" || stop "Neo4j HTTP at $NEO not reachable"
curl -fsS -o /dev/null "http://$VESPA/ApplicationStatus" || stop "Vespa application not deployed at $VESPA (run scripts/vespa-verify.sh first)"
# The projections now also maintain the 'knowledge' document type (reconcile and message deletion visit it). Without
# it every deletion fails in the Vespa consumer and is retried forever, so the build cannot pass: STOP before any change.
# Read-only: a visit of a group that cannot exist (no document is returned).
kcode=$(curl -sS -o "$OUT/vespa-knowledge-probe.json" -w '%{http_code}' \
  "http://$VESPA/document/v1/dita/knowledge/docid?selection=knowledge.group_id%3D%3D%22__probe__%22&cluster=dita&wantedDocumentCount=1" 2>/dev/null)
[[ "$kcode" == 200 ]] || stop "Vespa has no 'knowledge' document type (HTTP ${kcode:-none}; response in $OUT/vespa-knowledge-probe.json). The application package in vespa/app is not deployed; deploying it needs the user's approval"
pass "PostgreSQL, Neo4j and Vespa reachable; Vespa knowledge document type deployed"

echo "## 5. Confirmation"
echo "This run will change database '$DB' (system_identifier=$SYSID, volume $VOL) as follows:"
[[ $V2 == f ]] && echo "  - apply V2__outbox_relay.sql (relay cursor tables)"
[[ $V3 == f ]] && echo "  - apply V3__outbox_least_privilege.sql (dita_relay role, outbox RLS)"
[[ $V4 == f ]] && echo "  - apply V4__outbox_commit_order.sql (one function + one trigger) ONLY IF the pre-fix gate (step 6) is conclusive"
echo "  - tests insert SYNTHETIC rows (new groups tg…/order-…/lock-…, outbox rows, relay cursors rt-*/ordering-*). Nothing is deleted."
read -r -p "Type 'apply' to continue, anything else to stop: " answer || answer=""
[[ "$answer" == apply ]] || stop "not confirmed"
export DITA_TEST_DB_URL="jdbc:postgresql://$PG/$DB" DITA_TEST_DB_USER=dita_app DITA_TEST_DB_PASSWORD="$APP_PW" \
       DITA_TEST_RELAY_PASSWORD="$RELAY_PW" DITA_TEST_NEO4J_URL="http://$NEO" DITA_TEST_NEO4J_PASSWORD="$NEO_PW" \
       DITA_TEST_VESPA_URL="http://$VESPA" \
       DITA_TEST_FAKE_TELEGRAM_TOKEN="123456789:$(printf 'A%.0s' {1..35})" \
       DITA_TEST_FAKE_CALLBACK_SECRET="$(printf 's%.0s' {1..40})"
unset MODEL_API_KEY                                   # belt and braces: no Muse key in the test JVMs
if [[ $V2 == f ]]; then psql_owner -1 -f /migrations/V2__outbox_relay.sql >/dev/null || stop "V2 failed (rolled back)"; CHANGED+=("V2"); info "V2 applied"; fi
if [[ $V3 == f ]]; then psql_owner -1 -v relay_password="$RELAY_PW" -v dbname="$DB" -f /migrations/V3__outbox_least_privilege.sql >/dev/null || stop "V3 failed (rolled back)"; CHANGED+=("V3"); info "V3 applied"; fi

echo "## 6. HARD GATE: reproduce the original defect with the pre-fix publisher (V4 absent)"
if [[ $V4 == f ]]; then
  [[ "$(q "select exists(select 1 from pg_trigger where tgname='outbox_commit_order' and not tgisinternal)")" == f ]] || stop "V4 appeared during the run (another writer?)"
  rm -f dita-store-postgres/target/surefire-reports/*OutboxOrderingTest*
  sel=$(IFS=+; echo "${DEMOS[*]}")
  mvn -o -pl dita-store-postgres -am test -Dtest="OutboxOrderingTest#$sel" -Ddita.demonstrateDefect=true \
      -Dsurefire.failIfNoSpecifiedTests=false > "$OUT/mvn-defect-demo.log" 2>&1
  demo_rc=$?
  [[ -f "$SUITE" ]] || stop "pre-fix run INCONCLUSIVE: no OutboxOrderingTest report (compile, discovery or build failure; see $OUT/mvn-defect-demo.log)"
  x() { xmllint --xpath "$1" "$SUITE" 2>/dev/null; }
  tests=$(x 'string(/testsuite/@tests)'); errs=$(x 'string(/testsuite/@errors)'); skip=$(x 'string(/testsuite/@skipped)')
  info "report: tests=$tests failures=$(x 'string(/testsuite/@failures)') errors=$errs skipped=$skip mvn_rc=$demo_rc"
  [[ "$tests" == 3 && "$errs" == 0 && "$skip" == 0 ]] || stop "pre-fix run INCONCLUSIVE: expected 3 tests, 0 errors (connection/setup), 0 skipped"
  sig() {   # $1 test, $2 message fragment → 1 if the test FAILED with an AssertionFailedError carrying that fragment
    x "count(//testcase[@name='$1']/failure[contains(@type,'AssertionFailedError') and contains(@message,'$2')])"; }
  for m in "${DEMOS[@]:0:2}"; do
    [[ "$(sig "$m" 'MISSING committed outbox ids')" == 1 ]] || stop "pre-fix run INCONCLUSIVE: $m did not fail with the expected 'MISSING committed outbox ids' assertion"
  done
  m=${DEMOS[2]}
  if [[ "$(sig "$m" 'committed ids')" == 1 ]] && [[ "$(x "string(//testcase[@name='$m']/failure/@message)")" == MISSING* ]]; then stress="lost events (race hit)"
  elif [[ "$(x "count(//testcase[@name='$m']/*[self::failure or self::error or self::skipped])")" == 0 ]]; then stress="no loss in this run (timing-dependent; informational)"
  else stop "pre-fix run INCONCLUSIVE: $m failed for an unexpected reason: $(x "string(//testcase[@name='$m']/*/@message)" | head -c 200)"; fi
  (( demo_rc != 0 )) || stop "pre-fix run INCONCLUSIVE: maven reported success although the demos failed"
  mkdir -p "$EVID"; cp "$SUITE" "$EVID/"; cp "$OUT/mvn-defect-demo.log" "$EVID/"
  { echo "recorded=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "system_identifier=$SYSID"; echo "database=$ID_DB server=$ID_VER volume=$VOL"
    echo "verdict=REPRODUCED"
    for m in "${DEMOS[@]:0:2}"; do echo "$m: $(x "string(//testcase[@name='$m']/failure/@message)" | head -c 300)"; done
    echo "${DEMOS[2]}: $stress"; } > "$EVID/EVIDENCE.txt"
  pass "defect REPRODUCED: the pre-fix publisher lost a committed outbox row and a committed deletion (stress: $stress). Evidence: $EVID/EVIDENCE.txt"
else
  pass "pre-fix reproduction already recorded for this database ($EVID/EVIDENCE.txt)"
fi

echo "## 7. Apply V4 (only after a conclusive pre-fix gate)"
if [[ $V4 == f ]]; then
  psql_owner -1 -f /migrations/V4__outbox_commit_order.sql >/dev/null || stop "V4 failed (rolled back)"
  CHANGED+=("V4"); V4_APPLIED_NOW=yes
  [[ "$(q "select exists(select 1 from pg_trigger where tgname='outbox_commit_order' and not tgisinternal and tgenabled='O')")" == t ]] \
    && pass "V4 applied: trigger outbox_commit_order enabled" || fail "V4 applied but the trigger is not present/enabled"
else info "V4 already applied"; fi

echo "## 8. Full build with every gated suite (incl. the 3 pre-fix demos, which must now PASS under V4)"
MODULES=(dita-core dita-store-postgres dita-projections dita-muse dita-telegram dita-app)
rm -rf "$OUT/surefire"; for m in "${MODULES[@]}"; do rm -rf "$m/target/surefire-reports"; done   # never judge stale reports
RUN_START=$(q "select now()")
[[ -n "$RUN_START" ]] || stop "could not read the database clock"
"${MVN[@]}" -fae test -Ddita.demonstrateDefect=true > "$OUT/mvn-full.log" 2>&1
rc=$?
grep -E 'Tests run:.*in app\.dita|Reactor Summary|SUCCESS \[|FAILURE \[|BUILD' "$OUT/mvn-full.log" | head -80
mkdir -p "$OUT/surefire" && for m in "${MODULES[@]}"; do
  [[ -d "$m/target/surefire-reports" ]] && cp -Rp "$m/target/surefire-reports/." "$OUT/surefire/"; done
grep -E '^\[INFO\] Skipping ' "$OUT/mvn-full.log" | sed 's/^/    NOT RUN (module skipped by Maven after an upstream failure): /' || true
# Tests that skip BY DESIGN while V5 is absent (TestDb.assumeV5 / KnowledgeSlicePostgresTest). With V5 applied, none may skip.
V5_GATED=(
  PostgresMessageStoreContractTest#assertionAndSpansRoundTrip PostgresMessageStoreContractTest#relationsAreIdempotentByKeyAndValidated
  PostgresMessageStoreContractTest#removeKnowledgeCascadesRelations PostgresMessageStoreContractTest#atomicallyRollsBackKnowledgeV2
  PostgresMessageStoreContractTest#answerProvenanceRoundTripsAndIsIdempotent PostgresMessageStoreContractTest#knowledgeV2GroupsNeverSeeEachOther
  PostgresRlsIsolationTest#v5TablesAreGroupConfinedAndLeastPrivilege
  PostgresExtractionQueueContractTest#collectChunksOldestFirstWithDeterministicIds PostgresExtractionQueueContractTest#aMessageIsInAtMostOneBatch
  PostgresExtractionQueueContractTest#enqueueIsIdempotentFirstWins PostgresExtractionQueueContractTest#groupsAreIsolated
  PostgresExtractionQueueContractTest#transitionIsCompareAndSet
  KnowledgeSlicePostgresTest#syntheticMessagesBecomeDurableEvidenceGroundedKnowledge)
ALWAYS_SKIPPED=(MuseConnectivityLiveTest MuseExtractionLiveTest)        # approval-gated live Muse calls, never run here
allowed_skip() {   # $1 Class#method
  local x; for x in "${ALWAYS_SKIPPED[@]}"; do [[ "${1%%#*}" == "$x" ]] && return 0; done
  [[ $V5 == f ]] && for x in "${V5_GATED[@]}"; do [[ "$1" == "$x" ]] && return 0; done
  return 1
}
report_of() { ls "$OUT"/surefire/TEST-*."$1".xml 2>/dev/null | head -1; }
xcount() { xmllint --xpath "count($2)" "$1" 2>/dev/null | cut -d. -f1; }
skipped_of() {   # $1 report → Class#method per skipped test case
  local f=$1 n i c; n=$(xcount "$f" '//testcase[skipped]'); c=$(basename "$f" .xml); c=${c##*.}
  for ((i = 1; i <= ${n:-0}; i++)); do echo "$c#$(xmllint --xpath "string((//testcase[skipped])[$i]/@name)" "$f")"; done
}
req() {   # $1 class, $2 expected test count: all present, 0 failures/errors; skips only where allowed (V5 absent) and listed
  local f t bad sk unexpected=() expected=0 x
  f=$(report_of "$1")
  [[ -n "$f" ]] || { fail "$1 NOT RUN (no surefire report from this run)"; return; }
  t=$(xcount "$f" '//testcase'); bad=$(xcount "$f" '//testcase[failure or error]')
  while IFS= read -r sk; do [[ -z "$sk" ]] && continue; allowed_skip "$sk" && expected=$((expected+1)) || unexpected+=("$sk"); done < <(skipped_of "$f")
  if [[ "$t" == "$2" && "$bad" == 0 && ${#unexpected[@]} == 0 ]]; then
    if (( expected )); then pass "$1 ran $(( t - expected ))/$t; $expected skipped by design (V5 not applied: NOT EXECUTED, not passed)"
    else pass "$1 ran $t/$t, 0 skipped"; fi
  else
    fail "$1: tests=$t (expected $2) failures+errors=$bad unexpected-skips=${#unexpected[@]}"
    for x in ${unexpected[@]+"${unexpected[@]}"}; do info "unexpected skip: $x"; done
    grep -E "in .*\.$1\$" "$OUT/mvn-full.log" | sed 's/^/    /'
  fi
}
req OutboxOrderingTest 9                     # all 9 ordering tests on PostgreSQL (incl. deletion never skipped)
req AdvisoryLockSafetyTest 6                 # rollback release, contention, lock_timeout, deadlock, V4 raw insert, CACHE 1
req PostgresRuntimeIntegrationTest 5         # booted dita-app on POSTGRES: ingest, /ask, /forget through Neo4j + Vespa
req EventDispatcherConcurrencyTest 6         # F1c
req TransientFailureRetryTest 4              # Shuri B1: a transient DB failure never loses a Telegram update or /forget
req PostgresMessageStoreContractTest 30      # 24 relay-era + 6 knowledge-v2 (skip by design until V5)
req PostgresRlsIsolationTest 2              # 1 + the V5 tables test (skips by design until V5)
req PostgresExtractionQueueContractTest 5   # all V5-gated
req EndToEndSliceTest 6                     # incl. consumer health after ingest, deletion and reconcile
req VespaResponseTest 5

echo "## 9. Deletion events are never skipped (database evidence)"
dl=$(q "select count(*) from outbox_dead_letter d join outbox o on o.id = d.outbox_id where o.topic = 'message.deleted'")
[[ "$dl" == 0 ]] && pass "no message.deleted event was ever dead-lettered" || fail "${dl:-?} deletion events are dead-lettered (N-20 violated)"
# Every projection consumer used by this run must be fully healthy, not just "not yet blocked": a failing deletion is
# retried (attempts/retry_at) long before blocked_on_deletion is set. ordering-*/lock-* consumers are excluded: those
# tests make consumers fail on purpose and assert the outcome themselves.
used=$(q "select count(*) from outbox_consumer where updated_at >= '$RUN_START' and name ~ '^(rt|e2e|ks)-'")
sick=$(q "select 'ok:'||coalesce(string_agg(name||'(attempts='||attempts||' retry_pending='||(retry_at is not null)||' blocked_on_deletion='||blocked_on_deletion||')', ', '), '')
            from outbox_consumer where updated_at >= '$RUN_START' and name ~ '^(rt|e2e|ks)-'
             and (attempts > 0 or retry_at is not null or blocked_on_deletion)")
if [[ "$sick" == ok: && "${used:-0}" -ge 2 ]]; then pass "all $used projection consumers used by this run are healthy (no retry pending, nothing blocked)"
elif [[ "$sick" == ok: ]]; then fail "only ${used:-0} projection consumers were used by this run (expected the e2e and runtime consumers)"
elif [[ "$sick" == ok:* ]]; then fail "projection consumers failing or retrying: ${sick#ok:}"
else fail "could not read consumer status (query failed; see $OUT/psql-errors.log)"; fi
others=$(q "select 'ok:'||coalesce(string_agg(name, ','), '') from outbox_consumer where blocked_on_deletion and updated_at < '$RUN_START'")
[[ "$others" == ok:* ]] || fail "could not read other consumers' status (query failed)"
[[ "$others" == ok: || "$others" != ok:* ]] || info "NOTE: consumers blocked on a deletion before this run (earlier runs): ${others#ok:}"

echo "## 10. Failures and skips anywhere in the build"
grep -E '<<< (FAILURE|ERROR)!' "$OUT/mvn-full.log" | sed 's/^/  /' | head -40 || true
unexpected=0; bydesign=0
for f in "$OUT"/surefire/TEST-*.xml; do
  [[ -f "$f" ]] || continue
  while IFS= read -r sk; do [[ -z "$sk" ]] && continue
    if allowed_skip "$sk"; then bydesign=$((bydesign+1)); else unexpected=$((unexpected+1)); info "unexpected skip: $sk"; fi
  done < <(skipped_of "$f")
done
(( unexpected == 0 )) && pass "no unexpected skips ($bydesign skipped by design: Muse live tests$([[ $V5 == f ]] && echo ', V5-gated tests: NOT EXECUTED'))" \
  || fail "$unexpected unexpected skipped tests"
(( rc == 0 )) && pass "maven build" || fail "maven build rc=$rc (see $OUT/mvn-full.log)"

echo
echo "Database changes made by this run: ${CHANGED[*]:-none} (V4 applied now: $V4_APPLIED_NOW)"
if (( fails == 0 )); then echo "=== RESULT: PASS (all checks)"; exit 0; else echo "=== RESULT: FAIL ($fails failed checks) — see $OUT/run.log"; exit 1; fi
