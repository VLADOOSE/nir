# «Наш тендер — с телефона за минуту» — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> В этом проекте — **только executing-plans в основной сессии, без субагентов** (CLAUDE.md §2).

**Goal:** из сообщения бота в Telegram оператор отдаёт пачку потенциальных тендеров своему ИИ, возвращает блок «Для АИС», АИС сама закрепляет наши модели и готовит письма заводам на бланке со строкой о больнице, а оператор просматривает и отправляет каждое одним нажатием; тот же круг — с компьютера в АИС.

**Architecture:** новый сервисный пакет `service.aianswer` — разбор блока «Для АИС» и его применение (вывод ИИ у лота, закрепление нашего товара через `LotModelService`, план писем заводу / доверенному) — общий для бота и для `POST /api/tenders/ai-answer`. Новый пакет `service.tgbot` — бот: `TelegramBotApi` (кнопки, файлы, `getUpdates`) в `integration.telegram`, цикл приёма `TelegramBotPoller`, маршрутизатор нажатий `BotUpdateRouter` (кто нажал — Telegram ID учётки, действие от её имени с пометкой «Telegram» и привязанной сессией JPA, как OSIV), «📋 Для ИИ» файлом, «✍️ Ответ ИИ», письма-черновики `bot_letter` с просмотром фото бланка и отправкой через прежний `PriceRequestSendService`. `ProductWatchNotifier` в режиме кнопок шлёт сообщение-пачку (`ProductDigestComposer` + `ProductDigestPublisher`, таблица `product_digest`). Всё под `TELEGRAM_BOT_ACTIONS_ENABLED` (умолчание false).

**Tech Stack:** Spring Boot 3.5.6 / Java 17, JPA (Hibernate 6), Flyway, PostgreSQL 17, Telegram Bot API (long-poll `getUpdates`, inline-кнопки, HTML, multipart); Angular 21 standalone; JUnit 5, AssertJ, MockMvc, GreenMail, JDK HttpServer (заглушка Bot API).

**Spec:** `docs/superpowers/specs/2026-10-21-tender-telegram-bot-design.md`

## Global Constraints

- Только рынок KZ: разбор ответа ИИ и бот — при `MarketContext.get() == Market.KZ` (иначе 400 «Только для Казахстана»); бот сам ставит KZ в своём потоке.
- Записи — только ADMIN: `@PreAuthorize("hasRole('ADMIN')")` у API; у бота — учётка с ролью `ROLE_ADMIN` и вписанным Telegram ID, иначе «нет доступа».
- Тендер в письме не раскрываем: строка «Конечный заказчик: <больница>. Просим закрепить проект за West-Med.» (`RfqTexts.DEFAULT_CLIENT_LINE`) — только заводу и «Доверенным»; номера и ссылки тендера в письме нет (`KpEmailComposer` не меняется).
- Пределы Telegram: текст сообщения — 4096 знаков после разбора разметки (держим ≤ 3800), подпись к фото / файлу — 1024, `callback_data` — 64 байта, кнопка «копировать» — 256 (не используем); удалить своё сообщение бот может только 48 ч; 20 сообщений в минуту в группу (общий предел с сайтом и medsite).
- Сеть (Telegram, SMTP) — вне транзакций БД; запись — короткими транзакциями отдельных бинов (CLAUDE.md §6).
- В лог — без текстов писем и ответов ИИ, только id и классы ошибок; токен бота — ни в одном тексте (как `TelegramClient`).
- Пустой булев env роняет старт («Invalid boolean value []») — `TELEGRAM_BOT_ACTIONS_ENABLED` в `.env` пустым не оставлять.
- Каждый коммит — строка `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `./gradlew` и psql — с `dangerouslyDisableSandbox: true`; перед прогоном: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`. Команды — из корня: `cd /Users/vlad/IdeaProjects/AIS && …`. Логи прогонов — в `.superpowers/sdd/2026-10-21-tender-telegram-bot/`.
- Фронт: новое окно — ленивый чанк (`@defer`), начальный бандл ≤ 1,5 МБ (сейчас 1,44); результаты промисов — `.then`, не нативный `await`; классы компонентов — не `.empty / .subtitle / .counter / .badge / .form-actions / .field-error` (UI-kit); kit (`.btn btn-primary`, `.btn btn-line`, `.error-banner`) — брать намеренно.
- Ветка — `feature/tg-tender-bot`; мерж в `main` — `--ff-only`; push — оператор (`! git push origin main`).
- Тест — красный до правки по своей причине; после зелёного — мутация (сломать правку → нужный тест краснеет → вернуть копией файла, не `git checkout`, CLAUDE.md §14).

## Review Focus

1. Ответ ИИ вставлен не «чистым» блоком: таблица Markdown, блок кода, коды в скобках, кириллические «Л» / «Р», весь ответ с рассуждением вокруг — строки всё равно находятся, рассуждение не путается со строками (тест — задача 5).
2. Двойное или одновременное «✅ Отправить» (вы и отец) — уходит ровно одно письмо (тесты — задачи 1 и 11).
3. Между просмотром и отправкой модель лота поменяли в АИС или письмо уже отправили из АИС — бот не шлёт ни неверное, ни второе письмо (тесты — задача 11).
4. Telegram отвечает 409 / 401 / 429 во время приёма — цикл не крутится вхолостую и не падает, на 409 останавливается с ERROR; токен не попадает в тексты (тесты — задачи 8 и 10).
5. Нажимает незнакомый Telegram-пользователь, оператор без прав или кнопка из другого чата — ничего не происходит, кроме окна с ID / «нет доступа» (тест — задача 10).

## Решения при планировании (где план точнее спеки)

1. **Список писем после отправки** (спека §7 п. 4): правится только клавиатура — кнопка «✅ N» / «✅ N · АИС» / «✕ N» без действия; текст сообщения-итога не меняется (он нигде не хранится, собрать заново нечем). Об отправке — отдельный ответ «✅ Письмо … отправлено · vlad».
2. **429 у действий бота** (спека §8): один повтор после `retry_after`, если он не длиннее 15 с; дольше — «Не получилось…». Поток бота один: ждать минуту — значит минуту не принимать нажатия. У `getUpdates` — пауза цикла `retry_after`.
3. **Подсказка «блок «Для АИС» не найден»** — только на ответы на сообщение-пачку, файл «Для ИИ» и просьбу «✍️ Ответ ИИ» (спека §8). Ответ с блоком на любое сообщение бота (в том числе на итог) разбирается.
4. **Модель по ответу ИИ** — `LotModelService.applyByAi` с позицией каталога товара (`equipmentForSiteProduct`), а не `pinSiteProduct` (спека §6): та же позиция каталога, плюс пометка «закреплено ИИ» и « — по ответу ИИ» в событии; рынок, кандидатов и «снят с сайта» проверяет `AiAnswerService` до вызова.
5. **Правка текста в панели «КП» при поставщиках с клиентом и без** (спека §10 п. 2): правленый текст уходит только поставщикам того же вида, что образец предпросмотра, остальным — стандартный текст; сервер отвечает 400 на смешанную отправку с правкой — строка о больнице не уйдёт дилеру. Неправленый текст больше не отправляется как правка: каждому поставщику — свой (раньше всем уходило приветствие первого поставщика).
6. **Цикл приёма нажатий** — при `TELEGRAM_BOT_ACTIONS_ENABLED`, настроенном Telegram и включённой «нашей продукции» (`PRODUCT_WATCH_ENABLED`, спека §11).
7. **Порядок в пачке** (спека §4): ★, ✅, ◐, 🏷 — устойчивой сортировкой по значку поверх порядка `ProductWatchNotifier.prepare` (там уже «точнее, затем срок»): ◐ и 🏷 — оба уровня TYPE, их различает только сверка.

---

### Task 1: Данные (V41), сущности и репозитории

**Files:**
- Create: `src/main/resources/db/migration/V41__tender_telegram_bot.sql`
- Create: `src/main/java/com/vladoose/nir/entity/AiVerdict.java`, `BotLetterStatus.java`, `BotLetterItem.java`, `BotLetter.java`, `ProductDigest.java`
- Modify: `src/main/java/com/vladoose/nir/entity/UserAccount.java` (поле после `passwordHash`), `PriceRequest.java` (после `emailOverride`), `TenderLot.java` (после `modelSetAt`)
- Create: `src/main/java/com/vladoose/nir/repository/BotLetterRepository.java`, `ProductDigestRepository.java`
- Modify: `src/main/java/com/vladoose/nir/repository/UserAccountRepository.java`, `PriceRequestItemRepository.java`, `PriceRequestRepository.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java` (новые поля лота не пишутся из запроса)
- Test: `src/test/java/com/vladoose/nir/tgbot/TgBotSchemaTest.java`

**Interfaces:**
- Produces: `enum AiVerdict { GO, NO, UNSURE }`; `enum BotLetterStatus { DRAFT, SENDING, SENT, FAILED, ELSEWHERE, CANCELLED }`; `record BotLetterItem(long lotId, Long equipmentId, Integer qty)`; `BotLetter` (`id, tenderId, distributorId, items, status, priceRequestId, listMessageId, previewMessageId, lineNo, createdAt, createdBy, sentAt, sentBy, error`); `ProductDigest` (`id, createdAt, messageId, tenderIds: List<Long>`); `UserAccount.telegramUserId: Long`; `PriceRequest.createdBy: String`; `TenderLot.aiVerdict: AiVerdict`, `aiProduct: SiteProduct` (LAZY), `aiNote`, `aiBy: String`, `aiAt: OffsetDateTime`, `modelByAi: boolean`; `BotLetterRepository.findByListMessageIdOrderByLineNo(Long): List<BotLetter>`, `transition(Long id, Collection<BotLetterStatus> from, BotLetterStatus to): int`; `ProductDigestRepository.existsByMessageId(Long): boolean`; `UserAccountRepository.findByTelegramUserId(Long): Optional<UserAccount>`; `PriceRequestItemRepository.lastRequestedAt(Long lotId, Long distId): OffsetDateTime`; `PriceRequestRepository.lastCreatedAt(Long tenderId, Long distId, Collection<Long> lotIds): OffsetDateTime`.

- [ ] **Step 1: Тест схемы, JSONB и перевода статуса**

```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V41 (спека 2026-10-21-tender-telegram-bot §9): колонки, JSONB пачки и черновика, перевод статуса — один раз. */
@SpringBootTest
@Transactional
class TgBotSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductDigestRepository digests;
    @Autowired BotLetterRepository letters;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired EntityManager em;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void v41Columns() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'tender_lot' "
                + "and (column_name like 'ai\\_%' or column_name = 'model_by_ai')", String.class))
                .containsExactlyInAnyOrder("ai_verdict", "ai_product_id", "ai_note", "ai_by", "ai_at", "model_by_ai");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'user_account' "
                + "and column_name = 'telegram_user_id'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'price_request' "
                + "and column_name = 'created_by'", Integer.class)).isEqualTo(1);
    }

    @Test
    void digestKeepsTenderIdsAsLongs() {
        ProductDigest d = digests.saveAndFlush(ProductDigest.builder().tenderIds(new ArrayList<>(List.of(5L, 7L))).build());
        em.clear();
        assertThat(digests.findById(d.getId()).orElseThrow().getTenderIds()).containsExactly(5L, 7L);
    }

    @Test
    void letterItemsRoundTrip_andTransitionOnlyOnce() {
        MarketContext.set(Market.KZ);
        Tender t = tenders.saveAndFlush(Tender.builder().tenderNumber("ZZ-БОТ-" + System.nanoTime()).status("ACTIVE")
                .source(Source.PUBLIC_TENDER).market(Market.KZ).currency("KZT").build());
        Distributor d = distributors.saveAndFlush(Distributor.builder().name("ZZ Завод " + System.nanoTime()).market(Market.KZ).build());
        BotLetter b = letters.saveAndFlush(BotLetter.builder().tenderId(t.getId()).distributorId(d.getId()).lineNo(1)
                .items(new ArrayList<>(List.of(new BotLetterItem(11L, 22L, 3)))).build());
        em.clear();
        assertThat(letters.findById(b.getId()).orElseThrow().getItems()).containsExactly(new BotLetterItem(11L, 22L, 3));
        List<BotLetterStatus> from = List.of(BotLetterStatus.DRAFT, BotLetterStatus.FAILED);
        assertThat(letters.transition(b.getId(), from, BotLetterStatus.SENDING)).isEqualTo(1);
        assertThat(letters.transition(b.getId(), from, BotLetterStatus.SENDING)).isZero();   // второе нажатие — мимо
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && mkdir -p .superpowers/sdd/2026-10-21-tender-telegram-bot && ./gradlew test --tests 'com.vladoose.nir.tgbot.TgBotSchemaTest' > .superpowers/sdd/2026-10-21-tender-telegram-bot/t1.log 2>&1; tail -30 .superpowers/sdd/2026-10-21-tender-telegram-bot/t1.log`
Expected: FAIL — компиляция: нет `ProductDigest`, `BotLetter`, `BotLetterItem`, `BotLetterRepository`.

- [ ] **Step 3: Миграция, сущности, репозитории, маппер**

`V41__tender_telegram_bot.sql`:
```sql
-- «Наш тендер — с телефона за минуту» (спека 2026-10-21-tender-telegram-bot §9): Telegram ID учётки, автор запроса цен,
-- вывод ИИ у лота, сообщения-пачки и черновики писем из бота.
ALTER TABLE user_account ADD COLUMN telegram_user_id BIGINT UNIQUE;
ALTER TABLE price_request ADD COLUMN created_by VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN ai_verdict VARCHAR(10);
ALTER TABLE tender_lot ADD COLUMN ai_product_id BIGINT REFERENCES site_product (id) ON DELETE SET NULL;
ALTER TABLE tender_lot ADD COLUMN ai_note VARCHAR(500);
ALTER TABLE tender_lot ADD COLUMN ai_by VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN ai_at TIMESTAMPTZ;
ALTER TABLE tender_lot ADD COLUMN model_by_ai BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE product_digest (
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    message_id BIGINT,
    tender_ids JSONB       NOT NULL
);
CREATE INDEX idx_product_digest_message ON product_digest (message_id);

CREATE TABLE bot_letter (
    id                 BIGSERIAL PRIMARY KEY,
    tender_id          BIGINT      NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    distributor_id     BIGINT      NOT NULL REFERENCES distributor (id) ON DELETE CASCADE,
    items              JSONB       NOT NULL,
    status             VARCHAR(12) NOT NULL,
    price_request_id   BIGINT REFERENCES price_request (id) ON DELETE SET NULL,
    list_message_id    BIGINT,
    preview_message_id BIGINT,
    line_no            INT         NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(100),
    sent_at            TIMESTAMPTZ,
    sent_by            VARCHAR(100),
    error              VARCHAR(300)
);
CREATE INDEX idx_bot_letter_list ON bot_letter (list_message_id);
```
(`line_no`, а не `position`: ключевые слова HQL ломают `@OrderBy` и производные запросы, CLAUDE.md §7.)

`AiVerdict.java`:
```java
package com.vladoose.nir.entity;

/** Вывод ИИ по лоту (спека 2026-10-21-tender-telegram-bot §6): идти, не наше, не ясно. */
public enum AiVerdict { GO, NO, UNSURE }
```

`BotLetterStatus.java`:
```java
package com.vladoose.nir.entity;

/**
 * Черновик письма из бота (спека 2026-10-21-tender-telegram-bot §7): DRAFT → SENDING → SENT; FAILED — почта не приняла
 * (повтор — «↻ Переслать» того же запроса); ELSEWHERE — тем временем отправлено из АИС; CANCELLED — приём закончился.
 */
public enum BotLetterStatus { DRAFT, SENDING, SENT, FAILED, ELSEWHERE, CANCELLED }
```

`BotLetterItem.java`:
```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Позиция письма из бота: лот, позиция каталога модели в работе на момент черновика, количество (пусто — уточняется). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotLetterItem(long lotId, Long equipmentId, Integer qty) {}
```

`BotLetter.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Черновик письма заводу из бота (спека 2026-10-21-tender-telegram-bot §7, V41): кому, какие лоты, статус, сообщения бота. */
@Entity
@Table(name = "bot_letter")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "distributor_id", nullable = false)
    private Long distributorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<BotLetterItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private BotLetterStatus status = BotLetterStatus.DRAFT;

    @Column(name = "price_request_id")
    private Long priceRequestId;

    /** Сообщение бота со списком писем (кнопки «👁 N»). */
    @Column(name = "list_message_id")
    private Long listMessageId;

    /** Открытое фото / PDF бланка — удаляется после отправки или «⬅ Вернуться». */
    @Column(name = "preview_message_id")
    private Long previewMessageId;

    /** № письма в списке: «Письмо 2 из 3», кнопка «👁 2». */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "sent_by", length = 100)
    private String sentBy;

    @Column(length = 300)
    private String error;
}
```

`ProductDigest.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Сообщение-пачка «Потенциально наших тендеров» (спека 2026-10-21-tender-telegram-bot §4, V41): какие тендеры в нём. */
@Entity
@Table(name = "product_digest")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /** message_id в Telegram; пусто — ещё не ушло. */
    @Column(name = "message_id")
    private Long messageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tender_ids", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Long> tenderIds = new ArrayList<>();
}
```

`UserAccount.java` — после `passwordHash`:
```java
    /** Telegram ID — для кнопок бота (спека 2026-10-21-tender-telegram-bot §8, V41); пусто — бот учётку не знает. */
    @Column(name = "telegram_user_id", unique = true)
    private Long telegramUserId;
```

`PriceRequest.java` — после `emailOverride`:
```java
    /** Кто отправил: «vlad», из бота — «vlad, Telegram» (V41); у запросов до V41 — пусто. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
```

`TenderLot.java` — после `modelSetAt`:
```java
    /** Последний вывод ИИ по лоту (спека 2026-10-21-tender-telegram-bot §6, V41). */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_verdict", length = 10)
    private AiVerdict aiVerdict;

    /** Наш товар из совета ИИ — только из кандидатов этого лота. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_product_id")
    private SiteProduct aiProduct;

    @Column(name = "ai_note", length = 500)
    private String aiNote;

    @Column(name = "ai_by", length = 100)
    private String aiBy;

    @Column(name = "ai_at")
    private OffsetDateTime aiAt;

    /** Модель в работе закрепил ИИ по ответу: следующий ответ ИИ её заменит, ручную — нет. */
    @Column(name = "model_by_ai", nullable = false)
    private boolean modelByAi;
```

`BotLetterRepository.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.BotLetterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BotLetterRepository extends JpaRepository<BotLetter, Long> {

    List<BotLetter> findByListMessageIdOrderByLineNo(Long listMessageId);

    /** Перевод статуса «если сейчас один из from»: из одновременных «Отправить» 1 получит ровно одно (спека §7). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BotLetter b SET b.status = :to WHERE b.id = :id AND b.status IN :from")
    int transition(@Param("id") Long id, @Param("from") Collection<BotLetterStatus> from, @Param("to") BotLetterStatus to);
}
```

`ProductDigestRepository.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProductDigest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductDigestRepository extends JpaRepository<ProductDigest, Long> {
    boolean existsByMessageId(Long messageId);
}
```

`UserAccountRepository.java` — добавить:
```java
    Optional<UserAccount> findByTelegramUserId(Long telegramUserId);
```

`PriceRequestItemRepository.java` — добавить (импорт `java.time.OffsetDateTime`):
```java
    /** Когда этот поставщик последний раз получал запрос по лоту — письма из ответа ИИ (спека 2026-10-21 §6). */
    @Query("SELECT max(i.priceRequest.sentAt) FROM PriceRequestItem i "
            + "WHERE i.tenderLot.id = :lotId AND i.priceRequest.distributor.id = :distId")
    OffsetDateTime lastRequestedAt(@Param("lotId") Long lotId, @Param("distId") Long distId);
```

`PriceRequestRepository.java` — добавить (импорты `java.time.OffsetDateTime`, `java.util.Collection`, `Query`, `Param`):
```java
    /** Последний запрос цен поставщику по лотам тендера — «уже отправлено из АИС» у письма из бота (спека §7). */
    @Query("SELECT max(p.createdAt) FROM PriceRequest p JOIN p.items i WHERE p.tender.id = :tenderId "
            + "AND p.distributor.id = :distId AND i.tenderLot.id IN :lotIds")
    OffsetDateTime lastCreatedAt(@Param("tenderId") Long tenderId, @Param("distId") Long distId,
                                 @Param("lotIds") Collection<Long> lotIds);
```

`TenderLotMapper.java` — в оба метода записи (`toEntity` и `updateEntity`, где уже есть `@Mapping(target = "modelSetAt", ignore = true)`) добавить:
```java
    @Mapping(target = "aiVerdict", ignore = true)
    @Mapping(target = "aiProduct", ignore = true)
    @Mapping(target = "aiNote", ignore = true)
    @Mapping(target = "aiBy", ignore = true)
    @Mapping(target = "aiAt", ignore = true)
    @Mapping(target = "modelByAi", ignore = true)
```

- [ ] **Step 4: Запустить — зелёный**

Run: та же команда, `> t1.log`.
Expected: PASS (3 теста). Если `tenderIds` пришли `Integer` — у поля нет сведений о типе элемента; тогда `@JdbcTypeCode(SqlTypes.JSON)` заменить на `@Convert` с `AttributeConverter<List<Long>, String>` (Jackson `TypeReference<List<Long>>`) и повторить.

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V41__tender_telegram_bot.sql \
  src/main/java/com/vladoose/nir/entity/{AiVerdict,BotLetterStatus,BotLetterItem,BotLetter,ProductDigest,UserAccount,PriceRequest,TenderLot}.java \
  src/main/java/com/vladoose/nir/repository/{BotLetterRepository,ProductDigestRepository,UserAccountRepository,PriceRequestItemRepository,PriceRequestRepository}.java \
  src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java src/test/java/com/vladoose/nir/tgbot/TgBotSchemaTest.java
git commit -m "$(printf 'feat(tg-bot): V41 — Telegram ID учётки, автор запроса цен, вывод ИИ у лота, пачки и черновики писем\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 2: «Кто и откуда» — пометка «Telegram», модель от ИИ, автор запроса цен в «Истории»

**Files:**
- Modify: `src/main/java/com/vladoose/nir/util/CurrentUser.java`
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealEventService.java` (`CurrentUser.name()` → `CurrentUser.label()`, 2 места)
- Modify: `src/main/java/com/vladoose/nir/service/lotmodel/LotModelService.java` (`stamp`, `apply`, `clear`)
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestWriter.java` (`persist`)
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java` (`addSupply`)
- Create (test): `src/test/java/com/vladoose/nir/tgbot/TgFixtures.java`
- Test: `src/test/java/com/vladoose/nir/tgbot/ActionAuthorTest.java`

**Interfaces:**
- Consumes: поля задачи 1.
- Produces: `CurrentUser.setVia(String via)` (null — снять), `CurrentUser.label(): String` («vlad» / «vlad, Telegram» / null); `LotModelService.applyByAi(TenderLot, MedEquipment, String note): TenderLot`; `TgFixtures.lot(TenderRepository, String lotName, String customer): TenderLot` (KZ, ACTIVE, приём через 10 дней), `distributor(DistributorRepository, String name, String email, boolean maker, boolean trusted, String... brands)`, `product(SiteProductRepository, String name, String brand)`, `group(ProductGroupRepository)`, `match(TenderLotProductMatchRepository, TenderLot, long groupId, ProductMatchLevel, Long productId, List<Long> candidateIds)`, `user(UserAccountRepository, String username, String role, Long telegramId)`.

- [ ] **Step 1: Общие тестовые данные блока**

`TgFixtures.java`:
```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Тестовые данные кнопок бота: тендер KZ с лотом, поставщик, наш товар, группа, отметка «наш товар», учётка. */
public final class TgFixtures {
    private TgFixtures() {}

    public static TenderLot lot(TenderRepository tenders, String lotName, String customer) {
        LocalDate day = LocalDate.now(Market.KZ.zone()).plusDays(10);
        Tender t = Tender.builder().tenderNumber("ZZ-ТГ-" + System.nanoTime()).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT").platform(TenderPlatform.GOSZAKUP).description("Закуп медицинских изделий")
                .customerName(customer).deadline(day).deadlineAt(day.atTime(10, 0)).build();
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setLotNumber(1);
        l.setEquipName(lotName);
        l.setQuantity(1);
        t.getLots().add(l);
        tenders.saveAndFlush(t);
        return t.getLots().get(0);
    }

    public static Distributor distributor(DistributorRepository repo, String name, String email, boolean maker, boolean trusted,
                                          String... brands) {
        return repo.saveAndFlush(Distributor.builder().name(name + " " + System.nanoTime()).market(Market.KZ).email(email)
                .manufacturer(maker).trusted(trusted).brands(new ArrayList<>(List.of(brands))).build());
    }

    public static SiteProduct product(SiteProductRepository repo, String name, String brand) {
        return repo.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand(brand)
                .slug("zz-" + System.nanoTime()).syncedAt(OffsetDateTime.now()).build());
    }

    public static ProductGroup group(ProductGroupRepository repo) {
        return repo.saveAndFlush(ProductGroup.builder().market(Market.KZ).name("ZZ группа " + System.nanoTime())
                .searchWords(new ArrayList<>(List.of("дефибриллятор"))).position(990).build());
    }

    public static TenderLotProductMatch match(TenderLotProductMatchRepository repo, TenderLot lot, long groupId,
                                              ProductMatchLevel level, Long productId, List<Long> candidateIds) {
        List<FitCandidate> cands = candidateIds.stream().map(id -> new FitCandidate(id, "ZZ кандидат", "zz", FitVerdict.PARTIAL,
                1, 0, 2, 1, null, null, "ACTIVE", null, List.of())).toList();
        return repo.saveAndFlush(TenderLotProductMatch.builder().tenderId(lot.getTender().getId()).lotId(lot.getId())
                .groupId(groupId).word("дефибриллятор").level(level).productId(productId).fit(FitVerdict.PARTIAL)
                .fitCandidates(new ArrayList<>(cands)).source(ProductMatchSource.SEARCH).build());
    }

    public static UserAccount user(UserAccountRepository repo, String username, String role, Long telegramId) {
        return repo.saveAndFlush(UserAccount.builder().username(username + System.nanoTime()).role(role).passwordHash("x")
                .telegramUserId(telegramId).build());
    }
}
```

- [ ] **Step 2: Тест авторов**

`ActionAuthorTest.java`:
```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestWriter;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.util.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Кто и откуда (спека 2026-10-21-tender-telegram-bot §8): «vlad, Telegram» в модели, событии и запросе цен. */
@SpringBootTest
@Transactional
class ActionAuthorTest {

    @Autowired TenderRepository tenders;
    @Autowired MedEquipmentRepository equipment;
    @Autowired DistributorRepository distributors;
    @Autowired LotModelService models;
    @Autowired PriceRequestWriter writer;
    @Autowired DealTimelineService timeline;
    @Autowired JdbcTemplate jdbc;
    TenderLot lot;
    MedEquipment eq;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        eq = equipment.saveAndFlush(MedEquipment.builder().name("ZZ ДКИ-Н-12").manufact("ZZАксион").market(Market.KZ).build());
    }

    @AfterEach
    void clear() { CurrentUser.setVia(null); MarketContext.clear(); }

    @Test
    void labelAddsVia() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("vlad", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            assertThat(CurrentUser.label()).isEqualTo("vlad");
            CurrentUser.setVia("Telegram");
            assertThat(CurrentUser.label()).isEqualTo("vlad, Telegram");
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(CurrentUser.label()).isNull();   // система — без автора
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void aiPinMarksLotAndEvent_manualConfirmClearsMark() {
        CurrentUser.setVia("Telegram");
        models.applyByAi(lot, eq, "ИИ: подходит");
        CurrentUser.setVia(null);
        assertThat(lot.isModelByAi()).isTrue();
        assertThat(lot.getModelSetBy()).isEqualTo("vlad, Telegram");
        Map<String, Object> ev = jdbc.queryForMap("select text, author from deal_event where tender_id = ? order by id desc limit 1",
                lot.getTender().getId());
        assertThat((String) ev.get("text")).endsWith("— по ответу ИИ");
        assertThat(ev.get("author")).isEqualTo("vlad, Telegram");

        models.apply(lot, eq, null);   // человек подтвердил ту же модель — она теперь ручная
        assertThat(lot.isModelByAi()).isFalse();
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void priceRequestRemembersAuthor_andHistoryShowsIt() {
        Distributor d = TgFixtures.distributor(distributors, "ZZ Завод", "zz@example.kz", true, false, "ZZАксион");
        PriceRequest pr = writer.persist(lot.getTender(), d, List.of(new PriceRequestWriter.Line(lot, eq, 1)));
        assertThat(pr.getCreatedBy()).isEqualTo("vlad");
        List<DealTimelineItem> items = timeline.forTender(lot.getTender().getId(), null, 50).items();
        assertThat(items).filteredOn(i -> "RFQ_SENT".equals(i.kind())).singleElement()
                .satisfies(i -> assertThat(i.author()).isEqualTo("vlad"));
    }
}
```
(`DealTimelineResponse(List<DealTimelineItem> items, boolean hasMore)` и `DealTimelineItem(at, dateOnly, kind, text, author, …)` — записи.)

- [ ] **Step 3: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tgbot.ActionAuthorTest' > .superpowers/sdd/2026-10-21-tender-telegram-bot/t2.log 2>&1; tail -30 .superpowers/sdd/2026-10-21-tender-telegram-bot/t2.log`
Expected: FAIL — компиляция: нет `CurrentUser.setVia`, `label`, `LotModelService.applyByAi`.

- [ ] **Step 4: Правка**

`CurrentUser.java` — добавить в класс:
```java
    private static final ThreadLocal<String> VIA = new ThreadLocal<>();

    /** Откуда действие — «Telegram» у кнопок бота (спека 2026-10-21-tender-telegram-bot §8); null — снять. */
    public static void setVia(String via) {
        if (via == null) VIA.remove(); else VIA.set(via);
    }

    /** Автор для журналов: «vlad», из бота — «vlad, Telegram»; null — система (фоновый поток, аноним). */
    public static String label() {
        String n = name();
        if (n == null) return null;
        String v = VIA.get();
        return v == null ? n : n + ", " + v;
    }
```

`DealEventService.java` — оба `.author(CurrentUser.name())` → `.author(CurrentUser.label())`.

`LotModelService.java`:
```java
    /** Поставить модель: та же модель — только заметка (если прислана); новая модель без заметки заметку очищает. */
    @Transactional
    public TenderLot apply(TenderLot lot, MedEquipment eq, String note) {
        return apply(lot, eq, note, false);
    }

    /** По ответу ИИ (спека 2026-10-21-tender-telegram-bot §6): пометка «закреплено ИИ», событие «… — по ответу ИИ». */
    @Transactional
    public TenderLot applyByAi(TenderLot lot, MedEquipment eq, String note) {
        return apply(lot, eq, note, true);
    }

    private TenderLot apply(TenderLot lot, MedEquipment eq, String note, boolean byAi) {
        String n = note(note);
        boolean same = lot.getProposedEquipment() != null && Objects.equals(lot.getProposedEquipment().getId(), eq.getId());
        if (same) {
            boolean confirmedByHuman = !byAi && lot.isModelByAi();   // человек подтвердил модель ИИ — теперь она ручная
            if (n == null && !confirmedByHuman) return lot;
            if (n != null) lot.setModelNote(n);
            if (confirmedByHuman) lot.setModelByAi(false);
            return lots.save(lot);
        }
        lot.setProposedEquipment(eq);
        lot.setModelNote(n);
        lot.setModelByAi(byAi);
        stamp(lot);
        TenderLot saved = lots.save(lot);
        events.stage(lot.getTender().getId(), DealEventService.MODEL,
                "Лот «" + lotName(lot) + "»: модель в работе — " + label(eq) + (byAi ? " — по ответу ИИ" : ""), lot.getId());
        return saved;
    }
```
(прежний публичный `apply` заменяется этими тремя методами); в `clear` после `lot.setModelNote(null);` — `lot.setModelByAi(false);`; в `stamp` — `lot.setModelSetBy(CurrentUser.label());`.

`PriceRequestWriter.persist(...)` — в билдер после `.letterStatus(...)`:
```java
                .createdBy(CurrentUser.label())
```
(импорт `com.vladoose.nir.util.CurrentUser`).

`DealTimelineService.addSupply` — строку `all.add(item(group.get(0).getSentAt(), "RFQ_SENT", text, null, …))` заменить:
```java
            // кто отправил (V41): одно окно запроса цен — один автор; у запросов до V41 — пусто
            String author = group.stream().map(PriceRequest::getCreatedBy).filter(Objects::nonNull).findFirst().orElse(null);
            all.add(item(group.get(0).getSentAt(), "RFQ_SENT", text, author, one ? "PRICE_REQUEST" : null,
                    one ? group.get(0).getId() : null));
```

- [ ] **Step 5: Запустить — зелёный; соседние тесты модели и ленты**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tgbot.*' --tests 'com.vladoose.nir.lotmodel.*' --tests 'com.vladoose.nir.deal.*' > .superpowers/sdd/2026-10-21-tender-telegram-bot/t2.log 2>&1; tail -30 .superpowers/sdd/2026-10-21-tender-telegram-bot/t2.log`
Expected: PASS.

- [ ] **Step 6: Мутации**

(а) в `label()` вернуть `n` без `via` → краснеют `labelAddsVia`, `aiPinMarksLotAndEvent…`; (б) убрать `lot.setModelByAi(false)` из ветки `same` → краснеет `aiPinMarksLotAndEvent…`; (в) в `addSupply` вернуть `null` вместо `author` → краснеет `priceRequestRemembersAuthor…`. Каждую — копией файла, после серии — `./gradlew compileJava`.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/util/CurrentUser.java \
  src/main/java/com/vladoose/nir/service/deal/{DealEventService,DealTimelineService}.java \
  src/main/java/com/vladoose/nir/service/lotmodel/LotModelService.java src/main/java/com/vladoose/nir/service/PriceRequestWriter.java \
  src/test/java/com/vladoose/nir/tgbot/{TgFixtures,ActionAuthorTest}.java
git commit -m "$(printf 'feat(tg-bot): автор с пометкой «Telegram», модель по ответу ИИ, автор запроса цен в «Истории»\n\nCo-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>')"
```

---

### Task 3: Telegram ID в «Пользователях»

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/UserRequest.java`, `dto/response/UserResponse.java`, `mapper/UserMapper.java`, `service/UserService.java`, `controller/UserController.java`
- Modify: `frontend/src/app/pages/users/users.component.ts`
- Test: `src/test/java/com/vladoose/nir/tgbot/UserTelegramIdApiTest.java`

**Interfaces:**
- Produces: `UserRequest.telegramUserId: String` (не прислан — не менять; пусто — снять; цифры — поставить); `UserResponse.telegramUserId: Long`; `UserService.findByTelegramUserId(Long): Optional<UserAccount>`.

- [ ] **Step 1: Тест API**

```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Telegram ID учётки (спека 2026-10-21-tender-telegram-bot §8, §10 п. 4): цифры, уникально, пусто — снять. */
@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class UserTelegramIdApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired UserAccountRepository users;
    MockMvc mvc;
    UserAccount a, b;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        a = TgFixtures.user(users, "zza", "ROLE_ADMIN", null);
        b = TgFixtures.user(users, "zzb", "ROLE_USER", null);
    }

    private String body(UserAccount u, String telegram) {
        return "{\"username\":\"" + u.getUsername() + "\",\"role\":\"" + u.getRole() + "\""
                + (telegram == null ? "" : ",\"telegramUserId\":\"" + telegram + "\"") + "}";
    }

    @Test
    void setUnique_rejectGarbageAndDuplicate_clearWithEmpty_keepWhenAbsent() throws Exception {
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, " 123456789 ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.telegramUserId").value(123456789L));
        mvc.perform(put("/api/users/" + b.getId()).contentType(MediaType.APPLICATION_JSON).content(body(b, "123456789")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("уже у пользователя")));
        mvc.perform(put("/api/users/" + b.getId()).contentType(MediaType.APPLICATION_JSON).content(body(b, "12ab")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("только цифры")));
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.telegramUserId").value(123456789L));   // поля нет — не трогаем
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, "")))
                .andExpect(status().isOk());
        assertThat(users.findById(a.getId()).orElseThrow().getTelegramUserId()).isNull();
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tgbot.UserTelegramIdApiTest' > .superpowers/sdd/2026-10-21-tender-telegram-bot/t3.log 2>&1; tail -30 .superpowers/sdd/2026-10-21-tender-telegram-bot/t3.log`
Expected: FAIL — `$.telegramUserId` нет в ответе (поле не принимается).

- [ ] **Step 3: Правка**

`UserRequest.java`:
```java
    /**
     * Telegram ID для кнопок бота (спека 2026-10-21-tender-telegram-bot §8): не прислан — не меняется, пусто — снять,
     * иначе только цифры. Строкой: пустое поле формы — это «снять», а не ошибка разбора числа.
     */
    private String telegramUserId;
```
`UserResponse.java`: `private Long telegramUserId;`
`UserMapper.java` — в `toEntity` и `updateEntity`: `@Mapping(target = "telegramUserId", ignore = true)`.
`UserService.java`:
```java
    public Optional<UserAccount> findByTelegramUserId(Long telegramUserId) {
        return repository.findByTelegramUserId(telegramUserId);
    }
```
`UserController.java` — в `create` перед `service.save(entity)` и в `update` перед `service.save(existing)`: `applyTelegram(request, entity)` / `applyTelegram(request, existing)`; метод:
```java
    /** Telegram ID из формы: не прислан — не трогаем, пусто — снять, иначе цифры и не у другого пользователя. */
    private void applyTelegram(UserRequest request, UserAccount entity) {
        String raw = request.getTelegramUserId();
        if (raw == null) return;
        String s = raw.strip();
        if (s.isEmpty()) {
            entity.setTelegramUserId(null);
            return;
        }
        if (!s.matches("\\d{1,15}")) throw new BadRequestException("Telegram ID — только цифры (бот пишет его по первому нажатию)");
        long id = Long.parseLong(s);
        service.findByTelegramUserId(id).filter(u -> !u.getId().equals(entity.getId())).ifPresent(u -> {
            throw new BadRequestException("Этот Telegram ID уже у пользователя «" + u.getUsername() + "»");
        });
        entity.setTelegramUserId(id);
    }
```

`users.component.ts`:
- в `form`: `telegramUserId: new FormControl('')`;
- в форме после «Роль»:
```html
      <label>Telegram ID — для кнопок бота
        <input formControlName="telegramUserId" inputmode="numeric" placeholder="бот подскажет его по первому нажатию" [class.input-error]="validationErrors.telegramUserId" />
        <span class="field-error" *ngIf="validationErrors.telegramUserId">{{ validationErrors.telegramUserId }}</span>
      </label>
```
- `onAdd()`: `this.form.reset({ role: 'ROLE_USER', password: '', telegramUserId: '' })`;
- `onEdit(u)`: `this.form.patchValue({ ...u, password: '', telegramUserId: u.telegramUserId ?? '' })`;
- `onSave()`: `const body = { ...this.form.value, telegramUserId: String(this.form.value.telegramUserId ?? '').trim() };`;
- в ячейке ФИО: `{{ u.fullName }}<span class="tg" *ngIf="u.telegramUserId"> · TG {{ u.telegramUserId }}</span>` и стиль `.tg { color: var(--text-muted); font-size: 12px; }`.

- [ ] **Step 4: Запустить — зелёный**, затем `cd frontend && npm run build` (только сборка).

- [ ] **Step 5: Мутация** — убрать `.filter(u -> !u.getId().equals(entity.getId()))` → краснеет (своя учётка не сохраняется с тем же ID: второй PUT `a` с «123456789» упал бы) — добавить в тест шаг «повторить тот же ID той же учётке → 200», если мутация выжила.

- [ ] **Step 6: Коммит** — `feat(tg-bot): Telegram ID в «Пользователях»`.

---

### Task 4: Текст для ИИ — коды лотов и товаров, блок «Для АИС»

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductAiBrief.java` (`PROMPT`, `lot`, `product`)
- Test: `src/test/java/com/vladoose/nir/tgbot/AiBriefCodesTest.java`

**Interfaces:**
- Produces: в тексте «Для ИИ» — `--- Лот № <№> [L<lotId>]: <название>` и `* [P<productId>] <товар>`; в конце запроса — просьба о блоке «Для АИС» (формат строки — `L<код> | идти / не наше / не ясно | P<код> или — | почему`).

- [ ] **Step 1: Тест**

```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProductAiBrief;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Коды для блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §5): [L…] у лота, [P…] у нашего кандидата. */
@SpringBootTest
@Transactional
class AiBriefCodesTest {

    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired ProductGroupRepository groups;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductAiBrief brief;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void lotAndCandidateCodes_andBlockRequest() {
        MarketContext.set(Market.KZ);
        TenderLot lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        SiteProduct dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZАксион");
        TgFixtures.match(matches, lot, TgFixtures.group(groups).getId(), ProductMatchLevel.TYPE, null, List.of(dki.getId()));

        String text = brief.forTenders(List.of(lot.getTender().getId())).text();

        assertThat(text).contains("[L" + lot.getId() + "]: ZZ Дефибриллятор");
        assertThat(text).contains("* [P" + dki.getId() + "] ZZ Дефибриллятор ДКИ-Н-12");
        assertThat(text).contains("Для АИС").contains("| идти / не наше / не ясно |");
    }
}
```

- [ ] **Step 2: Запустить — красный** (`--tests 'com.vladoose.nir.tgbot.AiBriefCodesTest'`, лог `t4.log`). Expected: FAIL — нет «[L».

- [ ] **Step 3: Правка `ProductAiBrief`**

В конец текстового блока `PROMPT` (после строки формата ответа) — абзац:
```
            В самом конце ответа — блок «Для АИС»: отдельным блоком кода, по строке на каждый лот из текста:
            L<код лота> | идти / не наше / не ясно | P<код нашего товара> или — | коротко почему
            Код лота — в квадратных скобках у заголовка лота [L…], код товара — у наших товаров-кандидатов этого лота [P…].
            «идти» с кодом товара — только если этот наш товар подходит по ТЗ; если подходит не наш товар — «идти» без кода
            (—), а модель и № РУ — в «почему».
```
В `lot(...)`: `b.append("--- Лот № ").append(one(num)).append(" [L").append(l.getId()).append("]: ").append(one(l.getEquipName()));`
В `product(...)`: `b.append("* [P").append(p.getId()).append("] ").append(one(p.getName()));`

- [ ] **Step 4: Зелёный** + соседние тесты «Для ИИ»: `--tests 'com.vladoose.nir.productwatch.*AiBrief*'` (если старые тесты сверяли строку лота дословно — поправить ожидание на формат с кодом).

- [ ] **Step 5: Коммит** — `feat(tg-bot): коды лотов и товаров в «Для ИИ» и просьба о блоке «Для АИС»`.

---

### Task 5: Разбор блока «Для АИС» — `AiAnswerParser`

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/aianswer/AiAnswerParser.java`
- Test: `src/test/java/com/vladoose/nir/tgbot/AiAnswerParserTest.java`

**Interfaces:**
- Produces: `AiAnswerParser.parse(String text): Parsed`; `record Parsed(List<Line> lines, List<String> unparsed)`; `record Line(long lotId, AiVerdict verdict, Long productId, String reason)`; `REASON_MAX = 500`.

- [ ] **Step 1: Тест (чистая функция)**

```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.service.aianswer.AiAnswerParser;
import com.vladoose.nir.service.aianswer.AiAnswerParser.Line;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Разбор блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §6) — любые формы, в которых ИИ его отдаёт. */
class AiAnswerParserTest {

    @Test
    void plainLines() {
        var p = AiAnswerParser.parse("L5826 | идти | P123 | ТЗ совпадает\nL5830 | не наше | — | нужен передвижной");
        assertThat(p.lines()).containsExactly(new Line(5826, AiVerdict.GO, 123L, "ТЗ совпадает"),
                new Line(5830, AiVerdict.NO, null, "нужен передвижной"));
        assertThat(p.unparsed()).isEmpty();
    }

    @Test
    void codeFence_markdownTable_brackets_listMarkers_cyrillicLetters() {
        String text = """
                Разбор: лот 1 подходит, лот 2 — нет.
                ```
                [L1] | Идти | [P7] | совпало 14 из 14
                ```
                | Лот | Вердикт | Товар | Почему |
                |---|---|---|---|
                | L2 | не ясно | — | нет данных о мощности |
                - Л3 | ИДТИ | Р9 | кириллица
                2. L4 | нет | - | не наш вид
                """;
        var p = AiAnswerParser.parse(text);
        assertThat(p.lines()).extracting(Line::lotId, Line::verdict, Line::productId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, AiVerdict.GO, 7L),
                org.assertj.core.groups.Tuple.tuple(2L, AiVerdict.UNSURE, null),
                org.assertj.core.groups.Tuple.tuple(3L, AiVerdict.GO, 9L),
                org.assertj.core.groups.Tuple.tuple(4L, AiVerdict.NO, null));
        assertThat(p.unparsed()).isEmpty();   // заголовок и разделитель таблицы, рассуждение — не строки блока
    }

    @Test
    void productTextNotCode_goesToReason_duplicateLotTakesLast() {
        var p = AiAnswerParser.parse("L5 | идти | Nihon Kohden TEC-5600 | РУ РК-МТ-5№016668\nL6 | да | P1 | x\nL6 | не наше | — | передумал");
        assertThat(p.lines()).containsExactly(new Line(5, AiVerdict.GO, null, "Nihon Kohden TEC-5600; РУ РК-МТ-5№016668"),
                new Line(6, AiVerdict.NO, null, "передумал"));
    }

    @Test
    void badVerdict_isUnparsed_garbageIgnored_reasonCut() {
        String longWhy = "о".repeat(800);
        var p = AiAnswerParser.parse("L9 | может быть | P1 | ?\nпросто текст | с чертой\nL10 | идти | — | " + longWhy);
        assertThat(p.unparsed()).singleElement().asString().contains("L9");
        assertThat(p.lines()).singleElement().satisfies(l -> assertThat(l.reason()).hasSizeLessThanOrEqualTo(AiAnswerParser.REASON_MAX));
        assertThat(AiAnswerParser.parse(null).lines()).isEmpty();
        assertThat(AiAnswerParser.parse("без блока").lines()).isEmpty();
    }
}
```

- [ ] **Step 2: Красный** (`--tests 'com.vladoose.nir.tgbot.AiAnswerParserTest'`, `t5.log`) — компиляция: нет `AiAnswerParser`.

- [ ] **Step 3: Реализация**

```java
package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.integration.telegram.TelegramText;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Блок «Для АИС» из ответа ИИ (спека 2026-10-21-tender-telegram-bot §6): строки «L5826 | идти | P123 | почему» где угодно
 * в тексте — в блоке кода, таблице Markdown, списке, с кодами в скобках и кириллическими «Л» / «Р»; рассуждение вокруг не
 * мешает. Повтор лота — последняя строка. Строка с кодом лота, которую не разобрать, — в unparsed. Чистая функция.
 */
public final class AiAnswerParser {

    public static final int REASON_MAX = 500;

    public record Line(long lotId, AiVerdict verdict, Long productId, String reason) {}

    public record Parsed(List<Line> lines, List<String> unparsed) {}

    private static final Pattern LOT = Pattern.compile("^\\[?\\s*[LlЛл]\\s*(\\d{1,12})\\s*]?$");
    private static final Pattern PRODUCT = Pattern.compile("^\\[?\\s*[PpРр]\\s*(\\d{1,12})\\s*]?$");
    private static final Pattern LOOKS_LIKE_LINE = Pattern.compile("(?<![\\p{L}\\d])\\[?[LlЛл]\\s*\\d{1,12}]?\\s*\\|");
    private static final Pattern LEAD = Pattern.compile("^(?:[>*•\\-–—]+\\s*|\\d{1,3}[.)]\\s+)+");
    private static final Set<String> NO_PRODUCT = Set.of("", "—", "-", "–", "нет", "n/a", "none", "null");

    private AiAnswerParser() {}

    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) return new Parsed(List.of(), List.of());
        Map<Long, Line> lines = new LinkedHashMap<>();
        List<String> unparsed = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String s = LEAD.matcher(raw.replace("`", "").strip()).replaceFirst("").strip();
            if (s.startsWith("|")) s = s.substring(1);
            if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
            String[] cells = s.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].strip();
            var lot = LOT.matcher(cells[0]);
            if (cells.length < 3 || !lot.matches()) {
                if (LOOKS_LIKE_LINE.matcher(s).find()) unparsed.add(TelegramText.oneLine(raw, 120));
                continue;
            }
            AiVerdict verdict = verdict(cells[1]);
            if (verdict == null) {
                unparsed.add(TelegramText.oneLine(raw, 120));
                continue;
            }
            Long product = null;
            String productText = null;
            var p = PRODUCT.matcher(cells[2]);
            if (p.matches()) product = Long.parseLong(p.group(1));
            else if (!NO_PRODUCT.contains(cells[2].toLowerCase(Locale.ROOT))) productText = cells[2];
            String why = cells.length > 3 ? String.join(" | ", Arrays.copyOfRange(cells, 3, cells.length)).strip() : "";
            String reason = productText == null ? why : productText + (why.isEmpty() ? "" : "; " + why);
            long lotId = Long.parseLong(lot.group(1));
            lines.remove(lotId);
            lines.put(lotId, new Line(lotId, verdict, product, reason.isEmpty() ? null : TelegramText.oneLine(reason, REASON_MAX)));
        }
        return new Parsed(List.copyOf(lines.values()), List.copyOf(unparsed));
    }

    static AiVerdict verdict(String cell) {
        String v = cell.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[*_«»\"'.!:;]", "").replaceAll("\\s+", " ").strip();
        if (v.startsWith("не наш") || v.equals("нет") || v.startsWith("не иди") || v.startsWith("не подход") || v.equals("no")) {
            return AiVerdict.NO;
        }
        if (v.startsWith("не ясн") || v.equals("неясно") || v.equals("?") || v.startsWith("не уверен") || v.equals("unsure")) {
            return AiVerdict.UNSURE;
        }
        if (v.startsWith("идти") || v.equals("иди") || v.equals("да") || v.startsWith("подход") || v.equals("go") || v.equals("yes")) {
            return AiVerdict.GO;
        }
        return null;
    }
}
```

- [ ] **Step 4: Зелёный.** **Step 5: Мутации** — (а) убрать снятие `|` по краям → краснеет таблица; (б) убрать `[ЛлРр]` → краснеет кириллица; (в) `lines.putIfAbsent` вместо remove+put → краснеет «повтор лота». **Step 6: Коммит** — `feat(tg-bot): разбор блока «Для АИС»`.

---

### Task 6: Применение ответа ИИ — `AiAnswerService`, получатели писем, API, вывод ИИ у лота

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/aianswer/AiAnswerResult.java`, `LetterRecipients.java`, `AiAnswerService.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/AiAnswerRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java` (`POST /api/tenders/ai-answer` рядом с `/ai-brief`)
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java`, `mapper/TenderLotMapper.java` (`toResponse`)
- Test: `src/test/java/com/vladoose/nir/tgbot/LetterRecipientsTest.java`, `AiAnswerServiceTest.java`, `AiAnswerApiTest.java`

**Interfaces:**
- Consumes: `AiAnswerParser` (задача 5), `LotModelService.applyByAi` / `equipmentForSiteProduct` (задача 2), `PriceRequestItemRepository.lastRequestedAt` (задача 1), `TgFixtures`.
- Produces:
  - `record AiAnswerResult(List<LotResult> lots, List<String> unparsed, List<LetterPlan> letters, List<String> notes)` + `boolean isEmpty()`;
  - `record AiAnswerResult.LotResult(long lotId, Long tenderId, String tenderNumber, String lotName, String customer, AiVerdict verdict, Outcome outcome, String model, String text)`;
  - `record AiAnswerResult.LetterPlan(long tenderId, String tenderNumber, String customer, long distributorId, String distributorName, boolean manufacturer, List<Long> lotIds, List<String> lotNames)`;
  - `enum AiAnswerResult.Outcome { PINNED, SAME_MODEL, KEPT_MANUAL, ADVICE, NOT_OURS, UNSURE, CLOSED, LOT_NOT_FOUND, PRODUCT_NOT_CANDIDATE, PRODUCT_REMOVED }`;
  - `AiAnswerService.apply(String text): AiAnswerResult` (`@Transactional`; KZ; ≤ 20 000 знаков);
  - `LetterRecipients.pick(List<Distributor> all, String brand): List<Distributor>`;
  - `TenderLotResponse.aiVerdict / aiProductName / aiNote / aiBy / aiAt / modelByAi`.

- [ ] **Step 1: Тесты**

`LetterRecipientsTest.java`:
```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.service.aianswer.LetterRecipients;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Кому бот готовит письмо (спека 2026-10-21-tender-telegram-bot §2 п. 5): завод с почтой → «Доверенный» с почтой → никто. */
class LetterRecipientsTest {

    static Distributor d(String name, String email, boolean maker, boolean trusted, String... brands) {
        return Distributor.builder().name(name).email(email).manufacturer(maker).trusted(trusted)
                .brands(new ArrayList<>(List.of(brands))).build();
    }

    @Test
    void makerWithMailFirst_thenTrusted_neverPlainDealer() {
        Distributor maker = d("Аксион", "a@axion.ru", true, false, "Аксион");
        Distributor makerNoMail = d("Аксион-2", null, true, false, "Аксион");
        Distributor trusted = d("Дилер-Д", "t@x.kz", false, true, "Аксион");
        Distributor dealer = d("Дилер", "d@x.kz", false, false, "Аксион");
        assertThat(LetterRecipients.pick(List.of(dealer, trusted, maker), "Аксион")).containsExactly(maker);
        assertThat(LetterRecipients.pick(List.of(dealer, trusted, makerNoMail), "Аксион")).containsExactly(trusted);
        assertThat(LetterRecipients.pick(List.of(dealer), "Аксион")).isEmpty();
        assertThat(LetterRecipients.pick(List.of(maker), "не указан")).isEmpty();
        assertThat(LetterRecipients.pick(List.of(maker), null)).isEmpty();
    }
}
```

`AiAnswerServiceTest.java`:
```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestWriter;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.Outcome;
import com.vladoose.nir.service.aianswer.AiAnswerService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Применение блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §6). */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class AiAnswerServiceTest {

    @Autowired AiAnswerService service;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired SiteProductRepository products;
    @Autowired ProductGroupRepository groups;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired DistributorRepository distributors;
    @Autowired LotModelService models;
    @Autowired PriceRequestWriter writer;
    @Autowired EntityManager em;
    TenderLot lot;
    SiteProduct dki, other;
    Distributor maker;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZАксион");
        other = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-11", "ZZАксион");
        TgFixtures.match(matches, lot, TgFixtures.group(groups).getId(), ProductMatchLevel.TYPE, null, List.of(dki.getId(), other.getId()));
        maker = TgFixtures.distributor(distributors, "ZZ Аксион завод", "zz-axion@example.kz", true, false, "ZZАксион");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private String go(SiteProduct p) { return "L" + lot.getId() + " | идти | P" + p.getId() + " | ТЗ совпадает"; }

    private TenderLot reload() { em.flush(); em.clear(); return lots.findById(lot.getId()).orElseThrow(); }

    @Test
    void goWithCandidate_pinsModel_marksAi_andPlansLetterToMaker() {
        AiAnswerResult r = service.apply("```\n" + go(dki) + "\n```");
        assertThat(r.lots()).singleElement().satisfies(l -> {
            assertThat(l.outcome()).isEqualTo(Outcome.PINNED);
            assertThat(l.text()).startsWith("✅ «ZZ Дефибриллятор» · ZZ Горбольница №7 → ZZ Дефибриллятор ДКИ-Н-12");
        });
        TenderLot l = reload();
        assertThat(l.getProposedEquipment().getSiteProduct().getId()).isEqualTo(dki.getId());
        assertThat(l.isModelByAi()).isTrue();
        assertThat(l.getModelNote()).isEqualTo("ИИ: ТЗ совпадает");
        assertThat(l.getAiVerdict()).isEqualTo(AiVerdict.GO);
        assertThat(l.getAiProduct().getId()).isEqualTo(dki.getId());
        assertThat(l.getAiBy()).isEqualTo("vlad");
        assertThat(r.letters()).singleElement().satisfies(p -> {
            assertThat(p.distributorId()).isEqualTo(maker.getId());
            assertThat(p.manufacturer()).isTrue();
            assertThat(p.lotIds()).containsExactly(lot.getId());
        });
    }

    @Test
    void manualModelIsKept() {
        models.apply(lot, models.equipmentForSiteProduct(other), "руками");
        AiAnswerResult r = service.apply(go(dki));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.KEPT_MANUAL);
        assertThat(r.lots().get(0).text()).contains("замените в АИС");
        assertThat(reload().getProposedEquipment().getSiteProduct().getId()).isEqualTo(other.getId());
        assertThat(r.letters()).isEmpty();
    }

    @Test
    void aiModelIsReplacedByNextAnswer() {
        service.apply(go(other));
        AiAnswerResult r = service.apply(go(dki));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.PINNED);
        assertThat(reload().getProposedEquipment().getSiteProduct().getId()).isEqualTo(dki.getId());
    }

    @Test
    void productNotCandidate_notPinned_verdictKept() {
        SiteProduct stranger = TgFixtures.product(products, "ZZ Чужой", "ZZАксион");
        AiAnswerResult r = service.apply(go(stranger));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.PRODUCT_NOT_CANDIDATE);
        TenderLot l = reload();
        assertThat(l.getProposedEquipment()).isNull();
        assertThat(l.getAiVerdict()).isEqualTo(AiVerdict.GO);
        assertThat(l.getAiProduct()).isNull();
    }

    @Test
    void notOurs_recorded_noLetter() {
        AiAnswerResult r = service.apply("L" + lot.getId() + " | не наше | — | нужен передвижной");
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.NOT_OURS);
        assertThat(reload().getAiVerdict()).isEqualTo(AiVerdict.NO);
        assertThat(r.letters()).isEmpty();
    }

    @Test
    void closedTender_notPinned() {
        Tender t = lot.getTender();
        t.setDeadlineAt(LocalDateTime.now(Market.KZ.zone()).minusHours(1));
        tenders.saveAndFlush(t);
        assertThat(service.apply(go(dki)).lots().get(0).outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(reload().getProposedEquipment()).isNull();
    }

    @Test
    void foreignMarketLot_notFound() {
        MarketContext.set(Market.RF);
        TenderLot rf = TgFixtures.lot(tenders, "ZZ РФ лот", "ZZ");
        rf.getTender().setMarket(Market.RF);
        tenders.saveAndFlush(rf.getTender());
        MarketContext.set(Market.KZ);
        assertThat(service.apply("L" + rf.getId() + " | идти | — | x").lots().get(0).outcome()).isEqualTo(Outcome.LOT_NOT_FOUND);
    }

    @Test
    void trustedWhenNoMakerWithMail_noteWhenNobody() {
        maker.setEmail(null);
        distributors.saveAndFlush(maker);
        Distributor trusted = TgFixtures.distributor(distributors, "ZZ Доверенный", "zz-t@example.kz", false, true, "ZZАксион");
        assertThat(service.apply(go(dki)).letters()).singleElement()
                .satisfies(p -> assertThat(p.distributorId()).isEqualTo(trusted.getId()));
        trusted.setTrusted(false);
        distributors.saveAndFlush(trusted);
        AiAnswerResult r = service.apply("L" + lot.getId() + " | идти | P" + dki.getId() + " | снова");
        assertThat(r.letters()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("нет завода бренда «ZZАксион» с почтой"));
    }

    @Test
    void alreadyRequested_noteInsteadOfLetter() {
        service.apply(go(dki));
        TenderLot l = reload();
        writer.persist(l.getTender(), maker, List.of(new PriceRequestWriter.Line(l, l.getProposedEquipment(), 1)));
        AiAnswerResult r = service.apply(go(dki));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.SAME_MODEL);
        assertThat(r.letters()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("уже запрошено"));
    }

    @Test
    void rfMarket_400() {
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.apply(go(dki))).isInstanceOf(BadRequestException.class).hasMessageContaining("Казахстан");
    }
}
```

`AiAnswerApiTest.java` (образец MockMvc — `LotModelApiTest`):
```java
@SpringBootTest
@Transactional
class AiAnswerApiTest {
    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    MockMvc mvc;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Облучатель", "ZZ Поликлиника №2");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private MockHttpServletRequestBuilder answer(String text) {
        return post("/api/tenders/ai-answer").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + new ObjectMapper().valueToTree(text) + "}");
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void adminAppliesAnswer_lotShowsVerdict() throws Exception {
        mvc.perform(answer("L" + lot.getId() + " | не наше | — | нужен передвижной"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots[0].outcome").value("NOT_OURS"));
        MarketContext.set(Market.KZ);   // запрос MockMvc чистит MarketContext потока (CLAUDE.md §14)
        mvc.perform(get("/api/tenders/" + lot.getTender().getId() + "/lots").header("X-Market", "KZ"))
                .andExpect(jsonPath("$[0].aiVerdict").value("NO"))
                .andExpect(jsonPath("$[0].aiNote").value("нужен передвижной"))
                .andExpect(jsonPath("$[0].aiBy").value("vlad"));
    }

    @Test
    @WithMockUser(username = "op", roles = "USER")
    void operator_403() throws Exception {
        mvc.perform(answer("L" + lot.getId() + " | не наше | — | x")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void blank_400() throws Exception {
        mvc.perform(answer("  ")).andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Красный** (`--tests 'com.vladoose.nir.tgbot.LetterRecipientsTest' --tests '…AiAnswerServiceTest' --tests '…AiAnswerApiTest'`, `t6.log`) — компиляция.

- [ ] **Step 3: Реализация**

`AiAnswerResult.java`:
```java
package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.entity.AiVerdict;

import java.util.List;

/** Итог разбора ответа ИИ (спека 2026-10-21-tender-telegram-bot §6): по лотам, нераспознанное, письма, пометки. */
public record AiAnswerResult(List<LotResult> lots, List<String> unparsed, List<LetterPlan> letters, List<String> notes) {

    public boolean isEmpty() { return lots.isEmpty() && unparsed.isEmpty(); }

    public enum Outcome { PINNED, SAME_MODEL, KEPT_MANUAL, ADVICE, NOT_OURS, UNSURE, CLOSED, LOT_NOT_FOUND, PRODUCT_NOT_CANDIDATE, PRODUCT_REMOVED }

    /** text — готовая строка для бота и экрана («✅ «Дефибриллятор» · ГБ №7 → ДКИ-Н-12 — закреплено»). */
    public record LotResult(long lotId, Long tenderId, String tenderNumber, String lotName, String customer, AiVerdict verdict,
                            Outcome outcome, String model, String text) {}

    /** Письмо поставщику по лотам одного тендера: завод бренда модели или «Доверенный». */
    public record LetterPlan(long tenderId, String tenderNumber, String customer, long distributorId, String distributorName,
                             boolean manufacturer, List<Long> lotIds, List<String> lotNames) {}
}
```

`LetterRecipients.java`:
```java
package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.util.BrandMatch;

import java.util.List;

/** Кому бот готовит письмо по бренду модели (спека 2026-10-21-tender-telegram-bot §2 п. 5): завод с почтой, иначе «Доверенные». */
public final class LetterRecipients {

    private LetterRecipients() {}

    public static List<Distributor> pick(List<Distributor> all, String brand) {
        if (brand == null || brand.isBlank() || "не указан".equalsIgnoreCase(brand.strip())) return List.of();
        List<Distributor> withBrand = all.stream()
                .filter(d -> d.getEmail() != null && !d.getEmail().isBlank())
                .filter(d -> BrandMatch.firstCarried(d.getBrands(), brand) != null).toList();
        List<Distributor> makers = withBrand.stream().filter(Distributor::isManufacturer).toList();
        return makers.isEmpty() ? withBrand.stream().filter(Distributor::isTrusted).toList() : makers;
    }
}
```

`AiAnswerService.java`:
```java
package com.vladoose.nir.service.aianswer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.DistributorService;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LetterPlan;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LotResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.Outcome;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Применение блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §6) — общее для бота и «✍️ Вставить ответ ИИ» в АИС:
 * вывод ИИ — у лота всегда; «идти» + наш товар из кандидатов лота — модель в работе (ручную не трогает); письма — заводу
 * бренда модели или «Доверенным», если этому поставщику по лоту запрос ещё не уходил.
 */
@Service
public class AiAnswerService {

    public static final int MAX_TEXT = 20_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final TenderLotRepository lots;
    private final TenderLotProductMatchRepository matches;
    private final SiteProductRepository products;
    private final LotModelService models;
    private final DistributorService distributors;
    private final PriceRequestItemRepository items;

    public AiAnswerService(TenderLotRepository lots, TenderLotProductMatchRepository matches, SiteProductRepository products,
                           LotModelService models, DistributorService distributors, PriceRequestItemRepository items) {
        this.lots = lots;
        this.matches = matches;
        this.products = products;
        this.models = models;
        this.distributors = distributors;
        this.items = items;
    }

    @Transactional
    public AiAnswerResult apply(String text) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        if (text == null || text.isBlank()) throw new BadRequestException("Вставьте ответ ИИ — блок «Для АИС»");
        if (text.length() > MAX_TEXT) throw new BadRequestException("Ответ длиннее 20 000 знаков — вставьте только блок «Для АИС»");
        AiAnswerParser.Parsed parsed = AiAnswerParser.parse(text);
        LocalDateTime now = LocalDateTime.now(Market.KZ.zone());
        OffsetDateTime stamp = OffsetDateTime.now();
        String by = CurrentUser.label();
        List<LotResult> results = new ArrayList<>();
        List<TenderLot> toLetter = new ArrayList<>();
        for (AiAnswerParser.Line line : parsed.lines()) {
            TenderLot lot = lots.findById(line.lotId()).orElse(null);
            if (lot == null || lot.getTender().getMarket() != Market.KZ || lot.getTender().getSource() != Source.PUBLIC_TENDER) {
                results.add(new LotResult(line.lotId(), null, null, null, null, line.verdict(), Outcome.LOT_NOT_FOUND, null,
                        "⚠ лот L" + line.lotId() + " не найден"));
                continue;
            }
            Tender t = lot.getTender();
            SiteProduct product = null;
            Outcome rejected = null;
            if (line.productId() != null) {
                if (!candidates(lot.getId()).contains(line.productId())) {
                    rejected = Outcome.PRODUCT_NOT_CANDIDATE;
                } else {
                    product = products.findById(line.productId()).orElse(null);
                    if (product == null || product.getRemovedAt() != null) {
                        rejected = Outcome.PRODUCT_REMOVED;
                        product = null;
                    }
                }
            }
            lot.setAiVerdict(line.verdict());
            lot.setAiProduct(product);
            lot.setAiNote(line.reason());
            lot.setAiBy(by);
            lot.setAiAt(stamp);
            boolean closed = !"ACTIVE".equals(t.getStatus()) || ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now);
            String model = product == null ? null : ProductMatchQuery.shortName(product.getName());
            Outcome o;
            if (line.verdict() == AiVerdict.NO) o = Outcome.NOT_OURS;
            else if (line.verdict() == AiVerdict.UNSURE) o = Outcome.UNSURE;
            else if (rejected != null) o = rejected;
            else if (product == null) o = Outcome.ADVICE;
            else if (closed) o = Outcome.CLOSED;
            else o = pin(lot, product, line.reason());
            if (o == Outcome.PINNED || o == Outcome.SAME_MODEL) toLetter.add(lot);
            results.add(new LotResult(lot.getId(), t.getId(), t.getTenderNumber(), lot.getEquipName(), t.getCustomerName(),
                    line.verdict(), o, model, text(o, lot, line, model)));
        }
        List<String> notes = new ArrayList<>();
        List<LetterPlan> letters = plan(toLetter, notes);
        return new AiAnswerResult(results, parsed.unparsed(), letters, notes);
    }

    /** Наши товары, что были у лота в тексте для ИИ: товар отметки и кандидаты сверки (как ProductAiBrief). */
    private Set<Long> candidates(long lotId) {
        Set<Long> ids = new HashSet<>();
        for (TenderLotProductMatch m : matches.findByLotId(lotId)) {
            if (m.getProductId() != null) ids.add(m.getProductId());
            if (m.getFitCandidates() != null) m.getFitCandidates().forEach(c -> ids.add(c.productId()));
        }
        return ids;
    }

    private Outcome pin(TenderLot lot, SiteProduct product, String reason) {
        MedEquipment cur = lot.getProposedEquipment();
        if (cur != null && cur.getSiteProduct() != null && cur.getSiteProduct().getId().equals(product.getId())) return Outcome.SAME_MODEL;
        if (cur != null && !lot.isModelByAi()) return Outcome.KEPT_MANUAL;
        models.applyByAi(lot, models.equipmentForSiteProduct(product), reason == null ? "ИИ" : "ИИ: " + reason);
        return Outcome.PINNED;
    }

    private List<LetterPlan> plan(List<TenderLot> toLetter, List<String> notes) {
        if (toLetter.isEmpty()) return List.of();
        List<Distributor> all = distributors.findAll();   // рынок режет аспект
        Map<String, Acc> groups = new LinkedHashMap<>();
        for (TenderLot lot : toLetter) {
            String brand = lot.getProposedEquipment() == null ? null : lot.getProposedEquipment().getManufact();
            List<Distributor> to = LetterRecipients.pick(all, brand);
            if (to.isEmpty()) {
                notes.add("«" + lotName(lot) + "» — письмо из АИС: нет завода бренда «" + Objects.toString(brand, "—") + "» с почтой");
                continue;
            }
            for (Distributor d : to) {
                OffsetDateTime last = items.lastRequestedAt(lot.getId(), d.getId());
                if (last != null) {
                    notes.add("«" + lotName(lot) + "» — у " + d.getName() + " уже запрошено "
                            + last.atZoneSameInstant(Market.KZ.zone()).format(DAY));
                    continue;
                }
                groups.computeIfAbsent(lot.getTender().getId() + ":" + d.getId(), k -> new Acc(lot.getTender(), d)).add(lot);
            }
        }
        return groups.values().stream().map(Acc::plan).toList();
    }

    private static final class Acc {
        final Tender tender;
        final Distributor d;
        final List<Long> lotIds = new ArrayList<>();
        final List<String> lotNames = new ArrayList<>();

        Acc(Tender tender, Distributor d) { this.tender = tender; this.d = d; }

        void add(TenderLot l) { lotIds.add(l.getId()); lotNames.add(lotName(l)); }

        LetterPlan plan() {
            return new LetterPlan(tender.getId(), tender.getTenderNumber(), tender.getCustomerName(), d.getId(), d.getName(),
                    d.isManufacturer(), List.copyOf(lotIds), List.copyOf(lotNames));
        }
    }

    private static String text(Outcome o, TenderLot lot, AiAnswerParser.Line line, String model) {
        String head = "«" + lotName(lot) + "»" + who(lot.getTender());
        String why = line.reason() == null ? "" : line.reason();
        return switch (o) {
            case PINNED -> "✅ " + head + " → " + model + " — закреплено";
            case SAME_MODEL -> "✅ " + head + " → " + model + " — уже в работе";
            case KEPT_MANUAL -> "⚠ " + head + " — у лота уже " + current(lot) + ", ИИ советует " + model + " — замените в АИС";
            case ADVICE -> "💬 " + head + " — ИИ советует не наш товар" + (why.isEmpty() ? "" : ": " + why) + "; закрепить можно в АИС";
            case NOT_OURS -> "✕ " + head + " — не наше" + (why.isEmpty() ? "" : ": " + why);
            case UNSURE -> "❔ " + head + " — не ясно" + (why.isEmpty() ? "" : ": " + why);
            case CLOSED -> "⏹ " + head + " — приём закрыт, модель не закреплена";
            case PRODUCT_NOT_CANDIDATE -> "⚠ " + head + " — товар P" + line.productId() + " не из кандидатов лота — не закрепляю";
            case PRODUCT_REMOVED -> "⚠ " + head + " — товар P" + line.productId() + " снят с сайта — не закрепляю";
            case LOT_NOT_FOUND -> "⚠ лот L" + line.lotId() + " не найден";
        };
    }

    private static String who(Tender t) {
        String c = t.getCustomerName();
        return c == null || c.isBlank() ? "" : " · " + TelegramText.oneLine(c, 60);
    }

    private static String current(TenderLot lot) {
        MedEquipment e = lot.getProposedEquipment();
        String by = lot.getModelSetBy();
        return ProductMatchQuery.shortName(e.getName()) + (by == null ? "" : " (" + by + ")");
    }

    private static String lotName(TenderLot l) {
        return TelegramText.oneLine(Objects.toString(l.getEquipName(), "без названия"), 80);
    }
}
```

`AiAnswerRequest.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiAnswerRequest {
    @NotBlank(message = "Вставьте ответ ИИ — блок «Для АИС»")
    @Size(max = 20000, message = "Ответ длиннее 20 000 знаков — вставьте только блок «Для АИС»")
    private String text;
}
```

`TenderController.java` — поле `AiAnswerService aiAnswers` в конструктор, рядом с `/ai-brief`:
```java
    /** «✍️ Вставить ответ ИИ» (спека 2026-10-21-tender-telegram-bot §6, §10 п. 3): разбор блока, модели, письма. */
    @PostMapping("/ai-answer")
    @PreAuthorize("hasRole('ADMIN')")
    public AiAnswerResult aiAnswer(@Valid @RequestBody AiAnswerRequest req) {
        return aiAnswers.apply(req.getText());
    }
```

`TenderLotResponse.java` — после `modelSetAt`:
```java
    /** Последний вывод ИИ (спека 2026-10-21-tender-telegram-bot §10 п. 1). */
    private AiVerdict aiVerdict;
    private String aiProductName;
    private String aiNote;
    private String aiBy;
    private OffsetDateTime aiAt;
    private boolean modelByAi;
```
`TenderLotMapper.toResponse` — добавить `@Mapping(target = "aiProductName", source = "aiProduct.name")`.

- [ ] **Step 4: Зелёный** (те же тесты + `--tests 'com.vladoose.nir.lotmodel.*'`).

- [ ] **Step 5: Мутации** — (а) не проверять кандидатов → краснеет `productNotCandidate…`; (б) в `pin` убрать проверку `!lot.isModelByAi()` (всегда менять) → краснеет `manualModelIsKept`; (в) в `LetterRecipients` вернуть `withBrand` без отбора → краснеет `makerWithMailFirst…`; (г) не проверять `lastRequestedAt` → краснеет `alreadyRequested…`; (д) убрать проверку закрытого приёма → краснеет `closedTender…`.

- [ ] **Step 6: Коммит** — `feat(tg-bot): применение ответа ИИ — модель, вывод у лота, письма заводу; POST /api/tenders/ai-answer`.

---

### Task 7: Панель «КП» (сервер) — «Указать клиента» по поставщикам, завод первым

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java` (+`discloseClientIds`)
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java` (`send`, `preview`, `options`)
- Modify: `src/main/java/com/vladoose/nir/service/LotSourcingService.java` (завод бренда)
- Test: `src/test/java/com/vladoose/nir/tgbot/TenderKpClientLineTest.java`, `LotSourcingMakerTest.java`

**Interfaces:**
- Produces: `PriceRequestSendRequest.discloseClientIds: List<Long>`; причина подбора `MAKER` «завод»; у завода бренда с почтой — `preselect = true`, у дилеров бренда — `false`.

- [ ] **Step 1: Тесты**

`LotSourcingMakerTest.java`:
```java
@SpringBootTest
@Transactional
class LotSourcingMakerTest {
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired MedEquipmentRepository equipment;
    @Autowired LotModelService models;
    @Autowired LotSourcingService sourcing;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void makerWithMailIsFirstAndOnlyPreselected() {
        MarketContext.set(Market.KZ);
        TenderLot lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ ГБ");
        models.apply(lot, equipment.saveAndFlush(MedEquipment.builder().name("ZZ ДКИ").manufact("ZZБренд").market(Market.KZ).build()), null);
        Distributor dealer = TgFixtures.distributor(distributors, "ZZ Дилер", "d@x.kz", false, false, "ZZБренд");
        Distributor maker = TgFixtures.distributor(distributors, "ZZ Завод", "m@x.kz", true, false, "ZZБренд");

        LotSourcingResponse r = sourcing.build(lot.getTender().getId(), List.of(lot.getId()), null);

        LotSourcingResponse.Entry first = r.getDistributors().get(0);
        assertThat(first.getDistributor().getId()).isEqualTo(maker.getId());
        assertThat(first.isPreselect()).isTrue();
        assertThat(first.getReasons().get(0).getKind()).isEqualTo("MAKER");
        assertThat(r.getDistributors()).filteredOn(e -> e.getDistributor().getId().equals(dealer.getId()))
                .singleElement().satisfies(e -> assertThat(e.isPreselect()).isFalse());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void withoutMaker_dealerStaysPreselected() {
        MarketContext.set(Market.KZ);
        TenderLot lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ ГБ");
        models.apply(lot, equipment.saveAndFlush(MedEquipment.builder().name("ZZ ДКИ").manufact("ZZБренд2").market(Market.KZ).build()), null);
        Distributor dealer = TgFixtures.distributor(distributors, "ZZ Дилер", "d@x.kz", false, false, "ZZБренд2");
        LotSourcingResponse r = sourcing.build(lot.getTender().getId(), List.of(lot.getId()), null);
        assertThat(r.getDistributors()).filteredOn(e -> e.getDistributor().getId().equals(dealer.getId()))
                .singleElement().satisfies(e -> assertThat(e.isPreselect()).isTrue());
    }
}
```

`TenderKpClientLineTest.java` (MockMvc, ADMIN, `X-Market: KZ`):
```java
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class TenderKpClientLineTest {
    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    MockMvc mvc;
    TenderLot lot;
    Distributor maker, dealer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        maker = TgFixtures.distributor(distributors, "ZZ Завод", "m@x.kz", true, false, "ZZБренд");
        dealer = TgFixtures.distributor(distributors, "ZZ Дилер", "d@x.kz", false, false, "ZZБренд");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private String req(List<Long> ids, List<Long> client, String bodyOverride) {
        return "{\"tenderId\":" + lot.getTender().getId() + ",\"distributorIds\":" + ids + ",\"discloseClientIds\":" + client
                + ",\"items\":[{\"tenderLotId\":" + lot.getId() + ",\"requestedQuantity\":1}]"
                + (bodyOverride == null ? "" : ",\"bodyOverride\":\"" + bodyOverride + "\"") + "}";
    }

    @Test
    void previewHasClientLineOnlyForChosen() throws Exception {
        mvc.perform(post("/api/price-requests/preview").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(maker.getId()), List.of(maker.getId()), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(containsString("Конечный заказчик: ZZ Горбольница №7")));
        MarketContext.set(Market.KZ);
        mvc.perform(post("/api/price-requests/preview").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(dealer.getId()), List.of(), null)))
                .andExpect(jsonPath("$.body").value(not(containsString("Конечный заказчик"))));
    }

    @Test
    void sendSplitsByClient_andEditedMixedIs400() throws Exception {
        mvc.perform(post("/api/price-requests/send").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(maker.getId(), dealer.getId()), List.of(maker.getId()), null)))
                .andExpect(status().isOk());
        MarketContext.set(Market.KZ);
        assertThat(priceRequests.findAll()).filteredOn(p -> p.getTender().getId().equals(lot.getTender().getId()))
                .extracting(p -> p.getDistributor().getId(), PriceRequest::isDiscloseClient)
                .containsExactlyInAnyOrder(tuple(maker.getId(), true), tuple(dealer.getId(), false));
        mvc.perform(post("/api/price-requests/send").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(maker.getId(), dealer.getId()), List.of(maker.getId()), "правка")))
                .andExpect(status().isBadRequest());
    }
}
```
(SMTP в тестах не поднят — письма «не ушли», запросы записаны; проверяется только флаг `disclose_client`.)

- [ ] **Step 2: Красный** (`t7.log`) — `discloseClientIds` не принимается, строки «Конечный заказчик» нет, «MAKER» нет.

- [ ] **Step 3: Правка**

`PriceRequestSendRequest.java`:
```java
    /** Кому в письме и бланке — строка о больнице-заказчике (спека 2026-10-21-tender-telegram-bot §10 п. 2). */
    private List<Long> discloseClientIds;
```
и поправить javadoc «Указать клиента в тендерах нет» → «… — по поставщикам, `discloseClientIds`».

`PriceRequestController.java`:
```java
    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PriceRequestSendService.SendResult> send(@Valid @RequestBody PriceRequestSendRequest req) {
        var items = items(req);
        Set<Long> withClient = new HashSet<>(req.getDiscloseClientIds() == null ? List.of() : req.getDiscloseClientIds());
        List<Long> yes = req.getDistributorIds().stream().filter(withClient::contains).toList();
        List<Long> no = req.getDistributorIds().stream().filter(id -> !withClient.contains(id)).toList();
        boolean edited = notBlank(req.getSubjectOverride()) || notBlank(req.getBodyOverride());
        // правленый текст один на всех — у поставщиков с клиентом и без него тексты разные: строка о больнице ушла бы дилеру
        if (edited && !yes.isEmpty() && !no.isEmpty()) {
            throw new BadRequestException("Текст письма правился — поставщикам с клиентом и без него отправьте отдельно");
        }
        List<PriceRequestSendService.SendResult> out = new ArrayList<>();
        if (!yes.isEmpty()) out.addAll(sendService.send(req.getTenderId(), yes, items, req.getSubjectOverride(), req.getBodyOverride(), options(req, true)));
        if (!no.isEmpty()) out.addAll(sendService.send(req.getTenderId(), no, items, req.getSubjectOverride(), req.getBodyOverride(), options(req, false)));
        return out;
    }

    /** Опции панели КП тендера; «Указать клиента» — по поставщикам (discloseClientIds). */
    private static SendOptions options(PriceRequestSendRequest req, boolean discloseClient) {
        return new SendOptions(req.getReplyBy(), Boolean.TRUE.equals(req.getWithDocument()),
                Boolean.TRUE.equals(req.getWithStamp()), discloseClient, null);
    }

    private static List<PriceRequestSendService.SendItem> items(PriceRequestSendRequest req) {
        return req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
```
`preview(...)`: `return sendService.preview(req.getTenderId(), req.getDistributorIds(), items(req), options(req, req.getDiscloseClientIds() != null && req.getDiscloseClientIds().contains(req.getDistributorIds().get(0))));`

`LotSourcingService.build` — в цикле скоринга строки
```java
            boolean brandHit = !brandHits.isEmpty();
            double score = (brandHit ? 1.0 : 0.0) + (typeHit ? 0.7 : 0.0) + (brandHit && typeHit ? 0.3 : 0.0);
```
заменить на
```java
            boolean brandHit = !brandHits.isEmpty();
            // завод бренда — первым, с меткой «завод» (спека 2026-10-21 §10 п. 2; как частные заявки)
            boolean maker = brandHit && d.isManufacturer();
            if (maker) reasons.add(0, new LotSourcingResponse.Reason("MAKER", "завод"));
            double score = (brandHit ? 1.0 : 0.0) + (typeHit ? 0.7 : 0.0) + (brandHit && typeHit ? 0.3 : 0.0) + (maker ? 0.5 : 0.0);
```
а после цикла, перед `entries.sort(...)`:
```java
        // есть завод бренда с почтой — отмечен он, дилеры бренда — без отметки (письмо дилеру — решение оператора)
        if (entries.stream().anyMatch(LotSourcingService::makerWithMail)) {
            entries.forEach(x -> x.setPreselect(makerWithMail(x)));
        }
```
и метод класса:
```java
    /** Отмечен по бренду (preselect = попал бренд), завод и с почтой. */
    private static boolean makerWithMail(LotSourcingResponse.Entry e) {
        String mail = e.getDistributor().getEmail();
        return e.isPreselect() && e.getDistributor().isManufacturer() && mail != null && !mail.isBlank();
    }
```

- [ ] **Step 4: Зелёный** + `--tests 'com.vladoose.nir.sourcing.*' --tests 'com.vladoose.nir.pricerequest.*'`.

- [ ] **Step 5: Мутации** — (а) `options(req, false)` для обеих групп → краснеет `sendSplitsByClient…`; (б) убрать проверку «правка + смешанные» → краснеет 400; (в) убрать блок «есть завод бренда с почтой» после цикла → краснеет `makerWithMailIsFirstAndOnlyPreselected`; (г) метку «MAKER» добавлять в конец, а не первой → краснеет он же.

- [ ] **Step 6: Коммит** — `feat(tg-bot): «Указать клиента» по поставщикам в КП тендера, завод бренда — первым`.

---

### Task 8: `TelegramBotApi` — кнопки, правка, удаление, файлы, приём обновлений

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/telegram/TelegramBotApi.java`, `BotUpdate.java`
- Modify: `src/main/java/com/vladoose/nir/integration/telegram/TelegramText.java` (+`html`), `TelegramClient.java` (`maskToken` — пакетный статический), `TelegramSettings.java` (+ пакетный `botTokenForApi()` не нужен — `botToken()` уже пакетный)
- Modify (test): `src/test/java/com/vladoose/nir/integration/telegram/TelegramStubServer.java` (тип содержимого и ответы по методу)
- Test: `src/test/java/com/vladoose/nir/integration/telegram/TelegramBotApiTest.java`

**Interfaces:**
- Produces: `TelegramText.html(String): String`; `TelegramBotApi.Button(String text, String data, String url)` + `cb(text, data)`, `link(text, url)`; `long sendMessage(String html, boolean silent, List<List<Button>> keyboard, Long replyTo)`; `long sendForceReply(String html, Long replyTo, String placeholder)`; `void editKeyboard(long messageId, List<List<Button>> keyboard)`; `void deleteMessage(long messageId)`; `void answerCallback(String callbackId, String text, boolean alert)`; `long sendDocument(String fileName, byte[] bytes, String contentType, String captionHtml, List<List<Button>> keyboard, Long replyTo)`; `long sendPhoto(String fileName, byte[] png, String captionHtml, List<List<Button>> keyboard, Long replyTo)`; `List<BotUpdate> getUpdates(long offset, int timeoutSeconds)`; `long botId()`; `long chatId()`; `POLL_SECONDS = 25`. `BotUpdate(long updateId, Callback callback, Incoming message)`, `BotUpdate.Callback(String id, long fromId, long chatId, long messageId, String data)`, `BotUpdate.Incoming(long messageId, long chatId, long fromId, String text, Long replyToMessageId, Long replyToFromId)`. `TelegramStubServer.on(String method, Reply... r)`, `Request.contentType()`.

- [ ] **Step 1: Заглушка — ответы по методу**

В `TelegramStubServer`: `public record Request(String path, String body, String contentType) {}`; поле `private final Map<String, Deque<Reply>> byMethod = new ConcurrentHashMap<>();`; в обработчике:
```java
            String path = ex.getRequestURI().getPath();
            stub.requests.add(new Request(path, body, ex.getRequestHeaders().getFirst("Content-Type")));
            String method = path.substring(path.lastIndexOf('/') + 1);
            Deque<Reply> own = stub.byMethod.get(method);
            Reply r = own == null ? null : own.poll();
            if (r == null) r = stub.replies.poll();
```
и метод `public void on(String method, Reply... r) { byMethod.computeIfAbsent(method, k -> new ConcurrentLinkedDeque<>()).addAll(List.of(r)); }`; `Reply.json(String resultJson)` → `new Reply(200, "{\"ok\":true,\"result\":" + resultJson + "}", 0)`.

- [ ] **Step 2: Тест клиента**

```java
package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Кнопки бота (спека 2026-10-21-tender-telegram-bot §7–8): формы запросов Bot API, multipart, обновления, ошибки. */
class TelegramBotApiTest {

    static final String TOKEN = "123456:SECRET-TOKEN";
    final ObjectMapper json = new ObjectMapper();
    TelegramStubServer stub;

    @BeforeEach void up() throws Exception { stub = TelegramStubServer.start(0); }
    @AfterEach void down() { stub.close(); }

    TelegramBotApi api() {
        return new TelegramBotApi(new TelegramSettings(true, stub.url(), TOKEN, "-1001", "77", "55", "95"), json);
    }

    JsonNode lastJson() throws Exception { return json.readTree(stub.requests().get(stub.requests().size() - 1).body()); }

    @Test
    void sendMessage_htmlButtonsReplyThread() throws Exception {
        stub.on("sendMessage", TelegramStubServer.Reply.ok(501));
        long id = api().sendMessage("<b>Пачка</b>", true, List.of(List.of(Button.cb("📋 Для ИИ", "d:7:ai"),
                Button.link("Открыть в АИС", "https://ais.westmed.kz/tenders?ids=1,2"))), 42L);
        assertThat(id).isEqualTo(501);
        JsonNode b = lastJson();
        assertThat(b.path("message_thread_id").asLong()).isEqualTo(95);
        assertThat(b.path("parse_mode").asText()).isEqualTo("HTML");
        assertThat(b.path("disable_notification").asBoolean()).isTrue();
        assertThat(b.path("reply_parameters").path("message_id").asLong()).isEqualTo(42);
        JsonNode row = b.path("reply_markup").path("inline_keyboard").get(0);
        assertThat(row.get(0).path("callback_data").asText()).isEqualTo("d:7:ai");
        assertThat(row.get(1).path("url").asText()).endsWith("ids=1,2");
    }

    @Test
    void forceReply_editKeyboard_delete_answer() throws Exception {
        TelegramBotApi api = api();
        api.sendForceReply("Пришлите блок", 9L, "Вставьте блок «Для АИС»");
        assertThat(lastJson().path("reply_markup").path("force_reply").asBoolean()).isTrue();
        api.editKeyboard(501, List.of(List.of(Button.cb("✅ 1", "l:3:x"))));
        assertThat(stub.requests().get(1).path()).endsWith("/editMessageReplyMarkup");
        stub.on("deleteMessage", TelegramStubServer.Reply.error(400, "Bad Request: message can't be deleted"));
        api.deleteMessage(501);   // старше 48 ч — не ошибка
        api.answerCallback("cb1", "Ваш Telegram ID 111", true);
        assertThat(lastJson().path("show_alert").asBoolean()).isTrue();
    }

    @Test
    void sendDocument_multipartWithCyrillicName() {
        stub.on("sendDocument", TelegramStubServer.Reply.ok(600));
        long id = api().sendDocument("Для ИИ — 3 тендера.txt", "текст для ИИ".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8", "Вставьте в Claude", List.of(List.of(Button.cb("✍️ Ответ ИИ", "d:7:ans"))), 501L);
        assertThat(id).isEqualTo(600);
        TelegramStubServer.Request r = stub.requests().get(0);
        assertThat(r.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(r.body()).contains("name=\"document\"; filename=\"Для ИИ — 3 тендера.txt\"")
                .contains("текст для ИИ").contains("name=\"caption\"").contains("d:7:ans").contains("name=\"message_thread_id\"");
    }

    @Test
    void sendPhoto_multipart() {
        api().sendPhoto("бланк.png", new byte[]{1, 2, 3}, "Письмо 1 из 1", List.of(), 501L);
        assertThat(stub.requests().get(0).path()).endsWith("/sendPhoto");
        assertThat(stub.requests().get(0).body()).contains("name=\"photo\"; filename=\"бланк.png\"");
    }

    @Test
    void getUpdates_parsesCallbackAndReply() throws Exception {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[{\"update_id\":10,\"callback_query\":{\"id\":\"c1\",\"from\":{\"id\":111},"
                + "\"message\":{\"message_id\":501,\"chat\":{\"id\":-1001}},\"data\":\"d:7:ai\"}},{\"update_id\":11,\"message\":{"
                + "\"message_id\":502,\"chat\":{\"id\":-1001},\"from\":{\"id\":111},\"text\":\"L1 | идти | P2 | x\","
                + "\"reply_to_message\":{\"message_id\":501,\"from\":{\"id\":777}}}}]"));
        List<BotUpdate> us = api().getUpdates(10, 0);
        assertThat(us.get(0).callback()).isEqualTo(new BotUpdate.Callback("c1", 111, -1001, 501, "d:7:ai"));
        assertThat(us.get(1).message()).isEqualTo(new BotUpdate.Incoming(502, -1001, 111, "L1 | идти | P2 | x", 501L, 777L));
        JsonNode b = lastJson();
        assertThat(b.path("offset").asLong()).isEqualTo(10);
        assertThat(b.path("allowed_updates").toString()).contains("callback_query");
    }

    @Test
    void shortTooManyIsRetriedOnce() {
        stub.on("sendMessage", TelegramStubServer.Reply.tooMany(1), TelegramStubServer.Reply.ok(77));
        assertThat(api().sendMessage("x", true, null, null)).isEqualTo(77);
        assertThat(stub.requests()).hasSize(2);
    }

    @Test
    void errors_statusRetryAfter_noToken_botIdCached() {
        stub.on("getUpdates", TelegramStubServer.Reply.error(409, "Conflict: terminated by other getUpdates request; bot " + TOKEN));
        assertThatThrownBy(() -> api().getUpdates(0, 0)).isInstanceOf(TelegramException.class)
                .satisfies(e -> assertThat(((TelegramException) e).status()).isEqualTo(409))
                .hasMessageNotContaining("SECRET-TOKEN");
        stub.on("sendMessage", TelegramStubServer.Reply.tooMany(30));
        assertThatThrownBy(() -> api().sendMessage("x", true, null, null))
                .satisfies(e -> assertThat(((TelegramException) e).retryAfterSeconds()).isEqualTo(30));
        stub.on("getMe", TelegramStubServer.Reply.json("{\"id\":777,\"is_bot\":true}"));
        TelegramBotApi api = api();
        assertThat(api.botId()).isEqualTo(777);
        assertThat(api.botId()).isEqualTo(777);
        assertThat(stub.requests()).filteredOn(r -> r.path().endsWith("/getMe")).hasSize(1);
    }
}
```

- [ ] **Step 3: Красный** (`--tests 'com.vladoose.nir.integration.telegram.*'`, `t8.log`) — компиляция.

- [ ] **Step 4: Реализация**

`TelegramText.java` — добавить:
```java
    /** Экранирование для разметки HTML Telegram (& < > и кавычка в атрибуте). */
    public static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
```
`TelegramClient.java` — `private static String maskToken(...)` → `static String maskToken(...)` (пакетный).

`BotUpdate.java`:
```java
package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.databind.JsonNode;

/** Обновление Bot API, нужное кнопкам бота (спека 2026-10-21-tender-telegram-bot §8): нажатие или сообщение. */
public record BotUpdate(long updateId, Callback callback, Incoming message) {

    /** Нажатие кнопки: кто, в каком чате, на каком сообщении бота, данные кнопки. */
    public record Callback(String id, long fromId, long chatId, long messageId, String data) {}

    /** Сообщение: кто, где, текст; replyTo* — на какое сообщение и чьё это ответ (null — не ответ). */
    public record Incoming(long messageId, long chatId, long fromId, String text, Long replyToMessageId, Long replyToFromId) {}

    static BotUpdate of(JsonNode u) {
        long id = u.path("update_id").asLong();
        JsonNode cq = u.path("callback_query");
        if (cq.isObject()) {
            JsonNode msg = cq.path("message");
            return new BotUpdate(id, new Callback(cq.path("id").asText(), cq.path("from").path("id").asLong(),
                    msg.path("chat").path("id").asLong(), msg.path("message_id").asLong(), cq.path("data").asText("")), null);
        }
        JsonNode m = u.path("message");
        if (m.isObject()) {
            JsonNode r = m.path("reply_to_message");
            boolean reply = r.isObject();
            return new BotUpdate(id, null, new Incoming(m.path("message_id").asLong(), m.path("chat").path("id").asLong(),
                    m.path("from").path("id").asLong(), m.path("text").asText(m.path("caption").asText("")),
                    reply ? r.path("message_id").asLong() : null, reply ? r.path("from").path("id").asLong() : null));
        }
        return new BotUpdate(id, null, null);
    }
}
```

`TelegramBotApi.java`:
```java
package com.vladoose.nir.integration.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.GatewayHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Кнопки бота (спека 2026-10-21-tender-telegram-bot §7–8): сообщения с HTML и кнопками в тему «нашей продукции», ответы на
 * нажатия, правка кнопок и удаление своих сообщений, файл и фото (multipart), приём обновлений getUpdates. Уведомления без
 * кнопок шлёт по-прежнему {@link TelegramClient}. Токен стоит в пути — ни в одном тексте ошибки.
 */
@Component
public class TelegramBotApi {

    static final Duration DEADLINE = Duration.ofSeconds(30);
    /** long-poll getUpdates: Telegram держит запрос до 25 с, дедлайн всего обмена — на 10 с больше. */
    public static final int POLL_SECONDS = 25;
    /** 429 с ожиданием не дольше этого — повтор, дольше — ошибка действию. */
    static final int RETRY_MAX_SECONDS = 15;

    /** Кнопка: обратный вызов (data, до 64 байт) или ссылка. */
    public record Button(String text, String data, String url) {
        public static Button cb(String text, String data) { return new Button(text, data, null); }
        public static Button link(String text, String url) { return new Button(text, null, url); }
    }

    private final TelegramSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile Long botId;

    @Autowired
    public TelegramBotApi(TelegramSettings settings, ObjectMapper json) {
        this(settings, json, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    TelegramBotApi(TelegramSettings settings, ObjectMapper json, HttpClient http) {
        this.settings = settings;
        this.json = json;
        this.http = http;
    }

    /** Чат группы «Заявки» (TELEGRAM_CHAT_ID); не число — 0, тогда бот никого не слушает. */
    public long chatId() {
        try {
            return Long.parseLong(settings.chatId());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public long sendMessage(String html, boolean silent, List<List<Button>> keyboard, Long replyTo) {
        Map<String, Object> b = base(replyTo);
        b.put("text", html);
        b.put("parse_mode", "HTML");
        b.put("link_preview_options", Map.of("is_disabled", true));
        if (silent) b.put("disable_notification", true);
        if (keyboard != null && !keyboard.isEmpty()) b.put("reply_markup", Map.of("inline_keyboard", markup(keyboard)));
        return call("sendMessage", b, DEADLINE).path("message_id").asLong(0);
    }

    /** Просьба ответить (ForceReply): Telegram сам открывает поле ответа на это сообщение. */
    public long sendForceReply(String html, Long replyTo, String placeholder) {
        Map<String, Object> b = base(replyTo);
        b.put("text", html);
        b.put("parse_mode", "HTML");
        b.put("disable_notification", true);
        Map<String, Object> fr = new LinkedHashMap<>();
        fr.put("force_reply", true);
        if (placeholder != null) fr.put("input_field_placeholder", TelegramText.safeCut(placeholder, 64));
        b.put("reply_markup", fr);
        return call("sendMessage", b, DEADLINE).path("message_id").asLong(0);
    }

    public void editKeyboard(long messageId, List<List<Button>> keyboard) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("chat_id", settings.chatId());
        b.put("message_id", messageId);
        b.put("reply_markup", Map.of("inline_keyboard", markup(keyboard == null ? List.of() : keyboard)));
        ignore400(() -> call("editMessageReplyMarkup", b, DEADLINE));   // «message is not modified» / удалено — не ошибка
    }

    public void deleteMessage(long messageId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("chat_id", settings.chatId());
        b.put("message_id", messageId);
        ignore400(() -> call("deleteMessage", b, DEADLINE));   // старше 48 ч или уже удалено — остаётся как есть
    }

    public void answerCallback(String callbackId, String text, boolean alert) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("callback_query_id", callbackId);
        if (text != null && !text.isEmpty()) b.put("text", TelegramText.safeCut(text, 200));
        if (alert) b.put("show_alert", true);
        ignore400(() -> call("answerCallbackQuery", b, DEADLINE));   // нажатие устарело — не ошибка
    }

    public long sendDocument(String fileName, byte[] bytes, String contentType, String captionHtml,
                             List<List<Button>> keyboard, Long replyTo) {
        return upload("sendDocument", "document", fileName, bytes, contentType, captionHtml, keyboard, replyTo);
    }

    public long sendPhoto(String fileName, byte[] png, String captionHtml, List<List<Button>> keyboard, Long replyTo) {
        return upload("sendPhoto", "photo", fileName, png, "image/png", captionHtml, keyboard, replyTo);
    }

    public List<BotUpdate> getUpdates(long offset, int timeoutSeconds) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("offset", offset);
        b.put("timeout", timeoutSeconds);
        b.put("allowed_updates", List.of("message", "callback_query"));
        JsonNode r = call("getUpdates", b, Duration.ofSeconds(timeoutSeconds + 10L));
        List<BotUpdate> out = new ArrayList<>();
        if (r.isArray()) r.forEach(u -> out.add(BotUpdate.of(u)));
        return out;
    }

    /** id самого бота — ответ «на сообщение бота» узнаётся по нему; один запрос getMe на запуск. */
    public long botId() {
        Long id = botId;
        if (id == null) {
            id = call("getMe", Map.of(), DEADLINE).path("id").asLong(0);
            botId = id;
        }
        return id;
    }

    private String thread() {
        return settings.productsThreadId().isEmpty() ? settings.tendersThreadId() : settings.productsThreadId();
    }

    private Map<String, Object> base(Long replyTo) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("chat_id", settings.chatId());
        String t = thread();
        if (!t.isEmpty()) b.put("message_thread_id", threadId(t));
        if (replyTo != null) b.put("reply_parameters", Map.of("message_id", replyTo, "allow_sending_without_reply", true));
        return b;
    }

    private static long threadId(String t) {
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new TelegramException(0, "Telegram: тема «наших товаров» должна быть числом (id темы группы)", null);
        }
    }

    private static List<List<Map<String, String>>> markup(List<List<Button>> keyboard) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (List<Button> row : keyboard) {
            List<Map<String, String>> r = new ArrayList<>();
            for (Button btn : row) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("text", btn.text());
                if (btn.url() != null) m.put("url", btn.url()); else m.put("callback_data", btn.data());
                r.add(m);
            }
            if (!r.isEmpty()) rows.add(r);
        }
        return rows;
    }

    private interface Call { void run(); }

    private static void ignore400(Call c) {
        try {
            c.run();
        } catch (TelegramException e) {
            if (e.status() != 400) throw e;
        }
    }

    private long upload(String method, String field, String fileName, byte[] bytes, String contentType, String captionHtml,
                        List<List<Button>> keyboard, Long replyTo) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chat_id", settings.chatId());
        String t = thread();
        if (!t.isEmpty()) fields.put("message_thread_id", String.valueOf(threadId(t)));
        if (captionHtml != null && !captionHtml.isEmpty()) {
            fields.put("caption", captionHtml);
            fields.put("parse_mode", "HTML");
        }
        fields.put("disable_notification", "true");
        try {
            if (replyTo != null) fields.put("reply_parameters", json.writeValueAsString(Map.of("message_id", replyTo,
                    "allow_sending_without_reply", true)));
            if (keyboard != null && !keyboard.isEmpty()) {
                fields.put("reply_markup", json.writeValueAsString(Map.of("inline_keyboard", markup(keyboard))));
            }
        } catch (JsonProcessingException e) {
            throw new TelegramException(0, "Telegram: кнопки не собрались", null);
        }
        String boundary = "ais" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, fields, field, fileName, contentType, bytes);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(uri(method)).timeout(DEADLINE)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        } catch (IllegalArgumentException e) {
            throw new TelegramException(0, "Telegram: адрес API или токен в настройках некорректны", null);
        }
        return send(req, DEADLINE, method).path("message_id").asLong(0);
    }

    private static byte[] multipart(String boundary, Map<String, String> fields, String fileField, String fileName,
                                    String contentType, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
            write(out, e.getValue());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                + fileName.replaceAll("[\"\\\\\\r\\n]", "_") + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(data);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode call(String method, Map<String, Object> body, Duration deadline) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(uri(method)).timeout(deadline).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException | JsonProcessingException e) {   // текст IAE содержит адрес — наружу не отдаём
            throw new TelegramException(0, "Telegram: адрес API или токен в настройках некорректны", null);
        }
        return send(req, deadline, method);
    }

    /**
     * Обмен и разбор ответа. 429 (предел сообщений группы, общий с сайтом и medsite) с коротким retry_after — один повтор
     * (спека §8); длинное ожидание — наверх: поток бота один, минута ожидания — минута без нажатий. getUpdates не повторяем —
     * паузу держит цикл приёма.
     */
    private JsonNode send(HttpRequest req, Duration deadline, String method) {
        try {
            return result(exchange(req, deadline, method));
        } catch (TelegramException e) {
            Integer wait = e.retryAfterSeconds();
            if (e.status() != 429 || wait == null || wait > RETRY_MAX_SECONDS || "getUpdates".equals(method)) throw e;
            try {
                Thread.sleep(wait * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return result(exchange(req, deadline, method));
        }
    }

    private URI uri(String method) {
        return URI.create(settings.apiUrl().replaceAll("/+$", "") + "/bot" + settings.botToken() + "/" + method);
    }

    private HttpResponse<String> exchange(HttpRequest req, Duration deadline, String method) {
        try {
            return GatewayHttp.exchange(http, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    deadline, "Telegram", "методе " + method);
        } catch (GatewayException e) {
            throw new TelegramException(0, e.getMessage(), null);
        }
    }

    private JsonNode result(HttpResponse<String> resp) {
        JsonNode node;
        try {
            node = resp.body() == null || resp.body().isBlank() ? null : json.readTree(resp.body());
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (resp.statusCode() == 200 && node != null && node.path("ok").asBoolean(false)) return node.path("result");
        Integer retryAfter = node != null && node.path("parameters").has("retry_after")
                ? node.path("parameters").path("retry_after").asInt() : null;
        String desc = node == null ? "" : TelegramClient.maskToken(node.path("description").asText(""), settings.botToken());
        throw new TelegramException(resp.statusCode(), "Telegram: HTTP " + resp.statusCode()
                + (desc.isBlank() ? "" : " — " + TelegramText.safeCut(desc, 200)), retryAfter);
    }
}
```

- [ ] **Step 5: Зелёный** (весь пакет `integration.telegram` — прежние тесты `TelegramClientTest` тоже).

- [ ] **Step 6: Мутации** — (а) не маскировать токен → краснеет `errors_…noToken`; (б) не кешировать `botId` → краснеет «getMe один раз»; (в) `ignore400` пропускает и 500 → тест удаления с 500 (добавить шаг: `stub.on("deleteMessage", error(500, "x"))` → исключение); (г) без повтора при 429 → краснеет `shortTooManyIsRetriedOnce`; (д) повтор и при `retry_after` 30 → краснеет `errors_…` (ждёт исключение).

- [ ] **Step 7: Коммит** — `feat(tg-bot): TelegramBotApi — кнопки, ответы на нажатия, файлы, приём обновлений`.

---

### Task 9: Сообщение-пачка — составитель, отправка с кнопками, режим уведомителя

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProductDigestComposer.java`
- Create: `src/main/java/com/vladoose/nir/service/tgbot/ProductDigestPublisher.java`
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductWatchComposer.java` (`Card` + `ourSum`)
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductWatchNotifier.java`
- Modify: `src/main/resources/application.yaml` (`notify.telegram.bot-actions-enabled`)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductDigestComposerTest.java`, `src/test/java/com/vladoose/nir/tgbot/ProductDigestPublisherTest.java`, `ProductWatchNotifierDigestTest.java`

**Interfaces:**
- Consumes: `TelegramBotApi` (задача 8), `ProductDigestRepository` (задача 1), `TenderLot.aiVerdict` (задача 1).
- Produces: `ProductWatchComposer.Card(..., BigDecimal ourSum)` (новый последний компонент; прежний 13-аргументный конструктор оставлен); `ProductDigestComposer.compose(List<Card>, String publicUrl): List<Chunk>`, `record Chunk(String html, boolean sound, List<Long> tenderIds)`, `MAX_TENDERS = 15`; `ProductWatchNotifier.DigestPublisher { boolean publish(ProductDigestComposer.Chunk chunk); }`; `ProductDigestPublisher implements DigestPublisher`, `static List<List<Button>> keyboard(long digestId, List<Long> tenderIds, String publicUrl)`.

- [ ] **Step 1: Тесты**

`ProductDigestComposerTest.java` (чистая функция, карточки собираются руками):
```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.service.productwatch.ProductDigestComposer;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductWatchComposer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сообщение-пачка (спека 2026-10-21-tender-telegram-bot §4). */
class ProductDigestComposerTest {

    static ProductWatchComposer.Card card(long id, ProductMatchLevel level, ProductMatchQuery.FitSummary fit, String customer,
                                          BigDecimal sum) {
        ProductMatchQuery.LotLine lot = new ProductMatchQuery.LotLine(id * 10, "Дефибриллятор", 1, level, "Дефибрилляторы",
                "дефибриллятор", null, null, null, List.of(), fit);
        return new ProductWatchComposer.Card(id, "177" + id + "-1", "goszakup", null, "Закуп", customer, "Алматы", null,
                LocalDate.of(2026, 11, 2), LocalDateTime.of(2026, 11, 2, 14, 59), level, false, List.of(lot), sum);
    }

    @Test
    void lineFormat_linkEscaped_sumDeadlineMark_sound() {
        var fit = new ProductMatchQuery.FitSummary(FitVerdict.FITS, "ДКИ-Н-12 «Аксион»", "dki", 21, 34, 13, null, null, "ACTIVE");
        var chunks = ProductDigestComposer.compose(List.of(card(533, ProductMatchLevel.FITS, fit, "ГКП <Горбольница №7> & К", new BigDecimal("12500000"))),
                "https://ais.westmed.kz/");
        assertThat(chunks).singleElement().satisfies(c -> {
            assertThat(c.html()).startsWith("🔎 Потенциально наших тендеров: 1");
            assertThat(c.html()).contains("1. ✅ <a href=\"https://ais.westmed.kz/tenders?openId=533&amp;market=KZ\">Дефибриллятор</a>")
                    .contains("ГКП &lt;Горбольница №7&gt; &amp; К, Алматы")
                    .contains("наши лоты 12,5 млн ₸ · до 02.11 14:59 · подходит наш ДКИ-Н-12 «Аксион» · 21 из 34");
            assertThat(c.sound()).isTrue();
            assertThat(c.tenderIds()).containsExactly(533L);
        });
    }

    @Test
    void typeIsSilent_iconsByFit_upgradePrefix() {
        var partial = new ProductMatchQuery.FitSummary(FitVerdict.PARTIAL, "ОБПЕ-450", "o", 5, 8, 3, null, null, "ACTIVE");
        var chunks = ProductDigestComposer.compose(List.of(card(1, ProductMatchLevel.TYPE, partial, "П2", new BigDecimal("1200000")),
                card(2, ProductMatchLevel.TYPE, null, "П3", null)), null);
        String html = chunks.get(0).html();
        assertThat(chunks.get(0).sound()).isFalse();
        assertThat(html).contains("1. ◐ Дефибриллятор — П2").contains("похоже: ОБПЕ-450 · 5 из 8")
                .contains("2. 🏷 Дефибриллятор — П3").contains("наш вид: дефибриллятор").doesNotContain("<a href");
    }

    @Test
    void orderStarFitsPartialType_keepsDeadlineOrderInside_upgradePrefix() {
        var partial = new ProductMatchQuery.FitSummary(FitVerdict.PARTIAL, "ОБПЕ-450", "o", 5, 8, 3, null, null, "ACTIVE");
        ProductWatchComposer.Card type1 = card(1, ProductMatchLevel.TYPE, null, "П1", null);
        ProductWatchComposer.Card part = card(2, ProductMatchLevel.TYPE, partial, "П2", null);
        ProductWatchComposer.Card type3 = card(3, ProductMatchLevel.TYPE, null, "П3", null);
        ProductWatchComposer.Card fits = card(4, ProductMatchLevel.FITS, null, "П4", null);
        ProductWatchComposer.Card c = card(5, ProductMatchLevel.NAMED, null, "П5", null);
        ProductWatchComposer.Card named = new ProductWatchComposer.Card(5, c.number(), c.platformLabel(), null, c.subject(),
                c.customer(), c.region(), null, c.deadline(), c.deadlineAt(), ProductMatchLevel.NAMED, true, c.lots(), null);
        var chunk = ProductDigestComposer.compose(List.of(type1, part, type3, fits, named), null).get(0);
        assertThat(chunk.tenderIds()).containsExactly(5L, 4L, 2L, 1L, 3L);   // ★, ✅, ◐, 🏷 — внутри порядок входа (срок)
        assertThat(chunk.html()).contains("1. ⬆ Уточнение: ★ ");
    }

    @Test
    void chunksOf15() {
        List<ProductWatchComposer.Card> cards = new ArrayList<>();
        for (long i = 1; i <= 16; i++) cards.add(card(i, ProductMatchLevel.TYPE, null, "Заказчик " + i, null));
        var chunks = ProductDigestComposer.compose(cards, null);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).tenderIds()).hasSize(15);
        assertThat(chunks.get(0).html()).startsWith("🔎 Потенциально наших тендеров: 16 (часть 1 из 2)");
        assertThat(chunks.get(1).tenderIds()).containsExactly(16L);
    }
}
```

`ProductDigestPublisherTest.java` (`@SpringBootTest @Transactional` + `TelegramStubServer`):
```java
@SpringBootTest
@Transactional
class ProductDigestPublisherTest {
    @Autowired ProductDigestRepository digests;
    TelegramStubServer stub;

    @BeforeEach void up() throws Exception { stub = TelegramStubServer.start(0); }
    @AfterEach void down() { stub.close(); }

    ProductDigestPublisher publisher() {
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        return new ProductDigestPublisher(digests, api, "https://ais.westmed.kz");
    }

    @Test
    void savesDigestWithMessageId_buttonsCarryId() throws Exception {
        stub.on("sendMessage", TelegramStubServer.Reply.ok(812));
        boolean ok = publisher().publish(new ProductDigestComposer.Chunk("🔎 x", true, List.of(5L, 7L)));
        assertThat(ok).isTrue();
        ProductDigest d = digests.findAll().stream().filter(x -> Long.valueOf(812).equals(x.getMessageId())).findFirst().orElseThrow();
        assertThat(d.getTenderIds()).containsExactly(5L, 7L);
        String body = stub.requests().get(0).body();
        assertThat(body).contains("\"d:" + d.getId() + ":ai\"").contains("\"d:" + d.getId() + ":ans\"")
                .contains("tenders?ids=5,7&market=KZ");
    }

    @Test
    void failure_returnsFalse_digestRemoved() {
        stub.on("sendMessage", TelegramStubServer.Reply.error(500, "x"));
        long before = digests.count();
        assertThat(publisher().publish(new ProductDigestComposer.Chunk("🔎 x", false, List.of(5L)))).isFalse();
        assertThat(digests.count()).isEqualTo(before);
    }
}
```

`ProductWatchNotifierDigestTest.java` (образец — `ProductWatchNotifierTest`; отметки вставляются прямо в `tender_lot_product_match`, `groupRepo.deleteAllInBatch()` в начале — в откатываемой транзакции убирает чужие отметки nirdb):
```java
@SpringBootTest
@Transactional
class ProductWatchNotifierDigestTest {
    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired ProductMatchQuery query;
    @Autowired ProductMatchService matchService;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;
    final List<ProductDigestComposer.Chunk> published = new ArrayList<>();
    boolean failPublish;
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:1", "t:k", "-1", "52", "55", "95");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    ProductWatchNotifier notifier() {
        return new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG, (text, silent) -> { },
                true, ProductNotifyPolicy.of("all", "0"), "https://ais.westmed.kz", Clock.systemUTC(),
                chunk -> { if (failPublish) return false; published.add(chunk); return true; });
    }

    TenderLot marked(String name) {
        TenderLot lot = TgFixtures.lot(tenders, name, "ZZ ГБ");
        TgFixtures.match(matches, lot, TgFixtures.group(groupRepo).getId(), ProductMatchLevel.TYPE, null, List.of());
        return lot;
    }

    @Test
    void digestMode_publishesChunk_marksNotified() {
        TenderLot lot = marked("ZZ Облучатель");
        ImportSummary sum = new ImportSummary();
        notifier().afterRun(sum);
        assertThat(published).singleElement().satisfies(c -> assertThat(c.tenderIds()).containsExactly(lot.getTender().getId()));
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }

    @Test
    void allLotsNotOurs_noChunk_markedSilently() {
        TenderLot lot = marked("ZZ Халат");
        lot.setAiVerdict(AiVerdict.NO);
        lots.saveAndFlush(lot);
        notifier().afterRun(new ImportSummary());
        assertThat(published).isEmpty();
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }

    @Test
    void publishFails_notMarked_sideError() {
        TenderLot lot = marked("ZZ Аспиратор");
        failPublish = true;
        ImportSummary sum = new ImportSummary();
        notifier().afterRun(sum);
        assertThat(sum.getErrors()).isPositive();
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isNull();
    }
}
```
(Импорты тестов задачи — как у тестов выше; `ImportSummary` — `com.vladoose.nir.integration.goszakup.ImportSummary` (Lombok `@Data`, ошибки — `getErrors()`); `ProductNotifyPolicy.of` не различает регистр режима.)

- [ ] **Step 2: Красный** (`--tests '…ProductDigestComposerTest' --tests '…ProductDigestPublisherTest' --tests '…ProductWatchNotifierDigestTest'`, `t9.log`).

- [ ] **Step 3: Реализация**

`ProductWatchComposer.Card` — добавить последний компонент `BigDecimal ourSum` и конструктор совместимости:
```java
    public record Card(long tenderId, String number, String platformLabel, String tradeMethod, String subject, String customer,
                       String region, BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, ProductMatchLevel level,
                       boolean upgrade, List<ProductMatchQuery.LotLine> lots, BigDecimal ourSum) {
        /** Без суммы наших лотов — прежние вызовы (тесты сообщения «Наш товар»). */
        public Card(long tenderId, String number, String platformLabel, String tradeMethod, String subject, String customer,
                    String region, BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, ProductMatchLevel level,
                    boolean upgrade, List<ProductMatchQuery.LotLine> lots) {
            this(tenderId, number, platformLabel, tradeMethod, subject, customer, region, total, deadline, deadlineAt, level,
                    upgrade, lots, null);
        }
    }
```

`ProductDigestComposer.java`:
```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.telegram.TelegramText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сообщение-пачка «🔎 Потенциально наших тендеров» (спека 2026-10-21-tender-telegram-bot §4, чистая функция): до 15
 * тендеров в сообщении (столько берёт «Для ИИ») и не длиннее предела Telegram; строка — значок уровня, наши лоты ссылкой
 * на карточку, заказчик, регион; вторая — сумма наших лотов, срок приёма, наша отметка. Поля площадки — одной строкой и
 * экранированы (HTML).
 */
public final class ProductDigestComposer {

    public static final int MAX_TENDERS = ProductAiBrief.MAX_TENDERS;
    static final int MAX_VISIBLE = 3800;
    private static final BigDecimal THOUSAND = new BigDecimal(1000);
    private static final BigDecimal MILLION = new BigDecimal(1_000_000);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public record Chunk(String html, boolean sound, List<Long> tenderIds) {}

    private ProductDigestComposer() {}

    public static List<Chunk> compose(List<ProductWatchComposer.Card> input, String publicUrl) {
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
        // ★, ✅, ◐, 🏷 (спека §4); сортировка устойчивая — внутри значка остаётся порядок prepare(): срок приёма
        List<ProductWatchComposer.Card> cards = new ArrayList<>(input);
        cards.sort(java.util.Comparator.comparingInt(ProductDigestComposer::rank));
        List<List<ProductWatchComposer.Card>> groups = new ArrayList<>();
        List<ProductWatchComposer.Card> cur = new ArrayList<>();
        int visible = 80;
        for (ProductWatchComposer.Card c : cards) {
            int len = visible(block(c, base, 99)) + 2;
            if (!cur.isEmpty() && (cur.size() >= MAX_TENDERS || visible + len > MAX_VISIBLE)) {
                groups.add(cur);
                cur = new ArrayList<>();
                visible = 80;
            }
            cur.add(c);
            visible += len;
        }
        if (!cur.isEmpty()) groups.add(cur);
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            List<ProductWatchComposer.Card> g = groups.get(i);
            StringBuilder html = new StringBuilder("🔎 Потенциально наших тендеров: ").append(cards.size());
            if (groups.size() > 1) html.append(" (часть ").append(i + 1).append(" из ").append(groups.size()).append(')');
            int n = 1;
            for (ProductWatchComposer.Card c : g) html.append("\n\n").append(block(c, base, n++));
            out.add(new Chunk(html.toString(), g.stream().anyMatch(c -> c.level().alert()),
                    g.stream().map(ProductWatchComposer.Card::tenderId).toList()));
        }
        return out;
    }

    static String block(ProductWatchComposer.Card c, String base, int n) {
        String names = lotNames(c.lots());
        String name = base == null ? esc(names)
                : "<a href=\"" + esc(base + "/tenders?openId=" + c.tenderId() + "&market=KZ") + "\">" + esc(names) + "</a>";
        String who = join(", ", TelegramText.oneLine(c.customer(), 120), TelegramText.oneLine(c.region(), 60));
        StringBuilder b = new StringBuilder().append(n).append(". ").append(c.upgrade() ? "⬆ Уточнение: " : "")
                .append(icon(c)).append(' ').append(name);
        if (!who.isEmpty()) b.append(" — ").append(esc(who));
        List<String> second = new ArrayList<>();
        if (c.ourSum() != null) second.add("наши лоты " + money(c.ourSum()));
        if (c.deadlineAt() != null) second.add("до " + c.deadlineAt().format(DAY_TIME));
        else if (c.deadline() != null) second.add("до " + c.deadline().format(DAY));
        String mark = mark(c);
        if (!mark.isEmpty()) second.add(mark);
        if (!second.isEmpty()) b.append("\n   ").append(esc(String.join(" · ", second)));
        return b.toString();
    }

    static int rank(ProductWatchComposer.Card c) {
        return switch (icon(c)) {
            case "★" -> 0;
            case "✅" -> 1;
            case "◐" -> 2;
            default -> 3;
        };
    }

    static String icon(ProductWatchComposer.Card c) {
        if (c.level().exact()) return "★";
        if (c.level() == ProductMatchLevel.FITS) return "✅";
        ProductMatchQuery.FitSummary f = c.lots().isEmpty() ? null : c.lots().get(0).fit();
        return f != null && f.verdict() == FitVerdict.PARTIAL && f.met() > 0 ? "◐" : "🏷";
    }

    static String mark(ProductWatchComposer.Card c) {
        if (c.lots().isEmpty()) return "";
        ProductMatchQuery.LotLine l = c.lots().get(0);
        ProductMatchQuery.FitSummary f = l.fit();
        String fitName = f == null || f.productName() == null ? l.word() : ProductMatchQuery.shortName(f.productName());
        String met = f == null || f.total() == 0 ? "" : " · " + f.met() + " из " + f.total();
        return switch (l.level()) {
            case COPIED -> "ТЗ списано с нашего " + ProductMatchQuery.shortName(l.productName() == null ? l.word() : l.productName());
            case NAMED -> "назван наш " + ProductMatchQuery.what(l);
            case FITS -> "подходит наш " + fitName + met;
            case TYPE -> {
                if (f != null && f.verdict() == FitVerdict.PARTIAL && f.met() > 0) yield "похоже: " + fitName + met;
                if (f != null && f.verdict() == FitVerdict.FITS_NO_RU) yield "подходит по ТЗ " + fitName + ", " + ProductMatchQuery.noRu(f.regStatus());
                if (f != null && f.verdict() == FitVerdict.NOT_FIT) yield "✗ не подходит: " + (f.reasonShort() == null ? fitName : f.reasonShort());
                String similar = l.similar() == null || l.similar().isEmpty() ? ""
                        : " · похоже: " + l.similar().stream().limit(2).map(x -> ProductMatchQuery.shortName(x.name())
                                + (x.brand() == null || x.brand().isBlank() ? "" : " (" + x.brand() + ")")).collect(Collectors.joining(", "));
                yield "наш вид: " + l.word() + similar;
            }
        };
    }

    private static String lotNames(List<ProductMatchQuery.LotLine> lots) {
        List<String> names = lots.stream().map(l -> TelegramText.oneLine(l.lotName() == null ? "лот" : l.lotName(), 80)
                + (l.qty() == null || l.qty() <= 1 ? "" : ", " + l.qty() + " шт.")).distinct().toList();
        String head = String.join("; ", names.subList(0, Math.min(2, names.size())));
        return names.size() > 2 ? head + " и ещё " + (names.size() - 2) : head;
    }

    /** «12,5 млн ₸», «850 тыс. ₸», «950 ₸». */
    static String money(BigDecimal v) {
        BigDecimal abs = v.abs();
        if (abs.compareTo(MILLION) >= 0) return num(v.divide(MILLION, 1, RoundingMode.HALF_UP)) + " млн ₸";
        if (abs.compareTo(THOUSAND) >= 0) return num(v.divide(THOUSAND, 0, RoundingMode.HALF_UP)) + " тыс. ₸";
        return num(v.setScale(0, RoundingMode.HALF_UP)) + " ₸";
    }

    private static String num(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String join(String sep, String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        return b == null || b.isEmpty() ? a : a + sep + b;
    }

    private static String esc(String s) { return TelegramText.html(s); }

    static int visible(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").length();
    }
}
```
(`ProductMatchQuery.what`, `noRu` — пакетные статические: тот же пакет `productwatch`.)

`ProductDigestPublisher.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.ProductDigest;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.repository.ProductDigestRepository;
import com.vladoose.nir.service.productwatch.ProductDigestComposer;
import com.vladoose.nir.service.productwatch.ProductWatchNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Отправка сообщения-пачки с кнопками (спека 2026-10-21-tender-telegram-bot §4): пачка сохраняется до отправки — её id
 * в кнопках; не ушло — пачка удаляется, тендеры не отмечаются сообщёнными (повтор — следующим прогоном).
 */
@Component
public class ProductDigestPublisher implements ProductWatchNotifier.DigestPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductDigestPublisher.class);

    private final ProductDigestRepository digests;
    private final TelegramBotApi api;
    private final String publicUrl;

    public ProductDigestPublisher(ProductDigestRepository digests, TelegramBotApi api, @Value("${ais.public-url:}") String publicUrl) {
        this.digests = digests;
        this.api = api;
        this.publicUrl = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
    }

    @Override
    public boolean publish(ProductDigestComposer.Chunk chunk) {
        ProductDigest d = digests.save(ProductDigest.builder().tenderIds(new ArrayList<>(chunk.tenderIds())).build());
        try {
            long mid = api.sendMessage(chunk.html(), !chunk.sound(), keyboard(d.getId(), chunk.tenderIds(), publicUrl), null);
            d.setMessageId(mid);
            digests.save(d);
            return true;
        } catch (RuntimeException e) {
            log.warn("Telegram: сообщение-пачка ({} тендеров) не ушло: {}", chunk.tenderIds().size(), ErrorText.of(e));
            digests.deleteById(d.getId());
            return false;
        }
    }

    public static List<List<Button>> keyboard(long digestId, List<Long> tenderIds, String base) {
        List<List<Button>> kb = new ArrayList<>();
        kb.add(List.of(Button.cb("📋 Для ИИ", "d:" + digestId + ":ai"), Button.cb("✍️ Ответ ИИ", "d:" + digestId + ":ans")));
        if (base != null) {
            kb.add(List.of(Button.link("Открыть в АИС", base + "/tenders?ids="
                    + tenderIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "&market=KZ")));
        }
        return kb;
    }
}
```

`ProductWatchNotifier.java`:
- интерфейс в классе:
```java
    /** Сообщение-пачка с кнопками бота (спека 2026-10-21-tender-telegram-bot §4); false — не ушло, повтор следующим прогоном. */
    public interface DigestPublisher {
        boolean publish(ProductDigestComposer.Chunk chunk);
    }
```
- поле `private final DigestPublisher digest;` (null — прежний вид сообщения);
- `@Autowired`-конструктор: добавить параметры `DigestPublisher digestPublisher, @Value("${notify.telegram.bot-actions-enabled:false}") boolean botActions` (интерфейс — пакет `productwatch` не зависит от `tgbot`; реализация одна — `ProductDigestPublisher`) и передать `botActions ? digestPublisher : null` в полный конструктор;
- полный конструктор — новый последний параметр `DigestPublisher digest`; прежний 11-аргументный тестовый конструктор (`…, String publicUrl, Clock clock`) делегирует с `null`;
- `logPolicy`: при `digest != null` — `log.info("«Наш товар» в Telegram: сообщение-пачка с кнопками бота, {}, {}", min, topic)`;
- `prepare()` возвращает `Prepared(List<Card> cards, Map<Long, ProductMatchLevel> silent)` (`private record Prepared(...)`); в цикле до проверки суммы:
```java
            // «не наше» по ответу ИИ (спека 2026-10-21 §4): такие лоты не идут ни в сообщение, ни в сумму
            Set<Long> notOurs = t.getLots().stream().filter(l -> l.getAiVerdict() == AiVerdict.NO).map(TenderLot::getId)
                    .collect(Collectors.toSet());
            List<ProductMatchQuery.LotLine> lots = (upgrade || policy.mode() == ProductNotifyPolicy.Mode.STAR
                    ? s.lots().stream().filter(l -> l.level().alert()).toList() : s.lots())
                    .stream().filter(l -> !notOurs.contains(l.lotId())).toList();
            if (lots.isEmpty()) {
                if (!notOurs.isEmpty() && s.lots().stream().allMatch(l -> notOurs.contains(l.lotId()))) silent.put(t.getId(), s.level());
                continue;
            }
            BigDecimal ourSum = OurLots.sum(t, lots.stream().map(ProductMatchQuery.LotLine::lotId).toList());
            if (!policy.sumPasses(ourSum)) continue;
            out.add(new ProductWatchComposer.Card(t.getId(), t.getTenderNumber(), platformLabel(t.getPlatform()),
                    t.getPurchaseType(), t.getDescription(), t.getCustomerName(), t.getRegion(), t.getTotalCost(),
                    t.getDeadline(), t.getDeadlineAt(), s.level(), upgrade, lots, ourSum));
```
- `afterRun`: после `readTx.execute` — молчаливые отметки `prepared.silent().forEach((id, level) -> markQuietly(id, level))`; затем при `digest != null`:
```java
        if (digest != null) {
            Map<Long, ProductWatchComposer.Card> byId = cards.stream()
                    .collect(Collectors.toMap(ProductWatchComposer.Card::tenderId, c -> c, (a, b) -> a));
            int sent = 0;
            for (ProductDigestComposer.Chunk chunk : ProductDigestComposer.compose(cards, publicUrl)) {
                if (!digest.publish(chunk)) {
                    sum.addSideError("уведомление «наш товар» с кнопками не ушло (повтор — со следующим прогоном)");
                    break;
                }
                chunk.tenderIds().forEach(id -> markQuietly(id, byId.get(id).level()));
                sent += chunk.tenderIds().size();
            }
            log.info("Telegram: «наш товар» — сообщение-пачка с кнопками, тендеров {} из {}", sent, cards.size());
            return;
        }
```
где `markQuietly(id, level)` — прежний `try { matchService.markNotified(...) } catch (RuntimeException e) { log.warn(...) }`.

`application.yaml` — в `notify.telegram`:
```yaml
    # Кнопки бота (спека 2026-10-21-tender-telegram-bot): сообщение-пачка с кнопками и приём нажатий; пустым не оставлять
    bot-actions-enabled: ${TELEGRAM_BOT_ACTIONS_ENABLED:false}
```

- [ ] **Step 4: Зелёный** + прежние `com.vladoose.nir.productwatch.*` (сообщение «Наш товар» без кнопок не изменилось).

- [ ] **Step 5: Мутации** — (а) не отсекать «не наше» → краснеет `allLotsNotOurs…`; (б) отмечать сообщёнными до `publish` → краснеет `publishFails…`; (в) не экранировать заказчика → краснеет `lineFormat…`; (г) `MAX_TENDERS` 16 → краснеет `chunksOf15`.

- [ ] **Step 6: Коммит** — `feat(tg-bot): сообщение-пачка «Потенциально наших» с кнопками, «не наше» не приходит`.

---

### Task 10: Бот — приём обновлений, кто нажал, «📋 Для ИИ», «✍️ Ответ ИИ», разбор ответа

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tgbot/BotRunAs.java`, `BotPrompts.java`, `BotTexts.java`, `BotLetterStore.java`, `BotBriefSender.java`, `BotAnswerHandler.java`, `BotUpdateRouter.java`, `TelegramBotPoller.java`
- Test: `src/test/java/com/vladoose/nir/tgbot/BotTextsTest.java`, `TelegramBotPollerTest.java`, `BotUpdateRouterTest.java`

**Interfaces:**
- Consumes: `TelegramBotApi`, `BotUpdate` (задача 8); `AiAnswerService`, `AiAnswerResult` (задача 6); `ProductAiBrief.forTenders` (задача 4); `ProductDigestRepository`, `BotLetterRepository`, `UserAccountRepository.findByTelegramUserId` (задача 1); `CurrentUser.setVia` (задача 2).
- Produces: `BotRunAs.run(UserAccount, Runnable)`; `BotPrompts.add(long)`, `contains(Long)`; `BotTexts.NO_ACCESS`, `ASK_BLOCK`, `NO_BLOCK`, `BRIEF_CAPTION`, `unknownUser(long)`, `tenders(int)`, `summary(AiAnswerResult, List<BotLetter>)`, `listKeyboard(List<BotLetter>)`; `BotLetterStore.createDrafts(List<LetterPlan>, String by): List<BotLetter>`, `attachList(List<Long> ids, long listMessageId)`, `find(long): Optional<BotLetter>`, `list(Long listMessageId): List<BotLetter>`; `BotBriefSender.sendBrief(BotUpdate.Callback, long digestId)`, `askAnswer(BotUpdate.Callback, long digestId)`; `BotAnswerHandler.handle(BotUpdate.Incoming)`; `BotUpdateRouter.handle(BotUpdate)`; `TelegramBotPoller.start()`, `stop()`, `skipPending(): long`, `pollOnce(long offset): long`.

- [ ] **Step 1: Тесты**

`BotTextsTest.java`:
```java
package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.AiVerdict;
import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.BotLetterStatus;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LetterPlan;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LotResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.Outcome;
import com.vladoose.nir.service.tgbot.BotTexts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Тексты бота (спека 2026-10-21-tender-telegram-bot §6–8). */
class BotTextsTest {

    static BotLetter letter(long id, int n, BotLetterStatus st) {
        return BotLetter.builder().id(id).tenderId(1L).distributorId(2L).lineNo(n).status(st).build();
    }

    @Test
    void unknownUserNamesIdAndWhereToPutIt() {
        assertThat(BotTexts.unknownUser(111)).contains("111").contains("«Пользователи»");
    }

    @Test
    void tendersPlural() {
        assertThat(List.of(1, 3, 5, 11, 21).stream().map(BotTexts::tenders))
                .containsExactly("1 тендер", "3 тендера", "5 тендеров", "11 тендеров", "21 тендер");
    }

    @Test
    void summaryEscapesAndListsLetters() {
        AiAnswerResult r = new AiAnswerResult(
                List.of(new LotResult(5, 1L, "177-1", "Лот <1>", "ГБ", AiVerdict.GO, Outcome.PINNED, "ДКИ",
                        "✅ «Лот <1>» · ГБ → ДКИ — закреплено")),
                List.of("L9 | может быть"),
                List.of(new LetterPlan(1, "177-1", "ГБ", 2, "ZZ Завод", true, List.of(5L), List.of("Дефибриллятор"))),
                List.of("«Облучатель» — у ZZ уже запрошено 20.10"));
        assertThat(BotTexts.summary(r)).contains("лотов: 1").contains("«Лот &lt;1&gt;»").contains("Не понял строк: 1")
                .contains("• «Облучатель» — у ZZ уже запрошено 20.10")
                .contains("1. ZZ Завод (завод) — Дефибриллятор · ГБ");
        assertThat(BotTexts.summary(new AiAnswerResult(List.of(), List.of(), List.of(), List.of())))
                .contains("Писем к отправке нет");
    }

    @Test
    void listKeyboardByStatus_fourPerRow() {
        List<List<Button>> kb = BotTexts.listKeyboard(List.of(letter(10, 1, BotLetterStatus.DRAFT),
                letter(11, 2, BotLetterStatus.SENT), letter(12, 3, BotLetterStatus.ELSEWHERE),
                letter(13, 4, BotLetterStatus.CANCELLED), letter(14, 5, BotLetterStatus.FAILED)));
        assertThat(kb).hasSize(2);
        assertThat(kb.get(0)).extracting(Button::text, Button::data).containsExactly(tuple("👁 1", "l:10:v"),
                tuple("✅ 2", "l:11:x"), tuple("✅ 3 · АИС", "l:12:x"), tuple("✕ 4", "l:13:x"));
        assertThat(kb.get(1)).extracting(Button::data).containsExactly("l:14:v");   // не ушло — посмотреть и отправить ещё раз
    }
}
```

`TelegramBotPollerTest.java`:
```java
package com.vladoose.nir.tgbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.telegram.*;
import com.vladoose.nir.service.tgbot.TelegramBotPoller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Приём нажатий (спека 2026-10-21-tender-telegram-bot §8): накопившееся при старте — мимо, сбой одного — не стоп, 409 — стоп. */
class TelegramBotPollerTest {

    TelegramStubServer stub;
    final List<BotUpdate> handled = new ArrayList<>();
    final List<Long> sleeps = new ArrayList<>();

    @BeforeEach void up() throws Exception { stub = TelegramStubServer.start(0); }
    @AfterEach void down() { stub.close(); }

    TelegramBotPoller poller() {
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        return new TelegramBotPoller(api, u -> {
            if ("boom".equals(u.callback() == null ? null : u.callback().data())) throw new IllegalStateException("x");
            handled.add(u);
        }, true, ms -> { sleeps.add(ms); throw new InterruptedException(); });
    }

    static String cb(long id, String data) {
        return "{\"update_id\":" + id + ",\"callback_query\":{\"id\":\"c" + id + "\",\"from\":{\"id\":1},"
                + "\"message\":{\"message_id\":5,\"chat\":{\"id\":-1001}},\"data\":\"" + data + "\"}}";
    }

    @Test
    void skipPending_movesOffsetPastLast() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[" + cb(41, "d:1:ai") + "]"), TelegramStubServer.Reply.json("[]"));
        assertThat(poller().skipPending()).isEqualTo(42);
        assertThat(stub.requests().get(1).body()).contains("\"offset\":42");
        assertThat(handled).isEmpty();   // нажатие до старта не выполняется
    }

    @Test
    void pollOnce_failureOfOneDoesNotStopOthers() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[" + cb(5, "boom") + "," + cb(6, "d:1:ai") + "]"));
        assertThat(poller().pollOnce(5)).isEqualTo(7);
        assertThat(handled).extracting(BotUpdate::updateId).containsExactly(6L);
    }

    @Test
    void conflict409_stopsWithoutSleep_401_sleeps10min() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[]"), TelegramStubServer.Reply.error(409, "Conflict"));
        TelegramBotPoller p = poller();
        p.loop();
        assertThat(sleeps).isEmpty();
        stub.on("getUpdates", TelegramStubServer.Reply.json("[]"), TelegramStubServer.Reply.error(401, "Unauthorized"));
        poller().loop();
        assertThat(sleeps).containsExactly(600_000L);
    }

    @Test
    void tooMany_sleepsRetryAfter() {
        stub.on("getUpdates", TelegramStubServer.Reply.json("[]"), TelegramStubServer.Reply.tooMany(7));
        poller().loop();
        assertThat(sleeps).containsExactly(7_000L);
    }
}
```

`BotUpdateRouterTest.java` (`@SpringBootTest @Transactional` + `TelegramStubServer`; бот и его сборщики собираются руками со своим `TelegramBotApi`):
```java
@SpringBootTest
@Transactional
class BotUpdateRouterTest {
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired ProductGroupRepository groups;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired DistributorRepository distributors;
    @Autowired UserAccountRepository users;
    @Autowired ProductDigestRepository digests;
    @Autowired BotLetterRepository letters;
    @Autowired ProductAiBrief brief;
    @Autowired AiAnswerService aiAnswers;
    @Autowired BotRunAs runAs;
    @Autowired TenderLotRepository lots;
    TelegramStubServer stub;
    BotUpdateRouter router;
    BotPrompts prompts;
    TenderLot lot;
    SiteProduct dki;
    ProductDigest digest;

    @BeforeEach
    void setUp() throws Exception {
        stub = TelegramStubServer.start(0);
        stub.on("getMe", TelegramStubServer.Reply.json("{\"id\":777,\"is_bot\":true}"));
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        prompts = new BotPrompts(digests);
        BotLetterStore store = new BotLetterStore(letters, lots);
        router = new BotUpdateRouter(api, users, runAs, new BotBriefSender(api, digests, brief, prompts),
                new BotAnswerHandler(aiAnswers, store, api, prompts));
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZАксион");
        TgFixtures.match(matches, lot, TgFixtures.group(groups).getId(), ProductMatchLevel.TYPE, null, List.of(dki.getId()));
        TgFixtures.distributor(distributors, "ZZ Аксион завод", "zz-axion@example.kz", true, false, "ZZАксион");
        TgFixtures.user(users, "zzbot", "ROLE_ADMIN", 111L);
        TgFixtures.user(users, "zzop", "ROLE_USER", 222L);
        digest = digests.saveAndFlush(ProductDigest.builder().messageId(500L).tenderIds(new ArrayList<>(List.of(lot.getTender().getId()))).build());
    }

    @AfterEach void down() { stub.close(); MarketContext.clear(); }

    BotUpdate press(long from, String data) { return new BotUpdate(1, new BotUpdate.Callback("c1", from, -1001, 500, data), null); }
    BotUpdate reply(long from, long to, String text) { return new BotUpdate(2, null, new BotUpdate.Incoming(600, -1001, from, text, to, 777L)); }
    List<String> paths() { return stub.requests().stream().map(r -> r.path().substring(r.path().lastIndexOf('/') + 1)).toList(); }

    @Test
    void unknownUser_onlyAlertWithId() {
        router.handle(press(999, "d:" + digest.getId() + ":ai"));
        assertThat(paths()).containsExactly("answerCallbackQuery");
        assertThat(stub.requests().get(0).body()).contains("999").contains("\"show_alert\":true");
    }

    @Test
    void operatorRole_noAccess_otherChat_ignored() {
        router.handle(press(222, "d:" + digest.getId() + ":ai"));
        assertThat(stub.requests().get(0).body()).contains("Нет доступа");
        router.handle(new BotUpdate(3, new BotUpdate.Callback("c2", 111, -2002, 500, "d:1:ai"), null));
        assertThat(stub.requests()).hasSize(1);
    }

    @Test
    void briefButton_sendsFileWithCodes() {
        router.handle(press(111, "d:" + digest.getId() + ":ai"));
        MarketContext.set(Market.KZ);
        TelegramStubServer.Request doc = stub.requests().stream().filter(r -> r.path().endsWith("/sendDocument")).findFirst().orElseThrow();
        assertThat(doc.body()).contains("filename=\"Для ИИ — 1 тендер").contains("[L" + lot.getId() + "]").contains("Для АИС");
    }

    @Test
    void answerReply_pinsModel_summaryWithLetterButton_draftSaved() {
        router.handle(press(111, "d:" + digest.getId() + ":ans"));
        router.handle(reply(111, 500, "L" + lot.getId() + " | идти | P" + dki.getId() + " | ТЗ совпадает"));
        MarketContext.set(Market.KZ);
        TelegramStubServer.Request summary = stub.requests().get(stub.requests().size() - 1);
        assertThat(summary.path()).endsWith("/sendMessage");
        assertThat(summary.body()).contains("закреплено").contains("Письма к отправке").contains(":v\"");
        assertThat(lots.findById(lot.getId()).orElseThrow().isModelByAi()).isTrue();
        assertThat(letters.findAll()).filteredOn(b -> b.getTenderId().equals(lot.getTender().getId()))
                .singleElement().satisfies(b -> assertThat(b.getListMessageId()).isNotNull());
    }

    @Test
    void replyWithoutBlock_onlyToPrompt_says_notFound() {
        router.handle(reply(111, 500, "спасибо"));            // ответ на сообщение-пачку — подсказка
        assertThat(stub.requests().get(stub.requests().size() - 1).body()).contains("не найден");
        int before = stub.requests().size();
        router.handle(reply(111, 4242, "спасибо"));           // ответ на чужое сообщение бота — молча
        assertThat(stub.requests()).hasSize(before);
    }
}
```

- [ ] **Step 2: Красный** (`--tests 'com.vladoose.nir.tgbot.BotTextsTest' --tests '…TelegramBotPollerTest' --tests '…BotUpdateRouterTest'`, `t10.log`).

- [ ] **Step 3: Реализация**

`BotRunAs.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.util.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Действие кнопки бота от имени учётки АИС (спека 2026-10-21-tender-telegram-bot §8): рынок KZ, вход пользователя, пометка
 * «Telegram» и привязанная к потоку сессия JPA без транзакции — как OSIV у запросов с экрана: прежние сервисы (письмо,
 * бланк, ленивые связи, фильтр рынка) работают так же, а авторы пишутся «vlad, Telegram».
 */
@Component
public class BotRunAs {

    private final EntityManagerFactory emf;

    public BotRunAs(EntityManagerFactory emf) { this.emf = emf; }

    public void run(UserAccount user, Runnable work) {
        MarketContext.set(Market.KZ);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(), null,
                List.of(new SimpleGrantedAuthority(user.getRole()))));
        CurrentUser.setVia("Telegram");
        boolean bound = TransactionSynchronizationManager.hasResource(emf);   // в тестах сессия уже есть
        EntityManager em = null;
        if (!bound) {
            em = emf.createEntityManager();
            TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        }
        try {
            work.run();
        } finally {
            if (!bound) {
                TransactionSynchronizationManager.unbindResource(emf);
                EntityManagerFactoryUtils.closeEntityManager(em);
            }
            CurrentUser.setVia(null);
            SecurityContextHolder.clearContext();
            MarketContext.clear();
        }
    }
}
```

`BotPrompts.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.repository.ProductDigestRepository;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Сообщения бота, на которые ждём блок «Для АИС» (спека §8): просьбы «✍️ Ответ ИИ», файлы «Для ИИ», списки писем — в памяти
 * (до 500 последних; после перезапуска — только сообщения-пачки из БД). Ответ без блока на них — подсказка, на прочие — молча.
 */
@Component
public class BotPrompts {

    private static final int MAX = 500;
    private final ProductDigestRepository digests;
    private final Set<Long> ids = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > MAX; }
    }));

    public BotPrompts(ProductDigestRepository digests) { this.digests = digests; }

    public void add(long messageId) { ids.add(messageId); }

    public boolean contains(Long messageId) {
        return messageId != null && (ids.contains(messageId) || digests.existsByMessageId(messageId));
    }
}
```

`BotTexts.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.service.aianswer.AiAnswerResult;

import java.util.ArrayList;
import java.util.List;

/** Тексты бота (спека 2026-10-21-tender-telegram-bot §6–8), чистые функции; всё чужое — экранировано (HTML). */
public final class BotTexts {

    public static final String NO_ACCESS = "Нет доступа: нужна роль администратора в АИС";
    public static final String ASK_BLOCK = "Ответьте на это сообщение блоком «Для АИС» из конца ответа ИИ — "
            + "у блока кода есть кнопка «Копировать».";
    public static final String NO_BLOCK = "Блок «Для АИС» не найден — скопируйте блок кода из конца ответа ИИ и пришлите его ответом.";
    public static final String BRIEF_CAPTION = "Вставьте в Claude или Gemini. В конце ответа ИИ напишет блок «Для АИС» — "
            + "пришлите его мне ответом (кнопка «✍️ Ответ ИИ»).";
    private static final int MAX_VISIBLE = 3800;

    private BotTexts() {}

    public static String unknownUser(long telegramId) {
        return "Ваш Telegram ID " + telegramId + " — впишите его в «Пользователи» АИС (учётка администратора)";
    }

    /** «1 тендер», «3 тендера», «5 тендеров». */
    public static String tenders(int n) {
        int d = n % 10, h = n % 100;
        return n + " " + (d == 1 && h != 11 ? "тендер" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "тендера" : "тендеров");
    }

    public static String summary(AiAnswerResult r) {
        List<String> lines = new ArrayList<>();
        lines.add("🤖 Разобрал ответ ИИ — лотов: " + r.lots().size());
        r.lots().forEach(l -> lines.add(l.text()));
        if (!r.unparsed().isEmpty()) lines.add("⚠ Не понял строк: " + r.unparsed().size() + " — «" + r.unparsed().get(0) + "»");
        r.notes().forEach(n -> lines.add("• " + n));
        lines.add("");
        if (r.letters().isEmpty()) {
            lines.add("📨 Писем к отправке нет");
        } else {
            lines.add("📨 Письма к отправке:");
            for (int i = 0; i < r.letters().size(); i++) {
                AiAnswerResult.LetterPlan p = r.letters().get(i);
                lines.add((i + 1) + ". " + p.distributorName() + " (" + (p.manufacturer() ? "завод" : "доверенный") + ") — "
                        + String.join(", ", p.lotNames()) + (p.customer() == null ? "" : " · " + TelegramText.oneLine(p.customer(), 60)));
            }
        }
        StringBuilder out = new StringBuilder();
        int visible = 0;
        for (String line : lines) {
            if (visible + line.length() + 1 > MAX_VISIBLE) { out.append("\n…"); break; }
            if (!out.isEmpty()) out.append('\n');
            out.append(TelegramText.html(line));
            visible += line.length() + 1;
        }
        return out.toString();
    }

    /** Кнопки списка писем: «👁 N» — посмотреть; отправленные и закрытые — без действия. По 4 в ряд. */
    public static List<List<Button>> listKeyboard(List<BotLetter> drafts) {
        List<List<Button>> rows = new ArrayList<>();
        List<Button> row = new ArrayList<>();
        for (BotLetter b : drafts) {
            String n = String.valueOf(b.getLineNo());
            Button btn = switch (b.getStatus()) {
                case DRAFT, FAILED -> Button.cb("👁 " + n, "l:" + b.getId() + ":v");
                case SENDING -> Button.cb("⏳ " + n, "l:" + b.getId() + ":x");
                case SENT -> Button.cb("✅ " + n, "l:" + b.getId() + ":x");
                case ELSEWHERE -> Button.cb("✅ " + n + " · АИС", "l:" + b.getId() + ":x");
                case CANCELLED -> Button.cb("✕ " + n, "l:" + b.getId() + ":x");
            };
            row.add(btn);
            if (row.size() == 4) { rows.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }
}
```

`BotLetterStore.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.BotLetterItem;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.BotLetterRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LetterPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Черновики писем из бота (спека 2026-10-21-tender-telegram-bot §7): запись — короткими транзакциями, сеть — снаружи. */
@Service
public class BotLetterStore {

    private final BotLetterRepository repo;
    private final TenderLotRepository lots;

    public BotLetterStore(BotLetterRepository repo, TenderLotRepository lots) {
        this.repo = repo;
        this.lots = lots;
    }

    /** По письму на план; позиции — модель в работе лота сейчас (её сверит «Отправить»). */
    @Transactional
    public List<BotLetter> createDrafts(List<LetterPlan> plans, String by) {
        List<BotLetter> out = new ArrayList<>();
        int n = 1;
        for (LetterPlan p : plans) {
            List<BotLetterItem> items = new ArrayList<>();
            for (Long lotId : p.lotIds()) {
                TenderLot l = lots.findById(lotId).orElseThrow();
                items.add(new BotLetterItem(l.getId(), l.getProposedEquipment() == null ? null : l.getProposedEquipment().getId(),
                        l.getQuantity()));
            }
            out.add(repo.save(BotLetter.builder().tenderId(p.tenderId()).distributorId(p.distributorId()).items(items)
                    .lineNo(n++).createdBy(by).build()));
        }
        return out;
    }

    @Transactional
    public void attachList(List<Long> ids, long listMessageId) {
        repo.findAllById(ids).forEach(b -> b.setListMessageId(listMessageId));
    }

    public Optional<BotLetter> find(long id) { return repo.findById(id); }

    public List<BotLetter> list(Long listMessageId) {
        return listMessageId == null ? List.of() : repo.findByListMessageIdOrderByLineNo(listMessageId);
    }
}
```

`BotBriefSender.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductDigest;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.repository.ProductDigestRepository;
import com.vladoose.nir.service.productwatch.ProductAiBrief;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** «📋 Для ИИ» и «✍️ Ответ ИИ» у сообщения-пачки (спека 2026-10-21-tender-telegram-bot §5, §3 п. 2–4). */
@Component
public class BotBriefSender {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final TelegramBotApi api;
    private final ProductDigestRepository digests;
    private final ProductAiBrief brief;
    private final BotPrompts prompts;

    public BotBriefSender(TelegramBotApi api, ProductDigestRepository digests, ProductAiBrief brief, BotPrompts prompts) {
        this.api = api;
        this.digests = digests;
        this.brief = brief;
        this.prompts = prompts;
    }

    public void sendBrief(BotUpdate.Callback c, long digestId) {
        ProductDigest d = digests.findById(digestId).orElse(null);
        if (d == null) {
            api.answerCallback(c.id(), "Пачка не найдена — откройте тендеры в АИС", true);
            return;
        }
        api.answerCallback(c.id(), "Собираю текст для ИИ…", false);
        AiBriefResponse r = brief.forTenders(d.getTenderIds());
        String name = "Для ИИ — " + BotTexts.tenders(r.tenders()) + ", " + LocalDateTime.now(Market.KZ.zone()).format(STAMP) + ".txt";
        long mid = api.sendDocument(name, r.text().getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8",
                BotTexts.BRIEF_CAPTION, List.of(List.of(Button.cb("✍️ Ответ ИИ", "d:" + digestId + ":ans"))), c.messageId());
        prompts.add(mid);
    }

    public void askAnswer(BotUpdate.Callback c, long digestId) {
        api.answerCallback(c.id(), null, false);
        prompts.add(api.sendForceReply(BotTexts.ASK_BLOCK, c.messageId(), "Вставьте блок «Для АИС»"));
    }
}
```

`BotAnswerHandler.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerService;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.List;

/** Ответ боту блоком «Для АИС» (спека 2026-10-21-tender-telegram-bot §6–7): разбор, черновики писем, одно сообщение-итог. */
@Component
public class BotAnswerHandler {

    private final AiAnswerService answers;
    private final BotLetterStore store;
    private final TelegramBotApi api;
    private final BotPrompts prompts;

    public BotAnswerHandler(AiAnswerService answers, BotLetterStore store, TelegramBotApi api, BotPrompts prompts) {
        this.answers = answers;
        this.store = store;
        this.api = api;
        this.prompts = prompts;
    }

    public void handle(BotUpdate.Incoming m) {
        String text = m.text() == null ? "" : m.text();
        AiAnswerResult r = text.isBlank() ? null : answers.apply(text);
        if (r == null || r.isEmpty()) {
            if (prompts.contains(m.replyToMessageId())) api.sendMessage(BotTexts.NO_BLOCK, true, null, m.messageId());
            return;
        }
        List<BotLetter> drafts = store.createDrafts(r.letters(), CurrentUser.label());
        long listId = api.sendMessage(BotTexts.summary(r), true, BotTexts.listKeyboard(drafts), m.messageId());
        store.attachList(drafts.stream().map(BotLetter::getId).toList(), listId);
    }
}
```

`BotUpdateRouter.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Нажатия и ответы боту (спека 2026-10-21-tender-telegram-bot §8): только группа TELEGRAM_CHAT_ID, только администратор АИС
 * с этим Telegram ID; действие — от его имени (BotRunAs). Сбой одного действия — свой текст в ответ, цикл живёт.
 */
@Component
public class BotUpdateRouter {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateRouter.class);

    private final TelegramBotApi api;
    private final UserAccountRepository users;
    private final BotRunAs runAs;
    private final BotBriefSender briefs;
    private final BotAnswerHandler answers;

    public BotUpdateRouter(TelegramBotApi api, UserAccountRepository users, BotRunAs runAs, BotBriefSender briefs,
                           BotAnswerHandler answers) {
        this.api = api;
        this.users = users;
        this.runAs = runAs;
        this.briefs = briefs;
        this.answers = answers;
    }

    public void handle(BotUpdate u) {
        if (u.callback() != null) onCallback(u.callback());
        else if (u.message() != null) onMessage(u.message());
    }

    private void onCallback(BotUpdate.Callback c) {
        if (c.chatId() != api.chatId()) return;
        UserAccount user = allowed(c.fromId(), text -> api.answerCallback(c.id(), text, true));
        if (user == null) return;
        String[] p = c.data() == null ? new String[0] : c.data().split(":");
        long id;
        try {
            id = p.length == 3 ? Long.parseLong(p[1]) : -1;
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 0) {
            api.answerCallback(c.id(), "Кнопка устарела", false);
            return;
        }
        long target = id;
        run(user, c.messageId(), () -> {
            switch (p[0] + ":" + p[2]) {
                case "d:ai" -> briefs.sendBrief(c, target);
                case "d:ans" -> briefs.askAnswer(c, target);
                default -> api.answerCallback(c.id(), "Кнопка устарела", false);
            }
        });
    }

    private void onMessage(BotUpdate.Incoming m) {
        if (m.chatId() != api.chatId() || m.replyToFromId() == null || m.replyToFromId() != api.botId()) return;
        UserAccount user = allowed(m.fromId(), text -> api.sendMessage(TelegramText.html(text), true, null, m.messageId()));
        if (user == null) return;
        run(user, m.messageId(), () -> answers.handle(m));
    }

    /** Учётка АИС с этим Telegram ID и ролью администратора; нет — объяснение нажавшему и null. */
    private UserAccount allowed(long telegramId, Consumer<String> explain) {
        UserAccount u = users.findByTelegramUserId(telegramId).orElse(null);
        if (u == null) {
            explain.accept(BotTexts.unknownUser(telegramId));
            return null;
        }
        if (!"ROLE_ADMIN".equals(u.getRole())) {
            explain.accept(BotTexts.NO_ACCESS);
            return null;
        }
        return u;
    }

    private void run(UserAccount user, long replyTo, Runnable work) {
        try {
            runAs.run(user, work);
        } catch (BadRequestException | NotFoundException e) {
            api.sendMessage(TelegramText.html("Не получилось: " + e.getMessage() + " — повторите или откройте в АИС"), true, null, replyTo);
        } catch (RuntimeException e) {
            log.warn("Кнопки бота: действие не выполнено: {}", e.getClass().getSimpleName());
            api.sendMessage(TelegramText.html("Не получилось (" + e.getClass().getSimpleName() + ") — повторите или откройте в АИС"),
                    true, null, replyTo);
        }
    }
}
```

`TelegramBotPoller.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramException;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Приём нажатий бота (спека 2026-10-21-tender-telegram-bot §8): свой поток telegram-bot, long-poll getUpdates. При старте
 * накопившееся пропускается — нажатие часовой давности письмо не отправит. 409 (вебхук / другой читатель) — стоп с ERROR;
 * 401 / 404 — пауза 10 мин; 429 — retry_after; прочее — 5 с … 1 мин. Выключено по умолчанию (TELEGRAM_BOT_ACTIONS_ENABLED).
 */
@Component
public class TelegramBotPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPoller.class);

    /** Пауза; InterruptedException — остановка цикла. */
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final TelegramBotApi api;
    private final Consumer<BotUpdate> handler;
    private final boolean enabled;
    private final Sleeper sleeper;
    private volatile boolean running;
    private Thread thread;

    @Autowired
    public TelegramBotPoller(TelegramBotApi api, BotUpdateRouter router, TelegramSettings telegram,
                             @Value("${notify.telegram.bot-actions-enabled:false}") boolean botActions,
                             @Value("${product-watch.enabled:false}") boolean productWatch) {
        this(api, router::handle, botActions && productWatch && telegram.isConfigured(), Thread::sleep);
    }

    public TelegramBotPoller(TelegramBotApi api, Consumer<BotUpdate> handler, boolean enabled, Sleeper sleeper) {
        this.api = api;
        this.handler = handler;
        this.enabled = enabled;
        this.sleeper = sleeper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || running) return;
        running = true;
        thread = new Thread(this::loop, "telegram-bot");
        thread.setDaemon(true);
        thread.start();
        log.info("Кнопки бота Telegram: приём нажатий включён");
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public void loop() {
        running = true;
        long offset = -1;
        long backoff = 0;
        while (running) {
            try {
                if (offset < 0) offset = skipPending();
                offset = pollOnce(offset);
                backoff = 0;
            } catch (TelegramException e) {
                if (e.status() == 409) {
                    log.error("Кнопки бота Telegram не работают: у бота установлен вебхук или его обновления читает другая программа");
                    running = false;
                    return;
                }
                long wait = e.status() == 401 || e.status() == 404 ? 600_000
                        : e.retryAfterSeconds() != null ? e.retryAfterSeconds() * 1000L
                        : (backoff = backoff == 0 ? 5_000 : Math.min(backoff * 2, 60_000));
                log.warn("Кнопки бота Telegram: {} — повтор через {} с", e.getMessage(), wait / 1000);
                if (!pause(wait)) return;
            } catch (RuntimeException e) {
                backoff = backoff == 0 ? 5_000 : Math.min(backoff * 2, 60_000);
                log.warn("Кнопки бота Telegram: сбой приёма ({}) — повтор через {} с", e.getClass().getSimpleName(), backoff / 1000);
                if (!pause(backoff)) return;
            }
        }
    }

    /** Накопившиеся без АИС обновления не выполняются (спека §8): offset — за последним; подтверждение — вторым запросом. */
    public long skipPending() {
        List<BotUpdate> last = api.getUpdates(-1, 0);
        long offset = last.isEmpty() ? 0 : last.get(last.size() - 1).updateId() + 1;
        if (offset > 0) api.getUpdates(offset, 0);
        return offset;
    }

    public long pollOnce(long offset) {
        long next = offset;
        for (BotUpdate u : api.getUpdates(offset, TelegramBotApi.POLL_SECONDS)) {
            next = Math.max(next, u.updateId() + 1);
            try {
                handler.accept(u);
            } catch (RuntimeException e) {
                log.warn("Кнопки бота Telegram: обновление {} не обработано: {}", u.updateId(), e.getClass().getSimpleName());
            }
        }
        return next;
    }

    private boolean pause(long ms) {
        try {
            sleeper.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
            return false;
        }
    }
}
```

- [ ] **Step 4: Зелёный** (три класса + `com.vladoose.nir.tgbot.*`).

- [ ] **Step 5: Мутации** — (а) в `allowed` не проверять роль → краснеет `operatorRole…`; (б) не сверять чат → краснеет «другой чат»; (в) `skipPending` вернуть 0 → краснеет `skipPending…`; (г) в `pollOnce` без `try` → краснеет «сбой одного»; (д) в `onMessage` не сверять `replyToFromId` с ботом → добавленный случай «ответ на сообщение человека» должен краснеть (дописать в тест, если выжила).

- [ ] **Step 6: Коммит** — `feat(tg-bot): приём нажатий, кто нажал, «📋 Для ИИ» файлом, «✍️ Ответ ИИ» и разбор ответа`.

---

### Task 11: Письма в боте — просмотр, отправка, возврат

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tgbot/BotLetterActions.java`
- Modify: `src/main/java/com/vladoose/nir/service/tgbot/BotLetterStore.java` (+`claim`, `finish`, `setPreview`, `updateItems`), `BotUpdateRouter.java` (маршруты `l:*`, конструктор), `BotTexts.java` (+`caption`, `sent`, `failed`)
- Modify: `src/main/java/com/vladoose/nir/service/RfqDocumentService.java` (+`previewFiles`)
- Modify (test): `BotUpdateRouterTest.java` (новый параметр конструктора)
- Test: `src/test/java/com/vladoose/nir/tgbot/BotLetterActionsTest.java`, `BotReplyByTest.java`

**Interfaces:**
- Consumes: всё задачи 10; `PriceRequestSendService.draft / preview / send / resend`; `RfqDocumentService`; `CompanyProfileService.forMarket(...).getStampPng()`; `PriceRequestRepository.lastCreatedAt` (задача 1).
- Produces: `BotLetterActions.view(BotUpdate.Callback, long letterId)`, `back(…)`, `send(…)`; `static LocalDate replyBy(LocalDate deadline, LocalDate today)`; `BotLetterStore.claim(long): boolean`, `finish(long id, BotLetterStatus st, Long prId, String by, String error)`, `setPreview(long id, Long messageId)`, `updateItems(long id, List<BotLetterItem> items)`; `RfqDocumentService.PreviewFiles(byte[] pdf, String pdfName, List<byte[]> pages)`, `previewFiles(PriceRequest): PreviewFiles`.

- [ ] **Step 1: Тесты**

`BotReplyByTest.java`:
```java
class BotReplyByTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @Test
    void twoDaysBefore_notBeforeTomorrow_notAfterDeadline_noDeadline3Days() {
        assertThat(BotLetterActions.replyBy(LocalDate.of(2026, 10, 31), TODAY)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(BotLetterActions.replyBy(LocalDate.of(2026, 10, 22), TODAY)).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(BotLetterActions.replyBy(TODAY, TODAY)).isEqualTo(TODAY);
        assertThat(BotLetterActions.replyBy(null, TODAY)).isEqualTo(LocalDate.of(2026, 10, 24));
    }
}
```

`BotLetterActionsTest.java` (`@SmtpIntegrationTest` + `GreenMailExtension(ServerSetupTest.SMTP)` пользователь `zakup-test@westmed.local` / `secret` — как `PriceRequestSendServiceTest`; заглушка Bot API — своя):
```java
@SmtpIntegrationTest
class BotLetterActionsTest {
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("zakup-test@westmed.local", "secret"));

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired SiteProductRepository products;
    @Autowired DistributorRepository distributors;
    @Autowired UserAccountRepository users;
    @Autowired BotLetterRepository letterRepo;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestWriter writer;
    @Autowired LotModelService models;
    @Autowired MedEquipmentRepository equipment;
    @Autowired BotRunAs runAs;
    @Autowired PriceRequestSendService sendService;
    @Autowired RfqDocumentService documents;
    @Autowired DistributorService distributorService;
    @Autowired TenderService tenderService;
    @Autowired CompanyProfileService profiles;
    TelegramStubServer stub;
    BotLetterActions actions;
    BotLetterStore store;
    UserAccount admin;
    TenderLot lot;
    Distributor maker;
    BotLetter draft;

    @BeforeEach
    void setUp() throws Exception {
        stub = TelegramStubServer.start(0);
        TelegramBotApi api = new TelegramBotApi(new TelegramSettings(true, stub.url(), "1:T", "-1001", "77", "55", "95"), new ObjectMapper());
        store = new BotLetterStore(letterRepo, lots);
        actions = new BotLetterActions(api, store, sendService, documents, distributorService, tenderService, lots, priceRequests,
                profiles, "https://ais.westmed.kz");
        MarketContext.set(Market.KZ);
        admin = TgFixtures.user(users, "zzbot", "ROLE_ADMIN", 111L);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        SiteProduct dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZАксион");
        maker = TgFixtures.distributor(distributors, "ZZ Аксион завод", "zz-axion@example.kz", true, false, "ZZАксион");
        models.applyByAi(lot, models.equipmentForSiteProduct(dki), "ИИ: подходит");
        draft = store.createDrafts(List.of(new AiAnswerResult.LetterPlan(lot.getTender().getId(), lot.getTender().getTenderNumber(),
                "ZZ Горбольница №7", maker.getId(), maker.getName(), true, List.of(lot.getId()), List.of("ZZ Дефибриллятор"))), "zzbot").get(0);
        store.attachList(List.of(draft.getId()), 700);
    }

    @AfterEach void down() { stub.close(); MarketContext.clear(); }

    BotUpdate.Callback cb(String data) { return new BotUpdate.Callback("c", 111, -1001, 700, data); }
    void as(Runnable r) { runAs.run(admin, r); MarketContext.set(Market.KZ); }
    BotLetter reload() { return letterRepo.findById(draft.getId()).orElseThrow(); }

    /** Текст письма и Word во вложении — как в PrivateRequestRfqStage2Test. */
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
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            try (XWPFDocument d = new XWPFDocument(p.getInputStream()); XWPFWordExtractor x = new XWPFWordExtractor(d)) {
                return x.getText().replace('\u00A0', ' ');
            }
        }
        if (p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String t = document(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    @Test
    void view_sendsPhotoWithCaptionAndButtons() {
        as(() -> actions.view(cb("l:" + draft.getId() + ":v"), draft.getId()));
        TelegramStubServer.Request photo = stub.requests().stream().filter(r -> r.path().endsWith("/sendPhoto")).findFirst().orElseThrow();
        assertThat(photo.body()).contains("Письмо 1 из 1").contains("Кому: zz-axion@example.kz")
                .contains("l:" + draft.getId() + ":s").contains("l:" + draft.getId() + ":b").contains("kp=" + maker.getId());
        assertThat(reload().getPreviewMessageId()).isNotNull();
    }

    @Test
    void send_deliversLetterWithClientLineAndDocx_thenSecondPressDoesNothing() throws Exception {
        as(() -> actions.view(cb("v"), draft.getId()));
        as(() -> actions.send(cb("s"), draft.getId()));
        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(1);
        assertThat(got[0].getAllRecipients()[0].toString()).isEqualTo("zz-axion@example.kz");
        assertThat(body(got[0])).contains("Конечный заказчик: ZZ Горбольница №7")
                .doesNotContain(lot.getTender().getTenderNumber());   // тендер не раскрываем
        assertThat(document(got[0])).contains("Конечный заказчик: ZZ Горбольница №7");   // Word во вложении — с той же строкой
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.SENT);
        assertThat(priceRequests.findById(b.getPriceRequestId()).orElseThrow().getCreatedBy()).startsWith("zzbot").endsWith(", Telegram");
        assertThat(stub.requests()).extracting(r -> r.path().substring(r.path().lastIndexOf('/') + 1))
                .contains("deleteMessage", "editMessageReplyMarkup");
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void modelChanged_noLetter_backToDraftWithFreshItems() {
        MedEquipment other = equipment.saveAndFlush(MedEquipment.builder().name("ZZ Другая").manufact("ZZАксион").market(Market.KZ).build());
        models.apply(lot, other, null);
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.DRAFT);
        assertThat(b.getItems().get(0).equipmentId()).isEqualTo(other.getId());
        assertThat(stub.requests().get(stub.requests().size() - 1).body()).contains("модель лота изменилась");
    }

    @Test
    void sentFromAisMeanwhile_elsewhere() {
        TenderLot l = lots.findById(lot.getId()).orElseThrow();
        writer.persist(l.getTender(), maker, List.of(new PriceRequestWriter.Line(l, l.getProposedEquipment(), 1)));
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.ELSEWHERE);
    }

    @Test
    void closedTender_cancelled() {
        Tender t = lot.getTender();
        t.setDeadlineAt(LocalDateTime.now(Market.KZ.zone()).minusHours(1));
        tenders.saveAndFlush(t);
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.CANCELLED);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void badAddress_failed_thenResendSameRequest() {
        maker.setEmail("не адрес");
        distributors.saveAndFlush(maker);
        as(() -> actions.send(cb("s"), draft.getId()));
        BotLetter b = reload();
        assertThat(b.getStatus()).isEqualTo(BotLetterStatus.FAILED);
        Long prId = b.getPriceRequestId();
        assertThat(prId).isNotNull();
        maker.setEmail("zz-axion@example.kz");
        distributors.saveAndFlush(maker);
        as(() -> actions.send(cb("s"), draft.getId()));
        assertThat(reload().getStatus()).isEqualTo(BotLetterStatus.SENT);
        assertThat(reload().getPriceRequestId()).isEqualTo(prId);   // тот же запрос, не второй
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }
}
```
(Импорты — как у `PrivateRequestRfqStage2Test`: `jakarta.mail.Multipart`, `jakarta.mail.Part`, `jakarta.mail.internet.MimeMessage`, `org.apache.poi.xwpf.extractor.XWPFWordExtractor`, `org.apache.poi.xwpf.usermodel.XWPFDocument`, GreenMail; остальное — пакеты сущностей, репозиториев и сервисов.)

- [ ] **Step 2: Красный** (`--tests '…BotReplyByTest' --tests '…BotLetterActionsTest'`, `t11.log`).

- [ ] **Step 3: Реализация**

`RfqDocumentService.java`:
```java
    /** PDF и страницы картинками (не больше двух — понять, одна ли страница) — просмотр письма в боте (спека 2026-10-21 §7). */
    public record PreviewFiles(byte[] pdf, String pdfName, List<byte[]> pages) {}

    public PreviewFiles previewFiles(PriceRequest pr) {
        RfqFile f = render(pr, Format.PDF);
        return new PreviewFiles(f.bytes(), f.fileName(), previews.pages(f.bytes(), 2));
    }
```

`BotLetterStore.java` — добавить:
```java
    /** Взять письмо в отправку: DRAFT или FAILED → SENDING; одновременные нажатия — одно получит true. */
    @Transactional
    public boolean claim(long id) {
        return repo.transition(id, List.of(BotLetterStatus.DRAFT, BotLetterStatus.FAILED), BotLetterStatus.SENDING) == 1;
    }

    @Transactional
    public void finish(long id, BotLetterStatus status, Long priceRequestId, String by, String error) {
        repo.findById(id).ifPresent(b -> {
            b.setStatus(status);
            if (priceRequestId != null) b.setPriceRequestId(priceRequestId);
            if (status == BotLetterStatus.SENT) {
                b.setSentAt(OffsetDateTime.now());
                b.setSentBy(by);
            }
            b.setError(error == null ? null : TelegramText.safeCut(error, 300));
        });
    }

    @Transactional
    public void setPreview(long id, Long messageId) { repo.findById(id).ifPresent(b -> b.setPreviewMessageId(messageId)); }

    @Transactional
    public void updateItems(long id, List<BotLetterItem> items) {
        repo.findById(id).ifPresent(b -> b.setItems(new ArrayList<>(items)));
    }
```

`BotTexts.java` — добавить:
```java
    /** Подпись к фото бланка (≤ 1024 знаков после разбора разметки): номер письма, кому, тема, начало текста. */
    public static String caption(int n, int total, String supplier, boolean maker, String email, String subject, String body) {
        String head = "Письмо " + n + " из " + total + " · " + supplier + " (" + (maker ? "завод" : "доверенный") + ")\n"
                + "Кому: " + TelegramText.oneLine(email, 200) + "\n" + "Тема: " + TelegramText.oneLine(subject, 200) + "\n\n";
        String rest = body == null ? "" : body.strip();
        int room = 1000 - head.length();
        if (rest.length() > room) rest = TelegramText.safeCut(rest, Math.max(0, room - 1)) + "…";
        return TelegramText.html(head + rest);
    }

    public static String sent(String supplier, String user, String lots) {
        return TelegramText.html("✅ Письмо " + supplier + " отправлено · " + user + " (" + lots + ")");
    }

    public static String failed(String supplier, String why) {
        return TelegramText.html("⚠ Письмо " + supplier + " не ушло: " + why
                + " — нажмите «✅ Отправить» ещё раз (уйдёт тот же запрос) или откройте в АИС");
    }
```

`BotLetterActions.java`:
```java
package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.*;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.util.CurrentUser;
import com.vladoose.nir.util.ErrorText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Письма из бота (спека 2026-10-21-tender-telegram-bot §7): «👁 N» — фото бланка (2+ страниц — PDF) с «⬅ Вернуться ·
 * ✅ Отправить · ✏️ Открыть в АИС»; «✅ Отправить» — тот же путь, что панель «КП» (письмо, бланк, клиент, копия в
 * «Отправленные»), после проверок: приём идёт, модель та же, из АИС тем временем не отправляли. Повтор после сбоя почты —
 * «↻ Переслать» того же запроса.
 */
@Component
public class BotLetterActions {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final TelegramBotApi api;
    private final BotLetterStore store;
    private final PriceRequestSendService sendService;
    private final RfqDocumentService documents;
    private final DistributorService distributors;
    private final TenderService tenders;
    private final TenderLotRepository lots;
    private final PriceRequestRepository priceRequests;
    private final CompanyProfileService profiles;
    private final String base;

    public BotLetterActions(TelegramBotApi api, BotLetterStore store, PriceRequestSendService sendService, RfqDocumentService documents,
                            DistributorService distributors, TenderService tenders, TenderLotRepository lots,
                            PriceRequestRepository priceRequests, CompanyProfileService profiles,
                            @Value("${ais.public-url:}") String publicUrl) {
        this.api = api;
        this.store = store;
        this.sendService = sendService;
        this.documents = documents;
        this.distributors = distributors;
        this.tenders = tenders;
        this.lots = lots;
        this.priceRequests = priceRequests;
        this.profiles = profiles;
        this.base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
    }

    public void view(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (b.getStatus() != BotLetterStatus.DRAFT && b.getStatus() != BotLetterStatus.FAILED) {
            api.answerCallback(c.id(), statusText(b), false);
            return;
        }
        api.answerCallback(c.id(), "Собираю письмо…", false);
        Distributor d = distributors.findById(b.getDistributorId());
        Tender t = tenders.findPublic(b.getTenderId());
        PriceRequest draft = sendService.draft(b.getTenderId(), d, items(b), options(t));
        KpPreviewResponse letter = sendService.preview(draft);
        RfqDocumentService.PreviewFiles files = documents.previewFiles(draft);
        String caption = BotTexts.caption(b.getLineNo(), store.list(b.getListMessageId()).size(), d.getName(), d.isManufacturer(),
                d.getEmail(), letter.getSubject(), letter.getBody());
        if (b.getPreviewMessageId() != null) api.deleteMessage(b.getPreviewMessageId());
        long mid = files.pages().size() == 1
                ? api.sendPhoto("Запрос — " + TelegramText.oneLine(d.getName(), 60) + ".png", files.pages().get(0), caption,
                        previewKeyboard(b), b.getListMessageId())
                : api.sendDocument(files.pdfName(), files.pdf(), "application/pdf", caption, previewKeyboard(b), b.getListMessageId());
        store.setPreview(id, mid);
    }

    public void back(BotUpdate.Callback c, long id) {
        api.answerCallback(c.id(), null, false);
        store.find(id).filter(b -> b.getPreviewMessageId() != null).ifPresent(b -> {
            api.deleteMessage(b.getPreviewMessageId());
            store.setPreview(id, null);
        });
    }

    public void send(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (!store.claim(id)) {
            api.answerCallback(c.id(), b.getStatus() == BotLetterStatus.SENDING ? "Уже отправляется…" : statusText(b), false);
            return;
        }
        api.answerCallback(c.id(), "Отправляю…", false);
        try {
            String problem = check(b);
            if (problem != null) {
                api.sendMessage(TelegramText.html(problem), true, null, b.getListMessageId());
                return;
            }
            Distributor d = distributors.findById(b.getDistributorId());
            SendResult r = b.getPriceRequestId() != null
                    ? sendService.resend(b.getPriceRequestId())
                    : sendService.send(b.getTenderId(), List.of(b.getDistributorId()), items(b), null, null,
                            options(tenders.findPublic(b.getTenderId()))).get(0);
            if (r.emailSent()) {
                store.finish(id, BotLetterStatus.SENT, r.priceRequestId(), CurrentUser.label(), null);
                if (b.getPreviewMessageId() != null) {
                    api.deleteMessage(b.getPreviewMessageId());
                    store.setPreview(id, null);
                }
                api.sendMessage(BotTexts.sent(d.getName(), CurrentUser.name(), lotNames(b)), true, null, b.getListMessageId());
            } else {
                String why = PriceRequestSendService.REASON_NO_EMAIL.equals(r.reason()) ? "у поставщика нет почты" : "почта не приняла письмо";
                store.finish(id, BotLetterStatus.FAILED, r.priceRequestId(), null, why);
                api.sendMessage(BotTexts.failed(d.getName(), why), true, null, b.getListMessageId());
            }
        } catch (RuntimeException e) {
            store.finish(id, BotLetterStatus.FAILED, null, null, ErrorText.of(e));
            throw e;
        } finally {
            if (b.getListMessageId() != null) api.editKeyboard(b.getListMessageId(), BotTexts.listKeyboard(store.list(b.getListMessageId())));
        }
    }

    /** null — можно отправлять; иначе — причина, а статус письма уже переведён. */
    private String check(BotLetter b) {
        Tender t = tenders.findPublic(b.getTenderId());
        if (!"ACTIVE".equals(t.getStatus()) || ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), LocalDateTime.now(Market.KZ.zone()))) {
            store.finish(b.getId(), BotLetterStatus.CANCELLED, null, null, "приём закрыт");
            return "Приём по тендеру закончился — письмо не отправлено";
        }
        List<BotLetterItem> fresh = new ArrayList<>();
        boolean changed = false;
        for (BotLetterItem it : b.getItems()) {
            TenderLot l = lots.findById(it.lotId()).orElseThrow();
            Long eq = l.getProposedEquipment() == null ? null : l.getProposedEquipment().getId();
            if (!Objects.equals(eq, it.equipmentId())) changed = true;
            fresh.add(new BotLetterItem(l.getId(), eq, l.getQuantity()));
        }
        if (changed) {
            store.updateItems(b.getId(), fresh);
            store.finish(b.getId(), BotLetterStatus.DRAFT, null, null, null);
            return "Модель лота изменилась — посмотрите письмо заново (👁)";
        }
        if (b.getPriceRequestId() == null) {
            OffsetDateTime last = priceRequests.lastCreatedAt(b.getTenderId(), b.getDistributorId(),
                    b.getItems().stream().map(BotLetterItem::lotId).toList());
            if (last != null && last.isAfter(b.getCreatedAt())) {
                store.finish(b.getId(), BotLetterStatus.ELSEWHERE, null, null, null);
                return "Уже отправлено из АИС в " + last.atZoneSameInstant(Market.KZ.zone()).format(HM);
            }
        }
        return null;
    }

    private SendOptions options(Tender t) {
        LocalDate deadline = t.getDeadline() != null ? t.getDeadline() : t.getDeadlineAt() == null ? null : t.getDeadlineAt().toLocalDate();
        boolean stamp = profiles.forMarket(Market.KZ).getStampPng() != null;
        return new SendOptions(replyBy(deadline, LocalDate.now(Market.KZ.zone())), true, stamp, true, null);
    }

    /** «Ответить до» (спека §7): за 2 дня до конца приёма, не раньше завтра и не позже дня окончания; срока нет — через 3 дня. */
    public static LocalDate replyBy(LocalDate deadline, LocalDate today) {
        if (deadline == null) return today.plusDays(3);
        LocalDate d = deadline.minusDays(2);
        if (d.isBefore(today.plusDays(1))) d = today.plusDays(1);
        if (d.isAfter(deadline)) d = deadline;
        return d.isBefore(today) ? today : d;
    }

    private static List<SendItem> items(BotLetter b) {
        return b.getItems().stream().map(i -> new SendItem(i.lotId(), i.equipmentId(), i.qty())).toList();
    }

    private String lotNames(BotLetter b) {
        return String.join(", ", b.getItems().stream().map(i -> lots.findById(i.lotId()).map(TenderLot::getEquipName).orElse("лот"))
                .map(n -> TelegramText.oneLine(n, 60)).toList());
    }

    private List<List<Button>> previewKeyboard(BotLetter b) {
        List<List<Button>> kb = new ArrayList<>();
        kb.add(List.of(Button.cb("⬅ Вернуться", "l:" + b.getId() + ":b"), Button.cb("✅ Отправить", "l:" + b.getId() + ":s")));
        if (base != null && !b.getItems().isEmpty()) {
            kb.add(List.of(Button.link("✏️ Открыть в АИС", base + "/tenders?openId=" + b.getTenderId() + "&lot="
                    + b.getItems().get(0).lotId() + "&kp=" + b.getDistributorId() + "&market=KZ")));
        }
        return kb;
    }

    private static String statusText(BotLetter b) {
        return switch (b.getStatus()) {
            case SENT -> "Письмо уже отправлено";
            case ELSEWHERE -> "Уже отправлено из АИС";
            case CANCELLED -> "Приём закрыт — письмо не отправлялось";
            case SENDING -> "Уже отправляется…";
            default -> "Письмо в работе";
        };
    }
}
```
(`PriceRequestSendService.REASON_NO_EMAIL` — уже `public static final`.)

`BotUpdateRouter` — в конструктор `BotLetterActions letters`; в `switch` маршруты:
```java
                case "l:v" -> letters.view(c, target);
                case "l:s" -> letters.send(c, target);
                case "l:b" -> letters.back(c, target);
                case "l:x" -> api.answerCallback(c.id(), "Письмо уже обработано", false);
```
`BotUpdateRouterTest` — в сборку роутера добавить `new BotLetterActions(...)` (те же бины, свой `api`).

- [ ] **Step 4: Зелёный** (`--tests 'com.vladoose.nir.tgbot.*'`).

- [ ] **Step 5: Мутации** — (а) `claim` без условия статуса (всегда true) → краснеет «второе нажатие»; (б) без проверки модели → краснеет `modelChanged…`; (в) без проверки «из АИС» → краснеет `sentFromAisMeanwhile…`; (г) повтор после сбоя через `send` вместо `resend` → краснеет «тот же запрос»; (д) `discloseClient=false` в `options` → краснеет строка «Конечный заказчик».

- [ ] **Step 6: Коммит** — `feat(tg-bot): письма из бота — фото бланка, «✅ Отправить» с проверками, «⬅ Вернуться»`.

---

### Task 12: Фронт — вывод ИИ у лота, панель «КП» (клиент, завод, переход), пачка и «✍️ Вставить ответ ИИ», «Пользователи»

**Files:**
- Create: `frontend/src/app/shared/ai-answer.ts`
- Create: `frontend/src/app/pages/tenders/ai-answer-dialog.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`, `tender-lots.component.ts`, `lot-kp-panel.component.ts`

**Interfaces:**
- Consumes: `POST /api/tenders/ai-answer` (задача 6), `TenderLotResponse.ai*` (задача 6), `discloseClientIds` и причина `MAKER` (задача 7).
- Produces: `aiLotLine(l): string`, типы `AiAnswerResult` / `AiLotResult` / `AiLetterPlan`; `ApiService.postAiAnswer(text): Observable<AiAnswerResult>`; `app-ai-answer-dialog` (выходы `closed`, `applied`); `app-tender-lots` входы `focusLotId`, `focusKpId`, выход `focusUsed`; `app-lot-kp-panel` вход `preselectId`.

- [ ] **Step 1: Типы и строка вывода ИИ** — `shared/ai-answer.ts`:
```ts
export type AiVerdict = 'GO' | 'NO' | 'UNSURE';
export type AiOutcome = 'PINNED' | 'SAME_MODEL' | 'KEPT_MANUAL' | 'ADVICE' | 'NOT_OURS' | 'UNSURE' | 'CLOSED'
  | 'LOT_NOT_FOUND' | 'PRODUCT_NOT_CANDIDATE' | 'PRODUCT_REMOVED';
export interface AiLotResult { lotId: number; tenderId: number | null; tenderNumber: string | null; lotName: string | null;
  customer: string | null; verdict: AiVerdict; outcome: AiOutcome; model: string | null; text: string; }
export interface AiLetterPlan { tenderId: number; tenderNumber: string | null; customer: string | null; distributorId: number;
  distributorName: string; manufacturer: boolean; lotIds: number[]; lotNames: string[]; }
export interface AiAnswerResult { lots: AiLotResult[]; unparsed: string[]; letters: AiLetterPlan[]; notes: string[]; }

/**
 * Строка вывода ИИ у лота (спека 2026-10-21-tender-telegram-bot §10 п. 1): «ИИ: идти — ДКИ-Н-12 · почему · vlad, Telegram,
 * 21.10», «не наше: …», «не ясно: …»; значок 🤖 ставит шаблон.
 */
export function aiLotLine(l: { aiVerdict?: AiVerdict | null; aiProductName?: string | null; aiNote?: string | null;
                               aiBy?: string | null; aiAt?: string | null }): string {
  const day = l.aiAt ? new Date(l.aiAt).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', timeZone: 'Asia/Oral' }) : null;
  const who = [l.aiBy, day].filter(Boolean).join(', ');
  const why = l.aiNote || '';
  if (l.aiVerdict === 'GO') {
    return ['ИИ: идти' + (l.aiProductName ? ' — ' + shortProduct(l.aiProductName) : ''), why, who].filter(s => !!s).join(' · ');
  }
  const head = l.aiVerdict === 'NO' ? 'не наше' : 'не ясно';
  return (why ? head + ': ' + why : head) + (who ? ' · ' + who : '');
}
```
(в начале файла — `import { shortProduct } from './product-watch';`: тот модуль и так в начальном бандле — его берёт страница «Тендеры».)
`api.service.ts`: `import type { AiAnswerResult } from '../shared/ai-answer';`, в типы тела `sendPriceRequests(...)` и `previewKp(...)` — поле `discloseClientIds?: number[];` и метод
```ts
  postAiAnswer(text: string): Observable<AiAnswerResult> {
    return this.http.post<AiAnswerResult>(`${this.base}/tenders/ai-answer`, { text });
  }
```

- [ ] **Step 2: Окно «✍️ Вставить ответ ИИ»** — `ai-answer-dialog.component.ts`:
```ts
import { ChangeDetectorRef, Component, EventEmitter, HostListener, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import type { AiAnswerResult } from '../../shared/ai-answer';

/** Ответ ИИ с компьютера (спека 2026-10-21-tender-telegram-bot §10 п. 3): поле → «Разобрать» → итог и «КП ›» по письмам. */
@Component({
  selector: 'app-ai-answer-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div class="aa-veil" (click)="close()"></div>
    <div class="aa-dlg" role="dialog" aria-modal="true" aria-label="Ответ ИИ">
      <h3 class="aa-title">✍️ Ответ ИИ</h3>
      <ng-container *ngIf="!result">
        <p class="aa-hint">Вставьте блок «Для АИС» из конца ответа ИИ — можно весь ответ, АИС найдёт блок сама.</p>
        <textarea class="aa-text" [(ngModel)]="text" [disabled]="busy" placeholder="L5826 | идти | P123 | почему"></textarea>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
        <div class="aa-actions">
          <button type="button" class="btn" (click)="close()" [disabled]="busy">Отмена</button>
          <button type="button" class="btn btn-primary" (click)="apply()" [disabled]="busy || !text.trim()">{{ busy ? 'Разбираю…' : 'Разобрать' }}</button>
        </div>
      </ng-container>
      <ng-container *ngIf="result">
        <ul class="aa-list"><li *ngFor="let l of result.lots">{{ l.text }}</li></ul>
        <p class="aa-warn" *ngIf="result.unparsed.length">⚠ Не понял строк: {{ result.unparsed.length }} — «{{ result.unparsed[0] }}»</p>
        <p class="aa-note" *ngFor="let n of result.notes">• {{ n }}</p>
        <h4 class="aa-sub">📨 Письма к отправке</h4>
        <p class="aa-note" *ngIf="!result.letters.length">Писем к отправке нет</p>
        <a class="aa-letter" *ngFor="let p of result.letters" [routerLink]="['/tenders']"
           [queryParams]="{ openId: p.tenderId, lot: p.lotIds[0], kp: p.distributorId }" (click)="close()">
          КП › {{ p.distributorName }} ({{ p.manufacturer ? 'завод' : 'доверенный' }}) — {{ p.lotNames.join(', ') }}</a>
        <div class="aa-actions"><button type="button" class="btn btn-primary" (click)="close()">Готово</button></div>
      </ng-container>
    </div>
  `,
  styles: [`
    .aa-veil { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; }
    .aa-dlg { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); width: min(680px, calc(100vw - 32px));
              max-height: calc(100vh - 32px); overflow: auto; display: flex; flex-direction: column; gap: 10px; padding: 16px;
              background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 10px;
              box-shadow: var(--shadow-lg); z-index: 1101; }
    .aa-title { margin: 0; font-size: 17px; }
    .aa-sub { margin: 6px 0 0; font-size: 14px; }
    .aa-hint, .aa-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .aa-warn { margin: 0; font-size: 13px; color: var(--warn-text); }
    .aa-text { min-height: 180px; width: 100%; box-sizing: border-box; padding: 8px; font: 13px/1.4 monospace; color: var(--text);
               background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; resize: vertical; }
    .aa-list { margin: 0; padding-left: 18px; font-size: 14px; display: flex; flex-direction: column; gap: 4px; }
    .aa-letter { display: block; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--accent);
                 text-decoration: none; font-size: 14px; }
    .aa-letter:hover { background: var(--surface-2); }
    .aa-actions { display: flex; justify-content: flex-end; gap: 8px; }
    @media (max-width: 900px) {
      .aa-dlg { inset: 0; top: 0; left: 0; transform: none; width: 100vw; max-height: none; height: 100vh; border-radius: 0; }
      .aa-text { font-size: 16px; }
      .aa-letter { min-height: 40px; }
    }
  `],
})
export class AiAnswerDialogComponent {
  @Output() closed = new EventEmitter<void>();
  @Output() applied = new EventEmitter<void>();
  text = '';
  busy = false;
  error: string | null = null;
  result: AiAnswerResult | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  @HostListener('document:keydown.escape')
  onEsc() { this.close(); }

  apply() {
    if (this.busy || !this.text.trim()) return;
    this.busy = true;
    this.error = null;
    this.api.postAiAnswer(this.text).subscribe({
      next: r => { this.busy = false; this.result = r; this.applied.emit(); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.error = e?.error?.message || 'Не удалось разобрать ответ'; this.cdr.markForCheck(); },
    });
  }

  close() { if (!this.busy) this.closed.emit(); }
}
```

- [ ] **Step 3: Список «Тендеры» и карточка** — `tenders.component.ts`:
- поля `filterIds: number[] | null = null; aiAnswerOpen = false; focusLotId: number | null = null; focusKpId: number | null = null;`;
- в конструкторе рядом с `our`:
```ts
    // ?ids= — пачка из Telegram (спека 2026-10-21 §4): только эти тендеры; ставится один раз и убирается из адреса
    const ids = this.route.snapshot.queryParamMap.get('ids');
    if (ids) this.filterIds = ids.split(',').map(Number).filter(n => n > 0);
    if (ids !== null) setTimeout(() => this.router.navigate([], { relativeTo: this.route, queryParams: { ids: null }, queryParamsHandling: 'merge', replaceUrl: true }));
```
- подписка на адрес: `.subscribe(p => { this.focusLotId = Number(p.get('lot')) || null; this.focusKpId = Number(p.get('kp')) || null; this.syncOpen(Number(p.get('openId')) || null); });`;
- `clearFocus()`: `this.focusLotId = null; this.focusKpId = null; this.router.navigate([], { relativeTo: this.route, queryParams: { lot: null, kp: null }, queryParamsHandling: 'merge', replaceUrl: true });`;
- в `applyTendersFilter` первой проверкой: `if (this.filterIds && !this.filterIds.includes(t.id)) return false;`; `aiIds` — `(this.filterOur || this.filterIds) ? …` с условием `(this.filterIds ? true : this.filterOur === 'PINNED' ? t.modelsInWork?.length : t.ourProduct) && !deadlinePassed(t, market)`;
- `clearIds()`: `this.filterIds = null; this.applyTendersFilter(); this.cdr.detectChanges();`;
- `onAiApplied()`: `this.loadTenders(); if (this.selectedTender) this.loadLots();` (оба метода уже есть — список и лоты открытой карточки);
- шаблон над списком (рядом с `app-ai-brief-button`):
```html
        <app-ai-brief-button *ngIf="(filterOur || filterIds) && aiIds.length" [ids]="aiIds" [label]="'📋 Для ИИ: ' + aiIds.length + ' ' + aiWord()"></app-ai-brief-button>
        <button type="button" class="btn ai-answer-btn" *ngIf="isKz() && auth.isAdmin() && (filterOur || filterIds)" (click)="aiAnswerOpen = true">✍️ Вставить ответ ИИ</button>
```
и плашка перед карточками:
```html
      <div class="pack-plaque" *ngIf="filterIds">Пачка из Telegram: {{ filterIds.length }} {{ tendersWordOf(filterIds.length) }} ·
        <button type="button" class="btn btn-line" (click)="clearIds()">Показать все</button></div>
```
(`tendersWordOf(n)` — тот же `tendersWord`, что у кнопки);
- в шапке карточки рядом с `app-ai-brief-button [tenderId]`: `<button type="button" class="btn ai-answer-btn" *ngIf="isKz() && auth.isAdmin() && selectedTender.ourProduct" (click)="aiAnswerOpen = true">✍️ Ответ ИИ</button>`;
- в конце шаблона: `@defer (when aiAnswerOpen) { <app-ai-answer-dialog *ngIf="aiAnswerOpen" (closed)="aiAnswerOpen = false" (applied)="onAiApplied()"></app-ai-answer-dialog> }` и `AiAnswerDialogComponent` в `imports`;
- `app-tender-lots`: `[focusLotId]="focusLotId" [focusKpId]="focusKpId" (focusUsed)="clearFocus()"`;
- стили: `.ai-answer-btn { padding: 4px 12px; font-size: 13px; }` и `.pack-plaque { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border)); font-size: 14px; }`.

- [ ] **Step 4: Лот** — `tender-lots.component.ts`:
- `import { aiLotLine } from '../../shared/ai-answer';`; входы `@Input() focusLotId: number | null = null; @Input() focusKpId: number | null = null; @Output() focusUsed = new EventEmitter<void>();`; поля `kpPreselectId: number | null = null; aiOpen = new Set<number>();`;
- в шаблоне после блока `.lot-model` (виден и у свёрнутого):
```html
          <div class="lot-ai" *ngIf="l.aiVerdict" [class.lot-ai-open]="aiOpen.has(l.id)" [class.lot-ai-no]="l.aiVerdict === 'NO'"
               (click)="toggleAi(l, $event)" [title]="aiOpen.has(l.id) ? '' : 'Показать вывод ИИ целиком'">🤖 {{ aiLine(l) }}</div>
```
- методы: `aiLine(l: any) { return aiLotLine(l); }`, `toggleAi(l: any, e: Event) { e.stopPropagation(); this.aiOpen.has(l.id) ? this.aiOpen.delete(l.id) : this.aiOpen.add(l.id); }`;
- в `ngOnChanges` (после ресинка лотов):
```ts
    // переход из бота / окна ответа ИИ (?lot=&kp=): раскрыть лот и открыть «КП» с этим поставщиком (спека §7, §10 п. 2)
    if ((ch['lots'] || ch['focusLotId'] || ch['focusKpId']) && this.focusLotId != null) {
      const lot = this.lots.find((l: any) => l.id === this.focusLotId);
      if (lot) {
        this.kpPreselectId = this.focusKpId;
        if (this.focusKpId != null) this.openKp([lot]); else this.expandedLotId = lot.id;
        setTimeout(() => this.focusUsed.emit());
      }
    }
```
- обе вставки `app-lot-kp-panel` — `[preselectId]="kpPreselectId"`, а в `(close)` — `kpLots = []; kpPreselectId = null`;
- стили: `.lot-ai { margin-top: 4px; font-size: 13px; color: var(--text); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; cursor: pointer; }`, `.lot-ai-open { display: block; }`, `.lot-ai-no { color: var(--text-muted); }`.

- [ ] **Step 5: Панель «КП»** — `lot-kp-panel.component.ts`:
- `@Input() preselectId: number | null = null;`;
- в `load()` при сборке `entries`: `_checked: this.preselectId != null ? e.distributor?.id === this.preselectId : !!e.preselect, _client: !!(e.distributor?.manufacturer || e.distributor?.trusted)`; если отмеченный по `preselectId` оказался в `_nonrel` — `_showNonrel: true`;
- каждую `<label class="sup">` обернуть в `<ng-container *ngFor=…>` и после неё:
```html
        <div class="sup-client" *ngIf="e._checked">
          <label><input type="checkbox" [(ngModel)]="e._client" [ngModelOptions]="{standalone: true}" />
            👤 Указать клиента — больница в письме и на бланке</label>
        </div>
```
- чип причины: `{{ r.kind === 'TYPE' ? '✓ ' : r.kind === 'MAKER' ? '' : 'возит ' }}{{ r.label }}` и `[class.reason-maker]="r.kind === 'MAKER'"`;
- `sendKpRequests()`: `const discloseClientIds = this.checkedSuppliers().filter((e: any) => e._client).map((e: any) => e.distributor.id);` — запрос превью `this.api.previewKp({ tenderId, distributorIds, discloseClientIds, items, ...this.options() })`; в ответе
```ts
        this.preview = { subject: p.subject, body: p.body, originalSubject: p.subject, originalBody: p.body, sending: false,
          distributorIds, items, discloseClientIds,
          sample: discloseClientIds.includes(distributorIds[0]),   // образец предпросмотра — первый поставщик
          mixed: discloseClientIds.length > 0 && discloseClientIds.length < distributorIds.length };
```
- `confirmSendKp()` — целиком:
```ts
  confirmSendKp() {
    const tenderId = this.tenderId;
    const p = this.preview;
    if (!tenderId || !p) return;
    p.sending = true;
    // текст не правили — каждому поставщику свой (приветствие, строка о клиенте); правили — правка только поставщикам
    // того же вида, что образец (с клиентом / без), остальным — стандартный текст: строка о больнице не уйдёт дилеру
    const edited = p.subject !== p.originalSubject || p.body !== p.originalBody;
    const withClient = new Set<number>(p.discloseClientIds);
    const base = { tenderId, items: p.items, ...this.options() };
    const calls: Observable<any[]>[] = [];
    if (!edited) {
      calls.push(this.api.sendPriceRequests({ ...base, distributorIds: p.distributorIds, discloseClientIds: [...withClient] }));
    } else {
      const same = p.distributorIds.filter((id: number) => withClient.has(id) === p.sample);
      const other = p.distributorIds.filter((id: number) => withClient.has(id) !== p.sample);
      calls.push(this.api.sendPriceRequests({ ...base, distributorIds: same, discloseClientIds: p.sample ? same : [],
        subjectOverride: this.subjectHuman(p.subject), bodyOverride: p.body }));
      if (other.length) calls.push(this.api.sendPriceRequests({ ...base, distributorIds: other, discloseClientIds: p.sample ? [] : other }));
    }
    forkJoin(calls).subscribe({
      next: (rs) => {
        const t = kpToastFromResults(rs.flat());
        if (t.isError) this.notify.error(t.message); else this.notify.success(t.message);
        this.preview = null; this.panel = null;
        this.sent.emit();
        this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.preview) this.preview.sending = false;
        this.notify.error('Ошибка отправки: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }
```
(импорт `forkJoin`, `Observable` из `rxjs`); в превью-окне после первой пометки — `<p class="kp-preview-note" *ngIf="preview.mixed">Поставщикам с клиентом и без него уходят разные тексты: правка ниже — только для писем «{{ preview.sample ? 'с клиентом' : 'без клиента' }}», остальным — стандартный текст.</p>`;
- стили: `.sup-client { margin: -4px 0 6px 30px; font-size: 13px; }`, `.reason-maker { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }`.

- [ ] **Step 6: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -20`
Expected: сборка без ошибок; начальный бандл ≤ 1,5 МБ (окно ответа ИИ — отдельный ленивый чанк: `grep -l "Разобрать" dist/*/browser/chunk-*.js`).

- [ ] **Step 7: Коммит** — `feat(tg-bot): фронт — вывод ИИ у лота, «Указать клиента» и завод в «КП», пачка из Telegram, «✍️ Вставить ответ ИИ», Telegram ID`.

---

### Task 13: Заглушка Bot API, живая проверка, документация, ревью ветки

**Files:**
- Modify: `scripts/telegram-stub.mjs` (кнопки, файлы, обновления)
- Modify: `CLAUDE.md` (§5, §7, §8, §12, §13, §15, §16), `DEPLOY.md` (§11), `docs/PROGRESS.md` («▶ Последняя задача»)

- [ ] **Step 1: Заглушка** — `scripts/telegram-stub.mjs` целиком (тело — `Buffer`: файлы бывают двоичными):
```js
// Заглушка Telegram Bot API для живой проверки (слушает только 127.0.0.1:7709): уведомления, кнопки бота, файлы, нажатия.
// GET /__messages — что пришло; GET /__files/<n> — файл n как есть; POST /__press {from, message_id, data} — нажатие кнопки;
// POST /__reply {from, reply_to, text} — ответ человека на сообщение бота; POST /__fail/<код>[?retry_after=N] — следующий
// ответ (кроме getUpdates) с этим кодом. Токен из пути НЕ печатается.
import http from 'node:http';

const BOT_ID = 7000000001;
const CHAT = -1001;
const messages = [];   // всё, что прислал бэкенд (с кнопками, файлами, отметкой «удалено»)
const files = [];      // байты файлов: /__files/<index>
const updates = [];    // очередь getUpdates
const waiters = [];    // висящие long-poll
let nextMessageId = 1;
let nextUpdateId = 1;
let fail = null;

const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
const ok = (res, result) => json(res, 200, { ok: true, result });
const parseJson = (buf) => { try { const m = JSON.parse(buf.toString('utf8') || '{}'); return m && typeof m === 'object' ? m : null; } catch { return null; } };

function multipart(buf, boundary) {
  const fields = {};
  let file = null;
  const sep = Buffer.from('--' + boundary);
  let pos = buf.indexOf(sep);
  while (pos !== -1) {
    const start = pos + sep.length;
    if (buf.subarray(start, start + 2).toString() === '--') break;
    const next = buf.indexOf(sep, start);
    if (next === -1) break;
    const part = buf.subarray(start + 2, next - 2);
    const headEnd = part.indexOf('\r\n\r\n');
    const head = part.subarray(0, headEnd).toString('utf8');
    const data = part.subarray(headEnd + 4);
    const name = /name="([^"]+)"/.exec(head)?.[1];
    const filename = /filename="([^"]*)"/.exec(head)?.[1];
    if (filename !== undefined) file = { name: filename, type: /Content-Type: ([^\r\n]+)/i.exec(head)?.[1] || 'application/octet-stream', bytes: data };
    else if (name) fields[name] = data.toString('utf8');
    pos = next;
  }
  return { fields, file };
}

function buttons(markup) {
  const m = typeof markup === 'string' ? parseJson(Buffer.from(markup)) : markup;
  return (m?.inline_keyboard || []).map(row => row.map(b => b.text + ' → ' + (b.callback_data ?? b.url)));
}

function push(update) {
  updates.push({ update_id: nextUpdateId++, ...update });
  while (waiters.length) waiters.shift()();
}

function getUpdates(req, res) {
  const offset = Number(req.offset ?? 0);
  const timeout = Math.min(Number(req.timeout ?? 0), 25);
  if (offset === -1) return ok(res, updates.slice(-1));
  while (updates.length && updates[0].update_id < offset) updates.shift();   // подтверждённые
  if (updates.length || timeout === 0) return ok(res, updates.slice());
  const timer = setTimeout(() => { const i = waiters.indexOf(wake); if (i >= 0) waiters.splice(i, 1); ok(res, []); }, timeout * 1000);
  const wake = () => { clearTimeout(timer); ok(res, updates.filter(u => u.update_id >= offset)); };
  waiters.push(wake);
}

function bot(method, body, contentType, res) {
  if (method === 'getUpdates') return getUpdates(parseJson(body) || {}, res);
  if (fail) {
    const { code, retryAfter } = fail;
    fail = null;
    const payload = { ok: false, error_code: code, description: 'stub failure ' + code };
    if (code === 429) payload.parameters = { retry_after: retryAfter };
    console.log(new Date().toISOString(), method, '→', code);
    return json(res, code, payload);
  }
  if (method === 'getMe') return ok(res, { id: BOT_ID, is_bot: true, username: 'stub_bot' });
  const boundary = /boundary=(.+)$/.exec(contentType || '')?.[1];
  const m = boundary ? multipart(body, boundary) : { fields: parseJson(body), file: null };
  if (!m.fields) return json(res, 400, { ok: false, error_code: 400, description: 'stub: bad json' });
  const f = m.fields;
  const at = new Date().toISOString();
  if (method === 'deleteMessage') {
    const msg = messages.find(x => x.id === Number(f.message_id));
    if (msg) msg.deleted = at;
    console.log(at, 'deleteMessage', f.message_id);
    return ok(res, true);
  }
  if (method === 'editMessageReplyMarkup' || method === 'editMessageText') {
    const msg = messages.find(x => x.id === Number(f.message_id));
    if (msg) { msg.buttons = buttons(f.reply_markup); if (f.text) msg.text = f.text; msg.edited = at; }
    console.log(at, method, f.message_id);
    return ok(res, true);
  }
  if (method === 'answerCallbackQuery') {
    messages.push({ at, method, text: f.text || '', alert: !!f.show_alert });
    console.log(at, 'answerCallbackQuery', f.show_alert ? '(окно)' : '', f.text || '');
    return ok(res, true);
  }
  const id = nextMessageId++;
  const replyTo = (typeof f.reply_parameters === 'string' ? parseJson(Buffer.from(f.reply_parameters)) : f.reply_parameters)?.message_id;
  const rec = { id, at, method, chat_id: f.chat_id, thread: f.message_thread_id, silent: String(f.disable_notification) === 'true',
    text: f.text ?? f.caption, buttons: buttons(f.reply_markup), replyTo, forceReply: !!f.reply_markup?.force_reply };
  if (m.file) { files.push(m.file); rec.file = { name: m.file.name, type: m.file.type, size: m.file.bytes.length, index: files.length - 1 }; }
  messages.push(rec);
  console.log(at, method, '#' + id, 'thread', rec.thread, rec.silent ? '(тихо)' : '(звук)', rec.file ? 'файл ' + rec.file.name : '');
  console.log((rec.text || '') + '\n' + rec.buttons.map(r => '  [' + r.join(' | ') + ']').join('\n') + '\n');
  return ok(res, { message_id: id });
}

http.createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method === 'GET' && url.pathname === '/__messages') return json(res, 200, messages);
    const file = url.pathname.match(/^\/__files\/(\d+)$/);
    if (req.method === 'GET' && file && files[Number(file[1])]) {
      const f = files[Number(file[1])];
      res.writeHead(200, { 'Content-Type': f.type });
      return res.end(f.bytes);
    }
    const failMatch = url.pathname.match(/^\/__fail\/(\d{3})$/);
    if (req.method === 'POST' && failMatch) {
      fail = { code: Number(failMatch[1]), retryAfter: Number(url.searchParams.get('retry_after') || 30) };
      return json(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url.pathname === '/__press') {
      const p = parseJson(body) || {};
      push({ callback_query: { id: 'cb' + nextUpdateId, from: { id: Number(p.from) }, message: { message_id: Number(p.message_id), chat: { id: CHAT } }, data: p.data } });
      return json(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url.pathname === '/__reply') {
      const p = parseJson(body) || {};
      push({ message: { message_id: nextMessageId++, chat: { id: CHAT }, from: { id: Number(p.from) }, text: p.text,
        reply_to_message: { message_id: Number(p.reply_to), from: { id: BOT_ID } } } });
      return json(res, 200, { ok: true });
    }
    const call = url.pathname.match(/^\/bot[^/]+\/(\w+)$/);
    if (req.method === 'POST' && call) return bot(call[1], body, req.headers['content-type'], res);
    res.writeHead(404);
    res.end();
  });
}).listen(7709, '127.0.0.1', () => console.log('Telegram stub: http://127.0.0.1:7709 (GET /__messages, POST /__press, /__reply)'));
```
Проверка заглушки до бэкенда: `node scripts/telegram-stub.mjs &` → `curl -s -X POST 127.0.0.1:7709/botX:Y/sendMessage -H 'Content-Type: application/json' -d '{"chat_id":-1001,"text":"т","reply_markup":{"inline_keyboard":[[{"text":"a","callback_data":"d:1:ai"}]]}}'` → `{"ok":true,…}`; `curl -s 127.0.0.1:7709/__messages` — кнопка «a → d:1:ai»; `curl -s -X POST 127.0.0.1:7709/__press -d '{"from":111,"message_id":1,"data":"d:1:ai"}'` → `curl -s -X POST 127.0.0.1:7709/botX:Y/getUpdates -H 'Content-Type: application/json' -d '{"offset":0,"timeout":0}'` — нажатие в ответе.

- [ ] **Step 2: Локальный стенд** (sandbox off, всё в фоне; порты — по LISTEN):
```
./gradlew devMail
node scripts/telegram-stub.mjs
MAIL_HOST=127.0.0.1 MAIL_PORT=3025 MAIL_USERNAME=zakup@westmed.kz MAIL_PASSWORD=secret MAIL_SMTP_AUTH=true \
PRODUCT_WATCH_ENABLED=true PRODUCT_WATCH_CRON=- PRODUCT_WATCH_PLANS_CRON=- PRODUCT_WATCH_CATALOG_CRON=- PRODUCT_WATCH_NOTIFY=ALL \
TELEGRAM_ENABLED=true TELEGRAM_API_URL=http://127.0.0.1:7709 TELEGRAM_BOT_TOKEN=dev:token TELEGRAM_CHAT_ID=-1001 \
TELEGRAM_TENDERS_THREAD_ID=55 TELEGRAM_PRODUCTS_THREAD_ID=95 TELEGRAM_BOT_ACTIONS_ENABLED=true JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun
cd frontend && npm start
```
Записать ДО проверки: `select max(id) from price_request; select max(id) from deal_event; select max(id) from med_equipment;` и состояние тестовых лотов (`ai_*`, `proposed_equipment_id`, `model_*`), `product_notified_level` их тендеров.

- [ ] **Step 3: Живой сценарий** (заглушка + браузер 1280 / 390, обе темы; оператор; РФ):
  1. Админу вписать Telegram ID `111` в «Пользователях»; оператору — `222`.
  2. Открытому KZ-тендеру с отметкой (локальные 17733214-1 / 17729514-1 или вставленный) сбросить `product_notified_level` SQL → «Искать сейчас» (`POST /api/product-watch/run`) → в `/__messages` сообщение «🔎 Потенциально наших тендеров» с кнопками `d:<id>:ai`, `d:<id>:ans` и ссылкой `tenders?ids=…`.
  3. `/__press {from: 999, message_id: <id сообщения-пачки из /__messages>, data: "d:<id пачки>:ai"}` → `answerCallbackQuery` с «Ваш Telegram ID 999»; `from: 222` → «Нет доступа».
  4. `from: 111, d:<id>:ai` → `sendDocument` «Для ИИ — …txt»: в файле (`/__files/<n>`) коды `[L…]`, `[P…]` и просьба о блоке.
  5. `d:<id>:ans` → просьба с `force_reply`; `/__reply {from: 111, reply_to: <её id>, text: "L<lot> | идти | P<товар> | ТЗ совпадает"}` → сообщение-итог «✅ … — закреплено» и «👁 1»; в АИС у лота — «📌 Модель в работе», строка «🤖 ИИ: идти — …», в «Истории» — «модель в работе — … — по ответу ИИ · admin, Telegram».
  6. `👁 1` → `sendPhoto`; картинка (`/__files/<n>`) — бланк со строкой «Конечный заказчик: <больница>»; подпись «Письмо 1 из 1 · … (завод)».
  7. `l:<id>:s` → письмо в GreenMail у завода (`.docx`, строка о больнице), копия в «Sent» zakup@, фото удалено (`deleteMessage`), «✅ Письмо … отправлено · admin», кнопка «✅ 1»; повторное `l:<id>:s` — «Письмо уже отправлено», второго письма нет; «История» — «Запрос цен: письмом — … · admin, Telegram».
  8. «✏️ Открыть в АИС» (ссылка из фото) в браузере → карточка тендера, лот раскрыт, «КП» открыта, завод отмечен, «👤 Указать клиента» отмечено.
  9. Список «Тендеры» по ссылке `?ids=` → плашка «Пачка из Telegram», «📋 Для ИИ», «✍️ Вставить ответ ИИ» → окно → тот же блок с «не наше» → итог; лот — «🤖 не наше», следующий прогон — тендер в сообщение не приходит.
  10. Панель «КП» вручную: завод первым с чипом «завод» и отметкой, дилер без отметки; отправка заводу и дилеру с правкой текста → два запроса, дилеру без строки о больнице.
  11. «Пользователи» — поле Telegram ID: дубль — текст ошибки, пусто — снимается.
  12. Оператор: ни «✍️», ни кнопок записи, 0 запросов записи; РФ — кнопок нет. 390 px: окно ответа — во весь экран, цели 40 px; обе темы.
  13. `/__fail/429` перед нажатием → ответ приходит после паузы; бэкенд перезапустить с накопленными `/__press` → не выполняются.
- Найденное — исправлять с тестом, красным до правки (фронт — воспроизведение в браузере до и после).

- [ ] **Step 4: Уборка стенда** — СНАЧАЛА остановить бэкенд, потом SQL: вернуть записанное (модели, `ai_*`, `product_notified_level`), `delete from bot_letter; delete from product_digest; delete from price_request where id > <max>; delete from deal_event where id > <max>;`, Telegram ID учёток; остальное — по LISTEN-портам (7709, 3143, 4200).

- [ ] **Step 5: Полный прогон**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test > .superpowers/sdd/2026-10-21-tender-telegram-bot/full.log 2>&1; tail -40 .superpowers/sdd/2026-10-21-tender-telegram-bot/full.log`
Expected: 0 падений (skipped — прежние 6 инструментов калибровки).

- [ ] **Step 6: Ревью ветки самому** — `git diff main...HEAD` целиком свежим взглядом по Review Focus и спеке §2–§13; найденное — правка с тестом.

- [ ] **Step 7: Документация** — CLAUDE.md: §5 (локальный стенд кнопок бота — команда из шага 2, заглушка и её служебные пути, уборка), §7 (V41), §8 (блок «Наш тендер — с телефона за минуту»: механика, проверено), §12 (фронт), §13 (число тестов), §15 (`POST /api/tenders/ai-answer`, `discloseClientIds`, `telegramUserId`), §16 (ограничения и мелочи ревью; API Claude — в бэклог); DEPLOY.md §11 — раскатка: push → `PRODUCT_WATCH_NOTIFY=ALL` и `TELEGRAM_BOT_ACTIONS_ENABLED=true` в `/srv/ais/.env` → `docker compose up -d` → нажать кнопку → вписать Telegram ID оператора и отца → проверка; откат — `TELEGRAM_BOT_ACTIONS_ENABLED=false` (V41 — только новые колонки и таблицы, откат кода без SQL); первый прогон — сколько придёт и SQL «пометить сообщёнными»; PROGRESS — «▶ Последняя задача».

- [ ] **Step 8: Мерж** — `git checkout main && git merge --ff-only feature/tg-tender-bot && git branch -d feature/tg-tender-bot`; push — оператор (`! git push origin main`).
