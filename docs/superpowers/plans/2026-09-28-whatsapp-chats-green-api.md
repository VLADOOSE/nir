# Чаты WhatsApp рабочего номера через Green-API — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Все чаты одного рабочего номера WhatsApp видны в АИС (экран «Чаты», только чтение), а обращения автоматически появляются и ведутся поверх чатов; файлы хранятся, Excel разбирается в позиции обращения.

**Architecture:** Свой поток `whatsapp-chats` опрашивает очередь Green-API (`receiveNotification` → запись → `deleteNotification`), сеть и файлы — вне транзакции, запись — в `@Transactional`-бине `ChatIngestWriter` (чат, сообщение, файл, правила обращений). Сообщения живут один раз — в `chat_message`; обращение ссылается на чат (`lead.chat_id`), карточка обращения показывает переписку его чата. Экран «Чаты» и карточка грузят файлы только через `HttpClient` (заголовок `X-Market`).

**Tech Stack:** Java 17, Spring Boot 3.5.6, Hibernate 6, Flyway (V21), `java.net.http`, Jackson, Apache POI (разбор Excel — существующий `LineExtractor`), JUnit 5 + AssertJ на nirdb; Angular 21 standalone; Node 23 (dev-стаб Green-API).

**Spec:** `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md` — читать вместе с планом.

## Global Constraints

- Java **17**: `instanceof`-шаблоны можно, **pattern matching в `switch` — нельзя** (только с Java 21).
- Схема — **только новой миграцией `V21__whatsapp_chats.sql`**; V1–V20 не трогать (CLAUDE.md §10).
- `Chat` — рыночная сущность: `@Filter(name = "marketFilter", condition = "market = :market")` + `@EntityListeners(MarketStampingListener.class)` + `implements MarketScoped`. **`@FilterDef` НЕ объявлять** (он один — на `Tender`). `findById` обходит фильтр → явный гард рынка, как `LeadService.get`.
- Фоновый поток ставит `MarketContext.set(<рынок из конфига>)` сам и чистит в `finally`; БД — только в `@Transactional`-методах отдельного бина; сеть и скачивание файлов — **вне** транзакций (CLAUDE.md §6).
- ⚠️ Токен Green-API стоит **в пути URL** → URL вызовов не попадают ни в лог, ни в `getMessage()` исключений; тексты ошибок собираются вручную. Закреплено тестом.
- `DataIntegrityViolationException` целиком **не глотать** (очередь читает один поток, дубль проверяется явно).
- Уведомление удаляется из очереди **только после** успешной записи; 3-я неудача подряд → удалить + предупреждение `MESSAGE_DROPPED`.
- Файлы: предел `chats.whatsapp.max-file-mb` (25); группы — без скачивания (`GROUP`); inline отдаются только `image/jpeg|png|webp|gif`, остальное — `attachment` + `application/octet-stream`; всегда `X-Content-Type-Options: nosniff`. Инвариант: `content == null ⇔ not_stored_reason != null`.
- Фронт: только токены (ни одного хекса), `@media` — последним блоком `styles`, мобильный ≤900px, тач-таргеты 40px, `cdr.detectChanges()` после async, **без нативного `await`** в экранах, файлы/картинки — только `HttpClient` blob (иначе нет `X-Market` → 404).
- Тесты бэкенда — `@SpringBootTest @Transactional` на nirdb; запуск `./gradlew …` — **с `dangerouslyDisableSandbox: true`** (песочница режет localhost:5432). Гейт — `./gradlew cleanTest test` (0 падений) и `cd frontend && npm run build`.
- Коммиты — на ветке `feature/whatsapp-chats`, каждый заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Git и gradlew — из корня репозитория (`cd /Users/vlad/IdeaProjects/AIS && …`, урок §14 про персистентный cwd).

## Карта файлов

| Файл | Задача | Ответственность |
|---|---|---|
| `src/main/resources/db/migration/V21__whatsapp_chats.sql` | 1 | таблицы `chat`, `chat_message`, `chat_attachment`, `lead.chat_id` |
| `entity/Chat.java`, `ChatMessage.java`, `ChatAttachment.java`, `ChatMessageType.java`, `AttachmentNotStoredReason.java` | 1 | модель |
| `entity/Lead.java` | 1 | поле `chat` |
| `repository/ChatRepository.java`, `ChatMessageRepository.java`, `ChatAttachmentRepository.java`, `LeadRepository.java` | 1 | выборки |
| `dto/response/ChatAttachmentMeta.java` | 1 | метаданные файла без байтов |
| `integration/greenapi/GreenApiClient.java`, `GreenApiHttpClient.java`, `GreenApiReceived.java`, `GreenApiSettings.java`, `GreenApiException.java`, `GreenApiAuthException.java`, `GreenApiQuotaException.java`, `FileTooLargeException.java` | 2 | HTTP к Green-API |
| `integration/greenapi/ChatKind.java`, `FileRef.java`, `ParsedNotification.java`, `GreenApiNotificationParser.java` | 3 | уведомление → доменная запись |
| `util/SiteCartMessageParser.java` | 4 | шаблон корзины westmed.kz → позиции |
| `integration/lead/LeadSources.java`, `service/LeadIntakeService.java`, `service/LeadService.java` | 5 | источник `whatsapp`, подпись ленты, авто-«В работу» |
| `integration/greenapi/IncomingFile.java`, `service/ChatLeadRules.java`, `service/ChatIngestWriter.java` | 5 | запись чата/сообщения/файла + правила обращений |
| `integration/greenapi/WhatsappStatusHolder.java`, `WhatsappChatSync.java`, `WhatsappChatScheduler.java`, `dto/response/WhatsappStatusResponse.java`, `application.yaml` | 6 | цикл приёма, паузы, состояние |
| `dto/response/ChatListItemResponse.java`, `ChatResponse.java`, `ChatMessageResponse.java`, `ChatAttachmentResponse.java`, `ChatLeadRef.java`, `dto/request/ChatNotClientRequest.java`, `service/ChatService.java`, `controller/ChatController.java` | 7 | REST `/api/chats` |
| `dto/response/LeadCardResponse.java`, `mapper/LeadResponseMapper.java`, `dto/request/LeadItemsImportRequest.java`, `service/PrivateRequestImportService.java`, `service/LeadService.java`, `service/ChatService.java`, `controller/LeadController.java` | 8 | обращение ↔ чат, Excel → позиции |
| `scripts/greenapi-stub.mjs` | 9 | dev-стаб Green-API для живых проверок |
| `frontend/src/app/shared/import-lines.ts`, `import-grid.component.ts`, `pages/inbound/…`, `pages/private-requests/…` | 10 | один грид разбора Excel |
| `frontend/src/app/services/api.service.ts`, `shared/whatsapp-status.ts`, `shared/whatsapp-status-line.component.ts`, `shared/chat-message.component.ts`, `shared/lead-items-import.component.ts`, `pages/chats/chats.component.ts`, `app.routes.ts`, `app.config.ts`, `layout/layout.component.ts` | 11 | экран «Чаты» |
| `pages/leads/lead-card.component.ts`, `pages/leads/leads.component.ts` | 12 | переписка в карточке, строка WhatsApp на «Обращениях» |
| `CLAUDE.md`, `DEPLOY.md`, `docs/PROGRESS.md` | 13 | документация |

Пути Java ниже — относительно `src/main/java/com/vladoose/nir/` (тесты — `src/test/java/com/vladoose/nir/`).

---

### Task 1: Модель данных — V21, сущности, репозитории

**Files:**
- Create: `src/main/resources/db/migration/V21__whatsapp_chats.sql`
- Create: `entity/Chat.java`, `entity/ChatMessage.java`, `entity/ChatAttachment.java`, `entity/ChatMessageType.java`, `entity/AttachmentNotStoredReason.java`
- Modify: `entity/Lead.java` (поле `chat` после `privateRequest`)
- Create: `repository/ChatRepository.java`, `repository/ChatMessageRepository.java`, `repository/ChatAttachmentRepository.java`, `dto/response/ChatAttachmentMeta.java`
- Modify: `repository/LeadRepository.java`
- Test: `chat/ChatPersistenceTest.java`

**Interfaces:**
- Produces:
  - `Chat` (Lombok `@Builder`): `id, market, channel (LeadChannel), account, externalChatId, group (boolean → isGroup()), title, phoneNorm, notClient (boolean → isNotClient()), lastMessageAt, lastMessagePreview, createdAt`
  - `ChatMessage`: `id, chat, externalId, direction (LeadDirection), senderName, type (ChatMessageType), body, sentAt, edited, deleted, createdAt`
  - `ChatAttachment`: `id, message, fileName, mimeType, sizeBytes (Long), content (byte[]), notStoredReason`
  - `enum ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER, LOCATION, CONTACT, OTHER }`
  - `enum AttachmentNotStoredReason { TOO_LARGE, GROUP, DOWNLOAD_FAILED }`
  - `ChatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel, String, String) : Optional<Chat>`, `findRecent(Pageable) : List<Chat>`
  - `ChatMessageRepository`: `existsByChatIdAndExternalId`, `findByChatIdAndExternalId`, `findLatest(chatId, Pageable)`, `findBefore(chatId, sentAt, id, Pageable)`, `findSince(chatId, since, Pageable)`, `findEarliestAfter(chatId, after, Pageable)`, `findLatestByDirection(chatId, LeadDirection, Pageable)`, `findChatIdsByBody(String) : List<Long>`
  - `ChatAttachmentRepository.findMetaByMessageIds(Collection<Long>) : List<ChatAttachmentMeta>`
  - `record ChatAttachmentMeta(Long id, Long messageId, String fileName, String mimeType, Long sizeBytes, AttachmentNotStoredReason notStoredReason)` + `boolean stored()`
  - `LeadRepository`: `findByChatIdAndStatusIn(Long, Collection<LeadStatus>)`, `findByPhoneNormAndStatusIn(String, Collection<LeadStatus>)`, `findByChatIdIn(Collection<Long>)`
  - `Lead.getChat()/setChat(Chat)`, builder `.chat(…)`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/chat/ChatPersistenceTest.java`:

```java
package com.vladoose.nir.chat;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ChatPersistenceTest {

    static final String ACCOUNT = "77000000001";

    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired EntityManager em;

    @AfterEach void clear() { MarketContext.clear(); }

    static String chatId() {
        return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
    }

    private Chat chat(String ext) {
        return chatRepository.saveAndFlush(Chat.builder().channel(LeadChannel.WHATSAPP).account(ACCOUNT)
                .externalChatId(ext).title("Айгерим").build());
    }

    private ChatMessage message(Chat c, String ext, OffsetDateTime at) {
        return messageRepository.saveAndFlush(ChatMessage.builder().chat(c).externalId(ext)
                .direction(LeadDirection.IN).type(ChatMessageType.TEXT).body("Здравствуйте").sentAt(at).build());
    }

    @Test
    void chatIsStampedWithMarketAndInvisibleFromOtherMarket() {
        MarketContext.set(Market.KZ);
        String ext = chatId();
        chat(ext);
        em.clear();

        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, ACCOUNT, ext))
                .get().satisfies(c -> {
                    assertThat(c.getMarket()).isEqualTo(Market.KZ);
                    assertThat(c.getCreatedAt()).isNotNull();
                    assertThat(c.isGroup()).isFalse();
                    assertThat(c.isNotClient()).isFalse();
                });
        MarketContext.set(Market.RF);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, ACCOUNT, ext)).isEmpty();
    }

    @Test
    void messageIdIsUniqueWithinChat() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        message(c, "MSG-1", OffsetDateTime.now());

        assertThatThrownBy(() -> message(c, "MSG-1", OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pagesGoNewestFirstAndKeysetContinues() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        OffsetDateTime t = OffsetDateTime.now().minusHours(1);
        ChatMessage m1 = message(c, "A", t);
        ChatMessage m2 = message(c, "B", t.plusMinutes(1));
        ChatMessage m3 = message(c, "C", t.plusMinutes(2));

        assertThat(messageRepository.findLatest(c.getId(), PageRequest.of(0, 2)))
                .extracting(ChatMessage::getId).containsExactly(m3.getId(), m2.getId());
        assertThat(messageRepository.findBefore(c.getId(), m2.getSentAt(), m2.getId(), PageRequest.of(0, 2)))
                .extracting(ChatMessage::getId).containsExactly(m1.getId());
        assertThat(messageRepository.findSince(c.getId(), m2.getSentAt(), PageRequest.of(0, 10)))
                .extracting(ChatMessage::getId).containsExactly(m3.getId(), m2.getId());
        assertThat(messageRepository.findEarliestAfter(c.getId(), m1.getSentAt(), PageRequest.of(0, 1)))
                .extracting(ChatMessage::getId).containsExactly(m2.getId());
        assertThat(messageRepository.findLatestByDirection(c.getId(), LeadDirection.IN, PageRequest.of(0, 1)))
                .extracting(ChatMessage::getId).containsExactly(m3.getId());
    }

    @Test
    void chatIdsAreFoundByMessageText() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        messageRepository.saveAndFlush(ChatMessage.builder().chat(c).externalId("T1").direction(LeadDirection.IN)
                .type(ChatMessageType.TEXT).body("Нужен Облучатель-" + c.getExternalChatId()).sentAt(OffsetDateTime.now()).build());

        assertThat(messageRepository.findChatIdsByBody("облучатель-" + c.getExternalChatId())).containsExactly(c.getId());
    }

    @Test
    void attachmentMetaIsReadWithoutContent() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        ChatMessage stored = message(c, "F1", OffsetDateTime.now());
        ChatMessage skipped = message(c, "F2", OffsetDateTime.now());
        attachmentRepository.saveAndFlush(ChatAttachment.builder().message(stored).fileName("ТЗ.pdf")
                .mimeType("application/pdf").sizeBytes(3L).content(new byte[]{1, 2, 3}).build());
        attachmentRepository.saveAndFlush(ChatAttachment.builder().message(skipped).fileName("видео.mp4")
                .mimeType("video/mp4").notStoredReason(AttachmentNotStoredReason.TOO_LARGE).build());

        List<ChatAttachmentMeta> meta = attachmentRepository.findMetaByMessageIds(List.of(stored.getId(), skipped.getId()));

        assertThat(meta).extracting(ChatAttachmentMeta::messageId, ChatAttachmentMeta::fileName, ChatAttachmentMeta::stored)
                .containsExactlyInAnyOrder(tuple(stored.getId(), "ТЗ.pdf", true), tuple(skipped.getId(), "видео.mp4", false));
    }

    @Test
    void leadKeepsItsChat() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        Lead l = leadRepository.saveAndFlush(Lead.builder().channel(LeadChannel.WHATSAPP).source("whatsapp")
                .subject("WhatsApp").status(LeadStatus.NEW).receivedAt(OffsetDateTime.now()).chat(c).build());
        em.clear();

        assertThat(leadRepository.findById(l.getId()).orElseThrow().getChat().getId()).isEqualTo(c.getId());
        assertThat(leadRepository.findByChatIdIn(List.of(c.getId()))).extracting(Lead::getId).containsExactly(l.getId());
        assertThat(leadRepository.findByChatIdAndStatusIn(c.getId(), List.of(LeadStatus.NEW))).hasSize(1);
        assertThat(leadRepository.findByChatIdAndStatusIn(c.getId(), List.of(LeadStatus.CLOSED))).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.ChatPersistenceTest'`
Expected: FAIL — компиляция (`Chat`, `ChatRepository` … не найдены).

- [ ] **Step 3: Миграция**

`src/main/resources/db/migration/V21__whatsapp_chats.sql`:

```sql
-- Чаты WhatsApp рабочего номера через Green-API
-- (спека docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md §4).
-- Сообщение хранится ОДИН раз — в своём чате; обращение ссылается на чат (lead.chat_id).

CREATE TABLE chat (
    id                   BIGSERIAL PRIMARY KEY,
    market               VARCHAR(2)   NOT NULL,
    channel              VARCHAR(20)  NOT NULL,               -- WHATSAPP
    account              VARCHAR(40)  NOT NULL,               -- номер подключённого аккаунта: wid без «@c.us»
    external_chat_id     VARCHAR(100) NOT NULL,               -- chatId Green-API: 7701…@c.us / …@g.us / …@lid
    is_group             BOOLEAN      NOT NULL DEFAULT false,
    title                VARCHAR(255),
    phone_norm           VARCHAR(20),                         -- только у @c.us; ключ склейки с обращениями
    not_client           BOOLEAN      NOT NULL DEFAULT false, -- «не клиент»: обращения не создаются
    last_message_at      TIMESTAMPTZ,
    last_message_preview VARCHAR(300),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_ext   ON chat (channel, account, external_chat_id);
CREATE INDEX idx_chat_market_last ON chat (market, last_message_at DESC);
CREATE INDEX idx_chat_phone       ON chat (market, phone_norm) WHERE phone_norm IS NOT NULL;

CREATE TABLE chat_message (
    id           BIGSERIAL PRIMARY KEY,
    chat_id      BIGINT       NOT NULL REFERENCES chat(id) ON DELETE CASCADE,
    external_id  VARCHAR(100) NOT NULL,                       -- idMessage
    direction    VARCHAR(3)   NOT NULL,                       -- IN / OUT
    sender_name  VARCHAR(255),
    type         VARCHAR(20)  NOT NULL,
    body         TEXT,
    sent_at      TIMESTAMPTZ  NOT NULL,
    edited       BOOLEAN      NOT NULL DEFAULT false,
    deleted      BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_message_ext ON chat_message (chat_id, external_id);
CREATE INDEX idx_chat_message_chat_time ON chat_message (chat_id, sent_at DESC, id DESC);

CREATE TABLE chat_attachment (
    id                BIGSERIAL PRIMARY KEY,
    message_id        BIGINT NOT NULL UNIQUE REFERENCES chat_message(id) ON DELETE CASCADE,
    file_name         VARCHAR(255),
    mime_type         VARCHAR(100),
    size_bytes        BIGINT,
    content           BYTEA,                                  -- NULL ⇔ not_stored_reason IS NOT NULL
    not_stored_reason VARCHAR(20)                             -- TOO_LARGE / GROUP / DOWNLOAD_FAILED
);

ALTER TABLE lead ADD COLUMN chat_id BIGINT REFERENCES chat(id) ON DELETE SET NULL;
CREATE INDEX idx_lead_chat ON lead (chat_id) WHERE chat_id IS NOT NULL;
```

- [ ] **Step 4: Перечисления**

`entity/ChatMessageType.java`:

```java
package com.vladoose.nir.entity;

/** Вид сообщения чата (спека whatsapp-chats §6.5). */
public enum ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER, LOCATION, CONTACT, OTHER }
```

`entity/AttachmentNotStoredReason.java`:

```java
package com.vladoose.nir.entity;

/** Почему байтов файла нет в АИС (спека whatsapp-chats §6.4): больше предела / файл группы / не скачался. */
public enum AttachmentNotStoredReason { TOO_LARGE, GROUP, DOWNLOAD_FAILED }
```

- [ ] **Step 5: Сущности**

`entity/Chat.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;

/**
 * Чат рабочего номера WhatsApp (спека 2026-09-28-whatsapp-chats-green-api §4). Рыночная сущность:
 * @Filter + листенер штампа; @FilterDef объявлен ОДИН раз — на Tender (§6 CLAUDE.md).
 * Сообщения и файлы — отдельные сущности без рыночного фильтра: наружу только через свой чат.
 */
@Entity
@Table(name = "chat")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Chat implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadChannel channel;

    /** Номер подключённого аккаунта (wid без «@c.us»). */
    @Column(nullable = false, length = 40)
    private String account;

    /** chatId Green-API: «7701…@c.us» / «…@g.us» / «…@lid». */
    @Column(name = "external_chat_id", nullable = false, length = 100)
    private String externalChatId;

    @Column(name = "is_group", nullable = false)
    private boolean group;

    private String title;

    @Column(name = "phone_norm", length = 20)
    private String phoneNorm;

    /** «Не клиент» — из чата не создаются обращения (коллега, поставщик, спам). */
    @Column(name = "not_client", nullable = false)
    private boolean notClient;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    @Column(name = "last_message_preview", length = 300)
    private String lastMessagePreview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

`entity/ChatMessage.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Сообщение чата — в обе стороны; уникально по (chat, externalId = idMessage Green-API). */
@Entity
@Table(name = "chat_message")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private LeadDirection direction;

    /** Автор (важно в группах); у исходящих — null. */
    @Column(name = "sender_name")
    private String senderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessageType type;

    /** Текст или подпись к файлу. */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(nullable = false)
    private boolean edited;

    /** «Удалено отправителем» — текст при этом сохраняется. */
    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

`entity/ChatAttachment.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Файл сообщения. Байты читаются только при скачивании — лента берёт метаданные проекцией
 * (ChatAttachmentRepository.findMetaByMessageIds). Инвариант: content == null ⇔ notStoredReason != null.
 */
@Entity
@Table(name = "chat_attachment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false, unique = true)
    private ChatMessage message;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "content")
    private byte[] content;

    @Enumerated(EnumType.STRING)
    @Column(name = "not_stored_reason", length = 20)
    private AttachmentNotStoredReason notStoredReason;
}
```

В `entity/Lead.java` сразу после поля `privateRequest` добавить:

```java
    /** Чат WhatsApp обращения (спека whatsapp-chats §4). У одного чата со временем может быть несколько обращений. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id")
    private Chat chat;
```

- [ ] **Step 6: Репозитории и проекция**

`dto/response/ChatAttachmentMeta.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.AttachmentNotStoredReason;

/** Файл сообщения без байтов — для ленты (байты читаются только при скачивании). */
public record ChatAttachmentMeta(Long id, Long messageId, String fileName, String mimeType, Long sizeBytes,
                                 AttachmentNotStoredReason notStoredReason) {
    public boolean stored() { return notStoredReason == null; }
}
```

`repository/ChatRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.LeadChannel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** Выборки — HQL: рыночный фильтр аспекта их режет (§6 CLAUDE.md). findById фильтр обходит — гард в сервисе. */
public interface ChatRepository extends JpaRepository<Chat, Long> {

    Optional<Chat> findByChannelAndAccountAndExternalChatId(LeadChannel channel, String account, String externalChatId);

    @Query("select c from Chat c order by c.lastMessageAt desc nulls last, c.id desc")
    List<Chat> findRecent(Pageable pageable);
}
```

`repository/ChatMessageRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ChatMessage;
import com.vladoose.nir.entity.LeadDirection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** У сообщений нет рыночного фильтра: сюда ходят только с id чата, уже проверенного на рынок. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    boolean existsByChatIdAndExternalId(Long chatId, String externalId);

    Optional<ChatMessage> findByChatIdAndExternalId(Long chatId, String externalId);

    @Query("select m from ChatMessage m where m.chat.id = :chatId order by m.sentAt desc, m.id desc")
    List<ChatMessage> findLatest(@Param("chatId") Long chatId, Pageable pageable);

    /** Порция перед сообщением (sentAt, id) — keyset по тому же порядку, что findLatest. */
    @Query("""
           select m from ChatMessage m where m.chat.id = :chatId
             and (m.sentAt < :sentAt or (m.sentAt = :sentAt and m.id < :id))
           order by m.sentAt desc, m.id desc""")
    List<ChatMessage> findBefore(@Param("chatId") Long chatId, @Param("sentAt") OffsetDateTime sentAt,
                                 @Param("id") Long id, Pageable pageable);

    @Query("select m from ChatMessage m where m.chat.id = :chatId and m.sentAt >= :since order by m.sentAt desc, m.id desc")
    List<ChatMessage> findSince(@Param("chatId") Long chatId, @Param("since") OffsetDateTime since, Pageable pageable);

    @Query("select m from ChatMessage m where m.chat.id = :chatId and m.sentAt > :after order by m.sentAt asc, m.id asc")
    List<ChatMessage> findEarliestAfter(@Param("chatId") Long chatId, @Param("after") OffsetDateTime after, Pageable pageable);

    @Query("select m from ChatMessage m where m.chat.id = :chatId and m.direction = :direction order by m.sentAt desc, m.id desc")
    List<ChatMessage> findLatestByDirection(@Param("chatId") Long chatId, @Param("direction") LeadDirection direction,
                                            Pageable pageable);

    /** Чаты, где текст встречается в сообщениях. Без рыночного фильтра — вызывающий пересекает с чатами своего рынка. */
    @Query("select distinct m.chat.id from ChatMessage m where lower(m.body) like lower(concat('%', :q, '%'))")
    List<Long> findChatIdsByBody(@Param("q") String q);
}
```

`repository/ChatAttachmentRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    @Query("""
           select new com.vladoose.nir.dto.response.ChatAttachmentMeta(
               a.id, a.message.id, a.fileName, a.mimeType, a.sizeBytes, a.notStoredReason)
           from ChatAttachment a where a.message.id in :messageIds""")
    List<ChatAttachmentMeta> findMetaByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
```

В `repository/LeadRepository.java` добавить (под `findFirstByPrivateRequestId`):

```java
    List<Lead> findByChatIdAndStatusIn(Long chatId, Collection<LeadStatus> statuses);

    List<Lead> findByPhoneNormAndStatusIn(String phoneNorm, Collection<LeadStatus> statuses);

    List<Lead> findByChatIdIn(Collection<Long> chatIds);
```

- [ ] **Step 7: Прогнать тест**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.ChatPersistenceTest'`
Expected: PASS (6 тестов). Flyway в логе: `Migrating schema "public" to version "21 - whatsapp chats"`.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V21__whatsapp_chats.sql \
  src/main/java/com/vladoose/nir/entity/Chat.java src/main/java/com/vladoose/nir/entity/ChatMessage.java \
  src/main/java/com/vladoose/nir/entity/ChatAttachment.java src/main/java/com/vladoose/nir/entity/ChatMessageType.java \
  src/main/java/com/vladoose/nir/entity/AttachmentNotStoredReason.java src/main/java/com/vladoose/nir/entity/Lead.java \
  src/main/java/com/vladoose/nir/repository/ChatRepository.java src/main/java/com/vladoose/nir/repository/ChatMessageRepository.java \
  src/main/java/com/vladoose/nir/repository/ChatAttachmentRepository.java src/main/java/com/vladoose/nir/repository/LeadRepository.java \
  src/main/java/com/vladoose/nir/dto/response/ChatAttachmentMeta.java src/test/java/com/vladoose/nir/chat/ChatPersistenceTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): модель чатов — V21, сущности chat/chat_message/chat_attachment, lead.chat_id

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: HTTP-клиент Green-API

**Files:**
- Create: `integration/greenapi/GreenApiClient.java`, `GreenApiHttpClient.java`, `GreenApiReceived.java`, `GreenApiSettings.java`, `GreenApiException.java`, `GreenApiAuthException.java`, `GreenApiQuotaException.java`, `FileTooLargeException.java`
- Test: `integration/greenapi/GreenApiHttpClientTest.java`

**Interfaces:**
- Produces:
  - `interface GreenApiClient { boolean isConfigured(); GreenApiReceived receive(int receiveTimeoutSec); void delete(long receiptId); String state(); GreenApiSettings settings(); byte[] download(String url, long maxBytes); }`
  - `record GreenApiReceived(long receiptId, JsonNode body)` — `receive` возвращает `null`, если очередь пуста
  - `record GreenApiSettings(String wid, String webhookUrl, boolean incomingWebhook, boolean outgoingMessageWebhook)`
  - `class GreenApiException extends RuntimeException` с `int status()` (0 — сеть/конфиг); `GreenApiAuthException extends GreenApiException` (401/403 и незаданные учётные данные); `GreenApiQuotaException extends GreenApiException` (466); `FileTooLargeException extends RuntimeException`
  - `@Component GreenApiHttpClient(ObjectMapper, @Value("${chats.whatsapp.api-url:}") String apiUrl, @Value("${chats.whatsapp.id-instance:}") String idInstance, @Value("${chats.whatsapp.api-token:}") String token)`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/integration/greenapi/GreenApiHttpClientTest.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/** Стаб Green-API на JDK HttpServer, без сети. Формы ответов — из документации (спека §2). */
class GreenApiHttpClientTest {

    static final String TOKEN = "tok-secret-123";
    static HttpServer server;
    static int port;
    /** "METHOD path?query" */
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static volatile int status;
    static volatile String body;
    static volatile byte[] file;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
            byte[] b = path.startsWith("/files/") ? file : body.getBytes(StandardCharsets.UTF_8);
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
        status = 200;
        body = "null";
        file = new byte[0];
    }

    static GreenApiHttpClient client() {
        return new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port + "/", "1101", TOKEN);
    }

    @Test
    void emptyQueueIsNull() {
        assertThat(client().receive(5)).isNull();
        assertThat(calls).containsExactly("GET /waInstance1101/receiveNotification/" + TOKEN + "?receiveTimeout=5");
    }

    @Test
    void receivedNotificationCarriesReceiptAndBody() {
        body = """
            {"receiptId":1234567,"body":{"typeWebhook":"incomingMessageReceived","idMessage":"F7AE"}}
            """;

        GreenApiReceived r = client().receive(20);

        assertThat(r.receiptId()).isEqualTo(1234567L);
        assertThat(r.body().path("idMessage").asText()).isEqualTo("F7AE");
    }

    @Test
    void deleteSendsReceiptIdInPath() {
        body = "{\"result\":true}";
        client().delete(1234567);
        assertThat(calls).containsExactly("DELETE /waInstance1101/deleteNotification/" + TOKEN + "/1234567");
    }

    @Test
    void stateAndSettingsAreParsed() {
        body = "{\"stateInstance\":\"notAuthorized\"}";
        assertThat(client().state()).isEqualTo("notAuthorized");

        body = """
            {"wid":"77000000001@c.us","webhookUrl":"","incomingWebhook":"yes","outgoingMessageWebhook":"no",
             "outgoingWebhook":"yes","stateWebhook":"no"}
            """;
        GreenApiSettings s = client().settings();
        assertThat(s.wid()).isEqualTo("77000000001@c.us");
        assertThat(s.webhookUrl()).isEmpty();
        assertThat(s.incomingWebhook()).isTrue();
        assertThat(s.outgoingMessageWebhook()).isFalse();
    }

    @Test
    void rejectedKeyIsAuthErrorWithoutTokenOrUrlInMessage() {
        status = 401;
        body = "";
        assertThatThrownBy(() -> client().receive(5))
                .isInstanceOf(GreenApiAuthException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("waInstance");
        status = 403;
        assertThatThrownBy(() -> client().state()).isInstanceOf(GreenApiAuthException.class);
    }

    @Test
    void quota466IsQuotaError() {
        status = 466;
        body = "";
        assertThatThrownBy(() -> client().receive(5)).isInstanceOf(GreenApiQuotaException.class);
    }

    @Test
    void serverErrorKeepsStatusAndHidesToken() {
        status = 500;
        body = "";
        assertThatThrownBy(() -> client().delete(1))
                .isInstanceOfSatisfying(GreenApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain("waInstance");
                });
    }

    @Test
    void unreachableServiceIsStatusZeroWithoutToken() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) { free = s.getLocalPort(); }
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + free, "1101", TOKEN);

        assertThatThrownBy(() -> c.receive(5))
                .isInstanceOfSatisfying(GreenApiException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).startsWith("Green-API недоступен").doesNotContain(TOKEN);
                });
    }

    @Test
    void downloadReturnsBytesAndStopsAtLimit() {
        file = new byte[]{1, 2, 3, 4, 5};
        String url = "http://localhost:" + port + "/files/a.pdf";

        assertThat(client().download(url, 10)).containsExactly(1, 2, 3, 4, 5);
        assertThatThrownBy(() -> client().download(url, 4)).isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void httpsServiceRefusesPlainHttpDownload() {
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "https://api.green-api.example", "1101", TOKEN);

        assertThatThrownBy(() -> c.download("http://localhost:" + port + "/files/a.pdf", 10))
                .isInstanceOf(GreenApiException.class).hasMessageContaining("https");
        assertThat(calls).isEmpty();
    }

    @Test
    void missingCredentialsAreReportedWithoutNetwork() {
        GreenApiHttpClient c = new GreenApiHttpClient(new ObjectMapper(), "http://localhost:" + port, "1101", "");

        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.receive(5)).isInstanceOf(GreenApiAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.greenapi.GreenApiHttpClientTest'`
Expected: FAIL — компиляция (`GreenApiHttpClient` не найден).

- [ ] **Step 3: Типы и исключения**

`integration/greenapi/GreenApiClient.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Green-API (спека whatsapp-chats §2). Интерфейс — ради фейка в тестах (как WestmedClient). */
public interface GreenApiClient {

    boolean isConfigured();

    /** Голова очереди уведомлений (Green-API отдаёт её снова, пока не удалят); null — за receiveTimeoutSec ничего не пришло. */
    GreenApiReceived receive(int receiveTimeoutSec);

    void delete(long receiptId);

    /** stateInstance: authorized / notAuthorized / blocked / sleepMode / starting / suspended / … */
    String state();

    GreenApiSettings settings();

    /** Файл по downloadUrl из уведомления; больше maxBytes → FileTooLargeException (байты не копятся сверх предела). */
    byte[] download(String url, long maxBytes);
}
```

`integration/greenapi/GreenApiReceived.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Уведомление из очереди: receiptId — чем удалять, body — само уведомление. */
public record GreenApiReceived(long receiptId, JsonNode body) {}
```

`integration/greenapi/GreenApiSettings.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Нужное АИС из getSettings: номер и флаги, без которых приём молча неполный (спека §7). */
public record GreenApiSettings(String wid, String webhookUrl, boolean incomingWebhook, boolean outgoingMessageWebhook) {}
```

`integration/greenapi/GreenApiException.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Ошибка Green-API. status 0 — сеть/конфиг. Текст — человеческий и БЕЗ URL: в пути URL стоит токен. */
public class GreenApiException extends RuntimeException {

    private final int status;

    public GreenApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
```

`integration/greenapi/GreenApiAuthException.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Ключ отклонён (401/403) или не задан — планировщик ставит длинную паузу. */
public class GreenApiAuthException extends GreenApiException {
    public GreenApiAuthException(int status, String message) { super(status, message); }
}
```

`integration/greenapi/GreenApiQuotaException.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** HTTP 466 — исчерпан лимит тарифа Developer. */
public class GreenApiQuotaException extends GreenApiException {
    public GreenApiQuotaException(int status, String message) { super(status, message); }
}
```

`integration/greenapi/FileTooLargeException.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Файл больше chats.whatsapp.max-file-mb — скачивание оборвано, в базу не пишем. */
public class FileTooLargeException extends RuntimeException {
    public FileTooLargeException(long maxBytes) {
        super("файл больше " + (maxBytes / (1024 * 1024)) + " МБ");
    }
}
```

- [ ] **Step 4: Реализация клиента**

`integration/greenapi/GreenApiHttpClient.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP к Green-API (спека whatsapp-chats §2, §10). ⚠️ Токен стоит В ПУТИ URL
 * ({apiUrl}/waInstance{id}/{метод}/{token}), поэтому URL не попадает ни в лог, ни в текст исключения:
 * сообщения об ошибках собираются вручную, из названия операции.
 */
@Component
public class GreenApiHttpClient implements GreenApiClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)   // NORMAL не идёт с https на http
            .build();
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String idInstance;
    private final String token;

    public GreenApiHttpClient(ObjectMapper objectMapper,
                              @Value("${chats.whatsapp.api-url:}") String apiUrl,
                              @Value("${chats.whatsapp.id-instance:}") String idInstance,
                              @Value("${chats.whatsapp.api-token:}") String token) {
        this.objectMapper = objectMapper;
        String u = apiUrl == null ? "" : apiUrl.trim();
        this.apiUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.idInstance = idInstance == null ? "" : idInstance.trim();
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public boolean isConfigured() {
        return !apiUrl.isEmpty() && !idInstance.isEmpty() && !token.isEmpty();
    }

    @Override
    public GreenApiReceived receive(int receiveTimeoutSec) {
        String what = "приёме сообщений";
        HttpResponse<String> r = call("GET", "receiveNotification", "?receiveTimeout=" + receiveTimeoutSec,
                Duration.ofSeconds(receiveTimeoutSec + 15L), what);
        JsonNode root = parse(r.body(), what);
        if (root == null || root.isNull() || !root.has("receiptId")) return null;
        return new GreenApiReceived(root.get("receiptId").asLong(), root.path("body"));
    }

    @Override
    public void delete(long receiptId) {
        call("DELETE", "deleteNotification", "/" + receiptId, Duration.ofSeconds(20), "удалении уведомления из очереди");
    }

    @Override
    public String state() {
        String what = "запросе состояния";
        JsonNode s = parse(call("GET", "getStateInstance", "", Duration.ofSeconds(20), what).body(), what);
        return s == null ? "" : s.path("stateInstance").asText("");
    }

    @Override
    public GreenApiSettings settings() {
        String what = "запросе настроек";
        JsonNode s = parse(call("GET", "getSettings", "", Duration.ofSeconds(20), what).body(), what);
        if (s == null) s = objectMapper.createObjectNode();
        return new GreenApiSettings(s.path("wid").asText(""), s.path("webhookUrl").asText(""),
                yes(s.path("incomingWebhook")), yes(s.path("outgoingMessageWebhook")));
    }

    @Override
    public byte[] download(String url, long maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new GreenApiException(0, "Green-API: некорректная ссылка на файл");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // https всегда; http — только если сам сервис на http (dev-стаб, тесты)
        if (!scheme.equals("https") && !scheme.equals(apiScheme())) {
            throw new GreenApiException(0, "Green-API: ссылка на файл не по https — не скачиваем");
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() / 100 != 2) {
                    throw new GreenApiException(r.statusCode(), "Green-API: HTTP " + r.statusCode() + " при скачивании файла");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new FileTooLargeException(maxBytes);
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new GreenApiException(0, "Green-API: файл не скачался: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GreenApiException(0, "Green-API: скачивание прервано");
        }
    }

    private HttpResponse<String> call(String method, String apiMethod, String suffix, Duration timeout, String what) {
        if (!isConfigured()) {
            throw new GreenApiAuthException(0, "не заданы учётные данные Green-API");
        }
        URI uri = URI.create(apiUrl + "/waInstance" + idInstance + "/" + apiMethod + "/" + token + suffix);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // только класс исключения: текст части исключений JDK содержит адрес
            throw new GreenApiException(0, "Green-API недоступен при " + what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GreenApiException(0, "Green-API: запрос прерван при " + what);
        }
        int st = r.statusCode();
        if (st == 401 || st == 403) {
            throw new GreenApiAuthException(st, "Green-API отклонил ключ (HTTP " + st + ") при " + what
                    + " — проверьте idInstance и токен");
        }
        if (st == 466) {
            throw new GreenApiQuotaException(st, "Green-API: исчерпан лимит тарифа (HTTP 466) при " + what);
        }
        if (st / 100 != 2) {
            throw new GreenApiException(st, "Green-API: HTTP " + st + " при " + what);
        }
        return r;
    }

    private JsonNode parse(String body, String what) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new GreenApiException(200, "Green-API: ответ не разобран при " + what);
        }
    }

    private String apiScheme() {
        try {
            String s = URI.create(apiUrl).getScheme();
            return s == null ? "" : s.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean yes(JsonNode n) {
        return "yes".equalsIgnoreCase(n.asText(""));
    }
}
```

- [ ] **Step 5: Прогнать тест**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.greenapi.GreenApiHttpClientTest'`
Expected: PASS (11 тестов).

- [ ] **Step 6: Мутация (урок §14 «зелёный тест, который не может упасть»)**

Временно заменить в `call(...)` текст исключения `"Green-API: HTTP " + st + " при " + what` на `"Green-API: HTTP " + st + " на " + uri`. Прогнать тест → должен покраснеть `serverErrorKeepsStatusAndHidesToken`. Вернуть как было, прогнать → PASS.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/greenapi src/test/java/com/vladoose/nir/integration/greenapi/GreenApiHttpClientTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): HTTP-клиент Green-API — очередь, состояние, настройки, файлы; токен не утекает в ошибки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Разбор уведомлений Green-API

**Files:**
- Create: `integration/greenapi/ChatKind.java`, `FileRef.java`, `ParsedNotification.java`, `GreenApiNotificationParser.java`
- Create (тестовый помощник, нужен задачам 3, 5–8): `src/test/java/com/vladoose/nir/integration/greenapi/GreenApiJson.java`
- Test: `integration/greenapi/GreenApiNotificationParserTest.java`

**Interfaces:**
- Consumes: `ChatMessageType`, `LeadDirection` (Task 1 / существующее).
- Produces:
  - `enum ChatKind { PERSONAL, PERSONAL_HIDDEN, GROUP }`
  - `record FileRef(String downloadUrl, String fileName, String mimeType)`
  - `sealed interface ParsedNotification` с записями `Message(String account, String chatId, ChatKind kind, String phone, String chatName, String senderName, LeadDirection direction, String idMessage, OffsetDateTime sentAt, ChatMessageType type, String body, FileRef file, String editOf)` (+ `String displayText()`, `boolean isEdit()`), `Delete(String account, String chatId, String deletedId)`, `State(String state)`, `QuotaExceeded()`, `Skip(String reason)`
  - `GreenApiNotificationParser.parse(JsonNode body) : ParsedNotification` (static)
  - Тестовый `GreenApiJson`: `ACCOUNT`, `WID`, `incoming(chatId, name, idMessage, epochSec, messageData)`, `outgoing(chatId, recipientName, idMessage, epochSec, messageData)`, `group(groupId, groupName, authorChatId, authorName, idMessage, epochSec, messageData)`, `text(t)`, `extended(typeMessage, t)`, `file(typeMessage, url, fileName, mime, caption)`, `edited(stanzaId, t)`, `deleted(stanzaId)`, `reaction(stanzaId)`, `location(name, address, lat, lon)`, `contact(name)`, `typeOnly(typeMessage)`, `state(s)`, `quota()`, `webhook(typeWebhook)` — все возвращают `ObjectNode`

- [ ] **Step 1: Тестовый помощник с реальной формой уведомлений**

`src/test/java/com/vladoose/nir/integration/greenapi/GreenApiJson.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** Уведомления Green-API в реальной форме (документация, спека §2) — для тестов парсера, приёма и API. */
public final class GreenApiJson {

    public static final String ACCOUNT = "77000000001";
    public static final String WID = ACCOUNT + "@c.us";
    private static final ObjectMapper M = new ObjectMapper();

    private GreenApiJson() {}

    public static ObjectNode incoming(String chatId, String name, String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("incomingMessageReceived", idMessage, epochSec, chatId, chatId, name, name, name, messageData);
    }

    /** Отправлено с телефона: chatId — получатель, sender — наш номер, senderName — наше собственное имя. */
    public static ObjectNode outgoing(String chatId, String recipientName, String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("outgoingMessageReceived", idMessage, epochSec, chatId, WID, recipientName, "West-Med", "", messageData);
    }

    public static ObjectNode group(String groupId, String groupName, String authorChatId, String authorName,
                                   String idMessage, long epochSec, ObjectNode messageData) {
        return envelope("incomingMessageReceived", idMessage, epochSec, groupId, authorChatId, groupName,
                authorName, authorName, messageData);
    }

    public static ObjectNode text(String text) {
        ObjectNode md = type("textMessage");
        md.set("textMessageData", M.createObjectNode().put("textMessage", text));
        return md;
    }

    public static ObjectNode extended(String typeMessage, String text) {
        ObjectNode md = type(typeMessage);
        md.set("extendedTextMessageData", M.createObjectNode().put("text", text).put("description", "").put("title", ""));
        return md;
    }

    public static ObjectNode file(String typeMessage, String url, String fileName, String mime, String caption) {
        ObjectNode md = type(typeMessage);
        md.set("fileMessageData", M.createObjectNode().put("downloadUrl", url).put("caption", caption)
                .put("fileName", fileName).put("jpegThumbnail", "").put("mimeType", mime));
        return md;
    }

    public static ObjectNode edited(String stanzaId, String newText) {
        ObjectNode md = type("editedMessage");
        md.set("editedMessageData", M.createObjectNode().put("textMessage", newText).put("stanzaId", stanzaId));
        return md;
    }

    public static ObjectNode deleted(String stanzaId) {
        ObjectNode md = type("deletedMessage");
        md.set("deletedMessageData", M.createObjectNode().put("stanzaId", stanzaId));
        return md;
    }

    public static ObjectNode reaction(String stanzaId) {
        ObjectNode md = type("reactionMessage");
        md.set("extendedTextMessageData", M.createObjectNode().put("text", "👍"));
        md.set("quotedMessage", M.createObjectNode().put("stanzaId", stanzaId));
        return md;
    }

    public static ObjectNode location(String name, String address, double lat, double lon) {
        ObjectNode md = type("locationMessage");
        md.set("locationMessageData", M.createObjectNode().put("nameLocation", name).put("address", address)
                .put("latitude", lat).put("longitude", lon).put("jpegThumbnail", ""));
        return md;
    }

    public static ObjectNode contact(String displayName) {
        ObjectNode md = type("contactMessage");
        md.set("contactMessageData", M.createObjectNode().put("displayName", displayName).put("vcard", "BEGIN:VCARD\nEND:VCARD"));
        return md;
    }

    public static ObjectNode typeOnly(String typeMessage) { return type(typeMessage); }

    public static ObjectNode state(String state) {
        ObjectNode b = webhook("stateInstanceChanged");
        b.put("stateInstance", state);
        return b;
    }

    public static ObjectNode quota() { return webhook("quotaExceeded"); }

    public static ObjectNode webhook(String typeWebhook) {
        ObjectNode b = M.createObjectNode();
        b.put("typeWebhook", typeWebhook);
        b.set("instanceData", instance());
        b.put("timestamp", Instant.now().getEpochSecond());
        return b;
    }

    private static ObjectNode envelope(String type, String idMessage, long epochSec, String chatId, String sender,
                                       String chatName, String senderName, String senderContactName, ObjectNode messageData) {
        ObjectNode b = M.createObjectNode();
        b.put("typeWebhook", type);
        b.set("instanceData", instance());
        b.put("timestamp", epochSec);
        b.put("idMessage", idMessage);
        ObjectNode sd = M.createObjectNode();
        sd.put("chatId", chatId);
        sd.put("sender", sender);
        sd.put("chatName", chatName);
        sd.put("senderName", senderName);
        sd.put("senderContactName", senderContactName);
        b.set("senderData", sd);
        b.set("messageData", messageData);
        return b;
    }

    private static ObjectNode type(String typeMessage) {
        ObjectNode md = M.createObjectNode();
        md.put("typeMessage", typeMessage);
        return md;
    }

    private static ObjectNode instance() {
        return M.createObjectNode().put("idInstance", 1101).put("wid", WID).put("typeInstance", "whatsapp");
    }
}
```

- [ ] **Step 2: Написать падающий тест**

`src/test/java/com/vladoose/nir/integration/greenapi/GreenApiNotificationParserTest.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

class GreenApiNotificationParserTest {

    static final String CLIENT = "77011234567@c.us";
    static final long T = 1_790_000_000L;

    private static ParsedNotification.Message msg(ObjectNode body) {
        ParsedNotification p = GreenApiNotificationParser.parse(body);
        assertThat(p).isInstanceOf(ParsedNotification.Message.class);
        return (ParsedNotification.Message) p;
    }

    @Test
    void incomingTextFromPersonalChat() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming(CLIENT, "Айгерим", "ID-1", T, GreenApiJson.text("Нужен облучатель")));

        assertThat(m.account()).isEqualTo("77000000001");
        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL);
        assertThat(m.phone()).isEqualTo("+77011234567");
        assertThat(m.chatName()).isEqualTo("Айгерим");
        assertThat(m.senderName()).isEqualTo("Айгерим");
        assertThat(m.direction()).isEqualTo(LeadDirection.IN);
        assertThat(m.idMessage()).isEqualTo("ID-1");
        assertThat(m.sentAt()).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(T), ZoneOffset.UTC));
        assertThat(m.type()).isEqualTo(ChatMessageType.TEXT);
        assertThat(m.body()).isEqualTo("Нужен облучатель");
        assertThat(m.file()).isNull();
        assertThat(m.isEdit()).isFalse();
    }

    @Test
    void contactNameFromPhoneBookWinsOverProfileName() {
        ObjectNode b = GreenApiJson.incoming(CLIENT, "Profile", "ID-2", T, GreenApiJson.text("x"));
        ((ObjectNode) b.get("senderData")).put("senderContactName", "Клиника «Шипагер»");

        assertThat(msg(b).chatName()).isEqualTo("Клиника «Шипагер»");
    }

    @Test
    void extendedAndQuotedTextsUseExtendedData() {
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-3", T,
                GreenApiJson.extended("extendedTextMessage", "см. https://westmed.kz"))).body()).isEqualTo("см. https://westmed.kz");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-4", T,
                GreenApiJson.extended("quotedMessage", "да, этот"))).body()).isEqualTo("да, этот");
    }

    @Test
    void phoneReplyIsOutgoingToRecipientChat() {
        ParsedNotification.Message m = msg(GreenApiJson.outgoing(CLIENT, "Айгерим", "ID-5", T, GreenApiJson.text("Подготовим КП")));

        assertThat(m.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.chatName()).isEqualTo("Айгерим");
        assertThat(m.senderName()).isNull();
    }

    @Test
    void imageWithCaptionCarriesFileRef() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming(CLIENT, "A", "ID-6", T, GreenApiJson.file("imageMessage",
                "https://api.greenapi.com/waInstance1101/downloadFile/ABC", "photo.jpg", "image/jpeg", "Вот такой")));

        assertThat(m.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(m.body()).isEqualTo("Вот такой");
        assertThat(m.file()).isEqualTo(new FileRef("https://api.greenapi.com/waInstance1101/downloadFile/ABC", "photo.jpg", "image/jpeg"));
    }

    @Test
    void documentWithoutCaptionGetsPlaceholderText() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming(CLIENT, "A", "ID-7", T, GreenApiJson.file("documentMessage",
                "https://x/f", "Заявка.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "")));

        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.body()).isNull();
        assertThat(m.displayText()).isEqualTo("[документ: Заявка.xlsx]");
    }

    @Test
    void groupMessageKeepsAuthorAndGroupTitle() {
        ParsedNotification.Message m = msg(GreenApiJson.group("120363000000000001@g.us", "Коллеги West-Med",
                "77025556677@c.us", "Данияр", "ID-8", T, GreenApiJson.text("Кто едет в Уральск?")));

        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
        assertThat(m.phone()).isNull();
        assertThat(m.chatName()).isEqualTo("Коллеги West-Med");
        assertThat(m.senderName()).isEqualTo("Данияр");
    }

    @Test
    void hiddenNumberChatHasNoPhone() {
        ParsedNotification.Message m = msg(GreenApiJson.incoming("123456789012345@lid", "Скрытый", "ID-9", T, GreenApiJson.text("Добрый день")));

        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL_HIDDEN);
        assertThat(m.phone()).isNull();
    }

    @Test
    void storiesAndChannelsAreSkipped() {
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming("status@broadcast", "A", "ID-10", T, GreenApiJson.text("x"))))
                .isInstanceOf(ParsedNotification.Skip.class);
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming("120363000000000002@newsletter", "A", "ID-11", T, GreenApiJson.text("x"))))
                .isInstanceOf(ParsedNotification.Skip.class);
    }

    @Test
    void editDeleteAndReaction() {
        ParsedNotification.Message edit = msg(GreenApiJson.incoming(CLIENT, "A", "ID-12", T, GreenApiJson.edited("ID-1", "Нужны два облучателя")));
        assertThat(edit.isEdit()).isTrue();
        assertThat(edit.editOf()).isEqualTo("ID-1");
        assertThat(edit.body()).isEqualTo("Нужны два облучателя");

        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming(CLIENT, "A", "ID-13", T, GreenApiJson.deleted("ID-1"))))
                .isEqualTo(new ParsedNotification.Delete("77000000001", CLIENT, "ID-1"));
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.incoming(CLIENT, "A", "ID-14", T, GreenApiJson.reaction("ID-1"))))
                .isInstanceOf(ParsedNotification.Skip.class);
    }

    @Test
    void stickerLocationContactAndUnknownTypes() {
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-15", T, GreenApiJson.typeOnly("stickerMessage")))).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.STICKER);
            assertThat(m.body()).isEqualTo("[стикер]");
            assertThat(m.file()).isNull();
        });
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-16", T,
                GreenApiJson.location("Клиника", "Уральск, ул. Ленина 1", 51.2, 51.37))).body())
                .isEqualTo("📍 Клиника, Уральск, ул. Ленина 1");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-17", T, GreenApiJson.contact("Иван Поставщик"))).body())
                .isEqualTo("[контакт: Иван Поставщик]");
        assertThat(msg(GreenApiJson.incoming(CLIENT, "A", "ID-18", T, GreenApiJson.typeOnly("pollMessage")))).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.OTHER);
            assertThat(m.body()).contains("pollMessage");
        });
    }

    @Test
    void stateQuotaAndUnknownWebhooks() {
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.state("blocked"))).isEqualTo(new ParsedNotification.State("blocked"));
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.quota())).isInstanceOf(ParsedNotification.QuotaExceeded.class);
        assertThat(GreenApiNotificationParser.parse(GreenApiJson.webhook("outgoingMessageStatus")))
                .isInstanceOf(ParsedNotification.Skip.class);
    }
}
```

- [ ] **Step 3: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.greenapi.GreenApiNotificationParserTest'`
Expected: FAIL — компиляция (`ParsedNotification`, `GreenApiNotificationParser` не найдены).

- [ ] **Step 4: Реализация**

`integration/greenapi/ChatKind.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Вид чата по chatId: @c.us — личный с номером, @lid — личный со скрытым номером, @g.us — группа. */
public enum ChatKind { PERSONAL, PERSONAL_HIDDEN, GROUP }
```

`integration/greenapi/FileRef.java`:

```java
package com.vladoose.nir.integration.greenapi;

/** Файл сообщения в уведомлении: ссылка на скачивание (размера Green-API не сообщает), имя и MIME. */
public record FileRef(String downloadUrl, String fileName, String mimeType) {}
```

`integration/greenapi/ParsedNotification.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;

import java.time.OffsetDateTime;

/** Разобранное уведомление Green-API (спека whatsapp-chats §2, §6.5). */
public sealed interface ParsedNotification {

    /**
     * Сообщение — входящее или отправленное с телефона. phone — «+цифры» только у личного чата с номером (@c.us);
     * chatName — лучшее имя чата (§6.6); editOf != null — правка сообщения editOf, body — новый текст.
     */
    record Message(String account, String chatId, ChatKind kind, String phone, String chatName, String senderName,
                   LeadDirection direction, String idMessage, OffsetDateTime sentAt, ChatMessageType type,
                   String body, FileRef file, String editOf) implements ParsedNotification {

        /** Текст для превью и первого сообщения обращения: у файла без подписи — «[фото]» и т.п. */
        public String displayText() {
            if (body != null && !body.isBlank()) return body;
            return switch (type) {
                case IMAGE -> "[фото]";
                case VIDEO -> "[видео]";
                case AUDIO -> "[аудио]";
                case DOCUMENT -> "[документ" + (file != null && file.fileName() != null ? ": " + file.fileName() : "") + "]";
                default -> "[сообщение]";
            };
        }

        public boolean isEdit() { return editOf != null && !editOf.isBlank(); }
    }

    record Delete(String account, String chatId, String deletedId) implements ParsedNotification {}

    record State(String state) implements ParsedNotification {}

    record QuotaExceeded() implements ParsedNotification {}

    record Skip(String reason) implements ParsedNotification {}
}
```

`integration/greenapi/GreenApiNotificationParser.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Уведомление Green-API → доменная запись (спека whatsapp-chats §2, §6.5–6.6). Чистая функция:
 * на недостающих полях не бросает (path/asText), неизвестное — Skip или сообщение OTHER.
 */
public final class GreenApiNotificationParser {

    private GreenApiNotificationParser() {}

    public static ParsedNotification parse(JsonNode body) {
        String type = body == null ? "" : body.path("typeWebhook").asText("");
        switch (type) {
            case "incomingMessageReceived":
                return message(body, LeadDirection.IN);
            case "outgoingMessageReceived":
            case "outgoingAPIMessageReceived":
                return message(body, LeadDirection.OUT);
            case "stateInstanceChanged":
                return new ParsedNotification.State(body.path("stateInstance").asText(""));
            case "quotaExceeded":
                return new ParsedNotification.QuotaExceeded();
            default:
                return new ParsedNotification.Skip(type.isEmpty() ? "без typeWebhook" : type);
        }
    }

    /** Вид чата; null — это не чат (истории status@broadcast, каналы …@newsletter, рассылки …@broadcast). */
    static ChatKind kindOf(String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        if (chatId.endsWith("@g.us")) return ChatKind.GROUP;
        if (chatId.endsWith("@c.us")) return ChatKind.PERSONAL;
        if (chatId.endsWith("@lid")) return ChatKind.PERSONAL_HIDDEN;
        return null;
    }

    private static ParsedNotification message(JsonNode b, LeadDirection dir) {
        JsonNode sd = b.path("senderData");
        String chatId = sd.path("chatId").asText("");
        ChatKind kind = kindOf(chatId);
        if (kind == null) return new ParsedNotification.Skip("не чат: " + chatId);

        String account = beforeAt(b.path("instanceData").path("wid").asText(""));
        String idMessage = b.path("idMessage").asText("");
        OffsetDateTime at = OffsetDateTime.ofInstant(Instant.ofEpochSecond(b.path("timestamp").asLong(0)), ZoneOffset.UTC);
        String phone = kind == ChatKind.PERSONAL ? "+" + beforeAt(chatId) : null;
        // у исходящих senderName — НАШЕ имя, поэтому имя чата — только chatName (имя получателя)
        String chatName = kind == ChatKind.GROUP ? text(sd, "chatName")
                : dir == LeadDirection.IN ? first(text(sd, "senderContactName"), text(sd, "senderName"), text(sd, "chatName"))
                : text(sd, "chatName");
        String senderName = dir == LeadDirection.IN ? first(text(sd, "senderContactName"), text(sd, "senderName")) : null;

        JsonNode md = b.path("messageData");
        String tm = md.path("typeMessage").asText("");
        if (tm.equals("reactionMessage")) return new ParsedNotification.Skip("реакция");
        if (tm.equals("deletedMessage")) {
            return new ParsedNotification.Delete(account, chatId, md.path("deletedMessageData").path("stanzaId").asText(""));
        }
        if (tm.equals("editedMessage")) {
            JsonNode ed = md.path("editedMessageData");
            return new ParsedNotification.Message(account, chatId, kind, phone, chatName, senderName, dir, idMessage, at,
                    ChatMessageType.TEXT, text(ed, "textMessage"), null, ed.path("stanzaId").asText(""));
        }

        ChatMessageType type;
        String bodyText;
        FileRef file = null;
        switch (tm) {
            case "textMessage":
                type = ChatMessageType.TEXT;
                bodyText = text(md.path("textMessageData"), "textMessage");
                break;
            case "extendedTextMessage":
            case "quotedMessage":
                type = ChatMessageType.TEXT;
                bodyText = text(md.path("extendedTextMessageData"), "text");
                break;
            case "imageMessage":
            case "videoMessage":
            case "audioMessage":
            case "documentMessage": {
                type = switch (tm) {
                    case "imageMessage" -> ChatMessageType.IMAGE;
                    case "videoMessage" -> ChatMessageType.VIDEO;
                    case "audioMessage" -> ChatMessageType.AUDIO;
                    default -> ChatMessageType.DOCUMENT;
                };
                JsonNode fd = md.path("fileMessageData");
                bodyText = text(fd, "caption");
                file = new FileRef(text(fd, "downloadUrl"), text(fd, "fileName"), text(fd, "mimeType"));
                break;
            }
            case "stickerMessage":
                type = ChatMessageType.STICKER;
                bodyText = "[стикер]";
                break;
            case "locationMessage":
                type = ChatMessageType.LOCATION;
                bodyText = location(md.path("locationMessageData"));
                break;
            case "contactMessage":
                type = ChatMessageType.CONTACT;
                bodyText = "[контакт: " + first(text(md.path("contactMessageData"), "displayName"), "без имени") + "]";
                break;
            case "contactsArrayMessage":
                type = ChatMessageType.CONTACT;
                bodyText = "[контакты]";
                break;
            default:
                type = ChatMessageType.OTHER;
                bodyText = "[сообщение типа " + (tm.isEmpty() ? "неизвестно" : tm) + " — смотрите в WhatsApp]";
        }
        return new ParsedNotification.Message(account, chatId, kind, phone, chatName, senderName, dir, idMessage, at,
                type, bodyText, file, null);
    }

    private static String location(JsonNode ld) {
        List<String> parts = new ArrayList<>();
        String name = text(ld, "nameLocation");
        String address = text(ld, "address");
        if (name != null) parts.add(name);
        if (address != null) parts.add(address);
        if (parts.isEmpty()) parts.add(ld.path("latitude").asDouble() + ", " + ld.path("longitude").asDouble());
        return "📍 " + String.join(", ", parts);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("");
        return v.isBlank() ? null : v;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String beforeAt(String id) {
        int at = id.indexOf('@');
        return at < 0 ? id : id.substring(0, at);
    }
}
```

- [ ] **Step 5: Прогнать тест**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.greenapi.GreenApiNotificationParserTest'`
Expected: PASS (12 тестов).

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/greenapi \
  src/test/java/com/vladoose/nir/integration/greenapi/GreenApiJson.java \
  src/test/java/com/vladoose/nir/integration/greenapi/GreenApiNotificationParserTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): разбор уведомлений Green-API — сообщения, файлы, правки, удаления, состояние

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Шаблон корзины westmed.kz → позиции

**Files:**
- Create: `util/SiteCartMessageParser.java`
- Test: `util/SiteCartMessageParserTest.java`

**Interfaces:**
- Produces: `SiteCartMessageParser.parse(String text) : List<SiteCartMessageParser.Line>`, `record Line(String name, int quantity)`; пустой список — «не шаблон».

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/util/SiteCartMessageParserTest.java`:

```java
package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** Текст кнопки WhatsApp на westmed.kz: WhatsAppButton.tsx + messages/{ru,kz,en}.json сайта (спека §6.7). */
class SiteCartMessageParserTest {

    @Test
    void russianTemplateWithQuantities() {
        String text = """
                Здравствуйте! Интересует следующее оборудование:

                1. Облучатель ОБН-150 (x2)
                2. Рециркулятор СН-111-130

                Прошу подготовить коммерческое предложение.""";

        assertThat(SiteCartMessageParser.parse(text)).containsExactly(
                new SiteCartMessageParser.Line("Облучатель ОБН-150", 2),
                new SiteCartMessageParser.Line("Рециркулятор СН-111-130", 1));
    }

    @Test
    void kazakhAndEnglishTemplates() {
        assertThat(SiteCartMessageParser.parse("Сәлеметсіз бе! Келесі жабдық қызықтырады:\n\n1. Аппарат УЗИ (x3)\n\nКоммерциялық ұсыныс дайындауыңызды сұраймын."))
                .containsExactly(new SiteCartMessageParser.Line("Аппарат УЗИ", 3));
        assertThat(SiteCartMessageParser.parse("Hello! I'm interested in the following equipment:\n\n1. Autoclave\n\nPlease prepare a commercial offer."))
                .containsExactly(new SiteCartMessageParser.Line("Autoclave", 1));
    }

    @Test
    void spacesCaseAndWindowsLineBreaksDoNotMatter() {
        assertThat(SiteCartMessageParser.parse("  здравствуйте! интересует следующее оборудование:\r\n\r\n 1.  Облучатель  (x2) \r\n"))
                .containsExactly(new SiteCartMessageParser.Line("Облучатель", 2));
    }

    @Test
    void freeNumberedListOfClientIsNotTemplate() {
        assertThat(SiteCartMessageParser.parse("1. срочно\n2. доставка в Уральск")).isEmpty();
        assertThat(SiteCartMessageParser.parse("Здравствуйте, нужен аппарат УЗИ")).isEmpty();
        assertThat(SiteCartMessageParser.parse(null)).isEmpty();
    }

    @Test
    void greetingWithoutLinesGivesNothingAndHugeQuantityStaysInName() {
        assertThat(SiteCartMessageParser.parse("Здравствуйте! Интересует следующее оборудование:\n\nПрошу подготовить коммерческое предложение."))
                .isEmpty();
        assertThat(SiteCartMessageParser.parse("Здравствуйте! Интересует следующее оборудование:\n1. Бахилы (x123456)"))
                .containsExactly(new SiteCartMessageParser.Line("Бахилы (x123456)", 1));
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.util.SiteCartMessageParserTest'`
Expected: FAIL — компиляция.

- [ ] **Step 3: Реализация**

`util/SiteCartMessageParser.java`:

```java
package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст, который кнопка WhatsApp на westmed.kz подставляет из корзины КП (спека whatsapp-chats §6.7):
 * приветствие → «1. Товар (x2)» построчно → просьба о КП. Шаблон — ТОЛЬКО при приветствии в начале:
 * свободный нумерованный список клиента позициями не становится («1. срочно 2. доставка»).
 */
public final class SiteCartMessageParser {

    public record Line(String name, int quantity) {}

    /** whatsappGreeting из messages/{ru,kz,en}.json сайта, в нижнем регистре. */
    private static final List<String> GREETINGS = List.of(
            "здравствуйте! интересует следующее оборудование:",
            "сәлеметсіз бе! келесі жабдық қызықтырады:",
            "hello! i'm interested in the following equipment:");

    /** «1. Облучатель ОБН-150 (x2)»; количество — до 5 цифр (больше — часть наименования, без переполнения int). */
    private static final Pattern LINE = Pattern.compile("^\\d+\\.\\s+(.+?)(?:\\s+\\(x(\\d{1,5})\\))?\\s*$");

    private SiteCartMessageParser() {}

    public static List<Line> parse(String text) {
        if (text == null) return List.of();
        String t = text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        if (GREETINGS.stream().noneMatch(lower::startsWith)) return List.of();
        List<Line> out = new ArrayList<>();
        for (String raw : t.split("\\R")) {
            Matcher m = LINE.matcher(raw.strip());
            if (!m.matches()) continue;
            int quantity = m.group(2) == null ? 1 : Math.max(1, Integer.parseInt(m.group(2)));
            out.add(new Line(m.group(1).strip().replaceAll("\\s+", " "), quantity));
        }
        return out;
    }
}
```

- [ ] **Step 4: Прогнать тест**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.util.SiteCartMessageParserTest'`
Expected: PASS (5 тестов). Если `spacesCaseAndWindowsLineBreaksDoNotMatter` краснеет на «Облучатель  (x2)» — это ленивый `(.+?)` съел двойной пробел; `replaceAll("\\s+", " ")` в имени и `\\s+` перед скобкой это покрывают — проверить, что оба на месте.

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/util/SiteCartMessageParser.java src/test/java/com/vladoose/nir/util/SiteCartMessageParserTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): разбор шаблона корзины westmed.kz (ru/kz/en) в позиции

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Запись сообщения и правила обращений

**Files:**
- Modify: `integration/lead/LeadSources.java` (`WHATSAPP`, `label`)
- Modify: `service/LeadIntakeService.java` (`receivedText`)
- Modify: `service/LeadService.java` (`takeAutomatically`)
- Create: `integration/greenapi/IncomingFile.java`, `service/ChatLeadRules.java`, `service/ChatIngestWriter.java`
- Test: `chat/ChatIngestWriterTest.java`

**Interfaces:**
- Consumes: Task 1 (сущности, репозитории), Task 3 (`ParsedNotification`, `ChatKind`, `FileRef`, тестовый `GreenApiJson`), существующие `LeadIntakeService.ingest(IncomingLead) : Optional<Lead>`, `LeadService.transition(Lead, LeadStatus, String, String)` (package-private).
- Produces:
  - `LeadSources.WHATSAPP = "whatsapp"`, `LeadSources.label(String source) : String`
  - `LeadService.takeAutomatically(Lead lead, String note)` (`@Transactional`, только из NEW)
  - `record IncomingFile(String fileName, String mimeType, byte[] content, AttachmentNotStoredReason notStoredReason)` + `static IncomingFile stored(FileRef, byte[])`, `static IncomingFile notStored(FileRef, AttachmentNotStoredReason)`
  - `ChatLeadRules.findOpenLead(Chat chat, OffsetDateTime at) : Optional<Lead>`
  - `ChatIngestWriter` (конструктор `(ChatRepository, ChatMessageRepository, ChatAttachmentRepository, ChatLeadRules, LeadIntakeService, LeadService)`, класс НЕ final — тест задачи 6 подменяет `write`): `write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cartItems) : Outcome`, `applyDelete(ParsedNotification.Delete d)`, `record Outcome(boolean duplicate, Long chatId, Long messageId, Long createdLeadId)`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/chat/ChatIngestWriterTest.java`:

```java
package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.IncomingFile;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** Правила чат ↔ обращение (спека whatsapp-chats §5.2) на реальной базе, без сети. */
@SpringBootTest
@Transactional
class ChatIngestWriterTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired EntityManager em;

    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String group() { return "120363" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L) + "@g.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private static ParsedNotification.Message parse(ObjectNode body) {
        return (ParsedNotification.Message) GreenApiNotificationParser.parse(body);
    }
    private ParsedNotification.Message in(String chat, String text) {
        return parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text(text)));
    }
    private ParsedNotification.Message out(String chat, String text) {
        return parse(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text(text)));
    }
    private ChatIngestWriter.Outcome write(ParsedNotification.Message m) { return writer.write(m, null, List.of()); }
    private List<Lead> leadsOf(ChatIngestWriter.Outcome o) { return leadRepository.findByChatIdIn(List.of(o.chatId())); }

    @Test
    void firstMessageOfPersonalChatCreatesChatAndNewLead() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(in(chat, "Нужен облучатель ОБН-150"));

        Chat c = chatRepository.findById(o.chatId()).orElseThrow();
        assertThat(c.getMarket()).isEqualTo(Market.KZ);
        assertThat(c.getTitle()).isEqualTo("Айгерим");
        assertThat(c.getPhoneNorm()).isEqualTo("+" + chat.substring(0, 11));
        assertThat(c.getLastMessagePreview()).isEqualTo("Нужен облучатель ОБН-150");
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getChannel()).isEqualTo(LeadChannel.WHATSAPP);
        assertThat(l.getSource()).isEqualTo(LeadSources.WHATSAPP);
        assertThat(l.getSubject()).isEqualTo("WhatsApp");
        assertThat(l.getContactName()).isEqualTo("Айгерим");
        assertThat(l.getMessage()).isEqualTo("Нужен облучатель ОБН-150");
        assertThat(l.getChat().getId()).isEqualTo(c.getId());
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Сообщение в WhatsApp");
    }

    @Test
    void nextMessagesGoToTheSameLead() {
        String chat = personal();
        write(in(chat, "Здравствуйте"));
        ChatIngestWriter.Outcome second = write(in(chat, "Нужен облучатель"));

        assertThat(second.createdLeadId()).isNull();
        assertThat(leadsOf(second)).hasSize(1);
        assertThat(messageRepository.findLatest(second.chatId(), PageRequest.of(0, 10))).hasSize(2);
    }

    @Test
    void phoneReplyTakesNewLeadIntoWork() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(in(chat, "Здравствуйте"));
        write(out(chat, "Добрый день! Подготовим КП"));

        Lead l = leadRepository.findById(first.createdLeadId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getEvents()).last().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(LeadEventType.STATUS);
            assertThat(e.getAuthor()).isNull();
            assertThat(e.getBody()).contains("ответ клиенту в WhatsApp с телефона");
        });
    }

    @Test
    void siteLeadWithSamePhoneIsContinuedAndItsSiteStatusQueued() {
        String chat = personal();
        Lead site = intake.ingest(new IncomingLead(LeadSources.WESTMED, "price:zz-" + id(), LeadChannel.SITE,
                "Заявка с сайта", OffsetDateTime.now().minusDays(1), "Айгерим", "+" + chat.substring(0, 11), null, null,
                "Нужен аппарат", List.of(), LeadStatus.NEW, "NEW", null)).orElseThrow();

        ChatIngestWriter.Outcome o = write(in(chat, "Это я оставляла заявку на сайте"));
        write(out(chat, "Видим заявку, работаем"));

        assertThat(o.createdLeadId()).isNull();
        Lead l = leadRepository.findById(site.getId()).orElseThrow();
        assertThat(l.getChat().getId()).isEqualTo(o.chatId());
        assertThat(l.getStatus()).isEqualTo(LeadStatus.IN_WORK);
        assertThat(l.getExtStatusPending()).isEqualTo("PROCESSED");
    }

    @Test
    void outgoingFirstCreatesNoLead() {
        ChatIngestWriter.Outcome o = write(out(personal(), "Добрый день, это West-Med"));

        assertThat(o.createdLeadId()).isNull();
        assertThat(leadsOf(o)).isEmpty();
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 5))).hasSize(1);
    }

    @Test
    void messageAfterClosedLeadStartsNewLead() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(in(chat, "Нужен облучатель"));
        leadService.close(first.createdLeadId(), LeadCloseReason.ANSWERED, null, "admin");

        ChatIngestWriter.Outcome again = write(in(chat, "А теперь нужен рециркулятор"));

        assertThat(again.createdLeadId()).isNotNull().isNotEqualTo(first.createdLeadId());
    }

    @Test
    void convertedLeadIsContinuedWithin30DaysButNotAfter() {
        String recent = personal();
        ChatIngestWriter.Outcome r = write(in(recent, "Нужен облучатель"));
        age(r, 10);
        assertThat(write(in(recent, "Когда будет КП?")).createdLeadId()).isNull();

        String stale = personal();
        ChatIngestWriter.Outcome s = write(in(stale, "Нужен облучатель"));
        age(s, 40);
        assertThat(write(in(stale, "Теперь нужен рециркулятор")).createdLeadId()).isNotNull();
    }

    /** Обращение чата — «Заявка создана», вся активность N дней назад (через SQL: @PreUpdate переписал бы updated_at). */
    private void age(ChatIngestWriter.Outcome o, int days) {
        em.flush();
        em.createNativeQuery("update lead set status = 'CONVERTED', updated_at = now() - (:d * interval '1 day') where chat_id = :c")
                .setParameter("d", days).setParameter("c", o.chatId()).executeUpdate();
        em.createNativeQuery("update chat set last_message_at = now() - (:d * interval '1 day') where id = :c")
                .setParameter("d", days).setParameter("c", o.chatId()).executeUpdate();
        em.clear();
    }

    @Test
    void notClientChatNeverStartsLeads() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(out(chat, "Привет, это Данияр"));
        chatRepository.findById(o.chatId()).orElseThrow().setNotClient(true);

        assertThat(write(in(chat, "Ок, до завтра")).createdLeadId()).isNull();
        assertThat(leadsOf(o)).isEmpty();
    }

    @Test
    void groupMessagesAreStoredWithoutLeads() {
        ChatIngestWriter.Outcome o = write(parse(GreenApiJson.group(group(), "Коллеги West-Med", "77025556677@c.us",
                "Данияр", id(), clock += 60, GreenApiJson.text("Кто едет в Уральск?"))));

        Chat c = chatRepository.findById(o.chatId()).orElseThrow();
        assertThat(c.isGroup()).isTrue();
        assertThat(c.getTitle()).isEqualTo("Коллеги West-Med");
        assertThat(leadsOf(o)).isEmpty();
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 1)).get(0).getSenderName()).isEqualTo("Данияр");
    }

    @Test
    void redeliveredMessageIsNotDuplicated() {
        ParsedNotification.Message m = in(personal(), "Здравствуйте");
        ChatIngestWriter.Outcome first = write(m);
        ChatIngestWriter.Outcome again = write(m);

        assertThat(again.duplicate()).isTrue();
        assertThat(messageRepository.findLatest(first.chatId(), PageRequest.of(0, 5))).hasSize(1);
        assertThat(leadsOf(first)).hasSize(1);
    }

    @Test
    void siteCartTemplateBecomesQuoteRequestWithItems() {
        List<IncomingLead.Item> cart = List.of(
                new IncomingLead.Item("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn-150"),
                new IncomingLead.Item("Рециркулятор СН-111-130", null, 1, null));

        ChatIngestWriter.Outcome o = writer.write(in(personal(),
                "Здравствуйте! Интересует следующее оборудование:\n\n1. Облучатель ОБН-150 (x2)\n2. Рециркулятор СН-111-130\n\nПрошу подготовить коммерческое предложение."),
                null, cart);

        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getSubject()).isEqualTo("Запрос КП");
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getBrand, LeadItem::getQuantity)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2), tuple("Рециркулятор СН-111-130", null, 1));
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Запрос КП, 2 поз. — WhatsApp");
    }

    @Test
    void editChangesOriginalAndDeleteKeepsText() {
        String chat = personal();
        ParsedNotification.Message original = in(chat, "Нужен один облучатель");
        ChatIngestWriter.Outcome o = write(original);
        write(parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.edited(original.idMessage(), "Нужны два облучателя"))));

        ChatMessage edited = messageRepository.findByChatIdAndExternalId(o.chatId(), original.idMessage()).orElseThrow();
        assertThat(edited.getBody()).isEqualTo("Нужны два облучателя");
        assertThat(edited.isEdited()).isTrue();
        assertThat(messageRepository.findLatest(o.chatId(), PageRequest.of(0, 5))).hasSize(1);

        writer.applyDelete(new ParsedNotification.Delete(GreenApiJson.ACCOUNT, chat, original.idMessage()));

        ChatMessage deleted = messageRepository.findByChatIdAndExternalId(o.chatId(), original.idMessage()).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getBody()).isEqualTo("Нужны два облучателя");
    }

    @Test
    void fileIsStoredOrMarkedNotStored() {
        String chat = personal();
        ParsedNotification.Message photo = parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/p", "photo.jpg", "image/jpeg", "")));
        ParsedNotification.Message video = parse(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("videoMessage", "https://x/v", "big.mp4", "video/mp4", "")));

        ChatIngestWriter.Outcome p = writer.write(photo, IncomingFile.stored(photo.file(), new byte[]{9, 8, 7}), List.of());
        ChatIngestWriter.Outcome v = writer.write(video, IncomingFile.notStored(video.file(), AttachmentNotStoredReason.TOO_LARGE), List.of());

        assertThat(attachmentRepository.findMetaByMessageIds(List.of(p.messageId(), v.messageId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::sizeBytes, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("photo.jpg", 3L, null), tuple("big.mp4", null, AttachmentNotStoredReason.TOO_LARGE));
        assertThat(chatRepository.findById(p.chatId()).orElseThrow().getLastMessagePreview()).isEqualTo("[видео]");
        assertThat(leadRepository.findById(p.createdLeadId()).orElseThrow().getMessage()).isEqualTo("[фото]");
    }

    @Test
    void hiddenNumberChatIsContinuedByChatNotPhone() {
        String hidden = ThreadLocalRandom.current().nextLong(100_000_000_000_000L, 999_999_999_999_999L) + "@lid";
        ChatIngestWriter.Outcome first = write(in(hidden, "Добрый день"));
        ChatIngestWriter.Outcome second = write(in(hidden, "Нужен аппарат"));

        assertThat(leadRepository.findById(first.createdLeadId()).orElseThrow().getContactPhone()).isNull();
        assertThat(second.createdLeadId()).isNull();
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.ChatIngestWriterTest'`
Expected: FAIL — компиляция (`ChatIngestWriter`, `IncomingFile`, `LeadSources.WHATSAPP` не найдены).

- [ ] **Step 3: Источник `whatsapp` и подпись ленты**

В `integration/lead/LeadSources.java` добавить константу рядом с `MANUAL` и метод после `writesBack`:

```java
    public static final String WHATSAPP = "whatsapp";
```

```java
    /** Подпись источника в ленте обращения: «westmed.kz», «WhatsApp»… (у ручного ввода своя подпись). */
    public static String label(String source) {
        return WHATSAPP.equals(source) ? "WhatsApp" : source;
    }
```

В `service/LeadIntakeService.java` заменить метод `receivedText` целиком:

```java
    private static String receivedText(IncomingLead in, int itemCount) {
        String base = in.subject() + (itemCount > 0 ? ", " + itemCount + " поз." : "");
        if (LeadSources.MANUAL.equals(in.source())) return base + " — внесено вручную";
        String label = LeadSources.label(in.source());
        // тема «WhatsApp» от источника «WhatsApp» дала бы «WhatsApp — WhatsApp»
        return base.equals(label) ? "Сообщение в " + label : base + " — " + label;
    }
```

- [ ] **Step 4: Авто-«В работу»**

В `service/LeadService.java` после метода `take` добавить:

```java
    /**
     * «Взять в работу» без человека — ответ клиенту в WhatsApp с телефона (спека whatsapp-chats §5.2 п.3).
     * Тот же переход, что у кнопки: событие STATUS и запись статуса на сайт для заявок westmed.kz.
     */
    @Transactional
    public void takeAutomatically(Lead lead, String note) {
        if (lead.getStatus() != LeadStatus.NEW) return;
        transition(lead, LeadStatus.IN_WORK, null, note);
    }
```

- [ ] **Step 5: Файл для записи**

`integration/greenapi/IncomingFile.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.entity.AttachmentNotStoredReason;

/** Файл сообщения для записи (спека §6.4). Инвариант: content == null ⇔ notStoredReason != null. */
public record IncomingFile(String fileName, String mimeType, byte[] content, AttachmentNotStoredReason notStoredReason) {

    public static IncomingFile stored(FileRef ref, byte[] content) {
        return new IncomingFile(ref.fileName(), ref.mimeType(), content, null);
    }

    public static IncomingFile notStored(FileRef ref, AttachmentNotStoredReason reason) {
        return new IncomingFile(ref.fileName(), ref.mimeType(), null, reason);
    }
}
```

- [ ] **Step 6: Правило «открытое обращение чата»**

`service/ChatLeadRules.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * «Открытое обращение чата» (спека whatsapp-chats §5.1): того же рынка, по чату ИЛИ по номеру — склейка через
 * каналы (заявка с сайта + WhatsApp = одно обращение); NEW/IN_WORK — всегда, CONVERTED — если активность не
 * старше N дней. Вызывать ДО сдвига chat.lastMessageAt текущим сообщением.
 */
@Service
public class ChatLeadRules {

    private static final Set<LeadStatus> CANDIDATES = EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED);

    private final LeadRepository leadRepository;
    private final int convertedDays;

    public ChatLeadRules(LeadRepository leadRepository,
                         @Value("${chats.whatsapp.lead-converted-days:30}") int convertedDays) {
        this.leadRepository = leadRepository;
        this.convertedDays = convertedDays;
    }

    public Optional<Lead> findOpenLead(Chat chat, OffsetDateTime at) {
        Map<Long, Lead> byId = new LinkedHashMap<>();
        if (chat.getId() != null) {
            leadRepository.findByChatIdAndStatusIn(chat.getId(), CANDIDATES).forEach(l -> byId.put(l.getId(), l));
        }
        if (chat.getPhoneNorm() != null) {
            leadRepository.findByPhoneNormAndStatusIn(chat.getPhoneNorm(), CANDIDATES).forEach(l -> byId.put(l.getId(), l));
        }
        OffsetDateTime cutoff = at.minusDays(convertedDays);
        return byId.values().stream()
                .filter(l -> l.getStatus() != LeadStatus.CONVERTED || !activity(l).isBefore(cutoff))
                .max(Comparator.comparing(Lead::getReceivedAt).thenComparing(Lead::getId));
    }

    /** Позднейшее из правки обращения и последнего сообщения его чата. */
    static OffsetDateTime activity(Lead l) {
        OffsetDateTime a = l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getReceivedAt();
        OffsetDateTime chatLast = l.getChat() == null ? null : l.getChat().getLastMessageAt();
        return chatLast != null && chatLast.isAfter(a) ? chatLast : a;
    }
}
```

- [ ] **Step 7: Запись**

`service/ChatIngestWriter.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.greenapi.ChatKind;
import com.vladoose.nir.integration.greenapi.IncomingFile;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.util.PhoneNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Запись уведомления WhatsApp — одна транзакция на сообщение (спека whatsapp-chats §5, §6). Сеть (файл, бренды
 * корзины) делает вызывающий ДО транзакции. Дубль проверяется ЯВНО: очередь читает один поток, «гонки вставок»
 * нет, поэтому DataIntegrityViolationException здесь не глотается (урок разбора 2026-09-28).
 */
@Service
public class ChatIngestWriter {

    static final String AUTO_TAKE_NOTE = "ответ клиенту в WhatsApp с телефона";
    private static final int PREVIEW_MAX = 300;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatLeadRules rules;
    private final LeadIntakeService intake;
    private final LeadService leadService;

    public ChatIngestWriter(ChatRepository chatRepository, ChatMessageRepository messageRepository,
                            ChatAttachmentRepository attachmentRepository, ChatLeadRules rules,
                            LeadIntakeService intake, LeadService leadService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.rules = rules;
        this.intake = intake;
        this.leadService = leadService;
    }

    /** duplicate — такое сообщение уже записано; createdLeadId — создано новое обращение. */
    public record Outcome(boolean duplicate, Long chatId, Long messageId, Long createdLeadId) {}

    @Transactional
    public Outcome write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cartItems) {
        Chat chat = upsertChat(m);
        if (m.isEdit()) {
            Optional<ChatMessage> original = messageRepository.findByChatIdAndExternalId(chat.getId(), m.editOf());
            if (original.isPresent()) {
                original.get().setBody(m.body());
                original.get().setEdited(true);
                return new Outcome(false, chat.getId(), original.get().getId(), null);
            }
            // исходного нет (пришло до подключения) — сохраняем правку как новое сообщение с пометкой
        }
        if (messageRepository.existsByChatIdAndExternalId(chat.getId(), m.idMessage())) {
            return new Outcome(true, chat.getId(), null, null);
        }
        ChatMessage msg = messageRepository.save(ChatMessage.builder()
                .chat(chat).externalId(m.idMessage()).direction(m.direction())
                .senderName(trunc(m.senderName(), 255)).type(m.type()).body(m.body())
                .sentAt(m.sentAt()).edited(m.isEdit()).build());
        if (file != null) {
            attachmentRepository.save(ChatAttachment.builder().message(msg)
                    .fileName(trunc(file.fileName(), 255)).mimeType(trunc(file.mimeType(), 100))
                    .sizeBytes(file.content() == null ? null : (long) file.content().length)
                    .content(file.content()).notStoredReason(file.notStoredReason()).build());
        }
        // правила — ДО сдвига lastMessageAt: активность обращения меряется по ПРОШЛЫМ сообщениям (спека §5.1)
        Long createdLeadId = m.isEdit() ? null : applyLeadRules(chat, m, cartItems);
        if (chat.getLastMessageAt() == null || !m.sentAt().isBefore(chat.getLastMessageAt())) {
            chat.setLastMessageAt(m.sentAt());
            chat.setLastMessagePreview(trunc(m.displayText().strip(), PREVIEW_MAX));
        }
        return new Outcome(false, chat.getId(), msg.getId(), createdLeadId);
    }

    /** «Удалено отправителем»: пометка, текст сохраняется. Неизвестное сообщение — пропускаем. */
    @Transactional
    public void applyDelete(ParsedNotification.Delete d) {
        chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, d.account(), d.chatId())
                .flatMap(c -> messageRepository.findByChatIdAndExternalId(c.getId(), d.deletedId()))
                .ifPresent(msg -> msg.setDeleted(true));
    }

    private Chat upsertChat(ParsedNotification.Message m) {
        Optional<Chat> found = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, m.account(), m.chatId());
        if (found.isEmpty()) {
            return chatRepository.save(Chat.builder()
                    .market(MarketContext.get())   // пред-штамп (defense-in-depth к листенеру)
                    .channel(LeadChannel.WHATSAPP).account(m.account()).externalChatId(m.chatId())
                    .group(m.kind() == ChatKind.GROUP).phoneNorm(phoneNorm(m.phone()))
                    .title(m.chatName() != null ? trunc(m.chatName().strip(), 255) : m.phone())
                    .build());
        }
        Chat chat = found.get();
        String name = m.chatName();
        // имя — из входящих (несут имя из контактов телефона); из исходящих — только если имени ещё нет
        if (name != null && !name.isBlank() && (m.direction() == LeadDirection.IN || chat.getTitle() == null)
                && !name.strip().equals(chat.getTitle())) {
            chat.setTitle(trunc(name.strip(), 255));
        }
        return chat;
    }

    /** Спека §5.2: группы и «не клиент» — без обращений; открытое — продолжаем; нет — входящее создаёт новое. */
    private Long applyLeadRules(Chat chat, ParsedNotification.Message m, List<IncomingLead.Item> cartItems) {
        if (chat.isGroup() || chat.isNotClient()) return null;
        Optional<Lead> open = rules.findOpenLead(chat, m.sentAt());
        if (open.isPresent()) {
            Lead lead = open.get();
            if (lead.getChat() == null) lead.setChat(chat);
            if (m.direction() == LeadDirection.OUT) leadService.takeAutomatically(lead, AUTO_TAKE_NOTE);
            return null;
        }
        if (m.direction() != LeadDirection.IN) return null;   // написали первыми мы — обращение не создаём
        boolean cart = cartItems != null && !cartItems.isEmpty();
        IncomingLead in = new IncomingLead(LeadSources.WHATSAPP, "wa:" + m.idMessage(), LeadChannel.WHATSAPP,
                cart ? "Запрос КП" : "WhatsApp", m.sentAt(), m.chatName(), m.phone(), null, null,
                m.displayText(), cart ? cartItems : List.of(), LeadStatus.NEW, null, null);
        return intake.ingest(in).map(lead -> {
            lead.setChat(chat);
            return lead.getId();
        }).orElse(null);
    }

    /** «+77011234567» → нормализованный +7; иностранный номер — как есть (он уже международный). */
    static String phoneNorm(String phone) {
        if (phone == null) return null;
        String n = PhoneNormalizer.normalize(phone);
        return n != null ? n : trunc(phone, 20);
    }
}
```

- [ ] **Step 8: Прогнать тест**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.ChatIngestWriterTest'`
Expected: PASS (13 тестов).

- [ ] **Step 9: Мутации — по одной, каждая должна ронять ровно свой тест**

| Мутация в `ChatIngestWriter`/`ChatLeadRules` | Должен покраснеть |
|---|---|
| убрать `chat.isGroup() \|\|` | `groupMessagesAreStoredWithoutLeads` |
| убрать `\|\| chat.isNotClient()` | `notClientChatNeverStartsLeads` |
| фильтр CONVERTED заменить на `true` | `convertedLeadIsContinuedWithin30DaysButNotAfter` |
| удалить проверку `existsByChatIdAndExternalId` | `redeliveredMessageIsNotDuplicated` |
| удалить вызов `takeAutomatically` | `phoneReplyTakesNewLeadIntoWork` и `siteLeadWithSamePhoneIsContinuedAndItsSiteStatusQueued` |
| в `findOpenLead` не искать по номеру | `siteLeadWithSamePhoneIsContinuedAndItsSiteStatusQueued` |

После каждой — вернуть код и убедиться, что всё снова зелёное.

- [ ] **Step 10: Регресс обращений**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lead.*' --tests 'com.vladoose.nir.integration.westmed.*'`
Expected: PASS (подписи ленты `westmed.kz` и «внесено вручную» не изменились).

- [ ] **Step 11: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/lead/LeadSources.java \
  src/main/java/com/vladoose/nir/service/LeadIntakeService.java src/main/java/com/vladoose/nir/service/LeadService.java \
  src/main/java/com/vladoose/nir/integration/greenapi/IncomingFile.java src/main/java/com/vladoose/nir/service/ChatLeadRules.java \
  src/main/java/com/vladoose/nir/service/ChatIngestWriter.java src/test/java/com/vladoose/nir/chat/ChatIngestWriterTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): запись сообщений чата и правила обращений — склейка по номеру, 30 дней, «не клиент», авто-«В работу»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Цикл приёма, состояние подключения, планировщик

**Files:**
- Create: `dto/response/WhatsappStatusResponse.java`
- Create: `integration/greenapi/WhatsappStatusHolder.java`, `WhatsappChatSync.java`, `WhatsappChatScheduler.java`
- Modify: `src/main/resources/application.yaml` (блок `chats.whatsapp`)
- Create (тест): `integration/greenapi/FakeGreenApiClient.java`
- Test: `integration/greenapi/WhatsappChatSyncTest.java`, `integration/greenapi/WhatsappChatSchedulerTest.java`

**Interfaces:**
- Consumes: Task 2 (`GreenApiClient` и исключения), Task 3 (парсер), Task 4 (`SiteCartMessageParser`), Task 5 (`ChatIngestWriter`, `IncomingFile`), существующие `WestmedClient`, `WestmedProductLookup`, `FakeWestmedClient` (тест).
- Produces:
  - `WhatsappStatusResponse` (`@Data`): `enabled, configured, state, number, lastMessageAt, warnings (List<String>), lastError`
  - `WhatsappStatusHolder` (`@Component`): константы `WEBHOOK_URL_SET, INCOMING_OFF, OUTGOING_PHONE_OFF, QUOTA_EXCEEDED, MESSAGE_DROPPED`; `setState(String)`, `settingsChecked(GreenApiSettings)`, `messageSeen(OffsetDateTime)`, `quotaExceeded()`, `messageDropped()`, `setLastError(String)`, `lastError()`, `snapshot(boolean enabled, boolean configured) : WhatsappStatusResponse`
  - `WhatsappChatSync(GreenApiClient, ChatIngestWriter, WestmedClient, WhatsappStatusHolder, String siteUrl, int maxFileMb, int receiveTimeoutSec)`: `boolean drain(int maxNotifications)` — `false`, если проход оборван сбоем (нужна пауза)
  - `WhatsappChatScheduler(WhatsappChatSync, GreenApiClient, WhatsappStatusHolder, boolean enabled, String market, long authBackoffMs, long errorBackoffMs, long stateRefreshMs, long settingsRefreshMs)`: `tick()` (`@Scheduled`), `cycle()` (package-private — тесты зовут напрямую), `status() : WhatsappStatusResponse`

- [ ] **Step 1: Фейк Green-API**

`src/test/java/com/vladoose/nir/integration/greenapi/FakeGreenApiClient.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Управляемый фейк Green-API: очередь отдаёт ГОЛОВУ, пока её не удалят (как настоящая), файлы по URL. Без сети. */
public class FakeGreenApiClient implements GreenApiClient {

    public boolean configured = true;
    public final Deque<GreenApiReceived> queue = new ArrayDeque<>();
    public final List<Long> deleted = new ArrayList<>();
    public final Map<String, byte[]> files = new HashMap<>();
    /** url → сколько раз ещё упасть на скачивании */
    public final Map<String, Integer> downloadFailuresLeft = new HashMap<>();
    public final List<String> downloads = new ArrayList<>();
    public String state = "authorized";
    public GreenApiSettings settings = new GreenApiSettings(GreenApiJson.WID, "", true, true);
    public RuntimeException failReceiveWith;
    public int receiveCalls;
    private long nextReceipt = 1;

    public long enqueue(ObjectNode body) {
        long id = nextReceipt++;
        queue.addLast(new GreenApiReceived(id, body));
        return id;
    }

    @Override
    public boolean isConfigured() { return configured; }

    @Override
    public GreenApiReceived receive(int receiveTimeoutSec) {
        receiveCalls++;
        if (failReceiveWith != null) throw failReceiveWith;
        return queue.peekFirst();
    }

    @Override
    public void delete(long receiptId) {
        deleted.add(receiptId);
        queue.removeIf(r -> r.receiptId() == receiptId);
    }

    @Override
    public String state() { return state; }

    @Override
    public GreenApiSettings settings() { return settings; }

    @Override
    public byte[] download(String url, long maxBytes) {
        downloads.add(url);
        int left = downloadFailuresLeft.getOrDefault(url, 0);
        if (left > 0) {
            downloadFailuresLeft.put(url, left - 1);
            throw new GreenApiException(0, "Green-API: файл не скачался: ConnectException");
        }
        byte[] b = files.get(url);
        if (b == null) throw new GreenApiException(404, "Green-API: HTTP 404 при скачивании файла");
        if (b.length > maxBytes) throw new FileTooLargeException(maxBytes);
        return b;
    }
}
```

- [ ] **Step 2: Написать падающие тесты**

`src/test/java/com/vladoose/nir/integration/greenapi/WhatsappChatSyncTest.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.ChatLeadRules;
import com.vladoose.nir.service.LeadIntakeService;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class WhatsappChatSyncTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatLeadRules rules;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;

    FakeGreenApiClient fake;
    FakeWestmedClient westmed;
    WhatsappStatusHolder status;
    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGreenApiClient();
        westmed = new FakeWestmedClient();
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    /** Предел файла — 1 МБ, чтобы тест «больше предела» не гонял 25 МБ. */
    private WhatsappChatSync sync(ChatIngestWriter w) {
        return new WhatsappChatSync(fake, w, westmed, status, "https://westmed.kz/", 1, 5);
    }
    private WhatsappChatSync sync() { return sync(writer); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String group() { return "120363" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L) + "@g.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private Chat chat(String externalChatId) {
        return chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, GreenApiJson.ACCOUNT, externalChatId).orElseThrow();
    }

    private ChatMessage lastMessage(String externalChatId) {
        return messageRepository.findLatest(chat(externalChatId).getId(), PageRequest.of(0, 1)).get(0);
    }

    @Test
    void drainWritesEachNotificationThenDeletesIt() {
        String a = personal();
        String b = personal();
        long r1 = fake.enqueue(GreenApiJson.incoming(a, "Айгерим", id(), clock += 60, GreenApiJson.text("Здравствуйте")));
        long r2 = fake.enqueue(GreenApiJson.incoming(b, "Ерлан", id(), clock += 60, GreenApiJson.text("Нужен УЗИ")));

        assertThat(sync().drain(10)).isTrue();

        assertThat(fake.deleted).containsExactly(r1, r2);
        assertThat(lastMessage(a).getBody()).isEqualTo("Здравствуйте");
        assertThat(lastMessage(b).getBody()).isEqualTo("Нужен УЗИ");
        assertThat(status.snapshot(true, true).getLastMessageAt()).isNotNull();
    }

    @Test
    void failedWriteLeavesNotificationInQueueAndThirdFailureDropsIt() {
        ChatIngestWriter broken = new ChatIngestWriter(chatRepository, messageRepository, attachmentRepository, rules, intake, leadService) {
            @Override
            public Outcome write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cart) {
                throw new IllegalStateException("сбой записи");
            }
        };
        long r = fake.enqueue(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));
        WhatsappChatSync s = sync(broken);

        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isFalse();
        assertThat(fake.deleted).isEmpty();                       // не записано — ждёт в очереди
        assertThat(status.lastError()).contains("сбой записи");

        assertThat(s.drain(10)).isTrue();                          // третья неудача — «ядовитое», удаляем
        assertThat(fake.deleted).containsExactly(r);
        assertThat(status.snapshot(true, true).getWarnings()).contains(WhatsappStatusHolder.MESSAGE_DROPPED);
    }

    @Test
    void stateQuotaAndUnknownNotificationsAreAcknowledged() {
        fake.enqueue(GreenApiJson.state("blocked"));
        fake.enqueue(GreenApiJson.quota());
        fake.enqueue(GreenApiJson.webhook("outgoingMessageStatus"));

        assertThat(sync().drain(10)).isTrue();

        assertThat(fake.deleted).hasSize(3);
        WhatsappStatusResponse s = status.snapshot(true, true);
        assertThat(s.getState()).isEqualTo("blocked");
        assertThat(s.getWarnings()).contains(WhatsappStatusHolder.QUOTA_EXCEEDED);
    }

    @Test
    void cartTemplateGetsBrandsFromSiteCatalog() {
        westmed.productsBySearch.put("облучатель обн-150", List.of(new WestmedProduct("Облучатель ОБН-150", "obn-150", "Азов")));
        String chat = personal();
        fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text(
                "Здравствуйте! Интересует следующее оборудование:\n\n1. Облучатель ОБН-150 (x2)\n\nПрошу подготовить коммерческое предложение.")));

        sync().drain(10);

        Lead l = leadRepository.findByChatIdIn(List.of(chat(chat).getId())).get(0);
        assertThat(l.getSubject()).isEqualTo("Запрос КП");
        assertThat(l.getItems()).extracting(LeadItem::getName, LeadItem::getBrand, LeadItem::getQuantity, LeadItem::getProductUrl)
                .containsExactly(tuple("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn-150"));
    }

    @Test
    void filesAreDownloadedForPersonalChatsOnly() {
        String url = "https://files.example/photo.jpg";
        fake.files.put(url, new byte[]{1, 2, 3});
        String chat = personal();
        String grp = group();
        fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.file("imageMessage", url, "photo.jpg", "image/jpeg", "")));
        fake.enqueue(GreenApiJson.group(grp, "Коллеги", "77025556677@c.us", "Данияр", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://files.example/group.jpg", "group.jpg", "image/jpeg", "")));

        sync().drain(10);

        assertThat(fake.downloads).containsExactly(url);
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(chat).getId(), lastMessage(grp).getId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("photo.jpg", null), tuple("group.jpg", AttachmentNotStoredReason.GROUP));
    }

    @Test
    void fileOverLimitIsNotStored() {
        String url = "https://files.example/big.pdf";
        fake.files.put(url, new byte[1024 * 1024 + 1]);
        String chat = personal();
        fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.file("documentMessage", url, "big.pdf", "application/pdf", "ТЗ")));

        sync().drain(10);

        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(chat).getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.TOO_LARGE);
    }

    @Test
    void failingDownloadIsRetriedThenMessageKeptWithoutFile() {
        String url = "https://files.example/flaky.pdf";
        fake.files.put(url, new byte[]{1});
        fake.downloadFailuresLeft.put(url, 5);
        String chat = personal();
        long r = fake.enqueue(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("documentMessage", url, "ТЗ.pdf", "application/pdf", "Вот ТЗ")));
        WhatsappChatSync s = sync();

        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isTrue();

        assertThat(fake.deleted).containsExactly(r);
        ChatMessage m = lastMessage(chat);
        assertThat(m.getBody()).isEqualTo("Вот ТЗ");
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(m.getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.DOWNLOAD_FAILED);
    }

    @Test
    void rejectedKeyIsNotCountedAsBrokenNotification() {
        fake.enqueue(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));
        fake.failReceiveWith = new GreenApiAuthException(401, "Green-API отклонил ключ (HTTP 401) при приёме сообщений");

        assertThatThrownBy(() -> sync().drain(10)).isInstanceOf(GreenApiAuthException.class);
        assertThat(fake.deleted).isEmpty();
    }
}
```

`src/test/java/com/vladoose/nir/integration/greenapi/WhatsappChatSchedulerTest.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** cycle() зовётся напрямую в потоке теста: так он входит в транзакцию теста и откатывается. */
@SpringBootTest
@Transactional
class WhatsappChatSchedulerTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;

    FakeGreenApiClient fake;
    WhatsappStatusHolder status;

    @BeforeEach
    void setUp() {
        fake = new FakeGreenApiClient();
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    private WhatsappChatScheduler scheduler(boolean enabled) {
        WhatsappChatSync sync = new WhatsappChatSync(fake, writer, new FakeWestmedClient(), status, "https://westmed.kz", 25, 5);
        return new WhatsappChatScheduler(sync, fake, status, enabled, "KZ", 600_000, 30_000, 300_000, 3_600_000);
    }

    @Test
    void cycleWritesChatsOfConfiguredMarketEvenOnDefaultThread() {
        String chatId = "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
        fake.enqueue(GreenApiJson.incoming(chatId, "Айгерим", "ID-" + System.nanoTime(), Instant.now().getEpochSecond(),
                GreenApiJson.text("Здравствуйте")));
        MarketContext.clear();   // у фонового потока рынка нет — дефолт RF (§6 CLAUDE.md)

        scheduler(true).cycle();

        MarketContext.set(Market.RF);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, GreenApiJson.ACCOUNT, chatId)).isEmpty();
        MarketContext.set(Market.KZ);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, GreenApiJson.ACCOUNT, chatId))
                .get().extracting(Chat::getMarket).isEqualTo(Market.KZ);
    }

    @Test
    void statusShowsStateNumberAndSettingsWarnings() {
        fake.settings = new GreenApiSettings("77000000001@c.us", "https://hooks.example/wa", true, false);
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();

        WhatsappStatusResponse st = s.status();
        assertThat(st.isEnabled()).isTrue();
        assertThat(st.isConfigured()).isTrue();
        assertThat(st.getState()).isEqualTo("authorized");
        assertThat(st.getNumber()).isEqualTo("77000000001");
        assertThat(st.getWarnings()).containsExactly(WhatsappStatusHolder.WEBHOOK_URL_SET, WhatsappStatusHolder.OUTGOING_PHONE_OFF);
        assertThat(st.getLastError()).isNull();
    }

    @Test
    void rejectedKeyPausesFurtherCycles() {
        fake.failReceiveWith = new GreenApiAuthException(401,
                "Green-API отклонил ключ (HTTP 401) при приёме сообщений — проверьте idInstance и токен");
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();
        assertThat(s.status().getLastError()).contains("отклонил ключ").contains("повтор через 10 мин");
        int calls = fake.receiveCalls;

        fake.failReceiveWith = null;
        s.cycle();
        assertThat(fake.receiveCalls).isEqualTo(calls);   // пауза: в Green-API не ходили
    }

    @Test
    void missingCredentialsAreReportedWithoutCalls() {
        fake.configured = false;
        WhatsappChatScheduler s = scheduler(true);

        s.cycle();

        assertThat(s.status().getLastError()).contains("не заданы учётные данные Green-API");
        assertThat(fake.receiveCalls).isZero();
    }

    @Test
    void disabledSchedulerStillReportsStatus() {
        assertThat(scheduler(false).status().isEnabled()).isFalse();
    }
}
```

- [ ] **Step 3: Убедиться, что тесты падают**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.greenapi.WhatsappChat*'`
Expected: FAIL — компиляция (`WhatsappChatSync`, `WhatsappStatusHolder` … не найдены).

- [ ] **Step 4: DTO и хранилище состояния**

`dto/response/WhatsappStatusResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** Строка «WhatsApp …» на экранах «Чаты» и «Обращения» (спека whatsapp-chats §7). */
@Data
public class WhatsappStatusResponse {
    private boolean enabled;
    private boolean configured;
    private String state;
    private String number;
    private OffsetDateTime lastMessageAt;
    private List<String> warnings;
    private String lastError;
}
```

`integration/greenapi/WhatsappStatusHolder.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Состояние подключения WhatsApp для UI (спека §7). Пишут цикл приёма и планировщик (поток «whatsapp-chats»),
 * читает контроллер — поля volatile, список предупреждений заменяется целиком.
 */
@Component
public class WhatsappStatusHolder {

    public static final String WEBHOOK_URL_SET = "WEBHOOK_URL_SET";
    public static final String INCOMING_OFF = "INCOMING_OFF";
    public static final String OUTGOING_PHONE_OFF = "OUTGOING_PHONE_OFF";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String MESSAGE_DROPPED = "MESSAGE_DROPPED";

    /** Лимит тарифа и пропущенное сообщение показываем сутки: оба — события, а не состояние. */
    private static final Duration RECENT = Duration.ofHours(24);

    private volatile String state;
    private volatile String number;
    private volatile OffsetDateTime lastMessageAt;
    private volatile String lastError;
    private volatile OffsetDateTime quotaExceededAt;
    private volatile OffsetDateTime droppedAt;
    private volatile List<String> settingsWarnings = List.of();

    public void setState(String s) { state = s == null || s.isBlank() ? null : s; }

    public void settingsChecked(GreenApiSettings s) {
        String wid = s.wid() == null ? "" : s.wid().strip();
        int at = wid.indexOf('@');
        String digits = at < 0 ? wid : wid.substring(0, at);
        number = digits.isEmpty() ? null : digits;
        List<String> w = new ArrayList<>();
        if (s.webhookUrl() != null && !s.webhookUrl().isBlank()) w.add(WEBHOOK_URL_SET);
        if (!s.incomingWebhook()) w.add(INCOMING_OFF);
        if (!s.outgoingMessageWebhook()) w.add(OUTGOING_PHONE_OFF);
        settingsWarnings = List.copyOf(w);
    }

    public void messageSeen(OffsetDateTime at) {
        if (at != null && (lastMessageAt == null || at.isAfter(lastMessageAt))) lastMessageAt = at;
    }

    public void quotaExceeded() { quotaExceededAt = OffsetDateTime.now(); }

    public void messageDropped() { droppedAt = OffsetDateTime.now(); }

    public void setLastError(String e) { lastError = e; }

    public String lastError() { return lastError; }

    public WhatsappStatusResponse snapshot(boolean enabled, boolean configured) {
        WhatsappStatusResponse r = new WhatsappStatusResponse();
        r.setEnabled(enabled);
        r.setConfigured(configured);
        r.setState(state);
        r.setNumber(number);
        r.setLastMessageAt(lastMessageAt);
        r.setLastError(lastError);
        List<String> w = new ArrayList<>(settingsWarnings);
        OffsetDateTime cutoff = OffsetDateTime.now().minus(RECENT);
        if (quotaExceededAt != null && quotaExceededAt.isAfter(cutoff)) w.add(QUOTA_EXCEEDED);
        if (droppedAt != null && droppedAt.isAfter(cutoff)) w.add(MESSAGE_DROPPED);
        r.setWarnings(w);
        return r;
    }
}
```

- [ ] **Step 5: Цикл приёма**

`integration/greenapi/WhatsappChatSync.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.entity.AttachmentNotStoredReason;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.WestmedClient;
import com.vladoose.nir.integration.westmed.WestmedProductLookup;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.util.SiteCartMessageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проход по очереди Green-API (спека whatsapp-chats §6): получить → (скачать файл, найти бренды корзины — ВНЕ
 * транзакции) → записать (ChatIngestWriter, своя транзакция) → удалить из очереди. Удаляем ТОЛЬКО после записи:
 * сбой посередине → уведомление придёт снова, дубль отсечёт writer. Сам НЕ транзакционный; MarketContext ставит
 * вызывающий (WhatsappChatScheduler).
 */
@Service
public class WhatsappChatSync {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatSync.class);
    static final int MAX_ATTEMPTS = 3;

    private final GreenApiClient client;
    private final ChatIngestWriter writer;
    private final WestmedClient westmedClient;
    private final WhatsappStatusHolder status;
    private final String siteUrl;
    private final long maxFileBytes;
    private final int receiveTimeoutSec;
    /** receiptId → сколько раз подряд не приняли. В памяти: после рестарта счёт с нуля — это ≤ 2 лишние попытки. */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();

    public WhatsappChatSync(GreenApiClient client, ChatIngestWriter writer, WestmedClient westmedClient,
                            WhatsappStatusHolder status,
                            @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl,
                            @Value("${chats.whatsapp.max-file-mb:25}") int maxFileMb,
                            @Value("${chats.whatsapp.receive-timeout-s:20}") int receiveTimeoutSec) {
        this.client = client;
        this.writer = writer;
        this.westmedClient = westmedClient;
        this.status = status;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.maxFileBytes = maxFileMb * 1024L * 1024L;
        this.receiveTimeoutSec = receiveTimeoutSec;
    }

    /** До maxNotifications уведомлений или до пустой очереди. false — проход оборван сбоем уведомления (нужна пауза). */
    public boolean drain(int maxNotifications) {
        for (int i = 0; i < maxNotifications; i++) {
            GreenApiReceived r = client.receive(receiveTimeoutSec);
            if (r == null) return true;
            if (!process(r)) return false;
        }
        return true;
    }

    /** Одно уведомление. false — не принято и оставлено в очереди (Green-API отдаст его снова — голову очереди). */
    boolean process(GreenApiReceived r) {
        int attempt = failures.getOrDefault(r.receiptId(), 0) + 1;
        try {
            handle(GreenApiNotificationParser.parse(r.body()), attempt);
        } catch (GreenApiAuthException | GreenApiQuotaException e) {
            throw e;   // беда инстанса, а не уведомления: паузу ставит планировщик, попытку не считаем
        } catch (RuntimeException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (attempt < MAX_ATTEMPTS) {
                failures.put(r.receiptId(), attempt);
                status.setLastError("сообщение не принято (попытка " + attempt + " из " + MAX_ATTEMPTS + "): " + reason);
                return false;
            }
            // «ядовитое» уведомление не должно навсегда забить голову очереди
            status.messageDropped();
            status.setLastError("сообщение пропущено после " + MAX_ATTEMPTS + " попыток: " + reason);
            log.warn("WhatsApp: уведомление {} не принято {} раз подряд — удалено из очереди: {}", r.receiptId(), attempt, reason);
        }
        failures.remove(r.receiptId());
        client.delete(r.receiptId());
        return true;
    }

    private void handle(ParsedNotification p, int attempt) {
        if (p instanceof ParsedNotification.Message m) {
            IncomingFile file = fetchFile(m, attempt);
            writer.write(m, file, cartItems(m));
            status.messageSeen(m.sentAt());
        } else if (p instanceof ParsedNotification.Delete d) {
            writer.applyDelete(d);
        } else if (p instanceof ParsedNotification.State s) {
            status.setState(s.state());
        } else if (p instanceof ParsedNotification.QuotaExceeded) {
            status.quotaExceeded();
        }
        // Skip — только подтвердить (рекомендация Green-API)
    }

    private IncomingFile fetchFile(ParsedNotification.Message m, int attempt) {
        FileRef ref = m.file();
        if (ref == null) return null;
        if (m.kind() == ChatKind.GROUP) return IncomingFile.notStored(ref, AttachmentNotStoredReason.GROUP);
        if (ref.downloadUrl() == null) return IncomingFile.notStored(ref, AttachmentNotStoredReason.DOWNLOAD_FAILED);
        try {
            return IncomingFile.stored(ref, client.download(ref.downloadUrl(), maxFileBytes));
        } catch (FileTooLargeException e) {
            return IncomingFile.notStored(ref, AttachmentNotStoredReason.TOO_LARGE);
        } catch (GreenApiException e) {
            // последняя попытка — пишем сообщение без файла: текст важнее, файл остаётся в телефоне
            if (attempt >= MAX_ATTEMPTS) return IncomingFile.notStored(ref, AttachmentNotStoredReason.DOWNLOAD_FAILED);
            throw e;
        }
    }

    /** Позиции из шаблона корзины сайта (только входящие) с брендом из каталога westmed.kz; сбой поиска — без бренда. */
    private List<IncomingLead.Item> cartItems(ParsedNotification.Message m) {
        if (m.direction() != LeadDirection.IN || m.isEdit()) return List.of();
        List<SiteCartMessageParser.Line> lines = SiteCartMessageParser.parse(m.body());
        if (lines.isEmpty()) return List.of();
        WestmedProductLookup lookup = new WestmedProductLookup(westmedClient);
        return lines.stream().map(l -> {
            Optional<WestmedProduct> p = lookup.byName(l.name());
            return new IncomingLead.Item(l.name(), p.map(WestmedProduct::brandName).orElse(null), l.quantity(),
                    p.map(x -> siteUrl + "/product/" + x.slug()).orElse(null));
        }).toList();
    }
}
```

- [ ] **Step 6: Планировщик**

`integration/greenapi/WhatsappChatScheduler.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Market;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Приём WhatsApp (спека whatsapp-chats §6.1): тик раз в секунду ставит проход в СВОЙ однопоточный экзекьютор
 * «whatsapp-chats» — long-poll держит поток до 20 с, общий scheduling-1 занимать нельзя. Рынок ставится ЯВНО
 * и чистится в finally (§6 CLAUDE.md). Паузы: отказ ключа — 10 мин, прочие сбои — 30 с.
 */
@Component
public class WhatsappChatScheduler {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatScheduler.class);
    static final int MAX_PER_DRAIN = 200;

    private final WhatsappChatSync sync;
    private final GreenApiClient client;
    private final WhatsappStatusHolder status;
    private final boolean enabled;
    private final Market market;
    private final long authBackoffMs;
    private final long errorBackoffMs;
    private final long stateRefreshMs;
    private final long settingsRefreshMs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whatsapp-chats");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long pausedUntil;
    private volatile long nextStateCheck;
    private volatile long nextSettingsCheck;
    private volatile boolean credentialsWarned;

    public WhatsappChatScheduler(WhatsappChatSync sync, GreenApiClient client, WhatsappStatusHolder status,
                                 @Value("${chats.whatsapp.enabled:false}") boolean enabled,
                                 @Value("${chats.whatsapp.market:KZ}") String market,
                                 @Value("${chats.whatsapp.auth-backoff-ms:600000}") long authBackoffMs,
                                 @Value("${chats.whatsapp.error-backoff-ms:30000}") long errorBackoffMs,
                                 @Value("${chats.whatsapp.state-refresh-ms:300000}") long stateRefreshMs,
                                 @Value("${chats.whatsapp.settings-refresh-ms:3600000}") long settingsRefreshMs) {
        this.sync = sync;
        this.client = client;
        this.status = status;
        this.enabled = enabled;
        this.market = Market.fromHeader(market);
        this.authBackoffMs = authBackoffMs;
        this.errorBackoffMs = errorBackoffMs;
        this.stateRefreshMs = stateRefreshMs;
        this.settingsRefreshMs = settingsRefreshMs;
    }

    @Scheduled(fixedDelayString = "${chats.whatsapp.tick-ms:1000}",
               initialDelayString = "${chats.whatsapp.initial-delay-ms:20000}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            executor.submit(() -> {
                try {
                    cycle();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    /** Один проход. Пакетная видимость — ради тестов (зовётся напрямую, в транзакции теста). */
    void cycle() {
        if (!client.isConfigured()) {
            status.setLastError("не заданы учётные данные Green-API (WHATSAPP_API_URL / WHATSAPP_ID_INSTANCE / WHATSAPP_API_TOKEN)");
            if (!credentialsWarned) {
                log.warn("WhatsApp: {}", status.lastError());
                credentialsWarned = true;
            }
            return;
        }
        if (System.currentTimeMillis() < pausedUntil) return;   // пауза: lastError уже объясняет
        MarketContext.set(market);
        try {
            long now = System.currentTimeMillis();
            if (now >= nextSettingsCheck) {
                status.settingsChecked(client.settings());
                nextSettingsCheck = now + settingsRefreshMs;
            }
            if (now >= nextStateCheck) {
                status.setState(client.state());
                nextStateCheck = now + stateRefreshMs;
            }
            if (sync.drain(MAX_PER_DRAIN)) {
                status.setLastError(null);
            } else {
                pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            }
        } catch (GreenApiAuthException e) {
            pausedUntil = System.currentTimeMillis() + authBackoffMs;
            status.setLastError(e.getMessage() + " — повтор через " + Math.max(1, authBackoffMs / 60_000) + " мин");
            log.warn("WhatsApp: {}", status.lastError());
        } catch (GreenApiQuotaException e) {
            status.quotaExceeded();
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            status.setLastError(e.getMessage());
        } catch (RuntimeException e) {
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            status.setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.warn("WhatsApp: проход приёма не удался: {}", status.lastError());
        } finally {
            MarketContext.clear();
        }
    }

    public WhatsappStatusResponse status() {
        return status.snapshot(enabled, client.isConfigured());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 7: Конфигурация**

В конец `src/main/resources/application.yaml` добавить:

```yaml

# Чаты рабочего номера WhatsApp через Green-API (спека docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md).
# Выкл по умолчанию, как все интеграции. ⚠️ Очередь инстанса читает ОДИН потребитель: локальная АИС и прод —
# на РАЗНЫХ инстансах, иначе они забирают сообщения друг у друга.
chats:
  whatsapp:
    enabled: ${WHATSAPP_ENABLED:false}
    api-url: ${WHATSAPP_API_URL:}                  # «apiUrl» из кабинета Green-API, напр. https://7105.api.greenapi.com
    id-instance: ${WHATSAPP_ID_INSTANCE:}
    api-token: ${WHATSAPP_API_TOKEN:}              # секрет: только env; стоит в пути URL — URL нигде не печатаются
    market: ${WHATSAPP_MARKET:KZ}
    max-file-mb: ${WHATSAPP_MAX_FILE_MB:25}
    receive-timeout-s: 20
    tick-ms: 1000
    initial-delay-ms: ${WHATSAPP_INITIAL_DELAY_MS:20000}
    auth-backoff-ms: 600000
    error-backoff-ms: 30000
    state-refresh-ms: 300000
    settings-refresh-ms: 3600000
    lead-converted-days: 30
```

- [ ] **Step 8: Прогнать тесты**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.greenapi.*'`
Expected: PASS (HTTP-клиент 11, парсер 12, приём 8, планировщик 5).

- [ ] **Step 9: Мутации — по одной**

| Мутация | Должен покраснеть |
|---|---|
| в `process` перенести `client.delete(...)` ПЕРЕД `handle(...)` | `failedWriteLeavesNotificationInQueueAndThirdFailureDropsIt` |
| в `process` убрать ветку `attempt < MAX_ATTEMPTS` (удалять с первой неудачи) | `failedWriteLeavesNotificationInQueueAndThirdFailureDropsIt` |
| в `fetchFile` убрать строку про `ChatKind.GROUP` | `filesAreDownloadedForPersonalChatsOnly` |
| в `fetchFile` убрать `catch (FileTooLargeException …)` | `fileOverLimitIsNotStored` |
| в `cycle()` убрать `MarketContext.set(market)` | `cycleWritesChatsOfConfiguredMarketEvenOnDefaultThread` |

- [ ] **Step 10: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/WhatsappStatusResponse.java \
  src/main/java/com/vladoose/nir/integration/greenapi/WhatsappStatusHolder.java \
  src/main/java/com/vladoose/nir/integration/greenapi/WhatsappChatSync.java \
  src/main/java/com/vladoose/nir/integration/greenapi/WhatsappChatScheduler.java src/main/resources/application.yaml \
  src/test/java/com/vladoose/nir/integration/greenapi/FakeGreenApiClient.java \
  src/test/java/com/vladoose/nir/integration/greenapi/WhatsappChatSyncTest.java \
  src/test/java/com/vladoose/nir/integration/greenapi/WhatsappChatSchedulerTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): приём из очереди Green-API — удаление после записи, «ядовитые» уведомления, паузы, состояние

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: REST `/api/chats`

**Files:**
- Create: `dto/response/ChatLeadRef.java`, `ChatListItemResponse.java`, `ChatResponse.java`, `ChatMessageResponse.java`, `ChatAttachmentResponse.java`; `dto/request/ChatNotClientRequest.java`
- Create: `service/ChatService.java`, `controller/ChatController.java`
- Test: `chat/ChatApiTest.java`

**Interfaces:**
- Consumes: Task 1 (репозитории), Task 5 (`ChatLeadRules`, `ChatIngestWriter` — в тестах), Task 6 (`WhatsappChatScheduler.status()`), существующие `LeadIntakeService`, `PrivateRequestImportService.preview(byte[], String) : ImportPreviewResponse`.
- Produces:
  - `record ChatLeadRef(Long id, String status)`
  - `ChatListItemResponse` (`@Data`): `id, title, phone, group, notClient, lastMessageAt, lastMessagePreview, lead (ChatLeadRef)`
  - `ChatResponse` (`@Data`): `id, title, phone, group, notClient, lastMessageAt, lead (ChatLeadRef), leadOpen`
  - `ChatMessageResponse` (`@Data`): `id, direction, senderName, type, body, sentAt, edited, deleted, attachment (ChatAttachmentResponse)`
  - `ChatAttachmentResponse` (`@Data`): `id, fileName, mimeType, sizeBytes, stored, notStoredReason, excel, image`
  - `ChatService`: `list(String filter, String q)`, `get(Long)`, `messages(Long chatId, Long beforeId, int limit)`, `attachment(Long chatId, Long attachmentId) : ChatAttachment`, `previewAttachment(Long, Long) : ImportPreviewResponse`, `createLead(Long chatId, String author) : ChatResponse`, `setNotClient(Long, boolean) : ChatResponse`, пакетный `toResponses(List<ChatMessage> newestFirst)`, статические `isSafeImage(String)`, `isExcel(String, String)`
  - `ChatController` — эндпоинты из спеки §8 (кроме лидовых — они в Task 8)

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/chat/ChatApiTest.java`:

```java
package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.dto.request.ChatNotClientRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.IncomingFile;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ChatApiTest {

    @Autowired ChatController controller;
    @Autowired ChatIngestWriter writer;
    @Autowired LeadRepository leadRepository;

    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private ChatIngestWriter.Outcome write(ObjectNode body) {
        return writer.write((ParsedNotification.Message) GreenApiNotificationParser.parse(body), null, List.of());
    }

    private ChatIngestWriter.Outcome writeFile(ObjectNode body, byte[] bytes) {
        ParsedNotification.Message m = (ParsedNotification.Message) GreenApiNotificationParser.parse(body);
        return writer.write(m, IncomingFile.stored(m.file(), bytes), List.of());
    }

    private static ChatNotClientRequest notClient(boolean v) {
        ChatNotClientRequest r = new ChatNotClientRequest();
        r.setValue(v);
        return r;
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void listShowsChatsWithCurrentLeadAndFilters() {
        String client = personal();
        ChatIngestWriter.Outcome withLead = write(GreenApiJson.incoming(client, "Айгерим-" + client, id(), clock += 60,
                GreenApiJson.text("Нужен облучатель")));
        String colleague = personal();
        ChatIngestWriter.Outcome noLead = write(GreenApiJson.outgoing(colleague, "Данияр-" + colleague, id(), clock += 60,
                GreenApiJson.text("Привет")));

        ChatListItemResponse a = controller.list("ALL", null).stream()
                .filter(c -> c.getId().equals(withLead.chatId())).findFirst().orElseThrow();
        assertThat(a.getLead().status()).isEqualTo("NEW");
        assertThat(a.getLastMessagePreview()).isEqualTo("Нужен облучатель");

        assertThat(controller.list("WITH_LEAD", null)).extracting(ChatListItemResponse::getId)
                .contains(withLead.chatId()).doesNotContain(noLead.chatId());
        assertThat(controller.list("WITHOUT_LEAD", null)).extracting(ChatListItemResponse::getId)
                .contains(noLead.chatId()).doesNotContain(withLead.chatId());
        assertThat(controller.list("ALL", "облучатель")).extracting(ChatListItemResponse::getId)
                .contains(withLead.chatId()).doesNotContain(noLead.chatId());
        assertThat(controller.list("ALL", client.substring(4, 11))).extracting(ChatListItemResponse::getId)
                .contains(withLead.chatId()).doesNotContain(noLead.chatId());
        assertThatThrownBy(() -> controller.list("BOGUS", null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void messagesComeInPagesChronologicalWithinPage() {
        String chat = personal();
        ChatIngestWriter.Outcome o = null;
        for (int i = 1; i <= 5; i++) {
            o = write(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("сообщение " + i)));
        }

        List<ChatMessageResponse> last2 = controller.messages(o.chatId(), null, 2);
        assertThat(last2).extracting(ChatMessageResponse::getBody).containsExactly("сообщение 4", "сообщение 5");
        assertThat(controller.messages(o.chatId(), last2.get(0).getId(), 2))
                .extracting(ChatMessageResponse::getBody).containsExactly("сообщение 2", "сообщение 3");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void imageIsInlineButHtmlIsDownloadOnly() {
        String chat = personal();
        ChatIngestWriter.Outcome img = writeFile(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("imageMessage", "https://x/p", "фото.png", "image/png", "")), new byte[]{1, 2});
        ChatIngestWriter.Outcome html = writeFile(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.file("documentMessage", "https://x/h", "page.html", "text/html", "")),
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        List<ChatMessageResponse> msgs = controller.messages(img.chatId(), null, 10);
        ChatAttachmentResponse imgMeta = msgs.stream().filter(m -> m.getId().equals(img.messageId())).findFirst().orElseThrow().getAttachment();
        ChatAttachmentResponse htmlMeta = msgs.stream().filter(m -> m.getId().equals(html.messageId())).findFirst().orElseThrow().getAttachment();
        assertThat(imgMeta.isImage()).isTrue();
        assertThat(htmlMeta.isImage()).isFalse();
        assertThat(htmlMeta.isStored()).isTrue();

        ResponseEntity<byte[]> image = controller.attachment(img.chatId(), imgMeta.getId());
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(image.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(image.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        ResponseEntity<byte[]> page = controller.attachment(html.chatId(), htmlMeta.getId());
        assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(page.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void chatOfOtherMarketIsNotFound() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));

        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.get(o.chatId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.messages(o.chatId(), null, 10)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void adminCreatesLeadFromChatWrittenFirstByUs() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60,
                GreenApiJson.text("Добрый день, это West-Med")));

        ChatResponse r = controller.createLead(o.chatId());

        assertThat(r.getLead().status()).isEqualTo("IN_WORK");
        assertThat(r.isLeadOpen()).isTrue();
        Lead l = leadRepository.findById(r.getLead().id()).orElseThrow();
        assertThat(l.getSource()).isEqualTo(LeadSources.WHATSAPP);
        assertThat(l.getChat().getId()).isEqualTo(o.chatId());
        assertThat(l.getReceivedAt().toInstant()).isEqualTo(Instant.ofEpochSecond(clock));   // начало переписки
        assertThat(l.getEvents().get(0).getAuthor()).isEqualTo("manager1");
        assertThatThrownBy(() -> controller.createLead(o.chatId())).isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void notClientSwitchStopsAutomaticLeads() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Данияр", id(), clock += 60, GreenApiJson.text("Привет")));

        assertThat(controller.notClient(o.chatId(), notClient(true)).isNotClient()).isTrue();
        ChatIngestWriter.Outcome reply = write(GreenApiJson.incoming(chat, "Данияр", id(), clock += 60, GreenApiJson.text("Привет, до завтра")));

        assertThat(reply.createdLeadId()).isNull();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsButCannotWrite() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(personal(), "Данияр", id(), clock += 60, GreenApiJson.text("Привет")));

        assertThat(controller.get(o.chatId()).getId()).isEqualTo(o.chatId());
        assertThat(controller.status()).isNotNull();
        assertThatThrownBy(() -> controller.createLead(o.chatId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.notClient(o.chatId(), notClient(true))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void excelAttachmentIsPreviewedByTheImportGrid() throws IOException {
        byte[] xlsx = xlsx(new String[]{"Наименование", "Кол-во"}, new String[]{"Аппарат УЗИ", "1"});
        ChatIngestWriter.Outcome o = writeFile(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60,
                GreenApiJson.file("documentMessage", "https://x/l", "заявка.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "")), xlsx);
        ChatAttachmentResponse att = controller.messages(o.chatId(), null, 1).get(0).getAttachment();
        assertThat(att.isExcel()).isTrue();

        ImportPreviewResponse p = controller.preview(o.chatId(), att.getId());

        assertThat(p.getColumns()).extracting(PreviewColumnResponse::getHeader).contains("Наименование");
        assertThat(p.getRows()).anySatisfy(row -> assertThat(row).contains("Аппарат УЗИ"));
    }

    static byte[] xlsx(String[]... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Заявка");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.ChatApiTest'`
Expected: FAIL — компиляция (`ChatController` и DTO не найдены).

- [ ] **Step 3: DTO**

`dto/response/ChatLeadRef.java`:

```java
package com.vladoose.nir.dto.response;

/** Обращение чата в списке и шапке: id и статус (NEW / IN_WORK / CONVERTED / CLOSED). */
public record ChatLeadRef(Long id, String status) {}
```

`dto/response/ChatListItemResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ChatListItemResponse {
    private Long id;
    private String title;
    private String phone;
    private boolean group;
    private boolean notClient;
    private OffsetDateTime lastMessageAt;
    private String lastMessagePreview;
    /** Открытое обращение чата, иначе последнее; null — обращений не было. */
    private ChatLeadRef lead;
}
```

`dto/response/ChatResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ChatResponse {
    private Long id;
    private String title;
    private String phone;
    private boolean group;
    private boolean notClient;
    private OffsetDateTime lastMessageAt;
    private ChatLeadRef lead;
    /** Есть «открытое обращение» по правилам спеки §5.1 — кнопка «Создать обращение» не нужна. */
    private boolean leadOpen;
}
```

`dto/response/ChatMessageResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ChatMessageResponse {
    private Long id;
    private String direction;
    private String senderName;
    private String type;
    private String body;
    private OffsetDateTime sentAt;
    private boolean edited;
    private boolean deleted;
    private ChatAttachmentResponse attachment;
}
```

`dto/response/ChatAttachmentResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ChatAttachmentResponse {
    private Long id;
    private String fileName;
    private String mimeType;
    private Long sizeBytes;
    /** Байты есть в АИС; иначе notStoredReason — TOO_LARGE / GROUP / DOWNLOAD_FAILED. */
    private boolean stored;
    private String notStoredReason;
    /** Excel — можно «Разобрать в позиции». */
    private boolean excel;
    /** Сохранённая безопасная картинка — показывается миниатюрой. */
    private boolean image;
}
```

`dto/request/ChatNotClientRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatNotClientRequest {
    @NotNull
    private Boolean value;
}
```

- [ ] **Step 4: Сервис**

`service/ChatService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «Чаты» (спека whatsapp-chats §8–9): список, переписка, файлы, «Создать обращение», «не клиент».
 * Чат грузится через findById (он ОБХОДИТ рыночный фильтр) — поэтому явный гард рынка, как в LeadService.get.
 * Сообщения и файлы отдаются только через свой чат.
 */
@Service
public class ChatService {

    public static final int LIST_LIMIT = 300;
    public static final int PAGE_MAX = 100;
    private static final Set<LeadStatus> OPEN = EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED);
    private static final Set<String> FILTERS = Set.of("ALL", "WITH_LEAD", "WITHOUT_LEAD", "GROUPS");
    private static final Set<String> INLINE_IMAGES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final OffsetDateTime EPOCH = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final LeadRepository leadRepository;
    private final ChatLeadRules rules;
    private final LeadIntakeService intake;
    private final PrivateRequestImportService importService;

    public ChatService(ChatRepository chatRepository, ChatMessageRepository messageRepository,
                       ChatAttachmentRepository attachmentRepository, LeadRepository leadRepository,
                       ChatLeadRules rules, LeadIntakeService intake, PrivateRequestImportService importService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.leadRepository = leadRepository;
        this.rules = rules;
        this.intake = intake;
        this.importService = importService;
    }

    /** До LIST_LIMIT свежих чатов; q — по имени, номеру (от 4 цифр) и тексту сообщений. */
    @Transactional(readOnly = true)
    public List<ChatListItemResponse> list(String filter, String q) {
        String f = filter == null || filter.isBlank() ? "ALL" : filter.trim().toUpperCase(Locale.ROOT);
        if (!FILTERS.contains(f)) throw new BadRequestException("Неизвестный фильтр: " + filter);
        List<Chat> chats = chatRepository.findRecent(PageRequest.of(0, LIST_LIMIT));
        Map<Long, Lead> current = currentLeads(chats.stream().map(Chat::getId).toList());
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String digits = needle.replaceAll("\\D", "");
        // без рыночного фильтра — пересечение с чатами рынка ниже делает его безопасным
        Set<Long> bodyHits = needle.length() >= 2 ? new HashSet<>(messageRepository.findChatIdsByBody(needle)) : Set.of();
        return chats.stream()
                .filter(c -> passes(f, c, current.get(c.getId())))
                .filter(c -> needle.isEmpty() || matches(c, needle, digits) || bodyHits.contains(c.getId()))
                .map(c -> toListItem(c, current.get(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatResponse get(Long id) {
        return toResponse(chat(id));
    }

    /** Порция: последние limit сообщений, либо перед сообщением beforeId; внутри порции — по времени. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(Long chatId, Long beforeId, int limit) {
        Chat c = chat(chatId);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, PAGE_MAX)));
        if (beforeId == null) return toResponses(messageRepository.findLatest(c.getId(), page));
        ChatMessage before = messageRepository.findById(beforeId)
                .filter(m -> m.getChat().getId().equals(c.getId()))
                .orElseThrow(() -> new NotFoundException("Сообщение не найдено: id=" + beforeId));
        return toResponses(messageRepository.findBefore(c.getId(), before.getSentAt(), before.getId(), page));
    }

    /** Файл с байтами — только через свой чат текущего рынка. */
    @Transactional(readOnly = true)
    public ChatAttachment attachment(Long chatId, Long attachmentId) {
        Chat c = chat(chatId);
        ChatAttachment a = attachmentRepository.findById(attachmentId)
                .filter(x -> x.getMessage().getChat().getId().equals(c.getId()))
                .orElseThrow(() -> new NotFoundException("Файл не найден: id=" + attachmentId));
        if (a.getContent() == null) throw new NotFoundException("Файл не сохранён в АИС — смотрите его в телефоне");
        return a;
    }

    @Transactional(readOnly = true)
    public ImportPreviewResponse previewAttachment(Long chatId, Long attachmentId) {
        ChatAttachment a = attachment(chatId, attachmentId);
        if (!isExcel(a.getFileName(), a.getMimeType())) {
            throw new BadRequestException("Разобрать в позиции можно только Excel-файл");
        }
        return importService.preview(a.getContent(), a.getFileName());
    }

    /** «Создать обращение» (спека §5.2 п.5): личный чат без открытого обращения; сразу «В работе». */
    @Transactional
    public ChatResponse createLead(Long chatId, String author) {
        Chat c = chat(chatId);
        if (c.isGroup()) throw new BadRequestException("Из группы обращение не создаётся");
        OffsetDateTime now = OffsetDateTime.now();
        if (rules.findOpenLead(c, now).isPresent()) throw new BadRequestException("У чата уже есть открытое обращение");
        // обращение начинается с первого сообщения после прошлого обращения чата — его переписка попадёт в карточку
        OffsetDateTime after = leadRepository.findByChatIdIn(List.of(c.getId())).stream()
                .map(Lead::getReceivedAt).max(Comparator.naturalOrder()).orElse(EPOCH);
        OffsetDateTime start = messageRepository.findEarliestAfter(c.getId(), after, PageRequest.of(0, 1)).stream()
                .findFirst().map(ChatMessage::getSentAt).orElse(now);
        String lastIncoming = messageRepository.findLatestByDirection(c.getId(), LeadDirection.IN, PageRequest.of(0, 1))
                .stream().findFirst().map(ChatMessage::getBody).orElse(null);
        IncomingLead in = new IncomingLead(LeadSources.WHATSAPP, null, LeadChannel.WHATSAPP, "WhatsApp", start,
                contactName(c), c.getPhoneNorm(), null, null, lastIncoming, List.of(), LeadStatus.IN_WORK, null, author);
        Lead lead = intake.ingest(in).orElseThrow();   // externalId = null → дублей не бывает
        lead.setChat(c);
        return toResponse(c);
    }

    @Transactional
    public ChatResponse setNotClient(Long chatId, boolean value) {
        Chat c = chat(chatId);
        if (c.isGroup()) throw new BadRequestException("Для группы отметка не нужна — из групп обращения не создаются");
        c.setNotClient(value);
        return toResponse(c);
    }

    /** Сообщения (новые → старые) в ответ по времени + метаданные файлов одним запросом. */
    List<ChatMessageResponse> toResponses(List<ChatMessage> newestFirst) {
        List<ChatMessage> chrono = new ArrayList<>(newestFirst);
        Collections.reverse(chrono);
        Map<Long, ChatAttachmentMeta> files = chrono.isEmpty() ? Map.of()
                : attachmentRepository.findMetaByMessageIds(chrono.stream().map(ChatMessage::getId).toList()).stream()
                        .collect(Collectors.toMap(ChatAttachmentMeta::messageId, a -> a));
        return chrono.stream().map(m -> toMessage(m, files.get(m.getId()))).toList();
    }

    Chat chat(Long id) {
        Chat c = chatRepository.findById(id).orElseThrow(() -> new NotFoundException("Чат не найден: id=" + id));
        if (c.getMarket() != null && c.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Чат не найден: id=" + id);
        }
        return c;
    }

    public static boolean isSafeImage(String mime) {
        return mime != null && INLINE_IMAGES.contains(mime.split(";")[0].trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isExcel(String fileName, String mime) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String t = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls")
                || t.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || t.equals("application/vnd.ms-excel");
    }

    private ChatResponse toResponse(Chat c) {
        ChatResponse r = new ChatResponse();
        r.setId(c.getId());
        r.setTitle(c.getTitle());
        r.setPhone(c.getPhoneNorm());
        r.setGroup(c.isGroup());
        r.setNotClient(c.isNotClient());
        r.setLastMessageAt(c.getLastMessageAt());
        Lead lead = currentLeads(List.of(c.getId())).get(c.getId());
        r.setLead(lead == null ? null : new ChatLeadRef(lead.getId(), lead.getStatus().name()));
        r.setLeadOpen(!c.isGroup() && rules.findOpenLead(c, OffsetDateTime.now()).isPresent());
        return r;
    }

    private static ChatListItemResponse toListItem(Chat c, Lead lead) {
        ChatListItemResponse r = new ChatListItemResponse();
        r.setId(c.getId());
        r.setTitle(c.getTitle());
        r.setPhone(c.getPhoneNorm());
        r.setGroup(c.isGroup());
        r.setNotClient(c.isNotClient());
        r.setLastMessageAt(c.getLastMessageAt());
        r.setLastMessagePreview(c.getLastMessagePreview());
        r.setLead(lead == null ? null : new ChatLeadRef(lead.getId(), lead.getStatus().name()));
        return r;
    }

    private static ChatMessageResponse toMessage(ChatMessage m, ChatAttachmentMeta a) {
        ChatMessageResponse r = new ChatMessageResponse();
        r.setId(m.getId());
        r.setDirection(m.getDirection().name());
        r.setSenderName(m.getSenderName());
        r.setType(m.getType().name());
        r.setBody(m.getBody());
        r.setSentAt(m.getSentAt());
        r.setEdited(m.isEdited());
        r.setDeleted(m.isDeleted());
        if (a != null) {
            ChatAttachmentResponse f = new ChatAttachmentResponse();
            f.setId(a.id());
            f.setFileName(a.fileName());
            f.setMimeType(a.mimeType());
            f.setSizeBytes(a.sizeBytes());
            f.setStored(a.stored());
            f.setNotStoredReason(a.notStoredReason() == null ? null : a.notStoredReason().name());
            f.setExcel(isExcel(a.fileName(), a.mimeType()));
            f.setImage(a.stored() && isSafeImage(a.mimeType()));
            r.setAttachment(f);
        }
        return r;
    }

    /** Текущее обращение чата: открытое (NEW/IN_WORK/CONVERTED), иначе последнее. */
    private Map<Long, Lead> currentLeads(Collection<Long> chatIds) {
        if (chatIds.isEmpty()) return Map.of();
        Comparator<Lead> newest = Comparator.comparing(Lead::getReceivedAt).thenComparing(Lead::getId);
        Map<Long, Lead> out = new HashMap<>();
        for (Lead l : leadRepository.findByChatIdIn(chatIds)) {
            Long chatId = l.getChat().getId();
            Lead cur = out.get(chatId);
            boolean open = OPEN.contains(l.getStatus());
            boolean curOpen = cur != null && OPEN.contains(cur.getStatus());
            if (cur == null || (open && !curOpen) || (open == curOpen && newest.compare(l, cur) > 0)) out.put(chatId, l);
        }
        return out;
    }

    private static boolean passes(String filter, Chat c, Lead lead) {
        return switch (filter) {
            case "WITH_LEAD" -> lead != null;
            case "WITHOUT_LEAD" -> !c.isGroup() && lead == null;
            case "GROUPS" -> c.isGroup();
            default -> true;
        };
    }

    private static boolean matches(Chat c, String needle, String digits) {
        if (c.getTitle() != null && c.getTitle().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return digits.length() >= 4 && c.getPhoneNorm() != null && c.getPhoneNorm().contains(digits);
    }

    /** Имя контакта для обращения — если название чата не просто номер. */
    private static String contactName(Chat c) {
        return c.getTitle() != null && !c.getTitle().equals(c.getPhoneNorm()) ? c.getTitle() : null;
    }
}
```

- [ ] **Step 5: Контроллер**

`controller/ChatController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ChatNotClientRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.ChatAttachment;
import com.vladoose.nir.integration.greenapi.WhatsappChatScheduler;
import com.vladoose.nir.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** «Чаты» (спека whatsapp-chats §8). Чтение — любому вошедшему, запись — ADMIN, как везде в АИС. */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService service;
    private final WhatsappChatScheduler scheduler;

    public ChatController(ChatService service, WhatsappChatScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @GetMapping
    public List<ChatListItemResponse> list(@RequestParam(defaultValue = "ALL") String filter,
                                           @RequestParam(required = false) String q) {
        return service.list(filter, q);
    }

    @GetMapping("/status")
    public WhatsappStatusResponse status() {
        return scheduler.status();
    }

    @GetMapping("/{id}")
    public ChatResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/messages")
    public List<ChatMessageResponse> messages(@PathVariable Long id, @RequestParam(required = false) Long before,
                                              @RequestParam(defaultValue = "50") int limit) {
        return service.messages(id, before, limit);
    }

    /** Inline — только безопасные картинки; остальное (включая html/svg) — скачивание, чтобы файл не исполнился в АИС. */
    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        ChatAttachment a = service.attachment(id, attachmentId);
        boolean inline = ChatService.isSafeImage(a.getMimeType());
        String name = a.getFileName() == null || a.getFileName().isBlank() ? "file" : a.getFileName();
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(inline ? MediaType.parseMediaType(a.getMimeType()) : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(a.getContent());
    }

    @PostMapping("/{id}/attachments/{attachmentId}/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse preview(@PathVariable Long id, @PathVariable Long attachmentId) {
        return service.previewAttachment(id, attachmentId);
    }

    @PostMapping("/{id}/lead")
    @PreAuthorize("hasRole('ADMIN')")
    public ChatResponse createLead(@PathVariable Long id) {
        return service.createLead(id, currentUser());
    }

    @PostMapping("/{id}/not-client")
    @PreAuthorize("hasRole('ADMIN')")
    public ChatResponse notClient(@PathVariable Long id, @Valid @RequestBody ChatNotClientRequest req) {
        return service.setNotClient(id, req.getValue());
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
```

- [ ] **Step 6: Прогнать тест**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.ChatApiTest'`
Expected: PASS (8 тестов). Если `excelAttachmentIsPreviewedByTheImportGrid` краснеет на заголовке — посмотреть, что `RuleBasedLineExtractor` вернул в `columns` (печать `p`), и сверить с тем, как грид «Входящих» показывает ту же таблицу; менять ожидание только если экстрактор иначе трактует первую строку.

- [ ] **Step 7: Мутации — по одной**

| Мутация в `ChatService`/`ChatController` | Должен покраснеть |
|---|---|
| убрать проверку рынка в `chat(id)` | `chatOfOtherMarketIsNotFound` |
| `inline = true` всегда | `imageIsInlineButHtmlIsDownloadOnly` |
| убрать `@PreAuthorize` у `createLead` | `operatorReadsButCannotWrite` |
| в `createLead` убрать проверку открытого обращения | `adminCreatesLeadFromChatWrittenFirstByUs` |

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/ChatLeadRef.java \
  src/main/java/com/vladoose/nir/dto/response/ChatListItemResponse.java src/main/java/com/vladoose/nir/dto/response/ChatResponse.java \
  src/main/java/com/vladoose/nir/dto/response/ChatMessageResponse.java src/main/java/com/vladoose/nir/dto/response/ChatAttachmentResponse.java \
  src/main/java/com/vladoose/nir/dto/request/ChatNotClientRequest.java src/main/java/com/vladoose/nir/service/ChatService.java \
  src/main/java/com/vladoose/nir/controller/ChatController.java src/test/java/com/vladoose/nir/chat/ChatApiTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): REST /api/chats — список, переписка, файлы (inline только картинки), «Создать обращение», «не клиент»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Обращение ↔ чат: переписка в карточке, Excel → позиции

**Files:**
- Modify: `dto/response/LeadCardResponse.java` (`chatId`), `mapper/LeadResponseMapper.java` (`toCard`)
- Create: `dto/request/LeadItemsImportRequest.java`
- Modify: `service/PrivateRequestImportService.java` (`learn`)
- Modify: `service/LeadService.java` (`importItems`, конструктор + `PrivateRequestImportService`)
- Modify: `service/ChatService.java` (`messagesForLead`, конструктор + `LeadService`)
- Modify: `controller/LeadController.java` (два эндпоинта, конструктор + `ChatService`)
- Test: `chat/LeadChatTest.java`

**Interfaces:**
- Consumes: Task 7 (`ChatService.toResponses`, `ChatController.createLead`), Task 5 (`ChatIngestWriter`), существующие `LeadService.get/require`, `ColumnMapping`, `LeadItemDto`, `HeaderSynonymRepository.findByHeaderNorm`.
- Produces:
  - `LeadCardResponse.chatId : Long`
  - `LeadItemsImportRequest` (`@Data`): `List<ColumnMapping> mappings`, `List<LeadItemDto> items`, `String mode` (`REPLACE`/`APPEND`)
  - `PrivateRequestImportService.learn(List<ColumnMapping>)`
  - `LeadService.importItems(Long id, List<ColumnMapping> mappings, List<LeadItemDto> items, boolean replace, String author) : Lead`
  - `ChatService.messagesForLead(Long leadId) : List<ChatMessageResponse>`, константа `LEAD_MESSAGES = 200`
  - `GET /api/leads/{id}/chat-messages`, `POST /api/leads/{id}/items/import` (ADMIN), `LeadController.parseMode(String) : boolean`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/chat/LeadChatTest.java`:

```java
package com.vladoose.nir.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ChatController;
import com.vladoose.nir.controller.LeadController;
import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.LeadCreateRequest;
import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.dto.request.LeadItemsImportRequest;
import com.vladoose.nir.dto.response.ChatMessageResponse;
import com.vladoose.nir.dto.response.ChatResponse;
import com.vladoose.nir.dto.response.LeadCardResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiNotificationParser;
import com.vladoose.nir.integration.greenapi.ParsedNotification;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import com.vladoose.nir.service.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadChatTest {

    @Autowired LeadController leadController;
    @Autowired ChatController chatController;
    @Autowired ChatIngestWriter writer;
    @Autowired LeadService leadService;
    @Autowired HeaderSynonymRepository synonymRepository;

    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }
    static String id() { return UUID.randomUUID().toString().replace("-", "").toUpperCase(); }

    private ChatIngestWriter.Outcome write(ObjectNode body) {
        return writer.write((ParsedNotification.Message) GreenApiNotificationParser.parse(body), null, List.of());
    }

    private static LeadItemDto item(String name, String brand, int quantity) {
        LeadItemDto d = new LeadItemDto();
        d.setName(name);
        d.setBrand(brand);
        d.setQuantity(quantity);
        return d;
    }

    private static LeadItemsImportRequest request(String mode, String header, LeadItemDto... items) {
        ColumnMapping m = new ColumnMapping();
        m.setHeader(header);
        m.setField(LineField.NAME);
        LeadItemsImportRequest r = new LeadItemsImportRequest();
        r.setMode(mode);
        r.setMappings(List.of(m));
        r.setItems(List.of(items));
        return r;
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void cardPointsToChatAndShowsConversationSinceLead() {
        String chat = personal();
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Реклама: у нас скидки")));
        ChatIngestWriter.Outcome first = write(GreenApiJson.incoming(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Нужен облучатель")));
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Подготовим КП")));

        assertThat(leadController.get(first.createdLeadId()).getChatId()).isEqualTo(first.chatId());
        assertThat(leadController.chatMessages(first.createdLeadId()))
                .extracting(ChatMessageResponse::getBody).containsExactly("Нужен облучатель", "Подготовим КП");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void leadWithoutChatHasNoMessages() {
        LeadCreateRequest req = new LeadCreateRequest();
        req.setChannel(LeadChannel.PHONE);
        req.setContactName("Звонок");
        req.setContactPhone("+77010000000");
        Long leadId = leadController.create(req).getId();

        assertThat(leadController.get(leadId).getChatId()).isNull();
        assertThat(leadController.chatMessages(leadId)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void manualLeadFromChatShowsConversationFromItsStart() {
        String chat = personal();
        ChatIngestWriter.Outcome o = write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Добрый день, это West-Med")));
        write(GreenApiJson.outgoing(chat, "Айгерим", id(), clock += 60, GreenApiJson.text("Подскажите, что ищете?")));

        ChatResponse r = chatController.createLead(o.chatId());

        assertThat(leadController.chatMessages(r.getLead().id())).extracting(ChatMessageResponse::getBody)
                .containsExactly("Добрый день, это West-Med", "Подскажите, что ищете?");
    }

    @Test
    @WithMockUser(username = "manager1", roles = "ADMIN")
    void excelLinesReplaceOrAppendItemsAndTeachHeaders() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("Список во вложении")));
        String header = "модель аппарата " + System.nanoTime();

        LeadCardResponse replaced = leadController.importItems(o.createdLeadId(), request("REPLACE", header,
                item("Аппарат УЗИ", "Mindray", 1), item("Датчик конвексный", null, 2)));
        assertThat(replaced.getItems()).extracting(LeadItemDto::getName).containsExactly("Аппарат УЗИ", "Датчик конвексный");

        LeadCardResponse appended = leadController.importItems(o.createdLeadId(), request("APPEND", header, item("Принтер УЗИ", null, 1)));
        assertThat(appended.getItems()).extracting(LeadItemDto::getName, LeadItemDto::getQuantity)
                .containsExactly(tuple("Аппарат УЗИ", 1), tuple("Датчик конвексный", 2), tuple("Принтер УЗИ", 1));
        assertThat(appended.getEvents()).last().satisfies(e -> {
            assertThat(e.getBody()).isEqualTo("Позиции из Excel: 1 поз. (добавлены)");
            assertThat(e.getAuthor()).isEqualTo("manager1");
        });
        assertThat(synonymRepository.findByHeaderNorm(header)).get().extracting(HeaderSynonym::getField).isEqualTo(LineField.NAME);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void closedLeadAndUnknownModeAreRejected() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));

        assertThatThrownBy(() -> leadController.importItems(o.createdLeadId(), request("MERGE", "h", item("A", null, 1))))
                .isInstanceOf(BadRequestException.class);
        leadService.close(o.createdLeadId(), LeadCloseReason.SPAM, null, "admin");
        assertThatThrownBy(() -> leadController.importItems(o.createdLeadId(), request("REPLACE", "h", item("A", null, 1))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotImportItems() {
        ChatIngestWriter.Outcome o = write(GreenApiJson.incoming(personal(), "Айгерим", id(), clock += 60, GreenApiJson.text("x")));

        assertThatThrownBy(() -> leadController.importItems(o.createdLeadId(), request("REPLACE", "h", item("A", null, 1))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.LeadChatTest'`
Expected: FAIL — компиляция (`getChatId`, `chatMessages`, `importItems`, `LeadItemsImportRequest`).

- [ ] **Step 3: Карточка обращения знает свой чат**

В `dto/response/LeadCardResponse.java` после `extSyncError` добавить:

```java
    /** Чат WhatsApp обращения; null — обращение не из WhatsApp и к чату не привязано. */
    private Long chatId;
```

В `mapper/LeadResponseMapper.java`, метод `toCard`, после `r.setExtSyncError(l.getExtSyncError());` добавить:

```java
        r.setChatId(l.getChat() == null ? null : l.getChat().getId());
```

- [ ] **Step 4: Обучение словаря заголовков — отдельным методом**

В `service/PrivateRequestImportService.java` заменить в `commit` цикл по маппингам вызовом нового метода и добавить сам метод:

```java
    @Transactional
    public Tender commit(ImportCommitRequest dto) {
        learn(dto.getMappings());
        PrivateRequestCreate create = new PrivateRequestCreate();
        create.setClientFacilityId(dto.getClientFacilityId());
        create.setNote(dto.getNote());
        create.setLines(dto.getLines());
        return privateRequestService.createFromLines(create);
    }

    /** Разметка колонок оператора учит словарь заголовков: и при импорте заявки, и при разборе Excel в обращение. */
    @Transactional
    public void learn(List<ColumnMapping> mappings) {
        if (mappings == null) return;
        for (ColumnMapping m : mappings) {
            saveSynonym(m.getHeader(), m.getField());
        }
    }
```

Добавить импорт `java.util.List`.

- [ ] **Step 5: Позиции из Excel в обращение**

`dto/request/LeadItemsImportRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** Позиции обращения из Excel-файла чата (спека whatsapp-chats §9.4). */
@Data
public class LeadItemsImportRequest {
    /** Разметка колонок оператора — учит словарь заголовков. */
    private List<ColumnMapping> mappings;
    @Valid
    private List<LeadItemDto> items;
    /** REPLACE — заменить позиции, APPEND — добавить в конец. */
    @NotBlank
    private String mode;
}
```

В `service/LeadService.java`: добавить поле и параметр конструктора `PrivateRequestImportService importService` (последним), импорт `com.vladoose.nir.dto.request.ColumnMapping`, и метод после `updateItems`:

```java
    /**
     * Позиции из Excel-файла чата (спека whatsapp-chats §9.4): replace — заменить, иначе добавить в конец.
     * Разметка колонок учит словарь заголовков — как при импорте письма клиники.
     */
    @Transactional
    public Lead importItems(Long id, List<ColumnMapping> mappings, List<LeadItemDto> items, boolean replace, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), "править позиции");
        List<LeadItemDto> clean = items == null ? List.of()
                : items.stream().filter(i -> i != null && !isBlank(i.getName())).toList();
        if (clean.isEmpty()) throw new BadRequestException("В файле нет строк с наименованием");
        importService.learn(mappings);
        if (replace) lead.getItems().clear();   // через коллекцию — orphanRemoval (§7)
        for (LeadItemDto i : clean) {
            lead.addItem(trunc(i.getName().trim(), 500), trunc(blankToNull(i.getBrand()), 255),
                    i.getQuantity() != null ? i.getQuantity() : 1, null);
        }
        lead.addEvent(LeadEventType.NOTE, author,
                "Позиции из Excel: " + clean.size() + " поз. (" + (replace ? "заменены" : "добавлены") + ")");
        lead.setUpdatedAt(OffsetDateTime.now());
        return lead;
    }
```

Конструктор целиком:

```java
    public LeadService(LeadRepository leadRepository, LeadIntakeService intake,
                       FacilityRepository facilityRepository, PrivateRequestService privateRequestService,
                       PrivateRequestImportService importService) {
        this.leadRepository = leadRepository;
        this.intake = intake;
        this.facilityRepository = facilityRepository;
        this.privateRequestService = privateRequestService;
        this.importService = importService;
    }
```

(`grep -rn "new LeadService(" src/test` — пусто на 2026-09-28, ручных конструкций в тестах нет.)

- [ ] **Step 6: Переписка обращения**

В `service/ChatService.java`: поле и параметр конструктора `LeadService leadService` (последним), константа и метод:

```java
    public static final int LEAD_MESSAGES = 200;
```

```java
    /** Переписка чата обращения с момента обращения (спека §9.3); у обращения без чата — пусто. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messagesForLead(Long leadId) {
        Lead lead = leadService.get(leadId);   // гард рынка — там
        if (lead.getChat() == null) return List.of();
        return toResponses(messageRepository.findSince(lead.getChat().getId(), lead.getReceivedAt(),
                PageRequest.of(0, LEAD_MESSAGES)));
    }
```

- [ ] **Step 7: Эндпоинты обращения**

В `controller/LeadController.java`: импорты `com.vladoose.nir.dto.response.ChatMessageResponse`, `com.vladoose.nir.service.ChatService`; поле `private final ChatService chatService;`; конструктор:

```java
    public LeadController(LeadService service, LeadResponseMapper mapper, WestmedLeadScheduler westmedScheduler,
                          ChatService chatService) {
        this.service = service;
        this.mapper = mapper;
        this.westmedScheduler = westmedScheduler;
        this.chatService = chatService;
    }
```

и методы (после `updateItems`):

```java
    @GetMapping("/{id}/chat-messages")
    public List<ChatMessageResponse> chatMessages(@PathVariable Long id) {
        return chatService.messagesForLead(id);
    }

    @PostMapping("/{id}/items/import")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadCardResponse importItems(@PathVariable Long id, @Valid @RequestBody LeadItemsImportRequest req) {
        return card(service.importItems(id, req.getMappings(), req.getItems(), parseMode(req.getMode()), currentUser()));
    }

    /** «REPLACE» → заменить (true), «APPEND» → добавить (false), иное → 400. */
    static boolean parseMode(String raw) {
        String m = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (m.equals("REPLACE")) return true;
        if (m.equals("APPEND")) return false;
        throw new BadRequestException("Неизвестный режим: " + raw + " (нужно REPLACE или APPEND)");
    }
```

- [ ] **Step 8: Прогнать тест и соседей**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.chat.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS (новые 6 + все прежние тесты обращений; `LeadControllerTest` не сломан новым параметром конструктора — бины собирает Spring).

- [ ] **Step 9: Мутация**

В `messagesForLead` заменить `findSince(…, lead.getReceivedAt(), …)` на `findLatest(chatId, …)` → должен покраснеть `cardPointsToChatAndShowsConversationSinceLead` (в карточку попала реклама до обращения). Вернуть.

- [ ] **Step 10: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/LeadCardResponse.java \
  src/main/java/com/vladoose/nir/mapper/LeadResponseMapper.java src/main/java/com/vladoose/nir/dto/request/LeadItemsImportRequest.java \
  src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java src/main/java/com/vladoose/nir/service/LeadService.java \
  src/main/java/com/vladoose/nir/service/ChatService.java src/main/java/com/vladoose/nir/controller/LeadController.java \
  src/test/java/com/vladoose/nir/chat/LeadChatTest.java
git commit -m "$(cat <<'EOF'
feat(whatsapp): обращение ↔ чат — переписка в карточке, позиции из Excel (заменить/добавить) с обучением словаря

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 11: Гейт бэкенда**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test`
Expected: BUILD SUCCESSFUL, 0 падений (было 577 + ~61 новых). Число тестов записать в леджер.

---

### Task 9: Dev-стаб Green-API для живых проверок

**Files:**
- Create: `scripts/greenapi-stub.mjs`

**Interfaces:**
- Produces: HTTP-сервер на `localhost:7707` с путями настоящего Green-API (`/waInstance{id}/receiveNotification|deleteNotification|getStateInstance|getSettings/{token}`), файлами `/files/<имя>` и служебными `/__enqueue`, `/__scenario/basic`, `/__state/<state>`, `/__settings`, `/__queue`; режим `--write-xlsx <путь>` (записать тестовый Excel и выйти). Нужен задачам 10–12 для живых проверок.

- [ ] **Step 1: Скрипт**

`scripts/greenapi-stub.mjs`:

```js
#!/usr/bin/env node
// Стаб Green-API для живых проверок «Чатов» без WhatsApp
// (спека docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md §12). Только для разработки.
//
// Запуск:    node scripts/greenapi-stub.mjs                        (порт 7707; другой — STUB_PORT=…)
// Бэкенд:    WHATSAPP_ENABLED=true WHATSAPP_API_URL=http://localhost:7707 WHATSAPP_ID_INSTANCE=1101 \
//            WHATSAPP_API_TOKEN=dev-token WHATSAPP_INITIAL_DELAY_MS=3000 ./gradlew bootRun
// Сценарий:  curl -s -X POST localhost:7707/__scenario/basic      — корзина сайта, Excel, фото, ответ с телефона,
//                                                                     PDF, HTML, правка, группа, удаление
// Своё:      curl -s localhost:7707/__enqueue -H 'Content-Type: application/json' -d @notification.json
// Состояние: curl -s -X POST localhost:7707/__state/notAuthorized  (authorized / blocked / sleepMode / suspended …)
// Настройки: curl -s localhost:7707/__settings -d '{"outgoingMessageWebhook":"no"}'
// Очередь:   curl -s localhost:7707/__queue
// Excel:     node scripts/greenapi-stub.mjs --write-xlsx /путь/файл.xlsx   — записать тестовый .xlsx и выйти
//
// Токен проверяется как у настоящего сервиса (STUB_TOKEN, по умолчанию dev-token): чужой — 401.
// Очередь отдаёт ГОЛОВУ, пока её не удалят, — как настоящая.

import http from 'node:http';
import { crc32, deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.STUB_PORT || 7707);
const TOKEN = process.env.STUB_TOKEN || 'dev-token';
const BASE = `http://localhost:${PORT}`;
const WID = '77000000001@c.us';

// ---------- файлы: PNG и XLSX собираются на лету (zlib.crc32 — Node ≥ 20.15) ----------

function png(width, height) {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0;
    for (let x = 0; x < width; x++) {
      const i = y * stride + 1 + x * 3;
      raw[i] = 40 + Math.round((180 * x) / width);
      raw[i + 1] = 120 + Math.round((100 * y) / height);
      raw[i + 2] = 200;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // 8 бит
  ihdr[9] = 2; // RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** ZIP без сжатия (method 0) — POI такое читает; имена в UTF-8 (флаг 0x0800). */
function zip(files) {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const f of files) {
    const name = Buffer.from(f.name, 'utf8');
    const crc = crc32(f.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(f.data.length, 18);
    local.writeUInt32LE(f.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    parts.push(local, name, f.data);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0);
    c.writeUInt16LE(20, 4);
    c.writeUInt16LE(20, 6);
    c.writeUInt16LE(0x0800, 8);
    c.writeUInt16LE(0, 10);
    c.writeUInt16LE(0, 12);
    c.writeUInt16LE(0x21, 14);
    c.writeUInt32LE(crc, 16);
    c.writeUInt32LE(f.data.length, 20);
    c.writeUInt32LE(f.data.length, 24);
    c.writeUInt16LE(name.length, 28);
    c.writeUInt16LE(0, 30);
    c.writeUInt16LE(0, 32);
    c.writeUInt16LE(0, 34);
    c.writeUInt16LE(0, 36);
    c.writeUInt32LE(0, 38);
    c.writeUInt32LE(offset, 42);
    central.push(c, name);
    offset += 30 + name.length + f.data.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cd.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, cd, end]);
}

function xlsx(rows) {
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const col = (i) => String.fromCharCode(65 + i);
  const sheetRows = rows.map((r, ri) => `<row r="${ri + 1}">`
    + r.map((v, ci) => `<c r="${col(ci)}${ri + 1}" t="inlineStr"><is><t>${esc(v)}</t></is></c>`).join('')
    + '</row>').join('');
  const x = (s) => Buffer.from('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + s, 'utf8');
  const main = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
  const rel = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const pkg = 'http://schemas.openxmlformats.org/package/2006/relationships';
  return zip([
    { name: '[Content_Types].xml', data: x('<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
      + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      + '</Types>') },
    { name: '_rels/.rels', data: x(`<Relationships xmlns="${pkg}">`
      + `<Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`) },
    { name: 'xl/workbook.xml', data: x(`<workbook xmlns="${main}" xmlns:r="${rel}">`
      + '<sheets><sheet name="Заявка" sheetId="1" r:id="rId1"/></sheets></workbook>') },
    { name: 'xl/_rels/workbook.xml.rels', data: x(`<Relationships xmlns="${pkg}">`
      + `<Relationship Id="rId1" Type="${rel}/worksheet" Target="worksheets/sheet1.xml"/>`
      + `<Relationship Id="rId2" Type="${rel}/styles" Target="styles.xml"/></Relationships>`) },
    { name: 'xl/styles.xml', data: x(`<styleSheet xmlns="${main}">`
      + '<fonts count="1"><font/></fonts><fills count="1"><fill/></fills><borders count="1"><border/></borders>'
      + '<cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf/></cellXfs></styleSheet>') },
    { name: 'xl/worksheets/sheet1.xml', data: x(`<worksheet xmlns="${main}"><sheetData>${sheetRows}</sheetData></worksheet>`) },
  ]);
}

const XLSX_ROWS = [
  ['№', 'Наименование', 'Производитель', 'Кол-во'],
  ['1', 'Аппарат УЗИ экспертного класса', 'Mindray', '1'],
  ['2', 'Датчик конвексный', 'Mindray', '2'],
  ['3', 'Принтер для УЗИ', 'Sony', '1'],
];

if (process.argv[2] === '--write-xlsx') {
  writeFileSync(process.argv[3], xlsx(XLSX_ROWS));
  console.log('записан', process.argv[3]);
  process.exit(0);
}

const FILES = {
  'аппарат.png': { mime: 'image/png', data: png(320, 200) },
  'Заявка клиники.xlsx': { mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', data: xlsx(XLSX_ROWS) },
  'ТЗ.pdf': { mime: 'application/pdf', data: Buffer.from('%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj\n'
    + '2 0 obj <</Type/Pages/Kids[3 0 R]/Count 1>> endobj\n3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>> endobj\n'
    + 'trailer <</Root 1 0 R>>\n%%EOF\n', 'latin1') },
  'страница.html': { mime: 'text/html', data: Buffer.from('<html><body><script>alert("xss")</script>Если это исполнилось — дыра</body></html>') },
};

// ---------- уведомления в форме настоящего Green-API ----------

let seq = 0;
const now = () => Math.floor(Date.now() / 1000);
const idm = () => 'STUB' + Date.now().toString(16).toUpperCase() + (++seq);
const instance = () => ({ idInstance: 1101, wid: WID, typeInstance: 'whatsapp' });
const envelope = (type, chatId, sender, chatName, senderName, senderContactName, messageData) => ({
  typeWebhook: type, instanceData: instance(), timestamp: now(), idMessage: idm(),
  senderData: { chatId, sender, chatName, senderName, senderContactName }, messageData,
});
const incoming = (chatId, name, md) => envelope('incomingMessageReceived', chatId, chatId, name, name, name, md);
const outgoing = (chatId, name, md) => envelope('outgoingMessageReceived', chatId, WID, name, 'West-Med', '', md);
const inGroup = (groupId, groupName, authorId, author, md) =>
  envelope('incomingMessageReceived', groupId, authorId, groupName, author, author, md);
const text = (t) => ({ typeMessage: 'textMessage', textMessageData: { textMessage: t } });
const file = (typeMessage, name, caption) => ({
  typeMessage,
  fileMessageData: { downloadUrl: `${BASE}/files/${encodeURIComponent(name)}`, caption, fileName: name,
    jpegThumbnail: '', mimeType: FILES[name].mime },
});
const edited = (stanzaId, t) => ({ typeMessage: 'editedMessage', editedMessageData: { textMessage: t, stanzaId } });
const deleted = (stanzaId) => ({ typeMessage: 'deletedMessage', deletedMessageData: { stanzaId } });

let receipt = 0;
const queue = [];
let state = 'authorized';
const settings = {
  wid: WID, webhookUrl: '', webhookUrlToken: '', incomingWebhook: 'yes', outgoingMessageWebhook: 'yes',
  outgoingWebhook: 'yes', stateWebhook: 'yes', editedMessageWebhook: 'yes', deletedMessageWebhook: 'yes',
};
const enqueue = (body) => { queue.push({ receiptId: ++receipt, body }); return receipt; };

function basicScenario() {
  const aigerim = '77011234567@c.us';
  const erlan = '77029876543@c.us';
  const group = '120363000000000001@g.us';
  const a = 'Айгерим (тест)';
  const q = [
    incoming(aigerim, a, text('Здравствуйте! Интересует следующее оборудование:\n\n1. Облучатель ОБН-150 (x2)\n'
      + '2. Рециркулятор СН-111-130\n\nПрошу подготовить коммерческое предложение.')),
    incoming(aigerim, a, file('documentMessage', 'Заявка клиники.xlsx', 'Полный список во вложении')),
    incoming(aigerim, a, file('imageMessage', 'аппарат.png', 'Вот такой стоит сейчас')),
    outgoing(aigerim, a, text('Добрый день! Подготовим КП сегодня.')),
    incoming(aigerim, a, file('documentMessage', 'ТЗ.pdf', '')),
    incoming(aigerim, a, file('documentMessage', 'страница.html', 'проверка: должно скачиваться, а не открываться')),
  ];
  const uzi = incoming(erlan, 'Ерлан (тест)', text('Нужен аппарат УЗИ, какие есть?'));
  q.push(uzi, incoming(erlan, 'Ерлан (тест)', edited(uzi.idMessage, 'Нужен аппарат УЗИ экспертного класса')));
  const g = inGroup(group, 'Коллеги West-Med (тест)', '77025556677@c.us', 'Данияр', text('Кто завтра едет в Уральск?'));
  q.push(g,
    inGroup(group, 'Коллеги West-Med (тест)', '77025556677@c.us', 'Данияр', file('imageMessage', 'аппарат.png', 'фото из группы')),
    inGroup(group, 'Коллеги West-Med (тест)', '77025556677@c.us', 'Данияр', deleted(g.idMessage)));
  q.forEach(enqueue);
  return q.length;
}

// ---------- сервер ----------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, BASE);
  const send = (status, payload, type = 'application/json') => {
    const b = Buffer.isBuffer(payload) || typeof payload === 'string' ? payload : JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': type });
    res.end(b);
  };
  const readBody = async () => { let s = ''; for await (const c of req) s += c; return s; };

  if (req.method === 'POST' && url.pathname === '/__enqueue') return send(200, { receiptId: enqueue(JSON.parse(await readBody())) });
  if (req.method === 'POST' && url.pathname === '/__scenario/basic') return send(200, { enqueued: basicScenario() });
  if (req.method === 'POST' && url.pathname.startsWith('/__state/')) {
    state = decodeURIComponent(url.pathname.slice('/__state/'.length));
    enqueue({ typeWebhook: 'stateInstanceChanged', instanceData: instance(), timestamp: now(), stateInstance: state });
    return send(200, { state });
  }
  if (req.method === 'POST' && url.pathname === '/__settings') {
    Object.assign(settings, JSON.parse((await readBody()) || '{}'));
    return send(200, settings);
  }
  if (req.method === 'GET' && url.pathname === '/__queue') return send(200, queue);
  if (req.method === 'GET' && url.pathname.startsWith('/files/')) {
    const f = FILES[decodeURIComponent(url.pathname.slice('/files/'.length))];
    return f ? send(200, f.data, f.mime) : send(404, '');
  }

  const m = url.pathname.match(/^\/waInstance(\d+)\/([A-Za-z]+)\/([^/]+)(?:\/(\d+))?$/);
  if (!m) return send(404, '');
  if (m[3] !== TOKEN) return send(401, '');
  const method = m[2];
  if (method === 'receiveNotification') {
    const until = Date.now() + Math.min(Number(url.searchParams.get('receiveTimeout') || 5), 60) * 1000;
    while (!queue.length && Date.now() < until) await new Promise((r) => setTimeout(r, 200));
    return send(200, queue.length ? queue[0] : null);
  }
  if (method === 'deleteNotification' && req.method === 'DELETE') {
    const i = queue.findIndex((x) => x.receiptId === Number(m[4]));
    if (i >= 0) queue.splice(i, 1);
    return send(200, { result: i >= 0 });
  }
  if (method === 'getStateInstance') return send(200, { stateInstance: state });
  if (method === 'getSettings') return send(200, settings);
  return send(404, '');
});

server.listen(PORT, () => console.log(`Стаб Green-API: ${BASE} (idInstance любой, токен ${TOKEN === 'dev-token' ? 'dev-token' : 'из STUB_TOKEN'})`));
```

- [ ] **Step 2: Проверить стаб без бэкенда**

```bash
cd /Users/vlad/IdeaProjects/AIS && node scripts/greenapi-stub.mjs --write-xlsx /private/tmp/claude-501/stub.xlsx && unzip -t /private/tmp/claude-501/stub.xlsx
```
Expected: `записан …`, `No errors detected in compressed data`, 6 файлов.

Запустить стаб в фоне (`run_in_background: true`): `cd /Users/vlad/IdeaProjects/AIS && node scripts/greenapi-stub.mjs`, затем:

```bash
curl -s "localhost:7707/waInstance1101/receiveNotification/dev-token?receiveTimeout=5"; echo
curl -s -o /dev/null -w "%{http_code}\n" "localhost:7707/waInstance1101/getStateInstance/wrong"
curl -s -X POST localhost:7707/__scenario/basic; echo
curl -s "localhost:7707/waInstance1101/receiveNotification/dev-token?receiveTimeout=5" | head -c 200; echo
curl -s -X DELETE localhost:7707/waInstance1101/deleteNotification/dev-token/1; echo
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" "localhost:7707/files/%D0%B0%D0%BF%D0%BF%D0%B0%D1%80%D0%B0%D1%82.png"
```
Expected: `null` (через ~5 с); `401`; `{"enqueued":11}`; начало уведомления с `"receiptId":1`; `{"result":true}`; `200 image/png`.

- [ ] **Step 3: Прогнать бэкенд против стаба (данные — в локальную nirdb)**

Перезапустить стаб (чистая очередь). Бэкенд в фоне (sandbox off):

```bash
cd /Users/vlad/IdeaProjects/AIS && WHATSAPP_ENABLED=true WHATSAPP_API_URL=http://localhost:7707 WHATSAPP_ID_INSTANCE=1101 \
  WHATSAPP_API_TOKEN=dev-token WHATSAPP_INITIAL_DELAY_MS=3000 JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew bootRun
```

После `Started Nir2Application`: `curl -s -X POST localhost:7707/__scenario/basic`, подождать ~10 с, проверить (sandbox off):

```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "
select c.title, c.is_group, c.not_client, count(m.id) msgs,
       count(a.id) filter (where a.content is not null) stored, count(a.id) filter (where a.content is null) not_stored
from chat c join chat_message m on m.chat_id = c.id left join chat_attachment a on a.message_id = m.id
where c.account = '77000000001' group by 1,2,3 order by 1;" -c "
select l.subject, l.status, (select count(*) from lead_item i where i.lead_id = l.id) items
from lead l join chat c on c.id = l.chat_id where c.account = '77000000001' order by l.id;" -c "
select m.body, m.edited, m.deleted from chat_message m join chat c on c.id = m.chat_id
where c.account = '77000000001' and (m.edited or m.deleted);"
curl -s localhost:7707/__queue; echo
```

Expected: 3 чата — «Айгерим (тест)» (6 сообщений, сохранено 4 файла), «Ерлан (тест)» (1 сообщение), «Коллеги West-Med (тест)» (группа, 2 сообщения, 1 файл не сохранён); обращения — «Запрос КП», `IN_WORK` (ответ с телефона), 2 позиции + «WhatsApp», `NEW`, 0 позиций; правка «…экспертного класса» с `edited = t`, групповое сообщение `deleted = t`; очередь `[]`.

⚠️ Если пусто — смотреть лог бэкенда (`WhatsApp: …`) и строку состояния: `curl` к `/api/chats/status` требует входа, поэтому быстрее лог.

- [ ] **Step 4: Убрать тестовые данные из nirdb**

```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "
delete from lead where chat_id in (select id from chat where account = '77000000001');
delete from chat where account = '77000000001';"
```
(позиции и лента обращений, сообщения и файлы чатов удаляются каскадом). Бэкенд и стаб можно оставить — они нужны задачам 10–12; перед этими задачами повторить `/__scenario/basic`.

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add scripts/greenapi-stub.mjs
git commit -m "$(cat <<'EOF'
chore(whatsapp): dev-стаб Green-API — очередь, файлы (PNG/XLSX на лету), сценарий для живых проверок

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Один грид разбора Excel на все экраны

**Files:**
- Create: `frontend/src/app/shared/import-lines.ts`, `frontend/src/app/shared/import-grid.component.ts`
- Modify: `frontend/src/app/pages/inbound/inbound.component.ts`, `frontend/src/app/pages/private-requests/private-requests.component.ts`

**Interfaces:**
- Produces:
  - `import-lines.ts`: `interface ImportColumn { index: number; header: string; field: string | null }`, `interface ImportPreview { columns: ImportColumn[]; rows: string[][] }`, `interface ImportLine { name: string; manufact: string | null; quantity: number }`, `interface ImportMapping { header: string; field: string }`, `const IMPORT_FIELD_OPTIONS`, `function buildImportLines(preview: ImportPreview | null): { lines: ImportLine[]; mappings: ImportMapping[]; error: string | null }`
  - `<app-import-grid [preview]="…">` (`ImportGridComponent`, `@Input({ required: true }) preview`) — правит `column.field` на месте (как было)

Поведение двух экранов НЕ меняется: та же разметка, те же тексты ошибок, те же стили (фон шапки — `--surface-2`, как у kit-овского `th`, `styles.scss:85`).

- [ ] **Step 1: Снимок «до» (живьём)**

Поднять стек (sandbox off для бэкенда): бэкенд `./gradlew bootRun` (или уже запущенный из Task 9), фронт в фоне `cd /Users/vlad/IdeaProjects/AIS/frontend && npm start`. Тестовый файл: `node /Users/vlad/IdeaProjects/AIS/scripts/greenapi-stub.mjs --write-xlsx /private/tmp/claude-501/stub.xlsx`.

Playwright: `http://localhost:4200` → вход admin/admin → «Частные заявки» → «Импорт из Excel» → загрузить `stub.xlsx` (`browser_file_upload`). Снять `browser_evaluate`:

```js
() => {
  const pick = (sel) => { const el = document.querySelector(sel); if (!el) return null; const s = getComputedStyle(el);
    return { bg: s.backgroundColor, color: s.color, padding: s.padding, border: s.borderTopColor + ' ' + s.borderTopWidth,
             font: s.fontSize + ' ' + s.fontWeight, h: el.getBoundingClientRect().height }; };
  return { th: pick('.import-grid th'), ih: pick('.import-grid th .ih'), select: pick('.import-grid th select'),
           td: pick('.import-grid td'), wrap: pick('.grid-wrap') };
}
```

Сохранить результат (1280 и 390 — `browser_resize`, ⚠️ после каждой навигации проверять `window.innerWidth`: навигация сбрасывает вьюпорт, урок §12) и скриншоты грида в scratchpad.

- [ ] **Step 2: Общая сборка строк**

`frontend/src/app/shared/import-lines.ts`:

```ts
/**
 * Разбор Excel (D1): колонки файла, разметка оператора и сборка строк — один код на все экраны импорта
 * («Входящие», «Частные заявки», файлы чатов). Раньше логика была скопирована в каждый экран.
 */
export interface ImportColumn { index: number; header: string; field: string | null; }
export interface ImportPreview { columns: ImportColumn[]; rows: string[][]; }
export interface ImportLine { name: string; manufact: string | null; quantity: number; }
export interface ImportMapping { header: string; field: string; }

export const IMPORT_FIELD_OPTIONS = [
  { v: 'NAME', l: 'Наименование' },
  { v: 'MANUFACT', l: 'Бренд' },
  { v: 'QUANTITY', l: 'Кол-во' },
  { v: 'IGNORE', l: 'Игнорировать' },
];

/** Строки по разметке колонок; error — что сказать оператору, если собрать нечего. */
export function buildImportLines(preview: ImportPreview | null):
    { lines: ImportLine[]; mappings: ImportMapping[]; error: string | null } {
  const cols = preview?.columns || [];
  const nameCol = cols.find(c => c.field === 'NAME');
  if (!nameCol) return { lines: [], mappings: [], error: 'Отметьте колонку с наименованием' };
  const manuCol = cols.find(c => c.field === 'MANUFACT');
  const qtyCol = cols.find(c => c.field === 'QUANTITY');
  const lines = (preview?.rows || [])
    .map(row => ({
      name: row[nameCol.index],
      manufact: manuCol ? row[manuCol.index] : null,
      quantity: qtyCol ? (parseInt(row[qtyCol.index], 10) || 1) : 1,
    }))
    .filter(l => l.name && String(l.name).trim());
  if (!lines.length) return { lines: [], mappings: [], error: 'Нет строк с наименованием' };
  const mappings = cols
    .filter(c => c.field && c.field !== 'IGNORE')
    .map(c => ({ header: c.header, field: c.field as string }));
  return { lines, mappings, error: null };
}
```

- [ ] **Step 3: Компонент грида**

`frontend/src/app/shared/import-grid.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IMPORT_FIELD_OPTIONS, ImportPreview } from './import-lines';

/** Грид разбора Excel: заголовки файла + выбор роли колонки. Правит column.field на месте (как раньше в экранах). */
@Component({
  selector: 'app-import-grid',
  standalone: true,
  imports: [NgFor, FormsModule],
  template: `
    <div class="grid-wrap">
      <table class="import-grid">
        <thead>
          <tr>
            <th *ngFor="let c of preview.columns">
              <div class="ih">{{ c.header || '—' }}</div>
              <select [(ngModel)]="c.field" [ngModelOptions]="{standalone: true}"
                      [attr.aria-label]="'Роль колонки ' + (c.header || c.index + 1)">
                <option *ngFor="let o of options" [ngValue]="o.v">{{ o.l }}</option>
              </select>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let row of preview.rows">
            <td *ngFor="let cell of row">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .grid-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    /* Цвет текста НЕ приглушённый из kit: в шапке стоят заголовки ИЗ ФАЙЛА пользователя — данные, которые он
       сверяет с разметкой колонок, а не служебная подпись. Фон — как у kit-овского th (styles.scss). */
    .import-grid th { background: var(--surface-2); color: var(--text); padding: 8px; border: 1px solid var(--border); vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; background: var(--surface); color: var(--text); }
    .import-grid td { padding: 6px 8px; border: 1px solid var(--border); white-space: nowrap; }
  `],
})
export class ImportGridComponent {
  @Input({ required: true }) preview!: ImportPreview;
  readonly options = IMPORT_FIELD_OPTIONS;
}
```

- [ ] **Step 4: «Частные заявки» на общий грид**

`frontend/src/app/pages/private-requests/private-requests.component.ts`:

1. Импорты (после импорта `PrivateRequestCardComponent`):

```ts
import { ImportGridComponent } from '../../shared/import-grid.component';
import { buildImportLines } from '../../shared/import-lines';
```

2. `imports: [NgFor, NgIf, FormsModule, PrivateRequestCardComponent],` → `imports: [NgFor, NgIf, FormsModule, PrivateRequestCardComponent, ImportGridComponent],`

3. В шаблоне заменить блок от `<div class="grid-wrap">` до его закрывающего `</div>` (таблица `import-grid` с `thead`/`tbody`) на:

```html
          <app-import-grid [preview]="importPreview"></app-import-grid>
```

4. Удалить поле `fieldOptions = [ … ];` (4 опции).

5. В `createFromImport()` заменить всё от `const cols = this.importPreview?.columns || [];` до `.map((c: any) => ({ header: c.header, field: c.field }));` включительно на:

```ts
    const built = buildImportLines(this.importPreview);
    if (built.error) { this.importError = built.error; return; }
    const { lines, mappings } = built;
```

(проверка клиента `if (!this.importClientId) …` остаётся ПЕРВОЙ — как было.)

6. В `styles` удалить 9 строк от `.grid-wrap { … }` до `.import-grid td { … }` включительно (с комментарием «Цвет текста НЕ приглушённый…»).

- [ ] **Step 5: «Входящие» на общий грид**

`frontend/src/app/pages/inbound/inbound.component.ts`:

1. Импорты (после `NotificationService`):

```ts
import { ImportGridComponent } from '../../shared/import-grid.component';
import { buildImportLines } from '../../shared/import-lines';
```

2. `imports: [NgFor, NgIf, FormsModule],` → `imports: [NgFor, NgIf, FormsModule, ImportGridComponent],`

3. В шаблоне заменить блок `<div class="grid-wrap"> … </div>` (таблица `import-grid`) на:

```html
        <app-import-grid [preview]="importPreview"></app-import-grid>
```

4. Удалить поле `fieldOptions = [ … ];`.

5. В `createFromImport()` заменить всё от `const cols = this.importPreview?.columns || [];` до `.map((c: any) => ({ header: c.header, field: c.field }));` включительно на:

```ts
    const built = buildImportLines(this.importPreview);
    if (built.error) { this.importError = built.error; return; }
    const { lines, mappings } = built;
```

(здесь проверка колонок шла ДО проверки клиента — порядок сохраняется: `buildImportLines` стоит на месте прежнего кода.)

6. В `styles` удалить 10 строк от `.grid-wrap { … }` до `.import-grid td { … }` включительно (с комментарием «Цвет текста НЕ приглушённый…»).

- [ ] **Step 6: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка успешна; предупреждение о бюджете начального бандла было и раньше (1,26 МБ) — новых ошибок нет. `grep -rn "fieldOptions\|import-grid {" src/app/pages` — пусто.

- [ ] **Step 7: Снимок «после» и сверка**

Повторить Step 1 на тех же ширинах. Вычисленные стили `th/.ih/select/td/.grid-wrap` — совпадают с «до» до значения; скриншоты — визуально одинаковы. Создать заявку из файла (клиент — любой тестовый) → заявка создаётся с 3 строками; удалить её после проверки. «Входящие» живьём проверяются только при письме с Excel в локальной базе — если его нет, достаточно сборки: разметка и логика там те же.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/shared/import-lines.ts frontend/src/app/shared/import-grid.component.ts \
  frontend/src/app/pages/inbound/inbound.component.ts frontend/src/app/pages/private-requests/private-requests.component.ts
git commit -m "$(cat <<'EOF'
refactor(frontend): один грид разбора Excel — общий компонент вместо копий во «Входящих» и «Частных заявках»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Экран «Чаты»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Create: `frontend/src/app/shared/whatsapp-status.ts`, `shared/whatsapp-status-line.component.ts`, `shared/chat-message.component.ts`, `shared/lead-items-import.component.ts`, `pages/chats/chats.component.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/app.config.ts`, `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: REST задач 6–8; `ImportGridComponent`, `buildImportLines` (Task 10); `relativeTime`, `fullDateTime`, `LEAD_STATUS_LABELS`.
- Produces:
  - `ApiService`: `getChats({filter?, q?})`, `getChat(id)`, `getChatMessages(id, before?)`, `getChatAttachment(chatId, attachmentId): Observable<Blob>`, `previewChatAttachment(chatId, attachmentId)`, `createChatLead(chatId)`, `setChatNotClient(chatId, value)`, `getWhatsappStatus()`, `getLeadChatMessages(leadId)`, `importLeadItems(leadId, {mappings, items, mode})`
  - `whatsapp-status.ts`: `interface WhatsappStatus`, `whatsappStatusLine(s): {text, error} | null`, `formatPhone(raw): string`
  - `<app-whatsapp-status-line [status]>`, `<app-chat-message [m] [chatId] [showAuthor] [canParse] (parse)>`, `<app-lead-items-import [chatId] [attachment] [leadId] [existingItems] (done) (cancel)>` — последние два нужны и карточке обращения (Task 12)

- [ ] **Step 1: Методы API**

В `frontend/src/app/services/api.service.ts` после `runLeadSync()` добавить:

```ts
  // === Чаты WhatsApp (спека 2026-09-28-whatsapp-chats-green-api) ===
  getChats(params: { filter?: string; q?: string } = {}): Observable<any[]> {
    const p: any = {};
    if (params.filter) p.filter = params.filter;
    if (params.q) p.q = params.q;
    return this.http.get<any[]>(`${this.base}/chats`, { params: p });
  }
  getChat(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/chats/${id}`);
  }
  getChatMessages(id: number, before?: number): Observable<any[]> {
    const p: any = {};
    if (before != null) p.before = before;
    return this.http.get<any[]>(`${this.base}/chats/${id}/messages`, { params: p });
  }
  /** Только так (blob через HttpClient): голый <img src> не несёт X-Market → 404 на чат KZ. */
  getChatAttachment(chatId: number, attachmentId: number): Observable<Blob> {
    return this.http.get(`${this.base}/chats/${chatId}/attachments/${attachmentId}`, { responseType: 'blob' });
  }
  previewChatAttachment(chatId: number, attachmentId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/chats/${chatId}/attachments/${attachmentId}/preview`, {});
  }
  createChatLead(chatId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/chats/${chatId}/lead`, {});
  }
  setChatNotClient(chatId: number, value: boolean): Observable<any> {
    return this.http.post<any>(`${this.base}/chats/${chatId}/not-client`, { value });
  }
  getWhatsappStatus(): Observable<any> {
    return this.http.get<any>(`${this.base}/chats/status`);
  }
  getLeadChatMessages(leadId: number): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/leads/${leadId}/chat-messages`);
  }
  importLeadItems(leadId: number, body: { mappings: any[]; items: any[]; mode: 'REPLACE' | 'APPEND' }): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/${leadId}/items/import`, body);
  }
```

- [ ] **Step 2: Строка состояния WhatsApp**

`frontend/src/app/shared/whatsapp-status.ts`:

```ts
import { relativeTime } from './relative-time';

/** Ответ GET /api/chats/status (спека whatsapp-chats §7). */
export interface WhatsappStatus {
  enabled: boolean;
  configured: boolean;
  state: string | null;
  number: string | null;
  lastMessageAt: string | null;
  warnings: string[] | null;
  lastError: string | null;
}

export interface StatusLine { text: string; error: boolean; }

const WARNING_TEXT: Record<string, string> = {
  WEBHOOK_URL_SET: 'в инстансе указан адрес вебхука — сообщения уходят туда, а не в АИС. Очистите поле в кабинете Green-API',
  INCOMING_OFF: 'не включены уведомления о входящих',
  OUTGOING_PHONE_OFF: 'не включены уведомления об ответах с телефона — ваши ответы не попадут в АИС',
  QUOTA_EXCEEDED: 'исчерпан лимит бесплатного тарифа — сообщения из новых чатов не приходят',
  MESSAGE_DROPPED: 'одно сообщение не удалось принять — посмотрите его в телефоне',
};

const STATE_TEXT: Record<string, StatusLine> = {
  notAuthorized: { text: 'WhatsApp: номер не подключён — отсканируйте QR-код в кабинете Green-API', error: true },
  blocked: { text: 'WhatsApp: номер заблокирован WhatsApp', error: true },
  suspended: { text: 'WhatsApp: временные ограничения WhatsApp на номере', error: true },
  sleepMode: { text: 'WhatsApp: телефон выключен — сообщения придут, когда он появится в сети', error: true },
  starting: { text: 'WhatsApp: инстанс запускается', error: false },
};

/** Одна строка: выключено → нет ключей → ошибка → состояние → предупреждения → «подключён». */
export function whatsappStatusLine(s: WhatsappStatus | null): StatusLine | null {
  if (!s) return null;
  if (!s.enabled) return { text: 'WhatsApp: приём выключен', error: false };
  if (!s.configured) return { text: 'WhatsApp: не заданы учётные данные Green-API', error: true };
  if (s.lastError) return { text: 'WhatsApp: ' + s.lastError, error: true };
  if (!s.state) return { text: 'WhatsApp: подключение проверяется…', error: false };
  if (s.state !== 'authorized') {
    return STATE_TEXT[s.state] || { text: 'WhatsApp: состояние инстанса — ' + s.state, error: true };
  }
  const warnings = (s.warnings || []).map(w => WARNING_TEXT[w] || w);
  if (warnings.length) return { text: 'WhatsApp: ' + warnings.join('; '), error: true };
  const last = s.lastMessageAt ? ' · последнее сообщение ' + relativeTime(s.lastMessageAt) : '';
  return { text: 'WhatsApp' + (s.number ? ' ' + formatPhone(s.number) : '') + ' · подключён' + last, error: false };
}

/** «77000000001» / «+77000000001» → «+7 700 000 00 01». */
export function formatPhone(raw: string): string {
  const d = (raw || '').replace(/\D/g, '');
  if (d.length === 11) return `+${d[0]} ${d.slice(1, 4)} ${d.slice(4, 7)} ${d.slice(7, 9)} ${d.slice(9)}`;
  return d ? '+' + d : '';
}
```

`frontend/src/app/shared/whatsapp-status-line.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { NgIf } from '@angular/common';
import { WhatsappStatus, whatsappStatusLine } from './whatsapp-status';

/** Строка «WhatsApp …» на «Чатах» и «Обращениях» (спека §7). Красная, если что-то мешает приёму. */
@Component({
  selector: 'app-whatsapp-status-line',
  standalone: true,
  imports: [NgIf],
  template: `<div class="wa-plate" *ngIf="line as l" [class.is-error]="l.error">{{ l.text }}</div>`,
  styles: [`
    .wa-plate { font-size: 13px; color: var(--text-muted); background: var(--surface-2); border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; }
    .wa-plate.is-error { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
  `],
})
export class WhatsappStatusLineComponent {
  @Input() status: WhatsappStatus | null = null;
  get line() { return whatsappStatusLine(this.status); }
}
```

- [ ] **Step 3: Сообщение чата**

`frontend/src/app/shared/chat-message.component.ts`:

```ts
import { ChangeDetectorRef, Component, EventEmitter, HostListener, Input, OnChanges, OnDestroy, Output } from '@angular/core';
import { NgIf } from '@angular/common';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';
import { fullDateTime } from './relative-time';

const NOT_STORED: Record<string, string> = {
  TOO_LARGE: 'файл больше предела — смотрите в телефоне',
  GROUP: 'файлы групп не сохраняются — смотрите в телефоне',
  DOWNLOAD_FAILED: 'не удалось скачать — смотрите в телефоне',
};

/**
 * Сообщение чата пузырём (спека whatsapp-chats §9.2): входящие слева, исходящие справа. Файлы — ТОЛЬКО через
 * HttpClient blob: голый <img src="/api/…"> не несёт X-Market, и бэкенд ответил бы 404 на чат KZ.
 * Полный размер фото — оверлей в странице (window.open с blob на iOS режется).
 */
@Component({
  selector: 'app-chat-message',
  standalone: true,
  imports: [NgIf],
  template: `
    <div class="row" [class.out]="m.direction === 'OUT'">
      <div class="bubble" [class.out]="m.direction === 'OUT'" [class.deleted]="m.deleted">
        <div class="author" *ngIf="showAuthor && m.direction === 'IN' && m.senderName">{{ m.senderName }}</div>
        <ng-container *ngIf="m.attachment as a">
          <button type="button" class="thumb" *ngIf="a.image" (click)="zoomed = true" [attr.aria-label]="'Открыть ' + (a.fileName || 'фото')">
            <img *ngIf="imageUrl" [src]="imageUrl" [alt]="a.fileName || 'фото'" />
            <span *ngIf="!imageUrl" class="thumb-ph">фото загружается…</span>
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
    <div class="zoom" *ngIf="zoomed && imageUrl" (click)="zoomed = false" role="dialog" aria-label="Фото целиком">
      <img [src]="imageUrl" [alt]="m.attachment?.fileName || 'фото'" />
    </div>
  `,
  styles: [`
    .row { display: flex; margin: 4px 0; }
    .row.out { justify-content: flex-end; }
    .bubble { max-width: min(78%, 560px); background: var(--surface-2); border: 1px solid var(--border); border-radius: 12px; padding: 8px 10px; font-size: 14px; color: var(--text); }
    /* исходящие — подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit, §12 CLAUDE.md) */
    .bubble.out { background: color-mix(in srgb, var(--success) 8%, var(--surface)); border-color: color-mix(in srgb, var(--success) 35%, var(--border)); }
    .author { font-size: 12px; font-weight: 600; color: var(--accent); margin-bottom: 2px; }
    .text { white-space: pre-line; overflow-wrap: anywhere; }
    .bubble.deleted .text { text-decoration: line-through; color: var(--text-muted); }
    .meta { font-size: 11px; color: var(--text-muted); text-align: right; margin-top: 4px; }
    .thumb { display: block; padding: 0; border: none; background: none; cursor: zoom-in; margin-bottom: 4px; }
    .thumb img { display: block; max-width: 220px; max-height: 220px; border-radius: 8px; }
    .thumb-ph { display: inline-block; font-size: 12px; color: var(--text-muted); padding: 24px 12px; }
    .file { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; font-size: 13px; }
    .file.muted { color: var(--text-muted); }
    .linklike { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; text-align: left; overflow-wrap: anywhere; }
    .size { color: var(--text-muted); }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    /* вуаль — самое частое значение приложения; пятое не заводить (§16 CLAUDE.md) */
    .zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; cursor: zoom-out; }
    .zoom img { max-width: 94vw; max-height: 90vh; border-radius: 8px; }
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
  zoomed = false;
  private loadedFor: number | null = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnChanges() {
    const a = this.m?.attachment;
    if (a?.image && this.loadedFor !== a.id) {
      this.loadedFor = a.id;
      this.api.getChatAttachment(this.chatId, a.id).subscribe({
        next: blob => { this.revoke(); this.imageUrl = URL.createObjectURL(blob); this.cdr.detectChanges(); },
        error: () => {},
      });
    }
  }

  ngOnDestroy() { this.revoke(); }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.zoomed) { this.zoomed = false; this.cdr.detectChanges(); }
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
      error: () => this.notify.error('Файл не скачался — смотрите его в телефоне'),
    });
  }

  size(bytes: number): string {
    if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1).replace('.', ',') + ' МБ';
    if (bytes >= 1024) return Math.round(bytes / 1024) + ' КБ';
    return bytes + ' Б';
  }

  notStored(reason: string): string { return NOT_STORED[reason] || 'не сохранён — смотрите в телефоне'; }

  full(iso: string) { return fullDateTime(iso); }

  /** Сегодня — «14:05», иначе «28.09 14:05». */
  time(iso: string): string {
    const t = new Date(iso);
    if (isNaN(t.getTime())) return '';
    const hm = t.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
    return t.toDateString() === new Date().toDateString()
      ? hm : t.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }) + ' ' + hm;
  }

  private revoke() {
    if (this.imageUrl) {
      URL.revokeObjectURL(this.imageUrl);
      this.imageUrl = null;
    }
  }
}
```

- [ ] **Step 4: «Разобрать в позиции»**

`frontend/src/app/shared/lead-items-import.component.ts`:

```ts
import { ChangeDetectorRef, Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { NgIf } from '@angular/common';
import { ApiService } from '../services/api.service';
import { ImportGridComponent } from './import-grid.component';
import { buildImportLines, ImportPreview } from './import-lines';

/** Excel из чата → грид D1 → позиции обращения: заполнить / заменить / добавить (спека whatsapp-chats §9.4). */
@Component({
  selector: 'app-lead-items-import',
  standalone: true,
  imports: [NgIf, ImportGridComponent],
  template: `
    <section class="lii" aria-label="Позиции из Excel">
      <div class="lii-head">
        <strong>Позиции из «{{ attachment.fileName || 'файла' }}»</strong>
        <button type="button" class="x" (click)="cancel.emit()" aria-label="Закрыть">×</button>
      </div>
      <p class="hint" *ngIf="loading">Разбираю файл…</p>
      <p class="hint" *ngIf="preview">Проверьте роли колонок — система разметила их сама и запомнит ваши правки.</p>
      <app-import-grid *ngIf="preview" [preview]="preview"></app-import-grid>
      <div class="err" *ngIf="error">{{ error }}</div>
      <div class="lii-actions" *ngIf="preview">
        <ng-container *ngIf="existingItems > 0; else fill">
          <button type="button" class="btn btn-primary" [disabled]="busy" (click)="apply('REPLACE')">Заменить позиции ({{ existingItems }})</button>
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="apply('APPEND')">Добавить к позициям</button>
        </ng-container>
        <ng-template #fill>
          <button type="button" class="btn btn-primary" [disabled]="busy" (click)="apply('REPLACE')">Заполнить позиции</button>
        </ng-template>
        <button type="button" class="btn btn-cancel" (click)="cancel.emit()">Отмена</button>
      </div>
    </section>
  `,
  styles: [`
    .lii { border: 1px solid var(--border); border-radius: 10px; padding: 12px; margin: 10px 0; background: var(--surface); display: flex; flex-direction: column; gap: 8px; }
    .lii-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .x { background: none; border: none; font-size: 22px; line-height: 1; cursor: pointer; color: var(--text-muted); }
    .hint { font-size: 12px; color: var(--text-muted); margin: 0; }
    .err { color: var(--danger-text); font-size: 13px; }
    .lii-actions { display: flex; gap: 8px; flex-wrap: wrap; }
  `],
})
export class LeadItemsImportComponent implements OnInit {
  @Input({ required: true }) chatId!: number;
  @Input({ required: true }) attachment!: any;
  @Input({ required: true }) leadId!: number;
  @Input() existingItems = 0;
  /** Свежая карточка обращения после сохранения. */
  @Output() done = new EventEmitter<any>();
  @Output() cancel = new EventEmitter<void>();

  preview: ImportPreview | null = null;
  loading = true;
  busy = false;
  error = '';

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.api.previewChatAttachment(this.chatId, this.attachment.id).subscribe({
      next: p => { this.preview = p; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.error = e.error?.message || 'Файл не разобрался'; this.cdr.detectChanges(); },
    });
  }

  apply(mode: 'REPLACE' | 'APPEND') {
    const built = buildImportLines(this.preview);
    if (built.error) { this.error = built.error; return; }
    this.busy = true;
    this.error = '';
    const items = built.lines.map(l => ({
      name: String(l.name).trim(),
      brand: l.manufact && String(l.manufact).trim() ? String(l.manufact).trim() : null,
      quantity: l.quantity,
    }));
    this.api.importLeadItems(this.leadId, { mappings: built.mappings, items, mode }).subscribe({
      next: card => { this.busy = false; this.done.emit(card); },
      error: e => { this.busy = false; this.error = e.error?.message || 'Позиции не сохранились'; this.cdr.detectChanges(); },
    });
  }
}
```

- [ ] **Step 5: Экран «Чаты»**

`frontend/src/app/pages/chats/chats.component.ts`:

```ts
import { ChangeDetectorRef, Component, ElementRef, OnDestroy, ViewChild } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { fullDateTime, relativeTime } from '../../shared/relative-time';
import { LEAD_STATUS_LABELS } from '../../shared/lead-labels';
import { ChatMessageComponent } from '../../shared/chat-message.component';
import { WhatsappStatusLineComponent } from '../../shared/whatsapp-status-line.component';
import { LeadItemsImportComponent } from '../../shared/lead-items-import.component';
import { formatPhone, WhatsappStatus } from '../../shared/whatsapp-status';

const PAGE = 50;

/**
 * «Чаты» — все чаты рабочего номера WhatsApp, только чтение (спека whatsapp-chats §9.2). Обращения живут поверх:
 * чип статуса, «Создать обращение», «не клиент». На телефоне — мастер-деталь: список ↔ переписка (?chatId=).
 */
@Component({
  selector: 'app-chats',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink, ChatMessageComponent, WhatsappStatusLineComponent, LeadItemsImportComponent],
  template: `
    <div class="page-head">
      <div>
        <h2>Чаты</h2>
        <p class="subtitle">Вся переписка рабочего номера WhatsApp. Отвечаете с телефона — ответ появится здесь.</p>
      </div>
    </div>
    <app-whatsapp-status-line [status]="waStatus"></app-whatsapp-status-line>

    <div class="layout" [class.has-open]="openId !== null">
      <section class="list-pane" aria-label="Чаты">
        <div class="list-tools">
          <input type="search" [(ngModel)]="q" (input)="onSearch()" placeholder="Имя, номер, текст…" aria-label="Поиск по чатам" />
          <div class="chips" role="group" aria-label="Фильтр чатов">
            <button type="button" class="chip" *ngFor="let f of filters" [class.on]="filter === f.key" (click)="setFilter(f.key)">{{ f.label }}</button>
          </div>
        </div>
        <div class="empty" *ngIf="!loadingList && !chats.length">Чатов нет</div>
        <div class="chat-list">
          <button type="button" class="chat-row" *ngFor="let c of chats; trackBy: trackById"
                  [class.active]="c.id === openId" (click)="openChat(c.id)">
            <span class="cr-top">
              <span class="cr-title">{{ title(c) }}</span>
              <time class="cr-time" [attr.title]="full(c.lastMessageAt)">{{ ago(c.lastMessageAt) }}</time>
            </span>
            <span class="cr-preview">{{ c.lastMessagePreview || '—' }}</span>
            <span class="cr-tags" *ngIf="c.group || c.notClient || c.lead">
              <span class="tag" *ngIf="c.group">группа</span>
              <span class="tag" *ngIf="c.notClient">не клиент</span>
              <span class="st" *ngIf="c.lead" [attr.data-status]="c.lead.status">{{ statusLabel(c.lead.status) }}</span>
            </span>
          </button>
        </div>
      </section>

      <section class="thread-pane" *ngIf="openId !== null; else pick" aria-label="Переписка">
        <header class="thread-head">
          <button type="button" class="btn btn-line back" (click)="closeChat()">← Назад</button>
          <div class="th-title" *ngIf="chat">
            <strong>{{ title(chat) }}</strong>
            <span class="th-phone" *ngIf="chat.phone && chat.title">{{ phone(chat) }}</span>
          </div>
          <div class="th-actions" *ngIf="chat">
            <a class="btn btn-line btn-sm" *ngIf="waLink(chat)" [href]="waLink(chat)" target="_blank" rel="noopener">Написать в WhatsApp</a>
            <a class="btn btn-line btn-sm" *ngIf="chat.lead" [routerLink]="['/leads']" [queryParams]="{ openId: chat.lead.id }">Обращение · {{ statusLabel(chat.lead.status) }}</a>
            <button type="button" class="btn btn-primary btn-sm" *ngIf="canCreateLead()" [disabled]="busy" (click)="createLead()">Создать обращение</button>
            <label class="nc" *ngIf="auth.isAdmin() && !chat.group">
              <input type="checkbox" [checked]="chat.notClient" [disabled]="busy" (change)="toggleNotClient($event)" /> не клиент
            </label>
          </div>
        </header>
        <app-lead-items-import *ngIf="parseAttachment && chat?.lead" [chatId]="openId!" [attachment]="parseAttachment"
                               [leadId]="chat.lead.id" [existingItems]="parseLeadItems"
                               (done)="onItemsImported($event)" (cancel)="parseAttachment = null"></app-lead-items-import>
        <div class="thread" #thread>
          <div class="more" *ngIf="hasMore">
            <button type="button" class="btn btn-line btn-sm" [disabled]="loadingMore" (click)="loadMore()">Показать раньше</button>
          </div>
          <div class="empty" *ngIf="loadingThread">Загрузка…</div>
          <app-chat-message *ngFor="let m of messages; trackBy: trackById" [m]="m" [chatId]="openId!"
                            [showAuthor]="!!chat?.group" [canParse]="canParse()" (parse)="startParse($event)"></app-chat-message>
          <div class="empty" *ngIf="!loadingThread && !messages.length">Сообщений нет</div>
        </div>
      </section>
      <ng-template #pick><div class="thread-empty">Выберите чат слева</div></ng-template>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .layout { display: grid; grid-template-columns: minmax(260px, 340px) minmax(0, 1fr); gap: 12px; height: calc(100vh - 210px); min-height: 420px; }
    .list-pane, .thread-pane, .thread-empty { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; min-height: 0; }
    .list-pane { display: flex; flex-direction: column; overflow: hidden; }
    .list-tools { padding: 10px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 8px; }
    .list-tools input { width: 100%; box-sizing: border-box; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .chat-list { overflow-y: auto; flex: 1; }
    .chat-row { display: flex; flex-direction: column; gap: 3px; width: 100%; text-align: left; background: none; border: none; border-bottom: 1px solid var(--border); padding: 10px 12px; cursor: pointer; color: var(--text); }
    .chat-row:hover { background: color-mix(in srgb, var(--accent) 5%, var(--surface)); }
    /* выбранный чат — подсветка ОБЛАСТИ: 8% тинта поверх --surface + кромка (правило kit) */
    .chat-row.active { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); box-shadow: inset 3px 0 0 var(--accent); }
    .cr-top { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; min-width: 0; }
    .cr-title { font-weight: 600; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cr-time { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
    .cr-preview { font-size: 13px; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cr-tags { display: flex; gap: 6px; flex-wrap: wrap; }
    .tag { font-size: 11px; padding: 1px 8px; border-radius: 6px; background: var(--surface-2); color: var(--text-muted); }
    /* чипы: 15% тинта + текстовый токен (правило kit) */
    .st { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); }
    .st[data-status="NEW"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="IN_WORK"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .st[data-status="CONVERTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .thread-pane { display: flex; flex-direction: column; overflow: hidden; }
    .thread-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 12px; border-bottom: 1px solid var(--border); }
    .th-title { display: flex; flex-direction: column; min-width: 0; flex: 1; }
    .th-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .th-phone { font-size: 12px; color: var(--text-muted); }
    .th-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    a.btn { display: inline-flex; align-items: center; text-decoration: none; }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    .nc { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--text-muted); cursor: pointer; }
    .back { display: none; }
    .thread { flex: 1; overflow-y: auto; padding: 10px 12px; }
    .more { display: flex; justify-content: center; margin-bottom: 8px; }
    .thread-empty { display: flex; align-items: center; justify-content: center; color: var(--text-muted); }
    @media (max-width: 900px) {
      .layout { display: block; height: auto; min-height: 0; }
      .layout.has-open .list-pane { display: none; }
      .thread-empty { display: none; }
      .thread-pane { height: calc(100dvh - 190px); min-height: 360px; }
      .chat-list { overflow: visible; }
      .back { display: inline-flex; }
      .list-tools input { font-size: 16px; }
      .chat-row { padding: 12px; }
    }
  `],
})
export class ChatsComponent implements OnDestroy {
  @ViewChild('thread') threadRef?: ElementRef<HTMLElement>;

  chats: any[] = [];
  loadingList = false;
  filter = 'ALL';
  q = '';
  openId: number | null = null;
  chat: any = null;
  messages: any[] = [];
  hasMore = false;
  loadingThread = false;
  loadingMore = false;
  waStatus: WhatsappStatus | null = null;
  busy = false;
  parseAttachment: any = null;
  parseLeadItems = 0;
  readonly filters = [
    { key: 'ALL', label: 'Все' },
    { key: 'WITH_LEAD', label: 'С обращением' },
    { key: 'WITHOUT_LEAD', label: 'Без обращения' },
    { key: 'GROUPS', label: 'Группы' },
  ];
  private readonly refreshTimer: any;
  private searchTimer: any = null;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef) {
    // Без detectChanges: первое значение приходит синхронно ещё в конструкторе, до создания вида (урок §14)
    this.route.queryParams.subscribe(p => {
      const id = p['chatId'] ? +p['chatId'] : null;
      if (id !== this.openId) { this.openId = id; this.onOpenChanged(); }
    });
    this.loadList();
    this.loadStatus();
    // сообщения приходят фоном — список, строка состояния и открытый чат обновляются, пока экран открыт
    this.refreshTimer = setInterval(() => {
      this.loadList(true);
      this.loadStatus();
      if (this.openId !== null) this.refreshThread();
    }, 10000);
  }

  ngOnDestroy() {
    clearInterval(this.refreshTimer);
    clearTimeout(this.searchTimer);
  }

  loadList(silent = false) {
    if (!silent) this.loadingList = true;
    this.api.getChats({ filter: this.filter, q: this.q.trim() || undefined }).subscribe({
      next: d => { this.chats = d; this.loadingList = false; this.cdr.detectChanges(); },
      error: e => {
        this.loadingList = false;
        if (!silent) this.notify.error('Чаты не загрузились: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  loadStatus() {
    this.api.getWhatsappStatus().subscribe({ next: s => { this.waStatus = s; this.cdr.detectChanges(); }, error: () => {} });
  }

  setFilter(key: string) { this.filter = key; this.loadList(); }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.loadList(), 300);
  }

  openChat(id: number) {
    this.router.navigate([], { relativeTo: this.route, queryParams: { chatId: id }, queryParamsHandling: 'merge' });
  }

  closeChat() {
    this.router.navigate([], { relativeTo: this.route, queryParams: { chatId: null }, queryParamsHandling: 'merge' });
  }

  loadMore() {
    const id = this.openId;
    if (id === null || !this.messages.length) return;
    const el = this.threadRef?.nativeElement;
    const fromBottom = el ? el.scrollHeight - el.scrollTop : 0;
    this.loadingMore = true;
    this.api.getChatMessages(id, this.messages[0].id).subscribe({
      next: page => {
        if (id !== this.openId) return;
        this.messages = [...page, ...this.messages];
        this.hasMore = page.length === PAGE;
        this.loadingMore = false;
        this.cdr.detectChanges();
        if (el) el.scrollTop = el.scrollHeight - fromBottom;   // держим место чтения
      },
      error: () => { this.loadingMore = false; this.cdr.detectChanges(); },
    });
  }

  createLead() {
    if (!this.chat) return;
    this.busy = true;
    this.api.createChatLead(this.chat.id).subscribe({
      next: c => {
        this.chat = c;
        this.busy = false;
        this.notify.success('Обращение создано');
        this.loadList(true);
        this.cdr.detectChanges();
      },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не получилось'); this.cdr.detectChanges(); },
    });
  }

  toggleNotClient(ev: Event) {
    if (!this.chat) return;
    const box = ev.target as HTMLInputElement;
    const value = box.checked;
    this.busy = true;
    this.api.setChatNotClient(this.chat.id, value).subscribe({
      next: c => {
        this.chat = c;
        this.busy = false;
        this.notify.success(value ? 'Чат помечен «не клиент» — обращения из него не создаются' : 'Отметка «не клиент» снята');
        this.loadList(true);
        this.cdr.detectChanges();
      },
      error: e => {
        this.busy = false;
        box.checked = !value;
        this.notify.error(e.error?.message || 'Не получилось');
        this.cdr.detectChanges();
      },
    });
  }

  startParse(attachment: any) {
    if (!this.chat?.lead) return;
    this.api.getLead(this.chat.lead.id).subscribe({
      next: card => {
        this.parseLeadItems = card.items?.length || 0;
        this.parseAttachment = attachment;
        this.cdr.detectChanges();
      },
      error: e => this.notify.error(e.error?.message || 'Обращение не открылось'),
    });
  }

  onItemsImported(card: any) {
    this.parseAttachment = null;
    this.notify.success('Позиции обращения обновлены: ' + (card.items?.length || 0) + ' поз.');
    this.cdr.detectChanges();
  }

  canCreateLead(): boolean { return this.auth.isAdmin() && !!this.chat && !this.chat.group && !this.chat.leadOpen; }

  canParse(): boolean {
    const s = this.chat?.lead?.status;
    return this.auth.isAdmin() && (s === 'NEW' || s === 'IN_WORK');
  }

  waLink(c: any): string | null {
    const d = (c?.phone || '').replace(/\D/g, '');
    return d.length >= 10 ? 'https://wa.me/' + d : null;
  }

  title(c: any): string { return c?.title || (c?.phone ? formatPhone(c.phone) : 'Без имени'); }
  phone(c: any): string { return c?.phone ? formatPhone(c.phone) : ''; }
  statusLabel(s: string) { return LEAD_STATUS_LABELS[s] || s; }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }
  trackById(_: number, x: any) { return x.id; }

  private onOpenChanged() {
    this.chat = null;
    this.messages = [];
    this.hasMore = false;
    this.parseAttachment = null;
    const id = this.openId;
    if (id === null) return;
    this.loadingThread = true;
    this.api.getChat(id).subscribe({
      next: c => { if (id === this.openId) { this.chat = c; this.cdr.detectChanges(); } },
      error: e => this.notify.error('Чат не открылся: ' + (e.error?.message || e.message)),
    });
    this.api.getChatMessages(id).subscribe({
      next: page => {
        if (id !== this.openId) return;
        this.messages = page;
        this.hasMore = page.length === PAGE;
        this.loadingThread = false;
        this.cdr.detectChanges();
        this.scrollToBottom();
      },
      error: () => { this.loadingThread = false; this.cdr.detectChanges(); },
    });
  }

  /** Новые сообщения открытого чата — дописываем; правки/удаления — обновляем на месте. */
  private refreshThread() {
    const id = this.openId;
    if (id === null) return;
    this.api.getChatMessages(id).subscribe({
      next: page => {
        if (id !== this.openId) return;
        const nearBottom = this.isNearBottom();
        const known = new Map(this.messages.map(m => [m.id, m]));
        let changed = false;
        for (const m of page) {
          const k = known.get(m.id);
          if (!k) { this.messages.push(m); changed = true; }
          else if (k.body !== m.body || k.edited !== m.edited || k.deleted !== m.deleted) { Object.assign(k, m); changed = true; }
        }
        if (!changed) return;
        const t = (x: any) => new Date(x.sentAt).getTime();
        this.messages.sort((a, b) => t(a) - t(b) || a.id - b.id);
        this.cdr.detectChanges();
        if (nearBottom) this.scrollToBottom();
      },
      error: () => {},
    });
    this.api.getChat(id).subscribe({
      next: c => { if (id === this.openId) { this.chat = c; this.cdr.detectChanges(); } },
      error: () => {},
    });
  }

  private isNearBottom(): boolean {
    const el = this.threadRef?.nativeElement;
    return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  private scrollToBottom() {
    setTimeout(() => {
      const el = this.threadRef?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
```

- [ ] **Step 6: Маршрут, иконка, меню**

`frontend/src/app/app.routes.ts`: импорт `import { ChatsComponent } from './pages/chats/chats.component';` и строка сразу после `{ path: 'leads', component: LeadsComponent },`:

```ts
      { path: 'chats', component: ChatsComponent },
```

`frontend/src/app/app.config.ts`: добавить `LucideMessagesSquare` в список импорта из `@lucide/angular` (после `LucideMonitorSmartphone`) и в `provideLucideIcons(...)` (после `LucideMonitorSmartphone`).

`frontend/src/app/layout/layout.component.ts`: сразу после ссылки «Обращения» (закрывающий `</a>` блока `routerLink="/leads"`):

```html
            <a routerLink="/chats" routerLinkActive="active">
              <svg lucideIcon="messages-square" [size]="16"></svg> Чаты
            </a>
```

- [ ] **Step 7: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: успешно, без новых предупреждений бюджета стилей компонентов (у каждого свой бюджет 24 kB). Хексов в новых файлах нет: `grep -nE "#[0-9a-fA-F]{3,8}\b" src/app/shared/chat-message.component.ts src/app/shared/lead-items-import.component.ts src/app/shared/whatsapp-status-line.component.ts src/app/pages/chats/chats.component.ts` — пусто.

- [ ] **Step 8: Живая проверка (Playwright), стаб + бэкенд + фронт**

Стаб и бэкенд — как в Task 9 Step 3 (свежий стаб → `curl -s -X POST localhost:7707/__scenario/basic`), фронт `npm start`. Вход admin/admin, `localStorage.setItem('ais.market','KZ')`, перейти на `/chats`. Проверить и зафиксировать скриншотами (1280 и 390, светлая и тёмная тема):

1. Меню: пункт «Чаты» сразу после «Обращений», иконка видна (`svg.children.length > 0`, урок §14).
2. Строка: «WhatsApp +7 700 000 00 01 · подключён · последнее сообщение …».
3. Список: три чата; у «Айгерим (тест)» — чип «В работе», у «Ерлан (тест)» — «Новое», у группы — «группа»; превью — последнее сообщение.
4. «Айгерим (тест)»: входящие слева, ответ «Добрый день! Подготовим КП сегодня.» справа; текст корзины — списком по строкам; миниатюра фото (в Network запрос `/api/chats/…/attachments/…` с заголовком `X-Market: KZ` → 200), клик — оверлей, Esc закрывает; «ТЗ.pdf» скачивается; «страница.html» СКАЧИВАЕТСЯ, не открывается (в ответе `Content-Disposition: attachment`, `application/octet-stream`); у Excel — «Разобрать в позиции» → грид → «Заменить позиции (2)» → тост «Позиции обращения обновлены: 3 поз.».
5. «Ерлан (тест)»: сообщение «…экспертного класса» с пометкой «изменено»; кнопки «Создать обращение» нет (обращение открыто).
6. Группа: имя автора «Данияр» над сообщениями; «Кто завтра едет в Уральск?» зачёркнуто, «удалено отправителем»; фото группы — «файлы групп не сохраняются — смотрите в телефоне»; переключателя «не клиент» нет.
7. Фильтры: «С обращением» — 2, «Без обращения» — 0, «Группы» — 1; поиск «УЗИ» — «Ерлан (тест)».
8. Живое обновление: `curl -s localhost:7707/__enqueue -H 'Content-Type: application/json' -d '{"typeWebhook":"incomingMessageReceived","instanceData":{"idInstance":1101,"wid":"77000000001@c.us","typeInstance":"whatsapp"},"timestamp":'$(date +%s)',"idMessage":"LIVE1","senderData":{"chatId":"77029876543@c.us","sender":"77029876543@c.us","chatName":"Ерлан (тест)","senderName":"Ерлан (тест)","senderContactName":""},"messageData":{"typeMessage":"textMessage","textMessageData":{"textMessage":"Ещё нужен принтер"}}}'` при открытом чате Ерлана → сообщение появляется ≤ 10 с, лента прокручена вниз.
9. Состояние: `curl -s -X POST localhost:7707/__state/notAuthorized` → строка красная «номер не подключён…» ≤ 10 с; вернуть `…/__state/authorized`.
10. «не клиент»: чат без обращения (написать через `/__enqueue` ИСХОДЯЩЕЕ новому номеру) → поставить галку → затем входящее от него → обращение не создаётся, чат остаётся «Без обращения»; «Создать обращение» на нём → чип «В работе».
11. 390px: список на весь экран, тап → переписка на весь экран, «← Назад» возвращает; горизонтального скролла нет (`document.documentElement.scrollWidth <= innerWidth`); ⚠️ после каждой навигации проверять `window.innerWidth` (урок §12).
12. Оператор (operator/operator): нет «Создать обращение», «не клиент», «Разобрать в позиции»; просмотр и скачивание — есть.
13. Консоль браузера без ошибок.

Найденное — чинить в этой же задаче, пересобирать, перепроверять пункт.

- [ ] **Step 9: Убрать тестовые данные** — SQL из Task 9 Step 4 (+ чаты номеров, созданных в пунктах 8/10: `account = '77000000001'` покрывает все).

- [ ] **Step 10: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts frontend/src/app/shared/whatsapp-status.ts \
  frontend/src/app/shared/whatsapp-status-line.component.ts frontend/src/app/shared/chat-message.component.ts \
  frontend/src/app/shared/lead-items-import.component.ts frontend/src/app/pages/chats/chats.component.ts \
  frontend/src/app/app.routes.ts frontend/src/app/app.config.ts frontend/src/app/layout/layout.component.ts
git commit -m "$(cat <<'EOF'
feat(whatsapp): экран «Чаты» — список, переписка, файлы через blob, «Создать обращение», «не клиент», строка состояния

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Переписка в карточке обращения и строка WhatsApp на «Обращениях»

**Files:**
- Modify: `frontend/src/app/pages/leads/lead-card.component.ts`, `frontend/src/app/pages/leads/leads.component.ts`

**Interfaces:**
- Consumes: Task 11 (`ChatMessageComponent`, `LeadItemsImportComponent`, `WhatsappStatusLineComponent`, `WhatsappStatus`, `ApiService.getLeadChatMessages/getWhatsappStatus`), Task 8 (`LeadCardResponse.chatId`).

- [ ] **Step 1: Карточка обращения — импорты и поля**

`frontend/src/app/pages/leads/lead-card.component.ts`:

1. Первую строку импортов дополнить `ElementRef, ViewChild`:

```ts
import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef, HostListener, ElementRef, ViewChild } from '@angular/core';
```

2. После импорта `LeadConvertDialogComponent`:

```ts
import { ChatMessageComponent } from '../../shared/chat-message.component';
import { LeadItemsImportComponent } from '../../shared/lead-items-import.component';
```

3. `imports: [NgIf, NgFor, FormsModule, RouterLink, LeadConvertDialogComponent],` → `imports: [NgIf, NgFor, FormsModule, RouterLink, LeadConvertDialogComponent, ChatMessageComponent, LeadItemsImportComponent],`

4. Поля класса (после `readonly reasons = LEAD_CLOSE_REASONS;`):

```ts
  /** Переписка чата обращения (спека whatsapp-chats §9.3) и лента вперемешку с ней. */
  chatMessages: any[] = [];
  timeline: any[] = [];
  parseAttachment: any = null;
  @ViewChild(LeadItemsImportComponent, { read: ElementRef }) importRef?: ElementRef<HTMLElement>;
```

- [ ] **Step 2: Карточка — шаблон**

1. В секции «Что просят» перед её `</section>` (сразу после блока `<div class="items-edit" …>…</div>` с кнопками «Сохранить позиции»/«Отмена») вставить:

```html
              <app-lead-items-import *ngIf="parseAttachment && lead.chatId" [chatId]="lead.chatId" [attachment]="parseAttachment"
                                     [leadId]="lead.id" [existingItems]="lead.items.length"
                                     (done)="onItemsImported($event)" (cancel)="parseAttachment = null"></app-lead-items-import>
```

2. Секцию «Лента» (от `<section class="section">` с `<h3 class="section-title">Лента</h3>` до её `</section>`) заменить целиком:

```html
            <section class="section">
              <div class="section-head">
                <h3 class="section-title">{{ lead.chatId ? 'Лента и переписка' : 'Лента' }}</h3>
                <a class="btn btn-line btn-sm" *ngIf="lead.chatId" [routerLink]="['/chats']" [queryParams]="{ chatId: lead.chatId }">Открыть весь чат</a>
              </div>
              <ol class="timeline">
                <ng-container *ngFor="let t of timeline; trackBy: trackTimeline">
                  <li *ngIf="t.kind === 'event'" [attr.data-type]="t.e.type">
                    <div class="t-head">
                      <span class="t-type">{{ eventLabel(t.e) }}</span>
                      <time [attr.title]="full(t.e.at)">{{ ago(t.e.at) }}</time>
                      <span *ngIf="t.e.author">{{ t.e.author }}</span>
                    </div>
                    <div class="t-body" *ngIf="t.e.body">{{ t.e.body }}</div>
                  </li>
                  <li *ngIf="t.kind === 'msg'" class="msg">
                    <app-chat-message [m]="t.m" [chatId]="lead.chatId" [canParse]="auth.isAdmin() && canEditItems()"
                                      (parse)="startParse($event)"></app-chat-message>
                  </li>
                </ng-container>
              </ol>
            </section>
```

3. В `styles` после строки `.timeline li[data-type="SYNC"] { … }` добавить:

```css
    .timeline li.msg { border-left: none; padding-left: 0; }
```

- [ ] **Step 3: Карточка — логика**

1. В `ngOnChanges` внутри `if (ch['leadId']) {` сразу после `this.panel = 'none';`:

```ts
      this.parseAttachment = null;
      this.chatMessages = [];
      this.timeline = [];
```

2. В `load(id)` заменить `next: d => { this.lead = d; this.loading = false; this.cdr.detectChanges(); },` на:

```ts
      next: d => {
        this.lead = d;
        this.loading = false;
        this.rebuildTimeline();
        this.loadChatMessages();
        this.cdr.detectChanges();
      },
```

3. В `apply(...)` в ветке `next` после `this.lead = d;` добавить `this.rebuildTimeline();`.

4. Методы (после `saveItems()`):

```ts
  /** Переписка чата обращения — с момента обращения (спека whatsapp-chats §9.3). */
  private loadChatMessages() {
    const lead = this.lead;
    if (!lead?.chatId) return;
    this.api.getLeadChatMessages(lead.id).subscribe({
      next: msgs => {
        if (this.lead?.id !== lead.id) return;
        this.chatMessages = msgs;
        this.rebuildTimeline();
        this.cdr.detectChanges();
      },
      error: () => {},
    });
  }

  /** Лента и сообщения по времени; сортировка стабильная — при равном времени событие раньше сообщения. */
  private rebuildTimeline() {
    const time = (iso: string) => new Date(iso).getTime();
    const events = (this.lead?.events || []).map((e: any) => ({ kind: 'event', at: e.at, e }));
    const msgs = this.chatMessages.map((m: any) => ({ kind: 'msg', at: m.sentAt, m }));
    this.timeline = [...events, ...msgs].sort((a, b) => time(a.at) - time(b.at));
  }

  trackTimeline(_: number, t: any) { return t.kind + ':' + (t.kind === 'event' ? t.e.id : t.m.id); }

  startParse(attachment: any) {
    this.parseAttachment = attachment;
    this.panel = 'none';
    this.cdr.detectChanges();
    setTimeout(() => this.importRef?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'center' }));
  }

  onItemsImported(card: any) {
    this.lead = card;
    this.parseAttachment = null;
    this.rebuildTimeline();
    this.notify.success('Позиции обновлены: ' + (card.items?.length || 0) + ' поз.');
    this.changed.emit();
    this.cdr.detectChanges();
  }
```

- [ ] **Step 4: «Обращения» — строка WhatsApp**

`frontend/src/app/pages/leads/leads.component.ts`:

1. Импорты:

```ts
import { WhatsappStatusLineComponent } from '../../shared/whatsapp-status-line.component';
import { WhatsappStatus } from '../../shared/whatsapp-status';
```

2. В `imports` компонента добавить `WhatsappStatusLineComponent`.

3. В шаблоне сразу после строки `<div class="sync-plate" …>{{ syncText() }}</div>`:

```html
    <app-whatsapp-status-line *ngIf="waStatus?.enabled" [status]="waStatus"></app-whatsapp-status-line>
```

4. Поле `waStatus: WhatsappStatus | null = null;` (после `sync: any = null;`) и в конец `loadSync()`:

```ts
    this.api.getWhatsappStatus().subscribe({ next: s => { this.waStatus = s; this.cdr.detectChanges(); }, error: () => {} });
```

- [ ] **Step 5: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: успешно.

- [ ] **Step 6: Живая проверка (Playwright)**

Стаб + бэкенд с WhatsApp + фронт, `/__scenario/basic` на свежем стабе, рынок KZ:

1. `/leads`: под плашкой westmed.kz — строка «WhatsApp +7 700 000 00 01 · подключён…»; обращения из WhatsApp с зелёным чипом «WhatsApp»: «Запрос КП · 2 поз.» («В работе») и «WhatsApp» («Новое»); счётчик меню «Обращения» учитывает новое.
2. Карточка «Айгерим (тест)»: секция «Лента и переписка» — пузыри сообщений вперемешку с событиями («Получено», «Статус: Новое → В работе: ответ клиенту в WhatsApp с телефона»); миниатюра фото грузится; «Открыть весь чат» ведёт на `/chats?chatId=…` с открытым этим чатом.
3. В карточке у Excel «Разобрать в позиции» → панель появляется в «Что просят» и прокручивается в видимую область → «Добавить к позициям» → позиций стало больше на 3, в ленте «Позиции из Excel: 3 поз. (добавлены)».
4. Обращение с сайта westmed.kz (если есть в базе) — заголовок «Лента», ссылки на чат нет, всё как раньше.
5. Бэкенд без `WHATSAPP_ENABLED` (перезапуск) — на `/leads` строки WhatsApp нет, плашка westmed.kz на месте; на `/chats` — «WhatsApp: приём выключен».
6. 390px: карточка на весь экран, пузыри не вылезают за край; тёмная тема; оператор — без «Разобрать в позиции».
7. Консоль без ошибок.

Тестовые данные убрать (SQL Task 9 Step 4).

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/leads/lead-card.component.ts frontend/src/app/pages/leads/leads.component.ts
git commit -m "$(cat <<'EOF'
feat(whatsapp): переписка чата в карточке обращения, Excel → позиции из ленты, строка WhatsApp на «Обращениях»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Документация и финальный гейт

**Files:**
- Modify: `CLAUDE.md`, `DEPLOY.md`, `docs/PROGRESS.md`
- Modify (память агента, вне репо): `~/.claude/projects/-Users-vlad-IdeaProjects-AIS/memory/last-task-pointer.md`

- [ ] **Step 1: CLAUDE.md**

- **§5 «Среда разработки»** — новый подраздел «WhatsApp (Green-API) — локально»: команда стаба (`node scripts/greenapi-stub.mjs`), env бэкенда (`WHATSAPP_ENABLED=true WHATSAPP_API_URL=http://localhost:7707 WHATSAPP_ID_INSTANCE=1101 WHATSAPP_API_TOKEN=dev-token WHATSAPP_INITIAL_DELAY_MS=3000`), `curl -s -X POST localhost:7707/__scenario/basic`, SQL чистки тестовых чатов (Task 9 Step 4); ⚠️ очередь инстанса читает ОДИН потребитель — локальная АИС и прод на разных инстансах; реальный инстанс для разработки — бесплатный Developer (видно только 3 чата).
- **§8** — блок «Чаты WhatsApp через Green-API (2026-09-28, ветка `feature/whatsapp-chats`)» по образцу блока «Обращения»: решения (Green-API, отдельный номер, зеркало без отправки, все чаты номера, опрос очереди вместо вебхука); модель V21 (`chat`/`chat_message`/`chat_attachment`, `lead.chat_id`, сообщение хранится один раз); цикл (поток `whatsapp-chats`, удаление после записи, 3 попытки → `MESSAGE_DROPPED`, паузы 10 мин / 30 с, состояние и настройки инстанса); правила §5 спеки (склейка по номеру через каналы, 30 дней для CONVERTED, авто-«В работу», «не клиент», группы, «Создать обращение» с начала эпизода); файлы (предел, группы без байтов, inline только картинки, nosniff); экран «Чаты» и переписка в карточке; общий грид Excel (`shared/import-grid`) вместо двух копий.
- **§14** — уроки: (1) файлы с `/api` нельзя грузить голым `<img src>`/ссылкой — нет `X-Market` → 404 на KZ (только `HttpClient` blob); (2) токен Green-API в пути URL — URL не печатать нигде; (3) очередь Green-API отдаёт голову, пока не удалят → удалять только после записи, а «ядовитое» — после N попыток, иначе очередь встаёт навсегда; (4) `DataIntegrityViolationException` не глотать целиком как «гонку» (найдено в `WestmedLeadSync` при разборе 2026-09-28); (5) при жалобе «не видно данных» сначала рынок (новый браузер/иконка iPhone = РФ).
- **§15** — `/api/chats` (GET `?filter=ALL|WITH_LEAD|WITHOUT_LEAD|GROUPS&q=`, `/status`, `/{id}`, `/{id}/messages?before=&limit=`, `/{id}/attachments/{attId}`, POST `/{id}/attachments/{attId}/preview` ADMIN, `/{id}/lead` ADMIN, `/{id}/not-client` ADMIN); `/api/leads/{id}/chat-messages`, POST `/api/leads/{id}/items/import` ADMIN.
- **§16** — бэклог (спека §14): ответ из АИС, старая переписка (`GetChatHistory`), несколько номеров (поле `account` готово), Telegram через Green-API (поле `channel` готово), вынос файлов из БД, звонки WhatsApp, связь клика по кнопке сайта с сообщением; хвост `WestmedLeadSync` (глотание `DataIntegrityViolationException`); «следующая свободная миграция — **V22**» (заменить упоминание V21 в §16/§10).

- [ ] **Step 2: DEPLOY.md**

Раздел «WhatsApp (Green-API)»: env (`WHATSAPP_ENABLED`, `WHATSAPP_API_URL`, `WHATSAPP_ID_INSTANCE`, `WHATSAPP_API_TOKEN`, опц. `WHATSAPP_MAX_FILE_MB`, `WHATSAPP_MARKET`); подготовка инстанса (тариф Business; QR через «Связанные устройства»; уведомления о входящих, об отправленных с телефона, о правках и удалениях — включить; адрес вебхука — ПУСТОЙ); перед включением — `df -h` (файлы в БД); проверка после деплоя — строка «подключён» на «Чатах» + тестовое сообщение; откат — `WHATSAPP_ENABLED=false` (таблицы V21 остаются, приём останавливается); номер WhatsApp на сайте — три места (`WhatsAppButton.tsx`, `Footer.tsx`, `contacts/page.tsx`), отдельный деплой сайта.

- [ ] **Step 3: PROGRESS.md и память**

Переписать раздел «▶ Последняя задача» (блок «Чаты WhatsApp через Green-API»: что сделано, где спека/план, что в проде/не в проде, что ждёт оператора — инстанс Business, QR, ключи, номер на сайте) и добавить запись сессии (разбор «заявка не видна» → рынок РФ; решения оператора; гейт с числом тестов). Обновить `last-task-pointer.md` (строка «сейчас: …»).

- [ ] **Step 4: Финальный гейт**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test && cd frontend && npm run build`
Expected: 0 падений, 0 skipped; сборка фронта успешна. Число тестов — в PROGRESS.

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md DEPLOY.md docs/PROGRESS.md
git commit -m "$(cat <<'EOF'
docs(whatsapp): чаты через Green-API — механика, запуск со стабом, раскатка, уроки, API, бэклог

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Проверка на настоящем инстансе Green-API (нужен оператор)

Выполняется, когда оператор завёл инстанс. Не блокирует мерж в `main` (всё выключено по умолчанию), но блокирует включение на проде.

- [ ] **Step 1: Оператор** — бесплатный инстанс Developer для проверки: привязать QR тестовый номер ИЛИ рабочий номер вторым связанным устройством; в настройках — уведомления о входящих, об отправленных с телефона, о правках/удалениях; адрес вебхука пустой. `apiUrl`, `idInstance`, токен — в `~/.config/ais/greenapi-dev.env` (`WHATSAPP_API_URL=…`, `WHATSAPP_ID_INSTANCE=…`, `WHATSAPP_API_TOKEN=…`, права 600). В чат не писать.

- [ ] **Step 2: Запуск** (sandbox off; стаб не нужен): `cd /Users/vlad/IdeaProjects/AIS && set -a && . ~/.config/ais/greenapi-dev.env && set +a && WHATSAPP_ENABLED=true JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew bootRun` — содержимое файла не печатать.

- [ ] **Step 3: Сценарий** (Playwright + телефон оператора): строка «подключён» с верным номером; сообщение с личного телефона → чат и обращение «Новое»; ответ с привязанного телефона → «В работе»; фото → миниатюра; Excel → «Разобрать в позиции»; сообщение по шаблону корзины сайта (скопировать текст кнопки сайта) → «Запрос КП» с позициями и брендом; правка и удаление сообщения на телефоне → пометки; отвязать устройство в WhatsApp → строка «номер не подключён»; на Developer — написать с 4-го номера → предупреждение про лимит тарифа (если Green-API пришлёт `quotaExceeded`/466).

- [ ] **Step 4: Итог** — расхождения формы уведомлений с документацией (если найдутся) закрепить тестом в `GreenApiNotificationParserTest` на реальном JSON (без персональных данных), поправить парсер. Тестовые данные — убрать.

---

## Self-review (сделан при написании плана)

- **Покрытие спеки:** §2 контракты → Task 2–3; §4 модель → Task 1; §5 правила → Task 5 (+ «Создать обращение» Task 7); §6 цикл/идемпотентность/сбои/файлы/типы/имена/корзина → Task 3–6; §7 состояние → Task 6 (бэк) и 11 (строка); §8 REST → Task 7–8; §9 интерфейс → Task 10–12; §10 безопасность → Task 2 (токен), 7 (inline/nosniff/гард рынка); §11 конфиг → Task 6; §12 тесты и мутации → в каждой задаче; §13 раскатка → Task 13 (DEPLOY.md) и 14.
- **Имена сквозь задачи:** `ChatIngestWriter.Outcome`/`write`/`applyDelete`, `ChatLeadRules.findOpenLead`, `WhatsappStatusHolder` + константы, `ChatService.toResponses/messagesForLead/isSafeImage/isExcel`, `LeadService.takeAutomatically/importItems`, `PrivateRequestImportService.learn`, `ApiService.getChatAttachment/importLeadItems`, `buildImportLines` — сверены между определением и использованием.
- **Порядок:** Task 5 использует тестовый `GreenApiJson` из Task 3; Task 6 — `FakeWestmedClient` из существующих тестов; Task 8 расширяет конструкторы `LeadService`/`ChatService`/`LeadController` (Spring собирает сам, ручных `new` в тестах нет); Task 10 до 11 (грид нужен «Разобрать в позиции»); Task 9 до 10–12 (стаб для живых проверок).

