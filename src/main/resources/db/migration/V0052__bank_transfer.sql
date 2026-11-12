-- Bank transfer (VietQR): the customer pays the shop's own bank account; the shop marks the money received.
alter table ordering.orders drop constraint if exists orders_payment_method_check;
alter table ordering.orders add constraint orders_payment_method_check check (payment_method in ('COD', 'ONLINE', 'BANK_TRANSFER'));
alter table ordering.orders add column payment_received_at timestamptz;
