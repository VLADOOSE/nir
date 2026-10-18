-- События сделки (2026-10-18, спека 2026-10-18-deal-page-design §5): заметки, звонки и смена этапа — то, чего нет в других
-- таблицах; остальное лента собирает из существующих таблиц по их датам. Частная заявка и тендер — одна сущность tender,
-- поэтому таблица одна на оба вида сделок; рынок — через tender (сама таблица не рыночная).
CREATE TABLE deal_event (
    id          BIGSERIAL PRIMARY KEY,
    tender_id   BIGINT       NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    type        VARCHAR(20)  NOT NULL,
    code        VARCHAR(40),
    text        TEXT         NOT NULL,
    author      VARCHAR(100),
    ref_id      BIGINT,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_event_tender ON deal_event (tender_id, occurred_at);
