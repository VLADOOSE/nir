-- Курс пересчёта цен файла ответа поставщика в валюту запроса (2026-10-16): поставщик из РФ присылает КП в рублях на
-- запрос West-Med (тенге) — оператор вписывает курс на экране «Ответ поставщика», цены файла умножаются и встают в
-- ответ с 💡. «1 <rate_currency> = rate <валюта рынка запроса>»; rate_set_at — для подсказки «последний курс».
ALTER TABLE supplier_reply_file ADD COLUMN rate NUMERIC(14, 6);
ALTER TABLE supplier_reply_file ADD COLUMN rate_currency VARCHAR(8);
ALTER TABLE supplier_reply_file ADD COLUMN rate_set_at TIMESTAMPTZ;
ALTER TABLE supplier_reply_file ADD CONSTRAINT ck_supplier_reply_file_rate CHECK (rate IS NULL OR rate > 0);
