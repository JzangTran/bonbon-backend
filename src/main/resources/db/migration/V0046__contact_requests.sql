-- Contact requests: the public "Liên hệ hỗ trợ" form on the help site. Anyone may write, signed in or not; the email they give is
-- where the answer goes, so nothing here points at an account. A request is OPEN until an administrator answers (ANSWERED); the
-- answers are kept in order so a second answer never overwrites the first. CLOSED when there is nothing more to do.
CREATE TABLE contact_requests (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name   TEXT        NOT NULL CHECK (char_length(full_name) BETWEEN 1 AND 100),
    email       TEXT        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 255),
    -- who they say they are: someone who orders, a seller, or anyone else
    role        TEXT        NOT NULL CHECK (role IN ('CUSTOMER', 'SELLER', 'OTHER')),
    topic       TEXT        NOT NULL CHECK (topic IN ('ORDER', 'ACCOUNT', 'SHOP', 'PAYMENT', 'OTHER')),
    message     TEXT        NOT NULL CHECK (char_length(message) BETWEEN 1 AND 2000),
    status      TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ANSWERED', 'CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    closed_at   TIMESTAMPTZ
);
CREATE INDEX contact_requests_inbox_idx ON contact_requests (status, created_at);

CREATE TABLE contact_request_answers (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  UUID        NOT NULL REFERENCES contact_requests (id),
    admin_id    UUID        NOT NULL REFERENCES users (id),
    body        TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX contact_request_answers_request_idx ON contact_request_answers (request_id, created_at);
