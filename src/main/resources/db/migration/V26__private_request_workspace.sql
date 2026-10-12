-- Частная заявка — рабочее место (спека docs/superpowers/specs/2026-10-12-private-request-workspace-design.md, этап 1).

-- заявка: контактное лицо одной строкой и дата создания (у tender её не было — список не сортировался «новые сверху»)
ALTER TABLE tender ADD COLUMN contact_name VARCHAR(255);
ALTER TABLE tender ADD COLUMN created_at TIMESTAMPTZ;
-- умолчание ставим ПОСЛЕ добавления: иначе старые строки получили бы дату миграции вместо «неизвестно»
ALTER TABLE tender ALTER COLUMN created_at SET DEFAULT now();

-- дата создания уже заведённых частных заявок: из обращения, иначе из первого запроса цен; нет ни того ни другого — пусто
UPDATE tender t SET created_at = l.received_at
FROM lead l
WHERE l.private_request_id = t.id AND t.source = 'PRIVATE_REQUEST' AND t.created_at IS NULL;

UPDATE tender t SET created_at = pr.first_at
FROM (SELECT tender_id, min(created_at) AS first_at FROM price_request GROUP BY tender_id) pr
WHERE pr.tender_id = t.id AND t.source = 'PRIVATE_REQUEST' AND t.created_at IS NULL;

-- позиция частной заявки: ед. изм., ссылка на товар сайта, РУ (статус / номер / похожесть подсказки)
ALTER TABLE tender_lot ADD COLUMN unit VARCHAR(30);
ALTER TABLE tender_lot ADD COLUMN product_url VARCHAR(500);
ALTER TABLE tender_lot ADD COLUMN reg_status VARCHAR(20);
ALTER TABLE tender_lot ADD COLUMN reg_number VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN reg_score NUMERIC(5, 3);

-- запрос цен: «ответить до», адрес, на который письмо приняла почта, исход отправки
ALTER TABLE price_request ADD COLUMN reply_by DATE;
ALTER TABLE price_request ADD COLUMN sent_to VARCHAR(255);
ALTER TABLE price_request ADD COLUMN letter_status VARCHAR(20);

-- список частных заявок рынка
CREATE INDEX idx_tender_source_market ON tender (source, market);
