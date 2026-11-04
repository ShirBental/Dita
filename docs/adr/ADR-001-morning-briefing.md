> **Historical snapshot (2026-11-04 cleanup).** Existing findings are preserved; current runtime guidance: [documentation index](../README.md).

# Morning briefing: ADR-001 Kafka review (2026-10-29)

**Bottom line:** keep the Postgres outbox relay and don't add Kafka before Nov 4. The review did find a real bug, but it's in the relay, not in the transport choice, and it should be fixed whichever transport we use.

1. **Kafka was considered, but never measured.** DEC-004 and RFC-001 §4 deferred it because there was no Docker at the time. Docker exists now, so the review was worth doing. The reason to defer has changed: it's now value, risk and memory, not whether Kafka can run.
2. **Kafka adds nothing the demo needs.**
   - Events carry ids only, and projections re-read Postgres, so replay and rebuild already work.
   - Per-consumer cursors already give independent consumers.
   - Chat-scale load is tiny.
3. **Kafka would make things worse:**
   - No isolation on a PLAINTEXT broker (N-47).
   - Default offset handling can silently skip deletions (N-48).
   - A second copy of metadata outside Postgres deletion control (N-49).
   - It pushes the memory limits to about 7.05 of 7.75 GiB.
   - About 3–5 days of work that would push out the Telegram live run, putting Muse into the product, and rehearsal (Peter).
4. **What the review found instead:**
   - **F1 / N-44 (HIGH):** the relay can skip an event that commits out of order. That event can be a deletion. Kafka would inherit this.
   - **F1c:** the in-memory dispatcher has the same race, but only when publishes run concurrently. Shuri disagreed; I ruled from the code.
   - **F1 fix options:** B is an advisory lock in `publish()`: two lines, my hackathon pick. A is a txid cursor plus a V4 migration, the long-term fix. Diffs are in `prototypes/kafka-spike/proposed-relay-fix.md`.
   - **F2:** the running app still uses the in-memory store and dispatcher, so it isn't durable. The Postgres relay only runs in the E2E test.
   - **N-45:** projections apply deletions without checking truth.
   - All of these come from reading the code. **Nothing has been reproduced or benchmarked**, because the sandbox can't reach Docker or localhost.
5. **Natasha's gating:** the synthetic-only demo is not blocked. Real data is blocked by N-44.

**Your decisions:**
- **Q1:** Acknowledge DEC-020 ("keep A, defer Kafka"), or run the benchmark first.
- **Q2:** Approve the F1/F1c fix to the existing relay. It changes working infrastructure, so I held it.
- **Q3:** Wire the Postgres path into dita-app before Nov 4 (recommended).

**What you can run (Shuri's isolated spike in `prototypes/kafka-spike/`):** the out-of-order reproduction, the outbox benchmark, and a separate-project Kafka KRaft benchmark that measures memory and runs failure tests. See its README. It never touches the dita-* volumes.

Full ADR: [ADR-001-event-transport.md](ADR-001-event-transport.md). Notion: TASK-035 update in Dita → Reports & Updates.
