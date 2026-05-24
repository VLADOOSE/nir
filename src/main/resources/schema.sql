-- DEV ONLY: пересоздаёт все таблицы при каждом запуске

-- Удаление таблиц в обратном порядке зависимостей
DROP TABLE IF EXISTS price_request CASCADE;
DROP TABLE IF EXISTS apply_item CASCADE;
DROP TABLE IF EXISTS activity_apply CASCADE;
DROP TABLE IF EXISTS tender_lot CASCADE;
DROP TABLE IF EXISTS tender CASCADE;
DROP TABLE IF EXISTS med_equipment CASCADE;
DROP TABLE IF EXISTS distributor CASCADE;
DROP TABLE IF EXISTS facility CASCADE;
DROP TABLE IF EXISTS user_account CASCADE;

-- Удаление старых таблиц
DROP TABLE IF EXISTS med_equipment_offer CASCADE;
DROP TABLE IF EXISTS med_equipment_request CASCADE;
DROP TABLE IF EXISTS company_member CASCADE;
DROP TABLE IF EXISTS company CASCADE;
DROP TABLE IF EXISTS tender_step CASCADE;
DROP TABLE IF EXISTS tender_founder CASCADE;

-- ========== Справочники ==========

CREATE TABLE facility (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    inn         VARCHAR(12),
    address     VARCHAR(500),
    last_name   VARCHAR(100),
    first_name  VARCHAR(100),
    middle_name VARCHAR(100),
    phone       VARCHAR(50),
    email       VARCHAR(255)
);

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
    website     VARCHAR(255)
);

CREATE TABLE med_equipment (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    manufact   VARCHAR(255) NOT NULL,
    equip_type VARCHAR(100),
    cost       INTEGER NOT NULL,
    length_mm  INTEGER,
    width_mm   INTEGER,
    height_mm  INTEGER,
    weight_kg  NUMERIC(10, 2),
    spec       TEXT
);

CREATE TABLE user_account (
    id        BIGSERIAL PRIMARY KEY,
    username  VARCHAR(100) UNIQUE NOT NULL,
    full_name VARCHAR(255),
    role      VARCHAR(50)
);

-- ========== Тендеры ==========

CREATE TABLE tender (
    id              BIGSERIAL PRIMARY KEY,
    tender_number   VARCHAR(50) NOT NULL,
    facility_id     BIGINT REFERENCES facility(id),
    status          VARCHAR(50) NOT NULL,
    purchase_type   VARCHAR(50),
    deadline        DATE NOT NULL,
    publish_date    DATE,
    total_cost      NUMERIC(15, 2),
    currency        VARCHAR(10) DEFAULT 'RUB',
    description     TEXT,
    delivery_address TEXT,
    contact_last_name   VARCHAR(100),
    contact_first_name  VARCHAR(100),
    contact_middle_name VARCHAR(100),
    contact_phone       VARCHAR(50),
    contact_email       VARCHAR(255)
);

CREATE TABLE tender_lot (
    id            BIGSERIAL PRIMARY KEY,
    tender_id     BIGINT NOT NULL REFERENCES tender(id) ON DELETE CASCADE,
    lot_number    INTEGER,
    equip_name    VARCHAR(255),
    equip_type    VARCHAR(100),
    quantity      INTEGER,
    max_cost      NUMERIC(15, 2),
    max_length_mm INTEGER,
    max_width_mm  INTEGER,
    max_height_mm INTEGER,
    max_weight_kg NUMERIC(10, 2),
    required_spec TEXT
);

-- ========== Заявки ==========

CREATE TABLE activity_apply (
    id         BIGSERIAL PRIMARY KEY,
    tender_id  BIGINT NOT NULL REFERENCES tender(id),
    status     VARCHAR(50) DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE apply_item (
    id             BIGSERIAL PRIMARY KEY,
    apply_id       BIGINT NOT NULL REFERENCES activity_apply(id) ON DELETE CASCADE,
    tender_lot_id  BIGINT REFERENCES tender_lot(id),
    med_equip_id   BIGINT REFERENCES med_equipment(id),
    distributor_id BIGINT REFERENCES distributor(id),
    offered_cost   NUMERIC(15, 2),
    quantity       INTEGER
);

-- ========== Запросы КП ==========

CREATE TABLE price_request (
    id              BIGSERIAL PRIMARY KEY,
    tender_lot_id   BIGINT NOT NULL REFERENCES tender_lot(id),
    med_equip_id    BIGINT NOT NULL REFERENCES med_equipment(id),
    distributor_id  BIGINT NOT NULL REFERENCES distributor(id),
    status          VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    sent_at         TIMESTAMPTZ,
    response_price  NUMERIC(15, 2),
    response_date   DATE,
    response_note   TEXT,
    created_at      TIMESTAMPTZ DEFAULT now()
);
