-- «Наш тендер — с телефона за минуту» (спека 2026-10-21-tender-telegram-bot §9): Telegram ID учётки, автор запроса цен,
-- вывод ИИ у лота, сообщения-пачки и черновики писем из бота.
ALTER TABLE user_account ADD COLUMN telegram_user_id BIGINT UNIQUE;
ALTER TABLE price_request ADD COLUMN created_by VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN ai_verdict VARCHAR(10);
ALTER TABLE tender_lot ADD COLUMN ai_product_id BIGINT REFERENCES site_product (id) ON DELETE SET NULL;
ALTER TABLE tender_lot ADD COLUMN ai_note VARCHAR(500);
ALTER TABLE tender_lot ADD COLUMN ai_by VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN ai_at TIMESTAMPTZ;
ALTER TABLE tender_lot ADD COLUMN model_by_ai BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE product_digest (
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    message_id BIGINT,
    tender_ids JSONB       NOT NULL
);
CREATE INDEX idx_product_digest_message ON product_digest (message_id);

CREATE TABLE bot_letter (
    id                 BIGSERIAL PRIMARY KEY,
    tender_id          BIGINT      NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    distributor_id     BIGINT      NOT NULL REFERENCES distributor (id) ON DELETE CASCADE,
    items              JSONB       NOT NULL,
    status             VARCHAR(12) NOT NULL,
    price_request_id   BIGINT REFERENCES price_request (id) ON DELETE SET NULL,
    list_message_id    BIGINT,
    preview_message_id BIGINT,
    line_no            INT         NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(100),
    sent_at            TIMESTAMPTZ,
    sent_by            VARCHAR(100),
    error              VARCHAR(300)
);
CREATE INDEX idx_bot_letter_list ON bot_letter (list_message_id);
