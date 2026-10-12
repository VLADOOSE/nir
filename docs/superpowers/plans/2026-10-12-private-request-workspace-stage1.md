# Частная заявка — рабочее место сделки, этап 1 — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Частная заявка становится отдельной страницей-рабочим местом. Клиент на ней необязателен. Позиции добавляются с сайта westmed.kz, списком или из Excel. У каждой позиции хранится РУ с подтверждением человеком. Запросы цен поставщикам уходят из одного окна, ответы видны в таблице цен. Обращение превращается в заявку одной кнопкой.

**Architecture:** Бэкенд — расширение существующего `tender` (`source = PRIVATE_REQUEST`, миграция V26) и сервиса `PrivateRequestService`; новые классы — в пакете `service.privaterequest`: ответ страницы, этап, подсказка РУ, подбор поставщиков, окно запроса цен, таблица цен. Отправка писем идёт через существующий единый канал `PriceRequestSendService`. Фронт — две ленивые страницы (`/private-requests`, `/private-requests/:id`) и компоненты разделов. Боковая панель `private-request-card` и диалог превращения обращения удаляются.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/Hibernate 6, Flyway, PostgreSQL 17 (pg_trgm), JUnit 5 + AssertJ + MockMvc + GreenMail; Angular 21 (standalone, инлайн-шаблоны, SCSS в `styles: []`).

**Spec:** `docs/superpowers/specs/2026-10-12-private-request-workspace-design.md` — этап 1 из §13. Этапы 2 (запрос на бланке, клиент в запросе, производители, вписанная почта) и 3 (КП клиенту из заявки) пойдут отдельными планами по коду этого этапа.

## Global Constraints

- **Ветка** `feature/private-request-workspace`: спека уже в ней. Леджер SDD — `.superpowers/sdd/2026-10-12-private-request-workspace/progress.md`.
- **Каждый коммит** заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- **Миграция этапа — только `V26__private_request_workspace.sql`.** Схему меняем только новыми миграциями, V1–V25 не трогать (CLAUDE.md §10).
- **Гард рынка.** Любая сущность, взятая по id через `findById` (`em.find`), проверяется явно: рынок текущий, иначе 404. Фильтр рынка на загрузку по ключу не действует (CLAUDE.md §14).
- **Лоты** заявки меняются только через коллекцию `tender.getLots()`, а не `tenderLotRepository.delete` (CLAUDE.md §7).
- **Записи API** — `@PreAuthorize("hasRole('ADMIN')")`. Чтение — любой вошедший.
- **Тесты** — `@SpringBootTest @Transactional` на nirdb. Новые особые тестовые контексты не заводить (`@TestPropertySource`, `@MockitoBean`, `@AutoConfigureMockMvc` — каждый ещё 10 соединений к nirdb). MockMvc собирать из общего контекста: `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build()`. SMTP-тесты — под общей аннотацией `@SmtpIntegrationTest` (задача 8).
- **Gradle и psql** запускать с `dangerouslyDisableSandbox: true` (песочница режет localhost:5432). Перед прогоном остановить `bootRun` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`). Полный гейт — `./gradlew cleanTest test`.
- **Фронт:**
  - новые страницы — только ленивые (`loadComponent`), типы в `ApiService` — через `import type` (начальный бандл у предела 1,5 МБ);
  - кнопки — kit (`.btn` + роль), цвета — токены темы (`var(--…)`), обе темы;
  - адаптив — брейкпоинт 900 px; на телефоне цели 40 px, поля 16 px;
  - `@media` — в конце блока `styles` (CLAUDE.md §14);
  - без нативного `await` — результаты промисов через `.then`;
  - гейт — `cd frontend && npm run build`.
- **Тексты экрана** — по-русски, дословно как в спеке.
- Сообщения об ошибках сервера — свои тексты (400 / 404 с русским `message`); чужие исключения наружу не отдаются.

## Review Focus

Пять ситуаций, которые спека подразумевает, но легко упустить. У каждой — тест в задаче, где живёт код:

1. **Длинный вставленный список** (100–200 строк из сметы в WhatsApp). Разбор не падает и режет до 200 строк, добавление пачкой не превышает 500 позиций в заявке. Тесты: задача 5 `PastedLinesParserTest.capsAt200Lines`, задача 4 `PrivateRequestLinesTest.cannotExceedMaxLines`.
2. **Сайт westmed.kz не отвечает или отвечает медленно.** Поиск отдаёт 502 со своим текстом за ≤ 5 с, позицию можно добавить текстом. Тест: задача 5 `SiteProductServiceTest.siteFailureIsUpstreamWithOwnText`.
3. **Название клиента занято на другом рынке** (`facility.name` глобально уникален). «Сохранить в Учреждения» отвечает 400 с подсказкой, а не 500 из базы. Тест: задача 2 `PrivateRequestCoreTest.saveClient_sameMarketNameLinksExisting_otherMarketNameIs400`.
4. **«Заменить позиции» из Excel при уже разосланных запросах цен.** Запрошенные позиции остаются, нумерация непрерывная, внешний ключ не падает. Тест: задача 4 `PrivateRequestLinesTest.replaceKeepsRequestedLines`.
5. **Обращение с позицией длиннее 255 символов** (`lead_item.name` — 500). «Создать заявку» обрезает наименование до 255, а не падает на колонке `equip_name`. Тест: задача 6 `LeadConvertTest.oneClick_truncatesLongItemName`.

## Карта файлов

**Бэкенд — создать:**
- `src/main/resources/db/migration/V26__private_request_workspace.sql`
- `entity/LineRegStatus.java`, `entity/LetterStatus.java`, `entity/PrivateRequestStatus.java`
- `dto/request/PrivateRequestField.java`, `PrivateRequestFieldUpdate.java`, `PrivateRequestCloseRequest.java`, `PrivateRequestLineInput.java`, `PrivateRequestLinesAdd.java`, `LinesMode.java`, `PrivateRequestLineField.java`, `PrivateRequestLineFieldUpdate.java`, `LineRegistryAction.java`, `LineRegistryActionRequest.java`, `PastedLinesRequest.java`, `ImportLinesRequest.java`, `RfqPreviewRequest.java`, `RfqSendRequest.java`
- `dto/response/PrivateRequestViewResponse.java`, `PrivateRequestLineView.java`, `PrivateRequestListItemResponse.java`, `ParsedLineResponse.java`, `SiteProductResponse.java`, `PrivateRequestSourcingResponse.java`, `PrivateRequestPricesResponse.java`
- `service/privaterequest/PrivateRequestViewBuilder.java`, `PrivateRequestStageCalculator.java`, `PrivateRequestRegistry.java`, `PrivateRequestRfqService.java`, `PrivateRequestPricesService.java`
- `service/SiteProductService.java`, `controller/SiteProductController.java`, `util/PastedLinesParser.java`
- тесты: `privaterequest/PrivateRequestSchemaTest`, `PrivateRequestCoreTest`, `PrivateRequestApiTest`, `PrivateRequestListTest`, `PrivateRequestLinesTest`, `PrivateRequestRegistryCalibrationTest`, `PrivateRequestRfqTest`, `PrivateRequestPricesTest`, `PriceRequestResponsesGuardTest`; `util/PastedLinesParserTest`; `service/SiteProductServiceTest`; `support/SmtpIntegrationTest`

**Бэкенд — изменить:**
- сущности: `entity/Tender.java`, `TenderLot.java`, `PriceRequest.java`;
- репозитории: `TenderRepository`, `TenderLotRepository`, `PriceRequestRepository`, `PriceRequestItemRepository`, `MedRegistryRepository`, `ClientOfferRepository`, `LeadRepository`, `InboundEmailRepository`;
- сервисы: `service/PrivateRequestService.java`, `PrivateRequestImportService.java`, `PrivateRequestSourcingService.java`, `LeadService.java`, `PriceRequestSendService.java`, `PriceRequestWriter.java`, `KpEmailComposer.java`, `service/mail/MailNotificationComposer.java`;
- контроллеры: `PrivateRequestController.java`, `LeadController.java`, `PriceRequestController.java`;
- DTO: `dto/request/PrivateRequestCreate.java`, `ImportCommitRequest.java`, `LeadConvertRequest.java`;
- интеграция с сайтом: `integration/westmed/WestmedClient.java`, `WestmedHttpClient.java`, `dto/WestmedProduct.java`;
- тесты: `LeadConvertTest`, `PrivateRequestImportServiceTest`, `PrivateRequestServiceTest`, `PrivateRequestSourcingServiceTest`, `PriceRequestSendServiceTest`, `MailNotificationComposerTest`, `MailIngestWriterTest`.

**Бэкенд — удалить:** `dto/request/PrivateRequestUpdate.java`, `dto/response/PrivateRequestResponse.java`, `PrivateRequestLineResponse.java`, `SourcingGroupResponse.java`, `SourcingPreviewResponse.java`, тест `imports/PrivateRequestUpdateTest.java`.

**Фронт — создать:**
- `shared/private-request.ts`, `shared/error-text.ts`, `shared/registry-chip.component.ts`, `shared/site-product-search.component.ts`
- в `pages/private-requests/`: `private-request-page.component.ts`, `pr-client-section.component.ts`, `pr-lines.component.ts`, `pr-supply.component.ts`, `pr-rfq-dialog.component.ts`, `pr-prices.component.ts`

**Фронт — изменить:** `app.routes.ts`, `services/api.service.ts`, `pages/private-requests/private-requests.component.ts` (переписать), `pages/leads/lead-card.component.ts`, `pages/leads/leads.component.ts`, `pages/inbound/inbound.component.ts`.

**Фронт — удалить:** `pages/private-requests/private-request-card.component.ts`, `pages/leads/lead-convert-dialog.component.ts`.

**Документация:** `CLAUDE.md` (§8, §12, §15, §16), `docs/PROGRESS.md`.

---

### Task 1: Схема V26 и поля сущностей

**Files:**
- Create: `src/main/resources/db/migration/V26__private_request_workspace.sql`
- Create: `src/main/java/com/vladoose/nir/entity/LineRegStatus.java`
- Create: `src/main/java/com/vladoose/nir/entity/LetterStatus.java`
- Create: `src/main/java/com/vladoose/nir/entity/PrivateRequestStatus.java`
- Modify: `src/main/java/com/vladoose/nir/entity/Tender.java`
- Modify: `src/main/java/com/vladoose/nir/entity/TenderLot.java`
- Modify: `src/main/java/com/vladoose/nir/entity/PriceRequest.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestSchemaTest.java`

**Interfaces:**
- Produces:
  - `Tender.contactName` (`String`), `Tender.createdAt` (`OffsetDateTime`, ставится `@PrePersist`);
  - `TenderLot.unit`, `productUrl` (`String`), `regStatus` (`LineRegStatus`, может быть `null`), `regNumber` (`String`), `regScore` (`BigDecimal`);
  - `PriceRequest.replyBy` (`LocalDate`), `sentTo` (`String`), `letterStatus` (`LetterStatus`);
  - enum `LineRegStatus { SUGGESTED, CONFIRMED, NOT_FOUND, NOT_REQUIRED }`, `LetterStatus { SENT, NO_EMAIL, FAILED }`, `PrivateRequestStatus { NEW, WON, LOST, CANCELLED }` с `closed()` и `of(String)`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestSchemaTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** V26: новые колонки заявки, позиции и запроса цен читаются обратно; у даты создания есть умолчание базы. */
@SpringBootTest
@Transactional
class PrivateRequestSchemaTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void newColumnsRoundTrip() {
        Tender t = Tender.builder().tenderNumber("ZZ-SCHEMA-1").status(PrivateRequestStatus.NEW.name())
                .source(Source.PRIVATE_REQUEST).contactName("Айгуль Сапарова").build();
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName("Тонометр OMRON M2").manufact("OMRON")
                .quantity(5).unit("упак").productUrl("https://westmed.kz/product/omron-m2")
                .regStatus(LineRegStatus.SUGGESTED).regNumber("РК-МИ-ZZ-1").regScore(new BigDecimal("0.812")).build());
        tenderRepository.save(t);
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Схема поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT")
                .replyBy(LocalDate.of(2026, 10, 15)).sentTo("a@b.kz").letterStatus(LetterStatus.SENT).build());
        em.flush();
        em.clear();

        Tender back = tenderRepository.findById(t.getId()).orElseThrow();
        assertThat(back.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(back.getCreatedAt()).isNotNull();
        TenderLot lot = back.getLots().get(0);
        assertThat(lot.getUnit()).isEqualTo("упак");
        assertThat(lot.getProductUrl()).isEqualTo("https://westmed.kz/product/omron-m2");
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lot.getRegNumber()).isEqualTo("РК-МИ-ZZ-1");
        assertThat(lot.getRegScore()).isEqualByComparingTo("0.812");
        PriceRequest prBack = priceRequestRepository.findById(pr.getId()).orElseThrow();
        assertThat(prBack.getReplyBy()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(prBack.getSentTo()).isEqualTo("a@b.kz");
        assertThat(prBack.getLetterStatus()).isEqualTo(LetterStatus.SENT);
    }

    @Test
    void createdAtHasDatabaseDefaultForRawInserts() {
        String def = jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'tender' AND column_name = 'created_at'", String.class);
        assertThat(def).contains("now()");
    }

    @Test
    void privateRequestStatusReadsUnknownAsNew() {
        assertThat(PrivateRequestStatus.of(null)).isEqualTo(PrivateRequestStatus.NEW);
        assertThat(PrivateRequestStatus.of("ACTIVE")).isEqualTo(PrivateRequestStatus.NEW);
        assertThat(PrivateRequestStatus.of("WON").closed()).isTrue();
        assertThat(PrivateRequestStatus.NEW.closed()).isFalse();
    }
}
```

- [ ] **Step 2: Запустить — тест не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestSchemaTest'`
Expected: FAIL — `cannot find symbol: method contactName(String)` / `class LineRegStatus`.

- [ ] **Step 3: Миграция**

`src/main/resources/db/migration/V26__private_request_workspace.sql`:

```sql
-- Частная заявка — рабочее место (спека docs/superpowers/specs/2026-10-12-private-request-workspace-design.md, этап 1).

-- заявка: контактное лицо одной строкой и дата создания (у tender её не было — список не сортировался «новые сверху»)
ALTER TABLE tender ADD COLUMN contact_name VARCHAR(255);
ALTER TABLE tender ADD COLUMN created_at TIMESTAMPTZ;
-- умолчание ставим ПОСЛЕ добавления: иначе старые строки получили бы дату миграции вместо «неизвестно»
ALTER TABLE tender ALTER COLUMN created_at SET DEFAULT now();

-- дата создания уже заведённых частных заявок: из обращения, иначе из первого запроса цен; нет ни того ни другого — пусто
UPDATE tender t SET created_at = l.received_at
FROM lead l
WHERE l.private_request_id = t.id AND t.source = 'PRIVATE_REQUEST' AND t.created_at IS NULL;

UPDATE tender t SET created_at = pr.first_at
FROM (SELECT tender_id, min(created_at) AS first_at FROM price_request GROUP BY tender_id) pr
WHERE pr.tender_id = t.id AND t.source = 'PRIVATE_REQUEST' AND t.created_at IS NULL;

-- позиция частной заявки: ед. изм., ссылка на товар сайта, РУ (статус / номер / похожесть подсказки)
ALTER TABLE tender_lot ADD COLUMN unit VARCHAR(30);
ALTER TABLE tender_lot ADD COLUMN product_url VARCHAR(500);
ALTER TABLE tender_lot ADD COLUMN reg_status VARCHAR(20);
ALTER TABLE tender_lot ADD COLUMN reg_number VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN reg_score NUMERIC(5, 3);

-- запрос цен: «ответить до», адрес, на который письмо приняла почта, исход отправки
ALTER TABLE price_request ADD COLUMN reply_by DATE;
ALTER TABLE price_request ADD COLUMN sent_to VARCHAR(255);
ALTER TABLE price_request ADD COLUMN letter_status VARCHAR(20);

-- список частных заявок рынка
CREATE INDEX idx_tender_source_market ON tender (source, market);
```

- [ ] **Step 4: Перечисления**

`src/main/java/com/vladoose/nir/entity/LineRegStatus.java`:

```java
package com.vladoose.nir.entity;

/** РУ позиции частной заявки (спека 2026-10-12 §5.3). Пусто (null) — ещё не проверено; у тендеров площадок всегда пусто. */
public enum LineRegStatus {
    /** подсказка реестра — ждёт решения человека */
    SUGGESTED,
    /** подтверждено человеком */
    CONFIRMED,
    /** реестр не подсказал РУ выше порога */
    NOT_FOUND,
    /** «не подлежит регистрации» — решение человека */
    NOT_REQUIRED
}
```

`src/main/java/com/vladoose/nir/entity/LetterStatus.java`:

```java
package com.vladoose.nir.entity;

/** Исход последней отправки письма запроса цен (спека 2026-10-12 §3.1). Пусто — запрос заведён до V26. */
public enum LetterStatus { SENT, NO_EMAIL, FAILED }
```

`src/main/java/com/vladoose/nir/entity/PrivateRequestStatus.java`:

```java
package com.vladoose.nir.entity;

/**
 * Статус частной заявки (tender.status у source = PRIVATE_REQUEST): NEW — в работе, остальное — закрыта с исходом.
 * Этап («Запрошены цены…») не хранится — считается из данных (PrivateRequestStageCalculator).
 */
public enum PrivateRequestStatus {
    NEW, WON, LOST, CANCELLED;

    public boolean closed() { return this != NEW; }

    /** Неизвестное значение — «в работе»: так читаются и заявки до V26 (там всегда было NEW). */
    public static PrivateRequestStatus of(String raw) {
        if (raw == null) return NEW;
        try { return valueOf(raw); } catch (IllegalArgumentException e) { return NEW; }
    }
}
```

- [ ] **Step 5: Поля сущностей**

В `Tender.java` добавить импорт `java.time.OffsetDateTime` и после поля `contactEmail`:

```java
    /** Контактное лицо частной заявки одной строкой, как написал клиент (у тендеров площадок — разнесённые поля выше). */
    @Column(name = "contact_name")
    private String contactName;

    /** Дата создания (V26): новые строки любого источника; у старых частных заявок заполнена миграцией. */
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
```

и перед методом `setDeadline`:

```java
    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
```

В `TenderLot.java` (импорт `java.math.BigDecimal` уже есть) после поля `proposedEquipment`:

```java
    /** Ед. изм. позиции частной заявки; пусто = «шт». */
    @Column(length = 30)
    private String unit;

    /** Ссылка на товар westmed.kz, если позиция выбрана из каталога сайта. */
    @Column(name = "product_url", length = 500)
    private String productUrl;

    /** РУ позиции частной заявки; пусто — не проверено. Правка наименования или бренда пересчитывает подсказку. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reg_status", length = 20)
    private LineRegStatus regStatus;

    @Column(name = "reg_number", length = 100)
    private String regNumber;

    /** Похожесть подсказки реестра — для калибровки порога; на экране не печатается. */
    @Column(name = "reg_score", precision = 5, scale = 3)
    private BigDecimal regScore;
```

В `PriceRequest.java` после поля `createdAt`:

```java
    /** «Ответить до» из окна запроса цен; в письме — вместо срока тендера. */
    @Column(name = "reply_by")
    private LocalDate replyBy;

    /** Адрес, на который письмо приняла почта; пусто — письма не было или оно не ушло. */
    @Column(name = "sent_to")
    private String sentTo;

    /** Исход последней отправки письма; пусто у запросов до V26. */
    @Enumerated(EnumType.STRING)
    @Column(name = "letter_status", length = 20)
    private LetterStatus letterStatus;
```

- [ ] **Step 6: Запустить тест — зелёный**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestSchemaTest'`
Expected: PASS (3 теста). Flyway при подъёме контекста накатит V26 на nirdb (в логе — `Migrating schema "public" to version "26 - private request workspace"`).

- [ ] **Step 7: Проверить, что миграция ничего не сломала**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS.

- [ ] **Step 8: Коммит**

```bash
git add src/main/resources/db/migration/V26__private_request_workspace.sql src/main/java/com/vladoose/nir/entity/LineRegStatus.java src/main/java/com/vladoose/nir/entity/LetterStatus.java src/main/java/com/vladoose/nir/entity/PrivateRequestStatus.java src/main/java/com/vladoose/nir/entity/Tender.java src/main/java/com/vladoose/nir/entity/TenderLot.java src/main/java/com/vladoose/nir/entity/PriceRequest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestSchemaTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): V26 — контакт, дата создания, РУ позиции, исход письма запроса цен

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Ядро заявки — черновик, гард рынка, шапка по полю, клиент, закрытие и удаление

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestService.java` (переписать целиком — ниже)
- Modify: `src/main/java/com/vladoose/nir/service/LeadService.java` (метод `transition` → `public static`)
- Modify: `src/main/java/com/vladoose/nir/dto/request/PrivateRequestCreate.java` (снять `@NotNull` с клиента)
- Modify: `src/main/java/com/vladoose/nir/repository/TenderRepository.java`, `MedRegistryRepository.java`, `ClientOfferRepository.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (переписать целиком — ниже)
- Create: `src/main/java/com/vladoose/nir/dto/request/PrivateRequestField.java`, `PrivateRequestFieldUpdate.java`, `PrivateRequestCloseRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestViewResponse.java`, `PrivateRequestLineView.java`
- Create: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestCoreTest.java`, `PrivateRequestApiTest.java`
- Modify test: `src/test/java/com/vladoose/nir/lead/LeadConvertTest.java` (метод `privateRequestCardLinksBackToLead`)

**Interfaces:**
- Consumes: поля и перечисления задачи 1.
- Produces:
  - **`PrivateRequestService`:**
    - `Tender createEmpty()`, `Tender findById(Long id)` (частная + текущий рынок, иначе `NotFoundException`);
    - `Tender updateField(Long id, PrivateRequestField field, String value)`, `Tender saveClient(Long id)`;
    - `Tender close(Long id, PrivateRequestStatus outcome)`, `Tender reopen(Long id)`, `void delete(Long id, String author)`;
    - `Tender createFromLines(PrivateRequestCreate dto)` — клиент необязателен;
    - статические `blankToNull(String)`, `trunc(String, int)`.
  - **`PrivateRequestViewBuilder`:** `PrivateRequestViewResponse build(Tender t)`, `PrivateRequestLineView line(TenderLot l)`, статические `RegistryCandidateResponse candidate(MedRegistry m)`, `String clientLabel(Tender t)`.
  - **`PrivateRequestField { FACILITY, CUSTOMER_NAME, CONTACT_NAME, CONTACT_PHONE, CONTACT_EMAIL, DELIVERY_ADDRESS, NOTE }`.**
  - **`LeadService.transition(Lead, LeadStatus, String author, String note)`** — `public static`.
  - **REST:** `POST /api/private-requests` → вид; `GET /{id}` → вид; `PATCH /{id}` `{field, value}` → вид; `POST /{id}/save-client`, `/{id}/close` `{outcome}`, `/{id}/reopen` → вид; `DELETE /{id}` → 204.
- Временно остаются, их заменят: старый `GET /api/private-requests` (задача 3), `PUT /{id}` и `PrivateRequestService.update` (задача 4), импорт (задача 6), `GET /{id}/sourcing` и `linesWithRegistration` (задача 7).

- [ ] **Step 1: Написать падающие тесты ядра**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestCoreTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.response.PrivateRequestLineView;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PrivateRequestCoreTest {

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestViewBuilder viewBuilder;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired LeadIntakeService intake;
    @Autowired ClientOfferService clientOfferService;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Facility facility(String name, Market market) {
        return facilityRepository.save(Facility.builder().name(name).market(market).build());
    }

    private TenderLot lot(Tender t, int no, String name) {
        TenderLot l = TenderLot.builder().tender(t).lotNumber(no).equipName(name).quantity(1).build();
        t.getLots().add(l);
        tenderRepository.saveAndFlush(t);
        return l;
    }

    private PriceRequest requestPrices(Tender t, TenderLot lot) {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Ядро поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1).build());
        return pr;
    }

    @Test
    void createEmpty_numberStatusMarketDate() {
        Tender t = service.createEmpty();

        assertThat(t.getTenderNumber()).matches("ЧЗ-" + LocalDate.now(Market.KZ.zone()).getYear() + "-\\d{4}");
        assertThat(t.getStatus()).isEqualTo("NEW");
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getLots()).isEmpty();
    }

    @Test
    void numberIsMaxPlusOne_noDuplicateAfterDelete() {
        Tender a = service.createEmpty();
        Tender b = service.createEmpty();
        service.delete(a.getId(), "admin");
        em.flush();

        Tender c = service.createEmpty();

        int nb = Integer.parseInt(b.getTenderNumber().substring(b.getTenderNumber().lastIndexOf('-') + 1));
        int nc = Integer.parseInt(c.getTenderNumber().substring(c.getTenderNumber().lastIndexOf('-') + 1));
        assertThat(nc).isEqualTo(nb + 1);
    }

    @Test
    void findById_otherMarketOrPublicTender_is404() {
        Tender kz = service.createEmpty();
        Tender publicTender = tenderRepository.save(Tender.builder().tenderNumber("ZZ-PUB-1").status("ACTIVE").build());

        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.findById(kz.getId())).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.KZ);
        assertThatThrownBy(() -> service.findById(publicTender.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void facilityAndCustomerNameReplaceEachOther() {
        Tender t = service.createEmpty();
        Facility f = facility("ZZ Ядро клиника " + System.nanoTime(), Market.KZ);

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "  Частная клиника, Уральск ");
        assertThat(t.getCustomerName()).isEqualTo("Частная клиника, Уральск");
        assertThat(PrivateRequestViewBuilder.clientLabel(t)).isEqualTo("Частная клиника, Уральск");

        service.updateField(t.getId(), PrivateRequestField.FACILITY, String.valueOf(f.getId()));
        assertThat(t.getFacility().getId()).isEqualTo(f.getId());
        assertThat(t.getCustomerName()).isNull();

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ТОО «SP NEFT»");
        assertThat(t.getFacility()).isNull();

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "  ");
        assertThat(t.getCustomerName()).isNull();
        assertThat(PrivateRequestViewBuilder.clientLabel(t)).isEqualTo("Клиент не указан");
    }

    @Test
    void facilityOfOtherMarket_is404() {
        Tender t = service.createEmpty();
        Facility rf = facility("ZZ Ядро РФ клиника " + System.nanoTime(), Market.RF);

        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.FACILITY, String.valueOf(rf.getId())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void contactFieldsAreValidated() {
        Tender t = service.createEmpty();

        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "Айгуль Сапарова");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_PHONE, "+7 701 234 56 78");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_EMAIL, "aigul@clinic.kz");
        service.updateField(t.getId(), PrivateRequestField.DELIVERY_ADDRESS, "г. Уральск");
        service.updateField(t.getId(), PrivateRequestField.NOTE, "Нужно к 1 ноября");
        assertThat(t.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(t.getContactPhone()).isEqualTo("+7 701 234 56 78");
        assertThat(t.getContactEmail()).isEqualTo("aigul@clinic.kz");
        assertThat(t.getDeliveryAddress()).isEqualTo("г. Уральск");
        assertThat(t.getDescription()).isEqualTo("Нужно к 1 ноября");
        assertThat(PrivateRequestViewBuilder.clientLabel(t)).isEqualTo("Айгуль Сапарова");

        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.CONTACT_EMAIL, "не почта"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("почты");
        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.CONTACT_PHONE, "1".repeat(51)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Телефон");
        assertThatThrownBy(() -> service.updateField(t.getId(), PrivateRequestField.FACILITY, "abc"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void saveClient_createsFacilityAndLinks() {
        Tender t = service.createEmpty();
        String name = "ZZ Новая клиника " + System.nanoTime();
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, name);
        service.updateField(t.getId(), PrivateRequestField.CONTACT_PHONE, "+7 701 000 00 01");

        service.saveClient(t.getId());

        assertThat(t.getFacility()).isNotNull();
        assertThat(t.getFacility().getName()).isEqualTo(name);
        assertThat(t.getFacility().getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getFacility().getPhone()).isEqualTo("+7 701 000 00 01");
        assertThat(t.getCustomerName()).isNull();
    }

    @Test
    void saveClient_sameMarketNameLinksExisting_otherMarketNameIs400() {
        Facility existing = facility("ZZ Есть такая " + System.nanoTime(), Market.KZ);
        Facility rf = facility("ZZ Занято в РФ " + System.nanoTime(), Market.RF);
        Tender t = service.createEmpty();

        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, existing.getName());
        service.saveClient(t.getId());
        assertThat(t.getFacility().getId()).isEqualTo(existing.getId());

        Tender other = service.createEmpty();
        service.updateField(other.getId(), PrivateRequestField.CUSTOMER_NAME, rf.getName());
        assertThatThrownBy(() -> service.saveClient(other.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("другого рынка");

        Tender empty = service.createEmpty();
        assertThatThrownBy(() -> service.saveClient(empty.getId())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void closeAndReopen() {
        Tender t = service.createEmpty();

        service.close(t.getId(), PrivateRequestStatus.WON);
        assertThat(t.getStatus()).isEqualTo("WON");
        service.reopen(t.getId());
        assertThat(t.getStatus()).isEqualTo("NEW");
        assertThatThrownBy(() -> service.close(t.getId(), PrivateRequestStatus.NEW)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.close(t.getId(), null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void delete_blockedByPriceRequestOrClientOffer() {
        Tender withPr = service.createEmpty();
        requestPrices(withPr, lot(withPr, 1, "ZZ Позиция"));
        assertThatThrownBy(() -> service.delete(withPr.getId(), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("запросы цен");

        Tender withOffer = service.createEmpty();
        ClientOffer offer = clientOfferService.create("admin");
        offer.setTenderId(withOffer.getId());
        em.flush();
        assertThatThrownBy(() -> service.delete(withOffer.getId(), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("КП клиенту");
    }

    @Test
    void delete_returnsLeadToWork() {
        Tender t = service.createEmpty();
        Lead lead = intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-core-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим", "+7 777 000 00 09", null, null, "Нужен облучатель",
                List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();
        lead.setPrivateRequest(t);
        lead.setStatus(LeadStatus.CONVERTED);
        em.flush();

        service.delete(t.getId(), "admin");
        em.flush();

        assertThat(tenderRepository.findById(t.getId())).isEmpty();
        assertThat(lead.getPrivateRequest()).isNull();
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(lead.getEvents()).anyMatch(e -> e.getBody() != null && e.getBody().contains("заявка " + t.getTenderNumber() + " удалена"));
    }

    @Test
    void view_hasLeadLinesRequestedAndRegistry() {
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-VIEW-RU-1").name("Аппарат ZZVIEWMODEL")
                .producer("ZZVIEWVENDOR").country("ЯПОНИЯ").unlimited(true).build());
        Tender t = service.createEmpty();
        TenderLot second = lot(t, 2, "Вторая позиция");
        TenderLot first = lot(t, 1, "Первая позиция");
        first.setRegStatus(LineRegStatus.CONFIRMED);
        first.setRegNumber("ZZ-VIEW-RU-1");
        requestPrices(t, second);
        em.flush();

        PrivateRequestViewResponse v = viewBuilder.build(t);

        assertThat(v.getNumber()).isEqualTo(t.getTenderNumber());
        assertThat(v.getStatus()).isEqualTo("NEW");
        assertThat(v.getLines()).extracting(PrivateRequestLineView::getName).containsExactly("Первая позиция", "Вторая позиция");
        assertThat(v.getLines().get(0).isRequested()).isFalse();
        assertThat(v.getLines().get(1).isRequested()).isTrue();
        assertThat(v.getLines().get(0).getRegStatus()).isEqualTo("CONFIRMED");
        assertThat(v.getLines().get(0).getRegistry().getProducer()).isEqualTo("ZZVIEWVENDOR");
        assertThat(v.getLines().get(1).getRegistry()).isNull();
    }
}
```

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestApiTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-уровень: права (запись — только админ) и гард рынка по заголовку X-Market. */
@SpringBootTest
@Transactional
class PrivateRequestApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) {
        return b.header("X-Market", "KZ");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsButCannotWrite() throws Exception {
        mvc.perform(kz(get("/api/private-requests"))).andExpect(status().isOk());
        mvc.perform(kz(post("/api/private-requests"))).andExpect(status().isForbidden());
        mvc.perform(kz(patch("/api/private-requests/1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"NOTE\",\"value\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(kz(post("/api/private-requests/1/close")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"WON\"}")).andExpect(status().isForbidden());
        mvc.perform(kz(post("/api/private-requests/1/save-client"))).andExpect(status().isForbidden());
        mvc.perform(kz(delete("/api/private-requests/1"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreatesAndPatches_otherMarketIs404() throws Exception {
        String body = mvc.perform(kz(post("/api/private-requests")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = om.readTree(body).get("id").asLong();

        mvc.perform(kz(patch("/api/private-requests/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"CONTACT_NAME\",\"value\":\"Айгуль\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("Айгуль"))
                .andExpect(jsonPath("$.clientLabel").value("Айгуль"));
        mvc.perform(kz(patch("/api/private-requests/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"без поля\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/private-requests/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Запустить — тесты не компилируются**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestCoreTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestApiTest'`
Expected: FAIL — нет `PrivateRequestField`, `PrivateRequestViewBuilder`, `createEmpty`.

- [ ] **Step 3: DTO**

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestField.java`:

```java
package com.vladoose.nir.dto.request;

/** Поле шапки частной заявки, которое сохраняется по выходу из поля (спека §5.2). */
public enum PrivateRequestField {
    /** клиент из «Учреждений»: value — id учреждения или пусто */
    FACILITY,
    /** клиент текстом (не из справочника) */
    CUSTOMER_NAME,
    CONTACT_NAME, CONTACT_PHONE, CONTACT_EMAIL, DELIVERY_ADDRESS, NOTE
}
```

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestFieldUpdate.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Одно изменённое поле шапки; value пустое — поле очищается. */
@Data
public class PrivateRequestFieldUpdate {
    @NotNull(message = "Не указано поле")
    private PrivateRequestField field;
    @Size(max = 10000, message = "Слишком длинное значение")
    private String value;
}
```

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestCloseRequest.java`:

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.PrivateRequestStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PrivateRequestCloseRequest {
    @NotNull(message = "Укажите исход")
    private PrivateRequestStatus outcome;
}
```

`src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineView.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

/** Позиция на странице заявки. registry — запись реестра для подсказки или подтверждённого РУ. */
@Data
public class PrivateRequestLineView {
    private Long lotId;
    private Integer lineNo;
    private String name;
    private String brand;
    private Integer quantity;
    private String unit;
    private String spec;
    private String productUrl;
    /** null | SUGGESTED | CONFIRMED | NOT_FOUND | NOT_REQUIRED */
    private String regStatus;
    private RegistryCandidateResponse registry;
    /** по позиции есть запросы цен — удалить нельзя */
    private boolean requested;
}
```

`src/main/java/com/vladoose/nir/dto/response/PrivateRequestViewResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** Страница частной заявки (спека §5). stage/stageText заполняет задача 3. */
@Data
public class PrivateRequestViewResponse {
    private Long id;
    private String number;
    /** NEW | WON | LOST | CANCELLED */
    private String status;
    private String stage;
    private String stageText;
    private Long facilityId;
    private String facilityName;
    private String customerName;
    /** как клиент показывается: учреждение → текст → контакт → телефон → «Клиент не указан» */
    private String clientLabel;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String deliveryAddress;
    private String note;
    private OffsetDateTime createdAt;
    private LeadRefResponse lead;
    private List<PrivateRequestLineView> lines;
}
```

В `PrivateRequestCreate.java` снять `@NotNull(message = "Клиент обязателен")` с `clientFacilityId` (и неиспользуемый импорт `NotNull`): клиент необязателен; старый путь обращения сам проверяет «ровно одно из двух».

- [ ] **Step 4: Репозитории**

`TenderRepository.java` — добавить:

```java
    /** Номера частных заявок рынка по шаблону «ЧЗ-<год>-%» (HQL — рынок режет аспект). */
    @Query("SELECT t.tenderNumber FROM Tender t WHERE t.source = com.vladoose.nir.entity.Source.PRIVATE_REQUEST "
         + "AND t.tenderNumber LIKE :pattern")
    List<String> findPrivateNumbers(@Param("pattern") String pattern);
```

`MedRegistryRepository.java` — добавить (импорт `java.util.Collection`):

```java
    List<MedRegistry> findByRegNumberIn(Collection<String> regNumbers);
```

`ClientOfferRepository.java` — добавить:

```java
    boolean existsByTenderId(Long tenderId);
```

- [ ] **Step 5: `LeadService.transition` — статический**

В `LeadService.java` заменить объявление `void transition(Lead lead, LeadStatus to, String author, String note) {` на:

```java
    /**
     * Переход статуса с событием ленты и записью статуса на сайт — общий для обращений и для удаления частной заявки
     * (PrivateRequestService.delete не может зависеть от LeadService: тот зависит от него).
     */
    public static void transition(Lead lead, LeadStatus to, String author, String note) {
```

Тело не меняется: в нём нет обращений к полям экземпляра (`statusLabel` и `LeadSources` — статические).

- [ ] **Step 6: `PrivateRequestService` — переписать целиком**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.PrivateRequestUpdate;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Частная заявка — tender с source = PRIVATE_REQUEST (спека 2026-10-12): черновик создаётся сразу, шапка правится по
 * полю, лоты — только через коллекцию t.getLots() (orphanRemoval, CLAUDE.md §7).
 */
@Service
public class PrivateRequestService {

    private static final Pattern NUMBER_SUFFIX = Pattern.compile("^ЧЗ-\\d{4}-(\\d+)$");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final TenderRepository tenderRepository;
    private final FacilityRepository facilityRepository;
    private final RegistryMatchService registryMatchService;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final ClientOfferRepository clientOfferRepository;
    private final LeadRepository leadRepository;

    public PrivateRequestService(TenderRepository tenderRepository,
                                 FacilityRepository facilityRepository,
                                 RegistryMatchService registryMatchService,
                                 PriceRequestItemRepository priceRequestItemRepository,
                                 PriceRequestRepository priceRequestRepository,
                                 ClientOfferRepository clientOfferRepository,
                                 LeadRepository leadRepository) {
        this.tenderRepository = tenderRepository;
        this.facilityRepository = facilityRepository;
        this.registryMatchService = registryMatchService;
        this.priceRequestItemRepository = priceRequestItemRepository;
        this.priceRequestRepository = priceRequestRepository;
        this.clientOfferRepository = clientOfferRepository;
        this.leadRepository = leadRepository;
    }

    /** «+ Новая заявка»: пустой черновик — номер, «в работе», рынок (ставит листенер), дата (@PrePersist). */
    @Transactional
    public Tender createEmpty() {
        Tender t = Tender.builder()
                .tenderNumber(nextNumber())
                .status(PrivateRequestStatus.NEW.name())
                .source(Source.PRIVATE_REQUEST)
                .build();
        return tenderRepository.save(t);
    }

    /** Заявка по id — только частная и только текущего рынка: findById = em.find, фильтр рынка его не режет (§14). */
    public Tender findById(Long id) {
        Tender t = tenderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: id=" + id));
        if (t.getSource() != Source.PRIVATE_REQUEST
                || (t.getMarket() != null && t.getMarket() != MarketContext.get())) {
            throw new NotFoundException("Заявка не найдена: id=" + id);
        }
        return t;
    }

    /** Одно поле шапки (сохранение по выходу из поля). Пустое значение очищает поле. */
    @Transactional
    public Tender updateField(Long id, PrivateRequestField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле");
        Tender t = findById(id);
        String v = blankToNull(value);
        switch (field) {
            case FACILITY -> {
                if (v == null) {
                    t.setFacility(null);
                } else {
                    t.setFacility(facility(parseId(v)));
                    t.setCustomerName(null);
                }
            }
            case CUSTOMER_NAME -> {
                t.setCustomerName(limit(v, 500, "Клиент"));
                if (v != null) t.setFacility(null);
            }
            case CONTACT_NAME -> t.setContactName(limit(v, 255, "Контактное лицо"));
            case CONTACT_PHONE -> t.setContactPhone(limit(v, 50, "Телефон"));
            case CONTACT_EMAIL -> {
                String email = limit(v, 255, "Почта");
                if (email != null && !EMAIL.matcher(email).matches()) {
                    throw new BadRequestException("Неверный адрес почты: " + email);
                }
                t.setContactEmail(email);
            }
            case DELIVERY_ADDRESS -> t.setDeliveryAddress(limit(v, 2000, "Куда поставка"));
            case NOTE -> t.setDescription(limit(v, 10000, "Примечание"));
        }
        return t;
    }

    /** «Сохранить в Учреждения»: клиент из текста становится учреждением; такое же название этого рынка — привязывается. */
    @Transactional
    public Tender saveClient(Long id) {
        Tender t = findById(id);
        String name = trunc(blankToNull(t.getCustomerName()), 255);
        if (name == null) throw new BadRequestException("Клиент не вписан текстом — сохранять нечего");
        Facility f = facilityRepository.findByName(name).orElse(null);   // HQL: учреждение чужого рынка аспект отрезает
        if (f == null) {
            if (facilityRepository.existsByNameAnyMarket(name)) {
                throw new BadRequestException("Название «" + name + "» уже занято клиентом другого рынка — уточните название");
            }
            f = facilityRepository.save(Facility.builder()
                    .name(name)
                    .phone(trunc(t.getContactPhone(), 50))
                    .email(trunc(t.getContactEmail(), 255))
                    .market(MarketContext.get())
                    .build());
        }
        t.setFacility(f);
        t.setCustomerName(null);
        return t;
    }

    @Transactional
    public Tender close(Long id, PrivateRequestStatus outcome) {
        if (outcome == null || !outcome.closed()) {
            throw new BadRequestException("Укажите исход: купили у нас, не купили или отменена");
        }
        Tender t = findById(id);
        t.setStatus(outcome.name());
        return t;
    }

    @Transactional
    public Tender reopen(Long id) {
        Tender t = findById(id);
        t.setStatus(PrivateRequestStatus.NEW.name());
        return t;
    }

    /** Только заявка без запросов цен и без КП клиенту. Обращение, из которого она создана, возвращается «В работу». */
    @Transactional
    public void delete(Long id, String author) {
        Tender t = findById(id);
        if (!priceRequestRepository.findByTenderId(id).isEmpty()) {
            throw new BadRequestException("Удалить нельзя: по заявке есть запросы цен поставщикам");
        }
        if (clientOfferRepository.existsByTenderId(id)) {
            throw new BadRequestException("Удалить нельзя: по заявке есть КП клиенту");
        }
        leadRepository.findFirstByPrivateRequestId(id).ifPresent(lead -> {
            lead.setPrivateRequest(null);
            if (lead.getStatus() == LeadStatus.CONVERTED) {
                LeadService.transition(lead, LeadStatus.IN_WORK, author, "заявка " + t.getTenderNumber() + " удалена");
            }
        });
        tenderRepository.delete(t);
    }

    /** Заявка со строками (импорт Excel, превращение обращения из старого диалога). Клиент необязателен. */
    @Transactional
    public Tender createFromLines(PrivateRequestCreate dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка");
        }
        Tender t = createEmpty();
        if (dto.getClientFacilityId() != null) t.setFacility(facility(dto.getClientFacilityId()));
        t.setDescription(dto.getNote());
        int lotNo = 1;
        for (PrivateRequestCreate.Line line : dto.getLines()) {
            t.getLots().add(TenderLot.builder()
                    .tender(t)
                    .lotNumber(lotNo++)
                    .equipName(line.getName())
                    .manufact(line.getManufact())
                    .quantity(line.getQuantity())
                    .build());
        }
        tenderRepository.flush();
        return t;
    }

    /** Правка строк целиком (старый PUT боковой панели) — уходит в задаче 4 вместе с панелью. */
    @Transactional
    public Tender update(Long id, PrivateRequestUpdate dto) {
        Tender t = findById(id);
        if (dto.getNote() != null) {
            t.setDescription(dto.getNote());
        }
        List<PrivateRequestUpdate.Line> incoming = dto.getLines() != null ? dto.getLines() : List.of();
        Set<Long> incomingIds = new HashSet<>();
        for (PrivateRequestUpdate.Line line : incoming) {
            if (line.getLotId() != null) incomingIds.add(line.getLotId());
        }
        t.getLots().removeIf(lot -> !incomingIds.contains(lot.getId())
                && priceRequestItemRepository.findByTenderLotId(lot.getId()).isEmpty());
        int lotNo = 1;
        for (PrivateRequestUpdate.Line line : incoming) {
            if (line.getName() == null || line.getName().isBlank()) continue;
            TenderLot lot = null;
            if (line.getLotId() != null) {
                for (TenderLot e : t.getLots()) {
                    if (e.getId() != null && e.getId().equals(line.getLotId())) { lot = e; break; }
                }
            }
            if (lot == null) {
                lot = TenderLot.builder().tender(t).build();
                t.getLots().add(lot);
            }
            lot.setLotNumber(lotNo++);
            lot.setEquipName(line.getName());
            lot.setManufact(line.getManufact());
            lot.setQuantity(line.getQuantity() != null ? line.getQuantity() : 1);
        }
        tenderRepository.save(t);
        return findById(id);
    }

    /** Список для старого GET (уходит в задаче 3). */
    public List<Tender> findAll() {
        return tenderRepository.findBySource(Source.PRIVATE_REQUEST);
    }

    /** Реестр-догадка по строкам для старого подбора поставщиков (уходит в задаче 7). */
    public List<PrivateRequestLineResponse> linesWithRegistration(Long tenderId) {
        Tender t = findById(tenderId);
        List<PrivateRequestLineResponse> result = new ArrayList<>();
        for (TenderLot lot : t.getLots()) {
            PrivateRequestLineResponse r = new PrivateRequestLineResponse();
            r.setLotId(lot.getId());
            r.setName(lot.getEquipName());
            r.setManufact(lot.getManufact());
            r.setQuantity(lot.getQuantity());
            List<RegistryCandidateResponse> cands =
                    registryMatchService.findCandidates(lot.getEquipName(), lot.getManufact(), 1);
            if (!cands.isEmpty()) {
                r.setTopCandidate(cands.get(0));
                r.setRegistrationStatus("REGISTERED");
            } else {
                r.setRegistrationStatus("NOT_FOUND");
            }
            result.add(r);
        }
        return result;
    }

    /** «ЧЗ-<год>-NNNN»: наибольший номер этого года на рынке + 1, а не количество — после удаления дубля не будет. */
    private String nextNumber() {
        int year = LocalDate.now(MarketContext.get().zone()).getYear();
        String prefix = "ЧЗ-" + year + "-";
        int max = 0;
        for (String n : tenderRepository.findPrivateNumbers(prefix + "%")) {
            Matcher m = NUMBER_SUFFIX.matcher(n);
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("%s%04d", prefix, max + 1);
    }

    private Facility facility(Long facilityId) {
        Facility f = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new NotFoundException("Клиент не найден: id=" + facilityId));
        if (f.getMarket() != null && f.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Клиент не найден: id=" + facilityId);
        }
        return f;
    }

    private static Long parseId(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Неверный id клиента: " + v);
        }
    }

    private static String limit(String v, int max, String label) {
        if (v != null && v.length() > max) {
            throw new BadRequestException(label + ": слишком длинно — не больше " + max + " символов");
        }
        return v;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 7: `PrivateRequestViewBuilder`**

`src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java`:

```java
package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.PrivateRequestLineView;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.mapper.LeadResponseMapper;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/** Ответ страницы заявки: шапка, позиции с РУ и признаком «уже запрашивали», обращение-источник. */
@Component
public class PrivateRequestViewBuilder {

    private static final Comparator<TenderLot> BY_LINE = Comparator
            .comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
            .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId());

    private final PriceRequestItemRepository itemRepository;
    private final MedRegistryRepository registryRepository;
    private final LeadRepository leadRepository;
    private final LeadResponseMapper leadMapper;

    public PrivateRequestViewBuilder(PriceRequestItemRepository itemRepository, MedRegistryRepository registryRepository,
                                     LeadRepository leadRepository, LeadResponseMapper leadMapper) {
        this.itemRepository = itemRepository;
        this.registryRepository = registryRepository;
        this.leadRepository = leadRepository;
        this.leadMapper = leadMapper;
    }

    public PrivateRequestViewResponse build(Tender t) {
        PrivateRequestViewResponse r = new PrivateRequestViewResponse();
        r.setId(t.getId());
        r.setNumber(t.getTenderNumber());
        r.setStatus(PrivateRequestStatus.of(t.getStatus()).name());
        if (t.getFacility() != null) {
            r.setFacilityId(t.getFacility().getId());
            r.setFacilityName(t.getFacility().getName());
        }
        r.setCustomerName(t.getCustomerName());
        r.setClientLabel(clientLabel(t));
        r.setContactName(t.getContactName());
        r.setContactPhone(t.getContactPhone());
        r.setContactEmail(t.getContactEmail());
        r.setDeliveryAddress(t.getDeliveryAddress());
        r.setNote(t.getDescription());
        r.setCreatedAt(t.getCreatedAt());
        leadRepository.findFirstByPrivateRequestId(t.getId()).map(leadMapper::toRef).ifPresent(r::setLead);

        Set<Long> requested = new HashSet<>();
        for (PriceRequestItem i : itemRepository.findByTenderId(t.getId())) {
            requested.add(i.getTenderLot().getId());
        }
        List<TenderLot> lots = t.getLots().stream().sorted(BY_LINE).toList();
        Map<String, MedRegistry> records = records(lots);
        r.setLines(lots.stream()
                .map(l -> line(l, requested.contains(l.getId()), records.get(l.getRegNumber())))
                .toList());
        return r;
    }

    /** Одна позиция — ответ на правку строки и на действие с РУ. */
    public PrivateRequestLineView line(TenderLot l) {
        MedRegistry rec = l.getRegNumber() == null ? null : registryRepository.findByRegNumber(l.getRegNumber()).orElse(null);
        return line(l, l.getId() != null && itemRepository.existsByTenderLotId(l.getId()), rec);
    }

    private PrivateRequestLineView line(TenderLot l, boolean requested, MedRegistry rec) {
        PrivateRequestLineView v = new PrivateRequestLineView();
        v.setLotId(l.getId());
        v.setLineNo(l.getLotNumber());
        v.setName(l.getEquipName());
        v.setBrand(l.getManufact());
        v.setQuantity(l.getQuantity());
        v.setUnit(l.getUnit());
        v.setSpec(l.getRequiredSpec());
        v.setProductUrl(l.getProductUrl());
        v.setRegStatus(l.getRegStatus() == null ? null : l.getRegStatus().name());
        v.setRegistry(rec == null ? null : candidate(rec));
        v.setRequested(requested);
        return v;
    }

    private Map<String, MedRegistry> records(List<TenderLot> lots) {
        Set<String> numbers = new HashSet<>();
        for (TenderLot l : lots) {
            if (l.getRegNumber() != null) numbers.add(l.getRegNumber());
        }
        Map<String, MedRegistry> out = new HashMap<>();
        if (numbers.isEmpty()) return out;
        for (MedRegistry m : registryRepository.findByRegNumberIn(numbers)) out.put(m.getRegNumber(), m);
        return out;
    }

    public static RegistryCandidateResponse candidate(MedRegistry m) {
        RegistryCandidateResponse c = new RegistryCandidateResponse();
        c.setRegNumber(m.getRegNumber());
        c.setName(m.getName());
        c.setProducer(m.getProducer());
        c.setCountry(m.getCountry());
        c.setRegDate(m.getRegDate());
        c.setExpirationDate(m.getExpirationDate());
        c.setUnlimited(m.getUnlimited());
        return c;
    }

    /** Как клиент показывается в списке, шапке и КП (спека §5.1). */
    public static String clientLabel(Tender t) {
        if (t.getFacility() != null && notBlank(t.getFacility().getName())) return t.getFacility().getName();
        if (notBlank(t.getCustomerName())) return t.getCustomerName().trim();
        if (notBlank(t.getContactName())) return t.getContactName().trim();
        if (notBlank(t.getContactPhone())) return t.getContactPhone().trim();
        return "Клиент не указан";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 8: `PrivateRequestController` — переписать целиком**

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.mapper.FacilityMapper;
import com.vladoose.nir.mapper.LeadResponseMapper;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.PrivateRequestImportService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/private-requests")
public class PrivateRequestController {

    private final PrivateRequestService service;
    private final PrivateRequestViewBuilder viewBuilder;
    private final FacilityMapper facilityMapper;
    private final PrivateRequestSourcingService sourcingService;
    private final PrivateRequestImportService importService;
    private final LeadService leadService;
    private final LeadResponseMapper leadResponseMapper;

    public PrivateRequestController(PrivateRequestService service, PrivateRequestViewBuilder viewBuilder,
                                    FacilityMapper facilityMapper, PrivateRequestSourcingService sourcingService,
                                    PrivateRequestImportService importService,
                                    LeadService leadService, LeadResponseMapper leadResponseMapper) {
        this.service = service;
        this.viewBuilder = viewBuilder;
        this.facilityMapper = facilityMapper;
        this.sourcingService = sourcingService;
        this.importService = importService;
        this.leadService = leadService;
        this.leadResponseMapper = leadResponseMapper;
    }

    /** Старый список (задача 3 заменит его списком с этапом и поиском). */
    @GetMapping
    public List<PrivateRequestResponse> findAll() {
        return service.findAll().stream().map(t -> {
            PrivateRequestResponse r = toShort(t);
            r.setLineCount(t.getLots().size());
            r.setRegisteredCount(-1);
            return r;
        }).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse create() {
        return viewBuilder.build(service.createEmpty());
    }

    @GetMapping("/{id}")
    public PrivateRequestViewResponse get(@PathVariable Long id) {
        return viewBuilder.build(service.findById(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse updateField(@PathVariable Long id, @Valid @RequestBody PrivateRequestFieldUpdate req) {
        return viewBuilder.build(service.updateField(id, req.getField(), req.getValue()));
    }

    @PostMapping("/{id}/save-client")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse saveClient(@PathVariable Long id) {
        return viewBuilder.build(service.saveClient(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse close(@PathVariable Long id, @Valid @RequestBody PrivateRequestCloseRequest req) {
        return viewBuilder.build(service.close(id, req.getOutcome()));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse reopen(@PathVariable Long id) {
        return viewBuilder.build(service.reopen(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id, currentUser());
    }

    /** Старая правка строк целиком (задача 4 заменит её позициями по одной). */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse update(@PathVariable Long id, @Valid @RequestBody PrivateRequestUpdate request) {
        return buildFull(service.update(id, request));
    }

    @PostMapping("/import/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse importPreview(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.preview(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping("/import/commit")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse importCommit(@Valid @RequestBody ImportCommitRequest request) {
        return buildFull(importService.commit(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badFile(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    @GetMapping("/{id}/sourcing")
    public SourcingPreviewResponse sourcing(@PathVariable Long id) {
        return sourcingService.buildSourcing(id);
    }

    private PrivateRequestResponse buildFull(Tender t) {
        PrivateRequestResponse r = toShort(t);
        List<PrivateRequestLineResponse> lines = service.linesWithRegistration(t.getId());
        r.setLineCount(lines.size());
        r.setRegisteredCount((int) lines.stream().filter(l -> "REGISTERED".equals(l.getRegistrationStatus())).count());
        r.setLines(lines);
        leadService.findByPrivateRequest(t.getId()).map(leadResponseMapper::toRef).ifPresent(r::setLead);
        return r;
    }

    private PrivateRequestResponse toShort(Tender t) {
        PrivateRequestResponse r = new PrivateRequestResponse();
        r.setId(t.getId());
        r.setNumber(t.getTenderNumber());
        r.setStatus(t.getStatus());
        if (t.getFacility() != null) r.setClient(facilityMapper.toResponse(t.getFacility()));
        return r;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
```

- [ ] **Step 9: Поправить `LeadConvertTest.privateRequestCardLinksBackToLead`**

Ответ `GET /{id}` теперь — `PrivateRequestViewResponse`. В `LeadConvertTest` заменить импорт `com.vladoose.nir.dto.response.PrivateRequestResponse` на `com.vladoose.nir.dto.response.PrivateRequestViewResponse`, а в методе:

```java
        PrivateRequestViewResponse pr = privateRequestController.get(r.getPrivateRequestId());
```

Проверки `pr.getLead()…` остаются как были.

- [ ] **Step 10: Запустить тесты — зелёные**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.lead.*' --tests 'com.vladoose.nir.imports.*' --tests 'com.vladoose.nir.sourcing.*'`
Expected: PASS. Старые тесты (`PrivateRequestServiceTest`, `PrivateRequestUpdateTest`, импорт, подбор) тоже проходят — их пути ещё живы.

- [ ] **Step 11: Мутация гарда рынка**

Скопировать файл: `cp src/main/java/com/vladoose/nir/service/PrivateRequestService.java /tmp/prs.bak`. В `findById` убрать условие `|| (t.getMarket() != null && t.getMarket() != MarketContext.get())`.
Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestCoreTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestApiTest'`
Expected: FAIL — `findById_otherMarketOrPublicTender_is404` и `adminCreatesAndPatches_otherMarketIs404`.
Вернуть: `cp /tmp/prs.bak src/main/java/com/vladoose/nir/service/PrivateRequestService.java && diff -q /tmp/prs.bak src/main/java/com/vladoose/nir/service/PrivateRequestService.java`, затем `./gradlew compileJava` (`build/classes` снова = исходник, §14).

- [ ] **Step 12: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/PrivateRequestService.java src/main/java/com/vladoose/nir/service/LeadService.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestField.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestFieldUpdate.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestCloseRequest.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestCreate.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestViewResponse.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineView.java src/main/java/com/vladoose/nir/repository/TenderRepository.java src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestCoreTest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestApiTest.java src/test/java/com/vladoose/nir/lead/LeadConvertTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): черновик сразу, шапка по полю, клиент необязателен, закрытие и удаление; гард рынка

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Список заявок и этап

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestStageCalculator.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestListItemResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestService.java` (добавить `list`, убрать `findAll`)
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java` (этап в виде + `list(...)`)
- Modify: `src/main/java/com/vladoose/nir/repository/TenderRepository.java`, `TenderLotRepository.java`, `PriceRequestRepository.java`, `PriceRequestItemRepository.java`, `LeadRepository.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (список)
- Modify test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java` (убрать тест `findAll`)
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestListTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.findById/createEmpty`, `PrivateRequestViewBuilder.build`, `PrivateRequestStatus` (задачи 1–2).
- Produces:
  - **`PrivateRequestStageCalculator`:** `Map<Long, Summary> of(List<Tender>)`, `Summary of(Tender)`; `record Summary(String code, String text, long lineCount)`; статические `String positionsGen(long)`, `String answeredWord(long)`.
  - **Коды этапа:** `EMPTY | NEW | REQUESTED | PRICED | WON | LOST | CANCELLED`.
  - **`PrivateRequestService`:** `List<Tender> list(ListFilter filter, String q)`, `enum ListFilter { OPEN, CLOSED, ALL }`, `LIST_LIMIT = 300`.
  - **`PrivateRequestViewBuilder`:** `List<PrivateRequestListItemResponse> list(List<Tender>)`; `build(...)` заполняет `stage` и `stageText`.
  - **REST:** `GET /api/private-requests?status=OPEN|CLOSED|ALL&q=` → `List<PrivateRequestListItemResponse>`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestListTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.response.PrivateRequestListItemResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestService.ListFilter;
import com.vladoose.nir.service.privaterequest.PrivateRequestStageCalculator;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestListTest {

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestStageCalculator stages;
    @Autowired PrivateRequestViewBuilder viewBuilder;
    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired LeadIntakeService intake;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot lot(Tender t, int no, String name) {
        TenderLot l = TenderLot.builder().tender(t).lotNumber(no).equipName(name).quantity(1).build();
        t.getLots().add(l);
        tenderRepository.saveAndFlush(t);
        return l;
    }

    private PriceRequest ask(Tender t, String status, TenderLot... lots) {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Этап поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status(status).build());
        for (TenderLot l : lots) {
            pr.getItems().add(itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build()));
        }
        return pr;
    }

    @Test
    void stageFollowsData() {
        Tender t = service.createEmpty();
        assertThat(stages.of(t).text()).isEqualTo("Пустая");

        TenderLot a = lot(t, 1, "ZZ Позиция А");
        lot(t, 2, "ZZ Позиция Б");
        assertThat(stages.of(t).code()).isEqualTo("NEW");
        assertThat(stages.of(t).text()).isEqualTo("Новая");
        assertThat(stages.of(t).lineCount()).isEqualTo(2);

        ask(t, "SENT", a);
        PriceRequest answered = ask(t, "RESPONDED", a);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("REQUESTED");
        assertThat(stages.of(t).text()).isEqualTo("Запрошены цены · 1 из 2 ответил");

        answered.getItems().get(0).setResponsePrice(new BigDecimal("18400.00"));
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("PRICED");
        assertThat(stages.of(t).text()).isEqualTo("Есть цены: 1 из 2 позиций");

        service.close(t.getId(), PrivateRequestStatus.WON);
        assertThat(stages.of(t).text()).isEqualTo("Закрыта: купили у нас");
        assertThat(viewBuilder.build(t).getStage()).isEqualTo("WON");
    }

    @Test
    void pluralForms() {
        assertThat(PrivateRequestStageCalculator.positionsGen(1)).isEqualTo("позиции");
        assertThat(PrivateRequestStageCalculator.positionsGen(2)).isEqualTo("позиций");
        assertThat(PrivateRequestStageCalculator.positionsGen(11)).isEqualTo("позиций");
        assertThat(PrivateRequestStageCalculator.positionsGen(21)).isEqualTo("позиции");
        assertThat(PrivateRequestStageCalculator.answeredWord(1)).isEqualTo("ответил");
        assertThat(PrivateRequestStageCalculator.answeredWord(5)).isEqualTo("ответили");
        assertThat(PrivateRequestStageCalculator.answeredWord(11)).isEqualTo("ответили");
    }

    @Test
    void listFiltersSearchesAndSortsNewestFirst() {
        Tender older = service.createEmpty();
        lot(older, 1, "ZZСПИСОК Облучатель ОБН-150");
        Tender newer = service.createEmpty();
        service.updateField(newer.getId(), PrivateRequestField.CONTACT_PHONE, "+7 799 911 22 33");
        service.updateField(newer.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZСПИСОК Клиника Здоровье");
        Tender closed = service.createEmpty();
        service.close(closed.getId(), PrivateRequestStatus.LOST);
        em.flush();

        List<Long> open = service.list(ListFilter.OPEN, null).stream().map(Tender::getId).toList();
        assertThat(open).contains(older.getId(), newer.getId()).doesNotContain(closed.getId());
        assertThat(open.indexOf(newer.getId())).isLessThan(open.indexOf(older.getId()));
        assertThat(service.list(ListFilter.CLOSED, null)).extracting(Tender::getId).contains(closed.getId())
                .doesNotContain(older.getId());
        assertThat(service.list(ListFilter.ALL, null)).extracting(Tender::getId)
                .contains(older.getId(), newer.getId(), closed.getId());

        // уникальные токены: в nirdb живут настоящие заявки, их позиции и телефоны не должны попасть в выдачу
        assertThat(service.list(ListFilter.OPEN, "zzсписок облучатель")).extracting(Tender::getId).containsExactly(older.getId());
        assertThat(service.list(ListFilter.OPEN, "799 911 22")).extracting(Tender::getId).containsExactly(newer.getId());
        assertThat(service.list(ListFilter.OPEN, "zzсписок клиника")).extracting(Tender::getId).containsExactly(newer.getId());
        // % и _ из запроса — буквальные знаки, а не шаблон LIKE (иначе «zz%_» нашёл бы обе заявки)
        assertThat(service.list(ListFilter.OPEN, "zz%_нет")).isEmpty();
    }

    @Test
    void listItemCarriesStageClientAndLead() {
        Tender t = service.createEmpty();
        lot(t, 1, "ZZ Позиция");
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Клиника Список");
        Lead lead = intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-list-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим", "+7 777 000 00 08", null, null, null,
                List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();
        lead.setPrivateRequest(t);
        em.flush();

        PrivateRequestListItemResponse item = viewBuilder.list(List.of(t)).get(0);

        assertThat(item.getNumber()).isEqualTo(t.getTenderNumber());
        assertThat(item.getStage()).isEqualTo("NEW");
        assertThat(item.getStageText()).isEqualTo("Новая");
        assertThat(item.getClientLabel()).isEqualTo("ZZ Клиника Список");
        assertThat(item.getLineCount()).isEqualTo(1);
        assertThat(item.getLeadId()).isEqualTo(lead.getId());
        assertThat(item.getCreatedAt()).isNotNull();
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestListTest'`
Expected: FAIL — нет `PrivateRequestStageCalculator`, `ListFilter`.

- [ ] **Step 3: Запросы репозиториев**

`TenderRepository.java` — добавить (импорты `java.util.Collection`, `org.springframework.data.domain.Pageable`):

```java
    /** Частные заявки рынка в статусах — новые сверху (у заявок до V26 дата может быть пустой — они в конце). */
    @Query("SELECT t FROM Tender t WHERE t.source = com.vladoose.nir.entity.Source.PRIVATE_REQUEST "
         + "AND t.status IN :statuses ORDER BY t.createdAt DESC NULLS LAST, t.id DESC")
    List<Tender> findPrivate(@Param("statuses") Collection<String> statuses, Pageable pageable);
```

`TenderLotRepository.java` — добавить (импорт `java.util.Collection`):

```java
    @Query("SELECT l.tender.id, COUNT(l) FROM TenderLot l WHERE l.tender.id IN :ids GROUP BY l.tender.id")
    List<Object[]> countByTenderIds(@Param("ids") Collection<Long> ids);

    /** Частные заявки, у которых наименование или бренд позиции подходит под шаблон LIKE (с экранированием \). */
    @Query("SELECT DISTINCT l.tender.id FROM TenderLot l WHERE l.tender.source = com.vladoose.nir.entity.Source.PRIVATE_REQUEST "
         + "AND (LOWER(l.equipName) LIKE :pattern ESCAPE '\\' OR LOWER(l.manufact) LIKE :pattern ESCAPE '\\')")
    List<Long> findPrivateTenderIdsByLineText(@Param("pattern") String pattern);
```

`PriceRequestRepository.java` — добавить (импорт `java.util.Collection`):

```java
    /** Для этапа заявки: (заявка, поставщик, статус запроса). */
    @Query("SELECT pr.tender.id, pr.distributor.id, pr.status FROM PriceRequest pr WHERE pr.tender.id IN :ids")
    List<Object[]> findStageRows(@Param("ids") Collection<Long> ids);
```

`PriceRequestItemRepository.java` — добавить (импорт `java.util.Collection`):

```java
    /** Для этапа заявки: (заявка, позиция) с введённой ценой ответа. */
    @Query("SELECT DISTINCT i.priceRequest.tender.id, i.tenderLot.id FROM PriceRequestItem i "
         + "WHERE i.priceRequest.tender.id IN :ids AND i.responsePrice IS NOT NULL")
    List<Object[]> findPricedLots(@Param("ids") Collection<Long> ids);
```

`LeadRepository.java` — добавить:

```java
    List<Lead> findByPrivateRequestIdIn(Collection<Long> privateRequestIds);
```

- [ ] **Step 4: `PrivateRequestStageCalculator`**

```java
package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.entity.PrivateRequestStatus;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Этап частной заявки — считается из данных при каждом чтении (спека §5.4), сверху вниз: закрыта → (КП клиенту — этап 3)
 * → есть цены → запрошены цены → новая → пустая. Три запроса на весь список — без N+1.
 */
@Component
public class PrivateRequestStageCalculator {

    public record Summary(String code, String text, long lineCount) {}

    /** Поставщик «ответил», если хоть один его запрос по заявке получил ответ или отказ. */
    private static final Set<String> ANSWERED = Set.of("RESPONDED", "ACCEPTED", "DECLINED");

    private final TenderLotRepository lotRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final PriceRequestItemRepository itemRepository;

    public PrivateRequestStageCalculator(TenderLotRepository lotRepository, PriceRequestRepository priceRequestRepository,
                                         PriceRequestItemRepository itemRepository) {
        this.lotRepository = lotRepository;
        this.priceRequestRepository = priceRequestRepository;
        this.itemRepository = itemRepository;
    }

    public Summary of(Tender t) {
        return of(List.of(t)).get(t.getId());
    }

    public Map<Long, Summary> of(List<Tender> requests) {
        Map<Long, Summary> out = new HashMap<>();
        if (requests.isEmpty()) return out;
        List<Long> ids = requests.stream().map(Tender::getId).toList();

        Map<Long, Long> lines = new HashMap<>();
        for (Object[] row : lotRepository.countByTenderIds(ids)) lines.put((Long) row[0], (Long) row[1]);

        Map<Long, Map<Long, Boolean>> asked = new HashMap<>();   // заявка → поставщик → ответил/отказал
        for (Object[] row : priceRequestRepository.findStageRows(ids)) {
            asked.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                    .merge((Long) row[1], ANSWERED.contains((String) row[2]), Boolean::logicalOr);
        }

        Map<Long, Set<Long>> priced = new HashMap<>();
        for (Object[] row : itemRepository.findPricedLots(ids)) {
            priced.computeIfAbsent((Long) row[0], k -> new HashSet<>()).add((Long) row[1]);
        }

        for (Tender t : requests) {
            long lineCount = lines.getOrDefault(t.getId(), 0L);
            out.put(t.getId(), summary(PrivateRequestStatus.of(t.getStatus()), lineCount,
                    asked.getOrDefault(t.getId(), Map.of()), priced.getOrDefault(t.getId(), Set.of()).size()));
        }
        return out;
    }

    static Summary summary(PrivateRequestStatus status, long lines, Map<Long, Boolean> asked, int pricedLines) {
        if (status == PrivateRequestStatus.WON) return new Summary("WON", "Закрыта: купили у нас", lines);
        if (status == PrivateRequestStatus.LOST) return new Summary("LOST", "Закрыта: не купили", lines);
        if (status == PrivateRequestStatus.CANCELLED) return new Summary("CANCELLED", "Закрыта: отменена", lines);
        if (pricedLines > 0) {
            return new Summary("PRICED", "Есть цены: " + pricedLines + " из " + lines + " " + positionsGen(lines), lines);
        }
        if (!asked.isEmpty()) {
            long answered = asked.values().stream().filter(Boolean::booleanValue).count();
            return new Summary("REQUESTED",
                    "Запрошены цены · " + answered + " из " + asked.size() + " " + answeredWord(answered), lines);
        }
        if (lines > 0) return new Summary("NEW", "Новая", lines);
        return new Summary("EMPTY", "Пустая", lines);
    }

    /** «из 1 позиции», «из 2 позиций», «из 11 позиций», «из 21 позиции». */
    public static String positionsGen(long n) {
        return n % 10 == 1 && n % 100 != 11 ? "позиции" : "позиций";
    }

    /** «1 из 3 ответил», «2 из 5 ответили», «11 из 12 ответили». */
    public static String answeredWord(long n) {
        return n % 10 == 1 && n % 100 != 11 ? "ответил" : "ответили";
    }
}
```

- [ ] **Step 5: DTO списка**

`src/main/java/com/vladoose/nir/dto/response/PrivateRequestListItemResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Карточка в списке «Частные заявки» (спека §5.5). */
@Data
public class PrivateRequestListItemResponse {
    private Long id;
    private String number;
    private String status;
    private String stage;
    private String stageText;
    private String clientLabel;
    private long lineCount;
    private OffsetDateTime createdAt;
    /** обращение, из которого создана заявка, — значок «из обращения» */
    private Long leadId;
}
```

- [ ] **Step 6: Сервис — список вместо `findAll`**

В `PrivateRequestService.java`:
- добавить зависимость `TenderLotRepository tenderLotRepository` (поле, параметр конструктора, присваивание);
- импорты: `org.springframework.data.domain.PageRequest`, `java.util.Locale`;
- удалить метод `findAll()`;
- добавить:

```java
    public static final int LIST_LIMIT = 300;

    public enum ListFilter { OPEN, CLOSED, ALL }

    /** Список рынка: фильтр статуса, новые сверху, до LIST_LIMIT; поиск — номер, клиент, контакт, телефон, позиции. */
    @Transactional(readOnly = true)
    public List<Tender> list(ListFilter filter, String q) {
        Set<String> statuses = switch (filter == null ? ListFilter.OPEN : filter) {
            case OPEN -> Set.of(PrivateRequestStatus.NEW.name());
            case CLOSED -> Set.of(PrivateRequestStatus.WON.name(), PrivateRequestStatus.LOST.name(),
                    PrivateRequestStatus.CANCELLED.name());
            case ALL -> Set.of(PrivateRequestStatus.NEW.name(), PrivateRequestStatus.WON.name(),
                    PrivateRequestStatus.LOST.name(), PrivateRequestStatus.CANCELLED.name());
        };
        List<Tender> rows = tenderRepository.findPrivate(statuses, PageRequest.of(0, LIST_LIMIT));
        if (q == null || q.isBlank()) return rows;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");
        Set<Long> byLines = new HashSet<>(tenderLotRepository.findPrivateTenderIdsByLineText(likePattern(needle)));
        return rows.stream().filter(t -> byLines.contains(t.getId()) || headerMatches(t, needle, digits)).toList();
    }

    private static boolean headerMatches(Tender t, String needle, String digits) {
        if (contains(t.getTenderNumber(), needle) || contains(t.getCustomerName(), needle)
                || contains(t.getContactName(), needle) || contains(t.getContactEmail(), needle)) {
            return true;
        }
        if (t.getFacility() != null && contains(t.getFacility().getName(), needle)) return true;
        return digits.length() >= 3 && t.getContactPhone() != null
                && t.getContactPhone().replaceAll("\\D", "").contains(digits);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** «%текст%» для LIKE … ESCAPE '\': знаки % и _ из запроса — буквально. */
    private static String likePattern(String needle) {
        return "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
```

- [ ] **Step 7: Этап в ответе и список в `PrivateRequestViewBuilder`**

- добавить зависимость `PrivateRequestStageCalculator stages` (поле, конструктор);
- импорт `com.vladoose.nir.dto.response.PrivateRequestListItemResponse`;
- в `build(Tender t)` после `r.setStatus(...)`:

```java
        PrivateRequestStageCalculator.Summary stage = stages.of(t);
        r.setStage(stage.code());
        r.setStageText(stage.text());
```

- добавить метод:

```java
    /** Карточки списка: этап и число позиций — одним проходом калькулятора, обращения — одним запросом. */
    public List<PrivateRequestListItemResponse> list(List<Tender> requests) {
        Map<Long, PrivateRequestStageCalculator.Summary> summaries = stages.of(requests);
        Map<Long, Long> leadIds = new HashMap<>();
        if (!requests.isEmpty()) {
            for (Lead l : leadRepository.findByPrivateRequestIdIn(requests.stream().map(Tender::getId).toList())) {
                leadIds.putIfAbsent(l.getPrivateRequest().getId(), l.getId());
            }
        }
        return requests.stream().map(t -> {
            PrivateRequestStageCalculator.Summary s = summaries.get(t.getId());
            PrivateRequestListItemResponse r = new PrivateRequestListItemResponse();
            r.setId(t.getId());
            r.setNumber(t.getTenderNumber());
            r.setStatus(PrivateRequestStatus.of(t.getStatus()).name());
            r.setStage(s.code());
            r.setStageText(s.text());
            r.setClientLabel(clientLabel(t));
            r.setLineCount(s.lineCount());
            r.setCreatedAt(t.getCreatedAt());
            r.setLeadId(leadIds.get(t.getId()));
            return r;
        }).toList();
    }
```

- [ ] **Step 8: Контроллер — новый список**

В `PrivateRequestController.java` заменить метод `findAll()` на:

```java
    @GetMapping
    public List<PrivateRequestListItemResponse> list(
            @RequestParam(defaultValue = "OPEN") PrivateRequestService.ListFilter status,
            @RequestParam(required = false) String q) {
        return viewBuilder.list(service.list(status, q));
    }
```

- [ ] **Step 9: Убрать тест старого `findAll`**

В `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java` удалить метод `findAll_returnsOnlyPrivateRequests` (метода `findAll` больше нет; то же покрывает `PrivateRequestListTest`).

- [ ] **Step 10: Запустить — зелёные**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS.

- [ ] **Step 11: Мутация приоритета этапа**

Копия `PrivateRequestStageCalculator.java` в `/tmp`. В `summary(...)` переставить проверку `if (pricedLines > 0)` ниже `if (!asked.isEmpty())`.
Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestListTest'`
Expected: FAIL `stageFollowsData` («Запрошены цены · …» вместо «Есть цены: …»). Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 12: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestStageCalculator.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestListItemResponse.java src/main/java/com/vladoose/nir/service/PrivateRequestService.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java src/main/java/com/vladoose/nir/repository/TenderRepository.java src/main/java/com/vladoose/nir/repository/TenderLotRepository.java src/main/java/com/vladoose/nir/repository/PriceRequestRepository.java src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java src/main/java/com/vladoose/nir/repository/LeadRepository.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestListTest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): список с этапом, фильтром «в работе / закрытые» и поиском

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Позиции по одной и РУ с подтверждением

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRegistry.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineInput.java`, `PrivateRequestLinesAdd.java`, `LinesMode.java`, `PrivateRequestLineField.java`, `PrivateRequestLineFieldUpdate.java`, `LineRegistryAction.java`, `LineRegistryActionRequest.java`
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestService.java` (позиции, РУ; `createFromLines` — через общий путь; удалить `update`)
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (эндпоинты позиций, удалить `PUT /{id}`)
- Delete: `src/main/java/com/vladoose/nir/dto/request/PrivateRequestUpdate.java`, `src/test/java/com/vladoose/nir/imports/PrivateRequestUpdateTest.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestLinesTest.java`, `PrivateRequestRegistryCalibrationTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.findById`, `PrivateRequestViewBuilder.line/build` (задача 2), `RegistryMatchService.findCandidates(name, manufact, limit)`, `MedRegistryRepository.findByRegNumber`.
- Produces:
  - **`PrivateRequestRegistry`:** `SUGGEST_MIN`; `void suggest(TenderLot)`, `void confirm(TenderLot, String regNumber)`, `void notRequired(TenderLot)`, `List<RegistryCandidateResponse> candidates(TenderLot)`.
  - **`PrivateRequestService`:**
    - `Tender addLines(Long id, List<PrivateRequestLineInput>, LinesMode)`, `void appendLines(Tender, List<PrivateRequestLineInput>)` (package-private — для задачи 6);
    - `TenderLot updateLineField(Long id, Long lotId, PrivateRequestLineField, String value)`, `Tender deleteLine(Long id, Long lotId)`;
    - `TenderLot lineRegistry(Long id, Long lotId, LineRegistryAction, String regNumber)`, `Tender registryCheck(Long id)`, `List<RegistryCandidateResponse> lineCandidates(Long id, Long lotId)`;
    - `MAX_LINES = 500`;
    - `static PrivateRequestLineInput input(String name, String brand, Integer quantity, String unit, String spec, String productUrl)`.
  - **REST:** `POST /{id}/lines` `{lines, mode}` → вид; `PATCH /{id}/lines/{lotId}` `{field, value}` → позиция; `DELETE /{id}/lines/{lotId}` → вид; `POST /{id}/lines/{lotId}/registry` `{action, regNumber?}` → позиция; `POST /{id}/registry-check` → вид; `GET /{id}/lines/{lotId}/registry-candidates` → кандидаты.

- [ ] **Step 1: Написать падающие тесты**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestLinesTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LineRegistryAction;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestLineField;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PrivateRequestLinesTest {

    @Autowired PrivateRequestService service;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired EntityManager em;

    /** Уникальные токены — в реестре nirdb им ничто не созвучно, подсказка гарантированно наша. */
    private static final String VENDOR = "ZZPRLVENDOR";
    private static final String MODEL = "Аппарат ZZPRLMODEL QX7";

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-PRL-RU-1").name(MODEL)
                .producer(VENDOR + " Ltd").country("ЯПОНИЯ").unlimited(true).build());
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-PRL-RU-2").name("Другое изделие ZZPRLOTHER")
                .producer("ZZPRLOTHERVENDOR").country("КИТАЙ").unlimited(true).build());
        registryRepository.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static List<TenderLot> sorted(Tender t) {
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
        return lots;
    }

    private void requestPrices(Tender t, TenderLot lot) {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Позиции поставщик " + System.nanoTime()).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1).build());
        em.flush();
    }

    @Test
    void appendNumbersSavesFieldsAndSuggestsRegistration() {
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(
                input(MODEL, VENDOR, 2, "упак", "Класс IIa", "https://westmed.kz/product/zz"),
                input("ZZНетТакого изделия QQQ", "ZZНетБренда", null, null, null, null)), LinesMode.APPEND);

        List<TenderLot> lots = sorted(t);
        assertThat(lots).extracting(TenderLot::getLotNumber).containsExactly(1, 2);
        TenderLot a = lots.get(0);
        assertThat(a.getQuantity()).isEqualTo(2);
        assertThat(a.getUnit()).isEqualTo("упак");
        assertThat(a.getRequiredSpec()).isEqualTo("Класс IIa");
        assertThat(a.getProductUrl()).isEqualTo("https://westmed.kz/product/zz");
        assertThat(a.getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(a.getRegNumber()).isEqualTo("ZZ-PRL-RU-1");
        assertThat(a.getRegScore()).isNotNull();
        TenderLot b = lots.get(1);
        assertThat(b.getQuantity()).isEqualTo(1);
        assertThat(b.getRegStatus()).isEqualTo(LineRegStatus.NOT_FOUND);
        assertThat(b.getRegNumber()).isNull();
    }

    @Test
    void brandAloneIsNotASuggestion() {
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input("Совсем другое изделие ZZRAZNOE", VENDOR, 1, null, null, null)),
                LinesMode.APPEND);

        assertThat(t.getLots().get(0).getRegStatus()).isEqualTo(LineRegStatus.NOT_FOUND);
    }

    @Test
    void rfMarketLeavesRegistrationEmpty() {
        MarketContext.set(Market.RF);
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input(MODEL, VENDOR, 1, null, null, null)), LinesMode.APPEND);

        assertThat(t.getLots().get(0).getRegStatus()).isNull();
        assertThat(service.lineCandidates(t.getId(), t.getLots().get(0).getId())).isEmpty();
    }

    @Test
    void replaceKeepsRequestedLines() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Старая 1", null, 1, null, null, null),
                input("ZZ Старая 2", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot requested = sorted(t).get(1);
        requestPrices(t, requested);

        service.addLines(t.getId(), List.of(input("ZZ Новая", null, 3, null, null, null)), LinesMode.REPLACE);

        assertThat(sorted(t)).extracting(TenderLot::getEquipName).containsExactly("ZZ Старая 2", "ZZ Новая");
        assertThat(sorted(t)).extracting(TenderLot::getLotNumber).containsExactly(1, 2);
    }

    @Test
    void nameOrBrandChangeRecomputesSuggestion_sameValueKeepsConfirmation() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input(MODEL, VENDOR, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, null);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.CONFIRMED);

        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.NAME, MODEL);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.CONFIRMED);

        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.BRAND, "ZZДругойБренд");
        assertThat(lot.getRegStatus()).isNotEqualTo(LineRegStatus.CONFIRMED);

        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.QUANTITY, "7");
        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.UNIT, "компл");
        service.updateLineField(t.getId(), lot.getId(), PrivateRequestLineField.SPEC, "Две насадки");
        assertThat(lot.getQuantity()).isEqualTo(7);
        assertThat(lot.getUnit()).isEqualTo("компл");
        assertThat(lot.getRequiredSpec()).isEqualTo("Две насадки");
    }

    @Test
    void invalidLineValuesAre400() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Позиция", null, 1, null, null, null)), LinesMode.APPEND);
        Long lotId = t.getLots().get(0).getId();

        assertThatThrownBy(() -> service.updateLineField(t.getId(), lotId, PrivateRequestLineField.QUANTITY, "0"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLineField(t.getId(), lotId, PrivateRequestLineField.QUANTITY, "два"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLineField(t.getId(), lotId, PrivateRequestLineField.NAME, " "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.addLines(t.getId(),
                List.of(input("ZZ", null, 1, null, null, "javascript:alert(1)")), LinesMode.APPEND))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("http");
        assertThatThrownBy(() -> service.addLines(t.getId(), List.of(), LinesMode.APPEND))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void longNameIsTruncatedTo255() {
        Tender t = service.createEmpty();

        service.addLines(t.getId(), List.of(input("Я".repeat(400), null, 1, null, null, null)), LinesMode.APPEND);

        assertThat(t.getLots().get(0).getEquipName()).hasSize(255);
    }

    @Test
    void cannotExceedMaxLines() {
        MarketContext.set(Market.RF);   // на РФ подсказки РУ нет — 500 позиций без 500 запросов к реестру
        Tender t = service.createEmpty();
        List<PrivateRequestLineInput> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) many.add(input("ZZ Массовая " + i, null, 1, null, null, null));
        service.addLines(t.getId(), many, LinesMode.APPEND);
        service.addLines(t.getId(), many, LinesMode.APPEND);

        assertThatThrownBy(() -> service.addLines(t.getId(), List.of(input("ZZ Лишняя", null, 1, null, null, null)),
                LinesMode.APPEND)).isInstanceOf(BadRequestException.class).hasMessageContaining("500");
        assertThat(t.getLots()).hasSize(500);
    }

    @Test
    void deleteRenumbers_requestedLineIs400_foreignLotIs404() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ 1", null, 1, null, null, null), input("ZZ 2", null, 1, null, null, null),
                input("ZZ 3", null, 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = sorted(t);
        requestPrices(t, lots.get(2));

        service.deleteLine(t.getId(), lots.get(0).getId());

        assertThat(sorted(t)).extracting(TenderLot::getEquipName).containsExactly("ZZ 2", "ZZ 3");
        assertThat(sorted(t)).extracting(TenderLot::getLotNumber).containsExactly(1, 2);
        assertThatThrownBy(() -> service.deleteLine(t.getId(), lots.get(2).getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("запросы цен");

        Tender other = service.createEmpty();
        assertThatThrownBy(() -> service.deleteLine(other.getId(), lots.get(1).getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void registryActions() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input(MODEL, VENDOR, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);

        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, "ZZ-PRL-RU-2");
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.CONFIRMED);
        assertThat(lot.getRegNumber()).isEqualTo("ZZ-PRL-RU-2");
        assertThat(lot.getRegScore()).isNull();

        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.NOT_REQUIRED, null);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.NOT_REQUIRED);
        assertThat(lot.getRegNumber()).isNull();

        assertThatThrownBy(() -> service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, "ZZ-НЕТ-ТАКОГО"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не найдено");

        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.RESET, null);
        assertThat(lot.getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lot.getRegNumber()).isEqualTo("ZZ-PRL-RU-1");

        assertThat(service.lineCandidates(t.getId(), lot.getId())).extracting(c -> c.getRegNumber()).contains("ZZ-PRL-RU-1");
    }

    @Test
    void registryCheckFillsOnlyUnchecked() {
        Tender t = service.createEmpty();
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(1).equipName(MODEL).manufact(VENDOR).quantity(1).build());
        t.getLots().add(TenderLot.builder().tender(t).lotNumber(2).equipName("ZZ Ручная").quantity(1)
                .regStatus(LineRegStatus.NOT_REQUIRED).build());
        em.flush();

        service.registryCheck(t.getId());

        List<TenderLot> lots = sorted(t);
        assertThat(lots.get(0).getRegStatus()).isEqualTo(LineRegStatus.SUGGESTED);
        assertThat(lots.get(1).getRegStatus()).isEqualTo(LineRegStatus.NOT_REQUIRED);
    }
}
```

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRegistryCalibrationTest.java` — отчёт для калибровки порога (запускается вручную с `REG_CALIBRATION=1`, в обычном прогоне пропускается):

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.privaterequest.PrivateRequestRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Калибровка PrivateRequestRegistry.SUGGEST_MIN на живом реестре nirdb: печатает топ-1 для типичных позиций частных
 * заявок (товары westmed.kz и то, что называют клиники). Запуск:
 * REG_CALIBRATION=1 ./gradlew test --tests '*PrivateRequestRegistryCalibrationTest' -i | grep CALIB
 */
@SpringBootTest
@Transactional
class PrivateRequestRegistryCalibrationTest {

    @Autowired RegistryMatchService matcher;

    private static final String[][] LINES = {
            {"Рециркулятор бактерицидный настенный РБм 3Х15", "Завод ЭМА"},
            {"Облучатель бактерицидный ОБПЕ-450", "Завод ЭМА"},
            {"Светильник медицинский ЭМАЛЕД 100П", "Завод ЭМА"},
            {"Дефибриллятор-монитор ДКИ-Н-11", "Аксион"},
            {"Электрокардиограф ЭК3ТЦ-3/6-04", "Аксион"},
            {"Аппарат ИВЛ А-ИВЛ-Э-03", "Аксион"},
            {"Холтеровский монитор ЭКГ КРН-01", "НПП Монитор"},
            {"Спирограф СМП-02-Р-Д", "НПП Монитор"},
            {"Стерилизатор озоновый Орион", "Орион-Си"},
            {"Электрокардиограф BeneHeart R12", "Mindray"},
            {"Монитор пациента iMEC10", "Mindray"},
            {"Тонометр OMRON M2", "OMRON"},
            {"Небулайзер OMRON CompAir", "OMRON"},
            {"Аппарат УЗИ Vivid T8", "GE"},
            {"Холодильник фармацевтический ХФ-400", "POZIS"},
            {"Криосауна CryoSpace", "КриоСпейс"},
            {"Кушетка медицинская", null},
            {"Стол упаковочный 1600", null},
            {"Ростомер", null},
            {"Термометр min/max", null},
    };

    @Test
    void printTopCandidates() {
        assumeTrue(System.getenv("REG_CALIBRATION") != null, "отчёт калибровки — только с REG_CALIBRATION=1");
        System.out.println("CALIB порог SUGGEST_MIN = " + PrivateRequestRegistry.SUGGEST_MIN);
        for (String[] l : LINES) {
            List<RegistryCandidateResponse> top = matcher.findCandidates(l[0], l[1], 1);
            if (top.isEmpty()) {
                System.out.printf("CALIB %-50s | %-12s | — нет кандидатов%n", l[0], l[1]);
                continue;
            }
            RegistryCandidateResponse c = top.get(0);
            System.out.printf("CALIB %-50s | %-12s | %.3f | %s | %s | %s%n", l[0], l[1], c.getScore(), c.getRegNumber(),
                    c.getName().length() > 80 ? c.getName().substring(0, 80) + "…" : c.getName(), c.getProducer());
        }
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestLinesTest'`
Expected: FAIL — нет `LinesMode`, `PrivateRequestService.input`, `addLines`.

- [ ] **Step 3: DTO позиций**

`src/main/java/com/vladoose/nir/dto/request/LinesMode.java`:

```java
package com.vladoose.nir.dto.request;

/** APPEND — добавить в конец; REPLACE — заменить позиции, кроме уже запрошенных у поставщиков. */
public enum LinesMode { APPEND, REPLACE }
```

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineInput.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Новая позиция (сайт, свой текст, список, Excel). Наименование длиннее 255 сервис обрезает (колонка equip_name). */
@Data
public class PrivateRequestLineInput {
    @Size(max = 2000, message = "Наименование слишком длинное")
    private String name;
    @Size(max = 255, message = "Бренд — не больше 255 символов")
    private String brand;
    @Min(value = 1, message = "Количество — от 1")
    @Max(value = 1_000_000, message = "Количество — до 1 000 000")
    private Integer quantity;
    @Size(max = 30, message = "Ед. изм. — не больше 30 символов")
    private String unit;
    @Size(max = 10000, message = "Характеристики — не больше 10 000 символов")
    private String spec;
    @Size(max = 500, message = "Ссылка — не больше 500 символов")
    private String productUrl;
}
```

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestLinesAdd.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PrivateRequestLinesAdd {
    @NotEmpty(message = "Нет позиций для добавления")
    @Size(max = 200, message = "За раз — не больше 200 позиций")
    @Valid
    private List<PrivateRequestLineInput> lines;
    /** пусто — APPEND */
    private LinesMode mode;
}
```

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineField.java`:

```java
package com.vladoose.nir.dto.request;

/** Поле позиции, которое сохраняется по выходу из поля. */
public enum PrivateRequestLineField { NAME, BRAND, QUANTITY, UNIT, SPEC }
```

`src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineFieldUpdate.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PrivateRequestLineFieldUpdate {
    @NotNull(message = "Не указано поле позиции")
    private PrivateRequestLineField field;
    @Size(max = 10000, message = "Слишком длинное значение")
    private String value;
}
```

`src/main/java/com/vladoose/nir/dto/request/LineRegistryAction.java`:

```java
package com.vladoose.nir.dto.request;

/** Действие с РУ позиции: подтвердить (подсказку или выбранную запись), «не подлежит», пересчитать подсказку. */
public enum LineRegistryAction { CONFIRM, NOT_REQUIRED, RESET }
```

`src/main/java/com/vladoose/nir/dto/request/LineRegistryActionRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LineRegistryActionRequest {
    @NotNull(message = "Не указано действие")
    private LineRegistryAction action;
    /** у CONFIRM — выбранная запись реестра; пусто — подтвердить текущую подсказку */
    @Size(max = 100)
    private String regNumber;
}
```

- [ ] **Step 4: `PrivateRequestRegistry`**

```java
package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.LineRegStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.service.RegistryMatchService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * РУ позиции частной заявки (спека §5.3): подсказка считается сама (только KZ — на РФ реестра нет), подтверждает человек.
 */
@Component
public class PrivateRequestRegistry {

    /**
     * Порог подсказки для пары «наименование + бренд». Скор findCandidates = 0.6·производитель + 0.4·наименование:
     * точное совпадение бренда даёт 0.6 само по себе, поэтому порог выше 0.6 требует ещё и похожести наименования —
     * иначе «РУ?» стояло бы у любой позиции известного бренда (тест brandAloneIsNotASuggestion). Позиция без бренда
     * набирает максимум 0.4 и подсказки не получает — её РУ определится, когда выбрана модель.
     * Калибровка — PrivateRequestRegistryCalibrationTest (REG_CALIBRATION=1); замер записать сюда.
     */
    public static final double SUGGEST_MIN = 0.75;

    public static final int CANDIDATES_LIMIT = 5;

    private final RegistryMatchService matcher;
    private final MedRegistryRepository registryRepository;

    public PrivateRequestRegistry(RegistryMatchService matcher, MedRegistryRepository registryRepository) {
        this.matcher = matcher;
        this.registryRepository = registryRepository;
    }

    /** Подсказка по наименованию и бренду; РФ — статус пустой. Сбрасывает подтверждение и «не подлежит». */
    public void suggest(TenderLot lot) {
        if (lot.getTender().getMarket() != Market.KZ) {
            lot.setRegStatus(null);
            lot.setRegNumber(null);
            lot.setRegScore(null);
            return;
        }
        List<RegistryCandidateResponse> top = matcher.findCandidates(lot.getEquipName(), lot.getManufact(), 1);
        RegistryCandidateResponse best = top.isEmpty() ? null : top.get(0);
        if (best != null && best.getScore() != null && best.getScore() >= SUGGEST_MIN) {
            lot.setRegStatus(LineRegStatus.SUGGESTED);
            lot.setRegNumber(best.getRegNumber());
            lot.setRegScore(BigDecimal.valueOf(best.getScore()).setScale(3, RoundingMode.HALF_UP));
        } else {
            lot.setRegStatus(LineRegStatus.NOT_FOUND);
            lot.setRegNumber(null);
            lot.setRegScore(null);
        }
    }

    /** Подтвердить: без номера — текущую подсказку; с номером — выбранную запись (она должна быть в реестре). */
    public void confirm(TenderLot lot, String regNumber) {
        String number = regNumber == null || regNumber.isBlank() ? null : regNumber.trim();
        if (number == null) {
            if (lot.getRegStatus() != LineRegStatus.SUGGESTED || lot.getRegNumber() == null) {
                throw new BadRequestException("Подтверждать нечего: у позиции нет подсказки РУ");
            }
            number = lot.getRegNumber();
        }
        final String wanted = number;
        MedRegistry rec = registryRepository.findByRegNumber(wanted)
                .orElseThrow(() -> new BadRequestException("РУ не найдено в реестре: " + wanted));
        boolean sameAsSuggestion = rec.getRegNumber().equals(lot.getRegNumber());
        lot.setRegStatus(LineRegStatus.CONFIRMED);
        lot.setRegNumber(rec.getRegNumber());
        if (!sameAsSuggestion) lot.setRegScore(null);
    }

    public void notRequired(TenderLot lot) {
        lot.setRegStatus(LineRegStatus.NOT_REQUIRED);
        lot.setRegNumber(null);
        lot.setRegScore(null);
    }

    /** Варианты для окошка «Не то — найти в реестре». РФ — пусто. */
    public List<RegistryCandidateResponse> candidates(TenderLot lot) {
        if (lot.getTender().getMarket() != Market.KZ) return List.of();
        return matcher.findCandidates(lot.getEquipName(), lot.getManufact(), CANDIDATES_LIMIT);
    }
}
```

- [ ] **Step 5: Позиции и РУ в `PrivateRequestService`**

- добавить зависимость `PrivateRequestRegistry registry` (поле, параметр конструктора, присваивание; импорт `com.vladoose.nir.service.privaterequest.PrivateRequestRegistry`);
- импорты: `com.vladoose.nir.dto.request.*` (вместо поштучных), `java.util.Objects`, `java.util.Comparator`;
- удалить метод `update(Long, PrivateRequestUpdate)`;
- заменить `createFromLines` и добавить методы:

```java
    public static final int MAX_LINES = 500;

    /** Заявка со строками (импорт Excel, превращение обращения из старого диалога). Клиент необязателен. */
    @Transactional
    public Tender createFromLines(PrivateRequestCreate dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка");
        }
        Tender t = createEmpty();
        if (dto.getClientFacilityId() != null) t.setFacility(facility(dto.getClientFacilityId()));
        t.setDescription(dto.getNote());
        appendLines(t, dto.getLines().stream()
                .map(l -> input(l.getName(), l.getManufact(), l.getQuantity(), null, null, null)).toList());
        tenderRepository.flush();
        return t;
    }

    /** Добавить позиции (сайт, свой текст, список, Excel). REPLACE удаляет только позиции без запросов цен. */
    @Transactional
    public Tender addLines(Long id, List<PrivateRequestLineInput> inputs, LinesMode mode) {
        Tender t = findById(id);
        if (inputs == null || inputs.isEmpty()) throw new BadRequestException("Нет позиций для добавления");
        if (mode == LinesMode.REPLACE) {
            Set<Long> requested = requestedLotIds(t.getId());
            t.getLots().removeIf(l -> !requested.contains(l.getId()));
        }
        if (t.getLots().size() + inputs.size() > MAX_LINES) {
            throw new BadRequestException("В заявке не больше " + MAX_LINES + " позиций");
        }
        appendLines(t, inputs);
        tenderRepository.flush();
        return t;
    }

    /** Общий путь добавления: номера в конец, подсказка РУ, сплошная нумерация. */
    void appendLines(Tender t, List<PrivateRequestLineInput> inputs) {
        int no = t.getLots().stream().map(TenderLot::getLotNumber).filter(Objects::nonNull)
                .max(Integer::compare).orElse(0);
        for (PrivateRequestLineInput in : inputs) {
            TenderLot lot = TenderLot.builder().tender(t).lotNumber(++no).build();
            applyInput(lot, in);
            t.getLots().add(lot);
            registry.suggest(lot);
        }
        renumber(t);
    }

    /** Одно поле позиции. Смена наименования или бренда пересчитывает подсказку РУ (подтверждение сбрасывается). */
    @Transactional
    public TenderLot updateLineField(Long id, Long lotId, PrivateRequestLineField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле позиции");
        Tender t = findById(id);
        TenderLot lot = lotOf(t, lotId);
        String v = blankToNull(value);
        switch (field) {
            case NAME -> {
                String name = name(v);
                if (!name.equals(lot.getEquipName())) {
                    lot.setEquipName(name);
                    registry.suggest(lot);
                }
            }
            case BRAND -> {
                String brand = limit(v, 255, "Бренд");
                if (!Objects.equals(brand, lot.getManufact())) {
                    lot.setManufact(brand);
                    registry.suggest(lot);
                }
            }
            case QUANTITY -> lot.setQuantity(quantity(v));
            case UNIT -> lot.setUnit(limit(v, 30, "Ед. изм."));
            case SPEC -> lot.setRequiredSpec(limit(v, 10000, "Характеристики"));
        }
        tenderRepository.flush();
        return lot;
    }

    /** Удалить позицию (через коллекцию — orphanRemoval). С запросами цен — нельзя (внешний ключ). */
    @Transactional
    public Tender deleteLine(Long id, Long lotId) {
        Tender t = findById(id);
        TenderLot lot = lotOf(t, lotId);
        if (priceRequestItemRepository.existsByTenderLotId(lotId)) {
            throw new BadRequestException("Удалить нельзя: по позиции есть запросы цен");
        }
        t.getLots().remove(lot);
        renumber(t);
        tenderRepository.flush();
        return t;
    }

    @Transactional
    public TenderLot lineRegistry(Long id, Long lotId, LineRegistryAction action, String regNumber) {
        if (action == null) throw new BadRequestException("Не указано действие");
        Tender t = findById(id);
        TenderLot lot = lotOf(t, lotId);
        switch (action) {
            case CONFIRM -> registry.confirm(lot, regNumber);
            case NOT_REQUIRED -> registry.notRequired(lot);
            case RESET -> registry.suggest(lot);
        }
        tenderRepository.flush();
        return lot;
    }

    /** Подсказки позициям без статуса (заявки до V26, позиции из старых путей) — один вызов при открытии страницы. */
    @Transactional
    public Tender registryCheck(Long id) {
        Tender t = findById(id);
        for (TenderLot lot : t.getLots()) {
            if (lot.getRegStatus() == null) registry.suggest(lot);
        }
        tenderRepository.flush();
        return t;
    }

    @Transactional(readOnly = true)
    public List<RegistryCandidateResponse> lineCandidates(Long id, Long lotId) {
        return registry.candidates(lotOf(findById(id), lotId));
    }

    /** Новая позиция одной строкой — для импорта, обращений и тестов. */
    public static PrivateRequestLineInput input(String name, String brand, Integer quantity, String unit, String spec,
                                                String productUrl) {
        PrivateRequestLineInput in = new PrivateRequestLineInput();
        in.setName(name);
        in.setBrand(brand);
        in.setQuantity(quantity);
        in.setUnit(unit);
        in.setSpec(spec);
        in.setProductUrl(productUrl);
        return in;
    }

    private Set<Long> requestedLotIds(Long tenderId) {
        Set<Long> ids = new HashSet<>();
        for (PriceRequestItem i : priceRequestItemRepository.findByTenderId(tenderId)) ids.add(i.getTenderLot().getId());
        return ids;
    }

    private static TenderLot lotOf(Tender t, Long lotId) {
        return t.getLots().stream().filter(l -> lotId != null && lotId.equals(l.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Позиция не найдена: id=" + lotId));
    }

    /** Сплошная нумерация 1..n в прежнем порядке; у новых позиций id ещё нет — они в конце. */
    private static void renumber(Tender t) {
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
                .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId()));
        int no = 1;
        for (TenderLot l : lots) l.setLotNumber(no++);
    }

    private static void applyInput(TenderLot lot, PrivateRequestLineInput in) {
        lot.setEquipName(name(in.getName()));
        lot.setManufact(trunc(blankToNull(in.getBrand()), 255));
        // пути без @Valid (импорт Excel, обращение) могут прислать 0 или минус — это «1», а не ошибка всего файла
        Integer q = in.getQuantity();
        lot.setQuantity(q == null || q < 1 ? 1 : Math.min(q, 1_000_000));
        lot.setUnit(trunc(blankToNull(in.getUnit()), 30));
        lot.setRequiredSpec(blankToNull(in.getSpec()));
        lot.setProductUrl(url(in.getProductUrl()));
    }

    private static String name(String raw) {
        String v = blankToNull(raw);
        if (v == null) throw new BadRequestException("Наименование позиции пустое");
        return trunc(v.replaceAll("\\s+", " "), 255);
    }

    private static String url(String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        if (!(v.startsWith("https://") || v.startsWith("http://")) || v.length() > 500) {
            throw new BadRequestException("Ссылка на товар должна начинаться с http:// или https://");
        }
        return v;
    }

    private static int quantity(String v) {
        if (v == null) throw new BadRequestException("Количество обязательно");
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1 || n > 1_000_000) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new BadRequestException("Количество — целое число от 1 до 1 000 000");
        }
    }
```

Метод `linesWithRegistration` и его импорты (`PrivateRequestLineResponse`, `RegistryCandidateResponse`) остаются до задачи 7 (`RegistryCandidateResponse` теперь нужен и `lineCandidates`).

- [ ] **Step 6: Контроллер — эндпоинты позиций**

В `PrivateRequestController.java`:
- удалить метод `update(...)` (`PUT /{id}`);
- импорт `com.vladoose.nir.entity.TenderLot`;
- добавить:

```java
    @PostMapping("/{id}/lines")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse addLines(@PathVariable Long id, @Valid @RequestBody PrivateRequestLinesAdd req) {
        return viewBuilder.build(service.addLines(id, req.getLines(), req.getMode() == null ? LinesMode.APPEND : req.getMode()));
    }

    @PatchMapping("/{id}/lines/{lotId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestLineView updateLine(@PathVariable Long id, @PathVariable Long lotId,
                                             @Valid @RequestBody PrivateRequestLineFieldUpdate req) {
        TenderLot lot = service.updateLineField(id, lotId, req.getField(), req.getValue());
        return viewBuilder.line(lot);
    }

    @DeleteMapping("/{id}/lines/{lotId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deleteLine(@PathVariable Long id, @PathVariable Long lotId) {
        return viewBuilder.build(service.deleteLine(id, lotId));
    }

    @PostMapping("/{id}/lines/{lotId}/registry")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestLineView lineRegistry(@PathVariable Long id, @PathVariable Long lotId,
                                               @Valid @RequestBody LineRegistryActionRequest req) {
        return viewBuilder.line(service.lineRegistry(id, lotId, req.getAction(), req.getRegNumber()));
    }

    @GetMapping("/{id}/lines/{lotId}/registry-candidates")
    public List<RegistryCandidateResponse> lineCandidates(@PathVariable Long id, @PathVariable Long lotId) {
        return service.lineCandidates(id, lotId);
    }

    @PostMapping("/{id}/registry-check")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse registryCheck(@PathVariable Long id) {
        return viewBuilder.build(service.registryCheck(id));
    }
```

- [ ] **Step 7: Удалить старое**

```bash
git rm src/main/java/com/vladoose/nir/dto/request/PrivateRequestUpdate.java src/test/java/com/vladoose/nir/imports/PrivateRequestUpdateTest.java
```

Правку, добавление и удаление строк покрывает `PrivateRequestLinesTest`.

- [ ] **Step 8: Запустить — зелёные**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.lead.*' --tests 'com.vladoose.nir.imports.*' --tests 'com.vladoose.nir.sourcing.*'`
Expected: PASS. `PrivateRequestRegistryCalibrationTest` — SKIPPED (без `REG_CALIBRATION`).

- [ ] **Step 9: Калибровка порога на живом реестре**

Run (sandbox off): `REG_CALIBRATION=1 ./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestRegistryCalibrationTest' -i | grep CALIB`
Для каждой строки глазами решить, верен ли топ-1: та же модель того же производителя — верно; другой аппарат того же бренда — неверно.
- Порог ставить МЕЖДУ наибольшим скором неверных и наименьшим скором верных.
- Если 0.75 уже их разделяет — оставить.
- Если не разделяет — сдвинуть `SUGGEST_MIN` в середину зазора и перезапустить `PrivateRequestLinesTest` (тесты строят записи со скором ≈ 1.0 и ≈ 0.6 + малое, поэтому порог в коридоре 0.65–0.95 их не ломает).

Вписать в javadoc `SUGGEST_MIN` замер: дату (2026-10-12), сколько из 20 строк дали верный топ-1, наименьший верный и наибольший неверный скор. Если зазора нет вовсе — оставить 0.75, в javadoc записать пересечение и примеры: подсказка остаётся подсказкой, решает человек.

- [ ] **Step 10: Мутация сброса подтверждения**

Копия `PrivateRequestService.java` в `/tmp`. В `updateLineField` в ветке `BRAND` убрать вызов `registry.suggest(lot);`.
Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestLinesTest'`
Expected: FAIL `nameOrBrandChangeRecomputesSuggestion_sameValueKeepsConfirmation`. Вернуть копией, `diff -q`.

Вторая мутация — порог: в `PrivateRequestRegistry` поставить `SUGGEST_MIN = 0.5`.
Expected: FAIL `brandAloneIsNotASuggestion`. Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 11: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRegistry.java src/main/java/com/vladoose/nir/dto/request/LinesMode.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineInput.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestLinesAdd.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineField.java src/main/java/com/vladoose/nir/dto/request/PrivateRequestLineFieldUpdate.java src/main/java/com/vladoose/nir/dto/request/LineRegistryAction.java src/main/java/com/vladoose/nir/dto/request/LineRegistryActionRequest.java src/main/java/com/vladoose/nir/service/PrivateRequestService.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestLinesTest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRegistryCalibrationTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): позиции по одной, подсказка РУ с подтверждением, «заменить» не трогает запрошенные

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: «Вставить списком» и поиск по каталогу westmed.kz

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/PastedLinesParser.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/PastedLinesRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ParsedLineResponse.java`, `SiteProductResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/SiteProductService.java`, `src/main/java/com/vladoose/nir/controller/SiteProductController.java`
- Modify: `src/main/java/com/vladoose/nir/integration/westmed/WestmedClient.java`, `WestmedHttpClient.java`, `dto/WestmedProduct.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (эндпоинт разбора)
- Test: `src/test/java/com/vladoose/nir/util/PastedLinesParserTest.java`, `src/test/java/com/vladoose/nir/service/SiteProductServiceTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.findById` (задача 2); `FakeWestmedClient` из тестов (`integration/westmed`).
- Produces:
  - **`PastedLinesParser`:** `static List<ParsedLine> parse(String text)`, `record ParsedLine(String name, int quantity, String unit)`, `MAX_LINES = 200`.
  - **`SiteProductService`:** `List<SiteProductResponse> search(String q)`, `record SiteProductResponse(String name, String brand, String sku, String url)`.
  - **`WestmedClient`:** метод `default List<WestmedProduct> searchProducts(String text, int size, Duration deadline)`.
  - **`WestmedProduct`:** теперь `(name, slug, brandName, sku)`, прежний конструктор на три аргумента сохранён.
  - **REST:** `POST /api/private-requests/{id}/lines/parse` `{text}` → `List<ParsedLineResponse(name, quantity, unit)>`; `GET /api/site-products?q=` → `List<SiteProductResponse>` (не KZ — пусто; сайт не ответил — 502).

- [ ] **Step 1: Падающий тест разбора списка**

`src/test/java/com/vladoose/nir/util/PastedLinesParserTest.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.util.PastedLinesParser.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PastedLinesParserTest {

    private static ParsedLine one(String line) {
        List<ParsedLine> r = PastedLinesParser.parse(line);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void quantityWithUnit() {
        assertThat(one("Тонометр OMRON M2 — 5 шт")).isEqualTo(new ParsedLine("Тонометр OMRON M2", 5, "шт"));
        assertThat(one("Рециркулятор РБм 3Х15 - 4 шт.")).isEqualTo(new ParsedLine("Рециркулятор РБм 3Х15", 4, "шт"));
        assertThat(one("Перчатки нитриловые 10 упак")).isEqualTo(new ParsedLine("Перчатки нитриловые", 10, "упак"));
        assertThat(one("Набор акушерский: 2 комплекта")).isEqualTo(new ParsedLine("Набор акушерский", 2, "компл"));
        assertThat(one("Шприц 5 мл — 100 шт")).isEqualTo(new ParsedLine("Шприц 5 мл", 100, "шт"));
    }

    @Test
    void quantityByTimesParenthesesAndDash() {
        assertThat(one("Кушетка медицинская x2")).isEqualTo(new ParsedLine("Кушетка медицинская", 2, null));
        assertThat(one("Термометр min/max ×10")).isEqualTo(new ParsedLine("Термометр min/max", 10, null));
        assertThat(one("Ширма 3-секционная х3")).isEqualTo(new ParsedLine("Ширма 3-секционная", 3, null));
        assertThat(one("Небулайзер (2)")).isEqualTo(new ParsedLine("Небулайзер", 2, null));
        assertThat(one("Небулайзер (2 шт)")).isEqualTo(new ParsedLine("Небулайзер", 2, "шт"));
        assertThat(one("Ростомер — 1")).isEqualTo(new ParsedLine("Ростомер", 1, null));
    }

    @Test
    void modelNumbersAreNotQuantities() {
        assertThat(one("Кровать функциональная КФ-2")).isEqualTo(new ParsedLine("Кровать функциональная КФ-2", 1, null));
        assertThat(one("Насадка №2")).isEqualTo(new ParsedLine("Насадка №2", 1, null));
        assertThat(one("Стол упаковочный 1600")).isEqualTo(new ParsedLine("Стол упаковочный 1600", 1, null));
        assertThat(one("Монитор SX2")).isEqualTo(new ParsedLine("Монитор SX2", 1, null));
        assertThat(one("Бинт 7м x 14см")).isEqualTo(new ParsedLine("Бинт 7м x 14см", 1, null));
        assertThat(one("Шприц 5 мл")).isEqualTo(new ParsedLine("Шприц 5 мл", 1, null));
    }

    @Test
    void markersBlankLinesAndNumbersOnlyAreDropped() {
        List<ParsedLine> r = PastedLinesParser.parse("""
                1. Тонометр — 2 шт
                2) Кушетка x1

                • Ширма
                - Стойка инфузионная
                15
                """);
        assertThat(r).extracting(ParsedLine::name).containsExactly("Тонометр", "Кушетка", "Ширма", "Стойка инфузионная");
        assertThat(r).extracting(ParsedLine::quantity).containsExactly(2, 1, 1, 1);
    }

    @Test
    void nbspZeroAndHugeQuantity() {
        assertThat(one("Тонометр — 3 шт")).isEqualTo(new ParsedLine("Тонометр", 3, "шт"));
        assertThat(one("Тонометр — 0 шт").quantity()).isEqualTo(1);
        assertThat(one("Тонометр x999999").quantity()).isEqualTo(999_999);
    }

    @Test
    void capsAt200LinesAndTruncatesLongNames() {
        String text = "Позиция\n".repeat(250);
        assertThat(PastedLinesParser.parse(text)).hasSize(PastedLinesParser.MAX_LINES);
        assertThat(one("Я".repeat(400)).name()).hasSize(255);
        assertThat(PastedLinesParser.parse(null)).isEmpty();
        assertThat(PastedLinesParser.parse("   \n  ")).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.util.PastedLinesParserTest'`
Expected: FAIL — нет `PastedLinesParser`.

- [ ] **Step 3: Реализация разбора**

`src/main/java/com/vladoose/nir/util/PastedLinesParser.java`:

```java
package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Вставить списком» (спека 2026-10-12 §4): текст из WhatsApp или письма → позиции. Строка — позиция; маркеры списка
 * в начале убираются. Количество распознаётся только в однозначных формах в конце строки — с единицей, со знаком
 * умножения после пробела, в скобках, после тире с пробелами. Поэтому модель «КФ-2», «SX2» или «Насадка №2» количеством
 * не считается. Бренд не угадывается.
 */
public final class PastedLinesParser {

    public record ParsedLine(String name, int quantity, String unit) {}

    public static final int MAX_LINES = 200;
    private static final int MAX_NAME = 255;
    private static final int MAX_QTY = 1_000_000;

    private static final String UNIT =
            "(шт|штук[аи]?|ед|компл|комплект(?:а|ов)?|набор(?:а|ов)?|упак|уп|фл|флакон(?:а|ов)?|пар[аы]?|кг|л|м)";
    private static final Pattern MARKER = Pattern.compile("^\\s*(?:\\d{1,3}\\s*[.)]|[-–—•*·])\\s+");
    /** «… 2 шт», «… — 2 шт.», «…: 3 компл», «…, 4 упак» */
    private static final Pattern QTY_UNIT =
            Pattern.compile("(?iu)(?:\\s*[-–—:,]\\s*|\\s+)(\\d{1,7})\\s*" + UNIT + "\\.?\\s*$");
    /** «… (2)», «… (2 шт)» */
    private static final Pattern QTY_PAREN = Pattern.compile("(?iu)\\s*\\((\\d{1,7})(?:\\s*" + UNIT + "\\.?)?\\)\\s*$");
    /** «… x2», «… × 3», «… х2» (кириллическая «х») — только после пробела: «SX2» — модель */
    private static final Pattern QTY_TIMES = Pattern.compile("(?iu)\\s+[xх×]\\s*(\\d{1,7})\\s*$");
    /** «… — 2», «… - 2» — тире с пробелами с обеих сторон («КФ-2» — модель) */
    private static final Pattern QTY_DASH = Pattern.compile("(?u)\\s+[-–—]\\s+(\\d{1,7})\\s*$");
    private static final Pattern TRAILING_SEP = Pattern.compile("[\\s\\-–—:,;.]+$");

    private PastedLinesParser() {}

    public static List<ParsedLine> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<ParsedLine> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (out.size() >= MAX_LINES) break;
            ParsedLine line = parseLine(raw);
            if (line != null) out.add(line);
        }
        return out;
    }

    static ParsedLine parseLine(String raw) {
        String s = raw == null ? "" : raw.replace(' ', ' ').strip();
        s = MARKER.matcher(s).replaceFirst("");
        if (s.isBlank()) return null;
        int qty = 1;
        String unit = null;
        Matcher m;
        if ((m = QTY_UNIT.matcher(s)).find()) {
            qty = quantity(m.group(1));
            unit = normUnit(m.group(2));
            s = s.substring(0, m.start());
        } else if ((m = QTY_PAREN.matcher(s)).find()) {
            qty = quantity(m.group(1));
            unit = m.group(2) == null ? null : normUnit(m.group(2));
            s = s.substring(0, m.start());
        } else if ((m = QTY_TIMES.matcher(s)).find()) {
            qty = quantity(m.group(1));
            s = s.substring(0, m.start());
        } else if ((m = QTY_DASH.matcher(s)).find()) {
            qty = quantity(m.group(1));
            s = s.substring(0, m.start());
        }
        String name = TRAILING_SEP.matcher(s).replaceAll("").replaceAll("\\s+", " ").strip();
        if (name.isEmpty() || name.chars().noneMatch(Character::isLetter)) return null;
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        return new ParsedLine(name, qty, unit);
    }

    private static int quantity(String digits) {
        long n = Long.parseLong(digits);
        return (int) Math.max(1, Math.min(n, MAX_QTY));
    }

    private static String normUnit(String raw) {
        String u = raw.toLowerCase(Locale.ROOT);
        if (u.startsWith("шт")) return "шт";
        if (u.startsWith("компл")) return "компл";
        if (u.startsWith("набор")) return "набор";
        if (u.startsWith("уп")) return "упак";
        if (u.startsWith("фл")) return "фл";
        if (u.startsWith("пар")) return "пара";
        return u;
    }
}
```

- [ ] **Step 4: Тест разбора — зелёный**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.util.PastedLinesParserTest'`
Expected: PASS (6 тестов).

- [ ] **Step 5: Падающий тест поиска по сайту**

`src/test/java/com/vladoose/nir/service/SiteProductServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.SiteProductResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.integration.westmed.WestmedApiException;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Без Spring: фейк сайта из тестов интеграции, рынок — через MarketContext. */
class SiteProductServiceTest {

    private final FakeWestmedClient fake = new FakeWestmedClient();
    private final SiteProductService service = new SiteProductService(fake, "https://westmed.kz/");

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void kzSearchMapsProductsWithUrl() {
        MarketContext.set(Market.KZ);
        fake.productsBySearch.put("облучатель", List.of(
                new WestmedProduct("Облучатель ОБН-150", "obn-150", "Азов", "AZ-150"),
                new WestmedProduct("Облучатель без ссылки", null, " ", null),
                new WestmedProduct("  ", "empty", "X", null)));

        List<SiteProductResponse> r = service.search("  Облучатель ");

        assertThat(r).containsExactly(
                new SiteProductResponse("Облучатель ОБН-150", "Азов", "AZ-150", "https://westmed.kz/product/obn-150"),
                new SiteProductResponse("Облучатель без ссылки", null, null, null));
    }

    @Test
    void shortQueryOrRfMarketDoesNotCallSite() {
        MarketContext.set(Market.KZ);
        assertThat(service.search("о")).isEmpty();
        assertThat(service.search(null)).isEmpty();
        MarketContext.set(Market.RF);
        assertThat(service.search("облучатель")).isEmpty();
        assertThat(fake.searchCalls).isZero();
    }

    @Test
    void siteFailureIsUpstreamWithOwnText() {
        MarketContext.set(Market.KZ);
        fake.failSearchWith = new WestmedApiException(0, "сайт не ответил за 5 с");

        assertThatThrownBy(() -> service.search("облучатель"))
                .isInstanceOf(UpstreamException.class)
                .hasMessage("Сайт westmed.kz не отвечает — добавьте позицию своим текстом");
    }

    @Test
    void longQueryIsCutTo100() {
        MarketContext.set(Market.KZ);
        String longQ = "а".repeat(150);
        fake.productsBySearch.put("а".repeat(100), List.of(new WestmedProduct("Найдено", "x", null, null)));

        assertThat(service.search(longQ)).extracting(SiteProductResponse::name).containsExactly("Найдено");
    }
}
```

- [ ] **Step 6: Запустить — не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.SiteProductServiceTest'`
Expected: FAIL — нет `SiteProductService`, конструктора `WestmedProduct` на 4 аргумента.

- [ ] **Step 7: Клиент сайта — артикул и короткий дедлайн поиска**

`src/main/java/com/vladoose/nir/integration/westmed/dto/WestmedProduct.java`:

```java
package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Элемент публичного каталога сайта: бренд и slug для ссылки, артикул — для выпадающего списка частной заявки. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedProduct(String name, String slug, String brandName, String sku) {

    public WestmedProduct(String name, String slug, String brandName) {
        this(name, slug, brandName, null);
    }
}
```

В `WestmedClient.java` (импорт `java.time.Duration`) после `searchProducts(String text, int size);`:

```java
    /** Тот же поиск с собственным дедлайном — для выпадающего списка частной заявки (пользователь ждёт ответа). */
    default List<WestmedProduct> searchProducts(String text, int size, Duration deadline) {
        return searchProducts(text, size);
    }
```

В `WestmedHttpClient.java`:
- метод `searchProducts(String text, int size)` заменить на:

```java
    @Override
    public List<WestmedProduct> searchProducts(String text, int size) {
        return searchProducts(text, size, deadline);
    }

    @Override
    public List<WestmedProduct> searchProducts(String text, int size, Duration searchDeadline) {
        String path = "/api/v1/products?search=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&size=" + size;
        HttpResponse<String> r = send("GET", path, null, null, searchDeadline);
        if (r.statusCode() != 200) {
            throw new WestmedApiException(r.statusCode(), "поиск товара на сайте: HTTP " + r.statusCode());
        }
        return parse(r.body(), PRODUCT_PAGE).contentOrEmpty();
    }
```

- приватный `send(String method, String path, String body, String bearer)` разделить на два: прежняя сигнатура делегирует

```java
    private HttpResponse<String> send(String method, String path, String body, String bearer) {
        return send(method, path, body, bearer, deadline);
    }
```

а тело прежнего метода переезжает в `private HttpResponse<String> send(String method, String path, String body, String bearer, Duration limit)`. Внутри все обращения к полю `deadline` заменить на `limit`: в `.timeout(deadline)`, в `f.get(deadline.toMillis(), …)` и в тексте `"сайт не ответил за " + deadline.toSeconds() + " с"`.

- [ ] **Step 8: Сервис и контроллер поиска**

`src/main/java/com/vladoose/nir/dto/response/SiteProductResponse.java`:

```java
package com.vladoose.nir.dto.response;

/** Товар westmed.kz в выпадающем списке «Найти на westmed.kz или вписать своё». url — пусто, если у товара нет slug. */
public record SiteProductResponse(String name, String brand, String sku, String url) {}
```

`src/main/java/com/vladoose/nir/service/SiteProductService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.SiteProductResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.WestmedClient;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Поиск по каталогу westmed.kz для позиций частной заявки (спека §4): живой публичный поиск сайта, каталог в АИС не
 * копируется. Только KZ — у Регион-Мед своего сайта нет. Карточку товара /products/{slug} не зовём: она накручивает
 * просмотры (CLAUDE.md §8).
 */
@Service
public class SiteProductService {

    static final int MIN_QUERY = 2;
    static final int MAX_QUERY = 100;
    static final int LIMIT = 10;
    static final Duration DEADLINE = Duration.ofSeconds(5);

    private final WestmedClient client;
    private final String siteUrl;

    public SiteProductService(WestmedClient client, @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.client = client;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public List<SiteProductResponse> search(String q) {
        if (MarketContext.get() != Market.KZ) return List.of();
        String text = q == null ? "" : q.strip().replaceAll("\\s+", " ");
        if (text.length() < MIN_QUERY) return List.of();
        if (text.length() > MAX_QUERY) text = text.substring(0, MAX_QUERY);
        List<WestmedProduct> found;
        try {
            found = client.searchProducts(text.toLowerCase(Locale.ROOT), LIMIT, DEADLINE);
        } catch (RuntimeException e) {
            throw new UpstreamException("Сайт westmed.kz не отвечает — добавьте позицию своим текстом", e);
        }
        return found.stream()
                .filter(p -> p.name() != null && !p.name().isBlank())
                .limit(LIMIT)
                .map(p -> new SiteProductResponse(p.name().strip(), blankToNull(p.brandName()), blankToNull(p.sku()),
                        blankToNull(p.slug()) == null ? null : siteUrl + "/product/" + p.slug().strip()))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
```

`src/main/java/com/vladoose/nir/controller/SiteProductController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.SiteProductResponse;
import com.vladoose.nir.service.SiteProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/site-products")
public class SiteProductController {

    private final SiteProductService service;

    public SiteProductController(SiteProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<SiteProductResponse> search(@RequestParam(defaultValue = "") String q) {
        return service.search(q);
    }
}
```

- [ ] **Step 9: Эндпоинт разбора списка**

`src/main/java/com/vladoose/nir/dto/request/PastedLinesRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PastedLinesRequest {
    @NotNull(message = "Нет текста")
    @Size(max = 100_000, message = "Слишком длинный текст — вставьте частями")
    private String text;
}
```

`src/main/java/com/vladoose/nir/dto/response/ParsedLineResponse.java`:

```java
package com.vladoose.nir.dto.response;

/** Строка предпросмотра «Вставить списком»: оператор правит её до добавления. */
public record ParsedLineResponse(String name, int quantity, String unit) {}
```

В `PrivateRequestController.java` (импорт `com.vladoose.nir.util.PastedLinesParser`):

```java
    @PostMapping("/{id}/lines/parse")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ParsedLineResponse> parseLines(@PathVariable Long id, @Valid @RequestBody PastedLinesRequest req) {
        service.findById(id);   // гард: заявка своего рынка
        return PastedLinesParser.parse(req.getText()).stream()
                .map(p -> new ParsedLineResponse(p.name(), p.quantity(), p.unit()))
                .toList();
    }
```

- [ ] **Step 10: Тесты — зелёные, старый поиск сайта не сломан**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.util.PastedLinesParserTest' --tests 'com.vladoose.nir.service.SiteProductServiceTest' --tests 'com.vladoose.nir.integration.westmed.*' --tests 'com.vladoose.nir.integration.whatsapp.WhatsappChatSyncTest'`
Expected: PASS.

- [ ] **Step 11: Коммит**

```bash
git add src/main/java/com/vladoose/nir/util/PastedLinesParser.java src/main/java/com/vladoose/nir/dto/request/PastedLinesRequest.java src/main/java/com/vladoose/nir/dto/response/ParsedLineResponse.java src/main/java/com/vladoose/nir/dto/response/SiteProductResponse.java src/main/java/com/vladoose/nir/service/SiteProductService.java src/main/java/com/vladoose/nir/controller/SiteProductController.java src/main/java/com/vladoose/nir/integration/westmed/WestmedClient.java src/main/java/com/vladoose/nir/integration/westmed/WestmedHttpClient.java src/main/java/com/vladoose/nir/integration/westmed/dto/WestmedProduct.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/util/PastedLinesParserTest.java src/test/java/com/vladoose/nir/service/SiteProductServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): «вставить списком» и поиск по каталогу westmed.kz с дедлайном 5 с

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Заявка из обращения одной кнопкой, импорт без клиента и в существующую заявку

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/LeadConvertRequest.java` (`isOneClick()`)
- Modify: `src/main/java/com/vladoose/nir/service/LeadService.java` (`convert`)
- Modify: `src/main/java/com/vladoose/nir/controller/LeadController.java` (тело `convert` необязательно)
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestService.java` (`createFromLead`)
- Modify: `src/main/java/com/vladoose/nir/dto/request/ImportCommitRequest.java` (клиент необязателен, контакт)
- Create: `src/main/java/com/vladoose/nir/dto/request/ImportLinesRequest.java`
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java` (`commit` с контактом, `importInto`)
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (импорт возвращает вид; `POST /{id}/lines/import`; убрать старые помощники)
- Delete: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestResponse.java`
- Modify test: `src/test/java/com/vladoose/nir/lead/LeadConvertTest.java`, `src/test/java/com/vladoose/nir/imports/PrivateRequestImportServiceTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.createEmpty/appendLines/input/addLines/blankToNull/trunc` (задачи 2, 4), `LeadService.transition` (задача 2).
- Produces:
  - **`PrivateRequestService.createFromLead(Lead)`** → `Tender`.
  - **`PrivateRequestImportService`:** `Tender commit(ImportCommitRequest)` — клиент необязателен, контакт из письма; `Tender importInto(Long requestId, ImportLinesRequest)`.
  - **REST:**
    - `POST /api/leads/{id}/convert` — пустое тело: заявка одной кнопкой;
    - `POST /api/private-requests/import/commit` → `PrivateRequestViewResponse`;
    - `POST /api/private-requests/{id}/lines/import` `{mappings, lines, mode}` → вид.

- [ ] **Step 1: Падающие тесты превращения обращения**

В `LeadConvertTest.java` добавить импорты `com.vladoose.nir.entity.TenderLot` (если не покрыт `entity.*`) и три теста:

```java
    @Test
    void oneClick_takesClientTextContactNoteAndItemsFromLead() {
        Lead l = siteLead();

        LeadConvertResponse r = service.convert(l.getId(), null, "admin");

        Tender t = tenderRepository.findById(r.getPrivateRequestId()).orElseThrow();
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getFacility()).isNull();
        assertThat(t.getCustomerName()).isEqualTo("ТОО «ZZ Клиника»");
        assertThat(t.getContactName()).isEqualTo("Айгерим Сапарова");
        assertThat(t.getContactPhone()).isEqualTo("+7 777 000 00 03");
        assertThat(t.getContactEmail()).startsWith("aigerim-");
        assertThat(t.getDescription()).isEqualTo("Нужны облучатели");
        assertThat(t.getLots()).singleElement().satisfies(lot -> {
            assertThat(lot.getEquipName()).isEqualTo("Облучатель ОБН-150");
            assertThat(lot.getManufact()).isEqualTo("Азов");
            assertThat(lot.getQuantity()).isEqualTo(2);
            assertThat(lot.getRegStatus()).isNotNull();   // KZ: подсказка РУ посчитана сразу
        });
        assertThat(l.getStatus()).isEqualTo(LeadStatus.CONVERTED);
        assertThat(l.getPrivateRequest().getId()).isEqualTo(t.getId());
    }

    @Test
    void oneClick_knownClientIsLinked_emptyBodyMeansOneClick() {
        Lead l = siteLead();
        Facility f = client(Market.KZ);
        l.setFacility(f);

        LeadConvertResponse r = service.convert(l.getId(), new LeadConvertRequest(), "admin");

        Tender t = tenderRepository.findById(r.getPrivateRequestId()).orElseThrow();
        assertThat(t.getFacility().getId()).isEqualTo(f.getId());
        assertThat(t.getCustomerName()).isNull();
    }

    @Test
    void oneClick_truncatesLongItemName_andLeadWithoutItemsGivesEmptyRequest() {
        Lead longItem = intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-long-" + System.nanoTime(),
                LeadChannel.SITE, "Запрос КП", null, "Тест", "+7 777 000 00 04", null, null, null,
                List.of(new IncomingLead.Item("Д".repeat(480), null, 1, null)), LeadStatus.NEW, "NEW", null)).orElseThrow();
        Tender t = tenderRepository.findById(service.convert(longItem.getId(), null, "admin").getPrivateRequestId()).orElseThrow();
        assertThat(t.getLots().get(0).getEquipName()).hasSize(255);

        Lead noItems = intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-noitems-" + System.nanoTime(),
                LeadChannel.SITE, "Запрос КП", null, "Тест", "+7 777 000 00 05", null, null, "Нужна кушетка и ширма",
                List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();
        Tender empty = tenderRepository.findById(service.convert(noItems.getId(), null, "admin").getPrivateRequestId()).orElseThrow();
        assertThat(empty.getLots()).isEmpty();
        assertThat(empty.getDescription()).isEqualTo("Нужна кушетка и ширма");
    }
```

- [ ] **Step 2: Падающие тесты импорта**

В `PrivateRequestImportServiceTest.java`:
- в `commit_savesNewSynonym_andCreatesRequestWithLines` заменить последнюю проверку (через уходящий `linesWithRegistration`) на:

```java
        assertThat(t.getLots()).extracting(com.vladoose.nir.entity.TenderLot::getManufact).containsExactly("BrandY");
```

- добавить (импорты `com.vladoose.nir.context.MarketContext`, `com.vladoose.nir.dto.request.ImportLinesRequest`, `com.vladoose.nir.dto.request.LinesMode`, `com.vladoose.nir.entity.Market`, `com.vladoose.nir.entity.TenderLot`, `org.junit.jupiter.api.AfterEach`):

```java
    @AfterEach void clearMarket() { MarketContext.clear(); }

    @Test
    void commit_withoutClient_takesContactFromLetter() {
        ImportCommitRequest dto = new ImportCommitRequest();
        dto.setContactName("Айгуль Сапарова");
        dto.setContactEmail("aigul@clinic.kz");
        PrivateRequestCreate.Line line = new PrivateRequestCreate.Line();
        line.setName("Тонометр"); line.setQuantity(2);
        dto.setLines(List.of(line));

        Tender t = importService.commit(dto);

        assertThat(t.getFacility()).isNull();
        assertThat(t.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(t.getContactEmail()).isEqualTo("aigul@clinic.kz");
        assertThat(t.getLots()).hasSize(1);
    }

    @Test
    void importInto_appendsOrReplaces_andLearnsHeaders() {
        MarketContext.set(Market.KZ);
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(PrivateRequestService.input("ZZ Было", null, 1, null, null, null)),
                LinesMode.APPEND);
        ImportLinesRequest dto = new ImportLinesRequest();
        ColumnMapping m = new ColumnMapping(); m.setHeader("ZZ Товар Импорт"); m.setField(LineField.NAME);
        dto.setMappings(List.of(m));
        PrivateRequestCreate.Line a = new PrivateRequestCreate.Line(); a.setName("ZZ Новое А"); a.setQuantity(-3);
        PrivateRequestCreate.Line b = new PrivateRequestCreate.Line(); b.setName("ZZ Новое Б"); b.setQuantity(4);
        dto.setLines(List.of(a, b));

        importService.importInto(t.getId(), dto);
        assertThat(t.getLots()).extracting(TenderLot::getEquipName).containsExactlyInAnyOrder("ZZ Было", "ZZ Новое А", "ZZ Новое Б");
        assertThat(t.getLots()).filteredOn(l -> l.getEquipName().equals("ZZ Новое А"))
                .singleElement().extracting(TenderLot::getQuantity).isEqualTo(1);
        assertThat(synonymRepository.findByHeaderNorm("zz товар импорт")).isPresent();

        dto.setMode(LinesMode.REPLACE);
        dto.setLines(List.of(b));
        importService.importInto(t.getId(), dto);
        assertThat(t.getLots()).extracting(TenderLot::getEquipName).containsExactly("ZZ Новое Б");
    }
```

- [ ] **Step 3: Запустить — не компилируются**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.lead.LeadConvertTest' --tests 'com.vladoose.nir.imports.PrivateRequestImportServiceTest'`
Expected: FAIL — `convert(…, null, …)` бросает `NullPointerException` / нет `ImportLinesRequest`, `importInto`, `setContactName`.

- [ ] **Step 4: `LeadConvertRequest.isOneClick`**

В `LeadConvertRequest.java` (импорт `com.fasterxml.jackson.annotation.JsonIgnore`) добавить:

```java
    /** Пустое тело — «Создать заявку» одной кнопкой: клиент, контакт, текст и позиции берутся из обращения. */
    @JsonIgnore
    public boolean isOneClick() {
        return clientFacilityId == null && newClient == null && (lines == null || lines.isEmpty());
    }
```

- [ ] **Step 5: `PrivateRequestService.createFromLead`**

```java
    /** «Создать заявку» из обращения одной кнопкой (спека §4): клиент, если обращение его узнало, иначе текст компании. */
    @Transactional
    public Tender createFromLead(Lead lead) {
        Tender t = createEmpty();
        Facility f = lead.getFacility();
        if (f != null && (f.getMarket() == null || f.getMarket() == MarketContext.get())) {
            t.setFacility(f);
        } else {
            t.setCustomerName(trunc(blankToNull(lead.getCompany()), 500));
        }
        t.setContactName(trunc(blankToNull(lead.getContactName()), 255));
        t.setContactPhone(trunc(blankToNull(lead.getContactPhone()), 50));
        t.setContactEmail(trunc(blankToNull(lead.getContactEmail()), 255));
        t.setDescription(blankToNull(lead.getMessage()));
        List<PrivateRequestLineInput> inputs = lead.getItems().stream()
                .filter(i -> blankToNull(i.getName()) != null)
                .map(i -> input(i.getName(), i.getBrand(), i.getQuantity(), null, null, httpOrNull(i.getProductUrl())))
                .toList();
        if (!inputs.isEmpty()) appendLines(t, inputs);
        tenderRepository.flush();
        return t;
    }

    /** Ссылка из обращения — только http(s) и не длиннее колонки: иначе позиция без ссылки, а не отказ всей заявки. */
    private static String httpOrNull(String raw) {
        String v = blankToNull(raw);
        return v != null && v.length() <= 500 && (v.startsWith("https://") || v.startsWith("http://")) ? v : null;
    }
```

- [ ] **Step 6: `LeadService.convert` — ветка одной кнопки**

В `LeadService.convert` сразу после `require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), "создать частную заявку");` вставить:

```java
        if (req == null || req.isOneClick()) {
            Tender request = privateRequestService.createFromLead(lead);
            lead.setPrivateRequest(request);
            transition(lead, LeadStatus.CONVERTED, author, "создана частная заявка " + request.getTenderNumber());
            return new LeadConvertResponse(request.getId(), request.getTenderNumber());
        }
```

Javadoc метода дополнить строкой: «Пустое тело (`isOneClick`) — заявка одной кнопкой из данных обращения (спека 2026-10-12 §4); старые поля — путь прежнего диалога».

В `LeadController.convert` заменить параметр на `@Valid @RequestBody(required = false) LeadConvertRequest req`.

- [ ] **Step 7: Импорт — клиент необязателен, Excel в существующую заявку**

`ImportCommitRequest.java` — заменить целиком:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Заявка из Excel (экран «Входящие»): клиент необязателен, отправитель письма — контакт заявки. */
@Data
public class ImportCommitRequest {
    private Long clientFacilityId;
    @Size(max = 255) private String contactName;
    @Size(max = 255) private String contactEmail;
    private String note;
    private List<ColumnMapping> mappings;
    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<PrivateRequestCreate.Line> lines;
}
```

`src/main/java/com/vladoose/nir/dto/request/ImportLinesRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Excel в существующую заявку: разметка колонок (учит словарь) и строки грида. */
@Data
public class ImportLinesRequest {
    private List<ColumnMapping> mappings;
    @NotEmpty(message = "Нет строк с наименованием")
    private List<PrivateRequestCreate.Line> lines;
    /** пусто — APPEND */
    private LinesMode mode;
}
```

В `PrivateRequestImportService.java` (импорты `com.vladoose.nir.dto.request.ImportLinesRequest`, `LinesMode`, `PrivateRequestLineInput`) метод `commit` заменить и добавить `importInto`:

```java
    @Transactional
    public Tender commit(ImportCommitRequest dto) {
        learn(dto.getMappings());
        PrivateRequestCreate create = new PrivateRequestCreate();
        create.setClientFacilityId(dto.getClientFacilityId());
        create.setNote(dto.getNote());
        create.setLines(dto.getLines());
        Tender t = privateRequestService.createFromLines(create);
        t.setContactName(PrivateRequestService.trunc(PrivateRequestService.blankToNull(dto.getContactName()), 255));
        t.setContactEmail(PrivateRequestService.trunc(PrivateRequestService.blankToNull(dto.getContactEmail()), 255));
        return t;
    }

    /** Excel в существующую заявку: разметка учит словарь, позиции — в конец или вместо незапрошенных у поставщиков. */
    @Transactional
    public Tender importInto(Long requestId, ImportLinesRequest dto) {
        learn(dto.getMappings());
        List<PrivateRequestLineInput> inputs = dto.getLines().stream()
                .map(l -> PrivateRequestService.input(l.getName(), l.getManufact(), l.getQuantity(), null, null, null))
                .toList();
        return privateRequestService.addLines(requestId, inputs, dto.getMode() == null ? LinesMode.APPEND : dto.getMode());
    }
```

- [ ] **Step 8: Контроллер — импорт возвращает вид, старые помощники уходят**

В `PrivateRequestController.java`:
- `importCommit` теперь возвращает вид:

```java
    @PostMapping("/import/commit")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse importCommit(@Valid @RequestBody ImportCommitRequest request) {
        return viewBuilder.build(importService.commit(request));
    }

    @PostMapping("/{id}/lines/import")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse importLines(@PathVariable Long id, @Valid @RequestBody ImportLinesRequest request) {
        return viewBuilder.build(importService.importInto(id, request));
    }
```

- удалить приватные `buildFull(Tender)` и `toShort(Tender)`;
- убрать из полей и конструктора `FacilityMapper facilityMapper`, `LeadService leadService`, `LeadResponseMapper leadResponseMapper` и их импорты — больше не нужны.

```bash
git rm src/main/java/com/vladoose/nir/dto/response/PrivateRequestResponse.java
```

`grep -rn "PrivateRequestResponse\b" src/` после этого должен находить только `PrivateRequestViewResponse` и `PrivateRequestListItemResponse`.

- [ ] **Step 9: Запустить — зелёные**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.lead.*' --tests 'com.vladoose.nir.imports.*' --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.sourcing.*'`
Expected: PASS.

- [ ] **Step 10: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/request/LeadConvertRequest.java src/main/java/com/vladoose/nir/service/LeadService.java src/main/java/com/vladoose/nir/controller/LeadController.java src/main/java/com/vladoose/nir/service/PrivateRequestService.java src/main/java/com/vladoose/nir/dto/request/ImportCommitRequest.java src/main/java/com/vladoose/nir/dto/request/ImportLinesRequest.java src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/lead/LeadConvertTest.java src/test/java/com/vladoose/nir/imports/PrivateRequestImportServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): заявка из обращения одной кнопкой; импорт без клиента и Excel в существующую заявку

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Подбор поставщиков для запроса цен

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java` (переписать целиком)
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestSourcingResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (`GET /{id}/sourcing`)
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestService.java` (удалить `linesWithRegistration` и его импорты)
- Delete: `src/main/java/com/vladoose/nir/dto/response/SourcingGroupResponse.java`, `SourcingPreviewResponse.java`, `PrivateRequestLineResponse.java`
- Modify test: `src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java` (переписать целиком), `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java` (удалить `linesWithRegistration_returnsCandidateForRegisteredModel`)

**Interfaces:**
- Consumes: `PrivateRequestService.findById`, `DistributorService.findAll()` (рынок режет аспект), `LotTypeClassifier.classify(TenderLot)`, `BrandMatch.firstCarried(brands, haystack)`, `PriceRequestItemRepository.findByTenderId`.
- Produces:
  - **`PrivateRequestSourcingService.build(Long requestId)`** → `PrivateRequestSourcingResponse(List<Supplier> suppliers, List<LineRef> unmatched)`.
  - **`Supplier(distributorId, name, email, hasEmail, preselected, List<Line> lines)`.**
  - **`Line(lotId, name, quantity, unit, reason, label, preselected, requestedAt)`**, где `reason` — `BRAND | TYPE`.
  - **`LineRef(lotId, name, brand)`.**
  - **REST:** `GET /api/private-requests/{id}/sourcing`.

- [ ] **Step 1: Переписать тест подбора (падает)**

`src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java`:

```java
package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** Уникальные бренды и вид изделия — в nirdb живут настоящие поставщики, их бренды в подбор не попадают. */
@SpringBootTest
@Transactional
class PrivateRequestSourcingServiceTest {

    @Autowired PrivateRequestSourcingService sourcing;
    @Autowired PrivateRequestService privateRequestService;
    @Autowired DistributorRepository distributorRepository;
    @Autowired EquipmentTypeRepository typeRepository;
    @Autowired EquipmentTypeSynonymRepository synonymRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private Distributor dist(String name, String email, List<String> brands, List<EquipmentType> types) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email(email)
                .brands(new ArrayList<>(brands)).equipmentTypes(new ArrayList<>(types)).build());
    }

    private Supplier supplier(PrivateRequestSourcingResponse r, Distributor d) {
        return r.suppliers().stream().filter(s -> s.distributorId().equals(d.getId())).findFirst().orElseThrow();
    }

    @Test
    void brandFirst_typeAsWeakHint_unmatchedListed_requestedNotPreselected() {
        EquipmentType type = typeRepository.save(EquipmentType.builder().name("ZZ Вид подбора " + System.nanoTime()).build());
        synonymRepository.save(EquipmentTypeSynonym.builder().termNorm("zzклассификатор").equipmentType(type).build());
        Distributor brand = dist("ZZ Подбор Бренд", "b@zz.kz", List.of("ZZSRCBRAND"), List.of());
        Distributor brandNoMail = dist("ZZ Подбор Бренд без почты", null, List.of("ZZSRCBRAND"), List.of());
        Distributor byType = dist("ZZ Подбор Вид", "t@zz.kz", List.of(), List.of(type));
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(
                input("ZZ Монитор", "Завод ZZSRCBRAND", 2, null, null, null),
                input("ZZКЛАССИФИКАТОР модель 7", null, 1, null, null, null),
                input("ZZ Ничья позиция", "ZZНИКТО", 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        Supplier sBrand = supplier(r, brand);
        assertThat(sBrand.preselected()).isTrue();
        assertThat(sBrand.hasEmail()).isTrue();
        assertThat(sBrand.lines()).singleElement().satisfies(l -> {
            assertThat(l.lotId()).isEqualTo(lots.get(0).getId());
            assertThat(l.reason()).isEqualTo("BRAND");
            assertThat(l.label()).isEqualTo("возит ZZSRCBRAND");
            assertThat(l.preselected()).isTrue();
            assertThat(l.requestedAt()).isNull();
        });
        Supplier sNoMail = supplier(r, brandNoMail);
        assertThat(sNoMail.preselected()).isFalse();
        assertThat(sNoMail.hasEmail()).isFalse();
        Supplier sType = supplier(r, byType);
        assertThat(sType.preselected()).isFalse();
        assertThat(sType.lines()).singleElement().satisfies(l -> {
            assertThat(l.lotId()).isEqualTo(lots.get(1).getId());
            assertThat(l.reason()).isEqualTo("TYPE");
            assertThat(l.label()).startsWith("по виду: ZZ Вид подбора");
            assertThat(l.preselected()).isFalse();
        });
        assertThat(r.unmatched()).extracting(PrivateRequestSourcingResponse.LineRef::lotId).containsExactly(lots.get(2).getId());
        assertThat(r.suppliers().indexOf(sBrand)).isLessThan(r.suppliers().indexOf(sType));

        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(brand).status("SENT")
                .sentAt(OffsetDateTime.now()).build());
        itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lots.get(0)).requestedQuantity(2).build());
        em.flush();

        Supplier again = supplier(sourcing.build(t.getId()), brand);
        assertThat(again.preselected()).isFalse();
        assertThat(again.lines().get(0).requestedAt()).isNotNull();
        assertThat(again.lines().get(0).preselected()).isFalse();
    }

    @Test
    void otherMarketSuppliersAreNotOffered() {
        MarketContext.set(Market.RF);
        Distributor rf = dist("ZZ Подбор РФ", "rf@zz.ru", List.of("ZZSRCRFONLY"), List.of());
        MarketContext.set(Market.KZ);
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(input("ZZ Позиция", "ZZSRCRFONLY", 1, null, null, null)),
                LinesMode.APPEND);

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        assertThat(r.suppliers()).noneMatch(s -> s.distributorId().equals(rf.getId()));
        assertThat(r.unmatched()).hasSize(1);
    }
}
```

`EquipmentType.builder()` и `EquipmentTypeSynonym.builder()` есть (Lombok `@Builder`). Если у `EquipmentType` нет `@Builder` в этом виде — создать через `new EquipmentType()` и сеттер имени.

- [ ] **Step 2: Запустить — не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.sourcing.PrivateRequestSourcingServiceTest'`
Expected: FAIL — нет `PrivateRequestSourcingResponse`, метода `build`.

- [ ] **Step 3: DTO**

`src/main/java/com/vladoose/nir/dto/response/PrivateRequestSourcingResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** Окно «Запросить цены»: кто подходит по позициям и почему (спека §6.1). */
public record PrivateRequestSourcingResponse(List<Supplier> suppliers, List<LineRef> unmatched) {

    /** preselected — отмечен по умолчанию: есть почта и хоть одна позиция с совпавшим брендом, ещё не запрошенная. */
    public record Supplier(Long distributorId, String name, String email, boolean hasEmail, boolean preselected,
                           List<Line> lines) {}

    /** reason: BRAND («возит <бренд>») или TYPE («по виду: <вид>» — слабая подсказка, без отметки). */
    public record Line(Long lotId, String name, Integer quantity, String unit, String reason, String label,
                       boolean preselected, OffsetDateTime requestedAt) {}

    public record LineRef(Long lotId, String name, String brand) {}
}
```

- [ ] **Step 4: Сервис подбора — переписать целиком**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.Line;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.LineRef;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Кто подходит для запроса цен по позициям частной заявки (спека 2026-10-12 §6.1). Дистрибьюторы с брендом строки
 * отмечаются по умолчанию. Если бренда нет или его никто не возит — поставщики того же вида изделия, без отметки.
 * Уже запрошенная у поставщика позиция видна с датой и не отмечается. Производитель бренда по умолчанию — этап 2.
 */
@Service
public class PrivateRequestSourcingService {

    private final PrivateRequestService privateRequestService;
    private final DistributorService distributorService;
    private final LotTypeClassifier typeClassifier;
    private final PriceRequestItemRepository itemRepository;

    public PrivateRequestSourcingService(PrivateRequestService privateRequestService, DistributorService distributorService,
                                         LotTypeClassifier typeClassifier, PriceRequestItemRepository itemRepository) {
        this.privateRequestService = privateRequestService;
        this.distributorService = distributorService;
        this.typeClassifier = typeClassifier;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public PrivateRequestSourcingResponse build(Long requestId) {
        Tender t = privateRequestService.findById(requestId);
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()));
        List<Distributor> distributors = distributorService.findAll();   // рынок режет аспект
        Map<String, OffsetDateTime> requestedAt = requestedAt(requestId);

        Map<Long, Acc> bySupplier = new LinkedHashMap<>();
        List<LineRef> unmatched = new ArrayList<>();
        for (TenderLot lot : lots) {
            boolean matched = false;
            for (Distributor d : distributors) {
                String carried = BrandMatch.firstCarried(d.getBrands(), lot.getManufact());
                if (carried != null) {
                    acc(bySupplier, d).add(lot, "BRAND", "возит " + carried.trim(), requestedAt);
                    matched = true;
                }
            }
            if (!matched) {
                LotTypeClassifier.TypeGuess guess = typeClassifier.classify(lot);
                if (guess.typeId() != null) {
                    for (Distributor d : distributors) {
                        if (d.getEquipmentTypes().stream().anyMatch(et -> guess.typeId().equals(et.getId()))) {
                            acc(bySupplier, d).add(lot, "TYPE", "по виду: " + guess.typeName(), requestedAt);
                            matched = true;
                        }
                    }
                }
            }
            if (!matched) unmatched.add(new LineRef(lot.getId(), lot.getEquipName(), lot.getManufact()));
        }
        List<Supplier> suppliers = bySupplier.values().stream().map(Acc::toSupplier)
                .sorted(Comparator.comparing(Supplier::preselected).reversed()
                        .thenComparing((Supplier s) -> -s.lines().size())
                        .thenComparing(Supplier::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new PrivateRequestSourcingResponse(suppliers, unmatched);
    }

    /** «позиция:поставщик» → когда запрашивали последний раз (у старых запросов без дат — момент чтения). */
    private Map<String, OffsetDateTime> requestedAt(Long requestId) {
        Map<String, OffsetDateTime> out = new HashMap<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (PriceRequestItem i : itemRepository.findByTenderId(requestId)) {
            PriceRequest pr = i.getPriceRequest();
            OffsetDateTime at = pr.getSentAt() != null ? pr.getSentAt()
                    : pr.getCreatedAt() != null ? pr.getCreatedAt() : now;
            out.merge(i.getTenderLot().getId() + ":" + pr.getDistributor().getId(), at,
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    private static Acc acc(Map<Long, Acc> bySupplier, Distributor d) {
        return bySupplier.computeIfAbsent(d.getId(), k -> new Acc(d));
    }

    private static final class Acc {
        final Distributor d;
        final boolean hasEmail;
        final List<Line> lines = new ArrayList<>();
        final Set<Long> lotIds = new HashSet<>();

        Acc(Distributor d) {
            this.d = d;
            this.hasEmail = d.getEmail() != null && !d.getEmail().isBlank();
        }

        void add(TenderLot lot, String reason, String label, Map<String, OffsetDateTime> requestedAt) {
            if (!lotIds.add(lot.getId())) return;
            OffsetDateTime at = requestedAt.get(lot.getId() + ":" + d.getId());
            boolean pre = "BRAND".equals(reason) && hasEmail && at == null;
            lines.add(new Line(lot.getId(), lot.getEquipName(), lot.getQuantity(), lot.getUnit(), reason, label, pre, at));
        }

        Supplier toSupplier() {
            boolean pre = lines.stream().anyMatch(Line::preselected);
            return new Supplier(d.getId(), d.getName(), hasEmail ? d.getEmail().trim() : null, hasEmail, pre, List.copyOf(lines));
        }
    }
}
```

- [ ] **Step 5: Контроллер и уборка старого**

В `PrivateRequestController.java` метод `sourcing` заменить:

```java
    @GetMapping("/{id}/sourcing")
    public PrivateRequestSourcingResponse sourcing(@PathVariable Long id) {
        return sourcingService.build(id);
    }
```

В `PrivateRequestService.java` удалить метод `linesWithRegistration` и импорт `PrivateRequestLineResponse`. Поле `registryMatchService` больше не используется — убрать его из полей и конструктора вместе с импортом.

В `PrivateRequestServiceTest.java` удалить метод `linesWithRegistration_returnsCandidateForRegisteredModel` и неиспользуемые импорты (`PrivateRequestLineResponse`, `MedRegistryRepository`, `MedRegistry`, если только он их использовал). Подсказку РУ покрывает `PrivateRequestLinesTest`.

```bash
git rm src/main/java/com/vladoose/nir/dto/response/SourcingGroupResponse.java src/main/java/com/vladoose/nir/dto/response/SourcingPreviewResponse.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineResponse.java
```

- [ ] **Step 6: Запустить — зелёные**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.sourcing.*' --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.imports.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS.

- [ ] **Step 7: Мутация «уже запрошенное не отмечается»**

Копия `PrivateRequestSourcingService.java` в `/tmp`. В `Acc.add` убрать `&& at == null` из условия `pre`.
Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.sourcing.PrivateRequestSourcingServiceTest'`
Expected: FAIL (`again.preselected()` — true). Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestSourcingResponse.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/main/java/com/vladoose/nir/service/PrivateRequestService.java src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): подбор поставщиков — бренд по умолчанию, вид изделия подсказкой, «уже запрошено»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Окно «Запросить цены» — предпросмотр и отправка, исход письма

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestSendService.java` (`SendOptions`, перегрузки `send`/`preview`, исход письма)
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestWriter.java` (`replyBy`, `markLetter`)
- Modify: `src/main/java/com/vladoose/nir/service/KpEmailComposer.java` («ответить до», ед. изм., РУ)
- Create: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/RfqPreviewRequest.java`, `RfqSendRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (`rfq/preview`, `rfq/send`)
- Create test: `src/test/java/com/vladoose/nir/support/SmtpIntegrationTest.java`, `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRfqTest.java`
- Modify test: `src/test/java/com/vladoose/nir/service/PriceRequestSendServiceTest.java` (общая аннотация)

**Interfaces:**
- Consumes: `PrivateRequestService.findById` (задача 2), `LineRegStatus` (задача 1), `PriceRequestSendService.send/preview/resend`.
- Produces:
  - **`PriceRequestSendService`:**
    - `record SendOptions(LocalDate replyBy)` с `NONE`;
    - `List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items, String subjectOverride, String bodyOverride, SendOptions options)` — прежняя пятиаргументная делегирует с `NONE`;
    - `KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items, SendOptions options)`.
  - **`PriceRequestWriter`:** `PriceRequest persist(Tender, Distributor, List<Line>, LocalDate replyBy)`, `void markLetter(Long priceRequestId, LetterStatus, String sentTo)`.
  - **`PrivateRequestRfqService`:** `KpPreviewResponse preview(Long requestId, RfqPreviewRequest)`, `List<SendResult> send(Long requestId, RfqSendRequest)`.
  - **REST:** `POST /api/private-requests/{id}/rfq/preview` `{distributorId, lotIds, replyBy}` → `{subject, body}`; `POST /{id}/rfq/send` `{replyBy, suppliers: [{distributorId, lotIds}]}` → `List<SendResult>`.
  - **Тесты:** аннотация `@SmtpIntegrationTest`.

- [ ] **Step 1: Общая аннотация SMTP-тестов**

`src/test/java/com/vladoose/nir/support/SmtpIntegrationTest.java`:

```java
package com.vladoose.nir.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.*;

/**
 * Общий контекст тестов отправки писем через GreenMail SMTP 127.0.0.1:3025: ОДИН набор свойств на все такие классы —
 * каждый особый набор поднимает свой контекст и ещё 10 соединений к nirdb (CLAUDE.md §14). Класс сам регистрирует
 * GreenMailExtension(ServerSetupTest.SMTP) с пользователем zakup-test@westmed.local / secret.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=zakup-test@westmed.local",
        "spring.mail.password=secret"
})
public @interface SmtpIntegrationTest {
}
```

В `PriceRequestSendServiceTest.java` заменить три аннотации класса (`@SpringBootTest`, `@Transactional`, `@TestPropertySource(...)`) на `@SmtpIntegrationTest` (импорт `com.vladoose.nir.support.SmtpIntegrationTest`; неиспользуемые импорты `SpringBootTest`, `TestPropertySource`, `Transactional` убрать). Набор свойств тот же — контекст общий.

- [ ] **Step 2: Падающий тест окна запроса цен**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRfqTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LineRegistryAction;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestRfqService;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SmtpIntegrationTest
class PrivateRequestRfqTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestRfqService rfq;
    @Autowired PriceRequestSendService sendService;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired MedRegistryRepository registryRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name, String email) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email(email).build());
    }

    private static List<TenderLot> sorted(Tender t) {
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
        return lots;
    }

    private static RfqSendRequest.Supplier supplier(Distributor d, TenderLot... lots) {
        RfqSendRequest.Supplier s = new RfqSendRequest.Supplier();
        s.setDistributorId(d.getId());
        s.setLotIds(Arrays.stream(lots).map(TenderLot::getId).toList());
        return s;
    }

    private static RfqSendRequest request(LocalDate replyBy, RfqSendRequest.Supplier... suppliers) {
        RfqSendRequest r = new RfqSendRequest();
        r.setReplyBy(replyBy);
        r.setSuppliers(List.of(suppliers));
        return r;
    }

    private static String bodyTo(MimeMessage[] mails, String to) throws Exception {
        for (MimeMessage m : mails) {
            if (m.getAllRecipients()[0].toString().equals(to)) return m.getContent().toString();
        }
        throw new AssertionError("нет письма для " + to);
    }

    @Test
    void eachSupplierGetsOneLetterWithOnlyItsLines_letterOutcomeIsStored() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Тонометр RFQ", "ZZRFQBRAND", 5, "упак", "Манжета 22–42 см", null),
                input("ZZ Кушетка RFQ", null, 2, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = sorted(t);
        Distributor a = dist("ZZ RFQ А", "a@rfq.kz");
        Distributor b = dist("ZZ RFQ Б", "b@rfq.kz");
        Distributor noMail = dist("ZZ RFQ без почты", null);
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(3);

        List<SendResult> results = rfq.send(t.getId(), request(replyBy,
                supplier(a, lots.get(0)), supplier(b, lots.get(0), lots.get(1)), supplier(noMail, lots.get(1))));

        assertThat(results).hasSize(3);
        assertThat(results).extracting(SendResult::emailSent).containsExactly(true, true, false);
        MimeMessage[] mails = greenMail.getReceivedMessages();
        assertThat(mails).hasSize(2);
        String bodyA = bodyTo(mails, "a@rfq.kz");
        assertThat(bodyA).contains("ZZ Тонометр RFQ (ZZRFQBRAND) — 5 упак")
                .contains("Требования: Манжета 22–42 см")
                .contains("Просим ответить до " + replyBy.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .doesNotContain("ZZ Кушетка RFQ")
                .doesNotContain(t.getTenderNumber());
        assertThat(bodyTo(mails, "b@rfq.kz")).contains("ZZ Тонометр RFQ").contains("ZZ Кушетка RFQ — 2 шт.");

        PriceRequest prA = priceRequestRepository.findById(results.get(0).priceRequestId()).orElseThrow();
        assertThat(prA.getLetterStatus()).isEqualTo(LetterStatus.SENT);
        assertThat(prA.getSentTo()).isEqualTo("a@rfq.kz");
        assertThat(prA.getReplyBy()).isEqualTo(replyBy);
        assertThat(prA.getItems()).hasSize(1);
        PriceRequest prNo = priceRequestRepository.findById(results.get(2).priceRequestId()).orElseThrow();
        assertThat(prNo.getLetterStatus()).isEqualTo(LetterStatus.NO_EMAIL);
        assertThat(prNo.getSentTo()).isNull();
    }

    @Test
    void confirmedRegistrationGoesIntoLetter_previewMatchesLetter_resendKeepsReplyBy() throws Exception {
        registryRepository.save(MedRegistry.builder().regNumber("ZZ-RFQ-RU-1").name("Аппарат ZZRFQMODEL Q5")
                .producer("ZZRFQVENDOR").country("ГЕРМАНИЯ").unlimited(true).build());
        registryRepository.flush();
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("Аппарат ZZRFQMODEL Q5", "ZZRFQVENDOR", 1, null, null, null)),
                LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        service.lineRegistry(t.getId(), lot.getId(), LineRegistryAction.CONFIRM, null);
        Distributor a = dist("ZZ RFQ РУ", "ru@rfq.kz");
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(1);

        RfqPreviewRequest pr = new RfqPreviewRequest();
        pr.setDistributorId(a.getId());
        pr.setLotIds(List.of(lot.getId()));
        pr.setReplyBy(replyBy);
        KpPreviewResponse preview = rfq.preview(t.getId(), pr);
        assertThat(preview.getBody()).contains("Аппарат ZZRFQMODEL Q5 (ZZRFQVENDOR), РУ № ZZ-RFQ-RU-1 — 1 шт.");
        assertThat(greenMail.getReceivedMessages()).isEmpty();

        List<SendResult> results = rfq.send(t.getId(), request(replyBy, supplier(a, lot)));
        String body = bodyTo(greenMail.getReceivedMessages(), "ru@rfq.kz");
        assertThat(body).contains("РУ № ZZ-RFQ-RU-1").contains("Просим ответить до");

        sendService.resend(results.get(0).priceRequestId());
        MimeMessage[] mails = greenMail.getReceivedMessages();
        assertThat(mails).hasSize(2);
        assertThat(mails[1].getContent().toString()).contains("Просим ответить до");
    }

    @Test
    void foreignLotPastReplyByOrNoLines_is400_nothingSent() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Своя", null, 1, null, null, null)), LinesMode.APPEND);
        Tender other = service.createEmpty();
        service.addLines(other.getId(), List.of(input("ZZ Чужая", null, 1, null, null, null)), LinesMode.APPEND);
        Distributor a = dist("ZZ RFQ отказ", "x@rfq.kz");
        TenderLot own = t.getLots().get(0);
        TenderLot foreign = other.getLots().get(0);
        LocalDate ok = LocalDate.now(Market.KZ.zone()).plusDays(2);

        assertThatThrownBy(() -> rfq.send(t.getId(), request(ok, supplier(a, own), supplier(a, foreign))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не из этой заявки");
        assertThatThrownBy(() -> rfq.send(t.getId(), request(LocalDate.now(Market.KZ.zone()).minusDays(1), supplier(a, own))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже прошла");
        assertThatThrownBy(() -> rfq.send(t.getId(), request(ok, supplier(a))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("не выбраны позиции");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(priceRequestRepository.findByTenderId(t.getId())).isEmpty();
    }
}
```

- [ ] **Step 3: Запустить — не компилируется**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqTest'`
Expected: FAIL — нет `PrivateRequestRfqService`, `RfqSendRequest`.

- [ ] **Step 4: `PriceRequestWriter` — «ответить до» и исход письма**

В `PriceRequestWriter.java`:
- добавить зависимость `PriceRequestRepository priceRequestRepository` (поле, параметр конструктора);
- импорты `java.time.LocalDate`, `com.vladoose.nir.entity.LetterStatus`, `com.vladoose.nir.repository.PriceRequestRepository`;
- метод `persist(Tender, Distributor, List<Line>)` заменить двумя:

```java
    /** Создаёт один PriceRequest (SENT) с позициями и коммитит. Письмо шлётся вызывающим уже после коммита. */
    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines) {
        return persist(tender, dist, lines, null);
    }

    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines, LocalDate replyBy) {
        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status("SENT")
                .sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .replyBy(replyBy)
                .build();
        pr = priceRequestService.save(pr); // штампует market
        for (Line line : lines) {
            PriceRequestItem item = PriceRequestItem.builder()
                    .priceRequest(pr)
                    .tenderLot(line.lot())
                    .medEquipment(line.equipment())
                    .requestedQuantity(line.qty())
                    .build();
            pr.getItems().add(itemRepository.save(item));
        }
        return pr;
    }

    /** Исход письма — своей короткой транзакцией после отправки (письмо идёт вне транзакции, §6). */
    @Transactional
    public void markLetter(Long priceRequestId, LetterStatus status, String sentTo) {
        priceRequestRepository.findById(priceRequestId).ifPresent(pr -> {
            pr.setLetterStatus(status);
            pr.setSentTo(sentTo);
        });
    }
```

- [ ] **Step 5: `PriceRequestSendService` — настройки и исход письма**

В `PriceRequestSendService.java` (импорты `java.time.LocalDate`, `com.vladoose.nir.entity.LetterStatus`):

```java
    /** Настройки письма запроса цен. Этап 2 добавит вложение, клиента и адрес. */
    public record SendOptions(LocalDate replyBy) {
        public static final SendOptions NONE = new SendOptions(null);
    }

    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride) {
        return send(tenderId, distributorIds, items, subjectOverride, bodyOverride, SendOptions.NONE);
    }
```

У прежнего метода `send(...)` к сигнатуре добавить последний параметр `SendOptions options`, а в его теле заменить строку `PriceRequest pr = writer.persist(tender, dist, lines);` на:

```java
            PriceRequest pr = writer.persist(tender, dist, lines, options == null ? null : options.replyBy());
```

Метод `preview(Long tenderId, List<Long> distributorIds, List<SendItem> items)` заменить двумя (прежняя сигнатура — делегат):

```java
    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items) {
        return preview(tenderId, distributorIds, items, SendOptions.NONE);
    }

    /** Черновой предпросмотр текста КП (образец по первому поставщику, PriceRequest НЕ сохраняется, темы без токена). */
    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items, SendOptions options) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        List<PriceRequestItem> prItems = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = si.medEquipmentId() != null ? medEquipmentService.findById(si.medEquipmentId()) : null;
            prItems.add(PriceRequestItem.builder().tenderLot(lot).medEquipment(eq)
                    .requestedQuantity(si.requestedQuantity()).build());
        }
        Distributor sample = distributorService.findById(distributorIds.get(0));
        requireCurrentMarket(sample.getMarket(), "Поставщик не найден");
        PriceRequest draft = PriceRequest.builder().tender(tender).distributor(sample)
                .market(tender.getMarket()).items(prItems)
                .replyBy(options == null ? null : options.replyBy()).build();
        KpEmailComposer.Composed c = composer.composeForPreview(draft);
        return new KpPreviewResponse(c.subject(), c.body());
    }
```

В `dispatch(...)` записать исход письма в трёх местах:
- перед `return` ветки без почты: `letter(pr, LetterStatus.NO_EMAIL, null);`;
- после `emailService.sendEmail(to, subject, body);`: `letter(pr, LetterStatus.SENT, to);`;
- в `catch` перед `return`: `letter(pr, LetterStatus.FAILED, null);`.

Сам метод:

```java
    /** Исход письма в запрос; сбой записи не прячет результат отправки — только предупреждение в лог. */
    private void letter(PriceRequest pr, LetterStatus status, String sentTo) {
        try {
            writer.markLetter(pr.getId(), status, sentTo);
        } catch (RuntimeException e) {
            log.warn("КП id={}: исход письма {} не записан: {}", pr.getId(), status, e.getClass().getSimpleName());
        }
    }
```

- [ ] **Step 6: `KpEmailComposer` — «ответить до», ед. изм., РУ**

В `KpEmailComposer.java` (импорты `com.vladoose.nir.entity.LineRegStatus`, `java.time.LocalDate`):
- в `vars(...)` строку `v.put("дедлайн", …)` заменить на `v.put("дедлайн", deadlineLine(pr, tender));` и добавить:

```java
    /** «Ответить до» из окна запроса цен важнее срока тендера; нет ни того ни другого — пусто. */
    private static String deadlineLine(PriceRequest pr, Tender tender) {
        LocalDate d = pr.getReplyBy() != null ? pr.getReplyBy() : tender.getDeadline();
        return d != null ? "Просим ответить до " + DATE.format(d) + ".\n\n" : "";
    }

    /** Количество с ед. изм. позиции: «5 упак», без неё — «2 шт.» (как раньше). */
    private static String quantity(Integer qty, TenderLot lot) {
        if (qty == null) return "кол-во уточняется";
        String unit = lot.getUnit();
        return unit == null || unit.isBlank() ? qty + " шт." : qty + " " + unit.trim();
    }
```

- в `buildPositions` строку `String qty = …` заменить на `String qty = quantity(it.getRequestedQuantity(), lot);`;
- в ветке «голого» лота после добавления бренда и перед `sb.append(" — ").append(qty)` вставить:

```java
                if (lot.getRegStatus() == LineRegStatus.CONFIRMED && lot.getRegNumber() != null) {
                    sb.append(", РУ № ").append(lot.getRegNumber());
                }
```

- [ ] **Step 7: DTO и сервис окна запроса цен**

`src/main/java/com/vladoose/nir/dto/request/RfqPreviewRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class RfqPreviewRequest {
    @NotNull(message = "Не указан поставщик")
    private Long distributorId;
    @NotEmpty(message = "Не выбраны позиции")
    private List<Long> lotIds;
    private LocalDate replyBy;
}
```

`src/main/java/com/vladoose/nir/dto/request/RfqSendRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** «Отправить N писем»: каждому поставщику — одно письмо только с его позициями. */
@Data
public class RfqSendRequest {
    private LocalDate replyBy;
    @NotEmpty(message = "Не выбраны поставщики")
    @Valid
    private List<Supplier> suppliers;

    @Data
    public static class Supplier {
        @NotNull(message = "Не указан поставщик")
        private Long distributorId;
        @NotEmpty(message = "Не выбраны позиции")
        private List<Long> lotIds;
    }
}
```

`src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java`:

```java
package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Окно «Запросить цены» частной заявки (спека §6.2): каждому поставщику — одно письмо только с его позициями, через
 * единый канал отправки (метка [КП-id] в теме, исход письма в запросе). Сначала проверяется ВЕСЬ запрос — позиции своей
 * заявки, поставщики своего рынка, дата, — и только потом уходят письма: отказ на третьем поставщике не оставит двух
 * отправленных писем без итога.
 */
@Service
public class PrivateRequestRfqService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestSendService sendService;
    private final DistributorService distributorService;

    public PrivateRequestRfqService(PrivateRequestService privateRequestService, PriceRequestSendService sendService,
                                    DistributorService distributorService) {
        this.privateRequestService = privateRequestService;
        this.sendService = sendService;
        this.distributorService = distributorService;
    }

    /** Письмо, которое уйдёт поставщику, — без сохранения запроса (тема без метки). */
    public KpPreviewResponse preview(Long requestId, RfqPreviewRequest req) {
        Tender t = privateRequestService.findById(requestId);
        checkReplyBy(req.getReplyBy());
        Distributor d = distributor(req.getDistributorId());
        return sendService.preview(t.getId(), List.of(d.getId()), items(lots(t, req.getLotIds())),
                new SendOptions(req.getReplyBy()));
    }

    public List<SendResult> send(Long requestId, RfqSendRequest req) {
        Tender t = privateRequestService.findById(requestId);
        if (req.getSuppliers() == null || req.getSuppliers().isEmpty()) {
            throw new BadRequestException("Не выбраны поставщики");
        }
        checkReplyBy(req.getReplyBy());
        Map<Long, List<TenderLot>> plan = new LinkedHashMap<>();
        for (RfqSendRequest.Supplier s : req.getSuppliers()) {
            Distributor d = distributor(s.getDistributorId());
            if (s.getLotIds() == null || s.getLotIds().isEmpty()) {
                throw new BadRequestException("У поставщика «" + d.getName() + "» не выбраны позиции");
            }
            List<TenderLot> lots = lots(t, s.getLotIds());
            plan.merge(d.getId(), lots, (a, b) -> {
                List<TenderLot> merged = new ArrayList<>(a);
                for (TenderLot l : b) if (!merged.contains(l)) merged.add(l);
                return merged;
            });
        }
        SendOptions options = new SendOptions(req.getReplyBy());
        List<SendResult> results = new ArrayList<>();
        plan.forEach((distributorId, lots) ->
                results.addAll(sendService.send(t.getId(), List.of(distributorId), items(lots), null, null, options)));
        return results;
    }

    private Distributor distributor(Long id) {
        if (id == null) throw new BadRequestException("Не указан поставщик");
        Distributor d = distributorService.findById(id);
        if (d.getMarket() != null && d.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Поставщик не найден: " + id);
        }
        return d;
    }

    private static List<TenderLot> lots(Tender t, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        Map<Long, TenderLot> own = new HashMap<>();
        for (TenderLot l : t.getLots()) own.put(l.getId(), l);
        List<TenderLot> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            TenderLot l = own.get(id);
            if (l == null) throw new BadRequestException("Позиция " + id + " не из этой заявки");
            out.add(l);
        }
        return out;
    }

    private static List<SendItem> items(List<TenderLot> lots) {
        return lots.stream().map(l -> new SendItem(l.getId(), null, l.getQuantity())).toList();
    }

    private static void checkReplyBy(LocalDate replyBy) {
        if (replyBy != null && replyBy.isBefore(LocalDate.now(MarketContext.get().zone()))) {
            throw new BadRequestException("Дата «Ответить до» уже прошла");
        }
    }
}
```

- [ ] **Step 8: Контроллер**

В `PrivateRequestController.java`:
- добавить зависимость `PrivateRequestRfqService rfqService` (поле, параметр конструктора; импорты `com.vladoose.nir.service.privaterequest.PrivateRequestRfqService`, `com.vladoose.nir.service.PriceRequestSendService`);
- добавить эндпоинты:

```java
    @PostMapping("/{id}/rfq/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public KpPreviewResponse rfqPreview(@PathVariable Long id, @Valid @RequestBody RfqPreviewRequest req) {
        return rfqService.preview(id, req);
    }

    @PostMapping("/{id}/rfq/send")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PriceRequestSendService.SendResult> rfqSend(@PathVariable Long id, @Valid @RequestBody RfqSendRequest req) {
        return rfqService.send(id, req);
    }
```

- [ ] **Step 9: Запустить — зелёные, тендерная отправка не сломана**

Перед прогоном убедиться, что 127.0.0.1:3025 свободен (`lsof -ti tcp:3025 -sTCP:LISTEN` — пусто; `./gradlew devMail` держит его — остановить).
Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqTest' --tests 'com.vladoose.nir.service.PriceRequestSendServiceTest' --tests 'com.vladoose.nir.email.*' --tests 'com.vladoose.nir.mail.*'`
Expected: PASS.

- [ ] **Step 10: Мутация «клиент и номер заявки не в письме»**

Копия `KpEmailComposer.java` в `/tmp`. В `buildPositions` в ветке голого лота добавить `sb.append(" [").append(lot.getTender().getTenderNumber()).append("]");` после наименования.
Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqTest'`
Expected: FAIL `eachSupplierGetsOneLetterWithOnlyItsLines_letterOutcomeIsStored` (`doesNotContain(t.getTenderNumber())`). Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 11: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/PriceRequestSendService.java src/main/java/com/vladoose/nir/service/PriceRequestWriter.java src/main/java/com/vladoose/nir/service/KpEmailComposer.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java src/main/java/com/vladoose/nir/dto/request/RfqPreviewRequest.java src/main/java/com/vladoose/nir/dto/request/RfqSendRequest.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/support/SmtpIntegrationTest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRfqTest.java src/test/java/com/vladoose/nir/service/PriceRequestSendServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): запрос цен — письмо каждому поставщику с его позициями, «ответить до», исход письма в запросе

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Таблица цен, права записи ответов, ссылки уведомлений почты

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java`
- Modify: `src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java` (`findBouncedPriceRequestIds`)
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java` (`GET /{id}/prices`)
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java` (права и гарды записи)
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailNotificationComposer.java` (ссылка и совет при возврате)
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestPricesTest.java`, `PriceRequestResponsesGuardTest.java`
- Modify test: `src/test/java/com/vladoose/nir/service/mail/MailNotificationComposerTest.java`, `MailIngestWriterTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.findById`, `LetterStatus` (задачи 1, 2, 8).
- Produces:
  - **`PrivateRequestPricesService.build(Long requestId)`** → `PrivateRequestPricesResponse(List<Supplier> suppliers, List<Line> lines)`:
    - `Supplier(distributorId, name, firstSentAt)`;
    - `Line(lotId, name, quantity, unit, List<Cell> cells, bestPrice, bestDistributorName)`;
    - `Cell(distributorId, state, price, note, priceRequestId, itemId, best)`, где `state` — `PRICE | DECLINED | BOUNCED | NOT_SENT | WAITING | NONE`.
  - **REST:** `GET /api/private-requests/{id}/prices`; записи `PriceRequestController` — только ADMIN; `PUT /{id}/responses` — 404 на чужой рынок, 400 на строку чужого запроса.
  - **Ссылка уведомления о частной заявке** — `/private-requests/{id}`.

- [ ] **Step 1: Падающий тест таблицы цен**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestPricesTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Cell;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestPricesService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestPricesTest {

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestPricesService prices;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired InboundEmailRepository inboundRepository;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email("p@zz.kz").build());
    }

    private PriceRequestItem ask(Tender t, Distributor d, TenderLot lot, String status, LetterStatus letter, String price) {
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status(status)
                .letterStatus(letter).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot)
                .requestedQuantity(1).responsePrice(price == null ? null : new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private static Cell cell(PrivateRequestPricesResponse r, int line, Distributor d) {
        return r.lines().get(line).cells().stream().filter(c -> c.distributorId().equals(d.getId())).findFirst().orElseThrow();
    }

    @Test
    void cellStatesBestPriceAndSupplierOrder() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены А", null, 2, "упак", null, null),
                input("ZZ Цены Б", null, 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
        Distributor cheap = dist("ZZ Цены Дешёвый");
        Distributor dear = dist("ZZ Цены Дорогой");
        Distributor declined = dist("ZZ Цены Отказ");
        Distributor bounced = dist("ZZ Цены Возврат");
        Distributor noMail = dist("ZZ Цены Без письма");

        ask(t, dear, lots.get(0), "RESPONDED", LetterStatus.SENT, "20000.00");
        ask(t, cheap, lots.get(0), "RESPONDED", LetterStatus.SENT, "18400.00");
        ask(t, declined, lots.get(0), "DECLINED", LetterStatus.SENT, null);
        PriceRequestItem b = ask(t, bounced, lots.get(1), "SENT", LetterStatus.SENT, null);
        ask(t, noMail, lots.get(1), "SENT", LetterStatus.NO_EMAIL, null);
        inboundRepository.save(InboundEmail.builder().fromAddress("MAILER-DAEMON@mail.ru").subject("Undelivered")
                .type(InboundType.BOUNCE).matchedPriceRequestId(b.getPriceRequest().getId()).status(InboundStatus.NEW).build());
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(r.suppliers()).extracting(PrivateRequestPricesResponse.Supplier::distributorId)
                .containsExactly(dear.getId(), cheap.getId(), declined.getId(), bounced.getId(), noMail.getId());
        assertThat(r.lines().get(0).unit()).isEqualTo("упак");
        assertThat(cell(r, 0, cheap).state()).isEqualTo("PRICE");
        assertThat(cell(r, 0, cheap).best()).isTrue();
        assertThat(cell(r, 0, dear).best()).isFalse();
        assertThat(r.lines().get(0).bestPrice()).isEqualByComparingTo("18400.00");
        assertThat(r.lines().get(0).bestDistributorName()).isEqualTo(cheap.getName());
        assertThat(cell(r, 0, declined).state()).isEqualTo("DECLINED");
        assertThat(cell(r, 0, bounced).state()).isEqualTo("NONE");
        assertThat(cell(r, 1, bounced).state()).isEqualTo("BOUNCED");
        assertThat(cell(r, 1, noMail).state()).isEqualTo("NOT_SENT");
        assertThat(cell(r, 1, cheap).state()).isEqualTo("NONE");
        assertThat(r.lines().get(1).bestPrice()).isNull();
    }

    @Test
    void latestRequestToSameSupplierWins() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены повтор", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist("ZZ Цены Повтор");
        ask(t, d, lot, "RESPONDED", LetterStatus.SENT, "100.00");
        ask(t, d, lot, "SENT", LetterStatus.SENT, null);
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(r.suppliers()).hasSize(1);
        assertThat(cell(r, 0, d).state()).isEqualTo("WAITING");   // повторный запрос свежее прежнего ответа
    }

    @Test
    void priceEnteredAfterDeclineCounts() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены передумал", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist("ZZ Цены Передумал");
        ask(t, d, lot, "DECLINED", LetterStatus.SENT, "15000.00");   // отказ письмом, потом цена по телефону
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(cell(r, 0, d).state()).isEqualTo("PRICE");
        assertThat(cell(r, 0, d).best()).isTrue();
        assertThat(r.lines().get(0).bestPrice()).isEqualByComparingTo("15000.00");
    }
}
```

Если у `InboundEmail` другой набор обязательных полей — посмотреть сущность и заполнить их так же, как это делает `MailIngestWriterTest` (там строки «Входящих» строятся в тестах).

- [ ] **Step 2: Падающий тест прав записи ответов**

`src/test/java/com/vladoose/nir/privaterequest/PriceRequestResponsesGuardTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Таблица цен пишет через PUT /api/price-requests/{id}/responses — запись только админу, строка — только своего запроса. */
@SpringBootTest
@Transactional
class PriceRequestResponsesGuardTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    MockMvc mvc;

    PriceRequest prA;
    PriceRequestItem itemA;
    PriceRequestItem itemB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-GUARD-1").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Гард").quantity(1).build());
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Гард " + System.nanoTime()).build());
        prA = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        PriceRequest prB = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        itemA = itemRepository.save(PriceRequestItem.builder().priceRequest(prA).tenderLot(lot).requestedQuantity(1).build());
        itemB = itemRepository.save(PriceRequestItem.builder().priceRequest(prB).tenderLot(lot).requestedQuantity(1).build());
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private String body(Long itemId) {
        return "[{\"itemId\":" + itemId + ",\"responsePrice\":100,\"responseNote\":null}]";
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotWrite() throws Exception {
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemA.getId()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/price-requests/" + prA.getId() + "/close").header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/price-requests/" + prA.getId()).header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void itemOfAnotherRequestIs400_otherMarketIs404_ownItemIs200() throws Exception {
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemB.getId()))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "RF")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemA.getId()))).andExpect(status().isNotFound());
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemA.getId()))).andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Запустить — падают**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestPricesTest' --tests 'com.vladoose.nir.privaterequest.PriceRequestResponsesGuardTest'`
Expected: FAIL. Таблица — не компилируется (нет `PrivateRequestPricesService`). Гард — 200 вместо 403 / 400 / 404.

- [ ] **Step 4: Возвраты по запросам**

`InboundEmailRepository.java` — добавить (импорт `java.util.Collection`):

```java
    /**
     * Запросы цен, на письмо которых пришёл возврат. Нативный SQL рыночным фильтром не режется — и не должен: возврат на
     * запрос РФ лежит строкой ящика KZ (R17, CLAUDE.md §8).
     */
    @Query(value = "SELECT DISTINCT matched_price_request_id FROM inbound_email WHERE type = 'BOUNCE' "
            + "AND matched_price_request_id IN (:ids)", nativeQuery = true)
    List<Long> findBouncedPriceRequestIds(@Param("ids") Collection<Long> ids);
```

- [ ] **Step 5: DTO и сервис таблицы цен**

`src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Таблица цен заявки (спека §7): позиции × поставщики, кому писали; лучшая цена по позиции. */
public record PrivateRequestPricesResponse(List<Supplier> suppliers, List<Line> lines) {

    public record Supplier(Long distributorId, String name, OffsetDateTime firstSentAt) {}

    public record Line(Long lotId, String name, Integer quantity, String unit, List<Cell> cells,
                       BigDecimal bestPrice, String bestDistributorName) {}

    /** state: PRICE | DECLINED | BOUNCED | NOT_SENT | WAITING | NONE (позицию у поставщика не запрашивали). */
    public record Cell(Long distributorId, String state, BigDecimal price, String note, Long priceRequestId,
                       Long itemId, boolean best) {}
}
```

`src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java`:

```java
package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Cell;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Line;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Таблица цен частной заявки (спека §7). Колонки — поставщики в порядке первой отправки. Ячейка — последняя строка
 * запроса этого поставщика по позиции. Состояния по убыванию: цена → отказ → возврат письма → письмо не ушло →
 * ждём ответа (цена, вписанная после отказа, — цена); не запрашивали — NONE. Лучшая — наименьшая цена; при равенстве — более ранний поставщик.
 */
@Service
public class PrivateRequestPricesService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestItemRepository itemRepository;
    private final InboundEmailRepository inboundRepository;

    public PrivateRequestPricesService(PrivateRequestService privateRequestService, PriceRequestItemRepository itemRepository,
                                       InboundEmailRepository inboundRepository) {
        this.privateRequestService = privateRequestService;
        this.itemRepository = itemRepository;
        this.inboundRepository = inboundRepository;
    }

    @Transactional(readOnly = true)
    public PrivateRequestPricesResponse build(Long requestId) {
        Tender t = privateRequestService.findById(requestId);
        List<PriceRequestItem> items = new ArrayList<>(itemRepository.findByTenderId(requestId));
        items.sort(Comparator.comparing((PriceRequestItem i) -> i.getPriceRequest().getId()).thenComparing(PriceRequestItem::getId));

        Map<Long, Supplier> suppliers = new LinkedHashMap<>();
        Map<String, PriceRequestItem> latest = new HashMap<>();
        Set<Long> prIds = new LinkedHashSet<>();
        for (PriceRequestItem i : items) {
            PriceRequest pr = i.getPriceRequest();
            Distributor d = pr.getDistributor();
            suppliers.putIfAbsent(d.getId(), new Supplier(d.getId(), d.getName(), pr.getSentAt()));
            latest.put(key(i.getTenderLot().getId(), d.getId()), i);   // отсортировано по запросу — последний свежее
            prIds.add(pr.getId());
        }
        Set<Long> bounced = prIds.isEmpty() ? Set.of() : new HashSet<>(inboundRepository.findBouncedPriceRequestIds(prIds));

        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()));
        List<Line> lines = new ArrayList<>();
        for (TenderLot lot : lots) {
            List<Cell> cells = new ArrayList<>();
            Cell best = null;
            for (Supplier s : suppliers.values()) {
                Cell c = cell(s.distributorId(), latest.get(key(lot.getId(), s.distributorId())), bounced);
                cells.add(c);
                if ("PRICE".equals(c.state()) && (best == null || c.price().compareTo(best.price()) < 0)) best = c;
            }
            final Cell winner = best;
            List<Cell> marked = cells.stream()
                    .map(c -> c == winner ? new Cell(c.distributorId(), c.state(), c.price(), c.note(), c.priceRequestId(),
                            c.itemId(), true) : c)
                    .toList();
            BigDecimal bestPrice = winner == null ? null : winner.price();
            String bestName = winner == null ? null : suppliers.get(winner.distributorId()).name();
            lines.add(new Line(lot.getId(), lot.getEquipName(), lot.getQuantity(), lot.getUnit(), marked, bestPrice, bestName));
        }
        return new PrivateRequestPricesResponse(new ArrayList<>(suppliers.values()), lines);
    }

    static Cell cell(Long distributorId, PriceRequestItem i, Set<Long> bounced) {
        if (i == null) return new Cell(distributorId, "NONE", null, null, null, null, false);
        PriceRequest pr = i.getPriceRequest();
        String state;
        // цена — сильнейший факт: отказ письмом, а потом цена по телефону — это цена (и лучшая, если ниже других)
        if (i.getResponsePrice() != null) state = "PRICE";
        else if ("DECLINED".equals(pr.getStatus())) state = "DECLINED";
        else if (bounced.contains(pr.getId())) state = "BOUNCED";
        else if (pr.getLetterStatus() == LetterStatus.NO_EMAIL || pr.getLetterStatus() == LetterStatus.FAILED) state = "NOT_SENT";
        else state = "WAITING";
        return new Cell(distributorId, state, i.getResponsePrice(), i.getResponseNote(), pr.getId(), i.getId(), false);
    }

    private static String key(Long lotId, Long distributorId) {
        return lotId + ":" + distributorId;
    }
}
```

В `PrivateRequestController.java` (зависимость `PrivateRequestPricesService pricesService` — поле, конструктор, импорт):

```java
    @GetMapping("/{id}/prices")
    public PrivateRequestPricesResponse prices(@PathVariable Long id) {
        return pricesService.build(id);
    }
```

- [ ] **Step 6: Права и гарды записи ответов**

В `PriceRequestController.java`:
- добавить `@PreAuthorize("hasRole('ADMIN')")` к методам `create` (`POST /`), `update` (`PUT /{id}`), `updateResponses` (`PUT /{id}/responses`), `close`, `accept`, `delete`;
- импорты `com.vladoose.nir.context.MarketContext`, `com.vladoose.nir.exception.NotFoundException`;
- в `updateResponses` после `PriceRequest pr = service.findById(id);`:

```java
        // findById = em.find — фильтр рынка его не режет (§14): запрос другого рынка — как несуществующий
        if (pr.getMarket() != null && pr.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Запрос КП не найден: " + id);
        }
```

- в цикле сразу после загрузки `item`:

```java
            if (item.getPriceRequest() == null || !item.getPriceRequest().getId().equals(id)) {
                throw new BadRequestException("Позиция " + u.itemId() + " не из этого запроса КП");
            }
```

- [ ] **Step 7: Ссылка и совет в уведомлении о частной заявке**

В `MailNotificationComposer.java`:
- `kpLink`:

```java
    private static String kpLink(KpSnapshot kp, ComposeContext ctx) {
        return link(ctx, kp.privateRequest() ? "/private-requests/" + kp.tenderId() : "/tenders?openId=" + kp.tenderId());
    }
```

- совет при возврате:

```java
            head.append(kp.privateRequest()
                    ? "Исправьте адрес в карточке поставщика и нажмите «Переслать» в таблице цен заявки.\n"
                    : "Исправьте адрес в карточке поставщика и нажмите «Переслать» в запросах КП тендера.\n");
```

(комментарий над ним — «↻ Переслать» есть и у тендера, и в таблице цен частной заявки).

В `MailNotificationComposerTest.java`:
- `privateRequest_labelAndLink` → `.endsWith("https://ais.westmed.kz/private-requests/42?market=KZ");`;
- в `bounce_privateRequest_hintLeadsToRequestCard` строку совета → `Исправьте адрес в карточке поставщика и нажмите «Переслать» в таблице цен заявки.`, ссылку → `Открыть в АИС: https://ais.westmed.kz/private-requests/42?market=KZ`.

В `MailIngestWriterTest.privateRequest_linkToPrivateRequests` → `.endsWith("/private-requests/" + p.getTender().getId() + "?market=KZ");`.

Старые ссылки `?openId=` в уже отправленных сообщениях Telegram продолжают работать: список заявок перенаправляет их на страницу (задача 11).

- [ ] **Step 8: Запустить — зелёные**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.service.mail.*' --tests 'com.vladoose.nir.mail.*' --tests 'com.vladoose.nir.email.*'`
Expected: PASS.

- [ ] **Step 9: Мутации**

1. Копия `PriceRequestController.java` в `/tmp`. Убрать проверку «строка не из этого запроса».
   Expected: FAIL `itemOfAnotherRequestIs400_otherMarketIs404_ownItemIs200`. Вернуть копией.
2. Копия `PrivateRequestPricesService.java` в `/tmp`. В `cell(...)` убрать ветку `else if (bounced.contains(pr.getId())) state = "BOUNCED";`.
   Expected: FAIL `cellStatesBestPriceAndSupplierOrder` (`BOUNCED` → `WAITING`). Вернуть копией.
3. Там же поменять местами первые две ветки (`DECLINED` раньше `PRICE`).
   Expected: FAIL `priceEnteredAfterDeclineCounts`. Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 10: Полный гейт бэкенда**

Run (sandbox off): `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test`
Expected: BUILD SUCCESSFUL, 0 падений. Число тестов записать в леджер (было 1354 до этапа).

- [ ] **Step 11: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/main/java/com/vladoose/nir/controller/PriceRequestController.java src/main/java/com/vladoose/nir/service/mail/MailNotificationComposer.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestPricesTest.java src/test/java/com/vladoose/nir/privaterequest/PriceRequestResponsesGuardTest.java src/test/java/com/vladoose/nir/service/mail/MailNotificationComposerTest.java src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): таблица цен с возвратами и «письмо не ушло»; запись ответов — только админ и своя строка

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Фронт — каркас: API, типы, список заявок, страница с шапкой

**Files:**
- Create: `frontend/src/app/shared/private-request.ts`, `frontend/src/app/shared/error-text.ts`
- Modify: `frontend/src/app/services/api.service.ts` (раздел «Частные заявки», `convertLead`)
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/styles.scss` (чип этапа `.pr-stage`)
- Modify: `frontend/src/app/pages/private-requests/private-requests.component.ts` (переписать целиком)
- Create: `frontend/src/app/pages/private-requests/private-request-page.component.ts`
- Delete: `frontend/src/app/pages/private-requests/private-request-card.component.ts`

**Interfaces:**
- Consumes: REST задач 2–9.
- Produces:
  - **`shared/private-request.ts`:**
    - типы `PrView`, `PrLine`, `PrListItem`, `PrStatus`, `RegStatus`, `RegistryRecord`, `PrField`, `PrLineField`, `LinesMode`, `RegAction`, `LineInput`, `ParsedLine`, `SiteProduct`, `Sourcing`, `SourcingSupplier`, `SourcingLine`, `SendResult`, `Prices`, `PriceLine`, `PriceCell`, `PriceSupplier`, `CellState`, `SaveState`;
    - функции `stageTone`, `regLabel`, `regValidity`, `dateText`, `timeText`, `plural`;
    - константы `CLOSE_OUTCOMES`, `UNIT_SUGGESTIONS`.
  - **`shared/error-text.ts`:** `errorText(e)`.
  - **`ApiService`:**
    - заявка: `getPrivateRequests({status, q})`, `getPrivateRequest(id)`, `createPrivateRequest()`, `updatePrivateRequestField(id, field, value)`, `savePrivateRequestClient(id)`, `closePrivateRequest(id, outcome)`, `reopenPrivateRequest(id)`, `deletePrivateRequest(id)`;
    - позиции и РУ: `addPrivateRequestLines(id, lines, mode)`, `parsePrivateRequestLines(id, text)`, `updatePrivateRequestLine(id, lotId, field, value)`, `deletePrivateRequestLine(id, lotId)`, `setPrivateRequestLineRegistry(id, lotId, action, regNumber?)`, `getPrivateRequestLineCandidates(id, lotId)`, `checkPrivateRequestRegistry(id)`, `importPrivateRequestLines(id, body)`;
    - поставщики и цены: `getPrivateRequestSourcing(id)`, `previewRfq(id, body)`, `sendRfq(id, body)`, `getPrivateRequestPrices(id)`;
    - прочее: `searchSiteProducts(q)`, `convertLead(id, body = {})`.
  - **Маршруты:** `/private-requests` и `/private-requests/:id` — ленивые.
  - **`PrivateRequestPageComponent`:** поля `view: PrView | null`, методы `onView(v)`, `onSaveState(s)`; в шаблоне якорь `<!-- sections -->` — после блока «Из обращения», сюда вставляют разделы задачи 11, 13 и 14.

- [ ] **Step 1: Общие типы и функции**

`frontend/src/app/shared/error-text.ts`:

```ts
/**
 * Текст ошибки сервера (ApiError.message — его несут все ответы бэкенда, и 5xx тоже). Нет ответа или 5xx без него —
 * прокси перед лежащим бэкендом: «нет связи с сервером».
 */
export function errorText(e: any): string {
  if (e?.error?.message) return e.error.message;
  return !e?.status || e.status >= 500 ? 'нет связи с сервером' : `ошибка ${e.status}`;
}
```

`frontend/src/app/shared/private-request.ts`:

```ts
/**
 * Частная заявка — типы, подписи и форматы страницы (спека docs/superpowers/specs/2026-10-12-private-request-workspace-design.md).
 * Импортируют ленивые страницы; ApiService — только `import type` (начальный бандл у предела, CLAUDE.md §12).
 */

export type PrStatus = 'NEW' | 'WON' | 'LOST' | 'CANCELLED';
export type RegStatus = 'SUGGESTED' | 'CONFIRMED' | 'NOT_FOUND' | 'NOT_REQUIRED' | null;
export type PrField = 'FACILITY' | 'CUSTOMER_NAME' | 'CONTACT_NAME' | 'CONTACT_PHONE' | 'CONTACT_EMAIL' | 'DELIVERY_ADDRESS' | 'NOTE';
export type PrLineField = 'NAME' | 'BRAND' | 'QUANTITY' | 'UNIT' | 'SPEC';
export type LinesMode = 'APPEND' | 'REPLACE';
export type RegAction = 'CONFIRM' | 'NOT_REQUIRED' | 'RESET';

export interface RegistryRecord {
  regNumber: string;
  name: string;
  producer: string | null;
  country: string | null;
  regDate: string | null;
  expirationDate: string | null;
  unlimited: boolean | null;
  score?: number | null;
}

export interface PrLine {
  lotId: number;
  lineNo: number;
  name: string;
  brand: string | null;
  quantity: number | null;
  unit: string | null;
  spec: string | null;
  productUrl: string | null;
  regStatus: RegStatus;
  registry: RegistryRecord | null;
  requested: boolean;
}

export interface LeadRef { id: number; subject: string; source: string; status: string; receivedAt: string; }

export interface PrView {
  id: number;
  number: string;
  status: PrStatus;
  stage: string;
  stageText: string;
  facilityId: number | null;
  facilityName: string | null;
  customerName: string | null;
  clientLabel: string;
  contactName: string | null;
  contactPhone: string | null;
  contactEmail: string | null;
  deliveryAddress: string | null;
  note: string | null;
  createdAt: string | null;
  lead: LeadRef | null;
  lines: PrLine[];
}

export interface PrListItem {
  id: number;
  number: string;
  status: PrStatus;
  stage: string;
  stageText: string;
  clientLabel: string;
  lineCount: number;
  createdAt: string | null;
  leadId: number | null;
}

export interface LineInput {
  name: string;
  brand?: string | null;
  quantity?: number | null;
  unit?: string | null;
  spec?: string | null;
  productUrl?: string | null;
}

export interface ParsedLine { name: string; quantity: number; unit: string | null; }
export interface SiteProduct { name: string; brand: string | null; sku: string | null; url: string | null; }

export interface SourcingLine {
  lotId: number;
  name: string;
  quantity: number | null;
  unit: string | null;
  reason: 'BRAND' | 'TYPE';
  label: string;
  preselected: boolean;
  requestedAt: string | null;
}
export interface SourcingSupplier {
  distributorId: number;
  name: string;
  email: string | null;
  hasEmail: boolean;
  preselected: boolean;
  lines: SourcingLine[];
}
export interface Sourcing { suppliers: SourcingSupplier[]; unmatched: { lotId: number; name: string; brand: string | null }[]; }

export interface SendResult {
  priceRequestId: number;
  distributorId: number;
  distributorName: string;
  emailSent: boolean;
  reason: string | null;
}

export type CellState = 'PRICE' | 'DECLINED' | 'BOUNCED' | 'NOT_SENT' | 'WAITING' | 'NONE';
export interface PriceCell {
  distributorId: number;
  state: CellState;
  price: number | null;
  note: string | null;
  priceRequestId: number | null;
  itemId: number | null;
  best: boolean;
}
export interface PriceLine {
  lotId: number;
  name: string;
  quantity: number | null;
  unit: string | null;
  cells: PriceCell[];
  bestPrice: number | null;
  bestDistributorName: string | null;
}
export interface PriceSupplier { distributorId: number; name: string; firstSentAt: string | null; }
export interface Prices { suppliers: PriceSupplier[]; lines: PriceLine[]; }

/**
 * Сохранение по выходу из поля: разделы сообщают странице, та показывает «Сохраняю… / Сохранено · 14:05 / Не сохранено —
 * повторить» (спека §5.2). `retry` — только у идемпотентных правок поля (PATCH); добавление позиций не повторяется
 * (ответ мог потеряться после записи — повтор задвоил бы позиции).
 */
export interface SaveState { kind: 'saving' | 'saved' | 'error'; message?: string; retry?: () => void; }

/** Тон чипа этапа — data-tone для глобального .pr-stage (styles.scss). */
export function stageTone(stage: string | null | undefined): string {
  switch (stage) {
    case 'REQUESTED': return 'accent';
    case 'PRICED': return 'warn';
    case 'WON': return 'success';
    case 'LOST': return 'danger';
    case 'CANCELLED':
    case 'EMPTY': return 'muted';
    default: return 'neutral';
  }
}

export const CLOSE_OUTCOMES: { v: PrStatus; l: string }[] = [
  { v: 'WON', l: 'купили у нас' },
  { v: 'LOST', l: 'не купили' },
  { v: 'CANCELLED', l: 'отменена' },
];

export const UNIT_SUGGESTIONS = ['шт', 'набор', 'упак', 'компл', 'фл', 'л', 'кг', 'м', 'пара'];

/** Метка РУ позиции (спека §5.3). «РУ не найдено», а не «нет в реестре»: у позиции без бренда подсказки нет, а изделие в реестре есть. */
export function regLabel(s: RegStatus): string {
  switch (s) {
    case 'CONFIRMED': return 'РУ ✓';
    case 'SUGGESTED': return 'РУ?';
    case 'NOT_FOUND': return 'РУ не найдено';
    case 'NOT_REQUIRED': return 'не подлежит';
    default: return 'РУ —';
  }
}

/** «бессрочно» / «до 07.04.2031» / пусто. */
export function regValidity(r: RegistryRecord | null | undefined): string {
  if (!r) return '';
  if (r.unlimited) return 'бессрочно';
  return r.expirationDate ? 'до ' + dateText(r.expirationDate) : '';
}

/** «2026-10-12» или ISO-время → «12.10.2026»; пусто для null. Дата без времени не уезжает на день в чужом поясе. */
export function dateText(iso: string | null | undefined): string {
  if (!iso) return '';
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (m) return `${m[3]}.${m[2]}.${m[1]}`;
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '' : d.toLocaleDateString('ru-RU');
}

/** «14:05» — для индикатора сохранения. */
export function timeText(d: Date = new Date()): string {
  return d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

/** plural(5, 'поставщик', 'поставщика', 'поставщиков') → «поставщиков». */
export function plural(n: number, one: string, few: string, many: string): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}
```

- [ ] **Step 2: `ApiService`**

В начало `frontend/src/app/services/api.service.ts` добавить:

```ts
import type {
  LineInput, LinesMode, ParsedLine, PrField, PrLine, PrLineField, PrListItem, PrView, Prices, RegAction,
  RegistryRecord, SendResult, SiteProduct, Sourcing,
} from '../shared/private-request';
```

Раздел `// === Частные заявки ===` (методы `getPrivateRequests` … `commitImport`) заменить целиком:

```ts
  // === Частные заявки (страница-рабочее место, спека 2026-10-12) ===
  getPrivateRequests(params: { status?: string; q?: string } = {}): Observable<PrListItem[]> {
    const qs = new URLSearchParams();
    if (params.status) qs.set('status', params.status);
    if (params.q) qs.set('q', params.q);
    const s = qs.toString();
    return this.http.get<PrListItem[]>(`${this.base}/private-requests${s ? '?' + s : ''}`);
  }
  getPrivateRequest(id: number): Observable<PrView> {
    return this.http.get<PrView>(`${this.base}/private-requests/${id}`);
  }
  createPrivateRequest(): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests`, {});
  }
  updatePrivateRequestField(id: number, field: PrField, value: string | null): Observable<PrView> {
    return this.http.patch<PrView>(`${this.base}/private-requests/${id}`, { field, value });
  }
  savePrivateRequestClient(id: number): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/save-client`, {});
  }
  closePrivateRequest(id: number, outcome: string): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/close`, { outcome });
  }
  reopenPrivateRequest(id: number): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/reopen`, {});
  }
  deletePrivateRequest(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/private-requests/${id}`);
  }
  addPrivateRequestLines(id: number, lines: LineInput[], mode: LinesMode = 'APPEND'): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/lines`, { lines, mode });
  }
  parsePrivateRequestLines(id: number, text: string): Observable<ParsedLine[]> {
    return this.http.post<ParsedLine[]>(`${this.base}/private-requests/${id}/lines/parse`, { text });
  }
  updatePrivateRequestLine(id: number, lotId: number, field: PrLineField, value: string | null): Observable<PrLine> {
    return this.http.patch<PrLine>(`${this.base}/private-requests/${id}/lines/${lotId}`, { field, value });
  }
  deletePrivateRequestLine(id: number, lotId: number): Observable<PrView> {
    return this.http.delete<PrView>(`${this.base}/private-requests/${id}/lines/${lotId}`);
  }
  setPrivateRequestLineRegistry(id: number, lotId: number, action: RegAction, regNumber?: string): Observable<PrLine> {
    return this.http.post<PrLine>(`${this.base}/private-requests/${id}/lines/${lotId}/registry`,
      { action, regNumber: regNumber ?? null });
  }
  getPrivateRequestLineCandidates(id: number, lotId: number): Observable<RegistryRecord[]> {
    return this.http.get<RegistryRecord[]>(`${this.base}/private-requests/${id}/lines/${lotId}/registry-candidates`);
  }
  checkPrivateRequestRegistry(id: number): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/registry-check`, {});
  }
  importPrivateRequestLines(id: number, body: { mappings: any[]; lines: any[]; mode: LinesMode }): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/lines/import`, body);
  }
  getPrivateRequestSourcing(id: number): Observable<Sourcing> {
    return this.http.get<Sourcing>(`${this.base}/private-requests/${id}/sourcing`);
  }
  previewRfq(id: number, body: { distributorId: number; lotIds: number[]; replyBy: string | null }):
      Observable<{ subject: string; body: string }> {
    return this.http.post<{ subject: string; body: string }>(`${this.base}/private-requests/${id}/rfq/preview`, body);
  }
  sendRfq(id: number, body: { replyBy: string | null; suppliers: { distributorId: number; lotIds: number[] }[] }):
      Observable<SendResult[]> {
    return this.http.post<SendResult[]>(`${this.base}/private-requests/${id}/rfq/send`, body);
  }
  getPrivateRequestPrices(id: number): Observable<Prices> {
    return this.http.get<Prices>(`${this.base}/private-requests/${id}/prices`);
  }
  searchSiteProducts(q: string): Observable<SiteProduct[]> {
    return this.http.get<SiteProduct[]>(`${this.base}/site-products?q=${encodeURIComponent(q)}`);
  }

  previewImport(file: File): Observable<any> {
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<any>(`${this.base}/private-requests/import/preview`, fd);
  }

  commitImport(body: any): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/import/commit`, body);
  }
```

Метод `convertLead` заменить:

```ts
  /** Пустое тело — «Создать заявку» одной кнопкой (спека 2026-10-12 §4). */
  convertLead(id: number, body: any = {}): Observable<{ privateRequestId: number; number: string }> {
    return this.http.post<{ privateRequestId: number; number: string }>(`${this.base}/leads/${id}/convert`, body);
  }
```

- [ ] **Step 3: Маршруты**

В `app.routes.ts`:
- удалить импорт `PrivateRequestsComponent`;
- строку `{ path: 'private-requests', component: PrivateRequestsComponent },` заменить:

```ts
      { path: 'private-requests',
        loadComponent: () => import('./pages/private-requests/private-requests.component').then(m => m.PrivateRequestsComponent) },
      { path: 'private-requests/:id',
        loadComponent: () => import('./pages/private-requests/private-request-page.component').then(m => m.PrivateRequestPageComponent) },
```

- [ ] **Step 4: Чип этапа в kit**

В `frontend/src/styles.scss` после правил `.badge-pr-DECLINED` (блок бейджей):

```scss
/* --- этап частной заявки (список и страница заявки): правило чипа — 15 % тинта + текстовый токен --- */
.pr-stage { display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600;
            background: var(--surface-2); color: var(--text); white-space: nowrap; }
.pr-stage[data-tone="accent"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
.pr-stage[data-tone="warn"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
.pr-stage[data-tone="success"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
.pr-stage[data-tone="danger"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
/* «Пустая» / «отменена»: без заливки — --text-muted на --surface-2 даёт 4,39:1 (ниже 4,5 для 12 px), на --surface — читается */
.pr-stage[data-tone="muted"] { background: transparent; border: 1px solid var(--border); color: var(--text-muted); }
```

- [ ] **Step 5: Список заявок — переписать целиком**

`frontend/src/app/pages/private-requests/private-requests.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, OnDestroy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';
import { PrListItem, dateText, stageTone } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';

/**
 * «Частные заявки» — список (спека 2026-10-12 §5.5): «в работе / закрытые / все», поиск, «+ Новая заявка» сразу создаёт
 * черновик и открывает его страницу. Старые ссылки «?openId=» перенаправляются на страницу заявки.
 */
@Component({
  selector: 'app-private-requests',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <div>
        <h2>Частные заявки</h2>
        <p class="subtitle">Заявки клиентов {{ company }}: позиции, регистрация, цены поставщиков</p>
      </div>
      <button type="button" class="btn btn-primary" *ngIf="auth.isAdmin()" [disabled]="creating" (click)="create()">
        {{ creating ? 'Создаю…' : '+ Новая заявка' }}
      </button>
    </div>

    <div class="filters">
      <div class="chips" role="group" aria-label="Какие заявки показать">
        <button type="button" class="chip" *ngFor="let f of filters" [class.on]="status === f.key"
                [attr.aria-pressed]="status === f.key" (click)="setStatus(f.key)">{{ f.label }}</button>
      </div>
      <input type="search" [(ngModel)]="q" (input)="onSearch()" placeholder="Номер, клиент, телефон или позиция…"
             aria-label="Поиск заявок" />
    </div>

    <div class="error-banner load-error" *ngIf="loadError">
      <span>Список не загрузился: {{ loadError }}</span>
      <button type="button" class="btn btn-line" (click)="load()">Повторить</button>
    </div>
    <div class="empty" *ngIf="loading && !rows.length">Загрузка…</div>
    <div class="empty" *ngIf="!loading && !loadError && !rows.length">{{ emptyText() }}</div>

    <div class="list">
      <article class="card" *ngFor="let r of rows; trackBy: trackById">
        <div class="top">
          <!-- вся карточка — эта ссылка (::after растянут на карточку): Enter, новая вкладка, «копировать ссылку» -->
          <a class="num" [routerLink]="['/private-requests', r.id]" [attr.aria-label]="'Открыть заявку ' + r.number">{{ r.number }}</a>
          <span class="pr-stage" [attr.data-tone]="tone(r.stage)">{{ r.stageText }}</span>
          <span class="lead-mark" *ngIf="r.leadId">из обращения</span>
          <span class="date">{{ dateText(r.createdAt) }}</span>
        </div>
        <div class="client" [class.muted]="!r.clientLabel || r.clientLabel === 'Клиент не указан'">{{ r.clientLabel }}</div>
        <div class="bottom">{{ r.lineCount }} поз.</div>
      </article>
    </div>
  `,
  styles: [`
    :host { display: block; max-width: 1100px; }
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .filters { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .filters input { min-width: 280px; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .load-error { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .card { position: relative; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; transition: box-shadow .15s; }
    .card:hover { box-shadow: var(--shadow); }
    .top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .num { font-weight: 700; color: var(--text); text-decoration: none; }
    .num::after { content: ''; position: absolute; inset: 0; border-radius: 10px; }
    .num:focus-visible { outline: none; }
    .num:focus-visible::after { outline: 2px solid var(--accent); outline-offset: 2px; }
    .lead-mark { font-size: 12px; color: var(--text-muted); }
    .date { margin-left: auto; color: var(--text-muted); font-size: 13px; }
    .client { margin-top: 6px; color: var(--text); font-size: 14px; overflow-wrap: anywhere; }
    .client.muted { color: var(--text-muted); }
    .bottom { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
    @media (max-width: 900px) {
      .page-head > .btn { margin-bottom: 12px; }
      .filters input { flex: 1; min-width: 0; font-size: 16px; }
      .card { padding: 12px; }
      .client { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    }
  `],
})
export class PrivateRequestsComponent implements OnDestroy {
  rows: PrListItem[] = [];
  loading = false;
  loadError = '';
  creating = false;
  status = 'OPEN';
  q = '';
  readonly filters = [{ key: 'OPEN', label: 'В работе' }, { key: 'CLOSED', label: 'Закрытые' }, { key: 'ALL', label: 'Все' }];
  readonly dateText = dateText;
  private shownQ = '';
  private searchTimer: any = null;
  private loadSub?: Subscription;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private market: MarketService, private route: ActivatedRoute, private router: Router,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {
    // старые ссылки «?openId=» (Telegram, обращения, «Входящие») — на страницу заявки, заменой записи истории
    const openId = Number(this.route.snapshot.queryParamMap.get('openId'));
    if (openId > 0) {
      this.router.navigate(['/private-requests', openId], { replaceUrl: true });
      return;
    }
    this.load();
  }

  get company() { return this.market.companyLabel(); }

  ngOnDestroy() {
    clearTimeout(this.searchTimer);
  }

  tone(stage: string) {
    return stageTone(stage);
  }

  load() {
    this.loadSub?.unsubscribe();
    const q = this.q.trim();
    this.loading = true;
    this.loadSub = this.api.getPrivateRequests({ status: this.status, q: q || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: rows => {
          this.rows = rows;
          this.shownQ = q;
          this.loading = false;
          this.loadError = '';
          this.cdr.markForCheck();
        },
        error: e => { this.loading = false; this.loadError = errorText(e); this.cdr.markForCheck(); },
      });
  }

  setStatus(key: string) {
    this.status = key;
    this.load();
  }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  create() {
    this.creating = true;
    this.api.createPrivateRequest().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      // кнопка заблокирована до конца перехода: страница — отдельный чанк, повторное нажатие создало бы вторую заявку
      next: v => { this.router.navigate(['/private-requests', v.id]).finally(() => this.creating = false); },
      error: e => { this.creating = false; this.notify.error('Заявка не создана: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  emptyText(): string {
    if (this.shownQ) return 'Ничего не нашлось';
    if (this.status === 'CLOSED') return 'Закрытых заявок нет';
    return this.auth.isAdmin() ? 'Заявок пока нет — нажмите «+ Новая заявка»' : 'Заявок пока нет';
  }

  trackById(_: number, r: PrListItem) {
    return r.id;
  }
}
```

- [ ] **Step 6: Страница заявки — шапка, меню, обращение**

`frontend/src/app/pages/private-requests/private-request-page.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, HostListener } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { CLOSE_OUTCOMES, PrStatus, PrView, SaveState, stageTone, timeText } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { fullDateTime } from '../../shared/relative-time';

/**
 * Страница частной заявки (спека 2026-10-12 §5): шапка с этапом и меню, разделы — клиент, позиции, поставщики и цены.
 * Разделы сохраняют поля сами (по выходу из поля) и отдают странице новый вид заявки и состояние сохранения.
 */
@Component({
  selector: 'app-private-request-page',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <div class="pr-page" *ngIf="view as v; else stateTpl">
      <header class="pr-top">
        <a routerLink="/private-requests" class="back">← Частные заявки</a>
        <div class="pr-title">
          <h2>{{ v.number }}</h2>
          <span class="pr-stage" [attr.data-tone]="tone(v.stage)">{{ v.stageText }}</span>
          <span class="save-state" [class.err]="saveError" aria-live="polite">{{ saveText }}</span>
          <button type="button" class="retry" *ngIf="saveError && retryFn" (click)="retry()">Повторить</button>
        </div>
        <span class="pr-menu" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
          <button type="button" class="btn btn-more" (click)="menuOpen = !menuOpen" [attr.aria-expanded]="menuOpen"
                  [disabled]="busy" aria-label="Действия с заявкой">⋯</button>
          <span class="row-menu" *ngIf="menuOpen">
            <ng-container *ngIf="v.status === 'NEW'">
              <button type="button" *ngFor="let o of outcomes" (click)="close(o.v)">Закрыть: {{ o.l }}</button>
            </ng-container>
            <button type="button" *ngIf="v.status !== 'NEW'" (click)="reopen()">Вернуть в работу</button>
            <button type="button" class="danger" (click)="remove()">Удалить заявку</button>
          </span>
        </span>
      </header>
      <div class="from-lead" *ngIf="v.lead as ld">
        Из обращения:
        <a [routerLink]="['/leads']" [queryParams]="{ openId: ld.id }">«{{ ld.subject }}» · {{ leadSource(ld.source) }} · {{ when(ld.receivedAt) }}</a>
      </div>
      <!-- sections -->
    </div>
    <ng-template #stateTpl>
      <div class="empty" *ngIf="loading">Загрузка…</div>
      <div class="error-banner" *ngIf="loadError">
        {{ loadError }} · <a routerLink="/private-requests">к списку заявок</a>
      </div>
    </ng-template>
  `,
  styles: [`
    :host { display: block; max-width: 1100px; }
    .pr-page { display: flex; flex-direction: column; gap: 12px; }
    .pr-top { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .back { color: var(--accent); text-decoration: none; font-size: 13px; padding-top: 4px; }
    .pr-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; flex: 1; min-width: 0; }
    .pr-title h2 { margin: 0; font-size: 20px; }
    .save-state { font-size: 12px; color: var(--text-muted); }
    .save-state.err { color: var(--danger-text); }
    .retry { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 12px; text-decoration: underline; }
    .pr-menu { position: relative; }
    .from-lead { font-size: 13px; color: var(--text-muted); }
    .from-lead a { color: var(--accent); text-decoration: none; }
    @media (max-width: 900px) {
      .pr-top { gap: 8px; }
      .back { width: 100%; padding-top: 0; }
      .btn-more { min-width: 40px; }
    }
  `],
})
export class PrivateRequestPageComponent {
  view: PrView | null = null;
  loading = false;
  loadError = '';
  busy = false;
  menuOpen = false;
  saveText = '';
  saveError = false;
  retryFn?: () => void;
  readonly outcomes = CLOSE_OUTCOMES;
  private loadSub?: Subscription;

  constructor(private route: ActivatedRoute, private router: Router, private api: ApiService, public auth: AuthService,
              private notify: NotificationService, private confirm: ConfirmService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {
    // маршрут переиспользует компонент при переходе на другую заявку — грузим по каждому id
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      const id = Number(p.get('id'));
      if (!(id > 0)) {
        this.view = null;
        this.loadError = 'Заявка не найдена';
        return;
      }
      this.load(id);
    });
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  closeMenu() {
    this.menuOpen = false;
  }

  tone(stage: string) {
    return stageTone(stage);
  }

  leadSource(source: string): string {
    return source === 'manual' ? 'внесено вручную' : source;
  }

  when(iso: string): string {
    return fullDateTime(iso);
  }

  load(id: number) {
    this.loadSub?.unsubscribe();
    this.loading = true;
    this.loadError = '';
    this.view = null;
    this.saveText = '';
    this.saveError = false;
    this.loadSub = this.api.getPrivateRequest(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.view = v; this.loading = false; this.afterLoad(); this.cdr.markForCheck(); },
      error: e => {
        this.loading = false;
        this.loadError = e?.status === 404 ? 'Заявка не найдена — возможно, она другого рынка или удалена' : errorText(e);
        this.cdr.markForCheck();
      },
    });
  }

  /** Сразу после загрузки (задача 13 добавит проверку РУ у позиций без статуса). */
  private afterLoad() {}

  onView(v: PrView) {
    this.view = v;
    this.cdr.markForCheck();
  }

  onSaveState(s: SaveState) {
    if (s.kind === 'saving') {
      this.saveText = 'Сохраняю…';
      this.saveError = false;
      this.retryFn = undefined;
    } else if (s.kind === 'saved') {
      this.saveText = 'Сохранено · ' + timeText();
      this.saveError = false;
      this.retryFn = undefined;
    } else {
      this.saveText = 'Не сохранено: ' + (s.message || 'ошибка');
      this.saveError = true;
      this.retryFn = s.retry;
    }
    this.cdr.markForCheck();
  }

  retry() {
    const f = this.retryFn;
    this.retryFn = undefined;
    f?.();
  }

  close(outcome: PrStatus) {
    const v = this.view;
    if (!v) return;
    this.menuOpen = false;
    this.busy = true;
    this.api.closePrivateRequest(v.id, outcome).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { this.busy = false; this.view = nv; this.notify.success('Заявка закрыта'); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  reopen() {
    const v = this.view;
    if (!v) return;
    this.menuOpen = false;
    this.busy = true;
    this.api.reopenPrivateRequest(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { this.busy = false; this.view = nv; this.notify.success('Заявка снова в работе'); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  remove() {
    const v = this.view;
    if (!v) return;
    this.menuOpen = false;
    this.confirm.ask(`Удалить заявку ${v.number}?`,
      'Удалить можно заявку без запросов цен и КП клиенту. Это действие нельзя отменить.',
      { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        this.api.deletePrivateRequest(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => { this.notify.success('Заявка удалена'); this.router.navigate(['/private-requests']); },
          error: e => this.notify.error(errorText(e)),
        });
      });
  }
}
```

- [ ] **Step 7: Удалить боковую панель**

```bash
git rm frontend/src/app/pages/private-requests/private-request-card.component.ts
```

`grep -rn "private-request-card\|PrivateRequestCardComponent" frontend/src` — пусто.

- [ ] **Step 8: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESS. Бюджеты стилей не превышены, начальный бандл не вырос: обе страницы — отдельные ленивые чанки (в `dist/…/browser` есть `chunk-*.js` со строкой «Частные заявки», в `main-*.js` её нет — `grep -l "Частные заявки" dist/frontend/browser/*.js`).

- [ ] **Step 9: Живая проверка каркаса**

Поднять стек (sandbox off): `JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun` в фоне, `cd frontend && npm start` в фоне. Playwright (§5 CLAUDE.md): вход admin/admin, `localStorage['ais.market'] = 'KZ'`, `/private-requests`.
- **1280 px:**
  - список: чипы «В работе / Закрытые / Все», поиск, карточки — номер, этап, клиент, «N поз.», дата;
  - «+ Новая заявка» открывает `/private-requests/<id>` с номером, чипом «Пустая» и меню «⋯»;
  - «⋯ → Закрыть: отменена» — чип «Закрыта: отменена»; «Вернуть в работу» — снова «Пустая»; «Удалить заявку» с подтверждением — возврат к списку.
- **Старая ссылка:** `/private-requests?openId=<id>` заменяется на `/private-requests/<id>` («Назад» не возвращает на `?openId`).
- **Оператор** (operator/operator): «+ Новая заявка» и «⋯» не видны.
- **390 px:** шапка не шире экрана, карточки списка в одну колонку (замер ширины — `http://[::1]:4200`, §5).
- **Обе темы:** чип «Пустая» читается.

- [ ] **Step 10: Коммит**

```bash
git add frontend/src/app/shared/private-request.ts frontend/src/app/shared/error-text.ts frontend/src/app/services/api.service.ts frontend/src/app/app.routes.ts frontend/src/styles.scss frontend/src/app/pages/private-requests/private-requests.component.ts frontend/src/app/pages/private-requests/private-request-page.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): фронт — список заявок и страница заявки вместо боковой панели

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Раздел «Клиент» — клиент из справочника или текстом, контакт, сохранение по выходу из поля

**Files:**
- Create: `frontend/src/app/pages/private-requests/pr-client-section.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-page.component.ts` (вставить раздел)

**Interfaces:**
- Consumes: `ApiService.getFacilities`, `updatePrivateRequestField`, `savePrivateRequestClient` (задача 10); типы `PrView`, `PrField`, `SaveState`.
- Produces: `<app-pr-client-section [view] [readonly] (viewChange) (saveState)>`.

- [ ] **Step 1: Компонент раздела**

`frontend/src/app/pages/private-requests/pr-client-section.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { PrField, PrView, SaveState } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';

interface FacilityOption { id: number; name: string; }

/**
 * Клиент заявки (спека 2026-10-12 §5.1): из «Учреждений» (поиск в поле), своим текстом («Частная клиника, Уральск» —
 * в справочник только по «Сохранить в Учреждения») или пусто. Контакт, «Куда поставка», примечание — отдельными
 * полями. Каждое поле сохраняется по выходу из него или Enter; набираемое поле ответом сервера не затирается.
 */
@Component({
  selector: 'app-pr-client-section',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <section class="card">
      <h3>Клиент</h3>
      <div class="client-row">
        <div class="picker" (click)="$event.stopPropagation()">
          <input type="text" [value]="clientText" [disabled]="readonly" maxlength="500" autocomplete="off"
                 placeholder="Найти в «Учреждениях» или вписать: «Частная клиника, Уральск»"
                 role="combobox" [attr.aria-expanded]="pickerOpen" aria-label="Клиент"
                 (focus)="onClientFocus()" (input)="onClientInput($event)" (keydown)="onClientKey($event)"
                 (blur)="onClientBlur()" />
          <ul class="options" *ngIf="pickerOpen && options.length" role="listbox">
            <li *ngFor="let f of options; let i = index" role="option" [class.on]="i === active"
                [attr.aria-selected]="i === active" (mousedown)="$event.preventDefault(); pick(f)">{{ f.name }}</li>
          </ul>
        </div>
        <button type="button" class="btn btn-line" *ngIf="view.customerName && !readonly" [disabled]="busy"
                (click)="saveToDirectory()">Сохранить в Учреждения</button>
      </div>
      <p class="hint" *ngIf="!view.facilityId && !view.customerName">Клиент не указан — можно оставить так.</p>

      <div class="grid3">
        <label>Контактное лицо
          <input [value]="view.contactName || ''" [disabled]="readonly" maxlength="255"
                 (blur)="commit('CONTACT_NAME', $event)" (keydown.enter)="blurTarget($event)" /></label>
        <label>Телефон
          <input [value]="view.contactPhone || ''" [disabled]="readonly" maxlength="50" inputmode="tel"
                 (blur)="commit('CONTACT_PHONE', $event)" (keydown.enter)="blurTarget($event)" /></label>
        <label>Почта
          <input type="email" [value]="view.contactEmail || ''" [disabled]="readonly" maxlength="255"
                 (blur)="commit('CONTACT_EMAIL', $event)" (keydown.enter)="blurTarget($event)" /></label>
      </div>
      <div class="grid2">
        <label>Куда поставка
          <input [value]="view.deliveryAddress || ''" [disabled]="readonly" maxlength="2000" placeholder="Город, учреждение, адрес"
                 (blur)="commit('DELIVERY_ADDRESS', $event)" (keydown.enter)="blurTarget($event)" /></label>
        <label>Примечание
          <textarea rows="2" [value]="view.note || ''" [disabled]="readonly" maxlength="10000"
                    placeholder="Текст клиента, сроки, условия" (blur)="commit('NOTE', $event)"></textarea></label>
      </div>
    </section>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    h3 { margin: 0 0 10px; font-size: 15px; }
    .client-row { display: flex; gap: 8px; align-items: flex-start; flex-wrap: wrap; }
    .picker { position: relative; flex: 1; min-width: 240px; }
    .picker input { width: 100%; box-sizing: border-box; }
    .options { position: absolute; z-index: 20; left: 0; right: 0; top: 100%; margin: 4px 0 0; padding: 4px 0; list-style: none;
               background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow);
               max-height: 260px; overflow-y: auto; }
    .options li { padding: 8px 12px; cursor: pointer; font-size: 14px; color: var(--text); }
    .options li.on, .options li:hover { background: var(--surface-2); }
    .hint { margin: 6px 0 0; font-size: 12px; color: var(--text-muted); }
    .grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 12px; margin-top: 12px; }
    .grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; margin-top: 10px; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    input, textarea { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                      color: var(--text); font: inherit; font-size: 14px; }
    input:disabled, textarea:disabled { background: var(--surface-2); cursor: not-allowed; }
    textarea { resize: vertical; }
    @media (max-width: 900px) {
      .grid3, .grid2 { grid-template-columns: 1fr; }
      .picker { min-width: 0; flex-basis: 100%; }
      input, textarea { font-size: 16px; min-height: 40px; }
      .client-row .btn { min-height: 40px; }
    }
  `],
})
export class PrClientSectionComponent implements OnChanges {
  @Input() view!: PrView;
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  @Output() saveState = new EventEmitter<SaveState>();

  facilities: FacilityOption[] = [];
  options: FacilityOption[] = [];
  clientText = '';
  pickerOpen = false;
  active = -1;
  busy = false;
  private clientFocused = false;
  private picked = false;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {
    this.loadFacilities();
  }

  ngOnChanges(ch: SimpleChanges) {
    // пока оператор печатает в поле клиента, ответ сохранения другого поля не должен переписать набранное
    if (ch['view'] && !this.clientFocused) this.clientText = this.label();
  }

  private label(): string {
    return this.view?.facilityName || this.view?.customerName || '';
  }

  private loadFacilities() {
    this.api.getFacilities().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => { this.facilities = (list || []).map((f: any) => ({ id: f.id, name: f.name })); this.cdr.markForCheck(); },
      error: () => { this.facilities = []; },
    });
  }

  onClientFocus() {
    this.clientFocused = true;
    this.filter();
    this.pickerOpen = !this.readonly;
  }

  onClientInput(e: Event) {
    this.clientText = (e.target as HTMLInputElement).value;
    this.picked = false;
    this.filter();
    this.pickerOpen = true;
  }

  private filter() {
    const q = this.clientText.trim().toLowerCase();
    const list = q ? this.facilities.filter(f => f.name.toLowerCase().includes(q)) : this.facilities;
    this.options = list.slice(0, 8);
    this.active = -1;
  }

  onClientKey(e: KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.pickerOpen = true;
      this.active = Math.min(this.active + 1, this.options.length - 1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.active = Math.max(this.active - 1, -1);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (this.active >= 0) this.pick(this.options[this.active]);
      (e.target as HTMLInputElement).blur();
    } else if (e.key === 'Escape') {
      this.pickerOpen = false;
    }
  }

  pick(f: FacilityOption) {
    this.picked = true;
    this.pickerOpen = false;
    this.clientText = f.name;
    if (f.id !== this.view.facilityId) this.save('FACILITY', String(f.id));
  }

  onClientBlur() {
    this.clientFocused = false;
    this.pickerOpen = false;
    if (this.picked) {
      this.picked = false;
      return;
    }
    const text = this.clientText.trim();
    if (text === this.label().trim()) return;
    if (!text) {
      this.save(this.view.facilityId ? 'FACILITY' : 'CUSTOMER_NAME', null);
      return;
    }
    // вписанное слово в слово совпало с учреждением — это оно, а не текст
    const exact = this.facilities.find(f => f.name.trim().toLowerCase() === text.toLowerCase());
    if (exact) this.save('FACILITY', String(exact.id));
    else this.save('CUSTOMER_NAME', text);
  }

  commit(field: PrField, e: Event) {
    const value = (e.target as HTMLInputElement | HTMLTextAreaElement).value.trim();
    if (value === (this.current(field) || '').trim()) return;
    this.save(field, value || null);
  }

  blurTarget(e: Event) {
    (e.target as HTMLElement).blur();
  }

  private current(field: PrField): string | null {
    switch (field) {
      case 'CONTACT_NAME': return this.view.contactName;
      case 'CONTACT_PHONE': return this.view.contactPhone;
      case 'CONTACT_EMAIL': return this.view.contactEmail;
      case 'DELIVERY_ADDRESS': return this.view.deliveryAddress;
      case 'NOTE': return this.view.note;
      case 'CUSTOMER_NAME': return this.view.customerName;
      case 'FACILITY': return this.view.facilityId == null ? null : String(this.view.facilityId);
      default: return null;
    }
  }

  private save(field: PrField, value: string | null) {
    this.saveState.emit({ kind: 'saving' });
    this.api.updatePrivateRequestField(this.view.id, field, value).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.saveState.emit({ kind: 'saved' }); this.viewChange.emit(v); },
      error: e => {
        this.saveState.emit({ kind: 'error', message: errorText(e), retry: () => this.save(field, value) });
        if (field === 'FACILITY' || field === 'CUSTOMER_NAME') this.clientText = this.label();
        this.cdr.markForCheck();
      },
    });
  }

  saveToDirectory() {
    this.busy = true;
    this.api.savePrivateRequestClient(this.view.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => {
        this.busy = false;
        this.notify.success('Клиент сохранён в «Учреждения»');
        this.viewChange.emit(v);
        this.loadFacilities();
      },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 2: Вставить раздел в страницу**

В `private-request-page.component.ts`:
- импорт `import { PrClientSectionComponent } from './pr-client-section.component';` и `PrClientSectionComponent` в `imports`;
- комментарий `<!-- sections -->` заменить на:

```html
      <app-pr-client-section [view]="v" [readonly]="!auth.isAdmin()" (viewChange)="onView($event)"
                             (saveState)="onSaveState($event)"></app-pr-client-section>
      <!-- sections -->
```

(якорь `<!-- sections -->` остаётся для следующих разделов).

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: SUCCESS.

- [ ] **Step 4: Живая проверка раздела**

Стек поднят, как в задаче 10 (после правок бэка не нужен перезапуск — бэк не менялся).
- **Новая заявка, поле «Клиент»:**
  - фокус → список «Учреждений»;
  - вписать «Городск» → отфильтровано; Enter по варианту → «Сохранено · ЧЧ:ММ», в списке заявок клиент — эта больница;
  - вписать «Частная клиника, Уральск» и Tab → сохранено текстом, появилась кнопка «Сохранить в Учреждения»; нажать → тост, кнопка пропала, клиент — учреждение;
  - очистить поле и уйти → «Клиент не указан — можно оставить так.».
- **Контакт:**
  - «Почта» = «не почта» → в шапке «Не сохранено: Неверный адрес почты: не почта»;
  - исправить → «Сохранено»;
  - Enter в однострочном поле сохраняет.
- **Набор не затирается:** печатать в «Примечании», пока сохраняется телефон, — набранное не пропадает (нажать Tab из телефона и сразу печатать в примечании).
- **«Повторить»:** оборвать правки шапки в браузере — `browser_run_code_unsafe`: `await page.route('**/api/private-requests/*', r => r.request().method() === 'PATCH' ? r.abort() : r.continue())`; поправить «Куда поставка» и уйти из поля → «Не сохранено: нет связи с сервером» и «Повторить»; снять обрыв (`await page.unrouteAll()`), нажать «Повторить» → «Сохранено · ЧЧ:ММ», значение на месте после перезагрузки страницы.
- **390 px:** поля в столбик, высота полей 40 px, шрифт 16 px (iOS не зумит).
- **Оператор:** поля недоступны, «Сохранить в Учреждения» не видна.
- **Тёмная тема:** выпадающий список и поля читаются.

- [ ] **Step 5: Коммит**

```bash
git add frontend/src/app/pages/private-requests/pr-client-section.component.ts frontend/src/app/pages/private-requests/private-request-page.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): раздел «Клиент» — из справочника или текстом, контакт, сохранение по полю

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Общие компоненты — метка РУ с окошком и поиск по каталогу сайта

**Files:**
- Create: `frontend/src/app/shared/registry-chip.component.ts`
- Create: `frontend/src/app/shared/site-product-search.component.ts`

**Interfaces:**
- Consumes: `ApiService.setPrivateRequestLineRegistry`, `getPrivateRequestLineCandidates`, `searchRegistry(q, limit)` (есть), `searchSiteProducts` (задача 10); `regLabel`, `regValidity`, `PrLine`, `RegistryRecord`, `SiteProduct`.
- Produces:
  - **`<app-registry-chip [requestId] [line] [readonly] (changed)="onLine(PrLine)">`** — часть 3 поставит её в строки КП с другим источником действий;
  - **`<app-site-product-search [siteEnabled] (pickProduct)="SiteProduct" (addText)="string">`.**

- [ ] **Step 1: Метка РУ**

`frontend/src/app/shared/registry-chip.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { PrLine, RegAction, RegistryRecord, regLabel, regValidity } from './private-request';
import { errorText } from './error-text';

/** Открыта может быть только одна метка: клик по второй закрывает первую (клик внутри метки до document не доходит). */
let openChip: RegistryChipComponent | null = null;

/**
 * Метка РУ позиции (спека 2026-10-12 §5.3) — одна на строку: «РУ ✓», «РУ?», «РУ не найдено», «не подлежит».
 * Клик — окошко с записью реестра и действиями: подтвердить, найти в реестре, «не подлежит», снять/вернуть.
 * Без нагромождения в строке: всё остальное про реестр — только в окошке.
 */
@Component({
  selector: 'app-registry-chip',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <span class="rc" (click)="$event.stopPropagation()">
      <button type="button" class="chip" [attr.data-status]="line.regStatus || 'NONE'" (click)="toggle()"
              [attr.aria-expanded]="open" [attr.aria-label]="'Регистрация: ' + label()">{{ label() }}</button>
      <div class="pop" *ngIf="open" role="dialog" aria-label="Регистрация в реестре">
        <div class="rec" *ngIf="line.registry as r">
          <div class="rec-kind">{{ line.regStatus === 'CONFIRMED' ? 'Подтверждено' : 'Похоже на' }}</div>
          <div class="rec-name">{{ r.name }}</div>
          <div class="rec-meta">{{ r.producer || 'производитель не указан' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container></div>
          <div class="rec-meta">№ {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container></div>
        </div>
        <div class="rec-none" *ngIf="!line.registry">{{ noneText() }}</div>

        <div class="pop-actions" *ngIf="!readonly">
          <button type="button" class="btn btn-primary" *ngIf="line.regStatus === 'SUGGESTED'" [disabled]="busy"
                  (click)="act('CONFIRM')">Подтвердить</button>
          <button type="button" class="btn btn-line" *ngIf="line.regStatus === 'CONFIRMED'" [disabled]="busy"
                  (click)="act('RESET')">Снять подтверждение</button>
          <button type="button" class="btn btn-line" *ngIf="line.regStatus === 'NOT_REQUIRED'" [disabled]="busy"
                  (click)="act('RESET')">Вернуть проверку</button>
          <button type="button" class="btn btn-line" *ngIf="!searching" [disabled]="busy" (click)="openSearch()">
            {{ line.regStatus === 'SUGGESTED' ? 'Не то — найти в реестре' : 'Найти в реестре' }}</button>
          <button type="button" class="btn btn-line" *ngIf="line.regStatus !== 'NOT_REQUIRED'" [disabled]="busy"
                  (click)="act('NOT_REQUIRED')">Не подлежит</button>
        </div>

        <div class="search" *ngIf="searching && !readonly">
          <input type="search" [(ngModel)]="query" (input)="onQuery()" placeholder="Название, модель или производитель"
                 aria-label="Поиск в реестре" />
          <div class="hint" *ngIf="loadingResults">Ищу…</div>
          <div class="hint" *ngIf="!loadingResults && searchedOnce && !results.length">Ничего не нашлось</div>
          <ul class="results">
            <li *ngFor="let r of results">
              <button type="button" [disabled]="busy" (click)="act('CONFIRM', r.regNumber)">
                <span class="res-name">{{ r.name }}</span>
                <span class="res-meta">{{ r.producer || '—' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container>
                  · № {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container></span>
              </button>
            </li>
          </ul>
        </div>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
      </div>
    </span>
  `,
  styles: [`
    .rc { position: relative; display: inline-block; }
    .chip { border: none; border-radius: 10px; padding: 3px 10px; font-size: 12px; font-weight: 600; cursor: pointer; white-space: nowrap;
            background: var(--surface-2); color: var(--text); }
    .chip[data-status="CONFIRMED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .chip[data-status="SUGGESTED"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .chip[data-status="NOT_FOUND"], .chip[data-status="NOT_REQUIRED"], .chip[data-status="NONE"] {
      background: transparent; border: 1px solid var(--border); color: var(--text-muted); }
    .pop { position: absolute; right: 0; top: calc(100% + 6px); z-index: 30; width: 360px; max-width: calc(100vw - 32px);
           background: var(--surface); border: 1px solid var(--border); border-radius: 10px; box-shadow: var(--shadow-lg);
           padding: 12px; display: flex; flex-direction: column; gap: 10px; text-align: left; }
    .rec-kind { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .rec-name { font-size: 14px; color: var(--text); font-weight: 600; overflow-wrap: anywhere; }
    .rec-meta, .rec-none { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .pop-actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .search { display: flex; flex-direction: column; gap: 6px; }
    .search input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 14px; }
    .hint { font-size: 12px; color: var(--text-muted); }
    .results { list-style: none; margin: 0; padding: 0; max-height: 240px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
    .results button { width: 100%; text-align: left; background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
                      padding: 8px 10px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; color: var(--text); }
    .results button:hover { background: var(--surface-2); }
    .res-name { font-size: 13px; overflow-wrap: anywhere; }
    .res-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .error-banner { margin: 0; }
    @media (max-width: 900px) {
      .chip { min-height: 32px; }
      .pop { position: fixed; left: 16px; right: 16px; top: auto; bottom: 16px; width: auto; max-height: 70vh; overflow-y: auto; }
      .pop-actions .btn, .results button { min-height: 40px; }
      .search input { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class RegistryChipComponent implements OnDestroy {
  @Input() requestId!: number;
  @Input() line!: PrLine;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<PrLine>();

  open = false;
  searching = false;
  busy = false;
  error = '';
  query = '';
  results: RegistryRecord[] = [];
  loadingResults = false;
  searchedOnce = false;
  private timer: any = null;
  private searchSub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnDestroy() {
    clearTimeout(this.timer);
    if (openChip === this) openChip = null;
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  close() {
    if (!this.open) return;
    this.open = false;
    this.searching = false;
    if (openChip === this) openChip = null;
  }

  label() {
    return regLabel(this.line.regStatus);
  }

  validity(r: RegistryRecord) {
    return regValidity(r);
  }

  noneText(): string {
    switch (this.line.regStatus) {
      case 'NOT_REQUIRED': return 'Отмечено: не подлежит регистрации.';
      case 'NOT_FOUND': return 'Реестр не подсказал подходящего РУ — найдите вручную или отметьте «не подлежит».';
      default: return 'Регистрация ещё не проверялась.';
    }
  }

  toggle() {
    if (this.open) {
      this.close();
      return;
    }
    if (openChip && openChip !== this) {
      openChip.close();
      openChip.cdr.markForCheck();
    }
    openChip = this;
    this.open = true;
    this.searching = false;
    this.error = '';
  }

  /** Сначала кандидаты по наименованию и бренду позиции — они точнее общего поиска; дальше — свой запрос. */
  openSearch() {
    this.searching = true;
    this.query = [this.line.name, this.line.brand].filter(Boolean).join(' ');
    this.loadingResults = true;
    this.searchSub?.unsubscribe();
    this.searchSub = this.api.getPrivateRequestLineCandidates(this.requestId, this.line.lotId)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: r => { this.results = r || []; this.loadingResults = false; this.searchedOnce = true; this.cdr.markForCheck(); },
        error: e => { this.loadingResults = false; this.error = errorText(e); this.cdr.markForCheck(); },
      });
  }

  onQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  private search() {
    const q = this.query.trim();
    this.searchSub?.unsubscribe();
    if (q.length < 2) {
      this.results = [];
      return;
    }
    this.loadingResults = true;
    this.searchSub = this.api.searchRegistry(q, 10).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.results = r || []; this.loadingResults = false; this.searchedOnce = true; this.cdr.markForCheck(); },
      error: e => { this.loadingResults = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }

  act(action: RegAction, regNumber?: string) {
    this.busy = true;
    this.error = '';
    this.api.setPrivateRequestLineRegistry(this.requestId, this.line.lotId, action, regNumber)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: line => { this.busy = false; this.close(); this.changed.emit(line); this.cdr.markForCheck(); },
        error: e => { this.busy = false; this.error = errorText(e); this.cdr.markForCheck(); },
      });
  }
}
```

- [ ] **Step 2: Поиск по каталогу сайта**

`frontend/src/app/shared/site-product-search.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { SiteProduct } from './private-request';

/**
 * «Найти на westmed.kz или вписать своё…» (спека 2026-10-12 §4): от 2 символов, через 300 мс — до 10 товаров сайта;
 * выбор — позиция с брендом и ссылкой, Enter без выбора — позиция как написано. Сайт не ответил — подсказка, текст
 * добавляется всё равно. siteEnabled=false (РФ) — только свой текст.
 */
@Component({
  selector: 'app-site-product-search',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="sps" (click)="$event.stopPropagation()">
      <input type="text" [value]="text" maxlength="255" autocomplete="off"
             [placeholder]="siteEnabled ? 'Найти на westmed.kz или вписать своё…' : 'Вписать позицию…'"
             role="combobox" [attr.aria-expanded]="open" aria-label="Добавить позицию"
             (input)="onInput($event)" (keydown)="onKey($event)" (focus)="open = !!text.trim()" />
      <ul class="drop" *ngIf="open && text.trim()" role="listbox">
        <li class="hint" *ngIf="loading">Ищу на сайте…</li>
        <li class="hint warn" *ngIf="error">{{ error }}</li>
        <li *ngFor="let p of results; let i = index" role="option" [class.on]="i === active" [attr.aria-selected]="i === active"
            (mousedown)="$event.preventDefault(); pick(p)">
          <span class="nm">{{ p.name }}</span>
          <span class="meta">{{ p.brand || 'бренд не указан' }}<ng-container *ngIf="p.sku"> · {{ p.sku }}</ng-container></span>
        </li>
        <li class="own" role="option" [class.on]="active === results.length" [attr.aria-selected]="active === results.length"
            (mousedown)="$event.preventDefault(); addOwn()">＋ Добавить «{{ text.trim() }}» как есть</li>
      </ul>
    </div>
  `,
  styles: [`
    .sps { position: relative; margin-top: 10px; }
    input { width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px dashed var(--border); border-radius: 8px;
            background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    input:focus { border-style: solid; border-color: var(--accent); outline: none; }
    .drop { position: absolute; z-index: 25; left: 0; right: 0; top: 100%; margin: 4px 0 0; padding: 4px 0; list-style: none;
            background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow);
            max-height: 320px; overflow-y: auto; }
    .drop li { padding: 8px 12px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    .drop li.on, .drop li:hover { background: var(--surface-2); }
    .drop li.hint { cursor: default; font-size: 12px; color: var(--text-muted); }
    .drop li.hint.warn { color: var(--warn-text); }
    .drop li.hint:hover { background: none; }
    .nm { font-size: 14px; color: var(--text); overflow-wrap: anywhere; }
    .meta { font-size: 12px; color: var(--text-muted); }
    .own { font-size: 13px; color: var(--accent); border-top: 1px solid var(--border); }
    @media (max-width: 900px) {
      input { font-size: 16px; min-height: 44px; }
      .drop li { min-height: 40px; justify-content: center; }
    }
  `],
})
export class SiteProductSearchComponent implements OnDestroy {
  @Input() siteEnabled = true;
  @Output() pickProduct = new EventEmitter<SiteProduct>();
  @Output() addText = new EventEmitter<string>();

  text = '';
  results: SiteProduct[] = [];
  open = false;
  loading = false;
  error = '';
  active = -1;
  private timer: any = null;
  private sub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnDestroy() {
    clearTimeout(this.timer);
  }

  @HostListener('document:click')
  closeDrop() {
    this.open = false;
  }

  onInput(e: Event) {
    this.text = (e.target as HTMLInputElement).value;
    this.active = -1;
    this.error = '';
    this.open = !!this.text.trim();
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
    this.results = [];
    const q = this.text.trim();
    if (!this.siteEnabled || q.length < 2) {
      this.loading = false;
      return;
    }
    this.loading = true;
    this.timer = setTimeout(() => this.search(q), 300);
  }

  private search(q: string) {
    this.sub = this.api.searchSiteProducts(q).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        if (q !== this.text.trim()) return;   // ответ на устаревший запрос
        this.results = r || [];
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => {
        this.loading = false;
        this.results = [];
        this.error = e?.error?.message || 'Сайт не отвечает — добавьте своим текстом';
        this.cdr.markForCheck();
      },
    });
  }

  onKey(e: KeyboardEvent) {
    const own = this.results.length;   // последний вариант — «как есть»
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.open = !!this.text.trim();
      this.active = Math.min(this.active + 1, own);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.active = Math.max(this.active - 1, -1);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (this.active >= 0 && this.active < own) this.pick(this.results[this.active]);
      else this.addOwn();
    } else if (e.key === 'Escape') {
      this.open = false;
    }
  }

  pick(p: SiteProduct) {
    this.pickProduct.emit(p);
    this.reset();
  }

  addOwn() {
    const t = this.text.trim();
    if (!t) return;
    this.addText.emit(t);
    this.reset();
  }

  private reset() {
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
    this.text = '';
    this.results = [];
    this.open = false;
    this.active = -1;
    this.loading = false;
    this.error = '';
    this.cdr.markForCheck();
  }
}
```

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: SUCCESS. Компоненты пока никем не используются — esbuild их просто не включит, это нормально (проверка вживую — в задаче 13).

- [ ] **Step 4: Коммит**

```bash
git add frontend/src/app/shared/registry-chip.component.ts frontend/src/app/shared/site-product-search.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): общие компоненты — метка РУ с окошком и поиск по каталогу westmed.kz

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Раздел «Позиции» — строки, правка по полю, добавление с сайта, списком и из Excel

**Files:**
- Create: `frontend/src/app/pages/private-requests/pr-lines.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-page.component.ts` (вставить раздел; проверка РУ при открытии)

**Interfaces:**
- Consumes:
  - `ApiService`: `addPrivateRequestLines`, `updatePrivateRequestLine`, `deletePrivateRequestLine`, `parsePrivateRequestLines`, `previewImport`, `importPrivateRequestLines`, `checkPrivateRequestRegistry`;
  - компоненты `RegistryChipComponent`, `SiteProductSearchComponent` (задача 12), `ImportGridComponent` и `buildImportLines` (`shared/import-grid`, `shared/import-lines`);
  - `UNIT_SUGGESTIONS`.
- Produces: `<app-pr-lines [view] [readonly] [isKz] (viewChange) (saveState)>`.

- [ ] **Step 1: Компонент раздела**

`frontend/src/app/pages/private-requests/pr-lines.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { RegistryChipComponent } from '../../shared/registry-chip.component';
import { SiteProductSearchComponent } from '../../shared/site-product-search.component';
import { ImportGridComponent } from '../../shared/import-grid.component';
import { buildImportLines } from '../../shared/import-lines';
import {
  LineInput, LinesMode, ParsedLine, PrLine, PrLineField, PrView, SaveState, SiteProduct, UNIT_SUGGESTIONS,
} from '../../shared/private-request';
import { errorText } from '../../shared/error-text';

interface ParsedRow extends ParsedLine { on: boolean; }

/**
 * Позиции заявки (спека 2026-10-12 §5.3): строка — наименование, бренд, количество, метка РУ, «⋯»; клик «⋯» раскрывает
 * характеристики, ед. изм., ссылку и «Удалить». Поля сохраняются по выходу из поля. Добавление — поиск по сайту / свой
 * текст, «Вставить списком», «Из Excel».
 */
@Component({
  selector: 'app-pr-lines',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RegistryChipComponent, SiteProductSearchComponent, ImportGridComponent],
  template: `
    <section class="card">
      <div class="sec-head">
        <h3>Позиции <span class="count">({{ view.lines.length }})</span></h3>
        <div class="sec-actions" *ngIf="!readonly">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="openPaste()">Вставить списком</button>
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="openExcel()">Из Excel</button>
        </div>
      </div>

      <p class="empty-lines" *ngIf="!view.lines.length">
        Позиций пока нет{{ readonly ? '' : ' — найдите товар на сайте или впишите своё ниже' }}.
      </p>

      <ol class="lines" *ngIf="view.lines.length">
        <li class="line" *ngFor="let l of view.lines; trackBy: trackLine" [class.open]="openId === l.lotId">
          <div class="row">
            <span class="no">{{ l.lineNo }}</span>
            <ng-container *ngIf="!readonly; else roTpl">
              <input class="f-name" [value]="l.name" maxlength="255" aria-label="Наименование"
                     (blur)="commit(l, 'NAME', $event)" (keydown.enter)="blurTarget($event)" />
              <input class="f-brand" [value]="l.brand || ''" maxlength="255" placeholder="Бренд" aria-label="Бренд"
                     (blur)="commit(l, 'BRAND', $event)" (keydown.enter)="blurTarget($event)" />
              <span class="f-qty">
                <input type="number" min="1" step="1" inputmode="numeric" [value]="l.quantity ?? 1" aria-label="Количество"
                       (blur)="commit(l, 'QUANTITY', $event)" (keydown.enter)="blurTarget($event)" />
                <span class="unit">{{ l.unit || 'шт' }}</span>
              </span>
            </ng-container>
            <ng-template #roTpl>
              <span class="f-name ro">{{ l.name }}</span>
              <span class="f-brand ro">{{ l.brand || '—' }}</span>
              <span class="f-qty ro">{{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span>
            </ng-template>
            <span class="f-reg">
              <app-registry-chip *ngIf="isKz" [requestId]="view.id" [line]="l" [readonly]="readonly"
                                 (changed)="onLine($event)"></app-registry-chip>
            </span>
            <button type="button" class="btn btn-more" (click)="toggle(l)" [attr.aria-expanded]="openId === l.lotId"
                    [attr.aria-label]="'Подробнее: ' + l.name">⋯</button>
          </div>
          <div class="details" *ngIf="openId === l.lotId">
            <label class="spec">Характеристики
              <textarea rows="3" maxlength="10000" [value]="l.spec || ''" [disabled]="readonly"
                        placeholder="Что важно клиенту: размеры, комплектация, класс…" (blur)="commit(l, 'SPEC', $event)"></textarea>
            </label>
            <label class="unit-field">Ед. изм.
              <input [value]="l.unit || ''" list="pr-units" maxlength="30" placeholder="шт" [disabled]="readonly"
                     (blur)="commit(l, 'UNIT', $event)" (keydown.enter)="blurTarget($event)" />
            </label>
            <div class="detail-actions">
              <a *ngIf="l.productUrl" [href]="l.productUrl" target="_blank" rel="noopener">на сайте ↗</a>
              <ng-container *ngIf="!readonly">
                <button type="button" class="btn btn-line del" [disabled]="l.requested || busy" (click)="remove(l)">Удалить</button>
                <span class="hint" *ngIf="l.requested">Удалить нельзя: по позиции есть запросы цен</span>
              </ng-container>
            </div>
          </div>
        </li>
      </ol>
      <datalist id="pr-units"><option *ngFor="let u of units" [value]="u"></option></datalist>

      <app-site-product-search *ngIf="!readonly" [siteEnabled]="isKz" (pickProduct)="addFromSite($event)"
                               (addText)="addText($event)"></app-site-product-search>
    </section>

    <div class="overlay" *ngIf="pasteOpen" (click)="pasteOpen = false">
      <div class="modal" role="dialog" aria-label="Вставить списком" (click)="$event.stopPropagation()">
        <h3>Вставить списком</h3>
        <p class="hint">Каждая строка — позиция. Количество в конце строки: «— 2 шт», «x3», «(2)».</p>
        <textarea rows="8" [(ngModel)]="pasteText" aria-label="Текст списка"
                  placeholder="Тонометр OMRON M2 — 5 шт&#10;Кушетка медицинская x2"></textarea>
        <div class="form-actions">
          <button type="button" class="btn btn-line" [disabled]="busy || !pasteText.trim()" (click)="parsePaste()">Разобрать</button>
        </div>
        <table class="parsed" *ngIf="parsed.length">
          <thead><tr><th></th><th>Наименование</th><th>Кол-во</th><th>Ед.</th></tr></thead>
          <tbody>
            <tr *ngFor="let p of parsed">
              <td><input type="checkbox" [(ngModel)]="p.on" aria-label="Добавить строку" /></td>
              <td><input [(ngModel)]="p.name" maxlength="255" aria-label="Наименование" /></td>
              <td><input type="number" min="1" [(ngModel)]="p.quantity" aria-label="Количество" /></td>
              <td><input [(ngModel)]="p.unit" maxlength="30" list="pr-units" aria-label="Ед. изм." /></td>
            </tr>
          </tbody>
        </table>
        <div class="error-banner" *ngIf="dialogError">{{ dialogError }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="busy || !checkedParsed()" (click)="addParsed()">
            Добавить {{ checkedParsed() }} поз.</button>
          <button type="button" class="btn btn-cancel" (click)="pasteOpen = false">Отмена</button>
        </div>
      </div>
    </div>

    <div class="overlay" *ngIf="excelOpen" (click)="excelOpen = false">
      <div class="modal wide" role="dialog" aria-label="Позиции из Excel" (click)="$event.stopPropagation()">
        <h3>Позиции из Excel</h3>
        <input type="file" accept=".xlsx,.xls" (change)="onExcelFile($event)" aria-label="Файл Excel" />
        <p class="hint">Система разметит колонки сама — поправьте, если ошиблась.</p>
        <app-import-grid *ngIf="excelPreview" [preview]="excelPreview"></app-import-grid>
        <div class="mode" *ngIf="excelPreview && view.lines.length">
          <label class="check"><input type="radio" name="xmode" [(ngModel)]="excelMode" value="APPEND" /> Добавить к позициям</label>
          <label class="check"><input type="radio" name="xmode" [(ngModel)]="excelMode" value="REPLACE" />
            Заменить позиции (запрошенные у поставщиков останутся)</label>
        </div>
        <div class="error-banner" *ngIf="dialogError">{{ dialogError }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="busy || !excelPreview" (click)="importExcel()">Загрузить позиции</button>
          <button type="button" class="btn btn-cancel" (click)="excelOpen = false">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
    h3 { margin: 0; font-size: 15px; }
    .count { color: var(--text-muted); font-weight: 400; }
    .sec-actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .empty-lines { margin: 4px 0; font-size: 13px; color: var(--text-muted); }
    .lines { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
    .line { border-bottom: 1px solid var(--border); padding: 6px 0; }
    .line.open { background: color-mix(in srgb, var(--accent) 4%, var(--surface)); }
    .row { display: grid; grid-template-columns: 28px minmax(0, 1fr) 160px 110px auto 40px; gap: 8px; align-items: center; }
    .no { color: var(--text-muted); font-size: 13px; text-align: right; }
    .row input { width: 100%; box-sizing: border-box; padding: 6px 8px; border: 1px solid transparent; border-radius: 6px;
                 background: transparent; color: var(--text); font: inherit; font-size: 14px; }
    .row input:hover { border-color: var(--border); }
    .row input:focus { border-color: var(--accent); background: var(--surface); outline: none; }
    .f-qty { display: flex; align-items: center; gap: 4px; }
    .f-qty input { width: 64px; }
    .unit { font-size: 12px; color: var(--text-muted); }
    .ro { font-size: 14px; color: var(--text); overflow-wrap: anywhere; }
    .f-reg { display: flex; justify-content: flex-end; }
    .details { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 10px 12px; padding: 8px 0 6px 36px; }
    .details label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    .details textarea, .details input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px;
                                         background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .details textarea { resize: vertical; }
    .detail-actions { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
    .detail-actions a { color: var(--accent); text-decoration: none; }
    .del { color: var(--danger-text); }
    .hint { font-size: 12px; color: var(--text-muted); }
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 680px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    .modal.wide { max-width: 960px; }
    .modal h3 { margin: 0; }
    .modal textarea { padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                      color: var(--text); font: inherit; font-size: 14px; resize: vertical; }
    .parsed { width: 100%; border-collapse: collapse; font-size: 13px; }
    .parsed th { text-align: left; padding: 4px 6px; font-size: 12px; color: var(--text-muted); }
    .parsed td { padding: 3px 6px; }
    .parsed input:not([type="checkbox"]) { width: 100%; box-sizing: border-box; padding: 5px 8px; border: 1px solid var(--border);
                                            border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .parsed td:nth-child(3) { width: 80px; }
    .parsed td:nth-child(4) { width: 90px; }
    .mode { display: flex; flex-direction: column; gap: 6px; }
    .check { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text); }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .row { grid-template-columns: 22px minmax(0, 1fr) 40px; grid-template-areas: "no name more" ". brand brand" ". qty reg"; row-gap: 4px; }
      .no { grid-area: no; }
      .f-name { grid-area: name; }
      .f-brand { grid-area: brand; }
      .f-qty { grid-area: qty; }
      .f-reg { grid-area: reg; }
      .btn-more { grid-area: more; min-width: 40px; min-height: 40px; }
      .row input { font-size: 16px; min-height: 40px; border-color: var(--border); background: var(--surface); }
      .f-qty input { width: 80px; }
      .details { grid-template-columns: 1fr; padding-left: 0; }
      .details textarea, .details input { font-size: 16px; min-height: 40px; }
      .sec-actions .btn, .detail-actions .btn { min-height: 40px; }
      .overlay { padding: 0; align-items: stretch; }
      .modal, .modal.wide { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .parsed input:not([type="checkbox"]), .modal textarea { font-size: 16px; }
    }
  `],
})
export class PrLinesComponent {
  @Input() view!: PrView;
  @Input() readonly = false;
  @Input() isKz = true;
  @Output() viewChange = new EventEmitter<PrView>();
  @Output() saveState = new EventEmitter<SaveState>();

  readonly units = UNIT_SUGGESTIONS;
  openId: number | null = null;
  busy = false;
  pasteOpen = false;
  pasteText = '';
  parsed: ParsedRow[] = [];
  excelOpen = false;
  excelPreview: any = null;
  excelMode: LinesMode = 'APPEND';
  dialogError = '';

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  trackLine(_: number, l: PrLine) {
    return l.lotId;
  }

  toggle(l: PrLine) {
    this.openId = this.openId === l.lotId ? null : l.lotId;
  }

  blurTarget(e: Event) {
    (e.target as HTMLElement).blur();
  }

  private fieldValue(l: PrLine, field: PrLineField): string {
    switch (field) {
      case 'NAME': return l.name || '';
      case 'BRAND': return l.brand || '';
      case 'QUANTITY': return String(l.quantity ?? 1);
      case 'UNIT': return l.unit || '';
      case 'SPEC': return l.spec || '';
      default: return '';
    }
  }

  commit(l: PrLine, field: PrLineField, e: Event) {
    const el = e.target as HTMLInputElement | HTMLTextAreaElement;
    const raw = el.value.trim();
    if (raw === this.fieldValue(l, field).trim()) return;
    if (field === 'NAME' && !raw) {
      el.value = l.name;
      this.notify.error('Наименование не может быть пустым');
      return;
    }
    if (field === 'QUANTITY') {
      const n = Number(raw);
      if (!Number.isInteger(n) || n < 1 || n > 1_000_000) {
        el.value = String(l.quantity ?? 1);
        this.notify.error('Количество — целое число от 1');
        return;
      }
    }
    this.saveLine(l.lotId, field, raw || null);
  }

  private saveLine(lotId: number, field: PrLineField, value: string | null) {
    this.saveState.emit({ kind: 'saving' });
    this.api.updatePrivateRequestLine(this.view.id, lotId, field, value)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: line => { this.saveState.emit({ kind: 'saved' }); this.onLine(line); },
        error: err => {
          this.saveState.emit({ kind: 'error', message: errorText(err), retry: () => this.saveLine(lotId, field, value) });
          this.cdr.markForCheck();
        },
      });
  }

  /** Позиция после правки или действия с РУ — заменить в виде заявки и отдать странице. */
  onLine(line: PrLine) {
    this.viewChange.emit({ ...this.view, lines: this.view.lines.map(x => x.lotId === line.lotId ? line : x) });
  }

  addFromSite(p: SiteProduct) {
    this.add([{ name: p.name, brand: p.brand, productUrl: p.url, quantity: 1 }]);
  }

  addText(text: string) {
    this.add([{ name: text, quantity: 1 }]);
  }

  private add(lines: LineInput[], mode: LinesMode = 'APPEND', done?: () => void) {
    this.busy = true;
    this.saveState.emit({ kind: 'saving' });
    this.api.addPrivateRequestLines(this.view.id, lines, mode).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.saveState.emit({ kind: 'saved' }); this.viewChange.emit(v); done?.(); this.cdr.markForCheck(); },
      error: err => {
        this.busy = false;
        this.saveState.emit({ kind: 'error', message: errorText(err) });
        this.dialogError = errorText(err);
        this.cdr.markForCheck();
      },
    });
  }

  remove(l: PrLine) {
    this.confirm.ask(`Удалить позицию «${l.name}»?`, undefined, { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        this.busy = true;
        this.api.deletePrivateRequestLine(this.view.id, l.lotId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: v => { this.busy = false; this.openId = null; this.viewChange.emit(v); this.cdr.markForCheck(); },
          error: err => { this.busy = false; this.notify.error(errorText(err)); this.cdr.markForCheck(); },
        });
      });
  }

  openPaste() {
    this.pasteOpen = true;
    this.pasteText = '';
    this.parsed = [];
    this.dialogError = '';
  }

  parsePaste() {
    this.busy = true;
    this.dialogError = '';
    this.api.parsePrivateRequestLines(this.view.id, this.pasteText).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: rows => {
        this.busy = false;
        this.parsed = rows.map(r => ({ ...r, on: true }));
        if (!rows.length) this.dialogError = 'Не нашлось ни одной строки с наименованием';
        this.cdr.markForCheck();
      },
      error: err => { this.busy = false; this.dialogError = errorText(err); this.cdr.markForCheck(); },
    });
  }

  checkedParsed(): number {
    return this.parsed.filter(p => p.on && p.name && p.name.trim()).length;
  }

  addParsed() {
    const lines: LineInput[] = this.parsed
      .filter(p => p.on && p.name && p.name.trim())
      .map(p => ({ name: p.name.trim(), quantity: Math.max(1, Math.floor(Number(p.quantity) || 1)), unit: p.unit || null }));
    this.add(lines, 'APPEND', () => { this.pasteOpen = false; this.notify.success(`Добавлено позиций: ${lines.length}`); });
  }

  openExcel() {
    this.excelOpen = true;
    this.excelPreview = null;
    this.excelMode = 'APPEND';
    this.dialogError = '';
  }

  onExcelFile(e: Event) {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.dialogError = '';
    this.busy = true;
    this.api.previewImport(file).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.busy = false; this.excelPreview = p; this.cdr.markForCheck(); },
      error: err => { this.busy = false; this.dialogError = errorText(err) || 'Не удалось прочитать файл'; this.cdr.markForCheck(); },
    });
  }

  importExcel() {
    const built = buildImportLines(this.excelPreview);
    if (built.error) {
      this.dialogError = built.error;
      return;
    }
    this.busy = true;
    this.api.importPrivateRequestLines(this.view.id, { mappings: built.mappings, lines: built.lines, mode: this.excelMode })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: v => {
          this.busy = false;
          this.excelOpen = false;
          this.viewChange.emit(v);
          this.notify.success(`Позиции из файла: ${built.lines.length}`);
          this.cdr.markForCheck();
        },
        error: err => { this.busy = false; this.dialogError = errorText(err); this.cdr.markForCheck(); },
      });
  }
}
```

- [ ] **Step 2: Вставить раздел и проверку РУ при открытии**

В `private-request-page.component.ts`:
- импорты `import { PrLinesComponent } from './pr-lines.component';`, `import { MarketService } from '../../services/market.service';`; `PrLinesComponent` в `imports`;
- в конструктор добавить `private market: MarketService`;
- после `<app-pr-client-section …></app-pr-client-section>` вставить:

```html
      <app-pr-lines [view]="v" [readonly]="!auth.isAdmin()" [isKz]="isKz" (viewChange)="onView($event)"
                    (saveState)="onSaveState($event)"></app-pr-lines>
```

- добавить геттер `get isKz() { return this.market.value === 'KZ'; }`;
- метод `afterLoad` заменить:

```ts
  /** Позиции без статуса РУ (заявки до V26, старые пути) — одна проверка при открытии; только KZ и только админ. */
  private afterLoad() {
    const v = this.view;
    if (!v || !this.isKz || !this.auth.isAdmin() || !v.lines.some(l => l.regStatus == null)) return;
    this.api.checkPrivateRequestRegistry(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { if (this.view?.id === nv.id) { this.view = nv; this.cdr.markForCheck(); } },
      error: () => {},   // не вышло — метки остаются «РУ —», повтор при следующем открытии
    });
  }
```

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: SUCCESS; бюджет `anyComponentStyle` (24 kB) не превышен.

- [ ] **Step 4: Живая проверка позиций**

Стек поднят. Поиск по сайту локально ходит в прод westmed.kz — это публичный список, только чтение (не карточка товара).
- **Поиск по сайту:** в новой заявке вписать «рециркулятор» → список товаров сайта (наименование · «Завод ЭМА» · артикул). Выбор → позиция «1» с брендом; в «⋯» — ссылка «на сайте ↗» открывает товар.
- **Свой текст:** «Кушетка медицинская» + Enter → позиция «как есть», метка «РУ не найдено».
- **Метка РУ:**
  - у позиции с брендом метка «РУ?» → окошко с записью реестра → «Подтвердить» → «РУ ✓»;
  - «Не то — найти в реестре» → кандидаты, свой запрос, выбор → «РУ ✓» с другой записью;
  - «Не подлежит» → «не подлежит»;
  - «Вернуть проверку» → снова подсказка;
  - открыть вторую метку — первая закрывается.
- **Правка:**
  - наименование — по выходу из поля «Сохранено» (у подтверждённой метка сбросилась на подсказку);
  - количество «0» → тост, значение вернулось;
  - характеристики и ед. изм. в «⋯» сохраняются;
  - ед. изм. показывается рядом с количеством.
- **«Вставить списком»** — текст:
  ```
  1. Тонометр OMRON M2 — 5 шт
  Кровать КФ-2
  Ширма x3
  ```
  «Разобрать» даёт 5 / 1 / 3. Снять галочку, поправить строку → «Добавить 2 поз.».
- **«Из Excel»:** файл сметы → грид → «Загрузить позиции» добавляет в конец. Повторно с «Заменить» — замена.
- **Удаление** с подтверждением; номера сплошные.
- **Заявка до V26** (локальная `ЧЗ-2026-0001`): при открытии метки проставились сами (одна проверка).
- **390 px:** строка — наименование на всю ширину, бренд строкой ниже, количество и метка — ещё ниже. Всё в пределах экрана, цели 40 px. Окошко РУ — снизу экрана; модалки — на весь экран.
- **Обе темы;** оператор — только чтение (метки открываются, кнопок действий нет).

- [ ] **Step 5: Коммит**

```bash
git add frontend/src/app/pages/private-requests/pr-lines.component.ts frontend/src/app/pages/private-requests/private-request-page.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): раздел «Позиции» — правка по полю, сайт / свой текст / список / Excel, метка РУ

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Раздел «Поставщики и цены» — сводка, окно «Запросить цены», кнопка внизу на телефоне

**Files:**
- Create: `frontend/src/app/pages/private-requests/pr-supply.component.ts`
- Create: `frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-page.component.ts` (раздел, `refresh()`, нижняя панель)

**Interfaces:**
- Consumes:
  - `ApiService`: `getPrivateRequestSourcing`, `previewRfq`, `sendRfq`, `getDistributors`, `getPrivateRequest`;
  - типы `PrView`, `PrLine`, `Sourcing`, `SendResult`; функции `plural`, `dateText`; `errorText`.
- Produces:
  - **`<app-pr-supply #supply [view] [readonly] (changed)>`** — метод `openRfq()`, поле `pricesVersion` (растёт после отправки; задача 15 подписывает на него таблицу цен), якорь шаблона `<!-- prices -->`; функция `rfqToast(results)` — текст итога отправки;
  - **`<app-pr-rfq-dialog [requestId] [lines] (close) (sent)="SendResult[]">`**;
  - **страница:** `refresh()` — перечитать вид без «Загрузка…».

- [ ] **Step 1: Окно «Запросить цены»**

`frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { PrLine, SendResult, Sourcing, dateText, plural } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';

interface DlgLine {
  lotId: number;
  name: string;
  quantity: number | null;
  unit: string | null;
  /** BRAND / TYPE — из подбора; null — поставщик добавлен из справочника. */
  reason: 'BRAND' | 'TYPE' | null;
  label: string;
  requestedAt: string | null;
  on: boolean;
}

interface DlgSupplier {
  distributorId: number;
  name: string;
  email: string | null;
  hasEmail: boolean;
  lines: DlgLine[];
}

/**
 * «Запросить цены» (спека 2026-10-12 §6.2, этап 1): поставщики с причинами подбора и отметками по умолчанию, позиции
 * у каждого, «＋ Добавить поставщика» из справочника, «Ответить до», предпросмотр письма, отправка — каждому одно письмо
 * только с его позициями. Почта — из карточки поставщика (вписать свою — этап 2).
 */
@Component({
  selector: 'app-pr-rfq-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="close.emit()">
      <div class="modal" role="dialog" aria-label="Запросить цены" (click)="$event.stopPropagation()">
        <h3>Запросить цены<ng-container *ngIf="suppliers.length"> — {{ suppliers.length }} {{ supplierWord(suppliers.length) }}</ng-container></h3>
        <div class="empty" *ngIf="loading">Подбираю поставщиков…</div>
        <div class="error-banner" *ngIf="error">{{ error }}</div>

        <div class="sup" *ngFor="let s of suppliers" [class.on]="isOn(s)">
          <label class="sup-head">
            <input type="checkbox" [checked]="isOn(s)" (change)="toggleSupplier(s, $any($event.target).checked)"
                   [attr.aria-label]="'Запросить у ' + s.name" />
            <span class="sup-name">{{ s.name }}</span>
            <span class="sup-mail" [class.none]="!s.hasEmail">{{ s.hasEmail ? s.email : 'нет почты' }}</span>
          </label>
          <ul class="sup-lines">
            <li *ngFor="let l of s.lines">
              <label class="check">
                <input type="checkbox" [(ngModel)]="l.on" />
                <span class="ln">{{ l.name }} · {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span>
                <span class="why" *ngIf="l.label">{{ l.label }}</span>
                <span class="was" *ngIf="l.requestedAt">запрошено {{ shortDate(l.requestedAt) }}</span>
              </label>
            </li>
          </ul>
          <p class="warn" *ngIf="isOn(s) && !s.hasEmail">Письмо не уйдёт — запрос сохранится, цену внесёте вручную (например, по телефону).</p>
          <button type="button" class="link" [disabled]="!isOn(s) || previewBusy" (click)="showPreview(s)">Посмотреть письмо</button>
        </div>

        <p class="hint" *ngIf="!loading && unmatched.length">Без поставщика: {{ unmatchedText() }} — добавьте поставщика ниже.</p>

        <div class="add" *ngIf="!loading">
          <button type="button" class="btn btn-line" *ngIf="!adding" (click)="startAdd()">＋ Добавить поставщика</button>
          <div class="add-box" *ngIf="adding">
            <input type="search" [(ngModel)]="addQuery" placeholder="Поставщик из справочника…" aria-label="Поиск поставщика" />
            <ul class="add-list">
              <li *ngFor="let d of addOptions()">
                <button type="button" (click)="addSupplier(d)">
                  <span>{{ d.name }}</span><span class="sup-mail" [class.none]="!d.email">{{ d.email || 'нет почты' }}</span>
                </button>
              </li>
            </ul>
            <button type="button" class="btn btn-cancel" (click)="adding = false">Готово</button>
          </div>
        </div>

        <label class="reply">Ответить до <input type="date" [(ngModel)]="replyBy" [min]="today" /></label>

        <div class="preview" *ngIf="preview">
          <div class="pv-head">Письмо для «{{ previewFor }}»</div>
          <div class="pv-subject">Тема: {{ preview.subject }}</div>
          <pre class="pv-body">{{ preview.body }}</pre>
        </div>

        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="sending || !planCount()" (click)="send()">{{ sendLabel() }}</button>
          <button type="button" class="btn btn-cancel" (click)="close.emit()">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 760px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    h3 { margin: 0; }
    .sup { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; }
    .sup.on { background: color-mix(in srgb, var(--accent) 6%, var(--surface)); border-color: var(--accent); }
    .sup-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
    .sup-name { font-weight: 600; }
    .sup-mail { font-size: 12px; color: var(--text-muted); }
    .sup-mail.none { color: var(--warn-text); }
    .sup-lines { list-style: none; margin: 0; padding: 0 0 0 24px; display: flex; flex-direction: column; gap: 4px; }
    .check { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
    .why, .was { font-size: 12px; color: var(--text-muted); }
    .was { color: var(--warn-text); }
    .warn { margin: 0; font-size: 12px; color: var(--warn-text); }
    .link { align-self: flex-start; background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; }
    .link:disabled { color: var(--text-muted); cursor: default; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .add-box { display: flex; flex-direction: column; gap: 6px; }
    .add-box input, .reply input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                                    color: var(--text); font: inherit; font-size: 14px; }
    .add-list { list-style: none; margin: 0; padding: 0; max-height: 200px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
    .add-list button { width: 100%; display: flex; justify-content: space-between; gap: 8px; text-align: left; background: var(--surface);
                       border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; cursor: pointer; color: var(--text); }
    .add-list button:hover { background: var(--surface-2); }
    .reply { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .preview { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; background: var(--surface-2); }
    .pv-head { font-size: 12px; color: var(--text-muted); }
    .pv-subject { font-size: 13px; font-weight: 600; margin: 4px 0; }
    .pv-body { margin: 0; white-space: pre-wrap; font-family: inherit; font-size: 13px; color: var(--text); }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .sup-lines { padding-left: 0; }
      .check { min-height: 40px; }
      .add-box input, .reply input { font-size: 16px; min-height: 40px; }
      .form-actions .btn, .add .btn { min-height: 40px; }
      .link { min-height: 40px; }
    }
  `],
})
export class PrRfqDialogComponent implements OnInit {
  @Input() requestId!: number;
  @Input() lines: PrLine[] = [];
  @Output() close = new EventEmitter<void>();
  @Output() sent = new EventEmitter<SendResult[]>();

  suppliers: DlgSupplier[] = [];
  unmatched: { lotId: number; name: string }[] = [];
  loading = true;
  error = '';
  sending = false;
  adding = false;
  addQuery = '';
  replyBy = '';
  /** Сегодня по часам браузера (toISOString — UTC: до 05:00 по Уральску дал бы вчерашнее число). */
  readonly today = (() => {
    const d = new Date();
    d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
    return d.toISOString().slice(0, 10);
  })();
  preview: { subject: string; body: string } | null = null;
  previewFor = '';
  previewBusy = false;
  private allDistributors: { id: number; name: string; email: string | null }[] = [];

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnInit() {
    this.api.getPrivateRequestSourcing(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (s: Sourcing) => {
        this.suppliers = s.suppliers.map(x => ({
          distributorId: x.distributorId, name: x.name, email: x.email, hasEmail: x.hasEmail,
          lines: x.lines.map(l => ({ lotId: l.lotId, name: l.name, quantity: l.quantity, unit: l.unit, reason: l.reason,
                                     label: l.label, requestedAt: l.requestedAt, on: l.preselected })),
        }));
        this.unmatched = s.unmatched;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => { this.loading = false; this.error = 'Подбор поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.close.emit();
  }

  supplierWord(n: number) {
    return plural(n, 'поставщик', 'поставщика', 'поставщиков');
  }

  /** «12.10» — как в спеке: «запрошено ДД.ММ». */
  shortDate(iso: string | null): string {
    return dateText(iso).slice(0, 5);
  }

  isOn(s: DlgSupplier): boolean {
    return s.lines.some(l => l.on);
  }

  /** Отметить поставщика: позиции с совпавшим брендом, ещё не запрошенные; если таких нет — все незапрошенные; иначе все. */
  toggleSupplier(s: DlgSupplier, on: boolean) {
    if (!on) {
      s.lines.forEach(l => l.on = false);
      return;
    }
    const brand = s.lines.filter(l => l.reason === 'BRAND' && !l.requestedAt);
    const fresh = s.lines.filter(l => !l.requestedAt);
    (brand.length ? brand : fresh.length ? fresh : s.lines).forEach(l => l.on = true);
  }

  unmatchedText(): string {
    const names = this.unmatched.map(u => u.name);
    return names.slice(0, 3).join(', ') + (names.length > 3 ? ` и ещё ${names.length - 3}` : '');
  }

  startAdd() {
    this.adding = true;
    this.addQuery = '';
    if (this.allDistributors.length) return;
    this.api.getDistributors().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.allDistributors = (list || []).map((d: any) => ({ id: d.id, name: d.name, email: d.email || null }));
        this.cdr.markForCheck();
      },
      error: e => { this.error = 'Справочник поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  addOptions() {
    const q = this.addQuery.trim().toLowerCase();
    const taken = new Set(this.suppliers.map(s => s.distributorId));
    return this.allDistributors
      .filter(d => !taken.has(d.id) && (!q || d.name.toLowerCase().includes(q)))
      .slice(0, 10);
  }

  /** Поставщик из справочника — со всеми позициями заявки, отмеченными (оператор снимет лишние). */
  addSupplier(d: { id: number; name: string; email: string | null }) {
    const email = d.email && d.email.trim() ? d.email.trim() : null;
    this.suppliers = [...this.suppliers, {
      distributorId: d.id, name: d.name, email, hasEmail: !!email,
      lines: this.lines.map(l => ({ lotId: l.lotId, name: l.name, quantity: l.quantity, unit: l.unit, reason: null,
                                    label: '', requestedAt: null, on: true })),
    }];
    this.unmatched = [];
    this.adding = false;
  }

  showPreview(s: DlgSupplier) {
    const lotIds = s.lines.filter(l => l.on).map(l => l.lotId);
    if (!lotIds.length) return;
    this.previewBusy = true;
    this.api.previewRfq(this.requestId, { distributorId: s.distributorId, lotIds, replyBy: this.replyBy || null })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: p => { this.previewBusy = false; this.preview = p; this.previewFor = s.name; this.cdr.markForCheck(); },
        error: e => { this.previewBusy = false; this.error = errorText(e); this.cdr.markForCheck(); },
      });
  }

  private plan() {
    return this.suppliers
      .map(s => ({ s, lotIds: s.lines.filter(l => l.on).map(l => l.lotId) }))
      .filter(p => p.lotIds.length > 0);
  }

  planCount(): number {
    return this.plan().length;
  }

  sendLabel(): string {
    const plan = this.plan();
    const mails = plan.filter(p => p.s.hasEmail).length;
    const noMail = plan.length - mails;
    if (!plan.length) return 'Отметьте поставщиков';
    if (!mails) return `Сохранить ${noMail} ${plural(noMail, 'запрос', 'запроса', 'запросов')} без письма`;
    return `Отправить ${mails} ${plural(mails, 'письмо', 'письма', 'писем')}` + (noMail ? ` · без письма: ${noMail}` : '');
  }

  send() {
    const plan = this.plan();
    if (!plan.length) return;
    this.sending = true;
    this.error = '';
    this.api.sendRfq(this.requestId, {
      replyBy: this.replyBy || null,
      suppliers: plan.map(p => ({ distributorId: p.s.distributorId, lotIds: p.lotIds })),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: results => { this.sending = false; this.sent.emit(results); },
      error: e => { this.sending = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 2: Раздел «Поставщики и цены»**

`frontend/src/app/pages/private-requests/pr-supply.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { PrView, SendResult, Sourcing, plural } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { PrRfqDialogComponent } from './pr-rfq-dialog.component';

/** Итог отправки (спека §6.2): «Отправлено 2 из 3; «Жетысу-Мед» — без письма (нет почты)». */
export function rfqToast(results: SendResult[]): string {
  const sent = results.filter(r => r.emailSent).length;
  const parts = [`Отправлено ${sent} из ${results.length}`];
  for (const r of results) {
    if (r.emailSent) continue;
    parts.push(`«${r.distributorName}» — ` + (r.reason === 'NO_EMAIL' ? 'без письма (нет почты)' : 'письмо не ушло (ошибка почты)'));
  }
  return parts.join('; ');
}

/**
 * «Поставщики и цены» (спека 2026-10-12 §6–§7): сводка подбора, «Запросить цены…», таблица цен (задача 15).
 * После отправки сообщает странице — этап заявки меняется.
 */
@Component({
  selector: 'app-pr-supply',
  standalone: true,
  imports: [NgIf, PrRfqDialogComponent],
  template: `
    <section class="card">
      <div class="sec-head">
        <h3>Поставщики и цены</h3>
        <button type="button" class="btn btn-primary" *ngIf="canRequest()" (click)="openRfq()">Запросить цены…</button>
      </div>
      <p class="summary" *ngIf="view.lines.length && sourcing">{{ summary() }}</p>
      <p class="hint" *ngIf="!view.lines.length">Добавьте позиции — здесь появятся поставщики, у которых их можно запросить.</p>
      <p class="hint warn" *ngIf="sourcingError">{{ sourcingError }}</p>
      <!-- prices -->
    </section>
    <app-pr-rfq-dialog *ngIf="rfqOpen" [requestId]="view.id" [lines]="view.lines" (close)="rfqOpen = false"
                       (sent)="onSent($event)"></app-pr-rfq-dialog>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 15px; }
    .summary { margin: 8px 0 0; font-size: 13px; color: var(--text); }
    .hint { margin: 8px 0 0; font-size: 12px; color: var(--text-muted); }
    .hint.warn { color: var(--warn-text); }
  `],
})
export class PrSupplyComponent implements OnChanges {
  @Input() view!: PrView;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<void>();

  sourcing: Sourcing | null = null;
  sourcingError = '';
  rfqOpen = false;
  /** Растёт после каждой отправки и правки ответа — таблица цен перечитывается. */
  pricesVersion = 0;
  private linesKey = '';
  private sub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (!ch['view']) return;
    // подбор зависит от позиций (наименование, бренд), а не от контакта — перечитываем только при их изменении
    const key = this.view.id + '|' + this.view.lines.map(l => `${l.lotId}:${l.brand || ''}:${l.name}`).join(';');
    if (key !== this.linesKey) {
      this.linesKey = key;
      this.loadSourcing();
    }
  }

  canRequest(): boolean {
    return !this.readonly && this.view.status === 'NEW' && this.view.lines.length > 0;
  }

  openRfq() {
    if (this.canRequest()) this.rfqOpen = true;
  }

  private loadSourcing() {
    this.sub?.unsubscribe();
    if (!this.view.lines.length) {
      this.sourcing = null;
      return;
    }
    this.sub = this.api.getPrivateRequestSourcing(this.view.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { this.sourcing = s; this.sourcingError = ''; this.cdr.markForCheck(); },
      error: e => { this.sourcing = null; this.sourcingError = 'Подбор поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  summary(): string {
    const s = this.sourcing;
    if (!s) return '';
    if (!s.suppliers.length) return 'Подходящих поставщиков в справочнике нет — добавьте поставщика в окне запроса цен.';
    const matched = new Set<number>();
    s.suppliers.forEach(x => x.lines.forEach(l => matched.add(l.lotId)));
    const n = s.suppliers.length;
    const k = this.view.lines.length;
    let text = `Подходят ${n} ${plural(n, 'поставщик', 'поставщика', 'поставщиков')} для ${matched.size} из ${k} `
      + plural(k, 'позиции', 'позиций', 'позиций');
    if (s.unmatched.length) {
      const names = s.unmatched.map(u => u.name);
      text += ' · без поставщика: ' + names.slice(0, 3).join(', ') + (names.length > 3 ? ` и ещё ${names.length - 3}` : '');
    }
    return text;
  }

  onSent(results: SendResult[]) {
    this.rfqOpen = false;
    // «без письма» оператор выбрал сам (предупреждение в окне) — красным только сбой отправки
    if (results.some(r => r.reason === 'SEND_FAILED')) this.notify.error(rfqToast(results));
    else this.notify.success(rfqToast(results));
    this.pricesVersion++;
    this.loadSourcing();
    this.changed.emit();
  }
}
```

- [ ] **Step 3: Страница — раздел, перечитывание, кнопка внизу на телефоне**

В `private-request-page.component.ts`:
- импорты `ViewChild` (из `@angular/core`) и `import { PrSupplyComponent } from './pr-supply.component';`; `PrSupplyComponent` в `imports`;
- после `<app-pr-lines …></app-pr-lines>` вставить:

```html
      <app-pr-supply #supply [view]="v" [readonly]="!auth.isAdmin()" (changed)="refresh()"></app-pr-supply>
      <div class="pr-bar" *ngIf="auth.isAdmin() && v.stage === 'NEW'">
        <button type="button" class="btn btn-primary" (click)="supply?.openRfq()">Запросить цены</button>
      </div>
```

- в класс:

```ts
  @ViewChild('supply') supply?: PrSupplyComponent;

  /** Перечитать вид без «Загрузка…» — после отправки запросов и правки ответов этап меняется на сервере. */
  refresh() {
    const v = this.view;
    if (!v) return;
    this.api.getPrivateRequest(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { if (this.view?.id === nv.id) { this.view = nv; this.cdr.markForCheck(); } },
      error: () => {},
    });
  }
```

- в `styles` до блока `@media`:

```css
    .pr-bar { display: none; }
```

а внутри `@media (max-width: 900px)`:

```css
      /* липкая панель следующего шага (спека §5.6): «Запросить цены» — пока запросов нет (этап «Новая»); «КП клиенту» при
         ценах — этап 3. Граница — край прокрутки: нижний отступ main.content на телефоне 12 px */
      .pr-bar { display: flex; position: sticky; bottom: -12px; z-index: 5; justify-content: stretch;
                padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--surface);
                border-top: 1px solid var(--border); box-shadow: var(--shadow); }
      .pr-bar .btn { flex: 1; min-height: 44px; font-size: 15px; }
```

- [ ] **Step 4: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: SUCCESS.

- [ ] **Step 5: Живая проверка окна запроса цен**

Стек поднят. Нужен MailHog: `docker run -d --name nir2-mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog` (уже запущен — `docker start nir2-mailhog`). Бэкенд по умолчанию шлёт на `localhost:1025`.
- **Подготовка:** в заявке две позиции — с брендом, который есть у поставщика в «Дистрибьюторах» (например, «Mindray»), и без бренда.
- **Сводка:** «Подходят N поставщиков для 1 из 2 позиций · без поставщика: …».
- **Окно «Запросить цены…»:**
  - отмечены поставщики бренда с почтой, «возит Mindray» у позиции;
  - поставщик без почты не отмечен; если отметить — предупреждение «Письмо не уйдёт…», кнопка «Отправить 2 письма · без письма: 1»;
  - «＋ Добавить поставщика» → поиск по справочнику → поставщик со всеми позициями;
  - «Ответить до» — завтра;
  - «Посмотреть письмо» — тема и тело для этого поставщика: только его позиции, «Просим ответить до …», без номера заявки;
  - «Отправить» → тост «Отправлено 2 из 3; «<поставщик без почты>» — без письма (нет почты)» — зелёный (без письма — выбор оператора); красный — только при сбое почты.
- **MailHog** (`http://localhost:8025`): каждому поставщику одно письмо, тема с `[КП-…]`.
- **После отправки:** чип этапа — «Запрошены цены · 0 из N ответили»; в окне позиция у этого поставщика — «запрошено ДД.ММ» без отметки.
- **390 px:** пока запросов нет (этап «Новая») — внизу экрана закреплена «Запросить цены» (не перекрывает поля — прокрутка до конца, последняя строка видна над панелью); после отправки панель пропадает, «Запросить цены…» остаётся в разделе; окно на весь экран; галочки — цели 40 px.
- **Обе темы.**

- [ ] **Step 6: Коммит**

```bash
git add frontend/src/app/pages/private-requests/pr-supply.component.ts frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts frontend/src/app/pages/private-requests/private-request-page.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): «Запросить цены» — подбор с причинами, добавить поставщика, «ответить до», предпросмотр

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Таблица цен — ячейки, лучшая цена, правка ответа и «Переслать»

**Files:**
- Create: `frontend/src/app/pages/private-requests/pr-prices.component.ts`
- Modify: `frontend/src/app/pages/private-requests/pr-supply.component.ts` (вставить таблицу)

**Interfaces:**
- Consumes: `ApiService.getPrivateRequestPrices`, `updatePriceRequestResponses(prId, updates)` (есть), `resendPriceRequest(prId)` (есть); `MarketMoneyPipe`; `parseNum`, `numText` (`shared/client-offer`); типы `Prices`, `PriceLine`, `PriceCell`.
- Produces: `<app-pr-prices [requestId] [version] [readonly] (changed)>`.

- [ ] **Step 1: Компонент таблицы цен**

`frontend/src/app/pages/private-requests/pr-prices.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { PriceCell, PriceLine, Prices, dateText } from '../../shared/private-request';
import { numText, parseNum } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';

interface Editing { line: PriceLine; cell: PriceCell; supplier: string; price: string; note: string; }

/**
 * Таблица цен (спека 2026-10-12 §7): позиции × поставщики, кому писали. Ячейка — цена (★ лучшая по позиции), «отказ»,
 * «не доставлено», «письмо не ушло», «ждём ответа» или «—». Клик — цена и примечание вручную и «↻ Переслать».
 * На телефоне — карточка на позицию: лучшая цена и поставщики с состояниями.
 */
@Component({
  selector: 'app-pr-prices',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe],
  template: `
    <div class="prices" *ngIf="prices && prices.suppliers.length">
      <div class="table-scroll desk">
        <table class="pt">
          <thead>
            <tr>
              <th class="pos">Позиция</th>
              <th *ngFor="let s of prices.suppliers">
                {{ s.name }}<div class="sent" *ngIf="s.firstSentAt">письмо {{ shortDate(s.firstSentAt) }}</div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let l of prices.lines">
              <td class="pos">{{ l.name }} <span class="q">× {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span></td>
              <td *ngFor="let c of l.cells" [class.best]="c.best">
                <button type="button" class="cell" [attr.data-state]="c.state" [disabled]="c.state === 'NONE'"
                        [attr.title]="c.note || null" (click)="edit(l, c)">{{ cellText(c) }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="mob">
        <article class="pcard" *ngFor="let l of prices.lines">
          <div class="pc-head">{{ l.name }} <span class="q">× {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span></div>
          <div class="pc-best" *ngIf="l.bestPrice != null">лучшая: {{ l.bestPrice | money:2 }} ({{ l.bestDistributorName }})</div>
          <ul>
            <ng-container *ngFor="let c of l.cells; let i = index">
              <li *ngIf="c.state !== 'NONE'">
                <button type="button" class="cell-row" [attr.data-state]="c.state" [class.best]="c.best" (click)="edit(l, c)">
                  <span>{{ prices.suppliers[i].name }}</span><span>{{ cellText(c) }}</span>
                </button>
              </li>
            </ng-container>
          </ul>
        </article>
      </div>
    </div>
    <p class="hint" *ngIf="error">{{ error }}</p>

    <div class="overlay" *ngIf="editing as ed" (click)="editing = null">
      <div class="modal" role="dialog" aria-label="Ответ поставщика" (click)="$event.stopPropagation()">
        <h3>{{ ed.supplier }}</h3>
        <p class="sub">{{ ed.line.name }} × {{ ed.line.quantity ?? '—' }} {{ ed.line.unit || 'шт' }}</p>
        <p class="hint">{{ stateHint(ed.cell) }}</p>
        <label>Цена за единицу <input inputmode="decimal" [(ngModel)]="ed.price" [disabled]="readonly" placeholder="0" /></label>
        <label>Примечание <input [(ngModel)]="ed.note" maxlength="2000" [disabled]="readonly" placeholder="срок, модель, условия" /></label>
        <div class="error-banner" *ngIf="editError">{{ editError }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" *ngIf="!readonly" [disabled]="busy" (click)="saveCell()">Сохранить</button>
          <button type="button" class="btn btn-line" *ngIf="!readonly && ed.cell.priceRequestId" [disabled]="busy" (click)="resend()">↻ Переслать письмо</button>
          <button type="button" class="btn btn-cancel" (click)="editing = null">Закрыть</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .prices { margin-top: 12px; }
    .pt { border-collapse: collapse; width: 100%; font-size: 13px; }
    .pt th { text-align: left; font-size: 12px; padding: 6px 8px; border-bottom: 1px solid var(--border); vertical-align: bottom; }
    .pt td { padding: 4px 6px; border-bottom: 1px solid var(--border); vertical-align: middle; }
    .pt td.pos, .pt th.pos { min-width: 220px; }
    .sent { font-weight: 400; color: var(--text-muted); font-size: 11px; }
    .q { color: var(--text-muted); white-space: nowrap; }
    .cell { width: 100%; text-align: left; background: none; border: 1px solid transparent; border-radius: 6px; padding: 6px 8px;
            cursor: pointer; color: var(--text); font-size: 13px; white-space: nowrap; font-variant-numeric: tabular-nums; }
    .cell:hover:not(:disabled) { border-color: var(--border); }
    .cell:disabled { cursor: default; color: var(--text-muted); }
    .cell[data-state="WAITING"] { color: var(--text-muted); }
    .cell[data-state="DECLINED"], .cell[data-state="BOUNCED"], .cell-row[data-state="DECLINED"], .cell-row[data-state="BOUNCED"] { color: var(--danger-text); }
    .cell[data-state="NOT_SENT"], .cell-row[data-state="NOT_SENT"] { color: var(--warn-text); }
    td.best { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    td.best .cell { color: var(--success-text); font-weight: 600; }
    .mob { display: none; }
    .hint { font-size: 12px; color: var(--text-muted); margin: 6px 0 0; }
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 460px; padding: 20px 22px; display: flex; flex-direction: column; gap: 10px; }
    .modal h3 { margin: 0; }
    .sub { margin: 0; font-size: 13px; color: var(--text-muted); }
    .modal label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    .modal input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                   color: var(--text); font: inherit; font-size: 14px; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .desk { display: none; }
      .mob { display: flex; flex-direction: column; gap: 8px; }
      .pcard { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: var(--surface-2); }
      .pc-head { font-weight: 600; font-size: 14px; }
      .pc-best { font-size: 13px; color: var(--success-text); margin-top: 2px; }
      .pcard ul { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
      .cell-row { width: 100%; min-height: 40px; display: flex; justify-content: space-between; gap: 8px; align-items: center;
                  background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 6px 10px;
                  cursor: pointer; color: var(--text); font-size: 14px; text-align: left; }
      .cell-row.best { border-color: var(--success); color: var(--success-text); font-weight: 600; }
      .overlay { padding: 0; align-items: flex-end; }
      .modal { max-width: none; border-radius: 12px 12px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
      .modal input { font-size: 16px; min-height: 40px; }
      .form-actions .btn { min-height: 40px; }
    }
  `],
})
export class PrPricesComponent implements OnChanges {
  @Input() requestId!: number;
  @Input() version = 0;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<void>();

  prices: Prices | null = null;
  error = '';
  editing: Editing | null = null;
  editError = '';
  busy = false;
  private sub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (ch['requestId'] || ch['version']) this.load();
  }

  shortDate(iso: string | null): string {
    return dateText(iso).slice(0, 5);
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.editing = null;
  }

  load() {
    this.sub?.unsubscribe();
    this.sub = this.api.getPrivateRequestPrices(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.prices = p; this.error = ''; this.cdr.markForCheck(); },
      error: e => { this.error = 'Таблица цен не загрузилась: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  cellText(c: PriceCell): string {
    switch (c.state) {
      // «💡» — цена распознана из письма и ещё не проверена (примечание строки ставит приём почты)
      case 'PRICE': return numText(c.price) + (c.best ? ' ★' : '') + (this.autoPriced(c) ? ' 💡' : '');
      case 'DECLINED': return 'отказ';
      case 'BOUNCED': return 'не доставлено';
      case 'NOT_SENT': return 'письмо не ушло';
      case 'WAITING': return 'ждём ответа';
      default: return '—';
    }
  }

  private autoPriced(c: PriceCell): boolean {
    return !!c.note && c.note.startsWith('💡');
  }

  stateHint(c: PriceCell): string {
    switch (c.state) {
      case 'PRICE': return this.autoPriced(c)
        ? 'Цена распознана из письма — сверьте с письмом и сохраните.'
        : 'Цену можно поправить — например, после звонка.';
      case 'DECLINED': return 'Поставщик отказался. Если передумал — впишите цену.';
      case 'BOUNCED': return 'Письмо вернулось — исправьте адрес в карточке поставщика и перешлите.';
      case 'NOT_SENT': return 'Письмо не ушло: у поставщика нет почты или почта его не приняла. Цену можно вписать после звонка.';
      case 'WAITING': return 'Ответа ещё нет. Цена из письма с одной позицией встанет сама.';
      default: return '';
    }
  }

  edit(line: PriceLine, cell: PriceCell) {
    if (cell.state === 'NONE' || !this.prices) return;
    const supplier = this.prices.suppliers.find(s => s.distributorId === cell.distributorId)?.name || '';
    this.editError = '';
    // пометку «💡 …проверьте» в поле не переносим: «Сохранить» и есть проверка
    const note = this.autoPriced(cell) ? '' : cell.note || '';
    this.editing = { line, cell, supplier, price: cell.price == null ? '' : numText(cell.price), note };
  }

  saveCell() {
    const ed = this.editing;
    if (!ed || ed.cell.priceRequestId == null || ed.cell.itemId == null) return;
    const price = parseNum(ed.price);
    if (ed.price.trim() && (price == null || price < 0)) {
      this.editError = 'Цена — число не меньше нуля';
      return;
    }
    this.busy = true;
    this.api.updatePriceRequestResponses(ed.cell.priceRequestId,
      [{ itemId: ed.cell.itemId, responsePrice: price, responseNote: ed.note.trim() || null }])
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => { this.busy = false; this.editing = null; this.notify.success('Ответ сохранён'); this.load(); this.changed.emit(); },
        error: e => { this.busy = false; this.editError = errorText(e); this.cdr.markForCheck(); },
      });
  }

  resend() {
    const ed = this.editing;
    if (!ed || ed.cell.priceRequestId == null) return;
    this.busy = true;
    this.api.resendPriceRequest(ed.cell.priceRequestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (r: any) => {
        this.busy = false;
        if (r?.emailSent) this.notify.success('Письмо отправлено повторно');
        else this.notify.error(r?.reason === 'NO_EMAIL' ? 'У поставщика нет почты — письмо не ушло' : 'Письмо не ушло — ошибка почты');
        this.editing = null;
        this.load();
      },
      error: e => { this.busy = false; this.editError = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 2: Вставить таблицу в раздел**

В `pr-supply.component.ts`:
- импорт `import { PrPricesComponent } from './pr-prices.component';` и в `imports`;
- комментарий `<!-- prices -->` заменить на:

```html
      <app-pr-prices [requestId]="view.id" [version]="pricesVersion" [readonly]="readonly"
                     (changed)="changed.emit()"></app-pr-prices>
```

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: SUCCESS.

- [ ] **Step 4: Живая проверка таблицы цен**

Продолжение задачи 14 — запросы уже отправлены.
- **Таблица:** колонки — поставщики, под именем «письмо ДД.ММ»; ячейки «ждём ответа»; у поставщика без почты — «письмо не ушло».
- **Ответ вручную:** клик по ячейке → «Цена за единицу» 18 400 → «Сохранить». Ячейка «18 400 ★», подсветка зелёная, этап — «Есть цены: 1 из 2 позиций». Вторая цена ниже у другого поставщика → ★ переезжает к ней.
- **Ответ письмом:** настоящий ответ поставщика через GreenMail. Остановить бэкенд, запустить `./gradlew devMail` и бэкенд с приёмом zakup@ (команда — CLAUDE.md §5 «Почта zakup@ и Telegram — локально»). Отправить новый запрос с одной позицией — письмо ушло в GreenMail, не в MailHog. Затем `python3 scripts/dev-mail.py reply <prId>`, «Проверить почту» на «Входящих» — в таблице цена «3 450 000 ★ 💡». Клик → подсказка «Цена распознана из письма…», поле примечания пустое → «Сохранить» → «💡» пропал, цена та же.
- **Отказ, потом цена:** ячейке со «отказ» (запрос `DECLINED`) вписать цену → ячейка показывает цену и участвует в ★.
- **Возврат:** `python3 scripts/dev-mail.py bounce <prId>` → ячейка «не доставлено». Клик → «↻ Переслать письмо» → тост, письмо ушло повторно.
- **390 px:** таблицы нет, карточки позиций — «лучшая: … (поставщик)», строки поставщиков с состояниями (40 px); правка — лист снизу экрана.
- **Обе темы;** оператор — видит, правки нет.
- **Уборка dev:** вернуть почтовые хвосты по порядку CLAUDE.md §5 — СНАЧАЛА остановить бэкенд, потом SQL курсора.

- [ ] **Step 5: Коммит**

```bash
git add frontend/src/app/pages/private-requests/pr-prices.component.ts frontend/src/app/pages/private-requests/pr-supply.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): таблица цен — лучшая цена, отказ и возврат, правка ответа и «Переслать»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Обращения одной кнопкой, «Входящие» без клиента, уборка, документация, приёмка этапа

**Files:**
- Modify: `frontend/src/app/pages/leads/lead-card.component.ts`
- Modify: `frontend/src/app/pages/leads/leads.component.ts`
- Modify: `frontend/src/app/pages/inbound/inbound.component.ts`
- Delete: `frontend/src/app/pages/leads/lead-convert-dialog.component.ts`
- Modify: `CLAUDE.md` (§8, §12, §15, §16), `docs/PROGRESS.md`

**Interfaces:**
- Consumes: `ApiService.convertLead(id)` (пустое тело), `commitImport` (вернёт вид с `id`).
- Produces: «Создать заявку» в обращении; ссылки на `/private-requests/:id`.

- [ ] **Step 1: Карточка обращения — одна кнопка**

В `lead-card.component.ts`:
- убрать импорт `LeadConvertDialogComponent` и его из `imports`; добавить `import { errorText } from '../../shared/error-text';`;
- кнопку `(click)="convertOpen = true">Создать частную заявку` заменить на:

```html
                <button type="button" class="btn btn-primary" *ngIf="lead.status === 'NEW' || lead.status === 'IN_WORK'" [disabled]="busy" (click)="createRequest()">Создать заявку</button>
```

- ссылку «Открыть заявку» — на страницу заявки:

```html
              <a class="btn btn-line" *ngIf="lead.privateRequestId" [routerLink]="['/private-requests', lead.privateRequestId]">Открыть заявку {{ lead.privateRequestNumber }}</a>
```

- удалить `<app-lead-convert-dialog …></app-lead-convert-dialog>` (2 строки), поле `convertOpen`, метод `onConverted`;
- в `onEscape()` условие `if (this.leadId !== null && !this.convertOpen)` → `if (this.leadId !== null)` (комментарий про диалог убрать);
- добавить метод:

```ts
  /** «Создать заявку» одной кнопкой (спека 2026-10-12 §4): клиент, контакт, текст и позиции — из обращения; сразу страница заявки. */
  createRequest() {
    if (!this.lead) return;
    this.busy = true;
    this.api.convertLead(this.lead.id).subscribe({
      next: r => {
        this.busy = false;
        this.notify.success('Заявка ' + r.number + ' создана');
        this.changed.emit();
        this.router.navigate(['/private-requests', r.privateRequestId]);
      },
      error: e => { this.busy = false; this.notify.error('Заявка не создана: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }
```

```bash
git rm frontend/src/app/pages/leads/lead-convert-dialog.component.ts
```

В `leads.component.ts` ссылку на заявку в карточке списка заменить на:

```html
            <a *ngIf="l.privateRequestId" [routerLink]="['/private-requests', l.privateRequestId]"
               (click)="$event.stopPropagation()">{{ l.privateRequestNumber }}</a>
```

- [ ] **Step 2: «Входящие» — клиент необязателен, отправитель — контакт, затем страница заявки**

В `inbound.component.ts`:
- в выборе клиента опцию `<option [ngValue]="null" disabled>— выберите —</option>` заменить на `<option [ngValue]="null">— без клиента —</option>`;
- импорт `Router` из `@angular/router`, в конструктор `private router: Router`;
- в `createFromImport()` ветку `else { if (!this.importClientId) { … return; } … }` заменить на:

```ts
    } else {
      this.importing = true;
      this.doCommit(this.importClientId, mappings, lines);
    }
```

- `doCommit` заменить:

```ts
  /** Клиент необязателен; отправитель письма — контакт заявки: «Имя <адрес>» → имя и адрес, голый адрес — только адрес. */
  private doCommit(clientId: number | null, mappings: any[], lines: any[]) {
    const emailId = this.importEmailId;
    const from = this.importFrom || '';
    const address = (from.match(/<([^>]+)>/)?.[1] ?? (from.includes('@') ? from : '')).trim().slice(0, 255);
    const name = this.displayName(from).slice(0, 255);
    this.api.commitImport({
      clientFacilityId: clientId,
      contactName: name && name !== address ? name : null,
      contactEmail: address || null,
      mappings, lines,
    }).subscribe({
      next: (created: any) => {
        this.api.markInboundProcessed(emailId as number).subscribe({ next: () => {}, error: () => {} });
        this.importing = false;
        this.closeImport();
        this.notify.success('Заявка создана из письма');
        if (created?.id) this.router.navigate(['/private-requests', created.id]);
        else { this.load(); this.cdr.detectChanges(); }
      },
      error: (e: any) => {
        this.importing = false;
        this.importError = e.error?.message || 'Ошибка импорта';
        this.cdr.detectChanges();
      },
    });
  }
```

(`@Size(max = 255)` у `contactName` / `contactEmail` в `ImportCommitRequest` — поэтому `slice(0, 255)`: имя отправителя может быть любым.)

- [ ] **Step 3: Сборка и полный гейт**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` → SUCCESS.
Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test` → 0 падений. Записать число тестов.

- [ ] **Step 4: Приёмка этапа вживую (Playwright, 1280 и 390, обе темы, оператор)**

Полный путь заявки с сайта:
1. Обращение с позициями: «Обращения» → новое обращение с сайта (локальная копия westmed или ручной ввод с позицией и брендом).
2. «Создать заявку» → сразу страница: клиент — текст компании или учреждение, контакт, примечание, позиции, метки РУ.
3. Подтвердить РУ у позиции с брендом.
4. «Запросить цены…» → письма в MailHog. В письме: «…, РУ № …», «Ответить до», нет номера заявки.
5. Ответ ценой (вручную или `dev-mail.py reply`) → «Есть цены», ★.
6. «⋯ → Закрыть: купили у нас» → «Закрыта: купили у нас»; заявка в списке «Закрытые».

Ещё проверить:
- **Словесная заявка:** «+ Новая заявка» → клиент текстом «Частная клиника, Уральск» → «Вставить списком» с 5 строками → позиции → запрос цен.
- **«Входящие»:** письмо клиники с Excel → «Импортировать» → «— без клиента —» → «Создать заявку» → открылась страница, контакт — отправитель письма.
- **Старые ссылки:** «Открыть заявку» в обращении и `?openId=` из Telegram ведут на страницу.
- **Удаление заявки из обращения:** «Удалить заявку» → обращение снова «В работе».
- **390 px:** ни одной горизонтальной прокрутки страницы (замер `scrollWidth` на `http://[::1]:4200`, §5); нижняя кнопка «Запросить цены».
- **Тёмная тема:** чипы этапа, метки РУ, таблица цен читаются.
- **Оператор:** всё видно, правок нет, кнопок записи нет.

Найденное — исправлять тем же заходом с тестом. В отчёт — что проверено и что нет.

- [ ] **Step 5: Документация**

- **`CLAUDE.md` §8** — новый блок «Частная заявка — рабочее место (этап 1 из 3, 2026-10-12, ветка `feature/private-request-workspace`, V26)»:
  - модель (V26), страница и разделы, клиент необязателен, метка РУ (порог `SUGGEST_MIN` и замер);
  - подбор поставщиков, окно запроса цен, исход письма, таблица цен, обращение одной кнопкой, импорт без клиента;
  - права (`PriceRequestController` — записи только ADMIN), что проверено.
- **`CLAUDE.md` §12** — фронт: две ленивые страницы, разделы-компоненты, общие `registry-chip` и `site-product-search`, `.pr-stage` в kit.
- **`CLAUDE.md` §15** — API из задач 2–9 (заменить прежнюю строку `/api/private-requests (GET/POST/PUT {id}, …)`), `/api/site-products`.
- **`CLAUDE.md` §16** — этапы 2 и 3 следующими планами; часть 2 (Excel поставщика) — вопрос «куда заливать»; миграции — следующая свободная V27 (этап 2), отмеченные при живой проверке хвосты.
- **`CLAUDE.md` §7** — `tender_lot` (`unit`, `product_url`, `reg_*`), `tender` (`contact_name`, `created_at`), `price_request` (`reply_by`, `sent_to`, `letter_status`).
- **`CLAUDE.md` §13** — число тестов.
- **`docs/PROGRESS.md`** — «▶ Последняя задача»: что сделано в этапе 1, где спека/план/леджер, что ждёт оператора (push), следующий шаг — план этапа 2. В «🧭 Где мы» — строку про КП заменить на «частная заявка: этап 1 готов; дальше — этап 2».

- [ ] **Step 6: Коммит**

```bash
git add frontend/src/app/pages/leads/lead-card.component.ts frontend/src/app/pages/leads/leads.component.ts frontend/src/app/pages/inbound/inbound.component.ts CLAUDE.md docs/PROGRESS.md
git commit -m "$(cat <<'EOF'
feat(private-request): обращение → заявка одной кнопкой, «Входящие» без клиента; документация этапа 1

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Ревью всей ветки, мерж и push**

Финальное ревью ветки (Opus, по SDD) → исправления с тестами → мерж в `main` fast-forward (`git checkout main && git merge --ff-only feature/private-request-workspace`). Push делает оператор: `! git push origin main` (автодеплой ≈ 3–5 мин, V26 накатится сама). После деплоя — проверка на проде (SSH, только чтение, с согласия оператора): `select count(*) from tender where source = 'PRIVATE_REQUEST' and created_at is null;` и что страница заявки открывается через калитку.
