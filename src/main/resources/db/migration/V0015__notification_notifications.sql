-- In-app notifications and push devices (reference/architecture/notifications.md, push-notifications.md).
-- A notification row is written in the same transaction as the order change that raises it, before any push is tried:
-- nothing is lost while the recipient is offline.
CREATE TABLE notifications (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id    UUID        NOT NULL REFERENCES users (id),
    audience        TEXT        NOT NULL CHECK (audience IN ('CUSTOMER', 'SHOP')),
    type            TEXT        NOT NULL,
    order_id        UUID,
    order_number    BIGINT,
    title           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ
);
CREATE INDEX notifications_recipient_idx ON notifications (recipient_id, audience, created_at DESC);
CREATE INDEX notifications_pending_idx ON notifications (recipient_id, audience) WHERE acknowledged_at IS NULL;
CREATE INDEX notifications_order_idx ON notifications (order_id);

-- One row per installed app. UNIQUE (kind, token): registering the same token again moves it to the current user,
-- so a shared phone never keeps pushing the previous account's orders.
CREATE TABLE push_devices (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id),
    kind         TEXT        NOT NULL CHECK (kind IN ('EXPO')),
    token        TEXT        NOT NULL,
    platform     TEXT        NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    app_version  TEXT,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INVALID', 'REVOKED')),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, token)
);
CREATE INDEX push_devices_user_idx ON push_devices (user_id) WHERE status = 'ACTIVE';
