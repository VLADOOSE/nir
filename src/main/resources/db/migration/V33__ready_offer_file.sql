-- Готовое КП (2026-10-19, спека 2026-10-19-ready-offer-file-design §5): КП, сделанное вне конструктора (Word отца, КП по
-- образцу поставщика), — обычное КП клиенту с файлом документа. origin = FILE; номер у такого КП бывает пустым (в
-- документе его нет), счётчик «исх. №» не тратится; у конструктора номер по-прежнему обязателен (проверка в сервисе).
ALTER TABLE client_offer ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'BUILDER';
ALTER TABLE client_offer ALTER COLUMN number DROP NOT NULL;

-- Файл готового КП — отдельной таблицей: журнал и редактор КП байты не тянут. Один файл на КП, уходит вместе с ним.
-- Не рыночная: читается только через КП с гардом рынка (как client_offer_item).
CREATE TABLE client_offer_file (
    id                  BIGSERIAL PRIMARY KEY,
    offer_id            BIGINT        NOT NULL UNIQUE REFERENCES client_offer (id) ON DELETE CASCADE,
    file_name           VARCHAR(255)  NOT NULL,
    mime_type           VARCHAR(100),
    size_bytes          BIGINT        NOT NULL,
    sha256              VARCHAR(64)   NOT NULL,
    content             BYTEA         NOT NULL,
    kind                VARCHAR(10)   NOT NULL,   -- EXCEL / WORD / PDF / IMAGE (ReplyFileKind)
    parse_status        VARCHAR(12)   NOT NULL,   -- PARSED / NO_TABLE / NOT_PARSED / FAILED / TOO_LARGE (ReplyParseStatus)
    parse_note          TEXT,
    tables              JSONB,                    -- таблицы файла для просмотра (список ReplyTable)
    pages               INTEGER,                  -- страниц PDF
    file_total          NUMERIC(15, 2),           -- «ИТОГО» из файла
    chat_attachment_id  BIGINT REFERENCES chat_attachment (id) ON DELETE SET NULL,   -- файл переписки, откуда взят
    uploaded_by         VARCHAR(100),
    uploaded_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_client_offer_file_attachment ON client_offer_file (chat_attachment_id);

-- «Не КП»: подсказка «КП ушло клиенту» про этот файл переписки больше не показывается
ALTER TABLE chat_attachment ADD COLUMN offer_hint_dismissed BOOLEAN NOT NULL DEFAULT false;
