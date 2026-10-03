-- Who paused or resumed order intake, and when (flows/merchant/pause-orders.md).
ALTER TABLE vendors
    ADD COLUMN accepting_orders_changed_at      TIMESTAMPTZ,
    ADD COLUMN accepting_orders_changed_by_type TEXT,
    ADD COLUMN accepting_orders_changed_by_id   UUID;
