-- Shops and their application data (flows/merchant/open-shop.md). One shop account owns exactly one shop.
-- A row appears at the first saved wizard step (status DRAFT); every step may be saved incomplete.
CREATE TABLE vendors (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id           UUID         NOT NULL UNIQUE REFERENCES users (id),
    status                  TEXT         NOT NULL DEFAULT 'DRAFT'
                                         CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED', 'CLOSED')),
    name                    TEXT         CHECK (char_length(name) <= 100),
    phone                   TEXT,
    email                   TEXT,
    -- Address picked from Goong (Place Detail once on save); names, never administrative codes.
    goong_place_id          TEXT,
    formatted_address       TEXT,
    province                TEXT,
    ward                    TEXT,
    address_detail          TEXT         CHECK (char_length(address_detail) <= 200),
    lat                     DOUBLE PRECISION,
    lng                     DOUBLE PRECISION,
    delivery_radius_km      NUMERIC(4,1) CHECK (delivery_radius_km > 0),
    delivery_fee            INTEGER      CHECK (delivery_fee >= 0),
    free_delivery_threshold INTEGER      CHECK (free_delivery_threshold >= 0),
    min_order_value         INTEGER      CHECK (min_order_value >= 0),
    accepting_orders        BOOLEAN      NOT NULL DEFAULT true,
    rejection_reason        TEXT,
    submitted_at            TIMESTAMPTZ,
    decided_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((lat IS NULL) = (lng IS NULL))
);
CREATE INDEX vendors_status_idx ON vendors (status, submitted_at);

-- Weekly schedule in Asia/Ho_Chi_Minh. A window may run past midnight (closes_at < opens_at) and belongs to
-- the day it starts on.
CREATE TABLE shop_opening_hours (
    vendor_id UUID     NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    weekday   SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    opens_at  TIME     NOT NULL,
    closes_at TIME     NOT NULL,
    CHECK (opens_at <> closes_at)
);
CREATE INDEX shop_opening_hours_vendor_idx ON shop_opening_hours (vendor_id, weekday);

CREATE TABLE vendor_tax_info (
    vendor_id        UUID PRIMARY KEY REFERENCES vendors (id) ON DELETE CASCADE,
    business_type    TEXT CHECK (business_type IN ('INDIVIDUAL', 'HOUSEHOLD')),
    business_name    TEXT CHECK (char_length(business_name) <= 200),
    business_address TEXT CHECK (char_length(business_address) <= 300),
    tax_code         TEXT,
    license_file_key TEXT
);

CREATE TABLE vendor_invoice_emails (
    vendor_id UUID     NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    position  INTEGER  NOT NULL CHECK (position BETWEEN 0 AND 4),
    email     TEXT     NOT NULL,
    PRIMARY KEY (vendor_id, position)
);

-- Personal data: the document number is encrypted (FieldCipher), files live in the private bucket.
CREATE TABLE vendor_identity (
    vendor_id             UUID PRIMARY KEY REFERENCES vendors (id) ON DELETE CASCADE,
    doc_type              TEXT CHECK (doc_type IN ('CCCD', 'CMND')),
    doc_number_encrypted  TEXT,
    doc_number_last4      TEXT,
    full_name             TEXT CHECK (char_length(full_name) <= 100),
    front_file_key        TEXT,
    selfie_file_key       TEXT,
    accuracy_confirmed_at TIMESTAMPTZ,
    terms_accepted_at     TIMESTAMPTZ,
    identity_consent_at   TIMESTAMPTZ
);

-- Account number encrypted, last 4 digits in clear for display. One ACTIVE account per shop.
CREATE TABLE vendor_payout_accounts (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id                UUID        NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    -- Nullable only while the shop is a draft; submission requires all three.
    bank_name                TEXT        CHECK (char_length(bank_name) <= 100),
    account_number_encrypted TEXT,
    account_last4            TEXT,
    account_holder_name      TEXT        CHECK (char_length(account_holder_name) <= 100),
    status                   TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PENDING_HOLD', 'REPLACED')),
    effective_from           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_type          TEXT        NOT NULL,
    created_by_id            UUID,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX vendor_payout_accounts_one_active ON vendor_payout_accounts (vendor_id) WHERE status = 'ACTIVE';

-- Platform-wide delivery radius cap (merchant-approval/set-delivery-radius-cap.md); 3 km is this
-- project's starting value for a "khu vực nhỏ", changed by an administrator at runtime.
INSERT INTO system_settings (key, value) VALUES ('merchant.max_delivery_radius_km', '3') ON CONFLICT (key) DO NOTHING;
