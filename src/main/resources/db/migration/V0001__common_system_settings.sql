-- Runtime-tunable values (thresholds, timeouts, feature switches) read through SystemSettingsService.
-- One key per setting, dotted prefix per area: payment.refund_mode, settlement.debt_due_days, ...
CREATE TABLE system_settings (
    key             TEXT        PRIMARY KEY,
    value           TEXT        NOT NULL,
    updated_by_type TEXT        NOT NULL DEFAULT 'SYSTEM',
    updated_by_id   UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT system_settings_key_format CHECK (key ~ '^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$')
);
