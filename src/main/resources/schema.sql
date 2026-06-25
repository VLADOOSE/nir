-- DEV ONLY: пересоздаёт все таблицы при каждом запуске

-- Удаление таблиц в обратном порядке зависимостей
DROP TABLE IF EXISTS price_request_item CASCADE;
DROP TABLE IF EXISTS price_request CASCADE;
DROP TABLE IF EXISTS apply_item CASCADE;
DROP TABLE IF EXISTS activity_apply CASCADE;
DROP TABLE IF EXISTS tender_lot CASCADE;
DROP TABLE IF EXISTS tender CASCADE;
DROP TABLE IF EXISTS distributor_equipment_type CASCADE;
DROP TABLE IF EXISTS distributor_brand CASCADE;
DROP TABLE IF EXISTS med_equipment CASCADE;
DROP TABLE IF EXISTS distributor CASCADE;
DROP TABLE IF EXISTS equipment_type CASCADE;
DROP TABLE IF EXISTS facility CASCADE;
DROP TABLE IF EXISTS user_account CASCADE;

-- Удаление старых таблиц
DROP TABLE IF EXISTS med_equipment_offer CASCADE;
DROP TABLE IF EXISTS med_equipment_request CASCADE;
DROP TABLE IF EXISTS company_member CASCADE;
DROP TABLE IF EXISTS company CASCADE;
DROP TABLE IF EXISTS tender_step CASCADE;
DROP TABLE IF EXISTS tender_founder CASCADE;

-- ========== Реестр медизделий (живучая таблица, не пересоздаётся) ==========
-- ВАЖНО: БД должна быть создана с UTF-8 локалью (LC_CTYPE/LC_COLLATE), НЕ 'C'/'POSIX' —
-- иначе pg_trgm даёт пустые триграммы для кириллицы и матчинг молча возвращает 0 кандидатов.
-- Пример: createdb nirdb --template=template0 --lc-ctype=en_US.UTF-8 --lc-collate=en_US.UTF-8
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS med_registry (
    id              BIGSERIAL PRIMARY KEY,
    reg_number      VARCHAR(100) NOT NULL UNIQUE,   -- № РУ (естественный ключ)
    name            TEXT NOT NULL,                   -- наименование МИ
    producer        VARCHAR(500),
    country         VARCHAR(200),
    reg_date        DATE,
    expiration_date DATE,
    unlimited       BOOLEAN DEFAULT FALSE,
    imported_at     TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_reg_name_trgm     ON med_registry USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_reg_producer_trgm ON med_registry USING gin (producer gin_trgm_ops);

-- ========== Справочники ==========

CREATE TABLE equipment_type (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL
);

CREATE TABLE facility (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    inn         VARCHAR(12),
    address     VARCHAR(500),
    last_name   VARCHAR(100),
    first_name  VARCHAR(100),
    middle_name VARCHAR(100),
    phone       VARCHAR(50),
    email       VARCHAR(255),
    market      VARCHAR(2) NOT NULL DEFAULT 'RF'
);
CREATE INDEX IF NOT EXISTS idx_facility_market ON facility(market);

CREATE TABLE distributor (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    inn         VARCHAR(12) UNIQUE,
    address     VARCHAR(500),
    last_name   VARCHAR(100),
    first_name  VARCHAR(100),
    middle_name VARCHAR(100),
    phone       VARCHAR(50),
    email       VARCHAR(255),
    website     VARCHAR(255),
    market      VARCHAR(2) NOT NULL DEFAULT 'RF'
);
CREATE INDEX IF NOT EXISTS idx_distributor_market ON distributor(market);

CREATE TABLE distributor_equipment_type (
    distributor_id    BIGINT NOT NULL REFERENCES distributor(id) ON DELETE CASCADE,
    equipment_type_id BIGINT NOT NULL REFERENCES equipment_type(id) ON DELETE CASCADE,
    PRIMARY KEY (distributor_id, equipment_type_id)
);

CREATE TABLE distributor_brand (
    distributor_id BIGINT NOT NULL REFERENCES distributor(id) ON DELETE CASCADE,
    brand          VARCHAR(255) NOT NULL
);

CREATE TABLE med_equipment (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    manufact      VARCHAR(255) NOT NULL,
    equip_type_id BIGINT REFERENCES equipment_type(id),
    length_mm     INTEGER,
    width_mm      INTEGER,
    height_mm     INTEGER,
    weight_kg     NUMERIC(10, 2),
    spec          TEXT,
    registration_status     VARCHAR(30) NOT NULL DEFAULT 'UNCHECKED',
    med_registry_reg_number VARCHAR(100) REFERENCES med_registry(reg_number),
    registration_checked_at TIMESTAMPTZ,
    market      VARCHAR(2) NOT NULL DEFAULT 'RF',
    CONSTRAINT med_equipment_length_positive CHECK (length_mm IS NULL OR length_mm > 0),
    CONSTRAINT med_equipment_width_positive  CHECK (width_mm  IS NULL OR width_mm  > 0),
    CONSTRAINT med_equipment_height_positive CHECK (height_mm IS NULL OR height_mm > 0),
    CONSTRAINT med_equipment_weight_positive CHECK (weight_kg IS NULL OR weight_kg > 0)
);
CREATE INDEX IF NOT EXISTS idx_med_equipment_market ON med_equipment(market);

CREATE TABLE user_account (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) UNIQUE NOT NULL,
    full_name     VARCHAR(255),
    role          VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL
);

-- ========== Тендеры ==========

CREATE TABLE tender (
    id              BIGSERIAL PRIMARY KEY,
    tender_number   VARCHAR(50) NOT NULL,
    facility_id     BIGINT REFERENCES facility(id),
    status          VARCHAR(50) NOT NULL,
    purchase_type   VARCHAR(50),
    deadline        DATE,
    publish_date    DATE,
    total_cost      NUMERIC(15, 2),
    currency        VARCHAR(10) DEFAULT 'RUB',
    source          VARCHAR(20) NOT NULL DEFAULT 'PUBLIC_TENDER',
    description     TEXT,
    delivery_address TEXT,
    contact_last_name   VARCHAR(100),
    contact_first_name  VARCHAR(100),
    contact_middle_name VARCHAR(100),
    contact_phone       VARCHAR(50),
    contact_email       VARCHAR(255),
    market              VARCHAR(2) NOT NULL DEFAULT 'RF',
    CONSTRAINT tender_total_cost_nonneg CHECK (total_cost IS NULL OR total_cost >= 0)
);
CREATE INDEX IF NOT EXISTS idx_tender_market ON tender(market);

CREATE TABLE tender_lot (
    id            BIGSERIAL PRIMARY KEY,
    tender_id     BIGINT NOT NULL REFERENCES tender(id) ON DELETE CASCADE,
    lot_number    INTEGER,
    equip_name    VARCHAR(255),
    equip_type_id BIGINT REFERENCES equipment_type(id),
    manufact      VARCHAR(255),
    quantity      INTEGER,
    max_cost      NUMERIC(15, 2),
    max_length_mm INTEGER,
    max_width_mm  INTEGER,
    max_height_mm INTEGER,
    max_weight_kg NUMERIC(10, 2),
    required_spec TEXT,
    CONSTRAINT tender_lot_quantity_positive   CHECK (quantity      IS NULL OR quantity      > 0),
    CONSTRAINT tender_lot_max_cost_positive   CHECK (max_cost      IS NULL OR max_cost      > 0),
    CONSTRAINT tender_lot_max_length_positive CHECK (max_length_mm IS NULL OR max_length_mm > 0),
    CONSTRAINT tender_lot_max_width_positive  CHECK (max_width_mm  IS NULL OR max_width_mm  > 0),
    CONSTRAINT tender_lot_max_height_positive CHECK (max_height_mm IS NULL OR max_height_mm > 0),
    CONSTRAINT tender_lot_max_weight_positive CHECK (max_weight_kg IS NULL OR max_weight_kg > 0)
);

-- ========== Заявки ==========

CREATE TABLE activity_apply (
    id                  BIGSERIAL PRIMARY KEY,
    tender_id           BIGINT NOT NULL REFERENCES tender(id),
    status              VARCHAR(50) DEFAULT 'DRAFT',
    created_at          TIMESTAMPTZ DEFAULT now(),
    contract_number     VARCHAR(100),
    contract_signed_at  DATE,
    delivery_status     VARCHAR(50) DEFAULT 'NONE',
    delivered_at        DATE,
    paid_at             DATE,
    market              VARCHAR(2) NOT NULL DEFAULT 'RF'
);
CREATE INDEX IF NOT EXISTS idx_activity_apply_market ON activity_apply(market);

CREATE TABLE apply_item (
    id             BIGSERIAL PRIMARY KEY,
    apply_id       BIGINT NOT NULL REFERENCES activity_apply(id) ON DELETE CASCADE,
    tender_lot_id  BIGINT REFERENCES tender_lot(id),
    med_equip_id   BIGINT REFERENCES med_equipment(id),
    distributor_id BIGINT REFERENCES distributor(id),
    offered_cost   NUMERIC(15, 2),
    quantity       INTEGER,
    CONSTRAINT apply_item_offered_cost_positive CHECK (offered_cost IS NULL OR offered_cost > 0),
    CONSTRAINT apply_item_quantity_positive     CHECK (quantity     IS NULL OR quantity     > 0)
);

-- ========== Запросы КП ==========

CREATE TABLE price_request (
    id             BIGSERIAL PRIMARY KEY,
    tender_id      BIGINT NOT NULL REFERENCES tender(id),
    distributor_id BIGINT NOT NULL REFERENCES distributor(id),
    status         VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    sent_at        TIMESTAMPTZ,
    response_date  DATE,
    note           TEXT,
    created_at     TIMESTAMPTZ DEFAULT now(),
    market         VARCHAR(2) NOT NULL DEFAULT 'RF'
);
CREATE INDEX IF NOT EXISTS idx_price_request_market ON price_request(market);

CREATE TABLE price_request_item (
    id                 BIGSERIAL PRIMARY KEY,
    price_request_id   BIGINT NOT NULL REFERENCES price_request(id) ON DELETE CASCADE,
    tender_lot_id      BIGINT NOT NULL REFERENCES tender_lot(id),
    med_equipment_id   BIGINT REFERENCES med_equipment(id),
    requested_quantity INTEGER NOT NULL,
    response_price     NUMERIC(15, 2),
    response_note      TEXT,
    CONSTRAINT price_request_item_qty_positive    CHECK (requested_quantity > 0),
    CONSTRAINT price_request_item_price_nonneg    CHECK (response_price IS NULL OR response_price >= 0)
);
