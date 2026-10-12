-- V28: пять производителей брендов westmed.kz (спека 2026-10-12 §3.3, §6.1) — запрос цен по бренду строки
-- по умолчанию уходит производителю, дистрибьюторы бренда видны без отметки.
-- Адреса — с официальных сайтов производителей, проверены 2026-10-12 (исследование
-- .superpowers/research/2026-10-12-westmed-manufacturers.md: URL, дословные цитаты, MX-записи доменов),
-- утверждены оператором 2026-10-12 (у ЭМА — оба адреса). Дилеры этих брендов в РК не заводятся (решение оператора).
-- Название поставщика уникально по всем рынкам: заведённого руками с тем же названием не задваиваем — почту и
-- сайт пишем только в пустые поля, отметку «производитель», бренды и виды МИ добавляем.
-- Бренды — короткие формы: BrandMatch ищет бренд поставщика ВНУТРИ бренда строки («ЭМА» ловит и «Завод ЭМА»
-- с сайта, и «ЭМА», вписанное руками). Латиница — только у тех, кто ею пользуется сам (FOTEK, Axion), без «Orion»
-- и «EMA»: они поймали бы Orion Diagnostica и случайные слова.
-- Виды МИ — по ТОЧНЫМ именам equipment_type (V1 + V7): id локально и на проде разные. Продукция — по каталогам сайтов.

INSERT INTO distributor (name, address, website, email, last_name, first_name, middle_name, market, manufacturer) VALUES
  -- https://fotek.ru/contacts/ — «Центральный офис / 620078, Екатеринбург, ул. Малышева, стр. 145а / … / E-mail / fotek@fotek.ru»,
  -- единственный общий адрес производителя (ИНН 6660014314), электрохирургия и стойки СП1/СП2
  ('ООО «ФОТЕК»', 'Россия, 620078, г. Екатеринбург, ул. Малышева, стр. 145а', 'https://fotek.ru', 'fotek@fotek.ru',
   NULL, NULL, NULL, 'KZ', TRUE),
  -- https://ema.su/kontakty/ — «Отдел продаж «Светильники» / … / Страны СНГ / … / sales7@ema.su»,
  -- «Отдел продаж «Физиотерапия, неонатология, реанимация, бактерицидные рециркуляторы и облучатели» / … / sales9@ema.su»
  ('ЗАО «Завод ЭМА»', 'Россия, 620028, г. Екатеринбург, Верх-Исетский бульвар, 13Н', 'https://ema.su',
   'sales7@ema.su, sales9@ema.su', NULL, NULL, NULL, 'KZ', TRUE),
  -- https://axion-med.ru/contacts/ — «Борзенков Александр Алексеевич / Специалист отдела продаж медицинской техники /
  -- manager5@c.axion.ru / … / Азербайджан, Армения, Беларусь, Грузия, Казахстан, …»
  ('ООО Концерн «Аксион»', 'Россия, 426000, г. Ижевск, ул. М. Горького, 90', 'https://axion-med.ru', 'manager5@c.axion.ru',
   'Борзенков', 'Александр', 'Алексеевич', 'KZ', TRUE),
  -- https://orion-si.ru/contacts/ — «E-mail / orionsi@yandex.ru», единственный адрес сайта (домен yandex.ru, но его
  -- публикует сам производитель, ИНН 7727179138), озоновые стерилизаторы и озонаторы
  ('ООО «Орион-Си»', 'Россия, 115419, г. Москва, ул. Орджоникидзе, д. 11', 'https://orion-si.ru', 'orionsi@yandex.ru',
   NULL, NULL, NULL, 'KZ', TRUE),
  -- https://www.monitor-ltd.ru/kontaktyi — «Начальник отдела продаж, менеджер по … Казахстану … / Омельченко Евгений
  -- Сергеевич / … / genya@monitor-ltd.ru», ЭКГ, холтеры, спирографы, мониторы
  ('ООО «НПП «Монитор»»', 'Россия, 344068, г. Ростов-на-Дону, ул. Краснокурсантская, 104а', 'https://www.monitor-ltd.ru',
   'genya@monitor-ltd.ru', 'Омельченко', 'Евгений', 'Сергеевич', 'KZ', TRUE)
ON CONFLICT (name) DO UPDATE SET
  email        = CASE WHEN distributor.email IS NULL OR btrim(distributor.email) = '' THEN EXCLUDED.email ELSE distributor.email END,
  website      = CASE WHEN distributor.website IS NULL OR btrim(distributor.website) = '' THEN EXCLUDED.website ELSE distributor.website END,
  manufacturer = TRUE;

INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, x.brand FROM distributor d JOIN (VALUES
  ('ООО «ФОТЕК»', 'Фотек'), ('ООО «ФОТЕК»', 'FOTEK'),
  ('ЗАО «Завод ЭМА»', 'ЭМА'),
  ('ООО Концерн «Аксион»', 'Аксион'), ('ООО Концерн «Аксион»', 'Axion'),
  ('ООО «Орион-Си»', 'Орион'),
  ('ООО «НПП «Монитор»»', 'Монитор')
) AS x(name, brand) ON d.name = x.name
WHERE NOT EXISTS (SELECT 1 FROM distributor_brand b WHERE b.distributor_id = d.id AND lower(b.brand) = lower(x.brand));

INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d JOIN (VALUES
  ('ООО «ФОТЕК»', 'Хирургическое оборудование'),
  ('ЗАО «Завод ЭМА»', 'Хирургическое оборудование'), ('ЗАО «Завод ЭМА»', 'Стерилизация и дезинфекция'),
  ('ЗАО «Завод ЭМА»', 'Физиотерапия'), ('ЗАО «Завод ЭМА»', 'Неонатальное оборудование'),
  ('ЗАО «Завод ЭМА»', 'Анестезия и реанимация'),
  ('ООО Концерн «Аксион»', 'Дефибриллятор'), ('ООО Концерн «Аксион»', 'ЭКГ'), ('ООО Концерн «Аксион»', 'ИВЛ'),
  ('ООО Концерн «Аксион»', 'Анестезия и реанимация'), ('ООО Концерн «Аксион»', 'Неонатальное оборудование'),
  ('ООО Концерн «Аксион»', 'Физиотерапия'),
  ('ООО «Орион-Си»', 'Стерилизация и дезинфекция'), ('ООО «Орион-Си»', 'Физиотерапия'),
  ('ООО «НПП «Монитор»»', 'ЭКГ'), ('ООО «НПП «Монитор»»', 'Монитор пациента')
) AS x(name, type) ON d.name = x.name
JOIN equipment_type et ON et.name = x.type
ON CONFLICT DO NOTHING;
