# Дизайн: авто-импорт тендеров СК-Фармации (fms.ecc.kz) — Блок B

**Дата:** 2026-07-11
**Ветка:** `feat/skpharmacy-import`
**Статус:** одобрено, реализация. Зеркалит goszakup-импорт, но HTML-скрейп (у fms.ecc.kz нет API).

## Контекст

West-Med участвует в закупках медтехники и на **СК-Фармации** (единый дистрибьютор, портал `fms.ecc.kz`, оператор ЦЭФ). У портала **нет API** (в отличие от goszakup `ows.*`), но всё **server-rendered HTML** в открытом доступе → скрейп через Jsoup (feasibility подтверждён: searchanno/announce/lots отдаются готовым HTML, headless-браузер не нужен). Тянем **только медизделия/медтехнику, без лекарств** (решение оператора). Импортные тендеры получают `platform=SK_PHARMACY` (поле уже есть) → кнопка «Открыть» ведёт на fms.ecc.kz, фильтр «Площадка» их выделяет.

## Реальная HTML-структура (снята с портала, → фикстуры тестов)

**Список `GET /ru/searchanno` (+ `?page=N`):** `<table class="table table-bordered">`, строки данных по **10 `<td>`**:
`[0]` номер («521464-1») · `[1]` организатор · `[2]` наименование (рус + каз одной строкой) · `[3]` способ («Тендер») · `[4]` предмет («Товар») · `[5]` приём с («2026-07-13 09:00:00») · `[6]` приём по · `[7]` кол-во лотов · `[8]` сумма («15 085 999 992.00») · `[9]` статус («Опубликовано»). id объявления — из `a[href*="announce/index/"]`. Пагинация — `?page=N`.

**Лоты `GET /ru/announce/index/{id}?tab=lots`:** таблица, строки по **9 `<td>`**:
`[0]` № лота · `[2]` код лота («1040409-Т1») · `[3]` **наименование** («компьютерный томограф») · `[4]` цена за ед. · `[5]` кол-во · `[6]` сумма.

→ Поля тендера берём из строки searchanno; лоты — из вкладки lots. **Страницу general НЕ тянем.**

## Границы (scope MVP)

**В scope:** скрейп активных («Опубликовано»/приём заявок) объявлений с медтех-лотами, кап N страниц, upsert как тендеры `platform=SK_PHARMACY` с лотами; кнопка + async-прогресс как у goszakup; фильтр медизделий (не лекарства).
**НЕ в scope:** парс тех.спеки лота СК-Ф (потом, как «ТЗ» goszakup); регион-фильтр; general-страница объявления; статусы-маппинг сверх ACTIVE/по дедлайну.

## Архитектура (зеркалит `integration/goszakup`, новый пакет `integration/skpharmacy`)

- **build.gradle:** + `implementation 'org.jsoup:jsoup:1.18.1'` (HTML-парсер).
- **`SkPharmacyHttpClient`** — `java.net.http.HttpClient` (как goszakup) + **браузерный User-Agent** (портал режет ботов без UA), таймауты; `searchPage(int page) → String html`, `lotsPage(String announceId) → String html`. Ошибка/не-200 → `UpstreamException`/лог, объявление откладывается (не валит прогон).
- **`SkPharmacyHtmlParser`** (Jsoup, чистый, юнит-тестируемый):
  - `parseSearch(String html) → List<SkAnnounce>` (`SkAnnounce {announceId, numberAnno, organizer, nameRu, purchaseType, subjectType, acceptStart, acceptEnd, lotsCount, totalSum, status}`) — `table.table-bordered tr`, ≥10 td; id из ссылки.
  - `parseLots(String html) → List<SkLot>` (`SkLot {code, name, unitPrice, quantity}`) — строки лотов, ≥9 td, name=td[3], qty=td[5], price=td[4].
- **`SkPharmacyRelevanceFilter`** (чистый, 2 ступени как goszakup):
  - Ступень 1 (по имени объявления, дёшево): отсекаем явные лекарства (name содержит `лекарствен|препарат|фармацевт|медикамент` И не содержит `техник|изделие|оборудован|аппарат`); иначе — кандидат (тянем лоты).
  - Ступень 2 (по именам ЛОТОВ, после fetch): лот = медизделие/техника (POSITIVE: `аппарат|томограф|установк|монитор|изделие|инструмент|катетер|перчатк|шприц|стол|светильник|дефибрилл|насос|стерилиз|эндоскоп|…`) И **не** лекарство (NEGATIVE: `таблетк|раствор|ампул|капсул|мазь|сироп|инъекц|порошок|суспензи|мг/мл|флакон…`); тендер релевантен при **≥1 device-лоте**. Пустые лоты (сеть/404) → фолбэк по имени.
- **`SkPharmacyTenderWriter`** (`@Transactional`, отдельный бин — сеть вне tx, §6): upsert `Tender` по `sourceExtId=numberAnno` (findBySourceExtId), `market=KZ`, `platform=SK_PHARMACY`, `source=PUBLIC_TENDER`, поля из `SkAnnounce` (customerName=organizer, description=nameRu, totalCost=totalSum, deadline=acceptEnd.date, publishDate=acceptStart.date, purchaseType, currency=KZT); лоты через коллекцию (`removeIf`+add, §7) из `SkLot` (equipName=name, quantity, maxCost=unitPrice). Статус: приём → ACTIVE, дедлайн прошёл → COMPLETED (как writer goszakup).
- **`SkPharmacyImportService`** — оркестрация (как `GoszakupImportService`): по страницам searchanno (кап `MAX_PAGES` ~30, окно/активные), фильтр ступень-1 → тянем лоты → фильтр ступень-2 → writer; наполняет переданный `ImportSummary` (pagesRead/fetched/matched/created/updated/errors). Fail-soft на объявление.
- **`SkPharmacyImportScheduler`** (как `GoszakupImportScheduler`): фон single-thread executor, `MarketContext.set(KZ)` в фоне, `POST` стартует и сразу отдаёт статус; авто-поллинг только при `SKPHARMACY_IMPORT_ENABLED=true` (дефолт выкл). Состояние `{running, lastFinishedAt, lastSummary}`.
- **Эндпоинт:** `POST /api/tenders/import-sk` (старт, ADMIN) + `GET /api/tenders/import-sk/status`. Конфиг `skpharmacy.base-url` (дефолт `https://fms.ecc.kz`), `skpharmacy.import.max-pages`, `enabled`.
- **Frontend:** отдельная кнопка **«Обновить СК-Фармация»** на `/tenders` (KZ) рядом с «Обновить тендеры», свой прогресс-бар/поллинг статуса (переиспуем паттерн goszakup import UI). Токен не нужен (портал открытый).

## Обработка ошибок

- Не-200/сеть/timeout по странице или лотам → лог + skip (в `errors`), объявление не теряется (следующий прогон).
- Изменилась вёрстка → парсер вернёт 0 строк / пустые поля → 0 создано, лог; фикстуры в тестах ловят регресс.
- Портал режет по частоте → троттлинг между запросами + User-Agent; при блоке — 502 + понятное сообщение.

## Тестирование (TDD, на РЕАЛЬНОМ HTML)

- `SkPharmacyHtmlParserTest` — фикстуры `src/test/resources/skpharmacy/search.html` (реальная searchanno, обрезанная) + `lots.html` (реальная вкладка лотов 521464): `parseSearch` → 10 объявлений, поля первого («521464-1», «Закуп медицинской техники», сумма, даты, 12 лотов); `parseLots` → лоты с именами «компьютерный томограф»/«МРТ» и кол-вом/ценой.
- `SkPharmacyRelevanceFilterTest` — golden: «компьютерный томограф»/«аппарат ИВЛ» → device IN; «Парацетамол таблетки 500 мг»/«Натрия хлорид раствор» → medicine OUT; тендер с ≥1 device-лотом → релевантен.
- `SkPharmacyImportServiceTest` — мок `SkPharmacyHttpClient` (отдаёт фикстуры) → сервис парсит+фильтрует+пишет: создаётся тендер `platform=SK_PHARMACY` с device-лотами, лекарственный — пропущен; `ImportSummary` заполнен.
- Живой прогон по кнопке «Обновить СК-Фармация» (реальный портал) — проверка в браузере: появились СК-Ф тендеры, чип/кнопка/фильтр «Площадка» работают.

## Затрагиваемые файлы

- Create: `integration/skpharmacy/{SkPharmacyHttpClient, SkPharmacyClient(iface), SkPharmacyHtmlParser, SkPharmacyRelevanceFilter, SkPharmacyTenderWriter, SkPharmacyImportService, SkPharmacyImportScheduler}.java` + dto `{SkAnnounce, SkLot}` + тесты + фикстуры.
- Modify: `build.gradle` (jsoup), `controller/TenderController.java` (import-sk + status), `application.yaml` (skpharmacy.*), `frontend/.../tenders.component.ts` (кнопка + прогресс + api).
- Reuse: `Tender`/`TenderLot`, `TenderPlatform.SK_PHARMACY`, `ImportSummary`, `MarketContext`, паттерн async-прогресса goszakup.

## YAGNI / вне scope

- Без headless-браузера (server-rendered). Без общей страницы объявления (поля из searchanno). Без парса тех.спеки/региона на старте. Без токена (портал открытый).

## Открытые вопросы / риски

- **Хрупкость скрейпа:** вёрстка ЦЭФ может меняться → тесты на фикстурах + fail-soft; при поломке чиним селекторы. Индексы колонок (search 10-col, lots td[3]=name) закреплены в тестах.
- Троттлинг/бан: умеренная задержка между запросами; если портал начнёт блокировать — вынести UA/задержку в конфиг.
- Точный маппинг «приём с/по» → publishDate/deadline (берём date-часть из «2026-07-13 09:00:00»).
