-- The customer home page (owner decision 2026-11-03): discounted dishes, shop logos, display categories, favourite and hidden shops.

-- A dish's price before its discount, set by the seller; the customer always pays `price`. Null means no discount.
ALTER TABLE marketplace.menu_items ADD COLUMN original_price INTEGER;
ALTER TABLE marketplace.menu_items ADD CONSTRAINT menu_items_original_above_price
    CHECK (original_price IS NULL OR original_price > price);

-- The square picture next to the shop's name.
ALTER TABLE marketplace.vendors ADD COLUMN logo_key TEXT;

-- Display categories: the picture strip on the home page, set by administrators. Not the 3-level tree: each one gathers
-- leaf categories of the tree (Trà sữa = trà sữa + trà trái cây...), and picking it lists shops with dishes in any of them.
CREATE TABLE marketplace.display_categories (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    image_key   TEXT,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE marketplace.display_category_leaves (
    display_category_id UUID NOT NULL REFERENCES marketplace.display_categories (id) ON DELETE CASCADE,
    category_id         UUID NOT NULL REFERENCES marketplace.categories (id),
    PRIMARY KEY (display_category_id, category_id)
);

-- A customer's favourite shops (the heart on the home page).
CREATE TABLE ordering.favorite_shops (
    user_id    UUID NOT NULL,
    vendor_id  UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, vendor_id)
);

-- "Ẩn quán này": the shop is left out of the customer's home page until `hidden_until` (30 days).
CREATE TABLE ordering.hidden_shops (
    user_id      UUID NOT NULL,
    vendor_id    UUID NOT NULL,
    hidden_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, vendor_id)
);
CREATE INDEX hidden_shops_user_until ON ordering.hidden_shops (user_id, hidden_until);
