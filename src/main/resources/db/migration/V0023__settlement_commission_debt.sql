-- Commission a shop owes (flows/settlement/collect-commission-debt.md): weekly statements, the notices that precede any
-- restriction, and the cached standing the shop module reads. The debt itself is never stored: what is still owed is
-- computed from ledger_entries, so there is no second "paid" bookkeeping that could drift from the ledger.
ALTER TABLE vendors
    ADD COLUMN commission_overdue_since TIMESTAMPTZ,
    ADD COLUMN commission_stage         TEXT NOT NULL DEFAULT 'NONE'
        CHECK (commission_stage IN ('NONE', 'OVERDUE', 'RESTRICTED', 'PAUSED', 'REVIEW'));

CREATE TABLE commission_statements (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id         UUID        NOT NULL REFERENCES vendors (id),
    -- the Vietnam-time day the period starts on; a statement issued at once (credit limit) uses the day it was issued
    period_start      DATE        NOT NULL,
    -- the balance at this instant is what the statement bills; credits after it pay it off
    period_end        TIMESTAMPTZ NOT NULL,
    kind              TEXT        NOT NULL CHECK (kind IN ('WEEKLY', 'LIMIT')),
    amount_due        INTEGER     NOT NULL CHECK (amount_due > 0),
    due_at            TIMESTAMPTZ NOT NULL,
    original_due_at   TIMESTAMPTZ NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'OVERDUE')),
    reminder_sent_at  TIMESTAMPTZ,
    extended_by_id    UUID,
    extended_at       TIMESTAMPTZ,
    extension_reason  TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    -- a job that runs twice cannot bill a week twice
    UNIQUE (vendor_id, period_start)
);
CREATE INDEX commission_statements_vendor_idx ON commission_statements (vendor_id, period_end DESC);
CREATE INDEX commission_statements_open_idx ON commission_statements (status) WHERE status <> 'PAID';

-- One row per notice sent in one stretch of being overdue. A step is never applied before its notice is 5 days old.
CREATE TABLE commission_notices (
    vendor_id     UUID        NOT NULL REFERENCES vendors (id),
    step          TEXT        NOT NULL CHECK (step IN ('RESTRICT', 'PAUSE')),
    overdue_since TIMESTAMPTZ NOT NULL,
    sent_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vendor_id, step, overdue_since)
);
