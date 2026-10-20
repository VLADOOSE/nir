-- Сверка ТЗ с нашими товарами (спека docs/superpowers/specs/2026-10-20-product-spec-fit-design.md §10, план 2026-10-20-product-spec-fit).
-- РУ товаров сайта по данным ndda.kz (не рыночная: реестр общий; строка на № РУ сайта).
CREATE TABLE product_registration (
    reg_number      VARCHAR(100)  PRIMARY KEY,
    status          VARCHAR(15)   NOT NULL,
    ndda_id         BIGINT,
    name            TEXT,
    producer        VARCHAR(500),
    country         VARCHAR(200),
    reg_date        DATE,
    expiration_date DATE,
    unlimited       BOOLEAN,
    purpose         TEXT,
    use_area        TEXT,
    tech_chars      TEXT,
    risk_class      VARCHAR(200),
    complect        JSONB         NOT NULL DEFAULT '[]',
    checked_at      TIMESTAMPTZ   NOT NULL,
    error           VARCHAR(300)
);

-- Сверка отметки «наш товар»: вердикт лучшего товара, «N из M», первая причина, чужой бренд ТЗ, строки сверки (лучший + варианты).
ALTER TABLE tender_lot_product_match ADD COLUMN fit            VARCHAR(20);
ALTER TABLE tender_lot_product_match ADD COLUMN fit_product_id BIGINT REFERENCES site_product (id) ON DELETE SET NULL;
ALTER TABLE tender_lot_product_match ADD COLUMN fit_met        SMALLINT;
ALTER TABLE tender_lot_product_match ADD COLUMN fit_total      SMALLINT;
ALTER TABLE tender_lot_product_match ADD COLUMN fit_reason     VARCHAR(300);
ALTER TABLE tender_lot_product_match ADD COLUMN foreign_brand  VARCHAR(100);
ALTER TABLE tender_lot_product_match ADD COLUMN fit_candidates JSONB NOT NULL DEFAULT '[]';
ALTER TABLE tender_lot_product_match ADD COLUMN fit_checked_at TIMESTAMPTZ;
