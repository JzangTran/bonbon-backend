-- Orders (flows/order/README.md, place-order.md). Everything a customer or a shop sees later is a snapshot taken
-- here: names, prices, options, the delivery address, the commission rate. Money is integer VND.
CREATE TABLE orders (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    number            BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    customer_id       UUID        NOT NULL REFERENCES users (id),
    vendor_id         UUID        NOT NULL REFERENCES vendors (id),
    vendor_name       TEXT        NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PREPARING',
                                                             'OUT_FOR_DELIVERY', 'DELIVERED', 'REJECTED', 'CANCELLED',
                                                             'NOT_DELIVERED')),
    version           INTEGER     NOT NULL DEFAULT 0,
    payment_method    TEXT        NOT NULL CHECK (payment_method IN ('COD', 'ONLINE')),
    payment_status    TEXT        NOT NULL DEFAULT 'PENDING' CHECK (payment_status IN ('PENDING', 'PAID', 'SUCCESS', 'FAILED', 'REFUNDED')),
    delivery_name     TEXT        NOT NULL,
    delivery_phone    TEXT        NOT NULL,
    delivery_address  TEXT        NOT NULL,
    delivery_lat      DOUBLE PRECISION NOT NULL,
    delivery_lng      DOUBLE PRECISION NOT NULL,
    note              TEXT        CHECK (char_length(note) <= 300),
    items_total       INTEGER     NOT NULL CHECK (items_total >= 0),
    discount          INTEGER     NOT NULL DEFAULT 0 CHECK (discount >= 0),
    delivery_fee      INTEGER     NOT NULL CHECK (delivery_fee >= 0),
    grand_total       INTEGER     NOT NULL CHECK (grand_total >= 0),
    commission_amount INTEGER     NOT NULL DEFAULT 0,
    voucher_id        UUID,
    incident_hold     BOOLEAN     NOT NULL DEFAULT false,
    idempotency_key   TEXT        NOT NULL,
    client_ip         TEXT,
    client_agent      TEXT,
    placed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at      TIMESTAMPTZ,
    out_for_delivery_at TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX orders_customer_idx ON orders (customer_id, placed_at DESC);
CREATE INDEX orders_vendor_idx ON orders (vendor_id, placed_at DESC);
CREATE INDEX orders_status_idx ON orders (status, placed_at);

CREATE TABLE order_items (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id           UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    menu_item_id       UUID        NOT NULL REFERENCES menu_items (id),
    position           INTEGER     NOT NULL,
    name               TEXT        NOT NULL,
    unit_price         INTEGER     NOT NULL CHECK (unit_price >= 0),
    quantity           INTEGER     NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    line_total         INTEGER     NOT NULL CHECK (line_total >= 0),
    note               TEXT        CHECK (char_length(note) <= 200),
    category_id        UUID        NOT NULL,
    commission_rate    NUMERIC(5,2) NOT NULL,
    allocated_discount INTEGER     NOT NULL DEFAULT 0,
    commission_amount  INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX order_items_order_idx ON order_items (order_id, position);

CREATE TABLE order_item_options (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    order_item_id UUID    NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    group_name    TEXT    NOT NULL,
    option_name   TEXT    NOT NULL,
    price_delta   INTEGER NOT NULL
);
CREATE INDEX order_item_options_item_idx ON order_item_options (order_item_id, position);

CREATE TABLE order_status_history (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    acted_by_type  TEXT        NOT NULL,
    acted_by_id    UUID,
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, created_at);
