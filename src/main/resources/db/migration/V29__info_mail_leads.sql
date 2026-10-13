-- Письма клиентов на info@westmed.kz → «Обращения» (спека docs/superpowers/specs/2026-10-13-info-mail-leads-design.md §5).

-- Письмо в переписке: тема. У сообщений WhatsApp — NULL.
ALTER TABLE chat_message ADD COLUMN subject TEXT;

-- Почтовая переписка: аккаунт — адрес ящика, собеседник — адрес почты (длиннее номеров WhatsApp).
ALTER TABLE chat ALTER COLUMN account TYPE VARCHAR(320);
ALTER TABLE chat ALTER COLUMN external_chat_id TYPE VARCHAR(320);

-- У письма может быть несколько файлов: «один файл на сообщение» снимается (у WhatsApp он и так один).
ALTER TABLE chat_attachment DROP CONSTRAINT chat_attachment_message_id_key;
CREATE INDEX idx_chat_attachment_message ON chat_attachment (message_id);

-- Курсор почты — на ящик и папку: у info@ читаются «Входящие» и «Отправленные». Прежняя строка zakup@ — «Входящие».
ALTER TABLE mail_cursor ADD COLUMN folder VARCHAR(20) NOT NULL DEFAULT 'INBOX';
ALTER TABLE mail_cursor DROP CONSTRAINT mail_cursor_pkey;
ALTER TABLE mail_cursor ADD PRIMARY KEY (mailbox, folder);
