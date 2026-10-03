-- Rotation with reuse detection: every token minted from one login shares a family; presenting a revoked
-- token again revokes the whole family. auth_time is carried over so step-up checks see the real login time.
ALTER TABLE refresh_tokens ADD COLUMN family_id UUID NOT NULL;
ALTER TABLE refresh_tokens ADD COLUMN auth_time TIMESTAMPTZ NOT NULL;
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
