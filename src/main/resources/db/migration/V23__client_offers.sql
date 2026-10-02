-- V23: КП клиенту (спека docs/superpowers/specs/2026-10-02-client-kp-constructor-design.md §4).
-- company_profile — реквизиты и настройки КП, строка на рынок (как email_template, НЕ рыночная сущность).
-- client_offer / client_offer_item — КП и его строки (рыночная сущность; строки — через коллекцию, §7 CLAUDE.md).
CREATE TABLE company_profile (
    id                   BIGSERIAL PRIMARY KEY,
    market               VARCHAR(2)   NOT NULL UNIQUE,
    short_name           VARCHAR(255) NOT NULL,
    full_name            VARCHAR(500),
    header_left          TEXT,
    header_right         TEXT,
    brand_text           VARCHAR(100),
    ids_line             VARCHAR(500),
    bin_inn              VARCHAR(20),
    address              TEXT,
    accounts             TEXT,
    bank_name            VARCHAR(255),
    bik                  VARCHAR(20),
    phone                VARCHAR(100),
    email                VARCHAR(255),
    director_title       VARCHAR(100),
    director_name        VARCHAR(255),
    signoff_contacts     VARCHAR(500),
    logo_png             BYTEA,
    stamp_png            BYTEA,
    signature_png        BYTEA,
    images_updated_at    TIMESTAMPTZ,
    stamp_size_mm        INTEGER      NOT NULL DEFAULT 40,
    vat_rates            JSONB        NOT NULL,                -- [5, 16, null]; null = «Без НДС»
    vat_default          NUMERIC(5,2),                         -- NULL = без НДС
    vat_registered       NUMERIC(5,2),
    vat_not_registrable  NUMERIC(5,2),
    default_markup_pct   NUMERIC(7,2) NOT NULL DEFAULT 20,
    default_columns      JSONB        NOT NULL,                -- [{"key":"NUM","label":"№"}, …]
    default_terms        JSONB        NOT NULL,                -- [{"label":"…","value":"…"}, …]
    default_terms_style  VARCHAR(10)  NOT NULL DEFAULT 'LIST',
    default_intro        TEXT,
    next_number          INTEGER      NOT NULL DEFAULT 1,
    updated_at           TIMESTAMPTZ                           -- NULL = реквизиты ещё не правились (сид); ставит первое сохранение со страницы реквизитов
);

CREATE TABLE client_offer (
    id                    BIGSERIAL PRIMARY KEY,
    market                VARCHAR(2)   NOT NULL,
    number                INTEGER      NOT NULL,                -- «исх. №»; уникальность не навязываем (спека §4.2)
    offer_date            DATE         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT', -- DRAFT / SENT / ACCEPTED / REJECTED
    facility_id           BIGINT REFERENCES facility (id) ON DELETE SET NULL,
    recipient             TEXT,
    tender_id             BIGINT REFERENCES tender (id) ON DELETE SET NULL,  -- частная заявка-источник (волна 2)
    title                 VARCHAR(200) NOT NULL,
    subject               TEXT,
    intro                 TEXT,
    vat_enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    default_markup_pct    NUMERIC(7,2) NOT NULL DEFAULT 0,
    rounding              VARCHAR(10)  NOT NULL DEFAULT 'NONE',  -- NONE / UNIT / TEN / HUNDRED
    table_columns         JSONB        NOT NULL,
    details_in_name       BOOLEAN      NOT NULL DEFAULT TRUE,
    terms                 JSONB        NOT NULL DEFAULT '[]',
    terms_style           VARCHAR(10)  NOT NULL DEFAULT 'LIST',  -- TABLE / LIST / NONE
    show_amount_in_words  BOOLEAN      NOT NULL DEFAULT TRUE,
    show_vat_breakdown    BOOLEAN      NOT NULL DEFAULT TRUE,
    landscape             BOOLEAN      NOT NULL DEFAULT FALSE,
    signoff               VARCHAR(10)  NOT NULL DEFAULT 'DIRECTOR', -- COMPANY / DIRECTOR
    signoff_contacts      BOOLEAN      NOT NULL DEFAULT FALSE,
    with_stamp            BOOLEAN      NOT NULL DEFAULT FALSE,
    total_amount          NUMERIC(15,2),                         -- пересчитывается при сохранении — для журнала без N+1
    item_count            INTEGER      NOT NULL DEFAULT 0,
    internal_note         TEXT,
    version               INTEGER      NOT NULL DEFAULT 0,       -- @Version: защита от затирания из второй вкладки
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at               TIMESTAMPTZ
);
CREATE INDEX idx_client_offer_market_number ON client_offer (market, number);
CREATE INDEX idx_client_offer_tender ON client_offer (tender_id);
CREATE INDEX idx_client_offer_facility ON client_offer (facility_id);

CREATE TABLE client_offer_item (
    id                     BIGSERIAL PRIMARY KEY,
    offer_id               BIGINT        NOT NULL REFERENCES client_offer (id) ON DELETE CASCADE,
    line_no                INTEGER       NOT NULL,
    kind                   VARCHAR(10)   NOT NULL DEFAULT 'ITEM',  -- ITEM / SECTION / INCLUDED
    name                   TEXT          NOT NULL,
    model                  VARCHAR(255),
    manufacturer           VARCHAR(500),
    country                VARCHAR(200),
    unit                   VARCHAR(30)   NOT NULL DEFAULT 'шт',
    quantity               NUMERIC(12,3),
    purchase_price         NUMERIC(15,2),
    purchase_vat_same      BOOLEAN       NOT NULL DEFAULT TRUE,
    purchase_vat_rate      NUMERIC(5,2),                            -- при purchase_vat_same = false; NULL = без НДС
    supplier_name          VARCHAR(255),
    distributor_id         BIGINT REFERENCES distributor (id) ON DELETE SET NULL,
    tender_lot_id          BIGINT REFERENCES tender_lot (id) ON DELETE SET NULL,
    price_request_item_id  BIGINT REFERENCES price_request_item (id) ON DELETE SET NULL,
    med_equipment_id       BIGINT REFERENCES med_equipment (id) ON DELETE SET NULL,
    markup_pct             NUMERIC(7,2),                            -- NULL = общая наценка КП
    price_override         NUMERIC(15,2),                           -- цена клиенту, вбитая руками
    vat_rate               NUMERIC(5,2),                            -- NULL = без НДС
    registration_status    VARCHAR(20)   NOT NULL DEFAULT 'UNCHECKED',
    registration_text      TEXT,
    reg_number             VARCHAR(100),
    suggestion_score       NUMERIC(5,3),
    note                   TEXT
);
CREATE INDEX idx_client_offer_item_offer ON client_offer_item (offer_id, line_no);

-- Стартовые реквизиты. West-Med — из КП отца (24.09.2026, банк переименован в Alatau City Bank — подтверждено
-- оператором 2026-10-02), Регион-Мед — из прежних констант CompanyInfo. Казахский текст — с правильной «і» (U+0456):
-- в бланке отца стоит латинская «i», обход старых шрифтов. next_number = 1 — свой номер отец ставит перед первым КП.
INSERT INTO company_profile (market, short_name, full_name, header_left, header_right, brand_text, ids_line, bin_inn,
                             address, accounts, bank_name, bik, phone, email, director_title, director_name,
                             signoff_contacts, vat_rates, vat_default, vat_registered, vat_not_registrable,
                             default_markup_pct, default_columns, default_terms, default_terms_style, default_intro,
                             next_number)
VALUES ('KZ', 'ТОО «West-Med»', 'Товарищество с ограниченной ответственностью «West-Med»',
        E'Жауапкершілігі\nшектеулі серіктестігі', E'Товарищество\nс ограниченной ответственностью',
        '"West-Med"', 'РНН 271 800 059 535 БИН 121 040 000 303', '121040000303',
        E'Республика Казахстан, 090000, Западно-Казахстанская область,\nгород Уральск, ул.Мухита 121-21',
        'KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)',
        'АО "Alatau City Bank"', 'TSESKZKA', '87770752770', 'west-med@mail.ru',
        'Директор', 'Ширяев Илья Викторович', 'моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru',
        '[5, 16, null]', 5, 5, 16, 20,
        '[{"key":"NUM","label":"№"},{"key":"NAME","label":"Наименование"},{"key":"UNIT","label":"Ед. изм."},{"key":"QTY","label":"Кол-во"},{"key":"PRICE","label":"Цена за ед., тг"},{"key":"VAT_RATE","label":"НДС"},{"key":"SUM","label":"Общая сумма, тг"},{"key":"REGISTRATION","label":"Регистрация в РК"}]',
        '[{"label":"","value":"Цены действительны в течение 10 дней"},{"label":"","value":"Транспортные услуги включены в общую стоимость товара"},{"label":"Порядок оплаты","value":"100% предоплата"},{"label":"Форма оплаты","value":"безналичная"},{"label":"Срок поставки всего товара","value":"30 рабочих дней после поступления предоплаты"}]',
        'LIST', 'ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:', 1),
       ('RF', 'ООО «РЕГИОН-МЕД»', 'Общество с ограниченной ответственностью «РЕГИОН-МЕД»', NULL, NULL,
        'РЕГИОН-МЕД', 'ИНН 6318000846 КПП 631801001 ОГРН 1146318039218', '6318000846',
        E'Российская Федерация, 443066, Самарская область,\nг. Самара, ул. Дыбенко, д. 120, кв. 148',
        'р/с 40702810623000120018, к/с 30101810300000000847',
        'Поволжский филиал АО «РАЙФФАЙЗЕНБАНК» (г. Нижний Новгород)', '042202847', '+7 (846) 201-55-15',
        'region-med@mail.ru', 'Директор', 'Ширяев Илья Викторович',
        'моб.: +7 927 755-50-70, e-mail: region-med@mail.ru',
        '[null, 10, 22]', 22, NULL, 22, 20,
        '[{"key":"NUM"},{"key":"NAME"},{"key":"QTY"},{"key":"UNIT"},{"key":"PRICE"},{"key":"VAT_RATE"},{"key":"SUM"}]',
        '[{"label":"","value":"Цены действительны в течение 10 дней"},{"label":"Порядок оплаты","value":"100% предоплата"},{"label":"Форма оплаты","value":"безналичная"}]',
        'LIST', 'ООО «РЕГИОН-МЕД» предлагает поставку медицинской продукции по следующим ценам:', 1);
