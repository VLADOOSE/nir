-- Обращения (коммерческое направление West-Med): заявки с сайта westmed.kz, звонки, WhatsApp.
-- Спека: docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md §4.
CREATE TABLE lead (
    id                  BIGSERIAL PRIMARY KEY,
    market              VARCHAR(2)   NOT NULL,
    channel             VARCHAR(20)  NOT NULL,   -- SITE / PHONE / WHATSAPP / EMAIL / OTHER
    source              VARCHAR(40)  NOT NULL,   -- 'westmed.kz' | 'manual' | позже 'vital-spb.kz', 'whatsapp', 'pbx'
    external_id         VARCHAR(100),            -- id у источника; westmed: 'price:<uuid>' | 'quote:<uuid>'
    subject             VARCHAR(200) NOT NULL,
    contact_name        VARCHAR(255),
    contact_phone       VARCHAR(50),             -- как пришло (для показа)
    phone_norm          VARCHAR(20),             -- +7XXXXXXXXXX — ключ сопоставления каналов
    contact_email       VARCHAR(255),
    company             VARCHAR(255),
    message             TEXT,
    facility_id         BIGINT REFERENCES facility(id) ON DELETE SET NULL,
    status              VARCHAR(20)  NOT NULL,   -- NEW / IN_WORK / CONVERTED / CLOSED
    close_reason        VARCHAR(30),
    private_request_id  BIGINT REFERENCES tender(id) ON DELETE SET NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ext_status          VARCHAR(20),             -- статус у источника, как его знает АИС
    ext_status_pending  VARCHAR(20),             -- что надо записать в источник; NULL = нечего
    ext_sync_error      VARCHAR(500)
);
CREATE UNIQUE INDEX uq_lead_source_ext  ON lead (source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_lead_market_status     ON lead (market, status, received_at DESC);
CREATE INDEX idx_lead_phone             ON lead (market, phone_norm) WHERE phone_norm IS NOT NULL;
CREATE INDEX idx_lead_ext_pending       ON lead (source) WHERE ext_status_pending IS NOT NULL;
CREATE INDEX idx_lead_private_request   ON lead (private_request_id) WHERE private_request_id IS NOT NULL;

CREATE TABLE lead_item (
    id           BIGSERIAL PRIMARY KEY,
    lead_id      BIGINT NOT NULL REFERENCES lead(id) ON DELETE CASCADE,
    line_no      INT    NOT NULL,
    name         VARCHAR(500) NOT NULL,
    brand        VARCHAR(255),
    quantity     INT    NOT NULL DEFAULT 1,
    product_url  VARCHAR(500)
);
CREATE INDEX idx_lead_item_lead ON lead_item (lead_id);

CREATE TABLE lead_event (
    id           BIGSERIAL PRIMARY KEY,
    lead_id      BIGINT NOT NULL REFERENCES lead(id) ON DELETE CASCADE,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    type         VARCHAR(20) NOT NULL,   -- RECEIVED / NOTE / CALL / MESSAGE / STATUS / SYNC
    direction    VARCHAR(3),             -- IN / OUT — звонки и сообщения
    channel      VARCHAR(20),
    author       VARCHAR(100),           -- логин пользователя АИС; NULL = система
    body         TEXT,
    external_id  VARCHAR(100)            -- id сообщения/звонка у провайдера (будущие каналы)
);
CREATE INDEX idx_lead_event_lead ON lead_event (lead_id, occurred_at);
CREATE UNIQUE INDEX uq_lead_event_ext ON lead_event (channel, external_id) WHERE external_id IS NOT NULL;
