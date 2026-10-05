-- Почта zakup@ → Telegram «Заявки» (спека docs/superpowers/specs/2026-10-05-zakup-mail-telegram-design.md §7).

-- Курсор приёма: последнее обработанное письмо ящика (UID IMAP). Не рыночная таблица — ключ ящик.
CREATE TABLE mail_cursor (
    mailbox      VARCHAR(320) PRIMARY KEY,
    uid_validity BIGINT       NOT NULL,
    last_uid     BIGINT       NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Откуда письмо и очередь уведомления в Telegram: готовый текст пишется вместе с письмом.
ALTER TABLE inbound_email
    ADD COLUMN mailbox          VARCHAR(320),
    ADD COLUMN imap_uid         BIGINT,
    ADD COLUMN message_id       VARCHAR(998),
    ADD COLUMN notify_status    VARCHAR(10),
    ADD COLUMN notify_text      TEXT,
    ADD COLUMN notify_silent    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notify_queued_at TIMESTAMPTZ,
    ADD COLUMN notify_attempts  INT     NOT NULL DEFAULT 0,
    ADD COLUMN notify_error     VARCHAR(300),
    ADD COLUMN notified_at      TIMESTAMPTZ;

CREATE INDEX idx_inbound_email_notify_pending ON inbound_email (id) WHERE notify_status = 'PENDING';
CREATE INDEX idx_inbound_email_message_id ON inbound_email (mailbox, message_id) WHERE message_id IS NOT NULL;
