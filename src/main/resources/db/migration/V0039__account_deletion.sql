-- Deleting an account (flows/account/delete-account.md): the person asks, nothing is erased for a grace period during which signing in
-- again cancels it, then the identity is anonymized in place. The row stays, so orders, revenue and audit references stay valid.
ALTER TABLE users
    ADD COLUMN status                TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PENDING_DELETION', 'DELETED')),
    ADD COLUMN deletion_requested_at TIMESTAMPTZ,
    ADD COLUMN deletion_scheduled_at TIMESTAMPTZ,
    ADD COLUMN deleted_at            TIMESTAMPTZ;

CREATE INDEX users_deletion_due_idx ON users (deletion_scheduled_at) WHERE status = 'PENDING_DELETION';

INSERT INTO system_settings (key, value) VALUES ('account.deletion_grace_days', '7') ON CONFLICT (key) DO NOTHING;
