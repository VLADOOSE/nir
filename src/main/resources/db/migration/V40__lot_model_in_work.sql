-- «Модель в работе» лота (спека 2026-10-20-lot-model-in-work §4): позиция каталога из нашего товара сайта — одна на товар;
-- у лота — заметка «почему эта модель» (вывод ИИ) и кто / когда закрепил.
ALTER TABLE med_equipment ADD COLUMN site_product_id BIGINT REFERENCES site_product (id) ON DELETE SET NULL;
CREATE UNIQUE INDEX uq_med_equipment_site_product ON med_equipment (site_product_id) WHERE site_product_id IS NOT NULL;
ALTER TABLE tender_lot ADD COLUMN model_note VARCHAR(1000);
ALTER TABLE tender_lot ADD COLUMN model_set_by VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN model_set_at TIMESTAMPTZ;
