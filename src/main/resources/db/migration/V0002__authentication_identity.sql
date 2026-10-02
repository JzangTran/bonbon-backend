-- Identities, their roles, the role→permission mapping and every token table the auth flows use.

CREATE TABLE users (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email              TEXT        NOT NULL CHECK (char_length(email) <= 255),
    password_hash      TEXT,                                   -- NULL for OAuth-only and not-yet-activated admins
    name               TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    phone              TEXT,
    avatar_url         TEXT,
    email_verified     BOOLEAN     NOT NULL DEFAULT false,
    tokens_valid_after TIMESTAMPTZ NOT NULL DEFAULT now(),     -- access tokens issued before this are rejected
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Email is globally unique across roles, compared case-insensitively.
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));

CREATE TABLE user_roles (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL CHECK (role IN ('CUSTOMER', 'SELLER', 'ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

-- An administrator identity never also holds CUSTOMER or SELLER (product-scope: admin stays separate).
CREATE FUNCTION user_roles_admin_exclusive() RETURNS trigger AS $$
BEGIN
    IF NEW.role = 'ADMIN' AND EXISTS (
        SELECT 1 FROM user_roles WHERE user_id = NEW.user_id AND role <> 'ADMIN') THEN
        RAISE EXCEPTION 'admin identity cannot hold other roles' USING ERRCODE = 'check_violation',
            CONSTRAINT = 'user_roles_admin_exclusive';
    END IF;
    IF NEW.role <> 'ADMIN' AND EXISTS (
        SELECT 1 FROM user_roles WHERE user_id = NEW.user_id AND role = 'ADMIN') THEN
        RAISE EXCEPTION 'admin identity cannot hold other roles' USING ERRCODE = 'check_violation',
            CONSTRAINT = 'user_roles_admin_exclusive';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_roles_admin_exclusive
    BEFORE INSERT OR UPDATE ON user_roles
    FOR EACH ROW EXECUTE FUNCTION user_roles_admin_exclusive();

-- Mapping only; the permission names themselves are a fixed enum in code (Permission.java).
CREATE TABLE role_permissions (
    role       TEXT NOT NULL CHECK (role IN ('CUSTOMER', 'SELLER', 'ADMIN')),
    permission TEXT NOT NULL,
    PRIMARY KEY (role, permission)
);

CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL,
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_active_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

CREATE TABLE email_verification_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Also used for an admin's "set your initial password" link (purpose INITIAL_PASSWORD).
CREATE TABLE password_reset_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    TEXT        NOT NULL CHECK (purpose IN ('RESET', 'INITIAL_PASSWORD')),
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO role_permissions (role, permission) VALUES
    ('CUSTOMER', 'order:create'), ('CUSTOMER', 'order:cancel'), ('CUSTOMER', 'review:create'),
    ('CUSTOMER', 'conversation:read'), ('CUSTOMER', 'conversation:write'),

    ('SELLER', 'order:read'), ('SELLER', 'order:write'), ('SELLER', 'review:respond'),
    ('SELLER', 'vendor:create'), ('SELLER', 'vendor:read'), ('SELLER', 'vendor:write'),
    ('SELLER', 'voucher:read'), ('SELLER', 'voucher:write'), ('SELLER', 'stats:read'),
    ('SELLER', 'earnings:read'), ('SELLER', 'conversation:read'), ('SELLER', 'conversation:write'),

    ('ADMIN', 'merchant-approval:read'), ('ADMIN', 'merchant-approval:read-identity'),
    ('ADMIN', 'merchant-approval:decide'), ('ADMIN', 'merchant-approval:suspend'),
    ('ADMIN', 'merchant-approval:write-settings'), ('ADMIN', 'admin-conversation:read'),
    ('ADMIN', 'help-center:write'), ('ADMIN', 'platform-stats:read'), ('ADMIN', 'admin:write'),
    ('ADMIN', 'shop-penalty:write'), ('ADMIN', 'review:moderate'), ('ADMIN', 'order-case:decide'),
    ('ADMIN', 'ticket:read'), ('ADMIN', 'ticket:reply'), ('ADMIN', 'commission:write'),
    ('ADMIN', 'dish:moderate'), ('ADMIN', 'category:write'), ('ADMIN', 'settlement:read'),
    ('ADMIN', 'settlement:write'), ('ADMIN', 'refund:process'), ('ADMIN', 'legal-document:write'),
    ('ADMIN', 'legal-document:read-consents'), ('ADMIN', 'data-request:handle'),
    ('ADMIN', 'report:read'), ('ADMIN', 'report:decide'), ('ADMIN', 'moderation:write'),
    ('ADMIN', 'customer-abuse:read'), ('ADMIN', 'customer-abuse:decide'),
    ('ADMIN', 'admin-order:read'), ('ADMIN', 'admin-customer:read'), ('ADMIN', 'app-config:write');
