# Dita

Dita is a group-memory agent for chats, built as a Meta Global AI Developer Hackathon prototype. It stores what a
group says, finds the messages relevant to a question locally, and asks Meta's Muse model for one short answer
grounded in those messages.

This README describes what is **built and evidenced** as of 2026-11-04. Intended features that are not built are
listed under [Limits](#limits-and-what-we-do-not-claim). The detailed capability record is
[docs/capability-status.md](docs/capability-status.md); the documentation index is [docs/README.md](docs/README.md).

## What the demo does

In a Telegram group (synthetic conversations only):

1. **Ingest.** Messages from the allow-listed chat and senders are stored in PostgreSQL. A transactional outbox
   projects them to Vespa (search) and Neo4j (reply links).
2. **Retrieve, locally.** For `/ask <question>`, three channels are fused:
   - BM25 keyword search on the question's content words;
   - semantic search with a local, frozen `bge-small-en-v1.5` embedder inside Vespa;
   - reply-link expansion.
3. **Select context.** `ConversationContextSelector` rebuilds the relevant exchanges and keeps each correction with
   the proposal it corrects, within a small budget (about 12 messages).
4. **Synthesize.** It makes **one** Muse call (`muse-spark-1.3`, prompt `muse-answer-v3`) with pseudonymised
   speakers (P1, P2…). There is no extraction call.
5. **Check and reply.** In the demo configuration only reference integrity is enforced: cited ids must be supplied
   messages, plus access checks. Language-level validation (`SynthesisValidator`) is **off**, and sources are
   **hidden** from users but kept in the run evidence. Both are operator's explicit choices for the demo. If Muse fails
   or is refused, Dita replies with its deterministic extractive answer, marked as not a Muse answer.

Evidence: two live runs on the same synthetic picnic question were SYNTHESIZED (run-20261104T172225Z-57c9 and
run-20261104T172459Z-0cc5, recorded by Codex in `.avengers/tasks.md`). Two runs are not a reliability measure.

## Repository map

| Path | What it is |
|---|---|
| `dita-core/` | Domain model, ingestion, retrieval (`retrieval/`), answers and synthesis validation (`answer/`), knowledge model. No framework runtime dependencies. |
| `dita-store-postgres/` | PostgreSQL store, outbox publisher and relay (Spring JDBC, Flyway). |
| `dita-projections/` | Neo4j and Vespa projections and retrievers, including the TASK-049 hybrid knowledge retriever. |
| `dita-muse/` | Muse (Meta Model API) client, answer synthesizer and its prompt versions V1–V3, and the knowledge proposer. |
| `dita-telegram/` | Telegram adapter (polling, allow-lists). |
| `dita-app/` | Spring Boot application that wires everything (`app.dita.app.DitaApplication`). The demo trial lives in `app/trial/`, the workers in `app/config/`. |
| `dita-eval/` | Offline evaluation harness (value benchmark, synthetic lab); not part of the live answering path. |
| `dita-semeval/` | Gated semantic evaluations (NLI, retrieval acceptance). Built only with `-P semeval`; needs local model files. |
| `db/migration/` | Flyway SQL migrations V1–V6. V1–V5 are applied on the demo database and are immutable; V6 is not applied (awaiting approval). |
| `docker/` | Local stack: PostgreSQL 16.4, Neo4j 5.26, Vespa 8, all bound to 127.0.0.1. |
| `vespa/app/` | Vespa application package (schemas). |
| `scripts/` | Runners and verification scripts; see [docs/README.md](docs/README.md#scripts). |
| `eval/` | Synthetic development fixtures and historical reports. Sealed material stays local and is ignored. |
| `prototypes/kafka-spike/` | Isolated Kafka spike, not run. Kafka is an **open** decision (ADR-001 / DEC-020). |
| `connectivity/` | Early Python reference client for the Meta Model API, superseded by `dita-muse`. |
| `docs/` | Architecture (RFC/ADR), capability status, configuration, runbooks. |
| `.avengers/`, `.peter-parker/` | Local-only team/product records; ignored and absent from a fresh checkout. |

## Prerequisites

- Java 21 and Maven 3.9+. The trial/replay scripts hard-code `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
  (Homebrew on Apple Silicon). On another system, adapt that assignment in the scripts before running them;
  exporting a different JAVA_HOME alone does not override it.
- Docker (Compose v2) for the real stack.
- Python 3, Bash, `openssl`, `curl`, `zip` and `shasum` (used by the runner/deployment scripts).
- For the live demo only: a Telegram bot token, a Meta Model API key, and the local `bge-small-en-v1.5` model files.
  These are not in the repository. Hashes are in [docs/TASK-049-IMPLEMENTATION.md](docs/TASK-049-IMPLEMENTATION.md).

## Build and test

Run commands from the repository root. On the demonstrated Homebrew/macOS setup:

```bash
cd /path/to/Dita                    # replace with your checkout directory
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
java -version                      # must report Java 21
mvn -version
docker compose version             # required for replay/live modes
mvn -DskipTests package             # downloads build dependencies on the first run
```

`-DskipTests` builds the code without executing tests. For the core/store/projection/model-client/Telegram
unit suites, without the local benchmark bundle:

```bash
mvn -pl dita-core,dita-store-postgres,dita-projections,dita-muse,dita-telegram -am test
```

The complete local suite is `mvn test` (or `mvn -o test` once dependencies are cached). It includes
`dita-eval` and application tests that use local evaluation resources. A fresh checkout omits ignored
`eval/**/sealed/`; obtain the authorized local evaluation bundle separately before running that complete
suite. Do not publish it merely to make the tests pass. Semantic evaluations also require their separately
provisioned pinned models: `mvn -o -P semeval -pl dita-semeval -am test`.

The prior complete-suite verification used the local fixture bundle: 788 total, 0 failures/errors,
111 skips (677 executed). This is not the expected total for the smaller command above. Stack/model/live
checks may be gated; a skip is not a pass. See [cleanup validation](docs/cleanup-validation.md).

## Run

There is no bundled web UI. The runnable demonstration uses Telegram; a synthetic replay can exercise
answers without contacting Telegram or Muse. Both replay and the live demo need the local Docker stack.
The steps below are instructions; the documentation cleanup did not execute service changes or paid calls.

### 1. Prepare the local stack

Start Docker Desktop. Use a dedicated synthetic development stack, with no other Dita runner active.
For first-time setup, the infrastructure verifier starts the `dita` Compose services, applies V1 if missing,
deploys the base Vespa package and runs infrastructure checks:

```bash
bash scripts/infra-verify.sh
bash scripts/relay-verify.sh --check
```

Review the check output. If V2–V4 are missing, run `bash scripts/relay-verify.sh` and type `apply` only after
reviewing its migration plan. Then run `bash scripts/knowledge-verify.sh`; review its plan and type `apply`
to install V5 if needed and execute its checks. Do not apply V6 for this demo.

```bash
bash scripts/knowledge-verify.sh --check      # must report V5: applied
docker compose -p dita -f docker/compose.yml ps
```

Expected local ports: PostgreSQL 55432, Neo4j HTTP 57474/Bolt 57687, Vespa query 58080/config 59071.
The live trial assumes these default ports. Local development password defaults are configured in Compose
and the runners; if you override them, keep the corresponding settings consistent. Details and role setup:
[local infrastructure](docs/local-infra.md). Do not reset/delete volumes to fix a startup error without
first deciding whether the stored synthetic session is needed. On an already prepared demo stack, use the
read-only checks instead of rerunning the bootstrap, which can deploy the base Vespa package.

### 2. Replay a synthetic conversation (no paid API calls)

After V1–V5 and the projections are ready, run the included fixture with its explicit synthetic allow-lists:

```bash
bash scripts/demo-replay-run.sh \
  --chats -1001000000052 --authors 1001,1002,1003,1009 --approver 1009
```

This uses the local PostgreSQL/Neo4j/Vespa services and recorded Muse replies; it makes no Telegram or
Muse network calls. Replies print in the terminal and are written to
`build/demo-replay-run/transcript.txt`. Stop with Ctrl-C after the replay answer appears. A second Dita
process causes startup refusal; stop the previous runner yourself before starting another.

### 3. Prepare the live semantic demo

Create a local `.env.local` using your editor, with exactly one assignment per credential:

```dotenv
TELEGRAM_BOT_TOKEN=YOUR_TELEGRAM_BOT_TOKEN
MODEL_API_KEY=YOUR_META_MODEL_API_KEY
```

Replace both placeholders with your own credentials; the file is ignored. Configure routing separately:

```bash
cp config/telegram.example.env config/telegram.local.env
```

Edit `config/telegram.local.env` with your synthetic group's chat id, primary author, comma-separated session
authors and bot username. Use plain `KEY=value` lines without quotes, `export` or spaces. Both Telegram runners
read and validate these local values; the primary author must appear in the session author list. Environment
variables `DITA_DEMO_CHAT`, `DITA_DEMO_AUTHOR`, `DITA_DEMO_AUTHORS` and `DITA_DEMO_BOT` can override the file.
The committed application defaults and tests contain fictional ids only. Do not put personal ids into tracked
configuration, scripts or fixtures. The runner uses explicit local command-line overrides for the application.

Provision the approved `bge-small-en-v1.5` files (`model.onnx` and `tokenizer.json`) separately. The deployment
script verifies their pinned SHA-256 values and does not download them. Hashes and model provenance:
[TASK-049 implementation](docs/TASK-049-IMPLEMENTATION.md).

```bash
bash scripts/task054-semantic-deploy.sh build /absolute/path/to/approved-model
```

After the operator has approved the Vespa service change, deploy using a real approval reference:

```bash
bash scripts/task054-semantic-deploy.sh deploy --approved-by-operator=APPROVAL_REFERENCE
bash scripts/task054-semantic-deploy.sh status
```

`APPROVAL_REFERENCE` is a placeholder for an actual approval, not permission by itself. Startup requires
the semantic package; it refuses to claim semantic retrieval while silently falling back to keywords.

### 4. Start and use the live demo (paid Muse calls)

With paid synthetic-demo use authorized for the session:

```bash
bash scripts/telegram-synthesis-trial.sh postgres semantic
```

It starts silently with a preauthorized session: no confirmation screen and no per-call buttons. Send a
new fictional conversation in the authorized Telegram group, then ask, for example:

```text
Alice: The picnic is Saturday at noon at Meadow Park.
Bob: Correction: Saturday at one, at the west gate. If it rains, we postpone.
/ask When and where is the picnic, and what happens if it rains?
```

Send the conversation text from the allowed accounts, not as a real-chat export. Only messages inside
the session window are eligible. One synthesis call is made for an answered semantic-mode question;
there is no extraction call. Language validation is off and sources are hidden in the current demo.
The trial reserves at most $1 per process; the client also enforces its configured six-call ceiling.
These are local guards, not a provider billing cap. There are no automatic retries.

Ctrl-C stops the foreground runner. Evidence is saved under `build/telegram-synthesis-trial/<run-tag>/`;
the script performs a credential scan after stopping. To explicitly reuse one recorded session later:

```bash
bash scripts/telegram-synthesis-trial.sh postgres semantic --continue-session=RUN_TAG
```

Replace `RUN_TAG` with the actual recorded `run-...` directory name. No old session is reused automatically.
For readiness failures, missing model files, authorization and older modes, see the
[trial runbook](docs/task054-telegram-synthesis-trial.md).

## Configuration

See [docs/configuration.md](docs/configuration.md). The default storage backend is `IN_MEMORY`. Muse and Telegram are
off unless enabled. Prompt selection is `dita.memory.answers.synthesis.prompt`: the default is V2, the runner uses V3.

Cleanup verification and evidence limits: [docs/cleanup-validation.md](docs/cleanup-validation.md).

## Limits and what we do not claim

- English only. Hebrew/multilingual, WhatsApp and in-chat coordination (Demo B) are not built or are switched off.
- Not reliable or production-ready. No real users or real group data: this is synthetic only, and real-data use is
  blocked by open security findings (see [docs/security/finding-id-registry.md](docs/security/finding-id-registry.md)).
- In the demo, answers are not validated for wording and users do not see citations.
- Live Muse extraction is not used in the demo. The TASK-049 knowledge hybrid retriever passed a prior real-stack,
  recorded-Muse acceptance run (3/3 stages, 2026-11-03). This is evidence for that recorded tree, not a
  new verification of today’s code; see [the run record](docs/hybrid-verification-record.md).
- The $1 budget is Dita's own reservation, not a provider billing cap.

## Data and safety rules

All fixtures are synthetic (fictional people). Never load real chats. Model requests leave the machine; send only
synthetic content. Credentials live only in `.env.local`, which is git-ignored.
