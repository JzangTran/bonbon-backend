-- Sign-in methods from external identity providers (flows/authentication/login-oauth.md "Account linking").
-- A user has at most one identity per provider; a provider identity belongs to one user.
CREATE TABLE user_auth_providers (
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider    TEXT        NOT NULL CHECK (provider IN ('GOOGLE', 'FACEBOOK')),
    provider_id TEXT        NOT NULL CHECK (char_length(provider_id) <= 255),
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, provider),
    UNIQUE (provider, provider_id)
);
