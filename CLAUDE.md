# CLAUDE.md — АИС «Регион-Мед / West-Med»: тендеры и частные заявки на медоборудование

## 1. Что это за проект (актуально)

Изначально — дипломная АИС для ООО «Регион-Мед» (РФ). **Диплом защищён; теперь это дорабатывается как реальный продукт** для двух связанных компаний:

- **Регион-Мед (РФ, рубли ₽)** — тендеры на госзакупках (zakupki.gov.ru). По закону в тендерах нельзя называть бренды → заказчик даёт параметры → наш **автоподбор** оборудования из каталога.
- **West-Med (KZ, тенге ₸)** — частные клиники Казахстана. Клиника **прямо называет бренд/модель** (заявка) → проверяем **регистрацию в реестре НЦЭЛС РК** + сорсинг (запрос КП у поставщиков). West-Med закупает у дистрибьюторов; если что-то выгоднее купить в РФ — берётся на Регион-Мед, который продаёт/везёт в Казахстан для West-Med.

Компании **не работают вместе**, у каждой свой рынок. Реализован **глобальный переключатель рынка** (РФ ↔ KZ) — одна БД, колонка `market`, данные одного рынка не видны на другом.

Поток «**Частники**» (West-Med) — главное направление доработок: клиника пишет на **info@westmed.kz** с таблицей оборудования (Excel) → система читает почту, парсит → частная заявка → проверка реестра → подбор поставщиков по брендам → запрос КП дистрибьюторам с **zakup@westmed.kz**.

## 2. Стандартные инструкции пользователя (соблюдать всегда)

- **Все делегируемые агенты (Agent/subagent) — на Fable 5** (наследуют модель сессии, `model` не переопределять). Решение пользователя 2026-07-04 (ранее было «все субагенты — Opus 4.8»).
- **Строй как реальный продукт, не как диплом** — диплом сдан, дипломные ограничения не нужны.
- **После каждого блока** — давать «куда смотреть» (click-by-click тур) и **проверять фичу вживую в браузере (Playwright)** перед заявлением «готово».
- Пользователь технический, любит скорость; даёт рекомендации делать «как лучше».

## 3. Рабочий процесс (superpowers)

Каждый блок: **brainstorming → spec → writing-plans → subagent-driven-development (SDD) → whole-branch review → мерж в main**.
- Спеки: `docs/superpowers/specs/YYYY-MM-DD-<тема>-design.md`. Планы: `docs/superpowers/plans/YYYY-MM-DD-<тема>.md`.
- SDD: per-task реализатор + ревьюер + fix-loop + финальный whole-branch ревью; ledger в `.superpowers/sdd/progress.md`.
- Мелкие правки/фиксы — инлайн на короткой ветке + мерж (не на main напрямую: `git checkout -b ...` → commit → `merge --ff-only` → удалить ветку).
- Каждый commit заканчивать: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Иногда вылезают **транзиентные срывы субагентов** (0 tool_uses) — переотправить или сделать инлайн.

## 4. Стек

- **Backend:** Java 17, Spring Boot **3.5.6**, Spring Web (REST), Spring Data JPA / Hibernate 6, Spring Security (method security), MapStruct 1.5.5, Lombok, Gradle 8.14.
- **Миграции БД:** **Flyway** (схема + сид — версионные миграции; см. §10). PostgreSQL 17.
- **Excel:** Apache POI 5.2.5 (`poi-ooxml` + `poi` транзитивно — чтение/запись `.xlsx`/`.xls`).
- **Почта:** `spring-boot-starter-mail` (JavaMailSender для SMTP) + сырой `jakarta.mail` (IMAP — `Store`/`Folder`). GreenMail (`com.icegreen:greenmail-junit5:2.1.2`) — встроенный IMAP/SMTP в тестах.
- **Отчёты:** JasperReports 6.21, профит-Excel.
- **Frontend:** Angular **21** (standalone-компоненты, инлайн-шаблоны + `styles: []` SCSS). `ApiService` (база `/api`, прокси на :8080).

## 5. Среда разработки (КРИТИЧНО для свежей сессии)

### Запуск
- **Backend:** `./gradlew bootRun` (порт **8080**). Компилирует + стартует.
- **Frontend:** `cd frontend && npm start` (dev-сервер **4200**, HMR подхватывает правки).
- **Логин в UI:** `admin` / `admin` (или `operator` / `operator`). Переключатель рынка слева вверху: «Регион-Мед (РФ) ₽» / «West-Med (KZ) ₸». Смена рынка перезагружает страницу, `localStorage['ais.market']` = `RF`/`KZ`.

### Песочница Bash и БД ⚠️
- **Bash-sandbox блокирует localhost:5432** → ЛЮБЫЕ `./gradlew` и команды к БД запускать с `dangerouslyDisableSandbox: true`.
- **psql:** `/Library/PostgreSQL/17/bin/psql` (там же `dropdb`/`createdb`), `PGPASSWORD=admin`, пользователь `postgres`, БД **`nirdb`**.
- **nirdb ДОЛЖНА быть UTF-8 локалью** (LC_CTYPE/LC_COLLATE = `en_US.UTF-8`, НЕ `C`/`POSIX`) — иначе pg_trgm даёт пустые триграммы для кириллицы и реестр-матчинг молча возвращает 0. Пересоздание: `dropdb nirdb` + `createdb nirdb --template=template0 --lc-ctype=en_US.UTF-8 --lc-collate=en_US.UTF-8`, затем `ALTER DATABASE nirdb SET random_page_cost = 1.1;` (см. §11).

### SMTP (dev) — MailHog
- `spring.mail.*` настроен на **MailHog** (фейковый SMTP, `localhost:1025`, UI `http://localhost:8025`, без auth). Запуск: `docker run -d --name nir2-mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog`.

### Живой приём почты (IMAP) — Mail.ru
Ящики хостятся на **Mail.ru для бизнеса** (домен westmed.kz). Для Mail.ru нужен **пароль приложения** (не основной пароль; Настройки → Безопасность → «Пароли для внешних приложений», + включить доступ по IMAP).
- **info@westmed.kz** — входящие запросы от клиентов (читаем). Пароль приложения держим в `/tmp/info.pass` (вне репо).
- **zakup@westmed.kz** — отправка дистрибьюторам (+ ответы поставщиков). Пароль в `/tmp/imap.pass`.
- IMAP: `imap.mail.ru:993` (imaps). SMTP: `smtp.mail.ru:465` (SSL) / `587` (STARTTLS).
- **Команда запуска бэка с живым приёмом info@** (приём по умолчанию ВЫКЛ):
  ```
  MAIL_IMAP_ENABLED=true MAIL_IMAP_HOST=imap.mail.ru MAIL_IMAP_PORT=993 \
  MAIL_IMAP_PROTOCOL=imaps MAIL_IMAP_USERNAME=info@westmed.kz \
  MAIL_IMAP_PASSWORD="$(cat /tmp/info.pass)" MAIL_IMAP_MARKET=KZ \
  MAIL_IMAP_SINCE_MINUTES=1440 ./gradlew bootRun
  ```
- Если перезапустить **без** этих env — приём почты выключится (это нормальное состояние по умолчанию).
- ⚠️ **Безопасность:** пароли приложений Mail.ru пользователь вставлял в чат — на проде их стоит перевыпустить. Никогда не эхо-печатать содержимое `/tmp/*.pass` (читать только через `$(cat ...)`).

### Госзакуп РК (импорт KZ-тендеров) — живой токен
- Токен «Унифицированных сервисов» goszakup.gov.kz лежит в `/tmp/goszakup.token` (вне репо; тоже не эхо-печатать). Перевыпуск: Портал → «Профиль участника → Выпуск токена (для разработчиков)», нужна роль «Администратор организации».
- Запуск бэка с импортом: `GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun`. Кнопка «Обновить тендеры» на `/tenders` (рынок KZ) зовёт `POST /api/tenders/import-kz` синхронно (~2–3 мин, ~20 страниц + subject/lots per-item). Авто-поллинг раз в 6ч — только при `GOSZAKUP_IMPORT_ENABLED=true` (дефолт выкл).
- **Живые формы v2 API** (сняты токеном, закреплены в тестах `GoszakupDtoJsonTest`/`GoszakupHttpClientTest`): `/trd-buy` отдаёт только `org_bin` (`customer_bin` нет → `TrdBuyDto.effectiveBin()`); subject по БИН — **`/subject/biin/{биин}`** (`/subject/{id}` — по внутреннему id!), на неизвестный БИН — **200 и `[]`**, не 404; адреса subject — массив `address[].{address,kato_code}`.
- Словарь статусов `/v2/refs/ref_buy_status` (для T9-маппинга): 210/220/230/240/245 — «Опубликовано…» (идёт приём), 250–330 — рассмотрение/протоколы, 350 — «Завершено», 410 отказ, 420 приостановлено, 430 отменено. `mapStatus` пока всё маппит в ACTIVE (TODO T9).
- **Регион-импорт (v3 GraphQL):** выбранный регион в фильтре `/tenders` меняет кнопку на «Обновить тендеры — <регион>» → `POST /import-kz?region=<имя>` → v3 `TrdBuy(filter:{kato:[...]}, after:<lastId>)`. Фильтр kato принимает ТОЛЬКО точные 9-значные коды (масок нет) → шлём все коды области массивом (`KatoDictionary`: справочник `/v2/refs/ref_kato`, ~17k записей, 34 страницы по 500, кеш на процесс; карта регион→префикс ab захардкожена, вкл. новые области 10/33/61/62). Серверного keyword-поиска НЕТ ни в v2 (фильтры молча игнорируются), ни в v3 (`nameRu` — только точное совпадение). Регион поставки ≠ регион заказчика (республиканские заказчики из Астаны) → при регион-импорте `region` ставится принудительно (`upsertOne(..., regionOverride)`). v3-ответ парсится тем же `TrdBuyDto` через GraphQL-алиасы (`number_anno:numberAnno`).
- **Статусы тендеров (T9 закрыт):** `mapStatus`: 210–245 → ACTIVE, 410/420/430 → CANCELLED, ≥250 → COMPLETED; плюс правило дедлайна в writer (площадка держит «Опубликовано» после дедлайна → локально COMPLETED). `TenderStatusScheduler` (ежедневно 00:05 + на старте, оба рынка по §6) закрывает просроченные ACTIVE; следующий импорт сверяет с площадкой (продлили — вернётся ACTIVE). UI `/tenders`: сортировка «Сначала новые» (дефолт) / «Скоро дедлайн», мини-чипы лотов на карточке (title = спека), спека в таблице лотов разворачивается кликом, бейдж/фильтр «Отменён».
- **Импорт асинхронный с живым прогрессом:** `POST /import-kz` стартует фон (single-thread executor в `GoszakupImportScheduler`, `MarketContext.set(KZ)` В ФОНОВОМ потоке) и сразу возвращает статус; сервис наполняет ПЕРЕДАННЫЙ `ImportSummary` по ходу (`fillImport`, поля `pagesRead/maxPages`). `GET /import-kz/status` → `{running, lastFinishedAt, lastRegion, lastSummary}`; UI поллит каждые 2.5с: прогресс-бар (стр. N/60 · получено · подходящих · создано · обновлено · ошибок), по завершении — тост + перезагрузка списка + «Обновлено N мин назад»; кнопка блокируется и при чужом прогоне. Повторный POST во время прогона возвращает текущий статус (не дублирует).
- **Реестр по лоту:** кнопка «Реестр» в таблице лотов карточки тендера → `GET /api/lots/{id}/registry-candidates` (`RegistryMatchService.candidatesForLot` — тот же trigram-примитив, что у частных заявок) → панель топ-кандидатов НЦЭЛС (похожесть %, РУ №, наименование, производитель, страна, срок).

### Браузерная проверка (Playwright MCP)
- Навигация на `http://localhost:4200`, логин admin/admin, `localStorage.setItem('ais.market','KZ')`, навигация на нужную страницу (`?openId=<id>` открывает карточку). Сессия слетает при рестарте бэка — логиниться заново.
- Заполнять инпуты через `browser_type`/`fill` (триггерит ngModel); `browser_evaluate` с нативным сеттером Angular не всегда подхватывает. Refs в снапшотах устаревают — снимать свежий снапшот прямо перед кликом.

## 6. Архитектура

Монолит, layered: Controller → Service → Repository → DTO (MapStruct мапперы). REST/JSON. Entity на Lombok, PK BIGSERIAL (IDENTITY). Сервисы — constructor injection без `@Autowired`, `@Transactional` на записи. Контроллеры `@RestController`, «голые» DTO; записи под `@PreAuthorize("hasRole('ADMIN')")` (method security включена в `SecurityConfig`). `@EnableScheduling` на `Nir2Application`. OSIV включён.

### Многорыночность (РФ/KZ) — КРИТИЧНАЯ механика
Изоляция данных по рынку через один `ThreadLocal` **`MarketContext`** + 4 части:
1. **`MarketContext`** (`context/`): `get()` → по умолчанию **`Market.RF`** (НЕ null!), `set(Market)`, `clear()`.
2. **Штамп при вставке:** `MarketStampingListener` (`@PrePersist`) на рыночных сущностях через `@EntityListeners(...)` — ставит `market = MarketContext.get()` при INSERT, если null. Сервисы ещё и пред-штампуют при create (id==null) — defense-in-depth.
3. **Фильтр на чтение:** Hibernate `@FilterDef(name="marketFilter")` объявлен **ОДИН раз на `Tender`** + `@Filter(name="marketFilter", condition="market = :market")` на каждой рыночной сущности. **НЕ переобъявлять `@FilterDef`** на других сущностях. Включает фильтр `MarketFilterAspect` (`@Before` на каждом вызове Spring Data `Repository`): берёт привязанный (tx/OSIV) EntityManager и включает фильтр из `MarketContext`. Без привязанной сессии — НЕ фильтрует.
4. **HTTP:** `MarketInterceptor` (для `/api/**`) ставит `MarketContext` из заголовка `X-Market` в preHandle, чистит в afterCompletion. Фронт: `marketInterceptor` (HttpInterceptorFn) сам вешает `X-Market` на каждый запрос.

**⚠️ Гард `@Scheduled`/фоновых потоков (нет HTTP, нет OSIV):** у фонового потока НЕТ `MarketContext` (дефолт RF) и НЕТ привязанной сессии. Правило: **вызывающий ставит `MarketContext.set(market)` ДО вызова + `clear()` в finally; работа с БД — в `@Transactional`-методе ОТДЕЛЬНОГО бина (не self-invoke)**, чтобы аспект получил привязанную сессию. Пример — приём почты ставит рынок ящика вокруг `@Transactional MailReceiveService.poll()`.

Рыночные сущности: `Tender`, `TenderLot` (через tender), `ActivityApply`, `ApplyItem`, `Facility`, `Distributor`, `MedEquipment`, `PriceRequest`, `InboundEmail`. Общие (без рынка): `MedRegistry`, `EquipmentType`, `HeaderSynonym`, `UserAccount`.

## 7. Модель данных (PostgreSQL, схема — Flyway V1)

- **Справочники:** `facility` (учреждение/клиника; контакты разнесены: last/first/middle_name, phone, email — 1НФ), `distributor` (поставщик: name UK, inn, email; + `@ElementCollection distributor_brand` — какие бренды возит; + `distributor_equipment_type`), `med_equipment` (каталог: name, manufact, equip_type, габариты, weight, spec), `equipment_type`, `user_account`.
- **Реестр:** `med_registry` (≈14072 записи реестра НЦЭЛС РК: reg_number UK, name TEXT, producer, country, reg_date, expiration_date, unlimited). **Наполняется Java-инициализатором из JSON** (`registry/rk-mi-registry-full.json`), не сидом. GIN-trgm индексы `idx_reg_name_trgm`/`idx_reg_producer_trgm`.
- **Слой требований:** `tender` (tender_number, facility_id, status String, deadline nullable, total_cost, description, **`source` PUBLIC_TENDER/PRIVATE_REQUEST**, market), `tender_lot` (tender_id, lot_number, equip_name, equip_type, quantity, max_габариты, **`manufact`** — бренд/модель для частных заявок, required_spec).
- **Слой предложений:** `activity_apply`, `apply_item`, `price_request` (КП: status CREATED→SENT→RESPONDED→ACCEPTED/REJECTED/CLOSED, sentAt, responseDate, note, market; tender+distributor ManyToOne; items OneToMany **cascade ALL + orphanRemoval**), `price_request_item` (tenderLot, medEquipment nullable, requestedQuantity, responsePrice, responseNote).
- **Почта/импорт:** `inbound_email` (from_address, subject, received_at, type SUPPLIER_RESPONSE/CLIENT_REQUEST/UNMATCHED, matched_price_request_id, attachment_name, **attachment BYTEA**, excerpt, status NEW/PROCESSED, market), `header_synonym` (обучаемый словарь заголовков Excel: header_norm UK, field NAME/MANUFACT/QUANTITY).

**`Tender.lots` — `@OneToMany cascade=ALL, orphanRemoval=true`** → лотами управлять **через коллекцию `t.getLots()`** (removeIf/add), НЕ через `tenderLotRepository.delete` (cascade на flush вернёт удалённое). Этот баг ловили в `PrivateRequestService.update`.

## 8. Реализованные блоки (всё в main)

- **A — Переключатель рынка** РФ/₽ ↔ KZ/₸ (вся §6-механика). При смене рынка остаётся на текущей странице.
- **B — Частные заявки** (`/private-requests`): tender с `source=PRIVATE_REQUEST`, автономер `ЧЗ-<год>-NNNN`. Карточка: строки (модель/бренд/кол-во) + **инлайн реестр-статус** (Зарегистрировано/Не найдено + НДС-бейдж + топ-кандидат РУ) + запрос КП per-поставщик + ввод ответов. Шов приёма `PrivateRequestService.createFromLines(PrivateRequestCreate)`.
- **C — Подбор поставщиков по брендам:** справочник брендов в карточке поставщика; `PrivateRequestSourcingService.buildSourcing(id)` группирует строки заявки по поставщикам, у кого есть бренд строки (`manufact`, case-insensitive); блок «Подобрать поставщиков» + «Запросить КП» по группе (`GET /api/private-requests/{id}/sourcing`).
- **D1 — Импорт заявки из Excel:** `LineExtractor`/`RuleBasedLineExtractor` (POI) разбирает .xlsx/.xls в грид; разметка колонок по **обучаемому словарю** `header_synonym` (пополняется правками оператора при коммите). `POST /import/preview` (multipart) + `POST /import/commit` (переиспуют `createFromLines`).
- **D2 — Почта (round-trip + входящие):** см. §9.
- **Редактирование частной заявки:** `PUT /api/private-requests/{id}` (`PrivateRequestUpdate`) — правка/добавление/удаление строк (через коллекцию orphanRemoval; строки с уже запрошенным КП не удаляются — FK). UI: «✎ Редактировать» в карточке → инлайн-грид (наименование/бренд/кол-во).
- **Импорт из письма + «➕ Новый клиент»:** письмо клиники из «Входящих» → тот же грид D1 из сохранённого вложения; нет клиента — завести нового прямо в диалоге (имя из отправителя). Превью текста письма (свернуть/развернуть), колонка «Получено» (дата + день недели, `Intl` ru-RU).
- **Реестр-сверка** (`/registry-reconciliation`), отчёты, дашборд, глобальный поиск, типы оборудования, учреждения, дистрибьюторы — есть.
- **Лотовый запрос КП + предложенная модель (оба рынка):** единый канал отправки `POST /api/price-requests/send` (`PriceRequestSendService` + `KpEmailComposer` — брендинг по `pr.getMarket()`, токен `[КП-id]`, № РУ, обрезка спеки 1200, ссылка на объявление: KZ goszakup по числовому id из `sourceExtId` до дефиса / RF zakupki по номеру; результат per-поставщик `{emailSent, reason NO_EMAIL|SEND_FAILED}` — запись КП живёт даже без письма). На него переведены ВСЕ флоу: лотовый (чекбоксы лотов + «выбрать все» + кнопка «КП» в строке → панель поставщиков с подсказками `GET /api/tenders/{id}/lot-sourcing` — бренд предложенной модели или производители реестр-кандидатов НЦЭЛС ≥0.35, `BrandMatch`), smart-match, bulk-модалка, частные заявки (дыра «SENT без письма» закрыта). «Предложенная модель» — `tender_lot.proposed_equipment_id` (V4, ON DELETE SET NULL), апрув из smart-match (`POST/DELETE /api/lots/{id}/proposed-equipment`, гард чужого рынка: `em.find` обходит hibernate-фильтр), подставляется в КП-items. `SpecConstraintExtractor` — «не более A×B×C мм/см/м» + вес кг/г из текста спеки («не менее» игнорируется), питает `scoreLot`, когда все структурные поля лота пусты; `specDerived` в ответе матча показывается в smart-match. Письмостроение из `BulkPriceRequestService` выпилено (остался `buildPreview`); `POST /api/bulk-price/send` — тонкий делегат к `sendService`.
  - **Подбор без критериев:** если у лота нет ни типа, ни габаритов/веса (структурных или из спеки) — `scoreLot` возвращает `noCriteria=true` + пустой список (иначе матчинг вернул бы ВЕСЬ каталог с одинаковым score 50); smart-match показывает баннер «нужна техспецификация». Заголовок панели без «№», если у лота пустой номер.
- **Разбор техспеки лота goszakup (кнопка «ТЗ», только импортные KZ-тендеры):** `POST /api/lots/{id}/parse-techspec` → `TechSpecService`: v3 GraphQL `Lots(filter:{trdBuyNumberAnno}){ Files }` (файл `nameRu="Техническая спецификация"`, матч лота по `equipName==nameRu` case-insens; дубли имён → первый + `ambiguous`) → скачивание `filePath` Bearer-токеном (`downloadFile`, без Accept-заголовка, 404→null) → PDFBox (`PdfTextExtractor`, мусор→null) → `TechSpecExtractor.russianSection` (PDF двуязычный: казахская секция, потом русская «Приложение 2…»; маркеры «приложение»/«техническая спецификация») → `SpecConstraintExtractor` → `TechSpecWriter` (@Transactional отдельный бин, сеть вне tx §6): `requiredSpec` перезаписывается всегда, `max*`-поля — только найденными значениями (не затирает ручное). Ошибки: ручной тендер/нет токена → 400, файла нет → 404, PDF не читается → 422 (`UnprocessableException`), сеть → 502 (`UpstreamException`) — лот не трогается. Фикстур реальной техспеки: `src/test/resources/goszakup/techspec-pulse.pdf`. Габариты триплетом в казахских ТЗ редки → часто наполняется только `requiredSpec` (уже даёт содержательное ТЗ вместо куцего `description_ru`); подбор остаётся `noCriteria`, если габаритов в ТЗ нет.
  - **Умный реестр-матч по лоту:** `candidatesForLot` — бренд задан → бренд-путь `findCandidates`; иначе `LotQueryTokenizer` (стоп-слова канцелярита/служебных + «размер*», ≤5 токенов имени с крутыми весами 1.0/0.5/0.35/0.25/0.2 — головное слово доминирует, иначе хвост типа «портативный» топит изделие + ≤5 токенов из характеристик разобранного ТЗ ×0.5) → `searchByTokens` (кандидаты через `IN(join tok <% name) OFFSET 0` — фенс от расплющивания планировщиком, Bitmap Index Scan по GIN ~64мс; наивный EXISTS давал 650мс seq scan; ранг = взвешенное покрытие ВСЕХ токенов, отсечка score ≥ 0.2, токены/веса строками через `|`). Порог `word_similarity_threshold` глобально НЕ трогать. ⚠ Токенный score живёт в другой шкале, чем старый бренд-скоринг: `LotSourcingService.REGISTRY_SCORE_MIN=0.35` теперь срабатывает часто (подсказки поставщиков для канцелярских лотов реально заработали) — порог валидировать по жалобам. FTS дисквалифицирован замером (рус. стеммер не склеивает оцифровщик/оцифровки, рентген/рентгеновских — триграммы склеивают). Golden-набор в `RegistryLotMatchTest` на живом реестре. Живой результат: «Устройство оцифровки рентген снимков» → REGIUS SIGMA 2/Sirona/CRUXELL (было 0 кандидатов).
    - **Честный score (панель «Реестр»):** `matchForLotUi` → `LotRegistryMatchResponse {candidates, distinctive, techSpecParsed}`. `distinctive = ≥2 значимых токена ИЛИ задан бренд` — при 1 токене (одно-словный лот, «Центрифуга»/«Маска») `word_similarity` одного слова = 1.0 ВСЕМ вхождениям → врущие «100%». UI: `distinctive` → процент; `!distinctive` → метка «✓ по названию» (колонка «Соответствие»), + баннер «разберите ТЗ» когда `!techSpecParsed` на импортном. `candidatesForLot(List)` для `LotSourcingService` — контракт неизменен (делегирует `computeLotMatch`). Реестр НЦЭЛС без габаритов/веса — ранжировать по ним нельзя (легенда в шапке панели).
  - **Габариты-v2** (`SpecConstraintExtractor`): приоритет триплет `A×B×C` → поосевые («длина|глубина/ширина/высота … не более X мм|см|м», каждая ось независимо) → двумерный «(размер|габарит) 55*80 мм» → length+width. «Не менее» игнор везде; `PART_CONTEXT`-гард отсекает размеры детали («ширина ленты», «размер поля/экрана»).
  - **«Взять из реестра в работу» (мост реестр→каталог→модель лота→КП):** `POST /api/lots/{id}/adopt-registry {regNumber}` → `RegistryMatchService.adoptForLot` (@Transactional, гард рынка лота): РУ по `findByRegNumber` → дедуп `findFirstByRegistrationRegNumber` (рыночный фильтр аспектом) create/reuse `MedEquipment` (name/manufact из реестра, обрезка 255, producer null → «не указан», `REGISTERED` + привязка РУ, без габаритов/типа) → `lot.setProposedEquipment`. Кнопка «Взять в работу» в панели «Реестр» → бейдж «Предложено … РУ ✓» + сразу КП-панель. Так KZ-каталог наполняется по ходу работы (со временем оживёт и smart-match).
  - **Описание кандидата (карточка НЦЭЛС):** клик по строке кандидата в панели «Реестр» → разворот «ТЗ лота ↔ Из реестра НЦЭЛС» (назначение/область применения/класс риска/краткие тех. характеристики/вид МИ). `GET /api/registry/detail?regNumber=…` → `RegistryDetailService`: live-fetch с oldregister.ndda.kz при первом просмотре (`integration/ndda`: POST list-фильтр по точному № РУ → внутренний id → GET `MtMainGetById`, без auth, конфиг `ndda.api.base-url`), кеш в `med_registry` (V5: `tech_chars`/`purpose`/`use_area`/`risk_class`/`mi_kind`/`mi_kind_def`/`ndda_id`/`detail_fetched_at`; маркер ставится и при «на портале не найдено» — повторно не долбим). Сеть вне tx (`RegistryDetailWriter` — отдельный @Transactional-бин). Ошибка сети → 502, кеш не пишется, разворот показывает ошибку + retry кликом. Adopt переносит `tech_chars` в `spec` НОВОЙ позиции каталога (существующую не трогает, внешку не зовёт). Фронт кеширует ответ на объекте кандидата (`c._detail`).
  - **Поиск по комплектности аппаратов (аксессуарные лоты):** кнопка «🔧 Комплектность аппаратов» в панели «Реестр». Для лота-принадлежности (электрод/пластина к аппарату), которого нет отдельной записью реестра, `POST /api/lots/{id}/complect-search[?term=]` → `ComplectService`: `ComplectTermExtractor` вынимает бренд аппарата из названия+ТЗ («Элэскулап»; кавычки → «(для/к) аппарата X» → длинный не-generic токен) → `MedRegistryRepository.findApparatusByTerm` (записи «(МТ)», `<%`/`word_similarity`, топ-3) → `NddaClient.resolveId`+`fetchComplectList` (live `MtComplectList?registerId=`, ВНЕ tx) → кеш в `registry_component` (V6, `ComplectWriter` — отдельный @Transactional-бин, бэкфилит `ndda_id`) → `ComplectComponentMatcher` ранжирует компоненты по токенам лота (размеры «55»/«80» — дискриминаторы; токены берутся из ОПИСАТЕЛЬНОЙ части — `LotDescriptiveText`: имя+бренд + значения goszakup-меток наименование/описание/доп.описание лота + требуемые характеристики, а закупочный канцелярит номера/места/сроки поставки выброшен, иначе раздувал знаменатель и топил % — на лоте 3088 силиконовые 55×80 стали 42% вместо 11%; ТЗ без меток → берётся целиком, fallback без регресса). UI: редактируемое поле термина (штурвал при промахе эвристики) + аппараты со страной + компоненты (лучший сверху — бейдж «★ рекомендуем» + зелёная подсветка на первом; нерелевантные 0% свёрнуты под «ещё N нерелевантных», `_relevant`/`_zero`/`_showZero` предподсчитаны в `runComplect`). `POST /api/lots/{id}/adopt-component {regNumber, partNumber}` → `ComplectService.adoptComponent` (ОТДЕЛЬНО от `adoptForLot`, @Transactional, гард рынка лота, дедуп РУ+имя): позиция каталога с именем/производителем/spec КОМПОНЕНТА + `registration` = РУ АППАРАТА (его допуском компонент покрыт) → предложенная модель лота. Живой кейс: лот «Электрод» тендера 17279420-1 (ТЗ «пластинки для аппарата Элэскулап 55×80») → аппарат ЭЛЭСКУЛАП (РФ, Мед ТеКо, РК МИ (МТ)-0№027673) → компонент «Электроды силиконовые 55×80» (было: ЭКГ-электроды из Китая/Индии по названию).
  - **Чистка карточки тендера (оба рынка, «пусто — не рисуем»):** инфо-поля (Способ закупки/Контакт/Телефон/Почта/Адрес/даты) — `*ngIf` по значению (у 394/394 импортных пусты); колонки лотов Тип/Габариты/Вес — только если хоть у одного лота заполнены (`hasAnyType/Dims/Weight`; фикс мёртвого биндинга `l.equipType` → `l.equipmentType?.name`); «Подобрать» (каталог-матч `/equipment/match`) — только `lotHasCriteria && !isKz()` (на KZ каталог наполняется из реестра → подбор там через «Подбор»; на РФ реестра нет, каталог — единственный матчер); bulk «КП по всему тендеру» — только `!isImportedTender()`; кнопка реестр-подбора «Подбор» (бывш. «Реестр», открывает панель кандидатов НЦЭЛС + комплектность) — только `isKz()`. Ред./Удалить на строке лота — в overflow-меню «⋯» (`openMenuLotId` + `@HostListener('document:click')` для закрытия).
  - **Подбор поставщиков по виду МИ (КП-панель, «грамотная отправка» ч.1):** панель «КП» по лоту (`GET /api/tenders/{id}/lot-sourcing`) ранжирует дистрибьюторов рынка мягким скором `score = 1.0·brandHit + 0.7·typeHit (+0.3 оба)`, `relevant = brandHit∨typeHit` — релевантные сверху (★+зелёная подсветка лучшего, преотмечены сильнейшие=brandHit), нерелевантные свёрнуты под «ещё N» (стиль комплектности); чипы причин `✓<тип>`(TYPE)/`возит <бренд>`(BRAND) из `reasons` (суперсет; `matchedBrands` оставлен для совместимости). **Вид МИ лота:** `LotTypeClassifier.classify` (обучаемый словарь `equipment_type_synonym`, V7 — подстрочный матч терминов в имени+ТЗ, совпадение в имени ×1.5, `confidence`=доля веса; сид ~68 синонимов) — авто-подсказка в селекторе шапки, оператор меняет → `POST /api/lots/{id}/equipment-type {typeId|null}` (гард рынка через тендер как `setProposedEquipment`; персистит `lot.equipmentType` — **чинит дефект несохранения типа**; best-effort `learn` = **UPSERT** головного токена имени → тип, последняя коррекция побеждает, не «первое навсегда»). **Tier 2 (точечный поиск, аксессуары/мелочь):** редактируемое поле «Поиск поставщика», `sourcingTerm` выводится по приоритету бренд предложенной модели → `ComplectTermExtractor` (аппарат из «электроды для Элэскулап») → реестр-производитель → головной токен; `?term=` перебивает → добавляется `SEARCH_TERM` brand-источником. Только одно-лотовый режим (селектор+поле+detectedType/typeAlternatives); мульти-лот = объединение типов/брендов. Деградация: вид МИ не определён и брендов нет → фолбэк + баннер. `equipment_type` расширен V7 до 18 типов (+Хирургия/Стерилизация/Анестезия/Физио/Стоматология/Реабилитация/Мебель/Неонат). Реальные KZ-дистрибьюторы с брендами+видами МИ — V8 (10 ТОО) → **V9 качественная верификация: 20 ТОО** (10 уточнены по сайтам + email, 10 новых под тонкие ниши КТ/МРТ/лаб/мониторы/мебель/эндоскопия; источники URL в комментариях миграции; per-claim доказательства в `.superpowers/research/`; email на generic-доменах и 2 ненайденных — verify перед рассылкой; фейки V2 снесены). Правило веб-ресёрча данных: только реальное с сайта/авторитетного источника, не выдумывать бренды/email. Разметка «отметок» дистрибьютора (бренды-чипы + чекбоксы типов) — уже была в карточке `/distributors`.

## 9. Почта — детально (блок D2)

Модель: **info@ = входящие запросы клиентов (читаем и парсим), zakup@ = отправка дистрибьюторам (+ их ответы)**.
- **Отправка КП:** единый живой путь — `PriceRequestSendService.send` → `KpEmailComposer.compose` → `EmailService.sendEmail(...)` (см. §8, «Лотовый запрос КП»). Тема несёт токен `[КП-<id>]` (хелпер `KpToken.subjectToken/parse`, regex `\[КП-(\d+)\]`). Отдельного `/api/email/send` НЕТ (устаревшее упоминание из старого CLAUDE.md).
- **Приём (IMAP):** `MailReceiveService.poll()` (сырой `jakarta.mail`, поиск непрочитанных `FlagTerm`). По умолчанию ВЫКЛ (`mail.imap.enabled=false`). Классификация: токен в теме → `SUPPLIER_RESPONSE` (матч к `PriceRequest` по id; статус CREATED/SENT → RESPONDED, ACCEPTED/REJECTED/CLOSED не трогаем); есть .xlsx/.xls вложение → `CLIENT_REQUEST` (в «Входящие», вложение в BYTEA); иначе → `UNMATCHED`. Помечает SEEN (идемпотентно). **Гард `mail.imap.since-minutes` (дефолт 60)** — обрабатывает только письма за последние N минут (не трогает старый бэклог реального ящика). Рынок ящика — `mail.imap.market` (KZ).
- **Парсинг письма (важно для реальных писем):** **рекурсивный обход multipart** (вложение часто во вложенном `multipart/alternative`); **декод MIME-имени файла** (`MimeUtility.decodeText` — кириллица в `=?UTF-8?B?...?=`); распознавание Excel и по Content-Type. `received_at` берётся из письма (`getReceivedDate`/`getSentDate`), не из времени опроса.
- **`InboundController`** (`/api/inbound`): GET список, POST `/poll`, POST `/{id}/preview` (грид D1 по сохранённому вложению), POST `/{id}/processed`.
- **UI «Входящие письма»** (`/inbound`, группа «Заявки»): список с бейджами типа + «Проверить почту»; письмо клиники → «Импортировать» (грид D1 + «➕ Новый клиент»).
- **Тест:** `MailReceiveServiceIntegrationTest` на GreenMail (встроенный IMAP, `ServerSetupTest.IMAP`:3143) — в т.ч. кейс «вложенный multipart + MIME-кириллическое имя». Без реальных адресатов.

## 10. Персистентность — Flyway (важно!)

**Данные ЖИВУТ между перезапусками.** Раньше `schema.sql`+`data.sql` с `spring.sql.init.mode=always` дропали все таблицы при каждом старте → рантайм-данные (реальные заявки, письма, клиенты) слетали; и прогон `./gradlew test` тоже затирал базу. Теперь:
- Схема и демо-сид — **миграции Flyway** в `src/main/resources/db/migration/`: `V1__init_schema.sql` (схема **без DROP**), `V2__seed_demo.sql` (демо-данные). Накатываются один раз; дальше «up to date, no migration necessary».
- `application.yaml`: `spring.sql.init.mode: never`, `ddl-auto: none`, `spring.flyway.enabled: true`, `baseline-on-migrate: true`. **`schema.sql`/`data.sql` удалены** — единственный источник схемы это миграции.
- **Менять схему — ТОЛЬКО новыми миграциями V3, V4…** (не править V1/V2).
- Тесты идут на nirdb через Flyway + `@Transactional`-откат (изолированы, базу не затирают).
- **Реестр** (med_registry) Flyway не сеет — наполняет Java-инициализатор из JSON; переживает рестарт (`CREATE TABLE IF NOT EXISTS` + проверка пустоты).
- **Демо-данные пока остаются** (на время разработки). При деплое на сервер: убрать/заменить `V2` на реальные (оставить справочные — реестр, словарь синонимов), применить `ALTER DATABASE <name> SET random_page_cost = 1.1` один раз.
- **Пересоздать БД с нуля:** `dropdb`+`createdb` (UTF-8, см. §5) → старт → Flyway накатит V1+V2, реестр переимпортируется. Иначе Flyway упрётся в существующие таблицы.

## 11. Реестр-матчинг и производительность

- Примитив `MedRegistryRepository.findCandidates(name, manufact, limit)` (нативный, `word_similarity` `<%` — индексо-дружелюбный; раньше был оператор `%` similarity → seq scan по 14k на длинных названиях смет ~600мс). Скоринг 0.6·производитель + 0.4·название. `RegistryMatchService.findCandidates` обрезает названия до 80 символов.
- **`ALTER DATABASE nirdb SET random_page_cost = 1.1`** — обязательно, иначе планировщик на маленькой таблице (14k) игнорирует GIN-индекс и уходит в seq scan. Применено к nirdb; в V1 закомментировано (ALTER DATABASE нельзя в транзакции инициализации); на новой БД применить вручную.
- **Список частных заявок НЕ считает реестр** (было 10.6с → 6мс): показывает только число позиций; полный реестр-статус строк — только в карточке (`findById`). Карточка сметы (16 строк): ~9с → ~0.2с.

## 12. Frontend (Angular 21)

Standalone-компоненты, инлайн-шаблоны + `styles: []`. `ApiService` (база `/api`): generic `getAll/getById/create/update/delete` + доменные методы (напр. `getPrivateRequest`, `getPrivateRequestSourcing`). `marketInterceptor` вешает `X-Market`. `NotificationService.success(string)/error(string)`. Компоненты руками зовут `cdr.detectChanges()` после async. Роуты — дети `LayoutComponent` под `authGuard`; сайдбар инлайн в `layout.component.ts` (группы Тендеры/Каталог/Заявки/Система). Карточки заявок — инлайн-компоненты, открываются по `?openId=`/`@Input requestId`.

Грид импорта D1 (per-column `<select>` NAME/MANUFACT/QUANTITY/IGNORE) переиспользуется в `private-requests` (импорт файла) и `inbound` (импорт письма).

Карточка тендера (`tenders.component.ts`, большой — 5 инлайн-панелей: registry/kp/smart-match/bulk/spec): правило «пусто — не рисуем» (инфо-поля и колонки лотов Тип/Габариты/Вес по `*ngIf` наличия данных); каталожные кнопки по критериям (`isKz()`/`isImportedTender()`/`lotHasCriteria`). Спецификация лота раскрывается **полноширинной строкой-аккордеоном** под строкой лота (`<tr>` c colspan, `pre-wrap`+scroll), не в узкой колонке. `anyComponentStyle` budget в `angular.json` поднят 12→16 кБ.

## 13. Тестирование

- `./gradlew test` (sandbox off). **Ожидаемо 2 падения — `ApplyAutoFillServiceTest` (2)**, пред-существующие (расхождение assertion по наценке ×1.25, в бэклоге). Гейт «зелёного»: «только эти 2». Остальные (рынки, частные заявки, сорсинг, импорт, почта-GreenMail, реестр, update) — зелёные.
- Бэк-тесты — `@SpringBootTest @Transactional` на реальном Postgres (nirdb). Почта — GreenMail.
- Перед прогоном глушить лишний `bootRun`: `lsof -ti :8080 | xargs kill -9`.
- Фронт — тестов нет, гейт = `cd frontend && npm run build`.

## 14. Ключевые gotchas / уроки

- **`./gradlew test` раньше затирал nirdb** (schema.sql DROP) — теперь нет (Flyway). Если данные «пропали» — проверь, что используется Flyway-путь.
- **`@FilterDef` — только на `Tender`**; дубль на новой сущности = коллизия при старте.
- **`@Scheduled` без `MarketContext`** → дефолт RF + нет фильтра (см. §6).
- **JPA orphanRemoval**: лотами управлять через коллекцию, не `repository.delete`.
- **Mail.ru**: только пароль приложения; реальные письма — вложенный multipart + MIME-имена; обязательны рекурсивный обход + декод.
- **pg_trgm + кириллица**: nirdb обязан быть UTF-8 локалью; `random_page_cost=1.1` чтобы юзался индекс.
- Ручной клик-тест ловит то, что юнит-тесты (минуя `@Valid`) пропускают — проверять фичи в браузере.
- **Bash cwd персистит между вызовами**: `cd frontend && npm run build` оставляет cwd во `frontend` → следующий `./gradlew`/`git` падает («No such file»). git/gradlew — из корня (компаунд `cd /Users/vlad/IdeaProjects/AIS && …`).
- **Edit больших файлов** (`RegistryMatchService`, `tenders.component.ts`): фоновый автоформат иногда дублирует вставленный метод или съедает соседнюю сигнатуру (ловили дубль `adoptForLot` + съеденный `applyAction`). После крупной правки — `grep -c "имяМетода"` на дубли + `./gradlew compileJava`; Edit может упасть «File has not been read yet» после фоновой модификации — перечитать и повторить.
- **pg_trgm `word_similarity` / `<%`**: чувствителен к длине запроса (порог 0.6 отсекает длинные фразы целиком) → лот-матч по значимым токенам, не по всей строке (§8 `LotQueryTokenizer`). Индекс живёт только если фильтр `EXISTS(IN(join tok <% name)) OFFSET 0`, а не голый `EXISTS` (иначе seq scan 650мс, см. §8 `searchByTokens`).

## 15. API (основное)

`/api/auth/login`; `/api/facilities`, `/api/distributors`, `/api/equipment` (+ `/match/{lotId}`), `/api/equipment-types`, `/api/users`; `/api/tenders` (+ `/search`, `/{id}/lots`, `/{id}/applies`), `/api/lots`; `/api/applies`, `/api/apply-items`; `/api/private-requests` (GET/POST/**PUT {id}**, `/{id}/sourcing`, `/import/preview` multipart, `/import/commit`); `/api/price-requests` (+ `/{id}/responses`, accept/close, **`/send`** — единый канал КП), `/api/tenders/{id}/lot-sourcing` (+ опц. `?term=` — точечный термин Tier 2), `/api/lots/{id}/proposed-equipment` (POST/DELETE), `/api/lots/{id}/equipment-type` (POST — персист вида МИ лота, гард рынка), `/api/lots/{id}/parse-techspec` (POST — разбор техспеки goszakup), `/api/lots/{id}/adopt-registry` (POST — реестр→каталог→модель лота), `/api/lots/{id}/complect-search` (POST — поиск по комплектности аппаратов), `/api/lots/{id}/adopt-component` (POST — компонент комплектности → модель лота), `/api/bulk-price/*` (preview + `/send`-делегат); `/api/inbound` (GET, `/poll`, `/{id}/preview`, `/{id}/processed`); `/api/registry-*` (сверка) + `/api/registry/detail?regNumber=…` (GET — описание РУ из карточки НЦЭЛС, live+кеш), `/api/reports/*`. Записи — `@PreAuthorize ADMIN`.

## 16. Roadmap / бэклог

Поток «Частники» (West-Med) — основной — закрыт (A→D2 + edit + perf + Flyway). Дальше:
- **Hardening живого IMAP** перед регулярным включением: вынести IMAP-I/O из транзакции (вложенный multipart — уже сделано).
- Авто-резолв клиента по адресу отправителя (match `facility.email`).
- Структурный разбор ответа поставщика (цена → `PriceRequestItem.responsePrice`).
- LLM-парсер вложений (опт-ин по Anthropic API-ключу — отдельная оплата по токенам; дёшево на Haiku) как фолбэк к правилам; CSV/PDF.
- Нечёткий матч брендов (pg_trgm/синонимы/транслит: «Mindray»↔«Майндрей»↔«Shenzhen Mindray»). Сейчас `BrandMatch` — case-insensitive substring.
- ~~Пооосевые ограничения из спеки~~ ✔ сделано (габариты-v2: поосевые + двумерные; остались диапазоны «от X до Y»).
- Авто-разбор ТЗ при импорте / фоновая очередь (сейчас — только по кнопке «ТЗ»); LLM-фолбэк для сложных/табличных ТЗ (опт-ин); хранить goszakup `lot_number` при импорте → точный матч лота вместо имени (уберёт `ambiguous`).
- ~~Реестр-матч по разобранному ТЗ~~ ✔ сделано (токенный `searchByTokens` + обогащение из ТЗ, см. §8). Остался транслит брендов («Майндрей»↔Mindray).
- ~~Наполнение KZ-каталога из реестр-кандидата~~ ✔ сделано («Взять в работу», §8). Дальше: автосоздание позиций каталога при импорте (сейчас — по клику оператора).
- Хардкод-селект типов в **форме создания/редактирования лота** (`tenders.component` форма: УЗИ/Рентген/ИВЛ/Монитор строкой, не сохраняется — `TenderLotRequest` ждёт `equipTypeId`) → справочник `equipment-types`. Дефект. (Отдельно от него: сохранение вида МИ через КП-панель `POST /api/lots/{id}/equipment-type` уже работает — §8 подбор по виду МИ.)
- **Отправка КП — ч.2 (грамотная отправка):** тело письма под вид МИ, UI-статусы SENT/RESPONDED в панели, повторная отправка. **ч.3 (разбор ответов):** структурный парс ответа поставщика (цена/срок/модель → `PriceRequestItem.responsePrice`), автозаполнение КП, сравнение предложений. (ч.1 «подбор по виду МИ» — закрыта, §8.)
- Подбор по виду МИ — тюнинг после наблюдений: пара синонимов V7 кросс-категорийно неоднозначны (электрофорез→Физио, инкубатор/фототерап→Неонат, центрифуга→Лаб.анализатор); подстрочный матч чувствителен к порядку слов; head-token fallback в `sourcingTerm` (напр. «расходный») может дать ложный preselect по бренду на brand-less лотах — рассмотреть отсечку generic-токенов. При деплойной ревизии сида (§10): V8 не чистит `apply_item.distributor_id` фейков (не-каскадная FK) — no-op на свежей БД, но добавить `DELETE apply_item` для полноты.
- Удалить тонкий делегат `/api/bulk-price/send` (фронт уже полностью на `/api/price-requests/send`; FE-метод `bulkPriceSend` уже удалён).
- `TenderLotMapper.proposedEquipmentToResponse` читает LAZY `registration` — потенциальный N+1 в `GET /tenders/{id}/lots` (работает под OSIV; при росте — JOIN FETCH).
- `SpecConstraintExtractor`: вес без явного «не более» трактуется как верхняя граница (by design) — «масса образца до 5 г» даст абсурдный потолок; при жалобах — требовать квалификатор.
- Парсер казахстанских смет: пропуск служебных строк (нумерация, ИТОГО), сид типовых заголовков.
- RF-реестр (Росздравнадзор) для рынка РФ.
- КП-генератор клиенту (НДС/происхождение).
- Починить 2 `ApplyAutoFillServiceTest` (assertion vs наценка ×1.25).
- Деплой на сервер: убрать демо-сид (V2), реальные данные, перевыпустить пароли приложений.

## Контекст: дипломная записка
Параллельно писалась пояснительная записка (отдельный чат). Форматирование: Times New Roman 14pt, интервал 1.5, отступ 1.25см; генерация docx через Node.js `docx`. **Диплом сдан — это уже не приоритет.**
