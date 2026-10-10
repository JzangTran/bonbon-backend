-- Suspending a shop (flows/merchant-approval/suspend-seller.md). Restricting a seller's account needs a notice of at least 5 days
-- (Luật TMĐT 122/2025 Điều 17 khoản 2 điểm g), so a suspension is scheduled; only a competent authority's request is immediate.
-- vendors.status already has SUSPENDED; this keeps who decided what, why and when, one row per suspension.
CREATE TABLE merchant_suspensions (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id           UUID        NOT NULL REFERENCES vendors (id),
    kind                TEXT        NOT NULL CHECK (kind IN ('SCHEDULED', 'IMMEDIATE')),
    status              TEXT        NOT NULL CHECK (status IN ('SCHEDULED', 'APPLIED', 'CANCELLED', 'LIFTED')),
    reason              TEXT        NOT NULL,
    -- the request's reference number and date, for an immediate suspension at an authority's request
    authority_reference TEXT,
    notice_sent_at      TIMESTAMPTZ NOT NULL,
    effective_at        TIMESTAMPTZ NOT NULL,
    created_by_id       UUID        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    applied_at          TIMESTAMPTZ,
    ended_by_id         UUID,
    ended_at            TIMESTAMPTZ,
    end_reason          TEXT
);
-- a shop has one open suspension at a time: one scheduled, or one in force
CREATE UNIQUE INDEX merchant_suspensions_open_key ON merchant_suspensions (vendor_id) WHERE status IN ('SCHEDULED', 'APPLIED');
CREATE INDEX merchant_suspensions_due_idx ON merchant_suspensions (effective_at) WHERE status = 'SCHEDULED';
CREATE INDEX merchant_suspensions_vendor_idx ON merchant_suspensions (vendor_id, created_at DESC);
