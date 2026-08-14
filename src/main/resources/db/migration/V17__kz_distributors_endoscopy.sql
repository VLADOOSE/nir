-- V17: поставщики под эндоскопию/медтехнику СК-Фармации (2026-08-14).
--
-- Повод: активный тендер 522544-1 (АО «КазМедТех», лизинг) требует «Видеоэндоскопическую систему
-- VISERA ELITE III» Olympus по РУ РК МИ (МТ)-0№028555, а в КП-панель попадали только два поставщика.
--
-- Источники по каждому утверждению — в комментариях к строкам. Правило §16: только реальное
-- с сайта компании или из документа площадки, бренды/почты не выдумываем.
-- ⚠ Где почта не подтверждена — оставлен NULL: запись КП создастся и без письма (reason NO_EMAIL),
--   это честнее выдуманного адреса.

-- ═══════════ 1. MEDSYST: добавлен Olympus ═══════════
-- Источник: medsyst.kz/brands/olympus — каталог линейки Olympus (гастро-/коло-/бронхоскопы,
-- видеосистемы EVIS EXERA III). В V9 у них стоял только Pentax, из-за чего официальный дистрибьютор
-- Olympus не попадал в подбор поставщиков по эндоскопическим лотам.
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, 'Olympus' FROM distributor d
WHERE d.market = 'KZ' AND d.name = 'ТОО «MEDSYST (МЕДСИСТ)»'
  AND NOT EXISTS (SELECT 1 FROM distributor_brand b WHERE b.distributor_id = d.id AND b.brand = 'Olympus');

-- ═══════════ 2. Два проверенных участника тендеров КазМедТеха ═══════════
-- Источник: протокол итогов тендера СК-Фармации 514052-1 от 2026-02-05 (fms.ecc.kz, вкладка
-- «Протоколы», файл 47854089) — там же БИН и адреса. Это не догадка о профиле компании,
-- а факт: обе фирмы допущены к аукциону и выиграли лоты медтехники у этого же лизингодателя.
INSERT INTO distributor (name, inn, address, website, email, phone, market) VALUES
  ('ТОО «ATLANT MT»',              '110540019744', 'г. Астана, ул. Бейімбет Майлин, 4/1, оф. 117',                    'https://atlantmt.kz',            'info@atlantmt.kz', '+7 7172 97 83 97', 'KZ'),  -- победитель лота «Система видеоэндоскопической визуализации» (61 985 000 ₸, Pentax EPK-i5500c); контакты — atlantmt.kz
  ('ТОО «Life Medical Solutions»', '031140016493', 'г. Алматы, Алмалинский район, пр. Сейфуллина, 404/67',            'https://lifemedicalsolutions.kz', NULL,               NULL,                'KZ')   -- победитель рентген-лотов (75 874 766 ₸, Browiner CompaX 500A); ⚠ почта/телефон на сайте не подтверждены — сайт не открылся
ON CONFLICT (name) DO NOTHING;

-- ═══════════ 3. Бренды новых ═══════════
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, x.brand FROM distributor d JOIN (VALUES
  -- ТОО «ATLANT MT» — портфель с atlantmt.kz (раздел «Эндоскопия», видеопроцессор OPTIVISTA EPK-i7010)
  ('ТОО «ATLANT MT»','Pentax Medical'),('ТОО «ATLANT MT»','BOWA'),('ТОО «ATLANT MT»','Dornier MedTech'),
  ('ТОО «ATLANT MT»','EMS'),('ТОО «ATLANT MT»','Hitachi'),('ТОО «ATLANT MT»','Medivators'),
  ('ТОО «ATLANT MT»','Melag'),('ТОО «ATLANT MT»','Misonix'),('ТОО «ATLANT MT»','PFM Medical'),
  -- ТОО «Life Medical Solutions» — бренд из протокола итогов (что реально поставили)
  ('ТОО «Life Medical Solutions»','Shenzhen Browiner')
) AS x(dname, brand) ON d.name = x.dname AND d.market = 'KZ'
WHERE NOT EXISTS (SELECT 1 FROM distributor_brand b WHERE b.distributor_id = d.id AND b.brand = x.brand);

-- ═══════════ 4. Виды МИ (имена — ТОЧНО как в equipment_type, иначе join молча теряет строку) ═══════════
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d
JOIN (VALUES
  ('ТОО «ATLANT MT»','Эндоскоп'),('ТОО «ATLANT MT»','Хирургическое оборудование'),('ТОО «ATLANT MT»','Стерилизация и дезинфекция'),
  ('ТОО «Life Medical Solutions»','Рентген'),('ТОО «Life Medical Solutions»','Хирургическое оборудование'),('ТОО «Life Medical Solutions»','Неонатальное оборудование')
) AS x(dname, tname) ON d.name = x.dname AND d.market = 'KZ'
JOIN equipment_type et ON et.name = x.tname
WHERE NOT EXISTS (SELECT 1 FROM distributor_equipment_type dt WHERE dt.distributor_id = d.id AND dt.equipment_type_id = et.id);
