# Avengers team charter: Dita

Coordinator: **Nick Fury** (engineering lead / TPM): integration owner, plan, conflict resolution, combined reporting.

Five agent definitions exist (`.claude/agents/`). The eight workstreams are mapped onto them. Each delegation gets an isolated context: only the module path, its contract (RFC-001 §6) and synthetic fixtures. **Only Shuri has edit and shell access**, so code edits are serialized per module. Reviewers are read-only.

| Workstream | Implementer | Reviewer(s) | Owned paths | Contract in / out |
|---|---|---|---|---|
| WS1 Architecture & integration | Fury | Tony | `docs/rfc`, `dita-app` | RFC-001 |
| WS2 PostgreSQL & data model | Shuri | Tony, Natasha (isolation) | `db/`, `dita-store-postgres` | `MessageStore`, DDL |
| WS3 Neo4j & conversation graph | Shuri | Tony, Bruce | `dita-graph-neo4j` | `GraphProjection`, expansion |
| WS4 Vespa, retrieval & embeddings | Shuri | Bruce | `dita-retrieval-*` | `Retriever`, `Hit` |
| WS5 Muse integration | Shuri | Natasha (egress), Bruce (quality) | `dita-muse`, `connectivity/` | `LlmPort`, budget guard |
| WS6 Telegram | Shuri | Natasha (token, permissions) | `dita-telegram` | `PlatformEvent` |
| WS7 Evaluation & QA | Bruce (design, metrics) | Peter (scenario fit) | `eval/`, `dita-eval` | benchmark schema, metrics |
| WS8 DevOps & delivery | Shuri | Natasha | `docker/`, CI scripts | compose, run scripts |
| Product acceptance | Peter (advisory) | — | `.peter-parker/` | scenario A/B acceptance criteria |

Permissions:
- Reviewers: Read/Glob/Grep plus Notion reporting.
- Shuri: Edit/Write/Bash inside this project only.
- Reserved for the human: deploy, spend, live Muse calls, real tokens, sandbox or security settings, purge of real data, scope changes.
- Natasha's unresolved findings block release.
