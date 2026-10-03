-- Platform dish categories (flows/category/manage-categories.md): a 3-level tree whose level 1 is the fixed
-- "Thực phẩm và đồ uống". Every dish will sit on a leaf; a commission rate may be set on any node and is
-- inherited by descendants without their own.
CREATE TABLE categories (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id       UUID         REFERENCES categories (id),
    level           SMALLINT     NOT NULL CHECK (level BETWEEN 1 AND 3),
    name            TEXT         NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    active          BOOLEAN      NOT NULL DEFAULT true,
    commission_rate NUMERIC(5,2) CHECK (commission_rate BETWEEN 0 AND 100),
    acted_by_type   TEXT,
    acted_by_id     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((level = 1) = (parent_id IS NULL))
);
CREATE INDEX categories_parent_idx ON categories (parent_id, sort_order);
-- Sibling names are unique, compared case-insensitively.
CREATE UNIQUE INDEX categories_sibling_name_key
    ON categories (COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(btrim(name)));
-- Exactly one root.
CREATE UNIQUE INDEX categories_single_root_key ON categories ((true)) WHERE parent_id IS NULL;

-- Starter tree so the system is usable from day one; administrators adjust it from there.
WITH root AS (
    INSERT INTO categories (level, name, sort_order, acted_by_type) VALUES (1, 'Thực phẩm và đồ uống', 0, 'SYSTEM')
    RETURNING id
), l2 AS (
    INSERT INTO categories (parent_id, level, name, sort_order, acted_by_type)
    SELECT root.id, 2, v.name, v.ord, 'SYSTEM'
    FROM root, (VALUES ('Món chính', 1), ('Ăn vặt & tráng miệng', 2), ('Đồ uống', 3)) AS v(name, ord)
    RETURNING id, name
)
INSERT INTO categories (parent_id, level, name, sort_order, acted_by_type)
SELECT l2.id, 3, v.name, v.ord, 'SYSTEM'
FROM l2 JOIN (VALUES
    ('Món chính', 'Cơm', 1), ('Món chính', 'Bún & phở', 2), ('Món chính', 'Mì & miến', 3),
    ('Món chính', 'Bánh mì & xôi', 4), ('Món chính', 'Lẩu & nướng', 5),
    ('Ăn vặt & tráng miệng', 'Đồ ăn vặt', 1), ('Ăn vặt & tráng miệng', 'Bánh ngọt', 2),
    ('Ăn vặt & tráng miệng', 'Chè & tráng miệng', 3),
    ('Đồ uống', 'Cà phê', 1), ('Đồ uống', 'Trà sữa', 2), ('Đồ uống', 'Nước ép & sinh tố', 3),
    ('Đồ uống', 'Trà & nước giải khát', 4)
) AS v(parent, name, ord) ON v.parent = l2.name;
