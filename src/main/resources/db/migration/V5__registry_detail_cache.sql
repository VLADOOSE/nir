-- Кеш карточки НЦЭЛС (детали РУ). Наполняется on-demand при первом просмотре описания
-- кандидата в панели «Реестр»; detail_fetched_at — маркер «карточку уже тянули»
-- (ставится и при «РУ на портале не найден», чтобы не долбить портал повторно).
-- Реимпорт дампа (RegistryImportService, ON CONFLICT DO UPDATE) эти колонки не трогает.
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS ndda_id           BIGINT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS risk_class        TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS purpose           TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS use_area          TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS tech_chars        TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS mi_kind           TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS mi_kind_def       TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS detail_fetched_at TIMESTAMPTZ;
