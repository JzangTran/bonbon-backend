-- Versioned legal documents (immutable once published) and append-only consent evidence.

CREATE TABLE legal_documents (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type                  TEXT        NOT NULL CHECK (type IN ('CUSTOMER_TERMS', 'SELLER_TERMS', 'PRIVACY_POLICY')),
    language              TEXT        NOT NULL DEFAULT 'vi',
    version               INTEGER     NOT NULL CHECK (version > 0),
    title                 TEXT        NOT NULL,
    summary               TEXT,
    content               TEXT        NOT NULL,
    requires_reacceptance BOOLEAN     NOT NULL DEFAULT true,
    published_at          TIMESTAMPTZ,
    effective_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (type, language, version)
);

-- Never updated or deleted: a withdrawal is a new row with granted = false.
CREATE TABLE consent_records (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type TEXT        NOT NULL,
    principal_id   UUID        NOT NULL,
    purpose        TEXT        NOT NULL CHECK (purpose IN ('TERMS', 'IDENTITY_VERIFICATION', 'MARKETING', 'LOCATION_FOR_AREA')),
    document_id    UUID        REFERENCES legal_documents (id),
    granted        BOOLEAN     NOT NULL,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    channel        TEXT        NOT NULL CHECK (channel IN ('MOBILE', 'WEB')),
    app_version    TEXT,
    ip             TEXT
);
CREATE INDEX consent_records_principal_idx ON consent_records (principal_type, principal_id, purpose, at DESC);

-- Version 1 placeholders so registration works before the admin publishing flow (Sprint 9).
-- The real wording is written by the project owner with a legal advisor and published as version 2.
INSERT INTO legal_documents (type, version, title, summary, content, published_at, effective_at) VALUES
    ('CUSTOMER_TERMS', 1, 'Điều khoản sử dụng (khách hàng)', 'Bản nháp v1',
     'BẢN NHÁP — nội dung chính thức do chủ dự án và cố vấn pháp lý soạn, sẽ được đăng ở phiên bản 2.',
     now(), now()),
    ('SELLER_TERMS', 1, 'Điều khoản dành cho người bán', 'Bản nháp v1',
     'BẢN NHÁP — nội dung chính thức do chủ dự án và cố vấn pháp lý soạn, sẽ được đăng ở phiên bản 2.',
     now(), now()),
    ('PRIVACY_POLICY', 1, 'Chính sách quyền riêng tư', 'Bản nháp v1',
     'BẢN NHÁP — nội dung chính thức do chủ dự án và cố vấn pháp lý soạn, sẽ được đăng ở phiên bản 2.',
     now(), now());
