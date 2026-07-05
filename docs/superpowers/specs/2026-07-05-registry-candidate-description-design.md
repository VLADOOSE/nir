# Описание кандидата в панели «Реестр» (карточка НЦЭЛС по клику)

**Дата:** 2026-07-05. **Статус:** одобрено пользователем (вариант «live из НЦЭЛС + кеш в БД», включая бонус-обогащение каталога при adopt).

## Проблема

В панели «Реестр» у лота (карточка тендера, рынок KZ) кандидат показывается строкой: похожесть, № РУ, наименование, производитель, страна, срок. По этим полям оператор не может понять, подходит ли изделие под ТЗ тендера: в наших данных (`med_registry`, slim-дамп `rk-mi-registry-full.json`) описания нет вовсе — только name/producer/country/даты.

## Находка (проверено живьём 2026-07-05)

Портал НЦЭЛС (`oldregister.ndda.kz`) без авторизации отдаёт полную карточку РУ:

1. **Резолв внутреннего id по № РУ:** `POST {base}/RegisterService/list`, тело `{"regTypeId":2,"regPeriod":1,"regNumber":"РК МИ (ИМН)-0№031074"}` → массив из одного элемента с полем `id` (проверено: ровно 1 запись). Пустой массив — РУ не найден (паттерн «200 и []» как у goszakup subject).
2. **Карточка:** `GET {base}/RegisterService/MtMainGetById?Id={id}` → JSON с полями (среди прочих):
   - `purpose` — назначение изделия (текст, бывает несколько абзацев);
   - `useArea` — область применения;
   - `degreeRiskName` — класс риска («Класс 2 б – с повышенной степенью риска»);
   - `shortTechnicalCharacteristicsRu` / `...Kz` — краткие технические характеристики;
   - `termName_rus` + `termDefinition` — вид МИ по номенклатуре и его определение;
   - `comments` — прочее (хранение и т.п.; в UI не показываем — не про подбор).

   В list-ответе эти поля всегда `null` — наполнены только в карточке (проверено по всем 14126 записям).

`base = https://oldregister.ndda.kz/register-backend`.

## Решение

Live-запрос карточки при первом развороте кандидата + кеш в `med_registry` (повторные просмотры мгновенны и офлайн). Массовый скрейп 14k карточек НЕ делаем.

### 1. Данные — Flyway `V5__registry_detail_cache.sql`

`ALTER TABLE med_registry ADD COLUMN` (все nullable):

| колонка | тип | источник |
|---|---|---|
| `ndda_id` | BIGINT | `id` из list-резолва |
| `risk_class` | TEXT | `degreeRiskName` |
| `purpose` | TEXT | `purpose` |
| `use_area` | TEXT | `useArea` |
| `tech_chars` | TEXT | `shortTechnicalCharacteristicsRu` |
| `mi_kind` | TEXT | `termName_rus` |
| `mi_kind_def` | TEXT | `termDefinition` |
| `detail_fetched_at` | TIMESTAMP | момент удачного fetch (маркер «кеш есть») |

Entity `MedRegistry` — те же поля. Реимпорт дампа (`ON CONFLICT … DO UPDATE`) обновляет только slim-поля и кеш-колонки НЕ трогает.

### 2. Интеграция — пакет `integration/ndda` (по паттерну goszakup)

- `NddaClient` (интерфейс): `Long resolveId(String regNumber)` (null — не найден), `NddaDetailDto fetchDetail(long id)`.
- `NddaHttpClient` (`@Component`, `java.net.http.HttpClient`, connect-timeout 15с): без auth; конфиг `ndda.api.base-url` (default выше). Сеть/5xx/кривой JSON → `UpstreamException` (существующий, → 502). `NddaDetailDto` — Jackson-DTO с нужными полями, остальное игнорится.
- Живые формы ответов закрепить фикстурами в `src/test/resources/ndda/` (list-резолв + карточка, сняты 2026-07-05).

### 3. Сервис и API

`RegistryDetailService.detail(String regNumber)`:

1. `MedRegistryRepository.findByRegNumber` — нет записи → 404 (`NotFoundException`).
2. `detail_fetched_at != null` → ответ из БД, сети нет.
3. Иначе: `resolveId` + `fetchDetail` **вне транзакции** (§6 CLAUDE.md, паттерн TechSpecService) → `RegistryDetailWriter.save(...)` (`@Transactional`, отдельный бин) → ответ.
4. РУ на портале не найден (resolveId → null) — пишем только `detail_fetched_at` (поля null), чтобы не долбить портал при каждом клике; UI покажет «описание не заполнено». Повторный принудительный fetch не предусматриваем (YAGNI).
5. Ошибка сети — `UpstreamException` → 502, в БД ничего не пишем (следующий клик = retry).

Endpoint: `GET /api/registry/detail?regNumber=…` в `RegistryController` (чтение, без `@PreAuthorize ADMIN`; regNumber с №/скобками/кириллицей — обычный URL-encoded query-параметр). Ответ `RegistryDetailResponse {regNumber, riskClass, purpose, useArea, techChars, miKind, miKindDef, fetchedAt}`.

`med_registry` — общая сущность (без market): рыночный фильтр/штамп не участвуют.

### 4. UI — панель «Реестр» (`tenders.component.ts`)

- Строка кандидата кликабельна + явный чип «▸ Описание» (визуальный маркер, что есть разворот).
- Разворот — полноширинная `<tr>` с colspan под строкой кандидата (паттерн раскрытия спеки лота). Открыт один за раз; повторный клик сворачивает.
- Внутри две колонки:
  - **слева «ТЗ лота»** — `lot.requiredSpec`, `pre-wrap` + вертикальный scroll (ограничение высоты ~300px); если ТЗ пусто — колонку не рисуем, описание на всю ширину («пусто — не рисуем»);
  - **справа «Из реестра НЦЭЛС»** — строка «Класс риска · Вид МИ» (определение вида — мелким серым), затем блоки «Назначение», «Область применения», «Краткие тех. характеристики». Пустые блоки не рисуем; всё пусто → «В карточке НЦЭЛС описание не заполнено».
- Первое открытие: «Загружаем карточку НЦЭЛС…» (~1–2 с). Ошибка → мягкое сообщение в развороте + повторный клик = retry (панель не закрывается, тост не нужен).
- Ответ кешируется и на фронте (в объекте кандидата) — повторное разворачивание без запроса.

### 5. Бонус: adopt обогащает каталог

`RegistryMatchService.adoptForLot`: если создаётся **новая** `MedEquipment` и у записи реестра `tech_chars` непустой — записать его в `med_equipment.spec`. Внешний вызов при adopt НЕ делаем (adopt не должен зависеть от доступности НЦЭЛС); существующую позицию каталога не трогаем. Побочный эффект: KZ-каталог наполняется спеками → оживает smart-match.

### 6. Тесты

- `NddaHttpClientTest` — парсинг фикстур живых ответов (по образцу `GoszakupHttpClientTest`).
- `RegistryDetailServiceTest` (`@SpringBootTest @Transactional`, mock `NddaClient`): первый вызов тянет и кеширует; второй — из БД без обращения к клиенту; «не найден на портале» → маркер + пустые поля; неизвестный regNumber → 404; ошибка сети → 502 и кеш не записан.
- Adopt-обогащение: расширить существующий тест adopt (новая позиция получает spec из tech_chars; при пустом кеше — spec null как раньше).
- Фронт: `npm run build`; живая проверка Playwright: `/tenders` (KZ) → карточка импортного тендера → «Реестр» → клик по кандидату → описание рядом с ТЗ.

## Вне скоупа

- Массовое офлайн-обогащение 14k записей; обновление slim-дампа.
- Показ описания в карточке частной заявки (там бейдж без выпадашки) — отдельным блоком при необходимости.
- Использование purpose/tech_chars в скоринге матчинга (возможное будущее: токены из описания).
- TTL/принудительное освежение кеша карточки.
