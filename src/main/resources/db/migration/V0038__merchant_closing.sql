-- Closing a shop (flows/merchant/close-shop.md): APPROVED or SUSPENDED -> CLOSING (the owner asked) -> CLOSED (nothing left to settle).
ALTER TABLE vendors DROP CONSTRAINT IF EXISTS vendors_status_check;
ALTER TABLE vendors ADD CONSTRAINT vendors_status_check CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED', 'CLOSING', 'CLOSED'));

ALTER TABLE vendors
    ADD COLUMN closing_reason       TEXT CHECK (closing_reason IN ('STOP_SELLING', 'RELOCATING', 'TOO_FEW_ORDERS', 'OTHER')),
    ADD COLUMN closing_reason_text  TEXT CHECK (char_length(closing_reason_text) <= 500),
    ADD COLUMN closing_requested_at TIMESTAMPTZ,
    ADD COLUMN closed_at            TIMESTAMPTZ;

CREATE INDEX vendors_closing_idx ON vendors (closing_requested_at) WHERE status = 'CLOSING';
