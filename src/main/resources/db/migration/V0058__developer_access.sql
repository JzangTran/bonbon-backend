-- The developer portal: people ask the team for access to the API reference; an administrator approves or rejects. An approved
-- person holds the DEVELOPER role (never through self-registration) and its only permission reads the API reference.

alter table identity.user_roles drop constraint if exists user_roles_role_check;
alter table identity.user_roles add constraint user_roles_role_check check (role in ('CUSTOMER', 'SELLER', 'ADMIN', 'DEVELOPER'));
alter table identity.role_permissions drop constraint if exists role_permissions_role_check;
alter table identity.role_permissions add constraint role_permissions_role_check check (role in ('CUSTOMER', 'SELLER', 'ADMIN', 'DEVELOPER'));

insert into identity.role_permissions (role, permission) values
    ('DEVELOPER', 'api-docs:read'),
    ('ADMIN', 'developer-access:decide');

create table identity.developer_access_requests (
    id          uuid         primary key default gen_random_uuid(),
    name        varchar(100) not null,
    email       varchar(254) not null,
    company     varchar(150),
    website     varchar(300),
    purpose     varchar(1000) not null,
    status      varchar(10)  not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    reason      varchar(500),
    user_id     uuid,
    decided_by  uuid,
    decided_at  timestamptz,
    created_at  timestamptz  not null default now()
);
-- one open request per address
create unique index developer_access_one_pending on identity.developer_access_requests (lower(email)) where status = 'PENDING';
create index developer_access_by_status on identity.developer_access_requests (status, created_at desc);
