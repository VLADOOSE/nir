-- Чаты WhatsApp рабочего номера через Green-API
-- (спека docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md §4).
-- Сообщение хранится ОДИН раз — в своём чате; обращение ссылается на чат (lead.chat_id).

CREATE TABLE chat (
    id                   BIGSERIAL PRIMARY KEY,
    market               VARCHAR(2)   NOT NULL,
    channel              VARCHAR(20)  NOT NULL,               -- WHATSAPP
    account              VARCHAR(40)  NOT NULL,               -- номер подключённого аккаунта: wid без «@c.us»
    external_chat_id     VARCHAR(100) NOT NULL,               -- chatId Green-API: 7701…@c.us / …@g.us / …@lid
    is_group             BOOLEAN      NOT NULL DEFAULT false,
    title                VARCHAR(255),
    phone_norm           VARCHAR(20),                         -- только у @c.us; ключ склейки с обращениями
    not_client           BOOLEAN      NOT NULL DEFAULT false, -- «не клиент»: обращения не создаются
    last_message_at      TIMESTAMPTZ,
    last_message_preview VARCHAR(300),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_ext   ON chat (channel, account, external_chat_id);
CREATE INDEX idx_chat_market_last ON chat (market, last_message_at DESC);
CREATE INDEX idx_chat_phone       ON chat (market, phone_norm) WHERE phone_norm IS NOT NULL;

CREATE TABLE chat_message (
    id           BIGSERIAL PRIMARY KEY,
    chat_id      BIGINT       NOT NULL REFERENCES chat(id) ON DELETE CASCADE,
    external_id  VARCHAR(100) NOT NULL,                       -- idMessage
    direction    VARCHAR(3)   NOT NULL,                       -- IN / OUT
    sender_name  VARCHAR(255),
    type         VARCHAR(20)  NOT NULL,
    body         TEXT,
    sent_at      TIMESTAMPTZ  NOT NULL,
    edited       BOOLEAN      NOT NULL DEFAULT false,
    deleted      BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_message_ext ON chat_message (chat_id, external_id);
CREATE INDEX idx_chat_message_chat_time ON chat_message (chat_id, sent_at DESC, id DESC);

CREATE TABLE chat_attachment (
    id                BIGSERIAL PRIMARY KEY,
    message_id        BIGINT NOT NULL UNIQUE REFERENCES chat_message(id) ON DELETE CASCADE,
    file_name         VARCHAR(255),
    mime_type         VARCHAR(100),
    size_bytes        BIGINT,
    content           BYTEA,                                  -- NULL ⇔ not_stored_reason IS NOT NULL
    not_stored_reason VARCHAR(20)                             -- TOO_LARGE / GROUP / DOWNLOAD_FAILED
);

ALTER TABLE lead ADD COLUMN chat_id BIGINT REFERENCES chat(id) ON DELETE SET NULL;
CREATE INDEX idx_lead_chat ON lead (chat_id) WHERE chat_id IS NOT NULL;
