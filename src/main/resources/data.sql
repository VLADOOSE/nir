-- Тестовые данные (DEV ONLY)

-- 0. Типы оборудования (DataInitializer тоже умеет, но в data.sql проще для отладки)
INSERT INTO equipment_type (name) VALUES ('УЗИ'), ('Рентген'), ('ИВЛ'), ('Монитор');

-- 1. Медучреждения
INSERT INTO facility (name, inn, address, last_name, first_name, middle_name, phone, email) VALUES
('Городская больница №1', '7701234567', 'г. Москва, ул. Ленина, 10', 'Иванов', 'Иван', 'Иванович', '+7 (495) 111-11-11', 'ivanov@gb1.ru'),
('Поликлиника №5', '7702345678', 'г. Москва, ул. Мира, 25', 'Петрова', 'Анна', 'Сергеевна', '+7 (495) 222-22-22', 'petrova@pol5.ru'),
('Областной клинический центр', '5001234567', 'г. Подольск, ул. Советская, 3', 'Сидоров', 'Виктор', 'Павлович', '+7 (496) 333-33-33', 'sidorov@okc.ru'),
('Детская больница №3', '7703456789', 'г. Москва, пр. Вернадского, 82', 'Козлова', 'Елена', 'Николаевна', '+7 (495) 444-44-44', 'kozlova@db3.ru'),
('Центральная районная больница', '6101234567', 'г. Ростов-на-Дону, ул. Пушкина, 15', 'Морозов', 'Дмитрий', 'Андреевич', '+7 (863) 555-55-55', 'morozov@crb.ru');

-- 2. Дистрибьюторы (специализация выставится через UI; по умолчанию все = универсалы)
INSERT INTO distributor (name, inn, address, last_name, first_name, middle_name, phone, email, website) VALUES
('МедТехСнаб', '7711111111', 'г. Москва, ул. Промышленная, 5', 'Алексеев', 'Константин', 'Михайлович', '+7 (495) 600-00-01', 'alexeev@medtechsnab.ru', 'https://medtechsnab.ru'),
('Глобал Медикал', '7722222222', 'г. Москва, ул. Академика Королёва, 12', 'Белова', 'Ольга', 'Дмитриевна', '+7 (495) 600-00-02', 'belova@globalmed.ru', 'https://globalmedical.ru'),
('СириусМед', '7733333333', 'г. Санкт-Петербург, Невский пр., 100', 'Волков', 'Роман', 'Сергеевич', '+7 (495) 600-00-03', 'volkov@siriusmed.ru', 'https://siriusmed.ru'),
('Медицинские технологии', '7744444444', 'г. Москва, ул. Тверская, 30', 'Громова', 'Татьяна', 'Владимировна', '+7 (495) 600-00-04', 'gromova@medtech.ru', 'https://medtech.ru');

-- 3. Каталог оборудования
INSERT INTO med_equipment (name, manufact, equip_type_id, length_mm, width_mm, height_mm, weight_kg, spec) VALUES
('Аппарат УЗИ SonoAce R7', 'Samsung Medison', (SELECT id FROM equipment_type WHERE name='УЗИ'), 520, 480, 1350, 85.00, 'Портативный УЗИ аппарат с цветным допплером'),
('Аппарат УЗИ Vivid T8', 'GE Healthcare', (SELECT id FROM equipment_type WHERE name='УЗИ'), 550, 500, 1400, 95.00, 'Ультразвуковой сканер кардиологический'),
('Рентген-аппарат Luminos dRF', 'Siemens', (SELECT id FROM equipment_type WHERE name='Рентген'), 2100, 1800, 2200, 450.00, 'Цифровой рентгенографический комплекс'),
('Рентген-аппарат ProMax 3D', 'Planmeca', (SELECT id FROM equipment_type WHERE name='Рентген'), 1100, 1200, 1600, 180.00, 'Панорамный дентальный рентген с 3D'),
('Аппарат ИВЛ Авента-М', 'УОМЗ', (SELECT id FROM equipment_type WHERE name='ИВЛ'), 400, 350, 1200, 45.00, 'Аппарат искусственной вентиляции лёгких'),
('Аппарат ИВЛ Hamilton C6', 'Hamilton Medical', (SELECT id FROM equipment_type WHERE name='ИВЛ'), 380, 300, 1500, 32.00, 'Интеллектуальная вентиляция с адаптивной поддержкой'),
('Монитор пациента iMEC 12', 'Mindray', (SELECT id FROM equipment_type WHERE name='Монитор'), 350, 280, 300, 5.50, 'Прикроватный монитор с 12 отведениями ЭКГ'),
('Монитор пациента IntelliVue MX500', 'Philips', (SELECT id FROM equipment_type WHERE name='Монитор'), 370, 310, 330, 6.20, 'Модульный монитор пациента');

-- 4. Пользователи создаются DataInitializer'ом (BCrypt-хэши паролей)

-- 5. Тендеры
INSERT INTO tender (tender_number, facility_id, status, purchase_type, deadline, publish_date, total_cost, description, delivery_address, contact_last_name, contact_first_name, contact_middle_name, contact_phone, contact_email) VALUES
('0373100065021000041', 1, 'ACTIVE', 'ELECTRONIC_AUCTION', '2026-08-01', '2026-04-01', 2050000.00, 'Поставка ультразвукового диагностического оборудования для нужд ГБУЗ "Городская больница №1"', 'г. Москва, ул. Ленина, 10', 'Иванов', 'Иван', 'Иванович', '+7 (495) 111-11-11', 'zakupki@gb1.ru'),
('0373100065021000042', 2, 'ACTIVE', 'ELECTRONIC_AUCTION', '2026-09-15', '2026-04-15', 5000000.00, 'Поставка рентгенографического оборудования для нужд ГБУЗ "Поликлиника №5"', 'г. Москва, ул. Мира, 25', 'Петрова', 'Анна', 'Сергеевна', '+7 (495) 222-22-22', 'tender@pol5.ru'),
('0373100065021000043', 3, 'DRAFT', 'PAPER_TENDER', '2026-10-20', '2026-05-01', 1000000.00, 'Поставка аппаратов искусственной вентиляции лёгких для нужд ГБУЗ "Областной клинический центр"', 'г. Подольск, ул. Советская, 3', 'Сидоров', 'Виктор', 'Павлович', '+7 (496) 333-33-33', 'med@okc.ru');

-- 6. Лоты тендеров
INSERT INTO tender_lot (tender_id, lot_number, equip_name, equip_type_id, quantity, max_cost, max_length_mm, max_width_mm, max_height_mm, max_weight_kg, required_spec) VALUES
(1, 1, 'Аппарат УЗИ', (SELECT id FROM equipment_type WHERE name='УЗИ'), 2, 1100000.00, 600, 550, 1500, 100.00, 'Портативный, с допплером'),
(1, 2, 'Монитор пациента', (SELECT id FROM equipment_type WHERE name='Монитор'), 3, 350000.00, 400, 350, 400, 7.00, '12 отведений ЭКГ'),
(2, 1, 'Рентген-аппарат', (SELECT id FROM equipment_type WHERE name='Рентген'), 1, 4000000.00, 2500, 2000, 2500, 500.00, 'Цифровой комплекс'),
(2, 2, 'Монитор пациента', (SELECT id FROM equipment_type WHERE name='Монитор'), 2, 600000.00, 400, 350, 400, 8.00, 'Модульный монитор'),
(3, 1, 'Аппарат ИВЛ', (SELECT id FROM equipment_type WHERE name='ИВЛ'), 3, 500000.00, 450, 400, 1600, 50.00, 'С адаптивной поддержкой');
