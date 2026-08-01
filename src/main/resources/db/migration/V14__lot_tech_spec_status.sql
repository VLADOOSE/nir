-- V14: статус фонового авторазбора техспеки лота.
-- Нужен, чтобы (1) очередь не долбила бесконечно один и тот же лот,
-- (2) UI честно отличал «ТЗ не разобрано» от «ТЗ разобрано, но пустое».
-- NULL = лот в очередь ещё не ставился (ручные лоты, старые импорты).
ALTER TABLE tender_lot ADD COLUMN tech_spec_status VARCHAR(20);
ALTER TABLE tender_lot ADD COLUMN tech_spec_attempted_at TIMESTAMPTZ;

-- Воркер выбирает пачку по статусу — без индекса это seq scan по всем лотам.
CREATE INDEX idx_lot_tech_spec_status ON tender_lot (tech_spec_status)
    WHERE tech_spec_status = 'PENDING';
