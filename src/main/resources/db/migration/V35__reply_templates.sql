-- Шаблоны ответов клиентам и подпись письма (спека 2026-10-19-client-mail-reply §6): строка на шаблон, подпись — строка
-- с ролью SIGNATURE. Не рыночная сущность с явным рынком (как email_template). Тексты KZ утверждены оператором
-- 2026-10-19; updated_at пусто — «не правили» (тест сида сверяет точные тексты только у нетронутых строк).
CREATE TABLE reply_template (
    id          BIGSERIAL PRIMARY KEY,
    market      VARCHAR(2)   NOT NULL,
    title       VARCHAR(60)  NOT NULL,
    body        TEXT         NOT NULL,
    role        VARCHAR(20),
    position    INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100)
);
CREATE UNIQUE INDEX ux_reply_template_role ON reply_template (market, role) WHERE role IS NOT NULL;
CREATE INDEX idx_reply_template_market ON reply_template (market, position);

INSERT INTO reply_template (market, title, body, role, position) VALUES
('KZ', 'Подпись', E'С уважением,\n{{сотрудник}}\nWest-Med\n+7 777 075 27 70 · westmed.kz', 'SIGNATURE', 0),
('KZ', 'Первое письмо', E'Здравствуйте!\n\nСпасибо за обращение в West-Med. Подскажите, пожалуйста, кто конечный заказчик: название учреждения и город?', 'FIRST_REPLY', 1),
('KZ', 'Запрос принят', E'Здравствуйте!\n\nЗапрос получили, готовим коммерческое предложение.', NULL, 2),
('KZ', 'КП во вложении', E'Здравствуйте!\n\nНаправляем коммерческое предложение во вложении.', 'OFFER', 3),
('KZ', 'Напомнить о КП', E'Здравствуйте!\n\nПодскажите, пожалуйста, удалось ли рассмотреть наше коммерческое предложение?', NULL, 4);
