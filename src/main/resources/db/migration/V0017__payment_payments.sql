-- Payments (flows/payment/pay-online.md, pay-cod.md). One payments row per order, whatever the method; one
-- payment_attempts row per MoMo request (every retry keeps its own). Money is integer VND.
CREATE TABLE payments (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID        NOT NULL UNIQUE REFERENCES orders (id),
    method          TEXT        NOT NULL CHECK (method IN ('COD', 'ONLINE')),
    provider        TEXT,
    status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    amount          INTEGER     NOT NULL CHECK (amount >= 0),
    refunded_amount INTEGER     NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Orders placed before this table existed (all cash on delivery) get the row they would have had.
INSERT INTO payments (order_id, method, status, amount)
SELECT o.id, o.payment_method,
       CASE WHEN o.payment_status IN ('PAID', 'SUCCESS') THEN 'SUCCESS'
            WHEN o.payment_status = 'REFUNDED' THEN 'REFUNDED'
            WHEN o.payment_status = 'FAILED' OR o.status IN ('CANCELLED', 'REJECTED', 'NOT_DELIVERED') THEN 'FAILED'
            ELSE 'PENDING' END,
       o.grand_total
FROM orders o;

CREATE TABLE payment_attempts (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id        UUID        NOT NULL REFERENCES payments (id),
    attempt_no        INTEGER     NOT NULL,
    -- sent to MoMo as orderId; MoMo rejects a reused one (result code 41)
    provider_order_id TEXT        NOT NULL UNIQUE,
    request_id        TEXT        NOT NULL,
    pay_url           TEXT,
    deeplink          TEXT,
    qr_code_url       TEXT,
    provider_trans_id BIGINT,
    result_code       INTEGER,
    pay_type          TEXT,
    status            TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXPIRED')),
    raw_response      TEXT,
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payment_attempts_payment_idx ON payment_attempts (payment_id, attempt_no DESC);
CREATE INDEX payment_attempts_pending_idx ON payment_attempts (created_at) WHERE status = 'PENDING';

-- Money owed back to the payer (pay-online.md "Refunds"). This table only receives the requests; executing them,
-- through the gateway or by a manual transfer, is the refund work (backend#74).
CREATE TABLE payment_refunds (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id         UUID        NOT NULL REFERENCES payments (id),
    case_id            UUID,
    reason             TEXT        NOT NULL CHECK (reason IN ('ORDER_CLOSED', 'LATE_PAYMENT', 'CASE_UPHELD')),
    amount             INTEGER     NOT NULL CHECK (amount > 0),
    status             TEXT        NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'NEEDS_DESTINATION', 'FAILED')),
    mode               TEXT        NOT NULL DEFAULT 'GATEWAY' CHECK (mode IN ('GATEWAY', 'MANUAL')),
    provider_order_id  TEXT        UNIQUE,
    provider_trans_id  BIGINT,
    destination_bank   TEXT,
    destination_number TEXT,
    destination_name   TEXT,
    bank_reference     TEXT,
    requested_by_type  TEXT        NOT NULL DEFAULT 'SYSTEM',
    requested_by_id    UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);
-- A retry of the same event can never ask twice.
CREATE UNIQUE INDEX payment_refunds_case_uniq ON payment_refunds (payment_id, case_id) WHERE case_id IS NOT NULL;
CREATE UNIQUE INDEX payment_refunds_event_uniq ON payment_refunds (payment_id, reason) WHERE case_id IS NULL;
