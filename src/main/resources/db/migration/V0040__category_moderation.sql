-- Dish category moderation (flows/category/moderate-dish-category.md, view-category-violations.md, appeal-category-correction.md,
-- review-category-appeals.md). Commission follows a dish's category and the shop picks it, so a dish filed in a cheaper category than
-- it belongs to is flagged: dropped from search until fixed, corrected by the system after a deadline, and open to appeal.
ALTER TABLE menu_items ADD COLUMN category_restricted BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE dish_category_cases (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    dish_id               UUID        NOT NULL REFERENCES menu_items (id),
    vendor_id             UUID        NOT NULL REFERENCES vendors (id),
    dish_name             TEXT        NOT NULL,
    -- the shop's name when the case was opened, so a list needs nothing from the shop
    shop_name             TEXT        NOT NULL,
    status                TEXT        NOT NULL CHECK (status IN ('RESTRICTED', 'AUTO_CORRECTED', 'APPEALED', 'RESOLVED', 'CONFIRMED', 'EXPIRED', 'APPEAL_ACCEPTED', 'APPEAL_REJECTED')),
    reason                TEXT        NOT NULL CHECK (char_length(reason) <= 500),
    original_category_id  UUID        NOT NULL REFERENCES categories (id),
    suggested_category_id UUID        NOT NULL REFERENCES categories (id),
    deadline_at           TIMESTAMPTZ NOT NULL,
    -- what the dish ended up in, once a case is over or corrected
    final_category_id     UUID        REFERENCES categories (id),
    appeal_deadline_at    TIMESTAMPTZ,
    appeal_category_id    UUID        REFERENCES categories (id),
    appeal_note           TEXT        CHECK (char_length(appeal_note) <= 500),
    appealed_at           TIMESTAMPTZ,
    opened_by_id          UUID        NOT NULL,
    opened_at             TIMESTAMPTZ NOT NULL,
    -- SYSTEM, ADMIN or the seller's account type: whoever ended the case
    decided_by_type       TEXT,
    decided_by_id         UUID,
    decided_at            TIMESTAMPTZ,
    decision_reason       TEXT
);
-- one open case per dish
CREATE UNIQUE INDEX dish_category_cases_open_key ON dish_category_cases (dish_id) WHERE status IN ('RESTRICTED', 'AUTO_CORRECTED', 'APPEALED');
CREATE INDEX dish_category_cases_vendor_idx ON dish_category_cases (vendor_id, opened_at DESC);
CREATE INDEX dish_category_cases_queue_idx ON dish_category_cases (status, opened_at);

INSERT INTO system_settings (key, value) VALUES ('category.fix_deadline_hours', '24') ON CONFLICT (key) DO NOTHING;
