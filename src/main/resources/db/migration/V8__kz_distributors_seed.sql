-- V8: реальные KZ-дистрибьюторы медтехники (веб-ресёрч 2026-07-07).
-- Заменяют 2 демо-фейка из V2. email оставлен NULL — пользователь верифицирует реальные адреса перед любой рассылкой (в чат не эхо-печатать).
-- Каждый дистрибьютор реальный (ТОО), источник (URL сайта компании) указан в комментарии у INSERT-строки.
-- Виды МИ смапплены на точные имена equipment_type (V1 + V7); брендов у distributor_brand НЕТ уникального ограничения → обычный INSERT (первичный сид, дублей нет).

-- ── 1. Чистим демо-фейки из V2 (id 11/12) вместе со всей их демо-обвязкой ──
-- price_request.distributor_id — FK БЕЗ ON DELETE CASCADE → сначала удаляем демо-КП фейков
-- (их price_request_item уходят по ON DELETE CASCADE). Это только демо-строки V2, привязанные к фейкам.
DELETE FROM price_request WHERE distributor_id IN
  (SELECT id FROM distributor WHERE market = 'KZ' AND name IN ('ТОО «МедСнаб Казахстан»', 'ТОО «Алматы Медтехника»'));
-- Явно чистим brand/type-привязки (подстрахованы FK ON DELETE CASCADE, но делаем детерминированно).
DELETE FROM distributor_brand WHERE distributor_id IN
  (SELECT id FROM distributor WHERE market = 'KZ' AND name IN ('ТОО «МедСнаб Казахстан»', 'ТОО «Алматы Медтехника»'));
DELETE FROM distributor_equipment_type WHERE distributor_id IN
  (SELECT id FROM distributor WHERE market = 'KZ' AND name IN ('ТОО «МедСнаб Казахстан»', 'ТОО «Алматы Медтехника»'));
DELETE FROM distributor WHERE market = 'KZ' AND name IN ('ТОО «МедСнаб Казахстан»', 'ТОО «Алматы Медтехника»');

-- ── 2. Реальные дистрибьюторы (market='KZ', email=NULL — verify) ──
INSERT INTO distributor (name, address, website, market) VALUES
  ('ТОО «MEDSYST (МЕДСИСТ)»',              'г. Алматы, ул. Керей-Жанибек хандар, 117А',                 'https://medsyst.kz',                'KZ'),  -- источник: https://medsyst.kz — офиц. дистрибьютор GE Healthcare, Mindray, Dräger, Canon
  ('ТОО «РАХМЕД АЗИЯ»',                    'г. Алматы, ул. Ораз Жандосова, 60А',                        'https://rahmed.kz',                 'KZ'),  -- источник: https://rahmed.kz — офиц. дистрибьютор PHILIPS в Казахстане
  ('ТОО «НЕОМЕДРЕМ»',                      'г. Астана, ул. Кордай, 2, НП 5',                            'https://neomedrem.kz',              'KZ'),  -- источник: https://neomedrem.kz — Mindray, BTL, Chirana; >80 направлений техники
  ('ТОО «ВЗМед»',                          'г. Астана, ул. Кабанбай батыра, 13, оф. 81',                'https://www.vz-med.kz',             'KZ'),  -- источник: https://www.vz-med.kz — ECORAY, Bemems, TVT, JUSHA (рентген, стерилизация)
  ('ТОО «Медико-Инновационные Технологии»','г. Алматы, ул. Наурызбай батыра, 8, БЦ Коба',               'https://mindray.medico-intech.kz',  'KZ'),  -- источник: https://mindray.medico-intech.kz — офиц. дистрибьютор MINDRAY в РК
  ('ТОО «Искра Трэйдинг»',                 'г. Алматы, ул. Джалалабадская, 11',                         'https://iskra-trading.kz',          'KZ'),  -- источник: https://iskra-trading.kz — офиц. дилер Pentax (эндоскопия) в Казахстане
  ('ТОО «Астра-Дент»',                     'г. Алматы, пр. Абая, 58А, оф. 7',                           'https://astradent.kz',              'KZ'),  -- источник: https://astradent.kz — KaVo, Sirona, Planmeca, NSK, Diplomat (стоматология)
  ('ТОО «Жетысу-Мед»',                     'г. Алматы, мкр. Шугыла, ул. Жуалы, 12-5',                   'https://zhetysu-med.kz',            'KZ'),  -- источник: https://zhetysu-med.kz — Chirana, SLE, MOOG (реанимация, неонатал, хирургия)
  ('ТОО «BTL Kazakhstan»',                 'г. Алматы',                                                 'https://www.btlmed.kz',             'KZ'),  -- источник: https://www.btlmed.kz — офиц. представитель BTL (физиотерапия, реабилитация)
  ('ТОО «БиоХимПрибор»',                   'г. Алматы, пр. Суюнбая, 89Г',                               'https://bhp.kz',                    'KZ')   -- источник: https://bhp.kz — Eppendorf, Randox, IKA, Biobase (лаб. анализаторы, реактивы)
ON CONFLICT (name) DO NOTHING;

-- ── 3. Бренды дистрибьюторов (distributor_brand — БЕЗ ON CONFLICT: нет уникального ограничения) ──
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('GE Healthcare'),('Mindray'),('Dräger'),('Canon'),('SonoScape'),('Schiller'),('Pentax')) AS x(b)
WHERE d.name = 'ТОО «MEDSYST (МЕДСИСТ)»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Philips')) AS x(b)
WHERE d.name = 'ТОО «РАХМЕД АЗИЯ»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Mindray'),('BTL'),('Chirana')) AS x(b)
WHERE d.name = 'ТОО «НЕОМЕДРЕМ»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('ECORAY'),('Bemems'),('TVT'),('JUSHA')) AS x(b)
WHERE d.name = 'ТОО «ВЗМед»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Mindray')) AS x(b)
WHERE d.name = 'ТОО «Медико-Инновационные Технологии»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Pentax')) AS x(b)
WHERE d.name = 'ТОО «Искра Трэйдинг»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('KaVo'),('Sirona'),('Planmeca'),('NSK'),('Diplomat')) AS x(b)
WHERE d.name = 'ТОО «Астра-Дент»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Chirana'),('SLE'),('MOOG')) AS x(b)
WHERE d.name = 'ТОО «Жетысу-Мед»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('BTL')) AS x(b)
WHERE d.name = 'ТОО «BTL Kazakhstan»';
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Eppendorf'),('Randox'),('IKA'),('Biobase')) AS x(b)
WHERE d.name = 'ТОО «БиоХимПрибор»';

-- ── 4. Виды МИ (маппинг на equipment_type по ТОЧНОМУ имени — иначе join молча теряет строку) ──
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «MEDSYST (МЕДСИСТ)»'
  AND et.name IN ('УЗИ','ИВЛ','Компьютерный томограф','Магнитно-резонансный томограф','Монитор пациента','Рентген','Эндоскоп','Дефибриллятор','ЭКГ','Анестезия и реанимация')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «РАХМЕД АЗИЯ»'
  AND et.name IN ('УЗИ','Рентген','Эндоскоп','Хирургическое оборудование','Анестезия и реанимация')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «НЕОМЕДРЕМ»'
  AND et.name IN ('УЗИ','ЭКГ','ИВЛ','Монитор пациента','Дефибриллятор','Хирургическое оборудование','Стерилизация и дезинфекция','Физиотерапия','Лабораторный анализатор','Эндоскоп','Неонатальное оборудование','Реабилитационное оборудование','Анестезия и реанимация')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «ВЗМед»'
  AND et.name IN ('Рентген','Стерилизация и дезинфекция','Монитор пациента','Медицинская мебель')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «Медико-Инновационные Технологии»'
  AND et.name IN ('УЗИ','Анестезия и реанимация','ИВЛ')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «Искра Трэйдинг»'
  AND et.name IN ('Эндоскоп','Рентген','УЗИ','Анестезия и реанимация')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «Астра-Дент»'
  AND et.name IN ('Стоматологическое оборудование','Рентген')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «Жетысу-Мед»'
  AND et.name IN ('Анестезия и реанимация','Неонатальное оборудование','Монитор пациента','Хирургическое оборудование')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «BTL Kazakhstan»'
  AND et.name IN ('Физиотерапия','Реабилитационное оборудование','ЭКГ')
ON CONFLICT DO NOTHING;
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «БиоХимПрибор»'
  AND et.name IN ('Лабораторный анализатор')
ON CONFLICT DO NOTHING;
