-- Minimum app version per platform and the log of every change to it or to maintenance mode (docs: reference/architecture/app-operations.md).
-- The maintenance state itself lives in system_settings (maintenance.level, .message, .starts_at, .ends_at).
CREATE TABLE app_version_policies (
    platform              TEXT        PRIMARY KEY CHECK (platform IN ('ANDROID', 'IOS')),
    min_supported_version TEXT        NOT NULL CHECK (min_supported_version ~ '^[0-9]+(\.[0-9]+){0,3}$'),
    latest_version        TEXT        NOT NULL CHECK (latest_version ~ '^[0-9]+(\.[0-9]+){0,3}$'),
    store_url             TEXT,
    message               TEXT,
    updated_by_id         UUID,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Nothing is locked out until an administrator raises the minimum.
INSERT INTO app_version_policies (platform, min_supported_version, latest_version) VALUES ('ANDROID', '0.0.0', '0.0.0'), ('IOS', '0.0.0', '0.0.0');

CREATE TABLE app_config_log (
    id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID        NOT NULL,
    action   TEXT        NOT NULL CHECK (action IN ('VERSION_POLICY', 'MAINTENANCE')),
    subject  TEXT        NOT NULL,
    details  TEXT        NOT NULL,
    reason   TEXT        NOT NULL,
    at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX app_config_log_at_idx ON app_config_log (at DESC);
