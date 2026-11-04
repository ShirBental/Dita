> Privacy cleanup update: tracked demo identifiers and local home paths have been replaced with fictional/generic values. Personal routing is now read from ignored `config/telegram.local.env`. Earlier findings below describe the pre-cleanup snapshot.

# Git readiness review — 2026-11-04

## Ignore-policy update after review

The user requested that the identified local-only material be excluded from ordinary Git additions.
`.gitignore` now excludes root `.avengers/`, `.peter-parker/`, `outputs/` and all `eval/**/sealed/` directories.
No files were deleted or moved. Source code, including TASK-049/hybrid retrieval, remains eligible.
The original inventory below describes the pre-exclusion snapshot. The complete benchmark/application
suite needs separately provisioned local evaluation resources; the README explains the available commands.
At that intermediate stage real Telegram identifiers had not yet been sanitized; the subsequent privacy cleanup described above replaced them in tracked files.

## Observed state

The first check found no `.git`. During this review Git metadata appeared in the live directory. The final
read-only inspection found branch `main`, no commits, no staged or tracked files and no remotes. This review
did not initialize the live repository, stage files, commit, alter Git configuration or push anything.
The prior cleanup report accurately describes its earlier snapshot; it is not current Git status.

## Checks and findings

- Inspected candidate paths using an isolated temporary Git index/repository against the live work tree and
  its ignore rules. Initial inventory: 713 eligible files, 7,579,781 bytes; largest file 203,432 bytes.
  No eligible file exceeded 5 MiB. Only one Git directory was found, at the project root.
- Verified that `.env.local`, `.env.task049`, personal Claude/Codex settings and model weights are ignored.
  Credential files were not opened. Ignored does not mean a credential is absent from disk.
- Scanned eligible text for private-key headers, GitHub/AWS/OpenAI-style credentials, Telegram token shapes,
  bearer literals, credential assignments and URLs containing user/password fields. Reported Telegram-token
  matches in six test files are explicitly SYNTHETIC. Both user/password URL matches are negative validation
  tests. Other assignment matches are documented synthetic examples or local development defaults.
  No confirmed live credential was found in this scoped heuristic scan; it is not a guarantee of absence.
- `.mcp.json` contains a Notion service endpoint, with no embedded credential. Shared agent definitions remain
  trackable; machine-specific `.codex/config.toml` and `.claude/settings.local.json` remain ignored.
- Fifteen eligible files contain the configured real Telegram identifiers, fifteen contain home-directory
  paths, and four contain Notion references. Counts are by matching file, and categories overlap.
  These values were not changed because the demo and evidence reference them.
- In the initial snapshot, sixteen eligible files were beneath `sealed` directories. Their contents were not opened or scanned;
  publishing them could compromise future evaluation. Existing synthetic run/evaluation artifacts were also eligible in that initial snapshot. File size and a secret scan do not establish publication approval.
- Internal `.avengers/`, `.peter-parker/` and `outputs/` were initially eligible. They are now ignored,
  with project history and TASK-049 delivery artifacts preserved locally. A public repository should receive an explicitly reviewed selection;
  do not blindly stage everything merely because `.gitignore` allows it.

## Changes made

Extended `.gitignore` for local `.envrc` files (example template allowed), SQLite database artifacts and
additional Python caches. Existing source, SQL migrations, synthetic fixtures, hash manifests, agent
instructions and referenced TASK-051 logs remain trackable. Nothing was removed from disk.
Updated the README and documentation index to distinguish the earlier no-Git snapshot from the new Git state.
No production code, runtime configuration, TASK-049/hybrid retrieval, test fixtures or running services changed.

## Before a public first commit

Review real chat/sender identifiers, internal records, account/page ids, absolute paths, sealed holdouts and
recorded requests/outputs. Keep synthetic test tokens and development examples clearly labelled. Confirm
third-party licences for any assets intended for distribution. The downloaded model weights are excluded.
Select files deliberately, inspect the staged diff, and only then commit. A private local commit and a public
GitHub publication are separate actions. No publishing action is included in this review.

No code tests were rerun for these documentation/ignore changes. Ignore rules were checked with positive and
negative cases; the earlier isolated Maven verification is documented in cleanup-validation.md. No history
exists to scan for previously committed secrets; no tracked-file removal is needed in this observed state.
