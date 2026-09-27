# Обращения: приём заявок с westmed.kz + ручной ввод звонков/WhatsApp — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заявки с сайта westmed.kz и ручные обращения (звонок, WhatsApp) появляются в АИС на странице «Обращения», из них одной кнопкой собирается частная заявка, а статус АИС сама пишет обратно на сайт.

**Architecture:** Новая рыночная сущность `Lead` (+ позиции и лента событий) и единая точка входа `LeadIntakeService.ingest(IncomingLead)`. Адаптер `integration/westmed` опрашивает API админки сайта в своём фоновом потоке, кладёт новые заявки через точку входа и пишет ожидающие статусы обратно. Ручной ввод идёт через ту же точку. Превращение в частную заявку переиспользует `PrivateRequestService.createFromLines` в одной транзакции. Фронт — страница `/leads`, выдвижная карточка, диалоги ручного ввода и превращения.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA / Hibernate 6, Flyway, `java.net.http.HttpClient` + Jackson, JUnit 5 + AssertJ + JDK `HttpServer` + GreenMail, Angular 21 (standalone, инлайн-шаблоны).

**Spec:** `docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md` — план аргументирует от неё; исполнитель читает обе.

## Global Constraints

- **Код сайта westmed не меняется ни строкой.** Боевой сайт во время разработки не трогаем: локально `WESTMED_WRITE_STATUS=false`, живая проверка — на локальной копии westmed (Task 14).
- **Схема — только новой миграцией `V18__leads.sql`** (V17 занята `V17__kz_distributors_endoscopy.sql`). V1…V17 не править.
- **Многорыночность (§6 CLAUDE.md):** `Lead` — `MarketScoped` + `@Filter(name = "marketFilter", condition = "market = :market")` + `@EntityListeners(MarketStampingListener.class)`. **`@FilterDef` не переобъявлять** (он один — на `Tender`). Сервисы пред-штампуют рынок при создании.
- **Нативный SQL рыночный фильтр НЕ режет** — рынок в нативных запросах передаётся явно.
- **Гард рынка по id** — как в `WinnerAssignmentService`: `if (x.getMarket() != null && x.getMarket() != MarketContext.get()) throw new NotFoundException(...)`.
- **Фоновый поток:** `MarketContext.set(market)` явно + `MarketContext.clear()` в `finally`; работа с БД — в `@Transactional`-методах ОТДЕЛЬНЫХ бинов; сеть — вне транзакций.
- **Коллекции `cascade = ALL, orphanRemoval = true`** — менять только через коллекцию (урок §7 CLAUDE.md), не через `repository.delete`.
- **Права:** запись — `@PreAuthorize("hasRole('ADMIN')")`, чтение — любому вошедшему.
- **Секреты:** пароль и токен сайта никогда не попадают в логи и тексты ошибок.
- **API сайта:** протухший токен сайт отвечает **`403`, а не `401`** → повторный вход и на `401`, и на `403`. Карточку товара `GET /api/v1/products/{slug}` не вызывать никогда (накручивает счётчик просмотров) — только поиск `GET /api/v1/products?search=`.
- **Фронт:** только CSS-токены (`var(--…)`), `@media` — ПОСЛЕДНИМ блоком `styles`, у кнопок базовый класс `btn`, инлайн-цвета в разметке и TS запрещены, `cdr.detectChanges()` после async, lucide-иконка — только зарегистрированная в `app.config.ts`.
- **Команды:** `./gradlew` и `psql` — с `dangerouslyDisableSandbox: true`. Гейт бэка — `./gradlew cleanTest test` (обычный `test` отдаёт кеш). Гейт фронта — `cd frontend && npm run build`. git/gradlew — из корня репо (`cd /Users/vlad/IdeaProjects/AIS && …`).
- **Тесты** — `@SpringBootTest @Transactional` на живой nirdb: имена, телефоны и внешние id делать уникальными (`System.nanoTime()` / `UUID`), `MarketContext.clear()` в `@AfterEach`. Не предполагать пустых таблиц.
- **Коммиты** — на ветке `feature/leads-westmed-intake`, каждый заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. В `main` в рамках плана не мержить (мерж = прод).
- **Субагенты — `model: opus` явно** (§2 CLAUDE.md).

## Карта файлов

**Бэкенд — создать:**

| Файл | Ответственность |
|---|---|
| `src/main/resources/db/migration/V18__leads.sql` | таблицы `lead`, `lead_item`, `lead_event` |
| `entity/LeadChannel`, `LeadStatus`, `LeadCloseReason`, `LeadEventType`, `LeadDirection` | перечисления модели |
| `entity/Lead`, `LeadItem`, `LeadEvent` | сущности (обращение, позиция, событие ленты) |
| `repository/LeadRepository` | выборки обращений |
| `util/PhoneNormalizer` | телефон → `+7XXXXXXXXXX` |
| `util/LeadText` | `trunc` / `blankToNull` / `isBlank` для обращений |
| `integration/lead/IncomingLead` | record-шов входа |
| `integration/lead/LeadSources` | коды источников, соответствие статусов сайту |
| `service/LeadClientMatcher` | клиент по телефону/email |
| `service/LeadIntakeService` | единая идемпотентная точка входа |
| `service/LeadService` | ручной ввод, переходы, лента, позиции, превращение |
| `service/LeadStatusPushWriter` | транзакционные записи результата отправки статуса |
| `dto/request/LeadCreateRequest`, `LeadItemDto`, `LeadItemsUpdate`, `LeadCloseRequest`, `LeadEventCreate`, `LeadConvertRequest` | входные DTO |
| `dto/response/LeadListItemResponse`, `LeadCardResponse`, `LeadEventResponse`, `LeadRefResponse`, `LeadConvertResponse`, `LeadSyncStatusResponse` | выходные DTO |
| `mapper/LeadResponseMapper` | сущность → DTO |
| `controller/LeadController` | REST `/api/leads` |
| `integration/westmed/WestmedClient`, `WestmedHttpClient`, `WestmedKind`, `WestmedApiException`, `WestmedAuthException` | HTTP к сайту |
| `integration/westmed/dto/WestmedPage`, `WestmedPriceRequest`, `WestmedQuoteRequest`, `WestmedProduct` | формы ответов сайта |
| `integration/westmed/WestmedLeadMapper`, `WestmedProductLookup` | заявка сайта → `IncomingLead` + бренд из каталога |
| `integration/westmed/WestmedLeadSync`, `WestmedSyncResult` | один цикл: забрать новое, записать статусы |
| `integration/westmed/WestmedLeadScheduler` | расписание, свой поток, состояние для UI |

**Бэкенд — изменить:** `repository/FacilityRepository` (поиск клиента), `dto/response/PrivateRequestResponse` + `controller/PrivateRequestController` (обратная ссылка), `service/MailReceiveService` + `dto/response/PollResultResponse` (пропуск уведомлений сайта), `application.yaml`, `.env.example`.

**Фронт — создать:** `shared/relative-time.ts`, `shared/lead-labels.ts`, `pages/leads/leads.component.ts`, `pages/leads/lead-card.component.ts`, `pages/leads/lead-form.component.ts`, `pages/leads/lead-convert-dialog.component.ts`.
**Фронт — изменить:** `services/api.service.ts`, `app.routes.ts`, `app.config.ts`, `layout/layout.component.ts`, `pages/private-requests/private-request-card.component.ts`.

Все пути бэкенда ниже — от `src/main/java/com/vladoose/nir/` (тесты — от `src/test/java/com/vladoose/nir/`), если не указан полный путь.

---

### Task 1: Модель данных — миграция, сущности, репозиторий, нормализатор телефона

**Files:**
- Create: `src/main/resources/db/migration/V18__leads.sql`
- Create: `entity/LeadChannel.java`, `entity/LeadStatus.java`, `entity/LeadCloseReason.java`, `entity/LeadEventType.java`, `entity/LeadDirection.java`
- Create: `entity/Lead.java`, `entity/LeadItem.java`, `entity/LeadEvent.java`
- Create: `repository/LeadRepository.java`
- Create: `util/PhoneNormalizer.java`
- Test: `util/PhoneNormalizerTest.java`, `lead/LeadPersistenceTest.java`

**Interfaces:**
- Consumes: `Market`, `MarketScoped`, `MarketStampingListener`, `MarketContext`, `Facility`, `Tender` (существующие).
- Produces:
  - enum `LeadChannel { SITE, PHONE, WHATSAPP, EMAIL, OTHER }`, `LeadStatus { NEW, IN_WORK, CONVERTED, CLOSED }`, `LeadCloseReason { ANSWERED, SPAM, DUPLICATE, NOT_OUR_PROFILE, CLIENT_DECLINED, OTHER }`, `LeadEventType { RECEIVED, NOTE, CALL, MESSAGE, STATUS, SYNC }`, `LeadDirection { IN, OUT }`.
  - `Lead` (Lombok getters/setters/builder; поля по DDL), методы `void addItem(String name, String brand, int quantity, String productUrl)`, `LeadEvent addEvent(LeadEventType type, String author, String body)`.
  - `LeadItem` (`lineNo`, `name`, `brand`, `quantity`, `productUrl`), `LeadEvent` (`occurredAt`, `type`, `direction`, `channel`, `author`, `body`, `externalId`).
  - `LeadRepository`: `existsBySourceAndExternalId(String, String)`, `findBySourceAndExternalId(String, String): Optional<Lead>`, `findByStatusInOrderByReceivedAtDescIdDesc(Collection<LeadStatus>, Pageable): List<Lead>`, `findByStatusInAndChannelOrderByReceivedAtDescIdDesc(Collection<LeadStatus>, LeadChannel, Pageable): List<Lead>`, `countByStatus(LeadStatus): long`, `findTop5ByPhoneNormAndIdNotOrderByReceivedAtDesc(String, Long): List<Lead>`, `findBySourceAndExtStatusPendingIsNotNull(String): List<Lead>`, `findFirstByPrivateRequestId(Long): Optional<Lead>`.
  - `PhoneNormalizer.normalize(String): String` (`+7XXXXXXXXXX` или `null`), `PhoneNormalizer.last10(String): String`.

- [ ] **Step 1: Написать падающий тест нормализатора**

`src/test/java/com/vladoose/nir/util/PhoneNormalizerTest.java`:

```java
package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNormalizerTest {

    @Test
    void kzMobileInAnyNotationBecomesE164() {
        assertThat(PhoneNormalizer.normalize("8 (777) 075-27-70")).isEqualTo("+77770752770");
        assertThat(PhoneNormalizer.normalize("+7 777 075 27 70")).isEqualTo("+77770752770");
        assertThat(PhoneNormalizer.normalize("77770752770")).isEqualTo("+77770752770");
    }

    @Test
    void tenDigitsWithoutCountryCodeGetSeven() {
        assertThat(PhoneNormalizer.normalize("777 075 27 70")).isEqualTo("+77770752770");
        // городской СПб без кода страны: 10 цифр, ведущая «8» — код города, а не межгород
        assertThat(PhoneNormalizer.normalize("812 345-67-89")).isEqualTo("+78123456789");
    }

    @Test
    void foreignAndJunkAreNotNormalized() {
        assertThat(PhoneNormalizer.normalize("+998 90 123 45 67")).isNull();
        assertThat(PhoneNormalizer.normalize("12-34")).isNull();
        assertThat(PhoneNormalizer.normalize("")).isNull();
        assertThat(PhoneNormalizer.normalize(null)).isNull();
    }

    @Test
    void last10IsTheSubscriberPart() {
        assertThat(PhoneNormalizer.last10("+77770752770")).isEqualTo("7770752770");
        assertThat(PhoneNormalizer.last10(null)).isNull();
    }
}
```

- [ ] **Step 2: Написать падающий тест персистентности**

`src/test/java/com/vladoose/nir/lead/LeadPersistenceTest.java`:

```java
package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.LeadRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadPersistenceTest {

    @Autowired LeadRepository leadRepository;
    @Autowired EntityManager em;

    @AfterEach
    void clear() { MarketContext.clear(); }

    private static Lead lead(String externalId) {
        return Lead.builder().channel(LeadChannel.SITE).source("zz-test").externalId(externalId)
                .subject("Запрос КП").status(LeadStatus.NEW).receivedAt(OffsetDateTime.now()).build();
    }

    @Test
    void persistsItemsAndEventsAndStampsMarketFromContext() {
        MarketContext.set(Market.KZ);
        Lead l = lead("ext-" + System.nanoTime());
        l.addItem("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn-150");
        l.addItem("Рециркулятор", null, 0, null);   // количество < 1 превращается в 1
        l.addEvent(LeadEventType.RECEIVED, null, "Запрос КП — westmed.kz");
        Long id = leadRepository.saveAndFlush(l).getId();
        em.clear();

        Lead back = leadRepository.findById(id).orElseThrow();
        assertThat(back.getMarket()).isEqualTo(Market.KZ);
        assertThat(back.getItems()).extracting(LeadItem::getLineNo, LeadItem::getName, LeadItem::getQuantity)
                .containsExactly(tuple(1, "Облучатель ОБН-150", 2), tuple(2, "Рециркулятор", 1));
        assertThat(back.getEvents()).extracting(LeadEvent::getType).containsExactly(LeadEventType.RECEIVED);
        assertThat(back.getEvents().get(0).getOccurredAt()).isNotNull();
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(back.getUpdatedAt()).isNotNull();
    }

    @Test
    void sourceAndExternalIdAreUnique() {
        MarketContext.set(Market.KZ);
        String ext = "dup-" + System.nanoTime();
        leadRepository.saveAndFlush(lead(ext));
        assertThatThrownBy(() -> leadRepository.saveAndFlush(lead(ext)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leadsWithoutExternalIdDoNotCollide() {
        MarketContext.set(Market.KZ);
        leadRepository.saveAndFlush(lead(null));
        leadRepository.saveAndFlush(lead(null));   // частичный уникальный индекс: NULL в нём не участвует
    }

    @Test
    void marketFilterHidesLeadsOfAnotherMarket() {
        MarketContext.set(Market.KZ);
        Lead kz = leadRepository.saveAndFlush(lead("kz-" + System.nanoTime()));
        MarketContext.set(Market.RF);
        assertThat(leadRepository.findAll()).extracting(Lead::getId).doesNotContain(kz.getId());
        assertThat(leadRepository.findBySourceAndExternalId("zz-test", kz.getExternalId())).isEmpty();
    }
}
```

- [ ] **Step 3: Запустить тесты — убедиться, что не компилируются**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.util.PhoneNormalizerTest' --tests 'com.vladoose.nir.lead.LeadPersistenceTest'`
Expected: FAIL — `compileTestJava` не находит `PhoneNormalizer`, `Lead`, `LeadRepository`.

- [ ] **Step 4: Миграция**

`src/main/resources/db/migration/V18__leads.sql`:

```sql
-- Обращения (коммерческое направление West-Med): заявки с сайта westmed.kz, звонки, WhatsApp.
-- Спека: docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md §4.
CREATE TABLE lead (
    id                  BIGSERIAL PRIMARY KEY,
    market              VARCHAR(2)   NOT NULL,
    channel             VARCHAR(20)  NOT NULL,   -- SITE / PHONE / WHATSAPP / EMAIL / OTHER
    source              VARCHAR(40)  NOT NULL,   -- 'westmed.kz' | 'manual' | позже 'vital-spb.kz', 'whatsapp', 'pbx'
    external_id         VARCHAR(100),            -- id у источника; westmed: 'price:<uuid>' | 'quote:<uuid>'
    subject             VARCHAR(200) NOT NULL,
    contact_name        VARCHAR(255),
    contact_phone       VARCHAR(50),             -- как пришло (для показа)
    phone_norm          VARCHAR(20),             -- +7XXXXXXXXXX — ключ сопоставления каналов
    contact_email       VARCHAR(255),
    company             VARCHAR(255),
    message             TEXT,
    facility_id         BIGINT REFERENCES facility(id) ON DELETE SET NULL,
    status              VARCHAR(20)  NOT NULL,   -- NEW / IN_WORK / CONVERTED / CLOSED
    close_reason        VARCHAR(30),
    private_request_id  BIGINT REFERENCES tender(id) ON DELETE SET NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ext_status          VARCHAR(20),             -- статус у источника, как его знает АИС
    ext_status_pending  VARCHAR(20),             -- что надо записать в источник; NULL = нечего
    ext_sync_error      VARCHAR(500)
);
CREATE UNIQUE INDEX uq_lead_source_ext  ON lead (source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_lead_market_status     ON lead (market, status, received_at DESC);
CREATE INDEX idx_lead_phone             ON lead (market, phone_norm) WHERE phone_norm IS NOT NULL;
CREATE INDEX idx_lead_ext_pending       ON lead (source) WHERE ext_status_pending IS NOT NULL;
CREATE INDEX idx_lead_private_request   ON lead (private_request_id) WHERE private_request_id IS NOT NULL;

CREATE TABLE lead_item (
    id           BIGSERIAL PRIMARY KEY,
    lead_id      BIGINT NOT NULL REFERENCES lead(id) ON DELETE CASCADE,
    line_no      INT    NOT NULL,
    name         VARCHAR(500) NOT NULL,
    brand        VARCHAR(255),
    quantity     INT    NOT NULL DEFAULT 1,
    product_url  VARCHAR(500)
);
CREATE INDEX idx_lead_item_lead ON lead_item (lead_id);

CREATE TABLE lead_event (
    id           BIGSERIAL PRIMARY KEY,
    lead_id      BIGINT NOT NULL REFERENCES lead(id) ON DELETE CASCADE,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    type         VARCHAR(20) NOT NULL,   -- RECEIVED / NOTE / CALL / MESSAGE / STATUS / SYNC
    direction    VARCHAR(3),             -- IN / OUT — звонки и сообщения
    channel      VARCHAR(20),
    author       VARCHAR(100),           -- логин пользователя АИС; NULL = система
    body         TEXT,
    external_id  VARCHAR(100)            -- id сообщения/звонка у провайдера (будущие каналы)
);
CREATE INDEX idx_lead_event_lead ON lead_event (lead_id, occurred_at);
CREATE UNIQUE INDEX uq_lead_event_ext ON lead_event (channel, external_id) WHERE external_id IS NOT NULL;
```

- [ ] **Step 5: Перечисления**

`entity/LeadChannel.java`:
```java
package com.vladoose.nir.entity;

/** Как клиент до нас дошёл. PHONE/WHATSAPP/EMAIL сейчас вводятся вручную — это задел под АТС и WhatsApp Business. */
public enum LeadChannel { SITE, PHONE, WHATSAPP, EMAIL, OTHER }
```

`entity/LeadStatus.java`:
```java
package com.vladoose.nir.entity;

public enum LeadStatus { NEW, IN_WORK, CONVERTED, CLOSED }
```

`entity/LeadCloseReason.java`:
```java
package com.vladoose.nir.entity;

public enum LeadCloseReason { ANSWERED, SPAM, DUPLICATE, NOT_OUR_PROFILE, CLIENT_DECLINED, OTHER }
```

`entity/LeadEventType.java`:
```java
package com.vladoose.nir.entity;

/** MESSAGE пока не пишется никем — задел под WhatsApp Business (спека §12). */
public enum LeadEventType { RECEIVED, NOTE, CALL, MESSAGE, STATUS, SYNC }
```

`entity/LeadDirection.java`:
```java
package com.vladoose.nir.entity;

public enum LeadDirection { IN, OUT }
```

- [ ] **Step 6: Сущности**

`entity/Lead.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Обращение клиента (коммерция West-Med): заявка с сайта, звонок, сообщение.
 * Рыночная сущность (§6 CLAUDE.md): @Filter + листенер штампа; @FilterDef объявлен ОДИН раз — на Tender.
 * Позиции и лента — дети с cascade=ALL/orphanRemoval: менять ТОЛЬКО через коллекции (урок §7).
 */
@Entity
@Table(name = "lead")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Lead implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadChannel channel;

    /** Кто создал запись: «westmed.kz», «manual», позже «vital-spb.kz», «whatsapp», «pbx». */
    @Column(nullable = false, length = 40)
    private String source;

    /** Id у источника; пара (source, externalId) уникальна. У ручных обращений — null. */
    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    /** +7XXXXXXXXXX — ключ «к какому обращению относится звонок/сообщение» для будущих каналов. */
    @Column(name = "phone_norm", length = 20)
    private String phoneNorm;

    @Column(name = "contact_email")
    private String contactEmail;

    private String company;

    @Column(columnDefinition = "TEXT")
    private String message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 30)
    private LeadCloseReason closeReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "private_request_id")
    private Tender privateRequest;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Статус у источника, как его знает АИС. */
    @Column(name = "ext_status", length = 20)
    private String extStatus;

    /** Что надо записать в источник; null — нечего. */
    @Column(name = "ext_status_pending", length = 20)
    private String extStatusPending;

    @Column(name = "ext_sync_error", length = 500)
    private String extSyncError;

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @BatchSize(size = 50)   // список до 300 обращений: позиции подгружаются пачками, а не по одной
    @Builder.Default
    private List<LeadItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC, id ASC")
    @Builder.Default
    private List<LeadEvent> events = new ArrayList<>();

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (receivedAt == null) receivedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public void addItem(String name, String brand, int quantity, String productUrl) {
        items.add(LeadItem.builder().lead(this).lineNo(items.size() + 1)
                .name(name).brand(brand).quantity(Math.max(quantity, 1)).productUrl(productUrl).build());
    }

    public LeadEvent addEvent(LeadEventType type, String author, String body) {
        LeadEvent e = LeadEvent.builder().lead(this).occurredAt(OffsetDateTime.now())
                .type(type).author(author).body(body).build();
        events.add(e);
        return e;
    }
}
```

`entity/LeadItem.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lead_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeadItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    /** Порядковый номер строки (не «position»: это функция HQL, разбор @OrderBy по ней ненадёжен). */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 500)
    private String name;

    private String brand;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "product_url", length = 500)
    private String productUrl;
}
```

`entity/LeadEvent.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Событие ленты обращения. direction/channel/externalId — задел под звонки АТС и сообщения WhatsApp. */
@Entity
@Table(name = "lead_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadEventType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private LeadDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LeadChannel channel;

    @Column(length = 100)
    private String author;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 7: Репозиторий и нормализатор**

`repository/LeadRepository.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Все выборки — HQL, поэтому рыночный фильтр аспекта их режет (§6 CLAUDE.md). */
public interface LeadRepository extends JpaRepository<Lead, Long> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    Optional<Lead> findBySourceAndExternalId(String source, String externalId);

    List<Lead> findByStatusInOrderByReceivedAtDescIdDesc(Collection<LeadStatus> statuses, Pageable pageable);

    List<Lead> findByStatusInAndChannelOrderByReceivedAtDescIdDesc(Collection<LeadStatus> statuses,
                                                                  LeadChannel channel, Pageable pageable);

    long countByStatus(LeadStatus status);

    List<Lead> findTop5ByPhoneNormAndIdNotOrderByReceivedAtDesc(String phoneNorm, Long id);

    List<Lead> findBySourceAndExtStatusPendingIsNotNull(String source);

    Optional<Lead> findFirstByPrivateRequestId(Long privateRequestId);
}
```

`util/PhoneNormalizer.java`:
```java
package com.vladoose.nir.util;

/** Телефон зоны +7 (KZ/RU) в единый вид «+7XXXXXXXXXX» — ключ сопоставления каналов обращений. */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    /** «8 (777) 075-27-70» / «+7 777 075 27 70» / «7770752770» → «+77770752770»; иное → null. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("8")) {
            d = "7" + d.substring(1);
        } else if (d.length() == 10) {
            d = "7" + d;
        }
        return d.length() == 11 && d.startsWith("7") ? "+" + d : null;
    }

    /** Последние 10 цифр нормализованного номера (абонентская часть) или null. */
    public static String last10(String normalized) {
        return normalized == null || normalized.length() < 10 ? null : normalized.substring(normalized.length() - 10);
    }
}
```

- [ ] **Step 8: Запустить тесты — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.util.PhoneNormalizerTest' --tests 'com.vladoose.nir.lead.LeadPersistenceTest'`
Expected: PASS, 8 тестов. В логе старта: `Migrating schema "public" to version "18 - leads"` (при первом прогоне на nirdb).

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V18__leads.sql \
  src/main/java/com/vladoose/nir/entity/Lead*.java src/main/java/com/vladoose/nir/repository/LeadRepository.java \
  src/main/java/com/vladoose/nir/util/PhoneNormalizer.java \
  src/test/java/com/vladoose/nir/util/PhoneNormalizerTest.java src/test/java/com/vladoose/nir/lead/LeadPersistenceTest.java
git commit -m "$(printf 'feat(leads): модель обращений — V18, сущности, репозиторий, нормализатор телефона\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 2: Точка входа — `IncomingLead`, поиск клиента, `LeadIntakeService`

**Files:**
- Create: `util/LeadText.java`
- Create: `integration/lead/IncomingLead.java`, `integration/lead/LeadSources.java`
- Create: `service/LeadClientMatcher.java`, `service/LeadIntakeService.java`
- Modify: `repository/FacilityRepository.java` (+2 метода)
- Test: `lead/LeadIntakeServiceTest.java`

**Interfaces:**
- Consumes (Task 1): `Lead`, `LeadItem`, `LeadEvent`, перечисления, `LeadRepository.existsBySourceAndExternalId`, `PhoneNormalizer`.
- Produces:
  - `record IncomingLead(String source, String externalId, LeadChannel channel, String subject, OffsetDateTime receivedAt, String contactName, String contactPhone, String contactEmail, String company, String message, List<IncomingLead.Item> items, LeadStatus initialStatus, String extStatus, String author)` + `record Item(String name, String brand, int quantity, String productUrl)`.
  - `LeadSources.WESTMED = "westmed.kz"`, `LeadSources.MANUAL = "manual"`, `static boolean writesBack(String source)`, `static String siteStatusFor(LeadStatus)`, `static String siteStatusLabel(String)`.
  - `LeadText.trunc(String, int)`, `LeadText.blankToNull(String)`, `LeadText.isBlank(String)`.
  - `LeadClientMatcher.match(Market market, String phoneNorm, String email): Facility` (или `null`).
  - `LeadIntakeService.isKnown(String source, String externalId): boolean`, `LeadIntakeService.ingest(IncomingLead): Optional<Lead>` — пусто, если такое обращение уже есть.
  - `FacilityRepository.findByMarketAndPhoneLast10(String market, String last10): List<Facility>`, `FacilityRepository.findByMarketAndEmailIgnoreCase(Market, String): List<Facility>`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/lead/LeadIntakeServiceTest.java`:

```java
package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.LeadIntakeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadIntakeServiceTest {

    @Autowired LeadIntakeService intake;
    @Autowired FacilityRepository facilityRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** 7 уникальных цифр: номера вида +7 799 XXXXXXX в живой nirdb не встречаются. */
    private static String uniq7() {
        return String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
    }

    private static IncomingLead site(String ext, String phone, String email, List<IncomingLead.Item> items,
                                     LeadStatus status, String extStatus) {
        return new IncomingLead("zz-site", ext, LeadChannel.SITE, "Запрос КП",
                OffsetDateTime.parse("2026-09-20T08:15:30Z"), "Айгерим", phone, email, "ТОО «ZZ Клиника»",
                "Нужен облучатель", items, status, extStatus, null);
    }

    private static IncomingLead site(String ext, String phone, String email) {
        return site(ext, phone, email,
                List.of(new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn")),
                LeadStatus.NEW, "NEW");
    }

    private static String ext() { return "zz-" + System.nanoTime(); }

    @Test
    void createsLeadWithItemsReceivedEventAndNormalizedPhone() {
        Lead l = intake.ingest(site(ext(), "8 (777) 000-11-22", "a@zz.kz")).orElseThrow();

        assertThat(l.getMarket()).isEqualTo(Market.KZ);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getContactPhone()).isEqualTo("8 (777) 000-11-22");
        assertThat(l.getPhoneNorm()).isEqualTo("+77770001122");
        assertThat(l.getReceivedAt()).isEqualTo(OffsetDateTime.parse("2026-09-20T08:15:30Z"));
        assertThat(l.getExtStatus()).isEqualTo("NEW");
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getBrand, LeadItem::getQuantity)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2));
        assertThat(l.getEvents()).hasSize(1);
        LeadEvent received = l.getEvents().get(0);
        assertThat(received.getType()).isEqualTo(LeadEventType.RECEIVED);
        assertThat(received.getDirection()).isEqualTo(LeadDirection.IN);
        assertThat(received.getChannel()).isEqualTo(LeadChannel.SITE);
        assertThat(received.getBody()).contains("Запрос КП").contains("1 поз.").contains("zz-site");
    }

    @Test
    void secondIngestWithSameExternalIdIsDuplicate() {
        String e = ext();
        assertThat(intake.ingest(site(e, null, "a@zz.kz"))).isPresent();
        assertThat(intake.ingest(site(e, null, "a@zz.kz"))).isEmpty();
        assertThat(intake.isKnown("zz-site", e)).isTrue();
    }

    @Test
    void matchesClientByLast10DigitsOfPhone() {
        String d = uniq7();
        Facility f = facilityRepository.save(Facility.builder()
                .name("ZZ Клиника тел " + System.nanoTime()).phone("+7 (799) " + d).market(Market.KZ).build());

        Lead l = intake.ingest(site(ext(), "8799" + d, null)).orElseThrow();

        assertThat(l.getFacility()).isNotNull();
        assertThat(l.getFacility().getId()).isEqualTo(f.getId());
    }

    @Test
    void matchesClientByEmailWhenPhoneGivesNothing() {
        String email = "Info@ZZ-" + System.nanoTime() + ".kz";
        Facility f = facilityRepository.save(Facility.builder()
                .name("ZZ Клиника почта " + System.nanoTime()).email(email).market(Market.KZ).build());

        Lead l = intake.ingest(site(ext(), null, email.toLowerCase())).orElseThrow();

        assertThat(l.getFacility().getId()).isEqualTo(f.getId());
    }

    @Test
    void ambiguousPhoneLeavesClientEmpty() {
        String d = uniq7();
        facilityRepository.save(Facility.builder().name("ZZ Дубль А " + System.nanoTime()).phone("+7799" + d).market(Market.KZ).build());
        facilityRepository.save(Facility.builder().name("ZZ Дубль Б " + System.nanoTime()).phone("8 799 " + d).market(Market.KZ).build());

        assertThat(intake.ingest(site(ext(), "+7799" + d, null)).orElseThrow().getFacility()).isNull();
    }

    @Test
    void clientOfAnotherMarketIsNotMatched() {
        String d = uniq7();
        facilityRepository.save(Facility.builder().name("ZZ РФ клиника " + System.nanoTime()).phone("+7799" + d).market(Market.RF).build());

        assertThat(intake.ingest(site(ext(), "+7799" + d, null)).orElseThrow().getFacility()).isNull();
    }

    @Test
    void importedFromHistoryKeepsSourceStatusWithEvent() {
        Lead l = intake.ingest(site(ext(), null, "h@zz.kz", List.of(), LeadStatus.CLOSED, "CLOSED")).orElseThrow();

        assertThat(l.getStatus()).isEqualTo(LeadStatus.CLOSED);
        assertThat(l.getExtStatusPending()).isNull();
        assertThat(l.getEvents()).extracting(LeadEvent::getType).containsExactly(LeadEventType.RECEIVED, LeadEventType.STATUS);
        assertThat(l.getEvents().get(1).getBody()).isEqualTo("Импортировано со статусом источника «Закрыта»");
    }

    @Test
    void blankItemsAreSkippedAndQuantityIsAtLeastOne() {
        Lead l = intake.ingest(site(ext(), null, "b@zz.kz",
                List.of(new IncomingLead.Item("  ", null, 1, null), new IncomingLead.Item("Шприц 5 мл", null, 0, null)),
                LeadStatus.NEW, "NEW")).orElseThrow();

        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getQuantity, LeadItem::getLineNo)
                .containsExactly(tuple("Шприц 5 мл", 1, 1));
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadIntakeServiceTest'`
Expected: FAIL — нет `IncomingLead`, `LeadIntakeService`.

- [ ] **Step 3: Утилита текста и шов входа**

`util/LeadText.java`:
```java
package com.vladoose.nir.util;

/** Мелочи обработки текста обращений (обрезка под длину колонки, пустое → null). */
public final class LeadText {

    private LeadText() {}

    public static boolean isBlank(String s) { return s == null || s.isBlank(); }

    public static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }

    public static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
```

`integration/lead/IncomingLead.java`:
```java
package com.vladoose.nir.integration.lead;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Шов входа обращений (спека §3, §7): так в АИС попадает всё — опрос сайта, ручной ввод,
 * позже вебхуки WhatsApp Business и АТС. externalId = null — у ручных обращений дублей не бывает.
 * initialStatus/extStatus — статус источника при первом появлении (история сайта), author — логин,
 * если обращение внёс человек.
 */
public record IncomingLead(
        String source,
        String externalId,
        LeadChannel channel,
        String subject,
        OffsetDateTime receivedAt,
        String contactName,
        String contactPhone,
        String contactEmail,
        String company,
        String message,
        List<Item> items,
        LeadStatus initialStatus,
        String extStatus,
        String author) {

    public record Item(String name, String brand, int quantity, String productUrl) {}

    public IncomingLead {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
```

`integration/lead/LeadSources.java`:
```java
package com.vladoose.nir.integration.lead;

import com.vladoose.nir.entity.LeadStatus;

/** Коды источников обращений и соответствие статусов АИС статусам сайта (спека §5). */
public final class LeadSources {

    public static final String WESTMED = "westmed.kz";
    public static final String MANUAL = "manual";

    private LeadSources() {}

    /** Источники, куда АИС пишет статус обратно. Сейчас — только westmed.kz. */
    public static boolean writesBack(String source) {
        return WESTMED.equals(source);
    }

    /** Статус обращения → статус заявки на сайте (NEW / PROCESSED / CLOSED). */
    public static String siteStatusFor(LeadStatus status) {
        return switch (status) {
            case NEW -> "NEW";
            case IN_WORK, CONVERTED -> "PROCESSED";
            case CLOSED -> "CLOSED";
        };
    }

    /** Подпись статуса сайта — как в его админке. */
    public static String siteStatusLabel(String siteStatus) {
        if (siteStatus == null) return "—";
        return switch (siteStatus) {
            case "NEW" -> "Новая";
            case "PROCESSED" -> "В работе";
            case "CLOSED" -> "Закрыта";
            default -> siteStatus;
        };
    }
}
```

- [ ] **Step 4: Поиск клиента**

В `repository/FacilityRepository.java` добавить импорты `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param` и методы:

```java
    /**
     * Клиент по последним 10 цифрам телефона, в каком бы виде тот ни был введён.
     * Нативный SQL рыночным фильтром аспекта НЕ режется — рынок передаётся явно.
     */
    @Query(value = "SELECT * FROM facility WHERE market = :market AND phone IS NOT NULL "
            + "AND right(regexp_replace(phone, '\\D', '', 'g'), 10) = :last10", nativeQuery = true)
    List<Facility> findByMarketAndPhoneLast10(@Param("market") String market, @Param("last10") String last10);

    List<Facility> findByMarketAndEmailIgnoreCase(Market market, String email);
```

`service/LeadClientMatcher.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.util.PhoneNormalizer;
import org.springframework.stereotype.Service;

import java.util.List;

/** Клиент обращения: по телефону (последние 10 цифр), иначе по email. Берём только ОДНОЗНАЧНОЕ совпадение. */
@Service
public class LeadClientMatcher {

    private final FacilityRepository facilityRepository;

    public LeadClientMatcher(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    public Facility match(Market market, String phoneNorm, String email) {
        String last10 = PhoneNormalizer.last10(phoneNorm);
        if (last10 != null) {
            List<Facility> byPhone = facilityRepository.findByMarketAndPhoneLast10(market.name(), last10);
            if (byPhone.size() == 1) return byPhone.get(0);
        }
        if (email != null && !email.isBlank()) {
            List<Facility> byEmail = facilityRepository.findByMarketAndEmailIgnoreCase(market, email.trim());
            if (byEmail.size() == 1) return byEmail.get(0);
        }
        return null;
    }
}
```

- [ ] **Step 5: Точка входа**

`service/LeadIntakeService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.util.PhoneNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

import static com.vladoose.nir.util.LeadText.blankToNull;
import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Единая точка входа обращений (спека §7). Идемпотентна по (source, externalId); рынок — из
 * MarketContext (фоновый вызывающий ставит его ЯВНО, §6 CLAUDE.md). Гонку двух вставок ловит
 * уникальный индекс — вызывающий трактует DataIntegrityViolationException как «уже есть».
 */
@Service
public class LeadIntakeService {

    private final LeadRepository leadRepository;
    private final LeadClientMatcher clientMatcher;

    public LeadIntakeService(LeadRepository leadRepository, LeadClientMatcher clientMatcher) {
        this.leadRepository = leadRepository;
        this.clientMatcher = clientMatcher;
    }

    @Transactional(readOnly = true)
    public boolean isKnown(String source, String externalId) {
        return externalId != null && leadRepository.existsBySourceAndExternalId(source, externalId);
    }

    /** Пусто — такое обращение уже есть. */
    @Transactional
    public Optional<Lead> ingest(IncomingLead in) {
        if (isKnown(in.source(), in.externalId())) {
            return Optional.empty();
        }
        Market market = MarketContext.get();
        String phoneNorm = PhoneNormalizer.normalize(in.contactPhone());
        Lead lead = Lead.builder()
                .market(market)   // пред-штамп (defense-in-depth к листенеру)
                .channel(in.channel())
                .source(in.source())
                .externalId(in.externalId())
                .subject(trunc(in.subject(), 200))
                .contactName(trunc(blankToNull(in.contactName()), 255))
                .contactPhone(trunc(blankToNull(in.contactPhone()), 50))
                .phoneNorm(phoneNorm)
                .contactEmail(trunc(blankToNull(in.contactEmail()), 255))
                .company(trunc(blankToNull(in.company()), 255))
                .message(blankToNull(in.message()))
                .facility(clientMatcher.match(market, phoneNorm, in.contactEmail()))
                .status(in.initialStatus() != null ? in.initialStatus() : LeadStatus.NEW)
                .receivedAt(in.receivedAt() != null ? in.receivedAt() : OffsetDateTime.now())
                .extStatus(in.extStatus())
                .build();
        for (IncomingLead.Item it : in.items()) {
            if (it.name() == null || it.name().isBlank()) continue;
            lead.addItem(trunc(it.name().trim(), 500), trunc(blankToNull(it.brand()), 255),
                    it.quantity(), trunc(blankToNull(it.productUrl()), 500));
        }
        LeadEvent received = lead.addEvent(LeadEventType.RECEIVED, in.author(), receivedText(in, lead.getItems().size()));
        received.setDirection(LeadDirection.IN);
        received.setChannel(in.channel());
        if (in.extStatus() != null && lead.getStatus() != LeadStatus.NEW) {
            lead.addEvent(LeadEventType.STATUS, null,
                    "Импортировано со статусом источника «" + LeadSources.siteStatusLabel(in.extStatus()) + "»");
        }
        return Optional.of(leadRepository.save(lead));
    }

    private static String receivedText(IncomingLead in, int itemCount) {
        String base = in.subject() + (itemCount > 0 ? ", " + itemCount + " поз." : "");
        return LeadSources.MANUAL.equals(in.source()) ? base + " — внесено вручную" : base + " — " + in.source();
    }
}
```

- [ ] **Step 6: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadIntakeServiceTest'`
Expected: PASS, 8 тестов.

- [ ] **Step 7: Мутация — тест дубля должен ловить снятие проверки**

Временно закомментировать в `LeadIntakeService.ingest` блок `if (isKnown(in.source(), in.externalId())) { return Optional.empty(); }`.
Run: `./gradlew test --tests 'com.vladoose.nir.lead.LeadIntakeServiceTest.secondIngestWithSameExternalIdIsDuplicate'`
Expected: FAIL (второй `ingest` роняет уникальный индекс или возвращает непустое). Вернуть блок, перезапустить — PASS.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/util/LeadText.java \
  src/main/java/com/vladoose/nir/integration/lead/ src/main/java/com/vladoose/nir/service/LeadClientMatcher.java \
  src/main/java/com/vladoose/nir/service/LeadIntakeService.java src/main/java/com/vladoose/nir/repository/FacilityRepository.java \
  src/test/java/com/vladoose/nir/lead/LeadIntakeServiceTest.java
git commit -m "$(printf 'feat(leads): единая точка входа обращений + поиск клиента по телефону/email\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 3: `LeadService` — ручной ввод, список, переходы статусов, лента, позиции

**Files:**
- Create: `dto/request/LeadCreateRequest.java`, `dto/request/LeadItemDto.java`
- Create: `service/LeadService.java`
- Test: `lead/LeadServiceTest.java`

**Interfaces:**
- Consumes (Tasks 1–2): `LeadRepository`, `LeadIntakeService.ingest`, `IncomingLead`, `LeadSources`, `LeadText`.
- Produces:
  - `LeadCreateRequest` (`channel: LeadChannel`, `contactName`, `contactPhone`, `company`, `contactEmail`, `message`, `items: List<LeadItemDto>`), `LeadItemDto` (`id: Long`, `name`, `brand`, `quantity: Integer`, `productUrl`).
  - `LeadService`: `list(Set<LeadStatus>, LeadChannel, String q): List<Lead>`, `count(LeadStatus): long`, `get(Long): Lead`, `samePhone(Lead): List<Lead>`, `findByPrivateRequest(Long): Optional<Lead>`, `createManual(LeadCreateRequest, String author): Lead`, `take(Long, String author): Lead`, `close(Long, LeadCloseReason, String comment, String author): Lead`, `reopen(Long, String author): Lead`, `addEvent(Long, LeadEventType, LeadDirection, String body, String author): Lead`, `updateItems(Long, List<LeadItemDto>, String author): Lead`; статические `statusLabel(LeadStatus)`, `closeReasonLabel(LeadCloseReason)`; константа `LIST_LIMIT = 300`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/lead/LeadServiceTest.java`:

```java
package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LeadCreateRequest;
import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadServiceTest {

    @Autowired LeadService service;
    @Autowired LeadIntakeService intake;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Lead siteLead(LeadStatus status, String extStatus) {
        return siteLead(status, extStatus, "Иван", "Нужен облучатель");
    }

    private Lead siteLead(LeadStatus status, String extStatus, String name, String message) {
        return intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:zz-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос цены", null, name, null, "ivan-" + System.nanoTime() + "@zz.kz", null, message,
                List.of(new IncomingLead.Item("Облучатель ОБН-150", "Азов", 1, null)),
                status, extStatus, null)).orElseThrow();
    }

    private static LeadCreateRequest manual(LeadChannel channel, String phone, String email) {
        LeadCreateRequest r = new LeadCreateRequest();
        r.setChannel(channel);
        r.setContactName("Звонивший");
        r.setContactPhone(phone);
        r.setContactEmail(email);
        r.setMessage("Ищут УЗИ-аппарат для гинекологии");
        return r;
    }

    private static LeadItemDto item(String name, String brand, int qty) {
        LeadItemDto d = new LeadItemDto();
        d.setName(name);
        d.setBrand(brand);
        d.setQuantity(qty);
        return d;
    }

    private static LeadEvent last(Lead l) { return l.getEvents().get(l.getEvents().size() - 1); }

    @Test
    void takeMovesNewToInWorkAndQueuesSiteStatus() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");

        Lead after = service.take(l.getId(), "admin");

        assertThat(after.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(after.getExtStatusPending()).isEqualTo("PROCESSED");
        assertThat(last(after).getType()).isEqualTo(LeadEventType.STATUS);
        assertThat(last(after).getAuthor()).isEqualTo("admin");
        assertThat(last(after).getBody()).isEqualTo("Новое → В работе");
    }

    @Test
    void takeIsRejectedWhenNotNew() {
        Lead l = siteLead(LeadStatus.IN_WORK, "PROCESSED");
        assertThatThrownBy(() -> service.take(l.getId(), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("взять в работу");
    }

    @Test
    void closeNeedsReasonAndStoresReasonAndComment() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        assertThatThrownBy(() -> service.close(l.getId(), null, null, "admin")).isInstanceOf(BadRequestException.class);

        Lead closed = service.close(l.getId(), LeadCloseReason.SPAM, "реклама", "admin");

        assertThat(closed.getStatus()).isEqualTo(LeadStatus.CLOSED);
        assertThat(closed.getCloseReason()).isEqualTo(LeadCloseReason.SPAM);
        assertThat(closed.getExtStatusPending()).isEqualTo("CLOSED");
        assertThat(last(closed).getBody()).contains("спам").contains("реклама");
    }

    @Test
    void pendingIsDroppedWhenSiteAlreadyHasTheTargetStatus() {
        Lead l = siteLead(LeadStatus.IN_WORK, "PROCESSED");     // на сайте уже «В работе»
        service.close(l.getId(), LeadCloseReason.DUPLICATE, null, "admin");

        Lead reopened = service.reopen(l.getId(), "admin");    // снова «В работе» — писать на сайт нечего

        assertThat(reopened.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(reopened.getCloseReason()).isNull();
        assertThat(reopened.getExtStatusPending()).isNull();
    }

    @Test
    void manualLeadIsInWorkAndNeverQueuesSiteStatus() {
        Lead l = service.createManual(manual(LeadChannel.PHONE, "8 777 000 00 01", null), "admin");

        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getSource()).isEqualTo(LeadSources.MANUAL);
        assertThat(l.getExternalId()).isNull();
        assertThat(l.getSubject()).isEqualTo("Звонок");
        assertThat(l.getPhoneNorm()).isEqualTo("+77770000001");
        assertThat(l.getEvents().get(0).getAuthor()).isEqualTo("admin");
        assertThat(l.getEvents().get(0).getBody()).endsWith("внесено вручную");

        Lead closed = service.close(l.getId(), LeadCloseReason.ANSWERED, null, "admin");
        assertThat(closed.getExtStatusPending()).isNull();
    }

    @Test
    void manualLeadNeedsPhoneOrEmail() {
        assertThatThrownBy(() -> service.createManual(manual(LeadChannel.WHATSAPP, " ", null), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("телефон или email");
        assertThat(service.createManual(manual(LeadChannel.WHATSAPP, null, "x@zz.kz"), "admin").getSubject())
                .isEqualTo("WhatsApp");
    }

    @Test
    void manualLeadCannotPretendToBeSite() {
        assertThatThrownBy(() -> service.createManual(manual(LeadChannel.SITE, "+77770000001", null), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void callNeedsDirectionAndIsStoredAsPhoneEvent() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        assertThatThrownBy(() -> service.addEvent(l.getId(), LeadEventType.CALL, null, "перезвонил", "admin"))
                .isInstanceOf(BadRequestException.class);

        Lead after = service.addEvent(l.getId(), LeadEventType.CALL, LeadDirection.OUT, "уточнил модель", "admin");

        assertThat(last(after).getType()).isEqualTo(LeadEventType.CALL);
        assertThat(last(after).getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(last(after).getChannel()).isEqualTo(LeadChannel.PHONE);
        assertThat(last(after).getBody()).isEqualTo("уточнил модель");
    }

    @Test
    void systemEventTypesCannotBeAddedByHand() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        assertThatThrownBy(() -> service.addEvent(l.getId(), LeadEventType.STATUS, null, "x", "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void itemsAreReplacedWholesaleOnlyBeforeConversion() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");

        Lead after = service.updateItems(l.getId(), List.of(item("Облучатель ОБН-75", "Азов", 3), item("  ", null, 1)), "admin");

        assertThat(after.getItems()).extracting(LeadItem::getName, LeadItem::getQuantity, LeadItem::getLineNo)
                .containsExactly(tuple("Облучатель ОБН-75", 3, 1));
        service.close(l.getId(), LeadCloseReason.OTHER, null, "admin");
        assertThatThrownBy(() -> service.updateItems(l.getId(), List.of(item("Х", null, 1)), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void leadOfAnotherMarketIsNotFound() {
        Lead l = siteLead(LeadStatus.NEW, "NEW");
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.get(l.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listFiltersByStatusChannelAndSearchText() {
        String tag = "ZZТег" + System.nanoTime();
        Lead fresh = siteLead(LeadStatus.NEW, "NEW", tag + " Иванов", "Нужен облучатель");
        Lead inWork = siteLead(LeadStatus.IN_WORK, "PROCESSED", "Петров", "Ищем " + tag);

        assertThat(service.list(EnumSet.of(LeadStatus.NEW), null, tag))
                .extracting(Lead::getId).containsExactly(fresh.getId());
        assertThat(service.list(EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), null, tag.toLowerCase()))
                .extracting(Lead::getId).containsExactlyInAnyOrder(fresh.getId(), inWork.getId());
        assertThat(service.list(EnumSet.allOf(LeadStatus.class), LeadChannel.PHONE, tag)).isEmpty();
    }

    @Test
    void countIsPerMarket() {
        long kzBefore = service.count(LeadStatus.NEW);
        MarketContext.set(Market.RF);
        long rfBefore = service.count(LeadStatus.NEW);
        MarketContext.set(Market.KZ);

        siteLead(LeadStatus.NEW, "NEW");

        assertThat(service.count(LeadStatus.NEW)).isEqualTo(kzBefore + 1);
        MarketContext.set(Market.RF);
        assertThat(service.count(LeadStatus.NEW)).isEqualTo(rfBefore);
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadServiceTest'`
Expected: FAIL — нет `LeadService`, `LeadCreateRequest`, `LeadItemDto`.

- [ ] **Step 3: DTO**

`dto/request/LeadItemDto.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Позиция обращения — и во входе (правка, ручной ввод), и в карточке. */
@Data
public class LeadItemDto {
    private Long id;
    @Size(max = 500) private String name;
    @Size(max = 255) private String brand;
    private Integer quantity;
    @Size(max = 500) private String productUrl;
}
```

`dto/request/LeadCreateRequest.java`:
```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LeadChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Ручное обращение: звонок, WhatsApp или другое. Телефон или email обязателен — проверяет сервис. */
@Data
public class LeadCreateRequest {
    private LeadChannel channel;
    @Size(max = 255) private String contactName;
    @Size(max = 50) private String contactPhone;
    @Size(max = 255) private String company;
    @Size(max = 255) private String contactEmail;
    @Size(max = 5000) private String message;
    @Valid private List<LeadItemDto> items;
}
```

- [ ] **Step 4: Сервис**

`service/LeadService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LeadCreateRequest;
import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

import static com.vladoose.nir.util.LeadText.*;

/**
 * Работа с обращениями: список, карточка, ручной ввод, переходы статусов (спека §5), лента, позиции.
 * Мастер статусов — АИС: у источников с обратной записью (westmed.kz) переход ставит ext_status_pending,
 * а записывает его на сайт планировщик интеграции.
 */
@Service
public class LeadService {

    public static final int LIST_LIMIT = 300;

    private static final Set<LeadChannel> MANUAL_CHANNELS =
            EnumSet.of(LeadChannel.PHONE, LeadChannel.WHATSAPP, LeadChannel.OTHER);

    private final LeadRepository leadRepository;
    private final LeadIntakeService intake;

    public LeadService(LeadRepository leadRepository, LeadIntakeService intake) {
        this.leadRepository = leadRepository;
        this.intake = intake;
    }

    /** Новые сверху, не больше LIST_LIMIT; поиск — по имени, компании, email, теме, тексту, телефону, позициям. */
    @Transactional(readOnly = true)
    public List<Lead> list(Set<LeadStatus> statuses, LeadChannel channel, String q) {
        Pageable top = PageRequest.of(0, LIST_LIMIT);
        List<Lead> rows = channel == null
                ? leadRepository.findByStatusInOrderByReceivedAtDescIdDesc(statuses, top)
                : leadRepository.findByStatusInAndChannelOrderByReceivedAtDescIdDesc(statuses, channel, top);
        if (isBlank(q)) return rows;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");
        return rows.stream().filter(l -> matches(l, needle, digits)).toList();
    }

    @Transactional(readOnly = true)
    public long count(LeadStatus status) {
        return leadRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public Lead get(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Обращение не найдено: id=" + id));
        // аспект отсеивает чужой рынок; гард — defense-in-depth (как в WinnerAssignmentService)
        if (lead.getMarket() != null && lead.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Обращение не найдено: id=" + id);
        }
        return lead;
    }

    /** До 5 других обращений того же рынка с тем же телефоном — подсказка «этот номер уже обращался». */
    @Transactional(readOnly = true)
    public List<Lead> samePhone(Lead lead) {
        return lead.getPhoneNorm() == null ? List.of()
                : leadRepository.findTop5ByPhoneNormAndIdNotOrderByReceivedAtDesc(lead.getPhoneNorm(), lead.getId());
    }

    @Transactional(readOnly = true)
    public Optional<Lead> findByPrivateRequest(Long privateRequestId) {
        return leadRepository.findFirstByPrivateRequestId(privateRequestId);
    }

    /** Звонок / WhatsApp / другое, внесённое вручную. Сразу «В работе»: кто принял звонок, тот и ведёт. */
    @Transactional
    public Lead createManual(LeadCreateRequest req, String author) {
        LeadChannel channel = req.getChannel() != null ? req.getChannel() : LeadChannel.PHONE;
        if (!MANUAL_CHANNELS.contains(channel)) {
            throw new BadRequestException("Вручную вносится звонок, WhatsApp или другое обращение");
        }
        if (isBlank(req.getContactPhone()) && isBlank(req.getContactEmail())) {
            throw new BadRequestException("Укажите телефон или email клиента");
        }
        List<IncomingLead.Item> items = req.getItems() == null ? List.of() : req.getItems().stream()
                .filter(i -> !isBlank(i.getName()))
                .map(i -> new IncomingLead.Item(i.getName(), i.getBrand(),
                        i.getQuantity() != null ? i.getQuantity() : 1, null))
                .toList();
        IncomingLead in = new IncomingLead(LeadSources.MANUAL, null, channel, manualSubject(channel),
                OffsetDateTime.now(), req.getContactName(), req.getContactPhone(), req.getContactEmail(),
                req.getCompany(), req.getMessage(), items, LeadStatus.IN_WORK, null, author);
        return intake.ingest(in).orElseThrow();   // externalId = null → дублей не бывает
    }

    @Transactional
    public Lead take(Long id, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW), "взять в работу");
        transition(lead, LeadStatus.IN_WORK, author, null);
        return lead;
    }

    @Transactional
    public Lead close(Long id, LeadCloseReason reason, String comment, String author) {
        if (reason == null) throw new BadRequestException("Укажите причину закрытия");
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED), "закрыть");
        lead.setCloseReason(reason);
        transition(lead, LeadStatus.CLOSED, author,
                closeReasonLabel(reason) + (isBlank(comment) ? "" : " — " + comment.trim()));
        return lead;
    }

    @Transactional
    public Lead reopen(Long id, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.CLOSED), "вернуть в работу");
        lead.setCloseReason(null);
        transition(lead, lead.getPrivateRequest() != null ? LeadStatus.CONVERTED : LeadStatus.IN_WORK, author, null);
        return lead;
    }

    /** Заметка или звонок, внесённые человеком. Системные типы (RECEIVED/STATUS/SYNC/MESSAGE) — нельзя. */
    @Transactional
    public Lead addEvent(Long id, LeadEventType type, LeadDirection direction, String body, String author) {
        if (type != LeadEventType.NOTE && type != LeadEventType.CALL) {
            throw new BadRequestException("Вручную добавляется только заметка или звонок");
        }
        if (isBlank(body)) throw new BadRequestException("Текст пустой");
        if (type == LeadEventType.CALL && direction == null) {
            throw new BadRequestException("Укажите, входящий это звонок или исходящий");
        }
        Lead lead = get(id);
        LeadEvent e = lead.addEvent(type, author, body.trim());
        if (type == LeadEventType.CALL) {
            e.setDirection(direction);
            e.setChannel(LeadChannel.PHONE);
        }
        lead.setUpdatedAt(OffsetDateTime.now());   // новый ребёнок не пачкает родителя — время правки ставим сами
        return lead;
    }

    /** Позиции заменяются целиком (через коллекцию — orphanRemoval, §7). Только до превращения в заявку. */
    @Transactional
    public Lead updateItems(Long id, List<LeadItemDto> items, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), "править позиции");
        lead.getItems().clear();
        for (LeadItemDto i : items == null ? List.<LeadItemDto>of() : items) {
            if (isBlank(i.getName())) continue;
            lead.addItem(trunc(i.getName().trim(), 500), trunc(blankToNull(i.getBrand()), 255),
                    i.getQuantity() != null ? i.getQuantity() : 1, trunc(blankToNull(i.getProductUrl()), 500));
        }
        lead.addEvent(LeadEventType.NOTE, author, "Позиции обновлены: " + lead.getItems().size() + " поз.");
        lead.setUpdatedAt(OffsetDateTime.now());
        return lead;
    }

    void require(Lead lead, Set<LeadStatus> from, String action) {
        if (!from.contains(lead.getStatus())) {
            throw new BadRequestException("Нельзя " + action + ": обращение в статусе «" + statusLabel(lead.getStatus()) + "»");
        }
    }

    void transition(Lead lead, LeadStatus to, String author, String note) {
        LeadStatus from = lead.getStatus();
        lead.setStatus(to);
        lead.addEvent(LeadEventType.STATUS, author,
                statusLabel(from) + " → " + statusLabel(to) + (note == null ? "" : ": " + note));
        if (LeadSources.writesBack(lead.getSource())) {
            String site = LeadSources.siteStatusFor(to);
            lead.setExtStatusPending(site.equals(lead.getExtStatus()) ? null : site);
        }
    }

    public static String statusLabel(LeadStatus s) {
        return switch (s) {
            case NEW -> "Новое";
            case IN_WORK -> "В работе";
            case CONVERTED -> "Заявка создана";
            case CLOSED -> "Закрыто";
        };
    }

    public static String closeReasonLabel(LeadCloseReason r) {
        return switch (r) {
            case ANSWERED -> "ответили клиенту без заявки";
            case SPAM -> "спам";
            case DUPLICATE -> "дубль";
            case NOT_OUR_PROFILE -> "не наш профиль";
            case CLIENT_DECLINED -> "клиент отказался";
            case OTHER -> "другое";
        };
    }

    static String manualSubject(LeadChannel channel) {
        return switch (channel) {
            case PHONE -> "Звонок";
            case WHATSAPP -> "WhatsApp";
            default -> "Обращение";
        };
    }

    private static boolean matches(Lead l, String needle, String digits) {
        if (contains(l.getContactName(), needle) || contains(l.getCompany(), needle)
                || contains(l.getContactEmail(), needle) || contains(l.getSubject(), needle)
                || contains(l.getMessage(), needle)) {
            return true;
        }
        if (digits.length() >= 4) {
            if (l.getPhoneNorm() != null && l.getPhoneNorm().contains(digits)) return true;
            if (l.getContactPhone() != null && l.getContactPhone().replaceAll("\\D", "").contains(digits)) return true;
        }
        return l.getItems().stream().anyMatch(i -> contains(i.getName(), needle) || contains(i.getBrand(), needle));
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
```

- [ ] **Step 5: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadServiceTest'`
Expected: PASS, 13 тестов.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/request/LeadCreateRequest.java \
  src/main/java/com/vladoose/nir/dto/request/LeadItemDto.java src/main/java/com/vladoose/nir/service/LeadService.java \
  src/test/java/com/vladoose/nir/lead/LeadServiceTest.java
git commit -m "$(printf 'feat(leads): ручной ввод, переходы статусов с очередью записи на сайт, лента, позиции\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 4: REST `/api/leads` — DTO ответов, маппер, контроллер

**Files:**
- Create: `dto/request/LeadItemsUpdate.java`, `dto/request/LeadCloseRequest.java`, `dto/request/LeadEventCreate.java`
- Create: `dto/response/LeadListItemResponse.java`, `dto/response/LeadCardResponse.java`, `dto/response/LeadEventResponse.java`, `dto/response/LeadRefResponse.java`
- Create: `mapper/LeadResponseMapper.java`
- Create: `controller/LeadController.java`
- Test: `lead/LeadControllerTest.java`

**Interfaces:**
- Consumes (Task 3): все публичные методы `LeadService`, `LeadItemDto`, `LeadCreateRequest`.
- Produces:
  - `LeadResponseMapper.toListItem(Lead): LeadListItemResponse`, `toCard(Lead, List<Lead> samePhone): LeadCardResponse`, `toRef(Lead): LeadRefResponse`.
  - `LeadListItemResponse`: `id, channel, source, subject, contactName, contactPhone, company, itemsCount, itemsPreview (List<String>), messagePreview, status, closeReason, receivedAt, privateRequestId, privateRequestNumber, syncError (boolean)`.
  - `LeadCardResponse`: `id, channel, source, subject, contactName, contactPhone, phoneNorm, contactEmail, company, message, status, closeReason, receivedAt, facilityId, facilityName, privateRequestId, privateRequestNumber, extStatus, extStatusPending, extSyncError, items (List<LeadItemDto>), events (List<LeadEventResponse>), samePhone (List<LeadRefResponse>)`.
  - `LeadEventResponse`: `id, at, type, direction, channel, author, body`. `LeadRefResponse`: `id, subject, source, status, receivedAt`.
  - `LeadController` (`/api/leads`): `list(String status, LeadChannel channel, String q)`, `count(LeadStatus)`, `get(Long)`, `create(LeadCreateRequest)`, `updateItems(Long, LeadItemsUpdate)`, `take(Long)`, `close(Long, LeadCloseRequest)`, `reopen(Long)`, `addEvent(Long, LeadEventCreate)`; статический `parseStatuses(String): Set<LeadStatus>`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/lead/LeadControllerTest.java`:

```java
package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.dto.response.LeadListItemResponse;
import com.vladoose.nir.dto.response.LeadRefResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.service.LeadIntakeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadControllerTest {

    @Autowired LeadController controller;
    @Autowired LeadIntakeService intake;

    @AfterEach void clear() { MarketContext.clear(); }

    private Lead lead(String phone) {
        MarketContext.set(Market.KZ);
        return intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим", phone, "a-" + System.nanoTime() + "@zz.kz", "ТОО «ZZ»",
                "Нужны облучатели", List.of(
                        new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, null),
                        new IncomingLead.Item("Облучатель ОБН-75", "Азов", 1, null),
                        new IncomingLead.Item("Рециркулятор", null, 1, null)),
                LeadStatus.NEW, "NEW", null)).orElseThrow();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsButCannotWrite() {
        Lead l = lead(null);
        assertThat(controller.get(l.getId()).getId()).isEqualTo(l.getId());
        assertThatThrownBy(() -> controller.take(l.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void actionIsAuthoredByCurrentUser() {
        Lead l = lead(null);

        LeadCardResponse card = controller.take(l.getId());

        assertThat(card.getStatus()).isEqualTo("IN_WORK");
        assertThat(card.getExtStatusPending()).isEqualTo("PROCESSED");
        assertThat(card.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STATUS");
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listItemPreviewsFirstTwoPositions() {
        Lead l = lead(null);

        LeadListItemResponse item = controller.list("NEW", null, "ОБН-150").stream()
                .filter(x -> x.getId().equals(l.getId())).findFirst().orElseThrow();

        assertThat(item.getItemsCount()).isEqualTo(3);
        assertThat(item.getItemsPreview()).containsExactly("Облучатель ОБН-150", "Облучатель ОБН-75");
        assertThat(item.getChannel()).isEqualTo("SITE");
        assertThat(item.getSource()).isEqualTo("westmed.kz");
        assertThat(item.getMessagePreview()).isEqualTo("Нужны облучатели");
        assertThat(item.isSyncError()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cardListsOtherLeadsFromTheSamePhone() {
        String phone = "+7799" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        Lead first = lead(phone);
        Lead second = lead(phone);

        assertThat(controller.get(second.getId()).getSamePhone())
                .extracting(LeadRefResponse::getId).containsExactly(first.getId());
    }

    @Test
    void parseStatusesUnderstandsListsAndAll() {
        assertThat(LeadController.parseStatuses("NEW,IN_WORK")).containsExactlyInAnyOrder(LeadStatus.NEW, LeadStatus.IN_WORK);
        assertThat(LeadController.parseStatuses("ALL")).containsExactlyInAnyOrder(LeadStatus.values());
        assertThatThrownBy(() -> LeadController.parseStatuses("NEW,BOGUS")).isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadControllerTest'`
Expected: FAIL — нет `LeadController` и DTO.

- [ ] **Step 3: Входные DTO**

`dto/request/LeadItemsUpdate.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/** Обёртка, чтобы @Valid дошёл до элементов списка позиций. */
@Data
public class LeadItemsUpdate {
    @Valid private List<LeadItemDto> items;
}
```

`dto/request/LeadCloseRequest.java`:
```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LeadCloseReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeadCloseRequest {
    @NotNull(message = "Укажите причину закрытия") private LeadCloseReason reason;
    @Size(max = 1000) private String comment;
}
```

`dto/request/LeadEventCreate.java`:
```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.entity.LeadEventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Заметка (NOTE) или звонок (CALL + direction) в ленту обращения. */
@Data
public class LeadEventCreate {
    @NotNull private LeadEventType type;
    private LeadDirection direction;
    @NotBlank(message = "Текст пустой") @Size(max = 5000) private String body;
}
```

- [ ] **Step 4: Выходные DTO**

`dto/response/LeadRefResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Короткая ссылка на обращение: «этот номер уже обращался», обратная ссылка из частной заявки. */
@Data
public class LeadRefResponse {
    private Long id;
    private String subject;
    private String source;
    private String status;
    private OffsetDateTime receivedAt;
}
```

`dto/response/LeadEventResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class LeadEventResponse {
    private Long id;
    private OffsetDateTime at;
    private String type;
    private String direction;
    private String channel;
    private String author;
    private String body;
}
```

`dto/response/LeadListItemResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class LeadListItemResponse {
    private Long id;
    private String channel;
    private String source;
    private String subject;
    private String contactName;
    private String contactPhone;
    private String company;
    private int itemsCount;
    private List<String> itemsPreview;
    private String messagePreview;
    private String status;
    private String closeReason;
    private OffsetDateTime receivedAt;
    private Long privateRequestId;
    private String privateRequestNumber;
    /** Статус на сайт записать не удалось (повторим автоматически). */
    private boolean syncError;
}
```

`dto/response/LeadCardResponse.java`:
```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.dto.request.LeadItemDto;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class LeadCardResponse {
    private Long id;
    private String channel;
    private String source;
    private String subject;
    private String contactName;
    private String contactPhone;
    private String phoneNorm;
    private String contactEmail;
    private String company;
    private String message;
    private String status;
    private String closeReason;
    private OffsetDateTime receivedAt;
    private Long facilityId;
    private String facilityName;
    private Long privateRequestId;
    private String privateRequestNumber;
    private String extStatus;
    private String extStatusPending;
    private String extSyncError;
    private List<LeadItemDto> items;
    private List<LeadEventResponse> events;
    private List<LeadRefResponse> samePhone;
}
```

- [ ] **Step 5: Маппер**

`mapper/LeadResponseMapper.java`:
```java
package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadEvent;
import com.vladoose.nir.entity.LeadItem;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class LeadResponseMapper {

    private static final int PREVIEW_ITEMS = 2;
    private static final int PREVIEW_CHARS = 140;

    public LeadListItemResponse toListItem(Lead l) {
        LeadListItemResponse r = new LeadListItemResponse();
        r.setId(l.getId());
        r.setChannel(l.getChannel().name());
        r.setSource(l.getSource());
        r.setSubject(l.getSubject());
        r.setContactName(l.getContactName());
        r.setContactPhone(l.getContactPhone());
        r.setCompany(l.getCompany());
        r.setItemsCount(l.getItems().size());
        r.setItemsPreview(l.getItems().stream().limit(PREVIEW_ITEMS).map(LeadItem::getName).toList());
        r.setMessagePreview(preview(l.getMessage()));
        r.setStatus(l.getStatus().name());
        r.setCloseReason(l.getCloseReason() == null ? null : l.getCloseReason().name());
        r.setReceivedAt(l.getReceivedAt());
        if (l.getPrivateRequest() != null) {
            r.setPrivateRequestId(l.getPrivateRequest().getId());
            r.setPrivateRequestNumber(l.getPrivateRequest().getTenderNumber());
        }
        r.setSyncError(l.getExtSyncError() != null);
        return r;
    }

    public LeadCardResponse toCard(Lead l, List<Lead> samePhone) {
        LeadCardResponse r = new LeadCardResponse();
        r.setId(l.getId());
        r.setChannel(l.getChannel().name());
        r.setSource(l.getSource());
        r.setSubject(l.getSubject());
        r.setContactName(l.getContactName());
        r.setContactPhone(l.getContactPhone());
        r.setPhoneNorm(l.getPhoneNorm());
        r.setContactEmail(l.getContactEmail());
        r.setCompany(l.getCompany());
        r.setMessage(l.getMessage());
        r.setStatus(l.getStatus().name());
        r.setCloseReason(l.getCloseReason() == null ? null : l.getCloseReason().name());
        r.setReceivedAt(l.getReceivedAt());
        if (l.getFacility() != null) {
            r.setFacilityId(l.getFacility().getId());
            r.setFacilityName(l.getFacility().getName());
        }
        if (l.getPrivateRequest() != null) {
            r.setPrivateRequestId(l.getPrivateRequest().getId());
            r.setPrivateRequestNumber(l.getPrivateRequest().getTenderNumber());
        }
        r.setExtStatus(l.getExtStatus());
        r.setExtStatusPending(l.getExtStatusPending());
        r.setExtSyncError(l.getExtSyncError());
        r.setItems(l.getItems().stream().map(LeadResponseMapper::toItem).toList());
        r.setEvents(l.getEvents().stream()
                .sorted(Comparator.comparing(LeadEvent::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LeadEvent::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(LeadResponseMapper::toEvent).toList());
        r.setSamePhone(samePhone.stream().map(this::toRef).toList());
        return r;
    }

    public LeadRefResponse toRef(Lead l) {
        LeadRefResponse r = new LeadRefResponse();
        r.setId(l.getId());
        r.setSubject(l.getSubject());
        r.setSource(l.getSource());
        r.setStatus(l.getStatus().name());
        r.setReceivedAt(l.getReceivedAt());
        return r;
    }

    private static LeadItemDto toItem(LeadItem i) {
        LeadItemDto d = new LeadItemDto();
        d.setId(i.getId());
        d.setName(i.getName());
        d.setBrand(i.getBrand());
        d.setQuantity(i.getQuantity());
        d.setProductUrl(i.getProductUrl());
        return d;
    }

    private static LeadEventResponse toEvent(LeadEvent e) {
        LeadEventResponse r = new LeadEventResponse();
        r.setId(e.getId());
        r.setAt(e.getOccurredAt());
        r.setType(e.getType().name());
        r.setDirection(e.getDirection() == null ? null : e.getDirection().name());
        r.setChannel(e.getChannel() == null ? null : e.getChannel().name());
        r.setAuthor(e.getAuthor());
        r.setBody(e.getBody());
        return r;
    }

    static String preview(String text) {
        if (text == null || text.isBlank()) return null;
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS - 1) + "…";
    }
}
```

- [ ] **Step 6: Контроллер**

`controller/LeadController.java`:
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.dto.response.LeadListItemResponse;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.LeadResponseMapper;
import com.vladoose.nir.service.LeadService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** «Обращения» (спека §8). Чтение — любому вошедшему, запись — ADMIN, как везде в АИС. */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadService service;
    private final LeadResponseMapper mapper;

    public LeadController(LeadService service, LeadResponseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<LeadListItemResponse> list(@RequestParam(defaultValue = "NEW,IN_WORK") String status,
                                           @RequestParam(required = false) LeadChannel channel,
                                           @RequestParam(required = false) String q) {
        return service.list(parseStatuses(status), channel, q).stream().map(mapper::toListItem).toList();
    }

    @GetMapping("/count")
    public Map<String, Long> count(@RequestParam(defaultValue = "NEW") LeadStatus status) {
        return Map.of("count", service.count(status));
    }

    @GetMapping("/{id}")
    public LeadCardResponse get(@PathVariable Long id) {
        return card(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse create(@Valid @RequestBody LeadCreateRequest req) {
        return card(service.createManual(req, currentUser()));
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse updateItems(@PathVariable Long id, @Valid @RequestBody LeadItemsUpdate req) {
        return card(service.updateItems(id, req.getItems(), currentUser()));
    }

    @PostMapping("/{id}/take")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse take(@PathVariable Long id) {
        return card(service.take(id, currentUser()));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse close(@PathVariable Long id, @Valid @RequestBody LeadCloseRequest req) {
        return card(service.close(id, req.getReason(), req.getComment(), currentUser()));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse reopen(@PathVariable Long id) {
        return card(service.reopen(id, currentUser()));
    }

    @PostMapping("/{id}/events")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse addEvent(@PathVariable Long id, @Valid @RequestBody LeadEventCreate req) {
        return card(service.addEvent(id, req.getType(), req.getDirection(), req.getBody(), currentUser()));
    }

    private LeadCardResponse card(Lead lead) {
        return mapper.toCard(lead, service.samePhone(lead));
    }

    /** «NEW,IN_WORK» → набор; «ALL» или пусто → все статусы; неизвестное → 400. */
    public static Set<LeadStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("ALL")) {
            return EnumSet.allOf(LeadStatus.class);
        }
        Set<LeadStatus> out = EnumSet.noneOf(LeadStatus.class);
        for (String part : raw.split(",")) {
            try {
                out.add(LeadStatus.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Неизвестный статус: " + part.trim());
            }
        }
        return out;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
```

- [ ] **Step 7: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadControllerTest'`
Expected: PASS, 5 тестов.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/request/LeadItemsUpdate.java \
  src/main/java/com/vladoose/nir/dto/request/LeadCloseRequest.java src/main/java/com/vladoose/nir/dto/request/LeadEventCreate.java \
  src/main/java/com/vladoose/nir/dto/response/Lead*.java src/main/java/com/vladoose/nir/mapper/LeadResponseMapper.java \
  src/main/java/com/vladoose/nir/controller/LeadController.java src/test/java/com/vladoose/nir/lead/LeadControllerTest.java
git commit -m "$(printf 'feat(leads): REST /api/leads — список, карточка, ручной ввод, переходы, лента\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 5: «Создать частную заявку» одной транзакцией + обратная ссылка из частной заявки

**Files:**
- Create: `dto/request/LeadConvertRequest.java`, `dto/response/LeadConvertResponse.java`
- Modify: `service/LeadService.java` (конструктор + `convert` и помощники)
- Modify: `controller/LeadController.java` (+ эндпоинт `convert`)
- Modify: `repository/FacilityRepository.java` (+ `existsByNameAnyMarket`, `findByName`)
- Modify: `dto/response/PrivateRequestResponse.java` (+ поле `lead`)
- Modify: `controller/PrivateRequestController.java` (+ `LeadService`, `LeadResponseMapper`; поле `lead` в карточке)
- Test: `lead/LeadConvertTest.java`

**Interfaces:**
- Consumes: `LeadService` (Task 3: `get`, `require`, `transition`, `findByPrivateRequest`), `LeadResponseMapper.toRef` (Task 4), существующий `PrivateRequestService.createFromLines(PrivateRequestCreate): Tender`, `PrivateRequestCreate.Line` (`name`, `manufact`, `quantity`).
- Produces:
  - `LeadConvertRequest` (`clientFacilityId: Long`, `newClient: NewClient{name, phone, email, lastName, firstName, middleName}`, `note`, `lines: List<PrivateRequestCreate.Line>`), `LeadConvertResponse(privateRequestId: Long, number: String)`.
  - `LeadService.convert(Long leadId, LeadConvertRequest, String author): LeadConvertResponse`.
  - `LeadController.convert(Long, LeadConvertRequest)` → `POST /api/leads/{id}/convert`.
  - `PrivateRequestResponse.lead: LeadRefResponse` (null, если заявка не из обращения).

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/lead/LeadConvertTest.java`:

```java
package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.PrivateRequestController;
import com.vladoose.nir.dto.request.LeadConvertRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.LeadConvertResponse;
import com.vladoose.nir.dto.response.PrivateRequestResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadConvertTest {

    @Autowired LeadService service;
    @Autowired LeadIntakeService intake;
    @Autowired FacilityRepository facilityRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired PrivateRequestController privateRequestController;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Lead siteLead() {
        return intake.ingest(new IncomingLead(LeadSources.WESTMED, "quote:zz-" + System.nanoTime(), LeadChannel.SITE,
                "Запрос КП", null, "Айгерим Сапарова", "+7 777 000 00 03", "aigerim-" + System.nanoTime() + "@zz.kz",
                "ТОО «ZZ Клиника»", "Нужны облучатели",
                List.of(new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, null)),
                LeadStatus.NEW, "NEW", null)).orElseThrow();
    }

    private Facility client(Market market) {
        return facilityRepository.save(Facility.builder()
                .name("ZZ Конверт клиент " + System.nanoTime()).market(market).build());
    }

    private static PrivateRequestCreate.Line line(String name, String brand, int qty) {
        PrivateRequestCreate.Line l = new PrivateRequestCreate.Line();
        l.setName(name);
        l.setManufact(brand);
        l.setQuantity(qty);
        return l;
    }

    private static LeadConvertRequest withClient(Long facilityId) {
        LeadConvertRequest r = new LeadConvertRequest();
        r.setClientFacilityId(facilityId);
        r.setNote("Нужны облучатели");
        r.setLines(List.of(line("Облучатель ОБН-150", "Азов", 2)));
        return r;
    }

    private static LeadConvertRequest withNewClient(String name) {
        LeadConvertRequest.NewClient nc = new LeadConvertRequest.NewClient();
        nc.setName(name);
        nc.setPhone("+7 777 000 00 03");
        nc.setFirstName("Айгерим");
        nc.setLastName("Сапарова");
        LeadConvertRequest r = new LeadConvertRequest();
        r.setNewClient(nc);
        r.setLines(List.of(line("Облучатель ОБН-150", "Азов", 2)));
        return r;
    }

    @Test
    void existingClientGetsPrivateRequestAndLeadIsLinked() {
        Lead l = siteLead();
        Facility f = client(Market.KZ);

        LeadConvertResponse r = service.convert(l.getId(), withClient(f.getId()), "admin");

        Tender t = tenderRepository.findById(r.getPrivateRequestId()).orElseThrow();
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getTenderNumber()).isEqualTo(r.getNumber()).startsWith("ЧЗ-");
        assertThat(t.getFacility().getId()).isEqualTo(f.getId());
        assertThat(t.getDescription()).isEqualTo("Нужны облучатели");
        assertThat(t.getContactPhone()).isEqualTo("+7 777 000 00 03");
        assertThat(t.getLots()).extracting(TenderLot::getEquipName, TenderLot::getManufact, TenderLot::getQuantity)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2));

        Lead back = service.get(l.getId());
        assertThat(back.getStatus()).isEqualTo(LeadStatus.CONVERTED);
        assertThat(back.getPrivateRequest().getId()).isEqualTo(t.getId());
        assertThat(back.getFacility().getId()).isEqualTo(f.getId());
        assertThat(back.getExtStatusPending()).isEqualTo("PROCESSED");
        assertThat(back.getEvents().get(back.getEvents().size() - 1).getBody()).contains(r.getNumber());
    }

    @Test
    void newClientIsCreatedInCurrentMarket() {
        Lead l = siteLead();
        String name = "ZZ Новый клиент " + System.nanoTime();

        service.convert(l.getId(), withNewClient(name), "admin");

        Facility f = service.get(l.getId()).getFacility();
        assertThat(f.getName()).isEqualTo(name);
        assertThat(f.getMarket()).isEqualTo(Market.KZ);
        assertThat(f.getLastName()).isEqualTo("Сапарова");
        assertThat(f.getPhone()).isEqualTo("+7 777 000 00 03");
    }

    @Test
    void takenClientNameIsRejectedAndNothingIsCreated() {
        Lead l = siteLead();
        Facility existing = client(Market.KZ);
        int before = tenderRepository.findBySource(Source.PRIVATE_REQUEST).size();

        assertThatThrownBy(() -> service.convert(l.getId(), withNewClient(existing.getName()), "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже есть");

        assertThat(tenderRepository.findBySource(Source.PRIVATE_REQUEST)).hasSize(before);
        assertThat(service.get(l.getId()).getStatus()).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void secondConversionIsRejected() {
        Lead l = siteLead();
        Facility f = client(Market.KZ);
        service.convert(l.getId(), withClient(f.getId()), "admin");

        assertThatThrownBy(() -> service.convert(l.getId(), withClient(f.getId()), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void linesAreRequired() {
        Lead l = siteLead();
        LeadConvertRequest r = withClient(client(Market.KZ).getId());
        r.setLines(List.of(line("  ", null, 1)));

        assertThatThrownBy(() -> service.convert(l.getId(), r, "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("строка");
    }

    @Test
    void exactlyOneClientChoiceIsRequired() {
        Lead l = siteLead();
        LeadConvertRequest none = withClient(null);
        LeadConvertRequest both = withNewClient("ZZ Оба " + System.nanoTime());
        both.setClientFacilityId(client(Market.KZ).getId());

        assertThatThrownBy(() -> service.convert(l.getId(), none, "admin")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.convert(l.getId(), both, "admin")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void clientOfAnotherMarketIsNotFound() {
        Lead l = siteLead();
        Facility rf = client(Market.RF);

        assertThatThrownBy(() -> service.convert(l.getId(), withClient(rf.getId()), "admin"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void reopenAfterConvertAndCloseReturnsToConverted() {
        Lead l = siteLead();
        service.convert(l.getId(), withClient(client(Market.KZ).getId()), "admin");
        service.close(l.getId(), LeadCloseReason.CLIENT_DECLINED, null, "admin");

        assertThat(service.reopen(l.getId(), "admin").getStatus()).isEqualTo(LeadStatus.CONVERTED);
    }

    @Test
    void privateRequestCardLinksBackToLead() {
        Lead l = siteLead();
        LeadConvertResponse r = service.convert(l.getId(), withClient(client(Market.KZ).getId()), "admin");

        PrivateRequestResponse pr = privateRequestController.findById(r.getPrivateRequestId());

        assertThat(pr.getLead()).isNotNull();
        assertThat(pr.getLead().getId()).isEqualTo(l.getId());
        assertThat(pr.getLead().getSubject()).isEqualTo("Запрос КП");
        assertThat(pr.getLead().getSource()).isEqualTo("westmed.kz");
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.LeadConvertTest'`
Expected: FAIL — нет `LeadConvertRequest`, `LeadService.convert`, `PrivateRequestResponse.getLead`.

- [ ] **Step 3: DTO превращения**

`dto/request/LeadConvertRequest.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Ровно одно из clientFacilityId / newClient. Строки — как у частной заявки. */
@Data
public class LeadConvertRequest {
    private Long clientFacilityId;
    @Valid private NewClient newClient;
    @Size(max = 5000) private String note;
    private List<PrivateRequestCreate.Line> lines;

    @Data
    public static class NewClient {
        @Size(max = 255) private String name;
        @Size(max = 50) private String phone;
        @Size(max = 255) private String email;
        @Size(max = 100) private String lastName;
        @Size(max = 100) private String firstName;
        @Size(max = 100) private String middleName;
    }
}
```

`dto/response/LeadConvertResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeadConvertResponse {
    private Long privateRequestId;
    private String number;
}
```

- [ ] **Step 4: Поиск клиента по имени**

В `repository/FacilityRepository.java` добавить (импорт `java.util.Optional`):

```java
    /** У facility.name глобальный UNIQUE — проверяем по всем рынкам (нативный SQL фильтром не режется). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM facility WHERE name = :name)", nativeQuery = true)
    boolean existsByNameAnyMarket(@Param("name") String name);

    Optional<Facility> findByName(String name);
```

- [ ] **Step 5: `convert` в сервисе**

В `service/LeadService.java`:

1) Импорты добавить: `com.vladoose.nir.dto.request.LeadConvertRequest`, `com.vladoose.nir.dto.request.PrivateRequestCreate`, `com.vladoose.nir.dto.response.LeadConvertResponse`, `com.vladoose.nir.repository.FacilityRepository`.

2) Поля и конструктор заменить:

```java
    private final LeadRepository leadRepository;
    private final LeadIntakeService intake;
    private final FacilityRepository facilityRepository;
    private final PrivateRequestService privateRequestService;

    public LeadService(LeadRepository leadRepository, LeadIntakeService intake,
                       FacilityRepository facilityRepository, PrivateRequestService privateRequestService) {
        this.leadRepository = leadRepository;
        this.intake = intake;
        this.facilityRepository = facilityRepository;
        this.privateRequestService = privateRequestService;
    }
```

3) Метод `convert` и помощники — после метода `updateItems`:

```java
    /**
     * Частная заявка из обращения — одна транзакция (спека §9): любая ошибка откатывает всё,
     * ни клиента-сироты, ни заявки без обращения. Проверки идут ДО создания чего-либо.
     */
    @Transactional
    public LeadConvertResponse convert(Long id, LeadConvertRequest req, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), "создать частную заявку");
        boolean existing = req.getClientFacilityId() != null;
        boolean fresh = req.getNewClient() != null;
        if (existing == fresh) {
            throw new BadRequestException("Выберите клиента из списка или создайте нового");
        }
        List<PrivateRequestCreate.Line> lines = cleanLines(req.getLines());
        if (lines.isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка с наименованием");
        }
        Facility client = existing ? existingClient(req.getClientFacilityId()) : createClient(req.getNewClient());

        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(client.getId());
        dto.setNote(blankToNull(req.getNote()));
        dto.setLines(lines);
        Tender request = privateRequestService.createFromLines(dto);
        request.setContactPhone(lead.getContactPhone());
        request.setContactEmail(lead.getContactEmail());

        lead.setFacility(client);
        lead.setPrivateRequest(request);
        transition(lead, LeadStatus.CONVERTED, author, "создана частная заявка " + request.getTenderNumber());
        return new LeadConvertResponse(request.getId(), request.getTenderNumber());
    }

    private Facility existingClient(Long facilityId) {
        Facility f = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new NotFoundException("Клиент не найден: id=" + facilityId));
        if (f.getMarket() != null && f.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Клиент не найден: id=" + facilityId);
        }
        return f;
    }

    private Facility createClient(LeadConvertRequest.NewClient nc) {
        String name = nc.getName() == null ? "" : nc.getName().trim();
        if (name.isEmpty()) throw new BadRequestException("Введите название клиента");
        if (facilityRepository.existsByNameAnyMarket(name)) {
            throw new BadRequestException(facilityRepository.findByName(name).isPresent()
                    ? "Клиент «" + name + "» уже есть — выберите его в списке"
                    : "Название «" + name + "» уже занято клиентом другого рынка — уточните название");
        }
        return facilityRepository.save(Facility.builder()
                .name(trunc(name, 255))
                .phone(trunc(blankToNull(nc.getPhone()), 50))
                .email(trunc(blankToNull(nc.getEmail()), 255))
                .lastName(trunc(blankToNull(nc.getLastName()), 100))
                .firstName(trunc(blankToNull(nc.getFirstName()), 100))
                .middleName(trunc(blankToNull(nc.getMiddleName()), 100))
                .market(MarketContext.get())
                .build());
    }

    private static List<PrivateRequestCreate.Line> cleanLines(List<PrivateRequestCreate.Line> raw) {
        if (raw == null) return List.of();
        List<PrivateRequestCreate.Line> out = new ArrayList<>();
        for (PrivateRequestCreate.Line l : raw) {
            if (l == null || isBlank(l.getName())) continue;
            PrivateRequestCreate.Line c = new PrivateRequestCreate.Line();
            c.setName(l.getName().trim());
            c.setManufact(blankToNull(l.getManufact()));
            c.setQuantity(l.getQuantity() != null && l.getQuantity() > 0 ? l.getQuantity() : 1);
            out.add(c);
        }
        return out;
    }
```

- [ ] **Step 6: Эндпоинт и обратная ссылка**

В `controller/LeadController.java` — импорт `com.vladoose.nir.dto.response.LeadConvertResponse` и метод после `addEvent`:

```java
    @PostMapping("/{id}/convert")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadConvertResponse convert(@PathVariable Long id, @Valid @RequestBody LeadConvertRequest req) {
        return service.convert(id, req, currentUser());
    }
```

В `dto/response/PrivateRequestResponse.java` добавить поле:

```java
    /** Из какого обращения собрана заявка (null — заведена вручную или импортом). */
    private LeadRefResponse lead;
```

В `controller/PrivateRequestController.java`:
- импорты `com.vladoose.nir.mapper.LeadResponseMapper`, `com.vladoose.nir.service.LeadService`;
- поля `private final LeadService leadService;` и `private final LeadResponseMapper leadResponseMapper;`, два новых параметра конструктора в конец списка с присваиванием;
- в `findById` — строку `attachLead(r, t.getId());` перед `return r;`;
- в `buildFull` — строку `attachLead(r, t.getId());` перед `return r;`;
- метод:

```java
    private void attachLead(PrivateRequestResponse r, Long tenderId) {
        leadService.findByPrivateRequest(tenderId).map(leadResponseMapper::toRef).ifPresent(r::setLead);
    }
```

Проверить, что контроллер и сервис нигде не создаются вручную (иначе поправить вызовы):
Run: `cd /Users/vlad/IdeaProjects/AIS && grep -rn "new PrivateRequestController(\|new LeadService(" src/`
Expected: пусто.

- [ ] **Step 7: Запустить — зелёные, соседи не сломаны**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.*' --tests 'com.vladoose.nir.privaterequest.*'`
Expected: PASS (все тесты пакетов `lead` и `privaterequest`).

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/request/LeadConvertRequest.java \
  src/main/java/com/vladoose/nir/dto/response/LeadConvertResponse.java src/main/java/com/vladoose/nir/service/LeadService.java \
  src/main/java/com/vladoose/nir/controller/LeadController.java src/main/java/com/vladoose/nir/repository/FacilityRepository.java \
  src/main/java/com/vladoose/nir/dto/response/PrivateRequestResponse.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java \
  src/test/java/com/vladoose/nir/lead/LeadConvertTest.java
git commit -m "$(printf 'feat(leads): частная заявка из обращения одной транзакцией + обратная ссылка\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 6: HTTP-клиент сайта westmed.kz

**Files:**
- Create: `integration/westmed/WestmedClient.java`, `integration/westmed/WestmedHttpClient.java`, `integration/westmed/WestmedKind.java`
- Create: `integration/westmed/WestmedApiException.java`, `integration/westmed/WestmedAuthException.java`
- Create: `integration/westmed/dto/WestmedPage.java`, `dto/WestmedPriceRequest.java`, `dto/WestmedQuoteRequest.java`, `dto/WestmedProduct.java`
- Test: `integration/westmed/WestmedHttpClientTest.java`

**Interfaces:**
- Consumes: ничего из прошлых задач (самостоятельный слой).
- Produces:
  - `interface WestmedClient { boolean isConfigured(); WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size); WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size); List<WestmedProduct> searchProducts(String text, int size); void updateStatus(WestmedKind kind, String siteId, String status); }`
  - `enum WestmedKind { PRICE, QUOTE }`: `path()`, `externalId(String siteId)` → `"price:<id>"`/`"quote:<id>"`, `static ofExternalId(String)`, `static siteIdOf(String)`.
  - `WestmedApiException(int status, String message)` + `status()` (0 — сеть), `WestmedAuthException(String)`.
  - record-DTO: `WestmedPage<T>(List<T> content, Boolean last, Integer totalPages, Integer number)` + `contentOrEmpty()`, `isLast()`; `WestmedPriceRequest(id, name, email, phone, company, message, productName, status, createdAt)`; `WestmedQuoteRequest(id, name, email, phone, company, message, status, List<Item> items, createdAt)` + `Item(productSlug, productName, Integer quantity)`; `WestmedProduct(name, slug, brandName)`. Все поля — `String`, кроме указанных.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/integration/westmed/WestmedHttpClientTest.java`:

```java
package com.vladoose.nir.integration.westmed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Формы ответов — как у реального API сайта (westmed, коммит e87bfcb). Стаб на JDK HttpServer, без сети. */
class WestmedHttpClientTest {

    static HttpServer server;
    static int port;
    /** "METHOD path?query auth=<заголовок|none> body=<тело>" */
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final AtomicInteger logins = new AtomicInteger();
    static volatile int loginStatus;
    static volatile String validToken;     // какой токен принимают admin-вызовы
    static volatile int rejectStatus;      // чем отвечать на чужой токен (реальный сайт — 403)
    static volatile String adminBody;
    static volatile String productsBody;

    static final String PASSWORD = "S3cr3t-pass";

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + (query != null ? "?" + query : "")
                    + " auth=" + (auth == null ? "none" : auth) + " body=" + body);
            int status;
            String resp;
            if (path.equals("/api/auth/login")) {
                int n = logins.incrementAndGet();
                status = loginStatus;
                resp = status == 200 ? "{\"accessToken\":\"tok-" + n + "\",\"refreshToken\":\"r\"}" : "";
            } else if (path.startsWith("/api/admin/")) {
                if (!("Bearer " + validToken).equals(auth)) { status = rejectStatus; resp = ""; }
                else if (method.equals("PATCH")) { status = 200; resp = "{}"; }
                else { status = 200; resp = adminBody; }
            } else if (path.equals("/api/v1/products")) {
                status = 200;
                resp = productsBody;
            } else {
                status = 404;
                resp = "";
            }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            }
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void reset() {
        calls.clear();
        logins.set(0);
        loginStatus = 200;
        validToken = "tok-1";
        rejectStatus = 403;
        adminBody = "{\"content\":[],\"last\":true,\"totalPages\":0,\"number\":0}";
        productsBody = "{\"content\":[],\"last\":true,\"totalPages\":0,\"number\":0}";
    }

    private static WestmedHttpClient client(String password) {
        return new WestmedHttpClient(new ObjectMapper(), "http://localhost:" + port + "/", "ais@westmed.kz", password);
    }

    private static List<String> adminCalls() {
        return calls.stream().filter(c -> c.contains(" /api/admin/")).toList();
    }

    @Test
    void logsInOnceAndSendsBearerToAdminCalls() {
        WestmedHttpClient c = client(PASSWORD);
        c.fetchPriceRequests(0, 50);
        c.fetchQuoteRequests(0, 50);

        assertThat(logins.get()).isEqualTo(1);
        assertThat(adminCalls()).hasSize(2).allMatch(s -> s.contains("auth=Bearer tok-1"));
        assertThat(adminCalls().get(0)).startsWith("GET /api/admin/requests?page=0&size=50");
    }

    @Test
    void expiredTokenAnswered403TriggersOneReloginAndRetry() {
        validToken = "tok-2";   // первый токен сайт уже не принимает и отвечает 403 — как реальный westmed
        WestmedHttpClient c = client(PASSWORD);

        c.fetchPriceRequests(0, 50);

        assertThat(logins.get()).isEqualTo(2);
        assertThat(adminCalls()).last().asString().contains("auth=Bearer tok-2");
    }

    @Test
    void expiredTokenAnswered401AlsoTriggersRelogin() {
        validToken = "tok-2";
        rejectStatus = 401;

        client(PASSWORD).fetchQuoteRequests(0, 50);

        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    void persistentRefusalIsAuthErrorAfterExactlyOneRelogin() {
        validToken = "never";
        WestmedHttpClient c = client(PASSWORD);

        assertThatThrownBy(() -> c.fetchPriceRequests(0, 50)).isInstanceOf(WestmedAuthException.class);
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    void wrongPasswordIsAuthErrorWithoutLeakingPassword() {
        loginStatus = 401;
        assertThatThrownBy(() -> client(PASSWORD).fetchPriceRequests(0, 50))
                .isInstanceOf(WestmedAuthException.class)
                .hasMessageContaining("неверный логин")
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void rateLimitedLoginIsAuthError() {
        loginStatus = 429;
        assertThatThrownBy(() -> client(PASSWORD).fetchPriceRequests(0, 50))
                .isInstanceOf(WestmedAuthException.class).hasMessageContaining("429");
    }

    @Test
    void missingCredentialsAreReportedWithoutNetwork() {
        WestmedHttpClient c = client("");
        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.fetchPriceRequests(0, 50))
                .isInstanceOf(WestmedAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }

    @Test
    void parsesRealPageShapeWithAbsentOptionalFields() {
        // @JsonInclude(NON_NULL) на сайте: пустых полей в JSON просто НЕТ
        adminBody = """
            {"content":[
               {"id":"8a1f0c1e-0000-4000-8000-000000000001","name":"Айгерим","email":"a@clinic.kz",
                "status":"NEW","createdAt":"2026-09-20T08:15:30.123456Z"},
               {"id":"8a1f0c1e-0000-4000-8000-000000000002","name":"Иван","email":"i@x.kz","phone":"+77770752770",
                "company":"ТОО «Клиника»","message":"Нужен облучатель","productName":"Облучатель ОБН-150",
                "status":"PROCESSED","createdAt":"2026-09-19T10:00:00Z"}],
             "pageable":{"pageNumber":0,"pageSize":50},"last":true,"totalPages":1,"totalElements":2,
             "number":0,"size":50,"first":true,"numberOfElements":2,"empty":false}
            """;

        WestmedPage<WestmedPriceRequest> page = client(PASSWORD).fetchPriceRequests(0, 50);

        assertThat(page.isLast()).isTrue();
        assertThat(page.contentOrEmpty()).hasSize(2);
        WestmedPriceRequest general = page.contentOrEmpty().get(0);
        assertThat(general.phone()).isNull();
        assertThat(general.productName()).isNull();
        assertThat(general.createdAt()).isEqualTo("2026-09-20T08:15:30.123456Z");
        assertThat(page.contentOrEmpty().get(1).productName()).isEqualTo("Облучатель ОБН-150");
    }

    @Test
    void parsesQuoteItems() {
        adminBody = """
            {"content":[{"id":"q-1","name":"Айгерим","email":"a@clinic.kz","status":"NEW",
               "items":[{"productSlug":"obn-150","productName":"Облучатель ОБН-150","quantity":2}],
               "createdAt":"2026-09-20T08:15:30Z"}],"last":true,"totalPages":1,"number":0}
            """;

        WestmedQuoteRequest q = client(PASSWORD).fetchQuoteRequests(0, 50).contentOrEmpty().get(0);

        assertThat(q.items()).hasSize(1);
        assertThat(q.items().get(0).productSlug()).isEqualTo("obn-150");
        assertThat(q.items().get(0).quantity()).isEqualTo(2);
    }

    @Test
    void updateStatusSendsPatchWithJsonBody() {
        client(PASSWORD).updateStatus(WestmedKind.QUOTE, "q-1", "PROCESSED");

        assertThat(adminCalls()).singleElement().asString()
                .startsWith("PATCH /api/admin/quote-requests/q-1/status")
                .endsWith("body={\"status\":\"PROCESSED\"}");
    }

    @Test
    void searchProductsEncodesCyrillicAndSendsNoToken() {
        productsBody = """
            {"content":[{"id":"p1","name":"Облучатель «Азов»","slug":"obluchatel-azov","brandName":"Азов"}],
             "last":true,"totalPages":1,"number":0}
            """;

        List<WestmedProduct> found = client(PASSWORD).searchProducts("Облучатель «Азов»", 20);

        assertThat(found).singleElement().satisfies(p -> {
            assertThat(p.slug()).isEqualTo("obluchatel-azov");
            assertThat(p.brandName()).isEqualTo("Азов");
        });
        String call = calls.get(0);
        assertThat(call).contains("auth=none");
        String query = call.substring(call.indexOf('?') + 1, call.indexOf(" auth="));
        assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo("search=Облучатель «Азов»&size=20");
        assertThat(logins.get()).isZero();
    }

    @Test
    void siteDownIsApiErrorWithStatusZero() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) { freePort = s.getLocalPort(); }
        WestmedHttpClient c = new WestmedHttpClient(new ObjectMapper(), "http://localhost:" + freePort, "u", "p");

        assertThatThrownBy(() -> c.searchProducts("x", 5))
                .isInstanceOfSatisfying(WestmedApiException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).startsWith("сайт недоступен");
                });
    }

    @Test
    void kindRoundTripsExternalIds() {
        assertThat(WestmedKind.PRICE.externalId("u1")).isEqualTo("price:u1");
        assertThat(WestmedKind.ofExternalId("quote:q-9")).isEqualTo(WestmedKind.QUOTE);
        assertThat(WestmedKind.siteIdOf("quote:q-9")).isEqualTo("q-9");
        assertThatThrownBy(() -> WestmedKind.ofExternalId("other:1")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedHttpClientTest'`
Expected: FAIL — нет пакета `integration.westmed`.

- [ ] **Step 3: DTO сайта**

`integration/westmed/dto/WestmedPage.java`:
```java
package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Spring Page сайта (сериализуется «как есть»: content, last, totalPages, number, …). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedPage<T>(List<T> content, Boolean last, Integer totalPages, Integer number) {

    public List<T> contentOrEmpty() {
        return content == null ? List.of() : content;
    }

    /** Флаг last, иначе по номеру и числу страниц; данных нет — считаем последней (не зациклиться). */
    public boolean isLast() {
        if (last != null) return last;
        if (number != null && totalPages != null) return number >= totalPages - 1;
        return true;
    }
}
```

`integration/westmed/dto/WestmedPriceRequest.java`:
```java
package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** «Запрос цены» сайта: productName есть — по товару, нет — общая заявка текстом. Пустые поля сайт не присылает. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedPriceRequest(String id, String name, String email, String phone, String company,
                                  String message, String productName, String status, String createdAt) {}
```

`integration/westmed/dto/WestmedQuoteRequest.java`:
```java
package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** «Запрос КП» сайта — корзина товаров с количествами. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedQuoteRequest(String id, String name, String email, String phone, String company,
                                  String message, String status, List<Item> items, String createdAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String productSlug, String productName, Integer quantity) {}
}
```

`integration/westmed/dto/WestmedProduct.java`:
```java
package com.vladoose.nir.integration.westmed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Элемент публичного каталога сайта — нужен ради бренда и slug для ссылки. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WestmedProduct(String name, String slug, String brandName) {}
```

- [ ] **Step 4: Контракт, вид заявки, исключения**

`integration/westmed/WestmedClient.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;

import java.util.List;

/** API сайта westmed.kz — те же вызовы, что у его админки (спека §2). Интерфейс — ради фейка в тестах. */
public interface WestmedClient {

    boolean isConfigured();

    WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size);

    WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size);

    /** Публичный поиск каталога (name ILIKE). Карточку товара /products/{slug} НЕ вызываем — она накручивает просмотры. */
    List<WestmedProduct> searchProducts(String text, int size);

    void updateStatus(WestmedKind kind, String siteId, String status);
}
```

`integration/westmed/WestmedKind.java`:
```java
package com.vladoose.nir.integration.westmed;

/** Вид заявки сайта: по префиксу внешнего id понятно, в какой эндпоинт писать статус. */
public enum WestmedKind {
    PRICE("price", "requests"),
    QUOTE("quote", "quote-requests");

    private final String prefix;
    private final String path;

    WestmedKind(String prefix, String path) {
        this.prefix = prefix;
        this.path = path;
    }

    public String path() { return path; }

    public String externalId(String siteId) { return prefix + ":" + siteId; }

    public static WestmedKind ofExternalId(String externalId) {
        for (WestmedKind k : values()) {
            if (externalId != null && externalId.startsWith(k.prefix + ":")) return k;
        }
        throw new IllegalArgumentException("Неизвестный внешний id westmed: " + externalId);
    }

    public static String siteIdOf(String externalId) {
        return externalId.substring(externalId.indexOf(':') + 1);
    }
}
```

`integration/westmed/WestmedApiException.java`:
```java
package com.vladoose.nir.integration.westmed;

/** Ошибка вызова сайта; status 0 — до ответа не дошло (сеть, таймаут). */
public class WestmedApiException extends RuntimeException {

    private final int status;

    public WestmedApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
```

`integration/westmed/WestmedAuthException.java`:
```java
package com.vladoose.nir.integration.westmed;

/** Сайт не пускает учётку АИС (нет данных, неверный пароль, 429, отказ после повторного входа). */
public class WestmedAuthException extends RuntimeException {

    public WestmedAuthException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: HTTP-клиент**

`integration/westmed/WestmedHttpClient.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP к API сайта westmed.kz. Токен (живёт 30 мин) держим в памяти. На отказ admin-вызова —
 * ⚠️ и 401, и 403: протухший токен сайт отвечает 403 (у него нет entry point, Spring отдаёт анониму 403),
 * реагируй мы только на 401 — через полчаса приём заявок молча встал бы. Повторный вход — один.
 * Пароль и токен не попадают ни в логи, ни в тексты исключений.
 */
@Component
public class WestmedHttpClient implements WestmedClient {

    private static final TypeReference<WestmedPage<WestmedPriceRequest>> PRICE_PAGE = new TypeReference<>() {};
    private static final TypeReference<WestmedPage<WestmedQuoteRequest>> QUOTE_PAGE = new TypeReference<>() {};
    private static final TypeReference<WestmedPage<WestmedProduct>> PRODUCT_PAGE = new TypeReference<>() {};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String username;
    private final String password;
    private volatile String token;

    public WestmedHttpClient(ObjectMapper objectMapper,
                             @Value("${leads.westmed.base-url:https://westmed.kz}") String baseUrl,
                             @Value("${leads.westmed.username:}") String username,
                             @Value("${leads.westmed.password:}") String password) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size) {
        return admin("GET", "/api/admin/requests?page=" + page + "&size=" + size, null, PRICE_PAGE);
    }

    @Override
    public WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size) {
        return admin("GET", "/api/admin/quote-requests?page=" + page + "&size=" + size, null, QUOTE_PAGE);
    }

    @Override
    public List<WestmedProduct> searchProducts(String text, int size) {
        String path = "/api/v1/products?search=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&size=" + size;
        HttpResponse<String> r = send("GET", path, null, null);
        if (r.statusCode() != 200) {
            throw new WestmedApiException(r.statusCode(), "поиск товара на сайте: HTTP " + r.statusCode());
        }
        return parse(r.body(), PRODUCT_PAGE).contentOrEmpty();
    }

    @Override
    public void updateStatus(WestmedKind kind, String siteId, String status) {
        admin("PATCH", "/api/admin/" + kind.path() + "/" + siteId + "/status", json(Map.of("status", status)), null);
    }

    private <T> T admin(String method, String path, String body, TypeReference<T> type) {
        HttpResponse<String> r = send(method, path, body, currentToken());
        if (isTokenRejected(r)) {
            token = null;   // протух или отозван — входим заново ровно один раз
            r = send(method, path, body, currentToken());
            if (isTokenRejected(r)) {
                throw new WestmedAuthException(
                        "сайт отклоняет учётку АИС даже после повторного входа (HTTP " + r.statusCode() + ")");
            }
        }
        if (r.statusCode() / 100 != 2) {
            throw new WestmedApiException(r.statusCode(), "HTTP " + r.statusCode() + " на " + method + " " + stripQuery(path));
        }
        return type == null ? null : parse(r.body(), type);
    }

    private static boolean isTokenRejected(HttpResponse<String> r) {
        return r.statusCode() == 401 || r.statusCode() == 403;
    }

    private synchronized String currentToken() {
        if (token != null) return token;
        if (!isConfigured()) throw new WestmedAuthException("не заданы учётные данные сайта");
        HttpResponse<String> r = send("POST", "/api/auth/login", json(Map.of("email", username, "password", password)), null);
        if (r.statusCode() == 401) throw new WestmedAuthException("вход не удался: неверный логин или пароль");
        if (r.statusCode() == 429) {
            throw new WestmedAuthException("вход не удался: сайт временно ограничил попытки входа (HTTP 429)");
        }
        if (r.statusCode() != 200) throw new WestmedApiException(r.statusCode(), "вход на сайт: HTTP " + r.statusCode());
        String t;
        try {
            t = objectMapper.readTree(r.body()).path("accessToken").asText("");
        } catch (IOException e) {
            throw new WestmedApiException(r.statusCode(), "вход на сайт: ответ не разобран");
        }
        if (t.isBlank()) throw new WestmedAuthException("вход на сайт: токен не получен");
        token = t;
        return t;
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WestmedApiException(0, "сайт недоступен: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " — " + e.getMessage() : ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WestmedApiException(0, "запрос к сайту прерван");
        }
    }

    private <T> T parse(String body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new WestmedApiException(200, "ответ сайта не разобран: " + e.getClass().getSimpleName());
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось собрать JSON запроса", e);
        }
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
```

- [ ] **Step 6: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedHttpClientTest'`
Expected: PASS, 13 тестов.

- [ ] **Step 7: Мутация — ловушка 403**

Временно заменить тело `isTokenRejected` на `return r.statusCode() == 401;`.
Run: `./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedHttpClientTest.expiredTokenAnswered403TriggersOneReloginAndRetry'`
Expected: FAIL (без повторного входа сайт отвечает 403 → `WestmedApiException`). Вернуть `401 || 403`, перезапустить — PASS.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/westmed/ \
  src/test/java/com/vladoose/nir/integration/westmed/WestmedHttpClientTest.java
git commit -m "$(printf 'feat(westmed): HTTP-клиент API сайта — вход, повторный вход на 401/403, статусы, каталог\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 7: Заявка сайта → `IncomingLead` + бренд из каталога

**Files:**
- Create: `integration/westmed/WestmedProductLookup.java`, `integration/westmed/WestmedLeadMapper.java`
- Create (тестовый фейк): `src/test/java/com/vladoose/nir/integration/westmed/FakeWestmedClient.java`
- Test: `integration/westmed/WestmedLeadMapperTest.java`

**Interfaces:**
- Consumes: `WestmedClient`, `WestmedKind`, DTO сайта (Task 6); `IncomingLead`, `LeadSources` (Task 2).
- Produces:
  - `WestmedProductLookup(WestmedClient)` — кеш на один цикл: `byName(String): Optional<WestmedProduct>`, `bySlug(String slug, String nameHint): Optional<WestmedProduct>`; ошибки поиска глотает (позиция просто останется без бренда).
  - `WestmedLeadMapper(String siteUrl)` (`@Value("${leads.westmed.site-url:https://westmed.kz}")`): `fromPriceRequest(WestmedPriceRequest, WestmedProductLookup): IncomingLead`, `fromQuoteRequest(WestmedQuoteRequest, WestmedProductLookup): IncomingLead`, статические `initialStatus(String): LeadStatus`, `parseTime(String): OffsetDateTime`.
  - `FakeWestmedClient` (тесты Tasks 7–9): поля `configured`, `priceRequests`, `quoteRequests`, `productsBySearch` (ключ — текст поиска в нижнем регистре), `pricePages`, `quotePages`, `statusUpdates` («PRICE:<id>=PROCESSED»), `missingOnSite`, `failFetchWith`, `failUpdatesWith`, `failSearchWith`, `searchCalls`.

- [ ] **Step 1: Тестовый фейк сайта**

`src/test/java/com/vladoose/nir/integration/westmed/FakeWestmedClient.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;

import java.util.*;

/** Управляемый фейк API сайта: заявки (новые сверху, как отдаёт сайт), каталог, запись статусов. Без сети. */
public class FakeWestmedClient implements WestmedClient {

    public boolean configured = true;
    public final List<WestmedPriceRequest> priceRequests = new ArrayList<>();
    public final List<WestmedQuoteRequest> quoteRequests = new ArrayList<>();
    /** ключ — текст поиска в нижнем регистре */
    public final Map<String, List<WestmedProduct>> productsBySearch = new HashMap<>();
    public final List<Integer> pricePages = new ArrayList<>();
    public final List<Integer> quotePages = new ArrayList<>();
    public final List<String> statusUpdates = new ArrayList<>();
    public final Set<String> missingOnSite = new HashSet<>();
    public RuntimeException failFetchWith;
    public RuntimeException failUpdatesWith;
    public RuntimeException failSearchWith;
    public int searchCalls;

    @Override
    public boolean isConfigured() { return configured; }

    @Override
    public WestmedPage<WestmedPriceRequest> fetchPriceRequests(int page, int size) {
        if (failFetchWith != null) throw failFetchWith;
        pricePages.add(page);
        return page(priceRequests, page, size);
    }

    @Override
    public WestmedPage<WestmedQuoteRequest> fetchQuoteRequests(int page, int size) {
        if (failFetchWith != null) throw failFetchWith;
        quotePages.add(page);
        return page(quoteRequests, page, size);
    }

    @Override
    public List<WestmedProduct> searchProducts(String text, int size) {
        searchCalls++;
        if (failSearchWith != null) throw failSearchWith;
        return productsBySearch.getOrDefault(text.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public void updateStatus(WestmedKind kind, String siteId, String status) {
        if (failUpdatesWith != null) throw failUpdatesWith;
        if (missingOnSite.contains(siteId)) {
            throw new WestmedApiException(404, "HTTP 404 на PATCH /api/admin/" + kind.path() + "/" + siteId + "/status");
        }
        statusUpdates.add(kind + ":" + siteId + "=" + status);
    }

    static <T> WestmedPage<T> page(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = (all.size() + size - 1) / size;
        return new WestmedPage<>(new ArrayList<>(all.subList(from, to)), page >= totalPages - 1, totalPages, page);
    }
}
```

- [ ] **Step 2: Написать падающий тест маппера**

`src/test/java/com/vladoose/nir/integration/westmed/WestmedLeadMapperTest.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WestmedLeadMapperTest {

    FakeWestmedClient fake;
    WestmedProductLookup lookup;
    final WestmedLeadMapper mapper = new WestmedLeadMapper("https://westmed.kz/");

    @BeforeEach
    void setUp() {
        fake = new FakeWestmedClient();
        lookup = new WestmedProductLookup(fake);
    }

    private static WestmedPriceRequest price(String productName, String status) {
        return new WestmedPriceRequest("u1", "Айгерим", "a@clinic.kz", "+77770000000", null,
                "Сколько стоит?", productName, status, "2026-09-20T08:15:30.123456Z");
    }

    @Test
    void priceRequestForProductGetsBrandAndLinkFromCatalog() {
        fake.productsBySearch.put("стерилизатор озоновый «орион»", List.of(
                new WestmedProduct("Стерилизатор озоновый «Орион» без камеры", "orion-bk", "Орион"),
                new WestmedProduct("Стерилизатор озоновый «Орион»", "orion", "Орион")));

        IncomingLead in = mapper.fromPriceRequest(price("Стерилизатор озоновый «Орион»", "NEW"), lookup);

        assertThat(in.source()).isEqualTo("westmed.kz");
        assertThat(in.externalId()).isEqualTo("price:u1");
        assertThat(in.channel()).isEqualTo(LeadChannel.SITE);
        assertThat(in.subject()).isEqualTo("Запрос цены");
        assertThat(in.items()).containsExactly(new IncomingLead.Item(
                "Стерилизатор озоновый «Орион»", "Орион", 1, "https://westmed.kz/product/orion"));
        assertThat(in.initialStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(in.extStatus()).isEqualTo("NEW");
        assertThat(in.receivedAt()).isEqualTo(OffsetDateTime.parse("2026-09-20T08:15:30.123456Z"));
        assertThat(in.author()).isNull();
    }

    @Test
    void generalRequestWithoutProductHasNoItems() {
        IncomingLead in = mapper.fromPriceRequest(price(null, "NEW"), lookup);

        assertThat(in.subject()).isEqualTo("Заявка с сайта");
        assertThat(in.items()).isEmpty();
        assertThat(in.message()).isEqualTo("Сколько стоит?");
        assertThat(fake.searchCalls).isZero();
    }

    @Test
    void quoteItemsGetBrandBySlugAndLinkEvenWhenGoneFromCatalog() {
        fake.productsBySearch.put("облучатель обн-150", List.of(new WestmedProduct("Облучатель ОБН-150", "obn-150", "Азов")));
        WestmedQuoteRequest q = new WestmedQuoteRequest("q1", "Иван", "i@x.kz", null, "ТОО «Клиника»", null, "NEW",
                List.of(new WestmedQuoteRequest.Item("obn-150", "Облучатель ОБН-150", 3),
                        new WestmedQuoteRequest.Item("gone", "Снятый с сайта товар", null)),
                "2026-09-20T08:15:30Z");

        IncomingLead in = mapper.fromQuoteRequest(q, lookup);

        assertThat(in.externalId()).isEqualTo("quote:q1");
        assertThat(in.subject()).isEqualTo("Запрос КП");
        assertThat(in.items()).containsExactly(
                new IncomingLead.Item("Облучатель ОБН-150", "Азов", 3, "https://westmed.kz/product/obn-150"),
                new IncomingLead.Item("Снятый с сайта товар", null, 1, "https://westmed.kz/product/gone"));
    }

    @Test
    void siteStatusMapsToInitialStatus() {
        assertThat(WestmedLeadMapper.initialStatus("NEW")).isEqualTo(LeadStatus.NEW);
        assertThat(WestmedLeadMapper.initialStatus("PROCESSED")).isEqualTo(LeadStatus.IN_WORK);
        assertThat(WestmedLeadMapper.initialStatus("CLOSED")).isEqualTo(LeadStatus.CLOSED);
        assertThat(WestmedLeadMapper.initialStatus(null)).isEqualTo(LeadStatus.NEW);
        assertThat(WestmedLeadMapper.initialStatus("WHATEVER")).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void catalogIsSearchedOncePerNamePerCycle() {
        mapper.fromPriceRequest(price("Облучатель ОБН-150", "NEW"), lookup);
        mapper.fromPriceRequest(price("облучатель  обн-150 ", "NEW"), lookup);

        assertThat(fake.searchCalls).isEqualTo(1);
    }

    @Test
    void catalogFailureLeavesItemWithoutBrand() {
        fake.failSearchWith = new WestmedApiException(0, "сайт недоступен: ConnectException");

        IncomingLead in = mapper.fromPriceRequest(price("Облучатель ОБН-150", "NEW"), lookup);

        assertThat(in.items()).containsExactly(new IncomingLead.Item("Облучатель ОБН-150", null, 1, null));
    }

    @Test
    void brokenTimestampFallsBackToNull() {
        assertThat(WestmedLeadMapper.parseTime("вчера")).isNull();
        assertThat(WestmedLeadMapper.parseTime(null)).isNull();
    }
}
```

- [ ] **Step 3: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadMapperTest'`
Expected: FAIL — нет `WestmedLeadMapper`, `WestmedProductLookup`.

- [ ] **Step 4: Поиск по каталогу с кешем**

`integration/westmed/WestmedProductLookup.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Бренд и slug товара по публичному поиску каталога. Кеш — на ОДИН цикл синхронизации
 * (новый экземпляр на цикл): одно имя — один запрос. Ошибка поиска не валит приём заявки.
 */
public class WestmedProductLookup {

    private static final Logger log = LoggerFactory.getLogger(WestmedProductLookup.class);
    private static final int SEARCH_SIZE = 20;

    private final WestmedClient client;
    private final Map<String, List<WestmedProduct>> cache = new HashMap<>();

    public WestmedProductLookup(WestmedClient client) {
        this.client = client;
    }

    public Optional<WestmedProduct> byName(String name) {
        String n = norm(name);
        return search(name).stream().filter(p -> norm(p.name()).equals(n)).findFirst();
    }

    public Optional<WestmedProduct> bySlug(String slug, String nameHint) {
        if (slug == null || slug.isBlank()) return byName(nameHint);
        return search(nameHint).stream().filter(p -> slug.equals(p.slug())).findFirst();
    }

    private List<WestmedProduct> search(String text) {
        if (text == null || text.isBlank()) return List.of();
        return cache.computeIfAbsent(norm(text), key -> {
            try {
                return client.searchProducts(key, SEARCH_SIZE);
            } catch (RuntimeException e) {
                log.debug("westmed.kz: поиск товара «{}» не удался: {}", key, e.getMessage());
                return List.of();
            }
        });
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
```

(Поиск на сайте — `ILIKE` без учёта регистра, поэтому в него уходит нормализованный текст в нижнем регистре; фейк держит ключи в нижнем регистре.)

- [ ] **Step 5: Маппер**

`integration/westmed/WestmedLeadMapper.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Заявка сайта → IncomingLead (спека §6.2): тема, позиции с брендом из каталога, статус из истории. */
@Component
public class WestmedLeadMapper {

    private final String siteUrl;

    public WestmedLeadMapper(@Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public IncomingLead fromPriceRequest(WestmedPriceRequest r, WestmedProductLookup lookup) {
        List<IncomingLead.Item> items = new ArrayList<>();
        String subject;
        if (r.productName() != null && !r.productName().isBlank()) {
            subject = "Запрос цены";
            Optional<WestmedProduct> p = lookup.byName(r.productName());
            items.add(new IncomingLead.Item(r.productName().trim(),
                    p.map(WestmedProduct::brandName).orElse(null), 1,
                    p.map(x -> productUrl(x.slug())).orElse(null)));
        } else {
            subject = "Заявка с сайта";   // «Оставить заявку» без товара — суть в тексте клиента
        }
        return build(WestmedKind.PRICE.externalId(r.id()), subject, r.name(), r.phone(), r.email(),
                r.company(), r.message(), items, r.status(), r.createdAt());
    }

    public IncomingLead fromQuoteRequest(WestmedQuoteRequest r, WestmedProductLookup lookup) {
        List<IncomingLead.Item> items = new ArrayList<>();
        for (WestmedQuoteRequest.Item i : r.items() == null ? List.<WestmedQuoteRequest.Item>of() : r.items()) {
            if (i.productName() == null || i.productName().isBlank()) continue;
            Optional<WestmedProduct> p = lookup.bySlug(i.productSlug(), i.productName());
            items.add(new IncomingLead.Item(i.productName().trim(),
                    p.map(WestmedProduct::brandName).orElse(null),
                    i.quantity() != null && i.quantity() > 0 ? i.quantity() : 1,
                    i.productSlug() != null && !i.productSlug().isBlank() ? productUrl(i.productSlug()) : null));
        }
        return build(WestmedKind.QUOTE.externalId(r.id()), "Запрос КП", r.name(), r.phone(), r.email(),
                r.company(), r.message(), items, r.status(), r.createdAt());
    }

    private IncomingLead build(String externalId, String subject, String name, String phone, String email,
                               String company, String message, List<IncomingLead.Item> items,
                               String siteStatus, String createdAt) {
        return new IncomingLead(LeadSources.WESTMED, externalId, LeadChannel.SITE, subject, parseTime(createdAt),
                name, phone, email, company, message, items, initialStatus(siteStatus), siteStatus, null);
    }

    /** Статус сайта при ПЕРВОМ появлении заявки в АИС (дальше мастер статусов — АИС). */
    static LeadStatus initialStatus(String siteStatus) {
        if (siteStatus == null) return LeadStatus.NEW;
        return switch (siteStatus) {
            case "PROCESSED" -> LeadStatus.IN_WORK;
            case "CLOSED" -> LeadStatus.CLOSED;
            default -> LeadStatus.NEW;
        };
    }

    static OffsetDateTime parseTime(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String productUrl(String slug) {
        return siteUrl + "/product/" + slug;   // next-intl localePrefix "as-needed": ru — без префикса
    }
}
```

- [ ] **Step 6: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadMapperTest'`
Expected: PASS, 7 тестов.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/westmed/WestmedProductLookup.java \
  src/main/java/com/vladoose/nir/integration/westmed/WestmedLeadMapper.java \
  src/test/java/com/vladoose/nir/integration/westmed/FakeWestmedClient.java \
  src/test/java/com/vladoose/nir/integration/westmed/WestmedLeadMapperTest.java
git commit -m "$(printf 'feat(westmed): заявка сайта → обращение, бренд и ссылка из каталога\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 8: Цикл синхронизации — забрать новое, записать статусы

**Files:**
- Create: `service/LeadStatusPushWriter.java`
- Create: `integration/westmed/WestmedSyncResult.java`, `integration/westmed/WestmedLeadSync.java`
- Test: `integration/westmed/WestmedLeadSyncTest.java`

**Interfaces:**
- Consumes: `WestmedClient`, `WestmedKind`, `WestmedApiException`, `WestmedAuthException` (Task 6); `WestmedLeadMapper`, `WestmedProductLookup`, `FakeWestmedClient` (Task 7); `LeadIntakeService.isKnown/ingest` (Task 2); `LeadRepository.findBySourceAndExtStatusPendingIsNotNull/findBySourceAndExternalId` (Task 1); `LeadService.take` (Task 3, в тестах).
- Produces:
  - `LeadStatusPushWriter`: `record PendingPush(Long leadId, String externalId, String status)`, `findPending(String source): List<PendingPush>`, `markPushed(Long leadId, String status)`, `markFailed(Long leadId, String error)`, `markGone(Long leadId)` — каждый в своей транзакции.
  - `WestmedSyncResult` — счётчики `created`, `pushed`, `errors`, строка `lastError`.
  - `WestmedLeadSync(WestmedClient, WestmedLeadMapper, LeadIntakeService, LeadStatusPushWriter, int pageSize, boolean writeStatus)` (`@Value` на двух последних): `runOnce(): WestmedSyncResult` — рынок ставит вызывающий.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/integration/westmed/WestmedLeadSyncTest.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.LeadStatusPushWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class WestmedLeadSyncTest {

    @Autowired LeadIntakeService intake;
    @Autowired LeadStatusPushWriter pushWriter;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadService leadService;

    FakeWestmedClient fake;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeWestmedClient();
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private WestmedLeadSync sync(int pageSize, boolean writeStatus) {
        return new WestmedLeadSync(fake, new WestmedLeadMapper("https://westmed.kz"), intake, pushWriter, pageSize, writeStatus);
    }

    private static WestmedPriceRequest price(String status) {
        String id = "zz-" + UUID.randomUUID();
        return new WestmedPriceRequest(id, "Клиент " + id, id + "@zz.kz", null, null, "Нужен аппарат", null,
                status, "2026-09-20T08:15:30Z");
    }

    private Lead lead(WestmedKind kind, String siteId) {
        return leadRepository.findBySourceAndExternalId(LeadSources.WESTMED, kind.externalId(siteId)).orElseThrow();
    }

    /** Импортировать NEW-заявку и взять её в работу → у обращения появится pending «PROCESSED». */
    private WestmedPriceRequest importedAndTaken(WestmedLeadSync s) {
        WestmedPriceRequest r = price("NEW");
        fake.priceRequests.add(0, r);
        s.runOnce();
        leadService.take(lead(WestmedKind.PRICE, r.id()).getId(), "admin");
        return r;
    }

    @Test
    void firstRunWalksAllPagesLaterRunsStopAtFirstPageWithoutNews() {
        for (int i = 0; i < 5; i++) fake.priceRequests.add(price("NEW"));
        WestmedLeadSync s = sync(2, false);

        assertThat(s.runOnce().created).isEqualTo(5);
        assertThat(fake.pricePages).containsExactly(0, 1, 2);

        fake.pricePages.clear();
        fake.priceRequests.add(0, price("NEW"));             // сайт отдаёт новые сверху
        assertThat(s.runOnce().created).isEqualTo(1);
        assertThat(fake.pricePages).containsExactly(0, 1);   // на стр. 1 новых нет → стоп

        fake.pricePages.clear();
        assertThat(s.runOnce().created).isZero();
        assertThat(fake.pricePages).containsExactly(0);
    }

    @Test
    void quoteRequestsBecomeKzLeadsWithItems() {
        String id = "zz-" + UUID.randomUUID();
        fake.quoteRequests.add(new WestmedQuoteRequest(id, "Айгерим", id + "@zz.kz", null, "ТОО «ZZ»", null, "NEW",
                List.of(new WestmedQuoteRequest.Item("obn-150", "Облучатель ОБН-150", 2)), "2026-09-20T08:15:30Z"));

        sync(50, false).runOnce();

        Lead l = lead(WestmedKind.QUOTE, id);
        assertThat(l.getSubject()).isEqualTo("Запрос КП");
        assertThat(l.getMarket()).isEqualTo(Market.KZ);
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getQuantity, LeadItem::getProductUrl)
                .containsExactly(tuple("Облучатель ОБН-150", 2, "https://westmed.kz/product/obn-150"));
    }

    @Test
    void historyKeepsSiteStatusesWithoutQueueingWrites() {
        WestmedPriceRequest done = price("CLOSED");
        WestmedPriceRequest taken = price("PROCESSED");
        fake.priceRequests.add(done);
        fake.priceRequests.add(taken);

        sync(50, true).runOnce();

        assertThat(lead(WestmedKind.PRICE, done.id()).getStatus()).isEqualTo(LeadStatus.CLOSED);
        Lead t = lead(WestmedKind.PRICE, taken.id());
        assertThat(t.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(t.getExtStatus()).isEqualTo("PROCESSED");
        assertThat(t.getExtStatusPending()).isNull();
        assertThat(fake.statusUpdates).noneMatch(u -> u.contains(done.id()) || u.contains(taken.id()));
    }

    @Test
    void takenLeadIsPushedToSiteAndPendingCleared() {
        WestmedLeadSync s = sync(50, true);
        WestmedPriceRequest r = importedAndTaken(s);

        s.runOnce();

        assertThat(fake.statusUpdates).contains("PRICE:" + r.id() + "=PROCESSED");
        Lead l = lead(WestmedKind.PRICE, r.id());
        assertThat(l.getExtStatus()).isEqualTo("PROCESSED");
        assertThat(l.getExtStatusPending()).isNull();
        assertThat(l.getExtSyncError()).isNull();
        assertThat(l.getEvents()).anyMatch(e -> e.getType() == LeadEventType.SYNC && e.getBody().contains("В работе"));
    }

    @Test
    void failedPushKeepsPendingAndRecordsError() {
        WestmedLeadSync s = sync(50, true);
        WestmedPriceRequest r = importedAndTaken(s);
        fake.failUpdatesWith = new WestmedApiException(503, "HTTP 503 на PATCH /api/admin/requests/x/status");

        WestmedSyncResult res = s.runOnce();

        assertThat(res.errors).isGreaterThanOrEqualTo(1);
        Lead l = lead(WestmedKind.PRICE, r.id());
        assertThat(l.getExtStatusPending()).isEqualTo("PROCESSED");   // повторим на следующем цикле
        assertThat(l.getExtSyncError()).contains("503");
    }

    @Test
    void leadDeletedOnSiteStopsRetrying() {
        WestmedLeadSync s = sync(50, true);
        WestmedPriceRequest r = importedAndTaken(s);
        fake.missingOnSite.add(r.id());

        s.runOnce();

        Lead l = lead(WestmedKind.PRICE, r.id());
        assertThat(l.getExtStatusPending()).isNull();
        assertThat(l.getExtSyncError()).contains("больше нет");
    }

    @Test
    void writeStatusOffNeverCallsSite() {
        WestmedLeadSync s = sync(50, false);
        WestmedPriceRequest r = importedAndTaken(s);

        s.runOnce();

        assertThat(fake.statusUpdates).noneMatch(u -> u.contains(r.id()));
        assertThat(lead(WestmedKind.PRICE, r.id()).getExtStatusPending()).isEqualTo("PROCESSED");
    }
}
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadSyncTest'`
Expected: FAIL — нет `WestmedLeadSync`, `LeadStatusPushWriter`, `WestmedSyncResult`.

- [ ] **Step 3: Писатель результата отправки статуса**

`service/LeadStatusPushWriter.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadEventType;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Транзакционные шаги записи статуса на сайт (§6 CLAUDE.md): прочитать ожидающие — в транзакции,
 * сходить на сайт — ВНЕ её (это делает WestmedLeadSync), записать исход — отдельной транзакцией.
 */
@Service
public class LeadStatusPushWriter {

    public record PendingPush(Long leadId, String externalId, String status) {}

    static final String GONE = "Заявки на сайте больше нет — статус не записан";

    private final LeadRepository leadRepository;

    public LeadStatusPushWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(readOnly = true)
    public List<PendingPush> findPending(String source) {
        return leadRepository.findBySourceAndExtStatusPendingIsNotNull(source).stream()
                .map(l -> new PendingPush(l.getId(), l.getExternalId(), l.getExtStatusPending()))
                .toList();
    }

    @Transactional
    public void markPushed(Long leadId, String status) {
        leadRepository.findById(leadId).ifPresent(l -> {
            l.setExtStatus(status);
            // пока шёл PATCH, оператор мог сменить статус ещё раз — тогда новое ожидание остаётся
            if (status.equals(l.getExtStatusPending())) l.setExtStatusPending(null);
            l.setExtSyncError(null);
            l.addEvent(LeadEventType.SYNC, null, "Статус на сайте: «" + LeadSources.siteStatusLabel(status) + "»")
                    .setChannel(LeadChannel.SITE);
        });
    }

    /** Ошибку — только в поле (баннер карточки); в ленту НЕ пишем, иначе каждые 90 с новая строка. */
    @Transactional
    public void markFailed(Long leadId, String error) {
        leadRepository.findById(leadId).ifPresent(l -> l.setExtSyncError(trunc(error, 500)));
    }

    @Transactional
    public void markGone(Long leadId) {
        leadRepository.findById(leadId).ifPresent(l -> {
            l.setExtStatusPending(null);
            l.setExtSyncError(GONE);
            l.addEvent(LeadEventType.SYNC, null, GONE).setChannel(LeadChannel.SITE);
        });
    }
}
```

- [ ] **Step 4: Результат цикла и сам цикл**

`integration/westmed/WestmedSyncResult.java`:
```java
package com.vladoose.nir.integration.westmed;

/** Итог одного цикла синхронизации с сайтом. */
public class WestmedSyncResult {
    public int created;
    public int pushed;
    public int errors;
    public String lastError;
}
```

`integration/westmed/WestmedLeadSync.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPage;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadStatusPushWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Один цикл синхронизации с westmed.kz (спека §6.2–6.3): забрать новые заявки, затем записать
 * ожидающие статусы. Сам НЕ транзакционный — сеть вне транзакций; БД — через бины intake/pushWriter.
 * MarketContext ставит вызывающий (WestmedLeadScheduler).
 */
@Service
public class WestmedLeadSync {

    private static final Logger log = LoggerFactory.getLogger(WestmedLeadSync.class);

    /** Предохранитель от бесконечного листания при кривом ответе сайта. */
    static final int MAX_PAGES = 200;

    private final WestmedClient client;
    private final WestmedLeadMapper mapper;
    private final LeadIntakeService intake;
    private final LeadStatusPushWriter pushWriter;
    private final int pageSize;
    private final boolean writeStatus;

    public WestmedLeadSync(WestmedClient client, WestmedLeadMapper mapper, LeadIntakeService intake,
                           LeadStatusPushWriter pushWriter,
                           @Value("${leads.westmed.page-size:50}") int pageSize,
                           @Value("${leads.westmed.write-status:false}") boolean writeStatus) {
        this.client = client;
        this.mapper = mapper;
        this.intake = intake;
        this.pushWriter = pushWriter;
        this.pageSize = pageSize;
        this.writeStatus = writeStatus;
    }

    public WestmedSyncResult runOnce() {
        WestmedSyncResult res = new WestmedSyncResult();
        WestmedProductLookup lookup = new WestmedProductLookup(client);   // кеш каталога — на этот цикл
        pull(res, page -> client.fetchPriceRequests(page, pageSize), WestmedPriceRequest::id,
                WestmedKind.PRICE, r -> mapper.fromPriceRequest(r, lookup));
        pull(res, page -> client.fetchQuoteRequests(page, pageSize), WestmedQuoteRequest::id,
                WestmedKind.QUOTE, r -> mapper.fromQuoteRequest(r, lookup));
        if (writeStatus) push(res);
        return res;
    }

    /**
     * Листаем от новых к старым; страница без единой новой заявки — стоп. Одно правило даёт и импорт
     * всей истории на первом запуске, и дешёвый инкремент потом. Известные заявки НЕ маппим — иначе
     * каждые 90 с ходили бы в каталог за брендами уже принятых позиций.
     */
    private <T> void pull(WestmedSyncResult res, IntFunction<WestmedPage<T>> fetch, Function<T, String> siteIdOf,
                          WestmedKind kind, Function<T, IncomingLead> toLead) {
        for (int page = 0; page < MAX_PAGES; page++) {
            WestmedPage<T> p = fetch.apply(page);
            List<T> content = p == null ? List.of() : p.contentOrEmpty();
            int fresh = 0;
            for (T r : content) {
                String externalId = kind.externalId(siteIdOf.apply(r));
                if (intake.isKnown(LeadSources.WESTMED, externalId)) continue;
                fresh++;
                try {
                    if (intake.ingest(toLead.apply(r)).isPresent()) res.created++;
                } catch (DataIntegrityViolationException e) {
                    // гонка вставок: обращение уже создано — это не ошибка (спека §7)
                } catch (RuntimeException e) {
                    res.errors++;
                    res.lastError = "заявка " + externalId + ": " + e.getMessage();
                    log.warn("westmed.kz: заявка {} не принята: {}", externalId, e.getMessage());
                }
            }
            if (fresh == 0 || p == null || p.isLast()) break;
        }
    }

    /** Отказ входа (WestmedAuthException) пробрасывается — планировщик ставит паузу; ожидание остаётся. */
    private void push(WestmedSyncResult res) {
        for (LeadStatusPushWriter.PendingPush p : pushWriter.findPending(LeadSources.WESTMED)) {
            try {
                client.updateStatus(WestmedKind.ofExternalId(p.externalId()), WestmedKind.siteIdOf(p.externalId()), p.status());
                pushWriter.markPushed(p.leadId(), p.status());
                res.pushed++;
            } catch (WestmedApiException e) {
                if (e.status() == 404) {
                    pushWriter.markGone(p.leadId());
                } else {
                    pushWriter.markFailed(p.leadId(), "не удалось записать статус на сайт: " + e.getMessage());
                    res.errors++;
                    res.lastError = e.getMessage();
                }
            }
        }
    }
}
```

- [ ] **Step 5: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadSyncTest'`
Expected: PASS, 7 тестов.

- [ ] **Step 6: Мутации (по одной, спека §15)**

M1 — в `pull` заменить `if (fresh == 0 || p == null || p.isLast()) break;` на `if (p == null || p.isLast()) break;`.
Run: `./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadSyncTest.firstRunWalksAllPagesLaterRunsStopAtFirstPageWithoutNews'`
Expected: FAIL (на третьем прогоне листаются все страницы). Вернуть.

M2 — в `push` в ветке `else` заменить `pushWriter.markFailed(...)` на `pushWriter.markGone(p.leadId())`.
Run: `./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadSyncTest.failedPushKeepsPendingAndRecordsError'`
Expected: FAIL (ожидание пропало). Вернуть, прогнать весь класс — PASS.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/LeadStatusPushWriter.java \
  src/main/java/com/vladoose/nir/integration/westmed/WestmedSyncResult.java \
  src/main/java/com/vladoose/nir/integration/westmed/WestmedLeadSync.java \
  src/test/java/com/vladoose/nir/integration/westmed/WestmedLeadSyncTest.java
git commit -m "$(printf 'feat(westmed): цикл синхронизации — история, инкремент, надёжная запись статусов\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 9: Планировщик, конфигурация, эндпоинты синхронизации

**Files:**
- Create: `integration/westmed/WestmedLeadScheduler.java`, `dto/response/LeadSyncStatusResponse.java`
- Modify: `controller/LeadController.java` (+ планировщик, `GET /sync-status`, `POST /sync`)
- Modify: `src/main/resources/application.yaml` (+ блок `leads`), `.env.example` (+ переменные WESTMED_*)
- Test: `integration/westmed/WestmedLeadSchedulerTest.java`, `lead/LeadControllerTest.java` (+1 тест)

**Interfaces:**
- Consumes: `WestmedLeadSync.runOnce`, `WestmedSyncResult` (Task 8), `WestmedClient.isConfigured`, `WestmedAuthException` (Task 6), `FakeWestmedClient` (Task 7).
- Produces:
  - `WestmedLeadScheduler(WestmedLeadSync, WestmedClient, boolean enabled, boolean writeStatus, String market, long authBackoffMs)` (`@Value` на примитивах): `tick()` (`@Scheduled`), `runNow(): LeadSyncStatusResponse` (при выключенной интеграции — `BadRequestException`), `status(): LeadSyncStatusResponse`, пакетный `cycle()`.
  - `LeadSyncStatusResponse`: `enabled, writeStatus, running, lastRunAt, lastSuccessAt, lastError, lastCreated`.
  - `GET /api/leads/sync-status`, `POST /api/leads/sync` (ADMIN).

- [ ] **Step 1: Написать падающий тест планировщика**

`src/test/java/com/vladoose/nir/integration/westmed/WestmedLeadSchedulerTest.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadStatusPushWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** cycle() зовётся напрямую в потоке теста: так он входит в транзакцию теста и откатывается. */
@SpringBootTest
@Transactional
class WestmedLeadSchedulerTest {

    @Autowired LeadIntakeService intake;
    @Autowired LeadStatusPushWriter pushWriter;
    @Autowired LeadRepository leadRepository;

    FakeWestmedClient fake;

    @BeforeEach void setUp() { fake = new FakeWestmedClient(); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private WestmedLeadScheduler scheduler(boolean enabled) {
        WestmedLeadSync sync = new WestmedLeadSync(fake, new WestmedLeadMapper("https://westmed.kz"),
                intake, pushWriter, 50, false);
        return new WestmedLeadScheduler(sync, fake, enabled, false, "KZ", 600_000);
    }

    private static WestmedPriceRequest price() {
        String id = "zz-" + UUID.randomUUID();
        return new WestmedPriceRequest(id, "Клиент", id + "@zz.kz", null, null, "Нужен аппарат", null,
                "NEW", "2026-09-20T08:15:30Z");
    }

    @Test
    void cycleStampsMarketFromConfigEvenWhenThreadHasDefaultMarket() {
        WestmedPriceRequest r = price();
        fake.priceRequests.add(r);
        MarketContext.clear();   // у фонового потока рынка нет — дефолт RF (§6 CLAUDE.md)

        scheduler(true).cycle();

        String ext = WestmedKind.PRICE.externalId(r.id());
        MarketContext.set(Market.RF);
        assertThat(leadRepository.findBySourceAndExternalId(LeadSources.WESTMED, ext)).isEmpty();
        MarketContext.set(Market.KZ);
        Lead l = leadRepository.findBySourceAndExternalId(LeadSources.WESTMED, ext).orElseThrow();
        assertThat(l.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void successIsReportedInStatus() {
        fake.priceRequests.add(price());
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).isNull();
        assertThat(s.status().getLastSuccessAt()).isNotNull();
        assertThat(s.status().getLastCreated()).isEqualTo(1);
        assertThat(s.status().isEnabled()).isTrue();
    }

    @Test
    void missingCredentialsAreReportedAndSiteIsNotCalled() {
        fake.configured = false;
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).contains("учётные данные");
        assertThat(fake.pricePages).isEmpty();
    }

    @Test
    void authFailurePausesFurtherCycles() {
        fake.failFetchWith = new WestmedAuthException("вход не удался: неверный логин или пароль");
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("неверный логин").contains("повтор через 10 мин");

        fake.failFetchWith = null;
        s.cycle();
        assertThat(fake.pricePages).isEmpty();   // пауза: на сайт не ходили — не упираемся в лимит входов
    }

    @Test
    void siteDownIsReportedButNotPaused() {
        fake.failFetchWith = new WestmedApiException(0, "сайт недоступен: ConnectException");
        WestmedLeadScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("сайт недоступен");

        fake.failFetchWith = null;
        s.cycle();
        assertThat(fake.pricePages).containsExactly(0);
        assertThat(s.status().getLastError()).isNull();
    }

    @Test
    void manualRunIsRefusedWhenIntegrationIsOff() {
        assertThatThrownBy(() -> scheduler(false).runNow()).isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: Дописать тест контроллера**

В `src/test/java/com/vladoose/nir/lead/LeadControllerTest.java` добавить импорт `com.vladoose.nir.dto.response.LeadSyncStatusResponse` и тест:

```java
    @Test
    @WithMockUser(roles = "OPERATOR")
    void syncStatusIsReadableByEveryone() {
        LeadSyncStatusResponse s = controller.syncStatus();
        assertThat(s).isNotNull();
        assertThatThrownBy(() -> controller.sync()).isInstanceOf(AccessDeniedException.class);
    }
```

- [ ] **Step 3: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.WestmedLeadSchedulerTest' --tests 'com.vladoose.nir.lead.LeadControllerTest'`
Expected: FAIL — нет `WestmedLeadScheduler`, `LeadSyncStatusResponse`, `controller.syncStatus()`.

- [ ] **Step 4: DTO состояния и планировщик**

`dto/response/LeadSyncStatusResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Плашка «westmed.kz · синхронизировано N мин назад» на странице «Обращения». */
@Data
public class LeadSyncStatusResponse {
    private boolean enabled;
    private boolean writeStatus;
    private boolean running;
    private OffsetDateTime lastRunAt;
    private OffsetDateTime lastSuccessAt;
    private String lastError;
    private int lastCreated;
}
```

`integration/westmed/WestmedLeadScheduler.java`:
```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LeadSyncStatusResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Расписание приёма заявок westmed.kz (спека §6.4). Работа — на СВОЁМ однопоточном экзекьюторе
 * «westmed-leads», а не на общем scheduling-1: зависший сайт не должен задерживать приём почты и импорты.
 * Рынок ставится ЯВНО и чистится в finally (§6 CLAUDE.md). После отказа входа — пауза authBackoffMs:
 * сайт пускает 5 входов за 5 минут с IP, и долбёжка каждые 90 с держала бы его в 429.
 */
@Component
public class WestmedLeadScheduler {

    private static final Logger log = LoggerFactory.getLogger(WestmedLeadScheduler.class);

    private final WestmedLeadSync sync;
    private final WestmedClient client;
    private final boolean enabled;
    private final boolean writeStatus;
    private final Market market;
    private final long authBackoffMs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "westmed-leads");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile OffsetDateTime lastRunAt;
    private volatile OffsetDateTime lastSuccessAt;
    private volatile String lastError;
    private volatile int lastCreated;
    private volatile long authBlockedUntil;
    private volatile boolean credentialsWarned;

    public WestmedLeadScheduler(WestmedLeadSync sync, WestmedClient client,
                                @Value("${leads.westmed.enabled:false}") boolean enabled,
                                @Value("${leads.westmed.write-status:false}") boolean writeStatus,
                                @Value("${leads.westmed.market:KZ}") String market,
                                @Value("${leads.westmed.auth-backoff-ms:600000}") long authBackoffMs) {
        this.sync = sync;
        this.client = client;
        this.enabled = enabled;
        this.writeStatus = writeStatus;
        this.market = Market.fromHeader(market);
        this.authBackoffMs = authBackoffMs;
    }

    @Scheduled(fixedDelayString = "${leads.westmed.poll-ms:90000}",
               initialDelayString = "${leads.westmed.initial-delay-ms:30000}")
    public void tick() {
        if (enabled) submit();
    }

    /** «Проверить сейчас»: цикл в том же экзекьюторе, ждём до 30 с; идёт другой — отдаём текущее состояние. */
    public LeadSyncStatusResponse runNow() {
        if (!enabled) throw new BadRequestException("Приём заявок с сайта westmed.kz выключен");
        Future<?> f = submit();
        if (f != null) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // цикл продолжится в фоне — вернём то, что есть
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // ошибки цикл сам пишет в lastError
            }
        }
        return status();
    }

    private Future<?> submit() {
        if (!running.compareAndSet(false, true)) return null;
        try {
            return executor.submit(this::cycle);
        } catch (RejectedExecutionException e) {
            running.set(false);
            return null;
        }
    }

    /** Один цикл. Пакетная видимость — ради тестов (там зовётся напрямую, в транзакции теста). */
    void cycle() {
        try {
            lastRunAt = OffsetDateTime.now();
            if (!client.isConfigured()) {
                lastError = "не заданы учётные данные сайта (WESTMED_USERNAME / WESTMED_PASSWORD)";
                if (!credentialsWarned) {
                    log.warn("westmed.kz: {}", lastError);
                    credentialsWarned = true;
                }
                return;
            }
            if (System.currentTimeMillis() < authBlockedUntil) return;   // пауза: lastError уже объясняет
            MarketContext.set(market);
            WestmedSyncResult r = sync.runOnce();
            lastCreated = r.created;
            lastSuccessAt = OffsetDateTime.now();
            lastError = r.errors > 0 ? "часть операций не удалась: " + r.lastError : null;
        } catch (WestmedAuthException e) {
            authBlockedUntil = System.currentTimeMillis() + authBackoffMs;
            lastError = e.getMessage() + " — повтор через " + Math.max(1, authBackoffMs / 60_000) + " мин";
            log.warn("westmed.kz: {}", lastError);
        } catch (RuntimeException e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("westmed.kz: цикл синхронизации не удался: {}", lastError);
        } finally {
            MarketContext.clear();
            running.set(false);
        }
    }

    public LeadSyncStatusResponse status() {
        LeadSyncStatusResponse s = new LeadSyncStatusResponse();
        s.setEnabled(enabled);
        s.setWriteStatus(writeStatus);
        s.setRunning(running.get());
        s.setLastRunAt(lastRunAt);
        s.setLastSuccessAt(lastSuccessAt);
        s.setLastError(lastError);
        s.setLastCreated(lastCreated);
        return s;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 5: Эндпоинты в контроллере**

В `controller/LeadController.java`:
- импорты `com.vladoose.nir.dto.response.LeadSyncStatusResponse`, `com.vladoose.nir.integration.westmed.WestmedLeadScheduler`;
- поле `private final WestmedLeadScheduler westmedScheduler;` и третий параметр конструктора `WestmedLeadScheduler westmedScheduler` с присваиванием;
- методы после `convert`:

```java
    @GetMapping("/sync-status")
    public LeadSyncStatusResponse syncStatus() {
        return westmedScheduler.status();
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadSyncStatusResponse sync() {
        return westmedScheduler.runNow();
    }
```

- [ ] **Step 6: Конфигурация**

В `src/main/resources/application.yaml` после блока `techspec:` добавить:

```yaml

# Обращения: приём заявок с сайта westmed.kz через API его админки (код сайта не меняется).
# Спека: docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md. Выкл по умолчанию, как все импорты.
leads:
  westmed:
    enabled: ${WESTMED_LEADS_ENABLED:false}
    base-url: ${WESTMED_BASE_URL:https://westmed.kz}
    site-url: ${WESTMED_SITE_URL:https://westmed.kz}     # для ссылок «на сайте ↗» у позиций
    username: ${WESTMED_USERNAME:}
    password: ${WESTMED_PASSWORD:}
    write-status: ${WESTMED_WRITE_STATUS:false}        # локально НЕ пишем статусы в боевой сайт
    market: ${WESTMED_MARKET:KZ}
    poll-ms: ${WESTMED_POLL_MS:90000}
    initial-delay-ms: ${WESTMED_INITIAL_DELAY_MS:30000}
    page-size: 50
    auth-backoff-ms: 600000                            # сайт пускает 5 входов за 5 мин с IP
    # от кого сайт шлёт письма-уведомления о заявках — приём почты их пропускает (спека §11)
    notification-from: ${WESTMED_NOTIFICATION_FROM:info@westmed.kz}
```

В `.env.example` в конец добавить:

```
# --- Обращения: приём заявок с сайта westmed.kz (API админки сайта, код сайта не меняется) ---
# Учётка АИС на сайте создаётся одной строкой в его БД (спека §13). Пароль — только здесь.
WESTMED_LEADS_ENABLED=false
WESTMED_BASE_URL=https://westmed.kz
WESTMED_USERNAME=ais@westmed.kz
WESTMED_PASSWORD=CHANGE_ME_westmed_integration_password
WESTMED_WRITE_STATUS=true
```

- [ ] **Step 7: Запустить — зелёные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.westmed.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS (все тесты пакетов `integration.westmed` и `lead`).

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/westmed/WestmedLeadScheduler.java \
  src/main/java/com/vladoose/nir/dto/response/LeadSyncStatusResponse.java src/main/java/com/vladoose/nir/controller/LeadController.java \
  src/main/resources/application.yaml .env.example \
  src/test/java/com/vladoose/nir/integration/westmed/WestmedLeadSchedulerTest.java src/test/java/com/vladoose/nir/lead/LeadControllerTest.java
git commit -m "$(printf 'feat(westmed): планировщик приёма заявок в своём потоке + статус синхронизации в API\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 10: Почта — не дублировать заявки письмами-уведомлениями сайта

**Files:**
- Modify: `service/MailReceiveService.java`
- Modify: `dto/response/PollResultResponse.java`
- Test: `mail/MailReceiveServiceIntegrationTest.java` (+1 тест)

**Interfaces:**
- Consumes: свойство `leads.westmed.notification-from` (Task 9, дефолт `info@westmed.kz`).
- Produces: `PollResultResponse.skippedSiteNotifications: int`; письмо «от `notification-from` И тема оканчивается на „— westmed.kz“» помечается прочитанным и не сохраняется.

- [ ] **Step 1: Написать падающий тест**

В `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java` добавить импорт `com.vladoose.nir.dto.response.PollResultResponse` и тест (класс уже поднимает GreenMail и ящик `zakup@westmed.kz`; хелпер `message(from, subject, body, attach, name)` уже есть):

```java
    @Test
    void poll_skipsWestmedSiteNotifications_butKeepsClientMail() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        String tag = "ZZSITE-" + System.nanoTime();
        user.deliver(message("WestMed.kz <info@westmed.kz>", "Запрос КП (2 поз.) — westmed.kz",
                "Запрос коммерческого предложения " + tag, null, null));
        user.deliver(message("clinic@x.kz", "Нужен облучатель " + tag, "Добрый день, нужен облучатель", null, null));

        MarketContext.set(Market.KZ);
        long before = inboundEmailRepository.count();
        PollResultResponse res = mailReceiveService.poll();

        assertThat(res.getSkippedSiteNotifications()).isEqualTo(1);
        assertThat(inboundEmailRepository.count()).isEqualTo(before + 1);   // сохранено только письмо клиники
        assertThat(inboundEmailRepository.findAll())
                .anyMatch(e -> ("Нужен облучатель " + tag).equals(e.getSubject()))
                .noneMatch(e -> e.getExcerpt() != null && e.getExcerpt().contains(tag)
                        && e.getSubject() != null && e.getSubject().endsWith("— westmed.kz"));
    }
```

- [ ] **Step 2: Запустить — не компилируется**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest'`
Expected: FAIL — нет `getSkippedSiteNotifications()`.

- [ ] **Step 3: Счётчик в результате опроса**

В `dto/response/PollResultResponse.java` добавить поле:

```java
    /** Письма-уведомления westmed.kz о заявках с сайта: сами заявки приходят через API сайта (обращения). */
    private int skippedSiteNotifications;
```

- [ ] **Step 4: Фильтр в приёме почты**

В `service/MailReceiveService.java`:

1) Поле после `sendFrom`:
```java
    /** От кого сайт westmed.kz шлёт уведомления о заявках — их пропускаем (заявки берём через API сайта). */
    private final String siteNotificationFrom;
```

2) Параметр конструктора последним (после `@Value("${spring.mail.username:}") String sendFrom`):
```java
                              @Value("${leads.westmed.notification-from:info@westmed.kz}") String siteNotificationFrom
```
и присваивание в теле конструктора:
```java
        this.siteNotificationFrom = siteNotificationFrom == null ? "" : siteNotificationFrom.trim().toLowerCase();
```

3) В цикле `poll()` сразу после блока отсечки старых писем (`if (received != null && received.getTime() < cutoffMs) { … continue; }`) и ДО `handle(msg, result);`:
```java
                if (isSiteNotification(msg)) {
                    msg.setFlag(Flags.Flag.SEEN, true);
                    result.setSkippedSiteNotifications(result.getSkippedSiteNotifications() + 1);
                    continue;
                }
```

4) Сообщение результата: строку
```java
            result.setMessage("Обработано свежих писем (за " + sinceMinutes + " мин): " + result.getFetched()
                    + (skippedOld > 0 ? "; пропущено старых: " + skippedOld : ""));
```
заменить на
```java
            result.setMessage("Обработано свежих писем (за " + sinceMinutes + " мин): " + result.getFetched()
                    + (skippedOld > 0 ? "; пропущено старых: " + skippedOld : "")
                    + (result.getSkippedSiteNotifications() > 0
                        ? "; уведомлений сайта о заявках пропущено: " + result.getSkippedSiteNotifications() : ""));
```

5) Метод рядом с `handle`:
```java
    /**
     * Письмо-уведомление westmed.kz о заявке с сайта («Новая заявка…», «Запрос КП…», «WhatsApp-обращение…
     * — westmed.kz»). Заявки АИС получает через API сайта (спека обращений §11) — письмо было бы дублем.
     */
    private boolean isSiteNotification(Message msg) throws MessagingException {
        if (siteNotificationFrom.isBlank()) return false;
        String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? decode(msg.getFrom()[0].toString()) : "";
        String subject = msg.getSubject() == null ? "" : msg.getSubject().strip();
        return addressPart(from).equalsIgnoreCase(siteNotificationFrom) && subject.endsWith("— westmed.kz");
    }
```

- [ ] **Step 5: Запустить — весь класс почты зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.mail.*'`
Expected: PASS (новый тест + все прежние тесты почты).

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/MailReceiveService.java \
  src/main/java/com/vladoose/nir/dto/response/PollResultResponse.java \
  src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java
git commit -m "$(printf 'feat(mail): письма-уведомления сайта о заявках не дублируют обращения\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 11: Фронт — API, маршрут, пункт меню со счётчиком, список «Обращения»

**Files:**
- Create: `frontend/src/app/shared/relative-time.ts`, `frontend/src/app/shared/lead-labels.ts`
- Create: `frontend/src/app/pages/leads/leads.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`, `frontend/src/app/app.routes.ts`, `frontend/src/app/app.config.ts`, `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: REST из Tasks 4, 9 (`GET /api/leads`, `/count`, `/sync-status`, `POST /sync`).
- Produces:
  - `ApiService`: `getLeads({status?, channel?, q?})`, `getLead(id)`, `getLeadCount(status)`, `createLead(body)`, `updateLeadItems(id, items)`, `takeLead(id)`, `closeLead(id, reason, comment)`, `reopenLead(id)`, `addLeadEvent(id, {type, direction?, body})`, `convertLead(id, body)`, `getLeadSyncStatus()`, `runLeadSync()`.
  - `relativeTime(iso)`, `fullDateTime(iso)` (`shared/relative-time.ts`); `LEAD_STATUS_LABELS`, `LEAD_CHANNEL_LABELS`, `LEAD_CLOSE_REASONS`, `leadChannelLabel(channel, source)` (`shared/lead-labels.ts`).
  - `LeadsComponent` (`app-leads`) на `/leads` с полями `cardId`, `load(silent?)`, `open(id)`.

Фронт тестов не имеет — гейт `npm run build`, живая проверка — Task 14.

- [ ] **Step 1: Методы API**

В `frontend/src/app/services/api.service.ts` после блока `// === Входящие письма ===` (после метода `markInboundProcessed`) вставить:

```ts
  // === Обращения (заявки с сайтов, звонки, WhatsApp) ===
  getLeads(params: { status?: string; channel?: string; q?: string } = {}): Observable<any[]> {
    const p: any = {};
    if (params.status) p.status = params.status;
    if (params.channel) p.channel = params.channel;
    if (params.q) p.q = params.q;
    return this.http.get<any[]>(`${this.base}/leads`, { params: p });
  }
  getLead(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/leads/${id}`);
  }
  getLeadCount(status = 'NEW'): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.base}/leads/count`, { params: { status } });
  }
  createLead(body: any): Observable<any> {
    return this.http.post<any>(`${this.base}/leads`, body);
  }
  updateLeadItems(id: number, items: any[]): Observable<any> {
    return this.http.put<any>(`${this.base}/leads/${id}/items`, { items });
  }
  takeLead(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/take`, {});
  }
  closeLead(id: number, reason: string, comment: string): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/close`, { reason, comment });
  }
  reopenLead(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/reopen`, {});
  }
  addLeadEvent(id: number, body: { type: 'NOTE' | 'CALL'; direction?: 'IN' | 'OUT'; body: string }): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/events`, body);
  }
  convertLead(id: number, body: any): Observable<{ privateRequestId: number; number: string }> {
    return this.http.post<{ privateRequestId: number; number: string }>(`${this.base}/leads/${id}/convert`, body);
  }
  getLeadSyncStatus(): Observable<any> {
    return this.http.get<any>(`${this.base}/leads/sync-status`);
  }
  runLeadSync(): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/sync`, {});
  }
```

- [ ] **Step 2: Общие помощники**

`frontend/src/app/shared/relative-time.ts`:
```ts
/** «только что» / «12 мин назад» / «3 ч назад»; старше суток — полная дата. Для лент и списков. */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return '—';
  const t = new Date(iso);
  if (isNaN(t.getTime())) return '—';
  const min = Math.floor((now.getTime() - t.getTime()) / 60000);
  if (min < 1) return 'только что';
  if (min < 60) return `${min} мин назад`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} ч назад`;
  return fullDateTime(iso);
}

export function fullDateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const t = new Date(iso);
  if (isNaN(t.getTime())) return '';
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(t);
}
```

`frontend/src/app/shared/lead-labels.ts`:
```ts
export const LEAD_STATUS_LABELS: Record<string, string> = {
  NEW: 'Новое', IN_WORK: 'В работе', CONVERTED: 'Заявка создана', CLOSED: 'Закрыто',
};

export const LEAD_CHANNEL_LABELS: Record<string, string> = {
  SITE: 'Сайт', PHONE: 'Звонок', WHATSAPP: 'WhatsApp', EMAIL: 'Почта', OTHER: 'Другое',
};

export const LEAD_CLOSE_REASONS = [
  { v: 'ANSWERED', l: 'Ответили клиенту без заявки' },
  { v: 'SPAM', l: 'Спам' },
  { v: 'DUPLICATE', l: 'Дубль' },
  { v: 'NOT_OUR_PROFILE', l: 'Не наш профиль' },
  { v: 'CLIENT_DECLINED', l: 'Клиент отказался' },
  { v: 'OTHER', l: 'Другое' },
];

/** У заявок с сайта показываем сам сайт («westmed.kz», позже «vital-spb.kz»), у остальных — канал. */
export function leadChannelLabel(channel: string, source?: string | null): string {
  if (channel === 'SITE' && source) return source;
  return LEAD_CHANNEL_LABELS[channel] || channel;
}
```

- [ ] **Step 3: Страница списка**

`frontend/src/app/pages/leads/leads.component.ts`:
```ts
import { Component, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { relativeTime, fullDateTime } from '../../shared/relative-time';
import { LEAD_STATUS_LABELS, leadChannelLabel } from '../../shared/lead-labels';

/**
 * «Обращения» — вход коммерческой воронки West-Med: заявки с сайта westmed.kz (фоновый опрос сайта),
 * звонки и WhatsApp (ручной ввод). Спека: docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md §10.
 */
@Component({
  selector: 'app-leads',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <div>
        <h2>Обращения</h2>
        <p class="subtitle">Заявки с сайтов, звонки и WhatsApp — всё, что клиенты просят найти</p>
      </div>
      <div class="head-actions" *ngIf="auth.isAdmin()">
        <button type="button" class="btn btn-line" *ngIf="sync?.enabled" [disabled]="syncing" (click)="runSync()">
          {{ syncing ? 'Проверяю…' : 'Проверить сейчас' }}
        </button>
      </div>
    </div>

    <div class="sync-plate" *ngIf="sync" [class.is-error]="sync.enabled && sync.lastError">{{ syncText() }}</div>

    <div class="filters">
      <div class="chips" role="group" aria-label="Статус">
        <button type="button" class="chip" *ngFor="let f of statusFilters"
                [class.on]="statusKey === f.key" (click)="setStatus(f.key)">{{ f.label }}</button>
      </div>
      <div class="filters-right">
        <select [(ngModel)]="channel" (change)="load()" aria-label="Канал">
          <option value="">Все каналы</option>
          <option value="SITE">Сайт</option>
          <option value="PHONE">Звонок</option>
          <option value="WHATSAPP">WhatsApp</option>
          <option value="OTHER">Другое</option>
        </select>
        <input type="search" [(ngModel)]="q" (input)="onSearch()"
               placeholder="Имя, телефон, компания, текст…" aria-label="Поиск" />
      </div>
    </div>

    <div class="empty" *ngIf="!loading && !leads.length">Обращений нет</div>

    <div class="lead-list">
      <article class="lead-card" *ngFor="let l of leads; trackBy: trackById" tabindex="0"
               [class.is-new]="l.status === 'NEW'" (click)="open(l.id)" (keydown.enter)="open(l.id)">
        <div class="lc-top">
          <span class="ch" [attr.data-channel]="l.channel">{{ channelLabel(l) }}</span>
          <span class="subj">{{ l.subject }}<ng-container *ngIf="l.itemsCount"> · {{ l.itemsCount }} поз.</ng-container></span>
          <span class="st" [attr.data-status]="l.status">{{ statusLabel(l.status) }}</span>
        </div>
        <div class="lc-what">{{ whatText(l) }}</div>
        <div class="lc-bottom">
          <span class="who">{{ l.contactName || '—' }}<ng-container *ngIf="l.company"> · {{ l.company }}</ng-container><ng-container *ngIf="l.contactPhone"> · {{ l.contactPhone }}</ng-container></span>
          <span class="meta">
            <span class="warn" *ngIf="l.syncError" title="Статус на сайте не обновлён — повторим автоматически">⚠</span>
            <a *ngIf="l.privateRequestId" [routerLink]="['/private-requests']" [queryParams]="{ openId: l.privateRequestId }"
               (click)="$event.stopPropagation()">{{ l.privateRequestNumber }}</a>
            <time [attr.title]="full(l.receivedAt)">{{ ago(l.receivedAt) }}</time>
          </span>
        </div>
      </article>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .sync-plate { font-size: 13px; color: var(--text-muted); background: var(--surface-2); border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; }
    .sync-plate.is-error { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .filters { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .filters-right { display: flex; gap: 8px; flex-wrap: wrap; }
    .filters-right select, .filters-right input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .filters-right input { min-width: 240px; }
    .lead-list { display: flex; flex-direction: column; gap: 8px; }
    .lead-card { background: var(--surface); border: 1px solid var(--border); border-left: 3px solid transparent; border-radius: 10px; padding: 12px 14px; cursor: pointer; transition: box-shadow .15s; }
    .lead-card:hover { box-shadow: var(--shadow); }
    .lead-card:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    /* подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit, styles.scss) */
    .lead-card.is-new { border-left-color: var(--accent); background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .lc-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    /* чипы: 15% тинта + текстовый токен (правило kit) */
    .ch { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 6px; background: var(--surface-2); color: var(--text-muted); }
    .ch[data-channel="SITE"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .ch[data-channel="WHATSAPP"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .ch[data-channel="PHONE"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .subj { font-weight: 600; color: var(--text); flex: 1; min-width: 0; }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); white-space: nowrap; }
    .st[data-status="NEW"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="IN_WORK"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .st[data-status="CONVERTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .lc-what { margin-top: 6px; color: var(--text); font-size: 14px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .lc-bottom { margin-top: 6px; display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--text-muted); }
    .who { min-width: 0; overflow-wrap: anywhere; }
    .meta { display: flex; gap: 10px; align-items: center; white-space: nowrap; }
    .meta a { color: var(--accent); text-decoration: none; font-weight: 600; }
    .warn { color: var(--warn-text); }
    @media (max-width: 900px) {
      .filters-right { width: 100%; }
      .filters-right select, .filters-right input { flex: 1; min-width: 0; font-size: 16px; }
      .lead-card { padding: 12px; }
    }
  `]
})
export class LeadsComponent implements OnDestroy {
  leads: any[] = [];
  loading = false;
  sync: any = null;
  syncing = false;
  statusKey = 'NEW,IN_WORK';
  channel = '';
  q = '';
  cardId: number | null = null;
  readonly statusFilters = [
    { key: 'NEW,IN_WORK', label: 'Активные' },
    { key: 'NEW', label: 'Новые' },
    { key: 'IN_WORK', label: 'В работе' },
    { key: 'CONVERTED', label: 'Заявка создана' },
    { key: 'CLOSED', label: 'Закрытые' },
    { key: 'ALL', label: 'Все' },
  ];
  private searchTimer: any = null;
  private readonly refreshTimer: any;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef) {
    this.route.queryParams.subscribe(p => {
      this.cardId = p['openId'] ? +p['openId'] : null;
      this.cdr.detectChanges();
    });
    this.load();
    this.loadSync();
    // заявки с сайта приходят фоном (~90 с) — список и плашка обновляются, пока экран открыт
    this.refreshTimer = setInterval(() => { this.load(true); this.loadSync(); }, 60000);
  }

  ngOnDestroy() {
    clearInterval(this.refreshTimer);
    clearTimeout(this.searchTimer);
  }

  load(silent = false) {
    if (!silent) this.loading = true;
    this.api.getLeads({ status: this.statusKey, channel: this.channel || undefined, q: this.q.trim() || undefined }).subscribe({
      next: d => { this.leads = d; this.loading = false; this.cdr.detectChanges(); },
      error: e => {
        this.loading = false;
        if (!silent) this.notify.error('Ошибка загрузки обращений: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  loadSync() {
    this.api.getLeadSyncStatus().subscribe({ next: s => { this.sync = s; this.cdr.detectChanges(); }, error: () => {} });
  }

  runSync() {
    this.syncing = true;
    this.api.runLeadSync().subscribe({
      next: s => {
        this.sync = s;
        this.syncing = false;
        if (s?.lastError) this.notify.error('westmed.kz: ' + s.lastError);
        else this.notify.success(s?.lastCreated ? `Новых обращений: ${s.lastCreated}` : 'Новых обращений нет');
        this.load();
      },
      error: e => { this.syncing = false; this.notify.error(e.error?.message || 'Не удалось проверить сайт'); this.cdr.detectChanges(); },
    });
  }

  setStatus(key: string) { this.statusKey = key; this.load(); }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  open(id: number) {
    this.router.navigate([], { relativeTo: this.route, queryParams: { openId: id }, queryParamsHandling: 'merge' });
  }

  syncText(): string {
    const s = this.sync;
    if (!s.enabled) return 'westmed.kz: приём заявок выключен';
    if (s.lastError) return 'westmed.kz: ' + s.lastError;
    if (s.lastSuccessAt) return 'westmed.kz · синхронизировано ' + relativeTime(s.lastSuccessAt);
    return 'westmed.kz · ещё не синхронизировано';
  }

  whatText(l: any): string {
    if (l.itemsPreview?.length) {
      const rest = l.itemsCount - l.itemsPreview.length;
      return l.itemsPreview.join(' · ') + (rest > 0 ? ` · ещё ${rest}` : '');
    }
    return l.messagePreview || '—';
  }

  trackById(_: number, l: any) { return l.id; }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }
  statusLabel(s: string) { return LEAD_STATUS_LABELS[s] || s; }
  channelLabel(l: any) { return leadChannelLabel(l.channel, l.source); }
}
```

- [ ] **Step 4: Маршрут и иконка**

`frontend/src/app/app.routes.ts` — импорт после строки `import { InboundComponent } …`:
```ts
import { LeadsComponent } from './pages/leads/leads.component';
```
и маршрут перед `{ path: 'private-requests', component: PrivateRequestsComponent },`:
```ts
      { path: 'leads', component: LeadsComponent },
```

`frontend/src/app/app.config.ts` — иконка должна быть зарегистрирована, иначе lucide отдаст ПУСТОЙ `<svg>` (гоча §14 CLAUDE.md):
- в импорте заменить `  LucideExternalLink, LucideBadgeCheck\n} from '@lucide/angular';` на `  LucideExternalLink, LucideBadgeCheck, LucideInbox\n} from '@lucide/angular';`
- в `provideLucideIcons(...)` заменить `      LucideExternalLink, LucideBadgeCheck\n    ),` на `      LucideExternalLink, LucideBadgeCheck, LucideInbox\n    ),`

- [ ] **Step 5: Пункт меню со счётчиком**

В `frontend/src/app/layout/layout.component.ts`:

1) Импорты: `import { Component, HostListener, ChangeDetectorRef } from '@angular/core';` → `import { Component, HostListener, ChangeDetectorRef, OnInit, OnDestroy } from '@angular/core';`; `import { RouterOutlet, RouterLink, RouterLinkActive, Router } from '@angular/router';` → `import { RouterOutlet, RouterLink, RouterLinkActive, Router, NavigationEnd } from '@angular/router';`; добавить строки:
```ts
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { ApiService } from '../services/api.service';
```

2) Шаблон: заменить
```html
            <span class="nav-group-title">Заявки</span>
            <a routerLink="/applies" routerLinkActive="active">
```
на
```html
            <span class="nav-group-title">Заявки</span>
            <a routerLink="/leads" routerLinkActive="active">
              <svg lucideIcon="inbox" [size]="16"></svg> Обращения
              <span class="nav-count" *ngIf="newLeads > 0" [attr.aria-label]="newLeads + ' новых'">{{ newLeads }}</span>
            </a>
            <a routerLink="/applies" routerLinkActive="active">
```

3) Стили: после строки `    .sidebar a.active svg { opacity: 1; }` вставить (выше блока `@media` — он должен оставаться последним):
```css
    /* Счётчик новых обращений — формула ЧИПА (15% тинт + текстовый токен), читается в обеих темах.
       На активном пункте (заливка --accent) — «таблетка» цвета поверхности с обычным текстом. */
    .nav-count { margin-left: auto; min-width: 20px; padding: 0 7px; border-radius: 10px; font-size: 11px; font-weight: 700; line-height: 18px; text-align: center; background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .sidebar a.active .nav-count { background: var(--surface); color: var(--text); }
```

4) Класс: `export class LayoutComponent {` → `export class LayoutComponent implements OnInit, OnDestroy {`; после `showResults = false;` добавить:
```ts
  newLeads = 0;                   // новые обращения текущего рынка — счётчик в меню
  private leadsTimer: any = null;
  private navSub?: Subscription;
```
в конструктор последним параметром добавить `private api: ApiService`; после конструктора добавить:
```ts
  ngOnInit() {
    this.refreshLeadCount();
    this.leadsTimer = setInterval(() => this.refreshLeadCount(), 60000);
    this.navSub = this.router.events.pipe(filter(e => e instanceof NavigationEnd)).subscribe(() => this.refreshLeadCount());
  }

  ngOnDestroy() {
    clearInterval(this.leadsTimer);
    this.navSub?.unsubscribe();
  }

  /** Заявки с сайта приходят фоном — счётчик живёт без перезагрузки страницы. */
  refreshLeadCount() {
    if (!this.auth.isLoggedIn()) return;
    this.api.getLeadCount('NEW').subscribe({
      next: r => { this.newLeads = r.count; this.cdr.detectChanges(); },
      error: () => {},
    });
  }
```

- [ ] **Step 6: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка без ошибок и без превышения бюджетов.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts frontend/src/app/app.routes.ts \
  frontend/src/app/app.config.ts frontend/src/app/layout/layout.component.ts frontend/src/app/shared/relative-time.ts \
  frontend/src/app/shared/lead-labels.ts frontend/src/app/pages/leads/leads.component.ts
git commit -m "$(printf 'feat(leads-ui): страница «Обращения», пункт меню со счётчиком новых\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 12: Фронт — карточка обращения (выдвижная панель)

**Files:**
- Create: `frontend/src/app/pages/leads/lead-card.component.ts`
- Modify: `frontend/src/app/pages/leads/leads.component.ts` (подключить карточку)

**Interfaces:**
- Consumes (Task 11): `ApiService.getLead/takeLead/closeLead/reopenLead/addLeadEvent/updateLeadItems`, `relativeTime`, `fullDateTime`, `LEAD_STATUS_LABELS`, `LEAD_CLOSE_REASONS`, `leadChannelLabel`.
- Produces: `LeadCardComponent` (`app-lead-card`): `@Input() leadId: number | null`, `@Output() close`, `@Output() changed`, `@Output() openLead: EventEmitter<number>`; поле `lead`; кнопки «Создать частную заявку» здесь ещё НЕТ — её добавит Task 13.

- [ ] **Step 1: Компонент карточки**

`frontend/src/app/pages/leads/lead-card.component.ts`:
```ts
import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { relativeTime, fullDateTime } from '../../shared/relative-time';
import { LEAD_CLOSE_REASONS, LEAD_STATUS_LABELS, leadChannelLabel } from '../../shared/lead-labels';

type Panel = 'none' | 'note' | 'call' | 'close' | 'items';

/** Карточка обращения: контакт, что просят, текст клиента, лента, действия (спека §10). */
@Component({
  selector: 'app-lead-card',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div *ngIf="leadId !== null" class="overlay" (click)="close.emit()">
      <aside class="drawer" (click)="$event.stopPropagation()" aria-label="Карточка обращения">
        <header class="head">
          <div class="title-block">
            <h2 class="title">{{ lead?.subject || 'Обращение' }}</h2>
            <div class="subtitle" *ngIf="lead">
              <span>{{ channelLabel() }}</span>
              <span class="dot">·</span>
              <time [attr.title]="full(lead.receivedAt)">{{ ago(lead.receivedAt) }}</time>
              <span class="dot">·</span>
              <span class="st" [attr.data-status]="lead.status">{{ statusLabel(lead.status) }}</span>
            </div>
          </div>
          <button class="close-btn" type="button" (click)="close.emit()" aria-label="Закрыть">&times;</button>
        </header>

        <div class="body">
          <div *ngIf="loading" class="empty">Загрузка…</div>
          <ng-container *ngIf="lead && !loading">
            <div class="error-banner" *ngIf="lead.extSyncError">
              Статус на сайте не обновлён: {{ lead.extSyncError }}. Повторим автоматически.
            </div>

            <div class="actions">
              <ng-container *ngIf="auth.isAdmin()">
                <button type="button" class="btn btn-primary" *ngIf="lead.status === 'NEW'" [disabled]="busy" (click)="take()">Взять в работу</button>
                <button type="button" class="btn btn-line" *ngIf="lead.status === 'CLOSED'" [disabled]="busy" (click)="reopen()">Вернуть в работу</button>
              </ng-container>
              <a class="btn btn-line" *ngIf="lead.privateRequestId" [routerLink]="['/private-requests']"
                 [queryParams]="{ openId: lead.privateRequestId }">Открыть заявку {{ lead.privateRequestNumber }}</a>
              <ng-container *ngIf="auth.isAdmin()">
                <button type="button" class="btn btn-line" [disabled]="busy" (click)="togglePanel('note')">+ Заметка</button>
                <button type="button" class="btn btn-line" [disabled]="busy" (click)="togglePanel('call')">+ Звонок</button>
                <button type="button" class="btn btn-line" *ngIf="lead.status !== 'CLOSED'" [disabled]="busy" (click)="togglePanel('close')">Закрыть…</button>
              </ng-container>
            </div>

            <div class="panel" *ngIf="panel === 'note'">
              <textarea [(ngModel)]="noteText" rows="3" placeholder="Что уточнили, что пообещали клиенту…" aria-label="Заметка"></textarea>
              <div class="panel-actions">
                <button type="button" class="btn btn-primary" [disabled]="busy || !noteText.trim()" (click)="saveNote()">Сохранить</button>
                <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
              </div>
            </div>

            <div class="panel" *ngIf="panel === 'call'">
              <div class="radio-row" role="radiogroup" aria-label="Направление звонка">
                <label><input type="radio" name="leadCallDir" value="IN" [(ngModel)]="callDir" /> Входящий</label>
                <label><input type="radio" name="leadCallDir" value="OUT" [(ngModel)]="callDir" /> Исходящий</label>
              </div>
              <textarea [(ngModel)]="callText" rows="3" placeholder="Итог разговора" aria-label="Итог звонка"></textarea>
              <div class="panel-actions">
                <button type="button" class="btn btn-primary" [disabled]="busy || !callText.trim()" (click)="saveCall()">Сохранить</button>
                <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
              </div>
            </div>

            <div class="panel" *ngIf="panel === 'close'">
              <select [(ngModel)]="closeReason" aria-label="Причина закрытия">
                <option value="" disabled>Причина…</option>
                <option *ngFor="let r of reasons" [value]="r.v">{{ r.l }}</option>
              </select>
              <input type="text" [(ngModel)]="closeComment" placeholder="Комментарий (необязательно)" aria-label="Комментарий" />
              <div class="panel-actions">
                <button type="button" class="btn btn-danger" [disabled]="busy || !closeReason" (click)="doClose()">Закрыть обращение</button>
                <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
              </div>
            </div>

            <section class="section">
              <h3 class="section-title">Контакт</h3>
              <div class="contact">
                <div class="c-name">{{ lead.contactName || '—' }}<span *ngIf="lead.company" class="c-company"> · {{ lead.company }}</span></div>
                <div class="c-row" *ngIf="lead.contactPhone">
                  <span>{{ lead.contactPhone }}</span>
                  <a class="btn btn-line btn-sm" [href]="'tel:' + (lead.phoneNorm || lead.contactPhone)">Позвонить</a>
                  <a class="btn btn-line btn-sm" *ngIf="waLink()" [href]="waLink()" target="_blank" rel="noopener">Написать в WhatsApp</a>
                </div>
                <div class="c-row" *ngIf="lead.contactEmail"><a [href]="'mailto:' + lead.contactEmail">{{ lead.contactEmail }}</a></div>
                <div class="c-row muted">Клиент: {{ lead.facilityName || 'не определён — выберете при создании заявки' }}</div>
                <div class="c-same" *ngIf="lead.samePhone?.length">
                  <span>Этот номер уже обращался:</span>
                  <button type="button" class="linklike" *ngFor="let s of lead.samePhone" (click)="openLead.emit(s.id)">
                    {{ s.subject }} · {{ ago(s.receivedAt) }} ({{ statusLabel(s.status) }})
                  </button>
                </div>
              </div>
            </section>

            <section class="section">
              <div class="section-head">
                <h3 class="section-title">Что просят</h3>
                <button type="button" class="btn btn-line btn-sm" *ngIf="auth.isAdmin() && canEditItems() && panel !== 'items'" (click)="startItems()">✎ Править</button>
              </div>
              <ng-container *ngIf="panel !== 'items'">
                <div class="empty-inline" *ngIf="!lead.items.length">Позиций нет — суть в тексте клиента</div>
                <ul class="items" *ngIf="lead.items.length">
                  <li *ngFor="let i of lead.items">
                    <span class="i-name">{{ i.name }}</span>
                    <span class="i-meta"><ng-container *ngIf="i.brand">{{ i.brand }} · </ng-container>{{ i.quantity }} шт</span>
                    <a *ngIf="i.productUrl" class="i-link" [href]="i.productUrl" target="_blank" rel="noopener">на сайте ↗</a>
                  </li>
                </ul>
              </ng-container>
              <div class="items-edit" *ngIf="panel === 'items'">
                <div class="ie-row" *ngFor="let i of editItems; let idx = index">
                  <input [(ngModel)]="i.name" placeholder="Наименование / модель" aria-label="Наименование" />
                  <input [(ngModel)]="i.brand" placeholder="Бренд" aria-label="Бренд" />
                  <input type="number" min="1" [(ngModel)]="i.quantity" aria-label="Количество" />
                  <button type="button" class="btn btn-cancel btn-sm" (click)="editItems.splice(idx, 1)" aria-label="Удалить строку">✕</button>
                </div>
                <div class="panel-actions">
                  <button type="button" class="btn btn-line btn-sm" (click)="editItems.push({ name: '', brand: '', quantity: 1 })">+ Строка</button>
                </div>
                <div class="panel-actions">
                  <button type="button" class="btn btn-primary" [disabled]="busy" (click)="saveItems()">Сохранить позиции</button>
                  <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
                </div>
              </div>
            </section>

            <section class="section" *ngIf="lead.message">
              <h3 class="section-title">Текст клиента</h3>
              <p class="message">{{ lead.message }}</p>
            </section>

            <section class="section">
              <h3 class="section-title">Лента</h3>
              <ol class="timeline">
                <li *ngFor="let e of lead.events" [attr.data-type]="e.type">
                  <div class="t-head">
                    <span class="t-type">{{ eventLabel(e) }}</span>
                    <time [attr.title]="full(e.at)">{{ ago(e.at) }}</time>
                    <span *ngIf="e.author">{{ e.author }}</span>
                  </div>
                  <div class="t-body" *ngIf="e.body">{{ e.body }}</div>
                </li>
              </ol>
            </section>
          </ng-container>
        </div>
      </aside>
    </div>
  `,
  styles: [`
    /* rgba-вуаль НЕ токенизируется: затемнение под дровером уместно в обеих темах (самое частое значение в приложении) */
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1000; }
    /* Тень направленная: панель у правого края, тень падает влево; токенизирована только сила (--shadow-color) */
    .drawer { position: fixed; top: 0; right: 0; bottom: 0; width: 640px; max-width: 100vw; background: var(--surface); box-shadow: -8px 0 30px var(--shadow-color); display: flex; flex-direction: column; }
    .head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 20px 24px 14px; border-bottom: 1px solid var(--border); }
    .title-block { min-width: 0; flex: 1; }
    .title { margin: 0; font-size: 19px; font-weight: 600; word-break: break-word; }
    /* цвет и размер — из kit-овского .subtitle, здесь только раскладка */
    .subtitle { margin: 6px 0 0; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .dot { color: color-mix(in srgb, var(--text-muted) 45%, transparent); }
    .close-btn { background: transparent; border: none; cursor: pointer; font-size: 28px; line-height: 1; color: var(--text-muted); padding: 0 4px; border-radius: 4px; }
    .close-btn:hover { color: var(--danger); background: color-mix(in srgb, var(--danger) 8%, var(--surface)); }
    .body { flex: 1; overflow-y: auto; padding: 16px 24px 28px; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
    a.btn { display: inline-flex; align-items: center; text-decoration: none; }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    .panel { background: var(--surface-2); border-radius: 8px; padding: 12px; margin-bottom: 14px; display: flex; flex-direction: column; gap: 8px; }
    .panel textarea, .panel select, .panel input, .items-edit input { width: 100%; box-sizing: border-box; padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); font-family: inherit; }
    .panel-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .radio-row { display: flex; gap: 16px; font-size: 13px; }
    .section { margin-bottom: 22px; }
    /* приглушённая подпись секции: kit красит h3 в --text, роль здесь другая */
    .section-title { margin: 0 0 10px; font-size: 12px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: .04em; }
    .section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
    .section-head .section-title { margin: 0; }
    .contact { display: flex; flex-direction: column; gap: 6px; font-size: 14px; }
    .c-name { font-weight: 600; }
    .c-company { font-weight: 400; color: var(--text-muted); }
    .c-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .c-row a:not(.btn) { color: var(--accent); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .c-same { font-size: 13px; color: var(--text-muted); display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
    .linklike { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; text-align: left; }
    .items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .items li { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; font-size: 14px; }
    .i-name { font-weight: 500; }
    .i-meta { color: var(--text-muted); font-size: 13px; }
    .i-link { color: var(--accent); font-size: 12px; text-decoration: none; }
    .empty-inline { color: var(--text-muted); font-size: 13px; }
    .items-edit { display: flex; flex-direction: column; gap: 8px; }
    .ie-row { display: grid; grid-template-columns: 1fr 140px 70px auto; gap: 6px; }
    .message { white-space: pre-wrap; margin: 0; font-size: 14px; background: var(--surface-2); border-radius: 8px; padding: 10px 12px; }
    .timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
    .timeline li { border-left: 2px solid var(--border); padding-left: 10px; }
    .timeline li[data-type="STATUS"] { border-left-color: var(--accent); }
    .timeline li[data-type="CALL"], .timeline li[data-type="MESSAGE"] { border-left-color: var(--success); }
    .timeline li[data-type="SYNC"] { border-left-color: var(--warn); }
    .t-head { display: flex; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--text-muted); }
    .t-type { font-weight: 600; color: var(--text); }
    .t-body { font-size: 14px; white-space: pre-wrap; margin-top: 2px; }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); }
    .st[data-status="NEW"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="IN_WORK"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .st[data-status="CONVERTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    @media (max-width: 900px) {
      .drawer { width: 100vw; }
      .head { padding: 14px 16px 10px; }
      .body { padding: 12px 16px 24px; }
      .ie-row { grid-template-columns: 1fr 80px auto; }
      .ie-row input:first-child { grid-column: 1 / -1; }
      .panel textarea, .panel select, .panel input, .items-edit input { font-size: 16px; }
    }
  `]
})
export class LeadCardComponent implements OnChanges {
  @Input() leadId: number | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() changed = new EventEmitter<void>();
  @Output() openLead = new EventEmitter<number>();

  lead: any = null;
  loading = false;
  busy = false;
  panel: Panel = 'none';
  noteText = '';
  callDir: 'IN' | 'OUT' = 'IN';
  callText = '';
  closeReason = '';
  closeComment = '';
  editItems: any[] = [];
  readonly reasons = LEAD_CLOSE_REASONS;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private cdr: ChangeDetectorRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (ch['leadId']) {
      this.panel = 'none';
      if (this.leadId != null) this.load(this.leadId);
      else this.lead = null;
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.leadId !== null) this.close.emit();
  }

  load(id: number) {
    this.loading = true;
    this.lead = null;
    this.cdr.detectChanges();
    this.api.getLead(id).subscribe({
      next: d => { this.lead = d; this.loading = false; this.cdr.detectChanges(); },
      error: e => {
        this.loading = false;
        this.notify.error('Обращение не открылось: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  /** Любое действие возвращает свежую карточку целиком — ею и заменяем текущую. */
  private apply(req: Observable<any>, ok: string) {
    this.busy = true;
    req.subscribe({
      next: d => {
        this.lead = d;
        this.busy = false;
        this.panel = 'none';
        this.notify.success(ok);
        this.changed.emit();
        this.cdr.detectChanges();
      },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не получилось'); this.cdr.detectChanges(); },
    });
  }

  take() { this.apply(this.api.takeLead(this.lead.id), 'Взято в работу'); }
  reopen() { this.apply(this.api.reopenLead(this.lead.id), 'Обращение снова в работе'); }
  doClose() { this.apply(this.api.closeLead(this.lead.id, this.closeReason, this.closeComment), 'Обращение закрыто'); }
  saveNote() { this.apply(this.api.addLeadEvent(this.lead.id, { type: 'NOTE', body: this.noteText.trim() }), 'Заметка добавлена'); }
  saveCall() {
    this.apply(this.api.addLeadEvent(this.lead.id, { type: 'CALL', direction: this.callDir, body: this.callText.trim() }), 'Звонок записан');
  }

  startItems() {
    this.editItems = this.lead.items.map((i: any) => ({ ...i }));
    if (!this.editItems.length) this.editItems.push({ name: '', brand: '', quantity: 1 });
    this.panel = 'items';
  }

  saveItems() {
    const items = this.editItems
      .filter(i => i.name && String(i.name).trim())
      .map(i => ({
        name: String(i.name).trim(),
        brand: i.brand && String(i.brand).trim() ? String(i.brand).trim() : null,
        quantity: parseInt(i.quantity, 10) || 1,
        productUrl: i.productUrl || null,
      }));
    this.apply(this.api.updateLeadItems(this.lead.id, items), 'Позиции сохранены');
  }

  togglePanel(p: Panel) {
    const opening = this.panel !== p;
    this.panel = opening ? p : 'none';
    if (!opening) return;
    if (p === 'note') this.noteText = '';
    if (p === 'call') { this.callText = ''; this.callDir = 'IN'; }
    if (p === 'close') { this.closeReason = ''; this.closeComment = ''; }
  }

  canEditItems() { return this.lead?.status === 'NEW' || this.lead?.status === 'IN_WORK'; }

  /** wa.me открывает чат с номером уже сейчас — без бизнес-API. */
  waLink(): string | null {
    const d = (this.lead?.phoneNorm || '').replace(/\D/g, '');
    return d.length >= 10 ? 'https://wa.me/' + d : null;
  }

  channelLabel() { return leadChannelLabel(this.lead.channel, this.lead.source); }
  statusLabel(s: string) { return LEAD_STATUS_LABELS[s] || s; }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }

  eventLabel(e: any): string {
    switch (e.type) {
      case 'RECEIVED': return 'Получено';
      case 'NOTE': return 'Заметка';
      case 'CALL': return e.direction === 'OUT' ? 'Исходящий звонок' : 'Входящий звонок';
      case 'MESSAGE': return e.direction === 'OUT' ? 'Сообщение клиенту' : 'Сообщение от клиента';
      case 'STATUS': return 'Статус';
      case 'SYNC': return 'Сайт';
      default: return e.type;
    }
  }
}
```

- [ ] **Step 2: Подключить карточку к списку**

В `frontend/src/app/pages/leads/leads.component.ts`:
- импорт `import { LeadCardComponent } from './lead-card.component';`;
- `imports: [NgFor, NgIf, FormsModule, RouterLink],` → `imports: [NgFor, NgIf, FormsModule, RouterLink, LeadCardComponent],`;
- в шаблоне после закрывающего `</div>` блока `lead-list` (последняя строка шаблона перед обратной кавычкой) добавить:
```html

    <app-lead-card [leadId]="cardId" (close)="closeCard()" (changed)="load(true)" (openLead)="open($event)"></app-lead-card>
```
- метод после `open(id)`:
```ts
  closeCard() {
    this.router.navigate([], { relativeTo: this.route, queryParams: { openId: null }, queryParamsHandling: 'merge' });
  }
```

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: без ошибок и без превышения бюджетов.

- [ ] **Step 4: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/leads/lead-card.component.ts frontend/src/app/pages/leads/leads.component.ts
git commit -m "$(printf 'feat(leads-ui): карточка обращения — контакт, позиции, лента, заметки, звонки, закрытие\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 13: Фронт — «Создать частную заявку», ручной ввод, обратная ссылка

**Files:**
- Create: `frontend/src/app/pages/leads/lead-convert-dialog.component.ts`, `frontend/src/app/pages/leads/lead-form.component.ts`
- Modify: `frontend/src/app/pages/leads/lead-card.component.ts` (кнопка + диалог + переход в заявку)
- Modify: `frontend/src/app/pages/leads/leads.component.ts` (кнопка «+ Обращение» + форма)
- Modify: `frontend/src/app/pages/private-requests/private-request-card.component.ts` (строка «Из обращения»)

**Interfaces:**
- Consumes: `ApiService.convertLead/createLead/getFacilities` (Task 11 и существующий), `LeadCardComponent` (Task 12), `PrivateRequestResponse.lead` (Task 5), `fullDateTime` (Task 11).
- Produces: `LeadConvertDialogComponent` (`app-lead-convert-dialog`: `@Input() lead`, `@Output() close`, `@Output() converted: {privateRequestId, number}`), экспорт `splitName(full)`; `LeadFormComponent` (`app-lead-form`: `@Output() close`, `@Output() created: lead`).

- [ ] **Step 1: Диалог превращения**

`frontend/src/app/pages/leads/lead-convert-dialog.component.ts`:
```ts
import { Component, Input, Output, EventEmitter, OnInit, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

/** ФИО по простому правилу спеки §10: 1 слово → имя; 2 → имя + фамилия; 3+ → фамилия, имя, отчество. Всё правится. */
export function splitName(full: string | null | undefined): { lastName: string; firstName: string; middleName: string } {
  const parts = (full || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return { lastName: '', firstName: '', middleName: '' };
  if (parts.length === 1) return { lastName: '', firstName: parts[0], middleName: '' };
  if (parts.length === 2) return { lastName: parts[1], firstName: parts[0], middleName: '' };
  return { lastName: parts[0], firstName: parts[1], middleName: parts.slice(2).join(' ') };
}

/** «Создать частную заявку» из обращения — сервер делает всё одной транзакцией (спека §9). */
@Component({
  selector: 'app-lead-convert-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="close.emit()">
      <div class="modal" role="dialog" aria-label="Частная заявка из обращения" (click)="$event.stopPropagation()">
        <h3>Частная заявка из обращения</h3>
        <div class="error-banner" *ngIf="error">{{ error }}</div>

        <div class="block">
          <div class="block-head">
            <span class="lbl">Клиент</span>
            <button type="button" class="btn btn-line" (click)="toggleNew()">{{ newMode ? '✕ Выбрать из списка' : '＋ Новый клиент' }}</button>
          </div>
          <select *ngIf="!newMode" class="client" [(ngModel)]="clientId" aria-label="Клиент">
            <option [ngValue]="null" disabled>Выберите клиента…</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>
          <div class="grid2" *ngIf="newMode">
            <label class="fld">Название<input [(ngModel)]="nc.name" maxlength="255" /></label>
            <label class="fld">Телефон<input [(ngModel)]="nc.phone" maxlength="50" inputmode="tel" /></label>
            <label class="fld">Email<input [(ngModel)]="nc.email" maxlength="255" type="email" /></label>
            <label class="fld">Фамилия<input [(ngModel)]="nc.lastName" maxlength="100" /></label>
            <label class="fld">Имя<input [(ngModel)]="nc.firstName" maxlength="100" /></label>
            <label class="fld">Отчество<input [(ngModel)]="nc.middleName" maxlength="100" /></label>
          </div>
        </div>

        <div class="block">
          <span class="lbl">Строки заявки</span>
          <p class="client-text" *ngIf="lead.message && !lead.items?.length">Текст клиента: {{ lead.message }}</p>
          <div class="ie-row" *ngFor="let l of lines; let idx = index">
            <input [(ngModel)]="l.name" placeholder="Наименование / модель" aria-label="Наименование" />
            <input [(ngModel)]="l.manufact" placeholder="Бренд" aria-label="Бренд" />
            <input type="number" min="1" [(ngModel)]="l.quantity" aria-label="Количество" />
            <button type="button" class="btn btn-cancel" (click)="removeLine(idx)" aria-label="Удалить строку">✕</button>
          </div>
          <div><button type="button" class="btn btn-line" (click)="lines.push({ name: '', manufact: '', quantity: 1 })">+ Строка</button></div>
        </div>

        <label class="fld">Примечание к заявке<textarea [(ngModel)]="note" rows="3" maxlength="5000"></textarea></label>

        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="submit()">{{ saving ? 'Создаю…' : 'Создать заявку' }}</button>
          <button type="button" class="btn btn-cancel" (click)="close.emit()">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* выше дровера карточки (z 1000); вуаль — самое частое значение в приложении */
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%; max-width: 680px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 14px; }
    .modal h3 { margin: 0; }
    .block { display: flex; flex-direction: column; gap: 8px; }
    .block-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .lbl { font-size: 13px; font-weight: 600; color: var(--text-muted); }
    .fld { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-muted); }
    .fld input, .fld textarea, .ie-row input, select.client { padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; font-size: 14px; background: var(--surface); color: var(--text); font-family: inherit; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .ie-row { display: grid; grid-template-columns: 1fr 140px 70px auto; gap: 6px; }
    .client-text { margin: 0; font-size: 13px; color: var(--text-muted); white-space: pre-wrap; background: var(--surface-2); border-radius: 8px; padding: 8px 10px; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .grid2 { grid-template-columns: 1fr; }
      .ie-row { grid-template-columns: 1fr 80px auto; }
      .ie-row input:first-child { grid-column: 1 / -1; }
      .fld input, .fld textarea, .ie-row input, select.client { font-size: 16px; }
    }
  `]
})
export class LeadConvertDialogComponent implements OnInit {
  @Input() lead: any;
  @Output() close = new EventEmitter<void>();
  @Output() converted = new EventEmitter<{ privateRequestId: number; number: string }>();

  facilities: any[] = [];
  clientId: number | null = null;
  newMode = false;
  nc = { name: '', phone: '', email: '', lastName: '', firstName: '', middleName: '' };
  lines: any[] = [];
  note = '';
  error = '';
  saving = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.api.getFacilities().subscribe({ next: d => { this.facilities = d; this.cdr.detectChanges(); } });
    this.clientId = this.lead.facilityId ?? null;
    this.newMode = this.clientId == null;   // клиент не узнан — сразу форма нового, заполненная из обращения
    this.nc = {
      name: this.lead.company || this.lead.contactName || '',
      phone: this.lead.contactPhone || '',
      email: this.lead.contactEmail || '',
      ...splitName(this.lead.contactName),
    };
    this.lines = (this.lead.items || []).map((i: any) => ({ name: i.name, manufact: i.brand || '', quantity: i.quantity || 1 }));
    if (!this.lines.length) this.lines.push({ name: '', manufact: '', quantity: 1 });
    this.note = this.lead.message || '';
  }

  @HostListener('document:keydown.escape')
  onEscape() { this.close.emit(); }

  toggleNew() { this.newMode = !this.newMode; this.error = ''; }

  removeLine(i: number) {
    this.lines.splice(i, 1);
    if (!this.lines.length) this.lines.push({ name: '', manufact: '', quantity: 1 });
  }

  submit() {
    const lines = this.lines
      .filter(l => l.name && String(l.name).trim())
      .map(l => ({
        name: String(l.name).trim(),
        manufact: l.manufact && String(l.manufact).trim() ? String(l.manufact).trim() : null,
        quantity: parseInt(l.quantity, 10) || 1,
      }));
    if (!lines.length) { this.error = 'Нужна хотя бы одна строка с наименованием'; return; }
    if (!this.newMode && this.clientId == null) { this.error = 'Выберите клиента или создайте нового'; return; }
    if (this.newMode && !this.nc.name.trim()) { this.error = 'Введите название клиента'; return; }
    const body: any = { note: this.note, lines };
    if (this.newMode) body.newClient = { ...this.nc, name: this.nc.name.trim() };
    else body.clientFacilityId = this.clientId;
    this.saving = true;
    this.error = '';
    this.api.convertLead(this.lead.id, body).subscribe({
      next: r => { this.saving = false; this.converted.emit(r); },
      error: e => { this.saving = false; this.error = e.error?.message || 'Не удалось создать заявку'; this.cdr.detectChanges(); },
    });
  }
}
```

Диалог и карточка оба слушают `document:keydown.escape`; чтобы Esc закрывал только диалог, карточка пропускает Esc, пока `convertOpen === true` (Step 3).

- [ ] **Step 2: Форма ручного обращения**

`frontend/src/app/pages/leads/lead-form.component.ts`:
```ts
import { Component, Output, EventEmitter, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

/** «+ Обращение»: звонок, WhatsApp или другое, внесённое вручную (спека §10). */
@Component({
  selector: 'app-lead-form',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="close.emit()">
      <div class="modal" role="dialog" aria-label="Новое обращение" (click)="$event.stopPropagation()">
        <h3>Новое обращение</h3>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
        <label class="fld">Канал
          <select [(ngModel)]="form.channel">
            <option value="PHONE">Звонок</option>
            <option value="WHATSAPP">WhatsApp</option>
            <option value="OTHER">Другое</option>
          </select>
        </label>
        <div class="grid2">
          <label class="fld">Имя<input [(ngModel)]="form.contactName" maxlength="255" /></label>
          <label class="fld">Телефон<input [(ngModel)]="form.contactPhone" maxlength="50" inputmode="tel" placeholder="+7 7XX XXX XX XX" /></label>
          <label class="fld">Компания / клиника<input [(ngModel)]="form.company" maxlength="255" /></label>
          <label class="fld">Email<input [(ngModel)]="form.contactEmail" maxlength="255" type="email" /></label>
        </div>
        <label class="fld">Что ищут
          <textarea [(ngModel)]="form.message" rows="4" maxlength="5000"
                    placeholder="Например: УЗИ-аппарат для гинекологии, бюджет до 10 млн"></textarea>
        </label>
        <div class="block">
          <span class="lbl">Позиции <span class="muted">(необязательно)</span></span>
          <div class="ie-row" *ngFor="let i of form.items; let idx = index">
            <input [(ngModel)]="i.name" placeholder="Наименование / модель" aria-label="Наименование" />
            <input [(ngModel)]="i.brand" placeholder="Бренд" aria-label="Бренд" />
            <input type="number" min="1" [(ngModel)]="i.quantity" aria-label="Количество" />
            <button type="button" class="btn btn-cancel" (click)="form.items.splice(idx, 1)" aria-label="Удалить строку">✕</button>
          </div>
          <div><button type="button" class="btn btn-line" (click)="form.items.push({ name: '', brand: '', quantity: 1 })">+ Позиция</button></div>
        </div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Создать' }}</button>
          <button type="button" class="btn btn-cancel" (click)="close.emit()">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%; max-width: 640px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    .modal h3 { margin: 0; }
    .fld { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-muted); }
    .fld input, .fld select, .fld textarea, .ie-row input { padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; font-size: 14px; background: var(--surface); color: var(--text); font-family: inherit; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .block { display: flex; flex-direction: column; gap: 8px; }
    .lbl { font-size: 13px; font-weight: 600; color: var(--text-muted); }
    .muted { font-weight: 400; }
    .ie-row { display: grid; grid-template-columns: 1fr 140px 70px auto; gap: 6px; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .grid2 { grid-template-columns: 1fr; }
      .ie-row { grid-template-columns: 1fr 80px auto; }
      .ie-row input:first-child { grid-column: 1 / -1; }
      .fld input, .fld select, .fld textarea, .ie-row input { font-size: 16px; }
    }
  `]
})
export class LeadFormComponent {
  @Output() close = new EventEmitter<void>();
  @Output() created = new EventEmitter<any>();

  form = { channel: 'PHONE', contactName: '', contactPhone: '', company: '', contactEmail: '', message: '', items: [] as any[] };
  error = '';
  saving = false;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  @HostListener('document:keydown.escape')
  onEscape() { this.close.emit(); }

  save() {
    if (!this.form.contactPhone.trim() && !this.form.contactEmail.trim()) {
      this.error = 'Укажите телефон или email клиента';
      return;
    }
    const items = this.form.items
      .filter(i => i.name && String(i.name).trim())
      .map(i => ({
        name: String(i.name).trim(),
        brand: i.brand && String(i.brand).trim() ? String(i.brand).trim() : null,
        quantity: parseInt(i.quantity, 10) || 1,
      }));
    this.saving = true;
    this.error = '';
    this.api.createLead({ ...this.form, items }).subscribe({
      next: lead => { this.saving = false; this.notify.success('Обращение создано'); this.created.emit(lead); },
      error: e => { this.saving = false; this.error = e.error?.message || 'Не удалось создать обращение'; this.cdr.detectChanges(); },
    });
  }
}
```

- [ ] **Step 3: Кнопка и диалог в карточке**

В `frontend/src/app/pages/leads/lead-card.component.ts`:
- `import { RouterLink } from '@angular/router';` → `import { Router, RouterLink } from '@angular/router';`; добавить `import { LeadConvertDialogComponent } from './lead-convert-dialog.component';`;
- `imports: [NgIf, NgFor, FormsModule, RouterLink],` → `imports: [NgIf, NgFor, FormsModule, RouterLink, LeadConvertDialogComponent],`;
- в шаблоне строку
```html
                <button type="button" class="btn btn-primary" *ngIf="lead.status === 'NEW'" [disabled]="busy" (click)="take()">Взять в работу</button>
```
дополнить следующей строкой сразу после неё:
```html
                <button type="button" class="btn btn-primary" *ngIf="lead.status === 'NEW' || lead.status === 'IN_WORK'" [disabled]="busy" (click)="convertOpen = true">Создать частную заявку</button>
```
- в конце шаблона, после закрывающего `</div>` оверлея (перед обратной кавычкой), добавить:
```html
    <app-lead-convert-dialog *ngIf="convertOpen && lead" [lead]="lead"
                             (close)="convertOpen = false" (converted)="onConverted($event)"></app-lead-convert-dialog>
```
- поле `convertOpen = false;` после `editItems: any[] = [];`;
- конструктор: добавить последним параметром `private router: Router`;
- в `onEscape` пропускать Esc, пока открыт диалог (иначе закроются оба окна):
```ts
  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.leadId !== null && !this.convertOpen) this.close.emit();
  }
```
- метод после `saveItems()`:
```ts
  onConverted(r: { privateRequestId: number; number: string }) {
    this.convertOpen = false;
    this.notify.success('Частная заявка ' + r.number + ' создана');
    this.changed.emit();
    this.router.navigate(['/private-requests'], { queryParams: { openId: r.privateRequestId } });
  }
```

- [ ] **Step 4: Кнопка «+ Обращение» в списке**

В `frontend/src/app/pages/leads/leads.component.ts`:
- импорт `import { LeadFormComponent } from './lead-form.component';` и добавить `LeadFormComponent` в `imports`;
- в шаблоне внутри `<div class="head-actions" *ngIf="auth.isAdmin()">` после кнопки «Проверить сейчас» добавить:
```html
        <button type="button" class="btn btn-primary" (click)="formOpen = true">+ Обращение</button>
```
- после `<app-lead-card …></app-lead-card>` добавить:
```html
    <app-lead-form *ngIf="formOpen" (close)="formOpen = false" (created)="onCreated($event)"></app-lead-form>
```
- поле `formOpen = false;` после `cardId: number | null = null;`; метод после `closeCard()`:
```ts
  onCreated(lead: any) {
    this.formOpen = false;
    this.load();
    this.open(lead.id);
  }
```

- [ ] **Step 5: Обратная ссылка в карточке частной заявки**

В `frontend/src/app/pages/private-requests/private-request-card.component.ts`:
- импорты `import { RouterLink } from '@angular/router';` и `import { fullDateTime } from '../../shared/relative-time';`;
- `imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe],` → `imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe, RouterLink],`;
- в шаблоне заменить
```html
              <span class="type-pill" *ngIf="request?.status">{{ request?.status }}</span>
            </div>
          </div>
```
на
```html
              <span class="type-pill" *ngIf="request?.status">{{ request?.status }}</span>
            </div>
            <div class="from-lead" *ngIf="request?.lead as ld">
              Из обращения:
              <a [routerLink]="['/leads']" [queryParams]="{ openId: ld.id }">{{ ld.subject }} · {{ leadSource(ld.source) }} · {{ leadDate(ld.receivedAt) }}</a>
            </div>
          </div>
```
- в стилях после строки `.close-btn:hover { … }` (она выше блока `@media`) добавить:
```css
    .from-lead { margin-top: 6px; font-size: 13px; color: var(--text-muted); }
    .from-lead a { color: var(--accent); text-decoration: none; }
```
- методы после конструктора:
```ts
  leadSource(source: string): string { return source === 'manual' ? 'внесено вручную' : source; }
  leadDate(iso: string): string { return fullDateTime(iso); }
```

- [ ] **Step 6: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: без ошибок и без превышения бюджетов (`anyComponentStyle` 24 kB — у каждого нового компонента свой).

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/leads/ \
  frontend/src/app/pages/private-requests/private-request-card.component.ts
git commit -m "$(printf 'feat(leads-ui): частная заявка из обращения, ручной ввод звонков, обратная ссылка\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 14: Полный гейт, живая проверка на локальной копии westmed, документация (делает контроллер, не субагент)

Нужны Docker, Playwright MCP и два работающих бэкенда. Боевой сайт westmed.kz НЕ трогаем: АИС смотрит в локальную копию.

**Files:**
- Modify: `CLAUDE.md`, `docs/PROGRESS.md`
- Локально, вне репо: `~/.config/ais/westmed-local.pass` (пароль учётки АИС в локальном westmed)

- [ ] **Step 1: Полный гейт**

Run: `lsof -ti :8080 | xargs kill -9; cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test`
Expected: 0 падений, 0 skipped; число тестов больше прежних 437 на ~65 новых.
Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: зелёная сборка.

- [ ] **Step 2: Поднять локальный westmed**

1. `open -a Docker`; дождаться `docker info` (Monitor с until-циклом — голый `sleep` в Bash заблокирован).
2. `cd ~/IdeaProjects/westmed && docker compose up -d postgres redis minio`.
3. Бэкенд сайта на порту **8181** (8080 занят АИС). Проверить JDK 21: `/usr/libexec/java_home -v 21`.
   - Есть: `cd ~/IdeaProjects/westmed/backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8181 ADMIN_PASSWORD='local-admin-pass' ./gradlew bootRun` (фоном, sandbox off).
   - Нет: `cd ~/IdeaProjects/westmed && docker compose run -d --name westmed-backend-local -p 8181:8080 -e ADMIN_PASSWORD='local-admin-pass' backend`.
4. Дождаться `curl -s -o /dev/null -w '%{http_code}' http://localhost:8181/api/health` = `200`.

- [ ] **Step 3: Учётка АИС в локальном westmed — репетиция шага раскатки §13**

```bash
mkdir -p ~/.config/ais && openssl rand -base64 18 > ~/.config/ais/westmed-local.pass && chmod 600 ~/.config/ais/westmed-local.pass
HASH=$(htpasswd -nbBC 10 x "$(cat ~/.config/ais/westmed-local.pass)" | cut -d: -f2)
docker exec -i westmed-postgres psql -U westmed -d westmed -v h="$HASH" <<'SQL'
INSERT INTO admin_users (id, email, password_hash, name, role, created_at)
VALUES (uuid_generate_v4(), 'ais@westmed.kz', :'h', 'АИС (интеграция)', 'MANAGER', now())
ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;
SQL
```
Пароль в чат не печатать (читать только через `$(cat …)`). `htpasswd` даёт `$2y$` — `BCryptPasswordEncoder` сайта его принимает.
Проверка: `curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8181/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"ais@westmed.kz\",\"password\":\"$(cat ~/.config/ais/westmed-local.pass)\"}"` → `200`.

- [ ] **Step 4: Три заявки через публичный API сайта — те же эндпоинты, что зовут формы**

1. Взять два товара: `curl -s 'http://localhost:8181/api/v1/products?size=2'` → запомнить `id`, `slug`, `name` (если каталог пуст — завести два товара в локальной админке сайта).
2. «Запрос цены» по товару: `POST /api/v1/requests` с `{"productId":"<id1>","name":"Айгерим ZZ","email":"zz1@example.kz","phone":"87771112233","company":"ТОО «ZZ Клиника»","message":"Сколько стоит?"}`.
3. Общая заявка текстом: `POST /api/v1/requests` с `{"name":"Иван ZZ","email":"zz2@example.kz","phone":"+7 777 444 55 66","message":"Найдите УЗИ-аппарат для гинекологии"}`.
4. «Запрос КП» корзиной: `POST /api/v1/quote-requests` с `{"name":"Айгерим ZZ","email":"zz1@example.kz","phone":"87771112233","items":[{"productSlug":"<slug1>","productName":"<name1>","quantity":2},{"productSlug":"<slug2>","productName":"<name2>","quantity":1}]}`.
(Лимит формы сайта — 5 POST в минуту с IP.)

- [ ] **Step 5: Поднять АИС, смотрящую в локальный westmed**

```bash
cd /Users/vlad/IdeaProjects/AIS && WESTMED_LEADS_ENABLED=true WESTMED_BASE_URL=http://localhost:8181 \
  WESTMED_USERNAME=ais@westmed.kz WESTMED_PASSWORD="$(cat ~/.config/ais/westmed-local.pass)" \
  WESTMED_WRITE_STATUS=true WESTMED_POLL_MS=15000 WESTMED_INITIAL_DELAY_MS=5000 \
  JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew bootRun
```
(фоном, sandbox off) + `cd frontend && npm start`.

- [ ] **Step 6: Playwright — сценарий целиком**

На `http://localhost:4200`, логин admin/admin, `localStorage.setItem('ais.market','KZ')`:
1. В меню «Заявки» первым — «Обращения» со счётчиком новых; иконка видна (`svg.children.length > 0`).
2. `/leads`: плашка «westmed.kz · синхронизировано …»; три новых обращения: «Запрос цены» (1 поз., бренд из каталога), «Заявка с сайта» (текст клиента), «Запрос КП · 2 поз.».
3. Карточка «Запрос КП»: бренды у позиций, «на сайте ↗», у телефона «Позвонить» и «Написать в WhatsApp» (`https://wa.me/77771112233`), «этот номер уже обращался» указывает на «Запрос цены» (тот же телефон).
4. «Взять в работу» → в течение ~15 с на сайте статус «В работе»: `curl` с токеном локального admin@westmed.kz на `GET /api/admin/quote-requests?status=PROCESSED` содержит эту заявку; в ленте АИС событие «Сайт: Статус на сайте: «В работе»».
5. «Создать частную заявку» с «➕ Новый клиент» (поля предзаполнены) → переход в карточку частной заявки, строка «Из обращения: Запрос КП · westmed.kz · …»; в обращении статус «Заявка создана».
6. «Заявка с сайта» → «Закрыть…» причиной «Спам» → на сайте «Закрыта».
7. «+ Обращение»: звонок, телефон, «ищут стерилизатор» → карточка открылась, статус «В работе», в ленте «внесено вручную».
8. Операторская роль: войти `operator/operator` → действия скрыты, карточка читается.
9. 390px и тёмная тема: список, карточка, оба диалога — без горизонтальной прокрутки (перед замером проверить `window.innerWidth`: `browser_navigate` сбрасывает вьюпорт на 1280, §12 CLAUDE.md).
10. Отказоустойчивость: остановить бэкенд westmed → плашка «westmed.kz: сайт недоступен…», АИС работает; поднять обратно → плашка зелёная сама.

Любой дефект — чинить на ветке (TDD, отдельный коммит), затем повторить шаг.

- [ ] **Step 7: Убрать локальный стенд**

Остановить бэкенд westmed (`lsof -ti :8181 | xargs kill -9` или `docker rm -f westmed-backend-local`), `cd ~/IdeaProjects/westmed && docker compose stop`. АИС перезапустить без переменных `WESTMED_*` — интеграция снова выключена.

- [ ] **Step 8: Документация**

`CLAUDE.md`:
- §8 — новый блок «**Обращения (коммерция West-Med)**»: модель `lead`/`lead_item`/`lead_event` (V18), точка входа `LeadIntakeService`, адаптер `integration/westmed` (API админки сайта; ⚠️ протухший токен → 403, не 401; карточку товара не звать — накручивает просмотры; стоп на странице без новых; мастер статусов — АИС, `ext_status_pending`), превращение в частную заявку одной транзакцией, фильтр уведомлений в `MailReceiveService`, страница `/leads`.
- §5 — как запускать локально с интеграцией (переменные `WESTMED_*`, `WESTMED_WRITE_STATUS=false` против боевого сайта).
- §15 — эндпоинты `/api/leads/*`.
- §16 — закрытое и открытое: доступ без Tailscale (следующий блок), адаптер vital-spb.kz, WhatsApp Business и АТС (задел готов), КП клиенту, почта info@ как обращения; хвосты, найденные на живой проверке.

`docs/PROGRESS.md` — запись сессии 2026-09-27: что сделано, цифры живой проверки, гейт (`cleanTest test` N/0/0, `npm run build`), что ждёт решения оператора (раскатка — Task 15).

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md docs/PROGRESS.md
git commit -m "$(printf 'docs: обращения — механика, запуск с интеграцией westmed, API, бэклог\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 15: Раскатка на прод — ТОЛЬКО после явного «да» оператора

Каждый пункт затрагивает боевые системы; перед ним — отдельное подтверждение. Без него — остановиться и доложить готовность.

- [ ] **Step 1: Мерж ветки** — по решению оператора: `git checkout main && git merge --no-ff feature/leads-westmed-intake`, гейт на смерженном дереве (`./gradlew cleanTest test`, `npm run build`). `git push origin main` = автодеплой (~3–5 мин); приём заявок на проде при этом ещё выключен (дефолт `WESTMED_LEADS_ENABLED=false`).
- [ ] **Step 2: Учётка АИС на боевом сайте** — как в Task 14 Step 3, но: пароль генерируется локально в `~/.config/ais/westmed-prod.pass`, на сервер уходит ТОЛЬКО bcrypt-хеш; `INSERT` в `westmed-postgres` на 185.125.46.26 через `docker exec -i … psql` (heredoc, `-v h=`). Код сайта не меняется.
- [ ] **Step 3: `.env` АИС на сервере** — в `/srv/ais/.env` дописать `WESTMED_LEADS_ENABLED=true`, `WESTMED_USERNAME=ais@westmed.kz`, `WESTMED_PASSWORD=<из westmed-prod.pass>`, `WESTMED_WRITE_STATUS=true`; `docker compose up -d --force-recreate ais-backend`.
- [ ] **Step 4: Проверка на проде** — `docker compose logs ais-backend | grep -i westmed` без ошибок; на `/leads` плашка «синхронизировано», импортирована история заявок сайта; «Взять в работу» у одной заявки → в админке сайта «В работе». Если контейнер АИС не достаёт до собственного публичного IP (hairpin) — фолбэк из спеки §13: подключить `ais-backend` к внешней сети `westmed` в `docker-compose.yml` АИС и поставить `WESTMED_BASE_URL=http://westmed-backend:8080`.
- [ ] **Step 5: Итог оператору** — сколько заявок пришло историей, сколько новых за первые сутки, ссылка на §16 с открытыми вопросами.
