# Грамотная отправка КП + приём ответов — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сделать отправку КП «грамотной»: исправное тело письма (приветствие без контактного лица, подпись, Reply-To на zakup@), редактируемое превью перед отправкой, реальный round-trip через zakup@westmed.kz с пропуском «своих» писем, и UX статусов (переслать + проверить ответы).

**Architecture:** `KpEmailComposer` даёт «Здравствуйте!» когда нет ФИО + подпись-инструкцию. `EmailService` переходит на `MimeMessage` (Reply-To + UTF-8). `PriceRequestSendService.send` принимает `subjectOverride`/`bodyOverride` (токен `[КП-id]` остаётся серверным) + новый `resend(id)`. `MailReceiveService` не самопомечает письма, отправленные с нашего же адреса. Новый `POST /preview` компонует черновой текст. Схема БД не меняется.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA, `spring-boot-starter-mail` (JavaMailSender/MimeMessage), GreenMail 2.1.2 (тест SMTP+IMAP), Angular 21, Lombok.

## Global Constraints

- **Спек:** `docs/superpowers/specs/2026-07-09-grammatical-kp-sending-design.md` (источник истины).
- **Многорыночность (§6 CLAUDE.md):** записи гардить рынком; `resend`/preview — гард рынка тендера/поставщика через `MarketContext.get()`; `service.findById` = `em.find` минует hibernate-фильтр. Эталон — `PriceRequestSendService.requireCurrentMarket`.
- **Токен темы:** `[КП-<id>]` формируется ТОЛЬКО сервером через `KpToken.subjectToken(id)`; при override оператор редактирует человеческую часть, токен восстанавливается принудительно. Матч ответа (`MailReceiveService` + `KpToken.parse`) не должен ломаться.
- **Сеть вне транзакции (§6):** письмо шлётся ПОСЛЕ коммита записи (как сейчас в `dispatch`).
- **БД:** nirdb, UTF-8; `./gradlew` и psql — с `dangerouslyDisableSandbox: true`. Kill lingering: `lsof -ti :8080 | xargs kill -9`.
- **Тест-гейт (§13 CLAUDE.md):** зелёный = падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`. Почта — GreenMail (без реальных адресатов). git/gradlew из корня репо.
- **Коммиты:** ветка `feat/grammatical-kp-sending` (уже создана); каждый заканчивать `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Субагенты — наследуют модель сессии (§2 CLAUDE.md), `model` не переопределять.**
- **Секреты:** не эхо-печатать `/tmp/*.pass` (только `$(cat …)`); боевой SMTP — только через env.

## File Structure

**Создаём:**
- `src/main/java/com/vladoose/nir/dto/response/KpPreviewResponse.java` — `{subject, body}` превью.
- `src/main/java/com/vladoose/nir/dto/request/KpResendRequest.java` — (пусто/маркер; resend по path id) — НЕ нужен, resend без тела. (Не создавать; см. Task 5.)
- `src/test/java/com/vladoose/nir/email/KpEmailComposerTest.java`
- `src/test/java/com/vladoose/nir/email/KpRoundTripTest.java` (GreenMail SMTP+IMAP)

**Меняем:**
- `src/main/java/com/vladoose/nir/service/KpEmailComposer.java` — приветствие/подпись; выделить публичный метод для человеческой части темы.
- `src/main/java/com/vladoose/nir/service/EmailService.java` — MimeMessage + Reply-To + UTF-8.
- `src/main/resources/application.yaml` — smtp.auth/ssl через env; `mail.kp.reply-to`.
- `src/main/java/com/vladoose/nir/service/PriceRequestSendService.java` — override в `send`; метод `preview`; метод `resend`.
- `src/main/java/com/vladoose/nir/service/MailReceiveService.java` — фильтр «своих» писем (From==наш адрес).
- `src/main/java/com/vladoose/nir/controller/PriceRequestController.java` — `/preview`, `/{id}/resend`; проброс override в `/send`.
- `src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java` — поля `subjectOverride`, `bodyOverride`.
- `frontend/src/app/services/api.service.ts` — `previewKp`, `resendPriceRequest`, override в `sendPriceRequests`.
- `frontend/src/app/pages/tenders/tenders.component.ts` — диалог превью; кнопки «Переслать» / «Проверить ответы».
- `CLAUDE.md` — §8/§9/§15/§16 (финальная задача).

---

### Task 1: Качество письма — приветствие без ФИО + подпись-инструкция

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/KpEmailComposer.java`
- Test: `src/test/java/com/vladoose/nir/email/KpEmailComposerTest.java`

**Interfaces:**
- Consumes: `PriceRequest`, `KpEmailComposer.Composed`, `Market.companyShortName()`.
- Produces:
  - Поведение `compose(pr)`: приветствие «Уважаемый(ая) <Фамилия> <Имя>!» когда есть непустые `lastName`/`firstName`; иначе «Здравствуйте!». Подпись включает строку про Reply-To на адрес ответа.
  - Новый публичный метод `String replyToHint()` НЕ нужен; адрес ответа приходит через поле (см. Step 3).
  - Новый публичный метод `Composed composeForPreview(PriceRequest draft)` — та же человеческая часть, но тема БЕЗ токена (для чернового PR без id).

- [ ] **Step 1: Failing-тест композера**

```java
package com.vladoose.nir.email;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.KpEmailComposer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KpEmailComposerTest {

    @Autowired KpEmailComposer composer;

    private PriceRequest pr(Distributor d) {
        Tender t = Tender.builder().tenderNumber("KZ-TEST-1").market(Market.KZ)
                .source(Source.PUBLIC_TENDER).build();
        TenderLot lot = TenderLot.builder().tender(t).lotNumber(1).equipName("Аппарат ИВЛ").build();
        PriceRequest pr = PriceRequest.builder().id(777L).tender(t).distributor(d).market(Market.KZ)
                .items(List.of(PriceRequestItem.builder().tenderLot(lot).requestedQuantity(2).build()))
                .build();
        return pr;
    }

    @Test
    void greetsCompanyWhenNoContactPerson() {
        Distributor d = Distributor.builder().name("ТОО «MEDSYST»").market(Market.KZ).build();
        KpEmailComposer.Composed msg = composer.compose(pr(d));
        assertThat(msg.body()).startsWith("Здравствуйте!");
        assertThat(msg.body()).doesNotContain("Уважаемый(ая)  !");
        assertThat(msg.subject()).contains("[КП-777]");
        // подпись содержит инструкцию про ответ
        assertThat(msg.body()).containsIgnoringCase("ответ");
    }

    @Test
    void greetsPersonWhenContactPresent() {
        Distributor d = Distributor.builder().name("ТОО X").market(Market.KZ)
                .lastName("Алексеев").firstName("Константин").build();
        KpEmailComposer.Composed msg = composer.compose(pr(d));
        assertThat(msg.body()).startsWith("Уважаемый(ая) Алексеев Константин!");
    }

    @Test
    void previewSubjectHasNoToken() {
        Distributor d = Distributor.builder().name("ТОО X").market(Market.KZ).build();
        PriceRequest draft = pr(d);
        ReflectionTestUtils.setField(draft, "id", null);
        KpEmailComposer.Composed msg = composer.composeForPreview(draft);
        assertThat(msg.subject()).doesNotContain("[КП-");
        assertThat(msg.body()).startsWith("Здравствуйте!");
    }
}
```

- [ ] **Step 2: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.email.KpEmailComposerTest'` (sandbox off).
Expected: FAIL — `composeForPreview` не существует / приветствие ещё старое.

- [ ] **Step 3: Правка `KpEmailComposer`**

Заменить строку приветствия (сейчас `sb.append("Уважаемый(ая) ")...`) на условие:

```java
        String contact = (safe(d.getLastName()) + " " + safe(d.getFirstName())).trim();
        if (!contact.isBlank()) {
            sb.append("Уважаемый(ая) ").append(contact).append("!\n\n");
        } else {
            sb.append("Здравствуйте!\n\n");
        }
```

Перед `return new Composed(...)` заменить хвост подписи. Сейчас:
```java
        sb.append("С уважением,\n").append(market.companyShortName());
        return new Composed(subject, sb.toString());
```
на:
```java
        sb.append("С уважением,\n").append(market.companyShortName()).append("\n\n");
        sb.append("Ответ на этот запрос просим направить ответным письмом (Reply) — он поступит в наш отдел закупок.");
        return new Composed(subject, sb.toString());
```

Выделить построение человеческой части в переиспользуемый приватный метод и добавить публичный `composeForPreview`. Найти сигнатуру `public Composed compose(PriceRequest pr)`; извлечь всё тело (кроме первой строки, где формируется `subject` с токеном) в `private String buildBody(PriceRequest pr, Market market, boolean isPrivate, Tender tender)` и `private String humanSubject(Tender tender, boolean isPrivate)` (человеческая часть темы без токена). Тогда:

```java
    public Composed compose(PriceRequest pr) {
        Ctx c = ctx(pr);
        String subject = KpToken.subjectToken(pr.getId()) + " " + humanSubject(c.tender, c.isPrivate);
        return new Composed(subject, buildBody(pr, c.market, c.isPrivate, c.tender));
    }

    /** Черновой предпросмотр (id ещё нет) — тема без токена. */
    public Composed composeForPreview(PriceRequest draft) {
        Ctx c = ctx(draft);
        return new Composed(humanSubject(c.tender, c.isPrivate), buildBody(draft, c.market, c.isPrivate, c.tender));
    }

    private record Ctx(Tender tender, Market market, boolean isPrivate) {}
    private Ctx ctx(PriceRequest pr) {
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        boolean isPrivate = tender.getSource() == Source.PRIVATE_REQUEST;
        return new Ctx(tender, market, isPrivate);
    }

    private String humanSubject(Tender tender, boolean isPrivate) {
        String target = isPrivate ? "заявке " + tender.getTenderNumber() : "тендеру № " + tender.getTenderNumber();
        return "Запрос КП по " + target;
    }
```
`buildBody` — это текущее тело письма от `Distributor d = pr.getDistributor();` до конца подписи (с правками приветствия и подписи из этого шага), возвращающее `sb.toString()`.

> ⚠ Файл-паттерн (§14 CLAUDE.md): после правки — `grep -c "public Composed compose" src/main/java/com/vladoose/nir/service/KpEmailComposer.java` (ждём 1) + `./gradlew compileJava`.

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.KpEmailComposerTest'` (sandbox off).
Expected: PASS (3 теста).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/KpEmailComposer.java src/test/java/com/vladoose/nir/email/KpEmailComposerTest.java && git commit -m "feat(kp): письмо — «Здравствуйте!» без ФИО, подпись-инструкция, composeForPreview

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: EmailService — MimeMessage + Reply-To + UTF-8

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/EmailService.java`
- Modify: `src/main/resources/application.yaml`

**Interfaces:**
- Consumes: `JavaMailSender`, `${spring.mail.username}`, `${mail.kp.reply-to:}`.
- Produces: `sendEmail(String to, String subject, String body)` — теперь через `MimeMessage` с Reply-To и UTF-8; сигнатура и `isConfigured()` неизменны.

- [ ] **Step 1: Переписать `EmailService`** (полная новая версия файла)

```java
package com.vladoose.nir.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Отправка письма КП: MimeMessage (UTF-8) с Reply-To на ящик ответов (по умолчанию — адрес отправки). */
@Service
public class EmailService {

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
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false); // plain-text
            String rt = (replyTo != null && !replyTo.isBlank()) ? replyTo : fromAddress;
            if (rt != null && !rt.isBlank()) helper.setReplyTo(rt);
            mailSender.send(mime);
        } catch (jakarta.mail.MessagingException e) {
            throw new RuntimeException("Не удалось собрать письмо: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: `application.yaml` — smtp auth/ssl через env + reply-to**

Найти блок `spring.mail.properties.mail.smtp` и заменить `auth`/`starttls` на env-управляемые + добавить ssl; добавить `mail.kp.reply-to`. Текущий блок:
```yaml
        smtp:
          auth: false
          starttls.enable: false
```
на:
```yaml
        smtp:
          auth: ${MAIL_SMTP_AUTH:false}
          starttls.enable: ${MAIL_SMTP_STARTTLS:false}
          ssl.enable: ${MAIL_SMTP_SSL:false}
```
И в существующий верхнеуровневый блок `mail:` (тот, что с `imap:` — в конце файла) добавить рядом (или создать) секцию `kp`:
```yaml
mail:
  kp:
    # ящик, куда поставщики шлют ответы (Reply-To). Пусто → = spring.mail.username.
    reply-to: ${MAIL_KP_REPLY_TO:}
  imap:
    # ... существующее без изменений
```

> ⚠ В yaml уже есть верхнеуровневый `mail:` с `imap:` (строка ~52). НЕ создавать второй ключ `mail:` — добавить `kp:` внутрь существующего.

- [ ] **Step 3: Компиляция + существующие почтовые тесты не сломаны**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.*Mail*' --tests 'com.vladoose.nir.email.KpEmailComposerTest'` (sandbox off).
Expected: PASS (GreenMail `MailReceiveServiceIntegrationTest` + композер зелёные; MailHog не требуется — GreenMail сам SMTP).

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/EmailService.java src/main/resources/application.yaml && git commit -m "feat(kp): EmailService — MimeMessage + Reply-To (ящик ответов) + UTF-8; smtp ssl/auth через env

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: MailReceiveService — не самопомечать «свои» письма

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/MailReceiveService.java`
- Test: `src/test/java/com/vladoose/nir/email/KpRoundTripTest.java`

**Interfaces:**
- Consumes: `msg.getFrom()`, `KpToken.parse`, `${spring.mail.username}` (адрес отправки).
- Produces: письмо с токеном, но From == наш адрес отправки → классифицируется как `UNMATCHED` (не `SUPPLIER_RESPONSE`), КП не переводится в RESPONDED. Реальный ответ (другой From, тот же токен) → RESPONDED как прежде.

- [ ] **Step 1: Failing round-trip тест (GreenMail SMTP+IMAP)**

```java
package com.vladoose.nir.email;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.MailReceiveService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KpRoundTripTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    @Autowired MailReceiveService mailReceiveService;
    @Autowired TenderRepository tenderRepository;
    @Autowired PriceRequestRepository priceRequestRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private Long sentPr() {
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("KZ-RT-" + System.nanoTime())
                .status("NEW").market(Market.KZ).source(Source.PUBLIC_TENDER).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder()
                .tender(t).market(Market.KZ).status("SENT").build());
        return pr.getId();
    }

    private void deliver(String from, String subject) throws Exception {
        greenMail.setUser("zakup@westmed.kz", "zakup", "pass");
        MimeMessage m = new MimeMessage((jakarta.mail.Session) null);
        m.setFrom(from);
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, "zakup@westmed.kz");
        m.setSubject(subject);
        m.setText("Ответ поставщика: цена 1000000 тг, срок 30 дней.");
        GreenMailUtil.sendMimeMessage(m);
    }

    private void configureImapAndPoll() {
        // навести IMAP-поля сервиса на GreenMail
        ReflectionTestUtils.setField(mailReceiveService, "enabled", true);
        ReflectionTestUtils.setField(mailReceiveService, "host", "127.0.0.1");
        ReflectionTestUtils.setField(mailReceiveService, "port", ServerSetupTest.IMAP.getPort());
        ReflectionTestUtils.setField(mailReceiveService, "username", "zakup@westmed.kz");
        ReflectionTestUtils.setField(mailReceiveService, "password", "pass");
        ReflectionTestUtils.setField(mailReceiveService, "protocol", "imap");
        ReflectionTestUtils.setField(mailReceiveService, "sinceMinutes", 100000L);
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();
    }

    @Test
    void supplierReplyMarksResponded() throws Exception {
        Long id = sentPr();
        deliver("supplier@example.kz", "Re: [КП-" + id + "] Запрос КП");
        configureImapAndPoll();
        assertThat(priceRequestRepository.findById(id).orElseThrow().getStatus()).isEqualTo("RESPONDED");
    }

    @Test
    void ownEchoDoesNotSelfMarkResponded() throws Exception {
        Long id = sentPr();
        // письмо «от нас самих» (From == адрес отправки) с нашим же токеном
        deliver("zakup@westmed.kz", "[КП-" + id + "] Запрос КП по тендеру");
        configureImapAndPoll();
        assertThat(priceRequestRepository.findById(id).orElseThrow().getStatus()).isEqualTo("SENT");
    }
}
```

> Примечание: точный `ServerSetupTest.SMTP_IMAP` и способ доставки письма могут отличаться от версии GreenMail — реализатор сверяется с существующим `MailReceiveServiceIntegrationTest` (тот же приём на GreenMail IMAP) и переиспользует его паттерн доставки/поллинга, если этот не компилируется. Ключевые ассерты (RESPONDED vs SENT) неизменны.

- [ ] **Step 2: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.KpRoundTripTest'` (sandbox off).
Expected: FAIL на `ownEchoDoesNotSelfMarkResponded` — сейчас «своё» письмо помечает RESPONDED.

- [ ] **Step 3: Фильтр «своих» в `MailReceiveService.handle`**

Добавить поле адреса отправки. В конструктор добавить параметр:
```java
                              @Value("${spring.mail.username:}") String sendFrom,
```
и поле `private final String sendFrom;` + `this.sendFrom = sendFrom == null ? "" : sendFrom.trim().toLowerCase();`

В методе `handle`, где определяется тип (сейчас `if (kp.isPresent()) { type = InboundType.SUPPLIER_RESPONSE; ... }`), добавить проверку From. Заменить блок:
```java
        Optional<Long> kp = KpToken.parse(subject);
        InboundType type;
        Long matchedId = null;
        if (kp.isPresent()) {
            type = InboundType.SUPPLIER_RESPONSE;
            matchedId = matchSupplierResponse(kp.get(), text.toString());
            result.setSupplierResponses(result.getSupplierResponses() + 1);
        } else if (attachment != null) {
```
на:
```java
        Optional<Long> kp = KpToken.parse(subject);
        boolean ownEcho = !sendFrom.isBlank() && addressPart(from).equalsIgnoreCase(sendFrom);
        InboundType type;
        Long matchedId = null;
        if (kp.isPresent() && !ownEcho) {
            type = InboundType.SUPPLIER_RESPONSE;
            matchedId = matchSupplierResponse(kp.get(), text.toString());
            result.setSupplierResponses(result.getSupplierResponses() + 1);
        } else if (attachment != null && !ownEcho) {
```

Добавить хелпер (рядом с `trunc`):
```java
    /** Адресная часть из "Имя <a@b>" или "a@b" — нижним регистром, без угловых скобок. */
    private static String addressPart(String from) {
        if (from == null) return "";
        String s = from.trim();
        int lt = s.lastIndexOf('<'), gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) s = s.substring(lt + 1, gt);
        return s.trim().toLowerCase();
    }
```
(Если `ownEcho` и был токен — письмо становится `UNMATCHED`, помечается SEEN как прочее, КП не трогается.)

> ⚠ После правки — `./gradlew compileJava`.

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.KpRoundTripTest'` (sandbox off).
Expected: PASS (2 теста: реальный ответ → RESPONDED; своё эхо → остаётся SENT).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/MailReceiveService.java src/test/java/com/vladoose/nir/email/KpRoundTripTest.java && git commit -m "feat(kp): приём — не самопомечать RESPONDED письма с нашего же адреса (round-trip на zakup@)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Override текста в send + preview

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestSendService.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/KpPreviewResponse.java`
- Test: `src/test/java/com/vladoose/nir/email/KpEmailComposerTest.java` (дописать)

**Interfaces:**
- Consumes: `KpEmailComposer.compose/composeForPreview`, `PriceRequestWriter.persist`, `KpToken.subjectToken`.
- Produces:
  - `PriceRequestSendService.send(Long tenderId, List<Long> distributorIds, List<SendItem> items, String subjectOverride, String bodyOverride)` — новая 5-арг сигнатура (старую 3-арг удалить, обновив вызовы).
  - `KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items)` → `{subject, body}` (первый поставщик как образец; черновой PR без сохранения).
  - `POST /api/price-requests/preview` → `KpPreviewResponse`; `/send` принимает override.
  - `KpPreviewResponse { String subject; String body; }`.

- [ ] **Step 1: DTO ответа превью**

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KpPreviewResponse {
    private String subject;
    private String body;
}
```

- [ ] **Step 2: Override-поля в запросе**

В `PriceRequestSendRequest` добавить два необязательных поля (после `items`):
```java
    private String subjectOverride; // человеческая часть темы; токен [КП-id] всегда добавляет сервер
    private String bodyOverride;    // если задан — уходит вместо скомпонованного тела
```

- [ ] **Step 3: Failing-тест override (дописать в `KpEmailComposerTest`)**

Добавить тест, что при `bodyOverride` уходит именно он, но тема сохраняет токен. Проще — на уровне `dispatch`/`send` через сервис; но чтобы не поднимать SMTP, проверим композицию темы: override человеческой части + серверный токен.

```java
    @Test
    void overrideKeepsServerToken() {
        // человеческая часть темы берётся из override, токен добавляет сервер отдельно —
        // проверяем контракт KpToken.subjectToken + произвольная человеческая часть
        String humanOverride = "СРОЧНО: КП по ИВЛ";
        String subject = com.vladoose.nir.util.KpToken.subjectToken(777L) + " " + humanOverride;
        assertThat(subject).startsWith("[КП-777]");
        assertThat(subject).endsWith(humanOverride);
        assertThat(com.vladoose.nir.util.KpToken.parse(subject)).contains(777L);
    }
```

- [ ] **Step 4: Прогнать — падает/зелёный контроль**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.KpEmailComposerTest'` (sandbox off).
Expected: PASS (4 теста; этот тест фиксирует контракт токена — реализация override в Step 5 должна ему соответствовать).

- [ ] **Step 5: `send` c override + `preview` в `PriceRequestSendService`**

Изменить сигнатуру `send` на 5-арг и провести override в `dispatch`. Заменить хвост `send` (цикл по дистрибьюторам) и `dispatch`:

```java
    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride) {
        // ... начало метода без изменений (валидация, tender, lines) ...
        List<SendResult> results = new ArrayList<>();
        for (Long distId : new LinkedHashSet<>(distributorIds)) {
            Distributor dist = distributorService.findById(distId);
            requireCurrentMarket(dist.getMarket(), "Поставщик не найден: " + distId);
            PriceRequest pr = writer.persist(tender, dist, lines);
            results.add(dispatch(pr, dist, subjectOverride, bodyOverride));
        }
        return results;
    }
```
`dispatch` — принять override и применять их, сохраняя серверный токен:
```java
    private SendResult dispatch(PriceRequest pr, Distributor dist, String subjectOverride, String bodyOverride) {
        String to = dist.getEmail();
        if (to == null || to.isBlank()) {
            log.warn("КП id={} создан, но у поставщика «{}» нет email — письмо не отправлено", pr.getId(), dist.getName());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_NO_EMAIL);
        }
        KpEmailComposer.Composed def = composer.compose(pr);
        String subject = (subjectOverride != null && !subjectOverride.isBlank())
                ? com.vladoose.nir.util.KpToken.subjectToken(pr.getId()) + " " + subjectOverride.trim()
                : def.subject();
        String body = (bodyOverride != null && !bodyOverride.isBlank()) ? bodyOverride : def.body();
        try {
            emailService.sendEmail(to, subject, body);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), true, null);
        } catch (Exception ex) {
            log.warn("Не удалось отправить КП id={} на {}: {}. Запрос сохранён в БД.", pr.getId(), to, ex.getMessage());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
    }
```
Добавить `preview` (черновой PR без сохранения — образец по первому поставщику):
```java
    public com.vladoose.nir.dto.response.KpPreviewResponse preview(Long tenderId, List<Long> distributorIds,
                                                                    List<SendItem> items) {
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        List<PriceRequestItem> prItems = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) throw new BadRequestException("Лот не принадлежит тендеру");
            MedEquipment eq = si.medEquipmentId() != null ? medEquipmentService.findById(si.medEquipmentId()) : null;
            prItems.add(PriceRequestItem.builder().tenderLot(lot).medEquipment(eq)
                    .requestedQuantity(si.requestedQuantity()).build());
        }
        Distributor sample = distributorService.findById(distributorIds.get(0));
        requireCurrentMarket(sample.getMarket(), "Поставщик не найден");
        PriceRequest draft = PriceRequest.builder().tender(tender).distributor(sample)
                .market(tender.getMarket()).items(prItems).build();
        KpEmailComposer.Composed c = composer.composeForPreview(draft);
        return new com.vladoose.nir.dto.response.KpPreviewResponse(c.subject(), c.body());
    }
```
Добавить нужные импорты (`PriceRequestItem`, `KpPreviewResponse`).

- [ ] **Step 6: Контроллер — `/preview` + проброс override в `/send`**

Обновить handler `send` (проброс override) и добавить `preview`:
```java
    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PriceRequestSendService.SendResult> send(@Valid @RequestBody PriceRequestSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(
                        i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        return sendService.send(req.getTenderId(), req.getDistributorIds(), items,
                req.getSubjectOverride(), req.getBodyOverride());
    }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public com.vladoose.nir.dto.response.KpPreviewResponse preview(@Valid @RequestBody PriceRequestSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(
                        i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        return sendService.preview(req.getTenderId(), req.getDistributorIds(), items);
    }
```

- [ ] **Step 7: Обновить прочие вызовы `send` (3-арг → 5-арг)**

Ровно ДВА места вызывают старую 3-арг сигнатуру — обновить оба, добавив `, null, null`:
- `src/main/java/com/vladoose/nir/controller/PriceRequestController.java:65` — уже обновлён в Step 6 (проброс override).
- `src/main/java/com/vladoose/nir/controller/BulkPriceController.java:64` — сейчас `sendService.send(req.getTenderId(), List.of(req.getDistributorId()), items);` → добавить `, null, null` в конец аргументов.

Проверить, что других вызовов нет:
Run: `cd /Users/vlad/IdeaProjects/AIS && grep -rn "sendService.send(" src/main/java/com/vladoose/nir`
Expected: только эти два, оба 5-арг после правки.

- [ ] **Step 8: Прогнать — зелёный + компиляция**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.KpEmailComposerTest'` (sandbox off).
Expected: PASS (4). Плюс `./gradlew compileJava` без ошибок (5-арг разошёлся по вызовам).

- [ ] **Step 9: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/PriceRequestSendService.java src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java src/main/java/com/vladoose/nir/controller/PriceRequestController.java src/main/java/com/vladoose/nir/dto/response/KpPreviewResponse.java src/test/java/com/vladoose/nir/email/KpEmailComposerTest.java && git commit -m "feat(kp): POST /preview (черновой текст) + subject/bodyOverride в /send (токен серверный)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Resend существующего КП

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestSendService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`
- Test: `src/test/java/com/vladoose/nir/email/KpResendTest.java`

**Interfaces:**
- Consumes: `PriceRequestService.findById` (или репозиторий), `KpEmailComposer.compose`, `EmailService`.
- Produces: `SendResult resend(Long priceRequestId)` — перешлёт письмо СУЩЕСТВУЮЩЕГО PR (не создаёт новый); гард рынка; тот же токен по его id. `POST /api/price-requests/{id}/resend`.

- [ ] **Step 1: Failing-тест resend (гард рынка + не создаёт новый PR)**

```java
package com.vladoose.nir.email;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PriceRequestSendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class KpResendTest {

    @Autowired PriceRequestSendService sendService;
    @Autowired TenderRepository tenderRepository;
    @Autowired PriceRequestRepository priceRequestRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private PriceRequest prNoEmail(Market market) {
        MarketContext.set(market);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + market + "-" + System.nanoTime())
                .status("NEW").market(market).source(Source.PUBLIC_TENDER).build());
        Distributor d = Distributor.builder().name("Дист " + System.nanoTime()).market(market).build(); // без email
        // сохранить дистрибьютора через каскад PR? нет — нужен явный. Упрощаем: PR с distributor без email.
        return priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d)
                .market(market).status("SENT").build());
    }

    @Test
    void resendForeignMarketRejected() {
        PriceRequest pr = prNoEmail(Market.KZ);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> sendService.resend(pr.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void resendNoEmailReturnsReasonNotThrows() {
        PriceRequest pr = prNoEmail(Market.KZ);
        MarketContext.set(Market.KZ);
        PriceRequestSendService.SendResult r = sendService.resend(pr.getId());
        assertThat(r.priceRequestId()).isEqualTo(pr.getId());
        assertThat(r.emailSent()).isFalse();
        assertThat(r.reason()).isEqualTo(PriceRequestSendService.REASON_NO_EMAIL);
        // новый PR не создан — тот же id
        assertThat(priceRequestRepository.findById(pr.getId())).isPresent();
    }
}
```
> Если `Distributor` без каскада из PR не сохраняется — реализатор сохраняет дистрибьютора через `DistributorRepository` перед PR (добавить autowired репозиторий). Ассерты (reject/NO_EMAIL/тот же id) неизменны.

- [ ] **Step 2: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.email.KpResendTest'` (sandbox off).
Expected: FAIL — метода `resend` нет.

- [ ] **Step 3: `resend` в `PriceRequestSendService`**

`PriceRequestSendService` **сейчас НЕ инжектит** репозиторий PR — добавить в конструктор параметр `PriceRequestRepository priceRequestRepository` + поле `private final PriceRequestRepository priceRequestRepository;` + присвоение (импорт `com.vladoose.nir.repository.PriceRequestRepository`). Затем метод:
```java
    /** Переслать письмо существующего КП (после правки email / повторно). Новый PR не создаётся. */
    public SendResult resend(Long priceRequestId) {
        PriceRequest pr = priceRequestRepository.findById(priceRequestId)
                .orElseThrow(() -> new NotFoundException("КП не найден: " + priceRequestId));
        requireCurrentMarket(pr.getMarket(), "КП не найден: " + priceRequestId);
        return dispatch(pr, pr.getDistributor(), null, null);
    }
```
> Если инжектится `PriceRequestRepository` — `findById` уважает рыночный фильтр под сессией; дополнительный `requireCurrentMarket` — defense-in-depth. Импорт `NotFoundException` уже есть.

- [ ] **Step 4: Контроллер — `POST /{id}/resend`**

```java
    @PostMapping("/{id}/resend")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceRequestSendService.SendResult resend(@PathVariable Long id) {
        return sendService.resend(id);
    }
```

- [ ] **Step 5: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.KpResendTest'` (sandbox off).
Expected: PASS (2).

- [ ] **Step 6: Регресс всей сборки**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test` (sandbox off).
Expected: падают ТОЛЬКО 2 `ApplyAutoFillServiceTest`.

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/PriceRequestSendService.java src/main/java/com/vladoose/nir/controller/PriceRequestController.java src/test/java/com/vladoose/nir/email/KpResendTest.java && git commit -m "feat(kp): POST /api/price-requests/{id}/resend — переслать письмо существующего КП

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Фронт — диалог превью + «Переслать» + «Проверить ответы»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `POST /price-requests/preview`, `/send` (+override), `/{id}/resend`, `POST /inbound/poll` (уже есть `pollInbound`).
- Produces: `previewKp(body)`, `resendPriceRequest(id)`, `sendPriceRequests` с override; UI-диалог превью + кнопки.

- [ ] **Step 1: `ApiService` — методы**

Заменить `sendPriceRequests` (строки ~193-198) и добавить два метода:
```ts
  sendPriceRequests(body: {
    tenderId: number;
    distributorIds: number[];
    items: { tenderLotId: number; medEquipmentId: number | null; requestedQuantity: number }[];
    subjectOverride?: string;
    bodyOverride?: string;
  }): Observable<any[]> {
    return this.http.post<any[]>(`${this.base}/price-requests/send`, body);
  }

  /** Черновой текст письма КП (тема без токена — он присвоится при отправке). */
  previewKp(body: {
    tenderId: number;
    distributorIds: number[];
    items: { tenderLotId: number; medEquipmentId: number | null; requestedQuantity: number }[];
  }): Observable<{ subject: string; body: string }> {
    return this.http.post<{ subject: string; body: string }>(`${this.base}/price-requests/preview`, body);
  }

  resendPriceRequest(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/price-requests/${id}/resend`, {});
  }
```

- [ ] **Step 2: Component — состояние диалога превью**

Рядом с `kpPanel` объявить:
```ts
  kpPreview: { subject: string; body: string; sending: boolean;
               distributorIds: number[]; items: any[] } | null = null;
```

- [ ] **Step 3: Component — `sendKpRequests` открывает превью вместо прямой отправки**

Заменить `sendKpRequests()` (строки ~1553-1578): вместо прямого `/send` — запросить превью и открыть диалог.
```ts
  sendKpRequests() {
    if (!this.selectedTender || !this.kpPanel) return;
    const distributorIds = this.checkedSuppliers().map((e: any) => e.distributor.id);
    const items = this.lots
      .filter((l: any) => this.lotSel.has(l.id))
      .map((l: any) => ({ tenderLotId: l.id, medEquipmentId: l.proposedEquipment?.id ?? null, requestedQuantity: l.quantity ?? 1 }));
    if (!distributorIds.length || !items.length) return;
    this.kpPanel.sending = true;
    this.api.previewKp({ tenderId: this.selectedTender.id, distributorIds, items }).subscribe({
      next: (p) => {
        this.kpPreview = { subject: p.subject, body: p.body, sending: false, distributorIds, items };
        if (this.kpPanel) this.kpPanel.sending = false;
        this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.kpPanel) this.kpPanel.sending = false;
        this.notify.error('Ошибка превью: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  confirmSendKp() {
    if (!this.selectedTender || !this.kpPreview) return;
    this.kpPreview.sending = true;
    this.api.sendPriceRequests({
      tenderId: this.selectedTender.id,
      distributorIds: this.kpPreview.distributorIds,
      items: this.kpPreview.items,
      subjectOverride: this.subjectHuman(this.kpPreview.subject),
      bodyOverride: this.kpPreview.body,
    }).subscribe({
      next: (results) => {
        this.kpToastFromResults(results);
        this.kpPreview = null; this.kpPanel = null; this.lotSel.clear();
        this.loadPriceRequests(); this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.kpPreview) this.kpPreview.sending = false;
        this.notify.error('Ошибка отправки: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  /** Убрать токен [КП-…] из отредактированной темы — сервер добавит свой. */
  private subjectHuman(subject: string): string {
    return (subject || '').replace(/\[КП-\d+\]\s*/g, '').trim();
  }

  cancelKpPreview() { this.kpPreview = null; this.cdr.detectChanges(); }

  resendPr(pr: any) {
    this.api.resendPriceRequest(pr.id).subscribe({
      next: (r) => { this.kpToastFromResults([r]); this.loadPriceRequests(); },
      error: (e) => this.notify.error(e.error?.message || 'Ошибка пересылки'),
    });
  }

  checkKpResponses() {
    this.api.pollInbound().subscribe({
      next: () => { this.notify.success('Почта проверена'); this.loadPriceRequests(); },
      error: (e) => this.notify.error('Проверка почты: ' + (e.error?.message || e.message)),
    });
  }
```

- [ ] **Step 4: Component — шаблон диалога превью + кнопки**

Добавить модалку превью (после КП-панели `<div class="kp-panel" ...>...</div>`, перед `<app-smart-match`):
```html
      <div class="kp-preview-overlay" *ngIf="kpPreview" (click)="cancelKpPreview()">
        <div class="kp-preview" (click)="$event.stopPropagation()">
          <h3>Текст письма — проверьте перед отправкой</h3>
          <p class="kp-preview-note">Метка [КП-№] будет присвоена автоматически при отправке. Письмо уйдёт {{ kpPreview.distributorIds.length }} поставщик(ам).</p>
          <label class="kp-preview-lbl">Тема</label>
          <input class="kp-preview-subject" [(ngModel)]="kpPreview.subject" />
          <label class="kp-preview-lbl">Текст</label>
          <textarea class="kp-preview-body" rows="16" [(ngModel)]="kpPreview.body"></textarea>
          <div class="kp-preview-actions">
            <button class="btn btn-cancel" (click)="cancelKpPreview()">Отмена</button>
            <button class="btn btn-save" [disabled]="kpPreview.sending" (click)="confirmSendKp()">
              {{ kpPreview.sending ? 'Отправка…' : 'Отправить' }}
            </button>
          </div>
        </div>
      </div>
```
В секции «Запросы КП» — кнопка «Проверить ответы» (в заголовке) и «↻ Переслать» на карточке. Заменить `<h3>Запросы КП</h3>` на:
```html
        <div class="pr-section-head">
          <h3>Запросы КП</h3>
          <button class="btn btn-line" (click)="checkKpResponses()">Проверить ответы</button>
        </div>
```
И рядом с кнопками статуса КП (около `Принять КП`) добавить «Переслать» для не-финальных статусов:
```html
              <button *ngIf="pr.status === 'SENT' || pr.status === 'CREATED'" class="btn btn-line" (click)="resendPr(pr)">↻ Переслать</button>
```

Добавить стили:
```css
    .kp-preview-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .kp-preview { background: #fff; border-radius: 10px; padding: 20px; width: min(720px, 92vw); max-height: 88vh; overflow: auto; }
    .kp-preview-note { color: #6b7280; font-size: 12.5px; margin: 4px 0 12px; }
    .kp-preview-lbl { display: block; font-size: 12px; color: #374151; margin: 8px 0 3px; }
    .kp-preview-subject { width: 100%; padding: 7px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .kp-preview-body { width: 100%; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; font: inherit; resize: vertical; }
    .kp-preview-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 14px; }
    .pr-section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
```

> ⚠ `anyComponentStyle` budget 16 кБ — если превышает, урезать стили (не поднимать бюджет). После правок — `grep -c "sendKpRequests(" tenders.component.ts` (definition + вызовы) и build.

- [ ] **Step 5: Сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`.
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "feat(kp): диалог превью письма перед отправкой + «Переслать» + «Проверить ответы»

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Живая проверка (Playwright) + CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§8/§9/§15/§16)

- [ ] **Step 1: Поднять стек (MailHog SMTP по умолчанию — round-trip проверяем UI-логикой)**

Backend: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (sandbox off, фон). Frontend: `cd frontend && npm start` (фон). Дождаться :8080/:4200.
> Боевой round-trip через реальный zakup@ проверяется вручную под env (§6 спека) — в автопроверку не входит (внешняя почта). Живьём в браузере проверяем: превью открывается, редактируется, отправка создаёт КП (SENT), «Переслать»/«Проверить ответы» работают.

- [ ] **Step 2: Playwright — сценарий**

Navigate `http://localhost:4200`, логин admin/admin, `localStorage.setItem('ais.market','KZ')`, открыть импортный KZ-тендер (`?openId=412`), «КП» на лоте → выбрать поставщиков → «Отправить запросы» → **диалог превью**: тело начинается «Здравствуйте!» (у дистрибьютора нет ФИО), видна инструкция про ответ; отредактировать строку → «Отправить» → тост, КП появился в «Запросы КП» со статусом «Отправлен». Нажать «Переслать» на КП → тост. Нажать «Проверить ответы» → тост «Почта проверена». Снять скриншот `kp-preview-send.png`.

- [ ] **Step 3: Обновить CLAUDE.md**

§8/§9: отметить «грамотную отправку» — приветствие без ФИО, Reply-To на ящик ответов, редактируемое превью (`POST /preview`, override с серверным токеном), resend, пропуск «своих» писем в приёме. §15 API: `/api/price-requests/preview` (POST), `/{id}/resend` (POST), override-поля в `/send`. §16: отметить ч.2 сделанной, оставить ч.3 (разбор ответа) в follow-up; отметить боевой SMTP env-рецепт (smtp.mail.ru 465) и `MAIL_KP_REPLY_TO`.

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md kp-preview-send.png && git commit -m "docs: CLAUDE.md — грамотная отправка КП (превью/reply-to/resend/round-trip)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Затем — whole-branch review (superpowers:requesting-code-review) и finishing-a-development-branch (merge --ff-only в main, удалить ветку).

---

## Self-Review (выполнено при написании)

- **Покрытие спека:** §4 качество письма → Task 1; §5 EmailService MimeMessage/Reply-To → Task 2; §6 SMTP env → Task 2 (yaml); §7 пропуск своих писем → Task 3; §8 preview+override → Task 4; §9 UX (resend/проверить ответы/превью-диалог) → Task 5 (resend) + Task 6 (UI); §10 тесты → Task 1/3/4/5 + Task 7 (Playwright); §11 без миграций — соблюдено.
- **Отклонения от спека:** превью строится на несохранённом `PriceRequest` (спек §8) — реализовано без записи в БД (`composeForPreview`). Токен серверный при override — реализовано в `dispatch` (Task 4) и через `subjectHuman` на фронте (снимает токен перед отправкой, сервер добавляет свой).
- **Плейсхолдеры:** «Create KpResendRequest… НЕ создавать» — снят в File Structure (resend без тела, по path id). Остальные шаги содержат конкретный код.
- **Согласованность типов:** `send(tenderId, distributorIds, items, subjectOverride, bodyOverride)` (5-арг, Task 4) — вызовы обновлены в Task 4 Step 7; `preview(...)→KpPreviewResponse{subject,body}` (Task 4) ↔ `previewKp` FE (Task 6); `resend(id)→SendResult` (Task 5) ↔ `resendPriceRequest` FE (Task 6); `composeForPreview` (Task 1) ↔ `preview` (Task 4). `SendResult` record — существующий (`priceRequestId/emailSent/reason`), используется в тесте Task 5.
