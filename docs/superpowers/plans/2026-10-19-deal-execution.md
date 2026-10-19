# Исполнение сделки — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **В этом проекте — superpowers:executing-plans, всё в основной сессии, без субагентов** (CLAUDE.md §2, решение оператора 2026-10-18).

**Goal:** После «Клиент согласился» частная сделка ведётся в АИС до конца: договор или счёт, платежи клиента, поставщики (заказ, оплата, отгрузка по каждому), поставка, закрывающие документы и файлы к этапам; этапы, «Дальше», лента и чип в списке ведут сделку до «купили у нас».

**Architecture:** Четыре новые таблицы V34 на `tender` (`deal_execution`, `deal_supplier`, `deal_payment`, `deal_file`), не рыночные — только через заявку с гардом рынка. Чистая функция `DealExecutionCalculator` считает пять новых шагов и «Дальше» по снимку `ExecutionSnapshot`; `DealExecutionLoader` собирает снимок шестью запросами на любую выборку сделок (страница, список, лента). Запись — `DealExecutionService`, API — `DealExecutionController` под `/api/private-requests/{id}/execution`, раздел страницы — `pr-execution` (+ поставщики, форма отметки, файлы, окно файла).

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA / Hibernate 6, Flyway, PostgreSQL 17, Lombok; Angular 21 (standalone, инлайн-шаблоны).

**Spec:** `docs/superpowers/specs/2026-10-19-deal-execution-design.md` (§14 — решения при планировании).

## Global Constraints

- Миграция — **V34** (следующая свободная); старые миграции не трогать.
- Новые таблицы не рыночные: читать только после `PrivateRequestService.findById(id)` (частная заявка текущего рынка, иначе 404). `findById` других сущностей фильтр рынка не режет — у поставщика рынок сверять явно.
- Запись — `@PreAuthorize("hasRole('ADMIN')")`, оператор только читает (вид заявки, скачать файл).
- Ввод проверяется в сервисе до первой записи; отказ — `BadRequestException` (400) с русским текстом, никогда 500.
- Суммы: > 0, не больше 13 цифр до запятой и 2 после (≤ 9 999 999 999 999,99 — NUMERIC(15,2)); масштаб и число цифр — до округления.
- Даты отметок и платежей — не позже сегодняшней по часам рынка (`Market.zone()`: KZ — Asia/Oral, RF — Europe/Samara).
- Предоплата 0…100 % (по умолчанию 100); заметки ≤ 2000 символов; № договора ≤ 100; валюта поставщика — KZT / RUB / USD / EUR / CNY.
- Файл с компьютера ≤ 15 МБ (предел multipart); файл переписки — только из эпизода обращения этой заявки.
- Тексты денег: копейки только ненулевые, пробелы и пробел перед валютой — неразрывные (U+00A0), валюта словом: тг, руб., $, €, юань.
- Фронт: новое — только в ленивом чанке страницы заявки; `ApiService` берёт типы через `import type`; токены UI-kit, обе темы, цели 40 px при ≤ 900 px; классы компонентов не совпадают с глобальными классами UI-kit (`.empty`, `.subtitle`, `.counter`, `.badge`, `.btn`, `.form-actions`, `.field-error`, `.error-banner`); выбранный `<option>` в `*ngFor` — через `[selected]`; объекты-входы в шаблоне — литералами (`{ … }`), не методами (иначе NG0100 в dev-режиме).
- `./gradlew` — с `dangerouslyDisableSandbox: true` (песочница режет localhost:5432); перед полным прогоном глушить `bootRun` и `devMail` (CLAUDE.md §13); полный прогон — `./gradlew cleanTest test`.
- Ветка `feature/deal-execution`; каждый коммит заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Мутация — откат копией файла (`cp файл /tmp/x && … && cp /tmp/x файл && diff -q`), не `git checkout --`; серию мутаций заканчивать `./gradlew compileJava`.

## Review Focus

1. **КП правили после принятия** — сумма сделки и «к оплате по КП» идут за КП, пока не вписаны свои; своя сумма не плывёт → тест `offerEditedAfterAcceptance_amountsFollowUntilOwn` (задача 3).
2. **Полночь по Уральску** — платёж «сегодня» в 00:30 по Уральску (19:30 UTC прошлого дня) принимается датой рынка, не UTC → тест `todayIsMarketDate_notUtc` (задача 5).
3. **Двойное нажатие «Приложить»** — тот же файл к тому же месту второй раз не прикладывается, к другому месту — прикладывается → тест `sameFileTwiceSamePlace_oneRow_otherPlace_newRow` (задача 7).
4. **Смена валюты поставщика после платежей** — 400, платежи не «переезжают» в новую валюту → тест `currencyChangeWithPayments_400` (задача 6).
5. **КП вернули из «Принято» с уже внесёнными данными** — шаги по данным, «Дальше» и чип — про КП, раздел виден → тесты `offerRevertedAfterExecution_stepsByDataNextAboutOffer` (задача 4) и `revertedOfferKeepsSection` (задача 8).

## Карта файлов

Сервер (`src/main/java/com/vladoose/nir/…`):

| Файл | Что |
|---|---|
| `resources/db/migration/V34__deal_execution.sql` | четыре таблицы (спека §5) |
| `entity/ContractKind.java`, `entity/DealFileStage.java` | перечисления |
| `entity/DealExecution.java`, `DealSupplier.java`, `DealPayment.java`, `DealFile.java` | сущности; у первых двух `@DynamicUpdate` |
| `repository/DealExecutionRepository.java` … `DealFileRepository.java` | репозитории |
| `dto/response/DealFileMeta.java` | метаданные файла без байтов (проекция) |
| `service/execution/ExecutionSnapshot.java` | снимок для расчёта (+ `SupplierState`) |
| `service/execution/ExecutionTexts.java` | деньги, проценты, «ДД.ММ» |
| `service/execution/DealExecutionCalculator.java` | шаги, «Дальше», код этапа — чистая функция |
| `service/execution/DealExecutionData.java`, `DealExecutionLoader.java` | загрузка: КП ∪ строки → поставщики, снимок |
| `service/execution/ExecutionInput.java` | проверка ввода (чистые функции) |
| `service/execution/DealExecutionService.java` | запись: поля, отметки, платежи, поставщики, файлы |
| `service/execution/DealExecutionViews.java`, `dto/response/DealExecutionView.java` | вид раздела в ответе заявки |
| `dto/request/ExecutionField.java`, `ExecutionFieldUpdate.java`, `ExecutionMark.java`, `ExecutionMarkRequest.java`, `DealPaymentRequest.java`, `DealSupplierAddRequest.java`, `SupplierField.java`, `SupplierFieldUpdate.java`, `SupplierMark.java` | запросы |
| `dto/response/ChatEpisodeFile.java`, `ExecutionFileCandidate.java` | файлы переписки сделки |
| `controller/DealExecutionController.java` | API §9 |
| изменяются: `repository/ClientOfferRepository.java`, `DistributorRepository.java`, `ChatAttachmentRepository.java`, `service/offer/OfferLabels.java`, `service/privaterequest/PrivateRequestStageCalculator.java`, `PrivateRequestProgressBuilder.java`, `PrivateRequestViewBuilder.java`, `dto/response/PrivateRequestViewResponse.java`, `service/deal/DealTimelineService.java` | |

Фронт (`frontend/src/app/…`):

| Файл | Что |
|---|---|
| `shared/deal-execution.ts` | типы, валюты, `execMoney`, `filesAt` |
| `pages/private-requests/pr-execution.component.ts` | раздел: сумма, договор, оплата клиента, поставка, документы, окно файла |
| `pages/private-requests/pr-exec-suppliers.component.ts` | поставщики |
| `pages/private-requests/pr-exec-entry-form.component.ts` | форма «дата [+ сумма] + заметка» |
| `pages/private-requests/pr-exec-files.component.ts` | файлы места: скачать / удалить |
| `pages/private-requests/pr-exec-file-dialog.component.ts` | окно «Из переписки / С компьютера» |
| изменяются: `services/api.service.ts`, `shared/private-request.ts`, `shared/deal.ts`, `shared/deal-timeline.component.ts`, `pages/private-requests/private-request-page.component.ts` | |

Тесты: чистые — `src/test/java/com/vladoose/nir/service/execution/` (доступ к пакетным методам), интеграционные — `src/test/java/com/vladoose/nir/execution/`.

---

### Task 1: Схема V34, сущности, репозитории

**Files:**
- Create: `src/main/resources/db/migration/V34__deal_execution.sql`
- Create: `src/main/java/com/vladoose/nir/entity/ContractKind.java`, `DealFileStage.java`, `DealExecution.java`, `DealSupplier.java`, `DealPayment.java`, `DealFile.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/DealFileMeta.java`
- Create: `src/main/java/com/vladoose/nir/repository/DealExecutionRepository.java`, `DealSupplierRepository.java`, `DealPaymentRepository.java`, `DealFileRepository.java`
- Test: `src/test/java/com/vladoose/nir/execution/DealExecutionSchemaTest.java`

**Interfaces:**
- Produces: сущности `DealExecution` (id = `tenderId`), `DealSupplier`, `DealPayment`, `DealFile`; перечисления `ContractKind {CONTRACT, INVOICE, NONE}`, `DealFileStage {CONTRACT, CLIENT_PAYMENT, SUPPLIER, DELIVERY, DOCS}`; `DealFileMeta(Long id, DealFileStage stage, Long paymentId, Long dealSupplierId, String name, long size, String sha256, String uploadedBy, OffsetDateTime uploadedAt)`; репозитории:
  - `DealExecutionRepository.findByTenderIdIn(Collection<Long>)`
  - `DealSupplierRepository.findByTenderIdIn(Collection<Long>)`, `findByTenderIdAndDistributorId(Long, Long)`, `existsByTenderIdAndOrderedOnIsNotNull(Long)`, `existsByTenderIdAndShippedOnIsNotNull(Long)`
  - `DealPaymentRepository.findByTenderIdIn(Collection<Long>)`, `existsByDealSupplierId(Long)`, `existsByTenderIdAndDealSupplierIdIsNotNull(Long)`
  - `DealFileRepository.findMetaByTenderId(Long)`, `findByIdAndTenderId(Long, Long)`, `existsByTenderIdAndStage(Long, DealFileStage)`

- [ ] **Step 0: Ветка**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/deal-execution
```

- [ ] **Step 1: Тест схемы (красный — классов ещё нет)**

`src/test/java/com/vladoose/nir/execution/DealExecutionSchemaTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Таблицы исполнения сделки (V34, спека 2026-10-19-deal-execution §5): каскады, ограничения, проекция файлов. */
@SpringBootTest
@Transactional
class DealExecutionSchemaTest {

    @Autowired PrivateRequestService requests;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired DealExecutionRepository executions;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DealPaymentRepository payments;
    @Autowired DealFileRepository files;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor distributor() {
        return distributors.save(Distributor.builder().name("ZZ Исполнение " + System.nanoTime()).build());
    }

    private DealSupplier supplier(Tender t, Long distributorId) {
        return suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(distributorId).currency("KZT").build());
    }

    private DealPayment payment(Tender t, Long supplierRow, String amount) {
        return payments.save(DealPayment.builder().tenderId(t.getId()).dealSupplierId(supplierRow)
                .amount(new BigDecimal(amount)).paidOn(DAY).build());
    }

    private DealFile file(Tender t, DealFileStage stage, Long paymentId, Long supplierRow) {
        byte[] b = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        return files.save(DealFile.builder().tenderId(t.getId()).stage(stage).paymentId(paymentId).dealSupplierId(supplierRow)
                .fileName("Договор 42.pdf").mimeType("application/pdf").sizeBytes(b.length).sha256("ab").content(b).build());
    }

    private long count(String table, Long tenderId) {
        return jdbc.queryForObject("select count(*) from " + table + " where tender_id = ?", Long.class, tenderId);
    }

    @Test
    void rowsLiveWithDealAndGoWithIt() {
        Tender t = requests.createEmpty();
        executions.save(DealExecution.builder().tenderId(t.getId()).contractKind(ContractKind.CONTRACT).contractNumber("42").build());
        DealSupplier s = supplier(t, distributor().getId());
        DealPayment p = payment(t, null, "100.00");
        payment(t, s.getId(), "50.00");
        file(t, DealFileStage.CLIENT_PAYMENT, p.getId(), null);
        em.flush();
        em.clear();

        DealExecution back = executions.findById(t.getId()).orElseThrow();
        assertThat(back.getPrepayPct()).isEqualByComparingTo("100");
        assertThat(back.getUpdatedAt()).isNotNull();
        List<DealFileMeta> meta = files.findMetaByTenderId(t.getId());
        assertThat(meta).singleElement().satisfies(m -> {
            assertThat(m.stage()).isEqualTo(DealFileStage.CLIENT_PAYMENT);
            assertThat(m.paymentId()).isEqualTo(p.getId());
            assertThat(m.name()).isEqualTo("Договор 42.pdf");
            assertThat(m.size()).isEqualTo(8);
        });

        jdbc.update("delete from tender where id = ?", t.getId());
        for (String table : List.of("deal_execution", "deal_supplier", "deal_payment", "deal_file")) {
            assertThat(count(table, t.getId())).as(table).isZero();
        }
    }

    @Test
    void deletingSupplierTakesItsPaymentsAndFiles() {
        Tender t = requests.createEmpty();
        DealSupplier s = supplier(t, distributor().getId());
        DealPayment p = payment(t, s.getId(), "50.00");
        file(t, DealFileStage.SUPPLIER, p.getId(), s.getId());
        em.flush();
        jdbc.update("delete from deal_supplier where id = ?", s.getId());
        assertThat(count("deal_payment", t.getId())).isZero();
        assertThat(count("deal_file", t.getId())).isZero();
    }

    @Test
    void distributorWithDealSupplierCannotBeDeleted() {
        Tender t = requests.createEmpty();
        Distributor d = distributor();
        supplier(t, d.getId());
        em.flush();
        assertThatThrownBy(() -> jdbc.update("delete from distributor where id = ?", d.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paymentAmountMustBePositive() {
        Tender t = requests.createEmpty();
        assertThatThrownBy(() -> payments.saveAndFlush(DealPayment.builder().tenderId(t.getId())
                .amount(BigDecimal.ZERO).paidOn(DAY).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneRowPerSupplierInDeal() {
        Tender t = requests.createEmpty();
        Long d = distributor().getId();
        suppliers.saveAndFlush(DealSupplier.builder().tenderId(t.getId()).distributorId(d).currency("KZT").build());
        assertThatThrownBy(() -> suppliers.saveAndFlush(DealSupplier.builder().tenderId(t.getId()).distributorId(d)
                .currency("KZT").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Запустить — красный (компиляция: нет классов)**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionSchemaTest'`
Expected: FAIL — `cannot find symbol: class DealExecution` (и остальных).

- [ ] **Step 3: Миграция V34**

`src/main/resources/db/migration/V34__deal_execution.sql`:

```sql
-- Исполнение сделки (2026-10-19, спека 2026-10-19-deal-execution-design §5): после «Клиент согласился» — договор,
-- оплата клиента, поставщики (заказ, оплата, отгрузка), поставка, закрывающие документы и файлы этапов. Таблицы
-- привязаны к tender — подходят и частной заявке, и тендеру (подпроект 4). Не рыночные: читаются только через заявку
-- с гардом рынка (как deal_event). contract_kind / stage / currency проверяет сервис — без CHECK, как у прежних таблиц.

-- Одна строка на сделку, заводится при первой записи
CREATE TABLE deal_execution (
    tender_id        BIGINT         PRIMARY KEY REFERENCES tender (id) ON DELETE CASCADE,
    contract_kind    VARCHAR(10),                 -- CONTRACT / INVOICE / NONE («без договора»); пусто — не внесено
    contract_number  VARCHAR(100),
    contract_date    DATE,
    contract_note    TEXT,
    prepay_pct       NUMERIC(5, 2)  NOT NULL DEFAULT 100,   -- 0 — оплата после поставки
    amount           NUMERIC(15, 2),              -- своя сумма сделки; пусто — итог главного принятого КП
    from_stock       BOOLEAN        NOT NULL DEFAULT false, -- «Со склада»
    delivered_on     DATE,
    delivery_note    TEXT,
    docs_on          DATE,
    docs_note        TEXT,
    updated_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Поставщик сделки; поставщик из КП заводится строкой при первой записи (до неё — из КП на лету)
CREATE TABLE deal_supplier (
    id              BIGSERIAL      PRIMARY KEY,
    tender_id       BIGINT         NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    distributor_id  BIGINT         NOT NULL REFERENCES distributor (id),
    amount          NUMERIC(15, 2),               -- к оплате; пусто — по КП
    currency        VARCHAR(3)     NOT NULL,      -- KZT / RUB / USD / EUR / CNY
    excluded        BOOLEAN        NOT NULL DEFAULT false,  -- «Не заказываем»
    manual          BOOLEAN        NOT NULL DEFAULT false,  -- добавлен вручную
    ordered_on      DATE,
    order_note      TEXT,
    shipped_on      DATE,
    ship_note       TEXT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (tender_id, distributor_id)
);

-- Платёж: поставщик пуст — платёж клиента (валюта рынка), иначе — поставщику (его валюта)
CREATE TABLE deal_payment (
    id                BIGSERIAL      PRIMARY KEY,
    tender_id         BIGINT         NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    deal_supplier_id  BIGINT         REFERENCES deal_supplier (id) ON DELETE CASCADE,
    amount            NUMERIC(15, 2) NOT NULL CHECK (amount > 0),
    paid_on           DATE           NOT NULL,
    note              TEXT,
    created_by        VARCHAR(100),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_payment_tender ON deal_payment (tender_id);
CREATE INDEX idx_deal_payment_supplier ON deal_payment (deal_supplier_id);

-- Файл этапа: к платежу — payment_id, к поставщику — deal_supplier_id
CREATE TABLE deal_file (
    id                  BIGSERIAL      PRIMARY KEY,
    tender_id           BIGINT         NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    stage               VARCHAR(20)    NOT NULL,  -- CONTRACT / CLIENT_PAYMENT / SUPPLIER / DELIVERY / DOCS
    payment_id          BIGINT         REFERENCES deal_payment (id) ON DELETE CASCADE,
    deal_supplier_id    BIGINT         REFERENCES deal_supplier (id) ON DELETE CASCADE,
    file_name           VARCHAR(255)   NOT NULL,
    mime_type           VARCHAR(100),
    size_bytes          BIGINT         NOT NULL,
    sha256              VARCHAR(64)    NOT NULL,
    content             BYTEA          NOT NULL,
    chat_attachment_id  BIGINT         REFERENCES chat_attachment (id) ON DELETE SET NULL,  -- из какого файла переписки
    uploaded_by         VARCHAR(100),
    uploaded_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_file_tender ON deal_file (tender_id);
```

- [ ] **Step 4: Перечисления и сущности**

`entity/ContractKind.java`:

```java
package com.vladoose.nir.entity;

/** Документ с клиентом в исполнении сделки (V34): договор, счёт или «без договора». */
public enum ContractKind { CONTRACT, INVOICE, NONE }
```

`entity/DealFileStage.java`:

```java
package com.vladoose.nir.entity;

/** Место файла исполнения (V34): договор, платёж клиента, поставщик (его платёж — с payment_id), поставка, документы. */
public enum DealFileStage { CONTRACT, CLIENT_PAYMENT, SUPPLIER, DELIVERY, DOCS }
```

`entity/DealExecution.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Исполнение сделки (V34): договор, предоплата, своя сумма, «Со склада», поставка, документы — одна строка на сделку.
 * Не рыночная: только через заявку с гардом рынка. @DynamicUpdate — две вкладки, правящие разные поля, не затирают
 * друг друга (как Tender).
 */
@Entity
@Table(name = "deal_execution")
@DynamicUpdate
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealExecution {

    @Id
    @Column(name = "tender_id")
    private Long tenderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_kind", length = 10)
    private ContractKind contractKind;

    @Column(name = "contract_number", length = 100)
    private String contractNumber;

    @Column(name = "contract_date")
    private LocalDate contractDate;

    @Column(name = "contract_note", columnDefinition = "TEXT")
    private String contractNote;

    /** Предоплата, %: 100 — всё вперёд, 0 — оплата после поставки. */
    @Column(name = "prepay_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal prepayPct = new BigDecimal("100.00");

    /** Своя сумма сделки; пусто — итог главного принятого КП. */
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "from_stock", nullable = false)
    private boolean fromStock;

    @Column(name = "delivered_on")
    private LocalDate deliveredOn;

    @Column(name = "delivery_note", columnDefinition = "TEXT")
    private String deliveryNote;

    @Column(name = "docs_on")
    private LocalDate docsOn;

    @Column(name = "docs_note", columnDefinition = "TEXT")
    private String docsNote;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
```

`entity/DealSupplier.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Поставщик сделки (V34): к оплате, валюта, «Не заказываем», заказ и отгрузка. Платежи — DealPayment. */
@Entity
@Table(name = "deal_supplier")
@DynamicUpdate
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "distributor_id", nullable = false)
    private Long distributorId;

    /** К оплате; пусто — по КП (только в валюте рынка). */
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean excluded;

    @Column(nullable = false)
    private boolean manual;

    @Column(name = "ordered_on")
    private LocalDate orderedOn;

    @Column(name = "order_note", columnDefinition = "TEXT")
    private String orderNote;

    @Column(name = "shipped_on")
    private LocalDate shippedOn;

    @Column(name = "ship_note", columnDefinition = "TEXT")
    private String shipNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

`entity/DealPayment.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Платёж исполнения (V34): dealSupplierId пусто — платёж клиента (валюта рынка), иначе — поставщику (его валюта). */
@Entity
@Table(name = "deal_payment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "deal_supplier_id")
    private Long dealSupplierId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

`entity/DealFile.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Файл этапа исполнения (V34). Байты читаются только при скачивании — вид берёт метаданные проекцией (DealFileMeta). */
@Entity
@Table(name = "deal_file")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DealFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DealFileStage stage;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "deal_supplier_id")
    private Long dealSupplierId;

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

- [ ] **Step 5: Проекция и репозитории**

`dto/response/DealFileMeta.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.DealFileStage;

import java.time.OffsetDateTime;

/** Файл исполнения без байтов: вид раздела, лента, проверка повтора. */
public record DealFileMeta(Long id, DealFileStage stage, Long paymentId, Long dealSupplierId, String name, long size,
                           String sha256, String uploadedBy, OffsetDateTime uploadedAt) {}
```

`repository/DealExecutionRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DealExecutionRepository extends JpaRepository<DealExecution, Long> {
    List<DealExecution> findByTenderIdIn(Collection<Long> tenderIds);
}
```

`repository/DealSupplierRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealSupplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DealSupplierRepository extends JpaRepository<DealSupplier, Long> {
    List<DealSupplier> findByTenderIdIn(Collection<Long> tenderIds);
    Optional<DealSupplier> findByTenderIdAndDistributorId(Long tenderId, Long distributorId);
    boolean existsByTenderIdAndOrderedOnIsNotNull(Long tenderId);
    boolean existsByTenderIdAndShippedOnIsNotNull(Long tenderId);
}
```

`repository/DealPaymentRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DealPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DealPaymentRepository extends JpaRepository<DealPayment, Long> {
    List<DealPayment> findByTenderIdIn(Collection<Long> tenderIds);
    boolean existsByDealSupplierId(Long dealSupplierId);
    boolean existsByTenderIdAndDealSupplierIdIsNotNull(Long tenderId);
}
```

`repository/DealFileRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.entity.DealFile;
import com.vladoose.nir.entity.DealFileStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DealFileRepository extends JpaRepository<DealFile, Long> {

    @Query("""
           select new com.vladoose.nir.dto.response.DealFileMeta(f.id, f.stage, f.paymentId, f.dealSupplierId, f.fileName,
               f.sizeBytes, f.sha256, f.uploadedBy, f.uploadedAt)
           from DealFile f where f.tenderId = :tenderId order by f.uploadedAt asc, f.id asc""")
    List<DealFileMeta> findMetaByTenderId(@Param("tenderId") Long tenderId);

    Optional<DealFile> findByIdAndTenderId(Long id, Long tenderId);

    boolean existsByTenderIdAndStage(Long tenderId, DealFileStage stage);
}
```

- [ ] **Step 6: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionSchemaTest'`
Expected: PASS (5 тестов; Flyway применил V34 к nirdb).

- [ ] **Step 7: Мутация**

В V34 нельзя править уже применённую миграцию — мутация на сущности: в `DealFileRepository.findMetaByTenderId` поменять `f.sizeBytes` на `0L` → `rowsLiveWithDealAndGoWithIt` красный (`size` 0 ≠ 8) → вернуть копией.

- [ ] **Step 8: Коммит**

```bash
git add src/main/resources/db/migration/V34__deal_execution.sql src/main/java/com/vladoose/nir/entity/ContractKind.java \
  src/main/java/com/vladoose/nir/entity/DealFileStage.java src/main/java/com/vladoose/nir/entity/DealExecution.java \
  src/main/java/com/vladoose/nir/entity/DealSupplier.java src/main/java/com/vladoose/nir/entity/DealPayment.java \
  src/main/java/com/vladoose/nir/entity/DealFile.java src/main/java/com/vladoose/nir/dto/response/DealFileMeta.java \
  src/main/java/com/vladoose/nir/repository/Deal*Repository.java src/test/java/com/vladoose/nir/execution/DealExecutionSchemaTest.java
git commit -m "$(cat <<'EOF'
feat(execution): V34 — таблицы исполнения сделки: договор, поставщики, платежи, файлы этапов

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 2: Расчёт шагов и «Дальше» — чистая функция

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/execution/ExecutionSnapshot.java`
- Create: `src/main/java/com/vladoose/nir/service/execution/ExecutionTexts.java`
- Create: `src/main/java/com/vladoose/nir/service/execution/DealExecutionCalculator.java`
- Test: `src/test/java/com/vladoose/nir/service/execution/DealExecutionCalculatorTest.java`

**Interfaces:**
- Consumes: `ContractKind` (задача 1), `DealProgress.Step` / `DealProgress.Next` (есть).
- Produces:
  - `ExecutionSnapshot` (record, `@Builder(toBuilder = true)`): `currency, offerLabel, amount, contractKind, contractNumber, contractDate, prepayPct, clientPaid, lastClientPaidOn, fromStock, suppliers, deliveredOn, docsOn, hasData`; методы `amountKnown()`, `contractSkipped()`, `contractDone()`, `prepayAmount()`, `paymentDone()`, `counted()`, `suppliersDone()`, `deliveryDone()`, `docsDone()`.
  - `ExecutionSnapshot.SupplierState` (record, `@Builder(toBuilder = true)`): `distributorId, name, excluded, currency, amount, orderedOn, paid, shippedOn`; `amountKnown()`, `paidUp()`, `done()`.
  - `ExecutionTexts.money(BigDecimal, String currencyOrNull)`, `currency(String)`, `pct(BigDecimal)`, `ddMM(LocalDate)`.
  - `DealExecutionCalculator.evaluate(ExecutionSnapshot, Mode) → Result(List<Step> steps, Next next, String stageCode, String stageText, boolean allDone)`; `Mode {ACTIVE, PASSIVE, CLOSED}`; ключи шагов `CONTRACT, PAYMENT, SUPPLIERS, DELIVERY, DOCS`; коды действий `EXEC_CONTRACT, EXEC_AMOUNT, EXEC_PAY_CLIENT, EXEC_SUPPLIERS, EXEC_ORDER, EXEC_SUPPLIER_AMOUNT, EXEC_PAY_SUPPLIER, EXEC_SHIP, EXEC_DELIVERY, EXEC_DOCS, CLOSE_WON`; коды этапа `EXEC_CONTRACT, EXEC_PREPAY, EXEC_ORDER, EXEC_PAY_SUPPLIERS, EXEC_SHIPPING, EXEC_DELIVERY, EXEC_DOCS, EXEC_FINAL_PAY, EXEC_DONE, EXEC_AMOUNT`.

- [ ] **Step 1: Тест (красный — классов нет)**

`src/test/java/com/vladoose/nir/service/execution/DealExecutionCalculatorTest.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.service.execution.DealExecutionCalculator.Mode;
import com.vladoose.nir.service.execution.DealExecutionCalculator.Result;
import com.vladoose.nir.service.execution.ExecutionSnapshot.SupplierState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/** Шаги исполнения и «Дальше» (спека 2026-10-19-deal-execution §3) — по таблице §3.2. */
class DealExecutionCalculatorTest {

    static final LocalDate D20 = LocalDate.of(2026, 10, 20);
    static final LocalDate D21 = LocalDate.of(2026, 10, 21);
    static final LocalDate D25 = LocalDate.of(2026, 10, 25);

    /** Пробелы в суммах и процентах — неразрывные, как в ExecutionTexts. */
    static String nb(String s) { return s.replace(' ', ' '); }

    static SupplierState.SupplierStateBuilder fotek() {
        return SupplierState.builder().distributorId(1L).name("ФОТЕК").currency("KZT").amount(new BigDecimal("1662150.00"));
    }

    static SupplierState.SupplierStateBuilder medsyst() {
        return SupplierState.builder().distributorId(2L).name("MEDSYST").currency("KZT").amount(new BigDecimal("114000.00"));
    }

    static SupplierState done(SupplierState.SupplierStateBuilder b) {
        SupplierState s = b.build();
        return s.toBuilder().orderedOn(D21).paid(s.amount()).shippedOn(D21).build();
    }

    static ExecutionSnapshot.ExecutionSnapshotBuilder accepted() {
        return ExecutionSnapshot.builder().currency("KZT").offerLabel("КП № 481").amount(new BigDecimal("3070455.00"))
                .suppliers(List.of(fotek().build(), medsyst().build()));
    }

    static ExecutionSnapshot.ExecutionSnapshotBuilder contract(ExecutionSnapshot.ExecutionSnapshotBuilder b) {
        return b.contractKind(ContractKind.CONTRACT).contractNumber("42/2026").contractDate(D20).hasData(true);
    }

    static String states(Result r) {
        return r.steps().stream().map(x -> x.key() + ":" + x.state()).collect(joining(" "));
    }

    static Result active(ExecutionSnapshot s) { return DealExecutionCalculator.evaluate(s, Mode.ACTIVE); }

    @Test
    void acceptedOffer_asksForContract() {
        Result r = active(accepted().build());
        assertThat(r.next()).isEqualTo(new Next("Клиент согласился на КП № 481 — договор или счёт", "EXEC_CONTRACT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_CONTRACT");
        assertThat(r.stageText()).isEqualTo("Договор");
        assertThat(states(r)).isEqualTo("CONTRACT:CURRENT PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO");
        assertThat(r.steps().get(1).detail()).isEqualTo("оплачено 0 из " + nb("3 070 455 тг"));
        assertThat(r.steps().get(2).detail()).isEqualTo("заказ 0/2 · оплата 0/2 · отгрузка 0/2");
        assertThat(r.allDone()).isFalse();
    }

    @Test
    void prepayment50_waitsForPrepayment() {
        Result r = active(contract(accepted()).prepayPct(new BigDecimal("50")).build());
        assertThat(r.steps().get(0)).isEqualTo(new Step("CONTRACT", "DONE", "Договор", "№ 42/2026 от 20.10"));
        assertThat(r.next()).isEqualTo(new Next("Ждём предоплату " + nb("50 %") + ": оплачено 0 из " + nb("1 535 227,50 тг"),
                "EXEC_PAY_CLIENT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_PREPAY");
        assertThat(r.stageText()).isEqualTo("Ждём предоплату");
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:CURRENT SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO");
    }

    @Test
    void prepayment100_saysFullPayment() {
        Result r = active(contract(accepted()).build());
        assertThat(r.next().text()).isEqualTo("Ждём оплату: оплачено 0 из " + nb("3 070 455 тг"));
        assertThat(r.stageText()).isEqualTo("Ждём оплату");
    }

    @Test
    void prepaymentReached_movesToSuppliers() {
        Result r = active(contract(accepted()).prepayPct(new BigDecimal("50")).clientPaid(new BigDecimal("1535227.50")).build());
        assertThat(r.next()).isEqualTo(new Next("Закажите у ФОТЕК (и ещё у 1)", "EXEC_ORDER", 1L));
        assertThat(r.stageCode()).isEqualTo("EXEC_ORDER");
        assertThat(r.steps().get(1).state()).isEqualTo("TODO");
        assertThat(r.steps().get(1).detail()).isEqualTo("оплачено " + nb("1 535 227,50") + " из " + nb("3 070 455 тг"));
    }

    @Test
    void postpayment_suppliersFirst_paymentAtTheEnd() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).prepayPct(BigDecimal.ZERO);
        assertThat(active(b.build()).next().action()).isEqualTo("EXEC_ORDER");
        Result r = active(b.suppliers(List.of(done(fotek()), done(medsyst()))).deliveredOn(D25).docsOn(D25).build());
        assertThat(r.next()).isEqualTo(new Next("Ждём оплату: оплачено 0 из " + nb("3 070 455 тг"), "EXEC_PAY_CLIENT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_FINAL_PAY");
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:CURRENT SUPPLIERS:DONE DELIVERY:DONE DOCS:DONE");
    }

    @Test
    void paymentBeforeContract_paymentDoneContractCurrent() {
        Result r = active(accepted().clientPaid(new BigDecimal("3070455.00")).lastClientPaidOn(D21).hasData(true).build());
        assertThat(states(r)).startsWith("CONTRACT:CURRENT PAYMENT:DONE");
        assertThat(r.steps().get(1).detail()).isEqualTo("оплачено полностью 21.10");
    }

    @Test
    void overpayment_isDone() {
        Result r = active(contract(accepted()).clientPaid(new BigDecimal("3100000")).lastClientPaidOn(D21).build());
        assertThat(r.steps().get(1).state()).isEqualTo("DONE");
        assertThat(r.next().action()).isEqualTo("EXEC_ORDER");
    }

    @Test
    void suppliers_amountThenPaymentThenShipment() {
        SupplierState rub = SupplierState.builder().distributorId(1L).name("ФОТЕК").currency("RUB").orderedOn(D21).build();
        SupplierState ok = done(medsyst());
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).prepayPct(BigDecimal.ZERO);

        Result r = active(b.suppliers(List.of(rub, ok)).build());
        assertThat(r.next()).isEqualTo(new Next("Впишите сумму к оплате ФОТЕК", "EXEC_SUPPLIER_AMOUNT", 1L));
        assertThat(r.stageCode()).isEqualTo("EXEC_PAY_SUPPLIERS");

        SupplierState priced = rub.toBuilder().amount(new BigDecimal("277025")).build();
        r = active(b.suppliers(List.of(priced, ok)).build());
        assertThat(r.next()).isEqualTo(new Next("Оплатите ФОТЕК: 0 из " + nb("277 025 руб."), "EXEC_PAY_SUPPLIER", 1L));

        SupplierState paid = priced.toBuilder().paid(new BigDecimal("277025")).build();
        SupplierState notShipped = ok.toBuilder().shippedOn(null).build();
        r = active(b.suppliers(List.of(paid, notShipped)).build());
        assertThat(r.next()).isEqualTo(new Next("Ждём отгрузку от ФОТЕК (и ещё от 1)", "EXEC_SHIP", 1L));
        assertThat(r.stageCode()).isEqualTo("EXEC_SHIPPING");
        assertThat(r.steps().get(2).detail()).isEqualTo("заказ 2/2 · оплата 2/2 · отгрузка 0/2");
    }

    @Test
    void noSuppliers_askToAddOrStock_stockSkipsStep() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).prepayPct(BigDecimal.ZERO).suppliers(List.of());
        assertThat(active(b.build()).next()).isEqualTo(new Next("Добавьте поставщика или отметьте «Со склада»", "EXEC_SUPPLIERS", null));
        Result r = active(b.fromStock(true).build());
        assertThat(r.steps().get(2)).isEqualTo(new Step("SUPPLIERS", "SKIPPED", "Поставщики", "со склада"));
        assertThat(r.next()).isEqualTo(new Next("Поставьте клиенту", "EXEC_DELIVERY", null));
    }

    @Test
    void excludedSupplierNotCounted() {
        Result r = active(contract(accepted()).prepayPct(BigDecimal.ZERO)
                .suppliers(List.of(fotek().excluded(true).build(), done(medsyst()))).build());
        assertThat(r.steps().get(2)).isEqualTo(new Step("SUPPLIERS", "DONE", "Поставщики", "заказ 1/1 · оплата 1/1 · отгрузка 1/1"));
    }

    @Test
    void withoutContract_stepSkipped() {
        Result r = active(accepted().contractKind(ContractKind.NONE).hasData(true).build());
        assertThat(r.steps().get(0)).isEqualTo(new Step("CONTRACT", "SKIPPED", "Договор", "без договора"));
        assertThat(r.next().action()).isEqualTo("EXEC_PAY_CLIENT");
    }

    @Test
    void docsThenAllDone() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = contract(accepted()).clientPaid(new BigDecimal("3070455"))
                .lastClientPaidOn(D21).suppliers(List.of(done(fotek()), done(medsyst()))).deliveredOn(D25);
        assertThat(active(b.build()).next()).isEqualTo(new Next("Подпишите закрывающие документы (накладная, акт)", "EXEC_DOCS", null));
        Result r = active(b.docsOn(D25).build());
        assertThat(r.next()).isEqualTo(new Next("Всё сделано — закройте сделку", "CLOSE_WON", null));
        assertThat(r.allDone()).isTrue();
        assertThat(r.stageCode()).isEqualTo("EXEC_DONE");
        assertThat(r.stageText()).isEqualTo("Всё сделано — закрыть");
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:DONE SUPPLIERS:DONE DELIVERY:DONE DOCS:DONE");
        assertThat(r.steps().get(3).detail()).isEqualTo("поставлено 25.10");
        assertThat(r.steps().get(4).detail()).isEqualTo("подписаны 25.10");
    }

    @Test
    void unknownAmount_asksForAmount() {
        Result r = active(contract(accepted()).amount(null).build());
        assertThat(r.next()).isEqualTo(new Next("Укажите сумму сделки", "EXEC_AMOUNT", null));
        assertThat(r.stageCode()).isEqualTo("EXEC_AMOUNT");
        assertThat(r.stageText()).isEqualTo("Сумма сделки?");
        assertThat(active(contract(accepted()).amount(BigDecimal.ZERO).build()).next().action()).isEqualTo("EXEC_AMOUNT");
    }

    @Test
    void closedWithoutData_allSkippedNoDetails() {
        Result r = DealExecutionCalculator.evaluate(accepted().build(), Mode.CLOSED);
        assertThat(states(r)).isEqualTo("CONTRACT:SKIPPED PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:SKIPPED DOCS:SKIPPED");
        assertThat(r.steps()).allMatch(x -> x.detail() == null);
        assertThat(r.next()).isNull();
        assertThat(r.stageCode()).isNull();
    }

    @Test
    void closedWithData_doneStaysDone() {
        Result r = DealExecutionCalculator.evaluate(contract(accepted()).deliveredOn(D25).build(), Mode.CLOSED);
        assertThat(states(r)).isEqualTo("CONTRACT:DONE PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:DONE DOCS:SKIPPED");
    }

    @Test
    void passive_byDataWithoutNext() {
        Result r = DealExecutionCalculator.evaluate(ExecutionSnapshot.builder().currency("KZT").build(), Mode.PASSIVE);
        assertThat(states(r)).isEqualTo("CONTRACT:TODO PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO");
        assertThat(r.next()).isNull();
        r = DealExecutionCalculator.evaluate(ExecutionSnapshot.builder().currency("KZT").deliveredOn(D25).hasData(true).build(), Mode.PASSIVE);
        assertThat(r.steps().get(3).state()).isEqualTo("DONE");
    }

    @Test
    void contractDetails() {
        ExecutionSnapshot.ExecutionSnapshotBuilder b = accepted().hasData(true);
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(ContractKind.INVOICE).contractNumber("15").contractDate(D20).build()))
                .isEqualTo("счёт № 15 от 20.10");
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(ContractKind.INVOICE).contractNumber(null).contractDate(D20).build()))
                .isEqualTo("счёт от 20.10");
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(ContractKind.CONTRACT).contractNumber(null).contractDate(D20).build()))
                .isEqualTo("подписан 20.10");
        assertThat(DealExecutionCalculator.contractDetail(b.contractKind(null).contractNumber("42").contractDate(null).build()))
                .isEqualTo("№ 42");
    }

    @Test
    void texts() {
        assertThat(ExecutionTexts.money(new BigDecimal("1535227.50"), "KZT")).isEqualTo(nb("1 535 227,50 тг"));
        assertThat(ExecutionTexts.money(new BigDecimal("3000000.00"), "RUB")).isEqualTo(nb("3 000 000 руб."));
        assertThat(ExecutionTexts.money(new BigDecimal("1200"), "CNY")).isEqualTo(nb("1 200 юань"));
        assertThat(ExecutionTexts.pct(new BigDecimal("12.50"))).isEqualTo(nb("12,5 %"));
        assertThat(ExecutionTexts.pct(new BigDecimal("100.00"))).isEqualTo(nb("100 %"));
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.execution.DealExecutionCalculatorTest'`
Expected: FAIL — `cannot find symbol: class ExecutionSnapshot`.

- [ ] **Step 3: Снимок**

`service/execution/ExecutionSnapshot.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.ContractKind;
import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Снимок исполнения сделки — вход расчёта этапов (спека 2026-10-19-deal-execution §6). amount — сумма сделки (своя или
 * итог главного принятого КП), null — неизвестна; суммы клиента — в валюте рынка, поставщика — в его валюте.
 */
@Builder(toBuilder = true)
public record ExecutionSnapshot(String currency, String offerLabel, BigDecimal amount, ContractKind contractKind,
                                String contractNumber, LocalDate contractDate, BigDecimal prepayPct,
                                BigDecimal clientPaid, LocalDate lastClientPaidOn, boolean fromStock,
                                List<SupplierState> suppliers, LocalDate deliveredOn, LocalDate docsOn,
                                boolean hasData) {

    static final BigDecimal HUNDRED = new BigDecimal("100");

    public ExecutionSnapshot {
        if (prepayPct == null) prepayPct = HUNDRED;
        if (clientPaid == null) clientPaid = BigDecimal.ZERO;
        suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
    }

    /** Поставщик сделки: amount — к оплате (своя или по КП), null — неизвестна; paid — оплачено ему. */
    @Builder(toBuilder = true)
    public record SupplierState(Long distributorId, String name, boolean excluded, String currency, BigDecimal amount,
                                LocalDate orderedOn, BigDecimal paid, LocalDate shippedOn) {
        public SupplierState {
            if (paid == null) paid = BigDecimal.ZERO;
        }

        public boolean amountKnown() { return amount != null && amount.signum() > 0; }
        public boolean paidUp() { return amountKnown() && paid.compareTo(amount) >= 0; }
        public boolean done() { return orderedOn != null && paidUp() && shippedOn != null; }
    }

    public boolean amountKnown() { return amount != null && amount.signum() > 0; }

    public boolean contractSkipped() { return contractKind == ContractKind.NONE; }

    /** Вид не «без договора» и есть № или дата; вид пуст при № или дате — считается «Договор» (спека §6.3). */
    public boolean contractDone() {
        return !contractSkipped() && ((contractNumber != null && !contractNumber.isBlank()) || contractDate != null);
    }

    /** Предоплата = сумма сделки × процент, до тиына; сумма неизвестна — null. */
    public BigDecimal prepayAmount() {
        return amountKnown() ? amount.multiply(prepayPct).divide(HUNDRED, 2, RoundingMode.HALF_UP) : null;
    }

    public boolean paymentDone() { return amountKnown() && clientPaid.compareTo(amount) >= 0; }

    /** Поставщики, которые считаются: «Не заказываем» — нет. */
    public List<SupplierState> counted() { return suppliers.stream().filter(s -> !s.excluded()).toList(); }

    public boolean suppliersDone() {
        List<SupplierState> c = counted();
        return !fromStock && !c.isEmpty() && c.stream().allMatch(SupplierState::done);
    }

    public boolean deliveryDone() { return deliveredOn != null; }

    public boolean docsDone() { return docsOn != null; }
}
```

- [ ] **Step 4: Тексты**

`service/execution/ExecutionTexts.java`:

```java
package com.vladoose.nir.service.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Тексты исполнения: суммы (копейки — только ненулевые), проценты, «ДД.ММ». Пробелы — неразрывные. */
public final class ExecutionTexts {

    static final char NB = ' ';
    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");

    private ExecutionTexts() {}

    private static DecimalFormat format(String pattern) {
        DecimalFormatSymbols sy = new DecimalFormatSymbols(Locale.ROOT);
        sy.setGroupingSeparator(NB);
        sy.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(pattern, sy);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }

    /** «3 070 455 тг», «1 535 227,50 тг»; currency null — без валюты. */
    public static String money(BigDecimal v, String currency) {
        if (v == null) return "";
        boolean whole = v.stripTrailingZeros().scale() <= 0;
        String n = format(whole ? "#,##0" : "#,##0.00").format(v);
        return currency == null ? n : n + NB + currency(currency);
    }

    /** Валюта рядом с числом: тг, руб., $, €, юань (как на фронте, shared/deal-execution.ts). */
    public static String currency(String code) {
        if (code == null) return "";
        return switch (code) {
            case "KZT" -> "тг";
            case "RUB" -> "руб.";
            case "USD" -> "$";
            case "EUR" -> "€";
            case "CNY" -> "юань";
            default -> code;
        };
    }

    /** «50 %», «12,5 %». */
    public static String pct(BigDecimal p) {
        return p.stripTrailingZeros().toPlainString().replace('.', ',') + NB + "%";
    }

    public static String ddMM(LocalDate d) {
        return d == null ? null : DD_MM.format(d);
    }
}
```

- [ ] **Step 5: Расчёт**

`service/execution/DealExecutionCalculator.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.DealProgress.Step;
import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.service.execution.ExecutionSnapshot.SupplierState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.service.execution.ExecutionTexts.ddMM;
import static com.vladoose.nir.service.execution.ExecutionTexts.money;

/**
 * Шаги исполнения сделки и «Дальше» (спека 2026-10-19-deal-execution §3) — чистая функция снимка. ACTIVE — заявка в
 * работе с принятым КП: шаги по данным, «Дальше» — первое несделанное по порядку §3.2; PASSIVE — КП ещё не принято
 * (шаги по данным, «Дальше» — не наш); CLOSED — сделка закрыта: несделанное — «–». Подписи шагов без данных
 * исполнения — только в ACTIVE (решение при планировании 3).
 */
public final class DealExecutionCalculator {

    public enum Mode { ACTIVE, PASSIVE, CLOSED }

    /** stageCode / stageText — этап для списка заявок (§3.3); allDone — «Дальше» указывает на Итог. */
    public record Result(List<Step> steps, Next next, String stageCode, String stageText, boolean allDone) {}

    private record Pick(String step, Next next, String code, String chip) {}

    private DealExecutionCalculator() {}

    public static Result evaluate(ExecutionSnapshot s, Mode mode) {
        Pick pick = mode == Mode.ACTIVE ? pick(s) : null;
        String current = pick == null ? null : pick.step();
        boolean details = mode == Mode.ACTIVE || s.hasData();
        List<Step> steps = new ArrayList<>();
        steps.add(step("CONTRACT", "Договор", s.contractDone(), s.contractSkipped(), details ? contractDetail(s) : null, current, mode));
        steps.add(step("PAYMENT", "Оплата", s.paymentDone(), false, details ? paymentDetail(s) : null, current, mode));
        steps.add(step("SUPPLIERS", "Поставщики", s.suppliersDone(), s.fromStock(), details ? suppliersDetail(s) : null, current, mode));
        steps.add(step("DELIVERY", "Поставка", s.deliveryDone(), false,
                s.deliveryDone() ? "поставлено " + ddMM(s.deliveredOn()) : null, current, mode));
        steps.add(step("DOCS", "Документы", s.docsDone(), false,
                s.docsDone() ? "подписаны " + ddMM(s.docsOn()) : null, current, mode));
        if (pick == null) return new Result(steps, null, null, null, false);
        return new Result(steps, pick.next(), pick.code(), pick.chip(), "RESULT".equals(pick.step()));
    }

    private static Step step(String key, String label, boolean done, boolean skipped, String detail, String current, Mode mode) {
        String state = done ? "DONE"
                : skipped || mode == Mode.CLOSED ? "SKIPPED"
                : key.equals(current) ? "CURRENT" : "TODO";
        return new Step(key, state, label, detail);
    }

    /** «Дальше» — первое несделанное по порядку таблицы §3.2. */
    static Pick pick(ExecutionSnapshot s) {
        String cur = s.currency();
        if (!s.contractSkipped() && !s.contractDone()) {
            String text = s.offerLabel() == null ? "Клиент согласился — договор или счёт"
                    : "Клиент согласился на " + s.offerLabel() + " — договор или счёт";
            return new Pick("CONTRACT", new Next(text, "EXEC_CONTRACT", null), "EXEC_CONTRACT", "Договор");
        }
        if (s.prepayPct().signum() > 0) {
            if (!s.amountKnown()) return amount();
            BigDecimal prepay = s.prepayAmount();
            if (s.clientPaid().compareTo(prepay) < 0) {
                boolean full = s.prepayPct().compareTo(ExecutionSnapshot.HUNDRED) >= 0;
                String text = full
                        ? "Ждём оплату: оплачено " + money(s.clientPaid(), null) + " из " + money(s.amount(), cur)
                        : "Ждём предоплату " + ExecutionTexts.pct(s.prepayPct()) + ": оплачено " + money(s.clientPaid(), null)
                          + " из " + money(prepay, cur);
                return new Pick("PAYMENT", new Next(text, "EXEC_PAY_CLIENT", null), "EXEC_PREPAY",
                        full ? "Ждём оплату" : "Ждём предоплату");
            }
        }
        if (!s.fromStock()) {
            List<SupplierState> c = s.counted();
            if (c.isEmpty()) {
                return new Pick("SUPPLIERS", new Next("Добавьте поставщика или отметьте «Со склада»", "EXEC_SUPPLIERS", null),
                        "EXEC_ORDER", "Заказ у поставщиков");
            }
            List<SupplierState> notOrdered = c.stream().filter(x -> x.orderedOn() == null).toList();
            if (!notOrdered.isEmpty()) {
                return supplier("Закажите у " + notOrdered.get(0).name() + more(notOrdered, " (и ещё у "), "EXEC_ORDER",
                        notOrdered, "EXEC_ORDER", "Заказ у поставщиков");
            }
            List<SupplierState> noAmount = c.stream().filter(x -> !x.amountKnown()).toList();
            if (!noAmount.isEmpty()) {
                return supplier("Впишите сумму к оплате " + noAmount.get(0).name() + more(noAmount, " (и ещё "),
                        "EXEC_SUPPLIER_AMOUNT", noAmount, "EXEC_PAY_SUPPLIERS", "Оплата поставщикам");
            }
            List<SupplierState> unpaid = c.stream().filter(x -> !x.paidUp()).toList();
            if (!unpaid.isEmpty()) {
                SupplierState f = unpaid.get(0);
                return supplier("Оплатите " + f.name() + ": " + money(f.paid(), null) + " из " + money(f.amount(), f.currency())
                        + more(unpaid, " (и ещё "), "EXEC_PAY_SUPPLIER", unpaid, "EXEC_PAY_SUPPLIERS", "Оплата поставщикам");
            }
            List<SupplierState> notShipped = c.stream().filter(x -> x.shippedOn() == null).toList();
            if (!notShipped.isEmpty()) {
                return supplier("Ждём отгрузку от " + notShipped.get(0).name() + more(notShipped, " (и ещё от "), "EXEC_SHIP",
                        notShipped, "EXEC_SHIPPING", "Ждём отгрузку");
            }
        }
        if (!s.deliveryDone()) {
            return new Pick("DELIVERY", new Next("Поставьте клиенту", "EXEC_DELIVERY", null), "EXEC_DELIVERY", "Поставка");
        }
        if (!s.docsDone()) {
            return new Pick("DOCS", new Next("Подпишите закрывающие документы (накладная, акт)", "EXEC_DOCS", null),
                    "EXEC_DOCS", "Документы");
        }
        if (!s.amountKnown()) return amount();
        if (!s.paymentDone()) {
            return new Pick("PAYMENT", new Next("Ждём оплату: оплачено " + money(s.clientPaid(), null) + " из "
                    + money(s.amount(), cur), "EXEC_PAY_CLIENT", null), "EXEC_FINAL_PAY", "Ждём оплату");
        }
        return new Pick("RESULT", new Next("Всё сделано — закройте сделку", "CLOSE_WON", null), "EXEC_DONE", "Всё сделано — закрыть");
    }

    private static Pick amount() {
        return new Pick("PAYMENT", new Next("Укажите сумму сделки", "EXEC_AMOUNT", null), "EXEC_AMOUNT", "Сумма сделки?");
    }

    private static Pick supplier(String text, String action, List<SupplierState> list, String code, String chip) {
        return new Pick("SUPPLIERS", new Next(text, action, list.get(0).distributorId()), code, chip);
    }

    private static String more(List<SupplierState> list, String prefix) {
        return list.size() > 1 ? prefix + (list.size() - 1) + ")" : "";
    }

    static String contractDetail(ExecutionSnapshot s) {
        if (s.contractSkipped()) return "без договора";
        if (!s.contractDone()) return null;
        boolean invoice = s.contractKind() == ContractKind.INVOICE;
        String no = s.contractNumber() == null || s.contractNumber().isBlank() ? null : "№ " + s.contractNumber().strip();
        String date = ddMM(s.contractDate());
        if (no == null) return (invoice ? "счёт от " : "подписан ") + date;
        return (invoice ? "счёт " : "") + no + (date == null ? "" : " от " + date);
    }

    static String paymentDetail(ExecutionSnapshot s) {
        if (s.amountKnown()) {
            if (s.paymentDone()) {
                return "оплачено полностью" + (s.lastClientPaidOn() == null ? "" : " " + ddMM(s.lastClientPaidOn()));
            }
            return "оплачено " + money(s.clientPaid(), null) + " из " + money(s.amount(), s.currency());
        }
        return s.clientPaid().signum() > 0 ? "оплачено " + money(s.clientPaid(), s.currency()) : null;
    }

    static String suppliersDetail(ExecutionSnapshot s) {
        if (s.fromStock()) return "со склада";
        List<SupplierState> c = s.counted();
        if (c.isEmpty()) return null;
        long ordered = c.stream().filter(x -> x.orderedOn() != null).count();
        long paid = c.stream().filter(SupplierState::paidUp).count();
        long shipped = c.stream().filter(x -> x.shippedOn() != null).count();
        int n = c.size();
        return "заказ " + ordered + "/" + n + " · оплата " + paid + "/" + n + " · отгрузка " + shipped + "/" + n;
    }
}
```

- [ ] **Step 6: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.execution.DealExecutionCalculatorTest'`
Expected: PASS (18 тестов).

- [ ] **Step 7: Мутации (по одной, откат копией)**
  - `pick`: убрать ветку предоплаты (`if (s.prepayPct().signum() > 0) {…}`) → красные `prepayment50_waitsForPrepayment`, `prepayment100_saysFullPayment`.
  - `SupplierState.counts`: в `counted()` убрать фильтр `!s.excluded()` → красный `excludedSupplierNotCounted`.
  - `step()`: убрать `mode == Mode.CLOSED` → красный `closedWithoutData_allSkippedNoDetails`.
  - `evaluate`: `details = true` всегда → красный `closedWithoutData_allSkippedNoDetails` (подписи).
  - `ExecutionTexts.money`: всегда `#,##0.00` → красные `texts`, `acceptedOffer_asksForContract`.

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/execution/ExecutionSnapshot.java \
  src/main/java/com/vladoose/nir/service/execution/ExecutionTexts.java \
  src/main/java/com/vladoose/nir/service/execution/DealExecutionCalculator.java \
  src/test/java/com/vladoose/nir/service/execution/DealExecutionCalculatorTest.java
git commit -m "$(cat <<'EOF'
feat(execution): шаги исполнения и «Дальше» — чистая функция снимка (предоплата, поставщики, документы)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 3: Загрузка исполнения — поставщики из КП, строки, снимок

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/execution/DealExecutionData.java`
- Create: `src/main/java/com/vladoose/nir/service/execution/DealExecutionLoader.java`
- Modify: `src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java` (два запроса)
- Modify: `src/main/java/com/vladoose/nir/repository/DistributorRepository.java` (`findNames`)
- Modify: `src/main/java/com/vladoose/nir/service/offer/OfferLabels.java` (перегрузка)
- Test: `src/test/java/com/vladoose/nir/execution/ExecutionTestData.java`, `DealExecutionLoaderTest.java`

**Interfaces:**
- Consumes: сущности и репозитории задачи 1; `ExecutionSnapshot` (задача 2).
- Produces:
  - `DealExecutionLoader.load(Tender) → DealExecutionData`, `load(Collection<Tender>) → Map<Long, DealExecutionData>` — шесть запросов на любую выборку.
  - `DealExecutionData(Long tenderId, String currency, DealExecution row, MainOffer mainOffer, List<OfferGroup> offerGroups, int unassignedCount, BigDecimal unassignedPurchase, List<SupplierLine> supplierLines, List<DealPayment> clientPayments, ExecutionSnapshot snapshot)`; `visible()`, `inOffer(Long distributorId)`, `static positive(BigDecimal)`.
  - `MainOffer(Long id, BigDecimal total, String label)`, `OfferGroup(Long distributorId, BigDecimal purchase, int firstLine)`, `SupplierLine(Long rowId, Long distributorId, String name, boolean fromOffer, boolean manual, boolean excluded, String currency, BigDecimal amount, boolean amountFromOffer, BigDecimal offerAmount, LocalDate orderedOn, String orderNote, BigDecimal paid, List<DealPayment> payments, LocalDate shippedOn, String shipNote)`.
  - `ClientOfferRepository.findAcceptedByTenderIds(Collection<Long>, ClientOfferStatus)` → `Object[]{tenderId, id, totalAmount, number, offerDate}` новые сверху; `findPurchaseRows(Collection<Long>, ClientOfferItemKind)` → `Object[]{offerId, distributorId, purchasePrice, quantity, lineNo}`.
  - `DistributorRepository.findNames(Collection<Long>)` → `Object[]{id, name}`.
  - `OfferLabels.of(Integer number, LocalDate offerDate)`.

- [ ] **Step 1: Данные для тестов**

`src/test/java/com/vladoose/nir/execution/ExecutionTestData.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** КП сделки для тестов исполнения: статус, итог, строки «поставщик — закупка × кол-во». Рынок ставит листенер. */
final class ExecutionTestData {

    private ExecutionTestData() {}

    static ClientOffer offer(Tender t, ClientOfferStatus status, String total, int number) {
        return ClientOffer.builder()
                .number(number)
                .offerDate(LocalDate.of(2026, 10, 19))
                .status(status)
                .title("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ")
                .vatEnabled(true)
                .defaultMarkupPct(new BigDecimal("20"))
                .rounding(OfferRounding.NONE)
                .tableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null))))
                .detailsInName(true)
                .terms(new ArrayList<>())
                .termsStyle(TermsStyle.LIST)
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .tenderId(t.getId())
                .totalAmount(total == null ? null : new BigDecimal(total))
                .build();
    }

    /** Строка-позиция КП; purchase null — без закупки, distributorId null — без поставщика. */
    static ClientOffer line(ClientOffer o, Long distributorId, String purchase, String qty) {
        int no = o.getItems().size() + 1;
        o.getItems().add(ClientOfferItem.builder().offer(o).lineNo(no).name("Позиция " + no)
                .quantity(new BigDecimal(qty)).purchasePrice(purchase == null ? null : new BigDecimal(purchase))
                .distributorId(distributorId).build());
        return o;
    }
}
```

- [ ] **Step 2: Тест загрузки (красный — загрузчика нет)**

`src/test/java/com/vladoose/nir/execution/DealExecutionLoaderTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionData;
import com.vladoose.nir.service.execution.DealExecutionData.SupplierLine;
import com.vladoose.nir.service.execution.DealExecutionLoader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;

/** Загрузка исполнения (спека 2026-10-19-deal-execution §6): главное принятое КП, поставщики КП ∪ строки, снимок. */
@SpringBootTest
@Transactional
class DealExecutionLoaderTest {

    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DealPaymentRepository payments;
    @Autowired DealExecutionRepository executions;
    @Autowired DealExecutionLoader loader;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name) {
        return distributors.save(Distributor.builder().name("ZZ " + name + " " + System.nanoTime()).build());
    }

    private DealExecutionData load(Tender t) {
        em.flush();
        em.clear();
        return loader.load(requests.findById(t.getId()));
    }

    private static DealPayment pay(Tender t, Long supplierRow, String amount, LocalDate on) {
        return DealPayment.builder().tenderId(t.getId()).dealSupplierId(supplierRow).amount(new BigDecimal(amount)).paidOn(on).build();
    }

    @Test
    void suppliersFromMainAcceptedOffer_perLineRounding_unassignedHint() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК"), med = dist("MEDSYST");
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "100", 1), fotek.getId(), "100", "1"));   // прежнее принятое
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "3070455.00", 2);
        line(o, med.getId(), "57000", "2");      // 114 000
        line(o, fotek.getId(), "0.335", "3");    // 1,005 → 1,01
        line(o, fotek.getId(), "0.335", "3");    // ещё 1,01: по строке — 2,02, разом было бы 2,01
        line(o, fotek.getId(), null, "1");       // без закупки — не в сумме
        line(o, null, "30000", "1");             // без поставщика
        o = offers.save(o);
        offers.save(line(offer(t, ClientOfferStatus.SENT, "999", 3), med.getId(), "1", "1"));   // не принятое

        DealExecutionData d = load(t);
        assertThat(d.mainOffer().id()).isEqualTo(o.getId());
        assertThat(d.mainOffer().label()).isEqualTo("КП № 2");
        assertThat(d.supplierLines()).extracting(SupplierLine::distributorId).containsExactly(med.getId(), fotek.getId());
        assertThat(d.supplierLines().get(0).amount()).isEqualByComparingTo("114000");
        assertThat(d.supplierLines().get(1).amount()).isEqualByComparingTo("2.02");
        assertThat(d.supplierLines().get(1).amountFromOffer()).isTrue();
        assertThat(d.supplierLines().get(1).fromOffer()).isTrue();
        assertThat(d.supplierLines().get(1).rowId()).isNull();
        assertThat(d.unassignedCount()).isEqualTo(1);
        assertThat(d.unassignedPurchase()).isEqualByComparingTo("30000");
        assertThat(d.snapshot().amount()).isEqualByComparingTo("3070455");
        assertThat(d.snapshot().offerLabel()).isEqualTo("КП № 2");
        assertThat(d.snapshot().hasData()).isFalse();
        assertThat(d.visible()).isTrue();
        assertThat(d.inOffer(fotek.getId())).isTrue();
    }

    @Test
    void offerEditedAfterAcceptance_amountsFollowUntilOwn() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК");
        ClientOffer o = offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "500", 1), fotek.getId(), "100", "1"));
        assertThat(load(t).supplierLines().get(0).amount()).isEqualByComparingTo("100");

        ClientOffer back = offers.findById(o.getId()).orElseThrow();
        back.getItems().get(0).setPurchasePrice(new BigDecimal("120"));
        back.setTotalAmount(new BigDecimal("600"));
        DealExecutionData d = load(t);
        assertThat(d.supplierLines().get(0).amount()).isEqualByComparingTo("120");
        assertThat(d.snapshot().amount()).isEqualByComparingTo("600");

        suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(fotek.getId()).currency("KZT")
                .amount(new BigDecimal("90")).build());
        executions.save(DealExecution.builder().tenderId(t.getId()).amount(new BigDecimal("650")).build());
        d = load(t);
        assertThat(d.supplierLines().get(0).amount()).isEqualByComparingTo("90");
        assertThat(d.supplierLines().get(0).amountFromOffer()).isFalse();
        assertThat(d.supplierLines().get(0).offerAmount()).isEqualByComparingTo("120");
        assertThat(d.snapshot().amount()).isEqualByComparingTo("650");
        assertThat(d.snapshot().hasData()).isTrue();
    }

    @Test
    void foreignCurrencyWithoutOwnAmount_unknown() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК");
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "500", 1), fotek.getId(), "100", "1"));
        suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(fotek.getId()).currency("RUB").build());
        SupplierLine s = load(t).supplierLines().get(0);
        assertThat(s.amount()).isNull();
        assertThat(s.offerAmount()).isEqualByComparingTo("100");
        assertThat(s.currency()).isEqualTo("RUB");
    }

    @Test
    void manualSupplierAfterOffer_paymentsSplitClientAndSupplier() {
        Tender t = requests.createEmpty();
        Distributor fotek = dist("ФОТЕК"), rm = dist("Регион-Мед");
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "500", 1), fotek.getId(), "100", "1"));
        DealSupplier manual = suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(rm.getId())
                .currency("RUB").amount(new BigDecimal("277025")).manual(true).build());
        payments.save(pay(t, null, "200", LocalDate.of(2026, 10, 20)));
        payments.save(pay(t, null, "100", LocalDate.of(2026, 10, 21)));
        payments.save(pay(t, manual.getId(), "277025", LocalDate.of(2026, 10, 21)));

        DealExecutionData d = load(t);
        assertThat(d.supplierLines()).extracting(SupplierLine::name).containsExactly(fotek.getName(), rm.getName());
        assertThat(d.supplierLines().get(1).manual()).isTrue();
        assertThat(d.supplierLines().get(1).fromOffer()).isFalse();
        assertThat(d.supplierLines().get(1).paid()).isEqualByComparingTo("277025");
        assertThat(d.clientPayments()).extracting(DealPayment::getAmount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("200"), new BigDecimal("100"));
        assertThat(d.snapshot().clientPaid()).isEqualByComparingTo("300");
        assertThat(d.snapshot().lastClientPaidOn()).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void noAcceptedOffer_hiddenUntilData_zeroTotalUnknown() {
        Tender t = requests.createEmpty();
        offers.save(offer(t, ClientOfferStatus.SENT, "500", 1));
        assertThat(load(t).visible()).isFalse();
        payments.save(pay(t, null, "10", LocalDate.of(2026, 10, 20)));
        assertThat(load(t).visible()).isTrue();

        Tender z = requests.createEmpty();
        offers.save(offer(z, ClientOfferStatus.ACCEPTED, "0", 2));
        assertThat(load(z).snapshot().amount()).isNull();
    }

    @Test
    void batchLoadDoesNotGrowWithDeals() {
        List<Tender> two = deals(2), six = deals(6);
        assertThat(statements(six)).isEqualTo(statements(two));
    }

    private List<Tender> deals(int n) {
        List<Tender> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Tender t = requests.createEmpty();
            Distributor d = dist("Пачка");
            offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "100", 10 + i), d.getId(), "10", "1"));
            suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(d.getId()).currency("KZT").build());
            payments.save(pay(t, null, "5", LocalDate.of(2026, 10, 20)));
            out.add(t);
        }
        return out;
    }

    private long statements(List<Tender> deals) {
        em.flush();
        em.clear();
        List<Tender> fresh = deals.stream().map(t -> requests.findById(t.getId())).toList();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean was = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            loader.load(fresh);
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(was);
        }
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionLoaderTest'`
Expected: FAIL — `cannot find symbol: class DealExecutionData`.

- [ ] **Step 4: Запросы и подпись КП**

В `ClientOfferRepository` (после `findStatusesByTenderIds`):

```java
    /** Принятые КП сделок, новые сверху: id сделки, id КП, итог, номер, дата (исполнение: главное — первое). */
    @Query("select o.tenderId, o.id, o.totalAmount, o.number, o.offerDate from ClientOffer o "
           + "where o.tenderId in :ids and o.status = :status order by o.id desc")
    List<Object[]> findAcceptedByTenderIds(@Param("ids") Collection<Long> ids, @Param("status") ClientOfferStatus status);

    /** Строки КП вида kind: id КП, поставщик, закупка, количество, № строки — суммы закупки по поставщикам. */
    @Query("select i.offer.id, i.distributorId, i.purchasePrice, i.quantity, i.lineNo from ClientOfferItem i "
           + "where i.offer.id in :ids and i.kind = :kind")
    List<Object[]> findPurchaseRows(@Param("ids") Collection<Long> ids, @Param("kind") ClientOfferItemKind kind);
```

(импорт `com.vladoose.nir.entity.ClientOfferItemKind`, если его нет.)

В `DistributorRepository`:

```java
    /** id и название — подписи поставщиков исполнения (рынок режет аспект). */
    @Query("select d.id, d.name from Distributor d where d.id in :ids")
    List<Object[]> findNames(@Param("ids") Collection<Long> ids);
```

(импорт `java.util.Collection`, `org.springframework.data.repository.query.Param`, если нет.)

`OfferLabels` — перегрузка, прежний метод делегирует:

```java
    /** «КП № 24» или «КП от 24.09.2026». */
    public static String of(ClientOffer o) {
        return of(o.getNumber(), o.getOfferDate());
    }

    /** То же по номеру и дате — без загрузки КП (исполнение сделки). */
    public static String of(Integer number, LocalDate offerDate) {
        return "КП " + (number != null ? "№ " + number : "от " + DocFormat.date(offerDate));
    }
```

(импорт `java.time.LocalDate`; `numberOrDate` не меняется.)

- [ ] **Step 5: Данные и загрузчик**

`service/execution/DealExecutionData.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.DealExecution;
import com.vladoose.nir.entity.DealPayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Исполнение одной сделки (спека 2026-10-19-deal-execution §6): строка сделки, главное принятое КП, поставщики (группы КП
 * ∪ строки deal_supplier), платежи клиента и снимок для расчёта. Из него строятся и шаги, и вид раздела, и лента.
 */
public record DealExecutionData(Long tenderId, String currency, DealExecution row, MainOffer mainOffer,
                                List<OfferGroup> offerGroups, int unassignedCount, BigDecimal unassignedPurchase,
                                List<SupplierLine> supplierLines, List<DealPayment> clientPayments,
                                ExecutionSnapshot snapshot) {

    /** Главное принятое КП — последнее по id; total — его итог (у КП без цен — 0). */
    public record MainOffer(Long id, BigDecimal total, String label) {}

    /** Строки главного принятого КП одного поставщика: сумма закупок (каждая строка — до тиына), первая строка — порядок. */
    public record OfferGroup(Long distributorId, BigDecimal purchase, int firstLine) {}

    /** Поставщик раздела: строка deal_supplier (rowId) и/или группа КП; amount — к оплате (своя или по КП). */
    public record SupplierLine(Long rowId, Long distributorId, String name, boolean fromOffer, boolean manual,
                               boolean excluded, String currency, BigDecimal amount, boolean amountFromOffer,
                               BigDecimal offerAmount, LocalDate orderedOn, String orderNote, BigDecimal paid,
                               List<DealPayment> payments, LocalDate shippedOn, String shipNote) {}

    /** Раздел виден: есть принятое КП или данные исполнения. */
    public boolean visible() {
        return mainOffer != null || snapshot.hasData();
    }

    public boolean inOffer(Long distributorId) {
        return offerGroups.stream().anyMatch(g -> g.distributorId().equals(distributorId));
    }

    /** Положительное число, иначе null («КП без цен» и строки без закупки — сумма неизвестна). */
    public static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
    }
}
```

`service/execution/DealExecutionLoader.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.execution.DealExecutionData.MainOffer;
import com.vladoose.nir.service.execution.DealExecutionData.OfferGroup;
import com.vladoose.nir.service.execution.DealExecutionData.SupplierLine;
import com.vladoose.nir.service.execution.ExecutionSnapshot.SupplierState;
import com.vladoose.nir.service.offer.OfferLabels;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.vladoose.nir.service.execution.DealExecutionData.positive;

/**
 * Загрузка исполнения (спека 2026-10-19-deal-execution §6): шесть запросов на любую выборку сделок — страница, список
 * заявок, лента — без запроса на каждую заявку. Поставщики — группы главного принятого КП (в порядке первой строки), затем
 * строки deal_supplier без группы (добавленные вручную) по id; «к оплате по КП» — в валюте рынка и только пока своя
 * сумма пуста.
 */
@Component
public class DealExecutionLoader {

    private static final Comparator<DealPayment> PAYMENT_ORDER =
            Comparator.comparing(DealPayment::getPaidOn).thenComparing(DealPayment::getId);

    private final DealExecutionRepository executions;
    private final DealSupplierRepository suppliers;
    private final DealPaymentRepository payments;
    private final ClientOfferRepository offers;
    private final DistributorRepository distributors;

    public DealExecutionLoader(DealExecutionRepository executions, DealSupplierRepository suppliers,
                               DealPaymentRepository payments, ClientOfferRepository offers, DistributorRepository distributors) {
        this.executions = executions;
        this.suppliers = suppliers;
        this.payments = payments;
        this.offers = offers;
        this.distributors = distributors;
    }

    public DealExecutionData load(Tender t) {
        return load(List.of(t)).get(t.getId());
    }

    public Map<Long, DealExecutionData> load(Collection<Tender> deals) {
        Map<Long, DealExecutionData> out = new HashMap<>();
        if (deals.isEmpty()) return out;
        List<Long> ids = deals.stream().map(Tender::getId).toList();

        Map<Long, DealExecution> rows = new HashMap<>();
        for (DealExecution e : executions.findByTenderIdIn(ids)) rows.put(e.getTenderId(), e);
        Map<Long, List<DealSupplier>> supplierRows = new HashMap<>();
        for (DealSupplier s : suppliers.findByTenderIdIn(ids)) {
            supplierRows.computeIfAbsent(s.getTenderId(), k -> new ArrayList<>()).add(s);
        }
        Map<Long, List<DealPayment>> paymentRows = new HashMap<>();
        for (DealPayment p : payments.findByTenderIdIn(ids)) {
            paymentRows.computeIfAbsent(p.getTenderId(), k -> new ArrayList<>()).add(p);
        }
        Map<Long, MainOffer> main = new HashMap<>();
        for (Object[] r : offers.findAcceptedByTenderIds(ids, ClientOfferStatus.ACCEPTED)) {   // новые сверху
            main.putIfAbsent((Long) r[0], new MainOffer((Long) r[1], (BigDecimal) r[2], OfferLabels.of((Integer) r[3], (LocalDate) r[4])));
        }
        Map<Long, List<Object[]>> offerLines = new HashMap<>();
        if (!main.isEmpty()) {
            List<Long> offerIds = main.values().stream().map(MainOffer::id).toList();
            for (Object[] r : offers.findPurchaseRows(offerIds, ClientOfferItemKind.ITEM)) {
                offerLines.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(r);
            }
        }
        Set<Long> distributorIds = new HashSet<>();
        supplierRows.values().forEach(l -> l.forEach(s -> distributorIds.add(s.getDistributorId())));
        offerLines.values().forEach(l -> l.forEach(r -> { if (r[1] != null) distributorIds.add((Long) r[1]); }));
        Map<Long, String> names = new HashMap<>();
        if (!distributorIds.isEmpty()) {
            for (Object[] r : distributors.findNames(distributorIds)) names.put((Long) r[0], (String) r[1]);
        }

        for (Tender t : deals) {
            Market market = t.getMarket() == null ? MarketContext.get() : t.getMarket();
            MainOffer m = main.get(t.getId());
            out.put(t.getId(), build(t.getId(), market.currencyCode(), rows.get(t.getId()), m,
                    m == null ? List.of() : offerLines.getOrDefault(m.id(), List.of()),
                    supplierRows.getOrDefault(t.getId(), List.of()), paymentRows.getOrDefault(t.getId(), List.of()), names));
        }
        return out;
    }

    static DealExecutionData build(Long tenderId, String currency, DealExecution row, MainOffer main, List<Object[]> lines,
                                   List<DealSupplier> rows, List<DealPayment> payments, Map<Long, String> names) {
        // группы КП: сумма закупок по строке до тиына (как cost_total в расчёте КП), только строки с закупкой
        Map<Long, BigDecimal> purchase = new HashMap<>();
        Map<Long, Integer> firstLine = new HashMap<>();
        int unassigned = 0;
        BigDecimal unassignedSum = null;
        List<Object[]> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt(r -> (Integer) r[4]));
        for (Object[] r : sorted) {
            Long d = (Long) r[1];
            BigDecimal p = (BigDecimal) r[2], q = (BigDecimal) r[3];
            BigDecimal cost = p == null || q == null ? null : p.multiply(q).setScale(2, RoundingMode.HALF_UP);
            if (d == null) {
                unassigned++;
                if (cost != null) unassignedSum = unassignedSum == null ? cost : unassignedSum.add(cost);
                continue;
            }
            firstLine.putIfAbsent(d, (Integer) r[4]);
            if (cost != null) purchase.merge(d, cost, BigDecimal::add);
        }
        List<OfferGroup> groups = firstLine.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .map(e -> new OfferGroup(e.getKey(), positive(purchase.get(e.getKey())), e.getValue())).toList();

        List<DealPayment> client = new ArrayList<>();
        Map<Long, List<DealPayment>> bySupplier = new HashMap<>();
        for (DealPayment p : payments) {
            if (p.getDealSupplierId() == null) client.add(p);
            else bySupplier.computeIfAbsent(p.getDealSupplierId(), k -> new ArrayList<>()).add(p);
        }
        client.sort(PAYMENT_ORDER);
        bySupplier.values().forEach(l -> l.sort(PAYMENT_ORDER));

        Map<Long, DealSupplier> byDistributor = new HashMap<>();
        for (DealSupplier s : rows) byDistributor.put(s.getDistributorId(), s);
        List<SupplierLine> supplierLines = new ArrayList<>();
        for (OfferGroup g : groups) {
            supplierLines.add(line(byDistributor.remove(g.distributorId()), g, g.distributorId(), currency, bySupplier, names));
        }
        byDistributor.values().stream().sorted(Comparator.comparing(DealSupplier::getId))
                .forEach(s -> supplierLines.add(line(s, null, s.getDistributorId(), currency, bySupplier, names)));

        BigDecimal clientPaid = client.stream().map(DealPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate lastPaid = client.stream().map(DealPayment::getPaidOn).max(Comparator.naturalOrder()).orElse(null);
        BigDecimal amount = row != null && row.getAmount() != null ? row.getAmount() : main == null ? null : positive(main.total());
        boolean hasData = row != null || !rows.isEmpty() || !payments.isEmpty();
        ExecutionSnapshot snapshot = ExecutionSnapshot.builder()
                .currency(currency)
                .offerLabel(main == null ? null : main.label())
                .amount(amount)
                .contractKind(row == null ? null : row.getContractKind())
                .contractNumber(row == null ? null : row.getContractNumber())
                .contractDate(row == null ? null : row.getContractDate())
                .prepayPct(row == null ? null : row.getPrepayPct())
                .clientPaid(clientPaid)
                .lastClientPaidOn(lastPaid)
                .fromStock(row != null && row.isFromStock())
                .suppliers(supplierLines.stream().map(l -> new SupplierState(l.distributorId(), l.name(), l.excluded(),
                        l.currency(), l.amount(), l.orderedOn(), l.paid(), l.shippedOn())).toList())
                .deliveredOn(row == null ? null : row.getDeliveredOn())
                .docsOn(row == null ? null : row.getDocsOn())
                .hasData(hasData)
                .build();
        return new DealExecutionData(tenderId, currency, row, main, groups, unassigned, unassignedSum, supplierLines, client, snapshot);
    }

    private static SupplierLine line(DealSupplier r, OfferGroup g, Long distributorId, String marketCurrency,
                                     Map<Long, List<DealPayment>> bySupplier, Map<Long, String> names) {
        String cur = r == null ? marketCurrency : r.getCurrency();
        BigDecimal offerAmount = g == null ? null : g.purchase();
        boolean own = r != null && r.getAmount() != null;
        BigDecimal amount = own ? r.getAmount() : cur.equals(marketCurrency) ? offerAmount : null;
        List<DealPayment> pays = r == null ? List.of() : bySupplier.getOrDefault(r.getId(), List.of());
        BigDecimal paid = pays.stream().map(DealPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SupplierLine(r == null ? null : r.getId(), distributorId, names.getOrDefault(distributorId, "Поставщик"),
                g != null, r != null && r.isManual(), r != null && r.isExcluded(), cur, amount, !own && amount != null,
                offerAmount, r == null ? null : r.getOrderedOn(), r == null ? null : r.getOrderNote(), paid, pays,
                r == null ? null : r.getShippedOn(), r == null ? null : r.getShipNote());
    }
}
```

- [ ] **Step 6: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionLoaderTest'`
Expected: PASS (6 тестов).

- [ ] **Step 7: Мутации**
  - Округлять разом: в `build` `cost` без `setScale`, а `purchase` — `setScale(2, HALF_UP)` при сборке групп → красный `suppliersFromMainAcceptedOffer_perLineRounding_unassignedHint` (2,01 ≠ 2,02).
  - Своя сумма не побеждает: `amount = offerAmount` всегда → красный `offerEditedAfterAcceptance_amountsFollowUntilOwn`.
  - Валюта не проверяется: `amount = own ? r.getAmount() : offerAmount` → красный `foreignCurrencyWithoutOwnAmount_unknown`.
  - Главное — первое принятое: `main.put(...)` вместо `putIfAbsent` → красный первый тест (id другого КП).
  - Итог 0 считается суммой: убрать `positive(...)` у `main.total()` → красный `noAcceptedOffer_hiddenUntilData_zeroTotalUnknown`.

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/execution/DealExecutionData.java \
  src/main/java/com/vladoose/nir/service/execution/DealExecutionLoader.java \
  src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java \
  src/main/java/com/vladoose/nir/repository/DistributorRepository.java \
  src/main/java/com/vladoose/nir/service/offer/OfferLabels.java \
  src/test/java/com/vladoose/nir/execution/ExecutionTestData.java \
  src/test/java/com/vladoose/nir/execution/DealExecutionLoaderTest.java
git commit -m "$(cat <<'EOF'
feat(execution): загрузка исполнения — поставщики из принятого КП и строк, платежи, снимок шестью запросами

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 4: Этапы заявки — десять шагов, «Дальше» исполнения, код этапа `EXEC_*`

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestStageCalculator.java`
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestProgressBuilder.java`
- Modify (тесты, ждавшие прежнего): `src/test/java/com/vladoose/nir/deal/PrivateRequestProgressTest.java`, `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage3Test.java`
- Test: `src/test/java/com/vladoose/nir/execution/ExecutionProgressTest.java`

**Interfaces:**
- Consumes: `DealExecutionLoader.load(...)`, `DealExecutionData.snapshot()` (задача 3), `DealExecutionCalculator.evaluate(...)` (задача 2).
- Produces:
  - `PrivateRequestStageCalculator.Summary(String code, String text, long lineCount, DealExecutionData exec)` (+ прежний конструктор на три поля). У заявки в работе с принятым КП код — `EXEC_*`, текст — чип §3.3; `KP_ACCEPTED` наружу не отдаётся (решение при планировании 2).
  - `DealProgress` заявки — десять шагов: `SOURCE, LINES, PRICES, OFFER, CONTRACT, PAYMENT, SUPPLIERS, DELIVERY, DOCS, RESULT`.

- [ ] **Step 1: Тест (красный — шагов исполнения ещё нет)**

`src/test/java/com/vladoose/nir/execution/ExecutionProgressTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.dto.response.PrivateRequestListItemResponse;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
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

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/** Этапы заявки с исполнением (спека 2026-10-19-deal-execution §3): шаги, «Дальше», код этапа в виде и в списке. */
@SpringBootTest
@Transactional
class ExecutionProgressTest {

    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestViewBuilder views;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealExecutionRepository executions;
    @Autowired DealPaymentRepository payments;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Tender accepted(String total) {
        Tender t = requests.createEmpty();
        Distributor d = distributors.save(Distributor.builder().name("ZZ Этапы " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, total, 481), d.getId(), "100", "1"));
        return t;
    }

    private PrivateRequestViewResponse view(Tender t) {
        em.flush();
        em.clear();
        return views.build(requests.findById(t.getId()));
    }

    private static String states(DealProgress p) {
        return p.steps().stream().map(s -> s.key() + ":" + s.state()).collect(joining(" "));
    }

    @Test
    void acceptedOffer_executionStepsNextAndListChip() {
        Tender t = accepted("3070455");
        PrivateRequestViewResponse v = view(t);
        assertThat(states(v.getProgress())).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE "
                + "CONTRACT:CURRENT PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO RESULT:TODO");
        assertThat(v.getProgress().next()).isEqualTo(new Next("Клиент согласился на КП № 481 — договор или счёт", "EXEC_CONTRACT", null));
        assertThat(v.getStage()).isEqualTo("EXEC_CONTRACT");
        assertThat(v.getStageText()).isEqualTo("Договор");

        PrivateRequestListItemResponse row = views.list(List.of(requests.findById(t.getId()))).get(0);
        assertThat(row.getStage()).isEqualTo("EXEC_CONTRACT");
        assertThat(row.getStageText()).isEqualTo("Договор");
    }

    @Test
    void allDone_resultCurrent_closeWon() {
        Tender t = accepted("1000");
        executions.save(DealExecution.builder().tenderId(t.getId()).contractKind(ContractKind.NONE).fromStock(true)
                .deliveredOn(LocalDate.of(2026, 10, 19)).docsOn(LocalDate.of(2026, 10, 19)).build());
        payments.save(DealPayment.builder().tenderId(t.getId()).amount(new BigDecimal("1000")).paidOn(LocalDate.of(2026, 10, 19)).build());
        PrivateRequestViewResponse v = view(t);
        assertThat(states(v.getProgress())).endsWith("CONTRACT:SKIPPED PAYMENT:DONE SUPPLIERS:SKIPPED DELIVERY:DONE DOCS:DONE RESULT:CURRENT");
        assertThat(v.getProgress().next()).isEqualTo(new Next("Всё сделано — закройте сделку", "CLOSE_WON", null));
        assertThat(v.getStage()).isEqualTo("EXEC_DONE");
    }

    @Test
    void closedWonWithoutData_executionStepsSkipped() {
        Tender t = accepted("1000");
        requests.close(t.getId(), PrivateRequestStatus.WON);
        PrivateRequestViewResponse v = view(t);
        assertThat(states(v.getProgress())).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE "
                + "CONTRACT:SKIPPED PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:SKIPPED DOCS:SKIPPED RESULT:DONE");
        assertThat(v.getProgress().steps().subList(4, 9)).allMatch(s -> s.detail() == null);
        assertThat(v.getStage()).isEqualTo("WON");
    }

    @Test
    void offerRevertedAfterExecution_stepsByDataNextAboutOffer() {
        Tender t = accepted("1000");
        executions.save(DealExecution.builder().tenderId(t.getId()).contractNumber("42").build());
        em.flush();
        ClientOffer o = offers.findByTenderIdOrderByIdDesc(t.getId()).get(0);
        o.setStatus(ClientOfferStatus.SENT);
        o.setSentAt(OffsetDateTime.now());
        PrivateRequestViewResponse v = view(t);
        assertThat(v.getStage()).isEqualTo("KP_SENT");
        assertThat(v.getProgress().next().action()).isEqualTo("OFFER_DECISION");
        assertThat(states(v.getProgress())).contains("OFFER:CURRENT CONTRACT:DONE PAYMENT:TODO");
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.ExecutionProgressTest'`
Expected: FAIL — шагов 5, код `KP_ACCEPTED` (например, `expected "SOURCE:DONE … RESULT:TODO" but was "SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE RESULT:CURRENT"`).

- [ ] **Step 3: Калькулятор этапа — снимок и коды `EXEC_*`**

В `PrivateRequestStageCalculator`:

1. Запись итога и конструктор:

```java
    /** exec — исполнение сделки (тот же снимок дальше строит шаги, вид раздела и ленту — одна загрузка на страницу). */
    public record Summary(String code, String text, long lineCount, DealExecutionData exec) {
        public Summary(String code, String text, long lineCount) {
            this(code, text, lineCount, null);
        }
    }
```

2. Поле `private final DealExecutionLoader execution;` — последним параметром конструктора (импорты `com.vladoose.nir.service.execution.DealExecutionCalculator`, `DealExecutionData`, `DealExecutionLoader`).

3. В `of(List<Tender> requests)` цикл заполнения `out` заменить:

```java
        Map<Long, DealExecutionData> exec = execution.load(requests);
        for (Tender t : requests) {
            long lineCount = lines.getOrDefault(t.getId(), 0L);
            Summary s = summary(PrivateRequestStatus.of(t.getStatus()), lineCount,
                    asked.getOrDefault(t.getId(), Map.of()), priced.getOrDefault(t.getId(), Set.of()).size(),
                    kp.getOrDefault(t.getId(), Set.of()));
            DealExecutionData d = exec.get(t.getId());
            if ("KP_ACCEPTED".equals(s.code())) {
                // клиент согласился, заявка в работе — этап исполнения (спека 2026-10-19-deal-execution §3.3)
                DealExecutionCalculator.Result r = DealExecutionCalculator.evaluate(d.snapshot(), DealExecutionCalculator.Mode.ACTIVE);
                s = new Summary(r.stageCode(), r.stageText(), lineCount, d);
            } else {
                s = new Summary(s.code(), s.text(), s.lineCount(), d);
            }
            out.put(t.getId(), s);
        }
        return out;
```

Обновить javadoc класса: «…закрыта → КП клиенту (по лучшему статусу; принятое — этап исполнения `EXEC_*`) → …; шесть запросов исполнения — `DealExecutionLoader`».

- [ ] **Step 4: Строитель этапов — десять шагов**

В `PrivateRequestProgressBuilder`:

1. Поле `private final DealExecutionLoader execution;` — последним параметром конструктора.
2. В `build(...)` после `long lines = stage.lineCount();`:

```java
        DealExecutionData exec = stage.exec() != null ? stage.exec() : execution.load(t);
        boolean executing = code.startsWith("EXEC_");
```

3. Вычисление `phase` — без `KP_ACCEPTED`:

```java
        int phase = switch (code) {
            case "EMPTY" -> 1;
            case "NEW", "REQUESTED" -> 2;
            case "PRICED", "KP_DRAFT", "KP_SENT", "KP_REJECTED" -> 3;
            default -> 4;   // исполнение (EXEC_*) и закрытая сделка: Обращение … КП — сделаны
        };
        DealExecutionCalculator.Mode mode = executing ? DealExecutionCalculator.Mode.ACTIVE
                : status.closed() ? DealExecutionCalculator.Mode.CLOSED : DealExecutionCalculator.Mode.PASSIVE;
        DealExecutionCalculator.Result ex = DealExecutionCalculator.evaluate(exec.snapshot(), mode);
```

4. Список шагов — прежние четыре, затем шаги исполнения, затем Итог:

```java
        List<Step> steps = new ArrayList<>(List.of(
                step("SOURCE", 0, phase, lead != null ? "Обращение" : "Создана",
                        lead != null ? DealTexts.channel(lead.getChannel()) + " " + DealTexts.ddMM(lead.getReceivedAt(), zone)
                                : DealTexts.ddMM(t.getCreatedAt(), zone)),
                step("LINES", 1, phase, "Позиции", lines == 0 ? "нет позиций" : DealTexts.positions(lines)),
                step("PRICES", 2, phase, "Цены", pricesDetail),
                step("OFFER", 3, phase, "КП", main == null ? null : OfferLabels.numberOrDate(main) + " · " + offerState(main, zone))));
        steps.addAll(ex.steps());
        steps.add(new Step("RESULT", status.closed() ? "DONE" : ex.allDone() ? "CURRENT" : "TODO", "Итог", outcome(status, closedAt)));
```

5. «Дальше»: `Next next = executing ? ex.next() : switch (code) { … }` — в `switch` удалить строку `case "KP_ACCEPTED" -> …` (остальные ветки и `default` — как были).

6. Импорты: `java.util.ArrayList`, `com.vladoose.nir.service.execution.DealExecutionCalculator`, `DealExecutionData`, `DealExecutionLoader`. Javadoc класса: «…этапы и «Дальше» (спека 2026-10-18 §3.1) и исполнение сделки (спека 2026-10-19-deal-execution §3)».

- [ ] **Step 5: Прежние тесты — под десять шагов**

`PrivateRequestProgressTest`:
- поле `private static final String EXEC_TODO = " CONTRACT:TODO PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO";`
- `emptyRequest`: `"SOURCE:DONE LINES:CURRENT PRICES:TODO OFFER:TODO" + EXEC_TODO + " RESULT:TODO"`;
- `newRequestAsksForPrices`: `"SOURCE:DONE LINES:DONE PRICES:CURRENT OFFER:TODO" + EXEC_TODO + " RESULT:TODO"`;
- `pricedSuggestsOffer`: `"SOURCE:DONE LINES:DONE PRICES:DONE OFFER:CURRENT" + EXEC_TODO + " RESULT:TODO"`;
- `offerLifecycle` после `ACCEPTED`:

```java
        p = progress(t);
        assertThat(states(p)).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE "
                + "CONTRACT:CURRENT PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO RESULT:TODO");
        assertThat(p.next()).isEqualTo(new Next("Клиент согласился на КП № " + o.getNumber() + " — договор или счёт",
                "EXEC_CONTRACT", null));
```

- `closedShowsOutcomeWithDate`: строка шагов — `"SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE CONTRACT:SKIPPED PAYMENT:SKIPPED SUPPLIERS:SKIPPED DELIVERY:SKIPPED DOCS:SKIPPED RESULT:DONE"`, подпись Итога — `p.steps().get(9).detail()`.

`PrivateRequestStage3Test.stageFollowsBestKpStatus_closedRequestStaysClosed`: `assertThat(stages.of(t).code()).isEqualTo("EXEC_CONTRACT");` вместо `"KP_ACCEPTED"`.

Найти, не ждёт ли ещё кто прежнего: `grep -rn "KP_ACCEPTED\|закройте сделку\|RESULT:" src/test/java | grep -v TenderProgressTest` — поправить тем же образом.

- [ ] **Step 6: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.ExecutionProgressTest' --tests 'com.vladoose.nir.deal.PrivateRequestProgressTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestStage3Test' --tests 'com.vladoose.nir.readyoffer.ReadyOfferHintsTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestListTest'`
Expected: PASS.

- [ ] **Step 7: Мутации**
  - В калькуляторе этапа не превращать `KP_ACCEPTED` в `EXEC_*` → красные `acceptedOffer_executionStepsNextAndListChip` (код и чип), `PrivateRequestStage3Test`.
  - В строителе `mode` всегда `PASSIVE` → красные `closedWonWithoutData_executionStepsSkipped`, `acceptedOffer…` («Дальше» пусто).
  - Итог `CURRENT` убрать (`ex.allDone() ? "CURRENT"` → `"TODO"`) → красный `allDone_resultCurrent_closeWon`.

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestStageCalculator.java \
  src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestProgressBuilder.java \
  src/test/java/com/vladoose/nir/execution/ExecutionProgressTest.java \
  src/test/java/com/vladoose/nir/deal/PrivateRequestProgressTest.java \
  src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage3Test.java
git commit -m "$(cat <<'EOF'
feat(execution): этапы заявки — договор, оплата, поставщики, поставка, документы; «Дальше» и чип исполнения

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 5: Проверка ввода и запись — поля договора, отметки, платежи

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/execution/ExecutionInput.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/ExecutionField.java`, `ExecutionMark.java`
- Create: `src/main/java/com/vladoose/nir/service/execution/DealExecutionService.java`
- Test: `src/test/java/com/vladoose/nir/service/execution/ExecutionInputTest.java`, `src/test/java/com/vladoose/nir/execution/DealExecutionWriteTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.findById`, `blankToNull` (есть); репозитории (задача 1); `DealExecutionLoader` (задача 3).
- Produces:
  - `ExecutionInput.amount(String|BigDecimal, label)`, `pct(String)`, `date(String|LocalDate, label, today)`, `today(Clock, ZoneId)`, `kind(String)`, `currency(String)`, `bool(String)`, `text(String, max, label)`; константы `NOTE_MAX = 2000`, `NUMBER_MAX = 100`, `FULL_PREPAY = 100.00`, `CURRENCIES`.
  - `ExecutionField {CONTRACT_KIND, CONTRACT_NUMBER, CONTRACT_DATE, CONTRACT_NOTE, PREPAY_PCT, AMOUNT, FROM_STOCK}`, `ExecutionMark {DELIVERY, DOCS}`.
  - `DealExecutionService`: `updateField(Long, ExecutionField, String) → Tender`, `setMark(Long, ExecutionMark, String on, String note) → Tender`, `addPayment(Long, PaymentInput) → Tender`, `updatePayment(Long, Long, PaymentInput) → Tender`, `deletePayment(Long, Long) → Tender`; запись `PaymentInput(Long distributorId, BigDecimal amount, LocalDate paidOn, String note)`; пакетные помощники `ensureRow(Tender)`, `ensureSupplier(Tender, Long)`, `payment(Tender, Long)`, `today(Tender)`, `market(Tender)` — ими пользуются задачи 6–7.

- [ ] **Step 1: Тест проверки ввода (красный)**

`src/test/java/com/vladoose/nir/service/execution/ExecutionInputTest.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка ввода исполнения (спека 2026-10-19-deal-execution §7). */
class ExecutionInputTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void amounts() {
        assertThat(ExecutionInput.amount("1 535 227,50", "Сумма")).isEqualByComparingTo("1535227.50");
        assertThat(ExecutionInput.amount("1 535 228", "Сумма")).isEqualByComparingTo("1535228");
        assertThat(ExecutionInput.amount("9999999999999.99", "Сумма")).isEqualByComparingTo("9999999999999.99");
        assertThat(ExecutionInput.amount("1535227.500", "Сумма")).isEqualByComparingTo("1535227.50");
        assertThatThrownBy(() -> ExecutionInput.amount("0", "Сумма")).hasMessage("Сумма: сумма должна быть больше нуля");
        assertThatThrownBy(() -> ExecutionInput.amount("-5", "Сумма")).hasMessage("Сумма: сумма должна быть больше нуля");
        assertThatThrownBy(() -> ExecutionInput.amount("1.005", "Сумма")).hasMessage("Сумма: не больше 2 знаков после запятой");
        assertThatThrownBy(() -> ExecutionInput.amount("10000000000000", "Сумма"))
                .hasMessage("Сумма: слишком большая сумма — не больше 13 цифр до запятой");
        assertThatThrownBy(() -> ExecutionInput.amount("1e200000000", "Сумма"))
                .hasMessage("Сумма: слишком большая сумма — не больше 13 цифр до запятой");
        assertThatThrownBy(() -> ExecutionInput.amount("abc", "Сумма")).hasMessage("Сумма: нужно число, например 1 535 228");
        assertThatThrownBy(() -> ExecutionInput.amount((java.math.BigDecimal) null, "Сумма")).hasMessage("Сумма: укажите сумму");
    }

    @Test
    void dates_notAfterMarketToday() {
        assertThat(ExecutionInput.date("2026-10-20", "Дата", TODAY)).isEqualTo(TODAY);
        assertThatThrownBy(() -> ExecutionInput.date("2026-10-21", "Дата", TODAY))
                .hasMessage("Дата: дата ещё не наступила — отмечаются только факты");
        assertThatThrownBy(() -> ExecutionInput.date("20.10.2026", "Дата", TODAY)).hasMessage("Дата: дата в виде ГГГГ-ММ-ДД");
        assertThatThrownBy(() -> ExecutionInput.date((LocalDate) null, "Дата", TODAY)).hasMessage("Дата: укажите дату");
    }

    @Test
    void todayIsMarketDate_notUtc() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-10-19T19:30:00Z"), ZoneOffset.UTC);   // 00:30 20.10 по Уральску
        assertThat(ExecutionInput.today(lateUtc, Market.KZ.zone())).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(ExecutionInput.today(lateUtc, Market.RF.zone())).isEqualTo(LocalDate.of(2026, 10, 19));   // Самара — 23:30
        assertThat(ExecutionInput.date("2026-10-20", "Дата", ExecutionInput.today(lateUtc, Market.KZ.zone()))).isEqualTo(TODAY);
    }

    @Test
    void pctKindCurrencyBoolText() {
        assertThat(ExecutionInput.pct("50")).isEqualByComparingTo("50");
        assertThat(ExecutionInput.pct("12,5 %")).isEqualByComparingTo("12.5");
        assertThat(ExecutionInput.pct("0")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> ExecutionInput.pct("101")).hasMessage("Предоплата: число от 0 до 100");
        assertThatThrownBy(() -> ExecutionInput.pct("-1")).hasMessage("Предоплата: число от 0 до 100");
        assertThatThrownBy(() -> ExecutionInput.pct("половина")).hasMessage("Предоплата: число от 0 до 100");
        assertThat(ExecutionInput.kind("INVOICE")).isEqualTo(ContractKind.INVOICE);
        assertThatThrownBy(() -> ExecutionInput.kind("ACT")).hasMessage("Вид — договор, счёт или без договора");
        assertThat(ExecutionInput.currency("rub")).isEqualTo("RUB");
        assertThatThrownBy(() -> ExecutionInput.currency("GBP")).hasMessage("Валюта — тг, руб., $, € или юань");
        assertThat(ExecutionInput.bool("true")).isTrue();
        assertThat(ExecutionInput.bool(null)).isFalse();
        assertThatThrownBy(() -> ExecutionInput.bool("yes")).hasMessage("Нужно true или false");
        assertThat(ExecutionInput.text("  ", 10, "Заметка")).isNull();
        assertThat(ExecutionInput.text(" 42 ", 10, "Заметка")).isEqualTo("42");
        assertThatThrownBy(() -> ExecutionInput.text("x".repeat(11), 10, "Заметка")).hasMessage("Заметка: не больше 10 символов");
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.execution.ExecutionInputTest'`
Expected: FAIL — `cannot find symbol: class ExecutionInput`.

- [ ] **Step 3: `ExecutionInput`**

`service/execution/ExecutionInput.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.entity.ContractKind;
import com.vladoose.nir.exception.BadRequestException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Проверка ввода исполнения сделки (спека 2026-10-19-deal-execution §7): всё — до записи, отказ — 400 с русским текстом
 * (аннотации проверки отдают «Ошибка валидации данных» без поля — решение при планировании 9). Масштаб и число цифр
 * суммы — до округления: число вроде 1e200000000 иначе держало бы процессор (CLAUDE.md §14).
 */
public final class ExecutionInput {

    public static final int NOTE_MAX = 2000;
    public static final int NUMBER_MAX = 100;
    public static final BigDecimal FULL_PREPAY = new BigDecimal("100.00");
    public static final List<String> CURRENCIES = List.of("KZT", "RUB", "USD", "EUR", "CNY");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ExecutionInput() {}

    /** Сумма из поля: пробелы между разрядами (и неразрывные), запятая или точка. */
    public static BigDecimal amount(String raw, String label) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw.replaceAll("[\\s\\u00A0\\u202F]", "").replace(',', '.'));
        } catch (NumberFormatException | NullPointerException e) {
            throw new BadRequestException(label + ": нужно число, например 1 535 228");
        }
        return amount(v, label);
    }

    /** > 0, не больше 13 цифр до запятой и 2 после (предел NUMERIC(15,2)). */
    public static BigDecimal amount(BigDecimal v, String label) {
        if (v == null) throw new BadRequestException(label + ": укажите сумму");
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() > 2) throw new BadRequestException(label + ": не больше 2 знаков после запятой");
        if (s.precision() - s.scale() > 13) {
            throw new BadRequestException(label + ": слишком большая сумма — не больше 13 цифр до запятой");
        }
        if (s.signum() <= 0) throw new BadRequestException(label + ": сумма должна быть больше нуля");
        return s.setScale(2);
    }

    /** Предоплата, %: 0…100, не больше 2 знаков после запятой; «%» и пробелы допускаются. */
    public static BigDecimal pct(String raw) {
        BigDecimal v;
        try {
            v = new BigDecimal(raw.replaceAll("[\\s\\u00A0\\u202F%]", "").replace(',', '.')).stripTrailingZeros();
        } catch (NumberFormatException | NullPointerException e) {
            throw new BadRequestException("Предоплата: число от 0 до 100");
        }
        if (v.scale() > 2 || v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw new BadRequestException("Предоплата: число от 0 до 100");
        }
        return v.setScale(2);
    }

    public static LocalDate date(String raw, String label, LocalDate today) {
        LocalDate d;
        try {
            d = LocalDate.parse(raw.strip());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException(label + ": дата в виде ГГГГ-ММ-ДД");
        }
        return date(d, label, today);
    }

    /** Отмечаются факты, не планы: дата не позже сегодняшней по часам рынка. */
    public static LocalDate date(LocalDate d, String label, LocalDate today) {
        if (d == null) throw new BadRequestException(label + ": укажите дату");
        if (d.isAfter(today)) throw new BadRequestException(label + ": дата ещё не наступила — отмечаются только факты");
        return d;
    }

    /** «Сегодня» по часам рынка: прод живёт в UTC, а у Уральска уже завтра с 19:00 UTC. */
    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    public static ContractKind kind(String raw) {
        try {
            return ContractKind.valueOf(raw.strip());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Вид — договор, счёт или без договора");
        }
    }

    public static String currency(String raw) {
        String c = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(c)) throw new BadRequestException("Валюта — тг, руб., $, € или юань");
        return c;
    }

    public static boolean bool(String raw) {
        if (raw == null || "false".equals(raw)) return false;
        if ("true".equals(raw)) return true;
        throw new BadRequestException("Нужно true или false");
    }

    /** Пусто — null; без пробелов по краям; длиннее max — 400. */
    public static String text(String raw, int max, String label) {
        String v = raw == null ? null : raw.strip();
        if (v == null || v.isEmpty()) return null;
        if (v.length() > max) throw new BadRequestException(label + ": не больше " + max + " символов");
        return v;
    }
}
```

- [ ] **Step 4: Зелёный (проверка ввода)**

Run: `./gradlew test --tests 'com.vladoose.nir.service.execution.ExecutionInputTest'`
Expected: PASS (4 теста).

- [ ] **Step 5: Тест записи (красный — сервиса нет)**

`src/test/java/com/vladoose/nir/execution/DealExecutionWriteTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.ExecutionMark;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Запись исполнения (спека 2026-10-19-deal-execution §7, §9): поля договора, отметки, платежи; гард и отказ без записи. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class DealExecutionWriteTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealExecutionRepository executions;
    @Autowired DealPaymentRepository payments;
    @Autowired DealSupplierRepository suppliers;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.now(Market.KZ.zone());

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist() {
        return distributors.save(Distributor.builder().name("ZZ Запись " + System.nanoTime()).build());
    }

    private Tender accepted(Distributor... ds) {
        Tender t = requests.createEmpty();
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "1000", 1);
        for (Distributor d : ds) line(o, d.getId(), "100", "1");
        offers.save(o);
        return t;
    }

    private DealExecution row(Tender t) {
        em.flush();
        em.clear();
        return executions.findById(t.getId()).orElseThrow();
    }

    @Test
    void contractFields_saved_andReset() {
        Tender t = accepted();
        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "INVOICE");
        service.updateField(t.getId(), ExecutionField.CONTRACT_NUMBER, " 15 ");
        service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, DAY.toString());
        service.updateField(t.getId(), ExecutionField.PREPAY_PCT, "50");
        service.updateField(t.getId(), ExecutionField.AMOUNT, "3 200 000");
        DealExecution e = row(t);
        assertThat(e.getContractKind()).isEqualTo(ContractKind.INVOICE);
        assertThat(e.getContractNumber()).isEqualTo("15");
        assertThat(e.getContractDate()).isEqualTo(DAY);
        assertThat(e.getPrepayPct()).isEqualByComparingTo("50");
        assertThat(e.getAmount()).isEqualByComparingTo("3200000");
        assertThat(e.getUpdatedBy()).isEqualTo("vlad");

        service.updateField(t.getId(), ExecutionField.AMOUNT, "");        // вернуть по КП
        service.updateField(t.getId(), ExecutionField.PREPAY_PCT, null);  // по умолчанию — 100 %
        e = row(t);
        assertThat(e.getAmount()).isNull();
        assertThat(e.getPrepayPct()).isEqualByComparingTo("100");
    }

    @Test
    void rejectedInputWritesNothing() {
        Tender t = accepted();
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, DAY.plusDays(1).toString()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("ещё не наступила");
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.AMOUNT, "-1"))
                .isInstanceOf(BadRequestException.class).hasMessage("Сумма сделки: сумма должна быть больше нуля");
        em.flush();
        assertThat(executions.findById(t.getId())).isEmpty();
    }

    @Test
    void marks_setAndClear() {
        Tender t = accepted();
        service.setMark(t.getId(), ExecutionMark.DELIVERY, DAY.toString(), "обучение проведено");
        service.setMark(t.getId(), ExecutionMark.DOCS, DAY.toString(), null);
        DealExecution e = row(t);
        assertThat(e.getDeliveredOn()).isEqualTo(DAY);
        assertThat(e.getDeliveryNote()).isEqualTo("обучение проведено");
        assertThat(e.getDocsOn()).isEqualTo(DAY);

        service.setMark(t.getId(), ExecutionMark.DELIVERY, null, "что-то");   // снять — заметка уходит вместе с датой
        e = row(t);
        assertThat(e.getDeliveredOn()).isNull();
        assertThat(e.getDeliveryNote()).isNull();
    }

    @Test
    void clientPayment_addUpdateDelete() {
        Tender t = accepted();
        service.addPayment(t.getId(), new PaymentInput(null, new BigDecimal("1535227.50"), DAY, " предоплата "));
        em.flush();
        DealPayment p = payments.findByTenderIdIn(List.of(t.getId())).get(0);
        assertThat(p.getNote()).isEqualTo("предоплата");
        assertThat(p.getDealSupplierId()).isNull();
        assertThat(p.getCreatedBy()).isEqualTo("vlad");

        service.updatePayment(t.getId(), p.getId(), new PaymentInput(null, new BigDecimal("1535228"), DAY, null));
        em.flush();
        em.clear();
        assertThat(payments.findById(p.getId()).orElseThrow().getAmount()).isEqualByComparingTo("1535228");

        service.deletePayment(t.getId(), p.getId());
        em.flush();
        assertThat(payments.findByTenderIdIn(List.of(t.getId()))).isEmpty();
    }

    @Test
    void supplierPayment_createsRowForOfferSupplier_strangerIs404() {
        Distributor fotek = dist();
        Tender t = accepted(fotek);
        service.addPayment(t.getId(), new PaymentInput(fotek.getId(), new BigDecimal("100"), DAY, null));
        em.flush();
        DealSupplier s = suppliers.findByTenderIdAndDistributorId(t.getId(), fotek.getId()).orElseThrow();
        assertThat(s.getCurrency()).isEqualTo("KZT");
        assertThat(s.isManual()).isFalse();

        Distributor stranger = dist();
        assertThatThrownBy(() -> service.addPayment(t.getId(), new PaymentInput(stranger.getId(), BigDecimal.TEN, DAY, null)))
                .isInstanceOf(NotFoundException.class).hasMessage("Поставщика нет в сделке");
    }

    @Test
    void paymentOfOtherDeal_404() {
        Tender a = accepted(), b = accepted();
        service.addPayment(a.getId(), new PaymentInput(null, BigDecimal.TEN, DAY, null));
        em.flush();
        Long pid = payments.findByTenderIdIn(List.of(a.getId())).get(0).getId();
        assertThatThrownBy(() -> service.deletePayment(b.getId(), pid)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updatePayment(b.getId(), pid, new PaymentInput(null, BigDecimal.ONE, DAY, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherMarketDeal_404() {
        Tender t = accepted();
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.CONTRACT_NUMBER, "1"))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 6: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionWriteTest'`
Expected: FAIL — `cannot find symbol: class DealExecutionService`.

- [ ] **Step 7: Перечисления и сервис**

`dto/request/ExecutionField.java`:

```java
package com.vladoose.nir.dto.request;

/** Поле исполнения сделки, сохраняемое по выходу из поля (спека 2026-10-19-deal-execution §9). */
public enum ExecutionField { CONTRACT_KIND, CONTRACT_NUMBER, CONTRACT_DATE, CONTRACT_NOTE, PREPAY_PCT, AMOUNT, FROM_STOCK }
```

`dto/request/ExecutionMark.java`:

```java
package com.vladoose.nir.dto.request;

/** Отметка исполнения формой «дата + заметка»: поставка клиенту, закрывающие документы. */
public enum ExecutionMark { DELIVERY, DOCS }
```

`service/execution/DealExecutionService.java` (поставщики и файлы добавят задачи 6–7; зависимости — сразу все, чтобы конструктор не менять трижды):

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.ExecutionMark;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.readyoffer.ReadyOfferHints;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * Запись исполнения сделки (спека 2026-10-19-deal-execution §6–§7, §9): поля договора и суммы, отметки, платежи,
 * поставщики, файлы. Гард — PrivateRequestService.findById (частная заявка текущего рынка, иначе 404). Ввод проверяется
 * до первой записи; отказ — 400 с русским текстом.
 */
@Service
public class DealExecutionService {

    /** distributorId пусто — платёж клиента. */
    public record PaymentInput(Long distributorId, BigDecimal amount, LocalDate paidOn, String note) {}

    private record Checked(BigDecimal amount, LocalDate paidOn, String note) {}

    private final PrivateRequestService privateRequests;
    private final DealExecutionRepository executions;
    private final DealSupplierRepository suppliers;
    private final DealPaymentRepository payments;
    private final DealFileRepository files;
    private final DistributorRepository distributors;
    private final DealExecutionLoader loader;
    private final ReadyOfferHints hints;
    private final ChatAttachmentRepository attachments;
    private final Clock clock = Clock.systemUTC();

    public DealExecutionService(PrivateRequestService privateRequests, DealExecutionRepository executions,
                                DealSupplierRepository suppliers, DealPaymentRepository payments, DealFileRepository files,
                                DistributorRepository distributors, DealExecutionLoader loader, ReadyOfferHints hints,
                                ChatAttachmentRepository attachments) {
        this.privateRequests = privateRequests;
        this.executions = executions;
        this.suppliers = suppliers;
        this.payments = payments;
        this.files = files;
        this.distributors = distributors;
        this.loader = loader;
        this.hints = hints;
        this.attachments = attachments;
    }

    /** Поле договора, суммы, предоплаты или «Со склада»; пустое значение — сброс (сумма — по КП, предоплата — 100 %). */
    @Transactional
    public Tender updateField(Long id, ExecutionField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле");
        Tender t = privateRequests.findById(id);
        String v = PrivateRequestService.blankToNull(value);
        Consumer<DealExecution> apply = switch (field) {
            case CONTRACT_KIND -> {
                ContractKind k = v == null ? null : ExecutionInput.kind(v);
                yield e -> e.setContractKind(k);
            }
            case CONTRACT_NUMBER -> {
                String n = ExecutionInput.text(v, ExecutionInput.NUMBER_MAX, "№ договора");
                yield e -> e.setContractNumber(n);
            }
            case CONTRACT_DATE -> {
                LocalDate d = v == null ? null : ExecutionInput.date(v, "Дата договора", today(t));
                yield e -> e.setContractDate(d);
            }
            case CONTRACT_NOTE -> {
                String n = ExecutionInput.text(v, ExecutionInput.NOTE_MAX, "Заметка");
                yield e -> e.setContractNote(n);
            }
            case PREPAY_PCT -> {
                BigDecimal p = v == null ? ExecutionInput.FULL_PREPAY : ExecutionInput.pct(v);
                yield e -> e.setPrepayPct(p);
            }
            case AMOUNT -> {
                BigDecimal a = v == null ? null : ExecutionInput.amount(v, "Сумма сделки");
                yield e -> e.setAmount(a);
            }
            case FROM_STOCK -> {
                boolean on = ExecutionInput.bool(v);
                if (on && supplierMarks(t.getId())) {
                    throw new BadRequestException("Сначала уберите отметки, платежи и файлы поставщиков");
                }
                yield e -> e.setFromStock(on);
            }
        };
        DealExecution e = ensureRow(t);
        apply.accept(e);
        e.setUpdatedBy(CurrentUser.name());
        return t;
    }

    /** Поставка или документы: дата и заметка; дата пуста — отметка снимается вместе с заметкой. */
    @Transactional
    public Tender setMark(Long id, ExecutionMark mark, String on, String note) {
        if (mark == null) throw new BadRequestException("Неизвестная отметка");
        Tender t = privateRequests.findById(id);
        LocalDate date = blank(on) ? null
                : ExecutionInput.date(on, mark == ExecutionMark.DELIVERY ? "Дата поставки" : "Дата документов", today(t));
        String n = date == null ? null : ExecutionInput.text(note, ExecutionInput.NOTE_MAX, "Заметка");
        DealExecution e = ensureRow(t);
        if (mark == ExecutionMark.DELIVERY) {
            e.setDeliveredOn(date);
            e.setDeliveryNote(n);
        } else {
            e.setDocsOn(date);
            e.setDocsNote(n);
        }
        e.setUpdatedBy(CurrentUser.name());
        return t;
    }

    @Transactional
    public Tender addPayment(Long id, PaymentInput in) {
        Tender t = privateRequests.findById(id);
        Checked c = check(t, in);
        Long supplierRow = null;
        if (in.distributorId() != null) {
            DealSupplier s = ensureSupplier(t, in.distributorId());
            if (s.isExcluded()) throw new BadRequestException("Поставщик отмечен «Не заказываем» — платёж ему не вносится");
            supplierRow = s.getId();
        }
        payments.save(DealPayment.builder().tenderId(t.getId()).dealSupplierId(supplierRow).amount(c.amount())
                .paidOn(c.paidOn()).note(c.note()).createdBy(CurrentUser.name()).build());
        return t;
    }

    /** Сумма, дата, заметка; кому платёж — не меняется. */
    @Transactional
    public Tender updatePayment(Long id, Long paymentId, PaymentInput in) {
        Tender t = privateRequests.findById(id);
        DealPayment p = payment(t, paymentId);
        Checked c = check(t, in);
        p.setAmount(c.amount());
        p.setPaidOn(c.paidOn());
        p.setNote(c.note());
        return t;
    }

    /** Файлы платежа уходят каскадом. */
    @Transactional
    public Tender deletePayment(Long id, Long paymentId) {
        Tender t = privateRequests.findById(id);
        payments.delete(payment(t, paymentId));
        return t;
    }

    private Checked check(Tender t, PaymentInput in) {
        if (in == null) throw new BadRequestException("Нет данных платежа");
        return new Checked(ExecutionInput.amount(in.amount(), "Сумма платежа"),
                ExecutionInput.date(in.paidOn(), "Дата платежа", today(t)),
                ExecutionInput.text(in.note(), ExecutionInput.NOTE_MAX, "Заметка"));
    }

    /** Платёж этой сделки, иначе 404. */
    DealPayment payment(Tender t, Long paymentId) {
        if (paymentId == null) throw new NotFoundException("Платёж не найден");
        return payments.findById(paymentId).filter(p -> p.getTenderId().equals(t.getId()))
                .orElseThrow(() -> new NotFoundException("Платёж не найден"));
    }

    DealExecution ensureRow(Tender t) {
        return executions.findById(t.getId())
                .orElseGet(() -> executions.save(DealExecution.builder().tenderId(t.getId()).build()));
    }

    /** Строка поставщика сделки; поставщик главного принятого КП заводится строкой (валюта рынка), чужой — 404. */
    DealSupplier ensureSupplier(Tender t, Long distributorId) {
        if (distributorId == null) throw new BadRequestException("Не указан поставщик");
        return suppliers.findByTenderIdAndDistributorId(t.getId(), distributorId).orElseGet(() -> {
            if (!loader.load(t).inOffer(distributorId)) throw new NotFoundException("Поставщика нет в сделке");
            return suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(distributorId)
                    .currency(market(t).currencyCode()).build());
        });
    }

    private boolean supplierMarks(Long tenderId) {
        return suppliers.existsByTenderIdAndOrderedOnIsNotNull(tenderId) || suppliers.existsByTenderIdAndShippedOnIsNotNull(tenderId)
                || payments.existsByTenderIdAndDealSupplierIdIsNotNull(tenderId)
                || files.existsByTenderIdAndStage(tenderId, DealFileStage.SUPPLIER);
    }

    LocalDate today(Tender t) {
        return ExecutionInput.today(clock, market(t).zone());
    }

    static Market market(Tender t) {
        return t.getMarket() == null ? MarketContext.get() : t.getMarket();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 8: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionWriteTest' --tests 'com.vladoose.nir.service.execution.ExecutionInputTest'`
Expected: PASS (7 + 4).

- [ ] **Step 9: Мутации**
  - `ExecutionInput.amount`: проверку масштаба после `setScale(2)` (то есть округлять до проверки) → красный `amounts` («1.005» молча 1,01).
  - `today(t)`: `LocalDate.now(ZoneOffset.UTC)` вместо часов рынка → `todayIsMarketDate_notUtc` проверяет помощник, а сервис — `rejectedInputWritesNothing` только вечером по Уральску; поэтому мутация — в `ExecutionInput.today`: `LocalDate.now(clock)` → красный `todayIsMarketDate_notUtc`.
  - `updateField`: `ensureRow(t)` перед `switch` → красный `rejectedInputWritesNothing` (строка заведена).
  - `setMark`: не снимать заметку (`n = ExecutionInput.text(note, …)` без `date == null ?`) → красный `marks_setAndClear`.
  - `payment(...)`: без `.filter(...)` по сделке → красный `paymentOfOtherDeal_404`.

- [ ] **Step 10: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/execution/ExecutionInput.java \
  src/main/java/com/vladoose/nir/service/execution/DealExecutionService.java \
  src/main/java/com/vladoose/nir/dto/request/ExecutionField.java src/main/java/com/vladoose/nir/dto/request/ExecutionMark.java \
  src/test/java/com/vladoose/nir/service/execution/ExecutionInputTest.java \
  src/test/java/com/vladoose/nir/execution/DealExecutionWriteTest.java
git commit -m "$(cat <<'EOF'
feat(execution): запись — поля договора, сумма, предоплата, отметки поставки и документов, платежи клиента

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 6: Поставщики — добавить, сумма и валюта, «Не заказываем», заказ и отгрузка, удалить

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/SupplierField.java`, `SupplierMark.java`
- Modify: `src/main/java/com/vladoose/nir/service/execution/DealExecutionService.java`
- Test: `src/test/java/com/vladoose/nir/execution/DealExecutionSuppliersTest.java`

**Interfaces:**
- Consumes: `ensureSupplier`, `today`, `market`, `blank` (задача 5).
- Produces: `addSupplier(Long id, Long distributorId, BigDecimal amount, String currency) → Tender`, `updateSupplier(Long id, Long distributorId, SupplierField field, String value) → Tender`, `setSupplierMark(Long id, Long distributorId, SupplierMark mark, String on, String note) → Tender`, `deleteSupplier(Long id, Long distributorId) → Tender`; `SupplierField {AMOUNT, CURRENCY, EXCLUDED}`, `SupplierMark {ORDER, SHIP}`.

- [ ] **Step 1: Тест (красный)**

`src/test/java/com/vladoose/nir/execution/DealExecutionSuppliersTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.SupplierField;
import com.vladoose.nir.dto.request.SupplierMark;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Поставщики сделки (спека 2026-10-19-deal-execution §4.3, §6.2, §7). */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class DealExecutionSuppliersTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DealPaymentRepository payments;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.now(Market.KZ.zone());

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(Market m) {
        return distributors.save(Distributor.builder().name("ZZ Поставщик " + System.nanoTime()).market(m).build());
    }

    private Tender accepted(Distributor... ds) {
        Tender t = requests.createEmpty();
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "1000", 1);
        for (Distributor d : ds) line(o, d.getId(), "100", "1");
        offers.save(o);
        return t;
    }

    private DealSupplier row(Tender t, Distributor d) {
        em.flush();
        em.clear();
        return suppliers.findByTenderIdAndDistributorId(t.getId(), d.getId()).orElseThrow();
    }

    @Test
    void addManual_inOfferOrTwice400_otherMarket404() {
        Distributor fotek = dist(Market.KZ), rm = dist(Market.KZ), rf = dist(Market.RF);
        Tender t = accepted(fotek);
        service.addSupplier(t.getId(), rm.getId(), new BigDecimal("277025"), "rub");
        DealSupplier s = row(t, rm);
        assertThat(s.isManual()).isTrue();
        assertThat(s.getCurrency()).isEqualTo("RUB");
        assertThat(s.getAmount()).isEqualByComparingTo("277025");

        assertThatThrownBy(() -> service.addSupplier(t.getId(), rm.getId(), null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("«" + rm.getName() + "» уже есть в сделке");
        assertThatThrownBy(() -> service.addSupplier(t.getId(), fotek.getId(), null, null))
                .isInstanceOf(BadRequestException.class).hasMessage("«" + fotek.getName() + "» уже есть в сделке");
        assertThatThrownBy(() -> service.addSupplier(t.getId(), rf.getId(), null, null))
                .isInstanceOf(NotFoundException.class).hasMessage("Поставщик не найден");
    }

    @Test
    void currencyChangeWithPayments_400() {
        Distributor fotek = dist(Market.KZ);
        Tender t = accepted(fotek);
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.CURRENCY, "RUB");     // заводит строку, платежей нет
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.AMOUNT, "277 025");
        service.addPayment(t.getId(), new PaymentInput(fotek.getId(), new BigDecimal("100000"), DAY, null));
        assertThatThrownBy(() -> service.updateSupplier(t.getId(), fotek.getId(), SupplierField.CURRENCY, "KZT"))
                .isInstanceOf(BadRequestException.class).hasMessage("Сначала удалите платежи поставщику — они в прежней валюте");
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.CURRENCY, "RUB");     // та же валюта — можно
        assertThat(row(t, fotek).getCurrency()).isEqualTo("RUB");
    }

    @Test
    void excludeWithPayments400_excludedCannotBePaid() {
        Distributor a = dist(Market.KZ), b = dist(Market.KZ);
        Tender t = accepted(a, b);
        service.addPayment(t.getId(), new PaymentInput(a.getId(), BigDecimal.TEN, DAY, null));
        assertThatThrownBy(() -> service.updateSupplier(t.getId(), a.getId(), SupplierField.EXCLUDED, "true"))
                .isInstanceOf(BadRequestException.class).hasMessage("У поставщика есть платежи — сначала удалите их");

        service.updateSupplier(t.getId(), b.getId(), SupplierField.EXCLUDED, "true");
        assertThat(row(t, b).isExcluded()).isTrue();
        assertThatThrownBy(() -> service.addPayment(t.getId(), new PaymentInput(b.getId(), BigDecimal.TEN, DAY, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Поставщик отмечен «Не заказываем» — платёж ему не вносится");
    }

    @Test
    void fromStockOnlyWithoutSupplierMarks() {
        Distributor a = dist(Market.KZ);
        Tender t = accepted(a);
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.ORDER, DAY.toString(), "№ 7");
        assertThatThrownBy(() -> service.updateField(t.getId(), ExecutionField.FROM_STOCK, "true"))
                .isInstanceOf(BadRequestException.class).hasMessage("Сначала уберите отметки, платежи и файлы поставщиков");
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.ORDER, null, null);
        service.updateField(t.getId(), ExecutionField.FROM_STOCK, "true");   // отметок больше нет
    }

    @Test
    void marks_orderAndShip() {
        Distributor a = dist(Market.KZ);
        Tender t = accepted(a);
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.ORDER, DAY.toString(), " № 7 ");
        service.setSupplierMark(t.getId(), a.getId(), SupplierMark.SHIP, DAY.toString(), "трек 123");
        DealSupplier s = row(t, a);
        assertThat(s.getOrderedOn()).isEqualTo(DAY);
        assertThat(s.getOrderNote()).isEqualTo("№ 7");
        assertThat(s.getShippedOn()).isEqualTo(DAY);
        assertThat(s.getShipNote()).isEqualTo("трек 123");
        assertThatThrownBy(() -> service.setSupplierMark(t.getId(), a.getId(), SupplierMark.SHIP, DAY.plusDays(1).toString(), null))
                .isInstanceOf(BadRequestException.class).hasMessage("Дата отгрузки: дата ещё не наступила — отмечаются только факты");
    }

    @Test
    void deleteOnlyManual_cascadesPayments() {
        Distributor fotek = dist(Market.KZ), rm = dist(Market.KZ);
        Tender t = accepted(fotek);
        service.addSupplier(t.getId(), rm.getId(), new BigDecimal("500"), null);
        service.addPayment(t.getId(), new PaymentInput(rm.getId(), new BigDecimal("500"), DAY, null));
        service.updateSupplier(t.getId(), fotek.getId(), SupplierField.AMOUNT, "90");
        assertThatThrownBy(() -> service.deleteSupplier(t.getId(), fotek.getId()))
                .isInstanceOf(BadRequestException.class).hasMessage("Поставщик из КП — отметьте «Не заказываем»");
        service.deleteSupplier(t.getId(), rm.getId());
        em.flush();
        em.clear();
        assertThat(suppliers.findByTenderIdAndDistributorId(t.getId(), rm.getId())).isEmpty();
        assertThat(payments.findByTenderIdIn(List.of(t.getId()))).isEmpty();
        assertThatThrownBy(() -> service.deleteSupplier(t.getId(), rm.getId()))
                .isInstanceOf(NotFoundException.class).hasMessage("Поставщика нет в сделке");
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionSuppliersTest'`
Expected: FAIL — `cannot find symbol: class SupplierField` (и методов).

- [ ] **Step 3: Перечисления и методы сервиса**

`dto/request/SupplierField.java`:

```java
package com.vladoose.nir.dto.request;

/** Поле поставщика сделки (спека 2026-10-19-deal-execution §9): к оплате, валюта, «Не заказываем». */
public enum SupplierField { AMOUNT, CURRENCY, EXCLUDED }
```

`dto/request/SupplierMark.java`:

```java
package com.vladoose.nir.dto.request;

/** Отметка поставщика формой «дата + заметка»: заказ, отгрузка. */
public enum SupplierMark { ORDER, SHIP }
```

В `DealExecutionService` (импорты `com.vladoose.nir.dto.request.SupplierField`, `SupplierMark`):

```java
    /** «+ Поставщик» — из справочника своего рынка; к оплате необязательна (решение при планировании 5). */
    @Transactional
    public Tender addSupplier(Long id, Long distributorId, BigDecimal amount, String currency) {
        Tender t = privateRequests.findById(id);
        if (distributorId == null) throw new BadRequestException("Выберите поставщика");
        // findById = em.find: фильтр рынка его не режет — рынок поставщика сверяется явно (CLAUDE.md §14)
        Distributor d = distributors.findById(distributorId).filter(x -> x.getMarket() == market(t))
                .orElseThrow(() -> new NotFoundException("Поставщик не найден"));
        BigDecimal a = amount == null ? null : ExecutionInput.amount(amount, "Сумма к оплате");
        String cur = blank(currency) ? market(t).currencyCode() : ExecutionInput.currency(currency);
        if (suppliers.findByTenderIdAndDistributorId(t.getId(), d.getId()).isPresent() || loader.load(t).inOffer(d.getId())) {
            throw new BadRequestException("«" + d.getName() + "» уже есть в сделке");
        }
        suppliers.save(DealSupplier.builder().tenderId(t.getId()).distributorId(d.getId()).amount(a).currency(cur)
                .manual(true).build());
        return t;
    }

    /** Сумма к оплате (пусто — по КП), валюта (не при платежах в прежней), «Не заказываем» (не при платежах). */
    @Transactional
    public Tender updateSupplier(Long id, Long distributorId, SupplierField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле");
        Tender t = privateRequests.findById(id);
        if (distributorId == null) throw new BadRequestException("Не указан поставщик");
        String v = PrivateRequestService.blankToNull(value);
        DealSupplier row = suppliers.findByTenderIdAndDistributorId(t.getId(), distributorId).orElse(null);
        boolean paid = row != null && payments.existsByDealSupplierId(row.getId());
        Consumer<DealSupplier> apply = switch (field) {
            case AMOUNT -> {
                BigDecimal a = v == null ? null : ExecutionInput.amount(v, "Сумма к оплате");
                yield s -> s.setAmount(a);
            }
            case CURRENCY -> {
                String c = ExecutionInput.currency(v);
                if (paid && !c.equals(row.getCurrency())) {
                    throw new BadRequestException("Сначала удалите платежи поставщику — они в прежней валюте");
                }
                yield s -> s.setCurrency(c);
            }
            case EXCLUDED -> {
                boolean on = ExecutionInput.bool(v);
                if (on && paid) throw new BadRequestException("У поставщика есть платежи — сначала удалите их");
                yield s -> s.setExcluded(on);
            }
        };
        apply.accept(row != null ? row : ensureSupplier(t, distributorId));
        return t;
    }

    /** Заказ или отгрузка: дата и заметка; дата пуста — отметка снимается вместе с заметкой. */
    @Transactional
    public Tender setSupplierMark(Long id, Long distributorId, SupplierMark mark, String on, String note) {
        if (mark == null) throw new BadRequestException("Неизвестная отметка");
        Tender t = privateRequests.findById(id);
        LocalDate date = blank(on) ? null
                : ExecutionInput.date(on, mark == SupplierMark.ORDER ? "Дата заказа" : "Дата отгрузки", today(t));
        String n = date == null ? null : ExecutionInput.text(note, ExecutionInput.NOTE_MAX, "Заметка");
        DealSupplier s = ensureSupplier(t, distributorId);
        if (mark == SupplierMark.ORDER) {
            s.setOrderedOn(date);
            s.setOrderNote(n);
        } else {
            s.setShippedOn(date);
            s.setShipNote(n);
        }
        return t;
    }

    /** Только добавленный вручную; его платежи и файлы уходят каскадом. Поставщик из КП — «Не заказываем». */
    @Transactional
    public Tender deleteSupplier(Long id, Long distributorId) {
        Tender t = privateRequests.findById(id);
        DealSupplier s = distributorId == null ? null
                : suppliers.findByTenderIdAndDistributorId(t.getId(), distributorId).orElse(null);
        if (s == null) throw new NotFoundException("Поставщика нет в сделке");
        if (!s.isManual()) throw new BadRequestException("Поставщик из КП — отметьте «Не заказываем»");
        suppliers.delete(s);
        return t;
    }
```

- [ ] **Step 4: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionSuppliersTest' --tests 'com.vladoose.nir.execution.DealExecutionWriteTest'`
Expected: PASS (6 + 7).

- [ ] **Step 5: Мутации**
  - `addSupplier`: без `.filter(x -> x.getMarket() == market(t))` → красный `addManual_inOfferOrTwice400_otherMarket404`.
  - `updateSupplier`: без проверки `paid` у `CURRENCY` → красный `currencyChangeWithPayments_400`.
  - `deleteSupplier`: без `!s.isManual()` → красный `deleteOnlyManual_cascadesPayments`.
  - `addSupplier`: без `|| loader.load(t).inOffer(...)` → красный первый тест (поставщик из КП добавился вручную).

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/request/SupplierField.java src/main/java/com/vladoose/nir/dto/request/SupplierMark.java \
  src/main/java/com/vladoose/nir/service/execution/DealExecutionService.java \
  src/test/java/com/vladoose/nir/execution/DealExecutionSuppliersTest.java
git commit -m "$(cat <<'EOF'
feat(execution): поставщики сделки — добавить вручную, сумма и валюта, «Не заказываем», заказ и отгрузка

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 7: Файлы этапов — из переписки (в обе стороны) и с компьютера

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/ChatEpisodeFile.java`, `ExecutionFileCandidate.java`
- Modify: `src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java` (два запроса)
- Modify: `src/main/java/com/vladoose/nir/service/execution/DealExecutionService.java`
- Test: `src/test/java/com/vladoose/nir/execution/DealExecutionFilesTest.java`

**Interfaces:**
- Consumes: `ReadyOfferHints.episode(Tender)` / `Episode(chatId, from, to)` / `FILES_LIMIT` (есть); `SupplierReplyFiles.fileName`, `sha256` (есть); `ensureSupplier`, `payment`, `ensureRow` (задача 5).
- Produces:
  - `ChatAttachmentRepository.findEpisodeFiles(Long chatId, OffsetDateTime from, OffsetDateTime to, Pageable)` → `List<ChatEpisodeFile>`; `countEpisodeFile(Long id, Long chatId, OffsetDateTime from, OffsetDateTime to)` → `long`.
  - `ChatEpisodeFile(Long attachmentId, String fileName, String mimeType, Long sizeBytes, OffsetDateTime sentAt, String subject, LeadChannel channel, LeadDirection direction)`.
  - `ExecutionFileCandidate(Long attachmentId, String fileName, Long sizeBytes, OffsetDateTime sentAt, String channel, String subject, String direction)`.
  - `DealExecutionService.candidates(Long) → List<ExecutionFileCandidate>`, `attach(Long id, Upload upload, Long attachmentId, DealFileStage stage, Long paymentId, Long distributorId, String author) → Tender`, `file(Long id, Long fileId) → DealFile`, `deleteFile(Long id, Long fileId) → Tender`; запись `Upload(String fileName, String mimeType, byte[] bytes)`.

- [ ] **Step 1: Тест (красный)**

`src/test/java/com/vladoose/nir/execution/DealExecutionFilesTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.dto.response.ExecutionFileCandidate;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import com.vladoose.nir.service.execution.DealExecutionService.Upload;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Файлы этапов (спека 2026-10-19-deal-execution §4.4, §7): место, повтор, переписка в обе стороны, гарды. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class DealExecutionFilesTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealFileRepository files;
    @Autowired DealPaymentRepository payments;
    @Autowired DealExecutionRepository executions;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.now(Market.KZ.zone());
    static final OffsetDateTime LEAD_AT = OffsetDateTime.parse("2026-10-14T09:00:00Z");
    static final byte[] PDF = "%PDF-1.4 договор".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist() {
        return distributors.save(Distributor.builder().name("ZZ Файлы " + System.nanoTime()).build());
    }

    private Tender accepted(Distributor... ds) {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Аппарат", null, 1, null, null, null)), LinesMode.APPEND);
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "1000", 1);
        for (Distributor d : ds) line(o, d.getId(), "100", "1");
        offers.save(o);
        return t;
    }

    /** Переписка обращения заявки: почтовый чат с файлом клиента (IN) и нашим (OUT). */
    private Chat chatOf(Tender t) {
        Chat c = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId("zz" + System.nanoTime() + "@clinic.kz").title("Клиника").build());
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос").contactName("Клиент").status(LeadStatus.CONVERTED).chat(c).privateRequest(t)
                .receivedAt(LEAD_AT).build());
        return c;
    }

    private ChatAttachment chatFile(Chat c, LeadDirection dir, String at, String name) {
        ChatMessage m = messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(dir)
                .type(ChatMessageType.EMAIL).subject("Договор").body("Во вложении").sentAt(OffsetDateTime.parse(at)).build());
        return attachments.save(ChatAttachment.builder().message(m).fileName(name).mimeType("application/pdf")
                .sizeBytes((long) PDF.length).content(PDF).build());
    }

    private List<DealFileMeta> meta(Tender t) {
        em.flush();
        return files.findMetaByTenderId(t.getId());
    }

    @Test
    void sameFileTwiceSamePlace_oneRow_otherPlace_newRow() {
        Tender t = accepted();
        Upload up = new Upload("Договор 42.pdf", "application/pdf", PDF);
        service.attach(t.getId(), up, null, DealFileStage.CONTRACT, null, null, "vlad");
        service.attach(t.getId(), up, null, DealFileStage.CONTRACT, null, null, "vlad");
        assertThat(meta(t)).hasSize(1);
        service.attach(t.getId(), up, null, DealFileStage.DOCS, null, null, "vlad");
        assertThat(meta(t)).extracting(DealFileMeta::stage).containsExactly(DealFileStage.CONTRACT, DealFileStage.DOCS);
        assertThat(meta(t).get(0).uploadedBy()).isEqualTo("vlad");
        assertThat(executions.findById(t.getId())).isPresent();   // файл заводит строку исполнения (решение 7)
    }

    @Test
    void candidatesBothDirections_attachFromChatKeepsLink() {
        Tender t = accepted();
        Chat c = chatOf(t);
        chatFile(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", "Счёт.pdf");
        ChatAttachment signed = chatFile(c, LeadDirection.IN, "2026-10-16T07:00:00Z", "Договор подписан.pdf");
        chatFile(c, LeadDirection.IN, "2026-10-13T07:00:00Z", "до обращения.pdf");   // раньше эпизода

        List<ExecutionFileCandidate> list = service.candidates(t.getId());
        assertThat(list).extracting(ExecutionFileCandidate::fileName).containsExactly("Договор подписан.pdf", "Счёт.pdf");
        assertThat(list).extracting(ExecutionFileCandidate::direction).containsExactly("IN", "OUT");

        service.attach(t.getId(), null, signed.getId(), DealFileStage.CONTRACT, null, null, "vlad");
        em.flush();
        DealFile f = files.findById(meta(t).get(0).id()).orElseThrow();
        assertThat(f.getChatAttachmentId()).isEqualTo(signed.getId());
        assertThat(f.getFileName()).isEqualTo("Договор подписан.pdf");
        assertThat(f.getContent()).isEqualTo(PDF);
    }

    @Test
    void chatFileOfOtherDeal_404() {
        Tender a = accepted(), b = accepted();
        ChatAttachment foreign = chatFile(chatOf(a), LeadDirection.IN, "2026-10-16T07:00:00Z", "чужой.pdf");
        chatOf(b);
        assertThatThrownBy(() -> service.attach(b.getId(), null, foreign.getId(), DealFileStage.CONTRACT, null, null, "vlad"))
                .isInstanceOf(NotFoundException.class).hasMessage("Файл переписки не найден");
    }

    @Test
    void placeRules_paymentAndSupplier() {
        Distributor fotek = dist();
        Tender t = accepted(fotek);
        Upload up = new Upload("платёжка.pdf", "application/pdf", PDF);
        service.addPayment(t.getId(), new PaymentInput(null, BigDecimal.TEN, DAY, null));
        service.addPayment(t.getId(), new PaymentInput(fotek.getId(), BigDecimal.ONE, DAY, null));
        em.flush();
        List<DealPayment> ps = payments.findByTenderIdIn(List.of(t.getId()));
        Long clientPay = ps.stream().filter(p -> p.getDealSupplierId() == null).findFirst().orElseThrow().getId();
        Long supplierPay = ps.stream().filter(p -> p.getDealSupplierId() != null).findFirst().orElseThrow().getId();

        assertThatThrownBy(() -> service.attach(t.getId(), up, null, DealFileStage.CLIENT_PAYMENT, supplierPay, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Это платёж поставщику");
        assertThatThrownBy(() -> service.attach(t.getId(), up, null, DealFileStage.CONTRACT, clientPay, null, "vlad"))
                .isInstanceOf(BadRequestException.class);
        service.attach(t.getId(), up, null, DealFileStage.CLIENT_PAYMENT, clientPay, null, "vlad");
        service.attach(t.getId(), up, null, DealFileStage.SUPPLIER, supplierPay, fotek.getId(), "vlad");
        assertThat(meta(t)).hasSize(2);

        service.deletePayment(t.getId(), clientPay);   // файлы платежа — каскадом
        em.flush();
        em.clear();
        assertThat(files.findMetaByTenderId(t.getId())).extracting(DealFileMeta::stage).containsExactly(DealFileStage.SUPPLIER);
    }

    @Test
    void fileOfOtherDeal_404_delete() {
        Tender a = accepted(), b = accepted();
        service.attach(a.getId(), new Upload("a.pdf", null, PDF), null, DealFileStage.DELIVERY, null, null, "vlad");
        Long fileId = meta(a).get(0).id();
        assertThatThrownBy(() -> service.file(b.getId(), fileId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteFile(b.getId(), fileId)).isInstanceOf(NotFoundException.class);
        assertThat(service.file(a.getId(), fileId).getContent()).isEqualTo(PDF);
        service.deleteFile(a.getId(), fileId);
        assertThat(meta(a)).isEmpty();
    }

    @Test
    void sourceRules() {
        Tender t = accepted();
        assertThatThrownBy(() -> service.attach(t.getId(), null, null, DealFileStage.CONTRACT, null, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Выберите файл: с компьютера или из переписки");
        assertThatThrownBy(() -> service.attach(t.getId(), new Upload("x.pdf", null, new byte[0]), null, DealFileStage.CONTRACT, null, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Файл пустой");
        assertThatThrownBy(() -> service.attach(t.getId(), new Upload("x.pdf", null, PDF), null, null, null, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Не указано, к чему файл");
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionFilesTest'`
Expected: FAIL — `cannot find symbol: class Upload` / `ExecutionFileCandidate`.

- [ ] **Step 3: Проекции и запросы переписки**

`dto/response/ChatEpisodeFile.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadDirection;

import java.time.OffsetDateTime;

/** Файл переписки сделки в любую сторону — без байтов (окно файла этапа, спека 2026-10-19-deal-execution §4.4). */
public record ChatEpisodeFile(Long attachmentId, String fileName, String mimeType, Long sizeBytes, OffsetDateTime sentAt,
                              String subject, LeadChannel channel, LeadDirection direction) {}
```

`dto/response/ExecutionFileCandidate.java`:

```java
package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;

/** Файл переписки для окна «Файл этапа»: direction — IN (клиент) / OUT (мы). */
public record ExecutionFileCandidate(Long attachmentId, String fileName, Long sizeBytes, OffsetDateTime sentAt,
                                     String channel, String subject, String direction) {}
```

В `ChatAttachmentRepository` (импорт `com.vladoose.nir.dto.response.ChatEpisodeFile`):

```java
    /** Файлы сообщений чата в обе стороны в окне [from, to) с сохранёнными байтами — новые сверху, без байтов. */
    @Query("""
           select new com.vladoose.nir.dto.response.ChatEpisodeFile(a.id, a.fileName, a.mimeType, a.sizeBytes, m.sentAt,
               m.subject, c.channel, m.direction)
           from ChatAttachment a join a.message m join m.chat c
           where c.id = :chatId and m.sentAt >= :from and m.sentAt < :to and a.content is not null and m.deleted = false
           order by m.sentAt desc, a.id desc""")
    List<ChatEpisodeFile> findEpisodeFiles(@Param("chatId") Long chatId, @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to, Pageable page);

    /** Тот же отбор по id файла — проверка «файл переписки этой сделки» (решение при планировании 8). */
    @Query("""
           select count(a) from ChatAttachment a join a.message m
           where a.id = :id and m.chat.id = :chatId and m.sentAt >= :from and m.sentAt < :to
             and a.content is not null and m.deleted = false""")
    long countEpisodeFile(@Param("id") Long id, @Param("chatId") Long chatId, @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to);
```

- [ ] **Step 4: Файлы в сервисе**

В `DealExecutionService` (импорты `com.vladoose.nir.dto.response.ExecutionFileCandidate`, `com.vladoose.nir.service.supplierreply.SupplierReplyFiles`, `org.springframework.data.domain.PageRequest`, `java.util.List`, `java.util.Objects`):

```java
    public record Upload(String fileName, String mimeType, byte[] bytes) {}

    private record Source(String fileName, String mimeType, byte[] bytes, Long attachmentId) {}

    /** Файлы переписки сделки (эпизод обращения, как лента) в обе стороны, новые сверху, до 50. */
    @Transactional(readOnly = true)
    public List<ExecutionFileCandidate> candidates(Long id) {
        Tender t = privateRequests.findById(id);
        return hints.episode(t)
                .map(e -> attachments.findEpisodeFiles(e.chatId(), e.from(), e.to(), PageRequest.of(0, ReadyOfferHints.FILES_LIMIT)))
                .orElse(List.of()).stream()
                .map(f -> new ExecutionFileCandidate(f.attachmentId(), f.fileName(), f.sizeBytes(), f.sentAt(),
                        f.channel() == null ? null : f.channel().name(), f.subject(),
                        f.direction() == null ? null : f.direction().name()))
                .toList();
    }

    /**
     * Файл к месту: договор / поставка / документы — без платежа и поставщика; платёж клиента — его платёж; поставщик —
     * поставщик сделки (из КП заводится строкой), с платежом — его платёж. Тот же файл (SHA-256) к тому же месту второй
     * раз не прикладывается. Любой файл заводит строку исполнения — раздел не пропадёт (решение при планировании 7).
     */
    @Transactional
    public Tender attach(Long id, Upload upload, Long attachmentId, DealFileStage stage, Long paymentId, Long distributorId,
                         String author) {
        Tender t = privateRequests.findById(id);
        if (stage == null) throw new BadRequestException("Не указано, к чему файл");
        Source src = source(t, upload, attachmentId);
        Long supplierRow = null;
        Long pid = null;
        switch (stage) {
            case CLIENT_PAYMENT -> {
                if (distributorId != null) throw new BadRequestException("У платежа клиента нет поставщика");
                DealPayment p = payment(t, paymentId);
                if (p.getDealSupplierId() != null) throw new BadRequestException("Это платёж поставщику");
                pid = p.getId();
            }
            case SUPPLIER -> {
                DealSupplier s = ensureSupplier(t, distributorId);
                if (paymentId != null) {
                    DealPayment p = payment(t, paymentId);
                    if (!s.getId().equals(p.getDealSupplierId())) throw new BadRequestException("Это платёж другого поставщика");
                    pid = p.getId();
                }
                supplierRow = s.getId();
            }
            default -> {
                if (paymentId != null || distributorId != null) {
                    throw new BadRequestException("У договора, поставки и документов нет платежа и поставщика");
                }
            }
        }
        String sha = SupplierReplyFiles.sha256(src.bytes());
        Long sid = supplierRow, payment = pid;
        boolean same = files.findMetaByTenderId(t.getId()).stream().anyMatch(m -> m.stage() == stage
                && Objects.equals(m.paymentId(), payment) && Objects.equals(m.dealSupplierId(), sid) && m.sha256().equals(sha));
        if (!same) {
            files.save(DealFile.builder().tenderId(t.getId()).stage(stage).paymentId(payment).dealSupplierId(sid)
                    .fileName(src.fileName()).mimeType(src.mimeType() == null ? null : cut(src.mimeType(), 100))
                    .sizeBytes(src.bytes().length).sha256(sha).content(src.bytes()).chatAttachmentId(src.attachmentId())
                    .uploadedBy(author).build());
        }
        ensureRow(t);
        return t;
    }

    /** Файл этой сделки, иначе 404 (гард рынка — через заявку). */
    @Transactional(readOnly = true)
    public DealFile file(Long id, Long fileId) {
        Tender t = privateRequests.findById(id);
        return files.findByIdAndTenderId(fileId, t.getId()).orElseThrow(() -> new NotFoundException("Файл не найден"));
    }

    @Transactional
    public Tender deleteFile(Long id, Long fileId) {
        Tender t = privateRequests.findById(id);
        files.delete(files.findByIdAndTenderId(fileId, t.getId()).orElseThrow(() -> new NotFoundException("Файл не найден")));
        return t;
    }

    private Source source(Tender t, Upload upload, Long attachmentId) {
        if ((upload == null) == (attachmentId == null)) throw new BadRequestException("Выберите файл: с компьютера или из переписки");
        if (upload != null) {
            if (upload.bytes() == null || upload.bytes().length == 0) throw new BadRequestException("Файл пустой");
            return new Source(SupplierReplyFiles.fileName(upload.fileName()), upload.mimeType(), upload.bytes(), null);
        }
        ReadyOfferHints.Episode e = hints.episode(t).orElseThrow(() -> new NotFoundException("Файл переписки не найден"));
        if (attachments.countEpisodeFile(attachmentId, e.chatId(), e.from(), e.to()) == 0) {
            throw new NotFoundException("Файл переписки не найден");
        }
        ChatAttachment a = attachments.findById(attachmentId).orElseThrow(() -> new NotFoundException("Файл переписки не найден"));
        return new Source(SupplierReplyFiles.fileName(a.getFileName()), a.getMimeType(), a.getContent(), a.getId());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
```

- [ ] **Step 5: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionFilesTest'`
Expected: PASS (6 тестов).

- [ ] **Step 6: Мутации**
  - Повтор не проверяется (`same = false`) → красный `sameFileTwiceSamePlace_oneRow_otherPlace_newRow`.
  - Повтор без учёта места (только `sha256`) → тот же тест красный (вторая строка DOCS не появилась).
  - `findEpisodeFiles` с `and m.direction = OUT` → красный `candidatesBothDirections_attachFromChatKeepsLink`.
  - `source(...)` без `countEpisodeFile` → красный `chatFileOfOtherDeal_404`.

- [ ] **Step 7: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/response/ChatEpisodeFile.java \
  src/main/java/com/vladoose/nir/dto/response/ExecutionFileCandidate.java \
  src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java \
  src/main/java/com/vladoose/nir/service/execution/DealExecutionService.java \
  src/test/java/com/vladoose/nir/execution/DealExecutionFilesTest.java
git commit -m "$(cat <<'EOF'
feat(execution): файлы этапов — из переписки сделки в обе стороны и с компьютера, без повторов

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 8: API и вид раздела в ответе заявки

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/DealExecutionView.java`
- Create: `src/main/java/com/vladoose/nir/service/execution/DealExecutionViews.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/ExecutionFieldUpdate.java`, `ExecutionMarkRequest.java`, `DealPaymentRequest.java`, `DealSupplierAddRequest.java`, `SupplierFieldUpdate.java`
- Create: `src/main/java/com/vladoose/nir/controller/DealExecutionController.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestViewResponse.java`, `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java`
- Test: `src/test/java/com/vladoose/nir/execution/DealExecutionApiTest.java`, `DealExecutionOverlappingSavesTest.java`

**Interfaces:**
- Consumes: `DealExecutionService` (задачи 5–7), `DealExecutionData` (задача 3), `PrivateRequestStageCalculator.Summary.exec()` (задача 4), `ReadyOfferController.contentType(ReplyFileKind, String)` (пакетный, тот же пакет `controller`).
- Produces:
  - JSON вида заявки: `execution: DealExecutionView | null` (§9) — поля ниже; фронт задачи 10 читает их один в один.
  - Пути (все под `/api/private-requests/{id}/execution`, ответ на запись — `PrivateRequestViewResponse`): `PATCH ""` `{field, value}`; `PUT /marks/{DELIVERY|DOCS}` `{on, note}`; `POST /payments` `{distributorId?, amount, paidOn, note}`; `PUT /payments/{paymentId}`; `DELETE /payments/{paymentId}`; `POST /suppliers` `{distributorId, amount, currency}`; `PATCH /suppliers/{distributorId}` `{field, value}`; `PUT /suppliers/{distributorId}/marks/{ORDER|SHIP}` `{on, note}`; `DELETE /suppliers/{distributorId}`; `GET /file-candidates`; `POST /files` (multipart `file` | `attachmentId`, `stage`, `paymentId?`, `distributorId?`); `GET /files/{fileId}`; `DELETE /files/{fileId}`.

- [ ] **Step 1: Тест API (красный)**

`src/test/java/com/vladoose/nir/execution/DealExecutionApiTest.java`:

```java
package com.vladoose.nir.execution;

import com.jayway.jsonpath.JsonPath;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API исполнения (спека 2026-10-19-deal-execution §9): права, рынок и вид сделки, тексты 400, вид раздела, файлы. */
@SpringBootTest
@Transactional
class DealExecutionApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired TenderRepository tenders;
    @Autowired EntityManager em;
    MockMvc mvc;

    static final String TODAY = LocalDate.now(Market.KZ.zone()).toString();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static RequestPostProcessor admin() { return user("admin").roles("ADMIN"); }
    private static RequestPostProcessor operator() { return user("op").roles("OPERATOR"); }
    private static String base(Tender t) { return "/api/private-requests/" + t.getId() + "/execution"; }

    /** Запрос MockMvc чистит MarketContext своего потока (§14) — рынок ставится заново перед вызовами сервисов. */
    private Tender acceptedDeal() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        Distributor d = distributors.save(Distributor.builder().name("ZZ API " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "3070455", 481), d.getId(), "100", "1"));
        em.flush();
        MarketContext.clear();
        return t;
    }

    private Distributor distributor() {
        MarketContext.set(Market.KZ);
        Distributor d = distributors.save(Distributor.builder().name("ZZ API ручной " + System.nanoTime()).build());
        em.flush();
        MarketContext.clear();
        return d;
    }

    @Test
    void viewCarriesExecution_patchReturnsViewWithProgress() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(get("/api/private-requests/" + t.getId()).header("X-Market", "KZ").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.visible").value(true))
                .andExpect(jsonPath("$.execution.offerLabel").value("КП № 481"))
                .andExpect(jsonPath("$.execution.amount").value(3070455.0))
                .andExpect(jsonPath("$.execution.amountFromOffer").value(true))
                .andExpect(jsonPath("$.execution.suppliers[0].fromOffer").value(true))
                .andExpect(jsonPath("$.execution.suppliers[0].amount").value(100.0));
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"CONTRACT_NUMBER\",\"value\":\"42/2026\"}")
                        .header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.contractNumber").value("42/2026"))
                .andExpect(jsonPath("$.progress.next.action").value("EXEC_PAY_CLIENT"))
                .andExpect(jsonPath("$.stage").value("EXEC_PREPAY"));
    }

    @Test
    void operatorReadsButCannotWrite() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"AMOUNT\",\"value\":\"1\"}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(put(base(t) + "/marks/DELIVERY").contentType(APPLICATION_JSON).content("{\"on\":\"" + TODAY + "\"}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(post(base(t) + "/payments").contentType(APPLICATION_JSON).content("{\"amount\":1,\"paidOn\":\"" + TODAY + "\"}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(post(base(t) + "/suppliers").contentType(APPLICATION_JSON).content("{\"distributorId\":1}")
                .header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(get(base(t) + "/file-candidates").header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
        mvc.perform(multipart(base(t) + "/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}))
                .param("stage", "CONTRACT").header("X-Market", "KZ").with(operator())).andExpect(status().isForbidden());
    }

    @Test
    void otherMarketAndPublicTender404() throws Exception {
        Tender t = acceptedDeal();
        String body = "{\"field\":\"CONTRACT_NUMBER\",\"value\":\"1\"}";
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content(body).header("X-Market", "RF").with(admin()))
                .andExpect(status().isNotFound());
        MarketContext.set(Market.KZ);
        Tender pub = tenders.save(Tender.builder().tenderNumber("ZZ-PUB-" + System.nanoTime()).status("ACTIVE")
                .source(Source.PUBLIC_TENDER).build());
        em.flush();
        MarketContext.clear();
        mvc.perform(patch(base(pub)).contentType(APPLICATION_JSON).content(body).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void russianTextsFor400() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"AMOUNT\",\"value\":\"-5\"}")
                        .header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Сумма сделки: сумма должна быть больше нуля"));
        mvc.perform(put(base(t) + "/marks/XYZ").contentType(APPLICATION_JSON).content("{}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Неизвестная отметка"));
        String tomorrow = LocalDate.now(Market.KZ.zone()).plusDays(1).toString();
        mvc.perform(post(base(t) + "/payments").contentType(APPLICATION_JSON).content("{\"amount\":100,\"paidOn\":\"" + tomorrow + "\"}")
                        .header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Дата платежа: дата ещё не наступила — отмечаются только факты"));
    }

    @Test
    void paymentsSuppliersMarks() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(post(base(t) + "/payments").contentType(APPLICATION_JSON)
                        .content("{\"amount\":1000,\"paidOn\":\"" + TODAY + "\",\"note\":\"предоплата\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.clientPaid").value(1000.0))
                .andExpect(jsonPath("$.execution.clientPayments[0].note").value("предоплата"));
        Distributor rm = distributor();
        mvc.perform(post(base(t) + "/suppliers").contentType(APPLICATION_JSON)
                        .content("{\"distributorId\":" + rm.getId() + ",\"amount\":277025,\"currency\":\"RUB\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.suppliers[1].manual").value(true))
                .andExpect(jsonPath("$.execution.suppliers[1].currency").value("RUB"));
        mvc.perform(put(base(t) + "/suppliers/" + rm.getId() + "/marks/ORDER").contentType(APPLICATION_JSON)
                        .content("{\"on\":\"" + TODAY + "\",\"note\":\"№ 7\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.suppliers[1].orderNote").value("№ 7"));
        mvc.perform(put(base(t) + "/marks/DELIVERY").contentType(APPLICATION_JSON)
                        .content("{\"on\":\"" + TODAY + "\",\"note\":\"обучение\"}").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.deliveryNote").value("обучение"));
        mvc.perform(delete(base(t) + "/suppliers/" + rm.getId()).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.suppliers.length()").value(1));
    }

    @Test
    void fileUploadDownloadDelete() throws Exception {
        Tender t = acceptedDeal();
        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        String body = mvc.perform(multipart(base(t) + "/files")
                        .file(new MockMultipartFile("file", "Договор 42.pdf", "application/pdf", pdf))
                        .param("stage", "CONTRACT").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.files[0].name").value("Договор 42.pdf"))
                .andExpect(jsonPath("$.execution.files[0].stage").value("CONTRACT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fileId = JsonPath.<Integer>read(body, "$.execution.files[0].id").longValue();
        mvc.perform(get(base(t) + "/files/" + fileId).header("X-Market", "KZ").with(operator()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(content().bytes(pdf));
        mvc.perform(multipart(base(t) + "/files").file(new MockMultipartFile("file", "x.pdf", "application/pdf", pdf))
                        .param("stage", "NOWHERE").header("X-Market", "KZ").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Неизвестное место файла"));
        mvc.perform(delete(base(t) + "/files/" + fileId).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.files.length()").value(0));
    }

    @Test
    void revertedOfferKeepsSection() throws Exception {
        Tender t = acceptedDeal();
        mvc.perform(patch(base(t)).contentType(APPLICATION_JSON).content("{\"field\":\"CONTRACT_NUMBER\",\"value\":\"42\"}")
                .header("X-Market", "KZ").with(admin())).andExpect(status().isOk());
        MarketContext.set(Market.KZ);
        ClientOffer o = offers.findByTenderIdOrderByIdDesc(t.getId()).get(0);
        o.setStatus(ClientOfferStatus.SENT);
        em.flush();
        MarketContext.clear();
        mvc.perform(get("/api/private-requests/" + t.getId()).header("X-Market", "KZ").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("KP_SENT"))
                .andExpect(jsonPath("$.execution.contractNumber").value("42"))
                .andExpect(jsonPath("$.execution.offerLabel").value(nullValue()));
    }
}
```

- [ ] **Step 2: Тест наложения правок (красный — без `@DynamicUpdate` он и был бы красным; сейчас красный по компиляции)**

`src/test/java/com/vladoose/nir/execution/DealExecutionOverlappingSavesTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.SupplierField;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Две вкладки правят разные поля исполнения — наложившиеся сохранения не затирают друг друга (@DynamicUpdate у
 * DealExecution и DealSupplier). Без общей транзакции теста: перекрытие — вложенной REQUIRES_NEW, уборка — в @AfterEach.
 */
@SpringBootTest
class DealExecutionOverlappingSavesTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DealExecutionRepository executions;
    @Autowired DealSupplierRepository suppliers;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired TenderRepository tenders;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private TransactionTemplate txNew;
    private Long id;
    private Long distributorId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tx = new TransactionTemplate(txManager);
        txNew = new TransactionTemplate(txManager);
        txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        id = tx.execute(s -> {
            Tender t = requests.createEmpty();
            Distributor d = distributors.save(Distributor.builder().name("ZZ Наложение " + System.nanoTime()).build());
            distributorId = d.getId();
            offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "1000", 1), d.getId(), "100", "1"));
            service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "CONTRACT");
            service.updateSupplier(t.getId(), d.getId(), SupplierField.AMOUNT, "90");
            return t.getId();
        });
    }

    @AfterEach
    void tearDown() {
        tx.execute(s -> {
            offers.deleteAll(offers.findByTenderIdOrderByIdDesc(id));
            tenders.deleteById(id);                 // deal_* — каскадом
            distributors.deleteById(distributorId);
            return null;
        });
        MarketContext.clear();
    }

    @Test
    void overlappingExecutionFieldSavesKeepBothFields() {
        tx.execute(s -> {
            DealExecution first = executions.findById(id).orElseThrow();      // первый PATCH загрузил строку
            first.setContractNumber("42/2026");
            txNew.execute(s2 -> service.updateField(id, ExecutionField.PREPAY_PCT, "50"));   // второй — раньше
            return null;                                                       // первый коммитится последним
        });
        DealExecution back = tx.execute(s -> executions.findById(id).orElseThrow());
        assertThat(back.getContractNumber()).isEqualTo("42/2026");
        assertThat(back.getPrepayPct()).isEqualByComparingTo("50");
    }

    @Test
    void overlappingSupplierSavesKeepBothFields() {
        tx.execute(s -> {
            DealSupplier first = suppliers.findByTenderIdAndDistributorId(id, distributorId).orElseThrow();
            first.setOrderedOn(LocalDate.now(Market.KZ.zone()));
            first.setOrderNote("№ 7");
            txNew.execute(s2 -> service.updateSupplier(id, distributorId, SupplierField.AMOUNT, "95"));
            return null;
        });
        DealSupplier back = tx.execute(s -> suppliers.findByTenderIdAndDistributorId(id, distributorId).orElseThrow());
        assertThat(back.getOrderNote()).isEqualTo("№ 7");
        assertThat(back.getAmount()).isEqualByComparingTo("95");
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.DealExecutionApiTest' --tests 'com.vladoose.nir.execution.DealExecutionOverlappingSavesTest'`
Expected: FAIL — API: 404 на `/execution` (контроллера нет), `$.execution` отсутствует; наложение — PASS уже сейчас (сущности задачи 1 с `@DynamicUpdate`) — проверить мутацией на шаге 8.

- [ ] **Step 4: Вид раздела**

`dto/response/DealExecutionView.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Раздел «Исполнение сделки» в виде заявки (спека 2026-10-19-deal-execution §9); amount — своя или по КП, null — неизвестна. */
public record DealExecutionView(boolean visible, String currency, BigDecimal amount, boolean amountFromOffer,
                                BigDecimal offerAmount, Long offerId, String offerLabel,
                                String contractKind, String contractNumber, LocalDate contractDate, String contractNote,
                                BigDecimal prepayPct, BigDecimal prepayAmount, BigDecimal clientPaid,
                                List<Payment> clientPayments, boolean fromStock, List<Supplier> suppliers,
                                Unassigned unassigned, LocalDate deliveredOn, String deliveryNote,
                                LocalDate docsOn, String docsNote, List<FileRef> files) {

    public record Payment(Long id, BigDecimal amount, LocalDate paidOn, String note, String createdBy) {}

    /** id — строка deal_supplier (пусто — поставщик из КП ещё без отметок). */
    public record Supplier(Long id, Long distributorId, String name, boolean fromOffer, boolean manual, boolean excluded,
                           String currency, BigDecimal amount, boolean amountFromOffer, BigDecimal offerAmount,
                           LocalDate orderedOn, String orderNote, BigDecimal paid, List<Payment> payments,
                           LocalDate shippedOn, String shipNote) {}

    /** Строки КП без поставщика: сколько и сумма их закупок. */
    public record Unassigned(int count, BigDecimal purchase) {}

    /** distributorId — у файлов поставщика (из deal_supplier_id). */
    public record FileRef(Long id, String stage, Long paymentId, Long distributorId, String name, long size,
                          String uploadedBy, OffsetDateTime uploadedAt) {}
}
```

`service/execution/DealExecutionViews.java`:

```java
package com.vladoose.nir.service.execution;

import com.vladoose.nir.dto.response.DealExecutionView;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.entity.DealExecution;
import com.vladoose.nir.entity.DealPayment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vladoose.nir.service.execution.DealExecutionData.positive;

/** Вид раздела «Исполнение сделки» из загрузки и метаданных файлов (спека 2026-10-19-deal-execution §9). */
public final class DealExecutionViews {

    private DealExecutionViews() {}

    public static DealExecutionView toView(DealExecutionData d, List<DealFileMeta> files) {
        ExecutionSnapshot s = d.snapshot();
        DealExecution e = d.row();
        DealExecutionData.MainOffer m = d.mainOffer();
        Map<Long, Long> distributorByRow = new HashMap<>();
        for (DealExecutionData.SupplierLine l : d.supplierLines()) {
            if (l.rowId() != null) distributorByRow.put(l.rowId(), l.distributorId());
        }
        boolean own = e != null && e.getAmount() != null;
        return new DealExecutionView(true, d.currency(), s.amount(), !own && s.amount() != null,
                m == null ? null : positive(m.total()), m == null ? null : m.id(), m == null ? null : m.label(),
                e == null || e.getContractKind() == null ? null : e.getContractKind().name(),
                e == null ? null : e.getContractNumber(), e == null ? null : e.getContractDate(),
                e == null ? null : e.getContractNote(),
                s.prepayPct(), s.prepayAmount(), s.clientPaid(),
                d.clientPayments().stream().map(DealExecutionViews::payment).toList(), s.fromStock(),
                d.supplierLines().stream().map(DealExecutionViews::supplier).toList(),
                d.unassignedCount() == 0 ? null : new DealExecutionView.Unassigned(d.unassignedCount(), d.unassignedPurchase()),
                e == null ? null : e.getDeliveredOn(), e == null ? null : e.getDeliveryNote(),
                e == null ? null : e.getDocsOn(), e == null ? null : e.getDocsNote(),
                files.stream().map(f -> new DealExecutionView.FileRef(f.id(), f.stage().name(), f.paymentId(),
                        f.dealSupplierId() == null ? null : distributorByRow.get(f.dealSupplierId()), f.name(), f.size(),
                        f.uploadedBy(), f.uploadedAt())).toList());
    }

    private static DealExecutionView.Payment payment(DealPayment p) {
        return new DealExecutionView.Payment(p.getId(), p.getAmount(), p.getPaidOn(), p.getNote(), p.getCreatedBy());
    }

    private static DealExecutionView.Supplier supplier(DealExecutionData.SupplierLine l) {
        return new DealExecutionView.Supplier(l.rowId(), l.distributorId(), l.name(), l.fromOffer(), l.manual(), l.excluded(),
                l.currency(), l.amount(), l.amountFromOffer(), l.offerAmount(), l.orderedOn(), l.orderNote(), l.paid(),
                l.payments().stream().map(DealExecutionViews::payment).toList(), l.shippedOn(), l.shipNote());
    }
}
```

В `PrivateRequestViewResponse` — поле после `progress`:

```java
    /** Исполнение сделки — раздел после «КП клиенту»; пусто, пока нет принятого КП и данных (спека 2026-10-19-deal-execution §4.1). */
    private DealExecutionView execution;
```

В `PrivateRequestViewBuilder`: поле `private final DealFileRepository dealFiles;` (последний параметр конструктора), в `build(...)` после `r.setProgress(...)`:

```java
        DealExecutionData exec = stage.exec();
        if (exec != null && exec.visible()) {
            r.setExecution(DealExecutionViews.toView(exec, dealFiles.findMetaByTenderId(t.getId())));
        }
```

(импорты `com.vladoose.nir.repository.DealFileRepository`, `com.vladoose.nir.service.execution.DealExecutionData`, `DealExecutionViews`.)

- [ ] **Step 5: Запросы**

`dto/request/ExecutionFieldUpdate.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Одно поле исполнения; value пусто — сброс. Значение проверяет сервис (русский текст отказа). */
@Data
public class ExecutionFieldUpdate {
    @NotNull(message = "Не указано поле")
    private ExecutionField field;
    @Size(max = 10000, message = "Слишком длинное значение")
    private String value;
}
```

`dto/request/ExecutionMarkRequest.java`:

```java
package com.vladoose.nir.dto.request;

import lombok.Data;

/** Отметка «дата + заметка»: on — «ГГГГ-ММ-ДД», пусто — снять отметку. */
@Data
public class ExecutionMarkRequest {
    private String on;
    private String note;
}
```

`dto/request/DealPaymentRequest.java`:

```java
package com.vladoose.nir.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Платёж: distributorId пусто — клиента. Проверки — в сервисе (решение при планировании 9). */
@Data
public class DealPaymentRequest {
    private Long distributorId;
    private BigDecimal amount;
    private LocalDate paidOn;
    private String note;
}
```

`dto/request/DealSupplierAddRequest.java`:

```java
package com.vladoose.nir.dto.request;

import lombok.Data;

import java.math.BigDecimal;

/** «+ Поставщик»: из справочника рынка; amount необязательна, currency пусто — валюта рынка. */
@Data
public class DealSupplierAddRequest {
    private Long distributorId;
    private BigDecimal amount;
    private String currency;
}
```

`dto/request/SupplierFieldUpdate.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Одно поле поставщика сделки; value пусто — сброс суммы (по КП). */
@Data
public class SupplierFieldUpdate {
    @NotNull(message = "Не указано поле")
    private SupplierField field;
    @Size(max = 100, message = "Слишком длинное значение")
    private String value;
}
```

- [ ] **Step 6: Контроллер**

`controller/DealExecutionController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.ExecutionFileCandidate;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.DealFile;
import com.vladoose.nir.entity.DealFileStage;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Исполнение сделки (спека 2026-10-19-deal-execution §9): запись — администратор, ответ на запись — вид заявки (раздел,
 * этапы и «Дальше» обновляются разом); скачать файл — любой вошедший. Гард рынка и вида сделки — в сервисе.
 */
@RestController
@RequestMapping("/api/private-requests/{id}/execution")
public class DealExecutionController {

    private final DealExecutionService service;
    private final PrivateRequestViewBuilder views;

    public DealExecutionController(DealExecutionService service, PrivateRequestViewBuilder views) {
        this.service = service;
        this.views = views;
    }

    @PatchMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse field(@PathVariable Long id, @Valid @RequestBody ExecutionFieldUpdate req) {
        return views.build(service.updateField(id, req.getField(), req.getValue()));
    }

    @PutMapping("/marks/{mark}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse mark(@PathVariable Long id, @PathVariable String mark, @RequestBody ExecutionMarkRequest req) {
        return views.build(service.setMark(id, parse(ExecutionMark.class, mark), req.getOn(), req.getNote()));
    }

    @PostMapping("/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse addPayment(@PathVariable Long id, @RequestBody DealPaymentRequest req) {
        return views.build(service.addPayment(id, input(req)));
    }

    @PutMapping("/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse updatePayment(@PathVariable Long id, @PathVariable Long paymentId,
                                                    @RequestBody DealPaymentRequest req) {
        return views.build(service.updatePayment(id, paymentId, input(req)));
    }

    @DeleteMapping("/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deletePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        return views.build(service.deletePayment(id, paymentId));
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse addSupplier(@PathVariable Long id, @RequestBody DealSupplierAddRequest req) {
        return views.build(service.addSupplier(id, req.getDistributorId(), req.getAmount(), req.getCurrency()));
    }

    @PatchMapping("/suppliers/{distributorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse updateSupplier(@PathVariable Long id, @PathVariable Long distributorId,
                                                     @Valid @RequestBody SupplierFieldUpdate req) {
        return views.build(service.updateSupplier(id, distributorId, req.getField(), req.getValue()));
    }

    @PutMapping("/suppliers/{distributorId}/marks/{mark}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse supplierMark(@PathVariable Long id, @PathVariable Long distributorId,
                                                   @PathVariable String mark, @RequestBody ExecutionMarkRequest req) {
        return views.build(service.setSupplierMark(id, distributorId, parse(SupplierMark.class, mark), req.getOn(), req.getNote()));
    }

    @DeleteMapping("/suppliers/{distributorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deleteSupplier(@PathVariable Long id, @PathVariable Long distributorId) {
        return views.build(service.deleteSupplier(id, distributorId));
    }

    @GetMapping("/file-candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ExecutionFileCandidate> candidates(@PathVariable Long id) {
        return service.candidates(id);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse attach(@PathVariable Long id,
                                             @RequestParam(value = "file", required = false) MultipartFile file,
                                             @RequestParam(value = "attachmentId", required = false) Long attachmentId,
                                             @RequestParam("stage") String stage,
                                             @RequestParam(value = "paymentId", required = false) Long paymentId,
                                             @RequestParam(value = "distributorId", required = false) Long distributorId)
            throws IOException {
        DealExecutionService.Upload upload = file == null || file.isEmpty() ? null
                : new DealExecutionService.Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return views.build(service.attach(id, upload, attachmentId, parse(DealFileStage.class, stage), paymentId,
                distributorId, CurrentUser.name()));
    }

    /** Файл: attachment с именем RFC 5987, nosniff, без кеша; тип — только PDF и картинки (как готовое КП). */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable Long id, @PathVariable Long fileId) {
        DealFile f = service.file(id, fileId);
        ReplyFileKind kind = SupplierReplyFiles.kind(f.getFileName(), f.getMimeType(), f.getContent());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReadyOfferController.contentType(kind, f.getFileName())))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.getContent());
    }

    @DeleteMapping("/files/{fileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestViewResponse deleteFile(@PathVariable Long id, @PathVariable Long fileId) {
        return views.build(service.deleteFile(id, fileId));
    }

    private static DealExecutionService.PaymentInput input(DealPaymentRequest r) {
        return new DealExecutionService.PaymentInput(r.getDistributorId(), r.getAmount(), r.getPaidOn(), r.getNote());
    }

    /** Неизвестное значение в пути или параметре — 400, а не 500 (решение при планировании 10). */
    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(type == DealFileStage.class ? "Неизвестное место файла" : "Неизвестная отметка");
        }
    }
}
```

- [ ] **Step 7: Зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.*'`
Expected: PASS (все классы пакета `execution`).

- [ ] **Step 8: Мутации**
  - `@PreAuthorize` снять с `field(...)` → красный `operatorReadsButCannotWrite`.
  - `PrivateRequestViewBuilder`: `execution` только при `exec.mainOffer() != null` → красный `revertedOfferKeepsSection`.
  - `@DynamicUpdate` снять с `DealExecution` → красный `overlappingExecutionFieldSavesKeepBothFields`; с `DealSupplier` → красный `overlappingSupplierSavesKeepBothFields`.
  - `parse(...)` без `try` → красный `russianTextsFor400` (500).

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/response/DealExecutionView.java \
  src/main/java/com/vladoose/nir/service/execution/DealExecutionViews.java \
  src/main/java/com/vladoose/nir/dto/request/ExecutionFieldUpdate.java src/main/java/com/vladoose/nir/dto/request/ExecutionMarkRequest.java \
  src/main/java/com/vladoose/nir/dto/request/DealPaymentRequest.java src/main/java/com/vladoose/nir/dto/request/DealSupplierAddRequest.java \
  src/main/java/com/vladoose/nir/dto/request/SupplierFieldUpdate.java src/main/java/com/vladoose/nir/controller/DealExecutionController.java \
  src/main/java/com/vladoose/nir/dto/response/PrivateRequestViewResponse.java \
  src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java \
  src/test/java/com/vladoose/nir/execution/DealExecutionApiTest.java \
  src/test/java/com/vladoose/nir/execution/DealExecutionOverlappingSavesTest.java
git commit -m "$(cat <<'EOF'
feat(execution): API исполнения сделки и раздел в виде заявки; наложение правок двух вкладок

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 9: Лента «Переписка и история» — строки исполнения

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java`
- Test: `src/test/java/com/vladoose/nir/execution/ExecutionTimelineTest.java`

**Interfaces:**
- Consumes: `DealExecutionLoader.load(Tender)`, `DealExecutionData` (задача 3), `DealFileRepository.findMetaByTenderId` (задача 1), `ExecutionTexts.money` (задача 2), `DealTexts.ddMM(LocalDate)` (есть).
- Produces: виды строк `EXEC_CONTRACT, EXEC_PAY_CLIENT, EXEC_ORDER, EXEC_PAY_SUPPLIER, EXEC_SHIPPED, EXEC_DELIVERED, EXEC_DOCS, EXEC_FILE`; тип ссылки — ключ блока `EXEC_CONTRACT | EXEC_PAYMENT | EXEC_SUPPLIERS | EXEC_DELIVERY | EXEC_DOCS`, `linkId` пусто (спека §10).

- [ ] **Step 1: Тест (красный)**

`src/test/java/com/vladoose/nir/execution/ExecutionTimelineTest.java`:

```java
package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ExecutionField;
import com.vladoose.nir.dto.request.ExecutionMark;
import com.vladoose.nir.dto.request.SupplierField;
import com.vladoose.nir.dto.request.SupplierMark;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import com.vladoose.nir.service.execution.DealExecutionService.Upload;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Строки исполнения в ленте (спека 2026-10-19-deal-execution §10): тексты, «только дата», автор, ключ блока. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class ExecutionTimelineTest {

    @Autowired DealExecutionService service;
    @Autowired DealTimelineService timeline;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String nb(String s) { return s.replace(' ', ' '); }

    private List<DealTimelineItem> execItems(Tender t) {
        em.flush();
        em.clear();
        return timeline.forPrivateRequest(t.getId(), null, 50).items().stream()
                .filter(i -> i.kind().startsWith("EXEC_")).toList();
    }

    @Test
    void executionLines() {
        Tender t = requests.createEmpty();
        Distributor fotek = distributors.save(Distributor.builder().name("ФОТЕК ZZ " + System.nanoTime()).build());
        offers.save(line(offer(t, ClientOfferStatus.ACCEPTED, "3070455", 481), fotek.getId(), "1662150", "1"));
        Long id = t.getId(), f = fotek.getId();
        service.updateField(id, ExecutionField.CONTRACT_NUMBER, "42/2026");
        service.updateField(id, ExecutionField.CONTRACT_DATE, "2026-10-10");
        service.addPayment(id, new PaymentInput(null, new BigDecimal("1535227.50"), LocalDate.of(2026, 10, 11), "предоплата"));
        service.setSupplierMark(id, f, SupplierMark.ORDER, "2026-10-12", "заказ 7");
        service.updateSupplier(id, f, SupplierField.CURRENCY, "RUB");
        service.updateSupplier(id, f, SupplierField.AMOUNT, "277025");
        service.addPayment(id, new PaymentInput(f, new BigDecimal("277025"), LocalDate.of(2026, 10, 13), null));
        service.setSupplierMark(id, f, SupplierMark.SHIP, "2026-10-14", null);
        service.setMark(id, ExecutionMark.DELIVERY, "2026-10-15", "обучение");
        service.setMark(id, ExecutionMark.DOCS, "2026-10-15", null);
        service.attach(id, new Upload("Договор 42.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8)),
                null, DealFileStage.CONTRACT, null, null, "vlad");

        List<DealTimelineItem> items = execItems(t);
        String name = fotek.getName();
        assertThat(items).extracting(DealTimelineItem::kind, DealTimelineItem::text).containsExactly(
                tuple("EXEC_CONTRACT", "Договор № 42/2026 от 10.10 подписан"),
                tuple("EXEC_PAY_CLIENT", "Оплата клиента: " + nb("1 535 227,50 тг") + " — предоплата"),
                tuple("EXEC_ORDER", "Заказ у " + name + " — заказ 7"),
                tuple("EXEC_PAY_SUPPLIER", "Оплата " + name + ": " + nb("277 025 руб.")),
                tuple("EXEC_SHIPPED", name + " отгрузил"),
                tuple("EXEC_DELIVERED", "Поставлено клиенту — обучение"),
                tuple("EXEC_DOCS", "Закрывающие документы подписаны"),
                tuple("EXEC_FILE", "«Договор 42.pdf» — к договору"));
        assertThat(items).extracting(DealTimelineItem::linkType).containsExactly("EXEC_CONTRACT", "EXEC_PAYMENT",
                "EXEC_SUPPLIERS", "EXEC_SUPPLIERS", "EXEC_SUPPLIERS", "EXEC_DELIVERY", "EXEC_DOCS", "EXEC_CONTRACT");
        assertThat(items.get(1).dateOnly()).isTrue();
        assertThat(items.get(1).author()).isEqualTo("vlad");
        assertThat(items.get(0).author()).isNull();
        assertThat(items.get(7).dateOnly()).isFalse();
        assertThat(items.get(7).author()).isEqualTo("vlad");
        assertThat(items).allMatch(i -> i.linkId() == null);
    }

    @Test
    void invoiceAndWithoutContract() {
        Tender t = requests.createEmpty();
        offers.save(offer(t, ClientOfferStatus.ACCEPTED, "100", 1));
        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "INVOICE");
        service.updateField(t.getId(), ExecutionField.CONTRACT_NUMBER, "15");
        service.updateField(t.getId(), ExecutionField.CONTRACT_DATE, "2026-10-10");
        assertThat(execItems(t)).extracting(DealTimelineItem::text).containsExactly("Счёт № 15 от 10.10 выставлен");

        service.updateField(t.getId(), ExecutionField.CONTRACT_KIND, "NONE");   // «без договора» — строки нет
        assertThat(execItems(t)).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.ExecutionTimelineTest'`
Expected: FAIL — строк `EXEC_*` нет (`Expecting actual … to contain exactly … but was []`).

- [ ] **Step 3: Источник ленты**

В `DealTimelineService`:

1. Поля `private final DealExecutionLoader execution;` и `private final DealFileRepository execFiles;` — последними параметрами конструктора (импорты `com.vladoose.nir.dto.response.DealFileMeta`, `com.vladoose.nir.service.execution.DealExecutionData`, `DealExecutionLoader`, `ExecutionTexts`).
2. В `forPrivateRequest` после `addOffers(all, t.getId());`: `addExecution(all, t, zone(t));`
3. Методы:

```java
    /** Исполнение сделки (спека 2026-10-19-deal-execution §10): даты — «только дата», файлы — время, когда приложили. */
    private void addExecution(List<DealTimelineItem> all, Tender t, ZoneId zone) {
        DealExecutionData d = execution.load(t);
        DealExecution e = d.row();
        if (e != null) {
            if (e.getContractDate() != null && e.getContractKind() != ContractKind.NONE) {
                all.add(execDate(e.getContractDate(), zone, "EXEC_CONTRACT", contractText(e), null, "EXEC_CONTRACT"));
            }
            if (e.getDeliveredOn() != null) {
                all.add(execDate(e.getDeliveredOn(), zone, "EXEC_DELIVERED", withNote("Поставлено клиенту", e.getDeliveryNote()),
                        null, "EXEC_DELIVERY"));
            }
            if (e.getDocsOn() != null) {
                all.add(execDate(e.getDocsOn(), zone, "EXEC_DOCS", withNote("Закрывающие документы подписаны", e.getDocsNote()),
                        null, "EXEC_DOCS"));
            }
        }
        for (DealPayment p : d.clientPayments()) {
            all.add(execDate(p.getPaidOn(), zone, "EXEC_PAY_CLIENT",
                    withNote("Оплата клиента: " + ExecutionTexts.money(p.getAmount(), d.currency()), p.getNote()),
                    p.getCreatedBy(), "EXEC_PAYMENT"));
        }
        Map<Long, String> supplierNames = new HashMap<>();
        for (DealExecutionData.SupplierLine s : d.supplierLines()) {
            if (s.rowId() != null) supplierNames.put(s.rowId(), s.name());
            if (s.orderedOn() != null) {
                all.add(execDate(s.orderedOn(), zone, "EXEC_ORDER", withNote("Заказ у " + s.name(), s.orderNote()), null, "EXEC_SUPPLIERS"));
            }
            for (DealPayment p : s.payments()) {
                all.add(execDate(p.getPaidOn(), zone, "EXEC_PAY_SUPPLIER",
                        withNote("Оплата " + s.name() + ": " + ExecutionTexts.money(p.getAmount(), s.currency()), p.getNote()),
                        p.getCreatedBy(), "EXEC_SUPPLIERS"));
            }
            if (s.shippedOn() != null) {
                all.add(execDate(s.shippedOn(), zone, "EXEC_SHIPPED", withNote(s.name() + " отгрузил", s.shipNote()), null, "EXEC_SUPPLIERS"));
            }
        }
        for (DealFileMeta f : execFiles.findMetaByTenderId(t.getId())) {
            all.add(new DealTimelineItem(f.uploadedAt(), false, "EXEC_FILE", "«" + f.name() + "» — " + filePlace(f, supplierNames),
                    f.uploadedBy(), fileBlock(f.stage()), null, null));
        }
    }

    private static DealTimelineItem execDate(LocalDate d, ZoneId zone, String kind, String text, String author, String block) {
        return new DealTimelineItem(d.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime(), true, kind, text, author, block,
                null, null);
    }

    static String contractText(DealExecution e) {
        boolean invoice = e.getContractKind() == ContractKind.INVOICE;
        String no = blank(e.getContractNumber()) ? "" : " № " + e.getContractNumber().strip();
        return (invoice ? "Счёт" : "Договор") + no + " от " + DealTexts.ddMM(e.getContractDate())
                + (invoice ? " выставлен" : " подписан");
    }

    private static String withNote(String text, String note) {
        return blank(note) ? text : text + " — " + note.strip();
    }

    private static String filePlace(DealFileMeta f, Map<Long, String> names) {
        String name = f.dealSupplierId() == null ? "" : names.getOrDefault(f.dealSupplierId(), "поставщику");
        return switch (f.stage()) {
            case CONTRACT -> "к договору";
            case CLIENT_PAYMENT -> "к оплате клиента";
            case SUPPLIER -> f.paymentId() != null ? "к оплате " + name : "к поставщику " + name;
            case DELIVERY -> "к поставке";
            case DOCS -> "к документам";
        };
    }

    private static String fileBlock(DealFileStage stage) {
        return switch (stage) {
            case CONTRACT -> "EXEC_CONTRACT";
            case CLIENT_PAYMENT -> "EXEC_PAYMENT";
            case SUPPLIER -> "EXEC_SUPPLIERS";
            case DELIVERY -> "EXEC_DELIVERY";
            case DOCS -> "EXEC_DOCS";
        };
    }
```

- [ ] **Step 4: Зелёный (и прежние тесты ленты)**

Run: `./gradlew test --tests 'com.vladoose.nir.execution.ExecutionTimelineTest' --tests 'com.vladoose.nir.deal.*'`
Expected: PASS (подсчёт запросов `DealTimelineServiceTest` — сравнение двух выборок, добавка исполнения постоянна).

- [ ] **Step 5: Мутации**
  - Контракт «без договора» тоже в ленту (без проверки `!= NONE`) → красный `invoiceAndWithoutContract`.
  - `execDate(...)` с `dateOnly = false` → красный `executionLines` (`items.get(1).dateOnly()`).
  - Автор платежа — `null` → красный `executionLines`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java \
  src/test/java/com/vladoose/nir/execution/ExecutionTimelineTest.java
git commit -m "$(cat <<'EOF'
feat(execution): лента сделки — договор, платежи, заказы и отгрузки, поставка, документы, файлы

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 10: Фронт — раздел «Исполнение сделки» (сумма, договор, оплата клиента, поставка, документы, файлы)

**Files:**
- Create: `frontend/src/app/shared/deal-execution.ts`
- Create: `frontend/src/app/pages/private-requests/pr-exec-entry-form.component.ts`, `pr-exec-files.component.ts`, `pr-exec-file-dialog.component.ts`, `pr-execution.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`, `frontend/src/app/shared/private-request.ts`, `frontend/src/app/shared/deal.ts`, `frontend/src/app/shared/deal-timeline.component.ts`, `frontend/src/app/pages/private-requests/private-request-page.component.ts`

**Interfaces:**
- Consumes: JSON `execution` вида заявки и пути задачи 8; коды действий и этапа задач 2, 4; виды строк ленты и ключи блоков задачи 9.
- Produces: `PrExecutionComponent` (`app-pr-execution`): входы `view: PrView`, `readonly`; выходы `viewChange: PrView`, `saveState: SaveState`; методы `scrollTo(key)`, `act(a: DealAction)`, `attach(target: ExecFileTarget)`. Задача 11 вставляет в него `app-pr-exec-suppliers`.

Фронт-тестов в проекте нет: гейт — `npm run build`, поведение — живая проверка задачи 12.

- [ ] **Step 1: Типы и помощники**

`frontend/src/app/shared/deal-execution.ts`:

```ts
/** Исполнение сделки (спека 2026-10-19-deal-execution): раздел страницы частной заявки после «Клиент согласился». */
export type ContractKind = 'CONTRACT' | 'INVOICE' | 'NONE';
export type ExecField = 'CONTRACT_KIND' | 'CONTRACT_NUMBER' | 'CONTRACT_DATE' | 'CONTRACT_NOTE' | 'PREPAY_PCT' | 'AMOUNT' | 'FROM_STOCK';
export type ExecMark = 'DELIVERY' | 'DOCS';
export type SupplierField = 'AMOUNT' | 'CURRENCY' | 'EXCLUDED';
export type SupplierMark = 'ORDER' | 'SHIP';
export type ExecStage = 'CONTRACT' | 'CLIENT_PAYMENT' | 'SUPPLIER' | 'DELIVERY' | 'DOCS';

export interface ExecPayment { id: number; amount: number; paidOn: string; note: string | null; createdBy: string | null; }
export interface ExecSupplier {
  id: number | null; distributorId: number; name: string; fromOffer: boolean; manual: boolean; excluded: boolean;
  currency: string; amount: number | null; amountFromOffer: boolean; offerAmount: number | null;
  orderedOn: string | null; orderNote: string | null; paid: number; payments: ExecPayment[];
  shippedOn: string | null; shipNote: string | null;
}
export interface ExecFile {
  id: number; stage: ExecStage; paymentId: number | null; distributorId: number | null; name: string; size: number;
  uploadedBy: string | null; uploadedAt: string;
}
export interface DealExecution {
  visible: boolean; currency: string; amount: number | null; amountFromOffer: boolean; offerAmount: number | null;
  offerId: number | null; offerLabel: string | null;
  contractKind: ContractKind | null; contractNumber: string | null; contractDate: string | null; contractNote: string | null;
  prepayPct: number; prepayAmount: number | null; clientPaid: number; clientPayments: ExecPayment[];
  fromStock: boolean; suppliers: ExecSupplier[]; unassigned: { count: number; purchase: number | null } | null;
  deliveredOn: string | null; deliveryNote: string | null; docsOn: string | null; docsNote: string | null;
  files: ExecFile[];
}
export interface ExecFileCandidate {
  attachmentId: number; fileName: string; sizeBytes: number | null; sentAt: string; channel: string | null;
  subject: string | null; direction: 'IN' | 'OUT' | null;
}
/** Куда прикладывается файл: место, платёж, поставщик; title — в заголовок окна. */
export interface ExecFileTarget { stage: ExecStage; paymentId?: number | null; distributorId?: number | null; title: string; }
/** Форма «дата [+ сумма] + заметка». */
export interface ExecEntry { date: string; amount: number | null; note: string | null; }

export const EXEC_CURRENCIES: { v: string; l: string }[] = [
  { v: 'KZT', l: 'тг' }, { v: 'RUB', l: 'руб.' }, { v: 'USD', l: '$' }, { v: 'EUR', l: '€' }, { v: 'CNY', l: 'юань' },
];

export function execCurrency(code: string | null | undefined): string {
  return EXEC_CURRENCIES.find(c => c.v === code)?.l ?? (code || '');
}

const WHOLE = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
const CENTS = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** «3 070 455 тг», «1 535 227,50 тг»: копейки — только ненулевые (как ExecutionTexts на сервере). */
export function execMoney(v: number | null | undefined, currency?: string | null): string {
  if (v == null) return '';
  const s = Number.isInteger(v) ? WHOLE.format(v) : CENTS.format(v);
  return currency ? s + ' ' + execCurrency(currency) : s;
}

/** Файлы одного места: блок, платёж, поставщик. */
export function filesAt(e: DealExecution, stage: ExecStage, paymentId: number | null = null,
                        distributorId: number | null = null): ExecFile[] {
  return e.files.filter(f => f.stage === stage && (f.paymentId ?? null) === paymentId && (f.distributorId ?? null) === distributorId);
}
```

- [ ] **Step 2: Вид заявки, этапы, «Дальше», лента — общие файлы**

`shared/private-request.ts`:
- импорт `import type { DealExecution } from './deal-execution';`
- в `PrView` после `offers`: `/** Исполнение сделки — после «Клиент согласился» (спека 2026-10-19-deal-execution). */ execution: DealExecution | null;`
- `stageTone`: удалить `case 'KP_ACCEPTED'` и добавить перед `default`:

```ts
    case 'EXEC_PREPAY':
    case 'EXEC_FINAL_PAY':
    case 'EXEC_AMOUNT': return 'warn';
    case 'EXEC_DONE': return 'success';
    case 'EXEC_CONTRACT':
    case 'EXEC_ORDER':
    case 'EXEC_PAY_SUPPLIERS':
    case 'EXEC_SHIPPING':
    case 'EXEC_DELIVERY':
    case 'EXEC_DOCS': return 'accent';
```

`shared/deal.ts` — в `ACTION_LABELS`:

```ts
  EXEC_CONTRACT: 'Договор или счёт…', EXEC_AMOUNT: 'Сумма сделки…', EXEC_PAY_CLIENT: '+ Платёж клиента',
  EXEC_SUPPLIERS: 'К поставщикам', EXEC_ORDER: 'Отметить заказ…', EXEC_SUPPLIER_AMOUNT: 'Сумма…',
  EXEC_PAY_SUPPLIER: '+ Платёж поставщику', EXEC_SHIP: 'Отгрузил…', EXEC_DELIVERY: 'Отметить поставку…',
  EXEC_DOCS: 'Документы подписаны…',
```

в `KIND_ICONS`:

```ts
  EXEC_CONTRACT: '✍', EXEC_PAY_CLIENT: '💳', EXEC_ORDER: '🛒', EXEC_PAY_SUPPLIER: '💸', EXEC_SHIPPED: '📦',
  EXEC_DELIVERED: '🚚', EXEC_DOCS: '📑', EXEC_FILE: '📎',
```

`shared/deal-timeline.component.ts`:
- `@Output() localLink = new EventEmitter<string>();` (импорт `EventEmitter`, `Output`);
- метод `block(i: DealTimelineItem): string | null { return i.linkType?.startsWith('EXEC_') ? i.linkType.slice(5) : null; }`;
- в шаблоне строку ссылки заменить:

```html
              <a *ngIf="link(i) as l; else local" [routerLink]="l.path" [queryParams]="l.query">{{ i.text }}</a>
              <ng-template #local>
                <button type="button" class="tl-link" *ngIf="block(i) as b; else plain" (click)="localLink.emit(b)">{{ i.text }}</button>
              </ng-template>
              <ng-template #plain>{{ i.text }}</ng-template>
```

- в стиле `.tl-link` добавить `font: inherit; text-align: left;`.

- [ ] **Step 3: Методы `ApiService`**

Импорт: `import type { ExecField, ExecFileCandidate, ExecMark, ExecStage, SupplierField, SupplierMark } from '../shared/deal-execution';`. Методы — после блока «Готовое КП»:

```ts
  // === Исполнение сделки (спека 2026-10-19-deal-execution) ===
  updateExecutionField(id: number, field: ExecField, value: string | null): Observable<PrView> {
    return this.http.patch<PrView>(`${this.base}/private-requests/${id}/execution`, { field, value });
  }
  setExecutionMark(id: number, mark: ExecMark, on: string | null, note: string | null): Observable<PrView> {
    return this.http.put<PrView>(`${this.base}/private-requests/${id}/execution/marks/${mark}`, { on, note });
  }
  addExecutionPayment(id: number, body: { distributorId: number | null; amount: number; paidOn: string; note: string | null }): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/execution/payments`, body);
  }
  updateExecutionPayment(id: number, paymentId: number, body: { amount: number; paidOn: string; note: string | null }): Observable<PrView> {
    return this.http.put<PrView>(`${this.base}/private-requests/${id}/execution/payments/${paymentId}`, body);
  }
  deleteExecutionPayment(id: number, paymentId: number): Observable<PrView> {
    return this.http.delete<PrView>(`${this.base}/private-requests/${id}/execution/payments/${paymentId}`);
  }
  addExecutionSupplier(id: number, body: { distributorId: number; amount: number | null; currency: string }): Observable<PrView> {
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/execution/suppliers`, body);
  }
  updateExecutionSupplier(id: number, distributorId: number, field: SupplierField, value: string | null): Observable<PrView> {
    return this.http.patch<PrView>(`${this.base}/private-requests/${id}/execution/suppliers/${distributorId}`, { field, value });
  }
  setExecutionSupplierMark(id: number, distributorId: number, mark: SupplierMark, on: string | null, note: string | null): Observable<PrView> {
    return this.http.put<PrView>(`${this.base}/private-requests/${id}/execution/suppliers/${distributorId}/marks/${mark}`, { on, note });
  }
  deleteExecutionSupplier(id: number, distributorId: number): Observable<PrView> {
    return this.http.delete<PrView>(`${this.base}/private-requests/${id}/execution/suppliers/${distributorId}`);
  }
  getExecutionFileCandidates(id: number): Observable<ExecFileCandidate[]> {
    return this.http.get<ExecFileCandidate[]>(`${this.base}/private-requests/${id}/execution/file-candidates`);
  }
  attachExecutionFile(id: number, body: { file?: File; attachmentId?: number; stage: ExecStage; paymentId?: number | null;
                                          distributorId?: number | null }): Observable<PrView> {
    const fd = new FormData();
    if (body.file) fd.append('file', body.file, body.file.name);
    if (body.attachmentId != null) fd.append('attachmentId', String(body.attachmentId));
    fd.append('stage', body.stage);
    if (body.paymentId != null) fd.append('paymentId', String(body.paymentId));
    if (body.distributorId != null) fd.append('distributorId', String(body.distributorId));
    return this.http.post<PrView>(`${this.base}/private-requests/${id}/execution/files`, fd);
  }
  /** Файл исполнения — только blob (§14); имя — в Content-Disposition. */
  downloadExecutionFile(id: number, fileId: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/private-requests/${id}/execution/files/${fileId}`, { responseType: 'blob', observe: 'response' });
  }
  deleteExecutionFile(id: number, fileId: number): Observable<PrView> {
    return this.http.delete<PrView>(`${this.base}/private-requests/${id}/execution/files/${fileId}`);
  }
```

- [ ] **Step 4: Форма «дата [+ сумма] + заметка»**

`pages/private-requests/pr-exec-entry-form.component.ts`:

```ts
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MarketService } from '../../services/market.service';
import { marketToday } from '../../shared/deadline';
import { numText, parseNum } from '../../shared/client-offer';
import { execCurrency } from '../../shared/deal-execution';
import type { ExecEntry } from '../../shared/deal-execution';

/** Отметка (дата и заметка) или платёж (дата, сумма, заметка) — строкой в блоке раздела «Исполнение». */
@Component({
  selector: 'app-pr-exec-entry-form',
  standalone: true,
  imports: [NgIf, FormsModule],
  template: `
    <form class="entry" (ngSubmit)="submit()" (keydown.escape)="cancel.emit()">
      <label>Дата
        <input type="date" name="date" [max]="today" [(ngModel)]="date" required /></label>
      <label *ngIf="withAmount">Сумма{{ currency ? ', ' + label(currency) : '' }}
        <input name="amount" inputmode="decimal" [(ngModel)]="amountText" autocomplete="off" /></label>
      <label class="note">Заметка
        <input name="note" [(ngModel)]="note" maxlength="2000" [placeholder]="notePlaceholder" autocomplete="off" /></label>
      <span class="acts">
        <button type="submit" class="btn btn-primary" [disabled]="busy || !ready()">{{ busy ? 'Сохраняю…' : saveLabel }}</button>
        <button type="button" class="btn btn-line" [disabled]="busy" (click)="cancel.emit()">Отмена</button>
      </span>
    </form>
  `,
  styles: [`
    .entry { display: flex; flex-wrap: wrap; gap: 8px 10px; align-items: flex-end; padding: 8px 10px; border: 1px solid var(--border);
             border-radius: 8px; background: color-mix(in srgb, var(--accent) 6%, var(--surface)); }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label.note { flex: 1; min-width: 180px; }
    input { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
            font: inherit; font-size: 14px; }
    .acts { display: flex; gap: 6px; }
    @media (max-width: 900px) {
      .entry { flex-direction: column; align-items: stretch; }
      input { font-size: 16px; min-height: 40px; box-sizing: border-box; }
      .acts .btn { flex: 1; min-height: 40px; }
    }
  `],
})
export class PrExecEntryFormComponent implements OnInit {
  @Input() withAmount = false;
  @Input() currency: string | null = null;
  /** Начальные значения: дата (по умолчанию — сегодня по часам рынка), сумма, заметка. */
  @Input() initial: Partial<ExecEntry> | null = null;
  @Input() saveLabel = 'Сохранить';
  @Input() notePlaceholder = '';
  @Input() busy = false;
  @Output() save = new EventEmitter<ExecEntry>();
  @Output() cancel = new EventEmitter<void>();

  readonly today: string;
  date = '';
  amountText = '';
  note = '';

  constructor(market: MarketService) {
    this.today = marketToday(market.value);
  }

  ngOnInit() {
    this.date = this.initial?.date || this.today;
    this.amountText = numText(this.initial?.amount ?? null);
    this.note = this.initial?.note || '';
  }

  label(c: string): string { return execCurrency(c); }

  ready(): boolean {
    return !!this.date && (!this.withAmount || (parseNum(this.amountText) ?? 0) > 0);
  }

  submit() {
    if (this.busy || !this.ready()) return;
    this.save.emit({ date: this.date, amount: this.withAmount ? parseNum(this.amountText) : null, note: this.note.trim() || null });
  }
}
```

- [ ] **Step 5: Файлы места**

`pages/private-requests/pr-exec-files.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import type { PrView } from '../../shared/private-request';
import type { ExecFile } from '../../shared/deal-execution';
import { errorText } from '../../shared/error-text';
import { responseFile } from '../../shared/share';
import { saveBlob } from '../../shared/client-offer';

/** Файлы одного места раздела «Исполнение»: имя — скачать (blob, §14), × — удалить (админ). */
@Component({
  selector: 'app-pr-exec-files',
  standalone: true,
  imports: [NgFor, NgIf],
  template: `
    <span class="xf" *ngIf="files.length">
      <span class="xf-item" *ngFor="let f of files">
        <button type="button" class="xf-name" (click)="download(f)" [title]="f.name">📎 {{ f.name }}</button>
        <button type="button" class="xf-del" *ngIf="!readonly" [disabled]="busy" (click)="remove(f)"
                [attr.aria-label]="'Удалить файл ' + f.name">×</button>
      </span>
    </span>
  `,
  styles: [`
    .xf { display: inline-flex; flex-wrap: wrap; gap: 4px 8px; }
    .xf-item { display: inline-flex; align-items: center; gap: 2px; max-width: 100%; }
    .xf-name { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px;
               max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .xf-name:hover { text-decoration: underline; }
    .xf-del { background: none; border: none; padding: 0 4px; color: var(--text-muted); cursor: pointer; font-size: 16px; line-height: 1; }
    .xf-del:hover { color: var(--danger-text); }
    @media (max-width: 900px) {
      .xf-name { min-height: 40px; max-width: 220px; }
      .xf-del { min-width: 40px; min-height: 40px; }
    }
  `],
})
export class PrExecFilesComponent {
  @Input({ required: true }) requestId!: number;
  @Input() files: ExecFile[] = [];
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  busy = false;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  download(f: ExecFile) {
    this.api.downloadExecutionFile(this.requestId, f.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { const file = responseFile(r, f.name); if (file) saveBlob(file, file.name); },
      error: e => this.notify.error('Файл не скачался: ' + errorText(e)),
    });
  }

  remove(f: ExecFile) {
    this.confirm.ask(`Удалить файл «${f.name}»?`, undefined, { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe(ok => {
        if (!ok) return;
        this.busy = true;
        this.api.deleteExecutionFile(this.requestId, f.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: v => { this.busy = false; this.viewChange.emit(v); },
          error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
        });
      });
  }
}
```


- [ ] **Step 6: Окно «Файл этапа»**

`pages/private-requests/pr-exec-file-dialog.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import type { PrView } from '../../shared/private-request';
import type { ExecFileCandidate, ExecFileTarget } from '../../shared/deal-execution';
import { errorText } from '../../shared/error-text';

/** Предел загрузки — как у сервера и nginx фронта (15 МБ). */
const UPLOAD_LIMIT = 15 * 1024 * 1024;

/**
 * Файл к этапу исполнения (спека 2026-10-19-deal-execution §4.4): из переписки сделки — в обе стороны (подписанный
 * договор присылает клиент) — или с компьютера. Пока идёт запрос, окно не закрывается.
 */
@Component({
  selector: 'app-pr-exec-file-dialog',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" [attr.aria-label]="'Файл: ' + target.title" (click)="$event.stopPropagation()">
        <h3>Файл: {{ target.title }}</h3>
        <div class="seg" role="radiogroup" aria-label="Откуда файл">
          <button type="button" role="radio" [attr.aria-checked]="mode === 'chat'" [class.on]="mode === 'chat'"
                  [disabled]="!candidates.length" (click)="mode = 'chat'">Из переписки</button>
          <button type="button" role="radio" [attr.aria-checked]="mode === 'pc'" [class.on]="mode === 'pc'" (click)="mode = 'pc'">С компьютера</button>
        </div>
        <p class="hint" *ngIf="loading">Загружаю файлы переписки…</p>
        <p class="hint" *ngIf="!loading && !candidates.length">В переписке сделки файлов нет — загрузите файл с компьютера.</p>
        <div class="files" *ngIf="mode === 'chat'">
          <label class="file" *ngFor="let c of candidates" [class.on]="picked?.attachmentId === c.attachmentId">
            <input type="radio" name="ef" [checked]="picked?.attachmentId === c.attachmentId" (change)="picked = c" />
            <span class="f-main">
              <span class="f-meta">{{ when(c) }} · {{ c.direction === 'IN' ? 'клиент' : 'мы' }} · {{ c.channel === 'EMAIL' ? 'письмо' : 'WhatsApp' }}<ng-container
                *ngIf="c.subject"> «{{ c.subject }}»</ng-container></span>
              <span class="f-name">📎 {{ c.fileName }}</span>
            </span>
          </label>
        </div>
        <div class="pc" *ngIf="mode === 'pc'">
          <label class="btn btn-line pick-file">{{ file ? 'Другой файл' : 'Выбрать файл' }}
            <input type="file" hidden (change)="onFile($event)" /></label>
          <span class="f-name" *ngIf="file">{{ file.name }}</span>
          <p class="hint">Любой документ или картинка, до 15 МБ.</p>
        </div>
        <div class="error-banner" *ngIf="error" role="alert">{{ error }}</div>
        <div class="acts">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="dismiss()">Отмена</button>
          <button type="button" class="btn btn-primary" [disabled]="busy || !ready()" (click)="submit()">{{ busy ? 'Прикладываю…' : 'Приложить' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 600px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    h3 { margin: 0; }
    .seg { display: flex; gap: 6px; flex-wrap: wrap; }
    .seg button { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px;
                  font-size: 13px; cursor: pointer; }
    .seg button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .seg button:disabled { cursor: not-allowed; opacity: .6; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .files { display: flex; flex-direction: column; gap: 6px; }
    .file { border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; display: flex; gap: 8px; align-items: flex-start; cursor: pointer; }
    .file.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .f-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .f-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .f-name { font-weight: 600; overflow-wrap: anywhere; }
    .pc { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
    .pick-file { cursor: pointer; }
    .acts { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .seg button, .file, .acts .btn, .pick-file { min-height: 40px; }
    }
  `],
})
export class PrExecFileDialogComponent implements OnInit {
  @Input({ required: true }) requestId!: number;
  @Input({ required: true }) target!: ExecFileTarget;
  @Output() close = new EventEmitter<void>();
  @Output() viewChange = new EventEmitter<PrView>();

  candidates: ExecFileCandidate[] = [];
  loading = true;
  mode: 'chat' | 'pc' = 'chat';
  picked: ExecFileCandidate | null = null;
  file: File | null = null;
  busy = false;
  error = '';

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnInit() {
    this.api.getExecutionFileCandidates(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.loading = false;
        this.candidates = list;
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

  /** Закрыть (фон, Esc, «Отмена»), но не во время запроса. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (!this.busy) this.close.emit();
  }

  onFile(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
    input.value = '';
    this.error = '';
  }

  ready(): boolean {
    return this.mode === 'pc' ? !!this.file : !!this.picked;
  }

  when(c: ExecFileCandidate): string {
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
    const where = { stage: this.target.stage, paymentId: this.target.paymentId ?? null, distributorId: this.target.distributorId ?? null };
    const body = this.mode === 'pc' ? { ...where, file: this.file! } : { ...where, attachmentId: this.picked!.attachmentId };
    this.api.attachExecutionFile(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.viewChange.emit(v); this.close.emit(); },
      error: e => {
        this.busy = false;
        this.error = e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : errorText(e);
        this.cdr.markForCheck();
      },
    });
  }
}
```

- [ ] **Step 7: Раздел «Исполнение сделки»**

`pages/private-requests/pr-execution.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { nextSaveSeq } from '../../shared/private-request';
import type { PrView, SaveState } from '../../shared/private-request';
import { errorText, retriable } from '../../shared/error-text';
import { dateText, numText, parseNum } from '../../shared/client-offer';
import { marketToday } from '../../shared/deadline';
import type { DealAction } from '../../shared/deal';
import { execMoney, filesAt } from '../../shared/deal-execution';
import type { DealExecution, ExecEntry, ExecField, ExecFileTarget, ExecMark, ExecPayment } from '../../shared/deal-execution';
import { PrExecEntryFormComponent } from './pr-exec-entry-form.component';
import { PrExecFilesComponent } from './pr-exec-files.component';
import { PrExecFileDialogComponent } from './pr-exec-file-dialog.component';

/**
 * «Исполнение сделки» на странице заявки (спека 2026-10-19-deal-execution §4): сумма сделки, договор с предоплатой,
 * платежи клиента, поставщики (задача 11), поставка, закрывающие документы, файлы к каждому месту. Поля договора
 * сохраняются по выходу из поля (индикатор страницы), формы — кнопкой; ответ — новый вид заявки (этапы, «Дальше»).
 */
@Component({
  selector: 'app-pr-execution',
  standalone: true,
  imports: [NgIf, NgFor, PrExecEntryFormComponent, PrExecFilesComponent, PrExecFileDialogComponent],
  template: `
    <section class="card" *ngIf="view.execution as e">
      <div class="sec-head">
        <h3>Исполнение сделки</h3>
        <span class="sum" *ngIf="!amountEdit">
          <ng-container *ngIf="e.amount != null; else noAmount">
            <span *ngIf="e.amountFromOffer && e.offerLabel">по {{ e.offerLabel }} · </span><b>{{ money(e.amount, e.currency) }}</b>
            <span class="muted" *ngIf="!e.amountFromOffer && e.offerAmount != null"> (по КП — {{ money(e.offerAmount) }})</span>
          </ng-container>
          <ng-template #noAmount><span class="warn">сумма сделки не указана</span></ng-template>
          <button type="button" class="icon" *ngIf="!readonly" (click)="amountEdit = true" aria-label="Изменить сумму сделки">✎</button>
        </span>
        <span class="inline-form" *ngIf="amountEdit">
          <input #amountIn [value]="numText(e.amount)" inputmode="decimal" aria-label="Сумма сделки"
                 (keydown.enter)="saveAmount(amountIn.value)" (keydown.escape)="amountEdit = false" />
          <button type="button" class="btn btn-primary" (click)="saveAmount(amountIn.value)">Сохранить</button>
          <button type="button" class="btn btn-line" *ngIf="!e.amountFromOffer && e.offerAmount != null" (click)="saveAmount('')">Вернуть по КП</button>
          <button type="button" class="btn btn-line" (click)="amountEdit = false">Отмена</button>
        </span>
      </div>

      <div class="blk" #contractBlk>
        <div class="blk-head">
          <h4>Договор</h4>
          <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'CONTRACT')" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
          <button type="button" class="link" *ngIf="!readonly" (click)="attach({ stage: 'CONTRACT', title: 'договор' })">📎 Приложить</button>
        </div>
        <div class="row" *ngIf="!contractEdit">
          <span [class.done]="contractDone(e)">{{ contractText(e) }}</span>
          <button type="button" class="icon" *ngIf="!readonly" (click)="contractEdit = true" aria-label="Изменить договор">✎</button>
        </div>
        <div class="grid" *ngIf="contractEdit">
          <label>Вид
            <select (change)="field('CONTRACT_KIND', $any($event.target).value)">
              <option value="CONTRACT" [selected]="e.contractKind !== 'INVOICE' && e.contractKind !== 'NONE'">Договор</option>
              <option value="INVOICE" [selected]="e.contractKind === 'INVOICE'">Счёт</option>
              <option value="NONE" [selected]="e.contractKind === 'NONE'">Без договора</option>
            </select></label>
          <ng-container *ngIf="e.contractKind !== 'NONE'">
            <label>№
              <input [value]="e.contractNumber || ''" maxlength="100" (blur)="commit('CONTRACT_NUMBER', $event, e.contractNumber)"
                     (keydown.enter)="blur($event)" /></label>
            <label>Дата
              <input type="date" [max]="today" [value]="e.contractDate || ''" (change)="commit('CONTRACT_DATE', $event, e.contractDate)" /></label>
          </ng-container>
          <label>Предоплата, %
            <input [value]="pctText(e.prepayPct)" inputmode="decimal" (blur)="commit('PREPAY_PCT', $event, pctText(e.prepayPct))"
                   (keydown.enter)="blur($event)" />
            <small>0 — оплата после поставки</small></label>
          <label class="wide">Заметка
            <input [value]="e.contractNote || ''" maxlength="2000" (blur)="commit('CONTRACT_NOTE', $event, e.contractNote)"
                   (keydown.enter)="blur($event)" /></label>
          <span class="wide"><button type="button" class="btn btn-line" (click)="contractEdit = false">Готово</button></span>
        </div>
      </div>

      <div class="blk" #paymentBlk>
        <div class="blk-head">
          <h4>Оплата клиента</h4>
          <span class="muted">{{ paidText(e) }}</span>
        </div>
        <div class="pay" *ngFor="let p of e.clientPayments">
          <ng-container *ngIf="editPayment !== p.id; else payEdit">
            <span class="d">{{ dmy(p.paidOn) }}</span>
            <span class="amt">{{ money(p.amount, e.currency) }}</span>
            <span class="note" *ngIf="p.note">{{ p.note }}</span>
            <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'CLIENT_PAYMENT', p.id)" [readonly]="readonly"
                               (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
            <span class="acts" *ngIf="!readonly">
              <button type="button" class="link" (click)="attach({ stage: 'CLIENT_PAYMENT', paymentId: p.id, title: 'оплата ' + dmy(p.paidOn) })"
                      aria-label="Приложить файл к платежу">📎</button>
              <button type="button" class="link" (click)="editPayment = p.id" aria-label="Изменить платёж">✎</button>
              <button type="button" class="link danger" [disabled]="busy" (click)="removePayment(p, e.currency)" aria-label="Удалить платёж">×</button>
            </span>
          </ng-container>
          <ng-template #payEdit>
            <app-pr-exec-entry-form [withAmount]="true" [currency]="e.currency" [busy]="busy"
                                    [initial]="{ date: p.paidOn, amount: p.amount, note: p.note }"
                                    (save)="updatePayment(p, $event)" (cancel)="editPayment = null"></app-pr-exec-entry-form>
          </ng-template>
        </div>
        <app-pr-exec-entry-form *ngIf="addPayment" [withAmount]="true" [currency]="e.currency" [busy]="busy"
                                [initial]="{ amount: suggestClient(e) }" saveLabel="Внести платёж"
                                (save)="addClientPayment($event)" (cancel)="addPayment = false"></app-pr-exec-entry-form>
        <button type="button" class="btn btn-line add" *ngIf="!readonly && !addPayment" (click)="addPayment = true">+ Платёж</button>
        <span class="muted" *ngIf="readonly && !e.clientPayments.length">платежей нет</span>
      </div>

      <!-- поставщики: задача 11 -->

      <div class="blk" #deliveryBlk>
        <div class="blk-head">
          <h4>Поставка клиенту</h4>
          <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'DELIVERY')" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
          <button type="button" class="link" *ngIf="!readonly" (click)="attach({ stage: 'DELIVERY', title: 'поставка' })">📎 Приложить</button>
        </div>
        <div class="row" *ngIf="e.deliveredOn && !deliveryEdit">
          <span class="done">✓ Поставлено {{ dmy(e.deliveredOn) }}<ng-container *ngIf="e.deliveryNote"> · {{ e.deliveryNote }}</ng-container></span>
          <span class="acts" *ngIf="!readonly">
            <button type="button" class="link" (click)="deliveryEdit = true" aria-label="Изменить отметку поставки">✎</button>
            <button type="button" class="link danger" [disabled]="busy" (click)="mark('DELIVERY', null, null)">Снять отметку</button>
          </span>
        </div>
        <button type="button" class="btn btn-line add" *ngIf="!e.deliveredOn && !deliveryEdit && !readonly" (click)="deliveryEdit = true">Отметить поставку…</button>
        <span class="muted" *ngIf="!e.deliveredOn && readonly">не отмечено</span>
        <app-pr-exec-entry-form *ngIf="deliveryEdit" [busy]="busy" [initial]="{ date: e.deliveredOn || undefined, note: e.deliveryNote }"
                                notePlaceholder="введено в эксплуатацию, обучение проведено"
                                (save)="mark('DELIVERY', $event.date, $event.note)" (cancel)="deliveryEdit = false"></app-pr-exec-entry-form>
      </div>

      <div class="blk" #docsBlk>
        <div class="blk-head">
          <h4>Закрывающие документы</h4>
          <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'DOCS')" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
          <button type="button" class="link" *ngIf="!readonly" (click)="attach({ stage: 'DOCS', title: 'закрывающие документы' })">📎 Приложить</button>
        </div>
        <div class="row" *ngIf="e.docsOn && !docsEdit">
          <span class="done">✓ Подписаны {{ dmy(e.docsOn) }}<ng-container *ngIf="e.docsNote"> · {{ e.docsNote }}</ng-container></span>
          <span class="acts" *ngIf="!readonly">
            <button type="button" class="link" (click)="docsEdit = true" aria-label="Изменить отметку документов">✎</button>
            <button type="button" class="link danger" [disabled]="busy" (click)="mark('DOCS', null, null)">Снять отметку</button>
          </span>
        </div>
        <button type="button" class="btn btn-line add" *ngIf="!e.docsOn && !docsEdit && !readonly" (click)="docsEdit = true">Документы подписаны…</button>
        <span class="muted" *ngIf="!e.docsOn && readonly">не отмечено</span>
        <app-pr-exec-entry-form *ngIf="docsEdit" [busy]="busy" [initial]="{ date: e.docsOn || undefined, note: e.docsNote }"
                                notePlaceholder="накладная № 12, акт"
                                (save)="mark('DOCS', $event.date, $event.note)" (cancel)="docsEdit = false"></app-pr-exec-entry-form>
      </div>

      <app-pr-exec-file-dialog *ngIf="fileTarget" [requestId]="view.id" [target]="fileTarget"
                               (close)="fileTarget = null" (viewChange)="viewChange.emit($event)"></app-pr-exec-file-dialog>
    </section>
  `,
  styles: [`
    /* как соседние разделы страницы: карточка, заголовок 15 px */
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px;
            display: flex; flex-direction: column; gap: 12px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 15px; }
    h4 { margin: 0; font-size: 14px; }
    .sum { display: inline-flex; align-items: baseline; gap: 4px; flex-wrap: wrap; }
    .blk { display: flex; flex-direction: column; gap: 6px; padding-top: 10px; border-top: 1px solid var(--border); scroll-margin-top: 12px; }
    .blk-head { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
    .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .done { color: var(--success-text); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .warn { color: var(--warn-text); }
    .icon, .link { background: none; border: none; padding: 0 4px; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px; }
    .link.danger { color: var(--danger-text); }
    .link:disabled { opacity: .5; cursor: not-allowed; }
    .grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px 12px; align-items: end; }
    .grid .wide { grid-column: 1 / -1; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label small { font-size: 11px; }
    input, select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
                    font: inherit; font-size: 14px; }
    .inline-form { display: inline-flex; gap: 6px; flex-wrap: wrap; align-items: center; }
    .inline-form input { width: 160px; }
    .pay { display: flex; align-items: center; gap: 6px 12px; flex-wrap: wrap; padding: 4px 0; }
    .pay .d { color: var(--text-muted); font-size: 13px; min-width: 84px; }
    .pay .amt { font-weight: 600; }
    .pay .note { color: var(--text-muted); font-size: 13px; }
    .acts { display: inline-flex; gap: 2px; margin-left: auto; }
    .add { align-self: flex-start; }
    @media (max-width: 900px) {
      .grid { grid-template-columns: 1fr; }
      input, select { font-size: 16px; min-height: 40px; box-sizing: border-box; }
      .icon, .link { min-width: 40px; min-height: 40px; }
      .btn { min-height: 40px; }
      .inline-form { display: flex; width: 100%; }
      .inline-form input { width: 100%; }
    }
  `],
})
export class PrExecutionComponent {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  @Output() saveState = new EventEmitter<SaveState>();
  @ViewChild('contractBlk') contractBlk?: ElementRef<HTMLElement>;
  @ViewChild('paymentBlk') paymentBlk?: ElementRef<HTMLElement>;
  @ViewChild('deliveryBlk') deliveryBlk?: ElementRef<HTMLElement>;
  @ViewChild('docsBlk') docsBlk?: ElementRef<HTMLElement>;

  readonly today: string;
  busy = false;
  amountEdit = false;
  contractEdit = false;
  addPayment = false;
  editPayment: number | null = null;
  deliveryEdit = false;
  docsEdit = false;
  fileTarget: ExecFileTarget | null = null;
  readonly money = execMoney;
  readonly numText = numText;
  readonly dmy = dateText;
  readonly filesAt = filesAt;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef, private host: ElementRef<HTMLElement>,
              market: MarketService) {
    this.today = marketToday(market.value);
  }

  /** Шаг этапов → блок раздела; неизвестный ключ — начало раздела. */
  scrollTo(key: string) {
    const el = key === 'CONTRACT' ? this.contractBlk : key === 'PAYMENT' ? this.paymentBlk
      : key === 'DELIVERY' ? this.deliveryBlk : key === 'DOCS' ? this.docsBlk : null;
    (el?.nativeElement ?? this.host.nativeElement).scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** Кнопка «Дальше»: открыть форму своего блока и прокрутить к нему. */
  act(a: DealAction) {
    switch (a.action) {
      case 'EXEC_CONTRACT': this.contractEdit = true; this.scrollTo('CONTRACT'); break;
      case 'EXEC_AMOUNT': this.amountEdit = true; this.scrollTo(''); break;
      case 'EXEC_PAY_CLIENT': this.addPayment = true; this.scrollTo('PAYMENT'); break;
      case 'EXEC_DELIVERY': this.deliveryEdit = true; this.scrollTo('DELIVERY'); break;
      case 'EXEC_DOCS': this.docsEdit = true; this.scrollTo('DOCS'); break;
    }
    this.cdr.markForCheck();
  }

  contractDone(e: DealExecution): boolean {
    return e.contractKind !== 'NONE' && (!!e.contractNumber || !!e.contractDate);
  }

  contractText(e: DealExecution): string {
    const pre = ' · предоплата ' + (e.prepayPct > 0 ? this.pctText(e.prepayPct) + ' %' : 'нет — оплата после поставки');
    if (e.contractKind === 'NONE') return 'Без договора' + pre;
    const invoice = e.contractKind === 'INVOICE';
    if (!this.contractDone(e)) return (invoice ? 'Счёт не выставлен' : 'Договора пока нет') + pre;
    return (invoice ? '✓ Счёт' : '✓ Договор') + (e.contractNumber ? ' № ' + e.contractNumber : '')
      + (e.contractDate ? ' от ' + dateText(e.contractDate) : '') + pre;
  }

  pctText(p: number): string {
    return String(p).replace('.', ',');
  }

  paidText(e: DealExecution): string {
    return e.amount != null ? `оплачено ${execMoney(e.clientPaid)} из ${execMoney(e.amount, e.currency)}`
      : `оплачено ${execMoney(e.clientPaid, e.currency)}`;
  }

  /** Сумма нового платежа: остаток предоплаты, иначе остаток суммы сделки. */
  suggestClient(e: DealExecution): number | null {
    if (e.amount == null) return null;
    const pre = e.prepayAmount ?? 0;
    const rest = e.clientPaid < pre ? pre - e.clientPaid : e.amount - e.clientPaid;
    const r = Math.round(rest * 100) / 100;
    return r > 0 ? r : null;
  }

  blur(ev: Event) {
    (ev.target as HTMLElement).blur();
  }

  commit(field: ExecField, ev: Event, current: string | null) {
    const value = (ev.target as HTMLInputElement).value.trim();
    if (value === (current ?? '').trim()) return;
    this.field(field, value || null);
  }

  /** Поле — PATCH, состояние — общий индикатор страницы (как поля клиента). */
  field(field: ExecField, value: string | null) {
    const key = 'exec:' + field, seq = nextSaveSeq();
    this.saveState.emit({ kind: 'saving', key, seq });
    this.api.updateExecutionField(this.view.id, field, value).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.saveState.emit({ kind: 'saved', key, seq }); this.viewChange.emit(v); },
      error: e => {
        this.saveState.emit({ kind: 'error', key, seq, message: errorText(e),
          retry: retriable(e) ? () => this.field(field, value) : undefined });
        this.cdr.markForCheck();
      },
    });
  }

  saveAmount(text: string) {
    const t = text.trim();
    const n = parseNum(t);
    if (t && n == null) {
      this.notify.error('Сумма сделки — число, например 3 070 455');
      return;
    }
    this.amountEdit = false;
    this.field('AMOUNT', n == null ? null : String(n));
  }

  private run(req: Observable<PrView>, done: () => void, ok?: string) {
    if (this.busy) return;
    this.busy = true;
    req.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; done(); if (ok) this.notify.success(ok); this.viewChange.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  addClientPayment(x: ExecEntry) {
    this.run(this.api.addExecutionPayment(this.view.id, { distributorId: null, amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.addPayment = false, 'Платёж внесён');
  }

  updatePayment(p: ExecPayment, x: ExecEntry) {
    this.run(this.api.updateExecutionPayment(this.view.id, p.id, { amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.editPayment = null);
  }

  removePayment(p: ExecPayment, currency: string) {
    this.confirm.ask(`Удалить платёж ${execMoney(p.amount, currency)} от ${dateText(p.paidOn)}?`, 'Файлы платежа удалятся вместе с ним.',
      { danger: true, confirmLabel: 'Удалить' }).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.run(this.api.deleteExecutionPayment(this.view.id, p.id), () => {}); });
  }

  mark(m: ExecMark, on: string | null, note: string | null) {
    this.run(this.api.setExecutionMark(this.view.id, m, on, note), () => {
      if (m === 'DELIVERY') this.deliveryEdit = false; else this.docsEdit = false;
    });
  }

  attach(target: ExecFileTarget) {
    this.fileTarget = target;
  }
}
```

- [ ] **Step 8: Страница заявки**

`pages/private-requests/private-request-page.component.ts`:
- импорт `PrExecutionComponent` (в `imports` компонента);
- в шаблоне после блока `offersAnchor`:

```html
          <div #execAnchor class="anchor" *ngIf="v.execution">
            <app-pr-execution #exec [view]="v" [readonly]="!auth.isAdmin()" (viewChange)="onExecView($event)"
                              (saveState)="onSaveState($event)"></app-pr-execution>
          </div>
```

- у `app-deal-timeline` — `(localLink)="scrollToStep($event)"`;
- поле `@ViewChild('exec') exec?: PrExecutionComponent;`
- `scrollToStep` в начале:

```ts
    if (['CONTRACT', 'PAYMENT', 'SUPPLIERS', 'DELIVERY', 'DOCS'].includes(key)) {
      this.exec?.scrollTo(key);
      return;
    }
```

- в `onAct` — ветка по умолчанию: `default: if (a.action.startsWith('EXEC_')) this.exec?.act(a);`
- метод:

```ts
  /** Правка исполнения меняет и ленту (договор, платежи, отметки, файлы) — перечитать её сразу. */
  onExecView(v: PrView) {
    this.onView(v);
    this.timeline?.refresh();
  }
```

- [ ] **Step 9: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build; cd /Users/vlad/IdeaProjects/AIS`
Expected: сборка без ошибок; начальный бандл ≤ 1,5 МБ (новое — в ленивом чанке страницы заявки; проверить, что `pr-execution` не попал в `main`: `grep -l "Исполнение сделки" frontend/dist/*/browser/main*.js` — пусто).

- [ ] **Step 10: Коммит**

```bash
git add frontend/src/app/shared/deal-execution.ts frontend/src/app/shared/private-request.ts frontend/src/app/shared/deal.ts \
  frontend/src/app/shared/deal-timeline.component.ts frontend/src/app/services/api.service.ts \
  frontend/src/app/pages/private-requests/pr-exec-entry-form.component.ts frontend/src/app/pages/private-requests/pr-exec-files.component.ts \
  frontend/src/app/pages/private-requests/pr-exec-file-dialog.component.ts frontend/src/app/pages/private-requests/pr-execution.component.ts \
  frontend/src/app/pages/private-requests/private-request-page.component.ts
git commit -m "$(cat <<'EOF'
feat(execution): страница заявки — раздел «Исполнение сделки»: сумма, договор, платежи, поставка, документы, файлы

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 11: Фронт — поставщики сделки

**Files:**
- Create: `frontend/src/app/pages/private-requests/pr-exec-suppliers.component.ts`
- Modify: `frontend/src/app/pages/private-requests/pr-execution.component.ts`

**Interfaces:**
- Consumes: `ApiService` (задача 10: `updateExecutionField`, `updateExecutionSupplier`, `setExecutionSupplierMark`, `addExecutionSupplier`, `deleteExecutionSupplier`, `addExecutionPayment`, `updateExecutionPayment`, `deleteExecutionPayment`, `getDistributors`), `PrExecEntryFormComponent`, `PrExecFilesComponent`.
- Produces: `PrExecSuppliersComponent` (`app-pr-exec-suppliers`): входы `view`, `readonly`; выходы `viewChange`, `attach: ExecFileTarget`; методы `act(a)`, `scroll()`.

- [ ] **Step 1: Компонент поставщиков**

`pages/private-requests/pr-exec-suppliers.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, switchMap } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { plural } from '../../shared/private-request';
import type { PrView } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { dateText, numText, parseNum } from '../../shared/client-offer';
import type { DealAction } from '../../shared/deal';
import { EXEC_CURRENCIES, execMoney, filesAt } from '../../shared/deal-execution';
import type { DealExecution, ExecEntry, ExecFileTarget, ExecPayment, ExecSupplier, SupplierMark } from '../../shared/deal-execution';
import { PrExecEntryFormComponent } from './pr-exec-entry-form.component';
import { PrExecFilesComponent } from './pr-exec-files.component';

interface DirEntry { id: number; name: string; }

/**
 * Поставщики сделки (спека 2026-10-19-deal-execution §4.3): из строк принятого КП и добавленные вручную; у каждого —
 * заказ, платежи (в его валюте) и отгрузка; «Сумма и валюта…», «Не заказываем», «Со склада».
 */
@Component({
  selector: 'app-pr-exec-suppliers',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, PrExecEntryFormComponent, PrExecFilesComponent],
  template: `
    <div class="blk" #blk *ngIf="view.execution as e">
      <div class="blk-head">
        <h4>Поставщики</h4>
        <span class="muted">{{ summary(e) }}</span>
      </div>
      <label class="check" *ngIf="!readonly || e.fromStock">
        <input type="checkbox" [checked]="e.fromStock" [disabled]="readonly || busy" (change)="setStock($any($event.target).checked)" />
        Со склада — заказывать не нужно
      </label>
      <ng-container *ngIf="!e.fromStock">
        <div class="sup" *ngFor="let s of e.suppliers; trackBy: track" [class.off]="s.excluded">
          <div class="sup-head">
            <b class="sup-name">{{ s.name }}</b>
            <span class="muted">{{ amountText(s) }}</span>
            <span class="tag" *ngIf="s.excluded">не заказываем</span>
            <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'SUPPLIER', null, s.distributorId)" [readonly]="readonly"
                               (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
            <span class="menu-wrap" *ngIf="!readonly" (click)="$event.stopPropagation()">
              <button type="button" class="btn btn-more" (click)="menuFor = menuFor === s.distributorId ? null : s.distributorId"
                      [attr.aria-expanded]="menuFor === s.distributorId" [attr.aria-label]="'Действия: ' + s.name">⋯</button>
              <span class="row-menu" *ngIf="menuFor === s.distributorId">
                <button type="button" (click)="editAmount(s)">Сумма и валюта…</button>
                <button type="button" (click)="fileFor(s, null)">Приложить файл…</button>
                <button type="button" *ngIf="s.fromOffer && !s.excluded" (click)="exclude(s, true)">Не заказываем</button>
                <button type="button" *ngIf="s.excluded" (click)="exclude(s, false)">Вернуть в заказ</button>
                <button type="button" class="danger" *ngIf="s.manual" (click)="remove(s)">Удалить</button>
              </span>
            </span>
          </div>
          <div class="inline-form" *ngIf="amountFor === s.distributorId">
            <input #amt [value]="numText(s.amount)" inputmode="decimal" aria-label="Сумма к оплате"
                   (keydown.enter)="saveAmount(s, amt.value, cur.value)" (keydown.escape)="amountFor = null" />
            <select #cur aria-label="Валюта">
              <option *ngFor="let c of currencies" [value]="c.v" [selected]="c.v === s.currency">{{ c.l }}</option>
            </select>
            <button type="button" class="btn btn-primary" [disabled]="busy" (click)="saveAmount(s, amt.value, cur.value)">Сохранить</button>
            <button type="button" class="btn btn-line" *ngIf="!s.amountFromOffer && s.offerAmount != null" [disabled]="busy"
                    (click)="saveAmount(s, '', s.currency)">По КП</button>
            <button type="button" class="btn btn-line" (click)="amountFor = null">Отмена</button>
          </div>
          <div class="marks" *ngIf="!s.excluded">
            <span class="mark" [class.done]="!!s.orderedOn">
              <ng-container *ngIf="s.orderedOn; else noOrder">✓ Заказ {{ dmy(s.orderedOn) }}<ng-container *ngIf="s.orderNote"> · {{ s.orderNote }}</ng-container>
                <ng-container *ngIf="!readonly">
                  <button type="button" class="link" (click)="openMark(s, 'ORDER')" aria-label="Изменить отметку заказа">✎</button>
                  <button type="button" class="link danger" [disabled]="busy" (click)="clearMark(s, 'ORDER')">Снять</button>
                </ng-container>
              </ng-container>
              <ng-template #noOrder>○ Заказ
                <button type="button" class="link" *ngIf="!readonly" (click)="openMark(s, 'ORDER')">Отметить заказ…</button>
              </ng-template>
            </span>
            <span class="mark" [class.done]="paidUp(s)">{{ paidUp(s) ? '✓' : '○' }} {{ paidText(s) }}
              <button type="button" class="link" *ngIf="!readonly && !paidUp(s)" (click)="openPay(s)">+ Платёж</button>
            </span>
            <span class="mark" [class.done]="!!s.shippedOn">
              <ng-container *ngIf="s.shippedOn; else noShip">✓ Отгрузка {{ dmy(s.shippedOn) }}<ng-container *ngIf="s.shipNote"> · {{ s.shipNote }}</ng-container>
                <ng-container *ngIf="!readonly">
                  <button type="button" class="link" (click)="openMark(s, 'SHIP')" aria-label="Изменить отметку отгрузки">✎</button>
                  <button type="button" class="link danger" [disabled]="busy" (click)="clearMark(s, 'SHIP')">Снять</button>
                </ng-container>
              </ng-container>
              <ng-template #noShip>○ Отгрузка
                <button type="button" class="link" *ngIf="!readonly" (click)="openMark(s, 'SHIP')">Отгрузил…</button>
              </ng-template>
            </span>
          </div>
          <div class="pay" *ngFor="let p of s.payments">
            <ng-container *ngIf="editPay !== p.id; else payEdit">
              <span class="d">{{ dmy(p.paidOn) }}</span>
              <span class="amt">{{ money(p.amount, s.currency) }}</span>
              <span class="note" *ngIf="p.note">{{ p.note }}</span>
              <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'SUPPLIER', p.id, s.distributorId)" [readonly]="readonly"
                                 (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
              <span class="acts" *ngIf="!readonly">
                <button type="button" class="link" (click)="fileFor(s, p)" aria-label="Приложить файл к платежу">📎</button>
                <button type="button" class="link" (click)="editPay = p.id" aria-label="Изменить платёж">✎</button>
                <button type="button" class="link danger" [disabled]="busy" (click)="removePay(s, p)" aria-label="Удалить платёж">×</button>
              </span>
            </ng-container>
            <ng-template #payEdit>
              <app-pr-exec-entry-form [withAmount]="true" [currency]="s.currency" [busy]="busy"
                                      [initial]="{ date: p.paidOn, amount: p.amount, note: p.note }"
                                      (save)="updatePay(p, $event)" (cancel)="editPay = null"></app-pr-exec-entry-form>
            </ng-template>
          </div>
          <app-pr-exec-entry-form *ngIf="markFor?.d === s.distributorId" [busy]="busy"
                                  [initial]="{ date: (markFor?.m === 'ORDER' ? s.orderedOn : s.shippedOn) || undefined,
                                               note: markFor?.m === 'ORDER' ? s.orderNote : s.shipNote }"
                                  [notePlaceholder]="markFor?.m === 'ORDER' ? '№ заказа' : 'накладная, трек'"
                                  (save)="saveMark(s, $event)" (cancel)="markFor = null"></app-pr-exec-entry-form>
          <app-pr-exec-entry-form *ngIf="payFor === s.distributorId" [withAmount]="true" [currency]="s.currency" [busy]="busy"
                                  [initial]="{ amount: restOf(s) }" saveLabel="Внести платёж"
                                  (save)="addPay(s, $event)" (cancel)="payFor = null"></app-pr-exec-entry-form>
        </div>
        <p class="muted" *ngIf="e.unassigned as u">В КП без поставщика: {{ u.count }} {{ positions(u.count) }}<ng-container
          *ngIf="u.purchase != null"> (закупка {{ money(u.purchase, e.currency) }})</ng-container> — добавьте поставщика вручную.</p>
        <p class="muted" *ngIf="!e.suppliers.length">Поставщиков нет — добавьте или отметьте «Со склада».</p>
        <div class="add-sup" *ngIf="!readonly">
          <button type="button" class="btn btn-line" *ngIf="!adding" (click)="startAdd()">+ Поставщик</button>
          <div class="inline-form" *ngIf="adding">
            <span class="picker">
              <input type="search" [(ngModel)]="addQuery" (ngModelChange)="addPicked = null" placeholder="Поставщик из справочника"
                     aria-label="Поставщик" autocomplete="off" />
              <span class="opts" *ngIf="addQuery.trim() && !addPicked && options().length">
                <button type="button" *ngFor="let d of options()" (click)="addPicked = d; addQuery = d.name">{{ d.name }}</button>
              </span>
            </span>
            <input [(ngModel)]="addAmount" inputmode="decimal" placeholder="К оплате" aria-label="Сумма к оплате" />
            <select [(ngModel)]="addCurrency" aria-label="Валюта">
              <option *ngFor="let c of currencies" [ngValue]="c.v">{{ c.l }}</option>
            </select>
            <button type="button" class="btn btn-primary" [disabled]="busy || !addPicked" (click)="add()">Добавить</button>
            <button type="button" class="btn btn-line" (click)="adding = false">Отмена</button>
          </div>
        </div>
      </ng-container>
    </div>
  `,
  styles: [`
    .blk { display: flex; flex-direction: column; gap: 8px; padding-top: 10px; border-top: 1px solid var(--border); scroll-margin-top: 12px; }
    .blk-head { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
    h4 { margin: 0; font-size: 14px; }
    .muted { color: var(--text-muted); font-size: 13px; margin: 0; }
    .check { display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .sup { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; display: flex; flex-direction: column; gap: 6px; }
    .sup.off { opacity: .7; }
    .sup-head { display: flex; align-items: center; gap: 6px 10px; flex-wrap: wrap; }
    .sup-name { font-weight: 600; }
    .tag { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: var(--surface-2); color: var(--text); }
    .menu-wrap { position: relative; margin-left: auto; }
    .marks { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; }
    .mark { color: var(--text-muted); display: inline-flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .mark.done { color: var(--success-text); }
    .link { background: none; border: none; padding: 0 4px; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px; }
    .link.danger { color: var(--danger-text); }
    .link:disabled { opacity: .5; cursor: not-allowed; }
    .pay { display: flex; align-items: center; gap: 6px 12px; flex-wrap: wrap; font-size: 13px; }
    .pay .d { color: var(--text-muted); min-width: 84px; }
    .pay .amt { font-weight: 600; }
    .pay .note { color: var(--text-muted); }
    .acts { display: inline-flex; gap: 2px; margin-left: auto; }
    .inline-form { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
    input, select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
                    font: inherit; font-size: 14px; }
    .picker { position: relative; }
    .opts { position: absolute; z-index: 20; left: 0; right: 0; top: 100%; margin-top: 4px; display: flex; flex-direction: column;
            background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow);
            max-height: 240px; overflow-y: auto; min-width: 240px; }
    .opts button { background: none; border: none; text-align: left; padding: 8px 12px; cursor: pointer; color: var(--text); font: inherit; }
    .opts button:hover { background: var(--surface-2); }
    @media (max-width: 900px) {
      .marks { flex-direction: column; gap: 4px; }
      .link, .btn-more { min-width: 40px; min-height: 40px; }
      .btn { min-height: 40px; }
      input, select { font-size: 16px; min-height: 40px; box-sizing: border-box; }
      .inline-form { flex-direction: column; align-items: stretch; }
      .check { min-height: 40px; }
    }
  `],
})
export class PrExecSuppliersComponent {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  /** Приложить файл к поставщику или его платежу — окно у родителя. */
  @Output() attach = new EventEmitter<ExecFileTarget>();
  @ViewChild('blk') blk?: ElementRef<HTMLElement>;

  busy = false;
  menuFor: number | null = null;
  amountFor: number | null = null;
  markFor: { d: number; m: SupplierMark } | null = null;
  payFor: number | null = null;
  editPay: number | null = null;
  adding = false;
  addQuery = '';
  addPicked: DirEntry | null = null;
  addAmount = '';
  addCurrency: string;
  private directory: DirEntry[] = [];
  readonly currencies = EXEC_CURRENCIES;
  readonly money = execMoney;
  readonly numText = numText;
  readonly dmy = dateText;
  readonly filesAt = filesAt;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef, market: MarketService) {
    this.addCurrency = market.value === 'RF' ? 'RUB' : 'KZT';
  }

  @HostListener('document:click')
  closeMenu() {
    this.menuFor = null;
  }

  track(_: number, s: ExecSupplier) {
    return s.distributorId;
  }

  summary(e: DealExecution): string {
    if (e.fromStock) return 'со склада';
    const c = e.suppliers.filter(s => !s.excluded);
    if (!c.length) return '';
    const n = c.length;
    const ordered = c.filter(s => s.orderedOn).length, paid = c.filter(s => this.paidUp(s)).length;
    const shipped = c.filter(s => s.shippedOn).length;
    return `заказ ${ordered}/${n} · оплата ${paid}/${n} · отгрузка ${shipped}/${n}`;
  }

  paidUp(s: ExecSupplier): boolean {
    return s.amount != null && s.amount > 0 && s.paid >= s.amount;
  }

  paidText(s: ExecSupplier): string {
    if (s.amount != null) return `Оплачено ${execMoney(s.paid)} из ${execMoney(s.amount, s.currency)}`;
    return s.paid > 0 ? `Оплачено ${execMoney(s.paid, s.currency)} · сумма к оплате не указана` : 'Оплата · сумма к оплате не указана';
  }

  amountText(s: ExecSupplier): string {
    if (s.amount == null) return 'к оплате — впишите сумму';
    return 'к оплате ' + execMoney(s.amount, s.currency) + (s.amountFromOffer ? ' (по КП)' : '');
  }

  positions(n: number): string {
    return plural(n, 'позиция', 'позиции', 'позиций');
  }

  restOf(s: ExecSupplier): number | null {
    if (s.amount == null) return null;
    const rest = Math.round((s.amount - s.paid) * 100) / 100;
    return rest > 0 ? rest : null;
  }

  private run(req: Observable<PrView>, done: () => void, ok?: string) {
    if (this.busy) return;
    this.busy = true;
    req.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; done(); if (ok) this.notify.success(ok); this.viewChange.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  setStock(on: boolean) {
    this.run(this.api.updateExecutionField(this.view.id, 'FROM_STOCK', String(on)), () => {});
  }

  exclude(s: ExecSupplier, on: boolean) {
    this.menuFor = null;
    this.run(this.api.updateExecutionSupplier(this.view.id, s.distributorId, 'EXCLUDED', String(on)), () => {});
  }

  editAmount(s: ExecSupplier) {
    this.menuFor = null;
    this.amountFor = s.distributorId;
  }

  /** Валюта (если сменилась) — сначала, потом сумма; пустая сумма — снова по КП. */
  saveAmount(s: ExecSupplier, amountText: string, currency: string) {
    const t = amountText.trim();
    const n = parseNum(t);
    if (t && n == null) {
      this.notify.error('Сумма к оплате — число');
      return;
    }
    const id = this.view.id, d = s.distributorId, amount = n == null ? null : String(n);
    const req = currency !== s.currency
      ? this.api.updateExecutionSupplier(id, d, 'CURRENCY', currency).pipe(switchMap(() => this.api.updateExecutionSupplier(id, d, 'AMOUNT', amount)))
      : this.api.updateExecutionSupplier(id, d, 'AMOUNT', amount);
    this.run(req, () => this.amountFor = null);
  }

  openMark(s: ExecSupplier, m: SupplierMark) {
    this.markFor = { d: s.distributorId, m };
    this.payFor = null;
  }

  saveMark(s: ExecSupplier, x: ExecEntry) {
    const m = this.markFor?.m;
    if (!m) return;
    this.run(this.api.setExecutionSupplierMark(this.view.id, s.distributorId, m, x.date, x.note), () => this.markFor = null);
  }

  clearMark(s: ExecSupplier, m: SupplierMark) {
    this.run(this.api.setExecutionSupplierMark(this.view.id, s.distributorId, m, null, null), () => {});
  }

  openPay(s: ExecSupplier) {
    this.payFor = s.distributorId;
    this.markFor = null;
  }

  addPay(s: ExecSupplier, x: ExecEntry) {
    this.run(this.api.addExecutionPayment(this.view.id, { distributorId: s.distributorId, amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.payFor = null, 'Платёж поставщику внесён');
  }

  updatePay(p: ExecPayment, x: ExecEntry) {
    this.run(this.api.updateExecutionPayment(this.view.id, p.id, { amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.editPay = null);
  }

  removePay(s: ExecSupplier, p: ExecPayment) {
    this.confirm.ask(`Удалить платёж ${s.name} ${execMoney(p.amount, s.currency)} от ${dateText(p.paidOn)}?`,
      'Файлы платежа удалятся вместе с ним.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.run(this.api.deleteExecutionPayment(this.view.id, p.id), () => {}); });
  }

  remove(s: ExecSupplier) {
    this.menuFor = null;
    this.confirm.ask(`Удалить «${s.name}» из сделки?`, 'Его платежи и файлы удалятся вместе с ним.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.run(this.api.deleteExecutionSupplier(this.view.id, s.distributorId), () => {}); });
  }

  fileFor(s: ExecSupplier, p: ExecPayment | null) {
    this.menuFor = null;
    this.attach.emit({ stage: 'SUPPLIER', distributorId: s.distributorId, paymentId: p?.id ?? null,
      title: p ? `оплата ${s.name} ${dateText(p.paidOn)}` : s.name });
  }

  startAdd() {
    this.adding = true;
    this.addQuery = '';
    this.addPicked = null;
    this.addAmount = '';
    if (this.directory.length) return;
    this.api.getDistributors().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => { this.directory = (list || []).map((d: any) => ({ id: d.id, name: d.name })); this.cdr.markForCheck(); },
      error: e => this.notify.error('Справочник поставщиков не загрузился: ' + errorText(e)),
    });
  }

  options(): DirEntry[] {
    const taken = new Set((this.view.execution?.suppliers ?? []).map(s => s.distributorId));
    const q = this.addQuery.trim().toLowerCase();
    return this.directory.filter(d => !taken.has(d.id) && d.name.toLowerCase().includes(q)).slice(0, 8);
  }

  add() {
    const d = this.addPicked;
    if (!d) return;
    const n = parseNum(this.addAmount);
    this.run(this.api.addExecutionSupplier(this.view.id, { distributorId: d.id, amount: n, currency: this.addCurrency }),
      () => { this.adding = false; this.addPicked = null; this.addQuery = ''; this.addAmount = ''; }, `«${d.name}» добавлен`);
  }

  /** Кнопка «Дальше» по поставщикам: открыть нужную форму у своего поставщика. */
  act(a: DealAction) {
    const s = this.view.execution?.suppliers.find(x => x.distributorId === a.refId) ?? null;
    switch (a.action) {
      case 'EXEC_SUPPLIERS': if (!this.readonly) this.startAdd(); break;
      case 'EXEC_ORDER': if (s) this.openMark(s, 'ORDER'); break;
      case 'EXEC_SUPPLIER_AMOUNT': if (s) this.editAmount(s); break;
      case 'EXEC_PAY_SUPPLIER': if (s) this.openPay(s); break;
      case 'EXEC_SHIP': if (s) this.openMark(s, 'SHIP'); break;
    }
    this.cdr.markForCheck();
  }

  scroll() {
    this.blk?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
```

- [ ] **Step 2: Вставить в раздел**

В `pr-execution.component.ts`:
- импорт и `imports`: `PrExecSuppliersComponent`;
- вместо `<!-- поставщики: задача 11 -->`:

```html
      <app-pr-exec-suppliers #suppliersPart [view]="view" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)" (attach)="attach($event)"></app-pr-exec-suppliers>
```

- поле `@ViewChild('suppliersPart') suppliersPart?: PrExecSuppliersComponent;`
- в `scrollTo` первой строкой: `if (key === 'SUPPLIERS') { this.suppliersPart?.scroll(); return; }`
- в `act` ветка по умолчанию: `default: this.suppliersPart?.act(a); this.suppliersPart?.scroll();`

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build; cd /Users/vlad/IdeaProjects/AIS`
Expected: без ошибок; бюджет стилей компонентов (24 кБ) не превышен.

- [ ] **Step 4: Коммит**

```bash
git add frontend/src/app/pages/private-requests/pr-exec-suppliers.component.ts frontend/src/app/pages/private-requests/pr-execution.component.ts
git commit -m "$(cat <<'EOF'
feat(execution): поставщики в разделе исполнения — заказ, платежи в своей валюте, отгрузка, «Со склада»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---
### Task 12: Полный прогон, живая проверка, ревью ветки, документация, слияние

**Files:**
- Modify: `CLAUDE.md` (§7 модель данных, §8 новый блок, §12 фронт, §13 число тестов, §15 API, §16 бэклог), `docs/PROGRESS.md` («▶ Последняя задача»), `DEPLOY.md` (§8 — строка отката V34), `docs/superpowers/specs/2026-10-19-deal-execution-design.md` (§11 «Решения при реализации», если код разошёлся с текстом)

- [ ] **Step 1: Полный прогон**

Остановить `bootRun` и `devMail`: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`.
Run: `./gradlew cleanTest test` (sandbox off, ≈ 20 мин)
Expected: 0 падений (было 2059 тестов; добавится ~65). Падение — разбираться (`superpowers:systematic-debugging`), а не править ожидания.

- [ ] **Step 2: Стенд**

Записать до проверки: `select max(id) from tender; select max(id) from distributor; select max(id) from client_offer; select max(id) from price_request; select max(id) from lead; select max(id) from chat; select max(id) from inbound_email;` (psql — CLAUDE.md §5).
Поднять (sandbox off, в фоне): `./gradlew devMail`; бэкенд с приёмом info@ и отправкой через GreenMail (CLAUDE.md §5 «Почта info@ → «Обращения» — локально» + `MAIL_HOST=127.0.0.1 MAIL_PORT=3025 MAIL_USERNAME=zakup@westmed.kz MAIL_PASSWORD=secret MAIL_SMTP_AUTH=true`); `cd frontend && npm start`.

- [ ] **Step 3: Сценарий (KZ, 1280 px, светлая тема, admin)**
  1. `python3 scripts/dev-mail.py client zz.clinic@example.kz` → в «Обращениях» новое → «Создать заявку».
  2. Две позиции; «Запросить цены…» двум поставщикам, оба «Передам сам»; в таблице цен вписать цены (один — тот, кого потом переведём в рубли).
  3. «Создать КП клиенту» → «Отправлено» → в «Дальше» «Согласился».
  4. Ожидание: строка этапов — десять шагов, «● Договор», «Дальше: Клиент согласился на КП № N — договор или счёт» [Договор или счёт…]; раздел «Исполнение сделки» после «КП клиенту»: «по КП № N · <итог>», два поставщика «к оплате … (по КП)»; в списке заявок чип «Договор».
  5. «Договор или счёт…» → № «42/2026», дата сегодня, предоплата 50 → индикатор «Сохранено · ЧЧ:ММ», «Дальше: Ждём предоплату 50 %: оплачено 0 из …» [+ Платёж клиента]; чип «Ждём предоплату».
  6. Ещё раз `python3 scripts/dev-mail.py client zz.clinic@example.kz` (письмо клиента с PDF) → «📎 Приложить» у договора → «Из переписки»: файл с пометкой «клиент» → «Приложить» → файл у договора, строка в ленте «📎 «…» — к договору».
  7. «+ Платёж клиента» → сумма подставлена (остаток предоплаты) → «Внести платёж» → «Дальше: Закажите у … (и ещё у 1)»; чип «Заказ у поставщиков».
  8. Отметить заказ у обоих; у второго «⋯ → Сумма и валюта…» → руб., 277 025 → «Дальше: Оплатите …»; платежи обоим; отгрузка обоих → «Дальше: Поставьте клиенту».
  9. «Отметить поставку…» с заметкой; «Документы подписаны…» с файлом с компьютера → «Дальше: Ждём оплату: оплачено … из …» → остаток → «Дальше: Всё сделано — закройте сделку» [Закрыть: купили у нас] → закрыть → Итог «✓ купили у нас».
  10. Лента: договор, платежи, заказы, оплаты поставщикам (руб.), отгрузки, поставка, документы, файлы; клик по строке исполнения прокручивает к своему блоку.
  11. Вторая сделка (шаги 1–3 заново): предоплата «0» → после договора «Дальше» — сразу «Закажите у …», оплата — в конце; «Без договора» → шаг «Договор» — «–», «Дальше» — дальше по списку; «Со склада» при отметке заказа у поставщика → тост «Сначала уберите отметки, платежи и файлы поставщиков»; «Вернуть в работу» у закрытой первой сделки → данные исполнения на месте.
  12. 390 px: блоки карточками, отметки столбиком, цели ≥ 40 px, окно файла во весь экран, горизонтальной прокрутки нет.
  13. Тёмная тема: тинты, контраст подписей.
  14. Оператор (`operator`): раздел виден, кнопок записи нет, файл скачивается; в DevTools — 0 запросов записи.

- [ ] **Step 4: Уборка**

Остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), потом SQL (только dev; `X` — записанные максимумы):

```sql
delete from client_offer where tender_id > <max_tender>;
delete from price_request where tender_id > <max_tender>;
delete from lead where id > <max_lead>;
delete from tender where id > <max_tender>;          -- deal_* каскадом
delete from distributor where id > <max_distributor>;
delete from chat where channel = 'EMAIL' and account = 'info@westmed.kz' and id > <max_chat>;
delete from inbound_email where mailbox = 'info@westmed.kz';
delete from mail_cursor where mailbox = 'info@westmed.kz';
```

Остальное — по LISTEN-портам: 3143 (devMail), 4200.

- [ ] **Step 5: Ревью ветки — самому (CLAUDE.md §3)**

`git diff main...feature/deal-execution` целиком свежим взглядом: гарды (`findById` у поставщика — с рынком), ввод до записи, `@Transactional` на записи, тексты без длинного тире в документах (их здесь нет), классы фронта не из UI-kit, объекты-входы — литералы, `[selected]` у `<option>`. Найденное — исправить тем же порядком (тест красный до правки), мелочи — в CLAUDE.md §16.

- [ ] **Step 6: Документация**
  - `CLAUDE.md` §7 — `deal_execution`, `deal_supplier`, `deal_payment`, `deal_file` (V34) и правила; §8 — блок «Исполнение сделки (подпроект 3, …)» с механикой, проверкой и ревью; §12 — фронт раздела; §13 — число тестов полного прогона; §15 — пути §9 спеки; §16 — «Жизненный цикл сделки»: кусок сделан, что вне рамок (спека §13), мелочи ревью; V34 в списке миграций §16 («следующая свободная — V35»).
  - `DEPLOY.md` §8 — «Откат кода раньше V34 — безопасно: прежний код таблиц исполнения не читает, данные остаются».
  - `docs/PROGRESS.md` — «▶ Последняя задача»: что сделано, проверено, не запушено, следующий шаг.
  - Спека §11 «Решения при реализации» — если код разошёлся с текстом.

- [ ] **Step 7: Коммит документации и слияние**

```bash
git add CLAUDE.md DEPLOY.md docs/PROGRESS.md docs/superpowers/specs/2026-10-19-deal-execution-design.md
git commit -m "$(cat <<'EOF'
docs(execution): CLAUDE.md §7/§8/§12/§13/§15/§16, PROGRESS, откат V34 в DEPLOY

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
git checkout main && git merge --ff-only feature/deal-execution && git branch -d feature/deal-execution
```

Push — оператор: `! git push origin main` (CLAUDE.md §5). После деплоя (с согласия оператора, по SSH): V34 в `flyway_schema_history`, лог бэкенда без ERROR.
