-- The administrators' lookup of orders, customers and conversations (flows/support/admin-order-lookup.md). Every search, every
-- record opened and every reveal of masked contact details leaves a row: who, what, when, and why for a reveal.
CREATE TABLE admin_lookup_audit (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id     UUID        NOT NULL REFERENCES users (id),
    action       TEXT        NOT NULL CHECK (action IN ('SEARCH_ORDERS', 'OPEN_ORDER', 'REVEAL_ORDER', 'SEARCH_CUSTOMERS', 'OPEN_CUSTOMER', 'REVEAL_CUSTOMER',
                                                       'READ_CONVERSATION')),
    -- what was typed into the search, or null when a record was opened
    query        TEXT,
    subject_id   UUID,
    result_count INTEGER,
    -- only for a reveal: DISPUTE, DATA_REQUEST, SAFETY or OTHER (with the note)
    reason       TEXT        CHECK (reason IN ('DISPUTE', 'DATA_REQUEST', 'SAFETY', 'OTHER')),
    reason_note  TEXT,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX admin_lookup_audit_admin_idx ON admin_lookup_audit (admin_id, created_at DESC);
CREATE INDEX admin_lookup_audit_subject_idx ON admin_lookup_audit (subject_id, created_at DESC);
