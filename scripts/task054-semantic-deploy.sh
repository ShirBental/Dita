#!/usr/bin/env bash
# TASK-054 G1: deploy LOCAL semantic retrieval for raw messages to the DEMO Vespa (docker compose project "dita",
# service "vespa", container hostname vespa-container, config server 127.0.0.1:59071, query 127.0.0.1:58080).
# PREPARED BY NICK FURY, NOT EXECUTED. Every step that changes a service needs operator's explicit approval first.
#
#   bash scripts/task054-semantic-deploy.sh build    MODEL_DIR   # no service change: verify hashes, build + zip package
#   bash scripts/task054-semantic-deploy.sh deploy   --approved-by-operator=REF  # SERVICE CHANGE: activate the built package
#   bash scripts/task054-semantic-deploy.sh status                           # read-only: app status + a semantic probe query
#   bash scripts/task054-semantic-deploy.sh reindex  --approved-by-operator=REF  # SERVICE CHANGE (optional): re-embed old messages
#   bash scripts/task054-semantic-deploy.sh rollback --approved-by-operator=REF  # SERVICE CHANGE: re-activate the verified base
# The --approved-by-operator argument is a deliberate speed bump logged to build/task054-semantic/approvals.log; it is NOT a
# security boundary (anyone with a shell can type it). Stop Dita (or restart it without semantic mode) before rollback.
#
# MODEL_DIR must hold the approved TASK-049 freeze files (no download here):
#   model.onnx      sha256 828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35  (BAAI/bge-small-en-v1.5 @5c38ec7c, onnx/model.onnx)
#   tokenizer.json  sha256 d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66
# What the package changes (additive): services.xml gains the dita-english embedder; knowledge.sd gains the TASK-049
# derived embedding + dense profile; message.sd gains derived text_embedding (384-D) + dense profile. The existing
# message placeholder field `embedding` (8-D) is untouched. Documents are not deleted by deploy.
set -euo pipefail
cd "$(dirname "$0")/.."
CFG=http://127.0.0.1:${DITA_VESPA_CONFIG_PORT:-59071}
Q=http://127.0.0.1:${DITA_VESPA_QUERY_PORT:-58080}
OUT=build/task054-semantic
MODEL_SHA=828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35
TOK_SHA=d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66
die() { echo "FAIL: $*" >&2; exit 1; }
approved() {   # $1 = action, $2 = the --approved-by-operator=REF argument
  [[ ${2:-} =~ ^--approved-by-operator=([A-Za-z0-9._:-]{3,64})$ ]] || die "$1 changes the demo Vespa: pass --approved-by-operator=<reference to operator's approval>"
  mkdir -p "$OUT"; echo "$(date -u +%FT%TZ) $1 approved-by-operator=${BASH_REMATCH[1]}" >> "$OUT/approvals.log"
}

case "${1:-}" in
build)
  MODEL_DIR=${2:?usage: build MODEL_DIR}
  [[ -f $MODEL_DIR/model.onnx && -f $MODEL_DIR/tokenizer.json ]] || die "MODEL_DIR needs model.onnx and tokenizer.json"
  [[ $(shasum -a 256 "$MODEL_DIR/model.onnx" | cut -d' ' -f1) == "$MODEL_SHA" ]] || die "model.onnx is not the approved freeze"
  [[ $(shasum -a 256 "$MODEL_DIR/tokenizer.json" | cut -d' ' -f1) == "$TOK_SHA" ]] || die "tokenizer.json is not the approved freeze"
  for f in package package.zip rollback-base.zip zips.sha256 manifest.json; do
    [[ ! -e $OUT/$f ]] || die "$OUT/$f exists; move $OUT aside first (build outputs are never overwritten or merged)"
  done
  mkdir -p "$OUT"
  python3 -I - "$MODEL_DIR" "$OUT/manifest.json" <<'PY'
import json, os, sys
d, out = sys.argv[1], sys.argv[2]
json.dump({"approval_reference": "TASK-049 freeze (outputs/TASK-049-base-manifest.json model block); TASK-054 G1 message embedding",
           "model_id": "BAAI/bge-small-en-v1.5", "revision": "5c38ec7c405ec4b44b94cc5a9bb96e735b38267a",
           "model_path": os.path.relpath(os.path.join(d, "model.onnx"), os.path.dirname(out)),
           "model_sha256": "828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35",
           "tokenizer_path": os.path.relpath(os.path.join(d, "tokenizer.json"), os.path.dirname(out)),
           "tokenizer_sha256": "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
           "vespa_host": "vespa-container"}, open(out, "w"), indent=2)
PY
  python3 -I scripts/task054-message-embedding-package.py "$OUT/manifest.json" "$OUT/package"
  (cd "$OUT/package" && zip -qr ../package.zip .)
  (cd vespa/app && zip -qr "$PWD/../../$OUT/rollback-base.zip" .)
  shasum -a 256 "$OUT/package.zip" "$OUT/rollback-base.zip" | tee "$OUT/zips.sha256"
  diff -ru vespa/app "$OUT/package" --exclude=models > "$OUT/package.diff" || true
  echo "built: $OUT/package.zip (diff vs base: $OUT/package.diff); rollback: $OUT/rollback-base.zip. Nothing deployed."
  ;;
deploy)
  approved deploy "${2:-}"
  [[ -f $OUT/package.zip ]] || die "run build first"
  shasum -a 256 -c "$OUT/zips.sha256" >/dev/null || die "zip changed since build"
  curl -fsS "$CFG/state/v1/health" | grep -q '"up"' || die "config server not up"
  code=$(curl -sS -o "$OUT/deploy.json" -w '%{http_code}' -H 'Content-Type: application/zip' --data-binary @"$OUT/package.zip" \
    "$CFG/application/v2/tenant/default/prepareandactivate"); cat "$OUT/deploy.json"; echo
  [[ $code == 200 ]] && ! grep -q '"error-code"' "$OUT/deploy.json" || die "deploy rejected (HTTP $code, see $OUT/deploy.json); nothing activated"
  echo "activated. Read the 'configChangeActions' in $OUT/deploy.json: any 'reindex'/'restart' action is listed there."
  t=0; until curl -fsS -o /dev/null "$Q/ApplicationStatus" || (( t > 300 )); do sleep 5; t=$((t+5)); done
  bash "$0" status
  ;;
status)
  curl -fsS "$Q/ApplicationStatus" -o /dev/null && echo "query container up"
  # the same probe the application runs at startup (group "task054-readiness" has no documents: 0 hits is the success)
  mkdir -p "$OUT"
  curl -fsS -m 20 -H 'Content-Type: application/json' "$Q/search/" -d '{"yql":"select message_id, group_id from message where group_id contains \"task054-readiness\" and ({targetHits:1,approximate:false}nearestNeighbor(text_embedding,q))","ranking":"dense","hits":1,"semanticQuery":"semantic message retrieval readiness","input.query(q)":"embed(dita-english, @semanticQuery)","timeout":"5000ms"}' \
    | tee "$OUT/probe.json"; echo
  grep -q '"root"' "$OUT/probe.json" && ! grep -q '"errors"' "$OUT/probe.json" || die "semantic message probe failed"
  echo "semantic message probe OK"
  ;;
reindex)
  approved reindex "${2:-}"
  # Only needed for messages stored BEFORE the deploy (the demo uses messages of the confirmed session only, which are
  # fed after the deploy and embedded on write). Vespa reindexing re-runs the indexing script on stored documents.
  # UNVERIFIED endpoint shape for this self-hosted version: check "$CFG/application/v2/tenant/default/application/default/environment/prod/region/default/instance/default/reindexing" first.
  curl -sS -X POST "$CFG/application/v2/tenant/default/application/default/environment/prod/region/default/instance/default/reindex?clusterId=dita&documentType=message" | tee "$OUT/reindex.json"; echo
  ;;
rollback)
  approved rollback "${2:-}"
  [[ -f $OUT/rollback-base.zip && -f $OUT/zips.sha256 ]] || die "no verified rollback-base.zip from build; refusing to improvise a base"
  shasum -a 256 -c "$OUT/zips.sha256" >/dev/null || die "rollback-base.zip or package.zip changed since build"
  code=$(curl -sS -o "$OUT/rollback.json" -w '%{http_code}' -H 'Content-Type: application/zip' --data-binary @"$OUT/rollback-base.zip" \
    "$CFG/application/v2/tenant/default/prepareandactivate"); cat "$OUT/rollback.json"; echo
  [[ $code == 200 ]] && ! grep -q '"error-code"' "$OUT/rollback.json" || die "rollback rejected (HTTP $code, see $OUT/rollback.json): a field removal may need a validation override (separate reviewed step)"
  echo "base package re-activated; derived message/knowledge embeddings are dropped (recoverable by redeploy). Run Dita without semantic mode."
  ;;
*) sed -n 2,22p "$0"; exit 64 ;;
esac
