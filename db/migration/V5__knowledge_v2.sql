-- V5 (RFC-002 / DEC-022, TASK-043, Shuri): evidence-grounded knowledge model v2.
--
-- ADDITIVE ONLY. Adds nullable columns, one defaulted column, new tables, indexes, RLS policies and grants.
-- It does not drop, rename or rewrite anything from V1-V4, and every existing row stays valid (legacy knowledge
-- items have NULL assertion columns; legacy sources get role 'ASSERTS' and keep NULL spans).
-- Deliberately NOT done here (would not be additive): dropping knowledge_item.status/confidence/superseded_by,
-- making knowledge_source spans NOT NULL, message origin fields, knowledge_entity. Those need a later migration.
--
-- REQUIRES USER APPROVAL BEFORE APPLYING. Not applied by any script. Fury reviews first (ownership.md).
-- Apply as the SCHEMA OWNER (not dita_app), in ONE transaction, after V1-V4:
--   docker compose -p dita -f docker/compose.yml exec -T postgres \
--     psql -X -v ON_ERROR_STOP=1 -U "${DITA_DB_OWNER:-dita}" -d "${DITA_DB_NAME:-dita}" -1 -f /migrations/V5__knowledge_v2.sql
-- Applied check (read-only):  SELECT to_regclass('public.knowledge_relation') IS NOT NULL;
-- The role dita_app must already exist (docker/postgres/init). dita_relay gets NO access to any V5 table.
--
-- Conventions (V1/V3): ids are opaque text; group-owned tables key on (group_id, ...); every cross-table reference is a
-- composite (group_id, ...) FK so the database rejects cross-group references; ENABLE + FORCE ROW LEVEL SECURITY with
-- the app.group_id policy; no message text in any V5 column (spans are offsets, never copied text).

-- ------------------------------------------------------------------ knowledge_item: Assertion (KnowledgeItem.assertion)
-- `assertion_status` is a NEW column: the V1 `status` column has an incompatible CHECK and stays unused (NULL).
-- subject_surface = Assertion.subject (the verbatim surface form); the V1 `subject` column remains KnowledgeItem.subject.
-- qualifiers (V1 jsonb, so far always NULL) now holds Assertion.qualifiers as spans:
--   [{"kind":"POPULATION|CONDITION|TIME|CONTEXT","messageId":"...","spanStart":n,"spanEnd":n}, ...]
-- entities: [{"id":"...","surface":"..."}, ...]. speaker_id has no FK: core does not maintain member rows yet.
-- batch_id has no FK: rule-derived items carry no batch, and the item may outlive its batch bookkeeping.
ALTER TABLE knowledge_item
  ADD COLUMN assertion_status text CHECK (assertion_status IN ('ATTRIBUTED_STATEMENT','REPORTED_CLAIM','OFFER',
             'CONFIRMED_COMMITMENT','DECISION','QUESTION','CORRECTION','CONFIRMATION')),
  ADD COLUMN certainty        text CHECK (certainty IN ('ASSERTED','HEDGED','HEARSAY')),
  ADD COLUMN speaker_id       text,
  ADD COLUMN attributed_to    text,
  ADD COLUMN subject_id       text,
  ADD COLUMN subject_surface  text,
  ADD COLUMN predicate        text CHECK (predicate IN ('recommends','advises_against','states','asks','offers',
             'commits','decides','corrects','confirms')),
  ADD COLUMN entities         jsonb,
  ADD COLUMN stated_at        timestamptz,
  ADD COLUMN validity         text CHECK (validity IN ('ACTIVE','SUPERSEDED')),
  ADD COLUMN proposal_version text,
  ADD COLUMN batch_id         text;

ALTER TABLE knowledge_item
  -- An assertion is all-or-nothing: either every required field is set, or none (legacy item).
  ADD CONSTRAINT knowledge_item_assertion_complete CHECK (
    (assertion_status IS NULL AND certainty IS NULL AND speaker_id IS NULL AND subject_id IS NULL AND predicate IS NULL
       AND stated_at IS NULL AND validity IS NULL AND attributed_to IS NULL AND subject_surface IS NULL
       AND entities IS NULL AND proposal_version IS NULL AND batch_id IS NULL)
    OR (assertion_status IS NOT NULL AND certainty IS NOT NULL AND speaker_id IS NOT NULL AND subject_id IS NOT NULL
       AND predicate IS NOT NULL AND stated_at IS NOT NULL AND validity IS NOT NULL AND entities IS NOT NULL)),
  -- Mirrors Assertion: reported claims are hearsay and only they are; hearsay names its relayed source.
  ADD CONSTRAINT knowledge_item_hearsay CHECK ((assertion_status = 'REPORTED_CLAIM') = (certainty = 'HEARSAY')),
  ADD CONSTRAINT knowledge_item_hearsay_source CHECK (certainty IS DISTINCT FROM 'HEARSAY'
                                                         OR (attributed_to IS NOT NULL AND btrim(attributed_to) <> '')),
  -- Mirrors KnowledgeItem: assertions are proposed by RULE or LLM.
  ADD CONSTRAINT knowledge_item_assertion_method CHECK (assertion_status IS NULL OR method IN ('RULE','LLM')),
  ADD CONSTRAINT knowledge_item_qualifiers_array CHECK (qualifiers IS NULL OR jsonb_typeof(qualifiers) = 'array'),
  ADD CONSTRAINT knowledge_item_entities_array CHECK (entities IS NULL OR jsonb_typeof(entities) = 'array');
CREATE INDEX knowledge_item_subject ON knowledge_item (group_id, subject_id) WHERE subject_id IS NOT NULL;
CREATE INDEX knowledge_item_batch   ON knowledge_item (group_id, batch_id)   WHERE batch_id IS NOT NULL;

-- ------------------------------------------------------------------ knowledge_source: Evidence.role and spans
-- span_start/span_end exist since V1 (unused so far). -1/-1 in core (= no span) is stored as NULL/NULL.
ALTER TABLE knowledge_source
  ADD COLUMN role text NOT NULL DEFAULT 'ASSERTS' CHECK (role IN ('ASSERTS','QUALIFIES','CORRECTION_TARGET'));
ALTER TABLE knowledge_source
  ADD CONSTRAINT knowledge_source_span CHECK ((span_start IS NULL) = (span_end IS NULL)
                                              AND (span_start IS NULL OR (span_start >= 0 AND span_end > span_start)));

-- ------------------------------------------------------------------ knowledge_relation (RFC-002 §3.5)
-- Idempotent by (group, from, kind, to) = KnowledgeRelation.key(). Removing either item removes the relation
-- (ON DELETE CASCADE). evidence_message_id: the message that established it (nullable; same-group FK).
CREATE TABLE knowledge_relation (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- insertion order for relations()
  group_id            text NOT NULL REFERENCES chat_group(id),
  from_id             text NOT NULL,
  to_id               text NOT NULL,
  kind                text NOT NULL CHECK (kind IN ('SUPPORTS','QUALIFIES','CONTRADICTS','CORRECTS','SUPERSEDES','ANSWERS','CONFIRMS')),
  evidence_message_id text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id, from_id, kind, to_id),
  FOREIGN KEY (group_id, from_id) REFERENCES knowledge_item (group_id, id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, to_id)   REFERENCES knowledge_item (group_id, id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, evidence_message_id) REFERENCES message (group_id, id),
  CHECK (from_id <> to_id)
);
CREATE INDEX knowledge_relation_to  ON knowledge_relation (group_id, to_id);
CREATE INDEX knowledge_relation_msg ON knowledge_relation (group_id, evidence_message_id) WHERE evidence_message_id IS NOT NULL;

-- ------------------------------------------------------------------ answer provenance (AnswerRecord)
-- Which knowledge items and source messages a published answer was built from. No answer text is stored.
-- An item removed later (e.g. EARLY_PURGE) disappears from the answer's provenance (ON DELETE CASCADE).
CREATE TABLE dita_answer (
  group_id     text NOT NULL REFERENCES chat_group(id),
  reply_id     text NOT NULL,                     -- Dita's reply id as supplied by core
  published_at timestamptz NOT NULL,
  recorded_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, reply_id),
  CHECK (reply_id <> '')
);
CREATE TABLE dita_answer_knowledge (
  group_id     text NOT NULL,
  reply_id     text NOT NULL,
  ordinal      int  NOT NULL,                     -- list order of AnswerRecord.knowledgeIds
  knowledge_id text NOT NULL,
  PRIMARY KEY (group_id, reply_id, ordinal),
  FOREIGN KEY (group_id, reply_id)     REFERENCES dita_answer (group_id, reply_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, knowledge_id) REFERENCES knowledge_item (group_id, id) ON DELETE CASCADE
);
CREATE INDEX dita_answer_knowledge_item ON dita_answer_knowledge (group_id, knowledge_id);
CREATE TABLE dita_answer_message (
  group_id   text NOT NULL,
  reply_id   text NOT NULL,
  ordinal    int  NOT NULL,                       -- list order of AnswerRecord.messageIds
  message_id text NOT NULL,
  PRIMARY KEY (group_id, reply_id, ordinal),
  FOREIGN KEY (group_id, reply_id)   REFERENCES dita_answer (group_id, reply_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, message_id) REFERENCES message (group_id, id)
);
CREATE INDEX dita_answer_message_msg ON dita_answer_message (group_id, message_id);

-- ------------------------------------------------------------------ extraction queue (ExtractionQueue)
-- Ids only, never message text. last_error must hold a class name / code only (N-17), like outbox_dead_letter.
-- extraction_pending: messages waiting to be collected; seq gives "oldest first".
CREATE TABLE extraction_pending (
  group_id             text NOT NULL REFERENCES chat_group(id),
  message_id           text NOT NULL,
  replies_to_answer_id text,                      -- ExtractionBatch.Target.repliesToAnswerId (no FK, as in core)
  seq                  bigint GENERATED ALWAYS AS IDENTITY,
  enqueued_at          timestamptz NOT NULL,
  PRIMARY KEY (group_id, message_id),
  UNIQUE (seq),
  FOREIGN KEY (group_id, message_id) REFERENCES message (group_id, id)
);
CREATE INDEX extraction_pending_order ON extraction_pending (group_id, seq);

-- A batch leaves COLLECTED only with an approval (NATASHA-EGRESS-001 E-1), mirrored from ExtractionBatch.
CREATE TABLE extraction_batch (
  group_id     text NOT NULL REFERENCES chat_group(id),
  id           text NOT NULL,                     -- InMemoryExtractionQueue.batchId(group, targets)
  seq          bigint GENERATED ALWAYS AS IDENTITY,
  status       text NOT NULL CHECK (status IN ('COLLECTED','APPROVED','RUNNING','DONE','FAILED')),
  attempts     int  NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  approval_ref text,
  last_error   text,
  created_at   timestamptz NOT NULL,
  updated_at   timestamptz NOT NULL,
  PRIMARY KEY (group_id, id),
  UNIQUE (seq),
  CHECK (id <> ''),
  CHECK (status = 'COLLECTED' OR approval_ref IS NOT NULL)
);
CREATE INDEX extraction_batch_status ON extraction_batch (group_id, status, seq);

-- UNIQUE (group_id, message_id): the database itself guarantees a message is in at most one batch.
CREATE TABLE extraction_batch_target (
  group_id             text NOT NULL,
  batch_id             text NOT NULL,
  ordinal              int  NOT NULL,
  message_id           text NOT NULL,
  replies_to_answer_id text,
  PRIMARY KEY (group_id, batch_id, ordinal),
  UNIQUE (group_id, message_id),
  FOREIGN KEY (group_id, batch_id)   REFERENCES extraction_batch (group_id, id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, message_id) REFERENCES message (group_id, id)
);

-- ------------------------------------------------------------------ row-level security (same pattern as V1)
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['knowledge_relation','dita_answer','dita_answer_knowledge','dita_answer_message',
                           'extraction_pending','extraction_batch','extraction_batch_target']
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY group_isolation ON %I
                     USING (group_id = current_setting('app.group_id', true))
                     WITH CHECK (group_id = current_setting('app.group_id', true))$p$, t);
  END LOOP;
END $$;

-- ------------------------------------------------------------------ privileges (least privilege, as V3 did for outbox)
-- docker/postgres/init grants dita_app SELECT/INSERT/UPDATE/DELETE on every table the owner creates (default
-- privileges). V5 narrows that to what PostgresMessageStore / PostgresExtractionQueue actually use. Cascaded deletes
-- run as the table owner, so dita_app needs no DELETE on answer child tables.
REVOKE ALL ON knowledge_relation, dita_answer, dita_answer_knowledge, dita_answer_message,
              extraction_pending, extraction_batch, extraction_batch_target FROM dita_app;
GRANT SELECT, INSERT, DELETE         ON knowledge_relation       TO dita_app;
GRANT SELECT, INSERT                 ON dita_answer              TO dita_app;
GRANT SELECT, INSERT                 ON dita_answer_knowledge    TO dita_app;
GRANT SELECT, INSERT                 ON dita_answer_message      TO dita_app;
GRANT SELECT, INSERT, DELETE         ON extraction_pending       TO dita_app;
GRANT SELECT, INSERT, UPDATE         ON extraction_batch         TO dita_app;
GRANT SELECT, INSERT                 ON extraction_batch_target  TO dita_app;
-- dita_relay: nothing (it reads outbox ids only).
