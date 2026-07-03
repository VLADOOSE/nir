-- Предложенная (одобренная оператором) модель каталога для лота тендера
ALTER TABLE tender_lot ADD COLUMN IF NOT EXISTS proposed_equipment_id BIGINT REFERENCES med_equipment(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_lot_proposed_equipment ON tender_lot(proposed_equipment_id);
