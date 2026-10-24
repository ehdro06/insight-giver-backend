CREATE EXTENSION IF NOT EXISTS vector;

-- Anonymous, device-bound accounts. The app receives a random bearer token once at
-- registration; only its SHA-256 hash is stored.
CREATE TABLE accounts (
    id              uuid        PRIMARY KEY,
    token_hash      bytea       NOT NULL UNIQUE,
    time_zone       text        NOT NULL DEFAULT 'UTC',
    created_at      timestamptz NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    last_insight_at timestamptz
);

CREATE TABLE devices (
    id         uuid        PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    platform   text        NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    push_token text        NOT NULL UNIQUE,
    updated_at timestamptz NOT NULL
);

CREATE INDEX devices_account_idx ON devices (account_id);

-- Diary entries. Rows are written as PENDING by the API before the id is queued in Redis,
-- so Postgres stays the source of truth even if Redis loses its queue.
CREATE TABLE entries (
    id              uuid        PRIMARY KEY,
    account_id      uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    client_id       text        NOT NULL,
    content         text        NOT NULL,
    written_at      timestamptz NOT NULL,
    received_at     timestamptz NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'EMBEDDED', 'FAILED')),
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      text,
    -- When the sweeper should (re)queue a PENDING entry that no worker has finished.
    retry_at        timestamptz NOT NULL,
    -- Must match EmbeddingClient.DIMENSIONS. Changing it needs a new migration and a re-embed.
    embedding       vector(1024),
    -- Vectors from different models live in different spaces and are never compared.
    embedding_model text,
    embedded_at     timestamptz,
    UNIQUE (account_id, client_id)
);

-- Recall is always scoped to one account, whose entries are few enough for an exact scan.
-- An ANN index (HNSW) would filter by account only after the approximate search and
-- silently drop results, so none is created.
CREATE INDEX entries_account_written_idx ON entries (account_id, written_at DESC);
CREATE INDEX entries_pending_idx ON entries (retry_at) WHERE status = 'PENDING';

CREATE TABLE insights (
    id              uuid        PRIMARY KEY,
    account_id      uuid        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    body            text        NOT NULL,
    -- Set when the widget should show a truncated teaser instead of the full body.
    preview         text,
    seed_entry_id   uuid        REFERENCES entries (id) ON DELETE SET NULL,
    memory_entry_id uuid        REFERENCES entries (id) ON DELETE SET NULL,
    similarity      real        NOT NULL,
    writer          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    pushed_at       timestamptz
);

CREATE INDEX insights_account_created_idx ON insights (account_id, created_at DESC);
