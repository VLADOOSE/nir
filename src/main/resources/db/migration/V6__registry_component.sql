-- Кеш комплектности (состава) аппаратов НЦЭЛС. Наполняется on-demand при поиске по комплектности
-- (кнопка в панели «Реестр»); reg_number ссылается на аппарат в med_registry. Общая таблица (без market).
CREATE TABLE IF NOT EXISTS registry_component (
    id           BIGSERIAL PRIMARY KEY,
    reg_number   TEXT NOT NULL,
    part_number  INT,
    product_name TEXT,
    component    TEXT,
    producer     TEXT,
    country      TEXT,
    fetched_at   TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_registry_component UNIQUE (reg_number, part_number)
);
CREATE INDEX IF NOT EXISTS idx_registry_component_reg ON registry_component(reg_number);
