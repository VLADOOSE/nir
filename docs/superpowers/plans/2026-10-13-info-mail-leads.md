# Письма клиентов на info@ → «Обращения» — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** письмо клиента на info@westmed.kz становится обращением «Почта» в «Обращениях», переписка (письма клиента и наши ответы из «Отправленных») видна в карточке обращения и на «Чатах»; служебное, рассылки и возвраты ложатся во «Входящие».

**Architecture:** второй IMAP-ящик со своими настройками (`mail.clients.*`) и своим потоком читает «Входящие» и «Отправленные» только на чтение, у каждой папки свой курсор по UID. Проход по папке и политика сбоев — общие с zakup@ (выносятся в `MailFolderPass`). Письмо из «Входящих» маршрутизируется чистой функцией (`ClientMailRouter`): письмо собеседника — в почтовый чат (`channel = EMAIL`, переиспользуются таблицы и правила чатов WhatsApp: `ChatLeadRules`, `LeadIntakeService`), остальное — строкой «Входящих». Наше письмо из «Отправленных» — в чат с каждым внешним адресатом и переводит «Новое» → «В работе».

**Tech Stack:** Spring Boot 3.5.6 / Java 17 / Hibernate 6 / PostgreSQL 17 (nirdb), Flyway V29, Jakarta Mail / Angus 2.0.x (IMAP, `org.eclipse.angus.mail.imap.IMAPFolder`), GreenMail 2.1.2 в тестах, Angular 21.

**Spec:** `docs/superpowers/specs/2026-10-13-info-mail-leads-design.md` (утверждена оператором 2026-10-13).

**Ledger:** `.superpowers/sdd/2026-10-13-info-mail-leads/progress.md` (git-ignored).

## Global Constraints

- Ящик info@ только читает: IMAP EXAMINE (`Folder.READ_ONLY`) и `mail.<протокол>.peek=true` — тот же `ImapMailboxConnector`, что у zakup@; `\Seen` код не ставит никогда.
- Приём info@ выключен по умолчанию (`MAIL_CLIENTS_ENABLED=false`). Приём zakup@ (`mail.imap.*`, `MailReceiveService`, `MailPollScheduler`, Telegram) по поведению не меняется: все прежние почтовые тесты остаются зелёными без правки ожиданий (меняется только ключ курсора в вызовах репозитория).
- Об info@ АИС в Telegram не пишет: у строк «Входящих» ящика info@ поля `notify_*` пустые (спека §4).
- Рынок ящика — явно в потоке `mail-clients` (`MarketContext.set`, `clear()` в `finally`); работа с БД — в `@Transactional`-методе ОТДЕЛЬНОГО бина (CLAUDE.md §6).
- Первый запуск папки (курсора нет или сменился `UIDVALIDITY`) — окно 7 дней (`MAIL_CLIENTS_SINCE_DAYS`); предел файла — 25 МБ (`MAIL_CLIENTS_MAX_FILE_MB`, не больше 100); служебные домены по умолчанию `*.mail.ru,2gis.ru,2gis.com,ecc.kz,goszakup.gov.kz` («2gis.ru» — домен и поддомены, «*.mail.ru» — только поддомены); служебные имена — `noreply, no-reply, not-reply, donotreply, do-not-reply, mailer-daemon, postmaster`; свой домен — домен ящика (`westmed.kz`).
- Обращение из письма: статус «Новое», канал `EMAIL`, источник `email`, тема — тема письма (пустая — «Письмо»), текст — без цитаты, не длиннее 4000 символов; «Превращено в заявку» открыто 30 дней (`chats.whatsapp.lead-converted-days`).
- Адреса и тексты писем клиентов — персональные данные: в лог только адрес нашего ящика, UID, классы исключений и текст ответа почтового сервера. Не печатать содержимое `~/.config/ais/*.pass`.
- Схема — только новой миграцией V29; V1–V28 не трогать.
- Записи — `@PreAuthorize("hasRole('ADMIN')")`; оператор видит всё, кнопок записи у него нет.
- Пустые числовые/булевы ключи в `.env` не оставлять: пустое значение валит старт (CLAUDE.md §14).
- Фронт: файлы `/api` — только `HttpClient` blob; цвета — токены (хекс — только непрозрачность маски `#000`, с комментарием); цели ≥ 40 px при ≤ 900 px; `@media` — последним блоком `styles`.
- Gradle и psql — с `dangerouslyDisableSandbox: true`; перед `./gradlew test` остановить `./gradlew devMail` (порт 3143): `lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`.
- Каждый коммит оканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Push в `main` — только оператор (`! git push origin main`).

## Review Focus

1. **Наш ответ из «Отправленных» сразу нескольким адресатам** (клиенту, второму клиенту в «Копии», коллеге `@westmed.kz`, поддержке `support@corp.mail.ru`) — письмо ложится в переписку с каждым внешним адресатом, коллеге и службам переписка не заводится. Тест — задача 7 `sentReplyGoesToEachExternalRecipient_notToColleaguesOrServices`.
2. **Клиент с личного ящика Mail.ru** (`ivanov@mail.ru`, `@bk.ru`) — обращение, а `security@id.mail.ru` / `info@corp.mail.ru` — служебные; `buyer@notgoszakup.gov.kz` — не поддомен госзакупа. Тест — задача 5 `personalMailRuIsConversation_subdomainIsService` и `service_byDomainWithSubdomains`.
3. **Большое вложение** (больше предела) — письмо всё равно становится сообщением и обращением, файл — названием «больше предела — откройте в почте», в память читается не больше предела + 1 байт. Тесты — задача 2 `files_collectedUnderLimit_overLimitWithoutBytes`, задача 6 `filesAreStored_bigOneMarkedTooLarge`.
4. **Письмо целиком из цитаты** (переслали без своего текста, ответ «> …») — текст в переписке и обращении не пустой. Тест — задача 6 `letterThatIsAllQuote_keepsItsText`.
5. **Ящик без «Отправленных» или папка с локализованным именем** — «Входящие» всё равно обрабатываются, предупреждение в лог один раз; у Mail.ru папка находится по признаку `\Sent`. Тесты — задача 3 `SentFoldersTest`, задача 8 `sentFolderMissing_inboxStillProcessed_warnOnce`.

## Решения при планировании (где план уточняет текст спеки)

1. **У письма несколько файлов** (§2.3 «вложения — все») — V29 снимает `UNIQUE (message_id)` у `chat_attachment`, связь файла с сообщением — `@ManyToOne`; ответ API несёт `attachments` (список) и прежнее `attachment` (первый файл — экраны WhatsApp и тесты не меняются).
2. **Ширина колонок:** `chat.account` и `chat.external_chat_id` — `VARCHAR(320)` (адреса почты длиннее номеров WhatsApp). Ключ письма в переписке (`chat_message.external_id`, 100) — Message-ID; длиннее 100 символов — `sha256:<hex>`; без Message-ID — `uid:<папка>:<UIDVALIDITY>:<UID>`.
3. **Наше письмо берёт обращение «В работу», только если написано не раньше обращения.** Первый запуск читает «Отправленные» после «Входящих»: письмо, которое мы написали ДО письма клиента, иначе перевело бы свежее обращение в работу.
4. **Письмо без адреса отправителя** — во «Входящие» (как служебное): переписку вести не с кем.
5. **Фильтр «Входящих»** — список ящиков с сервера (`GET /api/inbound/mailboxes`), подпись — локальная часть («zakup@», «info@»); чипов нет, пока ящик один.
6. **Причина `NOT_CLIENT` — только кнопкой «Не клиент»** (она же отмечает переписку); `POST /api/leads/{id}/close` с этой причиной — 400.
7. **«Создать обращение» на «Чатах» у почтовой переписки** — обращение канала «Почта»: тема — тема последнего письма собеседника (нет — «Письмо»), контакт — адрес переписки.
8. **Общий код с zakup@:** сдвиг курсора — `MailCursorRepository.advance`, проход по папке — `MailFolderPass` (поведение zakup@ то же, его тесты — сетка регрессии).
9. **Пересланное письмо во вложении** (`message/rfc822`) — файлом «письмо.eml» (имя из письма, если есть).
10. **Фильтр «Почта» в «Обращениях»** — в выпадающем списке каналов его нет (спека считала, что есть): добавляется.
11. **Строка состояния WhatsApp на «Чатах»** скрывается при фильтре «Почта» и при открытой почтовой переписке.
12. **Длинное письмо в ленте свёрнуто** — больше 600 символов или 10 строк; картинки из письма — без миниатюр, по нажатию открываются крупно в странице (как фото WhatsApp), остальные файлы скачиваются.
13. **Ошибка настройки** (ящик совпадает с `MAIL_IMAP_USERNAME`, неизвестный рынок, пустой адрес) — приём info@ выключен, ERROR при старте.
14. **Скрипт раскатки** сначала проверяет вход по IMAP и только потом пишет `.env` (спека §6 называет шаги в другом порядке — так безопаснее).

## Структура файлов

**Бэкенд — новые:**
- `src/main/resources/db/migration/V29__info_mail_leads.sql` — тема письма, ширина колонок чата, несколько файлов у сообщения, курсор «ящик + папка».
- `entity/MailCursorKey.java` — составной ключ курсора.
- `service/mail/MailFile.java` — файл письма (имя, тип, размер, байты).
- `service/mail/MailFolder.java` — роль папки: INBOX / SENT.
- `service/mail/SentFolders.java` — выбор «Отправленных» по списку папок (чистая функция).
- `service/mail/MailFolderPass.java` — проход по папке и политика сбоев (общие у zakup@ и info@).
- `service/mail/ClientMailRoute.java`, `ClientMailRules.java`, `ClientMailRouter.java` — правила §2.2.
- `service/mail/ClientMailSettings.java` — настройки `mail.clients.*`.
- `service/mail/ClientMailbox.java` — IMAP-ящик info@ (папки, файлы по пределу).
- `service/mail/EmailLetter.java` — письмо для переписки (ключ, тема, текст без цитаты, файлы).
- `service/mail/ClientMailWriter.java` — запись письма и курсора папки (транзакция на письмо).
- `service/EmailChatWriter.java` — почтовый чат, сообщение, файлы, правила обращений.
- `service/ClientMailReceiveService.java`, `service/ClientMailScheduler.java` — проход по двум папкам, поток `mail-clients`.

**Бэкенд — изменяемые:** `entity/MailCursor.java`, `entity/ChatMessage.java`, `entity/ChatAttachment.java`, `entity/Chat.java`, `entity/ChatMessageType.java`, `entity/LeadCloseReason.java`, `repository/MailCursorRepository.java`, `repository/LeadRepository.java`, `repository/ChatMessageRepository.java`, `repository/ChatAttachmentRepository.java`, `repository/InboundEmailRepository.java`, `service/mail/ParsedMail.java`, `service/mail/MailParser.java`, `service/mail/ImapMailboxConnector.java`, `service/mail/MailClassifier.java`, `service/mail/MailIngestWriter.java`, `service/MailReceiveService.java`, `service/ChatLeadRules.java`, `service/ChatService.java`, `service/LeadService.java`, `integration/lead/LeadSources.java`, `controller/LeadController.java`, `controller/InboundController.java`, `dto/response/{ChatMessageResponse,ChatResponse,ChatListItemResponse,LeadCardResponse,InboundEmailResponse}.java`, `mapper/LeadResponseMapper.java`, `src/main/resources/application.yaml`, `.env.example`.

**Фронт — изменяемые:** `services/api.service.ts`, `shared/chat-message.component.ts`, `pages/chats/chats.component.ts`, `pages/leads/lead-card.component.ts`, `pages/leads/leads.component.ts`, `pages/inbound/inbound.component.ts`.

**Стенд и раскатка:** `src/test/java/com/vladoose/nir/mail/DevMailServer.java`, `scripts/dev-mail.py`, `deploy/set-info-mail.sh`, `DEPLOY.md` (§10), `CLAUDE.md`, `docs/PROGRESS.md`.

---

### Task 1: V29 — курсор «ящик + папка», тема письма, несколько файлов у сообщения

**Files:**
- Create: `src/main/resources/db/migration/V29__info_mail_leads.sql`
- Create: `src/main/java/com/vladoose/nir/entity/MailCursorKey.java`
- Modify: `src/main/java/com/vladoose/nir/entity/MailCursor.java`
- Modify: `src/main/java/com/vladoose/nir/repository/MailCursorRepository.java`
- Modify: `src/main/java/com/vladoose/nir/entity/ChatMessage.java`, `ChatAttachment.java`, `Chat.java`, `ChatMessageType.java`
- Modify: `src/main/java/com/vladoose/nir/service/MailReceiveService.java:107`, `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java:135-145`
- Modify (ключ курсора): `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`, `service/MailReceiveServiceTest.java`, `service/mail/MailSchemaTest.java`, `service/mail/MailIngestWriterTest.java`, `email/KpRoundTripTest.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/MailSchemaTest.java`, `src/test/java/com/vladoose/nir/chat/ChatPersistenceTest.java`

**Interfaces:**
- Produces: `MailCursorKey(String mailbox, String folder)` + `MailCursorKey.inbox(String mailbox)`; `MailCursor.INBOX = "INBOX"`, `MailCursor.SENT = "SENT"`, builder `.folder(...)` (по умолчанию INBOX); `MailCursorRepository extends JpaRepository<MailCursor, MailCursorKey>` + `default void advance(MailCursorKey key, long uidValidity, long uid)`; `ChatMessage.subject` (String); `ChatMessageType.EMAIL`; `ChatAttachment.message` — `@ManyToOne`.

- [ ] **Step 1: Write the failing tests**

В `MailSchemaTest` (импорты уже `com.vladoose.nir.entity.*`) — заменить `cursorRepo.findById("zz-cursor@test.kz")` на `cursorRepo.findById(MailCursorKey.inbox("zz-cursor@test.kz"))` и добавить:

```java
    /** V29: у ящика курсор на каждую папку; строка без папки (прежняя строка zakup@) — «Входящие». */
    @Test
    void mailCursor_twoFoldersOfOneMailbox_legacyRowIsInbox() {
        cursorRepo.save(MailCursor.builder().mailbox("zz-two@test.kz").folder(MailCursor.SENT).uidValidity(3L).lastUid(9L)
                .updatedAt(OffsetDateTime.now()).build());
        em.flush();
        em.createNativeQuery("insert into mail_cursor (mailbox, uid_validity, last_uid) values ('zz-two@test.kz', 7, 100)")
                .executeUpdate();
        em.clear();

        assertThat(cursorRepo.findById(MailCursorKey.inbox("zz-two@test.kz")).orElseThrow().getLastUid()).isEqualTo(100L);
        assertThat(cursorRepo.findById(new MailCursorKey("zz-two@test.kz", MailCursor.SENT)).orElseThrow().getLastUid())
                .isEqualTo(9L);
    }

    /** Курсор папки назад не ходит; новый UIDVALIDITY — отсчёт заново. */
    @Test
    void mailCursor_advance_forwardOnly_newValidityRestarts() {
        MailCursorKey key = new MailCursorKey("zz-adv@test.kz", MailCursor.SENT);
        cursorRepo.advance(key, 5L, 10L);
        cursorRepo.advance(key, 5L, 8L);
        em.flush();
        em.clear();
        assertThat(cursorRepo.findById(key).orElseThrow().getLastUid()).isEqualTo(10L);

        cursorRepo.advance(key, 6L, 2L);
        em.flush();
        em.clear();
        MailCursor c = cursorRepo.findById(key).orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(6L);
        assertThat(c.getLastUid()).isEqualTo(2L);
        assertThat(c.getFolder()).isEqualTo(MailCursor.SENT);
    }
```

В `ChatPersistenceTest` (импорты: добавить `java.util.UUID`) — тест:

```java
    /** Письмо (V29): тема, адрес собеседника длиннее 100 символов, несколько файлов у одного сообщения. */
    @Test
    void letterKeepsSubjectLongAddressAndSeveralFiles() {
        MarketContext.set(Market.KZ);
        String address = "zz-" + UUID.randomUUID() + "@" + "x".repeat(70) + ".kz";
        Chat c = chatRepository.saveAndFlush(Chat.builder().channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId(address).title("Клиент").build());
        ChatMessage m = messageRepository.saveAndFlush(ChatMessage.builder().chat(c).externalId("<m1@x.kz>")
                .direction(LeadDirection.IN).type(ChatMessageType.EMAIL).subject("Запрос цены").body("Текст")
                .sentAt(OffsetDateTime.now()).build());
        attachmentRepository.saveAndFlush(ChatAttachment.builder().message(m).fileName("смета.xlsx")
                .sizeBytes(1L).content(new byte[]{1}).build());
        attachmentRepository.saveAndFlush(ChatAttachment.builder().message(m).fileName("ТЗ.pdf")
                .sizeBytes(1L).content(new byte[]{2}).build());
        em.clear();

        assertThat(messageRepository.findById(m.getId()).orElseThrow().getSubject()).isEqualTo("Запрос цены");
        assertThat(chatRepository.findById(c.getId()).orElseThrow().getExternalChatId()).isEqualTo(address);
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(m.getId()))).hasSize(2);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.MailSchemaTest' --tests 'com.vladoose.nir.chat.ChatPersistenceTest'`
Expected: FAIL — компиляция: `cannot find symbol MailCursorKey` / `folder(…)` / `advance` / `subject(…)` / `EMAIL`.

- [ ] **Step 3: Implement**

`src/main/resources/db/migration/V29__info_mail_leads.sql`:

```sql
-- Письма клиентов на info@westmed.kz → «Обращения» (спека docs/superpowers/specs/2026-10-13-info-mail-leads-design.md §5).

-- Письмо в переписке: тема. У сообщений WhatsApp — NULL.
ALTER TABLE chat_message ADD COLUMN subject TEXT;

-- Почтовая переписка: аккаунт — адрес ящика, собеседник — адрес почты (длиннее номеров WhatsApp).
ALTER TABLE chat ALTER COLUMN account TYPE VARCHAR(320);
ALTER TABLE chat ALTER COLUMN external_chat_id TYPE VARCHAR(320);

-- У письма может быть несколько файлов: «один файл на сообщение» снимается (у WhatsApp он и так один).
ALTER TABLE chat_attachment DROP CONSTRAINT chat_attachment_message_id_key;
CREATE INDEX idx_chat_attachment_message ON chat_attachment (message_id);

-- Курсор почты — на ящик и папку: у info@ читаются «Входящие» и «Отправленные». Прежняя строка zakup@ — «Входящие».
ALTER TABLE mail_cursor ADD COLUMN folder VARCHAR(20) NOT NULL DEFAULT 'INBOX';
ALTER TABLE mail_cursor DROP CONSTRAINT mail_cursor_pkey;
ALTER TABLE mail_cursor ADD PRIMARY KEY (mailbox, folder);
```

`entity/MailCursorKey.java`:

```java
package com.vladoose.nir.entity;

import lombok.*;

import java.io.Serializable;

/** Ключ курсора почты (V29): ящик + папка. */
@Getter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode @ToString
public class MailCursorKey implements Serializable {

    private String mailbox;
    private String folder;

    /** «Входящие» ящика — у zakup@ единственная читаемая папка. */
    public static MailCursorKey inbox(String mailbox) {
        return new MailCursorKey(mailbox, MailCursor.INBOX);
    }
}
```

`entity/MailCursor.java` — целиком:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Курсор приёма почты: последнее обработанное письмо папки ящика. Не рыночная таблица — ключ ящик + папка (V29). */
@Entity
@Table(name = "mail_cursor")
@IdClass(MailCursorKey.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MailCursor {

    /** Папка «Входящие». */
    public static final String INBOX = "INBOX";
    /** Папка «Отправленные» — её читает ящик info@ (наши ответы клиентам). */
    public static final String SENT = "SENT";

    /** Адрес ящика нижним регистром. */
    @Id
    @Column(length = 320)
    private String mailbox;

    /** Роль папки — INBOX или SENT, а не её имя на сервере: имя у почты своё и бывает локализованным. */
    @Id
    @Column(length = 20)
    @Builder.Default
    private String folder = INBOX;

    /** UIDVALIDITY папки: сменился — UID прежних писем больше ничего не значат. */
    @Column(name = "uid_validity", nullable = false)
    private long uidValidity;

    /** UID последнего обработанного письма; 0 — папка была пуста. */
    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

`repository/MailCursorRepository.java` — целиком:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public interface MailCursorRepository extends JpaRepository<MailCursor, MailCursorKey> {

    /**
     * Сдвиг курсора папки: в пределах одного UIDVALIDITY назад не ходит, новый UIDVALIDITY — отсчёт заново. Зовётся
     * внутри транзакции записи письма — курсор встаёт вместе с письмом.
     */
    default void advance(MailCursorKey key, long uidValidity, long uid) {
        MailCursor c = findById(key).orElse(null);
        if (c == null || c.getUidValidity() != uidValidity) {
            c = MailCursor.builder().mailbox(key.getMailbox()).folder(key.getFolder())
                    .uidValidity(uidValidity).lastUid(uid).build();
        } else if (uid > c.getLastUid()) {
            c.setLastUid(uid);
        }
        c.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        save(c);
    }
}
```

`service/mail/MailIngestWriter.java` — метод `moveCursor` целиком:

```java
    /** В пределах одного UIDVALIDITY курсор назад не ходит; новый UIDVALIDITY — отсчёт заново. */
    private void moveCursor(long uidValidity, long uid) {
        cursorRepo.advance(MailCursorKey.inbox(mailbox), uidValidity, uid);
    }
```

`service/MailReceiveService.java:107` — `cursorRepository.findById(writer.mailbox())` → `cursorRepository.findById(MailCursorKey.inbox(writer.mailbox()))`, импорт `com.vladoose.nir.entity.MailCursorKey`.

`entity/ChatMessage.java` — после поля `type`:

```java
    /** Тема письма (вид EMAIL); у сообщений WhatsApp — null. */
    @Column(columnDefinition = "TEXT")
    private String subject;
```

`entity/ChatAttachment.java` — javadoc класса и связь:

```java
/**
 * Файл сообщения: у WhatsApp — один, у письма — сколько угодно (V29). Байты читаются только при скачивании — лента
 * берёт метаданные проекцией (ChatAttachmentRepository.findMetaByMessageIds). Инвариант: content == null ⇔
 * notStoredReason != null.
 */
...
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;
```

`entity/Chat.java` — колонки:

```java
    /** WhatsApp — номер подключённого аккаунта (wid без «@c.us»); почта — адрес ящика (info@westmed.kz). */
    @Column(nullable = false, length = 320)
    private String account;

    /** WhatsApp — chatId: «7701…@c.us» / «…@g.us» / «…@lid»; почта — адрес собеседника нижним регистром. */
    @Column(name = "external_chat_id", nullable = false, length = 320)
    private String externalChatId;
```

`entity/ChatMessageType.java`:

```java
package com.vladoose.nir.entity;

/**
 * Вид сообщения чата (спека whatsapp-chats §6.5). CALL — служебная строка о входящем звонке (спека whatsapp-waha §8),
 * EMAIL — письмо почтовой переписки (спека info-mail-leads §2.3).
 */
public enum ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER, LOCATION, CONTACT, CALL, OTHER, EMAIL }
```

Тесты zakup@ — ключ курсора (поведение не меняется):

```bash
cd /Users/vlad/IdeaProjects/AIS/src/test/java/com/vladoose/nir
perl -pi -e 's/(cursorRepository|cursorRepo|cursors)\.findById\(("[^"]+"|box)\)/$1.findById(MailCursorKey.inbox($2))/g' \
  mail/MailReceiveServiceIntegrationTest.java service/MailReceiveServiceTest.java service/mail/MailSchemaTest.java \
  service/mail/MailIngestWriterTest.java email/KpRoundTripTest.java
perl -pi -e 's/cursors\.findById\(anyString\(\)\)/cursors.findById(any())/g' service/MailReceiveServiceTest.java
grep -n "findById(\"\|findById(box)" mail/MailReceiveServiceIntegrationTest.java service/MailReceiveServiceTest.java \
  service/mail/MailSchemaTest.java service/mail/MailIngestWriterTest.java email/KpRoundTripTest.java
```

Expected: последний `grep` ничего не печатает. В `MailReceiveServiceTest` добавить импорт `com.vladoose.nir.entity.MailCursorKey` (остальные файлы импортируют `com.vladoose.nir.entity.*`).

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.MailSchemaTest' --tests 'com.vladoose.nir.chat.ChatPersistenceTest' --tests 'com.vladoose.nir.service.MailReceiveServiceTest' --tests 'com.vladoose.nir.service.mail.MailIngestWriterTest' --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest' --tests 'com.vladoose.nir.email.KpRoundTripTest'`
Expected: PASS, Flyway в выводе — `Migrating schema "public" to version "29 - info mail leads"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V29__info_mail_leads.sql src/main/java/com/vladoose/nir/entity \
  src/main/java/com/vladoose/nir/repository/MailCursorRepository.java src/main/java/com/vladoose/nir/service/MailReceiveService.java \
  src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java src/test/java
git commit -m "feat(mail): V29 — курсор «ящик + папка», тема письма, несколько файлов у сообщения

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Разбор письма — адресаты, признаки рассылки, файлы по пределу

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/MailFile.java`
- Modify: `src/main/java/com/vladoose/nir/service/mail/ParsedMail.java`, `MailParser.java`
- Modify: `src/test/java/com/vladoose/nir/service/mail/TestMails.java`, `MailTextTest.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/MailParserTest.java`

**Interfaces:**
- Produces: `record MailFile(String name, String mimeType, Long size, byte[] content)` + `boolean stored()` + package-private `static MailFile of(String name, String mimeType, byte[] bytes, int max)`; у `ParsedMail` два новых последних компонента — `List<String> recipients` (адреса «Кому» и «Копия» нижним регистром, без повторов, без групп) и `List<MailFile> files`; `MailParser.parse(Message, long)` (файлы не собираются) и `MailParser.parse(Message, long, int maxFileBytes)`; в `autoHeaders` — ещё `list-unsubscribe` и `list-id`; `TestMails.Builder.to(String...)`, `.files(MailFile...)`.

- [ ] **Step 1: Write the failing tests** — в `MailParserTest`:

```java
    @Test
    void recipients_toAndCc_lowercasedWithoutRepeats_groupsSkipped() throws Exception {
        MimeMessage m = plain("Иван <ivan@clinic.kz>", "Запрос", "Текст");
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse("Info@WestMed.kz, buyer@clinic.kz"));
        m.setRecipients(Message.RecipientType.CC, InternetAddress.parse("buyer@clinic.kz, undisclosed-recipients:;"));
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.recipients()).containsExactly("info@westmed.kz", "buyer@clinic.kz");
    }

    @Test
    void listHeaders_lowercased() throws Exception {
        MimeMessage m = plain("news@shop.kz", "Акция", "Скидки");
        m.setHeader("List-Unsubscribe", "<mailto:Unsub@Shop.kz>");
        m.setHeader("List-Id", "News <news.shop.kz>");
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1);

        assertThat(p.autoHeaders()).containsEntry("list-unsubscribe", "<mailto:unsub@shop.kz>").containsKey("list-id");
    }

    /** Ящик info@: файлы с байтами до предела; больше предела — имя без байтов и без размера. */
    @Test
    void files_collectedUnderLimit_overLimitWithoutBytes() throws Exception {
        MimeMessage m = roundTrip(withAttachments("Клиент <c@clinic.kz>", "Файлы", "Во вложении",
                "ТЗ.pdf", new byte[]{1, 2, 3}, "видео.mp4", new byte[50]));

        ParsedMail p = MailParser.parse(m, 1, 10);

        assertThat(p.files()).extracting(MailFile::name).containsExactly("ТЗ.pdf", "видео.mp4");
        assertThat(p.files().get(0).content()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(p.files().get(0).size()).isEqualTo(3L);
        assertThat(p.files().get(0).mimeType()).isEqualTo("application/octet-stream");
        assertThat(p.files().get(1).stored()).isFalse();
        assertThat(p.files().get(1).size()).isNull();
    }

    /** Ящик zakup@ (без предела) байтов файлов не собирает; Excel — те же байты, что excelBytes, без второго чтения. */
    @Test
    void files_onlyWithLimit_excelBytesReused() throws Exception {
        MimeMessage m = roundTrip(withAttachments("s@x.kz", "Смета", "Текст", "смета.xlsx", xlsxBytes()));

        assertThat(MailParser.parse(m, 1).files()).isEmpty();
        ParsedMail p = MailParser.parse(m, 1, 1_000_000);
        assertThat(p.files()).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("смета.xlsx");
            assertThat(f.content()).isEqualTo(p.excelBytes());
        });
    }

    /** Картинка подписи (inline + Content-ID) — не файл письма. */
    @Test
    void files_inlineSignatureImageSkipped() throws Exception {
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
        pdf.setFileName("КП.pdf");
        pdf.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(relPart);
        mixed.addBodyPart(pdf);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("s@x.kz"));
        m.setSubject("КП", "UTF-8");
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1, 1000);

        assertThat(p.files()).extracting(MailFile::name, MailFile::mimeType).containsExactly(tuple("КП.pdf", "application/pdf"));
    }

    /** U+0000 вычищается и из имён файлов. */
    @Test
    void nulCharacter_strippedFromFileNames() throws Exception {
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Текст", "UTF-8");
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1}, "application/pdf")));
        file.setHeader("Content-Disposition", "attachment; filename=\"" + encodedWord("ТЗ\0.pdf") + "\"");
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(text);
        mixed.addBodyPart(file);
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress("c@clinic.kz"));
        m.setContent(mixed);
        m.saveChanges();

        ParsedMail p = MailParser.parse(roundTrip(m), 1, 1000);

        assertThat(allStrings(p)).noneMatch(s -> s.indexOf('\0') >= 0);
        assertThat(p.files()).extracting(MailFile::name).containsExactly("ТЗ.pdf");
    }
```

Импорты `MailParserTest`: `static org.assertj.core.api.Assertions.tuple`.

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.MailParserTest'`
Expected: FAIL — компиляция: `cannot find symbol recipients()` / `files()` / `MailFile` / `parse(…, int)`.

- [ ] **Step 3: Implement**

`service/mail/MailFile.java`:

```java
package com.vladoose.nir.service.mail;

/**
 * Файл письма ящика info@: имя, базовый тип нижним регистром, размер и байты. content == null — файл больше предела:
 * он не сохраняется (в переписке — название с пометкой «откройте в почте»), размер тогда неизвестен — null.
 */
public record MailFile(String name, String mimeType, Long size, byte[] content) {

    public boolean stored() {
        return content != null;
    }

    /** bytes — прочитано не больше max + 1 байта: длиннее max — файл больше предела. */
    static MailFile of(String name, String mimeType, byte[] bytes, int max) {
        return bytes.length > max ? new MailFile(name, mimeType, null, null)
                : new MailFile(name, mimeType, (long) bytes.length, bytes);
    }
}
```

`service/mail/ParsedMail.java` — компоненты и javadoc:

```java
 * @param bounce          сведения из частей возврата (DSN); null — таких частей нет
 * @param recipients      адреса «Кому» и «Копия» нижним регистром, без повторов; группы («undisclosed-recipients:;») не входят
 * @param files           вложения с байтами — только при разборе с пределом файла (ящик info@), иначе пусто
 */
public record ParsedMail(long uid, String messageId, String from, String fromAddress, String subject,
                         OffsetDateTime receivedAt, String contentType, Map<String, String> autoHeaders,
                         String text, String html, List<String> attachmentNames,
                         byte[] excelBytes, String excelName, Bounce bounce,
                         List<String> recipients, List<MailFile> files) {
```

и в javadoc `autoHeaders`: «признаки автоответа и рассылки: имя заголовка нижним регистром → значение нижним регистром (auto-submitted, x-autoreply, x-autorespond, precedence, list-unsubscribe, list-id)».

`service/mail/MailParser.java`:

```java
    private static final List<String> AUTO_HEADERS = List.of("Auto-Submitted", "X-Autoreply", "X-Autorespond", "Precedence",
            "List-Unsubscribe", "List-Id");

    public static ParsedMail parse(Message msg, long uid) throws MessagingException, IOException {
        return parse(msg, uid, 0);
    }

    /**
     * maxFileBytes > 0 — собрать и сами файлы вложений (ящик info@: письмо — сообщение переписки с файлами); файл больше
     * предела — без байтов. 0 — только имена (ящик zakup@: байты ему нужны лишь у Excel, их разбор читает и так).
     */
    public static ParsedMail parse(Message msg, long uid, int maxFileBytes) throws MessagingException, IOException {
        String from = from(msg);
        String subject = subject(msg);
        Walk w = new Walk(maxFileBytes);
        walk(msg, w, 0);
        return new ParsedMail(uid, header(msg, "Message-ID"), from, addressPart(from),
                subject == null ? "" : subject, receivedAt(msg), contentType(msg), autoHeaders(msg),
                w.text.toString(), w.html.toString(), List.copyOf(w.attachments), w.excel, w.excelName, w.bounce(),
                recipients(msg), List.copyOf(w.files));
    }

    /** «Кому» и «Копия»: адреса нижним регистром, без повторов; группы пропускаются; кривой заголовок — мягкий разбор. */
    static List<String> recipients(Message msg) {
        Set<String> out = new LinkedHashSet<>();
        for (Message.RecipientType type : List.of(Message.RecipientType.TO, Message.RecipientType.CC)) {
            Address[] list;
            try {
                list = msg.getRecipients(type);
            } catch (MessagingException e) {
                list = lenient(msg, type == Message.RecipientType.TO ? "To" : "Cc");
            }
            if (list == null) continue;
            for (Address a : list) {
                if (a instanceof InternetAddress ia && !ia.isGroup() && ia.getAddress() != null && !ia.getAddress().isBlank()) {
                    out.add(noNul(ia.getAddress()).trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    private static Address[] lenient(Message msg, String header) {
        try {
            String raw = header(msg, header);
            return raw == null ? null : InternetAddress.parseHeader(raw, false);
        } catch (MessagingException e) {
            return null;
        }
    }
```

В `walk` — ветка `message/rfc822` (после `if (name != null) w.attachments.add(name);`):

```java
            if (w.maxFileBytes > 0) w.files.add(file(part, name != null ? name : "письмо.eml", "message/rfc822", w.maxFileBytes));
```

и обычные части — вместо блока от `boolean excel = …` до добавления имени вложения:

```java
        String fileName = decode(part.getFileName());
        boolean excel = isExcel(part, fileName);
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (fileName != null && !embeddedImage(part)) || excel;
        byte[] excelRead = null;
        if (excel && w.excel == null) {
            try (InputStream in = part.getInputStream()) {
                w.excel = in.readAllBytes();
            }
            w.excelName = fileName != null ? fileName : "attachment.xlsx";
            excelRead = w.excel;
        }
        if (attachment) {
            String name = fileName != null ? fileName : "без имени";
            w.attachments.add(name);
            if (w.maxFileBytes > 0) {
                // Excel уже прочитан целиком — второй раз с сервера его не качаем
                w.files.add(excelRead != null ? MailFile.of(name, mimeOf(part), excelRead, w.maxFileBytes)
                        : file(part, name, mimeOf(part), w.maxFileBytes));
            }
        } else if (part.isMimeType("text/plain")) {
            append(w.text, text(part));
        } else if (part.isMimeType("text/html")) {
            append(w.html, text(part));
        }
```

Помощники (рядом с `isExcel`):

```java
    /** Файл с байтами: читается не больше max + 1 байта — больше предела в память не попадает. */
    private static MailFile file(Part part, String name, String mime, int max) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            return MailFile.of(name, mime, in.readNBytes(max + 1), max);
        }
    }

    /** Базовый тип части нижним регистром, без параметров отправителя. */
    private static String mimeOf(Part part) throws MessagingException {
        String ct = noNul(part.getContentType());
        String base = ct == null ? "" : ct.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return base.isEmpty() ? "application/octet-stream" : base;
    }
```

`Walk` — поле и конструктор:

```java
        final int maxFileBytes;
        final List<MailFile> files = new ArrayList<>();
        ...
        Walk(int maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
```

`TestMails.Builder` — поля, методы и сборка:

```java
        private List<String> recipients = List.of();
        private List<MailFile> files = List.of();
        ...
        /** Адресаты «Кому» и «Копия» (уже нижним регистром, как их отдаёт разбор). */
        public Builder to(String... v) { recipients = List.of(v); return this; }
        public Builder files(MailFile... v) { files = List.of(v); return this; }

        public ParsedMail build() {
            return new ParsedMail(uid, messageId, from, MailParser.addressPart(from), subject, receivedAt, contentType,
                    autoHeaders, text, html, attachments, excel, excelName, bounce, recipients, files);
        }
```

`MailTextTest.mail(...)`:

```java
        return new ParsedMail(1, null, "s@x.kz", "s@x.kz", "Тема", OffsetDateTime.now(), "text/plain",
                Map.of(), text, html, List.of(), null, null, null, List.of(), List.of());
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.*' --tests 'com.vladoose.nir.service.MailReceiveServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail src/test/java/com/vladoose/nir/service/mail
git commit -m "feat(mail): разбор — адресаты, признаки рассылки, файлы вложений по пределу

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: IMAP — папка «Отправленные», файлы по пределу

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/MailFolder.java`, `SentFolders.java`
- Modify: `src/main/java/com/vladoose/nir/service/mail/ImapMailboxConnector.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/SentFoldersTest.java`, `ImapMailboxConnectorTest.java`

**Interfaces:**
- Consumes: `MailParser.parse(Message, long, int)` (задача 2), `MailCursor.INBOX/SENT` (задача 1).
- Produces: `enum MailFolder { INBOX, SENT }` + `String cursorKey()` + `String label()` («Входящие» / «Отправленные»); `SentFolders.Candidate(String fullName, String name, List<String> attributes)`, `SentFolders.pick(List<Candidate>) → Optional<String>`; `ImapMailboxConnector.open(MailFolder)` (папки нет — `FolderNotFoundException`), package-private конструктор `ImapMailboxConnector(host, port, username, password, protocol, connectTimeoutMs, readTimeoutMs, int maxFileBytes)`; константы `CONNECT_TIMEOUT_MS`, `READ_TIMEOUT_MS` (package-private, уже есть).

- [ ] **Step 1: Write the failing tests**

`SentFoldersTest.java`:

```java
package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentFoldersTest {

    static SentFolders.Candidate f(String full, String name, String... attrs) {
        return new SentFolders.Candidate(full, name, List.of(attrs));
    }

    /** Mail.ru: «Отправленные» под закодированным именем, но с признаком \Sent (RFC 6154) — признак важнее имени. */
    @Test
    void sentAttributeWinsOverName() {
        assertThat(SentFolders.pick(List.of(f("INBOX", "INBOX"), f("Sent", "Sent"),
                f("Отправленные", "Отправленные", "\\HasNoChildren", "\\Sent")))).contains("Отправленные");
    }

    @Test
    void byNameWhenNoAttribute_caseInsensitive() {
        assertThat(SentFolders.pick(List.of(f("INBOX", "INBOX"), f("ОТПРАВЛЕННЫЕ", "ОТПРАВЛЕННЫЕ")))).contains("ОТПРАВЛЕННЫЕ");
        assertThat(SentFolders.pick(List.of(f("[Gmail]/Sent Mail", "Sent Mail")))).contains("[Gmail]/Sent Mail");
        assertThat(SentFolders.pick(List.of(f("Sent Items", "Sent Items")))).contains("Sent Items");
    }

    @Test
    void noneFound() {
        assertThat(SentFolders.pick(List.of(f("INBOX", "INBOX"), f("Drafts", "Drafts", "\\Drafts")))).isEmpty();
    }
}
```

В `ImapMailboxConnectorTest` (там `user` — ящик zakup@ GreenMail; импорты: `jakarta.mail.Flags`, `jakarta.mail.FolderNotFoundException`, `java.util.Date`, `java.util.List`, `static org.assertj.core.api.Assertions.assertThatThrownBy`):

```java
    @Test
    void openSent_byName_readsItsLetters() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Sent")
                .appendMessage(TestMimes.plain(USER, "Наш ответ клиенту", "Добрый день"), new Flags(), new Date());

        try (MailboxSession s = connector().open(MailFolder.SENT)) {
            List<Long> uids = s.uidsAfter(0, 10);
            assertThat(uids).hasSize(1);
            assertThat(s.fetch(uids.get(0)).subject()).isEqualTo("Наш ответ клиенту");
        }
    }

    @Test
    void openSent_missing_folderNotFound() {
        assertThatThrownBy(() -> connector().open(MailFolder.SENT))
                .isInstanceOf(FolderNotFoundException.class).hasMessageContaining("Отправленные");
    }

    /** Предел файла: файлы читаются через IMAP, больший предела — без байтов. */
    @Test
    void fileLimit_filesReadThroughImap() throws Exception {
        user.deliver(TestMimes.withAttachments("c@clinic.kz", "Файлы", "Во вложении",
                "ТЗ.pdf", new byte[]{1, 2, 3}, "видео.mp4", new byte[64]));
        ImapMailboxConnector withFiles = new ImapMailboxConnector(HOST, PORT, USER, PASS, "imap", 5_000, 5_000, 10);

        try (MailboxSession s = withFiles.open(MailFolder.INBOX)) {
            ParsedMail p = s.fetch(s.maxUid());
            assertThat(p.files()).extracting(MailFile::name).containsExactly("ТЗ.pdf", "видео.mp4");
            assertThat(p.files().get(0).content()).isEqualTo(new byte[]{1, 2, 3});
            assertThat(p.files().get(1).stored()).isFalse();
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.SentFoldersTest' --tests 'com.vladoose.nir.service.mail.ImapMailboxConnectorTest'`
Expected: FAIL — компиляция: `cannot find symbol SentFolders` / `MailFolder` / `open(MailFolder)`.

- [ ] **Step 3: Implement**

`MailFolder.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.MailCursor;

/** Папка ящика, которую читает приём: роль, а не имя на сервере (имя у почты своё и бывает локализованным). */
public enum MailFolder {
    INBOX(MailCursor.INBOX, "Входящие"),
    SENT(MailCursor.SENT, "Отправленные");

    private final String cursorKey;
    private final String label;

    MailFolder(String cursorKey, String label) {
        this.cursorKey = cursorKey;
        this.label = label;
    }

    /** Ключ папки в курсоре (mail_cursor.folder). */
    public String cursorKey() { return cursorKey; }

    /** Подпись для людей и лога. */
    public String label() { return label; }
}
```

`SentFolders.java`:

```java
package com.vladoose.nir.service.mail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Выбор папки «Отправленные» по списку папок ящика — чистая функция (тестируется без IMAP). */
public final class SentFolders {

    /** Папка из LIST: полное имя (по нему открывают), имя (последний сегмент) и признаки (\Sent, \HasNoChildren…). */
    public record Candidate(String fullName, String name, List<String> attributes) {}

    /** Имена «Отправленных» у почтовых служб без признака \Sent. */
    static final List<String> NAMES = List.of("отправленные", "sent", "sent items", "sent messages", "sent mail");

    private SentFolders() {}

    /** Признак \Sent (RFC 6154) — первым: у Mail.ru он есть, а имя закодировано; без признака — по имени. */
    public static Optional<String> pick(List<Candidate> folders) {
        for (Candidate c : folders) {
            if (c.attributes().stream().anyMatch(a -> a.equalsIgnoreCase("\\Sent"))) return Optional.of(c.fullName());
        }
        for (Candidate c : folders) {
            String n = c.name() == null ? "" : c.name().strip().toLowerCase(Locale.ROOT);
            if (NAMES.contains(n)) return Optional.of(c.fullName());
        }
        return Optional.empty();
    }
}
```

`ImapMailboxConnector.java` — поле, конструкторы, открытие папки (импорты: `org.eclipse.angus.mail.imap.IMAPFolder`):

```java
    private final int maxFileBytes;

    @Autowired
    public ImapMailboxConnector(@Value("${mail.imap.host:localhost}") String host, ... ) {
        this(host, port, username, password, protocol, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    ImapMailboxConnector(String host, int port, String username, String password, String protocol,
                         int connectTimeoutMs, int readTimeoutMs) {
        this(host, port, username, password, protocol, connectTimeoutMs, readTimeoutMs, 0);
    }

    /** maxFileBytes > 0 — письма разбираются вместе с файлами вложений (ящик info@), см. MailParser.parse. */
    ImapMailboxConnector(String host, int port, String username, String password, String protocol,
                         int connectTimeoutMs, int readTimeoutMs, int maxFileBytes) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxFileBytes = maxFileBytes;
    }

    @Override
    public MailboxSession open() throws MessagingException {
        return open(MailFolder.INBOX);
    }

    /** Ящик с папкой: «Входящие» или «Отправленные» ({@link #sentFolder}); папки нет — FolderNotFoundException. */
    public MailboxSession open(MailFolder which) throws MessagingException {
        Properties props = new Properties();
        String p = "mail." + protocol + ".";
        props.put(p + "connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put(p + "timeout", String.valueOf(readTimeoutMs));
        props.put(p + "writetimeout", String.valueOf(WRITE_TIMEOUT_MS));
        props.put(p + "peek", "true");                  // тело — BODY.PEEK[]: «прочитано» не ставится
        Store store = Session.getInstance(props).getStore(protocol);
        store.connect(host, port, username, password);
        try {
            Folder folder = which == MailFolder.SENT ? sentFolder(store) : store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);              // EXAMINE: флаги не меняются
            return new ImapSession(store, folder, maxFileBytes);
        } catch (MessagingException | RuntimeException e) {
            closeQuietly(store);
            throw e;
        }
    }

    /** «Отправленные»: по признаку \Sent, иначе по имени ({@link SentFolders#pick}). */
    static Folder sentFolder(Store store) throws MessagingException {
        List<SentFolders.Candidate> candidates = new ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            String[] attrs = f instanceof IMAPFolder imap ? imap.getAttributes() : null;
            candidates.add(new SentFolders.Candidate(f.getFullName(), f.getName(), attrs == null ? List.of() : List.of(attrs)));
        }
        String full = SentFolders.pick(candidates)
                .orElseThrow(() -> new FolderNotFoundException((Folder) null, "папка «Отправленные» не найдена"));
        return store.getFolder(full);
    }
```

`ImapSession` — поле и чтение письма:

```java
        private final int maxFileBytes;

        ImapSession(Store store, Folder folder, int maxFileBytes) {
            this.store = store;
            this.folder = folder;
            this.uids = (UIDFolder) folder;
            this.maxFileBytes = maxFileBytes;
        }
        ...
        @Override
        public ParsedMail fetch(long uid) throws Exception {
            Message m = uids.getMessageByUID(uid);
            return m == null ? null : MailParser.parse(m, uid, maxFileBytes);
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.SentFoldersTest' --tests 'com.vladoose.nir.service.mail.ImapMailboxConnectorTest' --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest'`
Expected: PASS.

- [ ] **Step 5: Мутация** — в `SentFolders.pick` удалить первый цикл (признак \Sent) → `sentAttributeWinsOverName` красный (берётся «Sent»); вернуть копией файла (`cp` до правки, `cp` обратно, `diff -q`; CLAUDE.md §14).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail src/test/java/com/vladoose/nir/service/mail
git commit -m "feat(mail): IMAP — папка «Отправленные» по признаку \\Sent или имени, файлы по пределу

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Общий проход по папке (`MailFolderPass`); zakup@ — без изменений поведения

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/MailFolderPass.java`
- Modify: `src/main/java/com/vladoose/nir/service/MailReceiveService.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/MailFolderPassTest.java` (+ прежние `MailReceiveServiceTest`, `MailReceiveServiceIntegrationTest` — сетка регрессии)

**Interfaces:**
- Consumes: `MailboxSession`, `BrokenMail`, `ParsedMail`, `MailCursor`.
- Produces: `MailFolderPass.Target<R>` (`Optional<MailCursor> cursor()`, `R write(ParsedMail, long)`, `void writeBroken(BrokenMail, long)`, `void moveCursorTo(long, long)`, `void written(R)`, `void broken()`); `static <R> String MailFolderPass.run(MailboxSession, Target<R>, Instant windowStart, int maxPerPass, Clock, Logger, String where) throws MessagingException` (null — до конца); `MailFolderPass.CONNECTION_LOST`; `static String MailFolderPass.dbDown(Exception)`.

- [ ] **Step 1: Write the failing test** — `MailFolderPassTest.java`:

```java
package com.vladoose.nir.service.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.entity.MailCursor;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailFolderPassTest {

    MailboxSession session = mock(MailboxSession.class);
    @SuppressWarnings("unchecked")
    MailFolderPass.Target<String> target = mock(MailFolderPass.Target.class);
    Logger logger = (Logger) LoggerFactory.getLogger("mail-folder-pass-test");
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
    }

    /** У info@ две папки со своими UID: строки лога несут приставку where — ящик и папку. */
    @Test
    void connectionLost_stopsWithWherePrefixInLog() throws Exception {
        when(session.uidValidity()).thenReturn(7L);
        when(target.cursor()).thenReturn(Optional.of(MailCursor.builder().mailbox("info@westmed.kz")
                .folder(MailCursor.SENT).uidValidity(7).lastUid(10).build()));
        when(session.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(session.fetch(11L)).thenThrow(new MessagingException("BYE"));
        when(session.isAlive()).thenReturn(false);

        String stopped = MailFolderPass.run(session, target, Instant.now(), 100, Clock.systemUTC(), logger,
                "info@westmed.kz «Отправленные»: ");

        assertThat(stopped).isEqualTo(MailFolderPass.CONNECTION_LOST);
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getFormattedMessage())
                .startsWith("info@westmed.kz «Отправленные»: Связь с ящиком оборвалась на письме UID 11"));
        verify(target, never()).write(any(), anyLong());
    }

    /** Первый запуск: окно, письмо, пришедшее во время прохода (UID > max), — следующим проходом; курсор — на max. */
    @Test
    void firstRun_windowThenCursorToMaxTakenBeforeWindow() throws Exception {
        Instant window = Instant.parse("2026-10-06T09:00:00Z");
        when(session.uidValidity()).thenReturn(7L);
        when(target.cursor()).thenReturn(Optional.empty());
        when(session.maxUid()).thenReturn(50L);
        when(session.uidsReceivedSince(window, 100)).thenReturn(List.of(49L, 51L));
        when(session.fetch(49L)).thenReturn(mail().uid(49).build());
        when(target.write(any(), eq(7L))).thenReturn("записано");

        assertThat(MailFolderPass.run(session, target, window, 100, Clock.systemUTC(), logger, "")).isNull();

        verify(session, never()).fetch(51L);
        verify(target).written("записано");
        verify(target).moveCursorTo(7L, 50L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.MailFolderPassTest'`
Expected: FAIL — компиляция: `cannot find symbol MailFolderPass`.

- [ ] **Step 3: Implement** — `MailFolderPass.java` (код переезжает из `MailReceiveService` без изменения логики; в строках лога — приставка `where`):

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.util.InfrastructureFailure;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Проход по одной папке ящика — общий у zakup@ и info@ (спека 2026-10-05-zakup-mail-telegram §3): курсор по UID;
 * первый запуск (курсора нет или сменился UIDVALIDITY) — только окно, затем курсор на последнее письмо, снятое ДО
 * окна (письмо, пришедшее во время прохода, уйдёт следующим, а не перепрыгнется); сбои — §3.4: битое письмо —
 * короткая строка и дальше, обрыв связи или недоступная база — стоп, курсор на последнем записанном.
 * Транзакций проход не держит: запись и курсор — транзакционные бины за {@link Target}.
 */
public final class MailFolderPass {

    /** Причина остановки — свой текст для ответа «Проверить почту» (спека §3.4). */
    public static final String CONNECTION_LOST = "связь с почтой оборвалась";

    /** Что проход делает с письмами папки. */
    public interface Target<R> {
        /** Курсор папки; пусто — первый запуск. Сбой базы — исключение (проход стоп). */
        Optional<MailCursor> cursor();

        /** Запись письма — одна транзакция; не записалось — исключение. */
        R write(ParsedMail m, long uidValidity);

        /** Короткая строка вместо письма, которое не разобралось или не записалось. */
        void writeBroken(BrokenMail b, long uidValidity);

        /** Первый запуск: курсор — на последнее письмо папки, снятое до окна (0 — папка пуста). */
        void moveCursorTo(long uidValidity, long uid);

        /** Письмо записано — для итога прохода. */
        void written(R result);

        /** Записана короткая строка — для итога прохода. */
        void broken();
    }

    private MailFolderPass() {}

    /**
     * null — проход дошёл до конца; иначе — почему остановлен. where — приставка строк лога: у zakup@ пусто, у info@ —
     * ящик и папка (UID у каждой папки свой).
     */
    public static <R> String run(MailboxSession s, Target<R> t, Instant windowStart, int maxPerPass, Clock clock,
                                 Logger log, String where) throws MessagingException {
        long uidValidity = s.uidValidity();
        Optional<MailCursor> cursor = t.cursor().filter(c -> c.getUidValidity() == uidValidity);
        if (cursor.isPresent()) {
            for (long uid : s.uidsAfter(cursor.get().getLastUid(), maxPerPass)) {
                String stopped = processOne(s, t, uidValidity, uid, clock, log, where);
                if (stopped != null) return stopped;
            }
            return null;
        }
        long max = s.maxUid();
        for (long uid : s.uidsReceivedSince(windowStart, maxPerPass)) {
            if (uid > max) continue;
            String stopped = processOne(s, t, uidValidity, uid, clock, log, where);
            if (stopped != null) return stopped;
        }
        t.moveCursorTo(uidValidity, max);
        return null;
    }

    private static <R> String processOne(MailboxSession s, Target<R> t, long uidValidity, long uid, Clock clock,
                                         Logger log, String where) {
        ParsedMail m;
        try {
            m = s.fetch(uid);
        } catch (Exception e) {
            if (!s.isAlive()) {
                log.warn("{}Связь с ящиком оборвалась на письме UID {}: {}", where, uid, e.getClass().getSimpleName());
                return CONNECTION_LOST;
            }
            return writeBroken(t, s.envelope(uid, e), e, "не разобрано", uidValidity, clock, log, where);
        }
        if (m == null) return null;                                 // письмо удалили между поиском и чтением
        R written;
        try {
            written = t.write(m, uidValidity);
        } catch (RuntimeException e) {
            if (InfrastructureFailure.test(e)) {
                log.warn("{}База недоступна на письме UID {} — проход остановлен, повтор следующим: {}", where, uid,
                        e.getClass().getSimpleName());
                return dbDown(e);
            }
            return writeBroken(t, new BrokenMail(uid, m.messageId(), m.from(), m.subject(), m.receivedAt(),
                    e.getClass().getSimpleName()), e, "не записано", uidValidity, clock, log, where);
        }
        t.written(written);                                         // вне try: записанное письмо коротко не пишем
        return null;
    }

    /**
     * Короткая строка вместо письма, которое не разобралось или не записалось (cause). Не записалась и она (не база) —
     * ещё раз, минимальная: без отправителя, темы и Message-ID — в полях письма и бывает причина (символ, который база
     * не хранит). Записалась — письмо пройдено, сбой разовый: в лог со стеком. Не записалась — проход стоп, курсор на
     * месте: то же письмо упадёт и на следующем проходе, раз в минуту, поэтому в лог без стека — классы и UID.
     */
    private static <R> String writeBroken(Target<R> t, BrokenMail b, Exception cause, String what, long uidValidity,
                                          Clock clock, Logger log, String where) {
        RuntimeException withFields = tryWriteBroken(t, b, uidValidity);
        RuntimeException failure = withFields;
        if (withFields != null && !InfrastructureFailure.test(withFields)) {
            failure = tryWriteBroken(t, new BrokenMail(b.uid(), null, null, null,
                    b.receivedAt() != null ? b.receivedAt() : OffsetDateTime.now(clock), b.errorClass()), uidValidity);
        }
        if (failure == null) {
            if (withFields == null) {
                log.warn("{}Письмо UID {} {} — записано коротко", where, b.uid(), what, cause);
            } else {
                log.warn("{}Письмо UID {} {} — записано коротко, без полей письма (с ними не записалось: {})",
                        where, b.uid(), what, withFields.getClass().getSimpleName(), cause);
            }
            t.broken();
            return null;
        }
        log.warn("{}Письмо UID {} {} ({}), короткая строка не записалась ({}) — проход остановлен, повтор следующим",
                where, b.uid(), what, cause.getClass().getSimpleName(), failure.getClass().getSimpleName());
        return InfrastructureFailure.test(failure) ? dbDown(failure)
                : "письмо UID " + b.uid() + " не записалось (" + failure.getClass().getSimpleName() + ")";
    }

    /** null — записано; иначе — чем не записалось. */
    private static <R> RuntimeException tryWriteBroken(Target<R> t, BrokenMail b, long uidValidity) {
        try {
            t.writeBroken(b, uidValidity);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** «база данных недоступна (класс)» — свой текст: в исключениях базы бывают SQL и значения. */
    public static String dbDown(Exception e) {
        return "база данных недоступна (" + e.getClass().getSimpleName() + ")";
    }
}
```

`MailReceiveService` — удалить `CONNECTION_LOST`, `processOne`, `writeBroken`, `tryWriteBroken`, `dbDown`; в `poll()` — `stopped = MailFolderPass.dbDown(e);`; `pass` целиком:

```java
    /** null — проход дошёл до конца; иначе — почему остановлен. */
    private String pass(MailboxSession s, PollResultResponse result) throws MessagingException {
        return MailFolderPass.run(s, new MailFolderPass.Target<MailIngestWriter.WriteResult>() {
            @Override public Optional<MailCursor> cursor() {
                return cursorRepository.findById(MailCursorKey.inbox(writer.mailbox()));
            }
            @Override public MailIngestWriter.WriteResult write(ParsedMail m, long uidValidity) {
                return writer.write(m, uidValidity);
            }
            @Override public void writeBroken(BrokenMail b, long uidValidity) { writer.writeBroken(b, uidValidity); }
            @Override public void moveCursorTo(long uidValidity, long uid) { writer.moveCursorTo(uidValidity, uid); }
            @Override public void written(MailIngestWriter.WriteResult w) { count(result, w); }
            @Override public void broken() {
                result.setBroken(result.getBroken() + 1);
                result.setFetched(result.getFetched() + 1);
            }
        }, clock.instant().minus(Duration.ofMinutes(sinceMinutes)), MAX_PER_PASS, clock, log, "");
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.MailFolderPassTest' --tests 'com.vladoose.nir.service.MailReceiveServiceTest' --tests 'com.vladoose.nir.mail.MailReceiveServiceIntegrationTest' --tests 'com.vladoose.nir.email.KpRoundTripTest'`
Expected: PASS — тексты и логи zakup@ те же (приставка пустая).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail/MailFolderPass.java src/main/java/com/vladoose/nir/service/MailReceiveService.java \
  src/test/java/com/vladoose/nir/service/mail/MailFolderPassTest.java
git commit -m "refactor(mail): проход по папке и политика сбоев — общий MailFolderPass (zakup@ без изменений)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Правила ящика info@ и его настройки

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientMailRoute.java`, `ClientMailRules.java`, `ClientMailRouter.java`, `ClientMailSettings.java`
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailClassifier.java`, `src/main/resources/application.yaml`, `.env.example`
- Test: `src/test/java/com/vladoose/nir/service/mail/ClientMailRouterTest.java`, `ClientMailSettingsTest.java`

**Interfaces:**
- Consumes: `MailClassifier.isBounce/isDelayed/isAutoReply` (package-private static), `ParsedMail.autoHeaders()` с `list-unsubscribe`/`list-id` (задача 2).
- Produces: `enum ClientMailRoute { SITE_NOTIFICATION, BOUNCE, DELAYED, AUTO_REPLY, NEWSLETTER, SERVICE, OWN_DOMAIN, CONVERSATION }`; `record ClientMailRules(String siteNotificationFrom, String ownDomain, List<String> serviceDomains)`; `ClientMailRouter.route(ParsedMail, ClientMailRules)`, `public static boolean isService(String address, ClientMailRules)`, `public static boolean isOwnDomain(String address, ClientMailRules)`; `MailClassifier.isSiteNotification(ParsedMail, String)`; `ClientMailSettings` — конструктор `(boolean enabled, String host, int port, String username, String password, String protocol, String market, long sinceDays, int maxFileMb, String serviceDomains, String siteNotificationFrom, String zakupMailbox)` и методы `enabled()`, `host()`, `port()`, `username()`, `password()`, `protocol()`, `market()`, `sinceDays()`, `maxFileBytes()`, `rules()`.

- [ ] **Step 1: Write the failing tests**

`ClientMailRouterTest.java`:

```java
package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.vladoose.nir.service.mail.ClientMailRoute.*;
import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class ClientMailRouterTest {

    static final ClientMailRules RULES = new ClientMailRules("info@westmed.kz", "westmed.kz",
            List.of("*.mail.ru", "2gis.ru", "2gis.com", "ecc.kz", "goszakup.gov.kz"));

    static ClientMailRoute route(TestMails.Builder b) {
        return ClientMailRouter.route(b.build(), RULES);
    }

    @Test
    void clientLetter_isConversation() {
        assertThat(route(mail().from("Айгуль <aigul@clinic-uralsk.kz>").subject("Запрос цены"))).isEqualTo(CONVERSATION);
    }

    /** Уведомление сайта приходит С info@ — правило 1 раньше «своего домена». */
    @Test
    void siteNotification_beforeOwnDomain() {
        assertThat(route(mail().from("Сайт <info@westmed.kz>").subject("Новая заявка — westmed.kz"))).isEqualTo(SITE_NOTIFICATION);
        assertThat(route(mail().from("info@westmed.kz").subject("Пересылка"))).isEqualTo(OWN_DOMAIN);
    }

    @Test
    void reports_bounceDelayedAutoReplyReadReceipt() {
        assertThat(route(mail().from("MAILER-DAEMON@corp.mail.ru").contentType("multipart/report; report-type=delivery-status")
                .bounce("a@b.kz", "5.1.1", "550", "Запрос", "failed"))).isEqualTo(BOUNCE);
        assertThat(route(mail().from("mailer-daemon@corp.mail.ru").contentType("multipart/report; report-type=delivery-status")
                .bounce("a@b.kz", "4.4.1", "451", "Запрос", "delayed"))).isEqualTo(DELAYED);
        assertThat(route(mail().from("Анна <anna@clinic.kz>").header("Auto-Submitted", "auto-replied"))).isEqualTo(AUTO_REPLY);
        assertThat(route(mail().from("anna@clinic.kz").contentType("multipart/report; report-type=disposition-notification")))
                .isEqualTo(AUTO_REPLY);
        assertThat(route(mail().from("postmaster@clinic.kz"))).isEqualTo(BOUNCE);      // почтовый робот — правило возврата
    }

    @Test
    void newsletter_byListHeadersOrPrecedence() {
        assertThat(route(mail().from("news@shop.kz").header("List-Unsubscribe", "<mailto:u@shop.kz>"))).isEqualTo(NEWSLETTER);
        assertThat(route(mail().from("news@shop.kz").header("List-Id", "news.shop.kz"))).isEqualTo(NEWSLETTER);
        for (String p : List.of("bulk", "list", "junk")) {
            assertThat(route(mail().from("news@shop.kz").header("Precedence", p))).isEqualTo(NEWSLETTER);
        }
        // рассылка со служебного адреса — всё равно рассылка: правило 3 раньше 4
        assertThat(route(mail().from("noreply@shop.kz").header("List-Id", "news.shop.kz"))).isEqualTo(NEWSLETTER);
    }

    @Test
    void service_byLocalPart() {
        for (String local : List.of("noreply", "no-reply", "not-reply", "donotreply", "do-not-reply", "NoReply")) {
            assertThat(route(mail().from(local + "@shop.kz"))).as(local).isEqualTo(SERVICE);
        }
    }

    @Test
    void personalMailRuIsConversation_subdomainIsService() {
        assertThat(route(mail().from("Иван <ivanov@mail.ru>"))).isEqualTo(CONVERSATION);
        assertThat(route(mail().from("client@bk.ru"))).isEqualTo(CONVERSATION);
        assertThat(route(mail().from("security@id.mail.ru"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("info@corp.mail.ru"))).isEqualTo(SERVICE);
    }

    @Test
    void service_byDomainWithSubdomains() {
        assertThat(route(mail().from("help@2gis.ru"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("robot@info.2gis.com"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("fms@ecc.kz"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("portal@v3bl.goszakup.gov.kz"))).isEqualTo(SERVICE);
        assertThat(route(mail().from("buyer@notgoszakup.gov.kz"))).isEqualTo(CONVERSATION);   // похожее имя — не поддомен
    }

    @Test
    void ownDomainAndSubdomain() {
        assertThat(route(mail().from("Бухгалтерия <buh@westmed.kz>"))).isEqualTo(OWN_DOMAIN);
        assertThat(route(mail().from("robot@mail.westmed.kz"))).isEqualTo(OWN_DOMAIN);
    }

    @Test
    void noSenderAddress_isService() {
        assertThat(route(mail().from(""))).isEqualTo(SERVICE);
    }

    /** То же для адресатов наших писем из «Отправленных». */
    @Test
    void recipientsOfOurLetters() {
        assertThat(ClientMailRouter.isService("support@corp.mail.ru", RULES)).isTrue();
        assertThat(ClientMailRouter.isService("noreply@shop.kz", RULES)).isTrue();
        assertThat(ClientMailRouter.isService("client@mail.ru", RULES)).isFalse();
        assertThat(ClientMailRouter.isOwnDomain("manager@westmed.kz", RULES)).isTrue();
        assertThat(ClientMailRouter.isOwnDomain("client@westmed.kz.evil.com", RULES)).isFalse();
    }
}
```

`ClientMailSettingsTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientMailSettingsTest {

    static ClientMailSettings settings(boolean enabled, String user, String market, String zakup, String domains, int fileMb) {
        return new ClientMailSettings(enabled, "imap.mail.ru", 993, user, "pw", "imaps", market, 7, fileMb, domains,
                "info@westmed.kz", zakup);
    }

    @Test
    void enabled_derivesOwnDomainRulesAndLimit() {
        ClientMailSettings s = settings(true, " Info@WestMed.kz ", "kz", "zakup@westmed.kz", " *.mail.ru , 2gis.ru,, ", 25);

        assertThat(s.enabled()).isTrue();
        assertThat(s.username()).isEqualTo("info@westmed.kz");
        assertThat(s.market()).isEqualTo(Market.KZ);
        assertThat(s.rules().ownDomain()).isEqualTo("westmed.kz");
        assertThat(s.rules().siteNotificationFrom()).isEqualTo("info@westmed.kz");
        assertThat(s.rules().serviceDomains()).containsExactly("*.mail.ru", "2gis.ru");
        assertThat(s.maxFileBytes()).isEqualTo(25 * 1024 * 1024);
    }

    @Test
    void sameMailboxAsZakup_disabled() {
        assertThat(settings(true, "zakup@westmed.kz", "KZ", "Zakup@westmed.kz", "", 25).enabled()).isFalse();
    }

    @Test
    void unknownMarket_disabled() {
        assertThat(settings(true, "info@westmed.kz", "KZT", "zakup@westmed.kz", "", 25).enabled()).isFalse();
    }

    @Test
    void blankUser_disabled() {
        assertThat(settings(true, " ", "KZ", "zakup@westmed.kz", "", 25).enabled()).isFalse();
    }

    @Test
    void fileLimitBetweenOneAndHundredMb() {
        assertThat(settings(false, "info@westmed.kz", "KZ", "z@w.kz", "", 500).maxFileBytes()).isEqualTo(100 * 1024 * 1024);
        assertThat(settings(false, "info@westmed.kz", "KZ", "z@w.kz", "", 0).maxFileBytes()).isEqualTo(1024 * 1024);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientMailRouterTest' --tests 'com.vladoose.nir.service.mail.ClientMailSettingsTest'`
Expected: FAIL — компиляция: `cannot find symbol ClientMailRouter` / `ClientMailSettings`.

- [ ] **Step 3: Implement**

`MailClassifier.java` — первое правило выносится в метод (classify зовёт его):

```java
    public static Classification classify(ParsedMail m, ClassifierRules r) {
        if (isSiteNotification(m, r.siteNotificationFrom())) return new Classification(MailClass.SITE_NOTIFICATION, null);
        String from = m.fromAddress();
        if (!r.ownAddress().isBlank() && from.equalsIgnoreCase(r.ownAddress())) {
        ...

    /** Уведомление сайта о заявке: от адреса уведомлений, тема кончается «— westmed.kz»; адреса нет — правило выключено. */
    static boolean isSiteNotification(ParsedMail m, String siteNotificationFrom) {
        return siteNotificationFrom != null && !siteNotificationFrom.isBlank()
                && m.fromAddress().equalsIgnoreCase(siteNotificationFrom) && m.subject().strip().endsWith("— westmed.kz");
    }
```

`ClientMailRoute.java`:

```java
package com.vladoose.nir.service.mail;

/** Куда идёт письмо «Входящих» ящика info@ (спека 2026-10-13-info-mail-leads §2.2). */
public enum ClientMailRoute {
    /** Уведомление сайта о заявке — пропуск: заявка уже пришла обращением через API сайта. */
    SITE_NOTIFICATION,
    BOUNCE, DELAYED, AUTO_REPLY,
    NEWSLETTER, SERVICE, OWN_DOMAIN,
    /** Письмо собеседника — в переписку и обращения. */
    CONVERSATION
}
```

`ClientMailRules.java`:

```java
package com.vladoose.nir.service.mail;

import java.util.List;

/**
 * Правила ящика info@: адрес уведомлений сайта, свой домен (домен ящика), служебные домены — шаблоны «2gis.ru»
 * (домен и поддомены) и «*.mail.ru» (только поддомены). Всё нижним регистром.
 */
public record ClientMailRules(String siteNotificationFrom, String ownDomain, List<String> serviceDomains) {}
```

`ClientMailRouter.java`:

```java
package com.vladoose.nir.service.mail;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Куда идёт письмо «Входящих» ящика info@ — чистая функция, порядок правил значим (спека 2026-10-13-info-mail-leads
 * §2.2): уведомление сайта → возврат / задержка / автоответ / квитанция → рассылка → служебное → свой домен →
 * переписка. Те же «служебное» и «свой домен» отсеивают адресатов наших писем из «Отправленных».
 */
public final class ClientMailRouter {

    /** Служебные имена адреса. mailer-daemon и postmaster в письме раньше ловит правило возврата. */
    static final Set<String> SERVICE_LOCAL_PARTS =
            Set.of("noreply", "no-reply", "not-reply", "donotreply", "do-not-reply", "mailer-daemon", "postmaster");
    private static final Set<String> BULK = Set.of("bulk", "list", "junk");

    private ClientMailRouter() {}

    public static ClientMailRoute route(ParsedMail m, ClientMailRules r) {
        if (MailClassifier.isSiteNotification(m, r.siteNotificationFrom())) return ClientMailRoute.SITE_NOTIFICATION;
        if (MailClassifier.isBounce(m)) return MailClassifier.isDelayed(m) ? ClientMailRoute.DELAYED : ClientMailRoute.BOUNCE;
        if (MailClassifier.isAutoReply(m)) return ClientMailRoute.AUTO_REPLY;
        if (isNewsletter(m)) return ClientMailRoute.NEWSLETTER;
        if (isService(m.fromAddress(), r)) return ClientMailRoute.SERVICE;
        if (isOwnDomain(m.fromAddress(), r)) return ClientMailRoute.OWN_DOMAIN;
        return ClientMailRoute.CONVERSATION;
    }

    /** Рассылка: List-Unsubscribe / List-Id (RFC 2369 / 2919) или Precedence: bulk | list | junk. */
    static boolean isNewsletter(ParsedMail m) {
        Map<String, String> h = m.autoHeaders();
        return h.containsKey("list-unsubscribe") || h.containsKey("list-id")
                || BULK.contains(h.getOrDefault("precedence", ""));
    }

    /** Служебный адрес: служебное имя или домен из списка. Адрес без «@» — тоже: переписку вести не с кем. */
    public static boolean isService(String address, ClientMailRules r) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        int at = a.lastIndexOf('@');
        if (at <= 0 || at == a.length() - 1) return true;
        if (SERVICE_LOCAL_PARTS.contains(a.substring(0, at))) return true;
        String domain = a.substring(at + 1);
        return r.serviceDomains().stream().anyMatch(p -> domainMatches(domain, p));
    }

    /** Свой домен (домен ящика) и его поддомены. */
    public static boolean isOwnDomain(String address, ClientMailRules r) {
        if (address == null || r.ownDomain().isBlank()) return false;
        String a = address.trim().toLowerCase(Locale.ROOT);
        int at = a.lastIndexOf('@');
        return at >= 0 && domainMatches(a.substring(at + 1), r.ownDomain());
    }

    /** «2gis.ru» — сам домен и поддомены; «*.mail.ru» — только поддомены. */
    static boolean domainMatches(String domain, String pattern) {
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("*.")) return domain.endsWith(p.substring(1));
        return domain.equals(p) || domain.endsWith("." + p);
    }
}
```

`ClientMailSettings.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Настройки ящика писем клиентов info@ (`mail.clients.*`, спека 2026-10-13-info-mail-leads §2.1). Ошибка настройки —
 * тот же ящик, что у приёма zakup@, неизвестный рынок, пустой адрес — выключает приём с ERROR при старте: иначе
 * опечатка молча превратила бы рынок в RF или повела бы два приёма по одному курсору.
 */
@Component
public class ClientMailSettings {

    private static final Logger log = LoggerFactory.getLogger(ClientMailSettings.class);
    /** Предел файла — не больше 100 МБ: файл письма читается в память целиком (куча прода — 1 ГБ). */
    static final int MAX_FILE_MB_LIMIT = 100;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final Market market;
    private final long sinceDays;
    private final int maxFileBytes;
    private final ClientMailRules rules;

    public ClientMailSettings(@Value("${mail.clients.enabled:false}") boolean enabled,
                              @Value("${mail.clients.host:localhost}") String host,
                              @Value("${mail.clients.port:3143}") int port,
                              @Value("${mail.clients.username:info@westmed.kz}") String username,
                              @Value("${mail.clients.password:}") String password,
                              @Value("${mail.clients.protocol:imap}") String protocol,
                              @Value("${mail.clients.market:KZ}") String market,
                              @Value("${mail.clients.since-days:7}") long sinceDays,
                              @Value("${mail.clients.max-file-mb:25}") int maxFileMb,
                              @Value("${mail.clients.service-domains:*.mail.ru,2gis.ru,2gis.com,ecc.kz,goszakup.gov.kz}") String serviceDomains,
                              @Value("${leads.westmed.notification-from:info@westmed.kz}") String siteNotificationFrom,
                              @Value("${mail.imap.username:}") String zakupMailbox) {
        this.host = host;
        this.port = port;
        this.username = lower(username);
        this.password = password;
        this.protocol = protocol;
        this.market = parseMarket(market);
        this.sinceDays = Math.max(0, sinceDays);
        this.maxFileBytes = Math.max(1, Math.min(maxFileMb, MAX_FILE_MB_LIMIT)) * 1024 * 1024;
        this.rules = new ClientMailRules(lower(siteNotificationFrom), domainOf(this.username), domains(serviceDomains));
        String problem = !enabled ? null
                : !this.username.contains("@") ? "MAIL_CLIENTS_USERNAME не задан"
                : this.market == null ? "MAIL_CLIENTS_MARKET: неизвестный рынок «" + market + "» (нужно KZ или RF)"
                : this.username.equals(lower(zakupMailbox)) ? "MAIL_CLIENTS_USERNAME совпадает с ящиком приёма zakup@ (MAIL_IMAP_USERNAME)"
                : null;
        if (problem != null) log.error("Приём писем клиентов выключен: {}", problem);
        this.enabled = enabled && problem == null;
        if (this.enabled) {
            log.info("Приём писем клиентов: ящик {}, рынок {}, первый запуск папки — за {} дн.", this.username, this.market, this.sinceDays);
        }
    }

    public boolean enabled() { return enabled; }
    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public String password() { return password; }
    public String protocol() { return protocol; }
    public Market market() { return market; }
    public long sinceDays() { return sinceDays; }
    public int maxFileBytes() { return maxFileBytes; }
    public ClientMailRules rules() { return rules; }

    /** null — рынок не распознан (опечатка не превращается молча в RF, как у Market.fromHeader). */
    static Market parseMarket(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Market.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> domains(String raw) {
        return raw == null ? List.of() : Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? "" : address.substring(at + 1);
    }

    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
}
```

`application.yaml` — под `mail:` после блока `imap:`:

```yaml
  # Письма клиентов на info@ → «Обращения» (спека docs/superpowers/specs/2026-10-13-info-mail-leads-design.md):
  # второй ящик, только чтение; «Входящие» и «Отправленные», у каждой папки свой курсор. Telegram об info@ не шлёт.
  clients:
    enabled: ${MAIL_CLIENTS_ENABLED:false}
    host: ${MAIL_CLIENTS_HOST:localhost}
    port: ${MAIL_CLIENTS_PORT:3143}
    username: ${MAIL_CLIENTS_USERNAME:info@westmed.kz}
    password: ${MAIL_CLIENTS_PASSWORD:}
    protocol: ${MAIL_CLIENTS_PROTOCOL:imap}
    market: ${MAIL_CLIENTS_MARKET:KZ}
    poll-ms: ${MAIL_CLIENTS_POLL_MS:60000}
    initial-delay-ms: ${MAIL_CLIENTS_INITIAL_DELAY_MS:30000}
    # окно ПЕРВОГО запуска папки (курсора нет / сменился UIDVALIDITY), дней; дальше — курсор по UID
    since-days: ${MAIL_CLIENTS_SINCE_DAYS:7}
    # файл письма больше предела не сохраняется — в переписке название с пометкой «откройте в почте» (не больше 100)
    max-file-mb: ${MAIL_CLIENTS_MAX_FILE_MB:25}
    # служебные домены: «2gis.ru» — сам домен и поддомены, «*.mail.ru» — только поддомены (сам mail.ru — личные ящики)
    service-domains: ${MAIL_CLIENTS_SERVICE_DOMAINS:*.mail.ru,2gis.ru,2gis.com,ecc.kz,goszakup.gov.kz}
```

`.env.example` — после блока приёма zakup@:

```bash
# --- Почта: письма клиентов на info@ → «Обращения» (второй ящик, только чтение; DEPLOY.md §10) ---
# Telegram об info@ АИС не шлёт — это делает medsite. Пустыми ключи не оставлять: пустое число или флаг валит старт.
MAIL_CLIENTS_ENABLED=false
MAIL_CLIENTS_HOST=imap.mail.ru
MAIL_CLIENTS_PORT=993
MAIL_CLIENTS_PROTOCOL=imaps
MAIL_CLIENTS_USERNAME=info@westmed.kz
MAIL_CLIENTS_PASSWORD=CHANGE_ME_mailru_app_password   # пароль приложения info@ (доступ IMAP)
MAIL_CLIENTS_MARKET=KZ
MAIL_CLIENTS_SINCE_DAYS=7          # окно ПЕРВОГО запуска папки; дальше — курсор по UID
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientMailRouterTest' --tests 'com.vladoose.nir.service.mail.ClientMailSettingsTest' --tests 'com.vladoose.nir.service.mail.MailClassifierTest'`
Expected: PASS.

- [ ] **Step 5: Мутации** (по одной, откат копией файла): убрать проверку `list-unsubscribe` → `newsletter_byListHeadersOrPrecedence` красный; в `domainMatches` заменить `endsWith("." + p)` на `endsWith(p)` → `service_byDomainWithSubdomains` красный (notgoszakup); убрать ветку `*.` → `personalMailRuIsConversation_subdomainIsService` красный; переставить правила 4 и 3 → `newsletter…` (noreply с List-Id) красный.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail src/main/resources/application.yaml .env.example \
  src/test/java/com/vladoose/nir/service/mail
git commit -m "feat(mail): правила и настройки ящика писем клиентов info@

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Почтовая переписка и обращения (`EmailChatWriter`)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/EmailLetter.java`, `src/main/java/com/vladoose/nir/service/EmailChatWriter.java`
- Modify: `src/main/java/com/vladoose/nir/integration/lead/LeadSources.java`, `repository/LeadRepository.java`, `service/ChatLeadRules.java`
- Test: `src/test/java/com/vladoose/nir/service/mail/EmailLetterTest.java`, `src/test/java/com/vladoose/nir/chat/EmailChatWriterTest.java`

**Interfaces:**
- Consumes: `ParsedMail`, `MailFile`, `MailFolder`, `MailText.replyText/normalize`; `ChatLeadRules.findOpenLead(Chat, OffsetDateTime)`; `LeadIntakeService.ingest(IncomingLead)`; `LeadService.takeAutomatically(Lead, String)`.
- Produces: `record EmailLetter(String key, String subject, String body, OffsetDateTime sentAt, String fromAddress, String fromName, List<MailFile> files)` + `static EmailLetter of(ParsedMail, MailFolder, long uidValidity)` + package-private `static String key(String messageId, MailFolder, long uidValidity, long uid)`, `static String nameOf(String from)`; `EmailChatWriter.writeIncoming(String mailbox, EmailLetter)`, `writeOutgoing(String mailbox, String to, EmailLetter)` → `EmailChatWriter.Outcome(boolean duplicate, Long chatId, Long messageId, Long createdLeadId)`; константы `AUTO_TAKE_NOTE`, `NO_SUBJECT`, `LEAD_TEXT_MAX`; `LeadSources.EMAIL = "email"`; `LeadRepository.findByContactEmailIgnoreCaseAndStatusIn(String, Collection<LeadStatus>)`.

- [ ] **Step 1: Write the failing tests**

`EmailLetterTest.java` (пакет `service.mail`):

```java
package com.vladoose.nir.service.mail;

import org.junit.jupiter.api.Test;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class EmailLetterTest {

    @Test
    void fromParsedMail_quoteCut_nameAndKey() {
        ParsedMail m = mail().messageId("<abc@clinic.kz>").from("\"Айгуль Сапарова\" <aigul@clinic.kz>")
                .subject(" Запрос цены ")
                .text("Добрый день! Нужны кушетки 3 шт.\n\nOn Mon, 12 Oct 2026, info@westmed.kz wrote:\n> Здравствуйте").build();

        EmailLetter l = EmailLetter.of(m, MailFolder.INBOX, 7);

        assertThat(l.key()).isEqualTo("<abc@clinic.kz>");
        assertThat(l.subject()).isEqualTo("Запрос цены");
        assertThat(l.body()).isEqualTo("Добрый день! Нужны кушетки 3 шт.");
        assertThat(l.fromAddress()).isEqualTo("aigul@clinic.kz");
        assertThat(l.fromName()).isEqualTo("Айгуль Сапарова");
        assertThat(l.sentAt()).isEqualTo(m.receivedAt());
    }

    /** Переслали без своего текста — в переписке остаётся цитата, а не пусто. */
    @Test
    void letterThatIsAllQuote_keepsItsText() {
        ParsedMail m = mail().from("c@clinic.kz").text("> Добрый день! Повторяю запрос на кушетки").build();

        assertThat(EmailLetter.of(m, MailFolder.INBOX, 7).body()).isEqualTo("> Добрый день! Повторяю запрос на кушетки");
    }

    @Test
    void key_longMessageIdHashed_missingUsesFolderAndUid() {
        String longId = "<" + "x".repeat(120) + "@clinic.kz>";
        assertThat(EmailLetter.key(longId, MailFolder.INBOX, 7, 5)).startsWith("sha256:").hasSize(71)
                .isEqualTo(EmailLetter.key(longId, MailFolder.SENT, 9, 1));
        assertThat(EmailLetter.key(null, MailFolder.SENT, 7, 5)).isEqualTo("uid:sent:7:5");
        assertThat(EmailLetter.key("<>", MailFolder.INBOX, 7, 5)).isEqualTo("uid:inbox:7:5");
    }

    @Test
    void nameOf_cases() {
        assertThat(EmailLetter.nameOf("Иван Петров <ivan@x.kz>")).isEqualTo("Иван Петров");
        assertThat(EmailLetter.nameOf("ivan@x.kz")).isNull();
        assertThat(EmailLetter.nameOf("<ivan@x.kz>")).isNull();
    }
}
```

`EmailChatWriterTest.java` (пакет `chat`):

```java
package com.vladoose.nir.chat;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class EmailChatWriterTest {

    static final String BOX = "info@westmed.kz";

    @Autowired EmailChatWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired EntityManager em;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private EmailLetter letter(String from, String name, String subject, String body, MailFile... files) {
        clock = clock.plusMinutes(5);
        return new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject, body, clock, from, name, List.of(files));
    }

    private List<Lead> leadsOf(Long chatId) { return leadRepository.findByChatIdIn(List.of(chatId)); }

    /** Обращение — «Заявка создана», вся активность N дней назад (через SQL: @PreUpdate переписал бы updated_at). */
    private void age(Long chatId, int days) {
        em.flush();
        em.createNativeQuery("update lead set status = 'CONVERTED', updated_at = now() - (:d * interval '1 day') where chat_id = :c")
                .setParameter("d", days).setParameter("c", chatId).executeUpdate();
        em.createNativeQuery("update chat set last_message_at = now() - (:d * interval '1 day') where id = :c")
                .setParameter("d", days).setParameter("c", chatId).executeUpdate();
        em.clear();
    }

    @Test
    void firstLetterCreatesEmailChatMessageAndNewLead() {
        String from = address();
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(from, "Айгуль Сапарова", "Запрос цены", "Нужны кушетки 3 шт."));

        Chat c = chatRepository.findById(o.chatId()).orElseThrow();
        assertThat(c.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(c.getAccount()).isEqualTo(BOX);
        assertThat(c.getExternalChatId()).isEqualTo(from);
        assertThat(c.getTitle()).isEqualTo("Айгуль Сапарова");
        assertThat(c.getPhoneNorm()).isNull();
        assertThat(c.getMarket()).isEqualTo(Market.KZ);
        assertThat(c.getLastMessagePreview()).isEqualTo("Запрос цены — Нужны кушетки 3 шт.");
        ChatMessage m = messageRepository.findById(o.messageId()).orElseThrow();
        assertThat(m.getType()).isEqualTo(ChatMessageType.EMAIL);
        assertThat(m.getDirection()).isEqualTo(LeadDirection.IN);
        assertThat(m.getSubject()).isEqualTo("Запрос цены");
        assertThat(m.getBody()).isEqualTo("Нужны кушетки 3 шт.");
        assertThat(m.getSenderName()).isEqualTo("Айгуль Сапарова");
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(l.getSource()).isEqualTo(LeadSources.EMAIL);
        assertThat(l.getSubject()).isEqualTo("Запрос цены");
        assertThat(l.getContactName()).isEqualTo("Айгуль Сапарова");
        assertThat(l.getContactEmail()).isEqualTo(from);
        assertThat(l.getMessage()).isEqualTo("Нужны кушетки 3 шт.");
        assertThat(l.getReceivedAt().toInstant()).isEqualTo(m.getSentAt().toInstant());
        assertThat(l.getChat().getId()).isEqualTo(c.getId());
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Запрос цены — почта");
    }

    @Test
    void noSubjectBecomesLetter_noNameKeepsAddressAsTitle() {
        String from = address();
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(from, null, "", "Здравствуйте"));

        assertThat(chatRepository.findById(o.chatId()).orElseThrow().getTitle()).isEqualTo(from);
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getSubject()).isEqualTo("Письмо");
        assertThat(l.getContactName()).isNull();
    }

    @Test
    void nextLettersGoToTheSameChatAndLead() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Нужны кушетки"));
        EmailChatWriter.Outcome second = writer.writeIncoming(BOX, letter(from, "Айгуль", "Re: Запрос", "И ширмы"));

        assertThat(second.chatId()).isEqualTo(first.chatId());
        assertThat(second.createdLeadId()).isNull();
        assertThat(leadsOf(first.chatId())).hasSize(1);
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().getLastMessagePreview()).isEqualTo("Re: Запрос — И ширмы");
    }

    @Test
    void ourReplyTakesLeadIntoWork() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Нужны кушетки"));
        EmailChatWriter.Outcome reply = writer.writeOutgoing(BOX, from, letter(BOX, null, "Re: Запрос", "Подготовим КП"));

        assertThat(reply.chatId()).isEqualTo(first.chatId());
        ChatMessage out = messageRepository.findById(reply.messageId()).orElseThrow();
        assertThat(out.getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(out.getSenderName()).isNull();
        Lead l = leadRepository.findById(first.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.STATUS);
            assertThat(e.getAuthor()).isNull();
            assertThat(e.getBody()).contains("ответ клиенту письмом из почты");
        });
    }

    /** Первый запуск читает «Отправленные» после «Входящих»: наше письмо, написанное ДО письма клиента, в работу не берёт. */
    @Test
    void ourLetterWrittenBeforeTheLeadDoesNotTakeIt() {
        String from = address();
        EmailLetter early = letter(BOX, null, "КП", "Направляем КП");
        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(from, "Айгуль", "Re: КП", "А есть дешевле?"));
        writer.writeOutgoing(BOX, from, early);

        assertThat(leadRepository.findById(in.createdLeadId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void weWroteFirst_chatWithoutLead_thenClientReplyStartsLead() {
        String to = address();
        EmailChatWriter.Outcome out = writer.writeOutgoing(BOX, to, letter(BOX, null, "Предложение", "Добрый день"));

        assertThat(out.createdLeadId()).isNull();
        assertThat(leadsOf(out.chatId())).isEmpty();
        assertThat(chatRepository.findById(out.chatId()).orElseThrow().getTitle()).isEqualTo(to);

        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(to, "Ерлан", "Re: Предложение", "Интересно"));
        assertThat(in.createdLeadId()).isNotNull();
        assertThat(chatRepository.findById(out.chatId()).orElseThrow().getTitle()).isEqualTo("Ерлан");
    }

    @Test
    void siteLeadWithSameEmailIsContinued_caseInsensitive_andSiteStatusQueued() {
        String from = address();
        Lead site = intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:zz-" + UUID.randomUUID(), LeadChannel.SITE,
                "Заявка с сайта", OffsetDateTime.now().minusDays(1), "Айгуль", null, from.toUpperCase(Locale.ROOT), null,
                "Нужен аппарат", List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();

        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(from, "Айгуль", "Заявка", "Это я с сайта"));
        writer.writeOutgoing(BOX, from, letter(BOX, null, "Re: Заявка", "Видим, работаем"));

        assertThat(in.createdLeadId()).isNull();
        Lead l = leadRepository.findById(site.getId()).orElseThrow();
        assertThat(l.getChat().getId()).isEqualTo(in.chatId());
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getExtStatusPending()).isEqualTo("PROCESSED");
    }

    @Test
    void letterAfterClosedLeadStartsNewLead() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Нужны кушетки"));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");

        EmailChatWriter.Outcome again = writer.writeIncoming(BOX, letter(from, "Айгуль", "Новый запрос", "Теперь ширмы"));

        assertThat(again.createdLeadId()).isNotNull().isNotEqualTo(first.createdLeadId());
    }

    @Test
    void convertedLeadIsContinuedWithin30DaysButNotAfter() {
        String recent = address();
        EmailChatWriter.Outcome r = writer.writeIncoming(BOX, letter(recent, "Айгуль", "Запрос", "Нужны кушетки"));
        age(r.chatId(), 10);
        assertThat(writer.writeIncoming(BOX, letter(recent, "Айгуль", "Re: Запрос", "Когда КП?")).createdLeadId()).isNull();

        String stale = address();
        EmailChatWriter.Outcome s = writer.writeIncoming(BOX, letter(stale, "Ерлан", "Запрос", "Нужны кушетки"));
        age(s.chatId(), 40);
        assertThat(writer.writeIncoming(BOX, letter(stale, "Ерлан", "Новый запрос", "Теперь ширмы")).createdLeadId()).isNotNull();
    }

    @Test
    void notClientChat_storesLettersWithoutLeads() {
        String from = address();
        EmailChatWriter.Outcome first = writer.writeOutgoing(BOX, from, letter(BOX, null, "Привет", "Это мы"));
        chatRepository.findById(first.chatId()).orElseThrow().setNotClient(true);

        EmailChatWriter.Outcome in = writer.writeIncoming(BOX, letter(from, "Поставщик", "Каталог", "Наш каталог"));

        assertThat(in.createdLeadId()).isNull();
        assertThat(leadsOf(in.chatId())).isEmpty();
        assertThat(messageRepository.findLatest(in.chatId(), PageRequest.of(0, 5))).hasSize(2);
    }

    @Test
    void sameLetterTwice_isDuplicate() {
        String from = address();
        EmailLetter l = letter(from, "Айгуль", "Запрос", "Текст");
        writer.writeIncoming(BOX, l);

        EmailChatWriter.Outcome again = writer.writeIncoming(BOX, l);

        assertThat(again.duplicate()).isTrue();
        assertThat(messageRepository.findLatest(again.chatId(), PageRequest.of(0, 5))).hasSize(1);
        assertThat(leadsOf(again.chatId())).hasSize(1);
    }

    @Test
    void filesAreStored_bigOneMarkedTooLarge() {
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(address(), "Айгуль", "Смета", "Во вложении",
                new MailFile("смета.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 3L, new byte[]{1, 2, 3}),
                new MailFile("видео.mp4", "video/mp4", null, null)));

        assertThat(attachmentRepository.findMetaByMessageIds(List.of(o.messageId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::sizeBytes, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("смета.xlsx", 3L, null), tuple("видео.mp4", null, AttachmentNotStoredReason.TOO_LARGE));
    }

    @Test
    void facilityIsMatchedByEmail() {
        String from = address();
        Facility f = facilityRepository.save(Facility.builder().name("ZZ Клиника " + UUID.randomUUID()).email(from)
                .market(Market.KZ).build());

        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(from, "Айгуль", "Запрос", "Текст"));

        assertThat(leadRepository.findById(o.createdLeadId()).orElseThrow().getFacility().getId()).isEqualTo(f.getId());
    }

    @Test
    void leadTextIsCutTo4000_letterKeptWhole() {
        EmailChatWriter.Outcome o = writer.writeIncoming(BOX, letter(address(), null, "Длинное", "а".repeat(5000)));

        assertThat(leadRepository.findById(o.createdLeadId()).orElseThrow().getMessage()).hasSize(4000);
        assertThat(messageRepository.findById(o.messageId()).orElseThrow().getBody()).hasSize(5000);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.EmailLetterTest' --tests 'com.vladoose.nir.chat.EmailChatWriterTest'`
Expected: FAIL — компиляция: `cannot find symbol EmailLetter` / `EmailChatWriter` / `LeadSources.EMAIL`.

- [ ] **Step 3: Implement**

`LeadSources.java`:

```java
    public static final String EMAIL = "email";
    ...
    /** Подпись источника в ленте обращения: «westmed.kz», «WhatsApp», «почта»… (у ручного ввода своя подпись). */
    public static String label(String source) {
        if (WHATSAPP.equals(source)) return "WhatsApp";
        if (EMAIL.equals(source)) return "почта";
        return source;
    }
```

`LeadRepository.java`:

```java
    List<Lead> findByContactEmailIgnoreCaseAndStatusIn(String contactEmail, Collection<LeadStatus> statuses);
```

`ChatLeadRules.findOpenLead` — после блока по телефону (импорт `com.vladoose.nir.entity.LeadChannel`), javadoc класса — «по чату, по номеру ИЛИ (почта) по адресу»:

```java
        if (chat.getChannel() == LeadChannel.EMAIL && chat.getExternalChatId() != null) {
            // почтовая переписка склеивается с обращением того же адреса (заявка с сайта) — без учёта регистра
            leadRepository.findByContactEmailIgnoreCaseAndStatusIn(chat.getExternalChatId(), CANDIDATES)
                    .forEach(l -> byId.put(l.getId(), l));
        }
```

`service/mail/EmailLetter.java`:

```java
package com.vladoose.nir.service.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Письмо ящика info@ для переписки (спека 2026-10-13-info-mail-leads §2.3): ключ повтора, тема, текст без цитаты
 * (вся цепочка и так видна в ленте), время, отправитель, файлы. Собирается ВНЕ транзакции записи.
 *
 * @param key  ключ сообщения в переписке: Message-ID (длиннее 100 символов — его SHA-256), без него — папка и номер
 * @param body текст без цитаты; письмо целиком из цитаты — его текст как есть
 */
public record EmailLetter(String key, String subject, String body, OffsetDateTime sentAt,
                          String fromAddress, String fromName, List<MailFile> files) {

    /** Длина chat_message.external_id. */
    static final int KEY_MAX = 100;

    public static EmailLetter of(ParsedMail m, MailFolder folder, long uidValidity) {
        String reply = MailText.replyText(m);
        String body = reply.isBlank() ? MailText.normalize(m.body()) : reply;
        return new EmailLetter(key(m.messageId(), folder, uidValidity, m.uid()), m.subject().strip(), body,
                m.receivedAt(), m.fromAddress(), nameOf(m.from()), m.files());
    }

    static String key(String messageId, MailFolder folder, long uidValidity, long uid) {
        String id = messageId == null ? "" : messageId.strip();
        if (!id.replace("<", "").replace(">", "").isBlank()) {
            return id.length() <= KEY_MAX ? id : "sha256:" + sha256(id);
        }
        return "uid:" + folder.name().toLowerCase(Locale.ROOT) + ":" + uidValidity + ":" + uid;
    }

    /** «Иван Петров <ivan@x.kz>» → «Иван Петров»; голый адрес — null. */
    static String nameOf(String from) {
        if (from == null) return null;
        int lt = from.lastIndexOf('<');
        if (lt <= 0) return null;
        String name = from.substring(0, lt).strip().replaceAll("^\"|\"$", "").strip();
        return name.isEmpty() ? null : name;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`service/EmailChatWriter.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.service.mail.EmailLetter;
import com.vladoose.nir.service.mail.MailFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.util.LeadText.*;

/**
 * Письмо ящика info@ в почтовой переписке (спека 2026-10-13-info-mail-leads §2.3–§2.4): переписка — одна на адрес
 * собеседника (channel EMAIL, account — ящик, external_chat_id — адрес нижним регистром), сообщение — письмо (вид
 * EMAIL: тема, текст без цитаты, файлы). Обращения — по правилам чатов (ChatLeadRules): входящее без открытого —
 * новое; наше письмо из «Отправленных», написанное не раньше обращения, переводит «Новое» → «В работе»; «не клиент» —
 * без обращений. Транзакцию держит вызывающий (ClientMailWriter — одна на письмо).
 */
@Service
public class EmailChatWriter {

    /** Событие ленты, когда наше письмо из почты берёт обращение в работу. */
    static final String AUTO_TAKE_NOTE = "ответ клиенту письмом из почты";
    /** Тема обращения из письма без темы. */
    static final String NO_SUBJECT = "Письмо";
    static final int LEAD_TEXT_MAX = 4000;
    private static final int PREVIEW_MAX = 300;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatLeadRules rules;
    private final LeadIntakeService intake;
    private final LeadService leadService;

    public EmailChatWriter(ChatRepository chatRepository, ChatMessageRepository messageRepository,
                           ChatAttachmentRepository attachmentRepository, ChatLeadRules rules,
                           LeadIntakeService intake, LeadService leadService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.rules = rules;
        this.intake = intake;
        this.leadService = leadService;
    }

    /** duplicate — письмо уже в переписке; createdLeadId — заведено новое обращение. */
    public record Outcome(boolean duplicate, Long chatId, Long messageId, Long createdLeadId) {}

    /** Письмо собеседника из «Входящих» ящика mailbox. */
    @Transactional
    public Outcome writeIncoming(String mailbox, EmailLetter letter) {
        return write(mailbox, letter.fromAddress(), letter.fromName(), LeadDirection.IN, letter);
    }

    /** Наше письмо из «Отправленных» — в переписку с адресатом to (адрес нижним регистром). */
    @Transactional
    public Outcome writeOutgoing(String mailbox, String to, EmailLetter letter) {
        return write(mailbox, to, null, LeadDirection.OUT, letter);
    }

    private Outcome write(String mailbox, String address, String name, LeadDirection dir, EmailLetter letter) {
        Chat chat = upsertChat(mailbox, address, name);
        if (messageRepository.existsByChatIdAndExternalId(chat.getId(), letter.key())) {
            return new Outcome(true, chat.getId(), null, null);
        }
        ChatMessage msg = messageRepository.save(ChatMessage.builder()
                .chat(chat).externalId(letter.key()).direction(dir)
                .senderName(dir == LeadDirection.IN ? trunc(name != null ? name : address, 255) : null)
                .type(ChatMessageType.EMAIL).subject(blankToNull(letter.subject())).body(blankToNull(letter.body()))
                .sentAt(letter.sentAt()).build());
        for (MailFile f : letter.files()) {
            attachmentRepository.save(ChatAttachment.builder().message(msg)
                    .fileName(trunc(f.name(), 255)).mimeType(trunc(f.mimeType(), 100))
                    .sizeBytes(f.size()).content(f.content())
                    .notStoredReason(f.content() == null ? AttachmentNotStoredReason.TOO_LARGE : null).build());
        }
        // правила — ДО сдвига lastMessageAt: активность обращения меряется по ПРОШЛЫМ письмам (как у WhatsApp)
        Long createdLeadId = applyLeadRules(chat, msg, dir, letter);
        if (chat.getLastMessageAt() == null || !letter.sentAt().isBefore(chat.getLastMessageAt())) {
            chat.setLastMessageAt(letter.sentAt());
            chat.setLastMessagePreview(trunc(preview(letter), PREVIEW_MAX));
        }
        return new Outcome(false, chat.getId(), msg.getId(), createdLeadId);
    }

    private Chat upsertChat(String mailbox, String address, String name) {
        Optional<Chat> found = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, mailbox, address);
        if (found.isEmpty()) {
            return chatRepository.save(Chat.builder()
                    .market(MarketContext.get())   // пред-штамп (defense-in-depth к листенеру)
                    .channel(LeadChannel.EMAIL).account(mailbox).externalChatId(address)
                    .title(name != null ? trunc(name, 255) : address)
                    .build());
        }
        Chat chat = found.get();
        // имя — из писем собеседника (наше письмо из «Отправленных» его имени не несёт)
        if (name != null && !name.equals(chat.getTitle())) chat.setTitle(trunc(name, 255));
        return chat;
    }

    private Long applyLeadRules(Chat chat, ChatMessage msg, LeadDirection dir, EmailLetter letter) {
        if (chat.isNotClient()) return null;
        Optional<Lead> open = rules.findOpenLead(chat, letter.sentAt());
        if (open.isPresent()) {
            Lead lead = open.get();
            if (lead.getChat() == null) lead.setChat(chat);
            // наше письмо, написанное РАНЬШЕ обращения (первый запуск читает «Отправленные» после «Входящих»),
            // работу по нему не означает
            if (dir == LeadDirection.OUT && !letter.sentAt().isBefore(lead.getReceivedAt())) {
                leadService.takeAutomatically(lead, AUTO_TAKE_NOTE);
            }
            return null;
        }
        if (dir != LeadDirection.IN) return null;   // написали первыми мы — обращение не заводим
        String subject = isBlank(letter.subject()) ? NO_SUBJECT : letter.subject().strip();
        IncomingLead in = new IncomingLead(LeadSources.EMAIL, "mail:" + msg.getId(), LeadChannel.EMAIL, subject,
                letter.sentAt(), letter.fromName(), null, letter.fromAddress(), null,
                trunc(letter.body(), LEAD_TEXT_MAX), List.of(), LeadStatus.NEW, null, null);
        return intake.ingest(in).map(lead -> {
            lead.setChat(chat);
            return lead.getId();
        }).orElse(null);
    }

    /** Превью в списке чатов: «Тема — текст», иначе что есть. */
    static String preview(EmailLetter l) {
        String subject = l.subject() == null ? "" : l.subject().strip();
        String body = l.body() == null ? "" : l.body().strip().replaceAll("\\s+", " ");
        if (!subject.isEmpty() && !body.isEmpty()) return subject + " — " + body;
        if (!subject.isEmpty()) return subject;
        if (!body.isEmpty()) return body;
        return l.files().isEmpty() ? NO_SUBJECT : "📎 " + l.files().get(0).name();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.EmailLetterTest' --tests 'com.vladoose.nir.chat.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS (WhatsApp-тесты чатов — без изменений).

- [ ] **Step 5: Мутации** (по одной, откат копией): убрать ветку EMAIL в `ChatLeadRules` → `siteLeadWithSameEmailIsContinued…` красный; убрать проверку времени у `takeAutomatically` → `ourLetterWrittenBeforeTheLeadDoesNotTakeIt` красный; убрать `if (chat.isNotClient()) return null;` → `notClientChat…` красный; убрать `if (dir != LeadDirection.IN) return null;` → `weWroteFirst…` красный.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir
git commit -m "feat(leads): почтовая переписка и обращения из писем info@ (EmailChatWriter)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Запись писем info@ (`ClientMailWriter`)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientMailWriter.java`
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java` (`messageIdOf` → package-private)
- Test: `src/test/java/com/vladoose/nir/service/mail/ClientMailWriterTest.java`

**Interfaces:**
- Consumes: `ClientMailRouter`, `ClientMailSettings`, `EmailLetter.of`, `EmailChatWriter.writeIncoming/writeOutgoing`, `MailCursorRepository.advance`, `MailIngestWriter.messageIdOf`.
- Produces: `ClientMailWriter.Kind { SKIPPED, INBOUND, CONVERSATION, OUTGOING, DUPLICATE }`, `record ClientMailWriter.Result(Kind kind, Long createdLeadId)`; `@Transactional Result write(ParsedMail, MailFolder, long uidValidity)`, `@Transactional void writeBroken(BrokenMail, MailFolder, long uidValidity)`, `@Transactional void moveCursorTo(MailFolder, long uidValidity, long uid)`.

- [ ] **Step 1: Write the failing test** — `ClientMailWriterTest.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.EmailChatWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ClientMailWriterTest {

    static final String BOX = "info@westmed.kz";

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired MailCursorRepository cursorRepo;
    @Autowired EmailChatWriter chats;
    @Autowired ChatRepository chatRepository;

    ClientMailWriter writer;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        writer = new ClientMailWriter(inboundRepo, cursorRepo, chats, new ClientMailSettings(true, "127.0.0.1", 3143,
                BOX, "secret", "imap", "KZ", 7, 25, "*.mail.ru,2gis.ru,2gis.com,ecc.kz,goszakup.gov.kz",
                "info@westmed.kz", "zakup@westmed.kz"));
        cursorRepo.findById(MailCursorKey.inbox(BOX)).ifPresent(cursorRepo::delete);
        cursorRepo.findById(new MailCursorKey(BOX, MailCursor.SENT)).ifPresent(cursorRepo::delete);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String unique(String domain) { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@" + domain; }

    private InboundEmail row(ParsedMail m) {
        return inboundRepo.findAll().stream()
                .filter(e -> BOX.equals(e.getMailbox()) && m.messageId().equals(e.getMessageId())).findFirst().orElseThrow();
    }

    private long cursor(String folder) {
        return cursorRepo.findById(new MailCursorKey(BOX, folder)).orElseThrow().getLastUid();
    }

    @Test
    void clientLetter_goesToConversation_inboxCursorMoves() {
        String from = unique("clinic.kz");

        ClientMailWriter.Result r = writer.write(mail().uid(11).from("Айгуль <" + from + ">").subject("Запрос цены")
                .text("Нужны кушетки").build(), MailFolder.INBOX, 5);

        assertThat(r.kind()).isEqualTo(ClientMailWriter.Kind.CONVERSATION);
        assertThat(r.createdLeadId()).isNotNull();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, from)).isPresent();
        assertThat(cursor(MailCursor.INBOX)).isEqualTo(11);
        assertThat(cursorRepo.findById(new MailCursorKey(BOX, MailCursor.SENT))).isEmpty();
    }

    @Test
    void siteNotification_skipped_noRow_cursorMoves() {
        ParsedMail m = mail().uid(12).from("Сайт <info@westmed.kz>").subject("Новая заявка — westmed.kz").build();

        assertThat(writer.write(m, MailFolder.INBOX, 5).kind()).isEqualTo(ClientMailWriter.Kind.SKIPPED);
        assertThat(inboundRepo.existsByMailboxAndMessageId(BOX, m.messageId())).isFalse();
        assertThat(cursor(MailCursor.INBOX)).isEqualTo(12);
    }

    /** Возврат, автоответ, рассылка, служебное, своё — строкой «Входящих» ящика info@, без уведомления в Telegram (§4). */
    @Test
    void reportsNewslettersServiceAndOwnDomain_goToInboxJournal_withoutTelegram() {
        record Case(ParsedMail m, InboundType type) {}
        List<Case> cases = List.of(
                new Case(mail().uid(21).from("MAILER-DAEMON@corp.mail.ru").contentType("multipart/report; report-type=delivery-status")
                        .bounce("a@b.kz", "5.1.1", "550", "Запрос", "failed").build(), InboundType.BOUNCE),
                new Case(mail().uid(22).from("anna@clinic.kz").header("Auto-Submitted", "auto-replied").build(), InboundType.AUTO_REPLY),
                new Case(mail().uid(23).from("news@shop.kz").header("List-Unsubscribe", "<mailto:u@shop.kz>").build(), InboundType.UNMATCHED),
                new Case(mail().uid(24).from("security@id.mail.ru").build(), InboundType.UNMATCHED),
                new Case(mail().uid(25).from("buh@westmed.kz").build(), InboundType.UNMATCHED));

        for (Case c : cases) {
            assertThat(writer.write(c.m(), MailFolder.INBOX, 5).kind()).isEqualTo(ClientMailWriter.Kind.INBOUND);
            InboundEmail e = row(c.m());
            assertThat(e.getType()).isEqualTo(c.type());
            assertThat(e.getMarket()).isEqualTo(Market.KZ);
            assertThat(e.getNotifyStatus()).isNull();
        }
        assertThat(cursor(MailCursor.INBOX)).isEqualTo(25);
    }

    @Test
    void inboxJournalDuplicateByMessageId_oneRow() {
        ParsedMail m = mail().uid(26).from("news@shop.kz").header("Precedence", "bulk").build();
        writer.write(m, MailFolder.INBOX, 5);

        assertThat(writer.write(m, MailFolder.INBOX, 5).kind()).isEqualTo(ClientMailWriter.Kind.DUPLICATE);
        assertThat(inboundRepo.findAll().stream().filter(e -> m.messageId().equals(e.getMessageId()))).hasSize(1);
    }

    @Test
    void sentReplyGoesToEachExternalRecipient_notToColleaguesOrServices() {
        String a = unique("clinic.kz");
        String b = unique("hospital.kz");
        ParsedMail m = mail().uid(31).from("West-Med <info@westmed.kz>").subject("Re: Запрос").text("КП во вложении")
                .to(a, "colleague@westmed.kz", b, "support@corp.mail.ru", "noreply@2gis.ru").build();

        ClientMailWriter.Result r = writer.write(m, MailFolder.SENT, 9);

        assertThat(r.kind()).isEqualTo(ClientMailWriter.Kind.OUTGOING);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, a)).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, b)).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "colleague@westmed.kz")).isEmpty();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "support@corp.mail.ru")).isEmpty();
        assertThat(cursor(MailCursor.SENT)).isEqualTo(31);
        assertThat(cursorRepo.findById(MailCursorKey.inbox(BOX))).isEmpty();
    }

    @Test
    void sentToColleaguesOnly_skipped() {
        ParsedMail m = mail().uid(32).from("info@westmed.kz").subject("Акт").to("buh@westmed.kz").build();

        assertThat(writer.write(m, MailFolder.SENT, 9).kind()).isEqualTo(ClientMailWriter.Kind.SKIPPED);
        assertThat(cursor(MailCursor.SENT)).isEqualTo(32);
    }

    @Test
    void brokenLetter_shortRowAndCursorOfItsFolder() {
        writer.writeBroken(new BrokenMail(41, "<zz-broken-" + UUID.randomUUID() + "@x.kz>", "a@b.kz", "Тема",
                OffsetDateTime.now(), "MessagingException"), MailFolder.SENT, 9);

        assertThat(inboundRepo.findAll()).anySatisfy(e -> {
            assertThat(e.getMailbox()).isEqualTo(BOX);
            assertThat(e.getImapUid()).isEqualTo(41L);
            assertThat(e.getExcerpt()).isEqualTo("Письмо из «Отправленных» не удалось разобрать (MessagingException) — откройте его в почте");
        });
        assertThat(cursor(MailCursor.SENT)).isEqualTo(41);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientMailWriterTest'`
Expected: FAIL — компиляция: `cannot find symbol ClientMailWriter`.

- [ ] **Step 3: Implement**

`MailIngestWriter.java` — `private static String messageIdOf(String raw)` → `static String messageIdOf(String raw)` (javadoc тот же).

`ClientMailWriter.java`:

```java
package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.EmailChatWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Запись письма ящика info@ (спека 2026-10-13-info-mail-leads §2.2–§2.3) — одна транзакция на письмо вместе со сдвигом
 * курсора его папки. «Входящие»: уведомление сайта — пропуск; письмо собеседника — в переписку (EmailChatWriter);
 * возврат, автоответ, рассылка, служебное и своё — строкой «Входящих» БЕЗ уведомления в Telegram (письма info@ шлёт
 * medsite, §4). «Отправленные»: наше письмо — в переписку с каждым адресатом «Кому» и «Копия», кроме своего домена и
 * служебных адресов. Рынок ставит вызывающий (MarketContext).
 */
@Service
public class ClientMailWriter {

    /** Что стало с письмом. */
    public enum Kind { SKIPPED, INBOUND, CONVERSATION, OUTGOING, DUPLICATE }

    /** Итог записи; createdLeadId — заведено новое обращение. */
    public record Result(Kind kind, Long createdLeadId) {}

    private final InboundEmailRepository inboundRepo;
    private final MailCursorRepository cursorRepo;
    private final EmailChatWriter chats;
    private final ClientMailSettings settings;

    public ClientMailWriter(InboundEmailRepository inboundRepo, MailCursorRepository cursorRepo, EmailChatWriter chats,
                            ClientMailSettings settings) {
        this.inboundRepo = inboundRepo;
        this.cursorRepo = cursorRepo;
        this.chats = chats;
        this.settings = settings;
    }

    @Transactional
    public Result write(ParsedMail m, MailFolder folder, long uidValidity) {
        Result r = folder == MailFolder.SENT ? sent(m, uidValidity) : inbox(m, uidValidity);
        cursorRepo.advance(key(folder), uidValidity, m.uid());
        return r;
    }

    /** Письмо, которое не разобралось или не записалось: короткая строка, курсор папки дальше. */
    @Transactional
    public void writeBroken(BrokenMail b, MailFolder folder, long uidValidity) {
        inboundRepo.save(InboundEmail.builder()
                .mailbox(settings.username()).imapUid(b.uid()).messageId(MailIngestWriter.messageIdOf(b.messageId()))
                .fromAddress(MailText.safeCut(b.from(), 320)).subject(MailText.safeCut(b.subject(), 998))
                .receivedAt(b.receivedAt()).type(InboundType.UNMATCHED)
                .excerpt((folder == MailFolder.SENT ? "Письмо из «Отправленных» не удалось разобрать ("
                        : "Письмо не удалось разобрать (") + b.errorClass() + ") — откройте его в почте")
                .status(InboundStatus.NEW).build());
        cursorRepo.advance(key(folder), uidValidity, b.uid());
    }

    /** Первый запуск папки: курсор — на последнее письмо, снятое до окна. */
    @Transactional
    public void moveCursorTo(MailFolder folder, long uidValidity, long uid) {
        cursorRepo.advance(key(folder), uidValidity, uid);
    }

    private Result inbox(ParsedMail m, long uidValidity) {
        return switch (ClientMailRouter.route(m, settings.rules())) {
            case SITE_NOTIFICATION -> new Result(Kind.SKIPPED, null);   // заявка уже пришла обращением через API сайта
            case CONVERSATION -> {
                EmailChatWriter.Outcome o = chats.writeIncoming(settings.username(), EmailLetter.of(m, MailFolder.INBOX, uidValidity));
                yield new Result(o.duplicate() ? Kind.DUPLICATE : Kind.CONVERSATION, o.createdLeadId());
            }
            case BOUNCE -> inbound(m, InboundType.BOUNCE);
            case DELAYED -> inbound(m, InboundType.DELAYED);
            case AUTO_REPLY -> inbound(m, InboundType.AUTO_REPLY);
            case NEWSLETTER, SERVICE, OWN_DOMAIN -> inbound(m, InboundType.UNMATCHED);
        };
    }

    private Result inbound(ParsedMail m, InboundType type) {
        String messageId = MailIngestWriter.messageIdOf(m.messageId());
        if (messageId != null && inboundRepo.existsByMailboxAndMessageId(settings.username(), messageId)) {
            return new Result(Kind.DUPLICATE, null);
        }
        inboundRepo.save(InboundEmail.builder()
                .mailbox(settings.username()).imapUid(m.uid()).messageId(messageId)
                .fromAddress(MailText.safeCut(m.from(), 320)).subject(MailText.safeCut(m.subject(), 998))
                .receivedAt(m.receivedAt()).type(type).excerpt(MailText.safeCut(m.body(), 2000))
                .status(InboundStatus.NEW).build());   // @PrePersist стампит рынок ящика из MarketContext
        return new Result(Kind.INBOUND, null);
    }

    private Result sent(ParsedMail m, long uidValidity) {
        ClientMailRules rules = settings.rules();
        List<String> to = m.recipients().stream()
                .filter(a -> !ClientMailRouter.isOwnDomain(a, rules) && !ClientMailRouter.isService(a, rules))
                .toList();
        if (to.isEmpty()) return new Result(Kind.SKIPPED, null);   // внутреннее письмо, ответ в поддержку службы
        EmailLetter letter = EmailLetter.of(m, MailFolder.SENT, uidValidity);
        boolean fresh = false;
        for (String address : to) {
            fresh |= !chats.writeOutgoing(settings.username(), address, letter).duplicate();
        }
        return new Result(fresh ? Kind.OUTGOING : Kind.DUPLICATE, null);
    }

    private MailCursorKey key(MailFolder folder) {
        return new MailCursorKey(settings.username(), folder.cursorKey());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.mail.ClientMailWriterTest' --tests 'com.vladoose.nir.service.mail.MailIngestWriterTest'`
Expected: PASS.

- [ ] **Step 5: Мутации** (по одной): убрать фильтр `isOwnDomain` в `sent` → `sentReply…` красный (переписка с коллегой); убрать `isService` → красный (support@corp.mail.ru); убрать проверку повтора в `inbound` → `inboxJournalDuplicateByMessageId_oneRow` красный.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/mail src/test/java/com/vladoose/nir/service/mail/ClientMailWriterTest.java
git commit -m "feat(mail): запись писем info@ — переписка, «Входящие» без Telegram, наши ответы адресатам

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Приём info@ — две папки, свой поток, проверка на GreenMail

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/mail/ClientMailbox.java`, `src/main/java/com/vladoose/nir/service/ClientMailReceiveService.java`, `ClientMailScheduler.java`
- Modify: `src/test/java/com/vladoose/nir/mail/MailIntegrationTest.java`, `src/test/java/com/vladoose/nir/service/mail/ImapTestSupport.java`
- Test: `src/test/java/com/vladoose/nir/service/ClientMailReceiveServiceTest.java`, `ClientMailSchedulerTest.java`, `src/test/java/com/vladoose/nir/mail/ClientMailIntegrationTest.java`

**Interfaces:**
- Consumes: `ImapMailboxConnector` (8-аргументный конструктор, `open(MailFolder)`), `MailFolderPass.run`, `ClientMailWriter`, `ClientMailSettings`, `MailCursorRepository`.
- Produces: `ClientMailbox.open(MailFolder) → MailboxSession`; `ClientMailReceiveService.poll() → ClientMailReceiveService.Result(boolean enabled, int conversations, int outgoing, int inbound, int skipped, int duplicates, int broken, int createdLeads, String stopped)`; `ClientMailScheduler.tick()` и package-private `ClientMailReceiveService.Result ClientMailScheduler.pass()` (null — проход упал); `TestMimes.letter(from, to, cc, subject, text)`, `TestMimes.withHeader(message, name, value)`; `ImapTestSupport.seen(String user, String folder, String subject)`.

- [ ] **Step 1: Write the failing tests**

`ClientMailReceiveServiceTest.java` (пакет `service`):

```java
package com.vladoose.nir.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClientMailReceiveServiceTest {

    static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");
    static final String BOX = "info@westmed.kz";

    ClientMailbox mailbox = mock(ClientMailbox.class);
    MailboxSession inbox = mock(MailboxSession.class);
    MailboxSession sent = mock(MailboxSession.class);
    ClientMailWriter writer = mock(ClientMailWriter.class);
    MailCursorRepository cursors = mock(MailCursorRepository.class);
    Logger logger = (Logger) LoggerFactory.getLogger(ClientMailReceiveService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    static ClientMailSettings settings(boolean enabled) {
        return new ClientMailSettings(enabled, "imap.mail.ru", 993, BOX, "pw", "imaps", "KZ", 7, 25, "*.mail.ru",
                "info@westmed.kz", "zakup@westmed.kz");
    }

    ClientMailReceiveService service(boolean enabled) {
        return new ClientMailReceiveService(settings(enabled), mailbox, writer, cursors, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static ClientMailWriter.Result result(ClientMailWriter.Kind kind) { return new ClientMailWriter.Result(kind, null); }

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
        when(mailbox.open(MailFolder.INBOX)).thenReturn(inbox);
        when(mailbox.open(MailFolder.SENT)).thenReturn(sent);
        when(inbox.uidValidity()).thenReturn(5L);
        when(sent.uidValidity()).thenReturn(9L);
        when(inbox.isAlive()).thenReturn(true);
        when(sent.isAlive()).thenReturn(true);
        when(writer.write(any(), any(), anyLong())).thenReturn(result(ClientMailWriter.Kind.CONVERSATION));
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
    }

    void cursor(MailFolder f, long validity, long last) {
        when(cursors.findById(new MailCursorKey(BOX, f.cursorKey()))).thenReturn(Optional.of(
                MailCursor.builder().mailbox(BOX).folder(f.cursorKey()).uidValidity(validity).lastUid(last).build()));
    }

    @Test
    void disabled_noConnection() throws Exception {
        assertThat(service(false).poll().enabled()).isFalse();
        verify(mailbox, never()).open(any());
    }

    /** Сначала «Входящие» (письмо клиента заводит обращение), потом «Отправленные» (наш ответ берёт его в работу). */
    @Test
    void inboxThenSent_eachWithItsCursor() throws Exception {
        cursor(MailFolder.INBOX, 5, 10);
        cursor(MailFolder.SENT, 9, 3);
        when(inbox.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(sent.uidsAfter(3, 100)).thenReturn(List.of(4L));
        when(inbox.fetch(11L)).thenReturn(mail().uid(11).build());
        when(sent.fetch(4L)).thenReturn(mail().uid(4).build());
        when(writer.write(any(), eq(MailFolder.SENT), anyLong())).thenReturn(result(ClientMailWriter.Kind.OUTGOING));

        ClientMailReceiveService.Result r = service(true).poll();

        InOrder order = inOrder(writer);
        order.verify(writer).write(argThat(m -> m != null && m.uid() == 11), eq(MailFolder.INBOX), eq(5L));
        order.verify(writer).write(argThat(m -> m != null && m.uid() == 4), eq(MailFolder.SENT), eq(9L));
        assertThat(r.conversations()).isEqualTo(1);
        assertThat(r.outgoing()).isEqualTo(1);
        assertThat(r.stopped()).isNull();
    }

    @Test
    void firstRun_windowIsSevenDays_cursorsToMax() throws Exception {
        when(cursors.findById(any())).thenReturn(Optional.empty());
        when(inbox.maxUid()).thenReturn(20L);
        when(sent.maxUid()).thenReturn(0L);
        Instant window = NOW.minus(Duration.ofDays(7));
        when(inbox.uidsReceivedSince(window, 100)).thenReturn(List.of(19L));
        when(sent.uidsReceivedSince(window, 100)).thenReturn(List.of());
        when(inbox.fetch(19L)).thenReturn(mail().uid(19).build());

        service(true).poll();

        verify(writer).write(any(), eq(MailFolder.INBOX), eq(5L));
        verify(writer).moveCursorTo(MailFolder.INBOX, 5L, 20L);
        verify(writer).moveCursorTo(MailFolder.SENT, 9L, 0L);
    }

    @Test
    void newUidValidity_isFirstRunAgain() throws Exception {
        cursor(MailFolder.INBOX, 4, 100);
        cursor(MailFolder.SENT, 9, 3);
        when(inbox.maxUid()).thenReturn(2L);
        when(inbox.uidsReceivedSince(any(), anyInt())).thenReturn(List.of());

        service(true).poll();

        verify(inbox, never()).uidsAfter(anyLong(), anyInt());
        verify(writer).moveCursorTo(MailFolder.INBOX, 5L, 2L);
    }

    @Test
    void sentFolderMissing_inboxStillProcessed_warnOnce() throws Exception {
        cursor(MailFolder.INBOX, 5, 10);
        when(inbox.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(inbox.fetch(11L)).thenReturn(mail().uid(11).build());
        when(mailbox.open(MailFolder.SENT)).thenThrow(new FolderNotFoundException((Folder) null, "папка «Отправленные» не найдена"));
        ClientMailReceiveService s = service(true);

        ClientMailReceiveService.Result r = s.poll();
        s.poll();

        assertThat(r.stopped()).isNull();
        verify(writer, times(2)).write(any(), eq(MailFolder.INBOX), anyLong());
        assertThat(logs.list).filteredOn(ev -> ev.getFormattedMessage().contains("«Отправленные»: папка не найдена")).hasSize(1);
    }

    @Test
    void inboxLoginFails_sentNotOpened_serverTextKept() throws Exception {
        when(mailbox.open(MailFolder.INBOX)).thenThrow(new MessagingException("AUTHENTICATIONFAILED"));

        ClientMailReceiveService.Result r = service(true).poll();

        assertThat(r.stopped()).isEqualTo("ошибка почты: AUTHENTICATIONFAILED");
        verify(mailbox, never()).open(MailFolder.SENT);
    }

    @Test
    void connectionLostInInbox_sentNotOpened() throws Exception {
        cursor(MailFolder.INBOX, 5, 10);
        when(inbox.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(inbox.fetch(11L)).thenThrow(new MessagingException("BYE"));
        when(inbox.isAlive()).thenReturn(false);

        assertThat(service(true).poll().stopped()).isEqualTo(MailFolderPass.CONNECTION_LOST);
        verify(mailbox, never()).open(MailFolder.SENT);
    }

    @Test
    void dbDownOnCursor_stopsWithDbText_noAddressesInLog() throws Exception {
        when(cursors.findById(any())).thenThrow(new DataAccessResourceFailureException("db down: client@clinic.kz"));

        ClientMailReceiveService.Result r = service(true).poll();

        assertThat(r.stopped()).isEqualTo("база данных недоступна (DataAccessResourceFailureException)");
        verify(mailbox, never()).open(MailFolder.SENT);
        assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain("client@clinic.kz"));
    }
}
```

`ClientMailSchedulerTest.java` (пакет `service`):

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.mail.ClientMailSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClientMailSchedulerTest {

    ClientMailReceiveService service = mock(ClientMailReceiveService.class);

    @AfterEach void clear() { MarketContext.clear(); }

    static ClientMailSettings settings(boolean enabled) {
        return new ClientMailSettings(enabled, "imap.mail.ru", 993, "info@westmed.kz", "pw", "imaps", "KZ", 7, 25,
                "*.mail.ru", "info@westmed.kz", "zakup@westmed.kz");
    }

    @Test
    void pass_setsMailboxMarket_clearsAfter() {
        AtomicReference<Market> during = new AtomicReference<>();
        when(service.poll()).thenAnswer(inv -> { during.set(MarketContext.get()); return null; });

        new ClientMailScheduler(service, settings(true)).pass();

        assertThat(during.get()).isEqualTo(Market.KZ);
        assertThat(MarketContext.get()).isEqualTo(Market.RF);   // очищен: дефолт RF
    }

    @Test
    void pass_runtimeFailureClearsMarket() {
        when(service.poll()).thenThrow(new IllegalStateException("boom"));

        new ClientMailScheduler(service, settings(true)).pass();

        assertThat(MarketContext.get()).isEqualTo(Market.RF);
    }

    @Test
    void disabled_tickDoesNothing() {
        new ClientMailScheduler(service, settings(false)).tick();
        verifyNoInteractions(service);
    }
}
```

`ClientMailIntegrationTest.java` (пакет `mail`):

```java
package com.vladoose.nir.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientMailReceiveService;
import com.vladoose.nir.service.MailReceiveService;
import com.vladoose.nir.service.mail.ImapTestSupport;
import com.vladoose.nir.service.mail.TestMimes;
import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Ящик info@ сквозь IMAP: «Входящие» и «Отправленные» GreenMail, запись — в nirdb в транзакции теста (откат). */
@MailIntegrationTest
class ClientMailIntegrationTest {

    static final String BOX = "info@westmed.kz";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP).withPerMethodLifecycle(true);

    @Autowired ClientMailReceiveService service;
    @Autowired MailReceiveService zakupService;
    @Autowired MailCursorRepository cursorRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired InboundEmailRepository inboundRepository;
    @Autowired EntityManager em;

    GreenMailUser info;
    com.icegreen.greenmail.store.MailFolder sent;

    @BeforeEach
    void setUp() throws Exception {
        MarketContext.set(Market.KZ);
        info = greenMail.setUser(BOX, BOX, "secret");
        sent = greenMail.getManagers().getImapHostManager().createMailbox(info, "Sent");
        for (String f : List.of(MailCursor.INBOX, MailCursor.SENT)) {
            cursorRepository.findById(new MailCursorKey(BOX, f)).ifPresent(cursorRepository::delete);
        }
        cursorRepository.findById(MailCursorKey.inbox("zakup@westmed.kz")).ifPresent(cursorRepository::delete);
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static String unique() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    /** Письмо клиента: текст, Excel и PDF вложениями, картинка подписи (inline + Content-ID). */
    static MimeMessage clientLetter(String client) throws Exception {
        MimeMessage m = TestMimes.withAttachments("Айгуль Сапарова <" + client + ">", "Запрос цены",
                "Добрый день! Нужны кушетки 3 шт.", "смета.xlsx", TestMimes.xlsxBytes(), "ТЗ.pdf", new byte[]{4, 5, 6});
        MimeMultipart mixed = (MimeMultipart) m.getContent();
        MimeBodyPart logo = new MimeBodyPart();
        logo.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3}, "image/png")));
        logo.setContentID("<logo1>");
        logo.setDisposition(MimeBodyPart.INLINE);
        logo.setFileName("image001.png");
        mixed.addBodyPart(logo);
        m.setContent(mixed);
        m.setRecipients(Message.RecipientType.TO, BOX);
        m.saveChanges();
        return m;
    }

    @Test
    void clientLetter_becomesLead_ourReplyTakesIt_filesStored_lettersStayUnread() throws Exception {
        String client = unique();
        info.deliver(clientLetter(client));
        sent.appendMessage(TestMimes.letter("West-Med <info@westmed.kz>", client, "colleague@westmed.kz",
                "Re: Запрос цены", "Добрый день! КП во вложении.\n\n> Добрый день! Нужны кушетки 3 шт."), new Flags(), new Date());

        ClientMailReceiveService.Result r = service.poll();

        assertThat(r.stopped()).isNull();
        Chat chat = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, client).orElseThrow();
        List<ChatMessage> msgs = messageRepository.findLatest(chat.getId(), PageRequest.of(0, 10));
        assertThat(msgs).extracting(ChatMessage::getDirection).containsExactlyInAnyOrder(LeadDirection.IN, LeadDirection.OUT);
        ChatMessage in = msgs.stream().filter(m -> m.getDirection() == LeadDirection.IN).findFirst().orElseThrow();
        assertThat(in.getSubject()).isEqualTo("Запрос цены");
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(in.getId())))
                .extracting(ChatAttachmentMeta::fileName).containsExactlyInAnyOrder("смета.xlsx", "ТЗ.pdf");
        ChatMessage out = msgs.stream().filter(m -> m.getDirection() == LeadDirection.OUT).findFirst().orElseThrow();
        assertThat(out.getBody()).isEqualTo("Добрый день! КП во вложении.");
        Lead lead = leadRepository.findByChatIdIn(List.of(chat.getId())).get(0);
        assertThat(lead.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "colleague@westmed.kz")).isEmpty();
        assertThat(ImapTestSupport.seen(BOX, "INBOX", "Запрос цены")).isFalse();
        assertThat(ImapTestSupport.seen(BOX, "Sent", "Re: Запрос цены")).isFalse();
        assertThat(cursorRepository.findById(new MailCursorKey(BOX, MailCursor.INBOX))).isPresent();
        assertThat(cursorRepository.findById(new MailCursorKey(BOX, MailCursor.SENT))).isPresent();
    }

    @Test
    void newsletterServiceAndSiteNotification_doNotStartConversations() throws Exception {
        info.deliver(TestMimes.withHeader(TestMimes.plain("news@shop.kz", "Акция недели zz", "Скидки"),
                "List-Unsubscribe", "<mailto:u@shop.kz>"));
        info.deliver(TestMimes.plain("security@id.mail.ru", "Вход в аккаунт zz", "Новый вход"));
        info.deliver(TestMimes.plain("Сайт <info@westmed.kz>", "Новая заявка — westmed.kz", "Заявка №5"));
        long chatsBefore = chatRepository.count();

        service.poll();

        assertThat(chatRepository.count()).isEqualTo(chatsBefore);
        assertThat(inboundRepository.findAll()).filteredOn(e -> BOX.equals(e.getMailbox())
                        && Set.of("Акция недели zz", "Вход в аккаунт zz", "Новая заявка — westmed.kz").contains(e.getSubject()))
                .extracting(InboundEmail::getSubject).containsExactlyInAnyOrder("Акция недели zz", "Вход в аккаунт zz");
    }

    @Test
    void firstRunWindow_oldLetterIgnored_secondPollNoDuplicates() throws Exception {
        String client = unique();
        greenMail.getManagers().getImapHostManager().getInbox(info).appendMessage(
                TestMimes.plain("Старое <" + client + ">", "Письмо двухнедельной давности", "Давно"), new Flags(),
                Date.from(Instant.now().minus(Duration.ofDays(14))));
        info.deliver(TestMimes.plain("Новое <" + client + ">", "Свежее письмо", "Сейчас"));

        service.poll();
        service.poll();

        Chat chat = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, client).orElseThrow();
        assertThat(messageRepository.findLatest(chat.getId(), PageRequest.of(0, 10)))
                .extracting(ChatMessage::getSubject).containsExactly("Свежее письмо");
        assertThat(leadRepository.findByChatIdIn(List.of(chat.getId()))).hasSize(1);
    }

    @Test
    void zakupAndInfoCursorsAreIndependent() throws Exception {
        GreenMailUser zakup = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        zakup.deliver(TestMimes.plain("s@x.kz", "Прайс поставщика zz", "Текст"));
        String client = unique();
        info.deliver(TestMimes.plain("Клиент <" + client + ">", "Запрос клиента", "Текст"));

        zakupService.poll();
        assertThat(cursorRepository.findById(new MailCursorKey(BOX, MailCursor.INBOX))).isEmpty();

        service.poll();
        assertThat(cursorRepository.findById(MailCursorKey.inbox("zakup@westmed.kz"))).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, client)).isPresent();
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, BOX, "s@x.kz")).isEmpty();
    }
}
```

В `TestMimes` — помощники:

```java
    /** Письмо с «Кому» и «Копия» (адреса через запятую; cc — может быть null). */
    public static MimeMessage letter(String from, String to, String cc, String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(addr(from));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        if (cc != null) m.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        m.setSubject(subject, "UTF-8");
        m.setSentDate(new Date());
        m.setText(text, "UTF-8");
        m.saveChanges();
        return m;
    }

    /** То же письмо с дополнительным заголовком (рассылка, Precedence…). */
    public static MimeMessage withHeader(MimeMessage m, String name, String value) throws Exception {
        m.setHeader(name, value);
        m.saveChanges();
        return m;
    }
```

В `ImapTestSupport` — проверка любой папки любого ящика (прежний `hasFlag` переводится на неё):

```java
    /** «Прочитано» ли письмо с этой темой в папке ящика user (папка открывается только на чтение). */
    public static boolean seen(String user, String folder, String subject) throws Exception {
        return hasFlag(user, folder, subject, Flags.Flag.SEEN);
    }

    private static boolean hasFlag(String subject, Flags.Flag flag) throws Exception {
        return hasFlag(USER, "INBOX", subject, flag);
    }

    private static boolean hasFlag(String user, String folder, String subject, Flags.Flag flag) throws Exception {
        Store s = Session.getInstance(new Properties()).getStore("imap");
        s.connect(HOST, PORT, user, PASS);
        try {
            Folder f = s.getFolder(folder);
            f.open(Folder.READ_ONLY);
            for (Message m : f.getMessages()) {
                if (subject.equals(m.getSubject())) return m.isSet(flag);
            }
            throw new AssertionError("в папке " + folder + " ящика " + user + " нет письма «" + subject + "»");
        } finally {
            s.close();
        }
    }
```

`MailIntegrationTest` — свойства ящика info@ (общий набор всех почтовых тестов, планировщик не тикает):

```java
        "mail.clients.enabled=true",
        "mail.clients.host=127.0.0.1",
        "mail.clients.port=3143",
        "mail.clients.username=info@westmed.kz",
        "mail.clients.password=secret",
        "mail.clients.protocol=imap",
        "mail.clients.market=KZ",
        "mail.clients.initial-delay-ms=86400000",
```

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.ClientMailReceiveServiceTest' --tests 'com.vladoose.nir.service.ClientMailSchedulerTest' --tests 'com.vladoose.nir.mail.ClientMailIntegrationTest'`
Expected: FAIL — компиляция: `cannot find symbol ClientMailbox` / `ClientMailReceiveService` / `ClientMailScheduler`.

- [ ] **Step 3: Implement**

`service/mail/ClientMailbox.java`:

```java
package com.vladoose.nir.service.mail;

import jakarta.mail.MessagingException;
import org.springframework.stereotype.Component;

/**
 * IMAP-ящик писем клиентов (info@): только чтение, как zakup@, но с папкой «Отправленные» и файлами вложений до
 * предела. Отдельный компонент, а не второй MailboxConnector: тот один на приём zakup@.
 */
@Component
public class ClientMailbox {

    private final ImapMailboxConnector connector;

    public ClientMailbox(ClientMailSettings s) {
        this.connector = new ImapMailboxConnector(s.host(), s.port(), s.username(), s.password(), s.protocol(),
                ImapMailboxConnector.CONNECT_TIMEOUT_MS, ImapMailboxConnector.READ_TIMEOUT_MS, s.maxFileBytes());
    }

    public MailboxSession open(MailFolder folder) throws MessagingException {
        return connector.open(folder);
    }
}
```

`service/ClientMailReceiveService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import com.vladoose.nir.util.InfrastructureFailure;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Приём писем клиентов ящика info@ (спека 2026-10-13-info-mail-leads §2.1): только чтение; сначала «Входящие», потом
 * «Отправленные» — порядок значим: обращение заводит письмо клиента, наш ответ после него берёт его в работу. У каждой
 * папки свой курсор, сбои — как у zakup@ (MailFolderPass); остановка в одной папке останавливает проход.
 * НЕ @Transactional: запись — по письму в ClientMailWriter. Рынок ставит вызывающий (ClientMailScheduler / тест).
 */
@Service
public class ClientMailReceiveService {

    private static final Logger log = LoggerFactory.getLogger(ClientMailReceiveService.class);
    static final int MAX_PER_PASS = 100;

    /** Итог прохода: счётчики писем и причина остановки (null — проход дошёл до конца). */
    public record Result(boolean enabled, int conversations, int outgoing, int inbound, int skipped, int duplicates,
                         int broken, int createdLeads, String stopped) {
        static Result disabled() { return new Result(false, 0, 0, 0, 0, 0, 0, 0, null); }
    }

    private final ClientMailSettings settings;
    private final ClientMailbox mailbox;
    private final ClientMailWriter writer;
    private final MailCursorRepository cursorRepository;
    private final Clock clock;
    /** «Отправленных» нет — предупреждение один раз, а не каждую минуту. Поток приёма один — volatile не нужен. */
    private boolean sentMissingWarned;

    @Autowired
    public ClientMailReceiveService(ClientMailSettings settings, ClientMailbox mailbox, ClientMailWriter writer,
                                    MailCursorRepository cursorRepository) {
        this(settings, mailbox, writer, cursorRepository, Clock.systemUTC());
    }

    ClientMailReceiveService(ClientMailSettings settings, ClientMailbox mailbox, ClientMailWriter writer,
                             MailCursorRepository cursorRepository, Clock clock) {
        this.settings = settings;
        this.mailbox = mailbox;
        this.writer = writer;
        this.cursorRepository = cursorRepository;
        this.clock = clock;
    }

    public Result poll() {
        if (!settings.enabled()) return Result.disabled();
        Tally tally = new Tally();
        String stopped = null;
        for (MailFolder folder : List.of(MailFolder.INBOX, MailFolder.SENT)) {
            stopped = pass(folder, tally);
            if (stopped != null) break;
        }
        Result r = tally.result(stopped);
        if (stopped != null) {
            log.warn("Приём писем клиентов ({}): {} — проход остановлен, повтор следующим", settings.username(), stopped);
        } else if (r.conversations() + r.outgoing() + r.inbound() + r.broken() > 0) {
            log.info("Приём писем клиентов ({}): {}", settings.username(), summary(r));
        }
        return r;
    }

    /** null — папка пройдена (или «Отправленных» нет); иначе — почему проход остановлен. */
    private String pass(MailFolder folder, Tally tally) {
        String where = settings.username() + " «" + folder.label() + "»: ";
        try (MailboxSession s = mailbox.open(folder)) {
            return MailFolderPass.run(s, target(folder, tally), clock.instant().minus(Duration.ofDays(settings.sinceDays())),
                    MAX_PER_PASS, clock, log, where);
        } catch (FolderNotFoundException e) {
            if (folder != MailFolder.SENT) return "папка «" + folder.label() + "» не найдена";
            if (!sentMissingWarned) {
                sentMissingWarned = true;
                log.warn("{}папка не найдена — наши ответы клиентам не читаются и обращения «В работу» сами не переводят", where);
            }
            return null;
        } catch (Exception e) {
            if (InfrastructureFailure.test(e)) return MailFolderPass.dbDown(e);
            // текст почтового сервера — про ящик, его можно; у чужих исключений — только класс: в них бывают адреса и тексты писем
            String cls = e.getClass().getSimpleName();
            if (e instanceof MessagingException) {
                String text = e.getMessage() != null ? e.getMessage() : cls;
                log.warn("{}ошибка почты: {}", where, text);
                return "ошибка почты: " + text;
            }
            log.warn("{}внутренняя ошибка: {}", where, cls);
            return "внутренняя ошибка (" + cls + ")";
        }
    }

    private MailFolderPass.Target<ClientMailWriter.Result> target(MailFolder folder, Tally tally) {
        MailCursorKey key = new MailCursorKey(settings.username(), folder.cursorKey());
        return new MailFolderPass.Target<>() {
            @Override public Optional<MailCursor> cursor() { return cursorRepository.findById(key); }
            @Override public ClientMailWriter.Result write(ParsedMail m, long uidValidity) { return writer.write(m, folder, uidValidity); }
            @Override public void writeBroken(BrokenMail b, long uidValidity) { writer.writeBroken(b, folder, uidValidity); }
            @Override public void moveCursorTo(long uidValidity, long uid) { writer.moveCursorTo(folder, uidValidity, uid); }
            @Override public void written(ClientMailWriter.Result r) { tally.add(r); }
            @Override public void broken() { tally.broken++; }
        };
    }

    static String summary(Result r) {
        List<String> parts = new ArrayList<>();
        if (r.conversations() > 0) parts.add("в переписки — " + r.conversations());
        if (r.createdLeads() > 0) parts.add("новых обращений — " + r.createdLeads());
        if (r.outgoing() > 0) parts.add("наших ответов — " + r.outgoing());
        if (r.inbound() > 0) parts.add("во «Входящие» — " + r.inbound());
        if (r.broken() > 0) parts.add("не разобрано — " + r.broken());
        return String.join(", ", parts);
    }

    private static final class Tally {
        int conversations, outgoing, inbound, skipped, duplicates, broken, createdLeads;

        void add(ClientMailWriter.Result r) {
            switch (r.kind()) {
                case CONVERSATION -> conversations++;
                case OUTGOING -> outgoing++;
                case INBOUND -> inbound++;
                case SKIPPED -> skipped++;
                case DUPLICATE -> duplicates++;
            }
            if (r.createdLeadId() != null) createdLeads++;
        }

        Result result(String stopped) {
            return new Result(true, conversations, outgoing, inbound, skipped, duplicates, broken, createdLeads, stopped);
        }
    }
}
```

`service/ClientMailScheduler.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.service.mail.ClientMailSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Расписание приёма писем клиентов info@: проход — на СВОЁМ однопоточном экзекьюторе «mail-clients» (зависший IMAP не
 * держит ни общий scheduling-1, ни приём zakup@), проходы не идут параллельно. Рынок ящика — ЯВНО, в finally —
 * очистка (§6 CLAUDE.md). Ручного запуска нет: «Проверить почту» во «Входящих» — про zakup@ (спека §4).
 */
@Component
public class ClientMailScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClientMailScheduler.class);

    private final ClientMailReceiveService service;
    private final ClientMailSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-clients");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean(false);

    public ClientMailScheduler(ClientMailReceiveService service, ClientMailSettings settings) {
        this.service = service;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${mail.clients.poll-ms:60000}", initialDelayString = "${mail.clients.initial-delay-ms:30000}")
    public void tick() {
        if (!settings.enabled()) return;
        if (!queued.compareAndSet(false, true)) return;          // прошлый проход ещё идёт — тик пропускаем
        // execute, а не submit: Error прохода (OOM на вложении) дойдёт до обработчика потока, а не осядет в Future
        executor.execute(() -> {
            try {
                pass();
            } finally {
                queued.set(false);
            }
        });
    }

    /** Проход в потоке приёма; null — проход упал (в лог — только класс). */
    ClientMailReceiveService.Result pass() {
        MarketContext.set(settings.market());
        try {
            return service.poll();
        } catch (RuntimeException e) {
            log.warn("Проход приёма писем клиентов упал: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            MarketContext.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off; devMail остановлен): `./gradlew test --tests 'com.vladoose.nir.service.ClientMailReceiveServiceTest' --tests 'com.vladoose.nir.service.ClientMailSchedulerTest' --tests 'com.vladoose.nir.mail.*' --tests 'com.vladoose.nir.email.KpRoundTripTest'`
Expected: PASS (прежние почтовые интеграционные — тоже).

- [ ] **Step 5: Мутации** (по одной): переставить папки (`SENT`, затем `INBOX`) → `inboxThenSent_eachWithItsCursor` красный и в интеграционном обращение остаётся «Новым»; убрать `sentMissingWarned` → `…warnOnce` красный (две строки); в `ClientMailbox` передать `0` вместо `maxFileBytes` → интеграционный тест файлов красный.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service src/test/java/com/vladoose/nir
git commit -m "feat(mail): приём писем клиентов info@ — «Входящие» и «Отправленные», свой поток

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: «Чаты» в API — письма, файлы списком, фильтр «Почта», обращение из почтовой переписки

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/response/ChatMessageResponse.java`, `ChatResponse.java`, `ChatListItemResponse.java`
- Modify: `src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java`, `ChatMessageRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/ChatService.java`
- Test: `src/test/java/com/vladoose/nir/chat/ChatEmailApiTest.java` (+ прежний `ChatApiTest`)

**Interfaces:**
- Consumes: `EmailChatWriter`, `EmailLetter`, `MailFile` (задача 6), `ChatMessage.subject`, `ChatMessageType.EMAIL`.
- Produces: `ChatMessageResponse.subject`, `ChatMessageResponse.attachments` (`List<ChatAttachmentResponse>`) и `getAttachment()` (первый файл); `ChatResponse.channel/email`, `ChatListItemResponse.channel/email`; фильтр `GET /api/chats?filter=EMAIL`; поиск по адресу и теме письма; `ChatService.createLead` у почтовой переписки — обращение `EMAIL`.

- [ ] **Step 1: Write the failing test** — `ChatEmailApiTest.java`:

```java
package com.vladoose.nir.chat;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.mail.EmailLetter;
import com.vladoose.nir.service.mail.MailFile;
import com.vladoose.nir.service.mail.TestMimes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ChatEmailApiTest {

    static final String BOX = "info@westmed.kz";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired ChatController controller;
    @Autowired EmailChatWriter writer;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadService leadService;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@clinic.kz"; }

    private EmailChatWriter.Outcome in(String from, String subject, String body, MailFile... files) {
        clock = clock.plusMinutes(5);
        return writer.writeIncoming(BOX, new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject, body, clock,
                from, "Айгуль", List.of(files)));
    }

    @Test
    void emailFilter_searchByAddressAndSubject_listItemHasAddress() {
        String from = address();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        EmailChatWriter.Outcome o = in(from, "Кушетки " + tag, "Текст");

        List<ChatListItemResponse> email = controller.list("EMAIL", null);
        assertThat(email).extracting(ChatListItemResponse::getId).contains(o.chatId());
        assertThat(email).allSatisfy(c -> assertThat(c.getChannel()).isEqualTo("EMAIL"));
        assertThat(controller.list("ALL", from)).extracting(ChatListItemResponse::getId).containsExactly(o.chatId());
        assertThat(controller.list("ALL", "кушетки " + tag)).extracting(ChatListItemResponse::getId).containsExactly(o.chatId());
        ChatListItemResponse item = email.stream().filter(c -> c.getId().equals(o.chatId())).findFirst().orElseThrow();
        assertThat(item.getEmail()).isEqualTo(from);
        assertThat(item.getPhone()).isNull();
        assertThat(controller.get(o.chatId()).getEmail()).isEqualTo(from);
    }

    @Test
    void letterHasSubjectAndAllFilesInOrder_firstIsAlsoAttachment() {
        EmailChatWriter.Outcome o = in(address(), "Смета", "Во вложении",
                new MailFile("смета.xlsx", XLSX, 3L, new byte[]{1, 2, 3}),
                new MailFile("фото.png", "image/png", 4L, new byte[]{1, 2, 3, 4}),
                new MailFile("видео.mp4", "video/mp4", null, null));

        ChatMessageResponse m = controller.messages(o.chatId(), null, 10).get(0);

        assertThat(m.getType()).isEqualTo("EMAIL");
        assertThat(m.getSubject()).isEqualTo("Смета");
        assertThat(m.getAttachments()).extracting(ChatAttachmentResponse::getFileName)
                .containsExactly("смета.xlsx", "фото.png", "видео.mp4");
        assertThat(m.getAttachments().get(0).isExcel()).isTrue();
        assertThat(m.getAttachments().get(1).isImage()).isTrue();
        assertThat(m.getAttachments().get(2).isStored()).isFalse();
        assertThat(m.getAttachment().getFileName()).isEqualTo("смета.xlsx");
    }

    @Test
    void emailFile_servedThroughItsChat_notStoredSaysOpenInMail_otherMarket404() {
        EmailChatWriter.Outcome o = in(address(), "ТЗ", "Во вложении",
                new MailFile("ТЗ.pdf", "application/pdf", 2L, new byte[]{4, 5}),
                new MailFile("видео.mp4", "video/mp4", null, null));
        List<ChatAttachmentResponse> files = controller.messages(o.chatId(), null, 10).get(0).getAttachments();

        ResponseEntity<byte[]> pdf = controller.attachment(o.chatId(), files.get(0).getId());
        assertThat(pdf.getBody()).isEqualTo(new byte[]{4, 5});
        assertThatThrownBy(() -> controller.attachment(o.chatId(), files.get(1).getId()))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("откройте его в почте");
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.attachment(o.chatId(), files.get(0).getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void excelFromLetter_parsesIntoPreview() throws Exception {
        EmailChatWriter.Outcome o = in(address(), "Смета", "Во вложении", new MailFile("смета.xlsx", XLSX, 1L, TestMimes.xlsxBytes()));
        Long excelId = controller.messages(o.chatId(), null, 10).get(0).getAttachments().get(0).getId();

        ImportPreviewResponse p = controller.preview(o.chatId(), excelId);

        assertThat(p.getRows()).anySatisfy(row -> assertThat(row).contains("Аппарат УЗИ"));
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void createLeadOnEmailChat_isEmailLeadInWork() {
        String from = address();
        EmailChatWriter.Outcome o = in(from, "Запрос цены", "Нужны кушетки");
        leadService.close(o.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");

        ChatResponse r = controller.createLead(o.chatId());

        assertThat(r.isLeadOpen()).isTrue();
        Lead l = leadRepository.findById(r.getLead().id()).orElseThrow();
        assertThat(l.getChannel()).isEqualTo(LeadChannel.EMAIL);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getSubject()).isEqualTo("Запрос цены");
        assertThat(l.getContactEmail()).isEqualTo(from);
        assertThat(l.getContactName()).isEqualTo("Айгуль");
        assertThat(l.getPhoneNorm()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.chat.ChatEmailApiTest'`
Expected: FAIL — компиляция: `getAttachments()` / `getSubject()` / `getChannel()` / `getEmail()` не найдены.

- [ ] **Step 3: Implement**

`ChatMessageResponse.java` — целиком:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class ChatMessageResponse {
    private Long id;
    private String direction;
    private String senderName;
    private String type;
    /** Тема письма (вид EMAIL); у сообщений WhatsApp — null. */
    private String subject;
    private String body;
    private OffsetDateTime sentAt;
    private boolean edited;
    private boolean deleted;
    /** Файлы по порядку: у WhatsApp — не больше одного, у письма — все вложения. */
    private List<ChatAttachmentResponse> attachments = new ArrayList<>();

    /** Первый файл — прежнее поле ответа (экраны WhatsApp и их тесты читают его). */
    public ChatAttachmentResponse getAttachment() {
        return attachments.isEmpty() ? null : attachments.get(0);
    }
}
```

`ChatResponse.java` и `ChatListItemResponse.java` — после `title`:

```java
    /** WHATSAPP / EMAIL. */
    private String channel;
    /** Адрес собеседника почтовой переписки; у чатов WhatsApp — null. */
    private String email;
```

`ChatAttachmentRepository.findMetaByMessageIds` — порядок файлов:

```java
           from ChatAttachment a where a.message.id in :messageIds order by a.id""")
```

`ChatMessageRepository.findChatIdsByBody` — и по теме письма:

```java
    /** Чаты, где текст встречается в тексте или теме сообщений. Без рыночного фильтра — вызывающий пересекает с чатами своего рынка. */
    @Query("""
           select distinct m.chat.id from ChatMessage m
           where lower(m.body) like lower(concat('%', :q, '%')) or lower(m.subject) like lower(concat('%', :q, '%'))""")
    List<Long> findChatIdsByBody(@Param("q") String q);
```

`ChatService.java`:

```java
    private static final Set<String> FILTERS = Set.of("ALL", "WITH_LEAD", "WITHOUT_LEAD", "GROUPS", "EMAIL");
```

`attachment(...)` — текст для файла письма:

```java
        if (a.getContent() == null) {
            throw new NotFoundException(c.getChannel() == LeadChannel.EMAIL
                    ? "Файл не сохранён в АИС — откройте его в почте"
                    : "Файл не сохранён в АИС — смотрите его в телефоне");
        }
```

`createLead(...)` — после вычисления `start`:

```java
        ChatMessage lastIn = messageRepository.findLatestByDirection(c.getId(), LeadDirection.IN, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        String lastIncoming = lastIn == null ? null : lastIn.getBody();
        IncomingLead in = c.getChannel() == LeadChannel.EMAIL
                ? new IncomingLead(LeadSources.EMAIL, null, LeadChannel.EMAIL, emailSubject(lastIn), start,
                        contactName(c), null, c.getExternalChatId(), null, lastIncoming, List.of(), LeadStatus.IN_WORK, null, author)
                : new IncomingLead(LeadSources.WHATSAPP, null, LeadChannel.WHATSAPP, "WhatsApp", start,
                        contactName(c), c.getPhoneNorm(), null, null, lastIncoming, List.of(), LeadStatus.IN_WORK, null, author);
```

и помощники:

```java
    /** Тема обращения из почтовой переписки — тема последнего письма собеседника, иначе «Письмо». */
    private static String emailSubject(ChatMessage lastIn) {
        return lastIn != null && lastIn.getSubject() != null && !lastIn.getSubject().isBlank() ? lastIn.getSubject() : "Письмо";
    }

    /** Имя контакта для обращения — если название чата не просто номер или адрес. */
    private static String contactName(Chat c) {
        String t = c.getTitle();
        return t != null && !t.equals(c.getPhoneNorm()) && !t.equals(c.getExternalChatId()) ? t : null;
    }
```

`toResponses` — файлы группой на сообщение:

```java
        Map<Long, List<ChatAttachmentMeta>> files = chrono.isEmpty() ? Map.of()
                : attachmentRepository.findMetaByMessageIds(chrono.stream().map(ChatMessage::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ChatAttachmentMeta::messageId));
        return chrono.stream().map(m -> toMessage(m, files.getOrDefault(m.getId(), List.of()))).toList();
```

`toResponse` / `toListItem` — после `setTitle`:

```java
        r.setChannel(c.getChannel().name());
        r.setEmail(email(c));
```

`matches` — по адресу:

```java
    private static boolean matches(Chat c, String needle, String digits) {
        if (c.getTitle() != null && c.getTitle().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (c.getChannel() == LeadChannel.EMAIL && c.getExternalChatId().contains(needle)) return true;
        return digits.length() >= 4 && c.getPhoneNorm() != null && c.getPhoneNorm().contains(digits);
    }
```

`passes` — ветка `case "EMAIL" -> c.getChannel() == LeadChannel.EMAIL;`.

`toMessage` и новые помощники:

```java
    private static ChatMessageResponse toMessage(ChatMessage m, List<ChatAttachmentMeta> files) {
        ChatMessageResponse r = new ChatMessageResponse();
        r.setId(m.getId());
        r.setDirection(m.getDirection().name());
        r.setSenderName(m.getSenderName());
        r.setType(m.getType().name());
        r.setSubject(m.getSubject());
        r.setBody(m.getBody());
        r.setSentAt(m.getSentAt());
        r.setEdited(m.isEdited());
        r.setDeleted(m.isDeleted());
        r.setAttachments(files.stream().map(ChatService::toFile).toList());
        return r;
    }

    private static ChatAttachmentResponse toFile(ChatAttachmentMeta a) {
        ChatAttachmentResponse f = new ChatAttachmentResponse();
        f.setId(a.id());
        f.setFileName(a.fileName());
        f.setMimeType(a.mimeType());
        f.setSizeBytes(a.sizeBytes());
        f.setStored(a.stored());
        f.setNotStoredReason(a.notStoredReason() == null ? null : a.notStoredReason().name());
        f.setExcel(isExcel(a.fileName(), a.mimeType()));
        f.setImage(a.stored() && isSafeImage(a.mimeType()));
        return f;
    }

    /** Адрес собеседника почтовой переписки; у чатов WhatsApp — null. */
    private static String email(Chat c) {
        return c.getChannel() == LeadChannel.EMAIL ? c.getExternalChatId() : null;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.chat.*'`
Expected: PASS (WhatsApp `ChatApiTest` — через `getAttachment()` без изменений).

- [ ] **Step 5: Мутация** — `Collectors.groupingBy` обратно на `toMap` → `letterHasSubjectAndAllFilesInOrder…` красный (`IllegalStateException: Duplicate key`); вернуть копией.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/chat/ChatEmailApiTest.java
git commit -m "feat(chats): письма в API — тема, файлы списком, фильтр «Почта», обращение из почтовой переписки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: «Не клиент» в карточке обращения (API)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/entity/LeadCloseReason.java`, `service/LeadService.java`, `controller/LeadController.java`, `dto/response/LeadCardResponse.java`, `mapper/LeadResponseMapper.java`
- Test: `src/test/java/com/vladoose/nir/lead/LeadNotClientTest.java`

**Interfaces:**
- Consumes: `EmailChatWriter`, `EmailLetter` (задача 6).
- Produces: `LeadCloseReason.NOT_CLIENT`; `LeadService.markNotClient(Long id, String author) → Lead`; `POST /api/leads/{id}/not-client` (ADMIN) → `LeadCardResponse`; `LeadCardResponse.chatChannel` (`WHATSAPP` / `EMAIL` / null).

- [ ] **Step 1: Write the failing test** — `LeadNotClientTest.java`:

```java
package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.request.LeadCloseRequest;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.EmailChatWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.mail.EmailLetter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadNotClientTest {

    @Autowired LeadController controller;
    @Autowired EmailChatWriter writer;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatRepository chatRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;

    OffsetDateTime clock = OffsetDateTime.now().minusHours(2);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String address() { return "zz-" + UUID.randomUUID().toString().substring(0, 8) + "@supplier.cn"; }

    private EmailChatWriter.Outcome letterFrom(String from, String subject) {
        clock = clock.plusMinutes(5);
        return writer.writeIncoming("info@westmed.kz", new EmailLetter("<" + UUID.randomUUID() + "@x.kz>", subject,
                "Текст", clock, from, "Поставщик", List.of()));
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void notClient_closesLead_marksChat_nextLettersStartNoLead() {
        String from = address();
        EmailChatWriter.Outcome first = letterFrom(from, "Предложение о сотрудничестве");

        LeadCardResponse card = controller.notClient(first.createdLeadId());

        assertThat(card.getStatus()).isEqualTo("CLOSED");
        assertThat(card.getCloseReason()).isEqualTo("NOT_CLIENT");
        assertThat(card.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo("STATUS");
            assertThat(e.getBody()).contains("не клиент").contains(from);
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().isNotClient()).isTrue();

        EmailChatWriter.Outcome next = letterFrom(from, "Повторное предложение");
        assertThat(next.createdLeadId()).isNull();
        assertThat(leadRepository.findByChatIdIn(List.of(first.chatId()))).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void leadWithoutEmailChat_400_nothingChanges() {
        Lead phone = intake.ingest(new IncomingLead(LeadSources.MANUAL, null, LeadChannel.PHONE, "Звонок",
                OffsetDateTime.now(), "Иван", "+77011234567", null, null, "Нужен аппарат", List.of(),
                LeadStatus.IN_WORK, null, "admin")).orElseThrow();

        assertThatThrownBy(() -> controller.notClient(phone.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("из почты");
        assertThat(leadRepository.findById(phone.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closedLead_400_chatNotMarked() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        leadService.close(o.createdLeadId(), LeadCloseReason.SPAM, null, "admin");

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(BadRequestException.class);
        assertThat(chatRepository.findById(o.chatId()).orElseThrow().isNotClient()).isFalse();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operator_forbidden() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarket_404() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        MarketContext.set(Market.RF);

        assertThatThrownBy(() -> controller.notClient(o.createdLeadId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closeWithNotClientReason_400() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");
        LeadCloseRequest req = new LeadCloseRequest();
        req.setReason(LeadCloseReason.NOT_CLIENT);

        assertThatThrownBy(() -> controller.close(o.createdLeadId(), req)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cardShowsChatChannel() {
        EmailChatWriter.Outcome o = letterFrom(address(), "Запрос");

        assertThat(controller.get(o.createdLeadId()).getChatChannel()).isEqualTo("EMAIL");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.lead.LeadNotClientTest'`
Expected: FAIL — компиляция: `notClient` / `NOT_CLIENT` / `getChatChannel` не найдены.

- [ ] **Step 3: Implement**

`LeadCloseReason.java`:

```java
package com.vladoose.nir.entity;

/** NOT_CLIENT — «Не клиент» из почты: ставится только кнопкой, она же отмечает переписку (спека info-mail-leads §2.5). */
public enum LeadCloseReason { ANSWERED, SPAM, DUPLICATE, NOT_OUR_PROFILE, CLIENT_DECLINED, OTHER, NOT_CLIENT }
```

`LeadService.java` — в `close(...)` первой строкой после проверки null:

```java
        if (reason == LeadCloseReason.NOT_CLIENT) {
            throw new BadRequestException("«Не клиент» — отдельной кнопкой: она же отмечает переписку");
        }
```

новый метод:

```java
    /**
     * «Не клиент» (спека info-mail-leads §2.5): закрыть обращение причиной «Не клиент» и отметить его почтовую переписку —
     * письма с этого адреса ложатся в переписку, но обращений не заводят. Снять отметку — на «Чатах».
     */
    @Transactional
    public Lead markNotClient(Long id, String author) {
        Lead lead = get(id);
        Chat chat = lead.getChat();
        if (chat == null || chat.getChannel() != LeadChannel.EMAIL) {
            throw new BadRequestException("«Не клиент» — только у обращения из почты");
        }
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED), "закрыть");
        chat.setNotClient(true);
        lead.setCloseReason(LeadCloseReason.NOT_CLIENT);
        transition(lead, LeadStatus.CLOSED, author, closeReasonLabel(LeadCloseReason.NOT_CLIENT)
                + " — письма с " + chat.getExternalChatId() + " больше не заводят обращений");
        return lead;
    }
```

`closeReasonLabel` — ветка `case NOT_CLIENT -> "не клиент";`.

`LeadController.java`:

```java
    @PostMapping("/{id}/not-client")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse notClient(@PathVariable Long id) {
        return card(service.markNotClient(id, currentUser()));
    }
```

`LeadCardResponse.java`:

```java
    /** Чат обращения (WhatsApp или почта); null — к чату не привязано. */
    private Long chatId;
    /** Канал этого чата: WHATSAPP / EMAIL; null — чата нет. */
    private String chatChannel;
```

`LeadResponseMapper.toCard` — после `setChatId`:

```java
        r.setChatChannel(l.getChat() == null ? null : l.getChat().getChannel().name());
```

- [ ] **Step 4: Run tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.lead.*' --tests 'com.vladoose.nir.chat.*'`
Expected: PASS.

- [ ] **Step 5: Мутация** — перенести `chat.setNotClient(true)` до `require(...)` → `closedLead_400_chatNotMarked` красный (транзакция теста не откатывается до проверки — правка остаётся в контексте, §14); вернуть.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/lead/LeadNotClientTest.java
git commit -m "feat(leads): «Не клиент» у обращения из почты — закрыть и отметить переписку

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: «Входящие» — ящик письма и фильтр (API)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/InboundEmailRepository.java`, `controller/InboundController.java`, `dto/response/InboundEmailResponse.java`
- Test: `src/test/java/com/vladoose/nir/mail/InboundMailboxFilterTest.java`

**Interfaces:**
- Produces: `GET /api/inbound?mailbox=` (без параметра — оба ящика), `GET /api/inbound/mailboxes` → `List<String>` ящиков рынка; `InboundEmailResponse.mailbox`; `InboundController.list(String mailbox)`, `InboundController.mailboxes()`.

- [ ] **Step 1: Write the failing test** — `InboundMailboxFilterTest.java`:

```java
package com.vladoose.nir.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.InboundController;
import com.vladoose.nir.dto.response.InboundEmailResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InboundMailboxFilterTest {

    @Autowired InboundController controller;
    @Autowired InboundEmailRepository repository;

    @AfterEach void clear() { MarketContext.clear(); }

    private void row(Market market, String mailbox, String subject) {
        MarketContext.set(market);
        repository.save(InboundEmail.builder().mailbox(mailbox).fromAddress("a@x.kz").subject(subject)
                .receivedAt(OffsetDateTime.now().plusMinutes(5)).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
    }

    @Test
    void filterByMailbox_caseInsensitive_mailboxesOfOwnMarket() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String a = "zz-a-" + tag + "@test.kz", b = "zz-b-" + tag + "@test.kz", c = "zz-c-" + tag + "@test.kz";
        row(Market.KZ, a, "Письмо A");
        row(Market.KZ, b, "Письмо B");
        row(Market.RF, c, "Письмо C");
        MarketContext.set(Market.KZ);

        assertThat(controller.list(a.toUpperCase(Locale.ROOT))).extracting(InboundEmailResponse::getSubject).containsExactly("Письмо A");
        assertThat(controller.list(null)).extracting(InboundEmailResponse::getMailbox).contains(a, b).doesNotContain(c);
        assertThat(controller.mailboxes()).contains(a, b).doesNotContain(c);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.mail.InboundMailboxFilterTest'`
Expected: FAIL — компиляция: `list(String)` / `mailboxes()` / `getMailbox()`.

- [ ] **Step 3: Implement**

`InboundEmailRepository.java`:

```java
    /** Последние 300 писем ящика (адрес нижним регистром). */
    List<InboundEmail> findTop300ByMailboxOrderByReceivedAtDesc(String mailbox);

    /** Ящики, из которых есть письма рынка, — фильтр «Входящих». HQL: рыночный фильтр аспекта его режет. */
    @Query("select distinct e.mailbox from InboundEmail e where e.mailbox is not null order by e.mailbox")
    List<String> findMailboxes();
```

`InboundController.java` (импорт `java.util.Locale`):

```java
    /** Последние 300 писем рынка, или одного ящика (страница покажет, что список обрезан). */
    @GetMapping
    public List<InboundEmailResponse> list(@RequestParam(required = false) String mailbox) {
        List<InboundEmail> rows = mailbox == null || mailbox.isBlank()
                ? repository.findTop300ByOrderByReceivedAtDesc()
                : repository.findTop300ByMailboxOrderByReceivedAtDesc(mailbox.trim().toLowerCase(Locale.ROOT));
        return rows.stream().map(this::toResponse).toList();
    }

    /** Ящики «Входящих» рынка — чипы фильтра (zakup@, info@). */
    @GetMapping("/mailboxes")
    public List<String> mailboxes() {
        return repository.findMailboxes();
    }
```

и `toResponse` — `r.setMailbox(e.getMailbox());`; `InboundEmailResponse` — поле `private String mailbox;`.

- [ ] **Step 4: Run test to verify it passes**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.mail.InboundMailboxFilterTest' --tests 'com.vladoose.nir.mail.InboundEmailTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/mail/InboundMailboxFilterTest.java
git commit -m "feat(inbound): ящик письма и фильтр по ящику во «Входящих»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Фронт — письмо в переписке, «Чаты», карточка обращения, «Входящие», канал «Почта»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/shared/chat-message.component.ts`
- Modify: `frontend/src/app/pages/chats/chats.component.ts`
- Modify: `frontend/src/app/pages/leads/lead-card.component.ts`, `pages/leads/leads.component.ts`
- Modify: `frontend/src/app/pages/inbound/inbound.component.ts`

**Interfaces:**
- Consumes: `GET /api/chats?filter=EMAIL`, у сообщения `type: 'EMAIL'`, `subject`, `attachments[]`; у чата `channel`, `email`; у карточки обращения `chatChannel`; `POST /api/leads/{id}/not-client`; `GET /api/inbound?mailbox=`, `GET /api/inbound/mailboxes`, у строки `mailbox`.
- Produces: `ApiService.getInbound(mailbox?: string)`, `getInboundMailboxes()`, `markLeadNotClient(id)`.

- [ ] **Step 1: `api.service.ts`** — вместо `getInbound()`:

```ts
  /** mailbox — только письма этого ящика; пусто — оба. */
  getInbound(mailbox?: string): Observable<any[]> {
    const p: any = {};
    if (mailbox) p.mailbox = mailbox;
    return this.http.get<any[]>(`${this.base}/inbound`, { params: p });
  }
  getInboundMailboxes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/inbound/mailboxes`);
  }
```

после `reopenLead`:

```ts
  /** «Не клиент» (обращение из почты): закрыть и больше не заводить обращения из писем этого адреса. */
  markLeadNotClient(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${id}/not-client`, {});
  }
```

- [ ] **Step 2: `shared/chat-message.component.ts`** — целиком:

```ts
import { ChangeDetectorRef, Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, Output, ViewChild } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';
import { fullDateTime } from './relative-time';

const NOT_STORED: Record<string, string> = {
  TOO_LARGE: 'файл больше предела — смотрите в телефоне',
  GROUP: 'файлы групп не сохраняются — смотрите в телефоне',
  DOWNLOAD_FAILED: 'не удалось скачать — смотрите в телефоне',
};

/** Письмо длиннее — свёрнуто в ленте, целиком — «Показать полностью». */
const LONG_CHARS = 600;
const LONG_LINES = 10;

function isLongText(body: string | null | undefined): boolean {
  return !!body && (body.length > LONG_CHARS || body.split('\n').length > LONG_LINES);
}

/**
 * Сообщение чата пузырём (спека whatsapp-chats §9.2): входящие слева, исходящие справа. Письмо (вид EMAIL, спека
 * info-mail-leads §3) — тема, текст (длинный свёрнут) и все файлы списком: картинка — крупно в странице по нажатию,
 * остальное скачивается. Файлы — ТОЛЬКО через HttpClient blob: голый <img src="/api/…"> не несёт X-Market, и бэкенд
 * ответил бы 404 на чат KZ. Полный размер фото — оверлей в странице (window.open с blob на iOS режется).
 */
@Component({
  selector: 'app-chat-message',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="call" *ngIf="m.type === 'CALL'">
      <span class="call-line">{{ m.body }} · <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time></span>
    </div>
    <div class="row" *ngIf="m.type === 'EMAIL'" [class.out]="m.direction === 'OUT'">
      <div class="bubble" [class.out]="m.direction === 'OUT'">
        <div class="subj" *ngIf="m.subject">{{ m.subject }}</div>
        <div class="text" *ngIf="m.body" [class.clamped]="isLong && !expanded">{{ m.body }}</div>
        <button type="button" class="linklike more" *ngIf="isLong" [attr.aria-expanded]="expanded"
                (click)="expanded = !expanded">{{ expanded ? 'Свернуть' : 'Показать полностью' }}</button>
        <div class="file" *ngFor="let a of m.attachments || []">
          <ng-container *ngIf="a.stored; else gone">
            <button type="button" class="linklike" (click)="openFile(a)">📎 {{ a.fileName || 'файл' }}<span class="size" *ngIf="a.sizeBytes"> · {{ size(a.sizeBytes) }}</span></button>
            <button type="button" class="btn btn-line btn-sm" *ngIf="a.excel && canParse" (click)="parse.emit(a)">Разобрать в позиции</button>
          </ng-container>
          <ng-template #gone><span class="muted">📎 {{ a.fileName || 'файл' }} — {{ notStored(a.notStoredReason) }}</span></ng-template>
        </div>
        <div class="meta">письмо · <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time></div>
      </div>
    </div>
    <div class="row" *ngIf="m.type !== 'CALL' && m.type !== 'EMAIL'" [class.out]="m.direction === 'OUT'">
      <div class="bubble" [class.out]="m.direction === 'OUT'" [class.deleted]="m.deleted">
        <div class="author" *ngIf="showAuthor && m.direction === 'IN' && m.senderName">{{ m.senderName }}</div>
        <ng-container *ngIf="m.attachment as a">
          <button type="button" class="thumb" *ngIf="a.image" (click)="onThumb()" [attr.aria-label]="'Открыть ' + (a.fileName || 'фото')">
            <img *ngIf="imageUrl" [src]="imageUrl" [alt]="a.fileName || 'фото'" />
            <span *ngIf="!imageUrl" class="thumb-ph">{{ imageFailed ? 'фото не загрузилось — нажмите, чтобы повторить' : 'фото загружается…' }}</span>
          </button>
          <div class="file" *ngIf="a.stored && !a.image">
            <button type="button" class="linklike" (click)="download(a)">📎 {{ a.fileName || 'файл' }}<span class="size" *ngIf="a.sizeBytes"> · {{ size(a.sizeBytes) }}</span></button>
            <button type="button" class="btn btn-line btn-sm" *ngIf="a.excel && canParse" (click)="parse.emit(a)">Разобрать в позиции</button>
          </div>
          <div class="file muted" *ngIf="!a.stored">📎 {{ a.fileName || 'файл' }} — {{ notStored(a.notStoredReason) }}</div>
        </ng-container>
        <div class="text" *ngIf="m.body">{{ m.body }}</div>
        <div class="meta">
          <span *ngIf="m.edited">изменено · </span><span *ngIf="m.deleted">удалено отправителем · </span>
          <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time>
        </div>
      </div>
    </div>
    <!-- Esc ловит сам оверлей (он в фокусе) и дальше не пускает: иначе карточка обращения закрылась бы вместе с фото -->
    <div class="zoom" #zoomEl *ngIf="zoomed && imageUrl" tabindex="-1" (click)="zoomed = false"
         (keydown.escape)="closeZoom($event)" role="dialog" aria-modal="true" aria-label="Фото целиком">
      <img [src]="imageUrl" [alt]="zoomName || 'фото'" />
    </div>
  `,
  styles: [`
    .row { display: flex; margin: 4px 0; }
    .row.out { justify-content: flex-end; }
    .bubble { max-width: min(78%, 560px); background: var(--surface-2); border: 1px solid var(--border); border-radius: 12px; padding: 8px 10px; font-size: 14px; color: var(--text); }
    /* исходящие — подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit, §12 CLAUDE.md) */
    .bubble.out { background: color-mix(in srgb, var(--success) 8%, var(--surface)); border-color: color-mix(in srgb, var(--success) 35%, var(--border)); }
    .author { font-size: 12px; font-weight: 600; color: var(--accent); margin-bottom: 2px; }
    .subj { font-weight: 600; margin-bottom: 4px; overflow-wrap: anywhere; }
    .text { white-space: pre-line; overflow-wrap: anywhere; }
    /* #000 в mask-image — не цвет, а непрозрачность маски (затухание свёрнутого письма): тема на неё не влияет */
    .text.clamped { max-height: 12em; overflow: hidden; -webkit-mask-image: linear-gradient(180deg, #000 70%, transparent); mask-image: linear-gradient(180deg, #000 70%, transparent); }
    .more { margin: 2px 0 4px; font-size: 12px; }
    .bubble.deleted .text { text-decoration: line-through; color: var(--text-muted); }
    .meta { font-size: 11px; color: var(--text-muted); text-align: right; margin-top: 4px; }
    .thumb { display: block; padding: 0; border: none; background: none; cursor: zoom-in; margin-bottom: 4px; }
    .thumb img { display: block; max-width: 220px; max-height: 220px; border-radius: 8px; }
    .thumb-ph { display: inline-block; font-size: 12px; color: var(--text-muted); padding: 24px 12px; }
    .file { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; font-size: 13px; }
    .file.muted, .muted { color: var(--text-muted); }
    .linklike { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; text-align: left; overflow-wrap: anywhere; }
    .size { color: var(--text-muted); }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    /* вуаль — самое частое значение приложения; пятое не заводить (§16 CLAUDE.md) */
    .zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; cursor: zoom-out; outline: none; }
    .zoom img { max-width: 94vw; max-height: 90vh; border-radius: 8px; }
    /* звонок — служебная строка по центру, не пузырь (спека whatsapp-waha §8) */
    .call { display: flex; justify-content: center; margin: 6px 0; }
    .call-line { font-size: 12px; color: var(--text-muted); background: color-mix(in srgb, var(--text-muted) 15%, transparent);
                 border-radius: 999px; padding: 4px 12px; text-align: center; }
    @media (max-width: 900px) {
      .bubble { max-width: 88%; }
      .thumb img { max-width: 180px; }
    }
  `],
})
export class ChatMessageComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) m!: any;
  @Input({ required: true }) chatId!: number;
  @Input() showAuthor = false;
  @Input() canParse = false;
  @Output() parse = new EventEmitter<any>();

  imageUrl: string | null = null;
  imageFailed = false;
  zoomed = false;
  zoomName = '';
  isLong = false;
  expanded = false;
  private loadedFor: number | null = null;
  private imageSub?: Subscription;

  /** Оверлей появился — забираем фокус, чтобы Esc пришёл к нему, а не к карточке под ним. */
  @ViewChild('zoomEl') set zoomEl(el: ElementRef<HTMLElement> | undefined) { el?.nativeElement.focus(); }

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnChanges() {
    this.isLong = this.m?.type === 'EMAIL' && isLongText(this.m.body);
    if (this.m?.type === 'EMAIL') return;          // у письма картинки — по нажатию на файл, без миниатюр
    const a = this.m?.attachment;
    if (a?.image && this.loadedFor !== a.id) {
      this.loadedFor = a.id;
      this.zoomName = a.fileName || '';
      this.loadImage(a.id);
    }
  }

  /** Запрос отменяется вместе с видом: иначе ответ создал бы blob-адрес, который уже некому отозвать. */
  ngOnDestroy() {
    this.imageSub?.unsubscribe();
    this.revoke();
  }

  onThumb() {
    if (this.imageUrl) this.zoomed = true;
    else if (this.imageFailed && this.loadedFor !== null) this.loadImage(this.loadedFor);
  }

  closeZoom(ev: Event) {
    ev.stopPropagation();
    this.zoomed = false;
  }

  /** Файл письма: картинка — крупно в странице, остальное — скачать. */
  openFile(a: any) {
    if (!a.image) { this.download(a); return; }
    this.imageSub?.unsubscribe();
    this.imageSub = this.api.getChatAttachment(this.chatId, a.id).subscribe({
      next: blob => {
        this.revoke();
        this.imageUrl = URL.createObjectURL(blob);
        this.zoomName = a.fileName || '';
        this.zoomed = true;
        this.cdr.detectChanges();
      },
      error: () => this.notify.error(this.fileError()),
    });
  }

  private loadImage(attachmentId: number) {
    this.imageSub?.unsubscribe();
    this.imageFailed = false;
    this.imageSub = this.api.getChatAttachment(this.chatId, attachmentId).subscribe({
      next: blob => { this.revoke(); this.imageUrl = URL.createObjectURL(blob); this.cdr.detectChanges(); },
      error: () => { this.imageFailed = true; this.cdr.detectChanges(); },
    });
  }

  download(a: any) {
    this.api.getChatAttachment(this.chatId, a.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = a.fileName || 'file';
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
      },
      error: () => this.notify.error(this.fileError()),
    });
  }

  size(bytes: number): string {
    if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1).replace('.', ',') + ' МБ';
    if (bytes >= 1024) return Math.round(bytes / 1024) + ' КБ';
    return bytes + ' Б';
  }

  notStored(reason: string): string {
    if (this.m?.type === 'EMAIL') return reason === 'TOO_LARGE' ? 'больше предела — откройте в почте' : 'не сохранён — откройте в почте';
    return NOT_STORED[reason] || 'не сохранён — смотрите в телефоне';
  }

  full(iso: string) { return fullDateTime(iso); }

  /** Сегодня — «14:05», иначе «28.09 14:05». */
  time(iso: string): string {
    const t = new Date(iso);
    if (isNaN(t.getTime())) return '';
    const hm = t.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
    return t.toDateString() === new Date().toDateString()
      ? hm : t.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }) + ' ' + hm;
  }

  private fileError(): string {
    return this.m?.type === 'EMAIL' ? 'Файл не скачался — откройте его в почте' : 'Файл не скачался — смотрите его в телефоне';
  }

  private revoke() {
    if (this.imageUrl) {
      URL.revokeObjectURL(this.imageUrl);
      this.imageUrl = null;
    }
  }
}
```

- [ ] **Step 3: `pages/chats/chats.component.ts`**

Подзаголовок и строка состояния:

```html
        <p class="subtitle">Переписка рабочего номера WhatsApp и почты info@. Отвечаете с телефона или из почты — ответ появится здесь.</p>
      </div>
    </div>
    <app-whatsapp-status-line *ngIf="!emailView()" [status]="waStatus" [showMarketHint]="true"></app-whatsapp-status-line>
```

Поиск — `placeholder="Имя, номер, адрес, текст…"`. Метки строки списка:

```html
            <span class="cr-tags" *ngIf="c.group || c.notClient || c.lead || c.channel === 'EMAIL'">
              <span class="tag" *ngIf="c.channel === 'EMAIL'">Почта</span>
              <span class="tag" *ngIf="c.group">группа</span>
```

Шапка переписки — после строки телефона:

```html
            <span class="th-phone" *ngIf="chat.phone && chat.title">{{ phone(chat) }}</span>
            <a class="th-phone th-mail" *ngIf="chat.email && chat.title !== chat.email" [href]="'mailto:' + chat.email">{{ chat.email }}</a>
```

Стиль (перед `@media`): `.th-mail { color: var(--accent); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }`.

Фильтры:

```ts
  readonly filters = [
    { key: 'ALL', label: 'Все' },
    { key: 'WITH_LEAD', label: 'С обращением' },
    { key: 'WITHOUT_LEAD', label: 'Без обращения' },
    { key: 'GROUPS', label: 'Группы' },
    { key: 'EMAIL', label: 'Почта' },
  ];
```

Методы:

```ts
  /** Почтовая переписка или фильтр «Почта» — строка состояния WhatsApp и подсказка про рынок не к месту. */
  emailView(): boolean { return this.filter === 'EMAIL' || this.chat?.channel === 'EMAIL'; }

  title(c: any): string { return c?.title || (c?.phone ? formatPhone(c.phone) : (c?.email || 'Без имени')); }
```

- [ ] **Step 4: `pages/leads/lead-card.component.ts`**

В блок действий админа — после «Закрыть…»:

```html
                <button type="button" class="btn btn-line" *ngIf="lead.chatChannel === 'EMAIL' && lead.status !== 'CLOSED'"
                        [disabled]="busy" (click)="notClient()"
                        title="Закрыть обращение и больше не заводить обращения из писем этого адреса">Не клиент</button>
```

Ссылка на переписку:

```html
                <a class="btn btn-line btn-sm" *ngIf="lead.chatId" [routerLink]="['/chats']" [queryParams]="{ chatId: lead.chatId }">{{ lead.chatChannel === 'EMAIL' ? 'Открыть всю переписку' : 'Открыть весь чат' }}</a>
```

Метод:

```ts
  notClient() { this.apply(this.api.markLeadNotClient(this.lead.id), 'Закрыто: не клиент — письма с этого адреса больше не заводят обращений'); }
```

`pages/leads/leads.component.ts` — в выпадающем списке каналов после WhatsApp: `<option value="EMAIL">Почта</option>`.

- [ ] **Step 5: `pages/inbound/inbound.component.ts`**

Подзаголовок: `<p class="sub">Почта АИС: ответы поставщиков на запросы КП (zakup@), служебные письма и рассылки info@, возвраты и автоответы. Письма клиентов info@ — в «Обращениях» и «Чатах».</p>`.

Перед таблицей:

```html
    <div class="boxes" role="group" aria-label="Ящик" *ngIf="mailboxes.length > 1">
      <button type="button" class="chip" [class.on]="!mailbox" (click)="setMailbox('')">Все</button>
      <button type="button" class="chip" *ngFor="let b of mailboxes" [class.on]="mailbox === b" (click)="setMailbox(b)">{{ boxLabel(b) }}</button>
    </div>
```

Шапка и строка таблицы: `<tr><th>Отправитель</th><th>Тема</th><th>Получено</th><th>Ящик</th><th>Тип</th><th>Статус</th><th></th></tr>`; после ячейки «Получено» — `<td class="box" data-label="Ящик">{{ boxLabel(r.mailbox) }}</td>`; в ячейке «Тип» после метки КП — `<span class="muted box-ref" *ngIf="r.mailbox"> · {{ boxLabel(r.mailbox) }}</span>`.

Стили (до мобильного блока):

```css
    .boxes { display: flex; gap: 6px; flex-wrap: wrap; margin: 0 0 12px; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 4px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .box { white-space: nowrap; color: var(--text-muted); font-size: 12px; }
    /* ящик в ячейке «Тип» — только на телефоне, где колонки «Ящик» нет */
    .box-ref { display: none; }
```

в мобильный блок (`@media (max-width: 900px)`):

```css
      .inbound-list td[data-label="Ящик"] { display: none; }
      .inbound-list .box-ref { display: inline; white-space: nowrap; }
```

Код:

```ts
  mailboxes: string[] = [];
  mailbox = '';
  /** Номер последнего запроса списка: ответ по прежнему ящику не перетирает свежий. */
  private loadSeq = 0;
```

в конструкторе — `this.api.getInboundMailboxes().subscribe({ next: d => { this.mailboxes = d || []; this.cdr.detectChanges(); }, error: () => {} });`;

```ts
  load() {
    this.loading = true;
    const seq = ++this.loadSeq;
    this.api.getInbound(this.mailbox || undefined).subscribe({
      next: d => { if (seq !== this.loadSeq) return; this.rows = d || []; this.loading = false; this.cdr.detectChanges(); },
      error: () => { if (seq !== this.loadSeq) return; this.loading = false; this.cdr.detectChanges(); },
    });
  }

  setMailbox(b: string) { this.mailbox = b; this.load(); }

  /** «zakup@westmed.kz» → «zakup@». */
  boxLabel(b: string | null): string { return b ? b.split('@')[0] + '@' : '—'; }
```

- [ ] **Step 6: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка без ошибок; начальный бандл ниже 1,5 МБ (предупреждение «800 кБ» было и раньше).

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app
git commit -m "feat(ui): письма в переписке, «Почта» на «Чатах», «Не клиент» в обращении, ящик во «Входящих»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Стенд, раскатка, живая проверка, документация, полный прогон

**Files:**
- Modify: `src/test/java/com/vladoose/nir/mail/DevMailServer.java`, `scripts/dev-mail.py`
- Create: `deploy/set-info-mail.sh`
- Modify: `DEPLOY.md` (новый §10), `CLAUDE.md` (§5, §8, §9, §12, §13, §15, §16), `docs/PROGRESS.md` («▶ Последняя задача»)
- Память: `~/.claude/projects/-Users-vlad-IdeaProjects-AIS/memory/last-task-pointer.md`, `MEMORY.md`, `info-mail-telegram-chain.md`

- [ ] **Step 1: Локальная почта с ящиком info@** — `DevMailServer.main`:

```java
        GreenMail mail = new GreenMail(ServerSetupTest.SMTP_IMAP);
        mail.start();
        mail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        GreenMailUser info = mail.setUser("info@westmed.kz", "info@westmed.kz", "secret");
        mail.getManagers().getImapHostManager().createMailbox(info, "Sent");   // «Отправленные» ящика писем клиентов
        System.out.println("GreenMail: SMTP 127.0.0.1:3025, IMAP 127.0.0.1:3143, zakup@westmed.kz и info@westmed.kz / secret");
        Thread.currentThread().join();
```

(javadoc класса — оба ящика; импорт `com.icegreen.greenmail.user.GreenMailUser`.)

`scripts/dev-mail.py` — в docstring и разбор аргументов:

```
  python3 scripts/dev-mail.py client <адрес>        — письмо клиента на info@: текст, Excel и PDF, картинка подписи
  python3 scripts/dev-mail.py client-plain <адрес>  — продолжение переписки клиента (с цитатой)
  python3 scripts/dev-mail.py answer <адрес>        — наш ответ клиенту «из веб-почты»: в «Отправленные» info@ (IMAP APPEND)
  python3 scripts/dev-mail.py newsletter|service|site|colleague — рассылка / служебное / уведомление сайта / свой домен на info@
```

```python
import base64
import imaplib
import os
import subprocess
import tempfile

INFO = "info@westmed.kz"
# 1×1 PNG — картинка подписи (inline + Content-ID); контрольные суммы чанков сверены
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")


def xlsx_bytes():
    path = os.path.join(tempfile.mkdtemp(), "smeta.xlsx")
    subprocess.run(["node", os.path.join(os.path.dirname(__file__), "greenapi-stub.mjs"), "--write-xlsx", path], check=True)
    with open(path, "rb") as f:
        return f.read()


def client(address):
    m = EmailMessage()
    m["From"] = f"Айгуль Сапарова <{address}>"
    m["To"] = INFO
    m["Subject"] = "Запрос цены на кушетки"
    m.set_content("Добрый день!\nПросим КП на позиции из сметы во вложении.\n\nС уважением, Айгуль\nГКП «Областная больница»")
    m.add_alternative('<p>Добрый день!<br>Просим КП на позиции из сметы во вложении.</p>'
                      '<p>С уважением, Айгуль</p><img src="cid:logo">', subtype="html")
    m.get_payload()[1].add_related(PNG, maintype="image", subtype="png", cid="<logo>", filename="image001.png")
    m.add_attachment(xlsx_bytes(), maintype="application",
                     subtype="vnd.openxmlformats-officedocument.spreadsheetml.sheet", filename="Смета кушетки.xlsx")
    m.add_attachment(b"%PDF-1.4 stub", maintype="application", subtype="pdf", filename="ТЗ.pdf")
    send(m)


def client_plain(address):
    m = EmailMessage()
    m["From"] = f"Айгуль Сапарова <{address}>"
    m["To"] = INFO
    m["Subject"] = "Re: Запрос цены на кушетки"
    m.set_content("Уточните, пожалуйста, срок поставки.\n\n> Добрый день, Айгуль! КП подготовим сегодня.")
    send(m)


def answer(address):
    """Наш ответ клиенту из веб-почты: Mail.ru кладёт копию в «Отправленные» — здесь IMAP APPEND без даты (сервер ставит
    «сейчас»; дата в APPEND у GreenMail читается 12-часовым шаблоном и в 12:xx уехала бы на 12 часов назад)."""
    m = EmailMessage()
    m["From"] = f"West-Med <{INFO}>"
    m["To"] = address
    m["Cc"] = "manager@westmed.kz"
    m["Subject"] = "Re: Запрос цены на кушетки"
    m["Message-ID"] = make_msgid(domain="westmed.kz")
    m["Date"] = formatdate(localtime=True)
    m.set_content("Добрый день, Айгуль!\nКП подготовим сегодня.\n\nС уважением, West-Med\n\n> Добрый день! Просим КП…")
    imap = imaplib.IMAP4("127.0.0.1", 3143)
    imap.login(INFO, "secret")
    imap.append("Sent", None, None, m.as_bytes())
    imap.logout()
    print("в «Отправленные»:", m["Subject"])


def info_letter(sender, subject, text, **headers):
    m = EmailMessage()
    m["From"] = sender
    m["To"] = INFO
    m["Subject"] = subject
    for k, v in headers.items():
        m[k.replace("_", "-")] = v
    m.set_content(text)
    send(m)
```

и в `__main__`:

```python
    elif cmd in ("client", "client-plain", "answer") and len(sys.argv) > 2:
        {"client": client, "client-plain": client_plain, "answer": answer}[cmd](sys.argv[2])
    elif cmd == "newsletter":
        info_letter("Медтехника <news@medshop.kz>", "Скидки недели", "Скидки до 30%", List_Unsubscribe="<mailto:unsubscribe@medshop.kz>")
    elif cmd == "service":
        info_letter("Mail.ru <security@id.mail.ru>", "Вход в почту с нового устройства", "Выполнен вход в ящик")
    elif cmd == "site":
        info_letter(f"westmed.kz <{INFO}>", "Новая заявка с сайта — westmed.kz", "Заявка № 77")
    elif cmd == "colleague":
        info_letter("Бухгалтерия <buh@westmed.kz>", "Акт сверки", "Во вложении акт")
```

- [ ] **Step 2: Скрипт раскатки** — `deploy/set-info-mail.sh`:

```bash
#!/usr/bin/env bash
# Включение приёма писем клиентов info@westmed.kz → «Обращения» (DEPLOY.md §10). Запуск — с Mac, командой оператора:
#   scp -q deploy/set-info-mail.sh root@185.125.46.26:/root/ \
#     && ssh root@185.125.46.26 'bash /root/set-info-mail.sh; rm -f /root/set-info-mail.sh' < ~/.config/ais/info.pass
# Пароль приложения приходит со стандартного входа и не попадает ни на экран, ни в argv. Сначала вход по IMAP (curl,
# учётные данные — конфигом со стандартного входа) и признак папки «Отправленные»; не вошёл — .env не трогается.
# Потом копия .env.bak-…-info, ключи MAIL_CLIENTS_*, пересоздание бэкенда и строки лога о приёме.
set -u
BOX=info@westmed.kz
P=$(cat | tr -d '\r\n')
[ -n "$P" ] || { echo "пароль пуст — ничего не меняю"; exit 1; }
E=$(printf %s "$P" | sed 's/[\\"]/\\&/g')
L=$(printf 'url = "imaps://imap.mail.ru:993/"\nuser = "%s:%s"\n' "$BOX" "$E" | curl -sS -m 25 -K - 2>&1)
printf %s "$L" | grep -q 'INBOX' \
  || { echo "вход по IMAP не прошёл — ничего не меняю: $(printf %s "$L" | head -c 160)"; exit 1; }
echo "IMAP: вход в $BOX прошёл"
printf %s "$L" | grep -q '\\Sent' && echo "IMAP: «Отправленные» с признаком \\Sent есть" \
  || echo "IMAP: признака \\Sent нет — АИС поищет папку по имени («Отправленные» / Sent)"

cd /srv/ais || exit 1
B=.env.bak-$(date +%F-%H%M)-info
cp -p .env "$B" && echo "копия .env — $B"
umask 077
{ [ -z "$(tail -c1 .env)" ] || echo >> .env; }
set_env() { grep -v "^$1=" .env > .env.new; printf '%s=%s\n' "$1" "$2" >> .env.new; cat .env.new > .env; rm -f .env.new; }
set_env MAIL_CLIENTS_ENABLED true
set_env MAIL_CLIENTS_HOST imap.mail.ru
set_env MAIL_CLIENTS_PORT 993
set_env MAIL_CLIENTS_PROTOCOL imaps
set_env MAIL_CLIENTS_USERNAME "$BOX"
set_env MAIL_CLIENTS_MARKET KZ
set_env MAIL_CLIENTS_PASSWORD "$P"
H=$(printf %s "$P" | sha256sum | cut -c1-12)
[ "$(grep -c '^MAIL_CLIENTS_PASSWORD=' .env)" = 1 ] \
  && [ "$(grep '^MAIL_CLIENTS_PASSWORD=' .env | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-12)" = "$H" ] \
  && echo ".env: ключи MAIL_CLIENTS_* записаны" || { echo ".env не обновился — бэкенд не трогаю"; exit 1; }
docker compose up -d --force-recreate ais-backend </dev/null 2>&1 | grep -v -i 'variable is not set' | tail -2
for i in $(seq 1 60); do
  docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -q 'Started Nir2Application' && break
  sleep 3
done
docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -E 'Started Nir2Application|Приём писем клиентов' \
  | sed -E 's/^ais-backend-1 *\| //' | cut -c1-200
[ "$(docker exec ais-ais-backend-1 printenv MAIL_CLIENTS_ENABLED </dev/null)" = true ] \
  && echo "АИС: бэкенд запущен с приёмом info@" || echo "АИС: у бэкенда приём info@ НЕ включён — проверить"
unset P E L
```

`chmod +x deploy/set-info-mail.sh`; проверка синтаксиса: `bash -n deploy/set-info-mail.sh` (Expected: без вывода).

- [ ] **Step 3: DEPLOY.md §10** — новый раздел после §9:

````markdown
## 10. Письма клиентов на info@ → «Обращения»
Письмо клиента на info@westmed.kz становится обращением «Почта», переписка (письма клиента и наши ответы из «Отправленных») — в карточке обращения и на «Чатах»; служебное, рассылки и возвраты — во «Входящих» (фильтр «info@»). Спека — `docs/superpowers/specs/2026-10-13-info-mail-leads-design.md`, механика — CLAUDE.md §8 «Письма клиентов на info@». Код приезжает обычным деплоем (V29); пока в `.env` нет ключей ниже, приём выключен (`MAIL_CLIENTS_ENABLED=false`). Ящик АИС только читает (IMAP EXAMINE + `BODY.PEEK`): письма у людей остаются непрочитанными. Уведомления о письмах info@ в Telegram по-прежнему шлёт medsite — АИС их не шлёт.
**Статус:** не включено — код приезжает с выключенным приёмом; при раскатке сюда дописываются дата push, дата включения и итог приёмки.
- **Включение** (агент — только после явного «да» оператора на SSH; секреты на экран и в чат не выводить):
  1. **Оператор:** новый пароль приложения info@ (Mail.ru → Безопасность → «Пароли для внешних приложений», доступ IMAP; прежний светился в переписке) → `~/.config/ais/info.pass` (600): вставить в строку ввода `(umask 077; pbpaste | tr -d '[:space:]' > ~/.config/ais/info.pass)`, ПОТОМ скопировать пароль, и только тогда Enter.
  2. **Оператор:** `! git push origin main` — код с ВЫКЛЮЧЕННЫМ приёмом (V29). Агент проверяет, что бэкенд поднялся.
  3. **Оператор:** `! scp -q deploy/set-info-mail.sh root@185.125.46.26:/root/ && ssh root@185.125.46.26 'bash /root/set-info-mail.sh; rm -f /root/set-info-mail.sh' < ~/.config/ais/info.pass` — вход по IMAP, копия `.env.bak-…-info`, ключи `MAIL_CLIENTS_*`, пересоздание бэкенда.
  4. Первый проход — через ~30 с после старта: письма клиентов за 7 дней (`MAIL_CLIENTS_SINCE_DAYS`) станут обращениями, на которые мы уже ответили из почты — сразу «В работе». Отработанные закрыть «Ответили клиенту без заявки», шум — «Не клиент».
- **Приёмка:** (1) письмо с личной почты на info@ → обращение «Новое» с каналом «Почта» за минуту, письмо в почте осталось непрочитанным; (2) ответ из веб-почты info@ → «В работе», ответ виден в ленте карточки; (3) тестовое обращение закрыть «Не клиент» и снять отметку с переписки на «Чатах».
- **Проверка с сервера:**
  ```bash
  cd /srv/ais && docker compose logs --since 10m ais-backend | grep 'Приём писем клиентов' | tail -5
  docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -At' <<'SQL'
  select folder, uid_validity, last_uid, updated_at from mail_cursor where mailbox = 'info@westmed.kz';
  select status, count(*) from lead where channel = 'EMAIL' group by 1;
  SQL
  ```
- **Откат настройкой:** `MAIL_CLIENTS_ENABLED=false` → `docker compose up -d --force-recreate ais-backend`. Обращения и переписки остаются.
- ⚠️ **Повторное включение после паузы:** первые проходы догоняют всё, что пришло после курсора (по 100 писем за проход в каждой папке). Догонка не нужна — перед включением `delete from mail_cursor where mailbox = 'info@westmed.kz';` (первый запуск с окном 7 дней; записанное раньше отсеет повтор по Message-ID).
- ⚠️ **Кодом «просто откатить» нельзя:** старый бэкенд не прочитает сообщения вида `EMAIL` и причину `NOT_CLIENT` (`@Enumerated(STRING)` → 500 на «Чатах» и в карточке), а у письма бывает несколько файлов (старый код ждёт один — 500 в переписке). Порядок: (1) `MAIL_CLIENTS_ENABLED=false` + пересоздать бэкенд; (2) SQL:
  ```sql
  update lead set close_reason = 'OTHER' where close_reason = 'NOT_CLIENT';
  delete from chat where channel = 'EMAIL';   -- переписки info@: сообщения и файлы уходят каскадом, у обращений chat_id → NULL
  ```
  (3) откатить код (§3). Колонки V29 старому коду не мешают: курсор zakup@ — одна строка ящика, Flyway пропускает «будущую» миграцию.
- ⚠️ `MAIL_CLIENTS_MARKET` на ходу не менять; `MAIL_CLIENTS_USERNAME` ≠ `MAIL_IMAP_USERNAME` (иначе приём info@ выключается с ERROR при старте). Пароль с `$` или пробелом в `.env` без кавычек не пишется — у паролей приложений Mail.ru их нет.
- ⚠️ Письма, которые правила Mail.ru уносят из «Входящих» (и папку «Спам»), АИС не видит. Служебные адреса вне `MAIL_CLIENTS_SERVICE_DOMAINS` заведут обращение — «Не клиент» один раз на адрес. Письмо через сервис форм со служебного адреса (клиент — в Reply-To) уйдёт во «Входящие», а не в обращение.
````

- [ ] **Step 4: Живая проверка** (sandbox off; Playwright, 1280 и 390, обе темы, оператор)

Стек: `lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill` (если висит прошлый), `./gradlew devMail` (фон); бэкенд:

```bash
MAIL_CLIENTS_ENABLED=true MAIL_CLIENTS_HOST=127.0.0.1 MAIL_CLIENTS_PORT=3143 MAIL_CLIENTS_PROTOCOL=imap \
MAIL_CLIENTS_USERNAME=info@westmed.kz MAIL_CLIENTS_PASSWORD=secret MAIL_CLIENTS_MARKET=KZ \
MAIL_CLIENTS_POLL_MS=15000 MAIL_CLIENTS_INITIAL_DELAY_MS=3000 JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun
```

фронт — `npm --prefix frontend start`. Записать ДО проверки: `select max(id) from lead; select max(id) from chat; select max(id) from inbound_email; select max(id) from tender;`.

Сценарий:
1. `python3 scripts/dev-mail.py client zz-aigul@clinic-uralsk.kz` → за 15–20 с в «Обращениях» (KZ) «Новое» «Запрос цены на кушетки», канал «Почта»; карточка: контакт с `mailto:`, «Текст клиента», в ленте письмо — тема, текст, файлы «Смета кушетки.xlsx» и «ТЗ.pdf» (картинки подписи нет), «Открыть всю переписку».
2. `python3 scripts/dev-mail.py answer zz-aigul@clinic-uralsk.kz` → «В работе», в ленте — наше письмо справа, событие «… ответ клиенту письмом из почты»; переписки с manager@westmed.kz нет.
3. В карточке — «Разобрать в позиции» у Excel → позиции → «Создать заявку» → страница заявки.
4. `client-plain` тем же адресом → письмо в той же переписке, нового обращения нет.
5. `newsletter`, `service`, `site`, `colleague` → во «Входящих» чипы «Все / zakup@ / info@», с «info@» — «Скидки недели», «Вход в почту…», «Акт сверки» («Прочее»), уведомления сайта нет; на 390 — ящик в строке типа.
6. `client zz-supplier@factory.cn` → обращение → «Не клиент» → закрыто, на «Чатах» у переписки «не клиент»; ещё `client-plain zz-supplier@factory.cn` → письмо в переписке, нового обращения нет; снять отметку на «Чатах».
7. «Чаты»: фильтр «Почта» (только почтовые), метка «Почта», адрес в шапке ссылкой `mailto:`, строки состояния WhatsApp нет; поиск по адресу и по теме; длинное письмо свёрнуто, «Показать полностью»; файл-картинка открывается крупно, PDF скачивается.
8. Оператор: «Не клиент», «Разобрать в позиции», «Создать обращение» не видны.
9. Письма не прочитаны: `python3 -c "import imaplib;M=imaplib.IMAP4('127.0.0.1',3143);M.login('info@westmed.kz','secret');M.select('INBOX',readonly=True);print(M.search(None,'UNSEEN'))"` — все UID писем.

- [ ] **Step 5: Уборка (только dev)** — сначала заявку проверки удалить в UI («⋯ → Удалить заявку»), затем ОСТАНОВИТЬ бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), потом SQL (`PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -h localhost -d nirdb`):

```sql
delete from lead where id > :max_lead;
delete from chat where channel = 'EMAIL' and account = 'info@westmed.kz';
delete from inbound_email where mailbox = 'info@westmed.kz';
delete from mail_cursor where mailbox = 'info@westmed.kz';
```

(`:max_lead` — записанное значение.) Потом devMail (`lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`) и фронт (4200).

- [ ] **Step 6: Документация**

- **CLAUDE.md §5** — подраздел «Почта info@ → «Обращения» — локально»: devMail держит оба ящика (info@ с папкой «Sent»), команда бэкенда из шага 4, команды `dev-mail.py` для info@, уборка из шага 5.
- **CLAUDE.md §8** — блок «Письма клиентов на info@ → «Обращения» (2026-10-13, ветка `feature/info-mail-leads`, V29)»: второй ящик и поток `mail-clients`, две папки и курсоры, правила §2.2 (`ClientMailRouter`), переписка `channel = EMAIL` (`EmailChatWriter`), правила обращений и «написано не раньше обращения», «Не клиент», файлы письма (несколько, предел), «Входящие» с ящиком, Telegram не шлёт; решения при планировании 1–14; что проверено (тесты, мутации, живьём).
- **CLAUDE.md §9** — абзац: у приёма почты два ящика; zakup@ — как было, info@ — `ClientMailReceiveService`, общий проход `MailFolderPass`, курсор «ящик + папка».
- **CLAUDE.md §12** — фронт: письмо в `chat-message` (тема, свёртка, файлы списком), «Почта» на «Чатах», «Не клиент» в карточке, чипы ящиков во «Входящих».
- **CLAUDE.md §13** — число тестов полного прогона.
- **CLAUDE.md §15** — `GET /api/chats?filter=EMAIL`, `subject`/`attachments`/`channel`/`email` в ответах чатов, `POST /api/leads/{id}/not-client`, `chatChannel` карточки, `GET /api/inbound?mailbox=`, `GET /api/inbound/mailboxes`.
- **CLAUDE.md §16** — пункт блока: что в проде / ждёт оператора (пароль, push, скрипт, приёмка), известные ограничения спеки §9, что не делалось (отправка писем из АИС, слияние адресов, папка «Спам»); в списке миграций — «V29 — письма клиентов info@ → следующая свободная V30».
- **DEPLOY.md** — §10 (шаг 3).
- **docs/PROGRESS.md** — «▶ Последняя задача»: блок сделан, что в проде, что ждёт оператора.
- **Память:** `last-task-pointer.md` (текущая тема), строка в `MEMORY.md`; `info-mail-telegram-chain.md` — АИС теперь читает info@ (без Telegram).

- [ ] **Step 7: Полный прогон**

Run (sandbox off; devMail остановлен): `./gradlew cleanTest test > .superpowers/sdd/2026-10-13-info-mail-leads/fullgate.log 2>&1`, затем подсчёт по `build/test-results/test/*.xml`.
Expected: 0 failures, 0 errors; skipped — 1 (калибровка `PrivateRequestRegistryCalibrationTest`).

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/vladoose/nir/mail/DevMailServer.java scripts/dev-mail.py deploy/set-info-mail.sh DEPLOY.md CLAUDE.md docs/PROGRESS.md
git commit -m "docs: письма клиентов на info@ → «Обращения» — стенд, раскатка, документация

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 9: Финал** — ревью всей ветки (Opus) → одна волна исправлений (тест, красный до правки) → полный прогон → мерж в `main` fast-forward. Push — оператор (`! git push origin main`), затем раскатка по DEPLOY.md §10.
