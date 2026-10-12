-- V27: частная заявка, этап 2 (спека 2026-10-12 §3.2) — запрос на бланке, клиент в запросе, вписанная почта,
-- «Доверенный» и «Производитель» у поставщика, тексты запроса на бланке в шаблоне письма рынка.

-- запрос цен помнит свои настройки — «↻ Переслать» повторяет письмо в точности (§6.5)
ALTER TABLE price_request ADD COLUMN disclose_client BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_request ADD COLUMN with_document   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_request ADD COLUMN with_stamp      BOOLEAN NOT NULL DEFAULT FALSE;
-- письмо ушло на адрес, вписанный в окне запроса (он — в sent_to), а не на почту из карточки
ALTER TABLE price_request ADD COLUMN email_override  BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE distributor ADD COLUMN trusted      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE distributor ADD COLUMN manufacturer BOOLEAN NOT NULL DEFAULT FALSE;

-- пусто — зашитый текст (RfqTexts), как у темы и тела письма
ALTER TABLE email_template ADD COLUMN rfq_title       TEXT;
ALTER TABLE email_template ADD COLUMN rfq_intro       TEXT;
ALTER TABLE email_template ADD COLUMN rfq_ask         TEXT;
ALTER TABLE email_template ADD COLUMN rfq_client_line TEXT;
