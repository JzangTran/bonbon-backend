-- A shop's menu (flows/merchant/manage-menu.md, manage-menu-categories.md). A section is the shop's own display
-- grouping; the platform category on a dish (a leaf of the category tree) decides its commission and search.
CREATE TABLE menu_sections (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       UUID        NOT NULL REFERENCES vendors (id),
    name            TEXT        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 60),
    sort_order      INTEGER     NOT NULL DEFAULT 0,
    updated_by_type TEXT,
    updated_by_id   UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX menu_sections_vendor_idx ON menu_sections (vendor_id, sort_order);
CREATE UNIQUE INDEX menu_sections_name_key ON menu_sections (vendor_id, lower(btrim(name)));

-- Dishes are archived, never deleted: past orders keep pointing at them. stock_quantity NULL means unlimited.
CREATE TABLE menu_items (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       UUID        NOT NULL REFERENCES vendors (id),
    section_id      UUID        REFERENCES menu_sections (id) ON DELETE SET NULL, -- NULL only for archived dishes
    category_id     UUID        NOT NULL REFERENCES categories (id),
    name            TEXT        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    description     TEXT        CHECK (char_length(description) <= 500),
    price           INTEGER     NOT NULL CHECK (price BETWEEN 0 AND 10000000),
    photo_key       TEXT,
    status          TEXT        NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'SOLD_OUT')),
    stock_quantity  INTEGER     CHECK (stock_quantity >= 0),
    sort_order      INTEGER     NOT NULL DEFAULT 0,
    archived_at     TIMESTAMPTZ,
    updated_by_type TEXT,
    updated_by_id   UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX menu_items_section_idx ON menu_items (section_id, sort_order) WHERE archived_at IS NULL;
CREATE INDEX menu_items_vendor_idx ON menu_items (vendor_id) WHERE archived_at IS NULL;
CREATE INDEX menu_items_category_idx ON menu_items (category_id);
