-- Очередь событий шлюза WhatsApp WAHA (спека docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md §4–5).
-- Вебхук только кладёт сырое событие сюда (INSERT … ON CONFLICT DO NOTHING) и сразу отвечает 200; разбирает общий
-- цикл приёма (поток whatsapp-chats) по (event_at, id). Таблица не рыночная: рынок ставится при записи чата.
CREATE TABLE whatsapp_inbox (
    id            BIGSERIAL PRIMARY KEY,
    provider      VARCHAR(20)  NOT NULL,                     -- 'waha'
    request_id    VARCHAR(100),                              -- X-Webhook-Request-Id; у догонки NULL
    message_key   VARCHAR(200),                              -- 'fromMe_rawId' у message.any (вебхук И догонка); у прочих NULL
    event         VARCHAR(40)  NOT NULL,
    event_at      TIMESTAMPTZ  NOT NULL,                     -- payload.timestamp — порядок обработки
    payload       TEXT         NOT NULL,                     -- сырой JSON события целиком
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING',   -- PENDING / DONE / DROPPED
    last_error    VARCHAR(500),
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_whatsapp_inbox_request ON whatsapp_inbox (provider, request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_whatsapp_inbox_message ON whatsapp_inbox (provider, message_key) WHERE message_key IS NOT NULL;
CREATE INDEX idx_whatsapp_inbox_pending ON whatsapp_inbox (event_at, id) WHERE status = 'PENDING';
