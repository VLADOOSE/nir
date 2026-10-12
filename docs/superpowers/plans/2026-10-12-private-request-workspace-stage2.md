# Частная заявка — рабочее место сделки, этап 2 — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Запрос цен поставщику становится полноценным деловым запросом. К письму можно приложить Word на фирменном бланке, с подписью и печатью или без. Доверенным поставщикам по галочке указывается клиент. Почта поставщика вписывается прямо в окне; там же заводится новый поставщик. Пять производителей брендов westmed.kz получают запрос по умолчанию. «↻ Переслать» повторяет письмо со всеми настройками. Опции бланка, печати и «ответить до» появляются и в панели КП тендера.

**Architecture:** Миграция V27 добавляет настройки запроса (`price_request`), отметки «Доверенный» и «Производитель» (`distributor`) и тексты запроса на бланке (`email_template`). V28 заводит пять производителей. Документ запроса собирает новый `RfqDocumentBuilder`: он даёт модель `KpDocument` и отдаёт её существующему `KpDocxRenderer`, переиспользуя бланк, подбор колонок и подпись КП. Единый канал `PriceRequestSendService` получает расширенные `SendOptions` (вложение, печать, клиент, адрес). Канал хранит настройки в запросе, прикладывает документ и шлёт письмо на один или несколько адресов. Окно заявки (`PrivateRequestRfqService`) сперва проверяет весь запрос, затем заводит новых поставщиков и пишет почту в пустые карточки, и только потом шлёт письма.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/Hibernate 6, Flyway, PostgreSQL 17, Apache POI XWPF (через `KpDocxRenderer`), Jakarta Mail + Spring `MimeMessageHelper`; JUnit 5 + AssertJ + GreenMail; Angular 21 (standalone, инлайн-шаблоны).

**Spec:** `docs/superpowers/specs/2026-10-12-private-request-workspace-design.md` — этап 2 из §13 (§3.2, §3.3, §6.1–§6.6, §7 — шапка «👤», §9, §10, §12). Этап 3 (КП клиенту из заявки) — отдельным планом.

## Global Constraints

- **Ветка** `feature/private-request-workspace-stage2` от `main` (план и решение «подсказка РУ без бренда — как есть» — первым коммитом). Леджер — `.superpowers/sdd/2026-10-12-private-request-workspace-stage2/progress.md`.
- **Каждый коммит** заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- **Миграции этапа — только `V27__private_request_rfq_options.sql` и `V28__westmed_manufacturers.sql`.** V1–V26 не трогать (CLAUDE.md §10).
- **Перед V28** — сверка справочника поставщиков прода, только чтение по SSH и только с явного согласия оператора (спека §13). Совпадение по точному названию на рынке РФ — стоп и решение оператора.
- **Гард рынка.** Сущность по id (`findById` = `em.find`) проверяется явно: рынок текущий, иначе 404 (CLAUDE.md §14).
- **Записи API** — `@PreAuthorize("hasRole('ADMIN')")`, чтение — любой вошедший.
- **Тесты** — `@SpringBootTest @Transactional` на nirdb; SMTP — только под `@SmtpIntegrationTest` (GreenMail 127.0.0.1:3025). Новых особых тестовых контекстов не заводить (`@TestPropertySource`, `@MockitoBean`, `@AutoConfigureMockMvc` — каждый +10 соединений к nirdb). Контроллеры в тестах зовутся методами под `@WithMockUser(roles = "ADMIN")`, как `EmailTemplateEndpointTest`.
- **Gradle и psql** — с `dangerouslyDisableSandbox: true`. Перед прогоном остановить `bootRun` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`) и `devMail` (`lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`). Полный гейт — `./gradlew cleanTest test`.
- **Против утечки** (спека §10): ни в письме, ни в документе нет номера заявки (ЧЗ-…), номера и заказчика тендера; клиент — только у запроса с отметкой «Указать клиента». Контактное лицо и телефон заявки поставщику не уходят никогда.
- **Адрес почты — персональные данные:** в лог только домен (`EmailAddresses.domains`), тексты исключений SMTP — только классом.
- **Фронт:**
  - кнопки — kit (`.btn` + роль), цвета — токены (`var(--…)`), обе темы;
  - брейкпоинт 900 px, на телефоне цели 40 px, поля 16 px; `@media` — в конце `styles`;
  - без нативного `await` — промисы через `.then`;
  - файлы с `/api` — только `HttpClient` blob (CLAUDE.md §14);
  - типы в `ApiService` — через `import type`;
  - гейт — `cd frontend && npm run build`.
- **Word проверяется в настоящем Word**, не в Quick Look (CLAUDE.md §14).
- **Тексты экрана** — по-русски, как в спеке.

## Review Focus

Пять ситуаций, которые спека подразумевает, но тесты задач легко обойдут. У каждой — свой тест в задаче, где живёт код:

1. **В карточке поставщика старый кривой адрес** («info@x.kz; sales» или опечатка без домена). Панель КП тендера и «↻ Переслать» не должны падать 500: запрос сохраняется, ячейка — «письмо не ушло». В окне заявки этот адрес виден в поле почты, и отправка отвечает 400 с самим адресом — его исправляют там же. Сохранение такой карточки — 400 с адресом в тексте. Тесты: задача 6 `PriceRequestSendOptionsTest.malformedCardEmailIsFailedNotCrash`, задача 7 `PrivateRequestRfqStage2Test.invalidTypedAddressIs400BeforeAnyLetterOrRequest`, задача 3 `DistributorFlagsTest.invalidEmailIs400WithTheAddress`.
2. **Шаблон письма, сохранённый оператором до этапа 2,** без `{{заказчик}}` и `{{вложение}}`. Клиент у доверенного и строка о вложении не должны молча пропасть. Без клиента и вложения шаблон печатается как раньше. Тесты: задача 4 `KpEmailComposerRfqTest.customTemplateWithoutNewPlaceholdersGetsLinesAfterPositions`, `customTemplateUnchangedWhenNothingToAdd`.
3. **«Подпись и печать» без загруженной печати.** Отправка отвечает 400 до первой записи. «↻ Переслать» после удаления печати шлёт документ без печати, а не падает. Тесты: задача 6 `stampWithoutLoadedStampIs400BeforeAnythingIsSaved`, `resendAfterStampDeletedSendsDocumentWithoutStamp`.
4. **Огромные характеристики** (ТЗ СК-Фармации на 10–30 тыс. символов) в документе. Режутся так же, как в письме, документ собирается, а текст клиента и заказчика тендера в него не утекает. Тест: задача 5 `RfqDocumentServiceTest.longSpecIsTrimmedLikeTheLetter`.
5. **Новый поставщик с занятым названием** (`distributor.name` уникален по всем рынкам). Название занято на другом рынке — 400 с подсказкой. Занято на этом рынке (в любом регистре) — берётся существующий, дубля нет. Тест: задача 7 `PrivateRequestRfqStage2Test.newSupplierNameOnOtherMarketIs400_sameMarketNameIsReused`.

## Решения при планировании (уточнения спеки)

Решения ниже приняты при планировании; при ревью их сверять с кодом, а не с текстом спеки.

1. **«Клиент указан» — это учреждение или название клиента** (`facility.name` или `customer_name`). Контактное лицо и телефон клиентом не считаются: это персональные данные, «закрепить проект за» по ним нельзя. Нет ни учреждения, ни названия — «Указать клиента» недоступна, сервер отвечает 400. `{{клиент}}` в строке о заказчике — это же название (`RfqClientLine.clientName`), а не `clientLabel` страницы.
2. **`sent_to` — адрес последней попытки** (при сбое SMTP тоже), а `letter_status` говорит, принят ли он. Без этого «Переслать» вписанной почты после сбоя не знал бы, куда слать. На экране `sent_to` не показывается.
3. **Производитель без почты не забирает отметку у дилеров.** Дилеры бренда остаются без отметки, только когда у бренда есть производитель с почтой. Иначе позиция осталась бы вовсе без отмеченных поставщиков.
4. **Колонки документа.** Есть характеристики — им отдаётся ключ `NAME` (остаток ширины, не меньше четверти), а наименование уходит в текстовую колонку 15 % (ключ `MANUFACTURER`). Ключ решает класс подбора ширины в `KpDocumentBuilder.fitTable`, подпись — что в колонке. Иначе длинное ТЗ сжалось бы в колонку «Примечание» 13 %.
5. **Количество без значения** в документе — «уточняется» (в письме — «кол-во уточняется»).
6. **Контактов под подписью в документе нет** («моб: …, e-mail: …» из профиля): строка ответа уже называет ящик приёма `zakup@`, а призыв писать на другой адрес уводил бы ответ мимо приёма и Telegram. Телефон и почта в реквизитах бланка остаются — это бланк.
7. **Дата документа** — дата создания запроса по поясу рынка: «Переслать» повторяет тот же «Исх. № 534 от 12.10.2026». До отправки — сегодня.
8. **Имя файла** — «Запрос КП № 534 от 12.10.2026 — West-Med.docx». Компания — надпись бренда профиля без кавычек (у KZ в профиле `"West-Med"`), иначе краткое название.
9. **Поле почты в окне.** Пустое — запрос без письма, даже если в карточке почта есть: оператор сознательно очистил поле. `email` не прислан (`null`, старые клиенты API) — почта карточки, как на этапе 1. У нового поставщика почта — то же поле `email` строки поставщика (спека §9 пишет её ещё и внутрь `newSupplier`; одно поле — одна проверка).
10. **Виды МИ производителей** (V28) заводятся по их продукции из исследования. Спека о видах молчит, но без них позиции без бренда («Облучатель бактерицидный») никогда не дошли бы до ЭМА и Орион-Си даже подсказкой «по виду».
11. **Тексты запроса, совпадающие с зашитыми, не хранятся** (`RfqTexts.custom` → `NULL`): тогда поправка зашитого текста дойдёт и до этого рынка. Поле, не присланное в `PUT` (`null`), не меняется.
12. **Бренды производителей — короткие формы без «Orion» и «EMA» латиницей.** «Orion» поймал бы Orion Diagnostica (реагенты), «EMA» — любое слово с этими буквами. Латиница — только «FOTEK» и «Axion»: эти производители сами ею пользуются.
13. **Пункт про регистрацию в «просим указать» — по рынку.** Спека даёт список для KZ («номер РУ РК и сертификат соответствия, СТ-KZ / СТ-1 — при наличии»); у РФ — «Номер регистрационного удостоверения Росздравнадзора». Пункты — с заглавной: в документе они нумерованы.
14. **«Клиент указан» — уже, чем «как клиент показывается» (§5.1 спеки).** Спека для `{{клиент}}` ссылается на правило показа клиента, где после учреждения и названия идут контактное лицо и телефон; здесь — только учреждение и название (решение 1). Если оператор захочет указывать поставщику и контактное лицо — это одна строка в `RfqClientLine.clientName`.

## Карта файлов

**Бэкенд — создать:**
- `src/main/resources/db/migration/V27__private_request_rfq_options.sql`, `V28__westmed_manufacturers.sql`
- `util/EmailAddresses.java`
- `service/RfqTexts.java`, `service/RfqClientLine.java`, `service/RfqDocumentService.java`
- `service/document/RfqDocumentBuilder.java`
- тесты: `privaterequest/PrivateRequestStage2SchemaTest`, `privaterequest/PrivateRequestRfqStage2Test`; `util/EmailAddressesTest`; `service/EmailServiceTest`, `service/KpEmailComposerRfqTest`, `service/PriceRequestSendOptionsTest`, `service/RfqDocumentServiceTest`; `clientoffer/RfqDocumentBuilderTest`; `sourcing/DistributorFlagsTest`, `sourcing/ManufacturersSeedTest`

**Бэкенд — изменить:**
- сущности: `entity/PriceRequest.java`, `Distributor.java`, `EmailTemplate.java`
- `repository/DistributorRepository.java`
- сервисы: `EmailService.java`, `KpEmailComposer.java`, `EmailTemplateService.java`, `PriceRequestSendService.java`, `PriceRequestWriter.java`, `PrivateRequestSourcingService.java`, `privaterequest/PrivateRequestRfqService.java`, `privaterequest/PrivateRequestPricesService.java`, `document/KpDocumentBuilder.java`
- контроллеры: `DistributorController.java`, `EmailTemplateController.java`, `PriceRequestController.java`, `PrivateRequestController.java`
- DTO: `dto/request/DistributorRequest.java`, `EmailTemplateRequest.java`, `PriceRequestSendRequest.java`, `RfqSendRequest.java`, `RfqPreviewRequest.java`; `dto/response/DistributorResponse.java`, `EmailTemplateResponse.java`, `PrivateRequestSourcingResponse.java`, `PrivateRequestPricesResponse.java`
- тесты: `email/EmailTemplateEndpointTest`, `sourcing/PrivateRequestSourcingServiceTest`, `privaterequest/PrivateRequestPricesTest`

**Фронт — создать:** `shared/rfq-options.ts`

**Фронт — изменить:** `shared/private-request.ts`, `services/api.service.ts`, `pages/private-requests/pr-rfq-dialog.component.ts` (переписать), `pr-supply.component.ts`, `pr-prices.component.ts`, `pages/distributors/distributors.component.ts`, `pages/email-template/email-template.component.ts`, `pages/tenders/lot-kp-panel.component.ts`

**Документация:** `CLAUDE.md` (§7, §8, §9, §12, §13, §15, §16), `docs/PROGRESS.md`, память `last-task-pointer.md`.

---

### Task 1: Схема V27 и поля сущностей

**Files:**
- Create: `src/main/resources/db/migration/V27__private_request_rfq_options.sql`
- Modify: `src/main/java/com/vladoose/nir/entity/PriceRequest.java`
- Modify: `src/main/java/com/vladoose/nir/entity/Distributor.java`
- Modify: `src/main/java/com/vladoose/nir/entity/EmailTemplate.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage2SchemaTest.java`

**Interfaces:**
- Produces:
  - `PriceRequest`: `boolean discloseClient`, `withDocument`, `withStamp`, `emailOverride` (геттеры `isX()`, билдер `.x(true)`);
  - `Distributor`: `boolean trusted`, `manufacturer`;
  - `EmailTemplate`: `String rfqTitle`, `rfqIntro`, `rfqAsk`, `rfqClientLine`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage2SchemaTest.java`:

```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.EmailTemplateRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/** V27: настройки запроса цен, отметки поставщика и тексты запроса на бланке читаются обратно; умолчания базы — «нет». */
@SpringBootTest
@Transactional
class PrivateRequestStage2SchemaTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired EmailTemplateRepository templateRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void newColumnsRoundTrip() {
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ V27 " + System.nanoTime())
                .trusted(true).manufacturer(true).build());
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-V27-1").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT")
                .discloseClient(true).withDocument(true).withStamp(true).emailOverride(true).sentTo("x@zz.kz").build());
        templateRepository.deleteAllInBatch();   // строка рынка одна (UNIQUE market): в тесте — своя, откатится
        templateRepository.save(EmailTemplate.builder().market(Market.KZ).subjectTemplate("Т").bodyTemplate("Б")
                .rfqTitle("ЗАПРОС").rfqIntro("Вводная").rfqAsk("Модель\nЦена").rfqClientLine("Для: {{клиент}}").build());
        em.flush();
        em.clear();

        Distributor dBack = distributorRepository.findById(d.getId()).orElseThrow();
        assertThat(dBack.isTrusted()).isTrue();
        assertThat(dBack.isManufacturer()).isTrue();
        PriceRequest prBack = priceRequestRepository.findById(pr.getId()).orElseThrow();
        assertThat(prBack.isDiscloseClient()).isTrue();
        assertThat(prBack.isWithDocument()).isTrue();
        assertThat(prBack.isWithStamp()).isTrue();
        assertThat(prBack.isEmailOverride()).isTrue();
        EmailTemplate tBack = templateRepository.findByMarket(Market.KZ).orElseThrow();
        assertThat(tBack.getRfqTitle()).isEqualTo("ЗАПРОС");
        assertThat(tBack.getRfqIntro()).isEqualTo("Вводная");
        assertThat(tBack.getRfqAsk()).isEqualTo("Модель\nЦена");
        assertThat(tBack.getRfqClientLine()).isEqualTo("Для: {{клиент}}");
    }

    @Test
    void databaseDefaultsAreFalse() {
        String name = "ZZ V27 по умолчанию " + System.nanoTime();
        jdbc.update("INSERT INTO distributor (name, market) VALUES (?, 'KZ')", name);
        assertThat(jdbc.queryForObject("SELECT trusted OR manufacturer FROM distributor WHERE name = ?", Boolean.class, name))
                .isFalse();
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-V27-2").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        em.flush();
        Long distId = jdbc.queryForObject("SELECT id FROM distributor WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO price_request (tender_id, distributor_id, status, market) VALUES (?, ?, 'SENT', 'KZ')",
                t.getId(), distId);
        assertThat(jdbc.queryForObject("SELECT disclose_client OR with_document OR with_stamp OR email_override "
                + "FROM price_request WHERE tender_id = ?", Boolean.class, t.getId())).isFalse();
    }
}
```

- [ ] **Step 2: Запустить — тест не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestStage2SchemaTest'`
Expected: FAIL — `compileTestJava`: нет методов `trusted(…)`, `discloseClient(…)`, `rfqTitle(…)`.

- [ ] **Step 3: Миграция V27**

`src/main/resources/db/migration/V27__private_request_rfq_options.sql`:

```sql
-- V27: частная заявка, этап 2 (спека 2026-10-12 §3.2) — запрос на бланке, клиент в запросе, вписанная почта,
-- «Доверенный» и «Производитель» у поставщика, тексты запроса на бланке в шаблоне письма рынка.

-- запрос цен помнит свои настройки — «↻ Переслать» повторяет письмо в точности (§6.5)
ALTER TABLE price_request ADD COLUMN disclose_client BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_request ADD COLUMN with_document   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_request ADD COLUMN with_stamp      BOOLEAN NOT NULL DEFAULT FALSE;
-- письмо ушло на адрес, вписанный в окне запроса (он — в sent_to), а не на почту из карточки
ALTER TABLE price_request ADD COLUMN email_override  BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE distributor ADD COLUMN trusted      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE distributor ADD COLUMN manufacturer BOOLEAN NOT NULL DEFAULT FALSE;

-- пусто — зашитый текст (RfqTexts), как у темы и тела письма
ALTER TABLE email_template ADD COLUMN rfq_title       TEXT;
ALTER TABLE email_template ADD COLUMN rfq_intro       TEXT;
ALTER TABLE email_template ADD COLUMN rfq_ask         TEXT;
ALTER TABLE email_template ADD COLUMN rfq_client_line TEXT;
```

- [ ] **Step 4: Поля сущностей**

В `PriceRequest.java` заменить javadoc поля `sentTo` и добавить четыре поля после `letterStatus`:

```java
    /**
     * Адрес последней отправки письма (несколько — через запятую); принят ли он — letterStatus. При сбое SMTP адрес
     * остаётся: «↻ Переслать» вписанной почты знает, куда слать.
     */
    @Column(name = "sent_to")
    private String sentTo;
```

```java
    /** Клиент заявки указан поставщику (галочка «Указать клиента», спека 2026-10-12 §6.2). */
    @Column(name = "disclose_client", nullable = false)
    private boolean discloseClient;

    /** К письму приложен запрос на бланке (Word). */
    @Column(name = "with_document", nullable = false)
    private boolean withDocument;

    /** В документе — подпись и печать. */
    @Column(name = "with_stamp", nullable = false)
    private boolean withStamp;

    /** Письмо ушло не на почту из карточки, а на вписанную в окне запроса (она — в sentTo). */
    @Column(name = "email_override", nullable = false)
    private boolean emailOverride;
```

В `Distributor.java` после поля `website`:

```java
    /** «Доверенный»: в запросах цен клиент заявки указывается по умолчанию (спека 2026-10-12 §6.2). */
    @Column(nullable = false)
    private boolean trusted;

    /** «Производитель»: по его бренду запрос цен уходит ему по умолчанию, дистрибьюторы бренда — без отметки (§6.1). */
    @Column(nullable = false)
    private boolean manufacturer;
```

В `EmailTemplate.java` после `bodyTemplate`:

```java
    /** Запрос на бланке (спека 2026-10-12 §6.4); пусто — зашитый текст RfqTexts. */
    @Column(name = "rfq_title", columnDefinition = "TEXT")
    private String rfqTitle;

    @Column(name = "rfq_intro", columnDefinition = "TEXT")
    private String rfqIntro;

    /** Пункты «В предложении просим указать», по одному на строку. */
    @Column(name = "rfq_ask", columnDefinition = "TEXT")
    private String rfqAsk;

    /** Строка о конечном заказчике — общая для письма ({{заказчик}}) и документа. */
    @Column(name = "rfq_client_line", columnDefinition = "TEXT")
    private String rfqClientLine;
```

- [ ] **Step 5: Запустить тест**

Run: `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestStage2SchemaTest'`
Expected: PASS (2 теста); в логе Flyway — «Migrating schema "public" to version "27 - private request rfq options"».

- [ ] **Step 6: Коммит**

```bash
git add src/main/resources/db/migration/V27__private_request_rfq_options.sql src/main/java/com/vladoose/nir/entity/PriceRequest.java src/main/java/com/vladoose/nir/entity/Distributor.java src/main/java/com/vladoose/nir/entity/EmailTemplate.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage2SchemaTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): V27 — настройки запроса цен, «Доверенный» и «Производитель», тексты запроса на бланке

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Адреса почты и вложения письма

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/EmailAddresses.java`
- Modify: `src/main/java/com/vladoose/nir/service/EmailService.java`
- Test: `src/test/java/com/vladoose/nir/util/EmailAddressesTest.java`
- Test: `src/test/java/com/vladoose/nir/service/EmailServiceTest.java`

**Interfaces:**
- Produces:
  - `EmailAddresses.parse(String raw) → List<String>` (400 `BadRequestException` «Неверный адрес почты: «…»»), `normalize(String raw) → String` («a@x.kz, b@y.kz», пусто — `""`, длиннее 255 — 400), `same(String a, String b) → boolean` (без проверки формата), `domains(List<String>) → String`;
  - `EmailService.Attachment(String fileName, byte[] bytes, String contentType)`, `EmailService.sendEmail(List<String> to, String subject, String body, List<Attachment> attachments)`; прежний `sendEmail(String, String, String)` остаётся.

- [ ] **Step 1: Падающие тесты адресов**

`src/test/java/com/vladoose/nir/util/EmailAddressesTest.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailAddressesTest {

    @Test
    void splitsByCommaSemicolonAndSpaces_dropsRepeatsIgnoringCase() {
        assertThat(EmailAddresses.parse(" sales7@ema.su; sales9@ema.su ,SALES7@ema.su  manager5@c.axion.ru "))
                .containsExactly("sales7@ema.su", "sales9@ema.su", "manager5@c.axion.ru");
    }

    @Test
    void blankIsEmpty() {
        assertThat(EmailAddresses.parse("  ")).isEmpty();
        assertThat(EmailAddresses.parse(null)).isEmpty();
        assertThat(EmailAddresses.normalize(null)).isEmpty();
    }

    @Test
    void invalidAddressIs400WithTheAddress() {
        assertThatThrownBy(() -> EmailAddresses.parse("ok@a.kz, broken@kz"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("«broken@kz»");
        assertThatThrownBy(() -> EmailAddresses.parse("Иван <ivan@a.kz>")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> EmailAddresses.parse("a@@b.kz")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void normalizeJoinsWithCommaSpace() {
        assertThat(EmailAddresses.normalize("a@x.kz;b@y.kz")).isEqualTo("a@x.kz, b@y.kz");
    }

    @Test
    void listLongerThanTheColumnIs400() {
        String many = IntStream.range(0, 20).mapToObj(i -> "manager" + i + "@company-long-domain.kz")
                .collect(Collectors.joining(","));
        assertThatThrownBy(() -> EmailAddresses.normalize(many))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("255");
    }

    @Test
    void sameIgnoresCaseAndSeparatorsAndNeverThrows() {
        assertThat(EmailAddresses.same("a@x.kz, b@y.kz", "A@X.kz;b@y.kz")).isTrue();
        assertThat(EmailAddresses.same("a@x.kz", "b@y.kz")).isFalse();
        assertThat(EmailAddresses.same("кривой адрес", "кривой адрес")).isTrue();
        assertThat(EmailAddresses.same("a@x.kz", null)).isFalse();
    }

    @Test
    void domainsOnlyForLogs() {
        assertThat(EmailAddresses.domains(List.of("a@x.kz", "b@x.kz", "c@y.kz"))).isEqualTo("@x.kz, @y.kz");
    }
}
```

- [ ] **Step 2: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.util.EmailAddressesTest'`
Expected: FAIL — `compileTestJava`: нет класса `EmailAddresses`.

- [ ] **Step 3: `EmailAddresses`**

`src/main/java/com/vladoose/nir/util/EmailAddresses.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.exception.BadRequestException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Один или несколько адресов почты в одном поле — через запятую, точку с запятой или пробел (спека 2026-10-12 §6.2):
 * у ЭМА светильники и облучатели ведут разные отделы. Каждый адрес проверяется; неверный — 400 с самим адресом в тексте.
 */
public final class EmailAddresses {

    /** Колонки distributor.email и price_request.sent_to — VARCHAR(255). */
    static final int MAX_FIELD = 255;

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
    /** Простая форма «local@domain.tld»: имя с угловыми скобками и группы адресов в поле не нужны. */
    private static final Pattern SIMPLE =
            Pattern.compile("^[^@\\s<>()\\[\\],;:\"]+@[^@\\s<>()\\[\\],;:\"]+\\.[^@\\s<>()\\[\\],;:\".]{2,}$");

    private EmailAddresses() {}

    /** Адреса поля по порядку, без повторов (без учёта регистра); пусто — пустой список. */
    public static List<String> parse(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String part : split(raw)) {
            if (!valid(part)) throw new BadRequestException("Неверный адрес почты: «" + part + "»");
            out.putIfAbsent(part.toLowerCase(Locale.ROOT), part);
        }
        return List.copyOf(out.values());
    }

    /** Поле в каноническом виде «a@x.kz, b@y.kz»; пусто — пустая строка (так форма карточки очищает почту). */
    public static String normalize(String raw) {
        String joined = String.join(", ", parse(raw));
        if (joined.length() > MAX_FIELD) {
            throw new BadRequestException("Слишком много адресов: в поле почты помещается " + MAX_FIELD + " символов");
        }
        return joined;
    }

    /** Те же адреса без учёта регистра и разделителей. Формат не проверяется: в карточке бывают старые кривые адреса. */
    public static boolean same(String a, String b) {
        return lowered(a).equals(lowered(b));
    }

    /** Для лога — только домены: адреса — персональные данные (спека §10). */
    public static String domains(List<String> addresses) {
        return addresses.stream().map(a -> a.substring(Math.max(0, a.indexOf('@')))).distinct()
                .reduce((x, y) -> x + ", " + y).orElse("—");
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : SEPARATORS.split(raw.trim())) if (!part.isEmpty()) out.add(part);
        return out;
    }

    private static List<String> lowered(String raw) {
        return split(raw).stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static boolean valid(String address) {
        if (address.length() > MAX_FIELD || !SIMPLE.matcher(address).matches()) return false;
        try {
            new InternetAddress(address, true).validate();
            return true;
        } catch (AddressException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Запустить тест адресов**

Run: `./gradlew test --tests 'com.vladoose.nir.util.EmailAddressesTest'`
Expected: PASS (7 тестов).

- [ ] **Step 5: Падающий тест вложений**

`src/test/java/com/vladoose/nir/service/EmailServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Письмо на несколько адресов и вложение с кириллическим именем — сквозь настоящий SMTP (GreenMail). */
@SmtpIntegrationTest
class EmailServiceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired EmailService emailService;

    /** Текст письма — в любой глубине multipart (MimeMessageHelper кладёт его в related внутри mixed). */
    static String text(Part p) throws Exception {
        if (p.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return (String) p.getContent();
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = text(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    static Part attachment(Part p) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return p;
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                Part a = attachment(mp.getBodyPart(i));
                if (a != null) return a;
            }
        }
        return null;
    }

    @Test
    void sendsToAllRecipientsWithCyrillicAttachmentName() throws Exception {
        byte[] doc = {1, 2, 3, 4};
        String name = "Запрос КП № 1 от 12.10.2026 — West-Med.docx";

        emailService.sendEmail(List.of("a@ema.test", "b@ema.test"), "[КП-1] Тема", "Текст письма",
                List.of(new EmailService.Attachment(name, doc, DOCX)));

        MimeMessage[] mails = greenMail.getReceivedMessages();
        assertThat(mails).hasSize(2);   // GreenMail кладёт копию каждому адресату
        MimeMessage m = mails[0];
        assertThat(Arrays.stream(m.getRecipients(Message.RecipientType.TO)).map(Address::toString))
                .containsExactly("a@ema.test", "b@ema.test");
        assertThat(text(m)).isEqualTo("Текст письма");
        Part a = attachment(m);
        assertThat(a).isNotNull();
        assertThat(MimeUtility.decodeText(a.getFileName())).isEqualTo(name);
        assertThat(a.getContentType()).startsWith(DOCX);
        assertThat(a.getInputStream().readAllBytes()).isEqualTo(doc);
    }

    @Test
    void withoutAttachmentsTheLetterIsPlainText() throws Exception {
        emailService.sendEmail("one@ema.test", "Тема", "Просто текст");
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getContent()).isEqualTo("Просто текст");
    }
}
```

- [ ] **Step 6: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.service.EmailServiceTest'`
Expected: FAIL — `compileTestJava`: нет `EmailService.Attachment` и `sendEmail(List, …)`.

- [ ] **Step 7: `EmailService` с адресатами и вложениями**

`src/main/java/com/vladoose/nir/service/EmailService.java` — заменить целиком:

```java
package com.vladoose.nir.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Отправка письма КП: MimeMessage (UTF-8) с Reply-To на ящик ответов (по умолчанию — адрес отправки). Один или
 * несколько адресатов («Кому»), по отметке — вложения (запрос на бланке, спека 2026-10-12 §6.4).
 */
@Service
public class EmailService {

    /** Вложение письма: имя файла (кириллица кодируется), содержимое, тип. */
    public record Attachment(String fileName, byte[] bytes, String contentType) {}

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${mail.kp.reply-to:}")
    private String replyTo;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public boolean isConfigured() {
        return fromAddress != null && !fromAddress.isBlank();
    }

    public void sendEmail(String to, String subject, String body) {
        sendEmail(List.of(to), subject, body, List.of());
    }

    /**
     * Письмо на все адреса (в «Кому»), текст — plain UTF-8, вложения — multipart. Имена вложений кодируются
     * (setEncodeFilenames): в Spring 6.2 по умолчанию выключено, и «Запрос КП № 534 от 12.10.2026 — West-Med.docx» в части
     * почтовых программ пришёл бы нечитаемым.
     */
    public void sendEmail(List<String> to, String subject, String body, List<Attachment> attachments) {
        boolean multipart = attachments != null && !attachments.isEmpty();
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, "UTF-8");
            helper.setEncodeFilenames(true);
            helper.setFrom(fromAddress);
            helper.setTo(to.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(body, false); // plain-text
            String rt = (replyTo != null && !replyTo.isBlank()) ? replyTo : fromAddress;
            if (rt != null && !rt.isBlank()) helper.setReplyTo(rt);
            if (multipart) {
                for (Attachment a : attachments) {
                    helper.addAttachment(a.fileName(), new ByteArrayResource(a.bytes()), a.contentType());
                }
            }
            mailSender.send(mime);
        } catch (jakarta.mail.MessagingException e) {
            // текст исключения JavaMail может нести адрес — наружу только класс (спека §10)
            throw new RuntimeException("Не удалось собрать письмо: " + e.getClass().getSimpleName(), e);
        }
    }
}
```

- [ ] **Step 8: Запустить тесты задачи**

Run: `./gradlew test --tests 'com.vladoose.nir.util.EmailAddressesTest' --tests 'com.vladoose.nir.service.EmailServiceTest' --tests 'com.vladoose.nir.service.PriceRequestSendServiceTest'`
Expected: PASS (7 + 2 + прежние тесты отправки КП — через старый `sendEmail(String,…)`).

- [ ] **Step 9: Мутация — выключить кодирование имени**

Скопировать `EmailService.java` (`cp … /tmp/EmailService.java.bak`), удалить строку `helper.setEncodeFilenames(true);`, прогнать `EmailServiceTest`.
Expected: FAIL `sendsToAllRecipientsWithCyrillicAttachmentName` (имя не равно ожидаемому) — или PASS, если JavaMail сам кодирует по RFC 2231. Во втором случае записать в леджер `Task 2: Ruling: setEncodeFilenames оставлен — RFC 2047 понятнее старым почтовым программам; тест его не различает`. Вернуть файл копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 10: Коммит**

```bash
git add src/main/java/com/vladoose/nir/util/EmailAddresses.java src/main/java/com/vladoose/nir/service/EmailService.java src/test/java/com/vladoose/nir/util/EmailAddressesTest.java src/test/java/com/vladoose/nir/service/EmailServiceTest.java
git commit -m "$(cat <<'EOF'
feat(mail): несколько адресов в поле почты и вложения письма

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: «Доверенный» и «Производитель» в карточке поставщика

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/DistributorController.java`
- Modify: `frontend/src/app/pages/distributors/distributors.component.ts`
- Test: `src/test/java/com/vladoose/nir/sourcing/DistributorFlagsTest.java`

**Interfaces:**
- Consumes: `EmailAddresses.normalize` (задача 2), поля `Distributor.trusted/manufacturer` (задача 1).
- Produces: `DistributorRequest.trusted/manufacturer` (`Boolean`: не прислано — не меняется), `DistributorResponse.trusted/manufacturer` (`boolean`); почта карточки — нормализована («a@x.kz, b@y.kz»), неверная — 400.

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/sourcing/DistributorFlagsTest.java`:

```java
package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.DistributorController;
import com.vladoose.nir.dto.request.DistributorRequest;
import com.vladoose.nir.dto.response.DistributorResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class DistributorFlagsTest {

    @Autowired DistributorController controller;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private static DistributorRequest request(String name) {
        DistributorRequest r = new DistributorRequest();
        r.setName(name);
        return r;
    }

    @Test
    void flagsRoundTrip_notSentFlagIsKept_emailsNormalized() {
        DistributorRequest r = request("ZZ Флаги " + System.nanoTime());
        r.setTrusted(true);
        r.setManufacturer(true);
        r.setEmail(" sales7@ema.su; sales9@ema.su ");

        DistributorResponse created = controller.create(r);

        assertThat(created.isTrusted()).isTrue();
        assertThat(created.isManufacturer()).isTrue();
        assertThat(created.getEmail()).isEqualTo("sales7@ema.su, sales9@ema.su");

        DistributorRequest u = request(r.getName());
        u.setTrusted(false);                       // «производитель» и почта не присланы — не меняются
        DistributorResponse updated = controller.update(created.getId(), u);

        assertThat(updated.isTrusted()).isFalse();
        assertThat(updated.isManufacturer()).isTrue();
        assertThat(updated.getEmail()).isEqualTo("sales7@ema.su, sales9@ema.su");
    }

    @Test
    void newSupplierWithoutFlagsIsNeither() {
        DistributorResponse created = controller.create(request("ZZ Без флагов " + System.nanoTime()));
        assertThat(created.isTrusted()).isFalse();
        assertThat(created.isManufacturer()).isFalse();
    }

    @Test
    void invalidEmailIs400WithTheAddress() {
        DistributorRequest r = request("ZZ Кривая почта " + System.nanoTime());
        r.setEmail("ok@a.kz, broken@kz");
        assertThatThrownBy(() -> controller.create(r))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("broken@kz");
    }

    @Test
    void blankEmailClearsTheCard() {
        DistributorRequest r = request("ZZ Очистка " + System.nanoTime());
        r.setEmail("a@zz.kz");
        DistributorResponse created = controller.create(r);
        DistributorRequest u = request(r.getName());
        u.setEmail("   ");
        assertThat(controller.update(created.getId(), u).getEmail()).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.sourcing.DistributorFlagsTest'`
Expected: FAIL — `compileTestJava`: нет `setTrusted`/`isTrusted` у DTO.

- [ ] **Step 3: DTO и контроллер**

В `DistributorRequest.java` после `brands`:

```java
    /** null — не прислано (поле не меняется). */
    private Boolean trusted;
    private Boolean manufacturer;
```

В `DistributorResponse.java` после `brands`:

```java
    private boolean trusted;
    private boolean manufacturer;
```

В `DistributorController.java` добавить импорт `com.vladoose.nir.util.EmailAddresses`, первой строкой в `create` и в `update` — `normalizeEmail(request);`, и метод:

```java
    /** Почта карточки — один или несколько адресов (спека 2026-10-12 §6.2); неверный — 400 с адресом. Не прислана — не трогаем. */
    private static void normalizeEmail(DistributorRequest r) {
        if (r.getEmail() != null) r.setEmail(EmailAddresses.normalize(r.getEmail()));
    }
```

`DistributorMapper` не меняется: MapStruct сам переносит `trusted` и `manufacturer` и проверяет `Boolean` на `null` перед записью в `boolean`. После правки — `./gradlew compileJava` и глазами в `build/generated/sources/annotationProcessor/java/main/com/vladoose/nir/mapper/DistributorMapperImpl.java`, что в `updateEntity` есть `if ( request.getTrusted() != null )`.

- [ ] **Step 4: Запустить тест**

Run: `./gradlew test --tests 'com.vladoose.nir.sourcing.DistributorFlagsTest'`
Expected: PASS (4 теста).

- [ ] **Step 5: Фронт — отметки в карточке и в списке**

В `frontend/src/app/pages/distributors/distributors.component.ts`:

1) Поле «Эл. почта» — подсказка о нескольких адресах:

```html
      <label>Эл. почта<input formControlName="email" placeholder="несколько — через запятую" [class.input-error]="validationErrors.email" /><span class="field-error" *ngIf="validationErrors.email">{{ validationErrors.email }}</span></label>
```

2) После строки с полем «Сайт» — отметки:

```html
      <div class="flags">
        <label class="type-checkbox"><input type="checkbox" formControlName="trusted">
          <span><b>Доверенный</b> — в запросах цен клиент заявки указывается по умолчанию</span></label>
        <label class="type-checkbox"><input type="checkbox" formControlName="manufacturer">
          <span><b>Производитель</b> — по его брендам запрос цен уходит ему по умолчанию</span></label>
      </div>
```

3) В `FormGroup` — два контрола после `website`:

```ts
    website: new FormControl(''),
    trusted: new FormControl(false),
    manufacturer: new FormControl(false),
```

4) `onAdd()` — сброс с умолчаниями отметок: `this.form.reset({ trusted: false, manufacturer: false });` вместо `this.form.reset();`.

5) Ячейка «Название» списка — отметки рядом с названием:

```html
          <td data-label="Название">{{ d.name }} <span class="flag" *ngIf="d.manufacturer">производитель</span> <span class="flag" *ngIf="d.trusted">доверенный</span></td>
```

6) Стили (до мобильного `@media`):

```css
    .flags { display: flex; flex-direction: column; gap: 6px; margin: 4px 0 12px; }
    .flag { display: inline-block; font-size: 11px; font-weight: 500; border-radius: 999px; padding: 1px 8px; vertical-align: middle;
            background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
```

- [ ] **Step 6: Сборка фронта**

Run: `cd frontend && npm run build`
Expected: «Application bundle generation complete», ошибок нет (давнее предупреждение о начальном бандле — норма).

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java src/main/java/com/vladoose/nir/controller/DistributorController.java frontend/src/app/pages/distributors/distributors.component.ts src/test/java/com/vladoose/nir/sourcing/DistributorFlagsTest.java
git commit -m "$(cat <<'EOF'
feat(distributors): «Доверенный» и «Производитель», несколько адресов почты в карточке

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Тексты запроса на бланке и строки письма `{{заказчик}}` / `{{вложение}}`

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/RfqTexts.java`
- Create: `src/main/java/com/vladoose/nir/service/RfqClientLine.java`
- Modify: `src/main/java/com/vladoose/nir/service/KpEmailComposer.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/EmailTemplateRequest.java`, `dto/response/EmailTemplateResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/EmailTemplateService.java`, `controller/EmailTemplateController.java`
- Modify: `frontend/src/app/pages/email-template/email-template.component.ts`, `frontend/src/app/services/api.service.ts`
- Test: `src/test/java/com/vladoose/nir/service/KpEmailComposerRfqTest.java`
- Test: `src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java` (дополнить)

**Interfaces:**
- Consumes: `EmailTemplate.rfq*`, `PriceRequest.isDiscloseClient()/isWithDocument()` (задача 1).
- Produces:
  - `RfqTexts(String title, String intro, List<String> ask, String clientLine)`, `RfqTexts.of(EmailTemplate saved, Market market)`, `DEFAULT_TITLE`, `DEFAULT_INTRO`, `DEFAULT_CLIENT_LINE`, `defaultAsk(Market)`, `custom(String typed, String byDefault)`, `lines(String)`;
  - `RfqClientLine.clientName(Tender) → String|null`, `RfqClientLine.render(String template, Tender t, String company) → String|null`;
  - `KpEmailComposer.ATTACHMENT_LINE`, `static String trimSpec(String)` (пакетный), плейсхолдеры `{{заказчик}}` и `{{вложение}}`;
  - `EmailTemplateResponse` + `rfqTitle`, `rfqIntro`, `rfqAsk`, `rfqClientLine`; `EmailTemplateService.save(EmailTemplateRequest)`.

- [ ] **Step 1: Падающие тесты письма**

`src/test/java/com/vladoose/nir/service/KpEmailComposerRfqTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Строка о конечном заказчике и о вложении (спека 2026-10-12 §6.3) — без БД, шаблон рынка застаблен. */
class KpEmailComposerRfqTest {

    EmailTemplateRepository repo;
    KpEmailComposer composer;

    @BeforeEach
    void setUp() {
        repo = mock(EmailTemplateRepository.class);
        when(repo.findByMarket(any())).thenReturn(Optional.empty());
        composer = new KpEmailComposer(repo, new EmailTemplateRenderer());
    }

    private static PriceRequest pr(String customer, boolean disclose, boolean withDocument) {
        Tender t = new Tender();
        t.setTenderNumber("ЧЗ-2026-0099");
        t.setSource(Source.PRIVATE_REQUEST);
        t.setCustomerName(customer);
        t.setDeliveryAddress("г. Уральск,\n ул. Мира 5");
        t.setContactName("ZZ Айгуль Контакт");
        t.setContactPhone("+77010000000");
        TenderLot lot = new TenderLot();
        lot.setEquipName("Тонометр OMRON M2");
        lot.setManufact("OMRON");
        PriceRequestItem it = new PriceRequestItem();
        it.setTenderLot(lot);
        it.setRequestedQuantity(5);
        Distributor d = new Distributor();
        d.setName("ZZ Поставщик");
        PriceRequest pr = new PriceRequest();
        pr.setId(77L);
        pr.setTender(t);
        pr.setDistributor(d);
        pr.setMarket(Market.KZ);
        pr.setItems(new ArrayList<>(List.of(it)));
        pr.setDiscloseClient(disclose);
        pr.setWithDocument(withDocument);
        return pr;
    }

    @Test
    void noClientAndNoAttachmentLineByDefault() {
        String body = composer.compose(pr("ZZ Клиника «Здоровье»", false, false)).body();
        assertThat(body).doesNotContain("ZZ Клиника").doesNotContain("Конечный заказчик").doesNotContain("во вложении")
                .doesNotContain("ЧЗ-2026-0099").doesNotContain("ZZ Айгуль").doesNotContain("+77010000000");
    }

    @Test
    void clientLineWithPlaceAndCompany_attachmentLine_underPositions() {
        String body = composer.compose(pr("ZZ Клиника «Здоровье»", true, true)).body();
        assertThat(body)
                .contains("Конечный заказчик: ZZ Клиника «Здоровье», место поставки: г. Уральск, ул. Мира 5. "
                        + "Просим закрепить проект за ТОО «West-Med».")
                .contains("Запрос на бланке компании — во вложении.")
                .doesNotContain("ЧЗ-2026-0099").doesNotContain("ZZ Айгуль").doesNotContain("+77010000000");
        assertThat(body.indexOf("Конечный заказчик")).isGreaterThan(body.indexOf("Тонометр OMRON M2"));
    }

    @Test
    void contactPersonAloneIsNotAClient() {
        String body = composer.compose(pr(null, true, false)).body();
        assertThat(body).doesNotContain("Конечный заказчик").doesNotContain("ZZ Айгуль");
    }

    @Test
    void customTemplateWithoutNewPlaceholdersGetsLinesAfterPositions() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ)
                .subjectTemplate("Запрос").bodyTemplate("Здравствуйте!\n{{позиции}}\nСпасибо").build()));
        String body = composer.compose(pr("ZZ Клиника", true, true)).body();
        int pos = body.indexOf("Тонометр"), client = body.indexOf("Конечный заказчик"),
                att = body.indexOf("во вложении"), thanks = body.indexOf("Спасибо");
        assertThat(pos).isNotNegative();
        assertThat(pos).isLessThan(client);
        assertThat(client).isLessThan(att);
        assertThat(att).isLessThan(thanks);
    }

    @Test
    void customTemplateUnchangedWhenNothingToAdd() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ)
                .subjectTemplate("Запрос").bodyTemplate("Здравствуйте!\n{{позиции}}\nСпасибо").build()));
        assertThat(composer.compose(pr("ZZ Клиника", false, false)).body())
                .isEqualTo("Здравствуйте!\n— Тонометр OMRON M2 (OMRON) — 5 шт.\n\nСпасибо");
    }

    @Test
    void savedClientLineTemplateIsUsed() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ)
                .subjectTemplate("З").bodyTemplate(KpEmailComposer.DEFAULT_BODY).rfqClientLine("Проект для {{клиент}}").build()));
        assertThat(composer.compose(pr("ZZ Клиника", true, false)).body())
                .contains("Проект для ZZ Клиника").doesNotContain("место поставки");
    }
}
```

- [ ] **Step 2: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.service.KpEmailComposerRfqTest'`
Expected: FAIL — `compileTestJava`: нет `setDiscloseClient` (если задача 1 сделана — PASS не будет: падают проверки строки о заказчике и о вложении).

- [ ] **Step 3: `RfqTexts` и `RfqClientLine`**

`src/main/java/com/vladoose/nir/service/RfqTexts.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;

import java.util.Arrays;
import java.util.List;

/**
 * Тексты запроса на бланке и строка о конечном заказчике (спека 2026-10-12 §6.4): сохранённые рынка или зашитые.
 * Строка о заказчике общая для письма ({{заказчик}}) и документа. В вводной и строке — {{компания}}, в строке ещё
 * {{клиент}} и {{место}}.
 */
public record RfqTexts(String title, String intro, List<String> ask, String clientLine) {

    public static final String DEFAULT_TITLE = "ЗАПРОС КОММЕРЧЕСКОГО ПРЕДЛОЖЕНИЯ";
    public static final String DEFAULT_INTRO =
            "{{компания}} просит предоставить коммерческое предложение на поставку следующих товаров:";
    public static final String DEFAULT_CLIENT_LINE =
            "Конечный заказчик: {{клиент}}{{место}}. Просим закрепить проект за {{компания}}.";

    /** Пункты «В предложении просим указать», по одному на строку; регистрация — реестра своего рынка. */
    public static String defaultAsk(Market market) {
        String registration = market == Market.KZ
                ? "Номер РУ РК и сертификат соответствия (СТ-KZ / СТ-1) — при наличии"
                : "Номер регистрационного удостоверения Росздравнадзора";
        return String.join("\n", "Модель и производитель", "Цена за единицу с НДС и срок действия цены",
                "Срок поставки и наличие", registration, "Гарантийный срок", "Условия доставки");
    }

    public static RfqTexts of(EmailTemplate saved, Market market) {
        return new RfqTexts(
                or(saved == null ? null : saved.getRfqTitle(), DEFAULT_TITLE).strip(),
                or(saved == null ? null : saved.getRfqIntro(), DEFAULT_INTRO).strip(),
                lines(or(saved == null ? null : saved.getRfqAsk(), defaultAsk(market))),
                or(saved == null ? null : saved.getRfqClientLine(), DEFAULT_CLIENT_LINE).strip());
    }

    /** Что хранить: пусто или совпадает с зашитым — null (тогда поправка зашитого текста дойдёт и до этого рынка). */
    public static String custom(String typed, String byDefault) {
        if (typed == null || typed.isBlank()) return null;
        return String.join("\n", lines(typed)).equals(String.join("\n", lines(byDefault))) ? null : typed.strip();
    }

    /** Строки текста без пустых и пробелов по краям. */
    public static List<String> lines(String text) {
        if (text == null) return List.of();
        return Arrays.stream(text.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String or(String value, String byDefault) {
        return value == null || value.isBlank() ? byDefault : value;
    }
}
```

`src/main/java/com/vladoose/nir/service/RfqClientLine.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.Tender;

/**
 * Кто клиент для поставщика и строка о конечном заказчике (спека 2026-10-12 §6.2, §6.4). Клиент — учреждение или
 * название клиента заявки; контактное лицо и телефон поставщику не раскрываются (персональные данные, «закрепить проект»
 * за ними нельзя).
 */
public final class RfqClientLine {

    private RfqClientLine() {}

    /** Название клиента одной строкой; нет ни учреждения, ни названия — null. */
    public static String clientName(Tender t) {
        if (t.getFacility() != null && notBlank(t.getFacility().getName())) return oneLine(t.getFacility().getName());
        if (notBlank(t.getCustomerName())) return oneLine(t.getCustomerName());
        return null;
    }

    /** Строка по шаблону (пусто — зашитый) или null, если клиента в заявке нет. */
    public static String render(String template, Tender t, String company) {
        String client = clientName(t);
        if (client == null) return null;
        String place = notBlank(t.getDeliveryAddress()) ? ", место поставки: " + oneLine(t.getDeliveryAddress()) : "";
        String tpl = notBlank(template) ? template : RfqTexts.DEFAULT_CLIENT_LINE;
        return oneLine(tpl.replace("{{клиент}}", client).replace("{{место}}", place)
                .replace("{{компания}}", company == null ? "" : company));
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 4: `KpEmailComposer` — новые строки и вставка в старые шаблоны**

`src/main/java/com/vladoose/nir/service/KpEmailComposer.java` — заменить целиком (буквально сохраняются javadoc `SPEC_LIMIT`, `buildPositions`, `quantity`, `deadlineLine`):

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.util.KpToken;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Построение темы/тела письма КП из редактируемого шаблона рынка (email_template) с фолбэком на
 * зашитый дефолт. Плейсхолдеры: {{приветствие}} {{компания}} {{позиции}} {{дедлайн}} {{реестр}} {{заказчик}} {{вложение}}.
 * Письмо НЕ раскрывает конкретный тендер/заявку (нет номера и ссылки на объявление) — решение
 * оператора 2026-07-09; клиент заявки — только строкой {{заказчик}} у запроса с отметкой «Указать клиента»
 * (спека 2026-10-12 §6.2). Токен [КП-id] в теме — серверный, в шаблон не входит.
 */
@Component
public class KpEmailComposer {

    /**
     * Косметическая обрезка длинного ТЗ — анти-лик §9 на ней БОЛЬШЕ НЕ ДЕРЖИТСЯ, лимит можно менять
     * свободно. Коммерческий блок ТЗ СК-Фармации (условия/срок поставки, место дислокации, имя и
     * адрес заказчика) режет структурно {@code LotDescriptiveText.requirementsForEmail} — от первого
     * якоря до конца текста.
     * <p>Прежняя редакция комментария называла запас в 2528 символов: цифра снята с СЫРОГО ТЗ, а
     * обрезка видит санитизированное (срез шапки сдвигает текст влево) — там первый жёсткий якорь
     * стоял на 2459-м символе в 39/39. То есть запас был 2.05× и держался свойством ДАННЫХ (длиной
     * таблицы требований), а не кода: лот с тремя критериями вместо пятнадцати отправил бы имя
     * больницы поставщику, и ничто бы это не поймало. Поэтому запас заменён срезом хвоста.
     */
    static final int SPEC_LIMIT = 1200;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String DEFAULT_SUBJECT = "Запрос коммерческого предложения";
    public static final String DEFAULT_BODY =
            "{{приветствие}}\n\n" +
            "{{компания}} просит предоставить коммерческое предложение по следующим позициям:\n\n" +
            "{{позиции}}\n" +
            "{{заказчик}}{{вложение}}{{дедлайн}}Просим указать: цену за единицу, № регистрационного удостоверения " +
            "на предлагаемую модель, сроки поставки, условия оплаты, гарантию.\n\n" +
            "С уважением,\n{{компания}}\n\n" +
            "Ответ на этот запрос просим направить ответным письмом (Reply) — он поступит в наш отдел закупок.";

    /** Строка о вложении (спека §6.3) — только когда к письму приложен запрос на бланке. */
    public static final String ATTACHMENT_LINE = "Запрос на бланке компании — во вложении.";
    private static final String POSITIONS = "{{позиции}}", CLIENT = "{{заказчик}}", ATTACHMENT = "{{вложение}}";

    private final EmailTemplateRepository templateRepository;
    private final EmailTemplateRenderer renderer;

    public KpEmailComposer(EmailTemplateRepository templateRepository, EmailTemplateRenderer renderer) {
        this.templateRepository = templateRepository;
        this.renderer = renderer;
    }

    public record Composed(String subject, String body) {}

    public Composed compose(PriceRequest pr) {
        Composed c = build(pr);
        return new Composed(KpToken.subjectToken(pr.getId()) + " " + c.subject(), c.body());
    }

    /** Черновой предпросмотр (id ещё нет) — тема без токена. */
    public Composed composeForPreview(PriceRequest draft) {
        return build(draft);
    }

    private Composed build(PriceRequest pr) {
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        Optional<EmailTemplate> saved = templateRepository.findByMarket(market);
        String subject = saved.map(EmailTemplate::getSubjectTemplate).filter(s -> !s.isBlank()).orElse(DEFAULT_SUBJECT);
        String body = saved.map(EmailTemplate::getBodyTemplate).filter(s -> !s.isBlank()).orElse(DEFAULT_BODY);
        String client = pr.isDiscloseClient()
                ? RfqClientLine.render(RfqTexts.of(saved.orElse(null), market).clientLine(), pr.getTender(),
                        market.companyShortName())
                : null;
        String attachment = pr.isWithDocument() ? ATTACHMENT_LINE : null;
        Map<String, String> vars = vars(pr);
        vars.put("заказчик", client == null ? "" : client + "\n\n");
        vars.put("вложение", attachment == null ? "" : attachment + "\n\n");
        String fullBody = withMissingLines(body, client != null, attachment != null);
        return new Composed(renderer.render(subject, vars), renderer.render(fullBody, vars));
    }

    /**
     * Свой шаблон оператора, сохранённый до этапа 2, не знает {{заказчик}} и {{вложение}}: без них клиент, указанный
     * поставщику, и строка о вложении молча пропали бы из письма. Недостающие — и только когда им есть что сказать —
     * встают отдельным абзацем сразу после {{позиции}} (нет и её — в конец письма). Без клиента и вложения шаблон
     * не меняется.
     */
    static String withMissingLines(String body, boolean client, boolean attachment) {
        String missing = (client && !body.contains(CLIENT) ? CLIENT : "")
                + (attachment && !body.contains(ATTACHMENT) ? ATTACHMENT : "");
        if (missing.isEmpty()) return body;
        int at = body.indexOf(POSITIONS);
        if (at < 0) return body.stripTrailing() + "\n\n" + missing;
        int end = at + POSITIONS.length();
        return body.substring(0, end) + "\n" + missing + body.substring(end);
    }

    private Map<String, String> vars(PriceRequest pr) {
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        Distributor d = pr.getDistributor();
        Map<String, String> v = new HashMap<>();
        String contact = (safe(d.getLastName()) + " " + safe(d.getFirstName())).trim();
        v.put("приветствие", contact.isBlank() ? "Здравствуйте!" : "Уважаемый(ая) " + contact + "!");
        v.put("компания", market.companyShortName());
        v.put("реестр", market == Market.KZ ? "НЦЭЛС РК" : "Росздравнадзора");
        v.put("позиции", buildPositions(pr));
        v.put("дедлайн", deadlineLine(pr, tender));
        return v;
    }

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

    private String buildPositions(PriceRequest pr) {
        StringBuilder sb = new StringBuilder();
        for (PriceRequestItem it : pr.getItems()) {
            TenderLot lot = it.getTenderLot();
            String qty = quantity(it.getRequestedQuantity(), lot);
            // анти-лик: без «Лот N:» и номера лота — поставщик не должен видеть, что позиция под тендер
            sb.append("— ");
            MedEquipment eq = it.getMedEquipment();
            if (eq != null) {
                sb.append(eq.getName()).append(" (").append(eq.getManufact()).append(")");
                if (eq.getRegistration() != null) {
                    sb.append(", РУ № ").append(eq.getRegistration().getRegNumber());
                }
                sb.append(" — ").append(qty).append("\n");
            } else {
                sb.append(lot.getEquipName());
                if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
                    sb.append(" (").append(lot.getManufact()).append(")");
                }
                if (lot.getRegStatus() == LineRegStatus.CONFIRMED && lot.getRegNumber() != null) {
                    sb.append(", РУ № ").append(lot.getRegNumber());
                }
                sb.append(" — ").append(qty).append("\n");
                // санитизируем ТЗ: только тех.характеристики, без goszakup-шапки (номер закупки/лота,
                // места/сроки поставки) — иначе письмо раскрывает тендер поставщику (§9 анти-лик)
                String req = LotDescriptiveText.requirementsForEmail(lot.getRequiredSpec());
                if (req != null && !req.isBlank()) {
                    sb.append("  Требования: ").append(trimSpec(req)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** Обрезка ТЗ — одна для письма и документа на бланке (RfqDocumentService). */
    static String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ — по запросу)";
    }

    private String safe(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 5: Запустить тесты письма**

Run: `./gradlew test --tests 'com.vladoose.nir.service.KpEmailComposerRfqTest' --tests 'com.vladoose.nir.service.KpEmailComposerTest'`
Expected: PASS (6 новых; прежние `KpEmailComposerTest` — без изменений: пустые `{{заказчик}}`/`{{вложение}}` текст не меняют).

- [ ] **Step 6: Падающие тесты шаблона**

В `src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java` добавить импорты `com.vladoose.nir.dto.request.EmailTemplateRequest` и `com.vladoose.nir.service.RfqTexts` (`EmailTemplate` и `Market` там уже импортированы; строки шаблона класс чистит сам в `@BeforeEach`) и тесты:

```java
    @Test
    void rfqTextsAreDefaultsUntilSaved_textsLikeDefaultsAreNotStored() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var resp = controller.get();
        assertThat(resp.getRfqTitle()).isEqualTo(RfqTexts.DEFAULT_TITLE);
        assertThat(resp.getRfqIntro()).isEqualTo(RfqTexts.DEFAULT_INTRO);
        assertThat(resp.getRfqAsk()).isEqualTo(RfqTexts.defaultAsk(Market.KZ));
        assertThat(resp.getRfqClientLine()).isEqualTo(RfqTexts.DEFAULT_CLIENT_LINE);

        EmailTemplateRequest req = new EmailTemplateRequest();
        req.setSubject("Т");
        req.setBody("{{позиции}}");
        req.setRfqTitle("ЗАПРОС ЦЕН");
        req.setRfqAsk(RfqTexts.defaultAsk(Market.KZ));           // как зашитый — не хранится
        req.setRfqClientLine("Для: {{клиент}}");
        controller.put(req);

        EmailTemplate row = repository.findByMarket(Market.KZ).orElseThrow();
        assertThat(row.getRfqTitle()).isEqualTo("ЗАПРОС ЦЕН");
        assertThat(row.getRfqAsk()).isNull();
        assertThat(row.getRfqClientLine()).isEqualTo("Для: {{клиент}}");
        assertThat(controller.get().getRfqTitle()).isEqualTo("ЗАПРОС ЦЕН");
        assertThat(controller.get().getRfqAsk()).isEqualTo(RfqTexts.defaultAsk(Market.KZ));
    }

    @Test
    void rfqFieldsNotSentKeepSavedValues() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        EmailTemplateRequest first = new EmailTemplateRequest();
        first.setSubject("Т");
        first.setBody("{{позиции}}");
        first.setRfqIntro("Своя вводная");
        controller.put(first);
        EmailTemplateRequest second = new EmailTemplateRequest();   // старый экран: rfq-полей не шлёт
        second.setSubject("Т2");
        second.setBody("{{позиции}}");
        controller.put(second);
        assertThat(repository.findByMarket(Market.KZ).orElseThrow().getRfqIntro()).isEqualTo("Своя вводная");
    }

    @Test
    void defaultEndpointReturnsRfqDefaultsOfTheMarket() {
        com.vladoose.nir.context.MarketContext.set(Market.RF);
        var resp = controller.getDefault();
        assertThat(resp.getRfqAsk()).isEqualTo(RfqTexts.defaultAsk(Market.RF)).contains("Росздравнадзора");
    }
```

- [ ] **Step 7: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateEndpointTest'`
Expected: FAIL — `compileTestJava`: нет `getRfqTitle`/`setRfqTitle`.

- [ ] **Step 8: DTO, сервис и контроллер шаблона**

`EmailTemplateRequest.java` — заменить целиком:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmailTemplateRequest {
    @NotBlank(message = "Тема не может быть пустой")
    private String subject;
    @NotBlank(message = "Текст письма не может быть пустым")
    private String body;

    /** Тексты запроса на бланке (спека 2026-10-12 §6.4): null — не прислано (не меняется), пусто — зашитый текст. */
    @Size(max = 500, message = "Заголовок — не больше 500 символов")
    private String rfqTitle;
    @Size(max = 2000, message = "Вводная — не больше 2000 символов")
    private String rfqIntro;
    @Size(max = 4000, message = "Пункты — не больше 4000 символов")
    private String rfqAsk;
    @Size(max = 1000, message = "Строка о заказчике — не больше 1000 символов")
    private String rfqClientLine;
}
```

`EmailTemplateResponse.java` — добавить поля в конец (порядок полей = порядок аргументов `@AllArgsConstructor`):

```java
    private List<String> warnings;
    private String rfqTitle;
    private String rfqIntro;
    private String rfqAsk;
    private String rfqClientLine;
```

`EmailTemplateService.java` — заменить целиком:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.EmailTemplateRequest;
import com.vladoose.nir.dto.response.EmailTemplateResponse;
import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Шаблон письма КП и тексты запроса на бланке для активного рынка (MarketContext). Дефолты — KpEmailComposer и RfqTexts.
 */
@Service
public class EmailTemplateService {

    private final EmailTemplateRepository repository;

    public EmailTemplateService(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    /** Сохранённое рынка (по каждому полю — или зашитое). */
    public EmailTemplateResponse current() {
        Market market = MarketContext.get();
        Optional<EmailTemplate> t = repository.findByMarket(market);
        RfqTexts rfq = RfqTexts.of(t.orElse(null), market);
        return t.map(x -> response(market, x.getSubjectTemplate(), x.getBodyTemplate(), x.getUpdatedAt(), List.of(), rfq))
                .orElseGet(() -> response(market, KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null,
                        List.of(), rfq));
    }

    /** Зашитое (для «Сбросить»). */
    public EmailTemplateResponse defaults() {
        Market market = MarketContext.get();
        return response(market, KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null, List.of(),
                RfqTexts.of(null, market));
    }

    @Transactional
    public EmailTemplateResponse save(EmailTemplateRequest r) {
        Market market = MarketContext.get();
        EmailTemplate t = repository.findByMarket(market).orElseGet(EmailTemplate::new);
        t.setMarket(market);
        t.setSubjectTemplate(r.getSubject());
        t.setBodyTemplate(r.getBody());
        // не прислано (старый экран) — не меняется; совпадает с зашитым — не хранится (RfqTexts.custom)
        if (r.getRfqTitle() != null) t.setRfqTitle(RfqTexts.custom(r.getRfqTitle(), RfqTexts.DEFAULT_TITLE));
        if (r.getRfqIntro() != null) t.setRfqIntro(RfqTexts.custom(r.getRfqIntro(), RfqTexts.DEFAULT_INTRO));
        if (r.getRfqAsk() != null) t.setRfqAsk(RfqTexts.custom(r.getRfqAsk(), RfqTexts.defaultAsk(market)));
        if (r.getRfqClientLine() != null) {
            t.setRfqClientLine(RfqTexts.custom(r.getRfqClientLine(), RfqTexts.DEFAULT_CLIENT_LINE));
        }
        t.setUpdatedAt(OffsetDateTime.now());
        repository.save(t);
        List<String> warnings = new ArrayList<>();
        if (r.getBody() == null || !r.getBody().contains("{{позиции}}")) warnings.add("no-positions");
        return response(market, t.getSubjectTemplate(), t.getBodyTemplate(), t.getUpdatedAt(), warnings,
                RfqTexts.of(t, market));
    }

    private static EmailTemplateResponse response(Market market, String subject, String body, OffsetDateTime updatedAt,
                                                  List<String> warnings, RfqTexts rfq) {
        return new EmailTemplateResponse(market.name(), subject, body, updatedAt, warnings, rfq.title(), rfq.intro(),
                String.join("\n", rfq.ask()), rfq.clientLine());
    }
}
```

`EmailTemplateController.put` — `return service.save(req);`.

- [ ] **Step 9: Запустить тесты шаблона**

Run: `./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateEndpointTest'`
Expected: PASS (прежние 5 + 3 новых).

- [ ] **Step 10: Мутация — поставщику без отметки клиент**

Скопировать `KpEmailComposer.java`, в `build` заменить `pr.isDiscloseClient()` на `true`, прогнать `KpEmailComposerRfqTest`.
Expected: FAIL `noClientAndNoAttachmentLineByDefault`. Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 11: Фронт — блок «Запрос на бланке» на странице шаблона**

В `frontend/src/app/services/api.service.ts` тип тела `saveEmailTemplate`:

```ts
  saveEmailTemplate(body: { subject: string; body: string; rfqTitle?: string; rfqIntro?: string; rfqAsk?: string;
                            rfqClientLine?: string }): Observable<any> {
    return this.http.put<any>(`${this.base}/email-template`, body);
  }
```

В `frontend/src/app/pages/email-template/email-template.component.ts`:

1) В шаблоне после `<p class="et-note">…</p>` и перед `et-actions` — блок:

```html
      <h3 class="et-sec">Запрос на бланке (Word)</h3>
      <p class="et-note">Прикладывается к письму поставщику по галочке «Приложить запрос на бланке». Бланк, подпись и
        печать — из «Реквизитов и печати».</p>

      <label class="et-lbl">Заголовок</label>
      <input class="et-input" [(ngModel)]="rfqTitle" maxlength="500" />

      <label class="et-lbl">Вводная</label>
      <textarea class="et-body" rows="3" maxlength="2000" [(ngModel)]="rfqIntro"></textarea>

      <label class="et-lbl">«В предложении просим указать» — по пункту на строку</label>
      <textarea class="et-body" rows="7" maxlength="4000" [(ngModel)]="rfqAsk"></textarea>

      <label class="et-lbl">Строка о конечном заказчике — и в письме, и в документе</label>
      <textarea class="et-body" rows="2" maxlength="1000" [(ngModel)]="rfqClientLine"></textarea>
      <p class="et-note">В вводной и строке о заказчике: <code>{{ ph.company }}</code> — ваша компания; в строке ещё
        <code>{{ ph.client }}</code> — клиент заявки и <code>{{ ph.place }}</code> — «, место поставки: …». Клиент попадает
        в запрос только у поставщиков с галочкой «Указать клиента».</p>
      <button class="btn btn-line" (click)="resetRfq()">Сбросить тексты запроса</button>
```

2) Список чипов письма — два новых:

```ts
  placeholders = [
    { key: '{{приветствие}}', desc: 'Уважаемый(ая) ФИО! или Здравствуйте!' },
    { key: '{{компания}}', desc: 'Название вашей компании' },
    { key: '{{позиции}}', desc: 'Список оборудования (обязательно)' },
    { key: '{{дедлайн}}', desc: 'Просим ответить до даты' },
    { key: '{{заказчик}}', desc: 'Строка о конечном заказчике — только у поставщиков с «Указать клиента»' },
    { key: '{{вложение}}', desc: '«Запрос на бланке компании — во вложении.» — если документ приложен' },
    { key: '{{реестр}}', desc: 'НЦЭЛС РК / Росздравнадзора' },
  ];
  /** Плейсхолдеры строки о заказчике — полями: «{{…}}» прямо в шаблоне Angular принял бы за привязку. */
  readonly ph = { company: '{{компания}}', client: '{{клиент}}', place: '{{место}}' };
  rfqTitle = '';
  rfqIntro = '';
  rfqAsk = '';
  rfqClientLine = '';
```

3) `ngOnInit` — тексты запроса из ответа (`applyRfq`); прежний «Сбросить» (тема и тело письма) тексты запроса не трогает — у них свой «Сбросить тексты запроса» (спека §6.4); `save()` — шлёт всё; новый `resetRfq()`:

```ts
  ngOnInit() {
    this.api.getEmailTemplate().subscribe({
      next: (t) => { this.subject = t.subject || ''; this.body = t.body || ''; this.applyRfq(t); this.cdr.detectChanges(); },
      error: (e) => this.notify.error('Не удалось загрузить шаблон: ' + (e.error?.message || e.message)),
    });
  }

  private applyRfq(t: any) {
    this.rfqTitle = t.rfqTitle || '';
    this.rfqIntro = t.rfqIntro || '';
    this.rfqAsk = t.rfqAsk || '';
    this.rfqClientLine = t.rfqClientLine || '';
  }
```

```ts
    this.api.saveEmailTemplate({ subject: this.subject, body: this.body, rfqTitle: this.rfqTitle, rfqIntro: this.rfqIntro,
                                 rfqAsk: this.rfqAsk, rfqClientLine: this.rfqClientLine }).subscribe({
```

```ts
  /** Только тексты запроса — тему и тело письма не трогает. */
  resetRfq() {
    this.api.getEmailTemplateDefault().subscribe({
      next: (t) => {
        this.applyRfq(t);
        this.notify.success('Загружены стандартные тексты запроса — нажмите «Сохранить», чтобы применить');
        this.cdr.detectChanges();
      },
      error: (e) => this.notify.error('Ошибка: ' + (e.error?.message || e.message)),
    });
  }
```

4) Стили: `.et-sec { margin: 28px 0 4px; font-size: 16px; }` и `code { font-size: 12px; }`.

- [ ] **Step 12: Сборка фронта**

Run: `cd frontend && npm run build`
Expected: сборка без ошибок.

- [ ] **Step 13: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add src/main/java/com/vladoose/nir/service/RfqTexts.java src/main/java/com/vladoose/nir/service/RfqClientLine.java src/main/java/com/vladoose/nir/service/KpEmailComposer.java src/main/java/com/vladoose/nir/dto/request/EmailTemplateRequest.java src/main/java/com/vladoose/nir/dto/response/EmailTemplateResponse.java src/main/java/com/vladoose/nir/service/EmailTemplateService.java src/main/java/com/vladoose/nir/controller/EmailTemplateController.java src/test/java/com/vladoose/nir/service/KpEmailComposerRfqTest.java src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java frontend/src/app/pages/email-template/email-template.component.ts frontend/src/app/services/api.service.ts
git commit -m "$(cat <<'EOF'
feat(email): строка о конечном заказчике и о вложении в письме, тексты запроса на бланке в шаблоне

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Документ запроса на бланке (Word)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/document/KpDocumentBuilder.java`
- Create: `src/main/java/com/vladoose/nir/service/document/RfqDocumentBuilder.java`
- Create: `src/main/java/com/vladoose/nir/service/RfqDocumentService.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/RfqDocumentBuilderTest.java`
- Test: `src/test/java/com/vladoose/nir/service/RfqDocumentServiceTest.java`

**Interfaces:**
- Consumes: `RfqTexts`, `RfqClientLine`, `KpEmailComposer.trimSpec` (задача 4); `KpDocxRenderer.render(KpDocument)`.
- Produces:
  - `KpDocumentBuilder.letterhead(CompanyProfile)`, `KpDocumentBuilder.fitTable(List<KpDocument.Column>, List<KpDocument.Row>, boolean)` → `KpDocumentBuilder.TableFit(columns, fontPt)`, `KpDocumentBuilder.withListItemEnd(String, boolean)` — пакетные;
  - `RfqDocumentBuilder.Line(name, characteristics, quantity, unit, registration)`, `RfqDocumentBuilder.Input(profile, number, date, recipientName, recipientContact, texts, clientLine, lines, replyTo, replyBy, withStamp)`, `RfqDocumentBuilder.build(Input) → KpDocument`;
  - `RfqDocumentService.render(PriceRequest) → RfqDocumentService.RfqFile(fileName, bytes)`, `RfqDocumentService.DOCX` (тип).

- [ ] **Step 1: Падающий тест сборщика**

`src/test/java/com/vladoose/nir/clientoffer/RfqDocumentBuilderTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.RfqTexts;
import com.vladoose.nir.service.document.KpDocxRenderer;
import com.vladoose.nir.service.document.RfqDocumentBuilder;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Запрос на бланке читается обратно через POI (спека 2026-10-12 §6.4). */
class RfqDocumentBuilderTest {

    private final RfqDocumentBuilder builder = new RfqDocumentBuilder();
    private final KpDocxRenderer renderer = new KpDocxRenderer();

    private static final List<RfqDocumentBuilder.Line> LINES = List.of(
            new RfqDocumentBuilder.Line("Электрокардиограф BeneHeart R12 (Mindray)", "12 отведений, печать A4", "2", "шт",
                    "РУ № РК МИ (МТ)-0№030903"),
            new RfqDocumentBuilder.Line("Тонометр OMRON M2 (OMRON)", null, "уточняется", "упак", null));

    private static RfqDocumentBuilder.Input input(Long number, String client, List<RfqDocumentBuilder.Line> lines,
                                                  boolean stamp, CompanyProfile p) {
        return new RfqDocumentBuilder.Input(p, number, LocalDate.of(2026, 10, 12), "ТОО «MEDSYST»", "Иванов Иван",
                RfqTexts.of(null, Market.KZ), client, lines, "zakup@westmed.kz", LocalDate.of(2026, 10, 15), stamp);
    }

    private static XWPFDocument open(byte[] docx) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private static String text(byte[] docx) throws IOException {
        try (XWPFDocument d = open(docx); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
            return x.getText().replace(' ', ' ');
        }
    }

    private static XWPFTable items(XWPFDocument d) {
        return d.getTables().stream().filter(t -> t.getRow(0).getCell(0).getText().equals("№")).findFirst().orElseThrow();
    }

    private static List<String> row(XWPFTable t, int i) {
        return t.getRow(i).getTableCells().stream().map(XWPFTableCell::getText).toList();
    }

    @Test
    void letterheadNumberRecipientItemsAskAndReplyLine() throws Exception {
        byte[] docx = renderer.render(builder.build(input(534L, null, LINES, false, KpFixtures.profileKz())));

        assertThat(text(docx)).contains("West-Med")
                .contains("Исх. № 534 от 12.10.2026 г.").contains("ТОО «MEDSYST»").contains("Иванов Иван")
                .contains("ЗАПРОС КОММЕРЧЕСКОГО ПРЕДЛОЖЕНИЯ")
                .contains("ТОО «West-Med» просит предоставить коммерческое предложение на поставку следующих товаров:")
                .contains("В предложении просим указать:").contains("1. Модель и производитель;")
                .contains("6. Условия доставки.").contains("Ответ просим направить на zakup@westmed.kz до 15.10.2026.")
                .contains("Директор ТОО «West-Med»").contains("Ширяев И. В.")
                // контакты под подписью («моб: …, e-mail: …») не печатаются; телефон и почта в реквизитах бланка — остаются
                .doesNotContain("Конечный заказчик").doesNotContain("Итого").doesNotContain("моб: 87770752770");
        try (XWPFDocument d = open(docx)) {
            XWPFTable t = items(d);
            assertThat(row(t, 0)).containsExactly("№", "Наименование", "Характеристики", "Кол-во", "Ед. изм.", "Регистрация в РК");
            assertThat(row(t, 1)).containsExactly("1", "Электрокардиограф BeneHeart R12 (Mindray)", "12 отведений, печать A4",
                    "2", "шт", "РУ № РК МИ (МТ)-0№030903");
            assertThat(row(t, 2).get(3)).isEqualTo("уточняется");
        }
    }

    @Test
    void emptyColumnsAreDropped_draftHasDashNumber() throws Exception {
        List<RfqDocumentBuilder.Line> plain = List.of(new RfqDocumentBuilder.Line("Кушетка медицинская", null, "1", "шт", null));
        byte[] docx = renderer.render(builder.build(input(null, null, plain, false, KpFixtures.profileKz())));
        assertThat(text(docx)).contains("Исх. № — от 12.10.2026 г.");
        try (XWPFDocument d = open(docx)) {
            assertThat(row(items(d), 0)).containsExactly("№", "Наименование", "Кол-во", "Ед. изм.");
        }
    }

    @Test
    void clientLineGoesUnderIntroOnlyWhenGiven() throws Exception {
        String line = "Конечный заказчик: ZZ Клиника, место поставки: г. Уральск. Просим закрепить проект за ТОО «West-Med».";
        assertThat(text(renderer.render(builder.build(input(1L, line, LINES, false, KpFixtures.profileKz()))))).contains(line);
    }

    @Test
    void stampAndSignatureOnlyWithFlag() throws Exception {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        p.setSignaturePng(KpTestSupport.signaturePng());
        try (XWPFDocument d = open(renderer.render(builder.build(input(1L, null, LINES, false, p))))) {
            assertThat(anchors(d)).isZero();
        }
        try (XWPFDocument d = open(renderer.render(builder.build(input(1L, null, LINES, true, p))))) {
            assertThat(anchors(d)).isEqualTo(2);
        }
    }

    private static int anchors(XWPFDocument d) {
        List<XWPFParagraph> all = new ArrayList<>(d.getParagraphs());
        for (XWPFTable t : d.getTables()) {
            for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) all.addAll(c.getParagraphs());
        }
        int n = 0;
        for (XWPFParagraph p : all) {
            for (XWPFRun r : p.getRuns()) for (CTDrawing drawing : r.getCTR().getDrawingList()) n += drawing.sizeOfAnchorArray();
        }
        return n;
    }
}
```

- [ ] **Step 2: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.RfqDocumentBuilderTest'`
Expected: FAIL — `compileTestJava`: нет `RfqDocumentBuilder`.

- [ ] **Step 3: Пакетный доступ в `KpDocumentBuilder`**

В `src/main/java/com/vladoose/nir/service/document/KpDocumentBuilder.java`:

1) В `build(...)` вторым аргументом `new KpDocument(...)` вместо `new KpDocument.Letterhead(CompanyLines.split(…), …, CompanyLines.of(profile))` — `letterhead(profile)`.
2) Добавить после `build`:

```java
    /** Бланк рынка (спека §6.3) — общий для КП клиенту и запроса цен на бланке (RfqDocumentBuilder). */
    static KpDocument.Letterhead letterhead(CompanyProfile profile) {
        return new KpDocument.Letterhead(CompanyLines.split(profile.getHeaderLeft()), CompanyLines.split(profile.getHeaderRight()),
                profile.getLogoPng(), blankToNull(profile.getBrandText()), CompanyLines.of(profile));
    }
```

3) `private record TableFit(` → `record TableFit(`; `private static TableFit fitTable(` → `static TableFit fitTable(`; `private static String withListItemEnd(` → `static String withListItemEnd(`. Javadoc этих трёх не меняется, к `fitTable` — дописать строку «Пакетный: тот же подбор у запроса на бланке (RfqDocumentBuilder).».

- [ ] **Step 4: `RfqDocumentBuilder`**

`src/main/java/com/vladoose/nir/service/document/RfqDocumentBuilder.java`:

```java
package com.vladoose.nir.service.document;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.CompanyLines;
import com.vladoose.nir.service.RfqTexts;
import com.vladoose.nir.service.offer.OfferColumnKey;
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Запрос коммерческого предложения на бланке (спека 2026-10-12 §6.4) → KpDocument для KpDocxRenderer: тот же бланк,
 * геометрия, подпись и печать, что у КП клиенту; цен и итогов нет. Колонки «Характеристики» и «Регистрация» — только
 * если заполнены хоть у одной позиции; ширины — общий подбор KpDocumentBuilder.fitTable. Клиента, номера заявки и
 * тендера документ не знает вовсе: строку о заказчике ему дают готовой, и только по отметке «Указать клиента».
 */
@Component
public class RfqDocumentBuilder {

    /** Позиция — уже готовым текстом: наименование с брендом, характеристики, количество, ед. изм., регистрация. */
    public record Line(String name, String characteristics, String quantity, String unit, String registration) {}

    /**
     * number — id запроса (метка темы письма); null — документ до отправки, «№ —». clientLine — готовая строка о
     * заказчике или null. replyTo — ящик ответов; replyBy — «Ответить до» или null.
     */
    public record Input(CompanyProfile profile, Long number, LocalDate date, String recipientName, String recipientContact,
                        RfqTexts texts, String clientLine, List<Line> lines, String replyTo, LocalDate replyBy,
                        boolean withStamp) {}

    public KpDocument build(Input in) {
        CompanyProfile p = in.profile();
        boolean specs = in.lines().stream().anyMatch(l -> notBlank(l.characteristics()));
        boolean registration = in.lines().stream().anyMatch(l -> notBlank(l.registration()));

        List<KpDocument.Column> columns = new ArrayList<>();
        columns.add(column(OfferColumnKey.NUM, "№"));
        // ключ решает класс подбора ширины (NAME — остаток, не меньше четверти), подпись — что в колонке: длинным
        // характеристикам — остаток, наименованию — текстовая колонка в 15 %
        if (specs) {
            columns.add(column(OfferColumnKey.MANUFACTURER, "Наименование"));
            columns.add(column(OfferColumnKey.NAME, "Характеристики"));
        } else {
            columns.add(column(OfferColumnKey.NAME, "Наименование"));
        }
        columns.add(column(OfferColumnKey.QTY, "Кол-во"));
        columns.add(column(OfferColumnKey.UNIT, "Ед. изм."));
        if (registration) {
            columns.add(column(OfferColumnKey.REGISTRATION, p.getMarket() == Market.KZ ? "Регистрация в РК" : "Регистрация"));
        }

        List<KpDocument.Row> rows = new ArrayList<>();
        for (int i = 0; i < in.lines().size(); i++) {
            Line l = in.lines().get(i);
            List<List<String>> cells = new ArrayList<>();
            cells.add(List.of(String.valueOf(i + 1)));
            cells.add(CompanyLines.split(l.name()));
            if (specs) cells.add(CompanyLines.split(l.characteristics()));
            cells.add(List.of(l.quantity()));
            cells.add(List.of(l.unit()));
            if (registration) cells.add(CompanyLines.split(l.registration()));
            rows.add(new KpDocument.Row(KpDocument.RowKind.ITEM, cells, columns.size(), List.of()));
        }
        KpDocumentBuilder.TableFit table = KpDocumentBuilder.fitTable(columns, rows, false);

        String company = p.getShortName() == null ? "" : p.getShortName().trim();
        List<String> recipient = new ArrayList<>(CompanyLines.split(in.recipientName()));
        if (notBlank(in.recipientContact())) recipient.add(in.recipientContact().trim());
        String intro = in.texts().intro().replace("{{компания}}", company)
                + (notBlank(in.clientLine()) ? "\n" + in.clientLine().trim() : "");

        return new KpDocument(
                false,
                KpDocumentBuilder.letterhead(p),
                "Исх. № " + (in.number() == null ? "—" : in.number()) + " от " + DocFormat.date(in.date()) + " г.",
                recipient,
                in.texts().title(),
                null,
                intro,
                List.of(),
                table.columns(),
                rows,
                List.of(),
                null,
                terms(in),
                signoff(p, in.withStamp()),
                table.fontPt());
    }

    /** «В предложении просим указать:», пункты «1. …;» (последний — с точкой) и строка ответа. */
    private static List<String> terms(Input in) {
        List<String> out = new ArrayList<>();
        List<String> ask = in.texts().ask();
        if (!ask.isEmpty()) {
            out.add("В предложении просим указать:");
            for (int i = 0; i < ask.size(); i++) {
                out.add((i + 1) + ". " + KpDocumentBuilder.withListItemEnd(ask.get(i), i == ask.size() - 1));
            }
        }
        String to = notBlank(in.replyTo()) ? "на " + in.replyTo().trim() : "ответным письмом";
        out.add("Ответ просим направить " + to + (in.replyBy() == null ? "." : " до " + DocFormat.date(in.replyBy()) + "."));
        return out;
    }

    /**
     * Подпись директора из профиля; подпись и печать — по отметке, если они загружены. Контактов под подписью нет:
     * строка ответа уже называет ящик приёма, а контакты профиля уводили бы ответ мимо него.
     */
    private static KpDocument.Signoff signoff(CompanyProfile p, boolean withStamp) {
        String shortName = p.getShortName() == null ? "" : p.getShortName().trim();
        String title = notBlank(p.getDirectorTitle()) ? p.getDirectorTitle().trim() + " " + shortName : shortName;
        return new KpDocument.Signoff(true, title, KpDocumentBuilder.surnameWithInitials(p.getDirectorName()), null,
                withStamp ? p.getSignaturePng() : null, withStamp ? p.getStampPng() : null, p.getStampSizeMm());
    }

    private static KpDocument.Column column(OfferColumnKey key, String label) {
        return new KpDocument.Column(key.name(), label, key.align(), 0, false);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 5: Запустить тест сборщика**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.RfqDocumentBuilderTest' --tests 'com.vladoose.nir.clientoffer.KpDocxRendererTest' --tests 'com.vladoose.nir.clientoffer.KpDocumentBuilderTest'`
Expected: PASS (4 новых; прежние тесты документа КП — без изменений после выноса `letterhead`). Если класса `KpDocumentBuilderTest` нет — прогнать `--tests 'com.vladoose.nir.clientoffer.*'`.

- [ ] **Step 6: Падающий тест сервиса документа**

`src/test/java/com/vladoose/nir/service/RfqDocumentServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** Документ запроса из PriceRequest (спека §6.4): против утечки, обрезка ТЗ, имя файла. */
@SpringBootTest
@Transactional
class RfqDocumentServiceTest {

    @Autowired PrivateRequestService service;
    @Autowired RfqDocumentService documents;
    @Autowired DistributorRepository distributorRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist() {
        return distributorRepository.save(Distributor.builder().name("ZZ Документ " + System.nanoTime()).email("d@doc.kz").build());
    }

    private static PriceRequest draft(Tender t, TenderLot lot, Distributor d, boolean disclose) {
        return PriceRequest.builder().tender(t).distributor(d).market(Market.KZ).discloseClient(disclose).withDocument(true)
                .items(new ArrayList<>(List.of(PriceRequestItem.builder().tenderLot(lot).requestedQuantity(1).build()))).build();
    }

    private static String text(byte[] docx) throws Exception {
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(docx)); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
            return x.getText().replace(' ', ' ');
        }
    }

    @Test
    void clientOnlyWithFlag_requestNumberAndContactNever() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Аппарат DOC", "ZZDOCBRAND", 1, null, null, null)), LinesMode.APPEND);
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Клиника Документ");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Контакт Документ");
        service.updateField(t.getId(), PrivateRequestField.DELIVERY_ADDRESS, "г. Уральск");
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist();

        String hidden = text(documents.render(draft(t, lot, d, false)).bytes());
        assertThat(hidden).contains("ZZ Аппарат DOC (ZZDOCBRAND)").contains(d.getName())
                .doesNotContain("ZZ Клиника Документ").doesNotContain("ZZ Контакт Документ").doesNotContain(t.getTenderNumber());

        String shown = text(documents.render(draft(t, lot, d, true)).bytes());
        assertThat(shown).contains("Конечный заказчик: ZZ Клиника Документ, место поставки: г. Уральск")
                .doesNotContain("ZZ Контакт Документ").doesNotContain(t.getTenderNumber());
    }

    @Test
    void tenderNumberAndCustomerNeverInDocument() throws Exception {
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-17295275-1").status("ACTIVE")
                .source(Source.PUBLIC_TENDER).customerName("ZZ ГКП Областная больница").build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Монитор пациента")
                .quantity(3).requiredSpec("Экран не менее 12 дюймов").build());

        // в тендерах «Указать клиента» нет: канал ставит discloseClient = false всегда (спека §6.6)
        String text = text(documents.render(draft(t, lot, dist(), false)).bytes());

        assertThat(text).contains("ZZ Монитор пациента").contains("Экран не менее 12 дюймов")
                .doesNotContain("ZZ-17295275-1").doesNotContain("ZZ ГКП Областная больница");
    }

    @Test
    void longSpecIsTrimmedLikeTheLetter() throws Exception {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Длинное ТЗ", null, 1, null, "Характеристика ".repeat(400), null)),
                LinesMode.APPEND);

        String text = text(documents.render(draft(t, t.getLots().get(0), dist(), false)).bytes());

        assertThat(text).contains("(полное ТЗ — по запросу)");
        assertThat(text.length()).isLessThan(4000);
    }

    @Test
    void fileNameHasNumberDateAndCompanyWithoutQuotes() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Файл", null, 1, null, null, null)), LinesMode.APPEND);

        String name = documents.render(draft(t, t.getLots().get(0), dist(), false)).fileName();

        assertThat(name).matches("Запрос КП № — от \\d{2}\\.\\d{2}\\.\\d{4} — [^\"«»]+\\.docx");
    }
}
```

- [ ] **Step 7: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.service.RfqDocumentServiceTest'`
Expected: FAIL — `compileTestJava`: нет `RfqDocumentService`.

- [ ] **Step 8: `RfqDocumentService`**

`src/main/java/com/vladoose/nir/service/RfqDocumentService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.service.document.KpDocxRenderer;
import com.vladoose.nir.service.document.RfqDocumentBuilder;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Запрос цен на бланке (спека 2026-10-12 §6.4) из запроса — сохранённого («Исх. № <id>», дата создания запроса) или
 * черновика без id («№ —», сегодня). Позиции — по тем же правилам, что строки письма: модель с РУ или лот с брендом,
 * подтверждённым РУ и санитизированным ТЗ той же длины. Печать — по отметке запроса, если она загружена.
 */
@Service
public class RfqDocumentService {

    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public record RfqFile(String fileName, byte[] bytes) {}

    private final CompanyProfileService profiles;
    private final EmailTemplateRepository templates;
    private final RfqDocumentBuilder builder;
    private final KpDocxRenderer renderer;
    private final String replyTo;

    public RfqDocumentService(CompanyProfileService profiles, EmailTemplateRepository templates, RfqDocumentBuilder builder,
                              KpDocxRenderer renderer, @Value("${mail.kp.reply-to:}") String replyTo,
                              @Value("${spring.mail.username:}") String from) {
        this.profiles = profiles;
        this.templates = templates;
        this.builder = builder;
        this.renderer = renderer;
        this.replyTo = replyTo != null && !replyTo.isBlank() ? replyTo.trim() : (from == null ? "" : from.trim());
    }

    public RfqFile render(PriceRequest pr) {
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        CompanyProfile profile = profiles.forMarket(market);
        RfqTexts texts = RfqTexts.of(templates.findByMarket(market).orElse(null), market);
        LocalDate date = pr.getCreatedAt() != null
                ? pr.getCreatedAt().atZoneSameInstant(market.zone()).toLocalDate()
                : LocalDate.now(market.zone());
        String client = pr.isDiscloseClient()
                ? RfqClientLine.render(texts.clientLine(), pr.getTender(), profile.getShortName())
                : null;
        Distributor d = pr.getDistributor();
        List<RfqDocumentBuilder.Line> lines = pr.getItems().stream().map(RfqDocumentService::line).toList();
        byte[] bytes = renderer.render(builder.build(new RfqDocumentBuilder.Input(profile, pr.getId(), date, d.getName(),
                contact(d), texts, client, lines, replyTo, pr.getReplyBy(), pr.isWithStamp())));
        return new RfqFile(fileName(pr.getId(), date, profile), bytes);
    }

    /** Строка документа — по правилам строки письма (KpEmailComposer.buildPositions). */
    static RfqDocumentBuilder.Line line(PriceRequestItem it) {
        TenderLot lot = it.getTenderLot();
        String qty = it.getRequestedQuantity() == null ? "уточняется" : String.valueOf(it.getRequestedQuantity());
        String unit = blank(lot.getUnit()) ? "шт" : lot.getUnit().trim();
        MedEquipment eq = it.getMedEquipment();
        if (eq != null) {
            String reg = eq.getRegistration() == null ? null : "РУ № " + eq.getRegistration().getRegNumber();
            return new RfqDocumentBuilder.Line(eq.getName() + (blank(eq.getManufact()) ? "" : " (" + eq.getManufact().trim() + ")"),
                    null, qty, unit, reg);
        }
        String name = lot.getEquipName() + (blank(lot.getManufact()) ? "" : " (" + lot.getManufact().trim() + ")");
        String reg = lot.getRegStatus() == LineRegStatus.CONFIRMED && lot.getRegNumber() != null
                ? "РУ № " + lot.getRegNumber() : null;
        String spec = LotDescriptiveText.requirementsForEmail(lot.getRequiredSpec());
        return new RfqDocumentBuilder.Line(name, blank(spec) ? null : KpEmailComposer.trimSpec(spec), qty, unit, reg);
    }

    /** «Запрос КП № 534 от 12.10.2026 — West-Med.docx»: компания — надпись бренда без кавычек, иначе краткое название. */
    static String fileName(Long id, LocalDate date, CompanyProfile p) {
        String company = !blank(p.getBrandText()) ? p.getBrandText() : p.getShortName();
        company = company == null ? "" : company.replaceAll("[\"«»]", "").replaceAll("\\s+", " ").trim();
        return "Запрос КП № " + (id == null ? "—" : id) + " от " + DocFormat.date(date)
                + (company.isEmpty() ? "" : " — " + company) + ".docx";
    }

    private static String contact(Distributor d) {
        String s = String.join(" ", nz(d.getLastName()), nz(d.getFirstName()), nz(d.getMiddleName())).replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 9: Запустить тесты задачи**

Run: `./gradlew test --tests 'com.vladoose.nir.service.RfqDocumentServiceTest' --tests 'com.vladoose.nir.clientoffer.RfqDocumentBuilderTest'`
Expected: PASS (4 + 4).

- [ ] **Step 10: Мутация — характеристики без санитизации**

Скопировать `RfqDocumentService.java`, заменить `LotDescriptiveText.requirementsForEmail(lot.getRequiredSpec())` на `lot.getRequiredSpec()`, а `KpEmailComposer.trimSpec(spec)` на `spec`; прогнать `RfqDocumentServiceTest`.
Expected: FAIL `longSpecIsTrimmedLikeTheLetter`. Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 11: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/document/KpDocumentBuilder.java src/main/java/com/vladoose/nir/service/document/RfqDocumentBuilder.java src/main/java/com/vladoose/nir/service/RfqDocumentService.java src/test/java/com/vladoose/nir/clientoffer/RfqDocumentBuilderTest.java src/test/java/com/vladoose/nir/service/RfqDocumentServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): запрос цен на фирменном бланке (Word) на рендерере КП

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Канал отправки — настройки запроса, вложение, вписанная почта, «Переслать» с настройками; опции КП тендера на сервере

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestSendService.java` (переписать)
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestWriter.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java` (две строки — до задачи 7)
- Test: `src/test/java/com/vladoose/nir/service/PriceRequestSendOptionsTest.java`

**Interfaces:**
- Consumes: `EmailAddresses` и `EmailService.sendEmail(List,…,List<Attachment>)` (задача 2), `RfqDocumentService` (задача 5), поля `PriceRequest` (задача 1).
- Produces:
  - `PriceRequestSendService.SendOptions(LocalDate replyBy, boolean withDocument, boolean withStamp, boolean discloseClient, List<String> emailTo)` (без документа печать сбрасывается), `SendOptions.NONE`, `SendOptions.replyBy(LocalDate)`;
  - `PriceRequestSendService.draft(Long tenderId, Distributor supplier, List<SendItem> items, SendOptions) → PriceRequest`, `preview(PriceRequest draft) → KpPreviewResponse`, `preview(Long, List<Long>, List<SendItem>, SendOptions)`, `requireStamp(Market)`;
  - `PriceRequestWriter.persist(Tender, Distributor, List<Line>, SendOptions)`;
  - `PriceRequestSendRequest.replyBy` (`LocalDate`), `withDocument`, `withStamp` (`Boolean`).

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/service/PriceRequestSendOptionsTest.java`:

```java
package com.vladoose.nir.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.clientoffer.KpTestSupportAccess;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.PriceRequestController;
import com.vladoose.nir.dto.request.PriceRequestSendRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Настройки запроса в едином канале (спека 2026-10-12 §6.2–§6.6) — сквозь GreenMail. */
@SmtpIntegrationTest
class PriceRequestSendOptionsTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired PriceRequestSendService sendService;
    @Autowired PriceRequestController controller;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired CompanyProfileRepository profileRepository;

    Tender tender;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tender = tenderRepository.save(Tender.builder().tenderNumber("ZZ-OPT-1").status("ACTIVE")
                .source(Source.PUBLIC_TENDER).build());
        lot = tenderLotRepository.save(TenderLot.builder().tender(tender).lotNumber(1).equipName("ZZ Облучатель")
                .quantity(2).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String email) {
        return distributorRepository.save(Distributor.builder().name("ZZ Опции " + System.nanoTime()).email(email).build());
    }

    private List<SendItem> items() {
        return List.of(new SendItem(lot.getId(), null, 2));
    }

    private List<SendResult> send(Distributor d, SendOptions o) {
        return sendService.send(tender.getId(), List.of(d.getId()), items(), null, null, o);
    }

    private void stamp(byte[] png) {
        CompanyProfile p = profileRepository.findByMarket(Market.KZ).orElseThrow();
        p.setStampPng(png);
        p.setSignaturePng(png);
        profileRepository.saveAndFlush(p);
    }

    static String text(Part p) throws Exception {
        if (p.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return (String) p.getContent();
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = text(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    static Part attachment(Part p) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return p;
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                Part a = attachment(mp.getBodyPart(i));
                if (a != null) return a;
            }
        }
        return null;
    }

    static int anchors(Part docxPart) throws Exception {
        try (XWPFDocument d = new XWPFDocument(docxPart.getInputStream())) {
            List<XWPFParagraph> all = new ArrayList<>(d.getParagraphs());
            for (XWPFTable t : d.getTables()) {
                for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) all.addAll(c.getParagraphs());
            }
            int n = 0;
            for (XWPFParagraph p : all) {
                for (XWPFRun r : p.getRuns()) for (CTDrawing dr : r.getCTR().getDrawingList()) n += dr.sizeOfAnchorArray();
            }
            return n;
        }
    }

    @Test
    void documentIsAttachedAndMentioned() throws Exception {
        SendResult r = send(dist("doc@opt.kz"), new SendOptions(null, true, false, false, null)).get(0);

        assertThat(r.emailSent()).isTrue();
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(text(m)).contains("Запрос на бланке компании — во вложении.");
        Part a = attachment(m);
        assertThat(MimeUtility.decodeText(a.getFileName())).startsWith("Запрос КП № " + r.priceRequestId() + " от ").endsWith(".docx");
        assertThat(a.getContentType()).startsWith(RfqDocumentService.DOCX);
        try (XWPFDocument docx = new XWPFDocument(a.getInputStream()); XWPFWordExtractor x = new XWPFWordExtractor(docx)) {
            assertThat(x.getText()).contains("Исх. № " + r.priceRequestId()).contains("ZZ Облучатель").doesNotContain("ZZ-OPT-1");
        }
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().isWithDocument()).isTrue();
    }

    @Test
    void noDocumentByDefault() throws Exception {
        send(dist("plain@opt.kz"), SendOptions.NONE);
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getContent()).isInstanceOf(String.class);
        assertThat((String) m.getContent()).doesNotContain("во вложении");
    }

    @Test
    void severalCardAddressesAllGetTheLetter() {
        SendResult r = send(dist("a@opt.kz, b@opt.kz"), SendOptions.NONE).get(0);
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().getSentTo()).isEqualTo("a@opt.kz, b@opt.kz");
    }

    @Test
    void typedAddressOverridesCard_resendGoesThereAgain() throws Exception {
        Distributor d = dist("card@opt.kz");
        SendResult r = send(d, new SendOptions(null, false, false, false, List.of("typed@opt.kz"))).get(0);

        PriceRequest pr = priceRequestRepository.findById(r.priceRequestId()).orElseThrow();
        assertThat(pr.isEmailOverride()).isTrue();
        assertThat(pr.getSentTo()).isEqualTo("typed@opt.kz");
        assertThat(distributorRepository.findById(d.getId()).orElseThrow().getEmail()).isEqualTo("card@opt.kz");

        greenMail.purgeEmailFromAllMailboxes();
        assertThat(sendService.resend(r.priceRequestId()).emailSent()).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(greenMail.getReceivedMessages()[0].getAllRecipients()[0].toString()).isEqualTo("typed@opt.kz");
    }

    @Test
    void resendRepeatsSettingsAndUsesTheFixedCardAddress() throws Exception {
        Distributor d = dist("old@opt.kz");
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(2);
        SendResult r = send(d, new SendOptions(replyBy, true, false, false, null)).get(0);
        d.setEmail("fixed@opt.kz");
        distributorRepository.saveAndFlush(d);
        greenMail.purgeEmailFromAllMailboxes();

        assertThat(sendService.resend(r.priceRequestId()).emailSent()).isTrue();

        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("fixed@opt.kz");
        assertThat(text(m)).contains("Просим ответить до " + DATE.format(replyBy)).contains("во вложении");
        assertThat(attachment(m)).isNotNull();
    }

    @Test
    void stampWithoutLoadedStampIs400BeforeAnythingIsSaved() {
        stamp(null);
        long before = priceRequestRepository.count();
        Distributor d = dist("s@opt.kz");
        assertThatThrownBy(() -> send(d, new SendOptions(null, true, true, false, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Печать не загружена");
        assertThat(priceRequestRepository.count()).isEqualTo(before);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void resendAfterStampDeletedSendsDocumentWithoutStamp() throws Exception {
        stamp(KpTestSupportAccess.circlePng());
        SendResult r = send(dist("st@opt.kz"), new SendOptions(null, true, true, false, null)).get(0);
        assertThat(anchors(attachment(greenMail.getReceivedMessages()[0]))).isEqualTo(2);

        stamp(null);
        greenMail.purgeEmailFromAllMailboxes();
        assertThat(sendService.resend(r.priceRequestId()).emailSent()).isTrue();
        assertThat(anchors(attachment(greenMail.getReceivedMessages()[0]))).isZero();
    }

    @Test
    void stampWithoutDocumentIsIgnored() {
        stamp(null);   // печати нет, но и документа нет — отметка печати ни на что не влияет
        SendResult r = send(dist("nd@opt.kz"), new SendOptions(null, false, true, false, null)).get(0);
        assertThat(r.emailSent()).isTrue();
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().isWithStamp()).isFalse();
    }

    @Test
    void malformedCardEmailIsFailedNotCrash() {
        SendResult r = send(dist("broken@kz"), SendOptions.NONE).get(0);
        assertThat(r.emailSent()).isFalse();
        assertThat(r.reason()).isEqualTo(PriceRequestSendService.REASON_SEND_FAILED);
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().getLetterStatus())
                .isEqualTo(LetterStatus.FAILED);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void smtpFailureKeepsTheAttemptedAddress() {
        Distributor d = dist("c@opt.kz");
        greenMail.stop();
        try {
            SendResult r = send(d, new SendOptions(null, false, false, false, List.of("typed2@opt.kz"))).get(0);
            assertThat(r.reason()).isEqualTo(PriceRequestSendService.REASON_SEND_FAILED);
            PriceRequest pr = priceRequestRepository.findById(r.priceRequestId()).orElseThrow();
            assertThat(pr.getLetterStatus()).isEqualTo(LetterStatus.FAILED);
            assertThat(pr.getSentTo()).isEqualTo("typed2@opt.kz");
            assertThat(pr.isEmailOverride()).isTrue();
        } finally {
            greenMail.start();
        }
    }

    @Test
    void pastReplyByIs400() {
        Distributor d = dist("p@opt.kz");
        assertThatThrownBy(() -> send(d, SendOptions.replyBy(LocalDate.now(Market.KZ.zone()).minusDays(1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже прошла");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void tenderPanelOptionsReachTheLetter() throws Exception {
        PriceRequestSendRequest req = new PriceRequestSendRequest();
        req.setTenderId(tender.getId());
        req.setDistributorIds(List.of(dist("panel@opt.kz").getId()));
        PriceRequestSendRequest.Item it = new PriceRequestSendRequest.Item();
        it.setTenderLotId(lot.getId());
        it.setRequestedQuantity(2);
        req.setItems(List.of(it));
        LocalDate replyBy = LocalDate.now(Market.KZ.zone()).plusDays(3);
        req.setReplyBy(replyBy);
        req.setWithDocument(true);

        assertThat(controller.preview(req).getBody()).contains("во вложении").contains("Просим ответить до " + DATE.format(replyBy));
        controller.send(req);

        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(attachment(m)).isNotNull();
        assertThat(text(m)).contains("Просим ответить до " + DATE.format(replyBy));
    }
}
```

Тест печати берёт круг из помощников документа КП. `KpTestSupport` — пакетный класс пакета `clientoffer`, поэтому рядом с ним завести открытый проход:

`src/test/java/com/vladoose/nir/clientoffer/KpTestSupportAccess.java`:

```java
package com.vladoose.nir.clientoffer;

/** Открытый доступ к картинкам KpTestSupport для тестов других пакетов (печать в документе запроса). */
public final class KpTestSupportAccess {

    private KpTestSupportAccess() {}

    public static byte[] circlePng() {
        return KpTestSupport.circlePng();
    }
}
```

- [ ] **Step 2: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.service.PriceRequestSendOptionsTest'`
Expected: FAIL — `compileTestJava`: нет конструктора `SendOptions` с пятью аргументами, `setReplyBy` у DTO.

- [ ] **Step 3: `PriceRequestWriter.persist` с настройками**

В `PriceRequestWriter.java` заменить два метода `persist` на:

```java
    /** Создаёт один PriceRequest (SENT) с позициями и коммитит. Письмо шлётся вызывающим уже после коммита. */
    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines) {
        return persist(tender, dist, lines, PriceRequestSendService.SendOptions.NONE);
    }

    /** Запрос помнит свои настройки — «↻ Переслать» повторяет письмо в точности (спека 2026-10-12 §6.5). */
    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines, PriceRequestSendService.SendOptions options) {
        PriceRequestSendService.SendOptions o = options == null ? PriceRequestSendService.SendOptions.NONE : options;
        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status("SENT")
                .sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .replyBy(o.replyBy())
                .discloseClient(o.discloseClient())
                .withDocument(o.withDocument())
                .withStamp(o.withStamp())
                .emailOverride(o.emailTo() != null && !o.emailTo().isEmpty())
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
```

Импорт `java.time.LocalDate` стал лишним — убрать.

- [ ] **Step 4: `PriceRequestSendService` — переписать**

`src/main/java/com/vladoose/nir/service/PriceRequestSendService.java` — заменить целиком:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.util.EmailAddresses;
import com.vladoose.nir.util.KpToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Единый канал отправки запросов КП: на каждого поставщика создаётся PriceRequest (SENT) с позициями (лот + опционально
 * модель) и уходит письмо (KpEmailComposer + EmailService), по отметке — с запросом на бланке (Word, RfqDocumentService).
 * Запись КП — в отдельном @Transactional-бине ({@link PriceRequestWriter}); письмо шлётся ПОСЛЕ коммита (сеть вне
 * транзакции, §6). Ошибка/отсутствие email НЕ валит запись — отражается флагом в SendResult. Запрос помнит свои
 * настройки, и «↻ Переслать» повторяет письмо в точности (спека 2026-10-12 §6.5).
 */
@Service
public class PriceRequestSendService {

    private static final Logger log = LoggerFactory.getLogger(PriceRequestSendService.class);

    public static final String REASON_NO_EMAIL = "NO_EMAIL";
    public static final String REASON_SEND_FAILED = "SEND_FAILED";

    public record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity) {}
    public record SendResult(Long priceRequestId, Long distributorId, String distributorName,
                             boolean emailSent, String reason) {}

    /**
     * Настройки письма запроса цен (спека 2026-10-12 §6): «Ответить до»; запрос на бланке (Word) и подпись с печатью в
     * нём (без документа печати нет); клиент указан поставщику; адрес. emailTo: null — почта из карточки поставщика;
     * пустой список — письма нет (поле почты в окне очищено); иначе — вписанные адреса (у запроса email_override).
     */
    public record SendOptions(LocalDate replyBy, boolean withDocument, boolean withStamp, boolean discloseClient,
                              List<String> emailTo) {

        public static final SendOptions NONE = new SendOptions(null, false, false, false, null);

        public SendOptions {
            withStamp = withDocument && withStamp;
            emailTo = emailTo == null ? null : List.copyOf(emailTo);
        }

        public static SendOptions replyBy(LocalDate replyBy) {
            return new SendOptions(replyBy, false, false, false, null);
        }
    }

    private final TenderService tenderService;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;
    private final PriceRequestWriter writer;
    private final KpEmailComposer composer;
    private final EmailService emailService;
    private final PriceRequestRepository priceRequestRepository;
    private final CompanyProfileService profiles;
    private final RfqDocumentService documents;

    public PriceRequestSendService(TenderService tenderService,
                                   TenderLotService tenderLotService,
                                   MedEquipmentService medEquipmentService,
                                   DistributorService distributorService,
                                   PriceRequestWriter writer,
                                   KpEmailComposer composer,
                                   EmailService emailService,
                                   PriceRequestRepository priceRequestRepository,
                                   CompanyProfileService profiles,
                                   RfqDocumentService documents) {
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.writer = writer;
        this.composer = composer;
        this.emailService = emailService;
        this.priceRequestRepository = priceRequestRepository;
        this.profiles = profiles;
        this.documents = documents;
    }

    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride) {
        return send(tenderId, distributorIds, items, subjectOverride, bodyOverride, SendOptions.NONE);
    }

    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride, SendOptions options) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        SendOptions o = options == null ? SendOptions.NONE : options;

        // findById == em.find обходит hibernate-фильтр рынка → явные гарды от чужого рынка (как в setProposedEquipment)
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        checkReplyBy(o.replyBy());
        if (o.withStamp()) requireStamp(market(tender));
        List<PriceRequestWriter.Line> lines = lines(tenderId, items);

        List<SendResult> results = new ArrayList<>();
        for (Long distId : new LinkedHashSet<>(distributorIds)) { // дедуп: один поставщик — одно КП/письмо
            Distributor dist = distributorService.findById(distId);
            requireCurrentMarket(dist.getMarket(), "Поставщик не найден: " + distId);
            PriceRequest pr = writer.persist(tender, dist, lines, o); // коммит записи
            results.add(dispatch(pr, dist, subjectOverride, bodyOverride, o.emailTo())); // письмо ПОСЛЕ коммита
        }
        return results;
    }

    /**
     * Переслать письмо существующего КП с его настройками (спека §6.5). Вписанная почта — туда же (она в sent_to); иначе —
     * на почту из карточки: исправили адрес после возврата — письмо пойдёт на новый. Новый PR не создаётся.
     */
    public SendResult resend(Long priceRequestId) {
        PriceRequest pr = priceRequestRepository.findById(priceRequestId)
                .orElseThrow(() -> new NotFoundException("КП не найден: " + priceRequestId));
        requireCurrentMarket(pr.getMarket(), "КП не найден: " + priceRequestId);
        List<String> to = pr.isEmailOverride() ? EmailAddresses.parse(pr.getSentTo()) : null;
        return dispatch(pr, pr.getDistributor(), null, null, to);
    }

    /** «Подпись и печать» без загруженной печати — 400 до первой записи (спека §6.2: галочка недоступна, пока печати нет). */
    public void requireStamp(Market market) {
        if (profiles.forMarket(market).getStampPng() == null) {
            throw new BadRequestException("Печать не загружена — загрузите её в «Реквизитах и печати» или снимите «Подпись и печать»");
        }
    }

    private void requireCurrentMarket(Market market, String notFoundMessage) {
        if (market != null && market != MarketContext.get()) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    private static Market market(Tender tender) {
        return tender.getMarket() != null ? tender.getMarket() : MarketContext.get();
    }

    private static void checkReplyBy(LocalDate replyBy) {
        if (replyBy != null && replyBy.isBefore(LocalDate.now(MarketContext.get().zone()))) {
            throw new BadRequestException("Дата «Ответить до» уже прошла");
        }
    }

    /** Позиции запроса: лот этого тендера, модель своего рынка, количество — пусто или не меньше 1. */
    private List<PriceRequestWriter.Line> lines(Long tenderId, List<SendItem> items) {
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        List<PriceRequestWriter.Line> lines = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            // пусто — у лота нет количества (СК-Фармация, «приказ ЕД»): поставщику «кол-во уточняется», а не «1 шт.» (B4)
            if (si.requestedQuantity() != null && si.requestedQuantity() < 1) {
                throw new BadRequestException("Количество в позиции должно быть не меньше 1");
            }
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = null;
            if (si.medEquipmentId() != null) {
                eq = medEquipmentService.findById(si.medEquipmentId());
                requireCurrentMarket(eq.getMarket(), "Оборудование не найдено: " + si.medEquipmentId());
            }
            lines.add(new PriceRequestWriter.Line(lot, eq, si.requestedQuantity()));
        }
        return lines;
    }

    private SendResult dispatch(PriceRequest pr, Distributor dist, String subjectOverride, String bodyOverride,
                                List<String> emailTo) {
        List<String> to;
        try {
            to = emailTo != null ? emailTo : EmailAddresses.parse(dist.getEmail());
        } catch (BadRequestException e) {
            // в карточке адрес, который не разобрать (старые данные): письма нет, запрос сохранён — «письмо не ушло»
            log.warn("КП id={}: почту в карточке поставщика «{}» не разобрать — письмо не отправлено", pr.getId(), dist.getName());
            letter(pr, LetterStatus.FAILED, null);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
        if (to.isEmpty()) {
            log.warn("КП id={} создан, но у поставщика «{}» нет почты — письмо не отправлено", pr.getId(), dist.getName());
            letter(pr, LetterStatus.NO_EMAIL, null);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_NO_EMAIL);
        }
        String address = String.join(", ", to);
        try {
            KpEmailComposer.Composed def = composer.compose(pr);
            // токен [КП-id] ВСЕГДА серверный: при override человеческой части клеим его отдельно
            String subject = (subjectOverride != null && !subjectOverride.isBlank())
                    ? KpToken.subjectToken(pr.getId()) + " " + subjectOverride.trim()
                    : def.subject();
            String body = (bodyOverride != null && !bodyOverride.isBlank()) ? bodyOverride : def.body();
            List<EmailService.Attachment> files = new ArrayList<>();
            if (pr.isWithDocument()) {
                RfqDocumentService.RfqFile doc = documents.render(pr);
                files.add(new EmailService.Attachment(doc.fileName(), doc.bytes(), RfqDocumentService.DOCX));
            }
            emailService.sendEmail(to, subject, body, files);
            letter(pr, LetterStatus.SENT, address);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), true, null);
        } catch (Exception ex) {
            // адрес — персональные данные: в лог только домены (спека §10); текст исключения SMTP может нести адрес
            log.warn("Не удалось отправить КП id={} ({}): {}. Запрос сохранён в БД.", pr.getId(), EmailAddresses.domains(to),
                    ex.getClass().getSimpleName());
            // адрес попытки остаётся в sent_to: «↻ Переслать» вписанной почты после сбоя знает, куда слать
            letter(pr, LetterStatus.FAILED, address);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
    }

    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items) {
        return preview(tenderId, distributorIds, items, SendOptions.NONE);
    }

    /** Черновой предпросмотр письма (образец по первому поставщику, PriceRequest НЕ сохраняется, тема без токена). */
    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items, SendOptions options) {
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        Distributor sample = distributorService.findById(distributorIds.get(0));
        requireCurrentMarket(sample.getMarket(), "Поставщик не найден");
        return preview(draft(tenderId, sample, items, options));
    }

    public KpPreviewResponse preview(PriceRequest draft) {
        KpEmailComposer.Composed c = composer.composeForPreview(draft);
        return new KpPreviewResponse(c.subject(), c.body());
    }

    /**
     * Запрос без сохранения: id нет — тема письма без метки, в документе «№ —». Для предпросмотра письма и документа;
     * поставщик — проверенный вызывающим или ещё не заведённый (новый из окна заявки).
     */
    public PriceRequest draft(Long tenderId, Distributor supplier, List<SendItem> items, SendOptions options) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        SendOptions o = options == null ? SendOptions.NONE : options;
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        List<PriceRequestItem> prItems = new ArrayList<>();
        for (PriceRequestWriter.Line l : lines(tenderId, items)) {
            prItems.add(PriceRequestItem.builder().tenderLot(l.lot()).medEquipment(l.equipment())
                    .requestedQuantity(l.qty()).build());
        }
        return PriceRequest.builder().tender(tender).distributor(supplier).market(tender.getMarket()).items(prItems)
                .replyBy(o.replyBy()).discloseClient(o.discloseClient()).withDocument(o.withDocument())
                .withStamp(o.withStamp()).emailOverride(o.emailTo() != null && !o.emailTo().isEmpty()).build();
    }

    /** Исход письма в запрос; сбой записи не прячет результат отправки — только предупреждение в лог. */
    private void letter(PriceRequest pr, LetterStatus status, String sentTo) {
        try {
            writer.markLetter(pr.getId(), status, sentTo);
        } catch (RuntimeException e) {
            log.warn("КП id={}: исход письма {} не записан: {}", pr.getId(), status, e.getClass().getSimpleName());
        }
    }
}
```

- [ ] **Step 5: DTO и контроллер КП тендера**

В `PriceRequestSendRequest.java` — импорт `java.time.LocalDate` и после `bodyOverride`:

```java
    /** Опции панели КП тендера (спека 2026-10-12 §6.6); «Указать клиента» в тендерах нет. */
    private LocalDate replyBy;
    private Boolean withDocument;
    private Boolean withStamp;
```

В `PriceRequestController.java` — импорт `com.vladoose.nir.service.PriceRequestSendService.SendOptions`; в `send` последним аргументом `sendService.send(…)` — `options(req)`; в `preview` — `return sendService.preview(req.getTenderId(), req.getDistributorIds(), items, options(req));`; метод:

```java
    /** Опции панели КП тендера; клиента в тендерах не раскрываем (прежнее решение против утечки, спека §6.6). */
    private static SendOptions options(PriceRequestSendRequest req) {
        return new SendOptions(req.getReplyBy(), Boolean.TRUE.equals(req.getWithDocument()),
                Boolean.TRUE.equals(req.getWithStamp()), false, null);
    }
```

- [ ] **Step 6: Окно заявки — до задачи 7**

В `PrivateRequestRfqService.java` обе строки `new SendOptions(req.getReplyBy())` заменить на `SendOptions.replyBy(req.getReplyBy())`.

- [ ] **Step 7: Запустить тесты задачи и соседей**

Run: `./gradlew test --tests 'com.vladoose.nir.service.PriceRequestSendOptionsTest' --tests 'com.vladoose.nir.service.PriceRequestSendServiceTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqTest' --tests 'com.vladoose.nir.mail.*'`
Expected: PASS (12 новых и прежние). Если `smtpFailureKeepsTheAttemptedAddress` валит СЛЕДУЮЩИЙ тест класса (GreenMail не поднялся после `start()`), — записать `Task 6: Ruling` и перенести тест в конец класса через `@TestMethodOrder` или в отдельный класс.

- [ ] **Step 8: Мутации**

По одной, откат копией файла:
1) В `dispatch` удалить блок `if (pr.isWithDocument()) {…}` — FAIL `documentIsAttachedAndMentioned`, `resendRepeatsSettingsAndUsesTheFixedCardAddress`.
2) В `resend` заменить `pr.isEmailOverride() ? EmailAddresses.parse(pr.getSentTo()) : null` на `null` — FAIL `typedAddressOverridesCard_resendGoesThereAgain`.
3) В `catch (Exception ex)` поставить `letter(pr, LetterStatus.FAILED, null)` — FAIL `smtpFailureKeepsTheAttemptedAddress`.

После серии — `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/PriceRequestSendService.java src/main/java/com/vladoose/nir/service/PriceRequestWriter.java src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java src/main/java/com/vladoose/nir/controller/PriceRequestController.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java src/test/java/com/vladoose/nir/service/PriceRequestSendOptionsTest.java src/test/java/com/vladoose/nir/clientoffer/KpTestSupportAccess.java
git commit -m "$(cat <<'EOF'
feat(price-request): запрос помнит настройки — Word, печать, клиент, вписанная почта; «Переслать» с ними

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Окно «Запросить цены» заявки — сервер

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/RfqSendRequest.java`, `RfqPreviewRequest.java`
- Modify: `src/main/java/com/vladoose/nir/repository/DistributorRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java` (переписать)
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRfqStage2Test.java`

**Interfaces:**
- Consumes: `SendOptions`, `draft`, `preview(PriceRequest)`, `requireStamp` (задача 6); `RfqDocumentService` (задача 5); `RfqClientLine.clientName` (задача 4); `EmailAddresses` (задача 2).
- Produces:
  - `RfqSendRequest`: `withDocument`, `withStamp`; `Supplier.distributorId` (необязателен), `Supplier.newSupplier{name, rememberBrands}`, `Supplier.email` (`null` — карточка, пусто — без письма), `Supplier.discloseClient`;
  - `RfqPreviewRequest`: `distributorId` или `newSupplierName`, `discloseClient`, `withDocument`, `withStamp`;
  - `POST /api/private-requests/{id}/rfq/document` → Word (`attachment`, RFC 5987, `nosniff`, `no-store`);
  - `DistributorRepository.findIdAndMarketByName(String) → List<Object[]>` (id, market; все рынки).

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRfqStage2Test.java`:

```java
package com.vladoose.nir.privaterequest;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestRfqService;
import com.vladoose.nir.support.SmtpIntegrationTest;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Окно «Запросить цены» этапа 2 (спека 2026-10-12 §6.2): почта в окне, новый поставщик, клиент, документ. */
@SmtpIntegrationTest
class PrivateRequestRfqStage2Test {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestRfqService rfq;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired CompanyProfileRepository profileRepository;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name, String email) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email(email).build());
    }

    private Tender request(String brand) {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Облучатель окна", brand, 2, null, null, null)), LinesMode.APPEND);
        return t;
    }

    private static List<Long> lotIds(Tender t) {
        return t.getLots().stream().map(TenderLot::getId).toList();
    }

    private static RfqSendRequest.Supplier existing(Distributor d, Tender t, String email, boolean disclose) {
        RfqSendRequest.Supplier s = new RfqSendRequest.Supplier();
        s.setDistributorId(d.getId());
        s.setEmail(email);
        s.setDiscloseClient(disclose);
        s.setLotIds(lotIds(t));
        return s;
    }

    private static RfqSendRequest.Supplier fresh(String name, Tender t, String email, boolean remember) {
        RfqSendRequest.NewSupplier n = new RfqSendRequest.NewSupplier();
        n.setName(name);
        n.setRememberBrands(remember);
        RfqSendRequest.Supplier s = new RfqSendRequest.Supplier();
        s.setNewSupplier(n);
        s.setEmail(email);
        s.setLotIds(lotIds(t));
        return s;
    }

    private static RfqSendRequest send(boolean withDocument, RfqSendRequest.Supplier... suppliers) {
        RfqSendRequest r = new RfqSendRequest();
        r.setWithDocument(withDocument);
        r.setSuppliers(List.of(suppliers));
        return r;
    }

    private static MimeMessage mailTo(String to) throws Exception {
        for (MimeMessage m : greenMail.getReceivedMessages()) {
            if (m.getAllRecipients()[0].toString().equals(to)) return m;
        }
        throw new AssertionError("нет письма для " + to);
    }

    private static String body(Part p) throws Exception {
        if (p.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return (String) p.getContent();
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = body(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static String document(Part p) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return docxText(p.getInputStream());
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = document(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static String docxText(InputStream in) throws Exception {
        try (XWPFDocument d = new XWPFDocument(in); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
            return x.getText().replace(' ', ' ');
        }
    }

    @Test
    void typedEmailFillsEmptyCard_changedEmailOverridesWithoutTouchingCard() throws Exception {
        Tender t = request("ZZRFQ2A");
        Distributor empty = dist("ZZ Пустая карточка", null);
        Distributor full = dist("ZZ Полная карточка", "b@rfq2.kz");

        List<SendResult> r = rfq.send(t.getId(), send(false,
                existing(empty, t, "a@rfq2.kz", false), existing(full, t, "other@rfq2.kz", false)));

        assertThat(r).extracting(SendResult::emailSent).containsExactly(true, true);
        assertThat(distributorRepository.findById(empty.getId()).orElseThrow().getEmail()).isEqualTo("a@rfq2.kz");
        assertThat(priceRequestRepository.findById(r.get(0).priceRequestId()).orElseThrow().isEmailOverride()).isFalse();
        PriceRequest overridden = priceRequestRepository.findById(r.get(1).priceRequestId()).orElseThrow();
        assertThat(overridden.isEmailOverride()).isTrue();
        assertThat(overridden.getSentTo()).isEqualTo("other@rfq2.kz");
        assertThat(distributorRepository.findById(full.getId()).orElseThrow().getEmail()).isEqualTo("b@rfq2.kz");
        mailTo("a@rfq2.kz");
        mailTo("other@rfq2.kz");
    }

    @Test
    void clearedEmailSavesRequestWithoutLetter() {
        Tender t = request(null);
        Distributor d = dist("ZZ Очищено", "c@rfq2.kz");
        SendResult r = rfq.send(t.getId(), send(false, existing(d, t, "", false))).get(0);
        assertThat(r.emailSent()).isFalse();
        assertThat(priceRequestRepository.findById(r.priceRequestId()).orElseThrow().getLetterStatus())
                .isEqualTo(LetterStatus.NO_EMAIL);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(distributorRepository.findById(d.getId()).orElseThrow().getEmail()).isEqualTo("c@rfq2.kz");
    }

    @Test
    void newSupplierIsCreatedWithRememberedBrands() throws Exception {
        Tender t = request("ZZBRANDNEW");
        String name = "ZZ Новый поставщик " + System.nanoTime();

        SendResult r = rfq.send(t.getId(), send(false, fresh(name, t, "n@rfq2.kz", true))).get(0);

        Distributor created = distributorRepository.findById(r.distributorId()).orElseThrow();
        assertThat(created.getName()).isEqualTo(name);
        assertThat(created.getMarket()).isEqualTo(Market.KZ);
        assertThat(created.getEmail()).isEqualTo("n@rfq2.kz");
        assertThat(created.getBrands()).containsExactly("ZZBRANDNEW");
        assertThat(r.emailSent()).isTrue();
        mailTo("n@rfq2.kz");
    }

    @Test
    void newSupplierNameOnOtherMarketIs400_sameMarketNameIsReused() {
        Tender t = request(null);
        MarketContext.set(Market.RF);
        Distributor rf = distributorRepository.save(Distributor.builder().name("ZZ Занято РФ " + System.nanoTime()).build());
        MarketContext.set(Market.KZ);
        assertThatThrownBy(() -> rfq.send(t.getId(), send(false, fresh(rf.getName(), t, "x@rfq2.kz", false))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("уже есть на другом рынке");

        Distributor kz = dist("ZZ Есть KZ", "kz@rfq2.kz");
        long before = distributorRepository.count();
        SendResult r = rfq.send(t.getId(), send(false, fresh(kz.getName().toLowerCase(), t, null, false))).get(0);
        assertThat(r.distributorId()).isEqualTo(kz.getId());
        assertThat(distributorRepository.count()).isEqualTo(before);
    }

    @Test
    void clientOnlyForTheCheckedSupplier_inLetterAndDocument() throws Exception {
        Tender t = request(null);
        service.updateField(t.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Клиника Секретная");
        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Контакт Секретный");
        service.updateField(t.getId(), PrivateRequestField.DELIVERY_ADDRESS, "г. Уральск");
        Distributor told = dist("ZZ Доверенный", "told@rfq2.kz");
        Distributor silent = dist("ZZ Обычный", "silent@rfq2.kz");

        rfq.send(t.getId(), send(true, existing(told, t, null, true), existing(silent, t, null, false)));

        MimeMessage toTold = mailTo("told@rfq2.kz");
        assertThat(body(toTold)).contains("Конечный заказчик: ZZ Клиника Секретная, место поставки: г. Уральск")
                .doesNotContain("ZZ Контакт Секретный").doesNotContain(t.getTenderNumber());
        assertThat(document(toTold)).contains("Конечный заказчик: ZZ Клиника Секретная")
                .doesNotContain("ZZ Контакт Секретный").doesNotContain(t.getTenderNumber());
        MimeMessage toSilent = mailTo("silent@rfq2.kz");
        assertThat(body(toSilent)).doesNotContain("ZZ Клиника Секретная").doesNotContain(t.getTenderNumber());
        assertThat(document(toSilent)).doesNotContain("ZZ Клиника Секретная").doesNotContain(t.getTenderNumber());
    }

    @Test
    void discloseWithoutClientIs400() {
        Tender t = request(null);
        service.updateField(t.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Только контакт");
        Distributor d = dist("ZZ Раскрыть", "r@rfq2.kz");
        assertThatThrownBy(() -> rfq.send(t.getId(), send(false, existing(d, t, null, true))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Клиент в заявке не указан");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void invalidTypedAddressIs400BeforeAnyLetterOrRequest() {
        Tender t = request(null);
        Distributor ok = dist("ZZ Хороший", "ok@rfq2.kz");
        Distributor bad = dist("ZZ Плохой", "bad@rfq2.kz");
        long before = priceRequestRepository.count();
        assertThatThrownBy(() -> rfq.send(t.getId(), send(false,
                existing(ok, t, "ok@rfq2.kz", false), existing(bad, t, "bad@rfq2", false))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("«bad@rfq2»");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(priceRequestRepository.count()).isEqualTo(before);
    }

    @Test
    void severalTypedAddressesAllReceive() {
        Tender t = request(null);
        Distributor d = dist("ZZ Два отдела", null);
        rfq.send(t.getId(), send(false, existing(d, t, "a1@rfq2.kz; a2@rfq2.kz", false)));
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
        assertThat(distributorRepository.findById(d.getId()).orElseThrow().getEmail()).isEqualTo("a1@rfq2.kz, a2@rfq2.kz");
    }

    @Test
    void documentBeforeSendingHasDashNumber_newSupplierNameInRecipient() throws Exception {
        Tender t = request(null);
        RfqPreviewRequest p = new RfqPreviewRequest();
        p.setNewSupplierName("ZZ Ещё не заведён");
        p.setLotIds(lotIds(t));
        p.setWithDocument(true);

        var file = rfq.document(t.getId(), p);

        assertThat(file.fileName()).startsWith("Запрос КП № — от ").endsWith(".docx");
        assertThat(docxText(new ByteArrayInputStream(file.bytes()))).contains("Исх. № —").contains("ZZ Ещё не заведён")
                .contains("ZZ Облучатель окна");
        assertThat(priceRequestRepository.findAll()).noneMatch(pr -> pr.getTender().getId().equals(t.getId()));
    }

    @Test
    void stampWithoutLoadedStampIs400() {
        // предусловие — в откатываемой транзакции теста: строку реквизитов правит оператор (CLAUDE.md §14)
        CompanyProfile p = profileRepository.findByMarket(Market.KZ).orElseThrow();
        p.setStampPng(null);
        profileRepository.saveAndFlush(p);
        Tender t = request(null);
        Distributor d = dist("ZZ Печать", "st@rfq2.kz");
        RfqSendRequest r = send(true, existing(d, t, null, false));
        r.setWithStamp(true);
        long before = priceRequestRepository.count();

        assertThatThrownBy(() -> rfq.send(t.getId(), r)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Печать не загружена");
        assertThat(priceRequestRepository.count()).isEqualTo(before);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }
}
```

Ветка «печать загружена — письмо с подписью и печатью» покрыта в задаче 6 (`resendAfterStampDeletedSendsDocumentWithoutStamp`).

- [ ] **Step 2: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqStage2Test'`
Expected: FAIL — `compileTestJava`: нет `RfqSendRequest.NewSupplier`, `setEmail`, `setNewSupplierName`, `rfq.document`.

- [ ] **Step 3: DTO окна**

`RfqSendRequest.java` — заменить целиком:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** «Отправить N писем» (спека 2026-10-12 §6.2): каждому поставщику — одно письмо только с его позициями. */
@Data
public class RfqSendRequest {
    private LocalDate replyBy;
    /** Приложить запрос на бланке (Word) и поставить в нём подпись и печать. */
    private boolean withDocument;
    private boolean withStamp;
    @NotEmpty(message = "Не выбраны поставщики")
    @Valid
    private List<Supplier> suppliers;

    @Data
    public static class Supplier {
        /** Поставщик справочника; пусто — новый (newSupplier). */
        private Long distributorId;
        @Valid
        private NewSupplier newSupplier;
        /** Почта из окна: null — почта карточки; пустая — запрос без письма; иначе — один или несколько адресов. */
        private String email;
        /** «Указать клиента» — строка о конечном заказчике в письме и документе. */
        private boolean discloseClient;
        @NotEmpty(message = "Не выбраны позиции")
        private List<Long> lotIds;
    }

    /** Новый поставщик заводится в «Дистрибьюторах» при отправке. */
    @Data
    public static class NewSupplier {
        private String name;
        /** «Запомнить: возит <бренды выбранных позиций>». */
        private boolean rememberBrands = true;
    }
}
```

`RfqPreviewRequest.java` — заменить целиком:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Письмо или документ для одного поставщика до отправки (спека §6.2): из справочника или ещё не заведённый. */
@Data
public class RfqPreviewRequest {
    private Long distributorId;
    private String newSupplierName;
    @NotEmpty(message = "Не выбраны позиции")
    private List<Long> lotIds;
    private LocalDate replyBy;
    private boolean discloseClient;
    private boolean withDocument;
    private boolean withStamp;
}
```

- [ ] **Step 4: Поиск названия по всем рынкам**

В `DistributorRepository.java`:

```java
    /**
     * Все рынки: нативный запрос фильтр рынка не режет. Название поставщика уникально глобально (distributor_name_key),
     * поэтому «новый поставщик» сверяется со всеми рынками.
     */
    @Query(value = "SELECT id, market FROM distributor WHERE lower(name) = lower(:name)", nativeQuery = true)
    List<Object[]> findIdAndMarketByName(@Param("name") String name);
```

- [ ] **Step 5: `PrivateRequestRfqService` — переписать**

`src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java` — заменить целиком:

```java
package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RfqPreviewRequest;
import com.vladoose.nir.dto.request.RfqSendRequest;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.PriceRequestSendService;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.RfqClientLine;
import com.vladoose.nir.service.RfqDocumentService;
import com.vladoose.nir.util.EmailAddresses;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Окно «Запросить цены» частной заявки (спека 2026-10-12 §6.2): каждому поставщику — одно письмо только с его позициями,
 * через единый канал отправки. Сначала проверяется ВЕСЬ запрос — позиции своей заявки, поставщики своего рынка, новые
 * поставщики (название не занято на другом рынке), почта, клиент, дата, печать; потом заводятся новые поставщики и
 * пишется почта в пустые карточки; и только после этого уходят письма: отказ на третьем поставщике не оставит двух
 * отправленных писем без итога.
 */
@Service
public class PrivateRequestRfqService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestSendService sendService;
    private final DistributorService distributorService;
    private final DistributorRepository distributorRepository;
    private final RfqDocumentService documents;

    public PrivateRequestRfqService(PrivateRequestService privateRequestService, PriceRequestSendService sendService,
                                    DistributorService distributorService, DistributorRepository distributorRepository,
                                    RfqDocumentService documents) {
        this.privateRequestService = privateRequestService;
        this.sendService = sendService;
        this.distributorService = distributorService;
        this.distributorRepository = distributorRepository;
        this.documents = documents;
    }

    /** Письмо, которое уйдёт поставщику, — без сохранения запроса (тема без метки). */
    public KpPreviewResponse preview(Long requestId, RfqPreviewRequest req) {
        return sendService.preview(draft(requestId, req, req.isWithDocument()));
    }

    /** Документ на бланке до отправки: «№ —», номер появится при отправке. */
    public RfqDocumentService.RfqFile document(Long requestId, RfqPreviewRequest req) {
        PriceRequest draft = draft(requestId, req, true);
        if (draft.isWithStamp()) sendService.requireStamp(draft.getMarket() != null ? draft.getMarket() : MarketContext.get());
        return documents.render(draft);
    }

    private PriceRequest draft(Long requestId, RfqPreviewRequest req, boolean withDocument) {
        Tender t = privateRequestService.findById(requestId);
        checkReplyBy(req.getReplyBy());
        checkDisclose(t, req.isDiscloseClient());
        Distributor d;
        if (req.getDistributorId() != null) {
            d = distributor(req.getDistributorId());
        } else if (req.getNewSupplierName() != null && !req.getNewSupplierName().isBlank()) {
            d = Distributor.builder().name(req.getNewSupplierName().trim()).market(MarketContext.get()).build();
        } else {
            throw new BadRequestException("Не указан поставщик");
        }
        return sendService.draft(t.getId(), d, items(lots(t, req.getLotIds())),
                new SendOptions(req.getReplyBy(), withDocument, req.isWithStamp(), req.isDiscloseClient(), null));
    }

    public List<SendResult> send(Long requestId, RfqSendRequest req) {
        Tender t = privateRequestService.findById(requestId);
        if (req.getSuppliers() == null || req.getSuppliers().isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        checkReplyBy(req.getReplyBy());
        if (req.isWithDocument() && req.isWithStamp()) sendService.requireStamp(market(t));

        // 1. весь запрос проверяется до первой записи и первого письма
        Map<String, Plan> plans = new LinkedHashMap<>();
        for (RfqSendRequest.Supplier s : req.getSuppliers()) {
            Target target = target(s);
            if (s.getLotIds() == null || s.getLotIds().isEmpty()) {
                // текст этапа 1 (его держит PrivateRequestRfqTest): окно показывает, у кого именно пусто
                throw new BadRequestException("У поставщика «" + target.name() + "» не выбраны позиции");
            }
            List<TenderLot> lots = lots(t, s.getLotIds());
            checkDisclose(t, s.isDiscloseClient());
            List<String> typed = null;
            if (s.getEmail() != null) {
                typed = EmailAddresses.parse(s.getEmail());
                EmailAddresses.normalize(s.getEmail());   // длина поля — тоже до записи
            }
            Plan known = plans.get(target.key());
            if (known == null) {
                plans.put(target.key(), new Plan(target, new ArrayList<>(lots), typed, s.isDiscloseClient()));
            } else {
                for (TenderLot l : lots) if (!known.lots().contains(l)) known.lots().add(l);
            }
        }
        // 2. новые поставщики и почта в пустые карточки — до писем
        List<Sending> sendings = new ArrayList<>();
        for (Plan p : plans.values()) {
            Distributor d = materialize(p);
            sendings.add(new Sending(d, p, emailTo(d, p.typed())));
        }
        // 3. письма — каждому только его позиции
        List<SendResult> results = new ArrayList<>();
        for (Sending s : sendings) {
            SendOptions o = new SendOptions(req.getReplyBy(), req.isWithDocument(), req.isWithStamp(),
                    s.plan().disclose(), s.emailTo());
            results.addAll(sendService.send(t.getId(), List.of(s.distributor().getId()), items(s.plan().lots()), null, null, o));
        }
        return results;
    }

    /** Кому пишем: поставщик справочника (existing) или новый (newName); key — чтобы не писать одному дважды. */
    private record Target(String key, Distributor existing, String newName, boolean rememberBrands) {
        String name() {
            return existing != null ? existing.getName() : newName;
        }
    }

    private record Plan(Target target, List<TenderLot> lots, List<String> typed, boolean disclose) {}

    private record Sending(Distributor distributor, Plan plan, List<String> emailTo) {}

    private Target target(RfqSendRequest.Supplier s) {
        boolean hasId = s.getDistributorId() != null;
        boolean isNew = s.getNewSupplier() != null;
        if (hasId == isNew) throw new BadRequestException("Укажите поставщика из справочника или нового");
        if (hasId) {
            Distributor d = distributor(s.getDistributorId());
            return new Target("d:" + d.getId(), d, null, false);
        }
        String name = s.getNewSupplier().getName() == null ? "" : s.getNewSupplier().getName().trim();
        if (name.isEmpty()) throw new BadRequestException("Укажите название нового поставщика");
        if (name.length() > 255) throw new BadRequestException("Название поставщика — не больше 255 символов");
        Distributor same = existingByName(name);
        boolean remember = s.getNewSupplier().isRememberBrands();
        return same != null
                ? new Target("d:" + same.getId(), same, null, remember)
                : new Target("new:" + name.toLowerCase(Locale.ROOT), null, name, remember);
    }

    /** Название занято на другом рынке — 400; на этом (в любом регистре) — это и есть он, дубля не будет. */
    private Distributor existingByName(String name) {
        for (Object[] row : distributorRepository.findIdAndMarketByName(name)) {
            if (!MarketContext.get().name().equals(String.valueOf(row[1]))) {
                throw new BadRequestException("Поставщик «" + name + "» уже есть на другом рынке — уточните название");
            }
            return distributorService.findById(((Number) row[0]).longValue());
        }
        return null;
    }

    /** Новый — заводится в «Дистрибьюторах»; у существующего с пустой карточкой вписанная почта сохраняется в карточку. */
    private Distributor materialize(Plan p) {
        Target t = p.target();
        List<String> brands = t.rememberBrands() ? brandsOf(p.lots()) : List.of();
        if (t.existing() == null) {
            String email = p.typed() == null ? null : String.join(", ", p.typed());
            return distributorService.save(Distributor.builder().name(t.newName()).email(email)
                    .brands(new ArrayList<>(brands)).build());
        }
        Distributor d = t.existing();
        boolean changed = false;
        if (blank(d.getEmail()) && p.typed() != null && !p.typed().isEmpty()) {
            d.setEmail(String.join(", ", p.typed()));
            changed = true;
        }
        for (String b : brands) {
            if (d.getBrands().stream().noneMatch(x -> x.equalsIgnoreCase(b))) {
                d.getBrands().add(b);
                changed = true;
            }
        }
        return changed ? distributorService.save(d) : d;
    }

    /** null — почта карточки; пустой — без письма (поле очищено); иначе — вписанные адреса, если они не те же, что в карточке. */
    static List<String> emailTo(Distributor d, List<String> typed) {
        if (typed == null) return null;
        if (typed.isEmpty()) return List.of();
        return EmailAddresses.same(String.join(", ", typed), d.getEmail()) ? null : typed;
    }

    private static List<String> brandsOf(List<TenderLot> lots) {
        Map<String, String> out = new LinkedHashMap<>();
        for (TenderLot l : lots) {
            if (!blank(l.getManufact())) out.putIfAbsent(l.getManufact().trim().toLowerCase(Locale.ROOT), l.getManufact().trim());
        }
        return List.copyOf(out.values());
    }

    private static void checkDisclose(Tender t, boolean disclose) {
        if (disclose && RfqClientLine.clientName(t) == null) {
            throw new BadRequestException("Клиент в заявке не указан — указать его поставщику нельзя");
        }
    }

    private Distributor distributor(Long id) {
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

    private static Market market(Tender t) {
        return t.getMarket() != null ? t.getMarket() : MarketContext.get();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 6: Эндпоинт документа**

В `PrivateRequestController.java` — импорты `com.vladoose.nir.service.RfqDocumentService`, `org.springframework.http.*`, `java.nio.charset.StandardCharsets`; метод после `rfqSend`:

```java
    /** Word запроса для одного поставщика до отправки («№ —»); только скачивание, без угадывания типа и кеша. */
    @PostMapping("/{id}/rfq/document")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> rfqDocument(@PathVariable Long id, @Valid @RequestBody RfqPreviewRequest req) {
        RfqDocumentService.RfqFile f = rfqService.document(id, req);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(RfqDocumentService.DOCX))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.bytes());
    }
```

- [ ] **Step 7: Запустить тесты окна**

Run: `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqStage2Test' --tests 'com.vladoose.nir.privaterequest.PrivateRequestRfqTest'`
Expected: PASS (10 новых; прежние тесты этапа 1 — без изменений, в том числе текст «У поставщика «…» не выбраны позиции»).

- [ ] **Step 8: Мутации**

По одной, откат копией:
1) В `send` у `SendOptions` вместо `s.plan().disclose()` — `true` → FAIL `clientOnlyForTheCheckedSupplier_inLetterAndDocument`.
2) В `existingByName` убрать проверку рынка (сразу `return …findById`) → FAIL `newSupplierNameOnOtherMarketIs400_sameMarketNameIsReused`.
3) В `emailTo` вернуть `null` вместо `List.of()` для пустого поля → FAIL `clearedEmailSavesRequestWithoutLetter`.

После серии — `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/request/RfqSendRequest.java src/main/java/com/vladoose/nir/dto/request/RfqPreviewRequest.java src/main/java/com/vladoose/nir/repository/DistributorRepository.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRfqService.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestRfqStage2Test.java
git commit -m "$(cat <<'EOF'
feat(private-request): окно запроса цен — почта в окне, новый поставщик, «Указать клиента», Word до отправки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Подбор с производителем по умолчанию и 👤 в таблице цен

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestSourcingResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java`
- Test: `src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java` (дополнить)
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestPricesTest.java` (дополнить)

**Interfaces:**
- Consumes: `Distributor.isManufacturer()/isTrusted()`, `PriceRequest.isDiscloseClient()` (задача 1).
- Produces:
  - `PrivateRequestSourcingResponse.Supplier(distributorId, name, email, hasEmail, preselected, manufacturer, trusted, lines)`; `Line.reason` — `MANUFACTURER` | `BRAND` | `TYPE`;
  - `PrivateRequestPricesResponse.Supplier(distributorId, name, firstSentAt, clientDisclosed)`.

- [ ] **Step 1: Падающие тесты подбора**

В `PrivateRequestSourcingServiceTest.java` добавить:

```java
    @Test
    void manufacturerPreselected_brandDealersShownUnchecked_flagsInResponse() {
        Distributor maker = distributorRepository.save(Distributor.builder().name("ZZ Производитель " + System.nanoTime())
                .email("m@zz.kz").brands(new ArrayList<>(List.of("ZZMKR"))).manufacturer(true).build());
        Distributor dealer = distributorRepository.save(Distributor.builder().name("ZZ Дилер " + System.nanoTime())
                .email("d@zz.kz").brands(new ArrayList<>(List.of("ZZMKR"))).trusted(true).build());
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(input("ZZ Облучатель", "Завод ZZMKR", 1, null, null, null)),
                LinesMode.APPEND);

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        Supplier m = supplier(r, maker);
        assertThat(m.manufacturer()).isTrue();
        assertThat(m.preselected()).isTrue();
        assertThat(m.lines()).singleElement().satisfies(l -> {
            assertThat(l.reason()).isEqualTo("MANUFACTURER");
            assertThat(l.label()).isEqualTo("производитель");
            assertThat(l.preselected()).isTrue();
        });
        Supplier d = supplier(r, dealer);
        assertThat(d.trusted()).isTrue();
        assertThat(d.preselected()).isFalse();
        assertThat(d.lines()).singleElement().satisfies(l -> {
            assertThat(l.reason()).isEqualTo("BRAND");
            assertThat(l.label()).isEqualTo("возит ZZMKR");
            assertThat(l.preselected()).isFalse();
        });
        assertThat(r.suppliers().indexOf(m)).isLessThan(r.suppliers().indexOf(d));
    }

    @Test
    void manufacturerWithoutEmailLeavesDealersPreselected() {
        Distributor maker = distributorRepository.save(Distributor.builder().name("ZZ Производитель без почты " + System.nanoTime())
                .brands(new ArrayList<>(List.of("ZZNOMAIL"))).manufacturer(true).build());
        Distributor dealer = distributorRepository.save(Distributor.builder().name("ZZ Дилер с почтой " + System.nanoTime())
                .email("dd@zz.kz").brands(new ArrayList<>(List.of("ZZNOMAIL"))).build());
        Tender t = privateRequestService.createEmpty();
        privateRequestService.addLines(t.getId(), List.of(input("ZZ Прибор", "ZZNOMAIL", 1, null, null, null)), LinesMode.APPEND);

        PrivateRequestSourcingResponse r = sourcing.build(t.getId());

        assertThat(supplier(r, maker).preselected()).isFalse();
        assertThat(supplier(r, dealer).preselected()).isTrue();
    }
```

- [ ] **Step 2: Падающий тест таблицы цен**

В `PrivateRequestPricesTest.java` добавить:

```java
    @Test
    void clientDisclosedMarksTheSupplierColumn() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Позиция 👤", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor told = dist("ZZ Сказали");
        Distributor silent = dist("ZZ Молчок");
        ask(t, told, lot, "SENT", LetterStatus.SENT, null).getPriceRequest().setDiscloseClient(true);
        ask(t, silent, lot, "SENT", LetterStatus.SENT, null);
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(r.suppliers()).filteredOn(s -> s.distributorId().equals(told.getId()))
                .singleElement().satisfies(s -> assertThat(s.clientDisclosed()).isTrue());
        assertThat(r.suppliers()).filteredOn(s -> s.distributorId().equals(silent.getId()))
                .singleElement().satisfies(s -> assertThat(s.clientDisclosed()).isFalse());
    }
```

- [ ] **Step 3: Запустить — не собирается**

Run: `./gradlew test --tests 'com.vladoose.nir.sourcing.PrivateRequestSourcingServiceTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestPricesTest'`
Expected: FAIL — `compileTestJava`: нет `manufacturer()`, `trusted()`, `clientDisclosed()`.

- [ ] **Step 4: Ответы**

`PrivateRequestSourcingResponse.java` — `Supplier` и javadoc `Line`:

```java
    /**
     * preselected — отмечен по умолчанию: есть почта и хоть одна позиция по бренду, ещё не запрошенная (у бренда с
     * производителем с почтой — только у производителя). manufacturer / trusted — отметки карточки поставщика.
     */
    public record Supplier(Long distributorId, String name, String email, boolean hasEmail, boolean preselected,
                           boolean manufacturer, boolean trusted, List<Line> lines) {}

    /** reason: MANUFACTURER («производитель»), BRAND («возит <бренд>») или TYPE («по виду: <вид>» — подсказка без отметки). */
```

`PrivateRequestPricesResponse.java`:

```java
    /** clientDisclosed — хоть в одном запросе этому поставщику указан клиент (👤 в шапке колонки). */
    public record Supplier(Long distributorId, String name, OffsetDateTime firstSentAt, boolean clientDisclosed) {}
```

- [ ] **Step 5: Подбор с производителем**

В `PrivateRequestSourcingService.java`:

1) javadoc класса — последнее предложение «Производитель бренда по умолчанию — этап 2.» заменить на «У бренда с производителем (с почтой) по умолчанию отмечен производитель, дистрибьюторы бренда — без отметки.».

2) Цикл по позициям в `build`:

```java
        for (TenderLot lot : lots) {
            boolean matched = false;
            // производитель с почтой забирает отметку у дилеров бренда; без почты — нет, иначе позиция осталась бы
            // вовсе без отмеченных поставщиков
            boolean makerWithMail = distributors.stream().anyMatch(d -> d.isManufacturer() && !blank(d.getEmail())
                    && BrandMatch.firstCarried(d.getBrands(), lot.getManufact()) != null);
            for (Distributor d : distributors) {
                String carried = BrandMatch.firstCarried(d.getBrands(), lot.getManufact());
                if (carried == null) continue;
                matched = true;
                if (d.isManufacturer()) acc(bySupplier, d).add(lot, "MANUFACTURER", "производитель", true, requestedAt);
                else acc(bySupplier, d).add(lot, "BRAND", "возит " + carried.trim(), !makerWithMail, requestedAt);
            }
            if (!matched) {
                LotTypeClassifier.TypeGuess guess = typeClassifier.classify(lot);
                if (guess.typeId() != null) {
                    for (Distributor d : distributors) {
                        if (d.getEquipmentTypes().stream().anyMatch(et -> guess.typeId().equals(et.getId()))) {
                            acc(bySupplier, d).add(lot, "TYPE", "по виду: " + guess.typeName(), false, requestedAt);
                            matched = true;
                        }
                    }
                }
            }
            if (!matched) unmatched.add(new LineRef(lot.getId(), lot.getEquipName(), lot.getManufact()));
        }
```

3) Сортировка поставщиков:

```java
        List<Supplier> suppliers = bySupplier.values().stream().map(Acc::toSupplier)
                .sorted(Comparator.comparing(Supplier::preselected).reversed()
                        .thenComparing(Supplier::manufacturer, Comparator.reverseOrder())
                        .thenComparing((Supplier s) -> -s.lines().size())
                        .thenComparing(Supplier::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
```

4) В `Acc` — `add` с явным «по умолчанию» и `toSupplier` с отметками:

```java
        void add(TenderLot lot, String reason, String label, boolean byDefault, Map<String, OffsetDateTime> requestedAt) {
            if (!lotIds.add(lot.getId())) return;
            OffsetDateTime at = requestedAt.get(lot.getId() + ":" + d.getId());
            boolean pre = byDefault && hasEmail && at == null;
            lines.add(new Line(lot.getId(), lot.getEquipName(), lot.getQuantity(), lot.getUnit(), reason, label, pre, at));
        }

        Supplier toSupplier() {
            boolean pre = lines.stream().anyMatch(Line::preselected);
            return new Supplier(d.getId(), d.getName(), hasEmail ? d.getEmail().trim() : null, hasEmail, pre,
                    d.isManufacturer(), d.isTrusted(), List.copyOf(lines));
        }
```

5) Помощник в классе: `private static boolean blank(String s) { return s == null || s.isBlank(); }`.

- [ ] **Step 6: 👤 в таблице цен**

В `PrivateRequestPricesService.build`:

```java
        Map<Long, Supplier> suppliers = new LinkedHashMap<>();
        Set<Long> disclosed = new HashSet<>();
        Map<String, PriceRequestItem> latest = new HashMap<>();
        Set<Long> prIds = new LinkedHashSet<>();
        for (PriceRequestItem i : items) {
            PriceRequest pr = i.getPriceRequest();
            Distributor d = pr.getDistributor();
            suppliers.putIfAbsent(d.getId(), new Supplier(d.getId(), d.getName(), pr.getSentAt(), false));
            if (pr.isDiscloseClient()) disclosed.add(d.getId());
            latest.put(key(i.getTenderLot().getId(), d.getId()), i);   // отсортировано по запросу — последний свежее
            prIds.add(pr.getId());
        }
```

и в конце метода:

```java
        List<Supplier> header = suppliers.values().stream()
                .map(s -> disclosed.contains(s.distributorId())
                        ? new Supplier(s.distributorId(), s.name(), s.firstSentAt(), true) : s)
                .toList();
        return new PrivateRequestPricesResponse(header, lines);
```

- [ ] **Step 7: Запустить тесты задачи**

Run: `./gradlew test --tests 'com.vladoose.nir.sourcing.PrivateRequestSourcingServiceTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestPricesTest'`
Expected: PASS (2 + 1 новых, прежние — без изменений).

- [ ] **Step 8: Мутация — производитель не забирает отметку**

Скопировать `PrivateRequestSourcingService.java`, заменить `!makerWithMail` на `true`, прогнать `PrivateRequestSourcingServiceTest`.
Expected: FAIL `manufacturerPreselected_brandDealersShownUnchecked_flagsInResponse`. Вернуть копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/response/PrivateRequestSourcingResponse.java src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestPricesTest.java
git commit -m "$(cat <<'EOF'
feat(private-request): производитель бренда отмечен по умолчанию, 👤 в шапке таблицы цен

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: V28 — пять производителей westmed.kz

**Files:**
- Create: `src/main/resources/db/migration/V28__westmed_manufacturers.sql`
- Test: `src/test/java/com/vladoose/nir/sourcing/ManufacturersSeedTest.java`

**Interfaces:**
- Consumes: `distributor.manufacturer` (V27), `BrandMatch`, `EmailAddresses`.
- Produces: пять строк `distributor` рынка KZ с `manufacturer = TRUE`, почтой, сайтом, брендами и видами МИ.

- [ ] **Step 1: Сверка справочника прода (только чтение, с согласия оператора)**

Спросить оператора: «Сверить справочник поставщиков прода перед V28 — SSH, только чтение?». После «да»:

```bash
ssh -o ConnectTimeout=15 root@185.125.46.26 "cd /srv/ais && docker compose exec -T ais-postgres sh -c 'psql -U \"\$POSTGRES_USER\" -d nirdb -At'" <<'SQL'
select id, market, name, coalesce(email, ''), manufacturer from distributor
 where name ~* '(фотек|fotek|эма|аксион|axion|орион|orion|монитор|monitor)' order by id;
SQL
```

Expected: строк с точными названиями «ООО «ФОТЕК»», «ЗАО «Завод ЭМА»», «ООО Концерн «Аксион»», «ООО «Орион-Си»», «ООО «НПП «Монитор»»» на рынке RF нет. Ответ записать в леджер (`Task 9: prod check — …`). Есть совпадение на RF — стоп: решение оператора (иначе `ON CONFLICT` обновил бы строку РФ, а KZ остался бы без производителя). Похожие названия на KZ (дилеры, у ТОО «НЕОМЕДРЕМ» — бренд «НПП «МОНИТОР»») — не мешают.

- [ ] **Step 2: Падающий тест**

`src/test/java/com/vladoose/nir/sourcing/ManufacturersSeedTest.java`:

```java
package com.vladoose.nir.sourcing;

import com.vladoose.nir.util.BrandMatch;
import com.vladoose.nir.util.EmailAddresses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V28 (спека 2026-10-12 §3.3): пять производителей рынка KZ — отметка, почта, виды МИ и бренд, которым их товар приходит с
 * westmed.kz. Значения адресов — в самой миграции (их утвердил оператор); тест держит то, что не меняется правкой карточки.
 */
@SpringBootTest
class ManufacturersSeedTest {

    @Autowired JdbcTemplate jdbc;

    /** Название производителя → бренд товара на westmed.kz. */
    private static final Map<String, String> MAKERS = Map.of(
            "ООО «ФОТЕК»", "Фотек",
            "ЗАО «Завод ЭМА»", "Завод ЭМА",
            "ООО Концерн «Аксион»", "Аксион",
            "ООО «Орион-Си»", "Орион",
            "ООО «НПП «Монитор»»", "НПП Монитор");

    @Test
    void fiveManufacturersOnKzWithEmailTypesAndTheirWestmedBrand() {
        MAKERS.forEach((name, westmedBrand) -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT id, market, manufacturer, email FROM distributor WHERE name = ?", name);
            assertThat(row.get("market")).as(name).isEqualTo("KZ");
            assertThat(row.get("manufacturer")).as(name).isEqualTo(true);
            assertThat(EmailAddresses.parse((String) row.get("email"))).as(name).isNotEmpty();
            List<String> brands = jdbc.queryForList("SELECT brand FROM distributor_brand WHERE distributor_id = ?",
                    String.class, row.get("id"));
            assertThat(BrandMatch.firstCarried(brands, westmedBrand)).as(name + " ловит «" + westmedBrand + "»").isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM distributor_equipment_type WHERE distributor_id = ?",
                    Integer.class, row.get("id"))).as(name).isPositive();
        });
    }

    @Test
    void manufacturerBrandsDoNotCatchForeignLookAlikes() {
        List<String> all = jdbc.queryForList("SELECT b.brand FROM distributor_brand b JOIN distributor d ON d.id = b.distributor_id "
                + "WHERE d.manufacturer AND d.name IN ('ООО «ФОТЕК»','ЗАО «Завод ЭМА»','ООО Концерн «Аксион»','ООО «Орион-Си»',"
                + "'ООО «НПП «Монитор»»')", String.class);
        assertThat(BrandMatch.firstCarried(all, "Orion Diagnostica")).isNull();
        assertThat(BrandMatch.firstCarried(all, "Hemasure")).isNull();
        assertThat(BrandMatch.firstCarried(all, "Mindray")).isNull();
    }
}
```

- [ ] **Step 3: Запустить — падает**

Run: `./gradlew test --tests 'com.vladoose.nir.sourcing.ManufacturersSeedTest'`
Expected: FAIL `fiveManufacturersOnKzWithEmailTypesAndTheirWestmedBrand` — `EmptyResultDataAccessException` (строки нет).

- [ ] **Step 4: Миграция V28**

`src/main/resources/db/migration/V28__westmed_manufacturers.sql`:

```sql
-- V28: пять производителей брендов westmed.kz (спека 2026-10-12 §3.3, §6.1) — запрос цен по бренду строки
-- по умолчанию уходит производителю, дистрибьюторы бренда видны без отметки.
-- Адреса — с официальных сайтов производителей, проверены 2026-10-12 (исследование
-- .superpowers/research/2026-10-12-westmed-manufacturers.md: URL, дословные цитаты, MX-записи доменов),
-- утверждены оператором 2026-10-12 (у ЭМА — оба адреса). Дилеры этих брендов в РК не заводятся (решение оператора).
-- Название поставщика уникально по всем рынкам: заведённого руками с тем же названием не задваиваем — почту и
-- сайт пишем только в пустые поля, отметку «производитель», бренды и виды МИ добавляем.
-- Бренды — короткие формы: BrandMatch ищет бренд поставщика ВНУТРИ бренда строки («ЭМА» ловит и «Завод ЭМА»
-- с сайта, и «ЭМА», вписанное руками). Латиница — только у тех, кто ею пользуется сам (FOTEK, Axion), без «Orion»
-- и «EMA»: они поймали бы Orion Diagnostica и случайные слова.
-- Виды МИ — по ТОЧНЫМ именам equipment_type (V1 + V7): id локально и на проде разные. Продукция — по каталогам сайтов.

INSERT INTO distributor (name, address, website, email, last_name, first_name, middle_name, market, manufacturer) VALUES
  -- https://fotek.ru/contacts/ — «Центральный офис / 620078, Екатеринбург, ул. Малышева, стр. 145а / … / E-mail / fotek@fotek.ru»,
  -- единственный общий адрес производителя (ИНН 6660014314), электрохирургия и стойки СП1/СП2
  ('ООО «ФОТЕК»', 'Россия, 620078, г. Екатеринбург, ул. Малышева, стр. 145а', 'https://fotek.ru', 'fotek@fotek.ru',
   NULL, NULL, NULL, 'KZ', TRUE),
  -- https://ema.su/kontakty/ — «Отдел продаж «Светильники» / … / Страны СНГ / … / sales7@ema.su»,
  -- «Отдел продаж «Физиотерапия, неонатология, реанимация, бактерицидные рециркуляторы и облучатели» / … / sales9@ema.su»
  ('ЗАО «Завод ЭМА»', 'Россия, 620028, г. Екатеринбург, Верх-Исетский бульвар, 13Н', 'https://ema.su',
   'sales7@ema.su, sales9@ema.su', NULL, NULL, NULL, 'KZ', TRUE),
  -- https://axion-med.ru/contacts/ — «Борзенков Александр Алексеевич / Специалист отдела продаж медицинской техники /
  -- manager5@c.axion.ru / … / Азербайджан, Армения, Беларусь, Грузия, Казахстан, …»
  ('ООО Концерн «Аксион»', 'Россия, 426000, г. Ижевск, ул. М. Горького, 90', 'https://axion-med.ru', 'manager5@c.axion.ru',
   'Борзенков', 'Александр', 'Алексеевич', 'KZ', TRUE),
  -- https://orion-si.ru/contacts/ — «E-mail / orionsi@yandex.ru», единственный адрес сайта (домен yandex.ru, но его
  -- публикует сам производитель, ИНН 7727179138), озоновые стерилизаторы и озонаторы
  ('ООО «Орион-Си»', 'Россия, 115419, г. Москва, ул. Орджоникидзе, д. 11', 'https://orion-si.ru', 'orionsi@yandex.ru',
   NULL, NULL, NULL, 'KZ', TRUE),
  -- https://www.monitor-ltd.ru/kontaktyi — «Начальник отдела продаж, менеджер по … Казахстану … / Омельченко Евгений
  -- Сергеевич / … / genya@monitor-ltd.ru», ЭКГ, холтеры, спирографы, мониторы
  ('ООО «НПП «Монитор»»', 'Россия, 344068, г. Ростов-на-Дону, ул. Краснокурсантская, 104а', 'https://www.monitor-ltd.ru',
   'genya@monitor-ltd.ru', 'Омельченко', 'Евгений', 'Сергеевич', 'KZ', TRUE)
ON CONFLICT (name) DO UPDATE SET
  email        = CASE WHEN distributor.email IS NULL OR btrim(distributor.email) = '' THEN EXCLUDED.email ELSE distributor.email END,
  website      = CASE WHEN distributor.website IS NULL OR btrim(distributor.website) = '' THEN EXCLUDED.website ELSE distributor.website END,
  manufacturer = TRUE;

INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, x.brand FROM distributor d JOIN (VALUES
  ('ООО «ФОТЕК»', 'Фотек'), ('ООО «ФОТЕК»', 'FOTEK'),
  ('ЗАО «Завод ЭМА»', 'ЭМА'),
  ('ООО Концерн «Аксион»', 'Аксион'), ('ООО Концерн «Аксион»', 'Axion'),
  ('ООО «Орион-Си»', 'Орион'),
  ('ООО «НПП «Монитор»»', 'Монитор')
) AS x(name, brand) ON d.name = x.name
WHERE NOT EXISTS (SELECT 1 FROM distributor_brand b WHERE b.distributor_id = d.id AND lower(b.brand) = lower(x.brand));

INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d JOIN (VALUES
  ('ООО «ФОТЕК»', 'Хирургическое оборудование'),
  ('ЗАО «Завод ЭМА»', 'Хирургическое оборудование'), ('ЗАО «Завод ЭМА»', 'Стерилизация и дезинфекция'),
  ('ЗАО «Завод ЭМА»', 'Физиотерапия'), ('ЗАО «Завод ЭМА»', 'Неонатальное оборудование'),
  ('ЗАО «Завод ЭМА»', 'Анестезия и реанимация'),
  ('ООО Концерн «Аксион»', 'Дефибриллятор'), ('ООО Концерн «Аксион»', 'ЭКГ'), ('ООО Концерн «Аксион»', 'ИВЛ'),
  ('ООО Концерн «Аксион»', 'Анестезия и реанимация'), ('ООО Концерн «Аксион»', 'Неонатальное оборудование'),
  ('ООО Концерн «Аксион»', 'Физиотерапия'),
  ('ООО «Орион-Си»', 'Стерилизация и дезинфекция'), ('ООО «Орион-Си»', 'Физиотерапия'),
  ('ООО «НПП «Монитор»»', 'ЭКГ'), ('ООО «НПП «Монитор»»', 'Монитор пациента')
) AS x(name, type) ON d.name = x.name
JOIN equipment_type et ON et.name = x.type
ON CONFLICT DO NOTHING;
```

- [ ] **Step 5: Запустить тест**

Run: `./gradlew test --tests 'com.vladoose.nir.sourcing.ManufacturersSeedTest'`
Expected: PASS (2 теста); в логе Flyway — «Migrating schema "public" to version "28 - westmed manufacturers"». Проверить, что виды легли все: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -At -c "select d.name, count(*) from distributor_equipment_type x join distributor d on d.id = x.distributor_id where d.manufacturer group by 1 order by 1;"` → ФОТЕК 1, ЭМА 5, Аксион 6, Орион-Си 2, Монитор 2 (меньше — опечатка в имени вида: join молча теряет строку, поправить миграцию ДО коммита).

- [ ] **Step 6: Коммит**

```bash
git add src/main/resources/db/migration/V28__westmed_manufacturers.sql src/test/java/com/vladoose/nir/sourcing/ManufacturersSeedTest.java
git commit -m "$(cat <<'EOF'
feat(distributors): V28 — пять производителей брендов westmed.kz с почтами с официальных сайтов

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Фронт — окно «Запросить цены» этапа 2 и 👤 в таблице цен

**Files:**
- Create: `frontend/src/app/shared/rfq-options.ts`
- Modify: `frontend/src/app/shared/private-request.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts` (переписать)
- Modify: `frontend/src/app/pages/private-requests/pr-supply.component.ts`
- Modify: `frontend/src/app/pages/private-requests/pr-prices.component.ts`

**Interfaces:**
- Consumes: ответы и тела задач 7–8 (`Sourcing.suppliers[].manufacturer/trusted`, причина `MANUFACTURER`, `PriceSupplier.clientDisclosed`, `POST …/rfq/document`).
- Produces: `readWithDocument()`, `storeWithDocument(boolean)`, `todayIso()` (общие с панелью КП тендера, задача 11); `sameEmails`, `dispositionFileName`, типы `RfqPreviewBody`, `RfqSendBody`; `ApiService.downloadRfqDocument`.

- [ ] **Step 1: Общие помощники**

`frontend/src/app/shared/rfq-options.ts`:

```ts
/** Выбор «Приложить запрос на бланке (Word)» помнит браузер — общий для окна заявки и панели КП тендера (спека §6.2). */
const DOC_KEY = 'ais.rfq.withDocument';

export function readWithDocument(): boolean {
  try {
    return localStorage.getItem(DOC_KEY) === '1';
  } catch {
    return false;
  }
}

export function storeWithDocument(on: boolean) {
  try {
    localStorage.setItem(DOC_KEY, on ? '1' : '0');
  } catch {
    /* приватный режим или запрет хранилища — выбор просто не запомнится */
  }
}

/** Сегодня по часам браузера, YYYY-MM-DD (toISOString — UTC: до 05:00 по Уральску дал бы вчерашнее число). */
export function todayIso(): string {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 10);
}
```

В `frontend/src/app/shared/private-request.ts`:

1) `SourcingLine.reason` — `reason: 'MANUFACTURER' | 'BRAND' | 'TYPE';`; в `SourcingSupplier` после `preselected` — `manufacturer: boolean; trusted: boolean;`.
2) `PriceSupplier` — `export interface PriceSupplier { distributorId: number; name: string; firstSentAt: string | null; clientDisclosed: boolean; }`.
3) В конец файла:

```ts
/** Письмо или документ для одного поставщика до отправки: из справочника или ещё не заведённый. */
export interface RfqPreviewBody {
  distributorId?: number;
  newSupplierName?: string;
  lotIds: number[];
  replyBy: string | null;
  discloseClient: boolean;
  withDocument: boolean;
  withStamp: boolean;
}

export interface RfqSendSupplier {
  distributorId: number | null;
  newSupplier: { name: string; rememberBrands: boolean } | null;
  /** Почта из окна: пустая — запрос без письма. */
  email: string;
  discloseClient: boolean;
  lotIds: number[];
}

export interface RfqSendBody { replyBy: string | null; withDocument: boolean; withStamp: boolean; suppliers: RfqSendSupplier[]; }

/** Те же адреса без учёта регистра и разделителей — как EmailAddresses.same на сервере. */
export function sameEmails(a: string | null, b: string | null): boolean {
  const norm = (s: string | null) => (s || '').split(/[,;\s]+/).map(x => x.trim().toLowerCase()).filter(Boolean).join(',');
  return norm(a) === norm(b);
}

/** Имя файла из Content-Disposition (RFC 5987: filename*=UTF-8''…), иначе запасное. */
export function dispositionFileName(header: string | null, fallback: string): string {
  const m = /filename\*=UTF-8''([^;]+)/i.exec(header || '');
  if (m) {
    try {
      return decodeURIComponent(m[1]);
    } catch {
      /* битое имя — запасное */
    }
  }
  return fallback;
}
```

- [ ] **Step 2: Методы API**

В `frontend/src/app/services/api.service.ts`:
1) в `import type { … } from '../shared/private-request'` добавить `RfqPreviewBody, RfqSendBody`;
2) в импорт из `@angular/common/http` добавить `HttpResponse`;
3) методы окна:

```ts
  previewRfq(id: number, body: RfqPreviewBody): Observable<{ subject: string; body: string }> {
    return this.http.post<{ subject: string; body: string }>(`${this.base}/private-requests/${id}/rfq/preview`, body);
  }
  sendRfq(id: number, body: RfqSendBody): Observable<SendResult[]> {
    return this.http.post<SendResult[]>(`${this.base}/private-requests/${id}/rfq/send`, body);
  }
  /** Word запроса до отправки («№ —»); имя файла — в Content-Disposition; только blob через HttpClient (§14). */
  downloadRfqDocument(id: number, body: RfqPreviewBody): Observable<HttpResponse<Blob>> {
    return this.http.post(`${this.base}/private-requests/${id}/rfq/document`, body, { responseType: 'blob', observe: 'response' });
  }
```

- [ ] **Step 3: Окно — переписать**

`frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts` — заменить целиком:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import {
  PrLine, RfqPreviewBody, SendResult, Sourcing, dateText, dispositionFileName, plural, sameEmails,
} from '../../shared/private-request';
import { readWithDocument, storeWithDocument, todayIso } from '../../shared/rfq-options';
import { saveBlob } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';

type Reason = 'MANUFACTURER' | 'BRAND' | 'TYPE';

interface DlgLine {
  lotId: number;
  name: string;
  brand: string | null;
  quantity: number | null;
  unit: string | null;
  /** Из подбора; null — поставщик добавлен руками. */
  reason: Reason | null;
  label: string;
  requestedAt: string | null;
  on: boolean;
}

interface DlgSupplier {
  /** «d:<id>» — из справочника, «new:<название>» — новый, ещё без id. */
  key: string;
  distributorId: number | null;
  name: string;
  /** Почта из карточки ('' — пусто): от неё — подсказка, что станет с вписанной почтой. */
  cardEmail: string;
  email: string;
  manufacturer: boolean;
  trusted: boolean;
  isNew: boolean;
  rememberBrands: boolean;
  disclose: boolean;
  lines: DlgLine[];
}

interface DirEntry { id: number; name: string; email: string | null; trusted: boolean; manufacturer: boolean; }

/**
 * «Запросить цены» (спека 2026-10-12 §6.2): поставщики с причинами подбора и отметками по умолчанию (у бренда с
 * производителем — производитель), почта каждого — полем (пустая карточка — сохранится, другая — только для письма),
 * «Указать клиента» (по умолчанию — у доверенных), новый поставщик, «Ответить до», запрос на бланке (Word) с подписью и
 * печатью, предпросмотр письма и документа — ровно того, что уйдёт.
 */
@Component({
  selector: 'app-pr-rfq-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Запросить цены" (click)="$event.stopPropagation()">
        <h3>Запросить цены<ng-container *ngIf="suppliers.length"> — {{ suppliers.length }} {{ supplierWord(suppliers.length) }}</ng-container></h3>
        <div class="empty" *ngIf="loading">Подбираю поставщиков…</div>
        <div class="error-banner" *ngIf="error">{{ error }}</div>

        <div class="sup" *ngFor="let s of suppliers; trackBy: trackSupplier" [class.on]="isOn(s)">
          <div class="sup-head">
            <label class="sup-pick">
              <input type="checkbox" [checked]="isOn(s)" (change)="toggleSupplier(s, $any($event.target).checked)"
                     [attr.aria-label]="'Запросить у ' + s.name" />
              <span class="sup-name">{{ s.name }}</span>
              <span class="flag" *ngIf="s.manufacturer">производитель</span>
              <span class="flag" *ngIf="s.trusted">доверенный</span>
              <span class="flag" *ngIf="s.isNew">новый</span>
            </label>
            <label class="disclose" [class.off]="!clientName"
                   [attr.title]="clientName ? 'Строка о конечном заказчике в письме и документе' : 'Клиент в заявке не указан'">
              <input type="checkbox" [(ngModel)]="s.disclose" [disabled]="!clientName" /> Указать клиента
            </label>
          </div>
          <input class="mail" type="text" inputmode="email" autocomplete="off" [(ngModel)]="s.email"
                 placeholder="впишите почту — несколько через запятую" [attr.aria-label]="'Почта ' + s.name" />
          <p class="mail-hint" *ngIf="mailHint(s) as hint">{{ hint }}</p>
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
          <p class="warn" *ngIf="isOn(s) && !s.email.trim()">Письмо не уйдёт — запрос сохранится, цену внесёте вручную (например, по телефону).</p>
          <label class="remember" *ngIf="s.isNew && brandsText(s) as brands">
            <input type="checkbox" [(ngModel)]="s.rememberBrands" /> Запомнить: возит {{ brands }}
          </label>
          <div class="sup-actions">
            <button type="button" class="link" [disabled]="!isOn(s) || busy" (click)="showPreview(s)">Посмотреть письмо</button>
            <button type="button" class="link" *ngIf="withDocument" [disabled]="!isOn(s) || busy" (click)="downloadDocument(s)">Скачать документ</button>
          </div>
        </div>

        <p class="hint" *ngIf="!loading && unmatched.length">Без поставщика: {{ unmatchedText() }} — добавьте поставщика ниже.</p>

        <div class="add" *ngIf="!loading">
          <button type="button" class="btn btn-line" *ngIf="!adding" (click)="startAdd()">＋ Добавить поставщика</button>
          <div class="add-box" *ngIf="adding">
            <input type="search" [(ngModel)]="addQuery" placeholder="Поставщик из справочника или название нового…"
                   aria-label="Поиск поставщика" />
            <ul class="add-list">
              <li *ngFor="let d of addOptions()">
                <button type="button" (click)="addSupplier(d)">
                  <span>{{ d.name }}</span><span class="sup-mail" [class.none]="!d.email">{{ d.email || 'нет почты' }}</span>
                </button>
              </li>
              <li *ngIf="canAddNew()">
                <button type="button" class="new-sup" (click)="addNew()">＋ Новый поставщик «{{ addQuery.trim() }}»</button>
              </li>
            </ul>
            <button type="button" class="btn btn-cancel" (click)="adding = false">Готово</button>
          </div>
        </div>

        <div class="opts">
          <label class="reply">Ответить до <input type="date" [(ngModel)]="replyBy" [min]="today" /></label>
          <label class="opt"><input type="checkbox" [ngModel]="withDocument" (ngModelChange)="setWithDocument($event)" />
            Приложить запрос на бланке (Word)</label>
          <label class="opt" [class.off]="!hasStamp || !withDocument"
                 [attr.title]="hasStamp ? null : 'Печать не загружена — «Система → Реквизиты и печать»'">
            <input type="checkbox" [(ngModel)]="withStamp" [disabled]="!hasStamp || !withDocument" /> Подпись и печать
          </label>
        </div>

        <div class="preview" *ngIf="preview">
          <div class="pv-head">Письмо для «{{ previewFor }}»</div>
          <div class="pv-subject">Тема: {{ preview.subject }}</div>
          <pre class="pv-body">{{ preview.body }}</pre>
        </div>

        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="sending || !planCount()" (click)="send()">{{ sendLabel() }}</button>
          <button type="button" class="btn btn-cancel" [disabled]="sending" (click)="dismiss()">Отмена</button>
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
    .sup-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
    .sup-pick { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
    .sup-name { font-weight: 600; }
    .flag { font-size: 11px; border-radius: 999px; padding: 1px 8px; background: color-mix(in srgb, var(--accent) 15%, transparent);
            color: var(--accent); }
    .disclose { display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
    .disclose.off, .opt.off { color: var(--text-muted); cursor: default; }
    .mail { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
            font: inherit; font-size: 13px; }
    .mail-hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .sup-mail { font-size: 12px; color: var(--text-muted); }
    .sup-mail.none { color: var(--warn-text); }
    .sup-lines { list-style: none; margin: 0; padding: 0 0 0 24px; display: flex; flex-direction: column; gap: 4px; }
    .check { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
    .why, .was { font-size: 12px; color: var(--text-muted); }
    .was { color: var(--warn-text); }
    .warn { margin: 0; font-size: 12px; color: var(--warn-text); }
    .remember { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .sup-actions { display: flex; gap: 16px; flex-wrap: wrap; }
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
    .new-sup { color: var(--accent) !important; }
    .opts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; }
    .reply, .opt { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .opt { cursor: pointer; }
    .preview { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; background: var(--surface-2); }
    .pv-head { font-size: 12px; color: var(--text-muted); }
    .pv-subject { font-size: 13px; font-weight: 600; margin: 4px 0; }
    .pv-body { margin: 0; white-space: pre-wrap; font-family: inherit; font-size: 13px; color: var(--text); }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .sup-lines { padding-left: 0; }
      .check, .disclose, .opt, .remember { min-height: 40px; }
      .mail, .add-box input, .reply input { font-size: 16px; min-height: 40px; }
      .form-actions .btn, .add .btn, .link { min-height: 40px; }
    }
  `],
})
export class PrRfqDialogComponent implements OnInit {
  @Input() requestId!: number;
  @Input() lines: PrLine[] = [];
  /** Клиент заявки — учреждение или название; пусто — «Указать клиента» недоступна. */
  @Input() clientName: string | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() sent = new EventEmitter<SendResult[]>();

  suppliers: DlgSupplier[] = [];
  unmatched: { lotId: number; name: string }[] = [];
  loading = true;
  error = '';
  sending = false;
  /** Идёт предпросмотр письма или сборка документа. */
  busy = false;
  adding = false;
  addQuery = '';
  replyBy = '';
  readonly today = todayIso();
  withDocument = readWithDocument();
  withStamp = false;
  hasStamp = false;
  preview: { subject: string; body: string } | null = null;
  previewFor = '';
  private directory: DirEntry[] = [];

  constructor(private api: ApiService, private profiles: CompanyProfileService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnInit() {
    // профиль из кеша приходит синхронно — markForCheck, не detectChanges (CLAUDE.md §12)
    this.profiles.profile$().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.hasStamp = !!p?.hasStamp; this.cdr.markForCheck(); },
      error: () => { /* без профиля «Подпись и печать» просто недоступна */ },
    });
    this.api.getPrivateRequestSourcing(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (s: Sourcing) => {
        this.suppliers = s.suppliers.map(x => ({
          key: 'd:' + x.distributorId, distributorId: x.distributorId, name: x.name,
          cardEmail: x.email || '', email: x.email || '', manufacturer: x.manufacturer, trusted: x.trusted,
          isNew: false, rememberBrands: false, disclose: x.trusted && !!this.clientName,
          lines: x.lines.map(l => ({ lotId: l.lotId, name: l.name, brand: this.brandOf(l.lotId), quantity: l.quantity,
                                     unit: l.unit, reason: l.reason, label: l.label, requestedAt: l.requestedAt,
                                     on: l.preselected })),
        }));
        this.unmatched = s.unmatched;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => { this.loading = false; this.error = 'Подбор поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  /** Закрыть окно (фон, Esc, «Отмена»), но не во время отправки: ответ отписался бы, итога пользователь не увидел бы. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (this.sending) return;
    this.close.emit();
  }

  supplierWord(n: number) {
    return plural(n, 'поставщик', 'поставщика', 'поставщиков');
  }

  /** «12.10» — как в спеке: «запрошено ДД.ММ». */
  shortDate(iso: string | null): string {
    return dateText(iso).slice(0, 5);
  }

  trackSupplier(_: number, s: DlgSupplier) {
    return s.key;
  }

  isOn(s: DlgSupplier): boolean {
    return s.lines.some(l => l.on);
  }

  /** Отметить поставщика: позиции по бренду (производитель или «возит»), ещё не запрошенные; нет таких — все незапрошенные. */
  toggleSupplier(s: DlgSupplier, on: boolean) {
    if (!on) {
      s.lines.forEach(l => l.on = false);
      return;
    }
    const brand = s.lines.filter(l => (l.reason === 'MANUFACTURER' || l.reason === 'BRAND') && !l.requestedAt);
    const fresh = s.lines.filter(l => !l.requestedAt);
    (brand.length ? brand : fresh.length ? fresh : s.lines).forEach(l => l.on = true);
  }

  /** Что станет с вписанной почтой (спека §6.2): в пустую карточку — сохранится; другая — только для этого письма. */
  mailHint(s: DlgSupplier): string {
    const typed = s.email.trim();
    if (!typed || s.isNew) return '';
    if (!s.cardEmail) return 'Почта сохранится в карточку поставщика';
    return sameEmails(typed, s.cardEmail) ? '' : 'Письмо уйдёт на вписанный адрес; карточка поставщика не изменится';
  }

  /** Бренды отмеченных позиций — для «Запомнить: возит …» у нового поставщика. */
  brandsText(s: DlgSupplier): string {
    const seen = new Map<string, string>();
    for (const l of s.lines) if (l.on && l.brand && l.brand.trim()) seen.set(l.brand.trim().toLowerCase(), l.brand.trim());
    return [...seen.values()].join(', ');
  }

  unmatchedText(): string {
    const names = this.unmatched.map(u => u.name);
    return names.slice(0, 3).join(', ') + (names.length > 3 ? ` и ещё ${names.length - 3}` : '');
  }

  startAdd() {
    this.adding = true;
    this.addQuery = '';
    if (this.directory.length) return;
    this.api.getDistributors().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.directory = (list || []).map((d: any) => ({ id: d.id, name: d.name, email: d.email || null,
                                                         trusted: !!d.trusted, manufacturer: !!d.manufacturer }));
        this.cdr.markForCheck();
      },
      error: e => { this.error = 'Справочник поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  addOptions(): DirEntry[] {
    const q = this.addQuery.trim().toLowerCase();
    const taken = new Set(this.suppliers.map(s => s.distributorId));
    return this.directory.filter(d => !taken.has(d.id) && (!q || d.name.toLowerCase().includes(q))).slice(0, 10);
  }

  /** «Новый поставщик» — когда такого названия нет ни в справочнике, ни среди выбранных. */
  canAddNew(): boolean {
    const q = this.addQuery.trim().toLowerCase();
    return q.length >= 2 && !this.directory.some(d => d.name.toLowerCase() === q)
      && !this.suppliers.some(s => s.name.toLowerCase() === q);
  }

  addSupplier(d: DirEntry) {
    const email = d.email && d.email.trim() ? d.email.trim() : '';
    this.push({ key: 'd:' + d.id, distributorId: d.id, name: d.name, cardEmail: email, email, manufacturer: d.manufacturer,
                trusted: d.trusted, isNew: false, rememberBrands: false, disclose: d.trusted && !!this.clientName,
                lines: this.allLines() });
  }

  /** Новый поставщик заводится в «Дистрибьюторах» при отправке (спека §6.2). */
  addNew() {
    const name = this.addQuery.trim();
    this.push({ key: 'new:' + name.toLowerCase(), distributorId: null, name, cardEmail: '', email: '', manufacturer: false,
                trusted: false, isNew: true, rememberBrands: true, disclose: false, lines: this.allLines() });
  }

  private push(s: DlgSupplier) {
    this.suppliers = [...this.suppliers, s];
    this.unmatched = [];
    this.adding = false;
  }

  /** Позиции заявки, все отмечены (оператор снимет лишние). */
  private allLines(): DlgLine[] {
    return this.lines.map(l => ({ lotId: l.lotId, name: l.name, brand: l.brand, quantity: l.quantity, unit: l.unit,
                                  reason: null, label: '', requestedAt: null, on: true }));
  }

  private brandOf(lotId: number): string | null {
    return this.lines.find(l => l.lotId === lotId)?.brand ?? null;
  }

  setWithDocument(on: boolean) {
    this.withDocument = on;
    if (!on) this.withStamp = false;
    storeWithDocument(on);
  }

  private previewBody(s: DlgSupplier): RfqPreviewBody {
    return {
      ...(s.distributorId != null ? { distributorId: s.distributorId } : { newSupplierName: s.name }),
      lotIds: s.lines.filter(l => l.on).map(l => l.lotId),
      replyBy: this.replyBy || null,
      discloseClient: s.disclose && !!this.clientName,
      withDocument: this.withDocument,
      withStamp: this.withDocument && this.withStamp,
    };
  }

  showPreview(s: DlgSupplier) {
    const body = this.previewBody(s);
    if (!body.lotIds.length) return;
    this.busy = true;
    this.api.previewRfq(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.busy = false; this.preview = p; this.previewFor = s.name; this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }

  /** Документ ровно для этого поставщика; «№ —» — номер появится при отправке. */
  downloadDocument(s: DlgSupplier) {
    const body = this.previewBody(s);
    if (!body.lotIds.length) return;
    this.busy = true;
    this.api.downloadRfqDocument(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        this.busy = false;
        if (r.body) saveBlob(r.body, dispositionFileName(r.headers.get('Content-Disposition'), 'Запрос КП.docx'));
        this.cdr.markForCheck();
      },
      error: e => this.blobError(e),
    });
  }

  /** Ошибка ответа-blob: текст сервера лежит JSON-ом внутри Blob — достаём его (через .then, не await — §14). */
  private blobError(e: any) {
    this.busy = false;
    if (e?.error instanceof Blob) {
      e.error.text().then((t: string) => {
        try { this.error = JSON.parse(t).message || errorText(e); } catch { this.error = errorText(e); }
        this.cdr.markForCheck();
      });
    } else {
      this.error = errorText(e);
    }
    this.cdr.markForCheck();
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
    if (this.sending) return 'Отправляю…';
    const plan = this.plan();
    const mails = plan.filter(p => p.s.email.trim()).length;
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
      withDocument: this.withDocument,
      withStamp: this.withDocument && this.withStamp,
      suppliers: plan.map(p => ({
        distributorId: p.s.distributorId,
        newSupplier: p.s.isNew ? { name: p.s.name, rememberBrands: p.s.rememberBrands } : null,
        email: p.s.email.trim(),
        discloseClient: p.s.disclose && !!this.clientName,
        lotIds: p.lotIds,
      })),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: results => { this.sending = false; this.sent.emit(results); },
      error: e => { this.sending = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 4: Клиент в окно и 👤 в таблицу**

В `pr-supply.component.ts`:
1) тег окна — `[clientName]="clientName()"`:

```html
    <app-pr-rfq-dialog *ngIf="rfqOpen" [requestId]="view.id" [lines]="view.lines" [clientName]="clientName()"
                       (close)="rfqOpen = false" (sent)="onSent($event)"></app-pr-rfq-dialog>
```

2) метод:

```ts
  /** Клиент для поставщика — учреждение или название; контакт и телефон поставщику не раскрываются (как на сервере). */
  clientName(): string | null {
    const v = this.view;
    return (v.facilityName && v.facilityName.trim()) || (v.customerName && v.customerName.trim()) || null;
  }
```

В `pr-prices.component.ts`:
1) шапка колонки — `{{ s.name }}<span class="who" *ngIf="s.clientDisclosed" title="Клиент указан в запросе">👤</span><div class="sent" …>`;
2) мобильная строка — `<span>{{ prices.suppliers[i].name }}<ng-container *ngIf="prices.suppliers[i].clientDisclosed"> 👤</ng-container></span>`;
3) стиль — `.who { margin-left: 4px; }`.

- [ ] **Step 5: Сборка**

Run: `cd frontend && npm run build`
Expected: сборка без ошибок; `private-requests` — ленивый чанк, начальный бандл не вырос (сравнить строку `Initial total` с прошлой сборкой — 1,32 МБ).

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/shared/rfq-options.ts frontend/src/app/shared/private-request.ts frontend/src/app/services/api.service.ts frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts frontend/src/app/pages/private-requests/pr-supply.component.ts frontend/src/app/pages/private-requests/pr-prices.component.ts
git commit -m "$(cat <<'EOF'
feat(private-request): окно запроса цен этапа 2 — почта, новый поставщик, «Указать клиента», Word, 👤

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Фронт — опции в панели КП тендера

**Files:**
- Modify: `frontend/src/app/pages/tenders/lot-kp-panel.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`

**Interfaces:**
- Consumes: `PriceRequestSendRequest.replyBy/withDocument/withStamp` (задача 6), `readWithDocument/storeWithDocument/todayIso` (задача 10).

- [ ] **Step 1: Тела запросов API**

В `api.service.ts` у `sendPriceRequests` и `previewKp` в тип тела добавить поля:

```ts
    replyBy?: string | null;
    withDocument?: boolean;
    withStamp?: boolean;
```

- [ ] **Step 2: Панель**

В `lot-kp-panel.component.ts`:

1) Импорты: `DestroyRef` из `@angular/core`, `takeUntilDestroyed` из `@angular/core/rxjs-interop`, `CompanyProfileService` из `../../services/company-profile.service`, `readWithDocument, storeWithDocument, todayIso` из `../../shared/rfq-options`.

2) Перед `<div class="kp-actions" …>` — опции:

```html
        <div class="kp-opts" *ngIf="panel.entries.length">
          <label>Ответить до <input type="date" [(ngModel)]="replyBy" [min]="today" /></label>
          <label class="kp-opt"><input type="checkbox" [ngModel]="withDocument" (ngModelChange)="setWithDocument($event)" />
            Приложить запрос на бланке (Word)</label>
          <label class="kp-opt" [class.off]="!hasStamp || !withDocument"
                 [attr.title]="hasStamp ? null : 'Печать не загружена — «Система → Реквизиты и печать»'">
            <input type="checkbox" [(ngModel)]="withStamp" [disabled]="!hasStamp || !withDocument" /> Подпись и печать
          </label>
        </div>
```

3) В заметке предпросмотра письма после «Письмо уйдёт … поставщик(ам).» — `<ng-container *ngIf="withDocument"> К письму приложится запрос на бланке (Word).</ng-container>`.

4) Поля и конструктор:

```ts
  replyBy = '';
  readonly today = todayIso();
  withDocument = readWithDocument();
  withStamp = false;
  hasStamp = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService,
              profiles: CompanyProfileService, destroyRef: DestroyRef) {
    // «Подпись и печать» — только при загруженной печати; из кеша приходит синхронно — без detectChanges (§12)
    profiles.profile$().pipe(takeUntilDestroyed(destroyRef)).subscribe({
      next: p => { this.hasStamp = !!p?.hasStamp; this.cdr.markForCheck(); },
      error: () => { /* без профиля галочка просто недоступна */ },
    });
  }

  setWithDocument(on: boolean) {
    this.withDocument = on;
    if (!on) this.withStamp = false;
    storeWithDocument(on);
  }

  /** Опции письма для превью и отправки — одни и те же (спека §6.6). */
  private options() {
    return { replyBy: this.replyBy || null, withDocument: this.withDocument, withStamp: this.withDocument && this.withStamp };
  }
```

5) `sendKpRequests` — `this.api.previewKp({ tenderId, distributorIds, items, ...this.options() })`; `confirmSendKp` — в тело `sendPriceRequests` добавить `...this.options()`.

6) Стили (до `@media`):

```css
    .kp-opts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin-top: 10px; font-size: 13px; }
    .kp-opts input[type="date"] { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                                  color: var(--text); font: inherit; }
    .kp-opt { display: flex; align-items: center; gap: 6px; cursor: pointer; }
    .kp-opt.off { color: var(--text-muted); cursor: default; }
```

и в `@media (max-width: 900px)` — `.kp-opts label { min-height: 40px; } .kp-opts input[type="date"] { font-size: 16px; min-height: 40px; }`.

- [ ] **Step 3: Сборка**

Run: `cd frontend && npm run build`
Expected: сборка без ошибок, бюджет стилей компонента не превышен.

- [ ] **Step 4: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/tenders/lot-kp-panel.component.ts frontend/src/app/services/api.service.ts
git commit -m "$(cat <<'EOF'
feat(tenders): в панели КП — «Ответить до», запрос на бланке (Word), подпись и печать

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Живая проверка, документация, полный прогон

**Files:**
- Modify: `CLAUDE.md`, `docs/PROGRESS.md`
- Modify: `/Users/vlad/.claude/projects/-Users-vlad-IdeaProjects-AIS/memory/last-task-pointer.md`, `MEMORY.md`

- [ ] **Step 1: Стек локально**

Почта — `./gradlew devMail` (фон, sandbox off). Бэкенд (фон, sandbox off):

```bash
MAIL_HOST=127.0.0.1 MAIL_PORT=3025 MAIL_SMTP_AUTH=true MAIL_USERNAME=zakup@westmed.kz MAIL_PASSWORD=secret \
MAIL_IMAP_ENABLED=true MAIL_IMAP_HOST=127.0.0.1 MAIL_IMAP_PORT=3143 MAIL_IMAP_PROTOCOL=imap \
MAIL_IMAP_USERNAME=zakup@westmed.kz MAIL_IMAP_PASSWORD=secret MAIL_IMAP_MARKET=KZ MAIL_IMAP_CLIENT_REQUESTS=false \
MAIL_IMAP_POLL_MS=600000 MAIL_IMAP_INITIAL_DELAY_MS=600000 JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun
```

Фронт — `cd frontend && npm start`. Перед проверкой записать в леджер id всего, что будет создано (заявка, запросы КП, новый поставщик) — для уборки.

- [ ] **Step 2: Живая проверка (Playwright, 1280 и 390 px, обе темы, оператор без прав записи)**

1. «Дистрибьюторы» → поставщик → «Доверенный» → «Сохранить»: чип «доверенный» в списке; почта «a@x.kz; b@x.kz» сохраняется как «a@x.kz, b@x.kz», «broken@kz» — ошибка с адресом.
2. «Система → Шаблон письма КП»: блок «Запрос на бланке», чипы `{{заказчик}}`/`{{вложение}}`, «Сбросить тексты запроса» не трогает тему и тело.
3. Новая частная заявка: клиент текстом, «Куда поставка», позиции — одна с брендом «Завод ЭМА» (с сайта), одна без бренда.
4. «Запросить цены…»: «ЗАО «Завод ЭМА»» отмечен, причина «производитель», чип «производитель»; дилер бренда — без отметки; у доверенного «Указать клиента» отмечена, у остальных — нет; у заявки без клиента — недоступна с подсказкой.
5. Поле почты: пустая карточка + адрес → подсказка «Почта сохранится в карточку поставщика»; другой адрес у заполненной → «…карточка поставщика не изменится».
6. «＋ Добавить поставщика» → название, которого нет → «＋ Новый поставщик «…»» → почта → «Запомнить: возит Завод ЭМА».
7. «Приложить запрос на бланке» → «Скачать документ»: файл «Запрос КП № — от ДД.ММ.ГГГГ — West-Med.docx». Открыть в **Word** (AppleScript `open` + экспорт в PDF, как у КП; копия — в контейнер Word, после — убрать): бланк на первой странице, «Исх. № —», «Кому», таблица, «В предложении просим указать», строка ответа, подпись; у доверенного — строка «Конечный заказчик…», у остальных её нет. «Подпись и печать» недоступна без печати.
8. «Посмотреть письмо» у доверенного — строка о заказчике и «Запрос на бланке компании — во вложении.».
9. «Отправить N писем» → тост; в GreenMail (IMAP 3143, `python3 -c "import imaplib…"` или `scripts/dev-mail.py`) — письма с вложением; у нового поставщика — запись в «Дистрибьюторах» с почтой и брендом.
10. Таблица цен: 👤 у доверенного; `python3 scripts/dev-mail.py reply <prId>` → «Проверить почту» → цена в ячейке.
11. «↻ Переслать» у запроса с вписанной почтой → письмо на тот же адрес, с вложением.
12. Карточка тендера (KZ) → лот → «КП»: «Ответить до», «Приложить запрос на бланке», «Подпись и печать»; предпросмотр — строка о вложении; отправка — вложение в письме.
13. Оператор: окно и опции видны в режиме просмотра, отправка недоступна (кнопок записи нет).

Найденное — исправить с тестом (красным до правки), записать в леджер `Task 12: Ruling (fix, найдено вживую): …`.

- [ ] **Step 3: Уборка**

Сначала остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), затем SQL по записанным id: запросы КП заявки, заявка (`delete from price_request where tender_id = …; delete from tender where id = … and source = 'PRIVATE_REQUEST';`), новый поставщик, отметка «Доверенный» и почта, выставленные на шаге 1 (вернуть как было), строки «Входящих» zakup@ и курсор (`delete from inbound_email where mailbox = 'zakup@westmed.kz' and imap_uid is not null; delete from mail_cursor where mailbox = 'zakup@westmed.kz';`). Затем остановить devMail и фронт (LISTEN-порты 3143, 4200).

- [ ] **Step 4: Полный прогон**

Run: `./gradlew cleanTest test > .superpowers/sdd/2026-10-12-private-request-workspace-stage2/fullgate.log 2>&1` (sandbox off), затем подсчёт по `build/test-results/test/*.xml`.
Expected: 0 failures, 0 errors; skipped — 1 (отчёт калибровки `PrivateRequestRegistryCalibrationTest`).

- [ ] **Step 5: Документация**

- `CLAUDE.md`:
  - §7 — поля V27 (`price_request.disclose_client/with_document/with_stamp/email_override`, `sent_to` — адрес последней попытки; `distributor.trusted/manufacturer`; `email_template.rfq_*`);
  - §8 — блок «Частная заявка — рабочее место сделки, этап 2» (после этапа 1): Word на бланке (`RfqDocumentBuilder` на `KpDocxRenderer`, колонки, «№ —», имя файла, без контактов под подписью), «Указать клиента» (кто клиент — учреждение или название), производители по умолчанию (`MANUFACTURER`, производитель с почтой забирает отметку), почта в окне (пусто — без письма, пустая карточка — сохраняется, другая — `email_override`), новый поставщик (название занято на другом рынке — 400, на этом — тот же), «Переслать» с настройками, опции панели КП тендера, V28 с источниками, «Проверено» с числами;
  - §9 — несколько адресов, вложения (`setEncodeFilenames`), `{{заказчик}}`/`{{вложение}}` и их вставка в старые шаблоны, тексты запроса на бланке;
  - §12 — окно этапа 2, `shared/rfq-options.ts`, 👤;
  - §13 — число тестов;
  - §15 — `POST /api/private-requests/{id}/rfq/document`, новые поля `rfq/send`, `rfq/preview`, `price-requests/send|preview`, `email-template`, `distributors`;
  - §16 — этап 2 сделан, дальше этап 3; хвосты (что не делалось); в списке миграций — V27, V28, следующая свободная V29.
- `docs/PROGRESS.md` — «Последнее обновление», «🧭 Где мы», блок этапа 2 (что сделано, проверено, ждёт: ревью ветки, мерж, push оператора; после деплоя — проверка, что V27/V28 применились и пять производителей на месте).
- Память `last-task-pointer.md` и строка в `MEMORY.md` — этап 2.

- [ ] **Step 6: Коммит**

```bash
git add CLAUDE.md docs/PROGRESS.md
git commit -m "$(cat <<'EOF'
docs: частная заявка, этап 2 — запрос на бланке, клиент в запросе, производители, почта в окне

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Ревью ветки**

Финальное ревью ветки (Opus) по `superpowers:requesting-code-review` с этим планом, спекой, Review Focus и строками `Ruling:` леджера → исправления (каждое — тест, красный до правки) → полный прогон → мерж в `main` fast-forward. Push — оператор (`! git push origin main`).
