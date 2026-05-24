# CLAUDE.md — АИС учёта участия в тендерах на медицинское оборудование

## Обзор проекта

Дипломный проект: автоматизированная информационная система (АИС) для учёта участия торговой компании ООО «Регион-Мед» в тендерах на закупку медицинского оборудования.

Система решает задачу автоматизации подбора оборудования из внутреннего каталога под требования тендеров — это **система поддержки принятия решений**, а не просто CRUD.

## Стек технологий

- **Backend:** Java 17, Spring Boot 3.5.x, Spring Web (REST API), Spring Data JPA (Hibernate), Lombok
- **Frontend:** Angular (переписывается с Next.js)
- **Database:** PostgreSQL, нормализация до 3НФ
- **Сборка:** Gradle
- **Архитектура:** Монолит, layered (Controller → Service → Repository → DTO), REST API, JSON

## Структура проекта

```
nir2/
├── build.gradle
├── settings.gradle
├── src/main/java/com/vladoose/nir/
│   ├── Nir2Application.java
│   ├── config/
│   ├── controller/
│   ├── dto/
│   ├── entity/
│   ├── exception/
│   ├── mapper/
│   ├── repository/
│   └── service/
├── src/main/resources/
│   ├── application.yaml
│   └── schema.sql
└── frontend/          # Angular (в процессе миграции с Next.js)
```

## Модель данных (8 сущностей)

### Справочники
- **facility** — медучреждения-заказчики (id, name UK, inn, address, contact)
- **distributor** — дистрибьюторы/поставщики (id, name UK, inn UK, contact)
- **med_equipment** — каталог оборудования (id, name, manufact, equip_type, cost, length_mm, width_mm, height_mm, weight_kg, spec)
- **user_account** — пользователи системы (id, username UK, full_name, role)

### Слой требований (что хочет заказчик)
- **tender** — тендер (id, tender_number, facility_id FK, status, deadline, total_cost, description)
- **tender_lot** — лот тендера (id, tender_id FK, lot_number, equip_name, equip_type, quantity, max_cost, max_length_mm, max_width_mm, max_height_mm, max_weight_kg, required_spec)

### Слой предложений (что мы предлагаем)
- **activity_apply** — заявка на тендер (id, tender_id FK, status, created_at)
- **apply_item** — позиция заявки (id, apply_id FK, tender_lot_id FK, med_equip_id FK, distributor_id FK, offered_cost, quantity)

### Связи
```
FACILITY 1:N TENDER
TENDER 1:N TENDER_LOT
TENDER 1:N ACTIVITY_APPLY
ACTIVITY_APPLY 1:N APPLY_ITEM
TENDER_LOT 1:N APPLY_ITEM
MED_EQUIPMENT 1:N APPLY_ITEM
DISTRIBUTOR 1:N APPLY_ITEM
```

## Ключевая фича — автоматический подбор оборудования

При просмотре лота тендера система фильтрует каталог `med_equipment` по параметрам:
- `equip_type` — совпадение типа
- `length_mm <= max_length_mm` — габариты вписываются
- `width_mm <= max_width_mm`
- `height_mm <= max_height_mm`
- `weight_kg <= max_weight_kg`
- `cost <= max_cost` — цена в бюджете

Все ограничения nullable (если заказчик не указал — фильтр не применяется). Результат ранжируется по цене ASC.

## Бизнес-процесс

1. **Регистрация тендера** — номер, дедлайн, привязка к facility, статус «Новый»
2. **Разбор лотов** — текстовые требования + габаритные ограничения из тендерной документации
3. **Подбор оборудования** — автоматический по параметрам из каталога med_equipment
4. **Формирование заявки** — ACTIVITY_APPLY + APPLY_ITEM, статусы: Черновик → Подана → Выиграна / Отклонена

## Статусы

### Тендер
- Новый → Анализ → Заявка подана → Завершён (выигран/проигран)

### Заявка (activity_apply)
- Черновик → Подана → Выиграна / Отклонена

## Что было удалено из старой схемы (и почему)

| Удалено | Причина |
|---------|---------|
| `tender_step` | Избыточно — логика через статус тендера |
| `tender_founder` | Не использовался, заменён на description |
| `company`, `company_member` | Отслеживание конкурентов не нужно |
| `med_equipment_request` | Заменена на `tender_lot` |
| `med_equipment_offer` | Заменена на `apply_item` |
| Составные PK | Заменены на простые BIGSERIAL — упрощение JPA |
| `items_json` в activity_apply | Нарушение 1НФ — нормализовано в `apply_item` |

## Правила разработки

- Все первичные ключи — `BIGSERIAL` (простые, без `@IdClass`)
- Entity используют Lombok (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`)
- Валидация — пока убрана, будет добавлена позже
- MVP без DTO — контроллеры принимают entity напрямую через `@RequestBody`
- Именование таблиц — snake_case, полей entity — camelCase
- `ddl-auto: none`, schema.sql управляет схемой (dev: пересоздание при каждом запуске)
- Constructor injection без `@Autowired`, `@Transactional` на методах записи

## Контекст: дипломная работа

Это проект для защиты диплома. Параллельно пишется пояснительная записка (отдельный чат в проекте). Форматирование записки: Times New Roman 14pt, 1.5 интервал, отступ 1.25см. Генерация docx через Node.js библиотеку `docx`.

## Текущее состояние проекта

Backend полностью перестроен и работает:
- schema.sql: 9 таблиц (facility, distributor, med_equipment, user_account, tender, tender_lot, activity_apply, apply_item, **price_request**)
- 9 entity-классов с Lombok, простые BIGSERIAL PK, без @IdClass
- 9 репозиториев (включая JPQL findMatchingEquipment в MedEquipmentRepository, searchTenders в TenderRepository, countByEquipType в TenderLotRepository, distributorStats в ApplyItemRepository)
- 10 сервисов: 9 CRUD + ReportService + EmailService
- 11 REST-контроллеров (включая PriceRequestController, ReportController, EmailController)
- data.sql с тестовыми данными (5 учреждений, 4 дистрибьютора, 8 единиц оборудования, 2 пользователя, 3 тендера, 5 лотов)
- SecurityConfig: csrf disabled, всё permitAll (dev)
- spring.jpa.hibernate.ddl-auto: none, schema.sql управляет схемой
- Gradle 8.14, Spring Boot стартует на порту 8080

### Ключевые доработки

- **PriceRequest** — 9-я сущность, таблица `price_request`. Workflow статусов: CREATED → SENT → RESPONDED → ACCEPTED → REJECTED. Связана с tender_lot, med_equipment, distributor.
- **Нормализация контактов** — в facility, distributor, tender контактные данные разнесены на отдельные поля: `last_name`, `first_name`, `middle_name`, `phone`, `email` (для tender — с префиксом `contact_`). Это устраняет нарушение 1НФ.
- **EmailService + EmailController** — отправка КП дистрибьюторам через JavaMailSender (`spring-boot-starter-mail`). Конфигурация SMTP в application.yaml через переменные окружения `MAIL_USERNAME` / `MAIL_PASSWORD`. Endpoint `POST /api/email/send`.
- **Расширенный серверный поиск тендеров** — `GET /api/tenders/search` с 7 фильтрами (status, facilityId, equipType, minCost, maxCost, dateFrom, dateTo) через JPQL JOIN на лоты.
- **Отчёты** — `GET /api/reports/{tender-stats,equipment-demand,distributor-stats}` через ReportService с агрегациями GROUP BY/COUNT/AVG.

### Frontend (Angular)

- **Дашборд** (главная) — счётчики по статусам, ближайшие дедлайны, спрос на типы оборудования (progress bars), последние заявки.
- **Глобальный поиск в шапке** — `SearchService` с `forkJoin` параллельно по 4 сущностям (тендеры/оборудование/учреждения/дистрибьюторы) с `catchError`, фильтрация на клиенте, выпадающий список с цветными бейджами.
- **Расширенный поиск тендеров** — отдельная страница с серверной фильтрацией.
- **Страница отчётов** — три секции с горизонтальными bar-чартами и таблицами.
- **Workflow КП в детальном просмотре тендера** — кнопка «Запросить КП» в строке лота → форма выбора оборудования и дистрибьютора → автогенерация шаблона email с реквизитами лота/тендера → 3 действия (копировать в буфер / отправить из системы через `/api/email/send` / открыть mailto) → ручное обновление статуса КП → при ACCEPTED появляется кнопка «В заявку», создающая позицию в черновике activity_apply.
- **Поддержка `?openId=...`** — переходы на детали тендера с дашборда, поиска и глобального поиска.

## API endpoints

- GET/POST /api/facilities, GET/PUT/DELETE /api/facilities/{id}
- GET/POST /api/distributors, GET/PUT/DELETE /api/distributors/{id}
- GET/POST /api/equipment, GET/PUT/DELETE /api/equipment/{id}, GET /api/equipment/match/{lotId}
- GET/POST /api/users, GET/PUT/DELETE /api/users/{id}
- GET/POST /api/tenders, GET/PUT/DELETE /api/tenders/{id}, GET /api/tenders/{id}/lots, GET /api/tenders/{id}/applies
- GET/POST /api/lots, GET/PUT/DELETE /api/lots/{id}
- GET/POST /api/applies, GET/PUT/DELETE /api/applies/{id}, GET /api/applies/{id}/items
- GET/POST /api/apply-items, GET/PUT/DELETE /api/apply-items/{id}

## Следующий шаг

Создание Angular-фронтенда под новый API.
