-- Reporting content, blocking a conversation, the word list, the shop's auto-reply and the help-center chatbot
-- (flows/reporting/, flows/messaging/send-message.md "Blocking a conversation", configure-auto-reply.md, flows/support/chat-with-chatbot.md).

-- communication: a message can be an auto-reply or be hidden by an administrator after a report
alter table communication.messages add column is_auto_reply boolean not null default false;
alter table communication.messages add column hidden_at timestamptz;
alter table communication.messages add column hidden_reason text check (hidden_reason is null or char_length(hidden_reason) <= 500);

-- an auto-reply was sent for the customer's current unanswered streak; the shop's next real message clears it
alter table communication.conversations add column auto_reply_pending_reset boolean not null default false;

-- either side may close the conversation to the other; history stays readable, ordering is never affected
create table communication.conversation_blocks (
    conversation_id    uuid        not null references communication.conversations (id),
    blocked_by_side    text        not null check (blocked_by_side in ('CUSTOMER', 'SHOP')),
    blocked_by_user_id uuid        not null,
    created_at         timestamptz not null,
    primary key (conversation_id, blocked_by_side)
);

-- an administrator stopped a person from sending messages for a while, after an actioned report
create table communication.messaging_restrictions (
    user_id          uuid        primary key,
    restricted_until timestamptz not null,
    reason           text        not null check (char_length(reason) between 1 and 500),
    report_id        uuid,
    created_by       uuid        not null,
    created_at       timestamptz not null
);

-- words masked as *** in what the other side reads; the original text is kept
create table communication.moderation_terms (
    id         uuid        primary key default gen_random_uuid(),
    term       text        not null check (char_length(term) between 2 and 50),
    active     boolean     not null default true,
    created_by uuid        not null,
    created_at timestamptz not null
);
create unique index moderation_terms_term_key on communication.moderation_terms (lower(term));

-- marketplace: one auto-reply per shop, on by default with a starter message
alter table marketplace.vendors add column auto_reply_enabled boolean not null default true;
alter table marketplace.vendors add column auto_reply_message text not null
    default 'Cảm ơn bạn đã nhắn tin, quán sẽ phản hồi sớm nhất có thể!'
    check (char_length(auto_reply_message) between 1 and 1000);

-- support: a report is a request for review, never a penalty by itself; the evidence is copied when it is filed
create table support.content_reports (
    id                   uuid        primary key default gen_random_uuid(),
    reporter_user_id     uuid        not null,
    reporter_side        text        not null check (reporter_side in ('CUSTOMER', 'SELLER')),
    target_type          text        not null check (target_type in ('SHOP', 'MESSAGE', 'REVIEW', 'REVIEW_RESPONSE')),
    target_id            uuid        not null,
    target_vendor_id     uuid,
    target_owner_user_id uuid,
    reason_code          text        not null check (reason_code in ('SPAM', 'OFFENSIVE', 'SCAM_OR_FRAUD', 'INAPPROPRIATE_IMAGE', 'FALSE_INFORMATION', 'OTHER')),
    note                 text        check (note is null or char_length(note) <= 500),
    evidence             jsonb       not null,
    status               text        not null default 'OPEN' check (status in ('OPEN', 'ACTIONED', 'DISMISSED')),
    decision_code        text        check (decision_code is null or decision_code in
                                           ('NO_VIOLATION', 'DUPLICATE', 'CONTENT_HIDDEN', 'MESSAGING_RESTRICTED', 'ESCALATED')),
    decision_reason      text        check (decision_reason is null or char_length(decision_reason) <= 500),
    decided_by           uuid,
    decided_at           timestamptz,
    contested_at         timestamptz,
    contest_note         text        check (contest_note is null or char_length(contest_note) <= 500),
    created_at           timestamptz not null
);
-- one open report per person and target: a second one returns the first, so one person cannot inflate the count
create unique index content_reports_one_open on support.content_reports (reporter_user_id, target_type, target_id) where status = 'OPEN';
create index content_reports_queue_idx on support.content_reports (status, created_at);
create index content_reports_target_idx on support.content_reports (target_type, target_id);

-- every chatbot question, matched or not, so an administrator can write the articles people are missing
create table support.help_chat_log (
    id                 uuid        primary key default gen_random_uuid(),
    question           text        not null check (char_length(question) between 1 and 300),
    matched_article_id uuid        references support.help_articles (id) on delete set null,
    score              integer     not null,
    audience           text        not null check (audience in ('ALL', 'CUSTOMER', 'SELLER')),
    asked_by           uuid,
    created_at         timestamptz not null
);
create index help_chat_log_unanswered_idx on support.help_chat_log (created_at desc) where matched_article_id is null;
