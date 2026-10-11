-- Support tickets: talking to a person (flows/support/support-tickets.md). A ticket belongs to the account that opened it, as a
-- customer or as a seller (audience), and may point at one of its own orders. The order number is copied so a list never needs the
-- order. A ticket is OPEN while the person waits for an answer and ANSWERED while the administrators wait for the person.
CREATE TABLE support_tickets (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id),
    audience       TEXT        NOT NULL CHECK (audience IN ('CUSTOMER', 'SHOP')),
    order_id       UUID        REFERENCES orders (id),
    order_number   BIGINT,
    subject        TEXT        NOT NULL CHECK (char_length(subject) BETWEEN 1 AND 150),
    status         TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ANSWERED', 'CLOSED')),
    created_at     TIMESTAMPTZ NOT NULL,
    -- the time of the last message or status change; the inbox sorts on it and the auto-close counts from it
    updated_at     TIMESTAMPTZ NOT NULL,
    closed_at      TIMESTAMPTZ,
    -- USER, ADMIN or SYSTEM (the automatic close after the quiet period)
    closed_by_type TEXT        CHECK (closed_by_type IN ('USER', 'ADMIN', 'SYSTEM'))
);
CREATE INDEX support_tickets_user_idx ON support_tickets (user_id, updated_at DESC);
CREATE INDEX support_tickets_inbox_idx ON support_tickets (status, updated_at);

CREATE TABLE support_ticket_messages (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       UUID        NOT NULL REFERENCES support_tickets (id),
    author_type     TEXT        NOT NULL CHECK (author_type IN ('USER', 'ADMIN')),
    author_id       UUID        NOT NULL REFERENCES users (id),
    body            TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    attachment_keys TEXT[]      NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX support_ticket_messages_ticket_idx ON support_ticket_messages (ticket_id, created_at, id);
