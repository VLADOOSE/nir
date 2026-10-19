# Письма клиентам из АИС (info@) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **В этом проекте — superpowers:executing-plans, всё в основной сессии, без субагентов** (CLAUDE.md §2, решение оператора 2026-10-18).

**Goal:** Администратор отвечает клиенту письмом с info@ прямо из АИС (на «Чатах», в карточке обращения, на странице заявки, из редактора КП): «Re:» в цепочке клиента, шаблоны ответов, подпись с ФИО, файлы и КП во вложении; письмо сразу в переписке и ленте сделки, копия — в «Отправленных» info@, КП само становится «Отправлено».

**Architecture:** Свой SMTP ящика клиентов (`ClientSmtpSettings` + `ClientMailSender`) рядом с приёмом info@; письмо собирает чистая функция `ClientLetterBuilder` (наш Message-ID заранее — он же ключ сообщения в переписке, приём «Отправленных» встречает копию и пропускает её как повтор). `ClientMailSendService` проверяет ввод, рендерит КП, отправляет, кладёт копию (`ClientSentCopy` — тот же `SentCopy`), записывает через `ClientLetterRecorder` (одна транзакция: переписка через `EmailChatWriter.writeOutgoing`, правила обращения, событие сделки `EMAIL`, событие обращения, КП → «Отправлено»). Данные окна — `ClientMailComposeService`. Шаблоны и подпись — таблица `reply_template` (V35) и страница «Система → Шаблоны ответов». Фронт — ленивое окно `app-email-composer` у четырёх хозяев внутри `@defer`.

**Tech Stack:** Java 17, Spring Boot 3.5.6 (Spring Mail, Spring Data JPA, method security), Jakarta Mail, Flyway, PostgreSQL 17, GreenMail 2.1.2 в тестах; Angular 21 (standalone, инлайн-шаблоны, `@defer`).

**Spec:** `docs/superpowers/specs/2026-10-19-client-mail-reply-design.md` (§13 — решения при планировании).

## Global Constraints

- Миграция — **V35** (следующая свободная); старые миграции не трогать.
- Новые сервисы — в пакете `com.vladoose.nir.service` (там пакетный `ChatService.chat(id)` с гардом рынка); почтовые помощники — в `com.vladoose.nir.service.mail`.
- Переписка для ответа — только почтовая (`channel = EMAIL`), только ящика `mail.clients.username`, только текущего рынка — иначе 404. Обращение — `LeadService.get` (404 чужого рынка), заявка — `PrivateRequestService.findById` (404 чужого рынка и вида), КП — `ClientOfferService.get` (404 чужого рынка).
- Запись и данные окна — `@PreAuthorize("hasRole('ADMIN')")` (`/api/client-mail/**`, `/api/reply-templates`).
- Ввод проверяется до отправки письма; отказ — `BadRequestException` (400) с русским текстом. Сбой SMTP — `UpstreamException` (502) со своим текстом, ничего не записано. Письмо ушло, запись упала — **200** `{recorded: false, warning}` (решение при планировании 1).
- Пределы: текст ≤ 20 000; тема ≤ 255; адресов ≤ 10; файлов ≤ 10; всё письмо ≤ 20 МБ; название шаблона ≤ 60; текст шаблона ≤ 4000; подпись ≤ 1000; шаблонов на рынок ≤ 30.
- Всё, что АИС пишет клиенту сама (тема письма с КП, строка цитаты, подпись по умолчанию), — без длинного тире «—»: где нужно тире — `DocFormat.DASH` («–»).
- Журнал — классы исключений и числа; адресов и текстов писем в журнале нет.
- Фронт: окно письма — только внутри `@defer (when …)` у каждого хозяина («Чаты» и «Обращения» — в начальном бандле, 1,39 МБ при пределе ошибки 1,5 МБ); `ApiService` берёт типы через `import type`; токены UI-kit, обе темы, цели 40 px при ≤ 900 px; классы компонентов — с приставкой (`ec-`, `rt-`), не совпадают с классами UI-kit; объекты-входы в шаблоне — литералами (`{ chatId: openId! }`); подтверждения внутри окна письма — строкой в окне, не `ConfirmService` (его окно — z-index 1000, под модальным окном 1100).
- `./gradlew` — с `dangerouslyDisableSandbox: true`; перед полным прогоном глушить `bootRun` и `devMail` (CLAUDE.md §13); полный прогон — `./gradlew cleanTest test`.
- Ветка `feature/client-mail-reply`; каждый коммит заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Мутация — откат копией файла (`cp файл /tmp/x && … && cp /tmp/x файл && diff -q`), не `git checkout --`; серию мутаций заканчивать `./gradlew compileJava`.

## Review Focus

1. **Двойное нажатие «Отправить» / повтор запроса после обрыва связи** — второе письмо клиенту не уходит: тот же `token` окна → 409, письмо одно → тест `sameTokenTwice_secondIs409_oneLetterDelivered` (задача 6).
2. **Адрес в другом регистре** («Client@Clinic.KZ») — та же переписка, что у «client@clinic.kz», второй не заводится → тест `mixedCaseAddress_sameChat` (задача 4).
3. **Время в строке цитаты** — по часам рынка: письмо 20:30 UTC цитируется как «01:30» следующего дня по Уральску → тест `attributionUsesMarketZone` (задача 1).
4. **Письмо коллеге** (свой домен westmed.kz) — уходит, переписка не заводится, ответ без ошибки → тест `ownDomainRecipient_sentWithoutChat` (задача 6).
5. **Ответ на письмо без Message-ID** (ключ `uid:…` или `sha256:…`) — «Re:» и цитата есть, заголовков цепочки нет → тест `replyToLetterWithoutMessageId_noThreadHeaders` (задача 6).

## Карта файлов

Сервер (`src/main/java/com/vladoose/nir/…`):

| Файл | Что |
|---|---|
| `service/mail/ClientSmtpSettings.java` | SMTP info@: хост, порт, SSL, вход, рынок, имя «От», копия; `problem(market)` |
| `service/mail/ClientLetter.java` | письмо: от, кому, тема, текст, подпись, цитата, In-Reply-To, Message-ID, вложения |
| `service/mail/ClientLetterBuilder.java` | чистые функции: тема «Re:», текст с подписью и цитатой, Message-ID, MIME |
| `service/mail/ClientMailSender.java` | `JavaMailSenderImpl` ящика клиентов, тексты сбоев |
| `service/mail/ClientSentCopy.java` | копия в «Отправленные» info@ (обёртка `SentCopy`) |
| `resources/db/migration/V35__reply_templates.sql` | `reply_template` + сид KZ |
| `entity/ReplyTemplate.java`, `entity/ReplyTemplateRole.java` | шаблон / подпись |
| `repository/ReplyTemplateRepository.java` | шаблоны рынка по порядку |
| `dto/response/ReplyTemplateDto.java`, `ReplyTemplatesResponse.java`, `dto/request/ReplyTemplatesRequest.java` | API шаблонов |
| `service/ReplyTemplateService.java`, `controller/ReplyTemplateController.java` | шаблоны, подпись с ФИО, `GET/PUT /api/reply-templates` |
| `service/ClientLetterRecorder.java` | запись отправленного письма (одна транзакция) |
| `service/ClientMailComposeService.java`, `dto/response/ClientMailComposeResponse.java` | данные окна письма |
| `service/ClientMailSendService.java`, `dto/request/ClientMailSendRequest.java`, `dto/response/ClientMailSendResponse.java` | отправка |
| `controller/ClientMailController.java` | `GET /api/client-mail/compose`, `POST /api/client-mail/send` |
| изменяются: `entity/DealEventType.java`, `repository/DealEventRepository.java`, `service/deal/DealEventService.java`, `service/LeadService.java`, `service/EmailChatWriter.java`, `service/deal/DealTimelineService.java`, `resources/application.yaml`, `.env.example`, `deploy/set-info-smtp.sh` (новый), `DEPLOY.md` | |

Фронт (`frontend/src/app/…`):

| Файл | Что |
|---|---|
| `shared/client-mail.ts` | типы окна, отправки и шаблонов |
| `shared/email-composer.component.ts` | окно письма (ленивое) |
| `pages/reply-templates/reply-templates.component.ts` | «Система → Шаблоны ответов» |
| изменяются: `services/api.service.ts`, `pages/chats/chats.component.ts`, `shared/chat-message.component.ts`, `pages/leads/lead-card.component.ts`, `shared/deal-timeline.component.ts`, `pages/private-requests/private-request-page.component.ts`, `pages/client-offers/client-offer-editor.component.ts`, `app.routes.ts`, `layout/layout.component.ts`, `app.config.ts` | |

Тесты: чистые — `src/test/java/com/vladoose/nir/service/mail/`; интеграционные — `src/test/java/com/vladoose/nir/clientmail/`; лента — `src/test/java/com/vladoose/nir/deal/`.

---

### Task 1: SMTP-настройки info@ и сборка письма (чистые функции)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientSmtpSettings.java`
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientLetter.java`
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientLetterBuilder.java`
- Modify: `src/main/resources/application.yaml` (блок `mail.clients`)
- Test: `src/test/java/com/vladoose/nir/service/mail/ClientLetterBuilderTest.java`, `src/test/java/com/vladoose/nir/service/mail/ClientSmtpSettingsTest.java`

**Interfaces:**
- Produces: `ClientSmtpSettings(String host, int port, boolean ssl, String username, String password, String market, String fromName, boolean sentCopy)` — `enabled()`, `problem(Market)`, геттеры `host() port() ssl() username() password() market() fromName() sentCopy()`, константа `NOT_CONFIGURED`; `record ClientLetter(String fromName, String fromAddress, List<String> to, String subject, String text, String signature, Quote quote, String inReplyTo, String messageId, List<Attachment> attachments)` с `record Quote(OffsetDateTime sentAt, String name, String address, String body)` и `record Attachment(String fileName, byte[] bytes, String contentType)`; `ClientLetterBuilder.replySubject(String, OffsetDateTime, ZoneId)`, `body(String text, String signature, Quote, ZoneId)`, `attribution(Quote, ZoneId)` (пакетный), `quoted(String)` (пакетный), `messageId(String mailbox)`, `isMessageId(String key)`, `build(Session, ClientLetter, ZoneId) throws MessagingException`.

- [ ] **Step 1: Написать падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/ClientLetterBuilderTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.util.EmailReplyText;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Письмо клиенту с info@ (спека 2026-10-19-client-mail-reply §3.2). */
class ClientLetterBuilderTest {

    static final ZoneId ORAL = ZoneId.of("Asia/Oral");
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-19T05:15:00Z");   // 10:15 по Уральску
    final Session session = Session.getInstance(new Properties());

    @Test
    void replySubject_reOnce_emptySubjectGetsDate() {
        assertThat(ClientLetterBuilder.replySubject("Запрос цены", AT, ORAL)).isEqualTo("Re: Запрос цены");
        assertThat(ClientLetterBuilder.replySubject("RE: Запрос", AT, ORAL)).isEqualTo("RE: Запрос");
        assertThat(ClientLetterBuilder.replySubject("Ответ: Запрос", AT, ORAL)).isEqualTo("Ответ: Запрос");
        assertThat(ClientLetterBuilder.replySubject("Return policy", AT, ORAL)).isEqualTo("Re: Return policy");
        assertThat(ClientLetterBuilder.replySubject("  ", AT, ORAL)).isEqualTo("Re: письмо от 19.10.2026");
        assertThat(ClientLetterBuilder.replySubject(null, AT, ORAL)).isEqualTo("Re: письмо от 19.10.2026");
    }

    @Test
    void attributionUsesMarketZone() {   // Review Focus 3
        OffsetDateTime late = OffsetDateTime.parse("2026-10-19T20:30:00Z");   // 01:30 20.10 по Уральску (+05)
        assertThat(ClientLetterBuilder.attribution(new ClientLetter.Quote(late, "Айгерим Касымова", "a@clinic.kz", "x"), ORAL))
                .isEqualTo("20.10.2026 01:30, Айгерим Касымова <a@clinic.kz> пишет:");
        assertThat(ClientLetterBuilder.attribution(new ClientLetter.Quote(late, null, "a@clinic.kz", "x"), ORAL))
                .isEqualTo("20.10.2026 01:30, a@clinic.kz пишет:");
    }

    @Test
    void body_textSignatureQuote_crlfNormalized() {
        ClientLetter.Quote q = new ClientLetter.Quote(AT, "Айгерим", "a@clinic.kz", "Нужны кушетки.\r\n\r\nСпасибо");
        String b = ClientLetterBuilder.body("Здравствуйте!\r\n\r\nКП во вложении.  \n", "С уважением,\nИван Петров\nWest-Med", q, ORAL);
        assertThat(b).isEqualTo("Здравствуйте!\n\nКП во вложении.\n\nС уважением,\nИван Петров\nWest-Med\n\n"
                + "19.10.2026 10:15, Айгерим <a@clinic.kz> пишет:\n> Нужны кушетки.\n>\n> Спасибо");
    }

    @Test
    void body_withoutSignatureAndQuote_isText() {
        assertThat(ClientLetterBuilder.body(" Текст ", "  ", null, ORAL)).isEqualTo("Текст");
    }

    @Test
    void ourQuote_isCutByOurReplyParser() {
        // клиент ответит над нашей цитатой — приём режет его ответ по нашей же строке цитаты
        String b = ClientLetterBuilder.body("Ответ клиента", "", new ClientLetter.Quote(AT, "West-Med", "info@westmed.kz", "Наш вопрос"), ORAL);
        assertThat(EmailReplyText.cutQuote(b).strip()).isEqualTo("Ответ клиента");
    }

    @Test
    void messageId_ofMailboxDomain() {
        assertThat(ClientLetterBuilder.messageId("info@westmed.kz")).matches("<ais\\.[0-9a-f-]{36}@westmed\\.kz>");
        assertThat(ClientLetterBuilder.isMessageId("<a.b@mail.ru>")).isTrue();
        assertThat(ClientLetterBuilder.isMessageId("sha256:abc")).isFalse();
        assertThat(ClientLetterBuilder.isMessageId("uid:inbox:7:42")).isFalse();
        assertThat(ClientLetterBuilder.isMessageId(null)).isFalse();
    }

    @Test
    void build_headersFromToThreadAndAttachments_messageIdSurvivesSaveChanges() throws Exception {
        String id = ClientLetterBuilder.messageId("info@westmed.kz");
        ClientLetter l = new ClientLetter("West-Med", "info@westmed.kz", List.of("a@clinic.kz", "b@clinic.kz"),
                "Re: Запрос", "Текст", "С уважением,\nWest-Med", null, "<client-1@mail.ru>", id,
                List.of(new ClientLetter.Attachment("КП № 24 от 19.10.2026 – West-Med.pdf", new byte[]{1, 2}, "application/pdf")));

        MimeMessage m = ClientLetterBuilder.build(session, l, ORAL);

        assertThat(m.getMessageID()).isEqualTo(id);
        assertThat(m.getHeader("In-Reply-To", null)).isEqualTo("<client-1@mail.ru>");
        assertThat(m.getHeader("References", null)).isEqualTo("<client-1@mail.ru>");
        InternetAddress from = (InternetAddress) m.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("info@westmed.kz");
        assertThat(from.getPersonal()).isEqualTo("West-Med");
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly("a@clinic.kz", "b@clinic.kz");
        assertThat(m.getSubject()).isEqualTo("Re: Запрос");
        assertThat(fileNames(m)).containsExactly("КП № 24 от 19.10.2026 – West-Med.pdf");
        m.saveChanges();   // как JavaMailSender перед отправкой
        assertThat(m.getMessageID()).isEqualTo(id);
    }

    @Test
    void build_withoutParentId_noThreadHeaders_plainText() throws Exception {
        ClientLetter l = new ClientLetter("West-Med", "info@westmed.kz", List.of("a@clinic.kz"), "Счёт", "Текст", "",
                null, null, ClientLetterBuilder.messageId("info@westmed.kz"), List.of());
        MimeMessage m = ClientLetterBuilder.build(session, l, ORAL);
        assertThat(m.getHeader("In-Reply-To")).isNull();
        assertThat(m.getHeader("References")).isNull();
        assertThat(m.isMimeType("text/plain")).isTrue();
        assertThat(((String) m.getContent())).isEqualTo("Текст");
    }

    static List<String> fileNames(Part p) throws Exception {
        List<String> out = new ArrayList<>();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) out.addAll(fileNames(mp.getBodyPart(i)));
        } else if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            out.add(MimeUtility.decodeText(p.getFileName()));
        }
        return out;
    }
}
```

`src/test/java/com/vladoose/nir/service/mail/ClientSmtpSettingsTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SMTP ящика клиентов (спека 2026-10-19-client-mail-reply §3.1). */
class ClientSmtpSettingsTest {

    static ClientSmtpSettings s(String host, String user, String password, String market) {
        return new ClientSmtpSettings(host, 465, true, user, password, market, "", true);
    }

    @Test
    void enabled_onlyWithHostAddressPasswordAndMarket() {
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "p", "KZ").enabled()).isTrue();
        assertThat(s("", "info@westmed.kz", "p", "KZ").enabled()).isFalse();
        assertThat(s("smtp.mail.ru", "info", "p", "KZ").enabled()).isFalse();
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "", "KZ").enabled()).isFalse();
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "p", "XX").enabled()).isFalse();   // опечатка — не RF
    }

    @Test
    void problem_notConfigured_otherMarket_ok() {
        assertThat(s("", "info@westmed.kz", "p", "KZ").problem(Market.KZ)).isEqualTo(ClientSmtpSettings.NOT_CONFIGURED);
        assertThat(s("smtp.mail.ru", "info@westmed.kz", "p", "KZ").problem(Market.RF))
                .isEqualTo("Письма клиентам — только на рынке KZ (ящик info@westmed.kz)");
        assertThat(s("smtp.mail.ru", " Info@WestMed.kz ", "p", "KZ").problem(Market.KZ)).isNull();
    }

    @Test
    void username_lowercased_fromNameDefault() {
        ClientSmtpSettings x = s("smtp.mail.ru", " Info@WestMed.kz ", "p", "kz");
        assertThat(x.username()).isEqualTo("info@westmed.kz");
        assertThat(x.fromName()).isEqualTo("West-Med");
        assertThat(x.market()).isEqualTo(Market.KZ);
    }
}
```

- [ ] **Step 2: Прогнать — красные (нет классов)**

Run: `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientLetterBuilderTest' --tests 'com.vladoose.nir.service.mail.ClientSmtpSettingsTest'` (sandbox off)
Expected: FAIL — компиляция: `cannot find symbol ClientLetterBuilder`, `ClientSmtpSettings`.

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/service/mail/ClientSmtpSettings.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Отправка писем клиентам с ящика info@ (спека 2026-10-19-client-mail-reply §3.1): свой SMTP, вход — тот же, что у приёма
 * (mail.clients.username / password), рынок — mail.clients.market. Пустой хост — отправка выключена; от включения приёма
 * (mail.clients.enabled) не зависит. Почта поставщикам (spring.mail.*, zakup@) не меняется.
 */
@Component
public class ClientSmtpSettings {

    private static final Logger log = LoggerFactory.getLogger(ClientSmtpSettings.class);
    public static final String NOT_CONFIGURED = "Отправка писем клиентам не настроена";

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String username;
    private final String password;
    private final Market market;
    private final String fromName;
    private final boolean sentCopy;

    public ClientSmtpSettings(@Value("${mail.clients.smtp.host:}") String host,
                              @Value("${mail.clients.smtp.port:465}") int port,
                              @Value("${mail.clients.smtp.ssl:true}") boolean ssl,
                              @Value("${mail.clients.username:info@westmed.kz}") String username,
                              @Value("${mail.clients.password:}") String password,
                              @Value("${mail.clients.market:KZ}") String market,
                              @Value("${mail.clients.from-name:West-Med}") String fromName,
                              @Value("${mail.clients.sent-copy.enabled:true}") boolean sentCopy) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.ssl = ssl;
        this.username = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.market = ClientMailSettings.parseMarket(market);
        this.fromName = fromName == null || fromName.isBlank() ? "West-Med" : fromName.trim();
        this.sentCopy = sentCopy;
        if (enabled()) log.info("Отправка писем клиентам: ящик {}, рынок {}", this.username, this.market);
    }

    /** Всё нужное для входа на SMTP задано. */
    public boolean enabled() {
        return !host.isEmpty() && username.contains("@") && !password.isBlank() && market != null;
    }

    /** null — с этого рынка можно отправлять; иначе — причина для окна письма и страницы шаблонов. */
    public String problem(Market current) {
        if (!enabled()) return NOT_CONFIGURED;
        if (current != market) return "Письма клиентам — только на рынке " + market + " (ящик " + username + ")";
        return null;
    }

    public String host() { return host; }
    public int port() { return port; }
    public boolean ssl() { return ssl; }
    public String username() { return username; }
    public String password() { return password; }
    public Market market() { return market; }
    public String fromName() { return fromName; }
    public boolean sentCopy() { return sentCopy; }
}
```

`src/main/java/com/vladoose/nir/service/mail/ClientLetter.java`:

```java
package com.vladoose.nir.service.mail;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Письмо клиенту с ящика info@ (спека 2026-10-19-client-mail-reply §3.2): от кого, кому, тема, текст и подпись; у ответа —
 * цитата и Message-ID письма, на которое отвечаем (inReplyTo). messageId ставит АИС заранее — он же ключ в переписке.
 */
public record ClientLetter(String fromName, String fromAddress, List<String> to, String subject, String text,
                           String signature, Quote quote, String inReplyTo, String messageId,
                           List<Attachment> attachments) {

    /** Письмо, на которое отвечаем: когда, от кого (имени может не быть), текст — как хранится в переписке. */
    public record Quote(OffsetDateTime sentAt, String name, String address, String body) {}

    /** Вложение: имя (кириллица кодируется), байты, тип. */
    public record Attachment(String fileName, byte[] bytes, String contentType) {}
}
```

`src/main/java/com/vladoose/nir/service/mail/ClientLetterBuilder.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сборка письма клиенту (спека 2026-10-19-client-mail-reply §3.2) — чистые функции. Тема ответа — «Re:» без второго «Re:»,
 * текст — текст → подпись → у ответа строка цитаты «ДД.ММ.ГГГГ ЧЧ:ММ, Имя <адрес> пишет:» (часы рынка) и текст того
 * письма с «> ». Строка цитаты подходит под EmailReplyText.ATTRIBUTION: ответ клиента над нашей цитатой приём режет как
 * обычно. Длинного тире здесь нет (спека §2 п. 4).
 */
public final class ClientLetterBuilder {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern REPLY_PREFIX = Pattern.compile("(?iu)^\\s*(re|ответ)\\s*:");

    private ClientLetterBuilder() {}

    /** «Re: тема» без второго «Re:»; без темы — «Re: письмо от ДД.ММ.ГГГГ» (дата письма по часам рынка). */
    public static String replySubject(String parentSubject, OffsetDateTime parentSentAt, ZoneId zone) {
        String s = parentSubject == null ? "" : parentSubject.strip();
        if (s.isEmpty()) return "Re: письмо от " + DAY.format(parentSentAt.atZoneSameInstant(zone));
        return REPLY_PREFIX.matcher(s).find() ? s : "Re: " + s;
    }

    /** Текст письма: текст → подпись → у ответа строка цитаты и цитата. Переводы строк — \n. */
    public static String body(String text, String signature, ClientLetter.Quote quote, ZoneId zone) {
        StringBuilder b = new StringBuilder(normalize(text).strip());
        String sig = normalize(signature).strip();
        if (!sig.isEmpty()) b.append("\n\n").append(sig);
        if (quote != null) b.append("\n\n").append(attribution(quote, zone)).append('\n').append(quoted(quote.body()));
        return b.toString();
    }

    static String attribution(ClientLetter.Quote q, ZoneId zone) {
        String when = WHEN.format(q.sentAt().atZoneSameInstant(zone));
        String who = q.name() == null || q.name().isBlank() ? q.address() : q.name().strip() + " <" + q.address() + ">";
        return when + ", " + who + " пишет:";
    }

    static String quoted(String body) {
        String t = normalize(body).strip();
        if (t.isEmpty()) return ">";
        return t.lines().map(l -> l.isEmpty() ? ">" : "> " + l).collect(Collectors.joining("\n"));
    }

    /** Наш Message-ID — заранее: он же ключ сообщения в переписке (копию из «Отправленных» приём отсечёт как повтор). */
    public static String messageId(String mailbox) {
        String domain = mailbox.substring(mailbox.indexOf('@') + 1);
        return "<ais." + UUID.randomUUID() + "@" + domain + ">";
    }

    /** Ключ сообщения переписки — Message-ID (не «sha256:…» длинного и не «uid:…» письма без него). */
    public static boolean isMessageId(String key) {
        return key != null && key.startsWith("<") && key.endsWith(">") && key.indexOf('@') > 0;
    }

    /** MIME: простой текст UTF-8, вложения — multipart, имена кодируются; saveChanges наш Message-ID не меняет. */
    public static MimeMessage build(Session session, ClientLetter l, ZoneId zone) throws MessagingException {
        MimeMessage m = new FixedIdMessage(session, l.messageId());
        boolean multipart = l.attachments() != null && !l.attachments().isEmpty();
        MimeMessageHelper h = new MimeMessageHelper(m, multipart, "UTF-8");
        h.setEncodeFilenames(true);
        try {
            h.setFrom(new InternetAddress(l.fromAddress(), l.fromName(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Не собрался адрес «От»", e);
        }
        h.setTo(l.to().toArray(String[]::new));
        h.setSubject(l.subject());
        h.setSentDate(new Date());
        h.setText(body(l.text(), l.signature(), l.quote(), zone), false);
        if (l.inReplyTo() != null) {
            m.setHeader("In-Reply-To", l.inReplyTo());
            m.setHeader("References", l.inReplyTo());
        }
        if (multipart) {
            for (ClientLetter.Attachment a : l.attachments()) {
                h.addAttachment(a.fileName(), new ByteArrayResource(a.bytes()), a.contentType());
            }
        }
        m.saveChanges();
        return m;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** MimeMessage, у которого saveChanges не меняет Message-ID (JavaMail иначе ставит свой при каждом saveChanges). */
    static final class FixedIdMessage extends MimeMessage {
        private final String id;

        FixedIdMessage(Session session, String id) {
            super(session);
            this.id = id;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", id);
        }
    }
}
```

`src/main/resources/application.yaml` — в блок `mail.clients` (после `service-domains`, тот же отступ, что у `service-domains`):

```yaml
    # Письма клиентам из АИС (спека 2026-10-19-client-mail-reply §3.1): свой SMTP info@, вход — username / password выше.
    # Пустой хост — отправка выключена. Булевы ключи в .env пустыми не оставлять (пустое значение роняет старт).
    smtp:
      host: ${MAIL_CLIENTS_SMTP_HOST:}
      port: ${MAIL_CLIENTS_SMTP_PORT:465}
      ssl: ${MAIL_CLIENTS_SMTP_SSL:true}
    from-name: ${MAIL_CLIENTS_FROM_NAME:West-Med}
    # копия письма клиенту — в «Отправленные» info@ (IMAP APPEND, как у zakup@): Mail.ru кладёт туда только письма веб-почты
    sent-copy:
      enabled: ${MAIL_CLIENTS_SENT_COPY_ENABLED:true}
```

- [ ] **Step 4: Прогнать — зелёные**

Run: `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientLetterBuilderTest' --tests 'com.vladoose.nir.service.mail.ClientSmtpSettingsTest'`
Expected: PASS (11 тестов).

- [ ] **Step 5: Мутации (каждая — свой красный тест, откат копией)**
  - `replySubject`: убрать проверку `REPLY_PREFIX` → `replySubject_reOnce_emptySubjectGetsDate` красный.
  - `attribution`: `q.sentAt().toLocalDateTime()` вместо `atZoneSameInstant(zone)` → `attributionUsesMarketZone` красный.
  - `FixedIdMessage.updateMessageID` удалить → `build_headers…_messageIdSurvivesSaveChanges` красный.
  - `ClientSmtpSettings.enabled`: убрать `market != null` → `enabled_onlyWithHostAddressPasswordAndMarket` красный.
  После серии — `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/mail/ClientSmtpSettings.java src/main/java/com/vladoose/nir/service/mail/ClientLetter.java \
  src/main/java/com/vladoose/nir/service/mail/ClientLetterBuilder.java src/main/resources/application.yaml \
  src/test/java/com/vladoose/nir/service/mail/ClientLetterBuilderTest.java src/test/java/com/vladoose/nir/service/mail/ClientSmtpSettingsTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): SMTP-настройки info@ и сборка письма клиенту (Re:, цитата, подпись, Message-ID)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Отправитель SMTP info@ и копия в «Отправленные»

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientMailSender.java`
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientSentCopy.java`
- Modify: `src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java` (свойства SMTP info@ и выключенная копия)
- Test: `src/test/java/com/vladoose/nir/service/mail/ClientMailSenderTest.java`, `src/test/java/com/vladoose/nir/service/mail/ClientSentCopyTest.java`

**Interfaces:**
- Consumes: `ClientSmtpSettings`, `ClientLetterBuilder.build`, `ClientMailSettings` (IMAP info@), `SentCopy` (пакетный конструктор с `Executor`, пакетный `shutdown()`).
- Produces: `ClientMailSender(ClientSmtpSettings)` — `Session session()`, `void send(MimeMessage)`, `static String failureText(Throwable)`, константы `AUTH`, `DOWN`, `ADDRESS`; `ClientSentCopy(ClientSmtpSettings, ClientMailSettings)` (+ пакетный с `Executor`) — `void store(MimeMessage)`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/service/mail/ClientMailSenderTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** SMTP ящика клиентов сквозь GreenMail и тексты сбоев (спека 2026-10-19-client-mail-reply §3.5). */
class ClientMailSenderTest {

    static final String INFO = "info@westmed.kz";
    static final ZoneId ORAL = ZoneId.of("Asia/Oral");

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(INFO, "secret"))
            .withPerMethodLifecycle(true);

    static ClientSmtpSettings settings(int port) {
        return new ClientSmtpSettings("127.0.0.1", port, false, INFO, "secret", "KZ", "West-Med", true);
    }

    static ClientLetter letter(String id) {
        return new ClientLetter("West-Med", INFO, List.of("zz-client@clinic.kz"), "Re: Запрос цены", "Здравствуйте!",
                "С уважением,\nWest-Med", null, "<client-1@mail.ru>", id, List.of());
    }

    @Test
    void sendsLetter_messageIdAndThreadHeadersReachRecipient() throws Exception {
        ClientMailSender sender = new ClientMailSender(settings(ServerSetupTest.SMTP.getPort()));
        String id = ClientLetterBuilder.messageId(INFO);

        sender.send(ClientLetterBuilder.build(sender.session(), letter(id), ORAL));

        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(1);
        assertThat(got[0].getMessageID()).isEqualTo(id);
        assertThat(got[0].getHeader("In-Reply-To", null)).isEqualTo("<client-1@mail.ru>");
        assertThat(got[0].getSubject()).isEqualTo("Re: Запрос цены");
        assertThat(((InternetAddress) got[0].getFrom()[0]).getPersonal()).isEqualTo("West-Med");
    }

    @Test
    void serverDown_failureTextSaysNotResponding() throws Exception {
        ClientMailSender sender = new ClientMailSender(settings(3999));   // на 3999 никто не слушает
        MimeMessage m = ClientLetterBuilder.build(sender.session(), letter(ClientLetterBuilder.messageId(INFO)), ORAL);
        Throwable e = catchThrowable(() -> sender.send(m));
        assertThat(e).isInstanceOf(MailException.class);
        assertThat(ClientMailSender.failureText(e)).isEqualTo(ClientMailSender.DOWN);
    }

    @Test
    void failureText_authAddressConnectUnknown_noForeignText() throws Exception {
        assertThat(ClientMailSender.failureText(new MailAuthenticationException("535 bad creds for info@westmed.kz")))
                .isEqualTo(ClientMailSender.AUTH);
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        SendFailedException bad = new SendFailedException("Invalid Addresses", null, new Address[0], new Address[0],
                new Address[]{new InternetAddress("x@clinic.kz")});
        assertThat(ClientMailSender.failureText(new MailSendException(Map.<Object, Exception>of(m, bad))))
                .isEqualTo(ClientMailSender.ADDRESS);
        assertThat(ClientMailSender.failureText(new MailSendException("Mail server connection failed",
                new MessagingException("Couldn't connect to host smtp.mail.ru", new ConnectException("refused")))))
                .isEqualTo(ClientMailSender.DOWN);
        assertThat(ClientMailSender.failureText(new IllegalStateException("info@westmed.kz")))
                .isEqualTo("Письмо не ушло (IllegalStateException)");
    }
}
```

`src/test/java/com/vladoose/nir/service/mail/ClientSentCopyTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Копия письма клиенту — в «Отправленные» info@ (спека 2026-10-19-client-mail-reply §3.4). */
class ClientSentCopyTest {

    static final String INFO = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(INFO, "secret"))
            .withPerMethodLifecycle(true);

    @BeforeEach
    void sentFolder() throws Exception {
        GreenMailUser u = greenMail.getManagers().getUserManager().getUser(INFO);
        greenMail.getManagers().getImapHostManager().createMailbox(u, "Sent");
    }

    static ClientMailSettings imap() {
        return new ClientMailSettings(true, "127.0.0.1", ServerSetupTest.IMAP.getPort(), INFO, "secret", "imap", "KZ",
                7, 25, "", INFO, "zakup@westmed.kz");
    }

    static ClientSmtpSettings smtp(boolean copy) {
        return new ClientSmtpSettings("127.0.0.1", ServerSetupTest.SMTP.getPort(), false, INFO, "secret", "KZ", "West-Med", copy);
    }

    private static MimeMessage sent() throws Exception {
        ClientMailSender sender = new ClientMailSender(smtp(true));
        MimeMessage m = ClientLetterBuilder.build(sender.session(), new ClientLetter("West-Med", INFO, List.of("zz@clinic.kz"),
                "Re: Запрос", "Текст", "", null, null, ClientLetterBuilder.messageId(INFO), List.of()), ZoneId.of("Asia/Oral"));
        sender.send(m);
        return m;
    }

    @Test
    void copyLandsInInfoSent_sameMessageId_markedRead() throws Exception {
        MimeMessage m = sent();
        new ClientSentCopy(smtp(true), imap(), Runnable::run).store(m);
        assertThat(SentCopyTest.folder(INFO, "Sent")).containsExactly(new SentCopyTest.Stored("Re: Запрос", m.getMessageID(), true));
    }

    @Test
    void switchedOff_noCopy() throws Exception {
        MimeMessage m = sent();
        new ClientSentCopy(smtp(false), imap(), Runnable::run).store(m);
        assertThat(SentCopyTest.folder(INFO, "Sent")).isEmpty();
    }
}
```

- [ ] **Step 2: Прогнать — красные**

Run: `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientMailSenderTest' --tests 'com.vladoose.nir.service.mail.ClientSentCopyTest'`
Expected: FAIL — компиляция (`ClientMailSender`, `ClientSentCopy`).

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/service/mail/ClientMailSender.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SMTP ящика писем клиентам (спека 2026-10-19-client-mail-reply §3.1, §3.5): свой отправитель — почта поставщикам
 * (spring.mail.*, zakup@) не меняется. Таймауты — 10 с. Тексты сбоев — свои: адреса и тексты исключений почты наружу не идут.
 */
@Component
public class ClientMailSender {

    static final int TIMEOUT_MS = 10_000;
    public static final String AUTH = "Письмо не ушло: почта отклонила вход — проверьте пароль info@";
    public static final String DOWN = "Письмо не ушло: почтовый сервер не отвечает — попробуйте ещё раз";
    public static final String ADDRESS = "Письмо не ушло: почта не приняла адрес";

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();

    public ClientMailSender(ClientSmtpSettings s) {
        sender.setHost(s.host());
        sender.setPort(s.port());
        sender.setUsername(s.username());
        sender.setPassword(s.password());
        sender.setDefaultEncoding("UTF-8");
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.ssl.enable", String.valueOf(s.ssl()));
        p.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        p.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        p.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
    }

    /** Сессия для сборки письма (ClientLetterBuilder.build) — с настройками этого SMTP. */
    public Session session() {
        return sender.getSession();
    }

    public void send(MimeMessage m) {
        sender.send(m);
    }

    /** Свой текст сбоя: вход, соединение, адрес; остальное — классом исключения (§3.5). */
    public static String failureText(Throwable e) {
        for (Throwable c : causes(e)) {
            if (c instanceof MailAuthenticationException || c instanceof AuthenticationFailedException) return AUTH;
            if (c instanceof SendFailedException sf && sf.getInvalidAddresses() != null
                    && sf.getInvalidAddresses().length > 0) return ADDRESS;
            if (c instanceof ConnectException || c instanceof SocketTimeoutException || c instanceof UnknownHostException) {
                return DOWN;
            }
        }
        return "Письмо не ушло (" + e.getClass().getSimpleName() + ")";
    }

    /** Причины по цепочке, следующие исключения JavaMail и исключения по письмам MailSendException (они не в getCause). */
    private static List<Throwable> causes(Throwable e) {
        List<Throwable> out = new ArrayList<>();
        List<Throwable> queue = new ArrayList<>();
        queue.add(e);
        while (!queue.isEmpty() && out.size() < 50) {
            Throwable t = queue.remove(0);
            if (t == null || out.contains(t)) continue;
            out.add(t);
            queue.add(t.getCause());
            if (t instanceof MessagingException me) queue.add(me.getNextException());
            if (t instanceof MailSendException ms) queue.addAll(List.of(ms.getMessageExceptions()));
        }
        return out;
    }
}
```

`src/main/java/com/vladoose/nir/service/mail/ClientSentCopy.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Копия письма клиенту — в «Отправленные» info@ (спека 2026-10-19-client-mail-reply §3.4): тот же SentCopy, что у zakup@,
 * с IMAP-входом ящика клиентов и своим потоком. Выключатель — mail.clients.sent-copy.enabled; без SMTP — выключена.
 */
@Component
public class ClientSentCopy {

    private final SentCopy copy;

    @Autowired
    public ClientSentCopy(ClientSmtpSettings smtp, ClientMailSettings imap) {
        this(smtp, imap, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mail-clients-sent-copy");
            t.setDaemon(true);
            return t;
        }));
    }

    ClientSentCopy(ClientSmtpSettings smtp, ClientMailSettings imap, Executor executor) {
        this.copy = new SentCopy(smtp.sentCopy() && smtp.enabled(), imap.username(), imap.host(), imap.port(),
                imap.username(), imap.password(), imap.protocol(), executor);
    }

    /** Письмо ушло — копия своим потоком; ничего не бросает. */
    public void store(MimeMessage sent) {
        copy.store(sent);
    }

    @PreDestroy
    void shutdown() {
        copy.shutdown();
    }
}
```

`src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java` — в `@TestPropertySource(properties = {…})` после `"mail.clients.initial-delay-ms=86400000",`:

```java
        "mail.clients.smtp.host=127.0.0.1",
        "mail.clients.smtp.port=3025",
        "mail.clients.smtp.ssl=false",
        "mail.clients.sent-copy.enabled=false",
```

и в javadoc аннотации дописать: «Копия писем клиентам (ClientSentCopy) тоже выключена — по той же причине; тест копии собирает свой экземпляр с синхронным исполнителем».

- [ ] **Step 4: Зелёные**

Run: `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientMailSenderTest' --tests 'com.vladoose.nir.service.mail.ClientSentCopyTest' --tests 'com.vladoose.nir.mail.ClientMailIntegrationTest'`
Expected: PASS (ClientMailIntegrationTest — общий контекст с новыми свойствами поднялся).

- [ ] **Step 5: Мутации**
  - `failureText`: убрать ветку `MessagingException.getNextException`/цепочку (оставить только `e` сам) → `failureText_…` (DOWN) красный.
  - `ClientSentCopy`: `smtp.sentCopy() && smtp.enabled()` → `true` → `switchedOff_noCopy` красный.
  - `ClientMailSender`: `mail.smtp.auth` → `"false"` — GreenMail пустит и без входа, тест не различит: отметить в леджере как «держит живая проверка на проде» (вход проверяет скрипт §10).
  После серии — `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/mail/ClientMailSender.java src/main/java/com/vladoose/nir/service/mail/ClientSentCopy.java \
  src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java src/test/java/com/vladoose/nir/service/mail/ClientMailSenderTest.java \
  src/test/java/com/vladoose/nir/service/mail/ClientSentCopyTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): SMTP info@ — отправитель с текстами сбоев, копия в «Отправленные» info@

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Шаблоны ответов и подпись — V35, сервис, API

**Files:**
- Create: `src/main/resources/db/migration/V35__reply_templates.sql`
- Create: `src/main/java/com/vladoose/nir/entity/ReplyTemplate.java`, `src/main/java/com/vladoose/nir/entity/ReplyTemplateRole.java`
- Create: `src/main/java/com/vladoose/nir/repository/ReplyTemplateRepository.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ReplyTemplateDto.java`, `src/main/java/com/vladoose/nir/dto/response/ReplyTemplatesResponse.java`, `src/main/java/com/vladoose/nir/dto/request/ReplyTemplatesRequest.java`
- Create: `src/main/java/com/vladoose/nir/service/ReplyTemplateService.java`, `src/main/java/com/vladoose/nir/controller/ReplyTemplateController.java`
- Test: `src/test/java/com/vladoose/nir/clientmail/ReplyTemplateServiceTest.java`

**Interfaces:**
- Consumes: `ClientSmtpSettings.problem(Market)`, `.username()`.
- Produces: `enum ReplyTemplateRole { FIRST_REPLY, OFFER, SIGNATURE }`; `record ReplyTemplateDto(Long id, String title, String body, ReplyTemplateRole role)`; `record ReplyTemplatesResponse(List<ReplyTemplateDto> templates, String signature, Sending sending)` с `record Sending(boolean enabled, String mailbox, String reason)`; `record ReplyTemplatesRequest(List<ReplyTemplateDto> templates, String signature)`; `ReplyTemplateService.get()`, `save(ReplyTemplatesRequest, String author)`, `templates(Market)` (без подписи), `signatureFor(Market, String employee)`, `static String render(String signature, String employee)`; `ReplyTemplateRepository.findByMarketOrderByPositionAscIdAsc(Market)`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientmail/ReplyTemplateServiceTest.java`:

```java
package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ReplyTemplateController;
import com.vladoose.nir.dto.request.ReplyTemplatesRequest;
import com.vladoose.nir.dto.response.ReplyTemplateDto;
import com.vladoose.nir.dto.response.ReplyTemplatesResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ReplyTemplate;
import com.vladoose.nir.entity.ReplyTemplateRole;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.ReplyTemplateRepository;
import com.vladoose.nir.service.ReplyTemplateService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Шаблоны ответов и подпись (спека 2026-10-19-client-mail-reply §6). */
@SpringBootTest
@Transactional
class ReplyTemplateServiceTest {

    @Autowired ReplyTemplateService service;
    @Autowired ReplyTemplateRepository repository;
    @Autowired ReplyTemplateController controller;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Своя картина в откатываемой транзакции — тест не зависит от шаблонов, которые правит оператор (CLAUDE.md §14). */
    private void clean() {
        repository.deleteAllInBatch();
        em.flush();
        em.clear();
    }

    private static ReplyTemplateDto t(Long id, String title, ReplyTemplateRole role) {
        return new ReplyTemplateDto(id, title, "Текст «" + title + "»", role);
    }

    private static ReplyTemplatesRequest req(String signature, ReplyTemplateDto... items) {
        return new ReplyTemplatesRequest(Arrays.asList(items), signature);
    }

    /** Сид V35 — пока шаблоны KZ не правили: тексты утверждены оператором 2026-10-19. Правили — только инварианты. */
    @Test
    void seed_kzTemplatesAndSignature() {
        List<ReplyTemplate> kz = repository.findByMarketOrderByPositionAscIdAsc(Market.KZ);
        Set<ReplyTemplateRole> seen = EnumSet.noneOf(ReplyTemplateRole.class);
        assertThat(kz).allSatisfy(x -> assertThat(x.getRole() == null || seen.add(x.getRole())).isTrue());
        if (kz.stream().anyMatch(x -> x.getUpdatedAt() != null)) return;
        assertThat(kz).extracting(ReplyTemplate::getTitle, ReplyTemplate::getRole).containsExactly(
                tuple("Подпись", ReplyTemplateRole.SIGNATURE),
                tuple("Первое письмо", ReplyTemplateRole.FIRST_REPLY),
                tuple("Запрос принят", null),
                tuple("КП во вложении", ReplyTemplateRole.OFFER),
                tuple("Напомнить о КП", null));
        assertThat(kz.get(0).getBody()).isEqualTo("С уважением,\n{{сотрудник}}\nWest-Med\n+7 777 075 27 70 · westmed.kz");
        assertThat(kz.get(1).getBody()).isEqualTo("Здравствуйте!\n\nСпасибо за обращение в West-Med. Подскажите, пожалуйста, "
                + "кто конечный заказчик: название учреждения и город?");
        assertThat(kz).allSatisfy(x -> assertThat(x.getBody()).doesNotContain("—"));
    }

    @Test
    void save_createsUpdatesDeletesInOrder_signatureSeparately() {
        clean();
        ReplyTemplatesResponse r = service.save(req("С уважением,\n{{сотрудник}}",
                t(null, "Первое письмо", ReplyTemplateRole.FIRST_REPLY), t(null, "Запрос принят", null)), "zz");
        assertThat(r.templates()).extracting(ReplyTemplateDto::title).containsExactly("Первое письмо", "Запрос принят");
        assertThat(r.signature()).isEqualTo("С уважением,\n{{сотрудник}}");
        Long first = r.templates().get(0).id();
        Long second = r.templates().get(1).id();

        r = service.save(req("", t(second, "Запрос принят ✓", null), t(null, "КП во вложении", ReplyTemplateRole.OFFER)), "zz");

        assertThat(r.templates()).extracting(ReplyTemplateDto::id, ReplyTemplateDto::title)
                .containsExactly(tuple(second, "Запрос принят ✓"), tuple(r.templates().get(1).id(), "КП во вложении"));
        assertThat(repository.findById(first)).isEmpty();
        assertThat(r.signature()).isEmpty();
    }

    @Test
    void save_roleMovesToAnotherTemplateInOneSave() {
        clean();
        ReplyTemplatesResponse r = service.save(req("", t(null, "А", ReplyTemplateRole.FIRST_REPLY), t(null, "Б", null)), "zz");
        Long a = r.templates().get(0).id();
        Long b = r.templates().get(1).id();
        r = service.save(req("", t(a, "А", null), t(b, "Б", ReplyTemplateRole.FIRST_REPLY)), "zz");
        assertThat(r.templates()).extracting(ReplyTemplateDto::role).containsExactly(null, ReplyTemplateRole.FIRST_REPLY);
    }

    @Test
    void save_invalidInput_400_nothingWritten() {
        clean();
        assertBad(req("", t(null, "А", ReplyTemplateRole.OFFER), t(null, "Б", ReplyTemplateRole.OFFER)),
                "«Подставлять в письмо с КП» — только у одного шаблона");
        assertBad(req("", new ReplyTemplateDto(null, " ", "Текст", null)), "Название пустое — шаблон № 1");
        assertBad(req("", new ReplyTemplateDto(null, "А", " ", null)), "Текст пустой — шаблон № 1 «А»");
        assertBad(req("", new ReplyTemplateDto(null, "x".repeat(61), "Текст", null)), "Название длиннее 60 символов — шаблон № 1");
        assertBad(req("", new ReplyTemplateDto(null, "А", "x".repeat(4001), null)), "Текст длиннее 4000 символов — шаблон № 1 «А»");
        assertBad(req("", t(null, "А", ReplyTemplateRole.SIGNATURE)), "Подпись — отдельным полем");
        assertBad(req("x".repeat(1001)), "Подпись длиннее 1000 символов");
        assertThat(repository.findByMarketOrderByPositionAscIdAsc(Market.KZ)).isEmpty();
    }

    @Test
    void save_idOfOtherMarket_400() {
        clean();
        ReplyTemplate rf = repository.save(ReplyTemplate.builder().market(Market.RF).title("РФ").body("Текст").position(1).build());
        assertBad(req("", t(rf.getId(), "РФ", null)), "Шаблон не найден — шаблон № 1");
    }

    @Test
    void render_nameReplaced_blankNameDropsLine() {
        assertThat(ReplyTemplateService.render("С уважением,\n{{сотрудник}}\nWest-Med", "Иван Петров"))
                .isEqualTo("С уважением,\nИван Петров\nWest-Med");
        assertThat(ReplyTemplateService.render("С уважением,\n{{сотрудник}}\nWest-Med", "  ")).isEqualTo("С уважением,\nWest-Med");
        assertThat(ReplyTemplateService.render("", "Иван")).isEmpty();
        assertThat(ReplyTemplateService.render(null, "Иван")).isEmpty();
    }

    @Test
    void templates_withoutSignature_signatureForMarket_rfEmpty() {
        clean();
        service.save(req("С уважением,\n{{сотрудник}}", t(null, "А", null)), "zz");
        assertThat(service.templates(Market.KZ)).extracting(ReplyTemplateDto::title).containsExactly("А");
        assertThat(service.signatureFor(Market.KZ, "Иван Петров")).isEqualTo("С уважением,\nИван Петров");
        assertThat(service.templates(Market.RF)).isEmpty();
        assertThat(service.signatureFor(Market.RF, "Иван Петров")).isEmpty();
    }

    @Test
    void sending_notConfiguredInDefaultContext() {
        ReplyTemplatesResponse.Sending s = service.get().sending();
        assertThat(s.enabled()).isFalse();
        assertThat(s.reason()).isEqualTo("Отправка писем клиентам не настроена");
    }

    @Test
    @WithMockUser(roles = "USER")
    void operator_cannotReadOrSave() {
        assertThatThrownBy(() -> controller.get()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.save(req(""))).isInstanceOf(AccessDeniedException.class);
    }

    private void assertBad(ReplyTemplatesRequest r, String message) {
        assertThatThrownBy(() -> service.save(r, "zz")).isInstanceOf(BadRequestException.class).hasMessage(message);
    }
}
```

- [ ] **Step 2: Красные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ReplyTemplateServiceTest'`
Expected: FAIL — компиляция (`ReplyTemplateService`, `ReplyTemplate` …).

- [ ] **Step 3: Реализация**

`src/main/resources/db/migration/V35__reply_templates.sql`:

```sql
-- Шаблоны ответов клиентам и подпись письма (спека 2026-10-19-client-mail-reply §6): строка на шаблон, подпись — строка
-- с ролью SIGNATURE. Не рыночная сущность с явным рынком (как email_template). Тексты KZ утверждены оператором
-- 2026-10-19; updated_at пусто — «не правили» (тест сида сверяет точные тексты только у нетронутых строк).
CREATE TABLE reply_template (
    id          BIGSERIAL PRIMARY KEY,
    market      VARCHAR(2)   NOT NULL,
    title       VARCHAR(60)  NOT NULL,
    body        TEXT         NOT NULL,
    role        VARCHAR(20),
    position    INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100)
);
CREATE UNIQUE INDEX ux_reply_template_role ON reply_template (market, role) WHERE role IS NOT NULL;
CREATE INDEX idx_reply_template_market ON reply_template (market, position);

INSERT INTO reply_template (market, title, body, role, position) VALUES
('KZ', 'Подпись', E'С уважением,\n{{сотрудник}}\nWest-Med\n+7 777 075 27 70 · westmed.kz', 'SIGNATURE', 0),
('KZ', 'Первое письмо', E'Здравствуйте!\n\nСпасибо за обращение в West-Med. Подскажите, пожалуйста, кто конечный заказчик: название учреждения и город?', 'FIRST_REPLY', 1),
('KZ', 'Запрос принят', E'Здравствуйте!\n\nЗапрос получили, готовим коммерческое предложение.', NULL, 2),
('KZ', 'КП во вложении', E'Здравствуйте!\n\nНаправляем коммерческое предложение во вложении.', 'OFFER', 3),
('KZ', 'Напомнить о КП', E'Здравствуйте!\n\nПодскажите, пожалуйста, удалось ли рассмотреть наше коммерческое предложение?', NULL, 4);
```

`src/main/java/com/vladoose/nir/entity/ReplyTemplateRole.java`:

```java
package com.vladoose.nir.entity;

/** FIRST_REPLY — в первое письмо, OFFER — в письмо с КП, SIGNATURE — подпись (спека 2026-10-19-client-mail-reply §6.1). */
public enum ReplyTemplateRole { FIRST_REPLY, OFFER, SIGNATURE }
```

`src/main/java/com/vladoose/nir/entity/ReplyTemplate.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Шаблон ответа клиенту или подпись письма (V35, спека 2026-10-19-client-mail-reply §6). Не рыночная сущность с явным
 * рынком (как email_template): читается и пишется по MarketContext, без @Filter.
 */
@Entity
@Table(name = "reply_template")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReplyTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReplyTemplateRole role;

    @Column(nullable = false)
    private int position;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
```

`src/main/java/com/vladoose/nir/repository/ReplyTemplateRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ReplyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplyTemplateRepository extends JpaRepository<ReplyTemplate, Long> {
    List<ReplyTemplate> findByMarketOrderByPositionAscIdAsc(Market market);
}
```

`src/main/java/com/vladoose/nir/dto/response/ReplyTemplateDto.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ReplyTemplateRole;

/** Шаблон ответа (спека 2026-10-19-client-mail-reply §6): id пусто — новый. */
public record ReplyTemplateDto(Long id, String title, String body, ReplyTemplateRole role) {}
```

`src/main/java/com/vladoose/nir/dto/response/ReplyTemplatesResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Шаблоны рынка без подписи, подпись (как есть, с {{сотрудник}}) и состояние отправки с info@. */
public record ReplyTemplatesResponse(List<ReplyTemplateDto> templates, String signature, Sending sending) {
    public record Sending(boolean enabled, String mailbox, String reason) {}
}
```

`src/main/java/com/vladoose/nir/dto/request/ReplyTemplatesRequest.java`:

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.dto.response.ReplyTemplateDto;

import java.util.List;

/** Весь список шаблонов рынка по порядку и подпись (пусто — без подписи). */
public record ReplyTemplatesRequest(List<ReplyTemplateDto> templates, String signature) {}
```

`src/main/java/com/vladoose/nir/service/ReplyTemplateService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ReplyTemplatesRequest;
import com.vladoose.nir.dto.response.ReplyTemplateDto;
import com.vladoose.nir.dto.response.ReplyTemplatesResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ReplyTemplate;
import com.vladoose.nir.entity.ReplyTemplateRole;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.ReplyTemplateRepository;
import com.vladoose.nir.service.mail.ClientSmtpSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Шаблоны ответов клиентам и подпись письма (спека 2026-10-19-client-mail-reply §6). Сохранение — весь список целиком;
 * сначала проверка всего ввода, потом запись. Метка «подставлять самому» у рынка — не больше одной каждого вида.
 */
@Service
public class ReplyTemplateService {

    static final int TITLE_MAX = 60;
    static final int BODY_MAX = 4000;
    static final int SIGNATURE_MAX = 1000;
    static final int MAX_TEMPLATES = 30;
    static final String EMPLOYEE = "{{сотрудник}}";

    private final ReplyTemplateRepository repository;
    private final ClientSmtpSettings smtp;

    public ReplyTemplateService(ReplyTemplateRepository repository, ClientSmtpSettings smtp) {
        this.repository = repository;
        this.smtp = smtp;
    }

    @Transactional(readOnly = true)
    public ReplyTemplatesResponse get() {
        Market market = MarketContext.get();
        List<ReplyTemplate> all = repository.findByMarketOrderByPositionAscIdAsc(market);
        String problem = smtp.problem(market);
        return new ReplyTemplatesResponse(dtos(all), signatureText(all),
                new ReplyTemplatesResponse.Sending(problem == null, smtp.username(), problem));
    }

    /** Шаблоны рынка без подписи — кнопки окна письма. */
    @Transactional(readOnly = true)
    public List<ReplyTemplateDto> templates(Market market) {
        return dtos(repository.findByMarketOrderByPositionAscIdAsc(market));
    }

    /** Подпись рынка для отправителя: {{сотрудник}} — его ФИО; ФИО нет — строка с плейсхолдером убирается целиком. */
    @Transactional(readOnly = true)
    public String signatureFor(Market market, String employee) {
        return render(signatureText(repository.findByMarketOrderByPositionAscIdAsc(market)), employee);
    }

    public static String render(String signature, String employee) {
        if (signature == null || signature.isBlank()) return "";
        String name = employee == null ? "" : employee.strip();
        return signature.lines()
                .filter(l -> !(name.isEmpty() && l.contains(EMPLOYEE)))
                .map(l -> l.replace(EMPLOYEE, name))
                .collect(Collectors.joining("\n")).strip();
    }

    @Transactional
    public ReplyTemplatesResponse save(ReplyTemplatesRequest req, String author) {
        Market market = MarketContext.get();
        List<ReplyTemplateDto> items = req == null || req.templates() == null ? List.of() : req.templates();
        String signature = req == null || req.signature() == null ? "" : req.signature().strip();
        List<ReplyTemplate> current = repository.findByMarketOrderByPositionAscIdAsc(market);
        Map<Long, ReplyTemplate> byId = new HashMap<>();
        for (ReplyTemplate t : current) if (t.getRole() != ReplyTemplateRole.SIGNATURE) byId.put(t.getId(), t);
        check(items, signature, byId);

        Set<Long> keep = items.stream().map(ReplyTemplateDto::id).filter(Objects::nonNull).collect(Collectors.toSet());
        // удалённые — первыми, у остающихся метки снимаются до записи: метка, переехавшая с шаблона на шаблон, иначе
        // упёрлась бы в уникальный индекс (market, role)
        for (ReplyTemplate t : byId.values()) {
            if (keep.contains(t.getId())) t.setRole(null);
            else repository.delete(t);
        }
        repository.flush();
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < items.size(); i++) {
            ReplyTemplateDto d = items.get(i);
            ReplyTemplate t = d.id() == null ? ReplyTemplate.builder().market(market).build() : byId.get(d.id());
            t.setTitle(d.title().strip());
            t.setBody(d.body().strip());
            t.setRole(d.role());
            t.setPosition(i + 1);
            t.setUpdatedAt(now);
            t.setUpdatedBy(author);
            repository.save(t);
        }
        ReplyTemplate sig = current.stream().filter(t -> t.getRole() == ReplyTemplateRole.SIGNATURE).findFirst().orElse(null);
        if (signature.isEmpty()) {
            if (sig != null) repository.delete(sig);
        } else if (sig == null || !signature.equals(sig.getBody())) {
            if (sig == null) sig = ReplyTemplate.builder().market(market).title("Подпись").role(ReplyTemplateRole.SIGNATURE).position(0).build();
            sig.setBody(signature);
            sig.setUpdatedAt(now);
            sig.setUpdatedBy(author);
            repository.save(sig);
        }
        repository.flush();
        return get();
    }

    /** Весь ввод — до первой записи. */
    private static void check(List<ReplyTemplateDto> items, String signature, Map<Long, ReplyTemplate> byId) {
        if (items.size() > MAX_TEMPLATES) throw new BadRequestException("Шаблонов не больше " + MAX_TEMPLATES);
        if (signature.length() > SIGNATURE_MAX) throw new BadRequestException("Подпись длиннее " + SIGNATURE_MAX + " символов");
        Set<ReplyTemplateRole> roles = EnumSet.noneOf(ReplyTemplateRole.class);
        for (int i = 0; i < items.size(); i++) {
            ReplyTemplateDto d = items.get(i);
            String where = "шаблон № " + (i + 1);
            String title = d.title() == null ? "" : d.title().strip();
            String body = d.body() == null ? "" : d.body().strip();
            if (title.isEmpty()) throw new BadRequestException("Название пустое — " + where);
            if (title.length() > TITLE_MAX) throw new BadRequestException("Название длиннее " + TITLE_MAX + " символов — " + where);
            if (body.isEmpty()) throw new BadRequestException("Текст пустой — " + where + " «" + title + "»");
            if (body.length() > BODY_MAX) throw new BadRequestException("Текст длиннее " + BODY_MAX + " символов — " + where + " «" + title + "»");
            if (d.role() == ReplyTemplateRole.SIGNATURE) throw new BadRequestException("Подпись — отдельным полем");
            if (d.role() != null && !roles.add(d.role())) {
                throw new BadRequestException("«" + roleLabel(d.role()) + "» — только у одного шаблона");
            }
            if (d.id() != null && !byId.containsKey(d.id())) throw new BadRequestException("Шаблон не найден — " + where);
        }
    }

    static String roleLabel(ReplyTemplateRole r) {
        return switch (r) {
            case FIRST_REPLY -> "Подставлять в первое письмо";
            case OFFER -> "Подставлять в письмо с КП";
            case SIGNATURE -> "Подпись";
        };
    }

    private static List<ReplyTemplateDto> dtos(List<ReplyTemplate> all) {
        return all.stream().filter(t -> t.getRole() != ReplyTemplateRole.SIGNATURE)
                .map(t -> new ReplyTemplateDto(t.getId(), t.getTitle(), t.getBody(), t.getRole())).toList();
    }

    private static String signatureText(List<ReplyTemplate> all) {
        return all.stream().filter(t -> t.getRole() == ReplyTemplateRole.SIGNATURE).map(ReplyTemplate::getBody)
                .findFirst().orElse("");
    }
}
```

`src/main/java/com/vladoose/nir/controller/ReplyTemplateController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ReplyTemplatesRequest;
import com.vladoose.nir.dto.response.ReplyTemplatesResponse;
import com.vladoose.nir.service.ReplyTemplateService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Система → Шаблоны ответов» (спека 2026-10-19-client-mail-reply §6.4, §8). Только администратор. */
@RestController
@RequestMapping("/api/reply-templates")
public class ReplyTemplateController {

    private final ReplyTemplateService service;

    public ReplyTemplateController(ReplyTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReplyTemplatesResponse get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReplyTemplatesResponse save(@RequestBody ReplyTemplatesRequest req) {
        return service.save(req, CurrentUser.name());
    }
}
```

- [ ] **Step 4: Зелёные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ReplyTemplateServiceTest'`
Expected: PASS (9 тестов); V35 накатилась на nirdb (`select version from flyway_schema_history order by installed_rank desc limit 1;` → `35`).

- [ ] **Step 5: Мутации**
  - Убрать снятие меток у остающихся (`t.setRole(null)`) → `save_roleMovesToAnotherTemplateInOneSave` красный (уникальный индекс).
  - `render`: не удалять строку при пустом ФИО → `render_nameReplaced_blankNameDropsLine` красный.
  - `check`: убрать проверку чужого id → `save_idOfOtherMarket_400` красный.
  - `dtos`: не отфильтровывать подпись → `templates_withoutSignature…` красный.
  После серии — `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/resources/db/migration/V35__reply_templates.sql src/main/java/com/vladoose/nir/entity/ReplyTemplate.java \
  src/main/java/com/vladoose/nir/entity/ReplyTemplateRole.java src/main/java/com/vladoose/nir/repository/ReplyTemplateRepository.java \
  src/main/java/com/vladoose/nir/dto/response/ReplyTemplateDto.java src/main/java/com/vladoose/nir/dto/response/ReplyTemplatesResponse.java \
  src/main/java/com/vladoose/nir/dto/request/ReplyTemplatesRequest.java src/main/java/com/vladoose/nir/service/ReplyTemplateService.java \
  src/main/java/com/vladoose/nir/controller/ReplyTemplateController.java src/test/java/com/vladoose/nir/clientmail/ReplyTemplateServiceTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): шаблоны ответов и подпись — V35 (сид KZ), сервис, GET/PUT /api/reply-templates

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Запись отправленного письма — переписка, обращение, сделка, КП

**Files:**
- Modify: `src/main/java/com/vladoose/nir/entity/DealEventType.java`
- Modify: `src/main/java/com/vladoose/nir/repository/DealEventRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealEventService.java`
- Modify: `src/main/java/com/vladoose/nir/service/LeadService.java` (перегрузка `takeAutomatically`)
- Modify: `src/main/java/com/vladoose/nir/service/EmailChatWriter.java` (`Outgoing`, перегрузка `writeOutgoing`)
- Create: `src/main/java/com/vladoose/nir/service/ClientLetterRecorder.java`
- Test: `src/test/java/com/vladoose/nir/clientmail/ClientLetterRecorderTest.java`

**Interfaces:**
- Consumes: `EmailChatWriter.writeOutgoing`, `ClientMailSettings.rules()`, `ClientMailRouter.isOwnDomain/isService`, `ClientOfferService.get/setStatus`, `EmailLetter` (record: `key, subject, body, sentAt, fromAddress, fromName, files`), `MailFile(name, mimeType, size, content)`.
- Produces: `DealEventType.EMAIL`; `DealEventRepository.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(Long, DealEventType)`; `DealEventService.email(Long tenderId, Long chatId, String subject, OffsetDateTime at, String author)`; `LeadService.takeAutomatically(Lead, String note, String author)`; `EmailChatWriter.Outgoing(String senderName, String takeNote, String author)`, `EmailChatWriter.writeOutgoing(String mailbox, String to, EmailLetter, Outgoing)`; `ClientLetterRecorder.record(Sent) → Result`, `record Sent(String mailbox, List<String> to, EmailLetter letter, String senderName, String author, Long leadId, Long tenderId, List<Long> offerIds)`, `record Result(List<Long> chatIds, List<Long> messageIds, List<Long> offersSent)`, `TAKE_NOTE = "ответ клиенту письмом из АИС"`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientmail/ClientLetterRecorderTest.java`:

```java
package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientLetterRecorder;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.mail.EmailLetter;
import com.vladoose.nir.service.mail.MailFile;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Запись отправленного письма клиенту (спека 2026-10-19-client-mail-reply §3.3). */
@SpringBootTest
@Transactional
class ClientLetterRecorderTest {

    static final String BOX = "info@westmed.kz";

    @Autowired ClientLetterRecorder recorder;
    @Autowired EmailChatWriter chatWriter;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired DealEventRepository events;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferService offers;
    @Autowired EntityManager em;

    final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private Chat emailChat(String address) {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX).externalChatId(address)
                .title("Клиент").build());
    }

    private Lead lead(Chat chat, LeadChannel channel, String email) {
        return leads.save(Lead.builder().market(Market.KZ).channel(channel).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос").contactName("Клиент").contactEmail(email).status(LeadStatus.NEW).chat(chat)
                .receivedAt(now.minusHours(1)).build());
    }

    private EmailLetter letter() {
        return new EmailLetter("<ais." + UUID.randomUUID() + "@westmed.kz>", "Re: Запрос цены",
                "Здравствуйте!\n\nС уважением,\nИван Петров", now, BOX, null,
                List.of(new MailFile("КП.pdf", "application/pdf", 2L, new byte[]{1, 2})));
    }

    private ClientLetterRecorder.Sent sent(List<String> to, EmailLetter l, Long leadId, Long tenderId, List<Long> offerIds) {
        return new ClientLetterRecorder.Sent(BOX, to, l, "Иван Петров", "zz-sender", leadId, tenderId, offerIds);
    }

    @Test
    void reply_outgoingMessageWithKeyNameFiles_leadTakenInWorkWithAuthor() {
        String a = address();
        Chat chat = emailChat(a);
        Lead lead = lead(chat, LeadChannel.EMAIL, a);
        EmailLetter l = letter();

        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a), l, null, null, List.of()));

        assertThat(r.chatIds()).containsExactly(chat.getId());
        ChatMessage m = messages.findByChatIdAndExternalId(chat.getId(), l.key()).orElseThrow();
        assertThat(m.getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(m.getSenderName()).isEqualTo("Иван Петров");
        assertThat(m.getSubject()).isEqualTo("Re: Запрос цены");
        assertThat(attachments.findMetaByMessageIds(List.of(m.getId()))).hasSize(1);
        em.flush();
        em.refresh(lead);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(lead.getEvents()).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.STATUS);
            assertThat(e.getAuthor()).isEqualTo("zz-sender");
            assertThat(e.getBody()).endsWith(ClientLetterRecorder.TAKE_NOTE);
        });
    }

    @Test
    void mixedCaseAddress_sameChat() {   // Review Focus 2
        String a = address();
        Chat chat = emailChat(a);
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a.toUpperCase()), letter(), null, null, List.of()));
        assertThat(r.chatIds()).containsExactly(chat.getId());
        assertThat(chats.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, a.toUpperCase())).isEmpty();
    }

    @Test
    void ownDomainAndServiceAddresses_noChat() {
        ClientLetterRecorder.Result r = recorder.record(sent(List.of("manager@westmed.kz", "noreply@2gis.ru"), letter(), null, null, List.of()));
        assertThat(r.chatIds()).isEmpty();
        assertThat(r.messageIds()).isEmpty();
    }

    @Test
    void sameLetterFromSentFolder_isDuplicate() {
        String a = address();
        Chat chat = emailChat(a);
        EmailLetter l = letter();
        recorder.record(sent(List.of(a), l, null, null, List.of()));
        assertThat(chatWriter.writeOutgoing(BOX, a, l).duplicate()).isTrue();   // так пишет приём «Отправленных»
        assertThat(messages.findLatest(chat.getId(), PageRequest.of(0, 5))).hasSize(1);
    }

    @Test
    void dealContext_emailEventPerChat_atLetterTime() {
        Tender t = requests.createEmpty();
        String a = address();
        String b = address();
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a, b), letter(), null, t.getId(), List.of()));
        List<DealEvent> list = events.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(t.getId(), DealEventType.EMAIL);
        assertThat(list).extracting(DealEvent::getRefId).containsExactlyElementsOf(r.chatIds());
        assertThat(list).allSatisfy(e -> {
            assertThat(e.getOccurredAt()).isEqualTo(now);
            assertThat(e.getText()).isEqualTo("Письмо клиенту «Re: Запрос цены»");
            assertThat(e.getAuthor()).isEqualTo("zz-sender");
        });
    }

    @Test
    void offers_draftBecomesSentAtLetterTime_otherStatusesUnchanged() {
        ClientOffer draft = offers.create("zz-sender");
        ClientOffer accepted = offers.create("zz-sender");
        offers.setStatus(accepted.getId(), ClientOfferStatus.ACCEPTED);
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(address()), letter(), null, null,
                List.of(draft.getId(), accepted.getId())));
        assertThat(r.offersSent()).containsExactly(draft.getId());
        em.flush();
        em.refresh(draft);
        em.refresh(accepted);
        assertThat(draft.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(draft.getSentAt()).isEqualTo(now);
        assertThat(accepted.getStatus()).isEqualTo(ClientOfferStatus.ACCEPTED);
    }

    @Test
    void leadCard_whatsappLead_letterToEmail_messageEventOnLead() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("Клиент").build());
        String a = address();
        Lead lead = lead(wa, LeadChannel.WHATSAPP, a);
        recorder.record(sent(List.of(a), letter(), lead.getId(), null, List.of()));
        em.flush();
        em.refresh(lead);
        assertThat(lead.getChat().getId()).isEqualTo(wa.getId());
        assertThat(lead.getEvents()).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.MESSAGE);
            assertThat(e.getDirection()).isEqualTo(LeadDirection.OUT);
            assertThat(e.getChannel()).isEqualTo(LeadChannel.EMAIL);
            assertThat(e.getBody()).isEqualTo("Письмо клиенту «Re: Запрос цены» на " + a);
        });
    }

    @Test
    void leadWithoutChat_linkedToNewChat_noMessageEvent() {
        String a = address();
        Lead lead = lead(null, LeadChannel.SITE, a);
        ClientLetterRecorder.Result r = recorder.record(sent(List.of(a), letter(), lead.getId(), null, List.of()));
        em.flush();
        em.refresh(lead);
        assertThat(lead.getChat().getId()).isEqualTo(r.chatIds().get(0));
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(lead.getEvents()).noneMatch(e -> e.getType() == LeadEventType.MESSAGE);
    }
}
```

- [ ] **Step 2: Красные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ClientLetterRecorderTest'`
Expected: FAIL — компиляция (`ClientLetterRecorder`, `DealEventType.EMAIL`, `findByTenderIdAndType…`).

- [ ] **Step 3: Реализация**

`DealEventType.java`:

```java
/** NOTE / CALL — оператор, STAGE — смена этапа, EMAIL — письмо клиенту из сделки (ref — переписка; спека 2026-10-19-client-mail-reply §3.3). */
public enum DealEventType { NOTE, CALL, STAGE, EMAIL }
```

`DealEventRepository.java` — добавить:

```java
    /** Письма сделки (EMAIL) — переписки, которые лента показывает с первого письма (спека 2026-10-19-client-mail-reply §7.1). */
    List<DealEvent> findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(Long tenderId, DealEventType type);
```

`DealEventService.java` — добавить (импорт `java.time.OffsetDateTime`):

```java
    /**
     * Письмо клиенту из сделки (спека 2026-10-19-client-mail-reply §3.3 п. 3): ref — переписка, время — время письма: лента
     * берёт переписку с первого письма сделки включительно, а запись события на миллисекунды позже спрятала бы само письмо.
     */
    @Transactional
    public DealEvent email(Long tenderId, Long chatId, String subject, OffsetDateTime at, String author) {
        String s = subject == null || subject.isBlank() ? "без темы" : subject.strip();
        String text = "Письмо клиенту «" + s + "»";
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        return repository.save(DealEvent.builder().tenderId(tenderId).type(DealEventType.EMAIL).text(text).refId(chatId)
                .author(author).occurredAt(at).build());
    }
```

`LeadService.java` — заменить метод `takeAutomatically(Lead lead, String note)`:

```java
    /**
     * «Взять в работу» без кнопки — ответ клиенту в WhatsApp с телефона, письмом из почты или из АИС (спека whatsapp-chats
     * §5.2 п.3, client-mail-reply §3.3). Тот же переход, что у кнопки: событие STATUS и статус на сайт для заявок westmed.kz.
     */
    @Transactional
    public void takeAutomatically(Lead lead, String note) {
        takeAutomatically(lead, note, null);
    }

    /** author — кто отправил письмо из АИС; null — система (ответ с телефона или из почты). */
    @Transactional
    public void takeAutomatically(Lead lead, String note, String author) {
        if (lead.getStatus() != LeadStatus.NEW) return;
        transition(lead, LeadStatus.IN_WORK, author, note);
    }
```

`EmailChatWriter.java`:
1. Под константами — запись и константа:

```java
    /** Кто и как отправил наше письмо (спека 2026-10-19-client-mail-reply §3.3): имя в переписке, текст и автор «В работу». */
    public record Outgoing(String senderName, String takeNote, String author) {}

    /** Наше письмо из «Отправленных» почты: без имени, «ответ клиенту письмом из почты», без автора. */
    static final Outgoing FROM_MAILBOX = new Outgoing(null, AUTO_TAKE_NOTE, null);
```

2. Методы записи:

```java
    /** Письмо собеседника из «Входящих» ящика mailbox. */
    @Transactional
    public Outcome writeIncoming(String mailbox, EmailLetter letter) {
        return write(mailbox, letter.fromAddress(), letter.fromName(), LeadDirection.IN, letter, FROM_MAILBOX);
    }

    /** Наше письмо из «Отправленных» — в переписку с адресатом to (адрес нижним регистром). */
    @Transactional
    public Outcome writeOutgoing(String mailbox, String to, EmailLetter letter) {
        return writeOutgoing(mailbox, to, letter, FROM_MAILBOX);
    }

    /** Наше письмо — из «Отправленных» или из АИС (who — отправитель и текст события «В работу»). */
    @Transactional
    public Outcome writeOutgoing(String mailbox, String to, EmailLetter letter, Outgoing who) {
        return write(mailbox, to, null, LeadDirection.OUT, letter, who);
    }
```

3. `private Outcome write(String mailbox, String address, String name, LeadDirection dir, EmailLetter letter, Outgoing who)` — в построителе сообщения `.senderName(dir == LeadDirection.IN ? trunc(name != null ? name : address, 255) : trunc(who.senderName(), 255))`, вызов правил — `applyLeadRules(chat, msg, dir, letter, who)`.

4. В `applyLeadRules(Chat chat, ChatMessage msg, LeadDirection dir, EmailLetter letter, Outgoing who)` — `leadService.takeAutomatically(lead, who.takeNote(), who.author());` вместо `leadService.takeAutomatically(lead, AUTO_TAKE_NOTE);`.

`src/main/java/com/vladoose/nir/service/ClientLetterRecorder.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.mail.ClientMailRouter;
import com.vladoose.nir.service.mail.ClientMailRules;
import com.vladoose.nir.service.mail.ClientMailSettings;
import com.vladoose.nir.service.mail.EmailLetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Запись отправленного письма клиенту (спека 2026-10-19-client-mail-reply §3.3) — одной транзакцией: переписка с каждым
 * адресатом (свой домен и служебные адреса — пропуск, как у приёма «Отправленных»), правила обращения, событие сделки,
 * событие обращения, КП «Черновик» → «Отправлено». Гарды рынка — у вызывающего (ClientMailSendService).
 */
@Service
public class ClientLetterRecorder {

    public static final String TAKE_NOTE = "ответ клиенту письмом из АИС";

    /** Что записать: письмо (ключ — наш Message-ID, текст без цитаты), адресаты, кто отправил, откуда открыли окно, КП. */
    public record Sent(String mailbox, List<String> to, EmailLetter letter, String senderName, String author,
                       Long leadId, Long tenderId, List<Long> offerIds) {}

    public record Result(List<Long> chatIds, List<Long> messageIds, List<Long> offersSent) {}

    private final EmailChatWriter chats;
    private final ClientMailSettings settings;
    private final LeadRepository leads;
    private final DealEventService dealEvents;
    private final ClientOfferService offers;

    public ClientLetterRecorder(EmailChatWriter chats, ClientMailSettings settings, LeadRepository leads,
                                DealEventService dealEvents, ClientOfferService offers) {
        this.chats = chats;
        this.settings = settings;
        this.leads = leads;
        this.dealEvents = dealEvents;
        this.offers = offers;
    }

    @Transactional
    public Result record(Sent s) {
        ClientMailRules rules = settings.rules();
        List<Long> chatIds = new ArrayList<>();
        List<Long> messageIds = new ArrayList<>();
        for (String raw : s.to()) {
            String address = raw.strip().toLowerCase(Locale.ROOT);
            if (ClientMailRouter.isOwnDomain(address, rules) || ClientMailRouter.isService(address, rules)) continue;
            EmailChatWriter.Outcome o = chats.writeOutgoing(s.mailbox(), address, s.letter(),
                    new EmailChatWriter.Outgoing(s.senderName(), TAKE_NOTE, s.author()));
            if (chatIds.contains(o.chatId())) continue;
            chatIds.add(o.chatId());
            if (o.messageId() != null) messageIds.add(o.messageId());
            if (s.tenderId() != null) {
                dealEvents.email(s.tenderId(), o.chatId(), s.letter().subject(), s.letter().sentAt(), s.author());
            }
        }
        if (s.leadId() != null) noteOnLead(s, chatIds);
        List<Long> sent = new ArrayList<>();
        for (Long id : s.offerIds()) {
            if (offers.get(id).getStatus() == ClientOfferStatus.DRAFT) {
                offers.setStatus(id, ClientOfferStatus.SENT, s.letter().sentAt());
                sent.add(id);
            }
        }
        return new Result(chatIds, messageIds, sent);
    }

    /** Письмо из карточки обращения не в его переписку (обращение из WhatsApp) — строкой в ленте обращения (§3.3 п. 4). */
    private void noteOnLead(Sent s, List<Long> chatIds) {
        Lead lead = leads.findById(s.leadId()).orElse(null);
        if (lead == null) return;
        if (lead.getChat() != null && chatIds.contains(lead.getChat().getId())) return;
        String subject = s.letter().subject() == null || s.letter().subject().isBlank() ? "без темы" : s.letter().subject().strip();
        LeadEvent e = lead.addEvent(LeadEventType.MESSAGE, s.author(),
                "Письмо клиенту «" + subject + "» на " + String.join(", ", s.to()));
        e.setDirection(LeadDirection.OUT);
        e.setChannel(LeadChannel.EMAIL);
        e.setOccurredAt(s.letter().sentAt());
    }
}
```

- [ ] **Step 4: Зелёные + соседи**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ClientLetterRecorderTest' --tests 'com.vladoose.nir.mail.ClientMailIntegrationTest' --tests 'com.vladoose.nir.service.mail.ClientMailWriterTest' --tests 'com.vladoose.nir.deal.*'`
Expected: PASS (приём info@ пишет как раньше — «из почты», без автора).

- [ ] **Step 5: Мутации**
  - `record`: без `toLowerCase` → `mixedCaseAddress_sameChat` красный.
  - `record`: без пропуска своего домена → `ownDomainAndServiceAddresses_noChat` красный.
  - `DealEventService.email`: без `.occurredAt(at)` → `dealContext_emailEventPerChat_atLetterTime` красный.
  - `noteOnLead`: без проверки «письмо в его переписке» → `leadWithoutChat_linkedToNewChat_noMessageEvent` красный.
  - `EmailChatWriter.applyLeadRules`: `who.author()` → `null` → `reply_…_leadTakenInWorkWithAuthor` красный.
  После серии — `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/entity/DealEventType.java src/main/java/com/vladoose/nir/repository/DealEventRepository.java \
  src/main/java/com/vladoose/nir/service/deal/DealEventService.java src/main/java/com/vladoose/nir/service/LeadService.java \
  src/main/java/com/vladoose/nir/service/EmailChatWriter.java src/main/java/com/vladoose/nir/service/ClientLetterRecorder.java \
  src/test/java/com/vladoose/nir/clientmail/ClientLetterRecorderTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): запись письма клиенту — переписка, «В работу» с автором, событие сделки EMAIL, КП «Отправлено»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Данные окна письма — `GET /api/client-mail/compose`

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/ClientMailComposeResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/ClientMailComposeService.java`
- Create: `src/main/java/com/vladoose/nir/controller/ClientMailController.java` (только `compose`; `send` — задача 6)
- Test: `src/test/java/com/vladoose/nir/clientmail/ClientMailComposeTest.java` (`@MailIntegrationTest` — SMTP «настроен»), `src/test/java/com/vladoose/nir/clientmail/ClientMailComposeDisabledTest.java` (обычный контекст — не настроен)

**Interfaces:**
- Consumes: `ChatService.chat(Long)` (пакетный, гард рынка), `ChatRepository.findByChannelAndAccountAndExternalChatId`, `ChatMessageRepository.findLatest / findLatestByDirection`, `LeadService.get`, `LeadRepository.findFirstByPrivateRequestId`, `PrivateRequestService.findById`, `ClientOfferService.get / fileBaseName`, `ClientOfferRepository.findByTenderIdOrderByIdDesc`, `ClientOfferFileRepository.findMetaByOfferIds`, `DealEventRepository.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc`, `ReplyTemplateService.templates / signatureFor`, `UserAccountRepository.findByUsername`, `ClientLetterBuilder.replySubject`, `ClientSmtpSettings`, `OfferLabels.of`, `DocFormat.date / DASH`.
- Produces: `ClientMailComposeService.compose(Long chatId, Long leadId, Long tenderId, Long offerId, String username)`; пакетные `Chat mailboxChat(Long id)` и `ClientLetter.Quote quoteOf(Chat chat, ChatMessage m)` (их зовёт задача 6); `static String firstAddress(String raw)`; `ClientMailComposeResponse(boolean enabled, String reason, String from, Long chatId, String chatTitle, List<String> to, Reply reply, String newSubject, boolean firstLetter, List<ReplyTemplateDto> templates, String signature, List<OfferRef> offers, Long offerId, Long leadId, Long tenderId)` с `Reply(Long messageId, String subject, String replySubject, OffsetDateTime sentAt, String fromName, String fromAddress, String direction)` и `OfferRef(Long id, String label, String origin, String status, String fileName)`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientmail/ClientMailComposeTest.java`:

```java
package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.mail.MailIntegrationTest;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Данные окна письма (спека 2026-10-19-client-mail-reply §4, §5, §8). SMTP в этом контексте настроен (GreenMail не нужен). */
@MailIntegrationTest
class ClientMailComposeTest {

    static final String BOX = "info@westmed.kz";

    @Autowired ClientMailComposeService compose;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired LeadRepository leads;
    @Autowired PrivateRequestService requests;
    @Autowired TenderRepository tenders;
    @Autowired ClientOfferService offers;
    @Autowired ClientOfferRepository offerRepository;
    @Autowired DealEventService events;
    @Autowired ReplyTemplateRepository templates;
    @Autowired UserAccountRepository users;
    @Autowired EntityManager em;

    final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        templates.deleteAllInBatch();
        templates.save(ReplyTemplate.builder().market(Market.KZ).title("Подпись").body("С уважением,\n{{сотрудник}}\nWest-Med")
                .role(ReplyTemplateRole.SIGNATURE).position(0).build());
        templates.save(ReplyTemplate.builder().market(Market.KZ).title("Первое письмо").body("Здравствуйте!")
                .role(ReplyTemplateRole.FIRST_REPLY).position(1).build());
        users.save(UserAccount.builder().username("zz-sender").fullName("Иван Петров").role("ROLE_ADMIN").passwordHash("x").build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private Chat emailChat(String address) {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX).externalChatId(address)
                .title("Айгерим").lastMessageAt(now).build());
    }

    private ChatMessage msg(Chat c, LeadDirection d, OffsetDateTime at, String subject, String body) {
        return messages.save(ChatMessage.builder().chat(c).externalId("<" + UUID.randomUUID() + "@mail.ru>").direction(d)
                .senderName(d == LeadDirection.IN ? "Айгерим" : "Иван Петров").type(ChatMessageType.EMAIL)
                .subject(subject).body(body).sentAt(at).build());
    }

    private ClientMailComposeResponse byChat(Chat c) { return compose.compose(c.getId(), null, null, null, "zz-sender"); }

    @Test
    void chat_replyToLatestLetter_firstLetterUntilWeWrite_signatureWithName() {
        Chat c = emailChat(address());
        msg(c, LeadDirection.IN, now.minusHours(2), "Запрос цены", "Нужны кушетки");
        ClientMailComposeResponse r = byChat(c);
        assertThat(r.enabled()).isTrue();
        assertThat(r.from()).isEqualTo("West-Med <info@westmed.kz>");
        assertThat(r.to()).containsExactly(c.getExternalChatId());
        assertThat(r.reply().replySubject()).isEqualTo("Re: Запрос цены");
        assertThat(r.reply().fromName()).isEqualTo("Айгерим");
        assertThat(r.firstLetter()).isTrue();
        assertThat(r.signature()).isEqualTo("С уважением,\nИван Петров\nWest-Med");
        assertThat(r.templates()).extracting(t -> t.title()).containsExactly("Первое письмо");

        msg(c, LeadDirection.OUT, now.minusHours(1), "Re: Запрос цены", "Ответ");
        ClientMailComposeResponse after = byChat(c);
        assertThat(after.firstLetter()).isFalse();
        assertThat(after.reply().direction()).isEqualTo("OUT");
        assertThat(after.reply().fromName()).isEqualTo("West-Med");
        assertThat(after.reply().fromAddress()).isEqualTo(BOX);
    }

    @Test
    void target_guards() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        assertThatThrownBy(() -> byChat(wa)).isInstanceOf(NotFoundException.class);
        Chat rf = chats.save(Chat.builder().market(Market.RF).channel(LeadChannel.EMAIL).account(BOX).externalChatId(address())
                .title("x").build());
        assertThatThrownBy(() -> byChat(rf)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> compose.compose(null, null, null, null, "zz-sender"))
                .isInstanceOf(BadRequestException.class).hasMessage("Не указано, кому письмо");
        Chat c = emailChat(address());
        assertThatThrownBy(() -> compose.compose(c.getId(), 1L, null, null, "zz-sender")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void lead_whatsapp_newLetterToContactEmail_lead_email_reply() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        String a = address();
        Lead l = leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).source("whatsapp")
                .externalId("zz" + System.nanoTime()).subject("WhatsApp").contactEmail(a).status(LeadStatus.NEW).chat(wa)
                .receivedAt(now).build());
        ClientMailComposeResponse r = compose.compose(null, l.getId(), null, null, "zz-sender");
        assertThat(r.chatId()).isNull();
        assertThat(r.reply()).isNull();
        assertThat(r.to()).containsExactly(a);
        assertThat(r.firstLetter()).isTrue();
        assertThat(r.leadId()).isEqualTo(l.getId());

        Chat mail = emailChat(address());
        msg(mail, LeadDirection.IN, now, "Вопрос", "Текст");
        l.setChat(mail);
        ClientMailComposeResponse byMail = compose.compose(null, l.getId(), null, null, "zz-sender");
        assertThat(byMail.chatId()).isEqualTo(mail.getId());
        assertThat(byMail.reply()).isNotNull();
    }

    @Test
    void deal_picksChatWithLatestMessage_fromLeadAndDealLetters() {
        Tender t = requests.createEmpty();
        Chat old = emailChat(address());
        old.setLastMessageAt(now.minusDays(2));
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос").status(LeadStatus.CONVERTED).chat(old).privateRequest(t).receivedAt(now.minusDays(3)).build());
        Chat fresh = emailChat(address());
        events.email(t.getId(), fresh.getId(), "КП", now.minusHours(1), "zz-sender");
        ClientMailComposeResponse r = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(r.chatId()).isEqualTo(fresh.getId());
        assertThat(r.tenderId()).isEqualTo(t.getId());
    }

    @Test
    void deal_noChat_addressFromRequest_thenExistingChatOfThatAddress() {
        Tender t = requests.createEmpty();
        String a = address();
        t.setContactEmail(a);
        tenders.saveAndFlush(t);
        ClientMailComposeResponse r = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(r.chatId()).isNull();
        assertThat(r.to()).containsExactly(a);

        Chat c = emailChat(a);
        ClientMailComposeResponse withChat = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(withChat.chatId()).isEqualTo(c.getId());
    }

    @Test
    void offer_subjectWithEnDash_preselected_offersOfDeal() {
        Tender t = requests.createEmpty();
        ClientOffer o1 = offers.create("zz-sender");
        o1.setTenderId(t.getId());
        ClientOffer o2 = offers.create("zz-sender");
        o2.setTenderId(t.getId());
        offerRepository.saveAllAndFlush(java.util.List.of(o1, o2));
        ClientMailComposeResponse r = compose.compose(null, null, null, o1.getId(), "zz-sender");
        assertThat(r.offerId()).isEqualTo(o1.getId());
        assertThat(r.tenderId()).isEqualTo(t.getId());
        assertThat(r.offers()).extracting(ClientMailComposeResponse.OfferRef::id).containsExactlyInAnyOrder(o1.getId(), o2.getId());
        assertThat(r.newSubject()).matches("Коммерческое предложение № " + o1.getNumber() + " от \\d{2}\\.\\d{2}\\.\\d{4} – West-Med")
                .doesNotContain("—");
        assertThat(r.offers()).allSatisfy(x -> assertThat(x.fileName()).endsWith(".pdf"));
    }

    @Test
    void rfMarket_disabledWithReason() {
        MarketContext.set(Market.RF);
        Tender t = requests.createEmpty();
        ClientMailComposeResponse r = compose.compose(null, null, t.getId(), null, "zz-sender");
        assertThat(r.enabled()).isFalse();
        assertThat(r.reason()).isEqualTo("Письма клиентам — только на рынке KZ (ящик info@westmed.kz)");
    }
}
```

`src/test/java/com/vladoose/nir/clientmail/ClientMailComposeDisabledTest.java`:

```java
package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ClientMailController;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Обычный контекст — SMTP info@ не задан: окно открывается с причиной, «Отправить» нет (спека §4). */
@SpringBootTest
@Transactional
class ClientMailComposeDisabledTest {

    @Autowired ClientMailComposeService compose;
    @Autowired ClientMailController controller;
    @Autowired PrivateRequestService requests;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void notConfigured_reason() {
        Tender t = requests.createEmpty();
        var r = compose.compose(null, null, t.getId(), null, "admin");
        assertThat(r.enabled()).isFalse();
        assertThat(r.reason()).isEqualTo("Отправка писем клиентам не настроена");
    }

    @Test
    @WithMockUser(roles = "USER")
    void operator_403() {
        assertThatThrownBy(() -> controller.compose(null, null, 1L, null)).isInstanceOf(AccessDeniedException.class);
    }
}
```

- [ ] **Step 2: Красные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ClientMailCompose*'`
Expected: FAIL — компиляция.

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/dto/response/ClientMailComposeResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Данные окна письма клиенту (спека 2026-10-19-client-mail-reply §4, §8): можно ли отправлять и почему нет, от кого,
 * переписка и письмо, на которое отвечаем (или адреса нового письма), шаблоны, подпись с ФИО отправителя, КП сделки.
 */
public record ClientMailComposeResponse(boolean enabled, String reason, String from, Long chatId, String chatTitle,
                                        List<String> to, Reply reply, String newSubject, boolean firstLetter,
                                        List<ReplyTemplateDto> templates, String signature, List<OfferRef> offers,
                                        Long offerId, Long leadId, Long tenderId) {

    public record Reply(Long messageId, String subject, String replySubject, OffsetDateTime sentAt, String fromName,
                        String fromAddress, String direction) {}

    /** КП сделки для «📎 КП»: у готового КП — его файл, у КП из конструктора — PDF (Word — по флажку). */
    public record OfferRef(Long id, String label, String origin, String status, String fileName) {}
}
```

`src/main/java/com/vladoose/nir/service/ClientMailComposeService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.mail.ClientLetter;
import com.vladoose.nir.service.mail.ClientLetterBuilder;
import com.vladoose.nir.service.mail.ClientSmtpSettings;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.EmailAddresses;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;

/**
 * Данные окна письма клиенту (спека 2026-10-19-client-mail-reply §4–§5): ровно одна точка входа — переписка, обращение,
 * заявка или КП. Есть почтовая переписка с адресом — ответ на её последнее письмо; нет — новое письмо на адрес контакта.
 * Заявка выбирает переписку с самым поздним сообщением среди переписки обращения и переписок писем сделки (§5 п. 3).
 */
@Service
public class ClientMailComposeService {

    /** Кому и откуда: переписка (ответ в неё) или адрес (новое письмо); заявка и КП — для «📎 КП» и события сделки. */
    record Target(Chat chat, String address, Long leadId, Long tenderId, ClientOffer offer) {}

    private final ClientSmtpSettings smtp;
    private final ChatService chatService;
    private final ChatRepository chatRepository;
    private final ChatMessageRepository messages;
    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final PrivateRequestService privateRequests;
    private final ClientOfferService offers;
    private final ClientOfferRepository offerRepository;
    private final ClientOfferFileRepository offerFiles;
    private final DealEventRepository dealEvents;
    private final ReplyTemplateService templates;
    private final UserAccountRepository users;

    public ClientMailComposeService(ClientSmtpSettings smtp, ChatService chatService, ChatRepository chatRepository,
                                    ChatMessageRepository messages, LeadService leadService, LeadRepository leadRepository,
                                    PrivateRequestService privateRequests, ClientOfferService offers,
                                    ClientOfferRepository offerRepository, ClientOfferFileRepository offerFiles,
                                    DealEventRepository dealEvents, ReplyTemplateService templates,
                                    UserAccountRepository users) {
        this.smtp = smtp;
        this.chatService = chatService;
        this.chatRepository = chatRepository;
        this.messages = messages;
        this.leadService = leadService;
        this.leadRepository = leadRepository;
        this.privateRequests = privateRequests;
        this.offers = offers;
        this.offerRepository = offerRepository;
        this.offerFiles = offerFiles;
        this.dealEvents = dealEvents;
        this.templates = templates;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public ClientMailComposeResponse compose(Long chatId, Long leadId, Long tenderId, Long offerId, String username) {
        if (Stream.of(chatId, leadId, tenderId, offerId).filter(Objects::nonNull).count() != 1) {
            throw new BadRequestException("Не указано, кому письмо");
        }
        Market market = MarketContext.get();
        Target t = target(chatId, leadId, tenderId, offerId);
        Chat chat = t.chat();
        ClientMailComposeResponse.Reply reply = chat == null ? null : reply(chat, market.zone());
        boolean first = chat == null
                || messages.findLatestByDirection(chat.getId(), LeadDirection.OUT, PageRequest.of(0, 1)).isEmpty();
        List<String> to = chat != null ? List.of(chat.getExternalChatId()) : t.address() == null ? List.of() : List.of(t.address());
        String problem = smtp.problem(market);
        return new ClientMailComposeResponse(problem == null, problem, smtp.fromName() + " <" + smtp.username() + ">",
                chat == null ? null : chat.getId(), chat == null ? null : chat.getTitle(), to, reply,
                t.offer() == null ? "" : offerSubject(t.offer()), first, templates.templates(market),
                templates.signatureFor(market, fullName(username)), offerRefs(t),
                t.offer() == null ? null : t.offer().getId(), t.leadId(), t.tenderId());
    }

    /** Почтовая переписка ящика клиентов текущего рынка, иначе 404 (чат WhatsApp, чужой ящик, чужой рынок). */
    Chat mailboxChat(Long id) {
        Chat c = chatService.chat(id);
        if (!isMailboxChat(c)) throw new NotFoundException("Почтовая переписка не найдена: id=" + id);
        return c;
    }

    /** Цитата письма переписки: наше письмо — от имени «От» и адреса ящика; имя, равное адресу, не повторяется. */
    ClientLetter.Quote quoteOf(Chat chat, ChatMessage m) {
        boolean in = m.getDirection() == LeadDirection.IN;
        String address = in ? chat.getExternalChatId() : smtp.username();
        String name = in ? m.getSenderName() : smtp.fromName();
        if (name != null && name.equalsIgnoreCase(address)) name = null;
        return new ClientLetter.Quote(m.getSentAt(), name, address, m.getBody());
    }

    /** Первый адрес поля почты нижним регистром; кривой адрес карточки — как пустой. */
    static String firstAddress(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            List<String> all = EmailAddresses.parse(raw);
            return all.isEmpty() ? null : all.get(0).toLowerCase(Locale.ROOT);
        } catch (BadRequestException e) {
            return null;
        }
    }

    private Target target(Long chatId, Long leadId, Long tenderId, Long offerId) {
        if (chatId != null) return new Target(mailboxChat(chatId), null, null, null, null);
        if (leadId != null) {
            Lead lead = leadService.get(leadId);
            if (isMailboxChat(lead.getChat())) return new Target(lead.getChat(), null, lead.getId(), null, null);
            String address = firstAddress(lead.getContactEmail());
            return new Target(chatByAddress(address), address, lead.getId(), null, null);
        }
        if (offerId != null) {
            ClientOffer o = offers.get(offerId);
            if (o.getTenderId() != null) return dealTarget(o.getTenderId(), o);
            String address = o.getFacility() == null ? null : firstAddress(o.getFacility().getEmail());
            return new Target(chatByAddress(address), address, null, null, o);
        }
        return dealTarget(tenderId, null);
    }

    private Target dealTarget(Long tenderId, ClientOffer offer) {
        Tender t = privateRequests.findById(tenderId);
        Lead lead = leadRepository.findFirstByPrivateRequestId(t.getId()).orElse(null);
        List<Chat> candidates = new ArrayList<>();
        if (lead != null && isMailboxChat(lead.getChat())) candidates.add(lead.getChat());
        for (DealEvent e : dealEvents.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(t.getId(), DealEventType.EMAIL)) {
            if (e.getRefId() != null) chatRepository.findById(e.getRefId()).filter(this::isMailboxChat).ifPresent(candidates::add);
        }
        Chat best = candidates.stream()
                .max(Comparator.comparing(Chat::getLastMessageAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (best != null) return new Target(best, null, null, t.getId(), offer);
        String address = Stream.of(firstAddress(t.getContactEmail()), lead == null ? null : firstAddress(lead.getContactEmail()),
                        t.getFacility() == null ? null : firstAddress(t.getFacility().getEmail()))
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Target(chatByAddress(address), address, null, t.getId(), offer);
    }

    private boolean isMailboxChat(Chat c) {
        return c != null && c.getChannel() == LeadChannel.EMAIL && smtp.username().equalsIgnoreCase(c.getAccount())
                && (c.getMarket() == null || c.getMarket() == MarketContext.get());
    }

    private Chat chatByAddress(String address) {
        if (address == null) return null;
        return chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, smtp.username(), address)
                .filter(this::isMailboxChat).orElse(null);
    }

    private ClientMailComposeResponse.Reply reply(Chat chat, ZoneId zone) {
        List<ChatMessage> last = messages.findLatest(chat.getId(), PageRequest.of(0, 1));
        if (last.isEmpty()) return null;
        ChatMessage m = last.get(0);
        ClientLetter.Quote q = quoteOf(chat, m);
        return new ClientMailComposeResponse.Reply(m.getId(), m.getSubject(),
                ClientLetterBuilder.replySubject(m.getSubject(), m.getSentAt(), zone), m.getSentAt(), q.name(), q.address(),
                m.getDirection().name());
    }

    /** «Коммерческое предложение № 24 от 19.10.2026 – West-Med»: среднее тире (спека §2 п. 4). */
    private String offerSubject(ClientOffer o) {
        return "Коммерческое предложение " + (o.getNumber() != null ? "№ " + o.getNumber() + " " : "")
                + "от " + DocFormat.date(o.getOfferDate()) + " " + DocFormat.DASH + " " + smtp.fromName();
    }

    private List<ClientMailComposeResponse.OfferRef> offerRefs(Target t) {
        List<ClientOffer> list = t.tenderId() != null ? offerRepository.findByTenderIdOrderByIdDesc(t.tenderId())
                : t.offer() != null ? List.of(t.offer()) : List.of();
        Map<Long, String> fileNames = new HashMap<>();
        List<Long> fileIds = list.stream().filter(o -> o.getOrigin() == ClientOfferOrigin.FILE).map(ClientOffer::getId).toList();
        if (!fileIds.isEmpty()) for (ClientOfferFileMeta m : offerFiles.findMetaByOfferIds(fileIds)) fileNames.put(m.offerId(), m.name());
        return list.stream().map(o -> new ClientMailComposeResponse.OfferRef(o.getId(), OfferLabels.of(o), o.getOrigin().name(),
                o.getStatus().name(), o.getOrigin() == ClientOfferOrigin.FILE ? fileNames.get(o.getId())
                        : offers.fileBaseName(o) + ".pdf")).toList();
    }

    private String fullName(String username) {
        if (username == null) return null;
        return users.findByUsername(username).map(UserAccount::getFullName).filter(n -> !n.isBlank()).orElse(null);
    }
}
```

`src/main/java/com/vladoose/nir/controller/ClientMailController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Письма клиентам из АИС (спека 2026-10-19-client-mail-reply §8). Только администратор. */
@RestController
@RequestMapping("/api/client-mail")
public class ClientMailController {

    private final ClientMailComposeService compose;

    public ClientMailController(ClientMailComposeService compose) {
        this.compose = compose;
    }

    @GetMapping("/compose")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMailComposeResponse compose(@RequestParam(required = false) Long chatId,
                                             @RequestParam(required = false) Long leadId,
                                             @RequestParam(required = false) Long tenderId,
                                             @RequestParam(required = false) Long offerId) {
        return compose.compose(chatId, leadId, tenderId, offerId, CurrentUser.name());
    }
}
```

- [ ] **Step 4: Зелёные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ClientMailCompose*'`
Expected: PASS (9 тестов).

- [ ] **Step 5: Мутации**
  - `isMailboxChat`: без проверки канала → `target_guards` (WhatsApp) красный.
  - `dealTarget`: `min` вместо `max` → `deal_picksChatWithLatestMessage…` красный.
  - `first`: по любому сообщению, а не OUT → `chat_replyToLatestLetter_firstLetterUntilWeWrite…` красный.
  - `offerSubject`: `"—"` вместо `DocFormat.DASH` → `offer_subjectWithEnDash…` красный.
  После серии — `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/response/ClientMailComposeResponse.java src/main/java/com/vladoose/nir/service/ClientMailComposeService.java \
  src/main/java/com/vladoose/nir/controller/ClientMailController.java src/test/java/com/vladoose/nir/clientmail/ClientMailComposeTest.java \
  src/test/java/com/vladoose/nir/clientmail/ClientMailComposeDisabledTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): данные окна письма — GET /api/client-mail/compose (ответ в переписку или новое письмо, шаблоны, подпись, КП)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Отправка — `POST /api/client-mail/send`

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/ClientMailSendRequest.java`, `src/main/java/com/vladoose/nir/dto/response/ClientMailSendResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/ClientMailSendService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/ClientMailController.java` (`send`)
- Test: `src/test/java/com/vladoose/nir/clientmail/ClientMailSendTest.java` (`@MailIntegrationTest` + GreenMail SMTP/IMAP), `src/test/java/com/vladoose/nir/clientmail/ClientMailSendServiceUnitTest.java` (Mockito)

**Interfaces:**
- Consumes: всё из задач 1–5; `ReadyOfferService.file(Long)` (`ClientOfferFile`: `getFileName()`, `getMimeType()`, `getContent()`), `ClientOfferService.pdf/docx(Long) → OfferFile(fileName, bytes)`, `ConflictException` (409), `UpstreamException` (502).
- Produces: `record ClientMailSendRequest(Long chatId, Long replyToMessageId, String to, String subject, String text, Long leadId, Long tenderId, List<OfferAttachment> offers, String token)` с `record OfferAttachment(Long id, String format)`; `record ClientMailSendResponse(boolean recorded, List<Long> chatIds, List<Long> messageIds, List<Long> offersSent, String warning)`; `ClientMailSendService.send(ClientMailSendRequest, List<MultipartFile>, String author)`; константа `NOT_RECORDED`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientmail/ClientMailSendTest.java`:

```java
package com.vladoose.nir.clientmail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ClientMailController;
import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.mail.MailIntegrationTest;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientMailReceiveService;
import com.vladoose.nir.service.ClientMailSendService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Письмо клиенту сквозь SMTP и IMAP GreenMail (спека 2026-10-19-client-mail-reply §3, §9). */
@MailIntegrationTest
class ClientMailSendTest {

    static final String BOX = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(BOX, "secret"))
            .withPerMethodLifecycle(true);

    @Autowired ClientMailSendService service;
    @Autowired ClientMailController controller;
    @Autowired ClientMailReceiveService intake;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired LeadRepository leads;
    @Autowired UserAccountRepository users;
    @Autowired MailCursorRepository cursors;
    @Autowired ReplyTemplateRepository templates;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferService offers;
    @Autowired ClientOfferRepository offerRepository;
    @Autowired DealEventRepository events;
    @Autowired EntityManager em;

    String client;
    Chat chat;
    ChatMessage letterIn;
    Lead lead;

    @BeforeEach
    void setUp() throws Exception {
        MarketContext.set(Market.KZ);
        GreenMailUser info = greenMail.getManagers().getUserManager().getUser(BOX);
        greenMail.getManagers().getImapHostManager().createMailbox(info, "Sent");
        for (String f : List.of(MailCursor.INBOX, MailCursor.SENT)) {
            cursors.findById(new MailCursorKey(BOX, f)).ifPresent(cursors::delete);
        }
        templates.deleteAllInBatch();   // подпись — своя: тест не зависит от правок оператора (CLAUDE.md §14)
        templates.save(ReplyTemplate.builder().market(Market.KZ).title("Подпись").body("С уважением,\n{{сотрудник}}\nWest-Med")
                .role(ReplyTemplateRole.SIGNATURE).position(0).build());
        users.save(UserAccount.builder().username("zz-sender").fullName("Иван Петров").role("ROLE_ADMIN").passwordHash("x").build());
        client = "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz";
        chat = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX).externalChatId(client)
                .title("Айгерим").build());
        letterIn = messages.save(ChatMessage.builder().chat(chat).externalId("<client-" + UUID.randomUUID() + "@mail.ru>")
                .direction(LeadDirection.IN).senderName("Айгерим").type(ChatMessageType.EMAIL).subject("Запрос цены")
                .body("Нужны кушетки 3 шт.").sentAt(OffsetDateTime.now().minusHours(1)).build());
        lead = leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос цены").contactName("Айгерим").contactEmail(client).status(LeadStatus.NEW).chat(chat)
                .receivedAt(OffsetDateTime.now().minusHours(1)).build());
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String token() { return UUID.randomUUID().toString(); }

    private ClientMailSendRequest reply(String text) {
        return new ClientMailSendRequest(chat.getId(), letterIn.getId(), null, null, text, null, null, List.of(), token());
    }

    private ClientMailSendRequest letter(String to, String subject) {
        return new ClientMailSendRequest(null, null, to, subject, "Текст", null, null, List.of(), token());
    }

    private MimeMessage only() {
        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(1);
        return got[0];
    }

    private static String text(MimeMessage m) throws Exception {
        Object c = m.getContent();
        if (c instanceof String s) return s.replace("\r\n", "\n");
        Multipart mp = (Multipart) c;
        for (int i = 0; i < mp.getCount(); i++) {
            Part p = mp.getBodyPart(i);
            if (p.isMimeType("text/plain")) return ((String) p.getContent()).replace("\r\n", "\n");
            if (p.isMimeType("multipart/*")) {
                Multipart inner = (Multipart) p.getContent();
                for (int j = 0; j < inner.getCount(); j++) {
                    if (inner.getBodyPart(j).isMimeType("text/plain")) return ((String) inner.getBodyPart(j).getContent()).replace("\r\n", "\n");
                }
            }
        }
        return "";
    }

    private static List<String> fileNames(Part p) throws Exception {
        List<String> out = new ArrayList<>();
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) out.addAll(fileNames(mp.getBodyPart(i)));
        } else if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            out.add(MimeUtility.decodeText(p.getFileName()));
        }
        return out;
    }

    @Test
    void replyInChat_deliveredInThread_recorded_leadInWork() throws Exception {
        ClientMailSendResponse r = service.send(reply("Здравствуйте!\n\nКП пришлём сегодня."), List.of(), "zz-sender");

        MimeMessage m = only();
        assertThat(m.getSubject()).isEqualTo("Re: Запрос цены");
        assertThat(m.getHeader("In-Reply-To", null)).isEqualTo(letterIn.getExternalId());
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly(client);
        assertThat(text(m)).startsWith("Здравствуйте!\n\nКП пришлём сегодня.\n\nС уважением,\nИван Петров\nWest-Med")
                .contains("Айгерим <" + client + "> пишет:").contains("> Нужны кушетки 3 шт.");
        assertThat(r.recorded()).isTrue();
        assertThat(r.chatIds()).containsExactly(chat.getId());
        ChatMessage out = messages.findByChatIdAndExternalId(chat.getId(), m.getMessageID()).orElseThrow();
        assertThat(out.getSenderName()).isEqualTo("Иван Петров");
        assertThat(out.getBody()).doesNotContain("пишет:");
        em.flush();
        em.refresh(lead);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    void sentCopyReadByIntake_isDuplicate() throws Exception {
        service.send(reply("Ответ"), List.of(), "zz-sender");
        MimeMessage m = only();
        GreenMailUser info = greenMail.getManagers().getUserManager().getUser(BOX);
        greenMail.getManagers().getImapHostManager().getFolder(info, "Sent").appendMessage(m, new Flags(Flags.Flag.SEEN), new Date());
        em.flush();

        ClientMailReceiveService.Result r = intake.poll();

        assertThat(r.stopped()).isNull();
        assertThat(messages.findLatestByDirection(chat.getId(), LeadDirection.OUT, PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void newTopicInChat_needsSubject_noQuoteNoThread() throws Exception {
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(chat.getId(), null, null, " ", "Текст", null, null,
                List.of(), token()), List.of(), "zz-sender")).isInstanceOf(BadRequestException.class).hasMessage("Укажите тему письма");
        service.send(new ClientMailSendRequest(chat.getId(), null, null, "Счёт на оплату", "Текст", null, null, List.of(), token()),
                List.of(), "zz-sender");
        MimeMessage m = only();
        assertThat(m.getSubject()).isEqualTo("Счёт на оплату");
        assertThat(m.getHeader("In-Reply-To")).isNull();
        assertThat(text(m)).doesNotContain("пишет:");
    }

    @Test
    void replyToLetterWithoutMessageId_noThreadHeaders() throws Exception {   // Review Focus 5
        ChatMessage noId = messages.save(ChatMessage.builder().chat(chat).externalId("uid:inbox:7:42").direction(LeadDirection.IN)
                .senderName("Айгерим").type(ChatMessageType.EMAIL).subject("Вопрос").body("Есть в наличии?")
                .sentAt(OffsetDateTime.now()).build());
        service.send(new ClientMailSendRequest(chat.getId(), noId.getId(), null, null, "Да", null, null, List.of(), token()),
                List.of(), "zz-sender");
        MimeMessage m = only();
        assertThat(m.getSubject()).isEqualTo("Re: Вопрос");
        assertThat(m.getHeader("In-Reply-To")).isNull();
        assertThat(m.getHeader("References")).isNull();
        assertThat(text(m)).contains("> Есть в наличии?");
    }

    @Test
    void newLetter_twoAddresses_chatsAndAttachment() throws Exception {
        String a = "zz-a" + UUID.randomUUID().toString().substring(0, 6) + "@clinic.kz";
        MockMultipartFile f = new MockMultipartFile("files", "Смета.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
        ClientMailSendResponse r = service.send(new ClientMailSendRequest(null, null, a + ", " + client, "Счёт", "Текст", null,
                null, List.of(), token()), List.of(f), "zz-sender");
        assertThat(r.chatIds()).hasSize(2).contains(chat.getId());
        MimeMessage[] got = greenMail.getReceivedMessages();   // GreenMail кладёт копию каждому адресату
        assertThat(got).isNotEmpty();
        assertThat(fileNames(got[0])).containsExactly("Смета.xlsx");
    }

    @Test
    void ownDomainRecipient_sentWithoutChat() {   // Review Focus 4
        ClientMailSendResponse r = service.send(letter("manager@westmed.kz", "Вопрос"), List.of(), "zz-sender");
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(r.recorded()).isTrue();
        assertThat(r.chatIds()).isEmpty();
        assertThat(chats.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "manager@westmed.kz")).isEmpty();
    }

    @Test
    void offerFromDeal_pdfAttached_offerSent_dealEvent() throws Exception {
        Tender t = requests.createEmpty();
        ClientOffer o = offers.create("zz-sender");
        o.setTenderId(t.getId());
        offerRepository.saveAndFlush(o);

        ClientMailSendResponse r = service.send(new ClientMailSendRequest(chat.getId(), letterIn.getId(), null, null,
                "КП во вложении", null, t.getId(), List.of(new ClientMailSendRequest.OfferAttachment(o.getId(), "PDF")), token()),
                List.of(), "zz-sender");

        assertThat(fileNames(only())).singleElement().asString().startsWith("КП № " + o.getNumber()).endsWith(".pdf");
        assertThat(r.offersSent()).containsExactly(o.getId());
        em.flush();
        em.refresh(o);
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(events.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(t.getId(), DealEventType.EMAIL))
                .singleElement().extracting(DealEvent::getRefId).isEqualTo(chat.getId());
    }

    @Test
    void offerOfAnotherDeal_400_nothingSent() {
        Tender t1 = requests.createEmpty();
        Tender t2 = requests.createEmpty();
        ClientOffer o = offers.create("zz-sender");
        o.setTenderId(t1.getId());
        offerRepository.saveAndFlush(o);
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(chat.getId(), letterIn.getId(), null, null, "Текст", null,
                t2.getId(), List.of(new ClientMailSendRequest.OfferAttachment(o.getId(), "PDF")), token()), List.of(), "zz-sender"))
                .isInstanceOf(BadRequestException.class).hasMessageEndingWith("— не из этой заявки");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void sameTokenTwice_secondIs409_oneLetterDelivered() {   // Review Focus 1
        ClientMailSendRequest req = reply("Текст");
        service.send(req, List.of(), "zz-sender");
        assertThatThrownBy(() -> service.send(req, List.of(), "zz-sender")).isInstanceOf(ConflictException.class);
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void validation_beforeSending_nothingSent() {
        assertBad(reply("  "), List.of(), "Напишите текст письма");
        assertBad(letter("не-адрес", "Тема"), List.of(), "Неверный адрес почты: «не-адрес»");
        assertBad(letter("", "Тема"), List.of(), "Укажите адрес получателя");
        String eleven = IntStream.range(0, 11).mapToObj(i -> "zz" + i + "@clinic.kz").collect(Collectors.joining(", "));
        assertBad(letter(eleven, "Тема"), List.of(), "Адресов не больше 10");
        Chat other = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account(BOX)
                .externalChatId("zz-o" + System.nanoTime() + "@clinic.kz").title("x").build());
        ChatMessage foreign = messages.save(ChatMessage.builder().chat(other).externalId("<f" + System.nanoTime() + "@x>")
                .direction(LeadDirection.IN).type(ChatMessageType.EMAIL).subject("x").body("x").sentAt(OffsetDateTime.now()).build());
        assertBad(new ClientMailSendRequest(chat.getId(), foreign.getId(), null, null, "Текст", null, null, List.of(), token()),
                List.of(), "Письмо, на которое вы отвечаете, — не из этой переписки");
        List<MultipartFile> eleven = IntStream.range(0, 11)
                .mapToObj(i -> (MultipartFile) new MockMultipartFile("files", "f" + i + ".txt", "text/plain", new byte[]{1})).toList();
        assertBad(reply("Текст"), eleven, "Файлов не больше 10");
        byte[] big = new byte[11 * 1024 * 1024];
        assertBad(reply("Текст"), List.of(new MockMultipartFile("files", "a.bin", "application/octet-stream", big),
                new MockMultipartFile("files", "b.bin", "application/octet-stream", big)), "Письмо больше 20 МБ — уберите часть файлов");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void foreignChats_404_rfMarket_400() {
        Chat wa = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("x").build());
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(wa.getId(), null, null, "Тема", "Текст", null, null,
                List.of(), token()), List.of(), "zz-sender")).isInstanceOf(NotFoundException.class);
        Chat rf = chats.save(Chat.builder().market(Market.RF).channel(LeadChannel.EMAIL).account(BOX)
                .externalChatId("zz-rf" + System.nanoTime() + "@clinic.kz").title("x").build());
        assertThatThrownBy(() -> service.send(new ClientMailSendRequest(rf.getId(), null, null, "Тема", "Текст", null, null,
                List.of(), token()), List.of(), "zz-sender")).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertBad(letter(client, "Тема"), List.of(), "Письма клиентам — только на рынке KZ (ящик info@westmed.kz)");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "USER")
    void operator_403() {
        assertThatThrownBy(() -> controller.send(reply("Текст"), List.of())).isInstanceOf(AccessDeniedException.class);
    }

    private void assertBad(ClientMailSendRequest r, List<MultipartFile> files, String message) {
        assertThatThrownBy(() -> service.send(r, files, "zz-sender")).isInstanceOf(BadRequestException.class).hasMessage(message);
    }
}
```

`src/test/java/com/vladoose/nir/clientmail/ClientMailSendServiceUnitTest.java`:

```java
package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.UserAccountRepository;
import com.vladoose.nir.service.*;
import com.vladoose.nir.service.mail.ClientMailSender;
import com.vladoose.nir.service.mail.ClientSentCopy;
import com.vladoose.nir.service.mail.ClientSmtpSettings;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Ветки отправки, которые нечем вызвать на настоящем SMTP и базе: обрыв SMTP и упавшая запись (спека §3.5, §13 п. 1–2). */
class ClientMailSendServiceUnitTest {

    final ClientSmtpSettings smtp = new ClientSmtpSettings("127.0.0.1", 3025, false, "info@westmed.kz", "secret", "KZ", "West-Med", true);
    final ClientMailSender sender = mock(ClientMailSender.class);
    final ClientSentCopy copy = mock(ClientSentCopy.class);
    final ClientLetterRecorder recorder = mock(ClientLetterRecorder.class);
    final ReplyTemplateService templates = mock(ReplyTemplateService.class);
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final ClientMailSendService service = new ClientMailSendService(smtp, sender, copy, recorder, mock(ClientMailComposeService.class),
            mock(ChatMessageRepository.class), mock(LeadService.class), mock(PrivateRequestService.class),
            mock(ClientOfferService.class), mock(ReadyOfferService.class), templates, users);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        when(sender.session()).thenReturn(Session.getInstance(new Properties()));
        when(templates.signatureFor(any(), any())).thenReturn("");
        when(users.findByUsername(any())).thenReturn(Optional.empty());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ClientMailSendRequest req(String token) {
        return new ClientMailSendRequest(null, null, "zz@clinic.kz", "Тема", "Текст", null, null, List.of(), token);
    }

    @Test
    void smtpDown_502_notRecordedNoCopy_tokenReleasedForRetry() {
        doThrow(new MailSendException("Mail server connection failed", new MessagingException("x", new ConnectException())))
                .doNothing().when(sender).send(any());
        assertThatThrownBy(() -> service.send(req("t1"), List.of(), "zz")).isInstanceOf(UpstreamException.class)
                .hasMessage(ClientMailSender.DOWN);
        verifyNoInteractions(recorder, copy);
        when(recorder.record(any())).thenReturn(new ClientLetterRecorder.Result(List.of(1L), List.of(2L), List.of()));
        assertThat(service.send(req("t1"), List.of(), "zz").recorded()).isTrue();
    }

    @Test
    void recordFailsAfterSend_200WithWarning_sameTokenStays409() {
        when(recorder.record(any())).thenThrow(new IllegalStateException("db"));
        ClientMailSendResponse r = service.send(req("t2"), List.of(), "zz");
        assertThat(r.recorded()).isFalse();
        assertThat(r.warning()).isEqualTo(ClientMailSendService.NOT_RECORDED);
        verify(sender, times(1)).send(any());
        verify(copy).store(any());
        assertThatThrownBy(() -> service.send(req("t2"), List.of(), "zz")).isInstanceOf(ConflictException.class);
    }
}
```

- [ ] **Step 2: Красные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ClientMailSend*'`
Expected: FAIL — компиляция (`ClientMailSendService`, `ClientMailSendRequest`, `controller.send`).

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/dto/request/ClientMailSendRequest.java`:

```java
package com.vladoose.nir.dto.request;

import java.util.List;

/**
 * Письмо клиенту (спека 2026-10-19-client-mail-reply §8). chatId + replyToMessageId — ответ в переписку; chatId без
 * replyToMessageId — новой темой на адрес переписки; без chatId — новое письмо на адреса to. leadId / tenderId — откуда
 * открыли окно (событие обращения / сделки). token — окна письма: повтор того же запроса — 409.
 */
public record ClientMailSendRequest(Long chatId, Long replyToMessageId, String to, String subject, String text,
                                    Long leadId, Long tenderId, List<OfferAttachment> offers, String token) {

    /** КП во вложении: PDF / DOCX — из конструктора, FILE — файл готового КП. */
    public record OfferAttachment(Long id, String format) {}
}
```

`src/main/java/com/vladoose/nir/dto/response/ClientMailSendResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Итог письма: recorded = false — письмо ушло, но в АИС не записалось (warning — что сказать человеку). */
public record ClientMailSendResponse(boolean recorded, List<Long> chatIds, List<Long> messageIds, List<Long> offersSent,
                                     String warning) {}
```

`src/main/java/com/vladoose/nir/service/ClientMailSendService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.UserAccountRepository;
import com.vladoose.nir.service.mail.*;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.util.EmailAddresses;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Письмо клиенту из АИС (спека 2026-10-19-client-mail-reply §3): проверить весь ввод → собрать документы КП и письмо →
 * отправить по SMTP info@ → копия в «Отправленные» → записать (ClientLetterRecorder, одна транзакция). Сбой SMTP — 502 со
 * своим текстом, ничего не записано. Письмо ушло, а запись упала — 200 с предупреждением: повторная отправка задвоила бы
 * письмо клиенту, а приём «Отправленных» допишет переписку сам (§13 п. 1). Повтор того же запроса (token) — 409 (§13 п. 2).
 */
@Service
public class ClientMailSendService {

    private static final Logger log = LoggerFactory.getLogger(ClientMailSendService.class);
    static final int TEXT_MAX = 20_000;
    static final int SUBJECT_MAX = 255;
    static final int MAX_ADDRESSES = 10;
    static final int MAX_FILES = 10;
    static final long LETTER_MAX = 20L * 1024 * 1024;
    static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    public static final String NOT_RECORDED =
            "Письмо ушло, но в АИС не записалось — оно появится в переписке после проверки почты (до минуты)";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Письмо собрано и проверено: что отправить и что записать. */
    record Prepared(ClientLetter letter, ClientLetterRecorder.Sent sent) {}

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final ClientSmtpSettings smtp;
    private final ClientMailSender sender;
    private final ClientSentCopy copy;
    private final ClientLetterRecorder recorder;
    private final ClientMailComposeService compose;
    private final ChatMessageRepository messages;
    private final LeadService leads;
    private final PrivateRequestService privateRequests;
    private final ClientOfferService offers;
    private final ReadyOfferService readyOffers;
    private final ReplyTemplateService templates;
    private final UserAccountRepository users;

    public ClientMailSendService(ClientSmtpSettings smtp, ClientMailSender sender, ClientSentCopy copy,
                                 ClientLetterRecorder recorder, ClientMailComposeService compose,
                                 ChatMessageRepository messages, LeadService leads, PrivateRequestService privateRequests,
                                 ClientOfferService offers, ReadyOfferService readyOffers, ReplyTemplateService templates,
                                 UserAccountRepository users) {
        this.smtp = smtp;
        this.sender = sender;
        this.copy = copy;
        this.recorder = recorder;
        this.compose = compose;
        this.messages = messages;
        this.leads = leads;
        this.privateRequests = privateRequests;
        this.offers = offers;
        this.readyOffers = readyOffers;
        this.templates = templates;
        this.users = users;
    }

    public ClientMailSendResponse send(ClientMailSendRequest req, List<MultipartFile> files, String author) {
        Market market = MarketContext.get();
        String problem = smtp.problem(market);
        if (problem != null) throw new BadRequestException(problem);
        Prepared p = prepare(req, files == null ? List.of() : files, market, author);
        String token = req.token() == null || req.token().isBlank() ? null : req.token().strip();
        claim(token);
        MimeMessage mime;
        try {
            mime = ClientLetterBuilder.build(sender.session(), p.letter(), market.zone());
            sender.send(mime);
        } catch (MailException | MessagingException e) {
            release(token);
            log.warn("Письмо клиенту не ушло: {}", e.getClass().getSimpleName());
            throw new UpstreamException(ClientMailSender.failureText(e));
        } catch (RuntimeException e) {
            release(token);
            throw e;
        }
        copy.store(mime);
        try {
            ClientLetterRecorder.Result r = recorder.record(p.sent());
            log.info("Письмо клиенту отправлено: переписок {}, вложений {}", r.chatIds().size(), p.letter().attachments().size());
            return new ClientMailSendResponse(true, r.chatIds(), r.messageIds(), r.offersSent(), null);
        } catch (RuntimeException e) {
            log.warn("Письмо клиенту ушло, но не записалось: {}", e.getClass().getSimpleName());
            return new ClientMailSendResponse(false, List.of(), List.of(), List.of(),
                    p.sent().offerIds().isEmpty() ? NOT_RECORDED : NOT_RECORDED + ". Отметьте КП «Отправлено» вручную");
        }
    }

    /** Весь ввод — до отправки: текст, адреса, тема, цитата, файлы, КП, предел письма. */
    Prepared prepare(ClientMailSendRequest req, List<MultipartFile> files, Market market, String author) {
        String text = req.text() == null ? "" : req.text().strip();
        if (text.isEmpty()) throw new BadRequestException("Напишите текст письма");
        if (text.length() > TEXT_MAX) throw new BadRequestException("Текст длиннее " + TEXT_MAX + " символов");
        if (files.size() > MAX_FILES) throw new BadRequestException("Файлов не больше " + MAX_FILES);
        Long leadId = req.leadId() == null ? null : leads.get(req.leadId()).getId();
        Long tenderId = req.tenderId() == null ? null : privateRequests.findById(req.tenderId()).getId();

        List<String> to;
        String subject;
        ClientLetter.Quote quote = null;
        String inReplyTo = null;
        if (req.chatId() != null) {
            Chat chat = compose.mailboxChat(req.chatId());
            to = List.of(chat.getExternalChatId());
            if (req.replyToMessageId() != null) {
                ChatMessage parent = messages.findById(req.replyToMessageId())
                        .filter(m -> m.getChat().getId().equals(chat.getId()))
                        .orElseThrow(() -> new BadRequestException("Письмо, на которое вы отвечаете, — не из этой переписки"));
                subject = ClientLetterBuilder.replySubject(parent.getSubject(), parent.getSentAt(), market.zone());
                quote = compose.quoteOf(chat, parent);
                if (ClientLetterBuilder.isMessageId(parent.getExternalId())) inReplyTo = parent.getExternalId();
            } else {
                subject = subject(req.subject());
            }
        } else {
            to = EmailAddresses.parse(req.to());
            if (to.isEmpty()) throw new BadRequestException("Укажите адрес получателя");
            if (to.size() > MAX_ADDRESSES) throw new BadRequestException("Адресов не больше " + MAX_ADDRESSES);
            subject = subject(req.subject());
        }

        List<ClientLetter.Attachment> attachments = new ArrayList<>();
        long total = 0;
        for (MultipartFile f : files) {
            ClientLetter.Attachment a = new ClientLetter.Attachment(fileName(f), bytes(f), baseType(f.getContentType()));
            attachments.add(a);
            total += a.bytes().length;
        }
        if (total > LETTER_MAX) throw new BadRequestException("Письмо больше 20 МБ — уберите часть файлов");
        List<Long> offerIds = new ArrayList<>();
        for (ClientMailSendRequest.OfferAttachment a : req.offers() == null ? List.<ClientMailSendRequest.OfferAttachment>of() : req.offers()) {
            ClientOffer o = offers.get(a.id());
            if (tenderId != null && !tenderId.equals(o.getTenderId())) {
                throw new BadRequestException(OfferLabels.of(o) + " — не из этой заявки");
            }
            ClientLetter.Attachment doc = offerDocument(o, a.format());
            attachments.add(doc);
            total += doc.bytes().length;
            if (total > LETTER_MAX) throw new BadRequestException("Письмо больше 20 МБ — уберите часть файлов");
            if (!offerIds.contains(o.getId())) offerIds.add(o.getId());
        }

        String messageId = ClientLetterBuilder.messageId(smtp.username());
        String senderName = fullName(author);
        String signature = templates.signatureFor(market, senderName);
        ClientLetter letter = new ClientLetter(smtp.fromName(), smtp.username(), to, subject, text, signature, quote,
                inReplyTo, messageId, attachments);
        List<MailFile> mailFiles = attachments.stream()
                .map(a -> new MailFile(a.fileName(), a.contentType(), (long) a.bytes().length, a.bytes())).toList();
        EmailLetter stored = new EmailLetter(messageId, subject, ClientLetterBuilder.body(text, signature, null, market.zone()),
                OffsetDateTime.now(), smtp.username(), null, mailFiles);
        return new Prepared(letter, new ClientLetterRecorder.Sent(smtp.username(), to, stored,
                senderName != null ? senderName : author, author, leadId, tenderId, offerIds));
    }

    private ClientLetter.Attachment offerDocument(ClientOffer o, String rawFormat) {
        String format = rawFormat == null ? "" : rawFormat.strip().toUpperCase(Locale.ROOT);
        boolean file = o.getOrigin() == ClientOfferOrigin.FILE;
        switch (format) {
            case "PDF", "DOCX" -> {
                if (file) throw new BadRequestException(OfferLabels.of(o) + " из файла — прикладывается его файл");
                ClientOfferService.OfferFile f = "PDF".equals(format) ? offers.pdf(o.getId()) : offers.docx(o.getId());
                return new ClientLetter.Attachment(f.fileName(), f.bytes(), "PDF".equals(format) ? "application/pdf" : DOCX);
            }
            case "FILE" -> {
                if (!file) throw new BadRequestException(OfferLabels.of(o) + " — из конструктора: PDF или Word");
                ClientOfferFile f = readyOffers.file(o.getId());
                return new ClientLetter.Attachment(f.getFileName(), f.getContent(), baseType(f.getMimeType()));
            }
            default -> throw new BadRequestException("Формат КП — PDF, DOCX или FILE");
        }
    }

    private void claim(String token) {
        if (token == null) return;
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(TOKEN_TTL)));
        if (tokens.putIfAbsent(token, now) != null) {
            throw new ConflictException("Это письмо уже отправлено (или отправляется) — обновите переписку");
        }
    }

    private void release(String token) {
        if (token != null) tokens.remove(token);
    }

    private static String subject(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (s.isEmpty()) throw new BadRequestException("Укажите тему письма");
        if (s.length() > SUBJECT_MAX) throw new BadRequestException("Тема длиннее " + SUBJECT_MAX + " символов");
        return s;
    }

    static String fileName(MultipartFile f) {
        String n = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).replaceAll("[\\r\\n\\t]+", " ").strip();
        if (n.isEmpty()) return "файл";
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Файл «" + fileName(f) + "» не прочитался — приложите его снова");
        }
    }

    static String baseType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "application/octet-stream";
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private String fullName(String username) {
        if (username == null) return null;
        return users.findByUsername(username).map(UserAccount::getFullName).filter(n -> !n.isBlank()).orElse(null);
    }
}
```

`ClientMailController.java` — конструктор с `ClientMailSendService send`, метод (импорты `ClientMailSendRequest`, `ClientMailSendResponse`, `MediaType`, `MultipartFile`, `List`):

```java
    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ClientMailSendResponse send(@RequestPart("letter") ClientMailSendRequest letter,
                                       @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return send.send(letter, files == null ? List.of() : files, CurrentUser.name());
    }
```

- [ ] **Step 4: Зелёные**

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.*'`
Expected: PASS.

- [ ] **Step 5: Проверка приёма multipart с JSON-частью (MockMvc) — дописать в `ClientMailSendTest`**

```java
    @Autowired org.springframework.web.context.WebApplicationContext wac;

    @Test
    void multipartLetterPartBinds_overHttp() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String json = "{\"chatId\":" + chat.getId() + ",\"replyToMessageId\":" + letterIn.getId()
                + ",\"text\":\"Через HTTP\",\"offers\":[],\"token\":\"" + token() + "\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/client-mail/send")
                        .file(new MockMultipartFile("letter", "", "application/json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "a.txt", "text/plain", new byte[]{1}))
                        .header("X-Market", "KZ")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("zz-sender").roles("ADMIN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.recorded").value(true));
        MarketContext.set(Market.KZ);   // перехватчик рынка почистил контекст потока теста (CLAUDE.md §14)
        assertThat(fileNames(only())).containsExactly("a.txt");
    }
```

Run: `./gradlew test --tests 'com.vladoose.nir.clientmail.ClientMailSendTest'`
Expected: PASS.

- [ ] **Step 6: Мутации**
  - `claim`: без `putIfAbsent`-проверки → `sameTokenTwice_secondIs409_oneLetterDelivered` красный.
  - `prepare`: `inReplyTo` без `isMessageId` → `replyToLetterWithoutMessageId_noThreadHeaders` красный.
  - `prepare`: без проверки «письмо из этой переписки» → `validation_beforeSending_nothingSent` красный.
  - `send`: не освобождать токен при сбое SMTP → `smtpDown_502_…_tokenReleasedForRetry` красный.
  - `send`: запись в `try` убрать (исключение наружу) → `recordFailsAfterSend_200WithWarning…` красный.
  - `prepare`: КП другой заявки без проверки → `offerOfAnotherDeal_400_nothingSent` красный.
  После серии — `./gradlew compileJava`.

- [ ] **Step 7: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/request/ClientMailSendRequest.java src/main/java/com/vladoose/nir/dto/response/ClientMailSendResponse.java \
  src/main/java/com/vladoose/nir/service/ClientMailSendService.java src/main/java/com/vladoose/nir/controller/ClientMailController.java \
  src/test/java/com/vladoose/nir/clientmail/ClientMailSendTest.java src/test/java/com/vladoose/nir/clientmail/ClientMailSendServiceUnitTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): отправка письма клиенту — POST /api/client-mail/send (Re: в цепочке, файлы и КП, копия, запись, повтор — 409)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Лента сделки — переписки писем сделки

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java`
- Test: `src/test/java/com/vladoose/nir/deal/DealTimelineEmailTest.java`

**Interfaces:**
- Consumes: `DealEventRepository.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc`, `ChatRepository.findById`, `DealEventService.email`.
- Produces: лента заявки показывает сообщения переписок писем сделки (события `EMAIL`) от первого письма; события `EMAIL` строками не идут.

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/deal/DealTimelineEmailTest.java`:

```java
package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTimelineService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Письма сделки в ленте «Переписка и история» (спека 2026-10-19-client-mail-reply §7.1). */
@SpringBootTest
@Transactional
class DealTimelineEmailTest {

    @Autowired DealTimelineService timeline;
    @Autowired PrivateRequestService requests;
    @Autowired LeadRepository leads;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired DealEventService events;
    @Autowired EntityManager em;

    final OffsetDateTime t0 = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Chat emailChat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId("zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz").title("Клиент").build());
    }

    private Chat waChat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("zz" + System.nanoTime() + "@c.us").title("Клиент").build());
    }

    private void msg(Chat c, LeadDirection d, OffsetDateTime at, String text) {
        messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(d)
                .type(c.getChannel() == LeadChannel.EMAIL ? ChatMessageType.EMAIL : ChatMessageType.TEXT).body(text).sentAt(at).build());
    }

    private Tender withLead(Chat leadChat) {
        Tender t = requests.createEmpty();
        leads.save(Lead.builder().market(Market.KZ).channel(leadChat.getChannel()).source("whatsapp").externalId("zz" + System.nanoTime())
                .subject("Обращение").status(LeadStatus.CONVERTED).chat(leadChat).privateRequest(t).receivedAt(t0).build());
        return t;
    }

    private List<DealTimelineItem> items(Tender t) {
        em.flush();
        return timeline.forPrivateRequest(t.getId(), null, 50).items();
    }

    private static List<String> texts(List<DealTimelineItem> items) {
        return items.stream().filter(i -> "MESSAGE".equals(i.kind())).map(i -> i.message().getBody()).toList();
    }

    @Test
    void whatsappLead_letterFromDeal_letterAndReplyShown_earlierHidden() {
        Chat wa = waChat();
        Tender t = withLead(wa);
        msg(wa, LeadDirection.IN, t0.plusMinutes(1), "Привет из WhatsApp");
        Chat mail = emailChat();
        msg(mail, LeadDirection.IN, t0.plusMinutes(2), "Старое письмо до сделки");
        OffsetDateTime sent = t0.plusHours(1);
        msg(mail, LeadDirection.OUT, sent, "КП во вложении");
        events.email(t.getId(), mail.getId(), "КП", sent, "zz");
        msg(mail, LeadDirection.IN, sent.plusMinutes(30), "Спасибо, посмотрим");

        assertThat(texts(items(t))).containsExactly("Привет из WhatsApp", "КП во вложении", "Спасибо, посмотрим");
    }

    @Test
    void letterChatIsLeadChat_noDuplicates() {
        Chat mail = emailChat();
        Tender t = withLead(mail);
        OffsetDateTime sent = t0.plusHours(1);
        msg(mail, LeadDirection.OUT, sent, "Ответ");
        events.email(t.getId(), mail.getId(), "Re", sent, "zz");
        assertThat(texts(items(t))).containsExactly("Ответ");
    }

    @Test
    void emailEvents_notShownAsLines() {
        Tender t = requests.createEmpty();
        events.email(t.getId(), emailChat().getId(), "Тема", t0, "zz");
        assertThat(items(t)).noneMatch(i -> "EMAIL".equals(i.kind()));
    }

    @Test
    void removedChat_eventIgnored() {
        Tender t = requests.createEmpty();
        events.email(t.getId(), 987_654_321L, "Тема", t0, "zz");
        assertThat(items(t)).noneMatch(i -> "MESSAGE".equals(i.kind()));
    }
}
```

- [ ] **Step 2: Красный**

Run: `./gradlew test --tests 'com.vladoose.nir.deal.DealTimelineEmailTest'`
Expected: FAIL — `whatsappLead_…` (нет «КП во вложении» и ответа), `emailEvents_notShownAsLines` (строка `EMAIL` есть).

- [ ] **Step 3: Реализация в `DealTimelineService`**

1. Поле и конструктор: `private final ChatRepository chatRepository;` — последним параметром конструктора `ChatRepository chatRepository`.
2. `forPrivateRequest`:

```java
    @Transactional(readOnly = true)
    public DealTimelineResponse forPrivateRequest(Long id, OffsetDateTime before, int limit) {
        Tender t = privateRequests.findById(id);
        OffsetDateTime cursor = before == null ? FAR : before;
        int n = clamp(limit);
        List<DealTimelineItem> all = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        leads.findFirstByPrivateRequestId(t.getId()).ifPresent(lead -> addLead(all, lead, cursor, n, seen));
        all.add(item(t.getCreatedAt(), "CREATED", "Создана заявка " + t.getTenderNumber(), null, null, null));
        addSupply(all, t, zone(t));
        addOffers(all, t.getId());
        addExecution(all, t, zone(t));
        addEvents(all, t.getId());
        addEmailChats(all, t.getId(), cursor, n, seen);
        return page(all, cursor, n);
    }
```

3. `addLead(List<DealTimelineItem> all, Lead lead, OffsetDateTime cursor, int n, Set<Long> seen)` — хвост после вычисления `to` заменить вызовом:

```java
        addChatMessages(all, chatId, start, to, cursor, n, seen);
```

4. Новый метод (тело — прежний кусок `addLead` с отсевом уже взятых):

```java
    /**
     * Сообщения переписки [from, to) раньше курсора: n + 1 последних и все сообщения самой ранней взятой миллисекунды
     * (страница её не делит). Уже взятые — не повторяются: переписка обращения и переписка письма сделки бывают одной.
     */
    private void addChatMessages(List<DealTimelineItem> all, Long chatId, OffsetDateTime from, OffsetDateTime to,
                                 OffsetDateTime cursor, int n, Set<Long> seen) {
        List<ChatMessage> newestFirst = new ArrayList<>(messages.findForDeal(chatId, from, to, cursor, PageRequest.of(0, n + 1)));
        if (newestFirst.size() > n) {
            OffsetDateTime oldest = ms(newestFirst.get(newestFirst.size() - 1).getSentAt());
            OffsetDateTime next = oldest.plus(1, ChronoUnit.MILLIS);
            Set<Long> have = newestFirst.stream().map(ChatMessage::getId).collect(Collectors.toSet());
            messages.findForDeal(chatId, oldest.isBefore(from) ? from : oldest, next.isAfter(to) ? to : next, cursor,
                            PageRequest.of(0, SAME_TIME_MAX))
                    .stream().filter(m -> !have.contains(m.getId())).forEach(newestFirst::add);
            newestFirst.sort(Comparator.comparing(ChatMessage::getSentAt).thenComparing(ChatMessage::getId).reversed());
        }
        newestFirst.removeIf(m -> !seen.add(m.getId()));
        for (ChatMessageResponse m : chats.toResponses(newestFirst)) {
            all.add(new DealTimelineItem(m.getSentAt(), false, "MESSAGE", null, null, "CHAT", chatId, m));
        }
    }

    /** Переписки писем сделки (события EMAIL) — с первого письма сделки в каждую; удалённую переписку пропускаем. */
    private void addEmailChats(List<DealTimelineItem> all, Long tenderId, OffsetDateTime cursor, int n, Set<Long> seen) {
        Map<Long, OffsetDateTime> firstByChat = new LinkedHashMap<>();
        for (DealEvent e : events.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(tenderId, DealEventType.EMAIL)) {
            if (e.getRefId() != null) firstByChat.putIfAbsent(e.getRefId(), e.getOccurredAt());
        }
        for (Map.Entry<Long, OffsetDateTime> c : firstByChat.entrySet()) {
            Chat chat = chatRepository.findById(c.getKey()).orElse(null);
            if (chat == null || (chat.getMarket() != null && chat.getMarket() != MarketContext.get())) continue;
            addChatMessages(all, chat.getId(), c.getValue(), FAR, cursor, n, seen);
        }
    }
```

5. `addEvents`:

```java
    private void addEvents(List<DealTimelineItem> all, Long tenderId) {
        for (DealEvent e : events.findByTenderIdOrderByOccurredAtAscIdAsc(tenderId)) {
            if (e.getType() == DealEventType.EMAIL) continue;   // письмо показывается самим сообщением переписки (addEmailChats)
            all.add(toItem(e));
        }
    }
```

- [ ] **Step 4: Зелёные + соседи ленты**

Run: `./gradlew test --tests 'com.vladoose.nir.deal.*'`
Expected: PASS (прежние тесты ленты — без изменений).

- [ ] **Step 5: Мутации**
  - `addChatMessages`: без `removeIf(!seen.add)` → `letterChatIsLeadChat_noDuplicates` красный.
  - `addEvents`: без пропуска `EMAIL` → `emailEvents_notShownAsLines` красный.
  - `addEmailChats`: `from` = `FAR` раньше (взять с `t0` — показать всё) → `…earlierHidden` красный.
  После серии — `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java src/test/java/com/vladoose/nir/deal/DealTimelineEmailTest.java
git commit -m "$(cat <<'EOF'
feat(client-mail): лента сделки — переписки писем сделки с первого письма, без дублей с перепиской обращения

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Фронт — окно письма и «Чаты»

**Files:**
- Create: `frontend/src/app/shared/client-mail.ts`
- Create: `frontend/src/app/shared/email-composer.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/chats/chats.component.ts`
- Modify: `frontend/src/app/shared/chat-message.component.ts`

**Interfaces:**
- Consumes: `GET /api/client-mail/compose`, `POST /api/client-mail/send` (задачи 5–6).
- Produces: `<app-email-composer [target]="ComposeTarget" (sent)="…" (closed)="…">`; типы `ComposeTarget`, `ComposeContext`, `ClientMailSendBody`, `ClientMailSendResult`, `ReplyTemplate`, `ReplyTemplatesState`; `ApiService.getClientMailCompose`, `sendClientMail`, `getReplyTemplates`, `saveReplyTemplates`.

- [ ] **Step 1: Типы — `frontend/src/app/shared/client-mail.ts`**

```ts
/** Письма клиентам из АИС (спека 2026-10-19-client-mail-reply §4, §8). */
export type ReplyRole = 'FIRST_REPLY' | 'OFFER';

export interface ReplyTemplate { id: number | null; title: string; body: string; role: ReplyRole | null; }

export interface ComposeReply {
  messageId: number; subject: string | null; replySubject: string; sentAt: string;
  fromName: string | null; fromAddress: string; direction: 'IN' | 'OUT';
}

export interface ComposeOffer { id: number; label: string; origin: 'BUILDER' | 'FILE'; status: string; fileName: string | null; }

export interface ComposeContext {
  enabled: boolean; reason: string | null; from: string;
  chatId: number | null; chatTitle: string | null; to: string[];
  reply: ComposeReply | null; newSubject: string; firstLetter: boolean;
  templates: ReplyTemplate[]; signature: string;
  offers: ComposeOffer[]; offerId: number | null; leadId: number | null; tenderId: number | null;
}

/** Откуда открыли окно — ровно одно поле. */
export type ComposeTarget = { chatId?: number; leadId?: number; tenderId?: number; offerId?: number };

export interface OfferPick { id: number; format: 'PDF' | 'DOCX' | 'FILE'; }

export interface ClientMailSendBody {
  chatId: number | null; replyToMessageId: number | null; to: string | null; subject: string | null; text: string;
  leadId: number | null; tenderId: number | null; offers: OfferPick[]; token: string;
}

export interface ClientMailSendResult {
  recorded: boolean; chatIds: number[]; messageIds: number[]; offersSent: number[]; warning: string | null;
}

export interface ReplyTemplatesState {
  templates: ReplyTemplate[]; signature: string;
  sending: { enabled: boolean; mailbox: string; reason: string | null };
}

/** Файлы с компьютера — как предел запроса приложения (15 МБ, nginx фронта — тот же). */
export const UPLOAD_LIMIT = 15 * 1024 * 1024;
```

- [ ] **Step 2: `ApiService`**

В импорты:

```ts
import type { ClientMailSendBody, ClientMailSendResult, ComposeContext, ComposeTarget, ReplyTemplate, ReplyTemplatesState } from '../shared/client-mail';
```

Методы (рядом с «Готовое КП»):

```ts
  // === Письма клиентам из АИС (спека 2026-10-19-client-mail-reply) ===
  getClientMailCompose(t: ComposeTarget): Observable<ComposeContext> {
    const params: Record<string, number> = {};
    for (const k of ['chatId', 'leadId', 'tenderId', 'offerId'] as const) if (t[k] != null) params[k] = t[k]!;
    return this.http.get<ComposeContext>(`${this.base}/client-mail/compose`, { params });
  }
  sendClientMail(body: ClientMailSendBody, files: File[]): Observable<ClientMailSendResult> {
    const fd = new FormData();
    fd.append('letter', new Blob([JSON.stringify(body)], { type: 'application/json' }));
    for (const f of files) fd.append('files', f, f.name);
    return this.http.post<ClientMailSendResult>(`${this.base}/client-mail/send`, fd);
  }
  getReplyTemplates(): Observable<ReplyTemplatesState> {
    return this.http.get<ReplyTemplatesState>(`${this.base}/reply-templates`);
  }
  saveReplyTemplates(body: { templates: ReplyTemplate[]; signature: string }): Observable<ReplyTemplatesState> {
    return this.http.put<ReplyTemplatesState>(`${this.base}/reply-templates`, body);
  }
```

- [ ] **Step 3: Окно — `frontend/src/app/shared/email-composer.component.ts`**

```ts
import { ChangeDetectorRef, Component, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';
import { errorText } from './error-text';
import { UPLOAD_LIMIT } from './client-mail';
import type { ClientMailSendBody, ClientMailSendResult, ComposeContext, ComposeOffer, ComposeTarget, OfferPick, ReplyTemplate } from './client-mail';

interface Pick { on: boolean; word: boolean; }

function newToken(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID()
    : Date.now().toString(36) + Math.random().toString(36).slice(2);
}

/**
 * Окно письма клиенту с info@ (спека 2026-10-19-client-mail-reply §4): ответ в переписку или новое письмо, шаблоны,
 * подпись, файлы и КП сделки. Ленивое — хозяева держат его только внутри @defer. Подтверждения — строкой в самом окне:
 * общее окно подтверждения рисуется под модальными окнами.
 */
@Component({
  selector: 'app-email-composer',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="ec-overlay" (click)="tryClose()">
      <div class="ec-modal" role="dialog" aria-modal="true" [attr.aria-label]="title()" (click)="$event.stopPropagation()">
        <header class="ec-head">
          <h3>{{ title() }}</h3>
          <button type="button" class="btn btn-line btn-sm" (click)="tryClose()" [disabled]="sending" aria-label="Закрыть">✕</button>
        </header>
        <p class="ec-note" *ngIf="loading">Загружаю…</p>
        <p class="ec-err" *ngIf="loadError" role="alert">{{ loadError }}</p>
        <ng-container *ngIf="ctx as c">
          <p class="ec-err" *ngIf="!c.enabled">{{ c.reason }}</p>
          <div class="ec-meta">
            <div><span class="ec-k">От</span>{{ c.from }}</div>
            <ng-container *ngIf="c.reply && !newTopic; else fields">
              <div><span class="ec-k">Кому</span>{{ c.to.join(', ') }}</div>
              <div><span class="ec-k">Тема</span>{{ c.reply.replySubject }}</div>
              <div class="ec-sub">Ответ на письмо от {{ when(c.reply.sentAt) }} ·
                <button type="button" class="ec-link" (click)="newTopic = true">Новой темой</button></div>
            </ng-container>
            <ng-template #fields>
              <label class="ec-field"><span class="ec-k">Кому</span>
                <input [(ngModel)]="to" [readonly]="!!c.chatId" placeholder="адрес@почта.kz, второй@почта.kz" autocomplete="off" /></label>
              <label class="ec-field"><span class="ec-k">Тема</span><input [(ngModel)]="subject" maxlength="255" /></label>
              <div class="ec-sub" *ngIf="c.reply">
                <button type="button" class="ec-link" (click)="newTopic = false">Ответом на письмо от {{ when(c.reply.sentAt) }}</button></div>
            </ng-template>
          </div>
          <div class="ec-tpl" *ngIf="c.templates.length" role="group" aria-label="Шаблоны">
            <button type="button" class="btn btn-line btn-sm" *ngFor="let t of c.templates" (click)="useTemplate(t)">{{ t.title }}</button>
          </div>
          <div class="ec-ask" *ngIf="pendingTemplate">
            <span>Заменить текст на «{{ pendingTemplate.title }}»?</span>
            <button type="button" class="btn btn-primary btn-sm" (click)="applyTemplate(pendingTemplate)">Заменить</button>
            <button type="button" class="btn btn-line btn-sm" (click)="pendingTemplate = null">Нет</button>
          </div>
          <textarea class="ec-text" [(ngModel)]="text" rows="8" maxlength="20000" aria-label="Текст письма"></textarea>
          <div class="ec-sig" *ngIf="c.signature">{{ c.signature }}</div>
          <div class="ec-sub" *ngIf="c.reply && !newTopic">Ниже — цитата письма от {{ when(c.reply.sentAt) }}</div>
          <div class="ec-offers" *ngIf="c.offers.length">
            <span class="ec-k">КП</span>
            <div class="ec-offer" *ngFor="let o of c.offers">
              <label class="ec-check"><input type="checkbox" [checked]="pick(o).on" (change)="toggle(o, 'on')" />
                {{ o.label }} <span class="ec-muted">{{ o.origin === 'FILE' ? o.fileName : 'PDF' }}</span></label>
              <label class="ec-check" *ngIf="o.origin === 'BUILDER' && pick(o).on">
                <input type="checkbox" [checked]="pick(o).word" (change)="toggle(o, 'word')" /> + Word</label>
            </div>
          </div>
          <div class="ec-files">
            <label class="btn btn-line btn-sm ec-pick">📎 Файл<input type="file" multiple (change)="addFiles($event)" /></label>
            <span class="ec-sub" [class.ec-over]="overLimit()">{{ files.length ? size(filesTotal()) + ' из 15 МБ' : 'файлы — до 15 МБ вместе' }}</span>
          </div>
          <ul class="ec-list" *ngIf="files.length">
            <li *ngFor="let f of files; let i = index">
              <span class="ec-fname">{{ f.name }}</span> <span class="ec-muted">{{ size(f.size) }}</span>
              <button type="button" class="ec-link" (click)="files.splice(i, 1)" [attr.aria-label]="'Убрать ' + f.name">×</button>
            </li>
          </ul>
          <div class="ec-err" *ngIf="error" role="alert">{{ error }}</div>
          <div class="ec-ask" *ngIf="confirmClose">
            <span>Закрыть без отправки? Текст и файлы пропадут.</span>
            <button type="button" class="btn btn-danger btn-sm" (click)="closed.emit()">Закрыть</button>
            <button type="button" class="btn btn-line btn-sm" (click)="confirmClose = false">Нет</button>
          </div>
          <footer class="ec-actions">
            <button type="button" class="btn btn-line" (click)="tryClose()" [disabled]="sending">Отмена</button>
            <button type="button" class="btn btn-primary" *ngIf="c.enabled" (click)="send()" [disabled]="sending || !canSend()">
              {{ sending ? 'Отправляю…' : 'Отправить' }}</button>
          </footer>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    /* rgba-вуаль не токенизируется: затемнение уместно в обеих темах; окно выше дровера обращения (z-index 1000) */
    .ec-overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
                  justify-content: center; padding: 16px; }
    .ec-modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
                max-width: 720px; max-height: 92vh; overflow-y: auto; padding: 18px 20px; display: flex; flex-direction: column; gap: 10px; }
    .ec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .ec-head h3 { margin: 0; }
    .ec-meta { display: flex; flex-direction: column; gap: 4px; font-size: 13px; overflow-wrap: anywhere; }
    .ec-k { display: inline-block; min-width: 3.6em; color: var(--text-muted); }
    .ec-field { display: flex; align-items: center; gap: 8px; }
    .ec-field input { flex: 1; min-width: 0; padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px;
                      background: var(--surface); color: var(--text); font: inherit; }
    .ec-sub { font-size: 12px; color: var(--text-muted); }
    .ec-link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font: inherit; text-decoration: underline; }
    .ec-tpl { display: flex; gap: 6px; flex-wrap: wrap; }
    .ec-ask { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 8px 10px; border-radius: 8px; font-size: 13px;
              background: color-mix(in srgb, var(--warn) 8%, var(--surface)); border: 1px solid color-mix(in srgb, var(--warn) 40%, var(--border)); }
    .ec-text { width: 100%; box-sizing: border-box; resize: vertical; min-height: 160px; padding: 8px 10px; border: 1px solid var(--border);
               border-radius: 8px; background: var(--surface); color: var(--text); font: inherit; }
    .ec-sig { white-space: pre-wrap; font-size: 13px; color: var(--text-muted); border-left: 3px solid var(--border); padding-left: 8px; }
    .ec-offers { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
    .ec-offer { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
    .ec-check { display: inline-flex; gap: 6px; align-items: center; cursor: pointer; }
    .ec-muted { color: var(--text-muted); }
    .ec-files { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .ec-pick input { display: none; }
    .ec-over { color: var(--danger-text); }
    .ec-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
    .ec-fname { overflow-wrap: anywhere; }
    .ec-err { color: var(--danger-text); font-size: 13px; margin: 0; }
    .ec-note { color: var(--text-muted); margin: 0; }
    .ec-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .ec-overlay { padding: 0; align-items: stretch; }
      .ec-modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .ec-tpl .btn, .ec-actions .btn, .ec-pick, .ec-check, .ec-link { min-height: 40px; }
      .ec-link { display: inline-flex; align-items: center; }
      .ec-text, .ec-field input { font-size: 16px; }
    }
  `],
})
export class EmailComposerComponent implements OnInit {
  @Input({ required: true }) target!: ComposeTarget;
  @Output() sent = new EventEmitter<ClientMailSendResult>();
  @Output() closed = new EventEmitter<void>();

  ctx: ComposeContext | null = null;
  loading = true;
  loadError = '';
  newTopic = false;
  to = '';
  subject = '';
  text = '';
  files: File[] = [];
  error = '';
  sending = false;
  pendingTemplate: ReplyTemplate | null = null;
  confirmClose = false;
  private readonly picks = new Map<number, Pick>();
  private readonly token = newToken();

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.api.getClientMailCompose(this.target).subscribe({
      next: c => { this.ctx = c; this.prefill(c); this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.loadError = errorText(e); this.cdr.detectChanges(); },
    });
  }

  /** Новое письмо — адреса и тема из окна; первое письмо — шаблон «в первое письмо», письмо с КП — «в письмо с КП». */
  private prefill(c: ComposeContext) {
    this.to = c.to.join(', ');
    this.subject = c.newSubject || '';
    this.newTopic = !c.reply;
    const role = c.offerId != null ? 'OFFER' : c.firstLetter ? 'FIRST_REPLY' : null;
    const t = role ? c.templates.find(x => x.role === role) : undefined;
    this.text = t ? t.body : '';
    for (const o of c.offers) this.picks.set(o.id, { on: o.id === c.offerId, word: false });
  }

  title(): string {
    const c = this.ctx;
    if (!c) return 'Письмо клиенту';
    if (c.offerId != null) return 'Письмо с КП';
    return c.reply && !this.newTopic ? 'Ответить письмом' : 'Новое письмо';
  }

  when(iso: string): string {
    return new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  pick(o: ComposeOffer): Pick {
    let p = this.picks.get(o.id);
    if (!p) { p = { on: false, word: false }; this.picks.set(o.id, p); }
    return p;
  }

  toggle(o: ComposeOffer, k: keyof Pick) {
    const p = this.pick(o);
    p[k] = !p[k];
  }

  /** Свой текст — не пусто и не текст какого-то шаблона. */
  private ownText(): boolean {
    const t = this.text.trim();
    return !!t && !(this.ctx?.templates || []).some(x => x.body.trim() === t);
  }

  useTemplate(t: ReplyTemplate) {
    if (this.ownText()) this.pendingTemplate = t;
    else this.applyTemplate(t);
  }

  applyTemplate(t: ReplyTemplate) {
    this.text = t.body;
    this.pendingTemplate = null;
  }

  addFiles(ev: Event) {
    const input = ev.target as HTMLInputElement;
    for (const f of Array.from(input.files || [])) this.files.push(f);
    input.value = '';
    this.error = this.overLimit() ? 'Файлы больше 15 МБ вместе — уберите часть' : '';
  }

  filesTotal(): number { return this.files.reduce((s, f) => s + f.size, 0); }
  overLimit(): boolean { return this.filesTotal() > UPLOAD_LIMIT; }

  size(n: number): string {
    return n >= 1024 * 1024 ? (n / 1024 / 1024).toFixed(1).replace('.', ',') + ' МБ' : Math.max(1, Math.round(n / 1024)) + ' КБ';
  }

  private replyMode(): boolean { return !!this.ctx?.reply && !this.newTopic; }

  canSend(): boolean {
    const c = this.ctx;
    if (!c || !this.text.trim() || this.overLimit()) return false;
    if (this.replyMode()) return true;
    return !!this.subject.trim() && (!!c.chatId || !!this.to.trim());
  }

  private offersBody(): OfferPick[] {
    const out: OfferPick[] = [];
    for (const o of this.ctx?.offers || []) {
      const p = this.picks.get(o.id);
      if (!p?.on) continue;
      if (o.origin === 'FILE') out.push({ id: o.id, format: 'FILE' });
      else {
        out.push({ id: o.id, format: 'PDF' });
        if (p.word) out.push({ id: o.id, format: 'DOCX' });
      }
    }
    return out;
  }

  send() {
    const c = this.ctx;
    if (!c || this.sending || !this.canSend()) return;
    const reply = this.replyMode();
    const body: ClientMailSendBody = {
      chatId: c.chatId, replyToMessageId: reply ? c.reply!.messageId : null,
      to: c.chatId ? null : this.to, subject: reply ? null : this.subject, text: this.text,
      leadId: c.leadId, tenderId: c.tenderId, offers: this.offersBody(), token: this.token,
    };
    this.sending = true;
    this.error = '';
    this.api.sendClientMail(body, this.files).subscribe({
      next: r => {
        this.sending = false;
        if (r.recorded) this.notify.success(r.offersSent.length ? 'Письмо с КП отправлено, КП — «Отправлено»' : 'Письмо отправлено');
        else this.notify.info(r.warning || 'Письмо отправлено');
        this.sent.emit(r);
      },
      error: e => {
        this.sending = false;
        this.error = e?.status === 413 ? 'Файлы больше 15 МБ вместе — уберите часть' : errorText(e);
        this.cdr.detectChanges();
      },
    });
  }

  tryClose() {
    if (this.sending) return;
    if (this.ownText() || this.files.length) this.confirmClose = true;
    else this.closed.emit();
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    if (this.confirmClose) this.confirmClose = false;
    else this.tryClose();
  }
}
```

- [ ] **Step 4: «Чаты» — `pages/chats/chats.component.ts`**
  1. Импорт `EmailComposerComponent` (в `imports` компонента) и `MarketService`; в конструктор — `private market: MarketService`.
  2. В `.thread-pane` после `<div class="thread" …>…</div>`:

```html
        <footer class="thread-foot" *ngIf="canMail()">
          <button type="button" class="btn btn-primary btn-sm" (click)="mailOpen = true">Ответить письмом</button>
        </footer>
```

  3. В самом конце шаблона (после `</div>` раскладки):

```html
    @defer (when mailOpen) {
      <app-email-composer *ngIf="mailOpen && openId !== null" [target]="{ chatId: openId! }"
                          (sent)="onMailSent()" (closed)="mailOpen = false"></app-email-composer>
    }
```

  4. Стили: `.thread-foot { border-top: 1px solid var(--border); padding: 8px 12px; display: flex; justify-content: flex-end; }`.
  5. Код:

```ts
  /** Окно письма (спека 2026-10-19-client-mail-reply §5 п. 1). */
  mailOpen = false;

  canMail(): boolean {
    return this.auth.isAdmin() && this.market.value === 'KZ' && this.chat?.channel === 'EMAIL';
  }

  onMailSent() {
    this.mailOpen = false;
    this.refreshThread();
    this.loadList(true);
    this.cdr.detectChanges();
  }
```

  6. Там, где из `?chatId=` ставится `openId` (подписка на параметры адреса), — `this.mailOpen = false;` (окно прежней переписки не остаётся над новой).

- [ ] **Step 5: Отправитель нашего письма — `shared/chat-message.component.ts`**

Строку подписи письма (`<div class="meta">письмо · <time …>`) заменить на:

```html
        <div class="meta">письмо<ng-container *ngIf="m.direction === 'OUT' && m.senderName"> · {{ m.senderName }}</ng-container> · <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time></div>
```

- [ ] **Step 6: Сборка и бандл**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка без ошибок; начальный бандл (строка `Initial total`) — не больше, чем до задачи (1,39 МБ): окно — отдельным ленивым чанком. Проверка маркером: `grep -l "Ответить письмом\|ec-overlay" dist/*/browser/*.js` — `ec-overlay` только в чанке, который не грузится начальным (`main-*.js` его не содержит).

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/shared/client-mail.ts frontend/src/app/shared/email-composer.component.ts frontend/src/app/services/api.service.ts \
  frontend/src/app/pages/chats/chats.component.ts frontend/src/app/shared/chat-message.component.ts
git commit -m "$(cat <<'EOF'
feat(client-mail): окно письма клиенту (шаблоны, подпись, файлы, КП) и «Ответить письмом» на «Чатах»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Фронт — карточка обращения, лента сделки, редактор КП

**Files:**
- Modify: `frontend/src/app/pages/leads/lead-card.component.ts`
- Modify: `frontend/src/app/shared/deal-timeline.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-page.component.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offer-editor.component.ts`

**Interfaces:**
- Consumes: `<app-email-composer>` (задача 8).
- Produces: `DealTimelineComponent` — `@Input() canMail = false`, `@Output() mailSent`.

- [ ] **Step 1: Карточка обращения**
  1. Импорт `EmailComposerComponent` (в `imports`) и `MarketService`; конструктор — `private market: MarketService`.
  2. В шапку раздела «Лента и переписка» (`<div class="section-head">`, после ссылки «Открыть всю переписку / весь чат»):

```html
                <button type="button" class="btn btn-primary btn-sm" *ngIf="canMail()" (click)="mailOpen = true">
                  {{ lead.chatChannel === 'EMAIL' ? 'Ответить письмом' : 'Написать письмо' }}</button>
```

  3. В конце шаблона, **вне** `div.overlay` карточки (клик в окне письма иначе дошёл бы до вуали карточки и закрыл её):

```html
    @defer (when mailOpen) {
      <app-email-composer *ngIf="mailOpen && lead" [target]="{ leadId: lead.id }"
                          (sent)="onMailSent()" (closed)="mailOpen = false"></app-email-composer>
    }
```

  4. Код:

```ts
  /** Окно письма (спека 2026-10-19-client-mail-reply §5 п. 2). */
  mailOpen = false;

  canMail(): boolean {
    return this.auth.isAdmin() && this.market.value === 'KZ' && !!this.lead
      && (this.lead.chatChannel === 'EMAIL' || !!this.lead.contactEmail);
  }

  onMailSent() {
    this.mailOpen = false;
    if (this.lead) this.load(this.lead.id);
    this.changed.emit();
  }
```

  5. `onEscape()`: первой строкой `if (this.mailOpen) return;` (Esc закрывает окно письма, а не карточку); в `ngOnChanges` при смене `leadId` — `this.mailOpen = false;`.

- [ ] **Step 2: Лента сделки — `shared/deal-timeline.component.ts`**
  1. Импорт `EmailComposerComponent` (в `imports`).
  2. Входы и выход:

```ts
  /** Письмо клиенту из сделки — только у частной заявки рынка ящика клиентов (спека 2026-10-19-client-mail-reply §5 п. 3). */
  @Input() canMail = false;
  @Output() mailSent = new EventEmitter<void>();
  mailOpen = false;
```

  3. В `.tl-buttons`, внутри `<ng-container *ngIf="canWrite">`, после «+ Звонок»:

```html
          <button type="button" class="btn btn-line btn-sm" *ngIf="canMail && kind === 'private-request'" (click)="mailOpen = true">+ Письмо</button>
```

  4. В конце шаблона:

```html
    @defer (when mailOpen) {
      <app-email-composer *ngIf="mailOpen" [target]="{ tenderId: dealId }" (sent)="onMailSent()" (closed)="mailOpen = false"></app-email-composer>
    }
```

  5. Код:

```ts
  onMailSent() {
    this.mailOpen = false;
    this.refresh();
    this.mailSent.emit();
  }
```

  6. `writeLink()`: ветку `mailto:` — только `if (this.email && !this.canMail)` (письмо пишется в АИС, а не в почтовой программе); в `ngOnChanges` при смене `dealId` — `this.mailOpen = false;`.

- [ ] **Step 3: Страница заявки — `private-request-page.component.ts`**

У `<app-deal-timeline …>` добавить `[canMail]="isKz" (mailSent)="refresh()"` (у страницы уже есть геттер `isKz` и метод `refresh()`): письмо с КП меняет статус КП и этап — страница перечитывает вид.

- [ ] **Step 4: Редактор КП — `client-offer-editor.component.ts`**
  1. Импорт `EmailComposerComponent` (в `imports`).
  2. В обе панели (`*ngIf="!isFile(); else fileBar"` и `#fileBar`) — перед «Поделиться»:

```html
          <button type="button" class="btn btn-line" *ngIf="canMail()" (click)="mailOffer()" [disabled]="busy || conflict || exportQueued">Отправить письмом</button>
```

  3. В конце шаблона:

```html
    @defer (when mailOpen) {
      <app-email-composer *ngIf="mailOpen && offer" [target]="{ offerId: offer.id }" (sent)="onMailSent()" (closed)="mailOpen = false"></app-email-composer>
    }
```

  4. Код:

```ts
  /** Окно письма с КП (спека 2026-10-19-client-mail-reply §5 п. 4): сперва дописать правки — PDF собирается с сервера. */
  mailOpen = false;

  canMail(): boolean {
    const o = this.offer;
    return !!o && this.auth.isAdmin() && this.isKz() && (this.isFile() || o.totals.itemCount > 0);
  }

  mailOffer() {
    if (this.busy || this.exportQueued || !this.offer) return;
    this.exportQueued = true;
    this.render();
    this.flushThen(() => {
      this.exportQueued = false;
      this.mailOpen = true;
      this.render();
    });
  }

  onMailSent() {
    this.mailOpen = false;
    this.refreshStatus();
    this.render();
  }

  /** Письмо поставило «Отправлено» (у черновика) — статус, время и версия с сервера; правок за время окна не было. */
  private refreshStatus() {
    const o = this.offer;
    if (!o) return;
    const gen = this.gen;
    this.api.getClientOffer(o.id).subscribe({
      next: (r: ClientOffer) => {
        if (gen !== this.gen) return;
        o.status = r.status;
        o.sentAt = r.sentAt;
        o.version = r.version;
        this.statusSel = r.status;
        this.render();
      },
      error: () => { /* статус обновится при следующем открытии */ },
    });
  }
```

  5. В `load(id)` — `this.mailOpen = false;` рядом с остальными сбросами. (`ClientOffer.sentAt` в `shared/client-offer.ts` уже есть.)

- [ ] **Step 5: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: без ошибок; начальный бандл не вырос (лента сделки и карточка обращения — в начальном, окно — только в ленивом чанке).

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/leads/lead-card.component.ts frontend/src/app/shared/deal-timeline.component.ts \
  frontend/src/app/pages/private-requests/private-request-page.component.ts frontend/src/app/pages/client-offers/client-offer-editor.component.ts
git commit -m "$(cat <<'EOF'
feat(client-mail): письмо из карточки обращения, ленты сделки («+ Письмо») и редактора КП («Отправить письмом»)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Фронт — «Система → Шаблоны ответов»

**Files:**
- Create: `frontend/src/app/pages/reply-templates/reply-templates.component.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/layout.component.ts`, `frontend/src/app/app.config.ts`

**Interfaces:**
- Consumes: `ApiService.getReplyTemplates / saveReplyTemplates`, типы `ReplyTemplate`, `ReplyTemplatesState`.

- [ ] **Step 1: Страница — `pages/reply-templates/reply-templates.component.ts`**

```ts
import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';
import { errorText } from '../../shared/error-text';
import type { ReplyRole, ReplyTemplate, ReplyTemplatesState } from '../../shared/client-mail';

/** «Система → Шаблоны ответов» (спека 2026-10-19-client-mail-reply §6.4): кнопки окна письма клиенту и подпись. */
@Component({
  selector: 'app-reply-templates',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="rt-page">
      <h2>Шаблоны ответов</h2>
      <p class="subtitle">Кнопки в окне письма клиенту: нажатие ставит текст, перед отправкой его можно поправить. Рынок: <b>{{ marketLabel }}</b>.</p>
      <p class="rt-state" *ngIf="state" [class.rt-off]="!state.sending.enabled">
        {{ state.sending.enabled ? 'Отправка с ' + state.sending.mailbox + ': включена' : state.sending.reason + ' (DEPLOY.md §10)' }}</p>
      <p class="rt-note" *ngIf="loading">Загружаю…</p>
      <ng-container *ngIf="state">
        <div class="rt-card" *ngFor="let t of templates; let i = index; let last = last">
          <div class="rt-row">
            <input class="rt-title" [(ngModel)]="t.title" maxlength="60" placeholder="Название кнопки" [attr.aria-label]="'Название шаблона ' + (i + 1)" />
            <div class="rt-tools">
              <button type="button" class="btn btn-line btn-sm" [disabled]="i === 0" (click)="move(i, -1)" aria-label="Выше">↑</button>
              <button type="button" class="btn btn-line btn-sm" [disabled]="last" (click)="move(i, 1)" aria-label="Ниже">↓</button>
              <button type="button" class="btn btn-line btn-sm" (click)="templates.splice(i, 1)">Удалить</button>
            </div>
          </div>
          <textarea class="rt-body" [(ngModel)]="t.body" rows="5" maxlength="4000" [attr.aria-label]="'Текст шаблона ' + (i + 1)"></textarea>
          <label class="rt-role">Подставлять самому:
            <select [ngModel]="t.role" (ngModelChange)="setRole(t, $event)" [attr.aria-label]="'Подставлять самому — шаблон ' + (i + 1)">
              <option [ngValue]="null">—</option>
              <option [ngValue]="'FIRST_REPLY'">в первое письмо</option>
              <option [ngValue]="'OFFER'">в письмо с КП</option>
            </select>
          </label>
        </div>
        <button type="button" class="btn btn-line" (click)="add()">+ Шаблон</button>
        <h3 class="rt-sec">Подпись</h3>
        <p class="rt-note">Добавляется к каждому письму. <code>{{ employee }}</code> — ФИО отправителя из «Пользователей»; пусто — строки нет.</p>
        <textarea class="rt-body" [(ngModel)]="signature" rows="5" maxlength="1000" aria-label="Подпись"></textarea>
        <div class="rt-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
        </div>
      </ng-container>
    </div>
  `,
  styles: [`
    .rt-page { max-width: 820px; display: flex; flex-direction: column; gap: 10px; }
    .rt-page h2 { margin: 0; }
    .rt-state { margin: 0; font-size: 13px; color: var(--success-text); }
    .rt-state.rt-off { color: var(--warn-text); }
    .rt-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .rt-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px;
               background: var(--surface); }
    .rt-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .rt-title { flex: 1; min-width: 160px; padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                color: var(--text); font: inherit; font-weight: 600; }
    .rt-tools { display: flex; gap: 6px; }
    .rt-body { width: 100%; box-sizing: border-box; resize: vertical; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px;
               background: var(--surface); color: var(--text); font: inherit; }
    .rt-role { font-size: 13px; color: var(--text-muted); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .rt-sec { margin: 8px 0 0; }
    .rt-actions { display: flex; gap: 8px; }
    @media (max-width: 900px) {
      .rt-title, .rt-body { font-size: 16px; }
      .rt-tools { width: 100%; }
    }
  `],
})
export class ReplyTemplatesComponent implements OnInit {
  state: ReplyTemplatesState | null = null;
  templates: ReplyTemplate[] = [];
  signature = '';
  loading = true;
  saving = false;
  readonly employee = '{{сотрудник}}';

  constructor(private api: ApiService, private notify: NotificationService, private market: MarketService,
              private cdr: ChangeDetectorRef) {}

  get marketLabel(): string { return this.market.value === 'KZ' ? 'West-Med (KZ)' : 'Регион-Мед (РФ)'; }

  ngOnInit() {
    this.api.getReplyTemplates().subscribe({
      next: s => { this.apply(s); this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Шаблоны не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  private apply(s: ReplyTemplatesState) {
    this.state = s;
    this.templates = s.templates.map(t => ({ ...t }));
    this.signature = s.signature;
  }

  /** Метка «подставлять самому» — у одного шаблона: выбор снимает её с другого (сервер иначе ответит 400). */
  setRole(t: ReplyTemplate, role: ReplyRole | null) {
    if (role) for (const x of this.templates) if (x !== t && x.role === role) x.role = null;
    t.role = role;
  }

  move(i: number, d: number) {
    const [x] = this.templates.splice(i, 1);
    this.templates.splice(i + d, 0, x);
  }

  add() { this.templates.push({ id: null, title: '', body: '', role: null }); }

  save() {
    this.saving = true;
    this.api.saveReplyTemplates({ templates: this.templates, signature: this.signature }).subscribe({
      next: s => { this.apply(s); this.saving = false; this.notify.success('Шаблоны сохранены'); this.cdr.detectChanges(); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }
}
```

- [ ] **Step 2: Маршрут, меню, значок**
  1. `app.routes.ts` — после маршрута `email-template`:

```ts
      { path: 'reply-templates', canActivate: [adminGuard],
        loadComponent: () => import('./pages/reply-templates/reply-templates.component').then(m => m.ReplyTemplatesComponent) },
```

  2. `layout/layout.component.ts` — после ссылки «Шаблон письма КП»:

```html
            <a *ngIf="auth.isAdmin()" routerLink="/reply-templates" routerLinkActive="active">
              <svg lucideIcon="reply" [size]="16"></svg> Шаблоны ответов
            </a>
```

  3. `app.config.ts` — `LucideReply` в оба списка (импорт из `@lucide/angular` и `provideLucideIcons(…)`), рядом с `LucideStamp`.

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: без ошибок; страница — ленивый чанк.

- [ ] **Step 4: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/reply-templates/reply-templates.component.ts frontend/src/app/app.routes.ts \
  frontend/src/app/layout/layout.component.ts frontend/src/app/app.config.ts
git commit -m "$(cat <<'EOF'
feat(client-mail): страница «Система → Шаблоны ответов» — шаблоны, метки, подпись

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Прод — скрипт включения, DEPLOY.md §10, `.env.example`

**Files:**
- Create: `deploy/set-info-smtp.sh`
- Modify: `DEPLOY.md` (§10 — подраздел «Отправка писем клиентам из АИС»), `.env.example`

- [ ] **Step 1: Скрипт `deploy/set-info-smtp.sh`**

```bash
#!/usr/bin/env bash
# Включение отправки писем клиентам с info@westmed.kz из АИС (DEPLOY.md §10, спека 2026-10-19-client-mail-reply §10).
# Запуск — с Mac, командой оператора:
#   scp -q deploy/set-info-smtp.sh root@185.125.46.26:/root/ \
#     && ssh root@185.125.46.26 'bash /root/set-info-smtp.sh; rm -f /root/set-info-smtp.sh'
# Адрес и пароль info@ — из /srv/ais/.env (MAIL_CLIENTS_USERNAME / MAIL_CLIENTS_PASSWORD, их записал set-info-mail.sh):
# на экран и в argv не попадают. Сначала вход на smtps://smtp.mail.ru:465 (curl, учётные данные — конфигом со стандартного
# входа); не вошёл — .env не трогается. Потом копия .env.bak-…-smtp, ключи MAIL_CLIENTS_SMTP_*, пересоздание бэкенда и
# строка лога «Отправка писем клиентам».
set -u
cd /srv/ais || exit 1
get() { grep "^$1=" .env | tail -1 | cut -d= -f2-; }
BOX=$(get MAIL_CLIENTS_USERNAME)
P=$(get MAIL_CLIENTS_PASSWORD)
[ -n "$BOX" ] && [ -n "$P" ] || { echo "в .env нет MAIL_CLIENTS_USERNAME / MAIL_CLIENTS_PASSWORD — ничего не меняю"; exit 1; }
EB=$(printf %s "$BOX" | sed 's/[\\"]/\\&/g')
EP=$(printf %s "$P" | sed 's/[\\"]/\\&/g')
L=$(printf 'url = "smtps://smtp.mail.ru:465"\nuser = "%s:%s"\n' "$EB" "$EP" | curl -sS -m 25 -K - 2>&1)
RC=$?
[ "$RC" = 0 ] || { echo "вход на SMTP не прошёл (curl $RC) — ничего не меняю: $(printf %s "$L" | head -c 160)"; exit 1; }
echo "SMTP: вход в $BOX прошёл"
B=.env.bak-$(date +%F-%H%M)-smtp
cp -p .env "$B" && echo "копия .env — $B"
umask 077
{ [ -z "$(tail -c1 .env)" ] || echo >> .env; }
set_env() { grep -v "^$1=" .env > .env.new; printf '%s=%s\n' "$1" "$2" >> .env.new; cat .env.new > .env; rm -f .env.new; }
set_env MAIL_CLIENTS_SMTP_HOST smtp.mail.ru
set_env MAIL_CLIENTS_SMTP_PORT 465
set_env MAIL_CLIENTS_SMTP_SSL true
echo ".env: ключи MAIL_CLIENTS_SMTP_* записаны"
docker compose up -d --force-recreate ais-backend </dev/null 2>&1 | grep -v -i 'variable is not set' | tail -2
for i in $(seq 1 60); do
  docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -q 'Started Nir2Application' && break
  sleep 3
done
docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -E 'Started Nir2Application|Отправка писем клиентам' \
  | sed -E 's/^ais-backend-1 *\| //' | cut -c1-200
unset P EP L
```

Проверка синтаксиса: `bash -n deploy/set-info-smtp.sh` → без вывода.

- [ ] **Step 2: `.env.example`** — после блока `MAIL_CLIENTS_*`:

```
MAIL_CLIENTS_SMTP_HOST=                # письма клиентам из АИС: smtp.mail.ru (пусто — отправка выключена); вход — MAIL_CLIENTS_USERNAME / PASSWORD
MAIL_CLIENTS_SMTP_PORT=465
MAIL_CLIENTS_SMTP_SSL=true             # пустым не оставлять — пустой булев флаг роняет старт
MAIL_CLIENTS_FROM_NAME=West-Med        # имя в строке «От»
MAIL_CLIENTS_SENT_COPY_ENABLED=true    # копия письма клиенту — в «Отправленные» info@ (IMAP APPEND)
```

- [ ] **Step 3: DEPLOY.md §10** — в конце раздела подраздел:

```markdown
### Отправка писем клиентам из АИС (2026-10-19)
Ответ клиенту письмом с info@ прямо из АИС (окно на «Чатах», в карточке обращения, на странице заявки, в редакторе КП), шаблоны ответов («Система → Шаблоны ответов», V35), копия в «Отправленные» info@. Спека — `docs/superpowers/specs/2026-10-19-client-mail-reply-design.md`, механика — CLAUDE.md §8 «Письма клиентам из АИС». Код приезжает обычным деплоем (V35 заводит шаблоны); пока в `.env` нет `MAIL_CLIENTS_SMTP_HOST`, отправка выключена — окно письма показывает «Отправка писем клиентам не настроена».
- **Включение** (агент — только после явного «да» оператора на SSH; секреты на экран и в чат не выводить):
  1. **Оператор:** `! git push origin main` — код с ВЫКЛЮЧЕННОЙ отправкой. Агент проверяет, что бэкенд поднялся и V35 применена.
  2. **Оператор:** `! scp -q deploy/set-info-smtp.sh root@185.125.46.26:/root/ && ssh root@185.125.46.26 'bash /root/set-info-smtp.sh; rm -f /root/set-info-smtp.sh'` — вход на SMTP тем же паролем приложения info@, что у приёма; копия `.env.bak-…-smtp`, ключи `MAIL_CLIENTS_SMTP_*`, пересоздание бэкенда. Вход не прошёл — `.env` не тронут: пароль приложения без доступа SMTP → выпустить новый (Mail.ru → Безопасность → «Пароли для внешних приложений», полный доступ) и записать его скриптом `set-info-mail.sh` (DEPLOY.md §10, «Включение»), затем снова этот скрипт.
  3. **Оператор:** «ФИО» своей учётки в «Пользователях» — без него подпись письма без имени.
- **Приёмка:** письмо с личной почты на info@ → обращение → «Ответить письмом» (шаблон «Первое письмо») → ответ пришёл в цепочку личной почты, «От: West-Med», подпись с ФИО, цитата; в веб-почте info@ в «Отправленных» — **одна** копия (две — Mail.ru начал класть письма SMTP сам: `MAIL_CLIENTS_SENT_COPY_ENABLED=false`); через минуту в переписке АИС — без дубля; КП из редактора «Отправить письмом» → PDF во вложении, КП «Отправлено».
- **Откат настройкой:** убрать `MAIL_CLIENTS_SMTP_*` из `.env` → `docker compose up -d --force-recreate ais-backend`. Письма и переписки остаются.
- ⚠️ **Откат кода ниже V35:** прежний код не прочитает события сделки вида `EMAIL` (`@Enumerated(STRING)` → 500 в ленте заявки) — перед откатом `delete from deal_event where type = 'EMAIL';` (письма остаются в переписках). Таблицу `reply_template` прежний код не читает.
```

- [ ] **Step 4: Коммит**

```bash
git add deploy/set-info-smtp.sh DEPLOY.md .env.example
git commit -m "$(cat <<'EOF'
docs(client-mail): скрипт включения SMTP info@, DEPLOY.md §10, .env.example

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Полный прогон, живая проверка, ревью ветки, документация, слияние

**Files:**
- Modify: `CLAUDE.md` (§7 модель данных, §8 новый блок, §9 почта, §12 фронт, §13 число тестов, §15 API, §16 бэклог), `docs/PROGRESS.md` («▶ Последняя задача»), `docs/superpowers/specs/2026-10-19-client-mail-reply-design.md` (§14 «Решения при реализации», если код разошёлся с текстом)

- [ ] **Step 1: Полный прогон**

Остановить `bootRun` и `devMail`: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`.
Run: `./gradlew cleanTest test` (sandbox off, ≈ 20 мин)
Expected: 0 падений (было 2130; добавится ~55). Падение — разбираться (`superpowers:systematic-debugging`), не править ожидания.

- [ ] **Step 2: Стенд**

Записать до проверки: `select max(id) from lead; select max(id) from chat; select max(id) from chat_message; select max(id) from tender; select max(id) from client_offer; select max(id) from deal_event; select max(id) from user_account;` (psql — CLAUDE.md §5) и текущие шаблоны `select * from reply_template order by market, position;`.
Поднять (sandbox off, в фоне): `./gradlew devMail`; бэкенд:

```
MAIL_CLIENTS_ENABLED=true MAIL_CLIENTS_HOST=127.0.0.1 MAIL_CLIENTS_PORT=3143 MAIL_CLIENTS_PROTOCOL=imap \
MAIL_CLIENTS_USERNAME=info@westmed.kz MAIL_CLIENTS_PASSWORD=secret MAIL_CLIENTS_MARKET=KZ \
MAIL_CLIENTS_POLL_MS=15000 MAIL_CLIENTS_INITIAL_DELAY_MS=3000 \
MAIL_CLIENTS_SMTP_HOST=127.0.0.1 MAIL_CLIENTS_SMTP_PORT=3025 MAIL_CLIENTS_SMTP_SSL=false \
JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun
```

`cd frontend && npm start`. У учётки `admin` — ФИО «Администратор» (подпись покажет его).

Письма в ящике клиента GreenMail: `python3 -c "import imaplib,email;M=imaplib.IMAP4('127.0.0.1',3143);M.login('zz.clinic@example.kz','zz.clinic@example.kz');M.select('INBOX',readonly=True);t,d=M.search(None,'ALL');print(d)"` (GreenMail заводит адресата с паролем = адрес); копия — `M.login('info@westmed.kz','secret');M.select('Sent',readonly=True)`.

- [ ] **Step 3: Сценарий (KZ, 1280 px, светлая тема, admin)**
  1. `python3 scripts/dev-mail.py client zz.clinic@example.kz` → через ≤ 15 с обращение «Новое» «Почта».
  2. «Чаты» → переписка → «Ответить письмом» → в поле — шаблон «Первое письмо», подпись «С уважением, Администратор / West-Med / …», «Ниже — цитата письма от …» → «Отправить» → тост «Письмо отправлено», письмо справа в ленте («письмо · Администратор · ЧЧ:ММ»), обращение «В работе».
  3. Ящик клиента в GreenMail: тема «Re: …», `In-Reply-To` = Message-ID письма клиента, «От: West-Med <info@westmed.kz>», цитата с «> »; «Sent» info@ — одна копия «прочитано»; через 15 с в переписке АИС — без дубля.
  4. Шаблоны: «Запрос принят» поверх своего текста → строка «Заменить текст?» → «Заменить»; «Отмена» со своим текстом → «Закрыть без отправки?».
  5. «Новой темой» → «Кому» только для чтения, тема «Счёт» → письмо без цитаты.
  6. Карточка обращения → «Ответить письмом» → отправка → карточка перечитана; Esc в окне закрывает окно, а не карточку.
  7. «Создать заявку» → страница заявки → «+ Письмо» → ответ в переписку обращения; «📎 КП»: создать КП клиенту → снова «+ Письмо» → КП в списке «📎 КП» → отметить, «+ Word» → отправить → письмо с PDF и DOCX у клиента, КП «Отправлено», этап «КП отправлено», письмо в ленте сделки.
  8. Редактор КП (второе КП, черновик) → «Отправить письмом» → тема «Re: …» (переписка есть), «Новой темой» → тема «Коммерческое предложение № N от … – West-Med» → отправка → статус «Отправлено» в селекте без перезагрузки.
  9. Обращение из WhatsApp (стаб WAHA не нужен — завести строкой `lead` с `contact_email` и чатом WhatsApp в psql либо пропустить пункт с пометкой) — «Написать письмо» → новое письмо на почту контакта, в ленте обращения строка «Сообщение клиенту: Письмо клиенту «…» на …».
  10. Ошибки: остановить devMail (`lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`) → «Отправить» → плашка «Письмо не ушло: почтовый сервер не отвечает — попробуйте ещё раз», текст на месте; запустить devMail → «Отправить» снова → ушло (токен освобождён).
  11. «Система → Шаблоны ответов»: строка «Отправка с info@westmed.kz: включена»; поменять текст, метку «в первое письмо» на другой шаблон (с прежнего снимается сама), подпись → «Сохранить» → окно письма берёт новое.
  12. 390 px: окно во весь экран, кнопки шаблонов и действий 40 px, нет горизонтальной прокрутки; страница шаблонов — карточки в столбик.
  13. Тёмная тема: окно, плашки, подпись.
  14. Оператор (`operator`): кнопок «Ответить письмом» / «+ Письмо» / «Отправить письмом» нет, пункта «Шаблоны ответов» нет; в DevTools — 0 запросов к `/api/client-mail`.
  15. Рынок РФ: на странице заявки РФ «+ Письмо» нет.

- [ ] **Step 4: Уборка**

Остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), потом SQL (только dev; `X` — записанные максимумы):

```sql
delete from deal_event where id > <max_deal_event>;
delete from client_offer where id > <max_client_offer>;
delete from lead where id > <max_lead>;
delete from tender where id > <max_tender>;
delete from chat where id > <max_chat>;                    -- сообщения и файлы каскадом
delete from inbound_email where mailbox = 'info@westmed.kz';
delete from mail_cursor where mailbox = 'info@westmed.kz';
```

Шаблоны — вернуть записанные (если правились). Остальное — по LISTEN-портам: 3143 (devMail), 4200.

- [ ] **Step 5: Ревью ветки — самому (CLAUDE.md §3)**

`git diff main...feature/client-mail-reply` целиком свежим взглядом: гарды (переписка — канал, ящик, рынок; обращение, заявка, КП — их сервисы), ввод до отправки, `@Transactional` записи, журнал без адресов, тексты клиенту без «—», окно только внутри `@defer`, классы фронта с приставкой, объекты-входы — литералы. Найденное — тем же порядком (тест красный до правки), мелочи — в CLAUDE.md §16.

- [ ] **Step 6: Документация**
  - `CLAUDE.md` §7 — `reply_template` (V35), событие сделки `EMAIL`; §8 — блок «Письма клиентам из АИС (info@) …» с механикой, проверкой, ревью; §9 — отправка с info@ (`ClientMailSender`, копия `ClientSentCopy`, дедуп копии приёмом по Message-ID); §12 — фронт окна и страницы шаблонов; §13 — число тестов полного прогона; §15 — пути §8 спеки; §16 — что вне рамок (спека §12), мелочи ревью; V35 в списке миграций §16 («следующая свободная — V36»); §5 — локальный запуск с `MAIL_CLIENTS_SMTP_*`.
  - `docs/PROGRESS.md` — «▶ Последняя задача»: что сделано, проверено, не запушено, порядок включения на проде (DEPLOY.md §10), следующий пункт очереди — отслеживание нашей продукции в тендерах.
  - Спека §14 «Решения при реализации» — если код разошёлся с текстом.

- [ ] **Step 7: Коммит документации и слияние**

```bash
git add CLAUDE.md docs/PROGRESS.md docs/superpowers/specs/2026-10-19-client-mail-reply-design.md
git commit -m "$(cat <<'EOF'
docs(client-mail): CLAUDE.md §5/§7/§8/§9/§12/§13/§15/§16, PROGRESS

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
git checkout main && git merge --ff-only feature/client-mail-reply && git branch -d feature/client-mail-reply
```

Push — оператор: `! git push origin main` (CLAUDE.md §5). Включение отправки на проде — DEPLOY.md §10 «Отправка писем клиентам из АИС» (скрипт `set-info-smtp.sh` командой оператора).
