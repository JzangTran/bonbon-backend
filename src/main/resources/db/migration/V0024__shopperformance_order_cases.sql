-- Order cases (flows/shop-performance/review-order-cases.md): a complaint about a delivered order, answered by the shop
-- and decided by an administrator only when they disagree. Money figures are snapshotted when the case is filed, so a
-- later commission change cannot alter the outcome.
CREATE TABLE order_cases (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID        NOT NULL REFERENCES orders (id),
    vendor_id             UUID        NOT NULL REFERENCES vendors (id),
    customer_id           UUID        NOT NULL REFERENCES users (id),
    type                  TEXT        NOT NULL CHECK (type IN ('NOT_RECEIVED', 'MISSING_ITEM', 'WRONG_ITEM', 'QUALITY', 'CUSTOMER_NO_SHOW', 'SUSPECTED_FAKE')),
    status                TEXT        NOT NULL CHECK (status IN ('AWAITING_SHOP', 'AWAITING_CUSTOMER', 'OPEN', 'UPHELD', 'DISMISSED')),
    refund_amount         INTEGER     NOT NULL DEFAULT 0 CHECK (refund_amount >= 0),
    commission_amount     INTEGER     NOT NULL DEFAULT 0 CHECK (commission_amount >= 0),
    note                  TEXT        CHECK (char_length(note) <= 500),
    shop_response         TEXT        CHECK (shop_response IN ('ACCEPTED', 'DISPUTED')),
    shop_response_note    TEXT        CHECK (char_length(shop_response_note) <= 500),
    shop_response_due_at  TIMESTAMPTZ,
    shop_responded_at     TIMESTAMPTZ,
    opened_by_type        TEXT        NOT NULL,
    opened_by_id          UUID,
    opened_at             TIMESTAMPTZ NOT NULL,
    decided_by_type       TEXT,
    decided_by_id         UUID,
    decided_at            TIMESTAMPTZ,
    reason                TEXT,
    version               INTEGER     NOT NULL DEFAULT 0
);
-- one case the customer files per order, and one no-show the shop files
CREATE UNIQUE INDEX order_cases_customer_filed_key ON order_cases (order_id) WHERE type IN ('NOT_RECEIVED', 'MISSING_ITEM', 'WRONG_ITEM', 'QUALITY');
CREATE UNIQUE INDEX order_cases_no_show_key ON order_cases (order_id) WHERE type = 'CUSTOMER_NO_SHOW';
CREATE INDEX order_cases_queue_idx ON order_cases (status, opened_at);
CREATE INDEX order_cases_vendor_idx ON order_cases (vendor_id, opened_at DESC);
CREATE INDEX order_cases_customer_idx ON order_cases (customer_id, opened_at DESC);
CREATE INDEX order_cases_due_idx ON order_cases (shop_response_due_at) WHERE status = 'AWAITING_SHOP';

CREATE TABLE order_case_items (
    id                UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id           UUID    NOT NULL REFERENCES order_cases (id) ON DELETE CASCADE,
    order_item_id     UUID    NOT NULL REFERENCES order_items (id),
    item_name         TEXT    NOT NULL,
    quantity          INTEGER NOT NULL CHECK (quantity >= 1),
    refund_amount     INTEGER NOT NULL CHECK (refund_amount >= 0),
    commission_amount INTEGER NOT NULL CHECK (commission_amount >= 0),
    UNIQUE (case_id, order_item_id)
);

CREATE TABLE order_case_photos (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id          UUID        NOT NULL REFERENCES order_cases (id) ON DELETE CASCADE,
    file_key         TEXT        NOT NULL,
    uploaded_by_type TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (case_id, file_key)
);

-- Money set aside while a case is undecided (flows/settlement/record-payout.md): the shop's payable balance is the
-- balance minus what is held, so a payout cannot run ahead of a pending refund. Owned by settlement.
CREATE TABLE settlement_case_holds (
    case_id     UUID        PRIMARY KEY,
    vendor_id   UUID        NOT NULL REFERENCES vendors (id),
    amount      INTEGER     NOT NULL CHECK (amount >= 0),
    created_at  TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ
);
CREATE INDEX settlement_case_holds_vendor_idx ON settlement_case_holds (vendor_id) WHERE released_at IS NULL;

-- A customer may report a problem with their own delivered order; the order itself decides whose it is.
INSERT INTO role_permissions (role, permission) VALUES ('CUSTOMER', 'order:report');
