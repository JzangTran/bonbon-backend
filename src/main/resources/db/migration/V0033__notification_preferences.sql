-- Who wants which notification where (flows/account/manage-notification-preferences.md). A row exists only when the user
-- changed a default; going back to the default deletes it. Defaults live in code (NotificationCategory), and the categories
-- that cannot be silenced are never written.
CREATE TABLE notification_preferences (
    user_id    UUID        NOT NULL REFERENCES users (id),
    category   TEXT        NOT NULL,
    channel    TEXT        NOT NULL CHECK (channel IN ('PUSH', 'EMAIL')),
    enabled    BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, category, channel)
);

-- Quiet hours hold back push for the categories that can be silenced; an order alert or a security notice ignores them.
-- A window that crosses midnight has start_time later than end_time. The zone is the account's, not the device's, because
-- push devices do not report one yet.
CREATE TABLE notification_quiet_hours (
    user_id    UUID        PRIMARY KEY REFERENCES users (id),
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    time_zone  TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (start_time <> end_time)
);
