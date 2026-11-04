-- V2: durable per-consumer cursors for the Postgres outbox relay (DEC-004, N-20). Ids only; no message text.
-- Not group-scoped (the relay reads all groups' outbox rows), so no RLS, like outbox itself.
CREATE TABLE outbox_consumer (
  name                text PRIMARY KEY,
  last_id             bigint NOT NULL DEFAULT 0,
  attempts            int NOT NULL DEFAULT 0,
  retry_at            timestamptz,
  blocked_on_deletion boolean NOT NULL DEFAULT false,
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox_dead_letter (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  consumer    text NOT NULL REFERENCES outbox_consumer(name),
  outbox_id   bigint NOT NULL REFERENCES outbox(id),
  error_class text NOT NULL,                       -- class name only, never a message that could echo content
  created_at  timestamptz NOT NULL DEFAULT now(),
  redriven_at timestamptz
);
