-- Customer delivery addresses (flows/account/manage-delivery-addresses.md). Orders keep their own snapshot,
-- so editing or deleting an address never touches order history.
CREATE TABLE addresses (
    id                UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label             TEXT             NOT NULL CHECK (char_length(label) BETWEEN 1 AND 30),
    goong_place_id    TEXT             NOT NULL,
    formatted_address TEXT             NOT NULL,
    province          TEXT,
    ward              TEXT,
    detail            TEXT             CHECK (char_length(detail) <= 200),
    recipient_name    TEXT             NOT NULL CHECK (char_length(recipient_name) BETWEEN 1 AND 100),
    recipient_phone   TEXT             NOT NULL,
    lat               DOUBLE PRECISION NOT NULL,
    lng               DOUBLE PRECISION NOT NULL,
    is_default        BOOLEAN          NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX addresses_customer_idx ON addresses (customer_id);
-- Exactly one default per customer, enforced by the database rather than by "unset the old one first".
CREATE UNIQUE INDEX addresses_one_default ON addresses (customer_id) WHERE is_default;
