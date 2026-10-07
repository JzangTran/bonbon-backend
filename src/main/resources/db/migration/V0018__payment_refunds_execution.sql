-- Refunds (flows/payment/process-refund.md, pay-online.md "Refunds"): who the payment belongs to, what the gateway
-- answered, how often it was tried, and the manual transfer's trail. Money is integer VND.

-- The payment knows its order number and customer so the refund queue and the notifications need no look into orders.
ALTER TABLE payments ADD COLUMN customer_id  UUID REFERENCES users (id);
ALTER TABLE payments ADD COLUMN order_number BIGINT;
UPDATE payments p SET customer_id = o.customer_id, order_number = o.number FROM orders o WHERE o.id = p.order_id;
ALTER TABLE payments ALTER COLUMN customer_id SET NOT NULL;
ALTER TABLE payments ALTER COLUMN order_number SET NOT NULL;

ALTER TABLE payment_refunds
    ADD COLUMN gateway_result_code INTEGER,
    ADD COLUMN attempts            INTEGER     NOT NULL DEFAULT 0,
    -- a worker holds the refund until this moment; nobody else submits it meanwhile (and MoMo refuses a repeated orderId)
    ADD COLUMN claimed_until       TIMESTAMPTZ,
    ADD COLUMN failure_reason      TEXT,
    ADD COLUMN transferred_at      TIMESTAMPTZ,
    ADD COLUMN completed_by        UUID,
    -- the full number is encrypted in destination_number; only the last four digits are shown outside the queue
    ADD COLUMN destination_last4   TEXT;

-- One bank reference settles one refund: a reused reference is a probable double entry.
CREATE UNIQUE INDEX payment_refunds_bank_reference_uniq ON payment_refunds (lower(bank_reference)) WHERE bank_reference IS NOT NULL;
CREATE INDEX payment_refunds_work_idx ON payment_refunds (created_at) WHERE mode = 'GATEWAY' AND status IN ('REQUESTED', 'PROCESSING');
CREATE INDEX payment_refunds_queue_idx ON payment_refunds (created_at) WHERE mode = 'MANUAL' AND status IN ('REQUESTED', 'NEEDS_DESTINATION');

-- Every step of a refund, by whom: the sheet an accountant reconciles against the MoMo statement and the bank.
CREATE TABLE payment_refund_log (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id  UUID        NOT NULL REFERENCES payment_refunds (id),
    action     TEXT        NOT NULL,
    actor_type TEXT        NOT NULL,
    actor_id   UUID,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payment_refund_log_refund_idx ON payment_refund_log (refund_id, created_at);
