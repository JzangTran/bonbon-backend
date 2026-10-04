-- Dish options (flows/merchant/manage-menu-options.md): reusable option groups (size, toppings) with a min/max
-- number of choices, each option carrying a price delta, attached to dishes in a chosen order.
CREATE TABLE option_groups (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       UUID        NOT NULL REFERENCES vendors (id),
    name            TEXT        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 60),
    min_select      INTEGER     NOT NULL CHECK (min_select >= 0),
    max_select      INTEGER     NOT NULL CHECK (max_select >= 1),
    status          TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    updated_by_type TEXT,
    updated_by_id   UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (min_select <= max_select)
);
CREATE INDEX option_groups_vendor_idx ON option_groups (vendor_id) WHERE status = 'ACTIVE';

-- Options are archived, never deleted: order snapshots are taken from them.
CREATE TABLE options (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id        UUID        NOT NULL REFERENCES option_groups (id),
    name            TEXT        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 60),
    price_delta     INTEGER     NOT NULL DEFAULT 0 CHECK (price_delta BETWEEN 0 AND 1000000),
    status          TEXT        NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'SOLD_OUT', 'ARCHIVED')),
    is_default      BOOLEAN     NOT NULL DEFAULT false,
    display_order   INTEGER     NOT NULL DEFAULT 0,
    updated_by_type TEXT,
    updated_by_id   UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX options_group_idx ON options (group_id, display_order);

CREATE TABLE menu_item_option_groups (
    menu_item_id  UUID    NOT NULL REFERENCES menu_items (id),
    group_id      UUID    NOT NULL REFERENCES option_groups (id),
    display_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (menu_item_id, group_id)
);
CREATE INDEX menu_item_option_groups_group_idx ON menu_item_option_groups (group_id);
