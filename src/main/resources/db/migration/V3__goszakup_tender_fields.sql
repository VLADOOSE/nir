-- Поля для импортных KZ-тендеров с goszakup.gov.kz
ALTER TABLE tender ADD COLUMN IF NOT EXISTS source_ext_id VARCHAR(64);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS region        VARCHAR(100);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS region_kato   VARCHAR(20);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS customer_name VARCHAR(500);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS customer_bin  VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_tender_region ON tender(market, region);
-- ключ идемпотентности импорта (только для импортных строк)
CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_market_extid
    ON tender(market, source_ext_id) WHERE source_ext_id IS NOT NULL;
