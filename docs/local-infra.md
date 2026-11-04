# Local infrastructure (TASK-016)

Development only. Synthetic data only: never load real group messages into these containers.
Status (2026-10-28, executed on the developer's Mac, Docker Desktop 29.9.2, arm64, 7.75 GiB):
PostgreSQL 16.4: schema V1 plus 24 contract tests plus 1 RLS isolation test pass, and data persists. Neo4j 5.26 community:
health, Bolt, HTTP and persistence pass. Vespa 8.778.45: `scripts/vespa-verify.sh` reports 22/22 PASS (feed, BM25,
hybrid, cross-group nearest-neighbour isolation with positive controls, delete, persistence). All images are pinned
by digest (trust on first use). The end-to-end slice (`scripts/e2e-verify.sh`) has not been run yet.

## Services (`docker/compose.yml`)

| Service  | Image (tag unverified)        | Host ports (127.0.0.1 only)      | Healthcheck              |
|----------|-------------------------------|----------------------------------|--------------------------|
| postgres | `postgres:16.4`               | 55432                            | `pg_isready`             |
| neo4j    | `neo4j:5.26-community`        | 57474 (HTTP), 57687 (Bolt)         | `cypher-shell RETURN 1`  |
| vespa    | `vespaengine/vespa:8.461.29`  | 58080 (query), 59071 (config)     | config server `/state/v1/health` |

Credentials come from the environment. The defaults (`dita-dev-not-secret`, `dita-app-dev-not-secret`)
are deliberately non-secret placeholders. To override, export the variables or put them in an untracked
`docker/.env`: `DITA_DB_NAME`, `DITA_DB_OWNER`, `DITA_DB_OWNER_PASSWORD`, `DITA_APP_PASSWORD`,
`DITA_NEO4J_PASSWORD`, `DITA_DB_PORT`, and so on.

### Postgres roles

- `dita` (`POSTGRES_USER`) owns the schema and runs migrations. In the image it is a superuser, so it
  **bypasses RLS**. Never point the application or the tests at it.
- `dita_app` is created by `docker/postgres/init/01-app-role.sh` on first start of an empty volume.
  It is `NOSUPERUSER NOBYPASSRLS` and gets DML rights on tables the owner creates later, through
  default privileges. The application and the tests connect as this role.
  `PostgresRlsIsolationTest` fails loudly if its role can bypass RLS.

## Start

```sh
cd /path/to/Dita
docker compose -f docker/compose.yml up -d
docker compose -f docker/compose.yml ps        # wait for postgres "healthy"
```

The init script runs only on an empty data volume. To start over (this **destroys local dev data**):
`docker compose -f docker/compose.yml down -v`.

## Apply the schema (`db/migration/V1__core.sql`)

Option A, psql inside the container (the migration directory is mounted read-only at `/migrations`):

```sh
cd /path/to/Dita
docker compose -f docker/compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U dita -d dita -1 -f /migrations/V1__core.sql
```

Option B, Flyway CLI image (tag unverified; needs registry access). Flyway records the migration in
`flyway_schema_history`, so do not mix A and B on the same database:

```sh
cd /path/to/Dita
docker run --rm --network dita_default -v "$PWD/db/migration:/flyway/sql:ro" flyway/flyway:11.7.2 \
  -url=jdbc:postgresql://postgres:5432/dita -user=dita -password="${DITA_DB_OWNER_PASSWORD:-dita-dev-not-secret}" \
  -locations=filesystem:/flyway/sql migrate
```

Check it:

```sh
docker compose -f docker/compose.yml exec -T postgres psql -U dita -d dita -c '\dt'
```

## Run the tests

Offline build. Without a database, the Postgres tests are reported as **skipped** through JUnit assumptions:

```sh
cd /path/to/Dita
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -pl dita-core,dita-store-postgres test
```

Against the local database, as `dita_app` (after the schema has been applied):

```sh
cd /path/to/Dita
DITA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/dita \
DITA_TEST_DB_USER=dita_app \
DITA_TEST_DB_PASSWORD="${DITA_APP_PASSWORD:-dita-app-dev-not-secret}" \
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -pl dita-core,dita-store-postgres test
```

What runs:

- `InMemoryMessageStoreContractTest` always runs. `PostgresMessageStoreContractTest` runs only when the
  database is configured. Both use the same `MessageStoreContractTest`.
- `PostgresMessageStoreContractTest` adds two checks: a nested cross-group scope is refused, and the
  outbox row is ids-only and rolls back with the transaction.
- `PostgresRlsIsolationTest` runs raw SQL with `app.group_id` set to group A. It checks that A cannot
  read, update, delete or insert group B rows, and that nothing is visible when `app.group_id` is unset.
- `PostgresEventPublisherTest` is offline. It checks that payloads are reduced to ids.

Each test creates its own `chat_group` rows with random ids (`test-<label>-<uuid>`). The tests never
truncate or delete. To reset, use `down -v` (above).

## Id mapping

Core ids (`GroupScope.groupId`, `Message.id`, `KnowledgeItem.id`, Dita reply ids) are opaque strings
such as `g-parents`. They are stored as `text` exactly as supplied. Core ids are unique only within a
group, so group-owned tables key on `(group_id, id)`. The platform adapter builds `Message.id` from the
platform message id. `message.platform_message_id` is an optional column for the raw value when the
two differ.

## Verification script

`scripts/infra-verify.sh` runs the whole bring-up and verification for the `dita` compose project only. It
fails fast if a host port is held by something else; it never stops other processes. It also tests only through
the port Docker maps to the Dita container (`docker compose port`).
`scripts/infra-verify.sh --reset-dita-volumes` removes only the `dita_*` dev volumes, which hold synthetic data.

2026-10-28 incident: host port 5432 was held by a non-Dita Postgres. The first run's tests reached that server
and were rejected at authentication (`role "dita_app" does not exist`), so nothing was written to it. Dita now
uses non-default host ports.

## Vespa (resolution and verification)

`scripts/vespa-verify.sh` touches only the vespa service of the `dita` project (`up -d --no-deps vespa`; postgres and
neo4j are never restarted). It:
1. resolves the newest official `vespaengine/vespa` `8.x.y` tag that has an arm64 image (Docker Hub tags API), pulls it,
   checks the arch and the installed version, and pins `tag@digest` in `docker/compose.yml` (the first guess,
   `8.461.29`, did not exist);
2. deploys `vespa/app`;
3. tests feed, group-filtered BM25, hybrid (`nearestNeighbor` OR `userQuery()`, rank profile `hybrid` =
   bm25 + 10·closeness), a cross-group nearest-neighbour isolation probe, deletion, and persistence of documents and
   deletions across a vespa-only restart.

Memory: Vespa is limited to 4 GB (with postgres 768 MB and neo4j 1.5 GB, ≈ 6.3 GB of 7.75 GiB). `services.xml` raises the
feed-blocking resource limits to 0.90 for laptop development. Hybrid uses 8-dimensional **synthetic** vectors: this
proves the mechanics only. The real multilingual embedder and dimension are G3.

## End-to-end slice

`scripts/e2e-verify.sh` (after `vespa-verify.sh` passes) applies `V2__outbox_relay.sql` if missing, then runs every
module's tests with all gated integration tests enabled. That includes `EndToEndSliceTest`: Postgres →
durable relay → Neo4j + Vespa → retrieval → verified answer → early deletion propagating to both projections. Neo4j
stores structure only, never message text.
