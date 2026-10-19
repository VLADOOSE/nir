-- Исполнение сделки (2026-10-19, спека 2026-10-19-deal-execution-design §5): после «Клиент согласился» — договор,
-- оплата клиента, поставщики (заказ, оплата, отгрузка), поставка, закрывающие документы и файлы этапов. Таблицы
-- привязаны к tender — подходят и частной заявке, и тендеру (подпроект 4). Не рыночные: читаются только через заявку
-- с гардом рынка (как deal_event). contract_kind / stage / currency проверяет сервис — без CHECK, как у прежних таблиц.

-- Одна строка на сделку, заводится при первой записи
CREATE TABLE deal_execution (
    tender_id        BIGINT         PRIMARY KEY REFERENCES tender (id) ON DELETE CASCADE,
    contract_kind    VARCHAR(10),                 -- CONTRACT / INVOICE / NONE («без договора»); пусто — не внесено
    contract_number  VARCHAR(100),
    contract_date    DATE,
    contract_note    TEXT,
    prepay_pct       NUMERIC(5, 2)  NOT NULL DEFAULT 100,   -- 0 — оплата после поставки
    amount           NUMERIC(15, 2),              -- своя сумма сделки; пусто — итог главного принятого КП
    from_stock       BOOLEAN        NOT NULL DEFAULT false, -- «Со склада»
    delivered_on     DATE,
    delivery_note    TEXT,
    docs_on          DATE,
    docs_note        TEXT,
    updated_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Поставщик сделки; поставщик из КП заводится строкой при первой записи (до неё — из КП на лету)
CREATE TABLE deal_supplier (
    id              BIGSERIAL      PRIMARY KEY,
    tender_id       BIGINT         NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    distributor_id  BIGINT         NOT NULL REFERENCES distributor (id),
    amount          NUMERIC(15, 2),               -- к оплате; пусто — по КП
    currency        VARCHAR(3)     NOT NULL,      -- KZT / RUB / USD / EUR / CNY
    excluded        BOOLEAN        NOT NULL DEFAULT false,  -- «Не заказываем»
    manual          BOOLEAN        NOT NULL DEFAULT false,  -- добавлен вручную
    ordered_on      DATE,
    order_note      TEXT,
    shipped_on      DATE,
    ship_note       TEXT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (tender_id, distributor_id)
);

-- Платёж: поставщик пуст — платёж клиента (валюта рынка), иначе — поставщику (его валюта)
CREATE TABLE deal_payment (
    id                BIGSERIAL      PRIMARY KEY,
    tender_id         BIGINT         NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    deal_supplier_id  BIGINT         REFERENCES deal_supplier (id) ON DELETE CASCADE,
    amount            NUMERIC(15, 2) NOT NULL CHECK (amount > 0),
    paid_on           DATE           NOT NULL,
    note              TEXT,
    created_by        VARCHAR(100),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_payment_tender ON deal_payment (tender_id);
CREATE INDEX idx_deal_payment_supplier ON deal_payment (deal_supplier_id);

-- Файл этапа: к платежу — payment_id, к поставщику — deal_supplier_id
CREATE TABLE deal_file (
    id                  BIGSERIAL      PRIMARY KEY,
    tender_id           BIGINT         NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    stage               VARCHAR(20)    NOT NULL,  -- CONTRACT / CLIENT_PAYMENT / SUPPLIER / DELIVERY / DOCS
    payment_id          BIGINT         REFERENCES deal_payment (id) ON DELETE CASCADE,
    deal_supplier_id    BIGINT         REFERENCES deal_supplier (id) ON DELETE CASCADE,
    file_name           VARCHAR(255)   NOT NULL,
    mime_type           VARCHAR(100),
    size_bytes          BIGINT         NOT NULL,
    sha256              VARCHAR(64)    NOT NULL,
    content             BYTEA          NOT NULL,
    chat_attachment_id  BIGINT         REFERENCES chat_attachment (id) ON DELETE SET NULL,  -- из какого файла переписки
    uploaded_by         VARCHAR(100),
    uploaded_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_file_tender ON deal_file (tender_id);
