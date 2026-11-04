<p align="center">
  <img src="brand/dita-mascot-v5-640.png" alt="Dita logo" width="160">
</p>

# Dita

Dita is a Telegram group-memory prototype built for the Meta Global AI Developer Hackathon. It retrieves
relevant messages locally and uses Meta's Muse model to turn them into a short answer.

The current demo supports English synthetic conversations. For example, after a group discusses a picnic
and changes its time, a member can ask `/ask When and where is the picnic?`.

## How it works

1. **Store messages:** accept messages from configured chats and senders, store them in PostgreSQL, and
   project them to Vespa and Neo4j through a transactional outbox.
2. **Find relevant context:** combine BM25 keyword search, local `bge-small-en-v1.5` semantic message
   retrieval in Vespa, and reply-link expansion.
3. **Reconstruct the conversation:** select nearby exchanges and corrections within a bounded context
   budget of up to 12 messages by default. Ambiguous exchanges can still be selected incorrectly.
4. **Answer:** send the selected context to `muse-spark-1.3` using the `muse-answer-v3` prompt. Semantic
   message mode uses one synthesis call for an answered question and does not run structured extraction.

The demo checks source identifiers and message access, but its strict language validator is disabled.
Source references remain in local run records and are hidden in Telegram replies. If synthesis fails or
is refused, Dita returns its deterministic answer with a label indicating that it is not a Muse answer.

## Requirements

- Java 21 and Maven 3.9+.
- Docker with Compose v2 for PostgreSQL, Neo4j and Vespa.
- Bash, Python 3, `openssl`, `curl`, `zip` and `shasum`.
- For the live demo: your own Telegram bot token, Meta Model API key, and the pinned local embedding model.

The replay and synthesis runners currently use the Apple Silicon Homebrew Java path
`/opt/homebrew/opt/openjdk@21`. On another installation, update their `JAVA_HOME` assignment before running
them. An exported value alone does not override the hard-coded assignment in those runners.

## Build and test

From the repository root, on the supported Homebrew setup:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
mvn -DskipTests package
```

The first build needs network access to download dependencies. `-DskipTests` builds without executing tests.
To run the core, storage, projection, model-client and Telegram tests:

```bash
mvn -pl dita-core,dita-store-postgres,dita-projections,dita-muse,dita-telegram -am test
```

Database, model and live checks may be skipped when their prerequisites are absent. The complete suite
(`mvn test`) also includes evaluation and application tests that require a separately supplied local
evaluation bundle; that bundle is not included in a fresh checkout. Semantic evaluations additionally need
pinned local model files and the `semeval` profile. Add `-o` to Maven commands only after the dependencies
needed by that command are cached.

## Run locally

There are two paths: a recorded synthetic replay with no Telegram or Muse calls, and a live Telegram demo
with paid Muse synthesis. Both use the local Docker stack. There is no bundled web interface.

### 1. Prepare the stack

Start Docker Desktop. For a first-time synthetic development setup, run:

```bash
bash scripts/infra-verify.sh
bash scripts/relay-verify.sh --check
```

The infrastructure script starts the `dita` Compose services, applies V1 if missing, deploys the base Vespa
package and runs infrastructure checks. If the relay check reports missing V2–V4 migrations, run:

```bash
bash scripts/relay-verify.sh
```

Review its migration plan before typing `apply`. Next, prepare the knowledge schema:

```bash
bash scripts/knowledge-verify.sh
```

Review its plan and type `apply` to continue. The script applies V5 if missing and runs its checks. Verify:

```bash
bash scripts/knowledge-verify.sh --check      # must report V5: applied
docker compose -p dita -f docker/compose.yml ps
```

This demo uses V1–V5; do not apply V6 as part of these steps. On an already prepared stack, use the read-only
checks instead of repeating the bootstrap, which can redeploy the base Vespa package.

Default host ports are PostgreSQL **55432**, Neo4j HTTP **57474** / Bolt **57687**, and Vespa query **58080** /
config **59071**, bound to localhost. The live runner assumes these ports. If you change development database
passwords, update the corresponding runner environment variables too. See [local infrastructure](docs/local-infra.md)
for role and credential settings.

### 2. Try the recorded replay

With the stack ready and no other Dita runner active:

```bash
bash scripts/demo-replay-run.sh \
  --chats -1001000000052 --authors 1001,1002,1003,1009 --approver 1009
```

These are fictional identifiers matching the included fixture. The replay uses PostgreSQL, Neo4j and Vespa
with recorded Muse responses. It does not contact Telegram or Muse and needs no credentials for those APIs.
Replies appear in the terminal and in `build/demo-replay-run/transcript.txt`. Press **Ctrl-C** after the
replay answer appears. Stop this runner before starting the live demo.

### 3. Configure the live demo

Create `.env.local` in the repository root, replacing these placeholders with your credentials:

```dotenv
TELEGRAM_BOT_TOKEN=YOUR_TELEGRAM_BOT_TOKEN
MODEL_API_KEY=YOUR_META_MODEL_API_KEY
```

Use exactly one assignment per credential. Configure the Telegram routing separately:

```bash
cp config/telegram.example.env config/telegram.local.env
```

Edit the local file with your synthetic group's chat id, primary author, allowed session authors and bot
username. The primary author must appear in the comma-separated author list. Use plain `KEY=value` lines,
without quotes, `export` or spaces. `DITA_DEMO_CHAT`, `DITA_DEMO_AUTHOR`, `DITA_DEMO_AUTHORS` and
`DITA_DEMO_BOT` environment variables override the corresponding file values.

Both local files are ignored by Git. The example contains fictional identifiers that must be replaced for
a live session. Use a bot that can receive ordinary group messages, not only slash commands; Telegram bot
privacy settings affect that. See [the trial runbook](docs/task054-telegram-synthesis-trial.md) for details.

### 4. Deploy the embedding model

Obtain the pinned `bge-small-en-v1.5` files, `model.onnx` and `tokenizer.json`, separately. They are not bundled
or downloaded by the deployment script. The script verifies their SHA-256 values; model provenance and
hashes are recorded in [the hybrid retrieval implementation notes](docs/TASK-049-IMPLEMENTATION.md).

```bash
bash scripts/task054-semantic-deploy.sh build /absolute/path/to/approved-model
```

After approving the change to your local Vespa service, deploy the package with an approval reference:

```bash
bash scripts/task054-semantic-deploy.sh deploy --approved-by-operator=YOUR_APPROVAL_REFERENCE
bash scripts/task054-semantic-deploy.sh status
```

Replace both example paths/references with your own values. The semantic runner requires this package and
refuses startup if its semantic readiness check fails.

### 5. Start the live demo

Starting this runner enables paid Muse calls without a per-call confirmation screen:

```bash
bash scripts/telegram-synthesis-trial.sh postgres semantic
```

From the allowed accounts, send a new fictional conversation in the configured Telegram group:

```text
The picnic is Saturday at noon at Meadow Park.
Correction: Saturday at one, at the west gate. If it rains, we postpone.
/ask When and where is the picnic, and what happens if it rains?
```

Only messages within the session window are eligible. The process uses a $1 reservation budget, a six-call
Muse client ceiling and no automatic retries. The reservation budget is a local estimate, not a provider
billing cap. Restarting creates a new process budget.

Press **Ctrl-C** to stop. Run records are written to `build/telegram-synthesis-trial/<run-tag>/`; the script
checks the logs and records for credential leakage after stopping. To reuse a particular recorded session:

```bash
bash scripts/telegram-synthesis-trial.sh postgres semantic --continue-session=RUN_TAG
```

Replace `RUN_TAG` with the recorded `run-...` directory name. If startup reports another Dita process,
stop the existing runner before trying again.

## Project structure

| Directory | Purpose |
|---|---|
| `dita-core/` | Domain model, ingestion, retrieval, context selection and answer validation. |
| `dita-store-postgres/` | PostgreSQL persistence and transactional outbox. |
| `dita-projections/` | Vespa and Neo4j projections and retrievers. |
| `dita-muse/` | Meta Model API client, synthesis prompts and knowledge proposer. |
| `dita-telegram/` | Telegram adapter, polling and sender/chat restrictions. |
| `dita-app/` | Spring Boot wiring, workers and demo controllers. |
| `dita-eval/`, `dita-semeval/` | Offline evaluation tools and optional semantic evaluations. |
| `db/`, `docker/`, `vespa/` | Database migrations, local services and Vespa schemas. |
| `scripts/` | Setup, demo and verification runners. |
| `docs/` | Configuration, runbooks, architecture and evaluation records. |
| `brand/` | Dita's logo and mascot assets. |

## Limitations

- This is an English-language prototype tested on synthetic conversations, not a production service.
- Retrieval and context selection can miss information or include an ambiguous exchange. Source-id checks
  do not guarantee that a model answer is correct.
- The current live demo hides citations and disables strict linguistic validation.
- Multilingual support, WhatsApp and in-chat coordination are outside the demonstrated path.
- The separate structured-knowledge retrieval path has recorded real-stack acceptance evidence; it is not
  the extraction-free semantic message mode described above. See [the verification record](docs/hybrid-verification-record.md).
- Use fictional conversations only. Model requests leave the machine; never load real chat exports.

See the [documentation index](docs/README.md) for configuration, architecture and detailed runbooks.
