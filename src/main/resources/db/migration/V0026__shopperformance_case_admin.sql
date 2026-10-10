-- The administrator's side of order cases (flows/shop-performance/review-order-cases.md): what happened to a case and by
-- whom, kept as rows so a decision can be traced and a case reopened with its reason on record.
CREATE TABLE order_case_log (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id    UUID        NOT NULL REFERENCES order_cases (id) ON DELETE CASCADE,
    action     TEXT        NOT NULL,
    actor_type TEXT        NOT NULL,
    actor_id   UUID,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX order_case_log_case_idx ON order_case_log (case_id, created_at, id);

-- Reading the queue is a separate permission from deciding in it.
INSERT INTO role_permissions (role, permission) VALUES ('ADMIN', 'order-case:read');
