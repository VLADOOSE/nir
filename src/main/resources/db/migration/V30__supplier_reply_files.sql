-- Ответ поставщика: файлы из писем и загруженные вручную, цены из файла
-- (спека docs/superpowers/specs/2026-10-15-supplier-reply-files-design.md §5).

CREATE TABLE supplier_reply_file (
    id               BIGSERIAL PRIMARY KEY,
    price_request_id BIGINT       NOT NULL REFERENCES price_request (id) ON DELETE CASCADE,
    -- письмо, из которого файл; пусто — загружен вручную
    inbound_email_id BIGINT       REFERENCES inbound_email (id) ON DELETE SET NULL,
    file_name        VARCHAR(255) NOT NULL,
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    sha256           VARCHAR(64),
    -- пусто — файл больше предела хранения (только имя)
    data             BYTEA,
    kind             VARCHAR(10)  NOT NULL,
    parse_status     VARCHAR(20)  NOT NULL,
    parse_note       VARCHAR(300),
    -- разобранная таблица Excel / Word: колонки с ролями, строки с номерами строк файла, валюта
    parsed           JSONB,
    page_count       INT,
    uploaded_by      VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_reply_file_sha UNIQUE (price_request_id, sha256)
);
CREATE INDEX idx_supplier_reply_file_inbound ON supplier_reply_file (inbound_email_id);

-- 💡 — цена поставлена разбором (текст письма или файл) и ещё не сверена; ручное сохранение снимает
ALTER TABLE price_request_item ADD COLUMN response_auto BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_request_item ADD COLUMN response_file_id BIGINT REFERENCES supplier_reply_file (id) ON DELETE SET NULL;
ALTER TABLE price_request_item ADD COLUMN response_file_row INT;

-- 💡 из текста письма был пометкой в начале примечания — стал признаком строки ответа; «Срок…» и «Контекст…» остаются
UPDATE price_request_item
   SET response_auto = TRUE,
       response_note = NULLIF(regexp_replace(response_note, '^💡[^.]*\.?\s*', ''), '')
 WHERE response_note LIKE '💡%';

-- Словарь заголовков: роли колонок КП поставщика (§3.2). Уже выученные заголовки не трогаем.
INSERT INTO header_synonym (header_norm, field) VALUES
    ('№', 'NUMBER'), ('№ п/п', 'NUMBER'), ('№ пп', 'NUMBER'), ('№ п.п.', 'NUMBER'),
    ('артикул', 'ARTICLE'), ('артикул (модель)', 'ARTICLE'), ('модель', 'ARTICLE'), ('каталожный номер', 'ARTICLE'),
    ('ед.', 'UNIT'), ('ед', 'UNIT'), ('ед. изм.', 'UNIT'), ('ед.изм.', 'UNIT'), ('единица измерения', 'UNIT'),
    ('цена', 'PRICE'), ('цена за ед.', 'PRICE'), ('цена за единицу', 'PRICE'), ('цена, руб.', 'PRICE'),
    ('цена, руб. за ед.', 'PRICE'), ('цена, тенге', 'PRICE'), ('цена, тг', 'PRICE'), ('цена за ед., тг', 'PRICE'),
    ('цена с ндс', 'PRICE'), ('цена со скидкой', 'PRICE'),
    ('сумма', 'SUM'), ('сумма, руб.', 'SUM'), ('сумма, тенге', 'SUM'), ('сумма, тг', 'SUM'), ('общая сумма', 'SUM'),
    ('общая сумма, тг', 'SUM'), ('стоимость', 'SUM'),
    ('ндс', 'VAT'), ('ставка ндс', 'VAT'), ('ставка ндс, %', 'VAT'),
    ('валюта', 'CURRENCY'),
    ('примечание', 'NOTE'), ('комментарий', 'NOTE')
ON CONFLICT (header_norm) DO NOTHING;
