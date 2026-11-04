> **Current flow (TASK-055 note, 2026-11-04):** silent preauthorized start, one Muse synthesis call per `/ask` (prompt muse-answer-v3), no extraction, language validation off, sources hidden; budget at most $1 per process. Sections below that describe a confirm screen, Approve/Cancel buttons per call, extraction batches, or "replies with sources" describe EARLIER versions of this trial and are kept as history.

> Demo behavior update (operator, 2026-11-04): the runner sets synthesis.enforce-language-rules=false and synthesis.show-sources=false. Muse prose is not judged by the linguistic validator. Only source-id integrity and existing scoped/current-message access checks remain. Successful Telegram replies hide inline source markers and the source list; internal provenance remains. Alice/Bob names are seeded from explicit runner configuration on every start, including carried-over messages. The optional strict language validator remains available outside this demo.

> Update 2026-11-04 (operator): the demo runner now uses PREAUTHORIZED_SESSION. It starts silently; there is no synthetic-data setup message or confirmation button. Existing operator authorization is recorded locally in session.json. Session start time, explicit continuation boundaries, sender/group restrictions and the $1 process reservation budget remain active. Older confirmation-screen instructions below apply only to the optional CONFIRM_IN_TELEGRAM configuration.

## Preauthorized demo budget (operator, 2026-11-03)

> **Answer modes (DEC-049; supersedes the DEC-048 note).** The runner's second argument picks the mode:
> - `bash scripts/telegram-synthesis-trial.sh postgres semantic` (default): **the intended demo**. Raw messages are found by
>   LOCAL semantic search (the frozen bge-small `dita-english` embedder inside Vespa, message field `text_embedding`)
>   plus keywords (BM25) plus Telegram reply links. The candidates are fused, capped at 8, and sent as compact raw-message
>   context to ONE Muse synthesis call per question. Dita validates the answer and replies with the original-message
>   citations. No structured extraction. **It needs the semantic Vespa package deployed first (section S).** Without that
>   package, startup FAILS (the readiness probe); it never runs as keyword-only while claiming semantic retrieval.
> - `... postgres keyword`: INTERIM keyword + reply links only, labelled as such on the setup screen.
> - `... postgres knowledge`: the extraction-based mode below (DEC-046/047).
>
> Context handling (G2): "excellent for babies" and "not recommended for teenagers" are different contexts, not a
> disagreement, so a focused answer may cite only its own population. Opposing statements about the same or an unknown
> context remain a conflict that must be shown on both sides. A question about a population no message mentions is
> not answered by extrapolation (rule R10).
>
> Per runner process: at most 6 Muse calls (`max-calls-per-process`), so at most 6 answered questions; restart for more.

## S. Semantic package deployment (PREPARED, NOT EXECUTED; each service-changing step needs operator's approval)

Affected service: the docker compose project `dita`, service `vespa` (hostname `vespa-container`; config 127.0.0.1:59071,
query 127.0.0.1:58080). Additive change: services.xml gains the `dita-english` embedder; knowledge.sd gains the TASK-049
derived embedding; message.sd gains `text_embedding` (384-D, derived from `text` in Vespa) + rank profile `dense`. The
existing 8-D placeholder field is untouched; no documents are deleted.

1. Model files (no download by Dita): put the approved TASK-049 freeze files `model.onnx` and `tokenizer.json`
   (BAAI/bge-small-en-v1.5 @5c38ec7c; SHA-256 values in the script header) in a directory, e.g. `models/bge-small/`.
2. `bash scripts/task054-semantic-deploy.sh build models/bge-small` → verifies both hashes, builds
   `build/task054-semantic/package.zip`, `rollback-base.zip` and `package.diff`. No service change.
3. **Approval gate.** `bash scripts/task054-semantic-deploy.sh deploy --approved-by-operator=<ref>` → prepareandactivate on the demo Vespa, then
   `status` (the same semantic probe as Dita's startup check). Read `configChangeActions` in `deploy.json`.
4. Optional: `reindex` re-embeds messages stored BEFORE the deploy. The demo uses only messages of the confirmed session,
   which are embedded on write, so it is not needed for the rehearsal. The endpoint shape is unverified.
5. Rollback: stop Dita first (or restart it without semantic mode), then
   `bash scripts/task054-semantic-deploy.sh rollback --approved-by-operator=<ref>`. It re-activates the hash-verified base
   zip from step 2 and refuses to improvise one. The derived embeddings are dropped (recoverable by redeploying). If Vespa
   rejects the field removal, a validation override is a separate reviewed step; meanwhile the semantic package can stay
   active (keyword and knowledge modes ignore the new field).
   The `--approved-by-operator` argument is logged to `build/task054-semantic/approvals.log`. It is a speed bump, not a
   security boundary.


operator explicitly authorized up to USD 1 per demo process for extraction and synthesis, replacing per-call approvals. The runner sets session-budget-usd=1; default application configuration remains 0 (per-call approvals). The session setup screen discloses budget mode; each request and automatic authorization is still recorded. Reservations use the larger of the existing estimate and a conservative bytes-based input allowance plus worst-case output, at the historical configured prices. Reservations are not refunded for failures or timeouts. The process refuses a request before its reservation exceeds the budget; this is not a provider-enforced billing limit. Existing call-count limits and no-automatic-retry rules also apply. Restarting starts a new process budget. No live call was made while implementing this change.

# TASK-054: controlled synthetic Telegram demo in SyntheticDemoGroup

Flow: ordinary synthetic messages → a question → Approve / Cancel buttons for each paid call → Dita's answer with
sources. Everything happens in SyntheticDemoGroup. **You type nothing in the terminal after launch**: no `synthetic`, no `start`,
no `approve` phrases or request hashes, and no `/syn` or `/extract`.

**Path (the production path):**
1. Dita posts a **session setup** screen in the group: the group, the authorized sender and the synthetic-data
   declaration, plus a **Confirm synthetic session** button. Nothing is stored until the authorized sender presses it.
2. After that, your plain messages are stored silently as SYNTHETIC (ids `tgt1001000000054m<N>`). Replies keep their reply
   link.
3. `/ask <question>`:
   - **Selection.** Dita picks the messages relevant to the question with the answer path's own message retrieval (BM25
     plus reply/correction graph expansion, fused; at most 8). Messages that already have knowledge are reused, never
     re-extracted. All other messages stay pending.
   - **Step 1 of 2, prepare knowledge.** Only the relevant messages without knowledge go to extraction, at most 4 per
     call. Each call carries its linked context: reply parents and linked replies. Each call shows its own approval.
   - **Step 2 of 2, answer synthesis.** Dita retrieves the compact knowledge evidence and drafts the answer. Muse
     (`muse-answer-v3` by default in this runner; `SYNTHESIS_PROMPT=V2` selects the previous prompt; the application default stays V2) writes the reply. The prompt version is recorded in run.json `synthesis_prompt`. Dita validates it and replies with sources.

**Scope:**
- Chat `-1001000000054` (SyntheticDemoGroup) and sender `1001` only. Both are checked against `application.yml`.
- Only messages sent after confirmation are stored. Other senders, forwards, inline-bot messages and backlog are ignored.
- Only that sender can confirm, `/ask`, or press Approve. Anyone else's press is answered "Only the trial operator can
  use this button."

**What is NOT approval:** confirming the session, sending messages, or `/ask`. Each paid call is approved separately with
its own button. That button is bound internally to that exact request, works once, and expires.

## C. Continue the stored session after a restart (DEC-050)

A code change needs a runner restart, and a new session normally uses only messages sent after its own confirmation.
To keep using the 200 messages of the session confirmed at 2026-11-04T13:15:54.329377Z (run-20261104T131550Z-fd89/session.json) without resending them:

```bash
CONTINUE_SESSION_FROM=2026-11-04T13:15:54.329377Z bash scripts/telegram-synthesis-trial.sh postgres semantic
```

- The setup screen says how many stored messages are used and how many older ones are not. Confirm the session as usual: nothing is answered before confirmation.
- Only messages sent at or after that instant are eligible. If another session ran after it, that session's messages are included too, so check the count on the setup screen.
- Nothing is deleted or reset. The option is refused outside MESSAGES mode with CONFIRM_IN_TELEGRAM, and refused for a future or malformed instant.
- Names of authors not seen since the restart are shown by numeric id (MemberNames is process memory).

## 0. Preconditions (once)

- The Docker stack `dita` is up and healthy at schema V5. The runner checks this.
- No other Dita process is running, and no `dita_app`/`dita_relay` DB sessions are open. The runner checks both.
  **A code change takes effect only after you restart the runner.**
- `.env.local` holds exactly one `TELEGRAM_BOT_TOKEN=` line and one `MODEL_API_KEY=` line. They are never printed.

## 1. Start (before recording)

```bash
bash scripts/telegram-synthesis-trial.sh postgres
```

The runner checks the stack, prints the configuration and starts polling. Leave the terminal alone.

## 2. In SyntheticDemoGroup (recording)

1. Dita posts **"Dita synthetic trial: session setup"**. Check the group, the sender and the declaration that all text
   you send is fictional, then press **Confirm synthetic session**.
2. Send ordinary messages. For example, a mix of doctor and unrelated messages:

   ```
   Dr. Cedar on Maple Street is excellent for babies.
   Does anyone have a spare blue umbrella?
   I would choose Dr. Cedar again for a newborn appointment.
   The neighborhood picnic is planned for Saturday at noon.
   Dr. Cedar is not recommended for teenagers.
   ```

   Then reply (Telegram "Reply") to the newborn message with: `He was patient with our baby.`

   Dita stays silent, and nothing is sent to Muse.
3. Send `/ask Is Dr. Cedar recommended for babies?`
4. **Approval needed: prepare knowledge (extraction)**. The screen shows:
   - the step ("extraction 1 of N, k message(s) relevant to the question");
   - the messages sent (author pseudonymised as P1);
   - the model and host, the limits (4096 output tokens including reasoning, low reasoning, 60 s, no retry);
   - the cost estimate and its pricing basis.

   Unrelated messages (umbrella, picnic) must **not** be listed. Press **Approve** or **Cancel**. With more than 4
   relevant messages there is a second extraction screen.
5. **Approval needed: answer (Muse drafts, Dita validates)** shows the question and the compact evidence. Press
   **Approve** or **Cancel**.
6. The reply cites its source, e.g. `[tgt1001000000054m<N>]`, then `Sources:` with the quote, then
   nothing more: a validated Muse answer has no footer (the outcome is in `ask-NNN.json`). Speakers are shown by
   their Telegram display name (never sent to Muse, which sees P1).
   - If the answer was cancelled or rejected by the validator, the reply is Dita's deterministic answer, marked
     `[trial] Not a Muse answer`.
7. Optional: `/ask Is Dr. Cedar recommended for teenagers?`. The knowledge is already prepared, so there is no
   extraction screen, only the answer screen.

**If something stops:** Dita replies with a short status ending `[trial] No answer: …`. It never answers from raw
messages, and it retries nothing.
- `EXTRACTION_FAILED` (cancelled, timed out, or the call failed): the messages stay stored. **Ask again**: Dita re-selects
  the relevant messages and shows a new approval. Nothing needs resending, and an old button never works again.
- `PROJECTIONS_NOT_READY`: ask again.

## 3. Stop (after recording)

Press Ctrl-C. The runner scans the log and evidence for credentials and prints one line per request.

## Evidence (synthetic content only; not redacted by /forget)

`build/telegram-synthesis-trial/<run-tag>/`:
- `run.json` (limits)
- `session.json` (who confirmed, when)
- `call-NNN-<kind>/`: request.json (exact bytes, written before the approval screen), pending.json, approval.json,
  `attempted` (written before sending), http.json, response.json
- `extract-NNN.json` per prepared batch (mode `new-batch` or `failed-batch-subset`)
- `ask-NNN.json`: `preparation_plan` (retrieval candidates with their state, steps, messages left pending),
  preparation, synthesis outcome, final answer and sources, or `stopped` with the reason

## Limits

- At most 8 approved calls per run. The babies question with 6 relevant messages uses 3: 2 extraction calls and 1
  synthesis call.
- After an approval timeout (10 min) the request is not sent. Asking again shows a new approval.
- Selection is lexical plus reply/correction links. A message that shares no word with the question and has no reply link
  to a selected message is not selected (for example, "He was patient" sent as a plain message rather than as a reply).
  Lexical overlap can also pull in a loosely related message: "starts at four" matched a picnic question.
- At most 8 candidates per question. If more relevant messages exist, the lowest-ranked wait for a later question.
- An edit of a stored message withdraws it. `/forget` (as a reply) removes it. Neither is ever re-extracted.
- On PostgreSQL the trial group `tgt1001000000054` persists across runs, including its knowledge and the earlier FAILED
  19-message batch. Its relevant messages are recovered with new approvals when asked about. Its unrelated messages are
  left alone.
- The offline acceptance tests use the in-memory retriever. The PostgreSQL retriever (Vespa + Neo4j) is the same
  interface but has not been exercised with this flow until the rehearsal.
