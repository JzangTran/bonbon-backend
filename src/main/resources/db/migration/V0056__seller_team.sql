-- Seller team (flows/seller-team): a main account manages several shops it has linked, and its members work the shops it assigns them.
-- A shop account stays a User with the SELLER role; main accounts and members are their own principals with their own passwords.

create table identity.main_accounts (
    id                 uuid        primary key default gen_random_uuid(),
    username           varchar(40) not null unique,          -- without the ':main' suffix; immutable, it prefixes every member login
    account_name       varchar(100) not null,
    region             varchar(60),
    email              varchar(254) not null,
    phone              varchar(20),
    nickname           varchar(60),
    password_hash      text        not null,
    created_by_user_id uuid        not null,                 -- the shop account that registered it
    access_version     integer     not null default 0,   -- bumped when access shrinks; tokens carry the version they were issued at
    created_at         timestamptz not null default now(),
    constraint main_accounts_username_format check (username ~ '^[a-z0-9._-]{3,40}$')
);
create unique index main_accounts_email_unique on identity.main_accounts (lower(email));

-- Two-party link: the main account asks with a code mailed to it, the shop account authorizes from its own session.
create table identity.main_account_shop_links (
    id              uuid        primary key default gen_random_uuid(),
    main_account_id uuid        not null references identity.main_accounts (id) on delete cascade,
    vendor_id       uuid        not null,
    owner_user_id   uuid        not null,                    -- the shop account that answers
    status          varchar(24) not null check (status in ('PENDING_AUTHORIZATION', 'AUTHORIZED', 'DECLINED', 'CANCELLED', 'EXPIRED', 'UNLINKED')),
    requested_at    timestamptz not null default now(),
    expires_at      timestamptz not null,
    decided_at      timestamptz,
    ended_at        timestamptz
);
-- one shop answers to one main account at a time
create unique index shop_links_one_active on identity.main_account_shop_links (vendor_id) where status in ('PENDING_AUTHORIZATION', 'AUTHORIZED');
create index shop_links_by_main on identity.main_account_shop_links (main_account_id);

-- Named reusable roles (is_template) and the unnamed permission sets ticked while assigning (not listed).
create table identity.seller_roles (
    id              uuid        primary key default gen_random_uuid(),
    main_account_id uuid        not null references identity.main_accounts (id) on delete cascade,
    name            varchar(60),
    description     varchar(300),
    permissions     text[]      not null default '{}',
    is_template     boolean     not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);
create unique index seller_roles_name_unique on identity.seller_roles (main_account_id, lower(name)) where is_template;

create table identity.team_members (
    id                 uuid        primary key default gen_random_uuid(),
    main_account_id    uuid        not null references identity.main_accounts (id) on delete cascade,
    login_code         varchar(40) not null,                 -- immutable; the full login is '<username>:<login_code>'
    member_name        varchar(100) not null,
    phone              varchar(20),
    email              varchar(254),
    password_hash      text        not null,
    status             varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    access_version     integer     not null default 0,   -- bumped when access shrinks; tokens carry the version they were issued at
    created_at         timestamptz not null default now(),
    unique (main_account_id, login_code),
    constraint team_members_code_format check (login_code ~ '^[a-z0-9._-]{2,40}$' and login_code <> 'main')
);

create table identity.shop_assignments (
    id         uuid        primary key default gen_random_uuid(),
    member_id  uuid        not null references identity.team_members (id) on delete cascade,
    vendor_id  uuid        not null,
    role_id    uuid        not null references identity.seller_roles (id),
    assigned_at timestamptz not null default now(),
    removed_at timestamptz,
    unique (member_id, vendor_id)
);

-- Sessions of main accounts and members, rotated like the users' refresh tokens.
create table identity.team_refresh_tokens (
    id           uuid        primary key default gen_random_uuid(),
    principal_id uuid        not null,
    kind         varchar(12) not null check (kind in ('MAIN_ACCOUNT', 'MEMBER')),
    vendor_id    uuid,
    token_hash   text        not null unique,
    family_id    uuid        not null,
    auth_time    timestamptz not null,
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    created_at   timestamptz not null default now()
);
create index team_refresh_tokens_family on identity.team_refresh_tokens (family_id);
