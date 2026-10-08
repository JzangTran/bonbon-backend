-- Shop performance (flows/shop-performance/README.md): the orders that failed because of the shop, the weekly rate turned into
-- penalty points, and what the points do to a shop's visibility. Thresholds live in system settings, not here.
CREATE TABLE shop_fault_events (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id    UUID        NOT NULL REFERENCES vendors (id),
    order_id     UUID        NOT NULL REFERENCES orders (id),
    order_number BIGINT      NOT NULL,
    type         TEXT        NOT NULL CHECK (type IN ('SHOP_REJECTED', 'NO_RESPONSE', 'HANDOVER_TIMEOUT', 'SHOP_CANCELLED', 'INCIDENT_FULL_REFUND', 'NO_SHOW_SHOP_AT_FAULT')),
    case_id      UUID,
    occurred_at  TIMESTAMPTZ NOT NULL,
    -- an order can fail the shop in one way only once, so a repeated event writes nothing
    UNIQUE (order_id, type)
);
CREATE INDEX shop_fault_events_vendor_idx ON shop_fault_events (vendor_id, occurred_at);
CREATE INDEX shop_fault_events_case_idx ON shop_fault_events (case_id) WHERE case_id IS NOT NULL;

CREATE TABLE shop_penalties (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       UUID        NOT NULL REFERENCES vendors (id),
    points          INTEGER     NOT NULL CHECK (points <> 0),
    source          TEXT        NOT NULL CHECK (source IN ('WEEKLY', 'MANUAL')),
    -- the Monday the evaluated week started (WEEKLY only)
    week_start      DATE,
    finished_orders INTEGER,
    fault_orders    INTEGER,
    reason          TEXT,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'WAIVED')),
    issued_at       TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    decided_by_type TEXT,
    decided_by_id   UUID,
    decided_at      TIMESTAMPTZ,
    decision_reason TEXT
);
-- a job that runs twice cannot punish a week twice
CREATE UNIQUE INDEX shop_penalties_week_key ON shop_penalties (vendor_id, week_start) WHERE source = 'WEEKLY';
CREATE INDEX shop_penalties_vendor_idx ON shop_penalties (vendor_id, issued_at DESC);

-- Where each shop stands: the notice of a coming restriction (sent at least 5 days ahead, the legal minimum), whether the
-- restriction applies now, and whether an administrator should look at it.
CREATE TABLE shop_performance_state (
    vendor_id             UUID        PRIMARY KEY REFERENCES vendors (id),
    notice_sent_at        TIMESTAMPTZ,
    restriction_starts_at TIMESTAMPTZ,
    restricted            BOOLEAN     NOT NULL DEFAULT false,
    review_flagged        BOOLEAN     NOT NULL DEFAULT false,
    updated_at            TIMESTAMPTZ NOT NULL
);

-- The visibility restriction itself lives on the shop, next to the one unpaid commission can cause; each has its own reason.
ALTER TABLE vendors ADD COLUMN performance_restricted BOOLEAN NOT NULL DEFAULT false;
