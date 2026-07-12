# Разбор техспецификации лота СК-Фармации (fms.ecc.kz) — дизайн

**Дата:** 2026-07-12
**Статус:** одобрено, к реализации
**Автор:** сессия Claude + владелец

## 1. Цель

Кнопка «ТЗ» на лоте импортного тендера СК-Фармации должна реально разбирать техническую спецификацию (сейчас 404 «не найдено на goszakup», т.к. `TechSpecService` жёстко завязан на goszakup). Заполненный `requiredSpec` + габариты питают «Подбор» (реестр-матч по разобранному ТЗ) — ради этого всё.

## 2. Механика портала (проверено вживую на объявлении 521464)

СК-Фармация не отдаёт per-lot файл ТЗ прямой ссылкой (как goszakup v3 `Lots.Files`), но всё серверно и парсится Jsoup, без JS и без авторизации:

1. **Вкладка документов:** `GET /ru/announce/index/{announceId}?tab=documents` → server-rendered `<table>` требований (11 строк). Строка с типом «Техническая спецификация» содержит кнопку `<button onclick="actionModalShowFiles({announceId}, {docReqId})">Перейти</button>`. **`docReqId` свой на каждом объявлении** (на 521464 = 1608) → извлекаем динамически, не хардкодим.
2. **Модалка файлов ТЗ:** `GET /ru/announce/actionAjaxModalShowFiles/{announceId}/{docReqId}` → server-rendered `<table class="table table-bordered">`, колонки `[Номер лота | Документ | Автор | Организация | Дата | Подпись]`. Строка данных: `Номер лота` = `1040409-Т1`, `Документ` = `<a href="https://fms.ecc.kz/files/download_file/{fileId}/">ТС КТ каз-русс.pdf</a>`. На 521464 — 12 PDF на 12 лотов (+1 лишняя строка).
3. **PDF:** `GET /files/download_file/{fileId}/` → `200 application/pdf`, `%PDF`, ~300 КБ, двуязычный «каз-русс» (казахская + русская секция — как goszakup) → существующий `TechSpecExtractor.russianSection` подходит.

Все три запроса — `java.net.http` + browser-UA (как `SkPharmacyHttpClient`), троттлинг не нужен (единичные ручные клики).

## 3. Ключ связи «лот ↔ PDF» (главное решение)

PDF помечены реальным № лота `1040409-Т1`. У нас `tender_lot.lot_number` = порядковый `1..12`, а реальный код (`SkLot.code`, Jsoup td[1] вкладки lots = именно `1040409-Т1`) при импорте **выбрасывается** в `SkPharmacyTenderWriter.rebuildLots`.

**Решение (Approach A — join по коду лота):** персистить код лота в новую колонку `tender_lot.source_lot_code` и матчить строку модалки по `lotCode == lot.sourceLotCode`. Точное 1:1, устойчиво к мусорным именам файлов («ТС не подп», «ТЗ по стан»).

**Отклонённые альтернативы:**
- *Join по порядку строк* — хрупко: в модалке 13 строк на 12 лотов, пропуски в id (409,410,411,412,**414**…). Смещение → чужой ТЗ на лоте.
- *Свалить все PDF в один `requiredSpec` тендера* — теряется по-лотовая точность, «Подбор» не получит габариты конкретного лота.

**Цена решения:** существующие 8 SK-тендеров надо переимпортировать (кнопка «Обновить») для заполнения `source_lot_code`. До этого ТЗ на старом SK-лоте вернёт понятную ошибку.

## 4. Архитектура

Ветка по `tender.platform` внутри существующего `TechSpecService.parse(lotId)` — единый вход, единый эндпоинт `POST /api/lots/{id}/parse-techspec`, единая кнопка «ТЗ».

```
tender.sourceExtId "521464-1"  → announceId "521464" (часть до первого '-')
lot.sourceLotCode "1040409-Т1"
GET documents(announceId)      → строка типа "Техническая спецификация" → docReqId
GET modal(announceId,docReqId) → [{lotCode, pdfUrl, fileName}, …]
firstMatch lotCode==sourceLotCode → downloadFile(pdfUrl) → PdfTextExtractor
→ TechSpecExtractor.russianSection → SpecConstraintExtractor
→ TechSpecWriter.apply(lotId, text, constraints)   (переиспользуем как есть)
```

### Компоненты
- **`SkTechSpecClient`** (новый, `integration/skpharmacy`): browser-UA `java.net.http`.
  - `List<SkTechSpecRef> fetchTechSpecRefs(String announceId)` — documents → docReqId (по типу «Техническая спецификация») → modal → строки.
  - `byte[] downloadFile(String url)` — 404 → null.
  - `boolean isConfigured()` — всегда true (токен не нужен; для симметрии с goszakup-веткой).
- **`SkTechSpecHtmlParser`** (новый, чистый, статические методы — тестируется на фикстурах):
  - `String parseTechSpecDocReqId(String documentsHtml)` — из строки типа «Техническая спецификация» вынуть 2-й аргумент `actionModalShowFiles(...)`; нет строки → null.
  - `List<SkTechSpecRef> parseModal(String modalHtml)` — строки `table.table-bordered`, `lotCode` = col0, `pdfUrl` = `a[href*=download_file]` из col1; строки без ссылки/без кода пропускаем.
- **`SkTechSpecRef`** (record): `String lotCode, String pdfUrl, String fileName`.
- **`TechSpecService`**: ветка `if (tender.platform == SK_PHARMACY) { …sk path… } else { …goszakup path (как есть)… }`. Общий хвост (PDF→текст→секция→constraints→writer) — вынести в приватный метод, чтобы не дублировать.
- **`TenderLot`**: поле `String sourceLotCode` + колонка (V12).
- **`SkPharmacyTenderWriter.rebuildLots`**: `lot.setSourceLotCode(l.code())`.

### Данные (Flyway V12)
```sql
ALTER TABLE tender_lot ADD COLUMN source_lot_code VARCHAR(50);
```
Nullable, без бэкфила (заполнится переимпортом). `TenderLotResponse` НЕ расширяем — поле внутреннее (join-ключ), UI не показывает.

## 5. Мультифайл на лот

Если по `lotCode` в модалке > 1 строки (дубль/«не подп»/подпись отдельной строкой) — берём **первую** + возвращаем `ambiguous=true` (как goszakup `LotTechSpecRef.ambiguous`). `ParseResult.source` = имя файла.

## 6. Обработка ошибок (та же таксономия, что goszakup)

- `tender.platform != SK_PHARMACY` в SK-ветке не попадём (диспетчер по платформе).
- SK-лот без `sourceLotCode` (старый импорт) → `BadRequestException` «Переимпортируйте тендер СК-Фармации, чтобы разобрать ТЗ» (400).
- Нет строки «Техническая спецификация» на вкладке / нет строки под наш код лота / PDF 404 → `NotFoundException` (404).
- Сеть/не-200 от портала → `UpstreamException` (502).
- PDF нечитаем / пустая русская секция → `UnprocessableException` (422).
- Гард рынка лота — как в текущем `TechSpecService` (`em.find` обходит фильтр → явная сверка `market`).
- Сеть — ВНЕ транзакции; запись — `TechSpecWriter` (отдельный `@Transactional` бин), как сейчас (§6 CLAUDE.md).

## 7. Frontend

Изменений по сути нет: кнопка «ТЗ» уже показывается на SK-лотах (`isImportedTender()` = KZ + номер `\d+-\d+`, SK-номера подходят) и уже зовёт `POST /api/lots/{id}/parse-techspec`. Станет рабочей. Ошибки — существующий error-тост. Подсказка «нажмите ТЗ» в панели «Подбор» становится валидной и для SK.

## 8. Тестирование

- **`SkTechSpecHtmlParserTest`** — на реальных фикстурах (сохранить `documents.html` объявления 521464 и `techspec-modal.html` = ответ `actionAjaxModalShowFiles/521464/1608`): `parseTechSpecDocReqId` → "1608"; `parseModal` → ≥12 refs, первый `lotCode="1040409-Т1"`, `pdfUrl` содержит `download_file`.
- **`SkPharmacyHtmlParserTest`** (существующий) — добавить ассерт `SkLot.code == "1040409-Т1"` (защита ключа связи).
- **`SkPharmacyTenderWriterTest`** / интеграция — после upsert у лота 1 `source_lot_code == "1040409-Т1"`.
- **`TechSpecServiceSkTest`** — `@MockitoBean SkTechSpecClient` (refs + PDF-байты из фикстуры реального «ТС … .pdf»), проверить: заполнился `requiredSpec`, `source`; лот без кода → 400; нет матча → 404. Фикстура PDF: `src/test/resources/skpharmacy/techspec-*.pdf` (реальный скачанный файл).
- Гейт: `./gradlew test` 0 падений; FE `npm run build`.

## 9. Живая проверка (Playwright)

Переимпорт 521464-1 (кнопка «Обновить», фильтр СК-Фармация) → у лотов появился `source_lot_code` → открыть тендер → на лоте «компьютерный томограф» клик «ТЗ» → тост «ТЗ разобрано», в лоте заполнен `requiredSpec` → «Подбор» использует габариты/характеристики из ТЗ.

## 10. Вне scope

- Авто-разбор ТЗ при импорте (сейчас — по кнопке, как goszakup).
- Хранение реального № лота для goszakup (отдельный пункт бэклога).
- LLM-фолбэк для табличных ТЗ.
- Множественные ТЗ-файлы одного лота как разные варианты (берём первый).
