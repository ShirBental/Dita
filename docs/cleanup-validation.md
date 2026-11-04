> Privacy cleanup update: tracked demo identifiers and local home paths have been replaced with fictional/generic values. Personal routing is now read from ignored `config/telegram.local.env`. Earlier findings below describe the pre-cleanup snapshot.

# Cleanup verification — 2026-11-04

Scope: README, documentation index, dated historical banners and `.gitignore`. No runtime source,
TASK-049 implementation, hybrid configuration, migrations, fixtures or service processes were changed.

## Evidence checked

- Read `AGENTS.md`, `.avengers/context.md`, reporting contract, TASK-055 entries and TASK-051 ownership claims.
- Of the five requested audits, only Peter and Natasha summaries are present in the local TASK-055 record.
  Tony/Bruce retries are mentioned, but their completed audits and a Shuri result are not recorded there.
  Do not treat five completed audits as verified. Older dated audits are indexed separately.
- Checked parent and module POMs: Java 21, seven default modules, semantic evaluation behind `semeval`;
  `dita-eval` has main-scope dependencies, so describing it as entirely test-scoped would be inaccurate.
- Checked runner flags and application defaults: semantic messages plus BM25/reply links, V3 synthesis,
  no extraction in semantic mode, strict language validation off and sources hidden. Context selector defaults
  to 12 messages. Knowledge hybrid TASK-049 is a separate path; its real-stack acceptance was not rerun.
- TASK-054 live success and 9/9 frozen / 8/10 held-out scores are attributed to prior local task records.
  This cleanup made no paid calls and did not repeat those model or live Telegram evaluations.
- Runner trial limit is 8 calls, but MuseClient enforces its separately configured process limit of 6.
  The effective client ceiling is therefore lower; the $1 reservation is not a provider billing cap.

## Executed checks

`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o test` in an isolated source/config/fixture copy:
**BUILD SUCCESS; 788 total, 0 failures, 0 errors, 111 skipped, 677 executed**. Generated `build/` evidence,
credentials, previous targets and local model weights were excluded. Prior tree records report 89 skips;
these are different input conditions, not proof of additional runtime coverage. No test or production code
was edited to obtain this result. SavedLiveResponseReplayTest passed in the copied source state.

37 positive/negative ignore-rule assertions passed in a temporary Git repository. These cover env files,
private keys, model weights, build output, caches, local settings, trackable templates, agent definitions,
TASK-051 logs, source, evaluation manifests and TASK-049 patch artifacts. New README/index/banner links
were checked for existing local targets. Original historical document bodies remain byte-for-byte intact.

## Remaining risks and Git state

`/path/to/Dita` has no `.git` metadata. Git status, branch, tracked-secret history and commits are
unavailable. No Git repository was initialized there, and no merge, reset, staging or commit was performed.
A before/after patch and content manifest provide the reviewable change record.

Existing real Telegram identifiers, internal account/page ids, local paths, sealed holdouts and historical
outputs still require publication review. Ignore rules cannot sanitize existing tracked history or prove
that a publishable package is safe. Team/product records, outputs and sealed fixtures remain visible to Git
rather than being hidden wholesale. No artifact was deleted or moved.

The deletion-policy/configuration contradiction remains open; `DeletionPolicy` classifies timing, while
store implementations decide multi-source deletion behavior. Kafka remains an open decision. Architecture
RFCs and team module lists are retained with index caveats. Some original historical links may be stale;
the cleanup preserves their bodies rather than rewriting the evidence.

Notion reporting was not synchronized in this cleanup; the local report is the recovery record.
