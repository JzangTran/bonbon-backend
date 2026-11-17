-- Selling-day helpers from the UI/UX benchmark (docs/explanation/ux-benchmark.md):
-- a dish sold out "for today" comes back by itself, a pause in taking orders ends by itself, and a shop keeps quick replies for chat.

alter table marketplace.menu_items add column sold_out_until timestamptz;
create index menu_items_sold_out_until on marketplace.menu_items (sold_out_until) where sold_out_until is not null;

alter table marketplace.vendors add column paused_until timestamptz;
alter table marketplace.vendors add column pause_reason varchar(100);

create table marketplace.quick_replies (
    id         uuid        primary key default gen_random_uuid(),
    vendor_id  uuid        not null references marketplace.vendors (id) on delete cascade,
    shortcut   varchar(20) not null,
    text       varchar(1000) not null,
    sort_order integer     not null default 0,
    created_at timestamptz not null default now(),
    constraint quick_replies_shortcut_format check (shortcut ~ '^[a-z0-9-]{1,20}$')
);
create unique index quick_replies_shortcut_unique on marketplace.quick_replies (vendor_id, shortcut);

alter table marketplace.vendors add column quick_replies_seeded boolean not null default false;
