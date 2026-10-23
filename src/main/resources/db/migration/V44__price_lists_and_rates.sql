-- Прайс-листы поставщиков и курсы валют (спека 2026-10-23-price-lists-and-rates §8): наш курс и курс НБ РК, прайсы и их
-- строки, файлы писем «прочее» на zakup@, исходная валюта у цены ответа и у закупки строки КП, роль колонки «РУ».
CREATE TABLE currency_rate (
    id         BIGSERIAL PRIMARY KEY,
    market     VARCHAR(2)     NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    our_rate   NUMERIC(14, 6) NOT NULL,
    updated_by VARCHAR(100),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_currency_rate UNIQUE (market, currency)
);

CREATE TABLE official_rate (
    id         BIGSERIAL PRIMARY KEY,
    source     VARCHAR(10)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    rate_date  DATE           NOT NULL,
    rate       NUMERIC(14, 6) NOT NULL,
    fetched_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_official_rate UNIQUE (source, currency, rate_date)
);

CREATE TABLE price_list (
    id               BIGSERIAL PRIMARY KEY,
    market           VARCHAR(2)    NOT NULL,
    distributor_id   BIGINT        NOT NULL REFERENCES distributor (id) ON DELETE CASCADE,
    status           VARCHAR(10)   NOT NULL DEFAULT 'DRAFT',
    list_date        DATE,
    valid_to         DATE,
    currency         VARCHAR(5),
    retail           BOOLEAN       NOT NULL DEFAULT FALSE,
    price_columns    JSONB         NOT NULL DEFAULT '[]',
    our_column       INT,
    discount_pct     NUMERIC(5, 2) NOT NULL DEFAULT 0,
    terms            VARCHAR(1000),
    file_name        VARCHAR(255)  NOT NULL,
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    sha256           VARCHAR(64)   NOT NULL,
    data             BYTEA,
    kind             VARCHAR(10)   NOT NULL,
    parse_status     VARCHAR(20)   NOT NULL,
    parse_note       VARCHAR(300),
    inbound_email_id BIGINT REFERENCES inbound_email (id) ON DELETE SET NULL,
    created_by       VARCHAR(100),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    activated_by     VARCHAR(100),
    activated_at     TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    CONSTRAINT uq_price_list_file UNIQUE (distributor_id, sha256)
);
CREATE INDEX idx_price_list_market_status ON price_list (market, status);

CREATE TABLE price_list_item (
    id               BIGSERIAL PRIMARY KEY,
    price_list_id    BIGINT        NOT NULL REFERENCES price_list (id) ON DELETE CASCADE,
    line_no          INT           NOT NULL,
    section          VARCHAR(255),
    name             VARCHAR(1000) NOT NULL,
    model            VARCHAR(100),
    article          VARCHAR(100),
    prices           JSONB         NOT NULL DEFAULT '[]',
    our_price        NUMERIC(15, 2),
    reg_text         VARCHAR(500),
    reg_number       VARCHAR(100),
    no_reg           BOOLEAN       NOT NULL DEFAULT FALSE,
    site_product_id  BIGINT REFERENCES site_product (id) ON DELETE SET NULL,
    med_equipment_id BIGINT REFERENCES med_equipment (id) ON DELETE SET NULL,
    model_key        VARCHAR(100),
    file_row         INT,
    note             VARCHAR(500)
);
CREATE INDEX idx_price_list_item_list ON price_list_item (price_list_id);
CREATE INDEX idx_price_list_item_key ON price_list_item (model_key) WHERE model_key IS NOT NULL;
CREATE INDEX idx_price_list_item_site ON price_list_item (site_product_id) WHERE site_product_id IS NOT NULL;
CREATE INDEX idx_price_list_item_equipment ON price_list_item (med_equipment_id) WHERE med_equipment_id IS NOT NULL;

CREATE TABLE inbound_email_file (
    id               BIGSERIAL PRIMARY KEY,
    inbound_email_id BIGINT       NOT NULL REFERENCES inbound_email (id) ON DELETE CASCADE,
    file_name        VARCHAR(255) NOT NULL,
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    sha256           VARCHAR(64)  NOT NULL,
    data             BYTEA        NOT NULL,
    price_like       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_inbound_email_file_email ON inbound_email_file (inbound_email_id);

ALTER TABLE price_request_item ADD COLUMN response_currency VARCHAR(5);
ALTER TABLE price_request_item ADD COLUMN response_original NUMERIC(15, 2);
ALTER TABLE price_request_item ADD COLUMN response_rate NUMERIC(14, 6);

ALTER TABLE client_offer_item ADD COLUMN purchase_currency VARCHAR(5);
ALTER TABLE client_offer_item ADD COLUMN purchase_original NUMERIC(15, 2);
ALTER TABLE client_offer_item ADD COLUMN purchase_rate NUMERIC(14, 6);
ALTER TABLE client_offer_item ADD COLUMN price_list_item_id BIGINT REFERENCES price_list_item (id) ON DELETE SET NULL;

-- роль «РУ» (колонка «Разрешительные документы в Казахстане» прайса Аксиона и «Регистрационное удостоверение» КП поставщиков)
INSERT INTO header_synonym (header_norm, field) VALUES
    ('ру', 'REG'), ('№ ру', 'REG'), ('рег. удостоверение', 'REG'), ('регистрационное удостоверение', 'REG'),
    ('разрешительные документы', 'REG'), ('разрешительные документы в казахстане', 'REG'), ('регистрация в рк', 'REG')
ON CONFLICT (header_norm) DO NOTHING;
