-- Reviews of delivered orders, the shop's reply and the admin moderation trail
-- (flows/order/review-order.md, order-fulfillment/respond-to-review.md, order/moderate-review.md).
-- The shop's average is kept on the vendor (sum and count) so browsing never aggregates reviews.
ALTER TABLE vendors
    ADD COLUMN rating_sum   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE reviews (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID        NOT NULL UNIQUE REFERENCES orders (id),
    vendor_id     UUID        NOT NULL REFERENCES vendors (id),
    customer_id   UUID        NOT NULL REFERENCES users (id),
    rating        INTEGER     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment       TEXT        CHECK (char_length(comment) <= 1000),
    reviewer_name TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    hidden_at     TIMESTAMPTZ,
    hidden_reason TEXT,
    hidden_by     UUID
);
CREATE INDEX reviews_vendor_idx ON reviews (vendor_id, created_at DESC) WHERE hidden_at IS NULL;
CREATE INDEX reviews_hidden_idx ON reviews (hidden_at) WHERE hidden_at IS NOT NULL;

CREATE TABLE review_responses (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id     UUID        NOT NULL UNIQUE REFERENCES reviews (id) ON DELETE CASCADE,
    text          TEXT        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 1000),
    acted_by_type TEXT        NOT NULL,
    acted_by_id   UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    hidden_at     TIMESTAMPTZ,
    hidden_reason TEXT,
    hidden_by     UUID
);

-- Every hide and unhide, with who and why; the rows above only keep the current state.
CREATE TABLE review_moderation_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type TEXT        NOT NULL CHECK (target_type IN ('REVIEW', 'RESPONSE')),
    target_id   UUID        NOT NULL,
    action      TEXT        NOT NULL CHECK (action IN ('HIDE', 'UNHIDE')),
    reason      TEXT,
    admin_id    UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX review_moderation_log_target_idx ON review_moderation_log (target_type, target_id, created_at);
