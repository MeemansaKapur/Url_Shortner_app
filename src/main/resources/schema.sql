CREATE TABLE IF NOT EXISTS links (
  code        VARCHAR(16) PRIMARY KEY,
  url         TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ,
  owner       VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS links_expires_at_idx ON links (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS api_keys (
  key_id      VARCHAR(40) PRIMARY KEY,
  secret_hash BYTEA       NOT NULL,
  name        VARCHAR(100) NOT NULL UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  active      BOOLEAN     NOT NULL DEFAULT TRUE
);
