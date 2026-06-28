# Дизайн: автоимпорт KZ-тендеров (goszakup.gov.kz) + фильтр по региону

**Дата:** 2026-06-28
**Статус:** утверждён (брейншторм)
**Рынок:** KZ (West-Med). RF не затрагивается.

## 1. Цель и контекст

На странице «Тендеры» при выбранном рынке **KZ** список публичных тендеров наполняется
**автоматически** релевантными (медоборудование) госзакупками с портала
goszakup.gov.kz через официальное API. На страницу добавляется **фильтр по региону
(области)** Казахстана.

Бизнес-смысл: West-Med мониторит публичные закупки медоборудования РК, чтобы видеть
тендеры, на которые можно зайти, в дополнение к частным заявкам клиник. Импортные
тендеры — это обычные `Tender` с `source=PUBLIC_TENDER`, `market=KZ`; они появляются
на той же странице `/tenders`, что и RF-тендеры, но в контексте KZ-рынка (которая
сейчас по этой странице пуста).

**Поведение RF полностью сохраняется** — импорт и гео-фильтр относятся только к KZ.

## 2. Источник данных и доступ

- **API:** REST `https://ows.goszakup.gov.kz/v2` (официальные «унифицированные сервисы»).
- **Авторизация:** заголовок `Authorization: Bearer <token>`. Токен бесплатный,
  выдаётся после регистрации в «Центре электронных финансов».
- **Хранение токена:** в env/конфиге (`GOSZAKUP_TOKEN`), как почтовые пароли Mail.ru
  (вне репозитория). Никогда не коммитить и не эхо-печатать.
- **Поведение без токена:** импорт выключен (как IMAP-приём по умолчанию). Ручная
  кнопка/эндпоинт возвращают понятную ошибку «токен goszakup не настроен».

### Используемые эндпоинты
| Эндпоинт | Назначение |
|----------|-----------|
| `GET /v2/trd-buy?page=next&search_after={id}` | список объявлений, курсорная пагинация |
| `GET /v2/lots/number-anno/{number_anno}` | лоты объявления |
| `GET /v2/subject/{bin}` | организация-заказчик (для резолва региона/КАТО) |

### Поля объявления (trd_buy), которые мапим
`number_anno`, `name_ru`, `total_sum`, `count_lots`, `ref_buy_status_id`,
`customer_bin`, `org_bin`, `publish_date`, `start_date`, `end_date`.

### Поля лота (lots)
`lot_number`, `name_ru`, `amount`, `count`, `trd_buy_number_anno`.

## 3. Бэкенд-компоненты (новые)

Новых Gradle-зависимостей не требуется: HTTP — встроенный `java.net.http.HttpClient`
(Java 17), JSON — уже подключённый Jackson (как в `RegistryImportService`).

### 3.1 `GoszakupClient` (+ интерфейс)
Тонкая обёртка над REST API.
- Методы: `List<TrdBuyDto> fetchTrdBuyPage(String cursor)`,
  `List<LotDto> fetchLots(String numberAnno)`,
  `SubjectDto fetchSubject(String bin)`.
- Конфиг: base-url, token, page-size.
- **Интерфейс** `GoszakupClient` + реализация `GoszakupHttpClient`, чтобы в тестах
  подменять фейком, отдающим канонический JSON (без реальной сети).
- Ошибки сети/HTTP — логируются, пробрасываются как доменное исключение; сервис
  прерывает прогон с понятной сводкой.

### 3.2 `GoszakupImportService` (`@Transactional` на методе записи)
Отдельный бин (не self-invoke), чтобы аспект `MarketFilterAspect` получил
привязанную сессию.
- Пагинирует `trd-buy`: активные статусы (`goszakup.import.statuses`) и
  `publish_date >= now - since-days`.
- **Фильтр релевантности по ключевым словам** (`goszakup.import.keywords`,
  case-insensitive): объявление берётся, если ключевое слово встречается в `name_ru`
  ИЛИ в названии любого его лота.
- Резолвит регион (см. §5).
- Мапит в `Tender` + `TenderLot` (см. §4).
- **Идемпотентный upsert по `source_ext_id` (= `number_anno`)** в рамках `market=KZ`:
  существующий тендер обновляется (статус, суммы, дедлайн, лоты — через коллекцию
  `t.getLots()` с orphanRemoval, НЕ через `tenderLotRepository.delete`), нового не
  создаёт. Лоты пересобираются через коллекцию (урок §7/§14 CLAUDE.md).
- Ставит `market=KZ`, `source=PUBLIC_TENDER`, `facility=null`, `currency=KZT`,
  `customerName`/`customerBin`, `region`/`regionKato`.
- Возвращает `ImportSummary { fetched, matched, created, updated, skipped, errors }`.

### 3.3 `GoszakupImportScheduler`
- `@Scheduled(fixedDelayString="${goszakup.import.poll-ms:21600000}")` (6 ч).
- Гард флагом `goszakup.import.enabled` (по умолчанию ВЫКЛ) и наличием токена.
- **Дисциплина фонового потока (§6):** `MarketContext.set(Market.KZ)` ДО вызова
  сервиса, `MarketContext.clear()` в `finally`. Зеркало `MailPollScheduler`.

### 3.4 REST
- `POST /api/tenders/import-kz` — ручной запуск (под `@PreAuthorize("hasRole('ADMIN')")`),
  возвращает `ImportSummary`. Работает всегда (при наличии токена), независимо от
  флага расписания.

## 4. Модель данных — миграция **V3**

Новые колонки в таблице `tender` (новой миграцией `V3__goszakup_tender_fields.sql`,
V1/V2 не трогаем — §10 CLAUDE.md):

```sql
ALTER TABLE tender ADD COLUMN source_ext_id VARCHAR(64);
ALTER TABLE tender ADD COLUMN region        VARCHAR(100);
ALTER TABLE tender ADD COLUMN region_kato   VARCHAR(20);
ALTER TABLE tender ADD COLUMN customer_name VARCHAR(500);
ALTER TABLE tender ADD COLUMN customer_bin  VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_tender_region ON tender(market, region);
-- ключ дедупликации импорта (только для импортных строк):
CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_market_extid
    ON tender(market, source_ext_id) WHERE source_ext_id IS NOT NULL;
```

**Маппинг goszakup → Tender (reuse существующих полей):**

| Tender | Источник |
|--------|----------|
| `tenderNumber` | `number_anno` |
| `source_ext_id` | `number_anno` (ключ upsert) |
| `status` | маппинг `ref_buy_status_id` → строка |
| `deadline` | `end_date` |
| `publishDate` | `publish_date` |
| `totalCost` | `total_sum` |
| `currency` | `KZT` |
| `description` | `name_ru` |
| `deliveryAddress` | адрес (из subject/лота, если доступен) |
| `region` / `region_kato` | см. §5 |
| `customerName` / `customerBin` | заказчик (`customer_bin` + name из subject) |
| `source` | `PUBLIC_TENDER` |
| `market` | `KZ` |
| `facility` | `null` (госзаказчик ≠ клиника West-Med) |

**TenderLot:** `lotNumber`=`lot_number`, `equipName`=`name_ru`, `quantity`=`count`,
`maxCost`=`amount`.

**Сущности/DTO:** в `Tender` добавить поля `sourceExtId`, `region`, `regionKato`,
`customerName`, `customerBin` (Lombok). Отразить `region`/`customerName` в
`TenderResponse` и мэппере (MapStruct).

## 5. Резолв региона ⚠️ (точка проверки живым токеном)

Регион в полях `trd_buy` напрямую не присутствует. План:
1. Взять КАТО заказчика из `subject` (`/v2/subject/{customer_bin}`) — поле КАТО
   (`ref_kato_id` / аналог).
2. Свести КАТО → одну из **20 областей** (17 областей + Астана/Алматы/Шымкент) через
   **статическую таблицу-маппер по ведущим цифрам КАТО** (в коде, без доп. запросов).
3. **Фолбэк** (если поле КАТО окажется в другом месте/пустым): парс региона из
   текстового адреса по словарю названий областей.

**Точки, подтверждаемые живым токеном при реализации** (пробой схемы перед финалом):
(1) где именно лежит КАТО/регион — `subject` vs `trd_buy` (для §5);
(2) конкретные `ref_buy_status_id` активных статусов приёма заявок — для
`goszakup.import.statuses` (§7); до пробоя в конфиге placeholder.

Резолвер строится с фолбэком, чтобы импорт не падал и не
блокировал создание тендера, если регион не определился (тогда `region=null`, тендер
всё равно импортируется и попадает в фильтр как «Регион не указан»).

## 6. Фронтенд (Angular)

Страница `frontend/src/app/pages/tenders/tenders.component.ts`:
- **Кнопка «Обновить тендеры»** (видна только при `market=KZ`) → `POST /api/tenders/import-kz`,
  спиннер на время запроса, по завершении — перезагрузка списка + `NotificationService`
  со сводкой (создано/обновлено). Ошибка токена → `notification.error`.
- **Фильтр по региону** — `<select>` (виден только при KZ): опции — **статический
  список 20 регионов** (стабилен, не зависит от того, что уже подгрузилось) + «Все»
  и «Регион не указан». Фильтрация **клиентская**, в существующем `applyTendersFilter()`
  (регион приходит в DTO). При RF фильтр скрыт. Список 20 регионов держим в одном месте
  (фронт-константа), согласован с `KatoRegionResolver` на бэке.
- На карточке тендера (KZ) показывать `region` и `customerName`.
- `ApiService`: метод `importKzTenders()` → `POST /api/tenders/import-kz`.

Решение по фильтру: **клиентский для v1** (консистентно с текущей страницей, дёшево).
Если объём KZ-тендеров вырастет — вынести в серверный параметр `/api/tenders/search`
отдельной итерацией.

## 7. Конфигурация (`application.yaml` + env)

```yaml
goszakup:
  api:
    base-url: https://ows.goszakup.gov.kz/v2
    token: ${GOSZAKUP_TOKEN:}
  import:
    enabled: ${GOSZAKUP_IMPORT_ENABLED:false}
    poll-ms: 21600000        # 6 часов
    since-days: 30
    page-size: 50
    statuses: <id активных статусов приёма заявок>
    keywords: аппарат,УЗИ,ультразвук,томограф,рентген,монитор пациента,дефибриллятор,ИВЛ,анализатор,центрифуга,стерилизатор,...
```

Команда запуска с живым импортом (по аналогии с IMAP):
```
GOSZAKUP_IMPORT_ENABLED=true GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun
```

## 8. Тестирование

- **Интеграционный `GoszakupImportServiceTest`** (`@SpringBootTest @Transactional`,
  реальный Postgres nirdb): фейковый `GoszakupClient` отдаёт канонический JSON
  trd-buy + lots + subject. Проверяем:
  - фильтр по ключевым словам (мед оставлен, немед отброшен);
  - маппинг полей (number_anno→tenderNumber, total_sum→totalCost, end_date→deadline,
    лоты, currency=KZT, source=PUBLIC_TENDER, market=KZ, facility=null);
  - **идемпотентность**: 2 прогона → второй апдейтит, не плодит дубли
    (count не растёт; лоты пересобраны через коллекцию);
  - резолв региона по КАТО + фолбэк (region=null не ломает импорт).
- **Тест изоляции рынка**: импортный KZ-тендер не виден при `MarketContext=RF`.
- **Без реальной сети** в тестах (фейк-клиент), паттерн как у `RegistryImportService`.
- Существующий гейт «зелёного»: только пред-существующие 2 падения
  `ApplyAutoFillServiceTest` (§13).
- **Фронт-гейт:** `cd frontend && npm run build`.
- **Живая проверка (§5 CLAUDE.md):** браузер Playwright — KZ, кнопка «Обновить»,
  фильтр по региону, карточка тендера. Бэк с живым токеном перед заявлением «готово».

## 9. Вне скоупа (YAGNI)

- Город/район (2-й уровень гео) — только регион/область.
- Импорт RF-реестра (zakupki.gov.ru).
- GraphQL (берём REST v2 — проще курсорная пагинация).
- Автопривязка импортных тендеров к autopodbor/applies — список и так работает с любым
  тендером.
- Скрейпинг-фолбэк HTML.
- Серверный (DB-уровень) гео-фильтр — v1 клиентский.

## 10. Затрагиваемые/новые файлы (ориентир)

**Новые (бэк):**
- `entity/dto`/клиентские DTO: `TrdBuyDto`, `LotDto`, `SubjectDto`, `ImportSummary`.
- `service/GoszakupClient` (интерфейс) + `service/GoszakupHttpClient`.
- `service/GoszakupImportService`.
- `service/GoszakupImportScheduler`.
- `service/KatoRegionResolver` (статический маппер КАТО→область).
- `db/migration/V3__goszakup_tender_fields.sql`.
- тест `GoszakupImportServiceTest` + фейк-клиент.

**Изменяемые:**
- `entity/Tender.java` (+5 полей).
- `dto/response/TenderResponse.java` + MapStruct-маппер (+region, +customerName).
- `controller/TenderController.java` (+ `POST /import-kz`).
- `application.yaml` (+ блок `goszakup`).
- фронт: `pages/tenders/tenders.component.ts`, `services/api.service.ts`.
