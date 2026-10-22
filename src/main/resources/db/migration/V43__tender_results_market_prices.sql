-- Итоги тендеров goszakup, «звонить заказчику» и цены рынка из договоров (спека 2026-10-22-tender-results-market-prices §12):
-- поля площадки у лота, итог лота, участники протокола, события итогов, набор договоров, справочники.
ALTER TABLE tender_lot ADD COLUMN ext_lot_id BIGINT;
ALTER TABLE tender_lot ADD COLUMN plan_point_id BIGINT;
ALTER TABLE tender_lot ADD COLUMN enstru_code VARCHAR(30);
ALTER TABLE tender_lot ADD COLUMN plan_unit VARCHAR(50);
CREATE INDEX idx_tender_lot_plan_point ON tender_lot (plan_point_id) WHERE plan_point_id IS NOT NULL;

CREATE TABLE tender_lot_result (
    id                    BIGSERIAL PRIMARY KEY,
    tender_lot_id         BIGINT      NOT NULL UNIQUE REFERENCES tender_lot (id) ON DELETE CASCADE,
    tender_id             BIGINT      NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    lot_status_id         INT,
    lot_status            VARCHAR(100),
    outcome               VARCHAR(12) NOT NULL,
    fail_reason           VARCHAR(12),
    fail_text             VARCHAR(300),
    results_at            TIMESTAMP,
    protocol_url          VARCHAR(300),
    protocol_name         VARCHAR(200),
    protocol_status       VARCHAR(16),
    protocol_note         VARCHAR(300),
    protocol_attempts     SMALLINT    NOT NULL DEFAULT 0,
    bids_count            INT,
    rejected_count        INT,
    winner_name           VARCHAR(300),
    winner_bin            VARCHAR(20),
    winner_price          NUMERIC(15, 2),
    second_name           VARCHAR(300),
    second_bin            VARCHAR(20),
    second_price          NUMERIC(15, 2),
    min_price             NUMERIC(15, 2),
    max_price             NUMERIC(15, 2),
    our_place             INT,
    our_price             NUMERIC(15, 2),
    our_rejected          BOOLEAN,
    contract_number       VARCHAR(60),
    contract_signed_at    DATE,
    contract_unit_price   NUMERIC(15, 2),
    contract_sum          NUMERIC(17, 2),
    contract_supplier_bin VARCHAR(20),
    attempts              JSONB       NOT NULL DEFAULT '[]',
    next_tender_id        BIGINT REFERENCES tender (id) ON DELETE SET NULL,
    event_at              TIMESTAMPTZ,
    checked_at            TIMESTAMPTZ,
    final_at              TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tender_lot_result_tender ON tender_lot_result (tender_id);

CREATE TABLE tender_lot_bid (
    id            BIGSERIAL PRIMARY KEY,
    result_id     BIGINT  NOT NULL REFERENCES tender_lot_result (id) ON DELETE CASCADE,
    place         INT,
    supplier_name VARCHAR(300),
    supplier_bin  VARCHAR(20),
    unit_price    NUMERIC(15, 2),
    total         NUMERIC(17, 2),
    submitted_at  TIMESTAMP,
    rejected      BOOLEAN NOT NULL DEFAULT FALSE,
    reject_reason VARCHAR(500),
    winner        BOOLEAN NOT NULL DEFAULT FALSE,
    second_place  BOOLEAN NOT NULL DEFAULT FALSE,
    ours          BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_tender_lot_bid_result ON tender_lot_bid (result_id);

CREATE TABLE tender_result_event (
    id            BIGSERIAL PRIMARY KEY,
    tender_id     BIGINT       NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    tender_lot_id BIGINT       NOT NULL REFERENCES tender_lot (id) ON DELETE CASCADE,
    kind          VARCHAR(12)  NOT NULL,
    occurred_at   TIMESTAMP,
    text          VARCHAR(500) NOT NULL,
    urgent        BOOLEAN      NOT NULL,
    topic         VARCHAR(10)  NOT NULL,
    ref_tender_id BIGINT REFERENCES tender (id) ON DELETE SET NULL,
    ref_key       VARCHAR(60),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notified_at   TIMESTAMPTZ,
    digested_at   TIMESTAMPTZ
);
CREATE INDEX idx_tender_result_event_tender ON tender_result_event (tender_id);
CREATE INDEX idx_tender_result_event_pending ON tender_result_event (id) WHERE notified_at IS NULL OR digested_at IS NULL;

CREATE TABLE market_contract (
    id                BIGSERIAL PRIMARY KEY,
    lot_ext_id        BIGINT         NOT NULL UNIQUE,
    anno              VARCHAR(30),
    lot_number        VARCHAR(50),
    enstru_code       VARCHAR(30),
    name              VARCHAR(500),
    description       VARCHAR(1000),
    extra_desc        TEXT,
    unit              VARCHAR(50),
    customer_bin      VARCHAR(20),
    customer_name     VARCHAR(500),
    region            VARCHAR(100),
    quantity          NUMERIC(15, 3),
    budget_unit_price NUMERIC(15, 2),
    unit_price        NUMERIC(15, 2) NOT NULL,
    supplier_bin      VARCHAR(20),
    signed_at         DATE,
    contract_number   VARCHAR(60),
    trade_method      VARCHAR(150),
    fetched_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_market_contract_code ON market_contract (enstru_code, signed_at);
CREATE INDEX idx_market_contract_customer ON market_contract (customer_bin);

CREATE TABLE market_lookup (
    lookup_key VARCHAR(150) PRIMARY KEY,
    fetched_at TIMESTAMPTZ  NOT NULL,
    rows_count INT          NOT NULL DEFAULT 0,
    error      VARCHAR(300)
);

CREATE TABLE goszakup_subject (
    bin        VARCHAR(20) PRIMARY KEY,
    name_ru    VARCHAR(500),
    phone      VARCHAR(100),
    email      VARCHAR(300),
    not_found  BOOLEAN     NOT NULL DEFAULT FALSE,
    fetched_at TIMESTAMPTZ NOT NULL
);
