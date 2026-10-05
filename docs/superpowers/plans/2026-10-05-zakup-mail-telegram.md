# Почта zakup@ → Telegram «Заявки» — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** каждое письмо во «Входящих» zakup@westmed.kz — уведомлением в тему «Почта zakup@» группы Telegram «Заявки», а приём почты АИС доведён до состояния «можно включать на проде».

**Architecture:**
- **Приём:** ящик открывается только на чтение, обработанное помнит курсор по UID (`mail_cursor`). Письмо разбирается вне транзакции в неизменяемый `ParsedMail` и пишется отдельным `@Transactional`-бином, одна транзакция на письмо: классификация, правки запроса КП, строка «Входящих» с готовым текстом уведомления, сдвиг курсора.
- **Отправка:** очередь — колонки `notify_*` в `inbound_email`; после каждого прохода её отправляет `MailTelegramNotifier` через Bot API.
- **Расписание:** проход идёт на своём потоке `mail-imap`.
- **Фронт:** ссылка «Открыть в АИС» несёт рынок (`?market=`), после входа возвращает на ту же страницу.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Hibernate 6, Jakarta Mail (spring-boot-starter-mail), Flyway, PostgreSQL 17, Jsoup 1.18.1, GreenMail 2.1.2 (тесты), JDK HttpClient / HttpServer, Angular 21.

**Spec:** `docs/superpowers/specs/2026-10-05-zakup-mail-telegram-design.md` — читать вместе с планом.

## Global Constraints

- **Зависимости:** новых нет (Jsoup, GreenMail, Jakarta Mail уже в `build.gradle`).
- **Песочница:** любые `./gradlew` и команды к БД — через Bash с `dangerouslyDisableSandbox: true`: песочница блокирует localhost:5432. Если поднят `bootRun`, перед тестами: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`.
- **Запуск тестов:** `./gradlew cleanTest test --tests '<полное имя класса>'`. Голый `test` может отдать `UP-TO-DATE` из кеша.
- **Гейт задачи:** её тесты зелёные. **Гейт ветки:** `./gradlew cleanTest test` — 0 падений.
- **Схема БД** — только новой миграцией `V24__mail_cursor_and_telegram_queue.sql`; V1–V23 не трогать.
- **Рынок (§6 CLAUDE.md):**
  - фоновый поток ставит `MarketContext.set(...)` явно и чистит в `finally`;
  - работа с БД — в `@Transactional`-методе отдельного бина;
  - `@FilterDef` не объявлять.
- **Тестовые контексты:** новых `@TestPropertySource` не заводить, кроме мета-аннотации `@MailIntegrationTest` (задача 9). Каждый особый набор свойств — +10 соединений к nirdb при `max_connections=100`.
- **Токен Telegram** стоит в ПУТИ адреса Bot API:
  - адрес запроса нигде не печатается: ни в лог, ни в текст исключения, ни в `notify_error`;
  - тексты ошибок — свои.
- **Стиль:** комментарии и javadoc — по-русски, в стиле окружающего кода; внедрение через конструктор; `@Autowired` — только чтобы пометить основной конструктор, когда рядом есть второй, пакетный для тестов.
- **Коммиты:** по-русски, префикс `feat(mail)` / `test(mail)` / `docs(mail)` / `fix(mail)`, последняя строка — `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. В `origin` не пушить — пушит оператор.
- **Фронт:** юнит-тестов нет, гейт — `cd frontend && npm run build`. Новых тяжёлых импортов не добавлять: начальный бандл у предела 1,5 МБ. После `cd frontend` команды `git` и `./gradlew` — из корня (`cd /Users/vlad/IdeaProjects/AIS && …`).

## Карта файлов

**Бэкенд — создать:**
- `src/main/resources/db/migration/V24__mail_cursor_and_telegram_queue.sql` — курсор и очередь уведомлений.
- `entity/NotifyStatus.java`, `entity/MailCursor.java`, `repository/MailCursorRepository.java`.
- `service/mail/` (пакет `com.vladoose.nir.service.mail`):
  - `ParsedMail` — разобранное письмо;
  - `MailText` — HTML → текст, отрывок ответа;
  - `MailParser` — MIME → `ParsedMail`;
  - `MailClass`, `Classification`, `ClassifierRules`, `MailClassifier` — вид письма;
  - `KpOutcome`, `KpSnapshot`, `ComposeContext`, `BrokenMail`, `MailNotification`, `MailNotificationComposer` — текст уведомления;
  - `MailIngestWriter` — запись письма и курсора;
  - `PendingNotification`, `MailNotifyStore`, `MailTelegramNotifier` — очередь Telegram;
  - `MailboxConnector`, `MailboxSession`, `ImapMailboxConnector` — IMAP только на чтение.
- `integration/telegram/TelegramSettings.java`, `TelegramClient.java`, `TelegramException.java`.
- `util/InfrastructureFailure.java` — вынесено из `WhatsappChatSync`.

**Бэкенд — изменить:**
- `entity/InboundEmail.java`, `entity/InboundType.java`, `repository/InboundEmailRepository.java`.
- `service/MailReceiveService.java` — переписать; `service/MailPollScheduler.java` — свой поток.
- `dto/response/PollResultResponse.java`.
- `integration/whatsapp/WhatsappChatSync.java` — делегат к `InfrastructureFailure`.
- `application.yaml`, `application-prod.yaml`, `build.gradle` (задача `devMail`), `.env.example`.

**Тесты — создать:**
- `service/mail/TestMails.java`, `TestMimes.java`, `ImapTestSupport.java` — помощники;
- `integration/telegram/TelegramStubServer.java` — заглушка Bot API;
- `mail/MailIntegrationTest.java` — мета-аннотация общего контекста;
- `mail/DevMailServer.java` — не тест, почтовый сервер для живой проверки;
- тестовые классы по задачам.

**Фронт:**
- `src/index.html`;
- `app/shared/return-url.ts` (новый);
- `app/guards/auth.guard.ts`, `app/interceptors/auth.interceptor.ts`, `app/pages/login/login.component.ts`, `app/pages/inbound/inbound.component.ts`.

**Скрипты:** `scripts/telegram-stub.mjs`, `scripts/dev-mail.py`.
**Доки:** `DEPLOY.md`, `CLAUDE.md`, `docs/PROGRESS.md`.

Пути Java ниже — от `src/main/java/com/vladoose/nir/` и `src/test/java/com/vladoose/nir/`.

---

### Task 1: Схема — курсор ящика, очередь уведомлений, новые виды писем

**Files:**
- Create: `src/main/resources/db/migration/V24__mail_cursor_and_telegram_queue.sql`
- Create: `entity/NotifyStatus.java`, `entity/MailCursor.java`, `repository/MailCursorRepository.java`
- Modify: `entity/InboundEmail.java` (новые поля), `entity/InboundType.java` (+`BOUNCE`, `AUTO_REPLY`), `repository/InboundEmailRepository.java` (+`existsByMailboxAndMessageId`)
- Test: `src/test/java/com/vladoose/nir/service/mail/MailSchemaTest.java`

**Interfaces:**
- Produces:
  - `NotifyStatus { PENDING, SENT, FAILED }`;
  - `InboundType.BOUNCE`, `InboundType.AUTO_REPLY`;
  - поля `InboundEmail`: `mailbox: String`, `imapUid: Long`, `messageId: String`, `notifyStatus: NotifyStatus`, `notifyText: String`, `notifySilent: boolean`, `notifyQueuedAt: OffsetDateTime`, `notifyAttempts: int`, `notifyError: String`, `notifiedAt: OffsetDateTime` (Lombok-геттеры/сеттеры/билдер, у boolean — `isNotifySilent()`);
  - `MailCursor { String mailbox (@Id); long uidValidity; long lastUid; OffsetDateTime updatedAt }` + `MailCursorRepository extends JpaRepository<MailCursor, String>`;
  - `boolean InboundEmailRepository.existsByMailboxAndMessageId(String mailbox, String messageId)`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/service/mail/MailSchemaTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MailSchemaTest {

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired MailCursorRepository cursorRepo;
    @Autowired EntityManager em;

    @AfterEach
    void clear() { MarketContext.clear(); }

    @Test
    void inboundEmail_keepsMailboxAndTelegramQueue() {
        MarketContext.set(Market.KZ);
        OffsetDateTime queued = OffsetDateTime.parse("2026-10-05T10:15:30Z");
        InboundEmail saved = inboundRepo.save(InboundEmail.builder()
                .fromAddress("a@x.kz").subject("S").type(InboundType.BOUNCE).status(InboundStatus.NEW)
                .mailbox("zz-schema@test.kz").imapUid(42L).messageId("<m1@x.kz>")
                .notifyStatus(NotifyStatus.PENDING).notifyText("⚠️ текст").notifySilent(true)
                .notifyQueuedAt(queued).build());
        em.flush();
        em.clear();

        InboundEmail back = inboundRepo.findById(saved.getId()).orElseThrow();
        assertThat(back.getType()).isEqualTo(InboundType.BOUNCE);
        assertThat(back.getMailbox()).isEqualTo("zz-schema@test.kz");
        assertThat(back.getImapUid()).isEqualTo(42L);
        assertThat(back.getMessageId()).isEqualTo("<m1@x.kz>");
        assertThat(back.getNotifyStatus()).isEqualTo(NotifyStatus.PENDING);
        assertThat(back.getNotifyText()).isEqualTo("⚠️ текст");
        assertThat(back.isNotifySilent()).isTrue();
        assertThat(back.getNotifyQueuedAt().toInstant()).isEqualTo(queued.toInstant());
        assertThat(back.getNotifyAttempts()).isZero();
        assertThat(back.getNotifiedAt()).isNull();
        assertThat(inboundRepo.existsByMailboxAndMessageId("zz-schema@test.kz", "<m1@x.kz>")).isTrue();
        assertThat(inboundRepo.existsByMailboxAndMessageId("other@test.kz", "<m1@x.kz>")).isFalse();
    }

    @Test
    void autoReplyType_fitsColumn() {
        MarketContext.set(Market.KZ);
        InboundEmail saved = inboundRepo.save(InboundEmail.builder()
                .fromAddress("a@x.kz").type(InboundType.AUTO_REPLY).status(InboundStatus.NEW).build());
        em.flush();
        em.clear();
        assertThat(inboundRepo.findById(saved.getId()).orElseThrow().getType()).isEqualTo(InboundType.AUTO_REPLY);
    }

    @Test
    void mailCursor_roundTrip() {
        cursorRepo.save(MailCursor.builder().mailbox("zz-cursor@test.kz").uidValidity(7L).lastUid(100L)
                .updatedAt(OffsetDateTime.now()).build());
        em.flush();
        em.clear();
        MailCursor c = cursorRepo.findById("zz-cursor@test.kz").orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(7L);
        assertThat(c.getLastUid()).isEqualTo(100L);
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailSchemaTest'`
Expected: FAIL компиляции — нет `NotifyStatus`, `MailCursor`, `mailbox(...)` в билдере.

- [ ] **Step 3: Миграция V24**

`src/main/resources/db/migration/V24__mail_cursor_and_telegram_queue.sql`:

```sql
-- Почта zakup@ → Telegram «Заявки» (спека docs/superpowers/specs/2026-10-05-zakup-mail-telegram-design.md §7).

-- Курсор приёма: последнее обработанное письмо ящика (UID IMAP). Не рыночная таблица — ключ ящик.
CREATE TABLE mail_cursor (
    mailbox      VARCHAR(320) PRIMARY KEY,
    uid_validity BIGINT       NOT NULL,
    last_uid     BIGINT       NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Откуда письмо и очередь уведомления в Telegram: готовый текст пишется вместе с письмом.
ALTER TABLE inbound_email
    ADD COLUMN mailbox          VARCHAR(320),
    ADD COLUMN imap_uid         BIGINT,
    ADD COLUMN message_id       VARCHAR(998),
    ADD COLUMN notify_status    VARCHAR(10),
    ADD COLUMN notify_text      TEXT,
    ADD COLUMN notify_silent    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notify_queued_at TIMESTAMPTZ,
    ADD COLUMN notify_attempts  INT     NOT NULL DEFAULT 0,
    ADD COLUMN notify_error     VARCHAR(300),
    ADD COLUMN notified_at      TIMESTAMPTZ;

CREATE INDEX idx_inbound_email_notify_pending ON inbound_email (id) WHERE notify_status = 'PENDING';
CREATE INDEX idx_inbound_email_message_id ON inbound_email (mailbox, message_id) WHERE message_id IS NOT NULL;
```

- [ ] **Step 4: Сущности и репозитории**

`entity/NotifyStatus.java`:

```java
package com.vladoose.nir.entity;

/** Уведомление о письме в Telegram: ждёт отправки / ушло / не ушло за сутки. NULL в строке — не ставилось. */
public enum NotifyStatus { PENDING, SENT, FAILED }
```

`entity/InboundType.java` — заменить целиком:

```java
package com.vladoose.nir.entity;

public enum InboundType {
    SUPPLIER_RESPONSE, CLIENT_REQUEST, UNMATCHED,
    /** «Письмо не доставлено» — возврат почтового сервера; статус запроса КП не меняется. */
    BOUNCE,
    /** Автоответ («в отпуске», Auto-Submitted); статус запроса КП не меняется. */
    AUTO_REPLY
}
```

`entity/InboundEmail.java` — после поля `status` (перед `market`) добавить:

```java
    /** Ящик, из которого пришло письмо (адрес нижним регистром); у писем до V24 — null. */
    @Column(length = 320)
    private String mailbox;

    @Column(name = "imap_uid")
    private Long imapUid;

    @Column(name = "message_id", length = 998)
    private String messageId;

    /** Уведомление в Telegram: null — не ставилось (Telegram выключен, своё письмо, письмо до V24). */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_status", length = 10)
    private NotifyStatus notifyStatus;

    /** Готовый текст уведомления: собирается при записи письма, когда известно, что сделал разбор. */
    @Column(name = "notify_text", columnDefinition = "TEXT")
    private String notifyText;

    @Column(name = "notify_silent", nullable = false)
    private boolean notifySilent;

    @Column(name = "notify_queued_at")
    private OffsetDateTime notifyQueuedAt;

    @Column(name = "notify_attempts", nullable = false)
    private int notifyAttempts;

    @Column(name = "notify_error", length = 300)
    private String notifyError;

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;
```

`entity/MailCursor.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Курсор приёма почты: последнее обработанное письмо ящика. Не рыночная таблица — ключ ящик. */
@Entity
@Table(name = "mail_cursor")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MailCursor {

    /** Адрес ящика нижним регистром. */
    @Id
    @Column(length = 320)
    private String mailbox;

    /** UIDVALIDITY папки: сменился — UID прежних писем больше ничего не значат. */
    @Column(name = "uid_validity", nullable = false)
    private long uidValidity;

    /** UID последнего обработанного письма; 0 — ящик был пуст. */
    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

`repository/MailCursorRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MailCursor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailCursorRepository extends JpaRepository<MailCursor, String> {
}
```

`repository/InboundEmailRepository.java` — добавить метод:

```java
    boolean existsByMailboxAndMessageId(String mailbox, String messageId);
```

- [ ] **Step 5: Тест зелёный**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailSchemaTest'`
Expected: PASS (3 теста). Flyway накатит V24 на nirdb при старте контекста.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V24__mail_cursor_and_telegram_queue.sql \
  src/main/java/com/vladoose/nir/entity/NotifyStatus.java src/main/java/com/vladoose/nir/entity/MailCursor.java \
  src/main/java/com/vladoose/nir/entity/InboundEmail.java src/main/java/com/vladoose/nir/entity/InboundType.java \
  src/main/java/com/vladoose/nir/repository/MailCursorRepository.java src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java \
  src/test/java/com/vladoose/nir/service/mail/MailSchemaTest.java
git commit -m "feat(mail): V24 — курсор ящика и очередь уведомлений Telegram в inbound_email

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Разбор письма — `ParsedMail`, `MailParser`, `MailText`

**Files:**
- Create: `service/mail/ParsedMail.java`, `service/mail/MailText.java`, `service/mail/MailParser.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/TestMimes.java` (помощник, public), `MailParserTest.java`, `MailTextTest.java`

**Interfaces:**
- Produces:
  - `record ParsedMail(long uid, String messageId, String from, String fromAddress, String subject, OffsetDateTime receivedAt, String contentType, Map<String,String> autoHeaders, String text, String html, List<String> attachmentNames, byte[] excelBytes, String excelName, ParsedMail.Bounce bounce)` + `String body()`;
  - `record ParsedMail.Bounce(String finalRecipient, String status, String diagnostic, String originalSubject)`;
  - `MailParser.parse(Message, long uid): ParsedMail`;
  - `public static String MailParser.addressPart(String)`;
  - пакетные `static String from(Message)`, `static String header(Part, String)`, `static OffsetDateTime receivedAt(Message)` — их зовёт задача 8;
  - `MailText.htmlToText(String)`, `MailText.replyText(ParsedMail)`, `MailText.excerpt(String, int)`;
  - тестовый `TestMimes` (public): `plain(from, subject, text)`, `html(from, subject, html)`, `withAttachments(...)`, `dsn(originalSubject, recipient, diagnostic)`, `dsnHeadersOnly(originalSubject)`, `autoReply(from, subject, text, header, value)`, `roundTrip(MimeMessage)`, `xlsxBytes()`.

- [ ] **Step 1: Помощник MIME для тестов**

`src/test/java/com/vladoose/nir/service/mail/TestMimes.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Письма в MIME для тестов разбора и приёма: как их собирают настоящие почтовые клиенты и серверы. */
public final class TestMimes {

    private TestMimes() {}

    /** «Имя <адрес>» или «адрес» → InternetAddress с UTF-8 именем. */
    public static InternetAddress addr(String s) throws Exception {
        int lt = s.indexOf('<');
        if (lt < 0) return new InternetAddress(s.trim());
        return new InternetAddress(s.substring(lt + 1, s.indexOf('>')).trim(), s.substring(0, lt).trim(), "UTF-8");
    }

    private static MimeMessage base(String from, String subject) throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(addr(from));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        m.setSubject(subject, "UTF-8");
        m.setSentDate(new Date());
        return m;
    }

    public static MimeMessage plain(String from, String subject, String text) throws Exception {
        MimeMessage m = base(from, subject);
        m.setText(text, "UTF-8");
        m.saveChanges();
        return m;
    }

    public static MimeMessage html(String from, String subject, String html) throws Exception {
        MimeMessage m = base(from, subject);
        m.setContent(html, "text/html; charset=UTF-8");
        m.saveChanges();
        return m;
    }

    /** Текст + вложения: имя файла → байты. Имя кодируется RFC 2047, как у почтовых клиентов. */
    public static MimeMessage withAttachments(String from, String subject, String text, Object... nameAndBytes) throws Exception {
        MimeMessage m = base(from, subject);
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText(text, "UTF-8");
        mixed.addBodyPart(body);
        for (int i = 0; i < nameAndBytes.length; i += 2) {
            MimeBodyPart file = new MimeBodyPart();
            file.setDataHandler(new DataHandler(new ByteArrayDataSource((byte[]) nameAndBytes[i + 1], "application/octet-stream")));
            file.setFileName(MimeUtility.encodeText((String) nameAndBytes[i], "UTF-8", "B"));
            file.setDisposition(MimeBodyPart.ATTACHMENT);
            mixed.addBodyPart(file);
        }
        m.setContent(mixed);
        m.saveChanges();
        return m;
    }

    /** Возврат почтового сервера (RFC 3464): пояснение + message/delivery-status + исходное письмо message/rfc822. */
    public static MimeMessage dsn(String originalSubject, String recipient, String diagnostic) throws Exception {
        MimeMessage m = base("Mail Delivery System <MAILER-DAEMON@corp.mail.ru>", "Undelivered Mail Returned to Sender");
        MimeMultipart report = new MimeMultipart("report; report-type=delivery-status");
        report.addBodyPart(human());
        report.addBodyPart(deliveryStatus(recipient, diagnostic));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        plain("zakup@westmed.kz", originalSubject, "Здравствуйте! Просим коммерческое предложение.").writeTo(raw);
        MimeBodyPart original = new MimeBodyPart();
        original.setDataHandler(new DataHandler(new ByteArrayDataSource(raw.toByteArray(), "message/rfc822")));
        original.setHeader("Content-Type", "message/rfc822");
        report.addBodyPart(original);

        m.setContent(report);
        m.saveChanges();
        return m;
    }

    /** Возврат, где вместо исходного письма — только его заголовки (text/rfc822-headers), тема закодирована RFC 2047. */
    public static MimeMessage dsnHeadersOnly(String originalSubject) throws Exception {
        MimeMessage m = base("postmaster@mx.example.kz", "Delivery Status Notification (Failure)");
        MimeMultipart report = new MimeMultipart("report; report-type=delivery-status");
        report.addBodyPart(human());
        report.addBodyPart(deliveryStatus("sales@x.kz", "550 5.1.1 User unknown"));
        String headers = "From: zakup@westmed.kz\r\nTo: sales@x.kz\r\nSubject: "
                + MimeUtility.encodeText(originalSubject, "UTF-8", "B") + "\r\n\r\n";
        MimeBodyPart h = new MimeBodyPart();
        h.setDataHandler(new DataHandler(new ByteArrayDataSource(headers.getBytes(StandardCharsets.US_ASCII), "text/rfc822-headers")));
        h.setHeader("Content-Type", "text/rfc822-headers");
        report.addBodyPart(h);
        m.setContent(report);
        m.saveChanges();
        return m;
    }

    public static MimeMessage autoReply(String from, String subject, String text, String header, String value) throws Exception {
        MimeMessage m = plain(from, subject, text);
        m.setHeader(header, value);
        m.saveChanges();
        return m;
    }

    /** Как письмо приходит из IMAP: сериализовать и разобрать заново (части — из байтов, а не из объектов). */
    public static MimeMessage roundTrip(MimeMessage m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return new MimeMessage((Session) null, new ByteArrayInputStream(out.toByteArray()));
    }

    public static byte[] xlsxBytes() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Кол-во");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Аппарат УЗИ");
            r.createCell(1).setCellValue(2);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static MimeBodyPart human() throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setText("This is the mail system at host mx.mail.ru.\n\nYour message could not be delivered.", "UTF-8");
        return p;
    }

    private static MimeBodyPart deliveryStatus(String recipient, String diagnostic) throws Exception {
        String status = "Reporting-MTA: dns; mx.mail.ru\r\n\r\n"
                + "Final-Recipient: rfc822; " + recipient + "\r\n"
                + "Action: failed\r\n"
                + "Status: 5.1.1\r\n"
                + "Diagnostic-Code: smtp; " + diagnostic + "\r\n";
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new DataHandler(new ByteArrayDataSource(status.getBytes(StandardCharsets.US_ASCII), "message/delivery-status")));
        p.setHeader("Content-Type", "message/delivery-status");
        return p;
    }
}
```

- [ ] **Step 2: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/MailParserTest.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.vladoose.nir.service.mail.TestMimes.*;
import static org.assertj.core.api.Assertions.assertThat;

class MailParserTest {

    @Test
    void plainLetter_cyrillicSender_subjectMessageIdText() throws Exception {
        MimeMessage m = roundTrip(plain("Иван Петров <ivan@medtech.kz>", "Re: [КП-534] Запрос", "Цена 100 тенге"));

        ParsedMail p = MailParser.parse(m, 7);

        assertThat(p.uid()).isEqualTo(7);
        assertThat(p.from()).isEqualTo("Иван Петров <ivan@medtech.kz>");
        assertThat(p.fromAddress()).isEqualTo("ivan@medtech.kz");
        assertThat(p.subject()).isEqualTo("Re: [КП-534] Запрос");
        assertThat(p.messageId()).startsWith("<").endsWith(">");
        assertThat(p.text()).contains("Цена 100 тенге");
        assertThat(p.html()).isEmpty();
        assertThat(p.attachmentNames()).isEmpty();
        assertThat(p.bounce()).isNull();
        assertThat(p.contentType()).startsWith("text/plain");
    }

    @Test
    void htmlOnly_bodyWithoutStyleBlocks() throws Exception {
        MimeMessage m = roundTrip(html("s@x.kz", "Цена",
                "<html><head><style>p{color:red}</style></head><body><p>Добрый день!</p><p>Цена 5 000 тг</p></body></html>"));

        ParsedMail p = MailParser.parse(m, 1);

        assertThat(p.text()).isEmpty();
        assertThat(p.body()).contains("Добрый день!").contains("Цена 5 000 тг").doesNotContain("color");
    }

    @Test
    void nestedAlternativeWithEncodedExcel_textExcelAndName() throws Exception {
        MimeMultipart alt = new MimeMultipart("alternative");
        MimeBodyPart t = new MimeBodyPart();
        t.setText("Прошу выставить КП по списку", "UTF-8");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Прошу выставить КП по списку</p>", "text/html; charset=UTF-8");
        alt.addBodyPart(t);
        alt.addBodyPart(h);
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(xlsxBytes(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
        file.setFileName(MimeUtility.encodeText("Список ТХ.xlsx", "UTF-8", "B"));
        file.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(altPart);
        mixed.addBodyPart(file);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("clinic@x.kz"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("zakup@westmed.kz"));
        m.setSubject("Заявка", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Прошу выставить КП");
        assertThat(p.excelBytes()).isNotNull();
        assertThat(p.excelName()).isEqualTo("Список ТХ.xlsx");
        assertThat(p.attachmentNames()).containsExactly("Список ТХ.xlsx");
    }

    @Test
    void inlineSignatureImage_isNotAttachment_pdfIs() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart h = new MimeBodyPart();
        h.setContent("<p>Цена в файле</p><img src=\"cid:logo1\">", "text/html; charset=UTF-8");
        related.addBodyPart(h);
        MimeBodyPart img = new MimeBodyPart();
        img.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3}, "image/png")));
        img.setContentID("<logo1>");
        img.setDisposition(MimeBodyPart.INLINE);
        img.setFileName("image001.png");
        related.addBodyPart(img);
        MimeBodyPart relPart = new MimeBodyPart();
        relPart.setContent(related);
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{4, 5}, "application/pdf")));
        pdf.setFileName(MimeUtility.encodeText("КП.pdf", "UTF-8", "B"));
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(relPart);
        mixed.addBodyPart(pdf);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("КП", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.attachmentNames()).containsExactly("КП.pdf");
        assertThat(p.body()).contains("Цена в файле");
    }

    @Test
    void dsnWithOriginalMessage_bounceFields_andOriginalTextNotMerged() throws Exception {
        MimeMessage m = roundTrip(dsn("[КП-534] Запрос КП", "sales@medtech.kz",
                "550 5.1.1 <sales@medtech.kz>:\r\n    Recipient address rejected: User unknown"));

        ParsedMail p = MailParser.parse(m, 3);

        assertThat(p.contentType()).startsWith("multipart/report").contains("delivery-status");
        assertThat(p.fromAddress()).isEqualTo("mailer-daemon@corp.mail.ru");
        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().finalRecipient()).isEqualTo("sales@medtech.kz");
        assertThat(p.bounce().status()).isEqualTo("5.1.1");
        assertThat(p.bounce().diagnostic()).contains("550 5.1.1").contains("User unknown");
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-534] Запрос КП");
        assertThat(p.text()).contains("could not be delivered").doesNotContain("Просим коммерческое");
    }

    @Test
    void dsnWithHeadersOnly_decodesEncodedSubject() throws Exception {
        ParsedMail p = MailParser.parse(roundTrip(dsnHeadersOnly("[КП-77] Запрос коммерческого предложения")), 1);

        assertThat(p.bounce()).isNotNull();
        assertThat(p.bounce().originalSubject()).isEqualTo("[КП-77] Запрос коммерческого предложения");
    }

    @Test
    void autoReplyHeaders_lowercased() throws Exception {
        MimeMessage m = plain("s@x.kz", "Автоответ", "Я в отпуске");
        m.setHeader("Auto-Submitted", "Auto-Replied");
        m.setHeader("X-Autoreply", "yes");
        m.setHeader("Precedence", "Bulk");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.autoHeaders()).containsEntry("auto-submitted", "auto-replied")
                .containsEntry("x-autoreply", "yes").containsEntry("precedence", "bulk");
    }

    @Test
    void unknownCharset_doesNotThrow_readsBytes() throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("Кодировка", "UTF-8");
        m.setDataHandler(new DataHandler(new ByteArrayDataSource("Привет".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=x-unknown-777")));
        m.setHeader("Content-Type", "text/plain; charset=x-unknown-777");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.text()).contains("Привет");
    }

    @Test
    void addressPart_cases() {
        assertThat(MailParser.addressPart("Иван <Ivan@X.kz>")).isEqualTo("ivan@x.kz");
        assertThat(MailParser.addressPart("a@b.kz")).isEqualTo("a@b.kz");
        assertThat(MailParser.addressPart(null)).isEmpty();
    }
}
```

`src/test/java/com/vladoose/nir/service/mail/MailTextTest.java`:

```java
package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MailTextTest {

    private static ParsedMail mail(String text, String html) {
        return new ParsedMail(1, null, "s@x.kz", "s@x.kz", "Тема", OffsetDateTime.now(), "text/plain",
                Map.of(), text, html, List.of(), null, null, null);
    }

    @Test
    void htmlToText_dropsStyleAndScript_brAndBlocksBreakLines() {
        String t = MailText.htmlToText("<style>.a{x:1}</style><script>var a;</script><div>Строка 1<br>Строка 2</div><p>Абзац</p>");
        assertThat(t).isEqualTo("Строка 1\nСтрока 2\nАбзац");
    }

    @Test
    void replyText_cutsQuotedOriginal_plain() {
        String t = MailText.replyText(mail("Цена 10 000 тг.\n\n> Здравствуйте! Просим КП\n> по лоту", ""));
        assertThat(t).isEqualTo("Цена 10 000 тг.");
    }

    @Test
    void replyText_dropsHtmlBlockquote() {
        String t = MailText.replyText(mail("", "<div>Отказ, не поставляем</div><blockquote>Наше письмо: просим КП</blockquote>"));
        assertThat(t).isEqualTo("Отказ, не поставляем");
    }

    @Test
    void excerpt_cutsOnWordBoundary_withEllipsis() {
        String t = MailText.excerpt("один два три четыре пять", 12);
        assertThat(t).isEqualTo("один два…");
        assertThat(t.length()).isLessThanOrEqualTo(12);
        assertThat(MailText.excerpt("коротко", 100)).isEqualTo("коротко");
    }

    @Test
    void normalize_collapsesBlankLinesAndNbsp() {
        assertThat(MailText.normalize("\n\nа  б\n\n\n\nв  \n")).isEqualTo("а б\n\nв");
    }
}
```

- [ ] **Step 3: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailParserTest' --tests 'com.vladoose.nir.service.mail.MailTextTest'`
Expected: FAIL компиляции — нет `ParsedMail`, `MailParser`, `MailText`.

- [ ] **Step 4: Реализация**

`service/mail/ParsedMail.java`:

```java
package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Разобранное письмо — всё, что нужно классификации, записи и тексту уведомления. Неизменяемо и без ссылок на
 * Jakarta Mail: разбор идёт ВНЕ транзакции (спека §3.3), запись — потом, отдельным бином.
 *
 * @param messageId       заголовок Message-ID как есть; null — заголовка нет
 * @param from            отправитель для людей: «Имя <адрес>» или адрес; "" — нет
 * @param fromAddress     адрес отправителя нижним регистром; "" — нет
 * @param contentType     верхний Content-Type нижним регистром, пробелы схлопнуты
 * @param autoHeaders     признаки автоответа: имя заголовка нижним регистром → значение нижним регистром
 *                        (auto-submitted, x-autoreply, x-autorespond, precedence)
 * @param text            text/plain письма (не вложения); "" — нет
 * @param html            text/html письма (не вложения); "" — нет
 * @param attachmentNames имена вложений; встроенные картинки подписи (inline + Content-ID) не входят
 * @param excelBytes      первое Excel-вложение или null
 * @param bounce          сведения из частей возврата (DSN); null — таких частей нет
 */
public record ParsedMail(long uid, String messageId, String from, String fromAddress, String subject,
                         OffsetDateTime receivedAt, String contentType, Map<String, String> autoHeaders,
                         String text, String html, List<String> attachmentNames,
                         byte[] excelBytes, String excelName, Bounce bounce) {

    /** Возврат: адресат, код статуса, диагностика сервера, тема исходного письма (декодирована). */
    public record Bounce(String finalRecipient, String status, String diagnostic, String originalSubject) {}

    /** Текст письма: text/plain, а если его нет — HTML, переведённый в текст. */
    public String body() {
        return !text.isBlank() ? text : MailText.htmlToText(html);
    }
}
```

`service/mail/MailText.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.EmailReplyText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/** Текст письма для людей: HTML → текст, ответ без цитаты нашего письма, отрывок. */
public final class MailText {

    private MailText() {}

    /**
     * HTML → текст. Блоки style/script/head выбрасываются: регулярка {@link EmailReplyText} оставила бы CSS писем
     * Outlook в начале текста. br и блочные элементы дают переводы строк.
     */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("style, script, head, title").remove();
        for (Element br : doc.select("br")) br.before(new TextNode("\n"));
        for (Element block : doc.select("p, div, tr, li, h1, h2, h3, h4, h5, h6, table, blockquote")) {
            block.after(new TextNode("\n"));
        }
        return normalize(doc.body() != null ? doc.body().wholeText() : doc.wholeText());
    }

    /** Ответ без цитаты нашего письма: HTML-цитата (blockquote) выбрасывается целиком, текстовая — {@link EmailReplyText}. */
    public static String replyText(ParsedMail m) {
        String body;
        if (!m.text().isBlank()) {
            body = m.text();
        } else {
            Document doc = Jsoup.parse(m.html());
            doc.select("blockquote").remove();
            body = htmlToText(doc.outerHtml());
        }
        return normalize(EmailReplyText.stripToReply(body));
    }

    /** Отрывок не длиннее max символов: режется по границе слова, с «…». */
    public static String excerpt(String s, int max) {
        String t = normalize(s);
        if (t.length() <= max) return t;
        int cut = Math.max(t.lastIndexOf(' ', max - 1), t.lastIndexOf('\n', max - 1));
        if (cut < max / 2) cut = max - 1;
        return t.substring(0, cut).strip() + "…";
    }

    /** NBSP → пробел, пробелы в строке схлопнуты, строки обрезаны, подряд — не больше одной пустой строки. */
    static String normalize(String s) {
        if (s == null) return "";
        String[] lines = s.replace(' ', ' ').replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String l = line.replaceAll("[ \\t\\x0B\\f]+", " ").strip();
            if (l.isEmpty()) {
                blank++;
                if (blank > 1 || out.length() == 0) continue;
                out.append('\n');
            } else {
                blank = 0;
                out.append(l).append('\n');
            }
        }
        return out.toString().strip();
    }
}
```

`service/mail/MailParser.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Разбор письма в {@link ParsedMail}: рекурсивный обход multipart (у настоящих писем — вложенный multipart/alternative),
 * имена файлов декодируются RFC 2047, Excel распознаётся и по расширению, и по Content-Type. Части возврата (DSN) —
 * отдельно: message/delivery-status, message/rfc822 (исходное письмо — только тема, текст в тело не идёт),
 * text/rfc822-headers.
 */
public final class MailParser {

    static final int MAX_TEXT = 200_000;      // дальше текст не копим — защита памяти
    static final int MAX_DEPTH = 10;
    static final int MAX_DSN = 20_000;
    private static final List<String> AUTO_HEADERS = List.of("Auto-Submitted", "X-Autoreply", "X-Autorespond", "Precedence");

    private MailParser() {}

    public static ParsedMail parse(Message msg, long uid) throws MessagingException, IOException {
        String from = from(msg);
        String subject = msg.getSubject();
        Walk w = new Walk();
        walk(msg, w, 0);
        return new ParsedMail(uid, header(msg, "Message-ID"), from, addressPart(from),
                subject == null ? "" : subject, receivedAt(msg), contentType(msg), autoHeaders(msg),
                w.text.toString(), w.html.toString(), List.copyOf(w.attachments), w.excel, w.excelName, w.bounce());
    }

    /**
     * Отправитель для людей: «Имя <адрес>» без кавычек (InternetAddress.toUnicodeString взял бы кириллическое имя
     * в кавычки); битый From — сырое значение, декодированное RFC 2047.
     */
    static String from(Message msg) throws MessagingException {
        try {
            Address[] a = msg.getFrom();
            if (a == null || a.length == 0) return "";
            if (a[0] instanceof InternetAddress ia) {
                String name = ia.getPersonal();
                String addr = ia.getAddress() == null ? "" : ia.getAddress();
                return name == null || name.isBlank() ? addr : name.strip() + " <" + addr + ">";
            }
            return decode(a[0].toString());
        } catch (AddressException e) {
            String raw = header(msg, "From");
            return raw == null ? "" : decode(raw);
        }
    }

    /** Адресная часть «Имя <a@b>» → «a@b», нижним регистром. */
    public static String addressPart(String from) {
        if (from == null) return "";
        String s = from.trim();
        int lt = s.lastIndexOf('<'), gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) s = s.substring(lt + 1, gt);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** Время получения: дата сервера → дата отправки → сейчас. */
    static OffsetDateTime receivedAt(Message msg) {
        try {
            Date d = msg.getReceivedDate();
            if (d == null) d = msg.getSentDate();
            if (d != null) return d.toInstant().atOffset(ZoneOffset.UTC);
        } catch (MessagingException ignored) {
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    static String header(Part p, String name) throws MessagingException {
        String[] v = p.getHeader(name);
        return v == null || v.length == 0 ? null : v[0].trim();
    }

    private static String contentType(Message msg) throws MessagingException {
        String ct = msg.getContentType();
        return ct == null ? "" : ct.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> autoHeaders(Message msg) throws MessagingException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String h : AUTO_HEADERS) {
            String v = header(msg, h);
            if (v != null) out.put(h.toLowerCase(Locale.ROOT), v.toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(out);
    }

    private static void walk(Part part, Walk w, int depth) throws MessagingException, IOException {
        if (depth > MAX_DEPTH) return;
        if (part.isMimeType("multipart/*")) {
            if (part.getContent() instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), w, depth + 1);
            }
            return;
        }
        if (part.isMimeType("message/delivery-status")) {
            w.deliveryStatus(readText(part, MAX_DSN));
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            try (InputStream in = part.getInputStream()) {
                w.originalSubject = new MimeMessage((Session) null, in).getSubject();
            }
            String name = decode(part.getFileName());
            if (name != null) w.attachments.add(name);
            return;
        }
        if (part.isMimeType("text/rfc822-headers")) {
            try (InputStream in = part.getInputStream()) {
                String s = new InternetHeaders(in).getHeader("Subject", null);
                if (s != null) w.originalSubject = decode(MimeUtility.unfold(s));
            }
            return;
        }
        String fileName = decode(part.getFileName());
        boolean excel = isExcel(part, fileName);
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (fileName != null && !embeddedImage(part)) || excel;
        if (excel && w.excel == null) {
            try (InputStream in = part.getInputStream()) {
                w.excel = in.readAllBytes();
            }
            w.excelName = fileName != null ? fileName : "attachment.xlsx";
        }
        if (attachment) {
            w.attachments.add(fileName != null ? fileName : "без имени");
        } else if (part.isMimeType("text/plain")) {
            append(w.text, text(part));
        } else if (part.isMimeType("text/html")) {
            append(w.html, text(part));
        }
    }

    private static boolean isExcel(Part part, String fileName) throws MessagingException {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls")
                || part.isMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || part.isMimeType("application/vnd.ms-excel");
    }

    /** Картинка подписи, встроенная в HTML (inline + Content-ID), — не вложение. */
    private static boolean embeddedImage(Part part) throws MessagingException {
        return part.isMimeType("image/*") && part instanceof MimePart mp && mp.getContentID() != null
                && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
    }

    private static String text(Part part) throws MessagingException, IOException {
        try {
            Object c = part.getContent();
            return c instanceof String s ? s : readText(part, MAX_TEXT);
        } catch (UnsupportedEncodingException e) {    // неизвестная кодировка — байты как UTF-8, письмо не теряем
            return readText(part, MAX_TEXT);
        }
    }

    private static String readText(Part part, int max) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            return new String(in.readNBytes(max), StandardCharsets.UTF_8);
        }
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || sb.length() >= MAX_TEXT) return;
        sb.append(s, 0, Math.min(s.length(), MAX_TEXT - sb.length()));
    }

    static String decode(String s) {
        if (s == null) return null;
        try {
            return MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }

    /** Поля DSN (RFC 3464): первое значение каждого имени, строки-продолжения склеены. */
    static Map<String, String> dsnFields(String s) {
        Map<String, String> out = new HashMap<>();
        String unfolded = s.replace("\r\n", "\n").replaceAll("\n[ \t]+", " ");
        for (String line : unfolded.split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            out.putIfAbsent(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        return out;
    }

    /** «rfc822; a@b» → «a@b», «smtp; 550 …» → «550 …». */
    private static String afterType(String v) {
        if (v == null) return null;
        int i = v.indexOf(';');
        String s = (i >= 0 ? v.substring(i + 1) : v).trim();
        return s.isEmpty() ? null : s;
    }

    private static final class Walk {
        final StringBuilder text = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        final List<String> attachments = new ArrayList<>();
        byte[] excel;
        String excelName;
        boolean dsn;
        String finalRecipient;
        String status;
        String diagnostic;
        String originalSubject;

        void deliveryStatus(String s) {
            dsn = true;
            Map<String, String> f = dsnFields(s);
            finalRecipient = afterType(f.get("final-recipient"));
            if (finalRecipient == null) finalRecipient = afterType(f.get("original-recipient"));
            status = f.get("status");
            diagnostic = afterType(f.get("diagnostic-code"));
            if (diagnostic != null && diagnostic.length() > 200) diagnostic = diagnostic.substring(0, 200);
        }

        ParsedMail.Bounce bounce() {
            if (!dsn && originalSubject == null) return null;
            return new ParsedMail.Bounce(finalRecipient, status, diagnostic, originalSubject);
        }
    }
}
```

- [ ] **Step 5: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailParserTest' --tests 'com.vladoose.nir.service.mail.MailTextTest'`
Expected: PASS (9 + 5).
Если упадёт `htmlToText_…` из-за пробелов вокруг `\n` — правится `normalize`, а не ожидание: строки обязаны быть обрезаны.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/ParsedMail.java src/main/java/com/vladoose/nir/service/mail/MailText.java \
  src/main/java/com/vladoose/nir/service/mail/MailParser.java src/test/java/com/vladoose/nir/service/mail/TestMimes.java \
  src/test/java/com/vladoose/nir/service/mail/MailParserTest.java src/test/java/com/vladoose/nir/service/mail/MailTextTest.java
git commit -m "feat(mail): разбор письма — ParsedMail, части возврата (DSN), HTML в текст без CSS

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Вид письма — `MailClassifier`

**Files:**
- Create: `service/mail/MailClass.java`, `service/mail/Classification.java`, `service/mail/ClassifierRules.java`, `service/mail/MailClassifier.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/TestMails.java` (помощник, public), `MailClassifierTest.java`

**Interfaces:**
- Consumes: `ParsedMail`, `MailParser.addressPart` (задача 2); `KpToken.parse(String): Optional<Long>` (есть).
- Produces:
  - `enum MailClass { SITE_NOTIFICATION, OWN, BOUNCE, AUTO_REPLY, SUPPLIER_RESPONSE, CLIENT_REQUEST, UNMATCHED }`;
  - `record Classification(MailClass mailClass, Long kpId)`;
  - `record ClassifierRules(String ownAddress, String siteNotificationFrom, boolean clientRequests)` — адреса нижним регистром, "" — правило выключено;
  - `static Classification MailClassifier.classify(ParsedMail, ClassifierRules)`;
  - тестовый `TestMails.mail()` → `TestMails.Builder` с методами `uid`, `messageId`, `from`, `subject`, `receivedAt`, `contentType`, `header(name, value)`, `text`, `html`, `attachments(String...)`, `excel(name)`, `bounce(recipient, status, diagnostic, originalSubject)`, `build()`.

- [ ] **Step 1: Помощник `TestMails`**

`src/test/java/com/vladoose/nir/service/mail/TestMails.java`:

```java
package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Разобранные письма для тестов классификации, текста уведомления и записи — без IMAP и MIME. */
public final class TestMails {

    private TestMails() {}

    public static Builder mail() { return new Builder(); }

    public static final class Builder {
        private long uid = 1;
        private String messageId = "<t-" + System.nanoTime() + "@x.kz>";
        private String from = "Поставщик <s@x.kz>";
        private String subject = "Тема";
        private OffsetDateTime receivedAt = OffsetDateTime.parse("2026-10-05T09:00:00Z");
        private String contentType = "text/plain; charset=utf-8";
        private Map<String, String> autoHeaders = Map.of();
        private String text = "Текст письма";
        private String html = "";
        private List<String> attachments = List.of();
        private byte[] excel;
        private String excelName;
        private ParsedMail.Bounce bounce;

        public Builder uid(long v) { uid = v; return this; }
        public Builder messageId(String v) { messageId = v; return this; }
        public Builder from(String v) { from = v; return this; }
        public Builder subject(String v) { subject = v; return this; }
        public Builder receivedAt(OffsetDateTime v) { receivedAt = v; return this; }
        public Builder contentType(String v) { contentType = v; return this; }
        public Builder header(String name, String value) {
            Map<String, String> m = new HashMap<>(autoHeaders);
            m.put(name.toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT));
            autoHeaders = Map.copyOf(m);
            return this;
        }
        public Builder text(String v) { text = v; return this; }
        public Builder html(String v) { html = v; text = ""; return this; }
        public Builder attachments(String... v) { attachments = List.of(v); return this; }
        public Builder excel(String name) { excel = new byte[]{1, 2, 3}; excelName = name; attachments = List.of(name); return this; }
        public Builder bounce(String recipient, String status, String diagnostic, String originalSubject) {
            bounce = new ParsedMail.Bounce(recipient, status, diagnostic, originalSubject);
            return this;
        }

        public ParsedMail build() {
            return new ParsedMail(uid, messageId, from, MailParser.addressPart(from), subject, receivedAt, contentType,
                    autoHeaders, text, html, attachments, excel, excelName, bounce);
        }
    }
}
```

- [ ] **Step 2: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/MailClassifierTest.java`:

```java
package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class MailClassifierTest {

    static final ClassifierRules ZAKUP = new ClassifierRules("zakup@westmed.kz", "info@westmed.kz", false);
    static final ClassifierRules INFO = new ClassifierRules("zakup@westmed.kz", "info@westmed.kz", true);

    @Test
    void siteNotification_skipped() {
        Classification c = MailClassifier.classify(mail().from("WestMed.kz <info@westmed.kz>")
                .subject("Запрос КП (2 поз.) — westmed.kz").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SITE_NOTIFICATION);
    }

    @Test
    void ownEcho_withToken_isOwn() {
        Classification c = MailClassifier.classify(mail().from("zakup@westmed.kz").subject("[КП-5] Запрос").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.OWN);
    }

    @Test
    void dsnContentType_isBounce_tokenFromOriginalSubject() {
        Classification c = MailClassifier.classify(mail().from("MAILER-DAEMON@corp.mail.ru")
                .subject("Undelivered Mail Returned to Sender")
                .contentType("multipart/report; report-type=delivery-status; boundary=\"x\"")
                .bounce("sales@x.kz", "5.1.1", "550 User unknown", "[КП-534] Запрос КП").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
        assertThat(c.kpId()).isEqualTo(534L);
    }

    @Test
    void quotedReportType_isBounce() {
        Classification c = MailClassifier.classify(mail().from("robot@x.kz")
                .contentType("multipart/report; report-type=\"delivery-status\"").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
    }

    @Test
    void undeliverableFromPostmaster_isBounce_notSupplierResponse() {
        Classification c = MailClassifier.classify(mail().from("postmaster@medtech.kz")
                .subject("Undeliverable: [КП-5] Запрос КП").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.BOUNCE);
        assertThat(c.kpId()).isEqualTo(5L);
    }

    @Test
    void bounceToken_fromBodyAsLastResort() {
        Classification c = MailClassifier.classify(mail().from("mailer-daemon@x.kz").subject("Mail failure")
                .text("Original subject: [КП-12] Запрос").build(), ZAKUP);
        assertThat(c.kpId()).isEqualTo(12L);
    }

    @Test
    void autoSubmitted_withToken_isAutoReply() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-9] Запрос")
                .header("Auto-Submitted", "auto-replied").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.AUTO_REPLY);
        assertThat(c.kpId()).isEqualTo(9L);
    }

    @Test
    void autoSubmittedNo_isSupplierResponse() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-9] Запрос")
                .header("Auto-Submitted", "no").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
    }

    @Test
    void autoReplySubject_isAutoReply() {
        Classification c = MailClassifier.classify(mail().subject("Автоматический ответ: Re: [КП-9] Запрос").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.AUTO_REPLY);
        assertThat(c.kpId()).isEqualTo(9L);
    }

    @Test
    void xAutoreplyHeader_isAutoReply() {
        assertThat(MailClassifier.classify(mail().header("X-Autoreply", "yes").build(), ZAKUP).mailClass())
                .isEqualTo(MailClass.AUTO_REPLY);
    }

    @Test
    void precedenceBulk_isNotAutoReply() {
        assertThat(MailClassifier.classify(mail().header("Precedence", "bulk").build(), ZAKUP).mailClass())
                .isEqualTo(MailClass.UNMATCHED);
    }

    @Test
    void token_isSupplierResponse() {
        Classification c = MailClassifier.classify(mail().subject("Re: [КП-77] Запрос КП").build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
        assertThat(c.kpId()).isEqualTo(77L);
    }

    @Test
    void excelWithoutToken_dependsOnClientRequests() {
        assertThat(MailClassifier.classify(mail().excel("прайс.xlsx").build(), ZAKUP).mailClass()).isEqualTo(MailClass.UNMATCHED);
        assertThat(MailClassifier.classify(mail().excel("заявка.xlsx").build(), INFO).mailClass()).isEqualTo(MailClass.CLIENT_REQUEST);
    }

    @Test
    void plain_isUnmatched() {
        Classification c = MailClassifier.classify(mail().build(), ZAKUP);
        assertThat(c.mailClass()).isEqualTo(MailClass.UNMATCHED);
        assertThat(c.kpId()).isNull();
    }
}
```

- [ ] **Step 3: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailClassifierTest'`
Expected: FAIL компиляции.

- [ ] **Step 4: Реализация**

`service/mail/MailClass.java`:

```java
package com.vladoose.nir.service.mail;

/** Вид письма ящика (спека §4.1): от него зависят строка «Входящих», правки запроса КП и текст уведомления. */
public enum MailClass {
    /** Уведомление сайта о заявке — не записывается (заявки приходят через API сайта). */
    SITE_NOTIFICATION,
    /** Своё письмо (From = адрес отправки КП) — записывается без уведомления. */
    OWN,
    BOUNCE,
    AUTO_REPLY,
    SUPPLIER_RESPONSE,
    CLIENT_REQUEST,
    UNMATCHED
}
```

`service/mail/Classification.java`:

```java
package com.vladoose.nir.service.mail;

/** Вид письма и id запроса КП из метки [КП-id] (null — метки нет). */
public record Classification(MailClass mailClass, Long kpId) {}
```

`service/mail/ClassifierRules.java`:

```java
package com.vladoose.nir.service.mail;

/**
 * Настройки классификации: адрес отправки КП («своё письмо»), адрес уведомлений сайта (нижним регистром, "" — правило
 * выключено) и считать ли Excel без метки письмом клиники (ящик info@ — да, ящик закупок zakup@ — нет).
 */
public record ClassifierRules(String ownAddress, String siteNotificationFrom, boolean clientRequests) {}
```

`service/mail/MailClassifier.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.KpToken;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Вид письма (спека §4.1) — чистая функция, порядок проверок значим. */
public final class MailClassifier {

    private static final Set<String> DAEMONS = Set.of("mailer-daemon", "postmaster", "mail-daemon");
    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^\\s*(автоответ|автоматический ответ|auto:|automatic reply|autoreply|auto-reply|out of office)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private MailClassifier() {}

    public static Classification classify(ParsedMail m, ClassifierRules r) {
        String from = m.fromAddress();
        if (!r.siteNotificationFrom().isBlank() && from.equalsIgnoreCase(r.siteNotificationFrom())
                && m.subject().strip().endsWith("— westmed.kz")) {
            return new Classification(MailClass.SITE_NOTIFICATION, null);
        }
        if (!r.ownAddress().isBlank() && from.equalsIgnoreCase(r.ownAddress())) {
            return new Classification(MailClass.OWN, null);
        }
        if (isBounce(m)) return new Classification(MailClass.BOUNCE, bounceToken(m));
        Long token = KpToken.parse(m.subject()).orElse(null);
        if (isAutoReply(m)) return new Classification(MailClass.AUTO_REPLY, token);
        if (token != null) return new Classification(MailClass.SUPPLIER_RESPONSE, token);
        if (m.excelBytes() != null && r.clientRequests()) return new Classification(MailClass.CLIENT_REQUEST, null);
        return new Classification(MailClass.UNMATCHED, null);
    }

    /** Возврат: отчёт о доставке (multipart/report … delivery-status) или письмо почтового робота. */
    static boolean isBounce(ParsedMail m) {
        String ct = m.contentType();
        if (ct.startsWith("multipart/report") && ct.contains("delivery-status")) return true;
        String addr = m.fromAddress();
        int at = addr.indexOf('@');
        return DAEMONS.contains(at > 0 ? addr.substring(0, at) : addr);
    }

    /** Метка запроса КП в возврате: тема исходного письма → тема возврата → текст возврата. */
    static Long bounceToken(ParsedMail m) {
        if (m.bounce() != null && m.bounce().originalSubject() != null) {
            Optional<Long> t = KpToken.parse(m.bounce().originalSubject());
            if (t.isPresent()) return t.get();
        }
        Optional<Long> t = KpToken.parse(m.subject());
        return t.isPresent() ? t.get() : KpToken.parse(m.body()).orElse(null);
    }

    /** Автоответ: RFC 3834 Auto-Submitted (кроме «no»), X-Autoreply / X-Autorespond, Precedence auto_reply, тема. */
    static boolean isAutoReply(ParsedMail m) {
        String as = m.autoHeaders().get("auto-submitted");
        if (as != null && !as.isBlank() && !as.startsWith("no")) return true;
        if (m.autoHeaders().containsKey("x-autoreply") || m.autoHeaders().containsKey("x-autorespond")) return true;
        if ("auto_reply".equals(m.autoHeaders().get("precedence"))) return true;
        return AUTO_SUBJECT.matcher(m.subject()).find();
    }
}
```

- [ ] **Step 5: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailClassifierTest'`
Expected: PASS (14).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/MailClass.java src/main/java/com/vladoose/nir/service/mail/Classification.java \
  src/main/java/com/vladoose/nir/service/mail/ClassifierRules.java src/main/java/com/vladoose/nir/service/mail/MailClassifier.java \
  src/test/java/com/vladoose/nir/service/mail/TestMails.java src/test/java/com/vladoose/nir/service/mail/MailClassifierTest.java
git commit -m "feat(mail): вид письма — возврат, автоответ, ответ поставщика, прайс без метки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Текст уведомления — `MailNotificationComposer`

**Files:**
- Create: `service/mail/KpOutcome.java`, `service/mail/KpSnapshot.java`, `service/mail/ComposeContext.java`, `service/mail/BrokenMail.java`, `service/mail/MailNotification.java`, `service/mail/MailNotificationComposer.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/MailNotificationComposerTest.java`

**Interfaces:**
- Consumes: `ParsedMail`, `MailText` (задача 2); `MailClass`, `Classification`, `TestMails` (задача 3); `DocFormat.money(BigDecimal)`, `Market.currencySymbol()` (есть).
- Produces:
  - `enum KpOutcome { PRICE_PARSED, PRICE_SET, DECLINED, NO_PRICE, MULTI_LOT, UNCHANGED, NOT_FOUND }`;
  - `record KpSnapshot(long id, String supplierName, String supplierEmail, long tenderId, String tenderNumber, boolean privateRequest, List<KpSnapshot.LotLine> lots, String status, BigDecimal price)` + `record LotLine(String name, Integer quantity)`;
  - `record ComposeContext(String mailbox, Market market, String publicUrl, OffsetDateTime queuedAt)`;
  - `record BrokenMail(long uid, String messageId, String from, String subject, OffsetDateTime receivedAt, String errorClass)`;
  - `record MailNotification(String text, boolean silent)`;
  - `static MailNotification MailNotificationComposer.compose(ParsedMail, Classification, KpSnapshot /*null*/, KpOutcome /*null*/, ComposeContext)`;
  - `static MailNotification MailNotificationComposer.composeBroken(BrokenMail, ComposeContext)`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/MailNotificationComposerTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class MailNotificationComposerTest {

    static final OffsetDateTime QUEUED = OffsetDateTime.parse("2026-10-05T09:01:00Z");
    static final ComposeContext KZ = new ComposeContext("zakup@westmed.kz", Market.KZ, "https://ais.westmed.kz", QUEUED);

    static KpSnapshot kp(String status, BigDecimal price, KpSnapshot.LotLine... lots) {
        return new KpSnapshot(534, "ТОО «Медтехника»", "sales@medtech.kz", 77, "17295275-1", false,
                List.of(lots), status, price);
    }

    static Classification sup() { return new Classification(MailClass.SUPPLIER_RESPONSE, 534L); }

    @Test
    void supplierResponse_priceParsed_exactText() {
        ParsedMail m = mail().from("Иван Петров <ivan@medtech.kz>")
                .subject("Re: [КП-534] Запрос коммерческого предложения")
                .text("Добрый день!\nЦена 3 450 000 тг, срок 30 дней.\n\nС уважением, Иван\n\n> Здравствуйте! Просим КП")
                .attachments("КП.pdf").build();

        MailNotification n = MailNotificationComposer.compose(m, sup(),
                kp("RESPONDED", new BigDecimal("3450000"), new KpSnapshot.LotLine("Аппарат УЗИ", 1)),
                KpOutcome.PRICE_PARSED, KZ);

        assertThat(n.silent()).isFalse();
        assertThat(n.text()).isEqualTo("""
                📩 Ответ поставщика · ТОО «Медтехника»
                Запрос КП №534 · тендер 17295275-1
                Лот: Аппарат УЗИ — 1 шт.
                💡 Цена распознана: 3 450 000,00 ₸ — проверьте
                От: Иван Петров <ivan@medtech.kz>
                Тема: Re: [КП-534] Запрос коммерческого предложения
                Вложения: КП.pdf

                Добрый день!
                Цена 3 450 000 тг, срок 30 дней.

                С уважением, Иван

                Открыть в АИС: https://ais.westmed.kz/tenders?openId=77&market=KZ""");
    }

    @Test
    void bounce_withRequest_exactText() {
        ParsedMail m = mail().from("MAILER-DAEMON@corp.mail.ru").subject("Undelivered Mail Returned to Sender")
                .bounce("sales@medtech.kz", "5.1.1", "550 5.1.1 <sales@medtech.kz>: Recipient address rejected: User unknown",
                        "[КП-534] Запрос КП").build();

        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, 534L),
                kp("SENT", null), null, KZ);

        assertThat(n.silent()).isFalse();
        assertThat(n.text()).isEqualTo("""
                ⚠️ Письмо не доставлено · ТОО «Медтехника» (sales@medtech.kz)
                Запрос КП №534 · тендер 17295275-1
                Причина: 550 5.1.1 <sales@medtech.kz>: Recipient address rejected: User unknown
                Исправьте адрес в карточке поставщика и нажмите «Переслать» в запросах КП тендера.

                Открыть в АИС: https://ais.westmed.kz/tenders?openId=77&market=KZ""");
    }

    @Test
    void bounce_withoutRequest_recipientAndSubject_linkToInbound() {
        ParsedMail m = mail().from("postmaster@x.kz").subject("Mail failure").bounce("a@b.kz", "5.0.0", null, null).build();
        String t = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, null), null, null, KZ).text();
        assertThat(t).startsWith("⚠️ Письмо не доставлено · a@b.kz\nТема возврата: Mail failure\nПричина: 5.0.0")
                .endsWith("Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ");
    }

    @Test
    void outcomes_lines() {
        ParsedMail m = mail().build();
        KpSnapshot one = kp("SENT", null, new KpSnapshot.LotLine("А", 1));
        assertThat(MailNotificationComposer.compose(m, sup(), one, KpOutcome.DECLINED, KZ).text()).contains("\n⛔ Поставщик отказался\n");
        assertThat(MailNotificationComposer.compose(m, sup(), one, KpOutcome.NO_PRICE, KZ).text()).contains("\nЦену не распознали — введите вручную\n");
        assertThat(MailNotificationComposer.compose(m, sup(), kp("RESPONDED", new BigDecimal("10"), new KpSnapshot.LotLine("А", 1)),
                KpOutcome.PRICE_SET, KZ).text()).contains("\nЦена в АИС уже введена: 10,00 ₸\n");
        assertThat(MailNotificationComposer.compose(m, sup(), kp("ACCEPTED", null), KpOutcome.UNCHANGED, KZ).text())
                .contains("\nПовторное письмо — статус «Принят» не меняли\n");
        KpSnapshot three = kp("RESPONDED", null, new KpSnapshot.LotLine("А", 1), new KpSnapshot.LotLine("Б", 2),
                new KpSnapshot.LotLine("В", null));
        assertThat(MailNotificationComposer.compose(m, sup(), three, KpOutcome.MULTI_LOT, KZ).text())
                .contains("\nЛотов: 3\nЛотов несколько — цены вручную\n");
    }

    @Test
    void twoLots_listed() {
        KpSnapshot two = kp("RESPONDED", null, new KpSnapshot.LotLine("А", 1), new KpSnapshot.LotLine("Б", null));
        assertThat(MailNotificationComposer.compose(mail().build(), sup(), two, KpOutcome.MULTI_LOT, KZ).text())
                .contains("\nЛоты: А — 1 шт.; Б\n");
    }

    @Test
    void notFound_headerFromSender_linkToInbound() {
        String t = MailNotificationComposer.compose(mail().from("x@y.kz").build(),
                new Classification(MailClass.SUPPLIER_RESPONSE, 999L), null, KpOutcome.NOT_FOUND, KZ).text();
        assertThat(t).startsWith("📩 Ответ поставщика · x@y.kz\nЗапрос КП №999 в АИС не найден\n")
                .endsWith("Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ");
    }

    @Test
    void privateRequest_labelAndLink() {
        KpSnapshot pr = new KpSnapshot(5, "Дистр", null, 42, "ЧЗ-2026-0007", true, List.of(), "SENT", null);
        String t = MailNotificationComposer.compose(mail().build(), sup(), pr, KpOutcome.NO_PRICE, KZ).text();
        assertThat(t).contains("Запрос КП №5 · частная заявка ЧЗ-2026-0007")
                .endsWith("https://ais.westmed.kz/private-requests?openId=42&market=KZ");
    }

    @Test
    void autoReply_silent_shortExcerpt() {
        ParsedMail m = mail().subject("Автоответ").text("Я в отпуске до 12.10. " + "очень ".repeat(100)).build();
        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.AUTO_REPLY, 534L),
                kp("SENT", null), null, KZ);
        assertThat(n.silent()).isTrue();
        assertThat(n.text()).startsWith("🤖 Автоответ · ТОО «Медтехника»\nНа запрос КП №534 — статус запроса не меняли\n\nЯ в отпуске");
        String excerpt = n.text().split("\n\n")[1];
        assertThat(excerpt.length()).isLessThanOrEqualTo(MailNotificationComposer.AUTO_EXCERPT);
        assertThat(excerpt).endsWith("…");
    }

    @Test
    void other_silent_headerWithMailbox_attachmentsCapped() {
        ParsedMail m = mail().from("Иван <ivan@x.kz>").subject("Прайс октябрь")
                .attachments("1.pdf", "2.pdf", "3.pdf", "4.pdf", "5.pdf", "6.pdf", "7.pdf").text("Высылаем прайс").build();
        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.UNMATCHED, null), null, null, KZ);
        assertThat(n.silent()).isTrue();
        assertThat(n.text()).isEqualTo("""
                ✉️ Письмо на zakup@westmed.kz · Иван <ivan@x.kz>
                Тема: Прайс октябрь
                Вложения: 1.pdf, 2.pdf, 3.pdf, 4.pdf, 5.pdf и ещё 2

                Высылаем прайс

                Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ""");
    }

    @Test
    void broken_silent() {
        MailNotification n = MailNotificationComposer.composeBroken(
                new BrokenMail(3, null, null, null, QUEUED, "ParseException"), KZ);
        assertThat(n.silent()).isTrue();
        assertThat(n.text()).isEqualTo("""
                ✉️ Письмо на zakup@westmed.kz · отправитель не прочитан
                Тема: (тема не прочитана)
                Письмо не удалось разобрать — откройте его в почте Mail.ru.

                Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ""");
    }

    @Test
    void delayedLetter_receivedLineInMarketZone() {
        ParsedMail m = mail().receivedAt(OffsetDateTime.parse("2026-10-03T09:20:00Z")).build();
        String t = MailNotificationComposer.compose(m, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(t).contains("\nПолучено: 03.10 14:20\n");     // Asia/Oral = UTC+5
        String fresh = MailNotificationComposer.compose(mail().build(), new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(fresh).doesNotContain("Получено:");
    }

    @Test
    void noPublicUrl_noLink_rfMarketCurrency() {
        ComposeContext rf = new ComposeContext("zakup@westmed.kz", Market.RF, "", QUEUED);
        String t = MailNotificationComposer.compose(mail().build(), sup(),
                kp("RESPONDED", new BigDecimal("100"), new KpSnapshot.LotLine("А", 1)), KpOutcome.PRICE_PARSED, rf).text();
        assertThat(t).doesNotContain("Открыть в АИС").contains("100,00 ₽");
    }

    @Test
    void hugeBody_cappedTotal_andHtmlWithoutCss() {
        ParsedMail big = mail().text("слово ".repeat(5000)).build();
        String t = MailNotificationComposer.compose(big, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(t.length()).isLessThanOrEqualTo(MailNotificationComposer.MAX_TEXT);
        assertThat(t).contains("…");

        ParsedMail html = mail().html("<style>.x{color:red}</style><p>Цена 7 000 тг</p><blockquote>наше письмо</blockquote>").build();
        String h = MailNotificationComposer.compose(html, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(h).contains("Цена 7 000 тг").doesNotContain("color").doesNotContain("наше письмо");
    }
}
```

- [ ] **Step 2: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailNotificationComposerTest'`
Expected: FAIL компиляции.

- [ ] **Step 3: Реализация**

`service/mail/KpOutcome.java`:

```java
package com.vladoose.nir.service.mail;

/** Что разбор письма сделал с запросом КП (спека §4.1) — читает текст уведомления. */
public enum KpOutcome {
    /** Цена распознана из письма и записана (одно-лотовый запрос). */
    PRICE_PARSED,
    /** Цена уже была введена вручную — не трогали. */
    PRICE_SET,
    DECLINED,
    /** Ответ без распознанной цены — «Ответ получен», цену вводят вручную. */
    NO_PRICE,
    /** Запрос на несколько лотов — авторазбор цены не делается. */
    MULTI_LOT,
    /** Статус запроса не CREATED/SENT — не меняли. */
    UNCHANGED,
    /** Метка есть, запроса в рынке нет. */
    NOT_FOUND
}
```

`service/mail/KpSnapshot.java`:

```java
package com.vladoose.nir.service.mail;

import java.math.BigDecimal;
import java.util.List;

/** Запрос КП для текста уведомления — без JPA: тексты собирает чистая функция. price — цена единственного лота или null. */
public record KpSnapshot(long id, String supplierName, String supplierEmail, long tenderId, String tenderNumber,
                         boolean privateRequest, List<LotLine> lots, String status, BigDecimal price) {

    public record LotLine(String name, Integer quantity) {}
}
```

`service/mail/ComposeContext.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;

import java.time.OffsetDateTime;

/** Ящик, рынок (валюта, часовой пояс, ?market= в ссылке), публичный адрес АИС ("" — без ссылки), время постановки в очередь. */
public record ComposeContext(String mailbox, Market market, String publicUrl, OffsetDateTime queuedAt) {}
```

`service/mail/BrokenMail.java`:

```java
package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;

/** Письмо, которое не разобралось или не записалось: что удалось прочитать (null — не прочитано) и класс ошибки. */
public record BrokenMail(long uid, String messageId, String from, String subject, OffsetDateTime receivedAt, String errorClass) {}
```

`service/mail/MailNotification.java`:

```java
package com.vladoose.nir.service.mail;

/** Готовое уведомление: обычный текст и «без звука». */
public record MailNotification(String text, boolean silent) {}
```

`service/mail/MailNotificationComposer.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.util.DocFormat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Текст уведомления о письме в Telegram (спека §5.2). Чистая функция: собирается при записи письма, когда известно,
 * что сделал разбор (цена, отказ, повтор). Обычный текст без parse_mode — экранировать нечего.
 * Звук: ответ поставщика и «не доставлено» — со звуком, автоответ и прочее — без.
 */
public final class MailNotificationComposer {

    static final int MAX_TEXT = 3500;         // предел Telegram — 4096
    static final int EXCERPT = 700;
    static final int AUTO_EXCERPT = 300;
    static final Duration DELAYED = Duration.ofMinutes(15);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Map<String, String> STATUS = Map.of(
            "CREATED", "Создан", "SENT", "Отправлен", "RESPONDED", "Ответ получен", "ACCEPTED", "Принят",
            "REJECTED", "Отклонён", "DECLINED", "Отказ", "CLOSED", "Закрыт");

    private MailNotificationComposer() {}

    public static MailNotification compose(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome, ComposeContext ctx) {
        return switch (c.mailClass()) {
            case SUPPLIER_RESPONSE -> supplierResponse(m, c, kp, outcome, ctx);
            case BOUNCE -> bounce(m, kp, ctx);
            case AUTO_REPLY -> autoReply(m, kp, ctx);
            default -> other(m, ctx);
        };
    }

    public static MailNotification composeBroken(BrokenMail b, ComposeContext ctx) {
        StringBuilder head = new StringBuilder();
        head.append("✉️ Письмо на ").append(ctx.mailbox()).append(" · ")
                .append(cut(blankTo(b.from(), "отправитель не прочитан"), 200)).append('\n');
        head.append("Тема: ").append(cut(blankTo(b.subject(), "(тема не прочитана)"), 300)).append('\n');
        delayed(head, b.receivedAt(), ctx);
        head.append("Письмо не удалось разобрать — откройте его в почте Mail.ru.\n");
        return new MailNotification(finish(head, "", 0, link(ctx, "/inbound")), true);
    }

    private static MailNotification supplierResponse(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome,
                                                     ComposeContext ctx) {
        StringBuilder head = new StringBuilder();
        head.append("📩 Ответ поставщика · ").append(kp != null ? kp.supplierName() : cut(m.from(), 200)).append('\n');
        if (kp != null) {
            head.append("Запрос КП №").append(kp.id()).append(" · ").append(tenderLabel(kp)).append('\n');
            lots(head, kp.lots());
        }
        String line = outcomeLine(outcome, kp, c.kpId(), ctx.market());
        if (!line.isEmpty()) head.append(line).append('\n');
        sender(head, m);
        delayed(head, m.receivedAt(), ctx);
        String link = kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound");
        return new MailNotification(finish(head, MailText.replyText(m), EXCERPT, link), false);
    }

    private static MailNotification bounce(ParsedMail m, KpSnapshot kp, ComposeContext ctx) {
        ParsedMail.Bounce b = m.bounce();
        String recipient = b != null && b.finalRecipient() != null ? b.finalRecipient() : (kp != null ? kp.supplierEmail() : null);
        StringBuilder head = new StringBuilder("⚠️ Письмо не доставлено · ");
        if (kp != null) {
            head.append(kp.supplierName());
            if (recipient != null && !recipient.isBlank()) head.append(" (").append(recipient).append(')');
            head.append('\n').append("Запрос КП №").append(kp.id()).append(" · ").append(tenderLabel(kp)).append('\n');
        } else {
            head.append(blankTo(recipient, "адресат не указан")).append('\n');
            head.append("Тема возврата: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        }
        String reason = b == null ? null : (b.diagnostic() != null && !b.diagnostic().isBlank() ? b.diagnostic() : b.status());
        if (reason != null && !reason.isBlank()) head.append("Причина: ").append(cut(reason, 200)).append('\n');
        if (kp != null) {
            head.append("Исправьте адрес в карточке поставщика и нажмите «Переслать» в запросах КП ")
                    .append(kp.privateRequest() ? "заявки" : "тендера").append(".\n");
        }
        delayed(head, m.receivedAt(), ctx);
        return new MailNotification(finish(head, "", 0, kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound")), false);
    }

    private static MailNotification autoReply(ParsedMail m, KpSnapshot kp, ComposeContext ctx) {
        StringBuilder head = new StringBuilder("🤖 Автоответ · ")
                .append(kp != null ? kp.supplierName() : cut(blankTo(m.from(), "отправитель не указан"), 200)).append('\n');
        if (kp != null) {
            head.append("На запрос КП №").append(kp.id()).append(" — статус запроса не меняли\n");
        } else {
            head.append("Тема: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        }
        delayed(head, m.receivedAt(), ctx);
        String link = kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound");
        return new MailNotification(finish(head, MailText.replyText(m), AUTO_EXCERPT, link), true);
    }

    private static MailNotification other(ParsedMail m, ComposeContext ctx) {
        StringBuilder head = new StringBuilder("✉️ Письмо на ").append(ctx.mailbox()).append(" · ")
                .append(cut(blankTo(m.from(), "отправитель не указан"), 200)).append('\n');
        head.append("Тема: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        if (!m.attachmentNames().isEmpty()) head.append("Вложения: ").append(attachments(m.attachmentNames())).append('\n');
        delayed(head, m.receivedAt(), ctx);
        return new MailNotification(finish(head, MailText.replyText(m), EXCERPT, link(ctx, "/inbound")), true);
    }

    static String outcomeLine(KpOutcome o, KpSnapshot kp, Long kpId, Market market) {
        if (o == null) return "";
        return switch (o) {
            case PRICE_PARSED -> "💡 Цена распознана: " + money(kp, market) + " — проверьте";
            case PRICE_SET -> "Цена в АИС уже введена: " + money(kp, market);
            case DECLINED -> "⛔ Поставщик отказался";
            case NO_PRICE -> "Цену не распознали — введите вручную";
            case MULTI_LOT -> "Лотов несколько — цены вручную";
            case UNCHANGED -> "Повторное письмо — статус «" + STATUS.getOrDefault(kp.status(), kp.status()) + "» не меняли";
            case NOT_FOUND -> "Запрос КП №" + kpId + " в АИС не найден";
        };
    }

    private static String money(KpSnapshot kp, Market market) {
        return DocFormat.money(kp.price()) + " " + market.currencySymbol();
    }

    private static void sender(StringBuilder head, ParsedMail m) {
        head.append("От: ").append(cut(blankTo(m.from(), "не указан"), 200)).append('\n');
        head.append("Тема: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        if (!m.attachmentNames().isEmpty()) head.append("Вложения: ").append(attachments(m.attachmentNames())).append('\n');
    }

    private static void lots(StringBuilder head, List<KpSnapshot.LotLine> lots) {
        if (lots.isEmpty()) return;
        if (lots.size() > 2) {
            head.append("Лотов: ").append(lots.size()).append('\n');
            return;
        }
        head.append(lots.size() == 1 ? "Лот: " : "Лоты: ");
        for (int i = 0; i < lots.size(); i++) {
            KpSnapshot.LotLine l = lots.get(i);
            if (i > 0) head.append("; ");
            head.append(cut(blankTo(l.name(), "без наименования"), 80));
            if (l.quantity() != null) head.append(" — ").append(l.quantity()).append(" шт.");
        }
        head.append('\n');
    }

    private static String tenderLabel(KpSnapshot kp) {
        return (kp.privateRequest() ? "частная заявка " : "тендер ") + blankTo(kp.tenderNumber(), "без номера");
    }

    private static String attachments(List<String> names) {
        List<String> shown = names.stream().limit(5).map(n -> cut(n, 100)).toList();
        return String.join(", ", shown) + (names.size() > 5 ? " и ещё " + (names.size() - 5) : "");
    }

    /** Пришло заметно раньше записи (догонка после простоя) — время получения по часовому поясу рынка. */
    private static void delayed(StringBuilder head, OffsetDateTime receivedAt, ComposeContext ctx) {
        if (receivedAt == null || ctx.queuedAt() == null) return;
        if (Duration.between(receivedAt, ctx.queuedAt()).compareTo(DELAYED) > 0) {
            head.append("Получено: ").append(receivedAt.atZoneSameInstant(zone(ctx.market())).format(WHEN)).append('\n');
        }
    }

    private static ZoneId zone(Market market) {
        return market == Market.KZ ? ZoneId.of("Asia/Oral") : ZoneId.of("Europe/Samara");
    }

    private static String kpLink(KpSnapshot kp, ComposeContext ctx) {
        return link(ctx, (kp.privateRequest() ? "/private-requests" : "/tenders") + "?openId=" + kp.tenderId());
    }

    /** Ссылка с рынком (?market=): браузер, открытый впервые, стартует на РФ. Пустой publicUrl — без ссылки. */
    private static String link(ComposeContext ctx, String path) {
        if (ctx.publicUrl() == null || ctx.publicUrl().isBlank()) return null;
        String base = ctx.publicUrl().trim().replaceAll("/+$", "");
        return base + path + (path.contains("?") ? "&" : "?") + "market=" + ctx.market().name();
    }

    /** Шапка + отрывок (влезает в остаток предела) + ссылка. */
    private static String finish(StringBuilder head, String body, int max, String link) {
        String tail = link == null ? "" : "\nОткрыть в АИС: " + link;
        int room = MAX_TEXT - head.length() - tail.length() - 2;
        String ex = body == null || body.isBlank() || max <= 0 || room < 20 ? "" : MailText.excerpt(body, Math.min(max, room));
        StringBuilder out = new StringBuilder(head.toString().stripTrailing());
        if (!ex.isEmpty()) out.append("\n\n").append(ex);
        if (!tail.isEmpty()) out.append('\n').append(tail);
        String s = out.toString();
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT - 1) + "…";
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
```

- [ ] **Step 4: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailNotificationComposerTest'`
Expected: PASS (13).
Расхождение в «золотых» строках правится в коде, а не в ожидании. Исключение — если строка противоречит спеке §5.2; тогда сообщить контроллеру.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/KpOutcome.java src/main/java/com/vladoose/nir/service/mail/KpSnapshot.java \
  src/main/java/com/vladoose/nir/service/mail/ComposeContext.java src/main/java/com/vladoose/nir/service/mail/BrokenMail.java \
  src/main/java/com/vladoose/nir/service/mail/MailNotification.java src/main/java/com/vladoose/nir/service/mail/MailNotificationComposer.java \
  src/test/java/com/vladoose/nir/service/mail/MailNotificationComposerTest.java
git commit -m "feat(mail): текст уведомления о письме — ответ поставщика, возврат, автоответ, прочее

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Клиент Telegram — `TelegramSettings`, `TelegramClient`

**Files:**
- Create: `integration/telegram/TelegramSettings.java`, `integration/telegram/TelegramClient.java`, `integration/telegram/TelegramException.java`
- Test: `src/test/java/com/vladoose/nir/integration/telegram/TelegramStubServer.java` (помощник, public), `TelegramClientTest.java`

**Interfaces:**
- Consumes: `GatewayHttp.exchange(HttpClient, HttpRequest, BodyHandler, Duration, String gateway, String what)` и `GatewayException` из `integration/whatsapp` (есть).
- Produces:
  - `TelegramSettings(boolean enabled, String apiUrl, String botToken, String chatId, String mailThreadId)` — public-конструктор, он же для Spring через `@Value`; `isConfigured()`, `enabled()`, `apiUrl()`, `chatId()`, `mailThreadId()`; пакетный `botToken()`;
  - `TelegramClient(TelegramSettings, ObjectMapper)`; `long sendMail(String text, boolean silent)` → `message_id`;
  - `TelegramException(int status, String message, Integer retryAfterSeconds)` с `status()`, `retryAfterSeconds()`;
  - тестовый `TelegramStubServer` (public): `start(int port)` (0 — любой свободный), `url()`, `enqueue(Reply...)`, `requests(): List<Request>`, `close()`; `Request(String path, String body)`; `Reply.ok(long)`, `Reply.error(int, String)`, `Reply.tooMany(int)`, `Reply.hang(long ms)`.

- [ ] **Step 1: Заглушка Bot API для тестов**

`src/test/java/com/vladoose/nir/integration/telegram/TelegramStubServer.java`:

```java
package com.vladoose.nir.integration.telegram;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Заглушка Bot API на JDK HttpServer: записывает запросы, отвечает заданными ответами по очереди (по умолчанию — 200 ok). */
public final class TelegramStubServer implements AutoCloseable {

    public record Request(String path, String body) {}

    public record Reply(int status, String body, long delayMs) {
        public static Reply ok(long messageId) {
            return new Reply(200, "{\"ok\":true,\"result\":{\"message_id\":" + messageId + "}}", 0);
        }
        public static Reply error(int status, String description) {
            return new Reply(status, "{\"ok\":false,\"error_code\":" + status + ",\"description\":\"" + description + "\"}", 0);
        }
        public static Reply tooMany(int retryAfter) {
            return new Reply(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after "
                    + retryAfter + "\",\"parameters\":{\"retry_after\":" + retryAfter + "}}", 0);
        }
        /** Заголовки уходят сразу, тело — через delayMs: проверка дедлайна на ВЕСЬ ответ. */
        public static Reply hang(long delayMs) {
            return new Reply(200, "{\"ok\":true,\"result\":{\"message_id\":1}}", delayMs);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final AtomicLong nextId = new AtomicLong(100);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TelegramStubServer(HttpServer server) { this.server = server; }

    public static TelegramStubServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        TelegramStubServer stub = new TelegramStubServer(s);
        s.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            stub.requests.add(new Request(ex.getRequestURI().getPath(), body));
            Reply r = stub.replies.poll();
            if (r == null) r = Reply.ok(stub.nextId.getAndIncrement());
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (r.delayMs() > 0) {
                ex.sendResponseHeaders(r.status(), 0);          // chunked: заголовки ушли, тело — позже
                OutputStream os = ex.getResponseBody();
                os.flush();
                try { Thread.sleep(r.delayMs()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                try { os.write(bytes); os.close(); } catch (IOException ignored) { }
                return;
            }
            ex.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        s.start();
        return stub;
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public int port() { return server.getAddress().getPort(); }
    public void enqueue(Reply... r) { replies.addAll(List.of(r)); }
    public List<Request> requests() { return requests; }

    /** Повторное закрытие — ничего не делает: тест может закрыть заглушку сам, а потом её закроет @AfterEach. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) server.stop(0);
    }
}
```

- [ ] **Step 2: Падающие тесты**

`src/test/java/com/vladoose/nir/integration/telegram/TelegramClientTest.java`:

```java
package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramClientTest {

    static final String TOKEN = "123456:SECRET-TOKEN";
    final ObjectMapper json = new ObjectMapper();
    TelegramStubServer stub;

    @BeforeEach
    void up() throws Exception { stub = TelegramStubServer.start(0); }

    @AfterEach
    void down() { stub.close(); }

    TelegramClient client(String thread) {
        return new TelegramClient(new TelegramSettings(true, stub.url(), TOKEN, "-1001", thread), json);
    }

    @Test
    void sendsJsonToMailThread_returnsMessageId() throws Exception {
        stub.enqueue(TelegramStubServer.Reply.ok(555));

        long id = client("77").sendMail("Привет", true);

        assertThat(id).isEqualTo(555);
        TelegramStubServer.Request r = stub.requests().get(0);
        assertThat(r.path()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        JsonNode b = json.readTree(r.body());
        assertThat(b.path("chat_id").asText()).isEqualTo("-1001");
        assertThat(b.path("message_thread_id").isNumber()).isTrue();
        assertThat(b.path("message_thread_id").asLong()).isEqualTo(77);
        assertThat(b.path("text").asText()).isEqualTo("Привет");
        assertThat(b.path("disable_notification").asBoolean()).isTrue();
        assertThat(b.path("link_preview_options").path("is_disabled").asBoolean()).isTrue();
    }

    @Test
    void loud_withoutThread_omitsOptionalFields() throws Exception {
        client("").sendMail("Текст", false);
        JsonNode b = json.readTree(stub.requests().get(0).body());
        assertThat(b.has("disable_notification")).isFalse();
        assertThat(b.has("message_thread_id")).isFalse();
    }

    @Test
    void rateLimited_carriesRetryAfter() {
        stub.enqueue(TelegramStubServer.Reply.tooMany(7));
        assertThatThrownBy(() -> client("77").sendMail("x", false))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(7);
                });
    }

    @Test
    void badRequest_messageHasDescription_noToken() {
        stub.enqueue(TelegramStubServer.Reply.error(400, "Bad Request: message thread not found"));
        assertThatThrownBy(() -> client("77").sendMail("x", false))
                .isInstanceOfSatisfying(TelegramException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("message thread not found").doesNotContain(TOKEN).doesNotContain("SECRET");
                });
    }

    @Test
    void serverErrorNonJson_noToken() {
        stub.enqueue(new TelegramStubServer.Reply(502, "<html>Bad Gateway</html>", 0));
        assertThatThrownBy(() -> client("77").sendMail("x", false))
                .hasMessageContaining("HTTP 502").hasMessageNotContaining("SECRET");
    }

    @Test
    void connectionRefused_noTokenNoUrl() {
        int port = stub.port();
        stub.close();
        TelegramClient c = new TelegramClient(new TelegramSettings(true, "http://127.0.0.1:" + port, TOKEN, "-1001", "77"), json);
        assertThatThrownBy(() -> c.sendMail("x", false))
                .isInstanceOf(TelegramException.class)
                .hasMessageNotContaining("SECRET").hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void hangingBody_failsWithinDeadline() {
        stub.enqueue(TelegramStubServer.Reply.hang(3000));
        TelegramClient c = new TelegramClient(new TelegramSettings(true, stub.url(), TOKEN, "-1001", "77"), json,
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), Duration.ofMillis(500));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> c.sendMail("x", false)).isInstanceOf(TelegramException.class).hasMessageNotContaining("SECRET");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void brokenApiUrl_ownText_noToken() {
        TelegramClient c = new TelegramClient(new TelegramSettings(true, "http://bad host", TOKEN, "-1001", "77"), json);
        assertThatThrownBy(() -> c.sendMail("x", false))
                .isInstanceOf(TelegramException.class).hasMessageNotContaining("SECRET").hasMessageNotContaining("bad host");
    }

    @Test
    void nonNumericThread_ownText() {
        assertThatThrownBy(() -> client("abc").sendMail("x", false))
                .isInstanceOf(TelegramException.class).hasMessageContaining("TELEGRAM_MAIL_THREAD_ID");
    }

    @Test
    void settings_toStringHidesToken_configuredRules() {
        TelegramSettings s = new TelegramSettings(true, "https://api.telegram.org", TOKEN, "-1001", "77");
        assertThat(s.toString()).doesNotContain("SECRET").contains("-1001");
        assertThat(s.isConfigured()).isTrue();
        assertThat(new TelegramSettings(false, "x", TOKEN, "-1001", "").isConfigured()).isFalse();
        assertThat(new TelegramSettings(true, "x", "", "-1001", "").isConfigured()).isFalse();
        assertThat(new TelegramSettings(true, "x", TOKEN, " ", "").isConfigured()).isFalse();
    }
}
```

- [ ] **Step 3: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.telegram.TelegramClientTest'`
Expected: FAIL компиляции.

- [ ] **Step 4: Реализация**

`integration/telegram/TelegramException.java`:

```java
package com.vladoose.nir.integration.telegram;

/**
 * Отказ Telegram или сети. Текст — свой, БЕЗ адреса запроса: токен бота стоит прямо в пути. status 0 — сеть или
 * настройки; retryAfterSeconds — пауза из ответа 429.
 */
public class TelegramException extends RuntimeException {

    private final int status;
    private final Integer retryAfterSeconds;

    public TelegramException(int status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
```

`integration/telegram/TelegramSettings.java`:

```java
package com.vladoose.nir.integration.telegram;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Настройки Bot API (`notify.telegram.*`): бот @West_Med_bot сайта westmed.kz, группа «Заявки», тема «Почта zakup@».
 * Токен наружу не отдаётся: только пакетный {@link #botToken()} для клиента, toString — без него.
 */
@Component
public class TelegramSettings {

    private static final Logger log = LoggerFactory.getLogger(TelegramSettings.class);

    private final boolean enabled;
    private final String apiUrl;
    private final String botToken;
    private final String chatId;
    private final String mailThreadId;

    public TelegramSettings(@Value("${notify.telegram.enabled:false}") boolean enabled,
                            @Value("${notify.telegram.api-url:https://api.telegram.org}") String apiUrl,
                            @Value("${notify.telegram.bot-token:}") String botToken,
                            @Value("${notify.telegram.chat-id:}") String chatId,
                            @Value("${notify.telegram.mail-thread-id:}") String mailThreadId) {
        this.enabled = enabled;
        this.apiUrl = trim(apiUrl);
        this.botToken = trim(botToken);
        this.chatId = trim(chatId);
        this.mailThreadId = trim(mailThreadId);
    }

    @PostConstruct
    void warnIfIncomplete() {
        if (enabled && !isConfigured()) {
            log.warn("Telegram включён (TELEGRAM_ENABLED=true), но не настроен: нужны TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID — "
                    + "уведомления о письмах в очередь не ставятся");
        }
    }

    /** Включён и есть токен и чат — только тогда уведомления ставятся в очередь. */
    public boolean isConfigured() { return enabled && !botToken.isEmpty() && !chatId.isEmpty(); }

    public boolean enabled() { return enabled; }
    public String apiUrl() { return apiUrl; }
    public String chatId() { return chatId; }
    public String mailThreadId() { return mailThreadId; }

    String botToken() { return botToken; }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    @Override
    public String toString() {
        return "TelegramSettings{enabled=" + enabled + ", chatId=" + chatId + ", mailThreadId=" + mailThreadId
                + ", botToken=" + (botToken.isEmpty() ? "—" : "***") + "}";
    }
}
```

`integration/telegram/TelegramClient.java`:

```java
package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.GatewayHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bot API: sendMessage в тему почты. Обмен — с дедлайном на ВЕСЬ ответ (GatewayHttp: таймаут HttpRequest в JDK 17
 * снимается после заголовков). Адрес запроса содержит токен — он не попадает ни в одно сообщение об ошибке.
 */
@Component
public class TelegramClient {

    static final Duration DEADLINE = Duration.ofSeconds(15);

    private final TelegramSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration deadline;

    @Autowired
    public TelegramClient(TelegramSettings settings, ObjectMapper json) {
        this(settings, json, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build(), DEADLINE);
    }

    TelegramClient(TelegramSettings settings, ObjectMapper json, HttpClient http, Duration deadline) {
        this.settings = settings;
        this.json = json;
        this.http = http;
        this.deadline = deadline;
    }

    /** Сообщение в тему почты (`TELEGRAM_MAIL_THREAD_ID`; пусто — «Общая»). Возвращает message_id. */
    public long sendMail(String text, boolean silent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", settings.chatId());
        String thread = settings.mailThreadId();
        if (!thread.isEmpty()) {
            try {
                body.put("message_thread_id", Long.parseLong(thread));
            } catch (NumberFormatException e) {
                throw new TelegramException(0, "Telegram: TELEGRAM_MAIL_THREAD_ID должен быть числом (id темы группы)", null);
            }
        }
        body.put("text", text);
        if (silent) body.put("disable_notification", true);
        body.put("link_preview_options", Map.of("is_disabled", true));

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(settings.apiUrl().replaceAll("/+$", "")
                            + "/bot" + settings.botToken() + "/sendMessage"))
                    .timeout(deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException | JsonProcessingException e) {   // текст IAE содержит адрес — наружу не отдаём
            throw new TelegramException(0, "Telegram: адрес API или токен в настройках некорректны", null);
        }

        HttpResponse<String> resp;
        try {
            resp = GatewayHttp.exchange(http, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    deadline, "Telegram", "отправке сообщения");
        } catch (GatewayException e) {
            throw new TelegramException(0, e.getMessage(), null);
        }

        JsonNode node = parse(resp.body());
        if (resp.statusCode() == 200 && node != null && node.path("ok").asBoolean(false)) {
            return node.path("result").path("message_id").asLong(0);
        }
        Integer retryAfter = node != null && node.path("parameters").has("retry_after")
                ? node.path("parameters").path("retry_after").asInt() : null;
        String desc = node == null ? "" : node.path("description").asText("");
        throw new TelegramException(resp.statusCode(), "Telegram: HTTP " + resp.statusCode()
                + (desc.isBlank() ? "" : " — " + (desc.length() > 200 ? desc.substring(0, 200) : desc)), retryAfter);
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : json.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.telegram.TelegramClientTest'`
Expected: PASS (10).
Если `connectionRefused_noTokenNoUrl` поймает адрес в тексте, посмотреть на `GatewayHttp`: он отдаёт класс исключения, адреса там нет. Значит, текст собран не там — исправить в клиенте.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/integration/telegram/ src/test/java/com/vladoose/nir/integration/telegram/
git commit -m "feat(mail): клиент Bot API — sendMessage в тему, 429 с retry_after, токен не в текстах ошибок

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Запись письма — `MailIngestWriter`

**Files:**
- Create: `service/mail/MailIngestWriter.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java`

**Interfaces:**
- Consumes: всё из задач 1–5. Перенос логики `MailReceiveService.matchSupplierResponse` (цена одно-лотового запроса, отказ, RESPONDED) — без изменения поведения.
- Produces:
  - `MailIngestWriter(InboundEmailRepository, PriceRequestRepository, MailCursorRepository, TelegramSettings, String mailbox, String sendFrom, String siteNotificationFrom, boolean clientRequests, String publicUrl)` — public, параметры-строки через `@Value` (`mail.imap.username`, `spring.mail.username`, `leads.westmed.notification-from`, `mail.imap.client-requests`, `ais.public-url`);
  - `String mailbox()` — адрес ящика нижним регистром;
  - `@Transactional WriteResult write(ParsedMail m, long uidValidity)`;
  - `@Transactional void writeBroken(BrokenMail b, long uidValidity)`;
  - `@Transactional void moveCursorTo(long uidValidity, long uid)`;
  - `record WriteResult(MailClass mailClass, KpOutcome outcome, Long inboundId, boolean duplicate)` — у дубля `mailClass == null`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.util.KpToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MailIngestWriterTest {

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired PriceRequestRepository prRepo;
    @Autowired MailCursorRepository cursorRepo;
    @Autowired TenderRepository tenderRepo;
    @Autowired DistributorRepository distributorRepo;

    static final TelegramSettings TG_ON = new TelegramSettings(true, "http://127.0.0.1:1", "123:SECRET", "-1001", "77");
    static final TelegramSettings TG_OFF = new TelegramSettings(false, "", "", "", "");

    final String box = "zz-" + System.nanoTime() + "@test.kz";

    MailIngestWriter writer(TelegramSettings tg, boolean clientRequests) {
        return new MailIngestWriter(inboundRepo, prRepo, cursorRepo, tg, box, "zakup@westmed.kz", "info@westmed.kz",
                clientRequests, "https://ais.example");
    }

    MailIngestWriter writer() { return writer(TG_ON, false); }

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Запрос КП: lots — число лотов (0 — без строк), source — тендер или частная заявка. */
    PriceRequest pr(String status, int lots, Source source) {
        Tender t = Tender.builder().tenderNumber("ZZW-" + System.nanoTime()).status("NEW").source(source).build();
        for (int i = 0; i < lots; i++) {
            t.getLots().add(TenderLot.builder().tender(t).equipName("Аппарат " + (i + 1)).quantity(1).build());
        }
        t = tenderRepo.save(t);
        Distributor d = distributorRepo.save(Distributor.builder().name("ZZW Дистр " + System.nanoTime()).email("sales@zzw.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status(status).build();
        for (TenderLot l : t.getLots()) {
            pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build());
        }
        return prRepo.save(pr);
    }

    List<InboundEmail> rows() {
        return inboundRepo.findAll().stream().filter(e -> box.equals(e.getMailbox())).toList();
    }

    @Test
    void supplierResponse_singleLot_parsesPrice_queuesLoudNotification_movesCursor() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);

        MailIngestWriter.WriteResult r = writer().write(mail().uid(10).subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Цена 3 200 000 ₸, срок 3 недели").build(), 5);

        assertThat(r.mailClass()).isEqualTo(MailClass.SUPPLIER_RESPONSE);
        assertThat(r.outcome()).isEqualTo(KpOutcome.PRICE_PARSED);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("RESPONDED");
        assertThat(back.getItems().get(0).getResponsePrice()).isEqualByComparingTo("3200000");
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.SUPPLIER_RESPONSE);
        assertThat(e.getMatchedPriceRequestId()).isEqualTo(p.getId());
        assertThat(e.getImapUid()).isEqualTo(10L);
        assertThat(e.getMarket()).isEqualTo(Market.KZ);
        assertThat(e.getNotifyStatus()).isEqualTo(NotifyStatus.PENDING);
        assertThat(e.isNotifySilent()).isFalse();
        assertThat(e.getNotifyQueuedAt()).isNotNull();
        assertThat(e.getNotifyText()).startsWith("📩 Ответ поставщика · ZZW Дистр")
                .contains("💡 Цена распознана: 3 200 000,00 ₸")
                .contains("Запрос КП №" + p.getId() + " · тендер ZZW-")
                .endsWith("/tenders?openId=" + p.getTender().getId() + "&market=KZ");
        MailCursor c = cursorRepo.findById(box).orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(5);
        assertThat(c.getLastUid()).isEqualTo(10);
    }

    @Test
    void supplierRefusal_declined() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Добрый день, данную позицию мы не поставляем, к сожалению.").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.DECLINED);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("DECLINED");
        assertThat(rows().get(0).getNotifyText()).contains("⛔ Поставщик отказался");
    }

    @Test
    void multiLot_noAutoPrice() {
        PriceRequest p = pr("SENT", 2, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Цена 100 000 тенге").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.MULTI_LOT);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("RESPONDED");
    }

    @Test
    void alreadyAccepted_unchanged() {
        PriceRequest p = pr("ACCEPTED", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId()))
                .text("Уточнение: цена 5 000 тенге").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.UNCHANGED);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("ACCEPTED");
        assertThat(rows().get(0).getNotifyText()).contains("Повторное письмо — статус «Принят» не меняли");
    }

    @Test
    void tokenOfMissingRequest_notFound() {
        MailIngestWriter.WriteResult r = writer().write(mail().subject("Re: [КП-999999999] Запрос").build(), 5);
        assertThat(r.outcome()).isEqualTo(KpOutcome.NOT_FOUND);
        assertThat(rows().get(0).getMatchedPriceRequestId()).isNull();
        assertThat(rows().get(0).getNotifyText()).contains("Запрос КП №999999999 в АИС не найден");
    }

    @Test
    void bounce_keepsRequestSent_loud() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        MailIngestWriter.WriteResult r = writer().write(mail().from("MAILER-DAEMON@corp.mail.ru")
                .subject("Undelivered Mail Returned to Sender")
                .contentType("multipart/report; report-type=delivery-status; boundary=x")
                .bounce("sales@zzw.kz", "5.1.1", "550 5.1.1 User unknown", KpToken.subjectToken(p.getId()) + " Запрос КП").build(), 5);

        assertThat(r.mailClass()).isEqualTo(MailClass.BOUNCE);
        PriceRequest back = prRepo.findById(p.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("SENT");
        assertThat(back.getResponseDate()).isNull();
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.BOUNCE);
        assertThat(e.getMatchedPriceRequestId()).isEqualTo(p.getId());
        assertThat(e.isNotifySilent()).isFalse();
        assertThat(e.getNotifyText()).startsWith("⚠️ Письмо не доставлено · ZZW Дистр").contains("(sales@zzw.kz)")
                .contains("Причина: 550 5.1.1 User unknown");
    }

    @Test
    void undeliverableSubjectFromPostmaster_isBounce_notResponded() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        writer().write(mail().from("postmaster@medtech.kz").subject("Undeliverable: " + KpToken.subjectToken(p.getId()) + " Запрос")
                .text("Delivery has failed to these recipients").build(), 5);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(rows().get(0).getType()).isEqualTo(InboundType.BOUNCE);
    }

    @Test
    void autoReply_keepsRequestSent_silent() {
        PriceRequest p = pr("SENT", 1, Source.PUBLIC_TENDER);
        writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId())).header("Auto-Submitted", "auto-replied")
                .text("Я в отпуске до 12.10").build(), 5);
        assertThat(prRepo.findById(p.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.AUTO_REPLY);
        assertThat(e.isNotifySilent()).isTrue();
    }

    @Test
    void excelWithoutToken_dependsOnClientRequests() {
        writer(TG_ON, false).write(mail().uid(1).excel("прайс.xlsx").build(), 5);
        InboundEmail priceList = rows().get(0);
        assertThat(priceList.getType()).isEqualTo(InboundType.UNMATCHED);
        assertThat(priceList.getAttachment()).isNull();

        writer(TG_ON, true).write(mail().uid(2).excel("заявка.xlsx").build(), 5);
        InboundEmail request = rows().stream().filter(e -> e.getImapUid() == 2L).findFirst().orElseThrow();
        assertThat(request.getType()).isEqualTo(InboundType.CLIENT_REQUEST);
        assertThat(request.getAttachment()).isNotNull();
        assertThat(request.getAttachmentName()).isEqualTo("заявка.xlsx");
    }

    @Test
    void duplicateMessageId_noSecondRow_cursorMoves() {
        writer().write(mail().uid(1).messageId("<same@x.kz>").build(), 5);
        MailIngestWriter.WriteResult r = writer().write(mail().uid(2).messageId("<same@x.kz>").build(), 5);
        assertThat(r.duplicate()).isTrue();
        assertThat(rows()).hasSize(1);
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(2);
    }

    @Test
    void siteNotification_noRow_cursorMoves() {
        MailIngestWriter.WriteResult r = writer().write(mail().uid(3).from("WestMed.kz <info@westmed.kz>")
                .subject("Запрос КП (2 поз.) — westmed.kz").build(), 5);
        assertThat(r.mailClass()).isEqualTo(MailClass.SITE_NOTIFICATION);
        assertThat(rows()).isEmpty();
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(3);
    }

    @Test
    void ownEcho_rowWithoutNotification() {
        writer().write(mail().from("zakup@westmed.kz").subject("[КП-5] Запрос").build(), 5);
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.UNMATCHED);
        assertThat(e.getNotifyStatus()).isNull();
    }

    @Test
    void telegramOff_noQueue() {
        writer(TG_OFF, false).write(mail().build(), 5);
        assertThat(rows().get(0).getNotifyStatus()).isNull();
        assertThat(rows().get(0).getNotifyText()).isNull();
    }

    @Test
    void writeBroken_shortRowAndSilentNotice() {
        writer().writeBroken(new BrokenMail(9, "<b@x>", "a@b.kz", "Тема", java.time.OffsetDateTime.now(), "ParseException"), 5);
        InboundEmail e = rows().get(0);
        assertThat(e.getType()).isEqualTo(InboundType.UNMATCHED);
        assertThat(e.getExcerpt()).isEqualTo("Письмо не удалось разобрать (ParseException) — откройте его в почте");
        assertThat(e.isNotifySilent()).isTrue();
        assertThat(e.getNotifyText()).contains("Письмо не удалось разобрать");
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(9);
    }

    @Test
    void cursor_neverBackwardsWithinValidity_resetsOnNewValidity() {
        MailIngestWriter w = writer();
        w.moveCursorTo(5, 100);
        w.moveCursorTo(5, 40);
        assertThat(cursorRepo.findById(box).orElseThrow().getLastUid()).isEqualTo(100);
        w.moveCursorTo(6, 3);
        MailCursor c = cursorRepo.findById(box).orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(6);
        assertThat(c.getLastUid()).isEqualTo(3);
    }

    @Test
    void privateRequest_linkToPrivateRequests() {
        PriceRequest p = pr("SENT", 0, Source.PRIVATE_REQUEST);
        writer().write(mail().subject("Re: " + KpToken.subjectToken(p.getId())).text("Получили, ответим завтра").build(), 5);
        assertThat(rows().get(0).getNotifyText()).contains("частная заявка ZZW-")
                .endsWith("/private-requests?openId=" + p.getTender().getId() + "&market=KZ");
    }

    @Test
    void htmlOnly_excerptIsText() {
        writer().write(mail().html("<style>p{color:red}</style><p>Цена 7 000 тг</p>").build(), 5);
        assertThat(rows().get(0).getExcerpt()).isEqualTo("Цена 7 000 тг");
    }
}
```

- [ ] **Step 2: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailIngestWriterTest'`
Expected: FAIL компиляции — нет `MailIngestWriter`.

- [ ] **Step 3: Реализация**

`service/mail/MailIngestWriter.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.util.SupplierReplyDeclineDetector;
import com.vladoose.nir.util.SupplierReplyPriceParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Запись письма (спека §3.3): классификация, правки запроса КП, строка «Входящих» с готовым текстом уведомления и
 * сдвиг курсора — ОДНОЙ транзакцией на письмо. Сеть сюда не заходит: письмо уже разобрано (ParsedMail).
 * Рынок ставит вызывающий (MarketContext) — по нему стампится строка и ищется запрос КП.
 */
@Service
public class MailIngestWriter {

    public record WriteResult(MailClass mailClass, KpOutcome outcome, Long inboundId, boolean duplicate) {}

    private final InboundEmailRepository inboundRepo;
    private final PriceRequestRepository priceRequestRepo;
    private final MailCursorRepository cursorRepo;
    private final TelegramSettings telegram;
    private final String mailbox;
    private final ClassifierRules rules;
    private final String publicUrl;

    public MailIngestWriter(InboundEmailRepository inboundRepo, PriceRequestRepository priceRequestRepo,
                            MailCursorRepository cursorRepo, TelegramSettings telegram,
                            @Value("${mail.imap.username:}") String mailbox,
                            @Value("${spring.mail.username:}") String sendFrom,
                            @Value("${leads.westmed.notification-from:info@westmed.kz}") String siteNotificationFrom,
                            @Value("${mail.imap.client-requests:true}") boolean clientRequests,
                            @Value("${ais.public-url:}") String publicUrl) {
        this.inboundRepo = inboundRepo;
        this.priceRequestRepo = priceRequestRepo;
        this.cursorRepo = cursorRepo;
        this.telegram = telegram;
        this.mailbox = lower(mailbox);
        this.rules = new ClassifierRules(lower(sendFrom), lower(siteNotificationFrom), clientRequests);
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim();
    }

    /** Адрес ящика нижним регистром — ключ курсора. */
    public String mailbox() { return mailbox; }

    @Transactional
    public WriteResult write(ParsedMail m, long uidValidity) {
        String messageId = cut(m.messageId(), 998);
        if (messageId != null && inboundRepo.existsByMailboxAndMessageId(mailbox, messageId)) {
            moveCursor(uidValidity, m.uid());                 // уже записано (сброс курсора, повторная доставка)
            return new WriteResult(null, null, null, true);
        }
        Classification c = MailClassifier.classify(m, rules);
        if (c.mailClass() == MailClass.SITE_NOTIFICATION) {
            moveCursor(uidValidity, m.uid());
            return new WriteResult(c.mailClass(), null, null, false);
        }
        PriceRequest pr = c.kpId() == null ? null : priceRequestRepo.findById(c.kpId()).orElse(null);
        KpOutcome outcome = null;
        if (c.mailClass() == MailClass.SUPPLIER_RESPONSE) {
            outcome = pr == null ? KpOutcome.NOT_FOUND : applySupplierResponse(pr, m.body());
        }
        InboundType type = switch (c.mailClass()) {
            case SUPPLIER_RESPONSE -> InboundType.SUPPLIER_RESPONSE;
            case BOUNCE -> InboundType.BOUNCE;
            case AUTO_REPLY -> InboundType.AUTO_REPLY;
            case CLIENT_REQUEST -> InboundType.CLIENT_REQUEST;
            default -> InboundType.UNMATCHED;
        };
        boolean clientRequest = type == InboundType.CLIENT_REQUEST;
        InboundEmail e = InboundEmail.builder()
                .mailbox(mailbox).imapUid(m.uid()).messageId(messageId)
                .fromAddress(cut(m.from(), 320)).subject(cut(m.subject(), 998)).receivedAt(m.receivedAt())
                .type(type).matchedPriceRequestId(pr == null ? null : pr.getId())
                .attachmentName(clientRequest ? cut(m.excelName(), 255) : null)
                .attachment(clientRequest ? m.excelBytes() : null)
                .excerpt(cut(m.body(), 2000))
                .status(InboundStatus.NEW)
                .build();
        if (c.mailClass() != MailClass.OWN && telegram.isConfigured()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            queue(e, MailNotificationComposer.compose(m, c, pr == null ? null : snapshot(pr), outcome, context(now)), now);
        }
        inboundRepo.save(e);                                   // @PrePersist стампит market из MarketContext
        moveCursor(uidValidity, m.uid());
        return new WriteResult(c.mailClass(), outcome, e.getId(), false);
    }

    /** Письмо, которое не разобралось или не записалось: короткая строка, курсор дальше (спека §3.4). */
    @Transactional
    public void writeBroken(BrokenMail b, long uidValidity) {
        InboundEmail e = InboundEmail.builder()
                .mailbox(mailbox).imapUid(b.uid()).messageId(cut(b.messageId(), 998))
                .fromAddress(cut(b.from(), 320)).subject(cut(b.subject(), 998)).receivedAt(b.receivedAt())
                .type(InboundType.UNMATCHED)
                .excerpt("Письмо не удалось разобрать (" + b.errorClass() + ") — откройте его в почте")
                .status(InboundStatus.NEW)
                .build();
        if (telegram.isConfigured()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            queue(e, MailNotificationComposer.composeBroken(b, context(now)), now);
        }
        inboundRepo.save(e);
        moveCursor(uidValidity, b.uid());
    }

    /** Первый запуск: курсор — на последнее письмо ящика, даже если в окне ничего не было (0 — ящик пуст). */
    @Transactional
    public void moveCursorTo(long uidValidity, long uid) {
        moveCursor(uidValidity, uid);
    }

    /** В пределах одного UIDVALIDITY курсор назад не ходит; новый UIDVALIDITY — отсчёт заново. */
    private void moveCursor(long uidValidity, long uid) {
        MailCursor c = cursorRepo.findById(mailbox).orElse(null);
        if (c == null || c.getUidValidity() != uidValidity) {
            c = MailCursor.builder().mailbox(mailbox).uidValidity(uidValidity).lastUid(uid).build();
        } else if (uid > c.getLastUid()) {
            c.setLastUid(uid);
        }
        c.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        cursorRepo.save(c);
    }

    /**
     * Ответ поставщика (логика прежнего MailReceiveService.matchSupplierResponse без изменений): только из CREATED/SENT;
     * одно-лотовый — авторазбор цены (ручную не затираем); иначе явный отказ → DECLINED; иначе RESPONDED.
     */
    private KpOutcome applySupplierResponse(PriceRequest pr, String body) {
        String st = pr.getStatus();
        if (!"CREATED".equals(st) && !"SENT".equals(st)) return KpOutcome.UNCHANGED;
        pr.setResponseDate(LocalDate.now());
        pr.setNote(cut(body, 4000));
        KpOutcome outcome = null;
        if (pr.getItems().size() == 1) {
            PriceRequestItem item = pr.getItems().get(0);
            if (item.getResponsePrice() != null) {
                outcome = KpOutcome.PRICE_SET;                  // цена уже введена вручную
            } else {
                Optional<SupplierReplyPriceParser.ParsedPrice> pp = SupplierReplyPriceParser.parse(body, pr.getMarket());
                if (pp.isPresent()) {
                    item.setResponsePrice(pp.get().price());
                    item.setResponseNote("💡 Цена распознана автоматически, проверьте."
                            + (pp.get().term() != null ? " Срок: " + pp.get().term() + "." : "")
                            + (pp.get().matchedSnippet() != null ? " Контекст: «" + pp.get().matchedSnippet() + "»." : ""));
                    outcome = KpOutcome.PRICE_PARSED;
                }
            }
        }
        if (outcome != null) {
            pr.setStatus("RESPONDED");
        } else if (SupplierReplyDeclineDetector.isDecline(body)) {
            pr.setStatus("DECLINED");
            outcome = KpOutcome.DECLINED;
        } else {
            pr.setStatus("RESPONDED");
            outcome = pr.getItems().size() > 1 ? KpOutcome.MULTI_LOT : KpOutcome.NO_PRICE;
        }
        priceRequestRepo.save(pr);                              // cascade ALL сохранит правку item
        return outcome;
    }

    static KpSnapshot snapshot(PriceRequest pr) {
        Tender t = pr.getTender();
        Distributor d = pr.getDistributor();
        List<KpSnapshot.LotLine> lots = pr.getItems().stream()
                .map(i -> new KpSnapshot.LotLine(i.getTenderLot() == null ? null : i.getTenderLot().getEquipName(),
                        i.getRequestedQuantity()))
                .toList();
        BigDecimal price = pr.getItems().size() == 1 ? pr.getItems().get(0).getResponsePrice() : null;
        return new KpSnapshot(pr.getId(), d == null ? "поставщик" : d.getName(), d == null ? null : d.getEmail(),
                t.getId(), t.getTenderNumber(), t.getSource() == Source.PRIVATE_REQUEST, lots, pr.getStatus(), price);
    }

    private ComposeContext context(OffsetDateTime now) {
        return new ComposeContext(mailbox, MarketContext.get(), publicUrl, now);
    }

    private static void queue(InboundEmail e, MailNotification n, OffsetDateTime now) {
        e.setNotifyStatus(NotifyStatus.PENDING);
        e.setNotifyText(n.text());
        e.setNotifySilent(n.silent());
        e.setNotifyQueuedAt(now);
    }

    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 4: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailIngestWriterTest'`
Expected: PASS (17).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java src/test/java/com/vladoose/nir/service/mail/MailIngestWriterTest.java
git commit -m "feat(mail): запись письма и курсора одной транзакцией; возврат и автоответ не меняют статус КП

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Очередь Telegram — `MailNotifyStore`, `MailTelegramNotifier`

**Files:**
- Create: `service/mail/PendingNotification.java`, `service/mail/MailNotifyStore.java`, `service/mail/MailTelegramNotifier.java`
- Modify: `repository/InboundEmailRepository.java` (+`findPendingNotifications`, `countByNotifyStatus`)
- Test: `src/test/java/com/vladoose/nir/service/mail/MailTelegramNotifierTest.java`

**Interfaces:**
- Consumes: `TelegramClient`, `TelegramSettings`, `TelegramException`, `TelegramStubServer` (задача 5); `NotifyStatus` и поля `notify_*` (задача 1).
- Produces:
  - `record PendingNotification(Long id, String text, boolean silent, OffsetDateTime queuedAt)`;
  - `MailNotifyStore`: `pending(int limit)`, `countPending()`, `markSent(long, OffsetDateTime)`, `markAttempt(long, String)`, `markFailed(long, String)` — все `@Transactional`;
  - `MailTelegramNotifier(MailNotifyStore, TelegramClient, TelegramSettings)` — public `@Autowired` + пакетный с `Clock`;
  - `FlushResult flush()`, `record FlushResult(int sent, long pending, String lastError)`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/MailTelegramNotifierTest.java`:

```java
package com.vladoose.nir.service.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramStubServer;
import com.vladoose.nir.repository.InboundEmailRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MailTelegramNotifierTest {

    static final String TOKEN = "123456:SECRET-TOKEN";

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired MailNotifyStore store;
    @Autowired EntityManager em;

    TelegramStubServer stub;
    MutableClock clock;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-05T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void up() throws Exception {
        MarketContext.set(Market.KZ);
        // чужие PENDING (живые проверки на этой базе) не должны попасть в пачку теста — откатится вместе с тестом
        em.createNativeQuery("update inbound_email set notify_status = 'SENT' where notify_status = 'PENDING'").executeUpdate();
        stub = TelegramStubServer.start(0);
        clock = new MutableClock();
    }

    @AfterEach
    void down() {
        stub.close();
        MarketContext.clear();
    }

    MailTelegramNotifier notifier(boolean enabled) {
        TelegramSettings s = new TelegramSettings(enabled, stub.url(), TOKEN, "-1001", "77");
        return new MailTelegramNotifier(store, new TelegramClient(s, new ObjectMapper()), s, clock);
    }

    InboundEmail pending(String text, boolean silent, Instant queuedAt) {
        return inboundRepo.save(InboundEmail.builder().fromAddress("a@x.kz").subject("S").type(InboundType.UNMATCHED)
                .status(InboundStatus.NEW).mailbox("zz@test.kz").notifyStatus(NotifyStatus.PENDING)
                .notifyText(text).notifySilent(silent).notifyQueuedAt(queuedAt.atOffset(ZoneOffset.UTC)).build());
    }

    InboundEmail reload(InboundEmail e) {
        em.flush();
        em.clear();
        return inboundRepo.findById(e.getId()).orElseThrow();
    }

    @Test
    void sendsPendingInIdOrder_marksSent() {
        InboundEmail a = pending("первое", true, clock.now);
        InboundEmail b = pending("второе", false, clock.now);

        MailTelegramNotifier.FlushResult r = notifier(true).flush();

        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.pending()).isZero();
        assertThat(stub.requests()).hasSize(2);
        assertThat(stub.requests().get(0).body()).contains("первое").contains("\"disable_notification\":true");
        assertThat(stub.requests().get(1).body()).contains("второе").doesNotContain("disable_notification");
        assertThat(reload(a).getNotifyStatus()).isEqualTo(NotifyStatus.SENT);
        assertThat(reload(b).getNotifiedAt()).isNotNull();
    }

    @Test
    void serverError_stopsBatch_countsAttempt() {
        InboundEmail a = pending("первое", false, clock.now);
        InboundEmail b = pending("второе", false, clock.now);
        stub.enqueue(TelegramStubServer.Reply.error(500, "boom"));

        MailTelegramNotifier.FlushResult r = notifier(true).flush();

        assertThat(r.sent()).isZero();
        assertThat(r.pending()).isEqualTo(2);
        assertThat(r.lastError()).contains("HTTP 500");
        assertThat(stub.requests()).hasSize(1);
        InboundEmail ra = reload(a);
        assertThat(ra.getNotifyAttempts()).isEqualTo(1);
        assertThat(ra.getNotifyError()).contains("HTTP 500");
        assertThat(reload(b).getNotifyAttempts()).isZero();
    }

    @Test
    void rateLimit_pausesUntilRetryAfter() {
        pending("первое", false, clock.now);
        stub.enqueue(TelegramStubServer.Reply.tooMany(30));
        MailTelegramNotifier n = notifier(true);

        n.flush();
        n.flush();                                     // та же секунда — пауза, запросов нет
        assertThat(stub.requests()).hasSize(1);

        clock.now = clock.now.plusSeconds(31);
        MailTelegramNotifier.FlushResult r = n.flush();
        assertThat(r.sent()).isEqualTo(1);
        assertThat(stub.requests()).hasSize(2);
    }

    @Test
    void olderThanDay_failedWithoutRequest() {
        InboundEmail old = pending("старое", false, clock.now.minus(Duration.ofHours(25)));

        notifier(true).flush();

        assertThat(stub.requests()).isEmpty();
        assertThat(reload(old).getNotifyStatus()).isEqualTo(NotifyStatus.FAILED);
    }

    @Test
    void notConfigured_doesNothing() {
        InboundEmail a = pending("x", false, clock.now);
        notifier(false).flush();
        assertThat(stub.requests()).isEmpty();
        assertThat(reload(a).getNotifyStatus()).isEqualTo(NotifyStatus.PENDING);
    }

    @Test
    void tokenNeverInLogsOrStoredError() {
        Logger logger = (Logger) LoggerFactory.getLogger(MailTelegramNotifier.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            InboundEmail a = pending("x", false, clock.now);
            stub.enqueue(TelegramStubServer.Reply.error(400, "Bad Request: message thread not found"));
            notifier(true).flush();

            assertThat(reload(a).getNotifyError()).contains("message thread not found").doesNotContain("SECRET");
            int port = stub.port();
            stub.close();                               // обрыв соединения — второй вид отказа
            TelegramSettings s = new TelegramSettings(true, "http://127.0.0.1:" + port, TOKEN, "-1001", "77");
            new MailTelegramNotifier(store, new TelegramClient(s, new ObjectMapper()), s, clock).flush();

            assertThat(logs.list).isNotEmpty();
            assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain("SECRET"));
        } finally {
            logger.detachAppender(logs);
        }
    }
}
```

- [ ] **Step 2: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailTelegramNotifierTest'`
Expected: FAIL компиляции.

- [ ] **Step 3: Реализация**

`service/mail/PendingNotification.java`:

```java
package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;

/** Уведомление из очереди: только то, что нужно отправке (без тяжёлых колонок письма). */
public record PendingNotification(Long id, String text, boolean silent, OffsetDateTime queuedAt) {}
```

`repository/InboundEmailRepository.java` — добавить импорты `com.vladoose.nir.entity.NotifyStatus`, `com.vladoose.nir.service.mail.PendingNotification`, `org.springframework.data.domain.Pageable`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param` и методы:

```java
    @Query("select new com.vladoose.nir.service.mail.PendingNotification(e.id, e.notifyText, e.notifySilent, e.notifyQueuedAt) "
            + "from InboundEmail e where e.notifyStatus = :status order by e.id")
    List<PendingNotification> findPendingNotifications(@Param("status") NotifyStatus status, Pageable page);

    long countByNotifyStatus(NotifyStatus status);
```

`service/mail/MailNotifyStore.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.NotifyStatus;
import com.vladoose.nir.repository.InboundEmailRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Очередь уведомлений в строках inbound_email. Отдельный бин с транзакцией на метод: отправка в Telegram идёт ВНЕ
 * транзакции (§6), исход каждой — своей короткой транзакцией. Рыночный фильтр — по MarketContext вызывающего.
 */
@Service
public class MailNotifyStore {

    private final InboundEmailRepository repo;

    public MailNotifyStore(InboundEmailRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<PendingNotification> pending(int limit) {
        return repo.findPendingNotifications(NotifyStatus.PENDING, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return repo.countByNotifyStatus(NotifyStatus.PENDING);
    }

    @Transactional
    public void markSent(long id, OffsetDateTime at) {
        repo.findById(id).ifPresent(e -> {
            e.setNotifyStatus(NotifyStatus.SENT);
            e.setNotifiedAt(at);
            e.setNotifyError(null);
        });
    }

    @Transactional
    public void markAttempt(long id, String error) {
        repo.findById(id).ifPresent(e -> {
            e.setNotifyAttempts(e.getNotifyAttempts() + 1);
            e.setNotifyError(cut(error));
        });
    }

    @Transactional
    public void markFailed(long id, String error) {
        repo.findById(id).ifPresent(e -> {
            e.setNotifyStatus(NotifyStatus.FAILED);
            e.setNotifyError(cut(error));
        });
    }

    private static String cut(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }
}
```

`service/mail/MailTelegramNotifier.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramException;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Отправка очереди уведомлений о письмах (спека §5.3). Пачка до 20 по возрастанию id; ПЕРВЫЙ сбой останавливает пачку
 * до следующего прохода — сбои Telegram почти всегда общие (токен, чат, тема, сеть), а долбёжка упирается в лимит.
 * 429 — пауза до retry_after. Не ушло за сутки от постановки — FAILED (письмо остаётся во «Входящих» и в почте).
 */
@Service
public class MailTelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailTelegramNotifier.class);
    static final int BATCH = 20;
    static final Duration GIVE_UP = Duration.ofHours(24);

    public record FlushResult(int sent, long pending, String lastError) {}

    private final MailNotifyStore store;
    private final TelegramClient client;
    private final TelegramSettings settings;
    private final Clock clock;
    private volatile Instant pausedUntil = Instant.EPOCH;
    private volatile String lastError;

    @Autowired
    public MailTelegramNotifier(MailNotifyStore store, TelegramClient client, TelegramSettings settings) {
        this(store, client, settings, Clock.systemUTC());
    }

    MailTelegramNotifier(MailNotifyStore store, TelegramClient client, TelegramSettings settings, Clock clock) {
        this.store = store;
        this.client = client;
        this.settings = settings;
        this.clock = clock;
    }

    public FlushResult flush() {
        if (!settings.isConfigured()) return new FlushResult(0, 0, null);
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) return new FlushResult(0, store.countPending(), lastError);
        int sent = 0;
        for (PendingNotification p : store.pending(BATCH)) {
            if (p.queuedAt() != null && p.queuedAt().toInstant().plus(GIVE_UP).isBefore(now)) {
                store.markFailed(p.id(), "не отправлено за сутки" + (lastError == null ? "" : ": " + lastError));
                log.warn("Уведомление о письме id={} не ушло в Telegram за сутки — снято с очереди", p.id());
                continue;
            }
            try {
                client.sendMail(p.text(), p.silent());
                store.markSent(p.id(), OffsetDateTime.now(clock));
                sent++;
                lastError = null;
            } catch (TelegramException e) {
                lastError = e.getMessage();
                store.markAttempt(p.id(), e.getMessage());
                if (e.retryAfterSeconds() != null) pausedUntil = now.plusSeconds(e.retryAfterSeconds());
                log.warn("Уведомление о письме id={} не отправлено: {}", p.id(), e.getMessage());
                break;
            } catch (RuntimeException e) {        // дефект у нас — класс, без текста (в нём бывают адреса)
                lastError = "внутренняя ошибка (" + e.getClass().getSimpleName() + ")";
                store.markAttempt(p.id(), lastError);
                log.warn("Уведомление о письме id={} не отправлено: {}", p.id(), lastError);
                break;
            }
        }
        return new FlushResult(sent, store.countPending(), lastError);
    }
}
```

- [ ] **Step 4: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.MailTelegramNotifierTest'`
Expected: PASS (6).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/PendingNotification.java src/main/java/com/vladoose/nir/service/mail/MailNotifyStore.java \
  src/main/java/com/vladoose/nir/service/mail/MailTelegramNotifier.java src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java \
  src/test/java/com/vladoose/nir/service/mail/MailTelegramNotifierTest.java
git commit -m "feat(mail): очередь уведомлений в Telegram — пачка до первого сбоя, пауза по 429, сутки на отправку

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: IMAP только на чтение — `ImapMailboxConnector`

**Files:**
- Create: `service/mail/MailboxConnector.java`, `service/mail/MailboxSession.java`, `service/mail/ImapMailboxConnector.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/ImapTestSupport.java` (помощник, public), `ImapMailboxConnectorTest.java`

**Interfaces:**
- Consumes: `MailParser.parse/from/header/receivedAt` (задача 2), `BrokenMail` (задача 4).
- Produces:
  - `interface MailboxConnector { MailboxSession open() throws MessagingException; }`;
  - `interface MailboxSession extends AutoCloseable` с методами `long uidValidity()`, `long maxUid()`, `List<Long> uidsAfter(long lastUid, int limit)`, `List<Long> uidsReceivedSince(Instant since, int limit)`, `ParsedMail fetch(long uid) throws Exception` (null — письма нет), `BrokenMail envelope(long uid, Exception cause)`, `boolean isAlive()`, `void close()`;
  - `ImapMailboxConnector` — Spring-конструктор с `mail.imap.*` + пакетный `(host, port, username, password, protocol, connectTimeoutMs, readTimeoutMs)`;
  - тестовый `ImapTestSupport` (public): `HOST`, `PORT`, `USER`, `PASS`; `seen(String subject)`; `appendWithReceivedDate(String subject, Instant at)`.

- [ ] **Step 1: Помощник IMAP для тестов**

`src/test/java/com/vladoose/nir/service/mail/ImapTestSupport.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.time.Instant;
import java.util.Date;
import java.util.Properties;

/** Проверки ящика GreenMail в обход кода приёма: «прочитано» ли письмо, письмо с датой получения в прошлом. */
public final class ImapTestSupport {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 3143;                // ServerSetupTest.IMAP
    public static final String USER = "zakup@westmed.kz";
    public static final String PASS = "secret";

    private ImapTestSupport() {}

    /** Стоит ли у письма с этой темой отметка «прочитано» (папка открывается только на чтение — ничего не меняет). */
    public static boolean seen(String subject) throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect(HOST, PORT, USER, PASS);
        try {
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            for (Message m : f.getMessages()) {
                if (subject.equals(m.getSubject())) return m.isSet(Flags.Flag.SEEN);
            }
            throw new AssertionError("в ящике нет письма «" + subject + "»");
        } finally {
            s.close();
        }
    }

    /** APPEND письма с датой получения (INTERNALDATE) в прошлом — как письмо, пришедшее, пока приём не работал. */
    public static void appendWithReceivedDate(String subject, Instant receivedAt) throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect(HOST, PORT, USER, PASS);
        try {
            Folder f = s.getFolder("INBOX");
            MimeMessage m = new MimeMessage((Session) null) {
                @Override public Date getReceivedDate() { return Date.from(receivedAt); }
            };
            m.setFrom(new InternetAddress("old@x.kz"));
            m.setSubject(subject, "UTF-8");
            m.setSentDate(Date.from(receivedAt));
            m.setText("Письмо из прошлого", "UTF-8");
            m.saveChanges();
            f.appendMessages(new Message[]{m});
        } finally {
            s.close();
        }
    }
}
```

- [ ] **Step 2: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/ImapMailboxConnectorTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.vladoose.nir.service.mail.ImapTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImapMailboxConnectorTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

    GreenMailUser user;

    @BeforeEach
    void user() { user = greenMail.setUser(USER, USER, PASS); }

    ImapMailboxConnector connector() {
        return new ImapMailboxConnector(HOST, PORT, USER, PASS, "imap", 5_000, 5_000);
    }

    @Test
    void readOnly_fetchDoesNotMarkSeen() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Непрочитанное", "Текст"));

        try (MailboxSession s = connector().open()) {
            List<Long> uids = s.uidsAfter(0, 10);
            ParsedMail p = s.fetch(uids.get(0));
            assertThat(p.subject()).isEqualTo("Непрочитанное");
            assertThat(p.text()).contains("Текст");
        }

        assertThat(seen("Непрочитанное")).isFalse();
    }

    @Test
    void uidsAfter_lastUid_returnsNothing_despiteImapStarQuirk() throws Exception {
        user.deliver(TestMimes.plain("s@x.kz", "Первое", "1"));
        user.deliver(TestMimes.plain("s@x.kz", "Второе", "2"));

        try (MailboxSession s = connector().open()) {
            List<Long> all = s.uidsAfter(0, 10);
            assertThat(all).hasSize(2).isSorted();
            assertThat(s.uidsAfter(all.get(1), 10)).isEmpty();
            assertThat(s.uidsAfter(all.get(0), 10)).containsExactly(all.get(1));
            assertThat(s.uidsAfter(0, 1)).containsExactly(all.get(0));
        }
    }

    @Test
    void maxUid_emptyIsZero_thenLastDelivered() throws Exception {
        try (MailboxSession s = connector().open()) {
            assertThat(s.maxUid()).isZero();
        }
        user.deliver(TestMimes.plain("s@x.kz", "Одно", "1"));
        try (MailboxSession s = connector().open()) {
            assertThat(s.maxUid()).isEqualTo(s.uidsAfter(0, 10).get(0));
        }
    }

    @Test
    void uidValidity_stableBetweenSessions() throws Exception {
        long a, b;
        try (MailboxSession s = connector().open()) { a = s.uidValidity(); }
        try (MailboxSession s = connector().open()) { b = s.uidValidity(); }
        assertThat(a).isEqualTo(b).isPositive();
    }

    @Test
    void uidsReceivedSince_exactTimeInCode() throws Exception {
        appendWithReceivedDate("Старое", Instant.now().minus(Duration.ofHours(2)));
        user.deliver(TestMimes.plain("s@x.kz", "Свежее", "новое"));

        try (MailboxSession s = connector().open()) {
            List<Long> window = s.uidsReceivedSince(Instant.now().minus(Duration.ofMinutes(60)), 10);
            assertThat(window).hasSize(1);
            assertThat(s.fetch(window.get(0)).subject()).isEqualTo("Свежее");
        }
    }

    @Test
    void fetchUnknownUid_null_envelopeReadsHeaders() throws Exception {
        user.deliver(TestMimes.plain("Иван <ivan@x.kz>", "Конверт", "1"));
        try (MailboxSession s = connector().open()) {
            assertThat(s.fetch(999_999)).isNull();
            long uid = s.uidsAfter(0, 10).get(0);
            BrokenMail b = s.envelope(uid, new IllegalStateException("x"));
            assertThat(b.from()).isEqualTo("Иван <ivan@x.kz>");
            assertThat(b.subject()).isEqualTo("Конверт");
            assertThat(b.errorClass()).isEqualTo("IllegalStateException");
        }
    }

    @Test
    void isAlive_falseAfterClose() throws Exception {
        MailboxSession s = connector().open();
        assertThat(s.isAlive()).isTrue();
        s.close();
        assertThat(s.isAlive()).isFalse();
    }

    @Test
    void silentServer_openFailsByTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket ignored = silent.accept()) { Thread.sleep(10_000); } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
            ImapMailboxConnector c = new ImapMailboxConnector(HOST, silent.getLocalPort(), USER, PASS, "imap", 1_000, 1_000);
            long t0 = System.nanoTime();
            assertThatThrownBy(c::open).isInstanceOf(Exception.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
```

- [ ] **Step 3: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.ImapMailboxConnectorTest'`
Expected: FAIL компиляции.

- [ ] **Step 4: Реализация**

`service/mail/MailboxConnector.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;

/** Открывает сессию с ящиком; за интерфейсом — чтобы проход приёма тестировался без IMAP. */
public interface MailboxConnector {
    MailboxSession open() throws MessagingException;
}
```

`service/mail/MailboxSession.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;

import java.time.Instant;
import java.util.List;

/** Ящик, открытый только на чтение: UID, поиск по окну, разбор писем. */
public interface MailboxSession extends AutoCloseable {

    long uidValidity() throws MessagingException;

    /** UID последнего письма ящика; 0 — ящик пуст. */
    long maxUid() throws MessagingException;

    /** UID писем после lastUid по возрастанию, не больше limit. */
    List<Long> uidsAfter(long lastUid, int limit) throws MessagingException;

    /** UID писем, полученных не раньше since, по возрастанию; не больше limit (переполнено — самые свежие). */
    List<Long> uidsReceivedSince(Instant since, int limit) throws MessagingException;

    /** Разобранное письмо; null — письма с таким UID уже нет. */
    ParsedMail fetch(long uid) throws Exception;

    /** Что удалось прочитать о письме, которое не разобралось. */
    BrokenMail envelope(long uid, Exception cause);

    /** Соединение и папка живы: иначе сбой — это обрыв связи, а не битое письмо. */
    boolean isAlive();

    @Override
    void close();
}
```

`service/mail/ImapMailboxConnector.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.*;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * IMAP-ящик только на чтение (спека §3.1): EXAMINE + BODY.PEEK — отметки «прочитано» у людей не трогаем. Таймауты
 * на соединение, чтение и запись: без них зависшее соединение навсегда заняло бы поток приёма.
 */
@Component
public class ImapMailboxConnector implements MailboxConnector {

    static final int CONNECT_TIMEOUT_MS = 20_000;
    static final int READ_TIMEOUT_MS = 60_000;
    static final int WRITE_TIMEOUT_MS = 20_000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    @Autowired
    public ImapMailboxConnector(@Value("${mail.imap.host:localhost}") String host,
                                @Value("${mail.imap.port:3143}") int port,
                                @Value("${mail.imap.username:}") String username,
                                @Value("${mail.imap.password:}") String password,
                                @Value("${mail.imap.protocol:imap}") String protocol) {
        this(host, port, username, password, protocol, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    ImapMailboxConnector(String host, int port, String username, String password, String protocol,
                         int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public MailboxSession open() throws MessagingException {
        Properties props = new Properties();
        String p = "mail." + protocol + ".";
        props.put(p + "connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put(p + "timeout", String.valueOf(readTimeoutMs));
        props.put(p + "writetimeout", String.valueOf(WRITE_TIMEOUT_MS));
        props.put(p + "peek", "true");                  // тело — BODY.PEEK[]: «прочитано» не ставится
        Store store = Session.getInstance(props).getStore(protocol);
        store.connect(host, port, username, password);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);                // EXAMINE: флаги не меняются
            return new ImapSession(store, inbox);
        } catch (MessagingException | RuntimeException e) {
            closeQuietly(store);
            throw e;
        }
    }

    private static void closeQuietly(Store store) {
        try {
            store.close();
        } catch (Exception ignored) {
        }
    }

    static final class ImapSession implements MailboxSession {

        private final Store store;
        private final Folder folder;
        private final UIDFolder uids;

        ImapSession(Store store, Folder folder) {
            this.store = store;
            this.folder = folder;
            this.uids = (UIDFolder) folder;
        }

        @Override
        public long uidValidity() throws MessagingException {
            return uids.getUIDValidity();
        }

        @Override
        public long maxUid() throws MessagingException {
            int n = folder.getMessageCount();
            return n == 0 ? 0 : uids.getUID(folder.getMessage(n));
        }

        @Override
        public List<Long> uidsAfter(long lastUid, int limit) throws MessagingException {
            Message[] msgs = uids.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
            List<Long> out = new ArrayList<>();
            for (Message m : msgs) {
                if (m == null) continue;
                long uid = uids.getUID(m);
                if (uid > lastUid) out.add(uid);           // «UID n:*» отдаёт последнее письмо, даже если его UID < n
            }
            Collections.sort(out);
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }

        @Override
        public List<Long> uidsReceivedSince(Instant since, int limit) throws MessagingException {
            Message[] msgs = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));   // сервер — по ДНЮ
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(msgs, fp);
            List<Long> out = new ArrayList<>();
            for (Message m : msgs) {
                Date d = m.getReceivedDate();
                if (d != null && !d.toInstant().isBefore(since)) out.add(uids.getUID(m));   // точное время — здесь
            }
            Collections.sort(out);
            return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
        }

        @Override
        public ParsedMail fetch(long uid) throws Exception {
            Message m = uids.getMessageByUID(uid);
            return m == null ? null : MailParser.parse(m, uid);
        }

        @Override
        public BrokenMail envelope(long uid, Exception cause) {
            String from = null, subject = null, messageId = null;
            OffsetDateTime at = null;
            try {
                Message m = uids.getMessageByUID(uid);
                if (m != null) {
                    try { from = MailParser.from(m); } catch (Exception ignored) { }
                    try { subject = m.getSubject(); } catch (Exception ignored) { }
                    try { messageId = MailParser.header(m, "Message-ID"); } catch (Exception ignored) { }
                    at = MailParser.receivedAt(m);
                }
            } catch (Exception ignored) {
            }
            return new BrokenMail(uid, messageId, from, subject, at != null ? at : OffsetDateTime.now(ZoneOffset.UTC),
                    cause.getClass().getSimpleName());
        }

        @Override
        public boolean isAlive() {
            return store.isConnected() && folder.isOpen();
        }

        @Override
        public void close() {
            try {
                if (folder.isOpen()) folder.close(false);
            } catch (Exception ignored) {
            }
            closeQuietly(store);
        }
    }
}
```

- [ ] **Step 5: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.mail.ImapMailboxConnectorTest'`
Expected: PASS (8).
Если `uidsReceivedSince_exactTimeInCode` падает, потому что GreenMail не берёт дату из APPEND, — НЕ ослаблять тест. Проверить, что пришло в INTERNALDATE (`m.getReceivedDate()` у письма «Старое»), и сообщить контроллеру: окно первого запуска тогда проверяется иначе.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/MailboxConnector.java src/main/java/com/vladoose/nir/service/mail/MailboxSession.java \
  src/main/java/com/vladoose/nir/service/mail/ImapMailboxConnector.java src/test/java/com/vladoose/nir/service/mail/ImapTestSupport.java \
  src/test/java/com/vladoose/nir/service/mail/ImapMailboxConnectorTest.java
git commit -m "feat(mail): IMAP только на чтение — EXAMINE + PEEK, UID после курсора, окно по времени, таймауты

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Свой поток приёма — `MailPollScheduler` и общий контекст почтовых тестов

**Files:**
- Modify: `service/MailPollScheduler.java` (переписать), `src/main/resources/application.yaml` (`mail.imap.poll-ms` → 60000, + `initial-delay-ms`)
- Create: `src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java` (мета-аннотация)
- Modify: `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`, `src/test/java/com/vladoose/nir/email/KpRoundTripTest.java` — перейти на `@MailIntegrationTest`
- Test: `src/test/java/com/vladoose/nir/service/MailPollSchedulerTest.java`

**Interfaces:**
- Consumes: `MailReceiveService.poll(): PollResultResponse`, `MailReceiveService.getMailboxMarket(): Market` (есть сейчас и останутся после задачи 10).
- Produces:
  - `MailPollScheduler(MailReceiveService, boolean enabled)` — `@Autowired`, плюс пакетный `(MailReceiveService, boolean, Duration manualWait)`;
  - `void tick()` (`@Scheduled`), `PollResultResponse run()`;
  - `@MailIntegrationTest` — `@SpringBootTest @Transactional @TestPropertySource(…)` с IMAP GreenMail, `initial-delay-ms=86400000`, Telegram на заглушку `127.0.0.1:7798`.

Почему эта задача идёт ДО переписывания приёма. В контекстах с `mail.imap.enabled=true` планировщик тикает прямо во время тестов. Каждый тик — отдельная закоммиченная транзакция, то есть письма GreenMail уехали бы в nirdb мимо отката. Поэтому сначала тесты получают огромную начальную задержку, и только потом приём начинает опрашивать раз в минуту.

- [ ] **Step 1: Мета-аннотация общего контекста**

`src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java`:

```java
package com.vladoose.nir.mail;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.*;

/**
 * Общий контекст почтовых интеграционных тестов: ОДИН набор свойств на все классы (каждый особый набор — ещё 10
 * соединений к nirdb, CLAUDE.md §14). IMAP — GreenMail :3143; планировщик не тикает (начальная задержка — сутки),
 * иначе его проход коммитил бы письма GreenMail в nirdb мимо отката теста; Telegram — на заглушку 127.0.0.1:7798
 * (тест, которому она нужна, поднимает её сам; остальным отправка честно не удаётся — это пишется в их откатываемую строку).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "mail.imap.enabled=true",
        "mail.imap.host=127.0.0.1",
        "mail.imap.port=3143",
        "mail.imap.username=zakup@westmed.kz",
        "mail.imap.password=secret",
        "mail.imap.protocol=imap",
        "mail.imap.market=KZ",
        "mail.imap.initial-delay-ms=86400000",
        "spring.mail.username=zakup@westmed.kz",
        "notify.telegram.enabled=true",
        "notify.telegram.api-url=http://127.0.0.1:7798",
        "notify.telegram.bot-token=123456:TEST-TOKEN-SECRET",
        "notify.telegram.chat-id=-1001",
        "notify.telegram.mail-thread-id=77",
        "ais.public-url=https://ais.example"
})
public @interface MailIntegrationTest {
}
```

- [ ] **Step 2: Перевести два существующих класса на аннотацию**

В `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`:
- убрать `@SpringBootTest`, `@Transactional`, `@TestPropertySource(...)` и их импорты;
- поставить `@MailIntegrationTest`;
- `GreenMailExtension` — с `.withPerMethodLifecycle(true)` (явный свежий ящик на каждый тест).

В `src/test/java/com/vladoose/nir/email/KpRoundTripTest.java` — то же самое, плюс `import com.vladoose.nir.mail.MailIntegrationTest;`. Его прежний набор совпадает с новым по сути: `spring.mail.username` в аннотации есть.

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest' --tests 'com.vladoose.nir.email.KpRoundTripTest'`
Expected: PASS — поведение не менялось. Это проверка, что общий контекст поднимается.

- [ ] **Step 3: Падающие тесты планировщика**

`src/test/java/com/vladoose/nir/service/MailPollSchedulerTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MailPollSchedulerTest {

    MailReceiveService service = mock(MailReceiveService.class);

    PollResultResponse result(String msg) {
        PollResultResponse r = new PollResultResponse();
        r.setEnabled(true);
        r.setMessage(msg);
        return r;
    }

    @Test
    void tick_disabled_noPass() throws Exception {
        new MailPollScheduler(service, false).tick();
        Thread.sleep(200);
        verify(service, never()).poll();
    }

    @Test
    void tick_runsOnOwnThread_withMailboxMarket() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<Market> market = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        when(service.poll()).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            market.set(MarketContext.get());
            done.countDown();
            return result("ok");
        });

        new MailPollScheduler(service, true).tick();

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).isEqualTo("mail-imap");
        assertThat(market.get()).isEqualTo(Market.KZ);
    }

    @Test
    void tick_skipsWhilePassRunning() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        CountDownLatch release = new CountDownLatch(1);
        when(service.poll()).thenAnswer(inv -> { release.await(2, TimeUnit.SECONDS); return result("ok"); });
        MailPollScheduler s = new MailPollScheduler(service, true);

        s.tick();
        s.tick();
        s.tick();
        release.countDown();

        verify(service, timeout(2000).times(1)).poll();
        Thread.sleep(200);
        verify(service, times(1)).poll();
    }

    @Test
    void run_isSerializedWithTickPass() throws Exception {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        when(service.poll()).thenAnswer(inv -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstStarted.countDown();
            Thread.sleep(300);
            active.decrementAndGet();
            return result("ok");
        });
        MailPollScheduler s = new MailPollScheduler(service, true);

        s.tick();
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        PollResultResponse r = s.run();

        assertThat(r.getMessage()).isEqualTo("ok");
        assertThat(maxActive.get()).isEqualTo(1);
        verify(service, times(2)).poll();
    }

    @Test
    void run_timeout_returnsStillRunningMessage() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenAnswer(inv -> { Thread.sleep(1_000); return result("поздно"); });

        PollResultResponse r = new MailPollScheduler(service, true, Duration.ofMillis(200)).run();

        assertThat(r.getMessage()).contains("ещё идёт");
    }

    @Test
    void run_passThrows_messageWithClass_nextRunWorks() {
        when(service.getMailboxMarket()).thenReturn(Market.KZ);
        when(service.poll()).thenThrow(new IllegalStateException("boom")).thenReturn(result("ok"));
        MailPollScheduler s = new MailPollScheduler(service, true);

        assertThat(s.run().getMessage()).contains("IllegalStateException");
        assertThat(s.run().getMessage()).isEqualTo("ok");
    }
}
```

- [ ] **Step 4: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.MailPollSchedulerTest'`
Expected: FAIL — нет конструктора `(MailReceiveService, boolean, Duration)`; `tick` выполняется в вызывающем потоке.

- [ ] **Step 5: Реализация**

`service/MailPollScheduler.java` — заменить целиком:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Расписание приёма почты (спека §3.5). Проход — на СВОЁМ однопоточном экзекьюторе «mail-imap», а не на общем
 * scheduling-1: зависший IMAP не должен держать остальные фоновые задачи. Проходы никогда не идут параллельно —
 * курсор ящика не делится между двумя проходами. Рынок ящика ставится ЯВНО и чистится в finally (§6 CLAUDE.md).
 */
@Component
public class MailPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailPollScheduler.class);

    private final MailReceiveService mailReceiveService;
    private final boolean enabled;
    private final Duration manualWait;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-imap");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean tickQueued = new AtomicBoolean(false);

    @Autowired
    public MailPollScheduler(MailReceiveService mailReceiveService,
                             @Value("${mail.imap.enabled:false}") boolean enabled) {
        this(mailReceiveService, enabled, Duration.ofSeconds(90));
    }

    MailPollScheduler(MailReceiveService mailReceiveService, boolean enabled, Duration manualWait) {
        this.mailReceiveService = mailReceiveService;
        this.enabled = enabled;
        this.manualWait = manualWait;
    }

    @Scheduled(fixedDelayString = "${mail.imap.poll-ms:60000}", initialDelayString = "${mail.imap.initial-delay-ms:20000}")
    public void tick() {
        if (!enabled) return;
        if (!tickQueued.compareAndSet(false, true)) return;     // прошлый проход ещё идёт — тик пропускаем
        executor.submit(() -> {
            try {
                pass();
            } finally {
                tickQueued.set(false);
            }
        });
    }

    /** «Проверить почту» («Входящие») и «Проверить ответы» (запросы КП): проход в том же потоке, ждём до manualWait. */
    public PollResultResponse run() {
        Future<PollResultResponse> f = executor.submit(this::pass);
        try {
            return f.get(manualWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return message("Проверка почты ещё идёт — обновите страницу через минуту");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return message("Проверка почты прервана");
        } catch (ExecutionException e) {
            return message("Ошибка проверки почты: " + e.getCause().getClass().getSimpleName());
        }
    }

    private PollResultResponse pass() {
        MarketContext.set(mailReceiveService.getMailboxMarket());
        try {
            return mailReceiveService.poll();
        } catch (RuntimeException e) {
            log.warn("Проход приёма почты упал", e);
            return message("Ошибка проверки почты: " + e.getClass().getSimpleName());
        } finally {
            MarketContext.clear();
        }
    }

    private PollResultResponse message(String text) {
        PollResultResponse r = new PollResultResponse();
        r.setEnabled(enabled);
        r.setMessage(text);
        return r;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

В `src/main/resources/application.yaml`, блок `mail.imap`, заменить строки `poll-ms` и `since-minutes` (с комментарием над ней) на:

```yaml
    poll-ms: ${MAIL_IMAP_POLL_MS:60000}
    initial-delay-ms: ${MAIL_IMAP_INITIAL_DELAY_MS:20000}
    # окно ПЕРВОГО запуска (нет курсора / сменился UIDVALIDITY): письма старше не обрабатываются, дальше — курсор по UID
    since-minutes: ${MAIL_IMAP_SINCE_MINUTES:60}
```

- [ ] **Step 6: Тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.MailPollSchedulerTest' --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest' --tests 'com.vladoose.nir.email.KpRoundTripTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/MailPollScheduler.java src/main/resources/application.yaml \
  src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java \
  src/test/java/com/vladoose/nir/email/KpRoundTripTest.java src/test/java/com/vladoose/nir/service/MailPollSchedulerTest.java
git commit -m "feat(mail): приём почты на своём потоке mail-imap, опрос раз в минуту; общий контекст почтовых тестов

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Проход приёма — переписать `MailReceiveService`

**Files:**
- Create: `util/InfrastructureFailure.java`
- Modify:
  - `integration/whatsapp/WhatsappChatSync.java` — `isInfrastructureFailure` делегирует;
  - `dto/response/PollResultResponse.java` — новые счётчики;
  - `service/MailReceiveService.java` — переписать;
  - `src/main/resources/application.yaml` (+`client-requests`, `notify.telegram.*`, `ais.public-url`);
  - `src/main/resources/application-prod.yaml` (+`ais.public-url`).
- Test:
  - `src/test/java/com/vladoose/nir/service/MailReceiveServiceTest.java` — юнит, Mockito;
  - `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java` — дополнить сквозными.

**Interfaces:**
- Consumes: `MailboxConnector`, `MailboxSession` (задача 8); `MailIngestWriter` с `write/writeBroken/moveCursorTo/mailbox` (задача 6); `MailTelegramNotifier.flush()` (задача 7); `MailCursorRepository` (задача 1); `@MailIntegrationTest`, `TelegramStubServer`, `TestMimes`, `ImapTestSupport`.
- Produces:
  - `MailReceiveService(MailboxConnector, MailIngestWriter, MailCursorRepository, MailTelegramNotifier, boolean enabled, String market, long sinceMinutes)` — `@Autowired`, плюс пакетный с `Market` и `Clock`;
  - `PollResultResponse poll()`, `Market getMailboxMarket()`;
  - `PollResultResponse` — новые поля `bounces`, `autoReplies`, `broken` (int), `telegramSent` (int), `telegramPending` (long);
  - `InfrastructureFailure.test(Throwable): boolean`.

- [ ] **Step 1: Вынести признак «сбой инфраструктуры»**

`util/InfrastructureFailure.java` — перенести из `WhatsappChatSync` константы `MAX_CAUSE_DEPTH`, `INFRA_SQLSTATE_CLASSES` и тело `isInfrastructureFailure` без изменений:

```java
package com.vladoose.nir.util;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * Сбой инфраструктуры (база, диск, соединение), а не поломка конкретного письма или сообщения — по всей цепочке
 * причин: по типу исключения и классу SQLSTATE (08, 40, 53, 57, 58). Общий признак приёма WhatsApp и почты:
 * инфраструктурный сбой — пауза и повтор, а не «ядовитое» сообщение (CLAUDE.md §14).
 */
public final class InfrastructureFailure {

    private static final int MAX_CAUSE_DEPTH = 32;
    private static final Set<String> INFRA_SQLSTATE_CLASSES = Set.of("08", "40", "53", "57", "58");

    private InfrastructureFailure() {}

    public static boolean test(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() >= 2
                    && INFRA_SQLSTATE_CLASSES.contains(sql.getSQLState().substring(0, 2))) {
                return true;
            }
        }
        return false;
    }
}
```

В `integration/whatsapp/WhatsappChatSync.java`:
- тело `isInfrastructureFailure(Throwable e)` заменить на `return InfrastructureFailure.test(e);`;
- удалить ставшие неиспользуемыми константы `MAX_CAUSE_DEPTH` и `INFRA_SQLSTATE_CLASSES`, если на них больше ничего не ссылается (проверить `grep -n "MAX_CAUSE_DEPTH\|INFRA_SQLSTATE_CLASSES"`), и лишние импорты.

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.whatsapp.*'`
Expected: PASS — поведение WhatsApp не менялось.

- [ ] **Step 2: Падающие юнит-тесты прохода**

`src/test/java/com/vladoose/nir/service/MailReceiveServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailReceiveServiceTest {

    static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    MailboxConnector connector = mock(MailboxConnector.class);
    MailboxSession session = mock(MailboxSession.class);
    MailIngestWriter writer = mock(MailIngestWriter.class);
    MailCursorRepository cursors = mock(MailCursorRepository.class);
    MailTelegramNotifier notifier = mock(MailTelegramNotifier.class);

    MailReceiveService service(boolean enabled) {
        return new MailReceiveService(connector, writer, cursors, notifier, enabled, Market.KZ, 60,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static MailIngestWriter.WriteResult ok(MailClass c) { return new MailIngestWriter.WriteResult(c, null, 1L, false); }

    @BeforeEach
    void setUp() throws Exception {
        when(writer.mailbox()).thenReturn("zakup@westmed.kz");
        when(notifier.flush()).thenReturn(new MailTelegramNotifier.FlushResult(0, 0, null));
        when(connector.open()).thenReturn(session);
        when(session.uidValidity()).thenReturn(7L);
        when(session.isAlive()).thenReturn(true);
        when(writer.write(any(), anyLong())).thenReturn(ok(MailClass.UNMATCHED));
    }

    void cursorAt(long uidValidity, long lastUid) {
        when(cursors.findById("zakup@westmed.kz")).thenReturn(Optional.of(
                MailCursor.builder().mailbox("zakup@westmed.kz").uidValidity(uidValidity).lastUid(lastUid).build()));
    }

    @Test
    void disabled_noConnection() throws Exception {
        PollResultResponse r = service(false).poll();
        assertThat(r.isEnabled()).isFalse();
        verify(connector, never()).open();
    }

    @Test
    void firstRun_windowThenCursorToMaxTakenBeforeWindow() throws Exception {
        when(cursors.findById("zakup@westmed.kz")).thenReturn(Optional.empty());
        when(session.maxUid()).thenReturn(50L);
        when(session.uidsReceivedSince(NOW.minus(Duration.ofMinutes(60)), MailReceiveService.MAX_PER_PASS)).thenReturn(List.of(48L, 49L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());

        service(true).poll();

        InOrder order = inOrder(session, writer);
        order.verify(session).maxUid();
        order.verify(session).uidsReceivedSince(any(), anyInt());
        order.verify(writer).write(argThat(m -> m.uid() == 48), eq(7L));
        order.verify(writer).write(argThat(m -> m.uid() == 49), eq(7L));
        order.verify(writer).moveCursorTo(7L, 50L);
    }

    @Test
    void newUidValidity_isFirstRun() throws Exception {
        cursorAt(6, 100);
        when(session.maxUid()).thenReturn(3L);
        when(session.uidsReceivedSince(any(), anyInt())).thenReturn(List.of());

        service(true).poll();

        verify(session, never()).uidsAfter(anyLong(), anyInt());
        verify(writer).moveCursorTo(7L, 3L);
    }

    @Test
    void normalPass_afterCursor_noMoveToMax() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(10, MailReceiveService.MAX_PER_PASS)).thenReturn(List.of(11L, 12L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());

        PollResultResponse r = service(true).poll();

        verify(writer, times(2)).write(any(), eq(7L));
        verify(writer, never()).moveCursorTo(anyLong(), anyLong());
        assertThat(r.getFetched()).isEqualTo(2);
    }

    @Test
    void brokenMessage_whileAlive_writesBrokenAndContinues() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad mime"));
        when(session.fetch(12L)).thenReturn(mail().uid(12).build());
        BrokenMail broken = new BrokenMail(11, null, "a@b.kz", "S", OffsetDateTime.now(), "MessagingException");
        when(session.envelope(eq(11L), any())).thenReturn(broken);

        PollResultResponse r = service(true).poll();

        verify(writer).writeBroken(broken, 7L);
        verify(writer).write(argThat(m -> m.uid() == 12), eq(7L));
        assertThat(r.getBroken()).isEqualTo(1);
    }

    @Test
    void fetchFails_connectionDead_stopsPass() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("connection reset"));
        when(session.isAlive()).thenReturn(false);

        service(true).poll();

        verify(writer, never()).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
    }

    @Test
    void writerInfrastructureFailure_stopsPass_noBrokenRow() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());
        when(writer.write(argThat(m -> m.uid() == 11), anyLong())).thenThrow(new DataAccessResourceFailureException("db down"));

        service(true).poll();

        verify(writer, never()).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
    }

    @Test
    void writerOtherFailure_brokenRowFromParsedFields_continues() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).from("x@y.kz").subject("Тема").build());
        when(writer.write(argThat(m -> m.uid() == 11), anyLong())).thenThrow(new IllegalStateException("bug"));

        service(true).poll();

        verify(writer).writeBroken(argThat(b -> b.uid() == 11 && "x@y.kz".equals(b.from()) && "Тема".equals(b.subject())
                && "IllegalStateException".equals(b.errorClass())), eq(7L));
        verify(writer).write(argThat(m -> m.uid() == 12), eq(7L));
    }

    @Test
    void writeBrokenFails_stopsPass() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad"));
        when(session.envelope(eq(11L), any())).thenReturn(new BrokenMail(11, null, null, null, OffsetDateTime.now(), "X"));
        doThrow(new IllegalStateException("db")).when(writer).writeBroken(any(), anyLong());

        service(true).poll();

        verify(session, never()).fetch(12L);
    }

    @Test
    void deletedBetweenSearchAndFetch_skipped() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenReturn(null);
        when(session.fetch(12L)).thenReturn(mail().uid(12).build());

        service(true).poll();

        verify(writer, times(1)).write(any(), anyLong());
    }

    @Test
    void connectFails_flushStillRuns_messageSaysSo() throws Exception {
        when(connector.open()).thenThrow(new MessagingException("AUTHENTICATIONFAILED"));
        when(notifier.flush()).thenReturn(new MailTelegramNotifier.FlushResult(2, 0, null));

        PollResultResponse r = service(true).poll();

        verify(notifier).flush();
        assertThat(r.getMessage()).startsWith("Ошибка подключения к почте: AUTHENTICATIONFAILED")
                .contains("Telegram: отправлено 2, ждут 0");
        assertThat(r.getTelegramSent()).isEqualTo(2);
    }

    @Test
    void countsAndSummary() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L, 13L, 14L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());
        when(writer.write(argThat(m -> m.uid() == 11), anyLong())).thenReturn(ok(MailClass.SUPPLIER_RESPONSE));
        when(writer.write(argThat(m -> m.uid() == 12), anyLong())).thenReturn(ok(MailClass.BOUNCE));
        when(writer.write(argThat(m -> m.uid() == 13), anyLong())).thenReturn(ok(MailClass.SITE_NOTIFICATION));
        when(writer.write(argThat(m -> m.uid() == 14), anyLong())).thenReturn(new MailIngestWriter.WriteResult(null, null, null, true));
        when(notifier.flush()).thenReturn(new MailTelegramNotifier.FlushResult(1, 1, "Telegram: HTTP 400 — Bad Request"));

        PollResultResponse r = service(true).poll();

        assertThat(r.getFetched()).isEqualTo(2);
        assertThat(r.getSupplierResponses()).isEqualTo(1);
        assertThat(r.getBounces()).isEqualTo(1);
        assertThat(r.getSkippedSiteNotifications()).isEqualTo(1);
        assertThat(r.getMessage()).isEqualTo("Новых писем: 2 (ответов поставщиков — 1, не доставлено — 1); "
                + "уведомлений сайта о заявках пропущено: 1; Telegram: отправлено 1, ждут 1 — Telegram: HTTP 400 — Bad Request");
    }
}
```

- [ ] **Step 3: Убедиться, что падают**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.MailReceiveServiceTest'`
Expected: FAIL компиляции — старый конструктор и нет полей `PollResultResponse`.

- [ ] **Step 4: Реализация**

`dto/response/PollResultResponse.java` — заменить целиком:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PollResultResponse {
    private boolean enabled;
    /** Записано новых писем (без дублей и уведомлений сайта; с неразобранными). */
    private int fetched;
    private int supplierResponses;
    private int clientRequests;
    private int unmatched;
    /** «Письмо не доставлено» — возвраты почтовых серверов. */
    private int bounces;
    private int autoReplies;
    /** Письма, которые не разобрались или не записались (записаны короткой строкой). */
    private int broken;
    /** Письма-уведомления westmed.kz о заявках с сайта: сами заявки приходят через API сайта (обращения). */
    private int skippedSiteNotifications;
    private int telegramSent;
    private long telegramPending;
    private String message;
}
```

`service/MailReceiveService.java` — заменить целиком:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import com.vladoose.nir.util.InfrastructureFailure;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Приём почты ящика АИС (спека 2026-10-05-zakup-mail-telegram §3): ящик только на чтение, курсор по UID, разбор ВНЕ
 * транзакции, запись — по письму в {@link MailIngestWriter}, после писем — уведомления в Telegram.
 * НЕ @Transactional: транзакция на всё время IMAP держала бы соединение с базой. Рынок ставит вызывающий
 * (MailPollScheduler/тест): MarketContext.set(рынок ящика) до вызова.
 */
@Service
public class MailReceiveService {

    private static final Logger log = LoggerFactory.getLogger(MailReceiveService.class);
    static final int MAX_PER_PASS = 100;

    private final MailboxConnector connector;
    private final MailIngestWriter writer;
    private final MailCursorRepository cursorRepository;
    private final MailTelegramNotifier notifier;
    private final boolean enabled;
    private final Market mailboxMarket;
    private final long sinceMinutes;
    private final Clock clock;

    @Autowired
    public MailReceiveService(MailboxConnector connector, MailIngestWriter writer, MailCursorRepository cursorRepository,
                              MailTelegramNotifier notifier,
                              @Value("${mail.imap.enabled:false}") boolean enabled,
                              @Value("${mail.imap.market:KZ}") String market,
                              @Value("${mail.imap.since-minutes:60}") long sinceMinutes) {
        this(connector, writer, cursorRepository, notifier, enabled, Market.fromHeader(market), sinceMinutes, Clock.systemUTC());
    }

    MailReceiveService(MailboxConnector connector, MailIngestWriter writer, MailCursorRepository cursorRepository,
                       MailTelegramNotifier notifier, boolean enabled, Market mailboxMarket, long sinceMinutes, Clock clock) {
        this.connector = connector;
        this.writer = writer;
        this.cursorRepository = cursorRepository;
        this.notifier = notifier;
        this.enabled = enabled;
        this.mailboxMarket = mailboxMarket;
        this.sinceMinutes = sinceMinutes;
        this.clock = clock;
    }

    public Market getMailboxMarket() {
        return mailboxMarket;
    }

    public PollResultResponse poll() {
        PollResultResponse result = new PollResultResponse();
        if (!enabled) {
            result.setEnabled(false);
            result.setMessage("Приём почты выключен (MAIL_IMAP_ENABLED=false)");
            return result;
        }
        result.setEnabled(true);
        String imapError = null;
        try (MailboxSession session = connector.open()) {
            pass(session, result);
        } catch (Exception e) {
            imapError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Ошибка приёма почты: {}", imapError);
        }
        MailTelegramNotifier.FlushResult tg = notifier.flush();      // и при недоступной почте: очередь не ждёт IMAP
        result.setTelegramSent(tg.sent());
        result.setTelegramPending(tg.pending());
        result.setMessage(summary(result, imapError, tg));
        return result;
    }

    private void pass(MailboxSession s, PollResultResponse result) throws MessagingException {
        long uidValidity = s.uidValidity();
        Optional<MailCursor> cursor = cursorRepository.findById(writer.mailbox())
                .filter(c -> c.getUidValidity() == uidValidity);
        if (cursor.isPresent()) {
            for (long uid : s.uidsAfter(cursor.get().getLastUid(), MAX_PER_PASS)) {
                if (!processOne(s, uidValidity, uid, result)) return;
            }
            return;
        }
        // Первый запуск: только окно since-minutes, затем курсор — на последнее письмо, снятое ДО окна (письмо,
        // пришедшее во время прохода, получит UID больше и уйдёт следующим проходом, а не перепрыгнется).
        long max = s.maxUid();
        for (long uid : s.uidsReceivedSince(clock.instant().minus(Duration.ofMinutes(sinceMinutes)), MAX_PER_PASS)) {
            if (uid > max) continue;
            if (!processOne(s, uidValidity, uid, result)) return;
        }
        writer.moveCursorTo(uidValidity, max);
    }

    /** false — проход остановить: связь с ящиком оборвалась или база недоступна (спека §3.4). */
    private boolean processOne(MailboxSession s, long uidValidity, long uid, PollResultResponse result) {
        ParsedMail m;
        try {
            m = s.fetch(uid);
        } catch (Exception e) {
            if (!s.isAlive()) {
                log.warn("Связь с ящиком оборвалась на письме UID {}: {}", uid, e.getClass().getSimpleName());
                return false;
            }
            log.warn("Письмо UID {} не разобрано: {}", uid, e.toString());
            return writeBroken(s.envelope(uid, e), uidValidity, result);
        }
        if (m == null) return true;                         // письмо удалили между поиском и чтением
        try {
            count(result, writer.write(m, uidValidity));
            return true;
        } catch (RuntimeException e) {
            if (InfrastructureFailure.test(e)) {
                log.warn("База недоступна на письме UID {} — проход остановлен, повтор следующим: {}", uid, e.getClass().getSimpleName());
                return false;
            }
            log.warn("Письмо UID {} не записано: {}", uid, e.toString());
            return writeBroken(new BrokenMail(uid, m.messageId(), m.from(), m.subject(), m.receivedAt(),
                    e.getClass().getSimpleName()), uidValidity, result);
        }
    }

    private boolean writeBroken(BrokenMail b, long uidValidity, PollResultResponse result) {
        try {
            writer.writeBroken(b, uidValidity);
            result.setBroken(result.getBroken() + 1);
            result.setFetched(result.getFetched() + 1);
            return true;
        } catch (RuntimeException e) {
            log.warn("Письмо UID {} не записано даже коротко — проход остановлен: {}", b.uid(), e.getClass().getSimpleName());
            return false;
        }
    }

    private static void count(PollResultResponse r, MailIngestWriter.WriteResult w) {
        if (w.duplicate()) return;
        switch (w.mailClass()) {
            case SITE_NOTIFICATION -> {
                r.setSkippedSiteNotifications(r.getSkippedSiteNotifications() + 1);
                return;
            }
            case SUPPLIER_RESPONSE -> r.setSupplierResponses(r.getSupplierResponses() + 1);
            case BOUNCE -> r.setBounces(r.getBounces() + 1);
            case AUTO_REPLY -> r.setAutoReplies(r.getAutoReplies() + 1);
            case CLIENT_REQUEST -> r.setClientRequests(r.getClientRequests() + 1);
            default -> r.setUnmatched(r.getUnmatched() + 1);
        }
        r.setFetched(r.getFetched() + 1);
    }

    static String summary(PollResultResponse r, String imapError, MailTelegramNotifier.FlushResult tg) {
        StringBuilder s = new StringBuilder();
        if (imapError != null) {
            s.append("Ошибка подключения к почте: ").append(imapError);
        } else {
            s.append("Новых писем: ").append(r.getFetched());
            List<String> parts = new ArrayList<>();
            if (r.getSupplierResponses() > 0) parts.add("ответов поставщиков — " + r.getSupplierResponses());
            if (r.getBounces() > 0) parts.add("не доставлено — " + r.getBounces());
            if (r.getAutoReplies() > 0) parts.add("автоответов — " + r.getAutoReplies());
            if (r.getClientRequests() > 0) parts.add("писем клиник — " + r.getClientRequests());
            if (r.getUnmatched() > 0) parts.add("прочих — " + r.getUnmatched());
            if (r.getBroken() > 0) parts.add("не разобрано — " + r.getBroken());
            if (!parts.isEmpty()) s.append(" (").append(String.join(", ", parts)).append(')');
            if (r.getSkippedSiteNotifications() > 0) {
                s.append("; уведомлений сайта о заявках пропущено: ").append(r.getSkippedSiteNotifications());
            }
        }
        if (tg.sent() > 0 || tg.pending() > 0 || tg.lastError() != null) {
            s.append("; Telegram: отправлено ").append(tg.sent()).append(", ждут ").append(tg.pending());
            if (tg.lastError() != null) s.append(" — ").append(tg.lastError());
        }
        return s.toString();
    }
}
```

В `src/main/resources/application.yaml`, в блок `mail.imap` после `since-minutes`, добавить:

```yaml
    # Excel без метки [КП-…] → «Письмо клиники» (ящик info@). Для ящика закупок zakup@ — false: там это прайс поставщика
    client-requests: ${MAIL_IMAP_CLIENT_REQUESTS:true}
```

и после всего блока `mail:` (на верхнем уровне):

```yaml
# Уведомления о письмах ящика в Telegram: бот @West_Med_bot сайта, группа «Заявки», тема «Почта zakup@»
# (спека docs/superpowers/specs/2026-10-05-zakup-mail-telegram-design.md §5). Токен стоит в ПУТИ адреса Bot API —
# адреса запросов нигде не печатать.
notify:
  telegram:
    enabled: ${TELEGRAM_ENABLED:false}
    api-url: ${TELEGRAM_API_URL:https://api.telegram.org}
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:}
    mail-thread-id: ${TELEGRAM_MAIL_THREAD_ID:}

# Публичный адрес АИС — для ссылок «Открыть в АИС» в уведомлениях (прод — application-prod.yaml). Пусто — без ссылок.
ais:
  public-url: ${AIS_PUBLIC_URL:http://localhost:4200}
```

В конец `src/main/resources/application-prod.yaml`:

```yaml

# Ссылки «Открыть в АИС» в уведомлениях Telegram (спека zakup-mail-telegram §5.2).
ais:
  public-url: https://ais.westmed.kz
```

- [ ] **Step 5: Юнит-тесты зелёные**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.service.MailReceiveServiceTest'`
Expected: PASS (12).

- [ ] **Step 6: Сквозные интеграционные тесты**

В `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`:
- старые тесты оставить: их ожидания верны и при новом приёме. Повторный опрос не дублирует — теперь благодаря курсору, а не отметке `SEEN`. Комментарий в строке 93 поправить: `// повторный опрос: курсор по UID → не задваивает`;
- добавить поля и подготовку — курсор ящика удаляется в транзакции теста. Живая проверка на этой базе могла оставить
  закоммиченную строку курсора, и тогда проход пошёл бы от чужого UID (откат вернёт её после теста):

```java
    @Autowired MailCursorRepository cursorRepository;
    @Autowired jakarta.persistence.EntityManager em;

    @org.junit.jupiter.api.BeforeEach
    void freshCursor() {
        cursorRepository.findById("zakup@westmed.kz").ifPresent(cursorRepository::delete);
        em.flush();
    }
```

  в `src/test/java/com/vladoose/nir/email/KpRoundTripTest.java` — то же самое (`@Autowired MailCursorRepository`,
  `@Autowired EntityManager`, тот же `@BeforeEach`);

и тесты (импорты: `com.vladoose.nir.integration.telegram.TelegramStubServer`, `com.vladoose.nir.service.mail.ImapTestSupport`, `com.vladoose.nir.service.mail.TestMimes`, `java.time.Duration`, `java.time.Instant`):

```java
    @Test
    void poll_doesNotMarkLettersSeen() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.plain("s@x.kz", "ZZSEEN письмо", "текст"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        assertThat(ImapTestSupport.seen("ZZSEEN письмо")).isFalse();
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> "ZZSEEN письмо".equals(e.getSubject()));
    }

    @Test
    void firstRun_skipsOldLetter_thenCatchesUpOldDatedLetterAfterCursor() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        ImapTestSupport.appendWithReceivedDate("ZZOLD до включения", Instant.now().minus(Duration.ofHours(3)));
        user.deliver(TestMimes.plain("s@x.kz", "ZZFRESH свежее", "текст"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();                               // первый запуск: только окно 60 мин
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> "ZZFRESH свежее".equals(e.getSubject()))
                .noneMatch(e -> "ZZOLD до включения".equals(e.getSubject()));

        ImapTestSupport.appendWithReceivedDate("ZZGAP пришло во время простоя", Instant.now().minus(Duration.ofHours(2)));
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();                               // курсор есть: окно больше не действует
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> "ZZGAP пришло во время простоя".equals(e.getSubject()));
    }

    @Test
    void newUidValidity_doesNotDuplicate() throws Exception {
        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.plain("s@x.kz", "ZZUV письмо", "текст"));
        MarketContext.set(Market.KZ);
        mailReceiveService.poll();
        long count = inboundEmailRepository.count();

        com.vladoose.nir.entity.MailCursor c = cursorRepository.findById("zakup@westmed.kz").orElseThrow();
        long realValidity = c.getUidValidity();
        c.setUidValidity(realValidity + 1000);                  // как будто ящик пересоздан
        cursorRepository.save(c);
        em.flush();

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();                               // первый запуск снова, но Message-ID уже записан

        assertThat(inboundEmailRepository.count()).isEqualTo(count);
        assertThat(cursorRepository.findById("zakup@westmed.kz").orElseThrow().getUidValidity()).isEqualTo(realValidity);
    }

    @Test
    void bounceEndToEnd_keepsRequestSent() throws Exception {
        MarketContext.set(Market.KZ);
        Distributor dist = distributorRepository.save(Distributor.builder().name("ZZBNC Дистр " + System.nanoTime()).email("b@x.kz").build());
        Tender tender = tenderRepository.save(Tender.builder().tenderNumber("ZZBNC-T1").status("NEW").source(Source.PUBLIC_TENDER).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(tender).distributor(dist).status("SENT").build());

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.dsn(KpToken.subjectToken(pr.getId()) + " Запрос КП", "b@x.kz", "550 5.1.1 User unknown"));

        MarketContext.set(Market.KZ);
        PollResultResponse res = mailReceiveService.poll();

        assertThat(res.getBounces()).isEqualTo(1);
        assertThat(priceRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> e.getType() == InboundType.BOUNCE
                && pr.getId().equals(e.getMatchedPriceRequestId()));
    }

    @Test
    void autoReplyEndToEnd_keepsRequestSent() throws Exception {
        MarketContext.set(Market.KZ);
        Distributor dist = distributorRepository.save(Distributor.builder().name("ZZAUTO Дистр " + System.nanoTime()).email("a@x.kz").build());
        Tender tender = tenderRepository.save(Tender.builder().tenderNumber("ZZAUTO-T1").status("NEW").source(Source.PUBLIC_TENDER).build());
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(tender).distributor(dist).status("SENT").build());

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(TestMimes.autoReply("a@x.kz", "Re: " + KpToken.subjectToken(pr.getId()) + " Запрос",
                "Я в отпуске до 12.10", "Auto-Submitted", "auto-replied"));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        assertThat(priceRequestRepository.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(inboundEmailRepository.findAll()).anyMatch(e -> e.getType() == InboundType.AUTO_REPLY);
    }

    @Test
    void telegramEndToEnd_sendsToThread_marksSent() throws Exception {
        em.createNativeQuery("update inbound_email set notify_status = 'SENT' where notify_status = 'PENDING'").executeUpdate();
        try (TelegramStubServer stub = TelegramStubServer.start(7798)) {
            GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
            user.deliver(TestMimes.plain("Иван <ivan@x.kz>", "ZZTG Прайс октябрь", "Высылаем прайс"));

            MarketContext.set(Market.KZ);
            PollResultResponse res = mailReceiveService.poll();

            assertThat(res.getTelegramSent()).isEqualTo(1);
            assertThat(stub.requests()).hasSize(1);
            assertThat(stub.requests().get(0).path()).isEqualTo("/bot123456:TEST-TOKEN-SECRET/sendMessage");
            assertThat(stub.requests().get(0).body()).contains("\"message_thread_id\":77")
                    .contains("✉️ Письмо на zakup@westmed.kz · Иван <ivan@x.kz>")
                    .contains("https://ais.example/inbound?market=KZ");
            em.flush();
            em.clear();
            InboundEmail row = inboundEmailRepository.findAll().stream()
                    .filter(e -> "ZZTG Прайс октябрь".equals(e.getSubject())).findFirst().orElseThrow();
            assertThat(row.getNotifyStatus()).isEqualTo(NotifyStatus.SENT);
        }
    }
```

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest' --tests 'com.vladoose.nir.email.KpRoundTripTest'`
Expected: PASS — старые 5 и новые 6 тестов MailReceiveServiceIntegrationTest плюс 2 KpRoundTripTest.

- [ ] **Step 7: Полный прогон**

Run: `./gradlew cleanTest test`
Expected: 0 падений (на 2026-10-05 было 1008 тестов, станет больше). Упало что-то вне почты — разобраться, не глушить.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/InfrastructureFailure.java src/main/java/com/vladoose/nir/integration/whatsapp/WhatsappChatSync.java \
  src/main/java/com/vladoose/nir/dto/response/PollResultResponse.java src/main/java/com/vladoose/nir/service/MailReceiveService.java \
  src/main/resources/application.yaml src/main/resources/application-prod.yaml \
  src/test/java/com/vladoose/nir/service/MailReceiveServiceTest.java src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java
git commit -m "feat(mail): проход приёма — курсор по UID, письмо на транзакцию, битое не стопорит, сбой базы — пауза

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Фронт — ссылка из Telegram и бейджи «Входящих»

**Files:**
- Create: `frontend/src/app/shared/return-url.ts`
- Modify:
  - `frontend/src/index.html`;
  - `frontend/src/app/guards/auth.guard.ts`;
  - `frontend/src/app/interceptors/auth.interceptor.ts`;
  - `frontend/src/app/pages/login/login.component.ts`;
  - `frontend/src/app/pages/inbound/inbound.component.ts`.

**Interfaces:**
- Consumes: типы писем `BOUNCE` и `AUTO_REPLY` в `GET /api/inbound` (задача 1); ссылки `…?openId=…&market=KZ|RF` (задача 4).
- Produces:
  - `safeReturnUrl(u: string | null | undefined): string | null`;
  - `/login?returnUrl=…` — вход паролем, ключом и «уже вошёл» возвращает туда;
  - `?market=` в любом адресе ставит рынок до старта Angular.

- [ ] **Step 1: Рынок из ссылки — до старта Angular**

`frontend/src/index.html` — после скрипта темы (перед `</head>`):

```html
  <!-- рынок из ссылки (?market=KZ|RF, уведомления Telegram): до старта Angular, иначе новый браузер откроется на РФ -->
  <script>
    (function(){try{var m=new URLSearchParams(location.search).get('market');
      if(m==='KZ'||m==='RF')localStorage.setItem('ais.market',m);}catch(e){}})();
  </script>
```

- [ ] **Step 2: Возврат после входа**

`frontend/src/app/shared/return-url.ts`:

```ts
/** Адрес возврата после входа: только свой путь (не //чужой-хост, не /login), иначе null. */
export function safeReturnUrl(u: string | null | undefined): string | null {
  if (!u || !u.startsWith('/') || u.startsWith('//') || u.startsWith('/\\') || u.startsWith('/login')) return null;
  return u;
}
```

`frontend/src/app/guards/auth.guard.ts` — заменить целиком:

```ts
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { safeReturnUrl } from '../shared/return-url';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isLoggedIn()) return true;
  // ссылка из уведомления (?openId=…&market=…) после входа должна открыть ту же страницу, а не главную
  const back = safeReturnUrl(state.url);
  return router.createUrlTree(['/login'], back ? { queryParams: { returnUrl: back } } : {});
};
```

`frontend/src/app/interceptors/auth.interceptor.ts`:
- добавить `import { safeReturnUrl } from '../shared/return-url';`;
- строку `router.navigate(['/login']);` в ветке «сессия истекла» заменить на:

```ts
        const back = safeReturnUrl(router.url);
        router.navigate(['/login'], back ? { queryParams: { returnUrl: back } } : {});
```

`frontend/src/app/pages/login/login.component.ts`:
- импорт: `import { ActivatedRoute, Router } from '@angular/router';` и `import { safeReturnUrl } from '../../shared/return-url';`;
- конструктор: добавить параметр `private route: ActivatedRoute` (после `private router: Router`);
- в конструкторе `this.router.navigate(['/dashboard']);` → `this.router.navigateByUrl(this.target());`;
- в `onLogin()` и в `onPasskey()` — то же самое (две строки `this.router.navigate(['/dashboard']);`);
- метод в класс:

```ts
  /** Куда после входа: страница из ссылки (returnUrl), иначе главная. */
  private target(): string {
    return safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')) ?? '/dashboard';
  }
```

- [ ] **Step 3: «Входящие» — новые бейджи и подзаголовок**

`frontend/src/app/pages/inbound/inbound.component.ts`:
- подзаголовок `<p class="sub">…</p>` → `<p class="sub">Почта АИС: ответы поставщиков на запросы КП, возвраты и автоответы, письма клиник с таблицами, прочее.</p>`;
- бейдж типа — добавить классы и показывать «КП #» у трёх типов:

```html
            <span class="badge" [class.b-sup]="r.type==='SUPPLIER_RESPONSE'"
                  [class.b-cli]="r.type==='CLIENT_REQUEST'" [class.b-unm]="r.type==='UNMATCHED'"
                  [class.b-bounce]="r.type==='BOUNCE'" [class.b-auto]="r.type==='AUTO_REPLY'">
              {{ typeLabel(r.type) }}
            </span>
            <span *ngIf="(r.type==='SUPPLIER_RESPONSE' || r.type==='BOUNCE' || r.type==='AUTO_REPLY') && r.matchedPriceRequestId"
                  class="muted"> · КП #{{ r.matchedPriceRequestId }}</span>
```

- стили — рядом с `.b-unm` (правила kit: чип — `color-mix(… 15%, transparent)` + текстовый токен):

```scss
    .b-bounce { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .b-auto { background: var(--surface-2); color: var(--text-muted); font-style: italic; }
```

- `typeLabel`:

```ts
  typeLabel(t: string): string {
    return t === 'SUPPLIER_RESPONSE' ? 'Ответ поставщика'
      : t === 'CLIENT_REQUEST' ? 'Письмо клиники'
      : t === 'BOUNCE' ? 'Не доставлено'
      : t === 'AUTO_REPLY' ? 'Автоответ' : 'Прочее';
  }
```

- [ ] **Step 4: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка без ошибок; предупреждения бюджета — те же, что до правки (начальный бандл ~1,38 МБ).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/index.html frontend/src/app/shared/return-url.ts \
  frontend/src/app/guards/auth.guard.ts frontend/src/app/interceptors/auth.interceptor.ts \
  frontend/src/app/pages/login/login.component.ts frontend/src/app/pages/inbound/inbound.component.ts
git commit -m "feat(mail): ссылка из Telegram открывает нужную страницу на нужном рынке; бейджи «Не доставлено», «Автоответ»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Живая проверка локально и документация

**Files:**
- Create: `scripts/telegram-stub.mjs`, `scripts/dev-mail.py`, `src/test/java/com/vladoose/nir/mail/DevMailServer.java` (не тест)
- Modify: `build.gradle` (задача `devMail`), `.env.example`, `DEPLOY.md`, `CLAUDE.md`, `docs/PROGRESS.md`

**Interfaces:**
- Consumes: всё выше.
- Produces:
  - `./gradlew devMail` — GreenMail: SMTP 127.0.0.1:3025, IMAP 127.0.0.1:3143, ящик `zakup@westmed.kz` / `secret`;
  - `node scripts/telegram-stub.mjs` — заглушка Bot API на 127.0.0.1:7709, `GET /__messages`;
  - `python3 scripts/dev-mail.py plain|reply <prId>|bounce <prId>|auto <prId>` — письма в GreenMail.

- [ ] **Step 1: Инструменты живой проверки**

`src/test/java/com/vladoose/nir/mail/DevMailServer.java`:

```java
package com.vladoose.nir.mail;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

/**
 * НЕ тест: локальный почтовый сервер для живой проверки приёма (`./gradlew devMail`). SMTP 127.0.0.1:3025,
 * IMAP 127.0.0.1:3143, ящик zakup@westmed.kz / secret. Письма — `python3 scripts/dev-mail.py …`.
 */
public final class DevMailServer {

    public static void main(String[] args) throws Exception {
        GreenMail mail = new GreenMail(ServerSetupTest.SMTP_IMAP);
        mail.start();
        mail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        System.out.println("GreenMail: SMTP 127.0.0.1:3025, IMAP 127.0.0.1:3143, zakup@westmed.kz / secret");
        Thread.currentThread().join();
    }
}
```

`build.gradle` — после `tasks.named('test') { … }`:

```groovy
// Локальная почта для живой проверки приёма (не тест): GreenMail SMTP 3025 / IMAP 3143, ящик zakup@westmed.kz / secret
tasks.register('devMail', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.vladoose.nir.mail.DevMailServer'
}
```

`scripts/telegram-stub.mjs`:

```js
// Заглушка Telegram Bot API для живой проверки уведомлений о почте (слушает только 127.0.0.1:7709).
// sendMessage → {ok:true}; GET /__messages — что пришло; POST /__fail/<код> — следующий ответ с этим кодом
// (429 — с retry_after 30). Токен из пути НЕ печатается.
import http from 'node:http';

const messages = [];
let failNext = null;
let nextId = 1;

http.createServer((req, res) => {
  let body = '';
  req.on('data', c => { body += c; });
  req.on('end', () => {
    const path = req.url.replace(/\/bot[^/]+\//, '/bot***/');
    if (req.method === 'GET' && req.url === '/__messages') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(messages, null, 2));
    }
    const fail = req.url.match(/^\/__fail\/(\d{3})$/);
    if (req.method === 'POST' && fail) {
      failNext = Number(fail[1]);
      res.writeHead(200);
      return res.end('ok');
    }
    if (req.method === 'POST' && /\/bot[^/]+\/sendMessage$/.test(req.url)) {
      if (failNext) {
        const code = failNext;
        failNext = null;
        const payload = { ok: false, error_code: code, description: 'stub failure ' + code };
        if (code === 429) payload.parameters = { retry_after: 30 };
        res.writeHead(code, { 'Content-Type': 'application/json' });
        console.log(new Date().toISOString(), path, '→', code);
        return res.end(JSON.stringify(payload));
      }
      const m = JSON.parse(body || '{}');
      messages.push({ at: new Date().toISOString(), chat_id: m.chat_id, thread: m.message_thread_id,
        silent: !!m.disable_notification, text: m.text });
      console.log(new Date().toISOString(), path, 'thread', m.message_thread_id, m.disable_notification ? '(тихо)' : '(звук)');
      console.log(m.text + '\n');
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ ok: true, result: { message_id: nextId++ } }));
    }
    res.writeHead(404);
    res.end();
  });
}).listen(7709, '127.0.0.1', () => console.log('Telegram stub: http://127.0.0.1:7709 (GET /__messages)'));
```

`scripts/dev-mail.py`:

```python
#!/usr/bin/env python3
"""Письма в локальный GreenMail (./gradlew devMail) для живой проверки приёма zakup@:
  python3 scripts/dev-mail.py plain            — письмо с вложением (прочее)
  python3 scripts/dev-mail.py reply <prId>     — ответ поставщика с ценой на запрос КП
  python3 scripts/dev-mail.py bounce <prId>    — возврат почтового сервера (DSN) по запросу КП
  python3 scripts/dev-mail.py auto <prId>      — автоответ на запрос КП
"""
import smtplib
import sys
from email.message import EmailMessage
from email.mime.base import MIMEBase
from email.mime.message import MIMEMessage
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

TO = "zakup@westmed.kz"


def send(msg):
    with smtplib.SMTP("127.0.0.1", 3025) as s:
        s.send_message(msg)
    print("отправлено:", msg["Subject"])


def plain():
    m = EmailMessage()
    m["From"] = "Иван Петров <ivan@medtech.kz>"
    m["To"] = TO
    m["Subject"] = "Прайс-лист на октябрь"
    m.set_content("Добрый день!\nВысылаем актуальный прайс.\n\nС уважением, Иван")
    m.add_attachment(b"%PDF-1.4 stub", maintype="application", subtype="pdf", filename="Прайс октябрь.pdf")
    send(m)


def reply(pr_id):
    m = EmailMessage()
    m["From"] = "Отдел продаж <sales@medtech.kz>"
    m["To"] = TO
    m["Subject"] = f"Re: [КП-{pr_id}] Запрос коммерческого предложения"
    m.set_content("Добрый день!\nЦена 3 450 000 тг, срок поставки 30 дней.\n\n> Здравствуйте! Просим КП")
    send(m)


def bounce(pr_id):
    report = MIMEMultipart("report", report_type="delivery-status")
    report["From"] = "Mail Delivery System <MAILER-DAEMON@corp.mail.ru>"
    report["To"] = TO
    report["Subject"] = "Undelivered Mail Returned to Sender"
    report.attach(MIMEText("Your message could not be delivered.", "plain", "utf-8"))
    status = MIMEBase("message", "delivery-status")
    status.set_payload("Reporting-MTA: dns; mx.mail.ru\n\nFinal-Recipient: rfc822; nobody@medtech.kz\n"
                       "Action: failed\nStatus: 5.1.1\nDiagnostic-Code: smtp; 550 5.1.1 User unknown\n")
    report.attach(status)
    original = EmailMessage()
    original["From"] = TO
    original["To"] = "nobody@medtech.kz"
    original["Subject"] = f"[КП-{pr_id}] Запрос коммерческого предложения"
    original.set_content("Здравствуйте! Просим КП.")
    report.attach(MIMEMessage(original))
    send(report)


def auto(pr_id):
    m = EmailMessage()
    m["From"] = "Отдел продаж <sales@medtech.kz>"
    m["To"] = TO
    m["Subject"] = f"Автоматический ответ: Re: [КП-{pr_id}] Запрос коммерческого предложения"
    m["Auto-Submitted"] = "auto-replied"
    m.set_content("Я в отпуске до 12.10. По срочным вопросам — +7 700 000 00 00.")
    send(m)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "plain":
        plain()
    elif cmd in ("reply", "bounce", "auto") and len(sys.argv) > 2:
        {"reply": reply, "bounce": bounce, "auto": auto}[cmd](sys.argv[2])
    else:
        print(__doc__)
        sys.exit(1)
```

- [ ] **Step 2: Живой прогон (всё локально — в настоящую группу ничего не уходит)**

Запуск (каждое — в фоне, `run_in_background`; `./gradlew` — sandbox off):
1. `./gradlew devMail`
2. `node scripts/telegram-stub.mjs`
3. бэкенд:
```bash
MAIL_IMAP_ENABLED=true MAIL_IMAP_HOST=127.0.0.1 MAIL_IMAP_PORT=3143 MAIL_IMAP_PROTOCOL=imap \
MAIL_IMAP_USERNAME=zakup@westmed.kz MAIL_IMAP_PASSWORD=secret MAIL_IMAP_MARKET=KZ MAIL_IMAP_CLIENT_REQUESTS=false \
MAIL_IMAP_POLL_MS=15000 MAIL_IMAP_INITIAL_DELAY_MS=3000 \
TELEGRAM_ENABLED=true TELEGRAM_API_URL=http://127.0.0.1:7709 TELEGRAM_BOT_TOKEN=dev:token \
TELEGRAM_CHAT_ID=-1001 TELEGRAM_MAIL_THREAD_ID=77 JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun
```
4. `cd frontend && npm start`

Подготовка:
- три KZ-запроса КП в статусе SENT (по одному на ответ, возврат и автоответ):
```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c \
  "select pr.id, pr.tender_id, t.tender_number from price_request pr join tender t on t.id = pr.tender_id where pr.market = 'KZ' and pr.status in ('SENT','CREATED') order by pr.id desc limit 3;"
```
  Записать их id и исходные статусы — после проверки статусы вернуть.

Письма:
- `python3 scripts/dev-mail.py plain`;
- `python3 scripts/dev-mail.py reply <id1>`;
- `python3 scripts/dev-mail.py bounce <id2>`;
- `python3 scripts/dev-mail.py auto <id3>`.

Проверить:
1. За ≤ 30 с `curl -s 127.0.0.1:7709/__messages` — четыре сообщения:
   - ✉️ — тихое, «Вложения: Прайс октябрь.pdf»;
   - 📩 — со звуком, «💡 Цена распознана: 3 450 000,00 ₸»;
   - ⚠️ — со звуком, «(nobody@medtech.kz)», «Причина: 550 5.1.1 User unknown»;
   - 🤖 — тихое;
   - у всех `thread: 77`, ссылки `http://localhost:4200/…&market=KZ`.
2. В логе бэкенда ни разу нет `dev:token`: `grep -c "dev:token"` по выводу бэкенда — 0.
3. В ящике письма не прочитаны:
```bash
python3 -c "import imaplib;M=imaplib.IMAP4('127.0.0.1',3143);M.login('zakup@westmed.kz','secret');M.select('INBOX',readonly=True);print(M.search(None,'UNSEEN'))"
```
   — четыре номера.
4. Браузер (Playwright, KZ, admin):
   - «Входящие» — бейджи «Прочее», «Ответ поставщика», «Не доставлено», «Автоответ»; «КП #…» у трёх;
   - 1280 и 390, светлая и тёмная тема;
   - «Проверить почту» — тост со сводкой («Новых писем: 0…» или «…; Telegram: …»).
5. Ссылка из уведомления:
   - выйти; `localStorage.setItem('ais.market','RF')`;
   - открыть ссылку 📩 из `/__messages` (`http://localhost:4200/tenders?openId=…&market=KZ`) → страница входа → вход → открыта карточка ЭТОГО тендера, рынок KZ;
   - то же для ссылки ✉️ → «Входящие» KZ.
6. Запрос КП `<id1>` — «Ответ получен» с ценой 3 450 000; `<id2>` и `<id3>` — статус не изменился.
7. `curl -s -X POST 127.0.0.1:7709/__fail/429`, потом `python3 scripts/dev-mail.py plain` → в логе бэкенда предупреждение без токена. Через 30 с письмо уходит само (повтор после паузы).

Уборка (только dev-база):
```sql
delete from inbound_email where mailbox = 'zakup@westmed.kz' and imap_uid is not null;
delete from mail_cursor where mailbox = 'zakup@westmed.kz';
```
- статусы и цены запросов КП `<id1..3>` вернуть к записанным: `update price_request set status = …, response_date = null, note = null where id = …;` и `update price_request_item set response_price = null, response_note = null where price_request_id = <id1>;`;
- остановить процессы: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`, `lsof -ti tcp:4200 -sTCP:LISTEN | xargs kill`, `lsof -ti tcp:7709 -sTCP:LISTEN | xargs kill`, `lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`.

Найденное живой проверкой — исправить отдельным коммитом с тестом, который до исправления был красным.

- [ ] **Step 3: Документация**

`.env.example`:
- блок IMAP: `MAIL_IMAP_ENABLED=false` оставить; `MAIL_IMAP_SINCE_MINUTES=60` — комментарий «окно первого запуска»; добавить `MAIL_IMAP_POLL_MS=60000` и `MAIL_IMAP_CLIENT_REQUESTS=false   # zakup@ — Excel без метки это прайс поставщика, не письмо клиники`;
- новый блок:
```
# --- Уведомления о письмах zakup@ в Telegram (группа «Заявки», тема «Почта zakup@»; DEPLOY.md §8) ---
TELEGRAM_ENABLED=false
TELEGRAM_BOT_TOKEN=CHANGE_ME_bot_token          # тот же бот @West_Med_bot, что у сайта (app_settings.telegram_bot_token)
TELEGRAM_CHAT_ID=-1004352219740                 # группа «Заявки»
TELEGRAM_MAIL_THREAD_ID=CHANGE_ME_topic_id      # id темы «Почта zakup@» (из ссылки https://t.me/c/4352219740/<id>)
```

`DEPLOY.md` — новый раздел `## 8. Почта zakup@ и Telegram` по спеке §10:
- включение: тема в группе → id темы → `.env` (`MAIL_IMAP_*`, `TELEGRAM_*`; токен — из `app_settings` БД сайта, без вывода на экран) → `docker compose up -d --force-recreate ais-backend`;
- приёмка;
- откат: `TELEGRAM_ENABLED=false` или `MAIL_IMAP_ENABLED=false` + `--force-recreate`;
- проверка очереди: `docker compose exec -T ais-postgres psql -U "$POSTGRES_USER" nirdb -c "select notify_status, count(*) from inbound_email group by 1"`;
- предупреждения: токен бота общий с сайтом (перевыпустили на сайте — обновить `.env` АИС); встроенный браузер Telegram — новое устройство для калитки.

`CLAUDE.md`:
- §5 — абзац «Почта zakup@ и Telegram — локально»: `./gradlew devMail`, `node scripts/telegram-stub.mjs`, `scripts/dev-mail.py`, команда бэкенда из шага 2;
- §8 — блок «Почта zakup@ → Telegram «Заявки» (2026-10-05)»: механика из спеки §3–§6, что проверено;
- §9 — заменить устаревшее «Помечает SEEN» и «Гард since-minutes» описанием курсора и режима только-чтение; добавить возврат и автоответ; `MAIL_IMAP_CLIENT_REQUESTS`;
- §13 — число тестов после ветки;
- §14 — уроки, если живая проверка их дала;
- §15 — `PollResultResponse` (новые поля);
- §16 — что осталось (спека §11, §12); номер миграции: V24 занята этим блоком, сид словаря заголовков волны 2 КП — **V25**.

`docs/PROGRESS.md`: раздел «▶ Последняя задача» — этот блок, что ждёт оператора (спека §10, шаги 1, 2, 4, 5), где спека и план.

- [ ] **Step 4: Полный гейт и commit**

Run (sandbox off): `./gradlew cleanTest test` → 0 падений; `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` → без ошибок.

```bash
cd /Users/vlad/IdeaProjects/AIS && git add scripts/telegram-stub.mjs scripts/dev-mail.py \
  src/test/java/com/vladoose/nir/mail/DevMailServer.java build.gradle .env.example DEPLOY.md CLAUDE.md docs/PROGRESS.md
git commit -m "docs(mail): почта zakup@ в Telegram — живая проверка, раскатка, откат; инструменты dev-почты

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## После задач

1. **Финальное ревью всей ветки** (Opus). Найденное исправить; каждый новый тест до исправления должен быть красным.
2. **Мутации по списку спеки §9**, по одной, откат — копией файла:
   - `READ_ONLY` → `READ_WRITE` **вместе с** `peek=false` (только оба сразу роняют `readOnly_fetchDoesNotMarkSeen`: каждая защита страхует другую);
   - не сдвигать курсор;
   - убрать распознавание возврата;
   - убрать распознавание автоответа;
   - поменять звук местами;
   - пачка не останавливается на сбое.

   Затем `./gradlew compileJava` (§14) и полный `./gradlew cleanTest test`.
3. **Мерж в `main`** (`--ff-only`), ветку удалить. Push — оператор: `! git push origin main`.
4. **Раскатка — спека §10:**
   - тема и ссылка;
   - пароль приложения;
   - по желанию — проход против настоящего zakup@ только на чтение (Telegram — заглушка);
   - push;
   - `.env` и `--force-recreate` с согласия оператора на SSH;
   - приёмка.
