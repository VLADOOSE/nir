# Обращения: приём заявок с westmed.kz + ручной ввод звонков/WhatsApp — дизайн

**Дата:** 2026-09-27
**Статус:** утверждён оператором (разделы 1–3 согласованы в чате 2026-09-27)
**Контекст:** CLAUDE.md §1 (West-Med, поток «Частники»), §6 (многорыночность, гард фоновых потоков), §7 (лоты — через коллекцию), §8 блок B (частные заявки), §9 (почта), §16 (бэклог: «Авто-резолв клиента по адресу отправителя», «КП-генератор клиенту»).
**Запрос оператора:** расширить АИС с госзакупок на коммерцию, начать с West-Med: «по нему регулярно пишут, звонят и просят найти какое-нибудь оборудование». Для начала — чтобы заявки с сайта westmed.kz высвечивались в АИС. Сайт при этом менять минимально. Заложить будущий бизнес-номер WhatsApp и интеграцию звонков.

## 1. Решения, принятые в обсуждении

| # | Вопрос | Решение |
|---|---|---|
| 1 | Где ведутся заявки с сайта | **В АИС.** Статус АИС сама пишет обратно на сайт тем же API, что и админка сайта, — админка сайта не врёт |
| 2 | Объём первого этапа | **Сайт westmed.kz + ручной ввод звонков и WhatsApp** в одном реестре «Обращения» |
| 3 | Механизм приёма | **API админки сайта** (опрос). Код сайта не меняется ни строкой |
| 4 | WhatsApp Business и АТС | **Задел в модели и точке входа сейчас**, сами адаптеры — отдельными этапами, когда появятся бизнес-номер и АТС |
| 5 | Доступ без Tailscale | Отдельный блок сразу после этого: свой домен + обязательный хардинг входа + «калитка» по коду устройства (§14) |

Отвергнутые механизмы приёма (варианты 2–4 из обсуждения):

- **Чтение БД сайта read-only.** Привязывает АИС к внутренней схеме сайта (любая миграция сайта молча ломает приём), требует сцепки Docker-сетей, не даёт записать статус обратно (противоречит решению 1).
- **Разбор писем-уведомлений на info@.** Хрупко (HTML для людей), сайт глушит сбои отправки письма → заявка теряется молча, приём info@ выключен, статус не записать.
- **Вебхук из сайта в АИС.** Требует менять код сайта — против условия оператора.

## 2. Что есть на стороне westmed.kz (зафиксировано по коду, репо `~/IdeaProjects/westmed`, коммит `e87bfcb`)

Сайт хранит три вида обращений:

| Вид на сайте | Откуда | В БД сайта | Контакты |
|---|---|---|---|
| **Запрос цены** по товару | кнопка на карточке товара → `RequestModal` | `price_requests` с `product_id` | имя, email, телефон, компания?, сообщение? |
| **Заявка с сайта** (запрос цены без товара) | «Оставить заявку» на главной / контактах / тендерах → тот же `RequestModal` | `price_requests` без `product_id` | то же; суть — в свободном тексте «найдите нам…» |
| **Запрос КП** | корзина КП → `CartClient` / `QuoteCartDrawer` | `quote_requests` + `quote_request_items` (slug, имя, кол-во) | то же |
| Переход в WhatsApp | кнопка WhatsApp | **не хранится** — только письмо и Telegram, контактов нет | — |

Email и телефон на формах сайта обязательны (`@NotBlank`), телефон сайт нормализует в `+7XXXXXXXXXX`, если распознал.

### Контракт API сайта, который использует АИС (как есть, без изменений)

| Вызов | Назначение | Детали |
|---|---|---|
| `POST /api/auth/login` `{"email","password"}` | вход | `200 {"accessToken","refreshToken","email","name","role"}`; неверные данные → `401` с пустым телом; **лимит 5 входов (любых, в т.ч. успешных) за 5 мин с IP → `429`** (`RateLimitFilter`) |
| — | admin-вызов без токена или с протухшим | **`403`, а не `401`**: у сайта не настроен entry point, и Spring Security по умолчанию отвечает анонимному `403` (`Http403ForbiddenEntryPoint`); `JwtAuthenticationFilter` протухший токен просто не принимает. Проверено: `GET /api/admin/requests` без токена → `403` |
| `GET /api/admin/requests?page=&size=` | «Запрос цены» / «Заявка» | Spring `Page`: `content[]`, `totalPages`, `last`, …; новые сверху (`createdAt DESC`); `size ≤ 100`. Элемент (`@JsonInclude(NON_NULL)` — пустые поля ОТСУТСТВУЮТ): `id` (UUID), `name`, `email`, `phone`?, `company`?, `message`?, `productName`?, `status` (`NEW`/`PROCESSED`/`CLOSED`), `createdAt` (ISO-8601, UTC) |
| `GET /api/admin/quote-requests?page=&size=` | «Запрос КП» | то же + `items[]`: `productSlug`, `productName`, `quantity` |
| `PATCH /api/admin/requests/{id}/status` `{"status":"PROCESSED"}` | статус из АИС | `200`; неизвестный id → `404` |
| `PATCH /api/admin/quote-requests/{id}/status` | то же для КП | то же |
| `GET /api/v1/products?search=<текст>&size=<n>` | бренд товара (публичный) | поиск `name ILIKE %текст%`, только ACTIVE и не удалённые; элемент: `name`, `slug`, `brandName`?, `categoryName` |

- ⚠️ **`GET /api/v1/products/{slug}` АИС не вызывает никогда** — он увеличивает `viewCount` товара на сайте (`ProductService.incrementViewCount`). Бренд берём только поиском по списку.
- ⚠️ **Ролей на сайте нет:** `/api/admin/**` требует любой валидный токен (`authenticated()`), поэтому учётка АИС получает полные права админки сайта. Отсюда — пароль только в `.env` прода, нигде в логах, отдельная учётка (а не общая admin@).
- Ссылка на товар для оператора: `https://westmed.kz/product/<slug>` (next-intl `localePrefix: "as-needed"`, дефолтная локаль ru — без префикса).
- Сайт и АИС живут на одном сервере (oblako.kz), но АИС ходит на сайт по публичному `https://westmed.kz` — сетевые настройки не нужны (фолбэк — §13).

## 3. Архитектура

```
westmed.kz (код не меняется)                      АИС
  POST /api/auth/login        ◄──────────┐
  GET  /api/admin/requests    ◄──────────┤  WestmedHttpClient (java.net.http, токен в памяти)
  GET  /api/admin/quote-requests ◄───────┤        ▲
  PATCH …/{id}/status         ◄──────────┤        │
  GET  /api/v1/products?search= ◄────────┘  WestmedLeadSync.runOnce()  ← WestmedLeadScheduler
                                              (1) забрать новое  (2) записать статусы     (свой поток, ~90 с,
                                                        │                                   рынок KZ явно)
  ручной ввод «+ Обращение» (UI) ──► LeadService        │ IncomingLead
                                          │             ▼
                                          └──► LeadIntakeService.ingest()  ← ЕДИНАЯ точка входа
                                                 · дубли по (source, external_id)
                                                 · телефон → +7XXXXXXXXXX (PhoneNormalizer)
                                                 · клиент по телефону/email (LeadClientMatcher)
                                                        ▼
                                          lead · lead_item · lead_event   (рынок KZ)
                                                        ▼
                                  страница «Обращения» → «Создать частную заявку»
                                  → PrivateRequestService.createFromLines (уже есть)
                                  → реестр НЦЭЛС → поставщики по бренду → запрос КП → сравнение
```

Позже в ту же точку входа встанут (НЕ в этом этапе): адаптер vital-spb.kz, вебхук WhatsApp Business, вебхук АТС — см. §12.

### Компоненты бэкенда

Раскладка — по существующим слоям проекта (`entity/`, `repository/`, `service/`, `controller/`, `dto/`, `integration/<площадка>/`).

| Компонент | Ответственность |
|---|---|
| `entity/Lead`, `LeadItem`, `LeadEvent` + enum `LeadChannel`, `LeadStatus`, `LeadCloseReason`, `LeadEventType` | модель (§4). `Lead` — рыночная сущность: `MarketScoped` + `@Filter(marketFilter)` + `MarketStampingListener`, **`@FilterDef` не переобъявлять** (§6 CLAUDE.md). `LeadItem`/`LeadEvent` — дети `Lead`, `cascade=ALL, orphanRemoval=true`, управляются через коллекции (урок §7) |
| `repository/LeadRepository` | выборки для списка/счётчика/поиска по `(source, external_id)` / телефона / ожидающих записи статусов |
| `util/PhoneNormalizer` | чистая функция: сырой телефон → `+7XXXXXXXXXX` или `null` |
| `service/LeadClientMatcher` | поиск `Facility` текущего рынка по последним 10 цифрам телефона, иначе по email (без регистра); **однозначное совпадение** → клиент, несколько → не привязываем (подсказка в карточке) |
| `integration/lead/IncomingLead` | record-шов входа: `source, externalId, channel, subject, receivedAt, contactName, contactPhone, contactEmail, company, message, items[], initialStatus, extStatus` |
| `service/LeadIntakeService` | `@Transactional ingest(IncomingLead) → CREATED \| DUPLICATE`: идемпотентно создаёт `Lead` + позиции + событие RECEIVED |
| `service/LeadService` | ручной ввод, карточка, переходы статусов, заметки/звонки, правка позиций, **превращение в частную заявку** (одна транзакция) |
| `integration/westmed/WestmedClient` + `WestmedHttpClient` + `dto/*` | HTTP к сайту (§6). Интерфейс нужен для фейка в тестах (как `GoszakupClient`/`FakeGoszakupClient`) |
| `integration/westmed/WestmedLeadMapper` | DTO сайта → `IncomingLead` + обогащение брендом из каталога (кеш на цикл) |
| `integration/westmed/WestmedLeadSync` | один цикл: забрать новое (§6.2) → записать ожидающие статусы (§6.3) |
| `integration/westmed/WestmedLeadScheduler` | `@Scheduled` + свой однопоточный экзекьютор `westmed-leads` + флаг `running` + состояние для UI (`lastRunAt/lastSuccessAt/lastError/lastCreated`); `MarketContext.set(KZ)` в потоке экзекьютора, `clear()` в finally (§6 CLAUDE.md) |
| `controller/LeadController` | REST (§8) |
| `MailReceiveService` (правка) | пропуск писем-уведомлений сайта (§11) |

**Сознательный YAGNI:** общего интерфейса «источник заявок» с одной реализацией не заводим. Шов — `IncomingLead` + `LeadIntakeService.ingest`. Интерфейс источника выделим, когда появится второй (vital-spb.kz).

## 4. Данные — миграция `V18__leads.sql`

⚠️ Номер **V18**: V17 уже занята (`V17__kz_distributors_endoscopy.sql`). Схема — только новой миграцией (§10 CLAUDE.md).

```sql
CREATE TABLE lead (
    id                  BIGSERIAL PRIMARY KEY,
    market              VARCHAR(2)   NOT NULL,
    channel             VARCHAR(20)  NOT NULL,   -- SITE / PHONE / WHATSAPP / EMAIL / OTHER
    source              VARCHAR(40)  NOT NULL,   -- 'westmed.kz' | 'manual' | позже 'vital-spb.kz', 'whatsapp', 'pbx'
    external_id         VARCHAR(100),            -- id у источника; westmed: 'price:<uuid>' | 'quote:<uuid>'
    subject             VARCHAR(200) NOT NULL,   -- «Запрос КП», «Запрос цены», «Заявка с сайта», «Звонок», «WhatsApp»
    contact_name        VARCHAR(255),
    contact_phone       VARCHAR(50),             -- как пришло (для показа)
    phone_norm          VARCHAR(20),             -- +7XXXXXXXXXX — ключ сопоставления каналов
    contact_email       VARCHAR(255),
    company             VARCHAR(255),
    message             TEXT,
    facility_id         BIGINT REFERENCES facility(id) ON DELETE SET NULL,
    status              VARCHAR(20)  NOT NULL,   -- NEW / IN_WORK / CONVERTED / CLOSED
    close_reason        VARCHAR(30),             -- ANSWERED / SPAM / DUPLICATE / NOT_OUR_PROFILE / CLIENT_DECLINED / OTHER
    private_request_id  BIGINT REFERENCES tender(id) ON DELETE SET NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ext_status          VARCHAR(20),             -- статус у источника, как его знает АИС
    ext_status_pending  VARCHAR(20),             -- что надо записать в источник; NULL = нечего
    ext_sync_error      VARCHAR(500)             -- последняя ошибка записи статуса
);
CREATE UNIQUE INDEX uq_lead_source_ext  ON lead (source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_lead_market_status     ON lead (market, status, received_at DESC);
CREATE INDEX idx_lead_phone             ON lead (market, phone_norm) WHERE phone_norm IS NOT NULL;
CREATE INDEX idx_lead_ext_pending       ON lead (source) WHERE ext_status_pending IS NOT NULL;
CREATE INDEX idx_lead_private_request   ON lead (private_request_id) WHERE private_request_id IS NOT NULL;

CREATE TABLE lead_item (
    id           BIGSERIAL PRIMARY KEY,
    lead_id      BIGINT NOT NULL REFERENCES lead(id) ON DELETE CASCADE,
    line_no      INT    NOT NULL,
    name         VARCHAR(500) NOT NULL,
    brand        VARCHAR(255),
    quantity     INT    NOT NULL DEFAULT 1,
    product_url  VARCHAR(500)
);
CREATE INDEX idx_lead_item_lead ON lead_item (lead_id);

CREATE TABLE lead_event (
    id           BIGSERIAL PRIMARY KEY,
    lead_id      BIGINT NOT NULL REFERENCES lead(id) ON DELETE CASCADE,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    type         VARCHAR(20) NOT NULL,    -- RECEIVED / NOTE / CALL / MESSAGE / STATUS / SYNC
    direction    VARCHAR(3),              -- IN / OUT — звонки и сообщения
    channel      VARCHAR(20),             -- канал события
    author       VARCHAR(100),            -- логин пользователя АИС; NULL = система
    body         TEXT,
    external_id  VARCHAR(100)             -- id сообщения/звонка у провайдера (будущие каналы)
);
CREATE INDEX idx_lead_event_lead ON lead_event (lead_id, occurred_at);
CREATE UNIQUE INDEX uq_lead_event_ext ON lead_event (channel, external_id) WHERE external_id IS NOT NULL;
```

Замечания:

- `lead` в PostgreSQL не зарезервирован (это только имя оконной функции) — кавычки не нужны.
- Колонки названы `line_no` и `occurred_at`, а не `position` и `at`: `position` — функция HQL, `at` — ключевое слово части диалектов, и разбор `@OrderBy` по ним ненадёжен.
- `MESSAGE` и `external_id` у событий в этом этапе не используются — это задел §12. Уникальный индекс заведён сразу: доставка вебхуков «хотя бы один раз», повтор не должен плодить события.
- `phone_norm` пишется при создании обращения. Правки контакта в этом этапе нет (не согласовывалась) — если появится, пересчитывать и `phone_norm`.
- `updated_at` обновляет `@PreUpdate` сущности.

## 5. Статусы и переходы

| В АИС | Смысл | Статус на сайте (`ext_status_pending`) |
|---|---|---|
| `NEW` — Новое | никто не взял | `NEW` |
| `IN_WORK` — В работе | уточняют, ищут | `PROCESSED` («В работе») |
| `CONVERTED` — Заявка создана | собрана частная заявка, дальше работа в ней | `PROCESSED` |
| `CLOSED` — Закрыто (+ причина) | ответили без заявки / спам / дубль / не наш профиль / клиент отказался / другое | `CLOSED` |

Разрешённые переходы (сервер проверяет, недопустимый → `400` с понятным текстом):

| Действие | Из | В |
|---|---|---|
| «Взять в работу» | NEW | IN_WORK |
| «Создать частную заявку» | NEW, IN_WORK | CONVERTED |
| «Закрыть» (причина обязательна) | NEW, IN_WORK, CONVERTED | CLOSED |
| «Вернуть в работу» | CLOSED | CONVERTED, если есть частная заявка, иначе IN_WORK |

- Каждый переход пишет событие `STATUS` (автор, «было → стало», причина/комментарий).
- Если у источника есть обратная запись статуса (сейчас — только `westmed.kz`) и целевой статус сайта отличается от `ext_status` → ставится `ext_status_pending`. Для `manual` — никогда.
- **Мастер статусов — АИС.** Статус сайта читается только при ПЕРВОМ появлении заявки в АИС. Ручные правки статуса в админке сайта после этого АИС не перечитывает и не перезаписывает, пока сама не сменит статус обращения.
- Ручное обращение создаётся сразу `IN_WORK` (кто принял звонок, тот и ведёт).
- Правка позиций разрешена в NEW и IN_WORK; после превращения в частную заявку позиции обращения — справочные (строки живут в частной заявке).

## 6. Адаптер westmed.kz

### 6.1 Вход и HTTP

- Отдельная учётка сайта `ais@westmed.kz` (роль `MANAGER` — на сайте роли не ограничивают доступ, §2). Создаётся одной строкой в БД сайта при раскатке (§13) — код сайта не трогается.
- `WestmedHttpClient` — `java.net.http.HttpClient` (как `GoszakupHttpClient`), connect-таймаут 10 с, таймаут запроса 20 с, Jackson.
- Токен доступа (живёт 30 мин) держится в памяти. Вход — лениво при первом вызове. На **`401` или `403`** от любого admin-вызова — один повторный вход и один повтор вызова; повторный отказ — ошибка входа. ⚠️ Реагировать только на `401` нельзя: протухший токен сайт отвечает `403` (§2), и без этого АИС через 30 минут после старта перестала бы видеть заявки навсегда.
- **После неудачного входа (`401`/`429`) — пауза `auth-backoff-ms` (10 мин).** Иначе АИС каждые 90 с упиралась бы в лимит сайта 5 входов за 5 мин и держала бы IP в `429`.
- Пароль и токен **никогда** не попадают в логи и тексты ошибок (заголовок `Authorization` в сообщениях об ошибках не печатается).

### 6.2 Забрать новое

Для каждого из двух видов (`requests`, `quote-requests`):

1. Страница 0, `size = page-size` (50).
2. Для каждого элемента `externalId = "price:<id>"` / `"quote:<id>"` → `IncomingLead` → `LeadIntakeService.ingest`.
3. **Если на странице не было ни одной новой заявки — стоп.** Иначе следующая страница, пока `last == false`.

Правило «стоп на странице без новых» одновременно даёт импорт всей истории при первом запуске (все незнакомы → пролистывается всё) и дешёвый инкремент потом (новые — на первой странице, вторая уже вся знакома).

Маппинг элемента сайта:

| Сайт | `subject` | Позиции (`lead_item`) |
|---|---|---|
| `requests` с `productName` | «Запрос цены» | 1: `name=productName`, `brand` из каталога, `quantity=1`, `product_url` по slug из каталога |
| `requests` без `productName` | «Заявка с сайта» | нет — суть в `message` |
| `quote-requests` | «Запрос КП» | по `items[]`: `name=productName`, `quantity`, `brand` + `product_url` из каталога |

- Обогащение из каталога: `GET /api/v1/products?search=<productName>&size=20` → совпадение по `slug` (для КП) либо по имени без регистра и крайних пробелов (для запроса цены). Не нашлось (товар снят/переименован) → `brand = null`, `product_url` для КП всё равно строится по `productSlug`. Кеш на один цикл: одно имя — один запрос. Ошибка поиска не валит приём — позиция пишется без бренда.
- `channel = SITE`, `source = "westmed.kz"`, `receivedAt = createdAt` сайта.
- Статус при первом появлении: сайт `NEW` → `NEW`; `PROCESSED` → `IN_WORK`; `CLOSED` → `CLOSED` без причины + событие «Импортирована из истории сайта со статусом „Закрыта“». `ext_status` = статус сайта, `ext_status_pending = NULL`.
- Событие `RECEIVED`: «Заявка с сайта westmed.kz: Запрос КП, 3 поз.», автор `NULL`.

### 6.3 Записать статусы

После приёма, в том же цикле, если `write-status = true`:

1. Обращения `source='westmed.kz' AND ext_status_pending IS NOT NULL` — читаются в транзакции.
2. Для каждого `PATCH` (вне транзакции) на `requests` или `quote-requests` по префиксу `external_id`.
3. Успех → `ext_status = pending`, `pending = NULL`, `ext_sync_error = NULL` + событие `SYNC` «Статус на сайте: В работе». Ошибка → `ext_sync_error = текст`, `pending` остаётся → повтор на следующем цикле. `404` (заявку удалили на сайте) → `pending = NULL`, ошибка «заявки на сайте больше нет», больше не повторяем.

При `write-status = false` (локальная разработка) шаг пропускается целиком — `pending` копится и уйдёт, когда запись включат.

### 6.4 Планировщик и видимость

- `WestmedLeadScheduler.tick()` каждые `poll-ms` (90 с), только при `enabled=true`; работа — на свой однопоточный экзекьютор `westmed-leads` (не общий `scheduling-1`, чтобы зависший сайт не задерживал приём почты и импорты); повторный тик во время прогона пропускается.
- В потоке: `MarketContext.set(<рынок из конфига, KZ>)` → `WestmedLeadSync.runOnce()` → `MarketContext.clear()` в finally. Запись в БД — в `@Transactional`-методах ОТДЕЛЬНЫХ бинов (`LeadIntakeService`, писатель статусов), сеть — вне транзакций (§6 CLAUDE.md).
- Состояние для UI: `enabled`, `writeStatus`, `running`, `lastRunAt`, `lastSuccessAt`, `lastError` (человеческим текстом: «сайт недоступен», «вход не удался — проверьте учётку, повтор через 10 мин»), `lastCreated`.
- Ручной запуск «Проверить сейчас» ставит цикл в тот же экзекьютор и ждёт результат до 30 с; если цикл уже идёт — возвращает текущее состояние.

### 6.5 Конфигурация (`application.yaml`)

```yaml
leads:
  westmed:
    enabled: ${WESTMED_LEADS_ENABLED:false}          # выкл по умолчанию, как все импорты
    base-url: ${WESTMED_BASE_URL:https://westmed.kz}
    site-url: ${WESTMED_SITE_URL:https://westmed.kz}     # для ссылок «на сайте ↗» у позиций
    username: ${WESTMED_USERNAME:}
    password: ${WESTMED_PASSWORD:}
    write-status: ${WESTMED_WRITE_STATUS:false}      # локально НЕ пишем в боевой сайт
    market: ${WESTMED_MARKET:KZ}
    poll-ms: ${WESTMED_POLL_MS:90000}
    initial-delay-ms: ${WESTMED_INITIAL_DELAY_MS:30000}
    page-size: 50
    auth-backoff-ms: 600000
    notification-from: ${WESTMED_NOTIFICATION_FROM:info@westmed.kz}
```

Включено, но не задан `username`/`password` → цикл не ходит на сайт, `lastError` = «не заданы учётные данные сайта» (один раз в лог, не каждые 90 с).

## 7. Точка входа, клиент и телефон

- `LeadIntakeService.ingest`: есть обращение с тем же `(source, external_id)` → `DUPLICATE`, ничего не меняется (включая статус — §5 «мастер — АИС»). Иначе — создать. Гонка двух вставок ловится уникальным индексом → трактуется как `DUPLICATE`.
- `PhoneNormalizer`: оставить цифры; `8XXXXXXXXXX` → `7XXXXXXXXXX`; 10 цифр → приписать `7`; ровно 11 цифр с `7` → `+7XXXXXXXXXX`; иначе `null` (сырой телефон всё равно хранится в `contact_phone`).
- `LeadClientMatcher` при создании: по телефону — `Facility` текущего рынка, у которой последние 10 цифр телефона совпадают (нативный запрос с `regexp_replace(phone,'\D','','g')`); если нет — по email без регистра. Ровно одна → `lead.facility`; ноль или несколько → `NULL`.
- Карточка дополнительно показывает «этот номер уже обращался»: до 5 других обращений рынка с тем же `phone_norm`.

## 8. REST API (`/api/leads`)

Чтение — любому вошедшему; запись — `@PreAuthorize("hasRole('ADMIN')")`, как везде в АИС.

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/leads?status=&channel=&q=` | список, новые сверху (`received_at DESC`), до 300 записей; `status` — один или несколько через запятую (`NEW,IN_WORK` — дефолт экрана) либо `ALL`; `q` — по имени, телефону, компании, тексту, позициям |
| GET | `/api/leads/count?status=NEW` | `{count}` — для счётчика в меню |
| GET | `/api/leads/{id}` | карточка: контакт, клиент, позиции, текст, лента, «уже обращался», ссылка на частную заявку, `extSyncError` |
| POST | `/api/leads` | ручное обращение: `channel` (PHONE/WHATSAPP/OTHER), `contactName`, `contactPhone`, `company`, `contactEmail`, `message`, `items[]`. Телефон или email — хотя бы одно (иначе `400`). `subject` выводится из канала: «Звонок» / «WhatsApp» / «Обращение»; `source = manual`, статус `IN_WORK`, `received_at = now` |
| PUT | `/api/leads/{id}/items` | правка позиций (NEW/IN_WORK) |
| POST | `/api/leads/{id}/take` | NEW → IN_WORK |
| POST | `/api/leads/{id}/close` | `{reason, comment}` |
| POST | `/api/leads/{id}/reopen` | CLOSED → IN_WORK/CONVERTED |
| POST | `/api/leads/{id}/events` | `{type: NOTE\|CALL, direction?, body}` — заметка или звонок |
| POST | `/api/leads/{id}/convert` | частная заявка из обращения (§9) → `{privateRequestId, number}` |
| GET | `/api/leads/sync-status` | состояние синхронизации с сайтом (§6.4) |
| POST | `/api/leads/sync` | «Проверить сейчас»; при выключенной интеграции → `400` «интеграция с сайтом выключена» |

Обращение чужого рынка или несуществующее → `404` (рыночный аспект на `LeadRepository`). Позиции и события грузятся только через своё обращение — у детей нет рыночного фильтра, поэтому по голому id их не отдаём (урок §9 CLAUDE.md про `PriceRequestItem`).

`PrivateRequestResponse` получает поле `lead` (`{id, subject, source, receivedAt}` или `null`) — для обратной ссылки в карточке частной заявки.

## 9. «Создать частную заявку» — одна транзакция

`POST /api/leads/{id}/convert`:

```json
{
  "clientFacilityId": 12,
  "newClient": { "name": "ТОО «Клиника Х»", "phone": "+77770000000", "email": "a@b.kz",
                 "lastName": null, "firstName": "Айгерим", "middleName": null },
  "note": "текст клиента (по умолчанию)",
  "lines": [ { "name": "Облучатель ОБН-150", "manufact": "Азов", "quantity": 2 } ]
}
```

Ровно одно из `clientFacilityId` / `newClient`. В одной `@Transactional`:

1. Обращение текущего рынка в статусе NEW/IN_WORK (иначе `400` «уже превращено» / «закрыто»).
2. `newClient` → создать `Facility` текущего рынка. Имя занято → `400` «клиент „…“ уже есть — выберите его в списке» (у `facility.name` глобальный UNIQUE).
3. ≥1 строка с непустым наименованием (иначе `400`).
4. `PrivateRequestService.createFromLines(...)` — существующий шов; `description` = `note`; телефон и email контакта кладутся в `tender.contact_phone` / `contact_email`.
5. Обращение: `private_request_id`, `facility_id`, статус `CONVERTED`, `ext_status_pending` по §5, событие `STATUS` «Создана частная заявка ЧЗ-2026-0012».

Любая ошибка — откат целиком: ни клиента-сироты, ни заявки без обращения. Это отличие от импорта письма во «Входящих», где клиент и заявка создаются двумя вызовами с фронта.

## 10. Интерфейс

Общие правила проекта: standalone-компоненты, только токены (тёмная тема сама), `@media` последним блоком, мобильный ≤900px, тач-таргеты 40px, `cdr.detectChanges()` после async, инлайн-цвета запрещены (§12 CLAUDE.md).

- **Меню:** в группе «Заявки» первым пунктом — «Обращения» со счётчиком `NEW` (скрыт при 0; обновление при смене страницы и раз в 60 с). Порядок группы: Обращения → Частные заявки → Заявки на участие → Входящие.
- **`/leads` — список (`leads.component.ts`).** Карточки, одинаковые на 1280 и 390 (как список тендеров). В карточке: канал (🌐 westmed.kz / 📞 Звонок / WhatsApp / Другое) + `subject` (+ «· N поз.»); что просят (первые 2 позиции + «ещё N» или начало текста); кто (имя · компания · телефон); когда («12 мин назад», в `title` полная дата); чип статуса; номер частной заявки ссылкой. `NEW` выделены. Сверху: плашка синхронизации («westmed.kz · синхронизировано 1 мин назад» / ошибка / «интеграция выключена»), «Проверить сейчас» (админ, при включённой интеграции), «+ Обращение» (админ); фильтры: статус-чипы (Новые · В работе · Заявка создана · Закрытые · Все, по умолчанию «Новые + В работе»), канал, поиск.
- **Карточка (`lead-card.component.ts`, `?openId=`; на телефоне — на весь экран):**
  - Контакт: телефон + «Позвонить» (`tel:`) + «Написать в WhatsApp» (`https://wa.me/<цифры>` — открывает чат уже сейчас, без бизнес-API), email (`mailto:`), компания, клиент (если узнан — ссылкой), «этот номер уже обращался: …».
  - Что просят: позиции (правка в NEW/IN_WORK: наименование, бренд, кол-во; добавить/удалить) со ссылкой «на сайте ↗».
  - Текст клиента.
  - Лента событий (время, автор, текст; `SYNC`-ошибки — предупреждением).
  - Действия по §5: «Взять в работу», «Создать частную заявку», «Закрыть» (причина + комментарий), «+ Заметка», «+ Звонок» (входящий/исходящий + итог), «Вернуть в работу». Запись статуса на сайт не удалась → баннер с текстом `ext_sync_error` («повторим автоматически»).
- **Диалог «Создать частную заявку» (`lead-convert-dialog.component.ts`):** клиент — найденный подставлен; иначе выбор из списка или «➕ Новый клиент» с полями из обращения (название = компания, иначе имя; ФИО контакта разбито простым правилом: 1 слово → имя, 2 → имя + фамилия, 3 → фамилия имя отчество — всё правится); строки из позиций (правятся; у «Заявки с сайта» одна пустая строка, текст клиента рядом); после успеха — тост + переход в карточку частной заявки.
- **Ручной ввод (`lead-form.component.ts`):** канал (Звонок / WhatsApp / Другое), имя, телефон, компания, email, «что ищут», позиции по желанию. Хотя бы телефон или email обязателен.
- **Карточка частной заявки:** строка «Из обращения: Запрос КП · westmed.kz · 12.09.2026» со ссылкой.

## 11. Почта: не дублировать заявки

Сайт шлёт о каждой заявке письмо на `info@westmed.kz` от `info@westmed.kz` с темой, оканчивающейся на « — westmed.kz» («Новая заявка…», «Запрос КП (N поз.)…», «WhatsApp-обращение…»). Если в АИС включат приём почты с info@, эти письма попали бы во «Входящие» как `UNMATCHED` — дубли того, что уже пришло через API.

Правка `MailReceiveService.handle`: адрес отправителя == `leads.westmed.notification-from` **И** тема оканчивается на « — westmed.kz» → письмо помечается прочитанным и **не сохраняется**; счётчик `skippedSiteNotifications` в результате опроса. Прочие письма — без изменений.

## 12. Задел под WhatsApp Business и АТС (не реализуется в этом этапе)

Что закладывается сейчас:

- `channel` включает `PHONE`, `WHATSAPP`, `EMAIL`; ручной ввод уже пишет звонки и WhatsApp — история не оборвётся при включении автоматики.
- Лента `lead_event` с `direction`, `channel`, `external_id` и уникальным индексом — сообщения и звонки провайдеров лягут в неё без миграции смысла.
- `phone_norm` с индексом `(market, phone_norm)` — ключ «к какому обращению относится входящий звонок/сообщение».

Как встанут будущие адаптеры (контракт, чтобы не переделывать модель):

- Публичный вебхук `/api/hooks/<провайдер>` с проверкой подписи провайдера → `LeadIntakeService.appendOrCreate(phone, event)`: есть открытое обращение (NEW/IN_WORK/CONVERTED) рынка с тем же `phone_norm` → событие в его ленту; нет → новое обращение с `channel = WHATSAPP | PHONE`.
- Ответ клиенту из АИС (исходящее сообщение) — событие `OUT` + API провайдера; для WhatsApp действует правило 24-часового окна (свободный текст — в течение суток после сообщения клиента, первым — только утверждёнными платными шаблонами).

Что понадобится вне кода: аккаунт WhatsApp Business Platform (Meta напрямую или провайдер-посредник); облачная АТС с вебхуками (обычный номер на SIM событий не отдаёт); публичный адрес для вебхуков — решается блоком доступа (§14).

## 13. Раскатка

1. **Учётка АИС на сайте** (прод westmed, одна строка, код сайта не меняется): сгенерировать стойкий пароль, получить bcrypt-хеш и вставить `admin_users (email='ais@westmed.kz', role='MANAGER', name='АИС (интеграция)')` через `docker exec westmed-postgres psql`. Делается только с явного «да» оператора; пароль не печатается в чат.
2. `/srv/ais/.env`: `WESTMED_LEADS_ENABLED=true`, `WESTMED_USERNAME`, `WESTMED_PASSWORD`, `WESTMED_WRITE_STATUS=true`.
3. Мерж в `main` → пуш → автодеплой (§5 CLAUDE.md).
4. Проверка на проде: первый цикл импортирует историю; «Взять в работу» у тестовой заявки → в админке сайта «В работе».

История придёт со статусами сайта как есть: тестовые заявки, которые в админке сайта остались «Новыми», появятся в АИС новыми — их закрывают в АИС причиной «Спам» (статус уйдёт и на сайт).

**Риск hairpin:** контейнер АИС ходит на публичный IP собственного сервера. Обычно это работает (nginx хоста слушает `0.0.0.0:443`). Если нет — фолбэк без правок кода сайта: подключить `ais-backend` к внешней сети `westmed` в `docker-compose.yml` АИС и поставить `WESTMED_BASE_URL=http://westmed-backend:8080`.

## 14. Вне этого этапа (следующие блоки)

1. **Доступ без Tailscale** — сразу следом, отдельная спека: свой адрес на домене westmed.kz через nginx хоста + Let's Encrypt; перед открытием обязательно: смена `admin/admin` и `operator/operator`, защита входа от перебора, безопасные cookie сессии, `noindex`; «калитка» по коду устройства (чужой видит только поле кода, устройство помнит код год, смена кода отсекает все устройства). Даёт и публичный адрес для будущих вебхуков.
2. КП клиенту (цены поставщиков + наценка + НДС → PDF/письмо).
3. Адаптер vital-spb.kz (у него своя модель заявок, а клики WhatsApp там сохраняются).
4. Каталог сайта как источник моделей/брендов/РУ.
5. Воронка: выиграли/проиграли, скорость реакции, ответственный.
6. WhatsApp Business и АТС (по §12).
7. Письма клиентов с info@ как обращения (единый вход).

## 15. Тестирование

Гейт: `./gradlew cleanTest test` — 0 падений (§13 CLAUDE.md: именно `cleanTest`), `npm run build` зелёный.

- **`PhoneNormalizerTest`** — `8 777…`, `+7 (777) …`, `7771234567` (10 цифр), мусор → `null`, иностранный номер → `null`.
- **`WestmedHttpClientTest`** (стаб на JDK `HttpServer`, как `GoszakupHttpClientTest`) — вход и `Bearer` в admin-вызовах; `401` → один повторный вход и повтор; **`403` (протухший токен, реальное поведение сайта) → тоже повторный вход и повтор**; повторный отказ → ошибка входа; `429` на входе → ошибка входа с паузой; разбор `Page` с **реальной формой ответа** (в т.ч. отсутствующие поля при `NON_NULL`); тело `PATCH`; кодирование кириллицы в `search`; пароль не встречается в тексте исключения.
- **`WestmedLeadMapperTest`** — три вида заявок (§6.2), бренд по slug / по имени / не найден, `product_url`, маппинг статуса истории.
- **`WestmedLeadSyncTest`** (`FakeWestmedClient`, `@SpringBootTest @Transactional` на nirdb) — первый прогон листает все страницы; второй останавливается на первой (вызовов второй страницы нет); повторный прогон не плодит обращения; запись статусов: успех снимает `pending`, ошибка оставляет его и пишет `ext_sync_error`, `404` снимает `pending`; `write-status=false` → PATCH не вызывается; неудачный вход → пауза, следующий тик на сайт не ходит; всё создаётся с `market = KZ` даже при дефолтном `MarketContext` потока.
- **`LeadIntakeServiceTest`** — идемпотентность по `(source, external_id)`; привязка клиента по телефону (в т.ч. «8…» против «+7…»), по email, неоднозначность → без клиента; чужой рынок не матчится.
- **`LeadServiceTest`** — матрица переходов §5 (разрешённые и `400` на запрещённых), события `STATUS`, `pending` только у источника с обратной записью; превращение: существующий клиент / новый / занятое имя → `400` и **полный откат** (ни клиента, ни заявки); двойное превращение → `400`; обращение чужого рынка → `404`.
- **`LeadControllerTest`** — оператор получает `403` на запись и `200` на чтение.
- **Почта** (GreenMail, как `MailReceiveServiceIntegrationTest`) — уведомление сайта пропущено и не сохранено; обычное письмо от клиента с info@ — как раньше.
- **Мутации** (урок §14 CLAUDE.md «зелёный тест, который не может упасть»): снять условие «стоп на странице без новых», снять уникальность/проверку дубля, снять пропуск `pending` при ошибке — каждый раз должен краснеть ровно свой тест.
- **Живьём (Playwright), без касания боевого сайта:** локальная копия westmed (`docker compose` из его репо) → заявки через три формы сайта (товар, общая, корзина КП) → в АИС три обращения с верными позициями и брендами → «Взять в работу» → в локальной админке сайта «В работе» → «Создать частную заявку» с новым клиентом → карточка частной заявки с обратной ссылкой → ручной «Звонок» → закрытие со спамом → на сайте «Закрыта». Экраны на 1280 и 390, светлая и тёмная тема.
