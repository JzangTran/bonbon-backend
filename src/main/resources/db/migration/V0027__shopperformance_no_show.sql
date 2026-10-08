-- No-show at the door (flows/order-fulfillment/report-customer-no-show.md, flows/order/answer-no-show-report.md): the shop
-- says the customer was not there, the customer answers, an administrator decides when they disagree.
ALTER TABLE order_cases
    ADD COLUMN customer_answer         TEXT CHECK (customer_answer IN ('UNABLE', 'RECEIVED', 'SHOP_NEVER_CAME')),
    ADD COLUMN customer_answer_note    TEXT CHECK (char_length(customer_answer_note) <= 500),
    ADD COLUMN customer_answer_due_at  TIMESTAMPTZ,
    ADD COLUMN customer_answered_at    TIMESTAMPTZ,
    -- how a no-show ended; the shop performance job reads SHOP_NEVER_CAME as a fault of the shop
    ADD COLUMN no_show_outcome         TEXT CHECK (no_show_outcome IN ('CUSTOMER_AT_FAULT', 'CUSTOMER_RECEIVED', 'SHOP_NEVER_CAME'));
CREATE INDEX order_cases_customer_due_idx ON order_cases (customer_answer_due_at) WHERE status = 'AWAITING_CUSTOMER';
