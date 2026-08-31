-- Runtime allow-list: which Telegram users may use the bot. Managed at runtime (bootstrap via /claim,
-- then admins approve access requests) so adding a person needs no restart. Empty table = nobody allowed.

create table allowed_user (
    telegram_user_id bigint primary key,
    admin            boolean not null default false,
    display_name     varchar(255),
    added_at         timestamp(6) with time zone not null
);
