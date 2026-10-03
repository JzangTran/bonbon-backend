-- Review decisions on shop applications (merchant-approval/approve-seller.md, reject-seller.md): one row per
-- decision, so a shop rejected and resubmitted keeps its history.
CREATE TABLE vendor_review_decisions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       UUID        NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    decision        TEXT        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason          TEXT        CHECK (char_length(reason) <= 1000),
    decided_by_type TEXT        NOT NULL,
    decided_by_id   UUID        NOT NULL,
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (decision = 'APPROVED' OR reason IS NOT NULL)
);
CREATE INDEX vendor_review_decisions_vendor_idx ON vendor_review_decisions (vendor_id, decided_at DESC);

-- Every view of a shop owner's identity documents (open-shop.md "Sensitive data handling").
CREATE TABLE identity_document_access_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id   UUID        NOT NULL REFERENCES vendors (id) ON DELETE CASCADE,
    admin_id    UUID        NOT NULL,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip          TEXT
);
CREATE INDEX identity_document_access_log_vendor_idx ON identity_document_access_log (vendor_id, accessed_at DESC);
