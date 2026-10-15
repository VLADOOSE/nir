# Ответ поставщика: цены из файла (Excel, Word) и экран сверки — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ответ поставщика на запрос цен (письмо на zakup@ или файл, загруженный вручную) с КП в Excel или Word сам расставляет цены по позициям запроса с пометкой 💡 «не сверено»; экран «Ответ поставщика» показывает рядом нашу позицию и строку файла, расхождения и документ (таблица, страницы PDF, картинка, текст письма); «Всё верно» снимает 💡; в КП клиенту цены идут прежними путями.

**Architecture:** файлы ответа хранятся в новой таблице `supplier_reply_file` с разобранной таблицей (`parsed` JSONB). Разбор Excel / Word (`ReplyTableReader`) идёт вне транзакции письма, сопоставление строк с позициями (`ReplyMatcher`) и подстановка (`SupplierReplyService`) — в транзакции записи письма, строки ответа под блокировкой. 💡 становится признаком строки ответа (`price_request_item.response_auto`), а не текстом примечания. Экран — новая ленивая страница `/price-requests/:id` на API `SupplierReplyController`. Миграция V30.

**Tech Stack:** Spring Boot 3.5.6 / Java 17 / Hibernate 6 / PostgreSQL 17 (nirdb); Apache POI 5.2.5 (`poi-ooxml` + новый `poi-scratchpad` для .doc); PDFBox 3.0.7; GreenMail 2.1.2; Angular 21 standalone, ленивые страницы.

**Spec:** `docs/superpowers/specs/2026-10-15-supplier-reply-files-design.md` (заменяет §9.3 спеки `2026-10-02-client-kp-constructor-design.md`).

## Global Constraints

- Миграция — **V30** (`V30__supplier_reply_files.sql`); прежние миграции не трогать.
- `supplier_reply_file` не рыночная: доступ только через запрос цен с ЯВНЫМ гардом рынка — `findById` = `em.find`, фильтр рынка его не режет (CLAUDE.md §14); запрос чужого рынка — 404 «Запрос цен не найден».
- Запись — только `ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`), чтение — любой вошедший.
- Пределы: хранение файла письма — `mail.imap.max-file-mb` (по умолчанию 15 МБ, не больше 25; пусто или не число — 15); разбор Excel / Word — до 5 МБ (`SupplierReplyFiles.PARSE_MAX`); таблица — до 2 000 строк данных и 40 колонок; PDF — до 30 страниц (`ReplyPdfPages.MAX_PAGES`), страница — PNG 110 dpi (`KpPreviewRenderer.DPI`); ручная загрузка — предел сервлета 10 МБ (`spring.servlet.multipart.max-file-size`).
- Пороги сопоставления (`ReplyMatcher`): подставить — оценка ≥ 0,60 и отрыв от второй кандидатуры ≥ 0,15 и у позиции, и у строки; подсказка — ≥ 0,35.
- Ручная цена (цена есть, `response_auto = false`) не затирается разбором никогда; строки ответа запроса подстановка читает с `PESSIMISTIC_WRITE`.
- Разбор файла никогда не роняет приём почты: любой сбой (включая `Error`) — пометка у файла (`FAILED`), письмо записывается.
- Приём почты в КП клиенту не пишет.
- Валюта: таблица в валюте, отличной от валюты рынка запроса (`Market.currencyCode()`), не подставляется.
- Цена из суммы — только когда ячейка цены ПУСТАЯ (у «ХЭЛП» в ячейке цены «нет», а в сумме «15» — это отказ, а не цена 15).
- Тесты — `@SpringBootTest @Transactional` на nirdb; почтовые интеграционные — под `@MailIntegrationTest`; MockMvc — `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())` (без `@AutoConfigureMockMvc`, CLAUDE.md §14); новые тестовые данные с префиксом `ZZ`; полный прогон — `./gradlew cleanTest test` (sandbox off; перед ним остановить `bootRun` и `devMail`: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`, `lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`).
- Фронт: страница — только ленивая (`loadComponent`); типы в `ApiService` — `import type`; результаты промисов — `.then`, без нативного `await`; файлы — только через `HttpClient` blob (CLAUDE.md §14); гейт — `npm --prefix frontend run build`.
- Тексты для людей — по-русски, без длинного тире в документах (документов здесь нет); числа на экране — `money` / `numText`.
- Коммиты кончаются строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`; все делегируемые агенты — Opus.

## Review Focus

1. **Строка обращения над таблицей со словом «цена»** («Цена договорная», «Цены действительны…» одной ячейкой): шапкой берётся строка таблицы, а не обращение. Тест — задача 2 `letterLineWithPriceWordIsNotHeader`.
2. **Две одинаковые позиции** («Набор реагентов» × 2) и две похожие строки файла: ни одна цена не подставляется, обе — подсказки. Тест — задача 3 `twinPositionsAreSuggestionsOnly`.
3. **Цены в рублях в ответ на запрос West-Med (KZ)**: не подставляются, в уведомлении — «Цены в файле … — в руб., не подставлены». Тест — задача 4 `foreignCurrencyNotApplied` и задача 5 `foreignCurrencyFileLine`.
4. **Битый Excel / файл с паролем / Word без таблицы**: письмо записано, у файла пометка, проход не встаёт, текст письма разбирается как раньше. Тест — задача 5 `brokenFileKeepsTextParsing`.
5. **Вкладка, открытая до деплоя** (тело PUT `/responses` без полей файла): связь строки ответа с файлом сохраняется. Тест — задача 1 `putWithoutFileFieldsKeepsLink`.

## Решения при планировании (уточнения спеки)

1. Файл хранит разобранную таблицу (`parsed` JSONB): экран и пересчёт не перечитывают файл; роли колонок правятся в ней же.
2. Связь строки ответа с файлом — `response_file_id` + `response_file_row` (номер строки файла: у Excel — номер строки листа, у Word — номер строки таблицы, с 1). Подсказки экрана считаются заново из таблицы при каждом показе.
3. Статус запроса после подстановки из письма меняет приём почты (`SupplierReplyService.markResponded`), сервис файлов только хранит и подставляет.
4. Из файла подставлена хоть одна цена — текст письма не разбирается (`KpOutcome.FILE_PRICES`); иначе — прежний разбор текста.
5. Файлы письма разбираются вне транзакции — `SupplierReplyFiles.prepareFor` в `MailReceiveService` и только у писем с меткой `[КП-…]` в теме.
6. Ссылка «Открыть в АИС» у ответа поставщика — экран `/price-requests/{id}`; у возврата и автоответа — как была.
7. Excel / Word без шапки с наименованием и ценой — `NO_TABLE`; PDF, картинки и прочее — `NOT_PARSED`; больше предела хранения — `TOO_LARGE` без байтов; не открылся — `FAILED`.
8. Ручное сохранение цены (PUT `/responses`) снимает 💡 у присланных строк; связь с файлом меняется только явно: `fileId` + `fileRow` — новая строка, `clearFile: true` — снять.
9. «нет цены» в таблице цен — поставщик ответил (`response_date` есть, статус `RESPONDED` или `ACCEPTED`), а цены по позиции нет.
10. Удалить можно только загруженный вручную файл; снимаются только несверенные цены из него.
11. Колонка без заголовка, в которой есть текст (у «ХЭЛП» — «Заявлены, но отсутствуют»), — примечание.
12. `LineField` дополняется (`NUMBER`, `ARTICLE`, `UNIT`, `PRICE`, `SUM`, `VAT`, `CURRENCY`, `NOTE`). Разбор смет клиник (`PrivateRequestImportService.learnedMap`) берёт из словаря только свои роли (наименование, бренд, количество): выученная «цена» там остаётся «Игнорировать», из словаря не стирается, и прежние экраны импорта о новых ролях не знают.
13. Порядок подстрочных правил ролей — как в спеке §3.2, с двумя уточнениями: «наимен» стоит раньше «единиц» и «кол-во» («Наименование (описание), единица измерения» — наименование), а «товар» / «описание» / «комплектация» — позже них («Количество товара» — количество); «страна» — `IGNORE` раньше правила бренда («Страна производства» не бренд). Спека §3.2 выровнена.
14. Вместо признака `hasReply` у поставщика таблицы цен — `replyRequestId`: ссылке «Ответ ›» нужен id запроса.
15. Таблица другого файла — тем же `GET /reply?fileId=` (отдельный путь `/reply/table` из спеки §5 не нужен: подсказки позиций считаются по выбранному файлу и меняются вместе с таблицей). Спека выровнена.
16. **Разные числа — не подставлять.** Если у позиции есть число, которого нет в строке, а у строки — которого нет у позиции («№ 5» и «№ 2», «60 мм» и «40 мм», «MSL24» и «MST15»), оценка пары ×0,5: подставить нельзя, подсказкой — можно. Повод — настоящий КП «ХЭЛП»: кюретки № 5 / № 2, зеркала 40 / 60 / 90 мм; однозначные числа словами оценки не считаются, и без этого правила позиция «№ 5» при файле только с «№ 2» получила бы чужую цену. Спека §3.3 дополнена.
17. Файлы писем zakup@ скачиваются у всех писем ящика (предел — `mail.imap.max-file-mb`), а сохраняются только у ответов поставщиков с найденным запросом: решать «ответ ли это» разбор письма до классификации не умеет. Список имён вложений в уведомлении не меняется (картинки в тексте письма в него не входят).
18. Гейт «перепутано = 0» (спека §7) — `SupplierReplyGateTest` на `golden.txt`: позиции запроса к двум настоящим КП (обезличенные копии «Эр Оптикс» и «ХЭЛП»), «подставлено» — храповик по замеру. Казахстанские примеры (спека §11) добавляются в тот же файл, когда будут.

## Карта файлов

| Файл | Что |
|---|---|
| `src/main/resources/db/migration/V30__supplier_reply_files.sql` | новая таблица, признак 💡, перенос 💡, сид словаря |
| `src/main/java/com/vladoose/nir/entity/SupplierReplyFile.java`, `ReplyFileKind.java`, `ReplyParseStatus.java`, `ReplyTable.java` | новые |
| `src/main/java/com/vladoose/nir/entity/PriceRequestItem.java` | + `responseAuto`, `responseFileId`, `responseFileRow` |
| `src/main/java/com/vladoose/nir/entity/LineField.java` | + 8 ролей |
| `src/main/java/com/vladoose/nir/repository/SupplierReplyFileRepository.java` | новый |
| `src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java` | + `lockByPriceRequestId`, `clearAuto` |
| `src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java` | + `findSupplierReplies` (native) |
| `src/main/java/com/vladoose/nir/dto/response/PriceRequestItemResponse.java` | + 3 поля |
| `src/main/java/com/vladoose/nir/controller/PriceRequestController.java` | PUT `/responses`: 💡 и связь с файлом |
| `src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java` | словарь для смет клиник — только свои роли |
| `src/main/java/com/vladoose/nir/service/supplierreply/*` | `MoneyText`, `HeaderRoles`, `ReplyTableReader`, `ReplyRowValues`, `ReplyMatcher`, `HeaderDictionary`, `PreparedFile`, `ReplyFileInfo`, `ReplyPdfPages`, `SupplierReplyFiles`, `SupplierReplyService`, `SupplierReplyViewBuilder` |
| `src/main/java/com/vladoose/nir/dto/request/ReplyColumnRole.java`, `dto/response/SupplierReplyView.java` | новые |
| `src/main/java/com/vladoose/nir/controller/SupplierReplyController.java` | новый |
| `src/main/java/com/vladoose/nir/service/mail/ImapMailboxConnector.java` | предел файла zakup@ |
| `src/main/java/com/vladoose/nir/service/MailReceiveService.java` | разбор файлов вне транзакции |
| `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java` | подстановка из файла, статус, связь с письмом, текст 💡 |
| `src/main/java/com/vladoose/nir/service/mail/KpOutcome.java`, `MailNotificationComposer.java` | `FILE_PRICES`, строка о файле, ссылка на экран |
| `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java`, `dto/response/PrivateRequestPricesResponse.java` | `auto`, `NO_PRICE`, `replyRequestId` |
| `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java`, `service/ClientOfferMapper.java` | `purchaseUnverified`, `replyRequestId` |
| `src/main/java/com/vladoose/nir/service/offer/PurchaseOffer.java`, `dto/response/SupplierOfferResponse.java`, `service/ClientOfferRequestService.java` | `auto`, `priceRequestId` |
| `src/main/java/com/vladoose/nir/dto/response/InboundEmailResponse.java`, `controller/InboundController.java` | имена файлов ответа |
| `build.gradle`, `src/main/resources/application.yaml` | `poi-scratchpad`, `mail.imap.max-file-mb` |
| `frontend/src/app/shared/supplier-reply.ts` | новый — типы и роли |
| `frontend/src/app/services/api.service.ts` | методы ответа поставщика |
| `frontend/src/app/app.routes.ts` | ленивый маршрут `price-requests/:id` |
| `frontend/src/app/pages/price-requests/price-request-reply.component.ts`, `reply-document.component.ts` | новые — экран |
| `frontend/src/app/pages/private-requests/pr-prices.component.ts`, `shared/private-request.ts` | 💡 по признаку, «нет цены», ссылки |
| `frontend/src/app/shared/client-offer.ts`, `pages/client-offers/client-offer-items.component.ts`, `client-offer-editor.component.ts` | 💡 у закупки, «цены не сверены: N» |
| `frontend/src/app/pages/tenders/tenders.component.ts`, `pages/inbound/inbound.component.ts` | ссылки, 💡, файлы |
| `scripts/dev-mail.py` | `reply <prId> [файл]` |
| Тесты | `src/test/java/com/vladoose/nir/service/supplierreply/*` (тот же пакет, что код: тесты зовут служебные методы), правки `MailIngestWriterTest`, `MailReceiveServiceTest`, `MailReceiveServiceIntegrationTest`, `MailNotificationComposerTest`, `ImapMailboxConnectorTest` |
| Фикстуры | `src/test/resources/supplier-reply/optics.xls`, `help.xls`, `price-table.doc`, `golden.txt` |

---

### Task 1: V30 — файлы ответа, признак 💡 у строки ответа, роли колонок

**Files:**
- Create: `src/main/resources/db/migration/V30__supplier_reply_files.sql`
- Create: `src/main/java/com/vladoose/nir/entity/ReplyFileKind.java`, `ReplyParseStatus.java`, `ReplyTable.java`, `SupplierReplyFile.java`
- Create: `src/main/java/com/vladoose/nir/repository/SupplierReplyFileRepository.java`
- Create: `src/main/java/com/vladoose/nir/service/supplierreply/ReplyFileInfo.java`
- Modify: `src/main/java/com/vladoose/nir/entity/PriceRequestItem.java`
- Modify: `src/main/java/com/vladoose/nir/entity/LineField.java`
- Modify: `src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/PriceRequestItemResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java:160-165`
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java` (`updateResponses`, `ItemResponseDto`, конструктор)
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java` (`learnedMap`)
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplySchemaTest.java`, `src/test/java/com/vladoose/nir/service/supplierreply/PriceRequestResponsesAutoTest.java`; правка `src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java`

**Interfaces:**
- Produces: сущность `SupplierReplyFile` (поля ниже), перечисления `ReplyFileKind { EXCEL, WORD, PDF, IMAGE, OTHER }`, `ReplyParseStatus { PARSED, NO_TABLE, FAILED, TOO_LARGE, NOT_PARSED }`; `record ReplyTable(String source, int headerRow, int score, String currency, List<ReplyTable.Column> columns, List<ReplyTable.Row> rows)` с `record Column(int index, String header, LineField role)`, `record Row(int fileRow, List<String> cells)` и методом `ReplyTable withRoles(Map<Integer, LineField> roles)`; `PriceRequestItem.isResponseAuto()/setResponseAuto`, `get/setResponseFileId (Long)`, `get/setResponseFileRow (Integer)`; `PriceRequestItemRepository.lockByPriceRequestId(Long)`, `clearAuto(Long)`; `SupplierReplyFileRepository` (методы ниже); `record ReplyFileInfo(Long id, String fileName, String mimeType, Long sizeBytes, ReplyFileKind kind, ReplyParseStatus parseStatus, String parseNote, Integer pageCount, Long inboundEmailId, String uploadedBy, OffsetDateTime createdAt)`; `PriceRequestController.ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote, Long fileId, Integer fileRow, Boolean clearFile)`.

- [ ] **Step 1: Миграция `V30__supplier_reply_files.sql`**

```sql
-- Ответ поставщика: файлы из писем и загруженные вручную, цены из файла
-- (спека docs/superpowers/specs/2026-10-15-supplier-reply-files-design.md §5).

CREATE TABLE supplier_reply_file (
    id               BIGSERIAL PRIMARY KEY,
    price_request_id BIGINT       NOT NULL REFERENCES price_request (id) ON DELETE CASCADE,
    -- письмо, из которого файл; пусто — загружен вручную
    inbound_email_id BIGINT       REFERENCES inbound_email (id) ON DELETE SET NULL,
    file_name        VARCHAR(255) NOT NULL,
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    sha256           VARCHAR(64),
    -- пусто — файл больше предела хранения (только имя)
    data             BYTEA,
    kind             VARCHAR(10)  NOT NULL,
    parse_status     VARCHAR(20)  NOT NULL,
    parse_note       VARCHAR(300),
    -- разобранная таблица Excel / Word: колонки с ролями, строки с номерами строк файла, валюта
    parsed           JSONB,
    page_count       INT,
    uploaded_by      VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_reply_file_sha UNIQUE (price_request_id, sha256)
);
CREATE INDEX idx_supplier_reply_file_inbound ON supplier_reply_file (inbound_email_id);

-- 💡 — цена поставлена разбором (текст письма или файл) и ещё не сверена; ручное сохранение снимает
ALTER TABLE price_request_item ADD COLUMN response_auto BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_request_item ADD COLUMN response_file_id BIGINT REFERENCES supplier_reply_file (id) ON DELETE SET NULL;
ALTER TABLE price_request_item ADD COLUMN response_file_row INT;

-- 💡 из текста письма был пометкой в начале примечания — стал признаком строки ответа; «Срок…» и «Контекст…» остаются
UPDATE price_request_item
   SET response_auto = TRUE,
       response_note = NULLIF(regexp_replace(response_note, '^💡[^.]*\.?\s*', ''), '')
 WHERE response_note LIKE '💡%';

-- Словарь заголовков: роли колонок КП поставщика (§3.2). Уже выученные заголовки не трогаем.
INSERT INTO header_synonym (header_norm, field) VALUES
    ('№', 'NUMBER'), ('№ п/п', 'NUMBER'), ('№ пп', 'NUMBER'), ('№ п.п.', 'NUMBER'),
    ('артикул', 'ARTICLE'), ('артикул (модель)', 'ARTICLE'), ('модель', 'ARTICLE'), ('каталожный номер', 'ARTICLE'),
    ('ед.', 'UNIT'), ('ед', 'UNIT'), ('ед. изм.', 'UNIT'), ('ед.изм.', 'UNIT'), ('единица измерения', 'UNIT'),
    ('цена', 'PRICE'), ('цена за ед.', 'PRICE'), ('цена за единицу', 'PRICE'), ('цена, руб.', 'PRICE'),
    ('цена, руб. за ед.', 'PRICE'), ('цена, тенге', 'PRICE'), ('цена, тг', 'PRICE'), ('цена за ед., тг', 'PRICE'),
    ('цена с ндс', 'PRICE'), ('цена со скидкой', 'PRICE'),
    ('сумма', 'SUM'), ('сумма, руб.', 'SUM'), ('сумма, тенге', 'SUM'), ('сумма, тг', 'SUM'), ('общая сумма', 'SUM'),
    ('общая сумма, тг', 'SUM'), ('стоимость', 'SUM'),
    ('ндс', 'VAT'), ('ставка ндс', 'VAT'), ('ставка ндс, %', 'VAT'),
    ('валюта', 'CURRENCY'),
    ('примечание', 'NOTE'), ('комментарий', 'NOTE')
ON CONFLICT (header_norm) DO NOTHING;
```

- [ ] **Step 2: Перечисления, таблица JSONB, сущность, репозиторий**

`entity/ReplyFileKind.java`:

```java
package com.vladoose.nir.entity;

/** Вид файла ответа поставщика (спека 2026-10-15 §3.1): по расширению и первым байтам. */
public enum ReplyFileKind { EXCEL, WORD, PDF, IMAGE, OTHER }
```

`entity/ReplyParseStatus.java`:

```java
package com.vladoose.nir.entity;

/**
 * Итог разбора файла ответа: PARSED — таблица найдена; NO_TABLE — Excel / Word без шапки с наименованием и ценой;
 * FAILED — не открылся (пароль, повреждён); TOO_LARGE — больше предела хранения, байтов нет; NOT_PARSED — PDF,
 * картинка, прочее или Excel / Word больше предела разбора: только просмотр и «Скачать».
 */
public enum ReplyParseStatus { PARSED, NO_TABLE, FAILED, TOO_LARGE, NOT_PARSED }
```

`entity/ReplyTable.java`:

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Разобранная таблица файла ответа (колонка `supplier_reply_file.parsed`, JSONB): шапка с ролями колонок и строки
 * данных с номерами строк файла. source — «Лист1» или «таблица 2»; headerRow — номер строки шапки в файле (с 1);
 * score — сколько заголовков узнано; currency — код валюты по шапке / колонке «Валюта» / ценам (null — не названа).
 * Незнакомые поля JSON пропускаются: строка, записанная более новой версией, не должна мешать открыть файл после
 * отката (тот же урок, что у OfferColumn, CLAUDE.md §8 «КП клиенту»).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplyTable(String source, int headerRow, int score, String currency, List<Column> columns, List<Row> rows) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(int index, String header, LineField role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(int fileRow, List<String> cells) {}

    /** Копия с новыми ролями колонок (индекс → роль); незатронутые колонки — как были. */
    public ReplyTable withRoles(Map<Integer, LineField> roles) {
        List<Column> out = new ArrayList<>(columns.size());
        for (Column c : columns) out.add(roles.containsKey(c.index()) ? new Column(c.index(), c.header(), roles.get(c.index())) : c);
        return new ReplyTable(source, headerRow, score, currency, out, rows);
    }
}
```

`entity/SupplierReplyFile.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Файл ответа поставщика (спека 2026-10-15 §3.1): из письма с меткой [КП-…] или загруженный вручную. НЕ рыночный —
 * доступ только через запрос цен с явным гардом рынка (SupplierReplyService.guard). parsed — разобранная таблица
 * Excel / Word (роли колонок правятся в ней же).
 */
@Entity
@Table(name = "supplier_reply_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierReplyFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "price_request_id", nullable = false)
    private Long priceRequestId;

    /** Строка «Входящих», из письма которой файл; пусто — загружен вручную (или письмо ещё не записано). */
    @Column(name = "inbound_email_id")
    private Long inboundEmailId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    /** Байты файла; пусто — больше предела хранения. */
    @Column(name = "data")
    private byte[] data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReplyFileKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private ReplyParseStatus parseStatus;

    @Column(name = "parse_note", length = 300)
    private String parseNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed", columnDefinition = "jsonb")
    private ReplyTable parsed;

    @Column(name = "page_count")
    private Integer pageCount;

    /** Логин того, кто загрузил файл вручную; у файла из письма — пусто. */
    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

`service/supplierreply/ReplyFileInfo.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;

import java.time.OffsetDateTime;

/** Файл ответа без байтов и таблицы — список источников экрана (байты тянуть ради списка незачем). */
public record ReplyFileInfo(Long id, String fileName, String mimeType, Long sizeBytes, ReplyFileKind kind,
                            ReplyParseStatus parseStatus, String parseNote, Integer pageCount, Long inboundEmailId,
                            String uploadedBy, OffsetDateTime createdAt) {}
```

`repository/SupplierReplyFileRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.entity.SupplierReplyFile;
import com.vladoose.nir.service.supplierreply.ReplyFileInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierReplyFileRepository extends JpaRepository<SupplierReplyFile, Long> {

    /** Тот же файл в том же запросе хранится один раз (сравнение по SHA-256). */
    boolean existsByPriceRequestIdAndSha256(Long priceRequestId, String sha256);

    @Query("SELECT new com.vladoose.nir.service.supplierreply.ReplyFileInfo(f.id, f.fileName, f.mimeType, f.sizeBytes, "
            + "f.kind, f.parseStatus, f.parseNote, f.pageCount, f.inboundEmailId, f.uploadedBy, f.createdAt) "
            + "FROM SupplierReplyFile f WHERE f.priceRequestId = :prId ORDER BY f.id")
    List<ReplyFileInfo> findInfo(@Param("prId") Long prId);

    @Query("SELECT f.parsed FROM SupplierReplyFile f WHERE f.id = :id")
    Optional<ReplyTable> findParsed(@Param("id") Long id);

    /** Имена файлов по письмам — колонка «Входящих» (одним запросом на страницу). */
    @Query("SELECT f.inboundEmailId, f.fileName FROM SupplierReplyFile f WHERE f.inboundEmailId IN :ids ORDER BY f.id")
    List<Object[]> findNamesByInboundIds(@Param("ids") Collection<Long> ids);
}
```

- [ ] **Step 3: `PriceRequestItem`, `LineField`, `PriceRequestItemRepository`, `PriceRequestItemResponse`**

В `PriceRequestItem` — после `responseNote`:

```java
    /** 💡 — цена поставлена разбором (текст письма или файл ответа) и ещё не сверена; ручное сохранение снимает (V30). */
    @Column(name = "response_auto", nullable = false)
    private boolean responseAuto;

    /** Файл ответа, из которого цена; пусто — не из файла. */
    @Column(name = "response_file_id")
    private Long responseFileId;

    /** Строка файла (с 1), из которой цена. */
    @Column(name = "response_file_row")
    private Integer responseFileRow;
```

`LineField`:

```java
package com.vladoose.nir.entity;

public enum LineField {
    NAME, MANUFACT, QUANTITY, IGNORE,
    /** Роли колонок КП поставщика (спека 2026-10-15 §3.2); экраны позиций клиента их не показывают (решение плана 12). */
    NUMBER, ARTICLE, UNIT, PRICE, SUM, VAT, CURRENCY, NOTE
}
```

В `PriceRequestItemRepository` (импорты `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.Modifying`):

```java
    /** Строки ответа запроса — под блокировкой: ручная цена, сохранённая в ту же секунду, не затрётся подстановкой. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PriceRequestItem i WHERE i.priceRequest.id = :prId ORDER BY i.id")
    List<PriceRequestItem> lockByPriceRequestId(@Param("prId") Long prId);

    /** «Всё верно» — снять 💡 со всех цен запроса. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PriceRequestItem i SET i.responseAuto = false WHERE i.priceRequest.id = :prId AND i.responseAuto = true")
    int clearAuto(@Param("prId") Long prId);
```

В `PriceRequestItemResponse` — после `responseNote`:

```java
    /** 💡 — цена из разбора и ещё не сверена. */
    private boolean responseAuto;
    private Long responseFileId;
    private Integer responseFileRow;
```

MapStruct перенесёт поля по именам (`PriceRequestItemMapper.toResponse`); `toEntity` из `PriceRequestItemRequest` их не заполняет — предупреждение «unmapped target property», не ошибка.

- [ ] **Step 4: Цена из текста письма — признак, а не пометка в примечании**

В `MailIngestWriter.applySupplierResponse` заменить

```java
                    item.setResponseNote("💡 Цена распознана автоматически, проверьте."
                            + (pp.get().term() != null ? " Срок: " + pp.get().term() + "." : "")
                            + (pp.get().matchedSnippet() != null ? " Контекст: «" + pp.get().matchedSnippet() + "»." : ""));
```

на

```java
                    item.setResponseAuto(true);                 // 💡 — признак строки ответа (V30), не текст примечания
                    item.setResponseNote("Цена распознана из письма."
                            + (pp.get().term() != null ? " Срок: " + pp.get().term() + "." : "")
                            + (pp.get().matchedSnippet() != null ? " Контекст: «" + pp.get().matchedSnippet() + "»." : ""));
```

В `MailIngestWriterTest.supplierResponse_singleLot_parsesPrice_queuesLoudNotification_movesCursor` после проверки цены добавить:

```java
        assertThat(back.getItems().get(0).isResponseAuto()).isTrue();
        assertThat(back.getItems().get(0).getResponseNote()).startsWith("Цена распознана из письма").doesNotContain("💡");
```

- [ ] **Step 5: PUT `/responses` — снимает 💡, связь с файлом меняется только явно**

В `PriceRequestController`: поле `private final SupplierReplyFileRepository fileRepository;`, параметр конструктора (последним) и присваивание. `ItemResponseDto`:

```java
    /**
     * Ответ по строке: цена, примечание и, по желанию, строка файла ответа (fileId + fileRow — выбрать, clearFile —
     * снять). Тело вкладки, открытой до V30, полей файла не несёт — связь остаётся как была.
     */
    public record ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote,
                                  Long fileId, Integer fileRow, Boolean clearFile) {
        public ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote) {
            this(itemId, responsePrice, responseNote, null, null, null);
        }
    }
```

В цикле `updateResponses` заменить

```java
            item.setResponsePrice(u.responsePrice());
            item.setResponseNote(u.responseNote());
            itemRepository.save(item);
```

на

```java
            item.setResponsePrice(u.responsePrice());
            item.setResponseNote(u.responseNote());
            item.setResponseAuto(false);                    // ручное сохранение = сверено (спека §3.4)
            if (Boolean.TRUE.equals(u.clearFile())) {
                item.setResponseFileId(null);
                item.setResponseFileRow(null);
            } else if (u.fileId() != null && u.fileRow() != null) {
                if (u.fileRow() < 1) throw new BadRequestException("Номер строки файла — с 1 (позиция " + u.itemId() + ")");
                SupplierReplyFile f = fileRepository.findById(u.fileId())
                        .filter(x -> id.equals(x.getPriceRequestId()))
                        .orElseThrow(() -> new BadRequestException("Файл " + u.fileId() + " не из этого запроса цен"));
                item.setResponseFileId(f.getId());
                item.setResponseFileRow(u.fileRow());
            }
            itemRepository.save(item);
```

- [ ] **Step 6: Тесты схемы и PUT — написать**

`src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplySchemaTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V30: файл ответа хранит таблицу (JSONB), один файл — один раз на запрос, каскады, перенос 💡 из примечания. */
@SpringBootTest
@Transactional
class SupplierReplySchemaTest {

    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository requests;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    PriceRequest pr() {
        Tender t = Tender.builder().tenderNumber("ZZSRF-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        t.getLots().add(TenderLot.builder().tender(t).equipName("ZZ Аппарат").quantity(1).build());
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZSRF Дистр " + System.nanoTime()).build());
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        p.getItems().add(PriceRequestItem.builder().priceRequest(p).tenderLot(t.getLots().get(0)).requestedQuantity(1).build());
        return requests.save(p);
    }

    static ReplyTable table() {
        return new ReplyTable("Лист1", 3, 2, "KZT",
                List.of(new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Цена, тг", LineField.PRICE)),
                List.of(new ReplyTable.Row(4, List.of("ZZ Аппарат", "100"))));
    }

    SupplierReplyFile file(Long prId, String sha) {
        return files.save(SupplierReplyFile.builder().priceRequestId(prId).fileName("kp.xlsx").sha256(sha)
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).parsed(table()).data(new byte[]{1, 2}).build());
    }

    @Test
    void parsedTableRoundTrips() {
        PriceRequest p = pr();
        Long id = file(p.getId(), "a1").getId();
        em.flush();
        em.clear();

        SupplierReplyFile back = files.findById(id).orElseThrow();
        assertThat(back.getParsed()).isEqualTo(table());
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(files.findParsed(id)).contains(table());
        assertThat(files.findInfo(p.getId())).singleElement().extracting(i -> i.fileName()).isEqualTo("kp.xlsx");
    }

    @Test
    void sameFileTwiceInOneRequestRejected() {
        PriceRequest p = pr();
        file(p.getId(), "same");
        em.flush();
        assertThatThrownBy(() -> {
            file(p.getId(), "same");
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingFileUnlinksItem_deletingRequestDeletesFiles() {
        PriceRequest p = pr();
        SupplierReplyFile f = file(p.getId(), "b1");
        PriceRequestItem it = p.getItems().get(0);
        it.setResponseFileId(f.getId());
        it.setResponseFileRow(4);
        em.flush();

        em.createNativeQuery("DELETE FROM supplier_reply_file WHERE id = :id").setParameter("id", f.getId()).executeUpdate();
        em.clear();
        Object link = em.createNativeQuery("SELECT response_file_id FROM price_request_item WHERE id = :id")
                .setParameter("id", it.getId()).getSingleResult();
        assertThat(link).isNull();

        PriceRequest p2 = pr();
        file(p2.getId(), "c1");
        em.flush();
        em.createNativeQuery("DELETE FROM price_request_item WHERE price_request_id = :id").setParameter("id", p2.getId()).executeUpdate();
        em.createNativeQuery("DELETE FROM price_request WHERE id = :id").setParameter("id", p2.getId()).executeUpdate();
        Number left = (Number) em.createNativeQuery("SELECT count(*) FROM supplier_reply_file WHERE price_request_id = :id")
                .setParameter("id", p2.getId()).getSingleResult();
        assertThat(left.intValue()).isZero();
    }

    /** То же выражение, что в V30: пометка «💡 …» уходит из примечания, «Срок…» и «Контекст…» остаются. */
    @Test
    void autoMarkMigrationExpression() {
        String sql = "SELECT NULLIF(regexp_replace(CAST(:n AS text), '^💡[^.]*\\.?\\s*', ''), '')";
        assertThat(em.createNativeQuery(sql).setParameter("n", "💡 Цена распознана автоматически, проверьте. Срок: 3 недели.")
                .getSingleResult()).isEqualTo("Срок: 3 недели.");
        assertThat(em.createNativeQuery(sql).setParameter("n", "💡 Цена распознана автоматически, проверьте.")
                .getSingleResult()).isNull();
        Number marked = (Number) em.createNativeQuery("SELECT count(*) FROM price_request_item WHERE response_note LIKE '💡%'")
                .getSingleResult();
        assertThat(marked.intValue()).isZero();
    }
}
```

`src/test/java/com/vladoose/nir/service/supplierreply/PriceRequestResponsesAutoTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT /api/price-requests/{id}/responses: ручное сохранение снимает 💡; строка файла меняется только явно. */
@SpringBootTest
@Transactional
class PriceRequestResponsesAutoTest {

    @Autowired WebApplicationContext wac;
    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository requests;
    @Autowired PriceRequestItemRepository items;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired EntityManager em;
    MockMvc mvc;
    PriceRequest pr;
    PriceRequestItem item;
    SupplierReplyFile file;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MarketContext.set(Market.KZ);
        pr = request();
        file = file(pr);
        item = pr.getItems().get(0);
        item.setResponseFileId(file.getId());
        item.setResponseFileRow(3);
        em.flush();
        MarketContext.clear();
    }

    /** Запрос цен с одной позицией и 💡-ценой 100. */
    PriceRequest request() {
        Tender t = Tender.builder().tenderNumber("ZZPUT-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        t.getLots().add(TenderLot.builder().tender(t).equipName("ZZ Позиция").quantity(1).build());
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZPUT Дистр " + System.nanoTime()).build());
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).status("RESPONDED").build();
        p.getItems().add(PriceRequestItem.builder().priceRequest(p).tenderLot(t.getLots().get(0)).requestedQuantity(1)
                .responsePrice(new BigDecimal("100")).responseAuto(true).build());
        return requests.save(p);
    }

    SupplierReplyFile file(PriceRequest p) {
        return files.save(SupplierReplyFile.builder().priceRequestId(p.getId()).fileName("kp.xlsx").sha256("zz" + System.nanoTime())
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    PriceRequestItem reload() {
        em.flush();
        em.clear();
        return items.findById(item.getId()).orElseThrow();
    }

    void putBody(String body, int expected) throws Exception {
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/responses").header("X-Market", "KZ")
                        .contentType("application/json").content(body).with(user("admin").roles("ADMIN")))
                .andExpect(status().is(expected));
    }

    @Test
    void putWithoutFileFieldsKeepsLink() throws Exception {
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":120,\"responseNote\":\"после звонка\"}]", 200);
        PriceRequestItem back = reload();
        assertThat(back.isResponseAuto()).isFalse();
        assertThat(back.getResponsePrice()).isEqualByComparingTo("120");
        assertThat(back.getResponseFileId()).isEqualTo(file.getId());
        assertThat(back.getResponseFileRow()).isEqualTo(3);
    }

    @Test
    void pickedRowOfThisRequestIsLinked() throws Exception {
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":90,\"fileId\":" + file.getId() + ",\"fileRow\":5}]", 200);
        assertThat(reload().getResponseFileRow()).isEqualTo(5);
    }

    @Test
    void fileOfAnotherRequestIs400() throws Exception {
        MarketContext.set(Market.KZ);
        SupplierReplyFile other = file(request());
        em.flush();
        MarketContext.clear();
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":90,\"fileId\":" + other.getId() + ",\"fileRow\":1}]", 400);
    }

    @Test
    void clearFileRemovesLink() throws Exception {
        putBody("[{\"itemId\":" + item.getId() + ",\"responsePrice\":null,\"clearFile\":true}]", 200);
        PriceRequestItem back = reload();
        assertThat(back.getResponseFileId()).isNull();
        assertThat(back.getResponsePrice()).isNull();
    }
}
```

- [ ] **Step 7: Сметы клиник не видят новых ролей словаря**

`PrivateRequestImportService.learnedMap()` (разбор Excel смет клиник: «Входящие», частная заявка, чаты) — только свои роли (решение плана 12); импорт `java.util.Set`:

```java
    /** Роли, которые умеет разметка смет клиник; роли КП поставщика (цена, сумма…) здесь — «Игнорировать». */
    private static final Set<LineField> CLIENT_ROLES = Set.of(LineField.NAME, LineField.MANUFACT, LineField.QUANTITY);

    private Map<String, LineField> learnedMap() {
        Map<String, LineField> map = new HashMap<>();
        for (HeaderSynonym s : synonymRepository.findAll()) {
            if (CLIENT_ROLES.contains(s.getField())) map.put(s.getHeaderNorm(), s.getField());
        }
        return map;
    }
```

Тест — в `SupplierReplySchemaTest` (импорты `com.vladoose.nir.service.PrivateRequestImportService`, `org.apache.poi.xssf.usermodel.XSSFWorkbook`, `java.io.ByteArrayOutputStream`):

```java
    @Autowired PrivateRequestImportService importService;

    /** Сид V30 знает «цена» → цена; смета клиники показывает такую колонку «Игнорировать», как до V30. */
    @Test
    void clinicPreviewShowsSupplierRolesAsIgnore() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet();
            var head = sheet.createRow(0);
            head.createCell(0).setCellValue("Наименование");
            head.createCell(1).setCellValue("Цена");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("Кушетка");
            data.createCell(1).setCellValue(95000);
            wb.write(out);
            xlsx = out.toByteArray();
        }
        assertThat(importService.preview(xlsx, "смета.xlsx").getColumns().get(1).getField()).isEqualTo(LineField.IGNORE);
    }
```

- [ ] **Step 8: Прогнать — красные до правок, зелёные после**

Сначала (до шагов 1–5 и 7) убедиться, что классы не компилируются / тесты красные; после всех шагов:

Run: `./gradlew test --tests '*SupplierReplySchemaTest' --tests '*PriceRequestResponsesAutoTest' --tests '*MailIngestWriterTest' --tests '*MailReceiveServiceIntegrationTest' --tests '*PrivateRequestImport*'` (sandbox off)
Expected: PASS. Мутации (по одной): убрать `item.setResponseAuto(false)` в `updateResponses` — `putWithoutFileFieldsKeepsLink` красный; убрать фильтр `CLIENT_ROLES` — `clinicPreviewShowsSupplierRolesAsIgnore` красный.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V30__supplier_reply_files.sql src/main/java/com/vladoose/nir/entity \
  src/main/java/com/vladoose/nir/repository src/main/java/com/vladoose/nir/service/supplierreply/ReplyFileInfo.java \
  src/main/java/com/vladoose/nir/dto/response/PriceRequestItemResponse.java src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java \
  src/main/java/com/vladoose/nir/controller/PriceRequestController.java src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java \
  src/test/java/com/vladoose/nir/service/supplierreply src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java
git commit -m "feat(supplier-reply): V30 — файлы ответа, признак 💡 у строки ответа, роли колонок

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Excel и Word → таблица (`MoneyText`, `HeaderRoles`, `ReplyTableReader`)

**Files:**
- Modify: `build.gradle` (после `poi-ooxml`: `implementation 'org.apache.poi:poi-scratchpad:5.2.5'`)
- Create: `src/main/java/com/vladoose/nir/service/supplierreply/MoneyText.java`, `HeaderRoles.java`, `ReplyTableReader.java`
- Create fixtures: `src/test/resources/supplier-reply/optics.xls`, `help.xls`, `price-table.doc`
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/MoneyTextTest.java`, `HeaderRolesTest.java`, `ReplyTableReaderTest.java`

**Interfaces:**
- Consumes: `ReplyTable`, `ReplyFileKind`, `LineField` (задача 1).
- Produces: `MoneyText.amount(String) → BigDecimal|null` (2 знака), `MoneyText.quantity(String) → MoneyText.Qty(BigDecimal value, String unit)|null`, `MoneyText.currency(String) → "KZT"|"RUB"|"USD"|"EUR"|"CNY"|null`, `MoneyText.label(String code) → "тг"|"руб."|…`; `HeaderRoles.key(String)`, `HeaderRoles.role(String header, Map<String, LineField> learned)`; `ReplyTableReader.read(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException → Optional<ReplyTable>`.

- [ ] **Step 1: Фикстуры — обезличенные копии настоящих КП и .doc**

Обезличить два настоящих Excel-КП Регион-Мед (из `~/Downloads`): «КП № 72417 СК РЕГИОН-МЕД для ЦГБ Сызрань ножи шовный.xls» («Эр Оптикс», шапка — 15-я строка, 5 строк данных, условия и контакт менеджера под таблицей) и «КП Регион-Мед, 07.24г..xls» (НПФ «ХЭЛП», шапка — 12-я строка, 62 строки, колонка без заголовка «Заявлены, но отсутствуют», подпись директора в 77-й). В репозиторий идут копии ТОЛЬКО ЗНАЧЕНИЙ ячеек в новую книгу — без картинок (логотипы), стилей и свойств документа (автор), с заменой бланка, адресата, обращения и людей. Одноразовая программа во временной папке (не в репозитории), классы POI — из кеша Gradle:

```bash
S=$(mktemp -d); cat > $S/Anon.java <<'EOF'
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import java.io.*;
import java.util.Map;

/** Обезличенная копия КП: значения ячеек и объединения в новую книгу, без картинок, стилей и свойств документа. */
public class Anon {
  public static void main(String[] a) throws Exception {
    Map<String, String> repl = a[2].equals("optics") ? Map.of(
        "0:0", "ООО «Поставщик»",
        "1:0", "000000 г. Город, ул. Улица, д. 1 | +7 000 000 00 00 | www.example.com",
        "6:1", "ООО «Покупатель»",
        "7:1", "Больница",
        "9:0", "Уважаемые коллеги, / Компания ООО «Поставщик» направляет Вам коммерческое предложение",
        "12:0", "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ № 1 от 01-01-2026",
        "31:1", "Менеджер по продажам, +7 (000) 000-00-00, sales@example.com")
      : Map.of("7:5", "ООО «Покупатель»", "76:2", "Директор                                        Фамилия И. О.");
    try (Workbook src = WorkbookFactory.create(new FileInputStream(a[0])); HSSFWorkbook dst = new HSSFWorkbook()) {
      Sheet s = src.getSheetAt(0), d = dst.createSheet("Лист1");
      for (Row r : s) {
        Row nr = d.createRow(r.getRowNum());
        for (Cell c : r) {
          Cell nc = nr.createCell(c.getColumnIndex());
          String key = r.getRowNum() + ":" + c.getColumnIndex();
          if (repl.containsKey(key)) { nc.setCellValue(repl.get(key)); continue; }
          switch (c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType()) {
            case NUMERIC -> nc.setCellValue(c.getNumericCellValue());
            case STRING -> nc.setCellValue(c.getStringCellValue());
            case BOOLEAN -> nc.setCellValue(c.getBooleanCellValue());
            default -> { }
          }
        }
      }
      for (CellRangeAddress m : s.getMergedRegions()) d.addMergedRegion(m);
      try (OutputStream out = new FileOutputStream(a[1])) { dst.write(out); }
    }
  }
}
EOF
CP=$(find ~/.gradle/caches/modules-2/files-2.1 \( -name 'poi-5.2.5.jar' -o -name 'commons-io-2.*.jar' -o -name 'commons-collections4-4.4.jar' -o -name 'commons-math3-3.6.1.jar' -o -name 'log4j-api-2.*.jar' -o -name 'commons-codec-1.*.jar' -o -name 'SparseBitSet-1.*.jar' \) | tr '\n' ':')
java -cp "$CP" $S/Anon.java ~/Downloads/'КП № 72417 СК РЕГИОН-МЕД для ЦГБ Сызрань ножи шовный.xls' src/test/resources/supplier-reply/optics.xls optics
java -cp "$CP" $S/Anon.java ~/Downloads/'КП Регион-Мед, 07.24г..xls' src/test/resources/supplier-reply/help.xls help
rm -rf "$S"
```

Проверить глазами все непустые ячейки копий (`python3 -c "import xlrd; …"` — xlrd 2.0 есть в системе): ни имён, ни телефонов, ни адресов почты и сайтов, ни названий больниц; шапка — на прежних строках (15-я и 12-я); номера и цены — как в исходниках.

`.doc` (Word 97–2003) нельзя собрать POI — фикстура делается Word: python-docx собирает .docx, Word сохраняет его как .doc (контейнер Word — CLAUDE.md §14). python-docx — во временном venv:

```bash
V=$(mktemp -d); python3 -m venv $V && $V/bin/pip install -q python-docx
C=~/Library/Containers/com.microsoft.Word/Data/Documents/ais-fixture; mkdir -p $C
$V/bin/python - "$C/price-table.docx" <<'EOF'
import sys, docx
d = docx.Document()
d.add_paragraph("ТОО «Поставщик» — коммерческое предложение")
t = d.add_table(rows=4, cols=5)
for r, row in enumerate([["№", "Наименование", "Кол-во", "Ед. изм.", "Цена, тг"],
                         ["1", "Кушетка медицинская смотровая КМС-01", "2", "шт", "95 000"],
                         ["2", "Столик процедурный СП-02", "1", "шт", "48 500,50"],
                         ["3", "Ширма двухсекционная", "1", "шт", "нет в наличии"]]):
    for c, v in enumerate(row): t.cell(r, c).text = v
d.save(sys.argv[1])
EOF
osascript -e 'tell application "Microsoft Word"
  open file "Macintosh HD:Users:'"$USER"':Library:Containers:com.microsoft.Word:Data:Documents:ais-fixture:price-table.docx"
  delay 2
  save as active document file name "Macintosh HD:Users:'"$USER"':Library:Containers:com.microsoft.Word:Data:Documents:ais-fixture:price-table.doc" file format format document97
  close active document saving no
  quit
end tell'
cp "$C/price-table.doc" src/test/resources/supplier-reply/price-table.doc && rm -rf "$C" "$V"
```

Проверить: `head -c 4 src/test/resources/supplier-reply/price-table.doc | xxd` → `d0cf 11e0`. Константа формата Word 97–2004 в AppleScript — `format document97`; если Word её не знает — открыть словарь «Microsoft Word» в «Редакторе скриптов» (команда `save as`, параметр `file format`).

- [ ] **Step 2: Тесты `MoneyText` и `HeaderRoles` — написать (красные: классов нет)**

`MoneyTextTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.service.supplierreply.MoneyText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTextTest {

    @Test
    void amounts() {
        assertThat(MoneyText.amount("1 360 000 тенге")).isEqualByComparingTo("1360000");
        assertThat(MoneyText.amount("7 200,00")).isEqualByComparingTo("7200");
        assertThat(MoneyText.amount("4700.5")).isEqualByComparingTo("4700.50");
        assertThat(MoneyText.amount("1.360.000")).isEqualByComparingTo("1360000");
        assertThat(MoneyText.amount("1,360,000.50")).isEqualByComparingTo("1360000.50");
        assertThat(MoneyText.amount("48 500,50 ₸")).isEqualByComparingTo("48500.50");
        assertThat(MoneyText.amount("1,5")).isEqualByComparingTo("1.5");
        assertThat(MoneyText.amount("14125")).isEqualByComparingTo("14125");
        assertThat(MoneyText.amount("нет")).isNull();
        assertThat(MoneyText.amount("Без НДС")).isNull();
        assertThat(MoneyText.amount("-5")).isNull();
        assertThat(MoneyText.amount("99999999999999999")).isNull();
        assertThat(MoneyText.amount(null)).isNull();
    }

    @Test
    void quantities() {
        assertThat(MoneyText.quantity("1 уп")).isEqualTo(new MoneyText.Qty(new BigDecimal("1"), "уп"));
        assertThat(MoneyText.quantity("2 шт.")).isEqualTo(new MoneyText.Qty(new BigDecimal("2"), "шт"));
        assertThat(MoneyText.quantity("10")).isEqualTo(new MoneyText.Qty(new BigDecimal("10"), null));
        assertThat(MoneyText.quantity("2,5 кг")).isEqualTo(new MoneyText.Qty(new BigDecimal("2.5"), "кг"));
        assertThat(MoneyText.quantity("по запросу")).isNull();
    }

    @Test
    void currencies() {
        assertThat(MoneyText.currency("руб.")).isEqualTo("RUB");
        assertThat(MoneyText.currency("Цена, руб. за ед.")).isEqualTo("RUB");
        assertThat(MoneyText.currency("Цена, тг")).isEqualTo("KZT");
        assertThat(MoneyText.currency("₸")).isEqualTo("KZT");
        assertThat(MoneyText.currency("USD")).isEqualTo("USD");
        assertThat(MoneyText.currency("трубки соединительные")).isNull();
        assertThat(MoneyText.currency("Цена")).isNull();
        assertThat(MoneyText.label("RUB")).isEqualTo("руб.");
        assertThat(MoneyText.label("KZT")).isEqualTo("тг");
    }
}
```

`HeaderRolesTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.service.supplierreply.HeaderRoles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Роли по заголовку без словаря: порядок правил решает (решение плана 13). */
class HeaderRolesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "№ п.п.|NUMBER", "№ пп|NUMBER", "Артикул (модель)|ARTICLE",
            "Наименование (описание), единица измерения|NAME", "Наименование товара|NAME", "Товары (работы, услуги)|NAME",
            "Комплектация|NAME", "Количество|QUANTITY", "Кол-во, шт.|QUANTITY", "Количество товара|QUANTITY",
            "Штук в упаковке|NOTE", "НДС|VAT", "Ставка НДС, %|VAT", "Сумма НДС|VAT", "В т.ч. НДС|VAT",
            "Цена|PRICE", "Цена со скидкой|PRICE", "Цена, руб. за ед.|PRICE", "Цена (в т.ч. НДС)|PRICE", "Стоимость за ед.|PRICE",
            "Сумма, руб.|SUM", "Сумма с НДС|SUM", "Общая сумма, тг|SUM", "Стоимость|SUM",
            "Ед.|UNIT", "Ед. изм.|UNIT", "Валюта|CURRENCY", "Примечание|NOTE", "Срок поставки|NOTE",
            "Производитель|MANUFACT", "Страна производства|IGNORE", "Регистрация в РК|IGNORE", "Характеристики|IGNORE"
    })
    void heuristics(String header, LineField role) {
        assertThat(HeaderRoles.role(header, Map.of())).as(header).isEqualTo(role);
    }

    @Test
    void dictionaryWins_andBlankIsIgnore() {
        assertThat(HeaderRoles.role("  Цена  ", Map.of("цена", LineField.SUM))).isEqualTo(LineField.SUM);
        assertThat(HeaderRoles.role("", Map.of())).isEqualTo(LineField.IGNORE);
        assertThat(HeaderRoles.key("  Цена, ТГ ")).isEqualTo("цена, тг");
    }
}
```

Run: `./gradlew test --tests '*MoneyTextTest' --tests '*HeaderRolesTest'` → FAIL (классов нет).

- [ ] **Step 3: `MoneyText`**

```java
package com.vladoose.nir.service.supplierreply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Числа и валюта из ячеек КП поставщика (спека 2026-10-15 §3.2): «1 360 000 тенге», «7 200,00», «1 уп». Разделители
 * разрядов — пробелы, неразрывные пробелы, апостроф; запятая — дробная, если она одна (так пишут в KZ и RU), а
 * несколько запятых или точек подряд группами по три — разряды («1,360,000», «1.360.000»).
 */
public final class MoneyText {

    /** Больше — не цена, а мусор (предел NUMERIC(15,2), как у КП клиенту). */
    static final BigDecimal MAX = new BigDecimal("9999999999999.99");
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d '.,]*");
    private static final Pattern THOUSANDS_DOT = Pattern.compile("\\d{1,3}(\\.\\d{3}){2,}");
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("\\d{1,3}(,\\d{3}){2,}");
    private static final Pattern QTY = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(\\p{L}[\\p{L}.]*)?");
    private static final Pattern TG = Pattern.compile("(^|[^\\p{L}])тг([^\\p{L}]|$)");
    private static final Pattern RUB = Pattern.compile("(^|[^\\p{L}])руб");

    private MoneyText() {}

    /** Сумма или цена; без цифр, отрицательная или больше MAX — null. */
    public static BigDecimal amount(String s) {
        if (s == null) return null;
        String t = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').trim();
        Matcher m = NUMBER.matcher(t);
        if (!m.find()) return null;
        String n = m.group().replace(" ", "").replace("'", "").replaceAll("[.,]+$", "");
        if (n.isEmpty() || n.equals("-")) return null;
        String digits = n.startsWith("-") ? n.substring(1) : n;
        int comma = n.lastIndexOf(','), dot = n.lastIndexOf('.');
        String plain;
        if (comma >= 0 && dot >= 0) {
            plain = comma > dot ? n.replace(".", "").replace(',', '.') : n.replace(",", "");
        } else if (comma >= 0) {
            plain = THOUSANDS_COMMA.matcher(digits).matches() ? n.replace(",", "") : n.replace(',', '.');
        } else if (dot >= 0) {
            plain = THOUSANDS_DOT.matcher(digits).matches() ? n.replace(".", "") : n;
        } else {
            plain = n;
        }
        try {
            BigDecimal v = new BigDecimal(plain).setScale(2, RoundingMode.HALF_UP);
            return v.signum() < 0 || v.compareTo(MAX) > 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Количество и единица: «1 уп» → 1 и «уп»; «10» → 10 без единицы; без числа — null. */
    public record Qty(BigDecimal value, String unit) {}

    public static Qty quantity(String s) {
        if (s == null) return null;
        Matcher m = QTY.matcher(s.replace(' ', ' '));
        if (!m.find()) return null;
        BigDecimal v = new BigDecimal(m.group(1).replace(',', '.')).stripTrailingZeros();
        if (v.scale() < 0) v = v.setScale(0);
        String unit = m.group(2) == null ? null : m.group(2).replaceAll("\\.+$", "").toLowerCase(Locale.ROOT);
        return new Qty(v, unit == null || unit.isBlank() ? null : unit);
    }

    /** Код валюты по тексту ячейки или заголовка; не названа — null. */
    public static String currency(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("₸") || t.contains("kzt") || t.contains("тенге") || TG.matcher(t).find()) return "KZT";
        if (t.contains("₽") || t.contains("rub") || RUB.matcher(t).find()) return "RUB";
        if (t.contains("$") || t.contains("usd") || t.contains("долл")) return "USD";
        if (t.contains("€") || t.contains("eur") || t.contains("евро")) return "EUR";
        if (t.contains("¥") || t.contains("cny") || t.contains("юан")) return "CNY";
        return null;
    }

    /** Подпись валюты для людей. */
    public static String label(String code) {
        if (code == null) return "";
        return switch (code) {
            case "KZT" -> "тг";
            case "RUB" -> "руб.";
            case "USD" -> "$";
            case "EUR" -> "€";
            case "CNY" -> "юанях";
            default -> code;
        };
    }
}
```

- [ ] **Step 4: `HeaderRoles`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;

import java.util.Locale;
import java.util.Map;

/**
 * Роль колонки по заголовку (спека 2026-10-15 §3.2): сначала обучаемый словарь header_synonym (ключ — как его пишет
 * PrivateRequestImportService.learn: обрезка и нижний регистр), иначе подстроки В ПОРЯДКЕ — первое совпадение решает
 * (решение плана 13: «Цена (в т.ч. НДС)» — цена, «Сумма НДС» — НДС, «Наименование (описание), единица измерения» —
 * наименование, «Количество товара» — количество, «Штук в упаковке» — примечание, «Страна производства» — не бренд).
 */
public final class HeaderRoles {

    private HeaderRoles() {}

    public static String key(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    public static LineField role(String header, Map<String, LineField> learned) {
        String k = key(header);
        if (k.isEmpty()) return LineField.IGNORE;
        LineField l = learned.get(k);
        if (l != null) return l;
        String c = k.replaceAll("\\s+", " ").replace('ё', 'е');
        l = learned.get(c);
        return l != null ? l : heuristic(c);
    }

    static LineField heuristic(String h) {
        if (has(h, "цена", "стоимость за ед", "стоимость ед", "стоимость за 1")) return LineField.PRICE;
        if (h.contains("сумма ндс")) return LineField.VAT;
        if (has(h, "сумма", "стоимость")) return LineField.SUM;
        if (h.contains("ндс")) return LineField.VAT;
        if (h.contains("наимен")) return LineField.NAME;
        if (h.contains("в упаков")) return LineField.NOTE;
        if (has(h, "кол-во", "количество", "кол.")) return LineField.QUANTITY;
        if (has(h, "ед. изм", "ед.изм", "единиц") || h.equals("ед") || h.equals("ед.")) return LineField.UNIT;
        if (has(h, "товар", "описание", "номенклат", "продукц", "комплектац")) return LineField.NAME;
        if (has(h, "артикул", "модель", "каталож")) return LineField.ARTICLE;
        if (h.contains("страна")) return LineField.IGNORE;
        if (has(h, "производ", "бренд", "марка")) return LineField.MANUFACT;
        if (h.contains("валют")) return LineField.CURRENCY;
        if (has(h, "примеч", "комментар", "наличие", "срок")) return LineField.NOTE;
        if (h.startsWith("№") || has(h, "п/п", "п.п") || h.equals("пп")) return LineField.NUMBER;
        return LineField.IGNORE;
    }

    private static boolean has(String h, String... parts) {
        for (String p : parts) if (h.contains(p)) return true;
        return false;
    }
}
```

Run: `./gradlew test --tests '*MoneyTextTest' --tests '*HeaderRolesTest'` → PASS.

- [ ] **Step 5: Тест `ReplyTableReader` — написать (красный)**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Таблица КП поставщика из Excel / Word: шапка под бланком, роли, конец по «Итого», шапка в две строки, склейка Word. */
class ReplyTableReaderTest {

    static byte[] res(String name) throws Exception {
        try (InputStream in = ReplyTableReaderTest.class.getResourceAsStream("/supplier-reply/" + name)) {
            return in.readAllBytes();
        }
    }

    static List<LineField> roles(ReplyTable t) {
        return t.columns().stream().map(ReplyTable.Column::role).toList();
    }

    @Test
    void opticsXls_headerUnderLetterhead_twoPricesAndCurrency() throws Exception {
        ReplyTable t = ReplyTableReader.read(res("optics.xls"), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(15);
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.ARTICLE, LineField.NAME, LineField.QUANTITY,
                LineField.NOTE, LineField.VAT, LineField.PRICE, LineField.PRICE, LineField.CURRENCY);
        assertThat(t.currency()).isEqualTo("RUB");
        ReplyTable.Row first = t.rows().get(0);
        assertThat(first.fileRow()).isEqualTo(16);
        assertThat(first.cells()).containsSequence("1", "MSL24");
        assertThat(first.cells().get(7)).isEqualTo("4700");
    }

    @Test
    void helpXls_noteColumnWithoutHeader() throws Exception {
        ReplyTable t = ReplyTableReader.read(res("help.xls"), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(12);
        assertThat(roles(t)).contains(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.NOTE);
        assertThat(t.currency()).isEqualTo("RUB");
        assertThat(t.rows()).anyMatch(r -> r.cells().contains("Заявлены, но отсутствуют"));
    }

    @Test
    void letterLineWithPriceWordIsNotHeader_andTotalStops() throws Exception {
        byte[] xlsx = xlsx(List.of(
                List.of("ТОО «Поставщик»"),
                List.of("Цена договорная, доставка включена"),
                List.of(),
                List.of("№", "Наименование", "Кол-во", "Цена, тг", "Сумма, тг"),
                List.of("1", "Кушетка смотровая", "2", "95000", "190000"),
                List.of("2", "Столик процедурный", "1", "48500", "48500"),
                List.of("Итого", "", "", "", "238500"),
                List.of("3", "Условия: предоплата", "", "", "")));
        ReplyTable t = ReplyTableReader.read(xlsx, ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(4);
        assertThat(t.rows()).hasSize(2);
        assertThat(t.currency()).isEqualTo("KZT");
    }

    @Test
    void textWithoutTable_isEmpty() throws Exception {
        assertThat(ReplyTableReader.read(xlsx(List.of(List.of("Добрый день"), List.of("Цены по запросу"))), ReplyFileKind.EXCEL, Map.of())).isEmpty();
    }

    @Test
    void docx_rfqReturnedWithPrice_letterheadTablesSkipped() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("Жауапкершілігі шектеулі серіктестігі", "Товарищество с ограниченной ответственностью")));
        table(d, List.of(List.of("Исх. № 534 от 14.10.2026 г.", "ООО «Поставщик»")));
        table(d, List.of(
                List.of("№", "Наименование", "Характеристики", "Кол-во", "Ед. изм.", "Регистрация в РК", "Цена, тг"),
                List.of("1", "Электрокардиограф ЭК3ТЦ-3/6-04 (Аксион)", "", "1", "шт", "РК-МТ-5№017777", "1 382 400")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.source()).isEqualTo("таблица 3");
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.IGNORE, LineField.QUANTITY,
                LineField.UNIT, LineField.IGNORE, LineField.PRICE);
        assertThat(t.currency()).isEqualTo("KZT");
        assertThat(t.rows()).singleElement().extracting(r -> r.cells().get(6)).isEqualTo("1 382 400");
    }

    @Test
    void docx_twoRowHeader_mergedPrice() throws Exception {
        XWPFDocument d = new XWPFDocument();
        XWPFTable t = d.createTable(3, 5);
        String[][] cells = {{"№", "Наименование", "Кол-во", "Цена, тг", ""}, {"", "", "", "за ед.", "сумма"},
                {"1", "Ширма двухсекционная", "2", "30 000", "60 000"}};
        for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) t.getRow(r).getCell(c).setText(cells[r][c]);
        // «Цена, тг» над двумя колонками: gridSpan 2, лишняя ячейка шапки удаляется
        XWPFTableCell price = t.getRow(0).getCell(3);
        CTTcPr pr = price.getCTTc().isSetTcPr() ? price.getCTTc().getTcPr() : price.getCTTc().addNewTcPr();
        pr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        t.getRow(0).removeCell(4);
        ReplyTable read = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(roles(read)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM);
        assertThat(read.rows()).singleElement().extracting(r -> r.cells().get(3)).isEqualTo("30 000");
    }

    @Test
    void docx_tableSplitAcrossPages_isGlued() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("№", "Наименование", "Цена, тг"), List.of("1", "Кушетка", "95 000")));
        table(d, List.of(List.of("2", "Столик", "48 500"), List.of("3", "Ширма", "30 000")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(t.rows()).extracting(r -> r.cells().get(1)).containsExactly("Кушетка", "Столик", "Ширма");
    }

    /**
     * Первая строка данных без цифр — не вторая строка шапки. Склейка «Кол-во по запросу», «Цена, тг нет в наличии»
     * роли не меняет, поэтому без правила «под объединённой ячейкой» строку съела бы шапка.
     */
    @Test
    void digitlessFirstRowStaysData() throws Exception {
        ReplyTable t = ReplyTableReader.read(xlsx(List.of(
                List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Ширма двухсекционная", "по запросу", "нет в наличии"),
                List.of("Кушетка смотровая", "1", "95 000"))), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
        assertThat(t.headerRow()).isEqualTo(1);
        assertThat(t.rows()).extracting(r -> r.cells().get(0)).containsExactly("Ширма двухсекционная", "Кушетка смотровая");
    }

    /** Следующая таблица Word со своей шапкой (условия, вторая спецификация) — не продолжение первой. */
    @Test
    void docx_nextTableWithOwnHeaderIsNotGlued() throws Exception {
        XWPFDocument d = new XWPFDocument();
        table(d, List.of(List.of("№", "Наименование", "Цена, тг"), List.of("1", "Кушетка", "95 000")));
        table(d, List.of(List.of("№", "Наименование", "Сумма, тг"), List.of("1", "Доставка", "5 000"), List.of("2", "Монтаж", "7 000")));
        ReplyTable t = ReplyTableReader.read(docx(d), ReplyFileKind.WORD, Map.of()).orElseThrow();
        // не склеены: выбрана одна таблица целиком (с бо́льшим числом строк), шапка второй не стала строкой данных
        assertThat(t.rows()).extracting(r -> r.cells().get(1)).containsExactly("Доставка", "Монтаж");
    }

    @Test
    void doc_word97Table() throws Exception {
        ReplyTable t = ReplyTableReader.read(res("price-table.doc"), ReplyFileKind.WORD, Map.of()).orElseThrow();
        assertThat(roles(t)).containsExactly(LineField.NUMBER, LineField.NAME, LineField.QUANTITY, LineField.UNIT, LineField.PRICE);
        assertThat(t.rows()).hasSize(3);
        assertThat(t.rows().get(1).cells().get(4)).isEqualTo("48 500,50");
    }

    static byte[] xlsx(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Лист1");
            for (int r = 0; r < rows.size(); r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) row.createCell(c).setCellValue(rows.get(r).get(c));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static void table(XWPFDocument d, List<List<String>> rows) {
        XWPFTable t = d.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) for (int c = 0; c < rows.get(r).size(); c++) t.getRow(r).getCell(c).setText(rows.get(r).get(c));
        d.createParagraph();
    }

    static byte[] docx(XWPFDocument d) throws Exception {
        try (d; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.write(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 6: `ReplyTableReader`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Таблица КП поставщика из Excel (.xlsx / .xls) и Word (.docx / .doc) — спека 2026-10-15 §3.2. Шапка — строка с
 * наибольшим числом узнанных заголовков (не меньше двух, среди них наименование и цена или сумма) в первых 40 строках
 * листа или первых 5 строках таблицы Word; бланк и обращение над ней отбрасываются; шапка в две строки склеивается;
 * конец таблицы — «Итого» / «Всего» / «Итог»; таблица Word, разорванная по страницам (следующая таблица той же ширины
 * без своей шапки), склеивается. Сбой чтения — исключение: его ловит SupplierReplyFiles.
 */
public final class ReplyTableReader {

    static final int HEADER_SCAN = 40;
    static final int WORD_HEADER_SCAN = 5;
    static final int MAX_ROWS = 2000;
    static final int MAX_COLS = 40;
    private static final Pattern TOTAL = Pattern.compile("^(итого|всего|итог|total)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ReplyTableReader() {}

    /** Строки таблицы как текст; firstRow — номер первой строки в файле (с 1); word — таблица Word (шапка в первых 5 строках). */
    record Grid(String source, List<List<String>> rows, int firstRow, boolean word) {}

    public static Optional<ReplyTable> read(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException {
        List<Grid> grids = switch (kind) {
            case EXCEL -> excel(content);
            case WORD -> ole2(content) ? doc(content) : docx(content);
            default -> List.of();
        };
        List<Grid> candidates = new ArrayList<>(grids);
        if (kind == ReplyFileKind.WORD) {
            // таблица на нескольких страницах: следующая таблица той же ширины без своей шапки — её продолжение
            for (int i = 0; i < grids.size(); i++) {
                Grid acc = grids.get(i);
                for (int j = i + 1; j < grids.size() && width(grids.get(j)) == width(grids.get(i))
                        && !startsWithHeader(grids.get(j), learned); j++) {
                    acc = glue(acc, grids.get(j));
                    candidates.add(acc);
                }
            }
        }
        return candidates.stream().map(g -> table(g, learned)).flatMap(Optional::stream)
                .max(Comparator.comparingInt(ReplyTable::score).thenComparingInt(t -> t.rows().size()));
    }

    // ---- Excel ----------------------------------------------------------------------------------------------------

    static List<Grid> excel(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) continue;
                Sheet sheet = wb.getSheetAt(s);
                int last = Math.min(sheet.getLastRowNum(), HEADER_SCAN + MAX_ROWS + 2);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>();
                    if (row != null) {
                        int cols = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLS);
                        for (int c = 0; c < cols; c++) cells.add(text(row.getCell(c)));
                    }
                    rows.add(cells);
                }
                // объединённые ячейки шапки: текст — во все ячейки области («Цена, тг» над «за ед. | сумма»)
                for (CellRangeAddress m : sheet.getMergedRegions()) {
                    if (m.getFirstRow() > HEADER_SCAN + 1 || m.getFirstRow() >= rows.size()) continue;
                    String v = get(rows, m.getFirstRow(), m.getFirstColumn());
                    for (int r = m.getFirstRow(); r <= Math.min(m.getLastRow(), rows.size() - 1); r++) {
                        for (int c = m.getFirstColumn(); c <= Math.min(m.getLastColumn(), MAX_COLS - 1); c++) set(rows, r, c, v);
                    }
                }
                out.add(new Grid(sheet.getSheetName(), rows, 1, false));
            }
        }
        return out;
    }

    static String text(Cell cell) {
        if (cell == null) return "";
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (t) {
            case STRING -> clean(cell.getStringCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    // ---- Word -----------------------------------------------------------------------------------------------------

    static boolean ole2(byte[] b) {
        return b.length >= 4 && (b[0] & 0xff) == 0xD0 && (b[1] & 0xff) == 0xCF && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0xE0;
    }

    static List<Grid> docx(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            int n = 0;
            for (XWPFTable t : doc.getTables()) {
                n++;
                List<List<String>> rows = new ArrayList<>();
                for (XWPFTableRow row : t.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String v = clean(cell.getText());
                        int span = span(cell);
                        for (int i = 0; i < span && cells.size() < MAX_COLS; i++) cells.add(v);   // объединённая по ширине — текст и в соседние
                    }
                    rows.add(cells);
                    if (rows.size() > WORD_HEADER_SCAN + MAX_ROWS) break;
                }
                out.add(new Grid("таблица " + n, rows, 1, true));
            }
        }
        return out;
    }

    private static int span(XWPFTableCell cell) {
        CTTcPr pr = cell.getCTTc().getTcPr();
        if (pr == null || pr.getGridSpan() == null || pr.getGridSpan().getVal() == null) return 1;
        return Math.max(1, Math.min(MAX_COLS, pr.getGridSpan().getVal().intValue()));
    }

    static List<Grid> doc(byte[] content) throws IOException {
        List<Grid> out = new ArrayList<>();
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
            TableIterator it = new TableIterator(doc.getRange());
            int n = 0;
            while (it.hasNext()) {
                Table t = it.next();
                n++;
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r < t.numRows() && rows.size() <= WORD_HEADER_SCAN + MAX_ROWS; r++) {
                    TableRow row = t.getRow(r);
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.numCells() && c < MAX_COLS; c++) cells.add(clean(row.getCell(c).text()));
                    rows.add(cells);
                }
                out.add(new Grid("таблица " + n, rows, 1, true));
            }
        }
        return out;
    }

    private static int width(Grid g) {
        return g.rows().stream().mapToInt(List::size).max().orElse(0);
    }

    private static Grid glue(Grid a, Grid b) {
        List<List<String>> rows = new ArrayList<>(a.rows());
        rows.addAll(b.rows());
        return new Grid(a.source(), rows, a.firstRow(), a.word());
    }

    // ---- Таблица из сетки -----------------------------------------------------------------------------------------

    static Optional<ReplyTable> table(Grid g, Map<String, LineField> learned) {
        List<List<String>> rows = g.rows();
        int scan = Math.min(rows.size(), g.word() ? WORD_HEADER_SCAN : HEADER_SCAN);
        int best = -1, bestScore = 0;
        for (int r = 0; r < scan; r++) {
            int score = headerScore(roles(rows.get(r), learned));
            if (score > bestScore) {
                best = r;
                bestScore = score;
            }
        }
        if (best < 0) return Optional.empty();
        List<String> header = new ArrayList<>(rows.get(best));
        List<LineField> roles = roles(header, learned);
        int data = best + 1;
        if (data + 1 < rows.size() && subHeader(rows.get(best), rows.get(data))) {   // шапка в две строки
            List<String> sub = rows.get(data);
            int w = Math.max(header.size(), sub.size());
            List<String> combined = new ArrayList<>(w);
            List<LineField> combinedRoles = new ArrayList<>(w);
            for (int c = 0; c < w; c++) {
                String top = c < header.size() ? header.get(c) : "", low = c < sub.size() ? sub.get(c) : "";
                String text = top.equals(low) || low.isBlank() ? top : (top + " " + low).trim();
                LineField lr = low.isBlank() || top.equals(low) ? LineField.IGNORE : HeaderRoles.role(low, learned);
                combined.add(text);
                combinedRoles.add(lr != LineField.IGNORE ? lr : HeaderRoles.role(text, learned));
            }
            if (headerScore(combinedRoles) >= bestScore) {
                header = combined;
                roles = combinedRoles;
                data = best + 2;
            }
        }
        int width = header.size();
        for (int r = data; r < rows.size(); r++) width = Math.max(width, rows.get(r).size());
        width = Math.min(width, MAX_COLS);

        List<ReplyTable.Row> out = new ArrayList<>();
        for (int r = data; r < rows.size() && out.size() < MAX_ROWS; r++) {
            List<String> cells = pad(rows.get(r), width);
            String first = cells.stream().filter(s -> !s.isBlank()).findFirst().orElse("");
            if (first.isEmpty()) continue;
            if (TOTAL.matcher(first).find()) break;
            out.add(new ReplyTable.Row(g.firstRow() + r, cells));
        }
        List<ReplyTable.Column> columns = new ArrayList<>(width);
        for (int c = 0; c < width; c++) {
            String h = c < header.size() ? header.get(c) : "";
            LineField role = c < roles.size() ? roles.get(c) : LineField.IGNORE;
            if (h.isBlank() && role == LineField.IGNORE && textColumn(out, c)) role = LineField.NOTE;   // решение плана 11
            columns.add(new ReplyTable.Column(c, h, role));
        }
        return Optional.of(new ReplyTable(g.source(), g.firstRow() + best, bestScore, currency(columns, out), columns, out));
    }

    private static List<LineField> roles(List<String> row, Map<String, LineField> learned) {
        List<LineField> out = new ArrayList<>(row.size());
        for (String h : row) out.add(HeaderRoles.role(h, learned));
        return out;
    }

    /** Узнанные заголовки; 0 — не шапка (нет наименования или нет ни цены, ни суммы). */
    static int headerScore(List<LineField> roles) {
        boolean name = roles.contains(LineField.NAME);
        boolean money = roles.contains(LineField.PRICE) || roles.contains(LineField.SUM);
        if (!name || !money) return 0;
        return (int) roles.stream().filter(r -> r != LineField.IGNORE).count();
    }

    /**
     * Вторая строка шапки: без цифр, и каждая её непустая ячейка стоит под объединённой ячейкой верхней строки (тот же
     * текст у соседа слева или справа), под пустой или повторяет верхнюю (объединение по вертикали). Иначе это первая
     * строка данных без цифр («Ширма | шт | нет в наличии») — её нельзя съесть шапкой.
     */
    private static boolean subHeader(List<String> top, List<String> low) {
        boolean any = false;
        for (int c = 0; c < low.size(); c++) {
            String v = low.get(c);
            if (v.isBlank()) continue;
            if (v.chars().anyMatch(Character::isDigit)) return false;
            String t = c < top.size() ? top.get(c) : "";
            boolean underMerged = t.isBlank() || t.equals(v)
                    || (c > 0 && c - 1 < top.size() && t.equals(top.get(c - 1)))
                    || (c + 1 < top.size() && t.equals(top.get(c + 1)));
            if (!underMerged) return false;
            any = true;
        }
        return any;
    }

    private static boolean startsWithHeader(Grid g, Map<String, LineField> learned) {
        return !g.rows().isEmpty() && headerScore(roles(g.rows().get(0), learned)) > 0;
    }

    private static boolean textColumn(List<ReplyTable.Row> rows, int c) {
        for (ReplyTable.Row r : rows) {
            String v = r.cells().get(c);
            if (!v.isBlank() && v.chars().anyMatch(Character::isLetter)) return true;
        }
        return false;
    }

    /** Валюта таблицы: заголовки цены и суммы → колонка «Валюта» → сами цены; не названа — null. */
    private static String currency(List<ReplyTable.Column> columns, List<ReplyTable.Row> rows) {
        for (ReplyTable.Column c : columns) {
            if (c.role() == LineField.PRICE || c.role() == LineField.SUM) {
                String cur = MoneyText.currency(c.header());
                if (cur != null) return cur;
            }
        }
        for (ReplyTable.Column c : columns) {
            if (c.role() != LineField.CURRENCY && c.role() != LineField.PRICE && c.role() != LineField.SUM) continue;
            for (ReplyTable.Row r : rows) {
                String cur = MoneyText.currency(r.cells().get(c.index()));
                if (cur != null) return cur;
            }
        }
        return null;
    }

    private static List<String> pad(List<String> row, int width) {
        List<String> out = new ArrayList<>(width);
        for (int c = 0; c < width; c++) out.add(c < row.size() ? row.get(c) : "");
        return out;
    }

    private static String get(List<List<String>> rows, int r, int c) {
        return r < rows.size() && c < rows.get(r).size() ? rows.get(r).get(c) : "";
    }

    private static void set(List<List<String>> rows, int r, int c, String v) {
        List<String> row = rows.get(r);
        while (row.size() <= c) row.add("");
        row.set(c, v);
    }

    /** Переводы строк, табуляция, концы ячеек Word (U+0007) — пробелом; U+0000 вон (PostgreSQL его не хранит). */
    static String clean(String s) {
        if (s == null) return "";
        return s.replace("\0", "").replaceAll("[\\r\\n\\t\\u000B\\u0007]+", " ").replaceAll(" {2,}", " ").trim();
    }
}
```

- [ ] **Step 7: Прогнать**

Run: `./gradlew test --tests '*ReplyTableReaderTest' --tests '*MoneyTextTest' --tests '*HeaderRolesTest'`
Expected: PASS. Если `optics.xls` / `help.xls` дают другие номера строк — сверить с выводом xlrd из шага 1 (номер строки листа = индекс + 1) и поправить ожидание, а не правило. Мутации (по одной, откат копией файла — CLAUDE.md §14): (а) убрать склейку шапки в две строки — красный `docx_twoRowHeader_mergedPrice`; (б) поставить правило «единиц» раньше «наимен» — красный `heuristics` («Наименование (описание), единица измерения»); (в) убрать проверку `TOTAL` — красный `letterLineWithPriceWordIsNotHeader_andTotalStops`; (г) в `subHeader` убрать условие «под объединённой ячейкой» — красный `digitlessFirstRowStaysData`; (д) склеивать таблицы Word без проверки `startsWithHeader` — красный `docx_nextTableWithOwnHeaderIsNotGlued`.

- [ ] **Step 8: Commit**

```bash
git add build.gradle src/main/java/com/vladoose/nir/service/supplierreply src/test/java/com/vladoose/nir/service/supplierreply src/test/resources/supplier-reply
git commit -m "feat(supplier-reply): таблица КП поставщика из Excel и Word — шапка, роли, числа, валюта

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Строки файла → позиции (`ReplyRowValues`, `ReplyMatcher`)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/supplierreply/ReplyRowValues.java`, `ReplyMatcher.java`
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/ReplyRowValuesTest.java`, `ReplyMatcherTest.java`, `SupplierReplyGateTest.java`
- Create: `src/test/resources/supplier-reply/golden.txt`

**Interfaces:**
- Consumes: `ReplyTable` (задача 1), `MoneyText` (задача 2).
- Produces: `record ReplyRowValues(int fileRow, String name, String article, String brand, String number, MoneyText.Qty quantity, String unit, BigDecimal price, boolean priceComputed, boolean priceRefused, BigDecimal sum, String vat, String currency, String note)` с `static List<ReplyRowValues> of(ReplyTable t)`, `String matchText()`, `String displayText()`, `boolean participates()`; `ReplyMatcher` с константами `AUTO_MIN`, `AUTO_MARGIN`, `SUGGEST_MIN`, `enum Kind { AUTO, SUGGEST }`, `record Position(long itemId, int ordinal, String text, Integer quantity)`, `record Row(int fileRow, int order, String text, BigDecimal quantity)`, `record Match(long itemId, int fileRow, double score, Kind kind)`, `static List<Match> match(List<Position>, List<Row>)`, `static double score(String positionText, String rowText, List<Row> rows)`.

- [ ] **Step 1: Тесты — написать (красные)**

`ReplyRowValuesTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.ReplyRowValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyRowValuesTest {

    static ReplyTable t(List<String> headers, List<LineField> roles, String currency, List<List<String>> rows) {
        List<ReplyTable.Column> cols = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) cols.add(new ReplyTable.Column(i, headers.get(i), roles.get(i)));
        List<ReplyTable.Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(new ReplyTable.Row(10 + i, rows.get(i)));
        return new ReplyTable("Лист1", 9, 3, currency, cols, out);
    }

    @Test
    void discountPriceWins_unitFromQuantity() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Количество", "Цена", "Цена со скидкой"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.PRICE), "RUB",
                List.of(List.of("Скальпель", "1 уп", "7200", "4700")))).get(0);
        assertThat(v.price()).isEqualByComparingTo("4700");
        assertThat(v.unit()).isEqualTo("уп");
        assertThat(v.quantity().value()).isEqualByComparingTo("1");
        assertThat(v.currency()).isEqualTo("RUB");
    }

    /** «ХЭЛП»: в ячейке цены «нет», в сумме «15» — отказ, а не цена 15 (Global Constraints). */
    @Test
    void textInPriceCell_isRefusal_notComputedFromSum() {
        ReplyRowValues v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена", "Сумма", ""),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM, LineField.NOTE), null,
                List.of(List.of("Распатор", "1", "нет", "15", "Заявлены, но отсутствуют")))).get(0);
        assertThat(v.price()).isNull();
        assertThat(v.priceRefused()).isTrue();
        assertThat(v.priceComputed()).isFalse();
        assertThat(v.note()).isEqualTo("Заявлены, но отсутствуют");
        assertThat(v.participates()).isTrue();
    }

    @Test
    void blankPriceCell_computedFromSum_andNoPriceColumnUsesSum() {
        List<ReplyRowValues> a = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Цена", "Сумма"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.PRICE, LineField.SUM), "KZT",
                List.of(List.of("Кушетка", "2 шт", "", "190 000"))));
        assertThat(a.get(0).price()).isEqualByComparingTo("95000");
        assertThat(a.get(0).priceComputed()).isTrue();

        List<ReplyRowValues> b = ReplyRowValues.of(t(List.of("Наименование", "Стоимость"),
                List.of(LineField.NAME, LineField.SUM), "KZT", List.of(List.of("Ширма", "30 000"))));
        assertThat(b.get(0).price()).isEqualByComparingTo("30000");
    }

    @Test
    void zeroSumIsNoPrice_andSectionRowDoesNotParticipate() {
        List<ReplyRowValues> v = ReplyRowValues.of(t(List.of("Наименование", "Кол-во", "Сумма"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.SUM), null,
                List.of(List.of("Спалинты", "1", "0"), List.of("Основные комплектующие:", "", ""))));
        assertThat(v.get(0).price()).isNull();
        assertThat(v.get(1).participates()).isFalse();
    }
}
```

`ReplyMatcherTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.service.supplierreply.ReplyMatcher;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Kind;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Match;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Position;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyMatcherTest {

    static List<Position> positions(String... names) {
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) out.add(new Position(100 + i, i + 1, names[i], 1));
        return out;
    }

    static List<Row> rows(String... texts) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) out.add(new Row(20 + i, i, texts[i], null));
        return out;
    }

    static Match of(List<Match> ms, long itemId) {
        return ms.stream().filter(m -> m.itemId() == itemId).findFirst().orElse(null);
    }

    @Test
    void reorderedAndRenamed_codesDecide() {
        List<Match> ms = ReplyMatcher.match(
                positions("Скальпель офтальмологический MSL24", "Скальпель офтальмологический MST15", "Материал шовный нейлон 2056"),
                rows("Материал шовный стерильный с иглами: нейлон, арт. 2056, 1 уп",
                        "Скальпели офтальмологические: Скальпель MST15 15°",
                        "Скальпели офтальмологические: Скальпель MSL24 2,4 мм",
                        "Доставка до склада"));
        assertThat(of(ms, 100)).extracting(Match::fileRow, Match::kind).containsExactly(22, Kind.AUTO);
        assertThat(of(ms, 101)).extracting(Match::fileRow, Match::kind).containsExactly(21, Kind.AUTO);
        assertThat(of(ms, 102)).extracting(Match::fileRow, Match::kind).containsExactly(20, Kind.AUTO);
        assertThat(ms).noneMatch(m -> m.fileRow() == 23);
    }

    @Test
    void twinPositionsAreSuggestionsOnly() {
        List<Match> ms = ReplyMatcher.match(positions("Набор реагентов", "Набор реагентов"),
                rows("Набор реагентов для биохимического анализатора", "Набор реагентов (контроль)"));
        assertThat(ms).isNotEmpty().allMatch(m -> m.kind() == Kind.SUGGEST);
    }

    @Test
    void singlePositionSingleRow_auto() {
        List<Match> ms = ReplyMatcher.match(positions("Кушетка"), rows("Кушетка смотровая КС-1"));
        assertThat(ms).singleElement().extracting(Match::kind).isEqualTo(Kind.AUTO);
    }

    @Test
    void latinLookalikesInCode() {
        List<Match> ms = ReplyMatcher.match(positions("Монитор пациента BeneVision N12"),
                rows("Монитор пациента ВеnеVision N12 (Mindray)"));
        assertThat(ms).singleElement().extracting(Match::kind).isEqualTo(Kind.AUTO);
    }

    @Test
    void unrelatedNames_noMatch() {
        assertThat(ReplyMatcher.match(positions("Тонометр"), rows("Измеритель артериального давления OMRON M2"))).isEmpty();
    }

    /** «ХЭЛП»: кюретки № 5 и № 2 различаются только номером — позиция № 5 встаёт к своей строке. */
    @Test
    void siblingNumberPicksRightRow() {
        List<Match> ms = ReplyMatcher.match(positions("Кюретка аденоидная изогнутая Negus № 5"),
                rows("Кюретка аденоидная изогнутая (Negus) номер 2 (КА)", "Кюретка аденоидная изогнутая (Negus) номер 5 (КА)"));
        assertThat(ms).singleElement().extracting(Match::fileRow, Match::kind).containsExactly(21, Kind.AUTO);
    }

    /** В файле только соседний размер — не подставлять (иначе позиция «60 мм» получила бы цену «40 мм»). */
    @Test
    void onlySiblingInFile_isNotAuto() {
        List<Match> ms = ReplyMatcher.match(positions("Зеркало носовое Killian 60 мм"),
                rows("Зеркало носовое по KILLIAN длина крыльев 40 мм"));
        assertThat(ms).noneMatch(m -> m.kind() == Kind.AUTO);
    }

    @Test
    void missingPosition_hasNoMatch() {
        List<Match> ms = ReplyMatcher.match(positions("Кушетка смотровая", "Ширма двухсекционная"), rows("Кушетка смотровая КМС-01"));
        assertThat(of(ms, 101)).isNull();
    }
}
```

Run: `./gradlew test --tests '*ReplyRowValuesTest' --tests '*ReplyMatcherTest'` → FAIL.

- [ ] **Step 2: `ReplyRowValues`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Значения строки таблицы по ролям колонок (спека 2026-10-15 §3.2–3.4). Цена — из колонки цены с наибольшим приоритетом
 * («со скидкой» > «с НДС» > просто цена > «без НДС»); ячейка цены ПУСТАЯ — цена = сумма / количество (количества
 * нет — 1), с пометкой priceComputed; в ячейке цены текст («нет», «по запросу») или 0 — priceRefused, из суммы не
 * считается. Колонок цены нет вовсе — цена из суммы.
 */
public record ReplyRowValues(int fileRow, String name, String article, String brand, String number,
                             MoneyText.Qty quantity, String unit, BigDecimal price, boolean priceComputed,
                             boolean priceRefused, BigDecimal sum, String vat, String currency, String note) {

    /** Наименование, артикул и бренд — то, что сравнивается с позицией. */
    public String matchText() {
        return String.join(" ", nonBlank(name, article, brand));
    }

    /** Для людей: наименование, артикул (если его нет в наименовании) и бренд через «·». */
    public String displayText() {
        List<String> parts = new ArrayList<>();
        if (name != null && !name.isBlank()) parts.add(name);
        if (article != null && !article.isBlank() && (name == null || !name.contains(article))) parts.add(article);
        if (brand != null && !brand.isBlank()) parts.add(brand);
        return String.join(" · ", parts);
    }

    /** Строка участвует в сопоставлении: есть цена, явный отказ или примечание. */
    public boolean participates() {
        return (price != null || priceRefused || (note != null && !note.isBlank())) && !matchText().isBlank();
    }

    public static List<ReplyRowValues> of(ReplyTable t) {
        Map<LineField, List<Integer>> by = new EnumMap<>(LineField.class);
        for (ReplyTable.Column c : t.columns()) by.computeIfAbsent(c.role(), k -> new ArrayList<>()).add(c.index());
        List<Integer> prices = sorted(by.getOrDefault(LineField.PRICE, List.of()), t);
        List<Integer> sums = sorted(by.getOrDefault(LineField.SUM, List.of()), t);
        List<ReplyRowValues> out = new ArrayList<>(t.rows().size());
        for (ReplyTable.Row r : t.rows()) {
            List<String> cells = r.cells();
            String name = join(" ", cells, by.get(LineField.NAME));
            MoneyText.Qty q = MoneyText.quantity(first(cells, by.get(LineField.QUANTITY)));
            String unit = first(cells, by.get(LineField.UNIT));
            if ((unit == null || unit.isBlank()) && q != null) unit = q.unit();
            String sumCell = sums.isEmpty() ? null : cells.get(sums.get(0));
            BigDecimal sum = MoneyText.amount(sumCell);
            if (sum != null && sum.signum() == 0) sum = null;
            String priceCell = prices.isEmpty() ? null : cells.get(prices.get(0));
            BigDecimal price = null;
            boolean computed = false, refused = false;
            if (priceCell != null && !priceCell.isBlank()) {
                price = MoneyText.amount(priceCell);
                if (price == null || price.signum() == 0) {
                    price = null;
                    refused = true;
                }
            } else if (sum != null) {
                BigDecimal n = q == null || q.value().signum() <= 0 ? BigDecimal.ONE : q.value();
                price = sum.divide(n, 2, RoundingMode.HALF_UP);
                computed = true;
            }
            String cur = MoneyText.currency(first(cells, by.get(LineField.CURRENCY)));
            if (cur == null) cur = MoneyText.currency(priceCell);
            if (cur == null) cur = MoneyText.currency(sumCell);
            if (cur == null) cur = t.currency();
            out.add(new ReplyRowValues(r.fileRow(), blankToNull(name), blankToNull(first(cells, by.get(LineField.ARTICLE))),
                    blankToNull(first(cells, by.get(LineField.MANUFACT))), blankToNull(first(cells, by.get(LineField.NUMBER))),
                    q, blankToNull(unit), price, computed, refused, sum, blankToNull(first(cells, by.get(LineField.VAT))),
                    cur, blankToNull(join("; ", cells, by.get(LineField.NOTE)))));
        }
        return out;
    }

    /** Приоритет колонки цены / суммы по заголовку: «со скидкой», «итог», «с учётом» — 3; «с НДС» — 2; прочее — 1; «без НДС» — 0. */
    static int priority(String header) {
        String h = header == null ? "" : header.toLowerCase(Locale.ROOT);
        if (h.contains("скидк") || h.contains("итог") || h.contains("с учет") || h.contains("с учёт")) return 3;
        if (h.contains("без ндс")) return 0;
        if (h.contains("ндс")) return 2;
        return 1;
    }

    private static List<Integer> sorted(List<Integer> idx, ReplyTable t) {
        List<Integer> out = new ArrayList<>(idx);
        out.sort(Comparator.comparingInt((Integer i) -> -priority(t.columns().get(i).header())));
        return out;
    }

    private static String first(List<String> cells, List<Integer> idx) {
        if (idx == null) return null;
        for (int i : idx) if (i < cells.size() && !cells.get(i).isBlank()) return cells.get(i);
        return null;
    }

    private static String join(String sep, List<String> cells, List<Integer> idx) {
        if (idx == null) return null;
        List<String> parts = new ArrayList<>();
        for (int i : idx) if (i < cells.size() && !cells.get(i).isBlank()) parts.add(cells.get(i));
        return String.join(sep, parts);
    }

    private static List<String> nonBlank(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
```

- [ ] **Step 3: `ReplyMatcher`**

```java
package com.vladoose.nir.service.supplierreply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Строки файла → позиции запроса (спека 2026-10-15 §3.3). Слова: нижний регистр, «ё» → «е», латинские двойники
 * кириллицы складываются; слово от 3 букв или с цифрами; совпадение — целиком или по первым 5 буквам. Вес слова —
 * редкость внутри файла, у кодов моделей («MSL24», «ЭК3ТЦ-3/6-04») — втрое. Оценка = 0,75 × доля веса слов позиции,
 * найденных в строке, + 0,25 × доля веса слов строки, найденных в позиции. Пары разбираются от лучшей; одна строка —
 * одной позиции. Подставить — оценка ≥ 0,60 и отрыв от второй кандидатуры ≥ 0,15 и у позиции, и у строки; иначе
 * подсказка (≥ 0,35). Разные числа у обеих сторон («№ 5» и «№ 2», «60 мм» и «40 мм») — оценка ×0,5: подставить
 * нельзя, подсказать можно (решение плана 16). Принцип проекта: лучше не найти, чем перепутать.
 */
public final class ReplyMatcher {

    public static final double AUTO_MIN = 0.60;
    public static final double AUTO_MARGIN = 0.15;
    public static final double SUGGEST_MIN = 0.35;
    static final double CODE_WEIGHT = 3.0;
    /** Не больше 0,5 < AUTO_MIN: пара с разными числами уверенной не бывает. */
    static final double CONFLICT_FACTOR = 0.5;
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");

    public enum Kind { AUTO, SUGGEST }

    public record Position(long itemId, int ordinal, String text, Integer quantity) {}

    public record Row(int fileRow, int order, String text, BigDecimal quantity) {}

    public record Match(long itemId, int fileRow, double score, Kind kind) {}

    private ReplyMatcher() {}

    public static List<Match> match(List<Position> positions, List<Row> rows) {
        if (positions.isEmpty() || rows.isEmpty()) return List.of();
        Weights w = Weights.of(rows);
        double[][] s = new double[positions.size()][rows.size()];
        record Pair(int p, int r, double score) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = 0; j < rows.size(); j++) {
                s[i][j] = pairScore(w, positions.get(i).text(), rows.get(j).text());
                if (s[i][j] >= SUGGEST_MIN) pairs.add(new Pair(i, j, s[i][j]));
            }
        }
        pairs.sort(Comparator.comparingDouble((Pair x) -> -x.score())
                .thenComparingInt(x -> Math.abs(positions.get(x.p()).ordinal() - 1 - rows.get(x.r()).order()))
                .thenComparing(x -> !sameQuantity(positions.get(x.p()), rows.get(x.r()))));
        boolean[] usedP = new boolean[positions.size()], usedR = new boolean[rows.size()];
        List<Match> out = new ArrayList<>();
        for (Pair x : pairs) {
            if (usedP[x.p()] || usedR[x.r()]) continue;
            usedP[x.p()] = true;
            usedR[x.r()] = true;
            double secondP = 0, secondR = 0;
            for (int j = 0; j < rows.size(); j++) if (j != x.r()) secondP = Math.max(secondP, s[x.p()][j]);
            for (int i = 0; i < positions.size(); i++) if (i != x.p()) secondR = Math.max(secondR, s[i][x.r()]);
            boolean auto = x.score() >= AUTO_MIN && x.score() - secondP >= AUTO_MARGIN && x.score() - secondR >= AUTO_MARGIN;
            out.add(new Match(positions.get(x.p()).itemId(), rows.get(x.r()).fileRow(), round(x.score()), auto ? Kind.AUTO : Kind.SUGGEST));
        }
        return out;
    }

    /** Оценка пары вне подбора — строка выбрана вручную: экран пишет «названия похожи слабо». */
    public static double score(String positionText, String rowText, List<Row> rows) {
        return round(pairScore(Weights.of(rows), positionText, rowText));
    }

    private static double pairScore(Weights w, String positionText, String rowText) {
        double v = w.score(positionText, rowText);
        return conflicting(positionText, rowText) ? v * CONFLICT_FACTOR : v;
    }

    /** Числа текста — номера, размеры, цифры кодов («№ 5», «60 мм», «MSL24» → 24); ведущие нули не в счёт. */
    static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        java.util.regex.Matcher m = DIGITS.matcher(text);
        while (m.find()) out.add(m.group().replaceFirst("^0+(?=\\d)", ""));
        return out;
    }

    /** У позиции есть число, которого нет в строке, а у строки — которого нет у позиции: соседняя модель или размер. */
    static boolean conflicting(String positionText, String rowText) {
        Set<String> p = numbers(positionText), r = numbers(rowText);
        return !r.containsAll(p) && !p.containsAll(r);
    }

    private static boolean sameQuantity(Position p, Row r) {
        return p.quantity() != null && r.quantity() != null && r.quantity().compareTo(BigDecimal.valueOf(p.quantity())) == 0;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    record Token(String stem, boolean code) {}

    static List<Token> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String low = fold(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        Set<String> seen = new LinkedHashSet<>();
        List<Token> out = new ArrayList<>();
        for (String chunk : low.split("\\s+")) {
            String joined = chunk.replaceAll("[^\\p{L}\\p{N}]", "");
            if (joined.length() >= 3 && joined.chars().anyMatch(Character::isDigit) && seen.add(joined)) out.add(new Token(joined, true));
            for (String word : chunk.split("[^\\p{L}\\p{N}]+")) {
                boolean digit = word.chars().anyMatch(Character::isDigit);
                if (word.length() < (digit ? 2 : 3)) continue;
                String stem = digit ? word : (word.length() > 5 ? word.substring(0, 5) : word);
                if (seen.add(stem)) out.add(new Token(stem, digit));
            }
        }
        return out;
    }

    /** Латинские двойники кириллицы — в кириллицу (одинаково в обе стороны сравнения). */
    static String fold(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            b.append(switch (c) {
                case 'a' -> 'а'; case 'b' -> 'в'; case 'c' -> 'с'; case 'e' -> 'е'; case 'h' -> 'н'; case 'k' -> 'к';
                case 'm' -> 'м'; case 'o' -> 'о'; case 'p' -> 'р'; case 't' -> 'т'; case 'x' -> 'х'; case 'y' -> 'у';
                default -> c;
            });
        }
        return b.toString();
    }

    static final class Weights {
        private final Map<String, Integer> df = new HashMap<>();
        private final int n;

        private Weights(int n) {
            this.n = n;
        }

        static Weights of(List<Row> rows) {
            Weights w = new Weights(rows.size());
            for (Row r : rows) {
                Set<String> stems = new HashSet<>();
                for (Token t : tokens(r.text())) stems.add(t.stem());
                for (String st : stems) w.df.merge(st, 1, Integer::sum);
            }
            return w;
        }

        double weight(Token t) {
            int d = df.getOrDefault(t.stem(), 0);
            double base = Math.log(1.0 + n / (d == 0 ? 0.5 : d));
            return t.code() ? base * CODE_WEIGHT : base;
        }

        double score(String positionText, String rowText) {
            List<Token> tp = tokens(positionText), tr = tokens(rowText);
            if (tp.isEmpty() || tr.isEmpty()) return 0;
            Set<String> sp = new HashSet<>(), sr = new HashSet<>();
            tp.forEach(t -> sp.add(t.stem()));
            tr.forEach(t -> sr.add(t.stem()));
            double num1 = 0, den1 = 0, num2 = 0, den2 = 0;
            for (Token t : tp) {
                double wt = weight(t);
                den1 += wt;
                if (sr.contains(t.stem())) num1 += wt;
            }
            for (Token t : tr) {
                double wt = weight(t);
                den2 += wt;
                if (sp.contains(t.stem())) num2 += wt;
            }
            return 0.75 * (num1 / den1) + 0.25 * (num2 / den2);
        }
    }
}
```

- [ ] **Step 4: Гейт на настоящих КП — `golden.txt` и `SupplierReplyGateTest`**

`src/test/resources/supplier-reply/golden.txt` (номера строк — строки листа обезличенных копий из задачи 2; сверены по исходникам):

```text
# Сверка сопоставления на настоящих КП (спека 2026-10-15 §7, решение плана 18).
# файл | позиция, как в нашем запросе | кол-во | строка файла («-» — в файле нет)
# «Перепутано» = 0: уверенная пара не с той строкой или там, где строки нет. Новые настоящие КП (в т. ч. казахстанские,
# спека §11) — обезличенной копией рядом и строками сюда; номер строки сверять глазами по файлу, а не по выдаче.
optics.xls | Скальпель офтальмологический для тоннельного разреза MSL24 | 2 | 16
optics.xls | Скальпель офтальмологический стандартный MST15 | 2 | 17
optics.xls | Материал шовный нейлон с иглой 2056 | 1 | 18
optics.xls | Материал шовный нейлон с иглой 1406 | 1 | 19
optics.xls | Материал шовный нейлон с иглой 2002 | 1 | 20
optics.xls | Скальпель офтальмологический угловой MSA30 | 1 | -
help.xls | Элеватор для тонзиллэктомии Henke для детей | 1 | 13
help.xls | Элеватор для тонзиллэктомии Henke для взрослых | 1 | 14
help.xls | Щипцы Colver для захватывания нёбных миндалин изогнутые | 1 | 15
help.xls | Трубка отсасывающая Yankauer диаметр 6 мм | 1 | 16
help.xls | Пинцет биполярный штыковидный прямой 195 мм | 1 | 18
help.xls | Пинцет биполярный изогнутый 195 мм | 1 | 31
help.xls | Кюретка аденоидная изогнутая Negus № 5 | 1 | 22
help.xls | Кюретка аденоидная изогнутая Negus № 2 | 1 | 23
help.xls | Кюретка аденоидная по Козлову-Карпову № 5 | 1 | 24
help.xls | Кюретка аденоидная по Козлову-Карпову № 2 | 1 | 25
help.xls | Зеркало носовое Killian 40 мм | 1 | 43
help.xls | Зеркало носовое Killian 60 мм | 1 | 44
help.xls | Зеркало носовое Killian 90 мм | 1 | 45
help.xls | Роторасширитель Dingman титановый | 1 | 27
help.xls | Распатор двухсторонний Rodger 195 мм | 1 | 64
help.xls | Игла парацентезная | 1 | 71
help.xls | Молоток металлический 360 г | 1 | 51
help.xls | Молоток металлический с пластиковой накладкой 240 г | 1 | 52
help.xls | Ножницы хирургические с одним острым концом 140 мм | 1 | 74
help.xls | Ларингоскоп с набором клинков | 1 | -
help.xls | Кюретка ушная | 1 | -
```

`src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplyGateTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyTable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт сопоставления на настоящих КП поставщиков (спека 2026-10-15 §7): «перепутано» = 0 — уверенная пара не с той
 * строкой или там, где строки нет; «подставлено верно» — не меньше замера. Храповик: упало — разбираться, а не
 * понижать APPLIED_MIN и не трогать пороги ради зелёного (как у реестра, CLAUDE.md §8 «Гейт — RegistryMatchQualityTest»).
 */
class SupplierReplyGateTest {

    /** Замер на golden.txt при реализации (задача 3, шаг 5): верно подставленных цен. */
    static final int APPLIED_MIN = 0;

    record Golden(String file, String position, int quantity, Integer row) {}

    @Test
    void nothingMixedUp_appliedNotBelowMeasure() throws Exception {
        Map<String, List<Golden>> byFile = new LinkedHashMap<>();
        for (Golden g : golden()) byFile.computeIfAbsent(g.file(), k -> new ArrayList<>()).add(g);
        int applied = 0, suggested = 0, priced = 0;
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, List<Golden>> e : byFile.entrySet()) {
            ReplyTable t = ReplyTableReader.read(ReplyTableReaderTest.res(e.getKey()), ReplyFileKind.EXCEL, Map.of()).orElseThrow();
            Map<Integer, ReplyRowValues> byRow = new HashMap<>();
            List<ReplyMatcher.Row> rows = new ArrayList<>();
            int order = 0;
            for (ReplyRowValues v : ReplyRowValues.of(t)) {
                byRow.put(v.fileRow(), v);
                if (v.participates()) {
                    rows.add(new ReplyMatcher.Row(v.fileRow(), order++, v.matchText(), v.quantity() == null ? null : v.quantity().value()));
                }
            }
            List<Golden> gs = e.getValue();
            List<ReplyMatcher.Position> positions = new ArrayList<>();
            for (int i = 0; i < gs.size(); i++) {
                Golden g = gs.get(i);
                positions.add(new ReplyMatcher.Position(i, i + 1, g.position(), g.quantity()));
                if (g.row() != null && byRow.containsKey(g.row()) && byRow.get(g.row()).price() != null) priced++;
            }
            for (ReplyMatcher.Match m : ReplyMatcher.match(positions, rows)) {
                Golden g = gs.get((int) m.itemId());
                if (m.kind() == ReplyMatcher.Kind.SUGGEST) {
                    suggested++;
                } else if (g.row() == null || g.row() != m.fileRow()) {
                    wrong.add(e.getKey() + ": «" + g.position() + "» → строка " + m.fileRow()
                            + " (ждали " + (g.row() == null ? "«нет в файле»" : "строку " + g.row()) + ")");
                } else if (byRow.get(m.fileRow()).price() != null) {
                    applied++;
                }
            }
        }
        System.out.printf("REPORT supplier-reply gate: applied=%d of priced=%d, suggested=%d, wrong=%d%n",
                applied, priced, suggested, wrong.size());
        assertThat(wrong).as("перепутано").isEmpty();
        assertThat(applied).as("подставлено верно").isGreaterThanOrEqualTo(APPLIED_MIN);
    }

    static List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                SupplierReplyGateTest.class.getResourceAsStream("/supplier-reply/golden.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|");
                String row = f[3].trim();
                out.add(new Golden(f[0].trim(), f[1].trim(), Integer.parseInt(f[2].trim()), row.equals("-") ? null : Integer.valueOf(row)));
            }
        }
        return out;
    }
}
```

- [ ] **Step 5: Прогнать и записать замер**

Run: `./gradlew test --tests '*ReplyRowValuesTest' --tests '*ReplyMatcherTest' --tests '*SupplierReplyGateTest' -i | grep -E "REPORT|FAILED|PASSED"`
Expected: PASS; строка `REPORT supplier-reply gate: applied=… of priced=22, …, wrong=0`. Ожидание по прикидке — подставлено около 20 из 22 (тесные пары — пинцеты прямой / изогнутый — могут уйти в подсказки, это не ошибка). Число `applied` записать в `APPLIED_MIN` и прогнать снова. `wrong > 0` или `applied` меньше половины `priced` — остановиться и доложить контроллеру с выдачей сопоставления: пороги и веса без согласования не трогать.

Если `reorderedAndRenamed_codesDecide` даёт SUGGEST у одной из пар — проверить оценку `score(...)` (вывести в тесте) и отрыв: правило не трогать, а убедиться, что код модели попадает в токены обеих сторон (`tokens`). Мутации (по одной): (а) убрать проверку отрыва (`AUTO_MARGIN`) — красный `twinPositionsAreSuggestionsOnly`; (б) `CONFLICT_FACTOR = 1.0` — красные `siblingNumberPicksRightRow` / `onlySiblingInFile_isNotAuto` и, вероятно, гейт; (в) `CODE_WEIGHT = 1.0` — красный `reorderedAndRenamed_codesDecide` (проверить; если зелёный — записать в леджер, что веса кодов этим набором не закреплены, и добавить пару с одинаковыми словами и разными кодами).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/supplierreply src/test/java/com/vladoose/nir/service/supplierreply src/test/resources/supplier-reply/golden.txt
git commit -m "feat(supplier-reply): строки файла → позиции запроса — значения строки и сопоставление

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Файлы ответа и подстановка (`SupplierReplyFiles`, `SupplierReplyService`)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/supplierreply/HeaderDictionary.java`, `PreparedFile.java`, `ReplyPdfPages.java`, `SupplierReplyFiles.java`, `SupplierReplyService.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/ReplyColumnRole.java`
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplyFilesTest.java`, `SupplierReplyServiceTest.java`, `src/test/java/com/vladoose/nir/service/supplierreply/ReplyFixtures.java`

**Interfaces:**
- Consumes: всё из задач 1–3; `PrivateRequestImportService.learn(List<ColumnMapping>)`; `MailFile`, `ParsedMail`, `KpToken.parse(String) → Optional<Long>`.
- Produces: `record PreparedFile(String name, String mimeType, Long size, byte[] bytes, String sha256, ReplyFileKind kind, ReplyParseStatus status, String note, ReplyTable table, Integer pageCount)`; `SupplierReplyFiles.prepareFor(ParsedMail) → List<PreparedFile>`, `prepare(String name, String mime, byte[] bytes) → PreparedFile`, `static fileName(String)`, `static ooxmlTooBig(byte[], long)`, `PARSE_MAX`, `XML_MAX`; `ReplyPdfPages.pageCount(byte[])`, `page(byte[] pdf, int index) → byte[] PNG`, `MAX_PAGES`; `SupplierReplyService`: `record ReplyOutcome(List<Long> fileIds, String fileName, String parsedName, int applied, int positions, int manualKept, String foreignCurrency, boolean viewOnly, boolean image, String unreadable, boolean closed)`, `record ApplyResult(int applied, int positions, int manualKept, String foreignCurrency)`, `ingest(PriceRequest, List<PreparedFile>, String uploadedBy) → ReplyOutcome`, `linkInbound(Collection<Long>, Long)`, `static markResponded(PriceRequest, String displayText)`, `guard(Long prId) → PriceRequest`, `upload(Long prId, String name, String mime, byte[] bytes, String user) → SupplierReplyFile`, `applyFile(Long prId, Long fileId) → ApplyResult`, `setColumns(Long prId, Long fileId, List<ReplyColumnRole>)`, `deleteFile(Long prId, Long fileId)`, `verify(Long prId) → int`, `static positionText(PriceRequestItem)`; `record ReplyColumnRole(int index, LineField role)`.

- [ ] **Step 1: Помощник фикстур тестов**

`src/test/java/com/vladoose/nir/service/supplierreply/ReplyFixtures.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.supplierreply.PreparedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Таблицы и файлы ответа для тестов: без IMAP и без настоящих файлов. */
public final class ReplyFixtures {
    private ReplyFixtures() {}

    /** Таблица «Наименование | Кол-во | Цена» с заданной валютой; строки — пары «наименование, цена». */
    public static ReplyTable table(String currency, String... nameThenPrice) {
        List<ReplyTable.Row> rows = new ArrayList<>();
        for (int i = 0; i < nameThenPrice.length; i += 2) {
            rows.add(new ReplyTable.Row(5 + i / 2, List.of(nameThenPrice[i], "1", nameThenPrice[i + 1])));
        }
        return new ReplyTable("Лист1", 4, 3, currency, List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME),
                new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, currency == null ? "Цена" : "Цена, " + ("RUB".equals(currency) ? "руб." : "тг"), LineField.PRICE)),
                rows);
    }

    public static PreparedFile parsed(String name, ReplyTable t) {
        return new PreparedFile(name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 100L,
                ("zz-" + name + System.nanoTime()).getBytes(), "sha-" + name + "-" + System.nanoTime(),
                ReplyFileKind.EXCEL, ReplyParseStatus.PARSED, null, t, null);
    }

    public static PreparedFile pdf(String name) throws Exception {
        byte[] b = pdfBytes(2);
        return new PreparedFile(name, "application/pdf", (long) b.length, b, "sha-pdf-" + System.nanoTime(),
                ReplyFileKind.PDF, ReplyParseStatus.NOT_PARSED, null, null, 2);
    }

    public static byte[] pdfBytes(int pages) throws Exception {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) d.addPage(new PDPage());
            d.save(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 2: Тесты сервиса — написать (красные)**

`SupplierReplyServiceTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.supplierreply.SupplierReplyService;
import com.vladoose.nir.service.supplierreply.SupplierReplyService.ReplyOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.vladoose.nir.service.supplierreply.ReplyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SupplierReplyServiceTest {

    @Autowired SupplierReplyService service;
    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository requests;
    @Autowired PriceRequestItemRepository items;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired HeaderSynonymRepository synonyms;
    @Autowired EntityManager em;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        Tender t = Tender.builder().tenderNumber("ZZSVC-" + System.nanoTime()).status("NEW").source(Source.PRIVATE_REQUEST).build();
        for (String n : List.of("Скальпель офтальмологический MSL24", "Скальпель офтальмологический MST15", "Материал шовный нейлон 2056")) {
            t.getLots().add(TenderLot.builder().tender(t).equipName(n).quantity(2).build());
        }
        t = tenders.save(t);
        Distributor d = distributors.save(Distributor.builder().name("ZZSVC Дистр " + System.nanoTime()).build());
        pr = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        for (TenderLot l : t.getLots()) pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(2).build());
        pr = requests.save(pr);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ReplyTable kzTable() {
        return table("KZT", "Скальпели офтальмологические: Скальпель MSL24", "4 700",
                "Скальпели офтальмологические: Скальпель MST15", "4 200",
                "Материал шовный стерильный нейлон 2056", "11 750", "Доставка", "5 000");
    }

    PriceRequestItem item(int i) {
        return items.findById(pr.getItems().get(i).getId()).orElseThrow();
    }

    @Test
    void ingestAppliesConfidentPricesWithAutoMark() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);

        assertThat(o.applied()).isEqualTo(3);
        assertThat(o.positions()).isEqualTo(3);
        assertThat(o.fileIds()).hasSize(1);
        PriceRequestItem first = item(0);
        assertThat(first.getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(first.isResponseAuto()).isTrue();
        assertThat(first.getResponseFileId()).isEqualTo(o.fileIds().get(0));
        assertThat(first.getResponseFileRow()).isEqualTo(5);
        assertThat(first.getResponseNote()).startsWith("Из файла «kp.xlsx», строка 5.");
    }

    @Test
    void manualPriceKept_ownAutoPriceReplaced() {
        PriceRequestItem manual = pr.getItems().get(1);
        manual.setResponsePrice(new BigDecimal("999"));
        PriceRequestItem auto = pr.getItems().get(0);
        auto.setResponsePrice(new BigDecimal("100"));
        auto.setResponseAuto(true);
        em.flush();

        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);

        assertThat(o.manualKept()).isEqualTo(1);
        assertThat(item(1).getResponsePrice()).isEqualByComparingTo("999");
        assertThat(item(1).isResponseAuto()).isFalse();
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
    }

    @Test
    void sameFileTwiceStoredOnce() {
        var f = parsed("kp.xlsx", kzTable());
        service.ingest(pr, List.of(f), null);
        ReplyOutcome again = service.ingest(pr, List.of(f), null);
        assertThat(again.fileIds()).isEmpty();
        assertThat(files.findInfo(pr.getId())).hasSize(1);
    }

    @Test
    void foreignCurrencyNotApplied() {
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", table("RUB",
                "Скальпели офтальмологические: Скальпель MSL24", "4700"))), null);
        assertThat(o.applied()).isZero();
        assertThat(o.foreignCurrency()).isEqualTo("RUB");
        assertThat(item(0).getResponsePrice()).isNull();
    }

    @Test
    void closedRequestStoresFilesButNoPrices() {
        pr.setStatus("CLOSED");
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        assertThat(o.fileIds()).hasSize(1);
        assertThat(o.applied()).isZero();
        assertThat(o.closed()).isTrue();
        assertThat(item(0).getResponsePrice()).isNull();
    }

    @Test
    void pdfIsViewOnly() throws Exception {
        ReplyOutcome o = service.ingest(pr, List.of(pdf("kp.pdf")), null);
        assertThat(o.viewOnly()).isTrue();
        assertThat(o.parsedName()).isNull();
        assertThat(o.applied()).isZero();
    }

    @Test
    void markResponded_fromSentAndDeclined_notFromClosed() {
        SupplierReplyService.markResponded(pr, "Добрый день, КП во вложении");
        assertThat(pr.getStatus()).isEqualTo("RESPONDED");
        assertThat(pr.getResponseDate()).isNotNull();
        assertThat(pr.getNote()).isEqualTo("Добрый день, КП во вложении");
        pr.setStatus("CLOSED");
        SupplierReplyService.markResponded(pr, "ещё");
        assertThat(pr.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void setColumnsReappliesAndLearns() {
        ReplyTable t = kzTable();
        ReplyTable wrong = t.withRoles(java.util.Map.of(2, LineField.IGNORE));
        ReplyOutcome o = service.ingest(pr, List.of(parsed("kp.xlsx", wrong)), null);
        assertThat(o.applied()).isZero();
        Long fileId = o.fileIds().get(0);
        String header = "zz цена своя " + System.nanoTime();
        SupplierReplyFile f = files.findById(fileId).orElseThrow();
        f.setParsed(new ReplyTable(wrong.source(), wrong.headerRow(), wrong.score(), "KZT", List.of(
                wrong.columns().get(0), wrong.columns().get(1), new ReplyTable.Column(2, header, LineField.IGNORE)), wrong.rows()));
        em.flush();

        MarketContext.set(Market.KZ);
        service.setColumns(pr.getId(), fileId, List.of(new ReplyColumnRole(2, LineField.PRICE)));

        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");
        assertThat(synonyms.findByHeaderNorm(header)).get().extracting(HeaderSynonym::getField).isEqualTo(LineField.PRICE);
    }

    @Test
    void deleteUploadedFileRevertsUnverifiedPrices_emailFileIs400() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Скальпели офтальмологические: Скальпель MSL24", "2", "4 700")));
        SupplierReplyFile up = service.upload(pr.getId(), "мой.xlsx", "application/octet-stream", xlsx, "admin");
        assertThat(item(0).getResponsePrice()).isEqualByComparingTo("4700");

        service.deleteFile(pr.getId(), up.getId());
        assertThat(item(0).getResponsePrice()).isNull();
        assertThat(item(0).getResponseFileId()).isNull();

        ReplyOutcome mail = service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        assertThatThrownBy(() -> service.deleteFile(pr.getId(), mail.fileIds().get(0))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void verifyClearsAuto_otherMarketIs404() {
        service.ingest(pr, List.of(parsed("kp.xlsx", kzTable())), null);
        assertThat(service.verify(pr.getId())).isEqualTo(3);
        assertThat(item(0).isResponseAuto()).isFalse();

        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.verify(pr.getId())).isInstanceOf(NotFoundException.class);
    }
}
```

`SupplierReplyFilesTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.service.mail.MailFile;
import com.vladoose.nir.service.supplierreply.PreparedFile;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.KpToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SupplierReplyFilesTest {

    @Autowired SupplierReplyFiles replyFiles;

    @Test
    void excelParsed_garbageFailed_pdfPages_imageView() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        PreparedFile x = replyFiles.prepare("kp.xlsx", "application/octet-stream", xlsx);
        assertThat(x.kind()).isEqualTo(ReplyFileKind.EXCEL);
        assertThat(x.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(x.sha256()).hasSize(64);

        PreparedFile bad = replyFiles.prepare("kp.xlsx", "application/octet-stream", "не excel".getBytes());
        assertThat(bad.status()).isEqualTo(ReplyParseStatus.FAILED);
        assertThat(bad.note()).contains("не открылся");

        PreparedFile pdf = replyFiles.prepare("КП.pdf", "application/pdf", ReplyFixtures.pdfBytes(3));
        assertThat(pdf.kind()).isEqualTo(ReplyFileKind.PDF);
        assertThat(pdf.pageCount()).isEqualTo(3);

        assertThat(replyFiles.prepare("фото.jpg", "image/jpeg", new byte[]{1, 2, 3}).kind()).isEqualTo(ReplyFileKind.IMAGE);

        PreparedFile noTable = replyFiles.prepare("письмо.xlsx", "x", ReplyTableReaderTest.xlsx(List.of(List.of("Добрый день"))));
        assertThat(noTable.status()).isEqualTo(ReplyParseStatus.NO_TABLE);
    }

    @Autowired HeaderDictionary dictionary;
    @Autowired ReplyPdfPages pages;

    /** Прайс-лист на десятки тысяч строк не разбирается (память прода); имя файла — без пути и U+0000. */
    @Test
    void bigOoxmlIsNotParsed_fileNameCleaned() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Кушетка", "95 000")));
        assertThat(new SupplierReplyFiles(dictionary, pages, 100).prepare("kp.xlsx", "x", xlsx).status())
                .isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(SupplierReplyFiles.ooxmlTooBig(xlsx, 100)).isTrue();
        assertThat(SupplierReplyFiles.ooxmlTooBig(xlsx, SupplierReplyFiles.XML_MAX)).isFalse();
        assertThat(SupplierReplyFiles.ooxmlTooBig(new byte[]{1, 2, 3}, 1)).isFalse();
        assertThat(SupplierReplyFiles.fileName("C:\\Users\\x\\КП\0.xlsx")).isEqualTo("КП.xlsx");
        assertThat(SupplierReplyFiles.fileName("  ")).isEqualTo("файл");
    }

    @Test
    void prepareFor_onlyLettersWithKpToken_tooLargeWithoutBytes() {
        MailFile big = new MailFile("большой.pdf", "application/pdf", null, null);
        assertThat(replyFiles.prepareFor(mail().subject("Прайс").files(big).build())).isEmpty();
        List<PreparedFile> p = replyFiles.prepareFor(mail().subject("Re: " + KpToken.subjectToken(5L)).files(big).build());
        assertThat(p).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(ReplyParseStatus.TOO_LARGE);
            assertThat(f.bytes()).isNull();
        });
    }
}
```

Run: `./gradlew test --tests '*SupplierReplyServiceTest' --tests '*SupplierReplyFilesTest'` → FAIL.

- [ ] **Step 3: `HeaderDictionary`, `PreparedFile`, `ReplyColumnRole`, `ReplyPdfPages`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Обучаемый словарь заголовков (header_synonym) — общий с разбором смет клиник. */
@Component
public class HeaderDictionary {
    private final HeaderSynonymRepository repository;

    public HeaderDictionary(HeaderSynonymRepository repository) {
        this.repository = repository;
    }

    public Map<String, LineField> learned() {
        Map<String, LineField> out = new HashMap<>();
        for (HeaderSynonym s : repository.findAll()) out.put(s.getHeaderNorm(), s.getField());
        return out;
    }
}
```

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;

/** Файл ответа, разобранный вне транзакции (спека §3.1): bytes и sha256 — null у файла больше предела хранения. */
public record PreparedFile(String name, String mimeType, Long size, byte[] bytes, String sha256, ReplyFileKind kind,
                           ReplyParseStatus status, String note, ReplyTable table, Integer pageCount) {}
```

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LineField;

/** Роль колонки таблицы файла ответа, выбранная оператором на экране «Ответ поставщика». */
public record ReplyColumnRole(int index, LineField role) {}
```

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.document.KpPreviewRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Страницы PDF ответа поставщика картинками (спека §4.1): по одной, по запросу, кеш потоков — во временных файлах, а не
 * в куче (на проде -Xmx1g; сканы с крупными картинками). Показываются первые MAX_PAGES страниц.
 */
@Component
public class ReplyPdfPages {

    public static final int MAX_PAGES = 30;

    public int pageCount(byte[] pdf) throws IOException {
        try (PDDocument d = load(pdf)) {
            return d.getNumberOfPages();
        }
    }

    public byte[] page(byte[] pdf, int index) throws IOException {
        try (PDDocument d = load(pdf)) {
            if (index < 0 || index >= Math.min(d.getNumberOfPages(), MAX_PAGES)) {
                throw new NotFoundException("Страницы " + (index + 1) + " нет — показываются первые " + MAX_PAGES);
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new PDFRenderer(d).renderImageWithDPI(index, KpPreviewRenderer.DPI, ImageType.RGB), "png", png);
            return png.toByteArray();
        }
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        return Loader.loadPDF(pdf, "", null, null, IOUtils.createTempFileOnlyStreamCache());
    }
}
```

Если сигнатура `Loader.loadPDF(byte[], String, InputStream, String, StreamCacheCreateFunction)` в PDFBox 3.0.7 другая — сверить `javap -cp <pdfbox-3.0.7.jar из кеша Gradle> org.apache.pdfbox.Loader` и взять перегрузку с `StreamCacheCreateFunction`.

- [ ] **Step 4: `SupplierReplyFiles`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.mail.MailFile;
import com.vladoose.nir.service.mail.ParsedMail;
import com.vladoose.nir.util.KpToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Файлы ответа поставщика — вне транзакции письма (спека §3.1, решение плана 5): вид, SHA-256, таблица Excel / Word,
 * число страниц PDF. Любой сбой разбора — пометка FAILED, а не исключение: битый файл не роняет приём почты.
 */
@Component
public class SupplierReplyFiles {

    public static final int PARSE_MAX = 5 * 1024 * 1024;
    /** Распакованный XML книги / документа: 15 МБ — это десятки тысяч ячеек, КП на 40 позиций занимает сотни КБ. */
    static final long XML_MAX = 15L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(SupplierReplyFiles.class);

    private final HeaderDictionary dictionary;
    private final ReplyPdfPages pdf;
    private final long xmlMax;

    @Autowired
    public SupplierReplyFiles(HeaderDictionary dictionary, ReplyPdfPages pdf) {
        this(dictionary, pdf, XML_MAX);
    }

    /** Тесты: свой предел распакованного XML. */
    SupplierReplyFiles(HeaderDictionary dictionary, ReplyPdfPages pdf, long xmlMax) {
        this.dictionary = dictionary;
        this.pdf = pdf;
        this.xmlMax = xmlMax;
    }

    /** Файлы письма с меткой [КП-…] в теме; прочим письмам разбор файлов не нужен. */
    public List<PreparedFile> prepareFor(ParsedMail m) {
        if (m.files().isEmpty() || KpToken.parse(m.subject()).isEmpty()) return List.of();
        Map<String, LineField> learned = dictionary.learned();
        return m.files().stream().map(f -> f.stored() ? prepare(f.name(), f.mimeType(), f.content(), learned) : tooLarge(f)).toList();
    }

    public PreparedFile prepare(String name, String mime, byte[] bytes) {
        return prepare(name, mime, bytes, dictionary.learned());
    }

    PreparedFile prepare(String name, String mime, byte[] bytes, Map<String, LineField> learned) {
        String n = fileName(name);
        ReplyFileKind kind = kind(n, mime, bytes);
        String sha = sha256(bytes);
        try {
            switch (kind) {
                case EXCEL, WORD -> {
                    // POI держит книгу целиком в памяти: прайс-лист на десятки тысяч строк при -Xmx1g прода опасен
                    if (bytes.length > PARSE_MAX || ooxmlTooBig(bytes, xmlMax)) {
                        return file(n, mime, bytes, sha, kind, ReplyParseStatus.NOT_PARSED,
                                "Файл слишком большой для разбора — откройте его кнопкой «Скачать»", null, null);
                    }
                    Optional<ReplyTable> t = ReplyTableReader.read(bytes, kind, learned);
                    return t.isPresent()
                            ? file(n, mime, bytes, sha, kind, ReplyParseStatus.PARSED, null, t.get(), null)
                            : file(n, mime, bytes, sha, kind, ReplyParseStatus.NO_TABLE,
                                    "Таблица с наименованием и ценой не найдена — введите цены по документу", null, null);
                }
                case PDF -> {
                    return file(n, mime, bytes, sha, kind, ReplyParseStatus.NOT_PARSED, null, null, pdf.pageCount(bytes));
                }
                default -> {
                    return file(n, mime, bytes, sha, kind, ReplyParseStatus.NOT_PARSED, null, null, null);
                }
            }
        } catch (Exception | Error e) {
            log.warn("Файл ответа поставщика не разобран ({}): {}", kind, e.getClass().getSimpleName());
            return file(n, mime, bytes, sha, kind, ReplyParseStatus.FAILED,
                    kind == ReplyFileKind.PDF ? "PDF не открылся — скачайте файл" : "Файл не открылся: защищён паролем или повреждён",
                    null, null);
        }
    }

    static PreparedFile tooLarge(MailFile f) {
        String n = fileName(f.name());
        return new PreparedFile(n, f.mimeType(), null, null, null, kind(n, f.mimeType(), new byte[0]),
                ReplyParseStatus.TOO_LARGE, "Файл больше предела — откройте его в почте", null, null);
    }

    /** Имя файла для хранения: без пути (старые браузеры шлют «C:\…\КП.xlsx»), без U+0000 (PostgreSQL его не хранит). */
    static String fileName(String name) {
        String n = name == null ? "" : name.replace("\0", "").replaceAll(".*[/\\\\]", "").trim();
        return n.isEmpty() ? "файл" : cut(n, 255);
    }

    /**
     * Распакованный объём XML-частей OOXML (листы, общие строки, document.xml) больше limit. Не zip (.xls, .doc) — false;
     * битый zip — исключение (его ловит prepare: «не открылся»).
     */
    public static boolean ooxmlTooBig(byte[] b, long limit) throws IOException {
        if (b.length < 2 || b[0] != 'P' || b[1] != 'K') return false;
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(b))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (!e.getName().endsWith(".xml")) continue;
                for (int r; (r = zin.read(buf)) > 0; ) {
                    total += r;
                    if (total > limit) return true;
                }
            }
        }
        return false;
    }

    static ReplyFileKind kind(String name, String mime, byte[] b) {
        String n = name.toLowerCase(Locale.ROOT), m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx") || n.endsWith(".xls") || m.contains("spreadsheet") || m.equals("application/vnd.ms-excel")) return ReplyFileKind.EXCEL;
        if (n.endsWith(".docx") || n.endsWith(".doc") || m.contains("wordprocessing") || m.equals("application/msword")) return ReplyFileKind.WORD;
        if (n.endsWith(".pdf") || m.equals("application/pdf") || (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F')) return ReplyFileKind.PDF;
        if (n.matches(".*\\.(jpe?g|png|webp|gif)$") || m.matches("image/(jpeg|png|webp|gif)")) return ReplyFileKind.IMAGE;
        return ReplyFileKind.OTHER;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedFile file(String n, String mime, byte[] b, String sha, ReplyFileKind kind, ReplyParseStatus st,
                                     String note, ReplyTable t, Integer pages) {
        return new PreparedFile(n, mime == null ? null : cut(mime, 100), (long) b.length, b, sha, kind, st, note, t, pages);
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 5: `SupplierReplyService`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Файлы ответа поставщика и цены из них (спека 2026-10-15 §3.4): хранит файлы (один и тот же — один раз), подставляет
 * уверенные цены с 💡 в строки ответа (под блокировкой; ручную цену не трогает; свою прежнюю 💡 заменяет), снимает 💡
 * («Всё верно»), правит роли колонок, удаляет загруженные вручную файлы. Статус запроса после письма меняет приём почты
 * (markResponded), после загрузки и «Взять цены» — этот сервис. Гард рынка — явный (findById фильтр рынка не режет).
 */
@Service
public class SupplierReplyService {

    static final Set<String> CLOSED = Set.of("ACCEPTED", "REJECTED", "CLOSED");
    static final Set<String> RESPONDABLE = Set.of("CREATED", "SENT", "DECLINED", "RESPONDED");

    /**
     * Итог файлов письма — для строки уведомления (MailIngestWriter.fileLine). fileName — первый сохранённый файл,
     * parsedName — файл с разобранной таблицей (null — такого нет), unreadable — Excel / Word, который не разобрался,
     * closed — запрос закрыт: файлы сохранены, цены не трогали.
     */
    public record ReplyOutcome(List<Long> fileIds, String fileName, String parsedName, int applied, int positions,
                               int manualKept, String foreignCurrency, boolean viewOnly, boolean image, String unreadable,
                               boolean closed) {
        public static ReplyOutcome none() {
            return new ReplyOutcome(List.of(), null, null, 0, 0, 0, null, false, false, null, false);
        }
    }

    public record ApplyResult(int applied, int positions, int manualKept, String foreignCurrency) {}

    private final SupplierReplyFileRepository files;
    private final PriceRequestItemRepository items;
    private final PriceRequestRepository requests;
    private final SupplierReplyFiles replyFiles;
    private final PrivateRequestImportService importService;

    public SupplierReplyService(SupplierReplyFileRepository files, PriceRequestItemRepository items,
                                PriceRequestRepository requests, SupplierReplyFiles replyFiles,
                                PrivateRequestImportService importService) {
        this.files = files;
        this.items = items;
        this.requests = requests;
        this.replyFiles = replyFiles;
        this.importService = importService;
    }

    /** Письмо: сохранить новые файлы и подставить цены из лучшей таблицы. */
    @Transactional
    public ReplyOutcome ingest(PriceRequest pr, List<PreparedFile> prepared, String uploadedBy) {
        List<SupplierReplyFile> stored = new ArrayList<>();
        for (PreparedFile p : prepared) {
            if (p.sha256() != null && files.existsByPriceRequestIdAndSha256(pr.getId(), p.sha256())) continue;
            stored.add(files.save(entity(pr.getId(), p, uploadedBy)));
        }
        if (stored.isEmpty()) return ReplyOutcome.none();
        List<Long> ids = stored.stream().map(SupplierReplyFile::getId).toList();
        Optional<SupplierReplyFile> best = stored.stream().filter(f -> f.getParseStatus() == ReplyParseStatus.PARSED)
                .max(Comparator.comparingInt((SupplierReplyFile f) -> f.getParsed().score())
                        .thenComparingInt(f -> f.getParsed().rows().size()));
        boolean view = stored.stream().anyMatch(f -> f.getKind() == ReplyFileKind.PDF || f.getKind() == ReplyFileKind.IMAGE);
        boolean image = view && stored.stream().noneMatch(f -> f.getKind() == ReplyFileKind.PDF);
        String unreadable = stored.stream()
                .filter(f -> (f.getKind() == ReplyFileKind.EXCEL || f.getKind() == ReplyFileKind.WORD)
                        && f.getParseStatus() != ReplyParseStatus.PARSED)
                .map(SupplierReplyFile::getFileName).findFirst().orElse(null);
        String name = stored.get(0).getFileName();
        String parsedName = best.map(SupplierReplyFile::getFileName).orElse(null);
        boolean closed = CLOSED.contains(pr.getStatus());
        if (best.isEmpty() || closed) {
            return new ReplyOutcome(ids, name, parsedName, 0, pr.getItems().size(), 0, null, view, image, unreadable, closed);
        }
        ApplyResult a = apply(pr, best.get());
        return new ReplyOutcome(ids, name, parsedName, a.applied(), a.positions(), a.manualKept(), a.foreignCurrency(),
                view, image, unreadable, false);
    }

    /** Письмо записано — файлам ставится ссылка на строку «Входящих». */
    @Transactional
    public void linkInbound(Collection<Long> fileIds, Long inboundId) {
        for (SupplierReplyFile f : files.findAllById(fileIds)) f.setInboundEmailId(inboundId);
    }

    /** «Ответил»: статус, дата ответа и (если есть) текст письма в примечании запроса; закрытые запросы не трогает. */
    public static void markResponded(PriceRequest pr, String displayText) {
        if (!RESPONDABLE.contains(pr.getStatus())) return;
        pr.setStatus("RESPONDED");
        pr.setResponseDate(LocalDate.now());
        if (displayText != null) pr.setNote(displayText.length() <= 4000 ? displayText : displayText.substring(0, 4000));
    }

    public PriceRequest guard(Long prId) {
        PriceRequest pr = requests.findById(prId).orElseThrow(() -> new NotFoundException("Запрос цен не найден: " + prId));
        if (pr.getMarket() != null && pr.getMarket() != MarketContext.get()) throw new NotFoundException("Запрос цен не найден: " + prId);
        return pr;
    }

    /** Файл, загруженный вручную (КП из WhatsApp, письмо без метки): сохранить и, если таблица разобрана, подставить. */
    @Transactional
    public SupplierReplyFile upload(Long prId, String name, String mime, byte[] bytes, String user) {
        PriceRequest pr = guard(prId);
        if (bytes == null || bytes.length == 0) throw new BadRequestException("Файл пустой");
        PreparedFile p = replyFiles.prepare(name, mime, bytes);
        if (files.existsByPriceRequestIdAndSha256(pr.getId(), p.sha256())) {
            throw new BadRequestException("Этот файл уже есть в ответе поставщика");
        }
        SupplierReplyFile f = files.save(entity(pr.getId(), p, user == null ? "admin" : user));
        if (f.getParseStatus() == ReplyParseStatus.PARSED && !CLOSED.contains(pr.getStatus())) {
            if (apply(pr, f).applied() > 0) markResponded(pr, null);
        }
        return f;
    }

    /** «Взять цены из этого файла». */
    @Transactional
    public ApplyResult applyFile(Long prId, Long fileId) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        if (f.getParseStatus() != ReplyParseStatus.PARSED || f.getParsed() == null) {
            throw new BadRequestException("У файла нет разобранной таблицы — введите цены по документу");
        }
        if (CLOSED.contains(pr.getStatus())) throw new BadRequestException("Запрос закрыт — цены не подставляются");
        ApplyResult a = apply(pr, f);
        if (a.applied() > 0) markResponded(pr, null);
        return a;
    }

    /** Роли колонок от оператора: таблица файла правится, словарь учится, цены из файла пересчитываются. */
    @Transactional
    public void setColumns(Long prId, Long fileId, List<ReplyColumnRole> roles) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        ReplyTable t = f.getParsed();
        if (t == null) throw new BadRequestException("У файла нет разобранной таблицы");
        Map<Integer, LineField> map = new HashMap<>();
        List<ColumnMapping> learn = new ArrayList<>();
        for (ReplyColumnRole r : roles == null ? List.<ReplyColumnRole>of() : roles) {
            if (r.role() == null || r.index() < 0 || r.index() >= t.columns().size()) {
                throw new BadRequestException("Неверная колонка: " + r.index());
            }
            map.put(r.index(), r.role());
            ColumnMapping m = new ColumnMapping();
            m.setHeader(t.columns().get(r.index()).header());
            m.setField(r.role());
            learn.add(m);
        }
        f.setParsed(t.withRoles(map));
        importService.learn(learn);
        revertAuto(pr.getId(), f.getId(), false);
        if (!CLOSED.contains(pr.getStatus()) && apply(pr, f).applied() > 0) markResponded(pr, null);
    }

    /** Удалить загруженный вручную файл; несверенные цены из него снимаются. Файл из письма — часть ответа, не удаляется. */
    @Transactional
    public void deleteFile(Long prId, Long fileId) {
        PriceRequest pr = guard(prId);
        SupplierReplyFile f = fileOf(pr, fileId);
        if (f.getUploadedBy() == null) throw new BadRequestException("Файл из письма не удаляется — он часть ответа поставщика");
        revertAuto(pr.getId(), f.getId(), true);
        items.flush();
        files.delete(f);
    }

    /** «Всё верно» — снять 💡 со всех цен запроса. */
    @Transactional
    public int verify(Long prId) {
        guard(prId);
        return items.clearAuto(prId);
    }

    ApplyResult apply(PriceRequest pr, SupplierReplyFile f) {
        List<PriceRequestItem> its = items.lockByPriceRequestId(pr.getId());
        List<ReplyRowValues> rows = ReplyRowValues.of(f.getParsed());
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();
        String cur = tableCurrency(rows, f.getParsed());
        if (cur != null && market != null && !cur.equals(market)) return new ApplyResult(0, its.size(), 0, cur);
        Map<Integer, ReplyRowValues> byRow = new HashMap<>();
        List<ReplyMatcher.Row> mrows = new ArrayList<>();
        int order = 0;
        for (ReplyRowValues r : rows) {
            if (!r.participates()) continue;
            byRow.put(r.fileRow(), r);
            mrows.add(new ReplyMatcher.Row(r.fileRow(), order++, r.matchText(), r.quantity() == null ? null : r.quantity().value()));
        }
        Map<Long, PriceRequestItem> byId = new HashMap<>();
        List<ReplyMatcher.Position> pos = new ArrayList<>();
        for (int i = 0; i < its.size(); i++) {
            PriceRequestItem it = its.get(i);
            byId.put(it.getId(), it);
            pos.add(new ReplyMatcher.Position(it.getId(), i + 1, positionText(it), it.getRequestedQuantity()));
        }
        int applied = 0, manual = 0;
        for (ReplyMatcher.Match m : ReplyMatcher.match(pos, mrows)) {
            if (m.kind() != ReplyMatcher.Kind.AUTO) continue;
            ReplyRowValues r = byRow.get(m.fileRow());
            if (r == null || r.price() == null) continue;
            PriceRequestItem it = byId.get(m.itemId());
            if (it.getResponsePrice() != null && !it.isResponseAuto()) {
                manual++;
                continue;
            }
            it.setResponsePrice(r.price());
            it.setResponseAuto(true);
            it.setResponseFileId(f.getId());
            it.setResponseFileRow(r.fileRow());
            it.setResponseNote(note(f.getFileName(), r));
            applied++;
        }
        return new ApplyResult(applied, its.size(), manual, null);
    }

    /** Несверенные цены из файла — снять; unlinkAll — ещё и связь ручных цен с этим файлом (файл удаляется). */
    private void revertAuto(Long prId, Long fileId, boolean unlinkAll) {
        for (PriceRequestItem it : items.lockByPriceRequestId(prId)) {
            if (!fileId.equals(it.getResponseFileId())) continue;
            if (it.isResponseAuto()) {
                it.setResponsePrice(null);
                it.setResponseNote(null);
                it.setResponseAuto(false);
                it.setResponseFileId(null);
                it.setResponseFileRow(null);
            } else if (unlinkAll) {
                it.setResponseFileId(null);
                it.setResponseFileRow(null);
            }
        }
    }

    /** Наименование, бренд позиции и предложенная модель — то, что видит поставщик в письме. */
    public static String positionText(PriceRequestItem i) {
        TenderLot l = i.getTenderLot();
        MedEquipment e = i.getMedEquipment();
        return Stream.of(l == null ? null : l.getEquipName(), l == null ? null : l.getManufact(),
                        e == null ? null : e.getName(), e == null ? null : e.getManufact())
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
    }

    /** Валюта таблицы: самая частая у строк с ценой, иначе по шапке. */
    static String tableCurrency(List<ReplyRowValues> rows, ReplyTable t) {
        return rows.stream().filter(r -> r.price() != null && r.currency() != null)
                .collect(Collectors.groupingBy(ReplyRowValues::currency, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(t.currency());
    }

    private static String note(String fileName, ReplyRowValues r) {
        String name = fileName.length() <= 120 ? fileName : fileName.substring(0, 120);
        StringBuilder s = new StringBuilder("Из файла «").append(name).append("», строка ").append(r.fileRow()).append('.');
        if (r.priceComputed()) s.append(" Цена посчитана из суммы.");
        if (r.note() != null) s.append(" Примечание поставщика: «").append(r.note().length() <= 300 ? r.note() : r.note().substring(0, 300)).append("».");
        return s.toString();
    }

    private SupplierReplyFile fileOf(PriceRequest pr, Long fileId) {
        return files.findById(fileId).filter(f -> pr.getId().equals(f.getPriceRequestId()))
                .orElseThrow(() -> new NotFoundException("Файл ответа не найден: " + fileId));
    }

    private static SupplierReplyFile entity(Long prId, PreparedFile p, String uploadedBy) {
        return SupplierReplyFile.builder().priceRequestId(prId).fileName(p.name()).mimeType(p.mimeType()).sizeBytes(p.size())
                .sha256(p.sha256()).data(p.bytes()).kind(p.kind()).parseStatus(p.status())
                .parseNote(p.note() == null ? null : (p.note().length() <= 300 ? p.note() : p.note().substring(0, 300)))
                .parsed(p.table()).pageCount(p.pageCount()).uploadedBy(uploadedBy).build();
    }
}
```

- [ ] **Step 6: Прогнать**

Run: `./gradlew test --tests '*SupplierReplyServiceTest' --tests '*SupplierReplyFilesTest'` (sandbox off)
Expected: PASS. Мутации по одной: (а) убрать проверку ручной цены в `apply` — красный `manualPriceKept_ownAutoPriceReplaced`; (б) убрать проверку валюты — красный `foreignCurrencyNotApplied`; (в) убрать `existsByPriceRequestIdAndSha256` в `ingest` — красный `sameFileTwiceStoredOnce`; (г) убрать гард рынка в `guard` — красный `verifyClearsAuto_otherMarketIs404`; (д) убрать `ooxmlTooBig` из условия разбора — красный `bigOoxmlIsNotParsed_fileNameCleaned`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/supplierreply src/main/java/com/vladoose/nir/dto/request/ReplyColumnRole.java src/test/java/com/vladoose/nir/service/supplierreply
git commit -m "feat(supplier-reply): файлы ответа и подстановка цен с 💡 — хранение, сопоставление, роли колонок, удаление

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Приём zakup@ — файлы ответа, статус, Telegram

**Files:**
- Modify: `src/main/resources/application.yaml` (`mail.imap.max-file-mb`)
- Modify: `src/main/java/com/vladoose/nir/service/mail/ImapMailboxConnector.java:43-50`
- Modify: `src/main/java/com/vladoose/nir/service/MailReceiveService.java` (конструкторы, `pass`)
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java` (конструктор, `write`)
- Modify: `src/main/java/com/vladoose/nir/service/mail/KpOutcome.java`, `MailNotificationComposer.java`
- Modify tests: `src/test/java/com/vladoose/nir/service/MailReceiveServiceTest.java`, `src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java`, `src/test/java/com/vladoose/nir/service/mail/MailNotificationComposerTest.java`, `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`, `src/test/java/com/vladoose/nir/service/mail/ImapMailboxConnectorTest.java`

**Interfaces:**
- Consumes: `SupplierReplyFiles.prepareFor`, `SupplierReplyService.ingest / linkInbound / markResponded`, `ReplyOutcome`, `MoneyText.label` (задачи 2, 4).
- Produces: `MailIngestWriter.write(ParsedMail, List<PreparedFile>, long uidValidity)` (2-аргументный `write` остаётся и делегирует с `List.of()`); `KpOutcome.FILE_PRICES`; `MailNotificationComposer.compose(ParsedMail, Classification, KpSnapshot, KpOutcome, String fileLine, ComposeContext)` и прежняя перегрузка без `fileLine`; `MailIngestWriter.fileLine(ReplyOutcome) → String|null`.

- [ ] **Step 1: Тесты — написать / поправить (красные)**

В `MailIngestWriterTest`: поле `@Autowired SupplierReplyService replies;` и в `writer(...)` — новый последний аргумент конструктора `replies`. Новые тесты (импорты `com.vladoose.nir.service.supplierreply.*`, `com.vladoose.nir.repository.SupplierReplyFileRepository`, `static com.vladoose.nir.service.supplierreply.ReplyFixtures.*`):

Помощник `pr(…)` класса называет лоты «Аппарат 1», «Аппарат 2» — для сопоставления они неразличимы (однозначное число словом не считается), поэтому тестам файлов — свой помощник с настоящими названиями:

```java
    @Autowired SupplierReplyFileRepository replyFiles;

    /** Запрос КП с лотами по названиям, по одной штуке. */
    PriceRequest prNamed(String status, Source source, String... names) {
        Tender t = Tender.builder().tenderNumber("ZZW-" + System.nanoTime()).status("NEW").source(source).build();
        for (String n : names) t.getLots().add(TenderLot.builder().tender(t).equipName(n).quantity(1).build());
        t = tenderRepo.save(t);
        Distributor d = distributorRepo.save(Distributor.builder().name("ZZW Дистр " + System.nanoTime()).email("sales@zzw.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status(status).build();
        for (TenderLot l : t.getLots()) {
            pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build());
        }
        return prRepo.save(pr);
    }

    @Test
    void supplierResponse_fileWithPrices_applies_linksInbound_fileLineAndLink() {
        PriceRequest p = prNamed("SENT", Source.PRIVATE_REQUEST, "Кушетка смотровая", "Столик процедурный");
        PreparedFile f = parsed("kp.xlsx", table("KZT", "Кушетка смотровая КС-1", "100 000", "Столик процедурный СП-2", "200 000"));

        MailIngestWriter.WriteResult r = writer().write(mail().uid(30).subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Добрый день, КП во вложении").attachments("kp.xlsx").build(), List.of(f), 5);

        assertThat(r.outcome()).isEqualTo(KpOutcome.FILE_PRICES);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("RESPONDED");
        assertThat(back.getItems()).allMatch(PriceRequestItem::isResponseAuto);
        assertThat(replyFiles.findInfo(p.getId())).singleElement().extracting(ReplyFileInfo::inboundEmailId).isEqualTo(r.inboundId());
        assertThat(rows().get(0).getNotifyText())
                .contains("💡 Цены из файла «kp.xlsx»: подставлено 2 из 2 — сверьте")
                .contains("https://ais.example/price-requests/" + p.getId() + "?market=KZ");
    }

    @Test
    void respondedRequest_secondLetterWithFile_applies() {
        PriceRequest p = prNamed("RESPONDED", Source.PRIVATE_REQUEST, "Кушетка смотровая");
        writer().write(mail().uid(31).subject("Re: " + KpToken.subjectToken(p.getId())).text("Исправленное КП").build(),
                List.of(parsed("kp2.xlsx", table("KZT", "Кушетка смотровая КС-1", "90 000"))), 5);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getItems().get(0).getResponsePrice()).isEqualByComparingTo("90000");
    }

    @Test
    void closedRequest_fileStored_pricesUntouched_lineSaysSo() {
        PriceRequest p = prNamed("CLOSED", Source.PRIVATE_REQUEST, "Кушетка смотровая");
        writer().write(mail().uid(34).subject("Re: " + KpToken.subjectToken(p.getId())).text("КП").build(),
                List.of(parsed("kp.xlsx", table("KZT", "Кушетка смотровая КС-1", "90 000"))), 5);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getItems().get(0).getResponsePrice()).isNull();
        assertThat(replyFiles.findInfo(p.getId())).hasSize(1);
        assertThat(rows().get(0).getNotifyText()).contains("Файл «kp.xlsx» сохранён — запрос закрыт, цены не меняли");
    }

    @Test
    void brokenFileKeepsTextParsing() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        PreparedFile broken = new PreparedFile("kp.xlsx", "x", 3L, new byte[]{1, 2, 3}, "sha-broken-" + System.nanoTime(),
                ReplyFileKind.EXCEL, ReplyParseStatus.FAILED, "Файл не открылся: защищён паролем или повреждён", null, null);

        MailIngestWriter.WriteResult r = writer().write(mail().uid(32).subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Цена 3 200 000 ₸, срок 3 недели").build(), List.of(broken), 5);

        assertThat(r.outcome()).isEqualTo(KpOutcome.PRICE_PARSED);
        assertThat(rows().get(0).getNotifyText()).contains("Файл «kp.xlsx» не разобран — введите цены вручную");
    }

    @Test
    void foreignCurrencyFileLine() {
        PriceRequest p = prNamed("SENT", Source.PRIVATE_REQUEST, "Кушетка смотровая", "Столик процедурный");
        writer().write(mail().uid(33).subject("Re: " + KpToken.subjectToken(p.getId())).text("КП").build(),
                List.of(parsed("kp.xlsx", table("RUB", "Кушетка смотровая КС-1", "100", "Столик процедурный СП-2", "200"))), 5);
        assertThat(rows().get(0).getNotifyText()).contains("Цены в файле «kp.xlsx» — в руб., не подставлены");
    }
```

Прежние ожидания ссылки у ОТВЕТА ПОСТАВЩИКА — теперь экран сверки (решение плана 6); у возвратов и автоответов не меняются:
- `MailIngestWriterTest.supplierResponse_singleLot_parsesPrice_queuesLoudNotification_movesCursor`: `.endsWith("/price-requests/" + p.getId() + "?market=KZ")` вместо `/tenders?openId=…`;
- `MailIngestWriterTest.privateRequest_linkToPrivateRequests` → переименовать в `privateRequest_labelAndLinkToReply`, `.endsWith("/price-requests/" + p.getId() + "?market=KZ")`;
- `MailIngestWriterTest` — тест R17 (ответ на запрос РФ в ящик KZ, `…&market=RF`): `.endsWith("/price-requests/" + p.getId() + "?market=RF")`;
- `MailNotificationComposerTest.supplierResponse_priceParsed_exactText`: последняя строка — `Открыть в АИС: https://ais.westmed.kz/price-requests/534?market=KZ`;
- `MailNotificationComposerTest.privateRequest_labelAndLink`: `.endsWith("https://ais.westmed.kz/price-requests/5?market=KZ")`.

В `MailReceiveServiceTest`: поле `SupplierReplyFiles replyFiles = mock(SupplierReplyFiles.class);`, в `service(...)` — новый аргумент `replyFiles` после `notifier`; заменить во всём классе заглушки и проверки `writer.write(X, Y)` на `writer.write(X, anyList(), Y)` (импорт `static org.mockito.ArgumentMatchers.anyList`). В `MailReceiveServiceIntegrationTest.telegramEndToEnd_sendsToThread_marksSent` — в конструктор `new MailReceiveService(...)` после `MailTelegramNotifier` передать `@Autowired SupplierReplyFiles supplierReplyFiles`. Там же новый тест:

```java
    @Autowired SupplierReplyFileRepository supplierReplyFileRepository;

    @Test
    void poll_supplierReplyWithExcel_storesFileAndAppliesPrices() throws Exception {
        MarketContext.set(Market.KZ);
        Tender tender = Tender.builder().tenderNumber("ZZXLS-T1").status("NEW").source(Source.PRIVATE_REQUEST).build();
        tender.getLots().add(TenderLot.builder().tender(tender).equipName("Скальпель офтальмологический MSL24").quantity(2).build());
        tender.getLots().add(TenderLot.builder().tender(tender).equipName("Материал шовный нейлон 2056").quantity(1).build());
        tender = tenderRepository.save(tender);
        Distributor dist = distributorRepository.save(Distributor.builder().name("ZZXLS Дистр").email("x@x.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(tender).distributor(dist).status("SENT").build();
        for (TenderLot l : tender.getLots()) pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build());
        Long prId = priceRequestRepository.save(pr).getId();

        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("КП");
            String[][] rows = {{"ТОО «Поставщик»"}, {}, {"№", "Наименование", "Кол-во", "Цена, тг"},
                    {"1", "Скальпели офтальмологические: Скальпель MSL24", "2", "4 700"},
                    {"2", "Материал шовный стерильный нейлон 2056", "1", "11 750"}};
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@x.kz", "Re: Запрос КП " + KpToken.subjectToken(prId), "Добрый день, КП во вложении", xlsx, "КП.xlsx"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        PriceRequest back = priceRequestRepository.findById(prId).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("RESPONDED");
        assertThat(back.getItems()).extracting(PriceRequestItem::getResponsePrice)
                .usingElementComparator(BigDecimal::compareTo).containsExactlyInAnyOrder(new BigDecimal("4700"), new BigDecimal("11750"));
        assertThat(back.getItems()).allMatch(PriceRequestItem::isResponseAuto);
        assertThat(supplierReplyFileRepository.findInfo(prId)).singleElement()
                .satisfies(f -> assertThat(f.inboundEmailId()).isNotNull());
    }

    /** PDF не разбирается: файл хранится для просмотра, письмо разбирается как раньше (текст без цены — «Ответ получен»). */
    @Test
    void poll_supplierReplyWithPdf_storedForViewing() throws Exception {
        MarketContext.set(Market.KZ);
        Tender tender = Tender.builder().tenderNumber("ZZPDF-T1").status("NEW").source(Source.PRIVATE_REQUEST).build();
        tender.getLots().add(TenderLot.builder().tender(tender).equipName("Электрокардиограф").quantity(1).build());
        tender = tenderRepository.save(tender);
        Distributor dist = distributorRepository.save(Distributor.builder().name("ZZPDF Дистр").email("y@y.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(tender).distributor(dist).status("SENT").build();
        pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(tender.getLots().get(0)).requestedQuantity(1).build());
        Long prId = priceRequestRepository.save(pr).getId();
        byte[] pdf;
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.addPage(new PDPage());
            d.addPage(new PDPage());
            d.save(out);
            pdf = out.toByteArray();
        }
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@y.kz", "Re: " + KpToken.subjectToken(prId), "Добрый день, КП во вложении", pdf, "КП.pdf"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        assertThat(priceRequestRepository.findById(prId).orElseThrow().getStatus()).isEqualTo("RESPONDED");
        assertThat(supplierReplyFileRepository.findInfo(prId)).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(ReplyFileKind.PDF);
            assertThat(f.pageCount()).isEqualTo(2);
        });
    }
```

(импорты `java.math.BigDecimal`, `org.apache.pdfbox.pdmodel.PDDocument`, `org.apache.pdfbox.pdmodel.PDPage`, `com.vladoose.nir.service.supplierreply.SupplierReplyFiles`; `SupplierReplyFileRepository`, `ReplyFileKind` — через уже стоящие `repository.*` и `entity.*`).

В `MailNotificationComposerTest` — новый тест (помощники класса: `KZ` — контекст, `sup()` — вид «ответ поставщика» по запросу 534, `kp(status, price, lots…)` — снимок запроса 534):

```java
    @Test
    void supplierResponse_fileLineAfterOutcome_linkToReply() {
        String t = MailNotificationComposer.compose(mail().subject("Re: [КП-534] Запрос").text("КП во вложении").build(), sup(),
                kp("RESPONDED", null), KpOutcome.FILE_PRICES, "💡 Цены из файла «КП.xlsx»: подставлено 12 из 14 — сверьте", KZ).text();
        assertThat(t).contains("💡 Цены из файла «КП.xlsx»: подставлено 12 из 14 — сверьте")
                .endsWith("Открыть в АИС: https://ais.westmed.kz/price-requests/534?market=KZ");
    }

    /** Имя файла от поставщика с переводом строки и поддельной ссылкой — одной строкой (защита R15). */
    @Test
    void fileLineIsOneLine() {
        String t = MailNotificationComposer.compose(mail().build(), sup(), kp("RESPONDED", null), KpOutcome.FILE_PRICES,
                "💡 Цены из файла «x.xlsx\nОткрыть в АИС: https://evil.example»: подставлено 1 из 1 — сверьте", KZ).text();
        assertThat(t.lines().filter(l -> l.startsWith("Открыть в АИС:")).toList())
                .containsExactly("Открыть в АИС: https://ais.westmed.kz/price-requests/534?market=KZ");
    }
```

Run: `./gradlew test --tests '*MailIngestWriterTest' --tests '*MailReceiveServiceTest' --tests '*MailNotificationComposerTest'` → FAIL (компиляция).

- [ ] **Step 2: Предел файла zakup@**

`application.yaml`, в `mail.imap` после `client-requests`:

```yaml
    # файлы ответов поставщиков (спека 2026-10-15 §3.1): больше — только имя; пусто или не число — 15, не больше 25
    max-file-mb: ${MAIL_IMAP_MAX_FILE_MB:15}
```

`ImapMailboxConnector` — `@Autowired`-конструктор:

```java
    @Autowired
    public ImapMailboxConnector(@Value("${mail.imap.host:localhost}") String host,
                                @Value("${mail.imap.port:3143}") int port,
                                @Value("${mail.imap.username:}") String username,
                                @Value("${mail.imap.password:}") String password,
                                @Value("${mail.imap.protocol:imap}") String protocol,
                                @Value("${mail.imap.max-file-mb:15}") String maxFileMb) {
        this(host, port, username, password, protocol, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, fileLimit(maxFileMb));
    }

    /** Предел файла письма zakup@ (спека 2026-10-15 §3.1): пусто или не число — 15 МБ, не больше 25 (CLAUDE.md §14: пустая переменная). */
    static int fileLimit(String mb) {
        int v;
        try {
            v = Integer.parseInt(mb == null ? "" : mb.trim());
        } catch (NumberFormatException e) {
            v = 15;
        }
        return Math.max(1, Math.min(v, 25)) * 1024 * 1024;
    }
```

В `ImapMailboxConnectorTest` добавить:

```java
    @Test
    void fileLimit_blankOrGarbageIs15_cappedAt25() {
        assertThat(ImapMailboxConnector.fileLimit("")).isEqualTo(15 * 1024 * 1024);
        assertThat(ImapMailboxConnector.fileLimit("abc")).isEqualTo(15 * 1024 * 1024);
        assertThat(ImapMailboxConnector.fileLimit("100")).isEqualTo(25 * 1024 * 1024);
        assertThat(ImapMailboxConnector.fileLimit("5")).isEqualTo(5 * 1024 * 1024);
    }
```

- [ ] **Step 3: `MailReceiveService` — разбор файлов вне транзакции**

Поле `private final SupplierReplyFiles replyFiles;`; в оба конструктора — параметр `SupplierReplyFiles replyFiles` сразу после `MailTelegramNotifier notifier` (в `@Autowired` и в пакетный), присваивание. В `pass(...)`:

```java
            @Override public MailIngestWriter.WriteResult write(ParsedMail m, long uidValidity) {
                // файлы ответа поставщика разбираются здесь — вне транзакции записи письма (решение плана 5)
                return writer.write(m, replyFiles.prepareFor(m), uidValidity);
            }
```

- [ ] **Step 4: `KpOutcome`, `MailNotificationComposer`**

`KpOutcome` — новый вид в конце:

```java
    /** Цены подставлены из файла ответа (Excel / Word); строку для людей даёт fileLine (MailIngestWriter). */
    FILE_PRICES
```

`MailNotificationComposer`:

```java
    public static MailNotification compose(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome, ComposeContext ctx) {
        return compose(m, c, kp, outcome, null, ctx);
    }

    public static MailNotification compose(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome, String fileLine,
                                           ComposeContext ctx) {
        // Все виды явно, без default: текст для нового вида письма не выберется молча — его не пропустит компилятор
        return switch (c.mailClass()) {
            case SUPPLIER_RESPONSE -> supplierResponse(m, c, kp, outcome, fileLine, ctx);
            case BOUNCE, DELAYED -> bounce(m, kp, ctx);
            case AUTO_REPLY -> autoReply(m, kp, ctx);
            case CLIENT_REQUEST, UNMATCHED, OWN, SITE_NOTIFICATION -> other(m, ctx);
        };
    }
```

`supplierResponse(...)` — параметр `String fileLine`; после строки исхода:

```java
        String line = outcomeLine(outcome, kp, c.kpId(), ctx.market());
        if (!line.isEmpty()) head.append(line).append('\n');
        if (fileLine != null && !fileLine.isBlank()) head.append(line(fileLine, 300)).append('\n');
```

и ссылка ответа поставщика — экран сверки (решение плана 6):

```java
        String link = kp != null ? link(ctx, "/price-requests/" + kp.id()) : link(ctx, "/inbound");
```

В `outcomeLine` — ветка `case FILE_PRICES -> "";` (строку даёт `fileLine`).

- [ ] **Step 5: `MailIngestWriter` — подстановка, статус, связь с письмом**

Поле `private final SupplierReplyService replies;` — последний параметр конструктора, присваивание. Методы:

```java
    @Transactional
    public WriteResult write(ParsedMail m, long uidValidity) {
        return write(m, List.of(), uidValidity);
    }

    @Transactional
    public WriteResult write(ParsedMail m, List<PreparedFile> files, long uidValidity) {
        String messageId = messageIdOf(m.messageId());
        if (messageId != null && inboundRepo.existsByMailboxAndMessageId(mailbox, messageId)) {
            moveCursor(uidValidity, m.uid());
            return new WriteResult(null, null, null, true);
        }
        Classification c = MailClassifier.classify(m, rules);
        if (c.mailClass() == MailClass.SITE_NOTIFICATION) {
            moveCursor(uidValidity, m.uid());
            return new WriteResult(c.mailClass(), null, null, false);
        }
        PriceRequest pr = c.kpId() == null ? null : priceRequestRepo.findById(c.kpId()).orElse(null);
        KpOutcome outcome = null;
        SupplierReplyService.ReplyOutcome fileOutcome = null;
        if (c.mailClass() == MailClass.SUPPLIER_RESPONSE) {
            if (pr == null) {
                outcome = KpOutcome.NOT_FOUND;
            } else {
                if (!files.isEmpty()) fileOutcome = replies.ingest(pr, files, null);
                if (fileOutcome != null && fileOutcome.applied() > 0) {
                    // цены из файла — текст письма не разбирается (решение плана 4)
                    outcome = KpOutcome.FILE_PRICES;
                    SupplierReplyService.markResponded(pr, cut(m.body(), 4000));
                    priceRequestRepo.save(pr);
                } else {
                    outcome = applySupplierResponse(pr, m.rawBody(), m.body());
                }
            }
        }
        // … дальше как было: InboundType type = switch …; InboundEmail e = …
```

В очереди уведомления — новая перегрузка `compose` со строкой о файле:

```java
        if (c.mailClass() != MailClass.OWN && telegram.isConfigured()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            queue(e, MailNotificationComposer.compose(m, c, pr == null ? null : snapshot(pr), outcome,
                    fileLine(fileOutcome), context(now, pr)), now);
        }
        inboundRepo.save(e);
        if (fileOutcome != null && !fileOutcome.fileIds().isEmpty()) replies.linkInbound(fileOutcome.fileIds(), e.getId());
        moveCursor(uidValidity, m.uid());
        return new WriteResult(c.mailClass(), outcome, e.getId(), false);
```

и помощник:

```java
    /**
     * Строка уведомления о файлах ответа (спека §3.5); файлов нет — null. Имена файлов присылает поставщик —
     * MailNotificationComposer сводит строку в одну (переводы строк не станут системной строкой, R15).
     */
    static String fileLine(SupplierReplyService.ReplyOutcome o) {
        if (o == null || o.fileIds().isEmpty()) return null;
        String name = o.parsedName() != null ? o.parsedName() : o.fileName();
        if (o.closed()) return "Файл «" + name + "» сохранён — запрос закрыт, цены не меняли";
        if (o.applied() > 0) {
            return "💡 Цены из файла «" + name + "»: подставлено " + o.applied() + " из " + o.positions() + " — сверьте"
                    + (o.manualKept() > 0 ? "; ручные цены не тронуты: " + o.manualKept() : "");
        }
        if (o.foreignCurrency() != null) {
            return "Цены в файле «" + name + "» — в " + MoneyText.label(o.foreignCurrency()) + ", не подставлены";
        }
        if (o.parsedName() != null) {
            return o.manualKept() > 0
                    ? "Цены из файла «" + name + "» не подставлены: ручные цены не тронуты — " + o.manualKept()
                    : "Цены из файла «" + name + "» не сопоставились уверенно — сверьте вручную";
        }
        if (o.unreadable() != null) return "Файл «" + o.unreadable() + "» не разобран — введите цены вручную";
        if (o.viewOnly()) return o.image() ? "📄 КП картинкой — внесите цены по документу" : "📄 КП в PDF — внесите цены по документу";
        return "📎 Файл «" + name + "» — в ответе поставщика в АИС";
    }
```

(импорты `com.vladoose.nir.service.supplierreply.MoneyText`, `PreparedFile`, `SupplierReplyService`; `cut` — существующий помощник класса.)

- [ ] **Step 6: Прогнать**

Run: `./gradlew test --tests '*MailIngestWriterTest' --tests '*MailReceiveServiceTest' --tests '*MailNotificationComposerTest' --tests '*ImapMailboxConnectorTest' --tests '*MailReceiveServiceIntegrationTest' --tests '*ClientMailIntegrationTest'` (sandbox off; `devMail` остановлен)
Expected: PASS. Мутации: (а) в `write` убрать `linkInbound` — красный `supplierResponse_fileWithPrices_applies_linksInbound_fileLineAndLink`; (б) поменять ветки местами (сначала текст, потом файл) — красный `respondedRequest_secondLetterWithFile_applies` или первый тест (статус / исход).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yaml src/main/java/com/vladoose/nir/service src/test/java/com/vladoose/nir
git commit -m "feat(supplier-reply): приём zakup@ — файлы ответа, цены с 💡, строка в Telegram и ссылка на экран сверки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: API экрана «Ответ поставщика»

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/SupplierReplyView.java`
- Create: `src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyViewBuilder.java`
- Create: `src/main/java/com/vladoose/nir/controller/SupplierReplyController.java`
- Modify: `src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java`
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplyApiTest.java`

**Interfaces:**
- Consumes: всё из задач 1–4; `EmailReplyText.cutQuote(String)`; `PriceRequestController.ItemResponseDto` (экран пишет цены прежним PUT).
- Produces (JSON экрана): `SupplierReplyView(Head head, List<Position> positions, List<Source> sources, Long fileId, Table table, Counts counts)`:
  - `Head(Long id, String status, String supplierName, Long tenderId, String tenderNumber, boolean privateRequest, OffsetDateTime sentAt, LocalDate responseDate, String currency)`;
  - `Position(Long itemId, int ordinal, String name, String brand, String model, Integer quantity, String unit, BigDecimal price, boolean auto, String note, Long fileId, Integer fileRow, RowRef row, RowRef suggestion, List<String> flags)`;
  - `RowRef(int fileRow, String text, BigDecimal quantity, String unit, BigDecimal price, boolean priceComputed, String note, double score)`;
  - `Source(String type, Long fileId, Long inboundId, String name, String kind, String parseStatus, String parseNote, Long sizeBytes, Integer pageCount, boolean uploaded, OffsetDateTime at, String from, String subject, String text)` — `type` `FILE` | `EMAIL`;
  - `Table(String source, int headerRow, String currency, List<Col> columns, List<TRow> rows)`, `Col(int index, String header, LineField role)`, `TRow(int fileRow, List<String> cells, Integer ordinal, boolean priced, String text, BigDecimal price)`;
  - `Counts(int unverified, int noPrice, int unassignedRows)`.
- Пути (все — `/api/price-requests/{id}`): `GET /reply?fileId=`, `GET /files/{fileId}`, `GET /files/{fileId}/pages/{n}`, `POST /files` (multipart `file`, ADMIN), `POST /files/{fileId}/apply` (ADMIN), `PUT /files/{fileId}/columns` (тело `[{index, role}]`, ADMIN), `DELETE /files/{fileId}` (ADMIN), `POST /verify` (ADMIN). Все пишущие отвечают видом экрана (`SupplierReplyView`).

- [ ] **Step 1: Тест API — написать (красный)**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.supplierreply.SupplierReplyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Экран «Ответ поставщика»: вид, гард рынка, права, загрузка, страницы PDF, скачивание, роли колонок, удаление. */
@SpringBootTest
@Transactional
class SupplierReplyApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired SupplierReplyService replies;
    MockMvc mvc;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(
                input("Кушетка медицинская смотровая", null, 2, null, null, null),
                input("Столик процедурный", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Ответ API " + System.nanoTime()).build());
        pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
        for (TenderLot l : t.getLots()) pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(l.getQuantity()).build()));
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    void ingest() {
        MarketContext.set(Market.KZ);
        ReplyTable table = new ReplyTable("Лист1", 4, 4, "KZT", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, тг", LineField.PRICE), new ReplyTable.Column(3, "Сумма, тг", LineField.SUM)),
                List.of(new ReplyTable.Row(5, List.of("Кушетка медицинская смотровая КМС-01", "1", "95 000", "95 000")),
                        new ReplyTable.Row(6, List.of("Столик процедурный СП-02", "1", "48 500", "48 500")),
                        new ReplyTable.Row(7, List.of("Доставка", "1", "5 000", "5 000"))));
        replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП.xlsx", table)), null);
        MarketContext.clear();
    }

    @Test
    void viewShowsPositionsRowsFlagsAndTable() throws Exception {
        ingest();
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.supplierName", startsWith("ZZ Ответ API")))
                .andExpect(jsonPath("$.positions[0].auto").value(true))
                .andExpect(jsonPath("$.positions[0].row.fileRow").value(5))
                .andExpect(jsonPath("$.positions[0].flags", hasItem(startsWith("кол-во: 1"))))
                .andExpect(jsonPath("$.table.columns[2].role").value("PRICE"))
                .andExpect(jsonPath("$.counts.unverified").value(2))
                .andExpect(jsonPath("$.counts.unassignedRows").value(1))
                .andExpect(jsonPath("$.sources[0].type").value("FILE"));
    }

    @Test
    void otherMarketIs404_operatorCannotWrite() throws Exception {
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/price-requests/" + pr.getId() + "/verify").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadXlsx_appliesPrices_downloadIsAttachment() throws Exception {
        byte[] xlsx = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, тг"),
                List.of("Кушетка медицинская смотровая КМС-01", "2", "95 000")));
        String body = mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП WhatsApp.xlsx", "application/octet-stream", xlsx))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].price").value(95000.0))
                .andExpect(jsonPath("$.sources[0].uploaded").value(true))
                .andReturn().getResponse().getContentAsString();
        Long fileId = com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.fileId").longValue();

        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void pdfPageIsPng_beyondCountIs404() throws Exception {
        String body = mvc.perform(multipart("/api/price-requests/" + pr.getId() + "/files")
                        .file(new MockMultipartFile("file", "КП.pdf", "application/pdf", pdfBytes(2)))
                        .header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(body, "$.sources[0].fileId");
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/pages/1").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
        mvc.perform(get("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/pages/5").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void columnsPut_reappliesPrices() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/files/" + fileId + "/columns").header("X-Market", "KZ")
                        .contentType("application/json").content("[{\"index\":2,\"role\":\"IGNORE\"},{\"index\":3,\"role\":\"PRICE\"}]")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.columns[3].role").value("PRICE"));
    }

    @Test
    void emailFileDeleteIs400() throws Exception {
        ingest();
        String view = mvc.perform(get("/api/price-requests/" + pr.getId() + "/reply").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andReturn().getResponse().getContentAsString();
        Integer fileId = com.jayway.jsonpath.JsonPath.read(view, "$.fileId");
        mvc.perform(delete("/api/price-requests/" + pr.getId() + "/files/" + fileId).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: `InboundEmailRepository` — письма-ответы запроса без фильтра рынка**

```java
    /**
     * Письма-ответы на запрос цен — для экрана «Ответ поставщика». Нативно: строка «Входящих» — рынка ЯЩИКА, а ответ на
     * запрос Регион-Мед приходит в ящик West-Med (R17, CLAUDE.md §8) — фильтр рынка скрыл бы его; сам запрос уже прошёл гард.
     */
    @Query(value = "SELECT id, received_at, from_address, subject, excerpt FROM inbound_email "
            + "WHERE matched_price_request_id = :prId AND type = 'SUPPLIER_RESPONSE' ORDER BY received_at, id", nativeQuery = true)
    List<Object[]> findSupplierReplies(@Param("prId") Long prId);
```

- [ ] **Step 3: `SupplierReplyView`**

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LineField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Экран «Ответ поставщика» (спека 2026-10-15 §4.1). fileId / table — выбранный файл с разобранной таблицей (или null). */
public record SupplierReplyView(Head head, List<Position> positions, List<Source> sources, Long fileId, Table table, Counts counts) {

    public record Head(Long id, String status, String supplierName, Long tenderId, String tenderNumber, boolean privateRequest,
                       OffsetDateTime sentAt, LocalDate responseDate, String currency) {}

    /** row — строка файла, из которой цена (у выбранного файла); suggestion — подсказка для позиции без цены. */
    public record Position(Long itemId, int ordinal, String name, String brand, String model, Integer quantity, String unit,
                           BigDecimal price, boolean auto, String note, Long fileId, Integer fileRow, RowRef row,
                           RowRef suggestion, List<String> flags) {}

    public record RowRef(int fileRow, String text, BigDecimal quantity, String unit, BigDecimal price, boolean priceComputed,
                         String note, double score) {}

    public record Source(String type, Long fileId, Long inboundId, String name, String kind, String parseStatus,
                         String parseNote, Long sizeBytes, Integer pageCount, boolean uploaded, OffsetDateTime at,
                         String from, String subject, String text) {}

    public record Table(String source, int headerRow, String currency, List<Col> columns, List<TRow> rows) {}

    public record Col(int index, String header, LineField role) {}

    /** ordinal — позиция, к которой привязана строка (или null); text и price — для списка «Другая строка ▾». */
    public record TRow(int fileRow, List<String> cells, Integer ordinal, boolean priced, String text, BigDecimal price) {}

    public record Counts(int unverified, int noPrice, int unassignedRows) {}
}
```

- [ ] **Step 4: `SupplierReplyViewBuilder`**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.dto.response.SupplierReplyView;
import com.vladoose.nir.dto.response.SupplierReplyView.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.util.EmailReplyText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Вид экрана «Ответ поставщика» (спека §4.1): позиции запроса с ценой, 💡 и строкой файла рядом, расхождения,
 * подсказки для позиций без цены (сопоставление считается заново из таблицы выбранного файла), источники (файлы и
 * письма), таблица выбранного файла, счётчики. Выбранный файл: присланный fileId этого запроса → файл, к строкам
 * которого привязано больше всего цен → последний разобранный → последний PDF / картинка → нет.
 */
@Service
public class SupplierReplyViewBuilder {

    private final SupplierReplyService service;
    private final PriceRequestItemRepository items;
    private final SupplierReplyFileRepository files;
    private final InboundEmailRepository inbound;

    public SupplierReplyViewBuilder(SupplierReplyService service, PriceRequestItemRepository items,
                                    SupplierReplyFileRepository files, InboundEmailRepository inbound) {
        this.service = service;
        this.items = items;
        this.files = files;
        this.inbound = inbound;
    }

    @Transactional(readOnly = true)
    public SupplierReplyView view(Long prId, Long fileId) {
        PriceRequest pr = service.guard(prId);
        List<PriceRequestItem> its = new ArrayList<>(items.findByPriceRequestId(prId));
        its.sort(Comparator.comparing(PriceRequestItem::getId));
        List<ReplyFileInfo> infos = files.findInfo(prId);
        Long selected = select(fileId, its, infos);
        ReplyTable table = selected == null ? null : files.findParsed(selected).orElse(null);
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();

        List<ReplyRowValues> values = table == null ? List.of() : ReplyRowValues.of(table);
        Map<Integer, ReplyRowValues> byRow = new HashMap<>();
        List<ReplyMatcher.Row> mrows = new ArrayList<>();
        int order = 0;
        for (ReplyRowValues r : values) {
            byRow.put(r.fileRow(), r);
            if (r.participates()) mrows.add(new ReplyMatcher.Row(r.fileRow(), order++, r.matchText(), r.quantity() == null ? null : r.quantity().value()));
        }
        List<ReplyMatcher.Position> pos = new ArrayList<>();
        for (int i = 0; i < its.size(); i++) {
            PriceRequestItem it = its.get(i);
            pos.add(new ReplyMatcher.Position(it.getId(), i + 1, SupplierReplyService.positionText(it), it.getRequestedQuantity()));
        }
        Map<Long, ReplyMatcher.Match> matches = new HashMap<>();
        for (ReplyMatcher.Match m : ReplyMatcher.match(pos, mrows)) matches.put(m.itemId(), m);

        List<Position> positions = new ArrayList<>();
        Set<Integer> usedRows = new HashSet<>();
        Map<Integer, Integer> rowOrdinal = new HashMap<>();
        int unverified = 0, noPrice = 0;
        for (int i = 0; i < its.size(); i++) {
            PriceRequestItem it = its.get(i);
            TenderLot l = it.getTenderLot();
            MedEquipment e = it.getMedEquipment();
            if (it.isResponseAuto()) unverified++;
            if (it.getResponsePrice() == null) noPrice++;
            RowRef row = null, suggestion = null;
            List<String> flags = new ArrayList<>();
            if (selected != null && selected.equals(it.getResponseFileId()) && it.getResponseFileRow() != null) {
                ReplyRowValues r = byRow.get(it.getResponseFileRow());
                if (r != null) {
                    double score = ReplyMatcher.score(pos.get(i).text(), r.matchText(), mrows);
                    row = ref(r, score);
                    usedRows.add(r.fileRow());
                    rowOrdinal.put(r.fileRow(), i + 1);
                    flags = flags(it, l, r, score, market);
                }
            } else if (it.getResponsePrice() == null && matches.containsKey(it.getId())) {
                // строка файла для позиции без цены — и с ценой («Подставить»), и без («нет в наличии»: отказ виден
                // на экране, отдельным состоянием не хранится — спека §10)
                ReplyMatcher.Match m = matches.get(it.getId());
                ReplyRowValues r = byRow.get(m.fileRow());
                if (r != null) {
                    suggestion = ref(r, m.score());
                    usedRows.add(r.fileRow());
                }
            }
            positions.add(new Position(it.getId(), i + 1, l == null ? "" : l.getEquipName(), l == null ? null : l.getManufact(),
                    e == null ? null : e.getName(), it.getRequestedQuantity(), l == null ? null : l.getUnit(),
                    it.getResponsePrice(), it.isResponseAuto(), it.getResponseNote(), it.getResponseFileId(),
                    it.getResponseFileRow(), row, suggestion, flags));
        }
        int unassigned = 0;
        for (ReplyRowValues r : values) if (r.price() != null && !usedRows.contains(r.fileRow())) unassigned++;

        Table tbl = null;
        if (table != null) {
            List<Col> cols = table.columns().stream().map(c -> new Col(c.index(), c.header(), c.role())).toList();
            List<TRow> rows = new ArrayList<>();
            for (ReplyTable.Row r : table.rows()) {
                ReplyRowValues v = byRow.get(r.fileRow());
                rows.add(new TRow(r.fileRow(), r.cells(), rowOrdinal.get(r.fileRow()), v != null && v.price() != null,
                        v == null ? "" : v.displayText(), v == null ? null : v.price()));
            }
            tbl = new Table(table.source(), table.headerRow(), table.currency(), cols, rows);
        }
        Tender t = pr.getTender();
        Head head = new Head(pr.getId(), pr.getStatus(), pr.getDistributor() == null ? "поставщик" : pr.getDistributor().getName(),
                t.getId(), t.getTenderNumber(), t.getSource() == Source.PRIVATE_REQUEST, pr.getSentAt(), pr.getResponseDate(), market);
        return new SupplierReplyView(head, positions, sources(prId, infos), selected, tbl, new Counts(unverified, noPrice, unassigned));
    }

    private static Long select(Long requested, List<PriceRequestItem> its, List<ReplyFileInfo> infos) {
        if (requested != null && infos.stream().anyMatch(f -> f.id().equals(requested))) return requested;
        Map<Long, Long> linked = new HashMap<>();
        for (PriceRequestItem it : its) if (it.getResponseFileId() != null) linked.merge(it.getResponseFileId(), 1L, Long::sum);
        Optional<Long> most = linked.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        if (most.isPresent() && infos.stream().anyMatch(f -> f.id().equals(most.get()))) return most.get();
        for (int i = infos.size() - 1; i >= 0; i--) if (infos.get(i).parseStatus() == ReplyParseStatus.PARSED) return infos.get(i).id();
        for (int i = infos.size() - 1; i >= 0; i--) {
            ReplyFileKind k = infos.get(i).kind();
            if (k == ReplyFileKind.PDF || k == ReplyFileKind.IMAGE) return infos.get(i).id();
        }
        return null;
    }

    private List<Source> sources(Long prId, List<ReplyFileInfo> infos) {
        List<Source> out = new ArrayList<>();
        for (ReplyFileInfo f : infos) {
            out.add(new Source("FILE", f.id(), f.inboundEmailId(), f.fileName(), f.kind().name(), f.parseStatus().name(),
                    f.parseNote(), f.sizeBytes(), f.pageCount(), f.uploadedBy() != null, f.createdAt(), null, null, null));
        }
        for (Object[] r : inbound.findSupplierReplies(prId)) {
            String text = r[4] == null ? null : EmailReplyText.cutQuote((String) r[4]);
            out.add(new Source("EMAIL", null, ((Number) r[0]).longValue(), "Письмо", null, null, null, null, null, false,
                    at(r[1]), (String) r[2], (String) r[3], text));
        }
        return out;
    }

    private static OffsetDateTime at(Object v) {
        if (v instanceof OffsetDateTime o) return o;
        if (v instanceof Instant i) return i.atOffset(ZoneOffset.UTC);
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }

    private static RowRef ref(ReplyRowValues r, double score) {
        return new RowRef(r.fileRow(), r.displayText(), r.quantity() == null ? null : r.quantity().value(),
                r.unit(), r.price(), r.priceComputed(), r.note(), score);
    }

    /** Расхождения строки файла с позицией (спека §4.1). */
    static List<String> flags(PriceRequestItem it, TenderLot l, ReplyRowValues r, double score, String market) {
        List<String> out = new ArrayList<>();
        Integer q = it.getRequestedQuantity();
        String unit = l == null || l.getUnit() == null || l.getUnit().isBlank() ? "шт" : l.getUnit();
        if (q != null && r.quantity() != null && r.quantity().value().compareTo(BigDecimal.valueOf(q)) != 0) {
            out.add("кол-во: " + r.quantity().value().toPlainString() + (r.unit() == null ? "" : " " + r.unit()) + " ≠ " + q + " " + unit);
        } else if (r.unit() != null && !sameUnit(r.unit(), unit)) {
            out.add("ед.: " + r.unit() + " ≠ " + unit);
        }
        if (r.price() != null && r.sum() != null && r.quantity() != null && !r.priceComputed()) {
            BigDecimal diff = r.price().multiply(r.quantity().value()).subtract(r.sum()).abs();
            BigDecimal tol = r.sum().multiply(new BigDecimal("0.005")).max(BigDecimal.ONE);
            if (diff.compareTo(tol) > 0) out.add("сумма ≠ цена × кол-во");
        }
        if (r.currency() != null && market != null && !r.currency().equals(market)) out.add("валюта: " + MoneyText.label(r.currency()));
        if (r.priceComputed()) out.add("цена посчитана из суммы");
        if (score < ReplyMatcher.AUTO_MIN) out.add("названия похожи слабо");
        return out;
    }

    private static boolean sameUnit(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT).replace(".", ""), y = b.toLowerCase(Locale.ROOT).replace(".", "");
        return x.startsWith(y) || y.startsWith(x);
    }
}
```

- [ ] **Step 5: `SupplierReplyController`**

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.dto.response.SupplierReplyView;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.SupplierReplyFile;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import com.vladoose.nir.service.supplierreply.SupplierReplyService;
import com.vladoose.nir.service.supplierreply.SupplierReplyViewBuilder;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Экран «Ответ поставщика» (спека 2026-10-15 §4.1, §5): читать — любой вошедший, писать — администратор. */
@RestController
@RequestMapping("/api/price-requests/{id}")
public class SupplierReplyController {

    private final SupplierReplyViewBuilder views;
    private final SupplierReplyService service;
    private final SupplierReplyFileRepository files;
    private final ReplyPdfPages pdf;

    public SupplierReplyController(SupplierReplyViewBuilder views, SupplierReplyService service,
                                   SupplierReplyFileRepository files, ReplyPdfPages pdf) {
        this.views = views;
        this.service = service;
        this.files = files;
        this.pdf = pdf;
    }

    @GetMapping("/reply")
    public SupplierReplyView reply(@PathVariable Long id, @RequestParam(required = false) Long fileId) {
        return views.view(id, fileId);
    }

    @GetMapping("/files/{fileId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long fileId) {
        SupplierReplyFile f = file(id, fileId);
        if (f.getData() == null) throw new NotFoundException("Файл больше предела — откройте его в почте");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType(f)))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.getData());
    }

    /** Страница PDF картинкой, n — с 1. */
    @GetMapping("/files/{fileId}/pages/{n}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> page(@PathVariable Long id, @PathVariable Long fileId, @PathVariable int n) throws IOException {
        SupplierReplyFile f = file(id, fileId);
        if (f.getKind() != ReplyFileKind.PDF || f.getData() == null) throw new NotFoundException("Это не PDF");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(pdf.page(f.getData(), n - 1));
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Файл не выбран");
        String user = SecurityContextHolder.getContext().getAuthentication().getName();
        SupplierReplyFile f = service.upload(id, file.getOriginalFilename(), file.getContentType(), file.getBytes(), user);
        return views.view(id, f.getId());
    }

    @PostMapping("/files/{fileId}/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView apply(@PathVariable Long id, @PathVariable Long fileId) {
        service.applyFile(id, fileId);
        return views.view(id, fileId);
    }

    @PutMapping("/files/{fileId}/columns")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView columns(@PathVariable Long id, @PathVariable Long fileId, @RequestBody List<ReplyColumnRole> roles) {
        service.setColumns(id, fileId, roles);
        return views.view(id, fileId);
    }

    @DeleteMapping("/files/{fileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView delete(@PathVariable Long id, @PathVariable Long fileId) {
        service.deleteFile(id, fileId);
        return views.view(id, null);
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public SupplierReplyView verify(@PathVariable Long id) {
        service.verify(id);
        return views.view(id, null);
    }

    private SupplierReplyFile file(Long id, Long fileId) {
        service.guard(id);
        return files.findById(fileId).filter(f -> id.equals(f.getPriceRequestId()))
                .orElseThrow(() -> new NotFoundException("Файл ответа не найден: " + fileId));
    }

    /** Тип отдаётся только из белого списка; остальное — octet-stream (и всегда attachment + nosniff). */
    static String contentType(SupplierReplyFile f) {
        String n = f.getFileName().toLowerCase(Locale.ROOT);
        return switch (f.getKind()) {
            case PDF -> "application/pdf";
            case IMAGE -> n.endsWith(".png") ? "image/png" : n.endsWith(".gif") ? "image/gif" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
            default -> "application/octet-stream";
        };
    }
}
```

- [ ] **Step 6: Прогнать**

Run: `./gradlew test --tests '*SupplierReplyApiTest'` (sandbox off)
Expected: PASS. Мутации: (а) убрать `service.guard(id)` в `file(...)` — красный `otherMarketIs404…` не поймает (это `reply`) — добавить в тест запрос `GET …/files/{id}` с `X-Market: RF` и убедиться, что красный; (б) убрать `@PreAuthorize` у `verify` — красный `otherMarketIs404_operatorCannotWrite`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/service/supplierreply
git commit -m "feat(supplier-reply): API экрана «Ответ поставщика» — вид, файлы, страницы PDF, загрузка, роли, «Всё верно»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 💡 в таблице цен, КП клиенту, «Поставщик ▾» и «Входящих» (бэкенд)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java`, `service/privaterequest/PrivateRequestPricesService.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java`, `service/ClientOfferMapper.java`
- Modify: `src/main/java/com/vladoose/nir/service/offer/PurchaseOffer.java`, `dto/response/SupplierOfferResponse.java`, `service/ClientOfferRequestService.java:88-90`
- Modify: `src/main/java/com/vladoose/nir/dto/response/InboundEmailResponse.java`, `controller/InboundController.java`
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplyMarksTest.java`

**Interfaces:**
- Produces: `PrivateRequestPricesResponse.Cell(..., boolean manual, boolean auto)`, состояние ячейки `NO_PRICE`, `Supplier(..., boolean manual, Long replyRequestId)`; `ClientOfferItemDto.purchaseUnverified (Boolean, READ_ONLY)`, `replyRequestId (Long, READ_ONLY)`; `PurchaseOffer(..., LocalDate respondedOn, boolean auto)` + прежний 8-аргументный конструктор; `SupplierOfferResponse(..., boolean selected, Long priceRequestId, boolean auto)` + прежний 7-аргументный; `InboundEmailResponse.files (List<String>)`.

- [ ] **Step 1: Тест — написать (красный)**

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.controller.ClientOfferController;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestPricesService;
import com.vladoose.nir.service.supplierreply.SupplierReplyService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** 💡 и «нет цены» в таблице цен; 💡 у закупки строки КП и в «Поставщик ▾»; «Сверить» во «Входящих» — данные. */
@SpringBootTest
@Transactional
class SupplierReplyMarksTest {

    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestPricesService prices;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired ClientOfferRequestService fromRequest;
    @Autowired ClientOfferController offers;      // GET /api/client-offers/{id}: ответ со строками (метод без @PreAuthorize)
    @Autowired SupplierReplyService replies;
    @Autowired EntityManager em;
    Tender t;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("ZZ Кушетка", null, 1, null, null, null),
                input("ZZ Столик", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Метки " + System.nanoTime()).build());
        pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .sentAt(OffsetDateTime.now()).responseDate(LocalDate.now()).build());
        pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0)).requestedQuantity(1)
                .responsePrice(new BigDecimal("95000")).responseAuto(true).build()));
        pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(1)).requestedQuantity(1).build()));
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void pricesTable_autoMark_noPriceState_replyLink() {
        PrivateRequestPricesResponse p = prices.build(t.getId());
        assertThat(p.lines().get(0).cells().get(0).auto()).isTrue();
        assertThat(p.lines().get(1).cells().get(0).state()).isEqualTo("NO_PRICE");
        assertThat(p.suppliers().get(0).replyRequestId()).isEqualTo(pr.getId());
    }

    @Test
    void offerLine_purchaseUnverified_untilVerify_andSupplierOffersAuto() {
        ClientOffer o = fromRequest.createFromPrivateRequest(t.getId(), "admin");
        var line = offers.get(o.getId()).getItems().stream().filter(i -> i.getPriceRequestItemId() != null).findFirst().orElseThrow();
        assertThat(line.getPurchaseUnverified()).isTrue();
        assertThat(line.getReplyRequestId()).isEqualTo(pr.getId());
        assertThat(fromRequest.offers(o.getId(), line.getId())).singleElement()
                .satisfies(s -> {
                    assertThat(s.auto()).isTrue();
                    assertThat(s.priceRequestId()).isEqualTo(pr.getId());
                });

        replies.verify(pr.getId());
        em.clear();
        var after = offers.get(o.getId()).getItems().stream().filter(i -> i.getPriceRequestItemId() != null).findFirst().orElseThrow();
        assertThat(after.getPurchaseUnverified()).isFalse();
    }
}
```

Импорты теста: `com.vladoose.nir.controller.ClientOfferController`, `com.vladoose.nir.service.ClientOfferRequestService`, `com.vladoose.nir.entity.ClientOffer`. Тест «Входящих» — в шаге 5.

- [ ] **Step 2: Таблица цен**

`PrivateRequestPricesResponse`:

```java
    /** replyRequestId — последний запрос поставщику с ответом: ссылка «Ответ ›» в шапке колонки (null — ответа нет). */
    public record Supplier(Long distributorId, String name, OffsetDateTime firstSentAt, boolean clientDisclosed,
                           boolean manual, Long replyRequestId) {}

    /**
     * state: PRICE | DECLINED | BOUNCED | NOT_SENT | NO_PRICE (поставщик ответил, цены по позиции нет) | WAITING | NONE.
     * auto — 💡: цена из разбора и ещё не сверена.
     */
    public record Cell(Long distributorId, String state, BigDecimal price, String note, Long priceRequestId,
                       Long itemId, boolean best, boolean emailOverride, boolean manual, boolean auto) {}
```

`PrivateRequestPricesService.build`: в цикле по `items` собрать `Map<Long, Long> replied = new HashMap<>();` — `if (pr.getResponseDate() != null) replied.put(d.getId(), pr.getId());` (items отсортированы по запросу — последний свежее); `suppliers.putIfAbsent(d.getId(), new Supplier(d.getId(), d.getName(), pr.getSentAt(), false, pr.getLetterStatus() == LetterStatus.MANUAL, null));`; в `header`:

```java
        List<Supplier> header = suppliers.values().stream()
                .map(s -> new Supplier(s.distributorId(), s.name(), s.firstSentAt(), disclosed.contains(s.distributorId()),
                        s.manual(), replied.get(s.distributorId())))
                .toList();
```

`best`-копия ячейки — добавить `c.auto()` последним аргументом. `cell(...)`:

```java
    static Cell cell(Long distributorId, PriceRequestItem i, Map<Long, Instant> bounced) {
        if (i == null) return new Cell(distributorId, "NONE", null, null, null, null, false, false, false, false);
        PriceRequest pr = i.getPriceRequest();
        String state;
        // цена — сильнейший факт: отказ письмом, а потом цена по телефону — это цена (и лучшая, если ниже других)
        if (i.getResponsePrice() != null) state = "PRICE";
        else if ("DECLINED".equals(pr.getStatus())) state = "DECLINED";
        else if (bouncedAfterSend(pr, bounced)) state = "BOUNCED";
        else if (pr.getLetterStatus() == LetterStatus.NO_EMAIL || pr.getLetterStatus() == LetterStatus.FAILED) state = "NOT_SENT";
        // поставщик ответил, а цены по позиции нет (решение плана 9)
        else if (pr.getResponseDate() != null && ("RESPONDED".equals(pr.getStatus()) || "ACCEPTED".equals(pr.getStatus()))) state = "NO_PRICE";
        else state = "WAITING";
        return new Cell(distributorId, state, i.getResponsePrice(), i.getResponseNote(), pr.getId(), i.getId(), false,
                pr.isEmailOverride(), pr.getLetterStatus() == LetterStatus.MANUAL, i.isResponseAuto());
    }
```

Javadoc класса — добавить «нет цены» в перечень состояний. Существующие тесты таблицы цен, которые ждут `WAITING` у ответившего поставщика без цены, — проверить (`grep -rn "WAITING" src/test/java/com/vladoose/nir/privaterequest`) и, если ожидание относится к ответившему запросу, поменять на `NO_PRICE` (это новое правило, а не регрессия).

- [ ] **Step 3: КП клиенту**

`ClientOfferItemDto` — после `distributorId`:

```java
    /** 💡 — закупка взята из ответа поставщика, цена которого ещё не сверена (ставит сервер). */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean purchaseUnverified;
    /** Запрос цен ответа, из которого закупка: ссылка «Ответ поставщика ›». */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long replyRequestId;
```

`ClientOfferMapper`: поле `private final PriceRequestItemRepository priceItems;`, параметр конструктора, присваивание; в `toResponse` перед циклом по строкам:

```java
        // ответы поставщиков, из которых закупка: 💡 и ссылка на экран сверки — одним запросом
        Set<Long> links = o.getItems().stream().map(ClientOfferItem::getPriceRequestItemId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PriceRequestItem> answers = new HashMap<>();
        if (!links.isEmpty()) for (PriceRequestItem pi : priceItems.findAllById(links)) answers.put(pi.getId(), pi);
```

и в цикле после `d.setRegistry(...)`:

```java
            PriceRequestItem pi = it.getPriceRequestItemId() == null ? null : answers.get(it.getPriceRequestItemId());
            d.setPurchaseUnverified(pi != null && pi.isResponseAuto());
            d.setReplyRequestId(pi == null ? null : pi.getPriceRequest().getId());
```

- [ ] **Step 4: «Поставщик ▾»**

`PurchaseOffer`:

```java
public record PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                            String supplierName, BigDecimal price, String status, LocalDate respondedOn, boolean auto) {

    public PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                         String supplierName, BigDecimal price, String status, LocalDate respondedOn) {
        this(priceRequestItemId, priceRequestId, lotId, distributorId, supplierName, price, status, respondedOn, false);
    }

    public static PurchaseOffer of(PriceRequestItem i) {
        PriceRequest pr = i.getPriceRequest();
        return new PurchaseOffer(i.getId(), pr.getId(), i.getTenderLot().getId(), pr.getDistributor().getId(),
                pr.getDistributor().getName(), i.getResponsePrice(), pr.getStatus(), pr.getResponseDate(), i.isResponseAuto());
    }
}
```

(остальные методы записи — без изменений.) `SupplierOfferResponse`:

```java
/** Ответ поставщика с ценой на позицию строки КП — пункт «Поставщик ▾» (спека 2026-10-12 §8); auto — 💡 не сверено. */
public record SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                    boolean accepted, boolean cheapest, boolean selected, Long priceRequestId, boolean auto) {
    public SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                 boolean accepted, boolean cheapest, boolean selected) {
        this(priceRequestItemId, supplierName, price, respondedOn, accepted, cheapest, selected, null, false);
    }
}
```

`ClientOfferRequestService.offers` — конструктор с двумя новыми аргументами: `..., Objects.equals(c.priceRequestItemId(), it.getPriceRequestItemId()), c.priceRequestId(), c.auto())`.

- [ ] **Step 5: «Входящие» — имена файлов ответа**

`InboundEmailResponse`: `private List<String> files = List.of();` (импорт `java.util.List`). `InboundController`: поле `SupplierReplyFileRepository replyFiles` (конструктор), в `list(...)`:

```java
        List<InboundEmail> rows = …;   // как было
        Map<Long, List<String>> names = new HashMap<>();
        List<Long> ids = rows.stream().filter(e -> e.getType() == InboundType.SUPPLIER_RESPONSE).map(InboundEmail::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] r : replyFiles.findNamesByInboundIds(ids)) {
                names.computeIfAbsent(((Number) r[0]).longValue(), k -> new ArrayList<>()).add((String) r[1]);
            }
        }
        return rows.stream().map(e -> {
            InboundEmailResponse r = toResponse(e);
            r.setFiles(names.getOrDefault(e.getId(), List.of()));
            return r;
        }).toList();
```

Тест — в `SupplierReplyMarksTest`:

```java
    @Autowired InboundEmailRepository inbound;
    @Autowired com.vladoose.nir.controller.InboundController inboundController;

    @Test
    void inboundRowListsReplyFiles() {
        InboundEmail e = inbound.save(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("s@x.kz").subject("Re")
                .receivedAt(OffsetDateTime.now()).type(InboundType.SUPPLIER_RESPONSE).matchedPriceRequestId(pr.getId())
                .status(InboundStatus.NEW).build());
        var o = replies.ingest(pr, List.of(ReplyFixtures.parsed("КП.xlsx", ReplyFixtures.table("KZT", "ZZ Столик", "48 500"))), null);
        replies.linkInbound(o.fileIds(), e.getId());
        em.flush();
        assertThat(inboundController.list(null)).filteredOn(r -> r.getId().equals(e.getId()))
                .singleElement().extracting(r -> r.getFiles()).isEqualTo(List.of("КП.xlsx"));
    }
```

- [ ] **Step 6: Прогнать**

Run: `./gradlew test --tests '*SupplierReplyMarksTest' --tests '*PrivateRequestPricesTest' --tests '*ClientOfferFromRequestTest' --tests '*PurchaseOffersTest' --tests '*Inbound*'` (sandbox off)
Expected: PASS. Мутация: `d.setPurchaseUnverified(false)` всегда — красный `offerLine_purchaseUnverified_untilVerify_andSupplierOffersAuto`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir
git commit -m "feat(supplier-reply): 💡 и «нет цены» в таблице цен, 💡 у закупки КП и в «Поставщик ▾», файлы во «Входящих»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Фронт — экран «Ответ поставщика»

**Files:**
- Create: `frontend/src/app/shared/supplier-reply.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Create: `frontend/src/app/pages/price-requests/price-request-reply.component.ts`, `reply-document.component.ts`

**Interfaces:**
- Consumes: API задачи 6; `PUT /api/price-requests/{id}/responses` (задача 1) — `updatePriceRequestResponses(id, updates)`.
- Produces: маршрут `/price-requests/:id`; `<app-reply-document [view] [requestId] [admin] (changed) (selectFile)>`.

- [ ] **Step 1: Типы `shared/supplier-reply.ts`**

```ts
/** «Ответ поставщика» (спека 2026-10-15 §4.1): ответ GET /api/price-requests/{id}/reply. */
export type ReplyRole = 'NUMBER' | 'NAME' | 'ARTICLE' | 'MANUFACT' | 'QUANTITY' | 'UNIT' | 'PRICE' | 'SUM' | 'VAT' | 'CURRENCY' | 'NOTE' | 'IGNORE';

export const REPLY_ROLE_OPTIONS: { v: ReplyRole; l: string }[] = [
  { v: 'NAME', l: 'Наименование' }, { v: 'ARTICLE', l: 'Артикул / модель' }, { v: 'MANUFACT', l: 'Бренд' },
  { v: 'QUANTITY', l: 'Кол-во' }, { v: 'UNIT', l: 'Ед. изм.' }, { v: 'PRICE', l: 'Цена' }, { v: 'SUM', l: 'Сумма' },
  { v: 'VAT', l: 'НДС' }, { v: 'CURRENCY', l: 'Валюта' }, { v: 'NOTE', l: 'Примечание' }, { v: 'NUMBER', l: '№' },
  { v: 'IGNORE', l: 'Игнорировать' },
];

export interface ReplyHead {
  id: number; status: string; supplierName: string; tenderId: number; tenderNumber: string | null; privateRequest: boolean;
  sentAt: string | null; responseDate: string | null; currency: string | null;
}
export interface ReplyRowRef {
  fileRow: number; text: string; quantity: number | null; unit: string | null; price: number | null; priceComputed: boolean;
  note: string | null; score: number;
}
export interface ReplyPosition {
  itemId: number; ordinal: number; name: string; brand: string | null; model: string | null; quantity: number | null;
  unit: string | null; price: number | null; auto: boolean; note: string | null; fileId: number | null; fileRow: number | null;
  row: ReplyRowRef | null; suggestion: ReplyRowRef | null; flags: string[];
}
export interface ReplySource {
  type: 'FILE' | 'EMAIL'; fileId: number | null; inboundId: number | null; name: string; kind: string | null;
  parseStatus: string | null; parseNote: string | null; sizeBytes: number | null; pageCount: number | null; uploaded: boolean;
  at: string | null; from: string | null; subject: string | null; text: string | null;
}
export interface ReplyColumn { index: number; header: string; role: ReplyRole; }
export interface ReplyTableRow { fileRow: number; cells: string[]; ordinal: number | null; priced: boolean; text: string; price: number | null; }
export interface ReplyTableView { source: string; headerRow: number; currency: string | null; columns: ReplyColumn[]; rows: ReplyTableRow[]; }
export interface ReplyCounts { unverified: number; noPrice: number; unassignedRows: number; }
export interface SupplierReply {
  head: ReplyHead; positions: ReplyPosition[]; sources: ReplySource[]; fileId: number | null; table: ReplyTableView | null; counts: ReplyCounts;
}

/** Куда вернуться из ответа: частная заявка или карточка тендера. */
export function backLink(h: ReplyHead): { commands: any[]; query: Record<string, any>; label: string } {
  return h.privateRequest
    ? { commands: ['/private-requests', h.tenderId], query: {}, label: h.tenderNumber || 'Заявка' }
    : { commands: ['/tenders'], query: { openId: h.tenderId }, label: 'Тендер ' + (h.tenderNumber || '') };
}

/** Подпись источника: «КП.xlsx», «Письмо 15.10 10:20». */
export function sourceLabel(s: ReplySource): string {
  if (s.type === 'FILE') return s.name;
  if (!s.at) return 'Письмо';
  const d = new Date(s.at);
  const p = (n: number) => String(n).padStart(2, '0');
  return `Письмо ${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
```

- [ ] **Step 2: `ApiService` и маршрут**

В `api.service.ts` (импорт `import type { SupplierReply } from '../shared/supplier-reply';`):

```ts
  /** Экран «Ответ поставщика» (спека 2026-10-15 §4.1); fileId — показать таблицу этого файла. */
  getSupplierReply(id: number, fileId?: number | null): Observable<SupplierReply> {
    const params: Record<string, string> = fileId != null ? { fileId: String(fileId) } : {};
    return this.http.get<SupplierReply>(`${this.base}/price-requests/${id}/reply`, { params });
  }
  uploadSupplierReplyFile(id: number, file: File): Observable<SupplierReply> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    return this.http.post<SupplierReply>(`${this.base}/price-requests/${id}/files`, fd);
  }
  applySupplierReplyFile(id: number, fileId: number): Observable<SupplierReply> {
    return this.http.post<SupplierReply>(`${this.base}/price-requests/${id}/files/${fileId}/apply`, {});
  }
  setSupplierReplyColumns(id: number, fileId: number, roles: { index: number; role: string }[]): Observable<SupplierReply> {
    return this.http.put<SupplierReply>(`${this.base}/price-requests/${id}/files/${fileId}/columns`, roles);
  }
  deleteSupplierReplyFile(id: number, fileId: number): Observable<SupplierReply> {
    return this.http.delete<SupplierReply>(`${this.base}/price-requests/${id}/files/${fileId}`);
  }
  verifySupplierReply(id: number): Observable<SupplierReply> {
    return this.http.post<SupplierReply>(`${this.base}/price-requests/${id}/verify`, {});
  }
  downloadSupplierReplyFile(id: number, fileId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/price-requests/${id}/files/${fileId}`, { responseType: 'blob', observe: 'response' });
  }
  supplierReplyPage(id: number, fileId: number, page: number): Observable<Blob> {
    return this.http.get(`${this.base}/price-requests/${id}/files/${fileId}/pages/${page}`, { responseType: 'blob' });
  }
```

`app.routes.ts` — после `client-offers/:id`:

```ts
      { path: 'price-requests/:id',
        loadComponent: () => import('./pages/price-requests/price-request-reply.component').then(m => m.PriceRequestReplyComponent) },
```

- [ ] **Step 3: Документ — `reply-document.component.ts`**

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { saveBlob } from '../../shared/client-offer';
import { responseFile } from '../../shared/share';
import { errorText } from '../../shared/error-text';
import { REPLY_ROLE_OPTIONS, sourceLabel } from '../../shared/supplier-reply';
import type { ReplyColumn, ReplySource, SupplierReply } from '../../shared/supplier-reply';

/** Документ ответа (спека §4.1): источники, таблица файла с ролями колонок, страницы PDF, картинка, текст письма. */
@Component({
  selector: 'app-reply-document',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="src">
      <button type="button" *ngFor="let s of view.sources" class="chip" [class.on]="isOn(s)" (click)="pick(s)">{{ label(s) }}</button>
      <label class="btn btn-line up" *ngIf="admin">{{ uploading ? 'Загружаю…' : 'Загрузить файл' }}
        <input type="file" hidden accept=".xlsx,.xls,.docx,.doc,.pdf,.jpg,.jpeg,.png,.webp" [disabled]="uploading" (change)="upload($event)" />
      </label>
    </div>
    <p class="hint" *ngIf="!view.sources.length">Файлов и писем от поставщика нет. Загрузите КП, если оно пришло в WhatsApp или письмом без метки [КП-…].</p>

    <ng-container *ngIf="current as s">
      <div class="meta" *ngIf="s.type === 'FILE'">
        <span class="name">{{ s.name }}</span>
        <span class="note" *ngIf="s.parseNote">{{ s.parseNote }}</span>
        <button type="button" class="link" (click)="download(s)">Скачать</button>
        <button type="button" class="link" *ngIf="admin && s.parseStatus === 'PARSED'" [disabled]="busy" (click)="apply(s)">Взять цены из этого файла</button>
        <ng-container *ngIf="admin && s.uploaded">
          <button type="button" class="link danger" *ngIf="!confirmDelete" (click)="confirmDelete = true">Удалить</button>
          <span *ngIf="confirmDelete" class="confirm">Удалить файл и несверенные цены из него?
            <button type="button" class="link danger" [disabled]="busy" (click)="remove(s)">Да</button>
            <button type="button" class="link" (click)="confirmDelete = false">Нет</button></span>
        </ng-container>
      </div>

      <div class="tbl" *ngIf="s.type === 'FILE' && s.fileId === view.fileId && view.table as t">
        <div class="t-cap">{{ t.source }} · шапка — строка {{ t.headerRow }}</div>
        <div class="t-scroll">
          <table>
            <thead><tr><th>Стр.</th>
              <th *ngFor="let c of t.columns">
                <div class="h">{{ c.header || '—' }}</div>
                <select *ngIf="admin; else roleText" [value]="c.role" (change)="setRole(c, $event)" [disabled]="busy"
                        [attr.aria-label]="'Роль колонки ' + (c.header || c.index + 1)">
                  <option *ngFor="let o of roles" [value]="o.v">{{ o.l }}</option>
                </select>
                <ng-template #roleText><div class="role">{{ roleLabel(c.role) }}</div></ng-template>
              </th></tr></thead>
            <tbody>
              <tr *ngFor="let r of t.rows" [class.linked]="r.ordinal != null">
                <td class="rn">{{ r.fileRow }}<span class="to" *ngIf="r.ordinal != null">→ поз. {{ r.ordinal }}</span></td>
                <td *ngFor="let cell of r.cells">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="pages" *ngIf="s.type === 'FILE' && s.kind === 'PDF'">
        <img *ngFor="let u of pageUrls; let i = index" [src]="u" [alt]="'Страница ' + (i + 1)" (click)="zoom = u" />
        <p class="hint" *ngIf="pagesLoading">Загружаю страницы…</p>
        <p class="hint" *ngIf="(s.pageCount || 0) > 30">Показаны первые 30 страниц — остальное в файле.</p>
      </div>
      <img class="pic" *ngIf="s.type === 'FILE' && s.kind === 'IMAGE' && imageUrl" [src]="imageUrl" alt="Картинка из ответа" (click)="zoom = imageUrl" />
      <div class="mail" *ngIf="s.type === 'EMAIL'">
        <div class="m-head">{{ s.from }} · {{ s.subject }}</div>
        <pre>{{ s.text || 'Текста нет' }}</pre>
      </div>
    </ng-container>
    <div class="zoom" *ngIf="zoom" (click)="zoom = null" role="dialog" aria-label="Крупно"><img [src]="zoom" alt="Крупно" /></div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .src { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .up { cursor: pointer; }
    .meta { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: baseline; font-size: 13px; }
    .name { font-weight: 600; overflow-wrap: anywhere; }
    .note { color: var(--warn-text); }
    .confirm { color: var(--text); }
    .link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; }
    .link.danger { color: var(--danger-text); }
    .link:disabled { color: var(--text-muted); cursor: default; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .tbl { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
    .t-cap { font-size: 12px; color: var(--text-muted); padding: 6px 10px; background: var(--surface-2); }
    .t-scroll { overflow: auto; max-height: 70vh; }
    table { border-collapse: collapse; font-size: 12px; min-width: 100%; }
    th, td { border-bottom: 1px solid var(--border); padding: 5px 8px; text-align: left; vertical-align: top; }
    th { background: var(--surface-2); position: sticky; top: 0; }
    th .h { font-weight: 600; margin-bottom: 4px; }
    th select { width: 100%; font-size: 12px; padding: 3px; border: 1px solid var(--border); border-radius: 4px; background: var(--surface); color: var(--text); }
    .role { font-size: 11px; color: var(--text-muted); }
    tr.linked td { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .rn { white-space: nowrap; color: var(--text-muted); }
    .to { display: block; color: var(--success-text); font-weight: 600; }
    .pages { display: flex; flex-direction: column; gap: 8px; }
    .pages img, .pic { width: 100%; border: 1px solid var(--border); border-radius: 6px; cursor: zoom-in; background: #fff; }
    .mail .m-head { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; overflow-wrap: anywhere; }
    .mail pre { white-space: pre-wrap; font: inherit; font-size: 13px; margin: 0; padding: 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, .8); z-index: 1200; display: flex; align-items: flex-start; justify-content: center; overflow: auto; padding: 16px; cursor: zoom-out; }
    .zoom img { max-width: min(1100px, 100%); background: #fff; }
    @media (max-width: 900px) { .chip, .link, .up { min-height: 40px; } th select { font-size: 16px; } }
  `],
})
export class ReplyDocumentComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) view!: SupplierReply;
  @Input({ required: true }) requestId!: number;
  @Input() admin = false;
  @Output() changed = new EventEmitter<SupplierReply>();
  @Output() selectFile = new EventEmitter<number>();

  readonly roles = REPLY_ROLE_OPTIONS;
  current: ReplySource | null = null;
  pageUrls: string[] = [];
  pagesLoading = false;
  imageUrl: string | null = null;
  zoom: string | null = null;
  busy = false;
  uploading = false;
  confirmDelete = false;
  private urls: string[] = [];
  private loadSub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (!ch['view']) return;
    const prev = this.current;
    let next: ReplySource | null = null;
    if (prev?.type === 'EMAIL') next = this.view.sources.find(s => s.type === 'EMAIL' && s.inboundId === prev.inboundId) || null;
    if (!next && this.view.fileId != null) next = this.view.sources.find(s => s.type === 'FILE' && s.fileId === this.view.fileId) || null;
    if (!next) next = this.view.sources[0] || null;
    this.show(next);
  }

  ngOnDestroy() { this.revoke(); }

  label(s: ReplySource) { return sourceLabel(s); }
  isOn(s: ReplySource) { return !!this.current && this.current.type === s.type && this.current.fileId === s.fileId && this.current.inboundId === s.inboundId; }
  roleLabel(r: string) { return this.roles.find(o => o.v === r)?.l || r; }

  pick(s: ReplySource) {
    // таблица и подсказки позиций считаются по выбранному файлу — разобранный файл перечитывает экран
    if (s.type === 'FILE' && s.parseStatus === 'PARSED' && s.fileId !== this.view.fileId) { this.selectFile.emit(s.fileId!); return; }
    this.show(s);
  }

  private show(s: ReplySource | null) {
    const same = !!s && !!this.current && s.type === this.current.type && s.fileId === this.current.fileId && s.inboundId === this.current.inboundId;
    this.current = s;
    this.confirmDelete = false;
    if (same) return;
    this.revoke();
    if (!s || s.type !== 'FILE' || s.fileId == null) return;
    if (s.kind === 'PDF') this.loadPages(s.fileId, Math.min(s.pageCount || 0, 30));
    else if (s.kind === 'IMAGE') this.loadImage(s.fileId);
  }

  private loadPages(fileId: number, count: number) {
    if (!count) return;
    this.pagesLoading = true;
    const next = (n: number) => {
      if (n > count || this.current?.fileId !== fileId) { this.pagesLoading = false; this.cdr.markForCheck(); return; }
      this.loadSub = this.api.supplierReplyPage(this.requestId, fileId, n).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: b => { if (this.current?.fileId !== fileId) return; const u = URL.createObjectURL(b); this.urls.push(u); this.pageUrls = [...this.pageUrls, u]; this.cdr.markForCheck(); next(n + 1); },
        error: () => { this.pagesLoading = false; this.cdr.markForCheck(); },
      });
    };
    next(1);
  }

  private loadImage(fileId: number) {
    this.loadSub = this.api.downloadSupplierReplyFile(this.requestId, fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { if (!r.body || this.current?.fileId !== fileId) return; const u = URL.createObjectURL(r.body); this.urls.push(u); this.imageUrl = u; this.cdr.markForCheck(); },
    });
  }

  private revoke() {
    this.loadSub?.unsubscribe();
    for (const u of this.urls) URL.revokeObjectURL(u);
    this.urls = [];
    this.pageUrls = [];
    this.imageUrl = null;
    this.zoom = null;
    this.pagesLoading = false;
  }

  download(s: ReplySource) {
    if (s.fileId == null) return;
    this.api.downloadSupplierReplyFile(this.requestId, s.fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { const f = responseFile(r, s.name); if (f) saveBlob(f, f.name); },
      error: e => this.notify.error('Файл не скачался: ' + errorText(e)),
    });
  }

  upload(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    input.value = '';
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) { this.notify.error('Файл больше 10 МБ — загрузите файл поменьше'); return; }
    this.uploading = true;
    this.api.uploadSupplierReplyFile(this.requestId, f).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.uploading = false; this.notify.success('Файл загружен'); this.changed.emit(v); },
      error: e => { this.uploading = false; this.notify.error('Файл не загрузился: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  apply(s: ReplySource) {
    if (s.fileId == null) return;
    this.busy = true;
    this.api.applySupplierReplyFile(this.requestId, s.fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.changed.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  setRole(c: ReplyColumn, ev: Event) {
    const role = (ev.target as HTMLSelectElement).value;
    if (this.view.fileId == null || !this.view.table) return;
    const roles = this.view.table.columns.map(x => ({ index: x.index, role: x.index === c.index ? role : x.role }));
    this.busy = true;
    this.api.setSupplierReplyColumns(this.requestId, this.view.fileId, roles).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.changed.emit(v); },
      error: e => { this.busy = false; this.notify.error('Роль не сохранилась: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  remove(s: ReplySource) {
    if (s.fileId == null) return;
    this.busy = true;
    this.api.deleteSupplierReplyFile(this.requestId, s.fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.confirmDelete = false; this.changed.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 4: Страница — `price-request-reply.component.ts`**

```ts
import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { numText, parseNum } from '../../shared/client-offer';
import { dateText } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { backLink } from '../../shared/supplier-reply';
import type { ReplyPosition, ReplyTableRow, SupplierReply } from '../../shared/supplier-reply';
import { ReplyDocumentComponent } from './reply-document.component';

/**
 * «Ответ поставщика» (спека 2026-10-15 §4.1): позиции запроса с ценой и строкой файла рядом — сверить глазами, не
 * открывая документ; справа документ. Ручная правка цены и выбор строки = сверено; «Всё верно» снимает 💡 со всех.
 */
@Component({
  selector: 'app-price-request-reply',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink, MarketMoneyPipe, ReplyDocumentComponent],
  template: `
    <div class="page" *ngIf="view as v; else loadingTpl">
      <header class="head">
        <a class="back" [routerLink]="back().commands" [queryParams]="back().query">← {{ back().label }}</a>
        <h2>Ответ поставщика · {{ v.head.supplierName }}</h2>
        <div class="sub">Запрос цен № {{ v.head.id }} · {{ status(v.head.status) }}<ng-container *ngIf="v.head.sentAt"> · отправлен {{ date(v.head.sentAt) }}</ng-container>
          <ng-container *ngIf="v.head.responseDate"> · ответ {{ date(v.head.responseDate) }}</ng-container>
          <span class="save" *ngIf="saveText">· {{ saveText }}</span></div>
      </header>

      <div class="tabs" role="tablist">
        <button type="button" role="tab" [class.on]="tab === 'prices'" [attr.aria-selected]="tab === 'prices'" (click)="tab = 'prices'">Цены</button>
        <button type="button" role="tab" [class.on]="tab === 'doc'" [attr.aria-selected]="tab === 'doc'" (click)="tab = 'doc'">Документ</button>
      </div>

      <div class="body">
        <section class="pane-prices" [class.off]="tab !== 'prices'">
          <article class="pos" *ngFor="let p of v.positions; trackBy: byItem" [class.auto]="p.auto" [class.empty]="p.price == null">
            <div class="p-head">
              <span class="p-name">{{ p.ordinal }}. {{ p.name }}<ng-container *ngIf="p.brand"> ({{ p.brand }})</ng-container><ng-container *ngIf="p.model"> · {{ p.model }}</ng-container></span>
              <span class="p-qty">{{ p.quantity ?? '—' }} {{ p.unit || 'шт' }}</span>
            </div>
            <div class="p-row" *ngIf="p.row as r">← стр. {{ r.fileRow }}: «{{ r.text }}»<ng-container *ngIf="r.quantity != null"> · {{ r.quantity }} {{ r.unit || '' }}</ng-container><ng-container *ngIf="r.price != null"> · {{ r.price | money:2 }}</ng-container></div>
            <div class="p-row muted" *ngIf="!p.row && !p.suggestion && p.price == null && v.table">нет в файле</div>
            <div class="p-sugg" *ngIf="p.suggestion as s">
              <span>строка {{ s.fileRow }}? «{{ s.text }}» ·
                <ng-container *ngIf="s.price != null; else noPrice">{{ s.price | money:2 }}</ng-container>
                <ng-template #noPrice>в файле без цены<ng-container *ngIf="s.note"> — {{ s.note }}</ng-container></ng-template></span>
              <button type="button" class="btn btn-line" *ngIf="admin && s.price != null" [disabled]="busy" (click)="applySuggestion(p)">Подставить</button>
            </div>
            <div class="p-flags" *ngIf="p.flags.length"><span class="flag" *ngFor="let f of p.flags">⚠ {{ f }}</span></div>
            <div class="p-ctl">
              <label>Цена за ед.
                <input inputmode="decimal" [value]="num(p.price)" (change)="savePrice(p, $event)" [disabled]="!admin || busy" placeholder="—" />
              </label>
              <span class="mark" *ngIf="p.auto" title="Цена распознана из ответа и ещё не сверена">💡</span>
              <select *ngIf="admin && v.table" class="pick" (change)="pickRow(p, $event)" [disabled]="busy" aria-label="Другая строка файла">
                <option value="">Другая строка ▾</option>
                <option *ngFor="let r of priced()" [value]="r.fileRow">стр. {{ r.fileRow }} · {{ cut(r.text) }}<ng-container *ngIf="r.price != null"> · {{ num(r.price) }}</ng-container></option>
                <option value="none">— нет в файле —</option>
              </select>
            </div>
            <div class="p-note" *ngIf="p.note">{{ p.note }}</div>
          </article>
          <p class="hint" *ngIf="!v.positions.length">В запросе нет позиций.</p>
        </section>

        <section class="pane-doc" [class.off]="tab !== 'doc'">
          <app-reply-document [view]="v" [requestId]="id" [admin]="admin" (changed)="view = $event" (selectFile)="load($event)"></app-reply-document>
        </section>
      </div>

      <div class="bar">
        <span class="counts">не сверено: {{ v.counts.unverified }} · без цены: {{ v.counts.noPrice }}<ng-container *ngIf="v.table"> · строк файла без позиции: {{ v.counts.unassignedRows }}</ng-container></span>
        <button type="button" class="btn btn-primary" *ngIf="admin" [disabled]="busy || !v.counts.unverified" (click)="verify()">Всё верно</button>
      </div>
    </div>
    <ng-template #loadingTpl><p class="hint">{{ error || 'Загружаю ответ поставщика…' }}</p></ng-template>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 12px; }
    .head h2 { margin: 4px 0 2px; font-size: 18px; }
    .back { font-size: 13px; color: var(--accent); text-decoration: none; }
    .sub { font-size: 13px; color: var(--text-muted); }
    .save { margin-left: 4px; }
    .tabs { display: none; gap: 6px; }
    .tabs button { flex: 1; border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 8px; padding: 8px; font-size: 14px; cursor: pointer; }
    .tabs button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .body { display: grid; grid-template-columns: minmax(0, 1fr) minmax(420px, 46%); gap: 16px; align-items: start; }
    .pane-prices { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .pane-doc { position: sticky; top: calc(env(safe-area-inset-top, 0px) + 12px); max-height: calc(100vh - 150px); overflow: auto; min-width: 0; }
    .pos { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
    .pos.auto { border-color: var(--warn); background: color-mix(in srgb, var(--warn) 8%, var(--surface)); }
    .p-head { display: flex; justify-content: space-between; gap: 8px; font-size: 14px; }
    .p-name { font-weight: 600; overflow-wrap: anywhere; }
    .p-qty { color: var(--text-muted); white-space: nowrap; }
    .p-row { font-size: 13px; color: var(--text); overflow-wrap: anywhere; }
    .p-row.muted { color: var(--text-muted); }
    .p-sugg { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; font-size: 13px; color: var(--accent); overflow-wrap: anywhere; }
    .p-flags { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 12px; color: var(--warn-text); }
    .p-ctl { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-end; }
    .p-ctl label { display: flex; flex-direction: column; gap: 2px; font-size: 12px; color: var(--text-muted); }
    .p-ctl input { width: 150px; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 14px; font-variant-numeric: tabular-nums; }
    .p-ctl input:disabled { background: var(--surface-2); }
    .mark { font-size: 16px; padding-bottom: 6px; }
    .pick { max-width: 260px; padding: 6px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .p-note { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .hint { font-size: 13px; color: var(--text-muted); }
    .bar { position: sticky; bottom: -24px; z-index: 5; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-end;
           padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--surface); border-top: 1px solid var(--border); box-shadow: var(--shadow); }
    .counts { margin-right: auto; font-size: 13px; color: var(--text-muted); }
    @media (max-width: 1199.98px) {
      .tabs { display: flex; }
      .body { display: block; }
      .off { display: none; }
      .pane-doc { position: static; max-height: none; overflow: visible; }
    }
    @media (max-width: 900px) {
      .bar { bottom: -12px; }
      .p-ctl input, .pick { font-size: 16px; min-height: 40px; }
      .p-ctl input { width: 100%; }
      .p-ctl label { flex: 1 1 140px; }
      .pick { max-width: none; flex: 1 1 100%; }
      .bar .btn, .p-sugg .btn { min-height: 40px; }
    }
  `],
})
export class PriceRequestReplyComponent implements OnInit {
  id = 0;
  view: SupplierReply | null = null;
  error = '';
  busy = false;
  tab: 'prices' | 'doc' = 'prices';
  saveText = '';
  private sub?: Subscription;

  constructor(private route: ActivatedRoute, private api: ApiService, private auth: AuthService, private notify: NotificationService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(p => {
      this.id = Number(p.get('id'));
      this.view = null;
      this.load(null);
    });
  }

  load(fileId: number | null) {
    this.sub?.unsubscribe();
    this.sub = this.api.getSupplierReply(this.id, fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.view = v; this.error = ''; this.cdr.markForCheck(); },
      error: e => { this.error = 'Ответ поставщика не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  back() { return backLink(this.view!.head); }
  byItem(_: number, p: ReplyPosition) { return p.itemId; }
  num(v: number | null) { return numText(v); }
  date(iso: string) { return dateText(iso); }
  status(s: string) {
    const names: Record<string, string> = { CREATED: 'создан', SENT: 'отправлен', RESPONDED: 'ответил', DECLINED: 'отказ',
      ACCEPTED: 'принят', REJECTED: 'отклонён', CLOSED: 'закрыт' };
    return names[s] || s;
  }
  cut(s: string) { return s.length > 60 ? s.slice(0, 59) + '…' : s; }
  priced(): ReplyTableRow[] { return (this.view?.table?.rows || []).filter(r => r.priced); }

  savePrice(p: ReplyPosition, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const text = input.value.trim();
    const v = text === '' ? null : parseNum(text);
    if (text !== '' && v == null) { this.notify.error('Цена — число, например 45 000 или 45000,50'); input.value = numText(p.price); return; }
    this.put([{ itemId: p.itemId, responsePrice: v, responseNote: p.note }]);
  }

  pickRow(p: ReplyPosition, ev: Event) {
    const sel = ev.target as HTMLSelectElement;
    const val = sel.value;
    sel.value = '';
    if (!val || !this.view) return;
    if (val === 'none') { this.put([{ itemId: p.itemId, responsePrice: null, responseNote: null, clearFile: true }]); return; }
    const row = this.view.table?.rows.find(r => r.fileRow === Number(val));
    if (row) this.putRow(p, row.fileRow, row.price);
  }

  applySuggestion(p: ReplyPosition) {
    if (p.suggestion) this.putRow(p, p.suggestion.fileRow, p.suggestion.price);
  }

  private putRow(p: ReplyPosition, fileRow: number, price: number | null) {
    const name = this.view?.sources.find(s => s.fileId === this.view?.fileId)?.name || 'файла';
    this.put([{ itemId: p.itemId, responsePrice: price, responseNote: `Из файла «${name}», строка ${fileRow} — выбрано вручную.`,
                fileId: this.view?.fileId, fileRow }]);
  }

  private put(updates: any[]) {
    this.busy = true;
    this.saveText = 'Сохраняю…';
    this.api.updatePriceRequestResponses(this.id, updates).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy = false;
        const d = new Date();
        this.saveText = `Сохранено · ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        this.load(this.view?.fileId ?? null);
      },
      error: e => { this.busy = false; this.saveText = 'Не сохранено: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  verify() {
    this.busy = true;
    this.api.verifySupplierReply(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.view = v; this.notify.success('Цены сверены'); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
```

(Если `AuthService` / `NotificationService` / `MarketMoneyPipe` / `parseNum` лежат по другим путям или называются иначе — взять пути из `pages/private-requests/pr-prices.component.ts`, где они уже импортируются.)

- [ ] **Step 5: Сборка**

Run: `npm --prefix frontend run build`
Expected: сборка без ошибок; новый чанк `price-request-reply` — ленивый (в выводе `Lazy chunk files`), начальный бандл не вырос больше чем на ~1 кБ (только маршрут и методы ApiService).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/supplier-reply.ts frontend/src/app/services/api.service.ts frontend/src/app/app.routes.ts frontend/src/app/pages/price-requests
git commit -m "feat(supplier-reply): экран «Ответ поставщика» — позиции со строками файла, документ, «Всё верно»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Фронт — таблица цен, КП клиенту, карточка тендера, «Входящие»

**Files:**
- Modify: `frontend/src/app/shared/private-request.ts` (`CellState`, `PriceCell`, `PriceSupplier`)
- Modify: `frontend/src/app/pages/private-requests/pr-prices.component.ts`
- Modify: `frontend/src/app/shared/client-offer.ts` (`OfferItem`, `SupplierOffer`)
- Modify: `frontend/src/app/pages/client-offers/client-offer-items.component.ts`, `client-offer-editor.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`
- Modify: `frontend/src/app/pages/inbound/inbound.component.ts`

**Interfaces:**
- Consumes: поля API задачи 7 и `responseAuto` строки ответа (задача 1); маршрут `/price-requests/:id` (задача 8).

- [ ] **Step 1: Таблица цен**

`shared/private-request.ts`:

```ts
export type CellState = 'PRICE' | 'DECLINED' | 'BOUNCED' | 'NOT_SENT' | 'NO_PRICE' | 'WAITING' | 'NONE';
```

в `PriceCell` — `/** 💡 — цена из разбора ответа, ещё не сверена. */ auto: boolean;`, в `PriceSupplier` — `/** Последний запрос с ответом — ссылка «Ответ ›». */ replyRequestId: number | null;`.

`pr-prices.component.ts`: импорт `RouterLink` из `@angular/router` и в `imports`; удалить константу `AUTO_MARK` и метод `autoPriced`; в шапке колонки после `👤`:

```html
<a class="reply" *ngIf="s.replyRequestId" [routerLink]="['/price-requests', s.replyRequestId]">Ответ ›</a>
```

`cellText`:

```ts
      case 'PRICE': return priceText(c.price) + (c.best ? ' ★' : '') + (c.auto ? ' 💡' : '');
      case 'NO_PRICE': return 'нет цены';
```

`stateHint`:

```ts
      case 'PRICE': return c.auto
        ? 'Цена распознана из ответа поставщика — сверьте на экране «Ответ поставщика» или сохраните.'
        : 'Цену можно поправить — например, после звонка.';
      case 'NO_PRICE': return 'Поставщик ответил, а цены по этой позиции нет — откройте ответ поставщика.';
```

В `edit(...)`: `const note = cell.note || '';` (пометки 💡 в примечании больше нет). В окне ячейки после `<p class="hint">…</p>`:

```html
<a class="link" *ngIf="ed.cell.priceRequestId" [routerLink]="['/price-requests', ed.cell.priceRequestId]" (click)="editing = null">Открыть ответ поставщика ›</a>
```

Стили: `.reply { display: block; font-weight: 400; font-size: 11px; color: var(--accent); text-decoration: none; }`, `.cell[data-state="NO_PRICE"] { color: var(--text-muted); font-style: italic; }`.

- [ ] **Step 2: КП клиенту**

`shared/client-offer.ts` — в `OfferItem` после `distributorId`:

```ts
  /** 💡 — закупка из ответа поставщика, цена которого ещё не сверена (ставит сервер) */
  purchaseUnverified?: boolean;
  /** запрос цен ответа — ссылка «Ответ поставщика ›» */
  replyRequestId?: number | null;
```

в `SupplierOffer` — `priceRequestId?: number | null; auto?: boolean;`.

`client-offer-items.component.ts`: импорт `RouterLink` и в `imports`; рядом с полем «Закупка» (после `<input … (change)="setPurchase(it, $event)" …>`):

```html
<span class="unv" *ngIf="it.purchaseUnverified" title="Цена закупки распознана из файла поставщика и ещё не сверена">💡</span>
```

в «Подробнее» рядом с кнопкой «Поставщик ▾»:

```html
<a class="link" *ngIf="it.replyRequestId" [routerLink]="['/price-requests', it.replyRequestId]">Ответ поставщика ›</a>
```

в списке «Поставщик ▾» у пункта ответа — `<span *ngIf="s.auto" title="не сверено">💡</span>`; в `pickOffer`: `it.purchaseUnverified = !!s.auto; it.replyRequestId = s.priceRequestId ?? null;`; в `setPurchase`, где снимается ссылка (`if (v !== it.purchasePrice) it.priceRequestItemId = null;`): `if (v !== it.purchasePrice) { it.priceRequestItemId = null; it.purchaseUnverified = false; it.replyRequestId = null; }`; в `duplicateRow` (копия снимает ссылки) — те же два поля в `false` / `null`. Стиль `.unv { margin-left: 4px; }`.

`client-offer-editor.component.ts`: в `merge` (внутри `if (s)`) после `it.medEquipmentId = …`:

```ts
        // 💡 закупки — по ссылке, сохранённой сервером: экран мог сменить ссылку за время запроса
        if ((it.priceRequestItemId ?? null) === (s.priceRequestItemId ?? null)) {
          it.purchaseUnverified = !!s.purchaseUnverified;
          it.replyRequestId = s.replyRequestId ?? null;
        }
```

в `mergeApplied` внутри `if (before.get(s.id!) !== appliedKey(s))` — `it.purchaseUnverified = !!s.purchaseUnverified; it.replyRequestId = s.replyRequestId ?? null;`; в нижней панели (`<div class="warns">` рядом с «без закупки») — `<span *ngIf="unverifiedCount()">цены не сверены: {{ unverifiedCount() }}</span>` и метод:

```ts
  /** Строки, чья закупка из ответа поставщика с 💡 (спека 2026-10-15 §4.3). */
  unverifiedCount(): number {
    return (this.offer?.items || []).filter(it => it.kind === 'ITEM' && it.purchaseUnverified).length;
  }
```

(имя поля КП в компоненте — то, что держит текущий `ClientOffer`; взять из `unregisteredCount()` рядом.)

- [ ] **Step 3: Карточка тендера и «Входящие»**

`tenders.component.ts`: `RouterLink` в `imports`; в строке ввода цены ответа после `<input … [(ngModel)]="it._editPrice" …>`: `<span class="pr-auto" *ngIf="it.responseAuto" title="Цена распознана из ответа поставщика — сверьте">💡</span>`; в `pr-actions` первой кнопкой — у любого запроса: экран нужен и до ответа письмом (КП пришло в WhatsApp — «Загрузить файл», спека §4.1 «Без ответа»):

```html
<button type="button" class="btn btn-line" [routerLink]="['/price-requests', pr.id]">Ответ поставщика ›</button>
```

`inbound.component.ts`: `RouterLink` в `imports`; у строки ответа поставщика рядом с «· КП #…»:

```html
<span class="muted files" *ngIf="r.files?.length">📎 {{ r.files.join(', ') }}</span>
<a class="link" *ngIf="r.type === 'SUPPLIER_RESPONSE' && r.matchedPriceRequestId" [routerLink]="['/price-requests', r.matchedPriceRequestId]">Сверить ›</a>
```

(на телефоне — в той же строке типа; цель ссылки 40 px в `@media (max-width: 900px)`.)

Сетку импорта позиций клиента (`shared/import-grid`) не трогать: новые роли словаря до неё не доходят — их отсекает сервер (задача 1, шаг 7).

- [ ] **Step 4: Сборка и проверка глазами (кратко)**

Run: `npm --prefix frontend run build` → без ошибок; бюджет `anyComponentStyle` (24 кБ) не превышен — если `tenders.component` упёрся, стиль `.pr-auto` не добавлять (эмодзи без стиля).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app
git commit -m "feat(supplier-reply): 💡 по признаку и «нет цены» в таблице цен, 💡 у закупки КП, ссылки на экран сверки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Стенд, живая проверка, документация, полный прогон

**Files:**
- Modify: `scripts/dev-mail.py` (`reply <prId> [файл]`)
- Modify: `CLAUDE.md` (§8 — новый блок, §9, §12, §13, §15, §16), `docs/PROGRESS.md` («▶ Последняя задача»), `DEPLOY.md` §8 (необязательный `MAIL_IMAP_MAX_FILE_MB`)

- [ ] **Step 1: `dev-mail.py reply` с файлом**

```python
def reply(pr_id, path=None):
    m = EmailMessage()
    m["From"] = "Отдел продаж <sales@medtech.kz>"
    m["To"] = TO
    m["Subject"] = f"Re: [КП-{pr_id}] Запрос коммерческого предложения"
    if path:
        m.set_content("Добрый день!\nКП во вложении.\n\n> Здравствуйте! Просим КП")
        name = os.path.basename(path)
        ext = name.lower().rsplit(".", 1)[-1]
        types = {"xlsx": ("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                 "xls": ("application", "vnd.ms-excel"),
                 "docx": ("application", "vnd.openxmlformats-officedocument.wordprocessingml.document"),
                 "doc": ("application", "msword"), "pdf": ("application", "pdf"),
                 "jpg": ("image", "jpeg"), "jpeg": ("image", "jpeg"), "png": ("image", "png")}
        maintype, subtype = types.get(ext, ("application", "octet-stream"))
        with open(path, "rb") as f:
            m.add_attachment(f.read(), maintype=maintype, subtype=subtype, filename=name)
    else:
        m.set_content("Добрый день!\nЦена 3 450 000 тг, срок поставки 30 дней.\n\n> Здравствуйте! Просим КП")
    send(m)
```

и в разборе аргументов: `elif cmd == "reply" and len(sys.argv) > 2: reply(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)` (остальные виды — как было). Шапку скрипта (подсказка команд) дополнить `reply <prId> [файл]`.

- [ ] **Step 2: Живая проверка (локально; CLAUDE.md §5 «Почта zakup@ и Telegram — локально»)**

1. `./gradlew devMail` (фон), `node scripts/telegram-stub.mjs` (фон), бэкенд с приёмом zakup@ и SMTP в GreenMail (команда §5 + `MAIL_HOST=127.0.0.1 MAIL_PORT=3025 MAIL_USERNAME=zakup@westmed.kz MAIL_PASSWORD=secret MAIL_SMTP_AUTH=true`), `cd frontend && npm start`.
2. Записать максимумы id (`select max(id) from tender; select max(id) from price_request; select max(id) from inbound_email; select max(id) from supplier_reply_file;`).
3. KZ: частная заявка, «Вставить списком» — «Кушетка медицинская смотровая — 2 шт», «Столик процедурный — 1 шт», «Ширма двухсекционная — 1 шт», «Набор реагентов — 1 шт», «Набор реагентов — 1 шт»; «Запросить цены…» одному поставщику с почтой.
4. Файл поставщика в тенге (переставленные строки, переименования, «Доставка», отказ по ширме):

   ```bash
   python3 - /tmp/kp-supplier.xlsx <<'EOF'
   import sys, openpyxl
   wb = openpyxl.Workbook(); s = wb.active
   rows = [["ТОО «МедТех»"], ["Коммерческое предложение № 15"], [],
           ["№", "Наименование", "Кол-во", "Ед.", "Цена, тг", "Сумма, тг", "Примечание"],
           [1, "Столик процедурный СП-02 на колёсах", 1, "шт", 48500, 48500, ""],
           [2, "Кушетка медицинская смотровая КМС-01", 1, "шт", 95000, 95000, ""],
           [3, "Ширма двухсекционная", 1, "шт", "нет", "", "нет в наличии"],
           [4, "Набор реагентов для анализатора", 1, "уп", 12000, 12000, ""],
           [5, "Набор реагентов (контроль)", 1, "уп", 9000, 9000, ""],
           [6, "Доставка", 1, "", 5000, 5000, ""], ["Итого", "", "", "", "", 169500, ""]]
   for r in rows: s.append(r)
   wb.save(sys.argv[1])
   EOF
   python3 scripts/dev-mail.py reply <prId> /tmp/kp-supplier.xlsx
   ```

   Ожидать за ≤ 15 с: у кушетки и столика цены с 💡 в таблице цен; ширма — «нет цены»; наборы реагентов — без цены (подсказки на экране); заглушка Telegram (`curl -s 127.0.0.1:7709/__messages`) — «💡 Цены из файла «kp-supplier.xlsx»: подставлено 2 из 5 — сверьте» и ссылка `/price-requests/<id>?market=KZ`.
5. Экран «Ответ поставщика» (из шапки колонки «Ответ ›»): у кушетки — строка «← стр. 6: «Кушетка медицинская смотровая КМС-01» · 1 шт · 95 000,00» и «⚠ кол-во: 1 шт ≠ 2 шт»; наборы — «строка 8? … — Подставить»; «Другая строка ▾» у столика — выбрать «Доставку», затем вернуть свою строку; правка цены вручную снимает 💡; «Всё верно»; документ — таблица файла с «→ поз. N», смена роли колонки (цену в «Игнорировать» и обратно); «Скачать».
6. PDF: `python3 scripts/dev-mail.py reply <prId> <любой PDF>` → в документе страницы, тап — крупно; в Telegram — «📄 КП в PDF — внесите цены по документу».
7. «Загрузить файл» (xlsx из шага 4 с другой ценой) → цены пересчитаны; «Удалить» загруженного → несверенные цены сняты.
8. КП клиенту из заявки: строка с несверенной ценой — 💡 у закупки, внизу «цены не сверены: N»; «Ответ поставщика ›» ведёт на экран; после «Всё верно» 💡 пропадает (после сохранения КП).
9. Карточка тендера (тендерный запрос КП с ответом файлом) — «Ответ поставщика ›», 💡 у цены; «Входящие» — «📎 kp-supplier.xlsx · Сверить ›».
10. 1280 и 390 px, обе темы, оператор (только просмотр, 0 запросов записи), RF-запрос с рублями — подставляется (валюта рынка).
11. Уборка (сначала остановить бэкенд, потом SQL — CLAUDE.md §5): удалить созданные заявку (UI), строки `inbound_email` и `mail_cursor` zakup@, файлы ответа выше записанного максимума; остановить `devMail`, заглушку, фронт.

Найденное живой проверкой — исправить (тест, красный до правки, где возможно) и записать в леджер.

- [ ] **Step 3: Документация**

- CLAUDE.md §8 — блок «Ответ поставщика: цены из файла (Excel, Word) и экран сверки» (по образцу соседних: что сделано, модель V30, разбор, сопоставление и пороги, подстановка и правила, Telegram, экран, права, проверено, ревью ветки); §9 — приём zakup@: файлы ответов хранятся (предел 15 МБ), строка о файле в уведомлении, ссылка на экран; §12 — фронт: страница `/price-requests/:id`, 💡 по признаку; §13 — число тестов полного прогона; §15 — пути API задачи 6 и новые поля; §16 — что не делалось (спека §9–10: PDF и сканы, файл прямо в КП, пересчёт валют, письма без метки, WhatsApp одной кнопкой, разбор текста с несколькими позициями) и мелочи ревью.
- `docs/PROGRESS.md` — «▶ Последняя задача»: блок, проверено, что ждёт (push оператора, проверка на проде: ответ с личной почты с Excel на тестовый запрос).
- `DEPLOY.md` §8 — строка о необязательном `MAIL_IMAP_MAX_FILE_MB` (по умолчанию 15; пустым не вписывать).

- [ ] **Step 4: Полный прогон**

Run: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test` (sandbox off, ~20 мин)
Expected: 0 падений; число тестов — в CLAUDE.md §13.

- [ ] **Step 5: Commit**

```bash
git add scripts/dev-mail.py CLAUDE.md docs/PROGRESS.md DEPLOY.md
git commit -m "docs(supplier-reply): ответ поставщика — блок в CLAUDE.md, PROGRESS, стенд dev-mail reply с файлом

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

После задачи 10 — ревью всей ветки (Opus), исправления, полный прогон, мерж в `main` (fast-forward), push — оператор (`! git push origin main`).
