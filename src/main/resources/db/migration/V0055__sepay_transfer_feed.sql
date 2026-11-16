-- Automatic bank-transfer confirmation through SePay, one connection per shop (confirm-bank-transfer.md).
-- The shop links its own bank account in SePay and points SePay's webhook at bonbon with a secret bonbon generated;
-- only the SHA-256 of that secret is kept.
create table payments.sepay_connections (
    vendor_id     uuid primary key,
    secret_hash   varchar(64) not null,
    created_at    timestamptz not null default now(),
    last_event_at timestamptz
);

-- Every incoming transfer SePay reported, once: SePay retries until it gets a success, the id keeps it idempotent.
create table payments.sepay_events (
    vendor_id    uuid        not null,
    sepay_id     bigint      not null,
    amount       bigint      not null,
    content      varchar(500),
    account_number varchar(40),
    order_number bigint,
    outcome      varchar(24) not null check (outcome in ('PAID', 'NOT_FOUND', 'NOT_A_BANK_TRANSFER', 'ALREADY_PAID', 'AMOUNT_SHORT', 'NO_CODE', 'OUTGOING')),
    received_at  timestamptz not null default now(),
    primary key (vendor_id, sepay_id)
);
create index sepay_events_recent on payments.sepay_events (vendor_id, received_at desc);
