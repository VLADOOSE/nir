# Готовое КП — план реализации

> **Для исполнителя:** исполнение — самому в основной сессии, задача за задачей (superpowers:executing-plans; субагентов не запускать — CLAUDE.md §2–3). Шаги — чекбоксы `- [ ]`. Ledger блока — `.superpowers/sdd/2026-10-19-ready-offer-file/progress.md` (git-ignored).

**Goal:** КП, сделанное вне конструктора (Word, Excel, PDF, картинка), заносится в частную заявку обычным «КП клиенту» с файлом, строками и ценами из файла; АИС сама замечает в переписке сделки наше письмо или сообщение с КП и предлагает занести его или отметить КП конструктора отправленным.

**Architecture:** Готовое КП — `client_offer` с `origin = FILE` и файлом в новой таблице `client_offer_file` (V33). Разбор — тот же `ReplyTableReader`, что у ответов поставщиков, плюс «все таблицы и итог» (`readAll`) и правила строк КП (`OfferFileLines`); позиции заявки — сопоставлением `ReplyMatcher` или из строк КП, закупка — `PurchaseOffers.best`, как «Создать КП клиенту». Подсказка «КП ушло клиенту» — `ReadyOfferHints` по нашим файлам переписки эпизода обращения, в «Дальше» строки этапов.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA / Hibernate 6, Flyway, Apache POI 5.2.5, PDFBox 3, Angular 21 (standalone), JUnit 5 + MockMvc на nirdb.

**Spec:** `docs/superpowers/specs/2026-10-19-ready-offer-file-design.md` (решения при планировании ниже уточняют §4.3 и §6 — спека поправлена тем же коммитом).

## Global Constraints

- Миграция — **V33** (V32 — последняя); схема меняется только миграцией, V1–V32 не трогать (CLAUDE.md §10).
- Записи — только `@PreAuthorize("hasRole('ADMIN')")`; читать и скачивать — любой вошедший; каждый путь по id — гард рынка (404 чужому), заявка — `PrivateRequestService.findById` (только `PRIVATE_REQUEST` своего рынка).
- Новых зависимостей нет (POI, PDFBox, commons-compress уже в сборке).
- Тексты интерфейса и ошибок — русские, словами спеки («Занести готовое КП», «Похоже, КП ушло клиенту…», «Не КП», «Отметить отправленным», «Скачать файл»).
- Файлы с `/api` на фронте — только `HttpClient` blob (§14); результаты промисов — `.then`, не нативный `await` (§14).
- Новое на фронте — только в ленивых маршрутах (`/private-requests/:id`, `/client-offers/:id`); начальный бандл ≤ 1,5 МБ (сейчас 1,38).
- Стили — токены UI-kit, тинты по правилам §12, цели 40 px на телефоне, обе темы; классы компонентов не совпадают с классами kit (`.empty`, `.subtitle`, `.counter`, `.badge`, `.btn`, `.form-actions`, `.field-error`, `.error-banner`, `.xref`) — кроме использования самих классов kit по назначению.
- Каждый коммит кончается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Любой `./gradlew` и `psql` — с выключенной песочницей (`dangerouslyDisableSandbox: true`, §5); перед полным прогоном — остановить `bootRun` и `devMail` (§13).
- Тест красный до правки, после — зелёный; на каждое правило — мутация (сломать → упал ровно свой тест → вернуть копией файла, §14; серию мутаций закончить `./gradlew compileJava`).

## Review Focus

1. **КП-файл больше 500 строк** — предел PUT КП 500 строк (`ClientOfferUpdateRequest.items`): заведи такое КП — автосохранение редактора упрётся в 400 на каждой правке. Ожидание: 400 «В файле больше 500 строк…» до записи. Тест — задача 5, `moreThan500LinesRefusedBeforeWrite`.
2. **Дата отправки файла из переписки — по часам рынка**: письмо в 20:30 UTC — уже следующий день в Уральске; окно иначе подставило бы вчерашнюю дату. Ожидание: кандидат несёт `sentOn` по поясу рынка. Тест — задача 6, `candidatesNewestFirstWithFlagsAndMarketDate`.
3. **Файл переписки пропал или не наш** (чат удалили, сообщение входящее, другой чат, до начала обращения) — между списком и «Занести». Ожидание: 404 «Файл переписки не найден», КП не заводится. Тест — задача 5, `notOurFileIs404`.
4. **Таблица только с колонкой «Сумма»** (цены за единицу нет, есть количество) — ожидание: цена клиенту = сумма / количество, итог по строкам = итогу файла. Тест — задача 3, `sumOnlyTablePriceIsSumDividedByQuantity`.
5. **Строка «Итого» без колонки суммы** («Итого (в т. ч. НДС 5 %: 64 761,90) | 1 360 000») — ожидание: итог файла — наибольшее число строки, а не первое («5»). Тест — задача 2, `totalRowValueFromSumColumnElseLargestNumber`.

## Решения при планировании (где план уточняет спеку)

1. **«Отметить отправленным» — свой путь** `POST /api/private-requests/{id}/offer-hints/{attachmentId}/mark-sent`: время отправки сервер берёт из сообщения сам. `POST /api/client-offers/{id}/status` не меняется; в сервисе — перегрузка `ClientOfferService.setStatus(id, status, sentAt)`. Спека §6 поправлена.
2. **Таблицы файла для просмотра — `GET /api/client-offers/{id}/file/view`**, а в ответе КП — только сведения о файле (`file`: имя, вид, размер, итог разбора, страницы, «ИТОГО»): автосохранение каждые 600 мс не гоняет таблицы туда-обратно. Спека §6 поправлена.
3. **Позиции из строк КП — через `PrivateRequestService.addLines`** (подсказка РУ, предел 500, нумерация): дробное количество становится 1, как на других путях добавления позиций; точное количество — в строке КП. Спека §4.3 поправлена.
4. **PDF самой АИС** в списке файлов переписки и в подсказке узнаётся **по имени файла** («КП № N от ДД.ММ.ГГГГ…»), при занесении — по имени и по тексту документа (первая страница PDF, абзацы и первые строки таблиц Word / Excel): разбирать каждый PDF переписки ради списка — дорого.
5. **Строка без цены и без «включено» — не позиция** (пропускается, даже с количеством: у такой строки нечего записать); раздел (одна надпись во всю строку) остаётся, только если под ним есть позиция или «включено» — иначе таблицы «Наличие разрешительной документации», приклеенные к таблице цен буклета, давали бы пустые разделы.
6. **Расчёт — тот же `ClientOfferCalculator`**, одно условие: у `origin = FILE` цена строки не считается от наценки (строка без ручной цены — без цены, `ItemCalc.NONE`-подобный расчёт со ставкой).
7. **Настройки КП из файла — умолчания «Реквизитов»** (`ClientOfferService.fileBase`): `table_columns` пустым быть не может — автосохранение отклонил бы `OfferSettingsValidator.columns` («Колонка «Наименование» обязательна»). В редакторе эти блоки скрыты.
8. **Строк в файле больше 500 → 400 до записи** (Review Focus 1).
9. **Файлы переписки читаются проекцией без байтов** (`ChatAttachmentRepository.findOurFiles`): у `ChatAttachment.content` нет ленивой загрузки, список из 50 файлов иначе тянул бы десятки МБ; байты — только у выбранного файла при занесении.
10. **Удаление любого КП убирает его события смены статуса** (`DealEventService.forgetOffer`): ссылка «КП № N: клиент согласился» на удалённое КП вела бы на «КП не найдено». У конструктора удаляется по-прежнему только черновик, у КП из файла — в любом статусе.
11. **`ReadyOfferService` — в пакете `com.vladoose.nir.service`** рядом с `ClientOfferRequestService`: пользуется его package-private помощниками (`item`, `link`, `offersByLot`, `recipient`) и `ClientOfferService.checkAmounts` / `fileBase` без расширения видимости; чистые части (разбор, строки, номер и дата, подсказки) — `service/readyoffer`.
12. **Кандидат из переписки несёт `sentOn`** — дату отправки по часам рынка (Review Focus 2): фронт пояса не считает.

## Карта файлов

**Бэкенд — новое:**
- `src/main/resources/db/migration/V33__ready_offer_file.sql` — `client_offer.origin`, необязательный `number`, `client_offer_file`, `chat_attachment.offer_hint_dismissed`.
- `entity/ClientOfferOrigin.java`, `entity/ClientOfferFile.java`.
- `repository/ClientOfferFileRepository.java`.
- `dto/response/ClientOfferFileMeta.java` (сведения о файле в ответе КП), `dto/response/ChatFileRow.java` (файл переписки — проекция), `dto/response/OfferFileCandidate.java` (файл для окна), `dto/response/OfferFileView.java` (таблицы файла).
- `service/offer/OfferLabels.java` — «КП № 24» / «КП от 24.09.2026».
- `service/readyoffer/OfferFileLine.java`, `OfferFileLines.java` (правила строк, ставка НДС), `OfferHeader.java` (номер и дата), `ReadyOfferReader.java` (файл → итог разбора), `ReadyOfferHints.java` (переписка сделки, подсказка, PDF самой АИС).
- `service/ReadyOfferService.java` — занести, кандидаты, «Не КП», «Отметить отправленным», файл КП.
- `controller/ReadyOfferController.java`.

**Бэкенд — правки:** `entity/ClientOffer.java`, `entity/ChatAttachment.java`, `service/supplierreply/ReplyTableReader.java` (`readAll`, итог, `documentLines`), `SupplierReplyFiles.java` / `OoxmlSlim.java` (видимость), `util/PdfTextExtractor.java` (`firstPage`), `service/offer/ClientOfferCalculator.java`, `service/ClientOfferService.java`, `dto/request/ClientOfferUpdateRequest.java`, `service/deal/DealEventService.java` + `repository/DealEventRepository.java`, `repository/ChatAttachmentRepository.java`, `dto/response/DealProgress.java`, `service/privaterequest/PrivateRequestProgressBuilder.java`, `service/privaterequest/PrivateRequestViewBuilder.java` + `dto/response/PrivateRequestOfferRef.java`, `service/ClientOfferMapper.java` + `ClientOfferResponse` / `ClientOfferListItemResponse`, `service/deal/DealTimelineService.java`.

**Фронт — новое:** `pages/private-requests/pr-offer-file-dialog.component.ts`, `pages/client-offers/offer-file-view.component.ts`.

**Фронт — правки:** `shared/client-offer.ts`, `shared/deal.ts`, `shared/private-request.ts`, `services/api.service.ts`, `services/search.service.ts`, `shared/deal-progress.component.ts`, `pages/private-requests/pr-offers.component.ts`, `pages/private-requests/private-request-page.component.ts`, `pages/client-offers/client-offer-editor.component.ts`, `pages/client-offers/client-offer-items.component.ts`, `pages/client-offers/client-offers.component.ts`.

**Прочее:** `scripts/dev-mail.py` (`kp <адрес> <файл>`), CLAUDE.md §7 / §8 / §12 / §15 / §16, `docs/PROGRESS.md`, `DEPLOY.md` (откат V33).

**Тесты — новое:** `clientoffer/ReadyOfferSchemaTest.java`, `clientoffer/OfferLabelsTest.java`, `clientoffer/ClientOfferFileModeTest.java`, `service/supplierreply/ReplyTableReaderAllTest.java`, `service/readyoffer/ReadyOfferFixtures.java`, `service/readyoffer/OfferFileLinesTest.java`, `service/readyoffer/OfferHeaderTest.java`, `service/readyoffer/ReadyOfferReaderTest.java`, `readyoffer/ReadyOfferAttachTest.java`, `readyoffer/ReadyOfferHintsTest.java`, `readyoffer/ReadyOfferApiTest.java`; правка — `clientoffer/ClientOfferCalculatorTest.java`.

---

### Task 1: Схема V33, сущности, подпись КП

**Files:**
- Create: `src/main/resources/db/migration/V33__ready_offer_file.sql`
- Create: `src/main/java/com/vladoose/nir/entity/ClientOfferOrigin.java`
- Create: `src/main/java/com/vladoose/nir/entity/ClientOfferFile.java`
- Create: `src/main/java/com/vladoose/nir/repository/ClientOfferFileRepository.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ClientOfferFileMeta.java`
- Create: `src/main/java/com/vladoose/nir/service/offer/OfferLabels.java`
- Modify: `src/main/java/com/vladoose/nir/entity/ClientOffer.java` (поле `number`, новое поле `origin`)
- Modify: `src/main/java/com/vladoose/nir/entity/ChatAttachment.java` (поле `offerHintDismissed`)
- Test: `src/test/java/com/vladoose/nir/clientoffer/ReadyOfferSchemaTest.java`, `src/test/java/com/vladoose/nir/clientoffer/OfferLabelsTest.java`

**Interfaces:**
- Produces: `enum ClientOfferOrigin { BUILDER, FILE }`; `ClientOffer.getOrigin()/setOrigin()` (умолчание BUILDER), `ClientOffer.getNumber()` может быть `null`; `ClientOfferFile` (поля ниже, builder); `ClientOfferFileRepository.findByOfferId(Long) : Optional<ClientOfferFile>`, `findMetaByOfferIds(Collection<Long>) : List<ClientOfferFileMeta>`; `record ClientOfferFileMeta(Long offerId, String name, ReplyFileKind kind, long sizeBytes, ReplyParseStatus parseStatus, String parseNote, Integer pages, BigDecimal fileTotal, Long chatAttachmentId)`; `ChatAttachment.isOfferHintDismissed()/setOfferHintDismissed(boolean)`; `OfferLabels.of(ClientOffer) : String` («КП № 24» / «КП от 24.09.2026»), `OfferLabels.numberOrDate(ClientOffer) : String` («№ 24» / «от 24.09.2026»).

- [ ] **Step 1: Тесты**

`src/test/java/com/vladoose/nir/clientoffer/OfferLabelsTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.service.offer.OfferLabels;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Подпись КП (спека 2026-10-19 §5.1): у КП из файла номера может не быть — тогда по дате. */
class OfferLabelsTest {

    @Test
    void numberElseDate() {
        ClientOffer o = ClientOffer.builder().number(24).offerDate(LocalDate.of(2026, 9, 24)).build();
        assertThat(OfferLabels.of(o)).isEqualTo("КП № 24");
        assertThat(OfferLabels.numberOrDate(o)).isEqualTo("№ 24");
        o.setNumber(null);
        assertThat(OfferLabels.of(o)).isEqualTo("КП от 24.09.2026");
        assertThat(OfferLabels.numberOrDate(o)).isEqualTo("от 24.09.2026");
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/ReadyOfferSchemaTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.ClientOfferRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V33 (спека 2026-10-19 §5): КП из файла без номера, его файл, отметка «Не КП» у файла переписки. */
@SpringBootTest
@Transactional
class ReadyOfferSchemaTest {

    @Autowired ClientOfferRepository offers;
    @Autowired ClientOfferFileRepository files;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private ClientOffer fileOffer() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setNumber(null);
        o.setOrigin(ClientOfferOrigin.FILE);
        return offers.saveAndFlush(o);
    }

    private ClientOfferFile file(Long offerId) {
        return files.saveAndFlush(ClientOfferFile.builder().offerId(offerId).fileName("КП West-Med.docx").sizeBytes(3)
                .sha256("a".repeat(64)).content(new byte[]{1, 2, 3}).kind(ReplyFileKind.WORD)
                .parseStatus(ReplyParseStatus.PARSED).fileTotal(new BigDecimal("370860.00")).build());
    }

    @Test
    void fileOfferWithoutNumberAndItsFileAreStored() {
        ClientOffer o = fileOffer();
        file(o.getId());
        em.clear();

        ClientOffer back = offers.findById(o.getId()).orElseThrow();
        assertThat(back.getNumber()).isNull();
        assertThat(back.getOrigin()).isEqualTo(ClientOfferOrigin.FILE);
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getContent()).containsExactly(1, 2, 3);
        List<ClientOfferFileMeta> meta = files.findMetaByOfferIds(List.of(o.getId()));
        assertThat(meta).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("КП West-Med.docx");
            assertThat(m.kind()).isEqualTo(ReplyFileKind.WORD);
            assertThat(m.fileTotal()).isEqualByComparingTo("370860");
        });
    }

    @Test
    void builderIsDefaultOrigin_fileGoesWithItsOffer() {
        ClientOffer built = offers.saveAndFlush(ClientOfferTestData.newOffer(7));
        assertThat(built.getOrigin()).isEqualTo(ClientOfferOrigin.BUILDER);
        assertThat(jdbc.queryForObject("select origin from client_offer where id = ?", String.class, built.getId()))
                .isEqualTo("BUILDER");

        ClientOffer o = fileOffer();
        file(o.getId());
        jdbc.update("delete from client_offer where id = ?", o.getId());
        assertThat(jdbc.queryForObject("select count(*) from client_offer_file where offer_id = ?", Integer.class, o.getId()))
                .isZero();
    }

    @Test
    void attachmentHintFlagNotNullFalseByDefault() {
        assertThat(jdbc.queryForObject("""
                select is_nullable || ' ' || column_default from information_schema.columns
                where table_name = 'chat_attachment' and column_name = 'offer_hint_dismissed'""", String.class))
                .isEqualTo("NO false");
    }
}
```

- [ ] **Step 2: Прогнать — красные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.OfferLabelsTest' --tests 'com.vladoose.nir.clientoffer.ReadyOfferSchemaTest'` (sandbox off)
Expected: FAIL — компиляция (`OfferLabels`, `ClientOfferOrigin`, `ClientOfferFile`… нет).

- [ ] **Step 3: Миграция**

`src/main/resources/db/migration/V33__ready_offer_file.sql`:

```sql
-- Готовое КП (2026-10-19, спека 2026-10-19-ready-offer-file-design §5): КП, сделанное вне конструктора (Word отца, КП по
-- образцу поставщика), — обычное КП клиенту с файлом документа. origin = FILE; номер у такого КП бывает пустым (в
-- документе его нет), счётчик «исх. №» не тратится; у конструктора номер по-прежнему обязателен (проверка в сервисе).
ALTER TABLE client_offer ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'BUILDER';
ALTER TABLE client_offer ALTER COLUMN number DROP NOT NULL;

-- Файл готового КП — отдельной таблицей: журнал и редактор КП байты не тянут. Один файл на КП, уходит вместе с ним.
-- Не рыночная: читается только через КП с гардом рынка (как client_offer_item).
CREATE TABLE client_offer_file (
    id                  BIGSERIAL PRIMARY KEY,
    offer_id            BIGINT        NOT NULL UNIQUE REFERENCES client_offer (id) ON DELETE CASCADE,
    file_name           VARCHAR(255)  NOT NULL,
    mime_type           VARCHAR(100),
    size_bytes          BIGINT        NOT NULL,
    sha256              VARCHAR(64)   NOT NULL,
    content             BYTEA         NOT NULL,
    kind                VARCHAR(10)   NOT NULL,   -- EXCEL / WORD / PDF / IMAGE (ReplyFileKind)
    parse_status        VARCHAR(12)   NOT NULL,   -- PARSED / NO_TABLE / NOT_PARSED / FAILED / TOO_LARGE (ReplyParseStatus)
    parse_note          TEXT,
    tables              JSONB,                    -- таблицы файла для просмотра (список ReplyTable)
    pages               INTEGER,                  -- страниц PDF
    file_total          NUMERIC(15, 2),           -- «ИТОГО» из файла
    chat_attachment_id  BIGINT REFERENCES chat_attachment (id) ON DELETE SET NULL,   -- файл переписки, откуда взят
    uploaded_by         VARCHAR(100),
    uploaded_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_client_offer_file_attachment ON client_offer_file (chat_attachment_id);

-- «Не КП»: подсказка «КП ушло клиенту» про этот файл переписки больше не показывается
ALTER TABLE chat_attachment ADD COLUMN offer_hint_dismissed BOOLEAN NOT NULL DEFAULT false;
```

- [ ] **Step 4: Сущности, репозиторий, подпись**

`src/main/java/com/vladoose/nir/entity/ClientOfferOrigin.java`:

```java
package com.vladoose.nir.entity;

/** Откуда КП клиенту: BUILDER — конструктор АИС; FILE — готовый документ, занесённый файлом (спека 2026-10-19). */
public enum ClientOfferOrigin { BUILDER, FILE }
```

В `ClientOffer.java` — заменить

```java
    @Column(nullable = false)
    private Integer number;
```

на

```java
    /** «Исх. №»; у КП из файла бывает пустым — в документе его нет (V33), у конструктора обязателен (сервис). */
    @Column
    private Integer number;

    /** Конструктор или готовый файл (V33, спека 2026-10-19). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ClientOfferOrigin origin = ClientOfferOrigin.BUILDER;
```

В `ChatAttachment.java` — после поля `notStoredReason`:

```java
    /** «Не КП» (V33): подсказка «КП ушло клиенту» про этот файл больше не показывается. */
    @Column(name = "offer_hint_dismissed", nullable = false)
    private boolean offerHintDismissed;
```

`src/main/java/com/vladoose/nir/entity/ClientOfferFile.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Файл готового КП (V33, спека 2026-10-19 §5.2): байты документа, итог разбора, таблицы файла для просмотра, «ИТОГО».
 * Один на КП (offer_id UNIQUE), уходит вместе с КП (ON DELETE CASCADE). Не рыночный — только через КП с гардом рынка.
 * Байты грузятся вместе с сущностью: журнал и ответ КП берут сведения проекцией (ClientOfferFileMeta).
 */
@Entity
@Table(name = "client_offer_file")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientOfferFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offer_id", nullable = false, unique = true)
    private Long offerId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private byte[] content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReplyFileKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 12)
    private ReplyParseStatus parseStatus;

    @Column(name = "parse_note", columnDefinition = "TEXT")
    private String parseNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<ReplyTable> tables;

    private Integer pages;

    @Column(name = "file_total", precision = 15, scale = 2)
    private BigDecimal fileTotal;

    @Column(name = "chat_attachment_id")
    private Long chatAttachmentId;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    @PrePersist
    void onCreate() {
        if (uploadedAt == null) uploadedAt = OffsetDateTime.now();
    }
}
```

`src/main/java/com/vladoose/nir/dto/response/ClientOfferFileMeta.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;

import java.math.BigDecimal;

/** Сведения о файле готового КП — без байтов и таблиц (ответ КП, журнал, лента): спека 2026-10-19 §6. */
public record ClientOfferFileMeta(Long offerId, String name, ReplyFileKind kind, long sizeBytes, ReplyParseStatus parseStatus,
                                  String parseNote, Integer pages, BigDecimal fileTotal, Long chatAttachmentId) {}
```

`src/main/java/com/vladoose/nir/repository/ClientOfferFileRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.entity.ClientOfferFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientOfferFileRepository extends JpaRepository<ClientOfferFile, Long> {

    Optional<ClientOfferFile> findByOfferId(Long offerId);

    /** Сведения без байтов и таблиц — одним запросом на КП сделки / журнала. */
    @Query("""
           select new com.vladoose.nir.dto.response.ClientOfferFileMeta(f.offerId, f.fileName, f.kind, f.sizeBytes,
               f.parseStatus, f.parseNote, f.pages, f.fileTotal, f.chatAttachmentId)
           from ClientOfferFile f where f.offerId in :ids""")
    List<ClientOfferFileMeta> findMetaByOfferIds(@Param("ids") Collection<Long> ids);
}
```

`src/main/java/com/vladoose/nir/service/offer/OfferLabels.java`:

```java
package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.util.DocFormat;

/** Подпись КП в текстах (лента, этапы, «Дальше», события, имена файлов): у КП из файла номера может не быть. */
public final class OfferLabels {

    private OfferLabels() {}

    /** «КП № 24» или «КП от 24.09.2026». */
    public static String of(ClientOffer o) {
        return "КП " + numberOrDate(o);
    }

    /** «№ 24» или «от 24.09.2026». */
    public static String numberOrDate(ClientOffer o) {
        return o.getNumber() != null ? "№ " + o.getNumber() : "от " + DocFormat.date(o.getOfferDate());
    }
}
```

- [ ] **Step 5: Прогнать — зелёные**

Run: тот же, что в Step 2. Expected: PASS (5 тестов).

- [ ] **Step 6: Мутации**
  - Схему мутацией не проверить: Flyway уже применил V33 к nirdb, а правка применённой миграции ломает контрольную сумму. Её держат тесты Step 1 (они красные до миграции).
  - `OfferLabels.numberOrDate`: всегда «№ » + номер → `numberElseDate` падает.
  - Вернуть копией файла, `./gradlew compileJava`.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V33__ready_offer_file.sql src/main/java/com/vladoose/nir/entity/ClientOfferOrigin.java \
  src/main/java/com/vladoose/nir/entity/ClientOfferFile.java src/main/java/com/vladoose/nir/entity/ClientOffer.java \
  src/main/java/com/vladoose/nir/entity/ChatAttachment.java src/main/java/com/vladoose/nir/repository/ClientOfferFileRepository.java \
  src/main/java/com/vladoose/nir/dto/response/ClientOfferFileMeta.java src/main/java/com/vladoose/nir/service/offer/OfferLabels.java \
  src/test/java/com/vladoose/nir/clientoffer/ReadyOfferSchemaTest.java src/test/java/com/vladoose/nir/clientoffer/OfferLabelsTest.java
git commit -m "feat(ready-offer): V33 — КП из файла, его файл, отметка «Не КП»; подпись КП без номера

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Все таблицы документа, итог «ИТОГО», текст документа

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/ReplyTableReader.java`
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/ReplyTableReaderAllTest.java`

**Interfaces:**
- Consumes: существующие `Grid`, `table(...)`, `glue`, `width`, `startsWithHeader`, `joined`, `pad`, `TOTAL`, `MoneyText.amount`.
- Produces: `public record ReplyTableReader.ReadTable(ReplyTable table, BigDecimal total)`; `public static List<ReadTable> readAll(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException`; `public static List<String> documentLines(byte[] content, ReplyFileKind kind) throws IOException`. `read(...)` ведёт себя как раньше.

- [ ] **Step 1: Тест**

`src/test/java/com/vladoose/nir/service/supplierreply/ReplyTableReaderAllTest.java`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.ReplyFileKind;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Готовое КП (спека 2026-10-19 §4.1): все таблицы документа с итогом «ИТОГО»; ответы поставщиков — по-прежнему одна. */
class ReplyTableReaderAllTest {

    /** КП-буклет Аксиона: таблица цен, «Наличие документации» той же ширины без своей шапки, вторая таблица цен. */
    @Test
    void allTablesWithNameAndPrice_continuationGlued_readStillPicksOne() throws Exception {
        XWPFDocument d = new XWPFDocument();
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Стоимость за 1 шт., тенге"),
                List.of("ЭКГ простая комплектация", "1 090 800,00"), List.of("ЭКГ с опцией", "1 317 600,00")));
        ReplyTableReaderTest.table(d, List.of(List.of("Наличие документации в РК", "Наличие документации в РК"),
                List.of("Регистрационное удостоверение", "РК-МТ-5№017777 (бессрочно)")));
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Стоимость за 1 шт., тенге", "Комплект поставки"),
                List.of("Дефибриллятор ДКИ-Н-11", "6 176 520,00", "1) монитор")));
        byte[] b = ReplyTableReaderTest.docx(d);

        List<ReplyTableReader.ReadTable> all = ReplyTableReader.readAll(b, ReplyFileKind.WORD, Map.of());

        assertThat(all).hasSize(2);
        assertThat(all.get(0).table().rows()).extracting(r -> r.cells().get(0)).containsExactly(
                "ЭКГ простая комплектация", "ЭКГ с опцией", "Наличие документации в РК", "Регистрационное удостоверение");
        assertThat(all.get(1).table().rows()).extracting(r -> r.cells().get(0)).containsExactly("Дефибриллятор ДКИ-Н-11");
        assertThat(all).allSatisfy(t -> assertThat(t.total()).isNull());
        assertThat(ReplyTableReader.read(b, ReplyFileKind.WORD, Map.of())).isPresent();
    }

    @Test
    void totalRowValueFromSumColumnElseLargestNumber() throws Exception {
        byte[] withSum = ReplyTableReaderTest.xlsx(List.of(
                List.of("№", "Наименование", "Кол-во", "Цена за ед., тг", "Общая сумма, тг"),
                List.of("1", "Пульсоксиметр", "3", "105 600,00", "316 800,00"),
                List.of("2", "Гигрометр", "4", "13 515,00", "54 060,00"),
                List.of("ИТОГО:", "", "7", "", "370 860,00")));
        ReplyTableReader.ReadTable t = ReplyTableReader.readAll(withSum, ReplyFileKind.EXCEL, Map.of()).get(0);
        assertThat(t.total()).isEqualByComparingTo("370860");
        assertThat(t.table().rows()).hasSize(2);

        // колонки суммы нет: итог — наибольшее число строки, а не первое («5» из «НДС 5 %»)
        byte[] noSum = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"),
                List.of("Центрифуга СМ-6М", "1 360 000"),
                List.of("Итого (в т.ч. НДС 5 %: 64 761,90)", "1 360 000")));
        assertThat(ReplyTableReader.readAll(noSum, ReplyFileKind.EXCEL, Map.of()).get(0).total()).isEqualByComparingTo("1360000");

        byte[] noTotal = ReplyTableReaderTest.xlsx(List.of(List.of("Наименование", "Цена, тг"), List.of("Ширма", "30 000")));
        assertThat(ReplyTableReader.readAll(noTotal, ReplyFileKind.EXCEL, Map.of()).get(0).total()).isNull();
    }

    @Test
    void documentLines_paragraphsThenFirstRowsOfTables() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("Исх № 24 от 17 апреля 2026 г.");
        ReplyTableReaderTest.table(d, List.of(List.of("Наименование", "Цена, тг"), List.of("Ширма", "30 000")));
        List<String> lines = ReplyTableReader.documentLines(ReplyTableReaderTest.docx(d), ReplyFileKind.WORD);
        assertThat(lines.get(0)).isEqualTo("Исх № 24 от 17 апреля 2026 г.");
        assertThat(lines).contains("Наименование Цена, тг", "Ширма 30 000");

        XWPFDocument noTables = new XWPFDocument();
        noTables.createParagraph().createRun().setText("Дата: 24.09.2026 г.");
        assertThat(ReplyTableReader.documentLines(ReplyTableReaderTest.docx(noTables), ReplyFileKind.WORD))
                .containsExactly("Дата: 24.09.2026 г.");
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.supplierreply.ReplyTableReaderAllTest'`
Expected: FAIL — компиляция (`readAll`, `ReadTable`, `documentLines` нет).

- [ ] **Step 3: Реализация в `ReplyTableReader.java`**

1. Рядом с `record Grid(...)`:

```java
    /** Документ: сетки (таблицы Word, листы Excel) и абзацы вне таблиц (у Excel — пусто). */
    record Document(List<Grid> grids, List<String> text) {}

    /** Таблица файла и число её строки «Итого» (null — строки «Итого» нет или числа в ней нет). */
    public record ReadTable(ReplyTable table, BigDecimal total) {}

    /** Таблица из сетки и её итог — общая основа read и readAll. */
    record Parsed(ReplyTable table, BigDecimal total) {}

    /** Сколько первых строк каждой таблицы / листа идёт в текст документа (бланк бывает таблицей). */
    static final int TEXT_ROWS = 10;

    static Document document(byte[] content, ReplyFileKind kind) throws IOException {
        return switch (kind) {
            case EXCEL -> new Document(excel(content), List.of());
            case WORD -> ole2(content) ? doc(content) : docx(content);
            default -> new Document(List.of(), List.of());
        };
    }
```

2. В `read(...)` первые строки заменить на `List<Grid> grids = document(content, kind).grids();` (дальше без изменений).

3. `docx(...)` и `doc(...)` — тип результата `Document`, в конце `return new Document(out, text);` (вместо `return out;`).

4. Переименовать тело `table(Grid, Map)` в `parse(Grid, Map) : Optional<Parsed>`; внутри цикла по строкам — запомнить строку «Итого»:

```java
        List<String> totalRow = null;
        ...
            if (TOTAL.matcher(first).find()) {
                end = r;
                totalRow = cells;
                break;
            }
        ...
        ReplyTable table = new ReplyTable(g.source(), g.firstRow() + best, bestScore, currency, columns, out, from);
        return Optional.of(new Parsed(table, totalRow == null ? null : total(totalRow, columns)));
```

и вернуть прежнее имя обёрткой:

```java
    static Optional<ReplyTable> table(Grid g, Map<String, LineField> learned) {
        return parse(g, learned).map(Parsed::table);
    }

    /** Число строки «Итого»: в колонке суммы, а нет его там — наибольшее число строки («Итого (в т. ч. НДС 5 %…) | 1 360 000»). */
    static BigDecimal total(List<String> cells, List<ReplyTable.Column> columns) {
        for (ReplyTable.Column c : columns) {
            if (c.role() != LineField.SUM || c.index() >= cells.size()) continue;
            BigDecimal v = MoneyText.amount(cells.get(c.index()));
            if (v != null && v.signum() > 0) return v;
        }
        BigDecimal max = null;
        for (String cell : cells) {
            BigDecimal v = MoneyText.amount(cell);
            if (v != null && v.signum() > 0 && (max == null || v.compareTo(max) > 0)) max = v;
        }
        return max;
    }
```

5. Новые публичные методы:

```java
    /**
     * Все таблицы документа с наименованием и ценой (готовое КП, спека 2026-10-19 §4.1) — в порядке документа. Таблица
     * Word той же ширины без своей шапки — продолжение предыдущей (как в read) и отдельной таблицей не считается.
     */
    public static List<ReadTable> readAll(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException {
        List<Grid> grids = document(content, kind).grids();
        List<ReadTable> out = new ArrayList<>();
        for (int i = 0; i < grids.size(); i++) {
            Grid acc = grids.get(i);
            int j = i + 1;
            if (kind == ReplyFileKind.WORD) {
                for (; j < grids.size() && width(grids.get(j)) == width(grids.get(i))
                        && !startsWithHeader(grids.get(j), learned); j++) {
                    acc = glue(acc, grids.get(j));
                }
            }
            Optional<Parsed> p = parse(acc, learned);
            if (p.isEmpty()) continue;
            out.add(new ReadTable(p.get().table(), p.get().total()));
            i = j - 1;   // продолжения — строки этой таблицы
        }
        return out;
    }

    /** Текст документа для номера и даты КП: абзацы Word, затем первые строки каждой таблицы / листа (бланк в таблице). */
    public static List<String> documentLines(byte[] content, ReplyFileKind kind) throws IOException {
        Document d = document(content, kind);
        List<String> out = new ArrayList<>(d.text());
        for (Grid g : d.grids()) {
            for (int r = 0; r < Math.min(g.rows().size(), TEXT_ROWS); r++) {
                String line = joined(g.rows().get(r));
                if (!line.isEmpty()) out.add(line);
            }
        }
        return out;
    }
```

- [ ] **Step 4: Прогнать — зелёный, плюс прежние тесты разбора**

Run: `./gradlew test --tests 'com.vladoose.nir.service.supplierreply.*'`
Expected: PASS (новые 3 + все прежние, включая гейт `SupplierReplyGateTest`).

- [ ] **Step 5: Мутации**
  - в `readAll` убрать склейку (цикл по `j`) → `allTablesWithNameAndPrice…` падает;
  - в `total` брать первое число строки вместо наибольшего → `totalRowValue…` падает на «1 360 000»;
  - `total` всегда `null` → `totalRowValue…` падает.
  Вернуть копией, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/supplierreply/ReplyTableReader.java \
  src/test/java/com/vladoose/nir/service/supplierreply/ReplyTableReaderAllTest.java
git commit -m "feat(ready-offer): все таблицы документа с итогом «ИТОГО» и текст документа для номера и даты

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Строки КП, номер и дата, разбор файла готового КП

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/readyoffer/OfferFileLine.java`
- Create: `src/main/java/com/vladoose/nir/service/readyoffer/OfferFileLines.java`
- Create: `src/main/java/com/vladoose/nir/service/readyoffer/OfferHeader.java`
- Create: `src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferReader.java`
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyFiles.java` (`kind`, `fileName`, `sha256`, `PARTS_MAX` — `public`)
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/OoxmlSlim.java` (класс и `forParsing` — `public`)
- Modify: `src/main/java/com/vladoose/nir/util/PdfTextExtractor.java` (`firstPage`)
- Test: `src/test/java/com/vladoose/nir/service/readyoffer/ReadyOfferFixtures.java`, `OfferFileLinesTest.java`, `OfferHeaderTest.java`, `ReadyOfferReaderTest.java`

**Interfaces:**
- Consumes: `ReplyTableReader.readAll/documentLines/ReadTable` (задача 2), `ReplyRowValues.of`, `MoneyText`, `OfferSettingsValidator.containsRate`, `ReplyPdfPages.pageCount`, `HeaderDictionary.learned`.
- Produces:
  - `record OfferFileLine(ClientOfferItemKind kind, String name, String model, String brand, BigDecimal quantity, String unit, BigDecimal price, String vat)` + `matchText()`;
  - `OfferFileLines.of(List<ReadTable>, String marketCurrency) : OfferFileLines.Lines` где `record Lines(List<OfferFileLine> lines, BigDecimal fileTotal, String foreignCurrency)`; `OfferFileLines.vat(String text, List<BigDecimal> rates) : VatPick` где `record VatPick(boolean found, BigDecimal rate)`;
  - `record OfferHeader(Integer number, LocalDate date)` + `static OfferHeader find(List<String> lines)`, `static final OfferHeader NONE`;
  - `@Component ReadyOfferReader(HeaderDictionary, ReplyPdfPages)` + `Result read(String fileName, String mime, byte[] bytes, String marketCurrency)` где `record Result(ReplyFileKind kind, ReplyParseStatus status, String note, List<ReplyTable> tables, List<OfferFileLine> lines, BigDecimal fileTotal, String foreignCurrency, OfferHeader header, Integer pages)`;
  - `PdfTextExtractor.firstPage(byte[]) : String` (null — не читается).

- [ ] **Step 1: Фикстуры**

`src/test/java/com/vladoose/nir/service/readyoffer/ReadyOfferFixtures.java`:

```java
package com.vladoose.nir.service.readyoffer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Синтетические КП по образцу настоящих (репозиторий публичный — настоящие файлы сюда не кладём). */
public final class ReadyOfferFixtures {

    private ReadyOfferFixtures() {}

    public static final List<String> FATHER_HEADER =
            List.of("№", "Наименование", "Ед. изм.", "Кол-во", "Цена за ед., тг", "НДС", "Общая сумма, тг");

    /** КП «как от 24.09»: «Дата: …», таблица с «ИТОГО». rows — {наименование, кол-во, цена, сумма}. */
    public static byte[] fatherKp(String date, List<String[]> rows, String total) throws Exception {
        XWPFDocument d = new XWPFDocument();
        paragraph(d, "Дата: " + date + " г.");
        List<List<String>> t = new ArrayList<>();
        t.add(FATHER_HEADER);
        int n = 0;
        for (String[] r : rows) t.add(List.of(String.valueOf(++n), r[0], "шт", r[1], r[2], "В том числе НДС 5%", r[3]));
        t.add(List.of("ИТОГО:", "ИТОГО:", "ИТОГО:", "ИТОГО:", "ИТОГО:", "ИТОГО:", total));
        table(d, t);
        return bytes(d);
    }

    public static void paragraph(XWPFDocument d, String text) {
        d.createParagraph().createRun().setText(text);
    }

    public static XWPFTable table(XWPFDocument d, List<List<String>> rows) {
        XWPFTable t = d.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) t.getRow(r).getCell(c).setText(rows.get(r).get(c));
        }
        d.createParagraph();
        return t;
    }

    /** Строка во всю ширину таблицы — объединённая ячейка, как «1 этаж» в КП на консоли «ОЗОН». */
    public static void mergedRow(XWPFTable t, int at, String text, int width) {
        XWPFTableRow row = t.insertNewTableRow(at);
        XWPFTableCell c = row.addNewTableCell();
        c.setText(text);
        c.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(width));
    }

    public static byte[] bytes(XWPFDocument d) throws Exception {
        try (d; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] xlsx(List<List<String>> rows) throws Exception {
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
}
```

- [ ] **Step 2: Тесты строк и номера**

`src/test/java/com/vladoose/nir/service/readyoffer/OfferFileLinesTest.java`:

```java
package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Правила строк готового КП (спека 2026-10-19 §4.2; решение при планировании 5). */
class OfferFileLinesTest {

    static final List<LineField> FATHER_ROLES = List.of(LineField.NUMBER, LineField.NAME, LineField.UNIT, LineField.QUANTITY,
            LineField.PRICE, LineField.VAT, LineField.SUM);

    static ReplyTableReader.ReadTable table(String currency, BigDecimal total, List<String> headers, List<LineField> roles,
                                           List<List<String>> rows) {
        List<ReplyTable.Column> cols = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) cols.add(new ReplyTable.Column(i, headers.get(i), roles.get(i)));
        List<ReplyTable.Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(new ReplyTable.Row(i + 2, rows.get(i)));
        return new ReplyTableReader.ReadTable(new ReplyTable("таблица 1", 1, roles.size(), currency, cols, out), total);
    }

    static List<String> same(String text, int n) {
        String[] a = new String[n];
        Arrays.fill(a, text);
        return List.of(a);
    }

    @Test
    void mergedRowIsSection_notAnItemPricedOne_sectionWithoutItemsDropped() {   // КП «ОЗОН»
        var t = table("KZT", null, ReadyOfferFixtures.FATHER_HEADER, FATHER_ROLES, List.of(
                same("1 этаж", 7),
                List.of("1", "Консоль МК-НД-800", "шт", "2", "2 386 800,00", "В том числе НДС 16%", "4 773 600,00"),
                same("Запас", 7)));
        List<OfferFileLine> lines = OfferFileLines.of(List.of(t), "KZT").lines();
        assertThat(lines).extracting(OfferFileLine::kind).containsExactly(ClientOfferItemKind.SECTION, ClientOfferItemKind.ITEM);
        assertThat(lines.get(0).name()).isEqualTo("1 этаж");
        OfferFileLine item = lines.get(1);
        assertThat(item.name()).isEqualTo("Консоль МК-НД-800");
        assertThat(item.quantity()).isEqualByComparingTo("2");
        assertThat(item.unit()).isEqualTo("шт");
        assertThat(item.price()).isEqualByComparingTo("2386800");
        assertThat(item.vat()).isEqualTo("В том числе НДС 16%");
    }

    @Test
    void includedRowAndItsSectionKept_registrationRowsSkipped() {
        var t = table("KZT", null, List.of("Наименование", "Стоимость за 1 шт., тенге"), List.of(LineField.NAME, LineField.PRICE), List.of(
                List.of("ЭКГ простая комплектация", "1 090 800,00"),
                same("Наличие документации в РК", 2),
                List.of("Регистрационное удостоверение", "РК-МТ-5№017777 (бессрочно)"),
                same("Сервис", 2),
                List.of("Гарантия 12 месяцев", "Включено в стоимость")));
        List<OfferFileLine> lines = OfferFileLines.of(List.of(t), "KZT").lines();
        assertThat(lines).extracting(OfferFileLine::kind)
                .containsExactly(ClientOfferItemKind.ITEM, ClientOfferItemKind.SECTION, ClientOfferItemKind.INCLUDED);
        assertThat(lines).extracting(OfferFileLine::name)
                .containsExactly("ЭКГ простая комплектация", "Сервис", "Гарантия 12 месяцев");
    }

    @Test
    void sumOnlyTablePriceIsSumDividedByQuantity() {
        var t = table(null, new BigDecimal("60000"), List.of("Наименование", "Кол-во", "Сумма, тг"),
                List.of(LineField.NAME, LineField.QUANTITY, LineField.SUM), List.of(List.of("Ширма", "2", "60 000")));
        OfferFileLines.Lines l = OfferFileLines.of(List.of(t), "KZT");
        assertThat(l.lines().get(0).price()).isEqualByComparingTo("30000");
        assertThat(l.fileTotal()).isEqualByComparingTo("60000");
    }

    @Test
    void foreignCurrencyItemsWithoutPrice() {
        var t = table("RUB", null, List.of("Наименование", "Цена, руб."), List.of(LineField.NAME, LineField.PRICE),
                List.of(List.of("Консоль", "940 000")));
        OfferFileLines.Lines l = OfferFileLines.of(List.of(t), "KZT");
        assertThat(l.lines()).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(ClientOfferItemKind.ITEM);
            assertThat(x.price()).isNull();
        });
        assertThat(l.foreignCurrency()).isEqualTo("RUB");
    }

    @Test
    void modelBrandFromOwnColumns_quantityAndUnitDefaults_totalsAcrossTables() {
        var a = table("KZT", new BigDecimal("100"), List.of("Наименование", "Модель", "Производитель", "Цена, тг"),
                List.of(LineField.NAME, LineField.ARTICLE, LineField.MANUFACT, LineField.PRICE),
                List.of(List.of("Центрифуга", "СМ-6М", "ЭЛМИ", "1 360 000")));
        var b = table("KZT", new BigDecimal("50"), List.of("Наименование", "Цена, тг"), List.of(LineField.NAME, LineField.PRICE),
                List.of(List.of("Ротор", "50")));
        OfferFileLines.Lines l = OfferFileLines.of(List.of(a, b), "KZT");
        OfferFileLine c = l.lines().get(0);
        assertThat(c.model()).isEqualTo("СМ-6М");
        assertThat(c.brand()).isEqualTo("ЭЛМИ");
        assertThat(c.quantity()).isEqualByComparingTo("1");
        assertThat(c.unit()).isEqualTo("шт");
        assertThat(l.fileTotal()).isEqualByComparingTo("150");
    }

    @Test
    void vatFromColumnTextOnlyMarketRates() {
        List<BigDecimal> rates = new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null));
        assertThat(OfferFileLines.vat("В том числе НДС 5%", rates)).isEqualTo(new OfferFileLines.VatPick(true, new BigDecimal("5")));
        assertThat(OfferFileLines.vat("16 %", rates).rate()).isEqualByComparingTo("16");
        assertThat(OfferFileLines.vat("Без НДС", rates)).isEqualTo(new OfferFileLines.VatPick(true, null));
        assertThat(OfferFileLines.vat("12%", rates).found()).isFalse();
        assertThat(OfferFileLines.vat("15 085,71", rates).found()).isFalse();   // «Сумма НДС» — не ставка
        assertThat(OfferFileLines.vat(null, rates).found()).isFalse();
    }
}
```

`src/test/java/com/vladoose/nir/service/readyoffer/OfferHeaderTest.java`:

```java
package com.vladoose.nir.service.readyoffer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Номер и дата КП из текста документа и имени файла (спека 2026-10-19 §4.4). */
class OfferHeaderTest {

    static OfferHeader of(String... lines) {
        return OfferHeader.find(List.of(lines));
    }

    @Test
    void formatsOfFatherAndAis() {
        assertThat(of("Исх № 24 от 17 апреля  2026 г.")).isEqualTo(new OfferHeader(24, LocalDate.of(2026, 4, 17)));
        assertThat(of("Исх. № 481 от 07.10.2026 г.")).isEqualTo(new OfferHeader(481, LocalDate.of(2026, 10, 7)));
        assertThat(of("КП № 481 от 07.10.2026 – Клиника.pdf")).isEqualTo(new OfferHeader(481, LocalDate.of(2026, 10, 7)));
        assertThat(of("Коммерческое предложение № 7 от 01.10.2026")).isEqualTo(new OfferHeader(7, LocalDate.of(2026, 10, 1)));
        assertThat(of("Дата: 24.09.2026 г.", "Главному врачу")).isEqualTo(new OfferHeader(null, LocalDate.of(2026, 9, 24)));
    }

    @Test
    void numberOnlyAfterIskhOrKp_badDateIsNone() {
        assertThat(of("ГКП на ПХВ «Городская поликлиника № 5»", "ГКП № 5 от 01.01.2026")).isEqualTo(OfferHeader.NONE);
        assertThat(of("Исх № 3 от 31.02.2026")).isEqualTo(new OfferHeader(3, null));
        assertThat(of("КП West-Med 24.09.2026.docx")).isEqualTo(OfferHeader.NONE);
    }
}
```

- [ ] **Step 3: Тест разбора файла**

`src/test/java/com/vladoose/nir/service/readyoffer/ReadyOfferReaderTest.java`:

```java
package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Файл готового КП → строки, итог, номер и дата (спека 2026-10-19 §4). Словарь заголовков — пустой: правила. */
class ReadyOfferReaderTest {

    final ReadyOfferReader reader = new ReadyOfferReader(null, new ReplyPdfPages());

    ReadyOfferReader.Result read(String name, byte[] b) {
        return reader.read(name, null, b, "KZT", Map.of());
    }

    @Test
    void fatherWordKp_parsedWithTotalAndDate() throws Exception {
        byte[] kp = ReadyOfferFixtures.fatherKp("24.09.2026", List.of(
                new String[]{"Пульсоксиметр QMP-PO70", "3", "105 600,00", "316 800,00"},
                new String[]{"Гигрометр ВИТ-2", "4", "13 515,00", "54 060,00"}), "370 860,00");
        ReadyOfferReader.Result r = read("КП West-Med 24.09.2026.docx", kp);
        assertThat(r.kind()).isEqualTo(ReplyFileKind.WORD);
        assertThat(r.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(r.lines()).extracting(OfferFileLine::name).containsExactly("Пульсоксиметр QMP-PO70", "Гигрометр ВИТ-2");
        assertThat(r.fileTotal()).isEqualByComparingTo("370860");
        assertThat(r.header()).isEqualTo(new OfferHeader(null, LocalDate.of(2026, 9, 24)));
        assertThat(r.tables()).hasSize(1);
    }

    @Test
    void mergedFloorRows_sectionsNotPricedLines() throws Exception {   // КП «ОЗОН»: «1 этаж» во всю ширину
        XWPFDocument d = new XWPFDocument();
        XWPFTable t = ReadyOfferFixtures.table(d, List.of(ReadyOfferFixtures.FATHER_HEADER,
                List.of("1", "Консоль МК-НД-800", "шт", "2", "2 386 800,00", "В том числе НДС 16%", "4 773 600,00"),
                List.of("2", "Консоль МК-ПГ-700", "шт", "1", "1 899 240,00", "В том числе НДС 16%", "1 899 240,00")));
        ReadyOfferFixtures.mergedRow(t, 1, "1 этаж", 7);
        ReadyOfferFixtures.mergedRow(t, 3, "2 этаж", 7);
        ReadyOfferReader.Result r = read("КП ОЗОН.docx", ReadyOfferFixtures.bytes(d));
        assertThat(r.lines()).extracting(OfferFileLine::kind).containsExactly(ClientOfferItemKind.SECTION, ClientOfferItemKind.ITEM,
                ClientOfferItemKind.SECTION, ClientOfferItemKind.ITEM);
        assertThat(r.lines()).extracting(OfferFileLine::name).containsExactly("1 этаж", "Консоль МК-НД-800", "2 этаж", "Консоль МК-ПГ-700");
        BigDecimal sum = r.lines().stream().filter(l -> l.price() != null)
                .map(l -> l.price().multiply(l.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("6672840");
    }

    @Test
    void rublesOnKzMarket_noPricesForeignCurrencyNoted() throws Exception {
        byte[] x = ReadyOfferFixtures.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, руб."), List.of("Консоль", "1", "940 000")));
        ReadyOfferReader.Result r = read("КП.xlsx", x);
        assertThat(r.lines()).singleElement().satisfies(l -> assertThat(l.price()).isNull());
        assertThat(r.foreignCurrency()).isEqualTo("RUB");
    }

    @Test
    void pdfPicturesAndBrokenFiles() throws Exception {
        ReadyOfferReader.Result pdf = read("КП № 481 от 07.10.2026 – Клиника.pdf", ReplyFixtures.pdfBytes(2));
        assertThat(pdf.kind()).isEqualTo(ReplyFileKind.PDF);
        assertThat(pdf.status()).isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(pdf.pages()).isEqualTo(2);
        assertThat(pdf.lines()).isEmpty();
        assertThat(pdf.header()).isEqualTo(new OfferHeader(481, LocalDate.of(2026, 10, 7)));   // из имени файла

        assertThat(read("КП.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1}).kind()).isEqualTo(ReplyFileKind.IMAGE);

        ReadyOfferReader.Result broken = read("КП.docx", "PK\u0003\u0004 не архив".getBytes());
        assertThat(broken.status()).isEqualTo(ReplyParseStatus.FAILED);
        assertThat(broken.note()).contains("не открылся");
    }

    @Test
    void word97Fixture_parsed() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/supplier-reply/price-table.doc")) {
            ReadyOfferReader.Result r = read("price-table.doc", in.readAllBytes());
            assertThat(r.status()).isEqualTo(ReplyParseStatus.PARSED);
            assertThat(r.lines()).isNotEmpty().allSatisfy(l -> assertThat(l.kind()).isEqualTo(ClientOfferItemKind.ITEM));
        }
    }
}
```

- [ ] **Step 4: Прогнать — красные**

Run: `./gradlew test --tests 'com.vladoose.nir.service.readyoffer.*'`
Expected: FAIL — компиляция (`OfferFileLines`, `OfferHeader`, `ReadyOfferReader`… нет).

- [ ] **Step 5: Видимость и первая страница PDF**

- `SupplierReplyFiles`: `static final long PARTS_MAX` → `public static final long PARTS_MAX`; `static ReplyFileKind kind(...)`, `static String fileName(...)`, `static String sha256(...)` → `public static`.
- `OoxmlSlim`: `final class OoxmlSlim` → `public final class OoxmlSlim`; `static byte[] forParsing(...)` → `public static byte[] forParsing(...)`.
- `PdfTextExtractor` — метод:

```java
    /** Текст первой страницы (номер и дата КП — в бланке); нечитаемый или пустой — null. */
    public static String firstPage(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return null;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(1);
            s.setEndPage(1);
            String text = s.getText(doc);
            return (text == null || text.isBlank()) ? null : text;
        } catch (IOException e) {
            return null;
        }
    }
```

- [ ] **Step 6: Строки, номер, разбор**

`src/main/java/com/vladoose/nir/service/readyoffer/OfferFileLine.java`:

```java
package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Строка КП из файла (спека 2026-10-19 §4.2): вид, наименование, модель (артикул), производитель (бренд), количество,
 * единица, цена клиенту за единицу с НДС, как в документе (null — нет или валюта не та), текст колонки НДС.
 */
public record OfferFileLine(ClientOfferItemKind kind, String name, String model, String brand, BigDecimal quantity,
                            String unit, BigDecimal price, String vat) {

    /** Наименование, модель и бренд — то, что сравнивается с позицией заявки. */
    public String matchText() {
        List<String> parts = new ArrayList<>();
        for (String p : new String[]{name, model, brand}) if (p != null && !p.isBlank()) parts.add(p);
        return String.join(" ", parts);
    }
}
```

`src/main/java/com/vladoose/nir/service/readyoffer/OfferFileLines.java`:

```java
package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.offer.OfferSettingsValidator;
import com.vladoose.nir.service.supplierreply.ReplyRowValues;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Строки готового КП из таблиц файла (спека 2026-10-19 §4.2, решение при планировании 5), по порядку правил:
 * одна надпись во всю строку — раздел (остаётся, только если под ним есть позиция или «включено»); цена есть —
 * позиция; цены нет, а в строке «включено» — «включено»; остальное — пропуск. Валюта таблицы не рынка — позиции без цены.
 */
public final class OfferFileLines {

    /** Наименование строки КП — TEXT; длиннее не бывает у настоящих КП, а описание на страницу ломает таблицу. */
    static final int NAME_MAX = 2000;
    static final int UNIT_MAX = 30;
    /** Предел колонки количества NUMERIC(12,3). */
    static final BigDecimal QTY_MAX = new BigDecimal("999999999.999");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*%");
    private static final Pattern BARE = Pattern.compile("^\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*$");

    public record Lines(List<OfferFileLine> lines, BigDecimal fileTotal, String foreignCurrency) {}

    /** Ставка из колонки НДС: found = false — колонки нет, ставка не распознана или её нет в списке рынка. */
    public record VatPick(boolean found, BigDecimal rate) {
        static final VatPick NONE = new VatPick(false, null);
    }

    private OfferFileLines() {}

    public static Lines of(List<ReplyTableReader.ReadTable> tables, String marketCurrency) {
        List<OfferFileLine> out = new ArrayList<>();
        BigDecimal total = null;
        String foreign = null;
        for (ReplyTableReader.ReadTable rt : tables) {
            ReplyTable t = rt.table();
            boolean ours = t.currency() == null || t.currency().equals(marketCurrency);
            if (!ours && foreign == null) foreign = t.currency();
            if (rt.total() != null) total = total == null ? rt.total() : total.add(rt.total());
            out.addAll(table(t, ours));
        }
        return new Lines(out, total, foreign);
    }

    static List<OfferFileLine> table(ReplyTable t, boolean pricesOk) {
        List<ReplyRowValues> values = ReplyRowValues.of(t);
        List<OfferFileLine> out = new ArrayList<>();
        int pending = -1;   // раздел, под которым ещё нет ни позиции, ни «включено»
        for (int i = 0; i < t.rows().size(); i++) {
            List<String> cells = t.rows().get(i).cells();
            Set<String> texts = new LinkedHashSet<>();
            for (String c : cells) if (c != null && !c.isBlank()) texts.add(c.strip());
            if (texts.isEmpty()) continue;
            if (texts.size() == 1 && hasLetter(texts.iterator().next())) {
                if (pending >= 0) out.remove(pending);
                out.add(new OfferFileLine(ClientOfferItemKind.SECTION, cut(texts.iterator().next(), NAME_MAX),
                        null, null, null, null, null, null));
                pending = out.size() - 1;
                continue;
            }
            ReplyRowValues v = values.get(i);
            if (v.name() == null) continue;
            if (v.price() != null) {
                out.add(item(v, pricesOk));
                pending = -1;
            } else if (included(cells)) {
                out.add(new OfferFileLine(ClientOfferItemKind.INCLUDED, cut(v.name(), NAME_MAX), null, null, null, null, null, null));
                pending = -1;
            }
        }
        if (pending >= 0) out.remove(pending);
        return out;
    }

    private static OfferFileLine item(ReplyRowValues v, boolean pricesOk) {
        BigDecimal q = v.quantity() == null || v.quantity().value().signum() <= 0
                || v.quantity().value().compareTo(QTY_MAX) > 0 ? BigDecimal.ONE : v.quantity().value();
        String unit = v.unit() == null || v.unit().isBlank() ? "шт" : cut(v.unit().strip(), UNIT_MAX);
        return new OfferFileLine(ClientOfferItemKind.ITEM, cut(v.name(), NAME_MAX), cut(v.article(), 255), cut(v.brand(), 500),
                q.setScale(3, RoundingMode.HALF_UP), unit, pricesOk ? v.price() : null, v.vat());
    }

    public static VatPick vat(String text, List<BigDecimal> rates) {
        if (text == null || text.isBlank()) return VatPick.NONE;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("без ндс") || t.contains("не облага") || t.strip().equals("без")) {
            return OfferSettingsValidator.containsRate(rates, null) ? new VatPick(true, null) : VatPick.NONE;
        }
        Matcher m = PERCENT.matcher(t);
        String num = m.find() ? m.group(1) : null;
        if (num == null) {
            Matcher b = BARE.matcher(t);
            if (b.find()) num = b.group(1);
        }
        if (num == null || rates == null) return VatPick.NONE;
        BigDecimal r = new BigDecimal(num.replace(',', '.'));
        for (BigDecimal x : rates) if (x != null && x.compareTo(r) == 0) return new VatPick(true, x);
        return VatPick.NONE;
    }

    private static boolean included(List<String> cells) {
        for (String c : cells) if (c != null && c.toLowerCase(Locale.ROOT).contains("включен")) return true;
        return false;
    }

    private static boolean hasLetter(String s) {
        return s.chars().anyMatch(Character::isLetter);
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
```

`src/main/java/com/vladoose/nir/service/readyoffer/OfferHeader.java`:

```java
package com.vladoose.nir.service.readyoffer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Номер и дата КП из текста документа или имени файла (спека 2026-10-19 §4.4): номер — только после «Исх», «КП» или
 * «Коммерческое предложение» (не «ГКП … № 5»), дата — после номера («от …») или после «Дата:»; ДД.ММ.ГГГГ и
 * «17 апреля 2026». Несуществующая дата (31.02) — нет даты.
 */
public record OfferHeader(Integer number, LocalDate date) {

    public static final OfferHeader NONE = new OfferHeader(null, null);

    private static final List<String> MONTHS = List.of("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
            "августа", "сентября", "октября", "ноября", "декабря");
    private static final String MONTH = String.join("|", MONTHS);
    private static final String DATE = "(?:\\d{1,2}\\.\\d{1,2}\\.\\d{4}|«?\\d{1,2}»?\\s+(?:" + MONTH + ")\\s+\\d{4})";
    private static final Pattern NUMBERED = Pattern.compile("(?iu)(?:(?<!\\p{L})исх(?:одящий)?\\.?\\s*(?:№|n(?!\\p{L})|номер)?"
            + "|(?<!\\p{L})(?:кп|коммерческое\\s+предложение)\\s*№)\\s*(?<n>\\d{1,7})(?:\\s*г?\\.?\\s*от\\s*(?<d>" + DATE + "))?");
    private static final Pattern DATED = Pattern.compile("(?iu)(?<!\\p{L})дата\\s*:?\\s*(?<d>" + DATE + ")");
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
    private static final Pattern WORDS = Pattern.compile("(?iu)(\\d{1,2})»?\\s+(" + MONTH + ")\\s+(\\d{4})");

    public static OfferHeader find(List<String> lines) {
        Integer number = null;
        LocalDate date = null;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.replace(' ', ' ');
            if (number == null) {
                Matcher m = NUMBERED.matcher(line);
                if (m.find()) {
                    int n = Integer.parseInt(m.group("n"));
                    if (n > 0) {
                        number = n;
                        if (date == null && m.group("d") != null) date = date(m.group("d"));
                    }
                }
            }
            if (date == null) {
                Matcher m = DATED.matcher(line);
                if (m.find()) date = date(m.group("d"));
            }
            if (number != null && date != null) break;
        }
        return new OfferHeader(number, date);
    }

    static LocalDate date(String s) {
        try {
            Matcher n = NUMERIC.matcher(s);
            if (n.find()) return LocalDate.of(Integer.parseInt(n.group(3)), Integer.parseInt(n.group(2)), Integer.parseInt(n.group(1)));
            Matcher w = WORDS.matcher(s);
            if (w.find()) {
                return LocalDate.of(Integer.parseInt(w.group(3)), MONTHS.indexOf(w.group(2).toLowerCase(Locale.ROOT)) + 1,
                        Integer.parseInt(w.group(1)));
            }
        } catch (DateTimeException e) {
            return null;
        }
        return null;
    }
}
```

`src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferReader.java`:

```java
package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.HeaderDictionary;
import com.vladoose.nir.service.supplierreply.OoxmlSlim;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import com.vladoose.nir.service.supplierreply.ReplyTableReader;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.PdfTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Файл готового КП → итог разбора (спека 2026-10-19 §4): Word / Excel — все таблицы, строки, «ИТОГО», номер и дата из
 * текста; PDF — страницы и номер с датой по первой странице; картинка — только номер по имени файла. Любой сбой — FAILED
 * с причиной, а не исключение: КП всё равно заводится с файлом (§8). Разбор Word / Excel — по копии без картинок
 * (OoxmlSlim), как у ответов поставщиков.
 */
@Component
public class ReadyOfferReader {

    private static final Logger log = LoggerFactory.getLogger(ReadyOfferReader.class);

    public record Result(ReplyFileKind kind, ReplyParseStatus status, String note, List<ReplyTable> tables,
                         List<OfferFileLine> lines, BigDecimal fileTotal, String foreignCurrency, OfferHeader header,
                         Integer pages) {}

    private final HeaderDictionary dictionary;
    private final ReplyPdfPages pdf;

    @Autowired
    public ReadyOfferReader(HeaderDictionary dictionary, ReplyPdfPages pdf) {
        this.dictionary = dictionary;
        this.pdf = pdf;
    }

    public Result read(String fileName, String mime, byte[] bytes, String marketCurrency) {
        return read(fileName, mime, bytes, marketCurrency, dictionary.learned());
    }

    Result read(String fileName, String mime, byte[] bytes, String marketCurrency, Map<String, LineField> learned) {
        ReplyFileKind kind = SupplierReplyFiles.kind(fileName, mime, bytes);
        OfferHeader byName = OfferHeader.find(List.of(fileName));
        try {
            switch (kind) {
                case EXCEL, WORD -> {
                    byte[] parse = bytes.length > SupplierReplyFiles.PARSE_MAX ? null
                            : OoxmlSlim.forParsing(bytes, SupplierReplyFiles.PARTS_MAX);
                    if (parse == null) {
                        return empty(kind, ReplyParseStatus.NOT_PARSED, "Файл слишком большой для разбора — впишите цены по документу",
                                byName, null);
                    }
                    OfferHeader header = merge(OfferHeader.find(ReplyTableReader.documentLines(parse, kind)), byName);
                    List<ReplyTableReader.ReadTable> all = ReplyTableReader.readAll(parse, kind, learned);
                    if (all.isEmpty()) {
                        return empty(kind, ReplyParseStatus.NO_TABLE, "Таблица с наименованием и ценой не найдена — впишите цены по документу",
                                header, null);
                    }
                    OfferFileLines.Lines lines = OfferFileLines.of(all, marketCurrency);
                    return new Result(kind, ReplyParseStatus.PARSED, null, all.stream().map(ReplyTableReader.ReadTable::table).toList(),
                            lines.lines(), lines.fileTotal(), lines.foreignCurrency(), header, null);
                }
                case PDF -> {
                    int pages = pdf.pageCount(bytes);
                    String text = PdfTextExtractor.firstPage(bytes);
                    OfferHeader header = merge(text == null ? OfferHeader.NONE : OfferHeader.find(List.of(text.split("\\R"))), byName);
                    return empty(kind, ReplyParseStatus.NOT_PARSED, null, header, pages);
                }
                default -> {
                    return empty(kind, ReplyParseStatus.NOT_PARSED, null, byName, null);
                }
            }
        } catch (Exception | Error e) {
            log.warn("Файл готового КП не разобран ({}): {}", kind, e.getClass().getSimpleName());
            return empty(kind, ReplyParseStatus.FAILED,
                    kind == ReplyFileKind.PDF ? "PDF не открылся — скачайте файл" : "Файл не открылся: защищён паролем или повреждён",
                    byName, null);
        }
    }

    private static Result empty(ReplyFileKind kind, ReplyParseStatus status, String note, OfferHeader header, Integer pages) {
        return new Result(kind, status, note, List.of(), List.of(), null, null, header, pages);
    }

    /** Номер и дата из документа; недостающее — из имени файла. */
    static OfferHeader merge(OfferHeader doc, OfferHeader name) {
        return new OfferHeader(doc.number() != null ? doc.number() : name.number(),
                doc.date() != null ? doc.date() : name.date());
    }
}
```

- [ ] **Step 7: Прогнать — зелёные**

Run: `./gradlew test --tests 'com.vladoose.nir.service.readyoffer.*' --tests 'com.vladoose.nir.service.supplierreply.*'`
Expected: PASS.

- [ ] **Step 8: Мутации**
  - в `OfferFileLines.table` убрать правило «одна надпись — раздел» → `mergedRowIsSection…` и `mergedFloorRows…` падают («1 этаж» позицией по 1 ₸);
  - не удалять раздел без позиций (`pending`) → `includedRowAndItsSectionKept…` падает;
  - `pricesOk ? v.price() : null` → `v.price()` → `foreignCurrencyItemsWithoutPrice` и `rublesOnKzMarket…` падают;
  - в `NUMBERED` убрать `(?<!\\p{L})` перед `кп` → `numberOnlyAfterIskhOrKp…` падает (`ГКП № 5`).
  Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/readyoffer src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyFiles.java \
  src/main/java/com/vladoose/nir/service/supplierreply/OoxmlSlim.java src/main/java/com/vladoose/nir/util/PdfTextExtractor.java \
  src/test/java/com/vladoose/nir/service/readyoffer
git commit -m "feat(ready-offer): строки КП из файла — разделы, «включено», позиции; номер и дата документа

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Расчёт и сервис КП в режиме «из файла»

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java:93`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferService.java` (`update`, `setStatus`, `offerStatusText`, `delete`, `snapshot`, `fileBaseName`, новый `fileBase`)
- Modify: `src/main/java/com/vladoose/nir/dto/request/ClientOfferUpdateRequest.java` (`number` без `@NotNull`)
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealEventService.java` (`forgetOffer`), `src/main/java/com/vladoose/nir/repository/DealEventRepository.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferCalculatorTest.java` (новый тест), `src/test/java/com/vladoose/nir/clientoffer/ClientOfferFileModeTest.java`

**Interfaces:**
- Consumes: `ClientOfferOrigin`, `OfferLabels` (задача 1).
- Produces: `ClientOfferService.setStatus(Long id, ClientOfferStatus status, OffsetDateTime sentAt) : ClientOffer` (прежний `setStatus(id, status)` — делегат с `null`); package-private `ClientOffer fileBase(Market market, String author)` (умолчания «Реквизитов», `origin = FILE`, без номера, статус SENT); `DealEventService.forgetOffer(Long tenderId, Long offerId)`; у КП из файла `pdf/docx/preview` → 400 «КП из файла — скачайте файл».

- [ ] **Step 1: Тесты**

В `ClientOfferCalculatorTest` — новый тест:

```java
    // Готовое КП (спека 2026-10-19 §4.5): цена — только из файла (ручная), от наценки не считается
    @Test
    void fileOffer_priceOnlyFromFile() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setOrigin(ClientOfferOrigin.FILE);
        ClientOfferItem withPrice = add(o, "100", "5");
        withPrice.setPriceOverride(new BigDecimal("150"));
        add(o, "100", "5");                                     // цены в файле нет — закупка есть
        OfferCalculation c = calculator.calculate(o);
        assertThat(c.items().get(0).price()).isEqualByComparingTo("150");
        assertThat(c.items().get(0).markupPct()).isEqualByComparingTo("50");
        assertThat(c.items().get(1).price()).isNull();
        assertThat(c.items().get(1).sum()).isNull();
        assertThat(c.totals().sum()).isEqualByComparingTo("150");

        o.setOrigin(ClientOfferOrigin.BUILDER);                 // у конструктора та же строка считается от наценки 20 %
        assertThat(calculator.calculate(o).items().get(1).price()).isEqualByComparingTo("120");
    }
```

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferFileModeTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** КП в режиме «из файла» (спека 2026-10-19 §3.3, §5.1, §6; решения при планировании 1, 6, 10). */
@SpringBootTest
@Transactional
class ClientOfferFileModeTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired ClientOfferRepository offers;
    @Autowired ClientOfferService service;
    @Autowired DealEventRepository events;
    @Autowired PrivateRequestService requests;
    @Autowired CompanyProfileRepository profiles;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** КП из файла заявки: «Отправлено», одна позиция с ценой из файла и закупкой. */
    private ClientOffer fileOffer(Tender t, Integer number) {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setNumber(number);
        o.setOrigin(ClientOfferOrigin.FILE);
        o.setStatus(ClientOfferStatus.SENT);
        o.setSentAt(OffsetDateTime.now());
        o.setOfferDate(LocalDate.of(2026, 9, 24));
        o.setTenderId(t == null ? null : t.getId());
        ClientOfferItem it = ClientOfferTestData.item(o, 1, "Пульсоксиметр", "80000", "5");
        it.setPriceOverride(new BigDecimal("105600"));
        o.getItems().add(it);
        return offers.saveAndFlush(o);
    }

    /** Ответ GET КП как JSON — тело PUT (лишние поля сервер игнорирует, как в ClientOfferApiTest). */
    private JsonNode json(long id) throws Exception {
        return om.readTree(mvc.perform(get("/api/client-offers/" + id).header("X-Market", "KZ")
                        .with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void putWithoutNumber_fileOk_builderRejected() throws Exception {
        ClientOffer file = fileOffer(null, null);
        ObjectNode body = json(file.getId()).deepCopy();
        body.putNull("number");
        mvc.perform(put("/api/client-offers/" + file.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number", nullValue()));

        ClientOffer built = service.create("admin");
        ObjectNode b2 = json(built.getId()).deepCopy();
        b2.putNull("number");
        mvc.perform(put("/api/client-offers/" + built.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(b2.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Укажите номер КП"));
    }

    @Test
    void fileOfferDeletedInAnyStatus_itsEventsGone_builderSentStaysProtected() throws Exception {
        Tender t = requests.createEmpty();
        ClientOffer o = fileOffer(t, null);
        service.setStatus(o.getId(), ClientOfferStatus.ACCEPTED);
        assertThat(events.findByTenderIdOrderByOccurredAtAscIdAsc(t.getId())).extracting(DealEvent::getText)
                .contains("КП от 24.09.2026: клиент согласился");

        mvc.perform(delete("/api/client-offers/" + o.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        assertThat(offers.findById(o.getId())).isEmpty();
        assertThat(events.findByTenderIdOrderByOccurredAtAscIdAsc(t.getId())).noneMatch(e -> o.getId().equals(e.getRefId()));

        ClientOffer built = service.create("admin");
        service.setStatus(built.getId(), ClientOfferStatus.SENT);
        mvc.perform(delete("/api/client-offers/" + built.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileOfferHasNoPdfWordPreview() throws Exception {
        ClientOffer o = fileOffer(null, 24);
        for (String path : new String[]{"pdf", "docx", "preview"}) {
            mvc.perform(get("/api/client-offers/" + o.getId() + "/" + path).header("X-Market", "KZ")
                            .with(user("op").roles("OPERATOR")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("КП из файла — скачайте файл"));
        }
    }

    @Test
    void duplicateOfFileOfferIsBuilderWithCounterNumberAndSamePrices() {
        ClientOffer o = fileOffer(null, null);
        int next = profiles.findByMarket(Market.KZ).orElseThrow().getNextNumber();
        ClientOffer copy = service.duplicate(o.getId(), "admin");
        assertThat(copy.getOrigin()).isEqualTo(ClientOfferOrigin.BUILDER);
        assertThat(copy.getNumber()).isEqualTo(next);
        assertThat(copy.getItems().get(0).getPriceOverride()).isEqualByComparingTo("105600");
    }

    @Test
    void setStatusWithTime_usesGivenTime_labelsWithoutNumber() {
        ClientOffer o = service.create("admin");
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 15, 7, 30, 0, 0, ZoneOffset.UTC);
        assertThat(service.setStatus(o.getId(), ClientOfferStatus.SENT, at).getSentAt()).isEqualTo(at);

        ClientOffer file = fileOffer(null, null);
        assertThat(service.fileBaseName(file)).startsWith("КП от 24.09.2026");
    }
}
```

- [ ] **Step 2: Прогнать — красные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferCalculatorTest' --tests 'com.vladoose.nir.clientoffer.ClientOfferFileModeTest'`
Expected: FAIL — компиляция (`setStatus(id, status, at)` нет); после неё — по сути: цена строки файла от наценки, «Укажите номер КП» не отвечается, удаление отправленного КП из файла — 400, PDF у КП из файла собирается.

- [ ] **Step 3: Реализация**

`ClientOfferCalculator.calculateItem` — условие ветки наценки:

```java
        } else if (p != null && offer.getOrigin() != ClientOfferOrigin.FILE) {
```

(и импорт `ClientOfferOrigin`; у КП из файла строка без ручной цены уходит в ветку «без цены» — ставка остаётся в `effectiveVatRate`).

`ClientOfferUpdateRequest`: `@NotNull @Min(value = 1, message = "Номер — от 1") private Integer number;` → `@Min(value = 1, message = "Номер — от 1") private Integer number;`

`ClientOfferService`:

1. В `update`, сразу после сверки версии:

```java
        if (r.getNumber() == null && o.getOrigin() != ClientOfferOrigin.FILE) throw new BadRequestException("Укажите номер КП");
```

2. `setStatus`:

```java
    @Transactional
    public ClientOffer setStatus(Long id, ClientOfferStatus status) {
        return setStatus(id, status, null);
    }

    /** sentAt — время отправки («Отметить отправленным» по письму); null — «сейчас», если отправки ещё не было. */
    @Transactional
    public ClientOffer setStatus(Long id, ClientOfferStatus status, OffsetDateTime sentAt) {
        ClientOffer o = get(id);
        ClientOfferStatus before = o.getStatus();
        o.setStatus(status);
        if (status == ClientOfferStatus.SENT && (sentAt != null || o.getSentAt() == null)) {
            o.setSentAt(sentAt != null ? sentAt : OffsetDateTime.now());
        }
        o.setUpdatedAt(OffsetDateTime.now());
        ClientOffer saved = repository.saveAndFlush(o);
        // DRAFT → SENT в ленте и так видно (sent_at); у КП не из заявки ленты нет
        if (o.getTenderId() != null && before != status
                && !(before == ClientOfferStatus.DRAFT && status == ClientOfferStatus.SENT)) {
            dealEvents.stage(o.getTenderId(), DealEventService.OFFER_STATUS, offerStatusText(o, status), o.getId());
        }
        return saved;
    }

    static String offerStatusText(ClientOffer o, ClientOfferStatus status) {
        String prefix = OfferLabels.of(o) + ": ";
        return switch (status) {
            case ACCEPTED -> prefix + "клиент согласился";
            case REJECTED -> prefix + "клиент отказался";
            case SENT -> prefix + "снова «отправлено»";
            case DRAFT -> prefix + "снова черновик";
        };
    }
```

3. `delete`:

```java
    @Transactional
    public void delete(Long id) {
        ClientOffer o = get(id);
        // КП из файла занесли не тем файлом — удаляется в любом статусе; у конструктора — только черновик
        if (o.getOrigin() != ClientOfferOrigin.FILE && o.getStatus() != ClientOfferStatus.DRAFT) {
            throw new BadRequestException("Удалить можно только черновик — отправленное КП отметьте «Отклонено»");
        }
        if (o.getTenderId() != null) dealEvents.forgetOffer(o.getTenderId(), o.getId());
        repository.delete(o);
    }
```

4. `snapshot` — первой строкой лямбды после `get(id)`:

```java
            if (o.getOrigin() == ClientOfferOrigin.FILE) throw new BadRequestException("КП из файла — скачайте файл");
```

5. `fileBaseName`:

```java
        String head = o.getNumber() != null
                ? "КП № " + o.getNumber() + " от " + DocFormat.date(o.getOfferDate())
                : "КП от " + DocFormat.date(o.getOfferDate());
        String base = head + (client == null ? "" : " " + DocFormat.DASH + " " + client);
```

6. Новый метод (package-private) — рядом с `create`:

```java
    /**
     * Основа готового КП (спека 2026-10-19 §5.1, решение при планировании 7): умолчания «Реквизитов», как у нового КП,
     * но без номера из счётчика — номер берётся из документа.
     */
    ClientOffer fileBase(Market market, String author) {
        CompanyProfile p = profiles.forMarket(market);
        return ClientOffer.builder()
                .market(market)
                .origin(ClientOfferOrigin.FILE)
                .offerDate(today(market))
                .status(ClientOfferStatus.SENT)
                .title(DEFAULT_TITLE)
                .intro(p.getDefaultIntro())
                .vatEnabled(true)
                .defaultMarkupPct(p.getDefaultMarkupPct())
                .rounding(OfferRounding.NONE)
                .tableColumns(copyColumns(p.getDefaultColumns()))
                .detailsInName(true)
                .terms(copyTerms(p.getDefaultTerms()))
                .termsStyle(p.getDefaultTermsStyle())
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .totalAmount(BigDecimal.ZERO.setScale(2))
                .createdBy(author)
                .build();
    }
```

`DealEventRepository`:

```java
    @Modifying
    @Transactional
    long deleteByTenderIdAndCodeAndRefId(Long tenderId, String code, Long refId);
```

(импорты `org.springframework.data.jpa.repository.Modifying`, `org.springframework.transaction.annotation.Transactional`).

`DealEventService`:

```java
    /** КП удалено — его события смены статуса не нужны: ссылка вела бы на «КП не найдено» (решение при планировании 10). */
    @Transactional
    public void forgetOffer(Long tenderId, Long offerId) {
        repository.deleteByTenderIdAndCodeAndRefId(tenderId, OFFER_STATUS, offerId);
    }
```

- [ ] **Step 4: Прогнать — зелёные, плюс весь пакет КП и сделки**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.*' --tests 'com.vladoose.nir.deal.*'`
Expected: PASS (прежние тексты «КП № N…» у КП с номером не изменились).

- [ ] **Step 5: Мутации**
  - убрать `&& offer.getOrigin() != ClientOfferOrigin.FILE` → `fileOffer_priceOnlyFromFile` падает;
  - убрать проверку номера в `update` → `putWithoutNumber…` падает (200 у конструктора);
  - убрать условие `origin != FILE` в `delete` → `fileOfferDeleted…` падает (400);
  - убрать `forgetOffer` из `delete` → `fileOfferDeleted…` падает на событиях;
  - убрать проверку в `snapshot` → `fileOfferHasNoPdfWordPreview` падает.
  Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java src/main/java/com/vladoose/nir/service/ClientOfferService.java \
  src/main/java/com/vladoose/nir/dto/request/ClientOfferUpdateRequest.java src/main/java/com/vladoose/nir/service/deal/DealEventService.java \
  src/main/java/com/vladoose/nir/repository/DealEventRepository.java \
  src/test/java/com/vladoose/nir/clientoffer/ClientOfferCalculatorTest.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferFileModeTest.java
git commit -m "feat(ready-offer): КП из файла — цена только из файла, номер необязателен, удаление в любом статусе

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Занести готовое КП — с компьютера и из переписки

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/ChatFileRow.java`
- Create: `src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferHints.java` (переписка сделки: эпизод, наши файлы, файл по id, PDF самой АИС, «похоже на КП»)
- Create: `src/main/java/com/vladoose/nir/service/ReadyOfferService.java` (`attach`)
- Modify: `src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java` (`findOurFiles`)
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java` (`FAR` — `public`)
- Test: `src/test/java/com/vladoose/nir/readyoffer/ReadyOfferAttachTest.java`

**Interfaces:**
- Consumes: `ReadyOfferReader.read` (задача 3), `ClientOfferService.fileBase/calculate/checkAmounts` (задача 4), `ClientOfferRequestService.item/link/offersByLot/recipient`, `PrivateRequestService.findById/addLines/input`, `ReplyMatcher.match`, `PurchaseOffers.best`, `DealEventService.stage`, `OfferLabels.of`.
- Produces:
  - `record ChatFileRow(Long attachmentId, String fileName, String mimeType, Long sizeBytes, boolean hintDismissed, OffsetDateTime sentAt, String subject, String body, LeadChannel channel)`;
  - `ChatAttachmentRepository.findOurFiles(Long chatId, LeadDirection direction, OffsetDateTime from, OffsetDateTime to, Pageable page) : List<ChatFileRow>`;
  - `@Component ReadyOfferHints`: `List<ChatFileRow> ourFiles(Tender)`, `ChatFileRow requireOurFile(Tender, Long attachmentId)` (404), `ChatAttachment ourAttachment(Tender, Long attachmentId)` (404, с байтами), `static boolean looksLikeOffer(ChatFileRow)`, `static Optional<ClientOffer> aisOffer(OfferHeader, List<ClientOffer>)`, `static OfferHeader nameHeader(String fileName)`, `static String channelWord(LeadChannel)`;
  - `ReadyOfferService.attach(Long tenderId, Upload upload, Long attachmentId, ClientOfferStatus status, LocalDate sentOn, String author) : ClientOffer`, `record Upload(String fileName, String mimeType, byte[] bytes)`, `MAX_LINES = 500`.

- [ ] **Step 1: Тест**

`src/test/java/com/vladoose/nir/readyoffer/ReadyOfferAttachTest.java`:

```java
package com.vladoose.nir.readyoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.ReadyOfferService;
import com.vladoose.nir.service.readyoffer.ReadyOfferFixtures;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** «Занести готовое КП» (спека 2026-10-19 §3.2, §4, §8; Review Focus 1, 3). */
@SpringBootTest
@Transactional
class ReadyOfferAttachTest {

    @Autowired ReadyOfferService service;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService clientOffers;
    @Autowired ClientOfferRepository offers;
    @Autowired ClientOfferFileRepository files;
    @Autowired CompanyProfileRepository profiles;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired DealEventRepository events;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    static final List<String[]> ROWS = List.of(
            new String[]{"Пульсоксиметр QMP-PO70", "3", "105 600,00", "316 800,00"},
            new String[]{"Гигрометр ВИТ-2", "4", "13 515,00", "54 060,00"});

    @BeforeEach
    void kz() {
        MarketContext.set(Market.KZ);
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setVatDefault(new BigDecimal("16"));
        kz.setVatRegistered(new BigDecimal("5"));
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ReadyOfferService.Upload word(byte[] b) {
        return new ReadyOfferService.Upload("КП West-Med 24.09.2026.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", b);
    }

    static byte[] fatherKp() throws Exception {
        return ReadyOfferFixtures.fatherKp("24.09.2026", ROWS, "370 860,00");
    }

    private TenderLot lot(Tender t, String name) {
        return requests.findById(t.getId()).getLots().stream().filter(l -> name.equals(l.getEquipName())).findFirst().orElseThrow();
    }

    private PriceRequestItem answer(Tender t, Distributor d, TenderLot lot, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private Chat chat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId("zz" + System.nanoTime() + "@clinic.kz").title("Клиника").build());
    }

    private void lead(Chat c, Tender t, OffsetDateTime receivedAt) {
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос КП").contactName("Клиент").status(LeadStatus.CONVERTED).chat(c).privateRequest(t)
                .receivedAt(receivedAt).build());
    }

    private ChatAttachment file(Chat c, LeadDirection dir, OffsetDateTime at, String subject, String name, byte[] content) {
        ChatMessage m = messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(dir)
                .type(ChatMessageType.EMAIL).subject(subject).body("Добрый день! Во вложении КП.").sentAt(at).build());
        return attachments.save(ChatAttachment.builder().message(m).fileName(name).mimeType("application/octet-stream")
                .sizeBytes((long) content.length).content(content).build());
    }

    @Test
    void emptyRequest_wordKp_linesPositionsTotalFile_counterUntouched() throws Exception {
        Tender t = requests.createEmpty();
        int counter = profiles.findByMarket(Market.KZ).orElseThrow().getNextNumber();

        ClientOffer o = service.attach(t.getId(), word(fatherKp()), null, ClientOfferStatus.SENT, DAY, "vlad");

        assertThat(o.getOrigin()).isEqualTo(ClientOfferOrigin.FILE);
        assertThat(o.getNumber()).isNull();
        assertThat(o.getOfferDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(o.getSentAt().atZoneSameInstant(Market.KZ.zone()).toLocalDate()).isEqualTo(DAY);
        assertThat(o.getTenderId()).isEqualTo(t.getId());
        assertThat(o.getItems()).extracting(ClientOfferItem::getName).containsExactly("Пульсоксиметр QMP-PO70", "Гигрометр ВИТ-2");
        ClientOfferItem first = o.getItems().get(0);
        assertThat(first.getPriceOverride()).isEqualByComparingTo("105600");
        assertThat(first.getQuantity()).isEqualByComparingTo("3");
        assertThat(first.getVatRate()).isEqualByComparingTo("5");
        assertThat(o.getTotalAmount()).isEqualByComparingTo("370860");
        assertThat(requests.findById(t.getId()).getLots()).extracting(TenderLot::getEquipName)
                .containsExactlyInAnyOrder("Пульсоксиметр QMP-PO70", "Гигрометр ВИТ-2");
        assertThat(o.getItems()).allSatisfy(it -> assertThat(it.getTenderLotId()).isNotNull());
        ClientOfferFile f = files.findByOfferId(o.getId()).orElseThrow();
        assertThat(f.getFileTotal()).isEqualByComparingTo("370860");
        assertThat(f.getKind()).isEqualTo(ReplyFileKind.WORD);
        assertThat(f.getParseStatus()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(f.getTables()).hasSize(1);
        assertThat(f.getUploadedBy()).isEqualTo("vlad");
        assertThat(profiles.findByMarket(Market.KZ).orElseThrow().getNextNumber()).isEqualTo(counter);
    }

    @Test
    void positionsMatched_purchaseFromBestAnswer_noNewPositions() throws Exception {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Пульсоксиметр QMP-PO70", null, 3, null, null, null),
                input("Тонометр механический", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot pulse = lot(t, "Пульсоксиметр QMP-PO70");
        Distributor d = distributors.save(Distributor.builder().name("ZZ Готовое КП " + System.nanoTime()).email("g@zz.kz").build());
        answer(t, d, pulse, "80000");
        em.flush();

        ClientOffer o = service.attach(t.getId(), word(fatherKp()), null, ClientOfferStatus.SENT, DAY, "vlad");

        ClientOfferItem first = o.getItems().get(0), second = o.getItems().get(1);
        assertThat(first.getTenderLotId()).isEqualTo(pulse.getId());
        assertThat(first.getPurchasePrice()).isEqualByComparingTo("80000");
        assertThat(first.getDistributorId()).isEqualTo(d.getId());
        assertThat(second.getTenderLotId()).isNull();            // «Гигрометр» — не позиция заявки
        assertThat(requests.findById(t.getId()).getLots()).hasSize(2);
    }

    @Test
    void acceptedAtOnce_writesEvent() throws Exception {
        Tender t = requests.createEmpty();
        ClientOffer o = service.attach(t.getId(), word(fatherKp()), null, ClientOfferStatus.ACCEPTED, DAY, "vlad");
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.ACCEPTED);
        assertThat(events.findByTenderIdOrderByOccurredAtAscIdAsc(t.getId())).extracting(DealEvent::getText)
                .containsExactly("КП от 24.09.2026: клиент согласился");
    }

    @Test
    void pdf_linesFromPositionsWithoutClientPrice() throws Exception {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Кушетка", null, 2, null, null, null), input("Ширма", null, 1, null, null, null)),
                LinesMode.APPEND);
        ClientOffer o = service.attach(t.getId(), new ReadyOfferService.Upload("КП.pdf", "application/pdf", ReplyFixtures.pdfBytes(2)),
                null, ClientOfferStatus.SENT, DAY, "vlad");
        assertThat(o.getItems()).hasSize(2).allSatisfy(it -> assertThat(it.getPriceOverride()).isNull());
        assertThat(o.getTotalAmount()).isEqualByComparingTo("0");
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getPages()).isEqualTo(2);
    }

    @Test
    void aisOwnPdfIsRefused() throws Exception {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Кушетка", null, 1, null, null, null)), LinesMode.APPEND);
        ClientOffer built = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        ClientOfferService.OfferFile pdf = clientOffers.pdf(built.getId());
        assertThatThrownBy(() -> service.attach(t.getId(), new ReadyOfferService.Upload(pdf.fileName(), "application/pdf", pdf.bytes()),
                null, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("из конструктора");
    }

    @Test
    void foreignCurrency_noPricesAndNote() throws Exception {
        Tender t = requests.createEmpty();
        byte[] x = ReadyOfferFixtures.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, руб."), List.of("Консоль", "1", "940 000")));
        ClientOffer o = service.attach(t.getId(), new ReadyOfferService.Upload("КП.xlsx", null, x), null, ClientOfferStatus.SENT, DAY, "vlad");
        assertThat(o.getItems()).singleElement().satisfies(it -> assertThat(it.getPriceOverride()).isNull());
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getParseNote()).contains("руб.");
    }

    @Test
    void refusals() throws Exception {
        Tender t = requests.createEmpty();
        byte[] kp = fatherKp();
        LocalDate tomorrow = LocalDate.now(Market.KZ.zone()).plusDays(1);
        assertThatThrownBy(() -> service.attach(t.getId(), word(kp), null, ClientOfferStatus.SENT, tomorrow, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Дата отправки ещё не наступила");
        assertThatThrownBy(() -> service.attach(t.getId(), new ReadyOfferService.Upload("КП.txt", "text/plain", "КП".getBytes()),
                null, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Нужен Word, Excel, PDF или картинка");
        assertThatThrownBy(() -> service.attach(t.getId(), null, null, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Выберите файл: с компьютера или из переписки");
        assertThatThrownBy(() -> service.attach(t.getId(), word(kp), null, ClientOfferStatus.DRAFT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class);
        assertThat(offers.findByTenderIdOrderByIdDesc(t.getId())).isEmpty();
    }

    @Test
    void moreThan500LinesRefusedBeforeWrite() throws Exception {   // Review Focus 1
        Tender t = requests.createEmpty();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Наименование", "Кол-во", "Цена, тг"));
        for (int i = 1; i <= 501; i++) rows.add(List.of("Позиция " + i, "1", "100"));
        byte[] x = ReadyOfferFixtures.xlsx(rows);
        assertThatThrownBy(() -> service.attach(t.getId(), new ReadyOfferService.Upload("КП.xlsx", null, x), null,
                ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("больше 500 строк");
        assertThat(offers.findByTenderIdOrderByIdDesc(t.getId())).isEmpty();
        assertThat(requests.findById(t.getId()).getLots()).isEmpty();
    }

    @Test
    void fromCorrespondence_sentAtFromMessage_attachmentLinked() throws Exception {
        Tender t = requests.createEmpty();
        Chat c = chat();
        lead(c, t, OffsetDateTime.parse("2026-10-14T09:00:00Z"));
        OffsetDateTime sent = OffsetDateTime.parse("2026-10-15T07:30:00Z");
        ChatAttachment a = file(c, LeadDirection.OUT, sent, "Коммерческое предложение", "КП West-Med.docx", fatherKp());

        ClientOffer o = service.attach(t.getId(), null, a.getId(), ClientOfferStatus.SENT, DAY, "vlad");

        assertThat(o.getSentAt()).isAtSameInstantAs(sent);
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getChatAttachmentId()).isEqualTo(a.getId());
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getFileName()).isEqualTo("КП West-Med.docx");
    }

    @Test
    void notOurFileIs404() throws Exception {   // Review Focus 3
        Tender t = requests.createEmpty();
        Chat c = chat();
        lead(c, t, OffsetDateTime.parse("2026-10-14T09:00:00Z"));
        byte[] kp = fatherKp();
        ChatAttachment incoming = file(c, LeadDirection.IN, OffsetDateTime.parse("2026-10-15T07:30:00Z"), "Re: КП", "КП.docx", kp);
        ChatAttachment before = file(c, LeadDirection.OUT, OffsetDateTime.parse("2026-10-13T07:30:00Z"), "КП", "КП.docx", kp);
        ChatAttachment otherChat = file(chat(), LeadDirection.OUT, OffsetDateTime.parse("2026-10-15T07:30:00Z"), "КП", "КП.docx", kp);
        for (ChatAttachment a : List.of(incoming, before, otherChat)) {
            assertThatThrownBy(() -> service.attach(t.getId(), null, a.getId(), ClientOfferStatus.SENT, DAY, "vlad"))
                    .isInstanceOf(NotFoundException.class).hasMessage("Файл переписки не найден");
        }
        assertThatThrownBy(() -> service.attach(t.getId(), null, Long.MAX_VALUE, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.readyoffer.ReadyOfferAttachTest'`
Expected: FAIL — компиляция (`ReadyOfferService` нет).

- [ ] **Step 3: Проекция файлов переписки**

`src/main/java/com/vladoose/nir/dto/response/ChatFileRow.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LeadChannel;

import java.time.OffsetDateTime;

/** Файл нашего сообщения переписки — без байтов (решение при планировании 9): окно «Готовое КП» и подсказка. */
public record ChatFileRow(Long attachmentId, String fileName, String mimeType, Long sizeBytes, boolean hintDismissed,
                          OffsetDateTime sentAt, String subject, String body, LeadChannel channel) {}
```

`ChatAttachmentRepository` — метод:

```java
    /** Файлы сообщений чата одного направления в окне [from, to) с сохранёнными байтами — новые сверху, без байтов. */
    @Query("""
           select new com.vladoose.nir.dto.response.ChatFileRow(a.id, a.fileName, a.mimeType, a.sizeBytes,
               a.offerHintDismissed, m.sentAt, m.subject, m.body, c.channel)
           from ChatAttachment a join a.message m join m.chat c
           where c.id = :chatId and m.direction = :direction and m.sentAt >= :from and m.sentAt < :to
             and a.content is not null and m.deleted = false
           order by m.sentAt desc, a.id desc""")
    List<ChatFileRow> findOurFiles(@Param("chatId") Long chatId, @Param("direction") LeadDirection direction,
                                   @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, Pageable page);
```

`DealTimelineService`: `static final OffsetDateTime FAR` → `public static final OffsetDateTime FAR`.

- [ ] **Step 4: `ReadyOfferHints` (часть для занесения)**

`src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferHints.java`:

```java
package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.dto.response.ChatFileRow;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Переписка сделки для готового КП (спека 2026-10-19 §3.2, §3.4, §5.3): эпизод обращения заявки (как лента
 * «Переписка и история»), наши файлы в нём (письма из «Отправленных» info@, сообщения с нашего номера), PDF самой АИС
 * и признаки «похоже на КП».
 */
@Component
public class ReadyOfferHints {

    public static final int FILES_LIMIT = 50;
    /** «КП» / «KP» отдельным словом, «коммерческое предложение», «ценовое предложение». */
    static final Pattern OFFER_WORDS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:кп|kp)(?!\\p{L})|коммерческ\\p{L}*\\s+предложени|ценов\\p{L}*\\s+предложени");

    /** Переписка сделки: чат обращения заявки и границы эпизода. */
    public record Episode(Long chatId, OffsetDateTime from, OffsetDateTime to) {}

    private final LeadRepository leads;
    private final ChatAttachmentRepository attachments;
    private final ClientOfferFileRepository files;

    public ReadyOfferHints(LeadRepository leads, ChatAttachmentRepository attachments, ClientOfferFileRepository files) {
        this.leads = leads;
        this.attachments = attachments;
        this.files = files;
    }

    public Optional<Episode> episode(Tender t) {
        Lead lead = leads.findFirstByPrivateRequestId(t.getId()).orElse(null);
        if (lead == null || lead.getChat() == null) return Optional.empty();
        OffsetDateTime from = lead.getReceivedAt() != null ? lead.getReceivedAt() : lead.getCreatedAt();
        if (from == null) return Optional.empty();
        Long chatId = lead.getChat().getId();
        OffsetDateTime to = leads.findFirstByChatIdAndReceivedAtAfterOrderByReceivedAtAsc(chatId, from)
                .map(Lead::getReceivedAt).orElse(DealTimelineService.FAR);
        return Optional.of(new Episode(chatId, from, to));
    }

    /** Наши файлы переписки сделки — новые сверху, до FILES_LIMIT; переписки нет — пусто. */
    public List<ChatFileRow> ourFiles(Tender t) {
        return episode(t).map(e -> attachments.findOurFiles(e.chatId(), LeadDirection.OUT, e.from(), e.to(),
                PageRequest.of(0, FILES_LIMIT))).orElse(List.of());
    }

    /** Наш файл переписки этой сделки — по проекции; иначе 404. */
    public ChatFileRow requireOurFile(Tender t, Long attachmentId) {
        return ourFiles(t).stream().filter(f -> f.attachmentId().equals(attachmentId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Файл переписки не найден"));
    }

    /** Тот же файл с байтами — для занесения. */
    public ChatAttachment ourAttachment(Tender t, Long attachmentId) {
        requireOurFile(t, attachmentId);
        return attachments.findById(attachmentId).orElseThrow(() -> new NotFoundException("Файл переписки не найден"));
    }

    /** PDF, Word или Excel, а в теме, тексте или имени файла — признак КП. */
    public static boolean looksLikeOffer(ChatFileRow f) {
        ReplyFileKind kind = SupplierReplyFiles.kind(nz(f.fileName()), f.mimeType(), new byte[0]);
        if (kind != ReplyFileKind.PDF && kind != ReplyFileKind.WORD && kind != ReplyFileKind.EXCEL) return false;
        return words(f.subject()) || words(f.body()) || words(f.fileName());
    }

    /** Номер и дата из имени файла («КП № 481 от 07.10.2026 – …» — так АИС называет свои PDF и Word). */
    public static OfferHeader nameHeader(String fileName) {
        return OfferHeader.find(List.of(nz(fileName)));
    }

    /** КП конструктора этой сделки с тем же номером и датой — документ самой АИС (спека §4.7). */
    public static Optional<ClientOffer> aisOffer(OfferHeader h, List<ClientOffer> offers) {
        if (h == null || h.number() == null || h.date() == null) return Optional.empty();
        return offers.stream().filter(o -> o.getOrigin() == ClientOfferOrigin.BUILDER && h.number().equals(o.getNumber())
                && h.date().equals(o.getOfferDate())).findFirst();
    }

    /** « письмом» / « в WhatsApp» — по каналу сообщения. */
    public static String channelWord(LeadChannel c) {
        return c == LeadChannel.EMAIL ? " письмом" : c == LeadChannel.WHATSAPP ? " в WhatsApp" : "";
    }

    /** Файл переписки → КП, в которое он занесён (по ссылке файла КП). */
    public Map<Long, ClientOffer> usedBy(List<ClientOffer> offers) {
        Map<Long, ClientOffer> byId = new HashMap<>();
        for (ClientOffer o : offers) if (o.getOrigin() == ClientOfferOrigin.FILE) byId.put(o.getId(), o);
        Map<Long, ClientOffer> out = new HashMap<>();
        if (byId.isEmpty()) return out;
        files.findMetaByOfferIds(byId.keySet()).forEach(m -> {
            if (m.chatAttachmentId() != null) out.put(m.chatAttachmentId(), byId.get(m.offerId()));
        });
        return out;
    }

    private static boolean words(String s) {
        return s != null && OFFER_WORDS.matcher(s).find();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 5: `ReadyOfferService.attach`**

`src/main/java/com/vladoose/nir/service/ReadyOfferService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.service.offer.PurchaseOffer;
import com.vladoose.nir.service.offer.PurchaseOffers;
import com.vladoose.nir.service.readyoffer.OfferFileLine;
import com.vladoose.nir.service.readyoffer.OfferFileLines;
import com.vladoose.nir.service.readyoffer.ReadyOfferHints;
import com.vladoose.nir.service.readyoffer.ReadyOfferReader;
import com.vladoose.nir.service.supplierreply.MoneyText;
import com.vladoose.nir.service.supplierreply.ReplyMatcher;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * Готовое КП (спека 2026-10-19): КП, сделанное вне конструктора, — обычное КП клиенту с файлом. Строки — из таблиц файла
 * (ReadyOfferReader); позиции заявки — из строк КП (заявка пустая) или сопоставлением ReplyMatcher (те же пороги, что у
 * ответов поставщиков: лучше не связать, чем перепутать); закупка — лучший ответ поставщика по позиции, как
 * «Создать КП клиенту». Номер — из документа, счётчик «исх. №» не тратится. Всё проверяется до первой записи.
 */
@Service
public class ReadyOfferService {

    /** Предел строк PUT КП (ClientOfferUpdateRequest.items) — больше автосохранение редактора не примет. */
    public static final int MAX_LINES = 500;

    public record Upload(String fileName, String mimeType, byte[] bytes) {}

    private record Source(String fileName, String mimeType, byte[] bytes, ChatAttachment attachment) {}

    private final PrivateRequestService privateRequests;
    private final ClientOfferService offers;
    private final ClientOfferRequestService requestOffers;
    private final ClientOfferRepository repository;
    private final ClientOfferFileRepository files;
    private final CompanyProfileService profiles;
    private final MedRegistryRepository registry;
    private final ReadyOfferReader reader;
    private final ReadyOfferHints hints;
    private final DealEventService dealEvents;

    public ReadyOfferService(PrivateRequestService privateRequests, ClientOfferService offers, ClientOfferRequestService requestOffers,
                             ClientOfferRepository repository, ClientOfferFileRepository files, CompanyProfileService profiles,
                             MedRegistryRepository registry, ReadyOfferReader reader, ReadyOfferHints hints,
                             DealEventService dealEvents) {
        this.privateRequests = privateRequests;
        this.offers = offers;
        this.requestOffers = requestOffers;
        this.repository = repository;
        this.files = files;
        this.profiles = profiles;
        this.registry = registry;
        this.reader = reader;
        this.hints = hints;
        this.dealEvents = dealEvents;
    }

    @Transactional
    public ClientOffer attach(Long tenderId, Upload upload, Long attachmentId, ClientOfferStatus status, LocalDate sentOn,
                              String author) {
        Tender t = privateRequests.findById(tenderId);
        Market market = t.getMarket() == null ? MarketContext.get() : t.getMarket();
        ZoneId zone = market.zone();
        if (status != ClientOfferStatus.SENT && status != ClientOfferStatus.ACCEPTED) {
            throw new BadRequestException("Статус готового КП — «Отправлено» или «Клиент согласился»");
        }
        if (sentOn == null) throw new BadRequestException("Укажите дату отправки");
        if (sentOn.isAfter(LocalDate.now(zone))) throw new BadRequestException("Дата отправки ещё не наступила");
        Source src = source(t, upload, attachmentId);
        if (SupplierReplyFiles.kind(src.fileName(), src.mimeType(), src.bytes()) == ReplyFileKind.OTHER) {
            throw new BadRequestException("Нужен Word, Excel, PDF или картинка");
        }
        ReadyOfferReader.Result read = reader.read(src.fileName(), src.mimeType(), src.bytes(), market.currencyCode());
        List<ClientOffer> existing = repository.findByTenderIdOrderByIdDesc(t.getId());
        Optional<ClientOffer> ais = ReadyOfferHints.aisOffer(read.header(), existing);
        if (ais.isPresent()) throw new BadRequestException(aisText(ais.get()));
        if (read.lines().size() > MAX_LINES) {
            throw new BadRequestException("В файле больше " + MAX_LINES + " строк — такое КП АИС не держит");
        }

        CompanyProfile p = profiles.forMarket(market);
        ClientOffer o = offers.fileBase(market, author);
        o.setNumber(read.header().number());
        o.setOfferDate(read.header().date() != null ? read.header().date() : sentOn);
        o.setStatus(status);
        o.setSentAt(sentAt(src, sentOn, zone));
        o.setFacility(t.getFacility());
        o.setRecipient(ClientOfferRequestService.recipient(t));
        o.setTenderId(t.getId());
        addItems(o, t, read, p);
        OfferCalculation calc = offers.calculate(o);
        ClientOfferService.checkAmounts(o, calc);
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        ClientOffer saved = repository.saveAndFlush(o);

        files.save(ClientOfferFile.builder().offerId(saved.getId()).fileName(src.fileName())
                .mimeType(src.mimeType() == null ? null : cut(src.mimeType(), 100)).sizeBytes(src.bytes().length)
                .sha256(SupplierReplyFiles.sha256(src.bytes())).content(src.bytes()).kind(read.kind())
                .parseStatus(read.status()).parseNote(note(read, market)).tables(read.tables()).pages(read.pages())
                .fileTotal(read.fileTotal()).chatAttachmentId(src.attachment() == null ? null : src.attachment().getId())
                .uploadedBy(author).build());
        if (status == ClientOfferStatus.ACCEPTED) {
            dealEvents.stage(t.getId(), DealEventService.OFFER_STATUS, OfferLabels.of(saved) + ": клиент согласился", saved.getId());
        }
        return saved;
    }

    private Source source(Tender t, Upload upload, Long attachmentId) {
        if ((upload == null) == (attachmentId == null)) throw new BadRequestException("Выберите файл: с компьютера или из переписки");
        if (upload != null) {
            if (upload.bytes() == null || upload.bytes().length == 0) throw new BadRequestException("Файл пустой");
            return new Source(SupplierReplyFiles.fileName(upload.fileName()), upload.mimeType(), upload.bytes(), null);
        }
        ChatAttachment a = hints.ourAttachment(t, attachmentId);
        return new Source(SupplierReplyFiles.fileName(a.getFileName()), a.getMimeType(), a.getContent(), a);
    }

    /** Время отправки: у файла переписки — время сообщения, если дата та же; иначе полдень даты по часам рынка. */
    static OffsetDateTime sentAt(Source src, LocalDate sentOn, ZoneId zone) {
        if (src.attachment() != null) {
            OffsetDateTime at = src.attachment().getMessage().getSentAt();
            if (at != null && at.atZoneSameInstant(zone).toLocalDate().equals(sentOn)) return at;
        }
        return sentOn.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime();
    }

    private void addItems(ClientOffer o, Tender t, ReadyOfferReader.Result read, CompanyProfile p) {
        Map<Long, List<PurchaseOffer>> byLot = requestOffers.offersByLot(t.getId());
        Map<String, MedRegistry> records = records(t.getLots());
        if (read.status() != ReplyParseStatus.PARSED) {
            // PDF, картинка, не разобран: строки — из позиций заявки без цены клиенту (спека §4.6)
            int no = 0;
            for (TenderLot lot : sorted(t.getLots())) {
                ClientOfferItem it = ClientOfferRequestService.item(o, ++no, lot, p, rec(lot, records));
                PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of())).ifPresent(b -> ClientOfferRequestService.link(it, b));
                o.getItems().add(it);
            }
            return;
        }
        List<OfferFileLine> lines = read.lines();
        List<OfferFileLine> items = lines.stream().filter(l -> l.kind() == ClientOfferItemKind.ITEM).toList();
        Map<OfferFileLine, TenderLot> links = new IdentityHashMap<>();
        if (t.getLots().isEmpty() && !items.isEmpty()) {
            privateRequests.addLines(t.getId(), items.stream().map(ReadyOfferService::input).toList(), LinesMode.APPEND);
            List<TenderLot> created = sorted(t.getLots());
            for (int i = 0; i < items.size() && i < created.size(); i++) links.put(items.get(i), created.get(i));
        } else {
            links.putAll(match(items, t.getLots()));
        }
        int no = 0;
        for (OfferFileLine l : lines) {
            no++;
            if (l.kind() != ClientOfferItemKind.ITEM) {
                o.getItems().add(ClientOfferItem.builder().offer(o).lineNo(no).kind(l.kind()).name(l.name()).build());
                continue;
            }
            TenderLot lot = links.get(l);
            ClientOfferItem it = lot != null
                    ? ClientOfferRequestService.item(o, no, lot, p, rec(lot, records))
                    : ClientOfferItem.builder().offer(o).lineNo(no).kind(ClientOfferItemKind.ITEM).vatRate(p.getVatDefault()).build();
            it.setName(l.name());
            it.setModel(l.model());
            if (l.brand() != null) it.setManufacturer(l.brand());
            it.setQuantity(l.quantity());
            it.setUnit(l.unit());
            it.setPriceOverride(l.price());
            OfferFileLines.VatPick vat = OfferFileLines.vat(l.vat(), p.getVatRates());
            if (vat.found()) it.setVatRate(vat.rate());
            if (lot != null) PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of())).ifPresent(b -> ClientOfferRequestService.link(it, b));
            o.getItems().add(it);
        }
    }

    /** Строки-позиции КП ↔ позиции заявки: только уверенные пары (AUTO). */
    static Map<OfferFileLine, TenderLot> match(List<OfferFileLine> items, Collection<TenderLot> lots) {
        Map<OfferFileLine, TenderLot> out = new IdentityHashMap<>();
        if (items.isEmpty() || lots.isEmpty()) return out;
        Map<Long, TenderLot> byId = new HashMap<>();
        List<ReplyMatcher.Position> positions = new ArrayList<>();
        for (TenderLot l : lots) {
            byId.put(l.getId(), l);
            String text = String.join(" ", Objects.toString(l.getEquipName(), ""), Objects.toString(l.getManufact(), "")).trim();
            positions.add(new ReplyMatcher.Position(l.getId(), l.getLotNumber() == null ? 0 : l.getLotNumber(), text, l.getQuantity()));
        }
        List<ReplyMatcher.Row> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) rows.add(new ReplyMatcher.Row(i, i, items.get(i).matchText(), items.get(i).quantity()));
        for (ReplyMatcher.Match m : ReplyMatcher.match(positions, rows)) {
            if (m.kind() == ReplyMatcher.Kind.AUTO) out.put(items.get(m.fileRow()), byId.get(m.itemId()));
        }
        return out;
    }

    /** Новая позиция заявки из строки КП: дробное количество — 1, как на других путях (решение при планировании 3). */
    private static PrivateRequestLineInput input(OfferFileLine l) {
        BigDecimal q = l.quantity();
        Integer qty = q != null && q.stripTrailingZeros().scale() <= 0 && q.compareTo(BigDecimal.valueOf(1_000_000)) <= 0
                ? q.intValue() : null;
        return PrivateRequestService.input(l.name(), l.brand(), qty, l.unit(), null, null);
    }

    private static List<TenderLot> sorted(Collection<TenderLot> lots) {
        List<TenderLot> out = new ArrayList<>(lots);
        out.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
                .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId()));
        return out;
    }

    private Map<String, MedRegistry> records(Collection<TenderLot> lots) {
        Set<String> numbers = new HashSet<>();
        for (TenderLot l : lots) {
            if (l.getRegStatus() == LineRegStatus.CONFIRMED && l.getRegNumber() != null && !l.getRegNumber().isBlank()) {
                numbers.add(l.getRegNumber().trim());
            }
        }
        Map<String, MedRegistry> out = new HashMap<>();
        if (!numbers.isEmpty()) for (MedRegistry m : registry.findByRegNumberIn(numbers)) out.put(m.getRegNumber(), m);
        return out;
    }

    private static MedRegistry rec(TenderLot lot, Map<String, MedRegistry> records) {
        return lot.getRegNumber() == null ? null : records.get(lot.getRegNumber().trim());
    }

    /** Пометка разбора: причина сбоя или чужая валюта цен. */
    private static String note(ReadyOfferReader.Result read, Market market) {
        if (read.foreignCurrency() == null) return read.note();
        String what = MoneyText.MIXED.equals(read.foreignCurrency()) ? "в разных валютах" : "в " + MoneyText.label(read.foreignCurrency());
        return "Цены в файле — " + what + ", а КП — в " + (market == Market.RF ? "рублях" : "тенге") + ": впишите цены по документу";
    }

    static String aisText(ClientOffer o) {
        String label = OfferLabels.of(o);
        return o.getStatus() == ClientOfferStatus.DRAFT
                ? "Это " + label + " из конструктора — отметьте его отправленным"
                : "Это " + label + " из конструктора — оно уже в сделке";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 6: Прогнать — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.readyoffer.ReadyOfferAttachTest'`
Expected: PASS (10 тестов).

- [ ] **Step 7: Мутации**
  - `match`: брать и `SUGGEST` → `positionsMatched…` может связать «Гигрометр»? (если нет — мутация «связывать по порядку строк» → падает);
  - убрать `addLines` для пустой заявки → `emptyRequest…` падает на позициях;
  - взять номер из счётчика (`profiles.allocateNumber`) → `emptyRequest…` падает (`counterUntouched`, `getNumber()`);
  - убрать проверку `MAX_LINES` → `moreThan500…` падает (заявка получила бы позиции);
  - `sentAt` без сверки даты сообщения (всегда полдень) → `fromCorrespondence…` падает;
  - `requireOurFile` без фильтра направления (в `findOurFiles` убрать `m.direction = :direction`) → `notOurFileIs404` падает.
  Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/ChatFileRow.java src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferHints.java \
  src/main/java/com/vladoose/nir/service/ReadyOfferService.java src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java \
  src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java src/test/java/com/vladoose/nir/readyoffer/ReadyOfferAttachTest.java
git commit -m "feat(ready-offer): занести готовое КП — строки и цены из файла, позиции, закупка, файл из переписки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Подсказка «КП ушло клиенту», файлы для окна, «Не КП», «Отметить отправленным»

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/OfferFileCandidate.java`
- Modify: `src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferHints.java` (`hint`)
- Modify: `src/main/java/com/vladoose/nir/service/ReadyOfferService.java` (`candidates`, `dismiss`, `markSent`)
- Modify: `src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java` (`markOfferHintDismissed`)
- Modify: `src/main/java/com/vladoose/nir/dto/response/DealProgress.java` (`Next.hintId`)
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestProgressBuilder.java` (подписи КП, подсказка)
- Test: `src/test/java/com/vladoose/nir/readyoffer/ReadyOfferHintsTest.java`

**Interfaces:**
- Consumes: всё из задачи 5, `OfferLabels`, `DealTexts.ddMM`, `ClientOfferService.setStatus(id, status, sentAt)`.
- Produces:
  - `record DealProgress.Next(String text, String action, Long refId, Long hintId)` + конструктор `(text, action, refId)`;
  - действия «Дальше»: `ATTACH_OFFER_FILE` (refId — пусто, hintId — файл), `MARK_OFFER_SENT` (refId — КП, hintId — файл);
  - `ReadyOfferHints.hint(Tender t, String stageCode, List<ClientOffer> offersNewestFirst) : Optional<Next>`;
  - `record OfferFileCandidate(Long attachmentId, String fileName, Long sizeBytes, String kind, OffsetDateTime sentAt, LocalDate sentOn, String channel, String subject, String text, boolean looksLikeOffer, Long usedByOfferId, String usedByOfferLabel, Long aisOfferId, String aisOfferLabel, boolean aisOfferDraft)`;
  - `ReadyOfferService.candidates(Long tenderId) : List<OfferFileCandidate>`, `dismiss(Long tenderId, Long attachmentId)`, `markSent(Long tenderId, Long attachmentId) : ClientOffer`.

- [ ] **Step 1: Тест**

`src/test/java/com/vladoose/nir/readyoffer/ReadyOfferHintsTest.java`:

```java
package com.vladoose.nir.readyoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.OfferFileCandidate;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.ReadyOfferService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.readyoffer.ReadyOfferFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** Подсказка «КП ушло клиенту» и окно «Готовое КП» (спека 2026-10-19 §3.2, §3.4; Review Focus 2). */
@SpringBootTest
@Transactional
class ReadyOfferHintsTest {

    @Autowired ReadyOfferService service;
    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestViewBuilder views;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService clientOffers;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired EntityManager em;

    static final OffsetDateTime LEAD_AT = OffsetDateTime.parse("2026-10-14T09:00:00Z");

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Заявка с позицией из обращения с почтовой перепиской. */
    private Tender deal(Chat c) {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Кушетка", null, 1, null, null, null)), LinesMode.APPEND);
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос КП").contactName("Клиент").status(LeadStatus.CONVERTED).chat(c).privateRequest(t)
                .receivedAt(LEAD_AT).build());
        return t;
    }

    private Chat chat(LeadChannel channel) {
        return chats.save(Chat.builder().market(Market.KZ).channel(channel).account("info@westmed.kz")
                .externalChatId("zz" + System.nanoTime() + "@clinic.kz").title("Клиника").build());
    }

    private ChatAttachment file(Chat c, LeadDirection dir, String at, String subject, String body, String name) throws Exception {
        ChatMessage m = messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(dir)
                .type(ChatMessageType.EMAIL).subject(subject).body(body).sentAt(OffsetDateTime.parse(at)).build());
        byte[] content = ReadyOfferFixtures.fatherKp("24.09.2026", List.<String[]>of(new String[]{"Кушетка", "1", "100", "100"}), "100");
        return attachments.save(ChatAttachment.builder().message(m).fileName(name).mimeType("application/octet-stream")
                .sizeBytes((long) content.length).content(content).build());
    }

    private DealProgress.Next next(Tender t) {
        em.flush();
        return views.build(requests.findById(t.getId())).getProgress().next();
    }

    @Test
    void ourOfferLetter_hintToAttach_goneAfterAttach() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ChatAttachment a = file(c, LeadDirection.OUT, "2026-10-15T07:30:00Z", "Коммерческое предложение West-Med", "Во вложении.", "Предложение.docx");

        DealProgress.Next n = next(t);
        assertThat(n.action()).isEqualTo("ATTACH_OFFER_FILE");
        assertThat(n.hintId()).isEqualTo(a.getId());
        assertThat(n.text()).isEqualTo("Похоже, КП ушло клиенту 15.10 письмом — «Предложение.docx»");

        service.attach(t.getId(), null, a.getId(), ClientOfferStatus.SENT, LocalDate.of(2026, 10, 15), "vlad");
        assertThat(next(t).action()).isEqualTo("OFFER_DECISION");
    }

    @Test
    void noHintForIncomingFilesWithoutWordsOrPictures_latinKpInNameHints() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        file(c, LeadDirection.IN, "2026-10-15T07:00:00Z", "КП", "Ваше КП", "КП клиента.docx");
        file(c, LeadDirection.OUT, "2026-10-15T07:10:00Z", "Счёт", "Счёт во вложении", "Счёт.pdf");
        file(c, LeadDirection.OUT, "2026-10-15T07:20:00Z", "КП", "Фото", "photo.jpg");
        assertThat(next(t).action()).isEqualTo("REQUEST_PRICES");

        ChatAttachment kp = file(c, LeadDirection.OUT, "2026-10-15T08:00:00Z", null, null, "KP-235143.pdf");
        assertThat(next(t).hintId()).isEqualTo(kp.getId());
    }

    @Test
    void dismissedFileNoHint_olderFileHintedNext() throws Exception {
        Chat c = chat(LeadChannel.WHATSAPP);
        Tender t = deal(c);
        ChatAttachment older = file(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", null, "Высылаем КП", "kp v1.pdf");
        ChatAttachment newer = file(c, LeadDirection.OUT, "2026-10-15T08:00:00Z", null, "Высылаем КП", "kp v2.pdf");
        assertThat(next(t).hintId()).isEqualTo(newer.getId());
        assertThat(next(t).text()).contains("в WhatsApp");

        service.dismiss(t.getId(), newer.getId());
        em.clear();
        assertThat(next(t).hintId()).isEqualTo(older.getId());
    }

    @Test
    void aisDraftPdfInLetter_markSentWithLetterTime() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ClientOffer built = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        String name = clientOffers.fileBaseName(built) + ".pdf";
        ChatAttachment a = file(c, LeadDirection.OUT, "2026-10-15T07:30:00Z", "Re: Запрос", "Добрый день", name);

        DealProgress.Next n = next(t);
        assertThat(n.action()).isEqualTo("MARK_OFFER_SENT");
        assertThat(n.refId()).isEqualTo(built.getId());
        assertThat(n.text()).isEqualTo("КП № " + built.getNumber() + " ушло клиенту 15.10 письмом — отметить отправленным?");

        ClientOffer sent = service.markSent(t.getId(), a.getId());
        assertThat(sent.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(sent.getSentAt()).isAtSameInstantAs(OffsetDateTime.parse("2026-10-15T07:30:00Z"));
        assertThat(next(t).action()).isEqualTo("OFFER_DECISION");
    }

    @Test
    void candidatesNewestFirstWithFlagsAndMarketDate() throws Exception {   // Review Focus 2
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ChatAttachment plain = file(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", "Паспорт", "Паспорт изделия", "Паспорт.pdf");
        ChatAttachment late = file(c, LeadDirection.OUT, "2026-10-15T20:30:00Z", "КП", "Высылаем КП", "КП.docx");
        file(c, LeadDirection.IN, "2026-10-15T21:00:00Z", "КП", "Клиент прислал", "Смета.xlsx");

        List<OfferFileCandidate> list = service.candidates(t.getId());
        assertThat(list).extracting(OfferFileCandidate::attachmentId).containsExactly(late.getId(), plain.getId());
        assertThat(list.get(0).looksLikeOffer()).isTrue();
        assertThat(list.get(0).sentOn()).isEqualTo(LocalDate.of(2026, 10, 16));   // 20:30 UTC — уже 16.10 в Уральске
        assertThat(list.get(1).looksLikeOffer()).isFalse();

        ClientOffer o = service.attach(t.getId(), null, late.getId(), ClientOfferStatus.SENT, LocalDate.of(2026, 10, 16), "vlad");
        assertThat(service.candidates(t.getId()).get(0).usedByOfferId()).isEqualTo(o.getId());
    }

    @Test
    void closedDealNoHint() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        file(c, LeadDirection.OUT, "2026-10-15T07:30:00Z", "КП", "Высылаем КП", "КП.pdf");
        requests.close(t.getId(), PrivateRequestStatus.WON);
        assertThat(next(t).action()).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.readyoffer.ReadyOfferHintsTest'`
Expected: FAIL — компиляция (`hintId()`, `candidates`, `dismiss`, `markSent` нет).

- [ ] **Step 3: `DealProgress.Next` с файлом подсказки**

```java
public record DealProgress(List<Step> steps, Next next) {
    public record Step(String key, String state, String label, String detail) {}

    /** hintId — файл переписки подсказки «КП ушло клиенту» (спека 2026-10-19 §3.4); у остальных «Дальше» — пусто. */
    public record Next(String text, String action, Long refId, Long hintId) {
        public Next(String text, String action, Long refId) {
            this(text, action, refId, null);
        }
    }
}
```

- [ ] **Step 4: Подсказка в `ReadyOfferHints`**

```java
    /** Этапы, когда у сделки нет отправленного или принятого КП (спека §3.4). */
    private static final Set<String> HINT_STAGES = Set.of("EMPTY", "NEW", "REQUESTED", "PRICED", "KP_DRAFT", "KP_REJECTED");

    /**
     * Подсказка «Дальше» (спека §3.4): самый поздний наш файл, ещё не занесённый и не отмеченный «Не КП»: PDF самой АИС с
     * черновиком этой сделки — «Отметить отправленным»; файл, похожий на КП, — «Занести КП». PDF самой АИС с КП в другом
     * статусе пропускается.
     */
    public Optional<DealProgress.Next> hint(Tender t, String stageCode, List<ClientOffer> offersNewestFirst) {
        if (!HINT_STAGES.contains(stageCode)) return Optional.empty();
        List<ChatFileRow> ours = ourFiles(t);
        if (ours.isEmpty()) return Optional.empty();
        Set<Long> used = usedBy(offersNewestFirst).keySet();
        ZoneId zone = (t.getMarket() == null ? Market.KZ : t.getMarket()).zone();
        for (ChatFileRow f : ours) {
            if (f.hintDismissed() || used.contains(f.attachmentId())) continue;
            String when = DealTexts.ddMM(f.sentAt(), zone) + channelWord(f.channel());
            Optional<ClientOffer> ais = aisOffer(nameHeader(f.fileName()), offersNewestFirst);
            if (ais.isPresent()) {
                if (ais.get().getStatus() != ClientOfferStatus.DRAFT) continue;
                return Optional.of(new DealProgress.Next(OfferLabels.of(ais.get()) + " ушло клиенту " + when
                        + " — отметить отправленным?", "MARK_OFFER_SENT", ais.get().getId(), f.attachmentId()));
            }
            if (looksLikeOffer(f)) {
                return Optional.of(new DealProgress.Next("Похоже, КП ушло клиенту " + when + " — «" + nz(f.fileName()) + "»",
                        "ATTACH_OFFER_FILE", null, f.attachmentId()));
            }
        }
        return Optional.empty();
    }
```

(импорты `DealProgress`, `DealTexts`, `OfferLabels`, `java.time.ZoneId`.)

- [ ] **Step 5: Сервис — файлы для окна, «Не КП», «Отметить отправленным»**

`src/main/java/com/vladoose/nir/dto/response/OfferFileCandidate.java`:

```java
package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Файл переписки для окна «Готовое КП» (спека 2026-10-19 §3.2): sentOn — дата отправки по часам рынка (решение при
 * планировании 12); usedBy… — уже занесён в это КП; aisOffer… — это PDF / Word самой АИС с КП этой сделки.
 */
public record OfferFileCandidate(Long attachmentId, String fileName, Long sizeBytes, String kind, OffsetDateTime sentAt,
                                 LocalDate sentOn, String channel, String subject, String text, boolean looksLikeOffer,
                                 Long usedByOfferId, String usedByOfferLabel, Long aisOfferId, String aisOfferLabel,
                                 boolean aisOfferDraft) {}
```

`ChatAttachmentRepository`:

```java
    @Modifying
    @Query("update ChatAttachment a set a.offerHintDismissed = true where a.id = :id")
    int markOfferHintDismissed(@Param("id") Long id);
```

`ReadyOfferService` — поле `ChatAttachmentRepository attachments` в конструкторе и методы:

```java
    @Transactional(readOnly = true)
    public List<OfferFileCandidate> candidates(Long tenderId) {
        Tender t = privateRequests.findById(tenderId);
        ZoneId zone = (t.getMarket() == null ? MarketContext.get() : t.getMarket()).zone();
        List<ClientOffer> list = repository.findByTenderIdOrderByIdDesc(t.getId());
        Map<Long, ClientOffer> used = hints.usedBy(list);
        List<OfferFileCandidate> out = new ArrayList<>();
        for (ChatFileRow f : hints.ourFiles(t)) {
            ClientOffer u = used.get(f.attachmentId());
            ClientOffer ais = ReadyOfferHints.aisOffer(ReadyOfferHints.nameHeader(f.fileName()), list).orElse(null);
            out.add(new OfferFileCandidate(f.attachmentId(), f.fileName(), f.sizeBytes(),
                    SupplierReplyFiles.kind(f.fileName() == null ? "" : f.fileName(), f.mimeType(), new byte[0]).name(),
                    f.sentAt(), f.sentAt().atZoneSameInstant(zone).toLocalDate(), f.channel() == null ? null : f.channel().name(),
                    f.subject(), snippet(f.body()), ReadyOfferHints.looksLikeOffer(f),
                    u == null ? null : u.getId(), u == null ? null : OfferLabels.of(u),
                    ais == null ? null : ais.getId(), ais == null ? null : OfferLabels.of(ais),
                    ais != null && ais.getStatus() == ClientOfferStatus.DRAFT));
        }
        return out;
    }

    /** «Не КП» / «Нет»: подсказка про этот файл больше не показывается. */
    @Transactional
    public void dismiss(Long tenderId, Long attachmentId) {
        Tender t = privateRequests.findById(tenderId);
        hints.requireOurFile(t, attachmentId);
        attachments.markOfferHintDismissed(attachmentId);
    }

    /** «Отметить отправленным»: черновик конструктора, чей PDF / Word ушёл этим сообщением, — «Отправлено» его временем. */
    @Transactional
    public ClientOffer markSent(Long tenderId, Long attachmentId) {
        Tender t = privateRequests.findById(tenderId);
        ChatFileRow f = hints.requireOurFile(t, attachmentId);
        ClientOffer o = ReadyOfferHints.aisOffer(ReadyOfferHints.nameHeader(f.fileName()), repository.findByTenderIdOrderByIdDesc(t.getId()))
                .orElseThrow(() -> new BadRequestException("Это не файл КП из конструктора этой сделки"));
        if (o.getStatus() != ClientOfferStatus.DRAFT) throw new BadRequestException(OfferLabels.of(o) + " уже не черновик");
        return offers.setStatus(o.getId(), ClientOfferStatus.SENT, f.sentAt());
    }

    private static String snippet(String body) {
        if (body == null) return null;
        String s = body.replaceAll("\\s+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
```

(импорты `ChatFileRow`, `OfferFileCandidate`, `ChatAttachmentRepository`.)

- [ ] **Step 6: Этапы заявки — подписи КП и подсказка**

`PrivateRequestProgressBuilder`:
- в конструктор — `ReadyOfferHints hints` (поле);
- `ClientOffer main = mainOffer(offers.findByTenderIdOrderByIdDesc(t.getId()));` → `List<ClientOffer> allOffers = offers.findByTenderIdOrderByIdDesc(t.getId()); ClientOffer main = mainOffer(allOffers);`
- `"№ " + main.getNumber()` → `OfferLabels.numberOrDate(main)`; `"Доделайте и отправьте КП № " + main.getNumber()` → `"Доделайте и отправьте " + OfferLabels.of(main)`; `"КП № " + main.getNumber() + " отправлено"` → `OfferLabels.of(main) + " отправлено"`; `"Клиент отказался от КП № " + main.getNumber()` → `"Клиент отказался от " + OfferLabels.of(main)`;
- в конце:

```java
        // подсказка «КП ушло клиенту» (спека 2026-10-19 §3.4) важнее обычного «Дальше»
        Next hint = hints.hint(t, code, allOffers).orElse(null);
        return new DealProgress(steps, hint != null ? hint : next);
```

- [ ] **Step 7: Прогнать — зелёные, плюс этапы и лента**

Run: `./gradlew test --tests 'com.vladoose.nir.readyoffer.*' --tests 'com.vladoose.nir.deal.*'`
Expected: PASS (тексты «КП № N…» у КП с номером прежние).

- [ ] **Step 8: Мутации**
  - не пропускать `hintDismissed` → `dismissedFileNoHint…` падает;
  - не пропускать занесённые (`used`) → `ourOfferLetter…` падает (после занесения — снова подсказка вместо «Согласился»);
  - добавить `KP_SENT` в `HINT_STAGES` → `ourOfferLetter…` и `aisDraftPdf…` падают;
  - `sentOn` по UTC (`f.sentAt().toLocalDate()`) → `candidatesNewestFirst…` падает (15.10 вместо 16.10);
  - убрать проверку вида файла в `looksLikeOffer` → `noHintFor…Pictures` падает (photo.jpg).
  Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/OfferFileCandidate.java src/main/java/com/vladoose/nir/dto/response/DealProgress.java \
  src/main/java/com/vladoose/nir/service/readyoffer/ReadyOfferHints.java src/main/java/com/vladoose/nir/service/ReadyOfferService.java \
  src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java \
  src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestProgressBuilder.java \
  src/test/java/com/vladoose/nir/readyoffer/ReadyOfferHintsTest.java
git commit -m "feat(ready-offer): подсказка «КП ушло клиенту» по переписке, «Не КП», «Отметить отправленным»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: API, ответ КП с файлом, журнал, лента

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/OfferFileView.java`
- Create: `src/main/java/com/vladoose/nir/controller/ReadyOfferController.java`
- Modify: `src/main/java/com/vladoose/nir/service/ReadyOfferService.java` (`file(Long offerId)`)
- Modify: `src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java` (`origin`, `file`), `ClientOfferListItemResponse.java` (`origin`)
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestOfferRef.java` (`origin`), `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java`
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java` (`addOffers`)
- Test: `src/test/java/com/vladoose/nir/readyoffer/ReadyOfferApiTest.java`

**Interfaces:**
- Produces (HTTP, спека §6 с решениями при планировании 1–2):
  - `GET /api/private-requests/{id}/offer-file/candidates` → `OfferFileCandidate[]`;
  - `POST /api/private-requests/{id}/offer-file` (multipart: `file` или `attachmentId`, `status`, `sentOn` = `ГГГГ-ММ-ДД`) → `ClientOfferResponse`;
  - `POST /api/private-requests/{id}/offer-hints/{attachmentId}/dismiss` → 204;
  - `POST /api/private-requests/{id}/offer-hints/{attachmentId}/mark-sent` → `ClientOfferResponse`;
  - `GET /api/client-offers/{id}/file` (attachment, RFC 5987, `nosniff`, `no-store`), `GET /api/client-offers/{id}/file/view` → `{kind, pages, tables}`, `GET /api/client-offers/{id}/file/pages/{n}` → PNG;
  - `ClientOfferResponse.origin`, `ClientOfferResponse.file` (`ClientOfferFileMeta` или null), `ClientOfferListItemResponse.origin`, `PrivateRequestOfferRef.origin`; лента: «КП … занесено из файла «…»».

- [ ] **Step 1: Тест**

`src/test/java/com/vladoose/nir/readyoffer/ReadyOfferApiTest.java`:

```java
package com.vladoose.nir.readyoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.readyoffer.ReadyOfferFixtures;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API готового КП (спека 2026-10-19 §6): права, рынок, файл, просмотр, ответ КП, журнал, заявка, лента. */
@SpringBootTest
@Transactional
class ReadyOfferApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DealTimelineService timeline;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender request() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        MarketContext.clear();
        return t;
    }

    private MockMultipartHttpServletRequestBuilder attach(Tender t, String name, byte[] b) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/private-requests/" + t.getId() + "/offer-file")
                .file(new MockMultipartFile("file", name, "application/octet-stream", b))
                .param("status", "SENT").param("sentOn", "2026-10-15").header("X-Market", "KZ");
    }

    private long attachWord(Tender t) throws Exception {
        byte[] kp = ReadyOfferFixtures.fatherKp("24.09.2026", List.<String[]>of(new String[]{"Кушетка КМС-01", "2", "95 000", "190 000"}), "190 000");
        String body = mvc.perform(attach(t, "КП West-Med 24.09.2026.docx", kp).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("FILE"))
                .andExpect(jsonPath("$.file.name").value("КП West-Med 24.09.2026.docx"))
                .andExpect(jsonPath("$.file.fileTotal").value(190000.0))
                .andExpect(jsonPath("$.items[0].priceOverride").value(95000.0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.id").longValue();
    }

    @Test
    void operatorReadsButCannotWrite() throws Exception {
        Tender t = request();
        mvc.perform(get("/api/private-requests/" + t.getId() + "/offer-file/candidates").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk());
        mvc.perform(attach(t, "КП.docx", new byte[]{1}).with(user("op").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/private-requests/" + t.getId() + "/offer-hints/1/dismiss").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/private-requests/" + t.getId() + "/offer-hints/1/mark-sent").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void wordFile_downloadViewJournalRequestTimeline() throws Exception {
        Tender t = request();
        long id = attachWord(t);

        mvc.perform(get("/api/client-offers/" + id + "/file").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        mvc.perform(get("/api/client-offers/" + id + "/file/view").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("WORD"))
                .andExpect(jsonPath("$.tables[0].rows[0].cells[1]").value("Кушетка КМС-01"));
        mvc.perform(get("/api/client-offers").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].origin").value("FILE"));
        mvc.perform(get("/api/private-requests/" + t.getId()).header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(jsonPath("$.offers[0].origin").value("FILE"));

        MarketContext.set(Market.KZ);
        assertThat(timeline.forPrivateRequest(t.getId(), null, 50).items()).anySatisfy(i ->
                assertThat(i.text()).isEqualTo("КП от 24.09.2026 занесено из файла «КП West-Med 24.09.2026.docx»"));
    }

    @Test
    void pdfPagesAndOtherMarket404() throws Exception {
        Tender t = request();
        String body = mvc.perform(attach(t, "КП.pdf", ReplyFixtures.pdfBytes(2)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.id").longValue();
        mvc.perform(get("/api/client-offers/" + id + "/file/pages/1").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/client-offers/" + id + "/file").header("X-Market", "KZ").with(user("op").roles("OPERATOR")))
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get("/api/client-offers/" + id + "/file").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/private-requests/" + t.getId() + "/offer-file/candidates").header("X-Market", "RF").with(user("op").roles("OPERATOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void badStatusOrDateIs400() throws Exception {
        Tender t = request();
        mvc.perform(multipart("/api/private-requests/" + t.getId() + "/offer-file")
                        .file(new MockMultipartFile("file", "КП.docx", null, new byte[]{1}))
                        .param("status", "WHATEVER").param("sentOn", "2026-10-15").header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/private-requests/" + t.getId() + "/offer-file")
                        .file(new MockMultipartFile("file", "КП.docx", null, new byte[]{1}))
                        .param("status", "SENT").param("sentOn", "15.10.2026").header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.readyoffer.ReadyOfferApiTest'`
Expected: FAIL — 404 на путях (контроллера нет), `$.origin` нет.

- [ ] **Step 3: DTO, маппер, заявка, лента**

`src/main/java/com/vladoose/nir/dto/response/OfferFileView.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ReplyTable;

import java.util.List;

/** Документ готового КП для просмотра (решение при планировании 2): вид, страницы PDF, таблицы файла. */
public record OfferFileView(String kind, Integer pages, List<ReplyTable> tables) {}
```

`ClientOfferResponse` — поля `private ClientOfferOrigin origin;` и `/** Файл готового КП (без байтов и таблиц) — только у origin = FILE. */ private ClientOfferFileMeta file;`. `ClientOfferListItemResponse` — `private ClientOfferOrigin origin;`.

`ClientOfferMapper` — в конструктор `ClientOfferFileRepository files`; в `toResponse` после `r.setNumber(...)`:

```java
        r.setOrigin(o.getOrigin());
        if (o.getOrigin() == ClientOfferOrigin.FILE && o.getId() != null) {
            r.setFile(files.findMetaByOfferIds(List.of(o.getId())).stream().findFirst().orElse(null));
        }
```

в `toListItem` — `r.setOrigin(o.getOrigin());`.

`PrivateRequestOfferRef` — последним компонентом `String origin`; в `PrivateRequestViewBuilder` — `..., o.getMarket().currencyCode(), o.getOrigin().name())`.

`DealTimelineService` — в конструктор `ClientOfferFileRepository offerFiles`; `addOffers`:

```java
    private void addOffers(List<DealTimelineItem> all, Long tenderId) {
        List<ClientOffer> list = offers.findByTenderIdOrderByIdDesc(tenderId);
        Map<Long, String> fileNames = new HashMap<>();
        List<Long> fileIds = list.stream().filter(o -> o.getOrigin() == ClientOfferOrigin.FILE).map(ClientOffer::getId).toList();
        if (!fileIds.isEmpty()) for (ClientOfferFileMeta m : offerFiles.findMetaByOfferIds(fileIds)) fileNames.put(m.offerId(), m.name());
        for (ClientOffer o : list) {
            String created = o.getOrigin() == ClientOfferOrigin.FILE
                    ? OfferLabels.of(o) + " занесено из файла" + (fileNames.containsKey(o.getId()) ? " «" + fileNames.get(o.getId()) + "»" : "")
                    : OfferLabels.of(o) + " создано";
            all.add(item(o.getCreatedAt(), "OFFER_CREATED", created, o.getCreatedBy(), "OFFER", o.getId()));
            if (o.getSentAt() != null) {
                all.add(item(o.getSentAt(), "OFFER_SENT", OfferLabels.of(o) + " отправлено", null, "OFFER", o.getId()));
            }
        }
    }
```

- [ ] **Step 4: Файл КП в сервисе и контроллер**

`ReadyOfferService`:

```java
    /** Файл готового КП: гард рынка — через КП (404 чужому), у КП без файла — 404. */
    @Transactional(readOnly = true)
    public ClientOfferFile file(Long offerId) {
        ClientOffer o = offers.get(offerId);
        return files.findByOfferId(o.getId()).orElseThrow(() -> new NotFoundException("У КП нет файла"));
    }
```

`src/main/java/com/vladoose/nir/controller/ReadyOfferController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.dto.response.OfferFileCandidate;
import com.vladoose.nir.dto.response.OfferFileView;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferFile;
import com.vladoose.nir.entity.ClientOfferStatus;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.ClientOfferMapper;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.ReadyOfferService;
import com.vladoose.nir.service.supplierreply.ReplyPdfPages;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Готовое КП (спека 2026-10-19 §6): читать и скачивать — любой вошедший, заносить и отмечать — администратор. */
@RestController
public class ReadyOfferController {

    private final ReadyOfferService service;
    private final ClientOfferService offers;
    private final ClientOfferMapper mapper;
    private final ReplyPdfPages pdf;

    public ReadyOfferController(ReadyOfferService service, ClientOfferService offers, ClientOfferMapper mapper, ReplyPdfPages pdf) {
        this.service = service;
        this.offers = offers;
        this.mapper = mapper;
        this.pdf = pdf;
    }

    @GetMapping("/api/private-requests/{id}/offer-file/candidates")
    public List<OfferFileCandidate> candidates(@PathVariable Long id) {
        return service.candidates(id);
    }

    @PostMapping(value = "/api/private-requests/{id}/offer-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse attach(@PathVariable Long id,
                                      @RequestParam(value = "file", required = false) MultipartFile file,
                                      @RequestParam(value = "attachmentId", required = false) Long attachmentId,
                                      @RequestParam("status") String status,
                                      @RequestParam("sentOn") String sentOn) throws IOException {
        ReadyOfferService.Upload upload = file == null || file.isEmpty() ? null
                : new ReadyOfferService.Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return full(service.attach(id, upload, attachmentId, status(status), date(sentOn), CurrentUser.name()));
    }

    @PostMapping("/api/private-requests/{id}/offer-hints/{attachmentId}/dismiss")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> dismiss(@PathVariable Long id, @PathVariable Long attachmentId) {
        service.dismiss(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/private-requests/{id}/offer-hints/{attachmentId}/mark-sent")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse markSent(@PathVariable Long id, @PathVariable Long attachmentId) {
        return full(service.markSent(id, attachmentId));
    }

    @GetMapping("/api/client-offers/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        ClientOfferFile f = service.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType(f.getKind(), f.getFileName())))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.getContent());
    }

    @GetMapping("/api/client-offers/{id}/file/view")
    public OfferFileView view(@PathVariable Long id) {
        ClientOfferFile f = service.file(id);
        return new OfferFileView(f.getKind().name(), f.getPages(), f.getTables() == null ? List.of() : f.getTables());
    }

    /** Страница PDF картинкой, n — с 1 (до 30, как у ответов поставщиков). */
    @GetMapping("/api/client-offers/{id}/file/pages/{n}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> page(@PathVariable Long id, @PathVariable int n) throws IOException {
        ClientOfferFile f = service.file(id);
        if (f.getKind() != ReplyFileKind.PDF) throw new NotFoundException("Это не PDF");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(pdf.page(f.getContent(), n - 1));
    }

    private ClientOfferResponse full(ClientOffer o) {
        return mapper.toResponse(o, offers.calculate(o), offers.fileBaseName(o));
    }

    /** Тип — только из белого списка; остальное — octet-stream (и всегда attachment + nosniff). */
    static String contentType(ReplyFileKind kind, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case PDF -> "application/pdf";
            case IMAGE -> n.endsWith(".png") ? "image/png" : n.endsWith(".gif") ? "image/gif" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    private static ClientOfferStatus status(String s) {
        try {
            return ClientOfferStatus.valueOf(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Статус готового КП — «Отправлено» или «Клиент согласился»");
        }
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException("Дата отправки — в виде ГГГГ-ММ-ДД");
        }
    }
}
```

- [ ] **Step 5: Прогнать — зелёный, плюс КП, сделка, заявки**

Run: `./gradlew test --tests 'com.vladoose.nir.readyoffer.*' --tests 'com.vladoose.nir.clientoffer.*' --tests 'com.vladoose.nir.deal.*' --tests 'com.vladoose.nir.privaterequest.*'`
Expected: PASS.

- [ ] **Step 6: Мутации**
  - убрать `@PreAuthorize` у `attach` → `operatorReadsButCannotWrite` падает;
  - `contentType` всегда `application/pdf` → `wordFile_download…` падает;
  - лента: вернуть «создано» и для файла → `wordFile_…Timeline` падает.
  Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/controller/ReadyOfferController.java src/main/java/com/vladoose/nir/dto/response/OfferFileView.java \
  src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java src/main/java/com/vladoose/nir/dto/response/ClientOfferListItemResponse.java \
  src/main/java/com/vladoose/nir/dto/response/PrivateRequestOfferRef.java src/main/java/com/vladoose/nir/service/ClientOfferMapper.java \
  src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java \
  src/main/java/com/vladoose/nir/service/ReadyOfferService.java src/test/java/com/vladoose/nir/readyoffer/ReadyOfferApiTest.java
git commit -m "feat(ready-offer): API готового КП — занести, файлы переписки, «Не КП», файл, просмотр, лента

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Фронт — окно «Готовое КП», раздел «КП клиенту», подсказка в «Дальше»

**Files:**
- Create: `frontend/src/app/pages/private-requests/pr-offer-file-dialog.component.ts`
- Modify: `frontend/src/app/shared/client-offer.ts` (типы, `offerLabel`, `offerFileCheck`)
- Modify: `frontend/src/app/shared/deal.ts`, `frontend/src/app/shared/private-request.ts` (`PrOfferRef.origin`, `number: number | null`)
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/shared/deal-progress.component.ts`
- Modify: `frontend/src/app/pages/private-requests/pr-offers.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-page.component.ts`

**Interfaces:**
- Consumes: HTTP задачи 7.
- Produces (TS): `OfferOrigin`, `OfferFileKind`, `OfferFileMeta`, `OfferFileTable`, `OfferFileView`, `OfferFileCandidate`; `offerLabel(o)`, `offerFileCheck(o)`; `ClientOffer.number: number | null`, `ClientOffer.origin`, `ClientOffer.file`; `DealNext.hintId`, `DealAction.hintId`; `ApiService.getOfferFileCandidates / attachOfferFile / dismissOfferHint / markOfferHintSent / downloadClientOfferFile / getClientOfferFileView / clientOfferFilePage`; `PrOffersComponent.openFile(attachmentId | null)` и `(changed)`.

- [ ] **Step 1: Типы и помощники (`shared/client-offer.ts`)**

В `interface ClientOffer`: `id: number; number: number | null; ...` и добавить `origin: OfferOrigin; file: OfferFileMeta | null;`. Новое рядом:

```ts
export type OfferOrigin = 'BUILDER' | 'FILE';
export type OfferFileKind = 'EXCEL' | 'WORD' | 'PDF' | 'IMAGE' | 'OTHER';

/** Файл готового КП (спека 2026-10-19 §6): без байтов и таблиц — таблицы отдаёт /file/view. */
export interface OfferFileMeta {
  name: string; kind: OfferFileKind; sizeBytes: number; parseStatus: string; parseNote: string | null;
  pages: number | null; fileTotal: number | null;
}
export interface OfferFileTable {
  source: string; headerRow: number; columns: { index: number; header: string; role: string }[];
  rows: { fileRow: number; cells: string[] }[];
}
export interface OfferFileView { kind: OfferFileKind; pages: number | null; tables: OfferFileTable[]; }

/** Файл переписки сделки для окна «Готовое КП» (спека §3.2): sentOn — дата отправки по часам рынка. */
export interface OfferFileCandidate {
  attachmentId: number; fileName: string; sizeBytes: number | null; kind: OfferFileKind; sentAt: string; sentOn: string;
  channel: string | null; subject: string | null; text: string | null; looksLikeOffer: boolean;
  usedByOfferId: number | null; usedByOfferLabel: string | null; aisOfferId: number | null; aisOfferLabel: string | null;
  aisOfferDraft: boolean;
}

/** «КП № 24» или «КП от 24.09.2026» — у КП из файла номера может не быть. */
export function offerLabel(o: { number: number | null; offerDate: string }): string {
  return o.number != null ? 'КП № ' + o.number : 'КП от ' + dateText(o.offerDate);
}

function positionsWord(n: number): string {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return 'позиция';
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return 'позиции';
  return 'позиций';
}

/** Строка сверки КП из файла (спека §3.3): итог по строкам против «ИТОГО» файла; не из файла — null. */
export function offerFileCheck(o: ClientOffer): { tone: 'ok' | 'warn' | 'info'; text: string } | null {
  const f = o.file;
  if (o.origin !== 'FILE' || !f) return null;
  const cur = o.currency === 'RUB' ? 'руб.' : 'тг';
  const head = `Из файла «${f.name}»`;
  if (f.kind === 'PDF' || f.kind === 'IMAGE') return { tone: 'info', text: `${head} — цены впишите по документу справа` };
  if (f.parseStatus !== 'PARSED') return { tone: 'warn', text: `${head}: ${f.parseNote || 'файл не разобран — впишите цены по документу'}` };
  if (f.parseNote) return { tone: 'warn', text: `${head}: ${f.parseNote}` };
  const n = o.totals.itemCount;
  const lines = `${head} · ${n} ${positionsWord(n)}`;
  if (f.fileTotal == null) {
    return { tone: 'info', text: `${lines} · итога в файле нет — итог по строкам ${money(o.totals.sum)} ${cur}; если это варианты на выбор, удалите лишние строки, когда клиент выберет` };
  }
  return Math.abs(f.fileTotal - o.totals.sum) < 0.005
    ? { tone: 'ok', text: `${lines} · итог ${money(o.totals.sum)} ${cur} = «ИТОГО» в файле ✓` }
    : { tone: 'warn', text: `${lines} · итог по строкам ${money(o.totals.sum)} ≠ ${money(f.fileTotal)} ${cur} в файле — проверьте строки` };
}
```

`shared/private-request.ts`: `export interface PrOfferRef { id: number; number: number | null; offerDate: string; status: string; itemCount: number; totalAmount: number | null; currency: string; origin: 'BUILDER' | 'FILE'; }`.

`shared/deal.ts`:

```ts
export interface DealNext { text: string; action: string | null; refId: number | null; hintId?: number | null; }
export interface DealAction { action: string; refId: number | null; hintId?: number | null; decision?: 'ACCEPTED' | 'REJECTED'; }
```

и в `ACTION_LABELS` добавить `ATTACH_OFFER_FILE: 'Занести КП', MARK_OFFER_SENT: 'Отметить отправленным',`.

- [ ] **Step 2: `ApiService`**

В `import type { … } from '../shared/client-offer';` добавить `OfferFileCandidate, OfferFileView`. Методы в блок «КП клиенту»:

```ts
  // === Готовое КП (спека 2026-10-19) ===
  getOfferFileCandidates(requestId: number): Observable<OfferFileCandidate[]> {
    return this.http.get<OfferFileCandidate[]>(`${this.base}/private-requests/${requestId}/offer-file/candidates`);
  }
  attachOfferFile(requestId: number, body: { file?: File; attachmentId?: number; status: 'SENT' | 'ACCEPTED'; sentOn: string }): Observable<any> {
    const fd = new FormData();
    if (body.file) fd.append('file', body.file, body.file.name);
    if (body.attachmentId != null) fd.append('attachmentId', String(body.attachmentId));
    fd.append('status', body.status);
    fd.append('sentOn', body.sentOn);
    return this.http.post<any>(`${this.base}/private-requests/${requestId}/offer-file`, fd);
  }
  dismissOfferHint(requestId: number, attachmentId: number): Observable<void> {
    return this.http.post<void>(`${this.base}/private-requests/${requestId}/offer-hints/${attachmentId}/dismiss`, {});
  }
  markOfferHintSent(requestId: number, attachmentId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/private-requests/${requestId}/offer-hints/${attachmentId}/mark-sent`, {});
  }
  downloadClientOfferFile(id: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/client-offers/${id}/file`, { responseType: 'blob', observe: 'response' });
  }
  getClientOfferFileView(id: number): Observable<OfferFileView> {
    return this.http.get<OfferFileView>(`${this.base}/client-offers/${id}/file/view`);
  }
  clientOfferFilePage(id: number, page: number): Observable<Blob> {
    return this.http.get(`${this.base}/client-offers/${id}/file/pages/${page}`, { responseType: 'blob' });
  }
```

- [ ] **Step 3: Окно**

`frontend/src/app/pages/private-requests/pr-offer-file-dialog.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import type { OfferFileCandidate } from '../../shared/client-offer';
import { marketToday } from '../../shared/deadline';
import { errorText } from '../../shared/error-text';

/** Предел загрузки — как у сервера и nginx фронта (15 МБ). */
const UPLOAD_LIMIT = 15 * 1024 * 1024;

/**
 * «Готовое КП» (спека 2026-10-19 §3.2): файл из переписки сделки (наши письма с info@ и сообщения WhatsApp) или с
 * компьютера, статус «Отправлено ДД.ММ.ГГГГ» / «Клиент уже согласился» → КП из файла, сразу редактор. PDF самой АИС с
 * черновиком этой сделки не заносится — у него «Отметить … отправленным».
 */
@Component({
  selector: 'app-pr-offer-file-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Готовое КП" (click)="$event.stopPropagation()">
        <h3>Готовое КП</h3>
        <div class="seg" role="radiogroup" aria-label="Откуда файл">
          <button type="button" role="radio" [attr.aria-checked]="mode === 'chat'" [class.on]="mode === 'chat'"
                  [disabled]="!candidates.length" (click)="setMode('chat')">Из переписки</button>
          <button type="button" role="radio" [attr.aria-checked]="mode === 'pc'" [class.on]="mode === 'pc'" (click)="setMode('pc')">С компьютера</button>
        </div>
        <p class="hint" *ngIf="loading">Загружаю файлы переписки…</p>
        <p class="hint" *ngIf="!loading && !candidates.length">В переписке сделки наших файлов нет — загрузите файл с компьютера.</p>

        <div class="files" *ngIf="mode === 'chat'">
          <div class="file" *ngFor="let c of candidates" [class.on]="picked?.attachmentId === c.attachmentId">
            <label class="f-pick">
              <input type="radio" name="kpf" [checked]="picked?.attachmentId === c.attachmentId" [disabled]="!!c.aisOfferId" (change)="pick(c)" />
              <span class="f-main">
                <span class="f-meta">{{ when(c) }} · {{ c.channel === 'EMAIL' ? 'письмо' : 'WhatsApp' }}<ng-container *ngIf="c.subject"> «{{ c.subject }}»</ng-container></span>
                <span class="f-name">📎 {{ c.fileName }}</span>
                <span class="f-flags">
                  <span class="flag" *ngIf="c.looksLikeOffer && !c.aisOfferId">похоже на КП</span>
                  <span class="flag muted" *ngIf="c.usedByOfferLabel">занесено: {{ c.usedByOfferLabel }}</span>
                  <span class="flag muted" *ngIf="c.aisOfferLabel">это {{ c.aisOfferLabel }} из АИС</span>
                </span>
              </span>
            </label>
            <button type="button" class="btn btn-line btn-sm" *ngIf="c.aisOfferDraft" [disabled]="busy" (click)="markSent(c)">
              Отметить {{ c.aisOfferLabel }} отправленным</button>
          </div>
        </div>

        <div class="pc" *ngIf="mode === 'pc'">
          <label class="btn btn-line pick-file">{{ file ? 'Другой файл' : 'Выбрать файл' }}
            <input type="file" hidden accept=".docx,.doc,.xlsx,.xls,.pdf,.jpg,.jpeg,.png,.webp,.gif" (change)="onFile($event)" /></label>
          <span class="f-name" *ngIf="file">{{ file.name }}</span>
          <p class="hint">Word, Excel, PDF или картинка, до 15 МБ. Цены из Word и Excel прочитаются сами, из PDF и картинки — впишите их по документу.</p>
        </div>

        <fieldset class="st">
          <legend>Статус</legend>
          <label class="check"><input type="radio" name="kps" [checked]="status === 'SENT'" (change)="status = 'SENT'" /> Отправлено
            <input type="date" [max]="today" [(ngModel)]="sentOn" aria-label="Дата отправки" /></label>
          <label class="check"><input type="radio" name="kps" [checked]="status === 'ACCEPTED'" (change)="status = 'ACCEPTED'" /> Клиент уже согласился</label>
        </fieldset>

        <div class="error-banner" *ngIf="error" role="alert">{{ error }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="dismiss()">Отмена</button>
          <button type="button" class="btn btn-primary" [disabled]="busy || !ready()" (click)="submit()">{{ busy ? 'Заношу…' : 'Занести' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 640px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    h3 { margin: 0; }
    .seg { display: flex; gap: 6px; flex-wrap: wrap; }
    .seg button { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .seg button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .seg button:disabled { cursor: not-allowed; opacity: .6; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .files { display: flex; flex-direction: column; gap: 6px; }
    .file { border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; display: flex; flex-direction: column; gap: 6px; }
    .file.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .f-pick { display: flex; gap: 8px; align-items: flex-start; cursor: pointer; }
    .f-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .f-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .f-name { font-weight: 600; overflow-wrap: anywhere; }
    .f-flags { display: flex; gap: 6px; flex-wrap: wrap; }
    .flag { font-size: 11px; border-radius: 999px; padding: 1px 8px; background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .flag.muted { background: var(--surface-2); color: var(--text); }
    .pc { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
    .pick-file { cursor: pointer; }
    .st { border: 1px solid var(--border); border-radius: 10px; padding: 8px 12px; display: flex; flex-direction: column; gap: 6px; margin: 0; }
    .st legend { font-size: 12px; color: var(--text-muted); padding: 0 4px; }
    .check { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 14px; }
    .check input[type="date"] { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .seg button, .check, .f-pick, .form-actions .btn, .file .btn, .pick-file { min-height: 40px; }
      .check input[type="date"] { font-size: 16px; }
    }
  `],
})
export class PrOfferFileDialogComponent implements OnInit {
  @Input({ required: true }) requestId!: number;
  /** Файл переписки из подсказки «Дальше» — выбран сразу. */
  @Input() preselect: number | null = null;
  @Output() close = new EventEmitter<void>();
  /** КП отмечено отправленным прямо из окна — странице перечитать этапы. */
  @Output() changed = new EventEmitter<void>();

  candidates: OfferFileCandidate[] = [];
  loading = true;
  mode: 'chat' | 'pc' = 'chat';
  picked: OfferFileCandidate | null = null;
  file: File | null = null;
  status: 'SENT' | 'ACCEPTED' = 'SENT';
  readonly today: string;
  sentOn: string;
  busy = false;
  error = '';

  constructor(private api: ApiService, private router: Router, private notify: NotificationService, market: MarketService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {
    this.today = marketToday(market.value);
    this.sentOn = this.today;
  }

  ngOnInit() {
    this.api.getOfferFileCandidates(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.loading = false;
        this.candidates = list;
        const first = list.find(c => c.attachmentId === this.preselect && !c.aisOfferId)
          ?? list.find(c => c.looksLikeOffer && !c.aisOfferId && !c.usedByOfferId) ?? null;
        if (first) this.pick(first);
        if (!list.length) this.mode = 'pc';
        this.cdr.markForCheck();
      },
      error: e => {
        this.loading = false;
        this.mode = 'pc';
        this.error = 'Файлы переписки не загрузились: ' + errorText(e);
        this.cdr.markForCheck();
      },
    });
  }

  /** Закрыть (фон, Esc, «Отмена»), но не во время занесения. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (!this.busy) this.close.emit();
  }

  setMode(m: 'chat' | 'pc') {
    this.mode = m;
    this.error = '';
    this.sentOn = m === 'chat' && this.picked ? this.picked.sentOn : this.today;
  }

  pick(c: OfferFileCandidate) {
    if (c.aisOfferId) return;
    this.picked = c;
    this.sentOn = c.sentOn;
    this.error = '';
  }

  onFile(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
    input.value = '';
    this.error = '';
  }

  ready(): boolean {
    return !!this.sentOn && (this.mode === 'pc' ? !!this.file : !!this.picked);
  }

  when(c: OfferFileCandidate): string {
    return new Date(c.sentAt).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  submit() {
    if (this.busy || !this.ready()) return;
    if (this.mode === 'pc' && this.file && this.file.size > UPLOAD_LIMIT) {
      this.error = 'Файл больше 15 МБ — уменьшите его и загрузите снова';
      return;
    }
    this.busy = true;
    this.error = '';
    const body = this.mode === 'pc'
      ? { file: this.file!, status: this.status, sentOn: this.sentOn }
      : { attachmentId: this.picked!.attachmentId, status: this.status, sentOn: this.sentOn };
    this.api.attachOfferFile(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: o => { this.router.navigate(['/client-offers', o.id]); },
      error: e => {
        this.busy = false;
        this.error = e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : errorText(e);
        this.cdr.markForCheck();
      },
    });
  }

  markSent(c: OfferFileCandidate) {
    if (this.busy) return;
    this.busy = true;
    this.api.markOfferHintSent(this.requestId, c.attachmentId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy = false; this.notify.success(`${c.aisOfferLabel} — отправлено`); this.changed.emit(); this.close.emit(); },
      error: e => { this.busy = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 4: Раздел «КП клиенту» (`pr-offers`)**

- импорты: `EventEmitter, Output` и `PrOfferFileDialogComponent` (в `imports` компонента);
- шапка раздела:

```html
      <div class="sec-head">
        <h3>КП клиенту</h3>
        <span class="acts" *ngIf="!readonly">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="openFile(null)">Занести готовое КП</button>
          <button type="button" class="btn btn-primary" *ngIf="view.lines.length" [disabled]="busy" (click)="create()">
            {{ busy ? 'Создаю…' : 'Создать КП клиенту' }}</button>
        </span>
      </div>
      <p class="hint" *ngIf="!view.offers.length">
        КП по заявке ещё нет{{ view.lines.length ? '' : ' — добавьте позиции или занесите готовое КП' }}.</p>
```

- строка КП: `<span class="num">{{ o.number != null ? '№ ' + o.number + ' от ' : 'от ' }}{{ dateText(o.offerDate) }}</span>` и после неё `<span class="from-file" *ngIf="o.origin === 'FILE'">из файла</span>`;
- в конце шаблона: `<app-pr-offer-file-dialog *ngIf="fileOpen" [requestId]="view.id" [preselect]="filePreselect" (close)="fileOpen = false" (changed)="changed.emit()"></app-pr-offer-file-dialog>`;
- стили: `.acts { display: flex; gap: 8px; flex-wrap: wrap; } .from-file { font-size: 12px; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text); }`;
- класс:

```ts
  @Output() changed = new EventEmitter<void>();
  fileOpen = false;
  filePreselect: number | null = null;

  /** «Занести готовое КП»; attachmentId — файл из подсказки «Дальше». */
  openFile(attachmentId: number | null) {
    if (this.readonly) return;
    this.filePreselect = attachmentId;
    this.fileOpen = true;
  }
```

- [ ] **Step 5: Подсказка в «Дальше» и обработчики страницы**

`deal-progress.component.ts` — блок кнопок:

```html
        <span class="dp-actions" *ngIf="canAct && n.action">
          <ng-container *ngIf="n.action === 'OFFER_DECISION'; else hintTpl">
            <button type="button" class="btn btn-primary btn-sm" [disabled]="busy" (click)="emit(n, 'ACCEPTED')">Согласился</button>
            <button type="button" class="btn btn-line btn-sm" [disabled]="busy" (click)="emit(n, 'REJECTED')">Отказался</button>
          </ng-container>
          <ng-template #hintTpl>
            <ng-container *ngIf="isHint(n); else one">
              <button type="button" class="btn btn-primary btn-sm" [disabled]="busy" (click)="emit(n)">{{ label(n.action) }}</button>
              <button type="button" class="btn btn-line btn-sm" [disabled]="busy" (click)="dismissHint(n)">{{ n.action === 'MARK_OFFER_SENT' ? 'Нет' : 'Не КП' }}</button>
            </ng-container>
          </ng-template>
          <ng-template #one>
            <button type="button" class="btn btn-primary btn-sm" [disabled]="busy" (click)="emit(n)">{{ label(n.action) }}</button>
          </ng-template>
        </span>
```

и методы:

```ts
  isHint(n: DealNext) { return n.action === 'ATTACH_OFFER_FILE' || n.action === 'MARK_OFFER_SENT'; }

  emit(n: DealNext, decision?: 'ACCEPTED' | 'REJECTED') {
    if (n.action) this.act.emit({ action: n.action, refId: n.refId, hintId: n.hintId ?? null, decision });
  }

  /** «Не КП» / «Нет» — подсказка про этот файл больше не показывается. */
  dismissHint(n: DealNext) {
    this.act.emit({ action: 'DISMISS_HINT', refId: null, hintId: n.hintId ?? null });
  }
```

`private-request-page.component.ts`:
- `<app-pr-offers #offersPart [view]="v" [readonly]="!auth.isAdmin()" (changed)="refresh()"></app-pr-offers>`;
- в `onAct`:

```ts
      case 'ATTACH_OFFER_FILE': this.offersPart?.openFile(a.hintId ?? null); break;
      case 'MARK_OFFER_SENT': this.markSent(a); break;
      case 'DISMISS_HINT': this.dismissHint(a); break;
```

- методы:

```ts
  /** «Отметить отправленным» (подсказка «Дальше»): черновик конструктора — «Отправлено» временем письма. */
  private markSent(a: DealAction) {
    const v = this.view;
    if (!v || a.hintId == null) return;
    this.busy = true;
    this.api.markOfferHintSent(v.id, a.hintId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy = false; this.notify.success('КП отмечено отправленным'); this.refresh(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  private dismissHint(a: DealAction) {
    const v = this.view;
    if (!v || a.hintId == null) return;
    this.busy = true;
    this.api.dismissOfferHint(v.id, a.hintId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy = false; this.refresh(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
```

- [ ] **Step 6: Сборка**

Run: `cd frontend && npm run build` (из корня проекта — `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`)
Expected: сборка без ошибок; начальный бандл ≤ 1,5 МБ (сверить с прошлым — 1,38 МБ).

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/private-requests/pr-offer-file-dialog.component.ts \
  frontend/src/app/pages/private-requests/pr-offers.component.ts frontend/src/app/pages/private-requests/private-request-page.component.ts \
  frontend/src/app/shared/client-offer.ts frontend/src/app/shared/deal.ts frontend/src/app/shared/private-request.ts \
  frontend/src/app/shared/deal-progress.component.ts frontend/src/app/services/api.service.ts
git commit -m "feat(ready-offer): окно «Готовое КП», раздел «КП клиенту», подсказка «КП ушло клиенту» в «Дальше»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Фронт — редактор КП в режиме «из файла», просмотр документа, журнал, поиск

**Files:**
- Create: `frontend/src/app/pages/client-offers/offer-file-view.component.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offer-editor.component.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offer-items.component.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offers.component.ts`
- Modify: `frontend/src/app/services/search.service.ts`

**Interfaces:**
- Consumes: `offerLabel`, `offerFileCheck`, `OfferFileMeta`, `OfferFileView`, `ApiService.downloadClientOfferFile/getClientOfferFileView/clientOfferFilePage` (задача 8).
- Produces: `<app-offer-file-view [offerId] [meta]>`; `ClientOfferItemsComponent.fileMode`.

- [ ] **Step 1: Просмотр документа**

`frontend/src/app/pages/client-offers/offer-file-view.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, Input, OnChanges, OnDestroy, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { saveBlob } from '../../shared/client-offer';
import type { OfferFileMeta, OfferFileTable } from '../../shared/client-offer';
import { responseFile } from '../../shared/share';
import { errorText } from '../../shared/error-text';

/** Показываются первые 30 страниц PDF — как у ответов поставщиков (ReplyPdfPages.MAX_PAGES). */
const MAX_PAGES = 30;

/**
 * Документ готового КП вместо предпросмотра конструктора (спека 2026-10-19 §3.3): таблицы файла как в файле, страницы
 * PDF по одной (blob через HttpClient — §14), картинка, «Скачать файл».
 */
@Component({
  selector: 'app-offer-file-view',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="ofv-head">
      <span class="ofv-name">{{ meta.name }}</span>
      <button type="button" class="btn btn-line btn-sm" (click)="download()">Скачать файл</button>
    </div>
    <p class="hint" *ngIf="loading">Загружаю документ…</p>
    <p class="hint warn" *ngIf="loadError">{{ loadError }}</p>
    <div class="ofv-tbl" *ngFor="let t of tables">
      <div class="t-cap">{{ t.source }}</div>
      <div class="t-scroll">
        <table>
          <thead><tr><th *ngFor="let c of t.columns">{{ c.header || '—' }}</th></tr></thead>
          <tbody><tr *ngFor="let r of t.rows"><td *ngFor="let cell of r.cells">{{ cell }}</td></tr></tbody>
        </table>
      </div>
    </div>
    <div class="ofv-pages" *ngIf="meta.kind === 'PDF'">
      <img *ngFor="let u of pageUrls; let i = index" [src]="u" [alt]="'Страница ' + (i + 1)" (click)="zoom = u" />
      <p class="hint" *ngIf="pagesLoading">Загружаю страницы…</p>
      <p class="hint" *ngIf="(meta.pages || 0) > 30">Показаны первые 30 страниц — остальное в файле.</p>
    </div>
    <img class="ofv-pic" *ngIf="meta.kind === 'IMAGE' && imageUrl" [src]="imageUrl" alt="КП картинкой" (click)="zoom = imageUrl" />
    <p class="hint" *ngIf="!loading && !loadError && (meta.kind === 'WORD' || meta.kind === 'EXCEL') && !tables.length">
      Таблицы с ценами в файле нет — откройте его кнопкой «Скачать файл».</p>
    <div class="ofv-zoom" *ngIf="zoom" (click)="zoom = null" role="dialog" aria-label="Крупно"><img [src]="zoom" alt="Крупно" /></div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .ofv-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
    .ofv-name { font-weight: 600; overflow-wrap: anywhere; flex: 1; min-width: 0; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .hint.warn { color: var(--warn-text); }
    .ofv-tbl { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
    .t-cap { font-size: 12px; color: var(--text-muted); padding: 6px 10px; background: var(--surface-2); }
    .t-scroll { overflow: auto; max-height: 70vh; }
    table { border-collapse: collapse; font-size: 12px; min-width: 100%; }
    th, td { border-bottom: 1px solid var(--border); padding: 5px 8px; text-align: left; vertical-align: top; }
    th { background: var(--surface-2); position: sticky; top: 0; font-weight: 600; }
    .ofv-pages { display: flex; flex-direction: column; gap: 8px; }
    .ofv-pages img, .ofv-pic { width: 100%; border: 1px solid var(--border); border-radius: 6px; cursor: zoom-in; background: #fff; }
    .ofv-zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, .8); z-index: 1200; display: flex; align-items: flex-start;
                justify-content: center; overflow: auto; padding: 16px; cursor: zoom-out; }
    .ofv-zoom img { max-width: min(1100px, 100%); background: #fff; }
    @media (max-width: 900px) { .ofv-head .btn { min-height: 40px; } }
  `],
})
export class OfferFileViewComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) offerId!: number;
  @Input({ required: true }) meta!: OfferFileMeta;

  tables: OfferFileTable[] = [];
  pageUrls: string[] = [];
  imageUrl: string | null = null;
  zoom: string | null = null;
  loading = false;
  pagesLoading = false;
  loadError = '';
  private urls: string[] = [];
  private sub?: Subscription;
  private shown: number | null = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (this.offerId === this.shown) return;   // ответ автосохранения меняет meta-объект, документ тот же
    this.shown = this.offerId;
    this.reset();
    if (this.meta.kind === 'PDF') this.loadPages(Math.min(this.meta.pages || 0, MAX_PAGES));
    else if (this.meta.kind === 'IMAGE') this.loadImage();
    else if (this.meta.kind === 'WORD' || this.meta.kind === 'EXCEL') this.loadTables();
  }

  ngOnDestroy() { this.reset(); }

  private loadTables() {
    const id = this.offerId;
    this.loading = true;
    this.sub = this.api.getClientOfferFileView(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { if (id !== this.offerId) return; this.loading = false; this.tables = v.tables; this.cdr.markForCheck(); },
      error: e => { this.loading = false; this.loadError = 'Документ не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  private loadPages(count: number) {
    if (!count) return;
    const id = this.offerId;
    this.pagesLoading = true;
    const next = (n: number) => {
      if (n > count || id !== this.offerId) { this.pagesLoading = false; this.cdr.markForCheck(); return; }
      this.sub = this.api.clientOfferFilePage(id, n).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: b => {
          if (id !== this.offerId) return;
          const u = URL.createObjectURL(b);
          this.urls.push(u);
          this.pageUrls = [...this.pageUrls, u];
          this.cdr.markForCheck();
          next(n + 1);
        },
        error: () => { this.pagesLoading = false; this.cdr.markForCheck(); },
      });
    };
    next(1);
  }

  private loadImage() {
    const id = this.offerId;
    this.sub = this.api.downloadClientOfferFile(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        if (!r.body || id !== this.offerId) return;
        const u = URL.createObjectURL(r.body);
        this.urls.push(u);
        this.imageUrl = u;
        this.cdr.markForCheck();
      },
      error: e => { this.loadError = 'Картинка не загрузилась: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  download() {
    this.api.downloadClientOfferFile(this.offerId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { const f = responseFile(r, this.meta.name); if (f) saveBlob(f, f.name); },
      error: e => this.notify.error('Файл не скачался: ' + errorText(e)),
    });
  }

  private reset() {
    this.sub?.unsubscribe();
    for (const u of this.urls) URL.revokeObjectURL(u);
    this.urls = [];
    this.pageUrls = [];
    this.imageUrl = null;
    this.zoom = null;
    this.tables = [];
    this.loading = false;
    this.pagesLoading = false;
    this.loadError = '';
  }
}
```

- [ ] **Step 2: Строки КП — режим «из файла» (`client-offer-items`)**

- `@Input() fileMode = false;` рядом с `readonly`;
- поле наценки: `[disabled]="readonly || fileMode || it.priceOverride != null"`, `[placeholder]="it.priceOverride != null || fileMode ? '—' : numText(offer.defaultMarkupPct)"`;
- кнопка «↺»: `*ngIf="it.priceOverride != null && !readonly && !fileMode"`;
- массовая наценка (поле `bulkMarkup` и его кнопка в блоке `.bulk`) — обернуть `<ng-container *ngIf="!fileMode">…</ng-container>`;
- в `setPrice` текст ошибки: `this.fileMode ? 'Цена клиенту — числом (пусто — без цены)' : 'Цена клиенту — числом (пусто — расчёт по наценке)'`.

- [ ] **Step 3: Редактор (`client-offer-editor`)**

- импорты: `OfferFileViewComponent` (в `imports` компонента), `offerLabel`, `offerFileCheck`; `responseFile` из `../../shared/share`;
- заголовок: `<h2>{{ title(o) }}</h2>` и после чипа статуса `<span class="from-file" *ngIf="isFile()">из файла</span>`;
- первой строкой `pane-edit` (перед плашкой новых цен): `<div class="file-check" *ngIf="offerFileCheck(o) as c" [attr.data-tone]="c.tone" role="status">{{ c.text }}</div>`;
- «Шапка»: поле «Исх. №» — `placeholder="нет"`; поля «Заголовок» и «Вводная фраза» — `*ngIf="!isFile()"`;
- `<app-client-offer-items … [fileMode]="isFile()" …>`;
- карточки «Цены», «Условия», «Оформление» — внутри `<ng-container *ngIf="!isFile()">…</ng-container>` (карточка «Маржа» остаётся);
- вкладки: вторая — `{{ isFile() ? 'Документ' : 'Просмотр' }}`;
- правая колонка:

```html
          <app-offer-file-view *ngIf="isFile() && o.file; else builderPreview" [offerId]="o.id" [meta]="o.file"></app-offer-file-view>
          <ng-template #builderPreview>
            <app-client-offer-preview [offerId]="o.id" [tick]="previewTick" [active]="previewActive()" (crowded)="onCrowded($event)"
                                      (zoomChange)="zoomed = $event"></app-client-offer-preview>
          </ng-template>
```

- нижняя панель: кнопки PDF / Word / «Поделиться» / «Отправить PDF» — в `<ng-container *ngIf="!isFile(); else fileBar">…</ng-container>`, и

```html
        <ng-template #fileBar>
          <button type="button" class="btn btn-primary" (click)="downloadFile()" [disabled]="busy || conflict"><svg lucideIcon="file-down" [size]="16"></svg> Скачать файл</button>
          <button type="button" class="btn btn-line" *ngIf="canShare && !shareFile" (click)="prepareShareFile()" [disabled]="busy || conflict">
            <svg lucideIcon="share-2" [size]="16"></svg> {{ sharePreparing ? 'Готовлю файл…' : 'Поделиться' }}</button>
          <button type="button" class="btn btn-primary" *ngIf="shareFile" (click)="sendShare()">Отправить файл</button>
        </ng-template>
```

- меню «⋯»: `<button type="button" class="danger" *ngIf="o.status === 'DRAFT' || isFile()" (click)="remove()">{{ isFile() ? 'Удалить КП' : 'Удалить черновик' }}</button>`;
- стили: `.from-file { font-size: 12px; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text); }`, `.file-check { padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--surface); font-size: 13px; overflow-wrap: anywhere; } .file-check[data-tone="ok"] { border-color: var(--success); background: color-mix(in srgb, var(--success) 8%, var(--surface)); } .file-check[data-tone="warn"] { border-color: var(--warn); background: color-mix(in srgb, var(--warn) 8%, var(--surface)); }`;
- класс:

```ts
  offerFileCheck = offerFileCheck;

  isFile(): boolean {
    return this.offer?.origin === 'FILE';
  }

  title(o: ClientOffer): string {
    return o.number != null ? `КП № ${o.number} от ${dateText(o.offerDate)}` : offerLabel(o);
  }

  /** «Скачать файл» — исходный документ готового КП. */
  downloadFile() {
    const o = this.offer!;
    this.api.downloadClientOfferFile(o.id).subscribe({
      next: r => { const f = responseFile(r, o.file?.name || 'КП'); if (f) saveBlob(f, f.name); },
      error: e => this.fileError(e, 'Файл не скачался'),
    });
  }

  /** «Поделиться» в два нажатия (как у PDF конструктора): сперва файл, затем окно отправки прямо из нажатия. */
  prepareShareFile() {
    const o = this.offer!;
    const gen = this.gen;
    this.sharePreparing = true;
    this.render();
    this.api.downloadClientOfferFile(o.id).subscribe({
      next: r => {
        if (gen !== this.gen) return;
        this.sharePreparing = false;
        this.shareFile = responseFile(r, o.file?.name || 'КП');
        this.render();
      },
      error: e => {
        if (gen !== this.gen) return;
        this.sharePreparing = false;
        this.fileError(e, 'Файл не скачался');
        this.render();
      },
    });
  }
```

- `remove()`: текст подтверждения — `` `Удалить ${this.isFile() ? '' : 'черновик '}${offerLabel(o)}?` `` (так «Удалить КП от 24.09.2026?» / «Удалить черновик КП № 481?»), тост — `this.isFile() ? 'КП удалено' : 'Черновик удалён'`, переход — `this.router.navigate(o.tenderId ? ['/private-requests', o.tenderId] : ['/client-offers'])`;
- `duplicate()`: тост — `this.isFile() ? `Создана копия в конструкторе — КП № ${r.number}` : `Создана копия — КП № ${r.number}``.

- [ ] **Step 4: Журнал и поиск**

`client-offers.component.ts`:
- номер: `<a class="num" [routerLink]="['/client-offers', o.id]" [attr.aria-label]="'Открыть ' + label(o)">{{ o.number != null ? '№ ' + o.number : 'без №' }}</a>` и после статуса `<span class="from-file" *ngIf="o.origin === 'FILE'">из файла</span>` (стиль как в редакторе);
- `aria-label` «⋯» — `'Действия с ' + label(o)`;
- «Удалить» — `*ngIf="o.status === 'DRAFT' || o.origin === 'FILE'"`; в `remove(o)` подтверждение `` `Удалить ${o.origin === 'FILE' ? '' : 'черновик '}${label(o)}?` ``, тост `o.origin === 'FILE' ? 'КП удалено' : 'Черновик удалён'`;
- `label = offerLabel;` (импорт из `../../shared/client-offer`).

`search.service.ts` (без импорта `shared/client-offer` — он держится вне начального бандла):

```ts
        title: o.number != null ? `КП № ${o.number}` : `КП от ${String(o.offerDate || '').split('-').reverse().join('.')}`,
```

- [ ] **Step 5: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: без ошибок; начальный бандл ≤ 1,5 МБ; маркер `app-offer-file-view` — только в ленивом чанке редактора (`grep -l "Скачать файл" dist/*/browser/*.js` — не `main-*.js`).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/client-offers frontend/src/app/services/search.service.ts
git commit -m "feat(ready-offer): редактор КП из файла — документ справа, сверка итога, «Скачать файл»; журнал и поиск

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Стенд писем, настоящие КП, живая проверка, документация, ревью, мерж

**Files:**
- Modify: `scripts/dev-mail.py` (`kp <адрес> <файл>`)
- Modify: `CLAUDE.md` (§7, §8 новый блок, §12, §13, §15, §16), `docs/PROGRESS.md` («▶ Последняя задача»), `DEPLOY.md` (откат V33)
- Ledger: `.superpowers/sdd/2026-10-19-ready-offer-file/progress.md`

- [ ] **Step 1: Наше письмо с КП в «Отправленные» info@**

В `scripts/dev-mail.py` — строка в docstring `python3 scripts/dev-mail.py kp <адрес> <файл>  — наш ответ клиенту с КП во вложении: в «Отправленные» info@ (IMAP APPEND)` и функция:

```python
def kp(address, path):
    """Наше письмо клиенту с готовым КП из веб-почты: копия в «Отправленные» info@ (как answer, но с файлом)."""
    m = EmailMessage()
    m["From"] = f"West-Med <{INFO}>"
    m["To"] = address
    m["Subject"] = "Коммерческое предложение West-Med"
    m["Message-ID"] = make_msgid(domain="westmed.kz")
    m["Date"] = formatdate(localtime=True)
    m.set_content("Добрый день!\nВысылаем КП во вложении.\n\nС уважением, West-Med")
    name = os.path.basename(path)
    with open(path, "rb") as f:
        m.add_attachment(f.read(), maintype="application", subtype="octet-stream", filename=name)
    imap = imaplib.IMAP4("127.0.0.1", 3143)
    imap.login(INFO, "secret")
    imap.append("Sent", None, None, m.as_bytes())
    imap.logout()
    print("в «Отправленные»:", m["Subject"], "—", name)
```

и в `__main__`: `elif cmd == "kp" and len(sys.argv) > 3: kp(sys.argv[2], sys.argv[3])`.

- [ ] **Step 2: Настоящие КП из «Загрузок» — тем же кодом**

Временная программа в папке сессии (как при брейнсторме: `KpProbe` с `ReadyOfferReader.read(..., learned)` из `header_synonym` nirdb; classpath — init-скрипт `printCp`), по файлам: `КП West-Med 24.09.2026.docx`, `КП West-Med 16.10.2026 – консоли ОЗОН, Казталовская РБ.docx`, `КП West-Med 15.10.2026 – Аксион, Казыгуртская ЦРБ.docx`, `2026 КП WEST-MED центрифуга.docx`, `КП 14.09.2026 ВитаЛайн 200.doc`, `КП   Аппарат искусственной вентиляции легких для экстренной помощи А-ИВЛ-Э-03.doc`, `КП № 481 от 07.10.2026 2.pdf`, `КП № 1 от 05.10.2026.pdf`. Ожидание: 24.09 — 44 позиции, итог 20 417 347,00 = «ИТОГО»; «ОЗОН» — разделы «1 этаж»…«4 этаж», итог 145 105 200,00 = «ИТОГО»; Аксион — 13 вариантов из 7 таблиц, итога нет; центрифуга — 2 позиции, «Исх № 24 от 17.04.2026»; ВитаЛайн 200 — 17 позиций, 9 902 248,23; А-ИВЛ — 1 позиция; PDF — номер и дата («КП № 481 от 07.10.2026»). Расхождения — в леджер и исправлять до живой проверки (тест на правило — красный до правки). Программа в репозиторий не входит.

- [ ] **Step 3: Живая проверка** (§5: `./gradlew devMail`; бэкенд с приёмом info@ — команда §5 «Почта info@ → «Обращения» — локально»; `npm start`; браузер — локальный Playwright или MCP; записать `max(id)` lead / chat / inbound_email / tender / client_offer до проверки)

1. Письмо клиента (`dev-mail.py client zz-kp@clinic.kz`) → обращение → «Создать заявку».
2. Наш ответ с КП (`dev-mail.py kp zz-kp@clinic.kz "~/Downloads/КП West-Med 24.09.2026.docx"`) → через 15 с в «Дальше» заявки: «Похоже, КП ушло клиенту ДД.ММ письмом — «КП West-Med 24.09.2026.docx»» [Занести КП] · Не КП.
3. «Занести КП» → окно с выбранным файлом и датой письма → «Занести» → редактор: «из файла», строка сверки «… · 44 позиции · итог 20 417 347,00 тг = «ИТОГО» в файле ✓», таблица файла справа, «Скачать файл»; нет блоков «Цены» / «Условия» / «Оформление»; правка цены строки — автосохранение, строка сверки — «≠»; вернуть цену.
4. Заявка: этап «КП отправлено» → «Согласился» → «КП принято»; лента — «КП от 24.09.2026 занесено из файла «…»» и «… отправлено»; журнал КП — «без №» и «из файла».
5. «С компьютера»: буклет Аксиона (13 строк, «итога в файле нет…»), «ОЗОН» (разделы, ✓), PDF (страницы справа, цены вписать — итог считается).
6. КП конструктора (черновик) → его PDF скачать → `dev-mail.py kp … "<файл КП № N>.pdf"` → «КП № N ушло клиенту ДД.ММ письмом — отметить отправленным?» → «Отметить отправленным» → «Отправлено» временем письма; второй файл → «Не КП» → подсказка уходит.
7. Удалить КП из файла («⋯ → Удалить КП») → переход на заявку, события КП из ленты ушли.
8. Оператор: окно «Занести готовое КП» не видно, «Дальше» без кнопок, документ КП открывается и скачивается; запросов записи — 0.
9. 1280 и 390 px, светлая и тёмная темы: окно, раздел «КП клиенту», «Дальше» с двумя кнопками, редактор КП из файла, просмотр документа.

Уборка (только dev): КП проверки — «Удалить КП»; заявки — «⋯ → Удалить заявку»; остановить бэкенд; SQL — по §5 «Почта info@ → «Обращения» — локально» (`lead`, `chat`, `inbound_email`, `mail_cursor` ящика info@); остановить devMail и фронт.

- [ ] **Step 4: Документация**
  - CLAUDE.md §7 — `client_offer.origin`, необязательный `number`, `client_offer_file`, `chat_attachment.offer_hint_dismissed` (V33); §8 — блок «Готовое КП» (механика, решения при планировании, проверено); §12 — фронт (окно, просмотр, режим «из файла», подсказка); §13 — полный прогон; §15 — пути; §16 — «Жизненный цикл сделки»: 3а сделан, что вне рамок (спека §10) и мелочи ревью; следующая свободная миграция — V34.
  - `docs/PROGRESS.md` — «▶ Последняя задача»: что сделано, где документы, что ждёт push оператора.
  - `DEPLOY.md` — откат кода ниже V33: сперва `delete from client_offer where origin = 'FILE';` (прежний бэкенд показал бы КП из файла как КП конструктора без номера).

- [ ] **Step 5: Полный прогон и ревью ветки**
  - остановить `bootRun` и `devMail` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`, `lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`);
  - `./gradlew cleanTest test` (sandbox off, ~18 мин) — 0 падений;
  - `cd frontend && npm run build`;
  - ревью ветки самому: `git diff main...feature/ready-offer-file` целиком свежим взглядом, по спеке раздел за разделом, Review Focus — каждая строка закрыта тестом; найденное — исправить тестом, красным до правки, и записать в леджер.

- [ ] **Step 6: Мерж**

```bash
git checkout main && git merge --ff-only feature/ready-offer-file && git branch -d feature/ready-offer-file
```

Push — оператор сам (`! git push origin main`, CLAUDE.md §5); после деплоя — проверить на проде V33 (по SSH — только с согласия оператора).
