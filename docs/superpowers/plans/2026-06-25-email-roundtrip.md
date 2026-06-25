# Почта: round-trip КП + входящие (Блок D2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Отправлять запрос КП поставщику с токеном `[КП-<id>]` в теме и принимать входящую почту по IMAP: ответы поставщиков авто-матчатся к `PriceRequest` (RESPONDED), письма клиник с Excel ставятся в очередь «Входящие» на импорт через готовый флоу D1.

**Architecture:** Новая рыночно-скоупленная сущность `InboundEmail` хранит каждое входящее письмо + классификацию + вложение. `MailReceiveService.poll()` (jakarta.mail `Store`/`Folder`, IMAP) читает непрочитанные, классифицирует по токену темы/вложению, пишет `InboundEmail`, матчит ответы к `PriceRequest`. Опрос выключен по умолчанию (`mail.imap.enabled`), запускается `@Scheduled`-планировщиком (под фиксированным рынком ящика) и ручной кнопкой. Письмо клиники импортируется через существующий `PrivateRequestImportService.preview(byte[],String)` + эндпоинт `/api/private-requests/import/commit`. Спека: `docs/superpowers/specs/2026-06-25-email-roundtrip-design.md`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/Hibernate 6, jakarta.mail (через spring-boot-starter-mail), Apache POI 5.2.5, GreenMail (test), PostgreSQL; Angular 21 (standalone, SCSS).

## Global Constraints

- Java **17**, Spring Boot **3.5.6**, Gradle **8.14** — не менять.
- Backend `com.vladoose.nir`. Entity на Lombok (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`), PK `@GeneratedValue(strategy = GenerationType.IDENTITY)`; сервисы `@Service` + constructor injection без `@Autowired`, `@Transactional` на записи; контроллеры `@RestController`, записи под `@PreAuthorize("hasRole('ADMIN')")` (method security включена). Конфиг — через `@Value` с дефолтом (конвенция репо, как `RegistryImportService`); `@ConfigurationProperties` НЕ использовать.
- БД PostgreSQL `localhost:5432/nirdb` (UTF-8). `ddl-auto: none`, `schema.sql` пересоздаёт таблицы, сид `data.sql`. **Редактировать `src/main/resources/schema.sql`** (не `build/...`).
- **КРИТИЧНО (среда):** Bash-sandbox блокирует :5432 → ЛЮБЫЕ `./gradlew`/DB-команды с `dangerouslyDisableSandbox: true`. `psql` = `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`, БД `nirdb`.
- **Известные PRE-EXISTING падения, НЕ наши:** `ApplyAutoFillServiceTest` (2). `./gradlew test` = BUILD FAILED ровно с этими 2 → норма; гейт: «компилируется + только эти 2».
- **Рыночный скоуп (КРИТИЧНО для планировщика):** `MarketContext` (ThreadLocal) — `get()` по умолчанию `Market.RF` (НЕ null). `@Scheduled`-поток НЕ имеет ни `MarketContext`, ни OSIV-сессии. Правило: **вызывающий ставит `MarketContext.set(market)` ДО вызова + `clear()` в finally; собственно работа с БД — в `@Transactional`-методе ОТДЕЛЬНОГО бина (не self-invoke), чтобы `MarketFilterAspect` получил привязанную сессию.** `@FilterDef` уже объявлен на `Tender` — на новой сущности только `@Filter` (НЕ переобъявлять `@FilterDef`).
- Фронт: Angular standalone, инлайн-шаблон + `styles: []`, `ApiService` (база `/api`, HttpClient, `marketInterceptor` вешает `X-Market` сам), `NotificationService.success(string)/error(string)`, `cdr.detectChanges()` после async. Фронт-тестов нет → гейт = `npm run build`.
- Каждый commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Ветка `feat/email-roundtrip` (содержит спеку). Рабочее дерево содержит несвязанный WIP пользователя — НЕ трогать; коммитить только файлы своей задачи.
- **Готовое к переиспользованию:** `PrivateRequestImportService.preview(byte[],String):ImportPreviewResponse` и `commit(ImportCommitRequest):Tender`; эндпоинт `POST /api/private-requests/import/commit`; `ImportPreviewResponse{columns:[{index,header,field}],rows:[[String]]}`; `LineField{NAME,MANUFACT,QUANTITY,IGNORE}`; `PriceRequestRepository extends JpaRepository<PriceRequest,Long>`; `PriceRequest` (status String, `setStatus/setResponseDate/setNote`, market-scoped); `Distributor.getEmail()`; `EmailService.sendEmail(to,subject,body)`; `Market.fromHeader(String)`; `NotFoundException`.

---

### Task 1: Backend — сущность `InboundEmail` (рыночно-скоупленная) + схема + репозиторий (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/InboundType.java`, `src/main/java/com/vladoose/nir/entity/InboundStatus.java`, `src/main/java/com/vladoose/nir/entity/InboundEmail.java`
- Create: `src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java`
- Modify: `src/main/resources/schema.sql`
- Test: `src/test/java/com/vladoose/nir/mail/InboundEmailTest.java`

**Interfaces:**
- Produces: `InboundType{SUPPLIER_RESPONSE,CLIENT_REQUEST,UNMATCHED}`; `InboundStatus{NEW,PROCESSED}`; `InboundEmail` (market-scoped, поля ниже); `InboundEmailRepository extends JpaRepository<InboundEmail,Long>` + `findAllByOrderByReceivedAtDesc()`.

- [ ] **Step 1: Падающий тест (рыночный штамп + персист)**

Create `src/test/java/com/vladoose/nir/mail/InboundEmailTest.java`:
```java
package com.vladoose.nir.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.InboundStatus;
import com.vladoose.nir.entity.InboundType;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.InboundEmailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InboundEmailTest {

    @Autowired InboundEmailRepository repository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void persistsAndStampsActiveMarket() {
        MarketContext.set(Market.KZ);
        InboundEmail saved = repository.save(InboundEmail.builder()
                .fromAddress("supplier@x.kz")
                .subject("Re: КП [КП-1]")
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .type(InboundType.SUPPLIER_RESPONSE)
                .status(InboundStatus.NEW)
                .build());
        repository.flush();

        InboundEmail loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getMarket()).isEqualTo(Market.KZ);
        assertThat(loaded.getType()).isEqualTo(InboundType.SUPPLIER_RESPONSE);
        assertThat(loaded.getStatus()).isEqualTo(InboundStatus.NEW);
    }
}
```

- [ ] **Step 2: Запустить — падает (нет классов)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.InboundEmailTest"`
Expected: FAIL (компиляция — нет `InboundEmail`/`InboundType`/`InboundStatus`/репозитория).

- [ ] **Step 3: Enums**

Create `src/main/java/com/vladoose/nir/entity/InboundType.java`:
```java
package com.vladoose.nir.entity;

public enum InboundType {
    SUPPLIER_RESPONSE, CLIENT_REQUEST, UNMATCHED
}
```
Create `src/main/java/com/vladoose/nir/entity/InboundStatus.java`:
```java
package com.vladoose.nir.entity;

public enum InboundStatus {
    NEW, PROCESSED
}
```

- [ ] **Step 4: Entity (market-scoped, как `Distributor` — `@Filter` БЕЗ `@FilterDef`)**

Create `src/main/java/com/vladoose/nir/entity/InboundEmail.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "inbound_email")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InboundEmail implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(length = 998)
    private String subject;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboundType type;

    @Column(name = "matched_price_request_id")
    private Long matchedPriceRequestId;

    @Column(name = "attachment_name", length = 255)
    private String attachmentName;

    @Column(name = "attachment")
    private byte[] attachment;

    @Column(length = 2000)
    private String excerpt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InboundStatus status = InboundStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Override public Market getMarket() { return market; }
    @Override public void setMarket(Market market) { this.market = market; }
}
```

- [ ] **Step 5: Репозиторий**

Create `src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.InboundEmail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InboundEmailRepository extends JpaRepository<InboundEmail, Long> {
    List<InboundEmail> findAllByOrderByReceivedAtDesc();
}
```

- [ ] **Step 6: Схема**

В `src/main/resources/schema.sql`:
1. В DROP-блок добавить `DROP TABLE IF EXISTS inbound_email CASCADE;` **перед** `DROP TABLE IF EXISTS price_request CASCADE;` (FK на price_request).
2. В конец секции CREATE (после `price_request`/`price_request_item`) добавить:
```sql
CREATE TABLE inbound_email (
    id                       BIGSERIAL PRIMARY KEY,
    from_address             VARCHAR(320),
    subject                  VARCHAR(998),
    received_at              TIMESTAMPTZ,
    type                     VARCHAR(20) NOT NULL,
    matched_price_request_id BIGINT REFERENCES price_request(id) ON DELETE SET NULL,
    attachment_name          VARCHAR(255),
    attachment               BYTEA,
    excerpt                  VARCHAR(2000),
    status                   VARCHAR(20) NOT NULL DEFAULT 'NEW',
    market                   VARCHAR(2) NOT NULL
);
```

- [ ] **Step 7: Зелёный + полный suite + проверка таблицы**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.InboundEmailTest"` → PASS.
Run (sandbox off): `./gradlew test` → только 2 известных `ApplyAutoFillServiceTest`.
```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -c "\d inbound_email" 2>&1 | head
```
Expected: таблица существует (FK на price_request, `attachment bytea`, `market` NOT NULL).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/InboundType.java src/main/java/com/vladoose/nir/entity/InboundStatus.java src/main/java/com/vladoose/nir/entity/InboundEmail.java src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java src/main/resources/schema.sql src/test/java/com/vladoose/nir/mail/InboundEmailTest.java
git commit -m "$(cat <<'EOF'
feat(email): сущность InboundEmail (рыночно-скоупленная) + таблица inbound_email

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Backend — токен `[КП-<id>]` (хелпер + вставка в отправку КП) (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/KpToken.java`
- Modify: `src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java`
- Test: `src/test/java/com/vladoose/nir/mail/KpTokenTest.java`

**Interfaces:**
- Produces: `KpToken.subjectToken(Long):String` (= `"[КП-"+id+"]"`), `KpToken.parse(String):Optional<Long>` (извлекает id из темы). Используется отправкой (T2) и классификатором приёма (T3) — единый источник формата токена.

- [ ] **Step 1: Падающий тест хелпера**

Create `src/test/java/com/vladoose/nir/mail/KpTokenTest.java`:
```java
package com.vladoose.nir.mail;

import com.vladoose.nir.util.KpToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KpTokenTest {

    @Test
    void roundTripAndParse() {
        assertThat(KpToken.subjectToken(42L)).isEqualTo("[КП-42]");
        assertThat(KpToken.parse(KpToken.subjectToken(42L))).contains(42L);
        assertThat(KpToken.parse("Re: Запрос КП [КП-7] от поставщика")).contains(7L);
        assertThat(KpToken.parse("Просто письмо без токена")).isEmpty();
        assertThat(KpToken.parse(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить — падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.KpTokenTest"`
Expected: FAIL — нет `KpToken`.

- [ ] **Step 3: Хелпер**

Create `src/main/java/com/vladoose/nir/util/KpToken.java`:
```java
package com.vladoose.nir.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Токен сопоставления КП в теме письма: [КП-<id>]. Единый формат для отправки и приёма. */
public final class KpToken {

    private static final Pattern PATTERN = Pattern.compile("\\[КП-(\\d+)\\]");

    private KpToken() {}

    public static String subjectToken(Long priceRequestId) {
        return "[КП-" + priceRequestId + "]";
    }

    public static Optional<Long> parse(String subject) {
        if (subject == null) return Optional.empty();
        Matcher m = PATTERN.matcher(subject);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }
}
```

- [ ] **Step 4: Зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.KpTokenTest"` → PASS.

- [ ] **Step 5: Вставить токен в тему отправки КП**

В `src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java` найти вызов `emailService.sendEmail(...)` (текущая тема: `"Запрос КП по тендеру №" + tender.getTenderNumber()`). `PriceRequest pr` уже сохранён выше (есть `pr.getId()`). Заменить тему на токен + текущую:
```java
            emailService.sendEmail(dist.getEmail() == null ? "" : dist.getEmail(),
                    com.vladoose.nir.util.KpToken.subjectToken(pr.getId())
                            + " Запрос КП по тендеру №" + tender.getTenderNumber(), body);
```
(Поведение и `status="SENT"` не трогаем — добавляется только токен в тему. Импорт можно сделать явным `import com.vladoose.nir.util.KpToken;` и звать `KpToken.subjectToken(pr.getId())`.)

- [ ] **Step 6: Полный suite**

Run (sandbox off): `./gradlew test` → компилируется, только 2 известных `ApplyAutoFillServiceTest` (существующий `BulkPriceRequestServiceTest` всё ещё проходит — изменилась только строка темы).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/KpToken.java src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java src/test/java/com/vladoose/nir/mail/KpTokenTest.java
git commit -m "$(cat <<'EOF'
feat(email): токен [КП-<id>] в теме отправки КП + хелпер KpToken (parse/subjectToken)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Backend — `MailReceiveService` (IMAP-опрос, классификация, матчинг) + GreenMail-тест (TDD)

**Files:**
- Modify: `build.gradle` (GreenMail testImplementation), `src/main/resources/application.yaml` (mail.imap.*)
- Create: `src/main/java/com/vladoose/nir/dto/response/PollResultResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/MailReceiveService.java`
- Test: `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `InboundEmailRepository`, `PriceRequestRepository`, `KpToken`, `Market`/`MarketContext`.
- Produces: `PollResultResponse { boolean enabled; int fetched; int supplierResponses; int clientRequests; int unmatched; String message; }`; `MailReceiveService.poll() : PollResultResponse` (`@Transactional`; предполагает, что `MarketContext` уже установлен вызывающим); `MailReceiveService.getMailboxMarket() : Market`.

- [ ] **Step 1: GreenMail в build.gradle**

В `build.gradle` в блок `dependencies { ... }` рядом с прочими `testImplementation` добавить:
```gradle
    testImplementation 'com.icegreen:greenmail-junit5:2.1.2'
```
(Если версия не резолвится — взять ближайшую доступную `2.1.x`. GreenMail 2.x — jakarta-based, совместим с Spring Boot 3.5.)

- [ ] **Step 2: Конфиг IMAP**

В `src/main/resources/application.yaml` на верхнем уровне (НЕ под `spring:` — это собственный корень `mail.imap`, рядом с корнем `spring:`) добавить:
```yaml
mail:
  imap:
    enabled: ${MAIL_IMAP_ENABLED:false}
    host: ${MAIL_IMAP_HOST:localhost}
    port: ${MAIL_IMAP_PORT:3143}
    username: ${MAIL_IMAP_USERNAME:zakup@westmed.kz}
    password: ${MAIL_IMAP_PASSWORD:}
    protocol: ${MAIL_IMAP_PROTOCOL:imap}
    market: ${MAIL_IMAP_MARKET:KZ}
    poll-ms: ${MAIL_IMAP_POLL_MS:300000}
```
(Учти: в YAML уже есть корневой `spring:`. `mail:` здесь — НОВЫЙ корневой ключ верхнего уровня. НЕ путать с `spring.mail` (SMTP). Проверить, что отступы делают `mail:` корневым, а не вложенным в `spring:`.)

- [ ] **Step 3: DTO результата**

Create `src/main/java/com/vladoose/nir/dto/response/PollResultResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PollResultResponse {
    private boolean enabled;
    private int fetched;
    private int supplierResponses;
    private int clientRequests;
    private int unmatched;
    private String message;
}
```

- [ ] **Step 4: Падающий интеграционный тест (GreenMail)**

Create `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`:
```java
package com.vladoose.nir.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.MailReceiveService;
import com.vladoose.nir.util.KpToken;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "mail.imap.enabled=true",
        "mail.imap.host=127.0.0.1",
        "mail.imap.port=3143",
        "mail.imap.username=zakup@westmed.kz",
        "mail.imap.password=secret",
        "mail.imap.protocol=imap",
        "mail.imap.market=KZ"
})
class MailReceiveServiceIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    @Autowired MailReceiveService mailReceiveService;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired InboundEmailRepository inboundEmailRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired DistributorRepository distributorRepository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void poll_matchesSupplierResponse_andQueuesClientExcel() throws Exception {
        MarketContext.set(Market.KZ);
        Facility fac = facilityRepository.save(Facility.builder().name("ZZMAIL Клиника").build());
        Distributor dist = distributorRepository.save(
                Distributor.builder().name("ZZMAIL Дистр").email("d@x.kz").build());
        Tender tender = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZMAIL-T1").facility(fac).status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder()
                .tender(tender).distributor(dist).status("SENT").build());
        Long prId = pr.getId();

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@x.kz",
                "Re: Запрос КП " + KpToken.subjectToken(prId), "Наша цена 100000 тенге", null, null));
        user.deliver(message("clinic@x.kz",
                "Заявка на оборудование", "Прошу выставить КП", sampleXlsx(), "zayavka.xlsx"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        PriceRequest reloaded = priceRequestRepository.findById(prId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("RESPONDED");
        assertThat(reloaded.getResponseDate()).isNotNull();

        List<InboundEmail> all = inboundEmailRepository.findAll();
        assertThat(all).anyMatch(e -> e.getType() == InboundType.SUPPLIER_RESPONSE
                && prId.equals(e.getMatchedPriceRequestId()));
        InboundEmail client = all.stream()
                .filter(e -> e.getType() == InboundType.CLIENT_REQUEST).findFirst().orElseThrow();
        assertThat(client.getAttachment()).isNotNull();
        assertThat(client.getAttachmentName()).endsWith(".xlsx");
        assertThat(client.getMarket()).isEqualTo(Market.KZ);

        long count = inboundEmailRepository.count();
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();   // повторный опрос: письма прочитаны (SEEN) → не задваивает
        assertThat(inboundEmailRepository.count()).isEqualTo(count);
    }

    private MimeMessage message(String from, String subject, String body, byte[] attach, String name)
            throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        msg.setSubject(subject, "UTF-8");
        if (attach == null) {
            msg.setText(body, "UTF-8");
        } else {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "UTF-8");
            MimeBodyPart filePart = new MimeBodyPart();
            filePart.setContent(attach, "application/octet-stream");
            filePart.setFileName(name);
            filePart.setDisposition(MimeBodyPart.ATTACHMENT);
            MimeMultipart mp = new MimeMultipart();
            mp.addBodyPart(textPart);
            mp.addBodyPart(filePart);
            msg.setContent(mp);
        }
        msg.saveChanges();
        return msg;
    }

    private byte[] sampleXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Производитель");
            h.createCell(2).setCellValue("Кол-во");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Аппарат УЗИ");
            r.createCell(1).setCellValue("Mindray");
            r.createCell(2).setCellValue(2);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 5: Запустить — падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.MailReceiveServiceIntegrationTest"`
Expected: FAIL — нет `MailReceiveService` (компиляция).

- [ ] **Step 6: Реализовать `MailReceiveService`**

Create `src/main/java/com/vladoose/nir/service/MailReceiveService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.util.KpToken;
import jakarta.mail.*;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;

@Service
public class MailReceiveService {

    private static final Logger log = LoggerFactory.getLogger(MailReceiveService.class);

    private final PriceRequestRepository priceRequestRepository;
    private final InboundEmailRepository inboundEmailRepository;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final Market mailboxMarket;

    public MailReceiveService(PriceRequestRepository priceRequestRepository,
                              InboundEmailRepository inboundEmailRepository,
                              @Value("${mail.imap.enabled:false}") boolean enabled,
                              @Value("${mail.imap.host:localhost}") String host,
                              @Value("${mail.imap.port:3143}") int port,
                              @Value("${mail.imap.username:}") String username,
                              @Value("${mail.imap.password:}") String password,
                              @Value("${mail.imap.protocol:imap}") String protocol,
                              @Value("${mail.imap.market:KZ}") String market) {
        this.priceRequestRepository = priceRequestRepository;
        this.inboundEmailRepository = inboundEmailRepository;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.mailboxMarket = Market.fromHeader(market);
    }

    public Market getMailboxMarket() {
        return mailboxMarket;
    }

    /** Предполагает, что MarketContext уже установлен вызывающим (планировщик/контроллер). */
    @Transactional
    public PollResultResponse poll() {
        PollResultResponse result = new PollResultResponse();
        if (!enabled) {
            result.setEnabled(false);
            result.setMessage("Приём почты выключен (MAIL_IMAP_ENABLED=false)");
            return result;
        }
        result.setEnabled(true);
        Store store = null;
        Folder inbox = null;
        try {
            Properties props = new Properties();
            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(host, port, username, password);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            for (Message msg : messages) {
                handle(msg, result);
                msg.setFlag(Flags.Flag.SEEN, true);
                result.setFetched(result.getFetched() + 1);
            }
            result.setMessage("Обработано писем: " + result.getFetched());
        } catch (Exception e) {
            log.warn("Ошибка приёма почты: {}", e.getMessage());
            result.setMessage("Ошибка подключения к почте: " + e.getMessage());
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    private void handle(Message msg, PollResultResponse result) throws Exception {
        String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? msg.getFrom()[0].toString() : "";
        String subject = msg.getSubject() == null ? "" : msg.getSubject();

        StringBuilder text = new StringBuilder();
        byte[] attachment = null;
        String attachmentName = null;

        Object content = msg.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String fileName = part.getFileName();
                if (attachment == null && fileName != null && (fileName.toLowerCase().endsWith(".xlsx")
                        || fileName.toLowerCase().endsWith(".xls"))) {   // берём ПЕРВОЕ Excel-вложение
                    try (InputStream is = part.getInputStream()) {
                        attachment = is.readAllBytes();
                        attachmentName = fileName;
                    }
                } else if (part.isMimeType("text/plain")) {
                    Object pc = part.getContent();
                    if (pc != null) text.append(pc);
                }
            }
        } else if (content != null) {
            text.append(content);
        }

        Optional<Long> kp = KpToken.parse(subject);
        InboundType type;
        Long matchedId = null;
        if (kp.isPresent()) {
            type = InboundType.SUPPLIER_RESPONSE;
            matchedId = matchSupplierResponse(kp.get(), text.toString());
            result.setSupplierResponses(result.getSupplierResponses() + 1);
        } else if (attachment != null) {
            type = InboundType.CLIENT_REQUEST;
            result.setClientRequests(result.getClientRequests() + 1);
        } else {
            type = InboundType.UNMATCHED;
            result.setUnmatched(result.getUnmatched() + 1);
        }

        InboundEmail ie = InboundEmail.builder()
                .fromAddress(trunc(from, 320))
                .subject(trunc(subject, 998))
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .type(type)
                .matchedPriceRequestId(matchedId)
                .attachmentName(attachmentName)
                .attachment(attachment)
                .excerpt(trunc(text.toString(), 2000))
                .status(InboundStatus.NEW)
                .build();
        inboundEmailRepository.save(ie);  // @PrePersist стампит market из MarketContext
    }

    /** Найти PriceRequest по id из токена и пометить RESPONDED. Возвращает id, если сопоставлено. */
    private Long matchSupplierResponse(Long priceRequestId, String body) {
        Optional<PriceRequest> opt = priceRequestRepository.findById(priceRequestId);
        if (opt.isEmpty()) return null;
        PriceRequest pr = opt.get();
        pr.setStatus("RESPONDED");
        pr.setResponseDate(LocalDate.now());
        pr.setNote(trunc(body, 4000));
        priceRequestRepository.save(pr);
        return priceRequestId;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 7: Зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.MailReceiveServiceIntegrationTest"` → PASS (ответ поставщика → RESPONDED; письмо клиники → CLIENT_REQUEST с вложением + market KZ; повторный опрос не задваивает).

- [ ] **Step 8: Полный suite**

Run (sandbox off): `./gradlew test` → только 2 известных `ApplyAutoFillServiceTest`.

- [ ] **Step 9: Commit**

```bash
git add build.gradle src/main/resources/application.yaml src/main/java/com/vladoose/nir/dto/response/PollResultResponse.java src/main/java/com/vladoose/nir/service/MailReceiveService.java src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java
git commit -m "$(cat <<'EOF'
feat(email): MailReceiveService — IMAP-опрос, классификация, матчинг ответов к КП (GreenMail-тест)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Backend — планировщик + `InboundController` (список/опрос/превью/processed) (TDD)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/Nir2Application.java` (`@EnableScheduling`)
- Create: `src/main/java/com/vladoose/nir/service/MailPollScheduler.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/InboundEmailResponse.java`
- Create: `src/main/java/com/vladoose/nir/controller/InboundController.java`
- Test: `src/test/java/com/vladoose/nir/mail/InboundImportReuseTest.java`

**Interfaces:**
- Consumes: `MailReceiveService` (T3), `InboundEmailRepository` (T1), `PrivateRequestImportService.preview(byte[],String)`, `MarketContext`.
- Produces: `MailPollScheduler.run() : PollResultResponse` (ставит `MarketContext` = рынок ящика, зовёт `mailReceiveService.poll()`, чистит); `@Scheduled tick()` под флагом; `InboundEmailResponse` DTO; HTTP `GET /api/inbound`, `POST /api/inbound/poll`, `POST /api/inbound/{id}/preview`, `POST /api/inbound/{id}/processed`.

- [ ] **Step 1: Падающий тест переиспользования D1 на сохранённом вложении**

Create `src/test/java/com/vladoose/nir/mail/InboundImportReuseTest.java`:
```java
package com.vladoose.nir.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InboundImportReuseTest {

    @Autowired InboundEmailRepository repository;
    @Autowired PrivateRequestImportService importService;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void preview_runsExtractorOnStoredAttachment_andMarkProcessed() throws Exception {
        MarketContext.set(Market.KZ);
        InboundEmail e = repository.save(InboundEmail.builder()
                .fromAddress("c@x.kz").subject("Заявка")
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .type(InboundType.CLIENT_REQUEST).status(InboundStatus.NEW)
                .attachmentName("z.xlsx").attachment(xlsx()).build());
        repository.flush();

        ImportPreviewResponse p = importService.preview(e.getAttachment(), e.getAttachmentName());
        assertThat(p.getColumns()).extracting("field").contains(LineField.NAME);

        e.setStatus(InboundStatus.PROCESSED);
        repository.save(e);
        assertThat(repository.findById(e.getId()).orElseThrow().getStatus())
                .isEqualTo(InboundStatus.PROCESSED);
    }

    private byte[] xlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Производитель");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("Монитор пациента");
            r.createCell(1).setCellValue("Philips");
            wb.write(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 2: Запустить — должен пройти (использует только готовое из T1+D1)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.mail.InboundImportReuseTest"` → PASS (подтверждает: парсер D1 работает на байтах из БД; статус переключается). Это тест-страховка шва переиспользования перед добавлением эндпоинтов.

- [ ] **Step 3: `@EnableScheduling`**

В `src/main/java/com/vladoose/nir/Nir2Application.java` добавить аннотацию и импорт:
```java
import org.springframework.scheduling.annotation.EnableScheduling;
```
```java
@SpringBootApplication
@EnableScheduling
public class Nir2Application {
```

- [ ] **Step 4: Планировщик**

Create `src/main/java/com/vladoose/nir/service/MailPollScheduler.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MailPollScheduler {

    private final MailReceiveService mailReceiveService;
    private final boolean enabled;

    public MailPollScheduler(MailReceiveService mailReceiveService,
                             @Value("${mail.imap.enabled:false}") boolean enabled) {
        this.mailReceiveService = mailReceiveService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${mail.imap.poll-ms:300000}")
    public void tick() {
        if (enabled) {
            run();
        }
    }

    /** Ставит рынок ящика, зовёт @Transactional poll() (отдельный бин → прокси/аспект работают), чистит. */
    public PollResultResponse run() {
        MarketContext.set(mailReceiveService.getMailboxMarket());
        try {
            return mailReceiveService.poll();
        } finally {
            MarketContext.clear();
        }
    }
}
```

- [ ] **Step 5: DTO ответа списка**

Create `src/main/java/com/vladoose/nir/dto/response/InboundEmailResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class InboundEmailResponse {
    private Long id;
    private String fromAddress;
    private String subject;
    private OffsetDateTime receivedAt;
    private String type;
    private Long matchedPriceRequestId;
    private String attachmentName;
    private boolean hasAttachment;
    private String excerpt;
    private String status;
}
```

- [ ] **Step 6: Контроллер**

Create `src/main/java/com/vladoose/nir/controller/InboundController.java`:
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.dto.response.InboundEmailResponse;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.InboundStatus;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.service.MailPollScheduler;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inbound")
public class InboundController {

    private final InboundEmailRepository repository;
    private final MailPollScheduler scheduler;
    private final PrivateRequestImportService importService;

    public InboundController(InboundEmailRepository repository,
                             MailPollScheduler scheduler,
                             PrivateRequestImportService importService) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.importService = importService;
    }

    @GetMapping
    public List<InboundEmailResponse> list() {
        return repository.findAllByOrderByReceivedAtDesc().stream().map(this::toResponse).toList();
    }

    @PostMapping("/poll")
    @PreAuthorize("hasRole('ADMIN')")
    public PollResultResponse poll() {
        return scheduler.run();
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse preview(@PathVariable Long id) {
        InboundEmail e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Письмо не найдено: id=" + id));
        if (e.getAttachment() == null) {
            throw new IllegalArgumentException("У письма нет Excel-вложения");
        }
        return importService.preview(e.getAttachment(), e.getAttachmentName());
    }

    @PostMapping("/{id}/processed")
    @PreAuthorize("hasRole('ADMIN')")
    public void markProcessed(@PathVariable Long id) {
        InboundEmail e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Письмо не найдено: id=" + id));
        e.setStatus(InboundStatus.PROCESSED);
        repository.save(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) {
        return Map.of("message", ex.getMessage());
    }

    private InboundEmailResponse toResponse(InboundEmail e) {
        InboundEmailResponse r = new InboundEmailResponse();
        r.setId(e.getId());
        r.setFromAddress(e.getFromAddress());
        r.setSubject(e.getSubject());
        r.setReceivedAt(e.getReceivedAt());
        r.setType(e.getType() == null ? null : e.getType().name());
        r.setMatchedPriceRequestId(e.getMatchedPriceRequestId());
        r.setAttachmentName(e.getAttachmentName());
        r.setHasAttachment(e.getAttachment() != null);
        r.setExcerpt(e.getExcerpt());
        r.setStatus(e.getStatus() == null ? null : e.getStatus().name());
        return r;
    }
}
```
(`NotFoundException` живёт в `com.vladoose.nir.exception` — проверить точный пакет по существующему использованию, например в `PriceRequestService`; импортировать оттуда.)

- [ ] **Step 7: Полный suite + контекст**

Run (sandbox off): `./gradlew test` → компилируется, контекст поднимается (`@EnableScheduling` + планировщик + контроллер грузятся; `@Scheduled` под флагом `enabled=false` по умолчанию — фоновый опрос в тестах не идёт), только 2 известных `ApplyAutoFillServiceTest`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/Nir2Application.java src/main/java/com/vladoose/nir/service/MailPollScheduler.java src/main/java/com/vladoose/nir/dto/response/InboundEmailResponse.java src/main/java/com/vladoose/nir/controller/InboundController.java src/test/java/com/vladoose/nir/mail/InboundImportReuseTest.java
git commit -m "$(cat <<'EOF'
feat(email): планировщик опроса + InboundController (список/опрос/превью/processed)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Frontend — страница «Входящие письма» (список + опрос + импорт письма клиники через грид D1)

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/layout/layout.component.ts`
- Create: `frontend/src/app/pages/inbound/inbound.component.ts`

**Interfaces:**
- Consumes: `GET /api/inbound` → `[{id,fromAddress,subject,receivedAt,type,matchedPriceRequestId,attachmentName,hasAttachment,excerpt,status}]`; `POST /api/inbound/poll` → `{enabled,fetched,supplierResponses,clientRequests,unmatched,message}`; `POST /api/inbound/{id}/preview` → `ImportPreviewResponse`; `POST /api/inbound/{id}/processed`; существующий `POST /api/private-requests/import/commit`.
- Produces: `ApiService.getInbound()`, `pollInbound()`, `previewInbound(id)`, `markInboundProcessed(id)`; маршрут `/inbound`; пункт сайдбара «Входящие».

- [ ] **Step 1: Методы ApiService**

В `frontend/src/app/services/api.service.ts` рядом с `commitImport`:
```typescript
  getInbound(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/inbound`);
  }
  pollInbound(): Observable<any> {
    return this.http.post<any>(`${this.base}/inbound/poll`, {});
  }
  previewInbound(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/inbound/${id}/preview`, {});
  }
  markInboundProcessed(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/inbound/${id}/processed`, {});
  }
```

- [ ] **Step 2: Маршрут**

В `frontend/src/app/app.routes.ts`: добавить импорт вверху рядом с прочими:
```typescript
import { InboundComponent } from './pages/inbound/inbound.component';
```
И child-маршрут внутри `children: [...]` родителя `LayoutComponent` (рядом с `private-requests`):
```typescript
      { path: 'inbound', component: InboundComponent },
```

- [ ] **Step 3: Пункт сайдбара**

В `frontend/src/app/layout/layout.component.ts` в группе «Заявки» (`<div class="nav-group">` со `<span class="nav-group-title">Заявки</span>`) добавить третью ссылку (иконка `inbox`, чтобы отличалась от `clipboard-list`):
```html
            <a routerLink="/inbound" routerLinkActive="active">
              <svg lucideIcon="inbox" [size]="16"></svg> Входящие
            </a>
```
(Если иконка `inbox` не зарегистрирована в наборе lucide компонента — использовать уже импортированную, напр. `clipboard-list`. Проверить список иконок в этом файле.)

- [ ] **Step 4: Компонент «Входящие»**

Create `frontend/src/app/pages/inbound/inbound.component.ts`:
```typescript
import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-inbound',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
  <div class="page">
    <div class="head">
      <div>
        <h1>Входящие письма</h1>
        <p class="sub">Ящик zakup@westmed.kz: ответы поставщиков на КП и письма клиник с таблицами.</p>
      </div>
      <button class="btn-primary" [disabled]="polling" (click)="poll()">⟳ Проверить почту</button>
    </div>

    <table class="grid" *ngIf="rows.length">
      <thead>
        <tr><th>Отправитель</th><th>Тема</th><th>Тип</th><th>Статус</th><th></th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let r of rows">
          <td>{{ r.fromAddress }}</td>
          <td>{{ r.subject }}</td>
          <td>
            <span class="badge" [class.b-sup]="r.type==='SUPPLIER_RESPONSE'"
                  [class.b-cli]="r.type==='CLIENT_REQUEST'" [class.b-unm]="r.type==='UNMATCHED'">
              {{ typeLabel(r.type) }}
            </span>
            <span *ngIf="r.type==='SUPPLIER_RESPONSE' && r.matchedPriceRequestId" class="muted"> · КП #{{ r.matchedPriceRequestId }}</span>
          </td>
          <td>{{ r.status==='PROCESSED' ? 'Обработано' : 'Новое' }}</td>
          <td>
            <button *ngIf="r.type==='CLIENT_REQUEST' && r.hasAttachment && r.status!=='PROCESSED'"
                    class="btn-line-solid" (click)="openImport(r)">Импортировать</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p class="empty" *ngIf="!rows.length && !loading">Писем пока нет. Нажмите «Проверить почту».</p>

    <!-- Импорт письма клиники через грид D1 -->
    <div class="import-panel" *ngIf="importEmailId !== null">
      <div class="import-head">
        <h3>Импорт заявки из письма</h3>
        <button class="x" (click)="closeImport()">×</button>
      </div>
      <div *ngIf="importPreview">
        <label class="lbl">Клиент</label>
        <select [(ngModel)]="importClientId" class="client-sel">
          <option [ngValue]="null" disabled>— выберите —</option>
          <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
        </select>
        <div class="grid-wrap">
          <table class="import-grid">
            <thead>
              <tr>
                <th *ngFor="let c of importPreview.columns">
                  <div class="ih">{{ c.header || '—' }}</div>
                  <select [(ngModel)]="c.field" [ngModelOptions]="{standalone:true}">
                    <option *ngFor="let o of fieldOptions" [ngValue]="o.v">{{ o.l }}</option>
                  </select>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let row of importPreview.rows">
                <td *ngFor="let cell of row">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="err" *ngIf="importError">{{ importError }}</div>
        <div class="import-actions">
          <button class="btn-primary" [disabled]="importing" (click)="createFromImport()">Создать заявку</button>
          <button class="btn-line-solid" (click)="closeImport()">Отмена</button>
        </div>
      </div>
    </div>
  </div>
  `,
  styles: [`
    .page { padding: 20px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    .head h1 { margin: 0; }
    .sub { color: #6b7280; font-size: 13px; margin: 4px 0 0; }
    .btn-primary { background: #2563eb; color: #fff; border: none; border-radius: 6px; padding: 8px 16px; cursor: pointer; }
    .grid { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; }
    .grid th { background: #f9fafb; text-align: left; padding: 10px; font-size: 12px; color: #374151; }
    .grid td { padding: 10px; border-top: 1px solid #f0f0f0; font-size: 13px; }
    .badge { font-size: 11px; padding: 2px 8px; border-radius: 999px; }
    .b-sup { background: #dcfce7; color: #166534; }
    .b-cli { background: #dbeafe; color: #1e40af; }
    .b-unm { background: #f3f4f6; color: #6b7280; }
    .muted { color: #6b7280; font-size: 12px; }
    .empty { color: #6b7280; padding: 20px 0; }
    .import-panel { border: 1px solid #e5e7eb; border-radius: 10px; padding: 16px; margin-top: 16px; background: #fff; }
    .import-head { display: flex; justify-content: space-between; align-items: center; }
    .import-head .x { background: none; border: none; font-size: 22px; cursor: pointer; color: #6b7280; }
    .lbl { display: block; font-size: 12px; color: #374151; margin: 8px 0 4px; }
    .client-sel { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; margin-bottom: 12px; min-width: 260px; }
    .grid-wrap { overflow-x: auto; border: 1px solid #eee; border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    .import-grid th { background: #f9fafb; padding: 8px; border: 1px solid #eee; vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 12px; }
    .import-grid td { padding: 6px 8px; border: 1px solid #f0f0f0; white-space: nowrap; }
    .import-actions { display: flex; gap: 8px; margin-top: 12px; }
    .err { color: #b91c1c; font-size: 13px; margin: 8px 0; }
    .btn-line-solid { background: #fff; border: 1px solid #9ca3af; border-radius: 6px; padding: 6px 14px; cursor: pointer; font-size: 13px; color: #374151; }
  `],
})
export class InboundComponent {
  rows: any[] = [];
  facilities: any[] = [];
  loading = false;
  polling = false;

  importEmailId: number | null = null;
  importPreview: any = null;
  importClientId: number | null = null;
  importError = '';
  importing = false;
  fieldOptions = [
    { v: 'NAME', l: 'Наименование' },
    { v: 'MANUFACT', l: 'Бренд' },
    { v: 'QUANTITY', l: 'Кол-во' },
    { v: 'IGNORE', l: 'Игнорировать' },
  ];

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService) {
    this.api.getFacilities().subscribe({ next: d => { this.facilities = d; this.cdr.detectChanges(); } });
    this.load();
  }

  load() {
    this.loading = true;
    this.api.getInbound().subscribe({
      next: d => { this.rows = d || []; this.loading = false; this.cdr.detectChanges(); },
      error: () => { this.loading = false; this.cdr.detectChanges(); },
    });
  }

  poll() {
    this.polling = true;
    this.api.pollInbound().subscribe({
      next: (r: any) => {
        this.polling = false;
        if (r && r.enabled === false) {
          this.notify.error(r.message || 'Приём почты выключен');
        } else {
          this.notify.success((r && r.message) || 'Почта проверена');
          this.load();
        }
        this.cdr.detectChanges();
      },
      error: (e: any) => {
        this.polling = false;
        this.notify.error('Ошибка: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  typeLabel(t: string): string {
    return t === 'SUPPLIER_RESPONSE' ? 'Ответ поставщика'
      : t === 'CLIENT_REQUEST' ? 'Письмо клиники' : 'Прочее';
  }

  openImport(r: any) {
    this.importEmailId = r.id;
    this.importPreview = null;
    this.importClientId = null;
    this.importError = '';
    this.api.previewInbound(r.id).subscribe({
      next: (p) => { this.importPreview = p; this.cdr.detectChanges(); },
      error: (e) => { this.importError = e.error?.message || 'Не удалось разобрать вложение'; this.cdr.detectChanges(); },
    });
  }

  closeImport() { this.importEmailId = null; this.importPreview = null; }

  createFromImport() {
    if (this.importEmailId === null) return;
    if (!this.importClientId) { this.importError = 'Выберите клиента'; return; }
    const cols = this.importPreview?.columns || [];
    const nameCol = cols.find((c: any) => c.field === 'NAME');
    if (!nameCol) { this.importError = 'Отметьте колонку с наименованием'; return; }
    const manuCol = cols.find((c: any) => c.field === 'MANUFACT');
    const qtyCol = cols.find((c: any) => c.field === 'QUANTITY');
    const lines = (this.importPreview.rows || [])
      .map((row: string[]) => ({
        name: row[nameCol.index],
        manufact: manuCol ? row[manuCol.index] : null,
        quantity: qtyCol ? (parseInt(row[qtyCol.index], 10) || 1) : 1,
      }))
      .filter((l: any) => l.name && String(l.name).trim());
    if (!lines.length) { this.importError = 'Нет строк с наименованием'; return; }
    const mappings = cols
      .filter((c: any) => c.field && c.field !== 'IGNORE')
      .map((c: any) => ({ header: c.header, field: c.field }));
    const emailId = this.importEmailId;
    this.importing = true;
    this.api.commitImport({ clientFacilityId: this.importClientId, mappings, lines }).subscribe({
      next: () => {
        this.api.markInboundProcessed(emailId as number).subscribe({ next: () => {}, error: () => {} });
        this.importing = false;
        this.closeImport();
        this.notify.success('Заявка создана из письма');
        this.load();
        this.cdr.detectChanges();
      },
      error: (e: any) => {
        this.importing = false;
        this.importError = e.error?.message || 'Ошибка импорта';
        this.cdr.detectChanges();
      },
    });
  }
}
```

- [ ] **Step 5: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/app.routes.ts frontend/src/app/layout/layout.component.ts frontend/src/app/pages/inbound/inbound.component.ts
git commit -m "$(cat <<'EOF'
feat(email): фронт — страница «Входящие письма» (список + опрос + импорт письма клиники через грид D1)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Финальная проверка (после всех задач)

- [ ] `./gradlew test` (sandbox off) — только 2 известных `ApplyAutoFillServiceTest`; все mail-тесты зелёные (включая GreenMail-интеграцию).
- [ ] `cd frontend && npm run build` — фронт собирается.
- [ ] **E2E-смоук (рынок KZ):** поднять бэк (`bootRun`, опрос ВЫКЛ по умолчанию). «Заявки → Входящие» → «Проверить почту» → тост «приём выключен» (корректно, без падения). Опционально с включённым `MAIL_IMAP_ENABLED` + dev-IMAP: положить письмо самому себе → опрос показывает классификацию; письмо клиники с Excel → «Импортировать» → грид D1 → выбрать клиента → создать заявку → письмо помечается «Обработано».
- [ ] Проверить, что отправка КП (bulk «Отправить КП») кладёт `[КП-<id>]` в тему (на MailHog UID :8025 видно тему письма).
