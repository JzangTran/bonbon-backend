-- Appeals against a penalty point (flows/shop-performance/manage-penalties.md): one per point, inside the appeal window, decided by an
-- administrator. A pending appeal does not lift anything; an accepted one waives the point.
ALTER TABLE shop_penalties
    ADD COLUMN appeal_status          TEXT CHECK (appeal_status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    ADD COLUMN appeal_reason          TEXT CHECK (char_length(appeal_reason) <= 500),
    ADD COLUMN appealed_at            TIMESTAMPTZ,
    ADD COLUMN appeal_decided_by_id   UUID,
    ADD COLUMN appeal_decided_at      TIMESTAMPTZ,
    ADD COLUMN appeal_decision_reason TEXT;
CREATE INDEX shop_penalties_appeal_idx ON shop_penalties (appealed_at) WHERE appeal_status = 'PENDING';

-- Reading the list is a separate permission from changing points (shop-penalty:write already exists).
INSERT INTO role_permissions (role, permission) VALUES ('ADMIN', 'shop-penalty:read');
