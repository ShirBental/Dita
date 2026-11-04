#!/usr/bin/env bash
# TASK-052 preflight (read-only). Checks Codex's TASK-049 artifacts against this tree WITHOUT applying or editing them.
#   scripts/task052-preflight.sh [patch] [report]
# 1. Both artifacts are present; prints their sha256.
# 2. Lists the files the patch touches; flags overlaps with TASK-048 and TASK-051 files; refuses any sealed, holdout,
#    data or .env path.
# 3. Dry-runs the patch on a throwaway copy of the tree (the working tree is never touched).
# Exit codes: 0 = applies cleanly, 3 = conflicts (the dry run lists the rejects), 2 = missing input or forbidden path.
set -uo pipefail
cd "$(dirname "$0")/.."
PATCH=${1:-outputs/TASK-049.patch}; REPORT=${2:-outputs/TASK-049-report.md}
for f in "$PATCH" "$REPORT"; do [[ -r "$f" ]] || { echo "MISSING: $f (place Codex's TASK-049 artifacts at this path)"; exit 2; }; done
PATCH_ABS="$(cd "$(dirname "$PATCH")" && pwd)/$(basename "$PATCH")"
echo "patch  $(shasum -a 256 "$PATCH" | cut -c1-64)"
echo "report $(shasum -a 256 "$REPORT" | cut -c1-64)"
echo "this tree: $(scripts/task046-freeze.sh | tail -1)"

files=$(grep -E '^(\+\+\+|---) ' "$PATCH" | awk '{print $2}' | sed -E 's#^[ab]/##' | grep -v '^/dev/null$' | sort -u)
[[ -n "$files" ]] || { echo "no file headers found in the patch"; exit 2; }
if grep -qE '(sealed|holdout|(^|/)data/|\.env)' <<<"$files"; then echo "FORBIDDEN path in patch:"; grep -E '(sealed|holdout|(^|/)data/|\.env)' <<<"$files"; exit 2; fi

T048='QueryService|KnowledgeAnswerComposer|SelectionPolicy|MessageStore|PostgresMessageStore|V6__|FinalAnswerIsolationTest|Task048'
T051='TelegramAdapter|ReplayTelegramGateway|RecordedMuseTransport|DitaProperties|DitaSettings|TelegramConfiguration|MuseConfiguration|KnowledgeConfiguration|ExtractionWorker|ExtractionService|TelegramReplay|ReplayConfiguration|application.yml'
echo "## files touched"
while read -r f; do
  tag=""; grep -qE "$T048" <<<"$f" && tag="$tag [TASK-048 overlap]"; grep -qE "$T051" <<<"$f" && tag="$tag [TASK-051 overlap]"
  [[ -e "$f" ]] || tag="$tag [new]"
  echo "  $f$tag"
done <<<"$files"

work=$(mktemp -d "${TMPDIR:-/tmp}/task052-dry.XXXXXX")
rsync -a --exclude target --exclude build --exclude data --exclude '.env*' --exclude '*sealed*' --exclude '*holdout*' \
      --exclude outputs ./ "$work/tree/"
echo "## dry run (on a copy in $work)"
if (cd "$work/tree" && patch -p1 --dry-run --batch < "$PATCH_ABS"); then echo "=== APPLIES CLEANLY (dry run only; nothing changed)"; rc=0
else echo "=== CONFLICTS: see the hunks above; resolve by hand against the TASK-052 checkpoint"; rc=3; fi
rm -rf "$work"
exit $rc
