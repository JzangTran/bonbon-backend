-- The per-shop ledger (flows/settlement/README.md): the source of truth for money between the platform and a shop.
-- Balance = sum of amount. Positive: the platform owes the shop; negative: the shop owes the platform. Money is
-- integer VND. Entries are append-only: a mistake is corrected by an opposite ADJUSTMENT, never by an edit.
CREATE TABLE ledger_entries (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id     UUID        NOT NULL REFERENCES vendors (id),
    type          TEXT        NOT NULL CHECK (type IN ('ONLINE_EARNING', 'COD_COMMISSION', 'PAYOUT', 'COLLECTION', 'ADJUSTMENT',
                                                       'CASE_REFUND', 'CASE_COMMISSION_REVERSAL', 'TAX_WITHHOLDING')),
    amount        INTEGER     NOT NULL CHECK (amount <> 0),
    order_id      UUID        REFERENCES orders (id),
    case_id       UUID,
    reference     TEXT,
    note          TEXT,
    acted_by_type TEXT        NOT NULL,
    acted_by_id   UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- each type has one direction; only an adjustment may go either way
    CHECK ((type IN ('ONLINE_EARNING', 'COLLECTION', 'CASE_COMMISSION_REVERSAL') AND amount > 0)
        OR (type IN ('COD_COMMISSION', 'PAYOUT', 'CASE_REFUND', 'TAX_WITHHOLDING') AND amount < 0)
        OR type = 'ADJUSTMENT'),
    CHECK (type NOT IN ('ONLINE_EARNING', 'COD_COMMISSION') OR order_id IS NOT NULL)
);
CREATE INDEX ledger_entries_vendor_idx ON ledger_entries (vendor_id, created_at DESC, id);
-- A retry or a repeated event can never post an order twice.
CREATE UNIQUE INDEX ledger_entries_order_type_key ON ledger_entries (order_id, type) WHERE order_id IS NOT NULL AND case_id IS NULL;
CREATE UNIQUE INDEX ledger_entries_case_type_key ON ledger_entries (case_id, type) WHERE case_id IS NOT NULL;

CREATE FUNCTION ledger_entries_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: correct a mistake with an opposite ADJUSTMENT';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER ledger_entries_no_change BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_append_only();

-- Orders that ended before the ledger existed get the entry they would have had: online-paid orders credit the shop what
-- the customer paid minus the commission, cash orders charge the commission. Never for cancelled or rejected orders.
INSERT INTO ledger_entries (vendor_id, type, amount, order_id, acted_by_type, created_at)
SELECT o.vendor_id,
       CASE WHEN o.payment_method = 'ONLINE' THEN 'ONLINE_EARNING' ELSE 'COD_COMMISSION' END,
       CASE WHEN o.payment_method = 'ONLINE' THEN o.grand_total - o.commission_amount ELSE -o.commission_amount END,
       o.id, 'SYSTEM', coalesce(o.finished_at, o.updated_at)
FROM orders o
WHERE (o.status = 'DELIVERED' OR (o.status = 'NOT_DELIVERED' AND o.payment_method = 'ONLINE'))
  AND (o.payment_method = 'ONLINE' OR o.commission_amount > 0)
  AND (o.payment_method = 'COD' OR o.grand_total - o.commission_amount > 0);
