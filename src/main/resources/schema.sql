-- DEV ONLY: пересоздаёт все таблицы при каждом запуске

-- Удаление таблиц в обратном порядке зависимостей
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
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(255) NOT NULL UNIQUE,
    inn      VARCHAR(12),
    address  VARCHAR(500),
    contact  VARCHAR(500)
);

CREATE TABLE distributor (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(255) NOT NULL UNIQUE,
    inn     VARCHAR(12) UNIQUE,
    contact VARCHAR(500)
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
    id            BIGSERIAL PRIMARY KEY,
    tender_number VARCHAR(50),
    facility_id   BIGINT REFERENCES facility(id),
    status        VARCHAR(50),
    deadline      DATE,
    total_cost    NUMERIC(15, 2),
    description   TEXT
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
