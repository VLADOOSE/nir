-- Наша продукция в тендерах (спека 2026-10-19-product-watch §4): группы видов изделий, каталог сайта, отметки на лотах,
-- признаки тендера и лота, пункты планов закупок. Группы и каталог — не рыночные сущности с явным рынком (как
-- reply_template), пункты планов — рыночная (market + @Filter), отметки — через тендер.
CREATE TABLE product_group (
    id            BIGSERIAL    PRIMARY KEY,
    market        VARCHAR(2)   NOT NULL,
    name          VARCHAR(120) NOT NULL,
    search_words  JSONB        NOT NULL DEFAULT '[]',
    require_words JSONB        NOT NULL DEFAULT '[]',
    exclude_words JSONB        NOT NULL DEFAULT '[]',
    brand_words   JSONB        NOT NULL DEFAULT '[]',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    -- «Наши бренды и модели»: слова поиска — бренды и модели, находки сразу NAMED (спека §5.3)
    brand_group   BOOLEAN      NOT NULL DEFAULT FALSE,
    position      INT          NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100)
);
CREATE INDEX idx_product_group_market ON product_group (market, position);

CREATE TABLE site_product (
    id                BIGSERIAL    PRIMARY KEY,
    market            VARCHAR(2)   NOT NULL,
    site_id           UUID         NOT NULL UNIQUE,
    name              VARCHAR(500) NOT NULL,
    sku               VARCHAR(50),
    slug              VARCHAR(500),
    brand             VARCHAR(200),
    category          VARCHAR(200),
    short_description TEXT,
    description       TEXT,
    attributes        JSONB        NOT NULL DEFAULT '[]',
    registration_cert VARCHAR(200),
    site_updated_at   TIMESTAMPTZ,
    synced_at         TIMESTAMPTZ  NOT NULL,
    removed_at        TIMESTAMPTZ
);
CREATE INDEX idx_site_product_market ON site_product (market) WHERE removed_at IS NULL;

CREATE TABLE tender_lot_product_match (
    id              BIGSERIAL     PRIMARY KEY,
    tender_id       BIGINT        NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    lot_id          BIGINT        NOT NULL REFERENCES tender_lot (id) ON DELETE CASCADE,
    group_id        BIGINT        NOT NULL REFERENCES product_group (id) ON DELETE CASCADE,
    word            VARCHAR(100),
    level           VARCHAR(10)   NOT NULL,
    evidence        VARCHAR(1000),
    product_id      BIGINT        REFERENCES site_product (id) ON DELETE SET NULL,
    -- similar — зарезервированное слово PostgreSQL (SIMILAR TO)
    similar_products JSONB        NOT NULL DEFAULT '[]',
    spec_checked_at TIMESTAMPTZ,
    source          VARCHAR(10)   NOT NULL,
    matched_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_lot_product_match UNIQUE (lot_id, group_id)
);
CREATE INDEX idx_lot_product_match_tender ON tender_lot_product_match (tender_id);

ALTER TABLE tender ADD COLUMN found_by_product_search BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tender ADD COLUMN product_notified_level VARCHAR(10);
ALTER TABLE tender ADD COLUMN product_notified_at TIMESTAMPTZ;
ALTER TABLE tender_lot ADD COLUMN tech_spec_attempts SMALLINT NOT NULL DEFAULT 0;

CREATE TABLE procurement_plan_point (
    id             BIGSERIAL     PRIMARY KEY,
    market         VARCHAR(2)    NOT NULL,
    ext_id         BIGINT        NOT NULL UNIQUE,
    year           INT           NOT NULL,
    month          SMALLINT,
    customer_bin   VARCHAR(20),
    customer_name  VARCHAR(500),
    region         VARCHAR(100),
    delivery_place TEXT,
    name           VARCHAR(500)  NOT NULL,
    description    TEXT,
    quantity       NUMERIC(15,3),
    unit           VARCHAR(100),
    unit_price     NUMERIC(15,2),
    amount         NUMERIC(15,2),
    trade_method   VARCHAR(200),
    status_id      INT,
    status_name    VARCHAR(100),
    level          VARCHAR(10)   NOT NULL,
    first_seen_at  TIMESTAMPTZ   NOT NULL,
    last_seen_at   TIMESTAMPTZ   NOT NULL,
    gone_at        TIMESTAMPTZ,
    gone_status    VARCHAR(100),
    digested_at    TIMESTAMPTZ
);
CREATE INDEX idx_plan_point_open ON procurement_plan_point (market, year, month) WHERE gone_at IS NULL;

CREATE TABLE procurement_plan_match (
    id            BIGSERIAL    PRIMARY KEY,
    plan_point_id BIGINT       NOT NULL REFERENCES procurement_plan_point (id) ON DELETE CASCADE,
    group_id      BIGINT       NOT NULL REFERENCES product_group (id) ON DELETE CASCADE,
    word          VARCHAR(100),
    CONSTRAINT ux_plan_match UNIQUE (plan_point_id, group_id)
);

-- Сид: 24 группы рынка KZ по каталогу westmed.kz на 2026-10-19 (спека §4.1). Слова поиска — в формах площадки
-- (поиск площадки — одно целое слово, без морфологии), уточнения и исключения — стемы (начало слова).
INSERT INTO product_group (market, name, search_words, require_words, exclude_words, brand_words, brand_group, position) VALUES
('KZ', 'Наши бренды и модели (части и принадлежности)',
 '["фотек","fotek","аксион","эмалед","эхвч","эхвча","митар","актитон","actiton"]', '[]', '[]', '[]', TRUE, 1),
('KZ', 'Электрокардиографы (Аксион, НПП Монитор)', '["электрокардиограф"]', '[]', '[]',
 '["аксион","эк1т","эк3тц","эк12т","эк3т","крп-01","нпп монитор"]', FALSE, 2),
('KZ', 'Дефибрилляторы (Аксион)', '["дефибриллятор"]', '[]', '[]', '["аксион","дки-н","да-н"]', FALSE, 3),
('KZ', 'Аппараты ИВЛ (Аксион)', '["вентиляции"]', '["легк"]', '["приточн","вытяжн"]', '["а-ивл"]', FALSE, 4),
('KZ', 'Облучатели и рециркуляторы бактерицидные, УФ (ЭМА)', '["облучатель","рециркулятор"]',
 '["бактерицид","кварц","ультрафиолет","рециркул","обеззараж"]', '[]',
 '["рбм","рбпе","рбмт","обн","обп","обпе","оуфну","эма"]', FALSE, 5),
('KZ', 'Облучатели фототерапевтические (Аксион)', '["облучатель","фототерапии"]', '["фототерап","новорожд","неонат"]', '[]',
 '["офтн","аксион"]', FALSE, 6),
('KZ', 'Обогрев новорождённых (Аксион)', '["обогрева"]', '["новорожд","неонат"]', '[]', '["уон","аксион"]', FALSE, 7),
('KZ', 'Светильники операционные и смотровые (ЭМАЛЕД)', '["светильник"]',
 '["операцион","хирург","бестенев","смотров","медицин","процедур"]', '["уличн","прожектор","офисн","аварийн","взрывозащ"]',
 '["эмалед"]', FALSE, 8),
('KZ', 'Электрохирургия (Фотек)', '["электрохирургический","электрокоагулятор","коагулятор"]', '[]', '[]',
 '["фотек","эхвч","эхвча","onyx","термошов"]', FALSE, 9),
('KZ', 'Отсасыватели и аспирация дыма (Аксион, Фотек)', '["отсасыватель","аспиратор","аспирации"]', '[]', '["катетер","назальн"]',
 '["асд","фотек","омх","омп","ог-10","аксион"]', FALSE, 10),
('KZ', 'Ультразвуковая хирургия (Фотек ACTITON)', '["ультразвуковой"]', '["хирург","костн","остеотом","скальпел"]', '[]',
 '["actiton","актитон","фотек"]', FALSE, 11),
('KZ', 'Инсуффляторы (Фотек)', '["инсуффлятор"]', '[]', '[]', '["фотек"]', FALSE, 12),
('KZ', 'Нейтральные электроды и принадлежности ЭХВЧ (Фотек)', '["электрод"]',
 '["нейтральн","возвратн","пассивн","электрохирург"]', '["сварочн","экг","кардиограф","сравнения","искров"]',
 '["фотек","valleylab"]', FALSE, 13),
('KZ', 'Биполярные инструменты (Фотек)', '["пинцет","ножницы"]', '["биполяр"]', '[]', '["фотек","cleantips"]', FALSE, 14),
('KZ', 'Стойки аппаратные и инфузионные (Фотек)', '["стойка"]', '["инфузион","аппарат","медицин","эндоскоп"]',
 '["ресепшн","серверн","телекоммуникац","стеллаж"]', '["сп1-01","сп2-01","фотек"]', FALSE, 15),
('KZ', 'Стерилизаторы озоновые (Орион)', '["стерилизатор"]', '["озон"]', '[]',
 '["орион","ск-16","ск-36","ск-40","ск-85","ск-140","ск-250"]', FALSE, 16),
('KZ', 'Озонотерапия (Орион)', '["озонатор","озонотерапии"]', '[]', '[]', '["орион","от-15"]', FALSE, 17),
('KZ', 'Индикаторы стерилизации (Орион)', '["индикатор"]', '["стерилиз","стерильн"]', '[]', '["озовин","дезтест"]', FALSE, 18),
('KZ', 'Магнитотерапия (ЭМА «Полюс»)', '["магнитотерапии"]', '[]', '[]', '["полюс"]', FALSE, 19),
('KZ', 'Электрофорез (ЭМА «Поток»)', '["электрофореза"]', '["аппарат","физиотерап","гальван"]', '["гель","капилляр","реагент","белк"]',
 '["поток-1"]', FALSE, 20),
('KZ', 'Спирографы (НПП Монитор)', '["спирограф","спирометр"]', '[]', '[]', '["смп-02","нпп монитор"]', FALSE, 21),
('KZ', 'Холтер и кардиорегистраторы (НПП Монитор)', '["холтер","кардиорегистратор","мониторирования"]',
 '["экг","суточн","холтер","кардиорегистр"]', '[]', '["крн-01","крб-02","нпп монитор"]', FALSE, 22),
('KZ', 'Мониторы пациента (НПП Монитор)', '["монитор"]', '["пациент","прикроват","реанимац","витальн"]',
 '["компьютер","жидкокристал","диагональ","офисн"]', '["митар","нпп монитор"]', FALSE, 23),
('KZ', 'Дозаторы шприцевые (Аксион)', '["дозатор"]', '["шприц","инфузион"]',
 '["лаборатор","пипет","мыл","антисептик","локтев","бумаж"]', '["д01","аксион"]', FALSE, 24);
