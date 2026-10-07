-- What a statement needs to show without reading orders: the order figures an entry was posted from, and the key that
-- makes recording a payout safe to repeat (a double click returns the first entry).
ALTER TABLE ledger_entries
    ADD COLUMN items_total     INTEGER,
    ADD COLUMN discount        INTEGER,
    ADD COLUMN delivery_fee    INTEGER,
    -- VAT-inclusive commission snapshotted on the order
    ADD COLUMN commission      INTEGER,
    ADD COLUMN idempotency_key TEXT;

-- The append-only trigger refuses updates; the back-fill of figures is the one allowed exception, done here once.
ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_no_change;
UPDATE ledger_entries e SET items_total = o.items_total, discount = o.discount, delivery_fee = o.delivery_fee, commission = o.commission_amount
FROM orders o WHERE o.id = e.order_id;
ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_no_change;

CREATE UNIQUE INDEX ledger_entries_idempotency_key ON ledger_entries (acted_by_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
