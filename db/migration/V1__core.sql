-- Dita V1 core schema, rev. 3 (2026-10-28, TASK-016). PostgreSQL 16. Source of truth.
-- Rev. 2 incorporated Natasha N-1/N-2/N-3/N-7 and Tony's review: composite (group_id, id) keys everywhere,
-- so the database itself rejects cross-group references; RLS; link review; plan revisions; outbox retries.
-- Rev. 3 aligns the schema with the dita-core model persisted by PostgresMessageStore:
--   * ID MAPPING: every core id (GroupScope.groupId, Message.id, KnowledgeItem.id, Dita reply id) is an opaque
--     `text` value, stored exactly as core supplies it. Core ids are unique only within a group (the reference
--     store partitions by group), so group-owned tables use PRIMARY KEY (group_id, id). The platform adapter
--     derives Message.id from the platform message id; the optional platform_message_id column keeps the raw value.
--   * message_link: link_key (= MessageLink.key()) unique per group, double-precision score, evidence message
--     ids in the child table message_link_evidence (composite FK, so evidence cannot cross groups).
--   * knowledge_item: statement_redacted; has_deleted_source/contains_source_text dropped (derived from sources).
--   * knowledge_source: surrogate PK, ordinal, basis, via_link_key, source_deleted (duplicate records allowed).
--   * dita_reply + dita_reply_citation.
-- NOT YET EXECUTED against a server (BLK-1). Never applied anywhere, so rewritten in place.

CREATE TYPE source_kind  AS ENUM ('SYNTHETIC', 'PLATFORM');
CREATE TYPE group_status AS ENUM ('ACTIVE', 'REMOVED_RETENTION', 'PURGED');
CREATE TYPE member_role  AS ENUM ('MEMBER', 'ADMIN');
CREATE TYPE msg_event    AS ENUM ('CREATED', 'EDITED', 'DELETED');
CREATE TYPE link_kind    AS ENUM ('EXPLICIT_REPLY', 'INFERRED_REFERENCE', 'INFERRED_CONTINUATION', 'INFERRED_CORRECTION');
CREATE TYPE link_status  AS ENUM ('FACT', 'CANDIDATE', 'CONFIRMED', 'REJECTED');
CREATE TYPE link_method  AS ENUM ('PLATFORM', 'RULE', 'LLM', 'HUMAN');
CREATE TYPE evidence_basis AS ENUM ('VERIFIED', 'PROVISIONAL');
-- DEC-012 deletion policy (product owner, OPEN-5): <= 5 min after creation → EARLY_PURGE, else LATE_TEXT_ONLY.
CREATE TYPE deletion_mode   AS ENUM ('EARLY_PURGE', 'LATE_TEXT_ONLY');
CREATE TYPE deletion_source AS ENUM ('PLATFORM_EVENT', 'FORGET_COMMAND');

CREATE TABLE chat_group (
  id               text PRIMARY KEY,              -- = GroupScope.groupId (opaque, assigned by GroupDirectory)
  platform         text NOT NULL,                 -- 'telegram', 'synthetic', later 'whatsapp'
  platform_chat_id text NOT NULL,
  chat_type        text NOT NULL CHECK (chat_type IN ('group', 'supergroup', 'synthetic')),  -- N-4: never 'private'/'channel'
  source_kind      source_kind NOT NULL,
  title            text,
  status           group_status NOT NULL DEFAULT 'ACTIVE',
  removed_at       timestamptz,
  purge_after      timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (platform, platform_chat_id),
  CHECK (id <> ''),
  -- Retention after removal is configurable in [1d, 21d] (DEC-016).
  CHECK (status <> 'REMOVED_RETENTION' OR (purge_after >= removed_at + interval '1 day' AND purge_after <= removed_at + interval '21 days'))
);

-- Telegram issues a new chat id when a group migrates to a supergroup (N-4).
CREATE TABLE chat_alias (
  platform         text NOT NULL,
  platform_chat_id text NOT NULL,
  group_id         text NOT NULL REFERENCES chat_group(id),
  PRIMARY KEY (platform, platform_chat_id)
);

CREATE TABLE member (
  group_id     text NOT NULL REFERENCES chat_group(id),
  member_id    text NOT NULL,                     -- platform user id (opaque)
  display_name text,
  role         member_role NOT NULL DEFAULT 'MEMBER',
  is_bot       boolean NOT NULL DEFAULT false,    -- N-6: bots never count as admins for unanimity
  joined_at    timestamptz NOT NULL,
  left_at      timestamptz,
  PRIMARY KEY (group_id, member_id)
);

CREATE TABLE message (
  group_id                     text NOT NULL REFERENCES chat_group(id),
  id                           text NOT NULL,     -- = core Message.id
  platform_message_id          text,              -- raw platform id when it differs from id (optional)
  author_id                    text NOT NULL,
  sent_at                      timestamptz NOT NULL,
  reply_to_platform_message_id text,              -- Tony: replies to pre-join messages are kept, resolved later
  reply_to_message_id          text,              -- = core Message.replyToId (must exist in the same group)
  source_kind                  source_kind NOT NULL,
  current_text                 text,              -- NULL once deleted (both modes, DEC-012)
  lang                         text,
  edited_at                    timestamptz,
  deleted_at                   timestamptz,        -- when the deletion became known to Dita (never back-dated)
  deletion_mode                deletion_mode,
  deletion_source              deletion_source,
  deletion_recorded_at         timestamptz,        -- server time the deletion was applied (audit; N-13)
  PRIMARY KEY (group_id, id),                     -- idempotent redelivery; ids are unique per group only
  UNIQUE (group_id, platform_message_id),
  FOREIGN KEY (group_id, reply_to_message_id) REFERENCES message (group_id, id),
  CHECK (id <> ''),
  CHECK (deleted_at IS NULL OR current_text IS NULL),
  CHECK (deleted_at IS NOT NULL OR current_text IS NOT NULL),   -- core: a live message always has text
  CHECK ((deleted_at IS NULL) = (deletion_mode IS NULL) AND (deleted_at IS NULL) = (deletion_source IS NULL)),
  -- N-13: mode must match the window exactly; deleted_at is clamped to >= sent_at by DeletionPolicy (N-14).
  CHECK (deleted_at IS NULL OR deleted_at >= sent_at),
  -- The early window is configurable in [5m, 24h] (DEC-016), so the DB enforces the bounds, not one value:
  -- an EARLY purge happened within 24h; a LATE deletion happened after at least the 5-minute floor.
  CHECK (deletion_mode IS NULL OR deletion_mode <> 'EARLY_PURGE' OR deleted_at <= sent_at + interval '24 hours'),
  CHECK (deletion_mode IS NULL OR deletion_mode <> 'LATE_TEXT_ONLY' OR deleted_at > sent_at + interval '5 minutes')
);
CREATE INDEX message_group_time ON message (group_id, sent_at);

-- Append-only history. On deletion (either mode) text is nulled in every event row; the event rows remain.
CREATE TABLE message_event (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_id    text NOT NULL,
  message_id  text NOT NULL,
  kind        msg_event NOT NULL,
  text        text,
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (group_id, message_id) REFERENCES message (group_id, id),
  CHECK (kind <> 'DELETED' OR text IS NULL)
);
CREATE INDEX message_event_msg ON message_event (group_id, message_id, id);
-- N-13: no text may remain in history for a deleted message.
CREATE FUNCTION message_event_no_text_after_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.text IS NOT NULL AND EXISTS (SELECT 1 FROM message m WHERE m.group_id = NEW.group_id
                                      AND m.id = NEW.message_id AND m.deleted_at IS NOT NULL) THEN
    RAISE EXCEPTION 'message % is deleted; event text must be NULL', NEW.message_id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER message_event_no_text_after_delete BEFORE INSERT OR UPDATE ON message_event
  FOR EACH ROW EXECUTE FUNCTION message_event_no_text_after_delete();

CREATE TABLE message_link (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- also gives links() its insertion order
  group_id      text NOT NULL,
  link_key      text NOT NULL,                    -- = MessageLink.key() = from || '->' || to || ':' || kind
  from_msg      text NOT NULL,
  to_msg        text NOT NULL,
  kind          link_kind NOT NULL,
  status        link_status NOT NULL,
  method        link_method NOT NULL,             -- how the link was FOUND; never overwritten by review
  score         double precision NOT NULL,
  evidence      jsonb NOT NULL,                   -- multidimensional evidence vector (signal -> weight)
  model_version text,                             -- rule-set or model id that produced it
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id, id),
  UNIQUE (group_id, link_key),                    -- addLink is idempotent by key (dedupes outbox redelivery)
  FOREIGN KEY (group_id, from_msg) REFERENCES message (group_id, id),
  FOREIGN KEY (group_id, to_msg)   REFERENCES message (group_id, id),
  CHECK (link_key = from_msg || '->' || to_msg || ':' || kind::text),
  -- FACT only from the platform; CONFIRMED/REJECTED only through link_review (status is a cached view).
  CHECK (status <> 'FACT' OR method = 'PLATFORM'),
  CHECK (status NOT IN ('FACT', 'CONFIRMED') OR method IN ('PLATFORM', 'HUMAN')),   -- mirrors MessageLink
  CHECK ((kind = 'EXPLICIT_REPLY') = (method = 'PLATFORM'))
);
CREATE INDEX message_link_from ON message_link (group_id, from_msg);
CREATE INDEX message_link_to   ON message_link (group_id, to_msg);

-- MessageLink.evidenceMessageIds (always includes from_msg and to_msg). Deletion invalidates dependent links.
CREATE TABLE message_link_evidence (
  group_id   text NOT NULL,
  link_id    bigint NOT NULL,
  message_id text NOT NULL,
  PRIMARY KEY (group_id, link_id, message_id),
  FOREIGN KEY (group_id, link_id)    REFERENCES message_link (group_id, id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, message_id) REFERENCES message (group_id, id)
);
CREATE INDEX message_link_evidence_msg ON message_link_evidence (group_id, message_id);

-- EARLY_PURGE deletes every message_link row touching the message; LATE_TEXT_ONLY keeps them.
-- Human review keeps the original provenance intact (Tony).
CREATE TABLE link_review (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_id    text NOT NULL,
  link_id     bigint NOT NULL,
  reviewer_id text NOT NULL,
  decision    text NOT NULL CHECK (decision IN ('CONFIRM', 'REJECT')),
  decided_at  timestamptz NOT NULL,
  FOREIGN KEY (group_id, link_id) REFERENCES message_link (group_id, id) ON DELETE CASCADE
);

CREATE TABLE knowledge_item (
  group_id   text NOT NULL REFERENCES chat_group(id),
  id         text NOT NULL,                       -- = KnowledgeItem.id
  seq        bigint GENERATED ALWAYS AS IDENTITY, -- insertion order (knowledge() order; replace keeps position)
  kind       text NOT NULL CHECK (kind IN ('RECOMMENDATION','CLAIM','DECISION','COMMITMENT','QUESTION','CORRECTION')),
  subject    text,
  statement  text NOT NULL,                       -- derived; EARLY_PURGE deletes the item, LATE keeps it (DEC-012)
  statement_redacted boolean NOT NULL DEFAULT false,  -- wording replaced because it repeated a deleted message (C-3)
  polarity   integer NOT NULL,                    -- +1 recommends, -1 advises against, 0 neutral
  qualifiers jsonb,
  -- status/confidence/superseded_by are not part of the core model yet; PostgresMessageStore leaves them NULL.
  status     text CHECK (status IN ('EXPLICIT','INFERRED','SUPERSEDED','RETRACTED')),
  confidence real,
  method     link_method NOT NULL,
  superseded_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz,
  PRIMARY KEY (group_id, id),
  UNIQUE (seq),
  FOREIGN KEY (group_id, superseded_by) REFERENCES knowledge_item (group_id, id),
  CHECK (id <> ''),
  CHECK (NOT (method = 'LLM' AND status = 'EXPLICIT'))   -- N-7: model output is never "explicit"
);
-- One row per KnowledgeItem.Evidence record, in list order. The same message may appear more than once for an
-- item (several derived records); independent support counts distinct VERIFIED messages (computed in core).
-- has_deleted_source = bool_or(source_deleted). via_link_key has no FK on purpose: the link may later be removed
-- by deletion processing while the provenance record is retained or rewritten by replaceKnowledge.
CREATE TABLE knowledge_source (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_id       text NOT NULL,
  knowledge_id   text NOT NULL,
  ordinal        integer NOT NULL,
  message_id     text NOT NULL,
  basis          evidence_basis NOT NULL,
  via_link_key   text,
  source_deleted boolean NOT NULL DEFAULT false,  -- LATE_TEXT_ONLY deletion of this source
  span_start     int,
  span_end       int,
  UNIQUE (group_id, knowledge_id, ordinal),
  FOREIGN KEY (group_id, knowledge_id) REFERENCES knowledge_item (group_id, id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, message_id)   REFERENCES message (group_id, id),
  CHECK ((basis = 'PROVISIONAL') = (via_link_key IS NOT NULL))   -- mirrors KnowledgeItem.Evidence
);
CREATE INDEX knowledge_source_msg ON knowledge_source (group_id, message_id);

-- Dita-authored replies and the messages they cited (C-11 reporting).
CREATE TABLE dita_reply (
  group_id    text NOT NULL REFERENCES chat_group(id),
  id          text NOT NULL,                      -- reply id as supplied by core
  recorded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, id)
);
CREATE TABLE dita_reply_citation (
  group_id   text NOT NULL,
  reply_id   text NOT NULL,
  message_id text NOT NULL,
  PRIMARY KEY (group_id, reply_id, message_id),
  FOREIGN KEY (group_id, reply_id)   REFERENCES dita_reply (group_id, id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, message_id) REFERENCES message (group_id, id)
);
CREATE INDEX dita_reply_citation_msg ON dita_reply_citation (group_id, message_id);

-- Coordination
CREATE TABLE plan (
  id         uuid NOT NULL,
  group_id   text NOT NULL REFERENCES chat_group(id),
  title      text NOT NULL,
  status     text NOT NULL CHECK (status IN ('DRAFT','AWAITING_APPROVAL','APPROVED','CHANGES_REQUESTED','CANCELLED')),
  revision   int  NOT NULL DEFAULT 0,             -- N-3: approvals bind to a revision
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id),
  UNIQUE (group_id, id)
);
CREATE TABLE plan_item (
  id             uuid PRIMARY KEY,
  group_id       text NOT NULL,
  plan_id        uuid NOT NULL,
  kind           text NOT NULL CHECK (kind IN ('PROPOSAL','TASK','QUESTION','DECISION')),
  text           text NOT NULL,
  assignee       text,
  accepted       boolean NOT NULL DEFAULT false,
  status         text NOT NULL,
  source_message text,
  source_deleted boolean NOT NULL DEFAULT false,  -- N-11: EARLY → text replaced by placeholder, source nulled
  FOREIGN KEY (group_id, plan_id) REFERENCES plan (group_id, id),
  FOREIGN KEY (group_id, source_message) REFERENCES message (group_id, id)
);
-- An approval row exists only for an explicit act by that member (button/command); never inferred.
CREATE TABLE approval (
  group_id    text NOT NULL,
  plan_id     uuid NOT NULL,
  revision    int  NOT NULL,
  member_id   text NOT NULL,                      -- from callback_query.from.id only
  decision    text NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
  act         text NOT NULL CHECK (act IN ('BUTTON','COMMAND')),
  decided_at  timestamptz NOT NULL,
  PRIMARY KEY (plan_id, revision, member_id),
  FOREIGN KEY (group_id, plan_id) REFERENCES plan (group_id, id)
);

-- Configuration with unanimous admin approval
CREATE TABLE group_config (
  group_id   text PRIMARY KEY REFERENCES chat_group(id),
  config     jsonb NOT NULL,
  version    int NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE TABLE config_change_request (
  id              uuid NOT NULL,
  group_id        text NOT NULL REFERENCES chat_group(id),
  proposed_by     text NOT NULL,                  -- must be a current human admin (N-6)
  proposed        jsonb NOT NULL,
  base_version    int NOT NULL,                   -- applied only if group_config.version still equals it
  required_admins text[] NOT NULL,                -- snapshot of current human admins at request time
  status          text NOT NULL CHECK (status IN ('PENDING','APPLIED','REJECTED','STALE','EXPIRED')),
  expires_at      timestamptz NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id),
  UNIQUE (group_id, id)
);
CREATE TABLE config_approval (
  group_id   text NOT NULL,
  request_id uuid NOT NULL,
  admin_id   text NOT NULL,
  decision   text NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
  decided_at timestamptz NOT NULL,
  PRIMARY KEY (request_id, admin_id),
  FOREIGN KEY (group_id, request_id) REFERENCES config_change_request (group_id, id)
);

-- Transactional outbox (DEC-004). Payload carries ids only, never message text (Tony/N-1).
-- Written by PostgresEventPublisher inside the caller's transaction. Topics and payloads:
--   message.created   {groupId, messageId}
--   link.added        {groupId, linkKey, fromId, toId}
--   knowledge.changed {groupId, knowledgeId}
--   message.deleted   DeletionEvent: {groupId, messageId, mode, source, createdAt, deletedAt, removedKnowledgeIds,
--                     updatedKnowledgeIds, redactedKnowledgeIds, preservedWithDeletedSourceIds, removedLinkKeys,
--                     addedLinkKeys, affectedDitaReplyIds}
CREATE TABLE outbox (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_id     text NOT NULL,
  topic        text NOT NULL,
  payload      jsonb NOT NULL,
  attempts     int NOT NULL DEFAULT 0,
  last_error   text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);
CREATE INDEX outbox_pending ON outbox (group_id, id) WHERE processed_at IS NULL;
-- Workers claim with: SELECT ... WHERE processed_at IS NULL ORDER BY id FOR UPDATE SKIP LOCKED,
-- one in-flight event per group_id to preserve per-group ordering (infer() reads existing links).

-- Row-level security (N-2). outbox and chat_group/chat_alias are excluded: the relay worker and GroupDirectory
-- read across groups, then set app.group_id before touching any group table. The app connects as a non-owner,
-- NOSUPERUSER NOBYPASSRLS role (dita_app, see docker/postgres/init) and runs set_config('app.group_id', ?, true)
-- (= SET LOCAL) per transaction. With app.group_id unset, current_setting(..., true) is NULL and no row matches.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['member','message','message_event','message_link','message_link_evidence','link_review',
                           'knowledge_item','knowledge_source','dita_reply','dita_reply_citation',
                           'plan','plan_item','approval','group_config','config_change_request','config_approval']
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY group_isolation ON %I
                     USING (group_id = current_setting('app.group_id', true))
                     WITH CHECK (group_id = current_setting('app.group_id', true))$p$, t);
  END LOOP;
END $$;
