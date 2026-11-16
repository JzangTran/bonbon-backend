-- Data-subject requests (flows/legal/data-subject-requests.md): every request a person makes about their personal data, self-service or
-- handled by an administrator, kept in one place with its deadlines.
create table identity.data_subject_requests (
    id               uuid        primary key default gen_random_uuid(),
    user_id          uuid        not null references identity.users (id),
    audience         text        not null check (audience in ('CUSTOMER', 'SELLER')),
    type             text        not null check (type in ('ACCESS', 'CORRECT', 'WITHDRAW', 'RESTRICT', 'OBJECT', 'DELETE')),
    details          text        check (details is null or char_length(details) <= 2000),
    status           text        not null default 'RECEIVED' check (status in ('RECEIVED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED')),
    received_at      timestamptz not null,
    ack_due_at       timestamptz not null,
    due_at           timestamptz not null,
    extended         boolean     not null default false,
    extension_reason text        check (extension_reason is null or char_length(extension_reason) <= 500),
    handled_by       uuid,
    response_note    text        check (response_note is null or char_length(response_note) <= 2000),
    closed_at        timestamptz,
    export_key       text
);
create index data_subject_requests_user_idx on identity.data_subject_requests (user_id, received_at desc);
create index data_subject_requests_queue_idx on identity.data_subject_requests (status, due_at) where status in ('RECEIVED', 'IN_PROGRESS');
