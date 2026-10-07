-- Commission rates over time (flows/settlement/set-commission-rate.md): "what rate applied on this date" must stay
-- answerable, so every change appends a row. The rate in force still lives where orders read it: the global default is
-- the system setting commission.default_rate, a category's own rate is categories.commission_rate.
CREATE TABLE commission_rate_history (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    scope          TEXT         NOT NULL CHECK (scope IN ('DEFAULT', 'CATEGORY')),
    -- not a foreign key: a deleted category keeps its history
    category_id    UUID,
    category_name  TEXT,
    -- null on a category row means the node went back to inheriting
    rate           NUMERIC(5,2) CHECK (rate BETWEEN 0 AND 100),
    previous_rate  NUMERIC(5,2),
    effective_from TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acted_by_type  TEXT         NOT NULL,
    acted_by_id    UUID,
    CHECK ((scope = 'DEFAULT') = (category_id IS NULL)),
    CHECK (scope = 'CATEGORY' OR rate IS NOT NULL)
);
CREATE INDEX commission_rate_history_time_idx ON commission_rate_history (effective_from DESC, id);
CREATE INDEX commission_rate_history_category_idx ON commission_rate_history (category_id, effective_from DESC);

-- The two settings the code falls back on, written down so an admin sees them.
INSERT INTO system_settings (key, value) VALUES ('commission.default_rate', '10') ON CONFLICT (key) DO NOTHING;
INSERT INTO system_settings (key, value) VALUES ('commission.vat_percent', '8') ON CONFLICT (key) DO NOTHING;

-- What is already in force becomes the first history rows.
INSERT INTO commission_rate_history (scope, rate, effective_from, acted_by_type)
SELECT 'DEFAULT', value::numeric(5,2), updated_at, 'SYSTEM' FROM system_settings WHERE key = 'commission.default_rate';
INSERT INTO commission_rate_history (scope, category_id, category_name, rate, effective_from, acted_by_type, acted_by_id)
SELECT 'CATEGORY', id, name, commission_rate, updated_at, coalesce(acted_by_type, 'SYSTEM'), acted_by_id
FROM categories WHERE commission_rate IS NOT NULL;
