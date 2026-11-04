# Dita documentation index

Status as of 2026-11-04 (TASK-055). The tables distinguish current guidance, designs, and dated evidence. Historical documents are kept
as evidence with their existing body preserved; banners point to current guidance. Nothing was deleted.

## Current

| Topic | Source of truth | Notes |
|---|---|---|
| Overview, build, run, code map | [../README.md](../README.md) | Start here. |
| What works, with evidence | [capability-status.md](capability-status.md) | Some rows predate the TASK-054 demo; see the 2026-11-04 note at its top. |
| Live demo runbook | [task054-telegram-synthesis-trial.md](task054-telegram-synthesis-trial.md) | The header describes the current flow. Later sections include older approval/extraction flows, marked as such. |
| Demo answer checks (strict validator, references) | [task054-contextual-validation.md](task054-contextual-validation.md) | The strict validator is off in the demo runner (operator's choice). |
| Configuration and properties | [configuration.md](configuration.md) | |
| Local stack (Docker, roles, ports) | [local-infra.md](local-infra.md) | It covers V1. V2–V4 are applied by `scripts/relay-verify.sh`, V5 by `scripts/knowledge-verify.sh`. V6 is not applied. |
| Hybrid retrieval and the frozen embedder | [TASK-049-IMPLEMENTATION.md](TASK-049-IMPLEMENTATION.md) | Written in Codex's isolated copy. Prior real-stack recorded-Muse acceptance passed 3/3 stages; see [run evidence](hybrid-verification-record.md). Current code was not rerun. |
| Hybrid credentials | [task052-hybrid-credentials.md](task052-hybrid-credentials.md) | |
| Deletion and corrections | [deletion-policy.md](deletion-policy.md), [TASK-046-deleted-correction-policy.md](TASK-046-deleted-correction-policy.md) | Known contradiction with configuration.md on multi-source knowledge. Open; check the store deletion implementations and their tests; `DeletionPolicy` only classifies early versus late deletion. |
| Security findings | [security/finding-id-registry.md](security/finding-id-registry.md) | Open findings block real-data use. |
| Architecture | [rfc/RFC-001-architecture.md](rfc/RFC-001-architecture.md), [rfc/RFC-002-evidence-knowledge-model.md](rfc/RFC-002-evidence-knowledge-model.md) | RFC-001's module list is outdated: `dita-graph-neo4j` and `dita-retrieval-vespa` became `dita-projections`. Trust `pom.xml` and the README code map. |
| Event transport decision (Kafka) | [adr/ADR-001-event-transport.md](adr/ADR-001-event-transport.md) | **Open** decision (DEC-020). Not resolved by TASK-055. |
| Team | [team.md](team.md) | Its module names are outdated (as in RFC-001). |

## Historical (kept as evidence; do not follow as instructions)

| Document | Why historical |
|---|---|
| [plan.md](plan.md) | Early plan and status (Oct 2026). The current status is capability-status.md. |
| [integration-plan.md](integration-plan.md), [integration-plan-demo.md](integration-plan-demo.md) | Pre-integration plans; they say "never run", and that was later superseded. |
| [adr/ADR-001-morning-briefing.md](adr/ADR-001-morning-briefing.md) | A briefing note, not an ADR. It shares the number ADR-001 with the event-transport ADR; renaming waits until DEC-020 is decided. |
| [Muse integration audit](audit/2026-10-29-muse-integration-audit.md), [Stage 0 readiness](audit/2026-10-29-stage0-readiness.md), [capability accountability](audit/2026-11-02-capability-accountability.md) | Point-in-time audits; current runtime status is in the README and capability register. |
| [../connectivity/README.md](../connectivity/README.md) | Python reference client from before `dita-muse`. |
| [../eval/BENCHMARK.md](../eval/BENCHMARK.md) | Benchmark design, written before measurements; results are in eval/value-benchmark and the task reports. |

## Scripts

| Script | Purpose | Calls paid APIs? |
|---|---|---|
| `telegram-synthesis-trial.sh` | **The live demo** (TASK-054). | Yes: Muse, $1 budget per process |
| `task054-semantic-deploy.sh`, `task054-message-embedding-package.py`, `task049-package.py` | Build and deploy the local semantic package. | No |
| `demo-replay-run.sh` | Recorded synthetic replay on the real stack (TASK-051). | No |
| `infra-verify.sh`, `vespa-verify.sh`, `relay-verify.sh`, `knowledge-verify.sh` | Stack checks; the relay and knowledge scripts apply migrations only after a typed confirmation. | No |
| `e2e-verify.sh`, `e2e-replay-verify.sh`, `e2e-hybrid-verify.sh`, `capability-verify.sh` | End-to-end and capability checks (see capability-status.md for which were run). | No |
| `muse-live.sh`, `controlled-muse-trial.sh`, `controlled-synthesis-trial.sh`, `semeval-muse.sh` | Earlier approval-gated live Muse experiments. | Yes, per-call approval |
| `telegram-run.sh`, `telegram-verify.sh` | First live Telegram run (TASK-040: in-memory, Muse off). | No |
| `task046-freeze.sh`, `task052-preflight.sh`, `lab-recorded-verify.sh`, `test-task049-package.py` | Task-specific freeze, preflight and lab checks. | No |

## Records

Decisions: `.avengers/decisions.md` (DEC-NNN). Tasks and evidence: `.avengers/tasks.md`, append-only. Product notes:
`.peter-parker/`. Notion synchronization must be verified separately; local records include pending updates.

## Additional document statuses

| Document | Status |
|---|---|
| [hybrid-verification-record.md](hybrid-verification-record.md) | Historical real-stack execution evidence; scoped to its recorded tree. |
| [git-readiness.md](git-readiness.md) | Git setup and pre-commit review as of 2026-11-04; publication risks remain. |
| [cleanup-validation.md](cleanup-validation.md) | Current cleanup verification and limitations; no new runtime acceptance claim. |
| [../prototypes/kafka-spike/README.md](../prototypes/kafka-spike/README.md) | Experimental proposal; not runtime proof. Kafka decision remains open. |
| [../prototypes/kafka-spike/proposed-relay-fix.md](../prototypes/kafka-spike/proposed-relay-fix.md) | Historical proposal; verify current relay implementation before use. |

### Evaluation document inventory

These are versioned designs, fixtures and point-in-time results, not current deployment instructions.
Statuses below classify their role, not whether every result has been independently reproduced. Sealed data
is listed by path only and was not inspected or used to tune this cleanup.

| Document | Status |
|---|---|
| [eval/BENCHMARK.md](../eval/BENCHMARK.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/TASK-044-FINAL-REPORT.md](../eval/TASK-044-FINAL-REPORT.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-044-capability-report.md](../eval/TASK-044-capability-report.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-044-structured-memory-report.md](../eval/TASK-044-structured-memory-report.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-045/replay-totals.md](../eval/TASK-045/replay-totals.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-045/trust-b2-unseen-report.md](../eval/TASK-045/trust-b2-unseen-report.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-045/trust-report-after.md](../eval/TASK-045/trust-report-after.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-045/trust-report-before.md](../eval/TASK-045/trust-report-before.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-045-FINAL-REPORT.md](../eval/TASK-045-FINAL-REPORT.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-046/PREREGISTRATION.md](../eval/TASK-046/PREREGISTRATION.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/TASK-046/READY-FOR-CODEX-REVIEW.md](../eval/TASK-046/READY-FOR-CODEX-REVIEW.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-046/SEMANTIC-RELATION-EVAL-DESIGN.md](../eval/TASK-046/SEMANTIC-RELATION-EVAL-DESIGN.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/TASK-046/semeval/CASE-SCHEMA.md](../eval/TASK-046/semeval/CASE-SCHEMA.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/TASK-046/semeval/REVIEWED-PREVIEWS.md](../eval/TASK-046/semeval/REVIEWED-PREVIEWS.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/TASK-046/semeval/dev/agreement.md](../eval/TASK-046/semeval/dev/agreement.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-047/codex-blocker-fix/README.md](../eval/TASK-047/codex-blocker-fix/README.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-047/codex-round2/READY-FOR-CODEX.md](../eval/TASK-047/codex-round2/READY-FOR-CODEX.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-047/fury-final-review/REVIEW.md](../eval/TASK-047/fury-final-review/REVIEW.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-051/READY-FOR-CODEX.md](../eval/TASK-051/READY-FOR-CODEX.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-052/codex-review-packet.md](../eval/TASK-052/codex-review-packet.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/TASK-053/report.md](../eval/TASK-053/report.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout/results/compare-postgres-live.md](../eval/synthetic-holdout/results/compare-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout/results/score-lab-A-postgres-live.md](../eval/synthetic-holdout/results/score-lab-A-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout/results/score-lab-B-postgres-live.md](../eval/synthetic-holdout/results/score-lab-B-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout/results/score-lab-C-postgres-live.md](../eval/synthetic-holdout/results/score-lab-C-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout/sealed/questions.md](../eval/synthetic-holdout/sealed/questions.md) | Sealed evaluation material; restricted, not inspected. |
| [eval/synthetic-holdout-v2/results/compare-postgres-live.md](../eval/synthetic-holdout-v2/results/compare-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout-v2/results/score-lab-A-postgres-live.md](../eval/synthetic-holdout-v2/results/score-lab-A-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout-v2/results/score-lab-B-postgres-live.md](../eval/synthetic-holdout-v2/results/score-lab-B-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout-v2/results/score-lab-C-postgres-live.md](../eval/synthetic-holdout-v2/results/score-lab-C-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-holdout-v2/sealed/questions.md](../eval/synthetic-holdout-v2/sealed/questions.md) | Sealed evaluation material; restricted, not inspected. |
| [eval/synthetic-holdout-v3/sealed/questions.md](../eval/synthetic-holdout-v3/sealed/questions.md) | Sealed evaluation material; restricted, not inspected. |
| [eval/synthetic-lab/expected_questions.md](../eval/synthetic-lab/expected_questions.md) | Synthetic fixture guidance; not execution evidence. |
| [eval/synthetic-lab/results/compare-in_memory-dec031.md](../eval/synthetic-lab/results/compare-in_memory-dec031.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/compare-in_memory-recorded.md](../eval/synthetic-lab/results/compare-in_memory-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/compare-postgres-live.md](../eval/synthetic-lab/results/compare-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/compare-postgres-recorded.md](../eval/synthetic-lab/results/compare-postgres-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-in_memory.md](../eval/synthetic-lab/results/score-in_memory.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-in_memory_bm25.md](../eval/synthetic-lab/results/score-in_memory_bm25.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-A-in_memory-dec031.md](../eval/synthetic-lab/results/score-lab-A-in_memory-dec031.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-A-in_memory-recorded.md](../eval/synthetic-lab/results/score-lab-A-in_memory-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-A-postgres-live.md](../eval/synthetic-lab/results/score-lab-A-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-A-postgres-recorded.md](../eval/synthetic-lab/results/score-lab-A-postgres-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-B-in_memory-dec031.md](../eval/synthetic-lab/results/score-lab-B-in_memory-dec031.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-B-in_memory-recorded.md](../eval/synthetic-lab/results/score-lab-B-in_memory-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-B-postgres-live.md](../eval/synthetic-lab/results/score-lab-B-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-B-postgres-recorded.md](../eval/synthetic-lab/results/score-lab-B-postgres-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-C-in_memory-dec031.md](../eval/synthetic-lab/results/score-lab-C-in_memory-dec031.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-C-in_memory-recorded.md](../eval/synthetic-lab/results/score-lab-C-in_memory-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-C-postgres-live.md](../eval/synthetic-lab/results/score-lab-C-postgres-live.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-lab-C-postgres-recorded.md](../eval/synthetic-lab/results/score-lab-C-postgres-recorded.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/synthetic-lab/results/score-postgres.md](../eval/synthetic-lab/results/score-postgres.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/COST-DESIGN.md](../eval/value-benchmark/COST-DESIGN.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/value-benchmark/DESIGN.md](../eval/value-benchmark/DESIGN.md) | Evaluation design/schema or preregistration; not execution evidence. |
| [eval/value-benchmark/corpora/README.md](../eval/value-benchmark/corpora/README.md) | Synthetic fixture guidance; not execution evidence. |
| [eval/value-benchmark/results/capability-dev-bm25.md](../eval/value-benchmark/results/capability-dev-bm25.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/results/capability-dev-failures.md](../eval/value-benchmark/results/capability-dev-failures.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/results/capability-dev-postgres.md](../eval/value-benchmark/results/capability-dev-postgres.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/results/capability-dev.md](../eval/value-benchmark/results/capability-dev.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/results/cost-prelim.md](../eval/value-benchmark/results/cost-prelim.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/results/stage0-retrieval-dev.md](../eval/value-benchmark/results/stage0-retrieval-dev.md) | Historical task/evaluation record; scoped to its recorded version and run. |
| [eval/value-benchmark/sealed/test/README.md](../eval/value-benchmark/sealed/test/README.md) | Sealed evaluation material; restricted, not inspected. |

Internal `.avengers/` and `.peter-parker/` Markdown files are append-only operational/product records, not
public onboarding instructions. `AGENTS.md` and `CLAUDE.md` describe local working rules; they are not evidence
of implemented features. Brand assets and local model cards are outside this runtime documentation index.
