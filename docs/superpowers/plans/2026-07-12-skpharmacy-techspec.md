# Разбор ТЗ лота СК-Фармации — план реализации

> **Для исполнителя:** реализовать по задачам, TDD (тест → провал → реализация → зелёный → коммит). Ветка `feat/sk-techspec`. Спека: `docs/superpowers/specs/2026-07-12-skpharmacy-techspec-design.md`.

**Goal:** кнопка «ТЗ» на лоте импортного тендера СК-Фармации разбирает техспецификацию с fms.ecc.kz и заполняет `requiredSpec`/габариты (питает «Подбор»).

**Architecture:** ветка по `tender.platform` в существующем `TechSpecService.parse(lotId)`; SK-путь: documents-tab → docReqId → modal-endpoint → per-lot PDF → PDFBox → русская секция → `SpecConstraintExtractor` → `TechSpecWriter`. Join лот↔PDF по новому `tender_lot.source_lot_code`.

**Tech Stack:** Java 17, Spring Boot, Jsoup 1.18.1, `java.net.http`, PDFBox, Flyway, JUnit5 + Mockito + Postgres nirdb.

## Global Constraints
- Сеть — ВНЕ транзакции; запись — через `TechSpecWriter` (@Transactional бин). §6 CLAUDE.md.
- Гард рынка лота: `em.find` обходит hibernate-фильтр → явная сверка `tender.market == MarketContext.get()`.
- Диспетч по платформе — ПЕРВЫМ (до goszakup-проверки токена), иначе SK без goszakup-токена ложно 400.
- Фикстуры реальные: `documents.html`, `techspec-modal.html`, `techspec-kt.pdf` (объявление 521464) — уже в `src/test/resources/skpharmacy/`.
- Менять схему — только новой миграцией (V12), не править V1–V11.

---

### Task 1: Ключ связи — `source_lot_code` (миграция + сущность + writer)

**Files:**
- Create: `src/main/resources/db/migration/V12__tender_lot_source_code.sql`
- Modify: `src/main/java/com/vladoose/nir/entity/TenderLot.java` (поле)
- Modify: `src/main/java/com/vladoose/nir/integration/skpharmacy/SkPharmacyTenderWriter.java` (`rebuildLots`)
- Test: `src/test/java/com/vladoose/nir/integration/skpharmacy/SkPharmacyHtmlParserTest.java` (доп. ассерт)

**Interfaces produced:** `TenderLot.getSourceLotCode()/setSourceLotCode(String)`; после SK-импорта у лота `source_lot_code` = код с портала (`1040409-Т1`).

- [ ] **Шаг 1.** В `SkPharmacyHtmlParserTest.parseLots_realFixture_deviceLots` добавить ассерт: `assertThat(lots.get(0).code()).isEqualTo("1040409-Т1");` (защита: `SkLot.code` = реальный № лота — ключ связи). Прогнать → должен ПРОЙТИ уже сейчас (парсер это извлекает), фиксируем контракт.
- [ ] **Шаг 2.** `V12__tender_lot_source_code.sql`: `ALTER TABLE tender_lot ADD COLUMN source_lot_code VARCHAR(50);`
- [ ] **Шаг 3.** `TenderLot`: добавить `@Column(name = "source_lot_code", length = 50) private String sourceLotCode;` (Lombok @Getter/@Setter на классе).
- [ ] **Шаг 4.** `SkPharmacyTenderWriter.rebuildLots`: в цикле `lot.setSourceLotCode(l.code());` (рядом с `setEquipName`).
- [ ] **Шаг 5.** `./gradlew compileJava test --tests SkPharmacyHtmlParserTest --tests SkPharmacyTenderWriterTest` (sandbox off) → зелёно. Если `SkPharmacyTenderWriterTest` проверяет лот — добавить ассерт `source_lot_code` после upsert.
- [ ] **Шаг 6.** Commit `feat(sk-techspec): source_lot_code — ключ связи лот↔ТЗ (V12 + writer)`.

### Task 2: Парсер ТЗ-страниц СК-Фармации (чистый, на фикстурах)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/skpharmacy/SkTechSpecRef.java`
- Create: `src/main/java/com/vladoose/nir/integration/skpharmacy/SkTechSpecHtmlParser.java`
- Test: `src/test/java/com/vladoose/nir/integration/skpharmacy/SkTechSpecHtmlParserTest.java`

**Interfaces produced:**
- `record SkTechSpecRef(String lotCode, String pdfUrl, String fileName)`
- `static String SkTechSpecHtmlParser.parseTechSpecDocReqId(String documentsHtml)` — id или null
- `static List<SkTechSpecRef> SkTechSpecHtmlParser.parseModal(String modalHtml)`

- [ ] **Шаг 1.** Тест `parseDocReqId_realFixture`: `parseTechSpecDocReqId(fixture("documents.html"))` → `"1608"`. Тест `parseModal_realFixture`: `parseModal(fixture("techspec-modal.html"))` → size ≥ 12; `get(0).lotCode()=="1040409-Т1"`; `get(0).pdfUrl()` contains `/files/download_file/`; `pdfUrl` абсолютный (`https://fms.ecc.kz…`). Тест null-safe: пустой html → null / пустой список.
- [ ] **Шаг 2.** Прогнать → FAIL (классов нет).
- [ ] **Шаг 3.** `SkTechSpecRef` — record. `SkTechSpecHtmlParser`:
  - `parseTechSpecDocReqId`: Jsoup, найти `tr`, где первая `td` text (trim) == «Техническая спецификация», в её `button[onclick]` regex `actionModalShowFiles\(\s*\d+\s*,\s*(\d+)\s*\)` → группа 1. Нет → null.
  - `parseModal`: `table.table-bordered tr`; для строки взять `td` — `lotCode = td[0].text().trim()`, `a = tr.selectFirst("a[href*=download_file]")`; если `a==null` или lotCode пуст → skip; `pdfUrl = a.absUrl("href")` (fallback `a.attr("href")`), `fileName = a.text().trim()`. (Jsoup `absUrl` требует baseUri → парсить через `Jsoup.parse(html, "https://fms.ecc.kz")`.)
- [ ] **Шаг 4.** Прогнать → PASS.
- [ ] **Шаг 5.** Commit `feat(sk-techspec): парсер documents/modal ТЗ СК-Фармации (Jsoup, фикстуры)`.

### Task 3: HTTP-клиент ТЗ СК-Фармации

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/skpharmacy/SkTechSpecClient.java`

**Interfaces produced:**
- `List<SkTechSpecRef> fetchTechSpecRefs(String announceId)` — documents → docReqId → modal; нет строки ТЗ → пустой список.
- `byte[] downloadFile(String url)` — 200 → байты, 404 → null, иначе IOException/не-200 → бросить `IllegalStateException` (обернём в 502 выше).
- `boolean isConfigured()` → true.

- [ ] **Шаг 1.** Реализовать на `java.net.http.HttpClient` + browser-UA (константа как в `SkPharmacyHttpClient`; можно переиспользовать её `base-url` из `skpharmacy.base-url`). Методы:
  - `fetchTechSpecRefs`: GET `{base}/ru/announce/index/{announceId}?tab=documents` → `parseTechSpecDocReqId` → null? верни `List.of()` : GET `{base}/ru/announce/actionAjaxModalShowFiles/{announceId}/{docReqId}` → `parseModal`.
  - `downloadFile`: GET url (абсолютный) → `HttpResponse.BodyHandlers.ofByteArray()`; 404 → null; не-200 → `IllegalStateException`.
  - не-200/IO на documents/modal → `IllegalStateException` (→ 502 в сервисе).
- [ ] **Шаг 2.** `./gradlew compileJava` (тест здесь не пишем — реальная сеть; поведение проверит `TechSpecServiceSkTest` через мок клиента + живая проверка). Commit `feat(sk-techspec): SkTechSpecClient (documents/modal/download)`.

### Task 4: Ветка платформы в `TechSpecService` + SK-путь

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/TechSpecService.java`
- Test: `src/test/java/com/vladoose/nir/service/TechSpecServiceSkTest.java`

**Interfaces consumed:** `SkTechSpecClient`, `TenderLot.getSourceLotCode()`, `Tender.getPlatform()`, существующие `PdfTextExtractor`/`TechSpecExtractor`/`SpecConstraintExtractor`/`TechSpecWriter`.

- [ ] **Шаг 1.** Тест `TechSpecServiceSkTest` (@SpringBootTest @Transactional, `@MockitoBean SkTechSpecClient`): создать SK-тендер (platform=SK_PHARMACY, sourceExtId="521464-1", market KZ) + лот с `sourceLotCode="1040409-Т1"`. Мок: `fetchTechSpecRefs("521464")` → `[ref("1040409-Т1", url, "ТС КТ.pdf")]`; `downloadFile(url)` → байты `techspec-kt.pdf`; `isConfigured()`→true. Вызвать `parse(lotId)` → `requiredSpec` не пуст, `source`="ТС КТ.pdf". Кейс: лот без `sourceLotCode` → `BadRequestException`. Кейс: `fetchTechSpecRefs`→`[]` → `NotFoundException`. Кейс: ref есть, `downloadFile`→null → `NotFoundException`.
- [ ] **Шаг 2.** Прогнать → FAIL.
- [ ] **Шаг 3.** Рефактор `TechSpecService`:
  - Инжектить `SkTechSpecClient` (доп. к `GoszakupClient`).
  - После market-гарда: `if (lot.getTender().getPlatform() == TenderPlatform.SK_PHARMACY) return parseSk(lot);` — ДО goszakup-проверок токена.
  - `parseSk(lot)`: `code = lot.getSourceLotCode()`; blank → `BadRequestException("Переимпортируйте тендер СК-Фармации, чтобы разобрать ТЗ")`. `announceId = beforeDash(tender.getSourceExtId())`. `refs = skClient.fetchTechSpecRefs(announceId)` (IllegalState → `UpstreamException` 502). `ref = refs.stream().filter(r->code.equalsIgnoreCase(r.lotCode())).findFirst()` → пусто → `NotFoundException`. `ambiguous = refs.stream().filter(...code...).count() > 1`. `pdf = skClient.downloadFile(ref.pdfUrl())` → null → `NotFoundException`. Дальше — общий хвост.
  - Выделить общий хвост goszakup/SK в приватный `ParseResult finishParse(long lotId, byte[] pdf, boolean ambiguous, String fileName)`: `PdfTextExtractor.extract` → `russianSection` (пусто → `UnprocessableException`) → `SpecConstraintExtractor.extract` → `writer.apply` → `ParseResult`.
  - Goszakup-ветку переключить на `finishParse(...)` (без изменения поведения).
- [ ] **Шаг 4.** `./gradlew test --tests TechSpecServiceSkTest --tests TechSpecService*` (+ goszakup-тест ТЗ, если есть — не регрессировал) → зелёно.
- [ ] **Шаг 5.** Commit `feat(sk-techspec): ветка платформы в TechSpecService + SK-разбор ТЗ`.

### Task 5: Полный прогон + живая проверка

- [ ] **Шаг 1.** `lsof -ti :8080 | xargs kill -9`; `./gradlew test` (sandbox off) — весь набор 0 падений; `cd frontend && npm run build` (из корня компаундом) — зелёно.
- [ ] **Шаг 2.** Поднять бэк с токеном/почтой (start-ais.sh или env); переимпорт СК-Фармации (кнопка «Обновить», фильтр СК-Фармация) → у лотов 521464-1 появился `source_lot_code` (psql-проверка).
- [ ] **Шаг 3.** Playwright: открыть тендер 521464-1 → лот «компьютерный томограф» → «ТЗ» → тост «ТЗ разобрано», `requiredSpec` заполнен; «Подбор» использует ТЗ. Скриншот.
- [ ] **Шаг 4.** Whole-branch review (субагент) → фикс findings → `git checkout main && merge --ff-only feat/sk-techspec` → удалить ветку. Обновить CLAUDE.md §8/§16 + PROGRESS.

## Self-review (сверка с спекой)
- Пункты спеки §4 (компоненты), §3 (join-код), §5 (мультифайл→first+ambiguous), §6 (ошибки), §8 (тесты) — покрыты Task 1–4. §7 (frontend без изменений) — Task 5 живая проверка. §9 — Task 5.
- Плейсхолдеров нет. Типы согласованы: `SkTechSpecRef(lotCode,pdfUrl,fileName)` одинаков в Task 2/3/4.
