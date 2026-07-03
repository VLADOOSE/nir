# Запрос КП по лотам тендера + предложенная модель + единый канал отправки КП — дизайн

**Дата:** 2026-07-03. **Ветка:** `feat/lot-price-request`.
**Статус:** одобрен пользователем (брейнсторм 2026-07-03).

## 1. Контекст и цель

Импортированные с goszakup.gov.kz KZ-тендеры имеют лоты «наименование + спека + кол-во» — без бренда,
без типа оборудования, без структурных габаритов. Существующие КП-механики к ним неприменимы:
bulk-модалка и smart-match требуют каталожного матча (тип + габариты), а единственный путь с реальным
письмом (`BulkPriceRequestService.sendGroup`) жёстко брендирован «ООО «Регион-Мед»».
Параллельно обнаружен разрыв: «Запросить КП» в карточке частной заявки (`POST /api/price-requests`)
**не отправляет письмо** — создаёт запись со статусом SENT, и почтовый round-trip (ответ поставщика
по токену `[КП-id]` → RESPONDED) для частников фактически не замкнут.

Блок закрывает полный конвейер для тендера (оба рынка):

> лот → (спека → габариты/вес → матч каталога) → проверка модели по реестру → **апрув модели**
> («предложенное оборудование» лота) → выбор лотов и поставщиков → **реальное письмо** запроса КП
> (по модели, а без модели — по голому лоту) → ответы через существующую секцию «Запросы КП».

Решения брейнсторма (зафиксированы ответами пользователя):
1. **Реальное письмо + единый путь отправки** — все флоу (лотовый, частные заявки, smart-match, bulk-модалка) шлют через один сервис; дыра частников закрывается.
2. **Подбор поставщиков: вручную + подсказка** из реестра/бренда предложенной модели.
3. **Обе точки входа**: чекбоксы на лотах + кнопка «КП» в строке лота.
4. **Оба рынка**; каталожный путь эволюционирует: спека → габариты/вес → матч → реестр → апрув → КП по модели.
5. **Весь пайплайн в этом блоке** (включая парсер спеки и апрув модели).

## 2. Переиспользуемое (уже в main)

- `PriceRequest`/`PriceRequestItem`: item уже допускает `medEquipment = null` + `tenderLot` — схема КП не меняется.
- `EquipmentScoringService.scoreLot(lotId, weights, preset)` + smart-match панель (пресеты, веса, `bestDistributor`, `recommended`).
- `MedEquipment.registrationStatus` + FK `registration → med_registry(reg_number)` (+ `POST /api/equipment/{id}/registration`) — реестр-статус модели уже есть.
- `RegistryMatchService.candidatesForLot(lotId)` — реестр-кандидаты НЦЭЛС по лоту (панель «Реестр»).
- `PrivateRequestSourcingService.carriesBrand`-логика (бренд ⊇ подстрока, case-insensitive).
- `KpToken.subjectToken/parse` + `MailReceiveService` — round-trip по токену уже работает, менять не надо.
- `Market.companyShortName()` («ООО «РЕГИОН-МЕД»» / «ТОО «West-Med»»), `EmailService.sendEmail`.
- Секция «Запросы КП» в карточке тендера (наценка, ответы, принять, сформировать заявку) — переиспользуется как есть.

## 3. Архитектура (бэкенд)

### 3.1 Единый канал отправки КП

**`KpEmailComposer`** (новый, `service/`) — единственное место построения темы/тела письма КП.

- Вход: собранный `PriceRequest` (с tender, distributor, items). Выход: `record Composed(String subject, String body)`.
- Брендинг из **`pr.getMarket().companyShortName()`** (не из `MarketContext` — надёжно и для фоновых сценариев).
- Тема: `[КП-<id>] Запрос КП по тендеру № <tenderNumber>` (source=PUBLIC_TENDER) /
  `[КП-<id>] Запрос КП по заявке <tenderNumber>` (source=PRIVATE_REQUEST). Токен — через `KpToken.subjectToken`.
- Тело (скелет, единый для рынков, различаются компания/реестр/ссылка):
  1. Обращение: «Уважаемый(ая) <Фамилия Имя>!» (как сейчас, null-safe).
  2. «<Компания рынка> просит предоставить коммерческое предложение по позициям <тендера № … / заявки …>:»
  3. Позиции:
     - item с моделью (`medEquipment != null`): `— Лот <N>: <model.name> (<model.manufact>)<, РУ № <regNumber> если привязано> — <qty> шт.`
     - item без модели: `— Лот <N>: <lot.equipName><(<lot.manufact>) если задан> — <qty> шт.` + при непустой
       `requiredSpec` блок «Требования (из ТЗ):» с обрезкой до **1200 символов** (+ «… (полное ТЗ — по ссылке на объявление)»).
  4. Если `tender.deadline != null`: «Приём заявок до <дата>.»
  5. Ссылка на объявление: KZ + `sourceExtId != null` → `https://goszakup.gov.kz/ru/announce/index/<sourceExtId>`;
     RF (PUBLIC_TENDER) → `https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString=<номер>`;
     частные заявки и ручные KZ-тендеры — без ссылки.
  6. «Просим указать: цену за единицу, № регистрационного удостоверения (<НЦЭЛС РК / Росздравнадзора> — по рынку)
     на предлагаемую модель, сроки поставки, условия оплаты, гарантию.»
  7. Подпись: «С уважением, <Компания рынка>».

**`PriceRequestSendService`** (новый, `service/`):

```
record SendItem(Long tenderLotId, Long medEquipmentId /*nullable*/, Integer requestedQuantity)
record SendResult(Long priceRequestId, Long distributorId, String distributorName,
                  boolean emailSent, String reason /*null | NO_EMAIL | SEND_FAILED*/)
List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items)
```

- Для КАЖДОГО дистрибьютора: создаёт `PriceRequest` (status `SENT`, `sentAt=now`, market — штамп как обычно)
  + items (lot required, medEquipment по id если задан, quantity) → `KpEmailComposer` → `EmailService.sendEmail`.
- Ошибки отправки не валят транзакцию записи: email пустой/не задан → `emailSent=false, reason=NO_EMAIL`;
  SMTP-исключение → `log.warn` + `emailSent=false, reason=SEND_FAILED` (см. §5). Запись КП в БД остаётся
  в любом случае (поведение как у текущего `sendGroup`).
- Повторная отправка тому же поставщику — новый `PriceRequest` (несколько раундов, как у частников).
- Валидации: items не пуст; лоты принадлежат tender'у; quantity ≥ 1.

**Эндпоинт:** `POST /api/price-requests/send` (`@PreAuthorize("hasRole('ADMIN')")`, в `PriceRequestController`):

```json
{ "tenderId": 1, "distributorIds": [2, 5],
  "items": [ { "tenderLotId": 10, "medEquipmentId": 7, "requestedQuantity": 2 },
             { "tenderLotId": 11, "medEquipmentId": null, "requestedQuantity": 1 } ] }
→ [ { "priceRequestId": 42, "distributorId": 2, "distributorName": "…", "emailSent": true, "reason": null }, … ]
```

**Перевод call-sites (все четыре):** лотовый флоу (новый), карточка частной заявки (`requestPrice`/`requestGroup`),
smart-match `requestPrice`, bulk-модалка — фронт зовёт `/api/price-requests/send`.
`POST /api/bulk-price/send` становится тонким делегатом к `PriceRequestSendService` (кандидат на удаление
следующим блоком); письмостроение из `BulkPriceRequestService` (`buildEmailBody`, вызов `EmailService`) выпиливается,
`buildPreview` остаётся. «Голый» `POST /api/price-requests` остаётся чистым CRUD без письма (UI им больше не пользуется).

### 3.2 Парсер спеки → габариты/вес

**`SpecConstraintExtractor`** (новый, `util/` или `service/`, чистая функция + юнит-тесты):

- Вход: текст `requiredSpec`. Выход: `record SpecConstraints(Integer maxLengthMm, Integer maxWidthMm,
  Integer maxHeightMm, BigDecimal maxWeightKg, List<String> snippets)` — все поля nullable, snippets — исходные
  фрагменты, из которых извлечены значения (для показа в UI).
- MVP-извлечение:
  - **Триплет габаритов**: `(габарит|размер)…(не более|до|≤|максимум)? A×B×C (мм|см|м)` — разделители `х x × *`,
    допускаются пробелы и десятичные; нормализация в мм. Триплет без явного «не менее» трактуем как верхнее ограничение.
  - **Вес**: `(вес|масса)…(не более|до|≤|максимум)? X (кг|г)` — нормализация в кг.
  - Формулировки «не менее / от / минимум» — **игнорируются** (нижняя граница — не наш фильтр).
  - Пооосевые «длина не более X мм» — вне скоупа (следующая итерация).
  - Ничего не распарсилось → все null (легальный результат).
- Интеграция: в `EquipmentScoringService.scoreLot` — если у лота ВСЕ структурные `max*`-поля null и `requiredSpec`
  непуст → извлечь и использовать как констрейнты матча (те же параметры `findMatchingEquipment`); структурные поля
  лота, если заданы, имеют приоритет (парсер не вызывается). В `EquipmentMatchResponse` добавить блок
  `specDerived {lengthMm, widthMm, heightMm, weightKg, snippets}` (null, если парсер не применялся/ничего не нашёл).
  В лот НИЧЕГО не записывается.

### 3.3 Предложенная модель лота (апрув)

- **Миграция `V4__lot_proposed_equipment.sql`**:
  `ALTER TABLE tender_lot ADD COLUMN proposed_equipment_id BIGINT REFERENCES med_equipment(id) ON DELETE SET NULL;`
- `TenderLot.proposedEquipment` — `@ManyToOne(fetch = EAGER)` (консистентно с остальными связями лота).
- Эндпоинты (в существующем контроллере, обслуживающем `/api/lots`):
  - `POST /api/lots/{id}/proposed-equipment` `{ "equipmentId": 7 }` — установить/заменить (ADMIN);
  - `DELETE /api/lots/{id}/proposed-equipment` — снять (ADMIN).
  Обновление поля существующего лота через `tenderLotRepository.save(lot)` — безопасно (gotcha про
  orphanRemoval касается добавления/удаления лотов, не правки полей).
- Lot-DTO: `proposedEquipment {id, name, manufact, registrationStatus, regNumber /*из registration, nullable*/}`.
- Рыночная изоляция: оборудование ищется обычным репозиторием (фильтр рынка применяется аспектом) —
  чужой рынок просто «не найден».

### 3.4 Подсказки поставщиков по лотам

`GET /api/tenders/{id}/lot-sourcing?lotIds=1,2` (новый сервис `LotSourcingService`) →

```json
{ "distributors": [ { "distributor": {…}, "preselect": true,
    "matchedBrands": [ { "brand": "Mindray", "via": "PROPOSED_MODEL", "lotId": 10 },
                       { "brand": "GE", "via": "REGISTRY", "lotId": 11 } ] }, … ] }
```

- Все дистрибьюторы активного рынка; аннотации по каждому выбранному лоту:
  1. у лота есть предложенная модель → её `manufact` матчится на бренды дистрибьютора (`carriesBrand`-логика,
     вынести в общий хелпер, чтобы не дублировать с `PrivateRequestSourcingService`);
  2. иначе — производители топ-кандидатов реестра по лоту (`candidatesForLot`, топ-5, порог score ≥ 0.35)
     матчатся на бренды дистрибьютора.
- `preselect = matchedBrands не пуст`. Никакой автоотправки — только предотметка чекбоксов.

## 4. Frontend (Angular, `tenders.component.ts` + карточка частной заявки)

### Карточка тендера (оба рынка)
- Таблица лотов: **колонка-чекбокс** (+ «выбрать все» в шапке); строка «Предложено: <модель> (<бренд>)»
  с бейджем реестра модели и кнопкой «✕ снять» (под названием лота или отдельной колонкой); микро-строка
  «КП: <поставщики>» у лота, по которому уже запрашивали (аналог `requestedDistributorsFor` частников).
- Тулбар: **«Запросить КП по выбранным (N)»** (активна при N>0). В строке лота — кнопка **«КП»**
  (= отметить только этот лот + открыть панель).
- **Панель выбора поставщиков** (инлайн-секция по образцу registry-panel): список дистрибьюторов с чекбоксами,
  подсвеченные бейджи «возит: <бренд>» (из `lot-sourcing`), совпавшие предотмечены; кнопка
  «Отправить запросы (M)» → `POST /api/price-requests/send` (items: выбранные лоты,
  `medEquipmentId = lot.proposedEquipment?.id ?? null`, `requestedQuantity = lot.quantity ?? 1`).
  После ответа: тост «Отправлено X писем» (+ «у «Y» нет email» при наличии), сброс выбора, `loadPriceRequests()`.
- Smart-match панель: у кандидата кнопка **«Утвердить модель»** → `POST /api/lots/{id}/proposed-equipment`;
  утверждённый помечен бейджем «Предложена»; повторный апрув другого кандидата заменяет выбор.
  Блок «Ограничения из спеки: ≤ A×B×C мм, ≤ X кг» при наличии `specDerived`.
  Кнопка «Запросить КП» кандидата (bestDistributor) переводится на `/send`.
- Секция «Запросы КП»: колонка «Модель» → `it.medEquipment?.name || '— по лоту'`.
- Bulk-модалка: остаётся на обоих рынках, отправка переключается на `/send` (items с medEquipmentId).

### Карточка частной заявки
- `requestPrice()` и `requestGroup()` → `POST /api/price-requests/send` (items как сейчас, medEquipmentId=null).
- Тост честный: «КП отправлено» / «КП создано, но у поставщика нет email».

## 5. Крайние случаи и ошибки

- Дистрибьютор без email → КП создаётся, `emailSent=false, reason=NO_EMAIL` (тост предупреждает).
- SMTP недоступен → КП создаётся, `emailSent=false, reason=SEND_FAILED`, `log.warn`.
- Пустые `items`/`distributorIds` → 400. Лот чужого тендера → 400. Кол-во < 1 → 400.
- Спека без распознаваемых габаритов → матч без габаритных ограничений (как раньше), `specDerived=null`.
- «не менее 500 мм» в спеке → НЕ ограничение (парсер игнорирует).
- Апрув оборудования чужого рынка / несуществующего → 404 (рыночный фильтр).
- Удаление оборудования, предложенного в лоте → `ON DELETE SET NULL` (лот живёт дальше без предложения).
- Повторный «Запросить КП» тому же поставщику → новый PriceRequest (осознанно, раунды).
- Ответ поставщика письмом с токеном → существующий IMAP-путь ставит RESPONDED (не трогаем).

## 6. Тестирование

- **Юнит:** `SpecConstraintExtractor` — триплет мм/см/м, вес кг/г, «не более/до», игнор «не менее»,
  разделители х/×/*, мусорный текст → null-результат; на реальных спеках импортированных лотов.
  `KpEmailComposer` — KZ/RF брендинг, тендер/частная заявка, позиция с моделью (+РУ №) и без (обрезка спеки 1200),
  токен в теме, дедлайн/ссылка на объявление.
- **Интеграционные** (`@SpringBootTest @Transactional`, nirdb): `PriceRequestSendService` — создание PR+items
  на нескольких дистрибьюторов + отправка через **GreenMail SMTP** (проверка темы с токеном и адресата);
  NO_EMAIL-кейс; рыночная изоляция созданных КП; апрув-эндпоинты (установить/заменить/снять);
  `LotSourcingService` (подсказка по бренду предложенной модели и по производителю реестр-кандидата);
  `scoreLot` со specDerived-констрейнтами (лот без структурных полей, спека с габаритами → каталог фильтруется).
- **Гейты:** `./gradlew test` — только 2 известных падения `ApplyAutoFillServiceTest`; `cd frontend && npm run build`.
- **Живая проверка (Playwright, до заявления «готово»):** рынок KZ → живой импортированный тендер →
  smart-match (виден блок «из спеки») → «Утвердить модель» → чекбоксы лотов → панель поставщиков
  (бейдж «возит…») → отправка → **MailHog UI**: письмо с `[КП-id]`, подписью West-Med, моделью/спекой →
  секция «Запросы КП» пополнилась. Аналогичный прогон «Запросить КП» из частной заявки (письмо реально уходит).

## 7. Вне скоупа (бэклог)

- Пооосевые ограничения из спеки («длина не более X»), диапазоны, LLM-парсер спеки.
- Нечёткий матч брендов (транслит «Mindray»↔«Майндрей») — остаётся в общем бэклоге.
- Удаление `POST /api/bulk-price/send` (после этого блока он — тонкий делегат).
- Структурный разбор ответа поставщика (цена из письма → responsePrice).
- Наполнение KZ-каталога (создание позиции каталога из реестр-кандидата) — отдельный блок.

## 8. Порядок работ (для плана)

1. V4 + `TenderLot.proposedEquipment` + DTO/маппер + апрув-эндпоинты.
2. `SpecConstraintExtractor` (TDD) + интеграция в `scoreLot` + `specDerived` в ответе.
3. `KpEmailComposer` (TDD) + `PriceRequestSendService` + `POST /api/price-requests/send` + делегат bulk-price/send.
4. `LotSourcingService` + `GET /api/tenders/{id}/lot-sourcing`.
5. Фронт: карточка тендера (чекбоксы, панель, апрув в smart-match, «— по лоту», specDerived-блок).
6. Фронт: перевод частной карточки, smart-match, bulk-модалки на `/send`.
7. Полный прогон тестов + Playwright live-проверка + тур «куда смотреть».

Гочи из CLAUDE.md, обязательные к соблюдению: миграции только новые (V4), `@FilterDef` не переобъявлять,
лоты создавать/удалять только через коллекцию тендера (правка полей лота — можно напрямую),
`MarketContext` в фоне не появляется (весь блок — HTTP-пути), секреты не эхо-печатать.
