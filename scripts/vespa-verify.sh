#!/usr/bin/env bash
# Vespa resolution + verification for the "dita" compose project only. Does NOT restart postgres or neo4j.
# 1) resolves the newest official vespaengine/vespa 8.x.y tag with an arm64 image (Docker Hub API), pulls it,
#    confirms arch + installed version, pins tag@digest in docker/compose.yml;
# 2) starts vespa (--no-deps), deploys vespa/app, runs feed / bm25 / hybrid / isolation / delete / persistence tests.
# Synthetic data only. Log: build/infra-report/vespa.log (+ JSON responses in build/infra-report/vespa/).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/infra-report; mkdir -p "$OUT/vespa"
exec > >(tee "$OUT/vespa.log") 2>&1
DC=(docker compose -p dita -f docker/compose.yml)
VQ=${DITA_VESPA_QUERY_PORT:-58080}; VC=${DITA_VESPA_CONFIG_PORT:-59071}
Q="http://127.0.0.1:$VQ"; CFG="http://127.0.0.1:$VC"
RESULTS=(); ok() { RESULTS+=("PASS  $1"); echo "=== PASS: $1"; }; bad() { RESULTS+=("FAIL  $1"); echo "=== FAIL: $1"; }
# N-29: any failure before the service is proven to be OUR container aborts; nothing is written to unknown services.
die() { bad "$1"; echo "ABORTING: $1"; printf '%s\n' "${RESULTS[@]}"; exit 1; }
port_free_or_ours() {   # $1 host port, $2 service
  local holder; holder=$(lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1}' | sort -u | tr '\n' ' ')
  [[ -z "$holder" ]] || "${DC[@]}" ps --status running -q "$2" | grep -q . || die "host port $1 held by: $holder (not Dita)"
}
require_mapping() {     # $1 service, $2 container port, $3 expected host port
  local m; m=$("${DC[@]}" port "$1" "$2" 2>/dev/null)
  [[ "$m" == "127.0.0.1:$3" ]] || die "$1:$2 maps to '${m:-nothing}', expected 127.0.0.1:$3"
}
step() { echo; echo "################ $1 ($(date -u +%H:%M:%SZ))"; }

step "1. resolve official arm64 tag"
PINNED=$(grep -Eo 'vespaengine/vespa:8\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}' docker/compose.yml | head -1)
if [[ -n "$PINNED" && "${1:-}" != "--resolve" ]] && docker image inspect "$PINNED" >/dev/null 2>&1; then
  ok "using pinned image $PINNED (pass --resolve to re-resolve)"
  ARCH=$(docker image inspect --format '{{.Architecture}}' "$PINNED"); [[ "$ARCH" == arm64 ]] && ok "image is arm64" || die "image arch is $ARCH"
  SKIP_RESOLVE=1
fi
if [[ -z "${SKIP_RESOLVE:-}" ]]; then
curl -fsS "https://hub.docker.com/v2/repositories/vespaengine/vespa/tags?page_size=100&ordering=last_updated" > "$OUT/vespa/tags.json" \
  || { bad "docker hub tag list"; exit 1; }
TAG=$(python3 - "$OUT/vespa/tags.json" <<'PY'
import json, re, sys
tags = json.load(open(sys.argv[1]))["results"]
ok = [t["name"] for t in tags if re.fullmatch(r"8\.\d+\.\d+", t["name"])
      and any(i.get("architecture") == "arm64" for i in t.get("images", []))]
ok.sort(key=lambda n: tuple(map(int, n.split("."))), reverse=True)
print(ok[0] if ok else "")
PY
)
[[ "$TAG" =~ ^8\.[0-9]+\.[0-9]+$ ]] && ok "newest 8.x.y tag with arm64 on Docker Hub: $TAG" || die "no 8.x.y arm64 tag found"
docker pull "vespaengine/vespa:$TAG" >/dev/null && ok "pulled vespaengine/vespa:$TAG" || die "pull vespaengine/vespa:$TAG"
ARCH=$(docker image inspect --format '{{.Architecture}}' "vespaengine/vespa:$TAG"); echo "image arch: $ARCH"
[[ "$ARCH" == arm64 ]] && ok "image is arm64" || die "image arch is $ARCH"
REF=$(docker image inspect --format '{{index .RepoDigests 0}}' "vespaengine/vespa:$TAG")
docker run --rm --entrypoint sh "vespaengine/vespa:$TAG" -c 'rpm -q vespa 2>/dev/null || ls /opt/vespa/lib/jars | head -3'
DIGEST=${REF#*@}
[[ "$DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || die "unexpected digest '$DIGEST'"
# N-32: pin ALL images by digest (repeatable; trust-on-first-use from Docker Hub, not a signature check). Backup first.
cp docker/compose.yml "$OUT/compose.yml.bak.$(date -u +%Y%m%dT%H%M%SZ)"
pin() {   # $1 repo:tag as written in compose.yml
  local d; d=$(docker image inspect --format '{{index .RepoDigests 0}}' "$1" 2>/dev/null); d=${d#*@}
  [[ "$d" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "not pulled locally, not pinned: $1"; return 0; }
  sed -i '' -E "s#^    image: ${1//./\\.}(@sha256:[0-9a-f]{64})?( .*)?\$#    image: $1@$d   \# pinned $(date -u +%F) from local pull (TOFU)#" docker/compose.yml
}
sed -i '' -E "s#^    image: vespaengine/vespa:.*#    image: vespaengine/vespa:${TAG}@${DIGEST}   \# resolved $(date -u +%F) from Docker Hub tag list (TOFU), arm64#" docker/compose.yml
pin postgres:16.4; pin neo4j:5.26-community
grep -n 'image:' docker/compose.yml
"${DC[@]}" config --quiet || die "compose.yml invalid after pinning (backup in $OUT)"
fi

step "2. start vespa only (postgres/neo4j untouched)"
port_free_or_ours "$VQ" vespa; port_free_or_ours "$VC" vespa
"${DC[@]}" up -d --no-deps vespa || die "compose up vespa"
require_mapping vespa 8080 "$VQ"; require_mapping vespa 19071 "$VC"
id=$("${DC[@]}" ps -q vespa); t=0
until [[ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" == healthy ]] || (( t > 300 )); do sleep 5; t=$((t+5)); done
(( t <= 300 )) && ok "vespa config server healthy after ${t}s" || { "${DC[@]}" logs --tail 100 vespa; die "vespa not healthy"; }

step "3. deploy vespa/app"
rm -f "$OUT/app.zip"; (cd vespa/app && zip -qr "../../$OUT/app.zip" .)
curl -sS -H 'Content-Type: application/zip' --data-binary @"$OUT/app.zip" "$CFG/application/v2/tenant/default/prepareandactivate" | tee "$OUT/vespa/deploy.json"; echo
grep -q '"error-code"' "$OUT/vespa/deploy.json" && die "deploy rejected (see vespa/deploy.json)" || ok "deploy accepted"
t=0; until curl -fsS -o /dev/null "$Q/ApplicationStatus" || (( t > 300 )); do sleep 5; t=$((t+5)); done
(( t <= 300 )) && ok "query/feed container up after ${t}s" || die "container not up"

step "4. feed (synthetic): every response must be HTTP 200 with the expected document id"
: > "$OUT/vespa/feed.json"
feed() {   # $1 docid, $2 body
  local code
  code=$(curl -sS -o "$OUT/vespa/feed-$1.json" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
         "$Q/document/v1/dita/message/docid/$1" -d "$2")
  cat "$OUT/vespa/feed-$1.json" >> "$OUT/vespa/feed.json"; echo >> "$OUT/vespa/feed.json"
  python3 - "$OUT/vespa/feed-$1.json" "$code" "$1" <<'PY' && ok "feed $1 (HTTP 200, id:dita:message::$1)" || bad "feed $1"
import json, sys
r = json.load(open(sys.argv[1])); code, doc = sys.argv[2], sys.argv[3]
print("feed", doc, "http", code, r)
sys.exit(0 if code == "200" and r.get("id") == "id:dita:message::" + doc and "message" not in r else 3)
PY
}
# Distinct vectors: b1's vector is unique, so an unfiltered nearest-neighbour search for it MUST return b1 first.
VA1='[0.9,0.1,0,0,0,0,0,0.1]'; VA2='[0,0,0.9,0.1,0,0,0.1,0]'; VB1='[0,0,0,0,0.9,0.1,0,0]'
feed a1 '{"fields":{"group_id":"vt-group-a","message_id":"a1","text":"Dr Levi on Herzl street is an excellent pediatrician","embedding":'"$VA1"'}}'
feed a2 '{"fields":{"group_id":"vt-group-a","message_id":"a2","text":"who is bringing snacks for the trip","embedding":'"$VA2"'}}'
feed b1 '{"fields":{"group_id":"vt-group-b","message_id":"b1","text":"Dr Levi is the worst pediatrician","embedding":'"$VB1"'}}'
sleep 2

# Every query: HTTP 200 and NO root.errors, otherwise the check FAILS (no false positives from empty error results).
search() {   # $1 JSON body, $2 name; prints a summary; returns 0 only for HTTP 200 without errors
  local code
  code=$(curl -sS -o "$OUT/vespa/$2.json" -w '%{http_code}' -H 'Content-Type: application/json' "$Q/search/" -d "$1")
  python3 - "$OUT/vespa/$2.json" "$code" > "$OUT/vespa/$2.txt" <<'PY'
import json, sys
r = json.load(open(sys.argv[1])); code = sys.argv[2]
root = r.get("root", {}); errs = root.get("errors")
print("http:", code, "errors:", errs, "totalCount:", root.get("fields", {}).get("totalCount"))
for c in root.get("children", []) or []:
    f = c.get("fields", {})
    print("HIT", f.get("group_id"), f.get("message_id"), round(c.get("relevance", 0), 4), f.get("matchfeatures"))
sys.exit(0 if code == "200" and not errs else 3)
PY
  local rc=$?; cat "$OUT/vespa/$2.txt"; return $rc
}
hits()  { grep '^HIT' "$OUT/vespa/$1.txt"; }
only_group() { ! hits "$1" | grep -qv "^HIT $2 "; }          # every hit belongs to group $2
total() { grep -Eo 'totalCount: [0-9]+' "$OUT/vespa/$1.txt" | grep -Eo '[0-9]+'; }

step "4b. regression: a malformed query must be detected as an error (the earlier false positive)"
if search '{"yql":"select * from message where group_id contains \"vt-group-a\" and {targetHits:10}nearestNeighbor(embedding,q)","ranking":"hybrid","input.query(q)":"'"$VB1"'"}' malformed; then
  bad "malformed nearestNeighbor YQL was NOT reported as an error"
else ok "malformed query is reported as an error (regression for the false-positive PASS)"; fi

step "5. bm25 (group-filtered)"
if search '{"yql":"select * from message where group_id contains \"vt-group-a\" and userQuery()","query":"pediatrician Levi","ranking":"bm25","hits":5}' bm25; then
  hits bm25 | grep -q '^HIT vt-group-a a1 ' && only_group bm25 vt-group-a && ok "bm25 finds a1; every hit is group a" || bad "bm25 results"
else bad "bm25 query error"; fi

step "6. hybrid (nearestNeighbor OR userQuery, group-filtered)"
if search '{"yql":"select * from message where group_id contains \"vt-group-a\" and (({targetHits:10}nearestNeighbor(embedding,q)) or userQuery())","query":"pediatrician","ranking":"hybrid","input.query(q)":"[0.85,0.15,0,0,0,0,0,0.1]","hits":5}' hybrid; then
  only_group hybrid vt-group-a && grep -q 'closeness(field,embedding)' "$OUT/vespa/hybrid.txt" && grep -q 'bm25(text)' "$OUT/vespa/hybrid.txt" \
    && ok "hybrid uses bm25 + closeness; every hit is group a" || bad "hybrid results"
  hits hybrid | head -1 | grep -q '^HIT vt-group-a a1 ' && ok "hybrid top hit is a1" || bad "hybrid top hit"
else bad "hybrid query error"; fi

step "7. nearest-neighbour isolation: probe = b1's exact vector"
NN='({targetHits:10}nearestNeighbor(embedding,q))'
if search '{"yql":"select * from message where '"$NN"'","ranking":"hybrid","input.query(q)":"'"$VB1"'","hits":3}' nn-unfiltered; then
  hits nn-unfiltered | head -1 | grep -q '^HIT vt-group-b b1 ' && ok "control: unfiltered probe returns b1 first (vector really matches b1)" || bad "control: b1 not top without filter"
else bad "unfiltered control query error"; fi
if search '{"yql":"select * from message where group_id contains \"vt-group-b\" and '"$NN"'","ranking":"hybrid","input.query(q)":"'"$VB1"'","hits":3}' nn-group-b; then
  hits nn-group-b | head -1 | grep -q '^HIT vt-group-b b1 ' && only_group nn-group-b vt-group-b && ok "control: group b query returns b1" || bad "control: group b"
else bad "group b control query error"; fi
if search '{"yql":"select * from message where group_id contains \"vt-group-a\" and '"$NN"'","ranking":"hybrid","input.query(q)":"'"$VB1"'","hits":10}' isolation; then
  ! hits isolation | grep -q ' b1 ' && only_group isolation vt-group-a && ok "group a query with b1's vector never returns b1" || bad "ISOLATION LEAK"
  [[ "$(total isolation)" == 2 ]] && ok "group filter applied: totalCount=2 (exactly group a's documents)" || bad "group a totalCount=$(total isolation), expected 2"
else bad "isolation query error"; fi

step "8. delete (deletion invariant)"
code=$(curl -sS -o "$OUT/vespa/delete-a2.json" -w '%{http_code}' -X DELETE "$Q/document/v1/dita/message/docid/a2"); cat "$OUT/vespa/delete-a2.json"; echo
[[ "$code" == 200 ]] && ok "delete a2 HTTP 200" || bad "delete a2 HTTP $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$Q/document/v1/dita/message/docid/a2"); echo "GET a2 after delete: $code"
[[ "$code" == 404 ]] && ok "deleted document gone (GET 404)" || bad "delete (GET returned $code)"
if search '{"yql":"select * from message where group_id contains \"vt-group-a\" and userQuery()","query":"snacks","ranking":"bm25"}' afterdelete; then
  ! hits afterdelete | grep -q ' a2 ' && ok "deleted document not searchable" || bad "deleted doc still searchable"
else bad "after-delete query error"; fi

step "9. persistence across vespa restart only"
"${DC[@]}" restart vespa
t=0; until curl -fsS -o /dev/null "$Q/document/v1/dita/message/docid/a1" || (( t > 300 )); do sleep 5; t=$((t+5)); done
curl -fsS "$Q/document/v1/dita/message/docid/a1" | grep -q 'Herzl' && ok "a1 persisted across restart (${t}s)" || bad "persistence"
code=$(curl -s -o /dev/null -w '%{http_code}' "$Q/document/v1/dita/message/docid/a2"); [[ "$code" == 404 ]] && ok "deletion persisted across restart" || bad "deleted doc returned after restart ($code)"
if search '{"yql":"select * from message where group_id contains \"vt-group-a\" and '"$NN"'","ranking":"hybrid","input.query(q)":"'"$VB1"'","hits":10}' isolation-after-restart; then
  ! hits isolation-after-restart | grep -q ' b1 ' && ok "isolation holds after restart" || bad "ISOLATION LEAK after restart"
else bad "post-restart isolation query error"; fi

step "memory"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}' $("${DC[@]}" ps -q)
"${DC[@]}" ps

step "SUMMARY"; printf '%s\n' "${RESULTS[@]}"
nfail=$(printf '%s\n' "${RESULTS[@]}" | grep -c '^FAIL'); echo "failures: $nfail"; (( nfail == 0 ))
