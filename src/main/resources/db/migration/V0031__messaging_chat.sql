-- Chat between a customer and a shop (flows/messaging/send-message.md, view-conversation-list.md).
-- One conversation per customer and shop, created by the first message from either side. Unread is kept per side, not per
-- person: several people can share the shop side. A message keeps who sent it for audit, but the customer only ever sees "the shop".
CREATE TABLE conversations (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id            UUID        NOT NULL REFERENCES users (id),
    vendor_id              UUID        NOT NULL REFERENCES vendors (id),
    created_at             TIMESTAMPTZ NOT NULL,
    last_message_at        TIMESTAMPTZ NOT NULL,
    customer_last_read_at  TIMESTAMPTZ,
    shop_last_read_at      TIMESTAMPTZ,
    CONSTRAINT conversations_customer_vendor_key UNIQUE (customer_id, vendor_id)
);
CREATE INDEX conversations_vendor_idx ON conversations (vendor_id, last_message_at DESC);
CREATE INDEX conversations_customer_idx ON conversations (customer_id, last_message_at DESC);

CREATE TABLE messages (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID        NOT NULL REFERENCES conversations (id),
    sender_type         TEXT        NOT NULL CHECK (sender_type IN ('CUSTOMER', 'SHOP')),
    sender_id           UUID        NOT NULL,
    text                TEXT,
    image_key           TEXT,
    reply_to_message_id UUID        REFERENCES messages (id),
    created_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT messages_has_content CHECK (text IS NOT NULL OR image_key IS NOT NULL)
);
CREATE INDEX messages_conversation_idx ON messages (conversation_id, created_at DESC, id DESC);
