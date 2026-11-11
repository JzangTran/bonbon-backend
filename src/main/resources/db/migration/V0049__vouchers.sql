-- Shop vouchers (manage-vouchers.md) and the picture at the top of a shop's page.

CREATE TABLE marketplace.vouchers (
    id               UUID PRIMARY KEY,
    vendor_id        UUID NOT NULL REFERENCES marketplace.vendors (id),
    code             TEXT NOT NULL,
    discount_type    TEXT NOT NULL CHECK (discount_type IN ('PERCENT', 'FIXED')),
    discount_value   INTEGER NOT NULL CHECK (discount_value > 0),
    -- the most a PERCENT voucher takes off; null means no ceiling
    max_discount     INTEGER CHECK (max_discount IS NULL OR max_discount > 0),
    min_order_value  INTEGER CHECK (min_order_value IS NULL OR min_order_value >= 0),
    valid_from       TIMESTAMPTZ NOT NULL,
    valid_to         TIMESTAMPTZ NOT NULL,
    -- null means no limit
    usage_limit      INTEGER CHECK (usage_limit IS NULL OR usage_limit > 0),
    used_count       INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    max_per_customer INTEGER CHECK (max_per_customer IS NULL OR max_per_customer > 0),
    status           TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CHECK (valid_to > valid_from),
    CHECK (discount_type <> 'PERCENT' OR discount_value <= 100),
    CHECK (usage_limit IS NULL OR used_count <= usage_limit)
);
-- Codes are unique within a shop, not across shops; a code is matched whatever its case.
CREATE UNIQUE INDEX vouchers_vendor_code ON marketplace.vouchers (vendor_id, upper(code));
CREATE INDEX orders_customer_voucher ON ordering.orders (customer_id, voucher_id) WHERE voucher_id IS NOT NULL;

-- The picture behind the top of the shop's page in the customer app.
ALTER TABLE marketplace.vendors ADD COLUMN cover_key TEXT;
