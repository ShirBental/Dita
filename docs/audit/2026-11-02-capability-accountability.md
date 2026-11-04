> **Historical snapshot (2026-11-04 cleanup).** Existing findings are preserved; current runtime guidance: [documentation index](../README.md).

# Dense-retrieval gap: accountability, capability audit, governance (Nick Fury, 2026-11-02, TASK-050)

## 1. How the gap arose. All of this is in the project records; nothing is inferred.
| Date | Record | What it says |
|---|---|---|
| 2026-10-27 | `docs/plan.md:29,47` | S6 "Vespa hybrid (BM25 + multilingual embeddings) + Vector-RAG baseline", Oct 31–Nov 1. Gate G3 (embedding source), owner Tony + Bruce, due Oct 30. WS4 retrieval & embeddings owner: Shuri (`docs/team.md:12`). |
| 2026-10-27 | `.avengers/tasks.md:31` | TASK-011 known gap: lexical retrieval cannot cross languages; "Vector retrieval (S6) must close this". |
| 2026-10-27 | `.avengers/tasks.md:79` | Tony's G3 recommendation (local multilingual embedder), "Escalation candidate". **No G3 decision was ever recorded.** |
| 2026-10-27 | `docs/plan.md:37-39` | The critical path excludes S6 and calls Vespa and Neo4j "value adds" with fallbacks. |
| 2026-10-28 | DEC-013 (`decisions.md:53`) | `retrieval.dense.enabled` fails startup because it is unimplemented. This honest guard is what you hit. |
| 2026-10-29 | DEC-023 (`decisions.md:113`), written by Fury | "C-full (vector …) NOT built … Building it is an architecture change and stays on hold per the user." The only user statement on record is the TASK-034 benchmark constraint "no architecture changes" (`tasks.md:281`). **No user decision specifically deferring dense retrieval exists.** |
| 2026-10-31 to 11-01 | TASK-044/045 reports | Dense retrieval's absence was disclosed only as caveats: "No vector retrieval exists, and none is claimed"; "lexical only"; "2 paraphrase expected-fail". |

Classification: **an untracked architectural gap that looked like an intentional deferral.**
- The code guard and the report caveats were deliberate and honest.
- The deferral itself was never decided:
  - G3 lapsed silently.
  - The S6 status in `plan.md` was never updated.
  - DEC-023 attributed the hold to the user by stretching a benchmark-scope constraint.

Accountable: Nick Fury (plan owner, author of DEC-023, owner of combined reporting). Gate owners Tony + Bruce (G3) and workstream owner Shuri (WS4) are named in the plan, but closing or escalating a lapsed gate was the coordinator's job.

## 2. Why it was not escalated
1. The plan's critical path classified dense retrieval as an optional "value add" from day one, so its slip never triggered critical-path escalation.
2. The PRD baseline (`.peter-parker/prd.md:5`) says "retrieve buried information" but never makes retrieval across different wording or languages an acceptance criterion. No test owned it.
3. The gap appeared only as report caveats ("expected-fail paraphrase"). Repeated tolerated failures normalized the deviation instead of escalating it.
4. From 2026-10-31 onward, attention went to the correctness defects the Codex reviews found (TASK-045 to 047). No one re-checked capability status against the runtime.

## 3. Other misleading capability claims
Full table: `docs/capability-status.md`. The most consequential:
1. **Extraction has no runtime trigger.** No application code calls `ExtractionService.enqueue`. The knowledge store is therefore empty in every real run, so relations, validity and knowledge retrieval never operate in the product. TASK-044 and TASK-045 measured them only through test harnesses that start extraction themselves. Excluding Telegram (PLATFORM) messages is an intentional privacy gate (N-8). The missing trigger even for synthetic messages is not intentional.
2. **The live runtime is in-memory only.** PostgreSQL, Vespa, Neo4j and RLS run only in environment-gated tests, which are skipped by default.
3. **The history-access option is a stub.** `SINCE_REQUESTER_JOINED` returns nothing for everyone.
4. **RFC-001** (lines 40, 52) describes Vespa as "BM25 + vector + graph-feature ranking". `integration-plan.md:18` calls the in-memory projections "wired and tested", though they are written and never read.
5. The semantic evaluation and TASK-047 harden the knowledge path, which the product does not run yet.

## 4. Updated critical path to a trustworthy demo
Each item below needs a user decision where marked. No implementation was started in this session.
1. **[USER DECISION]** Demo scope: synthetic replay (extraction is allowed) or real Telegram chat (extraction blocked by N-8 consent rules).
2. Runtime extraction trigger for the chosen source, gated by the existing approval flow. Acceptance: ingest through the adapter, then knowledge appears, then `/ask` answers from it.
3. Dense retrieval (Codex TASK-048/049): integrated into the demo's runtime path and backend. Acceptance: paraphrase and Hebrew↔English questions answered with valid citations through the runtime entry point. Fury will not duplicate this work.
4. **[USER DECISION]** Demo backend. Vespa, Neo4j and durability exist only on POSTGRES, which no launch path uses. Either add a POSTGRES launch path with gated tests run once, or demo IN_MEMORY and claim only that.
5. Semantic evaluation: resume as decided, but labelled as measuring the knowledge path that items 2–3 will connect.
6. Demo claims limited to rows at VERIFIED E2E or above in the register.

## 5. Safeguards (DEC-040)
- Five-level maturity ladder; only file:line runtime evidence plus an end-to-end test raises a level.
- `docs/capability-status.md` is the single register, cited at the top of every quality or readiness report, which must list the critical capabilities below INTEGRATED.
- Deferring a critical capability requires an explicit user DEC with an owner, a revisit date and its impact. General constraints do not count.
- Lapsed gates and plan dates are escalated in the same session.
- Each critical capability gets a named end-to-end acceptance command. Skips are not passes.

## 6. TASK-048 / TASK-049
They are not in `.avengers/tasks.md` or the Notion task board, so Fury cannot see their scope or branches. Nothing of theirs was read or modified. Request: register them (DEC-027) so the register can track their acceptance evidence.
