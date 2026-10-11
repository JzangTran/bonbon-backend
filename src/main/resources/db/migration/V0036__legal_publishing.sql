-- Publishing a new version of a legal document (flows/legal/publish-legal-document.md).
-- A version is a DRAFT (only administrators see it), then PUBLISHED with an effective date (immutable from here: its text is
-- hashed), or CANCELLED before it took effect. SUPERSEDED is not stored: a published, effective version is superseded the moment
-- a higher version becomes effective, and the version in force is always the highest effective one.
ALTER TABLE legal_documents
    ADD COLUMN status         TEXT NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    ADD COLUMN content_sha256 TEXT,
    ADD COLUMN created_by_id  UUID,
    ADD COLUMN cancelled_at   TIMESTAMPTZ,
    ADD COLUMN cancel_reason  TEXT,
    ADD COLUMN reminded_at    TIMESTAMPTZ;

-- The versions that exist already are published and in force; their hash is filled so every published text is provable.
UPDATE legal_documents SET content_sha256 = encode(sha256(convert_to(content, 'UTF8')), 'hex') WHERE content_sha256 IS NULL;

-- A type has one draft at a time; the next version number is taken when the draft is created.
CREATE UNIQUE INDEX legal_documents_one_draft ON legal_documents (type, language) WHERE status = 'DRAFT';

-- What administrators did to a document: who, what, when.
CREATE TABLE legal_document_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID        NOT NULL REFERENCES legal_documents (id),
    action      TEXT        NOT NULL CHECK (action IN ('DRAFTED', 'EDITED', 'PUBLISHED', 'CANCELLED')),
    admin_id    UUID        NOT NULL,
    note        TEXT,
    at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX legal_document_log_document_idx ON legal_document_log (document_id, at);

INSERT INTO system_settings (key, value) VALUES ('legal.min_notice_days', '15') ON CONFLICT (key) DO NOTHING;
