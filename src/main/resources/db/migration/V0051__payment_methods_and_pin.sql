-- The ways a customer has linked to pay (Cài đặt thanh toán) and the PIN that guards them. Only the last four digits of an
-- account are kept: enough to recognise it, nothing to steal.
create table payments.payment_methods (
    id            uuid primary key,
    customer_id   uuid        not null,
    provider      varchar(20) not null,
    account_last4 varchar(4)  not null,
    is_primary    boolean     not null default false,
    created_at    timestamptz not null default now(),
    unique (customer_id, provider)
);
create unique index payment_methods_one_primary on payments.payment_methods (customer_id) where is_primary;

create table payments.payment_pins (
    customer_id     uuid primary key,
    pin_hash        varchar(100) not null,
    failed_attempts int          not null default 0,
    locked_until    timestamptz,
    updated_at      timestamptz  not null default now()
);
