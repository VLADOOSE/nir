-- Реальный код лота на площадке (СК-Фармация «1040409-Т1»).
-- Ключ связи лота с файлами техспецификации в модалке fms.ecc.kz (actionAjaxModalShowFiles).
-- Nullable, без бэкфила: заполняется переимпортом СК-Фармации; goszakup-лотов не касается.
ALTER TABLE tender_lot ADD COLUMN source_lot_code VARCHAR(50);
