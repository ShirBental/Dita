#!/usr/bin/env bash
# TASK-046 freeze manifest (no git in this repo). Prints one "sha256  path" line per source file and a tree hash
# (sha256 of the sorted manifest). Scope: every module's src/ (main + test + resources), db/, vespa/, pom.xml files.
# Excludes build output, local secrets, data and every sealed / holdout dataset (never read).
# Usage: scripts/task046-freeze.sh [manifest-out]   (verify: re-run and compare the TREE line)
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-}"
manifest=$(find pom.xml */pom.xml dita-*/src db vespa -type f \
    -not -path '*/target/*' -not -path '*/.claude/*' -not -name '.DS_Store' -not -name '.env*' \
    -not -path '*sealed*' -not -path '*holdout*' -print0 \
  | LC_ALL=C sort -z | xargs -0 shasum -a 256)
emit() {
  printf '%s\n' "$manifest"
  printf 'TREE %s  (%s files)\n' "$(printf '%s\n' "$manifest" | shasum -a 256 | cut -d' ' -f1)" "$(printf '%s\n' "$manifest" | wc -l | tr -d ' ')"
}
if [[ -z "$out" ]]; then emit; else emit > "$out"; tail -1 "$out"; fi
